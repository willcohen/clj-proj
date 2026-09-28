;; Copyright (c) 2024, 2025, 2026 Will Cohen
;;
;; Part of clj-proj, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

;; Macros for proj.cljc. squint loads this .cljc through a plain :require
;; and expands the :cljs arm against the loaded fndefs. The clj-kondo hook
;; keeps a generated copy of fndefs; `bb kondo:fndefs` rewrites it.
#?(:clj
   (ns net.willcohen.proj.macros
     (:require [net.willcohen.native.macros :as nmac]))
   :cljs
   (ns macros
     (:require [fndefs :as pdefs]
               [net.willcohen.native.macros :as nmac
                :refer [c-name->clj-name underscore->camelCase]])))

#?(:clj (set! *warn-on-reflection* true))

(defmacro define-all-proj-public-fns
  "Define one public fn per fndefs entry, plus a camelCase alias on CLJS."
  []
  #?(:clj `(nmac/intern-library-fns!
            (ns-name *ns*)
            net.willcohen.proj.fndefs/fndefs
            nmac/c-name->clj-name
            (fn [fn-key# fn-def#]
              (fn proj-fn#
                ([] (proj-fn# {}))
                ([opts#] (~'dispatch-proj-fn fn-key# fn-def# opts#)))))
     :cljs (nmac/library-fns-form
            pdefs/fndefs
            {:name-fn c-name->clj-name
             :emit-fn
             (fn [fn-name fn-key fn-def]
               `(defn ~fn-name
                  ([] (~fn-name {}))
                  ([opts#]
                   (~'dispatch-proj-fn ~fn-key '~fn-def opts#))))
             :alias-name-fn
             (fn [fn-key]
               (with-meta (symbol (underscore->camelCase (name fn-key))) {:async true}))
             :alias-emit-fn
             (fn [fn-name fn-key fn-def]
               `(defn ~fn-name
                  ([] (~fn-name {}))
                  ([opts#]
                   (~'dispatch-proj-fn ~fn-key '~fn-def opts# :camel))))})))
