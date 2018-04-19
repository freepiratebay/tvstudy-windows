//
//  ExtDb.java
//  TVStudy
//
//  Copyright (c) 2015-2018 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core;

import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;
import gov.fcc.tvstudy.core.geo.*;

import java.util.*;
import java.text.*;
import java.sql.*;
import java.io.*;
import java.nio.file.*;
import java.net.*;
import java.util.zip.*;


//=====================================================================================================================
// Represents records from the ext_db table in the root database.  Provides static factory methods, as well as static
// methods to create and delete external data set databases.  See ExtDbRecord and gui.ExtDbManager for details.  This
// now also supports external databases on different hosts for direct connection to "live" database servers, and also
// special key values that automatically map to the most recent imported data.

public class ExtDb {

	public static final int DB_TYPE_NOT_SET = -1;
	public static final int DB_TYPE_UNKNOWN = 0;

	public static final int DB_TYPE_CDBS = 1;
	public static final int DB_TYPE_LMS = 2;
	public static final int DB_TYPE_WIRELESS = 3;
	public static final int DB_TYPE_CDBS_FM = 4;
	public static final int DB_TYPE_LMS_LIVE = 5;
	public static final int DB_TYPE_GENERIC_TV = 6;
	public static final int DB_TYPE_GENERIC_WL = 7;
	public static final int DB_TYPE_GENERIC_FM = 8;

	// Sorting map, see getExtDbList().

	private static final int[] DB_TYPE_ORDER = {4, 2, 5, 7, 1, 3, 6, 8};

	// A key range is reserved for internal use and will not be used for imported data sets, see createNewDatabase().

	public static final int RESERVED_KEY_RANGE_START = 10000;
	public static final int RESERVED_KEY_RANGE_END = 19999;

	// Key values in the reserved range for "live" external server objects, currently only LMS has that feature.

	public static final int KEY_LMS_LIVE = 10005;

	// Key values for the "most recent" functions, in the reserved range.  When these keys are retrieved by getExtDb(),
	// an object is returned for an actual imported data set in the category (the most-recent keys never appear on an
	// actual object).  Most recent is based on the actual data date determined during import, not the date of import.
	// See createNewDatabase().  For that reason, wireless and generic imports are not part of the most-recent test as
	// those have no intrinsic dating of content, the database timestamp is the time of import.

	public static final int KEY_MOST_RECENT_LMS = 10102;
	public static final int KEY_MOST_RECENT_CDBS = 10103;
	public static final int KEY_MOST_RECENT_CDBS_FM = 10104;

	// Name used in UI to identify an unnamed download set.

	public static final String DOWNLOAD_SET_NAME = "(download)";

	// Properties.

	public final String dbID;

	public final Integer key;

	public final String dbName;
	public final java.util.Date dbDate;

	public final int type;
	public final int version;
	public final int recordType;

	public String id;
	public String name;
	public String description;

	public boolean deleted;

	public boolean isDownload;

	// To support databases on different servers, connections are always obtained with connectDb()/releaseDb() methods
	// mirroring those in DbCore.  For imported data sets those are mostly just wrappers for the DbCore methods but if
	// isLive is true a separate server is used with a local connection pool.  These are called "live" because they are
	// usually active servers providing current data being edited through other UIs, e.g. see getLMSLiveExtDb().

	private final boolean isLive;
	private DbConnection liveDb;
	private ArrayDeque<DbConnection> dbPool;
	private HashSet<DbConnection> openDbs;

	// Generic import data sets can be expanded by additional imports, which involves creating new SourceEditData
	// objects to be saved into the data set's database.  See connectAndLock() and getNewRecordKey().

	private boolean dbLocked;
	private int nextRecordKey;

	// Interval for automatic update of cache, milliseconds.  Some calls will always update regardless of the interval.

	private static final long CACHE_UPDATE_INTERVAL = 300000L;

	// Name of table definitions file for CDBS dump files, see loadCDBSFieldNames().

	private static final String CDBS_TABLE_DEFS_FILE = "cdbs_table_defs.dat";

	// Table names for wireless import, see openWirelessTableFiles(), also ExtDbManager.createWirelessTableFiles().

	public static final String WIRELESS_BASE_TABLE = "base_station";
	public static final String WIRELESS_INDEX_TABLE = "antenna_index";
	public static final String WIRELESS_PATTERN_TABLE = "antenna_pattern";

	public static final String WIRELESS_BASE_FILE = WIRELESS_BASE_TABLE + ".dat";
	public static final String WIRELESS_INDEX_FILE = WIRELESS_INDEX_TABLE + ".dat";
	public static final String WIRELESS_PATTERN_FILE = WIRELESS_PATTERN_TABLE + ".dat";

	// Current version numbers for imports.

	// Note generic data sets are not versioned.  The backing store for those is the usual Source tables in separate
	// import databases, as with study databases those will be updated in place when changes occur.  Internally records
	// from those data sets are provided as native SourceEditData objects, there is no ExtDbRecord translation subclass
	// needed.  The SourceEditData superclass is used to compose search queries.

	// Version change history.  Note the import code has a limited ability to detect version from import file content
	// adjust accordingly, the constant here is the highest version but that may be rolled back in some cases.

	// Any version 0
	//   Imported before app version 1.4.1 when versioning was added, or generic.

	// CDBS
	//   Version 1
	//     import app_tracking table for accepted_date field
	//     import am_ant_sys table for AM station checks (optional)

	// LMS
	//   Version 1
	//     new fields dts_reference_ind, dts_waiver_distance in application
	//   Version 2
	//     include facility table to get facility_status field
	//     import tables for baseline record support
	//     import CDBS application ID cross-reference for lookup during XML import (optional)
	//   Version 3
	//     new fields for service and country in lkp_dtv_allotment
	//     import CDBS mirror tables gis_am_ant_sys, gis_facility, and gis_application for AM checks (optional)
	//   Version 4
	//     new field for Class A baseline records, app_dtv_channel_assignment.emission_mask_code
	//   Version 5
	//     new field app_dtv_channel_assignment.electrical_deg for electrical beam tilt
	//     new field app_antenna.foreign_station_beam_tilt for electrical beam tilt on non-U.S. records
	//   Version 6
	//     import shared_channel table

	// WIRELESS
	//   Version 1
	//     first version for new data type

	// CDBS_FM
	//   Version 1
	//     first version for new data type

	private static final int CDBS_VERSION = 1;
	private static final int LMS_VERSION = 6;
	private static final int WIRELESS_VERSION = 1;
	private static final int CDBS_FM_VERSION = 1;

	private static final int DOWNLOAD_TIMEOUT = 30000;   // milliseconds


	//-----------------------------------------------------------------------------------------------------------------
	// Instances come only from factory methods below.  Each data type only provides one record type, for convenience
	// that is provided as a property.

