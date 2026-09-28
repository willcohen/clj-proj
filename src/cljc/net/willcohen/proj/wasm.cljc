;; Copyright (c) 2024, 2025, 2026 Will Cohen
;;
;; Part of clj-proj, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

#?(:clj
   (ns net.willcohen.proj.wasm
     "GraalVM glue: loads the PROJ WASM module through clj-native's
      graal-wasm, and supplies coord arrays of PJ_COORD shape (4 doubles)."
     (:require [clojure.java.io :as io]
               [clojure.tools.logging :as log]
               [net.willcohen.native.graal-wasm :as nw]))
   :cljs
   (ns wasm
     "The worker pool wiring for browser and Node.js, and PROJ's
      extras-builder, result-wrapper and context-isolator hooks for
      clj-native dispatch."
     (:require ["ffi-wasm/pool" :as pool]
               ["ffi-wasm/workload-pool" :as wp]
               ["ffi-wasm/dispatch" :as dispatch]
               ["./handler.mjs" :as handler])))

#?(:clj (set! *warn-on-reflection* true))

#?(:clj
   (def ^:dynamic *load-grids*
     "Controls PROJ grid file load at GraalVM initialization.
  Grid load is very slow: every byte of every grid crosses the polyglot
  boundary into the JS heap.
  Set to false to skip grid load and make initialization faster."
     false))

;; With no with-wasm-context binding, clj-native's heap utilities fall back
;; to this sole registered WasmContext.
#?(:clj (defonce proj-context (nw/create-wasm-context! :net.willcohen.proj)))

;; The loaded module, or nil. Always nil in ClojureScript, where the module
;; lives in each worker.
#?(:clj  (def p (:module-ref proj-context))
   :cljs (defonce p (atom nil)))

;; Read the wiring only through clj-native's accessors: a squint Atom from
;; another package instance does not support deref.
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
     "The live joint pool, or nil before init-proj and after shutdown!."
     []
     (wp/wiring-pool pool-wiring)))

;; The cap is about twice the live-PJ peak of bb test:node, where V8 runs
;; almost no GC. init-proj opts override it.
#?(:cljs
   (pool/register-library-context! :net.willcohen.proj
                                   {:max-live-ctxs 128
                                    :min-age-ms 100}))

#?(:cljs
   (defn ^:async init-workers!
     "Wire the private workload-pool registry and return the joint pool.
      opts keys:

        :pool             a caller WorkerPool, adopted with owned? false. The
                          caller keeps its lifecycle, and its registry must
                          register the proj handler before it spawns the pool.
        :workers          :auto, an integer or \"auto\". Default :auto.
        :log-level        0..3, the level of the PROJ logger. Default 0.
        :max-live-ctxs    the cap on live PJ contexts, or nil to keep it.
        :min-age-ms       the eviction age threshold, or nil to keep it.
        :debug-level      :off | :error | :warn | :info | :debug | :trace, or
                          nil for off: clj-native's diagnostics, on the page
                          and in the workers.
        :debug-categories the categories to allow, or nil for all.

      wp/ensure-wired! latches the whole pass, so concurrent callers share
      one wiring and one pool, and a later call gets the first call's pool
      whatever its opts."
     [opts]
     (await
      (let [{caller-pool :pool
             :keys [workers max-live-ctxs min-age-ms debug-level debug-categories]} opts
            ;; Workers take it through init args before init() runs, and
            ;; again from the broadcast of init-pool!.
            handler-rt-opt (when (some? debug-level)
                             {:level      debug-level
                              :categories debug-categories})
            register!
            (fn ^:async register-proj! [reg]
              ;; Enable the page side BEFORE pool init, so QUEUE-* / FR-*
              ;; events from the create call land in the trace.
              (when handler-rt-opt
                (pool/set-log-config! handler-rt-opt))
              ;; A nil value keeps the current setting.
              (pool/register-library-context!
               :net.willcohen.proj
               {:max-live-ctxs max-live-ctxs :min-age-ms min-age-ms})
              (if (some? caller-pool)
                ;; An adopted pool also gets the flush and the
                ;; library-context reset at shutdown!. A spec with only
                ;; :pre-terminate can register at any time.
                (wp/register-handler! reg :compute :net.willcohen.proj
                                      {:pre-terminate handler/pre-terminate!})
                (let [init-args (await (handler/default-init-args opts))]
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
           {:registry-opts (cond-> {:size (or workers "auto")}
                             handler-rt-opt
                             (assoc :handler-runtime handler-rt-opt))
            :register! register!}))))))

#?(:cljs
   (defn ^:async worker-call
     "Call `method` of the proj handler with the JS array `args`, on worker
      worker-idx, or on the least-loaded worker when worker-idx is nil.

      Single arity: on a multi-arity defn, squint's `^:async` marks only
      the outer dispatcher, and esbuild rejects the inner `await`."
     [worker-idx method args]
     (await (pool/worker-call (current-pool) :net.willcohen.proj method args worker-idx))))

