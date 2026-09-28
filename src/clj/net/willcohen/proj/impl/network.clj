;; Copyright (c) 2024, 2025, 2026 Will Cohen
;;
;; Part of clj-proj, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns net.willcohen.proj.impl.network
  "Network callbacks for PROJ grid fetch through Java HttpClient, for the
  GraalVM wasm backend (Module.addFunction) and the FFI backend (upcalls)."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [net.willcohen.native.http :as http]
            [net.willcohen.native.callbacks :as cb]
            [net.willcohen.native.ffi-mem :as ffi-mem]
            [net.willcohen.native.platform :as nplatform]
            [net.willcohen.proj.wasm :as wasm]
            [net.willcohen.native.graal-wasm :as nw]
            [tech.v3.datatype.ffi :as dt-ffi])
  (:import [org.graalvm.polyglot.proxy ProxyExecutable]))

(set! *warn-on-reflection* true)

(defonce ^:private handles (atom {}))
(defonce ^:private next-handle-id (atom 0))

(defn- create-handle! [url headers]
  (let [id (swap! next-handle-id inc)]
    (swap! handles assoc id {:url url :headers headers})
    id))

(defn- get-handle [id]
  (get @handles id))

(defn- close-handle! [id]
  (swap! handles dissoc id))

(defn- keep-header-ptr!
  "Record wasm string `ptr` under handle `id`, for free-header-ptrs!."
  [id ptr]
  (swap! handles (fn [hs] (if (contains? hs id)
                            (update-in hs [id :header-ptrs] (fnil conj []) ptr)
                            hs))))

(defn- free-header-ptrs!
  "Free on `module` each get_header string of handle `id`."
  [module id]
  (doseq [ptr (get-in @handles [id :header-ptrs])]
    (nw/module-execute module "_free" [ptr])))

