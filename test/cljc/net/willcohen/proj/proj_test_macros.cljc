;; Copyright (c) 2024, 2025, 2026 Will Cohen
;;
;; Part of clj-proj, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

;; CLJS macros for proj_test.cljc. squint (0.11.187+) expands
;; cljc-defined macros through SCI at compile time, so a plain
;; `:require` loads them.
;;
;; Each macro emits an `await` form, so the enclosing deftest must
;; carry `^:async`. JVM clojure.test ignores that metadata, and the
;; JVM side defines an identity `await` macro.

(ns net.willcohen.proj.proj-test-macros)

(defmacro with-each-implementation
  "CLJS counterpart to the JVM impl-iteration macro. CLJS has one
  implementation for each runtime: `:node` under Node, `:browser`
  under playwright. The test runner selects the runtime. The macro
  makes sure that init ran, then runs the body once.

  The enclosing deftest must carry `^:async`."
  [& body]
  `(do
     (~'await (~'ensure-init!))
     ~@body))

(defmacro with-test-context
  "CLJS counterpart to the JVM `with-test-context` macro. The macro
  awaits `proj/context-create` and binds `ctx-binding` to the
  resolved context, so ctx is the value directly, as on the JVM.

  The enclosing deftest must carry `^:async`."
  [[ctx-binding] & body]
  `(let [~ctx-binding (~'await (~'proj/context-create))]
     ~@body))
