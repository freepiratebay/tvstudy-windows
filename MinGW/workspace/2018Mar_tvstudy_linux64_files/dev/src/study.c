//
//  study.c
//  TVStudy
//
//  Copyright (c) 2012-2018 Hammett & Edison, Inc.  All rights reserved.


// Program to perform TV coverage and interference studies for DTV and NTSC TV stations based on the procedures
// outlined in the final Report and Order on Docket 87-268 and described in OET Bulletin #69 and existing FCC Rules.


#include "tvstudy.h"

#include <ctype.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/errno.h>
#include <sys/wait.h>


//---------------------------------------------------------------------------------------------------------------------

#define WAIT_TIME_SECONDS  10   // Time to wait for study lock to become available, see discussion below.
#define MAX_WAIT_COUNT     18

#define CALC_CACHE_COUNT  1000000   // Max number of field calculations between cache updates.

static void close_at_exit();
static void clear_state();
static int do_run_ix_study(int probeMode);
static void ix_source_descrip(SOURCE *source, SOURCE *dsource, FILE *repFile, int doLabel);
static char *ix_source_label(SOURCE *source);
static int check_haat(SOURCE *target, FILE *repFile);
static int check_base_coverage(SOURCE *target, SOURCE *targetBase, FILE *repFile);
static int check_dts_coverage(SOURCE *target, FILE *repFile);
static int check_border_distance(SOURCE *target, FILE *repFile);
static int check_monitor_station(SOURCE *target, FILE *repFile);
static int check_va_quiet_zone(SOURCE *target, FILE *repFile);
static int check_table_mountain(SOURCE *target, FILE *repFile);
static int check_land_mobile(SOURCE *target, FILE *repFile);
static int check_offshore_radio(SOURCE *target, FILE *repFile);
static int do_run_scenario(int scenarioKey);
static int do_open_mapfiles();
static void do_start_image();
static void do_end_image(int err);
static int do_global_run();
static int do_write_compcov();
static int do_local_run();
static int do_points_run();
static void write_points(MAPFILE *mapPointsShp, MAPFILE *mapPointsKML, FILE *pointsFile);
static void clear_pending_files(int commit);
static int project_field(TVPOINT *point, FIELD *field);
static int analyze_source(SOURCE *source);
static int analyze_grid(INDEX_BOUNDS gridIndex);
static int analyze_points(TVPOINT *points, SOURCE *dsource);
static double dtv_codu_adjust(int, double);

// Public globals.

int OutputFlags[MAX_OUTPUT_FLAGS];
int OutputFlagsSet = 0;
int MapOutputFlags[MAX_MAP_OUTPUT_FLAGS];
int MapOutputFlagsSet = 0;

MYSQL *MyConnection = NULL;   // Database connection.

char DbName[MAX_STRING];       // Root database name, also prefix for study and external database names.
char DatabaseID[MAX_STRING];   // Database UUID.
char HostDbName[MAX_STRING];   // Host and root database names combined, for reports and file names.

int UseDbIDOutPath = 0;   // Set true to use database ID instead of hostname in output file paths.

int StudyKey = 0;                         // Study key, type, template key, lock, name, station database name.
int StudyType = STUDY_TYPE_UNKNOWN;
int StudyMode = STUDY_MODE_GRID;
int TemplateKey = 0;
int StudyLock = LOCK_NONE;
int StudyModCount = 0;
char StudyName[MAX_STRING];
char ExtDbName[MAX_STRING];
char PointSetName[MAX_STRING];
int PropagationModel = 1;
int StudyAreaMode = STUDY_AREA_SERVICE;
int StudyAreaGeoKey = 0;
int StudyAreaModCount = 0;

UND_TOTAL WirelessUndesiredTotals[MAX_COUNTRY];   // Used to tally net wireless interference in OET-74 study.

int DoComposite = 0;                      // Tally net coverage for scenario composite output, global grid mode only.
DES_TOTAL CompositeTotals[MAX_COUNTRY];

// Path to output directory, see set_out_path().

static char *OutPath = NULL;

// Private globals, see run_scenario().

static int LockCount = 0;
static int KeepLock = 0;
static int KeepExclLock = 0;
static int RunNumber = 0;
static int CacheUndesired = 1;

static char *ReportPreamble = NULL;     // Text items composed by the front-end app used in report outputs.
static char *ParameterSummary = NULL;
static char *IxRuleSummary = NULL;

static char OutputCodes[MAX_STRING];
static char MapOutputCodes[MAX_STRING];

static FILE *OutputFile[N_OUTPUT_FILES];               // Output files.
static MAPFILE *MapOutputFile[N_MAP_OUTPUT_FILES];
static int ReportSumHeader = 0;
static int CSVSumHeader = 0;

static int IXMarginSourceKey = 0;   // Source key for interference margin outputs, see analyze_points().
static double *IXMarginAz = NULL;   // Arrays to hold data for the IX margin CSV output, see analyze_points().
static double *IXMarginDb = NULL;
static int *IXMarginPop = NULL;
static int IXMarginCount = 0;
static int IXMarginMaxCount = -1;

static SCENARIO_PAIR *ScenarioPairList = NULL;   // List of scenario pairs, see open_study().

static float *Profile = NULL;    // The terrain profile retrieved by a project_field() call, if any.  If the call did
static int ProfileCount = 0;     //  not extract a profile for any reason, ProfileCount is set to 0.  In that case
static double ProfilePpk = 0.;   //  Profile may or may not be NULL.

// Color information for map image output, see do_start_image().

static double MapImageBackgroundR = 0.;
static double MapImageBackgroundG = 0.;
static double MapImageBackgroundB = 0.;
static int MapImageColorCount = 0;
static int MapImageMaxColorCount = 0;
static double *MapImageLevels = NULL;
static double *MapImageColorR = NULL;
static double *MapImageColorG = NULL;
static double *MapImageColorB = NULL;

// State for the pending-files feature, see open_file() and clear_pending_files().  Used by run_ix_study().

typedef struct pfl {
	char *fileName;
	char *filePath;
	struct pfl *next;
} PENDING_FILE;

static int UsePendingFiles = 0;
static PENDING_FILE *PendingFiles = NULL;
static int PendingFilesScenarioKey = 0;

// Points map used for points and/or composite output accumulation, see do_global_run().

static INDEX_BOUNDS PointMapBounds;
static int PointMapCount = 0;
static unsigned char *PointMap = NULL;
static int PointMapLatCount = 0;
static int PointMapLonSize = 0;
static int PointMapLonCount = 0;
static int *PointMapLonSizes = NULL;
static int *PointMapEastLonIndex = NULL;

static int PointMapBitShift[MAX_COUNTRY] = {0, 2, 4};

#define POINTMAP_HASPOP_BIT 0x40

#define POINTMAP_OUTPUT_BIT 0x80

#define RESULT_COVERAGE   1
#define RESULT_INTERFERE  2
#define RESULT_NOSERVICE  3
#define RESULT_UNKNOWN    0


//---------------------------------------------------------------------------------------------------------------------
// Open a connection to a study database, query the study record, check and configure the persistent lock flag as
// needed.  If open and locking are successful, load parameters, load and pre-process sources, and get everything set
// up to load and run scenarios.  If anything goes seriously wrong the return is <0, process should probably exit.  If
// the study does not exist, or the lock checks fail, or any of a variety of minor errors occur during setup, return
// is >0; in that case the run can continue and this can be called again with different arguments.  For any non-zero
// return the connection is not opened and study data not loaded, or incompletely loaded.

// This will not return success unless and until a valid lock state exists in the study database.  Under normal
// conditions, the existing state of the lock is checked and the response for each possible state is described below.

// LOCK_NONE
// Set LOCK_RUN_EXCL, giving this process exclusive use of the database and the ability to write updates back during
// the pre-processing done in load_sources().  Once load_sources() is done the lock will be demoted to LOCK_RUN_SHARE.

// LOCK_EDIT
// Immediately return a non-fatal failure.

// LOCK_ADMIN, LOCK_RUN_EXCL
// Enter a loop waiting for the lock to become LOCK_NONE or LOCK_RUN_SHARE, then proceed as described for those cases.
// The wait loop has a timeout, return a non-fatal failure if that expires.

// LOCK_RUN_SHARE
// Share the existing lock.  Continue with pre-processing and return success, however load_sources() will not attempt
// to make any updates.  The assumption is there won't be any since some other engine process must previously have had
// LOCK_RUN_EXCL and made any updates needed.  But even if there are records still needing update that does not
// invalidate this run.  The new values computed, used, and cached here are assumed to be identical to corresponding
// values computed by any other process.

// However if inheritLockCount is >0, the behavior is different.  In that case, attempt to inherit an existing lock
// state set by another application.  The lock must be set to LOCK_RUN_EXCL or LOCK_RUN_SHARE and the lock_count must
// match the argument else immediate (non-fatal) failure occurs.  When the lock is inherited, it will not be cleared
// on exit.  However it may still be downgraded to a shared lock, unless the keepExclLock flag is true in which case
// the lock will not be changed at all.

// Arguments:

//   host              Database host name, NULL to use the MySQL API default which is usually localhost.
//   name              Root database name, or NULL to use default.
//   user              Database user name, NULL to use the MySQL API default which is usually the OS login user name.
//   pass              Database password.  Must not be NULL or empty.
//   studyKey          Primary key of the study record in the root database study table, if 0 find study by name.
//   studyName         Study name to find, or NULL.
//   inheritLockCount  >0 to inherit existing run lock, lock_count must match this value.
//   keepExclLock      When inheritLockCount >0, if this is true the lock will not be changed to shared.
//   runNumber         An arbitrary run identification number, if >0 may be used for output file name uniqueness.
//   cacheUndesired    True if undesired cell caches are updated, false if not.  Performance optimization; in some
//                       situations the cache files become so large that I/O time approaches re-computation time.

// Return <0 for a serious error, >0 for a minor error, 0 for no error.

