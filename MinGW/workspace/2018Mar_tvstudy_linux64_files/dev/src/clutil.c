//
//  clutil.c
//  TVStudy
//
//  Copyright (c) 2016-2017 Hammett & Edison, Inc.  All rights reserved.


// Command-line utility providing various basic operations using modules from the TVStudy engine code.


#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <string.h>
#include <time.h>

#include "global.h"
#include "model/model.h"
#include "fcc_curve.h"
#include "terrain.h"
#include "landcover.h"
#include "coordinates.h"
#include "memory.h"
#include "parser.h"
#include "codeid/codeid.h"
#include <termios.h>


//---------------------------------------------------------------------------------------------------------------------
// Prototypes.

static char *get_usage(int mode);
static void load_configuration();


//---------------------------------------------------------------------------------------------------------------------
// Globals set from configuration file, defaults here.

static double KilometersPerDegree = 111.15;     // Spherical earth distance constant.
static double DipoleCenter = 615.;              // Band center frequency for dipole adjustment.

static int Database = TERR_DB1;                 // Terrain database, TERR_DB0, TERR_DB1, TERR_DB3, TERR_DB30.

static int HAATCount = 8;                       // Number of radials for overall HAAT calculation.
static double HAATMin = 3.2;                    // Start distance on each radial for HAAT calculation, km.
static double HAATMax = 16.1;                   // End distance for HAAT calculation.
static double HAATStep = 0.1;                   // Increment between points for HAAT calculation, km.
static int HAATPrint = 0;                       // True to print tabulation of individual radial HAATs.

static double ReceiveHeight = 10.;              // Receiver height AGL in meters.
static double PercentTime = 50.;                // Statistical variation for time, location, and confidence, percent.
static double PercentLocation = 50.;
static double PercentConfidence = 50.;
static double AtmosphericRefractivity = 301.;   // Atmospheric refractivity, N-units.
static double GroundPermittivity = 15.;         // Ground permittivity constant.
static double GroundConductivity = 0.005;       // Ground conductivity, Siemens/meter.
static int SignalPolarization = 0;              // Polarization, 0 = horizontal, 1 = vertical.
static int ServiceMode = 3;                     // Service mode, 0 = Single-message, 1 = Individual, 2 = Mobile,
                                                //   3 = Broadcast
static int ClimateType = 5;                     // Climate type, 1 = Equatorial, 2 = Continental subtropical,
                                                //   3 = Maritime subtropical, 4 = Desert, 5 = Continental temperate,
                                                //   6 = Maritime temperate over land, 7 = Maritime temperate over sea

static int IgnoreAreaFailure = 0;               // True to ignore outside-area coordinate conversion failure.

static int NLCDVersion = 2006;                  // Land cover database version, 2006 or 2011.

static char *CommandName = "";   // For error messages.


//---------------------------------------------------------------------------------------------------------------------
// Check the command line, show help if requested, else verify argument count and display usage if needed.

