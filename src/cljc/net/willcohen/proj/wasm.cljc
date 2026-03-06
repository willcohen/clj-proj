#?(:clj
   (ns net.willcohen.proj.wasm
     "GraalVM Polyglot context management, WASM module initialization, and type
      conversion between JVM and GraalVM WASM. Exposes init-proj as the unified
      entry point and proj-emscripten-helper as the WASM call dispatcher."
     (:require [clojure.java.io :as io]
               [clojure.string :as string]
               [clojure.tools.logging :as log]
               [net.willcohen.proj.fndefs :as pdefs]
               [net.willcohen.proj.macros :as macros :refer [tsgcd define-all-wasm-fns]])
     (:import [org.graalvm.polyglot Context PolyglotAccess Source]
              [org.graalvm.polyglot.proxy ProxyArray ProxyObject ProxyExecutable]
              [java.util.concurrent CompletableFuture]
              [java.nio ByteBuffer]))
   :cljs
   (ns wasm
     "Worker pool management for browser/Node.js. Maintains worker-pool atom
      (the JS pool object) and context-workers atom (ctx-id -> worker-idx mapping
      for call routing). Exposes init-proj as the unified entry point and
      proj-emscripten-helper as the WASM call dispatcher."
     (:require [cljs.string :as string]
               ["./fndefs.mjs" :as pdefs]
               ["./proj-loader.mjs" :as proj-loader])
     (:require-macros [macros])))

(def ^:dynamic *runtime-log-level* nil)

  ;; Helper function used by macros

#?(:clj
   (def ^:dynamic *load-grids*
     "Whether to load PROJ grid files during GraalVM initialization.
  Loading grids is very slow due to ProxyArray conversion overhead.
  Set to false to skip grid loading for faster initialization."
     false))

;; GraalVM emits "WARNING: The polyglot context is using an implementation that
;; does not support runtime compilation" -- this is expected. It means interpreted
;; WASM (not JIT compiled), which is the normal mode for non-GraalVM-CE JDKs.
#?(:clj
   (def context (-> (Context/newBuilder (into-array String ["js" "wasm"]))
                    (.allowPolyglotAccess PolyglotAccess/ALL)
                    (.option "js.ecmascript-version" "staging")
                    (.option "js.esm-eval-returns-exports" "true")
                    #_(.allowExperimentalOptions true)
                    (.option "js.webassembly" "true")
                    (.out System/out) ; Ensure JS console.log goes to the right place
                    (.err System/err)
                    #_(.option "js.foreign-object-prototype" "true")
                    (.allowIO true)
                    .build)))

#?(:clj
   (defn eval-js
     ([str]
      (eval-js str "src.js"))
     ([str js-name] ; Simplified to remove redundant nested tsgcd call
      (tsgcd (.eval context (.build (Source/newBuilder "js" str js-name)))))))

;; p persists across force-graal!/force-ffi! calls. init-proj checks (nil? @p)
;; to avoid re-initialization. Context can't be fully reset without JVM restart.
(defonce p (atom nil))
(defonce init-promise (atom nil))

;; Worker pool state (CLJS only)
#?(:cljs
   (do
     (defonce ^:private worker-pool (atom nil))
     (defonce ^:private context-workers (atom {}))))  ; ctx-id -> worker-idx

#?(:cljs
   (defn init-workers!
     "Initialize worker pool. Returns promise."
     [opts]
     (let [init-fn (.-initWithWorkers proj-loader)]
       (-> (init-fn (clj->js opts))
           (.then (fn [pool]
                    (reset! worker-pool pool)
                    pool))))))

#?(:cljs
   (defn worker-call
     "Send command to worker, return promise."
     ([cmd] (worker-call 0 cmd))
     ([worker-idx cmd]
      (let [pool @worker-pool
            send-fn (.-sendToWorker pool)]
        (send-fn worker-idx (clj->js cmd))))))

#?(:cljs
   (defn get-mode
     "Returns 'pthreads' or 'single-threaded' based on SharedArrayBuffer availability."
     []
     (when-let [pool @worker-pool]
       (.-mode pool))))

#?(:cljs
   (defn get-worker-count
     "Returns the number of workers in the pool."
     []
     (when-let [pool @worker-pool]
       (alength (.-workers pool)))))

