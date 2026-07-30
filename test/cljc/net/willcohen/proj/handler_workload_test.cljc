;; Copyright (c) 2024, 2025, 2026 Will Cohen
;;
;; Part of clj-proj, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

#?(:clj
   (ns net.willcohen.proj.handler-workload-test
     "JVM smoke tests for the :proj workload-pool handler.

      Makes sure that:
        - register-handler! plus as-executor-service connect the handler.
        - init runs once for each worker thread (per-worker Context).
        - transform-batch works from inside a pool worker.
        - transform-batch throws from a non-pool thread, because the
          handler creates no fallback context.
        - destroy fires at shutdown.

      This file holds surface-contract tests only. Deeper coverage
      against full Boston parcels is in cg's integration suite."
     (:require [clojure.test :refer [deftest is testing]]
               [net.willcohen.native.workload-pool :as wp]
               [net.willcohen.proj.proj :as proj]
               [net.willcohen.proj.handler :as handler])
     (:import [java.util.concurrent Callable CyclicBarrier ExecutorService TimeUnit])))

#?(:clj
   (defn- submit-and-get [^ExecutorService exec f]
     (.get (.submit exec ^Callable f))))

;; The pooled-graal tests force the graal implementation, so they run
;; only on the lane that selects it (the same property that
;; proj_test's with-each-implementation reads). They sit at the end of
;; this file so the unforced tests above keep their lane's default
;; implementation.
#?(:clj
   (defn- graal-lane? []
     (= "graal" (System/getProperty "net.willcohen.proj.proj-test.implementation"))))

#?(:clj
   (deftest current-context-throws-off-pool
     (testing "transform-batch outside a pool worker throws"
       (is (thrown? Exception
             (proj/transform-batch "EPSG:4326" "EPSG:2249"
                                   (proj/coord-array 1)))))))

