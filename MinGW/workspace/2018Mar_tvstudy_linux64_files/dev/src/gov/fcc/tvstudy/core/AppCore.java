//
//  AppCore.java
//  TVStudy
//
//  Copyright (c) 2012-2018 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core;

import gov.fcc.tvstudy.core.data.*;

import java.util.*;
import java.util.logging.*;
import java.util.regex.*;
import java.text.*;
import java.io.*;
import java.nio.file.*;


//=====================================================================================================================
// Collection of core application support methods and properties, this is all static and is never instantiated.  This
// includes low-level logging of messages not meant to be reported to the user; user error reporting is handled by the
// ErrorLogger class.  A local file-backed name-value property store is managed here, to hold persistent state.  Also
// a similar read-only store for configuration settings.  Various formatting and parsing methods are provided.

public class AppCore {

	// The application version.  Version numbers are X.Y.Z version strings expressed as XYYZZZ.

	public static final String APP_VERSION_STRING = "2.2.5";
	public static final int APP_VERSION = 202005;

	// Study engine executable name and standard output file name.

	public static final String STUDY_ENGINE_NAME = "tvstudy.exe";
	public static final String STUDY_ENGINE_REPORT = "tvstudy.txt";

	// The study engine has special behaviors to support indirect control through this front-end application, those
	// include a prompt-and-response system and status messages.  See e.g. ProcessPanel and IxCheckAPI.

	public static final String ENGINE_PROMPT_PREFIX = "#*#*#";

	public static final String ENGINE_MESSAGE_PREFIX = "$$";
	public static final int ENGINE_MESSAGE_PREFIX_LENGTH = 2;

	public static final String ENGINE_PROGRESS_KEY = "progress";
	public static final String ENGINE_FILE_KEY = "outfile";
	public static final String ENGINE_REPORT_KEY = "report";
	public static final String ENGINE_RUNCOUNT_KEY = "runcount";
	public static final String ENGINE_RESULT_KEY = "result";
	public static final String ENGINE_ERROR_KEY = "error";

	// Message types for logging and reporting.

	public static final int INFORMATION_MESSAGE = 1;
	public static final int WARNING_MESSAGE = 2;
	public static final int ERROR_MESSAGE = 3;

	// Debug flag.

	public static boolean Debug = (null != System.getProperty("DEBUG"));

	// Determines if image output options are available, see initialize().

	public static boolean showImageOptions = false;

	// Path to various directory and files, see setWorkingDirectory().

	public static String workingDirectoryPath = ".";

	public static final String LIB_DIRECTORY_NAME = "lib";
	public static final String DATA_DIRECTORY_NAME = "data";
	public static final String XML_DIRECTORY_NAME = "xml";
	public static final String DBASE_DIRECTORY_NAME = "dbase";
	public static final String CACHE_DIRECTORY_NAME = "cache";
	public static final String HELP_DIRECTORY_NAME = "help";
	public static final String OUT_DIRECTORY_NAME = "out";

	public static String libDirectoryPath = LIB_DIRECTORY_NAME;
	public static String dataDirectoryPath = DATA_DIRECTORY_NAME;
	public static String xmlDirectoryPath = XML_DIRECTORY_NAME;
	public static String dbaseDirectoryPath = DBASE_DIRECTORY_NAME;
	public static String cacheDirectoryPath = CACHE_DIRECTORY_NAME;
	public static String helpDirectoryPath = HELP_DIRECTORY_NAME;
	public static String outDirectoryPath = OUT_DIRECTORY_NAME;

	// Logger.

	private static final String LOGGER_NAME = "gov.fcc.tvstudy";
	private static final String LOG_FILE_NAME = "tvstudy_err.log";
	private static Logger logger;

	// Configuration and local properties store.  Note all configuration keys may also be used as preference keys,
	// the configuration will provide a value if the preference is not defined in properties, in that case code can
	// assume the key is always defined.  Preference keys may be undefined and so return null.  See getPreference().

	private static final String CONFIG_FILE_NAME = "config.props";
	private static Properties configProperties;

	public static final String CONFIG_SHOW_DB_NAME = "showDbName";
	public static final String CONFIG_HIDE_USER_RECORD_DELETE = "hideUserRecordDelete";
	public static final String CONFIG_VERSION_CHECK_URL = "versionCheckURL";
	public static final String CONFIG_LMS_DOWNLOAD_URL = "lmsDownloadURL";
	public static final String CONFIG_CDBS_DOWNLOAD_URL = "cdbsDownloadURL";
	public static final String CONFIG_AUTO_DELETE_PREVIOUS_DOWNLOAD = "autoDeletePreviousDownload";
	public static final String CONFIG_LMS_BASELINE_DATE = "lmsBaselineDate";
	public static final String CONFIG_TVIX_WINDOW_END_DATE = "ixCheckFilingWindowEndDate";
	public static final String CONFIG_TVIX_INCLUDE_FOREIGN_DEFAULT = "ixCheckIncludeForeignDefault";
	public static final String CONFIG_TVIX_DEFAULT_CELL_SIZE = "ixCheckDefaultCellSize";
	public static final String CONFIG_TVIX_DEFAULT_PROFILE_RESOLUTION = "ixCheckDefaultProfileResolution";
	public static final String CONFIG_TVIX_DEFAULT_CELL_SIZE_LPTV = "ixCheckDefaultCellSizeLPTV";
	public static final String CONFIG_TVIX_DEFAULT_PROFILE_RESOLUTION_LPTV = "ixCheckDefaultProfileResolutionLPTV";
	public static final String CONFIG_TVIX_AM_SEARCH_DISTANCE_ND = "ixCheckAMSearchDistanceND";
	public static final String CONFIG_TVIX_AM_SEARCH_DISTANCE_DA = "ixCheckAMSearchDistanceDA";

