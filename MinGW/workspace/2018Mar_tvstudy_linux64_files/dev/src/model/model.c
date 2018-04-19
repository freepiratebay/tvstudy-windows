//
//  model.c
//  TVStudy
//
//  Copyright (c) 2016-2017 Hammett & Edison, Inc.  All rights reserved.


// Abstraction layer for propagation models, provides a dependency-free bridge between tvstudy or clutil and the model
// code.  Model code can be implemented entirely here, or by calling other modules from here that only need to include
// model.h.  This changes behavior for a build of the tvstudy engine or the command-line utility due to fcc_curve().


#ifdef __BUILD_TVSTUDY
#include "../tvstudy.h"
#else
#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
#include <math.h>

#include "../global.h"
#include "model.h"
#include "../fcc_curve.h"
#include "../terrain.h"
#include "../memory.h"
#endif


//+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// ADDING A NEW MODEL: To add a new propagation model, make changes to this file and to model.h in each section that
// has a comment like this one.  The actual model code may be added directly to this file, or placed in separate code
// files.  For examples of both, see the fcc_curve_model() function below and the separate longley_rice.c file.  In the
// separate file case the main TVStudy project Makefile must also be modified, see comments there.  Once changes are
// made, rebuild TVStudy.  Start in model.h, then add code here to provide a descriptive name and include a message
// identifying the model.  The get_model_list() call is used to provide a list of available models to the UI to
// determine what models are available for selection in the study editor.  The model name appears in that UI, and will
// also be included in the settings report output file from a study run.  Note the UI pick-list will show the models
// in the order they are printed by get_model_list(), regardless of numerical sequence of the model numbers.  Do not
// use the '=' character in a model name.

char *get_model_name(int model) {

	switch (model) {

		case MODEL_LONGLEY_RICE: {
			return "Longley-Rice";
		}

		case MODEL_FCC_CURVES: {
			return "FCC curves";
		}

		case MODEL_FREE_SPACE: {
			return "Free space";
		}
	}

	return "(unknown)";
}

char *get_model_list() {

	static char list[10 * MESSAGE_LEN];

	list[0] = '\0';
	char model[MESSAGE_LEN];

	snprintf(model, MESSAGE_LEN, "%d=%s\n", MODEL_LONGLEY_RICE, get_model_name(MODEL_LONGLEY_RICE));
	lcatstr(list, model, (10 * MESSAGE_LEN));

	snprintf(model, MESSAGE_LEN, "%d=%s\n", MODEL_FCC_CURVES, get_model_name(MODEL_FCC_CURVES));
	lcatstr(list, model, (10 * MESSAGE_LEN));

	snprintf(model, MESSAGE_LEN, "%d=%s\n", MODEL_FREE_SPACE, get_model_name(MODEL_FREE_SPACE));
	lcatstr(list, model, (10 * MESSAGE_LEN));

	return list;
}
//+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++


//---------------------------------------------------------------------------------------------------------------------
// The model run function called from study code, make changes only in the flagged sections.

