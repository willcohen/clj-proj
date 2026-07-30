;; Copyright (c) 2024, 2025, 2026 Will Cohen
;;
;; Part of clj-proj, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

#?(:clj
   (ns net.willcohen.proj.wasm
     "PROJ-specific WASM glue. Loads the PROJ WASM module into the
      shared GraalVM Polyglot Context owned by clj-native.wasm. The
      generic per-fn dispatch engine lives in clj-native.dispatch. This
      namespace defines PROJ's extras-builder, result-wrapper, and
      context-isolator hooks, which proj.cljc puts on the library value,
      and supplies coord-array ergonomics tied to PROJ's PJ_COORD shape
      (4-double tuples)."
     (:require [clojure.java.io :as io]
               [clojure.tools.logging :as log]
               [net.willcohen.native.graal-wasm :as nw]))
   :cljs
   (ns wasm
     "Worker pool management for browser and Node.js. This namespace
      holds one private clj-native pool wiring. init-proj without :pool
      registers the proj handler spec and spawns an owned pool.
      init-proj with :pool adopts the caller's joint pool (owned?
      false). Either way the wiring latches the pass, so one pool
      serves every caller. The generic per-fn dispatch
      engine lives in clj-native.dispatch. This namespace supplies
      PROJ's extras-builder, result-wrapper, and context-isolator
      hooks, which proj.cljc puts on the library value."
     (:require ["ffi-wasm/pool" :as pool]
               ["ffi-wasm/workload-pool" :as wp]
               ["ffi-wasm/dispatch" :as dispatch]
               ["./handler.mjs" :as handler])))

#?(:clj (set! *warn-on-reflection* true))

(def ^:dynamic *runtime-log-level* nil)

#?(:clj
   (def ^:dynamic *load-grids*
     "Controls PROJ grid file load at GraalVM initialization.
  Grid load is very slow: every byte of every grid crosses the polyglot
  boundary into the JS heap.
  Set to false to skip grid load and make initialization faster."
     false))

;; Per-library WasmContext registered with clj-native. Pointerlike
;; protocol methods and heap utilities route through this. In a
;; single-library JVM, the registry's sole-entry fallback in
;; current-module finds it without a with-wasm-context wrap.
#?(:clj (defonce proj-context (nw/create-wasm-context! :net.willcohen.proj)))

;; Loaded WASM module ref. The JVM side shows the proj-context atom, so
;; existing @p reads continue to work. The cljs side keeps its own atom,
;; because clj-native's JVM-only state does not get to the cljs runtime.
#?(:clj  (def p (:module-ref proj-context))
   :cljs (defonce p (atom nil)))

;; Joint-pool state (CLJS only): one clj-native wiring for this
;; namespace. The wiring holds the workload-pool registry of the
;; current init!/shutdown! cycle plus the memo that makes the wiring
;; pass run once, so concurrent init-proj callers share one pass and
;; one pool. Only clj-native's own accessors read the wiring atoms. A
;; squint Atom from a different package instance does not support
;; deref, so the accessor is the API.
#?(:cljs
   (do
     (defonce ^:private pool-wiring (wp/make-wiring!))
     ;; Feeds the generation suffix of every PJ ctx_id. PROJ recycles heap
     ;; addresses, so ptr alone repeats in a session and cannot key
     ;; live-pjs on its own. The suffix keeps ctx_id unique for the full
     ;; life of the handle.
     (defonce ^:private pj-gen-counter (atom 0))))

#?(:cljs
   (defn current-pool
     "Return the live joint pool, or nil before init-proj and after
      shutdown!. Each call reads through the wiring with
      wp/wiring-pool."
     []
     (wp/wiring-pool pool-wiring)))