	private static final String PROPS_FILE_NAME = "tvstudy.props";
	private static Properties localProperties;
	private static String propsFileName;

	public static final String PREF_DEFAULT_ENGINE_MEMORY_LIMIT = "defaultEngineMemoryLimit";
	public static final String PREF_TVIX_DEFAULT_CP_EXCLUDES_BL = "ixCheckDefaultCPExcludesBL";
	public static final String PREF_TVIX_DEFAULT_EXCLUDE_NEW_LPTV = "ixCheckDefaultExcludeNewLPTV";
	public static final String PREF_STUDY_MANAGER_NAME_COLUMN_FIRST = "studyManagerNameColumnFirst";

	public static final String LAST_FILE_DIRECTORY_KEY = "last_file_directory";

	// Separate properties file storing database server login information, see APIOperation and ExtDb.

	public static final String API_PROPS_FILE_NAME = "api_login.props";

	// An estimate value for cache disk space needed to study one source, see isStudyCacheSpaceAvailable().

	private static final long SOURCE_CACHE_SPACE_NEEDED = 2000000L;

	// Number of CPU cores, see initialize(), also AppTask.

	public static int availableCPUCount = Runtime.getRuntime().availableProcessors();

	// Engine version number, maximum number of study engine processes, propagation model list, see initialize().  If
	// the max engine process count is zero it means there is not enough memory for the engine to run at all, the UI
	// can still be used for editing but no study runs can be performed.  If it is -1 it means the engine executable
	// was not reachable when probed for memory requirements, again there should be no attempt to run the engine.

	public static String engineVersionString = "(unknown)";
	public static int maxEngineProcessCount = -1;
	public static ArrayList<KeyedRecord> propagationModels = new ArrayList<KeyedRecord>();

	// Prefix strings for comment lines in parsed text files, see readLineSkipCommments(), getFileVersion().

	public static final String TEXT_FILE_COMMENT_PREFIX = "#";
	public static final String TEXT_FILE_VERSION_PREFIX = "#$version=";
	public static final String XML_FILE_COMMENT_PREFIX = "<!--";
	public static final String XML_FILE_VERSION_PREFIX = "<!--$version=";

	// State set in initialize() when it checks support file installation, if fileCheckError is non-null some file was
	// not found or was the wrong version, the application should probably exit immediately.

	public static final String FILE_CHECK_LIST_FILE = "versions.dat";
	public static final int FILE_CHECK_MINIMUM_VERSION = 6;
	public static int fileCheckVersion;
	public static String fileCheckID = "(unknown)";
	public static String fileCheckError;

	// Static initialization, the logger and properties will be associated with files later, see initialize().

