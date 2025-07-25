;; ONLY macros here - NO runtime logic
;; ALL runtime functions go in proj.cljc

(ns net.willcohen.proj.macros
  "Macros for proj.cljc - Clojure side.
   Clean macros with minimal dependencies for separation.")

;; Helper functions used at macro expansion time only

(defn- c-name->clj-name [c-fn-keyword]
  (symbol (clojure.string/replace (name c-fn-keyword) "_" "-")))

;; Simplified macro that generates minimal wrapper
(defmacro define-proj-public-fn [fn-key]
  (let [fn-name (c-name->clj-name fn-key)]
    `(defn ~fn-name
       ([] (~fn-name {}))
       ([opts#]
        ;; Look up fn-def once and pass it through
        (let [fn-def# (get net.willcohen.proj.fndefs/fndefs ~fn-key)]
          (if fn-def#
            (net.willcohen.proj.proj/dispatch-proj-fn ~fn-key fn-def# opts#)
            (throw (ex-info "Function definition not found"
                            {:fn-key ~fn-key :fn-name '~fn-name}))))))))

;; TODO: Remove this silly exclude thing, we're not doing that anymore.

(defmacro define-all-proj-public-fns [macro-log-level & {:keys [exclude]
                                                         :or {exclude #{}}}]
  (require 'net.willcohen.proj.fndefs)
  (let [fndefs-var (resolve 'net.willcohen.proj.fndefs/fndefs)
        fndefs (when fndefs-var @fndefs-var)]
    (if fndefs
      `(do
         ~@(for [[fn-key _] fndefs
                 :when (not (contains? exclude fn-key))]
             `(define-proj-public-fn ~fn-key))
         nil)
      `(throw (ex-info "Could not resolve fndefs" {})))))

;; WASM-specific macros

(defmacro tsgcd
  "thread-safe graal context do"
  [body]
  `(locking net.willcohen.proj.wasm/context
     ~body))

(defmacro with-allocated-string
  "Executes body with a string allocated on the Emscripten heap.
  Binds the pointer to sym and ensures it's freed afterwards."
  [[sym s] & body]
  `(let [s# ~s
         ~sym (net.willcohen.proj.wasm/allocate-string-on-heap s#)]
     (try
       ~@body
       (finally
         (net.willcohen.proj.wasm/free-on-heap ~sym)))))

(defmacro def-wasm-fn
  "Defines a wrapper function for a PROJ C API call via WASM."
  [fn-name fn-key fn-defs-provided]
  (require 'net.willcohen.proj.fndefs)
  (let [fn-defs (or fn-defs-provided
                    (when-let [v (resolve 'net.willcohen.proj.fndefs/fndefs)] @v))
        fn-def (get fn-defs fn-key)
        _ (when-not fn-def
            (throw (ex-info (str "No fn-def found for key: " fn-key) {:fn-key fn-key})))
        arg-symbols (mapv (comp symbol first) (:argtypes fn-def))]
    `(defn ~fn-name [~@arg-symbols]
       ;; Look up once at runtime
       (let [fn-def# (get net.willcohen.proj.fndefs/fndefs ~fn-key)]
         (net.willcohen.proj.wasm/def-wasm-fn-runtime ~fn-key fn-def# [~@arg-symbols])))))

(defmacro define-all-wasm-fns [fn-defs-sym c-name->clj-name-sym]
  `(doseq [[fn-key# fn-def#] ~fn-defs-sym]
     (let [fn-name# (~c-name->clj-name-sym fn-key#)]
       (intern *ns* fn-name#
               (fn [& args#]
                 ;; fn-def# is already bound from doseq
                 (net.willcohen.proj.wasm/def-wasm-fn-runtime fn-key# fn-def# (vec args#)))))))