#?(:cljs
   (defn ctx-key
     "The key of context ctx-id of worker worker-idx in the pool maps. Each
      worker numbers its contexts from 1, so ctx-id alone repeats across
      workers."
     [worker-idx ctx-id]
     (str worker-idx ":" ctx-id)))

#?(:cljs
   (def ^:private DESTROY-GATE-MAX-ITERS 16))

#?(:cljs
   (defn ^:async destroy-context!
     "Wait until every child handle of the context has released, then post
      context_destroy to `p`, the pool that created the context (refer to
      live-pool?). A register-handle! after the drain resolves can add a
      child, so the drain runs again, at most DESTROY-GATE-MAX-ITERS
      times, and then the destroy posts anyway."
     [p worker-idx ctx-id]
     (let [parent-key (ctx-key worker-idx ctx-id)]
       (loop [iters 0]
         (let [remaining (pool/in-flight-count-for-parent parent-key)]
           (when (and (pos? remaining)
                      (< iters DESTROY-GATE-MAX-ITERS))
             (await (pool/await-parent-drain! parent-key))
             (recur (inc iters)))))
       (await (pool/worker-call p :net.willcohen.proj
                                "context_destroy"
                                #js [ctx-id]
                                worker-idx)))))

#?(:cljs
   (defn live-pool?
     "True only if `p` is the pool this consumer routes to now. A GC-fired
      destroy tests it, because a new pool restarts each worker's ctx-ids
      at 1 and reuses heap addresses, so a stale destroy would free a live
      handle of the new pool. Refer to wp/live-pool? for an adopted pool."
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
     "Stop the wiring: run handler/pre-terminate!, terminate the pool when
      this consumer owns it, and clear the wiring, so a later init-proj
      starts fresh."
     []
     ;; A registry means the wiring ran its :pre-terminate hook, which
     ;; already flushed. nil means nothing was wired (init never ran, or a
     ;; shutdown already ran), so drain pending disposers here instead.
     (when-not (await (wp/shutdown-wiring! pool-wiring))
       (await (pool/flush-pending-disposes!)))
     nil))

#?(:cljs
   (defn create-context-on-worker
     "Create a PROJ context on the least-loaded worker, or on the :worker
      of opts. Returns a promise of {:ctx-id :ptr :worker-idx :release}.
      Call :release once, or hand it to track-context!, so the worker
      claim drains."
     [opts]
     (let [{:keys [idx release]} (pool/assign-worker-for-context! (current-pool) :net.willcohen.proj opts)]
       (-> (worker-call idx "context_create" #js [#js {}])
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
     "Track the context under ctx-key. release-fn fires once: on
      untrack-context!, or when V8 collects `owner`, a JS object."
     [ctx-id worker-idx release-fn owner]
     (pool/track-context! :net.willcohen.proj (ctx-key worker-idx ctx-id)
                          worker-idx release-fn owner)))

#?(:cljs
   (defn untrack-context!
     "Drain the worker-router claim and remove the ctx-id mapping. The
      context destroy-fn calls this, so the pool's claim_count releases
      synchronously when destroy fires, before the destroy promise of the
      worker-call settles."
     [worker-idx ctx-id]
     (pool/untrack-context! :net.willcohen.proj (ctx-key worker-idx ctx-id))))

#?(:clj
   (defn- read-resource-bytes [path]
     (let [url (or (io/resource path)
                   (throw (ex-info (str "Could not find resource on classpath: " path) {:path path})))]
       (with-open [in (io/input-stream url)]
         (.readAllBytes in)))))

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

