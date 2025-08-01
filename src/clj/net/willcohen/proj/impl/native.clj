(ns net.willcohen.proj.impl.native
  (:require [tech.v3.datatype.ffi :as dt-ffi]
            [tech.v3.datatype.native-buffer :as dt-nb]
            [tech.v3.datatype.struct :as dt-struct]
            [net.willcohen.proj.impl.struct :as struct]
            [net.willcohen.proj.fndefs :as fn-defs-data]
            [clojure.java.io :as io]
            [clojure.string :as s])
  (:import [java.io File InputStream OutputStream]
           [java.nio.file Files Path]
           [java.net JarURLConnection]
           [com.sun.jna Native NativeLibrary]))

(def fn-defs fn-defs-data/fndefs)

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
      "amd64" :amd64
      "x86_64" :amd64
      "x86-64" :amd64
      "i386" :x86
      "i486" :x86
      "i586" :x86
      "i686" :x86
      "i786" :x86
      "i886" :x86
      "aarch64" :aarch64
      ; Default case - convert to keyword and handle unknown archs
      (keyword (s/replace arch #"[_-]" "")))))

(defn get-proj-filename
  [os]
  (case os
    :android "libproj"
    :darwin "libproj"
    :windows "proj" ; JNA on Windows expects just "proj" not "libproj"
    :linux "libproj"))

(defn get-libtiff-filename
  [os]
  (case os
    :android "libtiff"
    :darwin "libtiff"
    :windows "tifflib"
    :linux "libtiff"))

(defn get-proj-suffix
  [os]
  (case os
    :android ".a"
    :darwin ".dylib"
    :windows ".dll"
    :linux ".so"))

(defn get-libtiff-suffix
  [os]
  (case os
    :android ".a"
    :darwin ".dylib"
    :windows ".dll"
    :linux ".so"))

(defn copy-file
  [p f]
  (doto ^File f
    (.setReadable true)
    (.setWritable true true)
    (.setExecutable true true))
  (if-let [resource-url (io/resource p)]
    (with-open [in (.openStream resource-url)
                out (java.io.FileOutputStream. f)]
      (io/copy in out))
    (throw (java.io.FileNotFoundException. (str "Classpath resource not found: " p)))))

(defn tmp-dir
  []
  (let [tmp (Files/createTempDirectory "proj" (into-array java.nio.file.attribute.FileAttribute []))]
    tmp))

(defn locate-proj-file
  ([t]
   (locate-proj-file t (get-os) (get-arch)))
  ([t os arch]
   (let [suffix (get-proj-suffix os)
         dir-name (str (name os) "-" (name arch))
         file-name (str (get-proj-filename os) suffix)
         resource-path (str dir-name "/" file-name)
         tmp (File. (.toString t) file-name)]
     (doto tmp .deleteOnExit)
     (copy-file resource-path tmp)
     tmp)))

(defn locate-libtiff-file
  ([t]
   (locate-libtiff-file t (get-os) (get-arch)))
  ([t os arch]
   (let [suffix (get-libtiff-suffix os)
         dir-name (str (name os) "-" (name arch))
         file-name (str (get-libtiff-filename os) suffix)
         resource-path (str dir-name "/" file-name)
         tmp (File. (.toString t) file-name)]
     (doto tmp .deleteOnExit)
     (copy-file resource-path tmp)
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

(defn- list-resource-dir
  "Lists all file resources within a given directory path on the classpath,
  supporting both filesystem and JAR resources.
  Returns a sequence of relative file paths."
  [path]
  (let [url (io/resource path)]
    (when url
      (let [protocol (.getProtocol url)]
        (cond
          (= "file" protocol)
          (let [dir (io/file url)]
            (when (.isDirectory dir)
              (let [dir-path (.toPath dir)]
                (->> (file-seq dir)
                     (filter #(.isFile %))
                     (map #(str (.relativize ^Path dir-path (.toPath ^File %))))))))

          (= "jar" protocol)
          (let [^JarURLConnection conn (.openConnection url)
                jar-file (.getJarFile conn)
                entry-prefix (.getEntryName conn)]
            (->> (enumeration-seq (.entries jar-file))
                 (map #(.getName %))
                 (filter #(and (.startsWith % entry-prefix)
                               (not (.endsWith % "/")))) ; only files
                 (map #(subs % (count entry-prefix)))))

          :else
          (throw (ex-info (str "Unsupported resource protocol: " protocol) {:url url})))))))

(defn locate-grids
  "Copies the PROJ grid files from classpath resources into a 'grids'
  subdirectory within the specified temporary directory `t`."
  [t]
  (let [tmp-grids-dir (File. (.toString t) "grids")]
    (.mkdirs tmp-grids-dir)
    (when-let [grid-files (list-resource-dir "grids/")]
      (doseq [grid-file grid-files]
        (let [dest-file (File. tmp-grids-dir grid-file)]
          (io/make-parents dest-file)
          (copy-file (str "grids/" grid-file) dest-file))))))

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
                 _ (locate-grids tmpdir)
                 os (get-os)
                 ;; Only load separate libtiff for Darwin (macOS) - Linux has it statically linked
                 tf (when (= os :darwin) (locate-libtiff-file tmpdir))
                 p (.getCanonicalPath (.getParentFile pf))
                 t (when tf (.getCanonicalPath (.getParentFile tf)))
                 pl (-> (.getName pf)
                        (.replaceFirst "[.][^.]+$" "")
                        (.replaceFirst "lib" ""))
                 tl (when tf (-> (.getName tf)
                                 (.replaceFirst "[.][^.]+$" "")
                                 (.replaceFirst "lib" "")))
                 s (dt-ffi/library-singleton #'fn-defs)]
               ;; (when (dt-ffi/jdk-ffi?)
               ;;   (System/setProperty "java.library.path" (.toString tmpdir)))
             (when (dt-ffi/jna-ffi?) ;(not (dt-ffi/jdk-ffi?)))
               (System/setProperty "jna.library.path" (.toString tmpdir)))
             {:file pf :db pd :libtiff-file tf :path p :libtiff-path t :singleton s :libname pl
              :libname-libtiff tl})
           (catch Exception _ proj))))

(defn init-proj
  []
  (init-ffi!)
  ;; The @proj atom is already configured by the top-level swap! which
  ;; handles copying all necessary native files. This call just triggers
  ;; the final library loading by the FFI implementation.
  (dt-ffi/library-singleton-set! (:singleton @proj) (:libname @proj)))

(defn reset-proj
  []
  (dt-ffi/library-singleton-reset! (:singleton @proj)))

(dt-ffi/define-library-functions
  fn-defs
  #(dt-ffi/library-singleton-find-fn (:singleton @proj) %)
  dt-ffi/check-error)
