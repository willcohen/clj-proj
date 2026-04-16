#?(:clj
   (ns net.willcohen.proj.proj
     "The primary clj API for the JVM wrapper of PROJ."
     (:require [net.willcohen.proj.impl.native :as native]
               [net.willcohen.proj.impl.logging :as proj-logging]
               [net.willcohen.proj.impl.network :as proj-network]
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
               [net.willcohen.proj.fndefs :as pdefs]
               [net.willcohen.proj.macros :refer [define-all-proj-public-fns tsgcd]])
     (:import [tech.v3.datatype.ffi Pointer]
              [java.io File]
              [com.sun.jna StringArray]))
   :cljs
   (ns net.willcohen.proj.proj
     (:require [clojure.string :as string]
               [wasm :as wasm]
               [fndefs :as pdefs]
               ["resource-tracker" :as resource])
     (:require-macros [macros :refer [define-all-proj-public-fns tsgcd]])))

(def implementation (atom nil))
(def force-graal (atom false))
(defn toggle-graal! [] (swap! force-graal not) (reset! implementation nil))
(defn force-graal! [] (reset! force-graal true) (reset! implementation nil))
(defn force-ffi! [] (reset! force-graal false) (reset! implementation nil))

(defn ffi? [] (= :ffi @implementation))
(defn graal? [] (= :graal @implementation))
(defn node? [] (= :node @implementation))

(def p #?(:clj nil
          :cljs wasm/p))

#?(:cljs
   (defn get-value
     [ptr type]
     ((.-getValue @p) ptr type)))

#?(:cljs
   (defn pointer->string
     [ptr]
     ((.-UTF8ToString @p) (get-value ptr "*"))))

#?(:cljs
   (defn alloc-coord-array
     "Allocate a coordinate array as a JS-side Float64Array.
      Data is transferred to the correct worker on demand by proj_trans_array."
     [num-coords _worker-idx]
     (let [floats-needed (* num-coords 4)]
       #js {:buffer (js/Float64Array. floats-needed)
            :numCoords num-coords
            :floatsNeeded floats-needed
            :type "coord-array"})))

#?(:cljs
   (defn set-coord-array
     "Set coordinate values in a JS-side coord array's Float64Array buffer."
     [coord-array allocated]
     (let [flattened (cond
                       (and (array? coord-array)
                            (every? number? coord-array))
                       coord-array

                       (and (array? coord-array)
                            (array? (aget coord-array 0)))
                       (let [result #js []]
                         (dotimes [i (.-length coord-array)]
                           (let [inner (aget coord-array i)]
                             (dotimes [j (.-length inner)]
                               (.push result (aget inner j)))))
                         result)

                       :else
                       (into-array (flatten coord-array)))]
       (.set (.-buffer allocated) (clj->js flattened) 0)
       allocated)))

#?(:cljs
   (defn get-coord-array
     "Read coordinates from a JS-side coord array's Float64Array buffer."
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
   opts is an optional map; in ClojureScript supports :workers (number or \"auto\")."
  ([]
   (init! nil))
  ([log-level]
   (init! log-level {}))
  ([log-level opts]
   #?(:clj
      (let [ffi-succeeded? (atom false)]
        (try
          (when log-level (println (str "Attempting to initialize PROJ library...")))
          (if @force-graal
            (do (when log-level (println "Forcing GraalVM implementation."))
                (wasm/init-proj))
            (try
              (when log-level (println "Attempting FFI implementation."))
              (native/init-proj)
              (reset! ffi-succeeded? true)
              (catch Throwable e
                (println "-------------------- FFI Initialization Failure --------------------")
                (println "FFI initialization failed, falling back to GraalVM.")
                (println (str "Top-level exception: " (.getClass e) " - " (.getMessage e)))
                (when-let [cause (.getCause e)]
                  (println (str "Root cause: " (.getClass cause) " - " (.getMessage cause))))
                (.printStackTrace e)
                (println "------------------------------------------------------------------")
                (wasm/init-proj))))
          (reset! implementation
                  (cond @force-graal :graal
                        @ffi-succeeded? :ffi
                        :else :graal))
          (when log-level (println (str "PROJ library initialized with " (name @implementation) " implementation.")))
          nil)) ;; Return nil for Clojure
      :cljs
      (do
        (when log-level (js/console.log "Attempting to initialize PROJ library for ClojureScript..."))
        ;; Detect runtime environment
        (let [runtime (cond
                        (and (exists? js/process)
                             (exists? js/process.versions)
                             (exists? js/process.versions.node)) :node
                        (exists? js/window) :browser
                        :else :unknown)]
          (when log-level (js/console.log (str "Detected runtime: " runtime)))
          ;; Initialize the PROJ WASM module - returns a promise
          (let [init-promise (wasm/init-proj opts)]
            ;; Chain a handler to set implementation when init completes
            (.then init-promise
                   (fn [proj-module]
                     ; p is now wasm/p, already set by wasm/init-proj
                     (reset! implementation runtime)
                     (when log-level
                       (js/console.log (str "PROJ initialized with " runtime " implementation")))
                     proj-module))
            ;; Return the promise so callers can await it
            init-promise))))))

#?(:cljs
   (defn shutdown!
     "Shutdown all workers and clean up resources. Returns a Promise.
      Call this to allow Node.js process to exit cleanly."
     []
     (wasm/shutdown!)))

#?(:cljs
   (defn get-worker-mode
     "Returns the current worker mode: 'pthreads' or 'single-threaded'.
      'pthreads' means SharedArrayBuffer is available (COOP/COEP headers enabled)
      and the pthreads WASM build can use internal threading.
      'single-threaded' means SharedArrayBuffer is unavailable."
     []
     (wasm/get-mode)))

#?(:cljs
   (defn get-worker-count
     "Returns the number of workers in the pool."
     []
     (wasm/get-worker-count)))

(defn ^:async set-coords!
  "Sets the value of the entire coordinate array.
  If the provided coordinates are shaped the same as the coord-array,
  (in other words, any number of collections of up to four doubles)
  they will be set directly.
  Otherwise, this will attempt to first reshape the provided coordinates into
  such a tensor.
  In ClojureScript, returns a Promise."
  [ca coords]
  #?(:clj
     (case @implementation
       :ffi (cond (= (dt/shape ca) (dt/shape coords))
                  (dt-t/mset! ca coords)
                  :else
                  (dt-t/mset! ca (dt-t/reshape coords (dt/shape ca))))
       :graal (wasm/set-coord-array coords ca))
     :cljs (js-await (set-coord-array coords ca))))

