;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-proj, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns kondo-fndefs
  "Copy the PROJ fn-defs data into the exported clj-kondo hook namespace.

   A clj-kondo hook runs in SCI and cannot read a var out of the project it
   lints, so `hooks.fn-defs` holds a copy of the data in
   `net.willcohen.proj.fndefs`. The copy drifts silently: a new fndefs entry
   reads as `Unresolved var: proj/proj-<name>` in every consumer.

   The copy holds the constants plus the fn-defs map. `:argsemantics`
   defaults name the constants, so the map does not read without them.

   Generic over the source and target, so clj-gdal can call it for its own
   fndefs copy."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

(def begin-marker
  ";; >>> BEGIN GENERATED -- run `bb kondo:fndefs` after editing fndefs.cljc")

(def end-marker
  ";; <<< END GENERATED -- the helpers below are hand-written")

(defn data-text
  "Every top-level form in `src` after the ns form, verbatim.

   Verbatim because the inline comments carry the C signatures, and a
   read-then-print round trip drops them."
  [src]
  (let [children (n/children (p/parse-file-all (str src)))
        forms (drop-while n/whitespace-or-comment? children)]
    (when (empty? forms)
      (throw (ex-info "No form found" {:src (str src)})))
    (str/triml (apply str (map n/string (rest forms))))))

(defn rename-def
  "Rename the top-level `(def from ...)` to `(def to ...)`.

   The hook namespace calls the var `fn-defs`. The source calls it `fndefs`."
  [text from to]
  (let [pattern (re-pattern (str "\\(def\\s+" (java.util.regex.Pattern/quote (str from)) "(?=\\s)"))]
    (when-not (re-find pattern text)
      (throw (ex-info "Source var not found" {:var from})))
    (str/replace-first text pattern (str "(def " to))))

(defn splice
  "Replace the marked region of `target-text` with `data`."
  [target-text data]
  (let [begin (str/index-of target-text begin-marker)
        end (str/index-of target-text end-marker)]
    (when-not (and begin end (< begin end))
      (throw (ex-info "Target is missing its generated-region markers"
                      {:begin-marker begin-marker :end-marker end-marker})))
    (str (subs target-text 0 (+ begin (count begin-marker)))
         "\n\n" data "\n"
         (subs target-text end))))

(defn sync!
  "Regenerate the marked region of `target` from `src`.

   With `:check?`, report staleness and exit 1 instead of a write. CI uses
   the check because a run that regenerates the copy passes against a stale
   tree."
  [{:keys [src target from to check?]}]
  (let [target-text (slurp (str target))
        updated (splice target-text (rename-def (data-text src) from to))]
    (cond
      (= target-text updated)
      (println (str target " is up to date"))

      check?
      (binding [*out* *err*]
        (println (str target " is stale against " src))
        (println "run `bb kondo:fndefs` and commit the result")
        (System/exit 1))

      :else
      (do (spit (str target) updated)
          (println (str "wrote " target " from " src))))))

(defn -main [& args]
  (sync! {:src (fs/path "src" "cljc" "net" "willcohen" "proj" "fndefs.cljc")
          :target (fs/path "resources" "clj-kondo.exports" "net.willcohen" "proj"
                           "hooks" "fn_defs.clj")
          :from 'fndefs
          :to 'fn-defs
          :check? (contains? (set args) "--check")}))