int main(int argc, char **argv) {

	CommandName = rindex(argv[0], '/');
	if (CommandName) {
		CommandName++;
	} else {
		CommandName = argv[0];
	}

	if ((argc > 1) && !strcasecmp(argv[1], "help")) {
		fprintf(stderr, "\nTVStudy utility functions, version %s (%s)\n", TVSTUDY_VERSION, CODE_ID);
		fputs("\nAvailable functions:\n", stderr);
		fprintf(stderr, "                Point elevation: %s %s\n", CommandName, get_usage(1));
		fprintf(stderr, "                NAD83 --> NAD27: %s %s\n", CommandName, get_usage(2));
		fprintf(stderr, "                NAD27 --> NAD83: %s %s\n", CommandName, get_usage(3));
		fprintf(stderr, "                HAAT given AMSL: %s %s\n", CommandName, get_usage(4));
		fprintf(stderr, "                HAAT given HAGL: %s %s\n", CommandName, get_usage(5));
		fprintf(stderr, "                Curves distance: %s %s\n", CommandName, get_usage(6));
		fprintf(stderr, "            Curves ERP required: %s %s\n", CommandName, get_usage(7));
		fprintf(stderr, "                HAGL given AMSL: %s %s\n", CommandName, get_usage(8));
		fprintf(stderr, "                AMSL given HAGL: %s %s\n", CommandName, get_usage(9));
		fprintf(stderr, "                HAGL given HAAT: %s %s\n", CommandName, get_usage(10));
		fprintf(stderr, "                AMSL given HAAT: %s %s\n", CommandName, get_usage(11));
		fprintf(stderr, "               Distance-bearing: %s %s\n", CommandName, get_usage(12));
		fprintf(stderr, "   Coordinates distance-bearing: %s %s\n", CommandName, get_usage(13));
		fprintf(stderr, "          UHF dipole adjustment: %s %s\n", CommandName, get_usage(14));
		fprintf(stderr, "                Land cover type: %s %s\n", CommandName, get_usage(15));
		fprintf(stderr, "         Profile point-to-point: %s %s\n", CommandName, get_usage(16));
		fprintf(stderr, "       Profile distance-bearing: %s %s\n", CommandName, get_usage(17));
		fprintf(stderr, "  Field strength point-to-point: %s %s\n", CommandName, get_usage(18));
		fprintf(stderr, "Field strength distance-bearing: %s %s\n", CommandName, get_usage(19));
		fputs("\nArgument details:\n", stderr);
		fputs("All coordinate inputs except for function 3 should be in NAD83.\n", stderr);
		fputs("Latitude and longitude must be in decimal degrees.\n", stderr);
		fputs("Latitude is positive north, longitude is positive west.\n", stderr);
		fputs("All heights are in meters, and ERP in kilowatts.\n", stderr);
		fputs("\nValues for \"trndb\":\n", stderr);
		fprintf(stderr, "%d = 1/3 second terrain (if available)\n", TERR_DB0);
		fprintf(stderr, "%d = 1 second terrain\n", TERR_DB1);
		fprintf(stderr, "%d = 3 second terrain\n", TERR_DB3);
		fprintf(stderr, "%d = 30 second terrain\n", TERR_DB30);
		fputs("\nValues for \"band\":\n", stderr);
		fprintf(stderr, "%d = Channels 2-4\n", BAND_VLO1);
		fprintf(stderr, "%d = Channels 5-6\n", BAND_VLO2);
		fprintf(stderr, "%d = Channels 7-13\n", BAND_VHI);
		fprintf(stderr, "%d = Channels 14-51\n", BAND_UHF);
		fputs("\nValues for \"curve\":\n", stderr);
		fprintf(stderr, "%d = F(50,50)\n", FCC_F50);
		fprintf(stderr, "%d = F(50,10)\n", FCC_F10);
		fprintf(stderr, "%d = F(50,90)\n", FCC_F90);
		fputs("\nValues for \"lcdb\"\n", stderr);
		fputs("2006 = NLCD 2006\n", stderr);
		fputs("2011 = NLCD 2011\n", stderr);
		fputs("\nValues for \"model\"\n", stderr);
		fputs(get_model_list(), stderr);
		fprintf(stderr, "\nSee %s/%s.conf for other configuration options.\n\n", LIB_DIRECTORY_NAME, CommandName);
		exit(0);
	}

	// Option -t sets the terrain database, this may appear regardless of mode although it is not needed by all.

	int iarg = 1, dbopt = 0, trndb = 0;
	if ((argc > 2) && (argv[1][0] == '-') && (argv[1][1] == 't')) {
		iarg = 3;
		dbopt = 1;
		trndb = atoi(argv[2]);
	}

	int mode = 0;
	if (argc > iarg) {
		mode = atoi(argv[iarg++]);
	}

	int minargc = 0, maxargc = 0, latlonargs = 0;
	switch (mode) {
		case 1:   // Point elevation: lat lon
			minargc = 2;
			maxargc = 2;
			latlonargs = 1;
			break;
		case 2:   // NAD83 --> NAD27: lat lon
		case 3:   // NAD27 --> NAD83: lat lon
			minargc = 2;
			maxargc = 2;
			latlonargs = 1;
			break;
		case 4:   // HAAT given AMSL: lat lon rcamsl
		case 5:   // HAAT given HAGL: lat lon rcagl
			minargc = 3;
			maxargc = 3;
			latlonargs = 1;
			break;
		case 6:   // Curves distance: contour haat erp band curve
		case 7:   // Curves ERP reqd: contour haat dist band curve
			minargc = 5;
			maxargc = 5;
			break;
		case 8:   // HAGL given AMSL: lat lon rcamsl
		case 9:   // AMSL given HAGL: lat lon rcagl
			minargc = 3;
			maxargc = 3;
			latlonargs = 1;
			break;
		case 10:   // HAGL given HAAT: lat lon haat
		case 11:   // AMSL given HAAT: lat lon haat
			minargc = 3;
			maxargc = 3;
			latlonargs = 1;
			break;
		case 12:   // DistanceBearing: lat1 lon1 lat2 lon2
			minargc = 4;
			maxargc = 4;
			latlonargs = 1;
			break;
		case 13:   // Coords DistBear: lat lon dist bear
			minargc = 4;
			maxargc = 4;
			latlonargs = 1;
			break;
		case 14:   // UHF Dipole Adj.: contour channel
			minargc = 2;
			maxargc = 2;
			break;
		case 15:   // Land Cover Type: lat lon [ lcdb ]
			minargc = 2;
			maxargc = 3;
			latlonargs = 1;
			break;
		case 16:   // Profile 2 Point: lat1 lon1 lat2 lon2 step
		case 17:   // Profile Dist Br: lat lon dist bear step
			minargc = 5;
			maxargc = 5;
			latlonargs = 1;
			break;
		case 18:   // Field Str 2 Pts: lat1 lon1 lat2 lon2 step model freq txagl [ rxagl ]
		case 19:   // Field Str Ds Br: lat lon dist bear step model freq txagl [ rxagl ]
			minargc = 8;
			maxargc = 9;
			latlonargs = 1;
			break;
	}

	if (((argc - iarg) < minargc) || ((argc - iarg) > maxargc)) {
		fprintf(stderr, "TVStudy utility functions, version %s\n", TVSTUDY_VERSION);
		fprintf(stderr, "usage: %s %s\n", CommandName, get_usage(mode));
		fprintf(stderr, "help: %s help\n", CommandName);
		exit(1);
	}

	// Load the configuration file.

	load_configuration();

	// Check the terrain key if provided as argument, else set default.

	if (dbopt) {

		if ((TERR_DB0 != trndb) && (TERR_DB1 != trndb) && (TERR_DB3 != trndb) && (TERR_DB30 != trndb)) {
			fprintf(stderr, "%s: illegal terrain database key\n", CommandName);
			exit(1);
		}

	} else {
		trndb = Database;
	}

	// Many commands have latitude, longitude as first two arguments.

	double latitude = 0., longitude = 0.;

	if (latlonargs) {

		latitude = atof(argv[iarg++]);
		if ((latitude < -90) || (latitude > 90.)) {
			fprintf(stderr, "%s: illegal latitude\n", CommandName);
			exit(1);
		}

		longitude = atof(argv[iarg++]);
		if ((longitude < -180) || (longitude > 180.)) {
			fprintf(stderr, "%s: illegal longitude\n", CommandName);
			exit(1);
		}
	}

	// Run the command.

	int err = 0;

	switch (mode) {

		// Point elevation: lat lon

		case 1: {

			float elev = 0.;
			err = terrain_point(latitude, longitude, trndb, &elev);
			if (err) {
				fprintf(stderr, "%s: terrain lookup failed: db=%d err=%d", CommandName, trndb, err);
				exit(1);
			}

			printf("%.2f\n", elev);

			break;
		}

		// NAD83 --> NAD27: lat lon
		// NAD27 --> NAD83: lat lon

		case 2:
		case 3: {

			int conv = CNV_N83N27;
			if (3 == mode) {
				conv = CNV_N27N83;
			}

			double lat2 = 0., lon2 = 0.;
			err = convert_coords(latitude, longitude, conv, &lat2, &lon2);
			if (err && ((err != CNV_EAREA) || !IgnoreAreaFailure)) {
				fprintf(stderr, "%s: coordinate conversion failed: err=%d", CommandName, err);
				exit(1);
			}

			printf("%.8f, %.8f\n", lat2, lon2);

			break;
		}

		// HAAT given AMSL: lat lon rcamsl
		// HAAT given HAGL: lat lon rcagl

		case 4:
		case 5: {

			double height = atof(argv[iarg++]);

			if (5 == mode) {

				float elev = 0.;
				err = terrain_point(latitude, longitude, trndb, &elev);
				if (err) {
					fprintf(stderr, "%s: terrain lookup failed: db=%d err=%d", CommandName, trndb, err);
					exit(1);
				}

				height += (double)elev;
			}

			double *haat = (double *)mem_alloc(HAATCount * sizeof(double));
			int err = compute_haat(latitude, longitude, height, haat, HAATCount, trndb, HAATMin, HAATMax, HAATStep,
				KilometersPerDegree, NULL);
			if (err) {
				fprintf(stderr, "%s: terrain lookup failed: db=%d err=%d", CommandName, trndb, err);
				exit(1);
			}

			double haatval = 0., azm;
			int i;
			for (i = 0; i < HAATCount; i++) {
				if (HAATPrint) {
					azm = (double)i * (360. / HAATCount);
					printf("%.2f, %.2f\n", azm, haat[i]);
				}
				haatval += haat[i];
			}
			haatval /= (double)HAATCount;

			printf("%.2f\n", haatval);

			mem_free(haat);
			break;
		}

		// Curves distance: contour haat erp band curve
		// Curves ERP reqd: contour haat dist band curve

		case 6:
		case 7: {

			double cont = atof(argv[iarg++]);
			if ((cont < 0.) || (cont > 150.)) {
				fprintf(stderr, "%s: illegal contour value\n", CommandName);
				exit(1);
			}

			double haat = atof(argv[iarg++]);

			double erp = 0., dist = 0.;
			int func = 0;

			if (6 == mode) {

				erp = atof(argv[iarg++]);
				if ((erp <= 0.) || (erp > 10000.)) {
					fprintf(stderr, "%s: illegal ERP\n", CommandName);
					exit(1);
				}
				erp = 10. * log10(erp);
				func = FCC_DST;

			} else {

				dist = atof(argv[iarg++]);
				if ((dist <= 0.) || (dist > 1000.)) {
					fprintf(stderr, "%s: illegal distance\n", CommandName);
					exit(1);
				}
				func = FCC_PWR;
			}

			int band = atoi(argv[iarg++]);
			if ((BAND_VLO1 != band) && (BAND_VLO2 != band) && (BAND_VHI != band) && (BAND_UHF != band)) {
				fprintf(stderr, "%s: illegal band number\n", CommandName);
				exit(1);
			}

			int curv = atoi(argv[iarg++]);
			if ((FCC_F50 != curv) && (FCC_F10 != curv) && (FCC_F90 != curv)) {
				fprintf(stderr, "%s: illegal curve set\n", CommandName);
				exit(1);
			}

			err = fcc_curve(&erp, &cont, &dist, haat, band, func, curv, OFF_CURV_METH_FS);
			if (err) {
				printf("Lookup returned warning code %d\n", err);
			}

			if (6 == mode) {
				printf("%.4f\n", dist);
			} else {
				printf("%.4f\n", pow(10., (erp / 10.)));
			}

			break;
		}

		// HAGL given AMSL: lat lon rcamsl
		// AMSL given HAGL: lat lon rcagl

		case 8:
		case 9: {

			double height = atof(argv[iarg++]);

			float elev = 0.;
			err = terrain_point(latitude, longitude, trndb, &elev);
			if (err) {
				fprintf(stderr, "%s: terrain lookup failed: db=%d err=%d", CommandName, trndb, err);
				exit(1);
			}

			if (8 == mode) {
				printf("%.2f\n", (height - (double)elev));
			} else {
				printf("%.2f\n", ((double)elev + height));
			}

			break;
		}

		// HAGL given HAAT: lat lon haat
		// AMSL given HAAT: lat lon haat

		case 10:
		case 11: {

			double haatin = atof(argv[iarg++]);

			double hamsl = 10000.;

			double *haat = (double *)mem_alloc(HAATCount * sizeof(double));
			int err = compute_haat(latitude, longitude, hamsl, haat, HAATCount, trndb, HAATMin, HAATMax, HAATStep,
				KilometersPerDegree, NULL);
			if (err) {
				fprintf(stderr, "%s: terrain lookup failed: db=%d err=%d", CommandName, trndb, err);
				exit(1);
			}

			double haatval = 0., azm;
			int i;
			for (i = 0; i < HAATCount; i++) {
				if (HAATPrint) {
					azm = (double)i * (360. / (double)HAATCount);
					printf("%.2f, %.2f\n", azm, haat[i]);
				}
				haatval += haat[i];
			}
			haatval /= (double)HAATCount;

			hamsl -= haatval - haatin;

			if (10 == mode) {

				float elev = 0.;
				err = terrain_point(latitude, longitude, trndb, &elev);
				if (err) {
					fprintf(stderr, "%s: terrain lookup failed: db=%d err=%d", CommandName, trndb, err);
					exit(1);
				}

				printf("%.2f\n", (hamsl - (double)elev));

			} else {

				printf("%.2f\n", hamsl);
			}

			mem_free(haat);
			break;
		}

		// DistanceBearing: lat1 lon1 lat2 lon2

		case 12: {

			double lat2 = atof(argv[iarg++]);
			if ((lat2 < -90) || (lat2 > 90.)) {
				fprintf(stderr, "%s: illegal latitude\n", CommandName);
				exit(1);
			}

			double lon2 = atof(argv[iarg++]);
			if ((lon2 < -180) || (lon2 > 180.)) {
				fprintf(stderr, "%s: illegal longitude\n", CommandName);
				exit(1);
			}

			double dist = 0., bear = 0.;
			bear_distance(latitude, longitude, lat2, lon2, &bear, NULL, &dist, KilometersPerDegree);

			printf("%.2f, %.2f\n", dist, bear);

			break;
		}

		// Coords DistBear: lat lon dist bear

		case 13: {

			double dist = atof(argv[iarg++]);
			if ((dist <= 0.) || (dist > 10000.)) {
				fprintf(stderr, "%s: illegal distance\n", CommandName);
				exit(1);
			}

			double bear = atof(argv[iarg++]);
			if ((bear < 0.) || (bear >= 360.)) {
				fprintf(stderr, "%s: illegal bearing\n", CommandName);
				exit(1);
			}

			double lat2 = 0., lon2 = 0.;
			coordinates(latitude, longitude, bear, dist, &lat2, &lon2, KilometersPerDegree);

			printf("%.8f, %.8f\n", lat2, lon2);

			break;
		}

		// UHF Dipole Adj.: contour channel

		case 14: {

			double cont = atof(argv[iarg++]);

			int chan = atoi(argv[iarg++]);
			if ((chan < 14) || (chan > 51)) {
				fprintf(stderr, "%s: illegal channel number\n", CommandName);
				exit(1);
			}

			double freq = 473. + ((double)(chan - 14) * 6.);
			cont += 20. * log10(freq / DipoleCenter);

			printf("%.2f\n", cont);

			break;
		}

		// Land Cover Type: lat lon [ lcdb ]

		case 15: {

			int lcdb = NLCDVersion;
			if (argc > iarg) {
				lcdb = atoi(argv[iarg++]);
				if ((2006 != lcdb) && (2011 != lcdb)) {
					fprintf(stderr, "%s: illegal land cover database version\n", CommandName);
					exit(1);
				}
			}

			puts(land_cover_string(latitude, longitude, lcdb));

			break;
		}

		// Profile 2 Point: lat1 lon1 lat2 lon2 step
		// Profile Dist Br: lat lon dist bear step

		case 16:
		case 17: {

			double dist = 0., bear = 0.;

			if (16 == mode) {

				double lat2 = atof(argv[iarg++]);
				if ((lat2 < -90) || (lat2 > 90.)) {
					fprintf(stderr, "%s: illegal latitude\n", CommandName);
					exit(1);
				}

				double lon2 = atof(argv[iarg++]);
				if ((lon2 < -180) || (lon2 > 180.)) {
					fprintf(stderr, "%s: illegal longitude\n", CommandName);
					exit(1);
				}

				bear_distance(latitude, longitude, lat2, lon2, &bear, NULL, &dist, KilometersPerDegree);

			} else {

				dist = atof(argv[iarg++]);
				if ((dist <= 0.) || (dist > 10000.)) {
					fprintf(stderr, "%s: illegal distance\n", CommandName);
					exit(1);
				}

				bear = atof(argv[iarg++]);
				if ((bear < 0.) || (bear >= 360.)) {
					fprintf(stderr, "%s: illegal bearing\n", CommandName);
					exit(1);
				}
			}

			double step = atof(argv[iarg++]);
			if ((step <= 0.) || (step > 2.)) {
				fprintf(stderr, "%s: illegal profile point spacing\n", CommandName);
				exit(1);
			}
			double ppk = 1. / step;

			int ptmax = (int)((dist * ppk) + 0.5) + 1;
			float *elev = (float *)mem_alloc(ptmax * sizeof(float));

			int ptcount;
			err = terrain_profile(latitude, longitude, bear, dist, ppk, trndb, ptmax, elev, &ptcount,
				KilometersPerDegree);
			if (err < 0) {
				fprintf(stderr, "%s: terrain lookup failed: db=%d err=%d\n", CommandName, trndb, err);
				exit(1);
			}
			if (ptcount < ptmax) {
				printf("Terrain profile is short, db=%d err=%d\n", trndb, err);
			}

			int i;
			for (i = 0; i < ptcount; i++) {
				dist = (double)i * step;
				printf("%.2f, %.2f\n", dist, elev[i]);
			}

			mem_free(elev);
			break;
		}

		// Field Str 2 Pts: lat1 lon1 lat2 lon2 step model freq txagl [ rxagl ]
		// Field Str Ds Br: lat lon dist bear step model freq txagl [ rxagl ]

		case 18:
		case 19: {

			MODEL_DATA data;

			data.latitude = latitude;
			data.longitude = longitude;

			if (18 == mode) {

				double lat2 = atof(argv[iarg++]);
				if ((lat2 < -90) || (lat2 > 90.)) {
					fprintf(stderr, "%s: illegal latitude\n", CommandName);
					exit(1);
				}

				double lon2 = atof(argv[iarg++]);
				if ((lon2 < -180) || (lon2 > 180.)) {
					fprintf(stderr, "%s: illegal longitude\n", CommandName);
					exit(1);
				}

				bear_distance(latitude, longitude, lat2, lon2, &data.bearing, NULL, &data.distance,
					KilometersPerDegree);

			} else {

				data.distance = atof(argv[iarg++]);
				if ((data.distance <= 0.) || (data.distance > 10000.)) {
					fprintf(stderr, "%s: illegal distance\n", CommandName);
					exit(1);
				}

				data.bearing = atof(argv[iarg++]);
				if ((data.bearing < 0.) || (data.bearing >= 360.)) {
					fprintf(stderr, "%s: illegal bearing\n", CommandName);
					exit(1);
				}
			}

			double step = atof(argv[iarg++]);
			if ((step <= 0.) || (step > 2.)) {
				fprintf(stderr, "%s: illegal profile point spacing\n", CommandName);
				exit(1);
			}
			data.profilePpk = 1. / step;

			data.model = atoi(argv[iarg++]);

			data.frequency = atof(argv[iarg++]);
			if (data.frequency < 50.) {
				fprintf(stderr, "%s: illegal frequency\n", CommandName);
				exit(1);
			}

			data.transmitHeightAGL = atof(argv[iarg++]);
			if (data.transmitHeightAGL < 0.01) {
				fprintf(stderr, "%s: illegal transmitter height\n", CommandName);
				exit(1);
			}

			if (argc > iarg) {
				data.receiveHeightAGL = atof(argv[iarg++]);
				if (data.receiveHeightAGL < 0.01) {
					fprintf(stderr, "%s: illegal receiver height\n", CommandName);
					exit(1);
				}
			} else {
				data.receiveHeightAGL = ReceiveHeight;
			}

			data.terrainDb = trndb;
			data.percentTime = PercentTime;
			data.percentLocation = PercentLocation;
			data.percentConfidence = PercentConfidence;
			data.atmosphericRefractivity = AtmosphericRefractivity;
			data.groundPermittivity = GroundPermittivity;
			data.groundConductivity = GroundConductivity;
			data.signalPolarization = SignalPolarization;
			data.serviceMode = ServiceMode;
			data.climateType = ClimateType;
			data.kilometersPerDegree = KilometersPerDegree;

			data.profileCount = (int)((data.distance * data.profilePpk) + 0.5) + 1;
			data.profile = NULL;
			data.fieldStrength = 0.;
			data.errorCode = 0;
			data.errorMessage[0] = '\0';

			err = run_model(&data);
			if (err) {
				if (data.errorMessage[0]) {
					fprintf(stderr, "%s: %s\n", CommandName, data.errorMessage);
				} else {
					fprintf(stderr, "%s: propagation model failed, err=%d\n", CommandName, err);
				}
				exit(1);
			}

			printf("%.2f\n", data.fieldStrength);
			if (data.errorCode) {
				printf("errorCode = %d\n", data.errorCode);
			}

			break;
		}
	}

	exit(0);
}


