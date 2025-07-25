#?(:clj
   (ns net.willcohen.proj.wasm
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

(defonce p (atom nil))
(defonce init-promise (atom nil))

#?(:clj
   (defn- read-resource-bytes [path]
     (with-open [in (io/input-stream (io/resource path))]
       (when-not in (throw (ex-info (str "Could not find resource on classpath: " path) {:path path})))
       (.readAllBytes in))))

(defn init-proj
  "Initialize PROJ - unified for both GraalVM and ClojureScript"
  []
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
               _ (tsgcd (let [source (.build (.mimeType (Source/newBuilder "js" proj-js-url) "application/javascript+module"))]
                          (log/info "Pre-loading PROJ.js module to assist module resolution:" (str proj-js-url))
                          (.eval context source)))

                ;; Load the main index module
               index-js-module (tsgcd (let [source (.build (.mimeType (Source/newBuilder "js" index-js-url) "application/javascript+module"))]
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
               opts (ProxyObject/fromMap
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
               _ (tsgcd (.execute init-fn (into-array Object [opts])))]

            ;; Wait for initialization to complete
           (log/info "Waiting for PROJ.js initialization to complete via callback...")
           (.get init-future)
           (log/info "PROJ.js initialization complete. System is ready."))))

     :cljs
      ;; ClojureScript initialization - resources are now embedded in WASM
     (if (nil? @init-promise)
       (let [promise (js/Promise.
                      (fn [resolve reject]
                        (let [initialize-fn (.-initialize proj-loader)]
                          (initialize-fn
                           #js {:onSuccess (fn [proj-module]
                                             (reset! p proj-module)
                                             (js/console.log "PROJ initialized in ClojureScript with embedded resources")
                                             (resolve proj-module))
                                :onError (fn [error]
                                           (js/console.error "PROJ init failed:" error)
                                           (reject error))
                                 ;; No locateFile needed - resources are embedded in WASM
                                }))))]
         (reset! init-promise promise)
         promise)
        ;; Already have init promise, return it
       @init-promise)))

(defn- ensure-proj-initialized! []
  (if (nil? @p)
    (init-proj)))

(defn valid-ccall-type?
  "Checks if a keyword represents a valid ccall type."
  [t]
  (#{:number :array :string} t))

(declare arg->js-literal arg-array->js-array-string keyword->js-string keyword-array->js-string)

(defn proj-emscripten-helper
  [f return-type arg-types args]
  (ensure-proj-initialized!)
  #?(:clj
      ;; GraalVM implementation with exception handling
     (let [p-instance @p
           ccall-fn (.getMember p-instance "ccall")]
       (when *runtime-log-level* (log/log *runtime-log-level* (str "Graal ccall: " f " " return-type " " arg-types " " args)))
       (try
         (tsgcd
          (.execute ccall-fn (into-array Object [f
                                                 (name return-type)
                                                 (ProxyArray/fromArray (object-array (map name arg-types)))
                                                 (ProxyArray/fromArray (object-array args))])))
         (catch Exception e
           ;; Handle C++ exceptions from WASM - these should return nil for pointer types
           (when *runtime-log-level* (log/log *runtime-log-level* (str "Graal ccall exception for " f ": " (.getMessage e))))
           (case return-type
             :pointer nil ; Return nil for pointer functions that fail
             :string nil ; Return nil for string functions that fail  
             :int32 0 ; Return 0 for int functions that fail
             :float64 0.0 ; Return 0.0 for float functions that fail
             :size-t 0 ; Return 0 for size-t functions that fail
             :void nil ; Return nil for void functions
             nil)))) ; Default case
     :cljs
      ;; ClojureScript implementation
     (let [p-instance @p
           ccall-fn (.-ccall p-instance)]
       (when *runtime-log-level* (js/console.log "CLJS ccall:" f return-type arg-types args))
       (ccall-fn f (name return-type) (clj->js (map name arg-types)) (clj->js args)))))

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
  [num-coords dims]
  #?(:clj
     (tsgcd (let [alloc (malloc (* 8 num-coords))
                  array (heapf64 (/ (address-as-int alloc) 8) (* dims num-coords))]
              {:malloc alloc :array array}))
     :cljs
     (let [alloc (malloc (* 8 num-coords))
           array (heapf64 (/ alloc 8) (* dims num-coords))]
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

(defn def-wasm-fn-runtime
  "Runtime implementation of WASM function call"
  [fn-key fn-def args]
  (let [c-fn-name (name fn-key)
        rettype (:rettype fn-def)
        argtypes (:argtypes fn-def)

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
                  (proj-emscripten-helper c-fn-name
                                          ccall-return-type
                                          ccall-arg-types
                                          args))]
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
       result)))

;; Generate all WASM function wrappers  
#?(:clj
   (macros/define-all-wasm-fns pdefs/fndefs c-name->clj-name)
   :cljs
   ;; For ClojureScript, we don't need to generate functions here
   ;; The dispatch system in proj.cljc calls def-wasm-fn-runtime directly
   nil)
