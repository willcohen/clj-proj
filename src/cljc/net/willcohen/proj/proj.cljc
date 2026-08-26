;; Copyright (c) 2024, 2025, 2026 Will Cohen
;;
;; Part of clj-proj, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

#?(:clj
   (ns net.willcohen.proj.proj
     "The primary clj API for the JVM wrapper of PROJ."
     ;; Exclude clojure.core/await so the JVM identity macro below
     ;; resolves. squint's `await` is a parser-level special form, so the
     ;; :cljs ns needs no exclude.
     (:refer-clojure :exclude [await])
     (:require [net.willcohen.proj.impl.native :as native]
               [net.willcohen.proj.impl.logging :as proj-logging]
               [net.willcohen.proj.impl.network :as proj-network]
               [net.willcohen.native.platform-state :as nps]
               [net.willcohen.native.dispatch :as dispatch]
               [net.willcohen.native.ffi-mem :as ffi-mem]
               [tech.v3.resource :as resource]
               [tech.v3.datatype :as dt]
               [tech.v3.datatype.ffi.ptr-value :as dt-ptr]
               [tech.v3.datatype.native-buffer :as dt-nb]
               [tech.v3.tensor :as dt-t]
               [clojure.tools.logging :as log]
               [clojure.string :as string]
               [tech.v3.datatype.struct :as dt-struct]
               [net.willcohen.proj.impl.struct :as proj-struct]
               [net.willcohen.proj.wasm :as wasm]
               [net.willcohen.native.graal-wasm :as nw :refer [with-graal-lock]]
               [net.willcohen.native.workload-pool :as wp]
               [net.willcohen.proj.fndefs :as pdefs]
               [net.willcohen.proj.macros :refer [define-all-proj-public-fns]])
     (:import [java.io File]))
   :cljs
   (ns net.willcohen.proj.proj
     (:require [clojure.string :as string]
               ["ffi-wasm/platform-state" :as nps]
               ["ffi-wasm/pool" :as pool]
               ["ffi-wasm/dispatch" :as dispatch]
               [wasm :as wasm]
               [fndefs :as pdefs]
               [macros :refer [define-all-proj-public-fns]]
               ["./handler.mjs" :as handler]
               ["resource-tracker" :as resource])))

#?(:clj (set! *warn-on-reflection* true))

;; Identity macro so one shared form serves the two platforms wherever the
;; only difference is the await. squint compiles the :cljs side to a real
;; `await`; the JVM keeps the plain call. Without it, every such site is a
;; reader conditional whose two arms must stay in step by hand.
#?(:clj (defmacro await [body] body))

;; defonce because `lib` is a defonce and holds `implementation` by identity.
;; Refer to the platform-state namespace docstring in clj-native.
(defonce implementation (atom nil))
(defonce force-graal    (atom false))
(defn toggle-graal! [] (nps/toggle-graal! implementation force-graal))
(defn force-graal!
  "Select the GraalVM wasm backend for later calls.

   This sets the implementation atom only. It does not reset the GraalVM
   polyglot Context. Only a JVM restart can reset that Context. The loaded
   module in wasm/p also stays, because wasm/init-proj starts the module only
   when that atom is nil. Thus a REPL session that changes backends keeps one
   Context for its full life."
  [] (nps/force-graal! implementation force-graal))

(defn force-ffi!
  "Select the native FFI backend for later calls. Refer to force-graal! for
   what a backend change does not undo."
  [] (nps/force-ffi! implementation force-graal))

(defn ffi?   [] (nps/ffi?   implementation))
(defn graal? [] (nps/graal? implementation))
(defn node?  [] (nps/node?  implementation))

(def p #?(:clj nil
          :cljs wasm/p))

#?(:cljs
   (defn alloc-coord-array
     "Allocate a coordinate array as a JS-side Float64Array.
      proj_trans_array moves the data to the correct worker when necessary."
     [num-coords _worker-idx]
     (let [floats-needed (* num-coords 4)]
       #js {:buffer (js/Float64Array. floats-needed)
            :numCoords num-coords
            :floatsNeeded floats-needed
            :type "coord-array"})))

#?(:cljs
   (defn set-coord-array
     "Set coordinate values in the Float64Array buffer of a JS-side coord array."
     [coord-array allocated]
     (let [^js buf (.-buffer allocated)]
       (cond
         (and (array? coord-array)
              (every? number? coord-array))
         (.set buf coord-array 0)

         (and (array? coord-array)
              (array? (aget coord-array 0)))
         (loop [i 0
                off 0]
           (when (< i (.-length coord-array))
             (let [inner (aget coord-array i)
                   len (.-length inner)]
               (when (> (+ off len) (.-length buf))
                 (throw (js/RangeError. "coords exceed coord-array capacity")))
               (dotimes [j len]
                 (aset buf (+ off j) (aget inner j)))
               (recur (inc i) (+ off len)))))

         :else
         (.set buf (into-array (flatten coord-array)) 0))
       allocated)))

#?(:cljs
   (defn get-coord-array
     "Read coordinates from the Float64Array buffer of a JS-side coord array."
     [allocated idx]
     (let [buf (.-buffer allocated)
           offset (* idx 4)]
       #js [(aget buf offset)
            (aget buf (+ offset 1))
            (aget buf (+ offset 2))
            (aget buf (+ offset 3))])))

(defn init!
  "Initialize PROJ. In ClojureScript, returns a Promise that must be awaited.
   In Clojure, initializes synchronously and returns nil.

   opts is an optional map. Recognized keys (ClojureScript-only unless noted):
     :workers        :auto, integer, or 'auto' string (default :auto).
     :pool           caller-supplied worker-router WorkerPool ref. When
                     given, init! adopts the caller's pool and the caller
                     keeps its lifecycle.
     :log-level      integer 0..3 for the PROJ C-library logger
                     (default 0). When set, also gates init-time
                     progress messages on stdout/console.
     :max-live-ctxs  integer or nil. Hard cap on the set of live PJ
                     contexts that no JS owner holds. Default 128.
                     Increase it for workloads with a high peak live
                     count (for example, heavy maplibre-proj replays).
     :min-age-ms     integer or nil. Minimum age in milliseconds before
                     eviction (default 100). The pool must not evict a
                     PJ younger than this while ccalls are in flight.
     :debug-level / :debug-categories: refer to the wasm/init-workers!
                     docstring."
  ([] (init! nil))
  ([opts]
   (let [log-level (:log-level opts)]
     #?(:clj
        (do
          (when log-level (println "Attempting to initialize PROJ library..."))
          (nps/try-init! implementation force-graal (some? log-level)
                         native/init-proj
                         wasm/init-proj)
          (when log-level (println (str "PROJ library initialized with " (name @implementation) " implementation.")))
          nil)
        :cljs
        (do
          (when log-level (js/console.log "Attempting to initialize PROJ library for ClojureScript..."))
          (let [runtime (cond
                          (and (exists? js/process)
                               (exists? js/process.versions)
                               (exists? js/process.versions.node)) :node
                          (exists? js/window) :browser
                          :else :unknown)]
            (when log-level (js/console.log (str "Detected runtime: " runtime)))
            (let [init-promise (wasm/init-proj opts)]
              (.then init-promise
                     (fn [proj-module]
                       (reset! implementation runtime)
                       (when log-level
                         (js/console.log (str "PROJ initialized with " runtime " implementation")))
                       proj-module))
              init-promise)))))))

#?(:cljs
   (defn shutdown!
     "Stop all workers and release resources. Returns a Promise.
      Call it so the Node.js process can exit."
     []
     (wasm/shutdown!)))

#?(:cljs
   (defn flush-pending-disposes!
     "Drain in-flight async disposers from clj-native's wrapped tracker
      (Promise returns from PJ destroy and context destroy messages).
      Returns a Promise that resolves to Promise.allSettled results.
      Tests and consumers can drain this directly to examine
      async-dispose failures. shutdown! awaits it implicitly."
     []
     (pool/flush-pending-disposes!)))

#?(:cljs
   (defn get-pool-detail
     "Diagnostic. Show for each entry why the pool can or cannot evict it.
      Returns a plain JS object through the clj-native pool. Use it to
      debug :bounded-blocked failures from the live-context cap."
     []
     (pool/get-pool-detail :net.willcohen.proj)))

#?(:cljs
   (defn get-worker-count
     "Returns the number of workers in the pool."
     []
     (wasm/get-worker-count)))

