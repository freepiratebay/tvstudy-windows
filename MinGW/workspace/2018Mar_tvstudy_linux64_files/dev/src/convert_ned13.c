//
//  convert_ned13.c
//  TVStudy
//
//  Copyright (c) 2016 Hammett & Edison, Inc.  All rights reserved.


// Convert NED 1/3-second data files in GRIDFLOAT format into tvstudy format.  This reads a float file into memory,
// processes and writes out a tvstudy output file to the NED13 output directory (defined in headers).  Multiple input
// files may be processed in one run, listed on the command line.  It also rebuilds the block file status index in the
// database directory, that is done even if no input files are processed.


#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <math.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/file.h>
#include <sys/errno.h>

#include "global.h"
#include "terrain.h"
#include "parser.h"
#include "memory.h"
#include "codeid/codeid.h"

#include <termios.h>

//---------------------------------------------------------------------------------------------------------------------

#define GRID_SIZE   10804   // Dimension of the buffer grid, 2 extra rows/columns all around, 1 will be kept in output
                            //   but 2 are needed for the missing-data fill algorithm.
#define EXCESS_OVER     8   // Excess overlap rows/columns in the input file, NED files have 6 extras all around.
#define CELL_SIZE    1352   // Dimension of cell grids in output file, 1 extra row/column all around.

#define MISSING_DATA  -32767   // Missing/bad data code used in internal buffer.


//---------------------------------------------------------------------------------------------------------------------

static int writetvs(int lat, int lon, int *miscount);
static int pack(int16_t *data, int npt, int16_t *minelv, u_int8_t *bitlen, u_int8_t **packed);


//---------------------------------------------------------------------------------------------------------------------

static int16_t Data[GRID_SIZE][GRID_SIZE];


//---------------------------------------------------------------------------------------------------------------------

