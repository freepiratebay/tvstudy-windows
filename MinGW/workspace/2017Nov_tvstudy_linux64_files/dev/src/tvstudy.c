//
//  tvstudy.c
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.


// Command-line study engine.  This is interface and process control only, see study.c for the actual study code.


#include "tvstudy.h"
#include "codeid/codeid.h"

#include <ctype.h>
#include <termios.h>


//---------------------------------------------------------------------------------------------------------------------

static int run_file(char *runfile);
static int do_open_study(char *study);
static int do_run_scenario(char *scenario);
static int is_name(char *str);

// Public globals.  Master debugging flag set by command-line argument, and run comment.

int Debug = 0;
char *RunComment = NULL;

// Private globals.

static char *CommandName = "";

static char *Host = NULL;
static char *Name = NULL;
static char *User = NULL;
static char *Pass = NULL;

static int ProcessCount = 0;
static int InheritLockCount = 0;
static int KeepExclLock = 0;
static int RunNumber = 0;
static int CacheUndesired = 1;


//---------------------------------------------------------------------------------------------------------------------
// Process command-line arguments, options always come first, possibly followed by other arguments.  Legal forms:

//   tvstudy [-h host] [-u user] [-c comment] [-e flags] [-f flags] -r runfile
//   tvstudy [-h host] [-u user] [-c comment] [-e flags] -f flags study [scenario ...]
//   tvstudy [-h host] [-u user] [-c comment] [-e flags] study [scenario flags ...]

// Database host and user may be provided with -h and -u.  If those are not provided on the command line or in a run
// file, defaults are used.  Defaults are determined by the MySQL API and can be set by other means i.e. environment
// variables, but typically the default host is "localhost" and the default user is the current OS login username.

// The database login password cannot be provided on the command line because that is not secure.  An interactive
// password prompt will always be issued, unless the password is provided in a runfile.  Empty passwords are not
// supported.  See do_open_study().

// The -c option provides comment text that will appear in the report output file for user reference.  If multiple
// scenarios are studied, the same comment appears in all outputs.

// If the -r option appears for operation from a run file there must not be any further arguments, study and scenario
// identifiers are read from the file.  However host, user, comment, and output flags may still be set on the command
// line and those initial values will be used by the run file processing, see run_file().

// If no run file is specified, there must be at least a study identifier argument, that may be a name or an integer
// key.  Scenario identifiers may follow, however a run with no scenario is still useful for side-effects (i.e. doing
// all source setup including replications) so that is allowed.  When running from command line only one study can be
// opened.  To open more than one study use a run file.

// Option -e sets the map output file configuration, determining the content of map files when requested by the
// output file flags.  These always apply to all scenarios.  The option codes are listed below, mostly these are
// determining attributes that will appear in the coverpts output file, others determine actual files and options.
// The flag letter may be followed by an option digit, if not present the default is option 1.
//   a  Area, population, households
//   b  Desired signal, margin
//   c  Worst undesired D/U, margin
//   d  Smallest margin
//   e  Wireless undesired signal, D/U, margin
//   f  DTS self-interference D/U, margin
//   g  Ramp/alpha value (uncapped)
//   h  Clutter category
//   i  Exclude points with no service
//   j  Exclude points with no population
//   k  Show map point at cell center instead of study point
//   l  Map output files, shapefile format
//   m  Map output files, KML format
//   n  Map image file output
//     1  Desired signal margin
//     2  Worst-case D/U margin
//     3  Wireless D/U margin
//     4  DTS self-interference D/U margin
//     5  Smallest margin
//     6  Desired margin with interference
//   o  Study point coordinates