#?(:clj
   (deftest handler-init-runs-once-per-thread
     (let [registry (wp/init-workload-pool! {:size 2})]
       (try
         (wp/register-handler! registry :compute :proj (handler/spec))
         (let [exec (wp/as-executor-service registry :compute)
               thread-set (atom #{})
               state-ids (atom #{})
               ;; Eight tasks make the pool start the two worker
               ;; threads.
               tasks (mapv (fn [_]
                             (fn []
                               (let [state (wp/current-context :proj)]
                                 (swap! thread-set conj (.getName (Thread/currentThread)))
                                 (swap! state-ids conj (System/identityHashCode state))
                                 :ok)))
                           (range 8))
               results (mapv #(submit-and-get exec %) tasks)]
           (is (= 8 (count results)))
           (is (every? #(= :ok %) results))
           (is (= 2 (count @thread-set))
               (str "expected 2 distinct threads, got " @thread-set))
           (is (= 2 (count @state-ids))
               (str "expected 2 distinct per-worker states, got " @state-ids)))
         (finally
           (wp/shutdown-pool! registry))))))

#?(:clj
   (deftest transform-batch-works-on-pool-worker
     (let [registry (wp/init-workload-pool! {:size 1})]
       (try
         (wp/register-handler! registry :compute :proj (handler/spec))
         (let [exec (wp/as-executor-service registry :compute)
               result (submit-and-get
                       exec
                       (fn []
                         (let [ca (proj/coord-array 2)]
                           (proj/set-coords! ca [[-71.0589 42.3601 0 0]
                                                 [-73.9857 40.7484 0 0]])
                           (proj/transform-batch "EPSG:4326" "EPSG:2249" ca)
                           [(proj/get-coords ca 0)
                            (proj/get-coords ca 1)])))]
           ;; EPSG:2249 is NAD83 / Massachusetts Mainland in US survey
           ;; feet. Boston lands near X 775k-780k, Y 2960k-2970k.
           ;; Exact values vary across PROJ versions, so the bounds
           ;; only assert magnitude.
           (let [[bx by _ _] (first result)
                 [_nx _ny _ _] (second result)]
             (is (number? bx))
             (is (number? by))
             (is (< 100000 bx 2000000)
                 (str "Boston projected X out of range: " bx))
             (is (< 100000 by 5000000)
                 (str "Boston projected Y out of range: " by))))
         (finally
           (wp/shutdown-pool! registry))))))

#?(:clj
   (deftest transform-batch-reuses-cached-transformer
     (let [registry (wp/init-workload-pool! {:size 1})]
       (try
         (wp/register-handler! registry :compute :proj (handler/spec))
         (let [exec (wp/as-executor-service registry :compute)
               [size-after-first size-after-second ctx-id-1 ctx-id-2]
               (submit-and-get
                exec
                (fn []
                  (let [ca (proj/coord-array 1)]
                    (proj/set-coords! ca [[-71.05 42.36 0 0]])
                    (proj/transform-batch "EPSG:4326" "EPSG:2249" ca)
                    (let [size-1 (count @(:tx-cache (wp/current-context :proj)))
                          id-1 (System/identityHashCode (:ctx (wp/current-context :proj)))]
                      ;; proj_trans_array mutates in place, so set the
                      ;; coords again.
                      (proj/set-coords! ca [[-71.05 42.36 0 0]])
                      (proj/transform-batch "EPSG:4326" "EPSG:2249" ca)
                      [size-1
                       (count @(:tx-cache (wp/current-context :proj)))
                       id-1
                       (System/identityHashCode (:ctx (wp/current-context :proj)))]))))]
           (is (= 1 size-after-first))
           (is (= 1 size-after-second)
               (str "expected tx-cache to remain at 1 entry; got " size-after-second))
           (is (= ctx-id-1 ctx-id-2)
               "expected the same per-worker Context across calls"))
         (finally
           (wp/shutdown-pool! registry))))))

#?(:clj
   (deftest pooled-workers-get-distinct-polyglot-contexts
     (if-not (graal-lane?)
       (is true "graal lane only")
       (do
         (proj/force-graal!)
         (proj/init!)
         (let [registry (wp/init-workload-pool! {:size 2})]
           (try
             (wp/register-handler! registry :compute :proj (handler/spec))
             (let [^ExecutorService exec (wp/as-executor-service registry :compute)
                   barrier (CyclicBarrier. 2)
                   task (fn []
                          (let [{:keys [pctx]} (wp/current-context :proj)]
                            ;; The barrier holds both workers in flight at
                            ;; once, so the two tasks cannot ride one
                            ;; thread.
                            (.await barrier 60 TimeUnit/SECONDS)
                            (let [ca (proj/coord-array 1)]
                              (proj/set-coords! ca [[-71.0589 42.3601 0 0]])
                              (proj/transform-batch "EPSG:4326" "EPSG:2249" ca)
                              {:pctx? (some? pctx)
                               :pctx-id (System/identityHashCode pctx)
                               :xy (proj/get-coords ca 0)})))
                   f1 (.submit exec ^Callable task)
                   f2 (.submit exec ^Callable task)
                   r1 (.get f1 120 TimeUnit/SECONDS)
                   r2 (.get f2 120 TimeUnit/SECONDS)]
               (is (:pctx? r1) "worker state carries a pooled polyglot Context")
               (is (:pctx? r2))
               (is (not= (:pctx-id r1) (:pctx-id r2))
                   "each pooled worker owns a distinct polyglot Context")
               (doseq [{[x y] :xy} [r1 r2]]
                 (is (< 100000 x 2000000) (str "Boston projected X out of range: " x))
                 (is (< 100000 y 5000000) (str "Boston projected Y out of range: " y))))
             (finally
               (wp/shutdown-pool! registry))))))))

#?(:clj
   (deftest graal-pool-opt-out-stays-on-the-default-context
     (if-not (graal-lane?)
       (is true "graal lane only")
       (do
         (proj/force-graal!)
         (proj/init!)
         (let [registry (wp/init-workload-pool! {:size 1})]
           (try
             (wp/register-handler! registry :compute :proj
                                   (handler/spec {:graal-pool? false}))
             (let [exec (wp/as-executor-service registry :compute)
                   result (submit-and-get
                           exec
                           (fn []
                             (let [state (wp/current-context :proj)
                                   ca (proj/coord-array 1)]
                               (proj/set-coords! ca [[-71.0589 42.3601 0 0]])
                               (proj/transform-batch "EPSG:4326" "EPSG:2249" ca)
                               {:wc (:wc state)
                                :pctx (:pctx state)
                                :xy (proj/get-coords ca 0)})))]
               (is (nil? (:wc result)) "opt-out state carries no pooled WasmContext")
               (is (nil? (:pctx result)) "opt-out state carries no pooled polyglot Context")
               (let [[x y] (:xy result)]
                 (is (< 100000 x 2000000) (str "Boston projected X out of range: " x))
                 (is (< 100000 y 5000000) (str "Boston projected Y out of range: " y))))
             (finally
               (wp/shutdown-pool! registry))))))))

#?(:clj
   (deftest pooled-context-closes-at-worker-destroy
     (if-not (graal-lane?)
       (is true "graal lane only")
       (do
         (proj/force-graal!)
         (proj/init!)
         (let [registry (wp/init-workload-pool! {:size 1})]
           (wp/register-handler! registry :compute :proj (handler/spec))
           (let [^ExecutorService exec (wp/as-executor-service registry :compute)
                 pctx (submit-and-get exec (fn [] (:pctx (wp/current-context :proj))))]
             (is (some? pctx))
             (wp/shutdown-pool! registry)
             (is (.awaitTermination exec 30 TimeUnit/SECONDS)
                 "pool termination within the timeout")
             (is (thrown? IllegalStateException
                          (.eval ^org.graalvm.polyglot.Context pctx "js" "1"))
                 "the pooled Context is closed after worker destroy")))))))
