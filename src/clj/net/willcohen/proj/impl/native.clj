;; Copyright (c) 2024, 2025, 2026 Will Cohen
;;
;; Part of clj-proj, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns net.willcohen.proj.impl.native
  "Native FFI backend. Extracts the platform PROJ library through
  clj-native and defines the dt-ffi bindings from fndefs."
  (:require [net.willcohen.native.platform :as nplatform]
            [net.willcohen.proj.fndefs :as fn-defs-data]))

(set! *warn-on-reflection* true)

(def fn-defs (nplatform/rehydrate-fn-defs fn-defs-data/fndefs))

;; Holds {:file :path :libname :singleton}, or {} when extraction failed.
;; PROJ reads proj.db, proj.ini, and the grid files from the directory
;; that holds the extracted library. Thus they come out adjacent to it,
;; and proj.cljc passes that :path as the database path.
(def proj
  (atom (nplatform/extract-and-bind-library!
         {:lib-basename    "libproj"
          :tmp-prefix      "proj"
          :fn-defs-var     #'fn-defs
          :extra-resources [{:resource "proj.db"}
                            {:resource "proj.ini"}
                            {:resource-dir "grids/"}]})))

(defn init-proj
  []
  ;; The @proj atom already holds the extracted files. This call does the
  ;; final load, selects :jdk, and binds the singleton to the absolute
  ;; path of the library.
  (nplatform/init-jdk-library! (:singleton @proj) (:file @proj)))

;; No PROJ fn-def sets :check-error?, so no error-check fn is given.
(nplatform/define-library-fns! fn-defs proj)