(defn- fetch-range
  "GET size bytes of url from offset. On a 200 or 206, pass a non-empty body
  to write! and return [bytes-read headers]. Otherwise pass \"HTTP <status>\"
  to err! and return nil. write! and err! write the caller's memory, so they
  run only inside the callback."
  [tag url offset size write! err!]
  (let [{:keys [status headers ^bytes body-bytes]}
        (http/range-request {:url url :offset offset :size size})]
    (when (zero? status)
      (log/error "Network request failed" {:url url}))
    (if (#{200 206} status)
      (let [n (if body-bytes (alength body-bytes) 0)]
        (when (pos? n) (write! body-bytes))
        [n headers])
      (do (log/warn (str tag " HTTP error") {:status status :url url})
          (err! (str "HTTP " status))
          nil))))

(defn- open!
  "The body of both open callbacks. Returns the new handle id, or nil."
  [tag url offset size write! write-size! err!]
  (log/debug (str tag ": open") {:url url :offset offset :size size})
  (when-let [[n headers] (fetch-range (str tag ": open") url offset size write! err!)]
    (write-size! n)
    (let [id (create-handle! url headers)]
      (log/debug (str tag ": opened") {:id id :bytes n})
      id)))

(defn- read-range!
  "The body of both read_range callbacks. Returns the byte count, or 0. It
  replaces the stored headers, since Content-Range changes with each request."
  [tag id offset size write! err!]
  (if-let [url (:url (get-handle id))]
    (do (log/debug (str tag ": readRange") {:handle id :offset offset :size size})
        (if-let [[n headers] (fetch-range (str tag ": readRange") url offset size write! err!)]
          (do (swap! handles assoc-in [id :headers] headers)
              (long n))
          0))
    (do (log/warn (str tag ": readRange invalid handle") {:id id})
        0)))

(defn- create-open-callback
  "Create the 'open' ProxyExecutable. The callback closes over `module`,
  the module it was installed against: a pooled Context's callbacks must
  not reach the default module."
  [module]
  (reify ProxyExecutable
    (execute [_ args]
      (try
        (let [[_ url-ptr offset size buf out-size] (mapv nw/value->long args)]
          (or (open! "GRAAL-NET" (nw/utf8->string module url-ptr) offset size
                     #(nw/heap-write-bytes! module buf %)
                     #(nw/module-execute module "setValue" [out-size % "i32"])
                     (constantly nil))
              0))
        (catch Exception e
          (log/error e "GRAAL-NET: open failed")
          0)))))

(defn- create-close-callback
  "Create the 'close' ProxyExecutable. It frees the get_header strings of
  the handle on `module`, the module of its get_header callback."
  [module]
  (reify ProxyExecutable
    (execute [_ args]
      (try
        (let [handle-id (nw/value->int (aget args 1))]
          (log/debug "GRAAL-NET: close" {:id handle-id})
          (free-header-ptrs! module handle-id)
          (close-handle! handle-id)
          nil)
        (catch Exception e
          (log/error e "GRAAL-NET: close failed")
          nil)))))

(defn- create-get-header-callback
  "Create the 'get_header' ProxyExecutable. PROJ reads the string after the
  callback returns, so the string lives until the close of the handle, as
  the strings of PROJ's own callbacks do."
  [module]
  (reify ProxyExecutable
    (execute [_ args]
      (try
        (let [handle-id (nw/value->int (aget args 1))
              header-name-ptr (nw/value->long (aget args 2))
              header-name (nw/utf8->string module header-name-ptr)
              header-name-lower (str/lower-case header-name)
              handle (get-handle handle-id)
              header-value (get-in handle [:headers header-name-lower])]
          (log/debug "GRAAL-NET: getHeader" {:handle handle-id :name header-name :value header-value})
          (if header-value
            (let [ptr (nw/module-execute module "stringToNewUTF8" [header-value] :int)]
              (keep-header-ptr! handle-id ptr)
              ptr)
            0))
        (catch Exception e
          (log/error e "GRAAL-NET: getHeader failed")
          0)))))

(defn- create-read-range-callback
  "Create the 'read_range' ProxyExecutable."
  [module]
  (reify ProxyExecutable
    (execute [_ args]
      (try
        (let [[_ id offset size buf] (mapv nw/value->long args)]
          (read-range! "GRAAL-NET" id offset size
                       #(nw/heap-write-bytes! module buf %)
                       (constantly nil)))
        (catch Exception e
          (log/error e "GRAAL-NET: readRange failed")
          0)))))

;; ProxyExecutable is callable from JS, but addFunction wants a plain
;; function, so wrap it. The spread keeps every argument, since the arity
;; differs per callback.
(def ^:private add-function-src
  "(Module, f, sig) => Module.addFunction((...a) => f(...a), sig)")

(defn- add-function!
  "Install `cb` in the wasm function table under emscripten signature `sig`
  and return its table index. The wrapper fn evals per install into the
  Context that owns `module`: a Value from one Context is unusable in
  another, so a shared delay cannot serve pooled Contexts."
  [module cb sig]
  (let [shim (nw/module-eval-js module add-function-src "proj-net-addfunction.js")]
    (nw/value-execute shim [module cb sig] :int)))

;; Signatures are PROJ's own callback types under wasm32, where pointers and
;; size_t are i32 and `unsigned long long offset` is i64 (j). PROJ takes them
;; in this order, so the vector order is the call order below.
(def ^:private callback-signatures
  [[:open       "iiijiiiiii"]
   [:close      "viii"]
   [:get-header "iiiii"]
   [:read-range "iiijiiiii"]])

(defn setup-network-callbacks!
  "Set up network callbacks for a GraalVM PROJ context, so grid fetches skip
  PROJ's libcurl and XHR path."
  [ctx-ptr]
  (log/info "Setting up GraalVM network callbacks...")
  (let [module (or (some-> nw/*wasm-context* nw/get-module) @wasm/p)
        _ (when (nil? module)
            (throw (ex-info "PROJ module not initialized - call proj/init! first" {})))
        callbacks {:open       (create-open-callback module)
                   :close      (create-close-callback module)
                   :get-header (create-get-header-callback module)
                   :read-range (create-read-range-callback module)}
        pointers (mapv (fn [[k sig]] (add-function! module (callbacks k) sig))
                       callback-signatures)
        result (nw/value->int (nw/ccall module "proj_context_set_network_callbacks" "number"
                                 ["number" "number" "number" "number" "number" "number"]
                                 (into [ctx-ptr] (conj pointers 0))))]
    (if (= result 1)
      (log/info "GraalVM network callbacks registered through addFunction")
      (log/warn "Failed to register network callbacks" {:result result}))
    result))

;; size_t and unsigned long long are :int64, correct on 64-bit hosts only.

;; PROJ_NETWORK_HANDLE* open(ctx, const char* url, unsigned long long offset,
;;   size_t size_to_read, void* buffer, size_t* out_size_read,
;;   size_t error_string_max_size, char* out_error_string, void* user_data)
(def ^:private open-iface
  (delay (cb/define-callback-interface
           :pointer [:pointer :pointer :int64 :int64 :pointer :pointer :int64 :pointer :pointer])))
;; void close(ctx, PROJ_NETWORK_HANDLE*, void* user_data)
(def ^:private close-iface
  (delay (cb/define-callback-interface :void [:pointer :pointer :pointer])))
;; const char* get_header(ctx, PROJ_NETWORK_HANDLE*, const char* header_name, void* user_data)
(def ^:private header-iface
  (delay (cb/define-callback-interface :pointer [:pointer :pointer :pointer :pointer])))
;; size_t read_range(ctx, PROJ_NETWORK_HANDLE*, unsigned long long offset,
;;   size_t size_to_read, void* buffer, size_t error_string_max_size,
;;   char* out_error_string, void* user_data)
(def ^:private read-iface
  (delay (cb/define-callback-interface
           :int64 [:pointer :pointer :int64 :int64 :pointer :int64 :pointer :pointer])))

(def ^:private nfn
  "Resolve a generated dt-ffi native fn by fndef key (interned at load time)."
  (nplatform/make-native-fn-resolver 'net.willcohen.proj.impl.native))

(defn- write-error-string!
  "Write a null-terminated, truncated msg into the caller's out_error_string."
  [out-err-ptr max-size msg]
  (when (and out-err-ptr (pos? (ffi-mem/ptr-addr out-err-ptr)) (pos? (long max-size)) msg)
    (let [truncated (subs msg 0 (min (count msg) (dec (long max-size))))
          src (.getBytes ^String truncated "UTF-8")
          n (alength src)
          padded (java.util.Arrays/copyOf src (inc n))] ; trailing 0 terminator
      (ffi-mem/copy-bytes! (ffi-mem/ptr-addr out-err-ptr) padded))))

(defn- native-open
  [_ctx url-ptr offset size-to-read buffer-ptr out-size-ptr err-max out-err-ptr _user]
  (let [err! #(write-error-string! out-err-ptr err-max %)]
    (try
      (if-let [id (open! "NET" (dt-ffi/c->string url-ptr) offset size-to-read
                         #(ffi-mem/copy-bytes! (ffi-mem/ptr-addr buffer-ptr) %)
                         #(ffi-mem/put-i64! (ffi-mem/ptr-addr out-size-ptr) 0 %)
                         err!)]
        ;; PROJ treats the return as an opaque PROJ_NETWORK_HANDLE* and
        ;; only gives it back to close/get_header/read_range, which
        ;; recover the id with ptr-addr. PROJ never dereferences it.
        (dt-ffi/->pointer (long id))
        0)
      (catch Exception e
        (log/error e "NET: open failed")
        (err! (or (.getMessage e) "error"))
        0))))

(defn- native-close
  [_ctx handle-ptr _user]
  (try
    (let [handle-id (ffi-mem/ptr-addr handle-ptr)]
      (log/debug "NET: close" {:id handle-id})
      (close-handle! handle-id)
      nil)
    (catch Exception e
      (log/error e "NET: close failed")
      nil)))

;; PROJ copies the value before the next callback on its thread, so keep
;; the newest buffer for each thread reachable.
(defonce ^:private last-header-buf (ThreadLocal.))

(defn- native-get-header
  [_ctx handle-ptr name-ptr _user]
  (try
    (let [handle-id (ffi-mem/ptr-addr handle-ptr)
          header-name (dt-ffi/c->string name-ptr)
          header-value (get-in (get-handle handle-id)
                               [:headers (str/lower-case header-name)])]
      (log/debug "NET: getHeader" {:handle handle-id :name header-name :value header-value})
      (if header-value
        (let [cbuf (dt-ffi/string->c header-value)]
          (.set ^ThreadLocal last-header-buf cbuf)
          (dt-ffi/->pointer cbuf))
        0))
    (catch Exception e
      (log/error e "NET: getHeader failed")
      0)))

(defn- native-read-range
  [_ctx handle-ptr offset size-to-read buffer-ptr err-max out-err-ptr _user]
  (let [err! #(write-error-string! out-err-ptr err-max %)]
    (try
      (read-range! "NET" (ffi-mem/ptr-addr handle-ptr) offset size-to-read
                   #(ffi-mem/copy-bytes! (ffi-mem/ptr-addr buffer-ptr) %)
                   err!)
      (catch Exception e
        (log/error e "NET: readRange failed")
        (err! (or (.getMessage e) "error"))
        0))))

;; One registration serves every context: the callbacks ignore ctx and keep
;; request state in handles. PROJ holds these pointers for each context's
;; life, so the instances must stay reachable, across ns reloads too.
(defonce ^:private native-callbacks
  (delay {:open-cb   (cb/register-callback! @open-iface   native-open)
          :close-cb  (cb/register-callback! @close-iface  native-close)
          :header-cb (cb/register-callback! @header-iface native-get-header)
          :read-cb   (cb/register-callback! @read-iface   native-read-range)}))

(defn setup-native-network-callbacks!
  "Register the network callbacks with a native PROJ context through
  proj_context_set_network_callbacks. Java HttpClient serves the HTTP
  requests through net.willcohen.native.http."
  [ctx-ptr]
  (log/info "Setting up native network callbacks...")
  (let [{:keys [open-cb close-cb header-cb read-cb]} @native-callbacks
        result ((nfn :proj_context_set_network_callbacks)
                ctx-ptr (:ptr open-cb) (:ptr close-cb)
                (:ptr header-cb) (:ptr read-cb) nil)]
    (if (= result 1)
      (log/info "Native network callbacks registered")
      (log/warn "Failed to register native network callbacks" {:result result}))
    result))
