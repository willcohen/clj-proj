;; Copyright (c) 2024, 2025, 2026 Will Cohen
;;
;; Part of clj-proj, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

;; Macros for proj.cljc and wasm.cljc. They bind clj-native's generic
;; name-mapping helpers to the PROJ runtime and fndefs.
;;
;; squint 0.11.187+ auto-loads .cljc macros through a plain `:require` (no
;; `:require-macros` necessary) and reads the required fndefs at expansion
;; time, so one file serves the two platforms. The :cljs arm expands against
;; the live data, so this file holds no second copy of the fn keys. The PJ_
;; and PROJ_ constants become integers when the fndefs namespace loads.
;;
;; The clj-kondo hook holds the one real copy of the data. It cannot read a
;; var out of the project it lints, so it carries its own snapshot.
;; `bb kondo:fndefs` regenerates it, and `bb lint` fails when it is stale.
#?(:clj
   (ns net.willcohen.proj.macros
     (:require [net.willcohen.native.macros :as nmac]))
   :cljs
   (ns macros
     (:require [fndefs :as pdefs]
               [net.willcohen.native.macros :as nmac
                :refer [c-name->clj-name underscore->camelCase]])))

#?(:clj (set! *warn-on-reflection* true))

;; Generates the public PROJ surface from fndefs. clj-native owns the pass
;; over fndefs, the name mapping, and the JVM intern loop. Only the shape of
;; one wrapper fn is PROJ's, so only that shape is here. CLJS emits
;; kebab-case and camelCase defns. The JVM interns at load time, because the
;; JVM reader cannot see the cljs fndefs shape at expansion time.
(defmacro define-all-proj-public-fns
  [_macro-log-level]
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
                  ~(str "PROJ function " fn-name " - see PROJ documentation")
                  ([] (~fn-name {}))
                  ([opts#]
                   (~'dispatch-proj-fn ~fn-key '~fn-def opts#))))
             ;; camelCase second spelling for JS callers. A fn whose C name
             ;; carries no underscore already reads as camelCase, so it
             ;; gets no alias.
             :alias-name-fn
             (fn [fn-key]
               (let [clj-name (c-name->clj-name fn-key)
                     camel-name (with-meta (symbol (underscore->camelCase (name fn-key)))
                                  {:async true})]
                 (when (not= clj-name camel-name) camel-name)))
             :alias-emit-fn
             (fn [fn-name fn-key fn-def]
               `(defn ~fn-name
                  ([] (~fn-name {}))
                  ([opts#]
                   (~'dispatch-proj-fn ~fn-key '~fn-def opts# :camel))))})))
