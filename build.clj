(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'net.willcohen/proj)
(def version "0.1.0-alpha1")
;(def version (format "1.2.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))

;; delay to defer side effects (artifact downloads)
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src/clj" "src/cljc" "src/java"]
                :pom-data [[:licenses
                            [:license
                             [:name "MIT License"]
                             [:url "https://opensource.org/license/mit"]
                             [:distribution "repo"]]]
                           [:description "This project provides a native (or transpiled) version of PROJ for both the JVM and JS ecosystems."]
                           [:developers
                            [:developer
                             [:name "Will Cohen"]]]
                           [:scm
                            [:url "https://github.com/willcohen/clj-proj"]]]})
  (b/copy-dir {:src-dirs ["src/clj" "src/cljc" "src/java" "resources"]
               :target-dir class-dir})
  (b/delete {:path "target/classes/net/willcohen/proj/node_modules"})
  (b/delete {:path "target/classes/net/willcohen/proj/package.json"})
  (b/delete {:path "target/classes/net/willcohen/proj/package-lock.json"})
  (b/delete {:path "target/classes/net/willcohen/proj/proj.mjs"})
  (b/delete {:path "target/classes/net/willcohen/proj/README.md"})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn javac [_]
 (b/javac {:src-dirs ["src/java"]
           :class-dir "classes"
           :javac-opts ["-source" "11" "-target" "11"]}))