#?(:clj
   (defn- bootstrap-proj!
     "Load PROJ into polyglot Context `pctx`, and store the module on `wc`."
     [wc pctx]
     (let [{:keys [proj-js-url loader-js-url wasm-bytes proj-db-bytes
                   proj-ini grid-files]} @proj-resources]
       (nw/bootstrap-graal-module! wc {:loader-module-url   loader-js-url
                                       :preload-module-urls [proj-js-url]
                                       :polyglot-context    pctx
                                       :init-opts {"wasmBinary" (nw/js-bytes pctx wasm-bytes)
                                                   "projDb"     (nw/js-bytes pctx proj-db-bytes)
                                                   "projIni"    proj-ini
                                                   "projGrids"  (nw/js-bytes-map pctx grid-files)}}))))

(defn init-proj
  "Initialize PROJ for GraalVM and for ClojureScript. In ClojureScript,
   opts goes to init-workers!, which lists its keys."
  ([] (init-proj {}))
  ;; Only the :cljs branch reads opts.
  #_{:clj-kondo/ignore [:unused-binding]}
  ([opts]
   #?(:clj
      (when (nil? @p)
        (bootstrap-proj! proj-context (nw/context))
        (log/info "PROJ.js initialization complete. System is ready."))

      :cljs
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
      after the PROJ resources inside it are released. A failed boot
      closes it."
     []
     (let [pctx (nw/new-polyglot-context!)
           wc   (nw/->WasmContext :net.willcohen.proj/pooled (atom nil))]
       (try
         (bootstrap-proj! wc pctx)
         {:wc wc :pctx pctx}
         (catch Throwable t
           (.close ^org.graalvm.polyglot.Context pctx)
           (throw t))))))

(defn ensure-proj-initialized!
  "Lazily start PROJ when a call arrives before init!. Best effort: the
   ClojureScript init is async and nothing awaits it here, so the first call
   still fails, and the next call recovers. The JVM holds the module in `p`.
   ClojureScript holds it in each worker, so the pool is the flag."
  []
  (when (nil? #?(:clj @p :cljs (current-pool)))
    (init-proj)))

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
      a call. Field i goes in :argIdx, the index of the i-th out_ arg. A
      :double-array field also carries the index of the argument that holds
      its element count, because only the caller's args give the allocation
      size."
     [fn-def]
     (let [arg-names (mapv #(str (first %)) (:argtypes fn-def))
           out-idxs (vec (keep-indexed (fn [i arg-name] (when (.startsWith arg-name "out_") i))
                                       arg-names))]
       {:outFields
        (vec (map-indexed (fn [i [field-name field-type _ count-arg]]
                            (cond-> {:key (kebab->snake field-name)
                                     :type (str field-type)
                                     :argIdx (nth out-idxs i)}
                              (= field-type :double-array)
                              (assoc :countArgIdx (.indexOf arg-names (str count-arg)))))
                          (:out-fields fn-def)))})))

