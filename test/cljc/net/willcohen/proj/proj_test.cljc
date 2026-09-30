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
  #?(:clj (:require [clojure.test :refer [deftest is testing]]
                    [net.willcohen.proj.proj :as proj]
                    [net.willcohen.proj.fndefs :as pdefs]
                    [net.willcohen.proj.impl.network :as proj-network]
                    [net.willcohen.native.graal-wasm :as nw]
                    [net.willcohen.proj.wasm :as wasm]
                    [tech.v3.resource :as resource])
     :cljs (:require [cljs.test :as t :refer [deftest is testing]]
                     ["proj-wasm" :as proj]
                     [net.willcohen.proj.proj-test-macros
                      :refer [with-each-implementation with-test-context]]
                     ;; `bb stage:clj-native-test-deps` copies this
                     ;; into test/cljc/dist/. An import through the
                     ;; proj-wasm symlink chain loads a second
                     ;; squint-cljs and splits the cljs.test registry.
                     ["../../../dist/test_runner.mjs"
                      :refer [run_tests_and_exit_BANG_]])))

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
   (def test-implementation
     (keyword (System/getProperty "net.willcohen.proj.proj-test.implementation" "ffi"))))

#?(:clj
   (defmacro with-each-implementation
     "Run body on the implementation that the
      net.willcohen.proj.proj-test.implementation property names."
     [& body]
     `(testing (str "With implementation: " (name test-implementation))
        ;; force-*! clears @implementation. The explicit init! is
        ;; necessary for a test that reads @proj/implementation and
        ;; calls no proj fn (initialization-test).
        (case test-implementation
          :ffi (proj/force-ffi!)
          :graal (proj/force-graal!))
        (proj/init!)
        ;; A missing or unloadable native lib makes init! fall back to
        ;; GraalVM, and the FFI lane would then pass on wasm.
        (is (= test-implementation @proj/implementation)
            "init! did not fall back from the requested implementation")
        ~@body)))

;; tech.v3.resource :auto tracking releases the context.
#?(:clj
   (defmacro with-test-context [[ctx-binding] & body]
     `(let [~ctx-binding (proj/context-create)]
        ~@body)))

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
          (is (some #{"4326"} epsg-codes) "Should contain a well-known code like '4326'")))
      (testing "a NULL string list reads as empty"
        ;; PROJ returns NULL for PJ_TYPE_UNKNOWN (0) and sets no errno.
        (is (empty? (await (proj/proj-get-codes-from-database {:context ctx
                                                               :auth_name "EPSG"
                                                               :type 0}))))))))

;; PROJ did not find proj.ini when the context turned the network on, and
;; left errno ENOENT (44 on wasm) on the new context. The errno check then
;; threw that stale code after a NULL result.
(deftest ^:async new-context-has-no-errno-test
  (with-each-implementation
    (with-test-context [ctx]
      (is (= 0 (await (proj/proj-context-errno {:context ctx})))))))

;; The fndef had no ctx argument, so the code went in as the PJ_CONTEXT
;; pointer, and PROJ wrote the message through that address.
(deftest ^:async context-errno-string-test
  (with-each-implementation
    (with-test-context [ctx]
      (is (= "API misuse"
             (await (proj/proj-context-errno-string {:context ctx :err 4097}))))
      (is (= "Unknown error (code 1)"
             (await (proj/proj-context-errno-string {:err 1})))
          "a call with no context gets a new one"))))

;; PROJ finds no operation from an engineering CRS to a geographic CRS. It
;; returns NULL and sets no errno.
(deftest ^:async no-operation-gives-nil-test
  (with-each-implementation
    (with-test-context [ctx]
      (is (nil? (await (proj/proj-create-crs-to-crs {:context ctx
                                                     :source_crs "EPSG:5800"
                                                     :target_crs "EPSG:4326"})))))))

