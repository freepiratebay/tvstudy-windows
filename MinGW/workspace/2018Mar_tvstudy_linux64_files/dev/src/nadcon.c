//
//  nadcon.c
//  TVStudy
//
//  Copyright (c) 2016 Hammett & Edison, Inc.  All rights reserved.


// Command-line utility to convert between NAD83 and NAD27 coordinates.


#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#include "global.h"
#include "coordinates.h"


//---------------------------------------------------------------------------------------------------------------------

int main(int argc, char **argv) {

	char *commandName = rindex(argv[0], '/');
	if (commandName) {
		commandName++;
	} else {
		commandName = argv[0];
	}

	int mode = CNV_N83N27, iarg = 1, narg = 2, badarg = 0;

	while ((argc > iarg) && ('-' == argv[iarg][0]) && !badarg) {
		switch (argv[iarg][1]) {
			case 'r':
				mode = CNV_N27N83;
				break;
			case 'd':
				narg = 6;
				break;
			default:
				badarg = 1;
				break;
		}
		iarg++;
	}

	if (badarg || (argc < (iarg + narg))) {
		fprintf(stderr, "usage: %s [ -r ] latitude longitude\n", commandName);
		fprintf(stderr, "       %s [ -r ] -d latd latm lats lond lonm lons\n", commandName);
		fputs("converts NAD83 to NAD27, -r converts NAD27 to NAD83\n", stderr);
		exit(1);
	}

	double latin, lonin;

	if (2 == narg) {

		latin = atof(argv[iarg++]);
		lonin = atof(argv[iarg++]);

	} else {

		int d = atoi(argv[iarg++]);
		int m = atoi(argv[iarg++]);
		double s = atof(argv[iarg++]);
		latin = (double)abs(d) + ((double)abs(m) / 60.) + (fabs(s) / 3600.);
		if ((d < 0) || (m < 0) || (s < 0.)) {
			latin *= -1.;
		}
		d = atoi(argv[iarg++]);
		m = atoi(argv[iarg++]);
		s = atof(argv[iarg++]);
		lonin = (double)abs(d) + ((double)abs(m) / 60.) + (fabs(s) / 3600.);
		if ((d < 0) || (m < 0) || (s < 0.)) {
			lonin *= -1.;
		}
	}

	while (latin < -90.) latin += 180.;
	while (latin > 90.) latin -= 180.;

	while (lonin < -180.) lonin += 360.;
	while (lonin > 180.) lonin -= 360.;

	double latout, lonout;

	int err = convert_coords(latin, lonin, mode, &latout, &lonout);
	if (err && (err != CNV_EAREA)) {
		fprintf(stderr, "%s: coordinate conversion failed: err=%d", commandName, err);
		exit(1);
	}

	if (2 == narg) {

		printf("%.8f %.8f\n", latout, lonout);

	} else {

		printf("%s  ", latlon_string(latout, 0));
		printf("%s\n", latlon_string(lonout, 1));
	}

	exit(0);
}
