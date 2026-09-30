;; Copyright (c) 2024, 2025, 2026 Will Cohen
;;
;; Part of clj-proj, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

#?(:clj
   (ns net.willcohen.proj.proj
     "The primary clj API for the JVM wrapper of PROJ."
     (:refer-clojure :exclude [await])
     (:require [net.willcohen.proj.impl.native :as native]
               [net.willcohen.proj.impl.logging :as proj-logging]
               [net.willcohen.proj.impl.network :as proj-network]
               [net.willcohen.native.platform-state :as nps]
               [net.willcohen.native.dispatch :as dispatch]
               [net.willcohen.native.ffi-mem :as ffi-mem]
               [tech.v3.resource :as resource]
               [tech.v3.datatype :as dt]
               [tech.v3.datatype.ffi :as dt-ffi]
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
     ;; ffi-wasm is one module. The aliases keep the names of the JVM branch.
     #_{:clj-kondo/ignore [:duplicate-require]}
     (:require [clojure.string :as string]
               ["ffi-wasm" :as nps]
               ["ffi-wasm" :as pool]
               ["ffi-wasm" :as dispatch :refer [underscore->camelCase]]
               [wasm :as wasm]
               [fndefs :as pdefs]
               [macros :refer [define-all-proj-public-fns]]
               ["./handler.mjs" :as handler]
               ["resource-tracker" :as resource])))

#?(:clj (set! *warn-on-reflection* true))

;; squint compiles await; on the JVM the call is already synchronous.
#?(:clj (defmacro await [body] body))

;; defonce because `lib` is a defonce and holds `implementation` by identity.
;; Refer to the platform-state namespace docstring in clj-native.
(defonce implementation (atom nil))
(defonce force-graal    (atom false))
(defn toggle-graal! [] (nps/toggle-graal! implementation force-graal))
(defn force-graal!
  "Force the GraalVM backend: set force-graal and clear implementation, so
   the next init! loads GraalVM. The polyglot Context and its loaded module
   stay until the JVM exits."
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

   opts, all optional:
     :log-level      integer 0..3 for the PROJ logger (CLJS). When set,
                     init! prints its progress.
     :workers        (CLJS) :auto, an integer, or 'auto'. Default :auto.
     :pool           (CLJS) a worker-router WorkerPool to adopt. The caller
                     keeps its lifecycle.
     :max-live-ctxs, :min-age-ms, :debug-level, :debug-categories: (CLJS)
                     refer to the wasm/init-workers! docstring."
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
            ;; Return the chain itself: a dropped chain rejects unhandled
            ;; when init fails, and Node then exits.
            (.then (wasm/init-proj opts)
                   (fn [proj-module]
                     (reset! implementation runtime)
                     (when log-level
                       (js/console.log (str "PROJ initialized with " runtime " implementation")))
                     proj-module))))))))

#?(:cljs
   (defn shutdown!
     "Stop all workers and release resources. Returns a Promise.
      Call it so the Node.js process can exit."
     []
     (wasm/shutdown!)))

