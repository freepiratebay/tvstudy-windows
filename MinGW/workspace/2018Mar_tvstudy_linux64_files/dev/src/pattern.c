//
//  pattern.c
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.


// Functions for doing lookups in horizontal and vertical patterns.


#include "tvstudy.h"


//---------------------------------------------------------------------------------------------------------------------
// Load the antenna patterns for a record, this retrieves pattern data for horizontal and vertical from the database
// and converts/interpolates.  Minimal error-checking is done, pattern data is logically part of the source record and
// should have been checked by the front-end apps.  All patterns are stored in relative dB here, in the database they
// are relative field.  Horizontal patterns are interpolated at 1-degree increments to make lookups faster.  Vertical
// patterns are stored as-is and interpolated during lookup, and may also be "mirrored" to extend data symmetrically
// around the pattern maximum, also done during lookup.

// Arguments:

//   source  The source for which to load patterns.

// Return is <0 for serious error, >0 for minor error (missing data is a minor error), 0 for no error.

int load_patterns(SOURCE *source) {

	static int max_pts = 0, *npbuf = NULL;
	static double *azbuf = NULL, *rfbuf = NULL;

	char query[MAX_QUERY];
	MYSQL_RES *myResult;
	MYSQL_ROW fields;

	VPAT *vpt;
	MPAT *mpt;
	double *pat, *dep, azm, az0, az1, rfl, rfmax, dpmax;
	size_t siz;
	int n_pts, i, i0, i1, n_pts_tot, j;

	// Horizontal pattern first.  If there is already data, toss it and reload.

	if (source->hasHpat) {

		if (source->hpat) {
			mem_free(source->hpat);
			source->hpat = NULL;
		}

		snprintf(query, MAX_QUERY, "SELECT azimuth, relative_field FROM %s_%d.source_horizontal_pattern WHERE source_key = %d ORDER BY azimuth;", DbName, StudyKey, source->sourceKey);
		if (mysql_query(MyConnection, query)) {
			log_db_error("Horizontal pattern query failed (1) for sourceKey=%d", source->sourceKey);
			return -1;
		}

		myResult = mysql_store_result(MyConnection);
		if (!myResult) {
			log_db_error("Horizontal pattern query failed (2) for sourceKey=%d", source->sourceKey);
			return -1;
		}

		// Load the pattern data into a temporary buffer.

		n_pts = (int)mysql_num_rows(myResult);
		if (n_pts < 2) {
			mysql_free_result(myResult);
			log_error("Missing horizontal pattern data for sourceKey=%d", source->sourceKey);
			return 1;
		}

		if (n_pts > max_pts) {
			max_pts = n_pts + 20;
			azbuf = (double *)mem_realloc(azbuf, (max_pts * sizeof(double)));
			rfbuf = (double *)mem_realloc(rfbuf, (max_pts * sizeof(double)));
			npbuf = (int *)mem_realloc(npbuf, (max_pts * sizeof(int)));
		}

		for (i = 0; i < n_pts; i++) {

			fields = mysql_fetch_row(myResult);
			if (NULL == fields) {
				mysql_free_result(myResult);
				log_db_error("Horizontal pattern query failed (3) for sourceKey=%d", source->sourceKey);
				return -1;
			}

			azbuf[i] = atof(fields[0]);
			rfbuf[i] = atof(fields[1]);
		}

		mysql_free_result(myResult);

		pat = (double *)mem_alloc(360 * sizeof(double));
		source->hpat = pat;

		// Make a safe assumption here, if the pattern has exactly 360 points then it is an already-interpolated
		// 1-degree-point pattern, probably a replication pattern, just save it directly.

		if (360 == n_pts) {

			for (i = 0; i < n_pts; i++) {
				pat[i] = 20. * log10(rfbuf[i]);
			}

		// Interpolate a 360-point relative dB pattern.

		} else {

			if (0. == azbuf[0]) {
				i0 = 0;
				az0 = azbuf[i0];
				i1 = 1;
				az1 = azbuf[i1];
			} else {
				i0 = n_pts - 1;
				az0 = azbuf[i0] - 360.;
				i1 = 0;
				az1 = azbuf[i1];
			}

			for (i = 0; i < 360; i++) {

				azm = (double)i;

				while (azm >= az1) {
					i0++;
					if (i0 == n_pts) {
						i0 = 0;
					}
					az0 = azbuf[i0];
					i1++;
					if (i1 == n_pts) {
						i1 = 0;
						az1 = azbuf[i1] + 360.;
					} else {
						az1 = azbuf[i1];
					}
				}

				rfl = rfbuf[i0] + (((azm - az0) / (az1 - az0)) * (rfbuf[i1] - rfbuf[i0]));
				pat[i] = 20. * log10(rfl);
			}
		}
	}

	// Now load the vertical pattern.  Release old data if needed.

	if (source->hasVpat && !source->hasMpat) {

		if (source->vpat) {
			mem_free(source->vpat);
			source->vpat = NULL;
		}

		snprintf(query, MAX_QUERY, "SELECT depression_angle, relative_field FROM %s_%d.source_vertical_pattern WHERE source_key = %d ORDER BY depression_angle;", DbName, StudyKey, source->sourceKey);
		if (mysql_query(MyConnection, query)) {
			log_db_error("Vertical pattern query failed (1) for sourceKey=%d", source->sourceKey);
			return -1;
		}

		myResult = mysql_store_result(MyConnection);
		if (!myResult) {
			log_db_error("Vertical pattern query failed (2) for sourceKey=%d", source->sourceKey);
			return -1;
		}

		// Load the pattern data.  No interpolation here, the data is stored directly and interpolation is done
		// on-the-fly, also note the peak value depression angle for "mirroring" lookups beyond the data range.

		n_pts = (int)mysql_num_rows(myResult);
		if (n_pts < 2) {
			mysql_free_result(myResult);
			log_error("Missing vertical pattern data for sourceKey=%d", source->sourceKey);
			return 1;
		}

		// Note special memory allocation procedure here, only a single block is allocated so the caching code can read
		// and write the data in a single operation.  Of course pointers have to be updated after a cache read.

		siz = sizeof(VPAT) + (n_pts * 2 * sizeof(double));
		vpt = (VPAT *)mem_zalloc(siz);
		vpt->siz = siz;
		vpt->np = n_pts;
		dep = (double *)(vpt + 1);
		vpt->dep = dep;
		pat = dep + n_pts;
		vpt->pat = pat;

		rfmax = -999.;
		dpmax = 0.;

		for (i = 0; i < n_pts; i++) {

			fields = mysql_fetch_row(myResult);
			if (NULL == fields) {
				mysql_free_result(myResult);
				mem_free(vpt);
				log_db_error("Vertical pattern query failed (3) for sourceKey=%d", source->sourceKey);
				return -1;
			}

			dep[i] = atof(fields[0]);
			pat[i] = 20. * log10(atof(fields[1]));
			if (pat[i] > rfmax) {
				rfmax = pat[i];
				dpmax = dep[i];
			}
		}

		mysql_free_result(myResult);

		vpt->mxdep = dpmax;
		source->vpat = vpt;
	}

	// Last, load a matrix pattern.

	if (source->hasMpat) {

		if (source->mpat) {
			mem_free(source->mpat);
			source->mpat = NULL;
		}

		// Two passes are needed here, on the first get the azimuths and the individual slice pattern point counts.

		snprintf(query, MAX_QUERY, "SELECT azimuth, COUNT(*) FROM %s_%d.source_matrix_pattern WHERE source_key = %d GROUP BY azimuth ORDER BY azimuth;", DbName, StudyKey, source->sourceKey);
		if (mysql_query(MyConnection, query)) {
			log_db_error("Matrix pattern query failed (1) for sourceKey=%d", source->sourceKey);
			return -1;
		}

		myResult = mysql_store_result(MyConnection);
		if (!myResult) {
			log_db_error("Matrix pattern query failed (2) for sourceKey=%d", source->sourceKey);
			return -1;
		}

		n_pts = (int)mysql_num_rows(myResult);
		if (n_pts < 2) {
			mysql_free_result(myResult);
			log_error("Missing matrix pattern data (1) for sourceKey=%d", source->sourceKey);
			return 1;
		}

		if (n_pts > max_pts) {
			max_pts = n_pts + 20;
			azbuf = (double *)mem_realloc(azbuf, (max_pts * sizeof(double)));
			rfbuf = (double *)mem_realloc(rfbuf, (max_pts * sizeof(double)));
			npbuf = (int *)mem_realloc(npbuf, (max_pts * sizeof(int)));
		}

		n_pts_tot = 0;

		for (i = 0; i < n_pts; i++) {

			fields = mysql_fetch_row(myResult);
			if (NULL == fields) {
				mysql_free_result(myResult);
				log_db_error("Matrix pattern query failed (3) for sourceKey=%d", source->sourceKey);
				return -1;
			}

			azbuf[i] = atof(fields[0]);
			npbuf[i] = atoi(fields[1]);
			if (npbuf[i] < 2) {
				mysql_free_result(myResult);
				log_error("Missing matrix pattern data (2) for sourceKey=%d", source->sourceKey);
				return 1;
			}

			n_pts_tot += npbuf[i];
		}

		mysql_free_result(myResult);

		// As with a vertical pattern only one block of memory is allocated.

		siz = sizeof(MPAT) + (n_pts * (sizeof(double) + sizeof(int) + (2 * sizeof(double *)))) +
			(n_pts_tot * 2 * sizeof(double));
		mpt = (MPAT *)mem_zalloc(siz);
		mpt->siz = siz;
		mpt->ns = n_pts;
		mpt->value = (double *)(mpt + 1);
		mpt->np = (int *)(mpt->value + n_pts);
		mpt->angle = (double **)(mpt->np + n_pts);
		mpt->pat = mpt->angle + n_pts;
		double *ptr = (double *)(mpt->pat + n_pts);
		for (i = 0; i < n_pts; i++) {
			mpt->value[i] = azbuf[i];
			mpt->np[i] = npbuf[i];
			mpt->angle[i] = ptr;
			ptr += mpt->np[i];
			mpt->pat[i] = ptr;
			ptr += mpt->np[i];
		}

		// Now get the actual data.

		snprintf(query, MAX_QUERY, "SELECT depression_angle, relative_field FROM %s_%d.source_matrix_pattern WHERE source_key = %d ORDER BY azimuth, depression_angle;", DbName, StudyKey, source->sourceKey);
		if (mysql_query(MyConnection, query)) {
			mem_free(mpt);
			log_db_error("Matrix pattern query failed (4) for sourceKey=%d", source->sourceKey);
			return -1;
		}

		myResult = mysql_store_result(MyConnection);
		if (!myResult) {
			mem_free(mpt);
			log_db_error("Matrix pattern query failed (5) for sourceKey=%d", source->sourceKey);
			return -1;
		}

		for (i = 0; i < n_pts; i++) {

			dep = mpt->angle[i];
			pat = mpt->pat[i];

			for (j = 0; j < mpt->np[i]; j++) {

				fields = mysql_fetch_row(myResult);
				if (NULL == fields) {
					mysql_free_result(myResult);
					mem_free(mpt);
					log_db_error("Matrix pattern query failed (6) for sourceKey=%d", source->sourceKey);
					return -1;
				}

				dep[j] = atof(fields[0]);
				pat[j] = 20. * log10(atof(fields[1]));
			}
		}

		mysql_free_result(myResult);

		source->mpat = mpt;
	}

	// Done.

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Look up an ERP value at a specified azimuth for a record.  This returns the max ERP, modified by a horizontal
// pattern value if the record has a directional horizontal pattern.

// Arguments:

//   source  Source.
//   azm     Lookup azimuth, degrees true.

// Return is absolute ERP in dBk.

double erp_lookup(SOURCE *source, double azm) {

	int i0, i1;
	double az0, erp, *pat;

	if (source->hpat) {

		pat = source->hpat;

		azm = fmod((azm - source->hpatOrientation), 360.);
		if (azm < 0.) azm += 360.;

		i0 = azm;
		az0 = i0;
		i1 = i0 + 1;
		if (i1 == 360) {
			i1 = 0;
		}

		erp = source->peakERP + (pat[i0] + ((azm - az0) * (pat[i1] - pat[i0])));

	} else {

		erp = source->peakERP;
	}

	return(erp);
}


//---------------------------------------------------------------------------------------------------------------------
// Look up an ERP value for contour projection, this can differ significantly from ERP used for other purposes.  If
// the record is a replication this will use the contourERP value instead of the peakERP value.  Also this will use the
// derived h-plane pattern if one exists in the record (note a replication will never have an h-plane pattern).

// Arguments:

//   source  Source.
//   azm     Lookup azimuth, degrees true.

// Return is absolute ERP in dBk.

double contour_erp_lookup(SOURCE *source, double azm) {

	int i0, i1;
	double az0, erp, *pat, ornt;

	if (source->conthpat) {

		erp = source->peakERP;
		pat = source->conthpat;
		ornt = 0.;

	} else {

		if (source->origSourceKey) {
			erp = source->contourERP;
		} else {
			erp = source->peakERP;
		}
		pat = source->hpat;
		ornt = source->hpatOrientation;
	}

	if (pat) {

		azm = fmod((azm - ornt), 360.);
		if (azm < 0.) azm += 360.;

		i0 = azm;
		az0 = i0;
		i1 = i0 + 1;
		if (i1 == 360) {
			i1 = 0;
		}

		erp += pat[i0] + ((azm - az0) * (pat[i1] - pat[i0]));
	}

	return(erp);
}


//---------------------------------------------------------------------------------------------------------------------
// Compute antenna vertical pattern gain.  Two different methods are available for computing the lookup depression
// angle.  One method uses the effective height (HAAT in FCC curve contexts, HAGL for other) and distance values to
// derive a pseudo-depression angle, the other computes the true depression angle using actual heights at both ends of
// the path.  The pattern lookup may be performed on a generic pattern selected per DTV/NTSC and band, or on real
// pattern data, either a conventional single vertical pattern envelope, or a "matrix" pattern which is a set of
// vertical slices at a tabulated range of azimuths.  Normally that choice is made based on the source itself, if a
// vertical or matrix pattern is available it is used, however conditions may override that behavior and force use of
// a generic pattern regardless.  The depression angle method, use of generic patterns, and various other behaviors
// will be determined based on the mode argument, see below.  Electrical and mechanical beam tilt may also be applied,
// according to global parameter settings.

// Arguments:

//   source    Source.
//   hgt       Effective height of transmitter, HAAT or HAGL, in meters.
//   azm       Path bearing in degrees true.
//   dist      Path distance in kilometers.
//   relev     Receiver point ground elevation AMSL in meters (not used if effective height method selected).
//   rhgt      Receiver height above ground, meters.
//   mode      VPAT_MODE_*, changes logic for various operations.
//   depAngle  If non-NULL return depression angle and lookup angle (after tilt and mirroring), for debugging.
//   lupAngle

// Return is relative gain in dB.

double vpat_lookup(SOURCE *source, double hgt, double azm, double dist, double relev, double rhgt, int mode,
	double *depAngle, double *lupAngle) {

	// Generic antenna vertical patterns for combinations of DTV/NTSC and channel band.  In the input data for the FCC
	// program these were tabulated at irregular depression points, with linear interpolation used between; but for
	// performance reasons these have been pre-interpolated to 0.25 degree spacings, up to the 10-degree maximum from
	// the original FCC data, beyond that the last point is used.

	static double xpat_vlo_ntsc[41] = {
		  0.00,   0.00,   0.00,   0.00,   0.00,   0.00,   0.00,  -0.04,  -0.09,
		 -0.13,  -0.18,  -0.22,  -0.26,  -0.35,  -0.45,  -0.54,  -0.63,  -0.75,
		 -0.87,  -0.99,  -1.11,  -1.26,  -1.41,  -1.57,  -1.72,  -1.94,  -2.16,
		 -2.38,  -2.62,  -2.92,  -3.24,  -3.57,  -3.92,  -4.15,  -4.39,  -4.63,
		 -4.88,  -5.23,  -5.60,  -5.98,  -6.38
	};
	static double xpat_vhi_ntsc[41] = {
		  0.00,   0.00,   0.00,   0.00,  -0.15,  -0.29,  -0.45,  -0.87,  -1.31,
		 -1.99,  -2.73,  -3.54,  -4.44,  -5.43,  -6.56,  -7.54,  -8.64,  -8.64,
		 -8.64,  -8.64,  -8.64,  -8.64,  -8.64,  -8.64,  -8.64,  -8.64,  -8.64,
		 -8.64,  -8.64,  -9.00,  -9.37,  -9.76, -10.17, -10.83, -11.54, -12.31,
		-13.15, -13.66, -14.20, -14.77, -15.39
	};
	static double xpat_uhf_ntsc[41] = {
		  0.00,   0.00,   0.00,   0.00,  -0.79,  -1.65,  -2.62,  -4.01,  -5.68,
		 -7.43,  -9.63, -11.21, -13.15, -14.20, -15.39, -15.92, -16.48, -16.77,
		-17.08, -17.39, -17.72, -18.06, -18.42, -18.79, -19.17, -19.17, -19.17,
		-19.17, -19.17, -19.17, -19.17, -19.17, -19.17, -19.17, -19.17, -19.17,
		-19.17, -19.17, -19.17, -19.17, -19.17
	};
	static double xpat_vlo_dtv[41] = {
		  0.00,   0.00,   0.00,   0.00,   0.00,   0.00,   0.00,  -0.04,  -0.09,
		 -0.13,  -0.18,  -0.22,  -0.26,  -0.35,  -0.45,  -0.54,  -0.63,  -0.75,
		 -0.87,  -0.99,  -1.11,  -1.26,  -1.41,  -1.57,  -1.72,  -1.94,  -2.16,
		 -2.38,  -2.62,  -2.92,  -3.24,  -3.57,  -3.92,  -4.15,  -4.39,  -4.63,
		 -4.88,  -5.23,  -5.60,  -5.98,  -6.38
	};
	static double xpat_vhi_dtv[41] = {
		  0.00,   0.00,   0.00,   0.00,  -0.09,  -0.18,  -0.26,  -0.40,  -0.54,
		 -0.77,  -1.01,  -1.36,  -1.72,  -2.21,  -2.73,  -3.22,  -3.74,  -4.36,
		 -5.04,  -5.76,  -6.56,  -7.23,  -7.96,  -8.75,  -9.63,  -9.97, -10.31,
		-10.68, -11.06, -11.06, -11.06, -11.06, -11.06, -11.06, -11.06, -11.06,
		-11.06, -11.29, -11.54, -11.78, -12.04
	};
	static double xpat_uhf_dtv[41] = {
		  0.00,   0.00,   0.00,   0.00,  -0.35,  -0.72,  -1.11,  -2.10,  -3.22,
		 -4.81,  -6.74,  -8.87, -11.70, -12.13, -12.58, -13.05, -13.56, -13.66,
		-13.76, -13.87, -13.98, -14.54, -15.14, -15.78, -16.48, -16.48, -16.48,
		-16.48, -16.48, -16.48, -16.48, -16.48, -16.48, -16.48, -16.48, -16.48,
		-16.48, -16.48, -16.48, -16.48, -16.48
	};

	// Derivations when parameters change.  Effective earth radius is computed from the spherical earth radius
	// equivalent to the surface distance value in kilometers/degree and the atmospheric refractivity in N-units.

	static int initForStudyKey = 0;
	static double earthRadius = 0.;

	if (StudyKey != initForStudyKey) {

		earthRadius = (1. / (1. - ((0.04663 * exp(0.005577 * Params.AtmosphericRefractivity)) /
			(1. + (Params.AtmosphericRefractivity * 1.e-6))))) * (Params.KilometersPerDegree * 360.) / TWO_PI;

		initForStudyKey = StudyKey;
	}

	// Zero return values as needed.

	if (depAngle) {
		*depAngle = 0.;
	}
	if (lupAngle) {
		*lupAngle = 0.;
	}

	// Set conditions based on mode.  In contour mode, first is a test to determine if elevation pattern should be
	// applied at all, if not immediately return 0.  If the source has a derived pattern the answer is always no as
	// the pattern adjustment is inherently part of that azimuth pattern.  Otherwise a parameter may determine if
	// elevation pattern should be applied for contour projection, with the options "always", "full-service only",
	// and "never".  If the pattern is to be applied in contour mode, the depression angle method is always effective-
	// height, a parameter determines if real patterns can be used or generic is forced, for generic patterns to be
	// mirrored the parameter must be "always", and mechanical tilt mode is determined by parameter.  When deriving a
	// pattern, the depression angle may be fixed at 0 in horizontal-plane mode, or at the radio horizon in that mode.
	// When deriving the source always has a real pattern that must be used, generic mirroring is also irrelevant, and
	// mechanical tilt is always applied.  Wireless sources are assumed to never be seen in contour mode or derivation
	// mode.  In propagation mode, a parameter sets the depression angle method based on wireless or other, real
	// patterns are allowed, generic patterns are mirrored if the parameter is "always" or "propagation only", and
	// mechanical tilt is by parameter based on wireless or other.

	int countryIndex = source->countryKey - 1;

#define DEP_EFFHGT  1
#define DEP_TRUE    2
#define DEP_HPLANE  3
#define DEP_RADHORZ 4

	int depMethod, forceGeneric, genMirror, mechTiltMode;

	switch (mode) {

		case VPAT_MODE_CONTOUR: {

			if (source->conthpat || (CONTOUR_VPAT_NEVER == Params.ContourVpatUse[countryIndex]) ||
					((CONTOUR_VPAT_TVFULL == Params.ContourVpatUse[countryIndex]) && (SERV_TV != source->service))) {
				return 0.;
			}

			depMethod = DEP_EFFHGT;
			forceGeneric = !Params.ContoursUseRealVpat[countryIndex];
			genMirror = (VPAT_GEN_MIRROR_ALWAYS == Params.GenericVpatMirroring[countryIndex]);
			mechTiltMode = Params.VpatMechTilt[countryIndex];

			break;
		}

		case VPAT_MODE_DERIVE: {

			if (DERIVE_HPAT_RADHORZ == Params.ContourDeriveHpat[countryIndex]) {
				depMethod = DEP_RADHORZ;
			} else {
				depMethod = DEP_HPLANE;
			}
			forceGeneric = 0;
			genMirror = 0;
			mechTiltMode = VPAT_MTILT_ALWAYS;

			break;
		}

		case VPAT_MODE_PROP:
		default: {

			if (DEPANGLE_METH_TRUE == ((RECORD_TYPE_WL == source->recordType) ? Params.WirelessDepressionAngleMethod :
					Params.DepressionAngleMethod[countryIndex])) {
				depMethod = DEP_TRUE;
			} else {
				depMethod = DEP_EFFHGT;
			}
			forceGeneric = 0;
			int p = (RECORD_TYPE_WL == source->recordType) ? Params.WirelessGenericVpatMirroring :
				Params.GenericVpatMirroring[countryIndex];
			genMirror = ((VPAT_GEN_MIRROR_ALWAYS == p) || (VPAT_GEN_MIRROR_PROPONLY == p));
			mechTiltMode = ((RECORD_TYPE_WL == source->recordType) ? Params.WirelessVpatMechTilt :
				Params.VpatMechTilt[countryIndex]);

			break;
		}
	}

	// If a generic pattern would be used (the source does not have a real pattern, or the flag is set forcing generic
	// patterns) but generic pattern use is disabled for this source, there is no pattern.

	int generic = (forceGeneric || (!source->vpat && !source->mpat));

	if (generic && !source->useGeneric) {
		return 0.;
	}

	int i;
	double elv, patval;

	// Calculate depression angle, if distance is very small angle is straight down, except in derive modes.

	switch (depMethod) {

		// Calculate by effective-height method.  Adjust the antenna height by the receive height and a correction
		// for 4/3 earth radius, that is 1/2 of the square of the distance in miles; the constant 0.0588391 combines
		// multiple unit conversions.

		case DEP_EFFHGT:
		default: {
			if (dist <= TINY) {
				elv = 90.;
			} else {
				double ehgt = hgt - rhgt + ((dist * dist) * 0.0588391);
				if (ehgt < 0.) {
					ehgt = 0.;
				}
				elv = atan(ehgt / (dist * 1000.)) * RADIANS_TO_DEGREES;
			}
			break;
		}

		// Calculate true depression angle.

		case DEP_TRUE: {
			if (dist <= TINY) {
				elv = 90.;
			} else {
				double txht = earthRadius + (source->actualHeightAMSL / 1000.);
				double rxht = earthRadius + ((relev + rhgt) / 1000.);
				double darc = dist / earthRadius;
				elv = atan((txht - (rxht * cos(darc))) / (rxht * sin(darc))) * RADIANS_TO_DEGREES;
			}
			break;
		}

		// Horizontal plane.

		case DEP_HPLANE: {
			elv = 0.;
			break;
		}

		// Radio horizon.

		case DEP_RADHORZ: {
			if (hgt < 0.) {
				elv = -0.0277 * sqrt(-hgt);
			} else {
				elv = 0.0277 * sqrt(hgt);
			}
			break;
		}
	}

	if (depAngle) {
		*depAngle = elv;
	}

	// Extract tilt values in case they are needed below.  Depending on a global option, this may use the values as
	// specified, or take the absolute value, meaning, assume negative tilts are errors and should be positive.  A
	// global option can also disable all use of mechanical tilt, or restrict that to only non-generic patterns.

	double etilt = 0., mtilt = 0.;

	if (Params.AbsoluteValueTilts[countryIndex]) {
		etilt = fabs(source->vpatElectricalTilt);
		if ((VPAT_MTILT_ALWAYS == mechTiltMode) || ((VPAT_MTILT_REAL_ONLY == mechTiltMode) && !generic)) {
			mtilt = fabs(source->vpatMechanicalTilt);
		}
	} else {
		etilt = source->vpatElectricalTilt;
		if ((VPAT_MTILT_ALWAYS == mechTiltMode) || ((VPAT_MTILT_REAL_ONLY == mechTiltMode) && !generic)) {
			mtilt = source->vpatMechanicalTilt;
		}
	}

	// If there is no vertical or matrix pattern, use generic.  An argument flag may also force generic.  FM uses the
	// same pattern as analog TV VHF low band, wireless the same as digital TV UHF.

	if (generic) {

		double *pat = NULL;

		switch (source->band) {
			case BAND_VLO1:
			case BAND_VLO2: {
				if (source->dtv) {
					pat = xpat_vlo_dtv;
				} else {
					pat = xpat_vlo_ntsc;
				}
				break;
			}
			case BAND_VHI: {
				if (source->dtv) {
					pat = xpat_vhi_dtv;
				} else {
					pat = xpat_vhi_ntsc;
				}
				break;
			}
			case BAND_UHF:
			default: {
				if (source->dtv) {
					pat = xpat_uhf_dtv;
				} else {
					pat = xpat_uhf_ntsc;
				}
				break;
			}
			case BAND_WL: {
				pat = xpat_uhf_dtv;
				break;
			}
			case BAND_FMED:
			case BAND_FM: {
				pat = xpat_vlo_ntsc;
				break;
			}
		}

		// Do the lookup.  At depression angles beyond the last lookup value in the table, use the last value.  At
		// angles above the peak (0.75 degrees for these patterns) the normal behavior is to just use the first value,
		// but an option can enable mirroring around the peak.  Beam tilt may be applied here in full, or with
		// electrical offset by 0.75 degrees, but only if one of the tilts is non-zero.

		if (VPAT_GEN_TILT_NONE != Params.GenericVpatTilt[countryIndex]) {
			if ((VPAT_GEN_TILT_OFFSET == Params.GenericVpatTilt[countryIndex]) && ((etilt != 0.) || (mtilt != 0.))) {
				etilt -= 0.75;
			}
			if (etilt != 0.) {
				elv -= etilt;
			}
			if (mtilt != 0.) {
				elv -= mtilt * cos((azm - source->vpatTiltOrientation) * DEGREES_TO_RADIANS);
			}
		}

		if (genMirror) {
			if (elv < 0.75) {
				elv = 1.5 - elv;
			}
		} else {
			if (elv < 0.) {
				elv = 0.;
			}
		}

		if (lupAngle) {
			*lupAngle = elv;
		}

		elv *= 4.;
		int i0 = elv;
		if (i0 >= 40) {
			patval = pat[40];
		} else {
			patval = pat[i0] + ((elv - (double)i0) * (pat[i0 + 1] - pat[i0]));
		}

		// For LPTV sources the pattern values are doubled in field, but the result cannot be greater than 1.  A study
		// parameter may disable this, or expand it to TV Class A stations as well.

		if (((VPAT_GEN_DOUBLE_LPTV == Params.GenericVpatDoubling[countryIndex]) && (SERV_TVLP == source->service)) ||
				((VPAT_GEN_DOUBLE_LPTV_A == Params.GenericVpatDoubling[countryIndex]) &&
					((SERV_TVLP == source->service) || (SERV_TVCA == source->service)))) {
			patval += 6.0206;
			if (patval > 0.) {
				patval = 0.;
			}
		}

	// Look up in real vertical pattern.  First correct for electrical and optionally mechanical beam tilt.  Electrical
	// tilt is applied only if the pattern tabulation does not have any inherent tilt; if the tabulation is already
	// tilted assume that is correct for electrical, and apply only mechanical tilt.

	} else {

		if (source->vpat && !source->mpat) {

			VPAT *vpt = source->vpat;
			double *dep = vpt->dep;
			double *pat = vpt->pat;
			int np1 = vpt->np - 1;

			if ((etilt != 0.) && (0. == vpt->mxdep)) {
				elv -= etilt;
			}
			if (mtilt != 0.) {
				elv -= mtilt * cos((azm - source->vpatTiltOrientation) * DEGREES_TO_RADIANS);
			}

			// If the lookup depression angle is beyond the first point, "mirror" the pattern by moving the lookup to
			// the other side of the maximum point, which was located during pattern loading, see load_patterns().

			if (elv < dep[0]) {
				elv = vpt->mxdep + (vpt->mxdep - elv);
			}
			if (elv > 90.) {
				elv = 90.;
			}

			if (lupAngle) {
				*lupAngle = elv;
			}

			// Do the lookup.  If beyond the last point, just use the last point.

			if (elv >= dep[np1]) {
				patval = pat[np1];
			} else {
				for (i = 1; i < np1; i++) {
					if (elv < dep[i]) {
						break;
					}
				}
				int i0 = i - 1;
				patval = pat[i0] + (((elv - dep[i0]) / (dep[i] - dep[i0])) * (pat[i] - pat[i0]));
			}

		// Look up in a matrix pattern.  Tilt never applies to this lookup.  First find the two slices bracketing the
		// lookup azimuth.

		} else {

			int i0, i1, j, j0;
			double az0, az1, patval0, patval1;

			MPAT *mpt = source->mpat;
			double *az = mpt->value;
			int *np = mpt->np;

			for (i = 0; i < mpt->ns; i++) {
				if (azm < az[i]) {
					break;
				}
			}
			if (i > 0) {
				i0 = i - 1;
				az0 = az[i0];
			} else {
				i0 = mpt->ns - 1;
				az0 = az[i0] - 360.;
			}
			if (i < mpt->ns) {
				i1 = i;
				az1 = az[i1];
			} else {
				i1 = 0;
				az1 = az[i1] + 360.;
			}

			// Look up values in the two slices.  No mirroring here, if beyond either end of the tabulation use the
			// first/last point.

			if (lupAngle) {
				*lupAngle = elv;
			}

			double *dep = mpt->angle[i0];
			double *pat = mpt->pat[i0];
			int np1 = np[i0] - 1;
			if (elv <= dep[0]) {
				patval0 = pat[0];
			} else {
				if (elv >= dep[np1]) {
					patval0 = pat[np1];
				} else {
					for (j = 1; j < np1; j++) {
						if (elv < dep[j]) {
							break;
						}
					}
					j0 = j - 1;
					patval0 = pat[j0] + (((elv - dep[j0]) / (dep[j] - dep[j0])) * (pat[j] - pat[j0]));
				}
			}

			dep = mpt->angle[i1];
			pat = mpt->pat[i1];
			np1 = np[i1] - 1;
			if (elv <= dep[0]) {
				patval1 = pat[0];
			} else {
				if (elv >= dep[np1]) {
					patval1 = pat[np1];
				} else {
					for (j = 1; j < np1; j++) {
						if (elv < dep[j]) {
							break;
						}
					}
					j0 = j - 1;
					patval1 = pat[j0] + (((elv - dep[j0]) / (dep[j] - dep[j0])) * (pat[j] - pat[j0]));
				}
			}

			// Finally, interpolate to the azimuth.

			patval = patval0 + (((azm - az0) / (az1 - az0)) * (patval1 - patval0));
		}
	}

	// Done.

	return(patval);
}


//---------------------------------------------------------------------------------------------------------------------
// Load a custom receive antenna by key.  These are azimuth patterns that may vary by frequency.  The matrix pattern
// structure is used to store the pattern, interpolation is always done at time of lookup, see recv_az_lookup().  As
// with other pattern data, values in the database are relative field but values stored in memory are relative dB.
// The antenna also has a gain value, that does not affect the pattern but will affect terrain-sensitive coverage
// determination, see study.c.  The antennas are cached here.

// Arguments:

//   antennaKey  The receive antenna key.

// Return the RECEIVE_ANT structure, NULL on error or not found.

RECEIVE_ANT *get_receive_antenna(int antennaKey) {

	if (!StudyKey) {
		log_error("get_receive_antenna() called with no study open");
		return NULL;
	}

	static RECEIVE_ANT *cacheHead = NULL;
	static int initForStudyKey = 0;

	RECEIVE_ANT *rant;

	if (StudyKey != initForStudyKey) {

		 while (cacheHead) {
			rant = cacheHead;
			cacheHead = rant->next;
			rant->next = NULL;
			mem_free(rant->rpat);
			rant->rpat = NULL;
			mem_free(rant);
		}

		initForStudyKey = StudyKey;
	}

	for (rant = cacheHead; rant; rant = rant->next) {
		if (antennaKey == rant->antennaKey) {
			return rant;
		}
	}

	// Query for antenna name and gain.

	char query[MAX_QUERY];
	MYSQL_RES *myResult;
	MYSQL_ROW fields;

	snprintf(query, MAX_QUERY, "SELECT name, gain FROM %s.receive_antenna_index WHERE antenna_key = %d;", DbName,
		antennaKey);
	if (mysql_query(MyConnection, query)) {
		log_db_error("Receive antenna query failed (1)");
		return NULL;
	}

	myResult = mysql_store_result(MyConnection);
	if (!myResult) {
		log_db_error("Receive antenna query failed (2)");
		return NULL;
	}

	if (!mysql_num_rows(myResult)) {
		mysql_free_result(myResult);
		log_error("Receive antenna not found for antennaKey=%d", antennaKey);
		return NULL;
	}

	fields = mysql_fetch_row(myResult);
	if (!fields) {
		mysql_free_result(myResult);
		log_db_error("Receive antenna query failed (3)");
		return NULL;
	}

	rant = (RECEIVE_ANT *)mem_zalloc(sizeof(RECEIVE_ANT));

	lcpystr(rant->name, fields[0], MAX_STRING);
	rant->gain = atof(fields[1]);

	mysql_free_result(myResult);

	// Read the pattern data entirely into a buffer then do a count-allocate-copy process from buffer contents.

	static int max_pts = 0;
	static double *fqbuf = NULL, *azbuf = NULL, *rfbuf = NULL;

	MPAT *mpt;
	double lastfq, *az, *pat;
	size_t siz;
	int n_pts_tot, n_slice, i, j, k;

	snprintf(query, MAX_QUERY, "SELECT frequency, azimuth, relative_field FROM %s.receive_pattern WHERE antenna_key = %d ORDER BY frequency, azimuth;", DbName, antennaKey);
	if (mysql_query(MyConnection, query)) {
		log_db_error("Receive pattern query failed (1) for antennaKey=%d", antennaKey);
		mem_free(rant);
		return NULL;
	}

	myResult = mysql_store_result(MyConnection);
	if (!myResult) {
		log_db_error("Receive pattern query failed (2) for antennaKey=%d", antennaKey);
		mem_free(rant);
		return NULL;
	}

	// It is valid to have only one slice, in which case this is a single azimuth pattern for all frequencies, meaning
	// the total point count could be as small as 2 and still be valid (that might rarely be useful as it is the only
	// way to get an omnidirectional receive pattern; a pattern with two points both relative field 1.0).

	n_pts_tot = (int)mysql_num_rows(myResult);
	if (n_pts_tot < 2) {
		mysql_free_result(myResult);
		log_error("Missing receive pattern data (1) for antennaKey=%d", antennaKey);
		mem_free(rant);
		return NULL;
	}

	if (n_pts_tot > max_pts) {
		max_pts = n_pts_tot + 100;
		fqbuf = (double *)mem_realloc(fqbuf, (max_pts * sizeof(double)));
		azbuf = (double *)mem_realloc(azbuf, (max_pts * sizeof(double)));
		rfbuf = (double *)mem_realloc(rfbuf, (max_pts * sizeof(double)));
	}

	n_slice = 0;
	lastfq = 0.;

	for (i = 0; i < n_pts_tot; i++) {

		fields = mysql_fetch_row(myResult);
		if (NULL == fields) {
			mysql_free_result(myResult);
			log_db_error("Receive pattern query failed (3) for antennaKey=%d", antennaKey);
			mem_free(rant);
			return NULL;
		}

		fqbuf[i] = atof(fields[0]);
		azbuf[i] = atof(fields[1]);
		rfbuf[i] = atof(fields[2]);

		if (0 == n_slice) {
			n_slice = 1;
			lastfq = fqbuf[i];
		} else {
			if (fqbuf[i] != lastfq) {
				n_slice++;
				lastfq = fqbuf[i];
			}
		}
	}

	mysql_free_result(myResult);

	// Although these are never written to external cache files, the same one-block allocation is used as for an
	// elevation matrix pattern, see load_patterns() for details.

	siz = sizeof(MPAT) + (n_slice * (sizeof(double) + sizeof(int) + (2 * sizeof(double *)))) +
		(n_pts_tot * 2 * sizeof(double));
	mpt = (MPAT *)mem_zalloc(siz);
	rant->rpat = mpt;
	mpt->siz = siz;
	mpt->ns = n_slice;
	mpt->value = (double *)(mpt + 1);
	mpt->np = (int *)(mpt->value + n_slice);
	mpt->angle = (double **)(mpt->np + n_slice);
	mpt->pat = mpt->angle + n_slice;

	// Count the slice sizes.

	j = 0;
	mpt->np[0] = 0;
	lastfq = fqbuf[0];
	for (i = 0; i < n_pts_tot; i++) {
		if (fqbuf[i] != lastfq) {
			j++;
			mpt->np[j] = 0;
			lastfq = fqbuf[i];
		} else {
			mpt->np[j]++;
		}
	}

	// Copy the data.

	double *ptr = (double *)(mpt->pat + n_slice);
	i = 0;

	for (j = 0; j < n_slice; j++) {

		az = ptr;
		mpt->angle[j] = ptr;
		ptr += mpt->np[j];
		pat = ptr;
		mpt->pat[j] = ptr;
		ptr += mpt->np[j];

		for (k = 0; k < mpt->np[j]; k++) {
			az[k] = azbuf[i];
			pat[k] = 20. * log10(rfbuf[i]);
			i++;
		}
	}

	// Done.

	rant->next = cacheHead;
	cacheHead = rant;

	return rant;
}


//---------------------------------------------------------------------------------------------------------------------
// Determine receive antenna horizontal pattern factor based on azimuth off-beam.  This may use the OET69 models for
// receive antennas, or a custom receive pattern.  The OET69 patterns vary with channel band and DTV/NTSC, custom
// patterns can vary with frequency as well but are tabulated as a matrix of field versus azimuth and frequency.

// The OET69 patterns are based on a simple cos^4 formula, but for performance they are pre-computed and cached.  A
// single varying parameter determines the overall pattern shape, that is the on-axis gain, aka the front/back ratio.

// Arguments:

//   source  Desired source.
//   rpat    Custom receive pattern, use OET69 patterns if NULL.
//   rot     Pointing angle of antenna (may be bearing to desired source, or a fixed bearing).
//   ang     Pointing angle to lookup (bearing to undesired source).
//   freq    Lookup frequency (may not be same as the desired source).

// Return is relative gain in dB.

#define N_RECV_PAT  7

double recv_az_lookup(SOURCE *source, MPAT *rpat, double rot, double ang, double freq) {

	if (!StudyKey) {
		log_error("recv_az_lookup() called with no study open");
		return 1;
	}

	int i0, i1;
	double *raz_pat, fbr, azm, az0, az1, pat;

	static int initForStudyKey = 0;
	static double **raz_pats = NULL;

	// Generate patterns when parameters change.

	if (StudyKey != initForStudyKey) {

		int pi, i;

		if (!raz_pats) {
			raz_pats = (double **)mem_alloc(N_RECV_PAT * sizeof(double *));
			for (pi = 0; pi < N_RECV_PAT; pi++) {
				raz_pats[pi] = (double *)malloc(3600 * sizeof(double));
			}
		}

		for (pi = 0; pi < N_RECV_PAT; pi++) {

			switch (pi) {
				case 0: {
					fbr = -Params.ReceivePatVloDigital;
					break;
				}
				case 1: {
					fbr = -Params.ReceivePatVhiDigital;
					break;
				}
				case 2:
				default: {
					fbr = -Params.ReceivePatUhfDigital;
					break;
				}
				case 3: {
					fbr = -Params.ReceivePatVloAnalog;
					break;
				}
				case 4: {
					fbr = -Params.ReceivePatVhiAnalog;
					break;
				}
				case 5: {
					fbr = -Params.ReceivePatUhfAnalog;
					break;
				}
				case 6: {
					fbr = -Params.ReceivePatFM;
					break;
				}
			}

			raz_pat = raz_pats[pi];

			for (i = 0; i < 3600; i++) {
				if ((i < 900) || (i > 2700)) {
					azm = (double)i / 10.;
					pat = 80. * log10(cos(azm * DEGREES_TO_RADIANS));
					raz_pat[i] = (fbr > pat) ? fbr : pat;
				} else {
					raz_pat[i] = fbr;
				}
			}
		}

		initForStudyKey = StudyKey;
	}

	// Compute lookup azimuth.

	azm = ang - rot;
	while (azm < 0.) {
		azm += 360.;
	}
	while (azm >= 360.) {
		azm -= 360.;
	}

	// If using OET69 patterns, select the proper pattern based on band and DTV/NTSC.

	if (NULL == rpat) {

		switch (source->band) {
			case BAND_VLO1:
			case BAND_VLO2: {
				if (source->dtv) {
					raz_pat = raz_pats[0];
				} else {
					raz_pat = raz_pats[3];
				}
				break;
			}
			case BAND_VHI: {
				if (source->dtv) {
					raz_pat = raz_pats[1];
				} else {
					raz_pat = raz_pats[4];
				}
				break;
			}
			case BAND_UHF:
			default: {
				if (source->dtv) {
					raz_pat = raz_pats[2];
				} else {
					raz_pat = raz_pats[5];
				}
				break;
			}
			case BAND_FMED:
			case BAND_FM: {
				raz_pat = raz_pats[6];
				break;
			}
		}

		// Do the lookup.

		i0 = azm * 10.;
		az0 = (double)i0 / 10.;
		i1 = i0 + 1;
		if (i1 == 3600) {
			i1 = 0;
		}

		pat = raz_pat[i0] + (((azm - az0) * 10.) * (raz_pat[i1] - raz_pat[i0]));

	// Look up in receive pattern.  If outside the frequency range use the lowest/highest pattern, else interpolate.

	} else {

		int i, j, j0, j1;
		double fq0, fq1, pat0, pat1;

		double *fq = rpat->value;
		int *np = rpat->np;

		if (1 == rpat->ns) {
			i0 = 0;
			i1 = 0;
		} else {
			for (i = 0; i < rpat->ns; i++) {
				if (freq < fq[i]) {
					break;
				}
			}
			if (i > 0) {
				i0 = i - 1;
			} else {
				i0 = 0;
			}
			fq0 = fq[i0];
			if (i < rpat->ns) {
				i1 = i;
			} else {
				i1 = rpat->ns - 1;
			}
			fq1 = fq[i1];
		}

		double *az = rpat->angle[i0];
		double *rf = rpat->pat[i0];
		for (j = 0; j < np[i0]; j++) {
			if (azm < az[j]) {
				break;
			}
		}
		if (j > 0) {
			j0 = j - 1;
			az0 = az[j0];
		} else {
			j0 = np[i0] - 1;
			az0 = az[j0] - 360.;
		}
		if (j < np[i0]) {
			j1 = j;
			az1 = az[j1];
		} else {
			j1 = 0;
			az1 = az[j1] + 360.;
		}
		pat0 = rf[j0] + (((azm - az0) / (az1 - az0)) * (rf[j1] - rf[j0]));

		if (i1 == i0) {

			pat = pat0;

		} else {

			az = rpat->angle[i1];
			rf = rpat->pat[i1];
			for (j = 0; j < np[i1]; j++) {
				if (azm < az[j]) {
					break;
				}
			}
			if (j > 0) {
				j0 = j - 1;
				az0 = az[j0];
			} else {
				j0 = np[i1] - 1;
				az0 = az[j0] - 360.;
			}
			if (j < np[i1]) {
				j1 = j;
				az1 = az[j1];
			} else {
				j1 = 0;
				az1 = az[j1] + 360.;
			}
			pat1 = rf[j0] + (((azm - az0) / (az1 - az0)) * (rf[j1] - rf[j0]));

			pat = pat0 + (((freq - fq0) / (fq1 - fq0)) * (pat1 - pat0));
		}
	}

	return(pat);
}
