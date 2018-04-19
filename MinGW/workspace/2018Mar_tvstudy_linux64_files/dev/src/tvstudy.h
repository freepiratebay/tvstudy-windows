//
//  tvstudy.h
//  TVStudy
//
//  Copyright (c) 2012-2018 Hammett & Edison, Inc.  All rights reserved.


#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <math.h>
#include <time.h>
#include <sys/types.h>

#include <mysql.h>

#ifndef __BUILD_TVSTUDY
#define __BUILD_TVSTUDY
#endif

#include "global.h"
#include "model/model.h"
#include "fcc_curve.h"
#include "terrain.h"
#include "landcover.h"
#include "coordinates.h"
#include "memory.h"
#include "parser.h"


//---------------------------------------------------------------------------------------------------------------------
// Version numbers are X.Y.Z version strings expressed as XYYZZZ.  The main version string changes when there are code
// changes.  The cache version number is stored in cache files, when it does not match the files are discarded.  The
// cache version changes only when an update makes cached information invalid.  The database version is stored in the
// root database, if it does not match the database cannot be opened.  It changes when the database structure changes.
// The main version number in string format has been moved to global.h so it is available to other utility builds.

#define TVSTUDY_CACHE_VERSION     202000
#define TVSTUDY_DATABASE_VERSION  20200502

// Default database name, see study.c.

#define DEFAULT_DB_NAME  "tvstudy"

// Station data, study, and record types, must match constants in UI app.

#define DB_TYPE_CDBS      1
#define DB_TYPE_LMS       2
#define DB_TYPE_WIRELESS  3
#define DB_TYPE_CDBS_FM   4

#define STUDY_TYPE_UNKNOWN   0
#define STUDY_TYPE_TV        1
#define STUDY_TYPE_TV_IX     2
#define STUDY_TYPE_TV_OET74  3
#define STUDY_TYPE_FM        4
#define STUDY_TYPE_TV6_FM    5

#define STUDY_MODE_GRID    1
#define STUDY_MODE_POINTS  2

#define STUDY_AREA_SERVICE    1
#define STUDY_AREA_GEOGRAPHY  2
#define STUDY_AREA_NO_BOUNDS  3

#define RECORD_TYPE_TV  1
#define RECORD_TYPE_WL  2
#define RECORD_TYPE_FM  3

// Database keys and enumeration values for study parameters.  These must be kept in sync with database contents, see
// the root database installation code in the UI application for details.  All parameters have keys defined here so
// this is a complete list, however not all parameters will be loaded, some are used only in the UI application.

#define PARAM_KEY_SIZE  370   // Size for parameter storage arrays, one more than largest key value, see parameter.c.
#define MAX_VALUE_COUNT   6   // Maximum number of value indices in any multi-value parameter.

// Originally the parameter keys also ordered the UI so assignment was sparse to allow for additions.  That was not
// sufficiently flexible so explicit ordering properties were added.  Keys are now arbitrary and fill-in assignment is
// being done for new parameters.  Below is the list of unassigned keys still available for fill-in use.
//   3 7 8 9 11 13 21 23 24 25 26 27 28 29 31 33 34 35 36 37 38 39 41 43 45 47 48 49 51 53 55 57 59 61 63 65 67 69 71
//   72 73 74 75 76 77 78 79 81 83 85 87 89 91 93 95 97 99 101 103 105 109 116 117 118 119 123 125 127 129 131 133 134
//   135 136 137 138 139 141 143 145 147 149 152 153
// Below is a list of keys for parameters that were defined but have been eliminated, these must _not_ be re-used.
//   155, 156, 157, 191, 198, 206, 207, 208, 209

// Now the actual parameter keys currently in use.

#define PARAM_GRID_TYPE             2
#define PARAM_CELL_SIZE             4
#define PARAM_POINT_METHOD          5
#define PARAM_POINT_NEAREST         6

#define PARAM_ERROR_HNDLNG         10

#define PARAM_DEPANGLE_METH        12
#define PARAM_VPAT_MTILT           14
#define PARAM_VPAT_GEN_MIRROR      15
#define PARAM_VPAT_GEN_TILT        16
#define PARAM_VPAT_GEN_DOUBLE     332
#define PARAM_CONTOUR_VPAT        333
#define PARAM_CONT_REAL_VPAT       17
#define PARAM_CONT_DERIVE_HPAT    202
#define PARAM_TRUST_DA_FLAG        18
#define PARAM_ABS_VAL_TILT         19

#define PARAM_AVG_TERR_DB          20
#define PARAM_AVG_TERR_RES         22
#define PARAM_PATH_TERR_DB         30
#define PARAM_PATH_TERR_RES        32

#define PARAM_US_CENSUS            40
#define PARAM_CA_CENSUS            42
#define PARAM_MX_CENSUS            44
#define PARAM_RND_POP_CRDS         46

#define PARAM_REPL_METH            50
#define PARAM_MIN_ERP_VLO          52
#define PARAM_MIN_ERP_VHI          54
#define PARAM_MIN_ERP_UHF          56
#define PARAM_MAX_ERP_VLO1         58
#define PARAM_MAX_ERP_VLO23        60
#define PARAM_MAX_ERP_VHI1         62
#define PARAM_MAX_ERP_VHI23        64
#define PARAM_MAX_ERP_UHF          66
#define PARAM_MIN_ERP_VHF_LP      327
#define PARAM_MIN_ERP_UHF_LP      328
#define PARAM_MAX_ERP_VHF_LP       68
#define PARAM_MAX_ERP_UHF_LP       70

#define PARAM_SERVAREA_MODE       199
#define PARAM_SERVAREA_ARG        201
#define PARAM_MAX_DIST            215

#define PARAM_CL_VLO_DTV           80
#define PARAM_CL_VHI_DTV           82
#define PARAM_CL_UHF_DTV           84
#define PARAM_CL_VLO_DTV_LP        86
#define PARAM_CL_VHI_DTV_LP        88
#define PARAM_CL_UHF_DTV_LP        90
#define PARAM_CL_VLO_NTSC          92
#define PARAM_CL_VHI_NTSC          94
#define PARAM_CL_UHF_NTSC          96
#define PARAM_CL_VLO_NTSC_LP       98
#define PARAM_CL_VHI_NTSC_LP      100
#define PARAM_CL_UHF_NTSC_LP      102
#define PARAM_USE_DIPOLE_CL       104
#define PARAM_DIPOLE_CENTER_CL    106

#define PARAM_CURV_DTV            107
#define PARAM_CURV_NTSC           108

#define PARAM_CL_FM               357
#define PARAM_CL_FM_B             358
#define PARAM_CL_FM_B1            359
#define PARAM_CL_FM_ED            360
#define PARAM_CL_FM_LP            361
#define PARAM_CL_FM_TX            362

#define PARAM_CURV_FM             363

#define PARAM_OFF_CURV_METH       329

#define PARAM_DTS_DIST_CHECK      326
#define PARAM_TRUNC_DTS           110
#define PARAM_DTS_DIST_VLO1       111
#define PARAM_DTS_DIST_VLO23      112
#define PARAM_DTS_DIST_VHI1       113
#define PARAM_DTS_DIST_VHI23      114
#define PARAM_DTS_DIST_UHF        115

#define PARAM_LRCON_TERR_DB       175
#define PARAM_LRCON_TERR_RES      177
#define PARAM_LRCON_DIST_STEP     159
#define PARAM_LRCON_LOC_DTV       179
#define PARAM_LRCON_TME_DTV       181
#define PARAM_LRCON_CNF_DTV       183
#define PARAM_LRCON_LOC_NTSC      185
#define PARAM_LRCON_TME_NTSC      187
#define PARAM_LRCON_CNF_NTSC      188
#define PARAM_LRCON_RECV_HGT      161
#define PARAM_LRCON_SIG_POL       163
#define PARAM_LRCON_ATM_REFRAC    165
#define PARAM_LRCON_GND_PERMIT    167
#define PARAM_LRCON_GND_CONDUC    169
#define PARAM_LRCON_SERV_MODE     171
#define PARAM_LRCON_CLIM_TYPE     173

#define PARAM_CHECK_SELF_IX       193
#define PARAM_KM_PER_MS           194
#define PARAM_MAX_LEAD_TIME       195
#define PARAM_MAX_LAG_TIME        196
#define PARAM_SELF_IX_DU          197
#define PARAM_SELF_IX_UTIME       154

#define PARAM_CAP_DU_RAMP         306
#define PARAM_DU_RAMP_CAP         307

#define PARAM_FM_ADJ_IBOC         248

#define PARAM_TV6_FM_DIST         244
#define PARAM_TV6_FM_CURVES       246
#define PARAM_TV6_FM_DTV_METH     203
#define PARAM_TV6_FM_DU_DTV       204
#define PARAM_TV6_FM_TME_UND_FM   245
#define PARAM_TV6_FM_DIST_DTV     242
#define PARAM_TV6_FM_BASE_DU_DTV  218
#define PARAM_TV6_FM_TME_UND_TV   243

