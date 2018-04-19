//
//  source.c
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.


// Functions for loading and manipulating source records and scenario lists from a study database.


#include "tvstudy.h"
#include <termios.h>

//---------------------------------------------------------------------------------------------------------------------

static void free_source(SOURCE *source);
static int source_setup();
static int do_height_checks(SOURCE *source);
static int do_source_update(SOURCE *source);
static char *sql_escape(char *fr);
static int do_replication(SOURCE *source);
static int do_mapfiles();
static int write_source_shapes(SOURCE *source, MAPFILE *mapSrcs, MAPFILE *mapConts, int desiredSourceKey,
	double cullingDistance);
static int do_service_area(SOURCE *source);
static int do_service_contour(SOURCE *source);
static CONTOUR *project_lr_contour(SOURCE *source, int mode, double arg, double contourLevel);
static int do_service_geography(SOURCE *source);
static int find_undesired(SOURCE *source);
static int check_distance(SOURCE *source, SOURCE *usource, double ruleDist);
static int check_service_area(SOURCE *source, SOURCE *usource, double cullDist);
static void set_rule_extra_distance(SOURCE *source);

// Public globals.

SOURCE *Sources = NULL;           // Array of source records from database, see source.c.
int SourceCount = 0;              // Size of Sources array.
SOURCE **SourceKeyIndex = NULL;   // Reverse-lookup of sources by sourceKey.
int SourceIndexSize = 0;          // Size of index array (maximum key value plus one).
int SourceIndexMaxSize = 0;       // Allocated size of index; it never gets smaller, beyond current end is always NULL.

int ScenarioKey = 0;             // Scenario key and name.
char ScenarioName[MAX_STRING];

// Private globals.

static FILE *ContourDebug = NULL;


//---------------------------------------------------------------------------------------------------------------------
// Load and pre-process sources for a study.  This will load all source records from the database, check the cache
// state for all and clear the cache for any that need update or have a mis-match to cache contents, and also do any
// necessary replication calculations.

// Arguments:

//   studyNeedsUpdate  True if the needs_update flag is set on the study.

// Return is <0 for major error, >0 for minor error, 0 for no error.

int load_sources(int studyNeedsUpdate) {

	static int initForStudyKey = 0;

	if (!StudyKey) {
		log_error("load_sources() called with no study open");
		return 1;
	}
	if (initForStudyKey == StudyKey) {
		return 0;
	}
	initForStudyKey = 0;

	int sourceIndex;
	SOURCE *source;

	// If debugging is on, details of HAAT lookup and contour projection are written to a debug file.

	if (Debug && !ContourDebug) {
		ContourDebug = fopen("contour_debug.dat", "wb");
	}

	// Start by releasing memory from the previous study, if any.  To do this safely the SourceKeyIndex block is
	// nulled, all secondary blocks in SOURCE structures are freed, then the Sources block is freed.  That eliminates
	// all pointers to SOURCE structures in the persistent state; those exist only in SourceKeyIndex and in other
	// SOURCE structures.  Everywhere else (i.e. FIELD structures) reference to a source is with the sourceKey, which
	// is used to retrieve the pointer from the SourceKeyIndex, and lookups always test for a null pointer from the
	// index.  Finally, when SourceKeyIndex is re-allocated it is never made smaller.  So any possible sourceKey left
	// over from previous state will always point somewhere in the index, and all pointers in the index are either
	// NULL or point to a valid current source.

	if (SourceIndexSize) {

		memset(SourceKeyIndex, 0, (SourceIndexSize * sizeof(SOURCE *)));
		SourceIndexSize = 0;

		SOURCE *source = Sources;

		for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {
			free_source(source);
		}

		mem_free(Sources);
		Sources = NULL;
		SourceCount = 0;
	}

	// First do a query to determine the necessary size of SourceKeyIndex.

	char query[MAX_QUERY];
	MYSQL_RES *myResult;
	MYSQL_ROW fields;

	snprintf(query, MAX_QUERY, "SELECT MAX(source_key) FROM %s_%d.source;", DbName, StudyKey);
	if (mysql_query(MyConnection, query)) {
		log_db_error("Source index size query failed (1)");
		return -1;
	}

	myResult = mysql_store_result(MyConnection);
	if (!myResult) {
		log_db_error("Source index size query failed (2)");
		return -1;
	}

	fields = mysql_fetch_row(myResult);
	if (!fields) {
		mysql_free_result(myResult);
		log_db_error("Source index size query failed (3)");
		return -1;
	}

	int newIndexSize = 0;
	if (fields[0]) {
		newIndexSize = atoi(fields[0]);
	}

	mysql_free_result(myResult);

	if (newIndexSize < 1) {
		log_error("No sources found for studyKey=%d", StudyKey);
		return 1;
	}

	// Currently sourceKey values are stored unsigned short because there is one in every FIELD structure and those are
	// at exactly 32 bytes right now.  Changing sourceKey to 32-bit would result in roughly a 20% increase in overall
	// memory footprint, because the FIELD structures would jump from 32 to 40 bytes and those structures represent the
	// majority of memory used for a study.  A limit of 64k sources per study should not cause any problem, but this
	// does mean the frontend apps that build the database have to make sure keys stay in range.

	if (newIndexSize > 65535) {
		log_error("Key range exceeds 16-bit integer capacity, study cannot run");
		return 1;
	}

	newIndexSize++;

	// Do the source query.  This is done in two passes to get secondary sources for DTS operations in a separate query
	// because those are not part of the main list of sources.  DTS sources do not have separate cell caches, they are
	// always attached to the identifying parent source and studied indirectly through that source.  The parent source
	// does not represent an actual operating facility, it stores identifying information and the DTS reference point
	// coordinates and maximum distance used for 73.626(c) checks.  Other sources are attached to the parent, including
	// one or more actual DTS transmitter sources, and a reference facility that provides just a contour that may limit
	// the combined service area of the individual transmitters.

	char *commonFields = "source.source_key, source.needs_update, source.site_number, source.record_type, source.facility_id, service.service_type_key, service.is_dts, source.station_class, source.is_iboc, source.channel, source.country_key, source.zone_key, source.frequency_offset_key, source.emission_mask_key, source.latitude, source.longitude, source.dts_maximum_distance, source.height_amsl, source.actual_height_amsl, source.height_agl, source.overall_haat, source.actual_overall_haat, source.peak_erp, source.contour_erp, source.iboc_fraction, source.has_horizontal_pattern, source.horizontal_pattern_orientation, source.has_vertical_pattern, source.vertical_pattern_electrical_tilt, source.vertical_pattern_mechanical_tilt, source.vertical_pattern_mechanical_tilt_orientation, source.has_matrix_pattern, source.use_generic_vertical_pattern, source.call_sign, source.sector_id, service.service_code, source.status, source.city, source.state, source.file_number, (CASE WHEN (source.user_record_id > 0) THEN CONCAT('UserRecord-', source.user_record_id) ELSE CASE WHEN (source.ext_record_id IS NOT NULL) THEN source.ext_record_id ELSE '' END END), source.antenna_id, source.horizontal_pattern_name, source.vertical_pattern_name, source.matrix_pattern_name, source.original_source_key, source.mod_count, source.service_area_mode, source.service_area_arg, source.service_area_cl, source.service_area_key, geography.mod_count, source.signal_type_key";

	int firstPass, sourceCount, parentSourceKey, sourceKey, needsUpdate, siteNumber, recordType, servRecType, len;
	SOURCE *parentSource;

	for (firstPass = 1; firstPass >= 0; firstPass--) {

		if (firstPass) {
			char *ord = "source.country_key, source.state, source.city, source.channel, source.source_key";
			if (STUDY_TYPE_TV_IX == StudyType) {
				ord = "source.country_key, source.channel, source.state, source.city, source.source_key";
			}
			snprintf(query, MAX_QUERY, "SELECT %s, source.dts_sectors FROM %s_%d.source JOIN %s.service USING (service_key) LEFT JOIN %s.geography ON (geography.geo_key=source.service_area_key) WHERE source.parent_source_key=0 ORDER BY %s;", commonFields, DbName, StudyKey, DbName, DbName, ord);
		} else {
			snprintf(query, MAX_QUERY, "SELECT %s, source.parent_source_key, source.dts_time_delay FROM %s_%d.source JOIN %s.service USING (service_key) LEFT JOIN %s.geography ON (geography.geo_key=source.service_area_key) WHERE source.parent_source_key>0 ORDER BY source.parent_source_key, source.site_number DESC, source.source_key DESC;", commonFields, DbName, StudyKey, DbName, DbName);
		}
		if (mysql_query(MyConnection, query)) {
			if (firstPass) {
				log_db_error("Source query failed (1)");
			} else {
				log_db_error("Secondary source query failed (1)");
			}
			return -1;
		}

		myResult = mysql_store_result(MyConnection);
		if (!myResult) {
			if (firstPass) {
				log_db_error("Source query failed (2)");
			} else {
				log_db_error("Secondary source query failed (2)");
			}
			return -1;
		}

		// Zero rows is not an error on the secondary source query, maybe there aren't any in this study.

		sourceCount = (int)mysql_num_rows(myResult);
		if (!sourceCount) {
			mysql_free_result(myResult);
			if (firstPass) {
				log_error("No sources found for studyKey=%d", StudyKey);
				return 1;
			} else {
				continue;
			}
		}

		// See discussion above regarding allocation of the key index.

		if (firstPass) {

			Sources = (SOURCE *)mem_zalloc(sourceCount * sizeof(SOURCE));
			SourceCount = sourceCount;

			if (newIndexSize > SourceIndexMaxSize) {
				SourceKeyIndex = (SOURCE **)mem_realloc(SourceKeyIndex, (newIndexSize * sizeof(SOURCE *)));
				memset(SourceKeyIndex, 0, (newIndexSize * sizeof(SOURCE *)));
				SourceIndexMaxSize = newIndexSize;
			}
			SourceIndexSize = newIndexSize;
		}

		for (sourceIndex = 0; sourceIndex < sourceCount; sourceIndex++) {

			hb_log();

			fields = mysql_fetch_row(myResult);
			if (!fields) {
				mysql_free_result(myResult);
				if (firstPass) {
					log_db_error("Source query failed (3)");
				} else {
					log_db_error("Secondary source query failed (3)");
				}
				return -1;
			}

			// If needs_update is set on the study record, all source records get updated regardless.

			sourceKey = atoi(fields[0]);
			if (studyNeedsUpdate) {
				needsUpdate = 1;
			} else {
				needsUpdate = atoi(fields[1]);
			}
			siteNumber = atoi(fields[2]);
			recordType = atoi(fields[3]);

			if (firstPass) {

				source = Sources + sourceIndex;

				len = strlen(fields[53]);
				if (len > 0) {
					source->dtsSectors = (char *)mem_alloc(len + 1);
					lcpystr(source->dtsSectors, fields[53], (len + 1));
				}

			} else {

				parentSourceKey = atoi(fields[53]);
				if ((parentSourceKey >= SourceIndexSize) ||
						(NULL == (parentSource = SourceKeyIndex[parentSourceKey]))) {
					mysql_free_result(myResult);
					log_error("Parent source not found for sourceKey=%d, parentSourceKey=%d", sourceKey,
						parentSourceKey);
					return 1;
				}

				// Theoretically the secondary source structure could have multiple uses but for the moment it will
				// only ever occur for a DTS operation; other code currently assumes isParent == dts.  To be completely
				// paranoid this also verifies both parent and secondary are TV records, so other code can assume that.

				if (!parentSource->dts || (RECORD_TYPE_TV != parentSource->recordType)) {
					mysql_free_result(myResult);
					log_error("Secondary source has non-DTS parent, sourceKey=%d parentSourceKey=%d", sourceKey,
						parentSourceKey);
					return 1;
				}
				if (RECORD_TYPE_TV != recordType) {
					mysql_free_result(myResult);
					log_error("Secondary source is not a TV record, sourceKey=%d", sourceKey);
					return 1;
				}

				parentSource->isParent = 1;

				source = (SOURCE *)mem_zalloc(sizeof(SOURCE));
				source->parentSource = parentSource;

				if (0 == siteNumber) {
					parentSource->dtsRefSource = source;
				} else {
					source->next = parentSource->dtsSources;
					parentSource->dtsSources = source;
				}

				// If needsUpdate is set on any secondary source it must also be set on the parent.

				if (needsUpdate) {
					parentSource->needsUpdate = 1;
				}

				source->dtsTimeDelay = atof(fields[54]);
			}

			// All sources are placed in the reverse-lookup index, including secondaries.

			SourceKeyIndex[sourceKey] = source;

			source->sourceKey = (unsigned short)sourceKey;
			source->needsUpdate = (short)needsUpdate;
			source->siteNumber = (short)siteNumber;
			source->recordType = (short)recordType;

			source->facility_id = atoi(fields[4]);

			// The service type key is translated into a separate service key and digital flag.  This also checks the
			// record type, if it is inconsistent with the service type that is an error, abort the load.

			source->serviceTypeKey = (short)atoi(fields[5]);
			source->dts = (short)atoi(fields[6]);

			switch (source->serviceTypeKey) {

				case SERVTYPE_DTV_FULL:
				default: {
					source->service = SERV_TV;
					source->dtv = 1;
					servRecType = RECORD_TYPE_TV;
					break;
				}

				case SERVTYPE_NTSC_FULL: {
					source->service = SERV_TV;
					servRecType = RECORD_TYPE_TV;
					break;
				}

				case SERVTYPE_DTV_CLASS_A: {
					source->service = SERV_TVCA;
					source->dtv = 1;
					servRecType = RECORD_TYPE_TV;
					break;
				}

				case SERVTYPE_NTSC_CLASS_A: {
					source->service = SERV_TVCA;
					servRecType = RECORD_TYPE_TV;
					break;
				}

				case SERVTYPE_DTV_LPTV: {
					source->service = SERV_TVLP;
					source->dtv = 1;
					servRecType = RECORD_TYPE_TV;
					break;
				}

				case SERVTYPE_NTSC_LPTV: {
					source->service = SERV_TVLP;
					servRecType = RECORD_TYPE_TV;
					break;
				}

				case SERVTYPE_WIRELESS: {
					source->service = SERV_WL;
					servRecType = RECORD_TYPE_WL;
					break;
				}

				case SERVTYPE_FM_FULL:
				case SERVTYPE_FM_IBOC: {
					source->service = SERV_FM;
					servRecType = RECORD_TYPE_FM;
					break;
				}

				case SERVTYPE_FM_LP: {
					source->service = SERV_FMLP;
					servRecType = RECORD_TYPE_FM;
					break;
				}

				case SERVTYPE_FM_TX: {
					source->service = SERV_FMTX;
					servRecType = RECORD_TYPE_FM;
					break;
				}
			}

			if (servRecType != recordType) {
				mysql_free_result(myResult);
				log_error("Inconsistent service and record types, sourceKey=%d", sourceKey);
				return 1;
			}

			source->fmClass = (short)atoi(fields[7]);
			source->iboc = (short)atoi(fields[8]);

			// Set band and frequency per the channel.

			source->channel = (short)atoi(fields[9]);

			switch (recordType) {

				case RECORD_TYPE_TV: {

					if (source->channel < 5) {
						source->band = BAND_VLO1;
						source->clutterBand = CLUTTER_BAND_VLO_FM;
						source->frequency = 57. + ((double)(source->channel - 2) * 6.);

					} else {
						if (source->channel < 7) {
							source->band = BAND_VLO2;
							source->clutterBand = CLUTTER_BAND_VLO_FM;
							source->frequency = 79. + ((double)(source->channel - 5) * 6.);

						} else {
							if (source->channel < 14) {
								source->band = BAND_VHI;
								source->clutterBand = CLUTTER_BAND_VHI;
								source->frequency = 177. + ((double)(source->channel - 7) * 6.);

							} else {
								if (source->channel < 38) {
									source->band = BAND_UHF;
									source->clutterBand = CLUTTER_BAND_ULO;
									source->frequency = 473. + ((double)(source->channel - 14) * 6.);

								} else {
									source->band = BAND_UHF;
									source->clutterBand = CLUTTER_BAND_UHI_WL;
									source->frequency = 473. + ((double)(source->channel - 14) * 6.);
								}
							}
						}
					}

					// Adjust NTSC to visual carrier frequency.

					if (!source->dtv) {
						source->frequency -= 1.75;
					}

					break;
				}

				// Wireless records do not have channels, and the frequency will be set from a scenario parameter.

				case RECORD_TYPE_WL: {

					source->band = BAND_WL;
					source->clutterBand = CLUTTER_BAND_UHI_WL;
					source->frequency = 0.;
					source->channel = 0;

					break;
				}

				case RECORD_TYPE_FM: {

					if (source->channel < 221) {
						source->band = BAND_FMED;
					} else {
						source->band = BAND_FM;
					}
					source->clutterBand = CLUTTER_BAND_VLO_FM;
					source->frequency = 87.9 + ((double)(source->channel - 200) * 0.2);

					break;
				}
			}

			source->countryKey = (short)atoi(fields[10]);
			source->zoneKey = (short)atoi(fields[11]);

			source->signalTypeKey = atoi(fields[52]);

			source->frequencyOffsetKey = atoi(fields[12]);

			source->emissionMaskKey = atoi(fields[13]);

			source->latitude = atof(fields[14]);
			source->longitude = atof(fields[15]);

			source->dtsMaximumDistance = atof(fields[16]);

			source->heightAMSL = atof(fields[17]);
			source->actualHeightAMSL = atof(fields[18]);
			source->heightAGL = atof(fields[19]);
			source->overallHAAT = atof(fields[20]);
			source->actualOverallHAAT = atof(fields[21]);

			// Convert peak ERP from kilowatts to dBk.  Note the contour ERP is the same value in most cases, already
			// expressed in dBk.  However for a TV replication source it may not be the same, see do_replication().

			source->peakERP = 10. * log10(atof(fields[22]));
			source->contourERP = atof(fields[23]);
			source->ibocFraction = atof(fields[24]);

			source->hasHpat = (short)atoi(fields[25]);
			source->hpatOrientation = atof(fields[26]);

			source->hasVpat = (short)atoi(fields[27]);
			source->vpatElectricalTilt = atof(fields[28]);
			source->vpatMechanicalTilt = atof(fields[29]);
			source->vpatTiltOrientation = atof(fields[30]);

			source->hasMpat = (short)atoi(fields[31]);

			source->useGeneric = (short)atoi(fields[32]);

			lcpystr(source->callSign, fields[33], CALL_SIGN_L);
			if ((RECORD_TYPE_WL == recordType) && (strlen(fields[34]) > 0)) {
				lcatstr(source->callSign, "-", CALL_SIGN_L);
				lcatstr(source->callSign, fields[34], CALL_SIGN_L);
			}
			lcpystr(source->serviceCode, fields[35], SERVICE_CODE_L);
			lcpystr(source->status, fields[36], STATUS_L);
			lcpystr(source->city, fields[37], CITY_L);
			lcpystr(source->state, fields[38], STATE_L);
			lcpystr(source->fileNumber, fields[39], FILE_NUMBER_L);
			lcpystr(source->recordID, fields[40], RECORD_ID_L);
			if (fields[41]) {
				lcpystr(source->antennaID, fields[41], ANTENNA_ID_L);
			}
			lcpystr(source->hpatName, fields[42], PAT_NAME_L);
			lcpystr(source->vpatName, fields[43], PAT_NAME_L);
			lcpystr(source->mpatName, fields[44], PAT_NAME_L);

			source->origSourceKey = (unsigned short)atoi(fields[45]);

			source->modCount = atoi(fields[46]);

			source->serviceAreaMode = (short)atoi(fields[47]);
			source->serviceAreaArg = atof(fields[48]);
			source->serviceAreaCL = atof(fields[49]);
			source->serviceAreaKey = atoi(fields[50]);
			if (fields[51]) {
				source->serviceAreaModCount = atoi(fields[51]);
			}
		}

		mysql_free_result(myResult);
	}

	// Confirm all DTS sources are parents with at least one transmitter source and a reference facility.  Strictly
	// speaking they should have at least two transmitters, but everything works with only one so not checking.

	source = Sources;
	for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {
		if (source->dts) {
			if (!source->isParent || !source->dtsSources) {
				log_error("No transmitter sources found for DTS parent sourceKey=%d", source->sourceKey);
				return 1;
			}
			if (!source->dtsRefSource) {
				log_error("No reference facility found for DTS parent sourceKey=%d", source->sourceKey);
				return 1;
			}
		}
	}

	// Do all the source pre-processing.

	int err = source_setup();
	if (err) return err;

	initForStudyKey = StudyKey;

	return 0;
}

