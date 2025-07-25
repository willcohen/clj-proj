;; ONLY macros here - NO runtime logic, NO dependencies
;; ALL runtime functions go in proj.cljc

(ns macros
  "Macros for proj.cljc - ClojureScript side.
   Pure vanilla macros with NO external dependencies for Cherry/Squint compatibility.")

 ;; Function keys injected by bb update-macro-fn-keys task
;; DO NOT EDIT - this will be replaced automatically
(def fndefs-raw
  {:proj_string_list_destroy {:rettype :void
                              :argtypes [['list :pointer]]}
   :proj_context_set_autoclose_database {:rettype :void
                                         :argtypes [['context :pointer]
                                                    ['autoclose :int32]]}
   :proj_context_set_database_path {:rettype :int32
                                    :argtypes [['context :pointer]
                                               ['db-path :string]
                                               ['aux-db-paths :pointer] ; const char *const *auxDbPaths
                                               ['options :pointer]]} ; const char *const *options
   :proj_context_errno {:rettype :int32
                        :argtypes [['context :pointer]]}
   :proj_context_errno_string {:rettype :string
                               :argtypes [['err :int32]]}
   :proj_context_get_database_path {:rettype :string
                                    :argtypes [['context :pointer]]}
   :proj_context_get_database_metadata {:rettype :string
                                        :argtypes [['context :pointer]
                                                   ['key :string]]}
   :proj_context_get_database_structure {:rettype :pointer ; PROJ_STRING_LIST
                                         :argtypes [['context :pointer]
                                                    ['options :pointer]] ; const char *const *options 
                                         :proj-returns :string-list}
   :proj_context_guess_wkt_dialect {:rettype :int32 ; PJ_GUESSED_WKT_DIALECT
                                    :argtypes [['context :pointer]
                                               ['wkt :string]]}
   :proj_create_from_wkt {:rettype :pointer ; PJ *
                          :argtypes [['context :pointer]
                                     ['wkt :string]
                                     ['options :pointer] ; const char *const *options
                                     ['out_warnings :pointer] ; PROJ_STRING_LIST *out_warnings
                                     ['out_grammar_errors :pointer]] ; PROJ_STRING_LIST *out_grammar_errors
                          :proj-returns :pj}
   :proj_create_from_database {:rettype :pointer ; PJ *
                               :argtypes [['context :pointer]
                                          ['auth_name :string]
                                          ['code :string]
                                          ['category :int32] ; PJ_CATEGORY
                                          ['use-proj-alternative-grid-names :int32]
                                          ['options :pointer]]
                               :argsemantics [['use-proj-alternative-grid-names :boolean :default false] ; pass 0 by default
                                              ['options :string-array? :default nil]]
                               :proj-returns :pj}
   :proj_uom_get_info_from_database {:rettype :int32
                                     :argtypes [['context :pointer]
                                                ['auth_name :string]
                                                ['code :string]
                                                ['out_name :pointer] ; const char **out_name
                                                ['out_conv_factor :pointer] ; double *out_conv_factor
                                                ['out_category :pointer]]} ; const char **out_category
   :proj_grid_get_info_from_database {:rettype :int32
                                      :argtypes [['context :pointer]
                                                 ['grid_name :string]
                                                 ['out_full_name :pointer] ; const char **out_full_name
                                                 ['out_package_name :pointer] ; const char **out_package_name
                                                 ['out_url :pointer] ; const char **out_url
                                                 ['out_direct_download :pointer] ; int *out_direct_download
                                                 ['out_open_license :pointer] ; int *out_open_license
                                                 ['out_available :pointer]]} ; int *out_available
   :proj_log_level {:rettype :int32 ; PJ_LOG_LEVEL
                    :argtypes [['context :pointer]
                               ['level :int32]]} ; PJ_LOG_LEVEL
   :proj_clone {:rettype :pointer ; PJ *
                :argtypes [['context :pointer]
                           ['p :pointer]] ; const PJ *p
                :proj-returns :pj}
   :proj_create_from_name {:rettype :pointer ; PJ_OBJ_LIST *
                           :argtypes [['context :pointer]
                                      ['auth_name :string]
                                      ['searchedName :string]
                                      ['types :pointer] ; const PJ_TYPE *types
                                      ['typesCount :size-t]
                                      ['approximateMatch :int32]
                                      ['limitResultCount :size-t]
                                      ['options :pointer]] ; const char *const *options
                           :proj-returns :pj-list}
   :proj_get_type {:rettype :int32 ; PJ_TYPE
                   :argtypes [['obj :pointer]]} ; const PJ *obj
   :proj_is_deprecated {:rettype :int32
                        :argtypes [['obj :pointer]]} ; const PJ *obj
   :proj_get_non_deprecated {:rettype :pointer ; PJ_OBJ_LIST *
                             :argtypes [['context :pointer]
                                        ['obj :pointer]] ; const PJ *obj
                             :proj-returns :pj-list}
   :proj_is_equivalent_to {:rettype :int32
                           :argtypes [['obj :pointer] ; const PJ *obj
                                      ['other :pointer] ; const PJ *other
                                      ['criterion :int32]]} ; PJ_COMPARISON_CRITERION
   :proj_is_equivalent_to_with_ctx {:rettype :int32
                                    :argtypes [['context :pointer]
                                               ['obj :pointer] ; const PJ *obj
                                               ['other :pointer] ; const PJ *other
                                               ['criterion :int32]]} ; PJ_COMPARISON_CRITERION
   :proj_is_crs {:rettype :int32
                 :argtypes [['obj :pointer]]} ; const PJ *obj
   :proj_get_name {:rettype :string
                   :argtypes [['obj :pointer]]} ; const PJ *obj
   :proj_get_id_auth_name {:rettype :string
                           :argtypes [['obj :pointer] ; const PJ *obj
                                      ['index :int32]]}
   :proj_get_id_code {:rettype :string
                      :argtypes [['obj :pointer] ; const PJ *obj
                                 ['index :int32]]}
   :proj_get_remarks {:rettype :string
                      :argtypes [['obj :pointer]]} ; const PJ *obj
   :proj_get_domain_count {:rettype :int32
                           :argtypes [['obj :pointer]]} ; const PJ *obj
   :proj_get_scope {:rettype :string
                    :argtypes [['obj :pointer]]} ; const PJ *obj
   :proj_get_scope_ex {:rettype :string
                       :argtypes [['obj :pointer] ; const PJ *obj
                                  ['domainIdx :int32]]}
   :proj_get_area_of_use {:rettype :int32
                          :argtypes [['context :pointer]
                                     ['obj :pointer] ; const PJ *obj
                                     ['out_west_lon_degree :pointer] ; double *out_west_lon_degree
                                     ['out_south_lat_degree :pointer] ; double *out_south_lat_degree
                                     ['out_east_lon_degree :pointer] ; double *out_east_lon_degree
                                     ['out_north_lat_degree :pointer] ; double *out_north_lat_degree
                                     ['out_area_name :pointer]]} ; const char **out_area_name
   :proj_get_area_of_use_ex {:rettype :int32
                             :argtypes [['context :pointer]
                                        ['obj :pointer] ; const PJ *obj
                                        ['domainIdx :int32]
                                        ['out_west_lon_degree :pointer] ; double *out_west_lon_degree
                                        ['out_south_lat_degree :pointer] ; double *out_south_lat_degree
                                        ['out_east_lon_degree :pointer] ; double *out_east_lon_degree
                                        ['out_north_lat_degree :pointer] ; double *out_north_lat_degree
                                        ['out_area_name :pointer]]} ; const char **out_area_name
   :proj_as_wkt {:rettype :string
                 :argtypes [['context :pointer]
                            ['pj :pointer] ; const PJ *obj
                            ['type :int32] ; PJ_WKT_TYPE,
                            ['options :pointer?]]
                 :argsemantics [['options :string-array? :default nil]
                                ['type :int32 :default 2]]} ; const char *const *options
   :proj_as_proj_string {:rettype :string
                         :argtypes [['context :pointer]
                                    ['pj :pointer] ; const PJ *obj
                                    ['type :int32] ; PJ_PROJ_STRING_TYPE
                                    ['options :pointer?]]
                         :argsemantics [['options :string-array? :default nil]]} ; const char *const *options
   :proj_as_projjson {:rettype :string
                      :argtypes [['context :pointer]
                                 ['pj :pointer] ; const PJ *obj
                                 ['options :pointer?]]
                      :argsemantics [['options :string-array? :default nil]]} ; const char *const *options
   :proj_get_source_crs {:rettype :pointer ; PJ *
                         :argtypes [['context :pointer]
                                    ['pj :pointer]] ; const PJ *obj
                         :proj-returns :pj}
   :proj_get_target_crs {:rettype :pointer ; PJ *
                         :argtypes [['context :pointer]
                                    ['pj :pointer]] ; const PJ *obj
                         :proj-returns :pj}
   :proj_context_create {:rettype :pointer ; PJ_CONTEXT *
                         :argtypes [],
                         :proj-returns :pj-context,
                         :is-context-fn false}
   :proj_context_destroy {:rettype :void
                          :argtypes [['context :pointer]], ; PJ_CONTEXT *ctx
                          :is-context-fn false}
   :proj_identify {:rettype :pointer ; PJ_OBJ_LIST *
                   :argtypes [['context :pointer]
                              ['obj :pointer] ; const PJ *obj
                              ['auth_name :string]
                              ['options :pointer] ; const char *const *options
                              ['out_confidence :pointer]] ; int **out_confidence
                   :proj-returns :pj-list}
   :proj_get_geoid_models_from_database {:rettype :pointer ; PROJ_STRING_LIST
                                         :argtypes [['context :pointer]
                                                    ['auth_name :string]
                                                    ['code :string]
                                                    ['options :pointer]] ; const char *const *options 
                                         :proj-returns :string-list}
   :proj_int_list_destroy {:rettype :void
                           :argtypes [['list :pointer]]} ; int *list
   :proj_cs_get_type {:rettype :int32 ; PJ_COORDINATE_SYSTEM_TYPE
                      :argtypes [['context :pointer]
                                 ['cs :pointer]]} ; const PJ *cs
   :proj_destroy {:rettype :pointer ; PJ *
                  :argtypes [['pj :pointer]]} ; PJ *P
   :proj_get_authorities_from_database {:rettype :pointer ; PROJ_STRING_LIST
                                        :argtypes [['context :pointer]]
                                        :proj-returns :string-list}
   :proj_get_codes_from_database {:rettype :pointer ; PROJ_STRING_LIST
                                  :argtypes [['context :pointer]
                                             ['auth_name :string]
                                             ['type :int32 :default 8] ; PJ_TYPE, default to CRS
                                             ['allow_deprecated :int32 :default 1]]
                                  :proj-returns :string-list}
   :proj_get_celestial_body_list_from_database {:rettype :pointer ; PROJ_CELESTIAL_BODY_INFO **
                                                :argtypes [['context :pointer]
                                                           ['auth_name :string]
                                                           ['out_result_count :pointer]] ; int *out_result_count
                                                :proj-returns :pj-celestial-body-info-list}
   :proj_celestial_body_list_destroy {:rettype :void
                                      :argtypes [['list :pointer]]} ; PROJ_CELESTIAL_BODY_INFO **list
   :proj_get_crs_list_parameters_create {:rettype :pointer ; PROJ_CRS_LIST_PARAMETERS *
                                         :argtypes []
                                         :proj-returns :pj-crs-list-parameters}
   :proj_get_crs_list_parameters_destroy {:rettype :void
                                          :argtypes [['params :pointer]]} ; PROJ_CRS_LIST_PARAMETERS *params
   :proj_get_crs_info_list_from_database {:rettype :pointer ; PROJ_CRS_INFO **
                                          :argtypes [['context :pointer]
                                                     ['auth_name :string]
                                                     ['params :pointer] ; const PROJ_CRS_LIST_PARAMETERS *params
                                                     ['out_result_count :pointer]] ; int *out_result_count
                                          :proj-returns :pj-crs-info-list}
   :proj_crs_info_list_destroy {:rettype :void
                                :argtypes [['list :pointer]]} ; PROJ_CRS_INFO **list
   :proj_get_units_from_database {:rettype :pointer ; PROJ_UNIT_INFO **
                                  :argtypes [['context :pointer]
                                             ['auth_name :string]
                                             ['category :string]
                                             ['allow_deprecated :int32]
                                             ['out_result_count :pointer]] ; int *out_result_count
                                  :proj-returns :pj-unit-info-list}
   :proj_unit_list_destroy {:rettype :void
                            :argtypes [['list :pointer]]} ; PROJ_UNIT_INFO **list
   :proj_insert_object_session_create {:rettype :pointer ; PJ_INSERT_SESSION *
                                       :argtypes [['context :pointer]]
                                       :proj-returns :pj-insert-session}
   :proj_insert_object_session_destroy {:rettype :void
                                        :argtypes [['context :pointer]
                                                   ['session :pointer]]} ; PJ_INSERT_SESSION *session
   :proj_get_insert_statements {:rettype :pointer ; PROJ_STRING_LIST
                                :argtypes [['context :pointer]
                                           ['session :pointer] ; PJ_INSERT_SESSION *session
                                           ['object :pointer] ; const PJ *object
                                           ['authority :string]
                                           ['code :string]
                                           ['numeric_codes :int32]
                                           ['allowed_authorities :pointer] ; const char *const *allowed_authorities
                                           ['options :pointer]] ; const char *const *options 
                                :proj-returns :string-list}
   :proj_suggests_code_for {:rettype :string
                            :argtypes [['context :pointer]
                                       ['object :pointer] ; const PJ *object
                                       ['authority :string]
                                       ['numeric_code :int32]
                                       ['options :pointer]]} ; const char *const *options
   :proj_string_destroy {:rettype :void
                         :argtypes [['str :string]]} ; char *str
   :proj_create_operation_factory_context {:rettype :pointer ; PJ_OPERATION_FACTORY_CONTEXT *
                                           :argtypes [['context :pointer]
                                                      ['authority :string]]
                                           :proj-returns :pj-operation-factory-context}
   :proj_operation_factory_context_destroy {:rettype :void ; takes a context, but is a destroy fn
                                            :argtypes [['ctx :pointer]], ; PJ_OPERATION_FACTORY_CONTEXT *ctx
                                            :is-context-fn false}
   :proj_operation_factory_context_set_desired_accuracy {:rettype :void
                                                         :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                                    ['factory_ctx :pointer] ; PJ_OPERATION_FACTORY_CONTEXT *factory_ctx
                                                                    ['accuracy :float64]]}
   :proj_operation_factory_context_set_area_of_interest {:rettype :void
                                                         :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                                    ['factory_ctx :pointer] ; PJ_OPERATION_FACTORY_CONTEXT *factory_ctx
                                                                    ['west_lon_degree :float64]
                                                                    ['south_lat_degree :float64]
                                                                    ['east_lon_degree :float64]
                                                                    ['north_lat_degree :float64]]}
   :proj_operation_factory_context_set_area_of_interest_name {:rettype :void
                                                              :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                                         ['factory_ctx :pointer] ; PJ_OPERATION_FACTORY_CONTEXT *factory_ctx
                                                                         ['area_name :string]]}
   :proj_operation_factory_context_set_crs_extent_use {:rettype :void
                                                       :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                                  ['factory_ctx :pointer] ; PJ_OPERATION_FACTORY_CONTEXT *factory_ctx
                                                                  ['use :int32]]} ; PROJ_CRS_EXTENT_USE
   :proj_operation_factory_context_set_spatial_criterion {:rettype :void
                                                          :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                                     ['factory_ctx :pointer] ; PJ_OPERATION_FACTORY_CONTEXT *factory_ctx
                                                                     ['criterion :int32]]} ; PROJ_SPATIAL_CRITERION
   :proj_operation_factory_context_set_grid_availability_use {:rettype :void
                                                              :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                                         ['factory_ctx :pointer] ; PJ_OPERATION_FACTORY_CONTEXT *factory_ctx
                                                                         ['use :int32]]} ; PROJ_GRID_AVAILABILITY_USE
   :proj_operation_factory_context_set_use_proj_alternative_grid_names {:rettype :void
                                                                        :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                                                   ['factory_ctx :pointer] ; PJ_OPERATION_FACTORY_CONTEXT *factory_ctx
                                                                                   ['usePROJNames :int32]]}
   :proj_operation_factory_context_set_allow_use_intermediate_crs {:rettype :void
                                                                   :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                                              ['factory_ctx :pointer] ; PJ_OPERATION_FACTORY_CONTEXT *factory_ctx
                                                                              ['use :int32]]} ; PROJ_INTERMEDIATE_CRS_USE
   :proj_operation_factory_context_set_allowed_intermediate_crs {:rettype :void
                                                                 :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                                            ['factory_ctx :pointer] ; PJ_OPERATION_FACTORY_CONTEXT *factory_ctx
                                                                            ['list_of_auth_name_codes :pointer]]} ; const char *const *list_of_auth_name_codes
   :proj_operation_factory_context_set_discard_superseded {:rettype :void
                                                           :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                                      ['factory_ctx :pointer] ; PJ_OPERATION_FACTORY_CONTEXT *factory_ctx
                                                                      ['discard :int32]]}
   :proj_operation_factory_context_set_allow_ballpark_transformations {:rettype :void
                                                                       :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                                                  ['factory_ctx :pointer] ; PJ_OPERATION_FACTORY_CONTEXT *factory_ctx
                                                                                  ['allow :int32]]}
   :proj_create_operations {:rettype :pointer ; PJ_OBJ_LIST *
                            :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                       ['source_crs :pointer] ; const PJ *source_crs
                                       ['target_crs :pointer] ; const PJ *target_crs
                                       ['operationContext :pointer]] ; const PJ_OPERATION_FACTORY_CONTEXT *operationContext
                            :proj-returns :pj-list}
   :proj_list_get_count {:rettype :int32
                         :argtypes [['result :pointer]]} ; const PJ_OBJ_LIST *result
   :proj_list_get {:rettype :pointer ; PJ *
                   :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                              ['result :pointer] ; const PJ_OBJ_LIST *result
                              ['index :int32]]
                   :proj-returns :pj}
   :proj_list_destroy {:rettype :void
                       :argtypes [['result :pointer]]} ; PJ_OBJ_LIST *result
   :proj_get_suggested_operation {:rettype :int32
                                  :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                             ['operations :pointer] ; PJ_OBJ_LIST *operations
                                             ['direction :int32] ; PJ_DIRECTION
                                             ['coord :pointer]]} ; PJ_COORD coord
   :proj_crs_is_derived {:rettype :int32
                         :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                    ['crs :pointer]]} ; const PJ *crs
   :proj_crs_get_geodetic_crs {:rettype :pointer ; PJ *
                               :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                          ['crs :pointer]] ; const PJ *crs
                               :proj-returns :pj}
   :proj_crs_get_horizontal_datum {:rettype :pointer ; PJ *
                                   :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                              ['crs :pointer]] ; const PJ *crs
                                   :proj-returns :pj}
   :proj_crs_get_sub_crs {:rettype :pointer ; PJ *
                          :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                     ['crs :pointer] ; const PJ *crs
                                     ['index :int32]]
                          :proj-returns :pj}
   :proj_crs_get_datum {:rettype :pointer ; PJ *
                        :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                   ['crs :pointer]] ; const PJ *crs
                        :proj-returns :pj}
   :proj_crs_get_datum_ensemble {:rettype :pointer ; PJ *
                                 :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                            ['crs :pointer]] ; const PJ *crs
                                 :proj-returns :pj}
   :proj_crs_get_datum_forced {:rettype :pointer ; PJ *
                               :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                          ['crs :pointer]] ; const PJ *crs
                               :proj-returns :pj}
   :proj_crs_has_point_motion_operation {:rettype :int32
                                         :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                    ['crs :pointer]]} ; const PJ *crs
   :proj_datum_ensemble_get_member_count {:rettype :int32
                                          :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                     ['datum_ensemble :pointer]]} ; const PJ *datum_ensemble
   :proj_datum_ensemble_get_accuracy {:rettype :float64
                                      :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                 ['datum_ensemble :pointer]]} ; const PJ *datum_ensemble
   :proj_datum_ensemble_get_member {:rettype :pointer ; PJ *
                                    :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                               ['datum_ensemble :pointer] ; const PJ *datum_ensemble
                                               ['member_index :int32]]
                                    :proj-returns :pj}
   :proj_dynamic_datum_get_frame_reference_epoch {:rettype :float64
                                                  :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                             ['datum :pointer]]} ; const PJ *datum
   :proj_crs_get_coordinate_system {:rettype :pointer ; PJ *
                                    :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                               ['crs :pointer]] ; const PJ *crs
                                    :proj-returns :pj}
   :proj_xy_dist {:rettype :float64
                  :argtypes [['a :pointer] ; PJ_COORD a
                             ['b :pointer]]} ; PJ_COORD b
   :proj_coord {:rettype :pointer ; PJ_COORD
                :argtypes [['x :float64]
                           ['y :float64]
                           ['z :float64]
                           ['t :float64]]}
   :proj_create_crs_to_crs {:rettype :pointer ; PJ *
                            :argtypes [['context :pointer]
                                       ['source_crs :string]
                                       ['target_crs :string]
                                       ['area :pointer?]]
                            :argsemantics [['area :pj-area :default 0]]
                            :proj-returns :pj}
   :proj_normalize_for_visualization {:rettype :pointer ; PJ *
                                      :argtypes [['context :pointer]
                                                 ['obj :pointer]] ; const PJ *obj
                                      :proj-returns :pj}
   :proj_trans_array {:rettype :int32
                      :argtypes [['p :pointer] ; PJ *P
                                 ['direction :int32] ; PJ_DIRECTION
                                 ['n :size-t]
                                 ['coord :pointer]] ; PJ_COORD *coord
                      :argsemantics [['coord :coord-array]
                                     ['n :coord-count]]}
   :proj_cs_get_axis_count {:rettype :int32
                            :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                       ['cs :pointer]]} ; const PJ *cs
   :proj_cs_get_axis_info {:rettype :int32
                           :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                      ['cs :pointer] ; const PJ *cs
                                      ['index :int32]
                                      ['out_name :pointer] ; const char **out_name
                                      ['out_abbrev :pointer] ; const char **out_abbrev
                                      ['out_direction :pointer] ; const char **out_direction
                                      ['out_unit_conv_factor :pointer] ; double *out_unit_conv_factor
                                      ['out_unit_name :pointer] ; const char **out_unit_name
                                      ['out_unit_auth_name :pointer] ; const char **out_unit_auth_name
                                      ['out_unit_code :pointer]]} ; const char **out_unit_code
   :proj_get_ellipsoid {:rettype :pointer ; PJ *
                        :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                   ['obj :pointer]] ; const PJ *obj
                        :proj-returns :pj}
   :proj_ellipsoid_get_parameters {:rettype :int32
                                   :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                              ['ellipsoid :pointer] ; const PJ *ellipsoid
                                              ['out_semi_major_metre :pointer] ; double *out_semi_major_metre
                                              ['out_semi_minor_metre :pointer] ; double *out_semi_minor_metre
                                              ['out_is_semi_minor_computed :pointer] ; int *out_is_semi_minor_computed
                                              ['out_inv_flattening :pointer]]} ; double *out_inv_flattening
   :proj_get_celestial_body_name {:rettype :string
                                  :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                             ['obj :pointer]]} ; const PJ *obj
   :proj_get_prime_meridian {:rettype :pointer ; PJ *
                             :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                        ['obj :pointer]] ; const PJ *obj
                             :proj-returns :pj}
   :proj_prime_meridian_get_parameters {:rettype :int32
                                        :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                   ['prime_meridian :pointer] ; const PJ *prime_meridian
                                                   ['out_longitude :pointer] ; double *out_longitude
                                                   ['out_unit_conv_factor :pointer] ; double *out_unit_conv_factor
                                                   ['out_unit_name :pointer]]} ; const char **out_unit_name
   :proj_crs_get_coordoperation {:rettype :pointer ; PJ *
                                 :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                            ['crs :pointer]] ; const PJ *crs
                                 :proj-returns :pj}
   :proj_coordoperation_get_method_info {:rettype :int32
                                         :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                    ['coordoperation :pointer] ; const PJ *coordoperation
                                                    ['out_method_name :pointer] ; const char **out_method_name
                                                    ['out_method_auth_name :pointer] ; const char **out_method_auth_name
                                                    ['out_method_code :pointer]]} ; const char **out_method_code
   :proj_coordoperation_is_instantiable {:rettype :int32
                                         :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                    ['coordoperation :pointer]]} ; const PJ *coordoperation
   :proj_coordoperation_has_ballpark_transformation {:rettype :int32
                                                     :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                                ['coordoperation :pointer]]} ; const PJ *coordoperation
   :proj_coordoperation_requires_per_coordinate_input_time {:rettype :int32
                                                            :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                                       ['coordoperation :pointer]]} ; const PJ *coordoperation
   :proj_coordoperation_get_param_count {:rettype :int32
                                         :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                    ['coordoperation :pointer]]} ; const PJ *coordoperation
   :proj_coordoperation_get_param_index {:rettype :int32
                                         :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                    ['coordoperation :pointer] ; const PJ *coordoperation
                                                    ['name :string]]}
   :proj_coordoperation_get_param {:rettype :int32
                                   :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                              ['coordoperation :pointer] ; const PJ *coordoperation
                                              ['index :int32]
                                              ['out_name :pointer] ; const char **out_name
                                              ['out_auth_name :pointer] ; const char **out_auth_name
                                              ['out_code :pointer] ; const char **out_code
                                              ['out_value :pointer] ; double *out_value
                                              ['out_value_string :pointer] ; const char **out_value_string
                                              ['out_unit_conv_factor :pointer] ; double *out_unit_conv_factor
                                              ['out_unit_name :pointer] ; const char **out_unit_name
                                              ['out_unit_auth_name :pointer] ; const char **out_unit_auth_name
                                              ['out_unit_code :pointer] ; const char **out_unit_code
                                              ['out_unit_category :pointer]]} ; const char **out_unit_category
   :proj_coordoperation_get_grid_used_count {:rettype :int32
                                             :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                        ['coordoperation :pointer]]} ; const PJ *coordoperation
   :proj_coordoperation_get_grid_used {:rettype :int32
                                       :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                  ['coordoperation :pointer] ; const PJ *coordoperation
                                                  ['index :int32]
                                                  ['out_short_name :pointer] ; const char **out_short_name
                                                  ['out_full_name :pointer] ; const char **out_full_name
                                                  ['out_package_name :pointer] ; const char **out_package_name
                                                  ['out_url :pointer] ; const char **out_url
                                                  ['out_direct_download :pointer] ; int *out_direct_download
                                                  ['out_open_license :pointer] ; int *out_open_license
                                                  ['out_available :pointer]]} ; int *out_available
   :proj_coordoperation_get_accuracy {:rettype :float64
                                      :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                 ['obj :pointer]]} ; const PJ *obj
   :proj_coordoperation_get_towgs84_values {:rettype :int32
                                            :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                       ['coordoperation :pointer] ; const PJ *coordoperation
                                                       ['out_values :pointer] ; double *out_values
                                                       ['value_count :int32]
                                                       ['emit_error_if_incompatible :int32]]}
   :proj_coordoperation_create_inverse {:rettype :pointer ; PJ *
                                        :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                   ['obj :pointer]] ; const PJ *obj
                                        :proj-returns :pj}
   :proj_concatoperation_get_step_count {:rettype :int32
                                         :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                    ['concatoperation :pointer]]} ; const PJ *concatoperation
   :proj_concatoperation_get_step {:rettype :pointer ; PJ *
                                   :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                              ['concatoperation :pointer] ; const PJ *concatoperation
                                              ['i_step :int32]]
                                   :proj-returns :pj}
   :proj_coordinate_metadata_create {:rettype :pointer ; PJ *
                                     :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                ['crs :pointer] ; const PJ *crs
                                                ['epoch :float64]]
                                     :proj-returns :pj}
   :proj_coordinate_metadata_get_epoch {:rettype :float64
                                        :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                   ['obj :pointer]]} ; const PJ *obj
   :proj_create_cs {:rettype :pointer ; PJ *
                    :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                               ['type :int32] ; PJ_COORDINATE_SYSTEM_TYPE
                               ['axis_count :int32]
                               ['axis :pointer]] ; const PJ_AXIS_DESCRIPTION *axis
                    :proj-returns :pj}
   :proj_create_cartesian_2D_cs {:rettype :pointer ; PJ *
                                 :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                            ['type :int32] ; PJ_CARTESIAN_CS_2D_TYPE
                                            ['unit_name :string]
                                            ['unit_conv_factor :float64]]
                                 :proj-returns :pj}
   :proj_create_ellipsoidal_2D_cs {:rettype :pointer ; PJ *
                                   :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                              ['type :int32] ; PJ_ELLIPSOIDAL_CS_2D_TYPE
                                              ['unit_name :string]
                                              ['unit_conv_factor :float64]]
                                   :proj-returns :pj}
   :proj_create_ellipsoidal_3D_cs {:rettype :pointer ; PJ *
                                   :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                              ['type :int32] ; PJ_ELLIPSOIDAL_CS_3D_TYPE
                                              ['horizontal_angular_unit_name :string]
                                              ['horizontal_angular_unit_conv_factor :float64]
                                              ['vertical_linear_unit_name :string]
                                              ['vertical_linear_unit_conv_factor :float64]]
                                   :proj-returns :pj}
   :proj_query_geodetic_crs_from_datum {:rettype :pointer ; PJ_OBJ_LIST *
                                        :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                   ['crs_auth_name :string]
                                                   ['datum_auth_name :string]
                                                   ['datum_code :string]
                                                   ['crs_type :string]]
                                        :proj-returns :pj-list}
   :proj_create_geographic_crs {:rettype :pointer ; PJ *
                                :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                           ['crs_name :string]
                                           ['datum_name :string]
                                           ['ellps_name :string]
                                           ['semi_major_metre :float64]
                                           ['inv_flattening :float64]
                                           ['prime_meridian_name :string]
                                           ['prime_meridian_offset :float64]
                                           ['pm_angular_units :string]
                                           ['pm_units_conv :float64]
                                           ['ellipsoidal_cs :pointer]] ; const PJ *ellipsoidal_cs
                                :proj-returns :pj}
   :proj_create_geographic_crs_from_datum {:rettype :pointer ; PJ *
                                           :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                      ['crs_name :string]
                                                      ['datum_or_datum_ensemble :pointer] ; const PJ *datum_or_datum_ensemble
                                                      ['ellipsoidal_cs :pointer]] ; const PJ *ellipsoidal_cs
                                           :proj-returns :pj}
   :proj_create_geocentric_crs {:rettype :pointer ; PJ *
                                :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                           ['crs_name :string]
                                           ['datum_name :string]
                                           ['ellps_name :string]
                                           ['semi_major_metre :float64]
                                           ['inv_flattening :float64]
                                           ['prime_meridian_name :string]
                                           ['prime_meridian_offset :float64]
                                           ['angular_units :string]
                                           ['angular_units_conv :float64]
                                           ['linear_units :string]
                                           ['linear_units_conv :float64]]
                                :proj-returns :pj}
   :proj_create_geocentric_crs_from_datum {:rettype :pointer ; PJ *
                                           :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                      ['crs_name :string]
                                                      ['datum_or_datum_ensemble :pointer] ; const PJ *datum_or_datum_ensemble
                                                      ['linear_units :string]
                                                      ['linear_units_conv :float64]]
                                           :proj-returns :pj}
   :proj_create_derived_geographic_crs {:rettype :pointer ; PJ *
                                        :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                   ['crs_name :string]
                                                   ['base_geographic_crs :pointer] ; const PJ *base_geographic_crs
                                                   ['conversion :pointer] ; const PJ *conversion
                                                   ['ellipsoidal_cs :pointer]] ; const PJ *ellipsoidal_cs
                                        :proj-returns :pj}
   :proj_is_derived_crs {:rettype :int32
                         :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                    ['crs :pointer]]} ; const PJ *crs
   :proj_alter_name {:rettype :pointer ; PJ *
                     :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                ['obj :pointer] ; const PJ *obj
                                ['name :string]]
                     :proj-returns :pj}
   :proj_alter_id {:rettype :pointer ; PJ *
                   :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                              ['obj :pointer] ; const PJ *obj
                              ['auth_name :string]
                              ['code :string]]
                   :proj-returns :pj}
   :proj_crs_alter_geodetic_crs {:rettype :pointer ; PJ *
                                 :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                            ['obj :pointer] ; const PJ *obj
                                            ['new_geod_crs :pointer]] ; const PJ *new_geod_crs
                                 :proj-returns :pj}
   :proj_crs_alter_cs_angular_unit {:rettype :pointer ; PJ *
                                    :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                               ['obj :pointer] ; const PJ *obj
                                               ['angular_units :string]
                                               ['angular_units_conv :float64]
                                               ['unit_auth_name :string]
                                               ['unit_code :string]]
                                    :proj-returns :pj}
   :proj_crs_alter_cs_linear_unit {:rettype :pointer ; PJ *
                                   :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                              ['obj :pointer] ; const PJ *obj
                                              ['linear_units :string]
                                              ['linear_units_conv :float64]
                                              ['unit_auth_name :string]
                                              ['unit_code :string]]
                                   :proj-returns :pj}
   :proj_crs_alter_parameters_linear_unit {:rettype :pointer ; PJ *
                                           :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                      ['obj :pointer] ; const PJ *obj
                                                      ['linear_units :string]
                                                      ['linear_units_conv :float64]
                                                      ['unit_auth_name :string]
                                                      ['unit_code :string]
                                                      ['convert_to_new_unit :int32]]
                                           :proj-returns :pj}
   :proj_crs_promote_to_3D {:rettype :pointer ; PJ *
                            :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                       ['crs_3D_name :string]
                                       ['crs_2D :pointer]] ; const PJ *crs_2D
                            :proj-returns :pj}
   :proj_crs_create_projected_3D_crs_from_2D {:rettype :pointer ; PJ *
                                              :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                         ['crs_name :string]
                                                         ['projected_2D_crs :pointer] ; const PJ *projected_2D_crs
                                                         ['geog_3D_crs :pointer]] ; const PJ *geog_3D_crs
                                              :proj-returns :pj}
   :proj_crs_demote_to_2D {:rettype :pointer ; PJ *
                           :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                      ['crs_2D_name :string]
                                      ['crs_3D :pointer]] ; const PJ *crs_3D
                           :proj-returns :pj}
   :proj_create_engineering_crs {:rettype :pointer ; PJ *
                                 :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                            ['crsName :string]]
                                 :proj-returns :pj}
   :proj_create_vertical_crs {:rettype :pointer ; PJ *
                              :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                         ['crs_name :string]
                                         ['datum_name :string]
                                         ['linear_units :string]
                                         ['linear_units_conv :float64]]
                              :proj-returns :pj}
   :proj_create_vertical_crs_ex {:rettype :pointer ; PJ *
                                 :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                            ['crs_name :string]
                                            ['datum_name :string]
                                            ['datum_auth_name :string]
                                            ['datum_code :string]
                                            ['linear_units :string]
                                            ['linear_units_conv :float64]
                                            ['geoid_model_name :string]
                                            ['geoid_model_auth_name :string]
                                            ['geoid_model_code :string]
                                            ['geoid_geog_crs :pointer] ; const PJ *geoid_geog_crs
                                            ['options :pointer]] ; const char *const *options
                                 :proj-returns :pj}
   :proj_create_compound_crs {:rettype :pointer ; PJ *
                              :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                         ['crs_name :string]
                                         ['horiz_crs :pointer] ; const PJ *horiz_crs
                                         ['vert_crs :pointer]] ; const PJ *vert_crs
                              :proj-returns :pj}
   :proj_create_conversion {:rettype :pointer ; PJ *
                            :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                       ['name :string]
                                       ['auth_name :string]
                                       ['code :string]
                                       ['method_name :string]
                                       ['method_auth_name :string]
                                       ['method_code :string]
                                       ['param_count :int32]
                                       ['params :pointer]] ; const PJ_PARAM_DESCRIPTION *params
                            :proj-returns :pj}
   :proj_create_transformation {:rettype :pointer ; PJ *
                                :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                           ['name :string]
                                           ['auth_name :string]
                                           ['code :string]
                                           ['source_crs :pointer] ; const PJ *source_crs
                                           ['target_crs :pointer] ; const PJ *target_crs
                                           ['interpolation_crs :pointer] ; const PJ *interpolation_crs
                                           ['method_name :string]
                                           ['method_auth_name :string]
                                           ['method_code :string]
                                           ['param_count :int32]
                                           ['params :pointer] ; const PJ_PARAM_DESCRIPTION *params
                                           ['accuracy :float64]]
                                :proj-returns :pj}
   :proj_convert_conversion_to_other_method {:rettype :pointer ; PJ *
                                             :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                        ['conversion :pointer] ; const PJ *conversion
                                                        ['new_method_epsg_code :int32]
                                                        ['new_method_name :string]]
                                             :proj-returns :pj}
   :proj_create_projected_crs {:rettype :pointer ; PJ *
                               :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                          ['crs_name :string]
                                          ['geodetic_crs :pointer] ; const PJ *geodetic_crs
                                          ['conversion :pointer] ; const PJ *conversion
                                          ['coordinate_system :pointer]] ; const PJ *coordinate_system
                               :proj-returns :pj}
   :proj_crs_create_bound_crs {:rettype :pointer ; PJ *
                               :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                          ['base_crs :pointer] ; const PJ *base_crs
                                          ['hub_crs :pointer] ; const PJ *hub_crs
                                          ['transformation :pointer]] ; const PJ *transformation
                               :proj-returns :pj}
   :proj_crs_create_bound_crs_to_WGS84 {:rettype :pointer ; PJ *
                                        :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                   ['crs :pointer] ; const PJ *crs
                                                   ['options :pointer]] ; const char *const *options
                                        :proj-returns :pj}
   :proj_crs_create_bound_vertical_crs {:rettype :pointer ; PJ *
                                        :argtypes [['ctx :pointer] ; PJ_CONTEXT *ctx
                                                   ['vert_crs :pointer] ; const PJ *vert_crs
                                                   ['hub_geographic_3D_crs :pointer] ; const PJ *hub_geographic_3D_crs
                                                   ['grid_name :string]]
                                        :proj-returns :pj}})

;; Helper functions used at macro expansion time only

(defn- c-name->clj-name [c-fn-keyword]
  (symbol (.replace (name c-fn-keyword) "_" "-")))

;; Simplified macro that generates minimal wrapper
(defmacro define-proj-public-fn [fn-key]
  ;; Get fn-def from fndefs-raw at compile time
  (let [fn-def (get fndefs-raw fn-key)
        _ (when-not fn-def
            (throw (ex-info (str "Function definition not found in fndefs-raw: " fn-key) {:fn-key fn-key})))
        fn-name (symbol (.replace (name fn-key) "_" "-"))]
    `(defn ~fn-name
       ([] (~fn-name {}))
       ([opts#]
        ;; Embed fn-def at compile time
        (~'dispatch-proj-fn ~fn-key '~fn-def opts#)))))

(defmacro define-all-proj-public-fns []
  ;; Use fndefs-raw at compile time instead of proj-fn-keys
  `(do
     ~@(for [[fn-key fn-def] fndefs-raw]
         (let [fn-name (symbol (.replace (name fn-key) "_" "-"))]
           `(defn ~fn-name
              ~(str "PROJ function " fn-name " - see PROJ documentation")
              ([] (~fn-name {}))
              ([opts#]
               ;; Embed fn-def at compile time - no runtime lookup needed
               (~'dispatch-proj-fn ~fn-key '~fn-def opts#)))))))

;; WASM-specific macros

(defmacro ef [f]
  `(~f))

(defmacro def-emscripten-js-fn [cljs-fn-name fn-key]
  ;; Get fn-def from fndefs-raw at compile time
  (let [fn-def (get fndefs-raw fn-key)
        _ (when-not fn-def
            (throw (ex-info (str "Function definition not found in fndefs-raw: " fn-key) {:fn-key fn-key})))]
    `(defn ~cljs-fn-name [opts#]
       ;; Embed fn-def at compile time
       (~'dispatch-proj-fn ~fn-key '~fn-def opts#))))

(defmacro def-wasm-fn
  "Defines a wrapper function for a PROJ C API call via WASM."
  [fn-name fn-key fn-defs]
  (let [fn-def (get fn-defs fn-key)
        _ (when-not fn-def
            (throw (ex-info (str "No fn-def found for key: " fn-key) {:fn-key fn-key})))
        arg-symbols (mapv (comp symbol first) (:argtypes fn-def))]
    `(defn ~fn-name [~@arg-symbols]
       ;; Embed fn-def at compile time
       (def-wasm-fn-runtime ~fn-key '~fn-def [~@arg-symbols]))))

(defmacro define-all-wasm-fns
  ([]
   ;; ClojureScript version - generate all functions at compile time
   `(do
      ~@(for [[fn-key fn-def] fndefs-raw]
          (let [fn-name (symbol (.replace (name fn-key) "_" "-"))]
            `(aset js/exports
                   ~(name fn-name)
                   (fn [& args#]
                     ;; Embed fn-def at compile time
                     (def-wasm-fn-runtime ~fn-key '~fn-def (vec args#))))))))
  ([fn-defs-sym c-name->clj-name-sym]
   ;; Original version for other uses
   `(doseq [[fn-key# fn-def#] ~fn-defs-sym]
      (let [fn-name# (~c-name->clj-name-sym fn-key#)]
        (aset js/exports
              (name fn-name#)
              (fn [& args#]
                ;; fn-def# already bound from doseq
                (js/wasm.def-wasm-fn-runtime fn-key# fn-def# (vec args#))))))))

(defmacro tsgcd [body]
  `(let [result# ~body]
     (if (and (exists? js/Java) (.-then result#))
       (.then result# (fn [v#] v#))
       result#)))

(defmacro with-allocated-string [[sym s] & body]
  `(let [~sym (js/proj.allocate-string-on-heap ~s)]
     (try
       ~@body
       (finally
         (js/proj.free-on-heap ~sym)))))
