(ns net.willcohen.proj.impl.native
  (:require [tech.v3.datatype.ffi :as dt-ffi]
            [tech.v3.datatype.native-buffer :as dt-nb]
            ;[tech.v3.datatype.struct :as dt-struct]
            [net.willcohen.proj.impl.struct :as struct]
            [clojure.java.io :as io]
            [clojure.string :as s])
  (:import [java.io File InputStream OutputStream]
           [java.nio.file Files]
           [com.sun.jna Native NativeLibrary]))

(def fn-defs
  {:proj_string_list_destroy {:rettype :void
                              :argtypes [['list :pointer]]}
   :proj_context_set_database_path {:rettype :int32
                                    :argtypes [['context :pointer]
                                               ['db-path :string]
                                               ['aux-db-paths :string]
                                               ['options :string]]}
   :proj_context_get_database_path {:rettype :string
                                    :argtypes [['context :pointer]]}
   :proj_context_get_database_metadata {:rettype :string
                                        :argtypes [['context :pointer]
                                                   ['key :string]]}
   :proj_context_get_database_structure {:rettype :pointer
                                         :argtypes [['context :pointer]
                                                    ['options :int32]]}
   :proj_context_guess_wkt_dialect {:rettype :int32
                                    :argtypes [['context :pointer]
                                               ['wkt :string]]}
   :proj_create_from_wkt {:rettype :pointer
                          :argtypes [['context :pointer]
                                     ['wkt :string]
                                     ['options :int32]
                                     ['out_warnings :int32]
                                     ['out_grammar_errors :int32]]}
   :proj_create_from_database {:rettype :pointer
                               :argtypes [['context :pointer]
                                          ['auth_name :string]
                                          ['code :string]
                                          ['category :int32]
                                          ['use-proj-alternative-grid-names :int32]
                                          ['options :int32]]}
   :proj_uom_get_info_from_database {:rettype :int32
                                     :argtypes [['context :pointer]
                                                ['auth_name :string]
                                                ['code :string]
                                                ['out_name :pointer]
                                                ['out_conv_factor :pointer]
                                                ['out_category :pointer]]}
   :proj_grid_get_info_from_database {:rettype :int32
                                      :argtypes [['context :pointer]
                                                 ['grid_name :string]
                                                 ['out_full_name :pointer]
                                                 ['out_package_name :pointer]
                                                 ['out_url :pointer]
                                                 ['out_direct_download :pointer]
                                                 ['out_open_license :pointer]
                                                 ['out_available :pointer]]}
   :proj_log_level {:rettype :int32
                    :argtypes [['context :pointer]
                               ['level :int32()]]}
   :proj_clone {:rettype :pointer
                :argtypes [['context :pointer]
                           ['p :pointer]]}
   ; skip proj_create_from_name
   ;
   ; stopped here with https://proj.org/development/reference/functions.html#c-api-for-iso-19111-functionality
   :proj_context_create {:rettype :pointer
                         :argtypes []}
   :proj_context_destroy {:rettype :void
                          :argtypes [['context :pointer]]}


   :proj_cs_get_type {:rettype :int32
                      :argtypes [['context :pointer]
                                 ['cs :pointer]]}
   :proj_destroy {:rettype :pointer
                  :argtypes [['pj :pointer]]}
   :proj_get_authorities_from_database {:rettype :pointer
                                        :argtypes [['context :pointer]]}
   :proj_get_codes_from_database {:rettype :pointer
                                  :argtypes [['context :pointer]
                                             ['auth_name :string]
                                             ['type :int32]
                                             ['allow_deprecated :int32]]}
   :proj_xy_dist {:rettype :float64
                  :argtypes [['a :pointer]
                             ['b :pointer]]}
   :proj_coord {:rettype :pointer
                :argtypes [['x :float64]
                           ['y :float64]
                           ['z :float64]
                           ['t :float64]]}

   :proj_create_crs_to_crs {:rettype :pointer
                            :argtypes [['context :pointer]
                                       ['source_crs :string]
                                       ['target_crs :string]
                                       ['area :int32]]}
   :proj_trans_array {:rettype :int32
                      :argtypes [['p :pointer]
                                 ['direction :int32]
                                 ['n :size-t]
                                 ['coord :pointer]]}})

(defn get-os
  []
  (let [vendor (s/lower-case (System/getProperty "java.vendor"))
        os (s/lower-case (System/getProperty "os.name"))]
    (cond (s/includes? vendor "android") :android
          (s/includes? os "mac") :darwin
          (s/includes? os "win") :windows
          :else :linux)))

(defn get-arch
  []
  (let [arch (System/getProperty "os.arch")]
    (case arch
      "amd64" :x64
      "x86_64" :x64
      "i386" :x86
      "i486" :x86
      "i586" :x86
      "i686" :x86
      "i786" :x86
      "i886" :x86
      "aarch64" :aarch64)))

(defn get-proj-filename
  [os]
  (case os
    :android "libproj"
    :darwin "libproj"
    :windows "projlib"
    :linux "libproj"))

(defn get-libtiff-filename
  [os]
  (case os
    :android "libtiff"
    :darwin "libtiff.6"
    :windows "tifflib"
    :linux "libtiff"))


(defn get-suffix
  [os]
  (case os
    :android ".a"
    :darwin ".dylib"
    :windows ".dlls"
    :linux ".so"))

(defn copy-file
  [p f]
  (doto ^File f
    (.setReadable true)
    (.setWritable true true)
    (.setExecutable true true))
  (with-open [in (.openStream (io/resource p))
              out (java.io.FileOutputStream. f)]
    (io/copy in out)))

(defn tmp-dir
  []
  (let [tmp (Files/createTempDirectory "proj" (into-array java.nio.file.attribute.FileAttribute []))]
    tmp))

(defn locate-proj-file
  ([t]
   (locate-proj-file t (get-os) (get-arch)))
  ([t os arch]
   (let [suffix (get-suffix os)
         d (s/join [(name os) "-" (name arch)])
         f (s/join [(get-proj-filename os) suffix])
         tmp (File. (.toString t) f)]
     (doto tmp .deleteOnExit)
     (copy-file (s/join ["" d "/" f]) tmp)
     tmp)))
()
(defn locate-libtiff-file
  ([t]
   (locate-libtiff-file t (get-os) (get-arch)))
  ([t os arch]
   (let [suffix (get-suffix os)
         d (s/join [(name os) "-" (name arch)])
         f (s/join [(get-libtiff-filename os) suffix])
         tmp (File. (.toString t) f)]
     (doto tmp .deleteOnExit)
     (copy-file (s/join ["" d "/" f]) tmp)
     tmp)))

(defn locate-proj-db
  ([t]
   (let [tmp (File. (.toString t) "proj.db")
         tmp-ini (File. (.toString t) "proj.ini")]
     (doto tmp .deleteOnExit)
     (doto tmp-ini .deleteOnExit)
     (copy-file "proj.db" tmp)
     (copy-file "proj.ini" tmp-ini)
     tmp)))


(defn init-ffi!
  []
  (dt-ffi/set-ffi-impl! :jna))
  ;; (try (dt-ffi/set-ffi-impl! :jdk)
  ;;      (catch Exception _
  ;;        (dt-ffi/set-ffi-impl! :jna))))

(def proj (atom {}))

(swap! proj
         (fn [proj]
           (try
             (let [tmpdir (tmp-dir)
                   pf (locate-proj-file tmpdir)
                   pd (locate-proj-db tmpdir)
                   tf (locate-libtiff-file tmpdir)
                   p (.getCanonicalPath (.getParentFile pf))
                   t (.getCanonicalPath (.getParentFile tf))
                   pl (-> (.getName pf)
                          (.replaceFirst "[.][^.]+$" "")
                          (.replaceFirst "lib" ""))
                   tl (-> (.getName tf)
                          (.replaceFirst "[.][^.]+$" "")
                          (.replaceFirst "lib" ""))
                   s (dt-ffi/library-singleton #'fn-defs)]
               ;; (when (dt-ffi/jdk-ffi?)
               ;;   (System/setProperty "java.library.path" (.toString tmpdir)))
               (when (and (dt-ffi/jna-ffi?) (not (dt-ffi/jdk-ffi?)))
                 (System/setProperty "jna.library.path" (.toString tmpdir)))
               {:file pf :db pd :libtiff-file tf :path p :libtiff-path t :singleton s :libname pl
                :libname-libtiff tl})
         (catch Exception _ proj))))

(defn init-proj
  []
  (init-ffi!)
  (dt-ffi/library-singleton-set! (:singleton @proj) (:libname @proj)))

(defn reset-proj
  []
  (dt-ffi/library-singleton-reset! (:singleton @proj)))


(dt-ffi/define-library-functions
  fn-defs
  #(dt-ffi/library-singleton-find-fn (:singleton @proj) %)
  dt-ffi/check-error)
