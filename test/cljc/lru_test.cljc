;; Copyright (c) 2024, 2025, 2026 Will Cohen
;;
;; Part of clj-proj, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT
;;
;; Bounded-LRU acceptance suite for the helpers in
;; clj-native/pool.cljc. Synthetic library-key, owners, and dispose
;; fns. No PROJ or native handles.

(ns lru-test
  #?(:cljs (:require [cljs.test :as t :refer [deftest is]]
                     ;; The flat direct dep at test/cljc/node_modules, so its
                     ;; squint-cljs peer resolves to the same instance the
                     ;; test files use. Not the sibling clj-native checkout.
                     ["ffi-wasm/pool"
                      :refer [register_library_context_BANG_
                              register_handle_BANG_
                              ref_handle_BANG_
                              unref_handle_BANG_
                              evict_oldest_BANG_
                              bounded_create_handle_BANG_
                              get_pool_stats
                              reset_library_context_BANG_]]
                     ["./dist/test_runner.mjs"
                      :refer [run_tests_and_exit_BANG_]])))

#?(:cljs
   (do

     (defn ^:async sleep [ms]
       (js/Promise. (fn [resolve _reject] (js/setTimeout resolve ms))))

     (deftest bound-caps-live-count-and-increments-evictions-counter
       (let [lib "lru-test-bound"]
    ;; min_age_ms 0 makes every entry evictable immediately.
         (register_library_context_BANG_ lib #js {:max_live_ctxs 8 :min_age_ms 0})
         (let [releases #js {:fired 0}
               release-fn (fn [] (set! (.-fired releases) (inc (.-fired releases))))
               n 100
               peak-live (atom 0)]
           (dotimes [i n]
             (let [ctx-id (str "synthetic-" i)
                   owner #js {:ctxId ctx-id}]
               (bounded_create_handle_BANG_ lib
                                        (fn [] (register_handle_BANG_ lib ctx-id 0 release-fn owner)))
               (let [stats (get_pool_stats lib)]
                 (when (> (.-live stats) @peak-live)
                   (reset! peak-live (.-live stats))))))
           (let [stats (get_pool_stats lib)]
             (is (= 8 (.-live stats)) "live count parks at the bound")
             (is (= 8 @peak-live)    "live count never exceeded the bound")
             (is (= (- n 8) (.-evictions stats)) "evictions = creates beyond bound")
             (is (= 0 (.-blocks stats)) "no blocks under min-age 0")
             (is (= 8 (.-max_live_ctxs stats)))
             (is (= 0 (.-min_age_ms stats)))
             (is (= (- n 8) (.-fired releases)) "release fired once per evicted entry")))
         (reset_library_context_BANG_ lib)))

     (deftest refcount-prevents-eviction-EvictionGuard
       (let [lib "lru-test-refcount"]
         (register_library_context_BANG_ lib #js {:max_live_ctxs 4 :min_age_ms 0})
         (let [release-log #js []
               release-fn (fn [id] (fn [] (.push release-log id)))]
           (dotimes [i 4]
             (let [ctx-id (str "pinned-" i)]
               (register_handle_BANG_ lib ctx-id 0 (release-fn ctx-id) #js {:ctxId ctx-id})))
           (dotimes [i 4]
             (ref_handle_BANG_ lib (str "pinned-" i)))

           (let [result (evict_oldest_BANG_ lib)]
             (is (= "none-evictable" result) "evict refuses with all refcounted")
             (is (= 0 (.-length release-log)) "no release fired"))

           (unref_handle_BANG_ lib "pinned-2")
           (let [result2 (evict_oldest_BANG_ lib)]
             (is (= "evicted" result2))
             (is (= 1 (.-length release-log)))
             (is (= "pinned-2" (aget release-log 0))
                 "only the unrefcounted entry released")))
         (reset_library_context_BANG_ lib)))

     (deftest ^:async AgeGate-skips-entries-within-min-age-ms
       (let [lib "lru-test-age"]
         (register_library_context_BANG_ lib #js {:max_live_ctxs 2 :min_age_ms 60000})
         (let [release-log #js []
               release-fn (fn [id] (fn [] (.push release-log id)))]
           (register_handle_BANG_ lib "fresh-a" 0 (release-fn "fresh-a") #js {:id "a"})
           (register_handle_BANG_ lib "fresh-b" 0 (release-fn "fresh-b") #js {:id "b"})

      ;; The two entries are newer than the 60 s min-age gate.
           (let [result (evict_oldest_BANG_ lib)]
             (is (= "none-evictable" result) "fresh entries protected by the min-age gate")
             (is (= 0 (.-length release-log))))

           (let [threw (atom nil)]
             (try
               (bounded_create_handle_BANG_
                lib
                (fn [] (register_handle_BANG_ lib "overflow" 0 (release-fn "overflow") #js {:id "c"})))
               (catch :default e (reset! threw e)))
             (is (some? @threw) "create at bound with no evictable entry must throw"))

           (let [stats (get_pool_stats lib)]
             (is (= 2 (.-live stats))   "overflow create did not register")
             (is (= 1 (.-blocks stats)) "block counter advanced")))
         (reset_library_context_BANG_ lib)))

     (deftest unset-max-live-ctxs-no-bound-enforced
       (let [lib "lru-test-unbounded"]
         (register_library_context_BANG_ lib)
         (dotimes [i 50]
           (let [ctx-id (str "free-" i)]
             (bounded_create_handle_BANG_
              lib
              (fn [] (register_handle_BANG_ lib ctx-id 0 (fn [] nil) #js {:ctxId ctx-id})))))
         (let [stats (get_pool_stats lib)]
           (is (= 50 (.-live stats)) "no eviction when max-live-ctxs is unset")
           (is (= 0 (.-evictions stats)))
           (is (= 0 (.-blocks stats)))
           (is (nil? (.-max_live_ctxs stats))))
         (reset_library_context_BANG_ lib)))

     ;; The footer must always exit. An earlier footer exited only on
     ;; failure, and a green run then hung Node.
     (run_tests_and_exit_BANG_)))