(defn get-coords
  "Read coordinates from a coord-array at the given index.
   Returns [x y z t] for FFI tensors or GraalVM/WASM arrays."
  [ca idx]
  #?(:clj
     (case @implementation
       :ffi [(dt-t/mget ca idx 0) (dt-t/mget ca idx 1)
             (dt-t/mget ca idx 2) (dt-t/mget ca idx 3)]
       :graal (wasm/get-coord-array ca idx))
     :cljs
     (get-coord-array ca idx)))

(defn cs
  "The primary mechanism for attempting to ensure atomicity with contexts.
   In ClojureScript, skips counter updates to avoid SharedArrayBuffer mutations."
  [context f args]
  #?(:clj
     ;; JVM: Keep full atomicity with counters
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
                                    :graal (tsgcd (apply f (cons ptr args)))))))))
     :cljs
     ;; ClojureScript: Skip counters entirely - just call the function
     (let [ptr (context-ptr context)]
       (when-not ptr
         (throw (js/Error. (str "Pointer in context is nil for fn " f))))
       (try
         (case @implementation
           (:node :browser)
           (let [proj-fn (aget @p (str "_" (.-name f)))]
             (if proj-fn
               (apply proj-fn (cons ptr args))
               (throw (js/Error. (str "PROJ function not found: " (.-name f))))))
           (throw (js/Error. (str "Unsupported implementation: " @implementation))))
         (catch :default e
           ;; The C++ exception message already contains the error details
           ;; Just re-throw with function name for context
           (throw (js/Error. (str "PROJ operation failed in " (.-name f) ": " (.-message e)))))))))

#?(:clj
   (defn- string-array-pointer->strs-ffi
     [ptr runtime-log-level]
     (let [jna-ptr (-> ptr dt-ptr/ptr-value com.sun.jna.Pointer.)
           ptrs-array (.getPointerArray jna-ptr 0)
           ptrs (vec ptrs-array)]
       (when runtime-log-level
         (log/log runtime-log-level (str "FFI: string-array-pointer->strs - Input JNA Pointer: " jna-ptr))
         (log/log runtime-log-level (str "FFI: string-array-pointer->strs - Raw pointers array from JNA: " (pr-str ptrs))))
       (loop [remaining-ptrs (seq ptrs)
              result-vec []]
         (if-let [p (first remaining-ptrs)]
           (let [s (try (.getString p 0 "UTF-8")
                        (catch Exception e
                          (log/error e (str "FFI: Failed to read string from pointer " p))
                          nil))]
             (when (and runtime-log-level s)
               (log/log runtime-log-level (str "FFI: string-array-pointer->strs - Read string: \"" s "\" from JNA Pointer: " p)))
             (recur (rest remaining-ptrs) (if s (conj result-vec s) result-vec)))
           result-vec)))))

#?(:cljs
   (defn- string-array-pointer->strs-cljs
     [ptr]
     (if (and @p ptr (not (zero? ptr)))
       (let [result (loop [result []
                           idx 0]
                      (let [str-ptr-addr (+ ptr (* idx 4))
                            str-ptr (get-value str-ptr-addr "*")]
                        (if (or (nil? str-ptr) (zero? str-ptr))
                          result
                          (let [str-val ((.-UTF8ToString @p) str-ptr)]
                            (recur (conj result str-val) (inc idx))))))]
         (to-array result))
       (to-array []))))

(defn string-array-pointer->strs
  "Converts a pointer to a NULL-terminated array of string pointers into a Clojure vector of strings."
  [ptr runtime-log-level]
  #?(:clj
     (case @implementation
       :ffi (string-array-pointer->strs-ffi ptr runtime-log-level)
       :graal (if (nil? ptr)
                []
                (wasm/string-array-pointer->strs (wasm/address-as-int ptr))))
     :cljs (string-array-pointer->strs-cljs ptr)))

(declare context-set-database-path context-set-enable-network
         proj-context-create proj-context-set-database-path
         proj-context-set-enable-network)

(def proj-type->destroy-fn
  "Mapping of PROJ return types to their corresponding destroy functions."
  {:pj "proj_destroy"
   :pj-list "proj_list_destroy"
   :string-list "proj_string_list_destroy"
   :pj-context "proj_context_destroy"
   :pj-crs-list-parameters "proj_get_crs_list_parameters_destroy"
   :pj-insert-session "proj_insert_object_session_destroy"
   :pj-operation-factory-context "proj_operation_factory_context_destroy"})

(def proj-error-codes
  "Mapping of PROJ error codes to descriptions"
  {;; Success
   0 "Success (no error)"

   ;; Invalid operation errors (1024+)
   1024 "PROJ_ERR_INVALID_OP - Invalid coordinate operation"
   1025 "PROJ_ERR_INVALID_OP_WRONG_SYNTAX - Invalid pipeline structure or missing +proj"
   1026 "PROJ_ERR_INVALID_OP_MISSING_ARG - Missing required operation parameter"
   1027 "PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE - Illegal parameter value"
   1028 "PROJ_ERR_INVALID_OP_MUTUALLY_EXCLUSIVE_ARGS - Mutually exclusive arguments"
   1029 "PROJ_ERR_INVALID_OP_FILE_NOT_FOUND_OR_INVALID - File not found or invalid"

   ;; Coordinate transformation errors (2048+)
   2048 "PROJ_ERR_COORD_TRANSFM - Coordinate transformation error"
   2049 "PROJ_ERR_COORD_TRANSFM_INVALID_COORD - Invalid coordinate (e.g. lat > 90°)"
   2050 "PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN - Outside projection domain"
   2051 "PROJ_ERR_COORD_TRANSFM_NO_OPERATION - No operation found"
   2052 "PROJ_ERR_COORD_TRANSFM_OUTSIDE_GRID - Point outside grid"
   2053 "PROJ_ERR_COORD_TRANSFM_GRID_AT_NODATA - Grid cell is nodata"
   2054 "PROJ_ERR_COORD_TRANSFM_NO_CONVERGENCE - Iterative convergence failed"
   2055 "PROJ_ERR_COORD_TRANSFM_MISSING_TIME - Operation requires time"

   ;; Other errors (4096+)
   4096 "PROJ_ERR_OTHER - Other error"
   4097 "PROJ_ERR_OTHER_API_MISUSE - API misuse"
   4098 "PROJ_ERR_OTHER_NO_INVERSE_OP - No inverse operation available"
   4099 "PROJ_ERR_OTHER_NETWORK_ERROR - Network resource access failure"

   ;; Legacy or unknown codes
   44 "Unknown error code 44 - possibly legacy PROJ error or system errno"})

