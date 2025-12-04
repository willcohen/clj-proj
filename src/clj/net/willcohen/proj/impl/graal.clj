(ns net.willcohen.proj.impl.graal
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [net.willcohen.proj.impl.fn-defs :as fn-defs-data])

  (:import [org.graalvm.polyglot Context PolyglotAccess Source]
           [org.graalvm.polyglot.proxy ProxyArray ProxyObject ProxyExecutable]
           [java.util.concurrent CompletableFuture]
           [java.nio ByteBuffer]))

(def ^:dynamic *runtime-log-level* nil)

(def ^:dynamic *load-grids*
  "Whether to load PROJ grid files during GraalVM initialization.
  Loading grids is very slow due to ProxyArray conversion overhead.
  Set to false to skip grid loading for faster initialization."
  false)

(def context (-> (Context/newBuilder (into-array String ["js" "wasm"]))
                 (.allowPolyglotAccess PolyglotAccess/ALL)
                 (.option "js.ecmascript-version" "staging")
                 (.option "js.esm-eval-returns-exports" "true")
                 (.allowExperimentalOptions true)
                 (.option "js.webassembly" "true")
                 (.out System/out) ; Ensure JS console.log goes to the right place
                 (.err System/err)
                 #_(.option "js.foreign-object-prototype" "true")
                 (.allowIO true)
                 .build))