//---------------------------------------------------------------------------------------------------------------------
// Return command usage string based on mode.

static char *get_usage(int mode) {

	switch (mode) {
		case 1:
			return "[ -t trndb ] 1 lat lon";
		case 2:
			return "2 lat lon";
		case 3:
			return "3 lat lon";
		case 4:
			return "[ -t trndb ] 4 lat lon rcamsl";
		case 5:
			return "[ -t trndb ] 5 lat lon rcagl";
		case 6:
			return "6 contour haat erp band curve";
		case 7:
			return "7 contour haat dist band curve";
		case 8:
			return "[ -t trndb ] 8 lat lon rcamsl";
		case 9:
			return "[ -t trndb ] 9 lat lon rcagl";
		case 10:
			return "[ -t trndb ] 10 lat lon haat";
		case 11:
			return "[ -t trndb ] 11 lat lon haat";
		case 12:
			return "12 lat1 lon1 lat2 lon2";
		case 13:
			return "13 lat lon dist bear";
		case 14:
			return "14 contour channel";
		case 15:
			return "15 lat lon [ lcdb ]";
		case 16:
			return "[ -t trndb ] 16 lat1 lon1 lat2 lon2 step";
		case 17:
			return "[ -t trndb ] 17 lat lon dist bear step";
		case 18:
			return "[ -t trndb ] 18 lat1 lon1 lat2 lon2 step model freq txagl [ rxagl ]";
		case 19:
			return "[ -t trndb ] 19 lat lon dist bear step model freq txagl [ rxagl ]";
	}

	return "mode argument ...";
}