#?(:cljs
   (defn shutdown!
     "Shutdown all workers and clean up resources. Returns promise."
     []
     (let [shutdown-fn (.-shutdown proj-loader)]
       (-> (shutdown-fn)
           (.then (fn []
                    (reset! worker-pool nil)
                    (reset! init-promise nil)
                    nil))))))

#?(:cljs
   (defn worker-idx-from-args
     "Worker-affinity routing: PROJ objects (contexts, PJ transformers) carry a
      .worker_idx property identifying which worker owns them. Scans arguments to
      find the first object with a worker_idx and routes the call to that worker.
      Returns 0 as fallback. Coord arrays are JS-side and have no worker affinity."
     [args]
     (or (some (fn [arg]
                 (cond
                   (and (object? arg) (some? (.-worker-idx arg)))
                   (.-worker-idx arg)
                   (and (map? arg) (:worker-idx arg))
                   (:worker-idx arg)
                   :else nil))
               args)
         0)))

#?(:cljs
   (defn- assign-worker-for-context
     "Assign a worker to a new context. Round-robin by default."
     [opts]
     (let [pool @worker-pool
           workers (.-workers pool)
           worker-count (alength workers)]
       (if-let [explicit (:worker opts)]
         (do
           (when (>= explicit worker-count)
             (throw (js/Error. (str "Worker index " explicit " out of range (max " (dec worker-count) ")"))))
           explicit)
         ;; Round-robin
         (let [idx (.-nextWorkerIdx pool)]
           (set! (.-nextWorkerIdx pool) (mod (inc idx) worker-count))
           idx)))))

#?(:cljs
   (defn create-context-on-worker
     "Create a PROJ context on a specific worker. The worker's context_create
      handler does all setup: creates PROJ context, sets database path, enables
      network, sets up log callback. The CLJS side just stores the ctx-id ->
      worker-idx mapping for future call routing. Returns promise of map with
      :ctx-id, :ptr, and :worker-idx."
     [opts]
     (let [worker-idx (assign-worker-for-context opts)]
       (-> (worker-call worker-idx {:cmd "context_create"})
           (.then (fn [result]
                    (let [ctx-id (.-ctxId result)]
                      (swap! context-workers assoc ctx-id worker-idx)
                      {:ctx-id ctx-id
                       :ptr (.-ptr result)
                       :worker-idx worker-idx})))))))

#?(:cljs
   (defn get-context-worker
     "Get the worker index for a context."
     [ctx]
     (let [ctx-id (cond
                    (map? ctx) (:ctx-id ctx)
                    (number? ctx) ctx
                    :else ctx)]
       (get @context-workers ctx-id 0))))

#?(:clj
   (defn- read-resource-bytes [path]
     (with-open [in (io/input-stream (io/resource path))]
       (when-not in (throw (ex-info (str "Could not find resource on classpath: " path) {:path path})))
       (.readAllBytes in))))

