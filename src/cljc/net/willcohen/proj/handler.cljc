;; Copyright (c) 2024, 2025, 2026 Will Cohen
;;
;; Part of clj-proj, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

#?(:clj
   (ns net.willcohen.proj.handler
     "Per-worker init/destroy for the clj-native workload-pool `:proj` handler.

      The pool consumer (typically cg's session wiring) registers this
      handler under `:compute` with `(wp/register-handler! registry
      :compute :proj (handler/spec))`. The ThreadFactory behind the
      compute slot then runs `init` once per worker thread on the first
      job, and `destroy` once per thread at pool shutdown.

      JVM: init returns the per-thread state map
      `{:ctx <context-atom> :tx-cache (atom {})}`, which
      `proj/transform-batch` reads through `wp/current-context :proj`.

      CLJS: `spec` emits {:module :args :pre-terminate}. Per-worker init
      and teardown are in proj-handler.mjs. worker-bootstrap calls its
      create export with :args, and its module-level destroy sibling at
      terminate. The host-side :pre-terminate hook flushes pending async
      disposers before the pool dies. Build :args with
      `default-init-args`."
     (:require [net.willcohen.proj.proj :as proj]
               [net.willcohen.proj.wasm :as wasm]
               [net.willcohen.native.graal-wasm :as nw]
               [clojure.tools.logging :as log])
     (:import [org.graalvm.polyglot Context]))
   :cljs
   (ns net.willcohen.proj.handler
     "Per-worker init/destroy for the clj-native workload-pool `:proj` handler. See JVM ns docstring."
     (:require ["./proj-loader.mjs" :as proj-loader]
               ["ffi-wasm/pool" :as pool])))

#?(:clj (set! *warn-on-reflection* true))

#?(:clj
   (defn init
     "Allocate the per-worker PROJ state.

      `args` options:
        :graal-pool?  On the graal implementation, give this worker its
                      own polyglot Context on the shared Engine. Default
                      true. Measured cost per worker: ~54-70 MB heap and
                      ~220-340 ms init. {:graal-pool? false} keeps every
                      worker on the shared default Context under the
                      global lock. FFI ignores it.

      Returns the per-thread state map that `transform-batch` reads
      through `wp/current-context :proj`. Pooled graal state also carries
      :wc (the worker's WasmContext) and :pctx (its polyglot Context)."
     [args]
     (proj/init!)
     (if (and (proj/graal?) (not (false? (:graal-pool? args))))
       (let [{:keys [wc pctx]} (wasm/bootstrap-pooled-context!)
             ctx (nw/with-wasm-context wc (proj/context-create {}))]
         (log/info "proj/handler: pooled polyglot Context and PROJ Context allocated on"
                   (.getName (Thread/currentThread)))
         {:ctx ctx
          :tx-cache (atom {})
          :wc wc
          :pctx pctx})
       (let [ctx (proj/context-create {})]
         (log/info "proj/handler: per-worker Context allocated on"
                   (.getName (Thread/currentThread)))
         {:ctx ctx
          :tx-cache (atom {})}))))

#?(:clj
   (defn destroy
     "Release the per-worker Context and the cached transformers.

      Runs at pool shutdown on the thread that owns the Context.
      proj_context_destroy must run on the pthread that created the
      Context, because PROJ's per-context grid cache and error state are
      pthread-local.

      Logs and drops per-handle errors, so one bad release does not stop
      the rest of the teardown. Pooled graal state: the releases run under
      the worker's WasmContext binding, and the polyglot Context closes
      last."
     [{:keys [ctx tx-cache wc pctx] :as _state}]
     (when (some? ctx)
       (try
         (let [release!
               (fn []
                 (doseq [[_ tx] @tx-cache]
                   (try
                     (proj/release-tracked! tx "proj_destroy")
                     (catch Throwable t
                       (log/warn t "proj/handler: failed to destroy cached transformer"))))
                 (reset! tx-cache {})
                 (try
                   (proj/release-tracked! (:ptr @ctx) "proj_context_destroy")
                   (catch Throwable t
                     (log/warn t "proj/handler: failed to destroy Context"))))]
           (if wc
             (nw/with-wasm-context wc (release!))
             (release!)))
         (when pctx
           (.close ^Context pctx))
         (log/info "proj/handler: per-worker Context released on"
                   (.getName (Thread/currentThread)))
         (catch Throwable t
           (log/error t "proj/handler: unexpected error during destroy"))))))

#?(:cljs
   (defn ^:async default-init-args
     "Build the per-worker init payload of the proj handler: load
      proj.db and proj.ini once on the main thread through proj-loader,
      and return the plain JS object that proj-handler's init requires.
      Workers cannot load these themselves -- each worker is its own JS
      context with no access to the page URL or the main-thread fetch
      shim. opts:

        :log-level integer (0..3) for the PROJ C-library logger.
                   Default 0."
     [opts]
     (let [resources (await (.loadProjResources proj-loader))]
       (js-obj "dbBytes"  (.-projDb resources)
               "iniBytes" (.-projIni resources)
               "logLevel" (or (:log-level opts) 0)))))

#?(:cljs
   (defn ^:async pre-terminate!
     "Joint-pool shutdown hook: flush pending async disposers, so
      worker-call destroys land before the workers die, then reset the
      PROJ library-context registration. The registry runs this before
      it terminates the pool (refer to clj-native workload-pool
      shutdown-pool!)."
     []
     (await (pool/flush-pending-disposes!))
     (pool/reset-library-context! :net.willcohen.proj)
     nil))

(defn spec
  "Return a handler spec map for
   `clj-native.workload-pool/register-handler!`.

   JVM: {:init init :destroy destroy :args args} -- the cljc fns above,
   run once per worker thread.

   CLJS: {:module url :args args :pre-terminate pre-terminate!}.
   :module resolves ./proj-handler.mjs against this module's URL, which
   is correct unbundled (the source tree) and bundled (esbuild ships
   proj-handler.mjs adjacent to dist/proj.mjs). `args` must be the
   payload from `default-init-args` -- proj-handler's init throws
   without dbBytes."
  ([] (spec nil))
  ([args]
   #?(:clj  {:init init :destroy destroy :args args}
      :cljs {:module (.-href (js/URL. "./proj-handler.mjs"
                                      (.-url js/import.meta)))
             :args args
             :pre-terminate pre-terminate!})))
