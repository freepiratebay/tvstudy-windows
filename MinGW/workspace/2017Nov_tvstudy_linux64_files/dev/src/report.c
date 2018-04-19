//
//  report.c
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.


// Write text reports of study results.


#include "tvstudy.h"
#include "codeid/codeid.h"
#include <ctype.h>


//---------------------------------------------------------------------------------------------------------------------

static void write_OET74_report(FILE *reportFile);
static void write_source_parameters(SOURCE *source, FILE *paramsFile);
static void write_pattern(SOURCE *source, FILE *paramsFile);

char *CountryName[MAX_COUNTRY] = {"US", "CA", "MX"};


//---------------------------------------------------------------------------------------------------------------------
// Write a report preamble to a file, used for several different output file types.  A study must always be open, if a
// scenario is also open the scenario name may also be included.

// Arguments:

//   reportFlag    Format, REPORT_FILE_*
//   reportFile    File output stream, never NULL
//   showExtDb     Include name of the study station data set, if any
//   showScenario  Include scenario name in detail format, if any

void write_report_preamble(int reportFlag, FILE *reportFile, int showExtDb, int showScenario) {

	if (!StudyKey) {
		log_error("write_report_preamble() called with no study open");
		return;
	}

	if (REPORT_FILE_DETAIL == reportFlag) {

		fprintf(reportFile, "tvstudy v%s (%s)\n\n", TVSTUDY_VERSION, CODE_ID);
		fprintf(reportFile, "    Database: %s\n", HostDbName);
		if (showExtDb && ExtDbName[0]) {
			fprintf(reportFile, "Station Data: %s\n", ExtDbName);
		}
		fprintf(reportFile, "       Study: %s\n", StudyName);
		fprintf(reportFile, "       Model: %s\n", get_model_name(PropagationModel));
		if (showScenario && ScenarioKey) {
			fprintf(reportFile, "    Scenario: %s\n", ScenarioName);
		}
		fprintf(reportFile, "       Start: %s\n", log_open_time());

	} else {

		fprintf(reportFile, "tvstudy v%s (%s)\n", TVSTUDY_VERSION, CODE_ID);
		if (showExtDb && ExtDbName[0]) {
			fprintf(reportFile, "Database: %s,  Station Data: %s,  Study: %s,  Model: %s\n", HostDbName, ExtDbName,
				StudyName, get_model_name(PropagationModel));
		} else {
			fprintf(reportFile, "Database: %s,  Study: %s,  Model: %s\n", HostDbName, StudyName,
				get_model_name(PropagationModel));
		}
		fprintf(reportFile, "Start: %s\n", log_open_time());
	}

	if (RunComment) {
		fputs("Comment:\n", reportFile);
		int c = 0, i = 0, j = 0, n = strlen(RunComment);
		while (j < n) {
			do {
				j++;
			} while ((j < n) && !isspace(RunComment[j]));
			if ((c + (j - i)) > 120) {
				fputc('\n', reportFile);
				c = 0;
			}
			while (i < j) {
				if ((c > 0) || !isspace(RunComment[i])) {
					fputc(RunComment[i], reportFile);
					if ('\n' == RunComment[i]) {
						c = 0;
					} else {
						c++;
					}
				}
				i++;
			}
		}
		if (c > 0) {
			fputc('\n', reportFile);
		}
	}

	if (UserTerrainUsed) {
		fputs("\n**Unofficial terrain data was used for this study.\n", reportFile);
	}

	fputc('\n', reportFile);
}


//---------------------------------------------------------------------------------------------------------------------
// Write the text report for a scenario.

// Arguments:

//   reportFlag  Report format, REPORT_FILE_*
//   optionFlag  Specific value of the output flag passed from UI.
//   reportFile  File output stream, never NULL
//   doHeader    Include report table header