#define PARAM_HAAT_RADIALS        120
#define PARAM_HAAT_RADIALS_LPTV   158
#define PARAM_MIN_HAAT            121
#define PARAM_AVET_STRT           330
#define PARAM_AVET_END            331
#define PARAM_CONTOUR_RADIALS     122
#define PARAM_CONT_LIMIT_VLO      124
#define PARAM_CONT_LIMIT_VHI      126
#define PARAM_CONT_LIMIT_UHF      128

#define PARAM_RECEIVE_HEIGHT      130
#define PARAM_MIN_XMTR_HEIGHT     132

#define PARAM_RPAT_VLO_DTV        140
#define PARAM_RPAT_VHI_DTV        142
#define PARAM_RPAT_UHF_DTV        144
#define PARAM_RPAT_VLO_NTSC       146
#define PARAM_RPAT_VHI_NTSC       148
#define PARAM_RPAT_UHF_NTSC       150

#define PARAM_RPAT_FM             151

#define PARAM_LOC_DTV_DES         160
#define PARAM_TME_DTV_DES         162
#define PARAM_CNF_DTV_DES         164
#define PARAM_LOC_DTV_UND         166
#define PARAM_CNF_DTV_UND         168
#define PARAM_LOC_NTSC_DES        170
#define PARAM_TME_NTSC_DES        172
#define PARAM_CNF_NTSC_DES        174
#define PARAM_LOC_NTSC_UND        176
#define PARAM_CNF_NTSC_UND        178
#define PARAM_SIG_POL             180
#define PARAM_ATM_REFRAC          182
#define PARAM_GND_PERMIT          184
#define PARAM_GND_CONDUC          186

#define PARAM_LR_SERV_MODE        190
#define PARAM_LR_CLIM_TYPE        192

#define PARAM_EARTH_SPH_DIST      200

#define PARAM_RUL_EXT_MAX         189
#define PARAM_RUL_EXT_DST_L       335
#define PARAM_RUL_EXT_ERP_L       336
#define PARAM_RUL_EXT_DST_LM      337
#define PARAM_RUL_EXT_ERP_M       338
#define PARAM_RUL_EXT_DST_MH      339
#define PARAM_RUL_EXT_ERP_H       340
#define PARAM_RUL_EXT_DST_H       210
#define PARAM_CO_CHAN_MX_DIST     212

#define PARAM_GEN_VPAT            219

#define PARAM_OA_HAAT_RADIALS     334

#define PARAM_MEX_ERP_D_VLO       220
#define PARAM_MEX_HAAT_D_VLO      221
#define PARAM_MEX_ERP_D_VHI       222
#define PARAM_MEX_HAAT_D_VHI      223
#define PARAM_MEX_ERP_D_UHF       224
#define PARAM_MEX_HAAT_D_UHF      225
#define PARAM_MEX_ERP_N_VLO       226
#define PARAM_MEX_HAAT_N_VLO      227
#define PARAM_MEX_ERP_N_VHI       228
#define PARAM_MEX_HAAT_N_VHI      229
#define PARAM_MEX_ERP_N_UHF       230
#define PARAM_MEX_HAAT_N_UHF      231

#define PARAM_MEX_ERP_FM_A        232
#define PARAM_MEX_HAAT_FM_A       233
#define PARAM_MEX_ERP_FM_B        234
#define PARAM_MEX_HAAT_FM_B       235
#define PARAM_MEX_ERP_FM_B1       236
#define PARAM_MEX_HAAT_FM_B1      237
#define PARAM_MEX_ERP_FM_C        238
#define PARAM_MEX_HAAT_FM_C       239
#define PARAM_MEX_ERP_FM_C1       240
#define PARAM_MEX_HAAT_FM_C1      241

#define PARAM_APPLY_CLUTTER       249
#define PARAM_LANDCOVER_VERSION   205
#define PARAM_CLUTTER             250   // Start of a sequential range of parameter keys, see parameter.c.
#define PARAM_N_CLUTTER            40
#define PARAM_N_LANDCOVER          16

#define PARAM_SET_SERVICE         309
#define PARAM_SL_VLO_DTV          310
#define PARAM_SL_VHI_DTV          311
#define PARAM_SL_UHF_DTV          312
#define PARAM_SL_VLO_DTV_LP       313
#define PARAM_SL_VHI_DTV_LP       314
#define PARAM_SL_UHF_DTV_LP       315
#define PARAM_SL_VLO_NTSC         316
#define PARAM_SL_VHI_NTSC         317
#define PARAM_SL_UHF_NTSC         318
#define PARAM_SL_VLO_NTSC_LP      319
#define PARAM_SL_VHI_NTSC_LP      320
#define PARAM_SL_UHF_NTSC_LP      321
#define PARAM_USE_DIPOLE_SL       322
#define PARAM_DIPOLE_CENTER_SL    323

#define PARAM_SL_FM               364
#define PARAM_SL_FM_B             365
#define PARAM_SL_FM_B1            366
#define PARAM_SL_FM_ED            367
#define PARAM_SL_FM_LP            368
#define PARAM_SL_FM_TX            369

#define PARAM_MIN_CHANNEL         324
#define PARAM_MAX_CHANNEL         325

#define PARAM_WL_REQD_DU          341
#define PARAM_WL_CULL_DIST        247
#define PARAM_WL_CAP_DU_RAMP      308
#define PARAM_WL_DU_RAMP_CAP      342
#define PARAM_WL_AVG_TERR_RES     343
#define PARAM_WL_PATH_TERR_RES    344
#define PARAM_WL_OA_HAAT_RADIALS  345
#define PARAM_WL_DEPANGLE_METH    346
#define PARAM_WL_VPAT_MTILT       347
#define PARAM_WL_VPAT_GEN_MIRROR  348
#define PARAM_WL_MIN_XMTR_HEIGHT  349
#define PARAM_WL_LOC_UND          350
#define PARAM_WL_TME_UND          351
#define PARAM_WL_CNF_UND          352
#define PARAM_WL_SIG_POL          353
#define PARAM_WL_LR_SERV_MODE     354

#define PARAM_SCEN_WL_FREQ        355
#define PARAM_SCEN_WL_BW          356

#define PARAM_TVIX_LIMIT          216
#define PARAM_TVIX_LIMIT_LPTV     217
#define PARAM_TVIX_CHECK_BL       211
#define PARAM_TVIX_BL_AREA_PCT    213
#define PARAM_TVIX_BL_POP_PCT     214

// Constants for various parameters that are enumerated options, must match database contents.

#define GRID_TYPE_LOCAL   1   // Values for PARAM_GRID_TYPE.
#define GRID_TYPE_GLOBAL  2

#define POINT_METHOD_CENTROID  1   // Values for PARAM_POINT_METHOD.
#define POINT_METHOD_CENTER    2
#define POINT_METHOD_LARGEST   3
#define POINT_METHOD_ALL       4

#define ERRORS_IGNORE     1   // Values for PARAM_ERROR_HNDLNG.
#define ERRORS_SERVICE    2
#define ERRORS_NOSERVICE  3

#define DEPANGLE_METH_EFF_HGT  1   // Values for PARAM_DEPANGLE_METH.
#define DEPANGLE_METH_TRUE     2

#define VPAT_MTILT_NEVER      0   // Values for PARAM_VPAT_MTILT.
#define VPAT_MTILT_ALWAYS     1
#define VPAT_MTILT_REAL_ONLY  2

#define VPAT_GEN_MIRROR_NEVER     0   // Values for PARAM_VPAT_GEN_MIRROR.
#define VPAT_GEN_MIRROR_ALWAYS    1
#define VPAT_GEN_MIRROR_PROPONLY  2

#define VPAT_GEN_TILT_NONE    0   // Values for PARAM_VPAT_GEN_TILT.
#define VPAT_GEN_TILT_FULL    1
#define VPAT_GEN_TILT_OFFSET  2

#define VPAT_GEN_DOUBLE_NEVER   0   // Values for PARAM_VPAT_GEN_DOUBLE.
#define VPAT_GEN_DOUBLE_LPTV    1
#define VPAT_GEN_DOUBLE_LPTV_A  2

#define CONTOUR_VPAT_NEVER   0   // Values for PARAM_CONTOUR_VPAT.
#define CONTOUR_VPAT_TVFULL  1
#define CONTOUR_VPAT_ALL     2

#define DERIVE_HPAT_NO       0   // Values for PARAM_CONT_DERIVE_HPAT
#define DERIVE_HPAT_HPLANE   1
#define DERIVE_HPAT_RADHORZ  2

#define REPL_METH_DERIVE  1   // Values for PARAM_REPL_METH.
#define REPL_METH_AREA    2

// Mode values for contours and for alternate service area modes per source.  Parameters set a default method for the
// service area mode for all sources, used for sources that have the SERVAREA_CONTOUR_DEFAULT mode.  The default can be
// any of the other CONTOUR modes.  The L-R contour modes need an argument value setting either a percentage or length
// threshold used in the algorithm.  Individual source records may also have an explicit mode overriding the default,
// with arguments specific to that source only.  In addition to contour modes, explicit geographies can be used on an
// individual source.  The geography may be in it's fixed location or automatically re-located to the source.  The
// "no bounds" option is just a fixed large radius at the maximum calculation distance parameter.  Also a fixed-radius
// area can be selected directly with the radius in the mode argument field.