// Free all dependent memory blocks on a source, including secondary sources for a DTS parent.

static void free_source(SOURCE *source) {

	if (source->dtsSources) {
		SOURCE *dtsSource, *nextSource;
		for (dtsSource = source->dtsSources; dtsSource; dtsSource = nextSource) {
			nextSource = dtsSource->next;
			dtsSource->next = NULL;
			free_source(dtsSource);
			mem_free(dtsSource);
		}
		source->dtsSources = NULL;
	}
	if (source->dtsRefSource) {
		free_source(source->dtsRefSource);
		mem_free(source->dtsRefSource);
		source->dtsRefSource = NULL;
	}
	if (source->dtsSectors) {
		mem_free(source->dtsSectors);
		source->dtsSectors = NULL;
	}
	if (source->hpat) {
		mem_free(source->hpat);
		source->hpat = NULL;
	}
	if (source->vpat) {
		mem_free(source->vpat);
		source->vpat = NULL;
	}
	if (source->mpat) {
		mem_free(source->mpat);
		source->mpat = NULL;
	}
	if (source->contour) {
		contour_free(source->contour);
		source->contour = NULL;
	}
	if (source->geography) {
		if (source->geography->geoKey < 0) {
			geography_free(source->geography);
		}
		source->geography = NULL;
	}
	if (source->undesireds) {
		mem_free(source->undesireds);
		source->undesireds = NULL;
	}
	if (source->selfIxTotals) {
		mem_free(source->selfIxTotals);
		source->selfIxTotals = NULL;
	}
	if (source->attributes) {
		mem_free(source->attributes);
		source->attributes = NULL;
		if (source->nAttributes > 0) {
			mem_free(source->attributeName);
			source->attributeName = NULL;
			mem_free(source->attributeValue);
			source->attributeValue = NULL;
			source->nAttributes = 0;
		}
	}
}


//---------------------------------------------------------------------------------------------------------------------
// Initial setup and cache check on all sources.  This will load source data, including patterns and possibly contour,
// from cache if possible else will clear the cache and set the source to be fully re-studied.  If that occurs, or if
// a source is already flagged for full study, this will do some preliminary setup, and will load patterns from the
// database.  All sources needing replication have that done, then all necessary database record updates are performed
// and caches are written.  Returns non-zero on any error, usual rule, <0 is fatal, >0 not so bad.

// Arguments:

//   (none)

// Return is <0 for a serious error, >0 for a minor error, 0 for no error.

static int source_setup() {

	SOURCE *source, *sources, *asource;
	int sourceIndex, err, band, countryIndex;
	double contourLevel, serviceLevel, dipoleCont = 0., dipoleServ = 0.;

	log_message("Running preliminary record checks");

	// First pass over all sources, derive values, check cache, update records, and load patterns.  Also determine
	// which secondary passes are needed below, and the number of records invovled.

	int needReplicationCount = 0, needUpdateCount = 0, needCacheCount = 0;

	hb_log_begin(SourceCount);

	source = Sources;
	for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {

		hb_log_tick();

		// Determine service contour and terrain-sensitive threshold levels, these are not necessarily the same.

		countryIndex = source->countryKey - 1;

		switch (source->band) {

			case BAND_VLO1:
			case BAND_VLO2: {
				if ((source->service == SERV_TVLP) || (source->service == SERV_TVCA)) {
					if (source->dtv) {
						contourLevel = Params.ContourVloDigitalLPTV[countryIndex];
						serviceLevel = Params.ServiceVloDigitalLPTV[countryIndex];
					} else {
						contourLevel = Params.ContourVloAnalogLPTV[countryIndex];
						serviceLevel = Params.ServiceVloAnalogLPTV[countryIndex];
					}
				} else {
					if (source->dtv) {
						contourLevel = Params.ContourVloDigital[countryIndex];
						serviceLevel = Params.ServiceVloDigital[countryIndex];
					} else {
						contourLevel = Params.ContourVloAnalog[countryIndex];
						serviceLevel = Params.ServiceVloAnalog[countryIndex];
					}
				}
				break;
			}

			case BAND_VHI: {
				if ((source->service == SERV_TVLP) || (source->service == SERV_TVCA)) {
					if (source->dtv) {
						contourLevel = Params.ContourVhiDigitalLPTV[countryIndex];
						serviceLevel = Params.ServiceVhiDigitalLPTV[countryIndex];
					} else {
						contourLevel = Params.ContourVhiAnalogLPTV[countryIndex];
						serviceLevel = Params.ServiceVhiAnalogLPTV[countryIndex];
					}
				} else {
					if (source->dtv) {
						contourLevel = Params.ContourVhiDigital[countryIndex];
						serviceLevel = Params.ServiceVhiDigital[countryIndex];
					} else {
						contourLevel = Params.ContourVhiAnalog[countryIndex];
						serviceLevel = Params.ServiceVhiAnalog[countryIndex];
					}
				}
				break;
			}

			case BAND_UHF:
			default: {
				if (Params.UseDipoleCont[countryIndex]) {
					dipoleCont = 20. * log10(source->frequency / Params.DipoleCenterFreqCont[countryIndex]);
				} else {
					dipoleCont = 0.;
				}
				if (Params.UseDipoleServ[countryIndex]) {
					dipoleServ = 20. * log10(source->frequency / Params.DipoleCenterFreqServ[countryIndex]);
				} else {
					dipoleServ = 0.;
				}
				if ((source->service == SERV_TVLP) || (source->service == SERV_TVCA)) {
					if (source->dtv) {
						contourLevel = Params.ContourUhfDigitalLPTV[countryIndex] + dipoleCont;
						serviceLevel = Params.ServiceUhfDigitalLPTV[countryIndex] + dipoleServ;
					} else {
						contourLevel = Params.ContourUhfAnalogLPTV[countryIndex] + dipoleCont;
						serviceLevel = Params.ServiceUhfAnalogLPTV[countryIndex] + dipoleServ;
					}
				} else {
					if (source->dtv) {
						contourLevel = Params.ContourUhfDigital[countryIndex] + dipoleCont;
						serviceLevel = Params.ServiceUhfDigital[countryIndex] + dipoleServ;
					} else {
						contourLevel = Params.ContourUhfAnalog[countryIndex] + dipoleCont;
						serviceLevel = Params.ServiceUhfAnalog[countryIndex] + dipoleServ;
					}
				}
				break;
			}

			// Wireless records will never have contour or service projection, they are interference sources only.

			case BAND_WL: {
				contourLevel = 0.;
				serviceLevel = 0.;
				break;
			}

			case BAND_FMED: {
				contourLevel = Params.ContourFMED[countryIndex];
				serviceLevel = Params.ServiceFMED[countryIndex];
				break;
			}

			case BAND_FM: {
				switch (source->service) {
					case SERV_FM: {
						switch (source->fmClass) {
							default: {
								contourLevel = Params.ContourFM[countryIndex];
								serviceLevel = Params.ServiceFM[countryIndex];
								break;
							}
							case FM_CLASS_B: {
								contourLevel = Params.ContourFMB[countryIndex];
								serviceLevel = Params.ServiceFMB[countryIndex];
								break;
							}
							case FM_CLASS_B1: {
								contourLevel = Params.ContourFMB1[countryIndex];
								serviceLevel = Params.ServiceFMB1[countryIndex];
								break;
							}
						}
						break;
					}
					case SERV_FMLP: {
						contourLevel = Params.ContourFMLP[countryIndex];
						serviceLevel = Params.ServiceFMLP[countryIndex];
						break;
					}
					case SERV_FMTX: {
						contourLevel = Params.ContourFMTX[countryIndex];
						serviceLevel = Params.ServiceFMTX[countryIndex];
						break;
					}
				}
			}
		}

		// Service levels are separate only if SetServiceLevels is true, else they are the same as contours.

		if (!Params.SetServiceLevels) {
			serviceLevel = contourLevel;
		}

		// Set levels and do preliminary service area setup on the source, or on all the individual sources for a DTS.
		// A source may specify an individual service area mode, possibly including a different contour level.  If the
		// service mode specifies the default contour mode apply that mode from parameters.  If the contour level is a
		// default value the parameter contour levels apply.  The no-bounds service area is actually a radius area
		// using the maximum desired signal distance parameter.  If the study area mode is not STUDY_AREA_SERVICE the
		// individual properties are ignored; the study area mode overrides the individual and/or default settings and
		// applies to all sources.  Contours will be projected or geographies retrieved/created in load_scenario().

		if (source->isParent) {
			sources = source->dtsSources;
		} else {
			sources = source;
			source->next = NULL;
		}

		for (asource = sources; asource; asource = asource->next) {

			asource->contourLevel = contourLevel;
			asource->serviceLevel = serviceLevel;

			switch (StudyAreaMode) {

				case STUDY_AREA_SERVICE:
				default: {

					if (SERVAREA_NO_BOUNDS == asource->serviceAreaMode) {

						asource->serviceAreaArg = Params.MaximumDistance;
						asource->serviceAreaCL = SERVAREA_CL_DEFAULT;

						asource->serviceAreaKey = 0;
						asource->serviceAreaModCount = 0;

					} else {

						if (SERVAREA_CONTOUR_DEFAULT == asource->serviceAreaMode) {

							asource->serviceAreaMode = Params.ServiceAreaMode[countryIndex];
							asource->serviceAreaArg = Params.ServiceAreaArg[countryIndex];
							asource->serviceAreaCL = SERVAREA_CL_DEFAULT;

							asource->serviceAreaKey = 0;
							asource->serviceAreaModCount = 0;
						}

						if (asource->serviceAreaCL != SERVAREA_CL_DEFAULT) {
							asource->contourLevel = asource->serviceAreaCL;
						}
					}

					break;
				}

				case STUDY_AREA_GEOGRAPHY: {

					asource->serviceAreaMode = SERVAREA_GEOGRAPHY_FIXED;
					asource->serviceAreaKey = StudyAreaGeoKey;
					asource->serviceAreaModCount = StudyAreaModCount;

					asource->serviceAreaArg = 0.;
					asource->serviceAreaCL = SERVAREA_CL_DEFAULT;

					break;
				}

				case STUDY_AREA_NO_BOUNDS: {

					asource->serviceAreaMode = SERVAREA_NO_BOUNDS;
					asource->serviceAreaArg = Params.MaximumDistance;
					asource->serviceAreaCL = SERVAREA_CL_DEFAULT;

					asource->serviceAreaKey = 0;
					asource->serviceAreaModCount = 0;

					break;
				}
			}
		}

		// Extra setup for a DTS parent source, set levels for information, they aren't actually used.  The reference
		// facility may be a different service and channel but is always an FCC contour.  The reference facility is
		// never used for terrain-sensitive service projection, it provides only a logical boundary.  The parent is
		// also never used for any service projection, it is just a placeholder.  However it has a service area that
		// acts in combination with the reference facility contour as the logical boundary.  That area may be a circle
		// or a sectors geography, the geography is created here since it will not be cached.

		if (source->isParent) {

			source->contourLevel = contourLevel;
			source->serviceLevel = serviceLevel;

			asource = source->dtsRefSource;

			asource->serviceAreaMode = SERVAREA_CONTOUR_FCC;
			asource->serviceAreaCL = SERVAREA_CL_DEFAULT;

			asource->serviceAreaArg = 0.;
			asource->serviceAreaKey = 0;
			asource->serviceAreaModCount = 0;

			switch (asource->band) {

				case BAND_VLO1:
				case BAND_VLO2: {
					asource->contourLevel = Params.ContourVloDigital[countryIndex];
					break;
				}

				case BAND_VHI: {
					asource->contourLevel = Params.ContourVhiDigital[countryIndex];
					break;
				}

				case BAND_UHF:
				default: {
					if (Params.UseDipoleCont[countryIndex]) {
						dipoleCont = 20. * log10(asource->frequency / Params.DipoleCenterFreqCont[countryIndex]);
					} else {
						dipoleCont = 0.;
					}
					asource->contourLevel = Params.ContourUhfDigital[countryIndex] + dipoleCont;
					break;
				}
			}

			// Set the DTS boundary radius (even if it might not be used, see below) from table values if needed.
			// If this is a replication record, the table distance is based on the original pre-replication channel.

			if (source->dtsMaximumDistance <= 0.) {

				if (source->origSourceKey) {
					asource = SourceKeyIndex[source->origSourceKey];
					if (!asource) {
						log_error("Original sourceKey=%d not found for replicated sourceKey=%d",
							source->origSourceKey, source->sourceKey);
						return 1;
					}
					band = asource->band;
				} else {
					band = source->band;
				}

				switch (band) {

					case BAND_VLO1:
					case BAND_VLO2: {
						if (source->zoneKey == ZONE_I) {
							source->dtsMaximumDistance = Params.DTSMaxDistVloZ1;
						} else {
							source->dtsMaximumDistance = Params.DTSMaxDistVloZ23;
						}
						break;
					}

					case BAND_VHI: {
						if (source->zoneKey == ZONE_I) {
							source->dtsMaximumDistance = Params.DTSMaxDistVhiZ1;
						} else {
							source->dtsMaximumDistance = Params.DTSMaxDistVhiZ23;
						}
						break;
					}

					case BAND_UHF:
					default: {
						source->dtsMaximumDistance = Params.DTSMaxDistUHF;
						break;
					}
				}
			}

			// Create a geography for the DTS logical boundary, this may be a sectors definition or just a circle
			// at a maximum distance.  If the sectors definition is invalid in any way revert to the circle.

			source->serviceAreaMode = SERVAREA_GEOGRAPHY_FIXED;
			source->serviceAreaKey = -(int)source->sourceKey;
			source->serviceAreaModCount = 0;

			source->serviceAreaArg = 0.;
			source->serviceAreaCL = SERVAREA_CL_DEFAULT;

			if (source->dtsSectors) {

				int nfld = 0;

				char *chr, lastChr = '\0';
				for (chr = source->dtsSectors; *chr; chr++) {
					if ((',' == *chr) || (';' == *chr)) {
						nfld++;
					}
					lastChr = *chr;
				}
				if (';' != lastChr) {
					nfld++;
				}

				if ((nfld > 5) && (0 == (nfld % 3))) {

					GEOGRAPHY *geo = geography_alloc(source->serviceAreaKey, GEO_TYPE_SECTORS, source->latitude,
						source->longitude, (nfld / 3));

					int i, bad = 0;
					double v, firstAz = -1., lastAz = -1.;

					chr = source->dtsSectors;
					char *fld = chr;
					for (i = 0; i < nfld; i++) {

						while ((',' != *chr) && (';' != *chr) && ('\0' != *chr)) chr++;
						*chr = '\0';
						v = atof(fld);
						fld = ++chr;

						switch (i % 3) {

							case 0: {
								if ((v < 0.) || (v >= 360.)) {
									bad = 1;
									break;
								}
								if (0 == i) {
									firstAz = v + 360.;
								} else {
									if (v != lastAz) {
										bad = 1;
										break;
									}
								}
								geo->a.sectorAzimuth[i / 3] = v;
								break;
							}

							case 1: {
								if ((v < 1.) || (v > 3000.)) {
									bad = 1;
									break;
								}
								geo->b.sectorRadius[i / 3] = v;
								break;
							}

							case 2: {
								if ((v <= geo->a.sectorAzimuth[i / 3]) || (v > firstAz)) {
									bad = 1;
									break;
								}
								lastAz = v;
								break;
							}
						}
					}

					if (lastAz != firstAz) {
						bad = 1;
					}

					if (bad) {
						log_message("Ignoring bad DTS boundary sectors list for sourceKey=%d", source->sourceKey);
						geography_free(geo);
					} else {
						source->geography = geo;
					}
				}
			}

			if (!source->geography) {

				GEOGRAPHY *geo = geography_alloc(source->serviceAreaKey, GEO_TYPE_CIRCLE, source->latitude,
					source->longitude, 0);
				geo->a.radius = source->dtsMaximumDistance;

				source->geography = geo;
			}
		}

		// Read and check the source cache.  If data does not exist or there is any problem, or if needsUpdate is set,
		// this will clear all cached data for the source and return false.  Later code can check source->cached to
		// determine if this succeeded.  However wireless records with missing or bad cache will not be cached here;
		// that cannot happen until the frequency is defined in load_scenario().

		if (read_source_cache(source)) {
			continue;
		}
		if (RECORD_TYPE_WL != source->recordType) {
			needCacheCount++;
		}

		// If needed, do updates that are written back to the database, mainly checking/adjusting heights.  Also load
		// antenna patterns.  These operations don't apply directly to a DTS parent source, only to it's secondaries.

		if (source->needsUpdate) {

			needUpdateCount++;
			if (source->origSourceKey) {
				needReplicationCount++;
			}

			for (asource = sources; asource; asource = asource->next) {

				err = do_height_checks(asource);
				if (err) return err;

				err = load_patterns(asource);
				if (err) return err;
			}

			if (source->isParent) {

				asource = source->dtsRefSource;

				err = do_height_checks(asource);
				if (err) return err;

				err = load_patterns(asource);
				if (err) return err;
			}

		// If the database record does not need update, just load patterns.

		} else {

			for (asource = sources; asource; asource = asource->next) {
				err = load_patterns(asource);
				if (err) return err;
			}

			if (source->isParent) {
				err = load_patterns(source->dtsRefSource);
				if (err) return err;
			}
		}
	}

	hb_log_end();

	// Do replications as needed.  The replication function will recursively handle DTS secondaries.

	if (needReplicationCount) {
		log_message("Replicating contours");
		hb_log_begin(needReplicationCount);
		source = Sources;
		for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {
			if (source->needsUpdate && source->origSourceKey) {
				hb_log_tick();
				err = do_replication(source);
				if (err) return err;
			}
		}
		hb_log_end();
	}

	// Write updates back to the database records as needed and if possible; database updates can only occur if the
	// the exclusive lock is held.  Verify the lock is still valid.

	if (needUpdateCount && (LOCK_RUN_EXCL == StudyLock)) {
		log_message("Updating database records");
		if (!check_study_lock()) {
			return -1;
		}
		hb_log_begin(needUpdateCount);
		source = Sources;
		for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {
			if (source->needsUpdate) {
				hb_log_tick();
				err = do_source_update(source);
				if (err) return err;
			}
		}
		hb_log_end();
	}

	// Write to caches as needed.  DTS secondary sources are not cached individually, they are attached to the parent.
	// Wireless sources are not cached here as the frequency is not set until scenario load, see load_scenario().

	if (needCacheCount) {
		log_message("Updating caches");
		hb_log_begin(needCacheCount);
		source = Sources;
		for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {
			if (!source->cached && (RECORD_TYPE_WL != source->recordType)) {
				hb_log_tick();
				write_source_cache(source);
			}
		}
		hb_log_end();
	}

	// Set the rule extra distance values for all sources.

	source = Sources;
	for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {
		set_rule_extra_distance(source);
	}

	// Done.

	return 0;
}

