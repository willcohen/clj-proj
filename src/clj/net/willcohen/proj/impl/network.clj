(ns net.willcohen.proj.impl.network
  "Network callbacks for PROJ grid fetching via Java HttpClient.

  Two callback systems:
  - GraalVM WASM: ProxyExecutable callbacks bridged via C stubs and globalThis
  - JNA native: Callback interfaces registered via proj_context_set_network_callbacks

  Both share handle management, HttpClient, and range request logic.

  Four callbacks implement PROJ's network interface:
  - open:       Initial HTTP range request, returns handle
  - close:      Removes handle from state
  - get_header: Returns stored header value
  - read_range: Subsequent range requests using an existing handle

  Handle state (URL + response headers) is stored in the `handles` atom, keyed by
  integer handle ID."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [net.willcohen.proj.wasm :as wasm]
            [net.willcohen.proj.macros :refer [tsgcd]]
            [tech.v3.datatype.ffi.ptr-value :as ptr-value])
  (:import [com.sun.jna Callback CallbackReference Pointer NativeLibrary]
           [org.graalvm.polyglot.proxy ProxyExecutable]
           [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers]))

;; JNA callback interfaces for proj_context_set_network_callbacks (proj.h:516-553)
(gen-interface
 :name net.willcohen.proj.impl.ProjNetworkOpenCallback
 :extends [com.sun.jna.Callback]
 :methods [[invoke [com.sun.jna.Pointer String long long com.sun.jna.Pointer
                    com.sun.jna.Pointer long com.sun.jna.Pointer com.sun.jna.Pointer]
            com.sun.jna.Pointer]])

(gen-interface
 :name net.willcohen.proj.impl.ProjNetworkCloseCallback
 :extends [com.sun.jna.Callback]
 :methods [[invoke [com.sun.jna.Pointer com.sun.jna.Pointer com.sun.jna.Pointer] void]])

(gen-interface
 :name net.willcohen.proj.impl.ProjNetworkGetHeaderCallback
 :extends [com.sun.jna.Callback]
 :methods [[invoke [com.sun.jna.Pointer com.sun.jna.Pointer String com.sun.jna.Pointer] String]])

(gen-interface
 :name net.willcohen.proj.impl.ProjNetworkReadRangeCallback
 :extends [com.sun.jna.Callback]
 :methods [[invoke [com.sun.jna.Pointer com.sun.jna.Pointer long long com.sun.jna.Pointer
                    long com.sun.jna.Pointer com.sun.jna.Pointer]
            long]])

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

(defn- parse-headers [http-headers]
  (into {}
        (for [[k vs] (.map http-headers)]
          [(clojure.string/lower-case k) (first vs)])))

(defonce ^:private http-client
  (delay
    (-> (HttpClient/newBuilder)
        (.followRedirects HttpClient$Redirect/NORMAL)
        (.build))))

(defn- make-range-request [url offset size-to-read]
  (try
    (let [range-header (format "bytes=%d-%d" offset (+ offset size-to-read -1))
          request (-> (HttpRequest/newBuilder)
                      (.uri (URI. url))
                      (.header "Range" range-header)
                      (.GET)
                      (.build))
          response (.send @http-client request (HttpResponse$BodyHandlers/ofByteArray))]
      {:status (.statusCode response)
       :body (.body response)
       :headers (parse-headers (.headers response))})
    (catch Exception e
      (log/error e "Network request failed" {:url url})
      {:status 0 :body nil :headers {}})))

