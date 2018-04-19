//
//  longley_rice.c
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.


// Propagation modelling using FORTRAN functions in the itsitm.f module, implementing the Longley-Rice model.


#include <math.h>

#include "model.h"


//---------------------------------------------------------------------------------------------------------------------
// Functions in itsitm.f and structures mapping to FORTRAN common blocks.

void qlrps_lr_(float *, float *, float *, int *, float *, float *);
void qlrpfl_lr_(float *, int *, int *);
void qlra_lr_(int *, int *, int *);
void lrprop_lr_(float *);
float qerf_lr_(float *);
float qerfi_lr_(float *);
float avar_lr_(float *, float *, float *);

extern struct {        // Main parameters common block PROP.
	int kwx;           // Returned error code, caller must set this to 0.
	float aref;        // Reference attenuation computed by LRPROP.
	int mdp;           // Propagation model mode, -1=point-to-point, 0/1=area.
	float dist;        // Path distance, in kilometers.
	float hg[2];       // Heights of antennas above ground, in meters.
	float wn;          // Wave number of the frequency.
	float dh;          // Terrain irregularity parameter.
	float ens;         // Surface refractivity.
	float gme;         // Effective earth curvature.
	float zgnd_real;   // Surface transfer impedance, these map to a FORTRAN COMPLEX variable.
	float zgnd_imag;
	float he[2];       // Effective antenna heights in meters.
	float dl[2];       // Horizon distances in kilometers.
	float the[2];      // Horizon depression angles.
} prop_lr_;

extern struct {   // Common block PROPV, parameters for subroutine AVAR.
	int lvar;     // Indicates how many parameters have been defined.
	float sgc;    // Standard deviation coefficient.
	int mdvar;    // Variability mode.
	int klim;     // Climate code.
} propv_lr_;


//---------------------------------------------------------------------------------------------------------------------
// Compute field strength at a specified location using Longley-Rice terrain-sensitive propagation modelling.

// Arguments:

//    data  Model data structure.

void longley_rice(MODEL_DATA *data) {

	float statval, fmhz, zsys, en0, eps, sgm, ztme, zloc, zcnf, aloss;
	int ipol;

	// Set up basic parameters, call initialization routine qlrps().

	ipol = data->signalPolarization;
	propv_lr_.mdvar = data->serviceMode;
	propv_lr_.klim = data->climateType;
	if (data->transmitHeightAGL > 3000.) {
		prop_lr_.hg[0] = 3000.;
	} else {
		if (data->transmitHeightAGL < 0.5) {
			prop_lr_.hg[0] = 0.5;
		} else {
			prop_lr_.hg[0] = (float)data->transmitHeightAGL;
		}
	}
	prop_lr_.hg[1] = (float)data->receiveHeightAGL;
	fmhz = (float)data->frequency;
	zsys = 0.;
	en0 = (float)data->atmosphericRefractivity;
	eps = (float)data->groundPermittivity;
	sgm = (float)data->groundConductivity;
	prop_lr_.kwx = 0;
	qlrps_lr_(&fmhz, &zsys, &en0, &ipol, &eps, &sgm);

	// The calling code offset profile storage in an allocated block so there are positions available ahead of the
	// profile, set the point index and spacing in those positions for the FORTRAN.  Then run the basic path loss.

	float *profile = data->profile - 2;
	profile[0] = (float)(data->profileCount - 1);
	profile[1] = (float)(1000. / data->profilePpk);
	prop_lr_.kwx = 0;

	qlrpfl_lr_(profile, &propv_lr_.klim, &propv_lr_.mdvar);

	// Apply the statistical parameters to get final path loss below free-space.

	statval = (float)data->percentTime / 100.;
	ztme = qerfi_lr_(&statval);
	statval = (float)data->percentLocation / 100.;
	zloc = qerfi_lr_(&statval);
	statval = (float)data->percentConfidence / 100.;
	zcnf = qerfi_lr_(&statval);

	aloss = avar_lr_(&ztme, &zloc, &zcnf);

	// Compute field strength in dBu for a reference power of 0 dBk by subtracting the path loss from free-space.

	data->fieldStrength = 106.92 - (20. * log10((double)(data->profileCount - 1) / data->profilePpk)) - (double)aloss;

	data->errorCode = prop_lr_.kwx;
}