#?(:cljs
   (do
     (pool/register-cmd-args! "context_create"
                              (fn [cmd] #js [(or (:opts cmd) #js {})]))
     (pool/register-cmd-args! "context_destroy"
                              (fn [cmd] #js [(:ctxId cmd)]))
     (pool/register-cmd-args! "set_log_level"
                              (fn [cmd] #js [(:level cmd)]))
     ;; Bounded-LRU config. evict-oldest! in pool.cljc uses
     ;; :max-live-ctxs as the hard cap on the set of live PJs with no JS
     ;; owner, and :min-age-ms as the age threshold, so the pool does not
     ;; evict fresh PJs under in-flight ccalls. Default 128: bb test:node
     ;; peaks near 65, because V8 does not run GC under the light
     ;; pressure of the test suite (PJ wrappers are small), so the
     ;; WeakRef gate cannot fire on them. 128 is two times that peak,
     ;; which keeps the bound a real back-pressure ceiling. Consumers
     ;; override it through init-proj opts.
     (pool/register-library-context! :net.willcohen.proj
                                     {:max-live-ctxs 128
                                      :min-age-ms 100})))

#?(:cljs
   (defn ^:async init-workers!
     "Wire the private workload-pool registry and return the joint pool.
      opts is a Clojure map with these keys:

        :pool           caller-supplied WorkerPool ref. init-workers!
                        adopts it with owned? false, and the caller
                        keeps its lifecycle. The caller's registry must
                        register the proj handler module before it
                        spawns the pool. This registry takes only the
                        host-side :pre-terminate hook.
        :workers        :auto, integer, or 'auto' string. Default :auto.
        :log-level      integer (0..3) for the proj logger. Default 0.
        :max-live-ctxs  integer or nil. Overrides the load-time default
                        for the hard cap on live PJ contexts. nil keeps
                        the current setting. Increase it for workloads
                        with a high peak live-context count (for
                        example, heavy maplibre-proj replays).
        :min-age-ms     integer or nil. Overrides the eviction age
                        threshold (default 100), so the pool does not
                        evict fresh PJs under in-flight ccalls.
        :debug-level      :off | :error | :warn | :info | :debug | :trace
                          (or nil → off). Controls clj-native's diagnostic
                          substrate page-side AND worker-side. Distinct from
                          :log-level, which is the PROJ C-library logger.
        :debug-categories collection of category keywords/strings to allow,
                          or nil for all. See clj-native pool/set-log-config!
                          for category derivation.

      Without :pool, handler/default-init-args builds the per-worker
      init payload from proj.db and proj.ini on the main thread. Then
      init-workers! registers the full proj handler spec and spawns an
      owned pool. Returns the pool ref.

      wp/ensure-wired! latches the whole pass, so concurrent callers
      share one wiring and one pool, and a later call yields the first
      call's pool whatever opts it passes."
     [opts]
     (await
      (let [opts             (or opts {})
            caller-pool      (:pool opts)
            size             (or (:workers opts) "auto")
            log-level        (or (:log-level opts) 0)
            max-live-ctxs    (:max-live-ctxs opts)
            min-age-ms       (:min-age-ms opts)
            debug-level      (:debug-level opts)
            debug-categories (:debug-categories opts)
            ;; Pool-wide worker propagation: the registry forwards
            ;; :handler-runtime to init-pool!, which broadcasts
            ;; setLogConfig to every worker through the substrate's
            ;; reserved __setLogConfig method. The per-handler
            ;; init-args.handlerRuntime path is ALSO populated, so the
            ;; substrate is live before the per-handler init() runs. That
            ;; is necessary to catch emscripten's pre-allocated pthread
            ;; worker spawn (PTHREAD_POOL_SIZE=1 +
            ;; PTHREAD_POOL_DELAY_LOAD=1), which fires inside
            ;; loadEmscriptenModule, before the pool-wide broadcast can
            ;; get to this worker.
            handler-rt-opt   (when (some? debug-level)
                               {:level      debug-level
                                :categories debug-categories})
            register!
            (fn ^:async register-proj! [reg]
              ;; Enable the page side BEFORE pool init, so QUEUE-* / FR-*
              ;; events from the create call land in the trace.
              (when (some? debug-level)
                (pool/set-log-config!
                 {:level      debug-level
                  :categories debug-categories}))
              ;; Apply the caller's live-context-cap overrides.
              ;; ensure-library! updates only fields with a non-nil value,
              ;; so a partial map keeps the load-time default for unset
              ;; fields.
              (when (or (some? max-live-ctxs) (some? min-age-ms))
                (pool/register-library-context!
                 :net.willcohen.proj
                 (cond-> {}
                   (some? max-live-ctxs) (assoc :max-live-ctxs max-live-ctxs)
                   (some? min-age-ms)    (assoc :min-age-ms min-age-ms))))
              (if (some? caller-pool)
                ;; An adopted pool also gets the flush and the
                ;; library-context reset at shutdown!. A spec with only
                ;; :pre-terminate can register at any time.
                (wp/register-handler! reg :compute :net.willcohen.proj
                                      {:pre-terminate handler/pre-terminate!})
                (let [init-args (await (handler/default-init-args
                                        {:log-level log-level}))]
                  (when handler-rt-opt
                    (aset init-args "handlerRuntime"
                          (js-obj "logLevel"      debug-level
                                  "logCategories" (clj->js debug-categories))))
                  (wp/register-handler! reg :compute :net.willcohen.proj
                                        (handler/spec init-args)))))]
        (wp/ensure-wired!
         pool-wiring
         (if (some? caller-pool)
           {:pool caller-pool :register! register!}
           {:registry-opts (cond-> {:size size}
                             handler-rt-opt
                             (assoc :handler-runtime handler-rt-opt))
            :register! register!}))))))

#?(:cljs
   (defn ^:async worker-call
     "Dispatch a legacy cmd envelope to the proj handler, on a specific
      worker (when worker-idx is non-nil, 0 included) or on the
      least-loaded worker from worker-router's any() picker (worker-idx
      nil). The handler method is (:cmd cmd). cmd-args extracts the
      positional args.

      Single-arity: on a multi-arity defn, squint's `^:async` metadata
      marks only the outer dispatcher, and the per-arity closures stay
      non-async. esbuild then rejects the inner `await`. All callers pass
      worker-idx already, so a 2-arity-only signature is the simplest fix."
     [worker-idx cmd]
     (await (pool/worker-call (current-pool) :net.willcohen.proj (:cmd cmd) (pool/cmd-args cmd) worker-idx))))

#?(:cljs
   (def ^:private DESTROY-GATE-MAX-ITERS 16))
;; Race-recovery cap for the event-driven gate of destroy-context!. Each
;; iteration awaits a Promise that resolves when the in-flight counter
;; gets to zero. The cap bounds the pathological case where a new
;; register-handle! for the parent re-arms the counter immediately after
;; each resolution. One iteration is the steady state. 16 is generous.

#?(:cljs
   (defn ^:async destroy-context!
     "Drain children, then post context_destroy.

      Composite pool-side gate that prevents context_destroy from a
      reorder before child handle disposes:
        (C) await the handle dispose Promises held under this ctx in
            pool/pending-disposes-by-parent.
        (B) await pool/await-parent-drain!, a Promise that resolves when
            the in-flight counter for this ctx drops to zero. The counter
            increments synchronously at register-handle! and decrements
            in the wrapped disposer's `.finally()` on the release-fn
            Promise. Thus zero means that every child's proj_destroy
            worker_call resolved on its worker, and not only that the
            membership map dropped the entry.

      The bounded re-check loop guards a register/decrement race: after
      the Promise resolves, a new register-handle! for this parent can
      increment the counter again, so the loop awaits a fresh Promise.
      DESTROY-GATE-MAX-ITERS bounds runaway registration at teardown. At
      the cap, the gate gives up and posts the worker-call.

      `p` is the pool the context was CREATED on, and not (current-pool).
      A GC-fired destroy can arrive after a shutdown!/init! cycle, and
      each pool restarts its per-worker ctx-id sequence at 1, so a stale
      ctx-id routed to the current pool destroys a LIVE context of the
      new generation. Refer to live-pool?."
     [p worker-idx ctx-id]
     (let [;; Globally unique parent key. Each worker keeps its own
           ;; ctx-id sequence, so the raw number is not unique across
           ;; workers. The composite prevents cross-worker counter
           ;; aggregation in in-flight-by-parent /
           ;; gate-promises-by-parent / pending-disposes-by-parent.
           parent-key (str worker-idx ":" ctx-id)]
       (await (pool/drain-pending-disposes-for-parent! parent-key))
       (loop [iters 0]
         (let [remaining (pool/in-flight-count-for-parent parent-key)]
           (when (and (pos? remaining)
                      (< iters DESTROY-GATE-MAX-ITERS))
             (await (pool/await-parent-drain! parent-key))
             (recur (inc iters)))))
       (await (pool/worker-call p :net.willcohen.proj
                                "context_destroy"
                                (pool/cmd-args {:cmd "context_destroy" :ctxId ctx-id})
                                worker-idx)))))