int open_study(char *host, char *name, char *user, char *pass, int studyKey, char *studyName, int inheritLockCount,
		int keepExclLock, int runNumber, int cacheUndesired) {

	if (MyConnection) {
		log_error("open_study() called with study already open");
		return 1;
	}

	// On the first open, install an exit hook to call close_study().

	static int init = 1;
	if (init) {
		atexit(close_at_exit);
		init = 0;
	}

	// Paranoia, make sure all state is clear.  Set database name.

	clear_state();

	if (name) {
		lcpystr(DbName, name, MAX_STRING);
	} else {
		lcpystr(DbName, DEFAULT_DB_NAME, MAX_STRING);
	}

	char query[MAX_QUERY];
	MYSQL_RES *myResult;
	my_ulonglong rowCount;
	MYSQL_ROW fields;

	// Outer loop in case this needs to wait for a lock change.  An inability to open the initial connection is not a
	// fatal error, the most likely explanation is the user provided incorrect login credentials.

	int err, shareCount, needsUpdate, len, pointSetKey, lockOK, newLock, wait, waitCount = MAX_WAIT_COUNT,
		waitMessage = 1;

	do {

		MyConnection = mysql_init(NULL);

		if (!mysql_real_connect(MyConnection, host, user, pass, DbName, 0, NULL, 0)) {
			log_db_error("Database connection failed");
			mysql_close(MyConnection);
			MyConnection = NULL;
			return 1;
		}

		// The study table is locked until the persistent lock flag (a field in the table) can be checked and set.

		snprintf(query, MAX_QUERY, "LOCK TABLES %s.study WRITE, %s.version WRITE, %s.ext_db WRITE, %s.geography AS point_geo WRITE, %s.geography AS area_geo WRITE;", DbName, DbName, DbName, DbName, DbName);
		if (mysql_query(MyConnection, query)) {
			log_db_error("Could not lock study table (1)");
			mysql_close(MyConnection);
			MyConnection = NULL;
			return -1;
		}

		err = -1;
		wait = 0;

		if (studyKey) {
			snprintf(query, MAX_QUERY, "SELECT study.name, version.version, study.study_lock, study.lock_count, study.share_count, study.study_type, study.study_mode, study.template_key, study.needs_update, study.mod_count, version.uuid, ext_db.db_type, ext_db.name, ext_db.id, study.point_set_key, point_geo.name, study.propagation_model, study.study_area_mode, study.study_area_geo_key, area_geo.mod_count, study.output_config_file_codes, study.output_config_map_codes, study.report_preamble, study.parameter_summary, study.ix_rule_summary FROM %s.study JOIN %s.version LEFT JOIN %s.ext_db USING (ext_db_key) LEFT JOIN %s.geography AS point_geo ON (point_geo.geo_key = study.point_set_key) LEFT JOIN %s.geography AS area_geo ON (area_geo.geo_key = study.study_area_geo_key) WHERE study.study_key = %d;", DbName, DbName, DbName, DbName, DbName, studyKey);
		} else {
			char nameBuf[512];
			unsigned long ulen = strlen(studyName);
			if (ulen > 255) {
				ulen = 255;
			}
			mysql_real_escape_string(MyConnection, nameBuf, studyName, ulen);
			snprintf(query, MAX_QUERY, "SELECT study.study_key, version.version, study.study_lock, study.lock_count, study.share_count, study.study_type, study.study_mode, study.template_key, study.needs_update, study.mod_count, version.uuid, ext_db.db_type, ext_db.name, ext_db.id, study.point_set_key, point_geo.name, study.propagation_model, study.study_area_mode, study.study_area_geo_key, area_geo.mod_count, study.output_config_file_codes, study.output_config_map_codes, study.report_preamble, study.parameter_summary, study.ix_rule_summary FROM %s.study JOIN %s.version LEFT JOIN %s.ext_db USING (ext_db_key) LEFT JOIN %s.geography AS point_geo ON (point_geo.geo_key = study.point_set_key) LEFT JOIN %s.geography AS area_geo ON (area_geo.geo_key = study.study_area_geo_key) WHERE study.name = '%s';", DbName, DbName, DbName, DbName, DbName, nameBuf);
		}

		if (mysql_query(MyConnection, query)) {
			log_db_error("Study query failed (1)");

		} else {
			myResult = mysql_store_result(MyConnection);
			if (!myResult) {
				log_db_error("Study query failed (2)");

			} else {
				rowCount = mysql_num_rows(myResult);
				if (!rowCount) {
					if (studyKey) {
						log_error("Study not found for studyKey=%d", studyKey);
					} else {
						log_error("Study '%s' not found", studyName);
					}
					err = 1;

				} else {
					fields = mysql_fetch_row(myResult);
					if (!fields) {
						log_db_error("Study query failed (3)");

					} else {

						if (studyKey) {
							lcpystr(StudyName, fields[0], MAX_STRING);
						} else {
							studyKey = atoi(fields[0]);
							lcpystr(StudyName, studyName, MAX_STRING);
						}

						if (TVSTUDY_DATABASE_VERSION != atoi(fields[1])) {
							log_error("Incorrect database version");
							err = 1;

						} else {

							StudyLock = atoi(fields[2]);
							LockCount = atoi(fields[3]);
							shareCount = atoi(fields[4]);
							StudyType = atoi(fields[5]);
							StudyMode = atoi(fields[6]);
							TemplateKey = atoi(fields[7]);
							needsUpdate = atoi(fields[8]);
							StudyModCount = atoi(fields[9]);

							lcpystr(DatabaseID, fields[10], MAX_STRING);

							if (fields[11]) {
								switch (atoi(fields[11])) {
									case DB_TYPE_CDBS: {
										lcpystr(ExtDbName, "CDBS TV ", MAX_STRING);
										break;
									}
									case DB_TYPE_LMS: {
										lcpystr(ExtDbName, "LMS TV ", MAX_STRING);
										break;
									}
									case DB_TYPE_CDBS_FM: {
										lcpystr(ExtDbName, "CDBS FM ", MAX_STRING);
										break;
									}
								}
								len = strlen(fields[12]);
								if (len > 0) {
									lcatstr(ExtDbName, fields[12], MAX_STRING);
								} else {
									lcatstr(ExtDbName, fields[13], MAX_STRING);
								}
							}

							pointSetKey = atoi(fields[14]);
							if (fields[15]) {
								len = strlen(fields[15]);
								if (len > 0) {
									lcpystr(PointSetName, fields[15], MAX_STRING);
								}
							}

							PropagationModel = atoi(fields[16]);

							StudyAreaMode = atoi(fields[17]);
							StudyAreaGeoKey = atoi(fields[18]);
							if (fields[19]) {
								StudyAreaModCount = atoi(fields[19]);
							}

							lcpystr(OutputCodes, fields[20], MAX_STRING);

							lcpystr(MapOutputCodes, fields[21], MAX_STRING);

							len = strlen(fields[22]);
							if (len > 0) {
								len++;
								ReportPreamble = (char *)mem_alloc(len);
								lcpystr(ReportPreamble, fields[22], len);
							}

							len = strlen(fields[23]);
							if (len > 0) {
								len++;
								ParameterSummary = (char *)mem_alloc(len);
								lcpystr(ParameterSummary, fields[23], len);
							}

							len = strlen(fields[24]);
							if (len > 0) {
								len++;
								IxRuleSummary = (char *)mem_alloc(len);
								lcpystr(IxRuleSummary, fields[24], len);
							}

							lockOK = 0;

							if (inheritLockCount > 0) {

								if (((StudyLock != LOCK_RUN_EXCL) && (StudyLock != LOCK_RUN_SHARE)) ||
										(LockCount != inheritLockCount)) {
									log_error("Could not inherit study lock, the lock was modified");

								} else {
									KeepLock = 1;
									KeepExclLock = keepExclLock;
									lockOK = 1;
								}

							} else {

								newLock = LOCK_NONE;
								switch (StudyLock) {
									case LOCK_NONE:
										newLock = LOCK_RUN_EXCL;
										LockCount++;
										shareCount = 0;
										break;
									case LOCK_EDIT:
										log_error("Study '%s' is in use by another application", StudyName);
										err = 1;
										break;
									case LOCK_ADMIN:
									case LOCK_RUN_EXCL:
										if (waitCount-- > 0) {
											if (waitMessage) {
												log_message("Waiting for study '%s' to be unlocked...", StudyName);
												waitMessage = 0;
											}
											wait = 1;
											err = 0;
										} else {
											log_error("Study '%s' is in use by another application", StudyName);
											err = 1;
										}
										break;
									case LOCK_RUN_SHARE:
										newLock = LOCK_RUN_SHARE;
										shareCount++;
										break;
								}

								if (newLock != LOCK_NONE) {
									snprintf(query, MAX_QUERY, "UPDATE %s.study SET study_lock = %d, lock_count = %d, share_count = %d WHERE study_key = %d;", DbName, newLock, LockCount, shareCount, studyKey);
									if (mysql_query(MyConnection, query)) {
										log_db_error("Study lock update query failed (1)");
									} else {
										StudyLock = newLock;
										lockOK = 1;
									}
								}
							}

							// If the default database name was used don't include it in the identifying name.

							if (lockOK) {
								if (host) {
									lcpystr(HostDbName, host, MAX_STRING);
								} else {
									lcpystr(HostDbName, "localhost", MAX_STRING);
								}
								if (strcmp(DbName, DEFAULT_DB_NAME)) {
									lcatstr(HostDbName, "-", MAX_STRING);
									lcatstr(HostDbName, DbName, MAX_STRING);
								}
								StudyKey = studyKey;
								RunNumber = runNumber;
								CacheUndesired = cacheUndesired;
								err = 0;
							}
						}
					}
				}
			}

			mysql_free_result(myResult);
		}

		if (mysql_query(MyConnection, "UNLOCK TABLES;")) {
			err = -1;
			log_db_error("Could not unlock study table (1)");
		}

		if (err) {
			if (LOCK_NONE != StudyLock) {
				close_study(1);
			} else {
				mysql_close(MyConnection);
				MyConnection = NULL;
				clear_state();
			}
			return err;
		}

		if (wait) {
			mysql_close(MyConnection);
			MyConnection = NULL;
			clear_state();
			sleep(WAIT_TIME_SECONDS);
		}

	} while (wait);

	// TV interference-check and TV6 vs. FM studies must use grid mode and indivudal service area mode, do not allow
	// anything else.

	if ((STUDY_TYPE_TV_IX == StudyType) || (STUDY_TYPE_TV6_FM == StudyType)) {
		StudyMode = STUDY_MODE_GRID;
		StudyAreaMode = STUDY_AREA_SERVICE;
	}

	// Study opened and lock acquired.  Do the study setup, first load parameters.

	log_message("Study open for studyKey=%d  %s", StudyKey, StudyName);

	err = load_study_parameters();
	if (err) {
		close_study(1);
		return err;
	}

	// Inform the terrain routines what types of terrain are being requested, mainly this sets UserTerrainRequested
	// if appropriate.  But someday this might also do some cache optimization.

	add_requested_terrain(Params.TerrAvgDb);
	add_requested_terrain(Params.TerrPathDb);

	// Check the cache.

	err = cache_setup(needsUpdate);
	if (err) {
		close_study(1);
		return err;
	}

	// Make sure mode state is consistent.  In points mode load points.

	if (STUDY_MODE_GRID == StudyMode) {

		if (STUDY_AREA_GEOGRAPHY != StudyAreaMode) {
			StudyAreaGeoKey = 0;
			StudyAreaModCount = 0;
		}

		PointSetName[0] = '\0';

	} else {

		err = load_points(pointSetKey);
		if (err) {
			close_study(1);
			return err;
		}

		StudyAreaMode = STUDY_AREA_SERVICE;
		StudyAreaGeoKey = 0;
		StudyAreaModCount = 0;
	}

	// Load all source records, this will also do all initial processing such as replication.

	err = load_sources(needsUpdate);
	if (err) {
		close_study(1);
		return err;
	}

	// Load the scenario pair list.  Each pairing links two scenarios, and specific desired sources in each, for
	// comparison analysis in some special study types e.g. IX check.  The source pointers are resolved as these are
	// loaded, if any are invalid the pairing is silently ignored.  For an IX check study, the query uses a special
	// ordering based on the desired source in the "after" case.

	if (STUDY_TYPE_TV_IX == StudyType) {
		snprintf(query, MAX_QUERY, "SELECT scenario_pair.scenario_key_a, scenario_pair.source_key_a, scenario_pair.scenario_key_b, scenario_pair.source_key_b, scenario_pair.name FROM %s_%d.scenario_pair JOIN %s_%d.source ON (source.source_key = scenario_pair.source_key_b) ORDER BY (CASE WHEN scenario_pair.name LIKE 'MX%%' THEN 1 ELSE 0 END), source.country_key, source.channel, source.state, source.city, scenario_pair.scenario_key_b", DbName, StudyKey, DbName, StudyKey);
	} else {
		snprintf(query, MAX_QUERY, "SELECT scenario_key_a, source_key_a, scenario_key_b, source_key_b, name FROM %s_%d.scenario_pair ORDER BY 3", DbName, StudyKey);
	}
	if (mysql_query(MyConnection, query)) {
		log_db_error("Scenario pair query failed (1)");
		return -1;
	}

	myResult = mysql_store_result(MyConnection);
	if (!myResult) {
		log_db_error("Scenario pair query failed (2)");
		return -1;
	}

	rowCount = mysql_num_rows(myResult);
	my_ulonglong rowIndex;
	SCENARIO_PAIR *thePair, **pairLink = &ScenarioPairList;
	int scenKeyA, srcKey, scenKeyB;
	SOURCE *srcA, *srcB;

	for (rowIndex = 0; rowIndex < rowCount; rowIndex++) {

		fields = mysql_fetch_row(myResult);
		if (!fields) {
			mysql_free_result(myResult);
			log_db_error("Scenario pair query failed (3)");
			return -1;
		}

		scenKeyA = atoi(fields[0]);
		srcKey = atoi(fields[1]);
		if (scenKeyA < 1) {
			continue;
		}
		if ((srcKey < 1) || (srcKey >= SourceIndexSize)) {
			continue;
		}
		srcA = SourceKeyIndex[srcKey];
		if (!srcA) {
			continue;
		}

		scenKeyB = atoi(fields[2]);
		srcKey = atoi(fields[3]);
		if (scenKeyB < 1) {
			continue;
		}
		if ((srcKey < 1) || (srcKey >= SourceIndexSize)) {
			continue;
		}
		srcB = SourceKeyIndex[srcKey];
		if (!srcB) {
			continue;
		}

		thePair = mem_zalloc(sizeof(SCENARIO_PAIR));
		*pairLink = thePair;
		pairLink = &(thePair->next);

		thePair->scenarioKeyA = scenKeyA;
		thePair->sourceA = srcA;

		thePair->scenarioKeyB = scenKeyB;
		thePair->sourceB = srcB;

		lcpystr(thePair->name, fields[4], MAX_STRING);
	}

	mysql_free_result(myResult);

	// If the study lock is currently exclusive change it to a shared lock, unless KeepExclLock is set.  In any case
	// the study needs_update flag is cleared.

	if (LOCK_RUN_EXCL == StudyLock) {

		snprintf(query, MAX_QUERY, "LOCK TABLES %s.study WRITE;", DbName);
		if (mysql_query(MyConnection, query)) {
			log_db_error("Could not lock study table (2)");
			err = -1;

		} else {

			if (check_study_lock()) {

				if (KeepExclLock) {

					snprintf(query, MAX_QUERY, "UPDATE %s.study SET needs_update=0 WHERE study_key=%d;", DbName,
						StudyKey);
					if (mysql_query(MyConnection, query)) {
						log_db_error("Study record update query failed (2)");
						err = -1;
					}

				} else {

					int lockCount = LockCount + 1;
					snprintf(query, MAX_QUERY,
				"UPDATE %s.study SET needs_update=0, study_lock=%d, lock_count=%d, share_count=1 WHERE study_key=%d;",
						DbName, LOCK_RUN_SHARE, lockCount, StudyKey);
					if (mysql_query(MyConnection, query)) {
						log_db_error("Study lock update query failed (2)");
						err = -1;
					} else {
						StudyLock = LOCK_RUN_SHARE;
						LockCount = lockCount;
					}
				}

			} else {
				err = -1;
			}

			if (mysql_query(MyConnection, "UNLOCK TABLES;")) {
				log_db_error("Could not unlock study table (2)");
				err = -1;
			}
		}

		if (err) {
			close_study(1);
			return err;
		}
	}

	// Ready to run scenarios.

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Exit hook to make sure study is closed.  If it is still open this is an error exit.

static void close_at_exit() {

	close_study(1);
}


//---------------------------------------------------------------------------------------------------------------------
// Check the status of the persistent study lock.  Return is non-zero if the lock has not changed since it was set or
// checked in open_study().  A change means another process violated the locking protocol and data integrity cannot be
// guaranteed, the run should abort.

// Arguments:

//   (none)

// Return is non-zero on success, the current share_count if the lock type is LOCK_RUN_SHARE else -1; return is 0 if
// the lock does not match or an error occurs.

int check_study_lock() {

	if (LOCK_NONE == StudyLock) {
		return 0;
	}

	char query[MAX_QUERY];
	MYSQL_RES *myResult;
	my_ulonglong rowCount;
	MYSQL_ROW fields;

	int result = 0;

	snprintf(query, MAX_QUERY, "SELECT study_lock, lock_count, share_count FROM %s.study WHERE study_key = %d;",
		DbName, StudyKey);
	if (mysql_query(MyConnection, query)) {
		log_db_error("Lock query failed (1)");

	} else {
		myResult = mysql_store_result(MyConnection);
		if (!myResult) {
			log_db_error("Lock query failed (2)");

		} else {
			rowCount = mysql_num_rows(myResult);
			if (rowCount) {
				fields = mysql_fetch_row(myResult);
				if (!fields) {
					log_db_error("Lock query failed (3)");

				} else {
					if ((atoi(fields[0]) == StudyLock) && (atoi(fields[1]) == LockCount)) {
						if (LOCK_RUN_SHARE == StudyLock) {
							result = atoi(fields[2]);
							if (0 == result) {
								result = -1;
							}
						} else {
							result = -1;
						}

					} else {
						log_error("Lock check failed, lock does not match.");
					}
				}

			} else {
				log_error("Lock check failed, study no longer exists.");
			}

			mysql_free_result(myResult);
		}
	}

	return result;
}


//---------------------------------------------------------------------------------------------------------------------
// Release the persistent study lock and close the database connection.  If database is not open, do nothing.  Check
// the lock state first, if it is still valid as initially acquired in open_study() clear it, otherwise do nothing.
// If the lock is LOCK_RUN_SHARE the share_count is decremented, the lock is only cleared if that is now 0.  However
// if the KeepLock flag is true the lock will not be changed; but it is still checked to verify no change since open.

// Arguments:

//   hadError  True if the close follows an error condition, don't do final analysis/reporting actions.

void close_study(int hadError) {

	if (!MyConnection) {
		return;
	}

	// If no error, do final reporting of scenario pairings.  These are only written to the summary files so one of
	// those must be open.  Also write the settings file if needed.

	if (!hadError) {

		if (ScenarioPairList && (OutputFile[REPORT_FILE_SUMMARY] || OutputFile[CSV_FILE_SUMMARY])) {

			FILE *repFile = OutputFile[REPORT_FILE_SUMMARY], *csvFile = OutputFile[CSV_FILE_SUMMARY];

			SCENARIO_PAIR *thePair = ScenarioPairList;
			int doHeader = 1;

			while (thePair) {
				if (thePair->didStudyA && thePair->didStudyB) {
					if (repFile) {
						write_pair_report(thePair, repFile, doHeader);
					}
					if (csvFile) {
						write_pair_csv(thePair, csvFile, doHeader);
					}
					doHeader = 0;
				}
				thePair = thePair->next;
			}
		}

		if (OutputFlags[SETTING_FILE]) {

			FILE *outFile = open_sum_file(SETTING_FILE_NAME);
			if (outFile) {

				write_report_preamble(REPORT_FILE_SUMMARY, outFile, 1, 0);

				if (ParameterSummary) {
					fprintf(outFile, "Study parameter settings:\n%s\n\n", ParameterSummary);
				}

				if (IxRuleSummary) {
					fprintf(outFile, "Active interference rules:\n\n%s\n\n", IxRuleSummary);
				}

				fclose(outFile);
			}
		}
	}

	// Close summary files.

	if (OutputFile[REPORT_FILE_SUMMARY]) {
		fclose(OutputFile[REPORT_FILE_SUMMARY]);
		OutputFile[REPORT_FILE_SUMMARY] = NULL;
	}
	if (OutputFile[CSV_FILE_SUMMARY]) {
		fclose(OutputFile[CSV_FILE_SUMMARY]);
		OutputFile[CSV_FILE_SUMMARY] = NULL;
	}
	if (OutputFile[CELL_FILE_SUMMARY]) {
		fclose(OutputFile[CELL_FILE_SUMMARY]);
		OutputFile[CELL_FILE_SUMMARY] = NULL;
	}
	if (OutputFile[CELL_FILE_PAIRSTUDY]) {
		fclose(OutputFile[CELL_FILE_PAIRSTUDY]);
		OutputFile[CELL_FILE_PAIRSTUDY] = NULL;
	}

	// Clear the pending files list in case it contains anything, always commit the files.

	clear_pending_files(1);

	// Clear the requested-terrain state, see terrain.c.

	clear_requested_terrain();

	// Unlock study.

	char query[MAX_QUERY];

	snprintf(query, MAX_QUERY, "LOCK TABLES %s.study WRITE;", DbName);
	if (mysql_query(MyConnection, query)) {
		log_db_error("Could not lock study table (3)");

	} else {

		int shareCount = check_study_lock();

		if (shareCount && !KeepLock) {

			int lockCount = LockCount;
			int newLock = LOCK_NONE;

			if ((LOCK_RUN_SHARE == StudyLock) && (--shareCount > 0)) {
				newLock = LOCK_RUN_SHARE;
			} else {
				lockCount++;
				shareCount = 0;
			}

			snprintf(query, MAX_QUERY,
				"UPDATE %s.study SET study_lock = %d, lock_count = %d, share_count = %d WHERE study_key = %d;",
				DbName, newLock, lockCount, shareCount, StudyKey);
			if (mysql_query(MyConnection, query)) {
				log_db_error("Study lock update query failed (3)");
			}
		}

		if (mysql_query(MyConnection, "UNLOCK TABLES;")) {
			log_db_error("Could not unlock study table (3)");
		}
	}

	// Close connection and clear state.

	mysql_close(MyConnection);
	MyConnection = NULL;

	clear_state();

	// These are not cleared in clear_state() because that may be called repeatedly while waiting for the lock state
	// to change in open_study() and so must not clear input state that was set before open_study() was called.

	OutputFlagsSet = 0;
	MapOutputFlagsSet = 0;

	log_message("Study closed");
}


//---------------------------------------------------------------------------------------------------------------------
// Clear global state for study.

static void clear_state() {

	DatabaseID[0] = '\0';
	HostDbName[0] = '\0';

	StudyKey = 0;
	StudyType = STUDY_TYPE_UNKNOWN;
	StudyMode = STUDY_MODE_GRID;
	TemplateKey = 0;
	StudyLock = LOCK_NONE;
	StudyModCount = 0;
	StudyName[0] = '\0';
	ExtDbName[0] = '\0';
	PointSetName[0] = '\0';
	PropagationModel = 1;
	StudyAreaMode = STUDY_AREA_SERVICE;
	StudyAreaGeoKey = 0;
	StudyAreaModCount = 0;

	LockCount = 0;
	KeepLock = 0;
	KeepExclLock = 0;
	RunNumber = 0;
	CacheUndesired = 1;

	if (ReportPreamble) {
		mem_free(ReportPreamble);
		ReportPreamble = NULL;
	}
	if (ParameterSummary) {
		mem_free(ParameterSummary);
		ParameterSummary = NULL;
	}
	if (IxRuleSummary) {
		mem_free(IxRuleSummary);
		IxRuleSummary = NULL;
	}

	OutputCodes[0] = '\0';
	MapOutputCodes[0] = '\0';

	ReportSumHeader = 0;
	CSVSumHeader = 0;

	SCENARIO_PAIR *nextPair, *thePair = ScenarioPairList;
	while (thePair) {
		nextPair = thePair->next;
		thePair->next = NULL;
		mem_free(thePair);
		thePair = nextPair;
	}
	ScenarioPairList = NULL;

	ScenarioKey = 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Find a scenario by name in the open study.

// Arguments:

//   scenarioName  Name of the scenario.

// Return is the scenario key if found, 0 if not found, <0 on error.

int find_scenario_name(char *scenarioName) {

	if (!StudyKey) {
		log_error("find_scenario_name() called with no study open");
		return -1;
	}

	char query[MAX_QUERY];
	MYSQL_RES *myResult;
	my_ulonglong rowCount;
	MYSQL_ROW fields;

	char nameBuf[512];
	unsigned long ulen = strlen(scenarioName);
	if (ulen > 255) {
		ulen = 255;
	}
	mysql_real_escape_string(MyConnection, nameBuf, scenarioName, ulen);
	snprintf(query, MAX_QUERY, "SELECT scenario_key FROM %s_%d.scenario WHERE name = '%s';", DbName, StudyKey,
		nameBuf);

	if (mysql_query(MyConnection, query)) {
		log_db_error("Scenario name query failed (1)");
		return -1;
	}

	myResult = mysql_store_result(MyConnection);
	if (!myResult) {
		log_db_error("Scenario name query failed (2)");
		return -1;
	}

	rowCount = mysql_num_rows(myResult);
	if (!rowCount) {
		mysql_free_result(myResult);
		log_error("Scenario '%s' not found", scenarioName);
		return 0;
	}

	fields = mysql_fetch_row(myResult);
	if (!fields) {
		mysql_free_result(myResult);
		log_db_error("Scenario name query failed (3)");
		return -1;
	}

	int scenarioKey = atoi(fields[0]);
	mysql_free_result(myResult);

	return scenarioKey;
}


//---------------------------------------------------------------------------------------------------------------------
// Parse a flags argument string into globals.  This does not fail, bad flags are just ignored.

// Arguments:

//   flags     Output flags, a string of letter codes selecting desired options or file-related behaviors.  Flag codes
//               may be followed by a modifier digit customizing the behavior.  In general anything not recognized
//               is simply ignored, including invalid modifiers.
//   outFlags  Flags translated to array of integers, character value of flag code is index into the array, value is
//                0 if code did not appear, >0 if it did, 1 if no modifier digit else the value of the digit.
//   maxFlag   Size of outflags array, maximum flag index.

void parse_flags(char *flags, int *outFlags, int maxFlag) {

	int i, flag, lastFlag = -1, len = strlen(flags);

	for (i = 0; i < maxFlag; i++) {
		outFlags[i] = 0;
	}

	for (i = 0; i < len; i++) {
		flag = toupper(flags[i]) - 'A';
		if (flag < 0) {
			flag = flags[i] - '0';
			if ((flag >= 0) && (flag < 10) && (lastFlag >= 0)) {
				outFlags[lastFlag] = flag;
			}
			lastFlag = -1;
		} else {
			lastFlag = -1;
			if (flag < maxFlag) {
				outFlags[flag] = 1;
				lastFlag = flag;
			}
		}
	}
}


//---------------------------------------------------------------------------------------------------------------------
// Run an interference-check study, this is a different processing sequence than normal studies and it writes a
// separate report output file.  It traverses the scenario pair list and runs the pairs in sequence.  Normal output
// files may also be written but those are not required.

// Arguments:

//   probeMode  True to do an interference "probe" run, see details below.

// Return is <0 for major error, >0 for minor error, 0 for no error.

static FILE *ixReportFile = NULL;
static UNDESIRED *uBefore = NULL;

int run_ix_study(int probeMode) {

	if (!StudyKey || (STUDY_TYPE_TV_IX != StudyType)) {
		log_error("run_ix_study() called with no study open, or wrong study type");
		return 1;
	}

	int err = do_run_ix_study(probeMode);

	if (uBefore) {
		mem_free(uBefore);
		uBefore = NULL;
	}

	if (ixReportFile) {
		fclose(ixReportFile);
		ixReportFile = NULL;
	}

	return err;
}

static int do_run_ix_study(int probeMode) {

	int err = 0;

	// In probe mode, all default type scenarios are run.  No output files are created; for each desired-undesired pair
	// a message is logged providing desired source key, undesired source key, and true/false if interference is caused
	// in isolation.  The front-end app uses this to determine which undesireds are needed in the final study, to avoid
	// generating extra scenarios with no interference potential.

	if (probeMode) {

		char query[MAX_QUERY];
		MYSQL_RES *myResult;
		my_ulonglong rowCount;
		MYSQL_ROW fields;

		int *scenarios = NULL, scenarioCount = 0, i;
		char mesg[MAX_STRING];

		snprintf(query, MAX_QUERY, "SELECT scenario_key FROM %s_%d.scenario WHERE scenario_type = 1 ORDER BY 1;",
			DbName, StudyKey);
		if (mysql_query(MyConnection, query)) {
			log_db_error("Scenario list query failed (1)");
			err = -1;

		} else {
			myResult = mysql_store_result(MyConnection);
			if (!myResult) {
				log_db_error("Scenario list query failed (2)");
				err = -1;

			} else {
				rowCount = mysql_num_rows(myResult);

				if (rowCount) {

					scenarioCount = (int)rowCount;
					scenarios = (int *)mem_alloc(scenarioCount * sizeof(int));
					for (i = 0; i < scenarioCount; i++) {

						fields = mysql_fetch_row(myResult);
						if (!fields) {
							log_db_error("Scenario list query failed (3)");
							err = -1;
							break;
						}

						scenarios[i] = atoi(fields[0]);
					}

				} else {
					log_error("No probe scenarios found");
					err = 1;
				}

				mysql_free_result(myResult);
			}
		}

		if (err) {
			status_message(STATUS_KEY_ERROR, "Interference probe study engine run failed");
			if (scenarios) {
				mem_free(scenarios);
			}
			return err;
		}

		SOURCE *source, *usource;
		UNDESIRED *undesireds;
		int sourceIndex, countryIndex, undesiredIndex, causesIX;

		for (i = 0; i < scenarioCount; i++) {

			err = run_scenario(scenarios[i]);
			if (err) {
				status_message(STATUS_KEY_ERROR, "Interference probe study engine run failed");
				mem_free(scenarios);
				return err;
			}

			source = Sources;
			for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {
				if (source->inScenario && source->isDesired) {
					countryIndex = source->countryKey - 1;
					undesireds = source->undesireds;
					for (undesiredIndex = 0; undesiredIndex < source->undesiredCount; undesiredIndex++) {
						usource = SourceKeyIndex[undesireds[undesiredIndex].sourceKey];
						if (!usource) {
							log_error("Source structure index is corrupted");
							exit(1);
						}
						causesIX = (undesireds[undesiredIndex].totals[countryIndex].ixArea > 0.);
						snprintf(mesg, MAX_STRING, "%d,%d,%d", source->sourceKey, usource->sourceKey, causesIX);
						status_message(STATUS_KEY_RESULT, mesg);
					}
				}
			}
		}

		mem_free(scenarios);
		return 0;
	}

	// Identify the study target, and perform auxiliary checks.  The target source, and possibly a baseline record for
	// the target, are expected to be in the first scenario in the study.  That scenario is also the "before" for
	// scenario pairs that analyze interference to the proposal.  Load and run that scenario, then identify the target
	// and possibly baseline.  Both target and baseline must be desireds, no other records should exist.  The target is
	// identified by an attribute.

	err = run_scenario(1);
	if (err) return err;

	SOURCE *source = Sources, *target = NULL, *targetBase = NULL;
	int sourceIndex;
	for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {
		if (source->inScenario) {
			if (source->isDesired) {
				if (get_source_attribute(source, ATTR_IS_PROPOSAL)) {
					if (target) {
						target = NULL;
						break;
					}
					target = source;
				} else {
					if (targetBase) {
						target = NULL;
						break;
					}
					targetBase = source;
				}
			} else {
				target = NULL;
				break;
			}
		}
	}

	if (!target || (RECORD_TYPE_TV != target->recordType)) {
		log_error("Could not identify proposal record in IX check study.");
		return 1;
	}

	// Verify the target and any baseline are using FCC curves contours to define service areas.  All of the check_*()
	// functions assume those contours already exist on the sources.

	SOURCE *sources;

	if (target->isParent) {
		sources = target->dtsSources;
	} else {
		sources = target;
		target->next = NULL;
	}
	for (source = sources; source; source = source->next) {
		if (!source->contour || (SERVAREA_CONTOUR_FCC != source->contour->mode)) {
			log_error("Proposal records must use FCC curves service contours for IX check study.");
			return 1;
		}
	}

	if (targetBase) {
		if (targetBase->isParent) {
			sources = targetBase->dtsSources;
		} else {
			sources = targetBase;
			targetBase->next = NULL;
		}
		for (source = sources; source; source = source->next) {
			if (!source->contour || (SERVAREA_CONTOUR_FCC != source->contour->mode)) {
				log_error("Proposal records must use FCC curves service contours for IX check study.");
				return 1;
			}
		}
	}

	// Open the report file, write the report preamble, add any preamble text from the study build.

	ixReportFile = open_sum_file(TV_IX_FILE_NAME);
	if (!ixReportFile) return 1;

	write_report_preamble(REPORT_FILE_SUMMARY, ixReportFile, 0, 0);
	if (ReportPreamble) {
		fputs(ReportPreamble, ixReportFile);
	}

	// Run the various special checks.

	err = check_haat(target, ixReportFile);
	if (err) return err;

	err = check_base_coverage(target, targetBase, ixReportFile);
	if (err) return err;

	err = check_dts_coverage(target, ixReportFile);
	if (err) return err;

	err = check_border_distance(target, ixReportFile);
	if (err) return err;

	err = check_monitor_station(target, ixReportFile);
	if (err) return err;

	err = check_va_quiet_zone(target, ixReportFile);
	if (err) return err;

	err = check_table_mountain(target, ixReportFile);
	if (err) return err;

	err = check_land_mobile(target, ixReportFile);
	if (err) return err;

	err = check_offshore_radio(target, ixReportFile);
	if (err) return err;

	// Report cell size and profile resolution and the interference limit percentages.

	char mesg[MAX_STRING];

	snprintf(mesg, MAX_STRING, "Study cell size: %.2f km", Params.CellSize);
	status_message(STATUS_KEY_REPORT, "");
	status_message(STATUS_KEY_REPORT, mesg);
	fprintf(ixReportFile, "\n%s\n", mesg);

	snprintf(mesg, MAX_STRING, "Profile point spacing: %.2f km", (1. / Params.TerrPathPpk[CNTRY_USA - 1]));
	status_message(STATUS_KEY_REPORT, mesg);
	status_message(STATUS_KEY_REPORT, "");
	fprintf(ixReportFile, "%s\n\n", mesg);

	snprintf(mesg, MAX_STRING, "Maximum new IX to full-service and Class A: %.2f%%", Params.IxCheckLimitPercent);
	status_message(STATUS_KEY_REPORT, mesg);
	fprintf(ixReportFile, "%s\n", mesg);

	snprintf(mesg, MAX_STRING, "Maximum new IX to LPTV: %.2f%%", Params.IxCheckLimitPercentLPTV);
	status_message(STATUS_KEY_REPORT, mesg);
	status_message(STATUS_KEY_REPORT, "");
	fprintf(ixReportFile, "%s\n\n", mesg);

	// Run the study, loop over scenario pairs, run before and after scenarios as needed, analyze and report.

	static char *countryLabel[MAX_COUNTRY + 1] = {"", "  (in U.S.)", "  (in Canada)", "  (in Mexico)"};

	SOURCE *usource;
	UNDESIRED *undesireds;
	int i, isIXPair, hasAPP, iAPP, countryKey, countryIndex, ubCount = 0, ubIndex, undesiredCount, undesiredIndex,
		showLbl, cLblIndex, showHdr, skip, fails, failed = 0, reportWorst = 0, desSourceKey = 0, firstMX = 1;
	double failLimit, pcta, pctp, worstCase;
	DES_TOTAL *dtotBefore, *dtotAfter;
	UND_TOTAL *utotBefore, *utotAfter, *ttotBefore, *ttotAfter;
	FILE *outFile;
	char *targID, *scenID, *desID, *undID, worstMesg[MAX_STRING];

	if (target->fileNumber[0]) {
		targID = target->fileNumber;
	} else {
		if (target->callSign[0]) {
			targID = target->callSign;
		} else {
			targID = "UNKNOWN";
		}
	}

	SCENARIO_PAIR *thePair = ScenarioPairList;

	while (thePair) {

		source = thePair->sourceB;
		isIXPair = (source != target);

		// By convention the pair name contains a # followed by a numerical identifier for the pairing, extract that
		// for use in reporting.  If # is not found use the entire pair name as the ID.

		scenID = thePair->name;
		for (i = strlen(scenID) - 2; i > 0; i--) {
			if ('#' == scenID[i]) {
				scenID += i + 1;
				break;
			}
		}

		// The desired source is identified by file number if any, else call sign if any, else "UNKNOWN".

		if (source->fileNumber[0]) {
			desID = source->fileNumber;
		} else {
			if (source->callSign[0]) {
				desID = source->callSign;
			} else {
				desID = "UNKNOWN";
			}
		}

		// Set up to report the worst-case interference for this desired if there are no actual failures.

		if (source->sourceKey != desSourceKey) {
			if (reportWorst) {
				status_message(STATUS_KEY_REPORT, worstMesg);
			}
			desSourceKey = source->sourceKey;
			reportWorst = OutputFlags[IXCHK_REPORT_WORST];
			if (reportWorst) {
				snprintf(worstMesg, MAX_STRING, "Proposal causes no interference to %s %s", desID, source->status);
			}
			worstCase = 0.;
		}

		// The pending-files feature may be used to delete files for scenario pairs that pass, if the output flag is
		// set to only output for failed scenarios.  Due to the code design in analyze_points() it is not possible to
		// defer file output until after the pass/fail test result is known.  So the files are always created during
		// the analysis, then once pass/fail is determined the files will be deleted or committed as appropriate.
		// This is cleared after the pair analysis so has to be re-set for every new scenario pair.

		UsePendingFiles = OutputFlags[IXCHK_DEL_PASS];

		// The "before" scenario may be shared between pairs for the cases that examine interference received by the
		// target station.  Results from shared scenarios are updated in all pair structures when the scenario is run
		// so it does not need to be re-run, see run_scenario().  In the coverage cases the "before" does not provide
		// any undesired information as it contains only the desired station.  In other pairs the scenarios should not
		// be shared so both are run regardless to get the undesired lists.  The "before" undesired list is copied
		// since it will be cleared by running the "after".  Note this assumes the two desired sources in the pair are
		// the same however it doesn't fail if they aren't, although the report may be incomplete in that case.

		if (isIXPair || !thePair->didStudyA) {

			err = run_scenario(thePair->scenarioKeyA);
			if (err) return err;

			if (isIXPair) {
				ubCount = thePair->sourceA->undesiredCount;
				if (ubCount) {
					uBefore = (UNDESIRED *)mem_alloc(ubCount * sizeof(UNDESIRED));
					memcpy(uBefore, thePair->sourceA->undesireds, (ubCount * sizeof(UNDESIRED)));
				}
			}
		}

		// During the after scenario run, activate the IX margin feature in analyze_points().  That will put extra
		// attributes in the coverage map file (if that is being generated) for any points that receive unique IX from
		// the target, those are the bearing from the target and the amount by which the D/U fails (the margin).  That
		// information is also accumulated for possible CSV file output after the scenario run.  The CSV output may
		// accumulate in 1-degree azimuth windows, keeping the worst margin (smallest number as failed points always
		// have negative margin) along with the total population in all unique IX points.  Or the output may list all
		// individual points.  In either case, zero-population points may or may not be included.

		if (isIXPair) {
			IXMarginSourceKey = target->sourceKey;
			if (OutputFlags[IXCHK_MARG_CSV]) {
				if ((IXCHK_MARG_CSV_AGG == OutputFlags[IXCHK_MARG_CSV]) ||
						(IXCHK_MARG_CSV_AGGNO0 == OutputFlags[IXCHK_MARG_CSV])) {
					IXMarginCount = 360;
				} else {
					IXMarginCount = 0;
				}
				if (IXMarginCount > IXMarginMaxCount) {
					IXMarginMaxCount = IXMarginCount + 1000;
					IXMarginAz = (double *)mem_realloc(IXMarginAz, (IXMarginMaxCount * sizeof(double)));
					IXMarginDb = (double *)mem_realloc(IXMarginDb, (IXMarginMaxCount * sizeof(double)));
					IXMarginPop = (int *)mem_realloc(IXMarginPop, (IXMarginMaxCount * sizeof(int)));
				}
				for (i = 0; i < IXMarginCount; i++) {
					IXMarginAz[i] = (double)i;
					IXMarginDb[i] = 0.;
					IXMarginPop[i] = 0;
				}
			}
		}

		err = run_scenario(thePair->scenarioKeyB);
		if (err) return err;

		if ((IXMarginSourceKey > 0) && OutputFlags[IXCHK_MARG_CSV]) {
			outFile = open_file(MARG_CSV_FILE_NAME);
			if (!outFile) return 1;
			for (i = 0; i < IXMarginCount; i++) {
				fprintf(outFile, "%.2f,%.3f,%d\n", IXMarginAz[i], IXMarginDb[i], IXMarginPop[i]);
			}
			fclose(outFile);
			IXMarginSourceKey = 0;
		}

		undesireds = source->undesireds;
		undesiredCount = source->undesiredCount;

		dtotBefore = thePair->totalsA;
		dtotAfter = thePair->totalsB;

		// For an IX pairing, locate the target totals for "before" and "after".  If there is a "before" undesireds
		// list search it for the target's baseline record to get the totals for that source as undesired.  In any case
		// these loops also validate the source index for both lists so that does not have to be done again later.

		ttotBefore = NULL;
		ttotAfter = NULL;

		for (ubIndex = 0; ubIndex < ubCount; ubIndex++) {
			usource = SourceKeyIndex[uBefore[ubIndex].sourceKey];
			if (!usource) {
				log_error("Source structure index is corrupted");
				exit(1);
			}
			if (isIXPair && (usource == targetBase)) {
				ttotBefore = uBefore[ubIndex].totals;
			}
		}

		for (undesiredIndex = 0; undesiredIndex < undesiredCount; undesiredIndex++) {
			usource = SourceKeyIndex[undesireds[undesiredIndex].sourceKey];
			if (!usource) {
				log_error("Source structure index is corrupted");
				exit(1);
			}
			if (isIXPair && (usource == target)) {
				ttotAfter = undesireds[undesiredIndex].totals;
			}
		}

		// Start the report.  In an IX pairing if the target causes no interference either "before" or "after" there is
		// nothing more to report.  Otherwise check the new interference percentage against limits, report any failures
		// to the front-end app by inline message as well.  An interference failure is based only on coverage in the
		// desired station's country, interference in other countries is reported but never considered a failure.  A
		// coverage pair (interference to the target) is always reported.

		fputs(
		"\n--------------------------------------------------------------------------------------------------------\n",
			ixReportFile);

		if (isIXPair) {

			fprintf(ixReportFile, "Interference to %s %s scenario %s\n", desID, source->status, scenID);
			skip = 1;
			for (countryIndex = 0; countryIndex < MAX_COUNTRY; countryIndex++) {
				if (ttotBefore && (ttotBefore[countryIndex].ixArea > 0.)) {
					skip = 0;
					break;
				}
				if (ttotAfter && (ttotAfter[countryIndex].ixArea > 0.)) {
					skip = 0;
					break;
				}
			}

		} else {

			fprintf(ixReportFile, "Interference to proposal scenario %s\n", scenID);
			skip = 0;
		}

		fails = 0;

		if (skip) {

			fprintf(ixReportFile, "Proposal causes no interference.\n");

		} else {

			countryIndex = source->countryKey - 1;
			if (SERV_TVLP == source->service) {
				failLimit = Params.IxCheckLimitPercentLPTV;
			} else {
				failLimit = Params.IxCheckLimitPercent;
			}
			fails = (thePair->popPercent[countryIndex] > failLimit);

			// Report failures.  For the IX pairs if the desired is an APP it is reported as an MX failure, otherwise
			// an IX failure.  For the MX check pairs, it is reported as an MX failure only if the scenario includes
			// one or more APP records actually causing interference.  Otherwise it is reported but not as a failure.

			if (fails) {
				if (isIXPair) {
					failed = 1;
					if (0 == strcasecmp(source->status, "APP")) {
						fprintf(ixReportFile, "**MX: %.2f%% interference caused\n", thePair->popPercent[countryIndex]);
						snprintf(mesg, MAX_STRING, "**MX with %s %s scenario %s, %.2f%% interference caused", desID,
							source->status, scenID, thePair->popPercent[countryIndex]);
					} else {
						fprintf(ixReportFile, "**IX: %.2f%% interference caused\n", thePair->popPercent[countryIndex]);
						snprintf(mesg, MAX_STRING,
							"**IX check failure to %s %s scenario %s, %.2f%% interference caused",
							desID, source->status, scenID, thePair->popPercent[countryIndex]);
					}
				} else {
					if (firstMX) {
						snprintf(mesg, MAX_STRING, "---- Below is IX received by proposal %s ----", targID);
						status_message(STATUS_KEY_REPORT, "");
						status_message(STATUS_KEY_REPORT, mesg);
						status_message(STATUS_KEY_REPORT, "");
						firstMX = 0;
					}
					hasAPP = 0;
					iAPP = -1;
					for (undesiredIndex = 0; undesiredIndex < undesiredCount; undesiredIndex++) {
						usource = SourceKeyIndex[undesireds[undesiredIndex].sourceKey];
						if (usource != target) {
							if ((0 == strcasecmp(usource->status, "APP")) &&
									(undesireds[undesiredIndex].totals[countryIndex].ixPop > 0)) {
								if (hasAPP) {
									iAPP = -1;
								} else {
									hasAPP = 1;
									iAPP = undesiredIndex;
								}
							}
						}
					}
					if (hasAPP) {
						failed = 1;
						fprintf(ixReportFile, "**MX: %.2f%% interference received\n",
							thePair->popPercent[countryIndex]);
						if (iAPP >= 0) {
							usource = SourceKeyIndex[undesireds[iAPP].sourceKey];
							if (usource->fileNumber[0]) {
								undID = usource->fileNumber;
							} else {
								if (usource->callSign[0]) {
									undID = usource->callSign;
								} else {
									undID = "UNKNOWN";
								}
							}
							snprintf(mesg, MAX_STRING, "**MX with %s %s scenario %s, %.2f%% interference received",
								undID, usource->status, scenID, thePair->popPercent[countryIndex]);
						} else {
							snprintf(mesg, MAX_STRING, "**MX with scenario %s, %.2f%% interference received",
								scenID, thePair->popPercent[countryIndex]);
						}
					} else {
						fprintf(ixReportFile, "%.2f%% interference received\n", thePair->popPercent[countryIndex]);
						snprintf(mesg, MAX_STRING, "Proposal receives %.2f%% interference from scenario %s",
							thePair->popPercent[countryIndex], scenID);
					}
				}
				status_message(STATUS_KEY_REPORT, mesg);

				reportWorst = 0;

			} else {

				// If the worst case is being reported update the message as needed, to be reported later.

				if (reportWorst && (thePair->popPercent[countryIndex] > worstCase)) {
					worstCase = thePair->popPercent[countryIndex];
					if (isIXPair) {
						snprintf(worstMesg, MAX_STRING, "Proposal causes %.2f%% interference to %s %s scenario %s",
							thePair->popPercent[countryIndex], desID, source->status, scenID);
					} else {
						if (firstMX) {
							snprintf(mesg, MAX_STRING, "---- Below is IX received by proposal %s ----", targID);
							status_message(STATUS_KEY_REPORT, "");
							status_message(STATUS_KEY_REPORT, mesg);
							status_message(STATUS_KEY_REPORT, "");
							firstMX = 0;
						}
						snprintf(worstMesg, MAX_STRING, "Proposal receives %.2f%% interference from scenario %s",
							thePair->popPercent[countryIndex], scenID);
					}
				}
			}

			// List all stations involved in the scenario.  In an IX pairing list the target first, including it's
			// "before" if that exists.

			fputs(
			"\n             Call      Chan  Svc Status  City, State               File Number             Distance\n",
				ixReportFile);

			ix_source_descrip(source, NULL, ixReportFile, 1);
			fputc('\n', ixReportFile);

			showLbl = 1;

			if (isIXPair) {
				if (targetBase) {
					ix_source_descrip(targetBase, source, ixReportFile, showLbl);
					showLbl = 0;
				}
				ix_source_descrip(target, source, ixReportFile, showLbl);
				showLbl = 0;
			}

			for (undesiredIndex = 0; undesiredIndex < undesiredCount; undesiredIndex++) {
				usource = SourceKeyIndex[undesireds[undesiredIndex].sourceKey];
				if (usource != target) {
					ix_source_descrip(usource, source, ixReportFile, showLbl);
					showLbl = 0;
				}
			}

			// Report the coverage and total interference for the desired.  For an interference pairing, this shows
			// interference-free coverage in both "before" and "after", and the percentage change.  A desired line is
			// always shown for the desired station's country even if the contour total is zero, other countries are
			// reported only if there were some study points in the contour.

			if (isIXPair) {

				fputs(
		"\n        Service area       Terrain-limited       IX-free, before        IX-free, after    Percent New IX\n",
					ixReportFile);

				for (countryIndex = 0; countryIndex < MAX_COUNTRY; countryIndex++) {

					countryKey = countryIndex + 1;
					if ((countryKey != source->countryKey) && (0. == dtotBefore[countryIndex].contourArea) &&
							(0. == dtotAfter[countryIndex].contourArea)) {
						continue;
					}
					if (countryKey == source->countryKey) {
						cLblIndex = 0;
					} else {
						cLblIndex = countryKey;
					}

					fprintf(ixReportFile, "%8.1f %11s", dtotAfter[countryIndex].contourArea,
						pop_commas(dtotAfter[countryIndex].contourPop));
					fprintf(ixReportFile, "  %8.1f %11s", dtotAfter[countryIndex].serviceArea,
						pop_commas(dtotAfter[countryIndex].servicePop));
					fprintf(ixReportFile, "  %8.1f %11s", dtotBefore[countryIndex].ixFreeArea,
						pop_commas(dtotBefore[countryIndex].ixFreePop));
					fprintf(ixReportFile, "  %8.1f %11s  %7.2f  %7.2f%s\n", dtotAfter[countryIndex].ixFreeArea,
						pop_commas(dtotAfter[countryIndex].ixFreePop), thePair->areaPercent[countryIndex],
						thePair->popPercent[countryIndex], countryLabel[cLblIndex]);
				}

				// Report the total and unique interference from undesireds, with the unique reported for both "before"
				// and "after" cases.  Report the target first, it's "before" and "after" totals were located earlier.
				// Don't show lines where the total IX is zero; there will be at least one line for the target in some
				// country, see above.  If the target has a "before" record report that on a separate line showing only
				// "before" totals, then only "after" totals on the target's line.  Separate total IX columns are not
				// not used because for all the other undesireds those would always be identical.

				showHdr = 1;

				for (countryIndex = 0; countryIndex < MAX_COUNTRY; countryIndex++) {

					countryKey = countryIndex + 1;
					if ((!ttotBefore || (0. == ttotBefore[countryIndex].ixArea)) &&
							(!ttotAfter || (0. == ttotAfter[countryIndex].ixArea))) {
						continue;
					}
					if (countryKey == source->countryKey) {
						cLblIndex = 0;
					} else {
						cLblIndex = countryKey;
					}

					if (showHdr) {
						fputs(
						"\nUndesired                         Total IX     Unique IX, before      Unique IX, after\n",
							ixReportFile);
						showHdr = 0;
					}

					if (ttotBefore) {
						fprintf(ixReportFile, "%-20s  %8.1f %11s", ix_source_label(targetBase),
							ttotBefore[countryIndex].ixArea, pop_commas(ttotBefore[countryIndex].ixPop));
						fprintf(ixReportFile, "  %8.1f %11s", ttotBefore[countryIndex].uniqueIxArea,
							pop_commas(ttotBefore[countryIndex].uniqueIxPop));
						fprintf(ixReportFile, "                      %s\n", countryLabel[cLblIndex]);
					}

					if (ttotAfter) {
						fprintf(ixReportFile, "%-20s  %8.1f %11s", ix_source_label(target),
							ttotAfter[countryIndex].ixArea, pop_commas(ttotAfter[countryIndex].ixPop));
						fprintf(ixReportFile, "                        %8.1f %11s%s\n",
							ttotAfter[countryIndex].uniqueIxArea, pop_commas(ttotAfter[countryIndex].uniqueIxPop),
							countryLabel[cLblIndex]);
					}
				}

				// Now report all of the other undesireds.  First locate the "before" totals for the undesired source,
				// the same source should exist in the "before" list but this will not fail if it does not.  Again do
				// not report any lines that have zero total interference.

				for (undesiredIndex = 0; undesiredIndex < undesiredCount; undesiredIndex++) {

					usource = SourceKeyIndex[undesireds[undesiredIndex].sourceKey];
					if (usource == target) {
						continue;
					}

					utotBefore = NULL;
					for (ubIndex = 0; ubIndex < ubCount; ubIndex++) {
						if (uBefore[ubIndex].sourceKey == usource->sourceKey) {
							utotBefore = uBefore[ubIndex].totals;
							break;
						}
					}

					utotAfter = undesireds[undesiredIndex].totals;

					for (countryIndex = 0; countryIndex < MAX_COUNTRY; countryIndex++) {

						countryKey = countryIndex + 1;
						if ((!utotBefore || (0. == utotBefore[countryIndex].ixArea)) &&
								(0. == utotAfter[countryIndex].ixArea)) {
							continue;
						}
						if (countryKey == source->countryKey) {
							cLblIndex = 0;
						} else {
							cLblIndex = countryKey;
						}

						if (showHdr) {
							fputs(
						"\nUndesired                         Total IX     Unique IX, before      Unique IX, after\n",
								ixReportFile);
							showHdr = 0;
						}

						if (utotBefore) {

							fprintf(ixReportFile, "%-20s  %8.1f %11s", ix_source_label(usource),
								utotAfter[countryIndex].ixArea, pop_commas(utotAfter[countryIndex].ixPop));
							fprintf(ixReportFile, "  %8.1f %11s", utotBefore[countryIndex].uniqueIxArea,
								pop_commas(utotBefore[countryIndex].uniqueIxPop));
							fprintf(ixReportFile, "  %8.1f %11s%s\n", utotAfter[countryIndex].uniqueIxArea,
								pop_commas(utotAfter[countryIndex].uniqueIxPop), countryLabel[cLblIndex]);

						} else {

							fprintf(ixReportFile, "%-20s  %8.1f %11s", ix_source_label(usource),
								utotAfter[countryIndex].ixArea, pop_commas(utotAfter[countryIndex].ixPop));
							fprintf(ixReportFile, "                        %8.1f %11s%s\n",
								utotAfter[countryIndex].uniqueIxArea, pop_commas(utotAfter[countryIndex].uniqueIxPop),
								countryLabel[cLblIndex]);
						}
					}
				}

			// Report for a coverage case, this is much simpler as there is no "before".  That scenario exists just as
			// a placeholder for the pairing, it contains no undesireds at all and is there just so the generic pairing
			// logic correctly calculates total interference percentages, see do_scenario().

			} else {

				fputs("\n        Service area       Terrain-limited               IX-free        Percent IX\n",
					ixReportFile);

				for (countryIndex = 0; countryIndex < MAX_COUNTRY; countryIndex++) {

					countryKey = countryIndex + 1;
					if ((countryKey != source->countryKey) && (0. == dtotAfter[countryIndex].contourArea)) {
						continue;
					}
					if (countryKey == source->countryKey) {
						cLblIndex = 0;
					} else {
						cLblIndex = countryKey;
					}

					fprintf(ixReportFile, "%8.1f %11s", dtotAfter[countryIndex].contourArea,
						pop_commas(dtotAfter[countryIndex].contourPop));
					fprintf(ixReportFile, "  %8.1f %11s", dtotAfter[countryIndex].serviceArea,
						pop_commas(dtotAfter[countryIndex].servicePop));
					fprintf(ixReportFile, "  %8.1f %11s  %7.2f  %7.2f%s\n", dtotAfter[countryIndex].ixFreeArea,
						pop_commas(dtotAfter[countryIndex].ixFreePop), thePair->areaPercent[countryIndex],
						thePair->popPercent[countryIndex], countryLabel[cLblIndex]);
				}

				showHdr = 1;

				for (undesiredIndex = 0; undesiredIndex < undesiredCount; undesiredIndex++) {

					usource = SourceKeyIndex[undesireds[undesiredIndex].sourceKey];
					utotAfter = undesireds[undesiredIndex].totals;

					for (countryIndex = 0; countryIndex < MAX_COUNTRY; countryIndex++) {

						countryKey = countryIndex + 1;
						if (0. == utotAfter[countryIndex].ixArea) {
							continue;
						}
						if (countryKey == source->countryKey) {
							cLblIndex = 0;
						} else {
							cLblIndex = countryKey;
						}

						if (showHdr) {
							fputs(
							"\nUndesired                         Total IX             Unique IX   Prcnt Unique IX\n",
								ixReportFile);
							showHdr = 0;
						}

						fprintf(ixReportFile, "%-20s  %8.1f %11s", ix_source_label(usource),
							utotAfter[countryIndex].ixArea, pop_commas(utotAfter[countryIndex].ixPop));
						fprintf(ixReportFile, "  %8.1f %11s", utotAfter[countryIndex].uniqueIxArea,
							pop_commas(utotAfter[countryIndex].uniqueIxPop));
						pcta = (undesireds[undesiredIndex].totals[countryIndex].uniqueIxArea /
							dtotBefore[countryIndex].ixFreeArea) * 100.;
						if (dtotBefore[countryIndex].ixFreePop > 0) {
							pctp = ((double)undesireds[undesiredIndex].totals[countryIndex].uniqueIxPop /
								(double)dtotBefore[countryIndex].ixFreePop) * 100.;
						} else {
							pctp = 0.;
						}
						fprintf(ixReportFile, "  %7.2f  %7.2f%s\n", pcta, pctp, countryLabel[cLblIndex]);
					}
				}
			}
		}

		// Commit or delete files (if pending files not active this does nothing).

		if (!fails && OutputFlags[IXCHK_DEL_PASS] &&
				(isIXPair || (IXCHK_DEL_PASS_ALL == OutputFlags[IXCHK_DEL_PASS]))) {
			clear_pending_files(0);
		} else {
			clear_pending_files(1);
		}

		// Next pair.

		if (uBefore) {
			mem_free(uBefore);
			uBefore = NULL;
			ubCount = 0;
		}

		thePair = thePair->next;
	}

	if (reportWorst) {
		status_message(STATUS_KEY_REPORT, worstMesg);
	}

	if (!failed) {
		status_message(STATUS_KEY_REPORT, "No IX check failures found.");
	}

	// All runs complete.

	status_message(STATUS_KEY_RUNCOUNT, "0");

	return 0;
}

// Descriptive text for a source in an IX check study report table.  If dsource is NULL source is a desired, otherwise
// it is an undesired relative to the desired in dsource, in that case this will include a distance column.  If
// showLabel is true prefix and suffix labels are shown.

static void ix_source_descrip(SOURCE *source, SOURCE *dsource, FILE *repFile, int showLabel) {

	if (showLabel) {
		if (dsource) {
			fputs("Undesireds:  ", repFile);
		} else {
			fputs("Desired:     ", repFile);
		}
	} else {
		fputs("             ", repFile);
	}

	char cityst[MAX_STRING];
	snprintf(cityst, MAX_STRING, "%s, %s", source->city, source->state);

	fprintf(repFile, "%-10s%-6s%-4s%-8s%-26s%-24s", source->callSign, channel_label(source), source->serviceCode,
		source->status, cityst, source->fileNumber);

	if (dsource) {

		double dist = 0.;
		bear_distance(dsource->latitude, dsource->longitude, source->latitude, source->longitude, NULL, NULL, &dist,
			Params.KilometersPerDegree);

		fprintf(repFile, "%5.1f", dist);

		if (showLabel) {
			fputs(" km", repFile);
		}
	}

	fputc('\n', repFile);
}

// Label text for a source in an IX check study report.

static char *ix_source_label(SOURCE *source) {

	static char str[MAX_STRING];

	snprintf(str, MAX_STRING, "%s %s %s %s", source->callSign, channel_label(source), source->serviceCode,
		source->status);

	return str;
}


//---------------------------------------------------------------------------------------------------------------------
// First of several target-record checks, these are auxiliary that perform various tests and immediately report the
// result, they do not affect the rest of the study run.  The report file argument may be NULL in which case reporting
// is only to the front-end app by inline message.  Some tests assume the target record has just been studied as a
// desired station and so has service contours and desired coverage totals.

// Check a target source for overall HAAT matching computed value, check overall HAAT vs. ERP limits, also print an
// HAAT, ERP, and contour distance table.  For DTS, each individual transmitter source is checked and tabulated.  The
// target contours must exist.

static int check_haat(SOURCE *target, FILE *repFile) {

	// Lookup tables for HAAT vs. ERP check, from 73.622(f).

#define NUM_LOW_VHF 11
	static double lowVhfHAAT[NUM_LOW_VHF] =
		{305., 335., 365., 395., 425., 460., 490., 520., 550., 580., 610.};
	static double lowVhfERP[NUM_LOW_VHF] = 
		{ 45.,  37.,  31.,  26.,  22.,  19.,  16.,  14.,  12.,  11.,  10.};

#define NUM_HIGH_VHF 11
	static double highVhfHAAT[NUM_HIGH_VHF] =
		{305., 335., 365., 395., 425., 460., 490., 520., 550., 580., 610.};
	static double highVhfERP[NUM_HIGH_VHF] =
		{160., 132., 110.,  92.,  76.,  64.,  54.,  47.,  40.,  34.,  30.};

#define NUM_UHF 9
	static double uhfHAAT[NUM_UHF] =
		{ 365., 395., 425., 460., 490., 520., 550., 580., 610.};
	static double uhfERP[NUM_UHF] =
		{1000., 900., 750., 630., 540., 460., 400., 350., 316.};

	SOURCE *sources, *source;
	double *haat, erp, azm, dist, overallHAAT, maxerp;
	int i, i0, i1;
	char mesg[MAX_STRING], *msk;

	int countryIndex = target->countryKey - 1;

	int radialCount = Params.OverallHAATCount[countryIndex];
	double radialStep = 360. / (double)radialCount;

	if (target->isParent) {
		sources = target->dtsSources;
	} else {
		sources = target;
		target->next = NULL;
	}

	for (source = sources; source; source = source->next) {

		haat = compute_source_haat(source, radialCount);
		if (!haat) return -1;

		if (source->parentSource) {
			snprintf(mesg, MAX_STRING, "Record parameters as studied, DTS site # %d:", source->siteNumber);
		} else {
			lcpystr(mesg, "Record parameters as studied:", MAX_STRING);
		}
		status_message(STATUS_KEY_REPORT, mesg);
		status_message(STATUS_KEY_REPORT, "");
		if (repFile) fprintf(repFile, "%s\n\n", mesg);

		snprintf(mesg, MAX_STRING, "    Channel: %s", channel_label(source));
		status_message(STATUS_KEY_REPORT, mesg);
		if (repFile) fprintf(repFile, "%s\n", mesg);

		msk = NULL;
		switch (source->emissionMaskKey) {
			case LPTV_MASK_SIMPLE:
				msk = "Simple";
				break;
			case LPTV_MASK_STRINGENT:
				msk = "Stringent";
				break;
			case LPTV_MASK_FULL_SERVICE:
				msk = "Full Service";
				break;
		}
		if (msk) {
			snprintf(mesg, MAX_STRING, "       Mask: %s", msk);
			status_message(STATUS_KEY_REPORT, mesg);
			if (repFile) fprintf(repFile, "%s\n", mesg);
		}

		snprintf(mesg, MAX_STRING, "   Latitude: %s (NAD83)", latlon_string(source->latitude, 0));
		status_message(STATUS_KEY_REPORT, mesg);
		if (repFile) fprintf(repFile, "%s\n", mesg);

		snprintf(mesg, MAX_STRING, "  Longitude: %s", latlon_string(source->longitude, 1));
		status_message(STATUS_KEY_REPORT, mesg);
		if (repFile) fprintf(repFile, "%s\n", mesg);

		if ((HEIGHT_DERIVE != source->heightAMSL) && ((source->actualHeightAMSL - source->heightAMSL) > HEIGHT_ROUND)) {
			snprintf(mesg, MAX_STRING, "Height AMSL: %.1f m (Adjusted based on actual ground elevation calculation)",
				source->actualHeightAMSL);
		} else {
			snprintf(mesg, MAX_STRING, "Height AMSL: %.1f m", source->actualHeightAMSL);
		}
		status_message(STATUS_KEY_REPORT, mesg);
		if (repFile) fprintf(repFile, "%s\n", mesg);

		snprintf(mesg, MAX_STRING, "       HAAT: %.1f m", source->actualOverallHAAT);
		status_message(STATUS_KEY_REPORT, mesg);
		if (repFile) fprintf(repFile, "%s\n", mesg);

		snprintf(mesg, MAX_STRING, "   Peak ERP: %s kW", erpkw_string(source->peakERP));
		status_message(STATUS_KEY_REPORT, mesg);
		if (repFile) fprintf(repFile, "%s\n", mesg);

		if (source->hasHpat) {
			if (source->hasMpat) {
				if (strlen(source->antennaID)) {
					snprintf(mesg, MAX_STRING, "    Antenna: %s (ID %s) (matrix)", source->mpatName,
						source->antennaID);
				} else {
					snprintf(mesg, MAX_STRING, "    Antenna: %s (matrix)", source->mpatName);
				}
			} else {
				if (strlen(source->antennaID)) {
					snprintf(mesg, MAX_STRING, "    Antenna: %s (ID %s) %.1f deg", source->hpatName, source->antennaID,
						source->hpatOrientation);
				} else {
					snprintf(mesg, MAX_STRING, "    Antenna: %s %.1f deg", source->hpatName, source->hpatOrientation);
				}
			}
		} else {
			lcpystr(mesg, "    Antenna: Omnidirectional", MAX_STRING);
		}
		status_message(STATUS_KEY_REPORT, mesg);
		if (repFile) fprintf(repFile, "%s\n", mesg);

		if (source->hasVpat) {
			snprintf(mesg, MAX_STRING, "Elev Pattrn: %s", source->vpatName);
		} else {
			if (source->useGeneric) {
				lcpystr(mesg, "Elev Pattrn: Generic", MAX_STRING);
			} else {
				lcpystr(mesg, "Elev Pattrn: None", MAX_STRING);
			}
		}
		status_message(STATUS_KEY_REPORT, mesg);
		if (repFile) fprintf(repFile, "%s\n", mesg);

		if ((source->hasVpat || source->useGeneric) &&
				((source->vpatElectricalTilt != 0.) || (source->vpatMechanicalTilt != 0.))) {
			if (source->vpatElectricalTilt != 0.) {
				if (source->vpatMechanicalTilt != 0.) {
					snprintf(mesg, MAX_STRING, "      Tilts: elec %.2f, mech %.2f @ %.1f deg",
						source->vpatElectricalTilt, source->vpatMechanicalTilt, source->vpatTiltOrientation);
				} else {
					snprintf(mesg, MAX_STRING, "  Elec Tilt: %.2f", source->vpatElectricalTilt);
				}
			} else {
				snprintf(mesg, MAX_STRING, "  Mech Tilt: %.2f @ %.1f deg", source->vpatMechanicalTilt,
					source->vpatTiltOrientation);
			}
			status_message(STATUS_KEY_REPORT, mesg);
			if (repFile) fprintf(repFile, "%s\n", mesg);
		}

		status_message(STATUS_KEY_REPORT, "");
		if (repFile) fputc('\n', repFile);

		snprintf(mesg, MAX_STRING, "%.1f dBu contour:", source->contourLevel);
		status_message(STATUS_KEY_REPORT, mesg);
		if (repFile) fprintf(repFile, "%s\n", mesg);

		lcpystr(mesg, "Azimuth      ERP       HAAT   Distance", MAX_STRING);
		status_message(STATUS_KEY_REPORT, mesg);
		if (repFile) fprintf(repFile, "%s\n", mesg);

		for (i = 0; i < radialCount; i++) {
			azm = (double)i * radialStep;
			erp = contour_erp_lookup(source, azm);
			dist = interp_cont(azm, source->contour);
			if (0 == i) {
				snprintf(mesg, MAX_STRING, "%5.1f deg  %5.5s kW  %6.1f m  %5.1f km", azm, erpkw_string(erp), haat[i],
					dist);
			} else {
				snprintf(mesg, MAX_STRING, "%5.1f      %5.5s     %6.1f    %5.1f", azm, erpkw_string(erp), haat[i],
					dist);
			}
			status_message(STATUS_KEY_REPORT, mesg);
			if (repFile) fprintf(repFile, "%s\n", mesg);
		}

		status_message(STATUS_KEY_REPORT, "");
		if (repFile) fputc('\n', repFile);

		overallHAAT = 0;
		for (i = 0; i < radialCount; i++) {
			overallHAAT += haat[i];
		}
		overallHAAT /= (double)radialCount;

		if (rint(overallHAAT) != rint(source->actualOverallHAAT)) {
			lcpystr(mesg, "Database HAAT does not agree with computed HAAT", MAX_STRING);
			status_message(STATUS_KEY_REPORT, mesg);
			if (repFile) fprintf(repFile, "%s\n", mesg);
			snprintf(mesg, MAX_STRING, "Database HAAT: %d m   Computed HAAT: %d m",
				(int)rint(source->actualOverallHAAT), (int)rint(overallHAAT));
			status_message(STATUS_KEY_REPORT, mesg);
			status_message(STATUS_KEY_REPORT, "");
			if (repFile) fprintf(repFile, "%s\n\n", mesg);
		};

		// For full-service DTV, check ERP vs. HAAT against limits.  For VHF this depends on the zone, so if the zone
		// is not defined on the record print a warning and skip the test.

		if ((SERV_TV == source->service) && source->dtv) {

			if ((ZONE_NONE == source->zoneKey) && (BAND_UHF != source->band)) {

				lcpystr(mesg, "Proposal record zone is undefined, ERP vs. HAAT check not performed", MAX_STRING);
				status_message(STATUS_KEY_REPORT, mesg);
				status_message(STATUS_KEY_REPORT, "");
				if (repFile) fprintf(repFile, "%s\n\n", mesg);

			} else {

				switch (source->band) {

					case BAND_VLO1:
					case BAND_VLO2: {
						if (source->zoneKey == ZONE_I) {
							if (overallHAAT <= 305.) {
								maxerp = 10.;
							} else {
								maxerp = 92.57 - 33.24 * log10(overallHAAT);
							}
						} else {
							if (overallHAAT <= 305.) {
								maxerp = 16.53;
							} else {
								if (overallHAAT <= 610.) {
									for (i1 = 1; i1 < (NUM_LOW_VHF - 1); i1++) {
										if (overallHAAT < lowVhfHAAT[i1]) {
											break;
										}
									}
									i0 = i1 - 1;
									maxerp = 10. * log10(lowVhfERP[i0] + ((lowVhfERP[i1] - lowVhfERP[i0]) *
										((overallHAAT - lowVhfHAAT[i0]) / (lowVhfHAAT[i1] - lowVhfHAAT[i0]))));
								} else {
									maxerp = 57.57 - 17.08 * log10(overallHAAT);
								}
							}
						}
						break;
					}

					case BAND_VHI: {
						if (source->zoneKey == ZONE_I) {
							if (overallHAAT <= 305.) {
								maxerp = 14.77;
							} else {
								maxerp = 97.35 - 33.24 * log10(overallHAAT);
							}
						} else {
							if (overallHAAT <= 305.) {
								maxerp = 22.04;
							} else {
								if (overallHAAT <= 610.) {
									for (i1 = 1; i1 < (NUM_HIGH_VHF - 1); i1++) {
										if (overallHAAT < highVhfHAAT[i1]) {
											break;
										}
									}
									i0 = i1 - 1;
									maxerp = 10. * log10(highVhfERP[i0] + ((highVhfERP[i1] - highVhfERP[i0]) *
										((overallHAAT - highVhfHAAT[i0]) / (highVhfHAAT[i1] - highVhfHAAT[i0]))));
								} else {
									maxerp = 62.34 - 17.08 * log10(overallHAAT);
								}
							}
						}
						break;
					}

					case BAND_UHF:
					default: {
						if (overallHAAT <= 365.) {
							maxerp = 30.;
						} else {
							if (overallHAAT <= 610.) {
								for (i1 = 1; i1 < (NUM_UHF - 1); i1++) {
									if (overallHAAT < uhfHAAT[i1]) {
										break;
									}
								}
								i0 = i1 - 1;
								maxerp = 10. * log10(uhfERP[i0] + ((uhfERP[i1] - uhfERP[i0]) *
									((overallHAAT - uhfHAAT[i0]) / (uhfHAAT[i1] - uhfHAAT[i0]))));
							} else {
								maxerp = 72.57 - 17.08 * log10(overallHAAT);
							}
						}
						break;
					}
				}

				if (source->peakERP > maxerp) {
					lcpystr(mesg, "ERP exceeds maximum", MAX_STRING);
					status_message(STATUS_KEY_REPORT, mesg);
					if (repFile) fprintf(repFile, "%s\n", mesg);
					lcpystr(mesg, "ERP: ", MAX_STRING);
					lcatstr(mesg, erpkw_string(source->peakERP), MAX_STRING);
					lcatstr(mesg, " kW   ERP maximum: ", MAX_STRING);
					lcatstr(mesg, erpkw_string(maxerp), MAX_STRING);
					lcatstr(mesg, " kW", MAX_STRING);
					status_message(STATUS_KEY_REPORT, mesg);
					status_message(STATUS_KEY_REPORT, "");
					if (repFile) fprintf(repFile, "%s\n\n", mesg);
				}
			}
		}

		mem_free(haat);
		haat = NULL;
	}

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Check coverage of the target vs. it's baseline record if any, and if enabled by parameter.  There are two tests.
// First determine if the target service area fits inside the baseline area expanded by a percentage (of distance on
// each contour radial) set by parameter.  For a DTS baseline the area is the union of the individual DTS source
// contours each expanded by the percentage.  For a DTS target all individual DTS source contours must fit in the
// baseline area, regardless of whether the baseline is DTS.  Also ignore an LPTV baseline, for a digital flash-cut
// proposal the analog license may appear in the baseline role, but the coverage test is not relevant in that case.
// Note this may still function even if there is no baseline or the baseline is not being checked, because this may
// also generate the proposed contour output files.

static int check_base_coverage(SOURCE *target, SOURCE *targetBase, FILE *repFile) {

	int doBaseCheck = (targetBase && Params.CheckBaselineAreaPop && (SERV_TVLP != targetBase->service));
	if (!doBaseCheck && !OutputFlags[IXCHK_PROP_CONT]) {
		return 0;
	}

	GEOPOINTS *baseContours = NULL, **linkContour, *contour, *baseContour;
	SOURCE *source, *sources;
	CONTOUR *contourExp;
	int i, fails, didfail = 0;
	char mesg[MAX_STRING];

	int countryIndex = target->countryKey - 1;

	double *haat = NULL, azm, dist, eah, erp, minHAAT = Params.MinimumHAAT[countryIndex];
	int haatCount =
		(SERV_TVLP == target->service) ? Params.HAATCountLPTV[countryIndex] : Params.HAATCount[countryIndex];

	FILE *outfile = NULL;

	// The contour(s) being tested, both baseline and proposed, may be output to CSV files.  If so the HAAT and ERP are
	// determined on each radial to a rendered contour point.  Note the rendering will always put points on all actual
	// contour radials, but it may also include intermediate curve-fit points.

	if (doBaseCheck) {

		double areaExtend = 1. + (Params.BaselineAreaExtendPercent / 100.);

		if (OutputFlags[IXCHK_PROP_CONT]) {
			snprintf(mesg, MAX_STRING, "%s_base.csv", PROP_CONT_BASE_NAME);
			outfile = open_sum_file(mesg);
			if (!outfile) return 1;
		}

		if (targetBase->isParent) {
			sources = targetBase->dtsSources;
		} else {
			sources = targetBase;
			targetBase->next = NULL;
		}

		linkContour = &baseContours;
		for (source = sources; source; source = source->next) {

			if (outfile) {
				if (source->parentSource) {
					fprintf(outfile, "DTS site # %d\n", source->siteNumber);
				}
				fputs("Azimuth,Distance,Latitude,Longitude,HAAT,ERP\n", outfile);
				if (haat) {
					mem_free(haat);
				}
				haat = compute_source_haat(source, haatCount);
				if (!haat) return -1;
			}

			contourExp = contour_alloc(source->contour->latitude, source->contour->longitude, source->contour->mode,
				source->contour->count);
			for (i = 0; i < contourExp->count; i++) {
				contourExp->distance[i] = source->contour->distance[i] * areaExtend;
			}
			contour = render_contour(contourExp);

			*linkContour = contour;
			linkContour = &(contour->next);
			contourExp->points = NULL;
			contour_free(contourExp);
			contourExp = NULL;

			if (outfile) {
				for (i = 0; i < contour->nPts; i++) {
					bear_distance(source->latitude, source->longitude, contour->ptLat[i], contour->ptLon[i], &azm,
						NULL, &dist, Params.KilometersPerDegree);
					eah = interp_min(azm, haat, haatCount, minHAAT, 1);
					erp = contour_erp_lookup(source, azm);
					fprintf(outfile, "%.2f,%.2f,%.8f,%.8f,%.2f,%.2f\n", azm, dist, contour->ptLat[i],
						contour->ptLon[i], eah, erp);
				}
			}
		}
	}

	if (outfile) {
		fclose(outfile);
	}

	if (OutputFlags[IXCHK_PROP_CONT]) {
		snprintf(mesg, MAX_STRING, "%s.csv", PROP_CONT_BASE_NAME);
		outfile = open_sum_file(mesg);
		if (!outfile) return 1;
	}

	if (target->isParent) {
		sources = target->dtsSources;
	} else {
		sources = target;
		target->next = NULL;
	}

	for (source = sources; source; source = source->next) {

		if (outfile) {
			if (source->parentSource) {
				fprintf(outfile, "DTS site # %d\n", source->siteNumber);
			}
			fputs("Azimuth,Distance,Latitude,Longitude,HAAT,ERP,Fails\n", outfile);
			if (haat) {
				mem_free(haat);
			}
			haat = compute_source_haat(source, haatCount);
			if (!haat) return -1;
		}

		contour = render_contour(source->contour);

		for (i = 0; i < contour->nPts; i++) {

			fails = 0;

			if (doBaseCheck) {
				for (baseContour = baseContours; baseContour; baseContour = baseContour->next) {
					if (inside_poly(contour->ptLat[i], contour->ptLon[i], baseContour)) {
						break;
					}
				}
				if (!baseContour) {
					fails = 1;
					didfail = 1;
					if (!outfile) {
						break;
					}
				}
			}

			if (outfile) {
				bear_distance(source->latitude, source->longitude, contour->ptLat[i], contour->ptLon[i], &azm, NULL,
					&dist, Params.KilometersPerDegree);
				eah = interp_min(azm, haat, haatCount, minHAAT, 1);
				erp = contour_erp_lookup(source, azm);
				fprintf(outfile, "%.2f,%.2f,%.8f,%.8f,%.2f,%.2f,%d\n", azm, dist, contour->ptLat[i], contour->ptLon[i],
					eah, erp, fails);
			}
		}

		if (didfail && !outfile) {
			break;
		}
	}

	// In addition to CSV, the proposal and baseline contours may also output to a shapefile or KML file.

	if (outfile) {
		fclose(outfile);
	}

	if ((IXCHK_PROP_CONT_CSVSHP == OutputFlags[IXCHK_PROP_CONT]) ||
			(IXCHK_PROP_CONT_CSVKML == OutputFlags[IXCHK_PROP_CONT])) {

		SHAPEATTR attrs[3] = {
			{"SOURCEKEY", SHP_ATTR_NUM, 5, 0},
			{"FILENUMBER", SHP_ATTR_CHAR, 22, 0},
			{"SITENUM", SHP_ATTR_NUM, 3, 0}
		};

		int fmt = MAP_FILE_SHAPE;
		if (IXCHK_PROP_CONT_CSVKML == OutputFlags[IXCHK_PROP_CONT]) {
			fmt = MAP_FILE_KML;
		}
		int nAttr = 2;
		if (target->isParent) {
			nAttr = 3;
		}
		MAPFILE *cont = open_sum_mapfile(fmt, PROP_CONT_BASE_NAME, SHP_TYPE_POLYLINE, nAttr, attrs, NULL, -1);
		if (!cont) return 1;

		char srcKey[6], siteNum[4], *attrData[3];
		attrData[0] = srcKey;
		attrData[2] = siteNum;

		if (targetBase) {
			if (targetBase->isParent) {
				sources = targetBase->dtsSources;
			} else {
				sources = targetBase;
				targetBase->next = NULL;
			}
			baseContour = baseContours;
			for (source = sources; source; source = source->next) {
				snprintf(srcKey, 6, "%d", source->sourceKey);
				snprintf(siteNum, 4, "%d", source->siteNumber);
				attrData[1] = source->fileNumber;
				if (write_shape(cont, 0., 0., render_service_area(source), 1, NULL, attrData)) {
					close_mapfile(cont);
					return 1;
				}
				if (baseContour) {
					if (write_shape(cont, 0., 0., baseContour, 1, NULL, attrData)) {
						close_mapfile(cont);
						return 1;
					}
					baseContour = baseContour->next;
				}
			}
		}

		if (target->isParent) {
			sources = target->dtsSources;
		} else {
			sources = target;
			target->next = NULL;
		}
		for (source = sources; source; source = source->next) {
			snprintf(srcKey, 6, "%d", source->sourceKey);
			snprintf(siteNum, 4, "%d", source->siteNumber);
			attrData[1] = source->fileNumber;
			if (write_shape(cont, 0., 0., render_service_area(source), 1, NULL, attrData)) {
				close_mapfile(cont);
				return 1;
			}
		}

		close_mapfile(cont);
	}

	if (haat) {
		mem_free(haat);
	}

	while (baseContours) {
		baseContour = baseContours;
		baseContours = baseContour->next;
		baseContour->next = NULL;
		geopoints_free(baseContour);
	}
	baseContour = NULL;

	if (!doBaseCheck) {
		return 0;
	}

	if (didfail) {
		snprintf(mesg, MAX_STRING, "**Proposal service area extends beyond baseline plus %.1f%%",
			Params.BaselineAreaExtendPercent);
	} else {
		snprintf(mesg, MAX_STRING, "Proposal service area is within baseline plus %.1f%%",
			Params.BaselineAreaExtendPercent);
	}
	status_message(STATUS_KEY_REPORT, mesg);
	if (repFile) fprintf(repFile, "%s\n", mesg);

	// The second test is just a straight percentage change of the terrain-limited population, fails if the target
	// reduces that by more than a percentage parameter.  Both sources should have desired totals, meaning both have
	// just been studied as desireds in a scenario.

	double frac = 1.;
	if (targetBase->totals[countryIndex].servicePop > 0) {
		frac = (double)target->totals[countryIndex].servicePop / (double)targetBase->totals[countryIndex].servicePop;
	}

	if (frac < (1. - (Params.BaselinePopReducePercent / 100.))) {
		snprintf(mesg, MAX_STRING, "**Proposal service area population is less than %.1f%% of baseline",
			(100. - Params.BaselinePopReducePercent));
	} else {
		snprintf(mesg, MAX_STRING, "Proposal service area population is more than %.1f%% of baseline",
			(100. - Params.BaselinePopReducePercent));
	}
	status_message(STATUS_KEY_REPORT, mesg);
	status_message(STATUS_KEY_REPORT, "");
	if (repFile) fprintf(repFile, "%s\n\n", mesg);

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Check a DTS operation to determine if there are coverage areas outside the union of the reference facility contour
// and the boundary distance/sectors around the reference point.  Does nothing for a non-DTS target.

static int check_dts_coverage(SOURCE *target, FILE *repFile) {

	if (!target->dts) {
		return 0;
	}

	GEOPOINTS *refContour, *contour;
	SOURCE *dtsSource;
	int i, fails = 0;
	char mesg[MAX_STRING];

	refContour = render_contour(target->dtsRefSource->contour);

	for (dtsSource = target->dtsSources; dtsSource; dtsSource = dtsSource->next) {
		contour = render_contour(dtsSource->contour);
		for (i = 0; i < contour->nPts; i++) {
			if (!inside_geography(contour->ptLat[i], contour->ptLon[i], target->geography) &&
					!inside_poly(contour->ptLat[i], contour->ptLon[i], refContour)) {
				fails = 1;
				break;
			}
		}
		if (fails) {
			break;
		}
	}

	if (fails) {
		lcpystr(mesg, "**DTS proposal has coverage outside reference facility and distance limit", MAX_STRING);
	} else {
		lcpystr(mesg, "DTS proposal coverage is within reference facility and distance limit", MAX_STRING);
	}
	status_message(STATUS_KEY_REPORT, mesg);
	status_message(STATUS_KEY_REPORT, "");
	if (repFile) fprintf(repFile, "%s\n\n", mesg);

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Report distances to Canadian and Mexican borders and check if the distances are within coordination limits.  For a
// DTS source, check all of the actual transmitter locations and use the smallest distance; the reference point and
// reference facility location are irrelevant for this test.  The coordination test for Canada may also be a contour
// check, project a particular F(50,10) contour and test if it crosses the Canadian border.

static int check_border_distance(SOURCE *target, FILE *repFile) {

	double bordDist[MAX_COUNTRY], minCanDist = 99999., minMexDist = 99999.;
	int err, i, fails;
	char mesg[MAX_STRING];

	SOURCE *source, *sources;

	if (target->isParent) {
		sources = target->dtsSources;
	} else {
		sources = target;
		target->next = NULL;
	}

	for (source = sources; source; source = source->next) {
		err = get_border_distances(source->latitude, source->longitude, bordDist);
		if (err) return err;
		if (bordDist[CNTRY_CAN - 1] < minCanDist) {
			minCanDist = bordDist[CNTRY_CAN - 1];
		}
		if (bordDist[CNTRY_MEX - 1] < minMexDist) {
			minMexDist = bordDist[CNTRY_MEX - 1];
		}
	}

	// For full-service, if less than 300 km from the Canadian border coordination is always required.  If between
	// 300 and 360 km, do the contour check.  For Class A and LPTV, if less than 300 km do the contour check.

	int doCont = 0;
	if (SERV_TV == target->service) {
		if (minCanDist <= 300.) {
			lcpystr(mesg, "**Proposal is within coordination distance of Canadian border", MAX_STRING);
			status_message(STATUS_KEY_REPORT, mesg);
			if (repFile) fprintf(repFile, "%s\n", mesg);
		} else {
			if (minCanDist <= 360.) {
				doCont = 1;
			}
		}
	} else {
		if (minCanDist <= 300.) {
			doCont = 1;
		}
	}

	if (doCont) {

		CONTOUR *srcContour;
		GEOPOINTS *contour;
		double contLevel;

		fails = 0;

		switch (target->band) {
			case BAND_VLO1:
			case BAND_VLO2: {
				contLevel = 13.;
				break;
			}
			case BAND_VHI: {
				contLevel = 21.;
				break;
			}
			case BAND_UHF:
			default: {
				contLevel = 26. - (20. * log10(615. / target->frequency));
				break;
			}
		}

		for (source = sources; source; source = source->next) {
			srcContour = project_fcc_contour(source, FCC_F10, contLevel);
			if (!srcContour) return -1;
			contour = render_contour(srcContour);
			for (i = 0; i < contour->nPts; i++) {
				if (CNTRY_CAN == find_country(contour->ptLat[i], contour->ptLon[i])) {
					fails = 1;
					break;
				}
			}
			contour_free(srcContour);
			srcContour = NULL;
			contour = NULL;
			if (fails) {
				break;
			}
		}

		if (fails) {
			snprintf(mesg, MAX_STRING,
				"**Proposal %.2f dBu contour crosses Canadian border, coordination required", contLevel);
		} else {
			snprintf(mesg, MAX_STRING, "Proposal %.2f dBu contour does not cross Canadian border", contLevel);
		}
		status_message(STATUS_KEY_REPORT, mesg);
		if (repFile) fprintf(repFile, "%s\n", mesg);
	}

	snprintf(mesg, MAX_STRING, "Distance to Canadian border: %.1f km", minCanDist);
	status_message(STATUS_KEY_REPORT, mesg);
	status_message(STATUS_KEY_REPORT, "");
	if (repFile) fprintf(repFile, "%s\n\n", mesg);

	if (SERV_TV == target->service) {
		fails = (minMexDist <= 250.);
	} else {
		fails = (minMexDist <= 275.);
	}
	if (fails) {
		lcpystr(mesg, "**Proposal is within coordination distance of Mexican border", MAX_STRING);
		status_message(STATUS_KEY_REPORT, mesg);
		if (repFile) fprintf(repFile, "%s\n", mesg);
	}

	snprintf(mesg, MAX_STRING, "Distance to Mexican border: %.1f km", minMexDist);
	status_message(STATUS_KEY_REPORT, mesg);
	status_message(STATUS_KEY_REPORT, "");
	if (repFile) fprintf(repFile, "%s\n\n", mesg);

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Check distance and field strength to FCC monitoring stations.  The list of locations to check is in a CSV data file
// so this does not involve any database access, on the first call read the file.

static int check_monitor_station(SOURCE *target, FILE *repFile) {

	static struct monsta {
		char name[MAX_STRING / 2];
		double latitude;
		double longitude;
		struct monsta *next;
	} *stationList = NULL;
	static int fileReadStatus = 0;

	struct monsta *station;
	int err = 0;

	if (!fileReadStatus) {

		char fname[MAX_STRING];

		snprintf(fname, MAX_STRING, "%s/monitor_station.csv", LIB_DIRECTORY_NAME);
		FILE *in = fopen(fname, "r");
		if (in) {

			struct monsta **link = &stationList;
			char line[MAX_STRING], *token;
			int deg, min;
			double sec;

			while (fgetnlc(line, MAX_STRING, in, NULL) >= 0) {

				token = next_token(line);
				if (!token) {
					err = 1;
					break;
				}

				station = (struct monsta *)mem_zalloc(sizeof(struct monsta));
				*link = station;
				link = &(station->next);

				lcpystr(station->name, token, (MAX_STRING / 2));

				err = token_atoi(next_token(NULL), -75, 75, &deg);
				if (err) break;
				err = token_atoi(next_token(NULL), 0, 59, &min);
				if (err) break;
				err = token_atof(next_token(NULL), 0, 60., &sec);
				if (err) break;
				station->latitude = (double)deg + ((double)min / 60.) + (sec / 3600.);

				err = token_atoi(next_token(NULL), -180, 180, &deg);
				if (err) break;
				err = token_atoi(next_token(NULL), 0, 59, &min);
				if (err) break;
				err = token_atof(next_token(NULL), 0, 60., &sec);
				if (err) break;
				station->longitude = (double)deg + ((double)min / 60.) + (sec / 3600.);
			}

			fclose(in);

			if (!stationList) {
				err = 1;
			}

		} else {
			err = 1;
		}

		if (err) {
			while (stationList) {
				station = stationList;
				stationList = station->next;
				station->next = NULL;
				mem_free(station);
			}
			fileReadStatus = -1;
		} else {
			fileReadStatus = 1;
		}
	}

	// If the file read failed report it but still return success, this does not abort the run.

	char mesg[MAX_STRING];

	if (fileReadStatus < 0) {
		lcpystr(mesg, "Unable to check FCC monitoring stations, error reading data file", MAX_STRING);
		status_message(STATUS_KEY_REPORT, mesg);
		status_message(STATUS_KEY_REPORT, "");
		if (repFile) fprintf(repFile, "%s\n\n", mesg);
		return 0;
	}

	// Check stations, report all that fail distance or field strength checks (the FCC stations are probably never
	// close enough for failures to more than one station, but to be safe that is not assumed).  If no failures are
	// found, just report conditions at the nearest station.  A one-off point structure is created at the station
	// location, then field strength(s) are computed for nearby transmitter(s), using FCC curves method.  For a DTS
	// there will be multiple fields.  All transmitters that fail the checks are reported.  If the distance check
	// fails the field strength conditions are also reported even if those do not fail the check.

	TVPOINT *point, *nearestPoint = NULL;
	FIELD *field;
	SOURCE *source;
	char *nearestName = NULL;
	double bear, dist, distInc, haat, erp, sigdbu, sigmvm, minDist, nearestDist = 99999.;
	int curv, failsDist, failsSig, didFail = 0, showName, showFailsDist = 1, showFailsSig = 1;

	int countryIndex = target->countryKey - 1;

	distInc = 1. / Params.TerrAvgPpk[countryIndex];
	if (target->dtv) {
		curv = Params.CurveSetDigital[countryIndex];
	} else {
		curv = Params.CurveSetAnalog[countryIndex];
	}

	for (station = stationList; station; station = station->next) {

		point = make_point(station->latitude, station->longitude, target);
		if (!point) return -1;

		minDist = 99999.;
		showName = 1;

		for (field = point->fields; field; field = field->next) {

			source = SourceKeyIndex[field->sourceKey];
			if (!source) {
				log_error("Source structure index is corrupted");
				exit(1);
			}

			bear = field->bearing;
			dist = field->distance;
			erp = erp_lookup(source, bear);

			if (dist < minDist) {
				minDist = dist;
			}

			failsDist = 0;
			failsSig = 0;

			if ((dist < 2.4) || ((dist < 4.8) && (erp > -13.01)) || ((dist < 16.) && (erp > 0.)) ||
					((dist < 80.) && (erp > 13.98))) {
				failsDist = 1;
				didFail = 1;
			}

			if (failsDist || (dist < Params.MaximumDistance)) {

				// This needs to use FCC curves with HAAT calculated by the contour-projection method, so it can't use
				// the propagation model code.  Still using point and field structures for convenience and to hold
				// results for later printout; but a bit of a hack, using the reverseBearing field to hold the HAAT.

				err = compute_haat_radial(source->latitude, source->longitude, bear, source->actualHeightAMSL, &haat,
					Params.TerrAvgDb, Params.AVETStartDistance[countryIndex], Params.AVETEndDistance[countryIndex],
					distInc, Params.KilometersPerDegree, NULL);
				if (err) {
					log_error("Terrain lookup failed: db=%d err=%d", Params.TerrAvgDb, err);
					free_point(point);
					if (nearestPoint) {
						free_point(nearestPoint);
					}
					return err;
				}
				field->reverseBearing = (float)haat;

				fcc_curve(&erp, &sigdbu, &dist, haat, source->band, FCC_FLD, curv, Params.OffCurveLookupMethod,
					source, bear, NULL, NULL, NULL);
				field->fieldStrength = (float)sigdbu;

				sigmvm = pow(10., (sigdbu / 20.)) / 1000.;

				if (sigdbu > 80.) {
					failsSig = 1;
					didFail = 1;
				}
			}

			if (failsDist && showFailsDist) {
				lcpystr(mesg, "**Proposal is within coordination distance of FCC monitoring station", MAX_STRING);
				status_message(STATUS_KEY_REPORT, mesg);
				if (repFile) fprintf(repFile, "%s\n", mesg);
				showFailsDist = 0;
			}

			if (failsSig && showFailsSig) {
				lcpystr(mesg, "**Proposal exceeds field strength limit at FCC monitoring station", MAX_STRING);
				status_message(STATUS_KEY_REPORT, mesg);
				if (repFile) fprintf(repFile, "%s\n", mesg);
				showFailsSig = 0;
			}

			if (failsDist || failsSig) {

				if (showName) {
					snprintf(mesg, MAX_STRING, "Conditions at FCC monitoring station: %s", station->name);
					status_message(STATUS_KEY_REPORT, mesg);
					if (repFile) fprintf(repFile, "%s\n", mesg);
					showName = 0;
				}
	
				if (source->parentSource) {
					snprintf(mesg, MAX_STRING, "DTS site # %d   Bearing: %.1f degrees   Distance: %.1f km",
						source->siteNumber, bear, dist);
				} else {
					snprintf(mesg, MAX_STRING, "Bearing: %.1f degrees   Distance: %.1f km", bear, dist);
				}
				status_message(STATUS_KEY_REPORT, mesg);
				if (repFile) fprintf(repFile, "%s\n", mesg);

				snprintf(mesg, MAX_STRING, "ERP: %s kW   HAAT: %.1f m  Field strength: %.1f dBu, %.1f mV/m",
					erpkw_string(erp), haat, sigdbu, sigmvm);
				status_message(STATUS_KEY_REPORT, mesg);
				if (repFile) fprintf(repFile, "%s\n", mesg);
			}
		}

		if (minDist < nearestDist) {
			nearestDist = minDist;
			nearestName = station->name;
			if (nearestPoint) {
				free_point(nearestPoint);
			}
			nearestPoint = point;
		} else {
			free_point(point);
		}
		point = NULL;
	}

	if (!didFail) {

		snprintf(mesg, MAX_STRING, "Conditions at FCC monitoring station: %s", nearestName);
		status_message(STATUS_KEY_REPORT, mesg);
		if (repFile) fprintf(repFile, "%s\n", mesg);

		for (field = nearestPoint->fields; field; field = field->next) {

			source = SourceKeyIndex[field->sourceKey];
			if (!source) {
				log_error("Source structure index is corrupted");
				exit(1);
			}

			bear = field->bearing;
			dist = field->distance;
			erp = erp_lookup(source, bear);

			if (source->parentSource) {
				snprintf(mesg, MAX_STRING, "DTS site # %d   Bearing: %.1f degrees   Distance: %.1f km",
					source->siteNumber, bear, dist);
			} else {
				snprintf(mesg, MAX_STRING, "Bearing: %.1f degrees   Distance: %.1f km", bear, dist);
			}
			status_message(STATUS_KEY_REPORT, mesg);
			if (repFile) fprintf(repFile, "%s\n", mesg);

			if (field->status >= 0) {

				haat = field->reverseBearing;
				sigdbu = field->fieldStrength;
				sigmvm = pow(10., (sigdbu / 20.)) / 1000.;

				snprintf(mesg, MAX_STRING, "ERP: %s kW   HAAT: %.1f m  Field strength: %.1f dBu, %.1f mV/m",
					erpkw_string(erp), haat, sigdbu, sigmvm);
				status_message(STATUS_KEY_REPORT, mesg);
				if (repFile) fprintf(repFile, "%s\n", mesg);
			}
		}
	}

	free_point(nearestPoint);

	status_message(STATUS_KEY_REPORT, "");
	if (repFile) fputc('\n', repFile);

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Check target versus the West Virgina quiet zone area.  This is a simple boundary box check.

static int check_va_quiet_zone(SOURCE *target, FILE *repFile) {

	SOURCE *dtsSource;
	int fail;
	char mesg[MAX_STRING];

	if (target->dts) {

		for (dtsSource = target->dtsSources; dtsSource; dtsSource = dtsSource->next) {
			fail = ((dtsSource->latitude >= 37.5) && (dtsSource->latitude <= 39.25) &&
				(dtsSource->longitude >= 78.5) && (dtsSource->longitude <= 80.5));
			if (fail) {
				break;
			}
		}

	} else {

		fail = ((target->latitude >= 37.5) && (target->latitude <= 39.25) &&
			(target->longitude >= 78.5) && (target->longitude <= 80.5));
	}

	if (fail) {
		lcpystr(mesg, "**Proposal is within the West Virginia quiet zone area", MAX_STRING);
	} else {
		lcpystr(mesg, "Proposal is not within the West Virginia quiet zone area", MAX_STRING);
	}
	status_message(STATUS_KEY_REPORT, mesg);
	status_message(STATUS_KEY_REPORT, "");
	if (repFile) fprintf(repFile, "%s\n\n", mesg);

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Check the Table Mountain receiving zone, test distance and field strength at the four corners of the area.  The
// approach is similar to the monitoring station check, except that only one corner point with worst-case conditions
// is reported, that is the point with highest field strength if those are projected, else it is the closest point.

static int check_table_mountain(SOURCE *target, FILE *repFile) {

	static double tmLat[2] = {40.11805556, 40.15277778};
	static double tmLon[2] = {105.2252778, 105.2536111};

	TVPOINT *point, *worstPoint = NULL;
	FIELD *field;
	SOURCE *source;
	double lat, lon, bear, dist, erp, sigdbu, sigmvm, minDist, maxSig, worstDist = 99999., worstSig = -999.;
	int err, ilat, ilon, failsDist, didFailDist = 0, didFailSig = 0;
	char mesg[MAX_STRING];

	for (ilat = 0; ilat < 2; ilat++) {
		for (ilon = 0; ilon < 2; ilon++) {

			lat = tmLat[ilat];
			lon = tmLon[ilon];

			point = make_point(lat, lon, target);
			if (!point) return -1;

			minDist = 99999.;
			maxSig = -999.;

			for (field = point->fields; field; field = field->next) {

				source = SourceKeyIndex[field->sourceKey];
				if (!source) {
					log_error("Source structure index is corrupted");
					exit(1);
				}

				dist = field->distance;
				bear = field->bearing;
				erp = erp_lookup(source, bear);

				if (dist < minDist) {
					minDist = dist;
				}

				failsDist = 0;

				if ((dist < 2.4) || ((dist < 4.8) && (erp > -13.01)) || ((dist < 16.) && (erp > 0.)) ||
						((dist < 80.) && (erp > 13.98))) {
					failsDist = 1;
					didFailDist = 1;
				}

				if (failsDist || (dist <= Params.MaximumDistance)) {

					err = project_field(point, field);
					if (err) {
						free_point(point);
						return err;
					}

					sigdbu = field->fieldStrength;
					sigmvm = pow(10., (sigdbu / 20.)) / 1000.;

					if (sigdbu > maxSig) {
						maxSig = sigdbu;
					}

					if (BAND_UHF == target->band) {
						if (sigdbu > 89.5) {
							didFailSig = 1;
						}
					} else {
						if (sigdbu > 80.) {
							didFailSig = 1;
						}
					}
				}
			}

			if ((maxSig > worstSig) || ((-999. == worstSig) && (minDist < worstDist))) {
				worstDist = minDist;
				worstSig = maxSig;
				if (worstPoint) {
					free_point(worstPoint);
					worstPoint = NULL;
				}
				worstPoint = point;
			} else {
				free_point(point);
			}
			point = NULL;
		}
	}

	if (didFailDist) {
		lcpystr(mesg, "**Proposal is within coordination distance of Table Mountain receiving zone", MAX_STRING);
		status_message(STATUS_KEY_REPORT, mesg);
		if (repFile) fprintf(repFile, "%s\n", mesg);
	}

	if (didFailSig) {
		lcpystr(mesg, "**Proposal exceeds field strength limit at Table Mountain receiving zone", MAX_STRING);
		status_message(STATUS_KEY_REPORT, mesg);
		if (repFile) fprintf(repFile, "%s\n", mesg);
	}

	lcpystr(mesg, "Conditions at Table Mountain receiving zone:", MAX_STRING);
	status_message(STATUS_KEY_REPORT, mesg);
	if (repFile) fprintf(repFile, "%s\n", mesg);

	for (field = worstPoint->fields; field; field = field->next) {

		source = SourceKeyIndex[field->sourceKey];
		if (!source) {
			log_error("Source structure index is corrupted");
			exit(1);
		}

		bear = field->bearing;
		dist = field->distance;
		erp = erp_lookup(source, bear);

		if (source->parentSource) {
			snprintf(mesg, MAX_STRING, "DTS site # %d   Bearing: %.1f degrees   Distance: %.1f km",
				source->siteNumber, bear, dist);
		} else {
			snprintf(mesg, MAX_STRING, "Bearing: %.1f degrees   Distance: %.1f km", bear, dist);
		}
		status_message(STATUS_KEY_REPORT, mesg);
		if (repFile) fprintf(repFile, "%s\n", mesg);

		if (field->status >= 0) {

			sigdbu = field->fieldStrength;
			sigmvm = pow(10., (sigdbu / 20.)) / 1000.;

			snprintf(mesg, MAX_STRING, "ERP: %s kW   Field strength: %.1f dBu, %.1f mV/m", erpkw_string(erp), sigdbu,
				sigmvm);
			status_message(STATUS_KEY_REPORT, mesg);
			if (repFile) fprintf(repFile, "%s\n", mesg);
		}
	}

	free_point(worstPoint);

	status_message(STATUS_KEY_REPORT, "");
	if (repFile) fputc('\n', repFile);

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Check for protection to land mobile stations.  This reads various land mobile city and coordinate lists and test
// conditions from data files, read those on the first call.  The main stationList is the set of station locations to
// be protected per 73.623(e) and 74.709(a) (the lists in those two sections are nominally the same, however the
// coordinates of some locations vary slightly so there may be multiple entries in the data files here).  An additional
// set of waiver locations is also included in the list, that data is provided and updated by FCC staff as needed.
// Each entry in the list can define a full-service distance check and/or a Class A/LPTV/translator distance and
// contour overlap check, optionally with exclusions to the land-mobile protected area as defined in 74.709(b) (as of
// this writing the exclusions do not apply to the waiver list).  The exclusion list is read from a separate file.  For
// convenience when updating the data the main and waiver lists are in separate files, but are combined into one list
// when read.  All of the distances and contour levels are provided in the files.  This only applies to channel 14-20
// according to the rules, however channel 21 is included as it may be adjacent to channel 20 land mobile.  In that
// case if there is an issue found it is reported as an advisory message not a failure.

static int check_land_mobile(SOURCE *target, FILE *repFile) {

	if ((target->channel < 14) || (target->channel > 21)) {
		return 0;
	}

	static struct lanmob {
		char name[MAX_STRING / 2];
		int channel;
		double latitude;
		double longitude;
		double fsCoDistance;
		double fsAdjDistance;
		double lpCoDistance;
		double lpAdjDistance;
		double lpCoContourLevel;
		double lpAdjContourLevel;
		double lpCoExcludeDistance;
		double lpAdjExcludeDistance;
		struct lanmob *next;
	} *stationList = NULL, *excludeList = NULL;
	static int fileReadStatus = 0;

	struct lanmob *station, *exclude;
	int err = 0;

	if (!fileReadStatus) {

		FILE *in = NULL;
		struct lanmob **link;
		char fname[MAX_STRING], line[MAX_STRING], *token;
		int deg, min, fi;
		double sec;

		for (fi = 0; fi < 3; fi++) {

			switch (fi) {
				case 0: {
					link = &stationList;
					snprintf(fname, MAX_STRING, "%s/land_mobile.csv", LIB_DIRECTORY_NAME);
					in = fopen(fname, "r");
					break;
				}
				case 1: {
					snprintf(fname, MAX_STRING, "%s/land_mobile_waiver.csv", LIB_DIRECTORY_NAME);
					in = fopen(fname, "r");
					break;
				}
				case 2: {
					link = &excludeList;
					snprintf(fname, MAX_STRING, "%s/land_mobile_exclude.csv", LIB_DIRECTORY_NAME);
					in = fopen(fname, "r");
					break;
				}
			}

			if (!in) {
				err = 1;
				break;
			}

			while (fgetnlc(line, MAX_STRING, in, NULL) >= 0) {

				token = next_token(line);
				if (!token) {
					err = 1;
					break;
				}

				station = (struct lanmob *)mem_zalloc(sizeof(struct lanmob));
				*link = station;
				link = &(station->next);

				lcpystr(station->name, token, (MAX_STRING / 2));

				err = token_atoi(next_token(NULL), 2, 51, &(station->channel));
				if (err) break;

				err = token_atoi(next_token(NULL), -75, 75, &deg);
				if (err) break;
				err = token_atoi(next_token(NULL), 0, 59, &min);
				if (err) break;
				err = token_atof(next_token(NULL), 0, 60., &sec);
				if (err) break;
				station->latitude = (double)deg + ((double)min / 60.) + (sec / 3600.);

				err = token_atoi(next_token(NULL), -180, 180, &deg);
				if (err) break;
				err = token_atoi(next_token(NULL), 0, 59, &min);
				if (err) break;
				err = token_atof(next_token(NULL), 0, 60., &sec);
				if (err) break;
				station->longitude = (double)deg + ((double)min / 60.) + (sec / 3600.);

				if ((token = next_token(NULL))) {
					err = token_atof(token, 0., 500., &(station->fsCoDistance));
					if (err) break;
				}
				if ((token = next_token(NULL))) {
					err = token_atof(token, 0., 500., &(station->fsAdjDistance));
					if (err) break;
				}

				if ((token = next_token(NULL))) {
					err = token_atof(token, 0., 500., &(station->lpCoDistance));
					if (err) break;
				}
				if ((token = next_token(NULL))) {
					err = token_atof(token, 0., 500., &(station->lpAdjDistance));
					if (err) break;
				}
				if ((token = next_token(NULL))) {
					err = token_atof(token, 0., 110., &(station->lpCoContourLevel));
					if (err) break;
				}
				if ((token = next_token(NULL))) {
					err = token_atof(token, 0., 110., &(station->lpAdjContourLevel));
					if (err) break;
				}
				if ((token = next_token(NULL))) {
					err = token_atof(token, 0., 500., &(station->lpCoExcludeDistance));
					if (err) break;
				}
				if ((token = next_token(NULL))) {
					err = token_atof(token, 0., 500., &(station->lpAdjExcludeDistance));
					if (err) break;
				}
			}

			fclose(in);
			if (err) break;
		}

		if (err) {
			while (stationList) {
				station = stationList;
				stationList = station->next;
				station->next = NULL;
				mem_free(station);
			}
			while (excludeList) {
				exclude = excludeList;
				excludeList = exclude->next;
				exclude->next = NULL;
				mem_free(exclude);
			}
			fileReadStatus = -1;
		} else {
			fileReadStatus = 1;
		}
	}

	// If any file reads failed report it but still return success, this does not abort the run.

	char mesg[MAX_STRING];

	if (fileReadStatus < 0) {
		lcpystr(mesg, "Unable to check land mobile stations, error reading data file", MAX_STRING);
		status_message(STATUS_KEY_REPORT, mesg);
		status_message(STATUS_KEY_REPORT, "");
		if (repFile) fprintf(repFile, "%s\n\n", mesg);
		return 0;
	}

	// Do the checks.  The full-service distance check only applies if the target is full-service of course, but
	// entries in the list may also not have the full-service check applied at all if the distances were not defined
	// in the file.  Likewise the Class A/LPTV/translator contour-overlap check only applies if parameters are in
	// the file.  Also within the overlap check, the exclusions may or may not be applied depending on whether the
	// exclusion distances are in the file.  Note the exclusion distance is a radius around the exclusion points, but
	// those distances are a property of the protected station not the exclusion; per 74.709(b) there are variations
	// in that distance depending on which protected station is being considered.

	GEOPOINTS *coContours = NULL, *adjContours = NULL, **linkContour, *chkContours, *contour;
	CONTOUR *srcContour;
	SOURCE *sources, *source;
	double minDist, exclDist, contLevel, dist, failDist, coContourLevel, adjContourLevel;
	int delta, exclDelta, i, fails, didFail = 0;

	for (station = stationList; station; station = station->next) {

		delta = abs(target->channel - station->channel);
		if (delta > 1) {
			continue;
		}

		if (SERV_TV == target->service) {
			if (0. == station->fsCoDistance) {
				continue;
			}
			if (0 == delta) {
				minDist = station->fsCoDistance;
			} else {
				minDist = station->fsAdjDistance;
			}
			exclDist = 0.;
			contLevel = 0.;
		} else {
			if (0. == station->lpCoDistance) {
				continue;
			}
			if (0 == delta) {
				minDist = station->lpCoDistance;
				contLevel = station->lpCoContourLevel;
			} else {
				minDist = station->lpAdjDistance;
				contLevel = station->lpAdjContourLevel;
			}
		}

		// Do the distance check.  For a DTS each transmitter point has to be fully checked not just the closest, due
		// to the exclusions the closest point could pass but the next-closest still fail.  Unlikely but possible.
		// However once a failure is found the check does not continue, this does not report distances to every DTS
		// transmitter, just the first one that fails.

		fails = 0;
		failDist = 0.;

		if (target->isParent) {
			sources = target->dtsSources;
		} else {
			sources = target;
			target->next = NULL;
		}

		for (source = sources; source; source = source->next) {
			bear_distance(station->latitude, station->longitude, source->latitude, source->longitude, NULL, NULL,
				&dist, Params.KilometersPerDegree);
			if (dist <= minDist) {
				fails = 1;
				failDist = dist;
				if ((SERV_TV != target->service) && (station->lpCoExcludeDistance > 0.)) {
					for (exclude = excludeList; exclude; exclude = exclude->next) {
						exclDelta = abs(station->channel - exclude->channel);
						if (exclDelta > 1) {
							continue;
						}
						if (0 == exclDelta) {
							exclDist = station->lpCoExcludeDistance;
						} else {
							exclDist = station->lpAdjExcludeDistance;
						}
						bear_distance(exclude->latitude, exclude->longitude, source->latitude, source->longitude, NULL,
							NULL, &dist, Params.KilometersPerDegree);
						if (dist <= exclDist) {
							fails = 0;
							break;
						}
					}
				}
			}
			if (fails) {
				break;
			}
		}

		// If the distance check fails, any contour check can be skipped since it would obviously fail too.  If the
		// proposal is on channel 21 this is advisory only, not a failure.

		if (fails) {
			if (target->channel < 21) {
				snprintf(mesg, MAX_STRING,
					"**Proposal fails distance check to land mobile station: %s ch. %d, %.1f km", station->name,
					station->channel, failDist);
				status_message(STATUS_KEY_REPORT, mesg);
				if (repFile) fprintf(repFile, "%s\n", mesg);
				didFail = 1;
			} else {
				if (repFile) {
					fprintf(repFile, "Proposal is short-spaced to land mobile station: %s ch. %d, %.1f km\n",
						station->name, station->channel, failDist);
				}
			}
			continue;
		}

		// Proceed to contour overlap if applicable and if the target is close enough for possible overlap; see
		// discussion in set_rule_extra_distance() in source.c.

		if (0. == contLevel) {
			continue;
		}

		for (source = sources; source; source = source->next) {
			bear_distance(station->latitude, station->longitude, source->latitude, source->longitude, NULL, NULL,
				&dist, Params.KilometersPerDegree);
			if (dist <= (minDist + source->ruleExtraDistance)) {
				break;
			}
		}
		if (!source) {
			continue;
		}

		// Project contours from the target station as needed.  All contours are projected from a DTS regardless of
		// the individual distance because once projected these may be re-used for the next land mobile station needing
		// this check.  Generally the same contour levels will apply to all but if the level does change, discard the
		// existing contours.  If there are contour changes the data file should be ordered to minimize re-projection.

		if (0 == delta) {

			linkContour = &coContours;
			if (*linkContour && (coContourLevel != contLevel)) {
				while (coContours) {
					contour = coContours;
					coContours = contour->next;
					contour->next = NULL;
					geopoints_free(contour);
				}
			}

			coContourLevel = contLevel;

		} else {

			linkContour = &adjContours;
			if (*linkContour && (adjContourLevel != contLevel)) {
				while (adjContours) {
					contour = adjContours;
					adjContours = contour->next;
					contour->next = NULL;
					geopoints_free(contour);
				}
			}

			adjContourLevel = contLevel;
		}

		if (!(*linkContour)) {
			for (source = sources; source; source = source->next) {
				srcContour = project_fcc_contour(source, FCC_F10, contLevel);
				if (!srcContour) return -1;
				*linkContour = render_contour(srcContour);
				linkContour = &((*linkContour)->next);
				srcContour->points = NULL;
				contour_free(srcContour);
				srcContour = NULL;
			}
		}

		if (0 == delta) {
			chkContours = coContours;
		} else {
			chkContours = adjContours;
		}

		for (contour = chkContours; contour; contour = contour->next) {
			for (i = 0; i < contour->nPts; i++) {
				bear_distance(station->latitude, station->longitude, contour->ptLat[i], contour->ptLon[i], NULL, NULL,
					&dist, Params.KilometersPerDegree);
				if (dist <= minDist) {
					fails = 1;
					if ((SERV_TV != target->service) && (station->lpCoExcludeDistance > 0.)) {
						for (exclude = excludeList; exclude; exclude = exclude->next) {
							exclDelta = abs(station->channel - exclude->channel);
							if (exclDelta > 1) {
								continue;
							}
							if (0 == exclDelta) {
								exclDist = station->lpCoExcludeDistance;
							} else {
								exclDist = station->lpAdjExcludeDistance;
							}
							bear_distance(exclude->latitude, exclude->longitude, contour->ptLat[i], contour->ptLon[i],
								NULL, NULL, &dist, Params.KilometersPerDegree);
							if (dist <= exclDist) {
								fails = 0;
								break;
							}
						}
					}
				}
				if (fails) {
					break;
				}
			}
			if (fails) {
				break;
			}
		}

		if (fails) {
			if (target->channel < 21) {
				snprintf(mesg, MAX_STRING, "**Proposal fails contour check to land mobile station: %s ch. %d",
					station->name, station->channel);
				status_message(STATUS_KEY_REPORT, mesg);
				if (repFile) fprintf(repFile, "%s\n", mesg);
				didFail = 1;
			} else {
				if (repFile) {
					fprintf(repFile, "Proposal has contour overlap to land mobile station: %s ch. %d\n",
						station->name, station->channel);
				}
			}
		}
	}

	while (coContours) {
		contour = coContours;
		coContours = contour->next;
		contour->next = NULL;
		geopoints_free(contour);
	}
	while (adjContours) {
		contour = adjContours;
		adjContours = contour->next;
		contour->next = NULL;
		geopoints_free(contour);
	}
	contour = NULL;

	if (didFail) {
		status_message(STATUS_KEY_REPORT, "");
		if (repFile) fputc('\n', repFile);
	} else {
		lcpystr(mesg, "No land mobile station failures found", MAX_STRING);
		status_message(STATUS_KEY_REPORT, mesg);
		status_message(STATUS_KEY_REPORT, "");
		if (repFile) fprintf(repFile, "%s\n\n", mesg);
	}

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Check target versus the offshore radio service protections in 74.709(e).  Only for Class A/LPTV/translator.  This
// is a set of geographic limit checks, the target cannot be between certain ranges of longitude and south of a line
// of latitude which may not be directly east-west so the latitude limit is interpolated.

static int check_offshore_radio(SOURCE *target, FILE *repFile) {

	if (SERV_TV == target->service) {
		return 0;
	}

	double lat1, lon1, lat2, lon2, lat3, lon3;

	switch (target->channel) {
		case 15: {
			lat1 = 30.5;
			lon1 = 92.0;
			lat2 = 30.5;
			lon2 = 96.0;
			lat3 = 28.0;
			lon3 = 98.5;
			break;
		}
		case 16: {
			lat1 = 31.0;
			lon1 = 86.6666666666667;
			lat2 = 31.0;
			lon2 = 95.0;
			lat3 = 29.5;
			lon3 = 96.5;
			break;
		}
		case 17: {
			lat1 = 31.0;
			lon1 = 86.5;
			lat2 = 31.5;
			lon2 = 94.0;
			lat3 = 29.5;
			lon3 = 96.0;
			break;
		}
		case 18: {
			lat1 = 31.0;
			lon1 = 87.0;
			lat2 = 31.0;
			lon2 = 91.0;
			lat3 = 31.0;
			lon3 = 95.0;
			break;
		}
		default: {
			return 0;
		}
	}

	SOURCE *source, *sources;
	double checkLat;
	int fail = 0;
	char mesg[MAX_STRING];

	if (target->isParent) {
		sources = target->dtsSources;
	} else {
		sources = target;
		target->next = NULL;
	}

	for (source = sources; source; source = source->next) {
		if ((source->longitude >= lon1) && (source->longitude <= lon2)) {
			checkLat = lat1 + ((lat2 - lat1) * ((source->longitude - lon1) / (lon2 - lon1)));
			if (source->latitude <= checkLat) {
				fail = 1;
				break;
			}
		} else {
			if ((source->longitude >= lon2) && (source->longitude <= lon3)) {
				checkLat = lat2 + ((lat3 - lat2) * ((source->longitude - lon2) / (lon3 - lon2)));
				if (source->latitude <= checkLat) {
					fail = 1;
					break;
				}
			}
		}
	}

	if (fail) {
		lcpystr(mesg, "**Proposal is within the Offshore Radio Service protected area", MAX_STRING);
	} else {
		lcpystr(mesg, "Proposal is not within the Offshore Radio Service protected area", MAX_STRING);
	}
	status_message(STATUS_KEY_REPORT, mesg);
	status_message(STATUS_KEY_REPORT, "");
	if (repFile) fprintf(repFile, "%s\n\n", mesg);

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Run a scenario, generate output.  A study must be open.

// Arguments:

//   scenarioKey  Primary key for scenario record in current study database.

// Return is <0 for major error, >0 for minor error, 0 for no error.

int run_scenario(int scenarioKey) {

	if (!StudyKey) {
		log_error("run_scenario() called with no study open");
		return 1;
	}

	// Initialize output flags if needed, caller may have done that already.

	if (!OutputFlagsSet) {
		parse_flags(OutputCodes, OutputFlags, MAX_OUTPUT_FLAGS);
		OutputFlagsSet = 1;
	}
	if (!MapOutputFlagsSet) {
		parse_flags(MapOutputCodes, MapOutputFlags, MAX_MAP_OUTPUT_FLAGS);
		MapOutputFlagsSet = 1;
	}

	DoComposite = 0;

	// Open summary report, CSV, and cell files if needed, these accumulate output across all scenarios run for the
	// open study so these stay open across calls and will be closed in close_study().  In the special pair study
	// cell format temporary output files are used which are post-processed by an external utility to generate final
	// output, those are also at the summary level and stay open across all scenarios.

	FILE *outFile;

	if (OutputFlags[REPORT_FILE_SUMMARY] && !OutputFile[REPORT_FILE_SUMMARY]) {
		outFile = open_sum_file(REPORT_FILE_NAME);
		if (!outFile) return 1;
		OutputFile[REPORT_FILE_SUMMARY] = outFile;
		write_report_preamble(REPORT_FILE_SUMMARY, outFile, 1, 1);
		ReportSumHeader = 1;
	}

	if (OutputFlags[CSV_FILE_SUMMARY] && !OutputFile[CSV_FILE_SUMMARY]) {
		outFile = open_sum_file(CSV_FILE_NAME);
		if (!outFile) return 1;
		OutputFile[CSV_FILE_SUMMARY] = outFile;
		write_csv_preamble(CSV_FILE_SUMMARY, outFile, 1, 1);
		CSVSumHeader = 1;
	}

	if (OutputFlags[CELL_FILE_SUMMARY] && !OutputFile[CELL_FILE_SUMMARY]) {
		outFile = open_sum_file(CELL_FILE_NAME);
		if (!outFile) return 1;
		OutputFile[CELL_FILE_SUMMARY] = outFile;
	}

	if (OutputFlags[CELL_FILE_PAIRSTUDY] && !OutputFile[CELL_FILE_PAIRSTUDY]) {
		outFile = open_temp_file();
		if (!outFile) return 1;
		OutputFile[CELL_FILE_PAIRSTUDY] = outFile;
	}

	// Load the scenario.

	log_message("Loading scenarioKey=%d", scenarioKey);

	int err = load_scenario(scenarioKey);
	if (err) return err;

	log_message("Running scenario %s", ScenarioName);

	// Run logic factored out so cleanup can happen here.

	err = do_run_scenario(scenarioKey);

	// Free the point map if it was allocated.

	if (PointMap) {
		PointMapCount = 0;
		mem_free(PointMap);
		PointMap = NULL;
		PointMapLatCount = 0;
		PointMapLonSize = 0;
		PointMapLonCount = 0;
		mem_free(PointMapLonSizes);
		PointMapLonSizes = NULL;
		mem_free(PointMapEastLonIndex);
		PointMapEastLonIndex = NULL;
	}

	// Close all scenario-level files that might be open.

	if (OutputFile[REPORT_FILE_DETAIL]) {
		fclose(OutputFile[REPORT_FILE_DETAIL]);
		OutputFile[REPORT_FILE_DETAIL] = NULL;
	}
	if (OutputFile[CSV_FILE_DETAIL]) {
		fclose(OutputFile[CSV_FILE_DETAIL]);
		OutputFile[CSV_FILE_DETAIL] = NULL;
	}
	if (OutputFile[CELL_FILE_DETAIL]) {
		fclose(OutputFile[CELL_FILE_DETAIL]);
		OutputFile[CELL_FILE_DETAIL] = NULL;
	}
	if (OutputFile[CELL_FILE_CSV]) {
		fclose(OutputFile[CELL_FILE_CSV]);
		OutputFile[CELL_FILE_CSV] = NULL;
	}
	if (OutputFile[CELL_FILE_CSV_D]) {
		fclose(OutputFile[CELL_FILE_CSV_D]);
		OutputFile[CELL_FILE_CSV_D] = NULL;
	}
	if (OutputFile[CELL_FILE_CSV_U]) {
		fclose(OutputFile[CELL_FILE_CSV_U]);
		OutputFile[CELL_FILE_CSV_U] = NULL;
	}
	if (OutputFile[CELL_FILE_CSV_WL_U]) {
		fclose(OutputFile[CELL_FILE_CSV_WL_U]);
		OutputFile[CELL_FILE_CSV_WL_U] = NULL;
	}
	if (OutputFile[CELL_FILE_CSV_WL_U_RSS]) {
		fclose(OutputFile[CELL_FILE_CSV_WL_U_RSS]);
		OutputFile[CELL_FILE_CSV_WL_U_RSS] = NULL;
	}
	if (MapOutputFile[MAP_OUT_SHAPE_POINTS]) {
		close_mapfile(MapOutputFile[MAP_OUT_SHAPE_POINTS]);
		MapOutputFile[MAP_OUT_SHAPE_POINTS] = NULL;
	}
	if (MapOutputFile[MAP_OUT_SHAPE_COV]) {
		close_mapfile(MapOutputFile[MAP_OUT_SHAPE_COV]);
		MapOutputFile[MAP_OUT_SHAPE_COV] = NULL;
	}
	if (MapOutputFile[MAP_OUT_KML_POINTS]) {
		close_mapfile(MapOutputFile[MAP_OUT_KML_POINTS]);
		MapOutputFile[MAP_OUT_KML_POINTS] = NULL;
	}
	if (MapOutputFile[MAP_OUT_KML_COV]) {
		close_mapfile(MapOutputFile[MAP_OUT_KML_COV]);
		MapOutputFile[MAP_OUT_KML_COV] = NULL;
	}
	if (MapOutputFile[MAP_OUT_SHAPE_SELFIX]) {
		close_mapfile(MapOutputFile[MAP_OUT_SHAPE_SELFIX]);
		MapOutputFile[MAP_OUT_SHAPE_SELFIX] = NULL;
	}
	if (MapOutputFile[MAP_OUT_KML_SELFIX]) {
		close_mapfile(MapOutputFile[MAP_OUT_KML_SELFIX]);
		MapOutputFile[MAP_OUT_KML_SELFIX] = NULL;
	}
	if (OutputFile[POINTS_FILE]) {
		fclose(OutputFile[POINTS_FILE]);
		OutputFile[POINTS_FILE] = NULL;
	}
	if (OutputFile[IMAGE_FILE]) {
		fclose(OutputFile[IMAGE_FILE]);
		OutputFile[IMAGE_FILE] = NULL;
	}

	return err;
}

static int do_run_scenario(int scenarioKey) {

	int err = 0;

	// Open scenario-level cell files, these are closed as soon as the scenario run is done.  The detail CSV cell file
	// format writes different data to multiple files, see analyze_points().  Some are specific to an OET-74 study.

	FILE *outFile;

	if (OutputFlags[CELL_FILE_DETAIL]) {
		outFile = open_file(CELL_FILE_NAME);
		if (!outFile) return 1;
		OutputFile[CELL_FILE_DETAIL] = outFile;
	}

	if (OutputFlags[CELL_FILE_CSV]) {

		outFile = open_file(CELL_CSV_SOURCE_FILE_NAME);
		if (!outFile) return 1;
		OutputFile[CELL_FILE_CSV] = outFile;

		outFile = open_file(CELL_CSV_D_FILE_NAME);
		if (!outFile) return 1;
		OutputFile[CELL_FILE_CSV_D] = outFile;

		outFile = open_file(CELL_CSV_U_FILE_NAME);
		if (!outFile) return 1;
		OutputFile[CELL_FILE_CSV_U] = outFile;

		if (STUDY_TYPE_TV_OET74 == StudyType) {

			outFile = open_file(CELL_CSV_WL_U_FILE_NAME);
			if (!outFile) return 1;
			OutputFile[CELL_FILE_CSV_WL_U] = outFile;

			outFile = open_file(CELL_CSV_WL_U_RSS_FILE_NAME);
			if (!outFile) return 1;
			OutputFile[CELL_FILE_CSV_WL_U_RSS] = outFile;
		}
	}

	// Initial output to cell files.

	if ((outFile = OutputFile[CELL_FILE_PAIRSTUDY])) {
		fprintf(outFile, "[case]\n%d,%d,%d\n", StudyKey, ScenarioKey, CellLatSize);
	}

	if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
		fprintf(outFile, "[scenario]\n%s\n%s\n%s\n%s\n%s\n%.2f,%d,%d\n", HostDbName, ExtDbName, StudyName,
			ScenarioName, log_open_time(), Params.CellSize, Params.GridType, CellLatSize);
		if (RunComment) {
			fprintf(outFile, "[comment]\n%s\n[endcomment]\n", RunComment);
		}
		fputs("[sources]\n", outFile);
	}

	if ((outFile = OutputFile[CELL_FILE_SUMMARY])) {
		fprintf(outFile, "[scenario]\n%s\n%s\n%s\n%s\n%s\n%.2f,%d,%d\n", HostDbName, ExtDbName, StudyName,
			ScenarioName, log_open_time(), Params.CellSize, Params.GridType, CellLatSize);
		if (RunComment) {
			fprintf(outFile, "[comment]\n%s\n[endcomment]\n", RunComment);
		}
		fputs("[sources]\n", outFile);
	}

	SOURCE *source = Sources, *dtsSource, *asource;
	int sourceIndex, fieldCount, dflag, uflag, azi, desCount = 0;
	char patName[MAX_STRING];

	for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {

		if (source->inScenario && (source->isDesired || source->isUndesired)) {

			if (source->isDesired) {
				dflag = 1;
				desCount++;
			} else {
				dflag = 0;
			}
			if (source->isUndesired) {
				uflag = 1;
			} else {
				uflag = 0;
			}

			if (source->isParent) {
				fieldCount = 0;
				for (dtsSource = source->dtsSources; dtsSource; dtsSource = dtsSource->next) {
					fieldCount++;
				}
			} else {
				fieldCount = 1;
			}

			if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
				if (dflag) {
					if (RECORD_TYPE_WL == source->recordType) {
						fprintf(outFile, "%d,%d,%d,%d,%d,%.2f,,%d,,,%d,%d,%d,%d,%s,%s\n", source->sourceKey, dflag,
							uflag, source->countryKey, source->serviceTypeKey, source->frequency, fieldCount,
							source->cellBounds.southLatIndex, source->cellBounds.eastLonIndex,
							source->cellBounds.northLatIndex, source->cellBounds.westLonIndex, source->fileNumber,
							source->callSign);
					} else {
						fprintf(outFile, "%d,%d,%d,%d,%d,%d,%.6f,%d,%d,%d,%d,%d,%d,%d,%s,%s\n", source->sourceKey,
							dflag, uflag, source->countryKey, source->serviceTypeKey, source->channel,
							source->serviceLevel, fieldCount, source->facility_id, source->siteNumber,
							source->cellBounds.southLatIndex, source->cellBounds.eastLonIndex,
							source->cellBounds.northLatIndex, source->cellBounds.westLonIndex, source->fileNumber,
							source->callSign);
					}
				} else {
					if (RECORD_TYPE_WL == source->recordType) {
						fprintf(outFile, "%d,%d,%d,%d,%d,%.2f,,%d,,,%s,%s\n", source->sourceKey, dflag, uflag,
							source->countryKey, source->serviceTypeKey, source->frequency, fieldCount,
							source->fileNumber, source->callSign);
					} else {
						fprintf(outFile, "%d,%d,%d,%d,%d,%d,%.6f,%d,%d,%d,%s,%s\n", source->sourceKey, dflag, uflag,
							source->countryKey, source->serviceTypeKey, source->channel, source->serviceLevel,
							fieldCount, source->facility_id, source->siteNumber, source->fileNumber, source->callSign);
					}
				}
			}

			if ((outFile = OutputFile[CELL_FILE_SUMMARY])) {
				if (RECORD_TYPE_WL == source->recordType) {
					fprintf(outFile, "%d,%d,%d,%d,%d,%.2f,,,%s,%s\n", source->sourceKey, dflag, uflag,
						source->countryKey, source->serviceTypeKey, source->frequency, source->fileNumber,
						source->callSign);
				} else {
					fprintf(outFile, "%d,%d,%d,%d,%d,%d,%d,%d,%s,%s\n", source->sourceKey, dflag, uflag,
						source->countryKey, source->serviceTypeKey, source->channel, source->facility_id,
						source->siteNumber, source->fileNumber, source->callSign);
				}
			}

			if ((outFile = OutputFile[CELL_FILE_CSV])) {
				if (RECORD_TYPE_WL == source->recordType) {
					fprintf(outFile, "%d,%d,%d,%d,%d,%.2f,,%d,,,%s,%s\n", source->sourceKey, dflag, uflag,
						source->countryKey, source->serviceTypeKey, source->frequency, fieldCount, source->fileNumber,
						source->callSign);
				} else {
					fprintf(outFile, "%d,%d,%d,%d,%d,%d,%.6f,%d,%d,%d,%s,%s\n", source->sourceKey, dflag, uflag,
						source->countryKey, source->serviceTypeKey, source->channel, source->serviceLevel, fieldCount,
						source->facility_id, source->siteNumber, source->fileNumber, source->callSign);
				}
			}

			if (OutputFlags[DERIVE_HPAT_FILE] && source->isDesired) {
				if (source->isParent) {
					asource = source->dtsSources;
				} else {
					asource = source;
					source->next = NULL;
				}
				while (asource) {
					if (asource->conthpat) {
						if (asource->siteNumber > 0) {
							snprintf(patName, MAX_STRING, "hpat-%s_%s_%s_%s_%d.csv", asource->callSign,
								channel_label(asource), asource->serviceCode, asource->status, asource->siteNumber);
						} else {
							snprintf(patName, MAX_STRING, "hpat-%s_%s_%s_%s.csv", asource->callSign,
								channel_label(asource), asource->serviceCode, asource->status);
						}
						outFile = open_file(patName);
						if (!outFile) return 1;
						for (azi = 0; azi < 360; azi++) {
							fprintf(outFile, "%d,%.4f\n", azi, pow(10., (asource->conthpat[azi] / 20.)));
						}
						fclose(outFile);
					}
					asource = asource->next;
				}
			}
		}
	}

	if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
		fputs("[endsources]\n", outFile);
	}

	if ((outFile = OutputFile[CELL_FILE_SUMMARY])) {
		fputs("[endsources]\n", outFile);
	}

	// Open map files as needed.

	if (MapOutputFlags[MAP_OUT_SHAPE] || MapOutputFlags[MAP_OUT_KML]) {
		err = do_open_mapfiles();
		if (err) return err;
	}

	// Open points file if needed.

	if (OutputFlags[POINTS_FILE]) {
		outFile = open_file(POINTS_FILE_NAME);
		if (!outFile) return 1;
		if (STUDY_MODE_POINTS == StudyMode) {
			fputs("PointName,CountryKey,Latitude,Longitude,ReceiveHeight,GroundHeight,LandCoverType,ClutterType\n",
				outFile);
		} else {
			if (GRID_TYPE_GLOBAL == Params.GridType) {
				fputs("LatIndex,LonIndex,CountryKey,PointKey,Latitude,Longitude,GroundHeight,Area,", outFile);
				fputs("Population,Households,LandCoverType,ClutterType\n", outFile);
			} else {
				fputs("SourceKey,LatIndex,LonIndex,CountryKey,Latitude,Longitude,GroundHeight,Area,", outFile);
				fputs("Population,Households,LandCoverType,ClutterType\n", outFile);
			}
		}
		OutputFile[POINTS_FILE] = outFile;
	}

	// Do the scenario run.  In grid mode, start image output if requested and there is only one desired station.  In
	// points mode, the report and CSV outputs are actually written during analysis in analyze_points() so those have
	// to be opened before the run starts.  In global mode, activate composite output if requested and the study type
	// is appropriate (only makes sense in general-pupose studies), regardless of how many desireds.  For consistency
	// in multi-scenario runs, the composite total shows even if it's just a repeat of totals for a single desired.

	if (STUDY_MODE_GRID == StudyMode) {

		if (MapOutputFlags[MAP_OUT_IMAGE] && (1 == desCount)) {
			do_start_image();
		}

		if (GRID_TYPE_GLOBAL == Params.GridType) {
			if (OutputFlags[COMPOSITE_COVERAGE] && ((STUDY_TYPE_TV == StudyType) || (STUDY_TYPE_FM == StudyType))) {
				DoComposite = 1;
			}
			err = do_global_run();
		} else {
			err = do_local_run();
		}

		if (OutputFile[IMAGE_FILE]) {
			do_end_image(err);
		}

	} else {

		if (OutputFlags[REPORT_FILE_DETAIL]) {
			outFile = open_file(REPORT_FILE_NAME);
			if (!outFile) return 1;
			write_report_preamble(REPORT_FILE_DETAIL, outFile, 1, 1);
			write_report(REPORT_FILE_DETAIL, OutputFlags[REPORT_FILE_DETAIL], outFile, 1);
			OutputFile[REPORT_FILE_DETAIL] = outFile;
		}

		if ((outFile = OutputFile[REPORT_FILE_SUMMARY])) {
			write_report(REPORT_FILE_SUMMARY, OutputFlags[REPORT_FILE_SUMMARY], outFile, ReportSumHeader);
			ReportSumHeader = 0;
		}

		if (OutputFlags[CSV_FILE_DETAIL]) {
			outFile = open_file(CSV_FILE_NAME);
			if (!outFile) return 1;
			write_csv_preamble(CSV_FILE_DETAIL, outFile, 1, 1);
			write_csv(CSV_FILE_DETAIL, OutputFlags[CSV_FILE_DETAIL], outFile, 1);
			OutputFile[CSV_FILE_DETAIL] = outFile;
		}

		if ((outFile = OutputFile[CSV_FILE_SUMMARY])) {
			write_csv(CSV_FILE_SUMMARY, OutputFlags[CSV_FILE_DETAIL], outFile, CSVSumHeader);
			CSVSumHeader = 0;
		}

		err = do_points_run();
	}

	if (err) {

		if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
			fputs("[abort]\n", outFile);
		}

		if ((outFile = OutputFile[CELL_FILE_SUMMARY])) {
			fputs("[abort]\n", outFile);
		}

		if ((outFile = OutputFile[CELL_FILE_PAIRSTUDY])) {
			fputs("[abort]\n", outFile);
		}

		return err;
	}

	// Final output to cell files as needed.

	if ((outFile = OutputFile[CELL_FILE_PAIRSTUDY])) {

		fputs("[coverage]\n", outFile);

		int countryIndex, countryKey;
		DES_TOTAL *dtot;

		source = Sources;
		for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {
			if (source->isDesired) {
				for (countryIndex = 0; countryIndex < MAX_COUNTRY; countryIndex++) {
					countryKey = countryIndex + 1;
					dtot = source->totals + countryIndex;
					if ((countryKey != source->countryKey) && (0. == dtot->contourArea)) {
						continue;
					}
					if (dtot->contourPop < 0) {
						fprintf(outFile, "%d,%d,%d,,,%.3f,%d,%.3f,%d\n", source->facility_id, source->channel,
							countryKey, dtot->serviceArea, dtot->servicePop, dtot->ixFreeArea, dtot->ixFreePop);
					} else {
						fprintf(outFile, "%d,%d,%d,%.3f,%d,%.3f,%d,%.3f,%d\n", source->facility_id, source->channel,
							countryKey, dtot->contourArea, dtot->contourPop, dtot->serviceArea, dtot->servicePop,
							dtot->ixFreeArea, dtot->ixFreePop);
					}
				}
			}
		}

		fputs("[endcoverage]\n[endcase]\n", outFile);
	}

	if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
		fputs("[endscenario]\n", outFile);
	}

	if ((outFile = OutputFile[CELL_FILE_SUMMARY])) {
		fputs("[endscenario]\n", outFile);
	}

	// Generate parameters output file as needed.

	if (OutputFlags[PARAMS_FILE]) {
		outFile = open_file(PARAMETER_FILE_NAME);
		if (!outFile) return 1;
		write_parameters(OutputFlags[PARAMS_FILE], outFile);
		fclose(outFile);
	}

	// In points mode, that's all.

	if (STUDY_MODE_POINTS == StudyMode) {
		return 0;
	}

	// In grid mode, write report and CSV files as needed.

	if (OutputFlags[REPORT_FILE_DETAIL]) {
		outFile = open_file(REPORT_FILE_NAME);
		if (!outFile) return 1;
		write_report_preamble(REPORT_FILE_DETAIL, outFile, 1, 1);
		write_report(REPORT_FILE_DETAIL, OutputFlags[REPORT_FILE_DETAIL], outFile, 1);
		fclose(outFile);
	}

	if ((outFile = OutputFile[REPORT_FILE_SUMMARY])) {
		write_report(REPORT_FILE_SUMMARY, OutputFlags[REPORT_FILE_SUMMARY], outFile, ReportSumHeader);
		ReportSumHeader = 0;
	}

	if (OutputFlags[CSV_FILE_DETAIL]) {
		outFile = open_file(CSV_FILE_NAME);
		if (!outFile) return 1;
		write_csv_preamble(CSV_FILE_DETAIL, outFile, 1, 1);
		write_csv(CSV_FILE_DETAIL, OutputFlags[CSV_FILE_DETAIL], outFile, 1);
		fclose(outFile);
	}

	if ((outFile = OutputFile[CSV_FILE_SUMMARY])) {
		write_csv(CSV_FILE_SUMMARY, OutputFlags[CSV_FILE_DETAIL], outFile, CSVSumHeader);
		CSVSumHeader = 0;
	}

	// Save totals for scenario pairing as needed, those have to be copied because the source may be studied again in
	// a later scenario.  Also check the entire list because the same scenario may be involved in more than one pair.
	// After any update if both scenarios in the pair have been run, update the percentages.

	int didA, didB, countryIndex, countryKey;
	DES_TOTAL *dtotA, *dtotB;

	SCENARIO_PAIR *thePair = ScenarioPairList;

	while (thePair) {

		didA = 0;
		didB = 0;

		if ((thePair->scenarioKeyA == ScenarioKey) && thePair->sourceA->inScenario && thePair->sourceA->isDesired) {
			memcpy(&(thePair->totalsA), &(thePair->sourceA->totals), (MAX_COUNTRY * sizeof(DES_TOTAL)));
			thePair->didStudyA = 1;
			didA = 1;
			didB = thePair->didStudyB;
		}

		if ((thePair->scenarioKeyB == ScenarioKey) && thePair->sourceB->inScenario && thePair->sourceB->isDesired) {
			memcpy(&(thePair->totalsB), &(thePair->sourceB->totals), (MAX_COUNTRY * sizeof(DES_TOTAL)));
			thePair->didStudyB = 1;
			didB = 1;
			didA = thePair->didStudyA;
		}

		if (didA && didB) {

			for (countryIndex = 0; countryIndex < MAX_COUNTRY; countryIndex++) {

				countryKey = countryIndex + 1;
				dtotA = thePair->totalsA + countryIndex;
				dtotB = thePair->totalsB + countryIndex;
				if ((0. == dtotA->contourArea) && (0. == dtotB->contourArea)) {
					continue;
				}

				if (dtotA->ixFreeArea > 0.) {
					thePair->areaPercent[countryIndex] =
						((dtotA->ixFreeArea - dtotB->ixFreeArea) / dtotA->ixFreeArea) * 100.;
				} else {
					thePair->areaPercent[countryIndex] = 0.;
				}

				if (dtotA->ixFreePop > 0) {
					thePair->popPercent[countryIndex] =
						((double)(dtotA->ixFreePop - dtotB->ixFreePop) / (double)dtotA->ixFreePop) * 100.;
				} else {
					thePair->popPercent[countryIndex] = 0.;
				}

				if (dtotA->ixFreeHouse > 0) {
					thePair->housePercent[countryIndex] =
						((double)(dtotA->ixFreeHouse - dtotB->ixFreeHouse) / (double)dtotA->ixFreeHouse) * 100.;
				} else {
					thePair->housePercent[countryIndex] = 0.;
				}
			}
		}

		thePair = thePair->next;
	}

	// Done.

	return 0;
}

// Open points and coverage map files as needed.

#define LEN_POINTNAME   40
#define LEN_COUNTRYKEY   1
#define LEN_SOURCEKEY    5
#define LEN_LATINDEX     7
#define LEN_LONINDEX     7
#define LEN_RESULT       2
#define LEN_IXBEARING    7
#define PREC_IXBEARING   2
#define LEN_ATTRDB       8
#define PREC_ATTRDB      3
#define LEN_LATLON      13
#define PREC_LATLON      8
#define LEN_AREA        10
#define PREC_AREA        6
#define LEN_POPULATION   8
#define LEN_HOUSEHOLDS   7
#define LEN_HEIGHT       7
#define PREC_HEIGHT      2
#define LEN_LANDCOVER    2
#define LEN_CLUTTER      2
#define LEN_SITENUM      3
#define LEN_DELTAT       7
#define PREC_DELTAT      1

#define CELL_MAX_ATTR  15
#define COV_MAX_ATTR   30

static int do_open_mapfiles() {

	// Possible fields for attribute list

	static SHAPEATTR attrPointName = {"POINTNAME", SHP_ATTR_CHAR, LEN_POINTNAME, 0};

	static SHAPEATTR attrCountryKey = {"COUNTRYKEY", SHP_ATTR_NUM, LEN_COUNTRYKEY, 0};
	static SHAPEATTR attrSourceKey = {"SOURCEKEY", SHP_ATTR_NUM, LEN_SOURCEKEY, 0};
	static SHAPEATTR attrLatIndex = {"LATINDEX", SHP_ATTR_NUM, LEN_LATINDEX, 0};
	static SHAPEATTR attrLonIndex = {"LONINDEX", SHP_ATTR_NUM, LEN_LONINDEX, 0};
	static SHAPEATTR attrResult = {"RESULT", SHP_ATTR_NUM, LEN_RESULT, 0};

	static SHAPEATTR attrIxBearing = {"IXBEARING", SHP_ATTR_NUM, LEN_IXBEARING, PREC_IXBEARING};
	static SHAPEATTR attrIxMargin = {"IXMARGIN", SHP_ATTR_NUM, LEN_ATTRDB, PREC_ATTRDB};

	static SHAPEATTR attrStudyLatitude = {"STUDYLAT", SHP_ATTR_NUM, LEN_LATLON, PREC_LATLON};
	static SHAPEATTR attrStudyLongitude = {"STUDYLON", SHP_ATTR_NUM, LEN_LATLON, PREC_LATLON};

	static SHAPEATTR attrArea = {"AREA", SHP_ATTR_NUM, LEN_AREA, PREC_AREA};
	static SHAPEATTR attrPopulation = {"POPULATION", SHP_ATTR_NUM, LEN_POPULATION, 0};
	static SHAPEATTR attrHouseholds = {"HOUSEHOLDS", SHP_ATTR_NUM, LEN_HOUSEHOLDS, 0};

	static SHAPEATTR attrReceiveHeight = {"RECEIVEHGT", SHP_ATTR_NUM, LEN_HEIGHT, PREC_HEIGHT};
	static SHAPEATTR attrGroundHeight = {"GROUNDHGT", SHP_ATTR_NUM, LEN_HEIGHT, PREC_HEIGHT};

	static SHAPEATTR attrLandCover = {"LANDCOVER", SHP_ATTR_NUM, LEN_LANDCOVER, 0};
	static SHAPEATTR attrClutterType = {"CLUTTER", SHP_ATTR_NUM, LEN_CLUTTER, 0};
	static SHAPEATTR attrClutterDb = {"CLUTTERDB", SHP_ATTR_NUM, LEN_ATTRDB, PREC_ATTRDB};

	static SHAPEATTR attrSiteNumber = {"SITENUM", SHP_ATTR_NUM, LEN_SITENUM, 0};
	static SHAPEATTR attrDesSignal = {"DSIGNAL", SHP_ATTR_NUM, LEN_ATTRDB, PREC_ATTRDB};
	static SHAPEATTR attrDesMargin = {"DMARGIN", SHP_ATTR_NUM, LEN_ATTRDB, PREC_ATTRDB};

	static SHAPEATTR attrDUSourceKey = {"USOURCEKEY", SHP_ATTR_NUM, LEN_SOURCEKEY, 0};
	static SHAPEATTR attrDU = {"DU", SHP_ATTR_NUM, LEN_ATTRDB, PREC_ATTRDB};
	static SHAPEATTR attrDUMargin = {"DUMARGIN", SHP_ATTR_NUM, LEN_ATTRDB, PREC_ATTRDB};

	static SHAPEATTR attrWLSignal = {"WLSIGNAL", SHP_ATTR_NUM, LEN_ATTRDB, PREC_ATTRDB};
	static SHAPEATTR attrWLDU = {"WLDU", SHP_ATTR_NUM, LEN_ATTRDB, PREC_ATTRDB};
	static SHAPEATTR attrWLDUMargin = {"WLDUMARGIN", SHP_ATTR_NUM, LEN_ATTRDB, PREC_ATTRDB};

	static SHAPEATTR attrSelfDU = {"SELFDU", SHP_ATTR_NUM, LEN_ATTRDB, PREC_ATTRDB};
	static SHAPEATTR attrSelfDUMargin = {"SELFDUMARG", SHP_ATTR_NUM, LEN_ATTRDB, PREC_ATTRDB};

	static SHAPEATTR attrMargin = {"MARGIN", SHP_ATTR_NUM, LEN_ATTRDB, PREC_ATTRDB};
	static SHAPEATTR attrMarginCause = {"CAUSE", SHP_ATTR_CHAR, 1, 0};

	static SHAPEATTR attrRamp = {"RAMPALPHA", SHP_ATTR_NUM, LEN_ATTRDB, PREC_ATTRDB};

#define SELFIX_NUM_ATTR  8

	static SHAPEATTR selfIXAttrs[SELFIX_NUM_ATTR] = {
		{"SOURCEKEY", SHP_ATTR_NUM, LEN_SOURCEKEY, 0},
		{"DSITENUM", SHP_ATTR_NUM, LEN_SITENUM, 0},
		{"DSIGNAL", SHP_ATTR_NUM, LEN_ATTRDB, PREC_ATTRDB},
		{"USIGNALRSS", SHP_ATTR_NUM, LEN_ATTRDB, PREC_ATTRDB},
		{"POPULATION", SHP_ATTR_NUM, LEN_POPULATION, 0},
		{"USITENUM", SHP_ATTR_NUM, LEN_SITENUM, 0},
		{"USIGNAL", SHP_ATTR_NUM, LEN_ATTRDB, PREC_ATTRDB},
		{"DELTAT", SHP_ATTR_NUM, LEN_DELTAT, PREC_DELTAT}
	};

	// Points file attribute list is different in local vs. global grid mode.  Points mode also has different formats.
	// Include study point coordinates if cell center is used for mapping location.

	SHAPEATTR cellAttrs[CELL_MAX_ATTR];
	int nCellAttrs = 0;

	if (STUDY_MODE_POINTS == StudyMode) {
		cellAttrs[nCellAttrs++] = attrPointName;
	}
	cellAttrs[nCellAttrs++] = attrCountryKey;
	if (STUDY_MODE_GRID == StudyMode) {
		if (GRID_TYPE_LOCAL == Params.GridType) {
			cellAttrs[nCellAttrs++] = attrSourceKey;
		}
		cellAttrs[nCellAttrs++] = attrLatIndex;
		cellAttrs[nCellAttrs++] = attrLonIndex;
		if (MapOutputFlags[MAP_OUT_CENTER]) {
			cellAttrs[nCellAttrs++] = attrStudyLatitude;
			cellAttrs[nCellAttrs++] = attrStudyLongitude;
		}
		cellAttrs[nCellAttrs++] = attrArea;
		cellAttrs[nCellAttrs++] = attrPopulation;
		cellAttrs[nCellAttrs++] = attrHouseholds;
	} else {
		cellAttrs[nCellAttrs++] = attrReceiveHeight;
	}
	cellAttrs[nCellAttrs++] = attrGroundHeight;
	if (Params.ApplyClutter) {
		cellAttrs[nCellAttrs++] = attrLandCover;
		cellAttrs[nCellAttrs++] = attrClutterType;
	}

	// If the global IXMarginSourceKey is set in grid mode, the coverage file has extra attributes containing the
	// bearing and the D/U margin from that specific interfering source.  The fields are populated only when the
	// undesired source causes unique interference at the point.  For any type of coverage output, additional
	// fields may be added per the output flags.

	SHAPEATTR covAttrs[COV_MAX_ATTR];
	int nCovAttrs = 0, resultAttr = -1;

	if (STUDY_MODE_GRID == StudyMode) {
		covAttrs[nCovAttrs++] = attrCountryKey;
		covAttrs[nCovAttrs++] = attrSourceKey;
		covAttrs[nCovAttrs++] = attrLatIndex;
		covAttrs[nCovAttrs++] = attrLonIndex;
		covAttrs[nCovAttrs] = attrResult;
		resultAttr = nCovAttrs++;
		if (IXMarginSourceKey > 0) {
			covAttrs[nCovAttrs++] = attrIxBearing;
			covAttrs[nCovAttrs++] = attrIxMargin;
		}
		if (MapOutputFlags[MAP_OUT_COORDS]) {
			covAttrs[nCovAttrs++] = attrStudyLatitude;
			covAttrs[nCovAttrs++] = attrStudyLongitude;
		}
		if (MapOutputFlags[MAP_OUT_AREAPOP]) {
			covAttrs[nCovAttrs++] = attrArea;
			covAttrs[nCovAttrs++] = attrPopulation;
			covAttrs[nCovAttrs++] = attrHouseholds;
		}
	} else {
		covAttrs[nCovAttrs++] = attrPointName;
		covAttrs[nCovAttrs++] = attrSourceKey;
		covAttrs[nCovAttrs] = attrResult;
		resultAttr = nCovAttrs++;
	}
	if (MapOutputFlags[MAP_OUT_CLUTTER] && Params.ApplyClutter) {
		covAttrs[nCovAttrs++] = attrLandCover;
		covAttrs[nCovAttrs++] = attrClutterType;
		covAttrs[nCovAttrs++] = attrClutterDb;
	}
	if (MapOutputFlags[MAP_OUT_DESINFO]) {
		covAttrs[nCovAttrs++] = attrSiteNumber;
		covAttrs[nCovAttrs++] = attrDesSignal;
		covAttrs[nCovAttrs++] = attrDesMargin;
	}
	if (MapOutputFlags[MAP_OUT_UNDINFO]) {
		covAttrs[nCovAttrs++] = attrDUSourceKey;
		covAttrs[nCovAttrs++] = attrDU;
		covAttrs[nCovAttrs++] = attrDUMargin;
	}
	if (MapOutputFlags[MAP_OUT_WLINFO] && (STUDY_TYPE_TV_OET74 == StudyType)) {
		covAttrs[nCovAttrs++] = attrWLSignal;
		covAttrs[nCovAttrs++] = attrWLDU;
		covAttrs[nCovAttrs++] = attrWLDUMargin;
	}
	if (MapOutputFlags[MAP_OUT_SELFIX] && Params.CheckSelfInterference) {
		covAttrs[nCovAttrs++] = attrSelfDU;
		covAttrs[nCovAttrs++] = attrSelfDUMargin;
	}
	if (MapOutputFlags[MAP_OUT_MARGIN]) {
		covAttrs[nCovAttrs++] = attrMargin;
		covAttrs[nCovAttrs++] = attrMarginCause;
	}
	if (MapOutputFlags[MAP_OUT_RAMP]) {
		covAttrs[nCovAttrs++] = attrRamp;
	}

	// Open files.

	MAPFILE *mapFile;

	if (MapOutputFlags[MAP_OUT_SHAPE]) {

		mapFile = open_mapfile(MAP_FILE_SHAPE, POINTS_MAPFILE_NAME, SHP_TYPE_POINT, nCellAttrs, cellAttrs, NULL, -1);
		if (!mapFile) return 1;
		MapOutputFile[MAP_OUT_SHAPE_POINTS] = mapFile;

		mapFile = open_mapfile(MAP_FILE_SHAPE, COVERAGE_MAPFILE_NAME, SHP_TYPE_POINT, nCovAttrs, covAttrs, NULL, -1);
		if (!mapFile) return 1;
		MapOutputFile[MAP_OUT_SHAPE_COV] = mapFile;

		if (MapOutputFlags[MAP_OUT_SELFIX] && Params.CheckSelfInterference) {
			mapFile = open_mapfile(MAP_FILE_SHAPE, SELFIX_MAPFILE_NAME, SHP_TYPE_POINT, SELFIX_NUM_ATTR, selfIXAttrs,
				NULL, -1);
			if (!mapFile) return 1;
			MapOutputFile[MAP_OUT_SHAPE_SELFIX] = mapFile;
		}
	}

	if (MapOutputFlags[MAP_OUT_KML]) {

		mapFile = open_mapfile(MAP_FILE_KML, POINTS_MAPFILE_NAME, SHP_TYPE_POINT, nCellAttrs, cellAttrs, ScenarioName,
			-1);
		if (!mapFile) return 1;
		MapOutputFile[MAP_OUT_KML_POINTS] = mapFile;

		mapFile = open_mapfile(MAP_FILE_KML, COVERAGE_MAPFILE_NAME, SHP_TYPE_POINT, nCovAttrs, covAttrs, ScenarioName,
			resultAttr);
		if (!mapFile) return 1;
		MapOutputFile[MAP_OUT_KML_COV] = mapFile;

		if (MapOutputFlags[MAP_OUT_SELFIX] && Params.CheckSelfInterference) {
			mapFile = open_mapfile(MAP_FILE_KML, SELFIX_MAPFILE_NAME, SHP_TYPE_POINT, SELFIX_NUM_ATTR, selfIXAttrs,
				ScenarioName, 5);
			if (!mapFile) return 1;
			MapOutputFile[MAP_OUT_KML_SELFIX] = mapFile;
		}
	}

	return 0;
}

// Start map image output.  The initial file is PostScript code, when the file is closed that code is rendered to the
// actual image file, see do_end_image().  Rendering requires a separately-installed version of the GhostScript
// package, so a "gs" command must be available.  To keep things simple, rather than trying to figure out where that
// command might be, the user is required to create a symlink to "gs" in TVStudy's lib directory.  If that link is
// not present, image output is disabled.  Note both this and do_end_image() do not report or attempt to recover from
// errors; the error is logged and the study continues without image output.

#define IMAGE_RES  300
#define MAX_LEVEL   20

static void do_start_image() {

	static int hasGS = -1;

	if (hasGS < 0) {
		char fname[MAX_STRING];
		snprintf(fname, MAX_STRING, "%s/gs", LIB_DIRECTORY_NAME);
		if (access(fname, X_OK)) {
			hasGS = 0;
		} else {
			hasGS = 1;
		}
	}

	if (!hasGS) return;

	// Read the color map for the quantity being presented from the root database and write PostScript to set up the
	// drawing environment.  During analysis each study point that defines the value to be mapped will cause the cell
	// region for that point to be filled with a color based on the color map.

	char query[MAX_QUERY];
	MYSQL_RES *myResult;
	my_ulonglong rowCount;
	MYSQL_ROW fields;

	snprintf(query, MAX_QUERY, "SELECT bg_color_r, bg_color_g, bg_color_b FROM %s.color_map WHERE color_map_key = %d;",
		DbName, MapOutputFlags[MAP_OUT_IMAGE]);
	if (mysql_query(MyConnection, query)) {
		log_db_error("Color map query failed, image output disabled (1)");
		hasGS = 0;
		return;
	}

	myResult = mysql_store_result(MyConnection);
	if (!myResult) {
		log_db_error("Color map query failed, image output disabled (2)");
		hasGS = 0;
		return;
	}

	rowCount = mysql_num_rows(myResult);
	if (!rowCount) {
		log_error("Color map not found for index %d, image output disabled", MapOutputFlags[MAP_OUT_IMAGE]);
		mysql_free_result(myResult);
		hasGS = 0;
		return;
	}

	fields = mysql_fetch_row(myResult);
	if (!fields) {
		log_db_error("Color map query failed, image output disabled (3)");
		mysql_free_result(myResult);
		hasGS = 0;
		return;
	}

	MapImageBackgroundR = atof(fields[0]) / 255.;
	MapImageBackgroundG = atof(fields[1]) / 255.;
	MapImageBackgroundB = atof(fields[2]) / 255.;

	mysql_free_result(myResult);

	snprintf(query, MAX_QUERY,
		"SELECT level, color_r, color_g, color_b FROM %s.color_map_data WHERE color_map_key = %d ORDER BY 1;",
		DbName, MapOutputFlags[MAP_OUT_IMAGE]);
	if (mysql_query(MyConnection, query)) {
		log_db_error("Color map query failed, image output disabled (4)");
		hasGS = 0;
		return;
	}

	myResult = mysql_store_result(MyConnection);
	if (!myResult) {
		log_db_error("Color map query failed, image output disabled (5)");
		hasGS = 0;
		return;
	}

	rowCount = mysql_num_rows(myResult);
	if (!rowCount) {
		log_error("Missing color map data for index %d, image output disabled", MapOutputFlags[MAP_OUT_IMAGE]);
		mysql_free_result(myResult);
		hasGS = 0;
		return;
	}

	MapImageColorCount = (int)rowCount;
	if (MapImageColorCount > MapImageMaxColorCount) {
		MapImageMaxColorCount = MapImageColorCount + 10;
		MapImageLevels = (double *)mem_realloc(MapImageLevels, (MapImageMaxColorCount * sizeof(double)));
		MapImageColorR = (double *)mem_realloc(MapImageColorR, (MapImageMaxColorCount * sizeof(double)));
		MapImageColorG = (double *)mem_realloc(MapImageColorG, (MapImageMaxColorCount * sizeof(double)));
		MapImageColorB = (double *)mem_realloc(MapImageColorB, (MapImageMaxColorCount * sizeof(double)));
	}

	int i;

	for (i = 0; i < MapImageColorCount; i++) {

		fields = mysql_fetch_row(myResult);
		if (!fields) {
			log_db_error("Color map query failed, image output disabled (6)");
			mysql_free_result(myResult);
			hasGS = 0;
			return;
		}

		MapImageLevels[i] = atof(fields[0]);
		MapImageColorR[i] = atof(fields[1]) / 255.;
		MapImageColorG[i] = atof(fields[2]) / 255.;
		MapImageColorB[i] = atof(fields[3]) / 255.;
	}

	mysql_free_result(myResult);

	char str[MAX_STRING];

	snprintf(str, MAX_STRING, "%s.ps", IMAGE_MAPFILE_NAME);
	FILE *outFile = open_file(str);
	if (!outFile) return;
	OutputFile[IMAGE_FILE] = outFile;

	fprintf(outFile, "%%!PS-Adobe\n");
	fputs(
	"/Draw {moveto dup 0 exch rlineto exch neg 0 rlineto neg 0 exch rlineto closepath setrgbcolor fill} bind def\n",
		outFile);
	fputs("0.1 0.1 scale\n", outFile);
}

// End image output.  If the error argument is true it indicates the run failed, just close the PostScript file and
// return.  Otherwise use the "gs" command to render to an image file.  If rendering succeeds write a KML file that
// loads the image as an overlay, and delete the PostScript file.

static void do_end_image(int err) {

	FILE *outFile = OutputFile[IMAGE_FILE];
	if (!outFile) return;

	fputs("showpage\n", outFile);
	fclose(outFile);
	OutputFile[IMAGE_FILE] = NULL;

	if (err) return;

	log_message("Rendering image output");

	// Open the image output file and immediately close it to put it in the pending files list if needed.

	char str[MAX_STRING];

	snprintf(str, MAX_STRING, "%s.png", IMAGE_MAPFILE_NAME);
	outFile = open_file(str);
	if (!outFile) return;
	fclose(outFile);

	// Construct the argument list for the "gs" command.  This does a direct fork-exec.

	int width = ((CellBounds.westLonIndex - CellBounds.eastLonIndex) + CellLonSize) / 10;
	int height = ((CellBounds.northLatIndex - CellBounds.southLatIndex) + CellLatSize) / 10;

	char *path = get_file_path();
	if (!path) return;
	int len = strlen(path) + strlen(IMAGE_MAPFILE_NAME) + 25;

	char *args[12];

	args[0] = (char *)mem_alloc(20);
	snprintf(args[0], 20, "%s/gs", LIB_DIRECTORY_NAME);
	args[1] = "-q";
	args[2] = "-dNOPAUSE";
	args[3] = "-dBATCH";
	args[4] = "-sDEVICE=pngalpha";
	args[5] = "-dGraphicsAlphaBits=1";
	args[6] = (char *)mem_alloc(10);
	snprintf(args[6], 10, "-r%d", IMAGE_RES);
	args[7] = (char *)mem_alloc(30);
	snprintf(args[7], 30, "-dDEVICEWIDTHPOINTS=%d", width);
	args[8] = (char *)mem_alloc(30);
	snprintf(args[8], 30, "-dDEVICEHEIGHTPOINTS=%d", height);
	args[9] = (char *)mem_alloc(len);
	snprintf(args[9], len, "-sOutputFile=%s/%s.png", path, IMAGE_MAPFILE_NAME);
	args[10] = (char *)mem_alloc(len);
	snprintf(args[10], len, "%s/%s.ps", path, IMAGE_MAPFILE_NAME);
	args[11] = NULL;

	int cpid;
	if((cpid=_spawnvp(P_NOWAIT, args[0], (const char* const*)args))<0)//if (0 == (cpid = fork())) 
	{
		//execvp(args[0], args);
		_exit(1);
	}
	
	mem_free(args[0]);
	mem_free(args[6]);
	mem_free(args[7]);
	mem_free(args[8]);
	mem_free(args[9]);

	int status = 0;
	while ((cpid > 0) && (waitpid(cpid, &status, 0) != cpid)) {
		if (EINTR != errno) cpid = -1;
	}

	if ((cpid > 0) && WIFEXITED(status) && (0 == WEXITSTATUS(status))) {

		unlink(args[10]);

		snprintf(str, MAX_STRING, "%s.kml", IMAGE_MAPFILE_NAME);
		outFile = open_file(str);

		if (outFile) {

			double slat = (double)CellBounds.southLatIndex / 3600.;
			double nlat = (double)(CellBounds.northLatIndex + CellLatSize) / 3600.;
			double elon = (double)CellBounds.eastLonIndex / 3600.;
			double wlon = (double)(CellBounds.westLonIndex + CellLonSize) / 3600.;

			fputs("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n", outFile);
			fputs("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n", outFile);
			fprintf(outFile, "<GroundOverlay>\n<name>%s</name>\n", ScenarioName);
			fprintf(outFile, "<color>7fffffff</color>\n<Icon><href>%s.png</href></Icon>\n", IMAGE_MAPFILE_NAME);
			fprintf(outFile, "<LatLonBox>\n<north>%.8f</north>\n<south>%.8f</south>\n", nlat, slat);
			fprintf(outFile, "<east>-%.8f</east>\n<west>-%.8f</west>\n</LatLonBox>\n", elon, wlon);
			fputs("</GroundOverlay>\n</kml>\n", outFile);

			fclose(outFile);
		}
	}

	mem_free(args[10]);
}

// Do scenario run for a global grid.  In this mode, the cell grid is defined independent of any source coverage area
// so the cells can be shared between all sources, meaning where desired coverages overlap the study is using the same
// cells and the undesired field strength calculations can be shared between desired sources.  In this type of study,
// the grid can be set up to encompass all sources being studied and all calculations done simultaneously.  However a
// limit has to be set on the grid size to avoid unreasonable memory allocation demands, also when just a few widely-
// separated sources are being studied a single large grid that is mostly empty is inefficient.  So this is a multi-
// pass process that dynamically groups sources onto multiple grids as needed.  The limit is based on the size of the
// terrain data cache, the goal being that all terrain needed for any one grid can be cached, the limit is determined
// by a function in the terrain module, see terrain.c.

static int do_global_run() {

	SOURCE *source, *sourceList, **sourceLink;
	TVPOINT **pointPtr, *point;
	FIELD *field;
	int err, sourceIndex, gridCellIndex, cacheCount, calcCount, doneCount, showPcnt, calcPcnt, cellLonSize,
		tempCellLonSize, gridCount, gridCountSum, loop, runCount;
	INDEX_BOUNDS cellBounds, tempCellBounds;
	char mesg[MAX_STRING];
	FILE *outFile;

	// Get the grid count limit used to segment study grids, see below, however if this returns -1 it means there is
	// not enough memory for terrain caching to initialize and all terrain lookups would fail, so in that case abort.

	int maxGridCount = get_max_grid_count(Params.CellSize, Params.KilometersPerDegree);
	if (maxGridCount < 0) {
		log_error("Insufficient memory available for terrain caching");
		return -1;
	}

	// If any output options requiring the points map are enabled, set up the map.  This is a grid layout analogous to
	// the main study grid in Cells, see grid_setup() for details, but it is always the entire grid encompassing all
	// desired sources in the scenario regardless of size.  As mentioned above the study grid may be segmented due to
	// memory limits so Cells may have multiple different layouts during the run, possibly overlapping.  The points map
	// covers all of those, and is used to track which points have been output to files to avoid duplication, see
	// write_points(), as well as to accumulate composite coverage output if enabled.

	if (MapOutputFlags[MAP_OUT_SHAPE] || MapOutputFlags[MAP_OUT_KML] || OutputFlags[POINTS_FILE] || DoComposite) {

		initialize_bounds(&PointMapBounds);
		PointMapLonSize = 999999;

		source = Sources;
		for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {
			if (source->isDesired) {
				extend_bounds_bounds(&PointMapBounds, &(source->cellBounds));
				if (source->cellLonSize < PointMapLonSize) {
					PointMapLonSize = source->cellLonSize;
				}
			}
		}

		PointMapBounds.southLatIndex = (int)floor((double)PointMapBounds.southLatIndex / (double)CellLatSize) *
			CellLatSize;
		PointMapBounds.eastLonIndex = (int)floor((double)PointMapBounds.eastLonIndex / (double)PointMapLonSize) *
			PointMapLonSize;

		PointMapLatCount = (((PointMapBounds.northLatIndex - 1) - PointMapBounds.southLatIndex) / CellLatSize) + 1;
		PointMapLonCount = (((PointMapBounds.westLonIndex - 1) - PointMapBounds.eastLonIndex) / PointMapLonSize) + 1;

		PointMapBounds.northLatIndex = PointMapBounds.southLatIndex + (PointMapLatCount * CellLatSize);
		PointMapBounds.westLonIndex = PointMapBounds.eastLonIndex + (PointMapLonCount * PointMapLonSize);

		PointMapCount = PointMapLatCount * PointMapLonCount;
		PointMap = (unsigned char *)mem_zalloc(PointMapCount * sizeof(unsigned char));

		PointMapLonSizes = (int *)mem_zalloc(PointMapLatCount * sizeof(int));
		PointMapEastLonIndex = (int *)mem_zalloc(PointMapLatCount * sizeof(int));

		int latMapIndex, lonSize, latIndex = PointMapBounds.southLatIndex;

		for (latMapIndex = 0; latMapIndex < PointMapLatCount; latMapIndex++, latIndex += CellLatSize) {
			lonSize = global_lon_size(latIndex);
			PointMapLonSizes[latMapIndex] = lonSize;
			PointMapEastLonIndex[latMapIndex] = (int)floor((double)PointMapBounds.eastLonIndex / (double)lonSize) *
				lonSize;
		}
	}

	// On each pass, loop repeatedly over the full source list looking for sources not yet studied and group sources
	// with overlapping coverage that would not make too large a grid.  Any single source will be studied regardless
	// of the size of it's isolated grid.

	while (1) {

		sourceList = NULL;
		sourceLink = &sourceList;
		initialize_bounds(&cellBounds);
		cellLonSize = 999999;
		gridCountSum = 0;
		runCount = 0;

		do {

			loop = 0;

			source = Sources;
			for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {

				if (source->isDesired > 0) {

					if (!sourceList) {

						tempCellBounds = source->cellBounds;
						tempCellLonSize = source->cellLonSize;
						source->isDesired = -1;

					} else {

						tempCellBounds = cellBounds;
						tempCellLonSize = cellLonSize;
						if (overlaps_bounds(&tempCellBounds, &(source->cellBounds))) {
							extend_bounds_bounds(&tempCellBounds, &(source->cellBounds));
							if (source->cellLonSize < tempCellLonSize) {
								tempCellLonSize = source->cellLonSize;
							}
							gridCount =
								((tempCellBounds.northLatIndex - tempCellBounds.southLatIndex) / CellLatSize) *
								((tempCellBounds.westLonIndex - tempCellBounds.eastLonIndex) / tempCellLonSize);
							if (gridCount < maxGridCount) {
								source->isDesired = -1;
							}
						}
					}

					if (source->isDesired < 0) {

						runCount++;

						*sourceLink = source;
						sourceLink = &(source->next);
						source->next = NULL;

						cellBounds = tempCellBounds;
						cellLonSize = tempCellLonSize;

						// Sum up the approximate cell count in each source's grid for those that won't load from
						// cache, this is used to decide how to do the population query below.

						if (!source->hasDesiredCache) {
							gridCountSum +=
								((source->cellBounds.northLatIndex - source->cellBounds.southLatIndex) /
									CellLatSize) *
								((source->cellBounds.westLonIndex - source->cellBounds.eastLonIndex) /
									source->cellLonSize);
						}

						loop = 1;
					}
				}
			}

		} while (loop);

		// If no more sources to study, done.

		if (!sourceList) {
			break;
		}

		snprintf(mesg, MAX_STRING, "%d", runCount);
		status_message(STATUS_KEY_RUNCOUNT, mesg);

		// Set up the grid for this group of sources.

		log_message("Building study grid");

		if (Debug) {
			log_message("Grid setup for %d %d %d %d", cellBounds.southLatIndex, cellBounds.northLatIndex,
				cellBounds.eastLonIndex, cellBounds.westLonIndex);
		}
		err = grid_setup(cellBounds, cellLonSize);
		if (err) return err;
		if (Debug) {
			log_message("Grid size %d %d %d", CellLatSize, CellLonSize, GridCount);
		}

		if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
			fprintf(outFile, "[grid]\n%d,%d,%d,%d\n", CellBounds.southLatIndex, CellBounds.eastLonIndex,
				CellBounds.northLatIndex, CellBounds.westLonIndex);
		}

		if ((outFile = OutputFile[CELL_FILE_SUMMARY])) {
			fprintf(outFile, "[grid]\n%d,%d,%d,%d\n", CellBounds.southLatIndex, CellBounds.eastLonIndex,
				CellBounds.northLatIndex, CellBounds.westLonIndex);
		}

		if ((outFile = OutputFile[IMAGE_FILE])) {
			fprintf(outFile, "%d -%d translate\n", (CellBounds.westLonIndex + CellLonSize),
				CellBounds.southLatIndex);
		}

		// Compare the combined cell count of all the sources that will not be loaded from cache to the count of the
		// entire grid.  If the combined count is larger, it is more efficient to do a single whole-grid population
		// query rather than having cell_setup() do separate overlapping queries for each source.

		if (gridCountSum > GridCount) {
			err = load_grid_population(NULL);
			if (err) return err;
		}

		// Set caching flags (cleared as caches are loaded, to prevent multiple cache loads of the same source), do
		// cell setup for sources.

		source = Sources;
		for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {
			if (source->inScenario) {
				source->dcache = 1;
				source->ucache = 1;
			}
		}

		cacheCount = 0;

		for (source = sourceList; source; source = source->next) {

			if (Debug) {
				log_message("Cell setup for sourceKey=%d %d %d %d %d", source->sourceKey,
					source->cellBounds.southLatIndex, source->cellBounds.northLatIndex,
					source->cellBounds.eastLonIndex, source->cellBounds.westLonIndex);
			}

			// The last argument is a flag indicating that Census points within each study point should be re-queried
			// for study points that are loaded from cache (the Census points are not cached, have to fix that soon).
			// Currently the Census points list is only needed for one format of the detail cell file.

			err = cell_setup(source, &cacheCount, (CELL_DETAIL_CENPT == OutputFlags[CELL_FILE_DETAIL]));
			if (err) return err;
		}

		if (cacheCount) {
			log_message("Read %d fields from cache", cacheCount);
		}

		// Clear caching flags (now they will be set as new fields are calculated to indicate which sources need data
		// written to cache), traverse the grid and calculate all fields needed.  That could be none, if everything
		// loaded from cache.

		source = Sources;
		for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {
			if (source->inScenario) {
				source->dcache = 0;
				source->ucache = 0;
				initialize_bounds(&(source->ugridIndex));
			}
		}

		// Check if any fields need to be calculated.

		calcCount = 0;
		pointPtr = Cells;
		for (gridCellIndex = 0; gridCellIndex < GridCount; gridCellIndex++, pointPtr++) {
			for (point = *pointPtr; point; point = point->next) {
				for (field = point->fields; field; field = field->next) {
					if (field->status < 0) {
						calcCount++;
					}
				}
			}
		}

		// Calculate new fields, periodically write to cache.  Ignore any errors from the writes.

		if (calcCount) {

			log_message("Calculating %d new fields", calcCount);
			doneCount = 0;

			int nCalc = calcCount;
			if (nCalc > CALC_CACHE_COUNT) {
				nCalc = CALC_CACHE_COUNT;
				showPcnt = 1;
			} else {
				showPcnt = 0;
			}
			hb_log_begin(nCalc);

			pointPtr = Cells;
			for (gridCellIndex = 0; gridCellIndex < GridCount; gridCellIndex++, pointPtr++) {
				for (point = *pointPtr; point; point = point->next) {
					for (field = point->fields; field; field = field->next) {
						if (field->status < 0) {

							hb_log_tick();
							err = project_field(point, field);
							if (err) return err;
							doneCount++;

							if (--nCalc == 0) {

								hb_log_end();
								if (showPcnt) {
									calcPcnt = (int)(((double)doneCount / (double)calcCount) * 100.);
									log_message("%d%% done, updating caches", calcPcnt);
									nCalc = calcCount - doneCount;
									if (nCalc > CALC_CACHE_COUNT) {
										nCalc = CALC_CACHE_COUNT;
									}
								} else {
									log_message("Updating caches");
								}
								source = Sources;
								for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {
									if (source->inScenario) {
										hb_log();
										if (source->dcache) {
											write_cell_cache(source, CACHE_DES, 0, NULL);
											source->dcache = 0;
										}
										if (source->ucache && CacheUndesired) {
											write_cell_cache(source, CACHE_UND, 0, NULL);
											source->ucache = 0;
											initialize_bounds(&(source->ugridIndex));
										}
									}
								}

								if (nCalc > 0) {
									hb_log_begin(nCalc);
								}
							}
						}
					}
				}
			}
		}

		// Write points to files as needed.

		write_points(MapOutputFile[MAP_OUT_SHAPE_POINTS], MapOutputFile[MAP_OUT_KML_POINTS], OutputFile[POINTS_FILE]);

		// Determine final coverage for all sources, final output to cell files.

		err = analyze_grid(GridIndex);
		if (err) return err;

		if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
			fputs("[endgrid]\n", outFile);
		}

		if ((outFile = OutputFile[CELL_FILE_SUMMARY])) {
			fputs("[endgrid]\n", outFile);
		}
	}

	// Output composite coverage map file as needed.

	if (DoComposite && (MapOutputFlags[MAP_OUT_SHAPE] || MapOutputFlags[MAP_OUT_KML])) {
		err = do_write_compcov();
	}

	return err;
}

// Write composite coverage results to map files as needed.

#define COMPCOV_NATTR  4

static int do_write_compcov() {

	static SHAPEATTR attrs[COMPCOV_NATTR] = {
		{"COUNTRYKEY", SHP_ATTR_NUM, LEN_COUNTRYKEY, 0},
		{"LATINDEX", SHP_ATTR_NUM, LEN_LATINDEX, 0},
		{"LONINDEX", SHP_ATTR_NUM, LEN_LONINDEX, 0},
		{"RESULT", SHP_ATTR_NUM, LEN_RESULT, 0}
	};

	MAPFILE *mapFileShp = NULL, *mapFileKML = NULL;

	if (MapOutputFlags[MAP_OUT_SHAPE]) {
		mapFileShp = open_mapfile(MAP_FILE_SHAPE, COMPCOV_MAPFILE_NAME, SHP_TYPE_POINT, COMPCOV_NATTR, attrs, NULL,
			-1);
		if (!mapFileShp) {
			return 1;
		}
	}

	if (MapOutputFlags[MAP_OUT_KML]) {
		mapFileKML = open_mapfile(MAP_FILE_KML, COMPCOV_MAPFILE_NAME, SHP_TYPE_POINT, COMPCOV_NATTR, attrs,
			ScenarioName, 3);
		if (!mapFileKML) {
			if (mapFileShp) {
				close_mapfile(mapFileShp);
			}
			return 1;
		}
	}

	static char attrCntKey[LEN_COUNTRYKEY + 1], attrLatIdx[LEN_LATINDEX + 1], attrLonIdx[LEN_LONINDEX + 1];

	attrCntKey[0] = '\0';
	attrLatIdx[0] = '\0';
	attrLonIdx[0] = '\0';

	char *attrData[COMPCOV_NATTR];

	attrData[0] = attrCntKey;
	attrData[1] = attrLatIdx;
	attrData[2] = attrLonIdx;

	int latIndex = PointMapBounds.southLatIndex, lonIndex, lonSize, lonCount, latMapIndex, lonMapIndex, pointMapIndex,
		countryKey, shift, result;
	unsigned char mask;
	double lat, lon, halfCellLat = (double)CellLatSize / 7200., halfCellLon;

	for (latMapIndex = 0; latMapIndex < PointMapLatCount; latMapIndex++, latIndex += CellLatSize) {

		snprintf(attrLatIdx, (LEN_LATINDEX + 1), "%d", latIndex);

		lonIndex = PointMapEastLonIndex[latMapIndex];
		lonSize = PointMapLonSizes[latMapIndex];
		lonCount = (((PointMapBounds.westLonIndex - 1) - lonIndex) / lonSize) + 1;

		halfCellLon = (double)lonSize / 7200.;

		for (lonMapIndex = 0; lonMapIndex < lonCount; lonMapIndex++, lonIndex += lonSize) {

			snprintf(attrLonIdx, (LEN_LONINDEX + 1), "%d", lonIndex);

			pointMapIndex = (latMapIndex * PointMapLonCount) + lonMapIndex;

			lat = ((double)latIndex / 3600.) + halfCellLat;
			lon = ((double)lonIndex / 3600.) + halfCellLon;

			for (countryKey = 1; countryKey <= MAX_COUNTRY; countryKey++) {

				shift = PointMapBitShift[countryKey - 1];

				mask = 0x03 << shift;
				result = (PointMap[pointMapIndex] & mask) >> shift;

				if ((RESULT_UNKNOWN == result) || (MapOutputFlags[MAP_OUT_NOSERV] && (RESULT_NOSERVICE == result)) ||
						(MapOutputFlags[MAP_OUT_NOPOP] && !(PointMap[pointMapIndex] & POINTMAP_HASPOP_BIT))) {
					continue;
				}

				snprintf(attrCntKey, (LEN_COUNTRYKEY + 1), "%d", countryKey);

				switch (result) {
					case RESULT_COVERAGE: {
						attrData[3] = "1";
						break;
					}
					case RESULT_INTERFERE: {
						attrData[3] = "2";
						break;
					}
					case RESULT_NOSERVICE: {
						attrData[3] = "3";
						break;
					}
				}

				if (mapFileShp) {
					write_shape(mapFileShp, lat, lon, NULL, 0, NULL, attrData);
				}

				if (mapFileKML) {
					write_shape(mapFileKML, lat, lon, NULL, 0, NULL, attrData);
				}
			}
		}
	}

	if (mapFileShp) {
		close_mapfile(mapFileShp);
	}
	if (mapFileKML) {
		close_mapfile(mapFileKML);
	}

	return 0;
}

// Do scenario run for a local grid.  In this mode, each desired source is studied independently on it's own cell grid,
// the grid is defined based on the bounds of the individual coverage contour and so the cells are never the same even
// where there is coverage overlap between sources.  Undesired signal projections thus cannot be shared with other
// desired source studies.  In this type of study, simply loop over the sources and study each one on it's own grid.
// Otherwise the logic is similar to global mode above; see that code for detailed comments.

static int do_local_run() {

	SOURCE *source, *usource;
	UNDESIRED *undesireds;
	TVPOINT **pointPtr, *point;
	FIELD *field;
	int err, sourceIndex, undesiredIndex, undesiredCount, gridCellIndex, cacheCount, calcCount, doneCount, showPcnt,
		calcPcnt;
	FILE *outFile;

	source = Sources;
	for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {

		if (source->isDesired) {

			undesireds = source->undesireds;
			undesiredCount = source->undesiredCount;

			source->dcache = 1;
			source->ucache = 1;
			for (undesiredIndex = 0; undesiredIndex < undesiredCount; undesiredIndex++) {
				usource = SourceKeyIndex[undesireds[undesiredIndex].sourceKey];
				if (!usource) {
					log_error("Source structure index is corrupted");
					exit(1);
				}
				usource->ucache = 1;
			}

			log_message("Building study grid");

			if (Debug) {
				log_message("Grid and cell setup for sourceKey=%d %d %d %d %d", source->sourceKey,
					source->cellBounds.southLatIndex, source->cellBounds.northLatIndex,
					source->cellBounds.eastLonIndex, source->cellBounds.westLonIndex);
			}

			status_message(STATUS_KEY_RUNCOUNT, "1");

			err = grid_setup(source->cellBounds, source->cellLonSize);
			if (err) return err;
			if (Debug) {
				log_message("Grid size %d %d %d", CellLatSize, CellLonSize, GridCount);
			}

			if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
				fprintf(outFile, "[grid]\n%d,%d,%d,%d\n", CellBounds.southLatIndex, CellBounds.eastLonIndex,
					CellBounds.northLatIndex, CellBounds.westLonIndex);
			}

			if ((outFile = OutputFile[CELL_FILE_SUMMARY])) {
				fprintf(outFile, "[grid]\n%d,%d,%d,%d\n", CellBounds.southLatIndex, CellBounds.eastLonIndex,
					CellBounds.northLatIndex, CellBounds.westLonIndex);
			}

			if ((outFile = OutputFile[IMAGE_FILE])) {
				fprintf(outFile, "%d -%d translate\n", (CellBounds.westLonIndex - CellLonSize),
					(CellBounds.southLatIndex - CellLatSize));
			}

			cacheCount = 0;

			err = cell_setup(source, &cacheCount, (CELL_DETAIL_CENPT == OutputFlags[CELL_FILE_DETAIL]));
			if (err) return err;

			if (cacheCount) {
				log_message("Read %d fields from cache", cacheCount);
			}

			source->dcache = 0;
			source->ucache = 0;
			for (undesiredIndex = 0; undesiredIndex < undesiredCount; undesiredIndex++) {
				usource = SourceKeyIndex[undesireds[undesiredIndex].sourceKey];
				if (!usource) {
					log_error("Source structure index is corrupted");
					exit(1);
				}
				usource->ucache = 0;
				initialize_bounds(&(usource->ugridIndex));
			}

			calcCount = 0;
			pointPtr = Cells;
			for (gridCellIndex = 0; gridCellIndex < GridCount; gridCellIndex++, pointPtr++) {
				for (point = *pointPtr; point; point = point->next) {
					for (field = point->fields; field; field = field->next) {
						if (field->status < 0) {
							calcCount++;
						}
					}
				}
			}

			if (calcCount) {

				log_message("Calculating %d new fields", calcCount);
				doneCount = 0;

				int nCalc = calcCount;
				if (nCalc > CALC_CACHE_COUNT) {
					nCalc = CALC_CACHE_COUNT;
					showPcnt = 1;
				} else {
					showPcnt = 0;
				}
				hb_log_begin(nCalc);

				pointPtr = Cells;
				for (gridCellIndex = 0; gridCellIndex < GridCount; gridCellIndex++, pointPtr++) {
					for (point = *pointPtr; point; point = point->next) {
						for (field = point->fields; field; field = field->next) {
							if (field->status < 0) {

								hb_log_tick();
								err = project_field(point, field);
								if (err) return err;
								doneCount++;

								if (--nCalc == 0) {

									hb_log_end();
									if (showPcnt) {
										calcPcnt = (int)(((double)doneCount / (double)calcCount) * 100.);
										log_message("%d%% done, updating caches", calcPcnt);
										nCalc = calcCount - doneCount;
										if (nCalc > CALC_CACHE_COUNT) {
											nCalc = CALC_CACHE_COUNT;
										}
									} else {
										log_message("Updating caches");
									}
									if (source->dcache) {
										write_cell_cache(source, CACHE_DES, 0, NULL);
										source->dcache = 0;
									}
									for (undesiredIndex = 0; undesiredIndex < undesiredCount; undesiredIndex++) {
										usource = SourceKeyIndex[undesireds[undesiredIndex].sourceKey];
										if (!usource) {
											log_error("Source structure index is corrupted");
											exit(1);
										}
										if (usource->ucache && CacheUndesired) {
											write_cell_cache(usource, CACHE_UND, source->sourceKey,
												&(undesireds[undesiredIndex].ucacheChecksum));
											usource->ucache = 0;
											initialize_bounds(&(usource->ugridIndex));
										}
									}

									// Must do a separate check for caching a source as an undesired to itself.  When
									// DTS self-interference is being analyzed the desired source also has undesired
									// fields calculated, but it does not appear in it's own list of undesireds so the
									// loop above does not handle this case.

									if (source->ucache && CacheUndesired) {
										write_cell_cache(source, CACHE_UND, source->sourceKey,
											&(source->ucacheChecksum));
									}

									if (nCalc > 0) {
										hb_log_begin(nCalc);
									}
								}
							}
						}
					}
				}
			}

			write_points(MapOutputFile[MAP_OUT_SHAPE_POINTS], MapOutputFile[MAP_OUT_KML_POINTS], 
				OutputFile[POINTS_FILE]);

			err = analyze_source(source);
			if (err) return err;

			if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
				fputs("[endgrid]\n", outFile);
			}

			if ((outFile = OutputFile[CELL_FILE_SUMMARY])) {
				fputs("[endgrid]\n", outFile);
			}
		}
	}

	return 0;
}

// Do scenario run for points mode.  This is fundamentally different than the other modes because the focus is the
// individual study points, not the desired stations.  Service and interference conditions for all desired stations,
// within a maximum distance limit, analyzed at each study point regardless of whether that point is or is not in the
// desired station's service area.  The set of points is arbitrarily defined by user input.

static int do_points_run() {

	SOURCE *source;
	TVPOINT *point;
	FIELD *field;
	int err, sourceIndex, calcCount;
	FILE *outFile;

	clear_points();

	source = Sources;
	for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {
		if (source->isDesired) {
			err = points_setup(source);
			if (err) return err;
		}
	}

	if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
		fputs("[points]\n", outFile);
	}

	if ((outFile = OutputFile[CELL_FILE_SUMMARY])) {
		fputs("[points]\n", outFile);
	}

	calcCount = 0;
	for (point = Points; point; point = point->next) {
		for (field = point->fields; field; field = field->next) {
			if (field->status < 0) {
				calcCount++;
			}
		}
	}

	if (calcCount) {
		log_message("Calculating %d new fields", calcCount);
		hb_log_begin(calcCount);
		for (point = Points; point; point = point->next) {
			for (field = point->fields; field; field = field->next) {
				if (field->status < 0) {
					hb_log_tick();
					err = project_field(point, field);
					if (err) return err;

					if (OutputFlags[POINT_FILE_PROF] && !field->a.isUndesired && ProfileCount) {

						char profName[MAX_STRING];

						source = SourceKeyIndex[field->sourceKey];
						snprintf(profName, MAX_STRING, "profile-%s_%s_%s_%s-%s.csv", source->callSign,
							channel_label(source), source->serviceCode, source->status,
							PointInfos[point->a.pointIndex].pointName);
						outFile = open_file(profName);
						if (!outFile) return 1;

						int pointIndex;
						for (pointIndex = 0; pointIndex < ProfileCount; pointIndex++) {
							fprintf(outFile, "%.2f,%.2f\n", ((double)pointIndex / ProfilePpk), Profile[pointIndex]);
						}

						fclose(outFile);
					}
				}
			}
		}
		hb_log_end();
	}

	write_points(MapOutputFile[MAP_OUT_SHAPE_POINTS], MapOutputFile[MAP_OUT_KML_POINTS], OutputFile[POINTS_FILE]);

	analyze_points(Points, NULL);

	if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
		fputs("[endpoints]\n", outFile);
	}

	if ((outFile = OutputFile[CELL_FILE_SUMMARY])) {
		fputs("[endpoints]\n", outFile);
	}

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Write studied points from the current grid or points list to the points map files and/or CSV file.  Include any
// point with a desired field, regardless of calculation status.  In local grid mode each record includes the source
// key for the source being studied, that can be obtained from the field structure because there will only be one
// desired field at any study point.  In global grid mode, this may be called multiple times with separate but often
// overlapping sub-grids.  In that case the point map is used to track which points have previously been output to
// avoid duplication.  Also in global grid mode the pointKey that would be assigned in a pair study output (see
// pair_study_post.c) is included in the CSV file.  However that may be -1 if the key cannot be defined for a point.
// Pair study grid range and cell size are restricted and the pointKey algorithm is only valid over those restricted
// conditions.  In points mode the cell information is undefined so each record has just the point name and country
// key, in addition to coordinates.

// Arguments:

//   mapPointsShp  The shapefile map file, or NULL.
//   mapPointsKML  The KML map file, or NULL.
//   pointsFile    The CSV points file, or NULL.

static void write_points(MAPFILE *mapPointsShp, MAPFILE *mapPointsKML, FILE *pointsFile) {

	if (!mapPointsShp && !mapPointsKML && !pointsFile) {
		return;
	}

	static char attrPtName[LEN_POINTNAME + 1], attrCntKey[LEN_COUNTRYKEY + 1], attrSrcKey[LEN_SOURCEKEY + 1],
		attrLatIdx[LEN_LATINDEX + 1], attrLonIdx[LEN_LONINDEX + 1], attrLat[LEN_LATLON + 1], attrLon[LEN_LATLON + 1],
		attrArea[LEN_AREA + 1], attrPop[LEN_POPULATION + 1], attrHouse[LEN_HOUSEHOLDS + 1], attrRhgt[LEN_HEIGHT + 1],
		attrElev[LEN_HEIGHT + 1], attrLand[LEN_LANDCOVER + 1], attrCltr[LEN_CLUTTER + 1];

	attrPtName[0] = '\0';
	attrCntKey[0] = '\0';
	attrSrcKey[0] = '\0';
	attrLatIdx[0] = '\0';
	attrLonIdx[0] = '\0';
	attrLat[0] = '\0';
	attrLon[0] = '\0';
	attrArea[0] = '\0';
	attrPop[0] = '\0';
	attrHouse[0] = '\0';
	attrRhgt[0] = '\0';
	attrElev[0] = '\0';
	attrLand[0] = '\0';
	attrCltr[0] = '\0';

	char *attrData[CELL_MAX_ATTR];

	int i = 0;

	if (STUDY_MODE_POINTS == StudyMode) {
		attrData[i++] = attrPtName;
	}
	attrData[i++] = attrCntKey;
	if (STUDY_MODE_GRID == StudyMode) {
		if (GRID_TYPE_LOCAL == Params.GridType) {
			attrData[i++] = attrSrcKey;
		}
		attrData[i++] = attrLatIdx;
		attrData[i++] = attrLonIdx;
		if (MapOutputFlags[MAP_OUT_CENTER]) {
			attrData[i++] = attrLat;
			attrData[i++] = attrLon;
		}
		attrData[i++] = attrArea;
		attrData[i++] = attrPop;
		attrData[i++] = attrHouse;
	} else {
		attrData[i++] = attrRhgt;
	}
	attrData[i++] = attrElev;
	if (Params.ApplyClutter) {
		attrData[i++] = attrLand;
		attrData[i++] = attrCltr;
	}

	for (; i < CELL_MAX_ATTR; i++) {
		attrData[i] = NULL;
	}

	TVPOINT **pointPtr, *point;
	FIELD *field;
	int loopIndex, loopCount, countryKey, latIndex, lonIndex, pointKey, maxLatIndex = 75 * 3600,
		maxLonIndex = 180 * 3600, checkPointMap = 0, latMapIndex, lonMapIndex, pointMapIndex;
	double latitude, longitude;

	if (STUDY_MODE_POINTS == StudyMode) {
		pointPtr = &Points;
		loopCount = 1;
	} else {
		pointPtr = Cells;
		loopCount = GridCount;
	}

	for (loopIndex = 0; loopIndex < loopCount; loopIndex++, pointPtr++) {

		hb_log();

		if (PointMap) {
			checkPointMap = 1;
		}

		for (point = *pointPtr; point; point = point->next) {

			for (field = point->fields; field; field = field->next) {
				if (!field->a.isUndesired) {
					break;
				}
			}

			if (field) {

				countryKey = point->countryKey;
				latIndex = point->cellLatIndex;
				lonIndex = point->cellLonIndex;

				// If needed, check the point map on the first point in the cell.  The output flag in the map applies
				// to all points in the cell, if the flag is already set skip to the next cell.

				if (checkPointMap) {
					latMapIndex = (latIndex - PointMapBounds.southLatIndex) / CellLatSize;
					lonMapIndex = (lonIndex - PointMapEastLonIndex[latMapIndex]) / PointMapLonSizes[latMapIndex];
					pointMapIndex = (latMapIndex * PointMapLonCount) + lonMapIndex;
					if (PointMap[pointMapIndex] & POINTMAP_OUTPUT_BIT) {
						 break;
					}
					PointMap[pointMapIndex] |= POINTMAP_OUTPUT_BIT;
					checkPointMap = 0;
				}

				if (mapPointsShp || mapPointsKML) {

					snprintf(attrCntKey, (LEN_COUNTRYKEY + 1), "%d", countryKey);

					latitude = point->latitude;
					longitude = point->longitude;

					if (STUDY_MODE_GRID == StudyMode) {

						if (GRID_TYPE_LOCAL == Params.GridType) {
							snprintf(attrSrcKey, (LEN_SOURCEKEY + 1), "%d", field->sourceKey);
						}

						snprintf(attrLatIdx, (LEN_LATINDEX + 1), "%d", latIndex);
						snprintf(attrLonIdx, (LEN_LONINDEX + 1), "%d", lonIndex);

						if (MapOutputFlags[MAP_OUT_CENTER]) {
							latitude = ((double)latIndex / 3600.) + ((double)CellLatSize / 7200.);
							if (GRID_TYPE_GLOBAL == Params.GridType) {
								longitude = ((double)lonIndex / 3600.) +
									((double)CellLonSizes[(latIndex - CellBounds.southLatIndex) / CellLatSize] /
									7200.);
							} else {
								longitude = ((double)lonIndex / 3600.) + ((double)CellLonSize / 7200.);
							}
							snprintf(attrLat, (LEN_LATLON + 1), "%.*f", PREC_LATLON, point->latitude);
							snprintf(attrLon, (LEN_LATLON + 1), "%.*f", PREC_LATLON, point->longitude);
						}

						snprintf(attrArea, (LEN_AREA + 1), "%.*f", PREC_AREA, point->b.area);
						snprintf(attrPop, (LEN_POPULATION + 1), "%d", point->a.population);
						snprintf(attrHouse, (LEN_HOUSEHOLDS + 1), "%d", point->households);

					} else {

						lcpystr(attrPtName, PointInfos[point->a.pointIndex].pointName, (LEN_POINTNAME + 1));
						snprintf(attrRhgt, (LEN_HEIGHT + 1), "%.*f", PREC_HEIGHT, point->b.receiveHeight);
					}

					snprintf(attrElev, (LEN_HEIGHT + 1), "%.*f", PREC_HEIGHT, point->elevation);
					if (Params.ApplyClutter) {
						snprintf(attrLand, (LEN_LANDCOVER + 1), "%d", point->landCoverType);
						snprintf(attrCltr, (LEN_CLUTTER + 1), "%d", point->clutterType);
					}

					if (mapPointsShp) {
						write_shape(mapPointsShp, latitude, longitude, NULL, 0, NULL, attrData);
					}

					if (mapPointsKML) {
						write_shape(mapPointsKML, latitude, longitude, NULL, 0, NULL, attrData);
					}
				}

				if (pointsFile) {

					if (STUDY_MODE_POINTS == StudyMode) {

						fprintf(pointsFile, "\"%s\",%d,%.*f,%.*f,%.*f,%.*f", PointInfos[point->a.pointIndex].pointName,
							countryKey, PREC_LATLON, point->latitude, PREC_LATLON, point->longitude, PREC_HEIGHT,
							point->b.receiveHeight, PREC_HEIGHT, point->elevation);

					} else {

						if (GRID_TYPE_GLOBAL == Params.GridType) {

							if ((CellLatSize < 16) || (latIndex < 0) || (latIndex > maxLatIndex) || (lonIndex < 0) ||
									(lonIndex > maxLonIndex) || (countryKey < 1) || (countryKey > 3)) {
								pointKey = -1;
							} else {
								pointKey = ((((latIndex / CellLatSize) * ((maxLonIndex / CellLatSize) + 1)) +
									(lonIndex / CellLatSize)) * 3) + (countryKey - 1);
							}
							fprintf(pointsFile, "%d,%d,%d,%d,%.*f,%.*f,%.*f,%.*f,%d,%d", latIndex, lonIndex,
								countryKey, pointKey, PREC_LATLON, point->latitude, PREC_LATLON, point->longitude,
								PREC_HEIGHT, point->elevation, PREC_AREA, point->b.area, point->a.population,
								point->households);

						} else {

							fprintf(pointsFile, "%d,%d,%d,%d,%.*f,%.*f,%.*f,%.*f,%d,%d", field->sourceKey,
								latIndex, lonIndex, countryKey, PREC_LATLON, point->latitude, PREC_LATLON,
								point->longitude, PREC_HEIGHT, point->elevation, PREC_AREA, point->b.area,
								point->a.population, point->households);
						}
					}

					if (Params.ApplyClutter) {
						fprintf(pointsFile, ",%d,%d\n", point->landCoverType, point->clutterType);
					} else {
						fputc('\n', pointsFile);
					}
				}
			}
		}
	}
}


//---------------------------------------------------------------------------------------------------------------------
// Set path to output file directory.  This is optional, if not called directly the first file open will set this to
// the path OUT_DIRECTORY_NAME.

void set_out_path(char *thePath) {

	int l = strlen(thePath) + 2;
	if (OutPath) {
		if (strlen(OutPath) < l) {
			OutPath = (char *)mem_realloc(OutPath, l);
		}
	} else {
		OutPath = (char *)mem_alloc(l);
	}
	lcpystr(OutPath, thePath, l);
}


//---------------------------------------------------------------------------------------------------------------------
// Open an output file using the host, study, and scenario names as the directory path, creating directories as needed.
// Optionally the database ID string may be used in place of the hostname string, that is used in situations where it
// is more important for the directory name to be unique and unambiguous than human-readable.

// If the UsePendingFiles global is set, the paths and names generated here are accumulated in the PendingFiles list,
// and file names are not reported with STATUS_KEY_OUTFILE messages here.  Later, clear_pending_files() is used to
// either report or delete all pending files and clear the list.

// Arguments:

//   fileName  File name, must not include any directory path!

// Return the open file, or NULL on error.

FILE *open_file(char *fileName) {

	char *path = get_file_path();
	if (!path) {
		return NULL;
	}
	int pathLen = strlen(path);

	static int filePathMax = 0;
	static char *filePath = NULL;

	int len = pathLen + strlen(fileName) + 10;
	if (len > filePathMax) {
		filePathMax = len;
		filePath = (char *)mem_realloc(filePath, filePathMax);
	}

	// If the pending files list is being used, the list may accumulate across several scenarios.  When the scenario
	// key changes add an entry to the list for the scenario directory itself.  Entries are head-linked so when
	// processing the list a scenario entry will be encountered after all of the files for that scenario; these are
	// ignored when commiting, otherwise deleted.  Then add an entry for the file itself, that holds both the complete
	// path to the file for deleting, and the relative name for the output file report message.

	PENDING_FILE *pending = NULL;

	if (UsePendingFiles) {

		if (ScenarioKey != PendingFilesScenarioKey) {

			pending = (PENDING_FILE *)mem_zalloc(sizeof(PENDING_FILE));
			pending->next = PendingFiles;
			PendingFiles = pending;

			len = pathLen + 1;
			pending->filePath = (char *)mem_alloc(len);
			lcpystr(pending->filePath, path, len);

			PendingFilesScenarioKey = ScenarioKey;
		}

		pending = (PENDING_FILE *)mem_zalloc(sizeof(PENDING_FILE));
		pending->next = PendingFiles;
		PendingFiles = pending;
	}

	len = snprintf(filePath, filePathMax, "%s/%s", path, fileName) + 1;
	FILE *out = fopen(filePath, "wb");
	if (!out) {
		log_error("Cannot create file '%s'", filePath);
	}
	if (pending) {
		pending->filePath = (char *)mem_alloc(len);
		lcpystr(pending->filePath, filePath, len);
	}

	len = snprintf(filePath, filePathMax, "%s/%s", ScenarioName, fileName) + 1;
	if (pending) {
		pending->fileName = (char *)mem_alloc(len);
		lcpystr(pending->fileName, filePath, len);
	} else {
		status_message(STATUS_KEY_OUTFILE, filePath);
	}

	return out;
}


//---------------------------------------------------------------------------------------------------------------------
// Construct the path for a file, not including the file name.  See comments for open_file() above.

char *get_file_path() {

	if (!ScenarioKey) {
		log_error("get_file_path() called with no scenario loaded");
		return NULL;
	}

	static int pathMax = 0, initForScenarioKey = 0;
	static char *path = NULL;

	if (ScenarioKey != initForScenarioKey) {

		if (!OutPath) {
			set_out_path(OUT_DIRECTORY_NAME);
		}

		char *hostDir;
		if (UseDbIDOutPath) {
			hostDir = DatabaseID;
		} else {
			hostDir = HostDbName;
		}

		int len = strlen(OutPath) + strlen(hostDir) + strlen(StudyName) + strlen(ScenarioName) + 10;
		if (len > pathMax) {
			pathMax = len;
			path = (char *)mem_realloc(path, pathMax);
		}

		if (mkdir(OutPath, 0750)) {
			if (errno != EEXIST) {
				log_error("Path creation failed: mkdir %s returned errno=%d", OutPath, errno);
				return NULL;
			}
		}

		snprintf(path, pathMax, "%s/%s", OutPath, hostDir);
		if (mkdir(path, 0750)) {
			if (errno != EEXIST) {
				log_error("Path creation failed: mkdir %s returned errno=%d", path, errno);
				return NULL;
			}
		}

		snprintf(path, pathMax, "%s/%s/%s", OutPath, hostDir, StudyName);
		if (mkdir(path, 0750)) {
			if (errno != EEXIST) {
				log_error("Path creation failed: mkdir %s returned errno=%d", path, errno);
				return NULL;
			}
		}

		snprintf(path, pathMax, "%s/%s/%s/%s", OutPath, hostDir, StudyName, ScenarioName);
		if (mkdir(path, 0750)) {
			if (errno != EEXIST) {
				log_error("Path creation failed: mkdir %s returned errno=%d", path, errno);
				return NULL;
			}
		}

		initForScenarioKey = ScenarioKey;
	}

	return path;
}


//---------------------------------------------------------------------------------------------------------------------
// Clear the pending files list and either commit or delete the files, commit is sending STATUS_KEY_OUTFILE messages
// to the front-end app, delete is of course deleting all files, and the enclosing scenario directories.  Errors are
// ignored here, if files fail to delete they are orphaned, the front-end has a clean-up procedure to deal with that.
// The file list is freed along the way.  Note this also clears the UsePendingFiles flag so that must be re-set if
// desired before the next open_file() occurs.

// Arguments:

//   commit  True to commit, false to delete.

static void clear_pending_files(int commit) {

	PENDING_FILE *pending;

	while (PendingFiles) {

		pending = PendingFiles;
		PendingFiles = pending->next;
		pending->next = NULL;

		if (!commit) {
			if (pending->fileName) {
				unlink(pending->filePath);
			} else {
				rmdir(pending->filePath);
			}
		}
		mem_free(pending->filePath);
		pending->filePath = NULL;

		if (pending->fileName) {
			if (commit) {
				status_message(STATUS_KEY_OUTFILE, pending->fileName);
			}
			mem_free(pending->fileName);
			pending->fileName = NULL;
		}

		mem_free(pending);
	}

	PendingFilesScenarioKey = 0;
	UsePendingFiles = 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Open a summary-format output file, these combine output from multiple scenario runs so the scenario directory is
// not part of the path and a scenario does not even need to be loaded.

// Arguments:

//   fileName  File name, must not include any directory path!

// Return the open file, or NULL on error.

FILE *open_sum_file(char *fileName) {

	char *path = get_sum_file_path();
	if (!path) {
		return NULL;
	}
	int pathLen = strlen(path);

	static int filePathMax = 0;
	static char *filePath = NULL;

	int len = pathLen + strlen(fileName) + 10;
	if (len > filePathMax) {
		filePathMax = len;
		filePath = (char *)mem_realloc(filePath, filePathMax);
	}

	snprintf(filePath, filePathMax, "%s/%s", path, fileName);
	FILE *out = fopen(filePath, "wb");
	if (!out) {
		log_error("Cannot create file '%s'", filePath);
	}

	status_message(STATUS_KEY_OUTFILE, fileName);

	return out;
}


//---------------------------------------------------------------------------------------------------------------------
// Get path for summary-level files, see comments above.

char *get_sum_file_path() {

	if (!StudyKey) {
		log_error("get_sum_file_path() called with no study open");
		return NULL;
	}

	static int pathMax = 0, initForStudyKey = 0;
	static char *path = NULL;

	if (StudyKey != initForStudyKey) {

		if (!OutPath) {
			set_out_path(OUT_DIRECTORY_NAME);
		}

		char *hostDir;
		if (UseDbIDOutPath) {
			hostDir = DatabaseID;
		} else {
			hostDir = HostDbName;
		}

		int len = strlen(OutPath) + strlen(hostDir) + strlen(StudyName) + 10;
		if (len > pathMax) {
			pathMax = len;
			path = (char *)mem_realloc(path, pathMax);
		}

		if (mkdir(OutPath, 0750)) {
			if (errno != EEXIST) {
				log_error("Path creation failed: mkdir %s returned errno=%d", OutPath, errno);
				return NULL;
			}
		}

		snprintf(path, pathMax, "%s/%s", OutPath, hostDir);
		if (mkdir(path, 0750)) {
			if (errno != EEXIST) {
				log_error("Path creation failed: mkdir %s returned errno=%d", path, errno);
				return NULL;
			}
		}

		snprintf(path, pathMax, "%s/%s/%s", OutPath, hostDir, StudyName);
		if (mkdir(path, 0750)) {
			if (errno != EEXIST) {
				log_error("Path creation failed: mkdir %s returned errno=%d", path, errno);
				return NULL;
			}
		}

		initForStudyKey = StudyKey;
	}

	return path;
}


//---------------------------------------------------------------------------------------------------------------------
// Open a temporary output file, the file is stored at the study level (summary-file level).  The global RunNumber is
// used in the name, also a sequential file count number.

FILE *open_temp_file() {

	static int fileNumber = 0;

	char tempName[MAX_STRING];

	snprintf(tempName, MAX_STRING, "outtemp_%d_%d", RunNumber, fileNumber++);
	return open_sum_file(tempName);
}


//---------------------------------------------------------------------------------------------------------------------
// Project a field at a study point.  Return error code passed through from run_model().

// Arguments:

//   point  The point being studied.
//   field  The field that needs to be computed.

// Return is <0 for a serious error, >0 for a minor error, 0 for no error.  Note that minor errors from the propagation
// model will NOT cause a non-zero return from this function; such errors are stored in the field structure.

static int project_field(TVPOINT *point, FIELD *field) {

	static MODEL_DATA data;

	// Get source structure, copy some basic values.

	SOURCE *source = SourceKeyIndex[field->sourceKey];
	if (!source) {
		log_error("Source structure index is corrupted");
		exit(1);
	}

	int countryIndex = source->countryKey - 1;

	double bear = (double)field->bearing, dist = (double)field->distance;

	double receiveHeight = (STUDY_MODE_POINTS == StudyMode) ? (double)point->b.receiveHeight : Params.ReceiveHeight;

	// Populate the propagation model data structure, see model.h and model.c.  For the model statistical parameters,
	// wireless records should not be desireds but use digital TV values just in case.  FM uses analog TV values.

	data.model = PropagationModel;

	data.latitude = source->latitude;
	data.longitude = source->longitude;

	data.bearing = bear;
	data.distance = dist;

	data.terrainDb = Params.TerrPathDb;

	data.transmitHeightAGL = source->heightAGL;
	data.receiveHeightAGL = receiveHeight;

	data.clutterType = (int)point->clutterType;

	data.frequency = source->frequency;

	if (field->a.percentTime) {
		data.percentTime = (double)field->a.percentTime / 100.;
	}
	if (RECORD_TYPE_WL == source->recordType) {
		if (field->a.percentTime) {
			data.percentLocation = Params.WirelessUndesiredLocation;
			data.percentConfidence = Params.WirelessUndesiredConfidence;
		} else {
			data.percentTime = Params.DigitalDesiredTime;
			data.percentLocation = Params.DigitalDesiredLocation;
			data.percentConfidence = Params.DigitalDesiredConfidence;
		}
	} else {
		if (source->dtv) {
			if (field->a.percentTime) {
				data.percentLocation = Params.DigitalUndesiredLocation;
				data.percentConfidence = Params.DigitalUndesiredConfidence;
			} else {
				data.percentTime = Params.DigitalDesiredTime;
				data.percentLocation = Params.DigitalDesiredLocation;
				data.percentConfidence = Params.DigitalDesiredConfidence;
			}
		} else {
			if (field->a.percentTime) {
				data.percentLocation = Params.AnalogUndesiredLocation;
				data.percentConfidence = Params.AnalogUndesiredConfidence;
			} else {
				data.percentTime = Params.AnalogDesiredTime;
				data.percentLocation = Params.AnalogDesiredLocation;
				data.percentConfidence = Params.AnalogDesiredConfidence;
			}
		}
	}

	data.atmosphericRefractivity = Params.AtmosphericRefractivity;
	data.groundPermittivity = Params.GroundPermittivity;
	data.groundConductivity = Params.GroundConductivity;

	if (RECORD_TYPE_WL == source->recordType) {
		data.signalPolarization = Params.WirelessSignalPolarization;
		data.serviceMode = Params.WirelessLRServiceMode;
	} else {
		data.signalPolarization = Params.SignalPolarization;
		data.serviceMode = Params.LRServiceMode;
	}
	data.climateType = Params.LRClimateType;

	data.profilePpk = (RECORD_TYPE_WL == source->recordType) ?
		Params.WirelessTerrPathPpk : Params.TerrPathPpk[countryIndex];
	data.kilometersPerDegree = Params.KilometersPerDegree;

	data.profileCount = (int)((data.distance * data.profilePpk) + 0.5) + 1;

	// If the the profile would be null (the distance is less than half the point spacing) use free-space, otherwise
	// run the model.  The result is a reference field strength for 0 dBk.  The profile retrieved here, if any, is
	// made available in globals for use by other code.

	data.profile = NULL;
	data.fieldStrength = 0.;
	data.errorCode = 0;
	data.errorMessage[0] = '\0';

	Profile = NULL;
	ProfileCount = 0;

	if (data.profileCount < 2) {

		if (data.distance > 0.01) {
			data.fieldStrength = 106.92 - (20. * log10(data.distance));
		} else {
			data.fieldStrength = 146.92;
		}

	} else {

		int err = run_model(&data);
		if (err) {
			if (data.errorMessage[0]) {
				log_error(data.errorMessage);
			} else {
				log_error("Propagation model failed, err=%d", err);
			}
			return err;
		}

		if (data.profile) {
			Profile = data.profile;
			ProfileCount = data.profileCount;
			ProfilePpk = data.profilePpk;
		}
	}

	// Apply ERP and pattern to arrive at final field strength.  Note this is deliberately not using the values in
	// the MODEL_DATA structure, if those were modified by the model code this should not be affected.

	data.fieldStrength += erp_lookup(source, bear) + vpat_lookup(source, source->heightAGL, bear, dist,
		(double)point->elevation, receiveHeight, VPAT_MODE_PROP, NULL, NULL);

	// If needed, adjust field strength for receiver clutter.

	if (Params.ApplyClutter && point->clutterType) {
		data.fieldStrength += Params.ClutterValues[((((point->clutterType - 1) * N_CLUTTER_BANDS) +
			source->clutterBand) * MAX_COUNTRY) + countryIndex];
	}

	// Save result.

	field->fieldStrength = (float)data.fieldStrength;
	field->status = (short)data.errorCode;
	if (STUDY_MODE_GRID == StudyMode) {
		field->b.cached = 0;
	}

	// For isolated points not part of an analysis grid, all done.  This occurs in points mode.

	if ((INVALID_LATLON_INDEX == point->cellLatIndex) && (INVALID_LATLON_INDEX == point->cellLonIndex)) {
		return 0;
	}

	// Set flag indicating there is a new result needing to be cached from this source.  For an undesired, add to the
	// bounds that indicate the grid range over which there are new fields to cache.  If this field was for a DTS
	// source, the updates are made to the parent source structure.

	if (source->parentSource) {
		source = source->parentSource;
	}

	if (field->a.isUndesired) {
		source->ucache = 1;
		int latGridIndex = (point->cellLatIndex - CellBounds.southLatIndex) / CellLatSize;
		int lonGridIndex = 0;
		if (GRID_TYPE_GLOBAL == Params.GridType) {
			lonGridIndex = point->cellLonIndex;
		} else {
			lonGridIndex = (point->cellLonIndex - CellBounds.eastLonIndex) / CellLonSize;
		}
		extend_bounds_index(&(source->ugridIndex), latGridIndex, lonGridIndex);
	} else {
		source->dcache = 1;
	}

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Analyze result for a given source in the current study grid, tally population and area.  This traverses the cell
// grid for the source coverage area, finds desired and undesired fields, applies receive-antenna-pattern corrections,
// and checks D/U limits to tally coverage under various conditions.  Optionally data will be written to a cell-level
// output file.  See analyze_points() for full details.

// Arguments:

//   source    The desired source to analyze.

// Return is <0 for a serious error, >0 for a minor error, 0 for no error.

static int analyze_source(SOURCE *source) {

	int eastLonIndex = CellBounds.eastLonIndex;
	int eastLonGridIndex = source->gridIndex.eastLonIndex;
	int westLonGridIndex = source->gridIndex.westLonIndex;

	FILE *outFile;

	if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
		fprintf(outFile, "[source]\n%d\n", source->sourceKey);
	}

	if ((outFile = OutputFile[CELL_FILE_SUMMARY])) {
		fprintf(outFile, "[source]\n%d\n", source->sourceKey);
	}

	int latGridIndex, lonGridIndex, err;

	for (latGridIndex = source->gridIndex.southLatIndex; latGridIndex < source->gridIndex.northLatIndex;
			latGridIndex++) {

		if (GRID_TYPE_GLOBAL == Params.GridType) {
			eastLonIndex = CellEastLonIndex[latGridIndex];
			eastLonGridIndex = (source->gridIndex.eastLonIndex - eastLonIndex) / CellLonSizes[latGridIndex];
			westLonGridIndex = (((source->gridIndex.westLonIndex - 1) - eastLonIndex) / CellLonSizes[latGridIndex]) + 1;
			if (westLonGridIndex > GridLonCounts[latGridIndex]) {
				westLonGridIndex = GridLonCounts[latGridIndex];
			}
		}

		for (lonGridIndex = eastLonGridIndex; lonGridIndex < westLonGridIndex; lonGridIndex++) {
			err = analyze_points(*(Cells + ((latGridIndex * GridLonCount) + lonGridIndex)), source);
			if (err) return err;
		}
	}

	if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
		fputs("[endsource]\n", outFile);
	}

	if ((outFile = OutputFile[CELL_FILE_SUMMARY])) {
		fputs("[endsource]\n", outFile);
	}

	return 0;
} 


//---------------------------------------------------------------------------------------------------------------------
// Analyze result for a specified index range of the study grid.  If called on the entire study grid, the end result
// will be identical to iteratively calling analyze_source() on each desired source studied; however the format and
// order of records in the cell-level output file will be different.

// Arguments:

//   gridIndex  Bounds of area to analyze, units of grid index.

// Return is <0 for a serious error, >0 for a minor error, 0 for no error.

static int analyze_grid(INDEX_BOUNDS gridIndex) {

	int eastLonIndex = CellBounds.eastLonIndex;
	int eastLonGridIndex = gridIndex.eastLonIndex;
	int westLonGridIndex = gridIndex.westLonIndex;

	int latGridIndex, lonGridIndex, err;

	for (latGridIndex = gridIndex.southLatIndex; latGridIndex < gridIndex.northLatIndex; latGridIndex++) {

		if (GRID_TYPE_GLOBAL == Params.GridType) {
			eastLonIndex = CellEastLonIndex[latGridIndex];
			eastLonGridIndex = (gridIndex.eastLonIndex - eastLonIndex) / CellLonSizes[latGridIndex];
			westLonGridIndex = (((gridIndex.westLonIndex - 1) - eastLonIndex) / CellLonSizes[latGridIndex]) + 1;
			if (westLonGridIndex > GridLonCounts[latGridIndex]) {
				westLonGridIndex = GridLonCounts[latGridIndex];
			}
		}

		for (lonGridIndex = eastLonGridIndex; lonGridIndex < westLonGridIndex; lonGridIndex++) {
			err = analyze_points(*(Cells + ((latGridIndex * GridLonCount) + lonGridIndex)), NULL);
			if (err) return err;
		}
	}

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Analyze a list of study points, which may be from a grid cell, or a separate list in points mode.  The analysis is
// is either for a specific source, or for all sources with desired service at any point.  This is the common part of
// the coverage analysis used by analyze_source() and analyze_grid(), also used directly in points mode.  In points
// mode this also writes directly to the summary and detail report and CSV files as those are open.  The format of
// data written to the various cell files varies depending on whether this is for a specific source, or all.

// Arguments:

//   points   Head pointer to list of points.
//   dsource  The desired source, or NULL to analyze all desired sources found in each point.

// Return is <0 for a serious error, >0 for a minor error, 0 for no error.

static int analyze_points(TVPOINT *points, SOURCE *dsource) {

	if (!points) {
		return 0;
	}

	int gridMode = (STUDY_MODE_GRID == StudyMode);
	int pointsMode = (STUDY_MODE_POINTS == StudyMode);

	// Set up attribute fields for map file output.

	static char attrCntKey[LEN_COUNTRYKEY + 1], attrSrcKey[LEN_SOURCEKEY + 1], attrLatIdx[LEN_LATINDEX + 1],
		attrLonIdx[LEN_LONINDEX + 1], attrIXBear[LEN_IXBEARING + 1], attrIXMarg[LEN_ATTRDB + 1],
		attrLat[LEN_LATLON + 1], attrLon[LEN_LATLON + 1], attrArea[LEN_AREA + 1], attrPop[LEN_POPULATION + 1],
		attrHouse[LEN_HOUSEHOLDS + 1], attrPtName[LEN_POINTNAME + 1], attrLand[LEN_LANDCOVER + 1],
		attrCltr[LEN_CLUTTER + 1], attrCltrDb[LEN_ATTRDB + 1], attrDesSig[LEN_ATTRDB + 1], attrDesMarg[LEN_ATTRDB + 1],
		attrWorstDUSrcKey[LEN_SOURCEKEY + 1], attrWorstDU[LEN_ATTRDB + 1], attrWorstDUMarg[LEN_ATTRDB + 1],
		attrWLSig[LEN_ATTRDB + 1], attrWLDU[LEN_ATTRDB + 1], attrWLDUMarg[LEN_ATTRDB + 1], attrSelfDU[LEN_ATTRDB + 1],
		attrSelfDUMarg[LEN_ATTRDB + 1], attrMarg[LEN_ATTRDB + 1], attrRamp[LEN_ATTRDB + 1],
		desSiteNum[LEN_SITENUM + 1], attrUndSigRSS[LEN_ATTRDB + 1], attrUndSiteNum[LEN_SITENUM + 1],
		attrUndSig[LEN_ATTRDB + 1], attrDeltaT[LEN_DELTAT + 1];

	char *attrData[COV_MAX_ATTR], *attrSelfIX[SELFIX_NUM_ATTR];
	int doMapCov = (MapOutputFile[MAP_OUT_SHAPE_COV] || MapOutputFile[MAP_OUT_KML_COV]);
	int i, resultAttr = 0, causeAttr = -1;

	if (doMapCov) {

		attrCntKey[0] = '\0';
		attrSrcKey[0] = '\0';
		attrLatIdx[0] = '\0';
		attrLonIdx[0] = '\0';
		attrLat[0] = '\0';
		attrLon[0] = '\0';
		attrArea[0] = '\0';
		attrPop[0] = '\0';
		attrHouse[0] = '\0';
		attrPtName[0] = '\0';
		attrLand[0] = '\0';
		attrCltr[0] = '\0';
		attrCltrDb[0] = '\0';
		attrDesSig[0] = '\0';
		attrDesMarg[0] = '\0';
		attrWorstDUSrcKey[0] = '\0';
		attrWorstDU[0] = '\0';
		attrWorstDUMarg[0] = '\0';
		attrWLSig[0] = '\0';
		attrWLDU[0] = '\0';
		attrWLDUMarg[0] = '\0';
		attrSelfDU[0] = '\0';
		attrSelfDUMarg[0] = '\0';
		attrMarg[0] = '\0';
		attrRamp[0] = '\0';

		i = 0;

		if (gridMode) {
			attrData[i++] = attrCntKey;
			attrData[i++] = attrSrcKey;
			attrData[i++] = attrLatIdx;
			attrData[i++] = attrLonIdx;
			resultAttr = i++;
			if (IXMarginSourceKey > 0) {
				attrData[i++] = attrIXBear;
				attrData[i++] = attrIXMarg;
			}
			if (MapOutputFlags[MAP_OUT_COORDS]) {
				attrData[i++] = attrLat;
				attrData[i++] = attrLon;
			}
			if (MapOutputFlags[MAP_OUT_AREAPOP]) {
				attrData[i++] = attrArea;
				attrData[i++] = attrPop;
				attrData[i++] = attrHouse;
			}
		} else {
			attrData[i++] = attrPtName;
			attrData[i++] = attrSrcKey;
			resultAttr = i++;
		}
		if (MapOutputFlags[MAP_OUT_CLUTTER] && Params.ApplyClutter) {
			attrData[i++] = attrLand;
			attrData[i++] = attrCltr;
			attrData[i++] = attrCltrDb;
		}
		if (MapOutputFlags[MAP_OUT_DESINFO]) {
			attrData[i++] = desSiteNum;
			attrData[i++] = attrDesSig;
			attrData[i++] = attrDesMarg;
		}
		if (MapOutputFlags[MAP_OUT_UNDINFO]) {
			attrData[i++] = attrWorstDUSrcKey;
			attrData[i++] = attrWorstDU;
			attrData[i++] = attrWorstDUMarg;
		}
		if (MapOutputFlags[MAP_OUT_WLINFO] && (STUDY_TYPE_TV_OET74 == StudyType)) {
			attrData[i++] = attrWLSig;
			attrData[i++] = attrWLDU;
			attrData[i++] = attrWLDUMarg;
		}
		if (MapOutputFlags[MAP_OUT_SELFIX] && Params.CheckSelfInterference) {
			attrData[i++] = attrSelfDU;
			attrData[i++] = attrSelfDUMarg;
			attrSelfIX[0] = attrSrcKey;
			attrSelfIX[1] = desSiteNum;
			attrSelfIX[2] = attrDesSig;
			attrSelfIX[3] = attrUndSigRSS;
			attrSelfIX[4] = attrPop;
			attrSelfIX[5] = attrUndSiteNum;
			attrSelfIX[6] = attrUndSig;
			attrSelfIX[7] = attrDeltaT;
		}
		if (MapOutputFlags[MAP_OUT_MARGIN]) {
			attrData[i++] = attrMarg;
			causeAttr = i++;
		}
		if (MapOutputFlags[MAP_OUT_RAMP]) {
			attrData[i++] = attrRamp;
		}

		for (; i < COV_MAX_ATTR; i++) {
			attrData[i] = NULL;
		}
	}

	// Local variables.

	TVPOINT *point;
	POINT_INFO *pointInfo = NULL;
	MPAT *recvPat = NULL;
	FIELD *field, *ufield, *desField, *undField, *selfIxUndFields;
	SOURCE *source, *usource, *dtsSource, *desSource;
	UNDESIRED *undesireds;
	DES_TOTAL *dtot;
	UND_TOTAL *utot, *utotix, *utotwl;
	char *resultStr;
	double desServBase, desServAdjust, desServ, recvOrient, desERP, desSigBase, desSigAdjust, desSig, desMargin,
		undSigBase, undSigAdjust, undSig, du, duReqBase, duReqAdjust, duReq, duMargin, ixBearing, ixMargin, mindist,
		dtime, utime, delta, worstDU, worstDUMarg, wlSig, wlDU, wlDUMarg, selfDU, selfDUMarg, ptlat, ptlon, imageVal;
	int countryIndex, totCountryIndex, startCell, startCellSum, startPoint, startPointSum, startPointPair,
		startPointReport, undesiredIndex, undesiredCount, hasError, hasServiceError, hasService, needsWireless,
		hasWirelessIXError, hasWirelessIX, ixCount, hasIXError, hasIX, done, result, latMapIndex, lonMapIndex,
		pointMapIndex, shift, compResult, updateResult, worstDUSrcKey, doImage, hasSelfIX;
	unsigned char mask;
	FILE *outFile;
	MAPFILE *mapFile;
	CEN_POINT *cenPoint;

	// Loop over study points, setup for cell and map file output.

	startCell = 1;
	startCellSum = 1;
	doImage = 0;
	imageVal = 999.;

	for (point = points; point; point = point->next) {

		hb_log();

		startPoint = 1;
		startPointSum = 1;
		startPointPair = 1;
		startPointReport = 1;

		if (pointsMode) {
			pointInfo = PointInfos + point->a.pointIndex;
		}

		if (doMapCov) {
			if (gridMode) {
				snprintf(attrCntKey, (LEN_COUNTRYKEY + 1), "%d", point->countryKey);
				snprintf(attrLatIdx, (LEN_LATINDEX + 1), "%d", point->cellLatIndex);
				snprintf(attrLonIdx, (LEN_LONINDEX + 1), "%d", point->cellLonIndex);
				if (MapOutputFlags[MAP_OUT_COORDS]) {
					snprintf(attrLat, (LEN_LATLON + 1), "%.*f", PREC_LATLON, point->latitude);
					snprintf(attrLon, (LEN_LATLON + 1), "%.*f", PREC_LATLON, point->longitude);
				}
				if (MapOutputFlags[MAP_OUT_AREAPOP]) {
					snprintf(attrArea, (LEN_AREA + 1), "%.*f", PREC_AREA, point->b.area);
					snprintf(attrPop, (LEN_POPULATION + 1), "%d", point->a.population);
					snprintf(attrHouse, (LEN_HOUSEHOLDS + 1), "%d", point->households);
				}
			} else {
				lcpystr(attrPtName, pointInfo->pointName, (LEN_POINTNAME + 1));
			}
			if (MapOutputFlags[MAP_OUT_CLUTTER] && Params.ApplyClutter) {
				snprintf(attrLand, (LEN_LANDCOVER + 1), "%d", point->landCoverType);
				snprintf(attrCltr, (LEN_CLUTTER + 1), "%d", point->clutterType);
			}
		}

		// Loop over fields at the point looking for desireds, either the specific one requested, or all.  Uncalculated
		// fields should never occur, if one is found abort.

		for (field = point->fields; field; field = field->next) {

			if (field->a.isUndesired) {
				continue;
			}
			if (dsource && (field->sourceKey != dsource->sourceKey)) {
				continue;
			}

			if (field->status < 0) {
				log_error("Un-calculated field: countryKey=%d latIndex=%d lonIndex=%d sourceKey=%d percentTime=%.2f",
					point->countryKey, point->cellLatIndex, point->cellLonIndex, field->sourceKey,
					((double)field->a.percentTime / 100.));
				return 1;
			}

			source = SourceKeyIndex[field->sourceKey];
			if (!source) {
				log_error("Source structure index is corrupted");
				exit(1);
			}
			countryIndex = source->countryKey - 1;

			if (doMapCov) {
				snprintf(attrSrcKey, (LEN_SOURCEKEY + 1), "%d", source->sourceKey);
				if (MapOutputFlags[MAP_OUT_CLUTTER] && Params.ApplyClutter) {
					if (point->clutterType) {
						snprintf(attrCltrDb, (LEN_ATTRDB + 1), "%.*f", PREC_ATTRDB,
							Params.ClutterValues[((((point->clutterType - 1) * N_CLUTTER_BANDS) +
								source->clutterBand) * MAX_COUNTRY) + countryIndex]);
					} else {
						attrCltrDb[0] = '\0';
					}
				}
				attrData[resultAttr] = "";
				attrIXBear[0] = '\0';
				attrIXMarg[0] = '\0';
				attrDesSig[0] = '\0';
				attrDesMarg[0] = '\0';
				attrWorstDUSrcKey[0] = '\0';
				attrWorstDU[0] = '\0';
				attrWorstDUMarg[0] = '\0';
				attrWLSig[0] = '\0';
				attrWLDU[0] = '\0';
				attrWLDUMarg[0] = '\0';
				attrSelfDU[0] = '\0';
				attrSelfDUMarg[0] = '\0';
				attrMarg[0] = '\0';
				if (causeAttr >= 0) {
					attrData[causeAttr] = "";
				}
				attrRamp[0] = '\0';
				attrUndSigRSS[0] = '\0';
				attrUndSiteNum[0] = '\0';
				attrUndSig[0] = '\0';
				attrDeltaT[0] = '\0';
			}

			worstDUSrcKey = 0;
			worstDU = 999.;
			worstDUMarg = 999.;
			wlSig = 999.;
			wlDU = 999.;
			wlDUMarg = 999.;
			selfDU = 999.;
			selfDUMarg = 999.;

			// For DTS, the desired source for this point is the source providing the strongest field strength.  The
			// field found is just a placeholder for the parent, which is not a "real" source in this context; fields
			// for the actual sources follow the parent in sequence.

			if (source->isParent) {

				desField = field->next;
				desSource = source->dtsSources;

				for (dtsSource = source->dtsSources; dtsSource; dtsSource = dtsSource->next) {

					field = field->next;

					if (!field || (field->sourceKey != dtsSource->sourceKey) || (field->status < 0)) {
						log_error("Missing or out-of-order fields for DTS sourceKey=%d (1)", source->sourceKey);
						return 1;
					}

					if (field->fieldStrength > desField->fieldStrength) {
						desField = field;
						desSource = dtsSource;
					}
				}

				snprintf(desSiteNum, (LEN_SITENUM + 1), "%d", desSource->siteNumber);

			} else {

				desField = field;
				desSource = source;
				desSiteNum[0] = '\0';
			}

			// In grid mode, add to the desired source's contour total.  When totalling, if the point's country is
			// disabled for separate study and reporting (CenYear[?] is 0), add to the source's country instead.  The
			// area total in the contour (or whatever defines the service area) is always tallied, because other code
			// depends on checking that area total against 0. to determine if any study points were actually checked.
			// However if the desired source is in unrestricted mode, that area totoal will not be reported, nor will
			// population or households totals as they are conceptually not meaningful.  To flag that condition set
			// the population and households totals to -1.  Once that is set it will never be un-set, so if any one
			// point is encountered that is based on an unrestricted service area, the entire area is considered to
			// be unrestricted and not reported.  There can be a mix of unrestricted and restricted service areas in
			// the case of DTS, or later when tallying composite coverage.

			if (gridMode) {

				totCountryIndex = point->countryKey - 1;
				if (!Params.CenYear[totCountryIndex]) {
					totCountryIndex = countryIndex;
				}

				dtot = source->totals + totCountryIndex;

				if (dtot->contourPop >= 0) {
					if (SERVAREA_NO_BOUNDS == desSource->serviceAreaMode) {
						dtot->contourPop = -1;
						dtot->contourHouse = -1;
					} else {
						dtot->contourPop += point->a.population;
						dtot->contourHouse += point->households;
					}
				}
				dtot->contourArea += point->b.area;
			}

			// Set up for the analysis.  In points mode, the service threshold for DTV may need to be adjusted for the
			// gain of a custom receive antenna vs. the OET69 generic antennas.  That does not affect D/U, only the
			// service test.  However the relative gain of the receive antenna does affect D/U, the desired signal may
			// be attenuated by off-axis or off-frequency response of the receive antenna.

			desServBase = source->serviceLevel;
			desServAdjust = 0.;
			desSigBase = (double)desField->fieldStrength;
			desSigAdjust = 0.;
			recvPat = NULL;
			recvOrient = (double)desField->reverseBearing;
			desERP = erp_lookup(desSource, (double)desField->bearing);

			if (pointsMode) {
				if (pointInfo->receiveAnt) {
					recvPat = pointInfo->receiveAnt->rpat;
					if (source->dtv) {
						switch (source->band) {
							case BAND_VLO1:
							case BAND_VLO2:
								desServAdjust = 4. - pointInfo->receiveAnt->gain;
								break;
							case BAND_VHI:
								desServAdjust = 6. - pointInfo->receiveAnt->gain;
								break;
							case BAND_UHF:
								desServAdjust = 10. - pointInfo->receiveAnt->gain;
								break;
						}
					}
				}
				if (pointInfo->receiveOrient >= 0.) {
					recvOrient = pointInfo->receiveOrient;
				}
				desSigAdjust = recv_az_lookup(source, recvPat, recvOrient, (double)desField->reverseBearing,
					source->frequency);
			}

			desServ = desServBase + desServAdjust;
			desSig = desSigBase + desSigAdjust;
			desMargin = desSig - desServ;

			// In points mode, write to the main and summary report and CSV files if open.

			if (pointsMode) {
				if (startPointReport) {
					if ((outFile = OutputFile[REPORT_FILE_DETAIL])) {
						fprintf(outFile, "%-20.20s  %s", pointInfo->pointName, latlon_string(point->latitude, 0));
						fprintf(outFile, "  %s  %7.1f m  %6.1f m\n", latlon_string(point->longitude, 1),
							point->elevation, point->b.receiveHeight);
					}
					if ((outFile = OutputFile[REPORT_FILE_SUMMARY])) {
						fprintf(outFile, "%-20.20s  %s", pointInfo->pointName, latlon_string(point->latitude, 0));
						fprintf(outFile, "  %s\n", latlon_string(point->longitude, 1));
					}
					if ((outFile = OutputFile[CSV_FILE_DETAIL])) {
						fprintf(outFile, "\"%s\",%.12f,%.12f,%.1f,%.1f,", pointInfo->pointName, point->latitude,
							point->longitude, point->elevation, point->b.receiveHeight);
					}
					if ((outFile = OutputFile[CSV_FILE_SUMMARY])) {
						fprintf(outFile, "\"%s\",%.12f,%.12f,", pointInfo->pointName, point->latitude,
							point->longitude);
					}
					startPointReport = 0;
				} else {
					if ((outFile = OutputFile[CSV_FILE_DETAIL])) {
						fputs(",,,,,", outFile);
					}
					if ((outFile = OutputFile[CSV_FILE_SUMMARY])) {
						fputs(",,,", outFile);
					}
				}
				if ((outFile = OutputFile[REPORT_FILE_DETAIL])) {
					fprintf(outFile, "  %-35.35s  %5.1f deg  %6.1f km", source_label(source),
						desField->reverseBearing, desField->distance);
					if (-999. == desSigBase) {
						fputs("  not calculated\n", outFile);
					} else {
						fprintf(outFile, "  %7.2f dBu", desSig);
					}
				}
				if ((outFile = OutputFile[REPORT_FILE_SUMMARY])) {
					fprintf(outFile, "  %-35.35s", source_label(source));
					if (-999. == desSigBase) {
						fputs("  not calculated\n", outFile);
					} else {
						fprintf(outFile, "  %7.2f dBu", desSig);
					}
				}
				if ((outFile = OutputFile[CSV_FILE_DETAIL])) {
					fprintf(outFile, "%d,%d,\"%s\",\"%s\",\"%s\",%s,%s,%.1f,%.1f", source->facility_id,
						source->channel, source->callSign, source->fileNumber, source->city, source->state,
						CountryName[countryIndex], desField->reverseBearing, desField->distance);
					if (-999. == desSigBase) {
						fputc('\n', outFile);
					} else {
						fprintf(outFile, ",%.6f", desSig);
					}
				}
				if ((outFile = OutputFile[CSV_FILE_SUMMARY])) {
					fprintf(outFile, "%d,\"%s\",%d,%d,%d", source->facility_id, source->fileNumber, source->countryKey,
						source->serviceTypeKey, source->channel);
					if (-999. == desSigBase) {
						fputc('\n', outFile);
					} else {
						fprintf(outFile, ",%.6f", desSig);
					}
				}
			}

			// A -999. field strength means the desired station was too far away and no calculations were actually
			// done, in that case skip the rest of the processing for this desired source.

			if (-999. == desSigBase) {
				continue;
			}

			// Write to the cell-level files.

			if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
				if (dsource) {
					if (startPoint) {
						fprintf(outFile, "P,%d,%d,%d,%.12f,%.12f,%.8f,%d,%d,%d\n", point->cellLatIndex,
							point->cellLonIndex, point->countryKey, point->latitude, point->longitude, point->b.area,
							point->a.population, point->households, point->clutterType);
						if (CELL_DETAIL_CENPT == OutputFlags[CELL_FILE_DETAIL]) {
							for (cenPoint = point->censusPoints; cenPoint; cenPoint = cenPoint->next) {
								fprintf(outFile, "C,%s,%d,%d\n", cenPoint->blockID, cenPoint->population,
									cenPoint->households);
							}
						}
						startPoint = 0;
					}
					fprintf(outFile, "D,%s,%.6f,%.2f,%.2f,%.6f,%d", desSiteNum, desSig, desField->reverseBearing,
						desField->bearing, desERP, desField->status);
				} else {
					if (gridMode && startCell) {
						fprintf(outFile, "[cell]\n%d,%d\n", point->cellLatIndex, point->cellLonIndex);
						startCell = 0;
					}
					if (startPoint) {
						if (pointsMode) {
							fprintf(outFile, "P,\"%s\",%d,%.12f,%.12f,%.1f,%.1f,%d\n",
								pointInfo->pointName, point->countryKey, point->latitude, point->longitude,
								point->elevation, point->b.receiveHeight, point->clutterType);
						} else {
							fprintf(outFile, "P,%d,%.12f,%.12f,%.8f,%d,%d,%d\n", point->countryKey, point->latitude,
								point->longitude, point->b.area, point->a.population, point->households,
								point->clutterType);
							if (CELL_DETAIL_CENPT == OutputFlags[CELL_FILE_DETAIL]) {
								for (cenPoint = point->censusPoints; cenPoint; cenPoint = cenPoint->next) {
									fprintf(outFile, "C,%s,%d,%d\n", cenPoint->blockID, cenPoint->population,
										cenPoint->households);
								}
							}
						}
						startPoint = 0;
					}
					if (pointsMode) {
						fprintf(outFile, "D,%d,%s,%.6f,%.6f,%.6f,%.2f,%.2f,%.6f,%d", source->sourceKey, desSiteNum,
							desSigBase, desSigAdjust, desServAdjust, desField->reverseBearing, desField->bearing,
							desERP, desField->status);
					} else {
						fprintf(outFile, "D,%d,%s,%.6f,%.2f,%.2f,%.6f,%d", source->sourceKey, desSiteNum, desSig,
							desField->reverseBearing, desField->bearing, desERP, desField->status);
					}
				}
			}

			if ((outFile = OutputFile[CELL_FILE_PAIRSTUDY])) {
				if (startPointPair) {
					fprintf(outFile, "[point]\n%d,%d,%d,%.12f,%.12f,%.8f,%d\n", point->cellLatIndex,
						point->cellLonIndex, point->countryKey, point->latitude, point->longitude, point->b.area,
						point->a.population);
					startPointPair = 0;
				}
				fprintf(outFile, "D,%d,%d", source->facility_id, source->channel);
			}

			// If a calculation error occurred on the desired, always add it to the error tally.  What happens after
			// that depends on the error-handling option; it may be considered service with no check for possible
			// interference, it may be considered no service, or the error may just be disregarded.

			hasError = 0;
			hasServiceError = 0;
			hasService = 0;
			done = 0;

			if (desField->status > 0) {

				hasServiceError = 1;
				hasError = 1;

				if (ERRORS_SERVICE == Params.ErrorHandling[countryIndex]) {
					hasService = 1;
					done = 1;
				} else {

					if (ERRORS_NOSERVICE == Params.ErrorHandling[countryIndex]) {
						done = 1;
					}
				}
			}

			// If still needed, compare the desired to the service level to determine service.  Even if no service, if
			// there is a map or cell output file, or any output file in points mode, the undesireds will be traversed
			// for the output, otherwise done.

			if (!done) {

				if (desSig >= desServ) {
					hasService = 1;
				} else {

					if (!doMapCov && !OutputFile[CELL_FILE_DETAIL] && !OutputFile[CELL_FILE_PAIRSTUDY] &&
							!OutputFile[CELL_FILE_CSV] && !OutputFile[IMAGE_FILE] && (!pointsMode ||
							(!OutputFile[REPORT_FILE_DETAIL] && !OutputFile[REPORT_FILE_SUMMARY] &&
							!OutputFile[CSV_FILE_DETAIL] && !OutputFile[CSV_FILE_SUMMARY]))) {
						done = 1;
					}
				}
			}

			if (pointsMode) {
				if ((outFile = OutputFile[REPORT_FILE_DETAIL])) {
					if (desField->b.inServiceArea) {
						fputs("  in service area", outFile);
					} else {
						fputs("  not in service area", outFile);
					}
					if (hasService) {
						fputs(", service", outFile);
					} else {
						fputs(", no service", outFile);
					}
					if (hasServiceError && done) {
						fputs(" (assumed)", outFile);
					}
					fputc('\n', outFile);
				}
				if ((outFile = OutputFile[REPORT_FILE_SUMMARY])) {
					if (desField->b.inServiceArea) {
						fputs("  in service area", outFile);
					} else {
						fputs("  not in service area", outFile);
					}
					if (hasService) {
						fputs(", service", outFile);
					} else {
						fputs(", no service", outFile);
					}
					if (hasServiceError && done) {
						fputs(" (assumed)", outFile);
					}
				}
				if ((outFile = OutputFile[CSV_FILE_DETAIL])) {
					fprintf(outFile, ",%d,%d\n", desField->b.inServiceArea, hasService);
				}
				if ((outFile = OutputFile[CSV_FILE_SUMMARY])) {
					fprintf(outFile, ",%d,%d", desField->b.inServiceArea, hasService);
				}
			}

			if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
				fprintf(outFile, ",%d\n", hasService);
			}

			if ((outFile = OutputFile[CELL_FILE_PAIRSTUDY])) {
				fprintf(outFile, ",%d\n", hasService);
			}

			if ((outFile = OutputFile[CELL_FILE_CSV_D])) {
				if (pointsMode) {
					fprintf(outFile, "\"%s\",%d,%.12f,%.12f,%.1f,%.1f,%d,%d,%s,%.6f,%.6f,%.6f,%.2f,%.2f,%.6f,%d,%d\n",
						pointInfo->pointName, point->countryKey, point->latitude, point->longitude, point->elevation,
						point->b.receiveHeight, point->clutterType, source->sourceKey, desSiteNum, desSigBase,
						desSigAdjust, desServAdjust, desField->reverseBearing, desField->bearing, desERP, hasService,
						desField->status);
				} else {
					fprintf(outFile, "%d-%d-%d,%.12f,%.12f,%.8f,%d,%d,%d,%d,%s,%.6f,%.2f,%.2f,%.6f,%d,%d\n",
						point->cellLatIndex, point->cellLonIndex, point->countryKey, point->latitude, point->longitude,
						point->b.area, point->a.population, point->households, point->clutterType, source->sourceKey,
						desSiteNum, desSig, desField->reverseBearing, desField->bearing, desERP, hasService,
						desField->status);
				}
			}

			// In an OET-74 study, interference from wireless sources to a TV desired has to be evaluated separately
			// because the error-handling logic may be different (errors are always disregarded) and the wireless
			// undesired signal is a composite of all wireless sources.  If the point is TV service and the desired
			// signal is above threshold disregarding errors, wireless interference will be checked below.  However
			// the check loop may also be traversed for file output even with no service.

			needsWireless = 0;
			hasWirelessIXError = 0;
			hasWirelessIX = 0;
			utotwl = NULL;

			if ((STUDY_TYPE_TV_OET74 == StudyType) && (RECORD_TYPE_TV == source->recordType) &&
					((hasService && (desSig >= desServ)) || doMapCov || OutputFile[CELL_FILE_DETAIL] ||
					OutputFile[CELL_FILE_CSV] || OutputFile[IMAGE_FILE] || (pointsMode &&
					(OutputFile[REPORT_FILE_DETAIL] || OutputFile[REPORT_FILE_SUMMARY] ||
					OutputFile[CSV_FILE_DETAIL])))) {
				needsWireless = 1;
			}

			// Find fields for undesired sources.  If the desired source is DTS and self-interference is being analyzed
			// also find the desired source appearing as an undesired, save a pointer to those undesired fields for
			// later analysis.

			undesireds = source->undesireds;
			undesiredCount = source->undesiredCount;
			for (undesiredIndex = 0; undesiredIndex < undesiredCount; undesiredIndex++) {
				undesireds[undesiredIndex].field = NULL;
			}
			selfIxUndFields = NULL;

			for (ufield = point->fields; ufield; ufield = ufield->next) {
				if (ufield->a.isUndesired) {
					for (undesiredIndex = 0; undesiredIndex < undesiredCount; undesiredIndex++) {
						if ((ufield->sourceKey == undesireds[undesiredIndex].sourceKey) &&
								(ufield->a.percentTime == undesireds[undesiredIndex].percentTime)) {
							if (ufield->status < 0) {
								log_error(
							"Un-calculated field: countryKey=%d latIndex=%d lonIndex=%d sourceKey=%d percentTime=%.2f",
									point->countryKey, point->cellLatIndex, point->cellLonIndex, ufield->sourceKey,
									((double)ufield->a.percentTime / 100.));
								return 1;
							}
							undesireds[undesiredIndex].field = ufield;
							break;
						}
					}
					if (source->isParent && Params.CheckSelfInterference &&
							(ufield->sourceKey == source->sourceKey) &&
							(ufield->a.percentTime == Params.SelfIxUndesiredTime)) {
						selfIxUndFields = ufield->next;
					}
				}
			}

			// Do the wireless interference analysis and/or cell file output.  Totals for wireless interference are
			// kept in globals.  Note the required D/U value is in each wireless undesired structure, but it is the
			// same value in all because all wireless sources have the same frequency and bandwidth and so the same
			// interference relationship to the desired.  Just use the D/U from the first undesired found.  Also there
			// is no distance check here.  If a wireless undesired appears in the list for a study point, it is always
			// used regardless of distance.  See discussion in find_undesired() in study.c.

			if (needsWireless) {

				undSig = 0.;

				for (undesiredIndex = 0; undesiredIndex < undesiredCount; undesiredIndex++) {

					undField = undesireds[undesiredIndex].field;
					if (!undField) {
						continue;
					}

					usource = SourceKeyIndex[undesireds[undesiredIndex].sourceKey];
					if (!usource) {
						log_error("Source structure index is corrupted");
						exit(1);
					}
					if (RECORD_TYPE_WL != usource->recordType) {
						continue;
					}

					if (0. == undSig) {
						duReqBase = undesireds[undesiredIndex].requiredDU;
						duReqAdjust = 0.;
						if (undesireds[undesiredIndex].adjustDU) {
							duReqAdjust = dtv_codu_adjust(1, desMargin);
							if (Params.WirelessCapDURamp && (duReqAdjust > Params.WirelessDURampCap)) {
								duReqAdjust = Params.WirelessDURampCap;
							}
						}
						duReq = duReqBase + duReqAdjust;
					}

					undSigBase = (double)undField->fieldStrength;
					undSigAdjust = recv_az_lookup(source, recvPat, recvOrient, (double)undField->reverseBearing,
						usource->frequency);

					// The undesired signal is a power sum of the signals from the individual wireless sources, meaning
					// it is an RSS of field strengths.  However the expression coded here is simplified.  Using 10
					// instead of 20 for the dBu conversions means the scalars are power ratios so a straight sum can
					// be done saving the explicit square and square-root operations.  Conversion to specific power
					// units is unnecessary since the result is only being converted back to dBu so the conversion
					// constants would just cancel out of the simplified expression.

					undSig += pow(10., ((undSigBase + undSigAdjust) / 10.));

					if (undField->status > 0) {
						hasWirelessIXError = 1;
						if (hasService) {
							hasError = 1;
						}
					}

					if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
						fprintf(outFile, "U,%d,%.6f,%.2f,%.6f,%d\n", usource->sourceKey, undSigBase,
							undField->reverseBearing, undSigAdjust, undField->status);
					}

					if ((outFile = OutputFile[CELL_FILE_CSV_WL_U])) {
						if (pointsMode) {
							fprintf(outFile,
								"\"%s\",%d,%.12f,%.12f,%.1f,%.1f,%d,%d,%s,%.6f,%.6f,%.6f,%d,%.6f,%.6f,%.2f,%d,%d\n",
								pointInfo->pointName, point->countryKey, point->latitude, point->longitude,
								point->elevation, point->b.receiveHeight, usource->sourceKey, source->sourceKey,
								desSiteNum, desSigBase, desSigAdjust, desServAdjust, hasService, undSigBase,
								undSigAdjust, undField->reverseBearing, desField->status, undField->status);
						} else {
							fprintf(outFile, "%d-%d-%d,%.12f,%.12f,%.8f,%d,%d,%d,%d,%s,%.6f,%d,%.6f,%.6f,%.2f,%d,%d\n",
								point->cellLatIndex, point->cellLonIndex, point->countryKey, point->latitude,
								point->longitude, point->b.area, point->a.population, point->households,
								usource->sourceKey, source->sourceKey, desSiteNum, desSig, hasService, undSigBase,
								undSigAdjust, undField->reverseBearing, desField->status, undField->status);
						}
					}
				}

				if (undSig > 0.) {

					undSig = 10. * log10(undSig);
					du = desSig - undSig;
					if (hasService && (desSig >= desServ) && (du < duReq)) {
						hasWirelessIX = 1;
					}

					wlSig = undSig;
					wlDU = du;
					wlDUMarg = du - duReq;

					if (pointsMode) {
						if ((outFile = OutputFile[REPORT_FILE_DETAIL])) {
							fprintf(outFile, "    Wireless base stations               %7.2f dBu  %7.2f dB  %7.2f dB",
								undSig, du, duReq);
							if (hasWirelessIX) {
								fputs("  interference\n", outFile);
							} else {
								fputs("  no interference\n", outFile);
							}
						}
						if ((outFile = OutputFile[CSV_FILE_DETAIL])) {
							fprintf(outFile, ",,,,,,,,,,,,,,,,,,,wireless,,,,%.6f,%.6f,%.6f,%d\n", undSig, du, duReq,
								hasWirelessIX);
						}
					}

					if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
						fprintf(outFile, "DU,%.6f,%.6f,%.6f,%.6f,%d\n", undSig, du, duReqBase, duReqAdjust,
							hasWirelessIX);
					}

					if ((outFile = OutputFile[CELL_FILE_CSV_WL_U_RSS])) {
						if (pointsMode) {
							fprintf(outFile,
								"\"%s\",%d,%.12f,%.12f,%.1f,%.1f,%d,%s,%.6f,%.6f,%.6f,%d,%.6f,%.6f,%.6f,%.6f,%d,%d\n",
								pointInfo->pointName, point->countryKey, point->latitude, point->longitude,
								point->elevation, point->b.receiveHeight, source->sourceKey, desSiteNum, desSigBase,
								desSigAdjust, desServAdjust, hasService, undSig, du, duReqBase, duReqAdjust,
								hasWirelessIX, desField->status);
						} else {
							fprintf(outFile,
								"%d-%d-%d,%.12f,%.12f,%.8f,%d,%d,%d,%s,%.6f,%d,%.6f,%.6f,%.6f,%.6f,%d,%d\n",
								point->cellLatIndex, point->cellLonIndex, point->countryKey, point->latitude,
								point->longitude, point->b.area, point->a.population, point->households,
								source->sourceKey, desSiteNum, desSig, hasService, undSig, du, duReqBase, duReqAdjust,
								hasWirelessIX, desField->status);
						}
					}

					if (gridMode) {
						utotwl = WirelessUndesiredTotals + totCountryIndex;
						utotwl->report = 1;
					}
				}
			}

			// Check DTS self-interference as needed.  The source appears again in the field list as an undesired to
			// provide the signals for this.  The site with the strongest desired signal is the desired, other sites
			// are potential undesireds.  An arrival time difference is computed between the desired and each other
			// undesired.  If that is within a time window that undesired causes no interference regardless of D/U.
			// Undesireds arriving outside the time window are summed to form a composite undesired signal which must
			// meet a minimum required D/U.  A separate points output file may be created here with details of the
			// points where self-interference is predicted.

			ixCount = 0;
			utotix = NULL;
			ixBearing = 0.;
			ixMargin = 0.;
			hasSelfIX = 0;

			if (source->isParent && Params.CheckSelfInterference && hasService && (desSig >= desServ)) {

				double undSigOne, undSigMax = -999., deltaMax = 0., undSigOther;
				int undSiteNum = 0;

				undSig = 0.;

				if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
					fputs("S", outFile);
				}

				dtime = ((double)desField->distance / Params.KilometersPerMicrosecond) + desSource->dtsTimeDelay;

				for (dtsSource = source->dtsSources, undField = selfIxUndFields; dtsSource;
						dtsSource = dtsSource->next, undField = undField->next) {

					if (dtsSource == desSource) {
						continue;
					}

					if (!undField || (undField->sourceKey != dtsSource->sourceKey) || (undField->status < 0)) {
						log_error("Missing or out-of-order fields for DTS sourceKey=%d (2)", source->sourceKey);
						return 1;
					}

					utime = ((double)undField->distance / Params.KilometersPerMicrosecond) + dtsSource->dtsTimeDelay;
					delta = utime - dtime;

					if ((delta < Params.PreArrivalTimeLimit) || (delta > Params.PostArrivalTimeLimit)) {

						undSigBase = undField->fieldStrength;
						undSigAdjust = recv_az_lookup(desSource, recvPat, recvOrient, (double)undField->reverseBearing,
							dtsSource->frequency);
						undSigOne = undSigBase + undSigAdjust;
						undSig += pow(10., (undSigOne / 10.));

						// For the self-interference points output file, keep track of the one undesired included in
						// the RSS with maximum signal.  This will be reported in the file if it turns out to be the
						// dominant source of interference.

						if (undSigOne > undSigMax) {
							undSiteNum = dtsSource->siteNumber;
							undSigMax = undSigOne;
							deltaMax = delta;
						}

						if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
							fprintf(outFile, ",%d,%.1f,%.6f,%.2f,%.6f", dtsSource->siteNumber, delta, undSigBase,
								undField->reverseBearing, undSigAdjust);
						}

					} else {

						if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
							fprintf(outFile, ",%d,%.1f,0,0,0", dtsSource->siteNumber, delta);
						}
					}
				}

				if (undSig > 0.) {

					// For the self-interference points output, compute a separate RSS of all undesireds excluding the
					// largest; see below.  There may have been only one signal in the sum.

					undSigOther = undSig - pow(10., (undSigMax / 10.));
					if (undSigOther > 0.) {
						undSigOther = 10. * log10(undSigOther);
					} else {
						undSigOther = -999.;
					}

					undSig = 10. * log10(undSig);

					du = desSig - undSig;

					duReqBase = Params.SelfIxRequiredDU;
					duReqAdjust = dtv_codu_adjust(1, desMargin);
					if (Params.CapDURamp && (duReqAdjust > Params.DURampCap)) {
						duReqAdjust = Params.DURampCap;
					}

					duReq = duReqBase + duReqAdjust;

					selfDU = du;
					selfDUMarg = du - duReq;

					utot = source->selfIxTotals + totCountryIndex;
					utot->report = 1;
					if (du < duReq) {
						hasSelfIX = 1;
						ixCount = 1;
						utot->ixPop += point->a.population;
						utot->ixHouse += point->households;
						utot->ixArea += point->b.area;
						utotix = utot;
					}

					if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
						fprintf(outFile, ",%d\nDS,%.6f,%.6f,%.6f\n", ixCount, du, duReqBase, duReqAdjust);
					}

					// If the self-interference points map file is being created, set up attributes for the output
					// (actual output is later, along with other map files).  Desired site number, desired signal,
					// undesired RSS, and population are always included.  If the one undesired with largest signal is
					// dominant, it's site number, individual signal, and arrival time delta are also include.  The
					// test for "dominant" is that the D/U would be met if that one signal is removed from the RSS.

					if (hasSelfIX && doMapCov && MapOutputFlags[MAP_OUT_SELFIX]) {

						snprintf(attrDesSig, (LEN_ATTRDB + 1), "%.*f", PREC_ATTRDB, desSig);
						snprintf(attrUndSigRSS, (LEN_ATTRDB + 1), "%.*f", PREC_ATTRDB, undSig);
						snprintf(attrPop, (LEN_POPULATION + 1), "%d", point->a.population);

						if ((desSig - undSigOther) > duReq) {
							snprintf(attrUndSiteNum, (LEN_SITENUM + 1), "%d", undSiteNum);
							snprintf(attrUndSig, (LEN_ATTRDB + 1), "%.*f", PREC_ATTRDB, undSigMax);
							snprintf(attrDeltaT, (LEN_DELTAT + 1), "%.*f", PREC_DELTAT, deltaMax);
						} else {
							lcpystr(attrUndSiteNum, "0", (LEN_SITENUM + 1));
						}
					}

				} else {

					if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
						fputs(",0\n", outFile);
					}
				}
			}

			// If still needed, loop over undesireds and check for interference from non-wireless sources.  Also this
			// loop may be traversed to generate map or cell file output even when the point is no-service.

			if (!done) {

				for (undesiredIndex = 0; undesiredIndex < undesiredCount; undesiredIndex++) {

					undField = undesireds[undesiredIndex].field;
					if (!undField) {
						continue;
					}

					usource = SourceKeyIndex[undesireds[undesiredIndex].sourceKey];
					if (!usource) {
						log_error("Source structure index is corrupted");
						exit(1);
					}

					if (RECORD_TYPE_WL == usource->recordType) {
						continue;
					}

					// Check the interference distance limit if needed, skip fields that are too far away.  When that
					// limit applies it was checked during study point setup to avoid adding undesired fields to the
					// point when possible, however it has to be checked again here in case the undesired affects more
					// than one desired at the point and so more than one limit may apply.  For a DTS, if the alternate
					// distance check is in effect this checks the smallest distance to any one of the transmitters,
					// otherwise it just checks the distance to the DTS reference point.  Also check the status and
					// order of the individual DTS field structures, abort if there is any problem.

					if (undesireds[undesiredIndex].checkIxDistance) {

						if (usource->isParent && Params.CheckIndividualDTSDistance) {

							mindist = 9999.;

							ufield = undField->next;
							for (dtsSource = usource->dtsSources; dtsSource;
									dtsSource = dtsSource->next, ufield = ufield->next) {

								if (!ufield || (ufield->sourceKey != dtsSource->sourceKey) || (ufield->status < 0)) {
									log_error("Missing or out-of-order fields for DTS sourceKey=%d (3)",
										usource->sourceKey);
									return 1;
								}

								if (ufield->distance < mindist) {
									mindist = ufield->distance;
								}
							}

						} else {

							mindist = undField->distance;
						}

						if (mindist > undesireds[undesiredIndex].ixDistance) {
							continue;
						}
					}

					// Set up for the analysis.

					hasIXError = 0;
					hasIX = 0;
					duMargin = 0.;
					done = 0;

					// The undesired field is attenuated by the receive antenna pattern.  For DTS, the undesired signal
					// is the power sum (RSS) of the individual sources, each of course attenuated differently by the
					// receive pattern.  However if the alternate distance check is in effect, each distance must be
					// checked again here and only those individual transmitters within the distance are included in
					// the sum.  Placeholders for those not used are written to the cell file as needed so the format
					// is consistent.  If any DTS source used has an error, depending on error handling that signal
					// may be excluded from the RSS.  But as long as at least one signal passes all the tests there
					// will be an aggregate result that is considered error-free.

					if (usource->isParent) {

						if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
							fprintf(outFile, "U,%d", usource->sourceKey);
						}

						undSig = 0.;
						double undSigAll = 0.;

						ufield = undField->next;
						for (dtsSource = usource->dtsSources; dtsSource;
								dtsSource = dtsSource->next, ufield = ufield->next) {

							if (!ufield || (ufield->sourceKey != dtsSource->sourceKey) || (ufield->status < 0)) {
								log_error("Missing or out-of-order fields for DTS sourceKey=%d (4)",
									usource->sourceKey);
								return 1;
							}

							if (!Params.CheckIndividualDTSDistance ||
									(ufield->distance <= undesireds[undesiredIndex].ixDistance)) {

								undSigBase = ufield->fieldStrength;
								undSigAdjust = recv_az_lookup(source, recvPat, recvOrient,
									(double)ufield->reverseBearing, dtsSource->frequency);
								if ((0 == ufield->status) || (ERRORS_IGNORE == Params.ErrorHandling[countryIndex])) {
									undSig += pow(10., ((undSigBase + undSigAdjust) / 10.));
								}
								undSigAll += pow(10., ((undSigBase + undSigAdjust) / 10.));

								if (ufield->status > 0) {
									if (hasService) {
										hasError = 1;
									}
								}

								if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
									fprintf(outFile, ",%d,%.6f,%.2f,%.6f,%d", dtsSource->siteNumber,
										undSigBase, ufield->reverseBearing, undSigAdjust, ufield->status);
								}

							} else {

								if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
									fprintf(outFile, ",%d,-999,0,0,0", dtsSource->siteNumber);
								}
							}
						}

						if (undSig > 0.) {
							undSig = 10. * log10(undSig);
						} else {
							hasIXError = 1;
							undSig = undSigAll;
						}

					} else {

						undSigBase = undField->fieldStrength;
						undSigAdjust = recv_az_lookup(source, recvPat, recvOrient, (double)undField->reverseBearing,
							usource->frequency);
						undSig = undSigBase + undSigAdjust;

						if (undField->status > 0) {
							hasIXError = 1;
							if (hasService) {
								hasError = 1;
							}
						}

						if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
							fprintf(outFile, "U,%d,%.6f,%.2f,%.6f,%d", usource->sourceKey, undSigBase,
								undField->reverseBearing, undSigAdjust, undField->status);
						}
					}

					// Determine the D/U and the required (minimum) value for no interference.

					du = desSig - undSig;

					// For an FM NCE undesired to a TV desired in an FM vs. TV channel 6 study the required D/U may not
					// have been pre-determined because it may vary with the desired TV signal level.  Curves from FCC
					// rules, 47 CFR 73.525, which vary both by FM channel and by TV desired signal level are used to
					// look up the D/U.  This method will always be used for an analog TV, and may also be used for
					// DTV based on a parameter setting.  Since the curves were developed for analog TV, the DTV signal
					// may fall below the low-signal end of the curves, in which case extrapolation will occur.  Also
					// extrapolation may occur for analog TV if the service level currently set fis less than the curve
					// minimum.  If the lookup is off the high-signal end of the curves there is no extrapolation, the
					// last value is used.  This method may not be used for DTV, there is an alternate method using
					// fixed D/U values, but in that case the D/U was set in find_undesireds().

					if (undesireds[undesiredIndex].computeTV6FMDU) {

						int ci = usource->channel - TV6_FM_CHAN_BASE;
						if (ci < 0) {
							ci = 0;
						}
						if (ci >= TV6_FM_CHAN_COUNT) {
							ci = TV6_FM_CHAN_COUNT - 1;
						}

						int si = (int)(desSig - TV6_FM_CURVE_MIN);
						if (si >= (TV6_FM_CURVE_COUNT - 1)) {
							duReqBase = Params.TV6FMCurves[((TV6_FM_CURVE_COUNT - 1) * TV6_FM_CHAN_COUNT) + ci];
						} else {
							if (si < 0) {
								si = 0;
							}
							double sig0 = TV6_FM_CURVE_MIN + (double)si;
							double sig1 = sig0 + TV6_FM_CURVE_STEP;
							double du0 = Params.TV6FMCurves[(si * TV6_FM_CHAN_COUNT) + ci];
							double du1 = Params.TV6FMCurves[((si + 1) * TV6_FM_CHAN_COUNT) + ci];
							duReqBase = du0 + ((du1 - du0) * ((desSig - sig0) / (sig1 - sig0)));
						}

						duReqAdjust = 0.;

					// For any other case the D/U was set in find_undesireds() from interference rules or lookup
					// tables.  However the D/U may be adjusted for a TV/DTV undesired into a DTV desired based on the
					// desired signal level, see dtv_codu_adjust().

					} else {

						duReqBase = undesireds[undesiredIndex].requiredDU;
						duReqAdjust = 0.;

						if (undesireds[undesiredIndex].adjustDU) {
							duReqAdjust = dtv_codu_adjust(usource->dtv, desMargin);
							if (Params.CapDURamp && (duReqAdjust > Params.DURampCap)) {
								duReqAdjust = Params.DURampCap;
							}
						}
					}

					duReq = duReqBase + duReqAdjust;

					// Error handling logic similar to desired; an error here may mean there is no interference, there
					// is interference, or the error may be disregarded and the D/U tested as usual.

					if (hasIXError) {

						if (ERRORS_SERVICE == Params.ErrorHandling[countryIndex]) {
							done = 1;
						} else {

							if (ERRORS_NOSERVICE == Params.ErrorHandling[countryIndex]) {
								hasIX = hasService;
								done = 1;
							}
						}
					}

					// If needed, compare the D/U to the adjusted requirement to determine if interference is present.
					// If the rule has signal threshold conditions the desired and undesired must be greater than the
					// respective thresholds for the rule to apply, see find_undesired() in source.c.

					if (!done) {

						if (((0. == undesireds[undesiredIndex].duThresholdD) ||
									(desSig >= undesireds[undesiredIndex].duThresholdD)) &&
								((0. == undesireds[undesiredIndex].duThresholdU) ||
									(undSig >= undesireds[undesiredIndex].duThresholdU))) {

							duMargin = du - duReq;

							if (duMargin < worstDUMarg) {
								worstDUSrcKey = usource->sourceKey;
								worstDU = du;
								worstDUMarg = duMargin;
							}

							if (hasService && (du < duReq)) {
								hasIX = 1;
							}

							if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
								fprintf(outFile, ",%d\nDU,%.6f,%.6f,%.6f\n", hasIX, du, duReqBase, duReqAdjust);
							}

						} else {

							if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
								fprintf(outFile, ",%d\n", hasIX);
							}
						}

					} else {

						if ((outFile = OutputFile[CELL_FILE_DETAIL])) {
							fprintf(outFile, ",%d\n", hasIX);
						}
					}

					if (pointsMode) {
						if ((outFile = OutputFile[REPORT_FILE_DETAIL])) {
							fprintf(outFile, "    %-35.35s  %7.2f dBu  %7.2f dB  %7.2f dB", source_label(usource),
								undSig, du, duReq);
							if (hasIX) {
								fputs("  interference", outFile);
							} else {
								fputs("  no interference", outFile);
							}
							if (hasIXError && done) {
								fputs(" (assumed)", outFile);
							}
							fputc('\n', outFile);
						}
						if ((outFile = OutputFile[CSV_FILE_DETAIL])) {
							fprintf(outFile, ",,,,,,,,,,,,,,,,,%d,%d,\"%s\",\"%s\",\"%s\",\"%s\",%.6f,%.6f,%.6f,%d\n",
								usource->facility_id, usource->channel, usource->callSign, usource->fileNumber,
								usource->city, usource->state, undSig, du, duReq, hasIX);
						}
					}

					if (((outFile = OutputFile[CELL_FILE_PAIRSTUDY])) && hasIX) {
						fprintf(outFile, "U,%d,%d\n", usource->facility_id, usource->channel);
					}

					if ((outFile = OutputFile[CELL_FILE_CSV_U])) {

						if (usource->isParent) {

							ufield = undField->next;
							for (dtsSource = usource->dtsSources; dtsSource;
									dtsSource = dtsSource->next, ufield = ufield->next) {

								if (!Params.CheckIndividualDTSDistance ||
										(ufield->distance <= undesireds[undesiredIndex].ixDistance)) {

									undSigBase = ufield->fieldStrength;
									undSigAdjust = recv_az_lookup(source, recvPat, recvOrient,
										(double)ufield->reverseBearing, dtsSource->frequency);

									if (pointsMode) {
										fprintf(outFile,
			"\"%s\",%d,%.12f,%.12f,%.1f,%.1f,%d,%d,%d,%s,%.6f,%.6f,%.6f,%d,%.6f,%.6f,%.2f,%.6f,%.6f,%.6f,%d,%d,%d\n",
											pointInfo->pointName, point->countryKey, point->latitude, point->longitude,
											point->elevation, point->b.receiveHeight, usource->sourceKey,
											dtsSource->siteNumber, source->sourceKey, desSiteNum, desSigBase,
											desSigAdjust, desServAdjust, hasService, undSigBase, undSigAdjust,
											ufield->reverseBearing, du, duReqBase, duReqAdjust, hasIX,
											desField->status, ufield->status);
									} else {
										fprintf(outFile,
						"%d-%d-%d,%.12f,%.12f,%.8f,%d,%d,%d,%d,%d,%s,%.6f,%d,%.6f,%.6f,%.2f,%.6f,%.6f,%.6f,%d,%d,%d\n",
											point->cellLatIndex, point->cellLonIndex, point->countryKey,
											point->latitude, point->longitude, point->b.area, point->a.population,
											point->households, usource->sourceKey, dtsSource->siteNumber,
											source->sourceKey, desSiteNum, desSig, hasService, undSigBase,
											undSigAdjust, ufield->reverseBearing, du, duReqBase, duReqAdjust, hasIX,
											desField->status, ufield->status);
									}
								}
							}

						} else {

							if (pointsMode) {
								fprintf(outFile,
				"\"%s\",%d,%.12f,%.12f,%.1f,%.1f,%d,,%d,%s,%.6f,%.6f,%.6f,%d,%.6f,%.6f,%.2f,%.6f,%.6f,%.6f,%d,%d,%d\n",
									pointInfo->pointName, point->countryKey, point->latitude, point->longitude,
									point->elevation, point->b.receiveHeight, usource->sourceKey, source->sourceKey,
									desSiteNum, desSigBase, desSigAdjust, desServAdjust, hasService, undSigBase,
									undSigAdjust, undField->reverseBearing, du, duReqBase, duReqAdjust, hasIX,
									desField->status, undField->status);
							} else {
								fprintf(outFile,
						"%d-%d-%d,%.12f,%.12f,%.8f,%d,%d,%d,,%d,%s,%.6f,%d,%.6f,%.6f,%.2f,%.6f,%.6f,%.6f,%d,%d,%d\n",
									point->cellLatIndex, point->cellLonIndex, point->countryKey, point->latitude,
									point->longitude, point->b.area, point->a.population, point->households,
									usource->sourceKey, source->sourceKey, desSiteNum, desSig, hasService, undSigBase,
									undSigAdjust, undField->reverseBearing, du, duReqBase, duReqAdjust, hasIX,
									desField->status, undField->status);
							}
						}
					}

					// Add to the interference totals for this undesired.  Also set up for adding to the unique IX
					// total for this undesired, and for the IX margin processing features.  Margin information is
					// placed in the coverage map file and accumulated for the margin CSV file only if unique IX is
					// found from a specific undesired.

					if (gridMode) {

						utot = undesireds[undesiredIndex].totals + totCountryIndex;
						utot->report = 1;

						if (hasService && hasIXError) {
							utot->errorPop += point->a.population;
							utot->errorHouse += point->households;
							utot->errorArea += point->b.area;
						}

						if (hasIX) {

							utot->ixPop += point->a.population;
							utot->ixHouse += point->households;
							utot->ixArea += point->b.area;

							if (1 == ++ixCount) {
								utotix = utot;
								if (IXMarginSourceKey == usource->sourceKey) {
									ixBearing = undField->bearing;
									ixMargin = duMargin;
								}
							} else {
								utotix = NULL;
								ixBearing = 0.;
								ixMargin = 0.;
							}
						}
					}

					if (pointsMode && hasIX) {
						++ixCount;
					}
				}
			}

			// Tally the desired service and interference totals, and also undesired wireless interference totals.  If
			// non-wireless interference was from just one undesired, also add to that undesired's unique interference
			// total.  Note wireless interference is not considered with respect to the unique interference totals for
			// non-wireless undesireds; wireless interference does not mask TV interference.  However TV interference
			// does mask wireless interference so is considered for the wireless unique interference total.

			if (gridMode) {

				if (hasServiceError) {
					dtot->errorPop += point->a.population;
					dtot->errorHouse += point->households;
					dtot->errorArea += point->b.area;
				}

				if (hasService) {

					dtot->servicePop += point->a.population;
					dtot->serviceHouse += point->households;
					dtot->serviceArea += point->b.area;

					if (hasWirelessIXError && utotwl) {
						utotwl->errorPop += point->a.population;
						utotwl->errorHouse += point->households;
						utotwl->errorArea += point->b.area;
					}

					if (ixCount || hasWirelessIX) {

						if (utotix) {
							utotix->uniqueIxPop += point->a.population;
							utotix->uniqueIxHouse += point->households;
							utotix->uniqueIxArea += point->b.area;
						}

						if (hasWirelessIX && utotwl) {

							utotwl->ixPop += point->a.population;
							utotwl->ixHouse += point->households;
							utotwl->ixArea += point->b.area;

							if (!ixCount) {
								utotwl->uniqueIxPop += point->a.population;
								utotwl->uniqueIxHouse += point->households;
								utotwl->uniqueIxArea += point->b.area;
							}
						}

					} else {

						dtot->ixFreePop += point->a.population;
						dtot->ixFreeHouse += point->households;
						dtot->ixFreeArea += point->b.area;
					}
				}
			}

			// Update the margin envelope for CSV file output as needed.

			if (OutputFlags[IXCHK_MARG_CSV] && (ixMargin < 0.) && ((point->a.population > 0) ||
					(IXCHK_MARG_CSV_AGG == OutputFlags[IXCHK_MARG_CSV]) ||
					(IXCHK_MARG_CSV_ALL == OutputFlags[IXCHK_MARG_CSV]))) {
				if ((IXCHK_MARG_CSV_AGG == OutputFlags[IXCHK_MARG_CSV]) ||
						(IXCHK_MARG_CSV_AGGNO0 == OutputFlags[IXCHK_MARG_CSV])) {
					i = (int)ixBearing;
					if (ixMargin < IXMarginDb[i]) {
						IXMarginDb[i] = ixMargin;
					}
					IXMarginPop[i] += point->a.population;
				} else {
					i = IXMarginCount++;
					if (i >= IXMarginMaxCount) {
						IXMarginMaxCount = i + 1000;
						IXMarginAz = (double *)mem_realloc(IXMarginAz, (IXMarginMaxCount * sizeof(double)));
						IXMarginDb = (double *)mem_realloc(IXMarginDb, (IXMarginMaxCount * sizeof(double)));
						IXMarginPop = (int *)mem_realloc(IXMarginPop, (IXMarginMaxCount * sizeof(int)));
					}
					IXMarginAz[i] = ixBearing;
					IXMarginDb[i] = ixMargin;
					IXMarginPop[i] = point->a.population;
				}
			}

			// Determine the final result status of this desired at the point.

			result = RESULT_NOSERVICE;
			if (hasService) {
				if (ixCount || hasWirelessIX) {
					result = RESULT_INTERFERE;
				} else {
					result = RESULT_COVERAGE;
				}
			}

			// If compositing coverage, extract current value from the points map and update as needed.

			if (DoComposite) {

				latMapIndex = (point->cellLatIndex - PointMapBounds.southLatIndex) / CellLatSize;
				lonMapIndex = (point->cellLonIndex - PointMapEastLonIndex[latMapIndex]) /
					PointMapLonSizes[latMapIndex];
				pointMapIndex = (latMapIndex * PointMapLonCount) + lonMapIndex;

				// Bit flag for the option to exclude no-population points from output later, see do_write_compcov().
				// Only one flag is needed even for cells with multiple points because a cell can only have multiple
				// points when each represents population from a different country, hence all will have population.
				// When a cell contains no population it has only one point.

				if (point->a.population > 0) {
					PointMap[pointMapIndex] |= POINTMAP_HASPOP_BIT;
				}

				shift = PointMapBitShift[point->countryKey - 1];

				mask = 0x03 << shift;
				compResult = (PointMap[pointMapIndex] & mask) >> shift;

				if (result != compResult) {

					dtot = CompositeTotals + totCountryIndex;
					updateResult = 0;

					switch (compResult) {

						case RESULT_UNKNOWN: {
							if (dtot->contourPop >= 0) {
								if (SERVAREA_NO_BOUNDS == desSource->serviceAreaMode) {
									dtot->contourPop = -1;
									dtot->contourHouse = -1;
								} else {
									dtot->contourPop += point->a.population;
									dtot->contourHouse += point->households;
								}
							}
							dtot->contourArea += point->b.area;
							if (RESULT_NOSERVICE != result) {
								dtot->servicePop += point->a.population;
								dtot->serviceHouse += point->households;
								dtot->serviceArea += point->b.area;
								if (RESULT_COVERAGE == result) {
									dtot->ixFreePop += point->a.population;
									dtot->ixFreeHouse += point->households;
									dtot->ixFreeArea += point->b.area;
								}
							}
							updateResult = 1;
							break;
						}

						case RESULT_NOSERVICE: {
							dtot->servicePop += point->a.population;
							dtot->serviceHouse += point->households;
							dtot->serviceArea += point->b.area;
							if (RESULT_COVERAGE == result) {
								dtot->ixFreePop += point->a.population;
								dtot->ixFreeHouse += point->households;
								dtot->ixFreeArea += point->b.area;
							}
							updateResult = 1;
							break;
						}

						case RESULT_INTERFERE: {
							if (RESULT_COVERAGE == result) {
								dtot->ixFreePop += point->a.population;
								dtot->ixFreeHouse += point->households;
								dtot->ixFreeArea += point->b.area;
								updateResult = 1;
							}
							break;
						}
					}

					if (updateResult) {
						PointMap[pointMapIndex] = (PointMap[pointMapIndex] & ~mask) | (unsigned char)(result << shift);
					}
				}
			}

			// Output to cell file in summary format and to coverage map file as needed.

			if (hasError) {
				switch (result) {
					case RESULT_COVERAGE:
						resultStr = "11";
						break;
					case RESULT_INTERFERE:
						resultStr = "12";
						break;
					case RESULT_NOSERVICE:
						resultStr = "13";
						break;
					default:
						resultStr = "0";
						break;
				}
			} else {
				switch (result) {
					case RESULT_COVERAGE:
						resultStr = "1";
						break;
					case RESULT_INTERFERE:
						resultStr = "2";
						break;
					case RESULT_NOSERVICE:
						resultStr = "3";
						break;
					default:
						resultStr = "0";
						break;
				}
			}

			if (pointsMode) {
				if ((outFile = OutputFile[REPORT_FILE_SUMMARY])) {
					if (hasService) {
						if (ixCount || hasWirelessIX) {
							fputs(", interference", outFile);
						} else {
							fputs(", no interference", outFile);
						}
					}
					fputc('\n', outFile);
				}
				if ((outFile = OutputFile[CSV_FILE_SUMMARY])) {
					if (hasService) {
						if (ixCount || hasWirelessIX) {
							fputs(",1", outFile);
						} else {
							fputs(",0", outFile);
						}
					}
					fputc('\n', outFile);
				}
			}

			if ((outFile = OutputFile[CELL_FILE_SUMMARY])) {
				if (dsource) {
					fprintf(outFile, "R,%d,%d,%d,%.8f,%d,%d,%d,%s\n", point->countryKey, point->cellLatIndex,
						point->cellLonIndex, point->b.area, point->a.population, point->households, point->clutterType,
						resultStr);
				} else {
					if (gridMode && startCellSum) {
						fprintf(outFile, "[cell]\n%d,%d\n", point->cellLatIndex, point->cellLonIndex);
						startCellSum = 0;
					}
					if (startPointSum) {
						if (pointsMode) {
							fprintf(outFile, "P,\"%s\",%d,%.1f,%.1f,%d\n", pointInfo->pointName, point->countryKey,
								point->elevation, point->b.receiveHeight, point->clutterType);
						} else {
							fprintf(outFile, "P,%d,%.8f,%d,%d,%d\n", point->countryKey, point->b.area,
								point->a.population, point->households, point->clutterType);
						}
						startPointSum = 0;
					}
					fprintf(outFile, "R,%d,%s,%s\n", source->sourceKey, desSiteNum, resultStr);
				}
			}

			// Output to coverage map file and image may be skipped in grid mode if this is a no-service or
			// no-population point.  Always output all points in points mode.

			if (pointsMode || (((RESULT_NOSERVICE != result) || !MapOutputFlags[MAP_OUT_NOSERV]) &&
					((point->a.population > 0) || !MapOutputFlags[MAP_OUT_NOPOP]))) {

				if (doMapCov) {

					attrData[resultAttr] = resultStr;

					if ((IXMarginSourceKey > 0) && (ixMargin < 0.)) {
						snprintf(attrIXBear, (LEN_IXBEARING + 1), "%.*f", PREC_IXBEARING, ixBearing);
						snprintf(attrIXMarg, (LEN_ATTRDB + 1), "%.*f", PREC_ATTRDB, ixMargin);
					}
					if (MapOutputFlags[MAP_OUT_DESINFO]) {
						snprintf(attrDesSig, (LEN_ATTRDB + 1), "%.*f", PREC_ATTRDB, desSig);
						snprintf(attrDesMarg, (LEN_ATTRDB + 1), "%.*f", PREC_ATTRDB, desMargin);
					}
					if ((MapOutputFlags[MAP_OUT_UNDINFO]) && (worstDUSrcKey > 0)) {
						snprintf(attrWorstDUSrcKey, (LEN_SOURCEKEY + 1), "%d", worstDUSrcKey);
						snprintf(attrWorstDU, (LEN_ATTRDB + 1), "%.*f", PREC_ATTRDB, worstDU);
						snprintf(attrWorstDUMarg, (LEN_ATTRDB + 1), "%.*f", PREC_ATTRDB, worstDUMarg);
					}
					if ((MapOutputFlags[MAP_OUT_WLINFO]) && (wlSig < 999.)) {
						snprintf(attrWLSig, (LEN_ATTRDB + 1), "%.*f", PREC_ATTRDB, wlSig);
						snprintf(attrWLDU, (LEN_ATTRDB + 1), "%.*f", PREC_ATTRDB, wlDU);
						snprintf(attrWLDUMarg, (LEN_ATTRDB + 1), "%.*f", PREC_ATTRDB, wlDUMarg);
					}
					if ((MapOutputFlags[MAP_OUT_SELFIX]) && (selfDU < 999.)) {
						snprintf(attrSelfDU, (LEN_ATTRDB + 1), "%.*f", PREC_ATTRDB, selfDU);
						snprintf(attrSelfDUMarg, (LEN_ATTRDB + 1), "%.*f", PREC_ATTRDB, selfDUMarg);
					}
					if (MapOutputFlags[MAP_OUT_MARGIN]) {
						double marg = desMargin;
						attrData[causeAttr] = "D";
						if (worstDUMarg < marg) {
							marg = worstDUMarg;
							attrData[causeAttr] = "U";
						}
						if (wlDUMarg < marg) {
							marg = wlDUMarg;
							attrData[causeAttr] = "W";
						}
						if (selfDUMarg < marg) {
							marg = selfDUMarg;
							attrData[causeAttr] = "S";
						}
						snprintf(attrMarg, (LEN_ATTRDB + 1), "%.*f", PREC_ATTRDB, marg);
					}
					if ((MapOutputFlags[MAP_OUT_RAMP]) && source->dtv) {
						snprintf(attrRamp, (LEN_ATTRDB + 1), "%.*f", PREC_ATTRDB, dtv_codu_adjust(1, desMargin));
					}

					if (gridMode && MapOutputFlags[MAP_OUT_CENTER]) {
						ptlat = ((double)point->cellLatIndex / 3600.) + ((double)CellLatSize / 7200.);
						if (GRID_TYPE_GLOBAL == Params.GridType) {
							ptlon = ((double)point->cellLonIndex / 3600.) +
								((double)CellLonSizes[(point->cellLatIndex - CellBounds.southLatIndex) / CellLatSize] /
								7200.);
						} else {
							ptlon = ((double)point->cellLonIndex / 3600.) + ((double)CellLonSize / 7200.);
						}
					} else {
						ptlat = point->latitude;
						ptlon = point->longitude;
					}

					if ((mapFile = MapOutputFile[MAP_OUT_SHAPE_COV])) {
						write_shape(mapFile, ptlat, ptlon, NULL, 0, NULL, attrData);
					}

					if ((mapFile = MapOutputFile[MAP_OUT_KML_COV])) {
						write_shape(mapFile, ptlat, ptlon, NULL, 0, NULL, attrData);
					}

					if (hasSelfIX && MapOutputFlags[MAP_OUT_SELFIX]) {

						if ((mapFile = MapOutputFile[MAP_OUT_SHAPE_SELFIX])) {
							write_shape(mapFile, ptlat, ptlon, NULL, 0, NULL, attrSelfIX);
						}

						if ((mapFile = MapOutputFile[MAP_OUT_KML_SELFIX])) {
							write_shape(mapFile, ptlat, ptlon, NULL, 0, NULL, attrSelfIX);
						}
					}
				}

				// If creating an image file, accumulate the smallest value of the selected quantity.  The image output
				// should not be active unless there is only one desired station so only one field should be seen here,
				// however there may be multiple points in the cell; always map the worst case.

				if ((outFile = OutputFile[IMAGE_FILE])) {
					doImage = 1;
					switch (MapOutputFlags[MAP_OUT_IMAGE]) {
						case MAP_OUT_IMAGE_DMARG: {
							if (desMargin < imageVal) {
								imageVal = desMargin;
							}
							break;
						}
						case MAP_OUT_IMAGE_DUMARG: {
							if (worstDUMarg < imageVal) {
								imageVal = worstDUMarg;
							}
							break;
						}
						case MAP_OUT_IMAGE_WLDUMARG: {
							if (wlDUMarg < imageVal) {
								imageVal = wlDUMarg;
							}
							break;
						}
						case MAP_OUT_IMAGE_SELFDUMARG: {
							if (selfDUMarg < imageVal) {
								imageVal = selfDUMarg;
							}
							break;
						}
						case MAP_OUT_IMAGE_MARG: {
							if (desMargin < imageVal) {
								imageVal = desMargin;
							}
							if (worstDUMarg < imageVal) {
								imageVal = worstDUMarg;
							}
							if (wlDUMarg < imageVal) {
								imageVal = wlDUMarg;
							}
							if (selfDUMarg < imageVal) {
								imageVal = selfDUMarg;
							}
							break;
						}
						case MAP_OUT_IMAGE_DMARGNOIX: {
							if (RESULT_INTERFERE == result) {
								imageVal = -999.;
							} else {
								if (desMargin < imageVal) {
									imageVal = desMargin;
								}
							}
							break;
						}
					}
				}
			}
		}

		if (((outFile = OutputFile[CELL_FILE_PAIRSTUDY])) && !startPointPair) {
			fputs("[endpoint]\n", outFile);
		}

		if (pointsMode) {
			if ((outFile = OutputFile[REPORT_FILE_DETAIL])) {
				fputc('\n', outFile);
			}
			if ((outFile = OutputFile[REPORT_FILE_SUMMARY])) {
				fputc('\n', outFile);
			}
		}
	}

	if (((outFile = OutputFile[CELL_FILE_DETAIL])) && !startCell) {
		fputs("[endcell]\n", outFile);
	}

	if (((outFile = OutputFile[CELL_FILE_SUMMARY])) && !startCellSum) {
		fputs("[endcell]\n", outFile);
	}

	// If creating an image file and this point had data, look up the color values, write PostScript to render the
	// current cell.  This assumes all points in the list are in the same cell; the image output option should not be
	// active unless that is true.  A 999 value indicating no data will only occur when mapping a D/U, that means
	// there was no undesired; in that case always show the top color.

	if (doImage) {

		outFile = OutputFile[IMAGE_FILE];

		double colorR, colorG, colorB;
		if (imageVal < 999.) {
			colorR = MapImageBackgroundR;
			colorG = MapImageBackgroundG;
			colorB = MapImageBackgroundB;
			for (i = (MapImageColorCount - 1); i >= 0; i--) {
				if (imageVal >= MapImageLevels[i]) {
					colorR = MapImageColorR[i];
					colorG = MapImageColorG[i];
					colorB = MapImageColorB[i];
					break;
				}
			}
		} else {
			i = MapImageColorCount - 1;
			colorR = MapImageColorR[i];
			colorG = MapImageColorG[i];
			colorB = MapImageColorB[i];
		}

		int cellLonSize = CellLonSize;
		if (GRID_TYPE_GLOBAL == Params.GridType) {
			cellLonSize = CellLonSizes[(points->cellLatIndex - CellBounds.southLatIndex) / CellLatSize];
		}

		fprintf(outFile, "%.3f %.3f %.3f %d %d -%d %d Draw\n", colorR, colorG, colorB, cellLonSize, CellLatSize,
			points->cellLonIndex, points->cellLatIndex);
	}

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Routine to compute and return the adjustment to the required D/U for co-channel protection to a DTV when the desired
// field strength is close to the noise-limited service level.  The adjustment value is added to the pre-defined
// required D/U for "strong signal" ("low noise") conditions, forcing the undesired field to be weaker when the
// desired field is closer to the noise-limited service level (i.e. has a smaller S/N).

