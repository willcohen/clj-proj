;; Copyright (c) 2024, 2025, 2026 Will Cohen
;;
;; Part of clj-proj, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns net.willcohen.proj.fallback-test
  "Coverage for the FFI->GraalVM fallback in
   `net.willcohen.native.platform-state/try-init!`.

   `bb test:clj-ffi` does not reach the catch branch, because FFI
   succeeds. `bb test:clojure-graal` does not reach it, because
   force-graal skips FFI. This file is the only coverage for the
   path that runs when FFI throws at init.

   JVM-only. The .clj extension keeps the file out of the squint
   compile. cognitect.test-runner finds it through `-d test/cljc/net`
   in the deps.edn :test alias."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [net.willcohen.proj.proj :as proj]
            [net.willcohen.proj.wasm :as wasm]
            [net.willcohen.native.platform-state :as nps]))

(def ^:private wasm-resources-available?
  ;; `bb test:clj-ffi` does not build the wasm, so it can be absent.
  (some? (io/resource "wasm/proj-emscripten.js")))

(defn- save-state []
  {:impl @proj/implementation
   :force @proj/force-graal})

(defn- restore-state! [s]
  (reset! proj/implementation (:impl s))
  (reset! proj/force-graal (:force s)))

(defmacro ^:private with-clean-state
  "Save the proj/implementation and proj/force-graal atoms, reset the
   two to fresh-init values, run the body, then restore them. The
   restore is bookkeeping only. A GraalVM polyglot context loaded in
   the body persists."
  [& body]
  `(let [saved# (save-state)]
     (try
       (reset! proj/implementation nil)
       (reset! proj/force-graal false)
       ~@body
       (finally
         (restore-state! saved#)))))

(deftest fallback-leaves-system-usable
  ;; End-to-end: a throwing ffi-fn with the real wasm/init-proj, then
  ;; a real transform through the graal-routed dispatch.
  ;;
  ;; Heavy: a cold GraalVM polyglot load takes 5-30 s. A missing wasm
  ;; must fail here, not skip.
  (is wasm-resources-available?
      "wasm/proj-emscripten.js is not on the classpath, so the end-to-end fallback cannot run. Run `bb build --wasm`.")
  (when wasm-resources-available?
    (with-clean-state
      (let [throwing-ffi (fn []
                           (throw (RuntimeException.
                                   "synthetic FFI failure for fallback test")))]
        (nps/try-init! proj/implementation proj/force-graal false
                       throwing-ffi
                       wasm/init-proj)
        (is (= :graal @proj/implementation)
            "FFI throw must land on :graal")
        (testing "graal-routed coordinate transform after fallback"
          (let [ctx    (proj/context-create)
                tx     (proj/proj-create-crs-to-crs
                        {:context ctx
                         :source-crs "EPSG:4326"
                         :target-crs "EPSG:2249"})
                coords (proj/coord-array 1)]
            (is (some? ctx) "context-create returned a context")
            (is (some? tx)  "proj-create-crs-to-crs returned a transformer")
            (proj/set-coords! coords [[42.3603222 -71.0579667 0 0]])
            (let [result (proj/proj-trans-array
                          {:p tx :direction 1 :n 1 :coord coords})]
              (is (or (nil? result) (= 0 result))
                  (str "proj-trans-array result: " result)))
            (let [[x y _ _] (proj/get-coords coords 0)]
              (is (< 775000 x 776000)
                  (str "Expected ~775200 ft X via Graal fallback, got " x))
              (is (< 2956000 y 2957000)
                  (str "Expected ~2956400 ft Y via Graal fallback, got " y)))))))))