void write_report(int reportFlag, int optionFlag, FILE *reportFile, int doHeader) {

	if (!ScenarioKey) {
		log_error("write_report() called with no scenario loaded");
		return;
	}

	if ((REPORT_FILE_DETAIL == reportFlag) && (STUDY_TYPE_TV_OET74 == StudyType)) {
		write_OET74_report(reportFile);
	}

	if (doHeader) {

		if (REPORT_FILE_DETAIL == reportFlag) {

			if (STUDY_MODE_GRID == StudyMode) {

				fputs("\nDesired station                                   Service area       Terrain-limited     Interference-free\n\n", reportFile);
				fputs("     Undesired station                           Total interference   Unique interference\n", reportFile);

			} else {

				fputs("Point name                  Latitude       Longitude  Ground el  Rcvr hgt\n", reportFile);
				fputs("  Desired station                        Bearing   Distance        Field  Conditions\n",
					reportFile);
				fputs("    Undesired station                          Field         D/U    Reqd D/U  Conditions\n\n",
					reportFile);
			}

		} else {

			if (STUDY_MODE_GRID == StudyMode) {

				fputs("\nScenario\n", reportFile);
				fputs("    Desired station                                   Service area       Terrain-limited     Interference-free\n\n", reportFile);

			} else {

				fputs("Point name                  Latitude       Longitude\n", reportFile);
				fputs("  Desired station                            Field  Conditions\n\n", reportFile);
			}
		}
	}

	// In points mode this is only used to get the headings.

	if (STUDY_MODE_POINTS == StudyMode) {
		return;
	}

	if (REPORT_FILE_SUMMARY == reportFlag) {
		fprintf(reportFile, "%s\n", ScenarioName);
		if (STUDY_TYPE_TV_OET74 == StudyType) {
			write_OET74_report(reportFile);
		}
	}

	SOURCE *usource;
	UNDESIRED *undesireds;
	int sourceIndex, undesiredCount, undesiredIndex, firstUnd, countryIndex, countryKey;
	DES_TOTAL *dtot;
	UND_TOTAL *utot;

	// Report scenario composite coverage if tallied.

	if (DoComposite) {

		for (countryIndex = 0; countryIndex < MAX_COUNTRY; countryIndex++) {

			countryKey = countryIndex + 1;
			dtot = CompositeTotals + countryIndex;
			if (0. == dtot->contourArea) {
				continue;
			}

			if (REPORT_FILE_DETAIL == reportFlag) {
				fputc('\n', reportFile);
			} else {
				fputs("     ", reportFile);
			}

			if (dtot->contourPop < 0) {
				fputs("Composite coverage                              (unrestricted)", reportFile);
			} else {
				fprintf(reportFile, "Composite coverage                        %8.1f %11s", dtot->contourArea,
					pop_commas(dtot->contourPop));
			}
			fprintf(reportFile, "  %8.1f %11s", dtot->serviceArea, pop_commas(dtot->servicePop));
			fprintf(reportFile, "  %8.1f %11s", dtot->ixFreeArea, pop_commas(dtot->ixFreePop));

			switch (countryKey) {
				case CNTRY_USA:
				default:
					fputs("  (in U.S.)\n", reportFile);
					break;
				case CNTRY_CAN:
					fputs("  (in Canada)\n", reportFile);
					break;
				case CNTRY_MEX:
					fputs("  (in Mexico)\n", reportFile);
					break;
			}
		}
	}

	// Report results for all desired stations in the scenario.

	SOURCE *source = Sources;
	for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {

		if (!source->isDesired) {
			continue;
		}

		undesireds = source->undesireds;
		undesiredCount = source->undesiredCount;

		for (countryIndex = 0; countryIndex < MAX_COUNTRY; countryIndex++) {

			countryKey = countryIndex + 1;
			dtot = source->totals + countryIndex;
			if ((countryKey != source->countryKey) && (0. == dtot->contourArea)) {
				continue;
			}

			if (REPORT_FILE_DETAIL == reportFlag) {
				fputc('\n', reportFile);
			} else {
				fputs("     ", reportFile);
			}

			if (dtot->contourPop < 0) {
				fprintf(reportFile, "%-40.40s        (unrestricted)", source_label(source));
			} else {
				fprintf(reportFile, "%-40.40s  %8.1f %11s", source_label(source), dtot->contourArea,
					pop_commas(dtot->contourPop));
			}
			fprintf(reportFile, "  %8.1f %11s", dtot->serviceArea, pop_commas(dtot->servicePop));
			fprintf(reportFile, "  %8.1f %11s", dtot->ixFreeArea, pop_commas(dtot->ixFreePop));

			if (countryKey == source->countryKey) {
				fputc('\n', reportFile);
			} else {

				switch (countryKey) {
					case CNTRY_USA:
					default:
						fputs("  (in U.S.)\n", reportFile);
						break;
					case CNTRY_CAN:
						fputs("  (in Canada)\n", reportFile);
						break;
					case CNTRY_MEX:
						fputs("  (in Mexico)\n", reportFile);
						break;
				}
			}

			if (REPORT_FILE_SUMMARY == reportFlag) {
				continue;
			}

			firstUnd = 1;

			// In an OET-74 study interference from all wireless sources is composited and reported as a single total.
			// The individual sources are skipped in the main reporting loop below.

			if (STUDY_TYPE_TV_OET74 == StudyType) {

				utot = WirelessUndesiredTotals + countryIndex;

				if ((utot->ixArea > 0.) || (utot->report && (REPORT_DETAIL_ALL_UND == optionFlag))) {

					if (firstUnd) {
						fputc('\n', reportFile);
						firstUnd = 0;
					}

					fprintf(reportFile, "     Wireless base stations                    %8.1f %11s", utot->ixArea,
						pop_commas(utot->ixPop));
					fprintf(reportFile, "  %8.1f %11s\n", utot->uniqueIxArea, pop_commas(utot->uniqueIxPop));
				}
			}

			// Report DTS self-interference if checked.

			if (source->isParent && Params.CheckSelfInterference) {

				utot = source->selfIxTotals + countryIndex;

				if ((utot->ixArea > 0.) || (utot->report && (REPORT_DETAIL_ALL_UND == optionFlag))) {

					if (firstUnd) {
						fputc('\n', reportFile);
						firstUnd = 0;
					}

					fprintf(reportFile, "     Self-interference                         %8.1f %11s", utot->ixArea,
						pop_commas(utot->ixPop));
					fprintf(reportFile, "  %8.1f %11s\n", utot->uniqueIxArea, pop_commas(utot->uniqueIxPop));
				}
			}

			// Loop over other undesireds.

			for (undesiredIndex = 0; undesiredIndex < undesiredCount; undesiredIndex++) {

				usource = SourceKeyIndex[undesireds[undesiredIndex].sourceKey];
				if (!usource) {
					log_error("Source structure index is corrupted");
					exit(1);
				}
				if (RECORD_TYPE_WL == usource->recordType) {
					continue;
				}

				utot = undesireds[undesiredIndex].totals + countryIndex;
				if (!utot->report || ((0. == utot->ixArea) && (REPORT_DETAIL_IX_ONLY == optionFlag))) {
					continue;
				}

				if (firstUnd) {
					fputc('\n', reportFile);
					firstUnd = 0;
				}

				fprintf(reportFile, "     %-40.40s  %8.1f %11s", source_label(usource), utot->ixArea,
					pop_commas(utot->ixPop));
				fprintf(reportFile, "  %8.1f %11s\n", utot->uniqueIxArea, pop_commas(utot->uniqueIxPop));
			}
		}
	}
}


//---------------------------------------------------------------------------------------------------------------------
// Special reporting for an OET-74 study, report any wireless undesireds inside the desired TV service area, see
// find_undesired(), report the total count of wireless undesireds, and report total and unique wireless interference
// summed for all countries.  There should be exactly one desired TV per scenario in this study type.  Much of this is
// also sent out as status messages for immediate display in the UI.

static void write_OET74_report(FILE *reportFile) {

	int sourceIndex;
	SOURCE *source = Sources;
	for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {
		if (source->isDesired) {
			break;
		}
	}
	if (!source) {
		return;
	}

	char mesg[MAX_STRING];

	snprintf(mesg, MAX_STRING, "Scenario: %s", ScenarioName);
	status_message(STATUS_KEY_REPORT, mesg);

	snprintf(mesg, MAX_STRING, "Desired station: %s", source_label(source));
	status_message(STATUS_KEY_REPORT, mesg);

	snprintf(mesg, MAX_STRING, "Wireless frequency %.3f MHz, bandwidth %.3f MHz", Params.WirelessFrequency,
		Params.WirelessBandwidth);
	status_message(STATUS_KEY_REPORT, mesg);
	fprintf(reportFile, "%s\n", mesg);

	UNDESIRED *undesireds = source->undesireds;
	int undesiredCount = source->undesiredCount;

	SOURCE *usource;
	int undesiredIndex, wirelessCount = 0;

	for (undesiredIndex = 0; undesiredIndex < undesiredCount; undesiredIndex++) {
		usource = SourceKeyIndex[undesireds[undesiredIndex].sourceKey];
		if (!usource) {
			log_error("Source structure index is corrupted");
			exit(1);
		}
		if (RECORD_TYPE_WL == usource->recordType) {
			wirelessCount++;
			if (undesireds[undesiredIndex].insideServiceArea) {
				snprintf(mesg, MAX_STRING, "**Wireless station inside TV service area: %s", source_label(usource));
				status_message(STATUS_KEY_REPORT, mesg);
				fprintf(reportFile, "%s\n", mesg);
			}
		}
	}

	snprintf(mesg, MAX_STRING, "%d wireless stations included in interference analysis", wirelessCount);
	status_message(STATUS_KEY_REPORT, mesg);
	fprintf(reportFile, "%s\n", mesg);

	double areaTot = 0., areaUniq = 0.;
	int popTot = 0, popUniq = 0, countryIndex;

	for (countryIndex = 0; countryIndex < MAX_COUNTRY; countryIndex++) {
		areaTot += WirelessUndesiredTotals[countryIndex].ixArea;
		popTot += WirelessUndesiredTotals[countryIndex].ixPop;
		areaUniq += WirelessUndesiredTotals[countryIndex].uniqueIxArea;
		popUniq += WirelessUndesiredTotals[countryIndex].uniqueIxPop;
	}

	snprintf(mesg, MAX_STRING, "Total wireless interference: %.1f km^2, %s persons", areaTot, pop_commas(popTot));
	status_message(STATUS_KEY_REPORT, mesg);
	if (areaUniq < areaTot) {
		snprintf(mesg, MAX_STRING, "Unique wireless interference: %.1f km^2, %s persons", areaUniq,
			pop_commas(popUniq));
		status_message(STATUS_KEY_REPORT, mesg);
	}
	status_message(STATUS_KEY_REPORT, "");
}