(defn init-proj
  "Initialize PROJ - unified for both GraalVM and ClojureScript.
   opts is an optional map. In ClojureScript, supports :workers key (number or \"auto\")."
  ([] (init-proj {}))
  ([opts]
   #?(:clj
       ;; GraalVM initialization
      (locking p
        (when (nil? @p)
          (let [;; Load JS modules from classpath
                proj-js-url (io/resource "wasm/proj-emscripten.js")
                index-js-url (io/resource "wasm/proj-loader.mjs")
                _ (when (or (nil? proj-js-url) (nil? index-js-url))
                    (throw (ex-info "Could not find proj-emscripten JS files on classpath."
                                    {:proj-js-url proj-js-url :index-js-url index-js-url})))

                ;; Pre-load the main PROJ.js module
                _ (tsgcd (let [source (.build (.mimeType (Source/newBuilder "js" (io/file (.toURI proj-js-url))) "application/javascript+module"))]
                           (log/info "Pre-loading PROJ.js module to assist module resolution:" (str proj-js-url))
                           (.eval context source)))

                ;; Load the main index module
                index-js-module (tsgcd (let [source (.build (.mimeType (Source/newBuilder "js" (io/file (.toURI index-js-url))) "application/javascript+module"))]
                                         (log/info "Loading JS module" (str index-js-url))
                                         (.eval context source)))

                _ (log/info "JS module import complete.")

                ;; CompletableFuture for coordination
                init-future (CompletableFuture.)

                ;; Load binary resources
                _ (log/info "Loading binary resources (WASM, proj.db)...")
                wasm-binary-bytes (read-resource-bytes "wasm/proj-emscripten.wasm")
                proj-db-bytes (read-resource-bytes "proj.db")
                proj-ini (slurp (io/resource "proj.ini"))

                ;; Load grid files
                _ (when *load-grids* (log/info "Loading PROJ grid files from resources..."))
                grid-files-map (if *load-grids*
                                 (let [grid-dir-url (io/resource "grids")]
                                   (if grid-dir-url
                                     (let [grid-dir-file (io/file (.toURI grid-dir-url))
                                           grid-files (when (and grid-dir-file (.isDirectory grid-dir-file))
                                                        (->> (file-seq grid-dir-file)
                                                             (filter #(.isFile %))))]
                                       (into {} (map (fn [f]
                                                       [(.getName f) (read-resource-bytes (str "grids/" (.getName f)))]))
                                             grid-files))
                                     (do (log/warn "PROJ grid resource directory not found. Transformations may be inaccurate.")
                                         {})))
                                 {})
                _ (if *load-grids*
                    (log/info (str "Loaded " (count grid-files-map) " grid files."))
                    (log/info "Skipping grid file loading (*load-grids* is false)."))

                ;; Create callbacks as separate ProxyExecutable objects
                success-callback (reify ProxyExecutable
                                   (execute [_ args]
                                     (let [proj-module (if (> (alength args) 0) (aget args 0) nil)]
                                       (log/info "PROJ.js initialization successful via callback.")
                                       (when proj-module
                                         (reset! p proj-module)
                                         (.complete init-future proj-module))
                                       nil)))

                error-callback (reify ProxyExecutable
                                 (execute [_ args]
                                   (let [error (if (> (alength args) 0) (aget args 0) "Unknown error")]
                                     (log/error error "PROJ.js initialization failed in GraalVM")
                                     (.completeExceptionally init-future
                                                             (ex-info "PROJ.js initialization failed"
                                                                      {:error error}))
                                     nil)))

                ;; Create options with callbacks
                graal-opts (ProxyObject/fromMap
                            {"wasmBinary" (ProxyArray/fromArray (object-array (seq wasm-binary-bytes)))
                             "projDb" (ProxyArray/fromArray (object-array (seq proj-db-bytes)))
                             "projIni" proj-ini
                             "projGrids" (ProxyObject/fromMap
                                          (into {} (map (fn [[name bytes]]
                                                          [name (ProxyArray/fromArray (object-array (seq bytes)))])
                                                        grid-files-map)))
                             "onSuccess" success-callback
                             "onError" error-callback})

                ;; Get the initialize function
                _ (log/info "Retrieving 'initialize' function from module.")
                init-fn (.getMember index-js-module "initialize")

                ;; Call initialize(opts) - no return value expected
                _ (log/info "Executing 'initialize' function...")
                _ (tsgcd (.execute init-fn (into-array Object [graal-opts])))]

            ;; Wait for initialization to complete
            (log/info "Waiting for PROJ.js initialization to complete via callback...")
            (.get init-future)
            (log/info "PROJ.js initialization complete. System is ready."))))

      :cljs
      ;; ClojureScript init is async (returns a Promise) because worker creation
      ;; and WASM loading are async, unlike the CLJ side which blocks on
      ;; CompletableFuture.get().
      (if (nil? @init-promise)
        (let [promise (-> (init-workers! opts)
                          (.then (fn [pool]
                                   (js/console.log "PROJ initialized with worker pool:" (.-mode pool))
                                   pool))
                          (.catch (fn [error]
                                    (js/console.error "PROJ worker init failed:" error)
                                    (throw error))))]
          (reset! init-promise promise)
          promise)
        @init-promise))))

(defn- ensure-proj-initialized! []
  (if (nil? @p)
    (init-proj)))

(defn valid-ccall-type?
  "Checks if a keyword represents a valid ccall type."
  [t]
  (#{:number :array :string} t))

(declare arg->js-literal arg-array->js-array-string keyword->js-string keyword-array->js-string)

(defn ^:async proj-emscripten-helper
  [f return-type arg-types args & [proj-returns force-worker-idx]]
  (let [proj-returns (or proj-returns nil)]
    (ensure-proj-initialized!)
    #?(:clj
       ;; GraalVM implementation with exception handling
       (let [p-instance @p
             ccall-fn (.getMember p-instance "ccall")
            ;; Convert args - pointers to integers, nil to 0
            ;; TrackablePointer is a record with :address key
            ;; Context atoms contain {:ptr TrackablePointer ...}
             convert-arg (fn [arg]
                           (cond
                            ;; TrackablePointer record -> extract address
                             (and (record? arg) (contains? arg :address)) (:address arg)
                            ;; Context atom -> extract :ptr's :address
                             (and (instance? clojure.lang.IDeref arg)
                                  (map? @arg)
                                  (contains? @arg :ptr))
                             (let [ptr (:ptr @arg)]
                               (if (and (record? ptr) (contains? ptr :address))
                                 (:address ptr)
                                 ptr))
                             (nil? arg) 0
                             :else arg))
             converted-args (mapv convert-arg args)]
         (when *runtime-log-level* (log/log *runtime-log-level* (str "Graal ccall: " f " " return-type " " arg-types " " converted-args)))
         (try
           (tsgcd
            (.execute ccall-fn (into-array Object [f
                                                   (name return-type)
                                                   (ProxyArray/fromArray (object-array (map name arg-types)))
                                                   (ProxyArray/fromArray (object-array converted-args))])))
           (catch Exception e
             (log/warn (str "Graal ccall exception for " f ": " (.getMessage e)))
             (case return-type
               (:pointer :number) nil
               :string nil
               :int32 0
               :float64 0.0
               :size-t 0
               :void nil
               nil))))
       :cljs
       ;; ClojureScript implementation - proxy through worker
       ;; Coord arrays (JS-side Float64Arrays) are detected, their data is sent
       ;; alongside the ccall, and the worker handles temp malloc/free.
       (let [worker-idx (if (some? force-worker-idx) force-worker-idx (worker-idx-from-args args))
             coord-arrays (into []
                                (keep-indexed
                                 (fn [idx arg]
                                   (when (and (object? arg)
                                              (= (.-type arg) "coord-array"))
                                     {:argIdx idx
                                      :data (.-buffer arg)
                                      :numFloats (.-floatsNeeded arg)}))
                                 args))
             convert-arg (fn [arg]
                           (cond
                             (and (object? arg) (= (.-type arg) "coord-array")) 0
                             (and (map? arg) (:ptr arg)) (:ptr arg)
                             (and (object? arg) (.-ptr arg)) (.-ptr arg)
                             (nil? arg) 0
                             :else arg))
             converted-args (mapv convert-arg args)
             ccall-cmd (cond-> {:cmd "ccall"
                                :fn f
                                :returnType (name return-type)
                                :argTypes (clj->js (mapv name arg-types))
                                :args (clj->js converted-args)}
                         proj-returns (assoc :projReturns (name proj-returns))
                         (seq coord-arrays)
                         (assoc :coordArrays
                                (clj->js (mapv (fn [ca]
                                                 {:argIdx (:argIdx ca)
                                                  :data (js/Array.from (:data ca))
                                                  :numFloats (:numFloats ca)})
                                               coord-arrays))))
             result (do
                      (when *runtime-log-level*
                        (js/console.log "CLJS worker-ccall:" f return-type arg-types
                                        (clj->js converted-args) "worker:" worker-idx
                                        "coordArrays:" (count coord-arrays)))
                      (js-await (worker-call worker-idx ccall-cmd)))]
         (when (seq coord-arrays)
           (let [returned-data (.-coordData result)]
             (dotimes [i (count coord-arrays)]
               (let [ca-info (nth coord-arrays i)
                     original-arg (nth args (:argIdx ca-info))
                     new-data (aget returned-data i)]
                 (.set (.-buffer original-arg) (js/Float64Array.from new-data))))))
         (if (seq coord-arrays)
           (.-result result)
           result)))))

#?(:clj
   (defprotocol Pointerlike
     (address-as-int [this])
     (address-as-string [this])
     (address-as-polyglot-value [this])
     (address-as-trackable-pointer [this])
     (get-value [this type])
     (pointer->string [this])
     (string-array-pointer->strs [this])))

#?(:clj
   (defrecord TrackablePointer [address]
     Pointerlike
     (address-as-int [this] (address-as-int (:address this)))
     (address-as-string [this] (address-as-string (:address this)))
     (address-as-polyglot-value [this] (address-as-polyglot-value (:address this)))
     (address-as-trackable-pointer [this] this)
     (get-value [this type] (get-value (:address this) type))
     (pointer->string [this] (pointer->string (:address this)))
     (string-array-pointer->strs [this] (string-array-pointer->strs (:address this)))))

#?(:clj
   (defn- arg->js-literal [arg]
     (cond
       (string? arg) (pr-str arg) ; Clojure's pr-str handles quoting and escaping
       (number? arg) (str arg)

        ;; Handle TrackablePointer first, as it's a common, specific type in our code.
        ;; Using instance? is the correct way to check for a record type.
       (instance? TrackablePointer arg)
       (str (address-as-int arg))

        ;; Handle polyglot.Value. This check is more robust against classloading issues.
       (if-let [c org.graalvm.polyglot.Value] (instance? c arg) false)
       (if (.isNumber arg)
         (str (.asLong arg))
         (pr-str (.asString arg))) ; Fallback to string representation if not a number

       :else (pr-str arg)))) ; Fallback for other types, pr-str is generally safe

#?(:clj
   (defn- arg-array->js-array-string [arr]
     (str "[" (string/join ", " (map arg->js-literal arr)) "]")))

#?(:clj
   (defn- keyword->js-string [k]
     (str "\"" (name k) "\"")))

