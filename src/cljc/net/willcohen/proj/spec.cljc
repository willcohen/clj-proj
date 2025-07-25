(ns net.willcohen.proj.spec
  #?(:clj (:require [clojure.spec.alpha :as s]
                    [clojure.spec.gen.alpha :as gen]) ; gen might be useful for other specs here
     :cljs (:require [cljs.spec.alpha :as s]
                     [cljs.spec.gen.alpha :as gen]))
  #?(:clj (:import [tech.v3.datatype.ffi Pointer]
                   [org.graalvm.polyglot Value])))

(println "Defining ::proj-object spec")

;; --- General Purpose Specs ---

(s/def ::epsg-code-string ; Renamed from ::crs-code-string for clarity, matches test ns original
  (s/and string? #(re-matches #"\d{4,5}" %)))

(s/def ::auth-name
  ;; This set can be extended if more authorities are supported/found.
  #{"EPSG" "ESRI" "IAU_2015" "IGNF" "NKG" "NRCAN" "OGC" "PROJ"})

;; --- Specs for PROJ Context and Common Arguments ---

;; Spec for the context object created by proj/context-create.
;; It's an atom holding a map, minimally expected to contain a :ptr key.
(s/def ::proj-context
  (s/and #?(:clj #(instance? clojure.lang.Atom %)
            :cljs #(instance? cljs.core.Atom %))
         #(map? (deref %))
         #(contains? (deref %) :ptr)))

;; Spec for the optional log-level argument.
(s/def ::log-level
  (s/nilable #?(:clj keyword? :cljs any?))) ; In CLJS, could be string or nil

;; Spec for the optional resource-type argument.
(s/def ::resource-type
  (s/nilable (s/or :keyword keyword? :string string?))) ; e.g., :auto or "auto"

;; --- Specs for Specific Function Return Values ---

(s/def ::authorities-result-set (s/coll-of ::auth-name :kind set? :min-count 0))

;; Spec for a PROJ object pointer/handle returned by functions like create-crs-to-crs.
;; The representation varies by implementation.
(s/def ::proj-object
  #?(:clj
     (letfn [(ffi-pointer? [x] (instance? Pointer x))
             (graal-value? [x] (instance? Value x))]
       (s/or :ffi-pointer ffi-pointer?
             :graal-value graal-value?))
     :cljs number?)) ; Emscripten typically returns numeric pointers

;; Spec for the return value of proj_normalize_for_visualization
;; This function also returns a PROJ object pointer/handle.
(s/def ::normalized-proj-object ::proj-object)

;; Spec for the return value of proj_get_source_crs and proj_get_target_crs
;; These functions also return PROJ object pointers/handles.
(s/def ::crs-object ::proj-object)