//---------------------------------------------------------------------------------------------------------------------
// Write a report for one scenario pair comparison to a text report file.

// Arguments:

//   thePair     Scenario pair data to report
//   reportFile  File output stream, never NULL
//   doHeader    Include table header

void write_pair_report(SCENARIO_PAIR *thePair, FILE *reportFile, int doHeader) {

	if (!thePair->didStudyA || !thePair->didStudyB) {
		return;
	}

	if (doHeader) {
		fputs("\n\nScenario comparisons\n\n", reportFile);
		fputs("Case                                Before                 After     Percent New IX\n\n", reportFile);
	}

	int countryIndex, countryKey;
	DES_TOTAL *dtotA, *dtotB;

	for (countryIndex = 0; countryIndex < MAX_COUNTRY; countryIndex++) {

		countryKey = countryIndex + 1;
		dtotA = thePair->totalsA + countryIndex;
		dtotB = thePair->totalsB + countryIndex;
		if ((countryKey != thePair->sourceB->countryKey) && (0. == dtotA->contourArea) && (0. == dtotB->contourArea)) {
			continue;
		}

		fprintf(reportFile, "%-20.20s  %8.1f %11s", thePair->name, dtotA->ixFreeArea, pop_commas(dtotA->ixFreePop));
		fprintf(reportFile, "  %8.1f %11s  %7.2f %9.2f", dtotB->ixFreeArea, pop_commas(dtotB->ixFreePop),
			thePair->areaPercent[countryIndex], thePair->popPercent[countryIndex]);

		if (countryKey == thePair->sourceB->countryKey) {
			fputc('\n', reportFile);
		} else {

			switch (countryKey) {
				case CNTRY_USA:
				default:
					fputs("  (in U.S.)\n", reportFile);
					break;
				case CNTRY_CAN:
					fputs("  (in Canada)\n", reportFile);
					break;
				case CNTRY_MEX:
					fputs("  (in Mexico)\n", reportFile);
					break;
			}
		}
	}
}


//---------------------------------------------------------------------------------------------------------------------
// Write preamble information to a CSV file.

// Arguments:

//   csvFlag       CSV format, CSV_FILE_*
//   csvFile       File output stream, never NULL
//   showExtDb     Show station data name, if any
//   showScenario  Show scenario name in detail format, if any

void write_csv_preamble(int csvFlag, FILE *csvFile, int showExtDb, int showScenario) {

	if (!StudyKey) {
		log_error("write_csv_preamble() called with no study open");
		return;
	}

	if (CSV_FILE_DETAIL == csvFlag) {

		fprintf(csvFile, "tvstudy v%s (%s)\n", TVSTUDY_VERSION, CODE_ID);

		fprintf(csvFile, "Database,\"%s\"\n", HostDbName);
		if (showExtDb && ExtDbName[0]) {
			fprintf(csvFile, "StationData,\"%s\"\n", ExtDbName);
		}
		fprintf(csvFile, "Study,\"%s\"\n", StudyName);
		fprintf(csvFile, "Model,\"%s\"\n", get_model_name(PropagationModel));
		if (showScenario && ScenarioKey) {
			fprintf(csvFile, "Scenario,\"%s\"\n", ScenarioName);
		}
		fprintf(csvFile, "Start,\"%s\"\n", log_open_time());
		fputs("Comment,", csvFile);

	} else {

		if (showExtDb && ExtDbName[0]) {
			fprintf(csvFile, "\"%s (%s)\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",", TVSTUDY_VERSION, CODE_ID, HostDbName,
				ExtDbName, StudyName, get_model_name(PropagationModel), log_open_time());
		} else {
			fprintf(csvFile, "\"%s (%s)\",\"%s\",\"%s\",\"%s\",\"%s\",", TVSTUDY_VERSION, CODE_ID, HostDbName,
				StudyName, get_model_name(PropagationModel), log_open_time());
		}
	}

	if (RunComment) {
		int i;
		fputc('"', csvFile);
		for (i = 0; i < strlen(RunComment); i++) {
			if ('"' == RunComment[i]) {
				fputc('"', csvFile);
			}
			fputc(RunComment[i], csvFile);
		}
		fputc('"', csvFile);
	}
	fputc('\n', csvFile);
}


//---------------------------------------------------------------------------------------------------------------------
// Write the CSV results for a scenario.

// Arguments:

//   csvFlag     CSV format, CSV_FILE_*
//   optionFlag  Specific value of the output flag passed from UI.
//   csvFile     File output stream, never NULL
//   doHeader    Include data header rows