#?(:clj
   (defn- keyword-array->js-string [arr]
     (str "[" (string/join ", " (map keyword->js-string arr)) "]")))

#?(:clj
   (extend-protocol Pointerlike
     org.graalvm.polyglot.Value
     (address-as-int [this] (.asInt this))
     (address-as-string [this] (.asString this))
     (address-as-polyglot-value [this] this)
     (address-as-trackable-pointer [this]
       (let [addr (if (.isNumber this) (.asLong this) 0)] ; Default to 0 if not a number
         (when (not (.isNumber this)) (log/error "DEBUG: Polyglot Value is not a number when creating TrackablePointer:" this))
         (->TrackablePointer addr)))
     (get-value [this type]
       (.execute (.getMember @p "getValue") (into-array Object [this type])))
     (pointer->string [this]
       (.asString (.execute (.getMember @p "UTF8ToString") (into-array Object [this]))))
     (string-array-pointer->strs [this]
       (loop [addr this
              result-strings []
              idx 0]
         (when *runtime-log-level*
           (log/log *runtime-log-level* (str "Graal: string-array-pointer->strs - Loop iteration " idx ", reading from address: " (address-as-int addr))))
         (let [;; Read the pointer *value* at addr. This value is the address of a string.
               string-addr-polyglot (get-value (address-as-int addr) "*")
               string-addr-int (address-as-int string-addr-polyglot)]

           (when *runtime-log-level*
             (log/log *runtime-log-level* (str "Graal: string-array-pointer->strs - Pointer at " (address-as-int addr) " points to string at: " string-addr-int)))
           (if (zero? string-addr-int) ; Check for null terminator (0 address)
             (do (when *runtime-log-level*
                   (log/log *runtime-log-level* (str "Graal: string-array-pointer->strs - Found null terminator, returning: " result-strings)))
                 result-strings)
             (let [current-str (pointer->string string-addr-polyglot)] ; Convert the string address to a string
               (when *runtime-log-level*
                 (log/log *runtime-log-level* (str "Graal: string-array-pointer->strs - Read string: " current-str "\"")))
               (recur (address-as-polyglot-value (+ (address-as-int addr) 4)) ; Move to the next pointer in the array (assuming 4-byte pointers)
                      (conj result-strings current-str)
                      (inc idx)))))))

     java.lang.String
     (address-as-int [this] (Integer/parseInt this))
     (address-as-string [this] this)
     (address-as-polyglot-value [this] (tsgcd (.asValue context this)))
     (address-as-trackable-pointer [this] (->TrackablePointer (address-as-int this)))
     (get-value [this type] (get-value (address-as-polyglot-value this) type))
     (pointer->string [this] (pointer->string (address-as-polyglot-value this)))
     (string-array-pointer->strs [this] (string-array-pointer->strs (address-as-polyglot-value this)))

     java.lang.Long
     (address-as-int [this] (int this))
     (address-as-string [this] (str this))
     (address-as-polyglot-value [this] (tsgcd (.asValue context this)))
     (address-as-trackable-pointer [this] (->TrackablePointer (address-as-int this)))
     (get-value [this type] (get-value (address-as-polyglot-value this) type))
     (pointer->string [this] (pointer->string (address-as-polyglot-value this)))
     (string-array-pointer->strs [this] (string-array-pointer->strs (address-as-polyglot-value this)))

     java.lang.Integer
     (address-as-int [this] this)
     (address-as-string [this] (str this))
     (address-as-polyglot-value [this] (tsgcd (.asValue context this)))
     (address-as-trackable-pointer [this] (->TrackablePointer this))
     (get-value [this type] (get-value (address-as-polyglot-value this) type))
     (pointer->string [this] (pointer->string (address-as-polyglot-value this)))
     (string-array-pointer->strs [this] (string-array-pointer->strs (address-as-polyglot-value this)))))