// If -f appears to set output file flags those apply to all scenarios and only scenario identifiers appear following
// the study identifier.  Scenario identifiers may be names or keys.  If -f does not appear then scenario identifiers
// and individual flags arguments follow in pairs.  The flag codes are listed below, note some letters are not used.
// As above, flag letter may be followed by option digit.
//   a  Detail report
//     1  list only undesireds causing IX
//     2  list all undesireds
//   b  Summary report
//   c  Detail CSV
//     1  list only undesireds causing IX
//     2  list all undesireds
//   d  Summary CSV
//   e  Detail cell
//     1  no Census point data
//     2  list Census points for each study point
//   f  Summary cell
//   g  Pair study cell
//   h  Detail cell CSV
//   k  Points CSV
//   l  Parameters CSV
//     1  all stations
//     2  desired stations only
//   n  Settings file
//   p  IX check study, output failed scenarios only
//     1  failed IX and MX only
//     2  failed IX, all MX regardless of failure
//   q  IX check study, IX margin CSV
//     1  1-degree aggregate
//     2  1-degree aggregate, exclude zero population
//     3  all points
//     4  all points, exclude zero population
//   r  Points mode, source to point profile CSV
//   s  H-plane derived patterns
//   t  Normal study, compute composite scenario coverage
//   u  IX check study, proposal contour data
//     1  CSV only
//     2  CSV and shapefile
//     3  CSV and KML
//   v  IX check study, report worst non-failure IX for each desired

// If either -e or -f is not present, flags from the study record in the database are used.

// There are also a number of private options for internal use by front-end applications.

// Option -b sets the root database name on the server, if not provided a default is used.

// Option -w sets the process working directory, the full path follows in the next argument.  All paths to files
// (database, cache, and output) are by default relative to the working directory, but the output path may be set
// independently.

// Option -o sets the full path to the output file directory.

// Option -s sets the UseDbIDOutPath flag so database ID is used in place of hostname in output paths.  That is used
// by the web servlet front end because the output files are served directly to web requests so the paths need to be
// unique, persistent, unambiguous, and URL-safe.

// Option -g sets the name of a log file that will collect all message and error logs, this is created in the output
// file directory.  By default messages and errors are written to stdout and stderr, respectively.

// Option -i activates inline status messages meant to be interpreted by a front-end application filtering output
// from the engine process.  These are always sent to stdout regardless of logging configuration.

// Options -l and -k are used to tell the process to inherit a study lock set by another application, see open_study()
// in study.c for details.  That feature is not meant to be used manually.  Problems with those arguments never throw
// an error, bad values are just ignored.

// Option -n sets a run identification number used for temporary output files.

// Option -x disables caching of undesired field calculations.  For some types of studies the undesired cache files can
// become so large that the time needed to scan the file and load a small number of fields for a scenario approaches
// or even exceeds the time taken to just re-compute those fields.

// Option -m reduces the amount of memory used, the argument value indicates the total number of processes (including
// this one) expected to be running in parallel and sharing physical memory, see terrain.c.

// Option -d sets the Debug flag and activates various debug outputs to log and file.

// Option -p triggers special "probe" mode for an IX check study only, ignored for other study types.  In this mode
// no output is generated, results are reported using message lines.  This option will cause -e and -f to be ignored,
// all flags are cleared, and -i is forced whether present or not.  This is command-line run mode only, it has no
// effect when running from a runfile.

// Option -q prints out various information needed by the UI at startup, including the engine version number, the
// maximum value that can be given to -m without causing an insufficient-memory error, and the list of available
// propagation models.  After printing the information the process immediately exits, all other arguments are ignored.

// Option -t provides a log-start timestamp value so run log merges with an exisiting log sequence.

// In the second form of the command line, using -f and scenario arguments, if the first scenario argument is a '*' an
// interactive behavior is triggered.  Once setup completes and a scenario run can begin, a coded prompt is written,
// then a scenario argument is read from stdin.  When that scenario run is done the prompt is written again, repeating
// until the input read is an echo of the prompt string to indicate no more scenarios, then the study is closed and the
// process exits (any command-line arguments after the '*' are ignored).  This protocol allows a front-end application
// to dynamically balance a set of scenario runs among parallel engine processes.

// Some study types may override the normal command-line behavior.  If the study is an interference check, the scenario
// arguments are ignored and need not be provided.  Scenarios are run indirectly through the scenario pairs defined in
// the study and a custom report output is created.  That will occur even when the study is opened by a runfile.  The
// flags argument may be present and normal output files will be generated accordingly, but that is optional.

// Because I'm tired of having to figure it out every time, here is list of unused option letters:  a j p v y z