//---------------------------------------------------------------------------------------------------------------------
// Read in the configuration.

static void load_configuration() {

	char fname[MAX_STRING];

	snprintf(fname, MAX_STRING, "%s/%s.conf", LIB_DIRECTORY_NAME, CommandName);
	int err = config_loadfile(fname);
	if (err) {
		if (err > 0) {
			fprintf(stderr, "%s: error reading %s at line number %d\n", CommandName, fname, err);
			exit(1);
		}
		printf("Could not open configuration file %s, using defaults.", fname);
	}

	char *value;

	if ((value = config_getsectionvalue("Constants", "KilometersPerDegree"))) {
		KilometersPerDegree = atof(value);
		if ((KilometersPerDegree < 111.) || (KilometersPerDegree > 112.)) {
			fprintf(stderr, "%s: illegal value for KilometersPerDegree in configuration\n", CommandName);
			exit(1);
		}
	}

	if ((value = config_getsectionvalue("Constants", "DipoleCenter"))) {
		DipoleCenter = atof(value);
		if ((DipoleCenter < 470.) || (DipoleCenter > 698.)) {
			fprintf(stderr, "%s: illegal value for DipoleCenter in configuration\n", CommandName);
			exit(1);
		}
	}

	if ((value = config_getsectionvalue("Terrain", "Database"))) {
		Database = atoi(value);
		if ((TERR_DB0 != Database) && (TERR_DB1 != Database) && (TERR_DB3 != Database) && (TERR_DB30 != Database)) {
			fprintf(stderr, "%s: illegal value for Database in configuration\n", CommandName);
			exit(1);
		}
	}

	if ((value = config_getsectionvalue("HAAT", "HAATCount"))) {
		HAATCount = atoi(value);
		if (HAATCount <= 0) {
			fprintf(stderr, "%s: illegal value for HAATCount in configuration\n", CommandName);
			exit(1);
		}
	}

	if ((value = config_getsectionvalue("HAAT", "HAATMin"))) {
		HAATMin = atof(value);
		if ((HAATMin <= 0.) || (HAATMin > 500.)) {
			fprintf(stderr, "%s: illegal value for HAATMin in configuration\n", CommandName);
			exit(1);
		}
	}

	if ((value = config_getsectionvalue("HAAT", "HAATMax"))) {
		HAATMax = atof(value);
		if ((HAATMax <= 0.) || (HAATMax > 500.)) {
			fprintf(stderr, "%s: illegal value for HAATMax in configuration\n", CommandName);
			exit(1);
		}
	}

	if (HAATMax <= HAATMin) {
		fprintf(stderr, "%s: illegal combination of HAATMin and HAATMax in configuration\n", CommandName);
		exit(1);
	}

	if ((value = config_getsectionvalue("HAAT", "HAATStep"))) {
		HAATStep = atof(value);
		if (HAATStep <= 0.) {
			fprintf(stderr, "%s: illegal value for HAATStep in configuration\n", CommandName);
			exit(1);
		}
	}

	if ((value = config_getsectionvalue("HAAT", "HAATPrint"))) {
		HAATPrint = atoi(value);
	}

	if ((value = config_getsectionvalue("Model", "ReceiveHeight"))) {
		ReceiveHeight = atof(value);
		if (ReceiveHeight < 0.01) {
			fprintf(stderr, "%s: illegal value for ReceiveHeight in configuration\n", CommandName);
			exit(1);
		}
	}

	if ((value = config_getsectionvalue("Model", "PercentTime"))) {
		PercentTime = atof(value);
		if ((PercentTime < 0.01) || (PercentTime > 99.99)) {
			fprintf(stderr, "%s: illegal value for PercentTime in configuration\n", CommandName);
			exit(1);
		}
	}

	if ((value = config_getsectionvalue("Model", "PercentLocation"))) {
		PercentLocation = atof(value);
		if ((PercentLocation < 0.01) || (PercentLocation > 99.99)) {
			fprintf(stderr, "%s: illegal value for PercentLocation in configuration\n", CommandName);
			exit(1);
		}
	}

	if ((value = config_getsectionvalue("Model", "PercentConfidence"))) {
		PercentConfidence = atof(value);
		if ((PercentConfidence < 0.01) || (PercentConfidence > 99.99)) {
			fprintf(stderr, "%s: illegal value for PercentConfidence in configuration\n", CommandName);
			exit(1);
		}
	}

	if ((value = config_getsectionvalue("Model", "AtmosphericRefractivity"))) {
		AtmosphericRefractivity = atof(value);
		if ((AtmosphericRefractivity < -10000.) || (AtmosphericRefractivity > 500.)) {
			fprintf(stderr, "%s: illegal value for AtmosphericRefractivity in configuration\n", CommandName);
			exit(1);
		}
	}

	if ((value = config_getsectionvalue("Model", "GroundPermittivity"))) {
		GroundPermittivity = atof(value);
		if ((GroundPermittivity < 1.) || (GroundPermittivity > 5000.)) {
			fprintf(stderr, "%s: illegal value for GroundPermittivity in configuration\n", CommandName);
			exit(1);
		}
	}

	if ((value = config_getsectionvalue("Model", "GroundConductivity"))) {
		GroundConductivity = atof(value);
		if ((GroundConductivity < 0.0001) || (GroundConductivity > 1.)) {
			fprintf(stderr, "%s: illegal value for GroundConductivity in configuration\n", CommandName);
			exit(1);
		}
	}

	if ((value = config_getsectionvalue("Model", "SignalPolarization"))) {
		SignalPolarization = atoi(value);
		if ((SignalPolarization < 0) || (SignalPolarization > 1)) {
			fprintf(stderr, "%s: illegal value for SignalPolarization in configuration\n", CommandName);
			exit(1);
		}
	}

	if ((value = config_getsectionvalue("Model", "ServiceMode"))) {
		ServiceMode = atoi(value);
		if ((ServiceMode < 0) || (ServiceMode > 3)) {
			fprintf(stderr, "%s: illegal value for ServiceMode in configuration\n", CommandName);
			exit(1);
		}
	}

	if ((value = config_getsectionvalue("Model", "ClimateType"))) {
		ClimateType = atoi(value);
		if ((ClimateType < 1) || (ClimateType > 5)) {
			fprintf(stderr, "%s: illegal value for ClimateType in configuration\n", CommandName);
			exit(1);
		}
	}

	if ((value = config_getsectionvalue("CoordinateConvert", "IgnoreAreaFailure"))) {
		IgnoreAreaFailure = atoi(value);
	}

	if ((value = config_getsectionvalue("LandCover", "NLCDVersion"))) {
		NLCDVersion = atoi(value);
		if ((2006 != NLCDVersion) && (2011 != NLCDVersion)) {
			fprintf(stderr, "%s: illegal value for NLCDVersion in configuration\n", CommandName);
			exit(1);
		}
	}

	config_free();
}