;;;; GraalVM Utility Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: Can these utility functions be simplified using a few HOFs (NOT macros)?
;; TODO: Lots of duplication, so I feel like yes.

#?(:clj
   (defn polyglot-array->jvm-array
     [a]
     (tsgcd
      (do (assert (.hasArrayElements a))
          (let [length (.getArraySize a)]
            (into-array Object (map #(.asDouble (.getArrayElement a %)) (range length))))))))

(defn malloc
  [b]
  (ensure-proj-initialized!)
  #?(:clj
     (tsgcd (address-as-trackable-pointer (.execute (.getMember @p "_malloc") (into-array Object [b]))))
     :cljs
     (let [p-instance @p]
       (._malloc p-instance b))))

(defn heapf64
  [o n]
  (ensure-proj-initialized!)
  (let [offset o]
    #?(:clj
       (tsgcd (.execute (.getMember (.getMember @p "HEAPF64") "subarray")
                        (into-array Object [offset (+ offset n)])))
       :cljs
       (let [p-instance @p]
         (.subarray (.-HEAPF64 p-instance) offset (+ offset n))))))

#?(:clj
   (defn fs-open
     [path flags _]
     (ensure-proj-initialized!)
     (tsgcd (.execute (.getMember (.getMember @p "FS") "open")
                      (into-array Object [path flags nil])))))

#?(:clj
   (defn fs-write
     [stream buffer offset length position _]
     (ensure-proj-initialized!)
     (tsgcd (.execute (.getMember (.getMember @p "FS") "write")
                      (into-array Object [stream buffer offset length position _])))))

#?(:clj
   (defn fs-close
     [stream]
     (ensure-proj-initialized!)
     (tsgcd (.execute (.getMember (.getMember @p "FS") "close")
                      (into-array Object [stream])))))

(defn alloc-coord-array
  [num-coords _dims]
  #?(:clj
     (tsgcd (let [alloc (malloc (* 32 num-coords))
                  array (heapf64 (/ (address-as-int alloc) 8) (* 4 num-coords))]
              {:malloc alloc :array array}))
     :cljs
     (let [alloc (malloc (* 32 num-coords))
           array (heapf64 (/ alloc 8) (* 4 num-coords))]
       {:malloc alloc :array array})))