int main(int argc, char **argv) {

	CommandName = rindex(argv[0], '/');
	if (CommandName) {
		CommandName++;
	} else {
		CommandName = argv[0];
	}

	int err = 0, exitcode = 0, iarg;
	char *flags = NULL, *mapflags = NULL, *runfile = NULL;
	int ixProbeMode = 0;

	for (iarg = 1; iarg < argc; iarg++) {
		if ('-' != argv[iarg][0]) {
			break;
		}
		switch (argv[iarg][1]) {
			case 'w':
				if (++iarg >= argc) {
					err = 1;
				} else {
					chdir(argv[iarg]);
				}
				break;
			case 'o':
				if (++iarg >= argc) {
					err = 1;
				} else {
					set_out_path(argv[iarg]);
				}
				break;
			case 's':
				UseDbIDOutPath = 1;
				break;
			case 'g':
				if (++iarg >= argc) {
					err = 1;
				} else {
					set_log_file(argv[iarg]);
				}
				break;
			case 't':
				if (++iarg >= argc) {
					err = 1;
				} else {
					long ztime = strtol(argv[iarg], NULL, 10);
					if (ztime > 0L) {
						set_log_start_time(ztime);
					}
				}
				break;
			case 'i':
				set_status_enabled(1);
				break;
			case 'h':
				if (++iarg >= argc) {
					err = 1;
				} else {
					Host = argv[iarg];
				}
				break;
			case 'u':
				if (++iarg >= argc) {
					err = 1;
				} else {
					User = argv[iarg];
				}
				break;
			case 'b':
				if (++iarg >= argc) {
					err = 1;
				} else {
					Name = argv[iarg];
				}
				break;
			case 'c':
				if (++iarg >= argc) {
					err = 1;
				} else {
					RunComment = argv[iarg];
				}
				break;
			case 'f':
				if (++iarg >= argc) {
					err = 1;
				} else {
					flags = argv[iarg];
				}
				break;
			case 'e':
				if (++iarg >= argc) {
					err = 1;
				} else {
					mapflags = argv[iarg];
				}
				break;
			case 'r':
				if (++iarg >= argc) {
					err = 1;
				} else {
					runfile = argv[iarg];
				}
				break;
			case 'l':
				if ((iarg + 1) < argc) {
					InheritLockCount = atoi(argv[++iarg]);
					if (InheritLockCount <= 0) {
						InheritLockCount = 0;
					}
				}
				break;
			case 'k':
				KeepExclLock = 1;
				break;
			case 'n':
				if ((iarg + 1) < argc) {
					RunNumber = atoi(argv[++iarg]);
				}
				break;
			case 'x':
				CacheUndesired = 0;
				break;
			case 'm':
				if ((iarg + 1) < argc) {
					ProcessCount = atoi(argv[++iarg]);
					if (ProcessCount < 1) {
						ProcessCount = 1;
					}
				}
				break;
			case 'd':
				Debug = 1;
				break;
			case 'p':
				ixProbeMode = 1;
				break;
			case 'q':
				fprintf(stdout, "%s (%s)\n", TVSTUDY_VERSION, CODE_ID);
				fprintf(stdout, "%d\n", get_max_memory_fraction());
				fputs(get_model_list(), stdout);
				exit(0);
				break;
			default:
				err = 1;
				break;
		}
		if (err) {
			break;
		}
	}

	if (!err) {
		if (runfile) {
			if (iarg < argc) {
				err = 1;
			}
		} else {
			if (iarg >= argc) {
				err = 1;
			} else {
				if (!flags && ((argc - (iarg + 1)) % 2)) {
					err = 1;
				}
			}
		}
	}

	if (err) {
		fputs("usage:\n", stderr);
		fprintf(stderr, "%s [-h host] [-u user] [-c comment] [-e flags] [-f flags] -r runfile\n", CommandName);
		fprintf(stderr, "%s [-h host] [-u user] [-c comment] [-e flags] -f flags study [scenario ...]\n", CommandName);
		fputs("  -h host      database host name or address, default is localhost\n", stderr);
		fputs("  -u user      user name, default is OS user name\n", stderr);
		fputs("  -c comment   text appears in report files for reference\n", stderr);
		fputs("  -f flags     flags for all scenarios, or initial flags for runfile\n", stderr);
		fputs("    flags are string of codes some with option digit, if no digit default is 1:\n", stderr);
		fputs("       a  detail report file\n", stderr);
		fputs("         1  list only undesireds causing IX\n", stderr);
		fputs("         2  list all undesireds\n", stderr);
		fputs("       b  summary report file\n", stderr);
		fputs("       c  detail CSV file\n", stderr);
		fputs("         1  list only undesireds causing IX\n", stderr);
		fputs("         2  list all undesireds\n", stderr);
		fputs("       d  summary CSV file\n", stderr);
		fputs("       e  detail cell data file\n", stderr);
		fputs("         1  no Census point data\n", stderr);
		fputs("         2  list Census points for each study point\n", stderr);
		fputs("       f  summary cell file\n", stderr);
		fputs("       h  detail cell CSV file set\n", stderr);
		fputs("       k  study points CSV file\n", stderr);
		fputs("       l  parameters CSV file\n", stderr);
		fputs("         1  all stations\n", stderr);
		fputs("         2  desired stations only\n", stderr);
		fputs("       n  parameter settings file\n", stderr);
		fputs("       p  IX check study, output failed scenarios only\n", stderr);
		fputs("         1  failed IX and MX only\n", stderr);
		fputs("         2  failed IX, all MX regardless of failure\n", stderr);
		fputs("       q  IX check study, IX margin CSV file\n", stderr);
		fputs("         1  1-degree aggregate\n", stderr);
		fputs("         2  1-degree aggregate, exclude zero population\n", stderr);
		fputs("         3  all points\n", stderr);
		fputs("         4  all points, exclude zero population\n", stderr);
		fputs("       r  points mode study, source to point profile CSV files\n", stderr);
		fputs("       s  H-plane derived pattern CSV files\n", stderr);
		fputs("       t  normal study, compute composite scenario coverage\n", stderr);
		fputs("       u  IX check study, proposal contour data\n", stderr);
		fputs("         1  CSV files only\n", stderr);
		fputs("         2  CSV and shapefile\n", stderr);
		fputs("         3  CSV and KML files\n", stderr);
		fputs("  -e mapflags  map output configuration flags\n", stderr);
		fputs("    primary map flags determine which file formats are created:\n", stderr);
		fputs("       l  Map output files, shapefile format\n", stderr);
		fputs("       m  Map output files, KML format\n", stderr);
		fputs("       n  Map image file output\n", stderr);
		fputs("         1  Desired signal margin\n", stderr);
		fputs("         2  Worst-case D/U margin\n", stderr);
		fputs("         3  Wireless D/U margin\n", stderr);
		fputs("         4  DTS self-interference D/U margin\n", stderr);
		fputs("         5  Smallest margin\n", stderr);
		fputs("         6  Desired margin with interference\n", stderr);
		fputs("     additional map flags determine metadata content in the files:\n", stderr);
		fputs("       a  area, population, households\n", stderr);
		fputs("       b  desired signal, margin\n", stderr);
		fputs("       c  worst undesired D/U, margin\n", stderr);
		fputs("       d  smallest margin\n", stderr);
		fputs("       e  wireless undesired signal, D/U, margin\n", stderr);
		fputs("       f  DTS self-interference D/U, margin\n", stderr);
		fputs("       g  ramp/alpha value (uncapped)\n", stderr);
		fputs("       h  clutter category\n", stderr);
		fputs("       o  study point coordinates\n", stderr);
		fputs("       i  exclude points with no service\n", stderr);
		fputs("       j  exclude points with no population\n", stderr);
		fputs("       k  show map point at cell center instead of study point\n", stderr);
		fputs("  -r runfile   read commands from 'runfile'\n", stderr);
		fputs("  study        study name or key\n", stderr);
		fputs("  scenario     scenario name or key\n", stderr);
		fputs("  multiple scenarios may be studied in a run\n", stderr);
		fputs("  a run with no scenario does study setup and replications\n", stderr);
		fputs("  if flags or mapflags not set, defaults for the study are used\n", stderr);
		exit(err);
	}

	if (flags) {
		parse_flags(flags, OutputFlags, MAX_OUTPUT_FLAGS);
		OutputFlagsSet = 1;
	} else {
		OutputFlagsSet = 0;
	}
	if (mapflags) {
		parse_flags(mapflags, MapOutputFlags, MAX_MAP_OUTPUT_FLAGS);
		MapOutputFlagsSet = 1;
	} else {
		MapOutputFlagsSet = 0;
	}

	if (0 == InheritLockCount) {
		KeepExclLock = 0;
	}

	// If running from file, run_file() does the rest.  Otherwise start by opening the study, which also has side-
	// effects, i.e. contour replication.  If the open succeeds, proceed to run logic per the study type.

	if (runfile) {

		exitcode = run_file(runfile);

	} else {

		err = do_open_study(argv[iarg]);
		if (err) {
			exitcode = err;
		} else {

			switch (StudyType) {

				// Normal studies, loop over scenario arguments and run the scenarios.  If any run returns a non-fatal
				// error that will be reflected in the process exit code, however attempts to run other scenarios still
				// occurred and may have been successful.

				case STUDY_TYPE_TV:
				case STUDY_TYPE_TV_OET74:
				case STUDY_TYPE_FM:
				case STUDY_TYPE_TV6_FM:
				default: {

					iarg++;

					// If the first scenario argument is a '*', enter interactive mode.  Write a special prompt, read
					// a scenario argument response, run that scenario, and repeat until the prompt itself is echoed.

					if ('*' == argv[iarg][0]) {

						char line[MAX_STRING], chr;
						int i = 0, c = 0;

						while (1) {
							puts("#*#*#");
							fflush(stdout);
							i = 0;
							while (((chr = (char)(c = getc(stdin))) != '\n') && (chr != '\r') && (c != EOF)) {
								if (i < (MAX_STRING - 1)) {
									line[i++] = chr;
								}
							}
							line[i] = '\0';
							if ((0 == i) || (0 == strcmp(line, "#*#*#"))) {
								break;
							}
							err = do_run_scenario(line);
							if (err) {
								exitcode = err;
								if (err < 0) {
									break;
								}
							}
						}

					// Loop over multiple scenario arguments.

					} else {

						for (; iarg < argc; iarg++) {
							err = do_run_scenario(argv[iarg]);
							if (err) {
								exitcode = err;
								if (err < 0) {
									break;
								}
							}
						}
					}

					break;
				}

				// TV interference-check study.  This ignores scenario arguments and runs scenarios indirectly by
				// stepping through the scenario pairs to generate a report file unique to this study type.  It also
				// has a special "probe" mode using during the study build process in the front-end, it reports the
				// individual IX result for each undesired-desired pair.  In that mode all output is disabled, results
				// are sent to the front end entirely with status_message().

				case STUDY_TYPE_TV_IX: {

					if (ixProbeMode) {
						parse_flags("", OutputFlags, MAX_OUTPUT_FLAGS);
						OutputFlagsSet = 1;
						parse_flags("", MapOutputFlags, MAX_MAP_OUTPUT_FLAGS);
						MapOutputFlagsSet = 1;
						set_status_enabled(1);
					}
					err = run_ix_study(ixProbeMode);
					if (err) {
						exitcode = err;
					}

					break;
				}
			}

			close_study(exitcode);
		}
	}

	log_close();

	exit(exitcode);
}