int main(int argc, char **argv) {

	FILE *flt, *hdr, *ls, *idx;
	int i, iarg, latIndex, lonIndex, badfile, latsize, lonsize, bigend, insize, lti, lni, nfile, miscount, hasMissing,
		zeroCount, ltin, ltis, lniw, lnie;
	int16_t d;
	size_t readsize;
	char *tmp, *infl;
	char fname[MAX_STRING], line[MAX_STRING], cmd[MAX_STRING];

	static u_int8_t blockIndex[TRN_FILE_STATUS_SIZE];

	static u_int8_t readBuf[(GRID_SIZE + EXCESS_OVER) * sizeof(float)];
	static float dataBuf[GRID_SIZE + EXCESS_OVER];

	// Check command line, can have no file arguments, print usage if first argument is an option or the word "help".

	if ((argc > 1) && ((argv[1][0] == '-') || !strcasecmp(argv[1], "help"))) {
		fprintf(stderr, "\nTVStudy 1/3-second terrain utility, version %s (%s)\n\n", TVSTUDY_VERSION, CODE_ID);
		fprintf(stderr, "usage: %s [ floatyNNxNNN_13.flt ...]\n\n", argv[0]);
		fputs("Matching .hdr file must be in same location as each .flt file\n", stderr);
		fputs("Run with no arguments to update the converted-file index\n", stderr);
		fputs("Update the index if previously-converted files are deleted\n\n", stderr);
		exit(1);
	}

	// Make sure output file path exists.

	if (mkdir(DBASE_DIRECTORY_NAME, 0750)) {
		if (errno != EEXIST) {
			fprintf(stderr, "%s: cannot create output directory '%s'", argv[0], DBASE_DIRECTORY_NAME);
			exit(1);
		}
	}

	snprintf(fname, MAX_STRING, "%s/%s", DBASE_DIRECTORY_NAME, TRN_DB_NAME_NED13);
	if (mkdir(fname, 0750)) {
		if (errno != EEXIST) {
			fprintf(stderr, "%s: cannot create output directory '%s'", argv[0], fname);
			exit(1);
		}
	}

	// Loop for input files, extract block coordinates from file name.  Blocks are named by north-west corner, convert
	// to south-east.  Note the convention in tvstudy is positive north latitude and positive west longitude.

	nfile = 0;

	for (iarg = 1; iarg < argc; iarg++) {

		if (NULL != (tmp = rindex(argv[iarg], '/'))) {
			infl = tmp + 1;
		} else {
			infl = argv[iarg];
		}
		latIndex = atoi(infl + 6);
		lonIndex = atoi(infl + 9);
		if ((latIndex < 0) || (latIndex > 90) || (lonIndex < 0) || (lonIndex > 180) ||
				((infl[5] != 'n') && (infl[5] != 's')) || ((infl[8] != 'w') && (infl[8] != 'e'))) {
			printf("%s skipped, bad coordinates in file name\n", argv[iarg]);
			continue;
		}
		if ('n' == infl[5]) {
			latIndex = latIndex - 1;
		} else {
			latIndex = -latIndex - 1;
		}
		if ('w' == infl[8]) {
			lonIndex = lonIndex - 1;
		} else {
			lonIndex = -lonIndex - 1;
		}

		// Open file, also open header and read to determine row/column count and byte order.

		badfile = 0;

		if (NULL == (flt = fopen(argv[iarg], "rb"))) {
			printf("%s skipped, cannot open file\n", argv[iarg]);
			continue;
		}

		lcpystr(fname, argv[iarg], MAX_STRING);
		if (NULL != (tmp = rindex(fname, '.'))) {
			*tmp = '\0';
		}
		lcatstr(fname, ".hdr", MAX_STRING);
		if (NULL == (hdr = fopen(fname, "rb"))) {
			printf("%s skipped, cannot open header file\n", argv[iarg]);
			fclose(flt);
			continue;
		}

		latsize = 0;
		lonsize = 0;
		bigend = -1;

		while (fgetnl(line, MAX_STRING, hdr) >= 0) {
			if (strncmp(line, "ncols", 5) == 0) {
				lonsize = atoi(line + 5);
				continue;
			}
			if (strncmp(line, "nrows", 5) == 0) {
				latsize = atoi(line + 5);
				continue;
			}
			if (strncmp(line, "byteorder", 9) == 0) {
				for (i = 9; line[i]; i++) {
					if (line[i] == 'L') {
						bigend = 0;
						break;
					}
					if (line[i] == 'M') {
						bigend = 1;
						break;
					}
				}
			}
		}

		fclose(hdr);

		if ((0 == latsize) || (0 == lonsize) || (bigend < 0)) {
			printf("%s skipped, missing data or bad format in header file\n", argv[iarg]);
			fclose(flt);
			continue;
		}

		insize = GRID_SIZE + EXCESS_OVER;
		if ((insize != latsize) || (latsize != lonsize)) {
			printf("%s skipped, incorrect grid size\n", argv[iarg]);
			fclose(flt);
			continue;
		}

		readsize = insize * sizeof(float);

		// If files have excess overlap skip the initial extra rows.

		for (i = 0; i < (EXCESS_OVER / 2); i++) {
			if (fread(readBuf, 1, readsize, flt) != readsize) {
				printf("%s skipped, bad read from file, errno=%d\n", argv[iarg], errno);
				badfile = 1;
				break;
			}
		}

		if (badfile) {
			fclose(flt);
			continue;
		}

		// Read and copy to buffer array, byte-swapping, re-ordering, and converting to integer along the way.  Row
		// and column order are reversed, input and output have opposite ordering for both.  Float input values are
		// range-checked and rounded to short integers.  Out of range sets the missing-data value.  Note the input
		// header file defines a specific missing-data value, but presumably that will always be out of range so no
		// need to look for that exact value.  Anything out-of-range here is considered missing data.

		hasMissing = 0;
		zeroCount = 0;

		for (lti = (GRID_SIZE - 1); lti >= 0; lti--) {

			if (fread(readBuf, 1, readsize, flt) != readsize) {
				printf("%s skipped, bad read from file, errno=%d\n", argv[iarg], errno);
				badfile = 1;
				break;
			}

			if (bigend) {
#ifdef __BIG_ENDIAN__
				memcpy(dataBuf, readBuf, readsize);
#else
				memswabcpy(dataBuf, readBuf, readsize, sizeof(float));
#endif
			} else {
#ifdef __BIG_ENDIAN__
				memswabcpy(dataBuf, readBuf, readsize, sizeof(float));
#else
				memcpy(dataBuf, readBuf, readsize);
#endif
			}

			// Skip excess overlap columns from the input.

			i = EXCESS_OVER / 2;
			for (lni = (GRID_SIZE - 1); lni >= 0; lni--, i++) {
				if ((dataBuf[i] < -1000.) || (dataBuf[i] > 10000.)) {
					Data[lti][lni] = MISSING_DATA;
					hasMissing = 1;
				} else {
					Data[lti][lni] = (int16_t)rintf(dataBuf[i]);
					if (0 == Data[lti][lni]) {
						zeroCount++;
					}
				}
			}
		}

		fclose(flt);

		if (badfile) {
			continue;
		}

		// Process the grid to fill missing-data regions that presumably should be 0 elevations (sea level).  Missing
		// data often occurs offshore in coastal areas.  But in the output file, missing data is handled at the cell
		// level; one missing point causes an entire cell to be flagged missing and often legitimate data discarded.
		// The logic is kept simple, taking the assumption that there are not going to be large missing areas away
		// from coastal regions.  If the block has missing data, and has more that a few actual 0 elevations, assume
		// it is coastal.  Then just replace any missing-data point with a 0 as long as it's neighboring points are
		// all either missing or 0 (actually +/-2 is allowed, the files sometimes have -1 or -2 in ocean areas).  Even
		// if this mistakenly fills some land area, a missing point adjacent to a non-0 elevation will not be changed
		// and thus would still cause the enclosing cell to be flagged missing.

		if (hasMissing && (zeroCount > 500)) {

			for (lti = 1; lti < (GRID_SIZE - 1); lti++) {

				ltin = lti + 1;
				ltis = lti - 1;

				for (lni = 1; lni < (GRID_SIZE - 1); lni++) {

					if (MISSING_DATA == Data[lti][lni]) {

						lniw = lni + 1;
						lnie = lni - 1;

						d = Data[ltin][lniw];
						if (((d < -1) || (d > 1)) && (d != MISSING_DATA)) continue;
						d = Data[ltin][lni];
						if (((d < -1) || (d > 1)) && (d != MISSING_DATA)) continue;
						d = Data[ltin][lnie];
						if (((d < -1) || (d > 1)) && (d != MISSING_DATA)) continue;
						d = Data[lti][lniw];
						if (((d < -1) || (d > 1)) && (d != MISSING_DATA)) continue;
						d = Data[lti][lnie];
						if (((d < -1) || (d > 1)) && (d != MISSING_DATA)) continue;
						d = Data[ltis][lniw];
						if (((d < -1) || (d > 1)) && (d != MISSING_DATA)) continue;
						d = Data[ltis][lni];
						if (((d < -1) || (d > 1)) && (d != MISSING_DATA)) continue;
						d = Data[ltis][lnie];
						if (((d < -1) || (d > 1)) && (d != MISSING_DATA)) continue;

						Data[lti][lni] = 0;
					}
				}
			}
		}

		// Write the output.

		switch (writetvs(latIndex, lonIndex, &miscount)) {
			default:
				printf("%s skipped, file write error, errno=%d\n", argv[iarg], errno);
				break;
			case 0:
				printf("%s skipped, all cells zero elevation\n", argv[iarg]);
				break;
			case 1:
				printf("%s skipped, all cells missing data\n", argv[iarg]);
				break;
			case 2:
				if (miscount > 0) {
					printf("%s done, %d cells missing data\n", argv[iarg], miscount);
				} else {
					printf("%s done\n", argv[iarg]);
				}
				nfile++;
				break;
		}
	}

	printf("wrote %d new output files\n", nfile);

	// Update/create the block index, start with all files at status "does not exist", list files in the directory and
	// update to "exists", write the new index.  Note this does not flag zero-elevation (sea level) blocks in this
	// index, but the "does not exist" status will cause the lookup to go to the next-lower-resolution database and
	// zero-elevation blocks are flagged in those because the indices are complete and static as distributed.

	memset(blockIndex, TRN_STATUS_NODATA, TRN_FILE_STATUS_SIZE);

	snprintf(cmd, MAX_STRING, "dir %s\\%s", DBASE_DIRECTORY_NAME, TRN_DB_NAME_NED13);
	ls = popen(cmd, "r");

	nfile = 0;

	while (fgetnl(line, MAX_STRING, ls) >= 0) {
		if (11 != strlen(line)) {
			continue;
		}
		if (strncmp(line + 7, ".trn", 4)) {
			continue;
		}
		if ('n' == line[0]) {
			lti = 1;
		} else {
			if ('s' == line[0]) {
				lti = -1;
			} else {
				continue;
			}
		}
		if ('w' == line[3]) {
			lni = 1;
		} else {
			if ('e' == line[3]) {
				lni = -1;
			} else {
				continue;
			}
		}
		line[3] = '\0';
		lti *= atoi(line + 1);
		if ((lti < -90) || (lti > 89)) {
			continue;
		}
		line[7] = '\0';
		lni = atoi(line + 4);
		if ((lni < -180) || (lni > 179)) {
			continue;
		}
		blockIndex[((lti + 90) * 360) + (lni + 180)] = TRN_STATUS_DATA;
		nfile++;
	}

	pclose(ls);

	snprintf(fname, MAX_STRING, "%s/%s/blocks.idx", DBASE_DIRECTORY_NAME, TRN_DB_NAME_NED13);
	idx = fopen(fname, "wb");
	if (!idx) {
		fprintf(stderr, "%s: cannot create block index file\n", argv[0]);
	} else {
		if (fwrite(blockIndex, 1, TRN_FILE_STATUS_SIZE, idx) != TRN_FILE_STATUS_SIZE) {
			fprintf(stderr, "%s: error writing to block index file\n", argv[0]);
		} else {
			printf("%d total files in index\n", nfile);
		}
		fclose(idx);
	}
		
	exit(0);
}