(defn error-code->string
  "Convert PROJ error code to human-readable string"
  [code]
  (get proj-error-codes code (str "Unknown error code: " code)))

(defn ^:async context-create
  "Creates a new PROJ context with grid network fetching configured per platform.

   Network setup flow:
   - FFI: logging callback set via JNA (logging.clj), then JNA network callbacks
     registered via proj_context_set_network_callbacks (network.clj). HTTP handled
     by Java HttpClient via JNA callbacks.
   - GraalVM: network callbacks registered via C stubs (network.clj + proj_network_stubs.c)
     before network is enabled. Callbacks must be registered first so PROJ can use them.
   - CLJS: the worker's context_create handler does everything (set db path, enable
     network, set log callback). CLJS side just stores the routing info.

   For ClojureScript, returns a plain immutable object (no counters) to avoid
   SharedArrayBuffer mutations. For JVM, returns an atom.

   Options:
   - :network - enables network access for grid downloads (default: true)
   - :worker - explicit worker index for CLJS (default: round-robin)"
  [& args]
  (let [opts (if (seq args) (first args) {})
        opts (if (map? opts) opts {})
        enable-network? (get opts :network true)]
    #?(:clj
       ;; JVM: Keep existing atom-based implementation with counters
       (let [tracked-native-ctx (proj-context-create {})
             a (atom {:ptr tracked-native-ctx :op (long 0) :result nil})]
         (context-set-database-path a)
         (when (ffi?)
           (proj-logging/setup-logging! (:ptr @a)))
         ;; Callbacks must be registered before enabling network so PROJ can use them
         (when (and enable-network? (graal?))
           (proj-network/setup-network-callbacks! (wasm/address-as-int (:ptr @a))))
         (when (and enable-network? (ffi?))
           (proj-network/setup-native-network-callbacks! (:ptr @a)))
         (when enable-network?
           (context-set-enable-network a true))
         a)
       :cljs
       ;; ClojureScript: Use worker pool - worker handles context setup
       ;; Worker's context_create command sets db path and enables network
       (do
         (ensure-initialized!)
         (let [ctx-result (js-await (wasm/create-context-on-worker opts))
               ctx-obj #js {:ptr (get ctx-result :ptr)
                            :ctx-id (get ctx-result :ctx-id)
                            :worker_idx (get ctx-result :worker-idx)
                            :type "proj-context"}]
           ctx-obj)))))

(defn context-ptr
  "Extract PROJ pointer from any context type. Works with both JVM atoms and 
   ClojureScript plain objects."
  [context]
  #?(:clj (:ptr @context)
     :cljs (.-ptr context)))

(defn context-database-path
  "Get database path from any context type."
  [context]
  #?(:clj (:database-path @context)
     :cljs (.-database-path context)))

(defn is-context?
  "Check if a value is a PROJ context. Works across platforms."
  [x]
  #?(:clj (and (instance? clojure.lang.IDeref x)
               (map? @x)
               (contains? @x :ptr))
     :cljs (and x
                (.-ptr x)
                (= (.-type x) "proj-context"))))

(defn context-set-database-path
  "High-level wrapper for setting the database path.
   Simplified to always use /proj/proj.db for ClojureScript (standard Emscripten FS)."
  ([context]
   (context-set-database-path context
                              #?(:clj
                                 (case @implementation
                                   :ffi (string/join File/separator
                                                     [(:path @native/proj)
                                                      "proj.db"])
                                   :graal "/proj/proj.db")
                                 :cljs
                                 ;; Always use virtual filesystem path - simple and reliable
                                 "/proj/proj.db")))
  ([context db-path]
   (context-set-database-path context db-path nil nil))
  ([context db-path aux-db-paths options]
   #?(:clj
      (if (ffi?)
        (let [final-aux (if (or (nil? aux-db-paths) (and (string? aux-db-paths) (string/blank? aux-db-paths)))
                          (StringArray. (into-array String []))
                          aux-db-paths)
              final-opts (if (or (nil? options) (and (string? options) (string/blank? options)))
                           (StringArray. (into-array String []))
                           options)]
          (proj-context-set-database-path {:context context :db-path db-path :aux-db-paths final-aux :options final-opts}))
        (proj-context-set-database-path {:context context :db-path db-path :aux-db-paths aux-db-paths :options options}))
      :cljs
      (let [ctx-ptr (context-ptr context)]
        (proj-context-set-database-path {:context ctx-ptr
                                         :db-path db-path
                                         :aux-db-paths aux-db-paths
                                         :options options})))))

(defn context-set-enable-network
  "Enable or disable network access for grid downloads on a PROJ context.
   enabled should be truthy (1 or true) to enable, falsy (0 or false/nil) to disable."
  [context enabled]
  #?(:clj
     (proj-context-set-enable-network {:context context :enabled (if enabled 1 0)})
     :cljs
     (proj-context-set-enable-network {:context (context-ptr context) :enabled (if enabled 1 0)})))

(defn coord-tensor
  [ca dims]
  #?(:clj
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
               :graal (wasm/alloc-coord-array n dims)
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
         :graal (wasm/set-coord-array coord (coord-array 1))))
     :cljs
     (case @implementation
       (:node :browser) (set-coord-array coord (coord-array 1)))))

#?(:clj
   (defn set-coord!
     "Sets the value of a single coordinate in a coord-array tensor.
   The index of the first coord in a tensor is 0.
   If using an array generated with coord-array, the coord provided should be an array of four doubles.
   Otherwise, the coord should have the same shape as a single coordinate in the provided tensor."
     [ca idx coord]
     (dt-t/mset! ca idx coord)))

#?(:clj
   (defn set-col!
     "Sets values for a specific column in a coordinate array tensor.
   Only available for JVM implementation."
     [ca idx vals]
     (-> ca
         (dt-t/transpose [1 0])
         (dt-t/mset! idx vals)
         (dt-t/transpose [1 0]))))

#?(:clj
   (defn set-xcol!
     "Sets X coordinate values for all points in a coordinate array.
   Only available for JVM implementation."
     [ca vals]
     (set-col! ca 0 vals)))

#?(:clj
   (defn set-ycol!
     "Sets Y coordinate values for all points in a coordinate array.
   Only available for JVM implementation."
     [ca vals]
     (set-col! ca 1 vals)))

