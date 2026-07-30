(ns hooks.test-macros
  "clj-kondo hooks for the test-only macros in proj_test.cljc.

   with-test-context binds its symbol through a macro, which clj-kondo does
   not expand. Without a hook, every use of the bound symbol reads as
   `Unresolved symbol`."
  (:require [clj-kondo.hooks-api :as api]))

(defn with-test-context
  "Rewrite (with-test-context [ctx] body...) to (let [ctx nil] body...).

   Both platform arms take the same [[ctx-binding] & body] shape: the :clj
   macro in proj_test.cljc and the :cljs macro in proj_test_macros.cljc."
  [{:keys [node]}]
  (let [[binding-vec & body] (rest (:children node))
        ctx-sym (first (:children binding-vec))]
    {:node (api/list-node
            (list* (api/token-node 'let)
                   (api/vector-node [ctx-sym (api/token-node nil)])
                   body))}))