(defn- error-message [e]
  #?(:clj (.getMessage ^Exception e) :cljs (.-message e)))

;; PROJ keeps errno after a failure, and proj_log_error sets a new code only
;; when errno is 0. The errno check read the context after the call, so after
;; one failure on a context, a NULL with no error of its own threw the old
;; code, and a new failure also reported the old code.
(deftest ^:async errno-of-each-call-test
  (with-each-implementation
    (with-test-context [ctx]
      (let [merc (await (proj/proj-create {:context ctx :definition "+proj=merc +ellps=WGS84"}))
            geog (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"}))
            ca (proj/coord-array 1)]
        (await (proj/set-coords! ca [[0 2.0 0 0]]))
        (is (= 2049 (await (proj/proj-trans-array {:p merc :direction 1 :n 1 :coord ca})))
            "a latitude of 2 radians fails, and leaves errno 2049 on ctx")
        (is (nil? (await (proj/proj-create-crs-to-crs {:context ctx
                                                       :source_crs "EPSG:5800"
                                                       :target_crs "EPSG:4326"})))
            "a NULL with no error of its own gives nil")
        (let [e (try (await (proj/proj-get-source-crs {:context ctx :pj geog}))
                     nil
                     (catch #?(:clj Exception :cljs :default) e e))]
          (is (re-find #"PROJ error 4096 " (str (some-> e error-message)))
              "a geographic CRS has no source CRS: the failure gives its own errno"))))))

;; proj_context_clone reports no failure through errno, so a clone must not
;; clear the errno of the context it copies.
(deftest ^:async context-clone-keeps-the-errno-test
  (with-each-implementation
    (with-test-context [ctx]
      (let [merc (await (proj/proj-create {:context ctx :definition "+proj=merc +ellps=WGS84"}))
            ca (proj/coord-array 1)]
        (await (proj/set-coords! ca [[0 2.0 0 0]]))
        (await (proj/proj-trans-array {:p merc :direction 1 :n 1 :coord ca}))
        (is (= 2049 (await (proj/proj-context-errno {:context ctx}))))
        (is (some? (await (proj/proj-context-clone {:ctx ctx}))))
        (is (= 2049 (await (proj/proj-context-errno {:context ctx})))
            "the clone kept the errno of ctx")))))

;; On ClojureScript an options arg reached ccall as a JS array, which ccall
;; passes as a number, so PROJ got NULL and ignored the options.
(deftest ^:async options-arg-test
  (with-each-implementation
    (with-test-context [ctx]
      (let [crs (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"}))
            wkt (await (proj/proj-as-wkt {:context ctx :pj crs :options ["MULTILINE=NO"]}))]
        (is (string? wkt))
        (is (not (re-find #"\n" wkt)) "MULTILINE=NO gives one line")))))

;; proj_coord, proj_xy_dist and proj_get_suggested_operation take or return a
;; PJ_COORD by value. Their :pointer bindings passed the wrong ABI.
(deftest by-value-pj-coord-fns-are-not-bound-test
  #?(:clj (is (not-any? (set (keys pdefs/fndefs))
                        [:proj_coord :proj_xy_dist :proj_get_suggested_operation]))
     :cljs (is (every? (fn [k] (nil? (aget proj k)))
                       ["projCoord" "projXyDist" "projGetSuggestedOperation"]))))

;; GraalVM: coord->coord-array wrote only x and y of [x y] into its malloc
;; block, so z and t held what the heap held before.
#?(:clj
   (deftest short-coord-to-coord-array-zero-fills-test
     (with-each-implementation
       (let [real-malloc wasm/malloc]
         (with-redefs [wasm/malloc (fn [b]
                                     (let [p (real-malloc b)]
                                       (nw/heap-write-doubles! (nw/address-as-int p)
                                                               (double-array (quot b 8) 7.0))
                                       p))]
           (is (= [1.0 2.0 0.0 0.0] (proj/get-coords (proj/coord->coord-array [1.0 2.0]) 0))))))))

;; GraalVM: the get_header callback copied each header value into a new wasm
;; block, and nothing freed it: three blocks for each grid file that PROJ
;; opened.
#?(:clj
   (deftest get-header-strings-are-freed-test
     (with-each-implementation
       (when (proj/graal?)
         (let [module @wasm/p
               real-execute nw/module-execute
               allocated (atom [])
               freed (atom [])
               get-header (#'proj-network/create-get-header-callback module)
               close (#'proj-network/create-close-callback module)
               call-get-header (nw/module-eval-js module "(f, h, n) => f(0, h, n, 0)" "get-header-test.js")
               call-close (nw/module-eval-js module "(f, h) => f(0, h, 0)" "close-test.js")
               id (#'proj-network/create-handle! "https://example.invalid/g.tif"
                                                 {"content-range" "bytes 0-9/100"})
               name-ptr (nw/module-execute module "stringToNewUTF8" ["Content-Range"] :int)]
           (try
             (with-redefs [nw/module-execute
                           (fn [m path args & more]
                             (let [r (apply real-execute m path args more)]
                               (case path
                                 "stringToNewUTF8" (swap! allocated conj r)
                                 "_free" (swap! freed conj (first args))
                                 nil)
                               r))]
               (dotimes [_ 3] (nw/value-execute call-get-header [get-header id name-ptr] :int))
               (nw/value-execute call-close [close id]))
             (is (= 3 (count @allocated)))
             (is (= (set @allocated) (set @freed)) "close freed each header string of the handle")
             (finally (nw/module-execute module "_free" [name-ptr]))))))))

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
            (is (< 0.3 (prop us-foot :conv-factor) 0.4) "US survey foot conv-factor ~0.3048"))))
      (testing "a nil category lists the units of every category"
        (let [entries (await (proj/proj-get-units-from-database
                              {:context ctx :auth-name "EPSG" :allow-deprecated 0}))]
          (is (some #(= "9001" (:code %)) entries) "a linear unit, EPSG:9001 metre")
          (is (some #(= "9101" (:code %)) entries) "an angular unit, EPSG:9101 radian"))))))

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
      (is (#{:ffi :graal :cljs :node :browser} @proj/implementation)
          "Should be a recognized runtime impl"))))

;; cs runs a PROJ call once. Inside a swap!, a concurrent change of the
;; context atom would make the swap! retry, and the call would run again.
#?(:clj
   (deftest cs-runs-each-call-once-test
     (with-each-implementation
       (let [calls (atom 0)
             ctx (atom {:ptr (Object.) :op 0})
             in-call (promise)
             bump (future @in-call (swap! ctx update :op inc))]
         (is (= :r (proj/cs ctx (fn [_] (swap! calls inc) (deliver in-call true) @bump :r) [])))
         (is (= 1 @calls) "a concurrent change of the context atom does not rerun the call")))))

(deftest ^:async context-database-path-test
  (with-each-implementation
    (with-test-context [ctx]
      (let [path (await (proj/context-database-path ctx))]
        (is (string? path) "the path of the database that the context uses")
        (is (re-find #"proj\.db$" (str path)))))))

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

#?(:clj
   (deftest ^:async coord-array-roundtrip-test
     (with-each-implementation
       (testing "set-coords!/get-coords roundtrip verification"
         (let [arr (proj/coord-array 1)]
           (proj/set-coords! arr [[42.3603222 -71.0579667 100.0 0.0]])
           (is (= [42.3603222 -71.0579667 100.0 0.0] (proj/get-coords arr 0))))))))

#?(:clj
   (deftest ^:async coord-to-coord-array-test
     (with-each-implementation
       (testing "coord->coord-array creates a 1-element coord array from a single coordinate"
         (let [ca (proj/coord->coord-array [42.3603222 -71.0579667 100.0 0.0])]
           (is (= [42.3603222 -71.0579667 100.0 0.0] (proj/get-coords ca 0))))))))

#?(:clj
   (deftest heap-calls-with-a-second-registered-context-test
     ;; cg also loads clj-gdal, and clj-gdal registers a second WasmContext.
     ;; Then an unbound heap call has no single default context.
     (with-each-implementation
       (let [k ::second-library]
         (nw/create-wasm-context! k)
         (try
           (let [ca  (proj/coord->coord-array [42.3603222 -71.0579667 100.0 0.0])
                 [x] (proj/get-coords ca 0)]
             (is (< (Math/abs (- x 42.3603222)) 0.0001) "a coord array round trip"))
           (is (seq (proj/proj-get-authorities-from-database)))
           (with-test-context [ctx]
             (let [crs (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"})
                   wkt (proj/proj-as-wkt {:context ctx :pj crs :options ["MULTILINE=NO"]})]
               (is (and (string? wkt) (not (re-find #"\n" wkt)))
                   "a string-array argument"))
             (is (seq (proj/proj-get-celestial-body-list-from-database {:context ctx :auth-name ""}))
                 "a struct list"))
           (finally (swap! nw/contexts dissoc k)))))))

#?(:clj
   (deftest string-array-arg-is-freed-test
     (with-each-implementation
       (with-test-context [ctx]
         (let [crs (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"})
               alloc nw/string-list-to-native-array
               free nw/free-on-heap
               allocated (atom [])
               freed (atom #{})
               wkt (with-redefs [nw/string-list-to-native-array
                                 (fn [s] (let [p (alloc s)]
                                           (swap! allocated conj (nw/address-as-int p))
                                           p))
                                 nw/free-on-heap
                                 (fn [p] (when p (swap! freed conj (nw/address-as-int p)))
                                   (free p))]
                     (proj/proj-as-wkt {:context ctx :pj crs :options ["MULTILINE=NO"]}))]
           (is (string? wkt))
           ;; FFI builds the array in a GC-tracked NativeBuffer.
           (when (proj/graal?)
             (is (= 1 (count @allocated)) "one block for the options")
             (is (every? @freed @allocated) "the block is freed after the call")))))))

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
        (is (every? string? authorities) "All elements should be strings")
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
  ;; export it as a PROJ string or PROJJSON and sets errno=4096
  ;; (PROJ_ERR_OTHER), which the errno-check raises. proj_as_wkt gives NULL
  ;; with no errno: a PJ with alternative operations has no one object.
  (with-each-implementation
    (with-test-context [ctx]
      (let [tx (await (proj/proj-create-crs-to-crs {:context ctx :source-crs "EPSG:4326" :target-crs "EPSG:2249"}))]
        (testing "proj-as-proj-string raises for non-exportable concatenated operation"
          (is (thrown? #?(:clj Exception :cljs js/Error)
                       (await (proj/proj-as-proj-string {:context ctx :pj tx :type 0})))))
        (testing "proj-as-wkt gives no WKT and no error for a concatenated operation"
          ;; The stale errno of an earlier call made it throw.
          (is (empty? (await (proj/proj-as-wkt {:context ctx :pj tx})))))
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

#?(:clj
   (deftest ^:async set-coord-test
     (with-each-implementation
       (when (proj/ffi?)
         (testing "set-coord! sets a single coordinate at an index"
           (let [ca (proj/coord-array 2)]
             (proj/set-coords! ca [[0 0 0 0] [0 0 0 0]])
             (proj/set-coord! ca 1 [10.0 20.0 30.0 40.0])
             (is (= [10.0 20.0 30.0 40.0] (proj/get-coords ca 1)))))))))

#?(:clj
   (deftest ^:async set-column-test
     (with-each-implementation
       (when (proj/ffi?)
         (testing "set-col! and convenience wrappers set coordinate columns"
           (let [ca (proj/coord-array 3)]
             (proj/set-coords! ca [[0 0 0 0] [0 0 0 0] [0 0 0 0]])
             (proj/set-xcol! ca [1.0 2.0 3.0])
             (proj/set-ycol! ca [4.0 5.0 6.0])
             (is (= [1.0 4.0 0.0 0.0] (proj/get-coords ca 0)))
             (is (= [2.0 5.0 0.0 0.0] (proj/get-coords ca 1)))
             (is (= [3.0 6.0 0.0 0.0] (proj/get-coords ca 2)))))))))

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
                     (str "Transformed X should be large (Web Mercator), got " x))))))))))

(deftest ^:async create-from-database-without-context-test
  (with-each-implementation
    (testing "proj-create-from-database without explicit context"
      (let [crs (await (proj/proj-create-from-database {:auth_name "EPSG" :code "4326"}))]
        (is (some? crs) "CRS should be created without explicit context")))))

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
               (doseq [i [0 1]]
                 (let [[x y] (proj/get-coords coord-array i)]
                   (is (< 775000 x 776000) (str "point " i " X, got " x))
                   (is (< 2956000 y 2957000) (str "point " i " Y, got " y)))))))))))

#?(:clj
   (defn- addr-of [p]
     (if (instance? tech.v3.datatype.ffi.Pointer p)
       (.address ^tech.v3.datatype.ffi.Pointer p)
       (nw/address-as-int p))))

;; call-native is the leaf of both backends, and the GC dispose runs on
;; another thread, which with-redefs reaches. The short arities of
;; call-native call the 4-arity through the var, so only the 4-arity logs.
;; With skip?, a logged call does not run, so a second free cannot crash
;; the JVM.
#?(:clj
   (defn- with-native-call-log
     "Run (f log) while call-native logs (entry fn-key args) for each call of
      a fn-key in ks."
     [ks entry skip? f]
     (let [log (atom [])
           orig proj/call-native]
       (with-redefs [proj/call-native
                     (fn [fn-key & more]
                       (if (and (contains? ks fn-key) (= 3 (count more)))
                         (let [r (swap! log conj (entry fn-key (second more)))]
                           (if skip? r (apply orig fn-key more)))
                         (apply orig fn-key more)))]
         (f log)))))

;; JVM-only. CLJS resource cleanup tests are in
;; resource_tracking_test.cljc.
#?(:clj
   (deftest ^:async resource-tracking-test
     (with-each-implementation
       (testing "Resources are cleaned up in stack contexts"
         (with-native-call-log
          #{:proj_destroy :proj_list_destroy :proj_context_destroy :proj_string_list_destroy
            :proj_crs_info_list_destroy :proj_unit_list_destroy}
          (fn [k _] k) false
          (fn [cleanup-called]
            (resource/stack-resource-context
             (let [ctx (proj/context-create)]
               (is (some? ctx) "Context should be created")
               (let [crs-4326 (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"})]
                 (is (some? crs-4326) "Should create CRS from database for EPSG:4326"))
               (let [crs-3857 (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "3857"})]
                 (is (some? crs-3857) "Should create CRS from database for EPSG:3857"))
               (let [authorities (proj/proj-get-authorities-from-database {:context ctx})]
                 (is (coll? authorities) "Should get authorities from database"))))

            ;; The string-list call frees its list at once, so only a PJ
            ;; destroy and a context destroy show the stack release.
            (is (every? (set @cleanup-called) [:proj_destroy :proj_context_destroy])
                (str "The stack context released its results. Called: " @cleanup-called))))))))

(deftest ^:async invalid-crs-error-test
  (with-each-implementation
    (with-test-context [ctx]
      ;; FFI throws through the errno check. The wasm has no C++ exception
      ;; catch, so on GraalVM and CLJS the PROJ exception throws or rejects.
      (testing "An invalid CRS code throws"
        (is (thrown? #?(:clj Exception :cljs js/Error)
                     (await (proj/proj-create-crs-to-crs {:context ctx
                                                          :source_crs "INVALID:9999"
                                                          :target_crs "EPSG:4326"}))))))))

;; A fake pool runs the real hooks of proj/lib. proj_context_clone gives
;; 77, and proj_create_crs_to_crs gives what `create` returns or throws. For
;; a call that asks for its errno, the fake worker gives `errno` after a NULL
;; that ran on the clone.
#?(:cljs
   (defn- clone-fake-pool [create destroyed errno]
     #js {:worker (fn [idx]
                    (js-obj "net.willcohen.proj"
                            #js {:ccall (fn [c-name _rettype _argtypes args extra]
                                          (let [r (case c-name
                                                    "proj_context_clone" 77
                                                    "proj_create_crs_to_crs" (create)
                                                    "proj_context_destroy"
                                                    (do (.push destroyed [idx (aget args 0)]) nil))]
                                            (if (.-errnoCheck extra)
                                              #js {:result r
                                                   :errno (if (and (= 0 r) (= 77 (aget args 0))) errno 0)}
                                              r)))}))}))

