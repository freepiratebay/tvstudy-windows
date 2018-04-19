//
//  DbUtil.java
//  TVStudy
//
//  Copyright (c) 2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.dbutil;

import codeid.CodeID;

import gov.fcc.tvstudy.core.*;

import java.util.*;
import java.io.*;


//=====================================================================================================================
// Main class for command-line database utility.  Can perform root database installation, update, and removal.  Also
// LMS and CDBS data set download and import.  For unattended operation the MySQL host, root database name, user, and
// password may be provided in a properties file.  Host, name, and user may also be in command-line options, if the
// password is not found in properties an interactive prompt will be issued, unless this is in unattended mode with
// option -q in which case this will fail if the password is not in properties.  Host, user, and password are required
// but a default will be used for the database name if needed.

public class DbUtil {

	private static final int SHOW_STATUS = 1;
	private static final int INSTALL_DATABASE = 2;
	private static final int UPDATE_DATABASE = 3;
	private static final int RELOAD_ROOT_DATA = 4;
	private static final int UNINSTALL_DATABASE = 5;
	private static final int DOWNLOAD_DATA_SET = 6;
	private static final int IMPORT_DATA_SET = 7;


	//-----------------------------------------------------------------------------------------------------------------
	// Start by initializing the core and loading properties.

	public static void main(String args[]) throws Exception {

		AppCore.initialize(System.getProperty("user.dir"), true, false);

		Properties props = new Properties();
		try {
			props.load(new FileInputStream(new File(AppCore.libDirectoryPath + File.separator + "dbutil.props")));
		} catch (IOException e) {
		}

		String theHost = props.getProperty("host");
		String theName = props.getProperty("name");
		String theUser = props.getProperty("user");
		String thePass = props.getProperty("password");

		// Use default root name if needed.

		if ((null == theName) || (0 == theName.length())) {
			theName = DbCore.DEFAULT_DB_NAME;
		}

		// Parse the command line, options first.  If quiet is set there will be minimal output, just error messages.

		boolean quiet = false, bad = false;

		int iarg;
		for (iarg = 0; iarg < args.length; iarg++) {
			if ('-' != args[iarg].charAt(0)) {
				break;
			}
			switch (args[iarg].charAt(1)) {
				case 'h':
					if (++iarg < args.length) {
						theHost = args[iarg];
					} else {
						bad = true;
					}
					break;
				case 'd':
					if (++iarg < args.length) {
						theName = args[iarg];
					} else {
						bad = true;
					}
					break;
				case 'u':
					if (++iarg < args.length) {
						theUser = args[iarg];
					} else {
						bad = true;
					}
					break;
				case 'q':
					quiet = true;
					break;
				default:
					bad = true;
					break;
			}
			if (bad) {
				break;
			}
		}

		// Match the command.

		int mode = 0;
		if (!bad) {
			if (iarg >= args.length) {
				bad = true;
			} else {
				if (args[iarg].equalsIgnoreCase("status")) {
					mode = SHOW_STATUS;
				} else {
					if (args[iarg].equalsIgnoreCase("install")) {
						mode = INSTALL_DATABASE;
					} else {
						if (args[iarg].equalsIgnoreCase("update")) {
							mode = UPDATE_DATABASE;
						} else {
							if (args[iarg].equalsIgnoreCase("reloadroot")) {
								mode = RELOAD_ROOT_DATA;
							} else {
								if (args[iarg].equalsIgnoreCase("uninstall")) {
									mode = UNINSTALL_DATABASE;
								} else {
									if (args[iarg].equalsIgnoreCase("download")) {
										mode = DOWNLOAD_DATA_SET;
									} else {
										if (args[iarg].equalsIgnoreCase("import")) {
											mode = IMPORT_DATA_SET;
										} else {
											bad = true;
										}
									}
								}
							}
						}
					}
				}
			}
		}

		// For download or import, match the data set type.

		int dbType = ExtDb.DB_TYPE_UNKNOWN;
		if (!bad && ((DOWNLOAD_DATA_SET == mode) || (IMPORT_DATA_SET == mode))) {
			if (++iarg >= args.length) {
				bad = true;
			} else {
				if (args[iarg].equalsIgnoreCase("LMSTV")) {
					dbType = ExtDb.DB_TYPE_LMS;
				} else {
					if (args[iarg].equalsIgnoreCase("CDBSFM")) {
						dbType = ExtDb.DB_TYPE_CDBS_FM;
					} else {
						if (args[iarg].equalsIgnoreCase("CDBSTV")) {
							dbType = ExtDb.DB_TYPE_CDBS;
						} else {
							bad = true;
						}
					}
				}
			}
		}

		// Import has one more argument, a path to either a file directory or a ZIP file.  Doesn't matter which here.

		File sourceFile = null;
		if (!bad) {
			if (IMPORT_DATA_SET == mode) {
				if (++iarg >= args.length) {
					bad = true;
				} else {
					sourceFile = new File(args[iarg]);
				}
			}
		}

		// Print out usage if anything wrong, unless in quiet then just one line error.  All output is printed to
		// System.out, even error messages; System.err is unreliable on some platforms.

		if (bad) {

			if (quiet) {
				System.out.print("ERROR: invalid command line format\n");
				return;
			}

			System.out.print("\nDbUtil v" + AppCore.APP_VERSION_STRING + " (" + CodeID.ID + ")\n\n");
			System.out.print("Usage:\n");
			System.out.print("  dbutil [ -h host ] [ -d name ] [ -u user ] [ -q ] command [ arguments ]\n\n");
			System.out.print("Options:\n");
			System.out.print("  -h host\n");
			System.out.print("    Set database server host name.\n");
			System.out.print("  -d name\n");
			System.out.print("    Set root database name.\n");
			System.out.print("  -u user\n");
			System.out.print("    Set login user name.\n");
			System.out.print("  -q\n");
			System.out.print("    Quiet mode, no status messages.\n\n");
			System.out.print("Host, name, user, and password may be specified in lib/dbutil.props file,\n");
			System.out.print("options will individually override properties.  If a password is not set\n");
			System.out.print("in properties an interactive prompt will appear.  Option -q prevents the\n");
			System.out.print("prompt so the password must be in properties.  Host, user, and password\n");
			System.out.print("must be provided.  If name is not provided a default is used.\n\n");
			System.out.print("Commands and arguments:\n");
			System.out.print("  status\n");
			System.out.print("    Show database status.\n");
			System.out.print("  install\n");
			System.out.print("    Install new database.\n");
			System.out.print("  update\n");
			System.out.print("    Update database.\n");
			System.out.print("  reloadroot\n");
			System.out.print("    Reload root database table data.\n");
			System.out.print("    Use this command only as instructed by technical support.\n");
			System.out.print("  uninstall\n");
			System.out.print("    Delete database and cache files.\n");
			System.out.print("  download type\n");
			System.out.print("    Download and import data set files.\n");
			System.out.print("    type: lmstv cdbsfm cdbstv\n");
			System.out.print("  import type path\n");
			System.out.print("    Import data set files.\n");
			System.out.print("    type: lmstv cdbsfm cdbstv\n");
			System.out.print("    path: file directory or ZIP file\n");
			return;
		}

		// Host, name, and user must be set by now.

		if ((null == theHost) || (null == theName) || (null == theUser)) {
			System.out.print("ERROR: Missing database login information\n");
			return;
		}

		// If password not set prompt for it if possible, else fail.

		if ((null == thePass) && !quiet) {
			Console theConsole = System.console();
			if (null != theConsole) {
				thePass = new String(theConsole.readPassword("Enter password:"));
			}
		}
		if (null == thePass) {
			System.out.print("ERROR: Missing database login information\n");
			return;
		}

		// Create error logger and status reporter for the core methods.  No status in quiet mode.

		ErrorLogger errors = new ErrorLogger(new PrintStream(System.out));

		StatusReporter status = null;
		if (!quiet) {
			status = new StatusReporter() {
				public void setWaitMessage(String theMessage) {
					System.out.print(theMessage + "\n");
				}
				public boolean isCanceled() {
					return false;
				}
			};
		}

		// Initialize the database connection, branch out to command.

		DbCore.DbInfo dbInfo = new DbCore.DbInfo(theHost, theName, theUser, thePass);
		if (dbInfo.connectionFailed) {
			System.out.print("ERROR: Cannot connect to database server\n");
			return;
		}

		switch (mode) {

			// Status is entirely in the DbInfo object.

			case SHOW_STATUS: {
				System.out.print("    Host: " + dbInfo.dbHostname + "\n");
				System.out.print("Database: " + dbInfo.dbName + "\n");
				System.out.print(" Version: " + AppCore.formatVersion(dbInfo.version) + "\n");
				System.out.print("  Status: " + dbInfo.statusText + "\n");
				dbInfo.updateCacheSize();
				String theSize = "Empty";
				if (dbInfo.cacheSize > 0) {
					if (dbInfo.cacheSize >= 1e9) {
						theSize = String.format(Locale.US, "%.2f GB", ((double)dbInfo.cacheSize / 1.e9));
					} else {
						if (dbInfo.cacheSize >= 1e6) {
							theSize = String.format(Locale.US, "%.1f MB", ((double)dbInfo.cacheSize / 1.e6));
						} else {
							theSize = String.format(Locale.US, "%.0f kB", ((double)dbInfo.cacheSize / 1.e3));
						}
					}
				}
				System.out.print("   Cache: " + theSize + "\n");
				break;
			}

			// Database install.  If canOpen is true means the db is already installed and fully updated so there is
			// nothing to do; that is not considered an error, only report it if not quiet.  If canInstall is false
			// the database is already installed but not usable e.g. needs update.

			case INSTALL_DATABASE: {
				if (dbInfo.canOpen) {
					if (!quiet) {
						System.out.print("Database is already installed\n");
					}
				} else {
					if (!dbInfo.canInstall) {
						System.out.print("ERROR: Database installed but not usable: " + dbInfo.setupError + "\n");
					} else {
						DbCore.installDb(dbInfo, status, errors);
					}
				}
				break;
			}

			// Database update.  Again canOpen means nothing is needed, and canUpdate needs to be true similar to
			// install case above.

			case UPDATE_DATABASE: {
				if (dbInfo.canOpen) {
					if (!quiet) {
						System.out.print("Database is up to date\n");
					}
				} else {
					if (!dbInfo.canUpdate) {
						System.out.print("ERROR: Database cannot be updated: " + dbInfo.setupError + "\n");
					} else {
						DbCore.updateDb(dbInfo, status, errors);
					}
				}
				break;
			}

			// Reload root table data, database must be installed and current version.

			case RELOAD_ROOT_DATA: {
				if (!dbInfo.canOpen) {
					System.out.print("ERROR: Root data cannot be reloaded: " + dbInfo.setupError + "\n");
				} else {
					DbCore.updateRootData(dbInfo, errors);
				}
				break;
			}

			// Database uninstall, no failure possible here, the uninstall will work if the database exists regardless
			// of the state it is in.  Since accidental use could be very nasty get confirmation, which means this
			// cannot be used in quiet mode or without a console.

			case UNINSTALL_DATABASE: {
				if (!dbInfo.canUninstall) {
					if (!quiet) {
						System.out.print("Database is not installed\n");
					}
				} else {
					Console theConsole = System.console();
					if (quiet || (null == theConsole)) {
						System.out.print("ERROR: Uninstall command requires interactive confirmation\n");
					} else {
						String resp =
							theConsole.readLine("Are you sure you want to uninstall database '%s' on '%s' <No>? ",
								dbInfo.dbName, dbInfo.dbHostname);
						if (resp.startsWith("y") || resp.startsWith("Y")) {
							DbCore.uninstallDb(dbInfo, errors);
						}
					}
				}
				break;
			}

			// Download and import data set.  Must open the database in core state first.

			case DOWNLOAD_DATA_SET: {
				if (DbCore.openDb(dbInfo, errors)) {
					ExtDb.downloadDatabase(dbInfo.dbID, dbType, "", status, errors);
					DbCore.closeDb(dbInfo.dbID);
				}
				break;
			}

			// Import data set already downloaded.

			case IMPORT_DATA_SET: {
				if (DbCore.openDb(dbInfo, errors)) {
					ExtDb.createNewDatabase(dbInfo.dbID, dbType, sourceFile, "", status, errors);
					DbCore.closeDb(dbInfo.dbID);
				}
				break;
			}
		}
	}
}