//---------------------------------------------------------------------------------------------------------------------
// Write a file in the output format, reading from the global Data array.

// Arguments:

//   lat, lon  Coordinates of south-east corner of 1x1 degree block, in whole degrees.
//   miscount  Return count of cells flagged as missing-data.

// Return is <0 for error else a result code based on file contents; 0 if all elevations were zero; 1 if the missing
// data code was found in all cells; or 2 if the file contained some data.  For anything but 2, no output file was
// actually created.

static int writetvs(int lat, int lon, int *miscount) {

	TRN_HEADER hdr;
	int i, lni, lti, out, idx, npt, ib, lnlo, lnhi, ltlo, lthi, misdata;
	u_int8_t *pdata;
	char ns, ew, filnm[MAX_STRING];

	static int16_t data[CELL_SIZE * CELL_SIZE];

	// File will be opened the first time it is needed, in case all cells are missing or zero elevation.

	if (lat < 0) {
		ns = 's';
		lti = -lat;
	} else {
		ns = 'n';
		lti = lat;
	}
	if (lon < 0) {
		ew = 'e';
		lni = -lon;
	} else {
		ew = 'w';
		lni = lon;
	}
	snprintf(filnm, MAX_STRING, "%s/%s/%c%02d%c%03d.trn", DBASE_DIRECTORY_NAME, TRN_DB_NAME_NED13, ns, lti, ew, lni);

	out = -1;

	// Set return value, start with all-zero code, if all cells have missing data this will become a missing-file code,
	// if data is written it becomes the file-exists code.

	idx = 0;

	// Set up a few things in the header.

	hdr.magicNumber = TRN_MAGIC_V2_U;
	hdr.fileID = ((lat + 90) * 10000) + ((lon + 180) * 10) + TRN_DB_NUMBER_NED13;
	for (i = 0; i < TRN_CELLS_PER_FILE; i++) {
		hdr.latPointCount[i] = CELL_SIZE;
		hdr.lonPointCount[i] = CELL_SIZE;
	}
	npt = CELL_SIZE * CELL_SIZE;

	// Loop for blocks.

	if (miscount) {
		*miscount = 0;
	}

	for (ib = 0; ib < TRN_CELLS_PER_FILE; ib++) {

		ltlo = ((ib / TRN_CELLS_PER_DEGREE) * (CELL_SIZE - 2)) + 1;
		lthi = ltlo + CELL_SIZE;
		lnlo = ((ib % TRN_CELLS_PER_DEGREE) * (CELL_SIZE - 2)) + 1;
		lnhi = lnlo + CELL_SIZE;

		// Copy values in proper sequence to a linear array for packing.  Check for missing data, just one point means
		// the entire cell is missing; for performance reasons, the extraction code does not support missing points.

		i = 0;
		misdata = 0;
		for (lti = ltlo; lti < lthi; lti++) {
			for (lni = lnlo; lni < lnhi; lni++, i++) {
				data[i] = Data[lti][lni];
				if (MISSING_DATA == data[i]) {
					misdata = 1;
					break;
				}
			}
		}

		if (misdata) {
			hdr.minimumElevation[ib] = 0;
			hdr.recordSize[ib] = 0;
			hdr.recordOffset[ib] = 0;
			hdr.cellFlags[ib] = TRN_NO_DATA_MASK;
			if (0 == idx) {
				idx = 1;
			}
			if (miscount) {
				(*miscount)++;
			}
			continue;
		}

		// Call packing routine.  The result is type 2 if any data needs to be written, or if any of the minimum
		// elevations are not 0; the first time that is seen, open the file and write the header.

		hdr.recordSize[ib] = (u_int32_t)pack(data, npt, &hdr.minimumElevation[ib], &hdr.cellFlags[ib], &pdata);

		if ((out < 0) && ((hdr.recordSize[ib] > 0) || (hdr.minimumElevation[ib] != 0))) {

			if ((out = open(filnm, O_WRONLY | O_CREAT | O_TRUNC, 0666)) < 0) {
				return -1;
			}

			if (write(out, &hdr, sizeof(TRN_HEADER)) != sizeof(TRN_HEADER)) {
				close(out);
				unlink(filnm);
				return -1;
			}

			idx = 2;
		}

		if (0 == hdr.recordSize[ib]) {
			hdr.recordOffset[ib] = 0;
		} else {
			hdr.recordOffset[ib] = lseek(out, 0, SEEK_CUR);
			if (write(out, pdata, hdr.recordSize[ib]) != hdr.recordSize[ib]) {
				close(out);
				unlink(filnm);
				return -1;
			}
		}
	}

	// All done, re-write header if needed.

	if (out >= 0) {
		lseek(out, 0, SEEK_SET);
		if (write(out, &hdr, sizeof(TRN_HEADER)) != sizeof(TRN_HEADER)) {
			close(out);
			unlink(filnm);
			return -1;
		}

		close(out);
	}

	return idx;
}


