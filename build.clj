(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'net.willcohen/proj)
(def version "0.1.0-alpha2")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))

;; delay to defer side effects (artifact downloads)
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn pom [_]
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
                            [:url "https://github.com/willcohen/clj-proj"]]]}))

(defn jar [_]
  (clean nil)
  (pom nil)
  ;; Compile Java sources
  (b/javac {:src-dirs ["src/java"]
            :class-dir class-dir
            :basis @basis
            :javac-opts ["--release" "21"]})
  (b/copy-dir {:src-dirs ["src/clj" "src/cljc" "resources"]
               :target-dir class-dir})
  ;; Delete npm/node related files that shouldn't be in the jar
  (b/delete {:path "target/classes/net/willcohen/proj/node_modules"})
  (b/delete {:path "target/classes/net/willcohen/proj/dist"})
  (b/delete {:path "target/classes/net/willcohen/proj/package.json"})
  (b/delete {:path "target/classes/net/willcohen/proj/package-lock.json"})
  (b/delete {:path "target/classes/net/willcohen/proj/esbuild.config.mjs"})
  (b/delete {:path "target/classes/net/willcohen/proj/.npmignore"})
  ;; Delete JavaScript build artifacts
  (b/delete {:path "target/classes/net/willcohen/proj/proj.mjs"})
  (b/delete {:path "target/classes/net/willcohen/proj/fndefs.mjs"})
  (b/delete {:path "target/classes/net/willcohen/proj/macros.mjs"})
  (b/delete {:path "target/classes/net/willcohen/proj/wasm.mjs"})
  (b/delete {:path "target/classes/net/willcohen/proj/proj-loader.mjs"})
  ;; Delete other non-jar files
  (b/delete {:path "target/classes/net/willcohen/proj/README.md"})
  (b/delete {:path "target/classes/net/willcohen/proj/LICENSE"})
  (b/delete {:path "target/classes/net/willcohen/proj/wasm.cljc.bak"})
  ;; Delete WASM files (they should be in resources/wasm if needed)
  (b/delete {:path "target/classes/net/willcohen/proj/proj-emscripten.js"})
  (b/delete {:path "target/classes/net/willcohen/proj/proj-emscripten.wasm"})
  ;; Delete clj-kondo exports
  (b/delete {:path "target/classes/clj-kondo.exports"})
  ;; Delete duplicate/misplaced files
  (b/delete {:path "target/classes/.keep"})
  (b/delete {:path "target/classes/net/willcohen/proj/proj"})  ; duplicate proj.db
  (b/delete {:path "target/classes/net/willcohen/proj/sqlite3.wasm"})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))