#?(:cljs
   (defn live-pool?
     "True only if `p` is the pool this consumer routes to at this time.

      A GC-fired destroy must fire against the pool that created its
      handle, so every tracked handle holds that pool, and its disposer
      guards on this test rather than on active?. active? alone is not
      sufficient: V8 delivers a FinalizationRegistry callback at an
      unspecified time, which can be after a shutdown!/init! cycle
      replaced the pool. A fresh pool restarts each worker's ctx-id
      sequence at 1, and a PJ destroy carries a raw per-worker heap
      address, so a re-routed destroy frees a live handle of the new
      generation. That showed as PROJ FactoryException 4000680
      (\"Cannot find proj.db\") and 4430488 (\"Open of 8 failed\", a
      garbage database path) in the browser benchmark, from 4 workers up.

      A stale destroy is safe to drop: the terminated pool owned the
      native memory, so the memory died with its workers. An adopted
      pool that survives shutdown! keeps its identity, and the destroys
      of its handles still fire when the consumer adopts it again. That
      is correct, because its workers never died and their ctx-id
      sequences did not restart. A counter that increases on each
      shutdown would drop those destroys and cause a leak."
     [p]
     (wp/live-pool? pool-wiring p)))

#?(:cljs
   (defn ignore-pool-terminated
     "Rejection handler: drop worker-router's \"pool terminated\" and
      rethrow all else. A GC-fired destroy can still lose a race with
      shutdown!: the pool it held was live at the live-pool? guard and
      terminated before the worker-call landed. The native memory died
      with the workers, so nothing is left to free, but an unhandled
      rejection shows as a browser pageerror."
     [err]
     (if (.includes (str (and err (.-message err))) "pool terminated")
       nil
       (throw err))))

