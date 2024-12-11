(ns net.willcohen.proj.impl.struct
  (:require [tech.v3.datatype.ffi.size-t :as ffi-size-t]
            [tech.v3.datatype.ffi.clang :as ffi-clang]
            [tech.v3.datatype.struct :as dt-struct]
            [camel-snake-kebab.core :as csk]
            [clojure.string :as s]))

(def crs-info-layout
  "      0 |   char * auth_name
         8 |   char * code
        16 |   char * name
        24 |   int type
        28 |   int deprecated
        32 |   int bbox_valid
        40 |   double west_lon_degree
        48 |   double south_lat_degree
        56 |   double east_lon_degree
        64 |   double north_lat_degree
        72 |   char * area_name
        80 |   char * projection_method_name
        88 |   char * celestial_body_name
           | [sizeof=96, dsize=96, align=8,
           |  nvsize=96, nvalign=8]")

(def crs-list-parameters-layout
  "      0 |   const int * types
         8 |   size_t typesCount
        16 |   int crs_area_of_use_contains_bbox
        20 |   int bbox_valid
        24 |   double west_lon_degree
        32 |   double south_lat_degree
        40 |   double east_lon_degree
        48 |   double north_lat_degree
        56 |   int allow_deprecated
        64 |   const char * celestial_body_name
           | [sizeof=72, dsize=72, align=8,
           |  nvsize=72, nvalign=8]")

(def unit-info-layout
  "      0 |   char * auth_name
         8 |   char * code
        16 |   char * name
        24 |   char * category
        32 |   double conv_factor
        40 |   char * proj_short_name
        48 |   int deprecated
           | [sizeof=56, dsize=56, align=8,
           |  nvsize=56, nvalign=8]")

(def celestial-body-info-layout
  "      0 |   char * auth_name
         8 |   char * name
           | [sizeof=16, dsize=16, align=8,
           |  nvsize=16, nvalign=8]")


(def crs-info-def* (delay (ffi-clang/defstruct-from-layout
                            :proj-crs-info crs-info-layout)))

(def crs-list-parameters-def* (delay (ffi-clang/defstruct-from-layout
                                       :proj-crs-list-parameters crs-list-parameters-layout)))

(def unit-info-def* (delay (ffi-clang/defstruct-from-layout
                             :proj-unit-info unit-info-layout)))

(def celestial-body-info-def* (delay (ffi-clang/defstruct-from-layout
                                       :proj-celestial-body-info celestial-body-info-layout)))


(def coord-def (dt-struct/define-datatype! :proj-coord
                 [{:name :x :datatype :float64}
                  {:name :y :datatype :float64}
                  {:name :z :datatype :float64}
                  {:name :t :datatype :float64}]))