//---------------------------------------------------------------------------------------------------------------------
// Open the named run file and process basic run commands.  I won't glorify this by calling it a script language, it's
// really just a means of providing command-line arguments in a file.

// Most lines in the file are of the form 'name=value', to set values corresponding to command-line arguments.  The
// possible names are:

//   host
//   user
//   pass
//   dbname
//   comment
//   study
//   flags
//   mapflags
//   scenario

// Names are case-insensitive.  See comments discussing command-line arguments for details of the values.  White-space
// is stripped before and after both name and value, however internal whitespace is preserved so study and scenario
// names containing spaces are supported.  Values must not be quoted.  Blank lines are ignored.  

// The one active element is the word 'run', appearing alone on a line.  That opens a study and runs a scenario as
// needed according to the values set at the time.  The only value that must be set is study.  If host, user, or dbname
// are not set defaults are used; if password is not set an interactive prompt will be issued; if flags or mapflags are
// not set values from the study record are used; if scenario is not set only pre-processing occurs.

// Some values remain set until explicitly changed, others are cleared automatically.  A line of just 'name=' or 'name'
// (no value) can be used to un-set a value and use the default behavior.  Host, user, password, dbname, and comment
// generally remain set until changed, however password is cleared when either host or user is set.  Scenario is
// cleared after a run command.  Study, flags, mapflags, and scenario are cleared when the current study is closed.
// There is no explicit close command, an open study is closed automatically when host, user, password, dbname, or
// study are set, and at the end of the file.  Note that restricts the position of flags, mapflags, and scenario; those
// must follow whatever might cause a previous study to be closed else they have no effect.  In general those should
// follow right after study to be safe.