#?(:cljs
   (defn get-worker-count
     "Returns the number of workers in the pool."
     []
     (when-let [p (current-pool)]
       (pool/pool-size p))))

#?(:cljs
   (defn ^:async shutdown!
     "Stop the wiring. wp/shutdown-wiring! runs proj's :pre-terminate
      hook first. The hook flushes pending async disposers, so
      worker-call destroys land before the workers die, and then resets
      the library context. The pool terminates only when this consumer
      owns it. A caller-supplied pool stays up. shutdown-wiring! then
      clears the registry and the wiring memo, so a later init-proj
      starts fresh."
     []
     (await
      (-> (wp/shutdown-wiring! pool-wiring)
          (.then (fn [reg]
                   ;; A registry means the wiring ran its :pre-terminate
                   ;; hook, which already flushed. nil means nothing was
                   ;; wired -- init never ran, or a shutdown already ran --
                   ;; so drain pending disposers here instead.
                   (when-not reg
                     (.then (pool/flush-pending-disposes!)
                            (fn [_] nil)))))))))

#?(:cljs
   (defn create-context-on-worker
     "Create a PROJ context on a specific worker. clj-native picks the
      worker through worker-router's claim() (least-loaded over pending
      plus claims) or honors an explicit :worker opt with no claim. The
      release closure returns to the caller, so the caller can pair it
      with a JS owner for clj-native's WeakRef-based stale sweep (refer
      to track-context!). The worker's context_create handler does the
      full setup: it creates the PROJ context, sets the database path,
      enables the network, and installs the log callback. Returns a
      promise of a map with :ctx-id, :ptr, :worker-idx, and :release."
     [opts]
     (let [{:keys [idx release]} (pool/assign-worker-for-context! (current-pool) :net.willcohen.proj opts)]
       (-> (worker-call idx {:cmd "context_create"})
           (.then (fn [result]
                    {:ctx-id (.-ctxId result)
                     :ptr (.-ptr result)
                     :worker-idx idx
                     :release release}))
           (.catch (fn [err]
                     (release)
                     (throw err)))))))

#?(:cljs
   (defn track-context!
     "Add an owner-tracked entry to the :ctx-workers map of clj-native's
      library context. owner must be a JS object. clj-native wraps it in
      a WeakRef and sweeps stale entries (collected owners) on every
      track/assign call. The sweep fires release-fn synchronously and
      does not wait for the FinalizationRegistry callback queue to
      drain."
     [ctx-id worker-idx release-fn owner]
     (pool/track-context! :net.willcohen.proj ctx-id worker-idx release-fn owner)))

#?(:cljs
   (defn untrack-context!
     "Drain the worker-router claim and remove the ctx-id mapping. The
      context destroy-fn calls this, so the pool's claim_count releases
      synchronously when destroy fires, before the destroy promise of the
      worker-call settles."
     [ctx-id]
     (pool/untrack-context! :net.willcohen.proj ctx-id)))

#?(:clj
   (defn- read-resource-bytes [path]
     (with-open [in (io/input-stream (io/resource path))]
       (when-not in (throw (ex-info (str "Could not find resource on classpath: " path) {:path path})))
       (.readAllBytes in))))

