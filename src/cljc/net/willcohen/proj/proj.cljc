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
               [clojure.tools.logging :as log]
               [clojure.string :as string]
               ;[net.willcohen.proj.clj.impl.struct :as struct]
               [tech.v3.datatype.struct :as dt-struct]
               [tech.v3.datatype.array-buffer :as dt-ab]
               [net.willcohen.proj.impl.graal :as graal])
     :cljs
     (:require ["proj-emscripten$default" :as proj-emscripten]
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

;; (defn ts-helper
;;   "thread-safe-helper"
;;   [body]
;;   (case (get-implementation)
;;     :ffi body
;;     :graal (graal/tsgcd body)))

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
                                  :cljs (apply f (cons ptr args)))))))))

(defn string-list-destroy
  ([addr]
   (string-list-destroy addr nil))
  ([addr log-level]
   (when log-level
     (log/logf log-level
               "Destroyed string list 0x%16X" addr))
   (case (get-implementation)
     :ffi (native/proj_string_list_destroy (Pointer. addr))
     :graal (graal/proj-string-list-destroy (graal/address-as-polyglot-value addr)))))

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

(defn string-array-pointer->strs
  [ptr]
  (let [ptrs (-> ptr
               dt-ptr/ptr-value
               com.sun.jna.Pointer.
               (.getPointerArray 0))]
    (map #(.getString % 0) ptrs)))

(defn context-destroy
  ([addr]
   (context-destroy addr nil))
  ([addr log-level]
   (when log-level
     (log/logf log-level
               "Destroyed context 0x%16X" addr))
   (log/info "Destroying" addr)
   (log/info (type addr))
   (case (get-implementation)
     :ffi (native/proj_context_destroy (Pointer. addr))
     :graal (graal/proj-context-destroy addr))))

(defn proj-destroy
  ([addr]
   (proj-destroy addr nil))
  ([addr log-level]
   (when log-level
     (log/logf log-level
               "Destroyed proj 0x%16X" addr))
   (case (get-implementation)
     :ffi (native/proj_destroy (Pointer. addr))
     :graal (graal/proj-destroy addr))))

(defn context-create
  ([]
   (context-create nil))
  ([log-level]
   (context-create log-level :auto))
  ([log-level resource-type]
   (let [retval #?(:clj (case (get-implementation)
                          :ffi (native/proj_context_create)
                          :graal (graal/proj-context-create))
                   :cljs 1)
         addr (case (get-implementation)
                :ffi (.address retval)
                :graal (graal/address-as-string retval))
         t (when resource-type
             (resource/track retval {:dispose-fn #(context-destroy addr log-level)
                                     :track-type resource-type}))
         a (atom {:ptr retval :op (long 0) :result nil})]
     (case (get-implementation)
       :ffi (context-set-database-path a)
       :graal nil)
     (println a)
     a)))

(def p nil)
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
     (def p (proj-emscripten/proj))))

(defn get-implementation
  []
  (cond @force-graal :graal
        (not (nil? (:singleton @native/proj))) :ffi
        :else :graal))

(defn ffi?
  []
  (= :ffi (get-implementation)))

(defn graal?
  []
  (= :graal (get-implementation)))

(defn proj-reset
  []
  ;; need to destroy first?
  (cond (ffi?) (native/reset-proj)
        (graal?) (do
                   (graal/reset-graal-context!)
                   (graal/init-proj))))

(defn get-authorities-from-database
  ([]
   (get-authorities-from-database (context-create)))
  ([context]
   (get-authorities-from-database context nil))
  ([context log-level]
   (get-authorities-from-database context log-level :auto))
  ([context log-level resource-type]
   (let [retval (cs context (case (get-implementation)
                              :ffi native/proj_get_authorities_from_database
                              :graal graal/proj-get-authorities-from-database) nil)
         addr (case (get-implementation)
                :ffi (.address retval)
                :graal (graal/address-as-int retval))]
     (when log-level
       (log/logf log-level
                 "proj_get_authorities_from_database returned 0x%16x" addr))
     (when resource-type
       (resource/track retval {:dispose-fn #(string-list-destroy addr log-level)
                               :track-type resource-type}))
     (case (get-implementation)
       :ffi (string-array-pointer->strs retval)
       :graal (graal/pointer-array->string-array retval)))))

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
   (get-codes-from-database context auth-name type allow-deprecated log-level :auto))
  ([context auth-name type allow-deprecated log-level resource-type]
   (let [retval (cs context (case (get-implementation)
                              :ffi native/proj_get_codes_from_database
                              :graal graal/proj-get-codes-from-database)
                    [auth-name type allow-deprecated])
          addr (case (get-implementation)
                 :ffi (.address retval)
                 :graal retval)]
      (when resource-type
        (resource/track retval {:dispose-fn #(string-list-destroy addr log-level)
                                :track-type resource-type}))
      ;; Need to diagnose why the string array continues too far
      (filter #(re-matches #"^[A-Za-z0-9\-]+$" %)
              (case (get-implementation)
                :ffi (string-array-pointer->strs retval)
                :graal (graal/pointer-array->string-array retval))))))

