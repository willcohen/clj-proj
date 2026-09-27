;; Copyright (c) 2024, 2025, 2026 Will Cohen
;;
;; Part of clj-proj, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT
;;
;; Tests for proj-handler.mjs, the worker-router ModuleHandler.
;;
;; These dist artifacts must exist:
;;   src/cljc/net/willcohen/proj/dist/proj-emscripten.{js,wasm}
;;   src/cljc/net/willcohen/proj/dist/proj.db
;;   src/cljc/net/willcohen/proj/dist/proj.ini
;; Run `bb squint` if they are absent.

(ns proj-handler-test
  #?(:cljs (:require [cljs.test :as t :refer [deftest is]]
                     ["node:fs" :refer [readFileSync existsSync copyFileSync rmSync]]
                     ["node:url" :refer [fileURLToPath pathToFileURL]]
                     ["node:path" :refer [dirname resolve]]
                     ["./dist/test_runner.mjs"
                      :refer [run_tests_and_exit_BANG_]])))

#?(:cljs
   (do

     (def __filename (fileURLToPath (.-url js/import.meta)))
     (def __dirname (dirname __filename))
     (def dist-dir (resolve __dirname "../../src/cljc/net/willcohen/proj/dist"))
     ;; A dynamic import needs a URL, not a path: node's ESM loader reads a
     ;; raw Windows absolute path as a URL with protocol "d:".
     (def handler-path
       (.-href (pathToFileURL (resolve __dirname "../../src/cljc/net/willcohen/proj/proj-handler.mjs"))))
     (def overrides-path
       (.-href (pathToFileURL (resolve __dirname "../../src/cljc/net/willcohen/proj/proj-handler-overrides.mjs"))))

     (def state (atom {:create nil :destroy nil :db-bytes nil :ini-bytes nil}))

;; cljs.test has no `before` hook, so each test calls init-once!
;; first.
     (defn ^:async init-once! []
       (when (nil? (:create @state))
         (let [mod (await (js/import handler-path))]
           (swap! state assoc
                  :create   (.-create mod)
                  :destroy  (.-destroy mod)
                  :db-bytes (readFileSync (resolve dist-dir "proj.db"))
                  :ini-bytes (readFileSync (resolve dist-dir "proj.ini"))))))

     (deftest ^:async ImportShape-create-is-an-async-function
       (await (init-once!))
       (let [create (:create @state)]
         (is (= "function" (js* "typeof ~{}" create))
             "create must be a function")
         (let [result (or (= "AsyncFunction" (-> create .-constructor .-name))
                          (and (= "function" (js* "typeof ~{}" create))
                               (>= (.-length create) 0)))]
           (is result "create must be callable as async"))))

     (deftest ^:async rejects-when-dbBytes-missing
       (await (init-once!))
       (let [create (:create @state)]
         (try
           (await (create #js {}))
           (is false "should reject")
           (catch :default err
             (is (re-find #"(?i)dbBytes" (str (.-message err)))
                 "create({}) must reject with a clear dbBytes-missing error")))))

     (deftest ^:async returns-plain-object-with-required-handler-methods
       (await (init-once!))
       (let [{:keys [create db-bytes ini-bytes]} @state
             handler (await (create #js {:dbBytes db-bytes :iniBytes ini-bytes :logLevel 0}))]
         (try
           (is (= "object" (js* "typeof ~{}" handler)) "handler must be an object")
           (is (not= nil handler) "handler must not be null")
           (doseq [name ["context_create" "set_log_level" "context_destroy"
                         "ccall" "malloc" "free"
                         "heapf64_set" "heapf64_get" "read_string_array"
                         "heapu8_set" "heapu8_get" "string_to_utf8" "utf8_to_string"]]
             (is (= "function" (js* "typeof ~{}" (aget handler name)))
                 (str "handler." name " must be a function")))
           (finally (await ((:destroy @state)))))))

     (deftest ^:async context-create-returns-a-positive-integer-ctxId
       (await (init-once!))
       (let [{:keys [create db-bytes ini-bytes]} @state
             handler (await (create #js {:dbBytes db-bytes :iniBytes ini-bytes :logLevel 0}))]
         (try
           (let [r (await (.context_create handler #js {}))]
             (is (= "number" (js* "typeof ~{}" (.-ctxId r))))
             (is (> (.-ctxId r) 0) "ctxId must be positive")
             (is (= "number" (js* "typeof ~{}" (.-ptr r))))
             (is (> (.-ptr r) 0) "ptr must be non-zero")
             (await (.context_destroy handler (.-ctxId r))))
           (finally (await ((:destroy @state)))))))

     (deftest ^:async malloc-returns-nonzero-ptr-free-succeeds
       (await (init-once!))
       (let [{:keys [create db-bytes ini-bytes]} @state
             handler (await (create #js {:dbBytes db-bytes :iniBytes ini-bytes :logLevel 0}))]
         (try
           (let [ptr (await (.malloc handler 8))]
             (is (= "number" (js* "typeof ~{}" ptr)))
             (is (not= 0 ptr) "malloc(8) must return nonzero ptr")
             (let [free (await (.free handler ptr))]
               (is (= true (.-ok free)))))
           (finally (await ((:destroy @state)))))))

     (deftest ^:async IdempotentInit-re-create-with-same-args-preserves-state
       (await (init-once!))
       (let [{:keys [create db-bytes ini-bytes]} @state
             handler1 (await (create #js {:dbBytes db-bytes :iniBytes ini-bytes :logLevel 0}))]
         (try
           (let [r1 (await (.context_create handler1 #js {}))
            ;; The ctxId sequence continues only when state stays
            ;; across re-create.
                 handler2 (await (create #js {:dbBytes db-bytes :iniBytes ini-bytes :logLevel 0}))
                 r2 (await (.context_create handler2 #js {}))]
             (is (> (.-ctxId r2) (.-ctxId r1))
                 (str "state preserved: r2.ctxId (" (.-ctxId r2)
                      ") > r1.ctxId (" (.-ctxId r1) ")"))
             (await (.context_destroy handler2 (.-ctxId r2)))
             (await (.context_destroy handler1 (.-ctxId r1))))
           (finally (await ((:destroy @state)))))))

     (deftest ^:async IdempotentInit-re-create-with-different-args-throws
       (await (init-once!))
       (let [{:keys [create db-bytes ini-bytes]} @state
             _ (await (create #js {:dbBytes db-bytes :iniBytes ini-bytes :logLevel 0}))]
         (try
           (try
             (await (create #js {:dbBytes db-bytes :iniBytes ini-bytes :logLevel 2}))
             (is false "should reject")
             (catch :default err
               (is (re-find #"(?i)different|already initiali[sz]ed|init args" (str (.-message err)))
                   "second create with different args must throw")))
           (finally (await ((:destroy @state)))))))

     (deftest ^:async parallel-mallocs-return-distinct-nonzero-pointers
  ;; The workerQueue in makeHandler serializes ccall bodies, so no
  ;; two concurrent mallocs share a ptr.
       (await (init-once!))
       (let [{:keys [create db-bytes ini-bytes]} @state
             handler (await (create #js {:dbBytes db-bytes :iniBytes ini-bytes :logLevel 0}))]
         (try
           (let [ptrs (await
                       (js/Promise.all
                        (.from js/Array #js {:length 10}
                               (fn [_ _idx] (.malloc handler 64)))))
                 seen (js/Set.)]
             (doseq [i (range (.-length ptrs))]
               (let [p (aget ptrs i)]
                 (is (= "number" (js* "typeof ~{}" p)))
                 (is (not= 0 p) "no malloc result may be 0")
                 (is (not (.has seen p)) (str "duplicate ptr " p))
                 (.add seen p)))
             (await
              (js/Promise.all
               (.map ptrs (fn [p] (.free handler p))))))
           (finally (await ((:destroy @state)))))))

     ;; URL for the same Windows reason as handler-path above.
     (def spec-module-path
       (.-href (pathToFileURL (resolve __dirname "../../src/cljc/net/willcohen/proj/handler.mjs"))))
     (def proj-src-dir
       (resolve __dirname "../../src/cljc/net/willcohen/proj"))

     (deftest ^:async spec-emits-a-real-module-url-args-and-pre-terminate
       (let [mod (await (js/import spec-module-path))
             spec-fn (.-spec mod)
             args #js {:tag "payload"}
             s (spec-fn args)]
         (let [module-url (:module s)]
           (is (string? module-url) ":module is a URL string")
           (is (.endsWith module-url "proj-handler.mjs"))
           (is (existsSync (fileURLToPath module-url))
               ":module points at a file that exists"))
         (is (identical? args (:args s)) ":args passes through untouched")
         (is (= "function" (js* "typeof ~{}" (:pre-terminate s)))
             ":pre-terminate is the shutdown hook fn")
         (is (nil? (await ((:pre-terminate s))))
             "pre-terminate resolves with nothing pending")))

     (deftest ^:async default-init-args-builds-the-working-payload
       ;; loadProjResources reads proj.db and proj.ini adjacent to
       ;; proj-loader.mjs. The copies make that layout in the source
       ;; tree for the duration of this test.
       (let [db-dst (resolve proj-src-dir "proj.db")
             ini-dst (resolve proj-src-dir "proj.ini")]
         (copyFileSync (resolve dist-dir "proj.db") db-dst)
         (copyFileSync (resolve dist-dir "proj.ini") ini-dst)
         (try
           (let [mod (await (js/import spec-module-path))
                 dia (.-default_init_args mod)
                 args (await (dia nil))]
             (is (pos? (.-length (.-dbBytes args))) "dbBytes loaded")
             (is (pos? (.-length (.-iniBytes args))) "iniBytes loaded")
             (is (= 0 (.-logLevel args)) "logLevel defaults to 0")
             (let [args2 (await (dia {:log-level 2}))]
               (is (= 2 (.-logLevel args2)) ":log-level override lands"))
             (let [s ((.-spec mod) args)
                   hmod (await (js/import (:module s)))
                   create (.-create hmod)
                   handler (await (create (:args s)))]
               (try
                 (let [r (await (.context_create handler #js {}))]
                   (is (> (.-ctxId r) 0)
                       "spec module + default args boot a live PROJ context"))
                 (finally (await ((:destroy @state)))))))
           (finally
             (rmSync db-dst)
             (rmSync ini-dst)))))

     ;; PROJ allocates a string list for the caller, so the worker frees it
     ;; after the decode.
     (deftest ^:async string-list-result-is-freed
       (let [overrides (await (js/import overrides-path))
             calls #js []
             mod #js {:getValue (fn [_ _] 0)
                      :UTF8ToString (fn [_] "")
                      :ccall (fn [c-name _ _ args] (.push calls #js [c-name (aget args 0)]))}
             result ((.-decodeCallResult overrides) mod 1234 #js {:projReturns "string-list"})]
         (is (= 0 (.-length result)))
         (is (= 1 (.-length calls)) "one ccall after the decode")
         (is (= "proj_string_list_destroy" (aget (aget calls 0) 0)))
         (is (= 1234 (aget (aget calls 0) 1)))))

     ;; netGetHeader copied each header value into a new block with
     ;; stringToNewUTF8, and nothing freed it: three blocks for each grid file
     ;; that PROJ opened.
     (deftest ^:async network-header-strings-are-freed-at-close
       (let [overrides (await (js/import overrides-path))
             allocated #js []
             freed #js []
             mod #js {:stringToNewUTF8 (fn [_] (let [p (+ 100 (.-length allocated))]
                                                 (.push allocated p)
                                                 p))
                      :_free (fn [p] (.push freed p))}
             entry #js {:url "https://example.invalid/g.tif"
                        :headers #js {"content-range" "bytes 0-9/100"}}]
         (dotimes [_ 3]
           (is (pos? ((.-headerValuePtr overrides) mod entry "Content-Range"))))
         (is (= 0 ((.-headerValuePtr overrides) mod entry "ETag")) "no header gives NULL")
         (is (= 0 (.-length freed)) "PROJ can read each string until the close")
         ((.-releaseHandleStrings overrides) mod entry)
         (is (= (vec allocated) (vec freed)) "the close frees each string of the handle")))

     ;; Each deftest calls the module destroy in a finally, so no
     ;; global teardown is necessary.
     (run_tests_and_exit_BANG_)))
