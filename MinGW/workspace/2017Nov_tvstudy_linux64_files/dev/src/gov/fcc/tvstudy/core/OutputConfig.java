//
//  OutputConfig.java
//  TVStudy
//
//  Copyright (c) 2016-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core;

import java.util.*;
import java.text.*;
import java.sql.*;


//=====================================================================================================================
// Utility class for managing output file configurations.  These objects use a string name as a key and define equals
// as a case-insensitive match of the names.  There are also different types of configurations each with a separate
// name space.  Configurations may be saved in a database table by type and name, those are kept in a local cache with
// write-through on save and delete.  However instances of this class may and often do exist without appearing in the
// cache or database.  Also properties are mutable, so cached objects are always copied.

public class OutputConfig {

	public static final int CONFIG_TYPE_FILE = 1;
	public static final int CONFIG_TYPE_MAP = 2;

	// Name used for objects not saved in the config database table (may be saved in last-used or study table).

	public static final String UNSAVED_CONFIG_NAME = "(custom)";

	// Name used for objects to represent a null condition, see getNullObject().

	public static final String NULL_CONFIG_NAME = "--";

	// A code that will never be used for an actual flag.  If run logic needs to be sure a study engine run does not
	// produce any output, it should pass this string as an argument on the engine command-line after a flags option.
	// The result is guaranteed to be a no-flags-set condition, overriding any default that might otherwise be used.

	public static final String NO_OUTPUT_CODE = "_";

	// Default config codes strings.

	public static final String DEFAULT_CODES_FILE = "an";
	public static final String DEFAULT_CODES_MAP = NO_OUTPUT_CODE;

	// Property keys for saving and restoring a last-used state.

	private static final String LAST_USED_NAME_FILE = "outputName";
	private static final String LAST_USED_CODES_FILE = "outputCodes";

	private static final String LAST_USED_NAME_MAP = "mapOutputName";
	private static final String LAST_USED_CODES_MAP = "mapOutputCodes";

	// Maximum number of flags.

	private static final int MAX_FLAG_COUNT = 26;

	// Index values for the file options.  Mostly these represent simple yes/no flags to generate a specific file, but
	// some may have multiple values for different formats of the file, and some represent broader options modifying
	// the behavior across multiple files.  These and the codes list must match the study engine code, although there
	// may be more here than in the engine, specifically the log file output is handled by the run manager.

	public static final int REPORT_FILE_DETAIL = 0;      // File - Report detail text.
	public static final int REPORT_FILE_SUMMARY = 1;     // File - Report summary text.
	public static final int CSV_FILE_DETAIL = 2;         // File - Report detail CSV.
	public static final int CSV_FILE_SUMMARY = 3;        // File - Report summary CSV.
	public static final int CELL_FILE_DETAIL = 4;        // File - Cell-level detail CEL.
	public static final int CELL_FILE_SUMMARY = 5;       // File - Cell-level summary CEL.
	public static final int CELL_FILE_PAIRSTUDY = 6;     // File - Cell-level special for pair study CEL.
	public static final int CELL_FILE_CSV = 7;           // File - Cell-level CSV.
	public static final int POINTS_FILE = 10;            // File - Study points CSV.
	public static final int PARAMS_FILE = 11;            // File - Parameters CSV.
	public static final int SETTING_FILE = 13;           // File - Settings text.
	public static final int LOG_FILE = 14;               // Option - Automatically save run log file.
	public static final int IXCHK_DEL_PASS = 15;         // Option - IX check study output delete pass scenarios.
	public static final int IXCHK_MARG_CSV = 16;         // File - D/U margin CSV, from IX check study only.
	public static final int POINT_FILE_PROF = 17;        // File - Terrain profile CSV, from points mode study only.
	public static final int DERIVE_HPAT_FILE = 18;       // File - Derived horizontal patterns, if any.
	public static final int COMPOSITE_COVERAGE = 19;     // Option - Include scenario composite.
	public static final int IXCHK_PROP_CONT = 20;        // File - Proposed contour, from IX check study only.
	public static final int IXCHK_REPORT_WORST = 21;     // Option - Report worst-case non-failure IX to each desired.

	// Descriptive names of the file flags for UI.  If an option has multiple names there are multiple versions of the
	// format, a menu will appear in the UI and the flag code will be followed by a modifier digit.