#?(:clj
   (defn set-zcol!
     "Sets Z coordinate values for all points in a coordinate array.
   Only available for JVM implementation."
     [ca vals]
     (set-col! ca 2 vals)))

#?(:clj
   (defn set-tcol!
     "Sets T (time) coordinate values for all points in a coordinate array.
   Only available for JVM implementation."
     [ca vals]
     (set-col! ca 3 vals)))

(defn is-c-context-fn?
  "Determines if a function is context-aware based on its definition."
  [fn-key fn-def]
  (let [arg-specs (:argtypes fn-def)]
    (cond
       ;; Explicit override in fn-defs takes precedence
      (contains? fn-def :is-context-fn)
      (:is-context-fn fn-def)

       ;; Explicitly mark known destroy functions that take a context as non-context-managed
      (#{:proj_context_destroy :proj_operation_factory_context_destroy} fn-key)
      false

       ;; Heuristic: if first arg is context or ctx, it's a context function
      :else (boolean (and (sequential? arg-specs)
                          (seq arg-specs)
                          (let [first-arg (first arg-specs)]
                            (and (sequential? first-arg)
                                 (seq first-arg)
                                 (#{'context 'ctx} (first first-arg)))))))))

(defn call-ffi-fn
  "Dispatch to FFI implementation. PROJ C functions returning const char* may
   return NULL (e.g. proj_as_proj_string on non-exportable types). dtype-next's
   generated wrappers call c->string on the result, which throws
   IllegalArgumentException on NULL. We catch that and return nil."
  [fn-key args]
  (let [native-fn (ns-resolve 'net.willcohen.proj.impl.native (symbol (name fn-key)))]
    (if native-fn
      (try
        (apply native-fn args)
        (catch IllegalArgumentException e
          (if (re-find #"PToPointer" (.getMessage e))
            nil
            (throw e))))
      (throw (ex-info "Native function not found" {:fn fn-key})))))

(defn call-graal-fn
  "Dispatch to Graal/WASM implementation"
  [fn-key fn-def args]
  (let [clj-name (-> (name fn-key)
                     (string/replace #"_" "-")
                     (symbol))
        wasm-fn (ns-resolve 'net.willcohen.proj.wasm clj-name)]
    (if wasm-fn
      (apply wasm-fn args)
      (throw (ex-info "WASM function not found" {:fn fn-key :looking-for clj-name})))))

#?(:cljs
   (defn call-cljs-fn
     "Dispatch to ClojureScript implementation"
     [fn-key fn-def args]
     (wasm/def-wasm-fn-runtime fn-key fn-def args)))

(defn ensure-initialized!
  "Ensure PROJ is initialized before dispatching"
  []
  #?(:clj
     ;; JVM auto-initializes on first use
     (when (nil? @implementation)
       (init!)
       (when (nil? @implementation)
         (throw (ex-info "Failed to initialize PROJ" {}))))
     :cljs
     ;; CLJS - check that implementation is set (init! was called)
     (when (nil? @implementation)
       ;; Don't throw - just log warning. The test calls init! before using
       (js/console.warn "PROJ may not be initialized - ensure proj/init! was called"))))

(defn- lookup-arg-val
  "Look up an argument value from opts, trying underscore, hyphenated, and
   context-alias key forms. Handles both Clojure maps and JS objects."
  [opts arg-name]
  (let [arg-kw-underscore (keyword arg-name)
        arg-kw-hyphenated (keyword
                           #?(:clj (clojure.string/replace (name arg-name) #"_" "-")
                              :cljs (-> (name arg-name)
                                        (.replace (js/RegExp. "_" "g") "-"))))
        context-alias (case arg-name ctx "context" context "ctx" nil)]
    #?(:clj (or (get opts arg-kw-underscore)
                (get opts arg-kw-hyphenated)
                (when context-alias
                  (get opts (keyword context-alias))))
       :cljs (if (object? opts)
               (or (aget opts (name arg-name))
                   (aget opts (-> (name arg-name)
                                  (.replace (js/RegExp. "_" "g") "-")))
                   (when context-alias
                     (aget opts context-alias)))
               (or (get opts arg-kw-underscore)
                   (get opts arg-kw-hyphenated)
                   (when context-alias
                     (get opts (keyword context-alias))))))))

(defn- resolve-default
  "Resolve a default value: dereference symbol refs, convert booleans for int32."
  [default-val arg-type]
  (cond
    (symbol? default-val)
    #?(:clj (if-let [resolved (ns-resolve 'net.willcohen.proj.fndefs default-val)]
              @resolved
              default-val)
       :cljs default-val)
    (and (= arg-type :int32) (boolean? default-val))
    (if default-val 1 0)
    :else default-val))

(defn- coerce-arg
  "Coerce a single extracted argument value for the target platform."
  [provided-val arg-type semantics-for-arg]
  (cond
    ;; JS-side coord arrays (CLJS) -- pass through for worker data transfer
    (and (= arg-type :pointer)
         #?(:cljs (and (object? provided-val)
                       (= (.-type provided-val) "coord-array"))
            :clj false))
    provided-val

    ;; coord-array maps (GraalVM/WASM) -- extract the malloc pointer
    (and (= arg-type :pointer)
         (map? provided-val)
         (contains? provided-val :malloc))
    (:malloc provided-val)

    ;; string-array? semantic -- convert vector of strings to char**
    (and (some? provided-val)
         (sequential? provided-val)
         (= :string-array? (:semantic-type semantics-for-arg)))
    #?(:clj (if (graal?)
              (wasm/string-list-to-native-array provided-val)
              (StringArray. (into-array String provided-val)))
       :cljs (wasm/string-list-to-native-array provided-val))

    ;; Nil pointer args become 0 (null pointer)
    (and (nil? provided-val) (#{:pointer :pointer?} arg-type)) 0

    ;; Nil string args: "" on FFI (string->c needs a string), 0 on WASM (NULL)
    (and (nil? provided-val) (= arg-type :string))
    #?(:clj (if (ffi?) "" 0)
       :cljs 0)

    :else provided-val))

(defn extract-args
  "Extract arguments from opts map based on function definition, applying defaults.
   Supports both underscore and hyphenated parameter names for better usability.
   Checks both :argtypes inline defaults and :argsemantics for default values."
  [fn-def opts & {:keys [skip-first?] :or {skip-first? false}}]
  (let [argtypes (if skip-first?
                   (rest (:argtypes fn-def))
                   (:argtypes fn-def))
        argsemantics-map (into {}
                               (map (fn [[arg-name semantic-type & rest-semantics]]
                                      [(symbol (name arg-name))
                                       (merge {:semantic-type semantic-type}
                                              (if (seq rest-semantics)
                                                (apply hash-map rest-semantics)
                                                {}))])
                                    (:argsemantics fn-def)))]
    (mapv (fn [arg-spec]
            (let [[arg-name arg-type & rest-spec] arg-spec
                  arg-map (when (seq rest-spec) (apply hash-map rest-spec))
                  semantics-for-arg (get argsemantics-map arg-name)
                  default-val (or (get arg-map :default)
                                  (get semantics-for-arg :default))
                  has-default? (or (contains? arg-map :default)
                                   (contains? semantics-for-arg :default))
                  is-context-arg (contains? #{'ctx 'context} arg-name)
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
          argtypes)))

(defn dispatch-to-platform-with-args
  "Dispatch to platform implementation with pre-extracted args"
  [fn-key fn-def args]
  #?(:clj
     (case @implementation
       :ffi (call-ffi-fn fn-key args)
       :graal (call-graal-fn fn-key fn-def args)
       (throw (ex-info "Unknown implementation" {:implementation @implementation})))
     :cljs
     (case @implementation
       (:node :browser) (call-cljs-fn fn-key fn-def args)
       (throw (js/Error. (str "Unknown implementation: " @implementation))))))

(defn process-return-value-with-tracking
  "Process return value based on proj-returns type and handle resource tracking"
  [result fn-def]
  (let [proj-returns (:proj-returns fn-def)]
    (case proj-returns
      ;; For CLJS, string-list is already processed in worker, just return result
      ;; For CLJ, convert the pointer to strings
      :string-list #?(:cljs result
                      :clj (string-array-pointer->strs result nil))
      ;; Track all PROJ pointer types
      #?(:cljs
         (if-let [destroy-fn-name (proj-type->destroy-fn proj-returns)]
           (when result
             (.track resource result
                     #js {:disposefn (fn []
                                       (when @p
                                         (wasm/proj-emscripten-helper destroy-fn-name :void [:pointer] [result])))
                          :tracktype "auto"})
             result)
           ;; No tracking needed for this type
           result)
         :clj
         (if-let [destroy-fn-name (proj-type->destroy-fn proj-returns)]
           (when result
             ;; Memory leak debugging (FFI): (resource/set-gc-reporting! true) to log cleanup,
             ;; (resource/resource-info) to inspect tracked objects, (resource/print-stack-traces!)
             ;; to see allocation sites.
             (resource/track
              result
              {:dispose-fn (fn []
                              ;; The tracked value (result) is captured in closure
                             (cond
                               (ffi?)
                               (call-ffi-fn (keyword destroy-fn-name) [result])

                               (graal?)
                               (let [destroy-fn-def (get pdefs/fndefs (keyword destroy-fn-name))]
                                 (call-graal-fn (keyword destroy-fn-name) destroy-fn-def [result]))

                               :else
                               (throw (ex-info "Unknown implementation for resource cleanup"
                                               {:implementation @implementation}))))
               :track-type :auto})
             result)
           ;; No tracking needed
           result)))))

(defn dispatch-context-fn
  "Handle dispatch for functions that use context atomicity via cs"
  [fn-key fn-def context-atom remaining-args]
  (cs context-atom
      (fn [ctx & args]
        (let [full-args (vec (cons ctx args))]
          (dispatch-to-platform-with-args fn-key fn-def full-args)))
      remaining-args))

(defn- resolve-context-val
  "Look up the context arg from opts, checking both :ctx and :context."
  [opts first-arg-name]
  (or (get opts first-arg-name)
      (when (#{:ctx :context} first-arg-name)
        (get opts (if (= first-arg-name :ctx) :context :ctx)))))

(defn- first-arg-kw
  "Keyword of the first argtype name, or nil if argtypes is empty."
  [fn-def]
  (when (seq (:argtypes fn-def))
    (keyword (first (first (:argtypes fn-def))))))

(defn should-use-context-dispatch?
  "Determine if a function should use context dispatch via cs"
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
  "Extract args for context functions (skipping the first context arg)"
  [opts fn-def]
  (let [first-arg-name (first-arg-kw fn-def)]
    (extract-args fn-def (dissoc (dissoc opts first-arg-name)
                                 (if (= first-arg-name :ctx) :context :ctx))
                  :skip-first? true)))

(defn- needs-auto-context?
  "Check if a context function was called without a context argument"
  [fn-key fn-def opts]
  (and (is-c-context-fn? fn-key fn-def)
       (let [first-arg-name (first-arg-kw fn-def)]
         (nil?
          #?(:clj (resolve-context-val opts first-arg-name)
             :cljs (if (object? opts)
                     (or (aget opts (name first-arg-name))
                         (when (#{:ctx :context} first-arg-name)
                           (aget opts (if (= first-arg-name :ctx) "context" "ctx"))))
                     (resolve-context-val opts first-arg-name)))))))

(defn- context-from-pj-args
  "Extract a stored context from the first PJ arg in opts."
  [fn-def opts]
  (some (fn [[arg-spec _]]
          (let [v #?(:clj (get opts (keyword arg-spec))
                     :cljs (if (object? opts)
                             (aget opts (name arg-spec))
                             (get opts (keyword arg-spec))))]
            #?(:clj (when (and v (map? (meta v)) (:proj-context (meta v)))
                      (:proj-context (meta v)))
               :cljs (when (and (object? v) (some? (.-_proj_context v)))
                       (.-_proj_context v)))))
        (rest (:argtypes fn-def))))

(defn- attach-context-to-result
  "Attach the context used for creation onto a PJ result object."
  [result ctx]
  (when result
    #?(:clj (if (instance? clojure.lang.IObj result)
              (vary-meta result assoc :proj-context ctx)
              result)
       :cljs (when (object? result)
               (aset result "_proj_context" ctx)
               result))))