#?(:cljs
   (defn flush-pending-disposes!
     "Wait for the in-flight async disposes. Returns a Promise of their
      Promise.allSettled results. shutdown! does this itself."
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
     "The pooled WasmContext of this thread, or nil for the default scope.
      An explicit *wasm-context* binding wins."
     []
     (if-let [bound nw/*wasm-context*]
       (when-not (identical? bound wasm/proj-context) bound)
       (:wc (wp/current-context-or-nil :proj)))))

#?(:clj
   (defn- graal-heap-scope
     "Run heap thunk f with this thread's WasmContext bound, so the nw heap
      primitives do not throw when a second library registers one. No lock:
      the primitives lock per module."
     [f]
     (nw/with-wasm-context (or (pooled-wc) wasm/proj-context) (f))))

#?(:clj
   (defn- graal-call-scope
     "Run dispatch thunk f in this thread's graal scope. On the default
      Context it also takes the global lock, so a multi-call region is atomic."
     [f]
     (if-let [wc (pooled-wc)]
       (nw/with-wasm-context wc (f))
       (with-graal-lock (nw/with-wasm-context wasm/proj-context (f))))))

(defn- pad-coords
  "Pad each coordinate in coords with 0.0 up to width values. A short
   coordinate throws on the tensor path and shifts the later coordinates on
   the ClojureScript path. Anything that is not a sequence of sequences
   passes through."
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
       :graal (graal-heap-scope #(wasm/set-coord-array coords ca)))
     :cljs (await (set-coord-array (pad-coords 4 coords) ca))))

(defn get-coords
  "Read coordinates from a coord-array at the given index.
   Returns [x y z t] for FFI tensors or GraalVM/WASM arrays."
  [ca idx]
  #?(:clj
     (case @implementation
       :ffi [(dt-t/mget ca idx 0) (dt-t/mget ca idx 1)
             (dt-t/mget ca idx 2) (dt-t/mget ca idx 3)]
       :graal (graal-heap-scope #(wasm/get-coord-array ca idx)))
     :cljs
     (get-coord-array ca idx)))

(defn cs
  "Call f with the context as its first argument.

   On the JVM the call runs under a lock on the context atom, because a PROJ
   context is not thread-safe, and the op counter advances after it. A
   swap! cannot hold the call: a concurrent change of the atom would make
   it run the call again.

   On ClojureScript f gets the context object, which routes the call to the
   worker that owns the context. That worker runs its calls one at a time."
  [context f args]
  #?(:clj
     (locking context
       (let [a @context
             ptr (:ptr a)]
         (when-not ptr (throw (ex-info (str "Pointer in context is nil for fn " f) {:f f :context-val a})))
         (let [result (case @implementation
                        :ffi (apply f (cons ptr args))
                        :graal (graal-call-scope #(apply f (cons ptr args))))]
           (swap! context update :op inc)
           result)))
     :cljs
     ;; The context object, not its ptr: dispatch routes on its .worker_idx.
     (apply f (cons context args))))

#?(:clj
   (defn string-array-pointer->strs
     "Decode a NULL-terminated char** into a vector of strings. NULL gives []."
     [ptr]
     (if (nps/null-ptr? ptr)
       []
       (case @implementation
         :ffi (ffi-mem/read-string-array (dt-ptr/ptr-value? ptr))
         :graal (graal-heap-scope
                 #(nw/string-array-pointer->strs (nw/address-as-int ptr)))))))

(declare context-set-database-path context-set-enable-network ensure-initialized! lib
         proj-context-create proj-context-get-database-path
         proj-context-get-user-writable-directory proj-context-set-database-path
         proj-context-set-enable-network proj-context-set-search-paths)

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

   1024 "PROJ_ERR_INVALID_OP - Invalid coordinate operation"
   1025 "PROJ_ERR_INVALID_OP_WRONG_SYNTAX - Invalid pipeline structure or missing +proj"
   1026 "PROJ_ERR_INVALID_OP_MISSING_ARG - Missing required operation parameter"
   1027 "PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE - Illegal parameter value"
   1028 "PROJ_ERR_INVALID_OP_MUTUALLY_EXCLUSIVE_ARGS - Mutually exclusive arguments"
   1029 "PROJ_ERR_INVALID_OP_FILE_NOT_FOUND_OR_INVALID - File not found or invalid"

   2048 "PROJ_ERR_COORD_TRANSFM - Coordinate transformation error"
   2049 "PROJ_ERR_COORD_TRANSFM_INVALID_COORD - Invalid coordinate (for example, lat > 90°)"
   2050 "PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN - Outside projection domain"
   2051 "PROJ_ERR_COORD_TRANSFM_NO_OPERATION - No operation found"
   2052 "PROJ_ERR_COORD_TRANSFM_OUTSIDE_GRID - Point outside grid"
   2053 "PROJ_ERR_COORD_TRANSFM_GRID_AT_NODATA - Grid cell is nodata"
   2054 "PROJ_ERR_COORD_TRANSFM_NO_CONVERGENCE - Iterative convergence failed"
   2055 "PROJ_ERR_COORD_TRANSFM_MISSING_TIME - Operation requires time"

   4096 "PROJ_ERR_OTHER - Other error"
   4097 "PROJ_ERR_OTHER_API_MISUSE - API misuse"
   4098 "PROJ_ERR_OTHER_NO_INVERSE_OP - No inverse operation available"
   4099 "PROJ_ERR_OTHER_NETWORK_ERROR - Network resource access failure"})

(defn error-code->string
  "Convert a PROJ error code to a readable string"
  [code]
  (get proj-error-codes code (str "Unknown error code: " code)))

(defn- errno-error
  "The error that a call of fn-key throws for PROJ errno `errno`."
  [fn-key errno]
  (let [msg (str "PROJ error " errno " in " (name fn-key) ": " (error-code->string errno))]
    #?(:clj  (ex-info msg {:errno errno :fn-key fn-key})
       :cljs (js/Error. msg))))

#?(:cljs
   (defn- build-ctx-destroy-fn
     "The context destroy-fn that resource-tracker holds as heldValue. It is
      a top-level fn for the reason in build-pj-destroy-fn. ctx-pool is the
      pool the context was created on."
     [ctx-pool worker-idx ctx-id]
     (let [fired? (atom false)]
       (fn dispose-ctx! []
         (when (and (wasm/live-pool? ctx-pool) (compare-and-set! fired? false true))
           ;; destroy-context! waits for the child PJ destroys, so
           ;; context_destroy cannot overtake a pending proj_destroy.
           (let [p (.catch (wasm/destroy-context! ctx-pool worker-idx ctx-id)
                           wasm/ignore-pool-terminated)]
             (wasm/untrack-context! worker-idx ctx-id)
             p))))))

#?(:cljs
   (defn- attach-symbol-dispose!
     "Install target[Symbol.dispose], for a kind \"ctx\" or \"pj\" target. A
      top-level fn for the reason in build-pj-destroy-fn."
     [target destroy-fn kind ctx-id worker-idx]
     (aset target js/Symbol.dispose
           (fn []
             ;; The registered release of a PJ also lowers the parent's
             ;; in-flight count, so a parent destroy does not wait for GC.
             ;; The raw destroy-fn covers a handle that is no longer live.
             (or (and (= kind "pj") (pool/dispose-handle! :net.willcohen.proj ctx-id))
                 (pool/fire-and-capture-dispose!
                  destroy-fn
                  #js {:lib "net.willcohen.proj"
                       :kind kind
                       :ctx-id ctx-id
                       :worker worker-idx}))))))

#?(:clj
   (defn- proj-data-dirs
     "The dirs of the PROJ_DATA env var, or of PROJ_LIB, its name before
      PROJ 9.1."
     []
     (let [v (or (System/getenv "PROJ_DATA") (System/getenv "PROJ_LIB"))]
       (when-not (string/blank? v)
         (remove string/blank?
                 (string/split v (re-pattern (java.util.regex.Pattern/quote File/pathSeparator))))))))

#?(:clj
   (defn- resource-search-paths
     "The dirs where PROJ looks for proj.ini and grid files. PROJ does not
      look in the dir of the database. Once a context has search paths, PROJ
      reads only those, so on FFI the user-writable dir and PROJ_DATA stay
      first, in PROJ's own order. Then the dir of the bundled proj.db, and
      its grids dir: PROJ does not search a subdir."
     [ctx-ptr]
     (case @implementation
       :ffi (let [bundle (:path @native/proj)]
              (-> [(proj-context-get-user-writable-directory {:context ctx-ptr :create 0})]
                  (into (proj-data-dirs))
                  (conj bundle (.getPath (File. ^String bundle "grids")))))
       :graal ["/proj" "/proj/grids"])))

#?(:clj
   (defn- context-set-search-paths!
     "Set the resource-search-paths of context atom `a`. Call it before the
      network turns on: that loads proj.ini."
     [a]
     (let [paths (resource-search-paths (:ptr @a))]
       (proj-context-set-search-paths {:context a :count_paths (count paths) :paths paths}))))

#?(:clj
   (defn- context-create-jvm
     "Create a PROJ context on the JVM, then wire its database path,
      logging, and network callbacks. Returns the context atom."
     [enable-network?]
     (let [a (atom {:ptr (proj-context-create {}) :op (long 0)})]
       (context-set-search-paths! a)
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
     (let [;; The pool at create time: a late FR callback must not post this
           ;; ctx-id to the pool of a later init!. Refer to wasm/live-pool?.
           ctx-pool (wasm/current-pool)
           ctx-result (await (wasm/create-context-on-worker opts))
           ctx-id (get ctx-result :ctx-id)
           worker-idx (get ctx-result :worker-idx)
           destroy-fn (build-ctx-destroy-fn ctx-pool worker-idx ctx-id)
           ctx-obj #js {:ptr (get ctx-result :ptr)
                        :ctx_id ctx-id
                        :worker_idx worker-idx
                        :type "proj-context"}]
       ;; A WeakRef entry lets clj-native's sweep fire release once V8
       ;; collects ctx-obj, without the FR-callback-queue lag.
       (wasm/track-context! ctx-id worker-idx (get ctx-result :release) ctx-obj)
       (.track resource ctx-obj
               #js {:disposefn destroy-fn
                    :tracktype "auto"})
       (attach-symbol-dispose! ctx-obj destroy-fn "ctx" ctx-id worker-idx)
       ctx-obj)))

(defn ^:async context-create
  "Create a PROJ context with its database path and grid network set up.
   Returns an atom on the JVM, and a Promise of a JS object on ClojureScript.

   Options:
   - :network  enable grid downloads (default true). JVM only: a
               ClojureScript context always has the network on.
   - :worker   ClojureScript worker index (default: the least-loaded worker)."
  [& args]
  (let [opts (if (map? (first args)) (first args) {})]
    #?(:clj (context-create-jvm (get opts :network true))
       :cljs (await (context-create-cljs opts)))))

(defn context-ptr
  "Return the PROJ pointer of any context type. Works with JVM atoms and
   with ClojureScript plain objects."
  [context]
  #?(:clj (:ptr @context)
     :cljs (.-ptr context)))

(defn context-database-path
  "The path of the database that the context uses. On ClojureScript, a
   Promise."
  [context]
  (proj-context-get-database-path {:context context}))

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
   ;; PROJ treats NULL auxDbPaths/options as none. The fndef types the two
   ;; as :pointer?, so nil passes as NULL.
   (let [blank->nil (fn [x] (if (and (string? x) (string/blank? x)) nil x))]
     (proj-context-set-database-path {:context context :db-path db-path
                                      :aux-db-paths (blank->nil aux-db-paths)
                                      :options (blank->nil options)}))))

(defn context-set-enable-network
  "Set network access for grid downloads on a PROJ context.
   Pass a truthy enabled (1 or true) to enable, or a falsy one
   (0, false, nil) to disable."
  [context enabled]
  (proj-context-set-enable-network {:context context :enabled (if enabled 1 0)}))

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
               :graal (graal-heap-scope #(wasm/alloc-coord-array n dims))
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
         ;; malloc does not zero the block, so a short coord pads to 4.
         :graal (graal-heap-scope
                 #(wasm/set-coord-array (if (sequential? coord) (pad-coords 4 [coord]) coord)
                                        (coord-array 1)))))
     :cljs (set-coord-array coord (coord-array 1))))

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
   FFI only."
     [ca idx vals]
     (-> ca
         (dt-t/transpose [1 0])
         (dt-t/mset! idx vals)
         (dt-t/transpose [1 0]))))

#?(:clj
   (defn set-xcol!
     "Set the X coordinate of all points in a coordinate array.
   FFI only."
     [ca vals]
     (set-col! ca 0 vals)))

#?(:clj
   (defn set-ycol!
     "Set the Y coordinate of all points in a coordinate array.
   FFI only."
     [ca vals]
     (set-col! ca 1 vals)))

#?(:clj
   (defn set-zcol!
     "Set the Z coordinate of all points in a coordinate array.
   FFI only."
     [ca vals]
     (set-col! ca 2 vals)))

#?(:clj
   (defn set-tcol!
     "Set the T (time) coordinate of all points in a coordinate array.
   FFI only."
     [ca vals]
     (set-col! ca 3 vals)))

(defn is-c-context-fn?
  "True when the fn-def describes a context-aware function."
  [_fn-key fn-def]
  (if (contains? fn-def :is-context-fn)
    (:is-context-fn fn-def)
    (contains? #{:context :ctx} (ffirst (:argtypes fn-def)))))

#?(:clj
   (def ^:dynamic ^:private *backend*
     "When bound, the JVM backend of call-native in place of the
      implementation atom. A GC release binds the backend that made its
      result: the atom can change first, and it is nil while init! runs."
     nil))

#?(:clj (declare backend-libs))

;; The wasm heap block of a :string-array? arg on GraalVM. coerce-arg
;; allocates it, and call-native frees it after the call. dispatch passes a
;; record with :address as the address.
#?(:clj
   (defrecord StringArrayBlock [address]))

#?(:clj
   (defn- free-string-array-blocks!
     [args]
     (doseq [a args]
       (when (instance? StringArrayBlock a)
         (nw/free-on-heap (:address a))))))

#?(:cljs
   (defn ^:async dispatch-call
     "dispatch/call! of fn-key on the library value, with the opts of call!,
      :pool included. When the call rejects, destroy the context clone of an
      isolated call, which no result owns. After a NULL or empty result,
      throw on the errno that the worker read in the call."
     [fn-key args opts]
     (let [result (await (.catch (dispatch/call! lib fn-key args opts)
                                 (fn [e]
                                   (wasm/release-unowned-clone! args)
                                   (throw e))))]
       (when-let [errno (wasm/take-call-errno! args)]
         (throw (errno-error fn-key errno)))
       result)))

(defn call-native
  "The leaf of each PROJ call that dispatch-proj-fn makes; tests redefine
   it. fn-def is unused. force-worker-idx (ClojureScript) pins the call to
   one worker. On GraalVM it frees each StringArrayBlock arg after the call."
  ([fn-key args] (call-native fn-key nil args nil))
  ([fn-key fn-def args] (call-native fn-key fn-def args nil))
  ;; Only the :cljs branch reads force-worker-idx, so clj-kondo's :clj
  ;; reader view sees it as unused.
  #_{:clj-kondo/ignore [:unused-binding]}
  ([fn-key _fn-def args force-worker-idx]
   #?(:clj
      (let [l (if *backend* (get @backend-libs *backend*) lib)]
        (if (= :graal (or *backend* @implementation))
          (do (wasm/ensure-proj-initialized!)
              (graal-call-scope
               #(try (dispatch/call! l fn-key args)
                     (finally (free-string-array-blocks! args)))))
          (dispatch/call! l fn-key args)))
      :cljs
      (do
        (wasm/ensure-proj-initialized!)
        ;; :primary-handle connects this call with the PJ-CREATE and
        ;; PJ-DESTROY-WRAPPER trace events on the same handle. Only a
        ;; tracked PJ carries type "pj". A context wrapper also has a ctx_id.
        (let [pj-arg (first (filter (fn [a] (and (object? a) (= "pj" (.-type a)))) args))
              primary-handle (when pj-arg (.-ctx_id pj-arg))]
          (dispatch-call fn-key args
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
  "Look up arg-name in opts under its underscore, hyphenated, and ctx or
   context alias keys."
  [opts arg-name]
  (let [underscore (keyword arg-name)
        hyphenated (keyword (string/replace (name arg-name) "_" "-"))
        context-alias (case arg-name :ctx :context :context :ctx nil)]
    (or (get opts underscore)
        (get opts hyphenated)
        (when context-alias (get opts context-alias)))))

(defn- resolve-default
  "Convert a boolean default of an :int32 arg to 1 or 0."
  [default-val arg-type]
  (cond
    (and (= arg-type :int32) (boolean? default-val))
    (if default-val 1 0)
    :else default-val))

(defn- coerce-arg
  "Coerce a single extracted argument value for the target platform."
  [provided-val arg-type semantics-for-arg]
  (cond
    (and (= arg-type :pointer)
         (map? provided-val)
         (contains? provided-val :malloc))
    (:malloc provided-val)

    (and (some? provided-val)
         (sequential? provided-val)
         (= :string-array? (:semantic-type semantics-for-arg)))
    #?(:clj (if (graal?)
              (->StringArrayBlock
               (nw/address-as-int
                (graal-heap-scope #(wasm/string-list-to-native-array provided-val))))
              (ffi-mem/strings->c-array provided-val))
       :cljs (wasm/string-list-to-native-array provided-val))

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
  ([fn-def opts] (extract-args fn-def opts {}))
  ([fn-def opts {:keys [skip-first?] :or {skip-first? false}}]
   (let [argtypes (if skip-first?
                    (rest (:argtypes fn-def))
                    (:argtypes fn-def))
         argsemantics-map (into {}
                                (map (fn [[arg-name semantic-type & rest-semantics]]
                                       [arg-name (apply hash-map :semantic-type semantic-type rest-semantics)])
                                     (:argsemantics fn-def)))]
     (mapv (fn [arg-spec]
             (let [[arg-name arg-type & rest-spec] arg-spec
                   arg-map (when (seq rest-spec) (apply assoc {} rest-spec))
                   semantics-for-arg (get argsemantics-map arg-name)
                   ;; contains?, not `or`: a :default of false or nil is a
                   ;; real default.
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
                 #?(:clj (context-ptr provided-val)
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
     "The PJ destroy-fn that the FinalizationRegistry holds as heldValue. It
      is a top-level fn so that its closure shares no V8 function-activation
      Context with the FR target: FR holds heldValue strongly, so a shared
      Context pins the target and WeakRef eviction never sees it collected.
      pj-pool is the pool the PJ was created on, so a late fire cannot free
      an address in the pool of a later init!. Refer to wasm/live-pool?."
     [pj-pool worker-idx ptr destroy-fn-name ephemeral-ctx-ptr ephemeral-ctx-worker-idx]
     ;; A squint keyword is a string, so destroy-fn-name is the fn-key.
     (let [fired? (atom false)]
       (fn ^:async dispose! []
                 (when (and (wasm/live-pool? pj-pool) (compare-and-set! fired? false true))
                   ;; Awaited, so the pool's in-flight count drops only after
                   ;; the worker ran proj_destroy.
                   (let [routing #js {:worker_idx worker-idx :ptr ptr}]
                     (await (.catch (dispatch/call! lib destroy-fn-name [routing]
                                                    {:pool pj-pool
                                                     :force-worker-idx worker-idx})
                                    wasm/ignore-pool-terminated)))
                   (when ephemeral-ctx-ptr
                     (let [ctx-routing #js {:worker_idx ephemeral-ctx-worker-idx :ptr ephemeral-ctx-ptr}]
                       (await (.catch (dispatch/call! lib "proj_context_destroy" [ctx-routing]
                                                      {:pool pj-pool
                                                       :force-worker-idx ephemeral-ctx-worker-idx})
                                      wasm/ignore-pool-terminated)))))))))

#?(:clj
   (defonce ^:private proj-destroy-lock
     ;; Serializes every PROJ destroy, explicit or GC, from any thread:
     ;; PROJ's process-global grid cache is not safe to free from two threads.
     (Object.)))

#?(:clj
   (defn- do-native-destroy!
     "Call the native destructor on `pointer`."
     [pointer destroy-fn-name]
     (call-native (keyword destroy-fn-name) [pointer])))

#?(:clj
   (defn release-tracked!
     "Release a tracked PROJ pointer. Returns true if the native destructor
      fired on this call, false if a prior caller (or the GC) already
      released it. `destroy-fn-name` is one of the strings in
      `proj-type->destroy-fn` (\"proj_destroy\", \"proj_context_destroy\", ...)."
     [pointer destroy-fn-name]
     (boolean
      (if-let [release! (::release (meta pointer))]
        (release!)
        (wp/release-once! pointer proj-destroy-lock
                          #(do-native-destroy! pointer destroy-fn-name))))))

#?(:cljs
   (defn- register-pj-handle!
     "Register a tracked PJ under the live-context cap. When nothing is
      evictable, bounded-create-handle! throws {:blocked :bounded-blocked}:
      fire destroy-fn so the new PJ does not leak, then rethrow. parent-key
      is wasm/ctx-key of the parent, because ctx ids repeat across workers."
     [result destroy-fn pj-ctx-id worker-idx parent-ctx-id]
     (try
       (pool/bounded-create-handle!
        :net.willcohen.proj
        (fn []
          (let [parent-key (when parent-ctx-id (wasm/ctx-key worker-idx parent-ctx-id))]
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
      Symbol.dispose. Returns result."
     [result destroy-fn-name]
     (let [worker-idx (.-worker_idx result)
           ptr (.-ptr result)
           pj-ctx-id (.-ctx_id result)
           parent-ctx-id (.-parent_ctx_id result)
           ephemeral-ctx-ptr (.-_ephemeral_context_ptr result)
           ephemeral-ctx-worker-idx (.-_ephemeral_context_worker_idx result)
           destroy-fn (build-pj-destroy-fn (wasm/current-pool) worker-idx ptr
                                           destroy-fn-name
                                           ephemeral-ctx-ptr ephemeral-ctx-worker-idx)]
       (register-pj-handle! result destroy-fn pj-ctx-id worker-idx parent-ctx-id)
       (attach-symbol-dispose! result destroy-fn "pj" pj-ctx-id worker-idx)
       result)))

#?(:clj
   (defn- track-jvm-result!
     "Track a pointer return for release at GC, and return it with its
      release fn under ::release in its metadata. release-tracked! calls the
      same fn, so an explicit release and the GC release free the pointer
      once. The fn closes over the address and not the pointer:
      tech.resource cannot collect an object that its dispose-fn holds. A
      result frees through the backend that made it, a result of a pooled
      GraalVM Context frees under that Context, and a PJ frees before the
      context it carries in its metadata."
     [result destroy-fn-name]
     (let [backend (if (graal?) :graal :ffi)
           addr (if (= :graal backend) (nw/address-as-int result) (dt-ptr/ptr-value? result))
           wc (when (= :graal backend) (pooled-wc))
           owner-ctx (:proj-context (meta result))
           fired (java.util.concurrent.atomic.AtomicBoolean. false)
           release! (fn []
                      (when (.compareAndSet fired false true)
                        (locking proj-destroy-lock
                          (binding [*backend* backend]
                            (let [p (if (= :graal backend)
                                      (nw/->TrackablePointer addr)
                                      (tech.v3.datatype.ffi.Pointer. (long addr)))]
                              (if wc
                                (nw/with-wasm-context wc (do-native-destroy! p destroy-fn-name))
                                (do-native-destroy! p destroy-fn-name)))))
                        ;; PROJ reads the context of a PJ when it frees the
                        ;; PJ. The fence keeps the context reachable, and so
                        ;; unreleased, until then.
                        (java.lang.ref.Reference/reachabilityFence owner-ctx)
                        true))
           tracked (vary-meta result assoc ::release release!)]
       (resource/track
        tracked
        {:dispose-fn #(try
                        (release!)
                        (catch Throwable t
                          (log/warn t "proj/dispose-fn: native destroy failed")))
         :track-type :auto})
       tracked)))

#?(:clj
   (defn- owned-string
     "Read the C string that PROJ allocated for the caller, then free it."
     [ptr]
     (when-not (nps/null-ptr? ptr)
       (let [s (case @implementation
                 :ffi (dt-ffi/c->string ptr)
                 :graal (graal-heap-scope #(nw/pointer->string (nw/address-as-int ptr))))]
         (call-native :proj_string_destroy [ptr])
         s))))

(defn process-return-value-with-tracking
  "Process the return value by proj-returns type and apply resource tracking"
  [result fn-def]
  (let [proj-returns (:proj-returns fn-def)]
    (cond
      ;; CLJS: the worker reads and frees the string.
      (= :owned-string proj-returns)
      #?(:cljs result
         :clj (owned-string result))

      (= :string-list proj-returns)
      ;; CLJS: the worker owns the module, and it decodes and frees the
      ;; list. CLJ: decode the list, then free it.
      #?(:cljs result
         :clj (let [strs (string-array-pointer->strs result)]
                (when-not (nps/null-ptr? result)
                  (call-native :proj_string_list_destroy [result]))
                strs))

      :else
      (let [destroy-fn-name (get proj-type->destroy-fn proj-returns)]
        (if (and destroy-fn-name result)
          #?(:cljs
             ;; Only a :pj result has .ctx_id and gets tracked; the caller destroys the rest.
             ;; TODO: track them too; a plain FR wrap deadlocked the worker mutex.
             (if (and (object? result) (.-ctx_id result))
               (track-pj-result! result destroy-fn-name)
               result)
             :clj (track-jvm-result! result destroy-fn-name))
          result)))))

#?(:clj
   (defn- errno-failure-signal?
     "True for a nil :pointer result or a nil or empty :string result. A
      numeric 0 can be a real result, so numbers are not probed."
     [result fn-def]
     (let [rettype (:rettype fn-def)]
       (cond
         (= rettype :pointer) (nil? result)
         (= rettype :string)  (or (nil? result) (= "" result))
         :else false))))

#?(:clj
   (defonce ^:private errno-pjs
     ;; The no-op PJ that reset-errno! points at a context: one for FFI under
     ;; :ffi, and one for each GraalVM WasmContext, which holds its own
     ;; module. Each PJ and its own context live as long as the module.
     (java.util.WeakHashMap.)))

#?(:clj
   (defn- errno-pj
     "The no-op PJ of the module that call-native uses now."
     []
     (let [k (if (= :graal (or *backend* @implementation))
               (or (pooled-wc) wasm/proj-context)
               :ffi)]
       (locking errno-pjs
         (or (.get ^java.util.WeakHashMap errno-pjs k)
             ;; FFI rejects a NULL :pointer, so the PJ gets a context.
             (let [pj (call-native :proj_create [(call-native :proj_context_create [])
                                                 "+proj=noop"])]
               (.put ^java.util.WeakHashMap errno-pjs k pj)
               pj))))))

#?(:clj
   (defn- reset-errno!
     "Clear the errno of context pointer ctx before a call. PROJ keeps errno
      after a failure, and proj_log_error sets a new code only when errno is
      0. PROJ exports one reset, proj_errno_reset of a PJ, so a no-op PJ
      takes the context first."
     [ctx]
     (let [pj (errno-pj)]
       (locking pj
         (call-native :proj_assign_context [pj ctx])
         (call-native :proj_errno_reset [pj])))))

(defn- call-checked
  "call-native of fn-key with args. When wasm/errno-checked? accepts the
   call, its first arg is the context. On the JVM, reset the errno of the
   context, and after a NULL or empty result throw on the errno of this
   call. On ClojureScript the worker does the two, and dispatch-call
   throws."
  [fn-key fn-def args]
  #?(:clj
     (if (and (wasm/errno-checked? fn-def) (not (nps/null-ptr? (first args))))
       (let [ctx (first args)
             _ (reset-errno! ctx)
             result (call-native fn-key fn-def args)]
         (when (errno-failure-signal? result fn-def)
           (let [errno (call-native :proj_context_errno [ctx])]
             (when (and (number? errno) (not (zero? errno)))
               (throw (errno-error fn-key errno)))))
         result)
       (call-native fn-key fn-def args))
     :cljs (call-native fn-key fn-def args)))

(defn dispatch-context-fn
  "Dispatch functions that use context atomicity through cs"
  [fn-key fn-def context-atom remaining-args]
  (cs context-atom
      (fn [ctx & args]
        (call-checked fn-key fn-def (vec (cons ctx args))))
      remaining-args))

(defn- first-arg-kw [fn-def] (ffirst (:argtypes fn-def)))

(defn- resolve-ctx-from-opts
  "Look up the call's context arg from opts and return the high-level
   atom (JVM) or JS object (CLJS), or nil when the fn-def's first arg
   is not a context."
  [fn-def opts]
  (let [fa (first-arg-kw fn-def)]
    (when (#{:ctx :context} fa)
      (lookup-arg-val opts fa))))

(defn should-use-context-dispatch?
  "True when a function must use context dispatch through cs."
  [fn-key fn-def opts]
  (and (is-c-context-fn? fn-key fn-def)
       (is-context? (resolve-ctx-from-opts fn-def opts))))

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
       (nil? (resolve-ctx-from-opts fn-def opts))))

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
   (defn- ^:async destroy-temporaries!
     "Destroy each PJ of temps on its worker, with the context clone that it
      owns. temps holds untracked PJs, so no other release frees them."
     [temps]
     (doseq [pj temps]
       (when (object? pj)
         (await ((build-pj-destroy-fn (wasm/current-pool) (.-worker_idx pj) (.-ptr pj)
                                      "proj_destroy"
                                      (.-_ephemeral_context_ptr pj)
                                      (.-_ephemeral_context_worker_idx pj))))))))

#?(:cljs
   (defn- ^:async reconcile-cross-worker-args!
     "When PJ/context args come from different workers, recreate mismatched
      ones on the target worker through a PROJJSON roundtrip. Returns
      [opts temps]: a copy of opts with the recreated args, and the PJs that
      the recreation made, which the caller destroys after its call."
     [fn-def opts]
     (let [worker-count (wasm/get-worker-count)]
       (if (or (nil? worker-count) (<= worker-count 1))
         [opts nil]
         (let [pj-args (into []
                             (keep (fn [[arg-spec _]]
                                     (let [arg-name (str arg-spec)
                                           v (get opts arg-spec)]
                                       (when (and (object? v)
                                                  (or (= (.-type v) "pj")
                                                      (= (.-type v) "proj-context")))
                                         {:arg-name arg-name :value v :worker-idx (.-worker_idx v) :type (.-type v)}))))
                             (:argtypes fn-def))
               worker-indices (into #{} (map :worker-idx) pj-args)]
           (if (<= (count worker-indices) 1)
             [opts nil]
             (let [target-worker (.-worker_idx (:value (first (filter #(= (:type %) "pj") pj-args))))
                   desc (str "proj-wasm: PJ args are on different workers ("
                             (string/join ", " (map #(str (:arg-name %) " on worker " (:worker-idx %)) pj-args))
                             "). Recreating on worker " target-worker ". For better performance, use an explicit context.")
                   opts (js/Object.assign #js {} opts)
                   temps #js []]
               (js/console.warn desc)
               (let [target-ctx (or (some (fn [{:keys [value worker-idx type]}]
                                            (when (and (= worker-idx target-worker) (= type "pj"))
                                              (.-_proj_context value)))
                                          pj-args)
                                    (await (context-create {:worker target-worker})))]
                 (try
                   (doseq [{:keys [arg-name value worker-idx type]} pj-args]
                     (when (and (not= worker-idx target-worker) (= type "pj"))
                       (let [src-ctx (or (.-_proj_context value) (await (context-create {:worker worker-idx})))
                             projjson (await (call-native :proj_as_projjson [src-ctx value 0]))]
                         (when (or (nil? projjson) (= projjson ""))
                           (throw (js/Error. (str "Cannot reconcile " arg-name " across workers: PROJJSON export failed. Use an explicit context."))))
                         (let [identity-op (await (call-native :proj_create_crs_to_crs nil
                                                               [target-ctx projjson projjson 0]
                                                               target-worker))
                               _ (.push temps identity-op)
                               new-pj (await (call-native :proj_get_source_crs nil
                                                          [target-ctx identity-op]
                                                          target-worker))]
                           (.push temps new-pj)
                           (aset new-pj "_proj_context" target-ctx)
                           (aset opts arg-name new-pj))))
                     (when (and (not= worker-idx target-worker) (= type "proj-context"))
                       (aset opts arg-name target-ctx)))
                   (catch :default e
                     (await (destroy-temporaries! temps))
                     (throw e)))
                 [opts temps]))))))))

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
     "Memory primitives of the current JVM backend. Build it on each call:
      the backend can change between calls."
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
     "The :struct-list call on the JVM, as one graal-call-scope region."
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
          (let [entries (if (and result-ptr (pos? n))
                          (let [base (addr-of result-ptr)]
                            (mapv #(read-struct (read-ptr (+ base (* % ptr-size))) struct-fields)
                                  (range n)))
                          [])]
            (when result-ptr
              (call-native (keyword struct-destroy-fn) [result-ptr]))
            entries)))))))

#?(:clj
   (defn- out-param-arg?
     "True if an argtype spec is an output parameter (name starts with out_)."
     [[arg-name _arg-type]]
     (string/starts-with? (name arg-name) "out_")))

#?(:clj
   (defn- out-field-alloc-size
     "Byte size for an out-field allocation."
     [field-spec args fn-def pointer-size]
     (case (second field-spec)
       :double 8
       :string pointer-size
       :int 4
       :double-array (* 8 (nth args (.indexOf ^java.util.List (mapv first (:argtypes fn-def))
                                              (nth field-spec 3)))))))

#?(:clj
   (defn- dispatch-out-params-jvm
     "The :out-params call on the JVM, as one graal-call-scope region. args
      holds 0 in each out_ slot."
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
              outs (.iterator ^Iterable (mapv :ptr allocs))
              full-args (mapv #(if (out-param-arg? %1) (.next ^java.util.Iterator outs) %2)
                              (:argtypes fn-def) args)
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
   (defn- camel-keys [obj]
     (let [out #js {}]
       (.forEach (.keys js/Object obj)
                 (fn [k] (aset out (underscore->camelCase k) (aget obj k))))
       out)))

#?(:cljs
   (defn- convert-js-result-keys [result key-casing]
     (cond
       (not= key-casing :camel) result
       (array? result) (.map result camel-keys)
       result (camel-keys result))))

;; Only the :cljs branch reads key-casing. The JVM returns Clojure maps, so
;; no key rewrite is necessary.
#_{:clj-kondo/ignore [:unused-binding]}
(defn- ^:async dispatch-out-data
  "Dispatch for the :struct-list and :out-params return types. Each out_ arg
   is 0 in args, and the call puts its own pointer there."
  [fn-key fn-def opts key-casing]
  (let [args (extract-args fn-def opts)]
    #?(:clj (if (= :out-params (:proj-returns fn-def))
              (dispatch-out-params-jvm fn-key fn-def args)
              (dispatch-struct-list-jvm fn-key fn-def args))
       :cljs (convert-js-result-keys
              (await (call-native fn-key fn-def args))
              key-casing))))

(def ^:private destroy-fn-keys
  (set (map keyword (vals proj-type->destroy-fn))))

(defn- tracked-release
  "The release fn of the tracked PJ or context that destroy fn fn-key frees,
   or nil. The GC release holds the same fn, so the native destroy runs once.
   On ClojureScript it is the Symbol.dispose of the object, whose release
   the FinalizationRegistry and an LRU eviction share. It does not wait: a
   context destroy waits on its worker until its PJs are released."
  [fn-key fn-def opts]
  (when (contains? destroy-fn-keys fn-key)
    (let [v (lookup-arg-val opts (ffirst (:argtypes fn-def)))]
      #?(:clj (::release (meta (if (instance? clojure.lang.IDeref v) (:ptr @v) v)))
         :cljs (when (object? v)
                 (let [dispose (aget v js/Symbol.dispose)]
                   (when (fn? dispose) dispose)))))))

(defn- ^:async dispatch-by-return-type
  "Dispatch fn-key by its return type."
  [fn-key fn-def opts key-casing]
  (let [proj-returns (:proj-returns fn-def)]
    (case proj-returns
      (:struct-list :out-params) (await (dispatch-out-data fn-key fn-def opts key-casing))
      (let [ctx-for-result (when (= :pj proj-returns)
                             (resolve-ctx-from-opts fn-def opts))
            result (if (should-use-context-dispatch? fn-key fn-def opts)
                     (await (dispatch-context-fn fn-key fn-def
                                                 (resolve-ctx-from-opts fn-def opts)
                                                 (get-remaining-args opts fn-def)))
                     (await (call-checked fn-key fn-def (extract-args fn-def opts))))
            result (if ctx-for-result (attach-context-to-result result ctx-for-result) result)]
        ;; Track after the attach. On the JVM the attach returns a copy, and
        ;; the GC must track the object that the caller holds.
        (process-return-value-with-tracking result fn-def)))))

(defn ^:async dispatch-proj-fn
  "Central dispatcher for all PROJ functions"
  [fn-key fn-def opts & [key-casing]]
  (ensure-initialized!)
  (if-let [release! (tracked-release fn-key fn-def opts)]
    (do (release!) nil)
    (let [opts (if (needs-auto-context? fn-key fn-def opts)
                 (let [ctx (or (context-from-pj-args fn-def opts)
                               (await (context-create {})))]
                   (assoc opts :context ctx))
                 opts)]
      #?(:clj (dispatch-by-return-type fn-key fn-def opts key-casing)
         :cljs (let [[opts temps] (await (reconcile-cross-worker-args! fn-def opts))]
                 (try
                   (await (dispatch-by-return-type fn-key fn-def opts key-casing))
                   (finally
                     (when temps (await (destroy-temporaries! temps))))))))))

(defonce lib
  (dispatch/library {:key :net.willcohen.proj
                     :fndefs pdefs/fndefs
                     :impl-atom implementation
                     :ffi-impl-ns 'net.willcohen.proj.impl.native
                     :hooks #?(:cljs {:extras-builder   wasm/proj-extras-builder
                                      :result-wrapper   wasm/proj-result-wrapper
                                      :context-isolator wasm/proj-context-isolator}
                               :clj  nil)}))

#?(:clj
   (def ^:private backend-libs
     "A library value for each JVM backend, with an implementation atom that
      never changes. call-native uses one when *backend* is bound."
     (delay
       (into {}
             (map (fn [impl]
                    [impl (dispatch/library {:key :net.willcohen.proj
                                             :fndefs pdefs/fndefs
                                             :impl-atom (atom impl)
                                             :ffi-impl-ns 'net.willcohen.proj.impl.native})]))
             [:ffi :graal]))))

;; A macro call, so the clj-kondo hook sees the generated fns.
(define-all-proj-public-fns)

(defn transform-batch
  "Transform coord-array in place from source-crs to target-crs with the
   PROJ Context of this workload-pool worker, and a transformer cached per
   CRS pair. Call it only on a pool thread whose :proj handler ran init.
   Returns 0. Throws on a non-zero result or off a pool thread."
  [source-crs target-crs coord-array]
  #?(:clj
     (let [{:keys [ctx tx-cache]} (wp/current-context :proj)
           tx-key [source-crs target-crs]
           tx (or (get @tx-cache tx-key)
                  (let [raw-tx (proj-create-crs-to-crs {:context ctx
                                                        :source_crs source-crs
                                                        :target_crs target-crs
                                                        :area nil})
                        normalized (proj-normalize-for-visualization {:context ctx :obj raw-tx})]
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
           result (proj-trans-array {:p tx :direction 1 :n n :coord coord-array})]
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

;; camelCase JS aliases of the hand-written fns. The macro aliases the rest.
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
#?(:cljs (def handler-spec handler/spec))
#?(:cljs (def handler-default-init-args handler/default-init-args))
#?(:cljs (def handlerSpec handler-spec))
#?(:cljs (def handlerDefaultInitArgs handler-default-init-args))