void write_csv(int csvFlag, int optionFlag, FILE *csvFile, int doHeader) {

	if (!ScenarioKey) {
		log_error("write_csv() called with no scenario loaded");
		return;
	}

	if (doHeader) {

		if (CSV_FILE_DETAIL == csvFlag) {

			if (STUDY_MODE_GRID == StudyMode) {

				fputs("Desired,,,,,,,ServiceArea,,,TerrainLimited,,,InterferenceFree,,,,", csvFile);
				fputs("Undesired,,,,,,TotalInterference,,,UniqueInterference\n", csvFile);
				fputs("FacID,Ch,Call,FileNumber,City,St,InCountry,", csvFile);
				fputs("Area,Population,Households,Area,Population,Households,Area,Population,Households,,", csvFile);
				fputs("FacID,Ch,Call,FileNumber,City,St,", csvFile);
				fputs("Area,Population,Households,Area,Population,Households\n", csvFile);

			} else {

				fputs("Point,,,,,Desired,,,,,,,,,,,Undesired\n", csvFile);
				fputs("Name,Latitude,Longitude,GroundElv,RecvHgt,", csvFile);
				fputs("FacID,Ch,Call,FileNumber,City,St,Cntry,Bear,Dist,Field,InSA,Serv,", csvFile);
				fputs("FacID,Ch,Call,FileNumber,City,St,Field,DU,ReqDU,IX\n", csvFile);
			}

		} else {

			if (STUDY_MODE_GRID == StudyMode) {

				fputs("Scenario,FacID,FileNumber,Cntry,Type,Chan,InCntry,,Contour,,,Service,,,IxFree\n", csvFile);

			} else {

				fputs("PtName,Latitude,Longitude,FacID,FileNumber,Cntry,Type,Ch,Field,InSA,Serv,IX\n", csvFile);
			}
		}
	}

	// In points mode this is only used to get the headings.

	if (STUDY_MODE_POINTS == StudyMode) {
		return;
	}

	SOURCE *source, *usource;
	UNDESIRED *undesireds;
	int sourceIndex, undesiredIndex, undesiredCount, countryIndex, countryKey, firstUnd;
	DES_TOTAL *dtot;
	UND_TOTAL *utot;

	// Report scenario composite coverage if tallied.

	if (DoComposite) {

		for (countryIndex = 0; countryIndex < MAX_COUNTRY; countryIndex++) {

			countryKey = countryIndex + 1;
			dtot = CompositeTotals + countryIndex;
			if (0. == dtot->contourArea) {
				continue;
			}

			if (CSV_FILE_SUMMARY == csvFlag) {

				if (dtot->contourPop < 0) {
					fprintf(csvFile, "\"%s\",,Composite,,,,%d,,,,%.1f,%d,%d,%.1f,%d,%d\n", ScenarioName,
						countryKey, dtot->serviceArea, dtot->servicePop, dtot->serviceHouse, dtot->ixFreeArea,
						dtot->ixFreePop, dtot->ixFreeHouse);
				} else {
					fprintf(csvFile, "\"%s\",,Composite,,,,%d,%.1f,%d,%d,%.1f,%d,%d,%.1f,%d,%d\n", ScenarioName,
						countryKey, dtot->contourArea, dtot->contourPop, dtot->contourHouse, dtot->serviceArea,
						dtot->servicePop, dtot->serviceHouse, dtot->ixFreeArea, dtot->ixFreePop, dtot->ixFreeHouse);
				}

			} else {

				if (dtot->contourPop < 0) {
					fprintf(csvFile, ",,,Composite,,,%s,,,,%.1f,%d,%d,%.1f,%d,%d\n", CountryName[countryIndex],
						dtot->serviceArea, dtot->servicePop, dtot->serviceHouse, dtot->ixFreeArea, dtot->ixFreePop,
						dtot->ixFreeHouse);
				} else {
					fprintf(csvFile, ",,,Composite,,,%s,%.1f,%d,%d,%.1f,%d,%d,%.1f,%d,%d\n", CountryName[countryIndex],
						dtot->contourArea, dtot->contourPop, dtot->contourHouse, dtot->serviceArea, dtot->servicePop,
						dtot->serviceHouse, dtot->ixFreeArea, dtot->ixFreePop, dtot->ixFreeHouse);
				}
			}
		}
	}

	source = Sources;
	for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {

		if (!source->isDesired) {
			continue;
		}

		undesireds = source->undesireds;
		undesiredCount = source->undesiredCount;

		for (countryIndex = 0; countryIndex < MAX_COUNTRY; countryIndex++) {

			countryKey = countryIndex + 1;
			dtot = source->totals + countryIndex;
			if ((countryKey != source->countryKey) && (0. == dtot->contourArea)) {
				continue;
			}

			if (CSV_FILE_SUMMARY == csvFlag) {
				if (dtot->contourPop < 0) {
					fprintf(csvFile, "\"%s\",%d,\"%s\",%d,%d,%d,%d,,,,%.1f,%d,%d,%.1f,%d,%d\n", ScenarioName,
						source->facility_id, source->fileNumber, source->countryKey, source->serviceTypeKey,
						source->channel, countryKey, dtot->serviceArea, dtot->servicePop, dtot->serviceHouse,
						dtot->ixFreeArea, dtot->ixFreePop, dtot->ixFreeHouse);
				} else {
					fprintf(csvFile, "\"%s\",%d,\"%s\",%d,%d,%d,%d,%.1f,%d,%d,%.1f,%d,%d,%.1f,%d,%d\n", ScenarioName,
						source->facility_id, source->fileNumber, source->countryKey, source->serviceTypeKey,
						source->channel, countryKey, dtot->contourArea, dtot->contourPop, dtot->contourHouse,
						dtot->serviceArea, dtot->servicePop, dtot->serviceHouse, dtot->ixFreeArea, dtot->ixFreePop,
						dtot->ixFreeHouse);
				}
				continue;
			}

			firstUnd = 1;

			if (STUDY_TYPE_TV_OET74 == StudyType) {

				utot = WirelessUndesiredTotals + countryIndex;

				if ((utot->ixArea > 0.) || (utot->report && (CSV_DETAIL_ALL_UND == optionFlag))) {

					if (dtot->contourPop < 0) {
						fprintf(csvFile,
				"%d,%d,\"%s\",\"%s\",\"%s\",%s,%s,,,,%.1f,%d,%d,%.1f,%d,%d,,,%.2f,Wireless,,,,%.1f,%d,%d,%.1f,%d,%d\n",
							source->facility_id, source->channel, source->callSign, source->fileNumber, source->city,
							source->state, CountryName[countryIndex], dtot->serviceArea, dtot->servicePop,
							dtot->serviceHouse, dtot->ixFreeArea, dtot->ixFreePop, dtot->ixFreeHouse,
							Params.WirelessFrequency, utot->ixArea, utot->ixPop, utot->ixHouse, utot->uniqueIxArea,
							utot->uniqueIxPop, utot->uniqueIxHouse);
					} else {
						fprintf(csvFile,
		"%d,%d,\"%s\",\"%s\",\"%s\",%s,%s,%.1f,%d,%d,%.1f,%d,%d,%.1f,%d,%d,,,%.2f,Wireless,,,,%.1f,%d,%d,%.1f,%d,%d\n",
							source->facility_id, source->channel, source->callSign, source->fileNumber, source->city,
							source->state, CountryName[countryIndex], dtot->contourArea, dtot->contourPop,
							dtot->contourHouse, dtot->serviceArea, dtot->servicePop, dtot->serviceHouse,
							dtot->ixFreeArea, dtot->ixFreePop, dtot->ixFreeHouse, Params.WirelessFrequency,
							utot->ixArea, utot->ixPop, utot->ixHouse, utot->uniqueIxArea, utot->uniqueIxPop,
							utot->uniqueIxHouse);
					}

					firstUnd = 0;
				}
			}

			if (source->isParent && Params.CheckSelfInterference) {

				utot = source->selfIxTotals + countryIndex;

				if ((utot->ixArea > 0.) || (utot->report && (CSV_DETAIL_ALL_UND == optionFlag))) {

					if (firstUnd) {

						if (dtot->contourPop < 0) {
							fprintf(csvFile,
	"%d,%d,\"%s\",\"%s\",\"%s\",%s,%s,,,,%.1f,%d,%d,%.1f,%d,%d,,,,Self-interference,,,,%.1f,%d,%d,%.1f,%d,%d\n",
								source->facility_id, source->channel, source->callSign, source->fileNumber,
								source->city, source->state, CountryName[countryIndex], dtot->serviceArea,
								dtot->servicePop, dtot->serviceHouse, dtot->ixFreeArea, dtot->ixFreePop,
								dtot->ixFreeHouse, utot->ixArea, utot->ixPop, utot->ixHouse, utot->uniqueIxArea,
								utot->uniqueIxPop, utot->uniqueIxHouse);
						} else {
							fprintf(csvFile,
	"%d,%d,\"%s\",\"%s\",\"%s\",%s,%s,%.1f,%d,%d,%.1f,%d,%d,%.1f,%d,%d,,,,Self-interference,,,,%.1f,%d,%d,%.1f,%d,%d\n",
								source->facility_id, source->channel, source->callSign, source->fileNumber,
								source->city, source->state, CountryName[countryIndex], dtot->contourArea,
								dtot->contourPop, dtot->contourHouse, dtot->serviceArea, dtot->servicePop,
								dtot->serviceHouse, dtot->ixFreeArea, dtot->ixFreePop, dtot->ixFreeHouse,
								utot->ixArea, utot->ixPop, utot->ixHouse, utot->uniqueIxArea, utot->uniqueIxPop,
								utot->uniqueIxHouse);
						}

						firstUnd = 0;

					} else {

						fprintf(csvFile, ",,,,,,,,,,,,,,,,,,,Self-interference,,,,%.1f,%d,%d,%.1f,%d,%d\n",
							utot->ixArea, utot->ixPop, utot->ixHouse, utot->uniqueIxArea, utot->uniqueIxPop,
							utot->uniqueIxHouse);
					}
				}
			}

			for (undesiredIndex = 0; undesiredIndex < undesiredCount; undesiredIndex++) {

				usource = SourceKeyIndex[undesireds[undesiredIndex].sourceKey];
				if (!usource) {
					log_error("Source structure index is corrupted");
					exit(1);
				}
				if (RECORD_TYPE_WL == usource->recordType) {
					continue;
				}

				utot = undesireds[undesiredIndex].totals + countryIndex;
				if (!utot->report || ((0. == utot->ixArea) && (CSV_DETAIL_IX_ONLY == optionFlag))) {
					continue;
				}

				if (firstUnd) {
					if (dtot->contourPop < 0) {
						fprintf(csvFile,
	"%d,%d,\"%s\",\"%s\",\"%s\",%s,%s,,,,%.1f,%d,%d,%.1f,%d,%d,,%d,%d,\"%s\",\"%s\",\"%s\",%s,%.1f,%d,%d,%.1f,%d,%d\n",
							source->facility_id, source->channel, source->callSign, source->fileNumber, source->city,
							source->state, CountryName[countryIndex], dtot->serviceArea, dtot->servicePop,
							dtot->serviceHouse, dtot->ixFreeArea, dtot->ixFreePop, dtot->ixFreeHouse,
							usource->facility_id, usource->channel, usource->callSign, usource->fileNumber,
							usource->city, usource->state, utot->ixArea, utot->ixPop, utot->ixHouse,
							utot->uniqueIxArea, utot->uniqueIxPop, utot->uniqueIxHouse);
					} else {
						fprintf(csvFile,
"%d,%d,\"%s\",\"%s\",\"%s\",%s,%s,%.1f,%d,%d,%.1f,%d,%d,%.1f,%d,%d,,%d,%d,\"%s\",\"%s\",\"%s\",%s,%.1f,%d,%d,%.1f,%d,%d\n",
							source->facility_id, source->channel, source->callSign, source->fileNumber, source->city,
							source->state, CountryName[countryIndex], dtot->contourArea, dtot->contourPop,
							dtot->contourHouse, dtot->serviceArea, dtot->servicePop, dtot->serviceHouse,
							dtot->ixFreeArea, dtot->ixFreePop, dtot->ixFreeHouse, usource->facility_id,
							usource->channel, usource->callSign, usource->fileNumber, usource->city, usource->state,
							utot->ixArea, utot->ixPop, utot->ixHouse, utot->uniqueIxArea, utot->uniqueIxPop,
							utot->uniqueIxHouse);
					}
					firstUnd = 0;
				} else {
					fprintf(csvFile, ",,,,,,,,,,,,,,,,,%d,%d,\"%s\",\"%s\",\"%s\",\"%s\",%.1f,%d,%d,%.1f,%d,%d\n",
						usource->facility_id, usource->channel, usource->callSign, usource->fileNumber, usource->city,
						usource->state, utot->ixArea, utot->ixPop, utot->ixHouse, utot->uniqueIxArea,
						utot->uniqueIxPop, utot->uniqueIxHouse);
				}
			}

			if (firstUnd) {
				if (dtot->contourPop < 0) {
					fprintf(csvFile, "%d,%d,\"%s\",\"%s\",\"%s\",%s,%s,,,,%.1f,%d,%d,%.1f,%d,%d\n",
						source->facility_id, source->channel, source->callSign, source->fileNumber, source->city,
						source->state, CountryName[countryIndex], dtot->serviceArea, dtot->servicePop,
						dtot->serviceHouse, dtot->ixFreeArea, dtot->ixFreePop, dtot->ixFreeHouse);
				} else {
					fprintf(csvFile, "%d,%d,\"%s\",\"%s\",\"%s\",%s,%s,%.1f,%d,%d,%.1f,%d,%d,%.1f,%d,%d\n",
						source->facility_id, source->channel, source->callSign, source->fileNumber, source->city,
						source->state, CountryName[countryIndex], dtot->contourArea, dtot->contourPop,
						dtot->contourHouse, dtot->serviceArea, dtot->servicePop, dtot->serviceHouse, dtot->ixFreeArea,
						dtot->ixFreePop, dtot->ixFreeHouse);
				}
			}
		}
	}
}