#?(:cljs
   (defn- ^:async isolated-create [create destroyed errno]
     (let [ctx #js {:ptr 5 :worker_idx 1 :type "proj-context"}
           r (await (.catch (proj/dispatch-call "proj_create_crs_to_crs"
                                                [ctx "EPSG:4326" "EPSG:3857" 0]
                                                #js {:pool (clone-fake-pool create destroyed errno)})
                            (fn [e] e)))]
       (await (proj/flush-pending-disposes!))
       r)))

;; A NULL from an isolated call: the errno check read the context of the
;; caller, after the clone that the call ran on was destroyed. The worker
;; reads the errno of the clone in the call.
#?(:cljs
   (deftest ^:async isolated-null-reads-the-errno-of-its-clone-test
     (let [destroyed #js []
           err (await (isolated-create (fn [] 0) destroyed 1027))]
       (is (instance? js/Error err))
       (is (re-find #"PROJ error 1027" (str (and err (.-message err)))))
       (is (= [[1 77]] (vec destroyed)) "the clone is destroyed after the errno read"))))

#?(:cljs
   (deftest ^:async isolated-call-destroys-an-unowned-clone-test
     (let [destroyed #js []
           pj (await (isolated-create (fn [] 1234) destroyed 0))]
       (is (= 77 (.-_ephemeral_context_ptr pj)) "a PJ owns its clone")
       (is (= 0 (.-length destroyed))))
     (let [destroyed #js []]
       (is (nil? (await (isolated-create (fn [] 0) destroyed 0))))
       (is (= [[1 77]] (vec destroyed)) "a NULL result destroys the clone on its worker"))
     (let [destroyed #js []
           err (await (isolated-create (fn [] (throw (js/Error. "crs not found"))) destroyed 0))]
       (is (instance? js/Error err))
       (is (= [[1 77]] (vec destroyed)) "a rejection destroys the clone on its worker"))))

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
          (is (some? (await (proj/proj-create-crs-to-crs-from-pj {:context ctx
                                                                  :source_crs source-crs
                                                                  :target_crs target-crs
                                                                  :options ["ALLOW_BALLPARK=NO"]})))
              "Should create transformation from database CRS objects with options")
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
                           (str "Y coordinate should be around 2,956,400 feet, got " y)))))))))))))

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

