;; Copyright (c) 2024, 2025, 2026 Will Cohen
;;
;; Part of clj-proj, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns net.willcohen.proj.impl.network
  "Network callbacks for PROJ grid fetch through Java HttpClient.

  Two callback systems:
  - GraalVM WASM: ProxyExecutable callbacks installed in the wasm function
    table through Module.addFunction
  - native FFI: callback interfaces registered through
    proj_context_set_network_callbacks

  The two share handle management, HttpClient, and range request logic.

  Four callbacks implement PROJ's network interface:
  - open:       initial HTTP range request, returns a handle
  - close:      removes the handle from state
  - get_header: returns a stored header value
  - read_range: later range requests with an existing handle

  Handle state (URL plus response headers) lives in the `handles` atom,
  keyed by integer handle ID."
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

(defn- make-range-request
  "Delegate the HTTP range GET to net.willcohen.native.http. Adapt its
  {:status :headers :body-bytes} shape to the {:status :headers :body}
  shape that the PROJ callbacks consume."
  [url offset size-to-read]
  (let [{:keys [status headers body-bytes]}
        (http/range-request {:url url :offset offset :size size-to-read})]
    (when (zero? status)
      (log/error "Network request failed" {:url url}))
    {:status status :headers headers :body body-bytes}))

(defn- create-open-callback
  "Create the 'open' ProxyExecutable for PROJ network access.
  It makes an initial HTTP range request for a grid file URL, copies the
  response bytes into the WASM heap through nw/heap-write-bytes!, writes
  the response size to out_size_ptr through Emscripten setValue, and
  returns a handle ID. The handle (URL plus response headers) goes into
  the `handles` atom for get_header and read_range.

  The callback closes over `module`, the module it was installed
  against: a pooled Context's callbacks must not reach the default
  module."
  [module]
  (reify ProxyExecutable
    (execute [_ args]
      (try
        (let [url-ptr (nw/value->long (aget args 1))
              offset (nw/value->long (aget args 2))
              size-to-read (nw/value->long (aget args 3))
              buffer-ptr (nw/value->long (aget args 4))
              out-size-ptr (nw/value->long (aget args 5))
              url (nw/utf8->string module url-ptr)
              _ (log/debug "GRAAL-NET: open" {:url url :offset offset :size size-to-read})
              response (make-range-request url offset size-to-read)]
          (if (#{200 206} (:status response))
            (let [^bytes body (:body response)
                  bytes-read (if body (alength body) 0)]
              (when (and body (pos? bytes-read))
                (nw/heap-write-bytes! module buffer-ptr body))
              (nw/module-execute module "setValue" [out-size-ptr bytes-read "i32"])
              (let [handle-id (create-handle! url (:headers response))]
                (log/debug "GRAAL-NET: opened" {:id handle-id :bytes bytes-read})
                handle-id))
            (do
              (log/warn "GRAAL-NET: HTTP error" {:status (:status response) :url url})
              0)))
        (catch Exception e
          (log/error e "GRAAL-NET: open failed")
          0)))))

(defn- create-close-callback []
  (reify ProxyExecutable
    (execute [_ args]
      (try
        (let [handle-id (nw/value->int (aget args 1))]
          (log/debug "GRAAL-NET: close" {:id handle-id})
          (close-handle! handle-id)
          nil)
        (catch Exception e
          (log/error e "GRAAL-NET: close failed")
          nil)))))

(defn- create-get-header-callback
  "Create the 'get_header' ProxyExecutable for PROJ network access.
  It reads the header name from WASM memory through UTF8ToString, looks
  up the value in the handle's stored headers (from the initial open or
  the most recent read_range), and writes the result string to WASM
  through stringToNewUTF8. That allocates on the WASM heap, and the
  caller frees it."
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
            (nw/module-execute module "stringToNewUTF8" [header-value] :int)
            0))
        (catch Exception e
          (log/error e "GRAAL-NET: getHeader failed")
          0)))))