//---------------------------------------------------------------------------------------------------------------------
// Write a scenario pair comparison to a CSV file.

// Arguments:

//   thePair   Scenario pair data to report
//   csvFile   File output stream, never NULL
//   doHeader  Include header rows

void write_pair_csv(SCENARIO_PAIR *thePair, FILE *csvFile, int doHeader) {

	if (!thePair->didStudyA || !thePair->didStudyB) {
		return;
	}

	if (doHeader) {
		fputs("\nCase,InCountry,ScenarioA,,,ScenarioB,,,PercentNewIX\n", csvFile);
	}

	int countryIndex, countryKey;
	DES_TOTAL *dtotA, *dtotB;

	for (countryIndex = 0; countryIndex < MAX_COUNTRY; countryIndex++) {

		countryKey = countryIndex + 1;
		dtotA = thePair->totalsA + countryIndex;
		dtotB = thePair->totalsB + countryIndex;
		if ((countryKey != thePair->sourceB->countryKey) && (0. == dtotA->contourArea) && (0. == dtotB->contourArea)) {
			continue;
		}

		fprintf(csvFile, "\"%s\",%d,%.1f,%d,%d,%.1f,%d,%d,%.2f,%.2f,%.2f\n", thePair->name, countryKey,
			dtotA->ixFreeArea, dtotA->ixFreePop, dtotA->ixFreeHouse, dtotB->ixFreeArea, dtotB->ixFreePop,
			dtotB->ixFreeHouse, thePair->areaPercent[countryIndex], thePair->popPercent[countryIndex],
			thePair->housePercent[countryIndex]);
	}
}