;; On ClojureScript the out pointer of this fn, arg 2 of 5, went to the end of
;; the args.
(deftest ^:async coordoperation-get-towgs84-values-test
  (with-each-implementation
    (with-test-context [ctx]
      (let [op (await (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "1173" :category 4}))
            r (await (proj/proj-coordoperation-get-towgs84-values
                      {:context ctx :coordoperation op :value_count 7 :emit_error_if_incompatible 0}))]
        (is (= [-8.0 160.0 176.0 0.0 0.0 0.0 0.0] (vec (prop r :values)))
            "the Helmert values of NAD27 to WGS 84 (4)")))))

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

#?(:clj
   (defn- with-destroy-log
     "Run (f log), and log each proj_destroy with its address and the backend
      that call-native takes."
     [f]
     (with-native-call-log
      #{:proj_destroy}
      (fn [_ args] {:impl (or (var-get #'proj/*backend*) @proj/implementation)
                    :addr (addr-of (first args))})
      false f)))

#?(:clj
   (defn- gc-until [pred]
     (loop [i 0]
       (when (and (< i 50) (not (pred)))
         (System/gc)
         (Thread/sleep 50)
         (recur (inc i))))))

#?(:clj
   (deftest gc-releases-an-unreferenced-pj-test
     (with-each-implementation
       (with-test-context [ctx]
         (with-destroy-log
           (fn [log]
             (let [held (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"})]
               (dotimes [_ 20]
                 (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "3857"}))
               (gc-until #(seq @log))
               (is (seq @log) "the GC released an unreferenced PJ")
               (is (string? (proj/proj-as-wkt {:context ctx :pj held}))
                   "a held PJ stays usable after GC"))))))))

#?(:clj
   (deftest explicit-release-then-gc-frees-once-test
     (with-each-implementation
       (with-test-context [ctx]
         (with-destroy-log
           (fn [log]
             ;; The canaries go first and stay unreleased, so no address
             ;; can repeat after the explicit release: nothing allocates
             ;; then. A canary destroy shows that the GC ran.
             (dotimes [_ 20]
               (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "3857"}))
             (let [n ((fn []
                        (let [pj (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"})]
                          (is (true? (proj/release-tracked! pj "proj_destroy")))
                          (is (false? (proj/release-tracked! pj "proj_destroy")))
                          (count @log))))
                   addr (:addr (nth @log (dec n)))]
               (gc-until #(> (count @log) n))
               (is (> (count @log) n) "the GC released the canaries")
               (is (not-any? #{addr} (map :addr (drop n @log)))
                   "the GC did not free the released PJ again"))))))))

;; The public destroy fns and the GC release share one release, so a PJ or a
;; context that the caller destroys is not freed again by the GC. The log
;; skips the real destroys, so a second free cannot crash the JVM.
#?(:clj
   (deftest explicit-destroy-then-gc-frees-once-test
     (with-each-implementation
       (with-native-call-log
        #{:proj_destroy :proj_context_destroy} (fn [k args] [k (addr-of (first args))]) true
        (fn [log]
          (let [n-canaries 20
                [c p] ((fn []
                         (let [ctx (proj/context-create)
                               pj (proj/proj-create-from-database
                                   {:context ctx :auth_name "EPSG" :code "4326"})]
                           (dotimes [_ n-canaries]
                             (proj/proj-create-from-database
                              {:context ctx :auth_name "EPSG" :code "3857"}))
                           (proj/proj-destroy {:pj pj})
                           (proj/proj-context-destroy {:context ctx})
                           [(addr-of (proj/context-ptr ctx)) (addr-of pj)])))
                n-of (fn [k a] (count (filter #{[k a]} @log)))]
            ;; The canaries hold the context until the GC frees them.
            (gc-until #(> (count (filter (comp #{:proj_destroy} first) @log)) n-canaries))
            (dotimes [_ 10] (System/gc) (Thread/sleep 50))
            (is (> (count @log) n-canaries) "the GC released the canaries")
            (is (= 1 (n-of :proj_destroy p)) "the PJ was freed once")
            (is (= 1 (n-of :proj_context_destroy c)) "the context was freed once")))))))

;; PROJ allocates a string list for the caller, so each string-list call
;; frees it after the decode.
#?(:clj
   (deftest string-list-result-is-freed-test
     (with-each-implementation
       (with-test-context [ctx]
         (with-native-call-log
          #{:proj_string_list_destroy} (constantly 1) false
          (fn [freed]
            (is (seq (proj/proj-get-authorities-from-database {:context ctx})))
            (is (= 1 (count @freed)) "the list was freed once")))))))

;; An options arg takes nil, which PROJ reads as no options.
#?(:clj
   (deftest options-arg-takes-nil-test
     (with-each-implementation
       (with-test-context [ctx]
         (let [pj (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "4326"})]
           (is (seq (proj/proj-context-get-database-structure {:context ctx})))
           (is (string? (proj/proj-suggests-code-for
                         {:context ctx :object pj :authority "HOBU" :numeric_code 1}))))))))

;; PROJ allocates the code string for the caller, so the call frees it after
;; the read.
#?(:clj
   (deftest suggests-code-for-frees-its-result-test
     (with-each-implementation
       (with-test-context [ctx]
         (let [pj (proj/proj-create {:context ctx :definition "+proj=longlat +ellps=GRS80 +no_defs +type=crs"})]
           (with-native-call-log
            #{:proj_string_destroy} (constantly 1) false
            (fn [freed]
              (is (string? (proj/proj-suggests-code-for {:context ctx :object pj :authority "HOBU" :numeric_code 1})))
              (is (= 1 (count @freed)) "the code string was freed once"))))))))

#?(:clj
   (deftest gc-release-after-a-backend-switch-test
     (with-each-implementation
       (let [made-on @proj/implementation
             other (if (= :graal made-on) :ffi :graal)]
         (with-destroy-log
           (fn [log]
             (with-test-context [ctx]
               (dotimes [_ 20]
                 (proj/proj-create-from-database {:context ctx :auth_name "EPSG" :code "3857"})))
             (case other :ffi (proj/force-ffi!) :graal (proj/force-graal!))
             (proj/init!)
             ;; A host with no native lib cannot switch to FFI.
             (when (= other @proj/implementation)
               (gc-until (constantly false))
               (is (and (seq @log) (every? #(= made-on (:impl %)) @log))
                   "a result frees only through the backend that made it"))))))))

;; PROJ reads the context of a PJ when it frees the PJ: ~NetworkFile calls
;; the network callbacks of the context. The GC must free each PJ before its
;; context. The log skips the real destroys, so a wrong order cannot crash.
#?(:clj
   (deftest gc-frees-a-pj-before-its-context-test
     (with-each-implementation
       (with-native-call-log
        #{:proj_destroy :proj_context_destroy} (fn [k args] [k (addr-of (first args))]) true
        (fn [log]
          (let [pairs ((fn []
                         (vec (for [_ (range 10)]
                                (let [ctx (proj/context-create)
                                      pj (proj/proj-create-from-database
                                          {:context ctx :auth_name "EPSG" :code "4326"})]
                                  [(addr-of (proj/context-ptr ctx)) (addr-of pj)])))))
                idx (fn [k a] (first (keep-indexed (fn [i [fk x]] (when (and (= k fk) (= a x)) i)) @log)))]
            (gc-until #(every? (fn [[c _]] (idx :proj_context_destroy c)) pairs))
            (is (every? (fn [[c _]] (idx :proj_context_destroy c)) pairs)
                "the GC released each context")
            (is (every? (fn [[c p]] (let [ci (idx :proj_context_destroy c)
                                          pi (idx :proj_destroy p)]
                                      (or (nil? ci) (and pi (< pi ci)))))
                        pairs)
                "each PJ was freed before its context")))))))

;; A pool worker whose PROJ boot fails closes the polyglot Context it made.
#?(:clj
   (deftest failed-pooled-boot-closes-its-context-test
     (let [ctx (org.graalvm.polyglot.Context/create (into-array String ["js"]))]
       (with-redefs [nw/new-polyglot-context! (constantly ctx)
                     nw/bootstrap-graal-module! (fn [& _] (throw (ex-info "boot failed" {})))]
         (is (thrown? clojure.lang.ExceptionInfo (wasm/bootstrap-pooled-context!))))
       (is (thrown? IllegalStateException (.eval ctx "js" "1"))
           "the failed boot closed its Context"))))

#?(:clj
   (deftest missing-resource-names-its-path-test
     (let [e (try (#'wasm/read-resource-bytes "wasm/no-such-file.bin")
                  nil
                  (catch Exception e e))]
       (is (instance? clojure.lang.ExceptionInfo e))
       (is (re-find #"wasm/no-such-file.bin" (str (ex-message e)))))))

;; CLJS runner footer. The teardown must call proj.shutdown: live
;; Worker_threads keep the Node event loop alive, and the bb task
;; then hangs on a green run.
;;
;; shutdown! is a named top-level ^:async defn because squint drops
;; ^:async from inline fns in argument position.
#?(:cljs (defn ^:async shutdown! [] (await (.shutdown proj))))

#?(:cljs (run_tests_and_exit_BANG_ shutdown!))