(defn- create-read-range-callback
  "Create the 'read_range' ProxyExecutable for PROJ network access.
  It serves later range requests for a resource that open already
  opened, with the same HTTP flow as open but an existing handle. It
  updates the stored headers after each request, because Content-Range
  changes for each request."
  [module]
  (reify ProxyExecutable
    (execute [_ args]
      (try
        (let [handle-id (nw/value->int (aget args 1))
              offset (nw/value->long (aget args 2))
              size-to-read (nw/value->long (aget args 3))
              buffer-ptr (nw/value->long (aget args 4))
              handle (get-handle handle-id)
              url (:url handle)]
          (if url
            (do
              (log/debug "GRAAL-NET: readRange" {:handle handle-id :offset offset :size size-to-read})
              (let [response (make-range-request url offset size-to-read)]
                (if (#{200 206} (:status response))
                  (let [^bytes body (:body response)
                        bytes-read (if body (alength body) 0)]
                    (when (and body (pos? bytes-read))
                      (nw/heap-write-bytes! module buffer-ptr body))
                    (swap! handles assoc-in [handle-id :headers] (:headers response))
                    bytes-read)
                  (do
                    (log/warn "GRAAL-NET: readRange HTTP error" {:status (:status response)})
                    0))))
            (do
              (log/warn "GRAAL-NET: readRange invalid handle" {:id handle-id})
              0)))
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
  "Set up network callbacks for a GraalVM PROJ context.
  It installs the four ProxyExecutables in the wasm function table through
  Module.addFunction and registers those pointers with PROJ, so grid fetches
  skip PROJ's libcurl and XHR path."
  [ctx-ptr]
  (log/info "Setting up GraalVM network callbacks...")
  (let [module (or (some-> nw/*wasm-context* nw/get-module) @wasm/p)
        _ (when (nil? module)
            (throw (ex-info "PROJ module not initialized - call proj/init! first" {})))
        callbacks {:open       (create-open-callback module)
                   :close      (create-close-callback)
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

;; define-callback-interface compiles to a Panama upcall stub on the :jdk
;; backend. PROJ callback pointers and out-params flow through dt-ffi
;; Pointers and dtype native buffers. size_t and unsigned long long params
;; are modeled as :int64 (correct on 64-bit).

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

(defn- write-buffer!
  "Copy the bytes of body into the caller's buffer at buffer-ptr."
  [buffer-ptr ^bytes body]
  (ffi-mem/copy-bytes! (ffi-mem/ptr-addr buffer-ptr) body))

(defn- write-size!
  "Write v into out-size-ptr[0] as a native size_t (int64)."
  [out-size-ptr v]
  (ffi-mem/put-i64! (ffi-mem/ptr-addr out-size-ptr) 0 v))

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
  (try
    (let [url (dt-ffi/c->string url-ptr)
          _ (log/debug "NET: open" {:url url :offset offset :size size-to-read})
          response (make-range-request url offset size-to-read)]
      (if (#{200 206} (:status response))
        (let [^bytes body (:body response)
              bytes-read (if body (alength body) 0)]
          (when (and body (pos? bytes-read)) (write-buffer! buffer-ptr body))
          (write-size! out-size-ptr bytes-read)
          (let [handle-id (create-handle! url (:headers response))]
            (log/debug "NET: opened" {:id handle-id :bytes bytes-read})
            ;; PROJ treats the return as an opaque PROJ_NETWORK_HANDLE* and
            ;; only gives it back to close/get_header/read_range, which
            ;; recover the id with ptr-addr. PROJ never dereferences it.
            (dt-ffi/->pointer (long handle-id))))
        (do
          (log/warn "NET: HTTP error" {:status (:status response) :url url})
          (write-error-string! out-err-ptr err-max (str "HTTP " (:status response)))
          0)))
    (catch Exception e
      (log/error e "NET: open failed")
      (write-error-string! out-err-ptr err-max (or (.getMessage e) "error"))
      0)))

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

;; The returned C string must stay valid until PROJ reads it, before the
;; next callback on this thread. Keep the most recent native buffer
;; reachable, which prevents GC.
;;
;; One slot for each thread, because the workload pool runs a PROJ context
;; on every worker thread and grid fetches run concurrently. A single shared
;; slot let one thread drop the buffer another thread had just handed to
;; PROJ but that PROJ had not yet read.
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
  (try
    (let [handle-id (ffi-mem/ptr-addr handle-ptr)
          url (:url (get-handle handle-id))]
      (if url
        (do
          (log/debug "NET: readRange" {:handle handle-id :offset offset :size size-to-read})
          (let [response (make-range-request url offset size-to-read)]
            (if (#{200 206} (:status response))
              (let [^bytes body (:body response)
                    bytes-read (if body (alength body) 0)]
                (when (and body (pos? bytes-read)) (write-buffer! buffer-ptr body))
                (swap! handles assoc-in [handle-id :headers] (:headers response))
                (long bytes-read))
              (do
                (log/warn "NET: readRange HTTP error" {:status (:status response)})
                (write-error-string! out-err-ptr err-max (str "HTTP " (:status response)))
                0))))
        (do
          (log/warn "NET: readRange invalid handle" {:id handle-id})
          0)))
    (catch Exception e
      (log/error e "NET: readRange failed")
      (write-error-string! out-err-ptr err-max (or (.getMessage e) "error"))
      0)))

;; One registration serves every context. The four callbacks take the ctx
;; as their first argument and ignore it, and they read their per-request
;; state from the `handles` atom, so they hold nothing context-specific.
;;
;; The delay is load-bearing, not an optimization. Each registered callback
;; (a {:ptr :inst} from callbacks/register-callback!) must stay GC-reachable
;; while PROJ holds its function pointer, and PROJ holds it for the life of
;; every context the pointer was registered on. Registering per context into
;; a single holder kept only the newest set, so with two or more contexts --
;; which the workload pool always has, one for each worker thread -- every
;; earlier context pointed at upcall stubs that GC was free to collect.
;; defonce so an ns reload does not drop instances that live contexts use.
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