// If host, user, password, dbname, comment, flags, or mapflags are set by command-line arguments those initial values
// are present at the start and will be used for the first run unless those are explicitly set or cleared first.  Also
// if study lock inheritance was set by command-line argument that will be used for the first study open.

// Arguments:

//   runfile  Name of run file.

// Return is 0 for no errors, -1 for fatal error, 1 for less serious error.  Any error aborts file processing.

static int run_file(char *runfile) {

	static char hostBuf[MAX_STRING], userBuf[MAX_STRING], passBuf[MAX_STRING], nameBuf[MAX_STRING],
		commentBuf[MAX_STRING], studyBuf[MAX_STRING], scenarioBuf[MAX_STRING];

	FILE *in = fopen(runfile, "r");
	if (!in) {
		fprintf(stderr, "%s: cannot open run file '%s'\n", CommandName, runfile);
		return 1;
	}

	int err = 0, returncode = 0, lineNumber = 0;
	char *study = NULL, *scenario = NULL, line[MAX_STRING], *theName, *theValue, *s;

	while (fgetnlc(line, MAX_STRING, in, &lineNumber) >= 0) {

		theName = line;
		s = index(line, '=');
		if (s) {
			theValue = s + 1;
			*s = '\0';
		} else {
			theValue = NULL;
		}

		while (*theName && isspace(*theName)) theName++;
		if (*theName) {
			s = theName + strlen(theName) - 1;
			while ((s > theName) && isspace(*s)) s--;
			*(s + 1) = '\0';
		} else {
			theName = NULL;
		}

		if (!theName && !theValue) {
			continue;
		}

		if (theName) {

			if (theValue) {
				while (*theValue && isspace(*theValue)) theValue++;
				if (*theValue) {
					s = theValue + strlen(theValue) - 1;
					while ((s > theValue) && isspace(*s)) s--;
					*(s + 1) = '\0';
				} else {
					theValue = NULL;
				}
			}

			if (!strcasecmp(theName, "host")) {
				if (StudyKey) {
					close_study(0);
					scenario = NULL;
					study = NULL;
					OutputFlagsSet = 0;
					MapOutputFlagsSet = 0;
				}
				if (theValue) {
					lcpystr(hostBuf, theValue, MAX_STRING);
					Host = hostBuf;
				} else {
					Host = NULL;
				}
				Pass = NULL;
				continue;
			}

			if (!strcasecmp(theName, "user")) {
				if (StudyKey) {
					close_study(0);
					scenario = NULL;
					study = NULL;
					OutputFlagsSet = 0;
					MapOutputFlagsSet = 0;
				}
				if (theValue) {
					lcpystr(userBuf, theValue, MAX_STRING);
					User = userBuf;
				} else {
					User = NULL;
				}
				Pass = NULL;
				continue;
			}

			if (!strcasecmp(theName, "pass")) {
				if (StudyKey) {
					close_study(0);
					scenario = NULL;
					study = NULL;
					OutputFlagsSet = 0;
					MapOutputFlagsSet = 0;
				}
				if (theValue) {
					lcpystr(passBuf, theValue, MAX_STRING);
					Pass = passBuf;
				} else {
					Pass = NULL;
				}
				continue;
			}

			if (!strcasecmp(theName, "dbname")) {
				if (StudyKey) {
					close_study(0);
					scenario = NULL;
					study = NULL;
					OutputFlagsSet = 0;
					MapOutputFlagsSet = 0;
				}
				if (theValue) {
					lcpystr(nameBuf, theValue, MAX_STRING);
					Name = nameBuf;
				} else {
					Name = NULL;
				}
				continue;
			}

			if (!strcasecmp(theName, "comment")) {
				if (theValue) {
					lcpystr(commentBuf, theValue, MAX_STRING);
					RunComment = commentBuf;
				} else {
					RunComment = NULL;
				}
				continue;
			}

			if (!strcasecmp(theName, "study")) {
				if (StudyKey) {
					close_study(0);
					scenario = NULL;
					study = NULL;
					OutputFlagsSet = 0;
					MapOutputFlagsSet = 0;
				}
				if (theValue) {
					lcpystr(studyBuf, theValue, MAX_STRING);
					study = studyBuf;
				} else {
					study = NULL;
				}
				continue;
			}

			if (!strcasecmp(theName, "flags")) {
				if (theValue) {
					parse_flags(theValue, OutputFlags, MAX_OUTPUT_FLAGS);
					OutputFlagsSet = 1;
				} else {
					OutputFlagsSet = 0;
				}
				continue;
			}

			if (!strcasecmp(theName, "mapflags")) {
				if (theValue) {
					parse_flags(theValue, MapOutputFlags, MAX_MAP_OUTPUT_FLAGS);
					MapOutputFlagsSet = 1;
				} else {
					MapOutputFlagsSet = 0;
				}
				continue;
			}

			if (!strcasecmp(theName, "scenario")) {
				if (theValue) {
					lcpystr(scenarioBuf, theValue, MAX_STRING);
					scenario = scenarioBuf;
				} else {
					scenario = NULL;
				}
				continue;
			}

			if (!strcasecmp(theName, "run")) {

				if (!StudyKey) {

					if (!study) {
						fprintf(stderr, "%s: in '%s' at line %d: study not set\n", CommandName, runfile, lineNumber);
						returncode = 1;
						break;
					}

					err = do_open_study(study);
					if (err) {
						fprintf(stderr, "%s: error occurred in '%s' at line %d\n", CommandName, runfile, lineNumber);
						returncode = err;
						break;
					}
				}

				switch (StudyType) {

					case STUDY_TYPE_TV:
					case STUDY_TYPE_TV_OET74:
					case STUDY_TYPE_FM:
					case STUDY_TYPE_TV6_FM:
					default: {

						if (!scenario) {
							break;
						}

						if (!OutputFlagsSet) {
							fprintf(stderr, "%s: in '%s' at line %d: flags not set\n", CommandName, runfile,
								lineNumber);
							returncode = 1;
							break;
						}

						err = do_run_scenario(scenario);
						if (err) {
							fprintf(stderr, "%s: error occurred in '%s' at line %d\n", CommandName, runfile,
								lineNumber);
							returncode = err;
							break;
						}

						break;
					}

					case STUDY_TYPE_TV_IX: {

						err = run_ix_study(0);
						if (err) {
							fprintf(stderr, "%s: error occurred in '%s' at line %d\n", CommandName, runfile,
								lineNumber);
							returncode = err;
							break;
						}

						break;
					}
				}

				if (returncode) {
					break;
				}

				scenario = NULL;

				continue;
			}
		}

		fprintf(stderr, "%s: in '%s' at line %d: line not understood\n", CommandName, runfile, lineNumber);
		returncode = 1;
		break;
	}

	if (StudyKey) {
		close_study(returncode);
	}

	fclose(in);

	return returncode;
}


