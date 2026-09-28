(ns hooks.proj
  "PROJ-specific clj-kondo hooks. The node-building logic is generic and lives
   in clj-native (hooks.native.generated); only the fndefs copy and the PROJ
   docstring shape are per-consumer."
  (:require [clj-kondo.hooks-api :as api]
            [hooks.native.generated :as gen]
            ;; A copy of the C function definitions. A hook cannot read a var
            ;; out of the project it lints, so the data is duplicated here.
            [hooks.fn-defs :as pdefs]))

(defn- fn-key->defn-node
  "Build the defn node for one PROJ fn key, or nil when the key is unknown."
  [fn-key]
  (when-let [fn-def (get pdefs/fn-defs fn-key)]
    (let [public-name (or (:public-name fn-def) (pdefs/c-name->clj-name fn-key))
          is-context-fn? (pdefs/is-c-context-fn? fn-key fn-def)]
      (gen/defn-node public-name
        (pdefs/generate-docstring fn-key fn-def is-context-fn?)
        ;; The generated bodies are nil, so a plain `opts` reads as an unused
        ;; binding in every consumer that lints these fns.
        [[] ['_opts]]))))

(defn define-all-proj-public-fns
  "Expand `define-all-proj-public-fns` into a `do` of one `defn` for each
  entry of hooks.fn-defs."
  [_]
  (let [defn-nodes (->> (keys pdefs/fn-defs)
                        (map fn-key->defn-node)
                        (remove nil?))
        do-node (api/list-node (cons (api/token-node 'do) defn-nodes))]
    {:node do-node}))