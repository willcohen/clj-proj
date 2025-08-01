#?(:clj
   (ns net.willcohen.proj.proj
     "The primary clj API for the JVM wrapper of PROJ."
     (:require [net.willcohen.proj.impl.native :as native]
               [tech.v3.resource :as resource]
               [tech.v3.datatype :as dt]
               [tech.v3.datatype.ffi :as dt-ffi]
               [tech.v3.datatype.ffi.ptr-value :as dt-ptr]
               [tech.v3.datatype.native-buffer :as dt-nb]
               [tech.v3.tensor :as dt-t]
               [clojure.tools.logging :as log]
               [clojure.string :as string]
               [tech.v3.datatype.struct :as dt-struct]
               [net.willcohen.proj.wasm :as wasm]
               [net.willcohen.proj.fndefs :as pdefs]
               [net.willcohen.proj.macros :refer [define-all-proj-public-fns tsgcd]])
     (:import [tech.v3.datatype.ffi Pointer]
              [java.io File]
              [com.sun.jna StringArray]))
   :cljs
   (ns net.willcohen.proj.proj
     (:require [wasm :as wasm]
               [fndefs :as pdefs]
               ["resource-tracker" :as resource])
     (:require-macros [macros :refer [define-all-proj-public-fns tsgcd]])))

(def implementation (atom nil))
(def force-graal (atom false))
(defn toggle-graal! [] (swap! force-graal not))
(defn force-graal! [] (reset! force-graal true) (reset! implementation nil))
(defn force-ffi! [] (reset! force-graal false) (reset! implementation nil))

(defn ffi? [] (= :ffi @implementation))
(defn graal? [] (= :graal @implementation))
(defn node? [] (= :node @implementation))

(def p #?(:clj nil
          :cljs (atom nil)))

;; TODO: Make as many of the helpers private as possible.
;; TODO: Begin adding docstrings to all functions to help clarify how ns works.

;; TODO: Do we still use these?
#?(:cljs
   (defn proj-emscripten-helper
     [f return-type arg-types args]
     ((.-ccall @p) f return-type arg-types args 0)))

(defn valid-ccall-type?
  [t]
  (if (not (nil? (#{:number :array :string} t)))
    true
    false))

#?(:cljs
   (defn get-value
     [ptr type]
     ((.-getValue @p) ptr type)))

#?(:cljs
   (defn pointer->string
     [ptr]
     ((.-UTF8ToString @p) (get-value ptr "*"))))

#?(:cljs
   (defn malloc
     [b]
     ((.-_malloc @p) b)))

#?(:cljs
   (defn heapf64
     [o n]
     (.subarray (.-HEAPF64 @p) o (+ o n))))