#define SERVAREA_CONTOUR_DEFAULT        0
#define SERVAREA_CONTOUR_FCC            1
#define SERVAREA_CONTOUR_LR_PERCENT     2
#define SERVAREA_CONTOUR_LR_RUN_ABOVE   3
#define SERVAREA_CONTOUR_LR_RUN_BELOW   4
#define SERVAREA_GEOGRAPHY_FIXED        5
#define SERVAREA_GEOGRAPHY_RELOCATED    6
#define SERVAREA_NO_BOUNDS              7
#define SERVAREA_CONTOUR_FCC_ADD_DIST   8
#define SERVAREA_CONTOUR_FCC_ADD_PCNT   9
#define SERVAREA_RADIUS                10

#define SERVAREA_CL_DEFAULT  -999.   // Value for individual source contour level which means to use parameter values.

#define GEO_TYPE_CIRCLE   2   // Geography types used for service areas.
#define GEO_TYPE_BOX      3
#define GEO_TYPE_POLYGON  4
#define GEO_TYPE_SECTORS  5

// Database keys from root index tables for country, service, etc., also must match database.

#define CNTRY_USA  1   // Country keys in source records - USA.
#define CNTRY_CAN  2   // Canada.
#define CNTRY_MEX  3   // Mexico.

#define MAX_COUNTRY  3   // Number of possible countries.  WARNING!  The code uses country keys as index values in
                         // arrays, after subtracting 1.  The key range must be 1 through MAX_COUNTRY without gaps.

#define TV6_FM_CHAN_BASE   200   // Starting channel in FM and TV channel 6 distance and D/U lookup tables.
#define TV6_FM_CHAN_COUNT   21   // Number of channels in FM and TV channel 6 distance and D/U lookup tables.
#define TV6_FM_CURVE_MIN   47.   // Minimum TV desired signal strength on D/U lookup curves.
#define TV6_FM_CURVE_STEP   1.   // Step in dBu between points in curve lookup tables.
#define TV6_FM_CURVE_COUNT  44   // Number of points in FM to TV channel 6 D/U curve tables.
#define TV6_FM_METH_CURVE    1   // Values for PARAM_TV6_FM_DTV_METH.
#define TV6_FM_METH_FIXED    3

#define WL_OVERLAP_COUNT    6   // Number of spectral overlap cases for wireless interference.
#define WL_CULL_HAAT_COUNT  8   // Number of rows and columns in wireless culling distance tables.
#define WL_CULL_ERP_COUNT   9

#define SERVTYPE_DTV_FULL      1   // Service type keys for source records.  Full service digital.
#define SERVTYPE_NTSC_FULL     2   // Full service analog.
#define SERVTYPE_DTV_CLASS_A   3   // Class A digital.
#define SERVTYPE_NTSC_CLASS_A  4   // Class A analog.
#define SERVTYPE_DTV_LPTV      5   // LPTV/translator/booster digital.
#define SERVTYPE_NTSC_LPTV     6   // LPTV/translator/booster analog.
#define SERVTYPE_WIRELESS     11   // Wireless.
#define SERVTYPE_FM_FULL      21   // FM full-service.
#define SERVTYPE_FM_IBOC      22   // FM full-service with IBOC (hybrid).
#define SERVTYPE_FM_LP        23   // FM low power.
#define SERVTYPE_FM_TX        24   // FM translator/booster.

#define ZONE_I     1   // Zone keys in source records.  Zone 1.
#define ZONE_II    2   // Zone 2.
#define ZONE_III   3   // Zone 3.
#define ZONE_NONE  0   // Undefined zone.

#define FREQ_OFFSET_ZERO   1   // Frequency offest keys.  Zero offset.
#define FREQ_OFFSET_PLUS   2   // + offset.
#define FREQ_OFFSET_MINUS  3   // - offset.
#define FREQ_OFFSET_NONE   0   // No offset or N/A.

#define LPTV_MASK_SIMPLE        1   // Emission mask keys for LPTV/Class A.  Simple mask.
#define LPTV_MASK_STRINGENT     2   // Stringent mask.
#define LPTV_MASK_FULL_SERVICE  3   // Full-service mask.
#define LPTV_MASK_NONE          0   // Unknown or N/A.

// These must match like-named code constants in the front end app, used in interference rule data, see source.c.

#define FREQUENCY_OFFSET_WITHOUT  1
#define FREQUENCY_OFFSET_WITH     2

// FM class keys must also match values in UI app.

#define FM_CLASS_OTHER  0
#define FM_CLASS_C      1
#define FM_CLASS_C0     2
#define FM_CLASS_C1     3
#define FM_CLASS_C2     4
#define FM_CLASS_C3     5
#define FM_CLASS_B      6
#define FM_CLASS_B1     7
#define FM_CLASS_A      8
#define FM_CLASS_D      9
#define FM_CLASS_L1    10
#define FM_CLASS_L2    11

// Other codes and values used only in this application, first values used in source.c.

#define SERV_TV    1   // Service type database key is translated to this enumeration, which is more relevant to the
#define SERV_TVCA  2   //  various code branchings.  Also for TV the dtv flag is set in the source structure for
#define SERV_TVLP  3   //  digital services.  For FM the distinction between analog and digital is not important
#define SERV_WL    4   //  other than during application of interference rules, but that code uses the service type
#define SERV_FM    5   //  key directly.  This scheme is a holdover from legacy code.
#define SERV_FMLP  6
#define SERV_FMTX  7

#define VPAT_MODE_CONTOUR  1   // Argument to vpat_lookup() determining how various parameters are applied.
#define VPAT_MODE_DERIVE   2
#define VPAT_MODE_PROP     3

#define REPL_ITERATION_LIMIT  100   // Maximum iterations for equal-area replication method.

// Clutter bands and types, values are used to compute an index into the ClutterValues[] parameter array.

#define CLUTTER_BAND_VLO_FM  0
#define CLUTTER_BAND_VHI     1
#define CLUTTER_BAND_ULO     2
#define CLUTTER_BAND_UHI_WL  3

#define N_CLUTTER_BANDS  4

#define CLUTTER_UNKNOWN                 0
#define CLUTTER_OPEN_LAND               1
#define CLUTTER_AGRICULTURAL            2
#define CLUTTER_RANGELAND               3
#define CLUTTER_WATER                   4
#define CLUTTER_FOREST_LAND             5
#define CLUTTER_WETLAND                 6
#define CLUTTER_RESIDENTIAL             7
#define CLUTTER_MIXED_URBAN_BUILDINGS   8
#define CLUTTER_COMMERCIAL_INDUSTRIAL   9
#define CLUTTER_SNOW_AND_ICE           10

// Constants for map file output code, see map.c.

#define MAP_FILE_SHAPE  1   // ESRI shapefile format.
#define MAP_FILE_KML    2   // KML format.

#define SHP_TYPE_NULL      0   // Supported shape types.
#define SHP_TYPE_POINT     1
#define SHP_TYPE_POLYLINE  3
#define SHP_TYPE_POLYGON   5

#define SHP_ATTR_CHAR  'C'   // Supported attribute types.
#define SHP_ATTR_NUM   'N'
#define SHP_ATTR_BOOL  'L'

#define RENDER_MAP_SCALE  500000.   // Map scale for rendering contours for map file output.

// Codes for selection of cache type, see cache.c.

#define CACHE_DES  1
#define CACHE_UND  2

// Values for database study_lock flag, must be in sync with UI source!

#define LOCK_NONE       0
#define LOCK_EDIT       1
#define LOCK_RUN_EXCL   2
#define LOCK_RUN_SHARE  3
#define LOCK_ADMIN      4

// Special HAMSL value used to indicate HAMSL must be derived from HAAT.

#define HEIGHT_DERIVE  -999.

// Heights are rounded to this precision to avoid false mismatch to cached values.

#define HEIGHT_ROUND  0.01

// Constants for output file and map output configuration.  Note the output flag and output FILE arrays use the same
// index values since flags map to no more than one output file and only a few flags are behavioral with no associated
// file.  However for the map output, most flags are behavioral with no file and those that do have associated files
// map to more than one file each, so the map flag and MAPFILE arrays use different index values.

#define REPORT_FILE_DETAIL     0
#define   REPORT_DETAIL_IX_ONLY  1
#define   REPORT_DETAIL_ALL_UND  2
#define REPORT_FILE_SUMMARY    1
#define CSV_FILE_DETAIL        2
#define   CSV_DETAIL_IX_ONLY     1
#define   CSV_DETAIL_ALL_UND     2
#define CSV_FILE_SUMMARY       3
#define CELL_FILE_DETAIL       4
#define   CELL_DETAIL_NORM       1
#define   CELL_DETAIL_CENPT      2
#define CELL_FILE_SUMMARY      5
#define CELL_FILE_PAIRSTUDY    6
#define CELL_FILE_CSV          7
#define POINTS_FILE           10
#define PARAMS_FILE           11
#define   PARAMS_FILE_ALL        1
#define   PARAMS_FILE_DES        2
#define SETTING_FILE          13
#define IXCHK_DEL_PASS        15
#define   IXCHK_DEL_PASS_ALL     1
#define   IXCHK_DEL_PASS_IX      2
#define IXCHK_MARG_CSV        16
#define   IXCHK_MARG_CSV_AGG     1
#define   IXCHK_MARG_CSV_AGGNO0  2
#define   IXCHK_MARG_CSV_ALL     3
#define   IXCHK_MARG_CSV_ALLNO0  4
#define POINT_FILE_PROF       17
#define DERIVE_HPAT_FILE      18
#define COMPOSITE_COVERAGE    19
#define IXCHK_PROP_CONT       20
#define   IXCHK_PROP_CONT_CSV    1
#define   IXCHK_PROP_CONT_CSVSHP 2
#define   IXCHK_PROP_CONT_CSVKML 3
#define IXCHK_REPORT_WORST    21