(defn errno-checked?
  "True when a call of fn-def gets its own errno: a context fn that returns
   a pointer or a string, where NULL or \"\" can signal a failure. The
   context is the first arg. A fn with :is-context-fn false, such as
   proj_context_clone, reports no failure through errno."
  [fn-def]
  (and (not (false? (:is-context-fn fn-def)))
       (contains? #{:ctx :context} (ffirst (:argtypes fn-def)))
       (contains? #{:pointer :string} (:rettype fn-def))))

;; The errno that the worker read after a NULL result of a call, keyed by the
;; args array of dispatch/call!, until proj/dispatch-call takes it.
#?(:cljs
   (defonce ^:private call-errnos (js/WeakMap.)))

#?(:cljs
   (defn take-call-errno!
     "The non-zero errno that the worker read after a NULL result of the
      call with `args`, or nil."
     [args]
     (when-let [errno (.get call-errnos args)]
       (.delete call-errnos args)
       errno)))

#?(:cljs
   (defn- worker-result-fn
     "Result hook for a call whose worker gives {result coordData errno}.
      Copy each coord buffer back into the caller's Float64Array, because
      proj_trans_array mutates in place. Keep a non-zero errno for
      take-call-errno!, then yield the call's own result."
     [coord-arrays args]
     (fn [result]
       (dotimes [i (count coord-arrays)]
         (.set (.-buffer (nth args (:argIdx (nth coord-arrays i))))
               (aget (.-coordData result) i)))
       (let [errno (.-errno result)]
         (when (and (number? errno) (not (zero? errno)))
           (.set call-errnos args errno)))
       (.-result result))))

#?(:cljs
   (defn proj-extras-builder
     "PROJ extras-builder hook for clj-native.dispatch. Turns the PROJ
      parts of a fn-def into the plain data the worker reads as `extras`.

      Each coord-array argument and each string array of a :string-array?
      argument is replaced by 0 in the outgoing args: it travels in
      :coordArrays or :stringArrays instead, and the worker allocates the
      pointer that the ccall really receives on its own heap. ccall passes a
      JS array as a number, so PROJ would get NULL.

      For an errno-checked? call, :errnoCheck asks the worker to reset the
      errno of the context before the ccall and to read it after a NULL."
     [fn-def args]
     (let [proj-returns (:proj-returns fn-def)
           coord-arrays (into []
                              (keep-indexed
                               (fn [idx arg]
                                 (when (and (object? arg) (= (.-type arg) "coord-array"))
                                   (let [^js data (.-buffer arg)
                                         n (.-floatsNeeded arg)]
                                     ;; structured clone of a view copies its
                                     ;; whole backing buffer
                                     {:argIdx idx
                                      :data (if (= (.-length data) n) data (.slice data 0 n))
                                      :numFloats n})))
                               args))
           string-names (into #{} (keep (fn [[arg-name semantic-type]]
                                          (when (= :string-array? semantic-type) arg-name)))
                              (:argsemantics fn-def))
           string-ix (into #{} (keep-indexed (fn [i [arg-name]] (when (contains? string-names arg-name) i))
                                             (:argtypes fn-def)))
           string-arrays (into []
                               (keep-indexed
                                (fn [idx arg]
                                  (when (and (contains? string-ix idx) (array? arg))
                                    {:argIdx idx :strings arg})))
                               args)
           moved (into #{} (map :argIdx) (concat coord-arrays string-arrays))
           errno? (errno-checked? fn-def)
           extras (cond-> {}
                    proj-returns (assoc :projReturns (str proj-returns))
                    (= proj-returns :struct-list) (merge (struct-list-extras fn-def))
                    (= proj-returns :out-params)  (merge (out-params-extras fn-def))
                    (seq coord-arrays) (assoc :coordArrays coord-arrays)
                    (seq string-arrays) (assoc :stringArrays string-arrays)
                    errno? (assoc :errnoCheck true))]
       {:args (if (seq moved)
                (into [] (map-indexed (fn [idx arg] (if (contains? moved idx) 0 arg)) args))
                args)
        :extras extras
        :on-result (when (or (seq coord-arrays) errno?)
                     (worker-result-fn coord-arrays args))})))

;; The context clone of each isolated call in flight, keyed by the args
;; array of dispatch/call!: a fn that destroys the clone. The result-wrapper
;; removes the entry. proj/dispatch-call calls it when the call rejects, which
;; is common: the wasm has no C++ exception catching, so PROJ rejects an
;; invalid CRS. The key works because call-cljs gives the args array of
;; dispatch/call! to the extras-builder, the isolator and the wrapper, and
;; proj-extras-builder returns that array when it moves no arg.
#?(:cljs
   (defonce ^:private unowned-clones (js/WeakMap.)))