	private static final String[][] FLAG_NAMES_FILE = {
		{"Detailed results, text", "Only IX undesireds", "All undesireds"},
		{"Summary results, text"},
		{"Detailed results, CSV", "Only IX undesireds", "All undesireds"},
		{"Summary results, CSV"},
		{"Detailed cell data, CEL", "No Census points", "With Census points"},
		{"Summary cell data, CEL"},
		{"Pair study cell data, CEL"},
		{"Detailed cell data, CSV"},
		{"(reserved)"},
		{"(reserved)"},
		{"Study points, CSV"},
		{"Parameters, CSV", "All stations", "Desired only"},
		{"(reserved)"},
		{"Settings file, text"},
		{"Run log, text"},
		{"TV IX check: Failed scenarios only", " ", "+ all MX"},
		{"TV IX check: D/U margins, CSV", "1-degree totals", "1-deg ignore 0 pop", "All points", "All ignore 0 pop"},
		{"Points: Terrain profiles, CSV"},
		{"Derived azimuth patterns, CSV"},
		{"General: Include scenario composite"},
		{"TV IX check: Proposed contour, CSV", " ", "+ shapefile", "+ KML"},
		{"TV IX check: Report worst-case IX"}
	};

	// List, and order, of flags appearing in the UI for file config, see OutputConfigDialog.  Not all will appear.

	private static final int[] FLAG_LIST_FILE = {
		REPORT_FILE_DETAIL,
		REPORT_FILE_SUMMARY,
		CSV_FILE_DETAIL,
		CSV_FILE_SUMMARY,
		PARAMS_FILE,
		CELL_FILE_DETAIL,
		CELL_FILE_SUMMARY,
		CELL_FILE_CSV,
		POINTS_FILE,
		DERIVE_HPAT_FILE,
		LOG_FILE,
		COMPOSITE_COVERAGE,
		IXCHK_DEL_PASS,
		IXCHK_REPORT_WORST,
		IXCHK_PROP_CONT,
		IXCHK_MARG_CSV,
		POINT_FILE_PROF
	};

	// Index values for the map output options.  Most of these represent additional attributes that may be included in
	// the shapefile/KML coverage points output, others are options to filter/modify the points in those files.

	public static final int MAP_OUT_AREAPOP = 0;   // Attributes - Area, population, households.
	public static final int MAP_OUT_DESINFO = 1;   // Attributes - Desired signal, desired signal margin.
	public static final int MAP_OUT_UNDINFO = 2;   // Attributes - Worst-case undesired D/U, D/U margin.
	public static final int MAP_OUT_MARGIN = 3;    // Attributes - Worst-case margin.
	public static final int MAP_OUT_WLINFO = 4;    // Attributes - Wireless undesired signal, D/U, margin.
	public static final int MAP_OUT_SELFIX = 5;    // Attributes - DTS self-interference values, plus points file.
	public static final int MAP_OUT_RAMP = 6;      // Attributes - Ramp/alpha value.
	public static final int MAP_OUT_CLUTTER = 7;   // Attributes - Land cover type, clutter category, clutter dB.
	public static final int MAP_OUT_NOSERV = 8;    // Option - Exclude no-service points, grid mode only.
	public static final int MAP_OUT_NOPOP = 9;     // Option - Exclude no-population points, grid mode only.
	public static final int MAP_OUT_CENTER = 10;   // Option - Show map feature at cell center, grid mode only.
	public static final int MAP_OUT_SHAPE = 11;    // Files - Map output shapefile.
	public static final int MAP_OUT_KML = 12;      // Files - Map output KML.
	public static final int MAP_OUT_IMAGE = 13;    // File - Image map of selected value KML/PNG.
	public static final int MAP_OUT_COORDS = 14;   // Attributes - Study point coordinates.

	// Descriptive names of the map flags for UI.

	private static final String[][] FLAG_NAMES_MAP = {
		{"Area, population, households"},
		{"Desired signal and margin"},
		{"Worst undesired D/U and margin"},
		{"Smallest margin"},
		{"Wireless signal, D/U, and margin"},
		{"DTS self-interference details"},
		{"Ramp/alpha (uncapped)"},
		{"Land cover and clutter"},
		{"Exclude no-service grid points"},
		{"Exclude no-population grid points"},
		{"Map point at grid cell center"},
		{"Shapefile map output"},
		{"KML map output"},
		{"Image output", "Desired signal margin", "Worst-case D/U margin", "Wireless D/U margin",
			"Self-interference D/U margin", "Smallest margin", "Desired margin with IX"},
		{"Study point coordinates"}
	};

