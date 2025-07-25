(ns test-ffi
  (:require [net.willcohen.proj.proj :as proj]))

(defn -main []
  (println "\n=== Testing FFI Implementation ===\n")

  ;; Force FFI before any initialization
  (proj/force-ffi!)
  (println "Forced FFI implementation")

  ;; Initialize
  (proj/init!)
  (println "Initialized PROJ")
  (println "Implementation:" @proj/implementation)
  (println "FFI?:" (proj/ffi?))
  (println "GraalVM?:" (proj/graal?))

  ;; Test basic functionality
  (let [ctx (proj/context-create)]
    (println "\nCreated context:" (boolean ctx))

    ;; Transform a coordinate
    (let [transformer (proj/proj-create-crs-to-crs {:context ctx
                                                    :source_crs "EPSG:4326"
                                                    :target_crs "EPSG:2249"})
          coord-array (proj/coord-array 1) ; Create array for 1 coordinate
          _ (proj/set-coords! coord-array [[42.3603222 -71.0579667 0 0]]) ; Boston City Hall (lat/lon order)
          _ (proj/proj-trans-array {:p transformer
                                    :direction 1 ; PJ_FWD
                                    :n 1
                                    :coord coord-array})]
      (let [x (get-in coord-array [0 0])
            y (get-in coord-array [0 1])]
        (println "Transformed Boston City Hall coordinates:" x y)))

    ;; Get some authorities
    (let [authorities (proj/proj-get-authorities-from-database {:context ctx})]
      (println "Found" (count authorities) "authorities")
      (println "First 3:" (take 3 authorities))))

  (println "\nFFI test completed successfully!"))