#?(:cljs
   (defn alloc-coord-array
     [num-coords]
     ;; Each coordinate has 4 doubles (x, y, z, t), each double is 8 bytes
     (let [floats-needed (* 4 num-coords)
           bytes-needed (* 8 floats-needed)
           alloc (malloc bytes-needed)
           array (heapf64 (/ alloc 8) floats-needed)]
       #js {:malloc alloc :array array})))

#?(:cljs
   (defn set-coord-array
     [coord-array allocated]
     (let [;; Handle nested arrays - could be CLJS vectors, JS arrays, or mixed
           flattened (cond
                       ;; If it's already a flat JS array
                       (and (array? coord-array)
                            (every? number? coord-array))
                       coord-array

                       ;; If it's a JS array of JS arrays
                       (and (array? coord-array)
                            (array? (aget coord-array 0)))
                       (let [result #js []]
                         (dotimes [i (.-length coord-array)]
                           (let [inner (aget coord-array i)]
                             (dotimes [j (.-length inner)]
                               (.push result (aget inner j)))))
                         result)

                       ;; If it's a Clojure structure
                       :else
                       (into-array (flatten coord-array)))
           ;; Handle both Clojure maps and JS objects
           array (cond
                   ;; JS object
                   (and (object? allocated) (.-array allocated))
                   (.-array allocated)
                   ;; Clojure map with get method
                   (and allocated (.-get allocated))
                   (.get allocated "array")
                   ;; Regular Clojure map
                   :else
                   (:array allocated))]
       (if array
         (do
           (.set array flattened 0)
           allocated)
         (throw (js/Error. "No :array key in allocated structure"))))))

#?(:cljs
   (defn get-coord-array
     "Read coordinates from a coord array"
     [allocated idx]
     (let [;; Handle both Clojure maps and JS objects
           array (cond
                   ;; JS object
                   (and (object? allocated) (.-array allocated))
                   (.-array allocated)
                   ;; Clojure map with get method
                   (and allocated (.-get allocated))
                   (.get allocated "array")
                   ;; Regular Clojure map
                   :else
                   (:array allocated))
           offset (* idx 4)]
       (when array
         [(aget array offset)
          (aget array (+ offset 1))
          (aget array (+ offset 2))
          (aget array (+ offset 3))]))))

(defn init!
  "Initialize PROJ. In ClojureScript, returns a Promise that must be awaited.
   In Clojure, initializes synchronously and returns nil."
  ([]
   (init! nil))
  ([log-level]
   #?(:clj
      (try
        (when log-level (println (str "Attempting to initialize PROJ library...")))
        (if @force-graal
          (do (when log-level (println "Forcing GraalVM implementation."))
              (wasm/init-proj))
          (try
            (when log-level (println "Attempting FFI implementation."))
            (native/init-proj)
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
                      (not (nil? (:singleton @native/proj))) :ffi
                      :else :graal))
        (when log-level (println (str "PROJ library initialized with " (name @implementation) " implementation.")))
        nil) ;; Return nil for Clojure
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
          (let [init-promise (wasm/init-proj)]
            ;; Chain a handler to set implementation when init completes
            (.then init-promise
                   (fn [proj-module]
                     (reset! p proj-module)
                     (reset! implementation runtime)
                     (when log-level
                       (js/console.log (str "PROJ initialized with " runtime " implementation")))
                     proj-module))
            ;; Return the promise so callers can await it
            init-promise))))))

;; Convenience alias without the bang for JavaScript
(def init init!)

 ;; TODO: move generate-docstring back!! example in proj_macros.
 ;; Maybe dispatch can use it? Mainly we need it for clj-kondo,
;; so if that one has to have dependencies in macros.clj, I'm
;; reluctantly open to it.

(defn set-coords!
  "Sets the value of the entire coordinate array.
  If the provided coordinates are shaped the same as the coord-array,
  (in other words, any number of collections of up to four doubles)
  they will be set directly.
  Otherwise, this will attempt to first reshape the provided coordinates into
  such a tensor."
  [ca coords]
  #?(:clj
     (case @implementation
       :ffi (cond (= (dt/shape ca) (dt/shape coords))
                  (dt-t/mset! ca coords)
                  :else
                  (dt-t/mset! ca (dt-t/reshape coords (dt/shape ca))))
       :graal (wasm/set-coord-array coords ca))
     :cljs (set-coord-array coords ca)))

 ;; to-emscripten-type moved to proj_macros.cljc 

;;TODO: Should this just be integrated into the dispatch?
(defn cs
  "The primary mechanism for attempting to ensure atomicity with contexts."
  [context f args]
  (:result
   (swap!
    context (fn [a]
              (let [old a
                    ptr (:ptr old)]
                (when-not ptr (throw (ex-info (str "Pointer in context is nil for fn " f) {:f f :context-val a})))
                (assoc old
                       :ptr ptr
                       :op (inc (:op old))
                       :result #?(:clj (case @implementation
                                         :ffi (try
                                                (let [result (apply f (cons ptr args))]
                                                  result)
                                                (catch Exception e
                                                  (throw e)))
                                         :graal (tsgcd (apply f (cons ptr args))))
                                  :cljs (case @implementation
                                          (:node :browser)
                                          (let [proj-fn (aget @p (str "_" (.-name f)))]
                                            (if proj-fn
                                              (apply proj-fn (cons ptr args))
                                              (throw (js/Error. (str "PROJ function not found: " (.-name f))))))
                                          (throw (js/Error. (str "Unsupported implementation: " @implementation)))))))))))