	// List, and order, of flags appearing in the UI for map options.

	private static final int[] FLAG_LIST_MAP = {
		MAP_OUT_SHAPE,
		MAP_OUT_KML,
		MAP_OUT_COORDS,
		MAP_OUT_AREAPOP,
		MAP_OUT_CLUTTER,
		MAP_OUT_DESINFO,
		MAP_OUT_UNDINFO,
		MAP_OUT_WLINFO,
		MAP_OUT_SELFIX,
		MAP_OUT_MARGIN,
		MAP_OUT_RAMP,
		MAP_OUT_NOSERV,
		MAP_OUT_NOPOP,
		MAP_OUT_CENTER,
		MAP_OUT_IMAGE
	};

	// List without the image file option, see getFlagList().

	private static final int[] FLAG_LIST_MAP_NOIMAGE = {
		MAP_OUT_SHAPE,
		MAP_OUT_KML,
		MAP_OUT_COORDS,
		MAP_OUT_AREAPOP,
		MAP_OUT_CLUTTER,
		MAP_OUT_DESINFO,
		MAP_OUT_UNDINFO,
		MAP_OUT_WLINFO,
		MAP_OUT_SELFIX,
		MAP_OUT_MARGIN,
		MAP_OUT_RAMP,
		MAP_OUT_NOSERV,
		MAP_OUT_NOPOP,
		MAP_OUT_CENTER
	};

	// Properties.

	public final int type;

	public String name;
	public int[] flags;


	//-----------------------------------------------------------------------------------------------------------------
	// Object is constructed with a code string to set initial flag state.  An empty string is valid.

