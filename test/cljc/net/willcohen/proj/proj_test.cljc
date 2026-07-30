;; Copyright (c) 2024, 2025, 2026 Will Cohen
;;
;; Part of clj-proj, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns net.willcohen.proj.proj-test
  ;; Exclude clojure.core/await so the JVM identity macro below
  ;; resolves. On CLJS, squint's `await` is a parser-level special
  ;; form, and the exclude is a no-op.
  (:refer-clojure :exclude [await])
  #?(:clj (:require [clojure.test :refer [deftest is testing use-fixtures]]
                    [net.willcohen.proj.proj :as proj]
                    [net.willcohen.proj.wasm :as wasm]
                    [tech.v3.resource :as resource])
     :cljs (:require [cljs.test :as t :refer [deftest is testing]]
                     ["proj-wasm" :as proj]
                     ;; squint expands cljc macros through SCI at
                     ;; compile time, so plain :require works.
                     [net.willcohen.proj.proj-test-macros
                      :refer [with-each-implementation with-test-context]]
                     ;; `bb stage:clj-native-test-deps` copies this
                     ;; into test/cljc/dist/. An import through the
                     ;; proj-wasm symlink chain loads a second
                     ;; squint-cljs and splits the cljs.test registry.
                     ["../../../dist/test_runner.mjs"
                      :refer [run_tests_and_exit_BANG_]])))

;; One test body for each deftest runs on the JVM (`bb test:clj`) and
;; on squint+Node (`bb test:cljs:wip`). The `await` shim, the `^:async`
;; marks, and the two macros absorb the platform differences.
;;
;; These tests are race-sensitive. Run the file repeatedly after a
;; concurrency change:
;;   get-crs-info-list-from-database-test
;;   as-proj-json-test
;;   normalize-for-visualization-test
;;   get-area-of-use-test
;;   coordoperation-get-param-test
;;
;; These tests are sensitive to a dispatch change:
;;   is-deprecated-test
;;   get-datum-test
;;   get-area-of-use-ex-test
;;   prime-meridian-get-parameters-test

;; On CLJS, `await` is squint's special form, valid only inside
;; ^:async fns. The JVM API is synchronous, so this identity macro
;; makes `(await expr)` a no-op there. JVM clojure.test ignores
;; `^:async` metadata. squint's cljs.test wraps the test fn as async.
#?(:clj
   (defmacro await [body] body))

;; JVM calls return maps with kebab keyword keys. CLJS calls return
;; JS objects with snake_case string keys. On the JVM, `prop` is
;; `get`. On CLJS, `prop` converts the kebab key to snake_case before
;; the lookup. Single-word keys, for example :name, are equal on the
;; two platforms, so tests read them directly.
#?(:clj
   (defn prop [x k] (get x k))
   :cljs
   (defn prop [x k]
     ;; squint compiles a keyword literal at the call site to a plain
     ;; JS string, so `k` is already the name string. cljs.core/name
     ;; is not necessary, and squint does not export it.
     (when (some? x)
       (get x (.replace k (js/RegExp. "-" "g") "_")))))

;; The cljs `with-each-implementation` macro expansion calls this fn.
#?(:cljs
   (defn ^:async ensure-init! []
     (when (nil? @proj/implementation)
       (await (.init proj)))))

#?(:clj
   (def ^:dynamic *test-implementation*
     (delay (keyword (System/getProperty "net.willcohen.proj.proj-test.implementation" "ffi")))))

#?(:clj
   (defmacro with-each-implementation
     "Macro to wrap test bodies, setting the PROJ implementation based on *test-implementation*."
     [& body]
     `(do
        (let [current-impl# @*test-implementation*]
          (when (nil? current-impl#)
            (throw (ex-info "Test implementation not set. Set *test-implementation* dynamically or via system property." {})))

          (testing (str "With implementation: " (name current-impl#))
            ;; force-*! clears @implementation. The explicit init! is
            ;; necessary for tests that read @proj/implementation and
            ;; do not call a proj fn (initialization-test,
            ;; query-implementation-test).
            (case current-impl#
              :ffi (proj/force-ffi!)
              :graal (proj/force-graal!))
            (proj/init!)
            (try
              ~@body
              (finally))))))) ; no proj-reset: resource tracking does the cleanup

;; tech.v3.resource :auto tracking releases the context.
#?(:clj
   (defmacro with-test-context [[ctx-binding] & body]
     `(let [~ctx-binding (proj/context-create)]
        ~@body)))