;;TODO: fn is too big. Need to simplify and/or extract into helper fns.
(defn string-array-pointer->strs
  "Converts a pointer to a NULL-terminated array of string pointers into a Clojure vector of strings."
  [ptr runtime-log-level]
  #?(:clj
     (case @implementation
       :ffi
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
             result-vec)))
        ;; For Graal, `ptr` is a TrackablePointer. `wasm/string-array-pointer->strs` expects an integer address.
       :graal (wasm/string-array-pointer->strs (wasm/address-as-int ptr)))
     :cljs
     (case @implementation
       (:node :browser)
       (if (and @p ptr (not (zero? ptr)))
         (try
           (let [result (loop [result []
                               idx 0]
                          (let [str-ptr-addr (+ ptr (* idx 4))
                                str-ptr (get-value str-ptr-addr "*")]
                            (if (or (nil? str-ptr) (zero? str-ptr))
                              result
                              (let [str-val ((.-UTF8ToString @p) str-ptr)]
                                (recur (conj result str-val) (inc idx))))))]
             ;; Convert Clojure vector to JavaScript array for JS-native feel
             (to-array result))
           (catch js/Error e
             (throw e)))
         ;; Return empty JavaScript array for null/zero pointers
         (to-array []))
       (throw (js/Error. (str "Unsupported implementation: " @implementation))))))

;; TODO: How much of this do we really need?
(declare proj-context-errno proj-string-list-destroy
         proj-destroy proj-list-destroy
         proj-celestial-body-list-destroy
         proj-get-crs-list-parameters-destroy
         proj-crs-info-list-destroy
         proj-unit-list-destroy
         proj-insert-object-session-destroy
         proj-operation-factory-context-destroy
         proj-context-destroy
         proj-errno proj-errno-string
         context-create context-set-database-path
         proj-context-create proj-context-set-database-path)

;; TODO: Do we need this?
(def ^:private pj-return-types-set
  #{:pj :pj-list :pj-celestial-body-info-list :pj-crs-list-parameters
    :pj-crs-info-list :pj-unit-info-list :pj-insert-session
    :pj-operation-factory-context})

(def proj-type->destroy-fn
  "Mapping of PROJ return types to their corresponding destroy functions."
  {:pj "proj_destroy"
   :pj-list "proj_list_destroy"
   :string-list "proj_string_list_destroy"
   :pj-context "proj_context_destroy"
   :pj-celestial-body-info-list "proj_celestial_body_list_destroy"
   :pj-crs-list-parameters "proj_get_crs_list_parameters_destroy"
   :pj-crs-info-list "proj_crs_info_list_destroy"
   :pj-unit-info-list "proj_unit_list_destroy"
   :pj-insert-session "proj_insert_object_session_destroy"
   :pj-operation-factory-context "proj_operation_factory_context_destroy"})

 ;; Macro invocation moved to end of file to avoid forward references

;; TODO: I want this integrated into the dispatch itself.
(defn context-create
  "Creates a new PROJ context wrapped in a Clojure atom."
  ([]
   (context-create nil))
  ([_log-level-ignored]
   (context-create _log-level-ignored #?(:clj :auto :cljs "auto")))
  ([_log-level-ignored _resource-type-ignored]
   (let [tracked-native-ctx #?(:clj (proj-context-create {})
                               :cljs (do
                                       (ensure-initialized!)
                                       ;; Call through the dispatch system
                                       (dispatch-proj-fn :proj_context_create
                                                         (get pdefs/fndefs :proj_context_create)
                                                         {})))
         a (atom {:ptr tracked-native-ctx :op (long 0) :result nil})]
     #?(:clj
        (when (ffi?) (context-set-database-path a))
        :cljs
         ;; No need to set database path in CLJS - resources are embedded in WASM
        nil)
     a)))