int run_model(MODEL_DATA *data) {

	// Terrain profile storage.

	static float *profileBlock = NULL, *profile = NULL;
	static int profileMaxCount = 0;

	//+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// ADDING A NEW MODEL: Add code here if needed to adjust the resolution or point count before profile extraction.
	// Values may be changed directly in the MODEL_DATA structure.  The point count is actually how profile length is
	// determined, so the resolution and count do not have to be consistent with the actual distance.  Note the count
	// is one more than the distance times resolution because the first profile point is at distance 0.  If the count
	// is 0 no profile will be extracted.  Many models won't need to do anything here.

	switch (data->model) {

		case MODEL_FCC_CURVES: {
			data->profileCount = (int)((17. * data->profilePpk) + 0.5) + 1;
			break;
		}

		case MODEL_FREE_SPACE: {
			data->profileCount = 0;
			break;
		}
	}
	//+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

	int err = 0;

	// Retrieve the terrain profile.  There is a bit of a hack here for performance.  Profile data storage is offset
	// into the allocated block so there are available storage positions ahead of the actual profile data.  That is
	// used by longley_rice() because the FORTRAN module called from there expects other arguments to be passed in
	// array positions ahead of the profile.  Without the offset the profile would have to be copied to another buffer.
	// However the profile in the MODEL_DATA structure points to the start of the actual profile data, so models that
	// don't need the extra storage can just ignore this.

	if (data->profileCount > 0) {

		if (data->profileCount > profileMaxCount) {
			profileMaxCount = data->profileCount + 500;
			profileBlock = (float *)mem_realloc(profileBlock, ((profileMaxCount + PROFILE_OFFSET) * sizeof(float)));
			profile = profileBlock + PROFILE_OFFSET;
		}

		// The distance given to terrain_profile() represents one point spacing more than actually needed, that will
		// almost always result in an extra point in the profile which will just be ignored.  However every now and
		// then this is necessary because the extra point will not be there due to rounding in the extraction code.
		// If the profile still comes up short but terrain_profile() did not report an error, log a message and return
		// an error anyway; if there are at least enough points, ignore any non-fatal error from terrain_profile().

		double distance = (double)data->profileCount / data->profilePpk;

		int profileCount;
		err = terrain_profile(data->latitude, data->longitude, data->bearing, distance, data->profilePpk,
			data->terrainDb, profileMaxCount, profile, &profileCount, data->kilometersPerDegree);
		if (err < 0) {
			snprintf(data->errorMessage, MESSAGE_LEN,
				"Terrain lookup failed: lat=%.8f lon=%.8f bear=%.2f dist=%.2f db=%d err=%d", data->latitude,
				data->longitude, data->bearing, data->distance, data->terrainDb, err);
			return err;
		}

		if (profileCount < data->profileCount) {
			snprintf(data->errorMessage, MESSAGE_LEN,
				"Terrain profile is short: lat=%.8f lon=%.8f bear=%.2f dist=%.2f db=%d nnp=%d np=%d err=%d",
				data->latitude, data->longitude, data->bearing, data->distance, data->terrainDb, data->profileCount,
				profileCount, err);
			return 1;
		}

		data->profile = profile;
	}

	//+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// ADDING A NEW MODEL: Add code here to run the model.  The final result must be two values set in the MODEL_DATA
	// structure, data->fieldStrength set to a field strength in dBu for a reference ERP of 0 dBk, and data->errorCode
	// set to a non-zero value if some non-fatal modelling issue occurs.  Regardless of whether errorCode is set or not
	// there must be a reasonable value placed in fieldStrength.  If an error occurs that prevents a field strength
	// from being determined at all, respond as in the default case below; write an error message string and return 1.

	switch (data->model) {

		default: {
			snprintf(data->errorMessage, MESSAGE_LEN, "**Unknown propagation model %d", data->model);
			return 1;
		}

		case MODEL_LONGLEY_RICE: {
			longley_rice(data);
			break;
		}

		case MODEL_FCC_CURVES: {
			fcc_curve_model(data);
			break;
		}

		case MODEL_FREE_SPACE: {
			data->fieldStrength = 106.92 - (20. * log10(data->distance));
			break;
		}
	}
	//+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Use FCC propagation curves as a general study point signal strength model.  Compute HAAT from profile, set band and
// curve set based on frequency and statistical parameters choosing the nearest appropriate, and call the curve lookup
// function in field-at-distance mode.  Vertical pattern correction is not applied here, that will be done later.

void fcc_curve_model(MODEL_DATA *data) {

	double avet = 0.;
	int i, i1 = 3.2 * data->profilePpk, i2 = 16.1 * data->profilePpk;
	if (i2 > (data->profileCount - 1)) {
		i2 = data->profileCount - 1;
	}
	for (i = i1; i <= i2; i++) {
		avet += data->profile[i];
	}
	avet /= (double)(i2 - i1 + 1);

	double haat = (data->profile[0] + data->transmitHeightAGL) - avet;
	if (haat < 30.5) {
		haat = 30.5;
	}

	int band;
	if (data->frequency < 74.) {
		band = BAND_VLO1;
	} else {
		if (data->frequency < 131.) {
			band = BAND_VLO2;
		} else {
			if (data->frequency < 300.) {
				band = BAND_VHI;
			} else {
				band = BAND_UHF;
			}
		}
	}

	int curv;
	if (data->percentTime < 30.) {
		curv = FCC_F10;
	} else {
		if (data->percentTime < 70.) {
			curv = FCC_F50;
		} else {
			curv = FCC_F90;
		}
	}

	double erp = 0.;
#ifdef __BUILD_TVSTUDY
	data->errorCode = fcc_curve(&erp, &(data->fieldStrength), &(data->distance), haat, band, FCC_FLD, curv,
		Params.OffCurveLookupMethod, NULL, 0., NULL, NULL, NULL);
#else
	data->errorCode = fcc_curve(&erp, &(data->fieldStrength), &(data->distance), haat, band, FCC_FLD, curv,
		OFF_CURV_METH_FS);
#endif
}