// This routine is based on FORTRAN code obtained from the FCC OET on February 23, 1998.  The FCC Rules imply there
// should be a "cut-off" limit when the relative S/N is above a threshold, however, the FCC software does not implement
// such a feature; it will compute and apply a D/U adjustment, which may be very small or 0., regardless of the value
// of the relative S/N.

// Arguments:

//   udtv    True if undesired is digital, false if analog.
//   relsnr  Difference between desired field strength and the desired service threshold.

// Return D/U adjustment in dB.

static double dtv_codu_adjust(int udtv, double relsnr) {

	static double n_adj[10] = {
		30.00, 18.75, 16.50, 15.25,  6.00,  3.50,  2.50,  1.75,  1.25,  0.00
	};

	double adj;

	// Compute adjustment.  If the undesired is DTV, the adjustment is a function of the relative S/N, this is derived
	// theoretically under the assumption that the interfering DTV signal behaves just like additional noise, hence at
	// any point the following relationship must be satisfied:
	//                               D
	//                         R = -----
	//                             U + N
	// where D is the desired signal, U is undesired, N is noise, and R is the "critical" value for service, that is
	// the S/N at the noise-limited threshold or the required D/U where the desired signal is strong and noise is not a
	// factor (these are the same value).

	if (udtv) {

		double x = relsnr / 10.;
		if (x < 0.0001) {
			x = 0.0001;
		}
		adj = 10. * log10(1. / (1. - pow(10., -x)));

	// If the undesired is NTSC, the interfering signal does not appear as more noise, it has a much more complex
	// effect.  The correction in this case is based on table-lookup from data determined empirically by the ATTC in
	// the original Grand Alliance system tests in October, 1995.

	} else {

		int i = (int)relsnr;
		if (i < 0) {
			i = 0;
		}
		if (i < 8) {
			adj = n_adj[i] + ((relsnr - (double)i) * (n_adj[i + 1] - n_adj[i]));
		} else {
			adj = n_adj[8] + (((relsnr - 8.) / 16.) * (n_adj[9] - n_adj[8]));
			if (adj < 0.) {
				adj = 0.;
			}
		}
	}

	// Done.

	return(adj);
}