#?(:cljs
   (defn- ^:async reconcile-cross-worker-args!
     "When PJ/context args come from different workers, recreate mismatched ones
      on the target worker via PROJJSON roundtrip."
     [fn-def opts]
     (let [worker-count (wasm/get-worker-count)]
       (if (or (nil? worker-count) (<= worker-count 1))
         opts
         (let [pj-args (into []
                             (keep (fn [[arg-spec _]]
                                     (let [arg-name (name arg-spec)
                                           v (if (object? opts)
                                               (aget opts arg-name)
                                               (get opts (keyword arg-spec)))]
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
                                    (js-await (context-create {:worker target-worker})))]
                 (doseq [{:keys [arg-name value worker-idx type]} pj-args]
                   (when (and (not= worker-idx target-worker) (= type "pj"))
                     (let [src-ctx (or (.-_proj_context value) (js-await (context-create {:worker worker-idx})))
                           projjson (js-await (wasm/def-wasm-fn-runtime
                                                :proj_as_projjson
                                                (get pdefs/fndefs-raw :proj_as_projjson)
                                                [src-ctx value 0]))]
                       (when (or (nil? projjson) (= projjson ""))
                         (throw (js/Error. (str "Cannot reconcile " arg-name " across workers: PROJJSON export failed. Use an explicit context."))))
                       (let [identity-op (js-await (wasm/def-wasm-fn-runtime
                                                     :proj_create_crs_to_crs
                                                     (get pdefs/fndefs-raw :proj_create_crs_to_crs)
                                                     [target-ctx projjson projjson 0]
                                                     target-worker))
                             new-pj (js-await (wasm/def-wasm-fn-runtime
                                                :proj_get_source_crs
                                                (get pdefs/fndefs-raw :proj_get_source_crs)
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
     "Read a single struct field from a JNA Pointer using the dtype-next struct offset."
     [^com.sun.jna.Pointer jna-ptr offset field-type]
     (case field-type
       :string (let [p (.getPointer jna-ptr (int offset))]
                 (when p (.getString p 0 "UTF-8")))
       :int (.getInt jna-ptr (int offset))
       :double (.getDouble jna-ptr (int offset))
       :boolean (not= 0 (.getInt jna-ptr (int offset))))))

#?(:clj
   (defn- read-struct-ffi
     "Read a C struct into a Clojure map using dtype-next struct definition for offsets."
     [^com.sun.jna.Pointer jna-ptr struct-def-key struct-fields]
     (let [layout (:layout-map (dt-struct/get-struct-def struct-def-key))]
       (persistent!
        (reduce (fn [m [kw field-type _wasm-offset]]
                  (let [offset (:offset (get layout kw))]
                    (assoc! m kw (read-struct-field-ffi jna-ptr offset field-type))))
                (transient {})
                struct-fields)))))

#?(:clj
   (defn- read-struct-wasm
     "Read a C struct from WASM memory using explicit offsets."
     [struct-addr struct-fields]
     (let [get-value-fn (.getMember @wasm/p "getValue")
           utf8-to-string-fn (.getMember @wasm/p "UTF8ToString")
           gv (fn [ptr type] (.asInt (.execute get-value-fn (object-array [ptr type]))))
           gv-double (fn [ptr] (.asDouble (.execute get-value-fn (object-array [ptr "double"]))))
           read-str (fn [addr]
                      (if (zero? addr) nil
                          (.asString (.execute utf8-to-string-fn (object-array [addr])))))]
       (persistent!
        (reduce (fn [m [kw field-type wasm-offset]]
                  (assoc! m kw
                          (case field-type
                            :string (read-str (gv (+ struct-addr wasm-offset) "*"))
                            :int (gv (+ struct-addr wasm-offset) "i32")
                            :double (gv-double (+ struct-addr wasm-offset))
                            :boolean (not= 0 (gv (+ struct-addr wasm-offset) "i32")))))
                (transient {})
                struct-fields)))))

#?(:clj
   (defn- dispatch-struct-list-ffi
     "Handle struct-list dispatch for FFI platform."
     [fn-key fn-def args]
     (ensure-struct-defs!)
     (let [{:keys [struct-def struct-fields struct-destroy-fn
                   struct-params-create struct-params-destroy]} fn-def
           count-ptr (com.sun.jna.Memory. 4)
           _ (.setInt count-ptr 0 0)
           params-ptr (when struct-params-create
                        (call-ffi-fn (keyword struct-params-create) []))
           args (mapv (fn [[arg-spec arg-type] arg-val]
                        (let [aname (str arg-spec)]
                          (cond
                            (= aname "out_result_count") count-ptr
                            (and (= aname "params") params-ptr) params-ptr
                            (and (= arg-type :string) (nil? arg-val)) ""
                            :else arg-val)))
                      (:argtypes fn-def) args)
           result-ptr (call-ffi-fn fn-key args)
           count (.getInt count-ptr 0)]
       (when params-ptr
         (call-ffi-fn (keyword struct-params-destroy) [params-ptr]))
       (if (and result-ptr (pos? count))
         (let [jna-ptr (-> result-ptr dt-ptr/ptr-value com.sun.jna.Pointer.)
               entries (mapv (fn [i]
                               (read-struct-ffi
                                (.getPointer jna-ptr (* i 8))
                                struct-def struct-fields))
                             (range count))]
           (call-ffi-fn (keyword struct-destroy-fn) [result-ptr])
           entries)
         (do
           (when result-ptr
             (call-ffi-fn (keyword struct-destroy-fn) [result-ptr]))
           [])))))

#?(:clj
   (defn- dispatch-struct-list-graal
     "Handle struct-list dispatch for GraalVM/WASM platform."
     [fn-key fn-def args]
     (let [{:keys [struct-fields struct-destroy-fn
                   struct-params-create struct-params-destroy]} fn-def
           malloc-fn (.getMember @wasm/p "_malloc")
           free-fn (.getMember @wasm/p "_free")
           ccall-fn (.getMember @wasm/p "ccall")
           count-ptr (.asInt (.execute malloc-fn (object-array [4])))
           params-ptr (when struct-params-create
                        (.asInt (.execute ccall-fn
                                          (into-array Object
                                                      [(name struct-params-create) "number"
                                                       (org.graalvm.polyglot.proxy.ProxyArray/fromArray (object-array []))
                                                       (org.graalvm.polyglot.proxy.ProxyArray/fromArray (object-array []))]))))
           args (mapv (fn [[arg-spec arg-type] arg-val]
                        (let [aname (str arg-spec)]
                          (cond
                            (= aname "out_result_count") count-ptr
                            (and (= aname "params") params-ptr) params-ptr
                            (= arg-type :string) (or arg-val "")
                            (nil? arg-val) 0
                            (and (= arg-type :pointer) (is-context? arg-val))
                            (wasm/address-as-int (context-ptr arg-val))
                            (= arg-type :pointer) (wasm/address-as-int arg-val)
                            :else arg-val)))
                      (:argtypes fn-def) args)
           arg-types (mapv (fn [[_ t]] (name (wasm/argtype->ccall-type t)))
                           (:argtypes fn-def))
           list-ptr (.asInt
                     (.execute ccall-fn
                               (into-array Object
                                           [(string/replace (name fn-key) "-" "_")
                                            "number"
                                            (org.graalvm.polyglot.proxy.ProxyArray/fromArray
                                             (object-array arg-types))
                                            (org.graalvm.polyglot.proxy.ProxyArray/fromArray
                                             (object-array args))])))
           get-value-fn (.getMember @wasm/p "getValue")
           count (.asInt (.execute get-value-fn (object-array [count-ptr "i32"])))]
       (.execute free-fn (object-array [count-ptr]))
       (when (and params-ptr struct-params-destroy)
         (.execute ccall-fn
                   (into-array Object
                               [(name struct-params-destroy) "void"
                                (org.graalvm.polyglot.proxy.ProxyArray/fromArray (object-array ["number"]))
                                (org.graalvm.polyglot.proxy.ProxyArray/fromArray (object-array [params-ptr]))])))
       (if (and (not= list-ptr 0) (pos? count))
         (let [entries (mapv (fn [i]
                               (let [struct-addr (.asInt (.execute get-value-fn
                                                                   (object-array [(+ list-ptr (* i 4)) "*"])))]
                                 (read-struct-wasm struct-addr struct-fields)))
                             (range count))]
           (.execute ccall-fn
                     (into-array Object
                                 [(name struct-destroy-fn) "void"
                                  (org.graalvm.polyglot.proxy.ProxyArray/fromArray (object-array ["number"]))
                                  (org.graalvm.polyglot.proxy.ProxyArray/fromArray (object-array [list-ptr]))]))
           entries)
         (do
           (when (not= list-ptr 0)
             (.execute ccall-fn
                       (into-array Object
                                   [(name struct-destroy-fn) "void"
                                    (org.graalvm.polyglot.proxy.ProxyArray/fromArray (object-array ["number"]))
                                    (org.graalvm.polyglot.proxy.ProxyArray/fromArray (object-array [list-ptr]))])))
           [])))))

(defn- out-param-arg?
  "True if an argtype spec is an output parameter (name starts with out_)."
  [[arg-name _arg-type]]
  (let [s (str arg-name)]
    (and (>= (count s) 4) (= "out_" (subs s 0 4)))))

(defn- out-field-alloc-size
  "Byte size needed for an out-field allocation.
   pointer-size is 8 for native FFI (64-bit), 4 for WASM (32-bit)."
  [field-spec args fn-def pointer-size]
  (let [field-type (second field-spec)]
    (case field-type
      :double 8
      :string pointer-size
      :int 4
      :double-array (let [count-arg-name (nth field-spec 3)
                          count-arg-kw (keyword count-arg-name)
                          input-argtypes (vec (remove out-param-arg? (:argtypes fn-def)))
                          idx (.indexOf (mapv #(keyword (first %)) input-argtypes) count-arg-kw)]
                      (* 8 (nth args idx))))))

#?(:clj
   (defn- dispatch-out-params-ffi
     "Handle out-params dispatch for FFI platform.
      args contains only user-provided (non-out) arguments from extract-args."
     [fn-key fn-def args]
     (let [ptr-size 8
           out-fields (:out-fields fn-def)
           allocs (mapv (fn [field-spec]
                          (let [size (out-field-alloc-size field-spec args fn-def ptr-size)]
                            (doto (com.sun.jna.Memory. size)
                              (.clear))))
                        out-fields)
           input-idx (atom 0)
           alloc-idx (atom 0)
           full-args (mapv (fn [argtype]
                             (if (out-param-arg? argtype)
                               (let [i @alloc-idx]
                                 (swap! alloc-idx inc)
                                 (nth allocs i))
                               (let [i @input-idx]
                                 (swap! input-idx inc)
                                 (nth args i))))
                           (:argtypes fn-def))
           result (call-ffi-fn fn-key full-args)]
       (if (and (number? result) (zero? result))
         nil
         (persistent!
          (reduce-kv
           (fn [m i field-spec]
             (let [[field-name field-type] field-spec
                   ^com.sun.jna.Memory alloc (nth allocs i)]
               (assoc! m field-name
                       (case field-type
                         :double (.getDouble alloc 0)
                         :int (.getInt alloc 0)
                         :string (let [p (.getPointer alloc 0)]
                                   (when p (.getString p 0 "UTF-8")))
                         :double-array (let [n (/ (.size alloc) 8)]
                                         (mapv #(.getDouble alloc (* % 8)) (range n)))))))
           (transient {})
           out-fields))))))

#?(:clj
   (defn- dispatch-out-params-graal
     "Handle out-params dispatch for GraalVM/WASM platform.
      args contains only user-provided (non-out) arguments from extract-args."
     [fn-key fn-def args]
     (let [ptr-size 4
           out-fields (:out-fields fn-def)
           malloc-fn (.getMember @wasm/p "_malloc")
           free-fn (.getMember @wasm/p "_free")
           ccall-fn (.getMember @wasm/p "ccall")
           get-value-fn (.getMember @wasm/p "getValue")
           utf8-to-string-fn (.getMember @wasm/p "UTF8ToString")
           allocs (mapv (fn [field-spec]
                          (let [size (out-field-alloc-size field-spec args fn-def ptr-size)]
                            (.asInt (.execute malloc-fn (object-array [size])))))
                        out-fields)
           input-argtypes (vec (remove out-param-arg? (:argtypes fn-def)))
           graal-args (mapv (fn [[_arg-spec arg-type] arg-val]
                              (cond
                                (= arg-type :string) (or arg-val "")
                                (nil? arg-val) 0
                                (and (= arg-type :pointer) (is-context? arg-val))
                                (wasm/address-as-int (context-ptr arg-val))
                                (= arg-type :pointer) (wasm/address-as-int arg-val)
                                :else arg-val))
                            input-argtypes args)
           input-idx (atom 0)
           alloc-idx (atom 0)
           full-graal-args (mapv (fn [argtype]
                                   (if (out-param-arg? argtype)
                                     (let [i @alloc-idx]
                                       (swap! alloc-idx inc)
                                       (nth allocs i))
                                     (let [i @input-idx]
                                       (swap! input-idx inc)
                                       (nth graal-args i))))
                                 (:argtypes fn-def))
           arg-types (mapv (fn [[_ t]] (name (wasm/argtype->ccall-type t)))
                           (:argtypes fn-def))
           result (.asInt
                   (.execute ccall-fn
                             (into-array Object
                                         [(string/replace (name fn-key) "-" "_")
                                          "number"
                                          (org.graalvm.polyglot.proxy.ProxyArray/fromArray
                                           (object-array arg-types))
                                          (org.graalvm.polyglot.proxy.ProxyArray/fromArray
                                           (object-array full-graal-args))])))]
       (let [result-map
             (when (not= result 0)
               (persistent!
                (reduce-kv
                 (fn [m i field-spec]
                   (let [[field-name field-type] field-spec
                         ptr (nth allocs i)]
                     (assoc! m field-name
                             (case field-type
                               :double (.asDouble (.execute get-value-fn (object-array [ptr "double"])))
                               :int (.asInt (.execute get-value-fn (object-array [ptr "i32"])))
                               :string (let [str-ptr (.asInt (.execute get-value-fn (object-array [ptr "*"])))]
                                         (when (not= str-ptr 0)
                                           (.asString (.execute utf8-to-string-fn (object-array [str-ptr])))))
                               :double-array (let [n (/ (out-field-alloc-size field-spec args fn-def ptr-size) 8)]
                                               (mapv (fn [j]
                                                       (.asDouble (.execute get-value-fn
                                                                            (object-array [(+ ptr (* j 8)) "double"]))))
                                                     (range n)))))))
                 (transient {})
                 out-fields)))]
         (doseq [ptr allocs]
           (.execute free-fn (object-array [ptr])))
         result-map))))

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

(defn- ^:async dispatch-struct-list
  "Dispatch for :struct-list return type."
  [fn-key fn-def opts key-casing]
  (let [args (extract-args fn-def opts)]
    #?(:clj (case @implementation
              :ffi (dispatch-struct-list-ffi fn-key fn-def args)
              :graal (dispatch-struct-list-graal fn-key fn-def args))
       :cljs (convert-js-result-keys
              (js-await (dispatch-to-platform-with-args fn-key fn-def args))
              key-casing))))

(defn- ^:async dispatch-out-params
  "Dispatch for :out-params return type."
  [fn-key fn-def opts key-casing]
  (let [input-fn-def (assoc fn-def :argtypes (vec (remove out-param-arg? (:argtypes fn-def))))
        args (extract-args input-fn-def opts)]
    #?(:clj (case @implementation
              :ffi (dispatch-out-params-ffi fn-key fn-def args)
              :graal (dispatch-out-params-graal fn-key fn-def args))
       :cljs (convert-js-result-keys
              (js-await (dispatch-to-platform-with-args fn-key fn-def args))
              key-casing))))

(defn- ^:async dispatch-default
  "Dispatch for functions without special return types. Handles context dispatch
   vs plain dispatch, return value tracking, and context attachment."
  [fn-key fn-def opts ctx-for-result]
  (let [result (if (should-use-context-dispatch? fn-key fn-def opts)
                 (let [context-atom (get-context-atom opts fn-def)
                       remaining-args (get-remaining-args opts fn-def)]
                   #?(:clj (dispatch-context-fn fn-key fn-def context-atom remaining-args)
                      :cljs (js-await (dispatch-context-fn fn-key fn-def context-atom remaining-args))))
                 (let [args (extract-args fn-def opts)]
                   #?(:clj (dispatch-to-platform-with-args fn-key fn-def args)
                      :cljs (js-await (dispatch-to-platform-with-args fn-key fn-def args)))))
        result (process-return-value-with-tracking result fn-def)]
    (if ctx-for-result (attach-context-to-result result ctx-for-result) result)))

(defn ^:async dispatch-proj-fn
  "Central dispatcher for all PROJ functions"
  [fn-key fn-def opts & [key-casing]]
  (ensure-initialized!)
  (let [opts (if (needs-auto-context? fn-key fn-def opts)
               (let [ctx (or (context-from-pj-args fn-def opts)
                             #?(:clj (context-create {})
                                :cljs (js-await (context-create {}))))]
                 #?(:clj (assoc opts :context ctx)
                    :cljs (if (object? opts)
                            (do (aset opts "context" ctx) opts)
                            (assoc opts :context ctx))))
               opts)
        opts #?(:clj opts
                :cljs (js-await (reconcile-cross-worker-args! fn-def opts)))
        proj-returns (:proj-returns fn-def)]
    (case proj-returns
      :struct-list (dispatch-struct-list fn-key fn-def opts key-casing)
      :out-params (dispatch-out-params fn-key fn-def opts key-casing)
      (let [ctx-for-result (when (= :pj proj-returns)
                             (let [fa (first-arg-kw fn-def)]
                               #?(:clj (resolve-context-val opts fa)
                                  :cljs (if (object? opts)
                                          (or (aget opts (name fa))
                                              (when (#{:ctx :context} fa)
                                                (aget opts (if (= fa :ctx) "context" "ctx"))))
                                          (resolve-context-val opts fa)))))]
        (dispatch-default fn-key fn-def opts ctx-for-result)))))

#?(:clj
   ;; Generate all PROJ functions at runtime for ClojureScript
   (do
     (doseq [[fn-key fn-def] pdefs/fndefs]
       (let [fn-name (symbol (string/replace (name fn-key) "_" "-"))
             fn-ns (ns-name *ns*)]
         ;; Create the function and intern it in the current namespace
         (intern fn-ns fn-name
                 (fn proj-fn
                   ([] (proj-fn {}))
                   ([opts] (dispatch-proj-fn fn-key fn-def opts)))))))
   :cljs
   (define-all-proj-public-fns nil))

;; camelCase JS aliases for manually-defined functions
;; (fndefs functions get camelCase aliases via define-all-proj-public-fns macro)
#?(:cljs (def init init!))
#?(:cljs (def shutdown shutdown!))
#?(:cljs (def setCoords set-coords!))
#?(:cljs (def getCoords get-coords))
#?(:cljs (def getWorkerMode get-worker-mode))
#?(:cljs (def getWorkerCount get-worker-count))
#?(:cljs (def contextCreate context-create))
#?(:cljs (def contextPtr context-ptr))
#?(:cljs (def contextDatabasePath context-database-path))
#?(:cljs (def contextSetDatabasePath context-set-database-path))
#?(:cljs (def contextSetEnableNetwork context-set-enable-network))
#?(:cljs (def isContext is-context?))
#?(:cljs (def coordArray coord-array))
#?(:cljs (def coordTensor coord-tensor))
#?(:cljs (def coordToCoordArray coord->coord-array))
#?(:cljs (def allocCoordArray alloc-coord-array))
#?(:cljs (def setCoordArray set-coord-array))
#?(:cljs (def getCoordArray get-coord-array))

