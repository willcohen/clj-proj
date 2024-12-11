(ns net.willcohen.proj.impl.graal
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log])

  (:import [org.graalvm.polyglot Context PolyglotAccess Source]
           [org.graalvm.polyglot.io ByteSequence]
           [org.graalvm.polyglot.proxy ProxyArray]
           [com.oracle.truffle.api.memory ByteArraySupport]))

(def context (-> (Context/newBuilder (into-array String ["js" "wasm"]))
                (.allowPolyglotAccess PolyglotAccess/ALL)
                (.allowExperimentalOptions true)
                (.option "js.webassembly" "true")
                (.option "js.foreign-object-prototype" "true")
                (.allowIO true)
                .build))

(defn reset-graal-context!
  []
  (def context (-> (Context/newBuilder (into-array String ["js" "wasm"]))
                (.allowPolyglotAccess PolyglotAccess/ALL)
                (.allowExperimentalOptions true)
                (.option "js.webassembly" "true")
                (.option "js.foreign-object-prototype" "true")
                (.allowIO true)
                .build)))

(defmacro tsgcd
  [body]
  `(locking context
     ~body))

(defn eval-js
  ([str]
   (eval-js str "src.js"))
  ([str js-name]
   (tsgcd (.eval (tsgcd context) (.build (Source/newBuilder "js" str js-name))))))


(defn init-proj
  []
  (reset-graal-context!)
  (def p (let [f (java.io.File. "resources/wasm/pw.wasm")
               w (byte-array (.length f))
               _ (with-open [in (java.io.DataInputStream. (clojure.java.io/input-stream f))]
                   (.readFully in w))]
          (.putMember (.getBindings context "js") "pwproxy" (ProxyArray/fromArray (object-array (seq w))))
          (eval-js (slurp "resources/wasm/proj_emscripten.graal.js"))
          (let [f  (java.io.File. "resources/proj.db")
                d (byte-array (.length f))]
            (with-open [in (java.io.DataInputStream. (clojure.java.io/input-stream f))]
              (.readFully in d))
            (.putMember (.getBindings context "js") "db" (ProxyArray/fromArray (object-array (seq d))))
            (.putMember (.getBindings context "js") "dbini" (slurp "resources/proj.ini"))
            (eval-js "var p = proj_emscripten.default.proj();")
            ;(reset! p (eval-js "p;"))
            (eval-js "p['FS'].writeFile('proj.ini', dbini);
            var f = p['FS'].open('proj.db', 'w+');
            p['FS'].write(f, db, 0, db.length, 0);
            p['FS'].close(f);
            p")))))

(defn sp []
  (if (nil? p)
    (do
      (init-proj)
     (tsgcd p))))

(defn emscripten-helper
  [f]
  (tsgcd (do
          (sp)
          (let [p-local (tsgcd p)]
           (tsgcd (.getMember p-local f))))))
()
(defn valid-ccall-type?
  [t]
  (if (not (nil? (#{:number :array :string} t)))
    true
    false))

(defn keyword->quoted-string
  [k]
  (string/join ["'" (name k) "'"]))

(defn arg->quoted-string
  [a]
  (string/join ["'" a "'"]))

(defn arg-array->string
  [a]
  (let [args (map arg->quoted-string a)
        args-list (string/join ", " args)]
    (string/join ["[" args-list "]"])))

(defn keyword-array->string
  [a]
  (let [keywords (map keyword->quoted-string a)
        keyword-list (string/join ", " keywords)]
    (string/join ["[" keyword-list "]"])))


(defn proj-emscripten-helper
  [f return-type arg-types args]
  (assert (valid-ccall-type? return-type))
  (map #(assert (valid-ccall-type? %)) arg-types)
  (log/info f)
  (log/info return-type)
  (log/info arg-types)
  (log/info args)
  (let [eval-str (string/join
                  ["p.ccall(" (tsgcd (arg->quoted-string f)) ", " (keyword->quoted-string return-type) ", "
                   (keyword-array->string arg-types) ", " (tsgcd (arg-array->string args)) ", 0);"])]
    (log/info eval-str)
   (tsgcd (eval-js eval-str))))

(defprotocol Pointerlike
  (address-as-int [this])
  (address-as-string [this])
  (address-as-polyglot-value [this])
  (address-as-trackable-pointer [this])
  (get-value [this type])
  (pointer->string [this])
  (pointer-array->string-array [this]))

(defrecord TrackablePointer [address]
  Pointerlike
  (address-as-int [this] (address-as-int (:address this)))
  (address-as-string [this] (address-as-string (:address this)))
  (address-as-polyglot-value [this] (address-as-polyglot-value (:address this)))
  (address-as-trackable-pointer [this] this)
  (get-value [this type] (get-value (:address this) type))
  (pointer->string [this] (pointer->string (:address this)))
  (pointer-array->string-array [this] (pointer-array->string-array (:address this))))

(extend-protocol Pointerlike
  org.graalvm.polyglot.Value
  (address-as-int [this] (.asInt this))
  (address-as-string [this] (.asString this))
  (address-as-polyglot-value [this] this)
  (address-as-trackable-pointer [this] (->TrackablePointer (address-as-int this)))
  (get-value [this type] (.execute (emscripten-helper "getValue")
                                (into-array Object [this type])))
  (pointer->string [this] (.asString (.execute (emscripten-helper "UTF8ToString")
                                               (into-array Object [(get-value this "*")]))))
  (pointer-array->string-array [this]
    (loop [addr this
           arr []
           str (pointer->string this)]
      (if (empty? str)
        arr
        (recur (+ 4 (address-as-int addr))
               (conj arr str)
               (pointer->string (+ 4 (address-as-int addr)))))))

  java.lang.String
  (address-as-int [this] (Integer/parseInt this))
  (address-as-string [this] this)
  (address-as-polyglot-value [this] (tsgcd (.asValue context this)))
  (address-as-trackable-pointer [this] (->TrackablePointer (address-as-int this)))
  (get-value [this type] (get-value (address-as-polyglot-value this) type))
  (pointer->string [this] (pointer->string (address-as-polyglot-value this)))
  (pointer-array->string-array [this] (pointer-array->string-array (address-as-polyglot-value this)))

  java.lang.Long
  (address-as-int [this] (int this))
  (address-as-string [this] (str this))
  (address-as-polyglot-value [this] (tsgcd (.asValue context this)))
  (address-as-trackable-pointer [this] (->TrackablePointer (address-as-int this)))
  (get-value [this type] (get-value (address-as-polyglot-value this) type))
  (pointer->string [this] (pointer->string (address-as-polyglot-value this)))
  (pointer-array->string-array [this] (pointer-array->string-array (address-as-polyglot-value this)))

  java.lang.Integer
  (address-as-int [this] this)
  (address-as-string [this] (str this))
  (address-as-polyglot-value [this] (tsgcd (.asValue context this)))
  (address-as-trackable-pointer [this] (->TrackablePointer this))
  (get-value [this type] (get-value (address-as-polyglot-value this) type))
  (pointer->string [this] (pointer->string (address-as-polyglot-value this)))
  (pointer-array->string-array [this] (pointer-array->string-array (address-as-polyglot-value this))))

(defn polyglot-array->jvm-array
  [a]
  (tsgcd
    (do (assert (.hasArrayElements a))
        (let [length (.getArraySize a)]
         (into-array Object (map #(.asDouble (.getArrayElement a %)) (range length)))))))


(defn malloc
  [b]
  (tsgcd (address-as-trackable-pointer
          (tsgcd (.execute (emscripten-helper "_malloc")
                          (into-array Object [b]))))))

(defn heapf64
  [o n]
  (let [offset o]
    (tsgcd (.execute (.getMember (emscripten-helper "HEAPF64") "subarray")
              (into-array Object [offset (+ offset n)])))))

(defn fs-open
  [path flags _]
  (tsgcd (.execute (.getMember (emscripten-helper "FS") "open")
            (into-array Object [path flags nil]))))

(defn fs-write
  [stream buffer offset length position _]
  (tsgcd (.execute (.getMember (emscripten-helper "FS") "write")
            (into-array Object [stream buffer offset length position _]))))

(defn fs-close
  [stream]
  (tsgcd (.execute (.getMember (emscripten-helper "FS") "close")
            (into-array Object [stream]))))

(defn alloc-coord-array
  [num-coords]
  (tsgcd (let [alloc (malloc (* 8 num-coords))
               array (heapf64 (/ (address-as-int alloc) 8) (* 4 num-coords))]
          {:malloc alloc :array array})))

(defn set-coord-array
  [coord-array allocated]
  (tsgcd (do (let [flattened (flatten coord-array) js-array (eval-js "new Array();")]
              (sp)
              (doall (map #(tsgcd (.setArrayElement js-array % (nth flattened %))) (range (count flattened))))
              (tsgcd (.execute (.getMember (:array allocated) "set")
                         (into-array Object [js-array 0]))))
             allocated)))

(defn new-coord-array
  [coord-array num-coords]
  (set-coord-array coord-array (alloc-coord-array num-coords)))

(defn proj-context-create
  []
  (address-as-trackable-pointer (proj-emscripten-helper "proj_context_create" :number [] [])))

(defn proj-string-list-destroy
  [addr]
  (proj-emscripten-helper "proj_string_list_destroy" :number [:number] [(address-as-polyglot-value addr)]))

(defn proj-context-destroy
  [addr]
  (tsgcd (proj-emscripten-helper "proj_context_destroy" :number [:number] [(address-as-string addr)])))

(defn proj-context-set-database-path
  [context db-path aux-db-paths options]
  (proj-emscripten-helper "proj_context_set_database_path"
                          :number [:number :string :number :number]
                          [(address-as-polyglot-value context) db-path aux-db-paths options]))

(defn proj-context-get-database-path
  [context]
  (proj-emscripten-helper "proj_context_get_database_path"
                   :number [:number] [(address-as-polyglot-value context)]))

(defn proj-get-authorities-from-database
  [context]
  (address-as-trackable-pointer (proj-emscripten-helper "proj_get_authorities_from_database"
                                                        :number [:number] [(address-as-polyglot-value context)])))

(defn proj-log-level
  [context log-level]
  (proj-emscripten-helper "proj_log_level" :number [:number :number] [(address-as-polyglot-value context) log-level]))


(defn proj-create-crs-to-crs
  [context source target area]
  (tsgcd (address-as-trackable-pointer (proj-emscripten-helper "proj_create_crs_to_crs" :number [:context :string :string :number] [(address-as-polyglot-value context) source target area]))))

(defn proj-trans-array
  [p direction n coords]
  (tsgcd (proj-emscripten-helper "proj_trans_array"
                                :number [:number :number :number :number]
                                [(address-as-polyglot-value p) direction n (address-as-polyglot-value (:malloc coords))])))

(defn proj-destroy
  [addr]
  (proj-emscripten-helper "proj_destroy" :number [:number] [(address-as-polyglot-value addr)]))

(defn proj-get-codes-from-database
  [context auth-name type allow-deprecated]
  (address-as-trackable-pointer (proj-emscripten-helper "proj_get_codes_from_database" :number
                                 [:number :string :number :number]
                                 [(address-as-polyglot-value context) auth-name type allow-deprecated])))

(defn proj-context-guess-wkt-dialect
  [context wkt]
  (proj-emscripten-helper "proj_context_guess_wkt_dialect" :number
                          [:number :string]
                          [(address-as-polyglot-value context) wkt]))

(defn proj-create-from-database
  [context auth-name code category use-proj-alternative-grid-names options]
  (proj-emscripten-helper "proj_create_from_database" :number
                          [:number :string :number :number :number :number]
                          [(address-as-polyglot-value context) auth-name code category use-proj-alternative-grid-names options]))
