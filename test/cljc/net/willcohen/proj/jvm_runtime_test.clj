;; Copyright (c) 2024, 2025, 2026 Will Cohen
;;
;; Part of clj-proj, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns net.willcohen.proj.jvm-runtime-test
  "Regression guard for the optimizing Truffle runtime.

   On the GraalVM polyglot path (force-graal! or the FFI->graal
   fallback), the PROJ wasm guest JIT-compiles only when an optimizing
   Truffle runtime is active. Two items are necessary:
   org.graalvm.truffle/truffle-runtime on the path, and a GraalVM CE
   25 JDK (libgraal). Without them Truffle uses the interpreter,
   approximately 6x slower. Stock OpenJDK 25 cannot load the
   optimizing runtime cleanly (JDK-8364936), so this guard asserts
   only on a GraalVM JDK. On a plain OpenJDK (FFI-only environments)
   guest JIT is not applicable and the test is informational.

   JVM-only. The .clj extension keeps the file out of the squint
   compile. cognitect.test-runner finds it through `-d test/cljc/net`
   in the deps.edn :test alias."
  (:require [clojure.test :refer [deftest is testing]]
            [net.willcohen.native.graal-wasm :as nw])
  (:import [org.graalvm.polyglot Engine]))

(set! *warn-on-reflection* true)

(def ^:private ^String vendor-version (System/getProperty "java.vendor.version"))

(def ^:private graalvm-jdk?
  (some-> vendor-version (.contains "GraalVM")))

(defn- graal-version
  "The GraalVM release out of java.vendor.version, so
   'GraalVM CE 25.2.4+7.1' gives '25.2.4'. This is the version of the JDK's
   libgraal, which is not java.version: GraalVM 25.2.4 ships JDK 25.0.4."
  [vendor]
  (second (re-find #"(\d+\.\d+\.\d+)" (or vendor ""))))

(defn- diagnose-interpreted
  "Truffle falls back to the interpreter for more than one reason, and the
   version mismatch is invisible from the runtime name alone. Compare the
   polyglot artifacts against the JDK's own libgraal before blaming the path."
  [runtime]
  (let [polyglot (with-open [e (Engine/create (into-array String []))]
                   (.getVersion e))
        jdk (graal-version vendor-version)
        head (str "Truffle runtime is '" runtime "' (interpreted fallback) on "
                  "GraalVM JDK '" vendor-version "'. ")]
    (if (and polyglot jdk (not= polyglot jdk))
      (str head "The org.graalvm.* artifacts are " polyglot " but the JDK's "
           "libgraal is " jdk ". Truffle needs both at the same version. "
           "Align the org.graalvm.* :mvn/version pins in deps.edn with the "
           "GraalVM CE JDK the flake provides, or move the JDK to " polyglot ".")
      (str head "The org.graalvm.* artifacts (" polyglot ") match the JDK, so "
           "the version check is not the cause. Ensure "
           "org.graalvm.truffle/truffle-runtime is on the path."))))

(deftest optimizing-runtime-active
  (let [runtime (nw/truffle-runtime-name)]
    (if graalvm-jdk?
      (testing "on a GraalVM JDK, Truffle selects an optimizing (guest-JIT) runtime"
        ;; Only diagnose on failure: building an Engine costs real time, and
        ;; `is` evaluates its message whether or not the assertion passes.
        (if (= "Interpreted" runtime)
          (is false (diagnose-interpreted runtime))
          (is (not= "Interpreted" runtime))))
      (testing "not a GraalVM JDK: guest JIT not applicable (FFI-only)"
        (is true (str "runtime: " runtime " (informational; run on GraalVM CE "
                      "for guest JIT)"))))))