(defn- create-open-callback
  "Creates the 'open' ProxyExecutable for PROJ network access.
  Makes an initial HTTP range request for a grid file URL, copies response bytes
  into the WASM heap byte-by-byte via HEAPU8.setArrayElement (the only way to write
  to GraalVM WASM memory from Java), writes response size to out_size_ptr via
  Emscripten setValue, and returns a handle ID. The handle (URL + response headers)
  is stored in the `handles` atom for use by get_header and read_range."
  []
  (reify ProxyExecutable
    (execute [_ args]
      (try
        (let [url-ptr (.asLong (aget args 1))
              offset (.asLong (aget args 2))
              size-to-read (.asLong (aget args 3))
              buffer-ptr (.asLong (aget args 4))
              out-size-ptr (.asLong (aget args 5))
              module @wasm/p
              url (tsgcd
                   (.asString (.execute (.getMember module "UTF8ToString")
                                        (into-array Object [url-ptr]))))
              _ (log/debug "GRAAL-NET: open" {:url url :offset offset :size size-to-read})
              response (make-range-request url offset size-to-read)]
          (if (#{200 206} (:status response))
            (let [body (:body response)
                  bytes-read (if body (alength body) 0)]
              (when (and body (pos? bytes-read))
                (tsgcd
                 (let [heapu8 (.getMember module "HEAPU8")]
                   (dotimes [i bytes-read]
                     (.setArrayElement heapu8 (+ buffer-ptr i)
                                       (bit-and (aget body i) 0xFF))))))
              (tsgcd
               (.execute (.getMember module "setValue")
                         (into-array Object [out-size-ptr bytes-read "i32"])))
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
        (let [handle-id (.asInt (aget args 1))]
          (log/debug "GRAAL-NET: close" {:id handle-id})
          (close-handle! handle-id)
          nil)
        (catch Exception e
          (log/error e "GRAAL-NET: close failed")
          nil)))))

(defn- create-get-header-callback
  "Creates the 'get_header' ProxyExecutable for PROJ network access.
  Reads the header name from WASM memory via UTF8ToString, looks up the value in
  the handle's stored headers (from the initial open or most recent read_range),
  and writes the result string to WASM via stringToNewUTF8 (allocates on the WASM
  heap; caller frees)."
  []
  (reify ProxyExecutable
    (execute [_ args]
      (try
        (let [handle-id (.asInt (aget args 1))
              header-name-ptr (.asLong (aget args 2))
              module @wasm/p
              header-name (tsgcd
                           (.asString (.execute (.getMember module "UTF8ToString")
                                                (into-array Object [header-name-ptr]))))
              header-name-lower (clojure.string/lower-case header-name)
              handle (get-handle handle-id)
              header-value (get-in handle [:headers header-name-lower])]
          (log/debug "GRAAL-NET: getHeader" {:handle handle-id :name header-name :value header-value})
          (if header-value
            (tsgcd
             (let [ptr-val (.execute (.getMember module "stringToNewUTF8")
                                     (into-array Object [header-value]))]
               (.asInt ptr-val)))
            0))
        (catch Exception e
          (log/error e "GRAAL-NET: getHeader failed")
          0)))))

(defn- create-read-range-callback
  "Creates the 'read_range' ProxyExecutable for PROJ network access.
  Handles subsequent range requests for a resource already opened via open. Uses
  the same HTTP flow as open but with an existing handle. Updates stored headers
  after each request (Content-Range changes per request)."
  []
  (reify ProxyExecutable
    (execute [_ args]
      (try
        (let [handle-id (.asInt (aget args 1))
              offset (.asLong (aget args 2))
              size-to-read (.asLong (aget args 3))
              buffer-ptr (.asLong (aget args 4))
              handle (get-handle handle-id)
              url (:url handle)]
          (if url
            (do
              (log/debug "GRAAL-NET: readRange" {:handle handle-id :offset offset :size size-to-read})
              (let [response (make-range-request url offset size-to-read)]
                (if (#{200 206} (:status response))
                  (let [body (:body response)
                        bytes-read (if body (alength body) 0)
                        module @wasm/p]
                    (when (and body (pos? bytes-read))
                      (tsgcd
                       (let [heapu8 (.getMember module "HEAPU8")]
                         (dotimes [i bytes-read]
                           (.setArrayElement heapu8 (+ buffer-ptr i)
                                             (bit-and (aget body i) 0xFF))))))
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

(defn setup-network-callbacks!
  "Set up network callbacks for a GraalVM PROJ context.
  Sets ProxyExecutable callbacks on globalThis (the GraalVM JS bindings), then
  calls the C stub proj_setup_network_stubs which registers C-level callbacks
  with PROJ. The C stubs dispatch to globalThis at runtime, completing the bridge.
  Uses C stubs instead of addFunction, which corrupts GraalVM's WASM function table."
  [ctx-ptr]
  (log/info "Setting up GraalVM network callbacks...")
  (let [module @wasm/p
        _ (when (nil? module)
            (throw (ex-info "PROJ module not initialized - call proj/init! first" {})))
        js-bindings (tsgcd (.getBindings wasm/context "js"))
        open-cb (create-open-callback)
        close-cb (create-close-callback)
        header-cb (create-get-header-callback)
        read-cb (create-read-range-callback)]
    (tsgcd (.putMember js-bindings "__proj_net_open" open-cb))
    (tsgcd (.putMember js-bindings "__proj_net_close" close-cb))
    (tsgcd (.putMember js-bindings "__proj_net_get_header" header-cb))
    (tsgcd (.putMember js-bindings "__proj_net_read_range" read-cb))
    (let [result (tsgcd
                  (.asInt (.execute (.getMember module "ccall")
                                    (into-array Object
                                                ["proj_setup_network_stubs"
                                                 "number"
                                                 (into-array Object ["number"])
                                                 (into-array Object [ctx-ptr])]))))]
      (if (= result 1)
        (log/info "GraalVM network callbacks registered via C stubs")
        (log/warn "Failed to register network callbacks" {:result result}))
      result)))

;; --- JNA native callbacks (FFI backend) ---

(defn- write-error-string [out-error-string error-string-max-size msg]
  (when (and out-error-string (pos? error-string-max-size))
    (let [max-len (dec error-string-max-size)
          truncated (subs msg 0 (min (count msg) max-len))]
      (.setString out-error-string 0 truncated))))

(defn- create-native-open-callback []
  (reify net.willcohen.proj.impl.ProjNetworkOpenCallback
    (invoke [_ _ctx url offset size-to-read buffer out-size-read
             error-string-max-size out-error-string _user-data]
      (try
        (log/debug "JNA-NET: open" {:url url :offset offset :size size-to-read})
        (let [response (make-range-request url offset size-to-read)]
          (if (#{200 206} (:status response))
            (let [body (:body response)
                  bytes-read (if body (alength body) 0)]
              (when (and body (pos? bytes-read))
                (.write buffer 0 body 0 bytes-read))
              (.setLong out-size-read 0 bytes-read)
              (let [handle-id (create-handle! url (:headers response))]
                (log/debug "JNA-NET: opened" {:id handle-id :bytes bytes-read})
                (Pointer. (long handle-id))))
            (do
              (log/warn "JNA-NET: HTTP error" {:status (:status response) :url url})
              (write-error-string out-error-string error-string-max-size
                                  (str "HTTP " (:status response)))
              nil)))
        (catch Exception e
          (log/error e "JNA-NET: open failed")
          (write-error-string out-error-string error-string-max-size
                              (or (.getMessage e) "error"))
          nil)))))

(defn- create-native-close-callback []
  (reify net.willcohen.proj.impl.ProjNetworkCloseCallback
    (invoke [_ _ctx handle _user-data]
      (try
        (let [handle-id (Pointer/nativeValue handle)]
          (log/debug "JNA-NET: close" {:id handle-id})
          (close-handle! handle-id))
        (catch Exception e
          (log/error e "JNA-NET: close failed"))))))

(defn- create-native-get-header-callback []
  (reify net.willcohen.proj.impl.ProjNetworkGetHeaderCallback
    (invoke [_ _ctx handle header-name _user-data]
      (try
        (let [handle-id (Pointer/nativeValue handle)
              handle-data (get-handle handle-id)
              header-value (get-in handle-data [:headers (str/lower-case header-name)])]
          (log/debug "JNA-NET: getHeader" {:handle handle-id :name header-name :value header-value})
          header-value)
        (catch Exception e
          (log/error e "JNA-NET: getHeader failed")
          nil)))))

(defn- create-native-read-range-callback []
  (reify net.willcohen.proj.impl.ProjNetworkReadRangeCallback
    (invoke [_ _ctx handle offset size-to-read buffer
             error-string-max-size out-error-string _user-data]
      (try
        (let [handle-id (Pointer/nativeValue handle)
              handle-data (get-handle handle-id)
              url (:url handle-data)]
          (if url
            (do
              (log/debug "JNA-NET: readRange" {:handle handle-id :offset offset :size size-to-read})
              (let [response (make-range-request url offset size-to-read)]
                (if (#{200 206} (:status response))
                  (let [body (:body response)
                        bytes-read (if body (alength body) 0)]
                    (when (and body (pos? bytes-read))
                      (.write buffer 0 body 0 bytes-read))
                    (swap! handles assoc-in [handle-id :headers] (:headers response))
                    (long bytes-read))
                  (do
                    (log/warn "JNA-NET: readRange HTTP error" {:status (:status response)})
                    (write-error-string out-error-string error-string-max-size
                                        (str "HTTP " (:status response)))
                    0))))
            (do
              (log/warn "JNA-NET: readRange invalid handle" {:id handle-id})
              0)))
        (catch Exception e
          (log/error e "JNA-NET: readRange failed")
          (write-error-string out-error-string error-string-max-size
                              (or (.getMessage e) "error"))
          0)))))

(defonce ^:private native-callbacks-holder (atom nil))

(defn setup-native-network-callbacks!
  "Register JNA network callbacks with a native PROJ context.
  Uses proj_context_set_network_callbacks to provide HTTP via Java HttpClient."
  [ctx-ptr]
  (log/info "Setting up JNA network callbacks...")
  (let [raw-ctx (ptr-value/ptr-value ctx-ptr)
        open-cb (create-native-open-callback)
        close-cb (create-native-close-callback)
        header-cb (create-native-get-header-callback)
        range-cb (create-native-read-range-callback)]
    (reset! native-callbacks-holder [open-cb close-cb header-cb range-cb])
    (let [lib (NativeLibrary/getInstance "proj")
          set-callbacks (.getFunction lib "proj_context_set_network_callbacks")
          result (.invoke set-callbacks Integer/TYPE
                          (object-array [(Pointer. raw-ctx)
                                         open-cb close-cb header-cb range-cb
                                         nil]))]
      (if (= result 1)
        (log/info "JNA network callbacks registered")
        (log/warn "Failed to register JNA network callbacks" {:result result}))
      result)))