//---------------------------------------------------------------------------------------------------------------------
// Open a connection to a database server and initialize for a specified study.  Called by either command-line or run
// file processing code.  Host, database name, username, and possibly password are in globals.  This will prompt
// interactively for a password if needed.  Lock inheritance arguments may be set in globals, however those are always
// cleared after the attempt to open whether it succeeds or not.  An inheritable lock is transient, it would not make
// any sense to try again later if the open fails, or to apply the inheritance to a subsequent open if it succeeds.

// Arguments:

//   study  Study to open, may be a primary key in string form or a study name; see is_name().

// Return is 0 if connection made and study opened, <0 for a fatal error meaning process should exit, >0 for a
// recoverable error meaning another open may be attempted.

static int do_open_study(char *study) {

	char *studyName = NULL;
	int studyKey = 0;

	if (is_name(study)) {
		studyName = study;
	} else {
		studyKey = atoi(study);
	}

	// If password is not defined, read it interactively.  Turn off echo on stdin, write a prompt to stderr, read a
	// non-empty line from stdin, then restore stdin settings.  Failures from the termios calls are ignored.  If echo
	// can't be disabled so be it, this is still safer than putting the password in a command-line argument.  Note
	// this does not use getpass() because that behaves differently depending on the presence of a controlling tty.
	// This must always use stdin, so an external application can write the password "interactively" at run-time.

	if (!Pass) {

		static char password[MAX_STRING];

		int tty = fileno(stdin), flagsSet = 0, saveFlags = 0;
		struct termios ttyFlags;

		if (!tcgetattr(tty, &ttyFlags)) {
			saveFlags = ttyFlags.c_lflag;
			ttyFlags.c_lflag &= ~ECHO;
			ttyFlags.c_lflag |= ECHONL;
			if (!tcsetattr(tty, TCSANOW, &ttyFlags)) {
				flagsSet = 1;
			}
		}

		int i = 0, c = 0;
		char chr;
		do {
			fputs("Enter password:", stderr);
			fflush(stderr);
			i = 0;
			while (((chr = (char)(c = getc(stdin))) != '\n') && (chr != '\r') && (c != EOF)) {
				if (i < (MAX_STRING - 1)) {
					password[i++] = chr;
				}
			}
			password[i] = '\0';
		} while ((0 == i) && (c != EOF));
		if (i > 0) {
			Pass = password;
		}

		if (flagsSet) {
			ttyFlags.c_lflag = saveFlags;
			tcsetattr(tty, TCSANOW, &ttyFlags);
		}

		fputc('\n', stderr);

		if (!Pass) {
			fprintf(stderr, "%s: could not read password from stdin\n", CommandName);
			return -1;
		}
	}

	if (initialize_terrain(ProcessCount)) {
		fprintf(stderr, "%s: insufficient memory available, study cannot run\n", CommandName);
		return -1;
	}

	int err = open_study(Host, Name, User, Pass, studyKey, studyName, InheritLockCount, KeepExclLock, RunNumber,
		CacheUndesired);
	InheritLockCount = 0;
	KeepExclLock = 0;
	return err;
}


//---------------------------------------------------------------------------------------------------------------------
// Run a scenario in the open study, called by command-line and run file processing code.

// Arguments:

//   scenario  Scenario to run, may be a primary key in string form or a scenario name; see is_name().

// Return is 0 for success, -1 for a fatal error, 1 for a recoverable error.

static int do_run_scenario(char *scenario) {

	int scenarioKey = 0;

	if (is_name(scenario)) {

		scenarioKey = find_scenario_name(scenario);
		if (scenarioKey <= 0) {
			if (scenarioKey < 0) {
				return -1;
			}
			return 1;
		}

	} else {

		scenarioKey = atoi(scenario);
	}

	int err = run_scenario(scenarioKey);
	return err;
}


//---------------------------------------------------------------------------------------------------------------------
// Determine if a string is a study/scenario name or a key.  A key is all digits ignoring leading/trailing whitespace,
// anything else is a name.  Return 1 for a name, 0 for a key or a string that is empty or all whitespace.

static int is_name(char *str) {

	char *c = str;

	while (*c && isspace(*c)) c++;
	while (*c && isdigit(*c)) c++;
	while (*c && isspace(*c)) c++;

	if (*c) {
		return 1;
	}
	return 0;
}