;; TODO: too big. need to simplify and/or extract to helper fns.
(defn context-set-database-path
  "High-level wrapper for setting the database path."
  ([context]
   (context-set-database-path context
                              #?(:clj
                                 (case @implementation
                                   :ffi (string/join File/separator
                                                     [(:path @native/proj)
                                                      "proj.db"])
                                   :graal "/proj.db"
                                   "/proj.db")
                                 :cljs nil)))
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
      (let [ctx-ptr (:ptr @context)
            result (proj-context-set-database-path {:context ctx-ptr
                                                    :db-path db-path
                                                    :aux-db-paths aux-db-paths
                                                    :options options})]
        result))))

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
      :cljs (do
              (when (nil? @implementation)
                (init!))
              (case @implementation
                :node (alloc-coord-array n)
                :browser (alloc-coord-array n)))))
  #?(:clj
     ([n dims container-type resource-type]
      (-> (dt-struct/new-array-of-structs :proj-coord n {:container-type container-type
                                                         :resource-type resource-type})
          (coord-tensor dims)))))

(defn coord->coord-array
  [coord]
  #?(:clj
     (case @implementation
       :ffi (if (dt-t/tensor? coord)
              (let [coord-array (coord-array 1)
                    len (count coord)]
                (reduce #(dt-t/mset! %1 0 %2 (dt-t/mget coord %2)) coord-array (range len)))
              (coord->coord-array (dt-t/->tensor coord)))
       :graal (wasm/set-coord-array coord (coord-array 1)))
     :cljs
     (case @implementation
       :node (set-coord-array coord (coord-array 1)))))

(defn set-coord!
  "Sets the value of a single coordinate in a coord-array tensor.
   The index of the first coord in a tensor is 0.
   If using an array generated with coord-array, the coord provided should be an array of four doubles.
   Otherwise, the coord should have the same shape as a single coordinate in the provided tensor."
  [ca idx coord]
  (dt-t/mset! ca idx coord))

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

;; Generate all PROJ public functions
 ;; Runtime dispatch system

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
  "Dispatch to FFI implementation"
  [fn-key args]
  (let [native-fn (ns-resolve 'net.willcohen.proj.impl.native (symbol (name fn-key)))]
    (if native-fn
      (apply native-fn args)
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
     (def-proj-fn-runtime fn-key fn-def args)))

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
     ;; CLJS requires explicit initialization
     (when (nil? @implementation)
       (throw (js/Error. "proj/init! must be called and awaited before using proj functions")))))

;; TODO: This fn is too long, need to extract it into helpers.
;; Consider integrating cs into this functionality.
(defn extract-args
  "Extract arguments from opts map based on function definition, applying defaults.
   Supports both underscore and hyphenated parameter names for better usability."
  [argtypes opts]
  (mapv (fn [arg-spec]
          (let [[arg-name arg-type & rest-spec] arg-spec
                arg-map (when (seq rest-spec) (apply hash-map rest-spec))
                default-val (get arg-map :default)
                ;; Try both underscore and hyphenated versions of the parameter name
                arg-kw-underscore (keyword arg-name)
                ;; Use JavaScript String.replace for ClojureScript, clojure.string/replace for Clojure  
                arg-kw-hyphenated (keyword
                                   #?(:clj (clojure.string/replace (name arg-name) #"_" "-")
                                      :cljs (-> (name arg-name)
                                                (.replace (js/RegExp. "_" "g") "-"))))
                ;; In CLJS, opts might be a JS object - use aget for JS objects
                provided-val #?(:clj (or (get opts arg-kw-underscore)
                                         (get opts arg-kw-hyphenated))
                                :cljs (if (object? opts)
                                        (or (aget opts (name arg-name))
                                            (aget opts (-> (name arg-name)
                                                           (.replace (js/RegExp. "_" "g") "-"))))
                                        (or (get opts arg-kw-underscore)
                                            (get opts arg-kw-hyphenated))))]
            ;; Special handling for context arguments
            (cond
              ;; If context is required but not provided, create a temporary one
              (and (= arg-name 'context)
                   (nil? provided-val)
                   (not (contains? arg-map :default)))
              (let [temp-ctx (context-create {})]
                #?(:clj (if (graal?)
                          temp-ctx
                          (:ptr @temp-ctx))
                   :cljs temp-ctx))

              ;; Use default value when provided
              (and (nil? provided-val) (contains? arg-map :default))
              (if (symbol? default-val)
                (if-let [resolved (ns-resolve 'net.willcohen.proj.fndefs default-val)]
                  @resolved
                  default-val)
                default-val)

              ;; Otherwise use provided value
              :else provided-val)))
        argtypes))

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
      :string-list (string-array-pointer->strs result nil)
      ;; Track all PROJ pointer types
      #?(:cljs
         (if-let [destroy-fn-name (proj-type->destroy-fn proj-returns)]
           (when result
             (.track resource result
                     #js {:disposefn (fn []
                                       (when @p
                                         (proj-emscripten-helper destroy-fn-name :void [:pointer] [result])))
                          :tracktype "auto"})
             result)
           ;; No tracking needed for this type
           result)
         :clj
         (if-let [destroy-fn-name (proj-type->destroy-fn proj-returns)]
           (when result
             ;; Use tech.v3.resource for automatic cleanup
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

(defn should-use-context-dispatch?
  "Determine if a function should use context dispatch via cs"
  [fn-key fn-def opts]
  (let [is-context-fn (is-c-context-fn? fn-key fn-def)
        first-arg-name (when (seq (:argtypes fn-def))
                         (keyword (first (first (:argtypes fn-def)))))
        first-arg-val (get opts first-arg-name)]
    (and is-context-fn
         #?(:clj (instance? clojure.lang.Atom first-arg-val)
            :cljs (and first-arg-val (.-state first-arg-val))))))

(defn get-context-atom
  "Extract the context atom from opts for context functions"
  [opts fn-def]
  (let [first-arg-name (when (seq (:argtypes fn-def))
                         (keyword (first (first (:argtypes fn-def)))))]
    (get opts first-arg-name)))

(defn get-remaining-args
  "Extract args for context functions (skipping the first context arg)"
  [opts fn-def]
  (let [first-arg-name (when (seq (:argtypes fn-def))
                         (keyword (first (first (:argtypes fn-def)))))]
    (extract-args (rest (:argtypes fn-def))
                  (dissoc opts first-arg-name))))

(defn dispatch-proj-fn
  "Central dispatcher for all PROJ functions"
  [fn-key fn-def opts]
  (ensure-initialized!)
  (if (should-use-context-dispatch? fn-key fn-def opts)
    ;; Context function with atom - use cs for atomicity
    (let [context-atom (get-context-atom opts fn-def)
          remaining-args (get-remaining-args opts fn-def)
          result (dispatch-context-fn fn-key fn-def context-atom remaining-args)]
      (process-return-value-with-tracking result fn-def))
    ;; Regular function or context is not an atom
    (let [args (extract-args (:argtypes fn-def) opts)
          result (dispatch-to-platform-with-args fn-key fn-def args)]
      (process-return-value-with-tracking result fn-def))))

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

;; Runtime helper functions for macros

#?(:cljs
   (defn c-name->clj-name [c-fn-keyword]
     (symbol (.replace (name c-fn-keyword) "_" "-"))))