#?(:clj
   (defonce ^:private proj-resources
     ;; Host-side URLs and byte arrays, context-agnostic; loaded once per
     ;; JVM and shared by the default bootstrap and every pooled one. The
     ;; js-bytes encoding stays per Context: a Uint8Array is unusable
     ;; outside the Context that built it.
     (delay
       (let [proj-js-url   (io/resource "wasm/proj-emscripten.js")
             loader-js-url (io/resource "wasm/proj-loader.mjs")]
         (when (or (nil? proj-js-url) (nil? loader-js-url))
           (throw (ex-info "Could not find proj-emscripten JS files on classpath."
                           {:proj-js-url proj-js-url :loader-js-url loader-js-url})))
         (log/info "Loading PROJ binary resources (WASM, proj.db)...")
         (let [grid-files (if *load-grids*
                            (let [_ (log/info "Loading PROJ grid files from resources...")
                                  grid-dir-url (io/resource "grids")]
                              (if grid-dir-url
                                (let [dir (io/file (.toURI grid-dir-url))]
                                  (if (and dir (.isDirectory dir))
                                    (into {} (map (fn [^java.io.File f]
                                                    [(.getName f) (read-resource-bytes (str "grids/" (.getName f)))]))
                                          (->> (file-seq dir) (filter (fn [^java.io.File f] (.isFile f)))))
                                    {}))
                                (do (log/warn "PROJ grid resource directory not found. Transformations may be inaccurate.")
                                    {})))
                            {})]
           (log/info (if *load-grids*
                       (str "Loaded " (count grid-files) " grid files.")
                       "Skipping grid file loading (*load-grids* is false)."))
           {:proj-js-url   proj-js-url
            :loader-js-url loader-js-url
            :wasm-bytes    (read-resource-bytes "wasm/proj-emscripten.wasm")
            :proj-db-bytes (read-resource-bytes "proj.db")
            :proj-ini      (slurp (io/resource "proj.ini"))
            :grid-files    grid-files})))))

(defn init-proj
  "Initialize PROJ for GraalVM and for ClojureScript.
   opts is an optional map. In ClojureScript, opts forwards to
   init-workers!. Recognized keys: :pool, :workers, :log-level,
   :max-live-ctxs, :min-age-ms, :debug-level, :debug-categories
   (refer to the init-workers! docstring)."
  ([] (init-proj {}))
  ;; opts is read by the :cljs branch only. The JVM reads its config from
  ;; classpath resources.
  #_{:clj-kondo/ignore [:unused-binding]}
  ([opts]
   #?(:clj
      (when (nil? @p)
        (let [{:keys [proj-js-url loader-js-url wasm-bytes proj-db-bytes
                      proj-ini grid-files]} @proj-resources
              ;; nw/js-bytes gives the loader a real Uint8Array, so the
              ;; loader does no signed-byte widening of its own.
              init-opts {"wasmBinary" (nw/js-bytes wasm-bytes)
                         "projDb"     (nw/js-bytes proj-db-bytes)
                         "projIni"    proj-ini
                         "projGrids"  (nw/js-bytes-map grid-files)}]
          (nw/bootstrap-graal-module! proj-context
                                      {:loader-module-url   loader-js-url
                                       :preload-module-urls [proj-js-url]
                                       :init-opts           init-opts})
          (log/info "PROJ.js initialization complete. System is ready.")))

      :cljs
      ;; CLJS init is async (returns a Promise) because worker creation and
      ;; WASM load are async. The CLJ side blocks on CompletableFuture.get().
      ;; The wiring latches init-workers!, so concurrent and later callers
      ;; share the first pass. A failed pass clears that memo, so a retry is
      ;; possible, and later calls do not inherit the rejection.
      (-> (init-workers! opts)
          (.catch (fn [error]
                    (js/console.error "PROJ worker init failed:" error)
                    (throw error)))))))