//---------------------------------------------------------------------------------------------------------------------
// Routine takes pointer to a linear array of elevations for one data block and delta-encodes and bit-packs the data.
// Returns the minimum elevation, the number of bits used per value, the length of the packed string, and a pointer to
// static space containing the packed string.  This assumes that the data will pack, i.e. the bit length will be 15 or
// less.  A bit length of 0 is legal, that will return a null string.

// Arguments:

//   data    The data.
//   npt     Number of points in input.
//   minelv  Returns minimum elevation.
//   bitlen  Returns bit length used in packing.
//   packed  Returns pointer to packed string.

// Return is length of packed string.

static int pack(int16_t *data, int npt, int16_t *minelv, u_int8_t *bitlen, u_int8_t **packed) {

	// Shift tables for the packing routine.

	static int shft[15][8] = {
		{0, 1, 2, 3, 4, 5, 6, 7},
		{0, 2, 4, 6, 0, 2, 4, 6},
		{0, 3, 6, 1, 4, 7, 2, 5},
		{0, 4, 0, 4, 0, 4, 0, 4},
		{0, 5, 2, 7, 4, 1, 6, 3},
		{0, 6, 4, 2, 0, 6, 4, 2},
		{0, 7, 6, 5, 4, 3, 2, 1},
		{0, 0, 0, 0, 0, 0, 0, 0},
		{0, 1, 2, 3, 4, 5, 6, 7},
		{0, 2, 4, 6, 0, 2, 4, 6},
		{0, 3, 6, 1, 4, 7, 2, 5},
		{0, 4, 0, 4, 0, 4, 0, 4},
		{0, 5, 2, 7, 4, 1, 6, 3},
		{0, 6, 4, 2, 0, 6, 4, 2},
		{0, 7, 6, 5, 4, 3, 2, 1}
	};
	static int incr[15][8] = {
		{0, 0, 0, 0, 0, 0, 0, 1},
		{0, 0, 0, 1, 0, 0, 0, 1},
		{0, 0, 1, 0, 0, 1, 0, 1},
		{0, 1, 0, 1, 0, 1, 0, 1},
		{0, 1, 0, 1, 1, 0, 1, 1},
		{0, 1, 1, 1, 0, 1, 1, 1},
		{0, 1, 1, 1, 1, 1, 1, 1},
		{1, 1, 1, 1, 1, 1, 1, 1},
		{1, 1, 1, 1, 1, 1, 1, 2},
		{1, 1, 1, 2, 1, 1, 1, 2},
		{1, 1, 2, 1, 1, 2, 1, 2},
		{1, 2, 1, 2, 1, 2, 1, 2},
		{1, 2, 1, 2, 2, 1, 2, 2},
		{1, 2, 2, 2, 1, 2, 2, 2},
		{1, 2, 2, 2, 2, 2, 2, 2}
	};

	// Static space for the packed data, allow for worst case of no packing.

	static u_int8_t packstr[CELL_SIZE * CELL_SIZE * 2];

	// Union used for packing.

	union {
		u_int32_t i;
		u_int8_t b[4];
	} acc;

	// Misc. local variables.

	int minel, maxel, delta, nbit, *shf, *inc, ib, ist, i, bitcnt, packlen;
	u_int32_t val, mask;

	// Scan the input data to determine min/max.

	minel = data[0];
	maxel = data[0];
	for (i = 1; i < npt; i++) {
		if (data[i] < minel) {
			minel = data[i];
		}
		if (data[i] > maxel) {
			maxel = data[i];
		}
	}

	// Compute bit length.

	delta = maxel - minel;
	nbit = 0;
	while (delta) {
		nbit++;
		delta >>= 1;
	}

	// Check for zero bit length.

	if (nbit == 0) {
		*minelv = (int16_t)minel;
		*bitlen = 0;
		*packed = NULL;
		return(0);
	}

	// Initialize for packing.

	bitcnt = npt * nbit;
	packlen = bitcnt / 8;
	if (bitcnt % 8) {
		packlen++;
	}
	mask = (1 << nbit) - 1;
	shf = shft[nbit - 1];
	inc = incr[nbit - 1];
	acc.i = 0;
	ib = 0;
	ist = 0;

	// Do it.

	for (i = 0; i < npt; i++) {
		val = data[i] - minel;
		acc.i |= (val << shf[ist]);
		if (inc[ist] > 0) {
#ifdef __BIG_ENDIAN__
			packstr[ib++] = acc.b[3];
#else
			packstr[ib++] = acc.b[0];
#endif
			acc.i >>= 8;
			if (inc[ist] > 1) {
#ifdef __BIG_ENDIAN__
				packstr[ib++] = acc.b[3];
#else
				packstr[ib++] = acc.b[0];
#endif
				acc.i >>= 8;
			}
		}
		if (++ist > 7) {
			ist = 0;
		}
	}
	if (ib < packlen) {
#ifdef __BIG_ENDIAN__
		packstr[ib++] = acc.b[3];
#else
		packstr[ib++] = acc.b[0];
#endif
	}

	// Done, return various values.

	*minelv = (int16_t)minel;
	*bitlen = (u_int8_t)nbit;
	*packed = packstr;
	return(packlen);
}