#define MAX_OUTPUT_FLAGS  22

#define CELL_FILE_CSV_D         22   // Cell CSV format needs multiple FILE opens.
#define CELL_FILE_CSV_U         23
#define CELL_FILE_CSV_WL_U      24
#define CELL_FILE_CSV_WL_U_RSS  25

#define IMAGE_FILE  26   // Part of map output settings, but a (temporary) FILE not a MAPFILE.

#define N_OUTPUT_FILES  27

#define MAP_OUT_AREAPOP    0
#define MAP_OUT_DESINFO    1
#define MAP_OUT_UNDINFO    2
#define MAP_OUT_MARGIN     3
#define MAP_OUT_WLINFO     4
#define MAP_OUT_SELFIX     5
#define MAP_OUT_RAMP       6
#define MAP_OUT_CLUTTER    7
#define MAP_OUT_NOSERV     8
#define MAP_OUT_NOPOP      9
#define MAP_OUT_CENTER    10
#define MAP_OUT_SHAPE     11
#define MAP_OUT_KML       12
#define MAP_OUT_IMAGE     13
#define   MAP_OUT_IMAGE_DMARG       1
#define   MAP_OUT_IMAGE_DUMARG      2
#define   MAP_OUT_IMAGE_WLDUMARG    3
#define   MAP_OUT_IMAGE_SELFDUMARG  4
#define   MAP_OUT_IMAGE_MARG        5
#define   MAP_OUT_IMAGE_DMARGNOIX   6
#define MAP_OUT_COORDS    14

#define MAX_MAP_OUTPUT_FLAGS  15

#define MAP_OUT_SHAPE_POINTS  0   // The MAPFILE opens needed.
#define MAP_OUT_SHAPE_COV     1
#define MAP_OUT_KML_POINTS    2
#define MAP_OUT_KML_COV       3
#define MAP_OUT_SHAPE_SELFIX  4
#define MAP_OUT_KML_SELFIX    5

#define N_MAP_OUTPUT_FILES  6

// Names for output files.

#define REPORT_FILE_NAME "tvstudy.txt"
#define SETTING_FILE_NAME "tvstudy_settings.txt"
#define CSV_FILE_NAME "tvstudy.csv"
#define CELL_FILE_NAME "tvstudy.cel"
#define POINTS_FILE_NAME "points.csv"
#define PARAMETER_FILE_NAME "parameters.csv"
#define TV_IX_FILE_NAME "tvixstudy.txt"
#define MARG_CSV_FILE_NAME "margins.csv"
#define PROP_CONT_BASE_NAME "tvixcontour"

// Names for alternate detail cell file CSV format, multiple files are created.

#define CELL_CSV_SOURCE_FILE_NAME "sources.csv"
#define CELL_CSV_D_FILE_NAME "D_CelData.csv"
#define CELL_CSV_U_FILE_NAME "U_CelData.csv"
#define CELL_CSV_WL_U_FILE_NAME "U_eNBCelData.csv"
#define CELL_CSV_WL_U_RSS_FILE_NAME "U_iRSSCelData.csv"

// Base names for map output files.

#define POINTS_MAPFILE_NAME "points"
#define COVERAGE_MAPFILE_NAME "coverpts"
#define COMPCOV_MAPFILE_NAME "cmpcvpts"
#define SOURCE_MAPFILE_NAME "sources"
#define CONTOUR_MAPFILE_NAME "contours"
#define IMAGE_MAPFILE_NAME "mapimage"
#define SELFIX_MAPFILE_NAME "selfix"

// Prefix and keys for inline status messages, see status_message() in log.c.

#define STATUS_MESSAGE_PREFIX "$$"
#define STATUS_KEY_PROGRESS "progress"
#define STATUS_KEY_OUTFILE "outfile"
#define STATUS_KEY_REPORT "report"
#define STATUS_KEY_RUNCOUNT "runcount"
#define STATUS_KEY_RESULT "result"
#define STATUS_KEY_ERROR "error"

// Source attribute keys, see get_source_attribute() in source.c.

#define ATTR_SEQUENCE_DATE    "sequenceDate"
#define ATTR_IS_SHARING_HOST  "isSharingHost"
#define ATTR_IS_BASELINE      "isBaseline"
#define ATTR_IS_PRE_BASELINE  "-isPreBaseline"
#define ATTR_IS_PROPOSAL      "-isProposal"


//---------------------------------------------------------------------------------------------------------------------
// Structure definitions.

#define BLOCK_ID_L  20   // Length of block ID, alphanumeric identifier for Census points.

typedef struct cpt {            // Data for one Census point.
	struct cpt *next;           // For list of points aggregated at a single study point.
	double latitude;            // Coordinates of Census point, north latitude and west longitude, NAD83.
	double longitude;
	int population;             // Population.
	int households;             // Households.
	char blockID[BLOCK_ID_L];   // Block ID for association of other data.
} CEN_POINT;

typedef struct fld {            // One calculated field strength at a study point, desired or undesired.
	struct fld *next;           // For list of fields at point.
	float bearing;              // Bearing from source to point, degrees true.
	float reverseBearing;       // Bearing from point to source, degrees true.
	float distance;             // Distance from source to point, kilometers.
	float fieldStrength;        // Field strength, dBu.
	unsigned short sourceKey;   // Source key.
	union {
		short percentTime;      // Percent time for undesired, fixed 0.01% precision.  Set by parameter for desireds.
		short isUndesired;      // False if this is a desired field.
	} a;
	short status;               // Status, 0 = calculated no error, >0 calculated with error, <0 needs calculation.
	union {
		short cached;           // True if data has been written to cache.  Caching not used in points mode.
		short inServiceArea;    // In points mode, true on desired field if point is inside station's service area.
	} b;
} FIELD;

typedef struct pt {            // Data for one study point.
	struct pt *next;           // For list of points e.g. multiple countries in one cell.
	CEN_POINT *censusPoints;   // Census points aggregated at study point, all from one country, may be NULL.
	FIELD *fields;             // Fields at point, desired and undesired, never NULL.
	double latitude;           // Coordinates of study point, north latitude and west longitude, NAD83.
	double longitude;
	union {
		int population;        // Total population at point, may be 0.
		int pointIndex;        // Point array index in points mode.  No population or area in points mode.
	} a;
	int households;            // Total househoulds at point, may be 0, not used in points mode.
	int cellLatIndex;          // Cell identifiers.  In grid mode these are the south-east corner of the containing
	int cellLonIndex;          //  cell in arc-seconds.  Not used in points mode, set to INVALID_LATLON_INDEX.
	union {
		float area;            // Area assigned to point, a portion of the cell area in case of multiple points.
		float receiveHeight;   // Receive height in points mode.
	} b;
	float elevation;           // Ground elevation at study coordinates.
	short countryKey;          // Country key, may be 0 for undetermined.
	short landCoverType;       // Land cover type, LANDCOVER_*.
	short clutterType;         // Receiver clutter type, CLUTTER_*.
	short cenPointStatus;      // Status of compiling the CEN_POINT list, see load_population().
} TVPOINT;

typedef struct {      // Data for a matrix pattern.
	int ns;           // Number of slices.
	double *value;    // Values are azimuths for elevation matrix or frequencies for azimuth matrix.
	int *np;          // Number of points in each slice.
	double **angle;   // Slice patterns, angle is depression or azimuth.
	double **pat;
	size_t siz;       // Size of allocated memory, see pattern.c.
} MPAT;

typedef struct rant {        // Data for a receive antenna.
	struct rant *next;       // For cache list, see pattern.c.
	MPAT *rpat;              // The azimuth pattern, simple or matrix by frequency.
	double gain;             // Antenna gain in dBd.
	int antennaKey;          // Primary record key.
	char name[MAX_STRING];   // Antenna name.
} RECEIVE_ANT;

typedef struct {                  // Additional info for a point in points study mode.
	RECEIVE_ANT *receiveAnt;      // Receive antenna, NULL for generic.
	int antennaKey;               // Receive antenna key, 0 for generic.
	float receiveOrient;          // Fixed azimuth orientation of receive pattern, -1 for auto to desired.
	char pointName[MAX_STRING];   // Point name.
} POINT_INFO;

typedef struct {           // Accumulator for interference.
	double ixArea;         // Areas in square kilometers - total interference,
	double uniqueIxArea;   //  unique interference (from this source only),
	double errorArea;      //  propagation model errors.
	int ixPop;             // Population totals, as above.
	int uniqueIxPop;
	int errorPop;
	int ixHouse;           // Households totals, as above.
	int uniqueIxHouse;
	int errorHouse;
	int report;            // Flag set if undesired was considered at any study point, regardless of predicted IX.
} UND_TOTAL;