#?(:cljs
   (defn- clone-release-fn
     "A fn that destroys context clone `clone-ptr` on worker `worker-idx` of
      `call-pool`. pool/fire-and-capture-dispose! holds the Promise, so a
      flush waits for it and reports a failure."
     [library call-pool worker-idx clone-ptr]
     (fn []
       (pool/fire-and-capture-dispose!
        (fn []
          (.catch (dispatch/call! library "proj_context_destroy"
                                  [#js {:worker_idx worker-idx :ptr clone-ptr}]
                                  {:pool call-pool :force-worker-idx worker-idx})
                  ignore-pool-terminated))
        #js {:lib "net.willcohen.proj" :kind "ctx-clone" :worker worker-idx}))))

#?(:cljs
   (defn release-unowned-clone!
     "Destroy the context clone of the isolated call with `args`, if no
      result took it."
     [args]
     (when-let [release (.get unowned-clones args)]
       (.delete unowned-clones args)
       (release))))

#?(:cljs
   (defn proj-result-wrapper
     "PROJ result-wrapper hook for clj-native.dispatch. Wraps a pointer
      return with its worker_idx, so a later call goes to the worker that
      owns the address. A :pj return also gets the type, ctx_id and
      parent_ctx_id that process-return-value-with-tracking tracks. A
      :pj-list or :pj-operation-factory-context return gets no ctx_id, so the
      caller must destroy it. The context clone of an isolated call goes on
      an object result as _ephemeral_context_ptr and
      _ephemeral_context_worker_idx, for build-pj-destroy-fn. With any other
      result, the wrapper destroys the clone at once."
     [{:keys [result fn-def worker-idx args isolator-result]}]
     (let [proj-returns (:proj-returns fn-def)
           wrapped
           (cond
             (or (nil? result) (= result 0)) result

             (= proj-returns :pj)
             (let [first-arg (first args)
                   ;; A ctx wrapper's ctx_id is a bare integer, and a PJ's
                   ;; starts with "pj-". destroy-context! drains the
                   ;; children of the parent ctx.
                   parent-ctx-id (when (and (object? first-arg)
                                            (some? (.-ctx_id first-arg))
                                            (not (.startsWith (str (.-ctx_id first-arg)) "pj-")))
                                   (.-ctx_id first-arg))]
               #js {:ptr result
                    :worker_idx worker-idx
                    :type "pj"
                    :ctx_id (str "pj-" result "-g" (swap! pj-gen-counter inc))
                    :parent_ctx_id parent-ctx-id})

             (contains? #{:pj-list :pj-operation-factory-context} proj-returns)
             #js {:ptr result
                  :worker_idx worker-idx
                  :type (str proj-returns)}

             :else result)]
       (when isolator-result
         (.delete unowned-clones args)
         (if (object? wrapped)
           (do (aset wrapped "_ephemeral_context_ptr" (:ephemeral-ctx-ptr isolator-result))
               (aset wrapped "_ephemeral_context_worker_idx" worker-idx))
           ((:release isolator-result))))
       wrapped)))

#?(:cljs
   (defn ^:async proj-context-isolator
     "Per-call PROJ context isolation for fn-defs with :isolate-context?.
      Clones the consumer's ctx (args[0]) on its worker and passes the clone
      in its place. Dispatch reads only :args. The rest reaches
      proj-result-wrapper as :isolator-result. :release destroys a clone
      that no result owns (refer to unowned-clones).

      PERF: proj_context_clone opens sqlite again and rebuilds the factory
      state (c_api.cpp:157). It keeps callers that share one ctx from
      drifting apart in cache state.

      The isolator gets the library VALUE in :library, because it
      dispatches again. It must not look one up."
     [{:keys [args worker-idx pool library]}]
     (let [consumer-ctx (first args)
           clone-ptr (await (dispatch/call! library
                                            :proj_context_clone
                                            [consumer-ctx]
                                            {:pool pool :force-worker-idx worker-idx}))
           release (clone-release-fn library pool worker-idx clone-ptr)]
       (.set unowned-clones args release)
       {:args (assoc (vec args) 0 clone-ptr)
        :ephemeral-ctx-ptr clone-ptr
        :release release})))

;; GraalVM only. A ClojureScript coord array is a Float64Array that the
;; worker copies into its own heap.
#?(:clj
   (defn malloc
     [b]
     (ensure-proj-initialized!)
     (nw/malloc b)))

#?(:clj
   (defn alloc-coord-array
     "Allocate space for `num-coords` PJ_COORD tuples of 4 doubles."
     [num-coords _dims]
     {:malloc (malloc (* 32 num-coords)) :n num-coords}))

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
     "Copy a Clojure coord vector into an allocated
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
     "Read a 4-double PJ_COORD tuple from an allocated
      coord array at index idx. Returns a vector [x y z t]."
     [allocated idx]
     (ensure-proj-initialized!)
     (vec (nw/read-heap-array (+ (nw/address-as-int (:malloc allocated))
                                 (* 32 (long idx)))
                              4 :f64))))

(defn string-list-to-native-array
  "CLJ: forwards to the generic clj-native helper.
   CLJS: returns a JS array. proj-extras-builder sends it to the worker,
   which allocates the char** on its heap."
  [s-list]
  #?(:cljs (vec s-list)
     :clj  (nw/string-list-to-native-array s-list)))