#?(:clj
   (defn- pooled-wc
     "The pooled WasmContext to bind for this thread, or nil for the
      default scope. An explicit *wasm-context* binding wins (the default
      WasmContext bound explicitly counts as the default scope); else a
      workload-pool worker whose :proj handler carries :wc is pooled;
      else nil. FFI worker state has no :wc, so it resolves nil here."
     []
     (if-let [bound nw/*wasm-context*]
       (when-not (identical? bound wasm/proj-context) bound)
       (:wc (wp/current-context-or-nil :proj)))))

#?(:clj
   (defn- on-worker-context
     "Run f with the pooled WasmContext bound when this thread has one.
      No lock on any path: the nw primitives lock per-module, and a
      pooled Context is single-threaded by pool contract. Dispatch calls
      go through graal-call-scope instead, which keeps the global lock on
      the default path for compound atomicity."
     [f]
     (if-let [wc (pooled-wc)]
       (nw/with-wasm-context wc (f))
       (f))))

#?(:clj
   (defn- graal-call-scope
     "Run dispatch thunk f in this thread's graal scope: under the pooled
      WasmContext binding when one applies, else under the global lock of
      the shared default Context."
     [f]
     (if-let [wc (pooled-wc)]
       (nw/with-wasm-context wc (f))
       (with-graal-lock (f)))))

(defn- pad-coords
  "Widen each coordinate in coords to width by appending 0.0.

   A PROJ coordinate is a PJ_COORD (x y z t), and both backends write a
   fixed number of values for each one. A short coordinate breaks them in
   different ways: the tensor path throws IndexOutOfBoundsException, and
   the WASM path writes the next coordinate's values into the previous
   one's z and t slots without an error. PROJ.setCoords pads the same way
   on the Java side.

   Anything that is not a sequence of sequences passes through, so a flat
   sequence still reaches the reshape path."
  [width coords]
  (if (and (sequential? coords) (every? sequential? coords))
    (mapv (fn [coord]
            (let [n (count coord)]
              (if (< n width)
                (into (vec coord) (repeat (- width n) 0.0))
                coord)))
          coords)
    coords))

(defn ^:async set-coords!
  "Set the value of the full coordinate array.
  If the given coordinates have the same shape as the coord-array (a
  sequence of collections, each with a maximum of four doubles), set
  them directly. If the shapes differ, first try to reshape the
  coordinates into that tensor shape.
  A coordinate with fewer values than the array holds is padded with
  zeros, so [[lat lon]] and [[lat lon 0 0]] do the same thing.
  In ClojureScript, returns a Promise."
  [ca coords]
  #?(:clj
     (case @implementation
       :ffi (let [coords (if (dt-t/tensor? coords)
                           coords
                           (pad-coords (or (last (dt/shape ca)) 4) coords))]
              (cond (= (dt/shape ca) (dt/shape coords))
                    (dt-t/mset! ca coords)
                    :else
                    (dt-t/mset! ca (dt-t/reshape coords (dt/shape ca)))))
       :graal (on-worker-context #(wasm/set-coord-array coords ca)))
     :cljs (await (set-coord-array (pad-coords 4 coords) ca))))

(defn get-coords
  "Read coordinates from a coord-array at the given index.
   Returns [x y z t] for FFI tensors or GraalVM/WASM arrays."
  [ca idx]
  #?(:clj
     (case @implementation
       :ffi [(dt-t/mget ca idx 0) (dt-t/mget ca idx 1)
             (dt-t/mget ca idx 2) (dt-t/mget ca idx 3)]
       :graal (on-worker-context #(wasm/get-coord-array ca idx)))
     :cljs
     (get-coord-array ca idx)))

;; Defined below. squint hoists the compiled `var`, so the call in cs
;; resolves at runtime. The declare only satisfies the reader.
(declare context-ptr)

(defn cs
  "Call f with the context as its first argument.

   On the JVM this is what makes a context operation atomic: the call runs
   inside a swap! on the context atom, so two threads cannot use one PROJ
   context at the same time, and the op counter advances with it.

   ClojureScript needs no such guard and gets none. The context there is an
   immutable routing object with no counters, and worker-router already
   serializes every call on a worker. Adding counters would mean mutating
   shared state across workers."
  [context f args]
  #?(:clj
     (:result
      (swap!
       context (fn [a]
                 (let [ptr (:ptr a)]
                   (when-not ptr (throw (ex-info (str "Pointer in context is nil for fn " f) {:f f :context-val a})))
                   (assoc a
                          :ptr ptr
                          :op (inc (:op a))
                          :result (case @implementation
                                    :ffi (apply f (cons ptr args))
                                    :graal (graal-call-scope #(apply f (cons ptr args)))))))))
     :cljs
     ;; f is the dispatch-context-fn wrapper. Pass the context OBJECT, and
     ;; not the bare ptr, as the first arg. Dispatch's worker-idx-from-args
     ;; reads .worker_idx from it and routes to the worker that owns this
     ;; context's PROJ state. extract-args also returns the context object
     ;; on the non-context path, so the worker contract is the same for the
     ;; two paths.
     (let [ptr (context-ptr context)]
       (when-not ptr
         (throw (js/Error. "Pointer in context is nil")))
       (apply f (cons context args)))))

;; JVM only. A ClojureScript string-list never gets here: the worker owns
;; the Emscripten module and decodes the char** on its own side, so
;; process-return-value-with-tracking passes the decoded value straight
;; through. Nothing on the main thread can read that memory.
#?(:clj
   (defn string-array-pointer->strs
     "Convert a pointer to a NULL-terminated array of string pointers into a Clojure vector of strings."
     [ptr _runtime-log-level]
     (case @implementation
       :ffi (ffi-mem/read-string-array (ffi-mem/ptr-addr ptr))
       :graal (if (nps/null-ptr? ptr)
                []
                (nw/string-array-pointer->strs (nw/address-as-int ptr))))))

;; Defined below. squint hoists the compiled `var`, so the calls above
;; resolve at runtime. The declare only satisfies the reader.
(declare context-set-database-path context-set-enable-network
         proj-context-create proj-context-set-database-path
         proj-context-set-enable-network
         ensure-initialized!)

(def proj-type->destroy-fn
  "Map of PROJ return types to their destroy functions."
  {:pj "proj_destroy"
   :pj-list "proj_list_destroy"
   :string-list "proj_string_list_destroy"
   :pj-context "proj_context_destroy"
   :pj-crs-list-parameters "proj_get_crs_list_parameters_destroy"
   :pj-insert-session "proj_insert_object_session_destroy"
   :pj-operation-factory-context "proj_operation_factory_context_destroy"})

(def proj-error-codes
  "Map of PROJ error codes to descriptions"
  {0 "Success (no error)"

   ;; Invalid-operation class (1024+)
   1024 "PROJ_ERR_INVALID_OP - Invalid coordinate operation"
   1025 "PROJ_ERR_INVALID_OP_WRONG_SYNTAX - Invalid pipeline structure or missing +proj"
   1026 "PROJ_ERR_INVALID_OP_MISSING_ARG - Missing required operation parameter"
   1027 "PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE - Illegal parameter value"
   1028 "PROJ_ERR_INVALID_OP_MUTUALLY_EXCLUSIVE_ARGS - Mutually exclusive arguments"
   1029 "PROJ_ERR_INVALID_OP_FILE_NOT_FOUND_OR_INVALID - File not found or invalid"

   ;; Coordinate-transformation class (2048+)
   2048 "PROJ_ERR_COORD_TRANSFM - Coordinate transformation error"
   2049 "PROJ_ERR_COORD_TRANSFM_INVALID_COORD - Invalid coordinate (for example, lat > 90°)"
   2050 "PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN - Outside projection domain"
   2051 "PROJ_ERR_COORD_TRANSFM_NO_OPERATION - No operation found"
   2052 "PROJ_ERR_COORD_TRANSFM_OUTSIDE_GRID - Point outside grid"
   2053 "PROJ_ERR_COORD_TRANSFM_GRID_AT_NODATA - Grid cell is nodata"
   2054 "PROJ_ERR_COORD_TRANSFM_NO_CONVERGENCE - Iterative convergence failed"
   2055 "PROJ_ERR_COORD_TRANSFM_MISSING_TIME - Operation requires time"

   ;; Other class (4096+)
   4096 "PROJ_ERR_OTHER - Other error"
   4097 "PROJ_ERR_OTHER_API_MISUSE - API misuse"
   4098 "PROJ_ERR_OTHER_NO_INVERSE_OP - No inverse operation available"
   4099 "PROJ_ERR_OTHER_NETWORK_ERROR - Network resource access failure"})

(defn error-code->string
  "Convert a PROJ error code to a readable string"
  [code]
  (get proj-error-codes code (str "Unknown error code: " code)))

#?(:cljs
   (defn- build-ctx-destroy-fn
     "Top-level builder for the context destroy-fn that resource-tracker
      holds as the heldValue of its FinalizationRegistry entry.

      The top-level position is what isolates the closure's lexical scope
      from the creation scope of ctx-obj. Refer to build-pj-destroy-fn for
      the full reasoning: a heldValue declared as a local beside its FR
      target puts the two in one V8 function-activation Context, FR holds
      heldValue strongly until the target is collected, and so the target
      is pinned and the WeakRef sweep over clj-native's ctx-workers map
      never sees owner-alive=false.

      The closure holds only primitives (ctx-id, worker-idx, the fired?
      flag) plus ctx-pool, the pool this context was created on. The pool
      ref is not the owner, so it pins nothing."
     [ctx-pool worker-idx ctx-id]
     (let [fired? (atom false)]
       (fn dispose-ctx! []
         (when (and (wasm/live-pool? ctx-pool) (compare-and-set! fired? false true))
           ;; Route through wasm/destroy-context! so the pool drains
           ;; in-flight child PJ disposes (C) and waits for children whose
           ;; FR did not fire (B) before it posts context_destroy. Without
           ;; this gate, the destroy can reorder before an in-flight child
           ;; release and free the parent ctx under a pending proj_destroy.
           (let [p (.catch (wasm/destroy-context! ctx-pool worker-idx ctx-id)
                           wasm/ignore-pool-terminated)]
             (wasm/untrack-context! ctx-id)
             p))))))

#?(:cljs
   (defn- attach-ctx-symbol-dispose!
     "Top-level installer for `ctx-obj[Symbol.dispose]`, mirroring
      attach-pj-symbol-dispose!. Routing through resource-tracker puts the
      returned Promise on pending_dispose_promises, which consumers drain
      with flush_pending_disposes at shutdown."
     [ctx-obj destroy-fn ctx-id worker-idx]
     (aset ctx-obj js/Symbol.dispose
           (fn []
             (pool/fire-and-capture-dispose!
              destroy-fn
              #js {:lib "net.willcohen.proj"
                   :kind "ctx"
                   :ctx-id ctx-id
                   :worker worker-idx})))))

#?(:clj
   (defn- context-create-jvm
     "Create a PROJ context on the JVM, then wire its database path,
      logging, and network callbacks. Returns the context atom."
     [enable-network?]
     (let [a (atom {:ptr (proj-context-create {}) :op (long 0) :result nil})]
       (context-set-database-path a)
       (when (ffi?)
         (proj-logging/setup-logging! (:ptr @a)))
       (when enable-network?
         ;; Register the callbacks before context-set-enable-network, so
         ;; PROJ can use them.
         (when (graal?)
           (proj-network/setup-network-callbacks! (nw/address-as-int (:ptr @a))))
         (when (ffi?)
           (proj-network/setup-native-network-callbacks! (:ptr @a)))
         (context-set-enable-network a true))
       a)))

#?(:cljs
   (defn- ^:async context-create-cljs
     "Create a PROJ context on a worker, then wrap it in the JS owner
      object the consumer holds."
     [opts]
     (ensure-initialized!)
     (let [;; The pool this context is created on. Read it BEFORE the
           ;; create, and not again at destroy time. A late FR callback
           ;; must not post this ctx-id to a pool from a later init!
           ;; cycle, where the same id names a different, live context.
           ;; Refer to wasm/live-pool?.
           ctx-pool (wasm/current-pool)
           ctx-result (await (wasm/create-context-on-worker opts))
           ctx-id (get ctx-result :ctx-id)
           worker-idx (get ctx-result :worker-idx)
           destroy-fn (build-ctx-destroy-fn ctx-pool worker-idx ctx-id)
           ctx-obj #js {:ptr (get ctx-result :ptr)
                        :ctx_id ctx-id
                        :worker_idx worker-idx
                        :type "proj-context"}]
       ;; The JS ctx-obj is the owner that the consumer holds. A WeakRef
       ;; entry in clj-native's ctx-workers map lets the per-library
       ;; sweep fire `release` when the next pool op sees that V8
       ;; collected the owner, without FR-callback-queue lag.
       (wasm/track-context! ctx-id worker-idx (get ctx-result :release) ctx-obj)
       (.track resource ctx-obj
               #js {:disposefn destroy-fn
                    :tracktype "auto"})
       (attach-ctx-symbol-dispose! ctx-obj destroy-fn ctx-id worker-idx)
       ctx-obj)))

(defn ^:async context-create
  "Create a new PROJ context with grid network fetch configured for the platform.

   Network setup flow:
   - FFI: logging.clj sets the logging callback, then network.clj registers
     the network callbacks through proj_context_set_network_callbacks. Java
     HttpClient serves the HTTP requests.
   - GraalVM: network.clj installs ProxyExecutable callbacks in the wasm
     function table through Module.addFunction, then registers them before
     the network is enabled. PROJ can use the callbacks only if
     registration comes first.
   - CLJS: the worker's context_create handler does the full setup (database
     path, network enable, log callback). The CLJS side stores only the
     routing information.

   For ClojureScript, returns a plain immutable object with no counters,
   which prevents SharedArrayBuffer mutations. For the JVM, returns an atom.

   Options:
   - :network - enables network access for grid downloads (default: true)
   - :worker - explicit worker index for CLJS (default: round-robin)"
  [& args]
  (let [opts (if (seq args) (first args) {})
        opts (if (map? opts) opts {})]
    #?(:clj (context-create-jvm (get opts :network true))
       ;; The :cljs context_create worker command enables the network
       ;; itself, so the :network opt does not reach this side.
       :cljs (await (context-create-cljs opts)))))

(defn context-ptr
  "Return the PROJ pointer of any context type. Works with JVM atoms and
   with ClojureScript plain objects."
  [context]
  #?(:clj (:ptr @context)
     :cljs (.-ptr context)))

(defn context-database-path
  "Get the database path from any context type."
  [context]
  #?(:clj (:database-path @context)
     :cljs (.-database-path context)))

(defn is-context?
  "True when the value is a PROJ context. Works on all platforms."
  [x]
  #?(:clj (and (instance? clojure.lang.IDeref x)
               (map? @x)
               (contains? @x :ptr))
     :cljs (and x
                (.-ptr x)
                (= (.-type x) "proj-context"))))

(defn context-set-database-path
  "High-level wrapper that sets the database path.
   ClojureScript always uses /proj/proj.db (the standard Emscripten FS path)."
  ([context]
   (context-set-database-path context
                              #?(:clj
                                 (case @implementation
                                   :ffi (string/join File/separator
                                                     [(:path @native/proj)
                                                      "proj.db"])
                                   :graal "/proj/proj.db")
                                 :cljs
                                 "/proj/proj.db")))
  ([context db-path]
   (context-set-database-path context db-path nil nil))
  ([context db-path aux-db-paths options]
   #?(:clj
      ;; PROJ treats NULL auxDbPaths/options as none. Pass nil through as a
      ;; null pointer (the fndef types the two as :pointer?). Blank strings
      ;; become nil.
      (let [blank->nil (fn [x] (if (and (string? x) (string/blank? x)) nil x))]
        (proj-context-set-database-path {:context context :db-path db-path
                                         :aux-db-paths (blank->nil aux-db-paths)
                                         :options (blank->nil options)}))
      :cljs
      (let [ctx-ptr (context-ptr context)]
        (proj-context-set-database-path {:context ctx-ptr
                                         :db-path db-path
                                         :aux-db-paths aux-db-paths
                                         :options options})))))

(defn context-set-enable-network
  "Set network access for grid downloads on a PROJ context.
   Pass a truthy enabled (1 or true) to enable, or a falsy one
   (0, false, nil) to disable."
  [context enabled]
  #?(:clj
     (proj-context-set-enable-network {:context context :enabled (if enabled 1 0)})
     :cljs
     (proj-context-set-enable-network {:context (context-ptr context) :enabled (if enabled 1 0)})))

#?(:clj
   (defn coord-tensor
     "Reshape a native coord-array buffer into an n-by-dims float64 tensor.
      FFI only. WASM has no native buffer to reshape."
     [ca dims]
     (-> (dt-nb/as-native-buffer ca)
         (dt-nb/set-native-datatype :float64)
         (dt-t/reshape [(count ca) dims]))))

(defn coord-array
  ([n]
   (coord-array n 4))
  ([n dims]
   #?(:clj (do
             (when (nil? @implementation)
               (init!))
             (case @implementation
               :ffi (coord-array n dims :native-heap :auto)
               :graal (on-worker-context #(wasm/alloc-coord-array n dims))
               (throw (ex-info "Unknown implementation" {:impl @implementation}))))
      :cljs (coord-array n dims {})))
  #?@(:clj
      [([n dims container-type resource-type]
        (-> (dt-struct/new-array-of-structs :proj-coord n {:container-type container-type
                                                           :resource-type resource-type})
            (coord-tensor dims)))]
      :cljs
      [([n _dims _opts]
        (when (nil? @implementation)
          (init!))
        (alloc-coord-array n 0))]))

(defn coord->coord-array
  [coord]
  #?(:clj
     (do
       (when (nil? @implementation)
         (init!))
       (case @implementation
         :ffi (if (dt-t/tensor? coord)
                (let [coord-array (coord-array 1)
                      len (count coord)]
                  (reduce #(dt-t/mset! %1 0 %2 (dt-t/mget coord %2)) coord-array (range len)))
                (coord->coord-array (dt-t/->tensor coord)))
         :graal (on-worker-context #(wasm/set-coord-array coord (coord-array 1)))))
     :cljs
     (case @implementation
       (:node :browser) (set-coord-array coord (coord-array 1)))))

#?(:clj
   (defn set-coord!
     "Set the value of one coordinate in a coord-array tensor.
   The index of the first coord in a tensor is 0.
   For an array from coord-array, give the coord as an array of four doubles.
   Otherwise the coord must have the same shape as one coordinate in the tensor."
     [ca idx coord]
     (dt-t/mset! ca idx coord)))

#?(:clj
   (defn set-col!
     "Set the values of one column in a coordinate array tensor.
   JVM only."
     [ca idx vals]
     (-> ca
         (dt-t/transpose [1 0])
         (dt-t/mset! idx vals)
         (dt-t/transpose [1 0]))))

#?(:clj
   (defn set-xcol!
     "Set the X coordinate of all points in a coordinate array.
   JVM only."
     [ca vals]
     (set-col! ca 0 vals)))

#?(:clj
   (defn set-ycol!
     "Set the Y coordinate of all points in a coordinate array.
   JVM only."
     [ca vals]
     (set-col! ca 1 vals)))

#?(:clj
   (defn set-zcol!
     "Set the Z coordinate of all points in a coordinate array.
   JVM only."
     [ca vals]
     (set-col! ca 2 vals)))

#?(:clj
   (defn set-tcol!
     "Set the T (time) coordinate of all points in a coordinate array.
   JVM only."
     [ca vals]
     (set-col! ca 3 vals)))

(defn is-c-context-fn?
  "True when the fn-def describes a context-aware function."
  [fn-key fn-def]
  (let [arg-specs (:argtypes fn-def)]
    (cond
      (contains? fn-def :is-context-fn)
      (:is-context-fn fn-def)

       ;; These destroy fns take a context but must not be context-managed.
      (#{:proj_context_destroy :proj_operation_factory_context_destroy} fn-key)
      false

      :else (boolean (and (sequential? arg-specs)
                          (seq arg-specs)
                          (let [first-arg (first arg-specs)]
                            (and (sequential? first-arg)
                                 (seq first-arg)
                                 (#{:context :ctx} (first first-arg)))))))))

(declare lib)

(defn call-native
  "The single leaf for every PROJ native call.

   It stays a named var, because resource-tracking-test redefines it to
   intercept the destroy calls.

   The fn-def parameter is unused. call! reads the fn-def from the library
   value. The parameter stays so that a call site which holds a fn-def can
   pass it.

   force-worker-idx pins the call to one worker and skips pool affinity.
   reconcile-cross-worker-args! needs it to rebuild a PJ on the worker that
   holds the other arguments. The JVM has no workers, so the JVM path
   ignores it.

   with-graal-lock locks the same Context monitor that call! locks. locking
   is reentrant, so call!'s own acquisition nests."
  ([fn-key args] (call-native fn-key nil args nil))
  ([fn-key fn-def args] (call-native fn-key fn-def args nil))
  ;; Only the :cljs branch reads force-worker-idx, so clj-kondo's :clj
  ;; reader view sees it as unused.
  #_{:clj-kondo/ignore [:unused-binding]}
  ([fn-key _fn-def args force-worker-idx]
   #?(:clj
      (if (graal?)
        (do (wasm/ensure-proj-initialized!)
            (graal-call-scope #(dispatch/call! lib fn-key args)))
        (dispatch/call! lib fn-key args))
      :cljs
      (do
        (wasm/ensure-proj-initialized!)
        ;; :primary-handle connects this call with the PJ-CREATE and
        ;; PJ-DESTROY-WRAPPER trace events on the same handle. Only a
        ;; tracked PJ carries type "pj". A context wrapper also has a ctx_id.
        (let [pj-arg (first (filter (fn [a] (and (object? a) (= "pj" (.-type a)))) args))
              primary-handle (when pj-arg (.-ctx_id pj-arg))]
          (dispatch/call! lib fn-key args
                          #js {:pool (wasm/current-pool)
                               :primary-handle primary-handle
                               :force-worker-idx force-worker-idx}))))))

(defn ensure-initialized!
  "Make sure that PROJ is initialized before dispatch"
  []
  #?(:clj
     (when (nil? @implementation)
       (init!)
       (when (nil? @implementation)
         (throw (ex-info "Failed to initialize PROJ" {}))))
     :cljs
     (when (nil? @implementation)
       ;; CLJS init! is async and cannot run here, so this only warns.
       (js/console.warn "PROJ may not be initialized - ensure proj/init! was called"))))

(defn- lookup-arg-val
  "Look up an argument value from opts. Try the underscore, hyphenated, and
   context-alias key forms. squint's `get` reads a plain JS object by key,
   so one body serves Clojure maps and JS objects.

   The hyphenated form stays platform-split because a JS `.replace` with a
   string pattern rewrites only the first match, so it needs the global
   RegExp."
  [opts arg-name]
  (let [underscore #?(:clj (keyword arg-name) :cljs arg-name)
        hyphenated #?(:clj (keyword (string/replace (name arg-name) #"_" "-"))
                      :cljs (.replace (str arg-name) (js/RegExp. "_" "g") "-"))
        context-alias (case arg-name :ctx :context :context :ctx nil)]
    (or (get opts underscore)
        (get opts hyphenated)
        (when context-alias (get opts context-alias)))))

(defn- resolve-default
  "Resolve a default value. On the JVM, dereference symbol refs. Under
   squint, defaults resolve at namespace load. Convert booleans for int32."
  [default-val arg-type]
  (cond
    #?@(:clj [(symbol? default-val)
              (if-let [resolved (ns-resolve 'net.willcohen.proj.fndefs default-val)]
                @resolved
                default-val)])
    (and (= arg-type :int32) (boolean? default-val))
    (if default-val 1 0)
    :else default-val))

(defn- coerce-arg
  "Coerce a single extracted argument value for the target platform."
  [provided-val arg-type semantics-for-arg]
  (cond
    ;; CLJS coord arrays pass through for worker data transfer.
    (and (= arg-type :pointer)
         #?(:cljs (and (object? provided-val)
                       (= (.-type provided-val) "coord-array"))
            :clj false))
    provided-val

    (and (= arg-type :pointer)
         (map? provided-val)
         (contains? provided-val :malloc))
    (:malloc provided-val)

    ;; A :string-array? arg becomes a char** from the string vector.
    (and (some? provided-val)
         (sequential? provided-val)
         (= :string-array? (:semantic-type semantics-for-arg)))
    #?(:clj (if (graal?)
              (wasm/string-list-to-native-array provided-val)
              (ffi-mem/strings->c-array provided-val))
       :cljs (wasm/string-list-to-native-array provided-val))

    ;; A nil pointer arg becomes 0 (the null pointer).
    (and (nil? provided-val) (#{:pointer :pointer?} arg-type)) 0

    ;; A nil string arg becomes "" on FFI, because string->c needs a
    ;; string, and 0 (NULL) on WASM.
    (and (nil? provided-val) (= arg-type :string))
    #?(:clj (if (ffi?) "" 0)
       :cljs 0)

    :else provided-val))

(defn extract-args
  "Extract arguments from the opts map with the function definition, and
   apply defaults. Accepts underscore and hyphenated parameter names.
   Reads defaults from :argtypes inline entries and from :argsemantics."
  ;; squint does not desugar `& {:keys [...]}` keyword args. Take an
  ;; explicit opts map instead.
  ([fn-def opts] (extract-args fn-def opts {}))
  ([fn-def opts {:keys [skip-first?] :or {skip-first? false}}]
   (let [argtypes (if skip-first?
                    (rest (:argtypes fn-def))
                    (:argtypes fn-def))
         argsemantics-map (into {}
                                (map (fn [[arg-name semantic-type & rest-semantics]]
                                       [arg-name
                                        (merge {:semantic-type semantic-type}
                                               (if (seq rest-semantics)
                                                 (apply assoc {} rest-semantics)
                                                 {}))])
                                     (:argsemantics fn-def)))]
     (mapv (fn [arg-spec]
             (let [[arg-name arg-type & rest-spec] arg-spec
                   arg-map (when (seq rest-spec) (apply assoc {} rest-spec))
                   semantics-for-arg (get argsemantics-map arg-name)
                   ;; contains?, not `or` on the values: a :default of
                   ;; false or nil is a real default, and an `or` would
                   ;; fall through it to the other source and then read
                   ;; back as no default at all.
                   default-val (if (contains? arg-map :default)
                                 (get arg-map :default)
                                 (get semantics-for-arg :default))
                   has-default? (or (contains? arg-map :default)
                                    (contains? semantics-for-arg :default))
                   is-context-arg (contains? #{:ctx :context} arg-name)
                   provided-val (lookup-arg-val opts arg-name)]
               (cond
                 (and is-context-arg
                      (some? provided-val)
                      (is-context? provided-val))
                 #?(:clj (if (graal?)
                           provided-val
                           (context-ptr provided-val))
                    :cljs provided-val)

                 (and is-context-arg
                      (nil? provided-val)
                      (not has-default?))
                 0

                 (and (nil? provided-val) has-default?)
                 (resolve-default default-val arg-type)

                 :else
                 (coerce-arg provided-val arg-type semantics-for-arg))))
           argtypes))))

#?(:cljs
   (defn- build-pj-destroy-fn
     "Top-level builder for the destroy-fn that the FinalizationRegistry
      holds as `heldValue`. The top-level position isolates its lexical
      scope from the creation scope of the wrapper object. Without this
      isolation, V8 put the wrapper and the destroy-fn into one
      function-activation Context, because the compiled
      `process-return-value-with-tracking` declared the two as locals in
      one `let`. FR holds `heldValue` strongly until the target is
      collected. A heldValue that shares scope with the target thus pins
      the target, and WeakRef-based eviction never sees
      `owner-alive=false`, even after multiple major GCs.

      The closure holds only primitives, the local `fired?` atom, and
      `pj-pool`, the pool this PJ was created on. The pool ref does not
      pin the FR target. A read of (wasm/current-pool) at fire time
      instead WOULD post this PJ's raw per-worker heap address to the
      pool of a later init!, and free live memory in a different worker.
      Refer to wasm/live-pool?."
     [pj-pool worker-idx ptr destroy-fn-name ephemeral-ctx-ptr ephemeral-ctx-worker-idx]
     ;; destroy-fn-name is a string ('proj_destroy', 'proj_list_destroy', ...).
     ;; squint-cljs/core does not export `keyword`, so the string stays the
     ;; fn-key. Under squint, the :fns map of the library value has string
     ;; keys, so a string fn-key indexes it directly.
     (let [fired? (atom false)]
       (fn ^:async dispose! []
                 (when (and (wasm/live-pool? pj-pool) (compare-and-set! fired? false true))
                   ;; Await the primary destroy so the returned Promise
                   ;; resolves only after the worker processed the
                   ;; proj_destroy. The pool's wrapped disposer chains
                   ;; .finally(decrement!) on that Promise. Without the
                   ;; await, the dispatch! Promise is discarded, the fn
                   ;; returns nil, .finally never chains, and the counter
                   ;; decrements before the worker_call settles. That opens
                   ;; again the TOCTOU window that destroy-context!'s B-gate
                   ;; closes.
                   (let [routing #js {:worker_idx worker-idx :ptr ptr}]
                     (await (.catch (dispatch/call! lib destroy-fn-name [routing]
                                                    {:pool pj-pool
                                                     :force-worker-idx worker-idx})
                                    wasm/ignore-pool-terminated)))
                   ;; If this PJ was created against an ephemeral cloned ctx
                   ;; (through the dispatch context-isolator), destroy that
                   ;; ctx after the primary destroy. workerQueue serializes
                   ;; calls on a worker, so a dispatch here puts the
                   ;; ctx-destroy strictly after the proj_destroy above.
                   ;; Awaited, so the counter does not decrement until the
                   ;; two complete.
                   (when ephemeral-ctx-ptr
                     (let [ctx-routing #js {:worker_idx ephemeral-ctx-worker-idx :ptr ephemeral-ctx-ptr}]
                       (await (.catch (dispatch/call! lib "proj_context_destroy" [ctx-routing]
                                                      {:pool pj-pool
                                                       :force-worker-idx ephemeral-ctx-worker-idx})
                                      wasm/ignore-pool-terminated)))))))))

#?(:cljs
   (defn- attach-pj-symbol-dispose!
     "Top-level installer for `target[Symbol.dispose]`. The same
      isolation reason as build-pj-destroy-fn applies: the dispose
      closure must not share lexical scope with the creation site of
      `target`.

      pj-ctx-id and worker-idx go into the [CLJ-NATIVE EXPLICIT-DISPOSE]
      event, so the trace can tell Symbol.dispose fires from FR fires."
     [target destroy-fn pj-ctx-id worker-idx]
     (aset target js/Symbol.dispose
           (fn []
             ;; Fire the registered release (the wrapped disposer that
             ;; resource-tracker holds for GC) so in-flight-by-parent
             ;; decrements and the drain gate of the parent ctx's
             ;; destroy-context! can resolve. A raw destroy-fn fire here
             ;; would release the native handle but keep the parent's
             ;; in-flight counter high until GC. An explicit dispose plus an
             ;; immediate flush or parent-destroy would then wait on a
             ;; counter that only GC lowers. Fall back to the raw destroy-fn
             ;; when the handle is not live (already GC'd or evicted).
             (or (pool/dispose-handle! :net.willcohen.proj pj-ctx-id)
                 (pool/fire-and-capture-dispose!
                  destroy-fn
                  #js {:lib "net.willcohen.proj"
                       :kind "pj"
                       :ctx-id pj-ctx-id
                       :worker worker-idx}))))))

#?(:clj
   (defonce ^:private proj-destroy-lock
     ;; One lock for ALL PROJ native destroys. PROJ's process-global
     ;; grid / FileManager cache is not safe to free from two threads at
     ;; the same time. Passed to `wp/release-once!`, this serializes every
     ;; proj_destroy / proj_context_destroy (explicit release AND the GC
     ;; dispose-fn) library-wide, from any thread. Refer to the
     ;; `release-once!` docstring in clj-native. Init and transform stay
     ;; parallel.
     (Object.)))

#?(:clj
   (defn- do-native-destroy!
     "Call the native destructor on `pointer`."
     [pointer destroy-fn-name]
     (call-native (keyword destroy-fn-name) [pointer])))

#?(:clj
   (defn release-tracked!
     "Release a tracked PROJ pointer through `wp/release-once!`. Returns
      true if the native destructor fired on this call, false if a
      prior caller (or the GC) already released it. `destroy-fn-name`
      matches the strings in `proj-type->destroy-fn`
      (\"proj_destroy\", \"proj_context_destroy\", ...)."
     [pointer destroy-fn-name]
     (wp/release-once! pointer proj-destroy-lock
                       #(do-native-destroy! pointer destroy-fn-name))))

#?(:cljs
   (defn- register-pj-handle!
     "Install the live-pjs entry and the resource-tracker GC hook for a
      tracked PJ, under the bounded live-context cap.

      Pre-evicts before it registers, so the live count stays at or below
      max-live-ctxs. When nothing is evictable (all young, or all with
      ccalls in flight), bounded-create-handle! throws ex-info {:blocked
      :bounded-blocked}. Fire destroy-fn at once in that case, which
      prevents a leak of the new PJ in the worker's PROJ heap, then
      rethrow.

      parent-key identifies the parent ctx across all workers. Each worker
      keeps its own ctx-id sequence (handler-runtime's nextContextId++), so
      ctx-id alone collides across workers and wedges the drain gate of
      destroy-context! on cross-worker counter aggregation. The composite
      \"${worker-idx}:${parent-ctx-id}\" keys in-flight-by-parent and
      gate-promises-by-parent for each (worker, ctx) pair, so a destroy
      gate waits only for the children of its own worker."
     [result destroy-fn pj-ctx-id worker-idx parent-ctx-id]
     (try
       (pool/bounded-create-handle!
        :net.willcohen.proj
        (fn []
          (let [parent-key (when parent-ctx-id (str worker-idx ":" parent-ctx-id))]
            (pool/register-handle! :net.willcohen.proj pj-ctx-id worker-idx
                                   destroy-fn result parent-key))))
       (catch :default e
         (when (= :bounded-blocked (some-> e ex-data :blocked))
           (try (pool/fire-and-capture-dispose!
                 destroy-fn
                 #js {:lib "net.willcohen.proj"
                      :kind "pj"
                      :ctx-id pj-ctx-id
                      :worker worker-idx
                      :path "bounded-blocked-cleanup"})
                (catch :default _)))
         (throw e)))))

#?(:cljs
   (defn- track-pj-result!
     "Give a :pj return the live-context cap, the FinalizationRegistry, and
      Symbol.dispose. Returns result.

      The destroy-fn must come from the top-level build-pj-destroy-fn, and
      not from a fn literal here. Refer to its docstring: a heldValue that
      shares a function-activation scope with the FR target pins the
      target, and WeakRef-based eviction never sees it collected."
     [result destroy-fn-name]
     (let [worker-idx (.-worker_idx result)
           ptr (.-ptr result)
           ;; proj-result-wrapper sets ctx_id. The dispatch arg scan reads
           ;; it as .ctx_id, and pool/register-handle! uses it as the
           ;; live-pjs key.
           pj-ctx-id (.-ctx_id result)
           ;; proj-result-wrapper sets parent_ctx_id from args[0].ctx_id
           ;; for PJ-creating calls. It threads into pool/register-handle!
           ;; so destroy-context! can drain children before it destroys the
           ;; parent.
           parent-ctx-id (.-parent_ctx_id result)
           ;; proj-result-wrapper sets the ephemeral ctx fields from the
           ;; isolator state when a context-isolated call (at this time only
           ;; :proj_create_crs_to_crs) produced this PJ. nil otherwise.
           ephemeral-ctx-ptr (.-_ephemeral_context_ptr result)
           ephemeral-ctx-worker-idx (.-_ephemeral_context_worker_idx result)
           destroy-fn (build-pj-destroy-fn (wasm/current-pool) worker-idx ptr
                                           destroy-fn-name
                                           ephemeral-ctx-ptr ephemeral-ctx-worker-idx)]
       (register-pj-handle! result destroy-fn pj-ctx-id worker-idx parent-ctx-id)
       ;; Symbol.dispose still routes through resource-tracker, so explicit
       ;; `using` blocks land their dispose promises on
       ;; pending_dispose_promises, which shutdown! drains.
       ;; pool/register-handle! registers the resource-tracker GC entry.
       (attach-pj-symbol-dispose! result destroy-fn pj-ctx-id worker-idx)
       result)))

#?(:clj
   (defn- track-jvm-result!
     "Track a pointer return for release at GC. Routes through the
      release-once sentinel so a racing explicit release (for example,
      workload-pool handler.destroy) cannot double-free this pointer."
     [result destroy-fn-name]
     (resource/track
      result
      {:dispose-fn (fn []
                     (try
                       (wp/release-once! result proj-destroy-lock
                                         #(do-native-destroy! result destroy-fn-name))
                       (catch Throwable t
                         (log/warn t "proj/dispose-fn: native destroy failed"))))
       :track-type :auto})
     result))

(defn process-return-value-with-tracking
  "Process the return value by proj-returns type and apply resource tracking"
  [result fn-def]
  (let [proj-returns (:proj-returns fn-def)]
    (if (= :string-list proj-returns)
      ;; CLJS: the worker owns the module and already decoded the
      ;; string-list. CLJ: convert the pointer to strings.
      #?(:cljs result
         :clj (string-array-pointer->strs result nil))
      (let [destroy-fn-name (get proj-type->destroy-fn proj-returns)]
        (if (and destroy-fn-name result)
          #?(:cljs
             ;; Only a :pj return carries .ctx_id, so only it gets the
             ;; FinalizationRegistry and live-context-cap treatment.
             ;; :pj-list and :pj-operation-factory-context come back as
             ;; light routing wraps that the caller must destroy
             ;; explicitly. Refer to wasm/proj-result-wrapper.
             ;; TODO: wrap the rest with FR. A naive wrap caused worker
             ;; mutex deadlocks in the CRS info list test.
             (if (and (object? result) (.-ctx_id result))
               (track-pj-result! result destroy-fn-name)
               result)
             :clj (track-jvm-result! result destroy-fn-name))
          result)))))

(defn dispatch-context-fn
  "Dispatch functions that use context atomicity through cs"
  [fn-key fn-def context-atom remaining-args]
  (cs context-atom
      (fn [ctx & args]
        (let [full-args (vec (cons ctx args))]
          (call-native fn-key fn-def full-args)))
      remaining-args))

(defn- resolve-context-val
  "Look up the context arg from opts. Check :ctx and :context.

   Works on JVM maps and on ClojureScript plain objects alike: squint's
   `get` reads a plain object by key, so the JS-object case needs no
   separate aget path."
  [opts first-arg-name]
  (or (get opts first-arg-name)
      (when (#{:ctx :context} first-arg-name)
        (get opts (if (= first-arg-name :ctx) :context :ctx)))))

(defn- first-arg-kw
  "Keyword of the first argtype name, or nil if argtypes is empty.
   Under squint, argtype first elements are already keywords (that is,
   JS strings), so the cljs branch returns the value unchanged."
  [fn-def]
  (when (seq (:argtypes fn-def))
    (let [v (first (first (:argtypes fn-def)))]
      #?(:clj (keyword v) :cljs v))))

(defn should-use-context-dispatch?
  "True when a function must use context dispatch through cs."
  [fn-key fn-def opts]
  (let [is-context-fn (is-c-context-fn? fn-key fn-def)
        first-arg-val (resolve-context-val opts (first-arg-kw fn-def))]
    (and is-context-fn
         (is-context? first-arg-val))))

(defn get-context-atom
  "Extract the context atom from opts for context functions"
  [opts fn-def]
  (resolve-context-val opts (first-arg-kw fn-def)))

(defn get-remaining-args
  "Extract args for context functions and skip the first context arg"
  [opts fn-def]
  (let [first-arg-name (first-arg-kw fn-def)]
    (extract-args fn-def (dissoc (dissoc opts first-arg-name)
                                 (if (= first-arg-name :ctx) :context :ctx))
                  {:skip-first? true})))

(defn- needs-auto-context?
  "True when a context function got no context argument."
  [fn-key fn-def opts]
  (and (is-c-context-fn? fn-key fn-def)
       (nil? (resolve-context-val opts (first-arg-kw fn-def)))))

(defn- context-from-pj-args
  "Extract a stored context from the first PJ arg in opts."
  [fn-def opts]
  (some (fn [[arg-spec _]]
          (let [v (get opts #?(:clj (keyword arg-spec) :cljs arg-spec))]
            #?(:clj (when (and v (map? (meta v)) (:proj-context (meta v)))
                      (:proj-context (meta v)))
               :cljs (when (and (object? v) (some? (.-_proj_context v)))
                       (.-_proj_context v)))))
        (rest (:argtypes fn-def))))

(defn- attach-context-to-result
  "Attach the context used for creation onto a PJ result object. A result
   that cannot carry the context passes through unchanged."
  [result ctx]
  (when result
    #?(:clj (if (instance? clojure.lang.IObj result)
              (vary-meta result assoc :proj-context ctx)
              result)
       :cljs (do (when (object? result)
                   (aset result "_proj_context" ctx))
                 result))))

#?(:cljs
   (defn- ^:async reconcile-cross-worker-args!
     "When PJ/context args come from different workers, recreate mismatched
      ones on the target worker through a PROJJSON roundtrip."
     [fn-def opts]
     (let [worker-count (wasm/get-worker-count)]
       (if (or (nil? worker-count) (<= worker-count 1))
         opts
         (let [pj-args (into []
                             (keep (fn [[arg-spec _]]
                                     (let [arg-name (str arg-spec)
                                           v (if (object? opts)
                                               (aget opts arg-name)
                                               (get opts arg-spec))]
                                       (when (and (object? v)
                                                  (or (= (.-type v) "pj")
                                                      (= (.-type v) "proj-context")))
                                         {:arg-name arg-name :value v :worker-idx (.-worker_idx v) :type (.-type v)}))))
                             (:argtypes fn-def))
               worker-indices (into #{} (map :worker-idx) pj-args)]
           (if (<= (count worker-indices) 1)
             opts
             (let [target-worker (.-worker_idx (:value (first (filter #(= (:type %) "pj") pj-args))))
                   desc (str "proj-wasm: PJ args are on different workers ("
                             (string/join ", " (map #(str (:arg-name %) " on worker " (:worker-idx %)) pj-args))
                             "). Recreating on worker " target-worker ". For better performance, use an explicit context.")]
               (js/console.warn desc)
               (let [target-ctx (or (some (fn [{:keys [value worker-idx type]}]
                                            (when (and (= worker-idx target-worker) (= type "pj"))
                                              (.-_proj_context value)))
                                          pj-args)
                                    (await (context-create {:worker target-worker})))]
                 (doseq [{:keys [arg-name value worker-idx type]} pj-args]
                   (when (and (not= worker-idx target-worker) (= type "pj"))
                     (let [src-ctx (or (.-_proj_context value) (await (context-create {:worker worker-idx})))
                           projjson (await (call-native :proj_as_projjson [src-ctx value 0]))]
                       (when (or (nil? projjson) (= projjson ""))
                         (throw (js/Error. (str "Cannot reconcile " arg-name " across workers: PROJJSON export failed. Use an explicit context."))))
                       (let [identity-op (await (call-native :proj_create_crs_to_crs nil
                                                             [target-ctx projjson projjson 0]
                                                             target-worker))
                             new-pj (await (call-native :proj_get_source_crs nil
                                                        [target-ctx identity-op]
                                                        target-worker))]
                         (aset new-pj "_proj_context" target-ctx)
                         (aset opts arg-name new-pj))))
                   (when (and (not= worker-idx target-worker) (= type "proj-context"))
                     (aset opts arg-name target-ctx)))
                 opts))))))))

#?(:clj
   (defn- ensure-struct-defs!
     "Force registration of dtype-next struct definitions."
     []
     @proj-struct/crs-info-def*
     @proj-struct/unit-info-def*
     @proj-struct/celestial-body-info-def*))

#?(:clj
   (defn- read-struct-field-ffi
     "Read a single struct field at (base-addr + offset)."
     [base-addr offset field-type]
     (case field-type
       :string (ffi-mem/rd-cstr (+ base-addr offset))
       :int (ffi-mem/rd-i32 (+ base-addr offset))
       :double (ffi-mem/rd-f64 (+ base-addr offset))
       :boolean (not= 0 (ffi-mem/rd-i32 (+ base-addr offset))))))

#?(:clj
   (defn- read-struct-ffi
     "Read a C struct at base-addr into a Clojure map with dtype-next struct offsets."
     [base-addr struct-def-key struct-fields]
     (let [layout (:layout-map (dt-struct/get-struct-def struct-def-key))]
       (persistent!
        (reduce (fn [m [kw field-type _wasm-offset]]
                  (let [offset (:offset (get layout kw))]
                    (assoc! m kw (read-struct-field-ffi base-addr offset field-type))))
                (transient {})
                struct-fields)))))

#?(:clj
   (defn- jvm-memory-ops
     "Backend memory primitives for the multi-call orchestrators.

      Each orchestrator builds this map inside its own body, on every call,
      because the (graal?) test below picks the backend. The implementation
      atom can change between calls through force-ffi! / force-graal! /
      toggle-graal!, and a map built once at definition time would freeze
      whichever backend happened to be current at namespace load."
     [struct-def]
     (if (graal?)
       {:malloc         (fn [n] (nw/address-as-int (nw/malloc n)))
        :free           nw/free-on-heap
        :addr-of        nw/address-as-int
        :read-i32       (fn [a] (nw/address-as-int (nw/get-value a "i32")))
        :read-f64       (fn [a] (.asDouble ^org.graalvm.polyglot.Value
                                           (nw/get-value a "double")))
        :read-cstr      (fn [a] (let [sp (nw/address-as-int (nw/get-value a "*"))]
                                  (when-not (zero? sp) (nw/pointer->string sp))))
        :read-ptr       (fn [a] (nw/address-as-int (nw/get-value a "*")))
        :read-f64-array (fn [a n] (vec (nw/read-heap-array a n :f64)))
        :read-struct    nw/read-struct
        :ptr-size       4}
       {:malloc         (fn [n] (dt-nb/malloc n {:datatype :int8}))
        ;; dt-nb/malloc is resource-tracked, so the FFI side frees nothing.
        :free           (fn [_] nil)
        :addr-of        (fn [p] (dt-ptr/ptr-value p))
        :read-i32       ffi-mem/rd-i32
        :read-f64       ffi-mem/rd-f64
        :read-cstr      ffi-mem/rd-cstr
        :read-ptr       ffi-mem/rd-addr
        :read-f64-array (fn [a n] (mapv #(ffi-mem/rd-f64 (+ a (* % 8))) (range n)))
        :read-struct    (fn [addr fields] (read-struct-ffi addr struct-def fields))
        :ptr-size       8})))

#?(:clj
   (defn- dispatch-struct-list-jvm
     "The :struct-list orchestrator for the two JVM backends.

      graal-call-scope makes allocate, call, read, and free one atomic
      region on the default Context (call! locks the same monitor, and
      locking is reentrant, so its own acquisition nests), and scopes a
      pooled worker onto its own Context."
     [fn-key fn-def args]
     (ensure-struct-defs!)
     (let [{:keys [struct-def struct-fields struct-destroy-fn
                   struct-params-create struct-params-destroy]} fn-def
           {:keys [malloc free addr-of read-i32 read-ptr read-struct ptr-size]}
           (jvm-memory-ops struct-def)]
       (graal-call-scope
        (fn []
        (let [count-ptr (malloc 4)
              params-ptr (when struct-params-create
                           (call-native (keyword struct-params-create) []))
              call-args (mapv (fn [[arg-spec arg-type] arg-val]
                                (let [aname (name arg-spec)]
                                  (cond
                                    (= aname "out_result_count") count-ptr
                                    (and (= aname "params") params-ptr) params-ptr
                                    (and (= arg-type :string) (nil? arg-val)) ""
                                    :else arg-val)))
                              (:argtypes fn-def) args)
              result-ptr (call-native fn-key fn-def call-args)
              n (read-i32 (addr-of count-ptr))]
          (free count-ptr)
          (when (and params-ptr struct-params-destroy)
            (call-native (keyword struct-params-destroy) [params-ptr]))
          (if (and result-ptr (pos? n))
            (let [base (addr-of result-ptr)
                  entries (mapv (fn [i]
                                  (read-struct (read-ptr (+ base (* i ptr-size)))
                                               struct-fields))
                                (range n))]
              (call-native (keyword struct-destroy-fn) [result-ptr])
              entries)
            (do
              (when result-ptr
                (call-native (keyword struct-destroy-fn) [result-ptr]))
              []))))))))

(defn- out-param-arg?
  "True if an argtype spec is an output parameter (name starts with out_)."
  [[arg-name _arg-type]]
  (let [s #?(:clj (name arg-name) :cljs (str arg-name))]
    (and (>= (count s) 4) (= "out_" (subs s 0 4)))))

#?(:clj
   (defn- out-field-alloc-size
     "Byte size for an out-field allocation. Only dispatch-out-params-jvm
      allocates out-fields, so this is JVM only. pointer-size is 8 for
      native FFI (64-bit), 4 for WASM (32-bit)."
     [field-spec args fn-def pointer-size]
     (let [field-type (second field-spec)]
       (case field-type
         :double 8
         :string pointer-size
         :int 4
         :double-array (let [count-arg-name (nth field-spec 3)
                             count-arg-kw (keyword count-arg-name)
                             input-argtypes (vec (remove out-param-arg? (:argtypes fn-def)))
                             idx (.indexOf ^java.util.List (mapv #(keyword (first %)) input-argtypes) count-arg-kw)]
                         (* 8 (nth args idx)))))))

#?(:clj
   (defn- dispatch-out-params-jvm
     "The :out-params orchestrator for the two JVM backends. `args` holds
      only the caller-supplied, non-out arguments that extract-args
      produced.

      graal-call-scope makes allocate, call, read, and free one atomic
      region on the default Context, and scopes a pooled worker onto its
      own Context."
     [fn-key fn-def args]
     (let [out-fields (:out-fields fn-def)
           {:keys [malloc free addr-of read-i32 read-f64 read-cstr
                   read-f64-array ptr-size]}
           (jvm-memory-ops nil)]
       (graal-call-scope
        (fn []
        (let [allocs (mapv (fn [field-spec]
                             (let [size (out-field-alloc-size field-spec args fn-def ptr-size)]
                               {:ptr (malloc size) :size size}))
                           out-fields)
              input-idx (atom 0)
              alloc-idx (atom 0)
              full-args (mapv (fn [argtype]
                                (if (out-param-arg? argtype)
                                  (let [i @alloc-idx]
                                    (swap! alloc-idx inc)
                                    (:ptr (nth allocs i)))
                                  (let [i @input-idx]
                                    (swap! input-idx inc)
                                    (nth args i))))
                              (:argtypes fn-def))
              result (call-native fn-key fn-def full-args)
              ;; A zero or nil return means the call failed, so the out slots
              ;; hold nothing to read.
              failed? (or (nil? result) (and (number? result) (zero? result)))
              result-map (when-not failed?
                           (persistent!
                            (reduce-kv
                             (fn [m i field-spec]
                               (let [[field-name field-type] field-spec
                                     {:keys [ptr size]} (nth allocs i)
                                     addr (addr-of ptr)]
                                 (assoc! m field-name
                                         (case field-type
                                           :double (read-f64 addr)
                                           :int (read-i32 addr)
                                           :string (read-cstr addr)
                                           :double-array (read-f64-array addr (quot size 8))))))
                             (transient {})
                             out-fields)))]
          (doseq [{:keys [ptr]} allocs] (free ptr))
          result-map))))))

#?(:cljs
   (defn- snake->camel [s]
     (let [parts (.split s "_")]
       (apply str (first parts)
              (map (fn [p] (str (.toUpperCase (.substring p 0 1)) (.substring p 1)))
                   (rest parts))))))

#?(:cljs
   (defn- convert-js-result-keys [result key-casing]
     (if (not= key-casing :camel)
       result
       (if (array? result)
         (.map result
               (fn [obj]
                 (let [out #js {}]
                   (.forEach (.keys js/Object obj)
                             (fn [k] (aset out (snake->camel k) (aget obj k))))
                   out)))
         (when result
           (let [out #js {}]
             (.forEach (.keys js/Object result)
                       (fn [k] (aset out (snake->camel k) (aget result k))))
             out))))))

;; Only the :cljs branch reads key-casing. The JVM returns Clojure maps, so
;; no key rewrite is necessary.
#_{:clj-kondo/ignore [:unused-binding]}
(defn- ^:async dispatch-struct-list
  "Dispatch for :struct-list return type."
  [fn-key fn-def opts key-casing]
  (let [args (extract-args fn-def opts)]
    #?(:clj (dispatch-struct-list-jvm fn-key fn-def args)
       :cljs (convert-js-result-keys
              (await (call-native fn-key fn-def args))
              key-casing))))

#_{:clj-kondo/ignore [:unused-binding]}
(defn- ^:async dispatch-out-params
  "Dispatch for :out-params return type."
  [fn-key fn-def opts key-casing]
  (let [input-fn-def (assoc fn-def :argtypes (vec (remove out-param-arg? (:argtypes fn-def))))
        args (extract-args input-fn-def opts)]
    #?(:clj (dispatch-out-params-jvm fn-key fn-def args)
       :cljs (convert-js-result-keys
              (await (call-native fn-key fn-def args))
              key-casing))))

(defn- ^:async dispatch-default
  "Dispatch for functions without special return types. Selects context
   dispatch or plain dispatch, then tracks the return value and attaches
   the context."
  [fn-key fn-def opts ctx-for-result]
  (let [result (if (should-use-context-dispatch? fn-key fn-def opts)
                 (let [context-atom (get-context-atom opts fn-def)
                       remaining-args (get-remaining-args opts fn-def)]
                   (await (dispatch-context-fn fn-key fn-def context-atom remaining-args)))
                 (let [args (extract-args fn-def opts)]
                   (await (call-native fn-key fn-def args))))
        result (process-return-value-with-tracking result fn-def)]
    (if ctx-for-result (attach-context-to-result result ctx-for-result) result)))

(defn- errno-failure-signal?
  "True when the result of a PROJ call shows possible failure, so that an
   errno read is worthwhile. The probed cases are pointer returns that
   come back nil and string returns that come back nil or empty. A
   numeric zero can be a legitimate result, so numeric returns are not
   probed."
  [result fn-def]
  (let [rettype (:rettype fn-def)]
    (cond
      (= rettype :pointer) (nil? result)
      (= rettype :string)  (or (nil? result) (= "" result))
      :else false)))

(defn- resolve-ctx-from-opts
  "Look up the call's context arg from opts and return the high-level
   atom (JVM) or JS object (CLJS), or nil when the fn-def's first arg
   is not a context."
  [fn-def opts]
  (let [fa (first-arg-kw fn-def)]
    (when (#{:ctx :context} fa)
      (resolve-context-val opts fa))))

(defn ^:async dispatch-proj-fn
  "Central dispatcher for all PROJ functions"
  [fn-key fn-def opts & [key-casing]]
  (ensure-initialized!)
  (let [opts (if (needs-auto-context? fn-key fn-def opts)
               (let [ctx (or (context-from-pj-args fn-def opts)
                             (await (context-create {})))]
                 #?(:clj (assoc opts :context ctx)
                    :cljs (if (object? opts)
                            (do (aset opts "context" ctx) opts)
                            (assoc opts :context ctx))))
               opts)
        opts #?(:clj opts
                :cljs (await (reconcile-cross-worker-args! fn-def opts)))
        proj-returns (:proj-returns fn-def)
        result (case proj-returns
                 :struct-list (await (dispatch-struct-list fn-key fn-def opts key-casing))
                 :out-params  (await (dispatch-out-params fn-key fn-def opts key-casing))
                 (let [ctx-for-result (when (= :pj proj-returns)
                                        (resolve-ctx-from-opts fn-def opts))]
                   (await (dispatch-default fn-key fn-def opts ctx-for-result))))]
    ;; The result-check hook returns the result unchanged or throws.
    (await (dispatch/check-result lib fn-key fn-def opts result))))

(defn ^:async proj-errno-result-check
  "Result-check hook for clj-native.dispatch. When a PROJ call returns
   a nil pointer or an empty string AND a context is available, read
   proj_context_errno on that context. If it is non-zero, throw. The
   check skips the errno readers themselves (guard on fn-key), which
   prevents recursion."
  [_library fn-key fn-def opts result]
  (when (and (not (#{:proj_context_errno :proj_context_errno_string} fn-key))
             (errno-failure-signal? result fn-def))
    (when-let [ctx-for-errno (resolve-ctx-from-opts fn-def opts)]
      (let [errno-def (get pdefs/fndefs :proj_context_errno)
            errno-opts #?(:clj {:context ctx-for-errno}
                          :cljs (let [o #js {}] (aset o "context" ctx-for-errno) o))
            errno-result (await (dispatch-proj-fn :proj_context_errno errno-def errno-opts))]
        (when (and (number? errno-result) (not (zero? errno-result)))
          (let [fn-name #?(:clj (name fn-key) :cljs (str fn-key))
                msg (str "PROJ error " errno-result " in " fn-name
                         ": " (error-code->string errno-result))]
            (throw #?(:clj  (ex-info msg {:errno errno-result :fn-key fn-key})
                      :cljs (js/Error. msg))))))))
  result)

;; `lib` is forward-declared above call-native, because the result-check hook
;; needs dispatch-proj-fn, which needs check-result, which needs `lib`.
(defonce lib
  (dispatch/library {:key :net.willcohen.proj
                     :fndefs pdefs/fndefs
                     :impl-atom implementation
                     :ffi-impl-ns 'net.willcohen.proj.impl.native
                     :hooks #?(:cljs {:extras-builder   wasm/proj-extras-builder
                                      :result-wrapper   wasm/proj-result-wrapper
                                      :context-isolator wasm/proj-context-isolator
                                      :result-check     proj-errno-result-check}
                               :clj  {:result-check proj-errno-result-check})}))

;; The two platforms call this, so clj-kondo's hooks.proj hook fires for the
;; two. A bare JVM doseq made every JVM caller of a generated fn report
;; "Unresolved var", which hid real dead code.
(define-all-proj-public-fns nil)

(defn transform-batch
  "Transform a batch of coords with the per-worker PROJ Context.

   Call it only from inside a clj-native workload-pool worker (a thread
   on which the `:proj` handler ran init). It looks up a cached PROJ
   transformer for the (source-crs, target-crs) pair against the
   per-worker Context, or creates and caches one on first call. It then
   runs `proj_trans_array` in the forward direction and mutates
   coord-array in place.

   Args:
     source-crs   PROJ source CRS string (for example \"EPSG:4326\")
     target-crs   PROJ target CRS string (for example \"EPSG:2249\")
     coord-array  A PROJ coord-array (from `coord-array`) of shape
                  [n 4]. `proj_trans_array` mutates it in place.

   Returns the proj_trans_array result (0 on success). Throws when no
   per-worker Context is bound. It does not fall back to a new
   temporary Context, because that fallback leaks 22 GB on long runs."
  [source-crs target-crs coord-array]
  #?(:clj
     (let [{:keys [ctx tx-cache]} (wp/current-context :proj)
           tx-key [source-crs target-crs]
           tx (or (get @tx-cache tx-key)
                  (let [raw-tx (dispatch-proj-fn
                                :proj_create_crs_to_crs
                                (get pdefs/fndefs :proj_create_crs_to_crs)
                                {:context ctx
                                 :source_crs source-crs
                                 :target_crs target-crs
                                 :area nil})
                        normalized (dispatch-proj-fn
                                    :proj_normalize_for_visualization
                                    (get pdefs/fndefs :proj_normalize_for_visualization)
                                    {:context ctx :obj raw-tx})]
                    (when (nil? normalized)
                      (throw (ex-info "transform-batch: failed to create transformer"
                                      {:source-crs source-crs
                                       :target-crs target-crs})))
                    (swap! tx-cache assoc tx-key normalized)
                    normalized))
           ;; The graal coord-array is a map, and counting it counts its
           ;; KEYS, not its coordinates; :n carries the allocation count.
           ;; The FFI tensor counts its rows.
           n (long (if (map? coord-array)
                     (:n coord-array)
                     (count coord-array)))
           result (dispatch-proj-fn
                   :proj_trans_array
                   (get pdefs/fndefs :proj_trans_array)
                   {:p tx :direction 1 :n n :coord coord-array})]
       (when (and (number? result) (not (zero? result)))
         (throw (ex-info "transform-batch: proj_trans_array returned non-zero"
                         {:result result
                          :source-crs source-crs
                          :target-crs target-crs
                          :n n})))
       result)
     :cljs
     (throw (ex-info "transform-batch: CLJS surface not yet implemented. Worker-side dispatch uses proj/proj-trans-array directly through the worker-router handler."
                     {:source-crs source-crs
                      :target-crs target-crs
                      :coord-array coord-array}))))

;; camelCase JS aliases for the manually defined fns. The
;; define-all-proj-public-fns macro adds aliases for fndefs fns.
#?(:cljs (def init init!))
#?(:cljs (def shutdown shutdown!))
#?(:cljs (def flushPendingDisposes flush-pending-disposes!))
#?(:cljs (def getPoolDetail get-pool-detail))

#?(:cljs (def setCoords set-coords!))
#?(:cljs (def getCoords get-coords))
#?(:cljs (def getWorkerCount get-worker-count))
#?(:cljs (def contextCreate context-create))
#?(:cljs (def contextPtr context-ptr))
#?(:cljs (def contextDatabasePath context-database-path))
#?(:cljs (def contextSetDatabasePath context-set-database-path))
#?(:cljs (def contextSetEnableNetwork context-set-enable-network))
#?(:cljs (def isContext is-context?))
#?(:cljs (def coordArray coord-array))
#?(:cljs (def coordToCoordArray coord->coord-array))
#?(:cljs (def allocCoordArray alloc-coord-array))
#?(:cljs (def setCoordArray set-coord-array))
#?(:cljs (def getCoordArray get-coord-array))

;; Joint-pool handler surface, re-exported so consumers get it through
;; dist/proj.mjs. handler.cljc must stay in this bundle. A standalone bundle
;; would inline a second ffi-wasm/pool copy, and the pending-dispose flush of
;; pre-terminate! would miss the instance that proj.cljc dispatches through.
;; pre-terminate! itself is not re-exported. It goes out inside the
;; :pre-terminate of the handler spec.
#?(:cljs (def handler-spec handler/spec))
#?(:cljs (def handler-default-init-args handler/default-init-args))
#?(:cljs (def handlerSpec handler-spec))
#?(:cljs (def handlerDefaultInitArgs handler-default-init-args))