typedef struct {                     // Information for an undesired source with respect to a specific desired source.
	UND_TOTAL totals[MAX_COUNTRY];   // Interference totals by country.
	FIELD *field;                    // Used during local setup and analysis loops to collect fields at a study point.
	double ixDistance;               // Maximum interference distance ("culling" distance).
	double requiredDU;               // Required (minimum) D/U for no interference.
	double duThresholdD;             // Desired signal threshold, signal must be >= for IX, 0 for no threshold test.
	double duThresholdU;             // Undesired signal threshold, 0 for no threshold test.
	int ucacheChecksum;              // Last record checksum in undesired cell cache file for local mode only.
	unsigned short sourceKey;        // Source key.
	short percentTime;               // Percent time for field strength prediction, fixed 0.01% precision.
	short checkIxDistance;           // True if ixDistance is checked during study point setup.
	short adjustDU;                  // Set for co-channel to DTV desired, D/U is not fixed, see dtv_codu_adjust().
	short computeTV6FMDU;            // True for FM NCE undesired to TV channel 6 desired when D/U is by curve lookup.
	short insideServiceArea;         // True if source is inside the desired service area, see find_undesired().
} UNDESIRED;

typedef struct {          // Accumulator for desired coverage.
	double contourArea;   // Areas in square kilometers - inside service contour or geography,
	double serviceArea;   //  above service level,
	double ixFreeArea;    //  no interference,
	double errorArea;     //  propagation model errors.
	int contourPop;       // Population totals, as above.
	int servicePop;
	int ixFreePop;
	int errorPop;
	int contourHouse;     // Households totals, as above.
	int serviceHouse;
	int ixFreeHouse;
	int errorHouse;
} DES_TOTAL;

typedef struct {    // Data for a vertical pattern.
	int np;         // Number of points in pattern.
	double *dep;    // Depression angles in degrees.
	double *pat;    // Pattern values in relative dB.
	double mxdep;   // Depression angle of max value, for "mirroring".
	size_t siz;     // Size of allocated memory, see pattern.c.
} VPAT;

typedef struct gpt {    // General-purpose geographic data structure, a list of coordinates.  See map.c.
	struct gpt *next;   // For linked lists (often not used).
	double *ptLat;      // Arrays of point coordinates, positive north and west, NAD83.
	double *ptLon;
	double xlts;        // Bounding box around points.
	double xlne;
	double xltn;
	double xlnw;
	int nPts;           // Current number of points stored, never greater than maxPts.
	int maxPts;         // Allocated size of the arrays, this may be 0 in which case ptLat and ptLon are NULL.
} GEOPOINTS;

typedef struct {         // Data for a signal level contour.
	double *distance;    // Projected contour distances in km for evenly-spaced radials.
	GEOPOINTS *points;   // Rendered contour points, may be NULL.
	double latitude;     // Contour center point coordinates, NAD83.
	double longitude;
	int mode;            // Contour projection mode, SERVAREA_CONTOUR_* (except DEFAULT).
	int count;           // Number of contour projection radials.
} CONTOUR;

typedef struct geo {             // Data for a service area geography.
	struct geo *next;            // For cache list, see map.c.
	union {
		double radius;           // Radius for circle, km.
		double width;            // Width for box, km.
		double *sectorAzimuth;   // Azimuths for sectors.
		GEOPOINTS *polygon;      // Data for polygon.
	} a;
	union {
		double height;           // Height for box, km.
		double *sectorRadius;    // Radii for sectors.
	} b;
	GEOPOINTS *points;           // Rendered boundary.
	double latitude;             // Center for circle, box, or sectors, reference point for polygon.  NAD83.
	double longitude;
	int geoKey;                  // Geography key.
	int type;                    // Geography type, GEO_TYPE_*.
	int nSectors;                // Number of sectors for sectors.
} GEOGRAPHY;

#define CALL_SIGN_L    17   // Lengths of source descriptive strings.
#define SERVICE_CODE_L  3
#define STATUS_L        7
#define CITY_L         21
#define STATE_L         3
#define FILE_NUMBER_L  23
#define RECORD_ID_L    37
#define ANTENNA_ID_L   37
#define PAT_NAME_L     41

typedef struct src {                  // Data for a signal source (not always a station; DTS station has many sources).
	DES_TOTAL totals[MAX_COUNTRY];    // Coverage totals by country.
	INDEX_BOUNDS cellBounds;          // Bounds of protected coverage area study cell grid, arc-second units.
	INDEX_BOUNDS gridIndex;           // Bounds of desired cells in the study grid, units of grid index.
	INDEX_BOUNDS ugridIndex;          // Bounds of undesired cells (only new calculations), units of grid index.
	double *hpat;                     // Horizontal pattern tabulation every 1 degree, relative dB.  NULL for omni.
	double *conthpat;                 // If non-NULL, derived horizontal pattern used for contour projection only.
	VPAT *vpat;                       // Vertical pattern data, NULL for generic or matrix.
	MPAT *mpat;                       // Matrix pattern data, or NULL for generic or vpat.
	CONTOUR *contour;                 // Service contour, depending on serviceAreaMode.
	GEOGRAPHY *geography;             // Service area geography, depending on serviceAreaMode.
	char *dtsSectors;                 // Sectors geography encoded as a string for DTS parent only.
	struct src *dtsRefSource;         // Reference facility source for DTS parent.
	struct src *dtsSources;           // Individual transmitter sources for DTS parent.
	struct src *parentSource;         // Parent for a DTS source, NULL on parent or non-DTS.
	UNDESIRED *undesireds;            // Undesired sources for this source as a desired, may be NULL.
	UND_TOTAL *selfIxTotals;          // DTS self-interference totals, on DTS parent only when self-IX enabled.
	struct src *next;                 // For secondary and working lists; the full source list is in a flat array.
	double frequency;                 // Transmitter frequency in MHz, computed from channel.
	double latitude;                  // Coordinates of transmitter, north latitude and west longitude, NAD83.
	double longitude;
	double dtsMaximumDistance;        // Distance for DTS parent, if 0 table values (per parameters) are used.
	double heightAMSL;                // Transmitter height AMSL, meters.  May be flag value to derive from HAAT.
	double actualHeightAMSL;          // Derived or modified height AMSL, cached in the database record.
	double heightAGL;                 // Derived height AGL, meters, cached in the database record.
	double overallHAAT;               // Overall height above average terrain, meters.
	double actualOverallHAAT;         // Derived or modified HAAT, cached in the database record.
	double peakERP;                   // Maximum ERP, dBk.
	double contourERP;                // ERP to project the contour, may be != peakERP for a replication source.
	double ibocFraction;              // For FM record with IBOC, digital ERP fraction of peak.
	double hpatOrientation;           // Horizontal pattern orientation value, degrees true.
	double vpatElectricalTilt;        // Electrical beam tilt, degrees of depression.
	double vpatMechanicalTilt;        // Mechanical beam tilt, degrees of depression.
	double vpatTiltOrientation;       // Orientation azimuth for mechanical tilt, degrees true.
	double contourLevel;              // Service contour level, dBu.
	double serviceLevel;              // Terrain-sensitive service threshold level, dBu.
	double cellArea;                  // Area of a cell in square kilometers for local mode, not used in global mode.
	double ruleExtraDistance;         // See set_rule_extra_distance() in source.c.
	double serviceAreaArg;            // Argument for service area determination, various uses depending on mode.
	double serviceAreaCL;             // Specific contour level for service area, or -999. to use parameters.
	double dtsTimeDelay;              // DTS transmitter relative time delay in microseconds for self-IX check.
	int facility_id;                  // Facility ID.
	int modCount;                     // Record modification count to detect invalid cache.
	int cellLonSize;                  // Longitude size of cells, all cells in local mode, minimum in global mode.
	int ucacheChecksum;               // Last record checksum in undesired cache file, in global mode only.
	int serviceAreaKey;               // Geography key for service area.
	int serviceAreaModCount;          // Geography record modification count to detect invalid cache.
	unsigned short sourceKey;         // Database primary key, always unique in a given study.
	short recordType;                 // Record type, RECORD_TYPE_*.
	short needsUpdate;                // True if source needs re-calculation and database record update.
	short inScenario;                 // True for sources in the current scenario being studied.
	short isDesired;                  // True if source is a desired (coverage studied and reported).
	short isUndesired;                // True if source is an undesired (potential interference source).
	short serviceTypeKey;             // Service type key, SERVTYPE_*.
	short service;                    // Service class, SERV_*, mapped from service type key.
	short dtv;                        // True for TV digital, false for anything else, mapped from service type key.
	short dts;                        // True for DTS (will always be DTV, may be any service).
	short isParent;                   // True for DTS parent source, gets lots of special handling.
	short fmClass;                    // Class for FM record, FM_CLASS_*.
	short iboc;                       // True for FM record when operating digital IBOC (hybrid mode only).
	short channel;                    // Channel number.
	short band;                       // Channel band, BAND_*, mapped from channel.
	short clutterBand;                // Channel band for clutter adjustments, CLUTTER_BAND_*, mapped from channel.
	short countryKey;                 // Country key, CNTRY_*.
	short zoneKey;                    // Zone key, ZONE_*.
	short signalTypeKey;              // Signal type for digital TV, else 0, used to match interference rules only.
	short frequencyOffsetKey;         // Frequency offset key, FREQ_OFFSET_*.
	short emissionMaskKey;            // LPTV/Class A digital emission mask key, LPTV_MASK_*.
	short hasHpat;                    // True if source has a directional horizontal pattern, else omni.
	short hasVpat;                    // True if source has vertical pattern, else generic or matrix is used.
	short hasMpat;                    // True if source has matrix pattern, else generic or vpat is used.
	short useGeneric;                 // True to use generic pattern when no vpat or mpat, if false, use no pattern.
	unsigned short origSourceKey;     // When replicating this is the original-channel source; else 0.
	short siteNumber;                 // Site number, generally just informational, but identifying for DTS sources.
	short undesiredCount;             // Count of undesired sources.
	short serviceAreaMode;            // Mode for determining service area, SERVAREA_*.
	short serviceAreaValid;           // True if service area parameters (grid limits, contours, etc.) are valid.
	short cached;                     // True if source data has been written to (or read from) cache.
	short hasDesiredCache;            // True if source has a desired cell cache file.
	short dcache;                     // Utility flags used during setup and study, may indicate cell data needs to be
	short ucache;                     //  loaded from cache, or new cell data needs to be written to cache.
	char callSign[CALL_SIGN_L];       // Information for reporting, not relevant to calculations.
	char serviceCode[SERVICE_CODE_L];
	char status[STATUS_L];
	char city[CITY_L];
	char state[STATE_L];
	char fileNumber[FILE_NUMBER_L];
	char recordID[RECORD_ID_L];
	char antennaID[ANTENNA_ID_L];
	char hpatName[PAT_NAME_L];
	char vpatName[PAT_NAME_L];
	char mpatName[PAT_NAME_L];
	char *attributes;                 // Attributes loaded only as needed, see get_source_attribute().
	char **attributeName;
	char **attributeValue;
	int nAttributes;
	short didLoadAttributes;
} SOURCE;

