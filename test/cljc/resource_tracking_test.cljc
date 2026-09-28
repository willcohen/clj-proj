;; Copyright (c) 2024, 2025, 2026 Will Cohen
;;
;; Part of clj-proj, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT
;;
;; The authoritative resource-tracking suite.
;;
;; The race under test: V8 GC, under memory pressure from earlier
;; tests, fires many FinalizationRegistry callbacks at once during an
;; unrelated ccall. Each callback enqueues a worker-call destroy.
;; Rejected destroy Promises then surface in node:test as unrelated
;; failures.
;;
;; Disposers return worker-call Promises. clj-native's pool collects
;; them in pending_dispose_promises, and flushPendingDisposes settles
;; and clears them, which gives test isolation.

(ns resource-tracking-test
  #?(:cljs (:require [cljs.test :as t :refer [deftest is]]
                     ["./dist/test_runner.mjs"
                      :refer [run_tests_and_exit_BANG_]])))

#?(:cljs
   (do

     (def state (atom {:proj nil}))

     (defn ^:async init-once!
       "Load and init proj once, and return it."
       []
       (when (nil? (:proj @state))
         (let [proj-mod (await (js/import "../../src/cljc/net/willcohen/proj/dist/proj.mjs"))]
           (swap! state assoc :proj proj-mod)
           (await (.init proj-mod))))
       (:proj @state))

     (defn ^:async shutdown-once! []
       (when-let [proj (:proj @state)]
         (when (.-shutdown proj)
           (await (.shutdown proj)))))

     (defn ^:async check-async-disposer
       "Make a PJ with (create proj ctx), dispose it and its context, and
        check that the flush settles their destroy Promises."
       [label create]
       (let [proj (await (init-once!))]
         (await (.flushPendingDisposes proj))
         (let [ctx (await (.contextCreate proj))
               crs (await (create proj ctx))]
           (is (some? crs) (str label " should return a handle"))
           (is (= "function" (js* "typeof ~{}" (aget crs (.-dispose js/Symbol))))
               (str label " handle should expose Symbol.dispose"))
           ((aget crs (.-dispose js/Symbol)))
           ((aget ctx (.-dispose js/Symbol)))
           (let [settled (await (.flushPendingDisposes proj))]
             (is (pos? (.-length settled))
                 (str label " and context disposes should produce pending "
                      "Promises; flush returned " (.-length settled)))))))

     (deftest ^:async clj-proj-projCreateCrsToCrs-registers-an-async-disposer
       (await (check-async-disposer
               "projCreateCrsToCrs"
               (fn [proj ctx]
                 (.projCreateCrsToCrs proj #js {:context ctx :source_crs "EPSG:4326" :target_crs "EPSG:3857"})))))

     (deftest ^:async clj-proj-projCreateFromDatabase-registers-an-async-disposer
       (await (check-async-disposer
               "projCreateFromDatabase"
               (fn [proj ctx]
                 (.projCreateFromDatabase proj #js {:context ctx :auth_name "EPSG" :code "4326"})))))

     (deftest ^:async clj-proj-flush-pending-disposes-clears-the-atom
       (let [proj (await (init-once!))]
         (await (.flushPendingDisposes proj))
         (let [ctx (await (.contextCreate proj))]
           ((aget ctx (.-dispose js/Symbol))))
         (let [first-flush  (await (.flushPendingDisposes proj))
               second-flush (await (.flushPendingDisposes proj))]
           (is (pos? (.-length first-flush))
               (str "first flush should drain at least one pending Promise; "
                    "got " (.-length first-flush)))
           (is (zero? (.-length second-flush))
               (str "second flush should be empty (atom cleared); "
                    "got " (.-length second-flush))))))

     (deftest ^:async clj-proj-bulk-disposes-under-GC-pressure-do-not-break-workerQueue
  ;; Regression test for the bulk-GC dispose race in the header.
  ;; Symbol.dispose replaces FR-triggered GC here, because FR coverage
  ;; for PJ handles is still gated on worker-mutex behavior in
  ;; proj.cljc. The dispose path still reaches worker-call, so the
  ;; workerQueue sees the same bulk-enqueue pattern.
       (let [proj (await (init-once!))
             n    10
             ctxs (await (js/Promise.all
                              (.from js/Array #js {:length n}
                                     (fn [_ _i] (.contextCreate proj)))))
             crses (await (js/Promise.all
                               (.from js/Array #js {:length n}
                                      (fn [_ i]
                                        (.projCreateCrsToCrs proj
                                          #js {:context    (aget ctxs i)
                                               :source_crs "EPSG:4326"
                                               :target_crs "EPSG:3857"})))))]
         (await (.flushPendingDisposes proj))
         (doseq [i (range n)]
           ((aget (aget crses i) (.-dispose js/Symbol)))
           ((aget (aget ctxs i)  (.-dispose js/Symbol))))
         (let [settled (await (.flushPendingDisposes proj))]
           (is (>= (.-length settled) (* 2 n))
               (str "bulk dispose should produce >= " (* 2 n)
                    " pending Promises; got " (.-length settled))))
         (let [post-ctx (await (.contextCreate proj))]
           (is (some? post-ctx)
               "workerQueue should still serve new contextCreate after bulk dispose")
           ((aget post-ctx (.-dispose js/Symbol)))
           (await (.flushPendingDisposes proj)))))

     ;; Ctx-id sequences restart for each pool generation, so a stale
     ;; destroy from generation N would hit a live context of
     ;; generation N+1. wasm/live-pool? drops it. A dropped dispose
     ;; adds no Promise, so a flush length of 0 is the observable
     ;; result.
     (deftest ^:async clj-proj-stale-dispose-from-a-previous-pool-generation-is-dropped
       (let [proj (await (init-once!))]
         (await (.flushPendingDisposes proj))
         (let [ctx1 (await (.contextCreate proj))]
           (await (.shutdown proj))
           (await (.init proj))
           (let [ctx2 (await (.contextCreate proj))]
             ((aget ctx1 (.-dispose js/Symbol)))
             (let [settled (await (.flushPendingDisposes proj))]
               (is (zero? (.-length settled))
                   (str "stale dispose crossed pool generations; flush "
                        "captured " (.-length settled) " Promise(s)")))
             (let [crs (await (.projCreateCrsToCrs proj
                                                   #js {:context    ctx2
                                                        :source_crs "EPSG:4326"
                                                        :target_crs "EPSG:2249"}))]
               (is (some? crs)
                   "generation-N+1 context unusable after stale dispose")
               ((aget crs (.-dispose js/Symbol))))
             ((aget ctx2 (.-dispose js/Symbol)))
             (await (.flushPendingDisposes proj))))))

     ;; Each worker numbers its contexts from 1, so two workers hand out
     ;; one ctx-id. Disposing a must release the worker claim of a. The
     ;; next context then goes to the worker of a, the least loaded.
     (deftest ^:async clj-proj-one-ctx-id-on-two-workers-keeps-two-claims
       (let [proj (await (init-once!))]
         (await (.shutdown proj))
         (await (.init proj {:workers 2}))
         (let [a (await (.contextCreate proj))
               b (await (.contextCreate proj))]
           (is (= [0 1] [(.-worker_idx a) (.-worker_idx b)]))
           (is (= (.-ctx_id a) (.-ctx_id b)) "one ctx-id on each worker")
           ((aget a (.-dispose js/Symbol)))
           (await (.flushPendingDisposes proj))
           (let [c (await (.contextCreate proj))]
             (is (= 0 (.-worker_idx c)) "the dispose of a released the claim of a")
             ((aget b (.-dispose js/Symbol)))
             ((aget c (.-dispose js/Symbol)))
             (await (.flushPendingDisposes proj))))
         (await (.shutdown proj))
         (await (.init proj))))

     (defn ^:async fresh-pool!
       "Shut the pool down and start it again with init opts `opts`."
       [proj opts]
       (await (.shutdown proj))
       (await (.init proj opts)))

     (defn- dispatches
       "The count of DISPATCH-RESOLVE trace lines in `lines` for C fn `c-fn`
        on worker `worker`."
       [lines c-fn worker]
       (count (filter (fn [l] (and (.includes l "DISPATCH-RESOLVE")
                                   (.includes l (str "c-fn=" c-fn " "))
                                   (.includes l (str "worker-idx=" worker))))
                      lines)))

     (defn ^:async traced
       "The console lines that async fn `f` logs. The trace lines need an
        init with :debug-level."
       [f]
       (let [lines #js []
             log (.-log js/console)]
         (set! (.-log js/console) (fn [& xs] (.push lines (.join (into-array xs) " "))))
         (try
           (await (f))
           (finally (set! (.-log js/console) log)))
         lines))

     (def trace-dispatch {:debug-level "debug" :debug-categories ["dispatch"]})

     ;; An explicit projDestroy freed the PJ on its worker, but its handle
     ;; stayed registered. Its Symbol.dispose, the FinalizationRegistry or an
     ;; LRU eviction then freed the PJ a second time.
     (deftest ^:async clj-proj-explicit-projDestroy-then-dispose-frees-once
       (let [proj (await (init-once!))]
         (await (fresh-pool! proj trace-dispatch))
         (let [ctx (await (.contextCreate proj))
               crs (await (.projCreateFromDatabase proj #js {:context ctx :auth_name "EPSG" :code "4326"}))
               live-before (.-total (.getPoolDetail proj))
               _ (await (.projDestroy proj #js {:pj crs}))
               released? (= (dec live-before) (.-total (.getPoolDetail proj)))]
           (is released? "the explicit destroy released the handle")
           ;; Without the release, a second destroy frees freed memory on the
           ;; worker, and a context dispose waits for the PJ forever.
           (when released?
             (await (.flushPendingDisposes proj))
             (let [lines (await (traced (fn ^:async dispose-again! []
                                          ((aget crs (.-dispose js/Symbol)))
                                          (await (.flushPendingDisposes proj)))))]
               (is (= 0 (dispatches lines "proj_destroy" (.-worker_idx crs)))
                   "a later dispose frees nothing"))
             ((aget ctx (.-dispose js/Symbol)))
             (await (.flushPendingDisposes proj))))
         (await (fresh-pool! proj {:debug-level "off"}))))

     ;; An explicit projContextDestroy freed the context on its worker, but
     ;; kept its worker claim, and its Symbol.dispose freed it a second time.
     (deftest ^:async clj-proj-explicit-projContextDestroy-releases-the-claim-once
       (let [proj (await (init-once!))]
         (await (fresh-pool! proj {:workers 2}))
         (let [a (await (.contextCreate proj))
               b (await (.contextCreate proj))
               c (await (.contextCreate proj))]
           (is (= [0 1 0] (mapv (fn [x] (.-worker_idx x)) [a b c])))
           (await (.projContextDestroy proj #js {:context a}))
           (await (.projContextDestroy proj #js {:context c}))
           ;; The pool counts a queued destroy as load on its worker.
           (await (.flushPendingDisposes proj))
           (let [d (await (.contextCreate proj))]
             (is (= 0 (.-worker_idx d)) "the destroys of a and c released their claims")
             (when (= 0 (.-worker_idx d))
               ((aget a (.-dispose js/Symbol)))
               (is (zero? (.-length (await (.flushPendingDisposes proj))))
                   "a later dispose frees nothing"))
             ((aget b (.-dispose js/Symbol)))
             ((aget d (.-dispose js/Symbol)))
             (await (.flushPendingDisposes proj))))
         (await (fresh-pool! proj nil))))

     ;; init returned the init promise and dropped its .then chain, so a
     ;; failed init left that chain rejected with no handler. Node then exits
     ;; with ERR_UNHANDLED_REJECTION, although the caller caught the error.
     (deftest ^:async clj-proj-failed-init-leaves-no-unhandled-rejection
       (let [proj (await (init-once!))
             unhandled #js []
             on-unhandled (fn [reason] (.push unhandled reason))]
         (await (.shutdown proj))
         (.on js/process "unhandledRejection" on-unhandled)
         (try
           (is (= "rejected" (await (-> (.init proj {:workers 0})
                                        (.then (fn [_] "resolved") (fn [_] "rejected"))))))
           ;; Node reports an unhandled rejection after the microtasks drain.
           (await (js/Promise. (fn [resolve] (js/setTimeout resolve 50))))
           (finally (.off js/process "unhandledRejection" on-unhandled)))
         (is (zero? (.-length unhandled))
             (str "unhandled rejections: " (.join (.map unhandled (fn [r] (str r))) "; ")))
         (await (fresh-pool! proj nil))))

     ;; contextSetEnableNetwork and contextSetDatabasePath passed the bare
     ;; pointer, so the call for a context on worker 1 ran on worker 0 with
     ;; the pointer of worker 1.
     (deftest ^:async clj-proj-context-setters-run-on-the-worker-of-the-context
       (let [proj (await (init-once!))]
         (await (fresh-pool! proj {:workers 2}))
         (let [c0 (await (.contextCreate proj))
               c1 (await (.contextCreate proj {:worker 1}))]
           (is (= [0 1] [(.-worker_idx c0) (.-worker_idx c1)]))
           (await (.contextSetEnableNetwork proj c1 false))
           (is (= 0 (await (.projContextIsNetworkEnabled proj #js {:context c1})))
               "the network of the context on worker 1 is off")
           (is (= 1 (await (.projContextIsNetworkEnabled proj #js {:context c0})))
               "the context on worker 0 did not change")
           (await (.contextSetDatabasePath proj c1 "/proj/../proj/proj.db"))
           (is (= "/proj/../proj/proj.db" (await (.contextDatabasePath proj c1))))
           (is (= "/proj/proj.db" (await (.contextDatabasePath proj c0))))
           ((aget c0 (.-dispose js/Symbol)))
           ((aget c1 (.-dispose js/Symbol)))
           (await (.flushPendingDisposes proj)))
         (await (fresh-pool! proj nil))))

     ;; A call with PJ args on two workers recreates each moved PJ on the
     ;; target worker. The identity operation, its context clone and the
     ;; recreated PJ were never destroyed, so each call leaked two PJs and a
     ;; context on that worker. The call also wrote the recreated PJ into the
     ;; opts of the caller.
     (deftest ^:async clj-proj-cross-worker-args-destroy-their-copies
       (let [proj (await (init-once!))
             op (atom nil)]
         (await (fresh-pool! proj (assoc trace-dispatch :workers 2)))
         (let [c0 (await (.contextCreate proj))
               c1 (await (.contextCreate proj {:worker 1}))
               src (await (.projCreateFromDatabase proj #js {:context c0 :auth_name "EPSG" :code "4326"}))
               dst (await (.projCreateFromDatabase proj #js {:context c1 :auth_name "EPSG" :code "3857"}))
               opts #js {:context c0 :source_crs src :target_crs dst}
               lines (await (traced (fn ^:async cross-worker-call! []
                                      (reset! op (await (.projCreateCrsToCrsFromPj proj opts))))))]
           (is (= [0 1] [(.-worker_idx src) (.-worker_idx dst)]))
           (is (some? @op))
           (is (identical? dst (.-target_crs opts)) "the opts of the caller keep its PJ")
           (is (= 2 (dispatches lines "proj_destroy" 0))
               "the identity operation and the recreated PJ are destroyed on worker 0")
           (is (= 1 (dispatches lines "proj_context_destroy" 0))
               "the context clone of the identity operation is destroyed on worker 0")
           (doseq [x [@op src dst c0 c1]] ((aget x (.-dispose js/Symbol))))
           (await (.flushPendingDisposes proj)))
         (await (fresh-pool! proj {:debug-level "off"}))))

     ;; Must run last in this file. After shutdown the `proj` module
     ;; is not usable, and the teardown shutdown call is a no-op.
     (deftest ^:async clj-proj-shutdown-drains-pending-disposes-before-terminating-workers
       (let [proj (await (init-once!))]
         (await (.flushPendingDisposes proj))
         (let [ctx (await (.contextCreate proj))
               crs (await (.projCreateCrsToCrs proj
                                                  #js {:context    ctx
                                                       :source_crs "EPSG:4326"
                                                       :target_crs "EPSG:2249"}))]
           ((aget crs (.-dispose js/Symbol)))
           ((aget ctx (.-dispose js/Symbol))))
         (await (.shutdown proj))
         (let [drained (await (.flushPendingDisposes proj))]
           (is (zero? (.-length drained))
               (str "shutdown should have already drained pending disposes; "
                    "post-shutdown flush returned " (.-length drained))))))

     ;; shutdown-once! is a named top-level ^:async defn because
     ;; squint drops ^:async from inline fns in argument position.
     (run_tests_and_exit_BANG_ shutdown-once!)))