// Do height checks and adjustments on a source.  The actual height AMSL and height AGL fields in the database record
// are really cache fields, but are in the record so they are available to view in the UI.

static int do_height_checks(SOURCE *source) {

	int i, err;

	int countryIndex = source->countryKey - 1;

	double minAGL = Params.MinimumHeightAGL;
	int haatCount = Params.OverallHAATCount[countryIndex];
	if (RECORD_TYPE_WL == source->recordType) {
		minAGL = Params.WirelessMinimumHeightAGL;
		haatCount = Params.WirelessOverallHAATCount;
	}

	// Get the ground elevation at the source, needed for the following checks.

	float fptelv = 0.;
	err = terrain_point(source->latitude, source->longitude, Params.TerrPathDb, &fptelv);
	if (err) {
		log_error("Terrain lookup failed: lat=%.8f lon=%.8f db=%d err=%d", source->latitude, source->longitude,
			Params.TerrPathDb, err);
		return err;
	}
	double ptelv = (double)fptelv;

	// The AMSL height in the external station data may be wrong or missing, however overall HAAT values, if present,
	// can be used to derive HAMSL.  The front-end application will set HAMSL to a flag value when this should occur.
	// Likewise the HAAT may be set to the flag value if it needs to be computed here and saved back.  If both fields
	// are set to derive, set the AMSL height to the minimum AGL height then compute HAAT.

	if (HEIGHT_DERIVE == source->heightAMSL) {

		if (HEIGHT_DERIVE == source->overallHAAT) {

			source->actualHeightAMSL = ptelv + minAGL + HEIGHT_ROUND;

		} else {

			source->actualHeightAMSL = 10000.;
			double *haat = compute_source_haat(source, haatCount);
			if (!haat) return -1;

			double avet = 0.;
			for (i = 0; i < haatCount; i++) {
				avet += 10000. - haat[i];
			}
			avet /= (double)haatCount;

			source->actualHeightAMSL = avet + source->overallHAAT;
			mem_free(haat);
		}

	} else {

		source->actualHeightAMSL = source->heightAMSL;
	}

	if (HEIGHT_DERIVE == source->overallHAAT) {

		double *haat = compute_source_haat(source, haatCount);
		if (!haat) return -1;

		double oahaat = 0.;
		for (i = 0; i < haatCount; i++) {
			oahaat += haat[i];
		}
		oahaat /= (double)haatCount;

		source->actualOverallHAAT = oahaat;
		mem_free(haat);

	} else {

		source->actualOverallHAAT = source->overallHAAT;
	}

	// Check height AGL against minimum, adjust AMSL if needed.

	if ((source->actualHeightAMSL - ptelv) < minAGL) {
		source->actualHeightAMSL = ptelv + minAGL + HEIGHT_ROUND;
	}

	// Round the heights so there won't later be an inconsistency between the database and cache.

	source->actualHeightAMSL = rint(source->actualHeightAMSL / HEIGHT_ROUND) * HEIGHT_ROUND;
	source->actualOverallHAAT = rint(source->actualOverallHAAT / HEIGHT_ROUND) * HEIGHT_ROUND;

	source->heightAGL = source->actualHeightAMSL - ptelv;

	return 0;
}

// Write a source's updates back to the database.

static int do_source_update(SOURCE *source) {

	static char query[MAX_QUERY], clause[MAX_STRING];

	// For a DTS parent, first recursively update the individual sources; if the parent was updated, those always were
	// too.  Then clear the update flag on the parent (heights don't matter on that record).

	if (source->isParent) {

		int err;

		SOURCE *dtsSource;
		for (dtsSource = source->dtsSources; dtsSource; dtsSource = dtsSource->next) {
			err = do_source_update(dtsSource);
			if (err) return err;
		}

		err = do_source_update(source->dtsRefSource);
		if (err) return err;

		snprintf(query, MAX_QUERY, "UPDATE %s_%d.source SET needs_update=0 WHERE source_key=%d;", DbName, StudyKey,
			source->sourceKey);
		if (mysql_query(MyConnection, query)) {
			log_db_error("Source update query failed for sourceKey=%d", source->sourceKey);
			return -1;
		}

		source->needsUpdate = 0;

		return 0;
	}

	// For non-DTS or DTS secondary, clear the needs_update flag, and save the various derived heights.

	snprintf(query, MAX_QUERY, "UPDATE %s_%d.source SET needs_update=0", DbName, StudyKey);

	snprintf(clause, MAX_STRING, ", actual_height_amsl=%.2f, actual_overall_haat=%.2f, height_agl=%.15e",
		source->actualHeightAMSL, source->actualOverallHAAT, source->heightAGL);
	lcatstr(query, clause, MAX_QUERY);

	// For a replicated source, save new ERPs and IDs, clear other values related to the patterns.  In some cases the
	// the elevation pattern information is preserved during replication.  However that will always be unmodified so
	// if the vpat or mpat flags are still set just don't change the record fields.

	if (source->origSourceKey) {
		snprintf(clause, MAX_STRING,
			", peak_erp=%s, contour_erp=%.15e, has_horizontal_pattern=%d, horizontal_pattern_orientation=0",
			erpkw_string(source->peakERP), source->contourERP, source->hasHpat);
		lcatstr(query, clause, MAX_QUERY);
		snprintf(clause, MAX_STRING, ", antenna_id='%s'", sql_escape(source->antennaID));
		lcatstr(query, clause, MAX_QUERY);
		snprintf(clause, MAX_STRING, ", horizontal_pattern_name='%s'", sql_escape(source->hpatName));
		lcatstr(query, clause, MAX_QUERY);
		if (!source->hasVpat) {
			lcatstr(query, ", has_vertical_pattern=0, vertical_pattern_name=''", MAX_QUERY);
		}
		if (!source->hasMpat) {
			lcatstr(query, ", has_matrix_pattern=0, matrix_pattern_name=''", MAX_QUERY);
		}
	}

	snprintf(clause, MAX_STRING, " WHERE source_key=%d;", source->sourceKey);
	lcatstr(query, clause, MAX_QUERY);

	if (mysql_query(MyConnection, query)) {
		log_db_error("Source update query failed for sourceKey=%d", source->sourceKey);
		return -1;
	}

	// For a replicated source, also clear existing pattern data and save new data (if any).

	if (source->origSourceKey) {

		snprintf(query, MAX_QUERY, "DELETE FROM %s_%d.source_horizontal_pattern WHERE source_key=%d;", DbName,
			StudyKey, source->sourceKey);
		if (mysql_query(MyConnection, query)) {
			log_db_error("Horizontal pattern delete failed for sourceKey=%d", source->sourceKey);
			return -1;
		}

		if (!source->hasVpat) {
			snprintf(query, MAX_QUERY, "DELETE FROM %s_%d.source_vertical_pattern WHERE source_key=%d;", DbName,
				StudyKey, source->sourceKey);
			if (mysql_query(MyConnection, query)) {
				log_db_error("Vertical pattern delete failed for sourceKey=%d", source->sourceKey);
				return -1;
			}
		}

		if (!source->hasMpat) {
			snprintf(query, MAX_QUERY, "DELETE FROM %s_%d.source_matrix_pattern WHERE source_key=%d;", DbName,
				StudyKey, source->sourceKey);
			if (mysql_query(MyConnection, query)) {
				log_db_error("Matrix pattern delete failed for sourceKey=%d", source->sourceKey);
				return -1;
			}
		}

		if (source->hasHpat) {

			snprintf(query, MAX_QUERY, "INSERT INTO %s_%d.source_horizontal_pattern VALUES", DbName, StudyKey);

			char sep = ' ';
			double rf, *pat = source->hpat;
			int i;

			for (i = 0; i < 360; i++) {
				rf = pow(10., (pat[i] / 20.));
				snprintf(clause, MAX_STRING, "%c(%d,%d,%.15e)", sep, source->sourceKey, i, rf);
				lcatstr(query, clause, MAX_QUERY);
				sep = ',';
			}

			lcatstr(query, ";", MAX_QUERY);

			if (mysql_query(MyConnection, query)) {
				log_db_error("Horizontal pattern save failed for sourceKey=%d", source->sourceKey);
				return -1;
			}
		}
	}

	source->needsUpdate = 0;

	return 0;
}

// Escape characters in a string for SQL query.

static char *sql_escape(char *fr) {

	static char to[MAX_STRING * 2];

	int f = 0, t = 0, m = (MAX_STRING * 2) - 1;
	while (fr[f] && (t < m)) {
		if ('\'' == fr[f]) {
			to[t++] = '\'';
		}
		if ('\\' == fr[f]) {
			to[t++] = '\\';
		}
		to[t++] = fr[f++];
	}
	to[t] = '\0';

	return to;
}


//---------------------------------------------------------------------------------------------------------------------
// Run contour replication on a source, project the contour of the original source and derive a new horizontal pattern
// and peak ERP to produce the replication contour.  Non-zero return on error, <0 is fatal, >0 study could continue
// just not involving this source.  If called on the parent source of a DTS operation, this will recursively replicate
// all of the actual sources as well.

// Arguments:

//   source  The source needing replication.

// Return <0 for major error, >0 for minor error, 0 for no error.

