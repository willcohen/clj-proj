;; Copyright (c) 2024, 2025, 2026 Will Cohen
;;
;; Part of clj-proj, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT
;;
;; Scaffold for test/js/benchmark.test.mjs, which stays authoritative
;; for perf numbers. This file keeps the `bb test:cljs` file
;; inventory complete. Port the bodies only when perf measurement
;; moves to cljs.test.

(ns benchmark-test
  #?(:cljs (:require [cljs.test :as t :refer [deftest is]])))

#?(:cljs
   (do

     (def state (atom {:proj nil}))

     (defn ^:async init-once! []
       (when (nil? (:proj @state))
         (let [proj-mod (await (js/import "../../src/cljc/net/willcohen/proj/dist/proj.mjs"))]
           (swap! state assoc :proj proj-mod)
           (await (.init proj-mod)))))

     (defn ^:async shutdown-once! []
       (when-let [proj (:proj @state)]
         (when (.-shutdown proj) (await (.shutdown proj)))))

     (deftest ^:async benchmark-scaffold-placeholder
       ;; TODO: port the benchConcurrentCrsCreation and
       ;; benchConcurrentTransforms loops from test/js/benchmark.test.mjs.
       (await (init-once!))
       (is true "benchmark scaffold present"))

     ;; squint drops ^:async from an inline fn in argument position,
     ;; so the handler is a named top-level ^:async defn.
     (defn ^:async handle-results [results]
       (await (shutdown-once!))
       (let [fail (or (get results "fail") 0)
             err  (or (get results "error") 0)]
         (when (pos? (+ fail err))
           (.exit js/process 1))))

     (.then (js/Promise.resolve (t/run-tests)) handle-results)))