(defn context-guess-wkt-dialect
  ([wkt]
   (context-guess-wkt-dialect (context-create) wkt))
  ([context wkt]
   (let [retval (cs context (case (get-implementation)
                              :ffi native/proj_context_guess_wkt_dialect
                              :graal graal/proj-context-guess-wkt-dialect)
                    [wkt])]
      retval)))

(defn create-crs-to-crs
  ([source-crs target-crs]
   (create-crs-to-crs (context-create) source-crs target-crs))
  ([context source-crs target-crs]
   (create-crs-to-crs context source-crs target-crs 0 nil))
  ([context source-crs target-crs area log-level]
   (create-crs-to-crs context source-crs target-crs area log-level :auto))
  ([context source-crs target-crs area log-level resource-type]
   (let [retval (cs context (case (get-implementation)
                              :ffi native/proj_create_crs_to_crs
                              :graal graal/proj-create-crs-to-crs)
                    [source-crs target-crs area])
         addr (case (get-implementation)
                :ffi (.address retval)
                :graal (graal/address-as-int retval))]
     (when resource-type
       (resource/track retval {:dispose-fn #(proj-destroy addr log-level)
                               :track-type resource-type}))
     retval)))

(defn create-from-database
  ([auth-name code category]
   (create-from-database (context-create) auth-name code category))
  ([context auth-name code category]
   (create-from-database context auth-name code category 0 0))
  ([context auth-name code category use-proj-alternative-grid-names options]
   (create-from-database context auth-name code category use-proj-alternative-grid-names options nil))
  ([context auth-name code category use-proj-alternative-grid-names options log-level]
   (create-from-database context auth-name code category use-proj-alternative-grid-names options log-level :auto))
  ([context auth-name code category use-proj-alternative-grid-names options log-level resource-type]
   (let [retval (cs context (case (get-implementation)
                              :ffi native/proj_create_from_database
                              :graal graal/proj-create-from-database)
                    [auth-name code category use-proj-alternative-grid-names options])
         addr (case (get-implementation)
                :ffi (.address retval)
                :graal (graal/address-as-int retval))]
     (when resource-type
       (resource/track retval {:dispose-fn #(proj-destroy addr log-level)
                               :track-type resource-type}))
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
                         (graal/tsgcd (/ (.asInt (.getMember (:array coord-array) "length")) 4)))))
  ([p coord-array direction n]
   (case (get-implementation)
     :ffi (if (dt-t/tensor? coord-array)
            (do (native/proj_trans_array p direction n coord-array)
                coord-array)
            (trans-array p (dt-t/ensure-native (dt-t/->tensor coord-array :datatype :float64)) direction n))
     :graal (do (graal/proj-trans-array p direction n coord-array)
                coord-array))))

(defn coord-tensor
  [ca]
  (-> (dt-nb/as-native-buffer ca)
      (dt-nb/set-native-datatype :float64)
      (dt-t/reshape [(count ca) 4])))

(defn coord-array
  ([n]
   (case (get-implementation)
     :ffi (coord-array n :native-heap :auto)
     :graal (graal/alloc-coord-array n)))
  ([n container-type resource-type]
   (-> (dt-struct/new-array-of-structs :proj-coord n {:container-type container-type
                                                      :resource-type resource-type})
       coord-tensor)))

(defn coord->coord-array
  [coord]
  (case (get-implementation)
    :ffi (if (dt-t/tensor? coord)
           (let [coord-array (coord-array 1)
                 len (count coord)]
             (reduce #(dt-t/mset! %1 0 %2 (dt-t/mget coord %2)) coord-array (range len)))
           (coord->coord-array (dt-t/->tensor coord)))
    :graal (graal/set-coord-array coord (coord-array 1))))

(defn trans-coord
  ([proj coord]
   (if (or (dt-t/tensor? coord) (= (get-implementation) :graal))
     (trans-coord proj coord PJ_FWD)
     (dt-t/->jvm (trans-coord proj coord PJ_FWD))))
  ([proj coord direction]
   (case (get-implementation)
     :ffi (if (dt-t/tensor? coord)
            (dt-t/mget (trans-array proj (coord->coord-array coord) direction 1) 0)
            (dt-t/->jvm (dt-t/select (dt-t/mget (trans-array proj (coord->coord-array coord) direction 1) 0) (range (count coord)))))
     :graal (graal/polyglot-array->jvm-array
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


#?(:cljs (do
           (proj-init)
           p))