typedef struct spr {                     // Data for a post-run analysis scenario pairing, i.e. "before and after".
	DES_TOTAL totalsA[MAX_COUNTRY];      // Totals from the scenarios.
	DES_TOTAL totalsB[MAX_COUNTRY];
	double areaPercent[MAX_COUNTRY];     // Percentage changes in interference-free coverage.
	double popPercent[MAX_COUNTRY];
	double housePercent[MAX_COUNTRY];
	struct spr *next;                    // For linked list.
	SOURCE *sourceA;                     // Sources for before and after.
	SOURCE *sourceB;
	int scenarioKeyA;                    // Scenario keys for before and after.
	int scenarioKeyB;
	short didStudyA;                     // Set true if scenario and source were studied in the run and totals are set.
	short didStudyB;
	char name[MAX_STRING];               // Pair name, by convention ends with a '#1234' numerical identifier.
} SCENARIO_PAIR;

typedef struct {     // Define one attribute for a map file.  See map.c.
	char *name;      // Attribute name.
	char type;       // Attribute type, SHP_ATTR_*.
	int length;      // Attribute length.
	int precision;   // Decimal precision, 0 for integer or text.
} SHAPEATTR;

#define MAP_FOLDER_ID_LEN  20   // Max length of attribute value used to sort KML output into folders.

typedef struct msf {   // Temporary file for KML output folder structure, see map.c.
	char id[MAP_FOLDER_ID_LEN];
	char tempFileName[MAX_STRING];
	FILE *tempFile;
	struct msf *next;
} MAP_FOLDER;

typedef struct {       // Structure to manage a map output file in shapefile or KML format, see functions in map.c.
	int fileFormat;    // File format, MAP_OUT_SHAPE or MAP_OUT_KML.
	char *baseName;    // File identifier, base for file names and root name in KML.
	FILE *mapFile;     // Shape file or KML file.
	FILE *indexFile;   // Index file, not used for KML.
	FILE *dbFile;      // DBF file, not used for KML.
	int shapeType;     // Shape type in file, SHP_TYPE_*.
	int numAttr;       // Number of attributes (DBF fields).
	int *attrLen;      // Lengths of attributes for shapefile, not used for KML.
	char **attrName;   // Names of attributes for KML, not used for shapefile.
	int recordNum;     // Current record number in output, not used for KML.
	int totalSize;     // Current total size of shape output, not used for KML.
	double xmin;       // Accumulated bounding box, not used for KML.
	double ymin;
	double xmax;
	double ymax;
	int folderAttrIndex;   // Index to attribute used to sort KML output into folders, -1 for no sorting.
	MAP_FOLDER *folders;   // List of temporary files for folder contents, one for each attribute value seen.
} MAPFILE;