	public OutputConfig(int theType, String theCodes) {

		type = theType;
		name = UNSAVED_CONFIG_NAME;
		flags = new int[MAX_FLAG_COUNT];

		setCodes(theCodes);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public OutputConfig(int theType, String theName, String theCodes) {

		type = theType;
		name = theName;
		flags = new int[MAX_FLAG_COUNT];

		setCodes(theCodes);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Private constructor to support copy().

	private OutputConfig(int theType) {

		type = theType;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public OutputConfig copy() {

		OutputConfig newConfig = new OutputConfig(type);
		newConfig.name = name;
		newConfig.flags = flags.clone();

		return newConfig;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String toString() {

		if (null == name) {
			return "";
		}
		return name;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int hashCode() {

		if (null == name) {
			return 0;
		}
		return (name + String.valueOf(type)).hashCode();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean equals(Object other) {

		return (null != other) && (null != name) && (type == ((OutputConfig)other).type) &&
			name.equalsIgnoreCase(((OutputConfig)other).name);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static String getTypeName(int theType) {

		switch (theType) {
			case CONFIG_TYPE_FILE:
			default: {
				return "Output Files";
			}
			case CONFIG_TYPE_MAP: {
				return "Map Output";
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// If the external image-processing software is not installed, the option for image output files is not shown.

	public static int[] getFlagList(int theType) {

		switch (theType) {
			case CONFIG_TYPE_FILE:
			default: {
				return FLAG_LIST_FILE;
			}
			case CONFIG_TYPE_MAP: {
				if (AppCore.showImageOptions) {
					return FLAG_LIST_MAP;
				} else {
					return FLAG_LIST_MAP_NOIMAGE;
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static String[][] getFlagNames(int theType) {

		switch (theType) {
			case CONFIG_TYPE_FILE:
			default: {
				return FLAG_NAMES_FILE;
			}
			case CONFIG_TYPE_MAP: {
				return FLAG_NAMES_MAP;
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isUnsaved() {

		return ((name != null) && name.equalsIgnoreCase(UNSAVED_CONFIG_NAME));
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isNull() {

		return ((name != null) && name.equalsIgnoreCase(NULL_CONFIG_NAME));
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get the code string for the current flags.  An empty string is valid.

	public String getCodes() {

		StringBuilder theCodes = new StringBuilder();
		for (int flagIndex = 0; flagIndex < MAX_FLAG_COUNT; flagIndex++) {
			if (flags[flagIndex] > 0) {
				theCodes.append(Character.forDigit((flagIndex + 10), 36));
				if ((flags[flagIndex] > 1) && (flags[flagIndex] < 10)) {
					theCodes.append(Character.forDigit(flags[flagIndex], 10));
				}
			}
		}

		return theCodes.toString();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void setCodes(String theCodes) {

		int flagIndex, lastFlagIndex = -1;

		for (flagIndex = 0; flagIndex < MAX_FLAG_COUNT; flagIndex++) {
			flags[flagIndex] = 0;
		}

		for (int i = 0; i < theCodes.length(); i++) {

			flagIndex = Character.digit(theCodes.charAt(i), 36);
			if (flagIndex < 0) {
				continue;
			}

			if (flagIndex < 10) {

				if (lastFlagIndex >= 0) {
					flags[lastFlagIndex] = flagIndex;
					lastFlagIndex = -1;
				}

			} else {

				lastFlagIndex = -1;
				flagIndex -= 10;
				if (flagIndex < MAX_FLAG_COUNT) {
					flags[flagIndex] = 1;
					lastFlagIndex = flagIndex;
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Compare to another config object for matching flags, return true if identical.

	public boolean matchesConfig(OutputConfig otherConfig) {

		if (type != otherConfig.type) {
			return false;
		}
		for (int flagIndex = 0; flagIndex < MAX_FLAG_COUNT; flagIndex++) {
			if (flags[flagIndex] != otherConfig.flags[flagIndex]) {
				return false;
			}
		}
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check if configuration is valid.  The name must be non-empty.  Any flag state is valid.

	public boolean isValid() {

		if ((null == name) || (0 == name.trim().length())) {
			return false;
		}
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save configuration to the database and cache.  If a configuration with the same name exists that is replaced.

	public boolean save(String theDbID) {
		return save(theDbID, null);
	}

	public synchronized boolean save(String theDbID, ErrorLogger errors) {

		if (null != name) {
			name = name.trim();
		}
		if ((null == name) || (0 == name.length()) || name.equalsIgnoreCase(UNSAVED_CONFIG_NAME) ||
				name.equalsIgnoreCase(NULL_CONFIG_NAME)) {
			if (null != errors) {
				errors.reportError("Settings save failed, invalid name.");
			}
			return false;
		}

		if (!isValid()) {
			if (null != errors) {
				errors.reportError("Settings save failed, invalid properties.");
			}
			return false;
		}

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return false;
		}

		String errmsg = null;

		try {

			db.update("LOCK TABLES output_config WRITE");

			db.update("DELETE FROM output_config WHERE type = " + type + " AND UPPER(name) = '" +
				db.clean(name.toUpperCase()) + "'");
			db.update("INSERT INTO output_config VALUES (" + type + ",'" + db.clean(name) + "', '" +
				db.clean(getCodes()) + "')");

		} catch (SQLException se) {
			errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
			db.reportError(se);
		}

		try {
			db.update("UNLOCK TABLES");
		} catch (SQLException se) {
			db.reportError(se);
		}

		DbCore.releaseDb(db);

		if (null != errmsg) {
			if (null != errors) {
				errors.reportError(errmsg);
			}
			return false;
		}

		TreeMap<String, OutputConfig> theMap = dbCache.get(theDbID);
		if (null == theMap) {
			theMap = loadCache(theDbID);
		} else {
			theMap.put(name.toUpperCase() + String.valueOf(type), this.copy());
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set this config as the last-used, store name and codes in database properties.  This can be done with an object
	// having the unsaved configuration name, but it cannot be done on the null object, silently ignore that.

	public void saveAsLastUsed(String theDbID) {

		if (null != name) {
			name = name.trim();
		}
		if ((null != name) && (0 != name.length()) && !name.equalsIgnoreCase(NULL_CONFIG_NAME)) {
			switch (type) {
				case CONFIG_TYPE_FILE:
				default: {
					DbCore.setProperty(theDbID, LAST_USED_NAME_FILE, name);
					DbCore.setProperty(theDbID, LAST_USED_CODES_FILE, getCodes());
					break;
				}
				case CONFIG_TYPE_MAP: {
					DbCore.setProperty(theDbID, LAST_USED_NAME_MAP, name);
					DbCore.setProperty(theDbID, LAST_USED_CODES_MAP, getCodes());
					break;
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get saved configurations, load the cache first if needed.  The return may be empty but is never null, errors
	// are ignored here.

	public static synchronized ArrayList<OutputConfig> getConfigs(String theDbID, int theType) {

		ArrayList<OutputConfig> result = new ArrayList<OutputConfig>();

		TreeMap<String, OutputConfig> theMap = dbCache.get(theDbID);
		if (null == theMap) {
			theMap = loadCache(theDbID);
		}

		for (OutputConfig theConfig : theMap.values()) {
			if (theType == theConfig.type) {
				result.add(theConfig.copy());
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a config by name, return null on not found but no error reporting.  Immediately return null for a null or
	// empty name, or either of the reserved names for unsaved state and null state.  If the name is not found in the
	// cache, reload the cache and try again.

	public static OutputConfig getConfig(String theDbID, int theType, String theName) {

		if (null != theName) {
			theName = theName.trim();
		}
		if ((null == theName) || (0 == theName.length()) || theName.equalsIgnoreCase(UNSAVED_CONFIG_NAME) ||
				theName.equalsIgnoreCase(NULL_CONFIG_NAME)) {
			return null;
		}

		TreeMap<String, OutputConfig> theMap = dbCache.get(theDbID);
		if (null == theMap) {
			theMap = loadCache(theDbID);
		}

		String theKey = theName.toUpperCase() + String.valueOf(theType);
		OutputConfig result = theMap.get(theKey);
		if (null == result) {
			result = loadCache(theDbID).get(theKey);
		}
		if (null != result) {
			result = result.copy();
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a config by name, make a config from arguments, or return the null object.  Always returns an object.  If
	// the name or codes are null or the name is empty or the null config name, return the null object.  If the name
	// is the unsaved config name return a new object using that name and codes.  Otherwise attempt a lookup by name,
	// if not found return a new object using the name and codes.

	public static OutputConfig getOrMakeConfig(String theDbID, int theType, String theName, String theCodes) {

		if ((null == theName) || (null == theCodes) || (0 == theName.length()) ||
				theName.equalsIgnoreCase(NULL_CONFIG_NAME)) {
			return getNullObject(theType);
		}

		OutputConfig result = null;

		if (!theName.equalsIgnoreCase(UNSAVED_CONFIG_NAME)) {

			TreeMap<String, OutputConfig> theMap = dbCache.get(theDbID);
			if (null == theMap) {
				theMap = loadCache(theDbID);
			}

			result = theMap.get(theName.toUpperCase() + String.valueOf(theType));
			if (null != result) {
				result = result.copy();
			}
		}

		if (null == result) {
			result = new OutputConfig(theType, theName, theCodes);
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Return a default object.

	public static OutputConfig getDefaultObject(int theType) {

		switch (theType) {
			case CONFIG_TYPE_FILE:
			default: {
				return new OutputConfig(CONFIG_TYPE_FILE, DEFAULT_CODES_FILE);
			}
			case CONFIG_TYPE_MAP: {
				return new OutputConfig(CONFIG_TYPE_MAP, DEFAULT_CODES_MAP);
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Return an object used to represent a null property, see OutputConfigDialog.

	public static OutputConfig getNullObject(int theType) {

		return new OutputConfig(theType, NULL_CONFIG_NAME, "");
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Load cache from database.  This does not return null, if an error occurs an empty map is returned, however that
	// map is not placed in the cache map so another attempt will be made to load the next time that is accessed.

	private static HashMap<String, TreeMap<String, OutputConfig>> dbCache =
		new HashMap<String, TreeMap<String, OutputConfig>>();

	private static TreeMap<String, OutputConfig> loadCache(String theDbID) {

		TreeMap<String, OutputConfig> result = new TreeMap<String, OutputConfig>();
		OutputConfig config;

		DbConnection db = DbCore.connectDb(theDbID);
		if (null != db) {
			try {

				db.query("SELECT type, name, codes FROM output_config");

				while (db.next()) {
					config = new OutputConfig(db.getInt(1), db.getString(2), db.getString(3));
					result.put(config.name.toUpperCase() + String.valueOf(config.type), config);
				}

				dbCache.put(theDbID, result);

			} catch (SQLException se) {
				db.reportError(se);
			}

			DbCore.releaseDb(db);
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a config representing the last-used state, this will always return an object.  If no saved state is found
	// for a file type, check for legacy codes in the application property store.  If that is not found return a
	// default object.  This will never return the null object.

	public static OutputConfig getLastUsed(String theDbID, int theType) {

		String theName = null, theCodes = null;
		switch (theType) {
			case CONFIG_TYPE_FILE:
			default: {
				theName = DbCore.getProperty(theDbID, LAST_USED_NAME_FILE);
				theCodes = DbCore.getProperty(theDbID, LAST_USED_CODES_FILE);
				break;
			}
			case CONFIG_TYPE_MAP: {
				theName = DbCore.getProperty(theDbID, LAST_USED_NAME_MAP);
				theCodes = DbCore.getProperty(theDbID, LAST_USED_CODES_MAP);
				break;
			}
		}

		if ((null != theName) && (null != theCodes)) {
			OutputConfig theConfig = getConfig(theDbID, theType, theName);
			if (null == theConfig) {
				theConfig = new OutputConfig(theType, theName, theCodes);
			}
			return theConfig;
		}
	
		if (CONFIG_TYPE_FILE == theType) {
			String oldCodes = AppCore.getProperty("outputFlags");
			if (null != oldCodes) {
				OutputConfig result = new OutputConfig(CONFIG_TYPE_FILE, "");
				result.setLegacyCodes(oldCodes);
				return result;
			}
		}

		return getDefaultObject(theType);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Delete an output configuration.

	public static boolean deleteConfig(String theDbID, int theType, String theName) {
		return deleteConfig(theDbID, theType, theName, null);
	}

	public static synchronized boolean deleteConfig(String theDbID, int theType, String theName, ErrorLogger errors) {

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return false;
		}

		try {

			db.update("DELETE FROM output_config WHERE type = " + theType + " AND UPPER(name) = '" +
				db.clean(theName.toUpperCase()) + "'");

			DbCore.releaseDb(db);

		} catch (SQLException se) {
			DbCore.releaseDb(db);
			DbConnection.reportError(errors, se);
			return false;
		}

		TreeMap<String, OutputConfig> theMap = dbCache.get(theDbID);
		if (null != theMap) {
			theMap.remove(theName.toUpperCase() + String.valueOf(theType));
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set file flags using output codes from the old system.  The old system was a string of numerical digits each in
	// sequence indicating a specific format for a general category of file type.  The new system is a string of
	// character codes, appearing in any sequence, where each code indicates a specific file and any format options
	// are indicated by a following digit.  This does not fail, anything bad in the old codes is just ignored.

	public void setLegacyCodes(String theCodes) {

		if (CONFIG_TYPE_FILE != type) {
			return;
		}

		for (int flagIndex = 0; flagIndex < MAX_FLAG_COUNT; flagIndex++) {
			flags[flagIndex] = 0;
		}

		flags[SETTING_FILE] = 1;

		for (int i = 0; i < theCodes.length(); i++) {

			switch (i) {

				case 0: {
					switch (theCodes.charAt(i)) {
						case '1':
							flags[REPORT_FILE_DETAIL] = 1;
							break;
						case '2':
							flags[REPORT_FILE_SUMMARY] = 1;
							break;
					}
					break;
				}

				case 1: {
					switch (theCodes.charAt(i)) {
						case '1':
							flags[CSV_FILE_DETAIL] = 1;
							break;
						case '2':
							flags[CSV_FILE_SUMMARY] = 1;
							break;
						}
					break;
				}

				case 2: {
					switch (theCodes.charAt(i)) {
						case '1':
							flags[CELL_FILE_DETAIL] = 1;
							break;
						case '2':
							flags[CELL_FILE_SUMMARY] = 1;
							break;
						case '4':
							flags[CELL_FILE_CSV] = 1;
							break;
					}
					break;
				}

				// Map file flags are now in map config, can't be transferred to a file config so they are dropped.

				case 3: {
					break;
				}

				case 4: {
					switch (theCodes.charAt(i)) {
						case '1':
							flags[POINTS_FILE] = 1;
							break;
					}
					break;
				}

				case 5: {
					switch (theCodes.charAt(i)) {
						case '1':
							flags[PARAMS_FILE] = 2;
							break;
						case '2':
							flags[PARAMS_FILE] = 1;
							break;
					}
					break;
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Translate a legacy code string to a new code string.

	public static String updateLegacyCodes(String theCodes) {

		OutputConfig conf = new OutputConfig(CONFIG_TYPE_FILE, "");
		conf.setLegacyCodes(theCodes);
		return conf.getCodes();
	}
}