#?(:clj
   (defn bootstrap-pooled-context!
     "Boot PROJ into a fresh polyglot Context on the shared Engine, for a
      workload-pool worker. Returns {:wc <WasmContext> :pctx <Context>}.

      The WasmContext is never registered: a second registry entry would
      break current-module's single-entry fallback for off-pool callers.
      The caller owns the Context and must close it at worker destroy,
      after the PROJ resources inside it are released.

      Costs about 54-70 MB heap and 220-340 ms init per Context on the
      shared Engine, each with its own proj.db in MEMFS."
     []
     (let [{:keys [proj-js-url loader-js-url wasm-bytes proj-db-bytes
                   proj-ini grid-files]} @proj-resources
           pctx (nw/new-polyglot-context!)
           wc   (nw/->WasmContext :net.willcohen.proj/pooled (atom nil))
           init-opts {"wasmBinary" (nw/js-bytes pctx wasm-bytes)
                      "projDb"     (nw/js-bytes pctx proj-db-bytes)
                      "projIni"    proj-ini
                      "projGrids"  (nw/js-bytes-map pctx grid-files)}]
       (nw/bootstrap-graal-module! wc {:loader-module-url   loader-js-url
                                       :preload-module-urls [proj-js-url]
                                       :init-opts           init-opts
                                       :polyglot-context    pctx})
       {:wc wc :pctx pctx})))

(defn ensure-proj-initialized!
  "Lazily start PROJ when a call arrives before init!. Best effort: the
   ClojureScript init is async and nothing awaits it here, so a call that
   really does arrive first still fails. It exists so a consumer that
   forgets init! recovers by the next call.

   The two platforms test different things because they hold the module in
   different places. The JVM loads it into the shared Polyglot Context, so
   `p` is the readiness flag. In ClojureScript the module lives inside each
   worker and `p` is never set on the main thread, so the pool is the flag.
   Testing `p` there made this fire on every native call."
  []
  (when (nil? #?(:clj @p :cljs (current-pool)))
    (init-proj)))

#?(:cljs
   (defn- coord-array-arg?
     [arg]
     (and (object? arg) (= (.-type arg) "coord-array"))))

#?(:cljs
   (defn- kebab->snake
     "Field keys cross to the worker in the snake_case spelling the C
      struct uses."
     [x]
     (.replace (str x) (js/RegExp. "-" "g") "_")))

#?(:cljs
   (defn- struct-list-extras
     "Field layout the worker needs to read a PROJ struct array back out of
      its own heap."
     [fn-def]
     {:structFields (mapv (fn [[kw ftype wasm-offset]]
                            #js {:key (kebab->snake kw)
                                 :type (str ftype)
                                 :offset wasm-offset})
                          (:struct-fields fn-def))
      :structDestroyFn (:struct-destroy-fn fn-def)
      :structParamsCreate (:struct-params-create fn-def)
      :structParamsDestroy (:struct-params-destroy fn-def)}))

#?(:cljs
   (defn- out-params-extras
     "Field layout the worker needs to allocate and read the out-params of
      a call. A :double-array field also carries the index of the argument
      that holds its element count, because only the caller's args give the
      allocation size."
     [fn-def]
     {:outFields
      (mapv (fn [field-spec]
              (let [[field-name field-type] field-spec]
                (cond-> {:key (kebab->snake field-name)
                         :type (str field-type)}
                  (= field-type :double-array)
                  (assoc :countArgIdx
                         (let [arg-names (mapv #(str (first %)) (:argtypes fn-def))]
                           (.indexOf arg-names (str (nth field-spec 3))))))))
            (:out-fields fn-def))}))

#?(:cljs
   (defn- coord-writeback-fn
     "Result hook that copies each coord buffer the worker returned back
      into the caller's Float64Array, then yields the call's own result.
      proj_trans_array mutates coordinates in place, so the caller expects
      to read them from the array it passed in."
     [coord-arrays args]
     (fn [result]
       (let [returned-data (.-coordData result)]
         (dotimes [i (count coord-arrays)]
           (let [ca-info (nth coord-arrays i)
                 original-arg (nth args (:argIdx ca-info))
                 new-data (aget returned-data i)]
             (.set (.-buffer original-arg) (js/Float64Array.from new-data)))))
       (.-result result))))

#?(:cljs
   (defn proj-extras-builder
     "PROJ extras-builder hook for clj-native.dispatch. Turns the PROJ
      parts of a fn-def into the plain data the worker reads as `extras`.

      Each coord-array argument is replaced by 0 in the outgoing args: its
      buffer travels in :coordArrays instead, and the worker allocates the
      pointer that the ccall really receives on its own heap."
     [fn-def args]
     (let [proj-returns (:proj-returns fn-def)
           coord-arrays (into []
                              (keep-indexed
                               (fn [idx arg]
                                 (when (coord-array-arg? arg)
                                   {:argIdx idx
                                    :data (.-buffer arg)
                                    :numFloats (.-floatsNeeded arg)}))
                               args))
           extras (cond-> {}
                    proj-returns (assoc :projReturns (str proj-returns))
                    (= proj-returns :struct-list) (merge (struct-list-extras fn-def))
                    (= proj-returns :out-params)  (merge (out-params-extras fn-def))
                    (seq coord-arrays)
                    (assoc :coordArrays
                           (mapv (fn [ca]
                                   {:argIdx (:argIdx ca)
                                    :data (js/Array.from (:data ca))
                                    :numFloats (:numFloats ca)})
                                 coord-arrays)))]
       {:args (if (seq coord-arrays)
                (mapv (fn [arg] (if (coord-array-arg? arg) 0 arg)) args)
                args)
        :extras extras
        :on-result (when (seq coord-arrays)
                     (coord-writeback-fn coord-arrays args))})))