typedef struct {   // Structure to hold study parameters, see parameters.c for details.

	int GridType;                // GRID_TYPE_LOCAL or GRID_TYPE_GLOBAL.
	double CellSize;             // Target cell dimension in kilometers.
	int StudyPointMethod;        // POINT_METHOD_*.
	int StudyPointToNearestCP;   // If true, move any calculated point location to the nearest actual Census point.

	int ErrorHandling[MAX_COUNTRY];            // How to handle propagation model errors; ERRORS_*.
	int DepressionAngleMethod[MAX_COUNTRY];    // Depression angle calc method, DEPANGLE_METH_*.
	int VpatMechTilt[MAX_COUNTRY];             // When to apply mechanical beam tilt to pattern lookup, VPAT_MTILT_*.
	int GenericVpatMirroring[MAX_COUNTRY];     // Generic pattern mirroring, VPAT_GEN_MIRROR_*.
	int GenericVpatTilt[MAX_COUNTRY];          // Type of electrical tilt on generic patterns, VPAT_GEN_TILT_*.
	int GenericVpatDoubling[MAX_COUNTRY];      // When to double generic pattern value, VPAT_GEN_DOUBLE_*.
	int ContourVpatUse[MAX_COUNTRY];           // When to use vertical patterns for contours, CONTOUR_VPAT_*.
	int ContoursUseRealVpat[MAX_COUNTRY];      // If true contour projection may use non-generic vertical patterns.
	int ContourDeriveHpat[MAX_COUNTRY];        // Derive pattern for contour projection, DERIVE_HPAT_*.
	int AbsoluteValueTilts[MAX_COUNTRY];       // If true use absolute value of beam tilts, assume <0 should be >0.

	int TerrAvgDb;                     // TERR_DB0, TERR_DB1, TERR_DB3, or TERR_DB30 for average terrain calculations.
	double TerrAvgPpk[MAX_COUNTRY];    // Terrain profile resolution for average terrains, points/kilometer.
	int TerrPathDb;                    // As above, for propagation model calculations.
	double TerrPathPpk[MAX_COUNTRY];

	int CenYear[MAX_COUNTRY];          // Census database year by country, may be 0 to disable country.
	int RoundPopCoords[MAX_COUNTRY];   // True to round all Census point coordinates to the nearest arc-second.

	int ReplicationMethod[MAX_COUNTRY];   // Method for performing contour replication, REPL_METH_*.
	double MinimumVloERP[MAX_COUNTRY];    // Minimum and maximum ERPs, in kW, for TV digital replications.  Applied to
	double MinimumVhiERP[MAX_COUNTRY];    //  adjust the ERP after matching the contour.
	double MinimumUhfERP[MAX_COUNTRY];
	double MaximumVloZ1ERP[MAX_COUNTRY];
	double MaximumVloZ23ERP[MAX_COUNTRY];
	double MaximumVhiZ1ERP[MAX_COUNTRY];
	double MaximumVhiZ23ERP[MAX_COUNTRY];
	double MaximumUhfERP[MAX_COUNTRY];
	double MinimumVhfErpLPTV[MAX_COUNTRY];
	double MinimumUhfErpLPTV[MAX_COUNTRY];
	double MaximumVhfErpLPTV[MAX_COUNTRY];
	double MaximumUhfErpLPTV[MAX_COUNTRY];

	int ServiceAreaMode[MAX_COUNTRY];     // Default mode and possible argument, mode is SERVAREA_CONTOUR_* (other
	double ServiceAreaArg[MAX_COUNTRY];   //  than DEFAULT), argument is the L-R contour method argument.

	double MaximumDistance;   // Maximum desired signal calculation distance, applies in various modes.

	double ContourVloDigital[MAX_COUNTRY];        // TV service contour levels in dBu by band and service, and country.
	double ContourVhiDigital[MAX_COUNTRY];
	double ContourUhfDigital[MAX_COUNTRY];
	double ContourVloDigitalLPTV[MAX_COUNTRY];
	double ContourVhiDigitalLPTV[MAX_COUNTRY];
	double ContourUhfDigitalLPTV[MAX_COUNTRY];
	double ContourVloAnalog[MAX_COUNTRY];
	double ContourVhiAnalog[MAX_COUNTRY];
	double ContourUhfAnalog[MAX_COUNTRY];
	double ContourVloAnalogLPTV[MAX_COUNTRY];
	double ContourVhiAnalogLPTV[MAX_COUNTRY];
	double ContourUhfAnalogLPTV[MAX_COUNTRY];
	int UseDipoleCont[MAX_COUNTRY];               // True to adjust UHF contour levels by dipole factor.
	double DipoleCenterFreqCont[MAX_COUNTRY];     // Center frequency for UHF dipole adjustment.

	int CurveSetDigital[MAX_COUNTRY];   // FCC propagation curve set for TV desired contour projection.
	int CurveSetAnalog[MAX_COUNTRY];

	double ContourFM[MAX_COUNTRY];     // FM service contour levels in dBu by band and service, and country.
	double ContourFMB[MAX_COUNTRY];
	double ContourFMB1[MAX_COUNTRY];
	double ContourFMED[MAX_COUNTRY];
	double ContourFMLP[MAX_COUNTRY];
	double ContourFMTX[MAX_COUNTRY];

	int CurveSetFM[MAX_COUNTRY];   // FCC propagation curve set for FM desired contour projection.

	int OffCurveLookupMethod;   // Lookup method used off the low end of propagation curves, OFF_CURV_METH_*.

	int CheckIndividualDTSDistance;   // If true, check distance to nearest DTS source, else use reference point.
	int TruncateDTS;                  // If true, truncate DTS service by pre-DTS contour and radius around ref point.
	double DTSMaxDistVloZ1;           // DTS distance limits used by band and zone, if truncation is on.
	double DTSMaxDistVloZ23;
	double DTSMaxDistVhiZ1;
	double DTSMaxDistVhiZ23;
	double DTSMaxDistUHF;

	int LRConTerrDb;                 // Terrain database and resolution for Longley-Rice contour projection.
	double LRConTerrPpk;
	double LRConDistanceStep;        // Distance increment between pathloss calculation points along profile.
	double LRConDigitalLocation;     // Model parameteres for Longley-Rice contour projection, see discussion below.
	double LRConDigitalTime;
	double LRConDigitalConfidence;
	double LRConAnalogLocation;
	double LRConAnalogTime;
	double LRConAnalogConfidence;
	double LRConReceiveHeight;
	int LRConSignalPolarization;
	double LRConAtmosphericRefractivity;
	double LRConGroundPermittivity;
	double LRConGroundConductivity;
	int LRConServiceMode;
	int LRConClimateType;

	int CheckSelfInterference;         // True to include DTS self-interference.
	double KilometersPerMicrosecond;   // Speed of light.
	double PreArrivalTimeLimit;        // Limiting difference in arrival time, undesired arrives early, microseconds.
	double PostArrivalTimeLimit;       // Limiting difference in arrival time, undesired arrives late, microseconds.
	double SelfIxRequiredDU;           // D/U requirement when outside the arrival time window.
	double SelfIxUndesiredTime;        // Percent time for projecting signals as undesired for self-interference.

	int CapDURamp;       // True to cap D/U ramp function for TV interference.
	double DURampCap;    // Cap value.

	int AdjustFMForIBOC;   // True to adjust FM D/U ratios when undesired is IBOC.

	double TV6FMDistance[TV6_FM_CHAN_COUNT];                      // FM to TV undesired culling distance table.
	double TV6FMCurves[TV6_FM_CHAN_COUNT * TV6_FM_CURVE_COUNT];   // FM to TV undesired 73.525 D/U tables.
	int TV6FMDtvMethod;                                           // FM to DTV undesired D/U method.
	double TV6FMDuDtv[TV6_FM_CHAN_COUNT];                         // FM to DTV undesired fixed D/U tables.
	double TV6FMUndesiredTimeFM;                                  // FM undesired percent time.
	double TV6FMDistanceDtv[TV6_FM_CHAN_COUNT];                   // TV undesired culling distance table.
	double TV6FMBaseDuDtv;                                        // TV undesired base D/U (adj. by emission mask).
	double TV6FMUndesiredTimeTV;                                  // TV undesired percent time.

	int HAATCount[MAX_COUNTRY];              // Number of radials in HAAT lookups.
	int HAATCountLPTV[MAX_COUNTRY];          // Number of radials in HAAT lookups for LPTV.
	double MinimumHAAT[MAX_COUNTRY];         // Minimum HAAT on any radial.
	double AVETStartDistance[MAX_COUNTRY];   // Distance ranges for terrain averaging, by country.
	double AVETEndDistance[MAX_COUNTRY];
	int ContourCount[MAX_COUNTRY];           // Number of radials in contour projection.
	double ContourLimitVlo[MAX_COUNTRY];     // Contour distance limits by band and country.
	double ContourLimitVhi[MAX_COUNTRY];
	double ContourLimitUHF[MAX_COUNTRY];

	double ReceiveHeight;      // Receiver height above ground.
	double MinimumHeightAGL;   // Minimum AGL height for transmitters, AMSL will be adjusted to meet this.

	double ReceivePatVloDigital;   // Front-to-back ratios in dB for receive antenna patterns, by band and service.
	double ReceivePatVhiDigital;
	double ReceivePatUhfDigital;
	double ReceivePatVloAnalog;
	double ReceivePatVhiAnalog;
	double ReceivePatUhfAnalog;

	double ReceivePatFM;   // F/R in dB for FM receive antenna pattern.

	double DigitalDesiredLocation;        // Statistical parameters for propagation model, percentages.  The percentage
	double DigitalDesiredTime;            // for time for undesired signals is variable and comes from the interference
	double DigitalDesiredConfidence;      // rule matching the specific desired-undesired case.
	double DigitalUndesiredLocation;
	double DigitalUndesiredConfidence;
	double AnalogDesiredLocation;
	double AnalogDesiredTime;
	double AnalogDesiredConfidence;
	double AnalogUndesiredLocation;
	double AnalogUndesiredConfidence;
	int SignalPolarization;            // Signal polarization for propagation model.
	double AtmosphericRefractivity;    // Refractivity in N-units, for propagation model and depression angle calcs.
	double GroundPermittivity;         // Soil constants for propagation model.
	double GroundConductivity;

	int LRServiceMode;   // Longley-Rice service mode.
	int LRClimateType;   // Longley-Rice climate type.

	double KilometersPerDegree;   // Spherical earth distance conversion, kilometers/degree.

	double RuleExtraDistance[4];      // "Safety zone" distance vs. ERP table for interference rule distance checks.
	double RuleExtraDistanceERP[3];
	double UseMaxRuleExtraDistance;   // If true use MaximumDistance as rule extra in all cases, ignore table.

	int OverallHAATCount[MAX_COUNTRY];   // Number of radials for overall HAAT calculation.

	int ApplyClutter;                                      // True to adjust for receiver clutter.
	int LandCoverVersion;                                  // Version of NLCD data, 2006 or 2011.
	double ClutterValues[PARAM_N_CLUTTER * MAX_COUNTRY];   // Clutter adjustments, by clutter type, band, and country.
	int LandCoverClutter[PARAM_N_LANDCOVER];               // Map of land-cover categories to clutter types.

	int SetServiceLevels;   // If true service levels are separate, else they are same as contours.

	double ServiceVloDigital[MAX_COUNTRY];      // Service TV threshold levels in dBu by band, service, and country,
	double ServiceVhiDigital[MAX_COUNTRY];      //  used only when SetServiceLevels is true, else ignored.
	double ServiceUhfDigital[MAX_COUNTRY];
	double ServiceVloDigitalLPTV[MAX_COUNTRY];
	double ServiceVhiDigitalLPTV[MAX_COUNTRY];
	double ServiceUhfDigitalLPTV[MAX_COUNTRY];
	double ServiceVloAnalog[MAX_COUNTRY];
	double ServiceVhiAnalog[MAX_COUNTRY];
	double ServiceUhfAnalog[MAX_COUNTRY];
	double ServiceVloAnalogLPTV[MAX_COUNTRY];
	double ServiceVhiAnalogLPTV[MAX_COUNTRY];
	double ServiceUhfAnalogLPTV[MAX_COUNTRY];
	int UseDipoleServ[MAX_COUNTRY];             // True to adjust UHF service levels by dipole factor.
	double DipoleCenterFreqServ[MAX_COUNTRY];   // Center frequency for UHF dipole adjustment.

	double ServiceFM[MAX_COUNTRY];     // FM service threshold levels in dBu by band and service, and country.
	double ServiceFMB[MAX_COUNTRY];
	double ServiceFMB1[MAX_COUNTRY];
	double ServiceFMED[MAX_COUNTRY];
	double ServiceFMLP[MAX_COUNTRY];
	double ServiceFMTX[MAX_COUNTRY];

	double WirelessDU[WL_OVERLAP_COUNT];   // D/U for varying channel overlap for wireless interference.

	double WirelessCullDistance[WL_OVERLAP_COUNT * WL_CULL_HAAT_COUNT * WL_CULL_ERP_COUNT];   // Culling dist. tables.

	int WirelessCapDURamp;      // True to cap D/U ramp function for wireless interference.
	double WirelessDURampCap;   // Cap value.

	double WirelessUndesiredTime;   // Percent time for wireless undesired signal projection.

	double WirelessTerrAvgPpk;           // Overrides of other parameters applying to wireless sources.
	double WirelessTerrPathPpk;
	int WirelessOverallHAATCount;
	int WirelessDepressionAngleMethod;
	int WirelessVpatMechTilt;
	int WirelessGenericVpatMirroring;
	double WirelessMinimumHeightAGL;
	double WirelessUndesiredLocation;
	double WirelessUndesiredConfidence;
	int WirelessSignalPolarization;
	int WirelessLRServiceMode;

	double WirelessFrequency;    // Wireless station center frequency in MHz, scenario parameter.
	double WirelessBandwidth;    // Wireless station bandwidth, scenario parameter.

	double WirelessLowerBandEdge;   // Lower band edge, MHz.
	double WirelessUpperBandEdge;   // Upper band edge, MHz.

	double IxCheckLimitPercent;        // Limits for scenario pair analysis in interference-check study.
	double IxCheckLimitPercentLPTV;

	int CheckBaselineAreaPop;           // For interference-check study, compare study record to baseline.
	double BaselineAreaExtendPercent;   // Percentage changes of study record vs. baseline.
	double BaselinePopReducePercent;
} PARAMS;