	private ExtDb(String theDbID, Integer theKey, String theDbName, java.util.Date theDbDate, int theType,
			int theVersion, int theRecordType, boolean theIsLive) {

		dbID = theDbID;

		key = theKey;

		dbName = theDbName;
		dbDate = theDbDate;

		type = theType;
		version = theVersion;
		recordType = theRecordType;

		isLive = theIsLive;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String toString() {

		return description;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int hashCode() {

		return key.intValue();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean equals(Object other) {

		return (null != other) && ((ExtDb)other).key.equals(key);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Connect to a generic data set and lock it for updating.  If this is not a generic set this fails, also if the
	// set is already locked this fails, see connectDb().  If this succeeds, update the value for the next record key.

	public DbConnection connectAndLock(ErrorLogger errors) {

		if (!isGeneric()) {
			if (null != errors) {
				errors.reportError("Connect failed, station data cannot be locked.");
			}
			return null;
		}

		DbConnection db = connectDb(true, errors);
		if (null == db) {
			return null;
		}

		nextRecordKey = 1;
		try {
			db.query("SELECT MAX(source_key) FROM source");
			if (db.next()) {
				nextRecordKey = db.getInt(1) + 1;
			}
		} catch (SQLException se) {
			DbCore.releaseDb(db);
			db = null;
			DbConnection.reportError(errors, se);
		}

		return db;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a connected database object from a pool, the connection must be released by passing it to releaseDb().  For
	// normal imported data sets this is just a wrapper around DbCore methods.  When the database represented by this
	// object is on a live server, a local connection pool is maintained.  That may be shared by other objects.  The
	// private version may attempt to lock the data set, see connectAndLock() above.  The current lock will always be
	// checked and the connect fails if the set is locked, if not locked and doLock is true the lock is set.  Note the
	// lock protocol is really just advisory within this app instance, there is no protection against some overlapping
	// uses of connectDb() and connectAndLock().  Specifically connectAndLock() does not check if there are existing
	// unreleased connections before setting the lock, and once the lock is set it is cleared by the next releaseDb()
	// regardless of which connection is being released.  But the primary purpose is to keep concurrent app instances
	// that are sharing the root database from concurrently modifying the data set, and it does that fine.  Note the
	// live databases are never locked, and currently the only others that can be locked are generic sets which can
	// have additional data added by multiple imports.  The other types of sets are created and imported atomically
	// (the ext_db record is not written until the import is done, see createNewDatabase()) and so don't need locking.

	// Note this deliberately does not check the deleted flag; if the data set database no longer exists an SQL error
	// will occur.  That should rarely happen because of the deferred-delete behavior.  When a set is deleted, the
	// index record is marked deleted however the database itself is not dropped until closeDb() is called, which
	// presumably does not occur until there are no running or pending activities that might still try to access the
	// data set.  The only case that is not guarded is concurrent delete by another application instance which may
	// actually drop the database before activities here complete, but that is assumed to be very unlikely.

	public DbConnection connectDb() {
		return connectDb(false, null);
	}

	public DbConnection connectDb(ErrorLogger errors) {
		return connectDb(false, errors);
	}

	private synchronized DbConnection connectDb(boolean doLock, ErrorLogger errors) {

		DbConnection db = null;

		if (isLive) {

			db = dbPool.poll();
			if (null == db) {
				db = liveDb.copy();
			}

			openDbs.add(db);

			if (!db.connect(dbName, errors)) {
				releaseDb(db);
				return null;
			}

		} else {

			// If an unreleased connection in this same app instance has the data set locked, can't open another.

			if (dbLocked) {
				if (null != errors) {
					errors.reportWarning("The station data is in use.");
				}
				return null;
			}

			db = DbCore.connectDb(dbID, errors);
			if (null == db) {
				return null;
			}

			// Check the lock state, fail if locked, else set the lock if requested.

			String rootName = DbCore.getDbName(dbID);
			boolean wasLocked = false;

			try {

				db.setDatabase(dbName);

				db.update("LOCK TABLES " + rootName + ".ext_db WRITE");

				db.query("SELECT locked FROM " + rootName + ".ext_db WHERE ext_db_key = " + key);
				if (db.next()) {
					wasLocked = db.getBoolean(1);
				}

				if (!wasLocked && doLock) {
					db.update("UPDATE " + rootName + ".ext_db SET locked = true WHERE ext_db_key = " + key);
					dbLocked = true;
				}

			} catch (SQLException se) {
				DbCore.releaseDb(db);
				DbConnection.reportError(errors, se);
				return null;
			}

			try {
				db.update("UNLOCK TABLES");
			} catch (SQLException se) {
				db.reportError(se);
			}

			if (wasLocked) {
				if (null != errors) {
					errors.reportWarning("The station data is in use.");
				}
				DbCore.releaseDb(db);
				db = null;
			}
		}

		return db;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// See comments above regarding locking protocol.

	public synchronized void releaseDb(DbConnection db) {

		if (isLive) {

			if (openDbs.remove(db)) {
				db.close();
				dbPool.push(db);
			}

		} else {

			if (dbLocked) {
				try {
					db.update("UPDATE " + DbCore.getDbName(dbID) + ".ext_db SET locked = false WHERE ext_db_key = " +
						key);
				} catch (SQLException se) {
					db.reportError(se);
				}
				dbLocked = false;
			}

			DbCore.releaseDb(db);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a new record key for new imports to a data set, the set must be locked which can only occur under certain
	// conditions, see connectAndLock() above.

	public Integer getNewRecordKey() {

		if (dbLocked) {
			return Integer.valueOf(nextRecordKey++);
		}
		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Return a string description of the data type.

	public String getTypeName() {
		return getTypeName(type);
	}

	public static String getTypeName(int theType) {

		switch (theType) {
			case DB_TYPE_CDBS:
				return "CDBS TV";
			case DB_TYPE_LMS:
			case DB_TYPE_LMS_LIVE:
				return "LMS TV";
			case DB_TYPE_WIRELESS:
				return "Wireless";
			case DB_TYPE_CDBS_FM:
				return "CDBS FM";
			case DB_TYPE_GENERIC_TV:
				return "Generic TV";
			case DB_TYPE_GENERIC_WL:
				return "Generic W/l";
			case DB_TYPE_GENERIC_FM:
				return "Generic FM";
			default:
				return "??";
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get the record type for a given db type.

	public static int recordTypeForDBType(int theType) {

		switch (theType) {
			case DB_TYPE_CDBS:
			case DB_TYPE_LMS:
			case DB_TYPE_LMS_LIVE:
			case DB_TYPE_GENERIC_TV:
				return Source.RECORD_TYPE_TV;
			case DB_TYPE_WIRELESS:
			case DB_TYPE_GENERIC_WL:
				return Source.RECORD_TYPE_WL;
			case DB_TYPE_CDBS_FM:
			case DB_TYPE_GENERIC_FM:
				return Source.RECORD_TYPE_FM;
			default:
				return 0;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Return a list of all import types for UI selection; this does not include the "live" database types.  The first
	// method does not include generic types, it provides only the types that are create-and-import in one step.  The
	// second method returns all the generic types, which are created first by separate action, then have one or more
	// imports into the set.  See ExtDbManager.

	private static ArrayList<KeyedRecord> downloadTypes = null;

	public static synchronized ArrayList<KeyedRecord> getDownloadTypes() {

		if (null == downloadTypes) {
			downloadTypes = new ArrayList<KeyedRecord>();
			downloadTypes.add(new KeyedRecord(DB_TYPE_LMS, getTypeName(DB_TYPE_LMS)));
			downloadTypes.add(new KeyedRecord(DB_TYPE_CDBS_FM, getTypeName(DB_TYPE_CDBS_FM)));
			downloadTypes.add(new KeyedRecord(DB_TYPE_CDBS, getTypeName(DB_TYPE_CDBS)));
		}

		return new ArrayList<KeyedRecord>(downloadTypes);
	}

	private static ArrayList<KeyedRecord> importTypes = null;

	public static synchronized ArrayList<KeyedRecord> getImportTypes() {

		if (null == importTypes) {
			importTypes = new ArrayList<KeyedRecord>();
			importTypes.add(new KeyedRecord(DB_TYPE_LMS, getTypeName(DB_TYPE_LMS)));
			importTypes.add(new KeyedRecord(DB_TYPE_WIRELESS, getTypeName(DB_TYPE_WIRELESS)));
			importTypes.add(new KeyedRecord(DB_TYPE_CDBS_FM, getTypeName(DB_TYPE_CDBS_FM)));
			importTypes.add(new KeyedRecord(DB_TYPE_CDBS, getTypeName(DB_TYPE_CDBS)));
		}

		return new ArrayList<KeyedRecord>(importTypes);
	}

	private static ArrayList<KeyedRecord> genericTypes = null;

	public static synchronized ArrayList<KeyedRecord> getGenericTypes() {

		if (null == genericTypes) {
			genericTypes = new ArrayList<KeyedRecord>();
			genericTypes.add(new KeyedRecord(DB_TYPE_GENERIC_TV, getTypeName(DB_TYPE_GENERIC_TV)));
			genericTypes.add(new KeyedRecord(DB_TYPE_GENERIC_WL, getTypeName(DB_TYPE_GENERIC_WL)));
			genericTypes.add(new KeyedRecord(DB_TYPE_GENERIC_FM, getTypeName(DB_TYPE_GENERIC_FM)));
		}

		return new ArrayList<KeyedRecord>(genericTypes);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Indicate if AM station data is present, in CDBS version 1 or later and LMS version 3 or later the AM tables are
	// imported.  Really this indicates the tables _might_ be present; the import code does not require these, if they
	// are missing the import will still succeed, so code needs to check for table existence before querying.

	public boolean hasAM() {

		return (((DB_TYPE_CDBS == type) && (version > 0)) || ((DB_TYPE_LMS == type) && (version > 2)) ||
			(DB_TYPE_LMS_LIVE == type));
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Indicate if TV baseline record searches can be performed, see ExtDbRecordTV.  CDBS imports always had the
	// baseline table.  In LMS those were added in import version 2.  Of course always there on the LMS live server.

	public boolean hasBaseline() {

		return ((DB_TYPE_CDBS == type) || ((DB_TYPE_LMS == type) && (version > 1)) || (DB_TYPE_LMS_LIVE == type));
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Convenience.

	public boolean isGeneric() {

		return isGeneric(type);
	}

	public static boolean isGeneric(int theType) {

		return ((DB_TYPE_GENERIC_TV == theType) || (DB_TYPE_GENERIC_WL == theType) || (DB_TYPE_GENERIC_FM == theType));
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve an object by key, return null on error.  If the key is not found that is an error; since records are
	// never removed from the database just marked deleted, not-found should never occur since other code should never
	// encounter undefined keys.  Also if the data is deleted that is an error unless the findDeleted flag is true.

	public static ExtDb getExtDb(String theDbID, Integer theKey) {
		return getExtDb(theDbID, theKey, false, null);
	}

	public static ExtDb getExtDb(String theDbID, Integer theKey, ErrorLogger errors) {
		return getExtDb(theDbID, theKey, false, errors);
	}

	public static ExtDb getExtDb(String theDbID, Integer theKey, boolean findDeleted) {
		return getExtDb(theDbID, theKey, findDeleted, null);
	}

	public static synchronized ExtDb getExtDb(String theDbID, Integer theKey, boolean findDeleted,
			ErrorLogger errors) {

		HashMap<Integer, ExtDb> theMap = getCache(theDbID, errors);
		if (null == theMap) {
			return null;
		}

		ExtDb result = theMap.get(theKey);

		if (null == result) {
			theMap = mrCache.get(theDbID);
			if (null != theMap) {
				result = theMap.get(theKey);
			}
		}

		if (null == result) {
			if (null != errors) {
				errors.reportError("Invalid station data key.");
			}
			return null;
		}

		if (result.deleted && !findDeleted) {
			if (null != errors) {
				errors.reportWarning("The station data has been deleted.");
			}
			return null;
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve an object by name, no index by name so this is a linear search but this is an uncommon lookup so
	// performance not a big concern.  This only has to check non-deleted sets since names are cleared on deleted sets.
	// Returns null on error or not found, not-found is not an error.

	public static ExtDb getExtDb(String theDbID, String theName) {
		return getExtDb(theDbID, theName, null);
	}

	public static synchronized ExtDb getExtDb(String theDbID, String theName, ErrorLogger errors) {

		HashMap<Integer, ExtDb> theMap = getCache(theDbID, errors);
		if (null == theMap) {
			return null;
		}

		ExtDb result = null;
		for (ExtDb theDb : theMap.values()) {
			if (!theDb.deleted && theDb.name.equalsIgnoreCase(theName)) {
				result = theDb;
				break;
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get the type name or description for an external data set key, regardless of whether the data set is deleted or
	// not.  These do not fail, they will return an empty string if the key is not found or errors occur.  Used for
	// non-critical labelling only.  Live server objects may be unavailable due to connection failure and so not appear
	// in the cache, recognize those directly and return an appropriate string.

	public static String getExtDbTypeName(String theDbID, Integer theKey) {

		ExtDb theDb = getExtDb(theDbID, theKey, true, null);
		if (null == theDb) {
			if (KEY_LMS_LIVE == theKey.intValue()) {
				return "LMS TV";
			}
			return "";
		}
		return getTypeName(theDb.type);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static String getExtDbDescription(String theDbID, Integer theKey) {

		ExtDb theDb = getExtDb(theDbID, theKey, true, null);
		if (null == theDb) {
			if (KEY_LMS_LIVE == theKey.intValue()) {
				return "LMS TV live server (offline)";
			}
			return "";
		}
		return theDb.description;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a full list of non-deleted ExtDb objects from a database.  See getExtDbList() for comments about sorting.
	// Returns null on error.

	public static ArrayList<ExtDb> getExtDbs(String theDbID) {
		return getExtDbs(theDbID);
	}

	public static synchronized ArrayList<ExtDb> getExtDbs(String theDbID, ErrorLogger errors) {

		HashMap<Integer, ExtDb> theMap = getCache(theDbID, errors);
		if (null == theMap) {
			return null;
		}

		ArrayList<ExtDb> result = new ArrayList<ExtDb>();
		for (ExtDb theExtDb : theMap.values()) {
			if (!theExtDb.deleted) {
				result.add(theExtDb);
			}
		}

		Collections.sort(result, new Comparator<ExtDb>() {
			public int compare(ExtDb one, ExtDb two) {
				if (DB_TYPE_ORDER[one.type - 1] < DB_TYPE_ORDER[two.type - 1]) {
					return -1;
				} else {
					if (DB_TYPE_ORDER[one.type - 1] > DB_TYPE_ORDER[two.type - 1]) {
						return 1;
					} else {
						if (one.key > two.key) {
							return -1;
						} else {
							if (one.key < two.key) {
								return 1;
							}
						}
					}
				}
				return 0;
			}
		});

		return result;
	}


	//=================================================================================================================
	// A KeyedRecord subclass used to facilitate sorting in getExtDbList.

	private static class DbListItem extends KeyedRecord {

		private final int type;


		//-------------------------------------------------------------------------------------------------------------

		private DbListItem(int theKey, String theName, int theType) {

			super(theKey, theName);

			type = theType;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a KeyedRecord list of non-deleted data sets in a specified database, updating the cache first.  The list is
	// usually filtered by record type, returning only data sets containing the specified type.  Optionally generic
	// data sets can be excluded.  Alternately, the list can be filtered by database type and version.  Either form
	// with 0 for recordType or dbType will return all, version 0 will return all.  Requesting the LMS database type
	// will also match the LMS live type.  Most-recent items may optionally be included.  Returns null on error.

	public static ArrayList<KeyedRecord> getExtDbList(String theDbID, int recordType) {
		return getExtDbList(theDbID, recordType, 0, 0, true, true, null);
	}

	public static ArrayList<KeyedRecord> getExtDbList(String theDbID, int recordType, ErrorLogger errors) {
		return getExtDbList(theDbID, recordType, 0, 0, true, true, errors);
	}

	public static ArrayList<KeyedRecord> getExtDbList(String theDbID, int recordType, boolean includeGeneric,
			boolean includeMostRecent) {
		return getExtDbList(theDbID, recordType, 0, 0, includeGeneric, includeMostRecent, null);
	}

	public static ArrayList<KeyedRecord> getExtDbList(String theDbID, int recordType, boolean includeGeneric,
			boolean includeMostRecent, ErrorLogger errors) {
		return getExtDbList(theDbID, recordType, 0, 0, includeGeneric, includeMostRecent, errors);
	}

	public static ArrayList<KeyedRecord> getExtDbList(String theDbID, int dbType, int minVersion) {
		return getExtDbList(theDbID, 0, dbType, minVersion, true, true, null);
	}

	public static ArrayList<KeyedRecord> getExtDbList(String theDbID, int dbType, int minVersion, ErrorLogger errors) {
		return getExtDbList(theDbID, 0, dbType, minVersion, true, true, errors);
	}

	private static synchronized ArrayList<KeyedRecord> getExtDbList(String theDbID, int recordType, int dbType,
			int minVersion, boolean includeGeneric, boolean includeMostRecent, ErrorLogger errors) {

		HashMap<Integer, ExtDb> theMap = getCache(theDbID, errors);
		if (null == theMap) {
			return null;
		}

		ArrayList<DbListItem> dbList = new ArrayList<DbListItem>();
		DbListItem theItem;
		for (ExtDb theDb : theMap.values()) {
			if (!theDb.deleted && (includeGeneric || !theDb.isGeneric()) &&
					((0 == recordType) || (recordType == theDb.recordType)) &&
					((0 == dbType) || (dbType == theDb.type) ||
						((DB_TYPE_LMS == dbType) && (DB_TYPE_LMS_LIVE == theDb.type))) &&
					(theDb.version >= minVersion)) {
				theItem = new DbListItem(theDb.key.intValue(), theDb.description, theDb.type);
				dbList.add(theItem);
			}
		}

		// This is the only context from which objects having the most-recent pseudo-keys are returned, those may
		// appear in UI lists but are resolved to real objects on lookup with getExtDb().  Check each of the objects
		// appearing in the most-recent cache map against filtering criteria, add a most-recent item as needed.  Some
		// criteria don't apply; the most-recent objects cannot be deleted sets, "live" servers, or generic sets.

		if (includeMostRecent) {
			theMap = mrCache.get(theDbID);
			if (null != theMap) {
				ExtDb theDb;
				int theKey;
				for (Map.Entry<Integer, ExtDb> e : theMap.entrySet()) {
					theDb = e.getValue();
					if (((0 == recordType) || (recordType == theDb.recordType)) &&
							((0 == dbType) || (dbType == theDb.type)) &&
							(theDb.version >= minVersion)) {
						theKey = e.getKey().intValue();
						dbList.add(new DbListItem(theKey, getMostRecentName(theKey), theDb.type));
					}
				}
			}
		}

		// The list is sorted first by database type using an ordering map, second by keys being in the reserved range
		// or not, third for reserved by ascending order of key, else by descending order of key.

		Comparator<DbListItem> comp = new Comparator<DbListItem>() {
			public int compare(DbListItem one, DbListItem two) {
				if (DB_TYPE_ORDER[one.type - 1] < DB_TYPE_ORDER[two.type - 1]) {
					return -1;
				} else {
					if (DB_TYPE_ORDER[one.type - 1] > DB_TYPE_ORDER[two.type - 1]) {
						return 1;
					} else {
						if ((one.key >= RESERVED_KEY_RANGE_START) && (one.key <= RESERVED_KEY_RANGE_END)) {
							if ((two.key >= RESERVED_KEY_RANGE_START) && (two.key <= RESERVED_KEY_RANGE_END)) {
								if (one.key < two.key) {
									return -1;
								} else {
									if (one.key > two.key) {
										return 1;
									}
								}
							} else {
								return -1;
							}
						} else {
							if ((two.key >= RESERVED_KEY_RANGE_START) && (two.key <= RESERVED_KEY_RANGE_END)) {
								return 1;
							} else {
								if (one.key > two.key) {
									return -1;
								} else {
									if (one.key < two.key) {
										return 1;
									}
								}
							}
						}
					}
				}
				return 0;
			}
		};

		Collections.sort(dbList, comp);

		return new ArrayList<KeyedRecord>(dbList);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static String getMostRecentName(int theKey) {

		switch (theKey) {
			case KEY_MOST_RECENT_LMS:
				return "Most recent LMS TV";
			case KEY_MOST_RECENT_CDBS:
				return "Most recent CDBS TV";
			case KEY_MOST_RECENT_CDBS_FM:
				return "Most recent CDBS FM";
			default:
				return "???";
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add and remove objects that are notified when cache is reloaded for a database.

	private static HashMap<String, HashSet<ExtDbListener>> listeners = new HashMap<String, HashSet<ExtDbListener>>();

	public static synchronized void addListener(ExtDbListener theListener) {

		String theDbID = theListener.getDbID();
		HashSet<ExtDbListener> theListeners = listeners.get(theDbID);
		if (null == theListeners) {
			theListeners = new HashSet<ExtDbListener>();
			listeners.put(theDbID, theListeners);
		}
		theListeners.add(theListener);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static synchronized void removeListener(ExtDbListener theListener) {

		String theDbID = theListener.getDbID();
		HashSet<ExtDbListener> theListeners = listeners.get(theDbID);
		if (null != theListeners) {
			theListeners.remove(theListener);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check a data set name for validity.  An existing name may be provided for comparison.  If the new name matches
	// the old name ignoring case the change is allowed without any further checks.  A reserved character is used as
	// part of a suffix that may be added to make the name unique (see createNewDatabase()), that character cannot be
	// used in a new name.  But since uniqueness is case-insensitive, changes to an existing name that only affect case
	// will not violate uniqueness and so can be allowed while preserving an existing uniqueness suffix.  If the new
	// name does not match or there is no old name, the new name is checked for length, for the reserved character used
	// in the uniqueness suffix, and for the reserved name used to indicate an unnamed downloaded set.  An empty new
	// name is allowed.  Finally, the name is checked against the existing data set cache for uniqueness (only non-
	// deleted sets need to be checked since the name is cleared when a set is deleted).  Note by the time the actual
	// database create occurs it's possible concurrent actions will result in the name no longer being unique, but that
	// is the case when the uniqueness suffix will be added.

	public static boolean checkExtDbName(String dbID, String newName) {
		return checkExtDbName(dbID, newName, null, null);
	}

	public static boolean checkExtDbName(String dbID, String newName, ErrorLogger errors) {
		return checkExtDbName(dbID, newName, null, errors);
	}

	public static boolean checkExtDbName(String dbID, String newName, String oldName) {
		return checkExtDbName(dbID, newName, oldName, null);
	}

	public static boolean checkExtDbName(String dbID, String newName, String oldName, ErrorLogger errors) {

		if ((null != oldName) && (oldName.length() > 0)) {
			if (newName.equalsIgnoreCase(oldName)) {
				return true;
			}
		}

		if (0 == newName.length()) {
			return true;
		}

		if (newName.length() > DbCore.NAME_MAX_LENGTH) {
			if (null != errors) {
				errors.reportWarning("The data set name cannot be more than " + DbCore.NAME_MAX_LENGTH +
					" characters long.");
			}
			return false;
		}

		if (newName.contains(String.valueOf(DbCore.NAME_UNIQUE_CHAR))) {
			if (null != errors) {
				errors.reportWarning("The data set name cannot contain the character '" + DbCore.NAME_UNIQUE_CHAR +
					"'");
			}
			return false;
		}

		if (newName.equalsIgnoreCase(DOWNLOAD_SET_NAME)) {
			if (null != errors) {
				errors.reportWarning("That data set name cannot be used, please try again.");
			}
			return false;
		}

		if (null != getExtDb(dbID, newName, errors)) {
			if (null != errors) {
				errors.reportWarning("That data set name is already in use, please try again.");
			}
			return false;
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Do an immediate cache reload for a database, and notify listeners.

	public static synchronized void reloadCache(String theDbID) {

		if (null == updateCache(theDbID, null)) {
			return;
		}

		HashSet<ExtDbListener> theListeners = listeners.get(theDbID);
		if (null != theListeners) {
			for (ExtDbListener theListener : theListeners) {
				theListener.updateExtDbList();
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get cache map for a database, update if needed based on time since last cache update, or if there is no cache
	// for the requested database.

	private static HashMap<Integer, ExtDb> getCache(String theDbID, ErrorLogger errors) {

		HashMap<Integer, ExtDb> theMap = null;

		Long lastUpdate = cacheLastUpdate.get(theDbID);
		if ((null != lastUpdate) && ((System.currentTimeMillis() - lastUpdate.longValue()) < CACHE_UPDATE_INTERVAL)) {
			theMap = dbCache.get(theDbID);
		}

		if (null == theMap) {
			theMap = updateCache(theDbID, errors);
		}

		return theMap;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update the cache of objects for a given database, returns null on error.  Note because rows in the ext_db table
	// are not ever deleted this does not empty and reload the cache, it updates existing content by key.  A separate
	// set of maps is used to look up psuedo-keys for the "most-recent" functions.

	private static HashMap<String, HashMap<Integer, ExtDb>> dbCache = new HashMap<String, HashMap<Integer, ExtDb>>();
	private static HashMap<String, HashMap<Integer, ExtDb>> mrCache = new HashMap<String, HashMap<Integer, ExtDb>>();
	private static HashMap<String, Long> cacheLastUpdate = new HashMap<String, Long>();

	private static HashMap<Integer, ExtDb> updateCache(String theDbID, ErrorLogger errors) {

		HashMap<Integer, ExtDb> theMap = dbCache.get(theDbID);
		HashMap<Integer, ExtDb> mrMap = mrCache.get(theDbID);

		// If a cache for this dbID does not exist create one, and add live server objects as needed.  Since those are
		// more or less constant "placeholder" objects they only need to be added once and do not need to be updated.

		if (null == theMap) {

			theMap = new HashMap<Integer, ExtDb>();
			mrMap = new HashMap<Integer, ExtDb>();

			dbCache.put(theDbID, theMap);
			mrCache.put(theDbID, mrMap);

			ExtDb theExtDb = getLMSLiveExtDb(theDbID, errors);
			if (null != theExtDb) {
				theMap.put(theExtDb.key, theExtDb);
			}
		}

		// Run the lookup query, add/update objects as needed.  The query is ordered by descending key value so the
		// data sets imported later are loaded earlier.  This is for the most-recent logic, if two data sets have the
		// same date the one imported later is preferred and needs to be encountered first.

		String rootName = DbCore.getDbName(theDbID);

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null != db) {
			try {

				db.query("SELECT ext_db_key, db_type, db_date, version, id, name, deleted, is_download FROM ext_db " +
					"ORDER BY 1 DESC");

				Integer theKey, mrKey;
				ExtDb theExtDb, mrExtDb;
				int theType, theRecordType = 0;
				String theDbName = "";

				// Remove any existing most-recent objects, new ones will be added as seen.

				mrMap.remove(Integer.valueOf(KEY_MOST_RECENT_LMS));
				mrMap.remove(Integer.valueOf(KEY_MOST_RECENT_CDBS));
				mrMap.remove(Integer.valueOf(KEY_MOST_RECENT_CDBS_FM));

				while (db.next()) {

					theKey = Integer.valueOf(db.getInt(1));
					theExtDb = theMap.get(theKey);

					if (null == theExtDb) {

						theType = db.getInt(2);
						theDbName = makeDbName(rootName, theType, theKey);
						theRecordType = recordTypeForDBType(theType);
						if (0 == theRecordType) {
							continue;
						}

						theExtDb = new ExtDb(
							theDbID,
							theKey,
							theDbName,
							db.getDate(3),
							theType,
							db.getInt(4),
							theRecordType,
							false);

						theMap.put(theExtDb.key, theExtDb);
					}

					theExtDb.id = db.getString(5);
					if (null == theExtDb.id) {
						theExtDb.id = "";
					}

					theExtDb.name = db.getString(6);
					if (null == theExtDb.name) {
						theExtDb.name = "";
					}

					theExtDb.deleted = db.getBoolean(7);

					theExtDb.isDownload = db.getBoolean(8);

					if (theExtDb.name.length() > 0) {
						theExtDb.description = theExtDb.getTypeName() + " " + theExtDb.name +
							(theExtDb.deleted ? " (deleted)" : "");
					} else {
						theExtDb.description = theExtDb.getTypeName() + " " + theExtDb.id +
							(theExtDb.deleted ? " (deleted)" : "");
					}

					// Update most-recent objects as needed, if the new object is not deleted.

					if (!theExtDb.deleted) {

						switch (theExtDb.type) {

							case DB_TYPE_LMS: {
								mrKey = Integer.valueOf(KEY_MOST_RECENT_LMS);
								mrExtDb = mrMap.get(mrKey);
								if ((null == mrExtDb) || theExtDb.dbDate.after(mrExtDb.dbDate)) {
									mrMap.put(mrKey, theExtDb);
								}
								break;
							}

							case DB_TYPE_CDBS: {
								mrKey = Integer.valueOf(KEY_MOST_RECENT_CDBS);
								mrExtDb = mrMap.get(mrKey);
								if ((null == mrExtDb) || theExtDb.dbDate.after(mrExtDb.dbDate)) {
									mrMap.put(mrKey, theExtDb);
								}
								break;
							}

							case DB_TYPE_CDBS_FM: {
								mrKey = Integer.valueOf(KEY_MOST_RECENT_CDBS_FM);
								mrExtDb = mrMap.get(mrKey);
								if ((null == mrExtDb) || theExtDb.dbDate.after(mrExtDb.dbDate)) {
									mrMap.put(mrKey, theExtDb);
								}
								break;
							}
						}
					}
				}

				DbCore.releaseDb(db);

				cacheLastUpdate.put(theDbID, Long.valueOf(System.currentTimeMillis()));

			} catch (SQLException se) {
				DbCore.releaseDb(db);
				theMap = null;
				DbConnection.reportError(errors, se);
			}

		} else {
			theMap = null;
		}

		return theMap;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get an object wrapping a connection to a "live" LMS server.  The ExtDb objects are specific to a dbID and are
	// cached, however the actual DbConnection, and objects to manage a connection pool, are static and are shared by
	// all of the ExtDb objects.  On first use, this will attempt to read server login properties from a local file
	// and open the initial connection.  If the properties file can't be opened or does not contain all necessary
	// properties this fails silently; however if a connection is attempted failures will be reported.  In any case if
	// the read or connect fails it is not attempted again.  This should only be called from synchronized methods.
	// Note the key assigned to the ExtDb object is constant and the version is always the current version.

	private static HashMap<String, ExtDb> lmsLiveDbCache = new HashMap<String, ExtDb>();

	private static boolean lmsLiveDidTryOpen;

	private static String lmsLiveDbName;
	private static DbConnection lmsLiveDb;
	private static ArrayDeque<DbConnection> lmsLiveDbPool;
	private static HashSet<DbConnection> lmsLiveOpenDbs;

	private static ExtDb getLMSLiveExtDb(String theDbID, ErrorLogger errors) {

		ExtDb theExtDb = lmsLiveDbCache.get(theDbID);
		if (null != theExtDb) {
			return theExtDb;
		}

		if (!lmsLiveDidTryOpen) {

			lmsLiveDidTryOpen = true;

			Properties props = new Properties();
			try {
				props.load(new FileInputStream(new File(AppCore.libDirectoryPath + File.separator +
					AppCore.API_PROPS_FILE_NAME)));
			} catch (IOException e) {
				return null;
			}

			String theDriver = props.getProperty("lms_driver");
			String theHost = props.getProperty("lms_host");
			lmsLiveDbName = props.getProperty("lms_name");
			String theUser = props.getProperty("lms_user");
			String thePass = props.getProperty("lms_pass");

			if ((null == theDriver) || (null == theHost) || (null == lmsLiveDbName) || (null == theUser) ||
					(null == thePass)) {
				return null;
			}

			DbConnection db = new DbConnection(theDriver, theHost, theUser, thePass);
			if (db.connect(lmsLiveDbName)) {

				db.close();

				lmsLiveDb = db;

				lmsLiveDbPool = new ArrayDeque<DbConnection>();
				lmsLiveDbPool.push(lmsLiveDb);
				lmsLiveOpenDbs = new HashSet<DbConnection>();

			} else {
				if (null != errors) {
					errors.reportError("Cannot open live LMS connection, properties may be invalid.");
				}
			}
		}

		if (null != lmsLiveDb) {

			theExtDb = new ExtDb(theDbID, Integer.valueOf(KEY_LMS_LIVE), lmsLiveDbName, new java.util.Date(),
				DB_TYPE_LMS_LIVE, LMS_VERSION, Source.RECORD_TYPE_TV, true);

			theExtDb.id = "";
			theExtDb.name = "LMS TV live server";
			theExtDb.description = theExtDb.name;

			theExtDb.liveDb = lmsLiveDb;
			theExtDb.dbPool = lmsLiveDbPool;
			theExtDb.openDbs = lmsLiveOpenDbs;

			lmsLiveDbCache.put(theDbID, theExtDb);
		}

		return theExtDb;
	}


	//=================================================================================================================
	// Item for antenna search results.

	public static class AntennaID {

		public String dbID;
		public Integer extDbKey;
		public String antennaRecordID;

		public String antennaID;
		public String name;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Do a search for antennas, normally a search by azimuth pattern, argument can request elevation pattern.  That
	// only matters for CDBS and wireless, in LMS data antenna identification is independent of pattern type.  If the
	// search string converts to a positive number match the numeric antenna ID in CDBS or wireless, or the descriptive
	// ID in LMS.  Otherwise, for CDBS or LMS if the search string is short enough to be a manufacturer code match the
	// make, and always match the model for CDBS/LMS or the name for wireless.  All the matches are combined with OR.
	// This returns null on error.

	public static ArrayList<AntennaID> findAntennas(String theDbID, Integer extDbKey, String search,
			boolean searchElevation) {
		return findAntennas(theDbID, extDbKey, search, searchElevation, null);
	}

	public static ArrayList<AntennaID> findAntennas(String theDbID, Integer extDbKey, String search,
			boolean searchElevation, ErrorLogger errors) {

		ExtDb extDb = getExtDb(theDbID, extDbKey, errors);
		if (null == extDb) {
			return null;
		}

		ArrayList<AntennaID> theItems = new ArrayList<AntennaID>();

		// Compose the query.

		String str = DbConnection.clean(search.trim().toUpperCase().replace('*', '%'));
		if (0 == str.length()) {
			return theItems;
		}

		int id = 0;
		try {
			id = Integer.parseInt(search);
		} catch (NumberFormatException ne) {
		}

		StringBuilder query = new StringBuilder();

		switch (extDb.type) {

			case DB_TYPE_CDBS:
			case DB_TYPE_CDBS_FM: {

				String fld = "", tbl = "";
				if (searchElevation) {
					fld = "elevation_antenna_id";
					tbl = "elevation_ant_make";
				} else {
					fld = "antenna_id";
					tbl = "ant_make";
				}

				query.append("SELECT ");
				query.append(fld);
				query.append(", ");
				query.append(fld);
				query.append(", CONCAT(ant_make, '-', ant_model_num) FROM ");
				query.append(tbl);
				query.append(" WHERE ");

				if (id > 0) {
					query.append("(");
					query.append(fld);
					query.append(" = ");
					query.append(String.valueOf(id));
					query.append(") ");
				} else {
					if (search.length() <= 3) {
						query.append("(UPPER(ant_make) LIKE '%");
						query.append(str);
						query.append("%') OR ");
					}
					query.append("(UPPER(ant_model_num) LIKE '%");
					query.append(str);
					query.append("%') ");
				}

				query.append("ORDER BY 3");

				break;
			}

			case DB_TYPE_LMS:
			case DB_TYPE_LMS_LIVE: {

				query.append("SELECT aant_antenna_record_id, aant_antenna_id, ");
				query.append("CONCAT(aant_make, '-', aant_model) FROM ");
				if (DB_TYPE_LMS_LIVE == extDb.type) {
					query.append("mass_media.");
				}
				query.append("app_antenna WHERE ");

				if (id > 0) {
					query.append("(aant_antenna_id = '");
					query.append(String.valueOf(id));
					query.append("') ");
				} else {
					if (search.length() <= 3) {
						query.append("(UPPER(aant_make) LIKE '%");
						query.append(str);
						query.append("%') OR ");
					}
					query.append("(UPPER(aant_model) LIKE '%");
					query.append(str);
					query.append("%') ");
				}

				query.append("ORDER BY 3");

				break;
			}

			case DB_TYPE_WIRELESS: {

				query.append("SELECT ant_id, ant_id, name FROM antenna_index WHERE " );

				if (searchElevation) {
					query.append("(pat_type = 'E') AND ");
				} else {
					query.append("(pat_type = 'A') AND ");
				}

				if (id > 0) {
					query.append("(ant_id = ");
					query.append(String.valueOf(id));
					query.append(") ");
				} else {
					query.append("(UPPER(name) LIKE '%");
					query.append(str);
					query.append("%') ");
				}

				query.append("ORDER BY 3");

				break;
			}

			// For generic data sets, stored in the Source record format, match the source_key if the search string is
			// numeric (that is the antennaRecordID for these), or the appropriate name by substring match.

			case DB_TYPE_GENERIC_TV:
			case DB_TYPE_GENERIC_WL:
			case DB_TYPE_GENERIC_FM: {

				String nameFld = "horizontal_pattern_name";
				if (searchElevation) {
					nameFld = "vertical_pattern_name";
				}

				query.append("SELECT source_key, source_key, ");
				query.append(nameFld);
				query.append(" FROM source WHERE " );

				if (searchElevation) {
					query.append("has_vertical_pattern AND (");
				} else {
					query.append("has_horizontal_pattern AND (");
				}

				if (id > 0) {
					query.append("(source_key = ");
					query.append(str);
					query.append(") OR ");
				}

				query.append("(UPPER(");
				query.append(nameFld);
				query.append(") LIKE '%");
				query.append(str);
				query.append("%')) ");

				query.append("ORDER BY 3");

				break;
			}
		}

		// Do the search.

		AntennaID theItem;

		DbConnection db = extDb.connectDb(errors);
		if (null != db) {
			try {

				db.query(query.toString());

				while (db.next()) {
					theItem = new AntennaID();
					theItem.dbID = extDb.dbID;
					theItem.extDbKey = extDb.key;
					theItem.antennaRecordID = db.getString(1);
					theItem.antennaID = db.getString(2);
					theItem.name = db.getString(3);
					theItems.add(theItem);
				}

				extDb.releaseDb(db);

			} catch (SQLException se) {
				extDb.releaseDb(db);
				DbConnection.reportError(errors, se);
				return null;
			}

		} else {
			return null;
		}

		return theItems;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Do an auxiliary search for AM stations within specified distance of a set of coordinates, append results to a
	// text report.  Used by automated-run study types e.g. interference check.  It is here rather than in the study
	// engine because it needs direct access to the external station data tables.  This does not fail; if the data set
	// does not support AM, or if any error happens during the query, this simply writes in the report that the check
	// could not be performed.  Note such failures would include the relevant tables not existing in the data set; the
	// files for these are optional on import and so may not always be present even if the version supports them.

	public static void checkForAMStations(ExtDb extDb, GeoPoint targetLocation, double searchDistanceND,
			double searchDistanceDA, double kilometersPerDegree, StringBuilder report) {

		boolean error = false;

		if (!extDb.hasAM()) {
			error = true;
		} else {

			DbConnection db = extDb.connectDb();
			if (null == db) {
				error = true;
			} else {

				GeoPoint point = new GeoPoint();
				String dir;
				boolean foundND = false, foundDA = false;
				StringBuilder repND = new StringBuilder(), repDA = new StringBuilder();
				String mode;

				try {

					switch (extDb.type) {

						case DB_TYPE_CDBS: {
							db.query(
							"SELECT " +
								"am_ant_sys.lat_dir," +
								"am_ant_sys.lat_deg," +
								"am_ant_sys.lat_min," +
								"am_ant_sys.lat_sec," +
								"am_ant_sys.lon_dir," +
								"am_ant_sys.lon_deg," +
								"am_ant_sys.lon_min," +
								"am_ant_sys.lon_sec," +
								"facility.fac_callsign, " +
								"application.fac_frequency, " +
								"am_ant_sys.am_dom_status," +
								"CONCAT(am_ant_sys.ant_mode, ' ', am_ant_sys.hours_operation)," +
								"facility.comm_city, " +
								"facility.comm_state, " +
								"application.file_prefix, " +
								"application.app_arn " +
							"FROM " +
								"am_ant_sys " +
								"JOIN application USING (application_id) " +
								"JOIN facility USING (facility_id) " +
							"WHERE " +
								"(am_ant_sys.eng_record_type NOT IN ('P','A','R')) " +
							"ORDER BY " +
								"14, 13, 10, 12");
							break;
						}

						case DB_TYPE_LMS: {
							db.query(
							"SELECT " +
								"gis_am_ant_sys.lat_dir," +
								"gis_am_ant_sys.lat_deg," +
								"gis_am_ant_sys.lat_min," +
								"gis_am_ant_sys.lat_sec," +
								"gis_am_ant_sys.lon_dir," +
								"gis_am_ant_sys.lon_deg," +
								"gis_am_ant_sys.lon_min," +
								"gis_am_ant_sys.lon_sec," +
								"gis_facility.fac_callsign, " +
								"gis_application.fac_frequency, " +
								"gis_am_ant_sys.am_dom_status," +
								"CONCAT(gis_am_ant_sys.ant_mode, ' ', gis_am_ant_sys.hours_operation)," +
								"gis_facility.comm_city, " +
								"gis_facility.comm_state, " +
								"gis_application.file_prefix, " +
								"gis_application.app_arn " +
							"FROM " +
								"gis_am_ant_sys " +
								"JOIN gis_application USING (application_id) " +
								"JOIN gis_facility USING (facility_id) " +
							"WHERE " +
								"(gis_am_ant_sys.eng_record_type NOT IN ('P','A','R')) " +
							"ORDER BY " +
								"14, 13, 10, 12");
							break;
						}

						case DB_TYPE_LMS_LIVE: {
							db.query(
							"SELECT " +
								"gis_am_ant_sys.lat_dir," +
								"gis_am_ant_sys.lat_deg," +
								"gis_am_ant_sys.lat_min," +
								"gis_am_ant_sys.lat_sec," +
								"gis_am_ant_sys.lon_dir," +
								"gis_am_ant_sys.lon_deg," +
								"gis_am_ant_sys.lon_min," +
								"gis_am_ant_sys.lon_sec," +
								"gis_facility.fac_callsign, " +
								"gis_application.fac_frequency, " +
								"gis_am_ant_sys.am_dom_status," +
								"gis_am_ant_sys.ant_mode || ' ' || gis_application.hours_operation," +
								"gis_facility.comm_city, " +
								"gis_facility.comm_state, " +
								"gis_application.file_prefix, " +
								"gis_application.app_arn " +
							"FROM " +
								"mass_media.gis_am_ant_sys " +
								"JOIN mass_media.gis_application USING (application_id) " +
								"JOIN mass_media.gis_facility USING (facility_id) " +
							"WHERE " +
								"(gis_am_ant_sys.eng_record_type NOT IN ('P','A','R')) " +
							"ORDER BY " +
								"14, 13, 10, 12");
							break;
						}

						default: {
							error = true;
							break;
						}
					}

					if (!error) {

						while (db.next()) {

							point.latitudeNS = 0;
							dir = db.getString(1);
							if ((null != dir) && dir.equalsIgnoreCase("S")) {
								point.latitudeNS = 1;
							}
							point.latitudeDegrees = db.getInt(2);
							point.latitudeMinutes = db.getInt(3);
							point.latitudeSeconds = db.getDouble(4);

							point.longitudeWE = 0;
							dir = db.getString(5);
							if ((null != dir) && dir.equalsIgnoreCase("E")) {
								point.longitudeWE = 1;
							}
							point.longitudeDegrees = db.getInt(6);
							point.longitudeMinutes = db.getInt(7);
							point.longitudeSeconds = db.getDouble(8);

							point.updateLatLon();
							point.convertFromNAD27();

							mode = db.getString(12);

							if (mode.startsWith("ND")) {

								if (targetLocation.distanceTo(point, kilometersPerDegree) <= searchDistanceND) {
									if (!foundND) {
										repND.append(String.format(Locale.US,
											"Non-directional AM stations within %.1f km:\n", searchDistanceND));
										foundND = true;
									}
									repND.append(db.getString(9) + " " + db.getInt(10) + " " + db.getString(11) +
										" " + mode + " " + db.getString(13) + ", " + db.getString(14) + " " +
										db.getString(15) + db.getString(16) + "\n");
								}

							} else {

								if (targetLocation.distanceTo(point, kilometersPerDegree) <= searchDistanceDA) {
									if (!foundDA) {
										repDA.append(String.format(Locale.US,
											"Directional AM stations within %.1f km:\n", searchDistanceDA));
										foundDA = true;
									}
									repDA.append(db.getString(9) + " " + db.getInt(10) + " " + db.getString(11) +
										" " + mode + " " + db.getString(13) + ", " + db.getString(14) + " " +
										db.getString(15) + db.getString(16) + "\n");
								}
							}
						}

						if (foundND) {
							repND.append("\n");
						} else {
							repND.append(String.format(Locale.US,
								"No non-directional AM stations found within %.1f km\n\n", searchDistanceND));
						}
						report.append(repND);

						if (foundDA) {
							repDA.append("\n");
						} else {
							repDA.append(String.format(Locale.US,
								"No directional AM stations found within %.1f km\n\n", searchDistanceDA));
						}
						report.append(repDA);
					}

				} catch (SQLException se) {
					DbConnection.reportError(se);
					error = true;
				}

				extDb.releaseDb(db);
			}
		}

		if (error) {
			report.append("Data is not available for AM station check\n\n");
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create a new generic import data set database, which stores data in a table structure created by the Source
	// class, identical to what is used in an actual study database.  Searches are managed by SourceEditData rather
	// than ExtDbRecord.  The database is initially empty, the caller will populate it by creating SourceEditData
	// objects and saving those directly.  It is possible to add to a generic data set with multiple imports.

	public static Integer createNewGenericDatabase(String theDbID, int dataType, String theName) {
		return createNewGenericDatabase(theDbID, dataType, theName, null);
	}

	public static Integer createNewGenericDatabase(String theDbID, int dataType, String theName, ErrorLogger errors) {

		if (!isGeneric(dataType)) {
			if (null != errors) {
				errors.reportError("Cannot create station data, unknown or unsupported data type.");
			}
			return null;
		}

		// Open database connection, lock tables, get a new key for the data set.

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return null;
		}

		String errmsg = null, theDbName = null, rootName = DbCore.getDbName(theDbID);
		boolean error = false;
		int newKey = 0;

		try {

			db.update("LOCK TABLES ext_db_key_sequence WRITE");

			db.update("UPDATE ext_db_key_sequence SET ext_db_key = ext_db_key + 1");
			db.query("SELECT ext_db_key FROM ext_db_key_sequence");
			db.next();
			newKey = db.getInt(1);

			// A range of keys is reserved for internally-composed objects, skip if needed.

			if (RESERVED_KEY_RANGE_START == newKey) {
				newKey = RESERVED_KEY_RANGE_END + 1;
				db.update("UPDATE ext_db_key_sequence SET ext_db_key = " + newKey);
			}

			db.update("UNLOCK TABLES");

			// Create the database and tables.

			theDbName = makeDbName(rootName, dataType, Integer.valueOf(newKey));
			db.update("CREATE DATABASE " + theDbName + " CHARACTER SET latin1");
			Source.createTables(db, theDbName);

			// Generate ID string and save the index record.

			String theID = AppCore.formatDateTime(new java.util.Date());

			if (null == theName) {
				theName = "";
			}

			db.setDatabase(rootName);

			// Check the ID and name for uniqueness, append the key as a suffix if needed.  Save the new index entry in
			// the ext_db table.

			db.update("LOCK TABLES ext_db WRITE");

			db.query("SELECT ext_db_key FROM ext_db WHERE (id = '" + db.clean(theID) + "')");
			if (db.next()) {
				theID = theID + " " + String.valueOf(DbCore.NAME_UNIQUE_CHAR) + String.valueOf(newKey);
			}

			if (theName.length() > 0) {
				db.query("SELECT ext_db_key FROM ext_db WHERE (UPPER(name) = '" +
					db.clean(theName.toUpperCase()) + "')");
				if (db.next()) {
					theName = theName + " " + String.valueOf(DbCore.NAME_UNIQUE_CHAR) + String.valueOf(newKey);
				}
			}

			db.update(
			"INSERT INTO ext_db (" +
				"ext_db_key, " +
				"db_type, " +
				"db_date, " +
				"version, " +
				"id, " +
				"name, " +
				"deleted, " +
				"locked, " +
				"is_download) "+
			"VALUES (" +
				newKey + ", "  +
				dataType + ", " +
				"NOW(), " +
				"0, " +
				"'" + db.clean(theID) + "', " +
				"'" + db.clean(theName) + "', " +
				"false, " +
				"false, " +
				"false)");

		} catch (SQLException se) {
			errmsg = "A database error occurred:\n" + se;
			error = true;
			db.reportError(se);
		}

		// Make sure table locks are released, if an error occurred also drop the database.

		try {
			db.update("UNLOCK TABLES");
			if (error && (null != theDbName)) {
				db.update("DROP DATABASE IF EXISTS " + theDbName);
			}
		} catch (SQLException se) {
			db.reportError(se);
		}

		DbCore.releaseDb(db);

		if ((null != errmsg) && (null != errors)) {
			if (error) {
				errors.reportError(errmsg);
			} else {
				errors.reportWarning(errmsg);
			}
		}

		if (error) {
			return null;
		}

		reloadCache(theDbID);

		return Integer.valueOf(newKey);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Wrapper around createNewDatabase() for creating a new wireless database.  The source files are in CSV format
	// and are first translated into temporary SQL-format data files, then imported normally as with other data types,
	// see below.  The data in the CSV files is often manually-composed so errors are much more likely than in typical
	// SQL dump files, and this approach means errors are detected and reported before starting the SQL session.
	// Returns the key for the new database, or null on error.

	public static Integer createNewWirelessDatabase(String theDbID, File stationFile, File patternFile,
			String theName) {
		return createNewWirelessDatabase(theDbID, stationFile, patternFile, theName, null, null);
	}

	public static Integer createNewWirelessDatabase(String theDbID, File stationFile, File patternFile, String theName,
			ErrorLogger errors) {
		return createNewWirelessDatabase(theDbID, stationFile, patternFile, theName, null, errors);
	}

	public static Integer createNewWirelessDatabase(String theDbID, File stationFile, File patternFile, String theName,
			StatusLogger status) {
		return createNewWirelessDatabase(theDbID, stationFile, patternFile, theName, status, null);
	}

	public static Integer createNewWirelessDatabase(String theDbID, File stationFile, File patternFile, String theName,
			StatusLogger status, ErrorLogger errors) {

		String errmsg = null;

		BufferedReader stationReader = null, patternReader = null;
		File theDirectory = null, baseFile = null, patIndexFile = null, patFile = null;
		BufferedWriter baseWriter = null, patIndexWriter = null, patWriter = null;

		// Open the CSV input files.

		try {
			stationReader = new BufferedReader(new FileReader(stationFile));
		} catch (FileNotFoundException fe) {
			errmsg = "Data file '" + stationFile.getName() + "' could not be opened";
		}

		if (null == errmsg) {
			try {
				patternReader = new BufferedReader(new FileReader(patternFile));
			} catch (FileNotFoundException fe) {
				errmsg = "Data file '" + patternFile.getName() + "' could not be opened";
			}
		}

		// Create a temporary directory and the output table files.

		if (null == errmsg) {

			try {

				theDirectory = Files.createTempDirectory("wl_import").toFile();

				baseFile = new File(theDirectory, ExtDb.WIRELESS_BASE_FILE);
				baseWriter = new BufferedWriter(new FileWriter(baseFile));

				patIndexFile = new File(theDirectory, ExtDb.WIRELESS_INDEX_FILE);
				patIndexWriter = new BufferedWriter(new FileWriter(patIndexFile));

				patFile = new File(theDirectory, ExtDb.WIRELESS_PATTERN_FILE);
				patWriter = new BufferedWriter(new FileWriter(patFile));

			} catch (IOException ie) {
				errmsg = "Could not create temporary files for data transfer";
			}
		}

		// Copy data from the main station file.  Assign a key to each row, just use the line counter.  In other types
		// of station data the primary key is persistent across different imports but that isn't possible here, so the
		// keys are valid only within a specific import.  The keys will never be exported.

		if (null == errmsg) {

			String fileName = "", line, str;
			AppCore.Counter lineCount = new AppCore.Counter(0);
			String[] fields;

			try {

				fileName = stationFile.getName();

				String cellSiteID, sectorID, referenceNumber, city, state, country;
				double cellLat, cellLon, rcAMSL, haat, erp, orientation, eTilt, mTilt, mTiltOrientation;
				int azAntID, elAntID;

				while (true) {

					line = AppCore.readLineSkipComments(stationReader, lineCount);
					if (null == line) {
						break;
					}
					if (line.contains("|")) {
						errmsg = "Illegal character '|' in '" + fileName + "' at line " + lineCount;
						break;
					}
					fields = line.split(",");

					// The reference number, city, state, and country fields at the end of the line are optional and
					// may all be missing or empty if present.

					if ((fields.length < 13) || (fields.length > 17)) {
						errmsg = "Bad field count in '" + fileName + "' at line " + lineCount;
						break;
					}

					// The cell site ID string must not be empty, the sector ID may be.  The site and sector IDs have
					// limited length, if exceeded truncate and log an informational message.  Neither may contain a
					// '|' character as that is used in the SQL data file as field separator.

					cellSiteID = fields[0].trim();
					if (0 == cellSiteID.length()) {
						errmsg = "Missing cell site ID in '" + fileName + "' at line " + lineCount;
						break;
					}
					if (cellSiteID.length() > Source.MAX_CALL_SIGN_LENGTH) {
						cellSiteID = cellSiteID.substring(0, Source.MAX_CALL_SIGN_LENGTH);
						if (null != errors) {
							errors.logMessage("Cell site ID too long, truncated, in '" + fileName + "' at line " +
								lineCount);
						}
					}

					sectorID = fields[1].trim();
					if (sectorID.length() > Source.MAX_SECTOR_ID_LENGTH) {
						sectorID = sectorID.substring(0, Source.MAX_SECTOR_ID_LENGTH);
						if (null != errors) {
							errors.logMessage("Sector ID too long, truncated, in '" + fileName + "' at line " +
								lineCount);
						}
					}

					// Check for valid numbers in latitude, longitude, AMSL height, HAAT, and ERP.

					cellLat = Source.LATITUDE_MIN - 1.;
					str = fields[2].trim();
					if (str.length() > 0) {
						try {
							cellLat = Double.parseDouble(str);
						} catch (NumberFormatException ne) {
						}
					}
					if ((cellLat < Source.LATITUDE_MIN) || (cellLat > Source.LATITUDE_MAX)) {
						errmsg = "Missing or bad latitude in '" + fileName + "' at line " + lineCount;
						break;
					}

					// Longitude is negative west in this data, reverse the sign.

					cellLon = Source.LONGITUDE_MIN - 1.;
					str = fields[3].trim();
					if (str.length() > 0) {
						try {
							cellLon = -Double.parseDouble(str);
						} catch (NumberFormatException ne) {
						}
					}
					if ((cellLon < Source.LONGITUDE_MIN) || (cellLon > Source.LONGITUDE_MAX)) {
						errmsg = "Missing or bad longitude in '" + fileName + "' at line " + lineCount;
						break;
					}

					rcAMSL = Source.HEIGHT_MIN - 1.;
					str = fields[4].trim();
					if (str.length() > 0) {
						try {
							rcAMSL = Double.parseDouble(str);
						} catch (NumberFormatException ne) {
						}
					}
					if ((rcAMSL < Source.HEIGHT_MIN) || (rcAMSL > Source.HEIGHT_MAX)) {
						errmsg = "Missing or bad AMSL height in '" + fileName + "' at line " + lineCount;
						break;
					}

					haat = Source.HEIGHT_MIN - 1.;
					str = fields[5].trim();
					if (str.length() > 0) {
						try {
							haat = Double.parseDouble(str);
						} catch (NumberFormatException ne) {
						}
					}
					if ((haat < Source.HEIGHT_MIN) || (haat > Source.HEIGHT_MAX)) {
						errmsg = "Missing or bad HAAT in '" + fileName + "' at line " + lineCount;
						break;
					}

					erp = Source.ERP_MIN - 1.;
					str = fields[6].trim();
					if (str.length() > 0) {
						try {
							erp = Double.parseDouble(str);
						} catch (NumberFormatException ne) {
						}
					}
					if ((erp < Source.ERP_MIN) || (erp > Source.ERP_MAX)) {
						errmsg = "Bad ERP in '" + fileName + "' at line " + lineCount;
						break;
					}

					azAntID = 0;
					str = fields[7].trim();
					if (str.length() > 0) {
						azAntID = -1;
						try {
							azAntID = Integer.parseInt(str);
						} catch (NumberFormatException ne) {
						}
						if (azAntID < 0) {
							errmsg = "Bad azimuth antenna ID in '" + fileName + "' at line " + lineCount;
							break;
						}
					}

					// Orientation and tilt values are always parsed even if pattern ID indicates omni, and all default
					// to 0 if the field is empty, again regardless of omni.

					orientation = 0.;
					str = fields[8].trim();
					if (str.length() > 0) {
						try {
							orientation = Math.IEEEremainder(Double.parseDouble(str), 360.);
							if (orientation < 0.) orientation += 360.;
						} catch (NumberFormatException ne) {
							errmsg = "Bad pattern orientation in '" + fileName + "' at line " + lineCount;
							break;
						}
					}

					elAntID = 0;
					str = fields[9].trim();
					if (str.length() > 0) {
						elAntID = -1;
						try {
							elAntID = Integer.parseInt(str);
						} catch (NumberFormatException ne) {
						}
						if (elAntID < 0) {
							errmsg = "Bad elevation antenna ID in '" + fileName + "' at line " + lineCount;
							break;
						}
					}

					eTilt = 0.;
					str = fields[10].trim();
					if (str.length() > 0) {
						eTilt = AntPattern.TILT_MIN - 1.;
						try {
							eTilt = Double.parseDouble(str);
						} catch (NumberFormatException ne) {
						}
						if ((eTilt < AntPattern.TILT_MIN) || (eTilt > AntPattern.TILT_MAX)) {
							errmsg = "Bad electrical tilt in '" + fileName + "' at line " + lineCount;
							break;
						}
					}

					mTilt = 0.;
					str = fields[11].trim();
					if (str.length() > 0) {
						mTilt = AntPattern.TILT_MIN - 1.;
						try {
							mTilt = Double.parseDouble(str);
						} catch (NumberFormatException ne) {
						}
						if ((mTilt < AntPattern.TILT_MIN) || (mTilt > AntPattern.TILT_MAX)) {
							errmsg = "Bad mechanical tilt in '" + fileName + "' at line " + lineCount;
							break;
						}
					}

					// The tilt orientation defaults to the pattern orientation.

					mTiltOrientation = orientation;
					str = fields[12].trim();
					if (str.length() > 0) {
						try {
							mTiltOrientation = Math.IEEEremainder(Double.parseDouble(str), 360.);
							if (mTiltOrientation < 0.) mTiltOrientation += 360.;
						} catch (NumberFormatException ne) {
							errmsg = "Bad mechanical tilt orientation in '" + fileName + "' at line " + lineCount;
							break;
						}
					}

					// Optional reference number, city, state, and country; if present there are length restrictions
					// however truncation is not logged for these, this is secondary identifying information.

					referenceNumber = "";
					if (fields.length >= 14) {
						referenceNumber = fields[13].trim();
						if (referenceNumber.length() > Source.MAX_FILE_NUMBER_LENGTH) {
							referenceNumber = referenceNumber.substring(0, Source.MAX_FILE_NUMBER_LENGTH);
						}
					}

					city = "";
					if (fields.length >= 15) {
						city = fields[14].trim();
						if (city.length() > Source.MAX_CITY_LENGTH) {
							city = city.substring(0, Source.MAX_CITY_LENGTH);
						}
					}

					state = "";
					if (fields.length >= 16) {
						state = fields[15].trim();
						if (state.length() > Source.MAX_STATE_LENGTH) {
							state = state.substring(0, Source.MAX_STATE_LENGTH);
						}
					}

					country = "";
					if (fields.length >= 17) {
						country = fields[16].trim();
						if (country.length() > 2) {
							country = country.substring(0, 2);
						}
					}

					// Write the row.

					baseWriter.write(String.valueOf(lineCount));          // cell_key
					baseWriter.write('|');
					baseWriter.write(cellSiteID);                         // cell_site_id
					baseWriter.write('|');
					baseWriter.write(sectorID);                           // sector_id
					baseWriter.write('|');
					baseWriter.write(String.valueOf(cellLat));            // cell_lat
					baseWriter.write('|');
					baseWriter.write(String.valueOf(cellLon));            // cell_lon
					baseWriter.write('|');
					baseWriter.write(String.valueOf(rcAMSL));             // rc_amsl
					baseWriter.write('|');
					baseWriter.write(String.valueOf(haat));               // haat
					baseWriter.write('|');
					baseWriter.write(String.valueOf(erp));                // erp
					baseWriter.write('|');
					baseWriter.write(String.valueOf(azAntID));            // az_ant_id
					baseWriter.write('|');
					baseWriter.write(String.valueOf(orientation));        // orientation
					baseWriter.write('|');
					baseWriter.write(String.valueOf(elAntID));            // el_ant_id
					baseWriter.write('|');
					baseWriter.write(String.valueOf(eTilt));              // e_tilt
					baseWriter.write('|');
					baseWriter.write(String.valueOf(mTilt));              // m_tilt
					baseWriter.write('|');
					baseWriter.write(String.valueOf(mTiltOrientation));   // m_tilt_orientation
					baseWriter.write('|');
					baseWriter.write(referenceNumber);                    // reference_number
					baseWriter.write('|');
					baseWriter.write(city);                               // city
					baseWriter.write('|');
					baseWriter.write(state);                              // state
					baseWriter.write('|');
					baseWriter.write(country);                            // country
					baseWriter.write("|^|");
					baseWriter.newLine();
				}

				// If all went well with station data, copy the pattern data.  In the input the full pattern tabulation
				// for each pattern is on a single line, break that out into an index of IDs, types, and names, and a
				// separate data table with one pattern point per row.  Each pattern point has the degree and field
				// values separated by a semicolon, with the points separated by commas. There must be at least 2
				// points in a pattern.  Degree and field values are range-checked, and degree values are checked for
				// correct order and for duplication.  Also check for a 1.0 field value, if not found log a warning
				// message but continue.

				if (null == errmsg) {

					fileName = patternFile.getName();
					lineCount.reset();

					int antID, i;
					char patType;
					boolean isAzPat;
					String patName, theID;
					String[] patFields;
					double degree, field, lastDegree, fieldMax;

					while (true) {

						line = AppCore.readLineSkipComments(patternReader, lineCount);
						if (null == line) {
							break;
						}
						fields = line.split(",");
						if (fields.length < (AntPattern.PATTERN_REQUIRED_POINTS + 3)) {
							errmsg = "Bad field count in '" + fileName + "' at line " + lineCount;
							break;
						}

						// Write to the pattern index, the ID and type are checked, the name must not be empty.

						antID = -1;
						str = fields[0].trim();
						if (str.length() > 0) {
							try {
								antID = Integer.parseInt(str);
							} catch (NumberFormatException ne) {
							}
						}
						if (antID <= 0) {
							errmsg = "Missing or bad antenna ID in '" + fileName + "' at line " + lineCount;
							break;
						}
						theID = String.valueOf(antID);

						patType = ' ';
						str = fields[1].trim();
						if (str.length() > 0) {
							patType = str.toUpperCase().charAt(0);
						}
						if (('A' != patType) && ('E' != patType)) {
							errmsg = "Missing or bad pattern type in '" + fileName + "' at line " + lineCount;
							break;
						}
						isAzPat = ('A' == patType);

						patName = fields[2].trim();
						if (0 == patName.length()) {
							errmsg = "Missing pattern name in '" + fileName + "' at line " + lineCount;
							break;
						}
						if (patName.length() > Source.MAX_PATTERN_NAME_LENGTH) {
							patName = patName.substring(0, Source.MAX_PATTERN_NAME_LENGTH);
							if (null != errors) {
								errors.logMessage("Pattern name too long, truncated, in '" + fileName + "' at line " +
									lineCount);
							}
						}
						if (patName.contains("|")) {
							errmsg = "Illegal character '|' in pattern name in '" + fileName + "' at line " +
								lineCount;
							break;
						}

						// Write the index row.

						patIndexWriter.write(theID);     // ant_id
						patIndexWriter.write('|');
						patIndexWriter.write(patType);   // pat_type
						patIndexWriter.write('|');
						patIndexWriter.write(patName);   // name
						patIndexWriter.write("|^|");
						patIndexWriter.newLine();

						// Copy the data points, check values.

						if (isAzPat) {
							lastDegree = AntPattern.AZIMUTH_MIN - 1.;
						} else {
							lastDegree = AntPattern.DEPRESSION_MIN - 1.;
						}
						fieldMax = 0.;

						for (i = 3; i < fields.length; i++) {

							patFields = fields[i].split(";");
							if (patFields.length != 2) {
								errmsg = "Bad pattern point format in '" + fileName + "' at line " + lineCount +
									" point " + (i - 2);
								break;
							}

							str = patFields[0].trim();
							if (isAzPat) {
								degree = AntPattern.AZIMUTH_MIN - 1.;
							} else {
								degree = AntPattern.DEPRESSION_MIN - 1.;
							}
							if (str.length() > 0) {
								try {
									degree = Double.parseDouble(str);
								} catch (NumberFormatException ne) {
								}
							}
							if (isAzPat) {
								if ((degree < AntPattern.AZIMUTH_MIN) || (degree > AntPattern.AZIMUTH_MAX)) {
									errmsg = "Bad azimuth in '" + fileName + "' at line " + lineCount + " point " +
										(i - 2);
									break;
								}
							} else {
								if ((degree < AntPattern.DEPRESSION_MIN) || (degree > AntPattern.DEPRESSION_MAX)) {
									errmsg = "Bad vertical angle in '" + fileName + "' at line " + lineCount +
										" point " + (i - 2);
									break;
								}
							}
							if (degree <= lastDegree) {
								errmsg = "Pattern points out of order or duplicated in '" + fileName + "' at line " +
									lineCount + " point " + (i - 2);
								break;
							}
							lastDegree = degree;

							// This does not use the FIELD_MIN, FIELD_MAX, and FIELD_MAX_CHECK constants as in other
							// code.  Here the field just has to be greater than 0, the FIELD_MIN limit will be applied
							// when the pattern is loaded from the SQL table, see ExtDbRecord.  For the max check an
							// exact 1.0 is expected here, FIELD_MAX_CHECK is a value somewhat less than 1 to reduce
							// the frequency of the warning with CDBS/LMS data.  But a stricter test is appropriate
							// here since this is assumed to be user-generated data not a dump from another database.

							field = -1.;
							str = patFields[1].trim();
							if (str.length() > 0) {
								try {
									field = Double.parseDouble(str);
								} catch (NumberFormatException ne) {
								}
							}
							if ((field <= 0.) || (field > 1.)) {
								errmsg = "Bad relative field in '" + fileName + "' at line " + lineCount + " point " +
									(i - 2);
								break;
							}
							if (field > fieldMax) {
								fieldMax = field;
							}

							// Write the row.

							patWriter.write(theID);                    // ant_id
							patWriter.write('|');
							patWriter.write(String.valueOf(degree));   // degree
							patWriter.write('|');
							patWriter.write(String.valueOf(field));    // relative_field
							patWriter.write("|^|");
							patWriter.newLine();
						}

						if (null != errmsg) {
							break;
						}

						if (fieldMax < 1.) {
							if (null != errors) {
								errors.logMessage("Pattern does not contain a 1 for antenna ID " + theID + " in '" +
									fileName + "' at line " + lineCount);
							}
						}
					}
				}

			} catch (IOException ie) {
				errmsg = "An I/O error occurred in '" + fileName + "' at line " + lineCount + ":\n" + ie;

			} catch (Throwable t) {
				errmsg = "An unexpected error occurred in '" + fileName + "' at line " + lineCount + ":\n" + t;
				AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
			}
		}

		// Close files.

		if (null != stationReader) {
			try {stationReader.close();} catch (IOException ie) {};
		}
		if (null != patternReader) {
			try {patternReader.close();} catch (IOException ie) {};
		}
		if (null != baseWriter) {
			try {baseWriter.close();} catch (IOException ie) {};
		}
		if (null != patIndexWriter) {
			try {patIndexWriter.close();} catch (IOException ie) {};
		}
		if (null != patWriter) {
			try {patWriter.close();} catch (IOException ie) {};
		}

		// If an error occurred, delete the temporary output files and directory and return the error.

		if (null != errmsg) {
			if (null != baseWriter) {
				baseFile.delete();
			}
			if (null != patIndexWriter) {
				patIndexFile.delete();
			}
			if (null != patWriter) {
				patFile.delete();
			}
			if (null != theDirectory) {
				theDirectory.delete();
			}
			if (null != errors) {
				errors.reportError(errmsg);
			}
			return null;
		}

		// Create the database.

		Integer result = createNewDatabase(theDbID, DB_TYPE_WIRELESS, theDirectory, theName, status, errors);

		// Delete the temporary files.

		baseFile.delete();
		patIndexFile.delete();
		patFile.delete();
		theDirectory.delete();

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Wrapper around createNewDatabase() to first download a CDBS or LMS ZIP file directly from the FCC server, store
	// it in a temporary location, then import data from it.  Data sets imported this way are flagged as downloads.
	// Once a new download and import succeeds, all other flagged data sets of the same type are deleted.  Data sets
	// created by manual import are not flagged.  Also when the name is set in the UI the download flag is cleared,
	// presumably if the user goes to the trouble of giving a downloaded set a name, they want to keep it.

	public static Integer downloadDatabase(String theDbID, int dataType, String theName) {
		return downloadDatabase(theDbID, dataType, theName, null, null);
	}

	public static Integer downloadDatabase(String theDbID, int dataType, String theName, ErrorLogger errors) {
		return downloadDatabase(theDbID, dataType, theName, null, errors);
	}

	public static Integer downloadDatabase(String theDbID, int dataType, String theName, StatusLogger status) {
		return downloadDatabase(theDbID, dataType, theName, status, null);
	}

	public static Integer downloadDatabase(String theDbID, int dataType, String theName, StatusLogger status,
			ErrorLogger errors) {

		File tempFile = null;
		Integer result = null;

		try {

			String str = null;

			switch (dataType) {

				case ExtDb.DB_TYPE_CDBS:
				case ExtDb.DB_TYPE_CDBS_FM: {
					str = AppCore.getConfig(AppCore.CONFIG_CDBS_DOWNLOAD_URL);
					break;
				}

				case ExtDb.DB_TYPE_LMS: {
					str = AppCore.getConfig(AppCore.CONFIG_LMS_DOWNLOAD_URL);
					break;
				}

				default: {
					if (null != errors) {
						errors.reportError("Unknown or unsupported station data type.");
					}
					return null;
				}
			}

			if (null == str) {
				if (null != errors) {
					errors.reportError("Configuration error, no URL for station data download.");
				}
				return null;
			}

			URL url = new URL(str);
			URLConnection theConn = url.openConnection();

			theConn.setConnectTimeout(DOWNLOAD_TIMEOUT);
			theConn.setReadTimeout(DOWNLOAD_TIMEOUT);

			theConn.connect();
			InputStream theInput = theConn.getInputStream();

			tempFile = File.createTempFile("dbdata", ".zip");
			BufferedOutputStream theOutput = new BufferedOutputStream(new FileOutputStream(tempFile));

			byte[] buffer = new byte[65536];
			int count = 0, bar, lastBar = 0, i;
			long length = theConn.getContentLengthLong(), done = 0;
			double percent;
			boolean error = false;

			if (null != status) {
				status.reportStatus("Downloading, 0% done");
				status.showMessage("Downloading [------------------------------] 0%");
			}

			while (true) {

				count = theInput.read(buffer);
				if (count < 0) {
					break;
				}

				if (count > 0) {

					theOutput.write(buffer, 0, count);
					done += count;

					if (null != status) {

						if (status.isCanceled()) {
							error = true;
							break;
						}

						bar = (int)(((double)done / (double)length) * 30.);
						if (bar > lastBar) {
							String pct = String.format(Locale.US, "%d%%",
								(int)Math.rint(((double)done / (double)length) * 100.));
							status.reportStatus("Downloading, " + pct + " done");
							StringBuilder b = new StringBuilder("Downloading [");
							for (i = 0; i < bar; i++) {
								b.append('#');
							}
							for (; i < 30; i++) {
								b.append('-');
							}
							b.append("] ");
							b.append(pct);
							status.showMessage(b.toString());
							lastBar = bar;
						}
					}
				}
			}

			theInput.close();
			theOutput.close();

			if (!error) {

				if (null != status) {
					status.reportStatus("Importing");
					status.logMessage("Download complete, importing data files...");
				}

				result = createNewDatabase(theDbID, dataType, tempFile, theName, status, true, errors);
			}

		} catch (Throwable t) {
			if (null != errors) {
				errors.reportError(t.toString());
			}
		}

		if (null != tempFile) {
			tempFile.delete();
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create a new database, importing data from files.  Multiple files required for the import always have fixed
	// names.  The files may be in a directory, or in a ZIP file (for LMS and CDBS downloads).  See TableFile and the
	// open*TableFiles() methods for details.  The new data set key is returned, or null on error.  See comments at
	// downloadDatabase() for purpose of the isDownload flag.  Note the name is assumed to have been checked for
	// length and reserved characters.  A concurrent-safe uniqueness check will be performed and a suffix added to
	// make the name unique if needed, but if it is too long this will fail.

	public static Integer createNewDatabase(String theDbID, int dataType, File fileSource, String theName) {
		return createNewDatabase(theDbID, dataType, fileSource, theName, null, false, null);
	}

	public static Integer createNewDatabase(String theDbID, int dataType, File fileSource, String theName,
			ErrorLogger errors) {
		return createNewDatabase(theDbID, dataType, fileSource, theName, null, false, errors);
	}

	public static Integer createNewDatabase(String theDbID, int dataType, File fileSource, String theName,
			StatusLogger status) {
		return createNewDatabase(theDbID, dataType, fileSource, theName, status, false, null);
	}

	public static Integer createNewDatabase(String theDbID, int dataType, File fileSource, String theName,
			StatusLogger status, ErrorLogger errors) {
		return createNewDatabase(theDbID, dataType, fileSource, theName, status, false, errors);
	}

	private static Integer createNewDatabase(String theDbID, int dataType, File fileSource, String theName,
			StatusLogger status, boolean isDownload, ErrorLogger errors) {

		File fileDirectory = null;
		ZipFile zipFile = null;

		if (fileSource.isDirectory()) {
			fileDirectory = fileSource;
		} else {
			try {
				zipFile = new ZipFile(fileSource);
			} catch (IOException ie) {
				if (null != errors) {
					errors.reportError(ie.toString());
				}
				return null;
			}
		}

		ArrayList<TableFile> tableFiles = null;

		switch (dataType) {

			case DB_TYPE_CDBS: {
				tableFiles = openCDBSTableFiles(fileDirectory, zipFile, errors);
				break;
			}
				
			case DB_TYPE_LMS: {
				tableFiles = openLMSTableFiles(fileDirectory, zipFile, errors);
				break;
			}

			case DB_TYPE_WIRELESS: {
				tableFiles = openWirelessTableFiles(fileDirectory, zipFile, errors);
				break;
			}

			case DB_TYPE_CDBS_FM: {
				tableFiles = openCDBSFMTableFiles(fileDirectory, zipFile, errors);
				break;
			}

			case DB_TYPE_LMS_LIVE:
			default: {
				if (null != errors) {
					errors.reportError("Unknown or unsupported station data type.");
				}
				break;
			}
		}

		if (null == tableFiles) {
			if (null != zipFile) {
				try {zipFile.close();} catch (IOException e) {};
			}
			return null;
		}

		// Open database connection, lock tables, get a new key for the database.  The LOCK TABLES is released as soon
		// as possible, the database creation can't occur with that in effect and will also take a significant amount
		// of time.  See further comments in Study.createNewStudy().

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			if (null != zipFile) {
				try {zipFile.close();} catch (IOException e) {};
			}
			return null;
		}

		String errmsg = null, theDbName = null, rootName = DbCore.getDbName(theDbID);
		boolean error = false;
		int newKey = 0;

		try {

			db.update("LOCK TABLES ext_db_key_sequence WRITE");

			db.update("UPDATE ext_db_key_sequence SET ext_db_key = ext_db_key + 1");
			db.query("SELECT ext_db_key FROM ext_db_key_sequence");
			db.next();
			newKey = db.getInt(1);

			// A range of keys is reserved for internally-composed objects, e.g. see getLMSLiveExtDb().  The range
			// start is not likely to ever be reached but if it is advance past the range.

			if (RESERVED_KEY_RANGE_START == newKey) {
				newKey = RESERVED_KEY_RANGE_END + 1;
				db.update("UPDATE ext_db_key_sequence SET ext_db_key = " + newKey);
			}

			db.update("UNLOCK TABLES");

			// Create the database, copy all the tables.

			theDbName = makeDbName(rootName, dataType, Integer.valueOf(newKey));

			DateCounter theDate = null;
			int theVersion = 0;

			switch (dataType) {

				case DB_TYPE_CDBS: {
					theDate = new DateCounter("MM/dd/yyyy");
					theVersion = CDBS_VERSION;
					break;
				}

				case DB_TYPE_LMS: {
					theDate = new DateCounter("yyyy-MM-dd");
					theVersion = LMS_VERSION;
					break;
				}

				case DB_TYPE_WIRELESS: {
					theVersion = WIRELESS_VERSION;
					break;
				}

				case DB_TYPE_CDBS_FM: {
					theDate = new DateCounter("MM/dd/yyyy");
					theVersion = CDBS_FM_VERSION;
					break;
				}
			}

			db.update("CREATE DATABASE " + theDbName + " CHARACTER SET latin1");
			db.setDatabase(theDbName);

			// This now supports a limited detection of past versions, in cases where the only change was adding some
			// additional fields and the query code still has fallback support.  If a field is flagged with a version
			// number and is not found in the file, no error occurs during import.  Check for that here and adjust the
			// version to one less than the version set on the field.  Entire files can now also be flagged with a
			// version, those will have the required flag false so the failure to open earlier did not cause an error,
			// but check for that here and adjust the version as needed.

			for (TableFile theFile : tableFiles) {
				if (null != theFile.reader) {

					if (null != status) {
						if (status.isCanceled()) {
							error = true;
							break;
						}
						status.logMessage("Importing data file " + theFile.fileName + "...");
					}

					errmsg = createAndCopyTable(db, theFile, theDate);
					if (null != errmsg) {
						error = true;
						break;
					}

					for (TableField theField : theFile.requiredFields) {
						if ((theField.version > 0) && (theField.index < 0)) {
							if (theField.version <= theVersion) {
								theVersion = theField.version - 1;
							}
						}
					}

				} else {
					if (theFile.version > 0) {
						if (theFile.version <= theVersion) {
							theVersion = theFile.version - 1;
						}
					}
				}
			}

			// If success generate ID string, typically based on a date field scan, see DateCounter.  The ID is always
			// unique, however the data set date alone may not be unique so the key may be appended to the date, see
			// below.  If a name is provided that will also be checked below for uniqueness, again if not unique the
			// key will be appended.  A reserved character is prefixed to the key to guarantee uniqueness.

			if (!error) {

				String theID = "", theDbDate = "NOW()";

				switch (dataType) {

					case DB_TYPE_CDBS: {
						theID = theDate.getDate();
						theDbDate = "'" + AppCore.formatDateTime(theDate.getDbDate()) + "'";
						break;
					}

					case DB_TYPE_LMS: {
						theID = theDate.getDate();
						theDbDate = "'" + AppCore.formatDateTime(theDate.getDbDate()) + "'";
						break;
					}

					case DB_TYPE_WIRELESS: {
						theID = AppCore.formatDateTime(new java.util.Date());
						break;
					}

					case DB_TYPE_CDBS_FM: {
						theID = theDate.getDate();
						theDbDate = "'" + AppCore.formatDateTime(theDate.getDbDate()) + "'";
						break;
					}
				}

				if (null == theName) {
					theName = "";
				}

				db.setDatabase(rootName);

				// Check the ID and name for uniqueness, save the new index entry in the ext_db table.

				db.update("LOCK TABLES ext_db WRITE");

				db.query("SELECT ext_db_key FROM ext_db WHERE (id = '" + db.clean(theID) + "')");
				if (db.next()) {
					theID = theID + " " + String.valueOf(DbCore.NAME_UNIQUE_CHAR) + String.valueOf(newKey);
				}

				if (theName.length() > 0) {
					db.query("SELECT ext_db_key FROM ext_db WHERE (UPPER(name) = '" +
						db.clean(theName.toUpperCase()) + "')");
					if (db.next()) {
						theName = theName + " " + String.valueOf(DbCore.NAME_UNIQUE_CHAR) + String.valueOf(newKey);
					}
				}

				db.update(
				"INSERT INTO ext_db (" +
					"ext_db_key, " +
					"db_type, " +
					"db_date, " +
					"version, " +
					"id, " +
					"name, " +
					"deleted, " +
					"locked, " +
					"is_download) " +
				"VALUES (" +
					newKey + ", "  +
					dataType + ", " +
					theDbDate + ", " +
					theVersion + ", " +
					"'" + db.clean(theID) + "', " +
					"'" + db.clean(theName) + "', " +
					"false, " +
					"false, " +
					isDownload + ")");

				// If this was a successful import of a downloaded set, delete all other downloads of the same type.
				// Note the databases are not dropped here, just marked deleted; the drops will be done in closeDb(),
				// see comments in deleteDatabase().  There should only be one other download, but allow more than one
				// just in case.  Also respect the locked flag, that shouldn't occur along with download but check
				// anyway, again, just in case.  This can be disabled by a preference setting.

				if (isDownload) {
					String str = AppCore.getPreference(AppCore.CONFIG_AUTO_DELETE_PREVIOUS_DOWNLOAD);
					if ((null != str) && Boolean.valueOf(str).booleanValue()) {
						db.update("UPDATE ext_db SET is_download = false, deleted = true WHERE db_type = " +
							dataType + " AND ext_db_key <> " + newKey + " AND is_download AND NOT locked");
					}
				}
			}

		} catch (SQLException se) {
			errmsg = "A database error occurred:\n" + se;
			error = true;
			db.reportError(se);
		}

		// Make sure table locks are released, if an error occurred also drop the database.

		try {
			db.update("UNLOCK TABLES");
			if (error && (null != theDbName)) {
				db.update("DROP DATABASE IF EXISTS " + theDbName);
			}
		} catch (SQLException se) {
			db.reportError(se);
		}

		DbCore.releaseDb(db);

		for (TableFile theFile : tableFiles) {
			theFile.closeFile();
		}

		if (null != zipFile) {
			try {zipFile.close();} catch (IOException e) {};
		}

		if ((null != errmsg) && (null != errors)) {
			if (error) {
				errors.reportError(errmsg);
			} else {
				errors.reportWarning(errmsg);
			}
		}

		reloadCache(theDbID);

		if (error) {
			return null;
		}

		status.logMessage("Import complete");

		return Integer.valueOf(newKey);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Compose the name of the database used for an imported data set.

	public static String makeDbName(String rootName, int theType, Integer theKey) {

		switch (theType) {
			case DB_TYPE_CDBS:
				return rootName + "_cdbs_" + theKey;
			case DB_TYPE_LMS:
				return rootName + "_lms_" + theKey;
			case DB_TYPE_WIRELESS:
				return rootName + "_wireless_" + theKey;
			case DB_TYPE_CDBS_FM:
				return rootName + "_cdbs_fm_" + theKey;
			case DB_TYPE_GENERIC_TV:
				return rootName + "_import_tv_" + theKey;
			case DB_TYPE_GENERIC_WL:
				return rootName + "_import_wl_" + theKey;
			case DB_TYPE_GENERIC_FM:
				return rootName + "_import_fm_" + theKey;
		}

		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------
	// A list of all the unique base names needed for SHOW DATABASES LIKE '[rootname]_[basename]%' expressions to
	// identify all existing databases for imported data sets; see closeDb().

	private static ArrayList<String> getDbBaseNames() {

		ArrayList<String> result = new ArrayList<String>();
		result.add("cdbs");
		result.add("lms");
		result.add("wireless");
		result.add("import");

		return result;
	}


	//=================================================================================================================
	// Class to define structure of a table and associated SQL dump file, and manage a file reader during import, see
	// createAndCopyTable().  The reader may be reading directly from a file, or from an entry in a ZIP file.  If the
	// fieldNames property is null the list of field names will be read from the first line.  The file name is derived
	// from the table name.  Field separator character is defined here, lines have a separator-terminator-separator
	// termination sequence, the terminator character is also defined here.  The required flag may be false allowing
	// the table file to be missing, code using tables that may not always be present must check for table existence.
	// This now also has a versioning ability like TableField, if the file is missing and version is >0 the import
	// version is adjusted to no greater than one less than version.

	private static class TableFile {

		private static final char SEPARATOR = '|';
		private static final char TERMINATOR = '^';

		private String tableName;
		private String[] fieldNames;
		private String extraDefinitions;
		private int dateFieldIndex;

		private boolean required;
		private int version;

		private ArrayList<TableField> requiredFields;

		private String fileName;
		private File dataFile;
		private InputStream zipStream;

		private BufferedReader reader;


		//-------------------------------------------------------------------------------------------------------------

		private TableFile(String theTableName, String[] theFieldNames, String theExtraDefinitions,
				int theDateFieldIndex, File fileDirectory, ZipFile zipFile, boolean theRequiredFlag) {

			required = theRequiredFlag;

			doInit(theTableName, theFieldNames, theExtraDefinitions, theDateFieldIndex, fileDirectory, zipFile);
		}


		//-------------------------------------------------------------------------------------------------------------

		private TableFile(String theTableName, String[] theFieldNames, String theExtraDefinitions,
				int theDateFieldIndex, File fileDirectory, ZipFile zipFile, int theVersion) {

			version = theVersion;

			doInit(theTableName, theFieldNames, theExtraDefinitions, theDateFieldIndex, fileDirectory, zipFile);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doInit(String theTableName, String[] theFieldNames, String theExtraDefinitions,
				int theDateFieldIndex, File fileDirectory, ZipFile zipFile) {

			tableName = theTableName;
			fieldNames = theFieldNames;
			extraDefinitions = theExtraDefinitions;
			dateFieldIndex = theDateFieldIndex;

			requiredFields = new ArrayList<TableField>();

			fileName = theTableName + ".dat";
			if (null != fileDirectory) {
				dataFile = new File(fileDirectory, fileName);
			} else {
				if (null != zipFile) {
					try {
						ZipEntry theEntry = zipFile.getEntry(fileName);
						if (null != theEntry) {
							zipStream = zipFile.getInputStream(theEntry);
						}
					} catch (IOException ie) {
					}
				}
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		private void addRequiredField(String name, String type, boolean isText) {

			requiredFields.add(new TableField(name, type, isText, 0));
		}


		//-------------------------------------------------------------------------------------------------------------

		private void addRequiredField(String name, String type, boolean isText, int version) {

			requiredFields.add(new TableField(name, type, isText, version));
		}


		//-------------------------------------------------------------------------------------------------------------

		private boolean openFile() {

			closeFile();

			try {
				if (null != dataFile) {
					reader = new BufferedReader(new FileReader(dataFile));
				} else {
					if (null != zipStream) {
						reader = new BufferedReader(new InputStreamReader(zipStream));
					} else {
						return false;
					}
				}
			} catch (IOException ie) {
				return false;
			}

			return true;
		}


		//-------------------------------------------------------------------------------------------------------------

		private void closeFile() {

			if (null != reader) {
				try {
					reader.close();
				} catch (IOException ie) {
				}
				reader = null;
			}
		}
	}


	//=================================================================================================================
	// Class used to manage required field definitions in a data file being imported.  If version is >0 the field may
	// be missing and the import version number will be adjusted accordingly (to at least one less than version).

	private static class TableField {

		private String name;
		private String type;
		private boolean isText;

		private int version;

		private int index;


		//-------------------------------------------------------------------------------------------------------------

		private TableField(String theName, String theType, boolean theIsText, int theVersion) {

			name = theName;
			type = theType;
			isText = theIsText;

			version = theVersion;

			index = -1;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// For CDBS SQL dump files, the field name lists defining the table structures and so dump file formats are stored
	// in a separate data file installed with the application.  Changes to the dump file structure that don't affect
	// queries can be applied by just updating that file.  The first time it is needed, the file is parsed and cached.
	// Errors are ignored, if it fails to load the imports will fail.

	private static HashMap<String, String[]> CDBSFieldNamesMap = null;

	private static void loadCDBSFieldNames() {

		if (null != CDBSFieldNamesMap) {
			return;
		}

		CDBSFieldNamesMap = new HashMap<String, String[]>();

		String tableName, fields;
		String[] fieldNames;

		try {

			BufferedReader reader = new BufferedReader(new FileReader(new File(AppCore.libDirectoryPath +
				File.separator + CDBS_TABLE_DEFS_FILE)));

			do {
				tableName = AppCore.readLineSkipComments(reader);
				if ((null != tableName) && (tableName.length() > 3)) {
					fields = AppCore.readLineSkipComments(reader);
					if (null != fields) {
						fieldNames = fields.split("\\|");
						if ((null != fieldNames) && (fieldNames.length > 2)) {
							CDBSFieldNamesMap.put(tableName, fieldNames);
						}
					}
				}
			} while (null != tableName);

			reader.close();

		} catch (FileNotFoundException fe) {
		} catch (IOException ie) {
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Open table files for a CDBS TV database, return open file list, or null on error.  This opens the TV files and
	// one AM file (AM is used only in support of TV interference-check studies).

	private static ArrayList<TableFile> openCDBSTableFiles(File fileDirectory, ZipFile zipFile, ErrorLogger errors) {

		loadCDBSFieldNames();

		ArrayList<TableFile> tableFiles = new ArrayList<TableFile>();

		String tableName;
		TableFile tableFile;

		tableName = "application";
		tableFile = new TableFile(tableName, CDBSFieldNamesMap.get(tableName), "PRIMARY KEY (application_id)", 6,
			fileDirectory, zipFile, true);
		tableFile.addRequiredField("application_id", "INT", false);
		tableFile.addRequiredField("fac_callsign", "CHAR(12)", true);
		tableFile.addRequiredField("comm_city", "CHAR(20)", true);
		tableFile.addRequiredField("comm_state", "CHAR(2)", true);
		tableFile.addRequiredField("file_prefix", "CHAR(10)", true);
		tableFile.addRequiredField("app_arn", "CHAR(12)", true);
		tableFile.addRequiredField("last_change_date", "CHAR(20)", true);
		tableFiles.add(tableFile);

		tableName = "app_tracking";
		tableFile = new TableFile(tableName, CDBSFieldNamesMap.get(tableName), "INDEX (application_id)", 2,
			fileDirectory, zipFile, true);
		tableFile.addRequiredField("application_id", "INT", false);
		tableFile.addRequiredField("accepted_date", "CHAR(20)", true);
		tableFile.addRequiredField("last_change_date", "CHAR(20)", true);
		tableFiles.add(tableFile);

		tableName = "facility";
		tableFile = new TableFile(tableName, CDBSFieldNamesMap.get(tableName), "PRIMARY KEY (facility_id)", 6,
			fileDirectory, zipFile, true);
		tableFile.addRequiredField("facility_id", "INT", false);
		tableFile.addRequiredField("fac_callsign", "CHAR(12)", true);
		tableFile.addRequiredField("comm_city", "CHAR(20)", true);
		tableFile.addRequiredField("comm_state", "CHAR(2)", true);
		tableFile.addRequiredField("fac_service", "CHAR(2)", true);
		tableFile.addRequiredField("fac_country", "CHAR(2)", true);
		tableFile.addRequiredField("last_change_date", "CHAR(20)", true);
		tableFiles.add(tableFile);

		tableName = "tv_eng_data";
		tableFile = new TableFile(tableName, CDBSFieldNamesMap.get(tableName),
			"UNIQUE (application_id,site_number),INDEX (facility_id)", 29, fileDirectory, zipFile, true);
		tableFile.addRequiredField("application_id", "INT", false);
		tableFile.addRequiredField("site_number", "TINYINT", false);
		tableFile.addRequiredField("facility_id", "INT", false);
		tableFile.addRequiredField("eng_record_type", "CHAR(1)", true);
		tableFile.addRequiredField("vsd_service", "CHAR(2)", true);
		tableFile.addRequiredField("station_channel", "INT", false);
		tableFile.addRequiredField("tv_dom_status", "CHAR(6)", true);
		tableFile.addRequiredField("fac_zone", "CHAR(3)", true);
		tableFile.addRequiredField("freq_offset", "CHAR(1)", true);
		tableFile.addRequiredField("dt_emission_mask", "CHAR(1)", true);
		tableFile.addRequiredField("lat_dir", "CHAR(1)", true);
		tableFile.addRequiredField("lat_deg", "INT", false);
		tableFile.addRequiredField("lat_min", "INT", false);
		tableFile.addRequiredField("lat_sec", "FLOAT", false);
		tableFile.addRequiredField("lon_dir", "CHAR(1)", true);
		tableFile.addRequiredField("lon_deg", "INT", false);
		tableFile.addRequiredField("lon_min", "INT", false);
		tableFile.addRequiredField("lon_sec", "FLOAT", false);
		tableFile.addRequiredField("rcamsl_horiz_mtr", "FLOAT", false);
		tableFile.addRequiredField("haat_rc_mtr", "FLOAT", false);
		tableFile.addRequiredField("effective_erp", "FLOAT", false);
		tableFile.addRequiredField("max_erp_any_angle", "FLOAT", false);
		tableFile.addRequiredField("antenna_id", "INT", false);
		tableFile.addRequiredField("ant_rotation", "FLOAT", false);
		tableFile.addRequiredField("elevation_antenna_id", "INT", false);
		tableFile.addRequiredField("electrical_deg", "FLOAT", false);
		tableFile.addRequiredField("mechanical_deg", "FLOAT", false);
		tableFile.addRequiredField("true_deg", "FLOAT", false);
		tableFile.addRequiredField("predict_coverage_area", "FLOAT", false);
		tableFile.addRequiredField("last_change_date", "CHAR(20)", true);
		tableFiles.add(tableFile);

		tableName = "tv_app_indicators";
		tableFile = new TableFile(tableName, CDBSFieldNamesMap.get(tableName), "UNIQUE (application_id,site_number)",
			-1, fileDirectory, zipFile, true);
		tableFile.addRequiredField("application_id", "INT", false);
		tableFile.addRequiredField("site_number", "TINYINT", false);
		tableFile.addRequiredField("da_ind", "CHAR(1)", true);
		tableFiles.add(tableFile);

		tableName = "dtv_channel_assignments";
		tableFile = new TableFile(tableName, CDBSFieldNamesMap.get(tableName), "UNIQUE (facility_id)", -1,
			fileDirectory, zipFile, true);
		tableFile.addRequiredField("facility_id", "INT", false);
		tableFile.addRequiredField("callsign", "CHAR(12)", true);
		tableFile.addRequiredField("city", "CHAR(20)", true);
		tableFile.addRequiredField("state", "CHAR(2)", true);
		tableFile.addRequiredField("post_dtv_channel", "INT", false);
		tableFile.addRequiredField("latitude", "CHAR(10)", true);
		tableFile.addRequiredField("longitude", "CHAR(11)", true);
		tableFile.addRequiredField("rcamsl", "INT", false);
		tableFile.addRequiredField("haat", "FLOAT", false);
		tableFile.addRequiredField("erp", "FLOAT", false);
		tableFile.addRequiredField("da_ind", "CHAR(1)", true);
		tableFile.addRequiredField("antenna_id", "INT", false);
		tableFile.addRequiredField("ref_azimuth", "INT", false);
		tableFiles.add(tableFile);

		tableName = "ant_make";
		tableFile = new TableFile(tableName, CDBSFieldNamesMap.get(tableName), "PRIMARY KEY (antenna_id)", -1,
			fileDirectory, zipFile, true);
		tableFile.addRequiredField("antenna_id", "INT", false);
		tableFile.addRequiredField("ant_make", "CHAR(3)", true);
		tableFile.addRequiredField("ant_model_num", "CHAR(60)", true);
		tableFiles.add(tableFile);

		tableName = "ant_pattern";
		tableFile = new TableFile(tableName, CDBSFieldNamesMap.get(tableName), "INDEX (antenna_id)", -1,
			fileDirectory, zipFile, true);
		tableFile.addRequiredField("antenna_id", "INT", false);
		tableFile.addRequiredField("azimuth", "FLOAT", false);
		tableFile.addRequiredField("field_value", "FLOAT", false);
		tableFiles.add(tableFile);

		tableName = "elevation_ant_make";
		tableFile = new TableFile(tableName, CDBSFieldNamesMap.get(tableName), "PRIMARY KEY (elevation_antenna_id)",
			-1, fileDirectory, zipFile, true);
		tableFile.addRequiredField("elevation_antenna_id", "INT", false);
		tableFile.addRequiredField("ant_make", "CHAR(3)", true);
		tableFile.addRequiredField("ant_model_num", "CHAR(60)", true);
		tableFiles.add(tableFile);

		tableName = "elevation_pattern";
		tableFile = new TableFile(tableName, CDBSFieldNamesMap.get(tableName), "INDEX (elevation_antenna_id)", -1,
			fileDirectory, zipFile, true);
		tableFile.addRequiredField("elevation_antenna_id", "INT", false);
		tableFile.addRequiredField("depression_angle", "FLOAT", false);
		tableFile.addRequiredField("field_value", "FLOAT", false);
		tableFile.addRequiredField("field_value0", "FLOAT", false);
		tableFile.addRequiredField("field_value10", "FLOAT", false);
		tableFile.addRequiredField("field_value20", "FLOAT", false);
		tableFile.addRequiredField("field_value30", "FLOAT", false);
		tableFile.addRequiredField("field_value40", "FLOAT", false);
		tableFile.addRequiredField("field_value50", "FLOAT", false);
		tableFile.addRequiredField("field_value60", "FLOAT", false);
		tableFile.addRequiredField("field_value70", "FLOAT", false);
		tableFile.addRequiredField("field_value80", "FLOAT", false);
		tableFile.addRequiredField("field_value90", "FLOAT", false);
		tableFile.addRequiredField("field_value100", "FLOAT", false);
		tableFile.addRequiredField("field_value110", "FLOAT", false);
		tableFile.addRequiredField("field_value120", "FLOAT", false);
		tableFile.addRequiredField("field_value130", "FLOAT", false);
		tableFile.addRequiredField("field_value140", "FLOAT", false);
		tableFile.addRequiredField("field_value150", "FLOAT", false);
		tableFile.addRequiredField("field_value160", "FLOAT", false);
		tableFile.addRequiredField("field_value170", "FLOAT", false);
		tableFile.addRequiredField("field_value180", "FLOAT", false);
		tableFile.addRequiredField("field_value190", "FLOAT", false);
		tableFile.addRequiredField("field_value200", "FLOAT", false);
		tableFile.addRequiredField("field_value210", "FLOAT", false);
		tableFile.addRequiredField("field_value220", "FLOAT", false);
		tableFile.addRequiredField("field_value230", "FLOAT", false);
		tableFile.addRequiredField("field_value240", "FLOAT", false);
		tableFile.addRequiredField("field_value250", "FLOAT", false);
		tableFile.addRequiredField("field_value260", "FLOAT", false);
		tableFile.addRequiredField("field_value270", "FLOAT", false);
		tableFile.addRequiredField("field_value280", "FLOAT", false);
		tableFile.addRequiredField("field_value290", "FLOAT", false);
		tableFile.addRequiredField("field_value300", "FLOAT", false);
		tableFile.addRequiredField("field_value310", "FLOAT", false);
		tableFile.addRequiredField("field_value320", "FLOAT", false);
		tableFile.addRequiredField("field_value330", "FLOAT", false);
		tableFile.addRequiredField("field_value340", "FLOAT", false);
		tableFile.addRequiredField("field_value350", "FLOAT", false);
		tableFiles.add(tableFile);

		tableName = "elevation_pattern_addl";
		tableFile = new TableFile(tableName, CDBSFieldNamesMap.get(tableName), "INDEX (elevation_antenna_id)", -1,
			fileDirectory, zipFile, true);
		tableFile.addRequiredField("elevation_antenna_id", "INT", false);
		tableFile.addRequiredField("azimuth", "FLOAT", false);
		tableFile.addRequiredField("depression_angle", "FLOAT", false);
		tableFile.addRequiredField("field_value", "FLOAT", false);
		tableFiles.add(tableFile);

		tableName = "am_ant_sys";
		tableFile = new TableFile(tableName, CDBSFieldNamesMap.get(tableName), null, -1, fileDirectory, zipFile,
			false);
		tableFile.addRequiredField("application_id", "INT", false);
		tableFile.addRequiredField("eng_record_type", "CHAR(1)", true);
		tableFile.addRequiredField("am_dom_status", "CHAR(1)", true);
		tableFile.addRequiredField("ant_mode", "CHAR(3)", true);
		tableFile.addRequiredField("lat_dir", "CHAR(1)", true);
		tableFile.addRequiredField("lat_deg", "INT", false);
		tableFile.addRequiredField("lat_min", "INT", false);
		tableFile.addRequiredField("lat_sec", "FLOAT", false);
		tableFile.addRequiredField("lon_dir", "CHAR(1)", true);
		tableFile.addRequiredField("lon_deg", "INT", false);
		tableFile.addRequiredField("lon_min", "INT", false);
		tableFile.addRequiredField("lon_sec", "FLOAT", false);
		tableFiles.add(tableFile);

		// Make sure all field name lists were found, and open all the files.

		String errmsg = null;
		for (TableFile theFile : tableFiles) {
			if (null == theFile.fieldNames) {
				errmsg = "Field name list not found for table '" + theFile.tableName + "'";
				break;
			}
			if (!theFile.openFile()) {
				if (theFile.required) {
					errmsg = "Data file '" + theFile.fileName + "' could not be opened";
					break;
				}
			}
		}

		if (null != errmsg) {
			for (TableFile theFile : tableFiles) {
				theFile.closeFile();
			}
			if (null != errors) {
				errors.reportError(errmsg);
			}
			return null;
		}

		return tableFiles;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Open files for an LMS database.  LMS dump files have field names defining the table and file structure provided
	// in a header line in each file, so this will adapt to changes automatically.  See createAndCopyTable().

	private static ArrayList<TableFile> openLMSTableFiles(File fileDirectory, ZipFile zipFile, ErrorLogger errors) {

		ArrayList<TableFile> tableFiles = new ArrayList<TableFile>();
		TableFile tableFile;

		tableFile = new TableFile("application", null, "INDEX (aapp_application_id)", 6, fileDirectory, zipFile, true);
		tableFile.addRequiredField("aapp_application_id", "CHAR(36)", true);
		tableFile.addRequiredField("aapp_callsign", "CHAR(12)", true);
		tableFile.addRequiredField("aapp_receipt_date", "CHAR(20)", true);
		tableFile.addRequiredField("aapp_file_num", "CHAR(20)", true);
		tableFile.addRequiredField("dts_reference_ind", "CHAR(1)", true);
		tableFile.addRequiredField("dts_waiver_distance", "VARCHAR(255)", true);
		tableFile.addRequiredField("last_update_ts", "CHAR(30)", true);
		tableFile.addRequiredField("channel_sharing_ind", "CHAR(1)", true);
		tableFiles.add(tableFile);

		tableFile = new TableFile("license_filing_version", null,
			"INDEX (filing_version_id),INDEX (service_code),INDEX (purpose_code)", 5, fileDirectory, zipFile, true);
		tableFile.addRequiredField("filing_version_id", "CHAR(36)", true);
		tableFile.addRequiredField("active_ind", "CHAR(1)", true);
		tableFile.addRequiredField("purpose_code", "CHAR(6)", true);
		tableFile.addRequiredField("original_purpose_code", "CHAR(6)", true);
		tableFile.addRequiredField("service_code", "CHAR(6)", true);
		tableFile.addRequiredField("auth_type_code", "CHAR(6)", true);
		tableFile.addRequiredField("current_status_code", "CHAR(6)", true);
		tableFile.addRequiredField("last_update_ts", "CHAR(30)", true);
		tableFiles.add(tableFile);

		tableFile = new TableFile("application_facility", null,
			"INDEX (afac_application_id),INDEX (afac_facility_id),INDEX (country_code)", 6, fileDirectory, zipFile,
			true);
		tableFile.addRequiredField("afac_application_id", "CHAR(36)", true);
		tableFile.addRequiredField("afac_facility_id", "INT", false);
		tableFile.addRequiredField("afac_channel", "INT", false);
		tableFile.addRequiredField("afac_community_city", "VARCHAR(255)", true);
		tableFile.addRequiredField("afac_community_state_code", "VARCHAR(255)", true);
		tableFile.addRequiredField("country_code", "CHAR(3)", true);
		tableFile.addRequiredField("last_update_ts", "CHAR(30)", true);
		tableFile.addRequiredField("licensee_name", "VARCHAR(255)", true);
		tableFiles.add(tableFile);

		tableFile = new TableFile("facility", null, "INDEX (facility_id)", -1, fileDirectory, zipFile, true);
		tableFile.addRequiredField("facility_id", "INT", false);
		tableFile.addRequiredField("callsign", "CHAR(12)", true);
		tableFile.addRequiredField("community_served_city", "VARCHAR(255)", true);
		tableFile.addRequiredField("community_served_state", "VARCHAR(255)", true);
		tableFile.addRequiredField("facility_status", "CHAR(6)", true);
		tableFiles.add(tableFile);

		tableFile = new TableFile("app_location", null, "INDEX (aloc_aapp_application_id),INDEX (aloc_loc_record_id)",
			-1, fileDirectory, zipFile, true);
		tableFile.addRequiredField("aloc_aapp_application_id", "CHAR(36)", true);
		tableFile.addRequiredField("aloc_loc_record_id", "CHAR(36)", true);
		tableFile.addRequiredField("aloc_loc_seq_id", "INT", false);
		tableFile.addRequiredField("aloc_dts_reference_location_ind", "CHAR(1)", true);
		tableFile.addRequiredField("aloc_lat_dir", "CHAR(1)", true);
		tableFile.addRequiredField("aloc_lat_deg", "INT", false);
		tableFile.addRequiredField("aloc_lat_mm", "INT", false);
		tableFile.addRequiredField("aloc_lat_ss", "FLOAT", false);
		tableFile.addRequiredField("aloc_long_dir", "CHAR(1)", true);
		tableFile.addRequiredField("aloc_long_deg", "INT", false);
		tableFile.addRequiredField("aloc_long_mm", "INT", false);
		tableFile.addRequiredField("aloc_long_ss", "FLOAT", false);
		tableFiles.add(tableFile);

		tableFile = new TableFile("app_antenna", null,
			"INDEX (aant_aloc_loc_record_id),INDEX (aant_antenna_record_id)", -1, fileDirectory, zipFile, true);
		tableFile.addRequiredField("aant_aloc_loc_record_id", "CHAR(36)", true);
		tableFile.addRequiredField("aant_antenna_record_id", "CHAR(36)", true);
		tableFile.addRequiredField("aant_antenna_type_code", "VARCHAR(255)", true);
		tableFile.addRequiredField("aant_rc_amsl", "FLOAT", false);
		tableFile.addRequiredField("aant_rc_haat", "FLOAT", false);
		tableFile.addRequiredField("aant_rotation_deg", "FLOAT", false);
		tableFile.addRequiredField("aant_electrical_deg", "FLOAT", false);
		tableFile.addRequiredField("aant_mechanical_deg", "FLOAT", false);
		tableFile.addRequiredField("aant_true_deg", "FLOAT", false);
		tableFile.addRequiredField("emission_mask_code", "CHAR(6)", true);
		tableFile.addRequiredField("aant_antenna_id", "VARCHAR(255)", true);
		tableFile.addRequiredField("aant_make", "VARCHAR(255)", true);
		tableFile.addRequiredField("aant_model", "VARCHAR(255)", true);
		tableFile.addRequiredField("foreign_station_beam_tilt", "FLOAT", false, 5);
		tableFiles.add(tableFile);

		tableFile = new TableFile("app_antenna_frequency", null, "INDEX (aafq_aant_antenna_record_id)", -1,
			fileDirectory, zipFile, true);
		tableFile.addRequiredField("aafq_aant_antenna_record_id", "CHAR(36)", true);
		tableFile.addRequiredField("aafq_power_erp_kw", "FLOAT", false);
		tableFile.addRequiredField("aafq_offset", "VARCHAR(255)", true);
		tableFiles.add(tableFile);

		tableFile = new TableFile("app_antenna_field_value", null, "INDEX (aafv_aant_antenna_record_id)", -1,
			fileDirectory, zipFile, true);
		tableFile.addRequiredField("aafv_aant_antenna_record_id", "CHAR(36)", true);
		tableFile.addRequiredField("aafv_azimuth", "FLOAT", false);
		tableFile.addRequiredField("aafv_field_value", "FLOAT", false);
		tableFiles.add(tableFile);

		tableFile = new TableFile("app_antenna_elevation_pattern", null, "INDEX (aaep_antenna_record_id)", -1,
			fileDirectory, zipFile, true);
		tableFile.addRequiredField("aaep_antenna_record_id", "CHAR(36)", true);
		tableFile.addRequiredField("aaep_azimuth", "FLOAT", false);
		tableFile.addRequiredField("aaep_depression_angle", "FLOAT", false);
		tableFile.addRequiredField("aaep_field_value", "FLOAT", false);
		tableFiles.add(tableFile);

		tableFile = new TableFile("app_dtv_channel_assignment", null, "INDEX (adca_facility_record_id)", -1,
			fileDirectory, zipFile, true);
		tableFile.addRequiredField("adca_facility_record_id", "INT", false);
		tableFile.addRequiredField("dtv_allotment_id", "CHAR(36)", true);
		tableFile.addRequiredField("callsign", "CHAR(12)", true);
		tableFile.addRequiredField("rcamsl", "FLOAT", false);
		tableFile.addRequiredField("directional_antenna_ind", "CHAR(1)", true);
		tableFile.addRequiredField("antenna_id", "CHAR(36)", true);
		tableFile.addRequiredField("antenna_rotation", "FLOAT", false);
		tableFile.addRequiredField("emission_mask_code", "VARCHAR(255)", true, 4);
		tableFile.addRequiredField("electrical_deg", "FLOAT", false, 5);
		tableFile.addRequiredField("pre_auction_channel", "INT", false);
		tableFiles.add(tableFile);

		tableFile = new TableFile("lkp_dtv_allotment", null, "INDEX (rdta_dtv_allotment_id)", -1, fileDirectory,
			zipFile, true);
		tableFile.addRequiredField("rdta_dtv_allotment_id", "CHAR(36)", true);
		tableFile.addRequiredField("rdta_service_code", "CHAR(6)", true, 3);
		tableFile.addRequiredField("rdta_city", "VARCHAR(255)", true);
		tableFile.addRequiredField("rdta_state", "CHAR(2)", true);
		tableFile.addRequiredField("rdta_country_code", "CHAR(3)", true, 3);
		tableFile.addRequiredField("rdta_digital_channel", "INT", false);
		tableFile.addRequiredField("rdta_erp", "FLOAT", false);
		tableFile.addRequiredField("rdta_haat", "FLOAT", false);
		tableFile.addRequiredField("rdta_lat_dir", "CHAR(1)", true);
		tableFile.addRequiredField("rdta_lat_deg", "INT", false);
		tableFile.addRequiredField("rdta_lat_min", "INT", false);
		tableFile.addRequiredField("rdta_lat_sec", "FLOAT", false);
		tableFile.addRequiredField("rdta_lon_dir", "CHAR(1)", true);
		tableFile.addRequiredField("rdta_lon_deg", "INT", false);
		tableFile.addRequiredField("rdta_lon_min", "INT", false);
		tableFile.addRequiredField("rdta_lon_sec", "FLOAT", false);
		tableFile.addRequiredField("dts_ref_application_id", "CHAR(36)", true);
		tableFiles.add(tableFile);

		tableFile = new TableFile("lkp_antenna", null, "INDEX (rant_antenna_id)", -1, fileDirectory, zipFile, true);
		tableFile.addRequiredField("rant_antenna_id", "CHAR(36)", true);
		tableFile.addRequiredField("rant_antenna_record_id", "CHAR(36)", true);
		tableFile.addRequiredField("rant_make", "VARCHAR(255)", true);
		tableFile.addRequiredField("rant_model", "VARCHAR(255)", true);
		tableFiles.add(tableFile);

		tableFile = new TableFile("lkp_antenna_field_value", null, "INDEX (rafv_antenna_record_id)", -1,
			fileDirectory, zipFile, true);
		tableFile.addRequiredField("rafv_antenna_record_id", "CHAR(36)", true);
		tableFile.addRequiredField("rafv_azimuth", "FLOAT", false);
		tableFile.addRequiredField("rafv_field_value", "FLOAT", false);
		tableFiles.add(tableFile);

		tableFile = new TableFile("shared_channel", null, "INDEX (application_id),INDEX (facility_id)", -1,
			fileDirectory, zipFile, 6);
		tableFile.addRequiredField("application_id", "CHAR(36)", true);
		tableFile.addRequiredField("facility_id", "INT", false);
		tableFile.addRequiredField("host_ind", "CHAR(1)", true);
		tableFiles.add(tableFile);

		tableFile = new TableFile("xref_cdbs_lm_app_id_transfer", null, "INDEX (application_id)", -1, fileDirectory,
			zipFile, false);
		tableFile.addRequiredField("application_id", "INT", false);
		tableFile.addRequiredField("filing_version_id", "CHAR(36)", true);
		tableFiles.add(tableFile);

		// AM tables are optional, these are used only for the AM station check in a TV interference-check study which
		// is advisory only so failure is non-fatal.  See checkForAMStations().

		tableFile = new TableFile("gis_am_ant_sys", null, "INDEX(application_id)", -1, fileDirectory, zipFile, false);
		tableFile.addRequiredField("application_id", "INT", false);
		tableFile.addRequiredField("eng_record_type", "CHAR(1)", true);
		tableFile.addRequiredField("am_dom_status", "CHAR(1)", true);
		tableFile.addRequiredField("ant_mode", "CHAR(3)", true);
		tableFile.addRequiredField("lat_dir", "CHAR(1)", true);
		tableFile.addRequiredField("lat_deg", "INT", false);
		tableFile.addRequiredField("lat_min", "INT", false);
		tableFile.addRequiredField("lat_sec", "FLOAT", false);
		tableFile.addRequiredField("lon_dir", "CHAR(1)", true);
		tableFile.addRequiredField("lon_deg", "INT", false);
		tableFile.addRequiredField("lon_min", "INT", false);
		tableFile.addRequiredField("lon_sec", "FLOAT", false);
		tableFiles.add(tableFile);

		tableFile = new TableFile("gis_application", null, "INDEX (application_id),INDEX (facility_id)", -1,
			fileDirectory, zipFile, false);
		tableFile.addRequiredField("application_id", "INT", false);
		tableFile.addRequiredField("facility_id", "INT", false);
		tableFile.addRequiredField("file_prefix", "CHAR(10)", true);
		tableFile.addRequiredField("app_arn", "CHAR(12)", true);
		tableFiles.add(tableFile);

		tableFile = new TableFile("gis_facility", null, "INDEX (facility_id)", -1, fileDirectory, zipFile, false);
		tableFile.addRequiredField("facility_id", "INT", false);
		tableFile.addRequiredField("fac_callsign", "CHAR(12)", true);
		tableFile.addRequiredField("fac_channel", "INT", false);
		tableFile.addRequiredField("comm_city", "CHAR(20)", true);
		tableFile.addRequiredField("comm_state", "CHAR(2)", true);
		tableFiles.add(tableFile);

		// Open all the files.

		String errmsg = null;
		for (TableFile theFile : tableFiles) {
			if (!theFile.openFile()) {
				if (theFile.required) {
					errmsg = "Data file '" + theFile.fileName + "' could not be opened";
					break;
				}
			}
		}

		if (null != errmsg) {
			for (TableFile theFile : tableFiles) {
				theFile.closeFile();
			}
			if (null != errors) {
				errors.reportError(errmsg);
			}
			return null;
		}

		return tableFiles;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Open files for a wireless database.  The structure here is entirely defined by this application, the "dump"
	// files are actually temporary files translated from CSV data, see createNewWirelessDatabase().

	private static ArrayList<TableFile> openWirelessTableFiles(File fileDirectory, ZipFile zipFile,
			ErrorLogger errors) {

		ArrayList<TableFile> tableFiles = new ArrayList<TableFile>();
		TableFile tableFile;
		{
			String[] fieldNames = {
				"cell_key", "cell_site_id", "sector_id", "cell_lat", "cell_lon", "rc_amsl", "haat", "erp",
				"az_ant_id", "orientation", "el_ant_id", "e_tilt", "m_tilt", "m_tilt_orientation", "reference_number",
				"city", "state", "country"
			};
			tableFile = new TableFile(WIRELESS_BASE_TABLE, fieldNames, "PRIMARY KEY (cell_key)", -1, fileDirectory,
				zipFile, true);
			tableFile.addRequiredField("cell_key", "INT", false);
			tableFile.addRequiredField("cell_lat", "FLOAT", false);
			tableFile.addRequiredField("cell_lon", "FLOAT", false);
			tableFile.addRequiredField("rc_amsl", "FLOAT", false);
			tableFile.addRequiredField("haat", "FLOAT", false);
			tableFile.addRequiredField("erp", "FLOAT", false);
			tableFile.addRequiredField("az_ant_id", "INT", false);
			tableFile.addRequiredField("orientation", "FLOAT", false);
			tableFile.addRequiredField("el_ant_id", "INT", false);
			tableFile.addRequiredField("e_tilt", "FLOAT", false);
			tableFile.addRequiredField("m_tilt", "FLOAT", false);
			tableFile.addRequiredField("m_tilt_orientation", "FLOAT", false);
			tableFile.addRequiredField("reference_number", "CHAR(22)", true);
			tableFile.addRequiredField("city", "CHAR(20)", true);
			tableFile.addRequiredField("state", "CHAR(2)", true);
			tableFile.addRequiredField("country", "CHAR(2)", true);
			tableFiles.add(tableFile);
		}

		{
			String[] fieldNames = {"ant_id", "pat_type", "name"};
			tableFile = new TableFile(WIRELESS_INDEX_TABLE, fieldNames, "PRIMARY KEY (ant_id)", -1, fileDirectory,
				zipFile, true);
			tableFile.addRequiredField("ant_id", "INT", false);
			tableFile.addRequiredField("pat_type", "CHAR(1)", true);
			tableFiles.add(tableFile);
		}

		{
			String[] fieldNames = {"ant_id", "degree", "relative_field"};
			tableFile = new TableFile(WIRELESS_PATTERN_TABLE, fieldNames, "INDEX (ant_id)", -1, fileDirectory, zipFile,
				true);
			tableFile.addRequiredField("ant_id", "INT", false);
			tableFile.addRequiredField("degree", "FLOAT", false);
			tableFile.addRequiredField("relative_field", "FLOAT", false);
			tableFiles.add(tableFile);
		}

		// Open all the files.

		String errmsg = null;
		for (TableFile theFile : tableFiles) {
			if (!theFile.openFile()) {
				if (theFile.required) {
					errmsg = "Data file '" + theFile.fileName + "' could not be opened";
					break;
				}
			}
		}

		if (null != errmsg) {
			for (TableFile theFile : tableFiles) {
				theFile.closeFile();
			}
			if (null != errors) {
				errors.reportError(errmsg);
			}
			return null;
		}

		return tableFiles;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Open table files for a CDBS FM database.  This includes many of the same files as for a CDBS TV data set e.g.
	// application, facility, and pattern tables, making it a little inefficient since a combined data set with both
	// TV and FM tables would be possible.  But it simplifies the rest of the code to have a given data set only able
	// to produce a single record type.  However the CDBS field names data is shared between the two types.

	private static ArrayList<TableFile> openCDBSFMTableFiles(File fileDirectory, ZipFile zipFile, ErrorLogger errors) {

		loadCDBSFieldNames();

		ArrayList<TableFile> tableFiles = new ArrayList<TableFile>();

		String tableName;
		TableFile tableFile;

		tableName = "application";
		tableFile = new TableFile(tableName, CDBSFieldNamesMap.get(tableName), "PRIMARY KEY (application_id)", 3,
			fileDirectory, zipFile, true);
		tableFile.addRequiredField("application_id", "INT", false);
		tableFile.addRequiredField("file_prefix", "CHAR(10)", true);
		tableFile.addRequiredField("app_arn", "CHAR(12)", true);
		tableFile.addRequiredField("last_change_date", "CHAR(20)", true);
		tableFiles.add(tableFile);

		tableName = "app_tracking";
		tableFile = new TableFile(tableName, CDBSFieldNamesMap.get(tableName), "INDEX (application_id)", 2,
			fileDirectory, zipFile, true);
		tableFile.addRequiredField("application_id", "INT", false);
		tableFile.addRequiredField("accepted_date", "CHAR(20)", true);
		tableFile.addRequiredField("last_change_date", "CHAR(20)", true);
		tableFiles.add(tableFile);

		tableName = "facility";
		tableFile = new TableFile(tableName, CDBSFieldNamesMap.get(tableName), "PRIMARY KEY (facility_id)", 7,
			fileDirectory, zipFile, true);
		tableFile.addRequiredField("facility_id", "INT", false);
		tableFile.addRequiredField("fac_callsign", "CHAR(12)", true);
		tableFile.addRequiredField("fac_service", "CHAR(2)", true);
		tableFile.addRequiredField("comm_city", "CHAR(20)", true);
		tableFile.addRequiredField("comm_state", "CHAR(2)", true);
		tableFile.addRequiredField("fac_country", "CHAR(2)", true);
		tableFile.addRequiredField("digital_status", "CHAR(1)", true);
		tableFile.addRequiredField("last_change_date", "CHAR(20)", true);
		tableFiles.add(tableFile);

		tableName = "fm_eng_data";
		tableFile = new TableFile(tableName, CDBSFieldNamesMap.get(tableName),
			"INDEX (application_id),INDEX (facility_id)", 24, fileDirectory, zipFile, true);
		tableFile.addRequiredField("application_id", "INT", false);
		tableFile.addRequiredField("facility_id", "INT", false);
		tableFile.addRequiredField("eng_record_type", "CHAR(1)", true);
		tableFile.addRequiredField("asd_service", "CHAR(2)", true);
		tableFile.addRequiredField("station_channel", "INT", false);
		tableFile.addRequiredField("fm_dom_status", "CHAR(6)", true);
		tableFile.addRequiredField("lat_dir", "CHAR(1)", true);
		tableFile.addRequiredField("lat_deg", "INT", false);
		tableFile.addRequiredField("lat_min", "INT", false);
		tableFile.addRequiredField("lat_sec", "FLOAT", false);
		tableFile.addRequiredField("lon_dir", "CHAR(1)", true);
		tableFile.addRequiredField("lon_deg", "INT", false);
		tableFile.addRequiredField("lon_min", "INT", false);
		tableFile.addRequiredField("lon_sec", "FLOAT", false);
		tableFile.addRequiredField("rcamsl_horiz_mtr", "FLOAT", false);
		tableFile.addRequiredField("rcamsl_vert_mtr", "FLOAT", false);
		tableFile.addRequiredField("haat_horiz_rc_mtr", "FLOAT", false);
		tableFile.addRequiredField("haat_vert_rc_mtr", "FLOAT", false);
		tableFile.addRequiredField("max_horiz_erp", "FLOAT", false);
		tableFile.addRequiredField("horiz_erp", "FLOAT", false);
		tableFile.addRequiredField("max_vert_erp", "FLOAT", false);
		tableFile.addRequiredField("vert_erp", "FLOAT", false);
		tableFile.addRequiredField("antenna_id", "INT", false);
		tableFile.addRequiredField("ant_rotation", "FLOAT", false);
		tableFile.addRequiredField("last_change_date", "CHAR(20)", true);
		tableFiles.add(tableFile);

		tableName = "fm_app_indicators";
		tableFile = new TableFile(tableName, CDBSFieldNamesMap.get(tableName), "PRIMARY KEY (application_id)",
			-1, fileDirectory, zipFile, true);
		tableFile.addRequiredField("application_id", "INT", false);
		tableFile.addRequiredField("da_ind", "CHAR(1)", true);
		tableFiles.add(tableFile);

		tableName = "if_notification";
		tableFile = new TableFile(tableName, CDBSFieldNamesMap.get(tableName), "PRIMARY KEY (application_id)",
			-1, fileDirectory, zipFile, true);
		tableFile.addRequiredField("application_id", "INT", false);
		tableFile.addRequiredField("analog_erp", "FLOAT", false);
		tableFile.addRequiredField("digital_erp", "FLOAT", false);
		tableFiles.add(tableFile);

		tableName = "ant_make";
		tableFile = new TableFile(tableName, CDBSFieldNamesMap.get(tableName), "PRIMARY KEY (antenna_id)", -1,
			fileDirectory, zipFile, true);
		tableFile.addRequiredField("antenna_id", "INT", false);
		tableFile.addRequiredField("ant_make", "CHAR(3)", true);
		tableFile.addRequiredField("ant_model_num", "CHAR(60)", true);
		tableFiles.add(tableFile);

		tableName = "ant_pattern";
		tableFile = new TableFile(tableName, CDBSFieldNamesMap.get(tableName), "INDEX (antenna_id)", -1,
			fileDirectory, zipFile, true);
		tableFile.addRequiredField("antenna_id", "INT", false);
		tableFile.addRequiredField("azimuth", "FLOAT", false);
		tableFile.addRequiredField("field_value", "FLOAT", false);
		tableFiles.add(tableFile);

		tableName = "elevation_ant_make";
		tableFile = new TableFile(tableName, CDBSFieldNamesMap.get(tableName), "PRIMARY KEY (elevation_antenna_id)",
			-1, fileDirectory, zipFile, true);
		tableFile.addRequiredField("elevation_antenna_id", "INT", false);
		tableFile.addRequiredField("ant_make", "CHAR(3)", true);
		tableFile.addRequiredField("ant_model_num", "CHAR(60)", true);
		tableFiles.add(tableFile);

		tableName = "elevation_pattern";
		tableFile = new TableFile(tableName, CDBSFieldNamesMap.get(tableName), "INDEX (elevation_antenna_id)", -1,
			fileDirectory, zipFile, true);
		tableFile.addRequiredField("elevation_antenna_id", "INT", false);
		tableFile.addRequiredField("depression_angle", "FLOAT", false);
		tableFile.addRequiredField("field_value", "FLOAT", false);
		tableFile.addRequiredField("field_value0", "FLOAT", false);
		tableFile.addRequiredField("field_value10", "FLOAT", false);
		tableFile.addRequiredField("field_value20", "FLOAT", false);
		tableFile.addRequiredField("field_value30", "FLOAT", false);
		tableFile.addRequiredField("field_value40", "FLOAT", false);
		tableFile.addRequiredField("field_value50", "FLOAT", false);
		tableFile.addRequiredField("field_value60", "FLOAT", false);
		tableFile.addRequiredField("field_value70", "FLOAT", false);
		tableFile.addRequiredField("field_value80", "FLOAT", false);
		tableFile.addRequiredField("field_value90", "FLOAT", false);
		tableFile.addRequiredField("field_value100", "FLOAT", false);
		tableFile.addRequiredField("field_value110", "FLOAT", false);
		tableFile.addRequiredField("field_value120", "FLOAT", false);
		tableFile.addRequiredField("field_value130", "FLOAT", false);
		tableFile.addRequiredField("field_value140", "FLOAT", false);
		tableFile.addRequiredField("field_value150", "FLOAT", false);
		tableFile.addRequiredField("field_value160", "FLOAT", false);
		tableFile.addRequiredField("field_value170", "FLOAT", false);
		tableFile.addRequiredField("field_value180", "FLOAT", false);
		tableFile.addRequiredField("field_value190", "FLOAT", false);
		tableFile.addRequiredField("field_value200", "FLOAT", false);
		tableFile.addRequiredField("field_value210", "FLOAT", false);
		tableFile.addRequiredField("field_value220", "FLOAT", false);
		tableFile.addRequiredField("field_value230", "FLOAT", false);
		tableFile.addRequiredField("field_value240", "FLOAT", false);
		tableFile.addRequiredField("field_value250", "FLOAT", false);
		tableFile.addRequiredField("field_value260", "FLOAT", false);
		tableFile.addRequiredField("field_value270", "FLOAT", false);
		tableFile.addRequiredField("field_value280", "FLOAT", false);
		tableFile.addRequiredField("field_value290", "FLOAT", false);
		tableFile.addRequiredField("field_value300", "FLOAT", false);
		tableFile.addRequiredField("field_value310", "FLOAT", false);
		tableFile.addRequiredField("field_value320", "FLOAT", false);
		tableFile.addRequiredField("field_value330", "FLOAT", false);
		tableFile.addRequiredField("field_value340", "FLOAT", false);
		tableFile.addRequiredField("field_value350", "FLOAT", false);
		tableFiles.add(tableFile);

		tableName = "elevation_pattern_addl";
		tableFile = new TableFile(tableName, CDBSFieldNamesMap.get(tableName), "INDEX (elevation_antenna_id)", -1,
			fileDirectory, zipFile, true);
		tableFile.addRequiredField("elevation_antenna_id", "INT", false);
		tableFile.addRequiredField("azimuth", "FLOAT", false);
		tableFile.addRequiredField("depression_angle", "FLOAT", false);
		tableFile.addRequiredField("field_value", "FLOAT", false);
		tableFiles.add(tableFile);

		// Make sure all field name lists were found, and open all the files.

		String errmsg = null;
		for (TableFile theFile : tableFiles) {
			if (null == theFile.fieldNames) {
				errmsg = "Field name list not found for table '" + theFile.tableName + "'";
				break;
			}
			if (!theFile.openFile()) {
				if (theFile.required) {
					errmsg = "Data file '" + theFile.fileName + "' could not be opened";
					break;
				}
			}
		}

		if (null != errmsg) {
			for (TableFile theFile : tableFiles) {
				theFile.closeFile();
			}
			if (null != errors) {
				errors.reportError(errmsg);
			}
			return null;
		}

		return tableFiles;
	}


	//=================================================================================================================
	// A class used to accumulate a most-recent date and a count of occurrences of that date from a series of dates
	// expressed as strings; the date format string is given to the constructor.

	private static class DateCounter {

		private SimpleDateFormat dateFormat;

		private java.util.Date latestDate;
		private int latestDateCount;
		

		//-------------------------------------------------------------------------------------------------------------

		private DateCounter(String theDateFormat) {

			dateFormat = new SimpleDateFormat(theDateFormat, Locale.US);

			latestDate = new java.util.Date(0);
			latestDateCount = 0;
		}


		//-------------------------------------------------------------------------------------------------------------

		private void add(String theDateStr) {

			java.util.Date theDate = null;
			try {
				theDate = dateFormat.parse(theDateStr);
			} catch (ParseException pe) {
			}

			if ((null != theDate) && !theDate.before(latestDate)) {
				if (theDate.after(latestDate)) {
					latestDate.setTime(theDate.getTime());
					latestDateCount = 1;
				} else {
					latestDateCount++;
				}
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		private String getDate() {

			if (latestDateCount > 0) {
				return dateFormat.format(latestDate);
			}
			return "(unknown)";
		}


		//-----------------------------------------------------------------------------------------------------------------

		private String getCount() {

			return String.valueOf(latestDateCount);
		}


		//-----------------------------------------------------------------------------------------------------------------

		private java.util.Date getDbDate() {

			return latestDate;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create the table and copy data from an open file.  If the fieldNames list is non-null that provides a list of
	// field names for the table, otherwise the field names are read from the first line of the dump file (currently
	// LMS files have the field names, CDBS and wireless files do not).  In any case, the required fields are those
	// that must appear and/or require specific typing, all other fields are typed generically.  If extraDefinitions is
	// non-null that is appended to the end of the table definition, typically to add indices.  If a date counter is
	// provided and a date field index is set, contents of that field are passed to the counter.  Note the index is to
	// the requiredFields list, not the fieldNames array.  Returns null on success else an error message.

	private static String createAndCopyTable(DbConnection db, TableFile tableFile, DateCounter dateCounter) {

		String errmsg = null;
		int i, j, lineCount = 1;
		boolean didCreate = false;

		try {

			// Read the names list from the file if needed.  The names list from the file will have an extra element
			// due to the line terminator sequence.

			String[] fieldNames = tableFile.fieldNames;
			int fieldCount = 0;
			if (null == fieldNames) {
				fieldNames = tableFile.reader.readLine().split("\\" + TableFile.SEPARATOR);
				lineCount++;
				fieldCount = fieldNames.length - 1;
			} else {
				fieldCount = fieldNames.length;
			}

			// Check the names list, names must be at least three characters long, contain only lower-case letters,
			// digits, or the character '_', and there must be at least two names in the list.  This is mainly to
			// confirm a names list just read from file wasn't actually a line of data due to a missing header.

			char c;
			for (i = 0; i < fieldCount; i++) {
				if (fieldNames[i].length() < 3) {
					fieldCount = -1;
				} else {
					for (j = 0; j < fieldNames[i].length(); j++) {
						c = fieldNames[i].charAt(j);
						if (!Character.isLowerCase(c) && !Character.isDigit(c) && ('_' != c)) {
							fieldCount = -1;
							break;
						}
					}
				}
				if (fieldCount < 0) {
					break;
				}
			}
			if (fieldCount < 2) {
				return "Missing or bad field names list for data file '" + tableFile.fileName + "'";
			}

			// Compose the table definition.  Along the way build an array of flags indicating fields that contain
			// text, those will have content quoted during the copy, see below.

			for (TableField field : tableFile.requiredFields) {
				field.index = -1;
			}

			boolean[] textFlags = new boolean[fieldCount];

			StringBuilder query = new StringBuilder("CREATE TABLE ");
			query.append(tableFile.tableName);
			query.append(' ');

			String type;
			char sep = '(';

			for (i = 0; i < fieldCount; i++) {

				type = "VARCHAR(255)";
				textFlags[i] = true;

				for (TableField field : tableFile.requiredFields) {
					if ((field.index < 0) && field.name.equals(fieldNames[i])) {
						type = field.type;
						textFlags[i] = field.isText;
						field.index = i;
						break;
					}
				}

				query.append(sep);
				query.append(fieldNames[i]);
				query.append(' ');
				query.append(type);
				sep = ',';
			}

			// Check for missing fields.

			for (TableField field : tableFile.requiredFields) {
				if ((0 == field.version) && (field.index < 0)) {
					return "Required field '" + field.name + "' not found in data file '" + tableFile.fileName + "'";
				}
			}

			// Create the table.

			if ((null != tableFile.extraDefinitions) && (tableFile.extraDefinitions.length() > 0)) {
				query.append(sep);
				query.append(tableFile.extraDefinitions);
			}
			query.append(')');

			db.update(query.toString());
			didCreate = true;

			// Copy file contents into the table.  The lines have an explicit line termination sequence of separator-
			// terminator-separator characters.  Newline and carriage return characters are ignored regardless of
			// context.  No nulls are inserted; blank text fields get empty strings, non-text get 0.

			query.setLength(0);
			query.append("INSERT INTO " + tableFile.tableName + " VALUES (");
			int startLength = query.length();

			int fieldIndex = 0, ci = -1, termstate = 0, dateFieldIndex = -1;
			char cc = '\0';
			boolean firstChar = true;
			StringBuilder values = new StringBuilder(), dateStr = null;

			if ((null != dateCounter) && (tableFile.dateFieldIndex >= 0) &&
					(tableFile.dateFieldIndex < tableFile.requiredFields.size())) {
				dateFieldIndex = tableFile.requiredFields.get(tableFile.dateFieldIndex).index;
				dateStr = new StringBuilder();
			}

			// Character-by-character read loop.  EOF does not imply a line terminator, that must be explicit.

			while (true) {

				ci = tableFile.reader.read();
				if (ci < 0) {
					if ((fieldIndex > 0) || !firstChar) {
						errmsg = "Unexpected EOF in data file '" + tableFile.fileName + "'";
						break;
					}
					if (query.length() > startLength) {
						db.update(query.toString());
					}
					break;
				}

				cc = (char)ci;

				// Termstate follows 1-2-3 through the expected separator-terminator-separator characters of the line
				// termination sequence.  The first separator also closes the last field.  If the terminator character
				// is seen after a separator do a short loop expecting the next character to be a separator, if it is
				// not that means the terminator character was actually data, set termstate to -1 and the character
				// will be added back into the field value below.

				if (TableFile.SEPARATOR == cc) {
					if (2 == termstate) {
						termstate = 3;
					} else {
						termstate = 1;
					}
				} else {
					if (1 == termstate) {
						if (TableFile.TERMINATOR == cc) {
							termstate = 2;
							continue;
						} else {
							termstate = 0;
						}
					} else {
						if (2 == termstate) {
							termstate = -1;
						} else {
							termstate = 0;
						}
					}
				}

				// If line termination seen, first check for a completely blank line and skip.  Otherswise verify the
				// field count, if bad fail, otherwise append the values list to the query.  If the query string gets
				// too long send it and start a new one.  Then reset state for the next line and loop.

				if (3 == termstate) {

					if ((0 == fieldIndex) && firstChar) {
						continue;
					}

					if (fieldIndex != fieldCount) {
						errmsg = "Incorrect field count in data file '" + tableFile.fileName + "' at line " + lineCount;
						break;
					}

					if (query.length() > startLength) {
						query.append(",(");
					}
					query.append(values);
					query.append(')');

					if (query.length() > DbCore.MAX_QUERY_LENGTH) {
						db.update(query.toString());
						query.setLength(startLength);
					}

					fieldIndex = 0;
					firstChar = true;
					values.setLength(0);
					lineCount++;
					termstate = 0;

				// On a separator, if not past the maximum field count add to the values list as needed.  If the field
				// was empty add a blank string or 0 value, if the field was text and has data add a closing quote.  If
				// this is the field being used for date counting it was also accumulated separately for that, send it
				// to the date counter.

				} else {

					if (1 == termstate) {

						if (fieldIndex < fieldCount) {

							if (firstChar) {
								if (textFlags[fieldIndex]) {
									values.append("''");
								} else {
									values.append('0');
								}
							} else {
								if (textFlags[fieldIndex]) {
									values.append('\'');
								}
							}
							if (fieldIndex < (fieldCount - 1)) {
								values.append(',');
							}

							if (dateFieldIndex == fieldIndex) {
								dateCounter.add(dateStr.toString());
								dateStr.setLength(0);
							}
						}

						fieldIndex++;
						firstChar = true;

					// Process a field character.  Any newline or carriage return here is ignored, also if past the
					// max field count everything is being ignored.  Otherwise if this is the first character of a text
					// field start with a quote.  See above regarding termstate = -1.  Escape quotes and backslashes as
					// needed, else just add the character.  If this field is being used for date counting also add the
					// character to the date string, no escaping there since it will only be sent to the date counter.

					} else {

						if (('\n' != cc) && ('\r' != cc) && (fieldIndex < fieldCount)) {

							if (firstChar && textFlags[fieldIndex]) {
								values.append('\'');
							}

							if (-1 == termstate) {
								values.append(TableFile.TERMINATOR);
								termstate = 0;
							}

							switch (cc) {
								case '\'': {
									values.append("''");
									break;
								}
								case '\\': {
									values.append("\\\\");
									break;
								}
								default: {
									values.append(cc);
									break;
								}
							}

							if (dateFieldIndex == fieldIndex) {
								dateStr.append(cc);
							}

							firstChar = false;
						}
					}
				}
			}

		} catch (IOException ie) {
			errmsg = "An I/O error occurred on data file '" + tableFile.fileName + "' at line " + lineCount +
				":\n" + ie;

		} catch (SQLException se) {
			errmsg = "A database error occurred on data file '" + tableFile.fileName + "' at line " + lineCount +
				":\n" + se;
			db.reportError(se);

		} catch (Throwable t) {
			errmsg = "An unexpected error occurred on data file '" + tableFile.fileName + "' at line " + lineCount +
				":\n" + t;
			AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
		}

		// If an error occurred after the table was created, drop it again.

		if ((null != errmsg) && didCreate) {
			try {
				db.update("DROP TABLE " + tableFile.tableName);
			} catch (SQLException se) {
				db.reportError(se);
			}
		}

		return errmsg;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Rename a database.  The new name is assumed to have been checked for length and reserved characters, uniqueness
	// is checked here and enforced by appending the key suffix if needed.  Note when the name is set the is_download
	// flag is cleared, see downloadDatabase().  Also when a data set is deleted the name is always cleared, so only
	// non-deleted sets need to be checked for name uniqueness.

	public static void renameDatabase(String theDbID, Integer theKey, String newName) {
		renameDatabase(theDbID, theKey, newName, null);
	}

	public static void renameDatabase(String theDbID, Integer theKey, String newName, ErrorLogger errors) {

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return;
		}

		boolean error = false;
		String errmsg = null;

		try {

			db.update("LOCK TABLES ext_db WRITE");

			if (newName.length() > 0) {
				db.query("SELECT ext_db_key FROM ext_db WHERE NOT deleted AND UPPER(name) = '" +
					db.clean(newName.toUpperCase()) + "' AND ext_db_key <> " + theKey);
				if (db.next()) {
					newName = newName + " " + String.valueOf(DbCore.NAME_UNIQUE_CHAR) + String.valueOf(theKey);
				}
				db.update("UPDATE ext_db SET name = '" + db.clean(newName) +
					"', is_download = false WHERE ext_db_key = " + theKey);
			} else {
				db.update("UPDATE ext_db SET name = '', is_download = false WHERE ext_db_key = " + theKey);
			}

		} catch (SQLException se) {
			error = true;
			errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
			db.reportError(se);
		}

		try {
			db.update("UNLOCK TABLES");
		} catch (SQLException se) {
			db.reportError(se);
		}

		DbCore.releaseDb(db);

		if (error && (null != errors)) {
			errors.reportError(errmsg);
		}

		reloadCache(theDbID);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Delete a database.  Keys in the reserved range cannot be deleted, they don't represent actual import databases.
	// When a data set is deleted the name is also cleared, names only apply to current sets.  This will not delete
	// the data set if it is locked.  Also this does not actually drop the database, just updates the content of the
	// index table.  The drops occur in closeDb() which syncs the actual database vs. index state.  That ensures
	// databases remain available if existing objects try to use them in this openDb()/closeDb() context.

	public static void deleteDatabase(String theDbID, Integer theKey) {
		deleteDatabase(theDbID, theKey, null);
	}

	public static void deleteDatabase(String theDbID, Integer theKey, ErrorLogger errors) {

		if ((theKey.intValue() >= RESERVED_KEY_RANGE_START) && (theKey.intValue() <= RESERVED_KEY_RANGE_END)) {
			return;
		}

		ExtDb theDb = getExtDb(theDbID, theKey, errors);
		if ((null == theDb) || theDb.deleted) {
			return;
		}

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return;
		}

		boolean error = false;
		String errmsg = "";

		try {

			db.update("LOCK TABLES ext_db WRITE");

			db.query("SELECT locked, deleted FROM ext_db WHERE ext_db_key = " + theKey);
			if (db.next()) {
				if (db.getBoolean(1)) {
					errmsg = "The station data is in use and cannot be deleted.";
					error = true;
				} else {
					if (!db.getBoolean(2)) {
						db.update("UPDATE ext_db SET deleted = true, is_download = false, name = '' " +
							"WHERE ext_db_key = " + theKey);
					}
				}
			}

		} catch (SQLException se) {
			error = true;
			errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
			db.reportError(se);
		}

		try {
			db.update("UNLOCK TABLES");
		} catch (SQLException se) {
			db.reportError(se);
		}

		DbCore.releaseDb(db);

		if (error && (null != errors)) {
			errors.reportError(errmsg);
		}

		reloadCache(theDbID);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by DbCore when a database is being closed.  First scan the actual database list and identify all data
	// set databases by name, then compare that to the content of the ext_db table.  If a database does not exist in
	// the table or is marked deleted, drop the database (see deleteDatabase()).  If there is an entry in the table
	// for which the database does not exist and the entry is not marked deleted, mark it deleted.

	public static synchronized void closeDb(String theDbID) {

		DbConnection db = DbCore.connectDb(theDbID);
		if (null != db) {
			try {

				String rootName = DbCore.getDbName(theDbID);

				HashSet<String> dbNames = new HashSet<String>();
				for (String baseName : getDbBaseNames()) {
					db.query("SHOW DATABASES LIKE '" + rootName + "\\_" + baseName + "%'");
					while (db.next()) {
						dbNames.add(db.getString(1));
					}
				}

				db.update("LOCK TABLES ext_db WRITE");

				HashSet<Integer> markDel = new HashSet<Integer>();
				int theKey;

				db.query("SELECT ext_db_key, db_type, locked FROM ext_db WHERE NOT deleted");

				while (db.next()) {
					theKey = db.getInt(1);
					if (!dbNames.remove(makeDbName(rootName, db.getInt(2), theKey))) {
						markDel.add(Integer.valueOf(theKey));
					}
				}

				if (!markDel.isEmpty()) {
					db.update("UPDATE ext_db SET deleted = true, is_download = false, name = '' WHERE ext_db_key IN " +
						DbConnection.makeKeyList(markDel));
				}

				db.update("UNLOCK TABLES");

				for (String dbName : dbNames) {
					db.update("DROP DATABASE IF EXISTS " + dbName);
				}

			} catch (SQLException se) {
				db.reportError(se);
			}

			try {
				db.update("UNLOCK TABLES");
			} catch (SQLException se) {
				db.reportError(se);
			}

			DbCore.releaseDb(db);
		}
	
		dbCache.remove(theDbID);
		mrCache.remove(theDbID);
		lmsLiveDbCache.remove(theDbID);
	}
}
