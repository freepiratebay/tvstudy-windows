//
//  model.h
//  TVStudy
//
//  Copyright (c) 2016-2017 Hammett & Edison, Inc.  All rights reserved.


// Propagation model interface between TVStudy code and propagation model code.  See model.c for details.


//+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// ADDING A NEW MODEL: To add a new propagation model, make changes to this file and to model.c in each section that
// has a comment like this one to set up and call the model.  The actual model code may be placed directly in model.c,
// or in separate code files.  In the latter case the TVStudy project Makefile must also be modified, see comments
// there.  Once changes are made, rebuild TVStudy.  Start here by assigning the new model a unique integer identifier
// and define it as an appropriate cpp symbol.  Numbers through MODEL_RESERVED_RANGE are reserved for future models
// that may be included in a TVStudy distribution, so always use a number greater than MODEL_RESERVED_RANGE.  Do not
// re-number existing models.  Be sure to also change the section below for function prototypes as needed.

#define MODEL_LONGLEY_RICE  1
#define MODEL_FCC_CURVES    2
#define MODEL_FREE_SPACE    3

#define MODEL_RESERVED_RANGE  100
//+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++


// Profile array storage offset into allocated block, to provide array positions ahead of the actual profile data
// needed by some model code.  See model.c.

#define PROFILE_OFFSET  2 

// Structure for passing arguments and data to/from model functions.

#define MESSAGE_LEN 256

typedef struct {

	int model;                         // Model identifier, defined in model.c.
	double latitude;                   // Transmitter latitude, NAD83, degrees positive north.
	double longitude;                  // Transmitter longitude, NAD83, degrees positive west.
	double bearing;                    // Bearing to receiver, degrees true.
	double distance;                   // Distance to receiver, kilometers.
	int terrainDb;                     // Terrain database identifier, DO NOT MODIFY.
	double transmitHeightAGL;          // Transmitter height above ground, meters.
	double receiveHeightAGL;           // Receiver height above ground, meters.
	int clutterType;                   // Receiver clutter type, CLUTTER_*.
	double frequency;                  // Frequency, MHz.
	double percentTime;                // Model statistical parameters - percent time variability.
	double percentLocation;            // Percent location variability.
	double percentConfidence;          // Percent confidence.
	double atmosphericRefractivity;    // Atmospheric refractivity, N-units.
	double groundPermittivity;         // Ground permittivity constant.
	double groundConductivity;         // Ground conductivity, Siemens per meter.
	int signalPolarization;            // Signal polarization:
                                       //   0 = horizontal
                                       //   1 = vertical
	int serviceMode;                   // Propagation service mode.  This is provided for the Longley-Rice model, but
                                       //   may be useful for other models as well:
                                       //   0 = Single-message
                                       //   1 = Individual
                                       //   2 = Mobile
                                       //   3 = Broadcast
	int climateType;                   // Climate type, also for Longley-Rice but may be useful for others:
                                       //   1 = Equatorial
                                       //   2 = Continental subtropical
                                       //   3 = Maritime subtropical
                                       //   4 = Desert
                                       //   5 = Continental temperate
                                       //   6 = Maritime temperate over land
                                       //   7 = Maritime temperate over sea
	double profilePpk;                 // Terrain profile resolution, points per kilometer.
	double kilometersPerDegree;        // Spherical earth distance constant.

	int profileCount;                  // Needed count of points in the terrain profile, this actually determines the
                                       //   length of the profile not the distance above.  Note this is one more than
                                       //   distance times resolution since the first profile point is at distance 0.
	float *profile;                    // Terrain profile elevations, meters.  If non-null this will have at least
                                       //   profileCount points, possibly more.

	double fieldStrength;              // Returned from model, field strength at receiver point, dBu for 0 dBk ERP.
	int errorCode;                     // Returned from model, advisory error code.  Even if this is non-zero the
                                       //   field strength must still be set to a "reasonable" value.

	char errorMessage[MESSAGE_LEN];    // Error message string, should be set if fieldStrength was _not_ set.

} MODEL_DATA;

char *get_model_name(int model);
char *get_model_list();
int run_model(MODEL_DATA *data);

//+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// ADDING A NEW MODEL: Place function prototypes here for any and all function calls in the new model code that will
// have to be called from model.c, regardless of whether those are directly in model.c or in separate files.

void longley_rice(MODEL_DATA *data);
void fcc_curve_model(MODEL_DATA *data);
//+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