static int do_replication(SOURCE *source) {

	int err;

	// This is only allowed on a TV record.

	if (RECORD_TYPE_TV != source->recordType) {
		log_error("Cannot replicate non-TV record, sourceKey=%d", source->sourceKey);
		return 1;
	}

	// For a DTS parent source, recursively replicate all the individual sources.  The parent record itself is just a
	// placeholder, it has no actual contour to replicate.  When the parent is flagged for replication that means the
	// individual transmitter sources are being replicated.  The parent origSourceKey points back to the parent of the
	// set of originals, but each individual source also points back to it's own original.  The reference facility
	// source is never replicated.  It is always a duplicate of the reference facility from the original.  That record
	// only provides a bounding contour that must be unchanging; channel is irrelevant.

	if (source->isParent) {

		SOURCE *dtsSource;
		for (dtsSource = source->dtsSources; dtsSource; dtsSource = dtsSource->next) {
			err = do_replication(dtsSource);
			if (err) return err;
		}

		return 0;
	}

	// Retrieve the original source.  It should always exist, it is kept in the database even if it is not directly
	// involved in any scenario.

	SOURCE *origSource = SourceKeyIndex[source->origSourceKey];
	if (NULL == origSource) {
		log_error("Original sourceKey=%d not found for replicated sourceKey=%d", source->origSourceKey,
			source->sourceKey);
		return 1;
	}

	// Project an FCC contour for the original source.  Replication is always based on FCC contours regardless of the
	// actual service area mode that will be used for either the original or replication source.  Always project a
	// temporary-use contour, it's too complicated to figure out if an existing contour on the source is suitable.

	int countryIndex = origSource->countryKey - 1, curv;
	if (origSource->dtv) {
		curv = Params.CurveSetDigital[countryIndex];
	} else {
		curv = Params.CurveSetAnalog[countryIndex];
	}

	CONTOUR *contour = project_fcc_contour(origSource, curv, origSource->contourLevel);
	if (!contour) return -1;

	// General setup, get HAAT.

	countryIndex = source->countryKey - 1;
	if (source->dtv) {
		curv = Params.CurveSetDigital[countryIndex];
	} else {
		curv = Params.CurveSetAnalog[countryIndex];
	}

	int haatCount =
		(SERV_TVLP == source->service) ? Params.HAATCountLPTV[countryIndex] : Params.HAATCount[countryIndex];
	double *haat = compute_source_haat(source, haatCount);
	if (!haat) return -1;

	// Allocate space for the new pattern.  Start by assuming this will end up with a directional pattern, if it is
	// omni that will be fixed later.  The new pattern will always have orientation zero.  The vertical pattern is
	// usually set to generic, however the tilt parameters are not changed, those may apply to the generic pattern.
	// The antenna ID is cleared and horizontal pattern name set to generic.

	// However the vertical/matrix pattern is not cleared if this is a replication of a baseline record.  The baseline
	// record tables can't contain DTS records, so those are supported as baselines by a non-DTS baseline record that
	// references a DTS record from the main tables.  If the baseline channel is not the same as the referenced record,
	// it is changed by replication.  Vertical/matrix patterns are preserved to be consistent with other DTS baseline
	// records that do not change channel and so are not replicated.

	source->hasHpat = 1;
	source->hpatOrientation = 0.;
	source->antennaID[0] = '\0';
	lcpystr(source->hpatName, "(replication)", PAT_NAME_L);

	if (!get_source_attribute(source, ATTR_IS_BASELINE)) {
		source->hasVpat = 0;
		if (source->vpat) {
			mem_free(source->vpat);
			source->vpat = NULL;
		}
		source->hasMpat = 0;
		if (source->mpat) {
			mem_free(source->mpat);
			source->mpat = NULL;
		}
		source->mpatName[0] = '\0';
	}

	if (source->hpat) {
		mem_free(source->hpat);
	}
	double *pat = (double *)mem_alloc(360 * sizeof(double));
	source->hpat = pat;

	source->contourERP = -999.;

	double cntr = source->contourLevel;

	double azm, dist, eah, erp, minHAAT = Params.MinimumHAAT[countryIndex];
	int i;

	switch (Params.ReplicationMethod[countryIndex]) {

		// For the derive-pattern method, a pattern and peak ERP are computed that exactly reproduce the original
		// contour.  At each 1-degree point, interpolate HAAT and distance to the original contour, then look up the
		// ERP needed to get that contour distance on the new channel.  When done convert the pattern to relative dB.

		case REPL_METH_DERIVE:
		default: {

			for (i = 0; i < 360; i++) {

				azm = (double)i;

				eah = interp_min(azm, haat, haatCount, minHAAT, 1);

				dist = interp_cont(azm, contour);

				fcc_curve(&erp, &cntr, &dist, eah, source->band, FCC_PWR, curv, Params.OffCurveLookupMethod,
					source, azm, NULL, NULL, NULL);

				pat[i] = erp;
				if (erp > source->contourERP) {
					source->contourERP = erp;
				}
			}

			for (i = 0; i < 360; i++) {
				pat[i] -= source->contourERP;
			}

			break;
		}

		// The match-area replication method uses the existing horizontal pattern but adjusts the peak ERP to produce a
		// new contour enclosing the same area as the original.  Even when the pattern is omni this must be done by
		// full contour projection and iteration because HAAT variations can shape the contours differently between
		// the original and new channels.

		// In the setup phase, look up pattern values every 1 degree (not really necessary for omni but makes the
		// following code uniform); interpolate and store HAAT values; interpolate the original contour and compute
		// it's area.  Also compute an overall HAAT for the initial ERP estimate below.

		// Note the pattern is computed and the actual peak ERP found, then values converted back to relative dB, to be
		// sure that the final pattern always has a 0 dB point.  That is not always true of patterns from the external
		// station data so it's best to not simply copy the original pattern.  Also the source may have a derived
		// h-plane pattern being used for contour projection which may be entirely different than the actual pattern.
		// In that case the replication pattern will be based on the h-plane pattern.  See contour_erp_lookup().

		case REPL_METH_AREA: {

			#define HALF_SINE_1  0.008726203218641756   // 0.5 * sin(1 degree)

			double area = 0., ldist = 0., fdist = 0., eahs[360], havg = 0.;

			for (i = 0; i < 360; i++) {

				azm = (double)i;

				pat[i] = contour_erp_lookup(origSource, azm);
				if (pat[i] > source->contourERP) {
					source->contourERP = pat[i];
				}

				eahs[i] = interp_min(azm, haat, haatCount, minHAAT, 1);
				havg += eahs[i];

				dist = interp_cont(azm, contour);

				if (i > 0) {
					area += ldist * dist * HALF_SINE_1;
				} else {
					fdist = dist;
				}
				ldist = dist;
			}
			area += ldist * fdist * HALF_SINE_1;

			havg /= 360.;

			for (i = 0; i < 360; i++) {
				pat[i] -= source->contourERP;
			}

			// Make an initial estimate of the ERP based on the radius of a circle of the target area projected using
			// the overall average HAAT.  Then begin iterating; project new contour and compute area, check against
			// target, adjust ERP and loop until within desired tolerance.  The desired tolerance is 1/2 of the study
			// cell area, but no less than 0.25 km^2.  Adjustment to the ERP is made based on the difference in free-
			// space path loss between the target and actual radii of circles of area equal to the contours (which
			// simplifies to just the log of the ratio of the radii), but since the curves are considerably more loss
			// than free-space that adjustment is always doubled (halved when reducing).

			// If this does not converge give up and use the last value.  There are discontinuities in fcc_curve() due
			// to transitions between curve sets and free-space so this can get stuck in oscillation.  But those
			// problems only occur with very small contours which are unlikely to be seen here since replication is
			// usually just for full-service stations with large contour areas.

			double target_dist = sqrt(area / PI);
			fcc_curve(&(source->contourERP), &cntr, &target_dist, havg, source->band, FCC_PWR, curv,
				Params.OffCurveLookupMethod, NULL, 0., NULL, NULL, NULL);

			double area_new, adjerp, target_diff = (Params.CellSize * Params.CellSize) / 2.;
			if (target_diff < 0.25) {
				target_diff = 0.25;
			}

			int iter = 0;

			while (++iter < REPL_ITERATION_LIMIT) {

				area_new = 0.;

				for (i = 0; i < 360; i++) { 

					azm = (double)i;

					erp = pat[i] + source->contourERP;

					fcc_curve(&erp, &cntr, &dist, eahs[i], source->band, FCC_DST, curv, Params.OffCurveLookupMethod,
						source, azm, NULL, NULL, NULL);

					if (i > 0) {
						area_new += ldist * dist * HALF_SINE_1;
					} else {
						fdist = dist;
					}
					ldist = dist;
				}
				area_new += ldist * fdist * HALF_SINE_1;

				if (fabs(area_new - area) <= target_diff) {
					break;
				}

				adjerp = 20. * log10(target_dist / sqrt(area_new / PI));
				source->contourERP += 2. * adjerp;
			}

			if (iter >= REPL_ITERATION_LIMIT) {
				log_message("Replication failed to converge for sourceKey=%d  %s", source->sourceKey,
					source_label(source));
			}

			// If the original source was omni, clear the pattern data from the new source.  Except if the original
			// source has a derived pattern the replication is directional because it is based on that.

			if (!origSource->hasHpat && !origSource->conthpat) {
				source->hasHpat = 0;
				source->hpatName[0] = '\0';
				source->hpat = NULL;
				mem_free(pat);
				pat = NULL;
			}

			break;
		}
	}

	mem_free(haat);
	haat = NULL;

	contour_free(contour);
	contour = NULL;

	// Minimum and maximum limits are applied to the ERP per band and zone.  This does not change the location of the
	// contour, hence separate values are kept for contourERP and peakERP, but it changes the predicted field strengths
	// at study points.  The minimum check does not apply to individual DTS sources, those may have power below the
	// minimum.  Minimum and maximum both apply to Class A/LPTV but with different values and no variations for VHF.

	source->peakERP = source->contourERP;

	double minerp = -999., maxerp = 999.;

	if ((source->service == SERV_TVLP) || (source->service == SERV_TVCA)) {

		switch (source->band) {

			case BAND_VLO1:
			case BAND_VLO2:
			case BAND_VHI: {
				minerp = 10. * log10(Params.MinimumVhfErpLPTV[countryIndex]);
				maxerp = 10. * log10(Params.MaximumVhfErpLPTV[countryIndex]);
				break;
			}

			case BAND_UHF: {
				minerp = 10. * log10(Params.MinimumUhfErpLPTV[countryIndex]);
				maxerp = 10. * log10(Params.MaximumUhfErpLPTV[countryIndex]);
				break;
			}
		}

	} else {

		switch (source->band) {

			case BAND_VLO1:
			case BAND_VLO2: {
				minerp = 10. * log10(Params.MinimumVloERP[countryIndex]);
				if (source->zoneKey == ZONE_I) {
					maxerp = 10. * log10(Params.MaximumVloZ1ERP[countryIndex]);
				} else {
					maxerp = 10. * log10(Params.MaximumVloZ23ERP[countryIndex]);
				}
				break;
			}

			case BAND_VHI: {
				minerp = 10. * log10(Params.MinimumVhiERP[countryIndex]);
				if (source->zoneKey == ZONE_I) {
					maxerp = 10. * log10(Params.MaximumVhiZ1ERP[countryIndex]);
				} else {
					maxerp = 10. * log10(Params.MaximumVhiZ23ERP[countryIndex]);
				}
				break;
			}

			case BAND_UHF:
			default: {
				minerp = 10. * log10(Params.MinimumUhfERP[countryIndex]);
				maxerp = 10. * log10(Params.MaximumUhfERP[countryIndex]);
				break;
			}
		}
	}

	if (!source->dts && (source->peakERP < minerp)) {
		source->peakERP = minerp;
	}
	if (source->peakERP > maxerp) {
		source->peakERP = maxerp;
	}

	// Round the new ERP value using the same conversion to kilowatts that will be used to write the new value back to
	// the database record, to avoid a mismatch between the database record and cache values.

	source->peakERP = 10. * log10(atof(erpkw_string(source->peakERP)));

	// Done.

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Set up sources for study in a particular scenario.  This sets the inScenario, isDesired, and isUndesired flags on
// all sources, then makes sure all with isDesired set have service areas and undesired source lists.  Non-zero return
// on error; an error return <0 is a serious error, process should exit, >0 means something like scenario not found,
// run can continue.  Also load scenario parameters.

// Arguments:

//   scenarioKey  Primary key for scenario record in current study database.

// Return <0 for serious error (run should abort), >0 for minor error (recoverable), 0 for no error.

int load_scenario(int scenarioKey) {

	if (!StudyKey) {
		log_error("load_scenario() called with no study open");
		return 1;
	}
	if (scenarioKey == ScenarioKey) {
		return 0;
	}

	if (!check_study_lock()) {
		return -1;
	}

	ScenarioKey = 0;

	int err, i;

	char query[MAX_QUERY];
	MYSQL_RES *myResult;
	MYSQL_ROW fields;

	snprintf(query, MAX_QUERY, "SELECT name FROM %s_%d.scenario WHERE scenario_key = %d;", DbName, StudyKey,
		scenarioKey);
	if (mysql_query(MyConnection, query)) {
		log_db_error("Scenario query failed (1)");
		return -1;
	}

	myResult = mysql_store_result(MyConnection);
	if (!myResult) {
		log_db_error("Scenario query failed (2)");
		return -1;
	}

	if (!mysql_num_rows(myResult)) {
		mysql_free_result(myResult);
		log_error("Scenario not found for scenarioKey=%d", scenarioKey);
		return 1;
	}

	fields = mysql_fetch_row(myResult);
	if (!fields) {
		mysql_free_result(myResult);
		log_db_error("Scenario query failed (3)");
		return -1;
	}

	lcpystr(ScenarioName, fields[0], MAX_STRING);

	mysql_free_result(myResult);

	// Load scenario parameters.

	err = load_scenario_parameters(scenarioKey);
	if (err) {
		return err;
	}

	// Clear state from a previous scenario.

	SOURCE *source;
	int sourceIndex, sourceCount, sourceKey;

	source = Sources;
	for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {
		source->inScenario = 0;
		source->isDesired = 0;
		source->isUndesired = 0;
		if (source->undesireds) {
			mem_free(source->undesireds);
			source->undesireds = NULL;
			source->undesiredCount = 0;
		}
		if (source->selfIxTotals) {
			mem_free(source->selfIxTotals);
			source->selfIxTotals = NULL;
		}
		memset(source->totals, 0, (MAX_COUNTRY * sizeof(DES_TOTAL)));
		source->next = NULL;
	}

	memset(WirelessUndesiredTotals, 0, (MAX_COUNTRY * sizeof(UND_TOTAL)));

	DoComposite = 0;
	memset(CompositeTotals, 0, (MAX_COUNTRY * sizeof(DES_TOTAL)));

	// Load the list of sources in the scenario.

	snprintf(query, MAX_QUERY,
		"SELECT source_key, is_desired, is_undesired FROM %s_%d.scenario_source WHERE scenario_key = %d",
		DbName, StudyKey, scenarioKey);
	if (mysql_query(MyConnection, query)) {
		log_db_error("Scenario source query failed (1)");
		return -1;
	}

	myResult = mysql_store_result(MyConnection);
	if (!myResult) {
		log_db_error("Scenario source query failed (2)");
		return -1;
	}

	sourceCount = (int)mysql_num_rows(myResult);
	if (!sourceCount) {
		mysql_free_result(myResult);
		log_error("No sources found for scenarioKey=%d", scenarioKey);
		return 1;
	}

	int desiredCount = 0, wirelessCount = 0;

	for (sourceIndex = 0; sourceIndex < sourceCount; sourceIndex++) {

		fields = mysql_fetch_row(myResult);
		if (!fields) {
			mysql_free_result(myResult);
			log_db_error("Scenario source query failed (3)");
			return -1;
		}

		sourceKey = atoi(fields[0]);
		if ((sourceKey >= SourceIndexSize) || (NULL == (source = SourceKeyIndex[sourceKey]))) {
			mysql_free_result(myResult);
			log_error("Source not found for sourceKey=%d in scenarioKey=%d", sourceKey, scenarioKey);
			return 1;
		}

		source->inScenario = 1;
		source->isDesired = (short)atoi(fields[1]);
		source->isUndesired = (short)atoi(fields[2]);

		// Wireless records cannot be desireds, also these will need further setup as undesireds so keep a count.

		if (RECORD_TYPE_WL == source->recordType) {
			source->isDesired = 0;
			if (source->isUndesired) {
				wirelessCount++;
			}
		}

		if (source->isDesired) {
			desiredCount++;
		}
	}

	mysql_free_result(myResult);

	// Set up service areas as needed and build undesired source lists.

	hb_log_begin(desiredCount);

	SOURCE *sources, *asource;
	GEOPOINTS *pts;
	double dist, maxdist;

	source = Sources;
	for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {

		if (source->isDesired) {

			hb_log_tick();

			if (source->isParent) {
				sources = source->dtsSources;
			} else {
				sources = source;
				source->next = NULL;
			}

			// Load service area geographies as needed.  Also for sources with SERVAREA_RADIUS or SERVAREA_NO_BOUNDS
			// modes, implement those here by creating a custom circle geography.

			for (asource = sources; asource; asource = asource->next) {

				if (asource->geography) {
					continue;
				}

				switch (asource->serviceAreaMode) {

					case SERVAREA_GEOGRAPHY_FIXED: {
						asource->geography = get_geography(asource->serviceAreaKey);
						if (!asource->geography) return 1;
						break;
					}

					case SERVAREA_GEOGRAPHY_RELOCATED: {
						asource->geography = get_geography(asource->serviceAreaKey);
						if (!asource->geography) return 1;
						relocate_geography(asource);
						break;
					}

					case SERVAREA_RADIUS:
					case SERVAREA_NO_BOUNDS: {
						asource->geography = geography_alloc(-(asource->sourceKey), GEO_TYPE_CIRCLE, asource->latitude,
							asource->longitude, 0);
						asource->geography->a.radius = asource->serviceAreaArg;
						break;
					}
				}
			}

			// Define service areas as needed, this will project contours and render geographies.  Clear the cache
			// flag so the source cache is updated below.

			if (!source->serviceAreaValid) {
				err = do_service_area(source);
				if (err) return err;
				source->serviceAreaValid = 1;
				source->cached = 0;
			}

			// In the front-end app undesired searches use the "rule extra distance" algorithm which provides a single
			// extra distance added to the interference rule culling distance to find undesired records by a site-to-
			// site distance check.  With default values for the parameters controlling that algorithm it provides a
			// worst-case maximum distance to an FCC curves contour.  However if the actual service area is defined by
			// some other method that distance may be insufficient causing undesireds that would be within culling
			// distance of some study points to be missing from the scenario.  To alert the user to that possibility,
			// check the actual rendered service area boundary vs. the extra distance and show a warning as needed.

			for (asource = sources; asource; asource = asource->next) {
				pts = render_service_area(asource);
				maxdist = 0.;
				for (i = 0; i < pts->nPts; i++) {
					bear_distance(asource->latitude, asource->longitude, pts->ptLat[i], pts->ptLon[i], NULL, NULL,
						&dist, Params.KilometersPerDegree);
					if (dist > maxdist) {
						maxdist = dist;
					}
				}
				if ((maxdist - asource->ruleExtraDistance) > 0.1) {
					log_message("Max service area distance %.2f > rule extra %.2f for sourceKey=%d  %s", maxdist,
						asource->ruleExtraDistance, asource->sourceKey, source_label(asource));
				}
			}

			err = find_undesired(source);
			if (err) return err;

			if (source->isParent && Params.CheckSelfInterference) {
				source->selfIxTotals = (UND_TOTAL *)mem_zalloc(MAX_COUNTRY * sizeof(UND_TOTAL));
			}
		}
	}

	hb_log_end();

	// Do setup on wireless sources.  The frequency is defined by a scenario parameter, however when the frequency
	// changes the source's cache has to be reset.  The frequency was initialized in read_source_cache(), if there was
	// an existing cache, so this can just compare the frequency in that case and clear the cache if it changes.  In
	// any case, a source cache is written if needed; wireless records were deliberately not cached with others in
	// source_setup() since at that point the frequency was undefined if the source had no existing cache.  Note the
	// wireless bandwidth is not relevant here, changing only the bandwidth does not affect pathloss predictions, it
	// just affects D/U analysis the results of which are not cached.  Also update source caches for desired sources
	// that had service area updates above.

	log_message("Updating caches");

	hb_log_begin(wirelessCount + desiredCount);

	source = Sources;
	for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {

		if ((RECORD_TYPE_WL == source->recordType) && source->isUndesired) {

			hb_log_tick();

			if (source->cached && (source->frequency != Params.WirelessFrequency)) {
				clear_cache(source);
			}
			source->frequency = Params.WirelessFrequency;
			if (!source->cached) {
				write_source_cache(source);
			}
		}

		if (source->isDesired) {

			hb_log_tick();

			if (!source->cached) {
				write_source_cache(source);
			}
		}
	}

	hb_log_end();

	// Scenario loaded, do map file output as needed.

	ScenarioKey = scenarioKey;

	err = do_mapfiles();
	if (err) {
		ScenarioKey = 0;
		return err;
	}

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Function to retrieve attribute data for a source and given attribute name.  The attributes are stored in a single
// database field as a text block in "name=value" format.  Because these are rarely used here in the engine (they are
// primarily used by the front-end app) the attributes are not routinely loaded when sources are read, this will query
// for the raw attributes string as needed.

// Arguments:

//   source   The source.
//   attr     The attribute name.

// Return is the attribute value, which may be an empty string, or NULL if the attribute is not found or errors occur.
// Note there is no way for the caller to tell between not-found and error, and no error messages are ever logged.
// Errors are essentially being ignored here.

char *get_source_attribute(SOURCE *source, char *attr) {

	if (!StudyKey) {
		return NULL;
	}

	if (!source->didLoadAttributes) {

		char query[MAX_QUERY];
		MYSQL_RES *myResult;
		MYSQL_ROW fields;

		snprintf(query, MAX_QUERY, "SELECT attributes FROM %s_%d.source WHERE source_key = %d;", DbName, StudyKey,
			source->sourceKey);
		if (mysql_query(MyConnection, query)) {
			return NULL;
		}

		myResult = mysql_store_result(MyConnection);
		if (!myResult) {
			return NULL;
		}

		if (!mysql_num_rows(myResult)) {
			mysql_free_result(myResult);
			return NULL;
		}

		fields = mysql_fetch_row(myResult);
			if (!fields) {
			mysql_free_result(myResult);
			return NULL;
		}

		source->didLoadAttributes = 1;

		int len = strlen(fields[0]) + 1;

		if (len > 1) {
			source->attributes = (char *)mem_alloc(len);
			lcpystr(source->attributes, fields[0], len);
		}

		mysql_free_result(myResult);

		if (len > 1) {

			char *pos, *end, *line, *sep, *name, *value;

			pos = source->attributes;
			end = pos + strlen(pos);

			int maxAttr = 0;

			while (pos < end) {

				line = pos;
				sep = index(line, '\n');
				if (sep) {
					*sep = '\0';
					pos = sep;
				} else {
					pos = end;
				}
				name = line;

				sep = index(line, '=');
				if (sep) {
					*sep = '\0';
					value = sep + 1;
				} else {
					value = pos;
				}

				if (source->nAttributes >= maxAttr) {
					maxAttr += 10;
					source->attributeName = (char **)mem_realloc(source->attributeName, (maxAttr * sizeof(char **)));
					source->attributeValue = (char **)mem_realloc(source->attributeValue, (maxAttr * sizeof(char **)));
				}

				source->attributeName[source->nAttributes] = name;
				source->attributeValue[source->nAttributes++] = value;

				pos++;
			}
		}
	}

	int i;
	for (i = 0; i < source->nAttributes; i++) {
		if (0 == strcmp(source->attributeName[i], attr)) {
			return source->attributeValue[i];
		}
	}
	return NULL;
}


//---------------------------------------------------------------------------------------------------------------------
// Do source and service area map file outputs for the loaded scenario, may be ESRI shapefile, KML, both, or neither.

// Arguments:

//   (none)

// Return is 0 for no error, >0 for error, all errors here are minor.

#define SRC_NATTR  23
#define CONT_NATTR  1

#define LEN_SOURCEKEY    5
#define LEN_SITENUMBER   3
#define LEN_FACILITYID   7
#define LEN_SERVICE      2
#define LEN_CHANNEL      3
#define LEN_FREQUENCY    6
#define PREC_FREQUENCY   2
#define LEN_CALLSIGN    16
#define LEN_CITY        20
#define LEN_STATE        2
#define LEN_COUNTRYKEY   1
#define LEN_STATUS       6
#define LEN_FILENUMBER  22
#define LEN_HEIGHT       6
#define PREC_HEIGHT      1
#define LEN_ERP          8
#define PREC_ERP         3
#define LEN_SVCLEVEL     5
#define PREC_SVCLEVEL    2
#define LEN_ORIENT       7
#define PREC_ORIENT      2
#define LEN_TILT         6
#define PREC_TILT        1
#define LEN_DISTANCE     6
#define PREC_DISTANCE    2

static int do_mapfiles() {

	// Attribute list is shared, contour/service area file uses just the first entry.

	static SHAPEATTR attrs[SRC_NATTR] = {
		{"SOURCEKEY", SHP_ATTR_NUM, LEN_SOURCEKEY, 0},
		{"DTSKEY", SHP_ATTR_NUM, LEN_SOURCEKEY, 0},
		{"SITENUMBER", SHP_ATTR_NUM, LEN_SITENUMBER, 0},
		{"FACILITYID", SHP_ATTR_NUM, LEN_FACILITYID, 0},
		{"SERVICE", SHP_ATTR_NUM, LEN_SERVICE, 0},
		{"CHANNEL", SHP_ATTR_NUM, LEN_CHANNEL, 0},
		{"FREQUENCY", SHP_ATTR_NUM, LEN_FREQUENCY, PREC_FREQUENCY},
		{"CALLSIGN", SHP_ATTR_CHAR, LEN_CALLSIGN, 0},
		{"CITY", SHP_ATTR_CHAR, LEN_CITY, 0},
		{"STATE", SHP_ATTR_CHAR, LEN_STATE, 0},
		{"COUNTRYKEY", SHP_ATTR_NUM, LEN_COUNTRYKEY, 0},
		{"STATUS", SHP_ATTR_CHAR, LEN_STATUS, 0},
		{"FILENUMBER", SHP_ATTR_CHAR, LEN_FILENUMBER, 0},
		{"HAMSL", SHP_ATTR_NUM, LEN_HEIGHT, PREC_HEIGHT},
		{"HAAT", SHP_ATTR_NUM, LEN_HEIGHT, PREC_HEIGHT},
		{"ERP", SHP_ATTR_NUM, LEN_ERP, PREC_ERP},
		{"SVCLEVEL", SHP_ATTR_NUM, LEN_SVCLEVEL, PREC_SVCLEVEL},
		{"AZORIENT", SHP_ATTR_NUM, LEN_ORIENT, PREC_ORIENT},
		{"ETILT", SHP_ATTR_NUM, LEN_TILT, PREC_TILT},
		{"MTILT", SHP_ATTR_NUM, LEN_TILT, PREC_TILT},
		{"MTLTORIENT", SHP_ATTR_NUM, LEN_ORIENT, PREC_ORIENT},
		{"DSOURCEKEY", SHP_ATTR_NUM, LEN_SOURCEKEY, 0},
		{"CULLDIST", SHP_ATTR_NUM, LEN_DISTANCE, PREC_DISTANCE}
	};

	// Open files if needed, write data.

	int doShape = MapOutputFlags[MAP_OUT_SHAPE], doKML = MapOutputFlags[MAP_OUT_KML];

	if (!doShape && !doKML) {
		return 0;
	}

	MAPFILE *shpSrcs = NULL, *shpConts = NULL, *kmlSrcs = NULL, *kmlConts = NULL;

	if (doShape) {

		shpSrcs = open_mapfile(MAP_FILE_SHAPE, SOURCE_MAPFILE_NAME, SHP_TYPE_POINT, SRC_NATTR, attrs, NULL, -1);
		if (!shpSrcs) {
			return 1;
		}

		shpConts = open_mapfile(MAP_FILE_SHAPE, CONTOUR_MAPFILE_NAME, SHP_TYPE_POLYLINE, CONT_NATTR, attrs, NULL, -1);
		if (!shpConts) {
			close_mapfile(shpSrcs);
			return 1;
		}
	}

	if (doKML) {

		kmlSrcs = open_mapfile(MAP_FILE_KML, SOURCE_MAPFILE_NAME, SHP_TYPE_POINT, SRC_NATTR, attrs, ScenarioName, -1);
		if (!kmlSrcs) {
			if (doShape) {
				close_mapfile(shpSrcs);
				close_mapfile(shpConts);
			}
			return 1;
		}

		kmlConts = open_mapfile(MAP_FILE_KML, CONTOUR_MAPFILE_NAME, SHP_TYPE_POLYLINE, CONT_NATTR, attrs,
			ScenarioName, -1);
		if (!kmlConts) {
			if (doShape) {
				close_mapfile(shpSrcs);
				close_mapfile(shpConts);
			}
			close_mapfile(kmlSrcs);
			return 1;
		}
	}

	SOURCE *source = Sources, *dtsSource, *usource;
	UNDESIRED *undesireds;
	int sourceIndex, undesiredCount, undesiredIndex, err = 0;
	double cullDist;

	for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {

		if (source->isDesired) {

			hb_log();

			if (doShape) {
				err = write_source_shapes(source, shpSrcs, shpConts, 0, 0.);
				if (err) break;
			}

			if (doKML) {
				err = write_source_shapes(source, kmlSrcs, kmlConts, 0, 0.);
				if (err) break;
			}

			if (source->isParent) {

				if (doShape) {
					err = write_source_shapes(source->dtsRefSource, shpSrcs, shpConts, 0, 0.);
					if (err) break;
				}

				if (doKML) {
					err = write_source_shapes(source->dtsRefSource, kmlSrcs, kmlConts, 0, 0.);
					if (err) break;
				}

				for (dtsSource = source->dtsSources; dtsSource; dtsSource = dtsSource->next) {

					if (doShape) {
						err = write_source_shapes(dtsSource, shpSrcs, shpConts, 0, 0.);
						if (err) break;
					}

					if (doKML) {
						err = write_source_shapes(dtsSource, kmlSrcs, kmlConts, 0, 0.);
						if (err) break;
					}
				}
				if (err) break;
			}

			undesireds = source->undesireds;
			undesiredCount = source->undesiredCount;

			for (undesiredIndex = 0; undesiredIndex < undesiredCount; undesiredIndex++) {

				usource = SourceKeyIndex[undesireds[undesiredIndex].sourceKey];
				if (!usource) {
					log_error("Source structure index is corrupted");
					exit(1);
				}
				cullDist = undesireds[undesiredIndex].ixDistance;

				if (doShape) {
					err = write_source_shapes(usource, shpSrcs, shpConts, source->sourceKey, cullDist);
					if (err) break;
				}

				if (doKML) {
					err = write_source_shapes(usource, kmlSrcs, kmlConts, source->sourceKey, cullDist);
					if (err) break;
				}

				if (usource->isParent) {

					for (dtsSource = usource->dtsSources; dtsSource; dtsSource = dtsSource->next) {

						if (doShape) {
							err = write_source_shapes(dtsSource, shpSrcs, shpConts, source->sourceKey, cullDist);
							if (err) break;
						}

						if (doKML) {
							err = write_source_shapes(dtsSource, kmlSrcs, kmlConts, source->sourceKey, cullDist);
							if (err) break;
						}
					}

					if (err) break;
				}
			}
		}
	}

	if (doShape) {
		close_mapfile(shpSrcs);
		close_mapfile(shpConts);
	}

	if (doKML) {
		close_mapfile(kmlSrcs);
		close_mapfile(kmlConts);
	}

	return err;
}

// Write shapes for one source.

static int write_source_shapes(SOURCE *source, MAPFILE *mapSrcs, MAPFILE *mapConts, int desiredSourceKey,
		double cullingDistance) {

	static char srcKey[LEN_SOURCEKEY + 1], dtsKey[LEN_SOURCEKEY + 1], siteNum[LEN_SITENUMBER + 1],
		facID[LEN_FACILITYID + 1], serv[LEN_SERVICE + 1], chan[LEN_CHANNEL + 1], freq[LEN_FREQUENCY + 1],
		cntKey[LEN_COUNTRYKEY + 1], hamsl[LEN_HEIGHT + 1], haat[LEN_HEIGHT + 1], erp[LEN_ERP + 1],
		svclvl[LEN_SVCLEVEL + 1], azorient[LEN_ORIENT + 1], etilt[LEN_TILT + 1], mtilt[LEN_TILT + 1],
		mtiltorient[LEN_ORIENT + 1], dSrcKey[LEN_SOURCEKEY + 1], cullDist[LEN_DISTANCE + 1], *attrData[SRC_NATTR];

	static int doInit = 1;

	if (doInit) {

		attrData[0] = srcKey;
		attrData[1] = dtsKey;
		attrData[2] = siteNum;
		attrData[3] = facID;
		attrData[4] = serv;
		attrData[5] = chan;
		attrData[6] = freq;

		attrData[10] = cntKey;

		attrData[13] = hamsl;
		attrData[14] = haat;
		attrData[15] = erp;
		attrData[16] = svclvl;
		attrData[17] = azorient;
		attrData[18] = etilt;
		attrData[19] = mtilt;
		attrData[20] = mtiltorient;

		attrData[21] = dSrcKey;
		attrData[22] = cullDist;

		doInit = 0;
	}

	// For a DTS parent or any of it's secondary sources the DTSKEY field is set to the parent source key to group all
	// the records together.  Identification of the individual records can then be made using the SITENUMBER field;
	// that is >0 on transmitter sources, 0 on the parent and reference facility, the parent will have SOURCEKEY ==
	// DTSKEY, the reference will not.  Also for a DTS parent there are no height or ERP values.

	snprintf(srcKey, (LEN_SOURCEKEY + 1), "%d", source->sourceKey);
	if (source->parentSource) {
		snprintf(dtsKey, (LEN_SOURCEKEY + 1), "%d", source->parentSource->sourceKey);
	} else {
		if (source->isParent) {
			snprintf(dtsKey, (LEN_SOURCEKEY + 1), "%d", source->sourceKey);
		} else {
			dtsKey[0] = '\0';
		}
	}
	snprintf(siteNum, (LEN_SITENUMBER + 1), "%d", source->siteNumber);
	snprintf(facID, (LEN_FACILITYID + 1), "%d", source->facility_id);
	snprintf(serv, (LEN_SERVICE + 1), "%d", source->serviceTypeKey);
	if (RECORD_TYPE_WL == source->recordType) {
		chan[0] = '\0';
	} else {
		snprintf(chan, (LEN_CHANNEL + 1), "%d", source->channel);
	}
	snprintf(freq, (LEN_FREQUENCY + 1), "%.*f", PREC_FREQUENCY, source->frequency);
	attrData[7] = source->callSign;
	attrData[8] = source->city;
	attrData[9] = source->state;
	snprintf(cntKey, (LEN_COUNTRYKEY + 1), "%d", source->countryKey);
	attrData[11] = source->status;
	attrData[12] = source->fileNumber;
	if (source->isParent) {
		hamsl[0] = '\0';
		haat[0] = '\0';
		erp[0] = '\0';
		svclvl[0] = '\0';
		azorient[0] = '\0';
		etilt[0] = '\0';
		mtilt[0] = '\0';
		mtiltorient[0] = '\0';
	} else {
		snprintf(hamsl, (LEN_HEIGHT + 1), "%.*f", PREC_HEIGHT, source->actualHeightAMSL);
		snprintf(haat, (LEN_HEIGHT + 1), "%.*f", PREC_HEIGHT, source->actualOverallHAAT);
		snprintf(erp, (LEN_ERP + 1), "%.*f", PREC_ERP, atof(erpkw_string(source->peakERP)));
		if (!desiredSourceKey) {
			snprintf(svclvl, (LEN_SVCLEVEL + 1), "%.*f", PREC_SVCLEVEL, source->serviceLevel);
		} else {
			svclvl[0] = '\0';
		}
		if (source->hasHpat) {
			snprintf(azorient, (LEN_ORIENT + 1), "%.*f", PREC_ORIENT, source->hpatOrientation);
		} else {
			azorient[0] = '\0';
		}
		if (source->hasVpat) {
			snprintf(etilt, (LEN_TILT + 1), "%.*f", PREC_TILT, source->vpatElectricalTilt);
			snprintf(mtilt, (LEN_TILT + 1), "%.*f", PREC_TILT, source->vpatMechanicalTilt);
			snprintf(mtiltorient, (LEN_ORIENT + 1), "%.*f", PREC_ORIENT, source->vpatTiltOrientation);
		} else {
			etilt[0] = '\0';
			mtilt[0] = '\0';
			mtiltorient[0] = '\0';
		}
	}
	if (desiredSourceKey) {
		snprintf(dSrcKey, (LEN_SOURCEKEY + 1), "%d", desiredSourceKey);
		snprintf(cullDist, (LEN_DISTANCE + 1), "%.*f", PREC_DISTANCE, cullingDistance);
	} else {
		dSrcKey[0] = '\0';
		cullDist[0] = '\0';
	}

	if (write_shape(mapSrcs, source->latitude, source->longitude, NULL, 0, NULL, attrData)) {
		return 1;
	}

	if (!desiredSourceKey) {
		if (write_shape(mapConts, 0., 0., render_service_area(source), 1, NULL, attrData)) {
			return 1;
		}
	}

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Set up the service area for a source, projecting a contour or loading a geography as needed.  For a normal source
// this will just call do_service_contour() or do_service_geography().  For a DTS parent source this will recursively
// call those functions as needed for the individual DTS transmitters, then properly update the parent's cell grid
// limits to reflect the composite of all service areas.  It will also set up the reference facility area, which is
// always an FCC curves contour.  The parent gets a simple circle geography at the maximum distance limit around the
// DTS reference point.  Those do not affect the cell grid but are used for logical tests and map output.

// Arguments:

//   source  Source needing contour projection.

// Return <0 for major error, >0 for minor error, 0 for no error.

static int do_service_area(SOURCE *source) {

	// Service areas are undefined for wireless records, they are interference sources only.

	if (RECORD_TYPE_WL == source->recordType) {
		log_error("do_service_area() called for wireless source, sourceKey=%d", source->sourceKey);
		return 1;
	}

	int err = 0;

	// For a DTS parent, first project the reference facility contour and initialze the parent cell bounds and size
	// from that.  Then project contours or set up geography-based service areas for the individual sources and expand
	// the parent bounds to enclose all, in a manner that will preserve the grid alignment in local mode.  Starting
	// with the reference contour is for consistency with previous versions; but that is only significant in local
	// mode.  In global-grid mode it really doesn't matter.  Once the cell bounds on the reference or an individual
	// source are accumulated to the parent they are discarded; those are not cached or used elsewhere, all processing
	// of DTS sources is via the parent.  Note the parent also has a geography that is a logical boundary on the
	// service area, however that does not have to be considered for the grid bounds because it does not add to the
	// study area it only acts to exclude areas that are within the individual contours.

	if (source->isParent) {

		SOURCE *dtsSource = source->dtsRefSource;

		err = do_service_contour(dtsSource);
		if (err) return err;

		source->cellBounds = dtsSource->cellBounds;
		memset(&(dtsSource->cellBounds), 0, sizeof(INDEX_BOUNDS));
		source->cellLonSize = dtsSource->cellLonSize;
		source->cellArea = dtsSource->cellArea;

		INDEX_BOUNDS newBounds = source->cellBounds;

		for (dtsSource = source->dtsSources; dtsSource; dtsSource = dtsSource->next) {
			if (dtsSource->geography) {
				err = do_service_geography(dtsSource);
			} else {
				err = do_service_contour(dtsSource);
			}
			if (err) return err;
			extend_bounds_bounds(&newBounds, &(dtsSource->cellBounds));
			memset(&(dtsSource->cellBounds), 0, sizeof(INDEX_BOUNDS));
			dtsSource->cellLonSize = source->cellLonSize;
			dtsSource->cellArea = source->cellArea;
		}

		while (source->cellBounds.southLatIndex > newBounds.southLatIndex) {
			source->cellBounds.southLatIndex -= CellLatSize;
		}
		while (source->cellBounds.eastLonIndex > newBounds.eastLonIndex) {
			source->cellBounds.eastLonIndex -= source->cellLonSize;
		}
		while (source->cellBounds.northLatIndex < newBounds.northLatIndex) {
			source->cellBounds.northLatIndex += CellLatSize;
		}
		while (source->cellBounds.westLonIndex < newBounds.westLonIndex) {
			source->cellBounds.westLonIndex += source->cellLonSize;
		}

	// For a normal source just project contour or set up geography area.

	} else {

		if (source->geography) {
			err = do_service_geography(source);
		} else {
			err = do_service_contour(source);
		}
		if (err) return err;
	}

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Project a source contour, and set the cell grid limits for studying the source.

// Arguments:

//   source  Source needing contour projection.

// Return <0 for major error, >0 for minor error, 0 for no error.

static int do_service_contour(SOURCE *source) {

	// Release old data if needed.

	if (source->contour) {
		contour_free(source->contour);
		source->contour = NULL;
	}

	// Project the contour, this may be called even if the service area is defined by geography rather than contour,
	// in that case just project an FCC curves contour.  That may occur when a source is being replicated.

	CONTOUR *contour = NULL;
	int countryIndex = source->countryKey - 1, i;

	if ((SERVAREA_CONTOUR_LR_PERCENT == source->serviceAreaMode) ||
			(SERVAREA_CONTOUR_LR_RUN_ABOVE == source->serviceAreaMode) ||
			(SERVAREA_CONTOUR_LR_RUN_BELOW == source->serviceAreaMode)) {

		contour = project_lr_contour(source, source->serviceAreaMode, source->serviceAreaArg, source->contourLevel);
		if (!contour) return -1;

	} else {

		int curv;
		if (RECORD_TYPE_FM == source->recordType) {
			curv = Params.CurveSetFM[countryIndex];
		} else {
			if (source->dtv) {
				curv = Params.CurveSetDigital[countryIndex];
			} else {
				curv = Params.CurveSetAnalog[countryIndex];
			}
		}

		contour = project_fcc_contour(source, curv, source->contourLevel);
		if (!contour) return -1;

		if (SERVAREA_CONTOUR_FCC_ADD_DIST == source->serviceAreaMode) {

			contour->mode = SERVAREA_CONTOUR_FCC_ADD_DIST;

			for (i = 0; i < contour->count; i++) {
				contour->distance[i] += source->serviceAreaArg;
			}

		} else {

			if (SERVAREA_CONTOUR_FCC_ADD_PCNT == source->serviceAreaMode) {

				contour->mode = SERVAREA_CONTOUR_FCC_ADD_PCNT;

				double frac = 1. + (source->serviceAreaArg / 100.);

				for (i = 0; i < contour->count; i++) {
					contour->distance[i] *= frac;
				}
			}
		}
	}

	source->contour = contour;

	// Apply the maximum contour distance limit, if 0 there is no limit.

	double contMaxDist = 0.;
	switch (source->band) {
		case BAND_VLO1:
		case BAND_VLO2:
			contMaxDist = Params.ContourLimitVlo[countryIndex];
			break;
		case BAND_VHI:
			contMaxDist = Params.ContourLimitVhi[countryIndex];
			break;
		case BAND_UHF:
		default:
			contMaxDist = Params.ContourLimitUHF[countryIndex];
			break;
		case BAND_WL:
		case BAND_FMED:
		case BAND_FM:
			break;
	}
	if (contMaxDist > 0.) {
		double *dist = contour->distance;
		for (i = 0; i < contour->count; i++) {
			if (dist[i] > contMaxDist) {
				dist[i] = contMaxDist;
			}
		}
	}

	// Determine the bounding box of the final contour, interpolate the contour every 1 degree, convert the point to
	// latitude and longitude, and accumulate the extremes.  The coordinates() routine will always return a longitude
	// value in the same east/west direction as the center point, so this bounding box is always continuous.  If it
	// spans the 180-degree longitude line, one side or the other will have an "over-range" longitude value >180 or
	// <-180 degrees.  That over-range value will carry through the entire study for this source, all grid limits
	// and bounds will be similarily over-range, and individual study points will have over-range coordinates.  All
	// other code is designed to function correctly with over-range longitudes.

	// Note this deliberately does not use the render_contour() boundary for legacy reasons.  That function produces
	// a boundary using a polynomial curve fit to the projected contour points.  Past software versions only used
	// linear interpolation between contour points, so for compatibility with past results the same method is still
	// used here when determining which study points are part of the service area, see cell_setup().  The rendered
	// contour will only be used in situations where the precise position of the boundary is not critical.

	double contourLat = contour->latitude;
	double contourLon = contour->longitude;

	double lat = 0., lon = 0., azm, dist;

	double contourSouthLat = 999.;
	double contourEastLon = 999.;
	double contourNorthLat = -999.;
	double contourWestLon = -999.;

	for (i = 0; i < 360; i++) {

		azm = (double)i;
	
		dist = interp_cont(azm, contour);

		coordinates(contourLat, contourLon, azm, dist, &lat, &lon, Params.KilometersPerDegree);

		if (lat < contourSouthLat) {
			contourSouthLat = lat;
		}
		if (lon < contourEastLon) {
			contourEastLon = lon;
		}
		if (lat > contourNorthLat) {
			contourNorthLat = lat;
		}
		if (lon > contourWestLon) {
			contourWestLon = lon;
		}
	}

	// In global grid mode, the cell locations are defined by a uniform algorithm that is the same for all source
	// contours so all study cells are exactly the same where protected contours overlap.  Set the grid limits from the
	// actual bounding box, the center is not involved.  Compute the longitude size for the maximum number of cells on
	// the longitude axis, that will be at the grid edge closest to the equator, or at the equator if the grid spans
	// that (unlikely but possible).  That size can be used to set a grid storage array, see cell.c.

	// Note the grid is always extended by one longitude cell west.  The longitude cell size can increase away from the
	// base sizing row, usually that will mean rows have fewer cells, however the alignment of cells is global so there
	// are cases where a row with a larger size needs one extra cell vs. the base sizing row.  There is no harm in
	// having extra cells in the layout, excess is ignored because the cell study points will be outside the contour.

	if (GRID_TYPE_GLOBAL == Params.GridType) {

		source->cellBounds.southLatIndex = (int)floor((contourSouthLat * 3600.) / (double)CellLatSize) * CellLatSize;
		source->cellBounds.northLatIndex = (int)ceil((contourNorthLat * 3600.) / (double)CellLatSize) * CellLatSize;

		int minLonSizeLatIndex = 0;
		if (source->cellBounds.southLatIndex < 0) {
			if (source->cellBounds.northLatIndex < 0) {
				minLonSizeLatIndex = source->cellBounds.northLatIndex - CellLatSize;
			}
		} else {
			minLonSizeLatIndex = source->cellBounds.southLatIndex;
		}
		source->cellLonSize = global_lon_size(minLonSizeLatIndex);

		source->cellBounds.eastLonIndex =
			(int)floor((contourEastLon * 3600.) / (double)source->cellLonSize) * source->cellLonSize;
		source->cellBounds.westLonIndex =
			(int)ceil((contourWestLon * 3600.) / (double)source->cellLonSize) * source->cellLonSize;

		source->cellBounds.westLonIndex += source->cellLonSize;

	// For a local grid, first re-compute the bounding box using just the projected discrete points.  The alignment
	// and size of the cells has to be based on those bounds for compatability with past versions.  But save the
	// 1-degree-interpolated bounds, those are used to set actual limits after the initial layout.

	} else {

		INDEX_BOUNDS checkBounds;
		checkBounds.southLatIndex = (int)floor(contourSouthLat * 3600.);
		checkBounds.eastLonIndex = (int)floor(contourEastLon * 3600.);
		checkBounds.northLatIndex = (int)ceil(contourNorthLat * 3600.);
		checkBounds.westLonIndex = (int)ceil(contourWestLon * 3600.);

		contourSouthLat = 999.;
		contourEastLon = 999.;
		contourNorthLat = -999.;
		contourWestLon = -999.;

		double step = 360. / (double)contour->count;

		for (i = 0; i < contour->count; i++) {

			azm = (double)i * step;

			coordinates(contourLat, contourLon, azm, contour->distance[i], &lat, &lon, Params.KilometersPerDegree);

			if (lat < contourSouthLat) {
				contourSouthLat = lat;
			}
			if (lon < contourEastLon) {
				contourEastLon = lon;
			}
			if (lat > contourNorthLat) {
				contourNorthLat = lat;
			}
			if (lon > contourWestLon) {
				contourWestLon = lon;
			}
		}

		// Compute the fixed longitude size of cells which is uniform across the contour.  The latitude size is always
		// the same regardless of mode, and was computed earlier as CellLatSize.  In local mode the center intersection
		// of the grid lines separating cells lies at the center point of the bounding box, compute the grid edges by
		// offset from that center point.

		double cosMidLat = cos(((contourSouthLat + contourNorthLat) / 2.) * DEGREES_TO_RADIANS);
		source->cellLonSize = (int)(((Params.CellSize / (Params.KilometersPerDegree * cosMidLat)) * 3600.) + 0.5);
		if (source->cellLonSize < 1) {
			source->cellLonSize = 1;
		}

		int cenlat = (int)floor(((contourSouthLat + contourNorthLat) / 2.) * 3600.);
		int cenlon = (int)floor(((contourEastLon + contourWestLon) / 2.) * 3600.);

		int ncell = ((cenlat - checkBounds.southLatIndex) / CellLatSize) + 1;
		source->cellBounds.southLatIndex = cenlat - (ncell * CellLatSize);
		ncell = ((cenlon - checkBounds.eastLonIndex) / source->cellLonSize) + 1;
		source->cellBounds.eastLonIndex = cenlon - (ncell * source->cellLonSize);
		ncell = ((checkBounds.northLatIndex - cenlat) / CellLatSize) + 1;
		source->cellBounds.northLatIndex = cenlat + (ncell * CellLatSize);
		ncell = ((checkBounds.westLonIndex - cenlon) / source->cellLonSize) + 1;
		source->cellBounds.westLonIndex = cenlon + (ncell * source->cellLonSize);

		// A uniform area for all cells is used based on the mid-latitude.

		source->cellArea = cell_area(cenlat, source->cellLonSize);
	}

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Project a contour from a source by FCC curves.

// Arguments:

//   source        Source needing contour projection.
//   curveSet      FCC curve set, FCC_F50, FCC_F10, or FCC_F90.
//   contourLevel  Contour level to project.

// Return contour data, or NULL for error.  Error should be considered serious.

CONTOUR *project_fcc_contour(SOURCE *source, int curveSet, double contourLevel) {

	int countryIndex = source->countryKey - 1;
	int haatCount =
		(SERV_TVLP == source->service) ? Params.HAATCountLPTV[countryIndex] : Params.HAATCount[countryIndex];
	int contourCount = Params.ContourCount[countryIndex];

	// Compute HAAT.

	double *haat = compute_source_haat(source, haatCount);
	if (!haat) return NULL;

	// Derive a horizontal pattern if needed.  This is done if the parameter requests it and the source has either a
	// vertical pattern with mechanical beam tilt, or a matrix pattern.  Other parameters regarding use of vertical
	// patterns or mechanical tilt are ignored for this operation, see vpat_lookup().  When the derived pattern is 
	// present that will be used for contour projection without any vertical pattern adjustment.  Terrain-sensitive
	// projections will continue to use the actual patterns according to the other parameters.  This is never done for
	// a replication source; if the original had a derived pattern that was used in the replication process so the
	// derivation is already applied implicitly to the replication pattern.  The derivation may be at the horizontal
	// plane (0 degrees depression) or at the radio horizon, HAAT may be needed for the horizon angle calculation.

	double azm, eah, minHAAT = Params.MinimumHAAT[countryIndex];
	int i;

	if ((DERIVE_HPAT_NO != Params.ContourDeriveHpat[countryIndex]) && !source->conthpat && !source->origSourceKey &&
			((source->hasVpat && (source->vpatMechanicalTilt != 0.)) || source->hasMpat)) {

		double *pat = (double *)mem_alloc(360 * sizeof(double));
		source->conthpat = pat;

		eah = 0.;

		for (i = 0; i < 360; i++) {
			azm = (double)i;
			eah = interp_min(azm, haat, haatCount, minHAAT, 1);
			pat[i] = erp_lookup(source, azm) + vpat_lookup(source, eah, azm, 0., 0., 0., VPAT_MODE_DERIVE, NULL, NULL);
		}

		for (i = 0; i < 360; i++) {
			pat[i] -= source->peakERP;
		}
	}

	// Allocate the contour structure.

	CONTOUR *contour = contour_alloc(source->latitude, source->longitude, SERVAREA_CONTOUR_FCC, contourCount);
	double *dist = contour->distance;

	// At each of contourCount radials look up or interpolate an HAAT, get ERP, and project distance to contour.  Note
	// the algorithm in fcc_curve() will always use the effective-height method for depression angle calculation
	// regardless of the global parameter setting, so it does not need to look up any ground elevations.

	if (ContourDebug) {
		fprintf(ContourDebug, "projecting contour %d %s %c%d %s\naz,haat,erp_dbk,dep,lkup,vpat,dist\nlevel=%.3f\n",
			source->facility_id, source->callSign, (source->dtv ? 'D' : 'N'), source->channel, source->status,
			source->contourLevel);
	}

	double erp, dep, lup, vpt, contourStep = 360. / (double)contourCount;

	for (i = 0; i < contourCount; i++) {

		azm = (double)i * contourStep;
		eah = interp_min(azm, haat, haatCount, minHAAT, 1);
		erp = contour_erp_lookup(source, azm);
		fcc_curve(&erp, &contourLevel, (dist + i), eah, source->band, FCC_DST, curveSet, Params.OffCurveLookupMethod,
			source, azm, &dep, &lup, &vpt);

		if (ContourDebug) {
			fprintf(ContourDebug, "%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f\n", azm, eah, erp, dep, lup, vpt, dist[i]);
		}
	}

	mem_free(haat);
	haat = NULL;

	return contour;
}


//---------------------------------------------------------------------------------------------------------------------
// Compute HAAT values for a source, wrapper around function in terrain.c.

// Arguments:

//   source     The source needing HAAT data.
//   haatCount  Number of evenly-spaced radials to compute.

// Return HAAT data, or NULL for error.  Error should be considered serious.

double *compute_source_haat(SOURCE *source, int haatCount) {

	if (ContourDebug) {
		fprintf(ContourDebug, "computing haat %d %s %c%d %s\ndist,lat,lon,elev\nlat=%.9f lon=%.9f amsl=%.3f\n",
			source->facility_id, source->callSign, (source->dtv ? 'D' : 'N'), source->channel, source->status,
			source->latitude, source->longitude, source->actualHeightAMSL);
	}

	double *haat = (double *)mem_alloc(haatCount * sizeof(double));

	int countryIndex = source->countryKey - 1;
	double distInc = 1. / ((RECORD_TYPE_WL == source->recordType) ? Params.WirelessTerrAvgPpk :
		Params.TerrAvgPpk[countryIndex]);

	int err = compute_haat(source->latitude, source->longitude, source->actualHeightAMSL, haat, haatCount,
		Params.TerrAvgDb, Params.AVETStartDistance[countryIndex], Params.AVETEndDistance[countryIndex], distInc,
		Params.KilometersPerDegree, ContourDebug);
	if (err) {
		log_error("Terrain lookup failed: db=%d err=%d", Params.TerrAvgDb, err);
		mem_free(haat);
		return NULL;
	}

	return haat;
}


//---------------------------------------------------------------------------------------------------------------------
// Project a contour from a source using Longley-Rice.  On each contour radial, extract a terrain profile to a large
// fixed distance and incrementally call Longley-Rice along the profile computing the field strength at individual
// points.  A distance increment parameter determines how many calculation points are used, the Longley-Rice code
// always places the receiver at a profile point so the distance is a target used to determine an integral point
// increment.  Determine the contour distance by one of three methods.  First is the greatest distance at which a
// specified percentage of preceding points are above the contour level.  Second is the distance at the end of the
// last contiguous run of points above the contour level that is equal or longer than a specified length.  Third is
// the distance at the start of the first contiguous run of points below the contour level that is equal or longer
// than a specified length.  Longley-Rice errors are always ignored.

// Arguments:

//   source        Source needing contour projection.
//   mode          Contour projection method to use, SERVAREA_CONTOUR_LR_*.
//   arg           Argument for the contour method.
//   contourLevel  Contour level to project.

// Return contour data, or NULL for error.  Error should be considered serious.

static CONTOUR *project_lr_contour(SOURCE *source, int mode, double arg, double contourLevel) {

	int countryIndex = source->countryKey - 1;

	// Set up the propagation model data block.  This has it's own set of parameters for terrain extraction and model
	// settings, rather than using the terrain-sensitive model parameters.  The contour location should not change if
	// the terrain-sensitive parameters are changed, e.g. when that is using some model other than Longley-Rice.

	static MODEL_DATA data;

	data.model = MODEL_LONGLEY_RICE;
	data.latitude = source->latitude;
	data.longitude = source->longitude;
	data.distance = Params.MaximumDistance;
	data.terrainDb = Params.LRConTerrDb;
	data.transmitHeightAGL = source->heightAGL;
	data.receiveHeightAGL = Params.LRConReceiveHeight;
	data.frequency = source->frequency;
	if (source->dtv) {
		data.percentLocation = Params.LRConDigitalLocation;
		data.percentTime = Params.LRConDigitalTime;
		data.percentConfidence = Params.LRConDigitalConfidence;
	} else {
		data.percentLocation = Params.LRConAnalogLocation;
		data.percentTime = Params.LRConAnalogTime;
		data.percentConfidence = Params.LRConAnalogConfidence;
	}
	data.atmosphericRefractivity = Params.LRConAtmosphericRefractivity;
	data.groundPermittivity = Params.LRConGroundPermittivity;
	data.groundConductivity = Params.LRConGroundConductivity;
	data.signalPolarization = Params.LRConSignalPolarization;
	data.serviceMode = Params.LRConServiceMode;
	data.climateType = Params.LRConClimateType;
	data.profilePpk = Params.LRConTerrPpk;
	data.kilometersPerDegree = Params.KilometersPerDegree;

	data.profileCount = (int)((data.distance * data.profilePpk) + 0.5) + 1;

	data.fieldStrength = 0.;
	data.errorCode = 0;
	data.errorMessage[0] = '\0';

	static float *elev = NULL;
	static int elevMax = 0;

	if (data.profileCount > elevMax) {
		elevMax = data.profileCount;
		elev = (float *)mem_realloc(elev, ((elevMax + PROFILE_OFFSET) * sizeof(float)));
	}
	data.profile = elev + PROFILE_OFFSET;

	int contourCount = Params.ContourCount[countryIndex];
	double contourStep = 360. / (double)contourCount;

	CONTOUR *contour = contour_alloc(data.latitude, data.longitude, mode, contourCount);
	double *dist = contour->distance;

	int calcStep = (int)rint(Params.LRConDistanceStep * data.profilePpk);
	if (calcStep < 1) calcStep = 1;

	double frac = 0.;
	int minrun = 0;
	if (SERVAREA_CONTOUR_LR_PERCENT == mode) {
		frac = arg / 100.;
	} else {
		minrun = ((int)(arg * data.profilePpk) / calcStep) + 1;
	}

	double erp, fld;
	int i, j, np, ncalc, contpt, nrun, err;

	for (i = 0; i < contourCount; i++) {

		data.bearing = (double)i * contourStep;

		erp = erp_lookup(source, data.bearing);

		err = terrain_profile(data.latitude, data.longitude, data.bearing, data.distance, data.profilePpk,
			data.terrainDb, elevMax, data.profile, &np, Params.KilometersPerDegree);
		if (err) {
			log_error("Terrain lookup failed: lat=%.8f lon=%.8f bear=%.2f dist=%.2f db=%d err=%d", data.latitude,
				data.longitude, data.bearing, data.distance, data.terrainDb, err);
			return NULL;
		}

		ncalc = 0;
		contpt = 0;
		nrun = 0;

		for (j = calcStep; j < np; j += calcStep) {

			ncalc++;

			data.profileCount = j + 1;
			longley_rice(&data);

			fld = data.fieldStrength + erp + vpat_lookup(source, data.transmitHeightAGL, data.bearing,
				((double)j / data.profilePpk), (double)data.profile[j], data.receiveHeightAGL, VPAT_MODE_PROP,
				NULL, NULL);

			switch (mode) {

				case SERVAREA_CONTOUR_LR_PERCENT: {
					if (fld >= contourLevel) {
						++nrun;
					}
					if (((double)nrun / (double)ncalc) >= frac) {
						contpt = j;
					}
					break;
				}

				case SERVAREA_CONTOUR_LR_RUN_ABOVE: {
					if (fld >= contourLevel) {
						if (++nrun >= minrun) {
							contpt = j;
						}
					} else {
						nrun = 0;
					}
					break;
				}

				case SERVAREA_CONTOUR_LR_RUN_BELOW:
				default: {
					if (fld >= contourLevel) {
						contpt = j;
						nrun = 0;
					} else {
						if (++nrun >= minrun) {
							j = np;
						}
					}
					break;
				}
			}
		}

		dist[i] = ((double)contpt + 0.5) / data.profilePpk;
	}

	return contour;
}


//---------------------------------------------------------------------------------------------------------------------
// Allocate a CONTOUR structure and a dependent distance array only (points are added later as needed).

// Arguments:

//   latitude   Contour center latitude.
//   longitude  Contour center longitude.
//   mode       Contour mode, SERVAREA_CONTOUR_* (except DEFAULT).
//   count      Count of contour projection radials.

// Return contour, no error return, allocation errors case process to abort.

CONTOUR *contour_alloc(double latitude, double longitude, int mode, int count) {

	CONTOUR *contour = mem_zalloc(sizeof(CONTOUR));
	contour->latitude = latitude;
	contour->longitude = longitude;
	contour->mode = mode;
	contour->count = count;
	contour->distance = mem_zalloc(count * sizeof(double));

	return contour;
}


//---------------------------------------------------------------------------------------------------------------------
// Free a CONTOUR structure and dependent arrays.

// Arguments:

//   contour  The contour to free.

void contour_free(CONTOUR *contour) {

	if (contour->distance) {
		mem_free(contour->distance);
		contour->distance = NULL;
	}
	if (contour->points) {
		geopoints_free(contour->points);
		contour->points = NULL;
	}
	mem_free(contour);
}


//---------------------------------------------------------------------------------------------------------------------
// Set up the service area of a source by geography, set the cell grid limits for studying the source.

// Arguments:

//   source  Source needing area setup.

// Return <0 for major error, >0 for minor error, 0 for no error.

static int do_service_geography(SOURCE *source) {

	// The geography must already be loaded.

	if (!source->geography) {
		log_error("do_service_geography() called on source with no geography, sourceKey=%d", source->sourceKey);
		return 1;
	}

	// Render the geography to get the bounding box, set up cell grid.  See do_service_contour().

	GEOPOINTS *pts = render_geography(source->geography);

	if (GRID_TYPE_GLOBAL == Params.GridType) {

		source->cellBounds.southLatIndex = (int)floor((pts->xlts * 3600.) / (double)CellLatSize) * CellLatSize;
		source->cellBounds.northLatIndex = (int)ceil((pts->xltn * 3600.) / (double)CellLatSize) * CellLatSize;

		int minLonSizeLatIndex = 0;
		if (source->cellBounds.southLatIndex < 0) {
			if (source->cellBounds.northLatIndex < 0) {
				minLonSizeLatIndex = source->cellBounds.northLatIndex - CellLatSize;
			}
		} else {
			minLonSizeLatIndex = source->cellBounds.southLatIndex;
		}
		source->cellLonSize = global_lon_size(minLonSizeLatIndex);

		source->cellBounds.eastLonIndex =
			(int)floor((pts->xlne * 3600.) / (double)source->cellLonSize) * source->cellLonSize;
		source->cellBounds.westLonIndex =
			(int)ceil((pts->xlnw * 3600.) / (double)source->cellLonSize) * source->cellLonSize;

		source->cellBounds.westLonIndex += source->cellLonSize;

	} else {

		INDEX_BOUNDS checkBounds;
		checkBounds.southLatIndex = (int)floor(pts->xlts * 3600.);
		checkBounds.eastLonIndex = (int)floor(pts->xlne * 3600.);
		checkBounds.northLatIndex = (int)ceil(pts->xltn * 3600.);
		checkBounds.westLonIndex = (int)ceil(pts->xlnw * 3600.);

		double cosMidLat = cos(((pts->xlts + pts->xltn) / 2.) * DEGREES_TO_RADIANS);
		source->cellLonSize = (int)(((Params.CellSize / (Params.KilometersPerDegree * cosMidLat)) * 3600.) + 0.5);
		if (source->cellLonSize < 1) {
			source->cellLonSize = 1;
		}

		int cenlat = (int)floor(((pts->xlts + pts->xltn) / 2.) * 3600.);
		int cenlon = (int)floor(((pts->xlne + pts->xlnw) / 2.) * 3600.);

		int ncell = ((cenlat - checkBounds.southLatIndex) / CellLatSize) + 1;
		source->cellBounds.southLatIndex = cenlat - (ncell * CellLatSize);
		ncell = ((cenlon - checkBounds.eastLonIndex) / source->cellLonSize) + 1;
		source->cellBounds.eastLonIndex = cenlon - (ncell * source->cellLonSize);
		ncell = ((checkBounds.northLatIndex - cenlat) / CellLatSize) + 1;
		source->cellBounds.northLatIndex = cenlat + (ncell * CellLatSize);
		ncell = ((checkBounds.westLonIndex - cenlon) / source->cellLonSize) + 1;
		source->cellBounds.westLonIndex = cenlon + (ncell * source->cellLonSize);

		source->cellArea = cell_area(cenlat, source->cellLonSize);
	}

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Build the list of undesired sources for a source by applying the interference rules.  On the first call for a study
// load the rules.  No rules is an error, there have to be some or what is the point?  This trusts that the UI
// application has checked for duplicate and contradictory rules, it simply applies them as found.

// Arguments:

//   source  The source needing an undesired list.

// Return <0 for major error, >0 for minor error, 0 for no error.

typedef struct {                     // Interference rule.
	short countryKey;                // Desired country, service type, and signal type, and undesired service type,
	short serviceTypeKey;            //  signal type, and channel delta define the initial match for desired-undesired
	short signalTypeKey;             //  pairs.  Implicit exclusions are a source never pairs with itself, and rules
	short undesiredServiceTypeKey;   //  never apply across bands.
	short undesiredSignalTypeKey;
	short channelDelta;
	short firstChannel;              // The channel range, requirement for mutual frequency offset, and emission mask
	short lastChannel;               //  may exclude the rule from matching a particular pair of sources.
	short frequencyOffset;           // 0 applies regardless, else FREQUENCY_OFFSET_WITHOUT or FREQUENCY_OFFSET_WITH.
	short emissionMaskKey;
	double ixDistance;               // Distance excludes individual points, rough-checked with a worst-case contour.
	double requiredDU;               // Required (minimum) D/U for no interference, and percent time for undesired
	short percentTime;               //  path loss prediction.  Percent is stored as fixed precision to 0.01%.
} IX_RULE;

static int find_undesired(SOURCE *source) {

	static int initForStudyKey = 0;
	static IX_RULE *ixRules = NULL;
	static int ixRuleCount = 0;

	// Lookup values for wireless culling distance tables.  ERPs in dBk.

	static double wirelessCullHAAT[WL_CULL_HAAT_COUNT] = {
		305., 200., 150., 100., 80., 65., 50., 35.
	};
	static double wirelessCullERP[WL_CULL_ERP_COUNT] = {
		6.99, 6.02, 4.77, 3.01, 0.00, -1.25, -3.01, -6.02, -10.00
	};

	int ixRuleIndex;
	IX_RULE *ixRule;

	if (StudyKey != initForStudyKey) {

		char query[MAX_QUERY];
		MYSQL_RES *myResult;
		MYSQL_ROW fields;
		unsigned long *fieldLengths;

		snprintf(query, MAX_QUERY, "SELECT ix_rule.country_key, ix_rule.service_type_key, ix_rule.signal_type_key, ix_rule.undesired_service_type_key, ix_rule.undesired_signal_type_key, channel_delta.delta, channel_band.first_channel, channel_band.last_channel, ix_rule.frequency_offset, ix_rule.emission_mask_key, ix_rule.distance, ix_rule.required_du, ix_rule.undesired_time FROM %s_%d.ix_rule JOIN %s.channel_delta USING (channel_delta_key) LEFT JOIN %s.channel_band USING (channel_band_key) WHERE ix_rule.is_active ORDER BY ix_rule_key;", DbName, StudyKey, DbName, DbName);
		if (mysql_query(MyConnection, query)) {
			log_db_error("Interference rule query failed (1)");
			return -1;
		}

		myResult = mysql_store_result(MyConnection);
		if (!myResult) {
			log_db_error("Interference rule query failed (2)");
			return -1;
		}

		ixRuleCount = (int)mysql_num_rows(myResult);
		if (!ixRuleCount) {
			mysql_free_result(myResult);
			mem_free(ixRules);
			ixRules = NULL;
			initForStudyKey = 0;
			log_error("No interference rules found for studyKey=%d", StudyKey);
			return 1;
		}

		size_t size = ixRuleCount * sizeof(IX_RULE);
		ixRules = (IX_RULE *)mem_realloc(ixRules, size);
		memset(ixRules, 0, size);

		ixRule = ixRules;
		for (ixRuleIndex = 0; ixRuleIndex < ixRuleCount; ixRuleIndex++, ixRule++) {

			fields = mysql_fetch_row(myResult);
			fieldLengths = mysql_fetch_lengths(myResult);
			if (!fields || !fieldLengths) {
				mysql_free_result(myResult);
				ixRuleCount = 0;
				mem_free(ixRules);
				ixRules = NULL;
				initForStudyKey = 0;
				log_db_error("Interference rule query failed (3)");
				return -1;
			}

			ixRule->countryKey = (short)atoi(fields[0]);
			ixRule->serviceTypeKey = (short)atoi(fields[1]);
			ixRule->signalTypeKey = (short)atoi(fields[2]);
			ixRule->undesiredServiceTypeKey = (short)atoi(fields[3]);
			ixRule->undesiredSignalTypeKey = (short)atoi(fields[4]);
			ixRule->channelDelta = (short)atoi(fields[5]);
			if (fieldLengths[6] > 0) {
				ixRule->firstChannel = (short)atoi(fields[6]);
				ixRule->lastChannel = (short)atoi(fields[7]);
			}
			ixRule->frequencyOffset = (short)atoi(fields[8]);
			ixRule->emissionMaskKey = (short)atoi(fields[9]);
			ixRule->ixDistance = atof(fields[10]);
			ixRule->requiredDU = atof(fields[11]);
			ixRule->percentTime = (short)rint(atof(fields[12]) * 100.);
		}

		mysql_free_result(myResult);

		initForStudyKey = StudyKey;
	}

	// Release old list if any.

	if (source->undesireds) {
		mem_free(source->undesireds);
		source->undesireds = NULL;
	}

	// Build the list of undesireds.

	UNDESIRED *undesireds = NULL;
	int undesiredCount = 0, undesiredMaxCount = 0, usourceIndex, match, adelt;
	SOURCE *usource, *dtsSource;
	double ixDistance, reqDU, duThrD, duThrU;

	int isTV = (RECORD_TYPE_TV == source->recordType);

	// Loop over sources looking for undesireds, only consider those in the scenario with isUndesired set, then
	// check for a rule match.  Wireless sources are ignored here, they get special handling if needed below.  Cross-
	// service relationships e.g. FM vs. TV channel 6 do not appear as rules, those also get special handling below.

	usource = Sources;
	for (usourceIndex = 0; usourceIndex < SourceCount; usourceIndex++, usource++) {

		if (!usource->inScenario || !usource->isUndesired) {
			continue;
		}

		if (RECORD_TYPE_WL == usource->recordType) {
			continue;
		}

		ixRule = ixRules;
		for (ixRuleIndex = 0; ixRuleIndex < ixRuleCount; ixRuleIndex++, ixRule++) {

			if (ixRule->serviceTypeKey != source->serviceTypeKey) {
				continue;
			}
			if (ixRule->signalTypeKey != source->signalTypeKey) {
				continue;
			}
			if (ixRule->countryKey != source->countryKey) {
				continue;
			}
			if ((ixRule->firstChannel > 0) &&
					((source->channel < ixRule->firstChannel) || (source->channel > ixRule->lastChannel))) {
				continue;
			}

			if (isTV && (FREQUENCY_OFFSET_WITH == ixRule->frequencyOffset) &&
					(FREQ_OFFSET_NONE == source->frequencyOffsetKey)) {
				continue;
			}

			if (ixRule->channelDelta != (usource->channel - source->channel)) {
				continue;
			}
			if (ixRule->undesiredServiceTypeKey != usource->serviceTypeKey) {
				continue;
			}
			if (ixRule->undesiredSignalTypeKey != usource->signalTypeKey) {
				continue;
			}
			if (usource->band != source->band) {
				continue;
			}

			if (isTV && (FREQUENCY_OFFSET_WITH == ixRule->frequencyOffset) &&
					((FREQ_OFFSET_NONE == usource->frequencyOffsetKey) ||
					(source->frequencyOffsetKey == usource->frequencyOffsetKey))) {
				continue;
			}
			if (isTV && (FREQUENCY_OFFSET_WITHOUT == ixRule->frequencyOffset) &&
					(FREQ_OFFSET_NONE != source->frequencyOffsetKey) &&
					(FREQ_OFFSET_NONE != usource->frequencyOffsetKey) &&
					(source->frequencyOffsetKey != usource->frequencyOffsetKey)) {
				continue;
			}
					
			if (isTV && (LPTV_MASK_NONE != ixRule->emissionMaskKey) &&
					((LPTV_MASK_NONE == usource->emissionMaskKey) ||
					(ixRule->emissionMaskKey != usource->emissionMaskKey))) {
				continue;
			}

			// A source does not interfere with itself.

			if (usource == source) {
				continue;
			}

			// Rule match found, now check distance to determine if the undesired should be included.  Any undesired
			// that might be close enough to a study point is included.  The final criteria during analysis will be
			// the distance between the undesired and an individual study point, compared to the distance from the
			// interference rule.  At this stage the distance is checked against the rendered service area boundary,
			// with a small excess distance to be safe, see check_distance().

			// When DTS sources are involved as either desired or undesired, or both, the distance check will involve
			// more than one location for the DTS source(s).  For a desired DTS, each of the individual desired DTS
			// transmitters is checked and the undesired included if it is close enough to any one of those.  However
			// if the TruncateDTS option is on meaning the service area is limited by the reference facility contour
			// and maximum distance around the reference point, those points are also checked and the undesired must
			// also be close enough to one of those.  For an undesired DTS, if the CheckIndividualDTSDistance option
			// is on each undesired DTS transmitter is checked, if any one is close enough the undesired is included.
			// If the individual-distance option is off, only the undesired DTS reference point is checked.  That
			// undesired DTS logic is in the check_distance() function, see below.

			ixDistance = ixRule->ixDistance;

			if (source->isParent) {

				for (dtsSource = source->dtsSources; dtsSource; dtsSource = dtsSource->next) {
					if ((match = check_distance(dtsSource, usource, ixDistance))) {
						break;
					}
				}

				if (match && Params.TruncateDTS) {
					if (!check_distance(source, usource, ixDistance) &&
							!check_distance(source->dtsRefSource, usource, ixDistance)) {
						match = 0;
					}
				}

			} else {

				match = check_distance(source, usource, ixDistance);
			}

			if (!match) {
				continue;
			}

			// Special rules for FM undesireds.  For an adjacent-channel operating with IBOC digital, the minimum D/U
			// is determined by formula so that increases as the relative IBOC power level increases.  However the D/U
			// cannot be less than the non-IBOC value.  The 53- and 54-channel rules have an extra condition, both the
			// desired and undesired signals must be above 91 dBu before the D/U test occurs.  If either is below there
			// can be no interference.  Setting the threshold to 0 disables the extra test, see code in study.c.

			reqDU = ixRule->requiredDU;
			duThrD = 0.;
			duThrU = 0.;

			if (RECORD_TYPE_FM == usource->recordType) {
				adelt = abs(ixRule->channelDelta);
				if (Params.AdjustFMForIBOC && usource->iboc && (1 == adelt)) {
					reqDU = ((10. * log10(usource->ibocFraction)) + 33.6) / 2.27;
					if (reqDU < ixRule->requiredDU) {
						reqDU = ixRule->requiredDU;
					}
				}
				if ((53 == adelt) || (54 == adelt)) {
					duThrD = 91.;
					duThrU = 91.;
				}
			}

			if (undesiredCount == undesiredMaxCount) {
				undesiredMaxCount += 10;
				undesireds = (UNDESIRED *)mem_realloc(undesireds, (undesiredMaxCount * sizeof(UNDESIRED)));
			}

			memset(&(undesireds[undesiredCount]), 0, sizeof(UNDESIRED));
			undesireds[undesiredCount].sourceKey = usource->sourceKey;
			undesireds[undesiredCount].percentTime = ixRule->percentTime;
			undesireds[undesiredCount].ixDistance = ixDistance;
			undesireds[undesiredCount].checkIxDistance = 1;
			undesireds[undesiredCount].requiredDU = reqDU;
			undesireds[undesiredCount].duThresholdD = duThrD;
			undesireds[undesiredCount].duThresholdU = duThrU;
			undesireds[undesiredCount].adjustDU = (source->dtv && (0 == ixRule->channelDelta));
			undesiredCount++;
		}
	}

	// In an OET-74 study for a digital TV desired source, check for wireless spectral overlap and search wireless
	// undesireds if needed.  The individual wireless sources are added to the undesireds list so fields for each are
	// calculated, however the wireless interference is always the sum of all wireless sources; the individual sources
	// will not be tallied or reported separately.

	if (source->dtv && (STUDY_TYPE_TV_OET74 == StudyType)) {

		double tvLower = source->frequency - 3., tvUpper = source->frequency + 3., overlap;
		if (Params.WirelessUpperBandEdge < tvUpper) {
			overlap = Params.WirelessUpperBandEdge - tvLower;
		} else {
			overlap = tvUpper - Params.WirelessLowerBandEdge;
		}
		if (overlap > Params.WirelessBandwidth) {
			overlap = Params.WirelessBandwidth;
		}

		if (overlap >= -5.) {

			int overlapIndex = 0;
			if (overlap > 4.) {
				overlapIndex = 5;
			} else {
				if (overlap > 3.) {
					overlapIndex = 4;
				} else {
					if (overlap > 2.) {
						overlapIndex = 3;
					} else {
						if (overlap > 1.) {
							overlapIndex = 2;
						} else {
							if (overlap > 0.) {
								overlapIndex = 1;
							}
						}
					}
				}
			}

			// The distance logic is different for wireless.  If a wireless undesired is within a distance limit
			// determined from a lookup table using ERP and HAAT, the wireless undesired is included in the list.  It
			// will then be considered at all study points regardless of distance.  In other words the check here is
			// the only distance check; once a wireless undesired is in the list for a desired there are no further
			// exclusionary distance checks.  That means the check here must use the actual service areas, not the
			// rule-extra-distance approximation.  When the desired is a DTS multiple area have to be checked, possibly
			// including the reference facility contour.  The logic here is analogous to the distance-check logic for
			// the normal interference rule case, see comments above for details.  Along the way this also does a check
			// to see if the wireless is actually inside a service area; if so it is always included, but a warning
			// message is sent to the study report because wireless sites should never be inside the area.

			int haatIndex, erpIndex;

			usource = Sources;
			for (usourceIndex = 0; usourceIndex < SourceCount; usourceIndex++, usource++) {

				if (!usource->inScenario || !usource->isUndesired) {
					continue;
				}
				if (RECORD_TYPE_WL != usource->recordType) {
					continue;
				}

				for (haatIndex = 1; haatIndex < WL_CULL_HAAT_COUNT; haatIndex++) {
					if (usource->actualOverallHAAT > wirelessCullHAAT[haatIndex]) {
						break;
					}
				}
				haatIndex--;

				for (erpIndex = 1; erpIndex < WL_CULL_ERP_COUNT; erpIndex++) {
					if (usource->peakERP > wirelessCullERP[erpIndex]) {
						break;
					}
				}
				erpIndex--;

				ixDistance = Params.WirelessCullDistance[(overlapIndex * WL_CULL_HAAT_COUNT * WL_CULL_ERP_COUNT) +
					(haatIndex * WL_CULL_ERP_COUNT) + erpIndex];

				// Check service areas, see comments above, also check_service_area().  That returns -1 if inside the
				// area, 1 if outside but within culling distance, else 0.  It has special logic for the DTS parent
				// where the area is just a fixed-radius circle.  Note for a DTS if the truncate option is on, the
				// checks of the reference circle and contour are exclusionary; having the wireless inside one of those
				// is not significant and so not reported, it only matters if it is inside one of the actual areas.

				if (ContourDebug) {
					fprintf(ContourDebug, "culling wireless records for desired sourceKey %d, culling distance %.2f\n",
						source->sourceKey, ixDistance);
				}

				if (source->isParent) {

					for (dtsSource = source->dtsSources; dtsSource; dtsSource = dtsSource->next) {
						if ((match = check_service_area(dtsSource, usource, ixDistance))) {
							break;
						}
					}

					if (match && Params.TruncateDTS) {
						if (!check_service_area(source, usource, ixDistance) &&
								!check_service_area(source->dtsRefSource, usource, ixDistance)) {
							if (ContourDebug) {
								fprintf(ContourDebug,
									"  wireless sourceKey %d excluded, too far from truncated DTS service boundary\n",
									usource->sourceKey);
							}
							match = 0;
						}
					}

				} else {

					match = check_service_area(source, usource, ixDistance);
				}

				if (!match) {
					continue;
				}

				if (undesiredCount == undesiredMaxCount) {
					undesiredMaxCount += 10;
					undesireds = (UNDESIRED *)mem_realloc(undesireds, (undesiredMaxCount * sizeof(UNDESIRED)));
				}

				memset(&(undesireds[undesiredCount]), 0, sizeof(UNDESIRED));
				undesireds[undesiredCount].sourceKey = usource->sourceKey;
				undesireds[undesiredCount].percentTime = (short)rint(Params.WirelessUndesiredTime * 100.);
				undesireds[undesiredCount].ixDistance = ixDistance;
				undesireds[undesiredCount].requiredDU = Params.WirelessDU[overlapIndex];
				undesireds[undesiredCount].adjustDU = (overlapIndex > 0);
				undesireds[undesiredCount].insideServiceArea = (match < 0);
				undesiredCount++;
			}
		}
	}

	// In an FM vs. TV channel 6 study for a TV channel 6 desired, check for FM undesireds on NCE channels meeting the
	// culling distances.  This uses the distance table in rules section 73.525 regardless of whether the TV is analog
	// or digital, or what method is used to determine the required D/U.  The distance check is site-to-site, all
	// points in the service area will be checked for interference if the undesired is included.

	if ((STUDY_TYPE_TV6_FM == StudyType) && isTV && (6 == source->channel)) {

		int ci;
		double dist;

		usource = Sources;
		for (usourceIndex = 0; usourceIndex < SourceCount; usourceIndex++, usource++) {

			if (!usource->inScenario || !usource->isUndesired) {
				continue;
			}
			if (RECORD_TYPE_FM != usource->recordType) {
				continue;
			}
			if (BAND_FMED != usource->band) {
				continue;
			}

			ci = usource->channel - TV6_FM_CHAN_BASE;
			if (ci < 0) {
				ci = 0;
			}
			if (ci >= TV6_FM_CHAN_COUNT) {
				ci = TV6_FM_CHAN_COUNT - 1;
			}
			ixDistance = Params.TV6FMDistance[ci];

			match = 0;

			if (source->isParent) {

				for (dtsSource = source->dtsSources; dtsSource; dtsSource = dtsSource->next) {
					bear_distance(dtsSource->latitude, dtsSource->longitude, usource->latitude, usource->longitude,
						NULL, NULL, &dist, Params.KilometersPerDegree);
					if (dist <= ixDistance) {
						match = 1;
						break;
					}
				}

			} else {

				bear_distance(source->latitude, source->longitude, usource->latitude, usource->longitude, NULL, NULL,
						&dist, Params.KilometersPerDegree);
				if (dist <= ixDistance) {
					match = 1;
				}
			}

			// The D/U may not be determined here.  If the 73.525 method is used the D/U varies with the desired TV
			// signal strength and so must be interpolated from digitized curve data during study point analysis.  In
			// that case set the flag to trigger that in analyze_points() in study.c.  If the desired is analog TV the
			// 73.525 method is always used.  If the desired is digital a parameter determines the D/U method, that may
			// be 73.525, or it may be from a fixed D/U table.  In the fixed case the D/U can be set normally here.

			if (match) {

				if (undesiredCount == undesiredMaxCount) {
					undesiredMaxCount += 10;
					undesireds = (UNDESIRED *)mem_realloc(undesireds, (undesiredMaxCount * sizeof(UNDESIRED)));
				}

				memset(&(undesireds[undesiredCount]), 0, sizeof(UNDESIRED));
				undesireds[undesiredCount].sourceKey = usource->sourceKey;
				undesireds[undesiredCount].percentTime = (short)rint(Params.TV6FMUndesiredTimeFM * 100.);
				undesireds[undesiredCount].ixDistance = ixDistance;
				if (source->dtv && (TV6_FM_METH_FIXED == Params.TV6FMDtvMethod)) {
					undesireds[undesiredCount].requiredDU = Params.TV6FMDuDtv[ci];
				} else {
					undesireds[undesiredCount].computeTV6FMDU = 1;
				}
				undesiredCount++;
			}
		}
	}

	// In an FM vs. TV channel 6 study for an FM NCE desired, add any TV channel 6 undesireds.  There are two tests
	// here depending on whether the undesired TV is digital or analog.

	if ((STUDY_TYPE_TV6_FM == StudyType) && (RECORD_TYPE_FM == source->recordType) && (BAND_FMED == source->band)) {

		int ci, mask;
		double deltaf, maskdb;

		usource = Sources;
		for (usourceIndex = 0; usourceIndex < SourceCount; usourceIndex++, usource++) {

			if (!usource->inScenario || !usource->isUndesired) {
				continue;
			}
			if (RECORD_TYPE_TV != usource->recordType) {
				continue;
			}
			if (usource->channel != 6) {
				continue;
			}

			// If the undesired is digital, parameters provide a table of culling distances that are equivalent to
			// those defined by interference rules.  Meaning, the distance check is site-to-point so the rule-extra-
			// distance is applied and individual site-to-point distances are checked during study point setup.  The
			// D/U is computed here from a base parameter adjusted by the DTV emission mask and bandwidth factors.

			if (usource->dtv) {

				ci = source->channel - TV6_FM_CHAN_BASE;
				if (ci < 0) {
					ci = 0;
				}
				if (ci >= TV6_FM_CHAN_COUNT) {
					ci = TV6_FM_CHAN_COUNT - 1;
				}

				ixDistance = Params.TV6FMDistanceDtv[ci];

				match = check_distance(source, usource, ixDistance);

				if (match) {

					deltaf = source->frequency - 88.;

					if (deltaf < 0.) {
						maskdb = 0.;
					} else {

						if (SERV_TV == usource->service) {
							mask = LPTV_MASK_FULL_SERVICE;
						} else {
							if (LPTV_MASK_NONE == usource->emissionMaskKey) {
								mask = LPTV_MASK_SIMPLE;
							} else {
								mask = usource->emissionMaskKey;
							}
						}

						// Emission mask formulas from 73.622(h) and 74.794(a)(2).  The mask definitions extend out to
						// what would be FM channel 230 in some cases, however beyond channel 220 even with the simple
						// mask the undesired TV signal for interference would be >100 dBu, so this stops at 220.

						switch (mask) {

							case LPTV_MASK_FULL_SERVICE: {
								if (deltaf <= 0.5) {
									maskdb = 47.;
								} else {
									maskdb = 11.5 * (deltaf + 3.6);
									if (maskdb > 110.) {
										maskdb = 110.;
									}
								}
								break;
							}

							case LPTV_MASK_STRINGENT: {
								if (deltaf <= 0.5) {
									maskdb = 47.;
								} else {
									maskdb = 47. + (11.5 * (deltaf - 0.5));
									if (maskdb > 76.) {
										maskdb = 76.;
									}
								}
								break;
							}

							case LPTV_MASK_SIMPLE:
							default: {
								maskdb = 46. + ((deltaf * deltaf) / 1.44);
								if (maskdb > 71.) {
									maskdb = 71.;
								}
								break;
							}
						}

						// Per 73.622(h) the mask suppression is based on 500 kHz bandwidth, adjust for FM bandwidth:
						//   10 * log10(500 / 200) = 3.98 dB

						maskdb += 3.98;
					}

					reqDU = Params.TV6FMBaseDuDtv - maskdb;

					if (undesiredCount == undesiredMaxCount) {
						undesiredMaxCount += 10;
						undesireds = (UNDESIRED *)mem_realloc(undesireds, (undesiredMaxCount * sizeof(UNDESIRED)));
					}

					memset(&(undesireds[undesiredCount]), 0, sizeof(UNDESIRED));
					undesireds[undesiredCount].sourceKey = usource->sourceKey;
					undesireds[undesiredCount].percentTime = (short)rint(Params.TV6FMUndesiredTimeTV * 100.);
					undesireds[undesiredCount].ixDistance = ixDistance;
					undesireds[undesiredCount].checkIxDistance = 1;
					undesireds[undesiredCount].requiredDU = reqDU;
					undesiredCount++;
				}

			// If the undesired is analog, it is treated like a full-service FM on channel 199 and the FM<->FM
			// interference rules are used to determine if the TV is an undesired.  See rule-test code above.  Note
			// there is no adjustment for percent aural power, that is assumed to be 100%.

			} else {

				ixRule = ixRules;
				for (ixRuleIndex = 0; ixRuleIndex < ixRuleCount; ixRuleIndex++, ixRule++) {

					if (ixRule->serviceTypeKey != SERVTYPE_FM_FULL) {
						continue;
					}
					if (ixRule->countryKey != source->countryKey) {
						continue;
					}
					if (ixRule->channelDelta != (usource->channel - 199)) {
						continue;
					}
					if (ixRule->undesiredServiceTypeKey != usource->serviceTypeKey) {
						continue;
					}

					ixDistance = ixRule->ixDistance;

					if (source->isParent) {

						for (dtsSource = source->dtsSources; dtsSource; dtsSource = dtsSource->next) {
							if ((match = check_distance(dtsSource, usource, ixDistance))) {
								break;
							}
						}

						if (match && Params.TruncateDTS) {
							if (!check_distance(source, usource, ixDistance) &&
									!check_distance(source->dtsRefSource, usource, ixDistance)) {
								match = 0;
							}
						}

					} else {

						match = check_distance(source, usource, ixDistance);
					}

					if (match) {

						reqDU = ixRule->requiredDU;
						adelt = abs(ixRule->channelDelta);
						if ((53 == adelt) || (54 == adelt)) {
							duThrD = 91.;
							duThrU = 91.;
						} else {
							duThrD = 0.;
							duThrU = 0.;
						}

						if (undesiredCount == undesiredMaxCount) {
							undesiredMaxCount += 10;
							undesireds = (UNDESIRED *)mem_realloc(undesireds, (undesiredMaxCount * sizeof(UNDESIRED)));
						}

						memset(&(undesireds[undesiredCount]), 0, sizeof(UNDESIRED));
						undesireds[undesiredCount].sourceKey = usource->sourceKey;
						undesireds[undesiredCount].percentTime = (short)rint(Params.TV6FMUndesiredTimeTV * 100.);
						undesireds[undesiredCount].ixDistance = ixDistance;
						undesireds[undesiredCount].checkIxDistance = 1;
						undesireds[undesiredCount].requiredDU = reqDU;
						undesireds[undesiredCount].duThresholdD = duThrD;
						undesireds[undesiredCount].duThresholdU = duThrU;
						undesiredCount++;
					}
				}
			}
		}
	}

	// Done.

	source->undesireds = undesireds;
	source->undesiredCount = undesiredCount;

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Function used during distance checks in find_undesired(), check if the distance between a desired service area
// boundary and undesired is less than an interference rule distance.  If the desired is a DTS parent this is checking
// the reference point and boundary, which are set as a geography on the parent source.  The caller will step through
// individual desired DTS sources if needed.  If the undesired is DTS this may check each transmitter or just the
// reference point depending on a parameter option.

static int check_distance(SOURCE *source, SOURCE *usource, double ruleDist) {

	GEOPOINTS *pts = render_service_area(source);

	SOURCE *sources = usource;
	usource->next = NULL;
	if (usource->isParent && Params.CheckIndividualDTSDistance) {
		sources = usource->dtsSources;
	}

	SOURCE *asource;
	double bear, dist;
	int in, i;

	for (asource = sources; asource; asource = asource->next) {

		if (source->geography) {
			in = inside_geography(asource->latitude, asource->longitude, source->geography);
		} else {
			bear_distance(source->latitude, source->longitude, asource->latitude, asource->longitude, &bear, NULL,
				&dist, Params.KilometersPerDegree);
			in = (dist <= interp_cont(bear, source->contour));
		}
		if (in) {
			return 1;
		}

		for (i = 0; i < pts->nPts; i++) {
			bear_distance(pts->ptLat[i], pts->ptLon[i], asource->latitude, asource->longitude, NULL, NULL, &dist,
				Params.KilometersPerDegree);
			if (dist <= ruleDist) {
				return 1;
			}
		}
	}

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Function used during checks of wireless undesireds in find_undesired(), check if the wireless source is inside the
// desired source's service area (return -1), outside but within a culling distance (return 1), or outside and beyond
// the culling distance (return 0).  See comments above.

static int check_service_area(SOURCE *source, SOURCE *usource, double cullDist) {

	double bear, dist;
	int i, in;

	if (source->geography) {
		in = inside_geography(usource->latitude, usource->longitude, source->geography);
	} else {
		bear_distance(source->latitude, source->longitude, usource->latitude, usource->longitude, &bear, NULL, &dist,
			Params.KilometersPerDegree);
		in = (dist <= interp_cont(bear, source->contour));
	}
	if (in) {
		if (ContourDebug) {
			fprintf(ContourDebug, "  wireless sourceKey %d included, inside service area\n", usource->sourceKey);
		}
		return -1;
	}

	GEOPOINTS *pts = render_service_area(source);

	double mindist = 99999.;
	for (i = 0; i < pts->nPts; i++) {
		bear_distance(usource->latitude, usource->longitude, pts->ptLat[i], pts->ptLon[i], NULL, NULL, &dist,
			Params.KilometersPerDegree);
		if (ContourDebug) {
			if (dist < mindist) {
				mindist = dist;
			}
		} else {
			if (dist <= cullDist) {
				return 1;
			}
		}
	}
	if (ContourDebug && (mindist <= cullDist)) {
		fprintf(ContourDebug, "  wireless sourceKey %d included, distance to service area %.6f\n", usource->sourceKey,
			mindist);
		return 1;
	}

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Determine the rule extra distance value for a source, based on an ERP vs. distance table set with parameters.  The
// ERP must be normalized first.  ERP values set in the parameters are for U.S. full-service digital, if the source is
// other the lookup ERP is adjusted both for differences in the service contour level and propagation curves, using a
// fixed 8 dB adjustment for F(50,50) to F(50,90).  For a DTS source the DTS service distance limit is used for the
// parent source, then the individual sources are set as usual, including the reference source.  See find_undesired().
// A flag may override the table and just use the maximum signal distance as the rule extra in all cases.  Note these
// actually have no function here, these are only used by the front-end application when searching for records to
// build scenarios.  However the extra distance is checked here against the actual maximum distance to the service
// area boundary (something the front-end app can't do) and if that boundary is larger than the extra distance radius
// a warning is logged.

static void set_rule_extra_distance(SOURCE *source) {

	if (source->isParent) {

		source->ruleExtraDistance = source->dtsMaximumDistance;

		SOURCE *dtsSource;
		for (dtsSource = source->dtsSources; dtsSource; dtsSource = dtsSource->next) {
			set_rule_extra_distance(dtsSource);
		}

		set_rule_extra_distance(source->dtsRefSource);

		return;
	}

	if (Params.UseMaxRuleExtraDistance) {
		source->ruleExtraDistance = Params.MaximumDistance;
		return;
	}

	// The service curve set should never be F(50,10); if it is just use a large fixed distance.

	int curv;
	if (RECORD_TYPE_FM == source->recordType) {
		curv = Params.CurveSetFM[source->countryKey - 1];
	} else {
		if (source->dtv) {
			curv = Params.CurveSetDigital[source->countryKey - 1];
		} else {
			curv = Params.CurveSetAnalog[source->countryKey - 1];
		}
	}
	if (FCC_F10 == curv) {
		source->ruleExtraDistance = 300.;
		return;
	}

	// The contour ERP is used in case this is a replication source.

	double erp = source->contourERP + (Params.ContourUhfDigital[CNTRY_USA - 1] - source->contourLevel);

	int curvref = Params.CurveSetDigital[CNTRY_USA - 1];
	if (FCC_F90 == curvref) {
		if (FCC_F50 == curv) {
			erp += 8.;
		}
	} else {
		if (FCC_F50 == curvref) {
			if (FCC_F90 == curv) {
				erp -= 8.;
			}
		}
	}

	int i;
	for (i = 0; i < 3; i++) {
		if (erp < Params.RuleExtraDistanceERP[i]) {
			break;
		}
	}

	source->ruleExtraDistance = Params.RuleExtraDistance[i];
}


//---------------------------------------------------------------------------------------------------------------------
// Convenience for using interp() on a contour structure.

double interp_cont(double lookup, CONTOUR *contour) {

	return interp(lookup, contour->distance, contour->count);
}


//---------------------------------------------------------------------------------------------------------------------
// Linear interpolation in a table of azimuthal data with regularily-spaced points.

// Arguments:

//   lookup   Azimuth to look up.
//   table    Data table.
//   count    Number of values in table.

// Return is the interpolated value.

double interp(double lookup, double *table, int count) {

	double step = 360. / (double)count;

	int index0 = lookup / step;
	double lookup0 = (double)index0 * step;
	while (index0 < 0) {
		index0 += count;
	}
	int index1 = index0 + 1;
	while (index1 >= count) {
		index1 -= count;
	}

	return table[index0] + (((lookup - lookup0) / step) * (table[index1] - table[index0]));
}


//---------------------------------------------------------------------------------------------------------------------
// Like interp(), but applies a minimum value to the result.  The minimum may optionally be applied
// to the table values before interpolation which changes the slope between points.

// Arguments:

//   lookup   Azimuth to look up.
//   table    Data table.
//   count    Number of values in table.
//   minVal   Minimum value, result will never be less than this.
//   minMode  True to apply minimum to table values before interpolation.

// Return is the interpolated value.

double interp_min(double lookup, double *table, int count, double minVal, int minMode) {

	double step = 360. / (double)count;

	int index0 = lookup / step;
	double lookup0 = (double)index0 * step;
	while (index0 < 0) {
		index0 += count;
	}
	int index1 = index0 + 1;
	while (index1 >= count) {
		index1 -= count;
	}

	double table0 = table[index0], table1 = table[index1];
	if (minMode) {
		if (table0 < minVal) {
			table0 = minVal;
		}
		if (table1 < minVal) {
			table1 = minVal;
		}
	}
	double result = table0 + (((lookup - lookup0) / step) * (table1 - table0));
	if (result < minVal) {
		result = minVal;
	}

	return result;
}
