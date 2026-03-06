(ns net.willcohen.proj.impl.logging
  "JNA callback for PROJ logging. Routes PROJ's C-level log messages to the JVM.

  Provides a JNA Callback matching PROJ's PJ_LOG_FUNC signature (void*, int, const char*).
  Uses CallbackReference.getFunctionPointer to create a stable native function pointer
  from a JNA Callback interface. The callback and its pointer are held in atoms to
  prevent GC (JNA requirement -- if the callback is collected, the function pointer
  becomes invalid).

  Only used for the FFI backend. GraalVM uses PROJ's default logging (output via the
  polyglot context's stdout/stderr). Browser/Node.js workers set up logging via
  addFunction in proj-worker.mjs."
  (:require [net.willcohen.proj.fndefs :as fndefs]
            [tech.v3.datatype.ffi.ptr-value :as ptr-value]
            [clojure.tools.logging :as log])
  (:import [com.sun.jna Callback CallbackReference Pointer NativeLibrary Function]))

(def ^:dynamic *runtime-log-level*
  "When non-nil, PROJ log messages are output at this level (e.g. :debug, :trace).
   When nil, only errors are logged."
  nil)

;; void (*PJ_LOG_FUNC)(void *user_data, int level, const char *message)
(gen-interface
 :name net.willcohen.proj.impl.ProjLogCallback
 :extends [com.sun.jna.Callback]
 :methods [[invoke [com.sun.jna.Pointer int String] void]])

(defn create-log-callback
  "Creates a JNA Callback implementing ProjLogCallback interface.
  PROJ log levels: 1=ERROR, 2=DEBUG, 3=TRACE. The *runtime-log-level* dynamic var
  gates non-error output: when nil, only errors are logged; when set (e.g. :debug),
  all levels are logged at that Clojure log level."
  ([]
   (create-log-callback
    (fn [level msg]
      (case (long level)
        1 (log/error msg)
        2 (when *runtime-log-level* (log/log *runtime-log-level* msg))
        3 (when *runtime-log-level* (log/log *runtime-log-level* msg))
        (when *runtime-log-level* (log/log *runtime-log-level* msg))))))
  ([log-fn]
   (reify net.willcohen.proj.impl.ProjLogCallback
     (invoke [_ _user-data level message]
       (when message
         (log-fn level message))))))

;; Must be kept alive to prevent GC
(defonce ^:private log-callback-holder (atom nil))
(defonce ^:private log-callback-ptr-holder (atom nil))

(defn get-log-callback
  "Returns a raw native pointer (long) suitable for passing to PROJ's proj_log_func.
  Caches the callback and pointer in atoms -- JNA callbacks must stay alive to prevent
  GC from invalidating the function pointer. Thread-safe: returns cached pointer on
  subsequent calls."
  ([]
   (get-log-callback nil))
  ([log-fn]
   (if-let [ptr @log-callback-ptr-holder]
     ptr
     (let [cb (if log-fn
                (create-log-callback log-fn)
                (create-log-callback))
           jna-ptr (CallbackReference/getFunctionPointer cb)
           raw-ptr (Pointer/nativeValue jna-ptr)]
       (reset! log-callback-holder cb)
       (reset! log-callback-ptr-holder raw-ptr)
       raw-ptr))))

(defn setup-logging!
  "Set up PROJ logging callback on a context via direct JNA NativeLibrary.getFunction.
  Bypasses the dtype-next FFI layer because proj_log_func takes a callback pointer,
  not a regular FFI argument. Sets default log level to PJ_LOG_ERROR; bind
  *runtime-log-level* for more verbose output."
  [ctx-ptr]
  (let [cb @log-callback-holder
        cb (or cb (do (get-log-callback) @log-callback-holder))
        raw-ctx (ptr-value/ptr-value ctx-ptr)
        lib (NativeLibrary/getInstance "proj")
        log-func (.getFunction lib "proj_log_func")
        log-level (.getFunction lib "proj_log_level")]
    (.invoke log-func Void/TYPE (object-array [(Pointer. raw-ctx) nil cb]))
    ;; Default to ERROR level - set *runtime-log-level* to :info or :debug for more output
    (.invoke log-level Integer/TYPE (object-array [(Pointer. raw-ctx) (int fndefs/PJ_LOG_ERROR)]))))
