(ns #?(:clj net.willcohen.proj.proj :cljs proj)
  "The primary clj/cljs API for the JVM/JS wrapper of PROJ."
  #?(:clj
     (:require [net.willcohen.proj.impl.native :as native]
               [tech.v3.resource :as resource]
               [tech.v3.datatype :as dt]
               [tech.v3.datatype.ffi :as dt-ffi]
               [tech.v3.datatype.ffi.ptr-value :as dt-ptr]
               [tech.v3.datatype.native-buffer :as dt-nb]
               [tech.v3.tensor :as dt-t]
               [clojure.java.io :as io]
               [clojure.tools.logging :as log]
               [clojure.string :as string]
               ;[net.willcohen.proj.clj.impl.struct :as struct]
               [tech.v3.datatype.struct :as dt-struct]
               [tech.v3.datatype.array-buffer :as dt-ab]
               [net.willcohen.proj.impl.graal :as graal])
     :cljs
     (:require ["proj-emscripten$default" :as proj-emscripten]
               ["resource-tracker" :as resource]
               [cljs.string :as string]
               ["fs" :as fs]))
  #?(:clj (:import [tech.v3.datatype.ffi Pointer]
                   [java.io File])))


(def ^{:tag 'long} PJ_CATEGORY_ELLIPSOID 0)
(def ^{:tag 'long} PJ_CATEGORY_PRIME_MERIDIAN 1)
(def ^{:tag 'long} PJ_CATEGORY_DATUM 2)
(def ^{:tag 'long} PJ_CATEGORY_CRS 3)
(def ^{:tag 'long} PJ_CATEGORY_COORDINATE_OPERATION 4)
(def ^{:tag 'long} PJ_CATEGORY_DATUM_ENSEMBLE 5)

(def ^{:tag 'long} PJ_FWD 1)
(def ^{:tag 'long} PJ_IDENT 0)
(def ^{:tag 'long} PJ_INV -1)

(def ^{:tag 'long} PJ_TYPE_UNKNOWN 0)
(def ^{:tag 'long} PJ_TYPE_ELLIPSOID 1)
(def ^{:tag 'long} PJ_TYPE_PRIME_MERIDIAN 2)
(def ^{:tag 'long} PJ_TYPE_GEODETIC_REFERENCE_FRAME 3)
(def ^{:tag 'long} PJ_TYPE_DYNAMIC_GEODETIC_REFERENCE_FRAME 4)

(declare get-implementation)

(def p #?(:clj nil
          :cljs (atom nil)))

#?(:cljs
   (defn proj-emscripten-helper
     [f return-type arg-types args]
     ;; Asserts to be commented out when not debugging
     ;; (assert (valid-ccall-type? return-type))
     ;; (map #(assert (valid-ccall-type? %)) arg-types)
     ((.-ccall @p) f return-type arg-types args 0)))

(defn cs
  "The primary mechanism for attempting to ensure atomicity with contexts.

  Each context is wrapped in a clojure atom, and calls to native PROJ functions
  requiring a context have their context passed through swap! via this function,
  which mutates the atom on every use before returning the result. This way,
  native calls require control of the context before they can return.

  This does mean, however, that if multiple threads are using and modifying a
  single context, that one thread's repeated usage of that context may have
  unpredictable behavior if another thread has modified that context in the
  meantime.

  For this reason, in situations where performance is less important, all
  wrapped functions using contexts here also include an arity generating a new
  context. In situations where more performance is desired in a multithreaded
  scenario, please ensure that a passed context is only used within one thread."
  [context f args]
  (:result
   (swap!
    context (fn [a]
              (let [old a
                    ptr (:ptr old)]
                (assoc old
                       :ptr ptr
                       :op (inc (:op old))
                       :result #?(:clj (case (get-implementation)
                                         :ffi (apply f (cons ptr args))
                                         :graal (graal/tsgcd (apply f (cons ptr args))))
                                  :cljs (case (get-implementation)
                                         :node (let [return-type (first args)
                                                     arg-types (nth args 1)
                                                     args (nth args 2)]
                                                 (proj-emscripten-helper f return-type
                                                                         (into-array (cons "number" arg-types))
                                                                         (into-array (cons ptr args))))))))))))

(defn string-list-destroy
  ([addr]
   (string-list-destroy addr nil))
  ([addr log-level]
   #?(:clj (when log-level
             (log/logf log-level
                       "Destroyed string list 0x%16X" addr)))
   #?(:clj (case (get-implementation)
             :ffi (native/proj_string_list_destroy (Pointer. addr))
             :graal (graal/proj-string-list-destroy (graal/address-as-polyglot-value addr)))
      :cljs (case (get-implementation)
              :node (proj-emscripten-helper "proj_string_list_destroy" "number" "number" addr)))))

(defn context-set-database-path
  ([context]
   (context-set-database-path context
                              (case (get-implementation)
                                :ffi (string/join File/separator
                                                  [(:path @native/proj)
                                                   "proj.db"])
                                :graal "/proj.db")))
  ([context db-path]
   (context-set-database-path context db-path
                              (case (get-implementation)
                                :ffi ""
                                :graal "[]")
                              (case (get-implementation)
                                :ffi ""
                                :graal 0)))
  ([context db-path aux-db-paths options]
   (cs context (case (get-implementation)
                 :ffi native/proj_context_set_database_path
                 :graal graal/proj-context-set-database-path)
       [db-path aux-db-paths options])))

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
     (let [alloc (malloc (* 8 num-coords))
           array (heapf64 (/ alloc 8) (* 4 num-coords))]
       {:malloc alloc :array array})))

#?(:cljs
   (defn set-coord-array
     [coord-array allocated]
     (let [flattened (into-array (flatten coord-array))]
       (.-set (:array allocated) [flattened 0])
       allocated)))


(defn string-array-pointer->strs
  [ptr]
  #?(:clj (let [ptrs (-> ptr
                       dt-ptr/ptr-value
                       com.sun.jna.Pointer.
                       (.getPointerArray 0))]
            (map #(.getString % 0) ptrs))
     :cljs (loop [addr ptr
                  arr []
                  str (pointer->string ptr)]
            (if (empty? str)
              arr
              (recur (+ 4 addr)
                     (conj arr str)
                     (pointer->string (+ 4 addr)))))))

(defn context-destroy
  ([addr]
   (context-destroy addr nil))
  ([addr log-level]
   #?(:clj (do
             (when log-level
               (log/logf log-level
                         "Destroyed context 0x%16X" addr))
             (log/info "Destroying" addr)
             (log/info (type addr))))
   #?(:clj (case (get-implementation)
             :ffi (native/proj_context_destroy (Pointer. addr))
             :graal (graal/proj-context-destroy addr))

      :cljs (case (get-implementation)
              :node ((.-_proj_context_destroy @p) addr)))))

(defn proj-destroy
  ([addr]
   (proj-destroy addr nil))
  ([addr log-level]
   #?(:clj (when log-level
             (log/logf log-level
                       "Destroyed proj 0x%16X" addr)))
   #?(:clj (case (get-implementation)
             :ffi (native/proj_destroy (Pointer. addr))
             :graal (graal/proj-destroy addr))
      :cljs (case (get-implementation)
              :node ((.-_proj_destroy @p) addr)))))

(defn context-create
  ([]
   (context-create nil))
  ([log-level]
   (context-create log-level #?(:clj :auto
                                :cljs "auto")))
  ([log-level resource-type]
   (let [retval #?(:clj (case (get-implementation)
                          :ffi (native/proj_context_create)
                          :graal (graal/proj-context-create))
                   :cljs (case (get-implementation)
                           :node ((.-_proj_context_create @p))))
         addr (case (get-implementation)
                :ffi (.address retval)
                :graal (graal/address-as-string retval)
                :node (str retval))
         t (when resource-type
            (resource/track
             retval
             #?(:clj {:dispose-fn #(context-destroy addr log-level)
                      :track-type resource-type}
                :cljs (js-obj "disposefn" #(context-destroy addr log-level)
                              "tracktype" resource-type))))
         a (atom {:ptr retval :op (long 0) :result nil})]
     (case (get-implementation)
       :ffi (context-set-database-path a)
       :graal nil
       :node nil)
     a)))

(def force-graal (atom false))
(defn toggle-graal! [] (swap! force-graal not))
(defn force-graal! [] (reset! force-graal true))

(defn proj-init
  []
  #?(:clj
     (if @force-graal (graal/init-proj)
      (try (native/init-proj)
       (catch Exception _
         (graal/init-proj))))
     :cljs
     (reset! p (proj-emscripten))))

(defn get-implementation
  []
  #?(:clj (cond @force-graal :graal
                (not (nil? (:singleton @native/proj))) :ffi
                :else :graal)
     :cljs :node))

