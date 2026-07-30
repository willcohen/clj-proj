;; Copyright (c) 2024, 2025, 2026 Will Cohen
;;
;; Part of clj-proj, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns net.willcohen.proj.impl.logging
  "PROJ logging callback for the FFI backend. Routes PROJ's C-level log
  messages to the JVM through a dt-ffi foreign interface that matches
  PROJ's PJ_LOG_FUNC signature (void*, int, const char*). The interface
  compiles to a Panama upcall stub on the :jdk backend.

  FFI backend only. GraalVM uses PROJ's default logging (output through
  the polyglot context's stdout/stderr). Browser and Node.js workers set
  up logging through addFunction in proj-handler-overrides.mjs."
  (:require [net.willcohen.proj.fndefs :as fndefs]
            [net.willcohen.native.callbacks :as cb]
            [net.willcohen.native.platform :as nplatform]
            [tech.v3.datatype.ffi :as dt-ffi]
            [tech.v3.datatype.ffi.ptr-value :as ptr-value]
            [clojure.tools.logging :as log]))

(set! *warn-on-reflection* true)

(def ^:private nfn
  "Resolve a generated dt-ffi native fn by fndef key.
  define-library-functions interns these at load time, so resolution
  occurs at runtime. proj.cljc's require graph loads impl.native
  transitively before any call here."
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

(defn- ptr->string
  "Read a (possibly null) C char* Pointer to a String."
  [p]
  (when (and p (not (zero? (ptr-value/ptr-value p))))
    (dt-ffi/c->string p)))

(defn- log-upcall
  "Adapt a (level, message-string) handler into the PJ_LOG_FUNC IFn. The
  C message arrives as a char* Pointer. Null messages are ignored."
  [log-fn]
  (fn [_user-data level msg-ptr]
    (when-let [msg (ptr->string msg-ptr)]
      (log-fn level msg))))

;; The registered callback (instance + pointer) must stay GC-reachable for as
;; long as PROJ holds the function pointer.
(defonce ^:private log-callback-holder (atom nil))

(defn get-log-callback
  "Return a dt-ffi Pointer to the log callback, applicable to
  proj_log_func. The first call creates and caches the callback, and
  later calls return that instance and ignore log-fn. The cache keeps
  the instance reachable, so the native function pointer stays valid.

  The swap! is what makes that safe under concurrency. proj/context-create
  calls setup-logging!, and the workload pool creates one context for each
  worker thread, so two threads can reach a nil holder together. A
  check-then-reset! there hands one thread a Pointer whose instance the
  other thread's reset! then drops, and the stub dangles once it is
  collected."
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
  (let [cb ((nfn :proj_log_func) ctx-ptr nil (get-log-callback))]
    ((nfn :proj_log_level) ctx-ptr (int fndefs/PJ_LOG_ERROR))
    cb))