(defn set-coord-array
  [coord-array allocated]
  (ensure-proj-initialized!)
  #?(:clj
     (tsgcd (do (let [flattened (flatten coord-array) js-array (eval-js "new Array();")]
                  (ensure-proj-initialized!)
                  (doall (map #(tsgcd (.setArrayElement js-array % (nth flattened %))) (range (count flattened))))
                  (tsgcd (.execute (.getMember (:array allocated) "set")
                                   (into-array Object [js-array 0]))))
                allocated))
     :cljs
     (do (let [flattened (flatten coord-array)
               array (:array allocated)]
           (.set array (clj->js flattened) 0))
         allocated)))

#?(:clj
   (defn get-coord-array
     "Read coordinates from a GraalVM-allocated coord array.
      Returns vector of doubles [x y z t] for the given index."
     [allocated idx]
     (let [array (:array allocated)
           offset (* idx 4)]
       (tsgcd
        [(double (.asDouble (.getArrayElement array offset)))
         (double (.asDouble (.getArrayElement array (+ offset 1))))
         (double (.asDouble (.getArrayElement array (+ offset 2))))
         (double (.asDouble (.getArrayElement array (+ offset 3))))]))))

(defn new-coord-array
  [coord-array num-coords]
  (set-coord-array coord-array (alloc-coord-array num-coords 4)))

#?(:clj
   (defn allocate-string-on-heap
     "Allocates a string on the Emscripten heap and returns a pointer."
     [s]
     (when s
       (let [len (+ 1 (alength (.getBytes s "UTF-8")))
             addr (malloc len)]
         (tsgcd (.execute (.getMember @p "stringToUTF8") (into-array Object [s (address-as-polyglot-value addr) len])))
         addr))))