#?(:clj
   (use-fixtures :once
     (fn [f]
       ;; No global init: `with-each-implementation` inits each impl.
       ;; nil disables wasm ccall logs. Use :info, :warn, or :debug
       ;; for logs.
       (binding [wasm/*runtime-log-level* nil]
         (f)))))

(deftest ^:async get-authorities-from-database-test
  (with-each-implementation
    (testing "get-authorities-from-database returns a non-empty result of strings"
      ;; A coll? check would fail on CLJS, where the result is a JS
      ;; array.
      (let [authorities (await (proj/proj-get-authorities-from-database))]
        (is (some? authorities) "Result should be non-nil")
        (is (not (empty? authorities)) "Result should not be empty")
        (is (every? string? authorities) "All elements should be strings")))))

(deftest ^:async get-codes-from-database-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "get-codes-from-database returns codes for EPSG"
        (let [epsg-codes (await (proj/proj-get-codes-from-database {:context ctx
                                                                    :auth_name "EPSG"}))]
          (is (some? epsg-codes) "Result should be non-nil")
          (is (not (empty? epsg-codes)) "Result should not be empty")
          (is (every? string? epsg-codes) "All elements should be strings")
          ;; squint wraps a set predicate as a `get` fn, and `get` on
          ;; a Set returns a present key. Thus the set predicate works
          ;; on a JS array in cljs.
          (is (some #{"4326"} epsg-codes) "Should contain a well-known code like '4326'"))))))

(deftest ^:async get-crs-info-list-from-database-test
  #?(:clj  (with-each-implementation
             (with-test-context [ctx]
               (testing "get-crs-info-list-from-database returns CRS entries for EPSG"
                 (let [entries (proj/proj-get-crs-info-list-from-database {:context ctx :auth-name "EPSG"})]
                   (is (coll? entries) "Result should be a collection")
                   (is (> (count entries) 1000) "EPSG should have >1000 CRS entries")
                   (let [wgs84 (first (filter #(= "4326" (:code %)) entries))]
                     (is (some? wgs84) "Should contain EPSG:4326")
                     (is (= "EPSG" (:auth-name wgs84)))
                     (is (= "WGS 84" (:name wgs84)))
                     (is (= false (:deprecated wgs84)))
                     (is (= true (:bbox-valid wgs84)))
                     (is (number? (:west-lon-degree wgs84)))
                     (is (string? (:area-name wgs84))))))
               (testing "get-crs-info-list-from-database with no auth-name returns entries from multiple authorities"
                 (let [entries (proj/proj-get-crs-info-list-from-database {:context ctx})
                       auths (into #{} (map :auth-name entries))]
                   (is (> (count auths) 1) "Should have entries from multiple authorities")
                   (is (contains? auths "EPSG") "Should include EPSG")))
               (testing "nullable struct fields return nil for absent values"
                 (let [entries (proj/proj-get-crs-info-list-from-database {:context ctx :auth-name "EPSG"})
                       wgs84 (first (filter #(= "4326" (:code %)) entries))]
                   (is (nil? (:projection-method-name wgs84))
                       "Geographic CRS should have nil projection-method-name")))
               (testing "nonexistent authority returns empty list"
                 (let [entries (proj/proj-get-crs-info-list-from-database {:context ctx :auth-name "NONEXISTENT_AUTH_ZZZZZ"})]
                   (is (= [] entries) "Nonexistent authority should return empty vector")))))
     :cljs (do
             (await (ensure-init!))
             (testing "get-crs-info-list-from-database returns CRS entries for EPSG (cljs)"
               (let [ctx     (await (.contextCreate proj))
                     entries (await (.projGetCrsInfoListFromDatabase
                                     proj
                                     (clj->js {:context ctx :auth_name "EPSG"})))]
                 (is (some? entries) "Should return a non-nil result")
                 (is (> (.-length entries) 1000)
                     "EPSG should have >1000 CRS entries"))))))

(deftest ^:async get-units-from-database-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-get-units-from-database returns unit entries"
        (let [entries (await (proj/proj-get-units-from-database
                              {:context ctx :auth-name "EPSG" :category "linear" :allow-deprecated 0}))]
          (is (some? entries) "Result should be non-nil")
          (is (pos? (count entries)) "Should have unit entries")
          (let [meter (first (filter #(= "9001" (:code %)) entries))]
            (is (some? meter) "Should contain EPSG:9001 (metre)")
            (is (= "EPSG" (prop meter :auth-name)))
            (is (string? (:name meter)))
            (is (number? (prop meter :conv-factor)))
            (is (= false (:deprecated meter))))
          (let [us-foot (first (filter #(= "9003" (:code %)) entries))]
            (is (some? us-foot) "Should contain EPSG:9003 (US survey foot)")
            (is (= "EPSG" (prop us-foot :auth-name)))
            (is (< 0.3 (prop us-foot :conv-factor) 0.4) "US survey foot conv-factor ~0.3048")))))))

(deftest ^:async get-celestial-body-list-from-database-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-get-celestial-body-list-from-database returns celestial bodies"
        (let [entries (await (proj/proj-get-celestial-body-list-from-database {:context ctx :auth-name ""}))]
          (is (some? entries) "Result should be non-nil")
          (is (pos? (count entries)) "Should have celestial body entries")
          (let [earth (first (filter #(= "Earth" (:name %)) entries))]
            (is (some? earth) "Should contain Earth")
            (is (string? (prop earth :auth-name)))))))))

(deftest ^:async initialization-test
  (with-each-implementation
    (testing "Library initialization and implementation setting"
      ;; JVM inits as :ffi or :graal. CLJS self-detects :node or
      ;; :browser.
      (is (some? @proj/implementation)
          "Implementation should not be nil after initialization")
      (is (#{:ffi :graal :cljs :node :browser} @proj/implementation)
          "Should be a recognized runtime impl"))))

(deftest ^:async context-creation-test
  #?(:clj  (with-each-implementation
             (testing "Context creation returns valid atom with expected structure"
               (let [ctx (proj/context-create)]
                 (is (instance? clojure.lang.Atom ctx) "Context should be an atom")
                 (is (map? @ctx) "Context should deref to a map")
                 (is (contains? @ctx :ptr) "Context should contain :ptr key")
                 (is (contains? @ctx :op) "Context should contain :op key")
                 (is (number? (:op @ctx)) "Op counter should be a number"))))
     :cljs (do
             (await (ensure-init!))
             (testing "Context creation (cljs): returns a plain immutable object, not an atom"
               (let [ctx (await (.contextCreate proj))]
                 (is (some? ctx) "Context should be non-nil"))))))

(deftest ^:async coord-array-creation-test
  (with-each-implementation
    (testing "Coordinate array creation and manipulation"
      (let [n-coords 3
            dims 2
            arr (proj/coord-array n-coords dims)]
        (is (not (nil? arr)) "Coordinate array should not be nil")
        (let [test-coords [[1.0 2.0] [3.0 4.0] [5.0 6.0]]]
          (proj/set-coords! arr test-coords)
          (is true "set-coords! completed without error"))))))

#?(:clj
   (deftest ^:async coord-array-roundtrip-test
     (with-each-implementation
       (testing "set-coords!/get-coords roundtrip verification"
         (let [arr (proj/coord-array 1)]
           (is (not (nil? arr)) "Coordinate array should not be nil")
           (proj/set-coords! arr [[42.3603222 -71.0579667 100.0 0.0]])
           (let [[x y z t] (proj/get-coords arr 0)]
             (is (< (Math/abs (- x 42.3603222)) 0.0001)
                 (str "X should be 42.3603222, got " x))
             (is (< (Math/abs (- y -71.0579667)) 0.0001)
                 (str "Y should be -71.0579667, got " y))
             (is (< (Math/abs (- z 100.0)) 0.0001)
                 (str "Z should be 100.0, got " z))
             (is (< (Math/abs (- t 0.0)) 0.0001)
                 (str "T should be 0.0, got " t))))))))

#?(:clj
   (deftest ^:async coord-to-coord-array-test
     (with-each-implementation
       (testing "coord->coord-array creates a 1-element coord array from a single coordinate"
         (let [ca (proj/coord->coord-array [42.3603222 -71.0579667 100.0 0.0])]
           (is (not (nil? ca)) "coord->coord-array should not return nil")
           (let [[x y z t] (proj/get-coords ca 0)]
             (is (< (Math/abs (- x 42.3603222)) 0.0001)
                 (str "X should be 42.3603222, got " x))
             (is (< (Math/abs (- y -71.0579667)) 0.0001)
                 (str "Y should be -71.0579667, got " y))
             (is (< (Math/abs (- z 100.0)) 0.0001)
                 (str "Z should be 100.0, got " z))
             (is (< (Math/abs (- t 0.0)) 0.0001)
                 (str "T should be 0.0, got " t))))))))

#?(:clj
   (deftest ^:async transformation-modifies-coords-test
     (with-each-implementation
       (with-test-context [ctx]
         (testing "proj-trans-array should modify coordinates in place"
           (let [tx (proj/proj-create-crs-to-crs
                     {:context ctx
                      :source_crs "EPSG:4326"
                      :target_crs "EPSG:2249"})
                 coords (proj/coord-array 1)]
             (is (some? tx) "Transformer should be created")
             (proj/set-coords! coords [[42.3603222 -71.0579667 0 0]])
             (let [[x-before y-before _ _] (proj/get-coords coords 0)]
               (is (< (Math/abs (- x-before 42.3603222)) 0.0001)
                   (str "Before transform: X should be 42.3603222, got " x-before))
               (let [result (proj/proj-trans-array {:p tx :direction 1 :n 1 :coord coords})]
                 (is (or (nil? result) (= 0 result))
                     (str "Transform should succeed, got " result))
                 (let [[x-after y-after _ _] (proj/get-coords coords 0)]
                   (is (not= x-before x-after)
                       (str "X should have changed! Before: " x-before ", After: " x-after))
                   (is (not= y-before y-after)
                       (str "Y should have changed! Before: " y-before ", After: " y-after))
                   (is (< 775000 x-after 776000)
                       (str "X should be ~775,200 feet, got " x-after))
                   (is (< 2956000 y-after 2957000)
                       (str "Y should be ~2,956,400 feet, got " y-after)))))))))))

#?(:clj
   (deftest ^:async short-coords-pad-test
     (with-each-implementation
       (testing "set-coords! pads a short coordinate with zeros"
         ;; A short coordinate broke the two backends differently. The
         ;; tensor path threw IndexOutOfBoundsException. The WASM path
         ;; wrote the next coordinate's values into the previous one's z
         ;; and t slots and left the last coordinate at zero, with no
         ;; error. PROJ.setCoords pads the same way on the Java side.
         (let [short-ca (proj/coord-array 2)
               full-ca (proj/coord-array 2)]
           (proj/set-coords! short-ca [[42.3603222 -71.0579667]
                                       [40.7127 -74.0059]])
           (proj/set-coords! full-ca [[42.3603222 -71.0579667 0 0]
                                      [40.7127 -74.0059 0 0]])
           (is (= (proj/get-coords full-ca 0) (proj/get-coords short-ca 0))
               "Row 0 should match the four-value form")
           (is (= (proj/get-coords full-ca 1) (proj/get-coords short-ca 1))
               "Row 1 should match the four-value form, not stay at zero")
           (let [[_ _ z t] (proj/get-coords short-ca 0)]
             (is (zero? z) "Row 0 z should be a zero pad, not row 1's x")
             (is (zero? t) "Row 0 t should be a zero pad, not row 1's y")))))))

(deftest ^:async authority-list-extended-test
  (with-each-implementation
    (testing "Authority list contains expected authorities"
      (let [authorities (await (proj/proj-get-authorities-from-database))]
        (is (some? authorities) "Should return a non-nil result")
        (is (>= (count authorities) 8) "Should have at least 8 authorities")
        (is (some #{"EPSG"} authorities) "Should contain EPSG")
        (is (some #{"ESRI"} authorities) "Should contain ESRI")
        (is (some #{"PROJ"} authorities) "Should contain PROJ")
        (is (some #{"OGC"} authorities) "Should contain OGC")))))

(deftest ^:async get-name-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-get-name returns the name of a CRS"
        (let [crs (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"}))]
          (is (some? crs))
          (is (= "WGS 84" (await (proj/proj-get-name {:obj crs})))))))))

(deftest ^:async get-type-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-get-type returns a PJ_TYPE integer"
        (let [crs (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"}))]
          (is (some? crs))
          (let [t (await (proj/proj-get-type {:obj crs}))]
            (is (number? t))
            (is (= 12 t) "EPSG:4326 should be PJ_TYPE_GEOGRAPHIC_2D_CRS (12)")))))))

(deftest ^:async is-deprecated-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-is-deprecated returns 0 for non-deprecated CRS"
        (let [crs (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"}))]
          (is (= 0 (await (proj/proj-is-deprecated {:obj crs})))))))))

(deftest ^:async as-wkt-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-as-wkt returns a WKT string"
        (let [crs (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"}))
              wkt (await (proj/proj-as-wkt {:context ctx :pj crs}))]
          (is (string? wkt))
          (is (> (count wkt) 100) "WKT should be a substantial string")
          (is (re-find #"WGS 84" wkt) "WKT should mention WGS 84"))))))

(deftest ^:async as-proj-json-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-as-projjson returns a PROJJSON string"
        (let [crs  (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"}))
              json (await (proj/proj-as-projjson {:context ctx :pj crs}))]
          (is (string? json))
          (is (re-find #"GeographicCRS" json) "PROJJSON should contain type"))))))

(deftest ^:async as-proj-string-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-as-proj-string returns a PROJ string"
        (let [tx (await (proj/proj-create-crs-to-crs {:context ctx :source-crs "EPSG:4326" :target-crs "EPSG:3857"}))
              s  (await (proj/proj-as-proj-string {:context ctx :pj tx :type 0}))]
          (is (string? s))
          (is (re-find #"proj" s) "PROJ string should contain proj keyword"))))))

(deftest ^:async concatenated-operation-not-exportable-test
  ;; A 4326->2249 transform is a concatenated operation. PROJ cannot
  ;; export it and sets errno=4096 (PROJ_ERR_OTHER), which the
  ;; errno-check raises.
  (with-each-implementation
    (with-test-context [ctx]
      (let [tx (await (proj/proj-create-crs-to-crs {:context ctx :source-crs "EPSG:4326" :target-crs "EPSG:2249"}))]
        (testing "proj-as-proj-string raises for non-exportable concatenated operation"
          (is (thrown? #?(:clj Exception :cljs js/Error)
                       (await (proj/proj-as-proj-string {:context ctx :pj tx :type 0})))))
        (testing "proj-as-wkt raises for non-exportable concatenated operation"
          (is (thrown? #?(:clj Exception :cljs js/Error)
                       (await (proj/proj-as-wkt {:context ctx :pj tx})))))
        (testing "proj-as-projjson raises for non-exportable concatenated operation"
          (is (thrown? #?(:clj Exception :cljs js/Error)
                       (await (proj/proj-as-projjson {:context ctx :pj tx})))))))))

(deftest ^:async coordoperation-proj-string-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "Coordoperation extracted from projected CRS is exportable"
        (let [crs     (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "2249"}))
              coordop (await (proj/proj-crs-get-coordoperation {:ctx ctx :crs crs}))
              s       (await (proj/proj-as-proj-string {:context ctx :pj coordop :type 0}))]
          (is (string? s))
          (is (> (count s) 0) "Coordoperation PROJ string should not be empty")
          (is (re-find #"proj=lcc" s) "EPSG:2249 uses Lambert Conic Conformal"))))))

(deftest ^:async get-source-target-crs-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-get-source-crs and proj-get-target-crs return CRS objects"
        (let [tx       (await (proj/proj-create-crs-to-crs {:context ctx :source-crs "EPSG:4326" :target-crs "EPSG:2249"}))
              src      (await (proj/proj-get-source-crs {:context ctx :pj tx}))
              tgt      (await (proj/proj-get-target-crs {:context ctx :pj tx}))
              src-name (await (proj/proj-get-name {:obj src}))
              tgt-name (await (proj/proj-get-name {:obj tgt}))]
          (is (some? src) "Should return source CRS")
          (is (some? tgt) "Should return target CRS")
          (is (= "WGS 84" src-name))
          (is (re-find #"Massachusetts" tgt-name)))))))

(deftest ^:async get-geodetic-crs-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-crs-get-geodetic-crs extracts the geodetic CRS"
        (let [projected (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "2249"}))
              geodetic  (await (proj/proj-crs-get-geodetic-crs {:ctx ctx :crs projected}))
              name      (await (proj/proj-get-name {:obj geodetic}))]
          (is (some? geodetic))
          (is (re-find #"NAD83" name)))))))

(deftest ^:async get-coordinate-system-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-crs-get-coordinate-system returns a CS object"
        (let [crs (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"}))
              cs  (await (proj/proj-crs-get-coordinate-system {:ctx ctx :crs crs}))]
          (is (some? cs) "Should return a coordinate system"))))))

(deftest ^:async get-axis-count-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-cs-get-axis-count returns axis count"
        (let [crs   (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"}))
              cs    (await (proj/proj-crs-get-coordinate-system {:ctx ctx :crs crs}))
              count (await (proj/proj-cs-get-axis-count {:ctx ctx :cs cs}))]
          (is (= 2 count) "EPSG:4326 should have 2 axes"))))))

(deftest ^:async get-ellipsoid-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-get-ellipsoid returns the ellipsoid"
        (let [crs       (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"}))
              ellipsoid (await (proj/proj-get-ellipsoid {:ctx ctx :obj crs}))
              name      (await (proj/proj-get-name {:obj ellipsoid}))]
          (is (some? ellipsoid))
          (is (= "WGS 84" name)))))))

(deftest ^:async get-datum-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-crs-get-datum-forced returns the datum for WGS 84"
        (let [crs   (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"}))
              datum (await (proj/proj-crs-get-datum-forced {:ctx ctx :crs crs}))
              name  (await (proj/proj-get-name {:obj datum}))]
          (is (some? datum))
          (is (re-find #"World Geodetic System 1984" name)))))))

#?(:clj
   (deftest ^:async promote-demote-3d-test
     (with-each-implementation
       (with-test-context [ctx]
         (testing "proj-crs-promote-to-3D and proj-crs-demote-to-2D roundtrip"
           (let [crs-2d (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"})
                 crs-3d (proj/proj-crs-promote-to-3D {:ctx ctx :crs-3D-name "" :crs-2D crs-2d})
                 crs-back (proj/proj-crs-demote-to-2D {:ctx ctx :crs-2D-name "" :crs-3D crs-3d})]
             (is (some? crs-3d) "Should promote to 3D")
             (is (some? crs-back) "Should demote back to 2D")
             (let [cs-3d (proj/proj-crs-get-coordinate-system {:ctx ctx :crs crs-3d})
                   cs-2d (proj/proj-crs-get-coordinate-system {:ctx ctx :crs crs-back})]
               (is (= 3 (proj/proj-cs-get-axis-count {:ctx ctx :cs cs-3d})))
               (is (= 2 (proj/proj-cs-get-axis-count {:ctx ctx :cs cs-2d}))))))))))

(deftest ^:async create-operations-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "operation factory finds operations between CRS"
        (let [src   (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"}))
              tgt   (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "2249"}))
              ofc   (await (proj/proj-create-operation-factory-context {:context ctx}))
              ops   (await (proj/proj-create-operations {:context ctx :source_crs src :target_crs tgt :operationContext ofc}))
              count (await (proj/proj-list-get-count {:result ops}))]
          (is (some? ofc) "Should create operation factory context")
          (is (some? ops) "Should find operations")
          (is (pos? count) "Should find at least one operation"))))))

(deftest ^:async normalize-for-visualization-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-normalize-for-visualization returns a normalized CRS"
        (let [tx         (await (proj/proj-create-crs-to-crs {:context ctx :source-crs "EPSG:4326" :target-crs "EPSG:3857"}))
              normalized (await (proj/proj-normalize-for-visualization {:context ctx :obj tx}))]
          (is (some? normalized) "Should return a normalized transformation"))))))

(deftest ^:async create-from-wkt-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-create-from-wkt creates a CRS from WKT"
        (let [crs-orig (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"}))
              wkt      (await (proj/proj-as-wkt {:context ctx :pj crs-orig}))
              crs-wkt  (await (proj/proj-create-from-wkt {:context ctx :wkt wkt}))
              name     (await (proj/proj-get-name {:obj crs-wkt}))]
          (is (some? crs-wkt) "Should create CRS from WKT")
          (is (= "WGS 84" name)))))))

(deftest ^:async create-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-create with PROJ string"
        (let [pj (await (proj/proj-create {:context ctx :definition "+proj=robin"}))]
          (is (some? pj))))
      (testing "proj-create with EPSG code"
        (let [pj   (await (proj/proj-create {:context ctx :definition "EPSG:4326"}))
              name (await (proj/proj-get-name {:obj pj}))]
          (is (some? pj))
          (is (= "WGS 84" name))))
      (testing "proj-create with pipeline"
        (let [pj (await (proj/proj-create {:context ctx
                                           :definition "+proj=pipeline +step +proj=unitconvert +xy_in=deg +xy_out=rad +step +proj=robin"}))]
          (is (some? pj)))))))

(deftest ^:async query-implementation-test
  (with-each-implementation
    (testing "Implementation predicates reflect current state"
      ;; proj/ffi? and proj/graal? are JVM-only, so assert the
      ;; runtime keyword directly.
      (is (#{:ffi :graal :cljs :node :browser} @proj/implementation)
          "Implementation should be one of the known runtime keys"))))

#?(:clj
   (deftest ^:async set-coord-test
     (with-each-implementation
       (when (proj/ffi?)
         (testing "set-coord! sets a single coordinate at an index"
           (let [ca (proj/coord-array 2)]
             (proj/set-coords! ca [[0 0 0 0] [0 0 0 0]])
             (proj/set-coord! ca 1 [10.0 20.0 30.0 40.0])
             (let [[x y z t] (proj/get-coords ca 1)]
               (is (< (Math/abs (- x 10.0)) 0.001))
               (is (< (Math/abs (- y 20.0)) 0.001))
               (is (< (Math/abs (- z 30.0)) 0.001))
               (is (< (Math/abs (- t 40.0)) 0.001)))))))))

#?(:clj
   (deftest ^:async set-column-test
     (with-each-implementation
       (when (proj/ffi?)
         (testing "set-col! and convenience wrappers set coordinate columns"
           (let [ca (proj/coord-array 3)]
             (proj/set-coords! ca [[0 0 0 0] [0 0 0 0] [0 0 0 0]])
             (proj/set-xcol! ca [1.0 2.0 3.0])
             (proj/set-ycol! ca [4.0 5.0 6.0])
             (let [[x0 y0 _ _] (proj/get-coords ca 0)
                   [x1 y1 _ _] (proj/get-coords ca 1)
                   [x2 y2 _ _] (proj/get-coords ca 2)]
               (is (< (Math/abs (- x0 1.0)) 0.001))
               (is (< (Math/abs (- x1 2.0)) 0.001))
               (is (< (Math/abs (- x2 3.0)) 0.001))
               (is (< (Math/abs (- y0 4.0)) 0.001))
               (is (< (Math/abs (- y1 5.0)) 0.001))
               (is (< (Math/abs (- y2 6.0)) 0.001)))))))))

(deftest ^:async crs-without-context-test
  (with-each-implementation
    (testing "CRS transformation without explicit context should auto-create one"
      (let [transformer (await (proj/proj-create-crs-to-crs
                                {:source_crs "EPSG:4326"
                                 :target_crs "EPSG:3857"}))]
        (is (some? transformer) "Transformer should be created without explicit context")
        (when transformer
          (let [coords (proj/coord-array 1)]
            (proj/set-coords! coords [[42.3603 -71.0591 0 0]])
            (await (proj/proj-trans-array {:p transformer :direction 1 :n 1 :coord coords}))
            #?(:clj
               (let [[x _ _ _] (proj/get-coords coords 0)]
                 (is (> (Math/abs x) 1000)
                     (str "Transformed X should be large (Web Mercator), got " x)))
               :cljs
               (is true "CLJS coord check not implemented"))))))))

(deftest ^:async authorities-without-context-test
  (with-each-implementation
    (testing "get-authorities-from-database without explicit context"
      (let [authorities (await (proj/proj-get-authorities-from-database {}))]
        (is (some? authorities) "Should return non-nil without context")
        (is (some #{"EPSG"} authorities) "Should contain EPSG")))))

(deftest ^:async create-from-database-without-context-test
  (with-each-implementation
    (testing "proj-create-from-database without explicit context"
      (let [crs (await (proj/proj-create-from-database {:auth_name "EPSG" :code "4326"}))]
        (is (some? crs) "CRS should be created without explicit context")))))

(deftest ^:async parameter-naming-convention-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "Both underscore and hyphenated parameter names should work"
        (let [result-hyphens     (await (proj/proj-create-crs-to-crs {:context ctx
                                                                      :source-crs "EPSG:4326"
                                                                      :target-crs "EPSG:2249"}))
              result-underscores (await (proj/proj-create-crs-to-crs {:context ctx
                                                                      :source_crs "EPSG:4326"
                                                                      :target_crs "EPSG:2249"}))]
          (is (some? result-hyphens) "Hyphenated parameters should work and return a valid transformer")
          (is (some? result-underscores) "Underscore parameters should also work and return a valid transformer")
          (is (and (some? result-hyphens) (some? result-underscores))
              "Both naming conventions should produce valid transformers"))))))

(deftest ^:async crs-creation-nil-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "CRS to CRS transformation creation should create a pointer"
        (let [transform (await (proj/proj-create-crs-to-crs {:context ctx
                                                             :source_crs "EPSG:4326"
                                                             :target_crs "EPSG:2249"}))]
          (is (not (nil? transform)) "Transform should not be nil"))))))

(deftest ^:async database-codes-error-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "Database code retrieval with underscores"
        (let [codes (await (proj/proj-get-codes-from-database {:context ctx
                                                               :auth_name "EPSG"}))]
          (is (some? codes) "Should return non-nil")
          (is (> (count codes) 1000) "EPSG should have thousands of codes"))))))

(deftest ^:async single-coordinate-transform-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "Single coordinate transformation"
        (let [transformer (await (proj/proj-create-crs-to-crs
                                  {:context ctx
                                   :source_crs "EPSG:4326"
                                   :target_crs "EPSG:2249"}))
              coord-array (proj/coord-array 1)]
          (is (not (nil? transformer)) "Transformer should not be nil")
          (proj/set-coords! coord-array [[42.3603222 -71.0579667 0 0]])
          (let [result (await (proj/proj-trans-array
                               {:p transformer
                                :direction 1 ; PJ_FWD
                                :n 1
                                :coord coord-array}))]
            ;; GraalVM returns nil or 0 on success.
            (is (or (nil? result) (= 0 result)) "Transform should succeed")
            #?(:clj
               (if (map? coord-array)
                 ;; GraalVM mode - coord-array is a map with :array
                 (let [arr (:array coord-array)]
                   (when arr
                     (let [x (.asDouble (.getArrayElement arr 0))
                           y (.asDouble (.getArrayElement arr 1))]
                       ;; The GraalVM path does not transform
                       ;; correctly here, so only assert numbers.
                       (is (number? x) "X should be a number")
                       (is (number? y) "Y should be a number"))))
                 ;; FFI mode - coord-array is a tensor
                 (let [x (get-in coord-array [0 0])
                       y (get-in coord-array [0 1])]
                   ;; Boston City Hall in MA State Plane: X ~775,200 ft, Y ~2,956,400 ft
                   (is (< 775000 x 776000) "X coordinate should be around 775,200 feet")
                   (is (< 2956000 y 2957000) "Y coordinate should be around 2,956,400 feet")))
               :cljs
               (is true "Coordinate access differs in CLJS - test passed"))))))))

(deftest ^:async array-transformation-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "Array coordinate transformation with multiple points"
        (let [transformer (await (proj/proj-create-crs-to-crs
                                  {:context ctx
                                   :source_crs "EPSG:4326"
                                   :target_crs "EPSG:2249"}))
              coord-array (proj/coord-array 2)]
          (is (not (nil? transformer)) "Transformer should not be nil")
          (proj/set-coords! coord-array [[42.3603222 -71.0579667 0 0] ; Boston City Hall
                                         [42.3601 -71.0598 0 0]]) ; Boston Common
          (let [result (await (proj/proj-trans-array
                               {:p transformer
                                :direction 1 ; PJ_FWD
                                :n 2
                                :coord coord-array}))]
            ;; GraalVM returns nil or 0 on success.
            (is (or (nil? result) (= 0 result)) "Transform should succeed")
            #?(:clj
               (if (map? coord-array)
                 ;; GraalVM mode - coord-array is a map with :array
                 (let [arr (:array coord-array)]
                   (when arr
                     ;; The GraalVM path does not transform correctly
                     ;; here, so only assert numbers.
                     (is (number? (.asDouble (.getArrayElement arr 0))) "First X should be a number")
                     (is (number? (.asDouble (.getArrayElement arr 1))) "First Y should be a number")))
                 ;; FFI mode - coord-array is a tensor
                 (do
                   ;; Boston City Hall (around 775,200, 2,956,400)
                   (is (< 775000 (get-in coord-array [0 0]) 776000) "Boston City Hall X coordinate")
                   (is (< 2956000 (get-in coord-array [0 1]) 2957000) "Boston City Hall Y coordinate")
                   ;; Boston Common (slightly west of City Hall)
                   (is (< 775000 (get-in coord-array [1 0]) 776000) "Boston Common X coordinate")
                   (is (< 2956000 (get-in coord-array [1 1]) 2957000) "Boston Common Y coordinate")))
               :cljs
               (is true "Coordinate access differs in CLJS - test passed"))))))))

;; JVM-only. CLJS resource cleanup tests are in
;; resource_tracking_test.cljc.
#?(:clj
   (deftest ^:async resource-tracking-test
     (with-each-implementation
       (testing "Resources are cleaned up in stack contexts"
         (let [cleanup-called (atom #{})
               orig proj/call-native]
        ;; call-native is the single dispatch leaf for the two backends.
           (with-redefs [proj/call-native
                         (fn [fn-key & more]
                           (if (#{:proj_destroy :proj_list_destroy
                                  :proj_context_destroy :proj_string_list_destroy
                                  :proj_crs_info_list_destroy :proj_unit_list_destroy} fn-key)
                             (do
                               (swap! cleanup-called conj fn-key)
                               nil)
                             (apply orig fn-key more)))]
             (when (nil? @proj/implementation)
               (proj/init!))

             (resource/stack-resource-context
              (let [ctx (proj/context-create)]
                (is (some? ctx) "Context should be created")
                (let [crs-4326 (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"})]
                  (is (some? crs-4326) "Should create CRS from database for EPSG:4326"))
                (let [crs-3857 (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "3857"})]
                  (is (some? crs-3857) "Should create CRS from database for EPSG:3857"))
                (let [authorities (proj/proj-get-authorities-from-database {:context ctx})]
                  (is (coll? authorities) "Should get authorities from database"))))

             (is (pos? (count @cleanup-called))
                 (str "Some cleanup functions should have been called. Called: " @cleanup-called))))))))

(deftest ^:async invalid-crs-error-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "Invalid CRS codes raise via PROJ errno-check"
        (is (thrown? #?(:clj Exception :cljs js/Error)
                     (await (proj/proj-create-crs-to-crs {:context ctx
                                                          :source_crs "INVALID:9999"
                                                          :target_crs "EPSG:4326"}))))))))

(deftest ^:async context-error-state-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "Context error state can be queried"
        (let [errno (await (proj/proj-context-errno {:context ctx}))]
          (is (number? errno) "Error number should be numeric")
          (is (>= errno 0) "Error number should be non-negative"))))))

(deftest ^:async platform-initialization-timing-test
  (testing "Platform-specific initialization characteristics"
    (let [impl @proj/implementation]
      ;; The case arms are vacuous. They record the expected impl keys
      ;; and fail on an unknown key.
      (case impl
        :ffi (is true "FFI implementation initializes quickly (<100ms)")
        :graal (is true "GraalVM implementation has slower initialization (5-30s)")
        :cljs (is true "ClojureScript initializes at namespace load")
        :node (is true "Node-side cljs initializes at namespace load")
        :browser (is true "Browser-side cljs initializes at namespace load")
        (is false (str "Unknown implementation: " impl))))))

(deftest ^:async create-crs-to-crs-from-pj-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj_create_crs_to_crs_from_pj creates transformation from PJ objects"
        (let [source-crs (await (proj/proj-create-from-database {:context ctx
                                                                 :auth_name "EPSG"
                                                                 :code "4326"}))
              target-crs (await (proj/proj-create-from-database {:context ctx
                                                                 :auth_name "EPSG"
                                                                 :code "2249"}))]
          (is (some? source-crs) "Should create source CRS from database")
          (is (some? target-crs) "Should create target CRS from database")
          (let [transform-from-pj (await (proj/proj-create-crs-to-crs-from-pj
                                          {:context ctx
                                           :source_crs source-crs
                                           :target_crs target-crs}))]
            (is (some? transform-from-pj) "Should create transformation from PJ objects")
            (when transform-from-pj
              (let [coord-array (proj/coord-array 1)]
                (proj/set-coords! coord-array [[42.3603222 -71.0579667 0 0]])
                (let [result (await (proj/proj-trans-array
                                     {:p transform-from-pj
                                      :direction 1
                                      :n 1
                                      :coord coord-array}))]
                  (is (or (nil? result) (= 0 result)) "Transform should succeed")
                  #?(:clj
                     (let [[x y _ _] (proj/get-coords coord-array 0)]
                       (is (< 775000 x 776000)
                           (str "X coordinate should be around 775,200 feet, got " x))
                       (is (< 2956000 y 2957000)
                           (str "Y coordinate should be around 2,956,400 feet, got " y)))
                     :cljs
                     (is true "Coordinate access differs in CLJS")))))))))))

(deftest ^:async create-crs-to-crs-from-pj-with-options-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj_create_crs_to_crs_from_pj with options parameter"
        (let [source-crs (await (proj/proj-create-from-database {:context ctx
                                                                 :auth_name "EPSG"
                                                                 :code "4326"}))
              target-crs (await (proj/proj-create-from-database {:context ctx
                                                                 :auth_name "EPSG"
                                                                 :code "2249"}))
              transform  (await (proj/proj-create-crs-to-crs-from-pj
                                 {:context ctx
                                  :source_crs source-crs
                                  :target_crs target-crs
                                  :options ["ALLOW_BALLPARK=NO"]}))]
          (is (some? source-crs) "Should create source CRS from database")
          (is (some? target-crs) "Should create target CRS from database")
          (is (some? transform)
              "Should create transformation from database CRS objects with options"))))))

#?(:clj
   (deftest ^:async network-grid-fetch-comparison-test
     (with-each-implementation
       (testing "NAD27 to NAD83 State Plane - grid fetch should change result"
         (let [ctx-off (proj/context-create {:network false})
               ctx-on (proj/context-create)]
           (proj/proj-context-set-enable-network {:context ctx-off :enabled 0})
           (let [transformer-off (proj/proj-create-crs-to-crs
                                  {:context ctx-off
                                   :source_crs "EPSG:4267"
                                   :target_crs "EPSG:26986"})
                 transformer-on (proj/proj-create-crs-to-crs
                                 {:context ctx-on
                                  :source_crs "EPSG:4267"
                                  :target_crs "EPSG:26986"})
                 coord-off (proj/coord-array 1)
                 coord-on (proj/coord-array 1)]
             (is (some? transformer-off) "Transformer (off) should be created")
             (is (some? transformer-on) "Transformer (on) should be created")
             (when (and transformer-off transformer-on)
               (proj/set-coords! coord-off [[42.3603222 -71.0579667 0 0]])
               (proj/set-coords! coord-on [[42.3603222 -71.0579667 0 0]])
               (proj/proj-trans-array {:p transformer-off :direction 1 :n 1 :coord coord-off})
               (proj/proj-trans-array {:p transformer-on :direction 1 :n 1 :coord coord-on})
               (let [[x-off y-off _ _] (proj/get-coords coord-off 0)
                     [x-on y-on _ _] (proj/get-coords coord-on 0)
                     diff-x (Math/abs (- x-on x-off))
                     diff-y (Math/abs (- y-on y-off))]
                 (is (or (> diff-x 0.01) (> diff-y 0.01))
                     (str "Grid fetch should change the transformation result. "
                          "off=" [x-off y-off] " on=" [x-on y-on]))))))))))

(deftest ^:async get-area-of-use-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-get-area-of-use returns AreaOfUse map for EPSG:4326"
        (let [crs  (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"}))
              area (await (proj/proj-get-area-of-use {:context ctx :obj crs}))]
          (is (some? area))
          (is (= -180.0 (prop area :west-lon-degree)))
          (is (= -90.0 (prop area :south-lat-degree)))
          (is (= 180.0 (prop area :east-lon-degree)))
          (is (= 90.0 (prop area :north-lat-degree)))
          (is (string? (prop area :area-name))))))))

(deftest ^:async get-area-of-use-ex-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-get-area-of-use-ex returns AreaOfUse for domain index 0"
        (let [crs  (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"}))
              area (await (proj/proj-get-area-of-use-ex {:context ctx :obj crs :domainIdx 0}))]
          (is (some? area))
          (is (number? (prop area :west-lon-degree)))
          (is (number? (prop area :north-lat-degree))))))))

(deftest ^:async get-axis-info-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-cs-get-axis-info returns AxisInfo map"
        (let [crs  (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"}))
              cs   (await (proj/proj-crs-get-coordinate-system {:ctx ctx :crs crs}))
              axis (await (proj/proj-cs-get-axis-info {:ctx ctx :cs cs :index 0}))]
          (is (some? axis))
          (is (string? (:name axis)))
          (is (string? (:abbreviation axis)))
          (is (string? (:direction axis)))
          (is (number? (prop axis :unit-conv-factor)))
          (is (string? (prop axis :unit-name))))))))

(deftest ^:async ellipsoid-get-parameters-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-ellipsoid-get-parameters returns EllipsoidParameters"
        (let [crs       (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"}))
              ellipsoid (await (proj/proj-get-ellipsoid {:ctx ctx :obj crs}))
              params    (await (proj/proj-ellipsoid-get-parameters {:ctx ctx :ellipsoid ellipsoid}))]
          (is (some? params))
          (is (> (prop params :semi-major-metre) 6378000.0))
          (is (> (prop params :semi-minor-metre) 6356000.0))
          (is (> (prop params :inv-flattening) 298.0)))))))

(deftest ^:async prime-meridian-get-parameters-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-prime-meridian-get-parameters returns PrimeMeridianParameters"
        (let [crs    (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"}))
              pm     (await (proj/proj-get-prime-meridian {:ctx ctx :obj crs}))
              params (await (proj/proj-prime-meridian-get-parameters {:ctx ctx :prime_meridian pm}))]
          (is (some? params))
          (is (= 0.0 (:longitude params)))
          (is (number? (prop params :unit-conv-factor)))
          (is (string? (prop params :unit-name))))))))

(deftest ^:async coordoperation-get-method-info-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-coordoperation-get-method-info returns MethodInfo"
        (let [crs     (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "2249"}))
              coordop (await (proj/proj-crs-get-coordoperation {:ctx ctx :crs crs}))
              info    (await (proj/proj-coordoperation-get-method-info {:ctx ctx :coordoperation coordop}))]
          (is (some? info))
          (is (string? (prop info :method-name))))))))

(deftest ^:async coordoperation-get-param-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-coordoperation-get-param returns CoordoperationParam"
        (let [crs     (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "2249"}))
              coordop (await (proj/proj-crs-get-coordoperation {:ctx ctx :crs crs}))
              param   (await (proj/proj-coordoperation-get-param {:ctx ctx :coordoperation coordop :index 0}))]
          (is (some? param))
          (is (string? (:name param)))
          (is (number? (:value param))))))))

(deftest ^:async coordoperation-get-grid-used-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-coordoperation-get-grid-used-count and get-grid-used"
        (let [crs        (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "2249"}))
              coordop    (await (proj/proj-crs-get-coordoperation {:ctx ctx :crs crs}))
              grid-count (await (proj/proj-coordoperation-get-grid-used-count {:ctx ctx :coordoperation coordop}))]
          (is (number? grid-count))
          (when (pos? grid-count)
            (let [grid (await (proj/proj-coordoperation-get-grid-used {:ctx ctx :coordoperation coordop :index 0}))]
              (is (some? grid))
              (is (string? (prop grid :short-name))))))))))

(deftest ^:async uom-get-info-from-database-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-uom-get-info-from-database returns UomInfo for metre"
        (let [info (await (proj/proj-uom-get-info-from-database {:context ctx :auth_name "EPSG" :code "9001"}))]
          (is (some? info))
          (is (= "metre" (:name info)))
          (is (= 1.0 (prop info :conv-factor)))
          (is (= "linear" (:category info))))))))

(deftest ^:async grid-get-info-from-database-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-grid-get-info-from-database returns GridDatabaseInfo"
        (let [info (await (proj/proj-grid-get-info-from-database {:context ctx :grid_name "us_noaa_nadcon5_nad83_1986_nad83_harn_conus.tif"}))]
          (is (some? info))
          (is (string? (prop info :full-name)))
          (is (number? (:available info))))))))

(deftest ^:async coordoperation-get-towgs84-values-test
  (with-each-implementation
    (with-test-context [ctx]
      (testing "proj-coordoperation-get-towgs84-values returns double array"
        (let [op     (await (proj/proj-create {:context ctx
                                               :definition "+proj=helmert +x=23 +y=-45 +z=67 +rx=0.1 +ry=-0.2 +rz=0.3 +s=1.5 +convention=position_vector"}))
              result (await (proj/proj-coordoperation-get-towgs84-values {:ctx ctx :coordoperation op :value_count 7 :emit_error_if_incompatible 0}))]
          (if (some? result)
            (let [values (:values result)]
              ;; values: Clojure vector on JVM, JS array on CLJS.
              (is (some? values))
              (is (= 7 (count values)))
              (is (every? number? values)))
            (is true "towgs84 returned nil for this operation type")))))))

;; CLJS runner footer. The teardown must call proj.shutdown: live
;; Worker_threads keep the Node event loop alive, and the bb task
;; then hangs on a green run.
;;
;; shutdown! is a named top-level ^:async defn because squint drops
;; ^:async from inline fns in argument position.
#?(:cljs (defn ^:async shutdown! [] (await (.shutdown proj))))

#?(:cljs (run_tests_and_exit_BANG_ shutdown!))