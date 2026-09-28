;; Copyright (c) 2024, 2025, 2026 Will Cohen
;;
;; Part of clj-proj, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns net.willcohen.proj.impl.logging
  "PROJ logging callback for the FFI backend, a Panama upcall of PJ_LOG_FUNC.
  GraalVM keeps PROJ's default logging, and the Node.js and browser workers
  set up theirs in proj-handler-overrides.mjs."
  (:require [net.willcohen.proj.fndefs :as fndefs]
            [net.willcohen.native.callbacks :as cb]
            [net.willcohen.native.platform :as nplatform]
            [tech.v3.datatype.ffi :as dt-ffi]
            [tech.v3.datatype.ffi.ptr-value :as ptr-value]
            [clojure.tools.logging :as log]))

(set! *warn-on-reflection* true)

(def ^:private nfn
  "Resolve a generated dt-ffi native fn by fndef key (interned at load time)."
  (nplatform/make-native-fn-resolver 'net.willcohen.proj.impl.native))

(def ^:dynamic *runtime-log-level*
  "When non-nil, PROJ log messages go out at this level (for example
   :debug or :trace). When nil, only errors are logged."
  nil)

;; void (*PJ_LOG_FUNC)(void *user_data, int level, const char *message)
(def ^:private log-iface
  (delay (cb/define-callback-interface :void [:pointer :int32 :pointer])))

(defn default-log-fn
  "PROJ log levels: 1=ERROR, 2=DEBUG, 3=TRACE. Errors always log.
  *runtime-log-level* gates non-error output."
  [level msg]
  (case (long level)
    1 (log/error msg)
    (when *runtime-log-level* (log/log *runtime-log-level* msg))))

(defn- log-upcall
  "Adapt a (level, message) handler into the PJ_LOG_FUNC IFn. A NULL message
  is ignored."
  [log-fn]
  (fn [_user-data level msg-ptr]
    (when (and msg-ptr (not (zero? (ptr-value/ptr-value msg-ptr))))
      (log-fn level (dt-ffi/c->string msg-ptr)))))

(defonce ^:private log-callback-holder (atom nil))

(defn get-log-callback
  "Return a dt-ffi Pointer to the log callback, applicable to
  proj_log_func. The first call creates and caches the callback, and
  later calls return that instance and ignore log-fn. The cache keeps
  the instance reachable, so the native function pointer stays valid.

  swap!, not check-then-reset!: pool workers create contexts concurrently,
  and a lost reset! would let GC free a stub that PROJ still calls."
  ([] (get-log-callback nil))
  ([log-fn]
   (:ptr (swap! log-callback-holder
                (fn [existing]
                  (or existing
                      (cb/register-callback!
                       @log-iface (log-upcall (or log-fn default-log-fn)))))))))

(defn setup-logging!
  "Install the PROJ logging callback on a context through proj_log_func,
  then set the default level to PJ_LOG_ERROR. Bind *runtime-log-level*
  for more output."
  [ctx-ptr]
  ((nfn :proj_log_func) ctx-ptr nil (get-log-callback))
  ((nfn :proj_log_level) ctx-ptr (int fndefs/PJ_LOG_ERROR)))