//---------------------------------------------------------------------------------------------------------------------
// Write the detailed parameters CSV file.

// Arguments:

//   paramsFlag     Parameters format, PARAMS_FILE_*
//   paramsFile     Output file stream, never NULL

void write_parameters(int paramsFlag, FILE *paramsFile) {

	if (!StudyKey) {
		log_error("write_parameters() called with no study open");
		return;
	}

	fprintf(paramsFile, "tvstudy v%s (%s)\n", TVSTUDY_VERSION, CODE_ID);

	fprintf(paramsFile, "Database,\"%s\"\n", HostDbName);
	if (ExtDbName[0]) {
		fprintf(paramsFile, "StationData,\"%s\"\n", ExtDbName);
	}
	fprintf(paramsFile, "Study,\"%s\"\n", StudyName);
	fprintf(paramsFile, "Start,\"%s\"\n", log_open_time());

	fputs("Stations,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,ServiceArea,,,TerrainLimited,,,InterferenceFree,,,,", paramsFile);
	fputs("Antenna,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,ExtraFVs\n", paramsFile);

	fputs("FacID,SrcKey,DTSKey,Site,FileNumber,RecID,Country,D,U,Call,Ch,FromCh,City,St,Lat83,Lon83,", paramsFile);
	fputs("DTSBound,RCAMSL,HAAT,ERP,DA,AntID,Rot,Tilt,OrigAntID,OrigRot,Offset,Mask,Type,InCountry,Area,Population,",
		paramsFile);
	fputs("Households,Area,Population,Households,Area,Population,Households,,0,10,20,30,40,50,60,70,80,", paramsFile);
	fputs("90,100,110,120,130,140,150,160,170,180,190,200,210,220,230,240,250,260,270,280,290,300,310,", paramsFile);
	fputs("320,330,340,350,,Az,FV,Az,FV,Az,FV,Az,FV,Az,FV,Az,FV\n", paramsFile);

	int sourceIndex;
	SOURCE *source = Sources, *dtsSource;
	for (sourceIndex = 0; sourceIndex < SourceCount; sourceIndex++, source++) {
		if (!source->isDesired && ((PARAMS_FILE_DES == paramsFlag) || !source->isUndesired)) {
			continue;
		}
		write_source_parameters(source, paramsFile);
		if (source->isParent) {
			write_source_parameters(source->dtsRefSource, paramsFile);
			for (dtsSource = source->dtsSources; dtsSource; dtsSource = dtsSource->next) {
				write_source_parameters(dtsSource, paramsFile);
			}
		}
	}
}

// Write parameter CSV data for a source.  Desired source may generate multiple rows for each country having coverage.