	static {

		logger = Logger.getLogger(LOGGER_NAME);
		logger.setUseParentHandlers(false);
		if (Debug) {
			logger.setLevel(Level.ALL);
		} else {
			logger.setLevel(Level.SEVERE);
		}

		configProperties = new Properties();

		localProperties = new Properties();
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				saveProperties();
			}
		});
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Initialization, set the working directory and derive paths and file names, associate the logger and properties
	// with files, and optionally redirect the standard error stream to the log file.  This should be called early in
	// application startup, and can only be used once.  Note the output path is a default, some operations may use a
	// custom output directory.

	private static boolean didInit = false;

	public static synchronized void initialize(String workDir, boolean redirectStderr, boolean doEngineChecks) {

		if (didInit) {
			return;
		}
		didInit = true;

		workingDirectoryPath = workDir;

		libDirectoryPath = workingDirectoryPath + File.separator + LIB_DIRECTORY_NAME;
		dataDirectoryPath = workingDirectoryPath + File.separator + DATA_DIRECTORY_NAME;
		xmlDirectoryPath = workingDirectoryPath + File.separator + XML_DIRECTORY_NAME;
		dbaseDirectoryPath = workingDirectoryPath + File.separator + DBASE_DIRECTORY_NAME;
		cacheDirectoryPath = workingDirectoryPath + File.separator + CACHE_DIRECTORY_NAME;
		helpDirectoryPath = workingDirectoryPath + File.separator + HELP_DIRECTORY_NAME;
		outDirectoryPath = workingDirectoryPath + File.separator + OUT_DIRECTORY_NAME;

		// Direct the logger to a file.

		FileOutputStream logStream = null;
		try {
			logStream = new FileOutputStream(libDirectoryPath + File.separator + LOG_FILE_NAME, true);
			logger.addHandler(new StreamHandler(logStream, new LogFormatter()));
		} catch (Throwable t) {
			log(ERROR_MESSAGE, "Could not initialize logger: ", t);
		}

		// In some environments (e.g. desktop on MacOS X), stderr dumps into the bit bucket and useful messages like
		// uncaught exceptions just vanish.  In that case it is desireable to redirect stderr to the local log file.
		// However when running from other contexts (e.g. a servlet container) that would not be a nice thing to do
		// since messages from unrelated code would end up in TVStudy's log.  Hence the caller has to decide.

		if ((null != logStream) && redirectStderr) {
			System.setErr(new PrintStream(logStream, true));
		}

		// Check support files for correct installation and current version.  The list of files and expected versions
		// are stored in the file "lib/versions.dat".  That file itself has a version number which is checked against
		// a minimum expected value here.  Many of the files may be updated without any update to the code, but such
		// updates will always change the versions list and it's version number.  The main purpose of this check is to
		// catch incomplete installation of such file-only updates.  But at the next code update, the minimum version
		// for the versions list will change so any missed file-only updates must be installed with the code update.
		// The versions list also has a descriptive ID string which appears in the "About" dialog.  If any problem is
		// found or error occurs, fileCheckError is set and other properties are cleared.  It's up to the caller to
		// decide how to behave in that situation; the initialization will continue here.

		String fileName = "", filePath = "", indexFileName;
		BufferedReader indexReader = null;

		try {

			indexFileName = LIB_DIRECTORY_NAME + File.separator + FILE_CHECK_LIST_FILE;
			fileName = indexFileName;
			indexReader = new BufferedReader(new FileReader(new File(makeFilePath(fileName))));

			fileCheckVersion = getFileVersion(indexReader);
			if (fileCheckVersion < FILE_CHECK_MINIMUM_VERSION) {
				fileCheckError = "File check failed, '" + indexFileName + "' is the wrong version.";
			} else {

				fileCheckID = readLineSkipComments(indexReader);
				if (null == fileCheckID) {
					fileCheckError = "File check failed, bad format in '" + indexFileName + "'.";
				} else {

					String str;
					int requiredVersion, fileVersion;

					while (true) {

						fileName = readLineSkipComments(indexReader);
						if (null == fileName) {
							break;
						}
						str = readLineSkipComments(indexReader);
						requiredVersion = 0;
						if (null != str) {
							try {
								requiredVersion = Integer.parseInt(str);
							} catch (NumberFormatException ne) {
							}
						}
						if (requiredVersion <= 0) {
							fileCheckError = "File check failed, bad format in '" + indexFileName + "'.";
							break;
						}

						if (fileName.endsWith("xml") || fileName.endsWith("XML")) {
							fileVersion = getXMLFileVersion(makeFilePath(fileName));
						} else {
							fileVersion = getFileVersion(makeFilePath(fileName));
						}
						if (fileVersion != requiredVersion) {
							fileCheckError = "File check failed, '" + fileName + "' is the wrong version.";
							break;
						}
					}
				}
			}

		} catch (FileNotFoundException fe) {
			fileCheckError = "File check failed, '" + fileName + "' not found.";
		} catch (IOException ie) {
			fileCheckError = "File check failed, I/O error.";
		}

		try {
			if (null != indexReader) {
				indexReader.close();
			}
		} catch (IOException ie) {
		}

		if (null != fileCheckError) {
			fileCheckVersion = 0;
			fileCheckID = "(failed)";
		}

		// Load configuration and properties from files.

		FileInputStream theStream;

		try {
			theStream = new FileInputStream(libDirectoryPath + File.separator + CONFIG_FILE_NAME);
			configProperties.load(theStream);
			theStream.close();
		} catch (Throwable t) {
		}

		propsFileName = libDirectoryPath + File.separator + PROPS_FILE_NAME;
		try {
			theStream = new FileInputStream(propsFileName);
			localProperties.load(theStream);
			theStream.close();
		} catch (Throwable t) {
		}

		// If this is initializing for a utility function that won't be using the study engine, skip the rest.

		if (!doEngineChecks) {
			return;
		}

		// Query the study engine for various information including it's verison number, a maximum process count based
		// on available memory, and a list of available propagation models.  If this succeeds maxEngineProcessCount is
		// set to the maximum number of parallel engine processes that can safely run at the same time.  That is the
		// smaller of the memory limit the study engine reports and the number of CPU cores.  However if CPU cores is
		// limiting that is never less than 2, even on a single-CPU system there is a benefit to running parallel
		// processes if memory permits.  Note the engine may return a limit of 0 meaning the total memory is below its
		// minimum limit and it would refuse to do anything, it would start but immediately abort with a message about
		// insufficient memory.  In that case all run UI should be disabled but editing can still be performed.  If the
		// process count remains at -1 it means the engine query failed, again other code should not allow any attempt
		// to start an engine process.  But a -1 should probably show a different error message than a 0.

		maxEngineProcessCount = -1;
		int memCount = 1;

		try {

			Process p =
				Runtime.getRuntime().exec(new String[] {libDirectoryPath + File.separator + STUDY_ENGINE_NAME, "-q"});

			BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));

			String line;
			String[] parts;
			int i = 0, model;

			while (true) {

				line = br.readLine();
				if (null == line) {
					break;
				}

				switch (++i) {

					case 1: {
						engineVersionString = line;
						break;
					}

					case 2: {
						try {

							memCount = Integer.parseInt(line);
							int cpuCount = availableCPUCount;
							if (cpuCount < 2) {
								cpuCount = 2;
							}
							maxEngineProcessCount = (memCount < cpuCount) ? memCount : cpuCount;
						} catch (NumberFormatException ne) {
						}
						break;
					}

					default: {
						parts = line.split("=");
						if (2 == parts.length) {
							model = 0;
							try {
								model = Integer.parseInt(parts[0].trim());
							} catch (NumberFormatException ne) {
							}
							if (model > 0) {
								propagationModels.add(new KeyedRecord(model, parts[1].trim()));
							}
						}
						break;
					}
				}
			}

		} catch (Throwable t) {
			log(ERROR_MESSAGE, "Unexpected error", t);
		}

		// Image output depends on separately-installed GhostScript imaging software.  There must be a symlink to that
		// software at $lib/gs if it is installed.  If not, image output options are hidden, see OutputConfig.

		showImageOptions = (new File(libDirectoryPath + File.separator + "gs")).exists();
	}


	//=================================================================================================================
	// Formatter for log messages.

	private static class LogFormatter extends java.util.logging.Formatter {

		public String format(LogRecord record) {

			StringWriter result = new StringWriter();
			result.write(formatTimestamp(record.getMillis()));
			result.write(" ");
			result.write(record.getLoggerName());
			result.write(" [");
			result.write(record.getLevel().getLocalizedName());
			result.write("] : ");
			result.write(record.getMessage());
			result.write("\n");

			Throwable theThrown = record.getThrown();
			if (null != theThrown) {
				theThrown.printStackTrace(new PrintWriter(result));
			}

			return result.toString();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Logging methods.  Mostly just wrappers around the Logger, but providing message type mapping.

	public static void log(int type, String msg) {

		Level level = Level.INFO;
		if (ERROR_MESSAGE == type) {
			level = Level.SEVERE;
		} else {
			if (WARNING_MESSAGE == type) {
				level = Level.WARNING;
			}
		}
		logger.log(level, msg);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static void log(int type, String msg, Throwable thrown) {

		Level level = Level.INFO;
		if (ERROR_MESSAGE == type) {
			level = Level.SEVERE;
		} else {
			if (WARNING_MESSAGE == type) {
				level = Level.WARNING;
			}
		}
		logger.log(level, msg, thrown);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Configuration settings, these are read-only.

	public static String getConfig(String name) {

		return configProperties.getProperty(name);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Local properties.

	public static String getProperty(String name) {

		return localProperties.getProperty(name);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Values stored in the properties can never be null.

	public static void setProperty(String name, String value) {

		if (null == value) {
			return;
		}
		localProperties.setProperty(name, value);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Commit properties to the file.

	public static void saveProperties() {

		if (null != propsFileName) {
			try {
				localProperties.store(new FileOutputStream(propsFileName), "");
			} catch (Throwable t) {
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Preferences.  These usually access the local property store, but if no value is found for a get this will check
	// the configuration so that may provide a default.

	public static String getPreference(String name) {

		String value = localProperties.getProperty(name);
		if (null == value) {
			value = configProperties.getProperty(name);
		}
		return value;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static void setPreference(String name, String value) {

		if (null == value) {
			return;
		}
		localProperties.setProperty(name, value);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Utility methods for working with cache and output files and directories.  First get the storage space used by
	// cache files for a specified database and study.  If the study key is 0, this will return the total size for all
	// studies in the database.

	public static long getStudyCacheSize(String theDbID) {
		return getStudyCacheSize(theDbID, 0);
	}

	public static long getStudyCacheSize(String theDbID, int studyKey) {

		long newSize = 0L;

		File cacheRoot;

		if (studyKey > 0) {
			cacheRoot = new File(cacheDirectoryPath + File.separator + theDbID + File.separator + studyKey);
		} else {
			cacheRoot = new File(cacheDirectoryPath + File.separator + theDbID);
		}

		if (cacheRoot.exists() && cacheRoot.isDirectory()) {
			newSize = sizeOfDirectoryContents(cacheRoot);
		}

		return newSize;
	}


	//=================================================================================================================
	// Information returned by getFreeSpace(), usable free space in bytes on the file store containing the cache
	// directory, and the output directory, for a particular database ID.  If sameFileStore is true, the cache and
	// output are on the same file store so are sharing the same free space.

	public static class FreeSpaceInfo {

		public static String dbID;

		public long totalFreeSpace;

		public boolean sameFileStore;

		public long cacheFreeSpace;
		public long outputFreeSpace;


		//-------------------------------------------------------------------------------------------------------------

		public FreeSpaceInfo(String theDbID) {

			dbID = theDbID;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get free space for cache and output file allocation for a database.  This tries to check the database-specific
	// directories to allow for any possible use of symlinks to separate storage into different physical filesystems,
	// but if the database-specific directory doesn't yet exist the root cache or output directory is checked.  This
	// always returns an result, if either free space value can't be determined it is set to -1.

	public static FreeSpaceInfo getFreeSpace(String theDbID) {

		FreeSpaceInfo theInfo = new FreeSpaceInfo(theDbID);
		FileStore cacheStore = null, outputStore = null;
		try {
			File cacheDir = new File(cacheDirectoryPath + File.separator + theDbID);
			if (!cacheDir.exists()) {
				cacheDir = new File(cacheDirectoryPath);
			}
			cacheStore = Files.getFileStore(cacheDir.toPath());
			theInfo.cacheFreeSpace = cacheStore.getUsableSpace();
		} catch (Throwable t) {
		}
		if (theInfo.cacheFreeSpace <= 0L) {
			theInfo.cacheFreeSpace = -1L;
		}

		try {
			File outputDir = new File(outDirectoryPath + File.separator + DbCore.getHostDbName(theDbID));
			if (!outputDir.exists()) {
				outputDir = new File(outDirectoryPath);
			}
			outputStore = Files.getFileStore(outputDir.toPath());
			theInfo.outputFreeSpace = outputStore.getUsableSpace();
		} catch (Throwable t) {
		}
		if (theInfo.outputFreeSpace <= 0L) {
			theInfo.outputFreeSpace = -1L;
		}

		if ((null != cacheStore) && (null != outputStore)) {
			theInfo.sameFileStore = cacheStore.equals(outputStore);
		}
		if (theInfo.sameFileStore) {
			theInfo.totalFreeSpace = theInfo.cacheFreeSpace;
		} else {
			if ((theInfo.cacheFreeSpace > 0L) && (theInfo.outputFreeSpace > 0L)) {
				theInfo.totalFreeSpace = theInfo.cacheFreeSpace + theInfo.outputFreeSpace;
			} else {
				theInfo.totalFreeSpace = -1L;
			}
		}

		return theInfo;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Test if disk space is available for estimate allocations for a study run, cache size is checked based on the
	// number of desired sources to be studied, output file size is passed in bytes and must be estimated by the
	// caller.  Zero for either will skip that test.  If free space cannot be determined the return is always false.
	// This is only used to generate a warning alert to the user which can be ignored.

	public static boolean isFreeSpaceAvailable(String theDbID, int sourceCount, long outputNeeded) {

		long cacheNeeded = (long)sourceCount * SOURCE_CACHE_SPACE_NEEDED;

		FreeSpaceInfo theInfo = getFreeSpace(theDbID);

		if (theInfo.sameFileStore) {
			return (cacheNeeded + outputNeeded) < theInfo.totalFreeSpace;
		}

		boolean cacheOK = true, outputOK = true;
		if (cacheNeeded > 0L) {
			cacheOK = (cacheNeeded < theInfo.cacheFreeSpace);
		}
		if (outputNeeded > 0L) {
			outputOK = (outputNeeded < theInfo.outputFreeSpace);
		}
		return (cacheOK && outputOK);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Delete study engine cache files, see comments above.

	public static boolean deleteStudyCache(String theDbID, int studyKey) {

		boolean result = false;

		File cacheRoot;

		if (studyKey > 0) {
			cacheRoot = new File(cacheDirectoryPath + File.separator + theDbID + File.separator + studyKey);
		} else {
			cacheRoot = new File(cacheDirectoryPath + File.separator + theDbID);
		}

		if (cacheRoot.exists() && cacheRoot.isDirectory()) {
			result = deleteDirectoryAndContents(cacheRoot);
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Delete study engine cache files for sources that no longer exist, based on a source key use map.

	public static boolean purgeStudyCache(String theDbID, int studyKey, boolean[] sourceKeyMap) {

		boolean result = false;

		File cacheRoot = new File(cacheDirectoryPath + File.separator + theDbID + File.separator + studyKey);

		if (cacheRoot.exists() && cacheRoot.isDirectory()) {
			result = deleteUnusedCacheFiles(cacheRoot, sourceKeyMap);
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Recurse through the cache directory for a study.  For each file extract the source key, check map of keys in
	// use, if not found delete the file.  For most files the key is the entire file name, undesired cache files in
	// local grid mode have a second key in the name following a '_', but the first key is the relevant one.  If a
	// file name doesn't parse leave it alone.  If a key is found that is beyond the range of the map, delete it.
	// This is private because only purgeStudyCache() needs it, it has no generic use.

	private static boolean deleteUnusedCacheFiles(File theDir, boolean[] sourceKeyMap) {

		File[] contents = theDir.listFiles();
		if (null == contents) {
			return false;
		}

		boolean result = true;

		String theName;
		int theKey, pos;

		for (int i = 0; i < contents.length; i++) {
			if (contents[i].isDirectory()) {
				if (!deleteUnusedCacheFiles(contents[i], sourceKeyMap)) {
					result = false;
				}
			} else {
				theName = contents[i].getName();
				pos = theName.indexOf('_');
				if (pos > 0) {
					theName = theName.substring(0, pos);
				}
				theKey = 0;
				try {
					theKey = Integer.parseInt(theName);
				} catch (NumberFormatException nfe) {
				}
				if (theKey > 0) {
					if ((theKey >= sourceKeyMap.length) || !sourceKeyMap[theKey]) {
						if (!contents[i].delete()) {
							result = false;
						}
					}
				}
			}
		}

		return result;
	}


	//-------------------------------------------------------------------------------------------------------------
	// Sum the length of all files in a directory tree.

	public static long sizeOfDirectoryContents(File theDir) {

		File[] contents = theDir.listFiles();
		if (null == contents) {
			return 0;
		}

		long result = 0;

		for (int i = 0; i < contents.length; i++) {
			if (contents[i].isDirectory()) {
				result += sizeOfDirectoryContents(contents[i]);
			} else {
				result += contents[i].length();
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Delete a directory tree.  The return indicates if any errors occurred, but this will continue trying to delete
	// files and directories through the entire tree regardless of previous errors.

	public static boolean deleteDirectoryAndContents(File theDir) {

		File[] contents = theDir.listFiles();
		if (null == contents) {
			return false;
		}

		boolean result = true;

		for (int i = 0; i < contents.length; i++) {
			if (contents[i].isDirectory()) {
				if (!deleteDirectoryAndContents(contents[i])) {
					result = false;
				}
			} else {
				if (!contents[i].delete()) {
					result = false;
				}
			}
		}

		if (result && !theDir.delete()) {
			result = false;
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Methods for formatting and parsing coordinates, first geographic latitude in decimal degrees broken out to
	// degrees-minutes-seconds.

	public static String formatLatitude(double theValue) {

		if (theValue < 0) {
			return formatLatLon(-theValue) + " S";
		} else {
			return formatLatLon(theValue) + " N";
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Geographic longitude, as above.

	public static String formatLongitude(double theValue) {

		if (theValue < 0) {
			return formatLatLon(-theValue) + " E";
		} else {
			return formatLatLon(theValue) + " W";
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private static String formatLatLon(double theValue) {

		int deg = (int)theValue;
		int min = (int)((theValue - (double)deg) * 60.);
		double sec = (((theValue - (double)deg) * 60.) - (double)min) * 60.;
		if (sec >= 59.995) {
			sec = 0.;
			if (60 == ++min) {
				min = 0;
				++deg;
			}
		}

		return String.format(Locale.US, "%3d %02d %04.2f", deg, min, sec);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Parse latitude/longitude strings as created by the formatting methods above.  These will also allow the string
	// to just contain a signed decimal value.

	public static double parseLatitude(String str) {

		return parseLatLon(str, (Source.LATITUDE_MIN - 1.), "S");
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static double parseLongitude(String str) {

		return parseLatLon(str, (Source.LONGITUDE_MIN - 1.), "E");
	}


	//-----------------------------------------------------------------------------------------------------------------

	private static Pattern latLonPattern = Pattern.compile("\\s");

	private static double parseLatLon(String str, double bad, String neg) {

		if (null == str) {
			return bad;
		}

		double latlon = bad;

		String[] tokens = latLonPattern.split(str.trim());

		if (1 == tokens.length) {

			try {

				latlon = Double.parseDouble(tokens[0]);

			} catch (NumberFormatException nfe) {
			}

		} else {

			if ((3 == tokens.length) || (4 == tokens.length)) {

				try {

					int deg = Integer.parseInt(tokens[0]);
					int min = Integer.parseInt(tokens[1]);
					double sec = Double.parseDouble(tokens[2]);

					latlon = (double)deg + ((double)min / 60.) + (sec / 3600.);

					if ((4 == tokens.length) && (tokens[3].equalsIgnoreCase(neg))) {
						latlon = -latlon;
					}

				} catch (NumberFormatException nfe) {
				}
			}
		}

		return latlon;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Methods for formatting and parsing numbers so the display and input behavior are consistent application-wide.
	// First is a general-purpose method for decimal format with specified precision.

	private static NumberFormat[] numberFormatters = new NumberFormat[4];
	private static NumberFormat minuteFormatter = null;
	private static final int MAX_FORMAT_PREC = 4;

	static {
		for (int i = 0; i < MAX_FORMAT_PREC; i++) {
			numberFormatters[i] = NumberFormat.getInstance(Locale.US);
			numberFormatters[i].setMinimumFractionDigits(i);
			numberFormatters[i].setMaximumFractionDigits(i);
			numberFormatters[i].setMinimumIntegerDigits(1);
			numberFormatters[i].setGroupingUsed(false);
		}
		minuteFormatter = NumberFormat.getInstance(Locale.US);
		minuteFormatter.setMinimumIntegerDigits(2);
	}

	public static String formatDecimal(double theValue, int prec) {

		if (prec < 0) {
			prec = 0;
		}
		if (prec > MAX_FORMAT_PREC) {
			prec = MAX_FORMAT_PREC;
		}
		return numberFormatters[prec].format(theValue);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The rest of the formatting methods are specific to a real-world value with assumed units, i.e. distance in km,
	// ERP in kW.  Code displaying such values always used these methods, so appearance can be altered application-
	// wide by just changing the code here.  First, a geographic coordinate in arc-seconds.

	public static String formatSeconds(double theValue) {

		return numberFormatters[2].format(theValue);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Height in meters.

	public static String formatHeight(double theValue) {

		return numberFormatters[1].format(theValue);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Distance in kilometers.

	public static String formatDistance(double theValue) {

		return numberFormatters[2].format(theValue);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Azimuth or bearing in degrees true.

	public static String formatAzimuth(double theValue) {

		return numberFormatters[1].format(theValue);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Depression angle in degrees.

	public static String formatDepression(double theValue) {

		return numberFormatters[2].format(theValue);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// ERP in kilowatts, the formatting is range-dependent.

	public static String formatERP(double theValue) {

		if (theValue < 0.001) {
			return String.valueOf(theValue);
		} else {
			if (theValue < 0.9995) {
				return numberFormatters[3].format(theValue);
			} else {
				if (theValue < 9.995) {
					return numberFormatters[2].format(theValue);
				} else {
					if (theValue < 99.95) {
						return numberFormatters[1].format(theValue);
					} else {
						return numberFormatters[0].format(theValue);
					}
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Relative field, 0-1.

	public static String formatRelativeField(double theValue) {

		if (theValue < 0.001) {
			return String.valueOf(theValue);
		} else {
			return numberFormatters[3].format(theValue);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Field strength in dBu.

	public static String formatField(double theValue) {

		return numberFormatters[1].format(theValue);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// D/U value in dB.

	public static String formatDU(double theValue) {

		return numberFormatters[1].format(theValue);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Percentage.

	public static String formatPercent(double theValue) {

		return numberFormatters[2].format(theValue);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Format an integer value with grouping.

	private static NumberFormat countFormatter = null;

	static {
		countFormatter = NumberFormat.getIntegerInstance(Locale.US);
		countFormatter.setGroupingUsed(true);
	}

	public static String formatCount(int theValue) {

		return countFormatter.format(theValue);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Convert a version number string in the form X, X.Y, or X.Y.Z to XYYZZZ format, return -1 if format not valid.
	// This will ignore additional dot-separated values beyond the first three, so version strings may include other
	// info as long as all three X, Y, and Z are present and Z is followed by a dot.

	public static int parseVersion(String theVers) {

		if (null == theVers) {
			return -1;
		}

		theVers = theVers.trim();
		if (0 == theVers.length()) {
			return -1;
		}

		String[] parts = theVers.split("\\.");

		int major = 0, minor = 0, dot = 0;

		try {
			major = Integer.parseInt(parts[0]);
			if (parts.length > 1) {
				minor = Integer.parseInt(parts[1]);
			}
			if (parts.length > 2) {
				dot = Integer.parseInt(parts[2]);
			}
		} catch (NumberFormatException ne) {
			return -1;
		}

		if ((major < 1) || (minor < 0) || (minor > 99) || (dot < 0) || (dot > 999)) {
			return -1;
		}

		return (major * 100000) + (minor * 1000) + dot;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Format an XYYZZZ version number into X.Y.Z format.  Also takes format XYYZZZSS, but SS is not shown unless > 0.

	public static String formatVersion(int version) {

		if (version <= 0) {
			return "";
		}

		int sub = 0;
		if (version > 9999999) {
			sub = version % 100;
			version /= 100;
		}

		int major = version / 100000;
		int minor = (version % 100000) / 1000;
		int dot = version % 1000;

		if (sub > 0) {
			return String.format(Locale.US, "%d.%d.%d.%d", major, minor, dot, sub);
		} else {
			return String.format(Locale.US, "%d.%d.%d", major, minor, dot);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Formatting and parsing methods for dates and times.  SimpleDateFormat is not thread-safe.

	private static final DateFormat dateTimeFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	public static synchronized String formatDateTime(java.util.Date theDate) {

		if (null != theDate) {
			return dateTimeFormatter.format(theDate);
		}
		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	private static final DateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd");

	public static synchronized String formatDate(java.util.Date theDate) {

		if (null != theDate) {
			return dateFormatter.format(theDate);
		}
		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	private static final DateFormat[] dateParsers = {
		new SimpleDateFormat("yyyy-MM-dd"),
		new SimpleDateFormat("M/d/yyyy"),
		new SimpleDateFormat("ddMMMyyyy")
	};

	public static synchronized java.util.Date parseDate(String s) {

		if (null == s) {
			return null;
		}

		java.util.Date d = null;
		for (DateFormat f : dateParsers) {
			try {
				d = f.parse(s);
			} catch (ParseException pe) {
			}
			if (null != d) {
				break;
			}
		}
		return d;
	}


	//-----------------------------------------------------------------------------------------------------------------

	private static final DateFormat dayFormatter = new SimpleDateFormat("EEEEEEEEE");

	public static synchronized String formatDay(java.util.Date theDate) {

		if (null != theDate) {
			return dayFormatter.format(theDate);
		}
		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	private static SimpleDateFormat timestampFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

	public static synchronized String formatTimestamp(long theTime) {

		return timestampFormatter.format(new java.util.Date(theTime));
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Format a number of bytes into a string, converting to gigabytes, megabytes, or kilobytes depending on value.
	// If the value is less than 0 return "(unknown)"; if it is exactly 0, return "-".

	public static String formatBytes(long bytes) {

		if (bytes < 0L) {
			return "(unknown)";
		}
		if (bytes >= 995000000L) {
			return String.format(Locale.US, "%.2f GB", ((double)bytes / 1.e9));
		}
		if (bytes >= 950000L) {
			return String.format(Locale.US, "%.1f MB", ((double)bytes / 1.e6));
		}
		if (bytes > 0L) {
			return String.format(Locale.US, "%.0f kB", ((double)bytes / 1.e3));
		}
		return "-";
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Replace special characters in a string for XML output.

	public static String xmlclean(String theText) {

		StringBuilder result = new StringBuilder();
		char c;

		for (int i = 0; i < theText.length(); i++) {

			c = theText.charAt(i);

			switch (c) {

				case '&':
					result.append("&amp;");
					break;

				case '<':
					result.append("&lt;");
					break;

				case '>':
					result.append("&gt;");
					break;

				case '"':
					result.append("&quot;");
					break;

				default:
					result.append(c);
					break;
			}
		}

		return result.toString();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Integer counter class.

	public static class Counter {

		public int count;

		public Counter(int theCount) {
			count = theCount;
		}

		public void set(int theCount) {
			count = theCount;
		}

		public int get() {
			return count;
		}

		public int increment() {
			return ++count;
		}

		public int decrement() {
			return --count;
		}
		
		public void reset() {
			count = 0;
		}

		public String toString() {
			return String.valueOf(count);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Wrapper around BufferedReader.readLine(), skip over lines that being with a comment prefix.  Optionally will
	// increment a line counter for each line read including comments.

	public static String readLineSkipComments(BufferedReader reader) throws IOException {
		return readLineSkipComments(reader, null);
	}

	public static String readLineSkipComments(BufferedReader reader, Counter counter) throws IOException {

		String line;

		do {
			line = reader.readLine();
			if ((null != counter) && (null != line)) {
				counter.increment();
			}
		} while ((null != line) && line.startsWith(TEXT_FILE_COMMENT_PREFIX));

		return line;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Take a file name that may include a directory relative off the installation root, and convert to a full path
	// using the current values of the various path properties.

	public static String makeFilePath(String theName) {

		String dirName = null, fileName = theName;

		int sep = theName.indexOf(File.separatorChar);
		if (sep >= 0) {
			if (sep > 0) {
				dirName = theName.substring(0, sep);
			}
			fileName = theName.substring(sep + 1);
			if ((0 == fileName.length()) && (null != dirName)) {
				fileName = dirName;
				dirName = null;
			}
		}

		if (null != dirName) {
			if (dirName.equals(LIB_DIRECTORY_NAME)) {
				dirName = libDirectoryPath;
			} else {
				if (dirName.equals(DATA_DIRECTORY_NAME)) {
					dirName = dataDirectoryPath;
				} else {
					if (dirName.equals(XML_DIRECTORY_NAME)) {
						dirName = xmlDirectoryPath;
					} else {
						if (dirName.equals(DBASE_DIRECTORY_NAME)) {
							dirName = dbaseDirectoryPath;
						} else {
							if (dirName.equals(HELP_DIRECTORY_NAME)) {
								dirName = helpDirectoryPath;
							} else {
								dirName = workingDirectoryPath + File.separator + dirName;
							}
						}
					}
				}
			}
		} else {
			dirName = workingDirectoryPath;
		}

		return dirName + File.separator + fileName;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a version number embedded in a text or XML file (different prefix strings used for comments).  The number
	// is in a comment line with a special format which must appear in the file before the first non-comment line.
	// Returns 0 if no version is found or any error occurs.  A negative version is invalid so returns 0.  If the form
	// taking a BufferedReader is used, the read will proceed from the current stream position which usually would be
	// the start of the file.  One or more comment lines may be skipped but no non-comment lines are skipped.  If this
	// reads past comments without finding a version it will reset the position back to before the first non-comment
	// line past the starting position, if there is one.

	public static int getFileVersion(String thePath) throws FileNotFoundException {
		return getFileVersion(thePath, TEXT_FILE_COMMENT_PREFIX, TEXT_FILE_VERSION_PREFIX);
	}

	public static int getFileVersion(BufferedReader theReader) {
		return getFileVersion(theReader, TEXT_FILE_COMMENT_PREFIX, TEXT_FILE_VERSION_PREFIX);
	}

	public static int getXMLFileVersion(String thePath) throws FileNotFoundException {
		return getFileVersion(thePath, XML_FILE_COMMENT_PREFIX, XML_FILE_VERSION_PREFIX);
	}

	private static int getFileVersion(String thePath, String commentPrefix,
			String versionPrefix) throws FileNotFoundException {
		BufferedReader reader = new BufferedReader(new FileReader(new File(thePath)));
		int version = getFileVersion(reader, commentPrefix, versionPrefix);
		try {reader.close();} catch (IOException ie) {}
		return version;
	}

	private static int getFileVersion(BufferedReader theReader, String commentPrefix, String versionPrefix) {

		int version = 0;
		String line = null;

		do {

			try {
				theReader.mark(10000);
				line = theReader.readLine();
			} catch (IOException ie) {
				line = null;
				break;
			}

			if ((null != line) && line.startsWith(versionPrefix)) {
				int s = versionPrefix.length(), e = s, n = line.length();
				while ((e < n) && Character.isDigit(line.charAt(e))) e++;
				try {
					version = Integer.parseInt(line.substring(s, e));
					if (version < 0) {
						version = 0;
					}
				} catch (NumberFormatException ne) {
				}
				line = null;
				break;
			}

		} while ((null != line) && line.startsWith(commentPrefix));

		if (null != line) {
			try {
				theReader.reset();
			} catch (IOException ie) {
			}
		}

		return version;
	}
}