(defn ffi?
  []
  (= :ffi (get-implementation)))

(defn graal?
  []
  (= :graal (get-implementation)))

(defn node?
  []
  (= :node (get-implementation)))

#?(:cljs
   (defmacro ef
     "Emscripten function helper, not working"
     ; TODO Fix this to simplify usage in this NS
     [f]
     `(~(symbol (str ".-" (name f))) (deref p))))

(defn valid-ccall-type?
  [t]
  (if (not (nil? (#{:number :array :string} t)))
    true
    false))

(defn proj-reset
  []
  ;; need to destroy first?
  (cond (ffi?) (native/reset-proj)
        (graal?) (do
                   (graal/reset-graal-context!)
                   (graal/init-proj))
        (node?) (proj-init)))

(defn get-authorities-from-database
  ([]
   (get-authorities-from-database (context-create)))
  ([context]
   (get-authorities-from-database context nil))
  ([context log-level]
   (get-authorities-from-database context log-level #?(:clj :auto
                                                       :cljs "auto")))
  ([context log-level resource-type]
   (let [retval (cs context #?(:clj (case (get-implementation)
                                      :ffi native/proj_get_authorities_from_database
                                      :graal graal/proj-get-authorities-from-database)
                               :cljs (case (get-implementation)
                                       :node (.-_proj_get_authorities_from_database @p)))
                    nil)
         addr #?(:clj (case (get-implementation)
                        :ffi (.address retval)
                        :graal (graal/address-as-int retval))
                 :clj (case (get-implementation)
                        :node retval))]
     (when log-level
       #?(:clj (log/logf log-level
                         "proj_get_authorities_from_database returned 0x%16x" addr)))
     (when resource-type
       (resource/track retval #?(:clj {:dispose-fn #(string-list-destroy addr log-level)
                                       :track-type resource-type}
                                 :cljs (js-obj "disposefn" #(string-list-destroy addr log-level)
                                               "tracktype" resource-type))))
     #?(:clj (case (get-implementation)
               :ffi (string-array-pointer->strs retval)
               :graal (graal/string-array-pointer->strs retval))
        :cljs (case (get-implementation)
                :node (string-array-pointer->strs retval))))))

(defn get-codes-from-database
  ([auth-name]
   (get-codes-from-database (context-create) auth-name))
  ([context auth-name]
   (get-codes-from-database context auth-name 8))
  ([context auth-name type]
   (get-codes-from-database context auth-name type 1))
  ([context auth-name type allow-deprecated]
   (get-codes-from-database context auth-name type allow-deprecated nil))
  ([context auth-name type allow-deprecated log-level]
   (get-codes-from-database context auth-name type allow-deprecated log-level #?(:clj :auto
                                                                                 :cljs "auto")))
  ([context auth-name type allow-deprecated log-level resource-type]
   (let [retval (cs context (case (get-implementation)
                              :ffi native/proj_get_codes_from_database
                              :graal graal/proj-get-codes-from-database
                              :node "proj_get_codes_from_database")
                    #?(:clj [auth-name type allow-deprecated]
                       :cljs ["number"
                              (array "string" "number" "number")
                              (array auth-name type log-level)]))
          addr (case (get-implementation)
                 :ffi (.address retval)
                 :graal retval
                 :node retval)]
      (when resource-type
        (resource/track retval #?(:clj {:dispose-fn #(string-list-destroy addr log-level)
                                        :track-type resource-type}
                                  :cljs (js-obj "disposefn" #(string-list-destroy addr log-level)
                                                "tracktype" resource-type))))
      ;; Need to diagnose why the string array continues too far
      (filter #(re-matches #"^[A-Za-z0-9\-]+$" %)
              (case (get-implementation)
                :ffi (string-array-pointer->strs retval)
                :graal (graal/string-array-pointer->strs retval)
                :node (string-array-pointer->strs retval))))))

(defn context-guess-wkt-dialect
  ([wkt]
   (context-guess-wkt-dialect (context-create) wkt))
  ([context wkt]
   (let [retval (cs context (case (get-implementation)
                              :ffi native/proj_context_guess_wkt_dialect
                              :graal graal/proj-context-guess-wkt-dialect
                              :node "proj_context_guess_wkt_dialect")
                    #?(:clj [wkt]
                       :cljs ["number"
                              (array "string")
                              (array wkt)]))]

      retval)))

(defn create-crs-to-crs
  ([source-crs target-crs]
   (create-crs-to-crs (context-create) source-crs target-crs))
  ([context source-crs target-crs]
   (create-crs-to-crs context source-crs target-crs 0 nil))
  ([context source-crs target-crs area log-level]
   (create-crs-to-crs context source-crs target-crs area log-level #?(:clj :auto
                                                                      :cljs "auto")))
  ([context source-crs target-crs area log-level resource-type]
   (let [retval (cs context (case (get-implementation)
                              :ffi native/proj_create_crs_to_crs
                              :graal graal/proj-create-crs-to-crs
                              :node "proj_create_crs_to_crs")
                    #?(:clj [source-crs target-crs area]
                       :cljs ["number"
                              (array "string" "string" "number")
                              (array source-crs target-crs area)]))
         addr (case (get-implementation)
                :ffi (.address retval)
                :graal (graal/address-as-int retval)
                :node retval)]
     (when resource-type
       (resource/track retval #?(:clj {:dispose-fn #(proj-destroy addr log-level)
                                       :track-type resource-type}
                                 :cljs (js-obj "disposefn" #(proj-destroy addr log-level)
                                               "tracktype" resource-type))))
     retval)))

(defn create-from-database
  ([auth-name code category]
   (create-from-database (context-create) auth-name code category))
  ([context auth-name code category]
   (create-from-database context auth-name code category 0 0))
  ([context auth-name code category use-proj-alternative-grid-names options]
   (create-from-database context auth-name code category use-proj-alternative-grid-names options nil))
  ([context auth-name code category use-proj-alternative-grid-names options log-level]
   (create-from-database context auth-name code category use-proj-alternative-grid-names options log-level #?(:clj :auto
                                                                                                              :cljs "auto")))
  ([context auth-name code category use-proj-alternative-grid-names options log-level resource-type]
   (let [retval (cs context (case (get-implementation)
                              :ffi native/proj_create_from_database
                              :graal graal/proj-create-from-database
                              :node "proj_create_from_database")
                    [auth-name code category use-proj-alternative-grid-names options])
         addr (case (get-implementation)
                :ffi (.address retval)
                :graal (graal/address-as-int retval)
                :node retval)]
     (when resource-type
       (resource/track retval #?(:clj {:dispose-fn #(proj-destroy addr log-level)
                                       :track-type resource-type}
                                 :cljs (js-obj "disposefn" #(proj-destroy log-level)
                                               "tracktype" resource-type))))
     retval)))

(defn trans-array
  ([p coord-array]
   (trans-array p coord-array PJ_FWD))
  ([p coord-array direction]
   (case (get-implementation)
     :ffi (if (dt-t/tensor? coord-array)
            (trans-array p coord-array direction (count (dt-t/rows coord-array)))
            (let [coord-array (dt-t/ensure-native (dt-t/->tensor coord-array :datatype :float64))]
              (dt-t/->jvm (trans-array p coord-array direction))))
     :graal (trans-array p coord-array direction
                         (graal/tsgcd (/ (.asInt (.getMember (:array coord-array) "length")) 4)))
     :node (trans-array p coord-array direction
                        (/ (.-length (:array coord-array)) 4))))
  ([p coord-array direction n]
   #?(:clj (case (get-implementation)
             :ffi (if (dt-t/tensor? coord-array)
                    (do (native/proj_trans_array p direction n coord-array)
                        coord-array)
                    (trans-array p (dt-t/ensure-native (dt-t/->tensor coord-array :datatype :float64)) direction n))
             :graal (do (graal/proj-trans-array p direction n coord-array)
                        coord-array))
       :cljs (case (get-implementation)
              :node (do (proj-emscripten-helper
                         "proj_trans_array"
                         "number"
                         (array "number" "number" "number" "number")
                         (array p direction n (:malloc coord-array)))
                        coord-array)))))

(defn coord-tensor
  [ca]
  (-> (dt-nb/as-native-buffer ca)
      (dt-nb/set-native-datatype :float64)
      (dt-t/reshape [(count ca) 4])))

(defn coord-array
  ([n]
   #?(:clj (case (get-implementation)
             :ffi (coord-array n :native-heap :auto)
             :graal (graal/alloc-coord-array n))
      :cljs (case (get-implementation)
             :node (alloc-coord-array n))))

  ([n container-type resource-type]
   (-> (dt-struct/new-array-of-structs :proj-coord n {:container-type container-type
                                                      :resource-type resource-type})
       coord-tensor)))

(defn coord->coord-array
  [coord]
  #?(:clj
     (case (get-implementation)
       :ffi (if (dt-t/tensor? coord)
              (let [coord-array (coord-array 1)
                    len (count coord)]
                (reduce #(dt-t/mset! %1 0 %2 (dt-t/mget coord %2)) coord-array (range len)))
              (coord->coord-array (dt-t/->tensor coord)))
       :graal (graal/set-coord-array coord (coord-array 1)))
     :cljs
     (case (get-implementation)
       :node (set-coord-array coord (coord-array 1)))))

(defn trans-coord
  ([proj coord]
   #?(:clj
      (if (or (dt-t/tensor? coord) (= (get-implementation) :graal))
        (trans-coord proj coord PJ_FWD)
        (dt-t/->jvm (trans-coord proj coord PJ_FWD)))
      :cljs
      (trans-coord proj coord PJ_FWD)))
  ([proj coord direction]
   #?(:clj
      (case (get-implementation)
        :ffi (if (dt-t/tensor? coord)
               (dt-t/mget (trans-array proj (coord->coord-array coord) direction 1) 0)
               (dt-t/->jvm (dt-t/select (dt-t/mget (trans-array proj (coord->coord-array coord) direction 1) 0) (range (count coord)))))
        :graal (graal/polyglot-array->jvm-array
                (:array (trans-array proj (coord->coord-array coord) direction))))
      :cljs
      (case (get-implementation)
        :node
        (:array (trans-array proj (coord->coord-array coord) direction))))))

(defn set-coord!
  "Sets the value of a single coordinate in a coord-array tensor.
   The index of the first coord in a tensor is 0.
   If using an array generated with coord-array, the coord provided should be an array of four doubles.
   Otherwise, the coord should have the same shape as a single coordinate in the provided tensor."
  [ca idx coord]
  (dt-t/mset! ca idx coord))

(defn set-coords!
  "Sets the value of the entire coordinate array.
  If the provided coordinates are shaped the same as the coord-array,
  (in other words, any number of collections of up to four doubles)
  they will be set directly.
  Otherwise, this will attempt to first reshape the provided coordinates into
  such a tensor."
  [ca coords]
  (case (get-implementation)
    :ffi (cond (= (dt/shape ca) (dt/shape coords))
               (dt-t/mset! ca coords)
               :else
               (dt-t/mset! ca (dt-t/reshape coords (dt/shape ca))))
    :graal (graal/set-coord-array coords ca)))

(defn coords->coord-array
  [coords]
  (let [l (count (flatten coords))
        ca (coord-array (/ l 4))]
    (set-coords! ca coords)))


(defn trans-coords
  ([proj coords]
   (trans-coords proj coords PJ_FWD))
  ([proj coords direction]
   (case (get-implementation)
     :ffi (trans-array proj (coords->coord-array coords))
     :graal (:array (trans-array proj (coords->coord-array coords))))))


(defn set-col!
  [ca idx vals]
  (-> ca
      (dt-t/transpose [1 0])
      (dt-t/mset! idx vals)
      (dt-t/transpose [1 0])))

(defn set-xcol!
  [ca vals]
  (set-col! ca 0 vals))

(defn set-ycol!
  [ca vals]
  (set-col! ca 1 vals))

(defn set-zcol!
  [ca vals]
  (set-col! ca 2 vals))

(defn set-tcol!
  [ca vals]
  (set-col! ca 3 vals))


;; #?(:cljs (do
;;            (proj-init)
;;            (println (trans-coord (create-crs-to-crs "EPSG:3586" "EPSG:4326") (array 0 0 0 0)))))