static void write_source_parameters(SOURCE *source, FILE *paramsFile) {

	static char fromCh[MAX_STRING], antInfo[MAX_STRING], origAntInfo[MAX_STRING], popInfo[MAX_STRING];

	lcpystr(origAntInfo, ",,", MAX_STRING);
	if (source->origSourceKey) {
		SOURCE *origSource = SourceKeyIndex[source->origSourceKey];
		if (origSource && (origSource->hasHpat || origSource->hasMpat)) {
			snprintf(origAntInfo, MAX_STRING, ",\"%s\",%.2f", origSource->antennaID, origSource->hpatOrientation);
		}
	}

	if (source->hasHpat || source->hasMpat) {
		snprintf(antInfo, MAX_STRING, "DA,\"%s\",%0f,%.2f%s", source->antennaID, source->hpatOrientation,
			source->vpatElectricalTilt, origAntInfo);
	} else {
		snprintf(antInfo, MAX_STRING, "ND,,,%.2f%s", source->vpatElectricalTilt, origAntInfo);
	}

	// For any DTS secondary source, output just one row without any area and pop information (that was all output
	// with the parent source).  For the reference facility source the file number and channel are shown as those may
	// be different, but those are not shown for the individual DTS transmitter sources as they are the same as the
	// parent, and for all secondaries the country, desired/undesired flags, call sign, city, and state are not shown.
	// These rows all have parameters and pattern data.  DTS is always TV so no record type variation here.

	if (source->parentSource) {

		if (0 == source->siteNumber) {
			fprintf(paramsFile, "%d,%d,%d,%d,\"%s\",\"%s\",,,,,%d,,,,%f,%f,,%.2f,%.2f,%s,%s,%d,%d,%d,,,,,,,,,,", 
				source->facility_id, source->sourceKey, source->parentSource->sourceKey, source->siteNumber,
				source->fileNumber, source->recordID, source->channel, source->latitude, source->longitude,
				source->actualHeightAMSL, source->actualOverallHAAT, erpkw_string(source->peakERP), antInfo,
				source->frequencyOffsetKey, source->emissionMaskKey, source->serviceTypeKey);
		} else {
			fprintf(paramsFile, "%d,%d,%d,%d,,,,,,,,,,,%f,%f,,%.2f,%.2f,%s,%s,,,%d,,,,,,,,,,", 
				source->facility_id, source->sourceKey, source->parentSource->sourceKey, source->siteNumber,
				source->latitude, source->longitude, source->actualHeightAMSL, source->actualOverallHAAT,
				erpkw_string(source->peakERP), antInfo, source->serviceTypeKey);
		}

		if (source->hasHpat) {
			write_pattern(source, paramsFile);
		}
		fputc('\n', paramsFile);

	// For any other row (DTS parent or non-DTS), loop in case there are multiple rows for area and population from
	// more than one country.  Skip countries that are not the source's native country and have zero contour area.  The
	// full data is output on the first row, which may just be the native country, other rows show just area and
	// population plus the two initial keys (facility ID and source key).  If the native country has zero contour area
	// assume this is an undesired-only and don't show area and pop at all.  A DTS parent source has a different format
	// as that source never has any operating parameters but includes the maximum distance radius.

	} else {

		int dflag = 0, uflag = 0;
		if (source->isDesired) {
			dflag = 1;
		}
		if (source->isUndesired) {
			uflag = 1;
		}

		if (source->origSourceKey) {
			SOURCE *origSource = SourceKeyIndex[source->origSourceKey];
			if (origSource) {
				snprintf(fromCh, MAX_STRING, "%d", origSource->channel);
			} else {
				lcpystr(fromCh, "??", MAX_STRING);
			}
		} else {
			fromCh[0] = '\0';
		}

		int countryIndex, countryKey, firstCountry = 1;
		DES_TOTAL *dtot;

		for (countryIndex = 0; countryIndex < MAX_COUNTRY; countryIndex++) {

			countryKey = countryIndex + 1;
			dtot = source->totals + countryIndex;
			if ((countryKey != source->countryKey) && (0. == dtot->contourArea)) {
				continue;
			}

			if (dtot->contourArea > 0.) {
				if (dtot->contourPop < 0) {
					snprintf(popInfo, MAX_STRING, "%s,,,,%.1f,%d,%d,%.1f,%d,%d", CountryName[countryIndex],
						dtot->serviceArea, dtot->servicePop, dtot->serviceHouse, dtot->ixFreeArea, dtot->ixFreePop,
						dtot->ixFreeHouse);
				} else {
					snprintf(popInfo, MAX_STRING, "%s,%.1f,%d,%d,%.1f,%d,%d,%.1f,%d,%d", CountryName[countryIndex],
						dtot->contourArea, dtot->contourPop, dtot->contourHouse, dtot->serviceArea, dtot->servicePop,
						dtot->serviceHouse, dtot->ixFreeArea, dtot->ixFreePop, dtot->ixFreeHouse);
				}
			} else {
				lcpystr(popInfo, ",,,,,,,,,", MAX_STRING);
			}

			if (firstCountry) {

				if (source->isParent) {

					// Format for first row for DTS parent, includes full identifying info, area and pop, but this has
					// no parameters and no pattern; those are all provided in later rows, see above.

					fprintf(paramsFile, "%d,%d,%d,%d,\"%s\",\"%s\",%s,%d,%d,\"%s\",%d,%s,\"%s\",%s,%f,%f,", 
						source->facility_id, source->sourceKey, source->sourceKey, source->siteNumber,
						source->fileNumber, source->recordID, CountryName[source->countryKey - 1], dflag, uflag,
						source->callSign, source->channel, fromCh, source->city, source->state, source->latitude,
						source->longitude);

					// Print out definition of the DTS area boundary, it should be a circle or a sectors geography, if
					// not just print the dtsMaximumDistance value.  Sectors are listed in the az,dist,az; format.

					GEOGRAPHY *geo = source->geography;
					if (!geo || ((GEO_TYPE_CIRCLE != geo->type) && (GEO_TYPE_SECTORS != geo->type))) {
						fprintf(paramsFile, "%.2f,", source->dtsMaximumDistance);
					} else {
						if (GEO_TYPE_CIRCLE == geo->type) {
							fprintf(paramsFile, "%.2f,", geo->a.radius);
						} else {
							int i, n = geo->nSectors;
							double azend;
							fputc('"', paramsFile);
							for (i = 0; i < n; i++) {
								if (i > 0) {
									fputc(';', paramsFile);
								}
								if (i < (n - 1)) {
									azend = geo->a.sectorAzimuth[i + 1];
								} else {
									azend = geo->a.sectorAzimuth[0] + 360.;
								}
								fprintf(paramsFile, "%.2f,%.2f,%.2f", geo->a.sectorAzimuth[i], geo->b.sectorRadius[i],
									azend);
							}
							fputs("\",", paramsFile);
						}
					}

					fprintf(paramsFile, ",,,,,,,,,,,%d,%s\n", source->serviceTypeKey, popInfo);

				} else {

					// Format for first row for non-parent, full info, area and pop, parameters, and may have pattern.
					// Variations for each record type since not all fields apply to all types.

					switch (source->recordType) {

						case RECORD_TYPE_TV:
							fprintf(paramsFile,
					"%d,%d,,%d,\"%s\",\"%s\",%s,%d,%d,\"%s\",%d,%s,\"%s\",\"%s\",%f,%f,,%.2f,%.2f,%s,%s,%d,%d,%d,%s", 
								source->facility_id, source->sourceKey, source->siteNumber, source->fileNumber,
								source->recordID, CountryName[source->countryKey - 1], dflag, uflag, source->callSign,
								source->channel, fromCh, source->city, source->state, source->latitude,
								source->longitude, source->actualHeightAMSL, source->actualOverallHAAT,
								erpkw_string(source->peakERP), antInfo, source->frequencyOffsetKey,
								source->emissionMaskKey, source->serviceTypeKey, popInfo);
							break;

						case RECORD_TYPE_WL:
							fprintf(paramsFile,
							",%d,,,\"%s\",\"%s\",%s,%d,%d,\"%s\",%.2f,,\"%s\",\"%s\",%f,%f,,%.2f,%.2f,%s,%s,,,%d,%s", 
								source->sourceKey, source->fileNumber, source->recordID,
								CountryName[source->countryKey - 1], dflag, uflag, source->callSign, source->frequency,
								source->city, source->state, source->latitude, source->longitude,
								source->actualHeightAMSL, source->actualOverallHAAT, erpkw_string(source->peakERP),
								antInfo, source->serviceTypeKey, popInfo);
							break;

						case RECORD_TYPE_FM:
							fprintf(paramsFile,
							"%d,%d,,,\"%s\",\"%s\",%s,%d,%d,\"%s\",%d,,\"%s\",\"%s\",%f,%f,,%.2f,%.2f,%s,%s,,,%d,%s", 
								source->facility_id, source->sourceKey, source->fileNumber, source->recordID,
								CountryName[source->countryKey - 1], dflag, uflag, source->callSign, source->channel,
								source->city, source->state, source->latitude, source->longitude,
								source->actualHeightAMSL, source->actualOverallHAAT, erpkw_string(source->peakERP),
								antInfo, source->serviceTypeKey, popInfo);
							break;
					}

					if (source->hasHpat) {
						write_pattern(source, paramsFile);
					}
					fputc('\n', paramsFile);
				}

				firstCountry = 0;

			} else {

				// Format for additional country rows, just basic keys plus area and pop.  Wireless has no facility ID.

				if (RECORD_TYPE_WL == source->recordType) {
					fprintf(paramsFile, ",%d,,,,,,,,,,,,,,,,,,,,,,,,,,,,%s\n", source->sourceKey, popInfo);
				} else {
					fprintf(paramsFile, "%d,%d,,,,,,,,,,,,,,,,,,,,,,,,,,,,%s\n", source->facility_id, source->sourceKey,
						popInfo);
				}
			}
		}
	}
}

