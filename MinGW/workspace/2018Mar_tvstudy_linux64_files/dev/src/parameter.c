//
//  parameter.c
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.


// Retrieve study parameters and set up values in globals.


#include "tvstudy.h"
#include <termios.h>

//---------------------------------------------------------------------------------------------------------------------
// Loaded parameters for an open study and scenario.

PARAMS Params;


//---------------------------------------------------------------------------------------------------------------------

#define TYPE_INT  1
#define TYPE_DBL  2

static PARAMS DefaultParams;
static PARAMS TemplateParams;

static int load_parameters(PARAMS *params, int templateKey, int studyKey, int scenarioKey, PARAMS *defaultParams);
static int parse_parameter(int paramKey, int valueCount, char **paramValues, int type, void *destination);
static int parse_table_parameter(int paramKey, int valueCount, int rowCount, int colCount, char **paramValues,
	int type, void *destination);


//---------------------------------------------------------------------------------------------------------------------
// Load all parameters for a study and set up global structures.  Only need to do this if study key has changed.

// Arguments:

//   (none)

// Return value is <0 for a serious error, >0 for a minor error, 0 for no error.

int load_study_parameters() {

	static int loadDefault = 1;
	static int initForStudyKey = 0;

	if (!StudyKey) {
		log_error("load_study_parameters() called with no study open");
		return -1;
	}

	if (initForStudyKey == StudyKey) {
		return 0;
	}
	initForStudyKey = 0;

	// On the first call, load parameters from the default template which is always template key 1.  No defaults are
	// applied here, any missing parameters return an error.  The default template should always be complete.  Also
	// that template has the same content regardless database server; the content is fixed for a database version.

	int err;

	if (loadDefault) {
		err = load_parameters(&DefaultParams, 1, 0, 0, NULL);
		if (err) {
			return err;
		}
		loadDefault = 0;
	}

	// Load study template parameters, applying defaults from the default template.  If the study template is the
	// default template just copy.

	if (TemplateKey > 1) {

		err = load_parameters(&TemplateParams, TemplateKey, 0, 0, &DefaultParams);
		if (err) {
			return err;
		}

	} else {

		memcpy(&TemplateParams, &DefaultParams, sizeof(PARAMS));
	}

	// Load the study parameters, applying defaults from the template.

	err = load_parameters(&Params, 0, StudyKey, 0, &TemplateParams);
	if (err) {
		return err;
	}

	// The local grid type and population coordinate rounding options are legacy TV options and are not allowed on
	// FM or OET-74 studies, in those cases force global grid and disable rounding regardless of parameters.

	if ((STUDY_TYPE_TV != StudyType) && (STUDY_TYPE_TV_IX != StudyType)) {
		int i;
		Params.GridType = GRID_TYPE_GLOBAL;
		for (i = 0; i < MAX_COUNTRY; i++) {
			Params.RoundPopCoords[i] = 0;
		}
	}

	// Do a few preliminary calculations on the parameters and derived globals.

	CellLatSize = (int)(((Params.CellSize / Params.KilometersPerDegree) * 3600.) + 0.5);
	if (CellLatSize < 1) {
		CellLatSize = 1;
	}

	Params.RuleExtraDistanceERP[0] = 10. * log10(Params.RuleExtraDistanceERP[0]);
	Params.RuleExtraDistanceERP[1] = 10. * log10(Params.RuleExtraDistanceERP[1]);
	Params.RuleExtraDistanceERP[2] = 10. * log10(Params.RuleExtraDistanceERP[2]);

	// Success.

	initForStudyKey = StudyKey;

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Load scenario parameters.  Note this is called early before the scenario is actually loaded because the parameters
// may be needed during the load process.  See load_scenario() in source.c.

// Arguments:

//   scenarioKey  The scenario being loaded.

// Return value is <0 for a serious error, >0 for a minor error, 0 for no error.

int load_scenario_parameters(int scenarioKey) {

	if (!StudyKey) {
		log_error("load_scenario_parameters() called with no study open");
		return -1;
	}

	int err = load_parameters(&Params, 0, StudyKey, scenarioKey, &TemplateParams);
	if (err) {
		return err;
	}

	Params.WirelessLowerBandEdge = Params.WirelessFrequency - (Params.WirelessBandwidth / 2.);
	Params.WirelessUpperBandEdge = Params.WirelessFrequency + (Params.WirelessBandwidth / 2.);

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Load parameters for a template, a study, or a scenario, optionally applying defaults for missing values from
// another parameter structure.

// Arguments:

//   params         Structure to update.
//   templateKey    If > 0 load parameters for template, ignore study and scenario keys.
//   studyKey       If > 0 load study or scenario.
//   scenarioKey    If == 0 load study, else load scenario.
//   defaultParams  Set values for missing parameters from this structure during the load.  May be NULL, in which
//                   case missing parameters will be considered an error.

// Return value is <0 for a serious error, >0 for a minor error, 0 for no error.

static int load_parameters(PARAMS *params, int templateKey, int studyKey, int scenarioKey, PARAMS *defaultParams) {

	char query[MAX_QUERY];
	MYSQL_RES *myResult;
	MYSQL_ROW fields;
	unsigned long *lengths;

	// Query for parameter values.  The only variation here is which table is queried, the template parameter data
	// in the root database, the study parameter data table in the study database, or the scenario parameter table
	// in the study database.  All three have basically the same structure.  When loading a template or a scenario
	// all parameters, both study and scenario, will be updated.  When loading a scenario, only scenario parameters
	// will be updated.  The template parameter data table will contain values for all parameters including scenario.
	// The study parameter data table generally will not contain values for scenario parameters, but updating those
	// anyway means defaults from the template will initially be set so any code that might look at those before a
	// scenario is actually loaded will see reasonable values.

	int loadStudy = 1;

	if (templateKey > 0) {
		snprintf(query, MAX_QUERY,
			"SELECT parameter_key, value_index, value FROM %s.template_parameter_data WHERE template_key=%d;",
			DbName, templateKey);
	} else {
		if (studyKey > 0) {
			if (0 == scenarioKey) {
				snprintf(query, MAX_QUERY,
					"SELECT parameter_key, value_index, value FROM %s_%d.parameter_data;",
					DbName, studyKey);
			} else {
				snprintf(query, MAX_QUERY,
			"SELECT parameter_key, value_index, value FROM %s_%d.scenario_parameter_data WHERE scenario_key = %d;",
					DbName, studyKey, scenarioKey);
				loadStudy = 0;
			}
		} else {
			log_error("Bad arguments to load_parameters()");
			return -1;
		}
	}

	if (mysql_query(MyConnection, query)) {
		log_db_error("Parameter query failed (1)");
		return -1;
	}

	myResult = mysql_store_result(MyConnection);
	if (!myResult) {
		log_db_error("Parameter query failed (2)");
		return -1;
	}

	// The values are loaded first into a string array indexed by the parameter key for easy lookup; NULLs indicate
	// missing values.  Actual values should never be null or empty so both conditions cause the value to be ignored,
	// in which case defaults are applied, or if no defaults structure was passed, an error occurs.  Parameter keys
	// out of range are also ignored.  When a parameter is multi-value it must be all-or-nothing, meaning it must
	// have values for all indices else defaults are applied to all.

	char **paramValues = (char **)mem_zalloc(PARAM_KEY_SIZE * MAX_VALUE_COUNT * sizeof(char *));

	int resultCount = (int)mysql_num_rows(myResult), resultIndex, paramKey, valueIndex, paramIndex, err = 0;

	for (resultIndex = 0; resultIndex < resultCount; resultIndex++) {

		fields = mysql_fetch_row(myResult);
		lengths = mysql_fetch_lengths(myResult);
		if ((NULL == fields) || (NULL == lengths)) {
			mysql_free_result(myResult);
			log_db_error("Parameter query failed (3)");
			err = -1;
			break;
		}

		paramKey = atoi(fields[0]);
		if ((paramKey < 1) || (paramKey >= PARAM_KEY_SIZE)) {
			continue;
		}

		valueIndex = atoi(fields[1]);
		if ((valueIndex < 0) || (valueIndex >= MAX_VALUE_COUNT)) {
			continue;
		}

		if (0 == lengths[2]) {
			continue;
		}

		paramIndex = (paramKey * MAX_VALUE_COUNT) + valueIndex;
		paramValues[paramIndex] = (char *)mem_alloc(lengths[2] + 1);
		lcpystr(paramValues[paramIndex], fields[2], (lengths[2] + 1));
	}

	mysql_free_result(myResult);

	if (err) {
		for (paramIndex = 0; paramIndex < (PARAM_KEY_SIZE * MAX_VALUE_COUNT); paramIndex++) {
			if (paramValues[paramIndex]) {
				mem_free(paramValues[paramIndex]);
				paramValues[paramIndex] = NULL;
			}
		}
		mem_free(paramValues);
		return err;
	}

	// Parse study parameters, if needed.  Note this does not parse all possible parameter keys.  Some parameters are
	// only used in the front-end app and so are not in the parameters structure here.  Although parameter keys are
	// defined for all in the header file, those not needed here are skipped.

	int clutterIndex;

	while (loadStudy) {

		err = 1;

		if (parse_parameter(PARAM_GRID_TYPE, 1, paramValues, TYPE_INT, &(params->GridType))) {
			if (!defaultParams) break;
			params->GridType = defaultParams->GridType;
		}
		if (parse_parameter(PARAM_CELL_SIZE, 1, paramValues, TYPE_DBL, &(params->CellSize))) {
			if (!defaultParams) break;
			params->CellSize = defaultParams->CellSize;
		}
		if (parse_parameter(PARAM_POINT_METHOD, 1, paramValues, TYPE_INT, &(params->StudyPointMethod))) {
			if (!defaultParams) break;
			params->StudyPointMethod = defaultParams->StudyPointMethod;
		}
		if (parse_parameter(PARAM_POINT_NEAREST, 1, paramValues, TYPE_INT, &(params->StudyPointToNearestCP))) {
			if (!defaultParams) break;
			params->StudyPointToNearestCP = defaultParams->StudyPointToNearestCP;
		}

		if (parse_parameter(PARAM_ERROR_HNDLNG, MAX_COUNTRY, paramValues, TYPE_INT, params->ErrorHandling)) {
			if (!defaultParams) break;
			memcpy(params->ErrorHandling, defaultParams->ErrorHandling, (MAX_COUNTRY * sizeof(int)));
		}
		if (parse_parameter(PARAM_DEPANGLE_METH, MAX_COUNTRY, paramValues, TYPE_INT, params->DepressionAngleMethod)) {
			if (!defaultParams) break;
			memcpy(params->DepressionAngleMethod, defaultParams->DepressionAngleMethod, (MAX_COUNTRY * sizeof(int)));
		}
		if (parse_parameter(PARAM_VPAT_MTILT, MAX_COUNTRY, paramValues, TYPE_INT, params->VpatMechTilt)) {
			if (!defaultParams) break;
			memcpy(params->VpatMechTilt, defaultParams->VpatMechTilt, (MAX_COUNTRY * sizeof(int)));
		}
		if (parse_parameter(PARAM_VPAT_GEN_MIRROR, MAX_COUNTRY, paramValues, TYPE_INT, params->GenericVpatMirroring)) {
			if (!defaultParams) break;
			memcpy(params->GenericVpatMirroring, defaultParams->GenericVpatMirroring, (MAX_COUNTRY * sizeof(int)));
		}
		if (parse_parameter(PARAM_VPAT_GEN_TILT, MAX_COUNTRY, paramValues, TYPE_INT, params->GenericVpatTilt)) {
			if (!defaultParams) break;
			memcpy(params->GenericVpatTilt, defaultParams->GenericVpatTilt, (MAX_COUNTRY * sizeof(int)));
		}
		if (parse_parameter(PARAM_VPAT_GEN_DOUBLE, MAX_COUNTRY, paramValues, TYPE_INT, params->GenericVpatDoubling)) {
			if (!defaultParams) break;
			memcpy(params->GenericVpatDoubling, defaultParams->GenericVpatDoubling, (MAX_COUNTRY * sizeof(int)));
		}
		if (parse_parameter(PARAM_CONTOUR_VPAT, MAX_COUNTRY, paramValues, TYPE_INT, params->ContourVpatUse)) {
			if (!defaultParams) break;
			memcpy(params->ContourVpatUse, defaultParams->ContourVpatUse, (MAX_COUNTRY * sizeof(int)));
		}
		if (parse_parameter(PARAM_CONT_REAL_VPAT, MAX_COUNTRY, paramValues, TYPE_INT, params->ContoursUseRealVpat)) {
			if (!defaultParams) break;
			memcpy(params->ContoursUseRealVpat, defaultParams->ContoursUseRealVpat, (MAX_COUNTRY * sizeof(int)));
		}
		if (parse_parameter(PARAM_CONT_DERIVE_HPAT, MAX_COUNTRY, paramValues, TYPE_INT, params->ContourDeriveHpat)) {
			if (!defaultParams) break;
			memcpy(params->ContourDeriveHpat, defaultParams->ContourDeriveHpat, (MAX_COUNTRY * sizeof(int)));
		}
		if (parse_parameter(PARAM_ABS_VAL_TILT, MAX_COUNTRY, paramValues, TYPE_INT, params->AbsoluteValueTilts)) {
			if (!defaultParams) break;
			memcpy(params->AbsoluteValueTilts, defaultParams->AbsoluteValueTilts, (MAX_COUNTRY * sizeof(int)));
		}

		if (parse_parameter(PARAM_AVG_TERR_DB, 1, paramValues, TYPE_INT, &(params->TerrAvgDb))) {
			if (!defaultParams) break;
			params->TerrAvgDb = defaultParams->TerrAvgDb;
		}
		if (parse_parameter(PARAM_AVG_TERR_RES, MAX_COUNTRY, paramValues, TYPE_DBL, params->TerrAvgPpk)) {
			if (!defaultParams) break;
			memcpy(params->TerrAvgPpk, defaultParams->TerrAvgPpk, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_PATH_TERR_DB, 1, paramValues, TYPE_INT, &(params->TerrPathDb))) {
			if (!defaultParams) break;
			params->TerrPathDb = defaultParams->TerrPathDb;
		}
		if (parse_parameter(PARAM_PATH_TERR_RES, MAX_COUNTRY, paramValues, TYPE_DBL, params->TerrPathPpk)) {
			if (!defaultParams) break;
			memcpy(params->TerrPathPpk, defaultParams->TerrPathPpk, (MAX_COUNTRY * sizeof(double)));
		}

		// The separate parameters for Census database selection by country existed before multi-value parmeters were
		// possible.  It's easy to convert a single-value parameter to multi-value, the editor will automatically fill
		// a single value found in old data (i.e. XML) to all multi-value slots.  But going the other way is difficult,
		// it would involve hard-coded mappings from old parameter keys that no longer exist to index slots in a new
		// parameter.  Since the only benefit would be a more compact and consistent presentation of the parameters in
		// the editing UI, it's just not worth all that.

		if (parse_parameter(PARAM_US_CENSUS, 1, paramValues, TYPE_INT, (params->CenYear + (CNTRY_USA - 1)))) {
			if (!defaultParams) break;
			params->CenYear[CNTRY_USA - 1] = defaultParams->CenYear[CNTRY_USA - 1];
		}
		if (parse_parameter(PARAM_CA_CENSUS, 1, paramValues, TYPE_INT, (params->CenYear + (CNTRY_CAN - 1)))) {
			if (!defaultParams) break;
			params->CenYear[CNTRY_CAN - 1] = defaultParams->CenYear[CNTRY_CAN - 1];
		}
		if (parse_parameter(PARAM_MX_CENSUS, 1, paramValues, TYPE_INT, (params->CenYear + (CNTRY_MEX - 1)))) {
			if (!defaultParams) break;
			params->CenYear[CNTRY_MEX - 1] = defaultParams->CenYear[CNTRY_MEX - 1];
		}
		if (parse_parameter(PARAM_RND_POP_CRDS, MAX_COUNTRY, paramValues, TYPE_INT, params->RoundPopCoords)) {
			if (!defaultParams) break;
			memcpy(params->RoundPopCoords, defaultParams->RoundPopCoords, (MAX_COUNTRY * sizeof(int)));
		}

		if (parse_parameter(PARAM_REPL_METH, MAX_COUNTRY, paramValues, TYPE_INT, params->ReplicationMethod)) {
			if (!defaultParams) break;
			memcpy(params->ReplicationMethod, defaultParams->ReplicationMethod, (MAX_COUNTRY * sizeof(int)));
		}
		if (parse_parameter(PARAM_MIN_ERP_VLO, MAX_COUNTRY, paramValues, TYPE_DBL, params->MinimumVloERP)) {
			if (!defaultParams) break;
			memcpy(params->MinimumVloERP, defaultParams->MinimumVloERP, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_MIN_ERP_VHI, MAX_COUNTRY, paramValues, TYPE_DBL, params->MinimumVhiERP)) {
			if (!defaultParams) break;
			memcpy(params->MinimumVhiERP, defaultParams->MinimumVhiERP, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_MIN_ERP_UHF, MAX_COUNTRY, paramValues, TYPE_DBL, params->MinimumUhfERP)) {
			if (!defaultParams) break;
			memcpy(params->MinimumUhfERP, defaultParams->MinimumUhfERP, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_MAX_ERP_VLO1, MAX_COUNTRY, paramValues, TYPE_DBL, params->MaximumVloZ1ERP)) {
			if (!defaultParams) break;
			memcpy(params->MaximumVloZ1ERP, defaultParams->MaximumVloZ1ERP, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_MAX_ERP_VLO23, MAX_COUNTRY, paramValues, TYPE_DBL, params->MaximumVloZ23ERP)) {
			if (!defaultParams) break;
			memcpy(params->MaximumVloZ23ERP, defaultParams->MaximumVloZ23ERP, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_MAX_ERP_VHI1, MAX_COUNTRY, paramValues, TYPE_DBL, params->MaximumVhiZ1ERP)) {
			if (!defaultParams) break;
			memcpy(params->MaximumVhiZ1ERP, defaultParams->MaximumVhiZ1ERP, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_MAX_ERP_VHI23, MAX_COUNTRY, paramValues, TYPE_DBL, params->MaximumVhiZ23ERP)) {
			if (!defaultParams) break;
			memcpy(params->MaximumVhiZ23ERP, defaultParams->MaximumVhiZ23ERP, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_MAX_ERP_UHF, MAX_COUNTRY, paramValues, TYPE_DBL, params->MaximumUhfERP)) {
			if (!defaultParams) break;
			memcpy(params->MaximumUhfERP, defaultParams->MaximumUhfERP, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_MIN_ERP_VHF_LP, MAX_COUNTRY, paramValues, TYPE_DBL, params->MinimumVhfErpLPTV)) {
			if (!defaultParams) break;
			memcpy(params->MinimumVhfErpLPTV, defaultParams->MinimumVhfErpLPTV, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_MIN_ERP_UHF_LP, MAX_COUNTRY, paramValues, TYPE_DBL, params->MinimumUhfErpLPTV)) {
			if (!defaultParams) break;
			memcpy(params->MinimumUhfErpLPTV, defaultParams->MinimumUhfErpLPTV, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_MAX_ERP_VHF_LP, MAX_COUNTRY, paramValues, TYPE_DBL, params->MaximumVhfErpLPTV)) {
			if (!defaultParams) break;
			memcpy(params->MaximumVhfErpLPTV, defaultParams->MaximumVhfErpLPTV, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_MAX_ERP_UHF_LP, MAX_COUNTRY, paramValues, TYPE_DBL, params->MaximumUhfErpLPTV)) {
			if (!defaultParams) break;
			memcpy(params->MaximumUhfErpLPTV, defaultParams->MaximumUhfErpLPTV, (MAX_COUNTRY * sizeof(double)));
		}

		if (parse_parameter(PARAM_SERVAREA_MODE, MAX_COUNTRY, paramValues, TYPE_INT, params->ServiceAreaMode)) {
			if (!defaultParams) break;
			memcpy(params->ServiceAreaMode, defaultParams->ServiceAreaMode, (MAX_COUNTRY * sizeof(int)));
		}
		if (parse_parameter(PARAM_SERVAREA_ARG, MAX_COUNTRY, paramValues, TYPE_DBL, params->ServiceAreaArg)) {
			if (!defaultParams) break;
			memcpy(params->ServiceAreaArg, defaultParams->ServiceAreaArg, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_MAX_DIST, 1, paramValues, TYPE_DBL, &(params->MaximumDistance))) {
			if (!defaultParams) break;
			params->MaximumDistance = defaultParams->MaximumDistance;
		}

		if (parse_parameter(PARAM_CL_VLO_DTV, MAX_COUNTRY, paramValues, TYPE_DBL, params->ContourVloDigital)) {
			if (!defaultParams) break;
			memcpy(params->ContourVloDigital, defaultParams->ContourVloDigital, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_CL_VHI_DTV, MAX_COUNTRY, paramValues, TYPE_DBL, params->ContourVhiDigital)) {
			if (!defaultParams) break;
			memcpy(params->ContourVhiDigital, defaultParams->ContourVhiDigital, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_CL_UHF_DTV, MAX_COUNTRY, paramValues, TYPE_DBL, params->ContourUhfDigital)) {
			if (!defaultParams) break;
			memcpy(params->ContourUhfDigital, defaultParams->ContourUhfDigital, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_CL_VLO_DTV_LP, MAX_COUNTRY, paramValues, TYPE_DBL, params->ContourVloDigitalLPTV)) {
			if (!defaultParams) break;
			memcpy(params->ContourVloDigitalLPTV, defaultParams->ContourVloDigitalLPTV, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_CL_VHI_DTV_LP, MAX_COUNTRY, paramValues, TYPE_DBL, params->ContourVhiDigitalLPTV)) {
			if (!defaultParams) break;
			memcpy(params->ContourVhiDigitalLPTV, defaultParams->ContourVhiDigitalLPTV, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_CL_UHF_DTV_LP, MAX_COUNTRY, paramValues, TYPE_DBL, params->ContourUhfDigitalLPTV)) {
			if (!defaultParams) break;
			memcpy(params->ContourUhfDigitalLPTV, defaultParams->ContourUhfDigitalLPTV, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_CL_VLO_NTSC, MAX_COUNTRY, paramValues, TYPE_DBL, params->ContourVloAnalog)) {
			if (!defaultParams) break;
			memcpy(params->ContourVloAnalog, defaultParams->ContourVloAnalog, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_CL_VHI_NTSC, MAX_COUNTRY, paramValues, TYPE_DBL, params->ContourVhiAnalog)) {
			if (!defaultParams) break;
			memcpy(params->ContourVhiAnalog, defaultParams->ContourVhiAnalog, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_CL_UHF_NTSC, MAX_COUNTRY, paramValues, TYPE_DBL, params->ContourUhfAnalog)) {
			if (!defaultParams) break;
			memcpy(params->ContourUhfAnalog, defaultParams->ContourUhfAnalog, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_CL_VLO_NTSC_LP, MAX_COUNTRY, paramValues, TYPE_DBL, params->ContourVloAnalogLPTV)) {
			if (!defaultParams) break;
			memcpy(params->ContourVloAnalogLPTV, defaultParams->ContourVloAnalogLPTV, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_CL_VHI_NTSC_LP, MAX_COUNTRY, paramValues, TYPE_DBL, params->ContourVhiAnalogLPTV)) {
			if (!defaultParams) break;
			memcpy(params->ContourVhiAnalogLPTV, defaultParams->ContourVhiAnalogLPTV, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_CL_UHF_NTSC_LP, MAX_COUNTRY, paramValues, TYPE_DBL, params->ContourUhfAnalogLPTV)) {
			if (!defaultParams) break;
			memcpy(params->ContourUhfAnalogLPTV, defaultParams->ContourUhfAnalogLPTV, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_USE_DIPOLE_CL, MAX_COUNTRY, paramValues, TYPE_INT, params->UseDipoleCont)) {
			if (!defaultParams) break;
			memcpy(params->UseDipoleCont, defaultParams->UseDipoleCont, (MAX_COUNTRY * sizeof(int)));
		}
		if (parse_parameter(PARAM_DIPOLE_CENTER_CL, MAX_COUNTRY, paramValues, TYPE_DBL, params->DipoleCenterFreqCont)) {
			if (!defaultParams) break;
			memcpy(params->DipoleCenterFreqCont, defaultParams->DipoleCenterFreqCont, (MAX_COUNTRY * sizeof(double)));
		}

		if (parse_parameter(PARAM_CURV_DTV, MAX_COUNTRY, paramValues, TYPE_INT, params->CurveSetDigital)) {
			if (!defaultParams) break;
			memcpy(params->CurveSetDigital, defaultParams->CurveSetDigital, (MAX_COUNTRY * sizeof(int)));
		}
		if (parse_parameter(PARAM_CURV_NTSC, MAX_COUNTRY, paramValues, TYPE_INT, params->CurveSetAnalog)) {
			if (!defaultParams) break;
			memcpy(params->CurveSetAnalog, defaultParams->CurveSetAnalog, (MAX_COUNTRY * sizeof(int)));
		}

		if (parse_parameter(PARAM_CL_FM, MAX_COUNTRY, paramValues, TYPE_DBL, params->ContourFM)) {
			if (!defaultParams) break;
			memcpy(params->ContourFM, defaultParams->ContourFM, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_CL_FM_B, MAX_COUNTRY, paramValues, TYPE_DBL, params->ContourFMB)) {
			if (!defaultParams) break;
			memcpy(params->ContourFMB, defaultParams->ContourFMB, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_CL_FM_B1, MAX_COUNTRY, paramValues, TYPE_DBL, params->ContourFMB1)) {
			if (!defaultParams) break;
			memcpy(params->ContourFMB1, defaultParams->ContourFMB1, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_CL_FM_ED, MAX_COUNTRY, paramValues, TYPE_DBL, params->ContourFMED)) {
			if (!defaultParams) break;
			memcpy(params->ContourFMED, defaultParams->ContourFMED, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_CL_FM_LP, MAX_COUNTRY, paramValues, TYPE_DBL, params->ContourFMLP)) {
			if (!defaultParams) break;
			memcpy(params->ContourFMLP, defaultParams->ContourFMLP, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_CL_FM_TX, MAX_COUNTRY, paramValues, TYPE_DBL, params->ContourFMTX)) {
			if (!defaultParams) break;
			memcpy(params->ContourFMTX, defaultParams->ContourFMTX, (MAX_COUNTRY * sizeof(double)));
		}

		if (parse_parameter(PARAM_CURV_FM, MAX_COUNTRY, paramValues, TYPE_INT, params->CurveSetFM)) {
			if (!defaultParams) break;
			memcpy(params->CurveSetFM, defaultParams->CurveSetFM, (MAX_COUNTRY * sizeof(int)));
		}

		if (parse_parameter(PARAM_OFF_CURV_METH, 1, paramValues, TYPE_INT, &(params->OffCurveLookupMethod))) {
			if (!defaultParams) break;
			params->OffCurveLookupMethod = defaultParams->OffCurveLookupMethod;
		}

		if (parse_parameter(PARAM_DTS_DIST_CHECK, 1, paramValues, TYPE_INT, &(params->CheckIndividualDTSDistance))) {
			if (!defaultParams) break;
			params->CheckIndividualDTSDistance = defaultParams->CheckIndividualDTSDistance;
		}
		if (parse_parameter(PARAM_TRUNC_DTS, 1, paramValues, TYPE_INT, &(params->TruncateDTS))) {
			if (!defaultParams) break;
			params->TruncateDTS = defaultParams->TruncateDTS;
		}
		if (parse_parameter(PARAM_DTS_DIST_VLO1, 1, paramValues, TYPE_DBL, &(params->DTSMaxDistVloZ1))) {
			if (!defaultParams) break;
			params->DTSMaxDistVloZ1 = defaultParams->DTSMaxDistVloZ1;
		}
		if (parse_parameter(PARAM_DTS_DIST_VLO23, 1, paramValues, TYPE_DBL, &(params->DTSMaxDistVloZ23))) {
			if (!defaultParams) break;
			params->DTSMaxDistVloZ23 = defaultParams->DTSMaxDistVloZ23;
		}
		if (parse_parameter(PARAM_DTS_DIST_VHI1, 1, paramValues, TYPE_DBL, &(params->DTSMaxDistVhiZ1))) {
			if (!defaultParams) break;
			params->DTSMaxDistVhiZ1 = defaultParams->DTSMaxDistVhiZ1;
		}
		if (parse_parameter(PARAM_DTS_DIST_VHI23, 1, paramValues, TYPE_DBL, &(params->DTSMaxDistVhiZ23))) {
			if (!defaultParams) break;
			params->DTSMaxDistVhiZ23 = defaultParams->DTSMaxDistVhiZ23;
		}
		if (parse_parameter(PARAM_DTS_DIST_UHF, 1, paramValues, TYPE_DBL, &(params->DTSMaxDistUHF))) {
			if (!defaultParams) break;
			params->DTSMaxDistUHF = defaultParams->DTSMaxDistUHF;
		}

		if (parse_parameter(PARAM_LRCON_TERR_DB, 1, paramValues, TYPE_INT, &(params->LRConTerrDb))) {
			if (!defaultParams) break;
			params->LRConTerrDb = defaultParams->LRConTerrDb;
		}
		if (parse_parameter(PARAM_LRCON_TERR_RES, 1, paramValues, TYPE_DBL, &(params->LRConTerrPpk))) {
			if (!defaultParams) break;
			params->LRConTerrPpk = defaultParams->LRConTerrPpk;
		}
		if (parse_parameter(PARAM_LRCON_DIST_STEP, 1, paramValues, TYPE_DBL, &(params->LRConDistanceStep))) {
			if (!defaultParams) break;
			params->LRConDistanceStep = defaultParams->LRConDistanceStep;
		}
		if (parse_parameter(PARAM_LRCON_LOC_DTV, 1, paramValues, TYPE_DBL, &(params->LRConDigitalLocation))) {
			if (!defaultParams) break;
			params->LRConDigitalLocation = defaultParams->LRConDigitalLocation;
		}
		if (parse_parameter(PARAM_LRCON_TME_DTV, 1, paramValues, TYPE_DBL, &(params->LRConDigitalTime))) {
			if (!defaultParams) break;
			params->LRConDigitalTime = defaultParams->LRConDigitalTime;
		}
		if (parse_parameter(PARAM_LRCON_CNF_DTV, 1, paramValues, TYPE_DBL, &(params->LRConDigitalConfidence))) {
			if (!defaultParams) break;
			params->LRConDigitalConfidence = defaultParams->LRConDigitalConfidence;
		}
		if (parse_parameter(PARAM_LRCON_LOC_NTSC, 1, paramValues, TYPE_DBL, &(params->LRConAnalogLocation))) {
			if (!defaultParams) break;
			params->LRConAnalogLocation = defaultParams->LRConAnalogLocation;
		}
		if (parse_parameter(PARAM_LRCON_TME_NTSC, 1, paramValues, TYPE_DBL, &(params->LRConAnalogTime))) {
			if (!defaultParams) break;
			params->LRConAnalogTime = defaultParams->LRConAnalogTime;
		}
		if (parse_parameter(PARAM_LRCON_CNF_NTSC, 1, paramValues, TYPE_DBL, &(params->LRConAnalogConfidence))) {
			if (!defaultParams) break;
			params->LRConAnalogConfidence = defaultParams->LRConAnalogConfidence;
		}
		if (parse_parameter(PARAM_LRCON_RECV_HGT, 1, paramValues, TYPE_DBL, &(params->LRConReceiveHeight))) {
			if (!defaultParams) break;
			params->LRConReceiveHeight = defaultParams->LRConReceiveHeight;
		}
		if (parse_parameter(PARAM_LRCON_SIG_POL, 1, paramValues, TYPE_INT, &(params->LRConSignalPolarization))) {
			if (!defaultParams) break;
			params->LRConSignalPolarization = defaultParams->LRConSignalPolarization;
		}
		if (parse_parameter(PARAM_LRCON_ATM_REFRAC, 1, paramValues, TYPE_DBL,
				&(params->LRConAtmosphericRefractivity))) {
			if (!defaultParams) break;
			params->LRConAtmosphericRefractivity = defaultParams->LRConAtmosphericRefractivity;
		}
		if (parse_parameter(PARAM_LRCON_GND_PERMIT, 1, paramValues, TYPE_DBL, &(params->LRConGroundPermittivity))) {
			if (!defaultParams) break;
			params->LRConGroundPermittivity = defaultParams->LRConGroundPermittivity;
		}
		if (parse_parameter(PARAM_LRCON_GND_CONDUC, 1, paramValues, TYPE_DBL, &(params->LRConGroundConductivity))) {
			if (!defaultParams) break;
			params->LRConGroundConductivity = defaultParams->LRConGroundConductivity;
		}
		if (parse_parameter(PARAM_LRCON_SERV_MODE, 1, paramValues, TYPE_INT, &(params->LRConServiceMode))) {
			if (!defaultParams) break;
			params->LRConServiceMode = defaultParams->LRConServiceMode;
		}
		if (parse_parameter(PARAM_LRCON_CLIM_TYPE, 1, paramValues, TYPE_INT, &(params->LRConClimateType))) {
			if (!defaultParams) break;
			params->LRConClimateType = defaultParams->LRConClimateType;
		}

		if (parse_parameter(PARAM_CHECK_SELF_IX, 1, paramValues, TYPE_INT, &(params->CheckSelfInterference))) {
			if (!defaultParams) break;
			params->CheckSelfInterference = defaultParams->CheckSelfInterference;
		}
		if (parse_parameter(PARAM_KM_PER_MS, 1, paramValues, TYPE_DBL, &(params->KilometersPerMicrosecond))) {
			if (!defaultParams) break;
			params->KilometersPerMicrosecond = defaultParams->KilometersPerMicrosecond;
		}
		if (parse_parameter(PARAM_MAX_LEAD_TIME, 1, paramValues, TYPE_DBL, &(params->PreArrivalTimeLimit))) {
			if (!defaultParams) break;
			params->PreArrivalTimeLimit = defaultParams->PreArrivalTimeLimit;
		}
		if (parse_parameter(PARAM_MAX_LAG_TIME, 1, paramValues, TYPE_DBL, &(params->PostArrivalTimeLimit))) {
			if (!defaultParams) break;
			params->PostArrivalTimeLimit = defaultParams->PostArrivalTimeLimit;
		}
		if (parse_parameter(PARAM_SELF_IX_DU, 1, paramValues, TYPE_DBL, &(params->SelfIxRequiredDU))) {
			if (!defaultParams) break;
			params->SelfIxRequiredDU = defaultParams->SelfIxRequiredDU;
		}
		if (parse_parameter(PARAM_SELF_IX_UTIME, 1, paramValues, TYPE_DBL, &(params->SelfIxUndesiredTime))) {
			if (!defaultParams) break;
			params->SelfIxUndesiredTime = defaultParams->SelfIxUndesiredTime;
		}

		if (parse_parameter(PARAM_CAP_DU_RAMP, 1, paramValues, TYPE_INT, &(params->CapDURamp))) {
			if (!defaultParams) break;
			params->CapDURamp = defaultParams->CapDURamp;
		}
		if (parse_parameter(PARAM_DU_RAMP_CAP, 1, paramValues, TYPE_DBL, &(params->DURampCap))) {
			if (!defaultParams) break;
			params->DURampCap = defaultParams->DURampCap;
		}

		if (parse_parameter(PARAM_FM_ADJ_IBOC, 1, paramValues, TYPE_INT, &(params->AdjustFMForIBOC))) {
			if (!defaultParams) break;
			params->AdjustFMForIBOC = defaultParams->AdjustFMForIBOC;
		}

		if (parse_table_parameter(PARAM_TV6_FM_DIST, 1, 1, TV6_FM_CHAN_COUNT, paramValues, TYPE_DBL,
				params->TV6FMDistance)) {
			if (!defaultParams) break;
			memcpy(params->TV6FMDistance, defaultParams->TV6FMDistance, (TV6_FM_CHAN_COUNT * sizeof(double)));
		}
		if (parse_table_parameter(PARAM_TV6_FM_CURVES, 1, TV6_FM_CURVE_COUNT, TV6_FM_CHAN_COUNT, paramValues,
				TYPE_DBL, params->TV6FMCurves)) {
			if (!defaultParams) break;
			memcpy(params->TV6FMCurves, defaultParams->TV6FMCurves, (TV6_FM_CURVE_COUNT * TV6_FM_CHAN_COUNT *
				sizeof(double)));
		}
		if (parse_parameter(PARAM_TV6_FM_DTV_METH, 1, paramValues, TYPE_INT, &(params->TV6FMDtvMethod))) {
			if (!defaultParams) break;
			params->TV6FMDtvMethod = defaultParams->TV6FMDtvMethod;
		}
		if (parse_table_parameter(PARAM_TV6_FM_DU_DTV, 1, 1, TV6_FM_CHAN_COUNT, paramValues, TYPE_DBL,
				params->TV6FMDuDtv)) {
			if (!defaultParams) break;
			memcpy(params->TV6FMDuDtv, defaultParams->TV6FMDuDtv, (TV6_FM_CHAN_COUNT * sizeof(double)));
		}
		if (parse_parameter(PARAM_TV6_FM_TME_UND_FM, 1, paramValues, TYPE_DBL, &(params->TV6FMUndesiredTimeFM))) {
			if (!defaultParams) break;
			params->TV6FMUndesiredTimeFM = defaultParams->TV6FMUndesiredTimeFM;
		}
		if (parse_table_parameter(PARAM_TV6_FM_DIST_DTV, 1, 1, TV6_FM_CHAN_COUNT, paramValues, TYPE_DBL,
				params->TV6FMDistanceDtv)) {
			if (!defaultParams) break;
			memcpy(params->TV6FMDistanceDtv, defaultParams->TV6FMDistanceDtv, (TV6_FM_CHAN_COUNT * sizeof(double)));
		}
		if (parse_parameter(PARAM_TV6_FM_BASE_DU_DTV, 1, paramValues, TYPE_DBL, &(params->TV6FMBaseDuDtv))) {
			if (!defaultParams) break;
			params->TV6FMBaseDuDtv = defaultParams->TV6FMBaseDuDtv;
		}
		if (parse_parameter(PARAM_TV6_FM_TME_UND_TV, 1, paramValues, TYPE_DBL, &(params->TV6FMUndesiredTimeTV))) {
			if (!defaultParams) break;
			params->TV6FMUndesiredTimeTV = defaultParams->TV6FMUndesiredTimeTV;
		}

		if (parse_parameter(PARAM_HAAT_RADIALS, MAX_COUNTRY, paramValues, TYPE_INT, params->HAATCount)) {
			if (!defaultParams) break;
			memcpy(params->HAATCount, defaultParams->HAATCount, (MAX_COUNTRY * sizeof(int)));
		}
		if (parse_parameter(PARAM_HAAT_RADIALS_LPTV, MAX_COUNTRY, paramValues, TYPE_INT, params->HAATCountLPTV)) {
			if (!defaultParams) break;
			memcpy(params->HAATCountLPTV, defaultParams->HAATCountLPTV, (MAX_COUNTRY * sizeof(int)));
		}
		if (parse_parameter(PARAM_MIN_HAAT, MAX_COUNTRY, paramValues, TYPE_DBL, params->MinimumHAAT)) {
			if (!defaultParams) break;
			memcpy(params->MinimumHAAT, defaultParams->MinimumHAAT, (MAX_COUNTRY * sizeof(int)));
		}
		if (parse_parameter(PARAM_AVET_STRT, MAX_COUNTRY, paramValues, TYPE_DBL, params->AVETStartDistance)) {
			if (!defaultParams) break;
			memcpy(params->AVETStartDistance, defaultParams->AVETStartDistance, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_AVET_END, MAX_COUNTRY, paramValues, TYPE_DBL, params->AVETEndDistance)) {
			if (!defaultParams) break;
			memcpy(params->AVETEndDistance, defaultParams->AVETEndDistance, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_CONTOUR_RADIALS, MAX_COUNTRY, paramValues, TYPE_INT, params->ContourCount)) {
			if (!defaultParams) break;
			memcpy(params->ContourCount, defaultParams->ContourCount, (MAX_COUNTRY * sizeof(int)));
		}
		if (parse_parameter(PARAM_CONT_LIMIT_VLO, MAX_COUNTRY, paramValues, TYPE_DBL, params->ContourLimitVlo)) {
			if (!defaultParams) break;
			memcpy(params->ContourLimitVlo, defaultParams->ContourLimitVlo, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_CONT_LIMIT_VHI, MAX_COUNTRY, paramValues, TYPE_DBL, params->ContourLimitVhi)) {
			if (!defaultParams) break;
			memcpy(params->ContourLimitVhi, defaultParams->ContourLimitVhi, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_CONT_LIMIT_UHF, MAX_COUNTRY, paramValues, TYPE_DBL, params->ContourLimitUHF)) {
			if (!defaultParams) break;
			memcpy(params->ContourLimitUHF, defaultParams->ContourLimitUHF, (MAX_COUNTRY * sizeof(double)));
		}

		if (parse_parameter(PARAM_RECEIVE_HEIGHT, 1, paramValues, TYPE_DBL, &(params->ReceiveHeight))) {
			if (!defaultParams) break;
			params->ReceiveHeight = defaultParams->ReceiveHeight;
		}
		if (parse_parameter(PARAM_MIN_XMTR_HEIGHT, 1, paramValues, TYPE_DBL, &(params->MinimumHeightAGL))) {
			if (!defaultParams) break;
			params->MinimumHeightAGL = defaultParams->MinimumHeightAGL;
		}

		if (parse_parameter(PARAM_RPAT_VLO_DTV, 1, paramValues, TYPE_DBL, &(params->ReceivePatVloDigital))) {
			if (!defaultParams) break;
			params->ReceivePatVloDigital = defaultParams->ReceivePatVloDigital;
		}
		if (parse_parameter(PARAM_RPAT_VHI_DTV, 1, paramValues, TYPE_DBL, &(params->ReceivePatVhiDigital))) {
			if (!defaultParams) break;
			params->ReceivePatVhiDigital = defaultParams->ReceivePatVhiDigital;
		}
		if (parse_parameter(PARAM_RPAT_UHF_DTV, 1, paramValues, TYPE_DBL, &(params->ReceivePatUhfDigital))) {
			if (!defaultParams) break;
			params->ReceivePatUhfDigital = defaultParams->ReceivePatUhfDigital;
		}
		if (parse_parameter(PARAM_RPAT_VLO_NTSC, 1, paramValues, TYPE_DBL, &(params->ReceivePatVloAnalog))) {
			if (!defaultParams) break;
			params->ReceivePatVloAnalog = defaultParams->ReceivePatVloAnalog;
		}
		if (parse_parameter(PARAM_RPAT_VHI_NTSC, 1, paramValues, TYPE_DBL, &(params->ReceivePatVhiAnalog))) {
			if (!defaultParams) break;
			params->ReceivePatVhiAnalog = defaultParams->ReceivePatVhiAnalog;
		}
		if (parse_parameter(PARAM_RPAT_UHF_NTSC, 1, paramValues, TYPE_DBL, &(params->ReceivePatUhfAnalog))) {
			if (!defaultParams) break;
			params->ReceivePatUhfAnalog = defaultParams->ReceivePatUhfAnalog;
		}

		if (parse_parameter(PARAM_RPAT_FM, 1, paramValues, TYPE_DBL, &(params->ReceivePatFM))) {
			if (!defaultParams) break;
			params->ReceivePatFM = defaultParams->ReceivePatFM;
		}

		if (parse_parameter(PARAM_LOC_DTV_DES, 1, paramValues, TYPE_DBL, &(params->DigitalDesiredLocation))) {
			if (!defaultParams) break;
			params->DigitalDesiredLocation = defaultParams->DigitalDesiredLocation;
		}
		if (parse_parameter(PARAM_TME_DTV_DES, 1, paramValues, TYPE_DBL, &(params->DigitalDesiredTime))) {
			if (!defaultParams) break;
			params->DigitalDesiredTime = defaultParams->DigitalDesiredTime;
		}
		if (parse_parameter(PARAM_CNF_DTV_DES, 1, paramValues, TYPE_DBL, &(params->DigitalDesiredConfidence))) {
			if (!defaultParams) break;
			params->DigitalDesiredConfidence = defaultParams->DigitalDesiredConfidence;
		}
		if (parse_parameter(PARAM_LOC_DTV_UND, 1, paramValues, TYPE_DBL, &(params->DigitalUndesiredLocation))) {
			if (!defaultParams) break;
			params->DigitalUndesiredLocation = defaultParams->DigitalUndesiredLocation;
		}
		if (parse_parameter(PARAM_CNF_DTV_UND, 1, paramValues, TYPE_DBL, &(params->DigitalUndesiredConfidence))) {
			if (!defaultParams) break;
			params->DigitalUndesiredConfidence = defaultParams->DigitalUndesiredConfidence;
		}
		if (parse_parameter(PARAM_LOC_NTSC_DES, 1, paramValues, TYPE_DBL, &(params->AnalogDesiredLocation))) {
			if (!defaultParams) break;
			params->AnalogDesiredLocation = defaultParams->AnalogDesiredLocation;
		}
		if (parse_parameter(PARAM_TME_NTSC_DES, 1, paramValues, TYPE_DBL, &(params->AnalogDesiredTime))) {
			if (!defaultParams) break;
			params->AnalogDesiredTime = defaultParams->AnalogDesiredTime;
		}
		if (parse_parameter(PARAM_CNF_NTSC_DES, 1, paramValues, TYPE_DBL, &(params->AnalogDesiredConfidence))) {
			if (!defaultParams) break;
			params->AnalogDesiredConfidence = defaultParams->AnalogDesiredConfidence;
		}
		if (parse_parameter(PARAM_LOC_NTSC_UND, 1, paramValues, TYPE_DBL, &(params->AnalogUndesiredLocation))) {
			if (!defaultParams) break;
			params->AnalogUndesiredLocation = defaultParams->AnalogUndesiredLocation;
		}
		if (parse_parameter(PARAM_CNF_NTSC_UND, 1, paramValues, TYPE_DBL, &(params->AnalogUndesiredConfidence))) {
			if (!defaultParams) break;
			params->AnalogUndesiredConfidence = defaultParams->AnalogUndesiredConfidence;
		}
		if (parse_parameter(PARAM_SIG_POL, 1, paramValues, TYPE_INT, &(params->SignalPolarization))) {
			if (!defaultParams) break;
			params->SignalPolarization = defaultParams->SignalPolarization;
		}
		if (parse_parameter(PARAM_ATM_REFRAC, 1, paramValues, TYPE_DBL, &(params->AtmosphericRefractivity))) {
			if (!defaultParams) break;
			params->AtmosphericRefractivity = defaultParams->AtmosphericRefractivity;
		}
		if (parse_parameter(PARAM_GND_PERMIT, 1, paramValues, TYPE_DBL, &(params->GroundPermittivity))) {
			if (!defaultParams) break;
			params->GroundPermittivity = defaultParams->GroundPermittivity;
		}
		if (parse_parameter(PARAM_GND_CONDUC, 1, paramValues, TYPE_DBL, &(params->GroundConductivity))) {
			if (!defaultParams) break;
			params->GroundConductivity = defaultParams->GroundConductivity;
		}

		if (parse_parameter(PARAM_LR_SERV_MODE, 1, paramValues, TYPE_INT, &(params->LRServiceMode))) {
			if (!defaultParams) break;
			params->LRServiceMode = defaultParams->LRServiceMode;
		}
		if (parse_parameter(PARAM_LR_CLIM_TYPE, 1, paramValues, TYPE_INT, &(params->LRClimateType))) {
			if (!defaultParams) break;
			params->LRClimateType = defaultParams->LRClimateType;
		}

		if (parse_parameter(PARAM_EARTH_SPH_DIST, 1, paramValues, TYPE_DBL, &(params->KilometersPerDegree))) {
			if (!defaultParams) break;
			params->KilometersPerDegree = defaultParams->KilometersPerDegree;
		}

		if (parse_parameter(PARAM_RUL_EXT_DST_L, 1, paramValues, TYPE_DBL, params->RuleExtraDistance)) {
			if (!defaultParams) break;
			params->RuleExtraDistance[0] = defaultParams->RuleExtraDistance[0];
		}
		if (parse_parameter(PARAM_RUL_EXT_ERP_L, 1, paramValues, TYPE_DBL, params->RuleExtraDistanceERP)) {
			if (!defaultParams) break;
			params->RuleExtraDistanceERP[0] = defaultParams->RuleExtraDistanceERP[0];
		}
		if (parse_parameter(PARAM_RUL_EXT_DST_LM, 1, paramValues, TYPE_DBL, (params->RuleExtraDistance + 1))) {
			if (!defaultParams) break;
			params->RuleExtraDistance[1] = defaultParams->RuleExtraDistance[1];
		}
		if (parse_parameter(PARAM_RUL_EXT_ERP_M, 1, paramValues, TYPE_DBL, (params->RuleExtraDistanceERP + 1))) {
			if (!defaultParams) break;
			params->RuleExtraDistanceERP[1] = defaultParams->RuleExtraDistanceERP[1];
		}
		if (parse_parameter(PARAM_RUL_EXT_DST_MH, 1, paramValues, TYPE_DBL, (params->RuleExtraDistance + 2))) {
			if (!defaultParams) break;
			params->RuleExtraDistance[2] = defaultParams->RuleExtraDistance[2];
		}
		if (parse_parameter(PARAM_RUL_EXT_ERP_H, 1, paramValues, TYPE_DBL, (params->RuleExtraDistanceERP + 2))) {
			if (!defaultParams) break;
			params->RuleExtraDistanceERP[2] = defaultParams->RuleExtraDistanceERP[2];
		}
		if (parse_parameter(PARAM_RUL_EXT_DST_H, 1, paramValues, TYPE_DBL, (params->RuleExtraDistance + 3))) {
			if (!defaultParams) break;
			params->RuleExtraDistance[3] = defaultParams->RuleExtraDistance[3];
		}
		if (parse_parameter(PARAM_RUL_EXT_MAX, 1, paramValues, TYPE_INT, &(params->UseMaxRuleExtraDistance))) {
			if (!defaultParams) break;
			params->UseMaxRuleExtraDistance = defaultParams->UseMaxRuleExtraDistance;
		}

		if (parse_parameter(PARAM_OA_HAAT_RADIALS, MAX_COUNTRY, paramValues, TYPE_INT, params->OverallHAATCount)) {
			if (!defaultParams) break;
			memcpy(params->OverallHAATCount, defaultParams->OverallHAATCount, (MAX_COUNTRY * sizeof(int)));
		}

		// The clutter adjustments must always be a sequence of parameters starting with PARAM_CLUTTER and running
		// with sequential key values comprising a total of PARAM_N_CLUTTER parameters.  The map of land-cover
		// categories to clutter types consisting of PARAM_N_LANDCOVER parameters must follow in sequence.

		if (parse_parameter(PARAM_APPLY_CLUTTER, 1, paramValues, TYPE_INT, &(params->ApplyClutter))) {
			if (!defaultParams) break;
			params->ApplyClutter = defaultParams->ApplyClutter;
		}
		if (parse_parameter(PARAM_LANDCOVER_VERSION, 1, paramValues, TYPE_INT, &(params->LandCoverVersion))) {
			if (!defaultParams) break;
			params->LandCoverVersion = defaultParams->LandCoverVersion;
		}
		paramKey = PARAM_CLUTTER;
		for (clutterIndex = 0; clutterIndex < PARAM_N_CLUTTER; clutterIndex++, paramKey++) {
			if (parse_parameter(paramKey, MAX_COUNTRY, paramValues, TYPE_DBL,
					(params->ClutterValues + (clutterIndex * MAX_COUNTRY)))) {
				if (!defaultParams) break;
				memcpy((params->ClutterValues + (clutterIndex * MAX_COUNTRY)),
					(defaultParams->ClutterValues + (clutterIndex * MAX_COUNTRY)), (MAX_COUNTRY * sizeof(int)));
			}
		}
		if (clutterIndex < PARAM_N_CLUTTER) {
			break;
		}
		for (clutterIndex = 0; clutterIndex < PARAM_N_LANDCOVER; clutterIndex++, paramKey++) {
			if (parse_parameter(paramKey, 1, paramValues, TYPE_INT, (params->LandCoverClutter + clutterIndex))) {
				if (!defaultParams) break;
				params->LandCoverClutter[clutterIndex] = defaultParams->LandCoverClutter[clutterIndex];
			}
		}
		if (clutterIndex < PARAM_N_LANDCOVER) {
			break;
		}

		if (parse_parameter(PARAM_SET_SERVICE, 1, paramValues, TYPE_INT, &(params->SetServiceLevels))) {
			if (!defaultParams) break;
			params->SetServiceLevels = defaultParams->SetServiceLevels;
		}
		if (parse_parameter(PARAM_SL_VLO_DTV, MAX_COUNTRY, paramValues, TYPE_DBL, params->ServiceVloDigital)) {
			if (!defaultParams) break;
			memcpy(params->ServiceVloDigital, defaultParams->ServiceVloDigital, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_SL_VHI_DTV, MAX_COUNTRY, paramValues, TYPE_DBL, params->ServiceVhiDigital)) {
			if (!defaultParams) break;
			memcpy(params->ServiceVhiDigital, defaultParams->ServiceVhiDigital, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_SL_UHF_DTV, MAX_COUNTRY, paramValues, TYPE_DBL, params->ServiceUhfDigital)) {
			if (!defaultParams) break;
			memcpy(params->ServiceUhfDigital, defaultParams->ServiceUhfDigital, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_SL_VLO_DTV_LP, MAX_COUNTRY, paramValues, TYPE_DBL, params->ServiceVloDigitalLPTV)) {
			if (!defaultParams) break;
			memcpy(params->ServiceVloDigitalLPTV, defaultParams->ServiceVloDigitalLPTV, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_SL_VHI_DTV_LP, MAX_COUNTRY, paramValues, TYPE_DBL, params->ServiceVhiDigitalLPTV)) {
			if (!defaultParams) break;
			memcpy(params->ServiceVhiDigitalLPTV, defaultParams->ServiceVhiDigitalLPTV, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_SL_UHF_DTV_LP, MAX_COUNTRY, paramValues, TYPE_DBL, params->ServiceUhfDigitalLPTV)) {
			if (!defaultParams) break;
			memcpy(params->ServiceUhfDigitalLPTV, defaultParams->ServiceUhfDigitalLPTV, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_SL_VLO_NTSC, MAX_COUNTRY, paramValues, TYPE_DBL, params->ServiceVloAnalog)) {
			if (!defaultParams) break;
			memcpy(params->ServiceVloAnalog, defaultParams->ServiceVloAnalog, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_SL_VHI_NTSC, MAX_COUNTRY, paramValues, TYPE_DBL, params->ServiceVhiAnalog)) {
			if (!defaultParams) break;
			memcpy(params->ServiceVhiAnalog, defaultParams->ServiceVhiAnalog, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_SL_UHF_NTSC, MAX_COUNTRY, paramValues, TYPE_DBL, params->ServiceUhfAnalog)) {
			if (!defaultParams) break;
			memcpy(params->ServiceUhfAnalog, defaultParams->ServiceUhfAnalog, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_SL_VLO_NTSC_LP, MAX_COUNTRY, paramValues, TYPE_DBL, params->ServiceVloAnalogLPTV)) {
			if (!defaultParams) break;
			memcpy(params->ServiceVloAnalogLPTV, defaultParams->ServiceVloAnalogLPTV, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_SL_VHI_NTSC_LP, MAX_COUNTRY, paramValues, TYPE_DBL, params->ServiceVhiAnalogLPTV)) {
			if (!defaultParams) break;
			memcpy(params->ServiceVhiAnalogLPTV, defaultParams->ServiceVhiAnalogLPTV, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_SL_UHF_NTSC_LP, MAX_COUNTRY, paramValues, TYPE_DBL, params->ServiceUhfAnalogLPTV)) {
			if (!defaultParams) break;
			memcpy(params->ServiceUhfAnalogLPTV, defaultParams->ServiceUhfAnalogLPTV, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_USE_DIPOLE_SL, MAX_COUNTRY, paramValues, TYPE_INT, params->UseDipoleServ)) {
			if (!defaultParams) break;
			memcpy(params->UseDipoleServ, defaultParams->UseDipoleServ, (MAX_COUNTRY * sizeof(int)));
		}
		if (parse_parameter(PARAM_DIPOLE_CENTER_SL, MAX_COUNTRY, paramValues, TYPE_DBL, params->DipoleCenterFreqServ)) {
			if (!defaultParams) break;
			memcpy(params->DipoleCenterFreqServ, defaultParams->DipoleCenterFreqServ, (MAX_COUNTRY * sizeof(double)));
		}

		if (parse_parameter(PARAM_SL_FM, MAX_COUNTRY, paramValues, TYPE_DBL, params->ServiceFM)) {
			if (!defaultParams) break;
			memcpy(params->ServiceFM, defaultParams->ServiceFM, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_SL_FM_B, MAX_COUNTRY, paramValues, TYPE_DBL, params->ServiceFMB)) {
			if (!defaultParams) break;
			memcpy(params->ServiceFMB, defaultParams->ServiceFMB, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_SL_FM_B1, MAX_COUNTRY, paramValues, TYPE_DBL, params->ServiceFMB1)) {
			if (!defaultParams) break;
			memcpy(params->ServiceFMB1, defaultParams->ServiceFMB1, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_SL_FM_ED, MAX_COUNTRY, paramValues, TYPE_DBL, params->ServiceFMED)) {
			if (!defaultParams) break;
			memcpy(params->ServiceFMED, defaultParams->ServiceFMED, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_SL_FM_LP, MAX_COUNTRY, paramValues, TYPE_DBL, params->ServiceFMLP)) {
			if (!defaultParams) break;
			memcpy(params->ServiceFMLP, defaultParams->ServiceFMLP, (MAX_COUNTRY * sizeof(double)));
		}
		if (parse_parameter(PARAM_SL_FM_TX, MAX_COUNTRY, paramValues, TYPE_DBL, params->ServiceFMTX)) {
			if (!defaultParams) break;
			memcpy(params->ServiceFMTX, defaultParams->ServiceFMTX, (MAX_COUNTRY * sizeof(double)));
		}

		if (parse_parameter(PARAM_WL_REQD_DU, WL_OVERLAP_COUNT, paramValues, TYPE_DBL, params->WirelessDU)) {
			if (!defaultParams) break;
			memcpy(params->WirelessDU, defaultParams->WirelessDU, (WL_OVERLAP_COUNT * sizeof(double)));
		}

		if (parse_table_parameter(PARAM_WL_CULL_DIST, WL_OVERLAP_COUNT, WL_CULL_HAAT_COUNT, WL_CULL_ERP_COUNT,
				paramValues, TYPE_DBL, params->WirelessCullDistance)) {
			if (!defaultParams) break;
			memcpy(params->WirelessCullDistance, defaultParams->WirelessCullDistance,
				(WL_OVERLAP_COUNT * WL_CULL_HAAT_COUNT * WL_CULL_ERP_COUNT * sizeof(double)));
		}

		if (parse_parameter(PARAM_WL_CAP_DU_RAMP, 1, paramValues, TYPE_INT, &(params->WirelessCapDURamp))) {
			if (!defaultParams) break;
			params->WirelessCapDURamp = defaultParams->WirelessCapDURamp;
		}
		if (parse_parameter(PARAM_WL_DU_RAMP_CAP, 1, paramValues, TYPE_DBL, &(params->WirelessDURampCap))) {
			if (!defaultParams) break;
			params->WirelessDURampCap = defaultParams->WirelessDURampCap;
		}

		if (parse_parameter(PARAM_WL_AVG_TERR_RES, 1, paramValues, TYPE_DBL, &(params->WirelessTerrAvgPpk))) {
			if (!defaultParams) break;
			params->WirelessTerrAvgPpk = defaultParams->WirelessTerrAvgPpk;
		}
		if (parse_parameter(PARAM_WL_PATH_TERR_RES, 1, paramValues, TYPE_DBL, &(params->WirelessTerrPathPpk))) {
			if (!defaultParams) break;
			params->WirelessTerrPathPpk = defaultParams->WirelessTerrPathPpk;
		}
		if (parse_parameter(PARAM_WL_OA_HAAT_RADIALS, 1, paramValues, TYPE_INT, &(params->WirelessOverallHAATCount))) {
			if (!defaultParams) break;
			params->WirelessOverallHAATCount = defaultParams->WirelessOverallHAATCount;
		}
		if (parse_parameter(PARAM_WL_DEPANGLE_METH, 1, paramValues, TYPE_INT,
				&(params->WirelessDepressionAngleMethod))) {
			if (!defaultParams) break;
			params->WirelessDepressionAngleMethod = defaultParams->WirelessDepressionAngleMethod;
		}
		if (parse_parameter(PARAM_WL_VPAT_MTILT, 1, paramValues, TYPE_INT, &(params->WirelessVpatMechTilt))) {
			if (!defaultParams) break;
			params->WirelessVpatMechTilt = defaultParams->WirelessVpatMechTilt;
		}
		if (parse_parameter(PARAM_WL_VPAT_GEN_MIRROR, 1, paramValues, TYPE_INT,
				&(params->WirelessGenericVpatMirroring))) {
			if (!defaultParams) break;
			params->WirelessGenericVpatMirroring = defaultParams->WirelessGenericVpatMirroring;
		}
		if (parse_parameter(PARAM_WL_MIN_XMTR_HEIGHT, 1, paramValues, TYPE_DBL,
				&(params->WirelessMinimumHeightAGL))) {
			if (!defaultParams) break;
			params->WirelessMinimumHeightAGL = defaultParams->WirelessMinimumHeightAGL;
		}
		if (parse_parameter(PARAM_WL_LOC_UND, 1, paramValues, TYPE_DBL, &(params->WirelessUndesiredLocation))) {
			if (!defaultParams) break;
			params->WirelessUndesiredLocation = defaultParams->WirelessUndesiredLocation;
		}
		if (parse_parameter(PARAM_WL_TME_UND, 1, paramValues, TYPE_DBL, &(params->WirelessUndesiredTime))) {
			if (!defaultParams) break;
			params->WirelessUndesiredTime = defaultParams->WirelessUndesiredTime;
		}
		if (parse_parameter(PARAM_WL_CNF_UND, 1, paramValues, TYPE_DBL, &(params->WirelessUndesiredConfidence))) {
			if (!defaultParams) break;
			params->WirelessUndesiredConfidence = defaultParams->WirelessUndesiredConfidence;
		}
		if (parse_parameter(PARAM_WL_SIG_POL, 1, paramValues, TYPE_INT, &(params->WirelessSignalPolarization))) {
			if (!defaultParams) break;
			params->WirelessSignalPolarization = defaultParams->WirelessSignalPolarization;
		}
		if (parse_parameter(PARAM_WL_LR_SERV_MODE, 1, paramValues, TYPE_INT, &(params->WirelessLRServiceMode))) {
			if (!defaultParams) break;
			params->WirelessLRServiceMode = defaultParams->WirelessLRServiceMode;
		}

		if (parse_parameter(PARAM_TVIX_LIMIT, 1, paramValues, TYPE_DBL, &(params->IxCheckLimitPercent))) {
			if (!defaultParams) break;
			params->IxCheckLimitPercent = defaultParams->IxCheckLimitPercent;
		}
		if (parse_parameter(PARAM_TVIX_LIMIT_LPTV, 1, paramValues, TYPE_DBL, &(params->IxCheckLimitPercentLPTV))) {
			if (!defaultParams) break;
			params->IxCheckLimitPercentLPTV = defaultParams->IxCheckLimitPercentLPTV;
		}
		if (parse_parameter(PARAM_TVIX_CHECK_BL, 1, paramValues, TYPE_INT, &(params->CheckBaselineAreaPop))) {
			if (!defaultParams) break;
			params->CheckBaselineAreaPop = defaultParams->CheckBaselineAreaPop;
		}
		if (parse_parameter(PARAM_TVIX_BL_AREA_PCT, 1, paramValues, TYPE_DBL, &(params->BaselineAreaExtendPercent))) {
			if (!defaultParams) break;
			params->BaselineAreaExtendPercent = defaultParams->BaselineAreaExtendPercent;
		}
		if (parse_parameter(PARAM_TVIX_BL_POP_PCT, 1, paramValues, TYPE_DBL, &(params->BaselinePopReducePercent))) {
			if (!defaultParams) break;
			params->BaselinePopReducePercent = defaultParams->BaselinePopReducePercent;
		}

		err = 0;
		break;
	}

	// If no error so far, load scenario parameters.

	while (!err) {

		err = 1;

		if (parse_parameter(PARAM_SCEN_WL_FREQ, 1, paramValues, TYPE_DBL, &(params->WirelessFrequency))) {
			if (!defaultParams) break;
			params->WirelessFrequency = defaultParams->WirelessFrequency;
		}
		if (parse_parameter(PARAM_SCEN_WL_BW, 1, paramValues, TYPE_DBL, &(params->WirelessBandwidth))) {
			if (!defaultParams) break;
			params->WirelessBandwidth = defaultParams->WirelessBandwidth;
		}

		err = 0;
		break;
	}

	// Free temporary storage.

	for (paramIndex = 0; paramIndex < (PARAM_KEY_SIZE * MAX_VALUE_COUNT); paramIndex++) {
		if (paramValues[paramIndex]) {
			mem_free(paramValues[paramIndex]);
			paramValues[paramIndex] = NULL;
		}
	}
	mem_free(paramValues);

	// Error means missing parameter and no defaults, this should only happen during load of the defaults template and
	// there is no possible recovery so this is a process-fatal error.

	if (err) {
		log_error("Missing parameter value, database is damaged");
		return -1;
	}

	// Success.

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Parse one parameter, which may consist of one or more actual values per valueCount.  Return 0 for success, non-zero
// if value is missing.  For a multi-value parameter, values must be present for all indices.  The values may not be
// independent of each other and so are handled as an all-or-nothing set.  However there is one special case.  If a
// multi-value parameter has a value for the first index but is missing all later indices, the first one is filled down
// to all others.  That allows existing parameters to be changed from single- to multi-value, which would only occur if
// the set of values are independent, e.g. values for different countries.

static int parse_parameter(int paramKey, int valueCount, char **paramValues, int type, void *destination) {

	int *idest = (int *)destination;
	double *ddest = (double *)destination;

	int paramBaseIndex = paramKey * MAX_VALUE_COUNT, valueIndex, fillDown = 0;

	if ((valueCount > 1) && paramValues[paramBaseIndex] && !paramValues[paramBaseIndex + 1]) {
		for (valueIndex = 2; valueIndex < valueCount; valueIndex++) {
			if (paramValues[paramBaseIndex + valueIndex]) {
				break;
			}
		}
		fillDown = (valueIndex >= valueCount);
	}

	int paramIndex = paramBaseIndex;

	for (valueIndex = 0; valueIndex < valueCount; valueIndex++) {
		if (!fillDown) {
			paramIndex = paramBaseIndex + valueIndex;
			if (!paramValues[paramIndex]) {
				return 1;
			}
		}
		switch (type) {
			case TYPE_INT:
				idest[valueIndex] = atoi(paramValues[paramIndex]);
				break;
			case TYPE_DBL:
				ddest[valueIndex] = atof(paramValues[paramIndex]);
				break;
		}
	}

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Parse a table parameter, see above for basic logic.  Table parameter values are stored as comma-separated strings
// of numbers in row-major order.  The destination array is assumed to be in the same storage order, this parses the
// comma-separated values into that array in sequence.  Also for a multi-valued parameter the values are concatenated
// in sequence to the destination array.  If the comma-separated list of values is short parsing fails.  If it is long
// extra values are ignored.

static int parse_table_parameter(int paramKey, int valueCount, int rowCount, int colCount, char **paramValues,
		int type, void *destination) {

	int *idest = (int *)destination;
	double *ddest = (double *)destination;

	int paramBaseIndex = paramKey * MAX_VALUE_COUNT, valueIndex, fillDown = 0;

	if ((valueCount > 1) && paramValues[paramBaseIndex] && !paramValues[paramBaseIndex + 1]) {
		for (valueIndex = 2; valueIndex < valueCount; valueIndex++) {
			if (paramValues[paramBaseIndex + valueIndex]) {
				break;
			}
		}
		fillDown = (valueIndex >= valueCount);
	}

	int paramIndex = paramBaseIndex, tableSize = rowCount * colCount, tableIndex, arrayIndex;
	char *fld, *sep, chr;

	for (valueIndex = 0; valueIndex < valueCount; valueIndex++) {
		if (!fillDown) {
			paramIndex = paramBaseIndex + valueIndex;
			if (!paramValues[paramIndex]) {
				return 1;
			}
		}
		fld = paramValues[paramIndex];
		for (tableIndex = 0, arrayIndex = valueIndex * tableSize; tableIndex < tableSize; tableIndex++, arrayIndex++) {
			if ((sep = index(fld, ','))) {
				chr = *sep;
				*sep = '\0';
			} else {
				if (tableIndex < (tableSize - 1)) {
					return 1;
				}
			}
			switch (type) {
				case TYPE_INT:
					idest[arrayIndex] = atoi(fld);
					break;
				case TYPE_DBL:
					ddest[arrayIndex] = atof(fld);
					break;
			}
			if (sep) {
				*sep = chr;
				fld = sep + 1;
			}
		}
	}

	return 0;
}
