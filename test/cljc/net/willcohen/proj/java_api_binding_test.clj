;; Copyright (c) 2024, 2025, 2026 Will Cohen
;;
;; Part of clj-proj, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns net.willcohen.proj.java-api-binding-test
  "Guard for the Java-to-Clojure var binding in PROJ.java.

   PROJ.java reaches every Clojure function by string name through
   `Clojure.var(NS, name)`. That call interns a missing var instead of
   failing, so a renamed or deleted Clojure var gives no compile-time
   and no lint signal. The Java side then fails at call time with
   `IllegalStateException: Attempting to call unbound fn`.

   This test reads the names out of PROJ.java and resolves each one
   against the namespace, so a rename fails here with the name in the
   message.

   JVM-only. The .clj extension keeps the file out of the squint
   compile. cognitect.test-runner finds it through `-d test/cljc/net`
   in the deps.edn :test alias."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [net.willcohen.proj.proj]))

(def ^:private proj-java-path "src/java/net/willcohen/proj/PROJ.java")

(defn- get-var-names
  "Return every name that PROJ.java passes to getVar."
  [source]
  (->> (re-seq (re-pattern "getVar\\(\"([^\"]+)\"\\)") source)
       (map second)
       distinct
       sort))

(deftest every-java-get-var-name-resolves
  (let [source-file (io/file proj-java-path)]
    (testing "PROJ.java is readable from the test working directory"
      (is (.exists source-file)
          (str "Cannot read " proj-java-path ". Run the tests from the "
               "repository root.")))
    (when (.exists source-file)
      (let [names (get-var-names (slurp source-file))]
        (testing "PROJ.java names at least one Clojure var"
          (is (seq names)
              "Found no getVar calls. The extraction pattern is stale."))
        (testing "every getVar name resolves in net.willcohen.proj.proj"
          (let [missing (remove #(ns-resolve 'net.willcohen.proj.proj (symbol %))
                                names)]
            (is (empty? missing)
                (str "PROJ.java calls getVar for vars that do not exist in "
                     "net.willcohen.proj.proj: " (str/join ", " missing)
                     ". Either restore the var or update PROJ.java."))))))))