// Write pattern data for a source to the parameters CSV file.

static void write_pattern(SOURCE *source, FILE *paramsFile) {

	int j;

	// First tabulate the 10-degree points

	fputc(',', paramsFile);

	for (j = 0; j < 360; j += 10) {
		fprintf(paramsFile, ",%.3f", pow(10., (source->hpat[j] / 20.)));
	}

	// Sdd extra points for local minima/maxima.  A long run of equal values will output a point at the start and end.

	double p0, p1, p2;

	fputc(',', paramsFile);

	for (j = 0; j < 360; j++) {
		if (0 == (j % 10)) {
			continue;
		}
		p0 = source->hpat[j - 1];
		p1 = source->hpat[j];
		if (359 == j) {
			p2 = source->hpat[0];
		} else {
			p2 = source->hpat[j + 1];
		}
		if (((p1 >= p0) && (p1 > p2)) || ((p1 > p0) && (p1 >= p2)) ||
				((p1 <= p0) && (p1 < p2)) || ((p1 < p0) && (p1 <= p2))) {
			fprintf(paramsFile, ",%d,%.3f", j, pow(10., (p1 / 20.)));
		}
	}
}


//---------------------------------------------------------------------------------------------------------------------
// Compose a short text description of a source.  For TV or FM this includes the call sign, channel, service code,
// status code, city, and state.  See channel_label() for details of channel field.  For wireless it is the site and
// sector IDs, which were combined into the call sign field, city, and state.

// Arguments:

//   source  The source.

// Return is label string, points to storage that will be re-used on next call!

char *source_label(SOURCE *source) {

	static char str[MAX_STRING];

	switch (source->recordType) {

		case RECORD_TYPE_TV:
		case RECORD_TYPE_FM: {
			snprintf(str, MAX_STRING, "%s %s %s %s %s, %s", source->callSign, channel_label(source),
				source->serviceCode, source->status, source->city, source->state);
			break;
		}

		case RECORD_TYPE_WL: {
			snprintf(str, MAX_STRING, "%s %s %s", source->callSign, source->city, source->state);
			break;
		}

		default: {
			str[0] = '\0';
			break;
		}
	}

	return(str);
}


//---------------------------------------------------------------------------------------------------------------------
// Format a string with channel information for a source.  For TV this has a D/N prefix for DTV/NTSC, the channel
// number, and a suffix for frequency offset if any.  For FM it is the channel number followed by the station class.
// This is not used for wireless, will return empty string in that case.

// Arguments:

//   source  The source.

// Return is label string, points to storage that will be re-used on next call!

char *channel_label(SOURCE *source) {

	static char *fmcl[FM_CLASS_L2 + 1] = {"", "C", "C0", "C1", "C2", "C3", "B", "B1", "A", "D", "L1", "L2"};

	static char str[MAX_STRING];

	switch (source->recordType) {

		case RECORD_TYPE_TV: {
			char dn;
			if (source->dtv) {
				dn = 'D';
			} else {
				dn = 'N';
			}
			char ofs[2];
			ofs[0] = '\0';
			ofs[1] = '\0';
			switch (source->frequencyOffsetKey) {
				case FREQ_OFFSET_PLUS:
					ofs[0] = '+';
					break;
				case FREQ_OFFSET_ZERO:
					ofs[0] = 'z';
					break;
				case FREQ_OFFSET_MINUS:
					ofs[0] = '-';
					break;
			}
			snprintf(str, MAX_STRING, "%c%d%s", dn, source->channel, ofs);
			break;
		}

		case RECORD_TYPE_FM: {
			snprintf(str, MAX_STRING, "%d%s", source->channel, fmcl[source->fmClass]);
			break;
		}

		default: {
			str[0] = '\0';
			break;
		}
	}

	return(str);
}


//---------------------------------------------------------------------------------------------------------------------
// Convert integer value to text inserting commas between 1000's places.  Meant for population totals, but could be
// used for any units.  Return is pointer to internal static.  The input value can be negative.  Maximum 999,999,999.

// Arguments:

//   av  The value to convert, <= 999,999,999.

// Return is converted string, points to storage that will be re-used on next call!

char *pop_commas(int av) {

	static char str[MAX_STRING];

	int v = abs(av);
	int m = v / 1000000;
	int t = (v % 1000000) / 1000;
	int o = v % 1000;

	if (m > 0) {
		if (av < 0) {
			m *= -1;
		}
		snprintf(str, MAX_STRING, "%d,%03d,%03d", m, t, o);
	} else {
		if (t > 0) {
			if (av < 0) {
				t *= -1;
			}
			snprintf(str, MAX_STRING, "%d,%03d", t, o);
		} else {
			if (av < 0) {
				o *= -1;
			}
			snprintf(str, MAX_STRING, "%d", o);
		}
	}

	return(str);
}


//---------------------------------------------------------------------------------------------------------------------
// Convert an ERP in dBk to kilowatts and write to a string with rounding according to magnitude.

// Arguments:

//   erpDbk  The ERP value in dBk to convert.

// Return is value in kilowatts as a string, return points to storage that will be re-used on the next call!

char *erpkw_string(double erpDbk) {

	static char string[MAX_STRING];

	// Convert to kilowatts, then to string.

	double kw = pow(10., (erpDbk / 10.));
	if (kw < 0.001) {
		snprintf(string, MAX_STRING, "%f", kw);
	} else {
		if (kw < 0.9995) {
			snprintf(string, MAX_STRING, "%.3f", kw);
		} else {
			if (kw < 9.995) {
				snprintf(string, MAX_STRING, "%.2f", kw);
			} else {
				if (kw < 99.95) {
					snprintf(string, MAX_STRING, "%.1f", kw);
				} else {
					snprintf(string, MAX_STRING, "%.0f", kw);
				}
			}
		}
	}

	return string;
}