#?(:cljs
   (defn to-emscripten-type [kw-type]
     (case kw-type
       :pointer :number
       :double :number
       :int :number
       :double* :array
       :size_t :number
       :string :string
       :string-array :array
       :proj-string-list :array
       :void nil
       :number)))

#?(:cljs
   (defn def-proj-fn-runtime
     "Runtime implementation of PROJ function call - dispatches to wasm implementation"
     [fn-key fn-def args]
  ;; Call wasm/def-wasm-fn-runtime directly
     (wasm/def-wasm-fn-runtime fn-key fn-def args)))

;; TODO: Do we need these? Why can't we just refer to them as needed? Are they just vestigial?
#?(:cljs
   (defn allocate-string-on-heap [s]
     (if (aget js/wasm "allocate-string-on-heap")
       ((aget js/wasm "allocate-string-on-heap") s)
       ;; Fallback implementation using malloc
       (let [bytes (.encode (js/TextEncoder.) s)
             ptr (malloc (+ (.-length bytes) 1))]
         (.set (js/Uint8Array. (.-buffer (aget @p "HEAPU8")) ptr (+ (.-length bytes) 1)) bytes)
         ptr))))

#?(:cljs
   (defn free-on-heap [ptr]
     (if (aget js/wasm "free-on-heap")
       ((aget js/wasm "free-on-heap") ptr)
       ;; Direct call to _free
       (when (and @p ptr)
         ((aget @p "_free") ptr)))))