(defmacro tsgcd
  "thread-safe graal context do"
  [body]
  `(locking context
     ~body))

(defn eval-js
  ([str]
   (eval-js str "src.js"))
  ([str js-name] ; Simplified to remove redundant nested tsgcd call
   (tsgcd (.eval context (.build (Source/newBuilder "js" str js-name))))))

(defonce p (atom nil))

(defn- read-resource-bytes [path]
  (with-open [in (io/input-stream (io/resource path))]
    (when-not in (throw (ex-info (str "Could not find resource on classpath: " path) {:path path})))
    (.readAllBytes in)))

(defn init-proj
  "Initialize PROJ using direct callbacks to avoid deadlock"
  []
  (locking p
    (when (nil? @p)
      (let [;; Load JS modules from classpath
            proj-js-url (io/resource "wasm/proj.js")
            index-js-url (io/resource "wasm/index.js")
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
            wasm-binary-bytes (read-resource-bytes "wasm/proj.wasm")
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
        (log/info "PROJ.js initialization complete. System is ready.")))))

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
  (let [p-instance @p
        ccall-fn (.getMember p-instance "ccall")]
    (when *runtime-log-level* (log/log *runtime-log-level* (str "Graal ccall: " f " " return-type " " arg-types " " args)))
    (tsgcd
     (.execute ccall-fn (into-array Object [f
                                            (name return-type)
                                            (ProxyArray/fromArray (object-array (map name arg-types)))
                                            (ProxyArray/fromArray (object-array args))])))))

(defprotocol Pointerlike
  (address-as-int [this])
  (address-as-string [this])
  (address-as-polyglot-value [this])
  (address-as-trackable-pointer [this])
  (get-value [this type])
  (pointer->string [this])
  (string-array-pointer->strs [this]))

(defrecord TrackablePointer [address]
  Pointerlike
  (address-as-int [this] (address-as-int (:address this)))
  (address-as-string [this] (address-as-string (:address this)))
  (address-as-polyglot-value [this] (address-as-polyglot-value (:address this)))
  (address-as-trackable-pointer [this] this)
  (get-value [this type] (get-value (:address this) type))
  (pointer->string [this] (pointer->string (:address this)))
  (string-array-pointer->strs [this] (string-array-pointer->strs (:address this))))

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

    :else (pr-str arg))) ; Fallback for other types, pr-str is generally safe

(defn- arg-array->js-array-string [arr]
  (str "[" (string/join ", " (map arg->js-literal arr)) "]"))

(defn- keyword->js-string [k]
  (str "\"" (name k) "\""))

(defn- keyword-array->js-string [arr]
  (str "[" (string/join ", " (map keyword->js-string arr)) "]"))

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
              (log/log *runtime-log-level* (str "Graal: string-array-pointer->strs - Read string: \"" current-str "\"")))
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
  (string-array-pointer->strs [this] (string-array-pointer->strs (address-as-polyglot-value this))))

;;;; GraalVM Utility Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn polyglot-array->jvm-array
  [a]
  (tsgcd
   (do (assert (.hasArrayElements a))
       (let [length (.getArraySize a)]
         (into-array Object (map #(.asDouble (.getArrayElement a %)) (range length)))))))

(defn malloc
  [b]
  (ensure-proj-initialized!)
  (tsgcd (address-as-trackable-pointer (.execute (.getMember @p "_malloc") (into-array Object [b])))))

(defn heapf64
  [o n]
  (ensure-proj-initialized!)
  (let [offset o]
    (tsgcd (.execute (.getMember (.getMember @p "HEAPF64") "subarray")
                     (into-array Object [offset (+ offset n)])))))

(defn fs-open
  [path flags _]
  (ensure-proj-initialized!)
  (tsgcd (.execute (.getMember (.getMember @p "FS") "open")
                   (into-array Object [path flags nil]))))

(defn fs-write
  [stream buffer offset length position _]
  (ensure-proj-initialized!)
  (tsgcd (.execute (.getMember (.getMember @p "FS") "write")
                   (into-array Object [stream buffer offset length position _]))))

(defn fs-close
  [stream]
  (ensure-proj-initialized!)
  (tsgcd (.execute (.getMember (.getMember @p "FS") "close")
                   (into-array Object [stream]))))

(defn alloc-coord-array
  [num-coords dims]
  (tsgcd (let [alloc (malloc (* 8 num-coords))
               array (heapf64 (/ (address-as-int alloc) 8) (* dims num-coords))]
           {:malloc alloc :array array})))

(defn set-coord-array
  [coord-array allocated]
  (ensure-proj-initialized!)
  (tsgcd (do (let [flattened (flatten coord-array) js-array (eval-js "new Array();")]
               (ensure-proj-initialized!)
               (doall (map #(tsgcd (.setArrayElement js-array % (nth flattened %))) (range (count flattened))))
               (tsgcd (.execute (.getMember (:array allocated) "set")
                                (into-array Object [js-array 0]))))
             allocated)))

(defn new-coord-array
  [coord-array num-coords]
  (set-coord-array coord-array (alloc-coord-array num-coords 4)))

(defn allocate-string-on-heap
  "Allocates a string on the Emscripten heap and returns a pointer."
  [s]
  (when s
    (let [len (+ 1 (alength (.getBytes s "UTF-8")))
          addr (malloc len)]
      (tsgcd (.execute (.getMember @p "stringToUTF8") (into-array Object [s (address-as-polyglot-value addr) len])))
      addr)))

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
         array-of-pointers-addr)))))

(defn free-on-heap
  "Frees a pointer on the Emscripten heap."
  [ptr]
  (ensure-proj-initialized!)
  (when ptr
    (tsgcd (.execute (.getMember @p "_free") (into-array Object [(address-as-polyglot-value ptr)])))))

(defmacro with-allocated-string
  "Executes body with a string allocated on the Emscripten heap.
  Binds the pointer to sym and ensures it's freed afterwards."
  [[sym s] & body]
  `(let [s# ~s
         ~sym (allocate-string-on-heap s#)]
     (try
       ~@body
       (finally
         (free-on-heap ~sym)))))

(defn- c-name->clj-name [c-fn-keyword]
  (-> (name c-fn-keyword)
      (string/replace #"_" "-")
      (symbol)))

(defmacro def-graal-fn
  "Defines a GraalVM wrapper function for a PROJ C API call.
   - fn-name: The Clojure name for the generated function.
   - fn-key: The keyword in fn-defs-data/fn-defs for the C function."
  [fn-name fn-key]
  (let [fn-def (get fn-defs-data/fn-defs fn-key)
        _ (when-not fn-def
            (throw (ex-info (str "No fn-def found for key: " fn-key) {:fn-key fn-key})))
        c-fn-name (name fn-key)
        rettype (:rettype fn-def)
        argtypes (:argtypes fn-def)
        arg-symbols (mapv (comp symbol first) argtypes) ; Symbols for defn arglist

        ;; Determine ccall return type for proj-emscripten-helper
        ccall-return-type (case rettype
                            :pointer :number
                            :string :string
                            :void :number
                            :int32 :number
                            :float64 :number
                            :size-t :number
                            :number) ;; Default

        ;; Determine ccall argument types for proj-emscripten-helper
        ccall-arg-types (mapv (fn [[_c-arg-name c-arg-type]]
                                (case c-arg-type
                                  (:pointer :pointer? :string-array :string-array?) :number
                                  :string :string
                                  :int32 :number
                                  :float64 :number
                                  :size-t :number
                                  :number)) ;; Default
                              argtypes)

        ;; Generate the actual arguments to be passed to proj-emscripten-helper
        ;; These will be the original arg-symbols.
        actual-ccall-args (mapv (fn [[_c-arg-name c-arg-type] arg-symbol] arg-symbol) ; Arguments are already transformed by proj.cljc
                                argtypes arg-symbols)

        ;; The core ccall expression
        ;; Emscripten's 'string' type handles allocation/deallocation automatically.
        wrapped-helper-call `(tsgcd (proj-emscripten-helper ~c-fn-name
                                                            ~ccall-return-type
                                                            '~ccall-arg-types
                                                            [~@actual-ccall-args]))
        ;; Wrap result if the C function returns a pointer
        final-body (if (= rettype :pointer)
                     `(address-as-trackable-pointer ~wrapped-helper-call)
                     wrapped-helper-call)]
    `(defn ~fn-name [~@arg-symbols] ~final-body)))

(defmacro define-all-graal-fns []
  (let [defs (for [[c-fn-key _] fn-defs-data/fn-defs
                   :let [clj-fn-name (c-name->clj-name c-fn-key)]]
               `(def-graal-fn ~clj-fn-name ~c-fn-key))]
    `(do ~@defs)))

(define-all-graal-fns)