//---------------------------------------------------------------------------------------------------------------------
// Function prototypes.

// study.c

void set_out_path(char *thePath);
int open_study(char *host, char *name, char *user, char *pass, int studyKey, char *studyName, int inheritLockCount,
	int keepLock, int runNumber, int cacheUndesired);
int check_study_lock();
void close_study(int hadError);
int find_scenario_name(char *scenarioName);
void parse_flags(char *flags, int *outFlags, int maxFlag);
int run_ix_study(int probeMode);
int run_scenario(int scenarioKey);
FILE *open_file(char *fileName);
char *get_file_path();
FILE *open_sum_file(char *fileName);
char *get_sum_file_path();
FILE *open_temp_file();

// parameter.c

int load_study_parameters();
int load_scenario_parameters(int scenarioKey);

// source.c

int load_sources(int studyNeedsUpdate);
int load_scenario(int scenarioKey);
char *get_source_attribute(SOURCE *source, char *attr);
CONTOUR *project_fcc_contour(SOURCE *source, int curveSet, double contourLevel);
CONTOUR *contour_alloc(double latitude, double longitude, int mode, int count);
void contour_free(CONTOUR *contour);
double *compute_source_haat(SOURCE *source, int haatCount);
double interp_cont(double lookup, CONTOUR *contour);
double interp(double lookup, double *table, int count);
double interp_min(double lookup, double *table, int count, double minVal, int minMode);

// report.c

void write_report_preamble(int reportFlag, FILE *reportFile, int showExtDb, int showScenario);
void write_report(int reportFlag, int optionFlag, FILE *reportFile, int doHeader);
void write_pair_report(SCENARIO_PAIR *thePair, FILE *reportFile, int doHeader);
void write_csv_preamble(int csvFlag, FILE *csvFile, int showExtDb, int showScenario);
void write_csv(int csvFlag, int optionFlag, FILE *csvFile, int doHeader);
void write_pair_csv(SCENARIO_PAIR *thePair, FILE *csvFile, int doHeader);
void write_parameters(int paramsFlag, FILE *paramsFile);
char *source_label(SOURCE *source);
char *channel_label(SOURCE *source);
char *pop_commas(int av);
char *erpkw_string(double erpDbk);

// cell.c

int global_lon_size(int latIndex);
double cell_area(int latIndex, int lonSize);
int grid_setup(INDEX_BOUNDS cellBounds, int cellLonSize);
int load_grid_population(SOURCE *source);
int cell_setup(SOURCE *source, int *cacheCount, int reloadCensusPoints);
int load_points();
int clear_points();
int points_setup(SOURCE *source);
TVPOINT *make_point(double lat, double lon, SOURCE *source);
void free_point(TVPOINT *point);
TVPOINT *get_point();
CEN_POINT *get_cen_point();
FIELD *get_field();

// cache.c

void clear_cache(SOURCE *source);
int cache_setup(int studyNeedsUpdate);
int read_source_cache(SOURCE *source);
int write_source_cache(SOURCE *source);
int read_cell_cache(SOURCE *source, int cacheType, unsigned short desiredSourceKey, int *ucacheChecksum,
	int *cacheCount);
int write_cell_cache(SOURCE *source, int cacheType, unsigned short desiredSourceKey, int *ucacheChecksum);

// pattern.c

int load_patterns(SOURCE *source);
double erp_lookup(SOURCE *source, double azm);
double contour_erp_lookup(SOURCE *source, double azm);
double vpat_lookup(SOURCE *source, double hgt, double azm, double dist, double relev, double rhgt, int mode,
	double *depAngle, double *lupAngle);
RECEIVE_ANT *get_receive_antenna(int antennaKey);
double recv_az_lookup(SOURCE *source, MPAT *rpat, double rot, double ang, double freq);

// fcc_curve.c

int fcc_curve(double *power, double *field, double *dist, double height, int iband, int mode1, int mode2, int mode3,
	SOURCE *source, double azm, double *dep, double *lup, double *vpt);

// map.c

int find_country(double lat, double lon);
int get_border_distances(double lat, double lon, double *borderDist);
MAPFILE *open_mapfile(int fileFormat, char *baseName, int shapeType, int nAttr, SHAPEATTR *attrs, char *infoName,
	int folderAttrIndex);
MAPFILE *open_sum_mapfile(int fileFormat, char *baseName, int shapeType, int nAttr, SHAPEATTR *attrs, char *infoName,
	int folderAttrIndex);
int write_shape(MAPFILE *map, double ptLat, double ptLon, GEOPOINTS *pts, int nParts, int *iParts, char **attrData);
void close_mapfile(MAPFILE *map);
GEOPOINTS *render_service_area(SOURCE *source);
GEOPOINTS *render_contour(CONTOUR *contour);
GEOGRAPHY *get_geography(int geoKey);
void relocate_geography(SOURCE *source);
GEOPOINTS *render_geography(GEOGRAPHY *geo);
int inside_geography(double plat, double plon, GEOGRAPHY *geo);
GEOGRAPHY *geography_alloc(int geoKey, int type, double lat, double lon, int count);
void geography_free(GEOGRAPHY *geo);
GEOPOINTS *geopoints_alloc(int np);
void geopoints_addpoint(GEOPOINTS *pts, double lat, double lon);
void geopoints_free(GEOPOINTS *pts);
int inside_poly(double plat, double plon, GEOPOINTS *pts);

// log.c

void set_log_file(char *theFile);
void set_log_start_time(long theTime);
void log_open();
void log_close();
char *log_open_time();
char *current_time();
void log_message(const char *fmt, ...);
void log_error(const char *fmt, ...);
void log_db_error(const char *fmt, ...);
void set_status_enabled(int enable);
void status_message(char *key, char *mesg);
void hb_log();
void hb_log_begin(int count);
void hb_log_tick();
void hb_log_end();


//---------------------------------------------------------------------------------------------------------------------
// Global debug flag and run notes, see tvstudy.c.

extern int Debug;
extern char *RunComment;


//---------------------------------------------------------------------------------------------------------------------
// Global parameters, see parameter.c.

extern PARAMS Params;


//---------------------------------------------------------------------------------------------------------------------
// Study globals, see study.c.

extern int OutputFlags[MAX_OUTPUT_FLAGS];
extern int OutputFlagsSet;
extern int MapOutputFlags[MAX_MAP_OUTPUT_FLAGS];
extern int MapOutputFlagsSet;

extern MYSQL *MyConnection;

extern char DbName[MAX_STRING];
extern char DatabaseID[MAX_STRING];
extern char HostDbName[MAX_STRING];

extern int UseDbIDOutPath;

extern int StudyKey;
extern int StudyType;
extern int StudyMode;
extern int TemplateKey;
extern int StudyLock;
extern int StudyModCount;
extern char StudyName[MAX_STRING];
extern char ExtDbName[MAX_STRING];
extern char PointSetName[MAX_STRING];
extern int PropagationModel;
extern int StudyAreaMode;
extern int StudyAreaGeoKey;
extern int StudyAreaModCount;

extern UND_TOTAL WirelessUndesiredTotals[MAX_COUNTRY];

extern int DoComposite;
extern DES_TOTAL CompositeTotals[MAX_COUNTRY];


//---------------------------------------------------------------------------------------------------------------------
// Globals for source data, see source.c.

extern SOURCE *Sources;
extern int SourceCount;
extern SOURCE **SourceKeyIndex;
extern int SourceIndexSize;
extern int SourceIndexMaxSize;

extern int ScenarioKey;
extern char ScenarioName[MAX_STRING];


//---------------------------------------------------------------------------------------------------------------------
// Globals for cell grid, see cell.c.

extern INDEX_BOUNDS CellBounds;
extern INDEX_BOUNDS GridIndex;
extern int GridCount;
extern TVPOINT **Cells;
extern int GridMaxCount;
extern int CellLatSize;
extern int GridLatCount;
extern int CellLonSize;
extern int GridLonCount;
extern int *CellLonSizes;
extern double *CellAreas;
extern int *GridLonCounts;
extern int *CellEastLonIndex;
extern int GridMaxLatCount;

extern TVPOINT *Points;
extern POINT_INFO *PointInfos;


//---------------------------------------------------------------------------------------------------------------------
// Reporting, see report.c.

extern char *CountryName[MAX_COUNTRY];