#?(:cljs
   (defn proj-result-wrapper
     "PROJ result-wrapper hook for clj-native.dispatch. It wraps an opaque
      pointer return with worker_idx, so that a later call goes to the same
      worker.

      A :pj return gets the full wrapper: ptr, worker_idx, type and ctx_id.
      process-return-value-with-tracking then registers it with the
      live-context-cap and FinalizationRegistry pipeline.

      ctx_id carries a generation suffix because PROJ recycles heap
      addresses. Two different PJs can hold the same ptr in one session, so
      ptr alone cannot key the live-pjs map.

      A :pj-list or :pj-operation-factory-context return gets a light
      worker_idx wrap. Without the wrap, a later call such as
      proj_list_get_count receives the raw integer pointer. That address is
      valid only on the worker that ran proj_create_operations. Worker
      affinity routing then falls back to any(), the call goes to a
      different worker, and PROJ returns 0.

      The light wrap has no ctx_id. Thus the FR-registration branch, which
      keys on ctx_id, skips it, and the worker mutex deadlock stays absent.
      The caller must destroy those two types explicitly with
      proj_list_destroy or proj_operation_factory_context_destroy.

      :isolator-result carries proj-context-isolator's return map when the
      call was context-isolated. The wrapper attaches its :ephemeral-ctx-ptr
      to the wrapped object as _ephemeral_context_ptr, with the call's
      worker-idx as _ephemeral_context_worker_idx. build-pj-destroy-fn reads
      the two fields and chains the ctx destroy after the primary destroy."
     [{:keys [result fn-def worker-idx platform args isolator-result]}]
     (let [proj-returns (:proj-returns fn-def)
           wrapped
           (cond
             (and (= platform :cljs)
                  (= proj-returns :pj)
                  (some? result)
                  (not= result 0))
             (let [gen (swap! pj-gen-counter inc)
                   ctx-id (str "pj-" result "-g" gen)
                   ;; Parent ctx-id of the new PJ. PJ-creating PROJ
                   ;; functions take a ctx as their first arg. The ctx
                   ;; wrapper carries a bare-integer .ctx_id, and PJ
                   ;; wrappers prefix with "pj-". parent-ctx-id threaded
                   ;; into pool/register-handle! lets destroy-context!
                   ;; drain only this ctx's children (C) and B-gate on its
                   ;; live PJs.
                   first-arg (first args)
                   parent-ctx-id (when (and (object? first-arg)
                                            (some? (.-ctx_id first-arg))
                                            (not (.startsWith (str (.-ctx_id first-arg)) "pj-")))
                                   (.-ctx_id first-arg))]
               ;; ctx_id participates in the refcount scan of clj-native
               ;; dispatch, so an explicit id keeps that arg scan O(1).
               #js {:ptr result
                    :worker_idx worker-idx
                    :type "pj"
                    :ctx_id ctx-id
                    :parent_ctx_id parent-ctx-id})

             (and (= platform :cljs)
                  (or (= proj-returns :pj-list)
                      (= proj-returns :pj-operation-factory-context))
                  (some? result)
                  (not= result 0))
             #js {:ptr result
                  :worker_idx worker-idx
                  :type (str proj-returns)}

             :else result)
           ephemeral-ctx-ptr (:ephemeral-ctx-ptr isolator-result)]
       (when (and ephemeral-ctx-ptr (object? wrapped))
         (aset wrapped "_ephemeral_context_ptr" ephemeral-ctx-ptr)
         (aset wrapped "_ephemeral_context_worker_idx" worker-idx))
       wrapped)))