#?(:clj
   (defn string-array-to-polyglot-array
     "Allocates an array of strings on the Emscripten heap and returns a pointer to an array of pointers.
   Each string is allocated individually, and then an array of pointers to these strings is created.
   Returns a Polyglot Value representing the `char**`."
     [s-list]
     (if (empty? s-list)
       (tsgcd (.asValue context 0)) ; Return NULL pointer for empty list
       (let [string-pointers (mapv allocate-string-on-heap s-list)
             num-strings (count string-pointers)
              ;; Allocate memory for the array of pointers (char**)
              ;; Each pointer is 4 bytes on 32-bit Emscripten WASM
             array-of-pointers-size (* (inc num-strings) 4) ; +1 for null terminator
             array-of-pointers-addr (address-as-polyglot-value (malloc array-of-pointers-size))]
         (tsgcd
          (do
             ;; Write each string pointer into the allocated array
            (doseq [idx (range num-strings)]
              (let [ptr (nth string-pointers idx)
                    offset (* idx 4)]
                (.execute (.getMember @p "setValue")
                          (into-array Object [(+ (address-as-int array-of-pointers-addr) offset) (address-as-int ptr) "*"]))))
             ;; Null-terminate the array of pointers
            (.execute (.getMember @p "setValue")
                      (into-array Object [(+ (address-as-int array-of-pointers-addr) (* num-strings 4)) 0 "*"]))
            array-of-pointers-addr))))))

#?(:clj
   (defn free-on-heap
     "Frees a pointer on the Emscripten heap."
     [ptr]
     (ensure-proj-initialized!)
     (when ptr
       (tsgcd (.execute (.getMember @p "_free") (into-array Object [(address-as-polyglot-value ptr)]))))))

(defn- c-name->clj-name [c-fn-keyword]
  (-> (name c-fn-keyword)
      (string/replace (re-pattern "_") "-")
      (symbol)))

(defn ^:async def-wasm-fn-runtime
  "Runtime implementation of WASM function call"
  [fn-key fn-def args & [force-worker-idx]]
  (let [c-fn-name (name fn-key)
        rettype (:rettype fn-def)
        argtypes (:argtypes fn-def)
        proj-returns (:proj-returns fn-def)

        ccall-return-type (case rettype
                            :pointer :number
                            :string :string
                            :void :number
                            :int32 :number
                            :float64 :number
                            :size-t :number
                            :number)

        ccall-arg-types (mapv (fn [[_c-arg-name c-arg-type]]
                                (case c-arg-type
                                  (:pointer :pointer? :string-array :string-array?) :number
                                  :string :string
                                  :int32 :number
                                  :float64 :number
                                  :size-t :number
                                  :number))
                              argtypes)

        result #?(:clj
                  (locking context
                    (proj-emscripten-helper c-fn-name
                                            ccall-return-type
                                            ccall-arg-types
                                            args))
                  :cljs
                  (js-await (proj-emscripten-helper c-fn-name
                                                    ccall-return-type
                                                    ccall-arg-types
                                                    args
                                                    proj-returns
                                                    force-worker-idx)))]
    #?(:clj
       (case rettype
         :pointer (if (nil? result)
                    nil ; Handle nil result from exception handling
                    (address-as-trackable-pointer result))
         :string (if (instance? org.graalvm.polyglot.Value result)
                   (address-as-string result)
                   result)
         :int32 (if (instance? org.graalvm.polyglot.Value result)
                  (address-as-int result)
                  result)
         :float64 (if (instance? org.graalvm.polyglot.Value result)
                    (.asDouble result)
                    result)
         :size-t (if (instance? org.graalvm.polyglot.Value result)
                   (.asLong result)
                   result)
         :void nil
         ;; Default case
         result)
       :cljs
       ;; Wrap PJ pointer results with worker-idx so downstream calls
       ;; (e.g. proj_trans_array) route to the same worker
       (if (and (= proj-returns :pj) (some? result) (not= result 0))
         #js {:ptr result
              :worker_idx (if (some? force-worker-idx) force-worker-idx (worker-idx-from-args args))
              :type "pj"}
         result))))

;; Generate all WASM function wrappers
#?(:clj
   (macros/define-all-wasm-fns pdefs/fndefs c-name->clj-name)
   :cljs
   ;; For ClojureScript, we don't need to generate functions here
   ;; The dispatch system in proj.cljc calls def-wasm-fn-runtime directly
   nil)