#?(:cljs
   (defn ^:async proj-context-isolator
     "Per-call PROJ context isolation. Runs for fn-defs flagged :isolate-context?
      true (at this time only proj_create_crs_to_crs). Sub-dispatches
      proj_context_clone against the consumer's ctx (args[0]) and substitutes
      the clone ptr. Dispatch reads only :args from this map. The rest comes
      back to proj-result-wrapper as :isolator-result. The wrapper attaches the
      clone ptr to the wrapped result through _ephemeral_context_ptr, and
      build-pj-destroy-fn reads it and chains proj_context_destroy after the
      primary proj_destroy fires.

      PERF: proj_context_clone reconstructs the projCppContext. It opens
      sqlite again and rebuilds factory state (c_api.cpp:157). The clone
      prevents cumulative cache-state divergence between callers that
      share one ctx. Examine the cost again if profiling shows a
      setup-time regression.

      The isolator gets the library VALUE in :library, because it
      dispatches again. It must not look one up."
     [{:keys [args worker-idx pool library]}]
     (let [consumer-ctx (first args)
           clone-ptr (await (dispatch/call! library
                                            :proj_context_clone
                                            [consumer-ctx]
                                            {:pool pool :force-worker-idx worker-idx}))]
       {:args (assoc (vec args) 0 clone-ptr)
        :ephemeral-ctx-ptr clone-ptr})))

;; The heap and coord-array helpers below are JVM-only, and specifically
;; the GraalVM backend. They reach into the Emscripten module through `p`,
;; and only the JVM keeps a module there. ClojureScript coord arrays are
;; plain JS Float64Arrays that proj.cljc builds and the worker copies into
;; its own heap, so nothing on the main thread allocates in wasm memory.
#?(:clj
   (defn malloc
     [b]
     (ensure-proj-initialized!)
     (nw/malloc b)))

#?(:clj
   (defn heapf64
     [offset n]
     (ensure-proj-initialized!)
     (nw/heapf64 offset n)))

#?(:clj
   (defn alloc-coord-array
     "PROJ ergonomics: allocate space for `num-coords` 4-double tuples
      that match the PJ_COORD shape. The generic byte-level malloc lives
      in clj-native.wasm; the two nw calls lock per-module on their own."
     [num-coords _dims]
     (let [alloc (malloc (* 32 num-coords))
           array (heapf64 (/ (nw/address-as-int alloc) 8) (* 4 num-coords))]
       {:malloc alloc :array array :n num-coords})))

#?(:clj
   (defn- coords->doubles
     "Pack coords into a flat double array, 4 slots per coordinate, the
      PJ_COORD shape. A coordinate shorter than four values pads with
      the zero fill. Anything that is not a sequence of sequences
      flattens and keeps its own layout."
     ^doubles [coords]
     (if (and (sequential? coords) (every? sequential? coords))
       (let [out (double-array (* 4 (count coords)))]
         (loop [i 0 cs (seq coords)]
           (if cs
             (let [base (* 4 i)]
               (loop [j 0 vs (seq (first cs))]
                 (when (and vs (< j 4))
                   (aset out (+ base j) (double (first vs)))
                   (recur (inc j) (next vs))))
               (recur (inc i) (next cs)))
             out)))
       (double-array (flatten coords)))))

#?(:clj
   (defn set-coord-array
     "PROJ ergonomics: copy a Clojure coord vector into an allocated
      PJ_COORD array. Packs on the host, then makes one bulk clj-native
      write into HEAPF64."
     [coord-array allocated]
     (ensure-proj-initialized!)
     (let [xs (coords->doubles coord-array)]
       (when-let [n (:n allocated)]
         (when (< (* 4 (long n)) (alength xs))
           (throw (ex-info "coord data exceeds the allocated coord-array"
                           {:capacity-coords n :doubles (alength xs)}))))
       (nw/heap-write-doubles! (nw/address-as-int (:malloc allocated)) xs)
       allocated)))

#?(:clj
   (defn get-coord-array
     "PROJ ergonomics: read a 4-double PJ_COORD tuple from an allocated
      coord array at index idx. Returns a vector [x y z t]."
     [allocated idx]
     (ensure-proj-initialized!)
     (vec (nw/read-heap-array (+ (nw/address-as-int (:malloc allocated))
                                 (* 32 (long idx)))
                              4 :f64))))

(defn string-list-to-native-array
  "CLJ: forwards to the generic clj-native helper.
   CLJS: returns a JS array (the worker allocates through ccall)."
  [s-list]
  #?(:cljs (vec s-list)
     :clj  (nw/string-list-to-native-array s-list)))

