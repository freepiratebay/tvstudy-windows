//
//  DbCore.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core;

import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;
import gov.fcc.tvstudy.core.geo.*;

import java.util.*;
import java.sql.*;
import java.io.*;
import java.text.*;


//=====================================================================================================================
// Database manager.  Provides connection management and persistent properties for multiple databases.  This is all
// static methods and properties, it is never instantiated.  Multiple databases can be open simultaneously.  Databases
// are identified by a UUID string stored in the database itself, so a connection will be recognized as being to the
// same database even if that can be reached by different host names; only one open state to a specific database is
// allowed at a time, regardless of host name.  DbConnection objects for a database are retrieved from a pool using
// connectDb(), and returned with releaseDb().  An open database state also provides name-value properties backed by a
// database table accessed with get*Property() and set*Property().  This also now includes the code to install and
// update root databases, prior to version 2.2.2 that code was in gui.DbController.

public class DbCore {

	// The database version number indicates the version of the database schema and root database contents, see
	// DbInfo class below.  This version number must be in sync with the study engine code!  As of application version
	// 2.2.3, the database has one more level of versioning than the application, e.g. 2.2.3.1, so multiple database
	// updates can occur within the development cycle of a given application version.

	public static final int DATABASE_VERSION = 20200401;

	// Default root database name.  The term "database" here is a bit ambiguous, in addition to a specific database on
	// a specific server, it also refers to a collection of such databases on one server sharing a common name prefix.
	// That name with no suffix is the root database, other databases add various suffixes (see Study, ExtDb).  Thus
	// multiple database collections can co-exist on the same server by varying the root name.

	public static final String DEFAULT_DB_NAME = "tvstudy";

	// Limit on the length of a query string, used in code that composes long INSERT queries.  The actual query length
	// may exceed this, it is meant to be checked after each value tuple is appended and if exceeded the query is sent
	// and a new one started.  So this should not be the true maximum for the connection but somewhat less than that.

	public static final int MAX_QUERY_LENGTH = 500000;

	// See DbInfo.

	public static final String DEFAULT_HOST_KEY = "host";
	public static final String DEFAULT_NAME_KEY = "name";
	public static final String DEFAULT_USER_KEY = "user";

	public static final String DB_ID_KEY_PREFIX = "db_uuid_";

	// Database connection objects and pools, see openDb() and connectDb().

	private static HashMap<String, DbInfo> dbs = new HashMap<String, DbInfo>();
	private static HashMap<String, ArrayDeque<DbConnection>> dbPools = new HashMap<String, ArrayDeque<DbConnection>>();
	private static HashMap<DbConnection, String> openDbs = new HashMap<DbConnection, String>();

	// Key-value properties, these are stored in the databases and automatically synchronized to the backing tables.

	private static HashMap<String, HashMap<String, String>> propertyMaps =
		new HashMap<String, HashMap<String, String>>();
	private static HashMap<String, HashMap<String, String>> changedPropertyMaps =
		new HashMap<String, HashMap<String, String>>();
	private static HashMap<String, Long> lastPropertySyncTimes = new HashMap<String, Long>();

	private static final int PROPERTY_SYNC_INTERVAL = 600000;   // milliseconds


	//=================================================================================================================
	// Class used for creating a connection and tracking state related to a database.  To open a database (which is in
	// this context a database collection, see comments above), an instance of this class must be created first using
	// host name, root database name, user and password.  The constructor will attempt to open a connection and perform
	// various queries to identify the database schema version, obtain UUID, etc.  If the canOpen flag is true, the
	// database is fully usable by the application and can be opened for use with dbOpen().  The update() method can
	// also be called to refresh the state information for example after a schema update.

	public static class DbInfo {

		public final String dbHostname;
		public final String dbName;
		public final String dbUsername;
		public final String dbPassword;

		public DbConnection db;

		public boolean hasRoot;
		public boolean hasVersionTable;
		public int version;

		public String dbID;
		public String idKey;

		public long cacheSize;

		// If any error occurs during construction this is updated appropriately.

		public String setupError = "Unknown error";

		// These control UI behavior, may be inconsistent with other properties if errors occur during operations such
		// as database install or update.

		public boolean connectionFailed;
		public String lookupErrorMessage;

		public String statusText;

		public boolean needsManage;
		public boolean canInstall;
		public boolean canUninstall;
		public boolean canUnlock;
		public boolean canUpdate;
		public boolean canOpen;


		//-------------------------------------------------------------------------------------------------------------
		// If either the username or password is blank no connection is attempted; that occurs when a manager dialog
		// is being opened just to manage local cache, see gui.DbController.  If the connection fails or is not
		// attempted, the DbConnection property will be null.  Otherwise do an initial update of properties, see
		// update(), and also save the host and user as defaults for future connection attempts.

		// As of version 1.2.10, databases are identified by a UUID instead of using the host name so each database
		// installation is recognized regardless of the specific host name used to open the connection; host names for
		// the same server can of course vary.  To deal with situations where the actual connection can't (or won't) be
		// made the UUIDs are also cached in local properties by host name (all host names ever used for a particular
		// database).  If a UUID can't be found at all the host name will be used as the internal identifying key.

		// As of version 1.3.2 the root database name (also the base name for study and other databases) can vary so a
		// particular host can have multiple databases installed.  Each has it's own UUID so the database name may have
		// to be combined with the host name as the key for UUID caching.  But for backwards compatibility, if the name
		// is the default it is not included in the key.

		public DbInfo(String theHost, String theName, String theUser, String thePass) {

			dbHostname = theHost;
			dbName = theName;
			dbUsername = theUser;
			dbPassword = thePass;

			if (dbName.equals(DEFAULT_DB_NAME)) {
				idKey = DB_ID_KEY_PREFIX + dbHostname;
			} else {
				idKey = DB_ID_KEY_PREFIX + dbHostname + "_" + dbName;
			}

			if ((dbUsername.length() > 0) && (dbPassword.length() > 0)) {

				db = new DbConnection("jdbc:mysql:", dbHostname, dbUsername, dbPassword);

				if (db.connect()) {

					AppCore.setProperty(DEFAULT_HOST_KEY, dbHostname);
					AppCore.setProperty(DEFAULT_NAME_KEY, dbName);
					AppCore.setProperty(DEFAULT_USER_KEY, dbUsername);

				} else {

					db = null;
					setupError = "Could not open connection, login may be incorrect";
					connectionFailed = true;
					statusText = "Connection failed";
				}

			} else {

				statusText = "No connection";
			}

			update();

			if (null != db) {
				db.close();
			}
		}


		//-------------------------------------------------------------------------------------------------------------
		// Update the database info, everything but the cache size; since updating that is time-consuming and it is
		// not always needed, the caller must update that separately.  If the connection exists it must be open.  First
		// check for the root database, then determine the version number.  Early root databases did not have a version
		// number, those are no longer supported.  Later the version number was a column in the study table, however
		// that design was never fully implemented and the version will always be the same for all studies; but if
		// there are no studies the version can't be determined and that case is also no longer supported.  Ultimately
		// a version table was added with a single row holding a version number applying to all schema and content.

		public synchronized void update() {

			hasRoot = false;
			hasVersionTable = false;
			version = 0;

			dbID = dbHostname;
			boolean hasID = false, saveID = false;
			String id = AppCore.getProperty(idKey);
			if (null != id) {
				try {
					UUID.fromString(id);
					dbID = id;
					hasID = true;
				} catch (IllegalArgumentException ie) {
				}
			}

			lookupErrorMessage = null;

			needsManage = true;
			canInstall = false;
			canUninstall = false;
			canUnlock = false;
			canUpdate = false;
			canOpen = false;

			if (null == db) {
				return;
			}

			try {

				int numlocks = 0;
				String studyTable;

				db.query("SHOW DATABASES LIKE '" + dbName + "'");
				hasRoot = db.next();

				if (hasRoot) {

					version = -1;

					db.setDatabase(dbName);

					db.query("SHOW TABLES LIKE 'study'");
					if (db.next()) {

						db.query("SHOW CREATE TABLE study");
						db.next();
						studyTable = db.getString(2);

						db.query("SHOW TABLES LIKE 'version'");
						hasVersionTable = db.next();

						if (hasVersionTable) {
							db.query("SELECT * FROM version");
							if (db.next()) {
								version = db.getInt("version");
								if (version >= 102010) {
									id = db.getString("uuid");
									if (!hasID || !dbID.equals(id)) {
										try {
											UUID.fromString(id);
											dbID = id;
											hasID = true;
											saveID = true;
										} catch (IllegalArgumentException ie) {
										}
									}
								}
							}
						} else {
							if (studyTable.contains("version")) {
								db.query("SELECT MAX(version) FROM study");
								if (db.next()) {
									version = db.getInt(1);
								}
							}
						}

						if (studyTable.contains("study_lock")) {
							db.query("SELECT COUNT(*) FROM study WHERE study_lock <> " + Study.LOCK_NONE);
							db.next();
							numlocks = db.getInt(1);
						}
					}
				}

				if (saveID) {
					AppCore.setProperty(idKey, dbID);
				}

				// Set UI flags.

				if (hasRoot) {
					canUninstall = true;
					if ((DATABASE_VERSION == version) && hasID) {
						statusText = "OK";
						needsManage = false;
						canUnlock = (numlocks > 0);
						canOpen = true;
					} else {
						if (0 == version) {
							setupError = "Database is currently being updated or installed";
							statusText = "In update or install";
						} else {
							canUpdate = canUpdateDb(version);
							if (canUpdate) {
								setupError = "Database needs to be updated";
								statusText = "Needs update";
								if (numlocks > 0) {
									canUnlock = true;
									canUpdate = false;
								}
							} else {
								setupError = "Unknown or unsupported database version";
								statusText = "Unsupported";
							}
						}
					}
				} else {
					setupError = "Database is not installed on the server";
					statusText = "Not installed";
					canInstall = true;
				}

			} catch (SQLException se) {
				db.reportError(se);
				setupError = "Database query error: " + se;
				lookupErrorMessage = DbConnection.ERROR_TEXT_PREFIX + se;
				statusText = "Query error";
			}
		}


		//-------------------------------------------------------------------------------------------------------------
		// Update the database cache size.  This may take a while so should be called on a background thread.

		public synchronized void updateCacheSize() {

			cacheSize = AppCore.getStudyCacheSize(dbID);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Open a database, the connection itself was already created by the DbInfo constructor, this is just adding local
	// state for the connection object pool and properties.  Check DbInfo state to be sure the connection is usable.
	// Note if the database UUID is already open this returns true but does not change existing state.

	private static boolean didCacheLoad = false;

	public static boolean openDb(DbInfo theInfo) {
		return openDb(theInfo, null);
	}

	public static synchronized boolean openDb(DbInfo theInfo, ErrorLogger errors) {

		if ((null == theInfo.db) || !theInfo.canOpen) {
			if (null != errors) {
				errors.reportError(theInfo.setupError);
			}
			return false;
		}

		if (dbs.containsKey(theInfo.dbID)) {
			return true;
		}

		// On the first successful open of any database, load caches of various root tables.  That cached content does
		// not vary between open databases nor does it need to be updated.  The root tables and contents are the same
		// for a given database version and only databases with the current version number can be opened, see DbInfo.

		DbConnection db = theInfo.db;

		if (!db.connect(theInfo.dbName, errors)) {
			return false;
		}

		if (!didCacheLoad) {

			try {

				ChannelBand.loadCache(db);
				ChannelDelta.loadCache(db);
				Country.loadCache(db);
				EmissionMask.loadCache(db);
				FrequencyOffset.loadCache(db);
				Service.loadCache(db);           // Indirectly loads ServiceType.
				SignalType.loadCache(db);
				Zone.loadCache(db);
				ExtDbRecord.loadCache(db);

			} catch (SQLException se) {
				db.close();
				db.reportError(errors, se);
				return false;
			}

			didCacheLoad = true;
		}

		db.close();

		// The original connection object from the DbInfo object is placed in the connection pool for immediate use,
		// if more connections are needed later those are created with copy() on that original connection.

		ArrayDeque<DbConnection> thePool = new ArrayDeque<DbConnection>();
		thePool.push(db);
		dbPools.put(theInfo.dbID, thePool);

		propertyMaps.put(theInfo.dbID, new HashMap<String, String>());
		changedPropertyMaps.put(theInfo.dbID, new HashMap<String, String>());
		lastPropertySyncTimes.put(theInfo.dbID, Long.valueOf(0));

		dbs.put(theInfo.dbID, theInfo);

		// Scan for XML files in the xml/ directory and attempt to import as templates or geographies.  An index of
		// files is kept in a root table, whether imported successfully or not.  If a file is in the index and both
		// modification time and length match the file is assumed the same and skipped, otherwise import is attempted
		// and the index updated.  Index entries are never removed, a given file is imported exactly once to a given
		// root database.  This is a convenience, the files can always be manually imported if needed.  All errors are
		// ignored during import, errors involving the index will abort the scan but are otherwise ignored.  This had
		// to wait until connection state is set up because the XML read methods use connectDb() so have to use that
		// for the index table here too.  That should never fail but if somehow it does abort the whole process.

		db = connectDb(theInfo.dbID, errors);
		if (null == db) {
			closeDb(theInfo.dbID);
			return false;
		}

		HashMap<String, long[]> fileIndex = new HashMap<String, long[]>();
		long[] theFileInfo;

		try {
			db.query("SELECT file_name, mod_time, length FROM xml_import");
			while (db.next()) {
				theFileInfo = new long[2];
				fileIndex.put(db.getString(1), theFileInfo);
				theFileInfo[0] = db.getLong(2);
				theFileInfo[1] = db.getLong(3);
			}
		} catch (SQLException se) {
			db.reportError(se);
			releaseDb(db);
			return true;
		}

		File[] files = (new File(AppCore.xmlDirectoryPath)).listFiles(new FilenameFilter() {
			public boolean accept(File theDir, String theName) {
				return theName.toLowerCase().endsWith(".xml");
			}
		});

		if ((null != files) && (files.length > 0)) {

			String theName;
			long theModTime, theLength;

			BufferedReader xml;
			Integer tempKey;
			Geography geo;

			for (File theFile : files) {

				theName = theFile.getName();
				theModTime = theFile.lastModified();
				theLength = theFile.length();
				if ((0L == theModTime) || (0L == theLength)) {
					continue;
				}

				theFileInfo = fileIndex.get(theName);
				if ((null != theFileInfo) && (theModTime == theFileInfo[0]) && (theLength == theFileInfo[1])) {
					continue;
				}

				try {

					xml = new BufferedReader(new FileReader(theFile));
					tempKey = TemplateEditData.readTemplateFromXML(theInfo.dbID, xml);

					if (null == tempKey) {
						xml.reset();
						geo = Geography.readGeographyFromXML(theInfo.dbID, xml);
						if (null != geo) {
							geo.save();
						}
					}

					xml.close();

				} catch (Throwable t) {
				}

				try {
					if (null != theFileInfo) {
						db.update("UPDATE xml_import SET mod_time = " + theModTime + ", length = " + theLength +
							" WHERE file_name = '" + db.clean(theName) + "'");
					} else {
						db.update("INSERT INTO xml_import VALUES ('" + db.clean(theName) + "', " + theModTime + ", " +
							theLength + ")");
					}
				} catch (SQLException se) {
					db.reportError(se);
					break;
				}
			}
		}

		releaseDb(db);

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Determine if a database is already in the open-databases list.

	public static synchronized boolean isDbOpen(String theDbID) {

		return dbs.containsKey(theDbID);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Return an open connection to the main server for a database.  Connections are maintained in a pool to support
	// multi-threaded use and simultaneous connections.  Once a connection is obtained with connectDb() it is moved to
	// an open connection list and will not be provided to another caller until it is returned to the pool by calling
	// releaseDb().  Due to the linger-open behavior (see DbConnection) a released connection is always put at the
	// front of the pool queue to maximize the chance it will be used again while still open.  When new connection
	// objects are needed they are obtained by copying the original objects given to openDb().  This will return null
	// if there is no state for the database ID or if the connection cannot be opened.  The open connection is always
	// set to the root database, caller can use db.setDatabase() to change as needed.

	public static DbConnection connectDb(String theDbID) {
		return connectDb(theDbID, null);
	}

	public static synchronized DbConnection connectDb(String theDbID, ErrorLogger errors) {

		DbInfo theInfo = dbs.get(theDbID);
		if (null == theInfo) {
			if (null != errors) {
				errors.reportError("Database connection failed, unknown database ID.");
			}
			return null;
		}

		DbConnection db = dbPools.get(theDbID).poll();
		if (null == db) {
			db = dbs.get(theDbID).db.copy();
		}

		openDbs.put(db, theDbID);

		if (!db.connect(errors)) {
			releaseDb(db);
			return null;
		}

		try {
			db.setDatabase(theInfo.dbName);
		} catch (SQLException se) {
			releaseDb(db);
			DbConnection.reportError(errors, se);
			return null;
		}

		return db;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Release a database connection object, close it and return to it's pool.

	public static synchronized void releaseDb(DbConnection db) {

		db.close();
		String theDbID = openDbs.remove(db);
		if (null != theDbID) {
			dbPools.get(theDbID).push(db);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Accessors for properties of the DbInfo object for a particular database.

	public static synchronized String getDbHostname(String theDbID) {

		DbInfo theInfo = dbs.get(theDbID);
		if (null == theInfo) {
			return "";
		}

		return theInfo.dbHostname;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static synchronized String getDbName(String theDbID) {

		DbInfo theInfo = dbs.get(theDbID);
		if (null == theInfo) {
			return "";
		}

		return theInfo.dbName;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static synchronized String getDbUsername(String theDbID) {

		DbInfo theInfo = dbs.get(theDbID);
		if (null == theInfo) {
			return "";
		}

		return theInfo.dbUsername;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static synchronized String getDbPassword(String theDbID) {

		DbInfo theInfo = dbs.get(theDbID);
		if (null == theInfo) {
			return "";
		}

		return theInfo.dbPassword;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The database UUID is used to key all references to the database itself, however the current host name is still
	// used for display purposes since the UUID string would not be very helpful to the user.  This method retrieves
	// the current host name for an open database.  If the root database name is not the default, include that too.

	public static synchronized String getHostDbName(String theDbID) {

		DbInfo theInfo = dbs.get(theDbID);
		if (null == theInfo) {
			return "unknown";
		}

		if (theInfo.dbName.equals(DEFAULT_DB_NAME)) {
			return theInfo.dbHostname;
		} else {
			return theInfo.dbHostname + "-" + theInfo.dbName;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Shut down connection to a database; synchronize properties, close all connections, and remove state.  Also
	// remove state from various other static contexts e.g. ExtDb.

	public static synchronized void closeDb(String theDbID) {

		if (!dbs.containsKey(theDbID)) {
			return;
		}

		syncProperties(theDbID, false);

		dbs.remove(theDbID);

		propertyMaps.remove(theDbID);
		changedPropertyMaps.remove(theDbID);
		lastPropertySyncTimes.remove(theDbID);

		Map.Entry<DbConnection, String> e;
		Iterator<Map.Entry<DbConnection, String>> it = openDbs.entrySet().iterator();
		while (it.hasNext()) {
			e = it.next();
			if (e.getValue().equals(theDbID)) {
				e.getKey().close(false);
				it.remove();
			}
		}

		ArrayDeque<DbConnection> thePool = dbPools.remove(theDbID);
		for (DbConnection db : thePool) {
			db.close(false);
		}

		ExtDb.closeDb(theDbID);
		ExtDbRecordTV.closeDb(theDbID);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Provide a persistent property service, similar to Properties but the backing stores are in the SQL databases
	// and automatically synchronized.  The core methods setProperty() and getProperty() work with string values; other
	// type-specific convenience methods are provided that convert to and from strings.  All accessors return null for
	// not found, stored values can never be null.  If the database ID string is null or empty, this falls back to the
	// local application properties, see AppCore.

	public static synchronized String getProperty(String theDbID, String name) {

		if ((null == theDbID) || (0 == theDbID.length())) {
			return AppCore.getProperty(name);
		}

		HashMap<String, String> properties = propertyMaps.get(theDbID);
		if (null == properties) {
			return null;
		}

		if ((System.currentTimeMillis() - lastPropertySyncTimes.get(theDbID).longValue()) > PROPERTY_SYNC_INTERVAL) {
			syncProperties(theDbID, true);
			properties = propertyMaps.get(theDbID);
		}

		return properties.get(name);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Values stored in the properties can never be null.

	public static synchronized void setProperty(String theDbID, String name, String value) {

		if (null == value) {
			return;
		}

		if ((null == theDbID) || (0 == theDbID.length())) {
			AppCore.setProperty(name, value);
			return;
		}

		HashMap<String, String> properties = propertyMaps.get(theDbID);
		if (null == properties) {
			return;
		}

		String oldValue = properties.put(name, value);

		if ((null == oldValue) || !value.equals(oldValue)) {
			changedPropertyMaps.get(theDbID).put(name, value);
		}

		if ((System.currentTimeMillis() - lastPropertySyncTimes.get(theDbID).longValue()) > PROPERTY_SYNC_INTERVAL) {
			syncProperties(theDbID, true);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static Boolean getBooleanProperty(String theDbID, String name) {

		String theValue = getProperty(theDbID, name);

		Boolean result = null;
		if (null != theValue) {
			result = Boolean.valueOf(theValue);
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static void setBooleanProperty(String theDbID, String name, Boolean value) {

		setProperty(theDbID, name, value.toString());
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static Integer getIntegerProperty(String theDbID, String name) {

		String theValue = getProperty(theDbID, name);

		Integer result = null;
		if (null != theValue) {
			try {
				result = Integer.valueOf(theValue);
			} catch (NumberFormatException nfe) {
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static void setIntegerProperty(String theDbID, String name, Integer value) {

		setProperty(theDbID, name, value.toString());
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The argument here may include SQL wildcards, anything that works with LIKE, to delete multiple properties.  The
	// return is the number of properties deleted.  Property deletes are assumed to be infrequent, so to keep things
	// simple the delete query is done immediately and triggers a re-sync.  First save any property changes and clear
	// the internal map, then do the delete; the next property get will re-load the map.  If sync fails do nothing.
	// Note properties that end up in the local file-backed store (ID null or empty) cannot be deleted.

	public static synchronized int deleteProperty(String theDbID, String likeName) {

		syncProperties(theDbID, false);

		DbConnection db = connectDb(theDbID);
		int rowCount = 0;

		if (null != db) {

			try {

				rowCount = db.update(
				"DELETE FROM " +
					"application_property " +
				"WHERE " +
					"name LIKE '" + db.clean(likeName) + "'");

			} catch (SQLException se) {
				db.reportError(se);
			}

			releaseDb(db);
		}

		return rowCount;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Synchronize the properties map with the database.  The properties table is locked during update, but only to
	// ensure the update operation is atomic; this does not attempt to determine if there were other updates from other
	// applications since the last load; whichever connection updates last, wins.  The argument determines if the
	// property map is reloaded after saving changes, it may not be desireable or necessary to have that done, i.e.
	// before a delete or at application quit.  If reload is false the map is cleared, it will reload automatically if
	// there is a subsequent property lookup.  To ensure a reload is all-or-nothing, the entire name-value map object
	// is replaced, the caller must retrieve the new map on return.  The sync time is updated even if an error occurs,
	// so reload attempts are not repeated excessively in case the error recurs.

	private static synchronized void syncProperties(String theDbID, boolean reload) {

		HashMap<String, String> newProperties = new HashMap<String, String>();
		Long newTime = Long.valueOf(System.currentTimeMillis());

		HashMap<String, String> changedProperties = changedPropertyMaps.get(theDbID);

		if (!changedProperties.isEmpty() || reload) {

			DbConnection db = connectDb(theDbID);

			if (null != db) {

				try {

					db.update("LOCK TABLES application_property WRITE");

					if (!changedProperties.isEmpty()) {

						Iterator<Map.Entry<String, String>> theIterator = changedProperties.entrySet().iterator();
						Map.Entry<String, String> mapEntry;
						String theName, theValue;
						int rowCount = 0;

						while (theIterator.hasNext()) {

							mapEntry = theIterator.next();
							theName = "'" + db.clean(mapEntry.getKey()) + "'";
							theValue = "'" + db.clean(mapEntry.getValue()) + "'";

							rowCount = db.update("UPDATE application_property SET value = " + theValue +
								" WHERE name = " + theName);

							if (0 == rowCount) {
								db.update("INSERT INTO application_property (name, value) VALUES (" + theName +
									", " + theValue + ")");
							}
						}

						changedProperties.clear();
					}

					if (reload) {

						db.query("SELECT name, value FROM application_property");

						while (db.next()) {
							newProperties.put(db.getString(1), db.getString(2));
						}
					}

				} catch (SQLException se) {
					db.reportError(se);
					newProperties = null;
				}

				try {
					db.update("UNLOCK TABLES");
				} catch (SQLException se) {
					db.reportError(se);
				}

				releaseDb(db);
			}
		}

		if (null != newProperties) {
			propertyMaps.put(theDbID, newProperties);
			if (!reload) {
				newTime = Long.valueOf(0);
			}
		}
		lastPropertySyncTimes.put(theDbID, newTime);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check a name string for use as a study or scenario name.  Names must be non-empty and less than 255 characters
	// long.  The study engine recognizes either names or primary keys on the command line and must be able to tell the
	// difference, so a name must have at least one non-digit character.  The names are used as directory names for
	// output file paths so they must not contain path-separator characters.  This does not assume the engine is
	// running on the same platform, a list of common separators is checked.  A uniqueness test may also be performed;
	// a study name must be unique in the database, a scenario name must be unique in the study.  However both study
	// and scenario creation logic can generate uniqueness by appending a primary key to the end of a non-unique name,
	// so the uniqueness test is optional.  But if it is not performed, the maximum allowed length is reduced to leave
	// room for the possible key suffix.  When checking a name for a new study the form taking the database ID is used.
	// When checking a name change for an existing study the StudyEditData form should be used so the name is not
	// considered a duplicate if it matches the current name.  Note in that form the uniqueness test cannot be skipped
	// as the key suffix behavior is not possible on an existing study, it can only occur at study creation.

	public static boolean checkStudyName(String theName, String dbID) {
		return checkName(true, theName, dbID, null, true, null);
	}

	public static boolean checkStudyName(String theName, String dbID, ErrorLogger errors) {
		return checkName(true, theName, dbID, null, true, errors);
	}

	public static boolean checkStudyName(String theName, String dbID, boolean checkUnique) {
		return checkName(true, theName, dbID, null, checkUnique, null);
	}

	public static boolean checkStudyName(String theName, String dbID, boolean checkUnique, ErrorLogger errors) {
		return checkName(true, theName, dbID, null, checkUnique, errors);
	}

	public static boolean checkStudyName(String theName, StudyEditData study) {
		return checkName(true, theName, study.dbID, study, true, null);
	}

	public static boolean checkStudyName(String theName, StudyEditData study, ErrorLogger errors) {
		return checkName(true, theName, study.dbID, study, true, errors);
	}

	public static boolean checkScenarioName(String theName, StudyEditData study) {
		return checkName(false, theName, study.dbID, study, true, null);
	}

	public static boolean checkScenarioName(String theName, StudyEditData study, ErrorLogger errors) {
		return checkName(false, theName, study.dbID, study, true, errors);
	}

	public static boolean checkScenarioName(String theName, StudyEditData study, boolean checkUnique) {
		return checkName(false, theName, study.dbID, study, checkUnique, null);
	}

	public static boolean checkScenarioName(String theName, StudyEditData study, boolean checkUnique,
			ErrorLogger errors) {
		return checkName(false, theName, study.dbID, study, checkUnique, errors);
	}

	private static boolean checkName(boolean isStudy, String theName, String dbID, StudyEditData study,
			boolean checkUnique, ErrorLogger errors) {

		String label;
		if (isStudy) {
			label = "study";
		} else {
			label = "scenario";
		}

		int len = theName.length();

		if (0 == len) {
			if (null != errors) {
				errors.reportValidationError("Please enter a name for the " + label + ".");
			}
			return false;
		}

		int maxLen = 255;
		if (!checkUnique) {
			maxLen = 245;
		}

		if (len > maxLen) {
			if (null != errors) {
				errors.reportValidationError("A " + label + " name cannot be more than " + maxLen +
					" characters long.");
			}
			return false;
		}

		boolean allDigits = true, badChar = false;
		char c;
		for (int i = 0; i < len; i++) {
			c = theName.charAt(i);
			if (('/' == c) || ('\\' == c) || (':' == c)) {
				badChar = true;
				break;
			}
			if (allDigits && !Character.isDigit(c)) {
				allDigits = false;
			}
		}

		if (badChar) {
			if (null != errors) {
				errors.reportValidationError("A " + label + " name cannot contain the characters '/', '\\', or ':'.");
			}
			return false;
		}

		if (allDigits) {
			if (null != errors) {
				errors.reportValidationError("A " + label + " name must contain at least one non-digit character.");
			}
			return false;
		}

		if (checkUnique) {

			boolean dupName;
			if (isStudy) {
				int theKey = Study.getStudyKeyForName(dbID, theName, errors);
				if (theKey < 0) {
					return false;
				}
				dupName = ((theKey > 0) && ((null == study) || (theKey != study.study.key)));
			} else {
				dupName = (null != study.scenarioData.get(theName));
			}

			if (dupName) {
				if (null != errors) {
					errors.reportValidationError("A " + label + " with that name already exists.");
				}
				return false;
			}
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check a version number against a list of known past versions that can be updated to current.

	private static boolean canUpdateDb(int version) {

		switch (version) {
			case 103000:
			case 103002:
			case 104000:
			case 104001:
			case 104002:
			case 104003:
			case 104005:
			case 104006:
			case 104007:
			case 104008:
			case 104009:
			case 104011:
			case 104012:
			case 104013:
			case 200000:
			case 200001:
			case 200002:
			case 200003:
			case 200004:
			case 200005:
			case 200006:
			case 200010:
			case 200011:
			case 200012:
			case 200013:
			case 200014:
			case 200016:
			case 200017:
			case 200018:
			case 200019:
			case 200020:
			case 200021:
			case 200022:
			case 200023:
			case 200024:
			case 201000:
			case 201001:
			case 201002:
			case 201003:
			case 202000:
			case 202001:
			case 202002:
			case 202003:
			case 20200301:
			case 20200302:
			case 20200400:
				return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Code to install a new root database on a database host.  If the status argument is non-null, setWaitMessage()
	// will be called on that to update status.  Check files in data/ before starting.

	public static boolean installDb(DbInfo dbInfo, StatusReporter status, ErrorLogger errors) {

		if (!dbInfo.canInstall) {
			return false;
		}

		if (!checkDataFiles(errors)) {
			return false;
		}

		DbConnection db = dbInfo.db;
		boolean result = true;

		if (db.connect(errors)) {
			try {

				doInstallDb(dbInfo, status);

			} catch (SQLException se) {
				result = false;
				if (null != errors) {
					errors.reportError(
						"An error occurred while installing the root database.\n" +
						"Uninstall and try the installation again.  The error was:\n" + se);
				}
				db.reportError(se);
			}

			dbInfo.update();

			db.close(false);
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update a database to the current version.  This will fully re-check the version and state to be safe.

	public static boolean updateDb(DbInfo dbInfo, StatusReporter status, ErrorLogger errors) {

		if (!dbInfo.canUpdate) {
			return false;
		}

		if (!checkDataFiles(errors)) {
			return false;
		}

		DbConnection db = dbInfo.db;
		String errmsg = null;

		if (db.connect(dbInfo.dbName, errors)) {
			try {

				// Lock tables and re-check the version number, it could have been updated by another app while a UI
				// was waiting for input, or be in the process of being updated now.

				if (dbInfo.hasVersionTable) {
					db.update("LOCK TABLES study WRITE, version WRITE");
					db.query("SELECT version FROM version");
				} else {
					db.update("LOCK TABLES study WRITE");
					db.query("SELECT MAX(version) FROM study");
				}
				db.next();
				int theVersion = db.getInt(1);

				if (0 != theVersion) {

					if (canUpdateDb(theVersion)) {

						// Don't do the update if any studies are currently locked.

						db.query("SELECT COUNT(*) FROM study WHERE study_lock <> " + Study.LOCK_NONE);
						db.next();
						if (0 == db.getInt(1)) {

							if (dbInfo.hasVersionTable) {
								db.update("UPDATE version SET version = 0");
							} else {
								db.update("UPDATE study SET version = 0");
							}

							db.update("UNLOCK TABLES");

							doUpdateDb(dbInfo, theVersion, status);

						} else {
							errmsg = "The database cannot be updated, studies are currently in use.";
						}

					} else {
						if (DATABASE_VERSION == theVersion) {
							errmsg = "The database has already been updated.";
						} else {
							errmsg = "The database version cannot be updated.";
						}
					}

				} else {
					errmsg = "Another application is already updating the database.";
				}

			} catch (SQLException se) {
				errmsg = "An error occurred while updating the database, it may have to be\n" +
					"repaired manually or uninstalled and re-installed.  The error was:\n" + se;
				db.reportError(se);
			}

			try {
				db.update("UNLOCK TABLES");
			} catch (SQLException se) {
				db.reportError(se);
			}

			dbInfo.update();
			db.close(false);
		}

		if (null != errmsg) {
			if (null != errors) {
				errors.reportError(errmsg);
			}
			return false;
		}
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check the state of files in the data/ directory, confirm files exist and have the correct versions.  This is
	// called before an installation or update, if it returns false the operation is not performed.

	public static boolean checkDataFiles(ErrorLogger errors) {

		String fileName = null;
		boolean result = false;

		try {

			while (true) {

				fileName = AppCore.DATA_DIRECTORY_NAME + File.separator + "pop_us_2000.dat";
				if (202003 != AppCore.getFileVersion(AppCore.makeFilePath(fileName))) {
					break;
				}

				fileName = AppCore.DATA_DIRECTORY_NAME + File.separator + "pop_us_2010.dat";
				if (202003 != AppCore.getFileVersion(AppCore.makeFilePath(fileName))) {
					break;
				}

				fileName = AppCore.DATA_DIRECTORY_NAME + File.separator + "pop_ca_2006.dat";
				if (202003 != AppCore.getFileVersion(AppCore.makeFilePath(fileName))) {
					break;
				}

				fileName = AppCore.DATA_DIRECTORY_NAME + File.separator + "pop_ca_2011.dat";
				if (202003 != AppCore.getFileVersion(AppCore.makeFilePath(fileName))) {
					break;
				}

				fileName = AppCore.DATA_DIRECTORY_NAME + File.separator + "pop_ca_2016.dat";
				if (202003 != AppCore.getFileVersion(AppCore.makeFilePath(fileName))) {
					break;
				}

				fileName = AppCore.DATA_DIRECTORY_NAME + File.separator + "pop_mx_2010.dat";
				if (202003 != AppCore.getFileVersion(AppCore.makeFilePath(fileName))) {
					break;
				}

				fileName = AppCore.DATA_DIRECTORY_NAME + File.separator + "country_poly.dat";
				if (20200401 != AppCore.getFileVersion(AppCore.makeFilePath(fileName))) {
					break;
				}

				fileName = null;
				result = true;
				break;
			}

			if ((null != fileName) && (null != errors)) {
				errors.reportError("File check failed, '" + fileName + "' is the wrong version.\n");
			}

		} catch (FileNotFoundException fe) {
			if (null != errors) {
				errors.reportError("File check failed, '" + fileName + "' not found.\n");
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update root data table content in a database.  This is usually only used during development and debugging.  The
	// version must be current, that is verified by checking canOpen in the info object.

	public static boolean updateRootData(DbInfo dbInfo, ErrorLogger errors) {

		if (!dbInfo.canOpen) {
			return false;
		}

		DbConnection db = dbInfo.db;
		String errmsg = null;

		if (db.connect(dbInfo.dbName, errors)) {
			try {

				// Lock tables, check version number, don't do the reload if any studies are currently locked.
				// Temporarily set the version number to 0 as in an update.

				db.update("LOCK TABLES study WRITE, version WRITE");

				db.query("SELECT version FROM version");
				db.next();
				int theVersion = db.getInt(1);

				if (0 != theVersion) {

					db.query("SELECT COUNT(*) FROM study WHERE study_lock <> " + Study.LOCK_NONE);
					db.next();
					if (0 == db.getInt(1)) {

						db.update("UPDATE version SET version = 0");
						db.update("UNLOCK TABLES");

						doUpdateRootData(db);

						db.update("UPDATE version SET version = " + theVersion);

					} else {
						errmsg = "The data cannot be reloaded, studies are currently in use.";
					}

				} else {
					errmsg = "Another application is currently updating the database.";
				}

			} catch (SQLException se) {
				errmsg = "An error occurred while reloading the data, the database may have to\n" +
					"be repaired manually or uninstalled and re-installed.  The error was:\n" + se;
				db.reportError(se);
			}

			try {
				db.update("UNLOCK TABLES");
			} catch (SQLException se) {
				db.reportError(se);
			}

			dbInfo.update();
			db.close(false);
		}

		if (null != errmsg) {
			if (null != errors) {
				errors.reportError(errmsg);
			}
			return false;
		}
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Uninstall a database.

	public static boolean uninstallDb(DbInfo dbInfo, ErrorLogger errors) {

		if (!dbInfo.canUninstall) {
			return false;
		}

		DbConnection db = dbInfo.db;
		boolean result = true;

		if (db.connect(errors)) {
			try {

				ArrayList<String> dropList = new ArrayList<String>();

				// Delete all study and station data databases.  Note the root database name is not allowed to contain
				// a '_' (or any other punctuation or whitespace) so this cannot inadvertently delete databases for a
				// different root even if the names share a common prefix.

				db.query("SHOW DATABASES LIKE '" + dbInfo.dbName + "\\_%'");
				while (db.next()) {
					dropList.add(db.getString(1));
				}

				for (String drop : dropList) {
					db.update("DROP DATABASE " + drop);
				}

				db.update("DROP DATABASE IF EXISTS " + dbInfo.dbName);

			} catch (SQLException se) {
				result = false;
				if (null != errors) {
					errors.reportError(DbConnection.ERROR_TEXT_PREFIX + se);
				}
				db.reportError(se);
			}

			dbInfo.update();

			db.close(false);
		}

		AppCore.deleteStudyCache(dbInfo.dbID, 0);

		return result;
	}
	
	private static String GetMySQLFilePath(String filename)
	{
		return "LOAD DATA LOCAL INFILE '" + (AppCore.dataDirectoryPath).replaceAll("\\\\","\\\\\\\\") + "\\\\" +
			filename+".dat' INTO TABLE "+filename+" FIELDS TERMINATED BY '|' LINES TERMINATED BY '\\n' " +
			"IGNORE 1 LINES";				
	}
				
	//-----------------------------------------------------------------------------------------------------------------
	// Do a new installation.  Start by creating the root database and version table, set the version number to 0
	// which will cause other attempts to access this host to recognize that the database is still being installed.
	// That mechanism is also used during updates.  Also generate a random UUID for this database and save it in the
	// version table and application properties, see DbInfo.

	private static void doInstallDb(DbInfo theInfo, StatusReporter status) throws SQLException {

		DbConnection db = theInfo.db;

		db.update("CREATE DATABASE " + theInfo.dbName);
		db.setDatabase(theInfo.dbName);

		theInfo.dbID = UUID.randomUUID().toString();

		db.update("CREATE TABLE version (" +
			"version INT NOT NULL," +
			"uuid VARCHAR(255) NOT NULL)");
		db.update("INSERT INTO version VALUES (0, '" + db.clean(theInfo.dbID) + "')");

		if (theInfo.dbName.equals(DEFAULT_DB_NAME)) {
			AppCore.setProperty(DB_ID_KEY_PREFIX + theInfo.dbHostname, theInfo.dbID);
		} else {
			AppCore.setProperty(DB_ID_KEY_PREFIX + theInfo.dbHostname + "_" + theInfo.dbName, theInfo.dbID);
		}

		// Remove any lingering study and station databases in case this host did have an installation in the past.

		ArrayList<String> toDrop = new ArrayList<String>();
		db.query("SHOW DATABASES LIKE '" + theInfo.dbName + "\\_%'");
		while (db.next()) {
			toDrop.add(db.getString(1));
		}
		for (String drop : toDrop) {
			db.update("DROP DATABASE " + drop);
		}

		// First create the study index table.

		db.update("CREATE TABLE study (" +
			"study_key INT NOT NULL PRIMARY KEY," +
			"name VARCHAR(255) NOT NULL," +
			"description VARCHAR(10000) NOT NULL," +
			"study_lock INT NOT NULL," +
			"lock_count INT NOT NULL," +
			"share_count INT NOT NULL," +
			"study_type INT NOT NULL," +
			"study_mode INT NOT NULL," +
			"needs_update BOOLEAN NOT NULL," +
			"mod_count INT NOT NULL," +
			"template_key INT NOT NULL," +
			"ext_db_key INT NOT NULL," +
			"point_set_key INT NOT NULL," +
			"propagation_model INT NOT NULL," +
			"study_area_mode INT NOT NULL," +
			"study_area_geo_key INT NOT NULL," +
			"output_config_file_name VARCHAR(255) NOT NULL," +
			"output_config_file_codes VARCHAR(255) NOT NULL," +
			"output_config_map_name VARCHAR(255) NOT NULL," +
			"output_config_map_codes VARCHAR(255) NOT NULL," +
			"report_preamble MEDIUMTEXT NOT NULL," +
			"parameter_summary MEDIUMTEXT NOT NULL," +
			"ix_rule_summary MEDIUMTEXT NOT NULL)");

		// A one-row table used to simulate a sequence for generating primary keys.  The process of creating various
		// new objects including studies, station data sets, and templates requires obtaining a new primary key before
		// the index table row is inserted (generally because the key may be needed outside a LOCK TABLES context to
		// create other new databases) so MySQL's auto-increment behavior is inadequate.

		db.update("CREATE TABLE study_key_sequence (" +
			"study_key INT NOT NULL)");
		db.update("INSERT INTO study_key_sequence VALUES (0)");

		// The external station data index table, and key sequence table.

		db.update("CREATE TABLE ext_db (" +
			"ext_db_key INT NOT NULL," +
			"db_type INT NOT NULL," +
			"db_date DATETIME NOT NULL," +
			"version INT NOT NULL," +
			"id VARCHAR(255) NOT NULL," +
			"name VARCHAR(255) NOT NULL," +
			"deleted BOOLEAN NOT NULL," +
			"locked BOOLEAN NOT NULL," +
			"is_download BOOLEAN NOT NULL)");

		db.update("CREATE TABLE ext_db_key_sequence (" +
			"ext_db_key INT NOT NULL)");
		db.update("INSERT INTO ext_db_key_sequence VALUES (0)");

		// Next are enumeration tables for various minor properties appearing in the UI.  Some of these also define
		// primary keys that shadow constants used in the application code.  In the latter case, the primary keys here
		// must match the constants defined in tvstudy.h for the study engine C code, and various data classes for the
		// Java UI code.  Countries are first, these primary keys are used in both engine and UI code.  Note for this
		// and all other root tables, the data is inserted by doUpdateRootData(); that method removes existing data
		// and inserts new.  It is called here during installation, and may also be called after an update any time
		// there have been changes to the data.  Conceptually that data is part of the database structure and the
		// version number applies to the state of that data as well as the schema.

		db.update("CREATE TABLE country (" +
			"country_key INT NOT NULL PRIMARY KEY," +
			"name VARCHAR(255) NOT NULL," +
			"country_code CHAR(2) NOT NULL)");

		// Service types.  These are generic categories that govern study engine behavior, see the services table below
		// for more specific categories.  The keys here are used in the engine code.  The record type identifies the
		// type of record for the service, TV, wireless, or FM; the values are defined in Source.java.

		db.update("CREATE TABLE service_type (" +
			"service_type_key INT NOT NULL PRIMARY KEY," +
			"record_type INT NOT NULL," +
			"name VARCHAR(255) NOT NULL," +
			"digital BOOLEAN NOT NULL," +
			"needs_emission_mask BOOLEAN NOT NULL)");

		// Signal type, applies only for a digital service type, used to match interference rules.

		db.update("CREATE TABLE signal_type (" +
			"signal_type_key INT NOT NULL PRIMARY KEY," +
			"name VARCHAR(255) NOT NULL," +
			"is_default BOOLEAN NOT NULL)");

		// Services.  The primary keys are used in UI code, and the character codes are used for XML import/export,
		// but generally these are informational categories.  The only thing that matters to the study engine is the
		// service type key so primarily these exist to map conceptual services to those service types.  Also create
		// tables that map service codes from CDBS and LMS databases to the internal services.

		db.update("CREATE TABLE service (" +
			"service_key INT NOT NULL PRIMARY KEY," +
			"name VARCHAR(255) NOT NULL," +
			"service_code CHAR(2) NOT NULL," +
			"service_type_key INT NOT NULL," +
			"is_dts BOOLEAN NOT NULL," +
			"is_operating BOOLEAN NOT NULL," +
			"preference_rank INT NOT NULL," +
			"digital_service_key INT NOT NULL)");

		db.update("CREATE TABLE cdbs_service (" +
			"cdbs_service_code CHAR(2) NOT NULL," +
			"service_key INT NOT NULL)");

		db.update("CREATE TABLE lms_service (" +
			"lms_service_code CHAR(6) NOT NULL," +
			"service_key INT NOT NULL)");

		// Channel bands for the interference rule UI (these apply only to TV rules).  The keys are not used in code,
		// the study engine has it's own internal enumeration with more ranges used for determining service contour
		// levels.  When interpreting rules, the engine only looks at the actual channel ranges from these records.

		db.update("CREATE TABLE channel_band (" +
			"channel_band_key INT NOT NULL PRIMARY KEY," +
			"name VARCHAR(255) NOT NULL," +
			"first_channel INT NOT NULL," +
			"last_channel INT NOT NULL)");

		// Channel deltas for the interference rule UI, when interpreting rules the study engine looks at the actual
		// channel delta values.  There is a separate set of records for different record types, meaning TV and FM
		// since the rules system is not use for wireless records.  The analog_only flag refers to the desired station
		// in a TV rule; if analog_only is true, the delta cannot be used on a rule for a digital desired station.
		// Primary keys are not used in code.

		db.update("CREATE TABLE channel_delta (" +
			"channel_delta_key INT NOT NULL PRIMARY KEY," +
			"record_type INT NOT NULL," +
			"name VARCHAR(255) NOT NULL," +
			"delta INT NOT NULL," +
			"analog_only BOOLEAN NOT NULL)");

		// LPTV/Class A digital emission masks.  Primary keys are used in the study engine code.  The UI application
		// uses the character codes to match data from external station data records.

		db.update("CREATE TABLE emission_mask (" +
			"emission_mask_key INT NOT NULL PRIMARY KEY," +
			"name VARCHAR(255) NOT NULL," +
			"emission_mask_code CHAR(1) NOT NULL," +
			"is_default BOOLEAN NOT NULL)");

		// TV frequency offsets.  Keys used in the engine, codes used to match station data records.

		db.update("CREATE TABLE frequency_offset (" +
			"frequency_offset_key INT NOT NULL PRIMARY KEY," +
			"name VARCHAR(255) NOT NULL," +
			"offset_code CHAR(1) NOT NULL)");

		// Zones for TV.  Keys used in the engine, codes used to match station data.

		db.update("CREATE TABLE zone (" +
			"zone_key INT NOT NULL PRIMARY KEY," +
			"name VARCHAR(255) NOT NULL," +
			"zone_code CHAR(1) NOT NULL)");

		// Table to index study templates providing presets for parameters and interference rules.  This defines one
		// default template that will always be present, a set of interference rules for that default template are
		// added in the data update method, parameter values are set as the parameter table is populated.  See
		// comments in Template regarding the various flags.

		db.update("CREATE TABLE template (" +
			"template_key INT NOT NULL PRIMARY KEY," +
			"name VARCHAR(255) NOT NULL," +
			"permanent BOOLEAN NOT NULL," +
			"locked BOOLEAN NOT NULL," +
			"locked_in_study BOOLEAN NOT NULL)");

		db.update("INSERT INTO template VALUES (1, \"Default\", true, true, false)");

		// Key sequence table for templates.

		db.update("CREATE TABLE template_key_sequence (" +
			"template_key INT NOT NULL)");
		db.update("INSERT INTO template_key_sequence VALUES (1)");

		// Data tables to store parameter values and interference rules for templates.

		db.update("CREATE TABLE template_parameter_data (" +
			"template_key INT NOT NULL," +
			"parameter_key INT NOT NULL," +
			"value_index INT NOT NULL," +
			"value VARCHAR(10000) NOT NULL," +
			"PRIMARY KEY (template_key, parameter_key, value_index))");

		db.update("CREATE TABLE template_ix_rule (" +
			"template_key INT NOT NULL," +
			"ix_rule_key INT NOT NULL," +
			"country_key INT NOT NULL," +
			"service_type_key INT NOT NULL," +
			"signal_type_key INT NOT NULL," +
			"undesired_service_type_key INT NOT NULL," +
			"undesired_signal_type_key INT NOT NULL," +
			"channel_delta_key INT NOT NULL," +
			"channel_band_key INT NOT NULL," +
			"frequency_offset INT NOT NULL," +
			"emission_mask_key INT NOT NULL," +
			"distance FLOAT NOT NULL," +
			"required_du FLOAT NOT NULL," +
			"undesired_time FLOAT NOT NULL," +
			"PRIMARY KEY (template_key, ix_rule_key))");

		// Parameter grouping for the UI.  Ordering is always by group list_order then by parameter list_order, so
		// list_order in the parameter table does not have to be unique and the ordering by that value alone is not
		// significant.  If the enabling_parameter_key is >0 it points to an option parameter in the same group, the
		// setting of that parameter will enable/disable all of the other parameters that follow it in the same group.

		db.update("CREATE TABLE parameter_group (" +
			"group_key INT NOT NULL PRIMARY KEY," +
			"list_order INT NOT NULL," +
			"name VARCHAR(255) NOT NULL," +
			"enabling_parameter_key INT NOT NULL)");

		// Table defining study parameters.  The parameter values for a study are stored in a parameter data table in
		// the study database, but the name, type, and description are always obtained from this table.  Parameters
		// can never be added or deleted to this list through the UI; such changes will only occur in conjunction with
		// application code changes and will be handled by an update method.  The keys and data values for these are
		// used extensively in both the study engine code and the UI code, so this has to match constants defined in
		// the source, see tvstudy.h and Parameter.java.  Default values are set in the default template, that template
		// must always have a value for all parameters.  A parameter may have multiple values, typically that ability
		// is used to assign different values to a parameter by country, but it is a generalized ability so the
		// value enumeration can define the indices of a multi-value parameter in any way desired.  If values is an
		// empty string the parameter has only a single value, else that provides a colon-separated list of value
		// names for the UI, also implicitly defining the count of values and the indices by sequence.  All parameters
		// do not necessarily appear for all study types.  The parameter_study_type table maps parameter keys to study
		// types, only the parameters in that map will appear for a given study type.  If the is_scenario_parameter
		// flag is true the parameter value varies for each scenario within a study, the values are stored in a
		// different table in the study database.

		db.update("CREATE TABLE parameter (" +
			"parameter_key INT NOT NULL PRIMARY KEY," +
			"group_key INT NOT NULL," +
			"list_order INT NOT NULL," +
			"name VARCHAR(255) NOT NULL," +
			"type VARCHAR(255) NOT NULL," +
			"units VARCHAR(255) NOT NULL," +
			"value_list VARCHAR(255) NOT NULL," +
			"description VARCHAR(10000) NOT NULL," +
			"is_scenario_parameter BOOLEAN NOT NULL)");

		db.update("CREATE TABLE parameter_study_type (" +
			"parameter_key INT NOT NULL," +
			"study_type INT NOT NULL," +
			"UNIQUE (parameter_key, study_type))");

		// Apply all the data to the tables just created.

		doUpdateRootData(db);

		// Tables to index and store geographies.

		db.update("CREATE TABLE geography (" +
			"geo_key INT NOT NULL PRIMARY KEY," +
			"study_key INT NOT NULL," +
			"source_key INT NOT NULL," +
			"geo_type INT NOT NULL," +
			"name VARCHAR(255) NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL," +
			"radius DOUBLE NOT NULL," +
			"width DOUBLE NOT NULL," +
			"height DOUBLE NOT NULL," +
			"mod_count INT NOT NULL)");

		db.update("CREATE TABLE geo_key_sequence (" +
			"geo_key INT NOT NULL)");
		db.update("INSERT INTO geo_key_sequence VALUES (0)");

		db.update("CREATE TABLE geo_point_set (" +
			"geo_key INT NOT NULL," +
			"point_name VARCHAR(255) NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL," +
			"receive_height DOUBLE NOT NULL," +
			"antenna_key INT NOT NULL," +
			"antenna_orientation DOUBLE NOT NULL)");

		db.update("CREATE TABLE geo_polygon (" +
			"geo_key INT NOT NULL," +
			"vertex_key INT NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL)");

		db.update("CREATE TABLE geo_sectors (" +
			"geo_key INT NOT NULL," +
			"azimuth DOUBLE NOT NULL," +
			"radius DOUBLE NOT NULL)");

		// Table listing geographies in use by studies, so geography editor can check easily before deleting.

		db.update("CREATE TABLE study_geography (" +
			"study_key INT NOT NULL," +
			"geo_key INT NOT NULL," +
			"UNIQUE (study_key, geo_key))");

		// Custom receive antenna data.

		db.update("CREATE TABLE receive_antenna_index (" +
			"antenna_key INT NOT NULL," +
			"name VARCHAR(255) NOT NULL," +
			"gain DOUBLE NOT NULL)");

		db.update("CREATE TABLE antenna_key_sequence (" +
			"antenna_key INT NOT NULL)");
		db.update("INSERT INTO antenna_key_sequence VALUES (0)");

		db.update("CREATE TABLE receive_pattern (" +
			"antenna_key INT NOT NULL," +
			"frequency DOUBLE NOT NULL," +
			"azimuth DOUBLE NOT NULL," +
			"relative_field DOUBLE NOT NULL)");

		db.update("CREATE TABLE geography_receive_antenna (" +
			"geo_key INT NOT NULL," +
			"antenna_key INT NOT NULL," +
			"UNIQUE (geo_key, antenna_key))");

		// Edit lock point for geography and receive antenna editors.

		db.update("CREATE TABLE edit_lock (locked BOOLEAN NOT NULL)");
		db.update("INSERT INTO edit_lock VALUES (false)");

		// Tables for image color maps, and initial data.  Each key corresponds to a particular image file output
		// option.  These can be edited in place but maps are never created/deleted by user action.  The defaults
		// tables are never changed, those just support a reset-to-defaults action in the editor.

		db.update("CREATE TABLE color_map (" +
			"color_map_key INT PRIMARY KEY," +
			"bg_color_r INT NOT NULL," +
			"bg_color_g INT NOT NULL," +
			"bg_color_b INT NOT NULL)");

		db.update("CREATE TABLE color_map_data (" +
			"color_map_key INT NOT NULL," +
			"level DOUBLE NOT NULL," +
			"color_r INT NOT NULL," +
			"color_g INT NOT NULL," +
			"color_b INT NOT NULL)");

		db.update("INSERT INTO color_map VALUES " +
			"(1, 0, 80, 180)," +
			"(2, 0, 80, 180)," +
			"(3, 0, 80, 180)," +
			"(4, 0, 80, 180)," +
			"(5, 0, 80, 180)," +
			"(6, 160, 0, 0)");

		db.update("INSERT INTO color_map_data VALUES " +
			"(1, 0, 180, 0, 0)," +
			"(1, 10, 180, 100, 0)," +
			"(1, 20, 140, 180, 0)," +
			"(1, 30, 40, 180, 0)," +
			"(1, 40, 0, 180, 70)," +
			"(2, -6, 180, 0, 0)," +
			"(2, -3, 180, 100, 0)," +
			"(2, 0, 140, 180, 0)," +
			"(2, 3, 40, 180, 0)," +
			"(2, 6, 0, 180, 70)," +
			"(3, -6, 180, 0, 0)," +
			"(3, -3, 180, 100, 0)," +
			"(3, 0, 140, 180, 0)," +
			"(3, 3, 40, 180, 0)," +
			"(3, 6, 0, 180, 70)," +
			"(4, -6, 180, 0, 0)," +
			"(4, -3, 180, 100, 0)," +
			"(4, 0, 140, 180, 0)," +
			"(4, 3, 40, 180, 0)," +
			"(4, 6, 0, 180, 68)," +
			"(5, -6, 180, 0, 0)," +
			"(5, -3, 180, 100, 0)," +
			"(5, 0, 140, 180, 0)," +
			"(5, 3, 40, 180, 0)," +
			"(5, 6, 0, 180, 70)," +
			"(6, -998, 0, 80, 180)," +
			"(6, 0, 220, 100, 100)," +
			"(6, 10, 220, 120, 0)," +
			"(6, 20, 140, 180, 0)," +
			"(6, 30, 40, 180, 0)," +
			"(6, 40, 0, 180, 70)");

		db.update("CREATE TABLE color_map_default (" +
			"color_map_key INT PRIMARY KEY," +
			"bg_color_r INT NOT NULL," +
			"bg_color_g INT NOT NULL," +
			"bg_color_b INT NOT NULL)");

		db.update("CREATE TABLE color_map_data_default (" +
			"color_map_key INT NOT NULL," +
			"level DOUBLE NOT NULL," +
			"color_r INT NOT NULL," +
			"color_g INT NOT NULL," +
			"color_b INT NOT NULL)");

		db.update("INSERT INTO color_map_default VALUES " +
			"(1, 0, 80, 180)," +
			"(2, 0, 80, 180)," +
			"(3, 0, 80, 180)," +
			"(4, 0, 80, 180)," +
			"(5, 0, 80, 180)," +
			"(6, 160, 0, 0)");

		db.update("INSERT INTO color_map_data_default VALUES " +
			"(1, 0, 180, 0, 0)," +
			"(1, 10, 180, 100, 0)," +
			"(1, 20, 140, 180, 0)," +
			"(1, 30, 40, 180, 0)," +
			"(1, 40, 0, 180, 70)," +
			"(2, -6, 180, 0, 0)," +
			"(2, -3, 180, 100, 0)," +
			"(2, 0, 140, 180, 0)," +
			"(2, 3, 40, 180, 0)," +
			"(2, 6, 0, 180, 70)," +
			"(3, -6, 180, 0, 0)," +
			"(3, -3, 180, 100, 0)," +
			"(3, 0, 140, 180, 0)," +
			"(3, 3, 40, 180, 0)," +
			"(3, 6, 0, 180, 70)," +
			"(4, -6, 180, 0, 0)," +
			"(4, -3, 180, 100, 0)," +
			"(4, 0, 140, 180, 0)," +
			"(4, 3, 40, 180, 0)," +
			"(4, 6, 0, 180, 68)," +
			"(5, -6, 180, 0, 0)," +
			"(5, -3, 180, 100, 0)," +
			"(5, 0, 140, 180, 0)," +
			"(5, 3, 40, 180, 0)," +
			"(5, 6, 0, 180, 70)," +
			"(6, -998, 0, 80, 180)," +
			"(6, 0, 220, 100, 100)," +
			"(6, 10, 220, 120, 0)," +
			"(6, 20, 140, 180, 0)," +
			"(6, 30, 40, 180, 0)," +
			"(6, 40, 0, 180, 70)");

		// Table for user-created record storage outside study context, source data is stored as XML but some fields
		// also exist as separate properties for searching.

		db.update("CREATE TABLE user_record (" +
			"user_record_id INT PRIMARY KEY," +
			"record_type INT NOT NULL," +
			"xml_data MEDIUMTEXT NOT NULL," +
			"facility_id INT NOT NULL," +
			"service_key INT NOT NULL," +
			"call_sign CHAR(12) NOT NULL," +
			"status CHAR(6) NOT NULL," +
			"channel INT NOT NULL," +
			"city CHAR(20) NOT NULL," +
			"state CHAR(2) NOT NULL," +
			"country CHAR(2) NOT NULL," +
			"file_number VARCHAR(255) NOT NULL," +
			"comment VARCHAR(10000) NOT NULL)");

		db.update("CREATE TABLE user_record_id_sequence (" +
			"user_record_id INT NOT NULL)");
		db.update("INSERT INTO user_record_id_sequence VALUES (0)");

		// Tables for interference-check study status.

		db.update("CREATE TABLE ix_check_status (" +
			"study_name VARCHAR(255) NOT NULL," +
			"study_id VARCHAR(10000) NOT NULL," +
			"run_date DATETIME NOT NULL)");

		db.update("CREATE TABLE ix_check_name_sequence (" +
			"name_key INT NOT NULL)");
		db.update("INSERT INTO ix_check_name_sequence VALUES (0)");

		// Table for output file configuration.

		db.update("CREATE TABLE output_config (" +
			"type INT NOT NULL," +
			"name VARCHAR(255) NOT NULL," +
			"codes VARCHAR(255) NOT NULL)");

		// Table for saved scenario-building searches.

		db.update("CREATE TABLE ext_db_search (" +
			"study_type INT NOT NULL," +
			"name VARCHAR(255) NOT NULL," +
			"search_type INT NOT NULL DEFAULT " + ExtDbSearch.SEARCH_TYPE_DESIREDS + "," +
			"disable_mx BOOLEAN NOT NULL," +
			"prefer_operating BOOLEAN NOT NULL," +
			"desired_only BOOLEAN NOT NULL," +
			"service_keys VARCHAR(255) NOT NULL," +
			"country_keys VARCHAR(255) NOT NULL," +
			"status_types VARCHAR(255) NOT NULL," +
			"radius DOUBLE NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL," +
			"minimum_channel INT NOT NULL DEFAULT 0," +
			"maximum_channel INT NOT NULL DEFAULT 0," +
			"additional_sql VARCHAR(20000) NOT NULL," +
			"auto_run BOOLEAN NOT NULL)");

		// Table to track auto-imported template or geography XML files.  Files are stored in the xml/ directory, that
		// is scanned when a database is opened and new or modified files are imported automatically, see openDb().

		db.update("CREATE TABLE xml_import (" +
			"file_name VARCHAR(255) NOT NULL, " +
			"mod_time BIGINT NOT NULL, " +
			"length BIGINT NOT NULL)");

		// Import population data.  Queries are always on ranges of the latitude and longitude index values.  Note all
		// of the table data files have a version tag line at the top which is ignored, see checkDataFiles().

		if (null != status) {
			status.setWaitMessage("Loading U.S. 2000 Census data, please wait...");
		}

		db.update("CREATE TABLE pop_us_2000 (" +
			"id VARCHAR(255) NOT NULL," +
			"duplicate BOOLEAN NOT NULL," +
			"lat_index INT NOT NULL," +
			"lon_index INT NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL," +
			"population INT NOT NULL," +
			"households INT NOT NULL," +
			"INDEX (lat_index)," +
			"INDEX (lon_index))");

		db.update(GetMySQLFilePath("pop_us_2000"));

		if (null != status) {
			status.setWaitMessage("Loading U.S. 2010 Census data, please wait...");
		}

		db.update("CREATE TABLE pop_us_2010 (" +
			"id VARCHAR(255) NOT NULL," +
			"duplicate BOOLEAN NOT NULL," +
			"lat_index INT NOT NULL," +
			"lon_index INT NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL," +
			"population INT NOT NULL," +
			"households INT NOT NULL," +
			"INDEX (lat_index)," +
			"INDEX (lon_index))");

		db.update(GetMySQLFilePath("pop_us_2010"));

		if (null != status) {
			status.setWaitMessage("Loading Canadian Census data, please wait...");
		}

		db.update("CREATE TABLE pop_ca_2006 (" +
			"id VARCHAR(255) NOT NULL," +
			"duplicate BOOLEAN NOT NULL," +
			"lat_index INT NOT NULL," +
			"lon_index INT NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL," +
			"population INT NOT NULL," +
			"households INT NOT NULL," +
			"INDEX (lat_index)," +
			"INDEX (lon_index))");

		db.update(GetMySQLFilePath("pop_ca_2006"));

		db.update("CREATE TABLE pop_ca_2011 (" +
			"id VARCHAR(255) NOT NULL," +
			"duplicate BOOLEAN NOT NULL," +
			"lat_index INT NOT NULL," +
			"lon_index INT NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL," +
			"population INT NOT NULL," +
			"households INT NOT NULL," +
			"INDEX (lat_index)," +
			"INDEX (lon_index))");

		db.update(GetMySQLFilePath("pop_ca_2011"));

		db.update("CREATE TABLE pop_ca_2016 (" +
			"id VARCHAR(255) NOT NULL," +
			"duplicate BOOLEAN NOT NULL," +
			"lat_index INT NOT NULL," +
			"lon_index INT NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL," +
			"population INT NOT NULL," +
			"households INT NOT NULL," +
			"INDEX (lat_index)," +
			"INDEX (lon_index))");
		db.update(GetMySQLFilePath("pop_ca_2016"));

		if (null != status) {
			status.setWaitMessage("Loading Mexican Census data, please wait...");
		}

		db.update("CREATE TABLE pop_mx_2010 (" +
			"id VARCHAR(255) NOT NULL," +
			"duplicate BOOLEAN NOT NULL," +
			"lat_index INT NOT NULL," +
			"lon_index INT NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL," +
			"population INT NOT NULL," +
			"households INT NOT NULL," +
			"INDEX (lat_index)," +
			"INDEX (lon_index))");
		db.update(GetMySQLFilePath("pop_mx_2010"));

		// Polygon data for determining country of arbitrary locations.  This is loaded entirely into memory by the
		// study engine on startup, poly_seq and vertex_seq are ordering values, they have no identifying significance
		// beyond the association with country_key here.  Individual polygons are explicitly closed.

		db.update("CREATE TABLE country_poly (" +
			"poly_seq INT NOT NULL," +
			"vertex_seq INT NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL," +
			"country_key INT NOT NULL," +
			"is_border BOOLEAN NOT NULL," +
			"PRIMARY KEY (poly_seq, vertex_seq))");
		db.update(GetMySQLFilePath("country_poly"));

		// Property store used by the UI application.

		db.update("CREATE TABLE application_property (" +
			"name VARCHAR(255) NOT NULL," +
			"value VARCHAR(255) NOT NULL)");

		// Finally, set the version number to current; the database is ready for use.

		db.update("UPDATE version SET version = " + DATABASE_VERSION);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Insert all data into root database tables, first removing any existing data.  This is used during installation
	// and also to refresh data during updates, after the update methods make any table structure changes.  Note in
	// the template parameter and rule data only the default template is updated, other existing templates are
	// unchanged.  Parameters will live-update in existing templates when loaded, see Parameter.java.

	private static void doUpdateRootData(DbConnection db) throws SQLException {

		// Countries.

		db.update("DELETE FROM country");

		db.update("INSERT INTO country VALUES " +
			"(1, 'U.S.', 'US')," +
			"(2, 'Canada', 'CA')," +
			"(3, 'Mexico', 'MX')");

		// Service types.

		db.update("DELETE FROM service_type");

		db.update("INSERT INTO service_type VALUES " +
			"(1, 1, 'TV Full Service digital', true, false)," +
			"(2, 1, 'TV Full Service analog', false, false)," +
			"(3, 1, 'TV Class A digital', true, true)," +
			"(4, 1, 'TV Class A analog', false, false)," +
			"(5, 1, 'TV Low Power digital', true, true)," +
			"(6, 1, 'TV Low Power analog', false, false)," +
			"(11, 2, 'Wireless', true, false)," +
			"(21, 3, 'FM Full Service', false, false)," +
			"(22, 3, 'FM Low Power', false, false)," +
			"(23, 3, 'FM Translator/Booster', false, false)");

		// Signal types.  Special behavior here, first check existing table contents, if it is already populated don't
		// do anything.  If empty this is a new installation, insert just one supported type, ATSC.  When this table
		// has only one row, all UI related to signal type is hidden.  However other types may be added manually to
		// activate that for testing and development; this behavior means those won't be disturbed by updates.

		db.query("SELECT COUNT(*) FROM signal_type");
		if (!db.next() || (0 == db.getInt(1))) {
			db.update("INSERT INTO signal_type VALUES " +
				"(1, 'ATSC', true)");
		}

		// Services and mappings for CDBS and LMS data.

		db.update("DELETE FROM service");
		db.update("DELETE FROM cdbs_service");
		db.update("DELETE FROM lms_service");

		db.update("INSERT INTO service VALUES " +
			"(1, 'TV digital', 'DT', 1, false, true, 80, 0)," +
			"(2, 'TV digital DTS', 'DD', 1, true, true, 90, 0)," +
			"(3, 'TV Class A digital', 'DC', 3, false, true, 70, 0)," +
			"(4, 'TV Low Power/Translator digital', 'LD', 5, false, true, 60, 0)," +
			"(5, 'TV analog', 'TV', 2, false, true, 50, 1)," +
			"(6, 'TV Class A analog', 'CA', 4, false, true, 40, 3)," +
			"(7, 'TV Low Power/Translator analog', 'TX', 6, false, true, 30, 4)," +
			"(8, 'TV Aux/Rulemaking digital', 'DX', 1, false, false, 20, 0)," +
			"(9, 'TV Aux/Rulemaking analog', 'TS', 2, false, false, 10, 8)," +
			"(21, 'Wireless', 'WL', 11, false, true, 10, 0)," +
			"(31, 'FM', 'FM', 21, false, true, 60, 42)," +
			"(32, 'FM Low Power', 'FL', 22, false, true, 40, 0)," +
			"(33, 'FM Translator', 'FX', 23, false, true, 30, 0)," +
			"(34, 'FM Booster', 'FB', 23, false, true, 20, 0)," +
			"(35, 'FM Aux/Allot/Rulemaking', 'FS', 21, false, false, 10, 0)");

		db.update("INSERT INTO cdbs_service VALUES " +
			"('DT', 1)," +
			"('DD', 2)," +
			"('DC', 3)," +
			"('LD', 4)," +
			"('DS', 1)," +
			"('TV', 5)," +
			"('CA', 6)," +
			"('TX', 7)," +
			"('TB', 7)," +
			"('DX', 8)," +
			"('DR', 1)," +
			"('DM', 8)," +
			"('DN', 8)," +
			"('TS', 9)," +
			"('TA', 9)," +
			"('TR', 9)," +
			"('NM', 9)," +
			"('NN', 9)," +
			"('FM', 31)," +
			"('FL', 32)," +
			"('FX', 33)," +
			"('FB', 34)," +
			"('FS', 35)," +
			"('FR', 35)," +
			"('FA', 35)");

		db.update("INSERT INTO lms_service VALUES " +
			"('DTV', 1)," +
			"('DTS', 2)," +
			"('DCA', 3)," +
			"('LPD', 4)," +
			"('LPT', 4)," +
			"('DRT', 4)," +
			"('TV', 5)," +
			"('ACA', 6)," +
			"('LPA', 7)," +
			"('LPX', 7)," +
			"('DTX', 8)," +
			"('TS', 9)");

		// TV channel bands.

		db.update("DELETE FROM channel_band");

		db.update("INSERT INTO channel_band VALUES " +
			"(1, 'VHF', 2, 13)," +
			"(2, 'UHF', 14, 51)");

		// Channel deltas for TV and FM.

		db.update("DELETE FROM channel_delta");

		db.update("INSERT INTO channel_delta VALUES " +
			"(1, 1, '8 below', -8, true)," +
			"(2, 1, '7 below', -7, true)," +
			"(3, 1, '4 below', -4, true)," +
			"(4, 1, '3 below', -3, true)," +
			"(5, 1, '2 below', -2, true)," +
			"(6, 1, '1 below', -1, false)," +
			"(7, 1, 'co-channel', 0, false)," +
			"(8, 1, '1 above', 1, false)," +
			"(9, 1, '2 above', 2, true)," +
			"(10, 1, '3 above', 3, true)," +
			"(11, 1, '4 above', 4, true)," +
			"(12, 1, '7 above', 7, true)," +
			"(13, 1, '8 above', 8, true)," +
			"(14, 1, '14 above', 14, true)," +
			"(15, 1, '15 above', 15, true)," +
			"(19, 3, '54 below', -54, false)," +
			"(20, 3, '53 below', -53, false)," +
			"(21, 3, '3 below', -3, false)," +
			"(22, 3, '2 below', -2, false)," +
			"(23, 3, '1 below', -1, false)," +
			"(24, 3, 'co-channel', 0, false)," +
			"(25, 3, '1 above', 1, false)," +
			"(26, 3, '2 above', 2, false)," +
			"(27, 3, '3 above', 3, false)," +
			"(28, 3, '53 above', 53, false)," +
			"(29, 3, '54 above', 54, false)");

		// TV emission masks.

		db.update("DELETE FROM emission_mask");

		db.update("INSERT INTO emission_mask VALUES" +
			"(1, 'Simple', 'S', true)," +
			"(2, 'Stringent', 'T', false)," +
			"(3, 'Full Service', 'F', false)");

		// TV frequency offsets.

		db.update("DELETE FROM frequency_offset");

		db.update("INSERT INTO frequency_offset VALUES" +
			"(1, 'Zero', 'z')," +
			"(2, 'Plus', '+')," +
			"(3, 'Minus', '-')");

		// TV zones.

		db.update("DELETE FROM zone");

		db.update("INSERT INTO zone VALUES" +
			"(1, 'I', '1')," +
			"(2, 'II', '2')," +
			"(3, 'III', '3')");

		// Interference rules in the default template.  

		db.update("DELETE FROM template_ix_rule WHERE template_key = 1");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// Protection of U.S. full-service NTSC from ...

		// ... full-service NTSC.  From OET-69.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1,   1, 1, 2, 0, 2, 0,  7, 0, 2, 0, 300.,  28., 10)," +
			"(1,   2, 1, 2, 0, 2, 0,  7, 0, 1, 0, 300.,  45., 10)," +
			"(1,   3, 1, 2, 0, 2, 0,  8, 0, 0, 0, 100., -13., 10)," +
			"(1,   4, 1, 2, 0, 2, 0,  9, 2, 0, 0,  35., -29., 10)," +
			"(1,   5, 1, 2, 0, 2, 0, 10, 2, 0, 0,  35., -34., 10)," +
			"(1,   6, 1, 2, 0, 2, 0, 11, 2, 0, 0,  35., -23., 10)," +
			"(1,   7, 1, 2, 0, 2, 0, 12, 2, 0, 0, 100., -33., 10)," +
			"(1,   8, 1, 2, 0, 2, 0, 13, 2, 0, 0,  35., -41., 10)," +
			"(1,   9, 1, 2, 0, 2, 0,  6, 0, 0, 0, 100.,  -3., 10)," +
			"(1,  10, 1, 2, 0, 2, 0,  5, 2, 0, 0,  35., -26., 10)," +
			"(1,  11, 1, 2, 0, 2, 0,  4, 2, 0, 0,  35., -33., 10)," +
			"(1,  12, 1, 2, 0, 2, 0,  2, 2, 0, 0, 100., -30., 10)," +
			"(1,  13, 1, 2, 0, 2, 0,  1, 2, 0, 0,  35., -32., 10)," +
			"(1,  14, 1, 2, 0, 2, 0, 14, 2, 0, 0, 100., -25., 10)," +
			"(1,  15, 1, 2, 0, 2, 0, 15, 2, 0, 0, 125.,  -9., 10)");

		// ... full-service DTV.  From OET-69.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1,  16, 1, 2, 0, 1, 1,  7, 0, 0, 0, 300.,  34., 10)," +
			"(1,  17, 1, 2, 0, 1, 1,  8, 0, 0, 0, 100., -17., 10)," +
			"(1,  18, 1, 2, 0, 1, 1,  9, 2, 0, 0,  35., -28., 10)," +
			"(1,  19, 1, 2, 0, 1, 1, 10, 2, 0, 0,  35., -34., 10)," +
			"(1,  20, 1, 2, 0, 1, 1, 11, 2, 0, 0,  35., -25., 10)," +
			"(1,  21, 1, 2, 0, 1, 1, 12, 2, 0, 0,  35., -43., 10)," +
			"(1,  22, 1, 2, 0, 1, 1, 13, 2, 0, 0,  35., -43., 10)," +
			"(1,  23, 1, 2, 0, 1, 1,  6, 0, 0, 0, 100., -14., 10)," +
			"(1,  24, 1, 2, 0, 1, 1,  5, 2, 0, 0,  35., -24., 10)," +
			"(1,  25, 1, 2, 0, 1, 1,  4, 2, 0, 0,  35., -30., 10)," +
			"(1,  26, 1, 2, 0, 1, 1,  3, 2, 0, 0,  35., -34., 10)," +
			"(1,  27, 1, 2, 0, 1, 1,  2, 2, 0, 0,  35., -35., 10)," +
			"(1,  28, 1, 2, 0, 1, 1,  1, 2, 0, 0,  35., -32., 10)," +
			"(1,  29, 1, 2, 0, 1, 1, 14, 2, 0, 0,  35., -33., 10)," +
			"(1,  30, 1, 2, 0, 1, 1, 15, 2, 0, 0,  35., -31., 10)");

		// ... Class A NTSC.  Per 73.6011, these are the same as protection of full-service NTSC from LPTV/translator.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1,  31, 1, 2, 0, 4, 0,  7, 0, 2, 0, 300.,  28., 10)," +
			"(1,  32, 1, 2, 0, 4, 0,  7, 0, 1, 0, 300.,  45., 10)," +
			"(1,  33, 1, 2, 0, 4, 0,  8, 1, 0, 0, 100., -12., 50)," +
			"(1,  34, 1, 2, 0, 4, 0,  8, 2, 0, 0, 100., -15., 50)," +
			"(1,  35, 1, 2, 0, 4, 0,  6, 1, 0, 0, 100.,  -6., 50)," +
			"(1,  36, 1, 2, 0, 4, 0,  6, 2, 0, 0, 100., -15., 50)," +
			"(1,  37, 1, 2, 0, 4, 0, 14, 2, 0, 0, 100., -23., 50)," +
			"(1,  38, 1, 2, 0, 4, 0, 15, 2, 0, 0, 125.,  -6., 50)");

		// ... Class A DTV.  Per 73.6016, these are same as protection of full-service NTSC from LPTV/translator DTV.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1,  39, 1, 2, 0, 3, 1,  7, 0, 0, 0, 300.,  34., 10)," +
			"(1,  40, 1, 2, 0, 3, 1,  8, 0, 0, 1, 100.,  10., 10)," +
			"(1,  41, 1, 2, 0, 3, 1,  8, 0, 0, 2, 100.,   0., 10)," +
			"(1,  42, 1, 2, 0, 3, 1,  8, 0, 0, 3, 100., -17., 10)," +
			"(1,  43, 1, 2, 0, 3, 1,  9, 2, 0, 0,  35., -28., 10)," +
			"(1,  44, 1, 2, 0, 3, 1, 10, 2, 0, 0,  35., -34., 10)," +
			"(1,  45, 1, 2, 0, 3, 1, 11, 2, 0, 0,  35., -25., 10)," +
			"(1,  46, 1, 2, 0, 3, 1, 12, 2, 0, 0,  35., -43., 10)," +
			"(1,  47, 1, 2, 0, 3, 1, 13, 2, 0, 0,  35., -43., 10)," +
			"(1,  48, 1, 2, 0, 3, 1,  6, 0, 0, 1, 100.,  10., 10)," +
			"(1,  49, 1, 2, 0, 3, 1,  6, 0, 0, 2, 100.,   0., 10)," +
			"(1,  50, 1, 2, 0, 3, 1,  6, 0, 0, 3, 100., -14., 10)," +
			"(1,  51, 1, 2, 0, 3, 1,  5, 2, 0, 0,  35., -24., 10)," +
			"(1,  52, 1, 2, 0, 3, 1,  4, 2, 0, 0,  35., -30., 10)," +
			"(1,  53, 1, 2, 0, 3, 1,  3, 2, 0, 0,  35., -34., 10)," +
			"(1,  54, 1, 2, 0, 3, 1,  2, 2, 0, 0,  35., -35., 10)," +
			"(1,  55, 1, 2, 0, 3, 1,  1, 2, 0, 0,  35., -32., 10)," +
			"(1,  56, 1, 2, 0, 3, 1, 14, 2, 0, 0,  35., -33., 10)," +
			"(1,  57, 1, 2, 0, 3, 1, 15, 2, 0, 0,  35., -31., 10)");

		// ... LPTV/translator.  From 74.705(c) and 74.705(d), except for culling distances which are from OET-69 for
		// full-service NTSC to full-service NTSC.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1,  58, 1, 2, 0, 6, 0,  7, 0, 2, 0, 300.,  28., 10)," +
			"(1,  59, 1, 2, 0, 6, 0,  7, 0, 1, 0, 300.,  45., 10)," +
			"(1,  60, 1, 2, 0, 6, 0,  8, 1, 0, 0, 100., -12., 50)," +
			"(1,  61, 1, 2, 0, 6, 0,  8, 2, 0, 0, 100., -15., 50)," +
			"(1,  62, 1, 2, 0, 6, 0,  6, 1, 0, 0, 100.,  -6., 50)," +
			"(1,  63, 1, 2, 0, 6, 0,  6, 2, 0, 0, 100., -15., 50)," +
			"(1,  64, 1, 2, 0, 6, 0, 14, 2, 0, 0, 100., -23., 50)," +
			"(1,  65, 1, 2, 0, 6, 0, 15, 2, 0, 0, 125.,  -6., 50)");

		// ... LPTV/translator DTV.  Per 74.793(b), these are the same as full-service DTV except for first-adjacent.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1,  66, 1, 2, 0, 5, 1,  7, 0, 0, 0, 300.,  34., 10)," +
			"(1,  67, 1, 2, 0, 5, 1,  8, 0, 0, 1, 100.,  10., 10)," +
			"(1,  68, 1, 2, 0, 5, 1,  8, 0, 0, 2, 100.,   0., 10)," +
			"(1,  69, 1, 2, 0, 5, 1,  8, 0, 0, 3, 100., -17., 10)," +
			"(1,  70, 1, 2, 0, 5, 1,  9, 2, 0, 0,  35., -28., 10)," +
			"(1,  71, 1, 2, 0, 5, 1, 10, 2, 0, 0,  35., -34., 10)," +
			"(1,  72, 1, 2, 0, 5, 1, 11, 2, 0, 0,  35., -25., 10)," +
			"(1,  73, 1, 2, 0, 5, 1, 12, 2, 0, 0,  35., -43., 10)," +
			"(1,  74, 1, 2, 0, 5, 1, 13, 2, 0, 0,  35., -43., 10)," +
			"(1,  75, 1, 2, 0, 5, 1,  6, 0, 0, 1, 100.,  10., 10)," +
			"(1,  76, 1, 2, 0, 5, 1,  6, 0, 0, 2, 100.,   0., 10)," +
			"(1,  77, 1, 2, 0, 5, 1,  6, 0, 0, 3, 100., -14., 10)," +
			"(1,  78, 1, 2, 0, 5, 1,  5, 2, 0, 0,  35., -24., 10)," +
			"(1,  79, 1, 2, 0, 5, 1,  4, 2, 0, 0,  35., -30., 10)," +
			"(1,  80, 1, 2, 0, 5, 1,  3, 2, 0, 0,  35., -34., 10)," +
			"(1,  81, 1, 2, 0, 5, 1,  2, 2, 0, 0,  35., -35., 10)," +
			"(1,  82, 1, 2, 0, 5, 1,  1, 2, 0, 0,  35., -32., 10)," +
			"(1,  83, 1, 2, 0, 5, 1, 14, 2, 0, 0,  35., -33., 10)," +
			"(1,  84, 1, 2, 0, 5, 1, 15, 2, 0, 0,  35., -31., 10)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// Protection of U.S. full-service DTV from ...

		// ... full-service NTSC.  From OET-69.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1,  85, 1, 1, 1, 2, 0,  7, 0, 0, 0, 300.,   2., 10)," +
			"(1,  86, 1, 1, 1, 2, 0,  8, 0, 0, 0, 100., -49., 10)," +
			"(1,  87, 1, 1, 1, 2, 0,  6, 0, 0, 0, 100., -48., 10)");

		// ... full-service DTV.  From OET-69.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1,  88, 1, 1, 1, 1, 1,  7, 0, 0, 0, 300.,  15., 10)," +
			"(1,  89, 1, 1, 1, 1, 1,  8, 0, 0, 0, 100., -26., 10)," +
			"(1,  90, 1, 1, 1, 1, 1,  6, 0, 0, 0, 100., -28., 10)");

		// ... Class A NTSC.  Per 73.6013, these are the same as protection of full-service DTV from full-service NTSC.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1,  91, 1, 1, 1, 4, 0,  7, 0, 0, 0, 300.,   2., 10)," +
			"(1,  92, 1, 1, 1, 4, 0,  8, 0, 0, 0, 100., -49., 10)," +
			"(1,  93, 1, 1, 1, 4, 0,  6, 0, 0, 0, 100., -48., 10)");

		// ... Class A DTV.  Per 73.6018, these are the same as protection of full-service DTV from LPTV/translator DTV.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1,  94, 1, 1, 1, 3, 1,  7, 0, 0, 0, 300.,  15., 10)," +
			"(1,  95, 1, 1, 1, 3, 1,  8, 0, 0, 1, 100.,  -7., 10)," +
			"(1,  96, 1, 1, 1, 3, 1,  8, 0, 0, 2, 100., -12., 10)," +
			"(1,  97, 1, 1, 1, 3, 1,  8, 0, 0, 3, 100., -26., 10)," +
			"(1,  98, 1, 1, 1, 3, 1,  6, 0, 0, 1, 100.,  -7., 10)," +
			"(1,  99, 1, 1, 1, 3, 1,  6, 0, 0, 2, 100., -12., 10)," +
			"(1, 100, 1, 1, 1, 3, 1,  6, 0, 0, 3, 100., -28., 10)");

		// ... LPTV/translator.  From 74.706(c) and 74.706(d), with culling distances from OET-69 for full-service DTV
		// to full-service DTV.  Note that 74.706(d) says for adjacent channel LPTVs located outside the DTV protected
		// contour, the interference requirement applies only at the perimeter of the protected area.  However it does
		// not seem logical to apply that in an OET-69-style study which is based on net changes in population across
		// the entire coverage area so for now that detail is not implemented, the entire coverage area will be studied.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 101, 1, 1, 1, 6, 0,  7, 0, 0, 0, 300.,   2., 10)," +
			"(1, 102, 1, 1, 1, 6, 0,  8, 0, 0, 0, 100., -49., 50)," +
			"(1, 103, 1, 1, 1, 6, 0,  6, 0, 0, 0, 100., -48., 50)");

		// ... LPTV/translator DTV.  Per 74.793(b), the co-channel case is the same as for full-service DTV.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 104, 1, 1, 1, 5, 1,  7, 0, 0, 0, 300.,  15., 10)," +
			"(1, 105, 1, 1, 1, 5, 1,  8, 0, 0, 1, 100.,  -7., 10)," +
			"(1, 106, 1, 1, 1, 5, 1,  8, 0, 0, 2, 100., -12., 10)," +
			"(1, 107, 1, 1, 1, 5, 1,  8, 0, 0, 3, 100., -26., 10)," +
			"(1, 108, 1, 1, 1, 5, 1,  6, 0, 0, 1, 100.,  -7., 10)," +
			"(1, 109, 1, 1, 1, 5, 1,  6, 0, 0, 2, 100., -12., 10)," +
			"(1, 110, 1, 1, 1, 5, 1,  6, 0, 0, 3, 100., -28., 10)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// Protection of U.S. Class A NTSC from ...

		// ... full-service NTSC.  From 73.613(f) and 73.613(g), with culling distances from OET-69 for full-service
		// NTSC to full-service NTSC.  Note this is the same as protection of full-service NTSC from LPTV based on a
		// comparison to the requirements in 74.705, but it is spelled out in 73.613 and not just referred to.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 111, 1, 4, 0, 2, 0,  7, 0, 2, 0, 300.,  28., 10)," +
			"(1, 112, 1, 4, 0, 2, 0,  7, 0, 1, 0, 300.,  45., 10)," +
			"(1, 113, 1, 4, 0, 2, 0,  8, 1, 0, 0, 100., -12., 50)," +
			"(1, 114, 1, 4, 0, 2, 0,  8, 2, 0, 0, 100., -15., 50)," +
			"(1, 115, 1, 4, 0, 2, 0,  6, 1, 0, 0, 100.,  -6., 50)," +
			"(1, 116, 1, 4, 0, 2, 0,  6, 2, 0, 0, 100., -15., 50)," +
			"(1, 117, 1, 4, 0, 2, 0, 14, 2, 0, 0, 100., -23., 50)," +
			"(1, 118, 1, 4, 0, 2, 0, 15, 2, 0, 0, 125.,  -6., 50)");

		// ... full-service DTV.  Per 73.623(c)(5)(i), same as protection of full-service NTSC from full-service DTV.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 119, 1, 4, 0, 1, 1,  7, 0, 0, 0, 300.,  34., 10)," +
			"(1, 120, 1, 4, 0, 1, 1,  8, 0, 0, 0, 100., -17., 10)," +
			"(1, 121, 1, 4, 0, 1, 1,  9, 2, 0, 0,  35., -28., 10)," +
			"(1, 122, 1, 4, 0, 1, 1, 10, 2, 0, 0,  35., -34., 10)," +
			"(1, 123, 1, 4, 0, 1, 1, 11, 2, 0, 0,  35., -25., 10)," +
			"(1, 124, 1, 4, 0, 1, 1, 12, 2, 0, 0,  35., -43., 10)," +
			"(1, 125, 1, 4, 0, 1, 1, 13, 2, 0, 0,  35., -43., 10)," +
			"(1, 126, 1, 4, 0, 1, 1,  6, 0, 0, 0, 100., -14., 10)," +
			"(1, 127, 1, 4, 0, 1, 1,  5, 2, 0, 0,  35., -24., 10)," +
			"(1, 128, 1, 4, 0, 1, 1,  4, 2, 0, 0,  35., -30., 10)," +
			"(1, 129, 1, 4, 0, 1, 1,  3, 2, 0, 0,  35., -34., 10)," +
			"(1, 130, 1, 4, 0, 1, 1,  2, 2, 0, 0,  35., -35., 10)," +
			"(1, 131, 1, 4, 0, 1, 1,  1, 2, 0, 0,  35., -32., 10)," +
			"(1, 132, 1, 4, 0, 1, 1, 14, 2, 0, 0,  35., -33., 10)," +
			"(1, 133, 1, 4, 0, 1, 1, 15, 2, 0, 0,  35., -31., 10)");

		// ... Class A NTSC.  Per 73.6012, same as protection of LPTV from LPTV, see below.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 134, 1, 4, 0, 4, 0,  7, 0, 2, 0, 300.,  28., 10)," +
			"(1, 135, 1, 4, 0, 4, 0,  7, 0, 1, 0, 300.,  45., 10)," +
			"(1, 136, 1, 4, 0, 4, 0,  8, 1, 0, 0, 100., -12., 50)," +
			"(1, 137, 1, 4, 0, 4, 0,  8, 2, 0, 0, 100., -15., 50)," +
			"(1, 138, 1, 4, 0, 4, 0,  6, 1, 0, 0, 100.,  -6., 50)," +
			"(1, 139, 1, 4, 0, 4, 0,  6, 2, 0, 0, 100., -15., 50)," +
			"(1, 140, 1, 4, 0, 4, 0, 15, 2, 0, 0, 125.,  -6., 50)");

		// ... Class A DTV.  Per 73.6017, same as the LPTV/translator case, see below.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 141, 1, 4, 0, 3, 1,  7, 0, 0, 0, 300.,  34., 10)," +
			"(1, 142, 1, 4, 0, 3, 1,  8, 0, 0, 1, 100.,  10., 10)," +
			"(1, 143, 1, 4, 0, 3, 1,  8, 0, 0, 2, 100.,   0., 10)," +
			"(1, 144, 1, 4, 0, 3, 1,  8, 0, 0, 3, 100., -17., 10)," +
			"(1, 145, 1, 4, 0, 3, 1,  9, 2, 0, 0,  35., -28., 10)," +
			"(1, 146, 1, 4, 0, 3, 1, 10, 2, 0, 0,  35., -34., 10)," +
			"(1, 147, 1, 4, 0, 3, 1, 11, 2, 0, 0,  35., -25., 10)," +
			"(1, 148, 1, 4, 0, 3, 1, 12, 2, 0, 0,  35., -43., 10)," +
			"(1, 149, 1, 4, 0, 3, 1, 13, 2, 0, 0,  35., -43., 10)," +
			"(1, 150, 1, 4, 0, 3, 1,  6, 0, 0, 1, 100.,  10., 10)," +
			"(1, 151, 1, 4, 0, 3, 1,  6, 0, 0, 2, 100.,   0., 10)," +
			"(1, 152, 1, 4, 0, 3, 1,  6, 0, 0, 3, 100., -14., 10)," +
			"(1, 153, 1, 4, 0, 3, 1,  5, 2, 0, 0,  35., -24., 10)," +
			"(1, 154, 1, 4, 0, 3, 1,  4, 2, 0, 0,  35., -30., 10)," +
			"(1, 155, 1, 4, 0, 3, 1,  3, 2, 0, 0,  35., -34., 10)," +
			"(1, 156, 1, 4, 0, 3, 1,  2, 2, 0, 0,  35., -35., 10)," +
			"(1, 157, 1, 4, 0, 3, 1,  1, 2, 0, 0,  35., -32., 10)," +
			"(1, 158, 1, 4, 0, 3, 1, 14, 2, 0, 0,  35., -33., 10)," +
			"(1, 159, 1, 4, 0, 3, 1, 15, 2, 0, 0,  35., -31., 10)");

		// ... LPTV/translator.  Per 74.708(c), same as protection of LPTV from LPTV.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 160, 1, 4, 0, 6, 0,  7, 0, 2, 0, 300.,  28., 10)," +
			"(1, 161, 1, 4, 0, 6, 0,  7, 0, 1, 0, 300.,  45., 10)," +
			"(1, 162, 1, 4, 0, 6, 0,  8, 1, 0, 0, 100., -12., 50)," +
			"(1, 163, 1, 4, 0, 6, 0,  8, 2, 0, 0, 100., -15., 50)," +
			"(1, 164, 1, 4, 0, 6, 0,  6, 1, 0, 0, 100.,  -6., 50)," +
			"(1, 165, 1, 4, 0, 6, 0,  6, 2, 0, 0, 100., -15., 50)," +
			"(1, 166, 1, 4, 0, 6, 0, 15, 2, 0, 0, 125.,  -6., 50)");

		// ... LPTV/translator DTV.  Per 74.793(b), same as protection of full-service NTSC from full-service DTV.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 167, 1, 4, 0, 5, 1,  7, 0, 0, 0, 300.,  34., 10)," +
			"(1, 168, 1, 4, 0, 5, 1,  8, 0, 0, 1, 100.,  10., 10)," +
			"(1, 169, 1, 4, 0, 5, 1,  8, 0, 0, 2, 100.,   0., 10)," +
			"(1, 170, 1, 4, 0, 5, 1,  8, 0, 0, 3, 100., -17., 10)," +
			"(1, 171, 1, 4, 0, 5, 1,  9, 2, 0, 0,  35., -28., 10)," +
			"(1, 172, 1, 4, 0, 5, 1, 10, 2, 0, 0,  35., -34., 10)," +
			"(1, 173, 1, 4, 0, 5, 1, 11, 2, 0, 0,  35., -25., 10)," +
			"(1, 174, 1, 4, 0, 5, 1, 12, 2, 0, 0,  35., -43., 10)," +
			"(1, 175, 1, 4, 0, 5, 1, 13, 2, 0, 0,  35., -43., 10)," +
			"(1, 176, 1, 4, 0, 5, 1,  6, 0, 0, 1, 100.,  10., 10)," +
			"(1, 177, 1, 4, 0, 5, 1,  6, 0, 0, 2, 100.,   0., 10)," +
			"(1, 178, 1, 4, 0, 5, 1,  6, 0, 0, 3, 100., -14., 10)," +
			"(1, 179, 1, 4, 0, 5, 1,  5, 2, 0, 0,  35., -24., 10)," +
			"(1, 180, 1, 4, 0, 5, 1,  4, 2, 0, 0,  35., -30., 10)," +
			"(1, 181, 1, 4, 0, 5, 1,  3, 2, 0, 0,  35., -34., 10)," +
			"(1, 182, 1, 4, 0, 5, 1,  2, 2, 0, 0,  35., -35., 10)," +
			"(1, 183, 1, 4, 0, 5, 1,  1, 2, 0, 0,  35., -32., 10)," +
			"(1, 184, 1, 4, 0, 5, 1, 14, 2, 0, 0,  35., -33., 10)," +
			"(1, 185, 1, 4, 0, 5, 1, 15, 2, 0, 0,  35., -31., 10)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// Protection of U.S. Class A DTV from ...

		// ... full-service NTSC.  Per 73.613(h), same as protection of full-service DTV from full-service NTSC.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 186, 1, 3, 1, 2, 0,  7, 0, 0, 0, 300.,   2., 10)," +
			"(1, 187, 1, 3, 1, 2, 0,  8, 0, 0, 0, 100., -49., 10)," +
			"(1, 188, 1, 3, 1, 2, 0,  6, 0, 0, 0, 100., -48., 10)");

		// ... full-service DTV.  Per 73.623(c)(5)(ii), same as protection of full-service DTV from full-service DTV.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 189, 1, 3, 1, 1, 1,  7, 0, 0, 0, 300.,  15., 10)," +
			"(1, 190, 1, 3, 1, 1, 1,  8, 0, 0, 0, 100., -26., 10)," +
			"(1, 191, 1, 3, 1, 1, 1,  6, 0, 0, 0, 100., -28., 10)");

		// ... Class A NTSC.  Per 73.6014, same as protection of full-service DTV from LPTV/translator.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 192, 1, 3, 1, 4, 0,  7, 0, 0, 0, 300.,   2., 10)," +
			"(1, 193, 1, 3, 1, 4, 0,  8, 0, 0, 0, 100., -49., 50)," +
			"(1, 194, 1, 3, 1, 4, 0,  6, 0, 0, 0, 100., -48., 50)");

		// ... Class A DTV.  Per 73.6019, same as protection of full-service DTV from LPTV/translator DTV, see below.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 195, 1, 3, 1, 3, 1,  7, 0, 0, 0, 300.,  15., 10)," +
			"(1, 196, 1, 3, 1, 3, 1,  8, 0, 0, 1, 100.,  -7., 10)," +
			"(1, 197, 1, 3, 1, 3, 1,  8, 0, 0, 2, 100., -12., 10)," +
			"(1, 198, 1, 3, 1, 3, 1,  8, 0, 0, 3, 100., -26., 10)," +
			"(1, 199, 1, 3, 1, 3, 1,  6, 0, 0, 1, 100.,  -7., 10)," +
			"(1, 200, 1, 3, 1, 3, 1,  6, 0, 0, 2, 100., -12., 10)," +
			"(1, 201, 1, 3, 1, 3, 1,  6, 0, 0, 3, 100., -28., 10)");

		// ... LPTV/translator.  Per 74.708(d)(2), same as protection of full-service DTV from LPTV/translator.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 202, 1, 3, 1, 6, 0,  7, 0, 0, 0, 300.,   2., 10)," +
			"(1, 203, 1, 3, 1, 6, 0,  8, 0, 0, 0, 100., -49., 50)," +
			"(1, 204, 1, 3, 1, 6, 0,  6, 0, 0, 0, 100., -48., 50)");

		// ... LPTV/translator DTV.  Per 74.793(b), co-channel is the same as for the full-service case.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 205, 1, 3, 1, 5, 1,  7, 0, 0, 0, 300.,  15., 10)," +
			"(1, 206, 1, 3, 1, 5, 1,  8, 0, 0, 1, 100.,  -7., 10)," +
			"(1, 207, 1, 3, 1, 5, 1,  8, 0, 0, 2, 100., -12., 10)," +
			"(1, 208, 1, 3, 1, 5, 1,  8, 0, 0, 3, 100., -26., 10)," +
			"(1, 209, 1, 3, 1, 5, 1,  6, 0, 0, 1, 100.,  -7., 10)," +
			"(1, 210, 1, 3, 1, 5, 1,  6, 0, 0, 2, 100., -12., 10)," +
			"(1, 211, 1, 3, 1, 5, 1,  6, 0, 0, 3, 100., -28., 10)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// Protection of U.S. LPTV/translator from ...

		// ... full-service NTSC.  The parameters are the same as for the LPTV-to-LPTV case.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 212, 1, 6, 0, 2, 0,  7, 0, 2, 0, 300.,  28., 10)," +
			"(1, 213, 1, 6, 0, 2, 0,  7, 0, 1, 0, 300.,  45., 10)," +
			"(1, 214, 1, 6, 0, 2, 0,  8, 1, 0, 0, 100., -12., 50)," +
			"(1, 215, 1, 6, 0, 2, 0,  8, 2, 0, 0, 100., -15., 50)," +
			"(1, 216, 1, 6, 0, 2, 0,  6, 1, 0, 0, 100.,  -6., 50)," +
			"(1, 217, 1, 6, 0, 2, 0,  6, 2, 0, 0, 100., -15., 50)," +
			"(1, 218, 1, 6, 0, 2, 0, 15, 2, 0, 0, 125.,  -6., 50)");

		// ... full-service DTV.  Here, there is no equivalent to draw from other than the full-service DTV to NTSC
		// case which is far too severe.  The parameters here are a composite of the taboo channels for the LPTV-to-
		// LPTV case but using D/Us and culling distances from the full-service DTV to NTSC case, also using F(50,50)
		// for undesired except for co-channel as in LPTV-to-LPTV.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 219, 1, 6, 0, 1, 1,  7, 0, 0, 0, 300.,  34., 10)," +
			"(1, 220, 1, 6, 0, 1, 1,  8, 0, 0, 0, 100., -17., 50)," +
			"(1, 221, 1, 6, 0, 1, 1,  6, 0, 0, 0, 100., -14., 50)," +
			"(1, 222, 1, 6, 0, 1, 1, 15, 2, 0, 0,  35., -31., 50)");

		// ... Class A NTSC.  Per 73.6012, same as protection of LPTV from LPTV.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 223, 1, 6, 0, 4, 0,  7, 0, 2, 0, 300.,  28., 10)," +
			"(1, 224, 1, 6, 0, 4, 0,  7, 0, 1, 0, 300.,  45., 10)," +
			"(1, 225, 1, 6, 0, 4, 0,  8, 1, 0, 0, 100., -12., 50)," +
			"(1, 226, 1, 6, 0, 4, 0,  8, 2, 0, 0, 100., -15., 50)," +
			"(1, 227, 1, 6, 0, 4, 0,  6, 1, 0, 0, 100.,  -6., 50)," +
			"(1, 228, 1, 6, 0, 4, 0,  6, 2, 0, 0, 100., -15., 50)," +
			"(1, 229, 1, 6, 0, 4, 0, 15, 2, 0, 0, 125.,  -6., 50)");

		// ... Class A DTV.  Per 73.6017, same as protection from LPTV DTV.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 230, 1, 6, 0, 3, 1,  7, 0, 0, 0, 300.,  34., 10)," +
			"(1, 231, 1, 6, 0, 3, 1,  8, 0, 0, 1, 100.,  10., 10)," +
			"(1, 232, 1, 6, 0, 3, 1,  8, 0, 0, 2, 100.,   0., 10)," +
			"(1, 233, 1, 6, 0, 3, 1,  8, 0, 0, 3, 100., -17., 10)," +
			"(1, 234, 1, 6, 0, 3, 1,  9, 2, 0, 0,  35., -28., 10)," +
			"(1, 235, 1, 6, 0, 3, 1, 10, 2, 0, 0,  35., -34., 10)," +
			"(1, 236, 1, 6, 0, 3, 1, 11, 2, 0, 0,  35., -25., 10)," +
			"(1, 237, 1, 6, 0, 3, 1, 12, 2, 0, 0,  35., -43., 10)," +
			"(1, 238, 1, 6, 0, 3, 1, 13, 2, 0, 0,  35., -43., 10)," +
			"(1, 239, 1, 6, 0, 3, 1,  6, 0, 0, 1, 100.,  10., 10)," +
			"(1, 240, 1, 6, 0, 3, 1,  6, 0, 0, 2, 100.,   0., 10)," +
			"(1, 241, 1, 6, 0, 3, 1,  6, 0, 0, 3, 100., -14., 10)," +
			"(1, 242, 1, 6, 0, 3, 1,  5, 2, 0, 0,  35., -24., 10)," +
			"(1, 243, 1, 6, 0, 3, 1,  4, 2, 0, 0,  35., -30., 10)," +
			"(1, 244, 1, 6, 0, 3, 1,  3, 2, 0, 0,  35., -34., 10)," +
			"(1, 245, 1, 6, 0, 3, 1,  2, 2, 0, 0,  35., -35., 10)," +
			"(1, 246, 1, 6, 0, 3, 1,  1, 2, 0, 0,  35., -32., 10)," +
			"(1, 247, 1, 6, 0, 3, 1, 14, 2, 0, 0,  35., -33., 10)," +
			"(1, 248, 1, 6, 0, 3, 1, 15, 2, 0, 0,  35., -31., 10)");

		// ... LPTV/translator.  From 74.707(c) and 74.707(d), with culling distances from OET-69 for full-service NTSC
		// to full-service NTSC.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 249, 1, 6, 0, 6, 0,  7, 0, 2, 0, 300.,  28., 10)," +
			"(1, 250, 1, 6, 0, 6, 0,  7, 0, 1, 0, 300.,  45., 10)," +
			"(1, 251, 1, 6, 0, 6, 0,  8, 1, 0, 0, 100., -12., 50)," +
			"(1, 252, 1, 6, 0, 6, 0,  8, 2, 0, 0, 100., -15., 50)," +
			"(1, 253, 1, 6, 0, 6, 0,  6, 1, 0, 0, 100.,  -6., 50)," +
			"(1, 254, 1, 6, 0, 6, 0,  6, 2, 0, 0, 100., -15., 50)," +
			"(1, 255, 1, 6, 0, 6, 0, 15, 2, 0, 0, 125.,  -6., 50)");

		// ... LPTV/translator DTV.  Per 74.793(b), same as protection of full-service NTSC from full-service DTV,
		// except for the extra first-adjacent cases for emission mask variations.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 256, 1, 6, 0, 5, 1,  7, 0, 0, 0, 300.,  34., 10)," +
			"(1, 257, 1, 6, 0, 5, 1,  8, 0, 0, 1, 100.,  10., 10)," +
			"(1, 258, 1, 6, 0, 5, 1,  8, 0, 0, 2, 100.,   0., 10)," +
			"(1, 259, 1, 6, 0, 5, 1,  8, 0, 0, 3, 100., -17., 10)," +
			"(1, 260, 1, 6, 0, 5, 1,  9, 2, 0, 0,  35., -28., 10)," +
			"(1, 261, 1, 6, 0, 5, 1, 10, 2, 0, 0,  35., -34., 10)," +
			"(1, 262, 1, 6, 0, 5, 1, 11, 2, 0, 0,  35., -25., 10)," +
			"(1, 263, 1, 6, 0, 5, 1, 12, 2, 0, 0,  35., -43., 10)," +
			"(1, 264, 1, 6, 0, 5, 1, 13, 2, 0, 0,  35., -43., 10)," +
			"(1, 265, 1, 6, 0, 5, 1,  6, 0, 0, 1, 100.,  10., 10)," +
			"(1, 266, 1, 6, 0, 5, 1,  6, 0, 0, 2, 100.,   0., 10)," +
			"(1, 267, 1, 6, 0, 5, 1,  6, 0, 0, 3, 100., -14., 10)," +
			"(1, 268, 1, 6, 0, 5, 1,  5, 2, 0, 0,  35., -24., 10)," +
			"(1, 269, 1, 6, 0, 5, 1,  4, 2, 0, 0,  35., -30., 10)," +
			"(1, 270, 1, 6, 0, 5, 1,  3, 2, 0, 0,  35., -34., 10)," +
			"(1, 271, 1, 6, 0, 5, 1,  2, 2, 0, 0,  35., -35., 10)," +
			"(1, 272, 1, 6, 0, 5, 1,  1, 2, 0, 0,  35., -32., 10)," +
			"(1, 273, 1, 6, 0, 5, 1, 14, 2, 0, 0,  35., -33., 10)," +
			"(1, 274, 1, 6, 0, 5, 1, 15, 2, 0, 0,  35., -31., 10)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// Protection of U.S. LPTV/translator DTV from ...

		// ... full-service NTSC.  Values are copied from the full-service NTSC vs. Class A DTV case.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 275, 1, 5, 1, 2, 0,  7, 0, 0, 0, 300.,   2., 10)," +
			"(1, 276, 1, 5, 1, 2, 0,  8, 0, 0, 0, 100., -49., 10)," +
			"(1, 277, 1, 5, 1, 2, 0,  6, 0, 0, 0, 100., -48., 10)");

		// ... full-service DTV.  As above, this is copied from the Class A case.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 278, 1, 5, 1, 1, 1,  7, 0, 0, 0, 300.,  15., 10)," +
			"(1, 279, 1, 5, 1, 1, 1,  8, 0, 0, 0, 100., -26., 10)," +
			"(1, 280, 1, 5, 1, 1, 1,  6, 0, 0, 0, 100., -28., 10)");

		// ... Class A NTSC.  Per 73.6014, same as protection of full-service DTV from LPTV/translator.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 281, 1, 5, 1, 4, 0,  7, 0, 0, 0, 300.,   2., 10)," +
			"(1, 282, 1, 5, 1, 4, 0,  8, 0, 0, 0, 100., -49., 50)," +
			"(1, 283, 1, 5, 1, 4, 0,  6, 0, 0, 0, 100., -48., 50)");

		// ... Class A DTV.  Per 73.6019, same as protection from other LPTV DTV.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 284, 1, 5, 1, 3, 1,  7, 0, 0, 0, 300.,  15., 10)," +
			"(1, 285, 1, 5, 1, 3, 1,  8, 0, 0, 1, 100.,  -7., 10)," +
			"(1, 286, 1, 5, 1, 3, 1,  8, 0, 0, 2, 100., -12., 10)," +
			"(1, 287, 1, 5, 1, 3, 1,  8, 0, 0, 3, 100., -26., 10)," +
			"(1, 288, 1, 5, 1, 3, 1,  6, 0, 0, 1, 100.,  -7., 10)," +
			"(1, 289, 1, 5, 1, 3, 1,  6, 0, 0, 2, 100., -12., 10)," +
			"(1, 290, 1, 5, 1, 3, 1,  6, 0, 0, 3, 100., -28., 10)");

		// ... LPTV/translator.  Per 74.710, same as protection of full-service DTV from LPTV/translator.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 291, 1, 5, 1, 6, 0,  7, 0, 0, 0, 300.,   2., 10)," +
			"(1, 292, 1, 5, 1, 6, 0,  8, 0, 0, 0, 100., -49., 50)," +
			"(1, 293, 1, 5, 1, 6, 0,  6, 0, 0, 0, 100., -48., 50)");

		// ... LPTV/translator DTV.  Per 74.793(b), co-channel is the same as for the full-service case.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 294, 1, 5, 1, 5, 1,  7, 0, 0, 0, 300.,  15., 10)," +
			"(1, 295, 1, 5, 1, 5, 1,  8, 0, 0, 1, 100.,  -7., 10)," +
			"(1, 296, 1, 5, 1, 5, 1,  8, 0, 0, 2, 100., -12., 10)," +
			"(1, 297, 1, 5, 1, 5, 1,  8, 0, 0, 3, 100., -26., 10)," +
			"(1, 298, 1, 5, 1, 5, 1,  6, 0, 0, 1, 100.,  -7., 10)," +
			"(1, 299, 1, 5, 1, 5, 1,  6, 0, 0, 2, 100., -12., 10)," +
			"(1, 300, 1, 5, 1, 5, 1,  6, 0, 0, 3, 100., -26., 10)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// All Canadian default rules were provided by FCC staff on 01Aug2014.

		// Protection of Canadian full-service DTV from ...

		// ... full-service DTV.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 301, 2, 1, 1, 1, 1,  6, 0, 0, 0, 100., -28., 10)," +
			"(1, 302, 2, 1, 1, 1, 1,  7, 0, 0, 0, 300.,  15., 10)," +
			"(1, 303, 2, 1, 1, 1, 1,  8, 0, 0, 0, 100., -26., 10)");

		// ... full-service NTSC.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 304, 2, 1, 1, 2, 0,  6, 0, 0, 0, 100., -48., 10)," +
			"(1, 305, 2, 1, 1, 2, 0,  7, 0, 0, 0, 300.,   2., 10)," +
			"(1, 306, 2, 1, 1, 2, 0,  8, 0, 0, 0, 100., -49., 10)");

		// ... Class A DTV.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 307, 2, 1, 1, 3, 1,  6, 0, 0, 3, 100., -28., 10)," +
			"(1, 308, 2, 1, 1, 3, 1,  6, 0, 0, 2, 100., -12., 10)," +
			"(1, 309, 2, 1, 1, 3, 1,  6, 0, 0, 1, 100.,  -7., 10)," +
			"(1, 310, 2, 1, 1, 3, 1,  7, 0, 0, 0, 300.,  15., 10)," +
			"(1, 311, 2, 1, 1, 3, 1,  8, 0, 0, 3, 100., -26., 10)," +
			"(1, 312, 2, 1, 1, 3, 1,  8, 0, 0, 2, 100., -12., 10)," +
			"(1, 313, 2, 1, 1, 3, 1,  8, 0, 0, 1, 100.,  -7., 10)");

		// ... Class A NTSC.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 314, 2, 1, 1, 4, 0,  6, 0, 0, 0, 100., -48., 10)," +
			"(1, 315, 2, 1, 1, 4, 0,  7, 0, 0, 0, 300.,   2., 10)," +
			"(1, 316, 2, 1, 1, 4, 0,  8, 0, 0, 0, 100., -49., 10)");

		// ... LPTV/translator DTV.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 317, 2, 1, 1, 5, 1,  6, 0, 0, 3, 100., -28., 10)," +
			"(1, 318, 2, 1, 1, 5, 1,  6, 0, 0, 2, 100., -12., 10)," +
			"(1, 319, 2, 1, 1, 5, 1,  6, 0, 0, 1, 100.,  -7., 10)," +
			"(1, 320, 2, 1, 1, 5, 1,  7, 0, 0, 0, 300.,  15., 10)," +
			"(1, 321, 2, 1, 1, 5, 1,  8, 0, 0, 3, 100., -26., 10)," +
			"(1, 322, 2, 1, 1, 5, 1,  8, 0, 0, 2, 100., -12., 10)," +
			"(1, 323, 2, 1, 1, 5, 1,  8, 0, 0, 1, 100.,  -7., 10)");

		// ... LPTV/translator NTSC.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 324, 2, 1, 1, 6, 0,  6, 0, 0, 0, 100., -48., 10)," +
			"(1, 325, 2, 1, 1, 6, 0,  7, 0, 0, 0, 300.,   2., 10)," +
			"(1, 326, 2, 1, 1, 6, 0,  8, 0, 0, 0, 100., -49., 10)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// Protection of Canadian full-service NTSC from ...

		// ... full-service DTV.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 327, 2, 2, 0, 1, 1,  1, 2, 0, 0,  35., -32., 10)," +
			"(1, 328, 2, 2, 0, 1, 1,  2, 2, 0, 0,  35., -35., 10)," +
			"(1, 329, 2, 2, 0, 1, 1,  3, 2, 0, 0,  35., -34., 10)," +
			"(1, 330, 2, 2, 0, 1, 1,  4, 2, 0, 0,  35., -30., 10)," +
			"(1, 331, 2, 2, 0, 1, 1,  5, 2, 0, 0,  35., -24., 10)," +
			"(1, 332, 2, 2, 0, 1, 1,  6, 0, 0, 0, 100., -14., 10)," +
			"(1, 333, 2, 2, 0, 1, 1,  7, 0, 0, 0, 300.,  34., 10)," +
			"(1, 334, 2, 2, 0, 1, 1,  8, 0, 0, 0, 100., -17., 10)," +
			"(1, 335, 2, 2, 0, 1, 1,  9, 2, 0, 0,  35., -28., 10)," +
			"(1, 336, 2, 2, 0, 1, 1, 10, 2, 0, 0,  35., -34., 10)," +
			"(1, 337, 2, 2, 0, 1, 1, 11, 2, 0, 0,  35., -25., 10)," +
			"(1, 338, 2, 2, 0, 1, 1, 12, 2, 0, 0,  35., -43., 10)," +
			"(1, 339, 2, 2, 0, 1, 1, 13, 2, 0, 0,  35., -43., 10)," +
			"(1, 340, 2, 2, 0, 1, 1, 14, 2, 0, 0, 100., -46., 50)," +
			"(1, 341, 2, 2, 0, 1, 1, 15, 2, 0, 0, 125., -28., 50)");

		// ... full-service NTSC.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 342, 2, 2, 0, 2, 0,  2, 2, 0, 0, 100., -30., 10)," +
			"(1, 343, 2, 2, 0, 2, 0,  4, 2, 0, 0,  35., -33., 10)," +
			"(1, 344, 2, 2, 0, 2, 0,  5, 2, 0, 0,  35., -26., 10)," +
			"(1, 345, 2, 2, 0, 2, 0,  6, 1, 0, 0, 100., -25., 50)," +
			"(1, 346, 2, 2, 0, 2, 0,  6, 2, 0, 0, 100., -36., 10)," +
			"(1, 347, 2, 2, 0, 2, 0,  7, 1, 2, 0, 300.,  15., 10)," +
			"(1, 348, 2, 2, 0, 2, 0,  7, 1, 1, 0, 300.,  32., 10)," +
			"(1, 349, 2, 2, 0, 2, 0,  7, 2, 2, 0, 300.,  18., 10)," +
			"(1, 350, 2, 2, 0, 2, 0,  7, 2, 1, 0, 300.,  35., 10)," +
			"(1, 351, 2, 2, 0, 2, 0,  8, 1, 0, 0, 100., -25., 50)," +
			"(1, 352, 2, 2, 0, 2, 0,  8, 2, 0, 0, 100., -36., 10)," +
			"(1, 353, 2, 2, 0, 2, 0,  9, 2, 0, 0,  35., -29., 10)," +
			"(1, 354, 2, 2, 0, 2, 0, 10, 2, 0, 0,  35., -34., 10)," +
			"(1, 355, 2, 2, 0, 2, 0, 11, 2, 0, 0,  35., -23., 10)," +
			"(1, 356, 2, 2, 0, 2, 0, 12, 2, 0, 0, 100., -33., 10)," +
			"(1, 357, 2, 2, 0, 2, 0, 14, 2, 0, 0,  35., -46., 10)," +
			"(1, 358, 2, 2, 0, 2, 0, 15, 2, 0, 0,  35., -28., 10)");

		// ... Class A DTV.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 359, 2, 2, 0, 3, 1,  1, 2, 0, 0,  35., -32., 10)," +
			"(1, 360, 2, 2, 0, 3, 1,  2, 2, 0, 0,  35., -35., 10)," +
			"(1, 361, 2, 2, 0, 3, 1,  3, 2, 0, 0,  35., -34., 10)," +
			"(1, 362, 2, 2, 0, 3, 1,  4, 2, 0, 0,  35., -30., 10)," +
			"(1, 363, 2, 2, 0, 3, 1,  5, 2, 0, 0,  35., -24., 10)," +
			"(1, 364, 2, 2, 0, 3, 1,  6, 0, 0, 3, 100., -14., 10)," +
			"(1, 365, 2, 2, 0, 3, 1,  6, 0, 0, 2, 100.,   0., 10)," +
			"(1, 366, 2, 2, 0, 3, 1,  6, 0, 0, 1, 100.,  10., 10)," +
			"(1, 367, 2, 2, 0, 3, 1,  7, 0, 0, 0, 300.,  34., 10)," +
			"(1, 368, 2, 2, 0, 3, 1,  8, 0, 0, 3, 100., -17., 10)," +
			"(1, 369, 2, 2, 0, 3, 1,  8, 0, 0, 2, 100.,   0., 10)," +
			"(1, 370, 2, 2, 0, 3, 1,  8, 0, 0, 1, 100.,  10., 10)," +
			"(1, 371, 2, 2, 0, 3, 1,  9, 2, 0, 0,  35., -28., 10)," +
			"(1, 372, 2, 2, 0, 3, 1, 10, 2, 0, 0,  35., -34., 10)," +
			"(1, 373, 2, 2, 0, 3, 1, 11, 2, 0, 0,  35., -25., 10)," +
			"(1, 374, 2, 2, 0, 3, 1, 12, 2, 0, 0,  35., -43., 10)," +
			"(1, 375, 2, 2, 0, 3, 1, 13, 2, 0, 0,  35., -43., 10)," +
			"(1, 376, 2, 2, 0, 3, 1, 14, 2, 0, 0,  35., -33., 10)," +
			"(1, 377, 2, 2, 0, 3, 1, 15, 2, 0, 0,  35., -31., 10)");

		// ... Class A NTSC.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 378, 2, 2, 0, 4, 0,  2, 2, 0, 0, 100., -30., 10)," +
			"(1, 379, 2, 2, 0, 4, 0,  4, 2, 0, 0,  35., -33., 10)," +
			"(1, 380, 2, 2, 0, 4, 0,  5, 2, 0, 0,  35., -26., 10)," +
			"(1, 381, 2, 2, 0, 4, 0,  6, 0, 0, 0, 100., -16., 10)," +
			"(1, 382, 2, 2, 0, 4, 0,  7, 1, 1, 0, 300.,  35., 10)," +
			"(1, 383, 2, 2, 0, 4, 0,  7, 1, 2, 0, 300.,  25., 10)," +
			"(1, 384, 2, 2, 0, 4, 0,  7, 2, 1, 0, 300.,  28., 10)," +
			"(1, 385, 2, 2, 0, 4, 0,  7, 2, 2, 0, 300.,  18., 10)," +
			"(1, 386, 2, 2, 0, 4, 0,  8, 0, 0, 0, 100., -16., 10)," +
			"(1, 387, 2, 2, 0, 4, 0,  9, 2, 0, 0,  35., -29., 10)," +
			"(1, 388, 2, 2, 0, 4, 0, 10, 2, 0, 0,  35., -34., 10)," +
			"(1, 389, 2, 2, 0, 4, 0, 11, 2, 0, 0,  35., -23., 10)," +
			"(1, 390, 2, 2, 0, 4, 0, 12, 2, 0, 0, 100., -33., 10)," +
			"(1, 391, 2, 2, 0, 4, 0, 14, 2, 0, 0, 100., -28., 10)," +
			"(1, 392, 2, 2, 0, 4, 0, 15, 2, 0, 0, 125., -10., 10)");

		// ... LPTV/translator DTV.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 393, 2, 2, 0, 5, 1,  1, 2, 0, 0,  35., -32., 10)," +
			"(1, 394, 2, 2, 0, 5, 1,  2, 2, 0, 0,  35., -35., 10)," +
			"(1, 395, 2, 2, 0, 5, 1,  3, 2, 0, 0,  35., -34., 10)," +
			"(1, 396, 2, 2, 0, 5, 1,  4, 2, 0, 0,  35., -30., 10)," +
			"(1, 397, 2, 2, 0, 5, 1,  5, 2, 0, 0,  35., -24., 10)," +
			"(1, 398, 2, 2, 0, 5, 1,  6, 0, 0, 3, 100., -14., 10)," +
			"(1, 399, 2, 2, 0, 5, 1,  6, 0, 0, 2, 100.,   0., 10)," +
			"(1, 400, 2, 2, 0, 5, 1,  6, 0, 0, 1, 100.,  10., 10)," +
			"(1, 401, 2, 2, 0, 5, 1,  7, 0, 0, 0, 300.,  34., 10)," +
			"(1, 402, 2, 2, 0, 5, 1,  8, 0, 0, 3, 100., -17., 10)," +
			"(1, 403, 2, 2, 0, 5, 1,  8, 0, 0, 2, 100.,   0., 10)," +
			"(1, 404, 2, 2, 0, 5, 1,  8, 0, 0, 1, 100.,  10., 10)," +
			"(1, 405, 2, 2, 0, 5, 1,  9, 2, 0, 0,  35., -28., 10)," +
			"(1, 406, 2, 2, 0, 5, 1, 10, 2, 0, 0,  35., -34., 10)," +
			"(1, 407, 2, 2, 0, 5, 1, 11, 2, 0, 0,  35., -25., 10)," +
			"(1, 408, 2, 2, 0, 5, 1, 12, 2, 0, 0,  35., -43., 10)," +
			"(1, 409, 2, 2, 0, 5, 1, 13, 2, 0, 0,  35., -43., 10)," +
			"(1, 410, 2, 2, 0, 5, 1, 14, 2, 0, 0,  35., -33., 10)," +
			"(1, 411, 2, 2, 0, 5, 1, 15, 2, 0, 0,  35., -31., 10)");

		// ... LPTV/translator NTSC.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 412, 2, 2, 0, 6, 0,  2, 2, 0, 0, 100., -30., 10)," +
			"(1, 413, 2, 2, 0, 6, 0,  4, 2, 0, 0,  35., -33., 10)," +
			"(1, 414, 2, 2, 0, 6, 0,  5, 2, 0, 0,  35., -26., 10)," +
			"(1, 415, 2, 2, 0, 6, 0,  6, 0, 0, 0, 100., -16., 10)," +
			"(1, 416, 2, 2, 0, 6, 0,  7, 1, 1, 0, 300.,  35., 10)," +
			"(1, 417, 2, 2, 0, 6, 0,  7, 1, 2, 0, 300.,  25., 10)," +
			"(1, 418, 2, 2, 0, 6, 0,  7, 2, 1, 0, 300.,  28., 10)," +
			"(1, 419, 2, 2, 0, 6, 0,  7, 2, 2, 0, 300.,  18., 10)," +
			"(1, 420, 2, 2, 0, 6, 0,  8, 0, 0, 0, 100., -16., 10)," +
			"(1, 421, 2, 2, 0, 6, 0,  9, 2, 0, 0,  35., -29., 10)," +
			"(1, 422, 2, 2, 0, 6, 0, 10, 2, 0, 0,  35., -34., 10)," +
			"(1, 423, 2, 2, 0, 6, 0, 11, 2, 0, 0,  35., -23., 10)," +
			"(1, 424, 2, 2, 0, 6, 0, 12, 2, 0, 0, 100., -33., 10)," +
			"(1, 425, 2, 2, 0, 6, 0, 14, 2, 0, 0, 100., -28., 10)," +
			"(1, 426, 2, 2, 0, 6, 0, 15, 2, 0, 0, 125., -10., 10)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// Protection of Canadian LPTV/translator DTV from ...

		// ... full-service DTV.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 427, 2, 5, 1, 1, 1,  6, 0, 0, 0, 100., -28., 10)," +
			"(1, 428, 2, 5, 1, 1, 1,  7, 0, 0, 0, 300.,  15., 10)," +
			"(1, 429, 2, 5, 1, 1, 1,  8, 0, 0, 0, 100., -26., 10)");

		// ... full-service NTSC.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 430, 2, 5, 1, 2, 0,  6, 0, 0, 0, 100., -48., 10)," +
			"(1, 431, 2, 5, 1, 2, 0,  7, 0, 0, 0, 300.,   2., 10)," +
			"(1, 432, 2, 5, 1, 2, 0,  8, 0, 0, 0, 100., -49., 10)");

		// ... Class A DTV.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 433, 2, 5, 1, 3, 1,  6, 0, 0, 3, 100., -28., 10)," +
			"(1, 434, 2, 5, 1, 3, 1,  6, 0, 0, 2, 100., -12., 10)," +
			"(1, 435, 2, 5, 1, 3, 1,  6, 0, 0, 1, 100.,  -7., 10)," +
			"(1, 436, 2, 5, 1, 3, 1,  7, 0, 0, 0, 300.,  15., 10)," +
			"(1, 437, 2, 5, 1, 3, 1,  8, 0, 0, 3, 100., -26., 10)," +
			"(1, 438, 2, 5, 1, 3, 1,  8, 0, 0, 2, 100., -12., 10)," +
			"(1, 439, 2, 5, 1, 3, 1,  8, 0, 0, 1, 100.,  -7., 10)");

		// ... Class A NTSC.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 440, 2, 5, 1, 4, 0,  6, 0, 0, 0, 100., -48., 10)," +
			"(1, 441, 2, 5, 1, 4, 0,  7, 0, 0, 0, 300.,   2., 10)," +
			"(1, 442, 2, 5, 1, 4, 0,  8, 0, 0, 0, 100., -49., 10)");

		// ... LPTV/translator DTV.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 443, 2, 5, 1, 5, 1,  6, 0, 0, 3, 100., -28., 10)," +
			"(1, 444, 2, 5, 1, 5, 1,  6, 0, 0, 2, 100., -12., 10)," +
			"(1, 445, 2, 5, 1, 5, 1,  6, 0, 0, 1, 100.,  -7., 10)," +
			"(1, 446, 2, 5, 1, 5, 1,  7, 0, 0, 0, 300.,  15., 10)," +
			"(1, 447, 2, 5, 1, 5, 1,  8, 0, 0, 3, 100., -26., 10)," +
			"(1, 448, 2, 5, 1, 5, 1,  8, 0, 0, 2, 100., -12., 10)," +
			"(1, 449, 2, 5, 1, 5, 1,  8, 0, 0, 1, 100.,  -7., 10)");

		// ... LPTV/translator NTSC.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 450, 2, 5, 1, 6, 0,  6, 0, 0, 0, 100., -48., 10)," +
			"(1, 451, 2, 5, 1, 6, 0,  7, 0, 0, 0, 300.,   2., 10)," +
			"(1, 452, 2, 5, 1, 6, 0,  8, 0, 0, 0, 100., -49., 10)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// Protection of Canadian LPTV/translator NTSC from ...

		// ... full-service DTV.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 453, 2, 6, 0, 1, 1,  1, 2, 0, 0,  35., -32., 10)," +
			"(1, 454, 2, 6, 0, 1, 1,  2, 2, 0, 0,  35., -35., 10)," +
			"(1, 455, 2, 6, 0, 1, 1,  3, 2, 0, 0,  35., -34., 10)," +
			"(1, 456, 2, 6, 0, 1, 1,  4, 2, 0, 0,  35., -30., 10)," +
			"(1, 457, 2, 6, 0, 1, 1,  5, 2, 0, 0,  35., -24., 10)," +
			"(1, 458, 2, 6, 0, 1, 1,  6, 0, 0, 0, 100., -14., 10)," +
			"(1, 459, 2, 6, 0, 1, 1,  7, 0, 0, 0, 300.,  34., 10)," +
			"(1, 460, 2, 6, 0, 1, 1,  8, 0, 0, 0, 100., -17., 10)," +
			"(1, 461, 2, 6, 0, 1, 1,  9, 2, 0, 0,  35., -28., 10)," +
			"(1, 462, 2, 6, 0, 1, 1, 10, 2, 0, 0,  35., -34., 10)," +
			"(1, 463, 2, 6, 0, 1, 1, 11, 2, 0, 0,  35., -25., 10)," +
			"(1, 464, 2, 6, 0, 1, 1, 12, 2, 0, 0,  35., -43., 10)," +
			"(1, 465, 2, 6, 0, 1, 1, 13, 2, 0, 0,  35., -43., 10)," +
			"(1, 466, 2, 6, 0, 1, 1, 14, 2, 0, 0,  35., -33., 10)," +
			"(1, 467, 2, 6, 0, 1, 1, 15, 2, 0, 0,  35., -31., 10)");

		// ... full-service NTSC.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 468, 2, 6, 0, 2, 0,  2, 2, 0, 0, 100., -30., 10)," +
			"(1, 469, 2, 6, 0, 2, 0,  4, 2, 0, 0,  35., -33., 10)," +
			"(1, 470, 2, 6, 0, 2, 0,  5, 2, 0, 0,  35., -26., 10)," +
			"(1, 471, 2, 6, 0, 2, 0,  6, 0, 0, 0, 100., -16., 10)," +
			"(1, 472, 2, 6, 0, 2, 0,  7, 1, 1, 0, 300.,  35., 10)," +
			"(1, 473, 2, 6, 0, 2, 0,  7, 1, 2, 0, 300.,  25., 10)," +
			"(1, 474, 2, 6, 0, 2, 0,  7, 2, 1, 0, 300.,  28., 10)," +
			"(1, 475, 2, 6, 0, 2, 0,  7, 2, 2, 0, 300.,  18., 10)," +
			"(1, 476, 2, 6, 0, 2, 0,  8, 0, 0, 0, 100., -16., 10)," +
			"(1, 477, 2, 6, 0, 2, 0,  9, 2, 0, 0,  35., -29., 10)," +
			"(1, 478, 2, 6, 0, 2, 0, 10, 2, 0, 0,  35., -34., 10)," +
			"(1, 479, 2, 6, 0, 2, 0, 11, 2, 0, 0,  35., -23., 10)," +
			"(1, 480, 2, 6, 0, 2, 0, 12, 2, 0, 0, 100., -33., 10)," +
			"(1, 481, 2, 6, 0, 2, 0, 14, 2, 0, 0, 100., -28., 10)," +
			"(1, 482, 2, 6, 0, 2, 0, 15, 2, 0, 0, 125., -10., 10)");

		// ... Class A DTV.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 483, 2, 6, 0, 3, 1,  1, 2, 0, 0,  35., -32., 10)," +
			"(1, 484, 2, 6, 0, 3, 1,  2, 2, 0, 0,  35., -35., 10)," +
			"(1, 485, 2, 6, 0, 3, 1,  3, 2, 0, 0,  35., -34., 10)," +
			"(1, 486, 2, 6, 0, 3, 1,  4, 2, 0, 0,  35., -30., 10)," +
			"(1, 487, 2, 6, 0, 3, 1,  5, 2, 0, 0,  35., -24., 10)," +
			"(1, 488, 2, 6, 0, 3, 1,  6, 0, 0, 3, 100., -14., 10)," +
			"(1, 489, 2, 6, 0, 3, 1,  6, 0, 0, 2, 100.,   0., 10)," +
			"(1, 490, 2, 6, 0, 3, 1,  6, 0, 0, 1, 100.,  10., 10)," +
			"(1, 491, 2, 6, 0, 3, 1,  7, 0, 0, 0, 300.,  34., 10)," +
			"(1, 492, 2, 6, 0, 3, 1,  8, 0, 0, 3, 100., -17., 10)," +
			"(1, 493, 2, 6, 0, 3, 1,  8, 0, 0, 2, 100.,   0., 10)," +
			"(1, 494, 2, 6, 0, 3, 1,  8, 0, 0, 1, 100.,  10., 10)," +
			"(1, 495, 2, 6, 0, 3, 1,  9, 2, 0, 0,  35., -28., 10)," +
			"(1, 496, 2, 6, 0, 3, 1, 10, 2, 0, 0,  35., -34., 10)," +
			"(1, 497, 2, 6, 0, 3, 1, 11, 2, 0, 0,  35., -25., 10)," +
			"(1, 498, 2, 6, 0, 3, 1, 12, 2, 0, 0,  35., -43., 10)," +
			"(1, 499, 2, 6, 0, 3, 1, 13, 2, 0, 0,  35., -43., 10)," +
			"(1, 500, 2, 6, 0, 3, 1, 14, 2, 0, 0,  35., -33., 10)," +
			"(1, 501, 2, 6, 0, 3, 1, 15, 2, 0, 0,  35., -31., 10)");

		// ... Class A NTSC.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 502, 2, 6, 0, 4, 0,  2, 2, 0, 0, 100., -30., 10)," +
			"(1, 503, 2, 6, 0, 4, 0,  4, 2, 0, 0,  35., -33., 10)," +
			"(1, 504, 2, 6, 0, 4, 0,  5, 2, 0, 0,  35., -26., 10)," +
			"(1, 505, 2, 6, 0, 4, 0,  6, 0, 0, 0, 100., -16., 10)," +
			"(1, 506, 2, 6, 0, 4, 0,  7, 1, 1, 0, 300.,  35., 10)," +
			"(1, 507, 2, 6, 0, 4, 0,  7, 1, 2, 0, 300.,  25., 10)," +
			"(1, 508, 2, 6, 0, 4, 0,  7, 2, 1, 0, 300.,  28., 10)," +
			"(1, 509, 2, 6, 0, 4, 0,  7, 2, 2, 0, 300.,  18., 10)," +
			"(1, 510, 2, 6, 0, 4, 0,  8, 0, 0, 0, 100., -16., 10)," +
			"(1, 511, 2, 6, 0, 4, 0,  9, 2, 0, 0,  35., -29., 10)," +
			"(1, 512, 2, 6, 0, 4, 0, 10, 2, 0, 0,  35., -34., 10)," +
			"(1, 513, 2, 6, 0, 4, 0, 11, 2, 0, 0,  35., -23., 10)," +
			"(1, 514, 2, 6, 0, 4, 0, 12, 2, 0, 0, 100., -33., 10)," +
			"(1, 515, 2, 6, 0, 4, 0, 14, 2, 0, 0, 100., -28., 10)," +
			"(1, 516, 2, 6, 0, 4, 0, 15, 2, 0, 0, 125., -10., 10)");

		// ... LPTV/translator DTV.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 517, 2, 6, 0, 5, 1,  1, 2, 0, 0,  35., -32., 10)," +
			"(1, 518, 2, 6, 0, 5, 1,  2, 2, 0, 0,  35., -35., 10)," +
			"(1, 519, 2, 6, 0, 5, 1,  3, 2, 0, 0,  35., -34., 10)," +
			"(1, 520, 2, 6, 0, 5, 1,  4, 2, 0, 0,  35., -30., 10)," +
			"(1, 521, 2, 6, 0, 5, 1,  5, 2, 0, 0,  35., -24., 10)," +
			"(1, 522, 2, 6, 0, 5, 1,  6, 0, 0, 3, 100., -14., 10)," +
			"(1, 523, 2, 6, 0, 5, 1,  6, 0, 0, 2, 100.,   0., 10)," +
			"(1, 524, 2, 6, 0, 5, 1,  6, 0, 0, 1, 100.,  10., 10)," +
			"(1, 525, 2, 6, 0, 5, 1,  7, 0, 0, 0, 300.,  34., 10)," +
			"(1, 526, 2, 6, 0, 5, 1,  8, 0, 0, 3, 100., -17., 10)," +
			"(1, 527, 2, 6, 0, 5, 1,  8, 0, 0, 2, 100.,   0., 10)," +
			"(1, 528, 2, 6, 0, 5, 1,  8, 0, 0, 1, 100.,  10., 10)," +
			"(1, 529, 2, 6, 0, 5, 1,  9, 2, 0, 0,  35., -28., 10)," +
			"(1, 530, 2, 6, 0, 5, 1, 10, 2, 0, 0,  35., -34., 10)," +
			"(1, 531, 2, 6, 0, 5, 1, 11, 2, 0, 0,  35., -25., 10)," +
			"(1, 532, 2, 6, 0, 5, 1, 12, 2, 0, 0,  35., -43., 10)," +
			"(1, 533, 2, 6, 0, 5, 1, 13, 2, 0, 0,  35., -43., 10)," +
			"(1, 534, 2, 6, 0, 5, 1, 14, 2, 0, 0,  35., -33., 10)," +
			"(1, 535, 2, 6, 0, 5, 1, 15, 2, 0, 0,  35., -31., 10)");

		// ... LPTV/translator NTSC.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 536, 2, 6, 0, 6, 0,  2, 2, 0, 0, 100., -30., 10)," +
			"(1, 537, 2, 6, 0, 6, 0,  4, 2, 0, 0,  35., -33., 10)," +
			"(1, 538, 2, 6, 0, 6, 0,  5, 2, 0, 0,  35., -26., 10)," +
			"(1, 539, 2, 6, 0, 6, 0,  6, 0, 0, 0, 100., -16., 10)," +
			"(1, 540, 2, 6, 0, 6, 0,  7, 1, 1, 0, 300.,  35., 10)," +
			"(1, 541, 2, 6, 0, 6, 0,  7, 1, 2, 0, 300.,  25., 10)," +
			"(1, 542, 2, 6, 0, 6, 0,  7, 2, 1, 0, 300.,  28., 10)," +
			"(1, 543, 2, 6, 0, 6, 0,  7, 2, 2, 0, 300.,  18., 10)," +
			"(1, 544, 2, 6, 0, 6, 0,  8, 0, 0, 0, 100., -16., 10)," +
			"(1, 545, 2, 6, 0, 6, 0,  9, 2, 0, 0,  35., -29., 10)," +
			"(1, 546, 2, 6, 0, 6, 0, 10, 2, 0, 0,  35., -34., 10)," +
			"(1, 547, 2, 6, 0, 6, 0, 11, 2, 0, 0,  35., -23., 10)," +
			"(1, 548, 2, 6, 0, 6, 0, 12, 2, 0, 0, 100., -33., 10)," +
			"(1, 549, 2, 6, 0, 6, 0, 14, 2, 0, 0, 100., -28., 10)," +
			"(1, 550, 2, 6, 0, 6, 0, 15, 2, 0, 0, 125., -10., 10)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// Mexican rules are idential to U.S. rules.

		db.update(
		"INSERT INTO template_ix_rule SELECT " +
			"template_key," +
			"ix_rule_key + 550," +
			"3," +
			"service_type_key," +
			"signal_type_key," +
			"undesired_service_type_key," +
			"undesired_signal_type_key," +
			"channel_delta_key," +
			"channel_band_key," +
			"frequency_offset," +
			"emission_mask_key," +
			"distance," +
			"required_du," +
			"undesired_time " +
		"FROM template_ix_rule " +
		"WHERE (template_key = 1) AND (service_type_key IN (1,2,3,4,5,6)) AND (country_key = 1)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// Protection of U.S. full-service FM from ...

		// ... full-service FM.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 851, 1, 21, 0, 21, 0, 24, 0, 0, 0, 300.,  20., 10)," +
			"(1, 852, 1, 21, 0, 21, 0, 25, 0, 0, 0, 100.,   6., 10)," +
			"(1, 853, 1, 21, 0, 21, 0, 26, 0, 0, 0,  50., -40., 10)," +
			"(1, 854, 1, 21, 0, 21, 0, 27, 0, 0, 0,  50., -40., 10)," +
			"(1, 855, 1, 21, 0, 21, 0, 23, 0, 0, 0, 100.,   6., 10)," +
			"(1, 856, 1, 21, 0, 21, 0, 22, 0, 0, 0,  50., -40., 10)," +
			"(1, 857, 1, 21, 0, 21, 0, 21, 0, 0, 0,  50., -40., 10)," +
			"(1, 858, 1, 21, 0, 21, 0, 28, 0, 0, 0,  50.,   0., 10)," +
			"(1, 859, 1, 21, 0, 21, 0, 29, 0, 0, 0,  50.,   0., 10)," +
			"(1, 860, 1, 21, 0, 21, 0, 20, 0, 0, 0,  50.,   0., 10)," +
			"(1, 861, 1, 21, 0, 21, 0, 19, 0, 0, 0,  50.,   0., 10)");

		// ... low power FM.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 862, 1, 21, 0, 22, 0, 24, 0, 0, 0, 300.,  20., 10)," +
			"(1, 863, 1, 21, 0, 22, 0, 25, 0, 0, 0, 100.,   6., 10)," +
			"(1, 864, 1, 21, 0, 22, 0, 26, 0, 0, 0,  50., -40., 10)," +
			"(1, 865, 1, 21, 0, 22, 0, 27, 0, 0, 0,  50., -40., 10)," +
			"(1, 866, 1, 21, 0, 22, 0, 23, 0, 0, 0, 100.,   6., 10)," +
			"(1, 867, 1, 21, 0, 22, 0, 22, 0, 0, 0,  50., -40., 10)," +
			"(1, 868, 1, 21, 0, 22, 0, 21, 0, 0, 0,  50., -40., 10)," +
			"(1, 869, 1, 21, 0, 22, 0, 28, 0, 0, 0,  50.,   0., 10)," +
			"(1, 870, 1, 21, 0, 22, 0, 29, 0, 0, 0,  50.,   0., 10)," +
			"(1, 871, 1, 21, 0, 22, 0, 20, 0, 0, 0,  50.,   0., 10)," +
			"(1, 872, 1, 21, 0, 22, 0, 19, 0, 0, 0,  50.,   0., 10)");

		// ... translator/booster FM.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 873, 1, 21, 0, 23, 0, 24, 0, 0, 0, 300.,  20., 10)," +
			"(1, 874, 1, 21, 0, 23, 0, 25, 0, 0, 0, 100.,   6., 10)," +
			"(1, 875, 1, 21, 0, 23, 0, 26, 0, 0, 0,  50., -40., 10)," +
			"(1, 876, 1, 21, 0, 23, 0, 27, 0, 0, 0,  50., -40., 10)," +
			"(1, 877, 1, 21, 0, 23, 0, 23, 0, 0, 0, 100.,   6., 10)," +
			"(1, 878, 1, 21, 0, 23, 0, 22, 0, 0, 0,  50., -40., 10)," +
			"(1, 879, 1, 21, 0, 23, 0, 21, 0, 0, 0,  50., -40., 10)," +
			"(1, 880, 1, 21, 0, 23, 0, 28, 0, 0, 0,  50.,   0., 10)," +
			"(1, 881, 1, 21, 0, 23, 0, 29, 0, 0, 0,  50.,   0., 10)," +
			"(1, 882, 1, 21, 0, 23, 0, 20, 0, 0, 0,  50.,   0., 10)," +
			"(1, 883, 1, 21, 0, 23, 0, 19, 0, 0, 0,  50.,   0., 10)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// Protection of U.S. low-power FM from ...

		// ... full-service FM.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 884, 1, 22, 0, 21, 0, 24, 0, 0, 0, 300.,  20., 10)," +
			"(1, 885, 1, 22, 0, 21, 0, 25, 0, 0, 0, 100.,   6., 10)," +
			"(1, 886, 1, 22, 0, 21, 0, 26, 0, 0, 0,  50., -40., 10)," +
			"(1, 887, 1, 22, 0, 21, 0, 27, 0, 0, 0,  50., -40., 10)," +
			"(1, 888, 1, 22, 0, 21, 0, 23, 0, 0, 0, 100.,   6., 10)," +
			"(1, 889, 1, 22, 0, 21, 0, 22, 0, 0, 0,  50., -40., 10)," +
			"(1, 890, 1, 22, 0, 21, 0, 21, 0, 0, 0,  50., -40., 10)," +
			"(1, 891, 1, 22, 0, 21, 0, 28, 0, 0, 0,  50.,   0., 10)," +
			"(1, 892, 1, 22, 0, 21, 0, 29, 0, 0, 0,  50.,   0., 10)," +
			"(1, 893, 1, 22, 0, 21, 0, 20, 0, 0, 0,  50.,   0., 10)," +
			"(1, 894, 1, 22, 0, 21, 0, 19, 0, 0, 0,  50.,   0., 10)");

		// ... low power FM.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 895, 1, 22, 0, 22, 0, 24, 0, 0, 0, 300.,  20., 10)," +
			"(1, 896, 1, 22, 0, 22, 0, 25, 0, 0, 0, 100.,   6., 10)," +
			"(1, 897, 1, 22, 0, 22, 0, 26, 0, 0, 0,  50., -40., 10)," +
			"(1, 898, 1, 22, 0, 22, 0, 27, 0, 0, 0,  50., -40., 10)," +
			"(1, 899, 1, 22, 0, 22, 0, 23, 0, 0, 0, 100.,   6., 10)," +
			"(1, 900, 1, 22, 0, 22, 0, 22, 0, 0, 0,  50., -40., 10)," +
			"(1, 901, 1, 22, 0, 22, 0, 21, 0, 0, 0,  50., -40., 10)," +
			"(1, 902, 1, 22, 0, 22, 0, 28, 0, 0, 0,  50.,   0., 10)," +
			"(1, 903, 1, 22, 0, 22, 0, 29, 0, 0, 0,  50.,   0., 10)," +
			"(1, 904, 1, 22, 0, 22, 0, 20, 0, 0, 0,  50.,   0., 10)," +
			"(1, 905, 1, 22, 0, 22, 0, 19, 0, 0, 0,  50.,   0., 10)");

		// ... translator/booster FM.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 906, 1, 22, 0, 23, 0, 24, 0, 0, 0, 300.,  20., 10)," +
			"(1, 907, 1, 22, 0, 23, 0, 25, 0, 0, 0, 100.,   6., 10)," +
			"(1, 908, 1, 22, 0, 23, 0, 26, 0, 0, 0,  50., -40., 10)," +
			"(1, 909, 1, 22, 0, 23, 0, 27, 0, 0, 0,  50., -40., 10)," +
			"(1, 910, 1, 22, 0, 23, 0, 23, 0, 0, 0, 100.,   6., 10)," +
			"(1, 911, 1, 22, 0, 23, 0, 22, 0, 0, 0,  50., -40., 10)," +
			"(1, 912, 1, 22, 0, 23, 0, 21, 0, 0, 0,  50., -40., 10)," +
			"(1, 913, 1, 22, 0, 23, 0, 28, 0, 0, 0,  50.,   0., 10)," +
			"(1, 914, 1, 22, 0, 23, 0, 29, 0, 0, 0,  50.,   0., 10)," +
			"(1, 915, 1, 22, 0, 23, 0, 20, 0, 0, 0,  50.,   0., 10)," +
			"(1, 916, 1, 22, 0, 23, 0, 19, 0, 0, 0,  50.,   0., 10)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// Protection of U.S. translator/booster FM from ...

		// ... full-service FM.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 917, 1, 23, 0, 21, 0, 24, 0, 0, 0, 300.,  20., 10)," +
			"(1, 918, 1, 23, 0, 21, 0, 25, 0, 0, 0, 100.,   6., 10)," +
			"(1, 919, 1, 23, 0, 21, 0, 26, 0, 0, 0,  50., -40., 10)," +
			"(1, 920, 1, 23, 0, 21, 0, 27, 0, 0, 0,  50., -40., 10)," +
			"(1, 921, 1, 23, 0, 21, 0, 23, 0, 0, 0, 100.,   6., 10)," +
			"(1, 922, 1, 23, 0, 21, 0, 22, 0, 0, 0,  50., -40., 10)," +
			"(1, 923, 1, 23, 0, 21, 0, 21, 0, 0, 0,  50., -40., 10)," +
			"(1, 924, 1, 23, 0, 21, 0, 28, 0, 0, 0,  50.,   0., 10)," +
			"(1, 925, 1, 23, 0, 21, 0, 29, 0, 0, 0,  50.,   0., 10)," +
			"(1, 926, 1, 23, 0, 21, 0, 20, 0, 0, 0,  50.,   0., 10)," +
			"(1, 927, 1, 23, 0, 21, 0, 19, 0, 0, 0,  50.,   0., 10)");

		// ... low power FM.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 928, 1, 23, 0, 22, 0, 24, 0, 0, 0, 300.,  20., 10)," +
			"(1, 929, 1, 23, 0, 22, 0, 25, 0, 0, 0, 100.,   6., 10)," +
			"(1, 930, 1, 23, 0, 22, 0, 26, 0, 0, 0,  50., -40., 10)," +
			"(1, 931, 1, 23, 0, 22, 0, 27, 0, 0, 0,  50., -40., 10)," +
			"(1, 932, 1, 23, 0, 22, 0, 23, 0, 0, 0, 100.,   6., 10)," +
			"(1, 933, 1, 23, 0, 22, 0, 22, 0, 0, 0,  50., -40., 10)," +
			"(1, 934, 1, 23, 0, 22, 0, 21, 0, 0, 0,  50., -40., 10)," +
			"(1, 935, 1, 23, 0, 22, 0, 28, 0, 0, 0,  50.,   0., 10)," +
			"(1, 936, 1, 23, 0, 22, 0, 29, 0, 0, 0,  50.,   0., 10)," +
			"(1, 937, 1, 23, 0, 22, 0, 20, 0, 0, 0,  50.,   0., 10)," +
			"(1, 938, 1, 23, 0, 22, 0, 19, 0, 0, 0,  50.,   0., 10)");

		// ... translator/booster FM.

		db.update("INSERT INTO template_ix_rule VALUES " +
			"(1, 939, 1, 23, 0, 23, 0, 24, 0, 0, 0, 300.,  20., 10)," +
			"(1, 940, 1, 23, 0, 23, 0, 25, 0, 0, 0, 100.,   6., 10)," +
			"(1, 941, 1, 23, 0, 23, 0, 26, 0, 0, 0,  50., -40., 10)," +
			"(1, 942, 1, 23, 0, 23, 0, 27, 0, 0, 0,  50., -40., 10)," +
			"(1, 943, 1, 23, 0, 23, 0, 23, 0, 0, 0, 100.,   6., 10)," +
			"(1, 944, 1, 23, 0, 23, 0, 22, 0, 0, 0,  50., -40., 10)," +
			"(1, 945, 1, 23, 0, 23, 0, 21, 0, 0, 0,  50., -40., 10)," +
			"(1, 946, 1, 23, 0, 23, 0, 28, 0, 0, 0,  50.,   0., 10)," +
			"(1, 947, 1, 23, 0, 23, 0, 29, 0, 0, 0,  50.,   0., 10)," +
			"(1, 948, 1, 23, 0, 23, 0, 20, 0, 0, 0,  50.,   0., 10)," +
			"(1, 949, 1, 23, 0, 23, 0, 19, 0, 0, 0,  50.,   0., 10)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// Canadian and Mexican FM rules are identical to U.S. rules.

		db.update(
		"INSERT INTO template_ix_rule SELECT " +
			"template_key," +
			"ix_rule_key + 99," +
			"2," +
			"service_type_key," +
			"signal_type_key," +
			"undesired_service_type_key," +
			"undesired_signal_type_key," +
			"channel_delta_key," +
			"channel_band_key," +
			"frequency_offset," +
			"emission_mask_key," +
			"distance," +
			"required_du," +
			"undesired_time " +
		"FROM template_ix_rule " +
		"WHERE (template_key = 1) AND (service_type_key IN (21,22,23)) AND (country_key = 1)");

		db.update(
		"INSERT INTO template_ix_rule SELECT " +
			"template_key," +
			"ix_rule_key + 198," +
			"3," +
			"service_type_key," +
			"signal_type_key," +
			"undesired_service_type_key," +
			"undesired_signal_type_key," +
			"channel_delta_key," +
			"channel_band_key," +
			"frequency_offset," +
			"emission_mask_key," +
			"distance," +
			"required_du," +
			"undesired_time " +
		"FROM template_ix_rule " +
		"WHERE (template_key = 1) AND (service_type_key IN (21,22,23)) AND (country_key = 1)");

		// End of default rules.
		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

		// Parameters.

		db.update("DELETE FROM parameter_group");
		db.update("DELETE FROM parameter");
		db.update("DELETE FROM template_parameter_data WHERE template_key = 1");
		db.update("DELETE FROM parameter_study_type");

		db.update("INSERT INTO parameter_group VALUES " +
			"(13, 1, 'Analysis', 0)," +
			"(2, 2, 'CDBS/LMS', 0)," +
			"(11, 3, 'Search', 0)," +
			"(4, 4, 'Contours', 0)," +
			"(14, 5, 'FCC Contours', 0)," +
			"(16, 6, 'L-R Contours', 0)," +
			"(5, 7, 'TV Replication', 0)," +
			"(3, 8, 'Patterns', 0)," +
			"(6, 9, 'Propagation', 0)," +
			"(7, 10, 'Service', 309)," +
			"(8, 11, 'Clutter', 249)," +
			"(12, 12, 'TV IX Check', 0)," +
			"(9, 13, 'Wireless', 0)," +
			"(10, 14, 'TV6 <-> FM', 0)," +
			"(15, 15, 'Scenario', 0)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// Analysis

		db.update("INSERT INTO parameter VALUES " +
			"(2, 13, 1, 'Grid type', 'pickfrom:1:Local:2:Global', '', '', '<HTML>Type of study cell grid:<BR>Local - Each station has an independent grid used only for that station.<BR>Global - A single uniform grid is shared by all stations.</HTML>', false)," +
			"(4, 13, 2, 'Cell size', 'decimal:0.1:10', 'km', '', '<HTML>Target edge size of study grid cells.<BR>Actual size will vary, cell dimensions are always integral arc-seconds.</HTML>', false)," +
			"(5, 13, 3, 'Study point location', 'pickfrom:1:Population centroid:2:Cell center:3:Largest population:4:All population', '', '', '<HTML>Method for defining study point location in each grid cell:<BR>Population centroid - Single study point at the population-weighted centroid of all Census points in the cell.<BR>Cell center - Single study point at the geographic center of the cell.<BR>Largest population - Single study point at the location of the Census point with the largest population in the cell.<BR>All population - Every Census point creates a study point with cell area proportioned by population.<BR>When only a single study point is created it represents the total area and population in the cell.<BR>When a cell contains no Census points it has a single study point at the geographic center.</HTML>', false)," +
			"(6, 13, 4, 'Move study point to nearest Census point', 'option', '', '', '<HTML>For centroid and center methods, use the location of the Census point nearest to the centroid or center.</HTML>', false)," +
			"(40, 13, 5, 'U.S. population', 'pickfrom:2010:2010:2000:2000:0:None', '', '', '<HTML>U.S. Census population database.<BR>If \"None\" is selected, area will also not be reported separately.</HTML>', false)," +
			"(42, 13, 6, 'Canadian population', 'pickfrom:2016:2016:2011:2011:2006:2006:0:None', '', '', '<HTML>Canadian Census population database.<BR>If \"None\" is selected, area will also not be reported separately.</HTML>', false)," +
			"(44, 13, 7, 'Mexican population', 'pickfrom:2010:2010:0:None', '', '', '<HTML>Mexican Census population database.<BR>If \"None\" is selected, area will also not be reported separately.</HTML>', false)," +
			"(46, 13, 8, 'Round population coordinates', 'option', '', 'U.S.:Canada:Mexico', '<HTML>Round all Census centroid coordinates to the nearest integer arc-second.</HTML>', false)," +
			"(199, 13, 9, 'Default service area method', 'pickfrom:1:FCC curves:8:FCC plus distance:9:FCC plus percent:2:Longley-Rice percent above:3:Longley-Rice length above:4:Longley-rice length below:10:Constant distance', '', 'U.S.:Canada:Mexico', '<HTML>Default method for determining service areas for TV and FM stations:<BR>FCC curves - Signal level contour using FCC propagation curves and all other parameters in this section.<BR>FCC curves plus distance - FCC curves contour plus an additional distance on each radial.<BR>FCC curves plus percent - FCC curves contour plus an additional percentage on each radial.<BR>Longley-Rice percent above - Contour based on Longley-Rice propagation, greatest distance at which specified percentage of preceding points along radial are above the contour level.<BR>Longley-Rice length above - Distance at which all following contiguous runs of points above contour level are shorter than specified length.<BR>Longley-Rice length below - Distance at which all preceding contiguous runs of points below contour level are shorter than specified length.<BR>Constant distance - Fixed-radius circle around station location.</HTML>', false)," +
			"(201, 13, 10, 'Distance or percent for default method', 'decimal:0:500', 'km or %', 'U.S.:Canada:Mexico', '<HTML>Distance, run length, or percentage to use with contour method selected above.</HTML>', false)," +
			"(215, 13, 11, 'Maximum desired signal distance', 'decimal:100:500', 'km', '', '<HTML>Maximum station-to-point distance for desired signal analysis in all modes.<BR>Restricts the maximum size of a Longley-Rice contour, also sets maximum calculation distance in unrestricted grid mode or points mode.</HTML>', false)," +
			"(306, 13, 12, 'Cap TV D/U ramp function', 'option', '', '', '<HTML>Set a cap on the amount by which the required D/U for co-channel TV interference to digital TV is increased as the desired signal nears the service threshold.</HTML>', false)," +
			"(307, 13, 13, 'TV D/U ramp function cap', 'decimal:0:36.4', 'dB', '', '<HTML>Cap value for digital TV co-channel D/U adjustment.</HTML>', false)," +
			"(248, 13, 14, 'Adjust FM D/U for IBOC', 'option', '', '', '<HTML>Adjust FM adjacent-channel required D/U when undesired station is operating IBOC digital.</HTML>', false)," +
			"(193, 13, 15, 'Include DTS self-interference', 'option', '', '', '<HTML>Evaluate self-interference in a DTS operation.</HTML>', false)," +
			"(194, 13, 16, 'Propagation speed', 'decimal:0.28:0.3', 'km/µS', '', '<HTML>Signal propagation speed for DTS arrival time calculation, in kilometers per microsecond.</HTML>', false)," +
			"(195, 13, 17, 'Pre-arrival time limit', 'decimal:-100:0', 'µS', '', '<HTML>Maximum time difference for no DTS self-interference when undesired arrives before desired, in microseconds.</HTML>', false)," +
			"(196, 13, 18, 'Post-arrival time limit', 'decimal:0:100', 'µS', '', '<HTML>Maximum time difference for no DTS self-interference when undesired arrives after desired, in microseconds.</HTML>', false)," +
			"(197, 13, 19, 'Required D/U', 'decimal:-50:50', 'dB', '', '<HTML>Required D/U for no DTS self-interference when outside the arrival time window.</HTML>', false)," +
			"(154, 13, 20, 'Undesired % time', 'decimal:0.01:99.99', '%', '', '<HTML>Propagation model percent time for self-interference undesired signals.</HTML>', false)");

		db.update("INSERT INTO template_parameter_data VALUES " +
			"(1, 2, 0, '2')," +
			"(1, 4, 0, '2')," +
			"(1, 5, 0, '1')," +
			"(1, 6, 0, '0')," +
			"(1, 40, 0, '2010')," +
			"(1, 42, 0, '2011')," +
			"(1, 44, 0, '2010')," +
			"(1, 46, 0, '0'),(1, 46, 1, '0'),(1, 46, 2, '0')," +
			"(1, 199, 0, '1'),(1, 199, 1, '1'),(1, 199, 2, '1')," +
			"(1, 201, 0, '0'),(1, 201, 1, '0'),(1, 201, 2, '0')," +
			"(1, 215, 0, '300')," +
			"(1, 306, 0, '0')," +
			"(1, 307, 0, '8')," +
			"(1, 248, 0, '0')," +
			"(1, 193, 0, '0')," +
			"(1, 194, 0, '0.2997915')," +
			"(1, 195, 0, '-60')," +
			"(1, 196, 0, '60')," +
			"(1, 197, 0, '15')," +
			"(1, 154, 0, '10')");

		db.update("INSERT INTO parameter_study_type VALUES " +
			"(2, 1),(2, 2)," +
			"(4, 1),(4, 2),(4, 3),(4, 4),(4, 5)," +
			"(5, 1),(5, 2),(5, 3),(5, 4),(5, 5)," +
			"(6, 1),(6, 2),(6, 3),(6, 4),(6, 5)," +
			"(40, 1),(40, 2),(40, 3),(40, 4),(40, 5)," +
			"(42, 1),(42, 2),(42, 3),(42, 4),(42, 5)," +
			"(44, 1),(44, 2),(44, 3),(44, 4),(44, 5)," +
			"(46, 1),(46, 2)," +
			"(199, 1),(199, 2),(199, 3),(199, 4),(199, 5)," +
			"(201, 1),(201, 2),(201, 3),(201, 4),(201, 5)," +
			"(215, 1),(215, 2),(215, 3),(215, 4),(215, 5)," +
			"(306, 1),(306, 2),(306, 3),(306, 5)," +
			"(307, 1),(307, 2),(307, 3),(307, 5)," +
			"(248, 4),(248, 5)," +
			"(193, 1),(193, 2),(193, 3),(193, 5)," +
			"(194, 1),(194, 2),(194, 3),(194, 5)," +
			"(195, 1),(195, 2),(195, 3),(195, 5)," +
			"(196, 1),(196, 2),(196, 3),(196, 5)," +
			"(197, 1),(197, 2),(197, 3),(197, 5)," +
			"(154, 1),(154, 2),(154, 3),(154, 5)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// CDBS/LMS

		db.update("INSERT INTO parameter VALUES " +
			"(18, 2, 1, 'Respect DA flag', 'option', '', '', '<HTML>Use directional pattern data only if the DA indicator flag is set.<BR>If not selected, pattern data is used if present regardless of the flag.</HTML>', false)," +
			"(334, 2, 2, 'HAAT radial count', 'integer:4:360', '', 'U.S.:Canada:Mexico', '<HTML>Number of radials used to calculate HAAT when deriving AMSL height or verifying database HAAT for TV and FM stations.</HTML>', false)," +
			"(220, 2, 3, 'Mexican TV digital ERP, VHF low', 'decimal:1:2000', 'kW', '', '<HTML>Default for Mexican full-service TV records with missing data, ERP for digital VHF low band.</HTML>', false)," +
			"(221, 2, 4, 'Mexican TV digital HAAT, VHF low', 'decimal:30:800', 'm', '', '<HTML>Default for Mexican full-service TV records with missing data, HAAT for digital VHF low band.</HTML>', false)," +
			"(222, 2, 5, 'Mexican TV digital ERP, VHF high', 'decimal:1:2000', 'kw', '', '<HTML>Default for Mexican full-service TV records with missing data, ERP for digital VHF high band.</HTML>', false)," +
			"(223, 2, 6, 'Mexican TV digital HAAT, VHF high', 'decimal:30:800', 'm', '', '<HTML>Default for Mexican full-service TV records with missing data, HAAT for digital VHF high band.</HTML>', false)," +
			"(224, 2, 7, 'Mexican TV digital ERP, UHF', 'decimal:1:2000', 'kW', '', '<HTML>Default for Mexican full-service TV records with missing data, ERP for digital UHF.</HTML>', false)," +
			"(225, 2, 8, 'Mexican TV digital HAAT, UHF', 'decimal:30:800', 'm', '', '<HTML>Default for Mexican full-service TV records with missing data, HAAT for digital UHF.</HTML>', false)," +
			"(226, 2, 9, 'Mexican TV analog ERP, VHF low', 'decimal:1:6000', 'kW', '', '<HTML>Default for Mexican full-service TV records with missing data, ERP for analog VHF low band.</HTML>', false)," +
			"(227, 2, 10, 'Mexican TV analog HAAT, VHF low', 'decimal:30:800', 'm', '', '<HTML>Default for Mexican full-service TV records with missing data, HAAT for analog VHF low band.</HTML>', false)," +
			"(228, 2, 11, 'Mexican TV analog ERP, VHF high', 'decimal:1:6000', 'kW', '', '<HTML>Default for Mexican full-service TV records with missing data, ERP for analog VHF high band.</HTML>', false)," +
			"(229, 2, 12, 'Mexican TV analog HAAT, VHF high', 'decimal:30:800', 'm', '', '<HTML>Default for Mexican full-service TV records with missing data, HAAT for analog VHF high band.</HTML>', false)," +
			"(230, 2, 13, 'Mexican TV analog ERP, UHF', 'decimal:1:6000', 'kW', '', '<HTML>Default for Mexican full-service TV records with missing data, ERP for analog UHF.</HTML>', false)," +
			"(231, 2, 14, 'Mexican TV analog HAAT, UHF', 'decimal:30:800', 'm', '', '<HTML>Default for Mexican full-service TV records with missing data, HAAT for analog UHF.</HTML>', false)," +
			"(324, 2, 15, 'First TV channel', 'integer:2:69', '', '', '<HTML>Lowest allowed TV channel number, database records below this are ignored.</HTML>', false)," +
			"(325, 2, 16, 'Last TV channel', 'integer:2:69', '', '', '<HTML>Highest allowed TV channel number, database records above this are ignored.</HTML>', false)," +
			"(232, 2, 17, 'Mexican FM ERP, class A', 'decimal:1:200', 'kW', '', '<HTML>Default for Mexican full-service FM records with missing data, ERP for class A.</HTML>', false)," +
			"(233, 2, 18, 'Mexican FM HAAT, class A', 'decimal:30:800', 'm', '', '<HTML>Default for Mexican full-service FM records with missing data, HAAT for class A.</HTML>', false)," +
			"(234, 2, 19, 'Mexican FM ERP, class B', 'decimal:1:200', 'kW', '', '<HTML>Default for Mexican full-service FM records with missing data, ERP for class B.</HTML>', false)," +
			"(235, 2, 20, 'Mexican FM HAAT, class B', 'decimal:30:800', 'm', '', '<HTML>Default for Mexican full-service FM records with missing data, HAAT for class B.</HTML>', false)," +
			"(236, 2, 21, 'Mexican FM ERP, class B1', 'decimal:1:200', 'kW', '', '<HTML>Default for Mexican full-service FM records with missing data, ERP for class B1.</HTML>', false)," +
			"(237, 2, 22, 'Mexican FM HAAT, class B1', 'decimal:30:800', 'm', '', '<HTML>Default for Mexican full-service FM records with missing data, HAAT for class B1.</HTML>', false)," +
			"(238, 2, 23, 'Mexican FM ERP, class C', 'decimal:1:200', 'kW', '', '<HTML>Default for Mexican full-service FM records with missing data, ERP for class C.</HTML>', false)," +
			"(239, 2, 24, 'Mexican FM HAAT, class C', 'decimal:30:800', 'm', '', '<HTML>Default for Mexican full-service FM records with missing data, HAAT for class C.</HTML>', false)," +
			"(240, 2, 25, 'Mexican FM ERP, class C1', 'decimal:1:200', 'kW', '', '<HTML>Default for Mexican full-service FM records with missing data, ERP for class C1.</HTML>', false)," +
			"(241, 2, 26, 'Mexican FM HAAT, class C1', 'decimal:30:800', 'm', '', '<HTML>Default for Mexican full-service FM records with missing data, HAAT for class C1.</HTML>', false)");

		db.update("INSERT INTO template_parameter_data VALUES " +
			"(1, 18, 0, '0')," +
			"(1, 334, 0, '8'),(1, 334, 1, '8'),(1, 334, 2, '8')," +
			"(1, 220, 0, '45')," +
			"(1, 221, 0, '305')," +
			"(1, 222, 0, '160')," +
			"(1, 223, 0, '305')," +
			"(1, 224, 0, '1000')," +
			"(1, 225, 0, '365')," +
			"(1, 226, 0, '100')," +
			"(1, 227, 0, '305')," +
			"(1, 228, 0, '316')," +
			"(1, 229, 0, '305')," +
			"(1, 230, 0, '5000')," +
			"(1, 231, 0, '610')," +
			"(1, 324, 0, '2')," +
			"(1, 325, 0, '69')," +
			"(1, 232, 0, '6')," +
			"(1, 233, 0, '100')," +
			"(1, 234, 0, '50')," +
			"(1, 235, 0, '150')," +
			"(1, 236, 0, '25')," +
			"(1, 237, 0, '100')," +
			"(1, 238, 0, '100')," +
			"(1, 239, 0, '600')," +
			"(1, 240, 0, '100')," +
			"(1, 241, 0, '299')");

		db.update("INSERT INTO parameter_study_type VALUES " +
			"(18, 1),(18, 2),(18, 3),(18, 4),(18, 5)," +
			"(334, 1),(334, 2),(334, 3),(334, 4),(334, 5)," +
			"(220, 1),(220, 2),(220, 3),(220, 5)," +
			"(221, 1),(221, 2),(221, 3),(221, 5)," +
			"(222, 1),(222, 2),(222, 3),(222, 5)," +
			"(223, 1),(223, 2),(223, 3),(223, 5)," +
			"(224, 1),(224, 2),(224, 3),(224, 5)," +
			"(225, 1),(225, 2),(225, 3),(225, 5)," +
			"(226, 1),(226, 2),(226, 3),(226, 5)," +
			"(227, 1),(227, 2),(227, 3),(227, 5)," +
			"(228, 1),(228, 2),(228, 3),(228, 5)," +
			"(229, 1),(229, 2),(229, 3),(229, 5)," +
			"(230, 1),(230, 2),(230, 3),(230, 5)," +
			"(231, 1),(231, 2),(231, 3),(231, 5)," +
			"(324, 1),(324, 2),(324, 3),(324, 5)," +
			"(325, 1),(325, 2),(325, 3),(325, 5)," +
			"(232, 4),(232, 5)," +
			"(233, 4),(233, 5)," +
			"(234, 4),(234, 5)," +
			"(235, 4),(235, 5)," +
			"(236, 4),(236, 5)," +
			"(237, 4),(237, 5)," +
			"(238, 4),(238, 5)," +
			"(239, 4),(239, 5)," +
			"(240, 4),(240, 5)," +
			"(241, 4),(241, 5)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// Search

		db.update("INSERT INTO parameter VALUES " +
			"(326, 11, 1, 'Check individual DTS transmitter distances', 'option', '', '', '<HTML>For distance checks involving a DTS station, use the coordinates of the nearest individal DTS transmitter.<BR>When not selected, distance checks always use the DTS reference point coordinates.</HTML>', false)," +
			"(212, 11, 2, 'Co-channel MX distance', 'decimal:0:100', 'km', '', '<HTML>Distance within which co-channel stations are considered mutually-exclusive regardless of other conditions.<BR>Set this to 0 to disable the co-channel distance check.</HTML>', false)," +
			"(335, 11, 3, 'Rule limit extra distance less than low ERP', 'decimal:50:200', 'km', '', '<HTML>Extra distance added to interference rule limits for station-to-station distance checks.<BR>Undesired stations beyond the distance are not checked for station-to-cell distance.<BR>Distance varies with peak ERP, enter distances and ERPs in following parameters.<BR>ERPs apply to full-service UHF digital, other cases are derived using differences in service contour levels.</HTML>', false)," +
			"(336, 11, 4, 'Rule limit low ERP', 'decimal:0.001:1000', 'kW', '', '<HTML>See above.</HTML>', false)," +
			"(337, 11, 5, 'Rule limit extra distance low to medium ERP', 'decimal:50:200', 'km', '', '<HTML>See above.</HTML>', false)," +
			"(338, 11, 6, 'Rule limit medium ERP', 'decimal:0.001:1000', 'kW', '', '<HTML>See above.</HTML>', false)," +
			"(339, 11, 7, 'Rule limit extra distance medium to high ERP', 'decimal:50:200', 'km', '', '<HTML>See above.</HTML>', false)," +
			"(340, 11, 8, 'Rule limit high ERP', 'decimal:0.001:1000', 'kW', '', '<HTML>See above.</HTML>', false)," +
			"(210, 11, 9, 'Rule limit extra distance greater than high ERP', 'decimal:50:200', 'km', '', '<HTML>See above.</HTML>', false)," +
			"(189, 11, 10, 'Use maximum signal distance as rule extra', 'option', '', '', '<HTML>Use the maximum desired signal calculation distance as the rule limit extra for all conditions, ignore other parameters.</HTML>', false)," +
			"(200, 11, 11, 'Spherical earth distance', 'decimal:110:112', 'km/deg', '', '<HTML>Spherical earth surface distance in kilometers per degree of arc length.</HTML>', false)");

		db.update("INSERT INTO template_parameter_data VALUES " +
			"(1, 326, 0, '0')," +
			"(1, 212, 0, '5')," +
			"(1, 335, 0, '72')," +
			"(1, 336, 0, '0.2')," +
			"(1, 337, 0, '95')," +
			"(1, 338, 0, '1.5')," +
			"(1, 339, 0, '120')," +
			"(1, 340, 0, '15')," +
			"(1, 210, 0, '163')," +
			"(1, 189, 0, '0')," +
			"(1, 200, 0, '111.15')");

		db.update("INSERT INTO parameter_study_type VALUES " +
			"(326, 1),(326, 2),(326, 3),(326, 5)," +
			"(212, 1),(212, 2),(212, 3),(212, 4),(212, 5)," +
			"(335, 1),(335, 2),(335, 3),(335, 4),(335, 5)," +
			"(336, 1),(336, 2),(336, 3),(336, 4),(336, 5)," +
			"(337, 1),(337, 2),(337, 3),(337, 4),(337, 5)," +
			"(338, 1),(338, 2),(338, 3),(338, 4),(338, 5)," +
			"(339, 1),(339, 2),(339, 3),(339, 4),(339, 5)," +
			"(340, 1),(340, 2),(340, 3),(340, 4),(340, 5)," +
			"(210, 1),(210, 2),(210, 3),(210, 4),(210, 5)," +
			"(189, 1),(189, 2),(189, 3),(189, 4),(189, 5)," +
			"(200, 1),(200, 2),(200, 3),(200, 4),(200, 5)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// Contours

		db.update("INSERT INTO parameter VALUES " +
			"(122, 4, 1, 'Contour radial count', 'integer:4:360', '', 'U.S.:Canada:Mexico', '<HTML>Number of radials to use for contour projections.</HTML>', false)," +
			"(80, 4, 2, 'Digital TV full-service contour, VHF low', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Contour level, digital full-service VHF low band.</HTML>', false)," +
			"(82, 4, 3, 'Digital TV full-service contour, VHF high', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Contour level, digital full-service VHF high band.</HTML>', false)," +
			"(84, 4, 4, 'Digital TV full-service contour, UHF', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Contour level, digital full-service UHF (may be dipole-adjusted).</HTML>', false)," +
			"(86, 4, 5, 'Digital TV Class A/LPTV contour, VHF low', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Contour level, digital LPTV/Class A VHF low band.</HTML>', false)," +
			"(88, 4, 6, 'Digital TV Class A/LPTV contour, VHF high', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Contour level, digital LPTV/Class A VHF high band.</HTML>', false)," +
			"(90, 4, 7, 'Digital TV Class A/LPTV contour, UHF', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Contour level, digital LPTV/Class A UHF (may be dipole-adjusted).</HTML>', false)," +
			"(92, 4, 8, 'Analog TV full-service contour, VHF low', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Contour level, analog full-service VHF low band.</HTML>', false)," +
			"(94, 4, 9, 'Analog TV full-service contour, VHF high', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Contour level, analog full-service VHF high band.</HTML>', false)," +
			"(96, 4, 10, 'Analog TV full-service contour, UHF', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Contour level, analog full-service UHF (may be dipole-adjusted).</HTML>', false)," +
			"(98, 4, 11, 'Analog TV Class A/LPTV contour, VHF low', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Contour level, analog LPTV/Class A VHF low band.</HTML>', false)," +
			"(100, 4, 12, 'Analog TV Class A/LPTV contour, VHF high', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Contour level, analog LPTV/Class A VHF high band.</HTML>', false)," +
			"(102, 4, 13, 'Analog TV Class A/LPTV contour, UHF', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Contour level, analog LPTV/Class A UHF (may be dipole-adjusted).</HTML>', false)," +
			"(104, 4, 14, 'Use UHF dipole adjustment', 'option', '', 'U.S.:Canada:Mexico', '<HTML>Apply dipole adjustment to TV UHF contour levels.</HTML>', false)," +
			"(106, 4, 15, 'Dipole center frequency', 'decimal:470:700', 'MHz', 'U.S.:Canada:Mexico', '<HTML>Center frequency for dipole adjustment of TV UHF contour levels.</HTML>', false)," +
			"(357, 4, 16, 'FM full-service contour', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Contour level, FM full-service for most commercial-band classes.</HTML>', false)," +
			"(358, 4, 17, 'FM full-service contour, class B', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Contour level, FM full-service class B.</HTML>', false)," +
			"(359, 4, 18, 'FM full-service contour, class B1', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Contour level, FM full-service class B1.</HTML>', false)," +
			"(360, 4, 19, 'FM NCE contour', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Contour level, FM non-commercial/educational band.</HTML>', false)," +
			"(361, 4, 20, 'LPFM contour', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Contour level, low-power FM.</HTML>', false)," +
			"(362, 4, 21, 'FM translator contour', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Contour level, FM translator.</HTML>', false)," +
			"(124, 4, 22, 'TV contour maximum distance, VHF low', 'decimal:0:200', 'km', 'U.S.:Canada:Mexico', '<HTML>Maximum contour distance for TV, VHF low band and FM.  Use 0 for no limit.</HTML>', false)," +
			"(126, 4, 23, 'TV contour maximum distance, VHF high', 'decimal:0:200', 'km', 'U.S.:Canada:Mexico', '<HTML>Maximum contour distance for TV, VHF high band.  Use 0 for no limit.</HTML>', false)," +
			"(128, 4, 24, 'TV contour maximum distance, UHF', 'decimal:0:200', 'km', 'U.S.:Canada:Mexico', '<HTML>Maximum contour distance for TV, UHF.  Use 0 for no limit.</HTML>', false)");

		db.update("INSERT INTO template_parameter_data VALUES " +
			"(1, 122, 0, '360'),(1, 122, 1, '360'),(1, 122, 2, '360')," +
			"(1, 80, 0, '28'),(1, 80, 1, '28'),(1, 80, 2, '28')," +
			"(1, 82, 0, '36'),(1, 82, 1, '36'),(1, 82, 2, '36')," +
			"(1, 84, 0, '41'),(1, 84, 1, '41'),(1, 84, 2, '41')," +
			"(1, 86, 0, '43'),(1, 86, 1, '43'),(1, 86, 2, '43')," +
			"(1, 88, 0, '48'),(1, 88, 1, '48'),(1, 88, 2, '48')," +
			"(1, 90, 0, '51'),(1, 90, 1, '51'),(1, 90, 2, '51')," +
			"(1, 92, 0, '47'),(1, 92, 1, '47'),(1, 92, 2, '47')," +
			"(1, 94, 0, '56'),(1, 94, 1, '56'),(1, 94, 2, '56')," +
			"(1, 96, 0, '64'),(1, 96, 1, '64'),(1, 96, 2, '64')," +
			"(1, 98, 0, '62'),(1, 98, 1, '62'),(1, 98, 2, '62')," +
			"(1, 100, 0, '68'),(1, 100, 1, '68'),(1, 100, 2, '68')," +
			"(1, 102, 0, '74'),(1, 102, 1, '74'),(1, 102, 2, '74')," +
			"(1, 104, 0, '1'),(1, 104, 1, '1'),(1, 104, 2, '0')," +
			"(1, 106, 0, '615'),(1, 106, 1, '615'),(1, 106, 2, '615')," +
			"(1, 357, 0, '60'),(1, 357, 1, '60'),(1, 357, 2, '60')," +
			"(1, 358, 0, '54'),(1, 358, 1, '54'),(1, 358, 2, '54')," +
			"(1, 359, 0, '57'),(1, 359, 1, '57'),(1, 359, 2, '57')," +
			"(1, 360, 0, '60'),(1, 360, 1, '60'),(1, 360, 2, '60')," +
			"(1, 361, 0, '60'),(1, 361, 1, '60'),(1, 361, 2, '60')," +
			"(1, 362, 0, '60'),(1, 362, 1, '60'),(1, 362, 2, '60')," +
			"(1, 124, 0, '0'),(1, 124, 1, '0'),(1, 124, 2, '0')," +
			"(1, 126, 0, '0'),(1, 126, 1, '0'),(1, 126, 2, '0')," +
			"(1, 128, 0, '0'),(1, 128, 1, '0'),(1, 128, 2, '0')");

		db.update("INSERT INTO parameter_study_type VALUES " +
			"(122, 1),(122, 2),(122, 3),(122, 4),(122, 5)," +
			"(80, 1),(80, 2),(80, 3),(80, 5)," +
			"(82, 1),(82, 2),(82, 3),(82, 5)," +
			"(84, 1),(84, 2),(84, 3),(84, 5)," +
			"(86, 1),(86, 2),(86, 3),(86, 5)," +
			"(88, 1),(88, 2),(88, 3),(88, 5)," +
			"(90, 1),(90, 2),(90, 3),(90, 5)," +
			"(92, 1),(92, 2),(92, 3),(92, 5)," +
			"(94, 1),(94, 2),(94, 3),(94, 5)," +
			"(96, 1),(96, 2),(96, 3),(96, 5)," +
			"(98, 1),(98, 2),(98, 3),(98, 5)," +
			"(100, 1),(100, 2),(100, 3),(100, 5)," +
			"(102, 1),(102, 2),(102, 3),(102, 5)," +
			"(104, 1),(104, 2),(104, 3),(104, 5)," +
			"(106, 1),(106, 2),(106, 3),(106, 5)," +
			"(357, 4),(357, 5)," +
			"(358, 4),(358, 5)," +
			"(359, 4),(359, 5)," +
			"(360, 4),(360, 5)," +
			"(361, 4),(361, 5)," +
			"(362, 4),(362, 5)," +
			"(124, 1),(124, 2),(124, 3),(124, 5)," +
			"(126, 1),(126, 2),(126, 3),(126, 5)," +
			"(128, 1),(128, 2),(128, 3),(128, 5)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// FCC Contours

		db.update("INSERT INTO parameter VALUES " +
			"(20, 14, 1, 'Average terrain database', 'pickfrom:0:1/3-second:1:1-second:2:3-second:3:30-second', '', '', '<HTML>Terrain database for average terrain calculations.</HTML>', false)," +
			"(22, 14, 2, 'Average terrain profile resolution', 'decimal:1:50', 'pts/km', 'U.S.:Canada:Mexico', '<HTML>Profile resolution in points per kilometer for average terrain calculations for TV and FM stations.</HTML>', false)," +
			"(333, 14, 3, 'Use elevation patterns', 'pickfrom:0:Never:1:Full-service TV only:2:All stations', '', 'U.S.:Canada:Mexico', '<HTML>Determines when FCC contour distance projection is adjusted by an elevation pattern.</HTML>', false)," +
			"(17, 14, 4, 'Use real elevation patterns', 'option', '', 'U.S.:Canada:Mexico', '<HTML>Use real elevation patterns when available for projecting FCC contours.<BR>If not selected, contour projection may use generic patterns, or no elevation pattern, per other settings.<HTML>', false)," +
			"(202, 14, 5, 'Derive azimuth pattern', 'pickfrom:0:No:1:At horizontal:2:At radio horizon', '', 'U.S.:Canada:Mexico', '<HTML>Project FCC contours using an azimuth pattern derived from actual azimuth<BR>and elevation patterns and beam tilt parameters. This will be done only when a record<BR>has a real elevation pattern and mechanical beam tilt, or has a matrix elevation pattern.<BR>When selected and conditions apply, other elevation pattern settings are ignored.</HTML>', false)," +
			"(107, 14, 6, 'FCC curve set, TV digital', 'pickfrom:1:F(50,50):2:F(50,10):3:F(50,90)', '', 'U.S.:Canada:Mexico', '<HTML>FCC propagation curve set for TV digital contour projection.</HTML>', false)," +
			"(108, 14, 7, 'FCC curve set, TV analog', 'pickfrom:1:F(50,50):2:F(50,10):3:F(50,90)', '', 'U.S.:Canada:Mexico', '<HTML>FCC propagation curve set for TV analog contour projection.</HTML>', false)," +
			"(363, 14, 8, 'FCC curve set, FM', 'pickfrom:1:F(50,50):2:F(50,10):3:F(50,90)', '', 'U.S.:Canada:Mexico', '<HTML>FCC propagation curve set for FM contour projection.</HTML>', false)," +
			"(329, 14, 9, 'Lookup method below curve minimum distance', 'pickfrom:1:Free-space:2:Scaled free-space:3:None', '', '', '<HTML>Lookup method used when below the minimum-distance point on propagation curves:<BR>Free-space - Use free-space function only (discontinuity at curve end)<BR>Scaled free-space - Use free-space function scaled to match curve end<BR>None - Always return values from the last curve point.</HTML>', false)," +
			"(120, 14, 10, 'Contour HAAT radial count', 'integer:4:360', '', 'U.S.:Canada:Mexico', '<HTML>Number of radials to use for HAAT lookup during contour projection for full-service and Class A TV, and FM.<BR>If different than the number of contour radials, HAAT values are interpolated between lookup radials.</HTML>', false)," +
			"(158, 14, 11, 'Contour HAAT radial count, LPTV', 'integer:4:360', '', 'U.S.:Canada:Mexico', '<HTML>Number of radials to use for HAAT lookup during contour projection for LPTV.</HTML>', false)," +
			"(121, 14, 12, 'Minimum HAAT', 'decimal:0:100', 'm', 'U.S.:Canada:Mexico', '<HTML>Minimum value for HAAT on any radial.</HTML>', false)," +
			"(330, 14, 13, 'Average terrain start distance', 'decimal:0:100', 'km', 'U.S.:Canada:Mexico', '<HTML>Start distance for average terrain calculation on one radial.</HTML>', false)," +
			"(331, 14, 14, 'Average terrain end distance', 'decimal:0:100', 'km', 'U.S.:Canada:Mexico', '<HTML>End distance for average terrain calculation on one radial.</HTML>', false)," +
			"(110, 14, 15, 'Truncate DTS service area', 'option', '', '', '<HTML>Truncate DTS service by the combined area of the pre-DTS FCC service contour and a radius or sectors definition around the DTS reference point.<BR>Applies only when service area is FCC contour for all DTS transmitters.</HTML>', false)," +
			"(111, 14, 16, 'DTS distance limit, VHF low Zone I', 'decimal:50:200', 'km', '', '<HTML>DTS distance limit, VHF low band in Zone I.</HTML>', false)," +
			"(112, 14, 17, 'DTS distance limit, VHF low Zone II/III', 'decimal:50:200', 'km', '', '<HTML>DTS distance limit, VHF low band in Zones II and III.</HTML>', false)," +
			"(113, 14, 18, 'DTS distance limit, VHF high Zone I', 'decimal:50:200', 'km', '', '<HTML>DTS distance limit, VHF high band in Zone I.</HTML>', false)," +
			"(114, 14, 19, 'DTS distance limit, VHF high Zone II/III', 'decimal:50:200', 'km', '', '<HTML>DTS distance limit, VHF high band in Zones II and III.</HTML>', false)," +
			"(115, 14, 20, 'DTS distance limit, UHF', 'decimal:50:200', 'km', '', '<HTML>DTS distance limit, UHF.</HTML>', false)");

		db.update("INSERT INTO template_parameter_data VALUES " +
			"(1, 20, 0, '1')," +
			"(1, 22, 0, '10'),(1, 22, 1, '10'),(1, 22, 2, '10')," +
			"(1, 333, 0, '1'),(1, 333, 1, '1'),(1, 333, 2, '1')," +
			"(1, 17, 0, '0'),(1, 17, 1, '0'),(1, 17, 2, '0')," +
			"(1, 202, 0, '0'),(1, 202, 1, '0'),(1, 202, 2, '0')," +
			"(1, 107, 0, '3'),(1, 107, 1, '3'),(1, 107, 2, '3')," +
			"(1, 108, 0, '1'),(1, 108, 1, '1'),(1, 108, 2, '1')," +
			"(1, 363, 0, '1'),(1, 363, 1, '1'),(1, 363, 2, '1')," +
			"(1, 329, 0, '1')," +
			"(1, 120, 0, '8'),(1, 120, 1, '36'),(1, 120, 2, '8')," +
			"(1, 158, 0, '360'),(1, 158, 1, '36'),(1, 158, 2, '8')," +
			"(1, 121, 0, '30.5'),(1, 121, 1, '30.5'),(1, 121, 2, '30.5')," +
			"(1, 330, 0, '3.2'),(1, 330, 1, '3.2'),(1, 330, 2, '3.2')," +
			"(1, 331, 0, '16.1'),(1, 331, 1, '16.1'),(1, 331, 2, '16.1')," +
			"(1, 110, 0, '1')," +
			"(1, 111, 0, '108')," +
			"(1, 112, 0, '128')," +
			"(1, 113, 0, '101')," +
			"(1, 114, 0, '123')," +
			"(1, 115, 0, '103')");

		db.update("INSERT INTO parameter_study_type VALUES " +
			"(20, 1),(20, 2),(20, 3),(20, 4),(20, 5)," +
			"(22, 1),(22, 2),(22, 3),(22, 4),(22, 5)," +
			"(333, 1),(333, 2),(333, 3),(333, 4),(333, 5)," +
			"(17, 1),(17, 2),(17, 3),(17, 4),(17, 5)," +
			"(202, 1),(202, 2),(202, 3),(202, 4),(202, 5)," +
			"(107, 1),(107, 2),(107, 3),(107, 5)," +
			"(108, 1),(108, 2),(108, 3),(108, 5)," +
			"(363, 4),(363, 5)," +
			"(329, 1),(329, 2),(329, 3),(329, 4),(329, 5)," +
			"(120, 1),(120, 2),(120, 3),(120, 4),(120, 5)," +
			"(158, 1),(158, 2),(158, 3),(158, 5)," +
			"(121, 1),(121, 2),(121, 3),(121, 4),(121, 5)," +
			"(330, 1),(330, 2),(330, 3),(330, 4),(330, 5)," +
			"(331, 1),(331, 2),(331, 3),(331, 4),(331, 5)," +
			"(110, 1),(110, 2),(110, 3),(110, 5)," +
			"(111, 1),(111, 2),(111, 3),(111, 5)," +
			"(112, 1),(112, 2),(112, 3),(112, 5)," +
			"(113, 1),(113, 2),(113, 3),(113, 5)," +
			"(114, 1),(114, 2),(114, 3),(114, 5)," +
			"(115, 1),(115, 2),(115, 3),(115, 5)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// L-R Contours

		db.update("INSERT INTO parameter VALUES " +
			"(175, 16, 1, 'Terrain database', 'pickfrom:0:1/3-second:1:1-second:2:3-second:3:30-second', '', '', '<HTML>Terrain database for Longley-Rice contour projections.</HTML>', false)," +
			"(177, 16, 2, 'Profile resolution', 'decimal:1:50', 'pts/km', '', '<HTML>Profile resolution in points per kilometer for Longley-Rice contour projections.</HTML>', false)," +
			"(159, 16, 3, 'Calculation distance increment', 'decimal:0.1:10', 'km', '', '<HTML>Target distance between calculated signal points along each radial used to determine contour distance.<BR>Determines an integral point increment based on the profile resolution; calculations can only occur at profile point distances.</HTML>', false)," +
			"(179, 16, 4, 'Digital % location', 'decimal:0.01:99.99', '%', '', '<HTML>Percent location for digital TV Longley-Rice contour projection.</HTML>', false)," +
			"(181, 16, 5, 'Digital % time', 'decimal:0.01:99.99', '%', '', '<HTML>Percent time for digital TV Longley-Rice contour projection.</HTML>', false)," +
			"(183, 16, 6, 'Digital % confidence', 'decimal:0.01:99.99', '%', '', '<HTML>Percent confidence for digital TV Longley-Rice contour projection.</HTML>', false)," +
			"(185, 16, 7, 'Analog % location', 'decimal:0.01:99.99', '%', '', '<HTML>Percent location for analog TV and FM Longley-Rice contour projection.</HTML>', false)," +
			"(187, 16, 8, 'Analog % time', 'decimal:0.01:99.99', '%', '', '<HTML>Percent time for analog TV and FM Longley-Rice contour projection.</HTML>', false)," +
			"(188, 16, 9, 'Analog % confidence', 'decimal:0.01:99.99', '%', '', '<HTML>Percent confidence for analog TV and FM Longley-Rice contour projection.</HTML>', false)," +
			"(161, 16, 10, 'Receiver height AGL', 'decimal:0.5:50', 'm', '', '<HTML>Receiver antenna height above ground.</HTML>', false)," +
			"(163, 16, 11, 'Signal polarization', 'pickfrom:0:Horizontal:1:Vertical', '', '', '<HTML>Signal polarization for Longley-Rice contour projections.</HTML>', false)," +
			"(165, 16, 12, 'Atmospheric refractivity', 'decimal:200:450', 'N', '', '<HTML>Atmospheric refractivity referenced to mean sea level in N-units for Longley-Rice contour projections.</HTML>', false)," +
			"(167, 16, 13, 'Ground permittivity', 'decimal:1:5000', '', '', '<HTML>Ground relative permittivity for Longley-Rice contour projections.', false)," +
			"(169, 16, 14, 'Ground conductivity', 'decimal:0.0001:1', 'S/m', '', '<HTML>Ground conductivity in Siemens per meter for Longley-Rice contour projections.</HTML>', false)," +
			"(171, 16, 15, 'Service mode', 'pickfrom:0:Single-message:1:Individual:2:Mobile:3:Broadcast', '', '', '<HTML>Service mode for Longley-Rice contour projections.</HTML>', false)," +
			"(173, 16, 16, 'Climate type', 'pickfrom:1:Equatorial:2:Continental subtropical:3:Maritime subtropical:4:Desert:5:Continental temperate:6:Maritime temperate over land:7:Maritime temperate over sea', '', '', '<HTML>Climate type for Longley-Rice contour projections.</HTML>', false)");

		db.update("INSERT INTO template_parameter_data VALUES " +
			"(1, 175, 0, '1')," +
			"(1, 177, 0, '1')," +
			"(1, 159, 0, '1')," +
			"(1, 179, 0, '50')," +
			"(1, 181, 0, '90')," +
			"(1, 183, 0, '50')," +
			"(1, 185, 0, '50')," +
			"(1, 187, 0, '50')," +
			"(1, 188, 0, '50')," +
			"(1, 161, 0, '10')," +
			"(1, 163, 0, '0')," +
			"(1, 165, 0, '301')," +
			"(1, 167, 0, '15')," +
			"(1, 169, 0, '0.005')," +
			"(1, 171, 0, '3')," +
			"(1, 173, 0, '5')");

		db.update("INSERT INTO parameter_study_type VALUES " +
			"(175, 1),(175, 2),(175, 3),(175, 4),(175, 5)," +
			"(177, 1),(177, 2),(177, 3),(177, 4),(177, 5)," +
			"(159, 1),(159, 2),(159, 3),(159, 4),(159, 5)," +
			"(179, 1),(179, 2),(179, 3),(179, 4),(179, 5)," +
			"(181, 1),(181, 2),(181, 3),(181, 4),(181, 5)," +
			"(183, 1),(183, 2),(183, 3),(183, 4),(183, 5)," +
			"(185, 1),(185, 2),(185, 3),(185, 4),(185, 5)," +
			"(187, 1),(187, 2),(187, 3),(187, 4),(187, 5)," +
			"(188, 1),(188, 2),(188, 3),(188, 4),(188, 5)," +
			"(161, 1),(161, 2),(161, 3),(161, 4),(161, 5)," +
			"(163, 1),(163, 2),(163, 3),(163, 4),(163, 5)," +
			"(165, 1),(165, 2),(165, 3),(165, 4),(165, 5)," +
			"(167, 1),(167, 2),(167, 3),(167, 4),(167, 5)," +
			"(169, 1),(169, 2),(169, 3),(169, 4),(169, 5)," +
			"(171, 1),(171, 2),(171, 3),(171, 4),(171, 5)," +
			"(173, 1),(173, 2),(173, 3),(173, 4),(173, 5)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// TV Replication

		db.update("INSERT INTO parameter VALUES " +
			"(50, 5, 1, 'Replication method', 'pickfrom:1:Derive pattern:2:Equal area', '', 'U.S.:Canada:Mexico', '<HTML>Method for performing service area replication on an alternate channel:<BR>Derive pattern - Reverse-project contour points to derive a new azimuth pattern and peak ERP.<BR>Equal area - Adjust peak ERP to match contour area using the original azimuth pattern.</HTML>', false)," +
			"(52, 5, 2, 'Digital full-service minimum ERP, VHF low', 'decimal:0:200', 'kW', 'U.S.:Canada:Mexico', '<HTML>Minimum ERP for digital full-service, VHF low band (applied after contour replication).</HTML>', false)," +
			"(54, 5, 3, 'Digital full-service minimum ERP, VHF high', 'decimal:0:200', 'kW', 'U.S.:Canada:Mexico', '<HTML>Minimum ERP for digital full-service, VHF high band (applied after contour replication).</HTML>', false)," +
			"(56, 5, 4, 'Digital full-service minimum ERP, UHF', 'decimal:0:200', 'kW', 'U.S.:Canada:Mexico', '<HTML>Minimum ERP for digital full-service, UHF (applied after contour replication).</HTML>', false)," +
			"(58, 5, 5, 'Digital full-service maximum ERP, VHF low Zone I', 'decimal:0.001:2000', 'kW', 'U.S.:Canada:Mexico', '<HTML>Maximum ERP for digital full-service, VHF low band in Zone I (applied after contour replication).</HTML>', false)," +
			"(60, 5, 6, 'Digital full-service maximum ERP, VHF low Zone II/III/other', 'decimal:0.001:2000', 'kW', 'U.S.:Canada:Mexico', '<HTML>Maximum ERP for digital full-service station, VHF low band in Zones II, III,<BR>and stations not assigned to a Zone (applied after contour replication).</HTML>', false)," +
			"(62, 5, 7, 'Digital full-service maximum ERP, VHF high Zone I', 'decimal:0.001:2000', 'kW', 'U.S.:Canada:Mexico', '<HTML>Maximum ERP for digital full-service station, VHF high band in Zone I (applied after contour replication).</HTML>', false)," +
			"(64, 5, 8, 'Digital full-service maximum ERP, VHF high Zone II/III/other', 'decimal:0.001:2000', 'kW', 'U.S.:Canada:Mexico', '<HTML>Maximum ERP for digital full-service station, VHF high band in Zones II, III,<BR>and stations not assigned to a Zone (applied after contour replication).</HTML>', false)," +
			"(66, 5, 9, 'Digital full-service maximum ERP, UHF', 'decimal:0.001:2000', 'kW', 'U.S.:Canada:Mexico', '<HTML>Maximum ERP for digital full-service, UHF (applied after contour replication).</HTML>', false)," +
			"(327, 5, 10, 'Digital Class A/LPTV minimum ERP, VHF', 'decimal:0.001:2000', 'kW', 'U.S.:Canada:Mexico', '<HTML>Minimum ERP for digital Class A/LPTV, VHF (applied after contour replication).</HTML>', false)," +
			"(328, 5, 11, 'Digital Class A/LPTV minimum ERP, UHF', 'decimal:0.001:2000', 'kW', 'U.S.:Canada:Mexico', '<HTML>Minimum ERP for digital Class A/LPTV, UHF (applied after contour replication).</HTML>', false)," +
			"(68, 5, 12, 'Digital Class A/LPTV maximum ERP, VHF', 'decimal:0.001:2000', 'kW', 'U.S.:Canada:Mexico', '<HTML>Maximum ERP for digital Class A/LPTV, VHF (applied after contour replication).</HTML>', false)," +
			"(70, 5, 13, 'Digital Class A/LPTV maximum ERP, UHF', 'decimal:0.001:2000', 'kW', 'U.S.:Canada:Mexico', '<HTML>Maximum ERP for digital Class A/LPTV, UHF (applied after contour replication).</HTML>', false)");

		db.update("INSERT INTO template_parameter_data VALUES " +
			"(1, 50, 0, '2'),(1, 50, 1, '2'),(1, 50, 2, '2')," +
			"(1, 52, 0, '0.1'),(1, 52, 1, '0.002'),(1, 52, 2, '0.1')," +
			"(1, 54, 0, '0.1'),(1, 54, 1, '0.002'),(1, 54, 2, '0.1')," +
			"(1, 56, 0, '0.1'),(1, 56, 1, '0.002'),(1, 56, 2, '0.1')," +
			"(1, 58, 0, '10'),(1, 58, 1, '10'),(1, 58, 2, '10')," +
			"(1, 60, 0, '45'),(1, 60, 1, '45'),(1, 60, 2, '45')," +
			"(1, 62, 0, '30'),(1, 62, 1, '30'),(1, 62, 2, '30')," +
			"(1, 64, 0, '185'),(1, 64, 1, '160'),(1, 64, 2, '160')," +
			"(1, 66, 0, '1000'),(1, 66, 1, '1000'),(1, 66, 2, '1000')," +
			"(1, 327, 0, '0.024'),(1, 327, 1, '0.001'),(1, 327, 2, '0.024')," +
			"(1, 328, 0, '0.024'),(1, 328, 1, '0.001'),(1, 328, 2, '0.024')," +
			"(1, 68, 0, '3'),(1, 68, 1, '3'),(1, 68, 2, '3')," +
			"(1, 70, 0, '15'),(1, 70, 1, '15'),(1, 70, 2, '15')");

		db.update("INSERT INTO parameter_study_type VALUES " +
			"(50, 1),(50, 2),(50, 3),(50, 5)," +
			"(52, 1),(52, 2),(52, 3),(52, 5)," +
			"(54, 1),(54, 2),(54, 3),(54, 5)," +
			"(56, 1),(56, 2),(56, 3),(56, 5)," +
			"(58, 1),(58, 2),(58, 3),(58, 5)," +
			"(60, 1),(60, 2),(60, 3),(60, 5)," +
			"(62, 1),(62, 2),(62, 3),(62, 5)," +
			"(64, 1),(64, 2),(64, 3),(64, 5)," +
			"(66, 1),(66, 2),(66, 3),(66, 5)," +
			"(327, 1),(327, 2),(327, 3),(327, 5)," +
			"(328, 1),(328, 2),(328, 3),(328, 5)," +
			"(68, 1),(68, 2),(68, 3),(68, 5)," +
			"(70, 1),(70, 2),(70, 3),(70, 5)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// Patterns

		db.update("INSERT INTO parameter VALUES " +
			"(12, 3, 1, 'Depression angle method', 'pickfrom:1:Effective height:2:True geometry', '', 'U.S.:Canada:Mexico', '<HTML>Method of computing depression angle for elevation pattern lookup for TV and FM stations:<BR>Effective height - Use transmitter HAAT or height AGL with distance-squared approximation formula.<BR>True geometry - Compute exact angle from transmitter to receiver over curved earth.</HTML>', false)," +
			"(14, 3, 2, 'Use mechanical beam tilt', 'pickfrom:0:Never:1:Always:2:Real patterns only', '', 'U.S.:Canada:Mexico', '<HTML>When to apply mechanical beam tilt to elevation patterns for TV and FM stations.<BR>Electrical beam tilt is always applied to real patterns.</HTML>', false)," +
			"(219, 3, 3, 'Use generic patterns by default', 'option', '', 'U.S.:Canada:Mexico', '<HTML>Determines the default setting of the option to use OET-69 generic patterns for TV and FM stations when needed.<BR>Wireless base stations always default to no.<BR>The option can be changed for any individual record in the editor.</HTML>', false)," +
			"(15, 3, 4, 'Mirror generic patterns', 'pickfrom:0:Never:1:Always:2:Propagation only', '', 'U.S.:Canada:Mexico', '<HTML>Mirror generic patterns around the depression angle of the maximum for TV and FM stations.<BR>If not selected, pattern is 1.0 at angles above the maximum.<BR>Non-generic patterns are always mirrored if needed.</HTML>', false)," +
			"(16, 3, 5, 'Beam tilt on generic patterns', 'pickfrom:0:None:1:Full:2:Offset', '', 'U.S.:Canada:Mexico', '<HTML>Amount of specified beam tilt applied to generic elevation patterns:<BR>None - Generic patterns have only inherent 0.75 degrees electrical tilt.<BR>Full - Apply the full amount of specified electrical tilt, and mechanical tilt if enabled.<BR>Offset - Apply specified electrical tilt with an offset of 0.75 degrees, and full mechanical tilt if enabled.</HTML>', false)," +
			"(332, 3, 6, 'Double generic pattern values', 'pickfrom:0:Never:1:LPTV only:2:LPTV and Class A', '', 'U.S.:Canada:Mexico', '<HTML>Determines when relative field values from OET-69 generic elevation patterns are doubled.</HTML>', false)," +
			"(19, 3, 7, 'Invert negative tilts', 'option', '', 'U.S.:Canada:Mexico', '<HTML>Change all negative values for electrical and mechanical beam tilt to positive (use absolute value).</HTML>', false)," +
			"(140, 3, 8, 'Digital receive antenna f/b, VHF low', 'decimal:0:20', 'dB', '', '<HTML>Receive antenna front-to-back ratio, digital VHF low band.</HTML>', false)," +
			"(142, 3, 9, 'Digital receive antenna f/b, VHF high', 'decimal:0:20', 'dB', '', '<HTML>Receive antenna front-to-back ratio, digital VHF high band.</HTML>', false)," +
			"(144, 3, 10, 'Digital receive antenna f/b, UHF', 'decimal:0:20', 'dB', '', '<HTML>Receive antenna front-to-back ratio, digital UHF.</HTML>', false)," +
			"(146, 3, 11, 'Analog receive antenna f/b, VHF low', 'decimal:0:20', 'dB', '', '<HTML>Receive antenna front-to-back ratio, analog VHF low band.</HTML>', false)," +
			"(148, 3, 12, 'Analog receive antenna f/b, VHF high', 'decimal:0:20', 'dB', '', '<HTML>Receive antenna front-to-back ratio, analog VHF high band.</HTML>', false)," +
			"(150, 3, 13, 'Analog receive antenna f/b, UHF', 'decimal:0:20', 'dB', '', '<HTML>Receive antenna front-to-back ratio, analog UHF.</HTML>', false)," +
			"(151, 3, 14, 'Analog receive antenna f/b, FM', 'decimal:0:20', 'dB', '', '<HTML>Receive antenna front-to-back ratio, analog VHF low band.</HTML>', false)");

		db.update("INSERT INTO template_parameter_data VALUES " +
			"(1, 12, 0, '2'),(1, 12, 1, '2'),(1, 12, 2, '2')," +
			"(1, 14, 0, '0'),(1, 14, 1, '0'),(1, 14, 2, '0')," +
			"(1, 219, 0, '1'),(1, 219, 1, '1'),(1, 219, 2, '1')," +
			"(1, 15, 0, '0'),(1, 15, 1, '0'),(1, 15, 2, '0')," +
			"(1, 16, 0, '2'),(1, 16, 1, '2'),(1, 16, 2, '2')," +
			"(1, 332, 0, '2'),(1, 332, 1, '2'),(1, 332, 2, '2')," +
			"(1, 19, 0, '1'),(1, 19, 1, '1'),(1, 19, 2, '1')," +
			"(1, 140, 0, '10')," +
			"(1, 142, 0, '12')," +
			"(1, 144, 0, '14')," +
			"(1, 146, 0, '6')," +
			"(1, 148, 0, '6')," +
			"(1, 150, 0, '6')," +
			"(1, 151, 0, '6')");

		db.update("INSERT INTO parameter_study_type VALUES " +
			"(12, 1),(12, 2),(12, 3),(12, 4),(12, 5)," +
			"(14, 1),(14, 2),(14, 3),(14, 4),(14, 5)," +
			"(219, 1),(219, 2),(219, 3),(219, 4),(219, 5)," +
			"(15, 1),(15, 2),(15, 3),(15, 4),(15, 5)," +
			"(16, 1),(16, 2),(16, 3),(16, 4),(16, 5)," +
			"(332, 1),(332, 2),(332, 3),(332, 5)," +
			"(19, 1),(19, 2),(19, 3),(19, 4),(19, 5)," +
			"(140, 1),(140, 2),(140, 3),(140, 5)," +
			"(142, 1),(142, 2),(142, 3),(142, 5)," +
			"(144, 1),(144, 2),(144, 3),(144, 5)," +
			"(146, 1),(146, 2),(146, 3),(146, 5)," +
			"(148, 1),(148, 2),(148, 3),(148, 5)," +
			"(150, 1),(150, 2),(150, 3),(150, 5)," +
			"(151, 4),(151, 5)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// Propagation

		db.update("INSERT INTO parameter VALUES " +
			"(30, 6, 1, 'Terrain database', 'pickfrom:0:1/3-second:1:1-second:2:3-second:3:30-second', '', '', '<HTML>Terrain database for propagation calculations.</HTML>', false)," +
			"(32, 6, 2, 'Profile resolution', 'decimal:1:50', 'pts/km', 'U.S.:Canada:Mexico', '<HTML>Profile resolution in points per kilometer for propagation calculations for TV and FM stations.</HTML>', false)," +
			"(10, 6, 3, 'Model error handling', 'pickfrom:1:Disregard:2:Assume service:3:Assume interference', '', 'U.S.:Canada:Mexico', '<HTML>Behavior when an error code is returned from the propagation model:<BR>Disregard - Use returned signal regardless of error.<BR>Assume service - Error on desired is interference-free service, error on undesired is no interference.<BR>Assume interference - Error on desired is no service, error on undesired is interference.</HTML>', false)," +
			"(130, 6, 4, 'Receiver height AGL', 'decimal:0.5:50', 'm', '', '<HTML>Receiver antenna height above ground.</HTML>', false)," +
			"(132, 6, 5, 'Minimum transmitter height AGL', 'decimal:0.5:50', 'm', '', '<HTML>Minimum transmitter height above ground for TV and FM stations.<BR>Transmitter height AMSL is increased as needed to meet this minimum.</HTML>', false)," +
			"(160, 6, 6, 'Digital desired % location', 'decimal:0.01:99.99', '%', '', '<HTML>Propagation model statistical parameter, % location for digital desired signals for TV and FM stations.</HTML>', false)," +
			"(162, 6, 7, 'Digital desired % time', 'decimal:0.01:99.99', '%', '', '<HTML>Propagation model statistical parameter, % time for digital desired signals for TV and FM stations.</HTML>', false)," +
			"(164, 6, 8, 'Digital desired % confidence', 'decimal:0.01:99.99', '%', '', '<HTML>Propagation model statistical parameter, % confidence for digital desired signals for TV and FM stations.</HTML>', false)," +
			"(166, 6, 9, 'Digital undesired % location', 'decimal:0.01:99.99', '%', '', '<HTML>Propagation model statistical parameter, % location for digital undesired signals for TV and FM stations.<BR>(Undesired signal % time is defined by the applicable interference rule.)</HTML>', false)," +
			"(168, 6, 10, 'Digital undesired % confidence', 'decimal:0.01:99.99', '%', '', '<HTML>Propagation model statistical parameter, % confidence for digital undesired signals for TV and FM stations.<BR>(Undesired signal % time is defined by the applicable interference rule.)</HTML>', false)," +
			"(170, 6, 11, 'Analog desired % location', 'decimal:0.01:99.99', '%', '', '<HTML>Propagation model statistical parameter, % location for analog desired signals for TV and FM stations.</HTML>', false)," +
			"(172, 6, 12, 'Analog desired % time', 'decimal:0.01:99.99', '%', '', '<HTML>Propagation model statistical parameter, % time for analog desired signals for TV and FM stations.</HTML>', false)," +
			"(174, 6, 13, 'Analog desired % confidence', 'decimal:0.01:99.99', '%', '', '<HTML>Propagation model statistical parameter, % confidence for analog desired signals for TV and FM stations.</HTML>', false)," +
			"(176, 6, 14, 'Analog undesired % location', 'decimal:0.01:99.99', '%', '', '<HTML>Propagation model statistical parameter, % location for analog undesired signals for TV and FM stations.<BR>(Undesired signal % time is defined by the applicable interference rule.)</HTML>', false)," +
			"(178, 6, 15, 'Analog undesired % confidence', 'decimal:0.01:99.99', '%', '', '<HTML>Propagation model statistical parameter, % confidence for analog undesired signals for TV and FM stations.<BR>(Undesired signal % time is defined by the applicable interference rule.)</HTML>', false)," +
			"(180, 6, 16, 'Signal polarization', 'pickfrom:0:Horizontal:1:Vertical', '', '', '<HTML>Propagation model signal polarization for TV and FM stations.</HTML>', false)," +
			"(182, 6, 17, 'Atmospheric refractivity', 'decimal:200:450', 'N', '', '<HTML>Propagation model atmospheric refractivity referenced to mean sea level in N-units.</HTML>', false)," +
			"(184, 6, 18, 'Ground permittivity', 'decimal:1:5000', '', '', '<HTML>Propagation model ground relative permittivity.', false)," +
			"(186, 6, 19, 'Ground conductivity', 'decimal:0.0001:1', 'S/m', '', '<HTML>Propagation model ground conductivity in Siemens per meter.</HTML>', false)," +
			"(190, 6, 20, 'Service mode', 'pickfrom:0:Single-message:1:Individual:2:Mobile:3:Broadcast', '', '', '<HTML>Propagation model service mode for FM and TV stations.</HTML>', false)," +
			"(192, 6, 22, 'Climate type', 'pickfrom:1:Equatorial:2:Continental subtropical:3:Maritime subtropical:4:Desert:5:Continental temperate:6:Maritime temperate over land:7:Maritime temperate over sea', '', '', '<HTML>Propagation model climate type.</HTML>', false)");

		db.update("INSERT INTO template_parameter_data VALUES " +
			"(1, 30, 0, '1')," +
			"(1, 32, 0, '1'),(1, 32, 1, '1'),(1, 32, 2, '1')," +
			"(1, 10, 0, '2'),(1, 10, 1, '2'),(1, 10, 2, '2')," +
			"(1, 130, 0, '10')," +
			"(1, 132, 0, '10')," +
			"(1, 160, 0, '50')," +
			"(1, 162, 0, '90')," +
			"(1, 164, 0, '50')," +
			"(1, 166, 0, '50')," +
			"(1, 168, 0, '50')," +
			"(1, 170, 0, '50')," +
			"(1, 172, 0, '50')," +
			"(1, 174, 0, '50')," +
			"(1, 176, 0, '50')," +
			"(1, 178, 0, '50')," +
			"(1, 180, 0, '0')," +
			"(1, 182, 0, '301')," +
			"(1, 184, 0, '15')," +
			"(1, 186, 0, '0.005')," +
			"(1, 190, 0, '3')," +
			"(1, 192, 0, '5')");

		db.update("INSERT INTO parameter_study_type VALUES " +
			"(30, 1),(30, 2),(30, 3),(30, 4),(30, 5)," +
			"(32, 1),(32, 2),(32, 3),(32, 4),(32, 5)," +
			"(10, 1),(10, 2),(10, 3),(10, 4),(10, 5)," +
			"(130, 1),(130, 2),(130, 3),(130, 4),(130, 5)," +
			"(132, 1),(132, 2),(132, 3),(132, 4),(132, 5)," +
			"(160, 1),(160, 2),(160, 3),(160, 5)," +
			"(162, 1),(162, 2),(162, 3),(162, 5)," +
			"(164, 1),(164, 2),(164, 3),(164, 5)," +
			"(166, 1),(166, 2),(166, 3),(166, 5)," +
			"(168, 1),(168, 2),(168, 3),(168, 5)," +
			"(170, 1),(170, 2),(170, 3),(170, 4),(170, 5)," +
			"(172, 1),(172, 2),(172, 3),(172, 4),(172, 5)," +
			"(174, 1),(174, 2),(174, 3),(174, 4),(174, 5)," +
			"(176, 1),(176, 2),(176, 3),(176, 4),(176, 5)," +
			"(178, 1),(178, 2),(178, 3),(178, 4),(178, 5)," +
			"(180, 1),(180, 2),(180, 3),(180, 4),(180, 5)," +
			"(182, 1),(182, 2),(182, 3),(182, 4),(182, 5)," +
			"(184, 1),(184, 2),(184, 3),(184, 4),(184, 5)," +
			"(186, 1),(186, 2),(186, 3),(186, 4),(186, 5)," +
			"(190, 1),(190, 2),(190, 3),(190, 4),(190, 5)," +
			"(192, 1),(192, 2),(192, 3),(192, 4),(192, 5)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// Service

		db.update("INSERT INTO parameter VALUES " +
			"(309, 7, 1, 'Set service thresholds', 'option', '', '', '<HTML>Independently set the terrain-sensitive service thresholds.<BR>If not selected, thresholds are the same as contour levels.</HTML>', false)," +
			"(310, 7, 2, 'Digital TV full-service threshold, VHF low', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Terrain-sensitive service threshold level, digital TV full-service VHF low band.</HTML>', false)," +
			"(311, 7, 3, 'Digital TV full-service threshold, VHF high', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Terrain-sensitive service threshold level, digital TV full-service VHF high band.</HTML>', false)," +
			"(312, 7, 4, 'Digital TV full-service threshold, UHF', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Terrain-sensitive service threshold level, digital TV full-service UHF (may be dipole-adjusted).</HTML>', false)," +
			"(313, 7, 5, 'Digital TV Class A/LPTV threshold, VHF low', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Terrain-sensitive service threshold level, digital LPTV/Class A VHF low band.</HTML>', false)," +
			"(314, 7, 6, 'Digital TV Class A/LPTV threshold, VHF high', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Terrain-sensitive service threshold level, digital LPTV/Class A VHF high band.</HTML>', false)," +
			"(315, 7, 7, 'Digital TV Class A/LPTV threshold, UHF', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Terrain-sensitive service threshold level, digital LPTV/Class A UHF (may be dipole-adjusted).</HTML>', false)," +
			"(316, 7, 8, 'Analog TV full-service threshold, VHF low', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Terrain-sensitive service threshold level, analog TV full-service VHF low band.</HTML>', false)," +
			"(317, 7, 9, 'Analog TV full-service threshold, VHF high', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Terrain-sensitive service threshold level, analog TV full-service VHF high band.</HTML>', false)," +
			"(318, 7, 10, 'Analog TV full-service threshold, UHF', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Terrain-sensitive service threshold level, analog TV full-service UHF (may be dipole-adjusted).</HTML>', false)," +
			"(319, 7, 11, 'Analog TV Class A/LPTV threshold, VHF low', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Terrain-sensitive service threshold level, analog LPTV/Class A VHF low band.</HTML>', false)," +
			"(320, 7, 12, 'Analog TV Class A/LPTV threshold, VHF high', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Terrain-sensitive service threshold level, analog LPTV/Class A VHF high band.</HTML>', false)," +
			"(321, 7, 13, 'Analog TV Class A/LPTV threshold, UHF', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Terrain-sensitive service threshold level, analog LPTV/Class A UHF (may be dipole-adjusted).</HTML>', false)," +
			"(322, 7, 14, 'Use UHF dipole adjustment', 'option', '', 'U.S.:Canada:Mexico', '<HTML>Apply dipole adjustment to TV UHF terrain-sensitive service threshold levels.</HTML>', false)," +
			"(323, 7, 15, 'Dipole center frequency', 'decimal:470:700', 'MHz', 'U.S.:Canada:Mexico', '<HTML>Center frequency for dipole adjustment of TV UHF terrain-sensitive service threshold levels.</HTML>', false)," +
			"(364, 7, 16, 'FM full-service threshold', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Terrain-sensitive service threshold level, FM full-service for most commercial-band classes.</HTML>', false)," +
			"(365, 7, 17, 'FM full-service threshold, class B', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Terrain-sensitive service threshold level, FM full-service class B.</HTML>', false)," +
			"(366, 7, 18, 'FM full-service threshold, class B1', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Terrain-sensitive service threshold level, FM full-service class B1.</HTML>', false)," +
			"(367, 7, 19, 'FM NCE threshold', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Terrain-sensitive service threshold level, FM non-commercial/educational band.</HTML>', false)," +
			"(368, 7, 20, 'LPFM threshold', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Terrain-sensitive service threshold level, low-power FM.</HTML>', false)," +
			"(369, 7, 21, 'FM translator threshold', 'decimal:0:120', 'dBu', 'U.S.:Canada:Mexico', '<HTML>Terrain-sensitive service threshold level, FM translator.</HTML>', false)");

		db.update("INSERT INTO template_parameter_data VALUES " +
			"(1, 309, 0, '0')," +
			"(1, 310, 0, '28'),(1, 310, 1, '28'),(1, 310, 2, '28')," +
			"(1, 311, 0, '36'),(1, 311, 1, '36'),(1, 311, 2, '36')," +
			"(1, 312, 0, '41'),(1, 312, 1, '41'),(1, 312, 2, '41')," +
			"(1, 313, 0, '43'),(1, 313, 1, '43'),(1, 313, 2, '43')," +
			"(1, 314, 0, '48'),(1, 314, 1, '48'),(1, 314, 2, '48')," +
			"(1, 315, 0, '51'),(1, 315, 1, '51'),(1, 315, 2, '51')," +
			"(1, 316, 0, '47'),(1, 316, 1, '47'),(1, 316, 2, '47')," +
			"(1, 317, 0, '56'),(1, 317, 1, '56'),(1, 317, 2, '56')," +
			"(1, 318, 0, '64'),(1, 318, 1, '64'),(1, 318, 2, '64')," +
			"(1, 319, 0, '62'),(1, 319, 1, '62'),(1, 319, 2, '62')," +
			"(1, 320, 0, '68'),(1, 320, 1, '68'),(1, 320, 2, '68')," +
			"(1, 321, 0, '74'),(1, 321, 1, '74'),(1, 321, 2, '74')," +
			"(1, 322, 0, '1'),(1, 322, 1, '1'),(1, 322, 2, '1')," +
			"(1, 323, 0, '615'),(1, 323, 1, '615'),(1, 323, 2, '615')," +
			"(1, 364, 0, '60'),(1, 364, 1, '60'),(1, 364, 2, '60')," +
			"(1, 365, 0, '54'),(1, 365, 1, '54'),(1, 365, 2, '54')," +
			"(1, 366, 0, '57'),(1, 366, 1, '57'),(1, 366, 2, '57')," +
			"(1, 367, 0, '60'),(1, 367, 1, '60'),(1, 367, 2, '60')," +
			"(1, 368, 0, '60'),(1, 368, 1, '60'),(1, 368, 2, '60')," +
			"(1, 369, 0, '60'),(1, 369, 1, '60'),(1, 369, 2, '60')");

		db.update("INSERT INTO parameter_study_type VALUES " +
			"(309, 1),(309, 2),(309, 3),(309, 4),(309, 5)," +
			"(310, 1),(310, 2),(310, 3),(310, 5)," +
			"(311, 1),(311, 2),(311, 3),(311, 5)," +
			"(312, 1),(312, 2),(312, 3),(312, 5)," +
			"(313, 1),(313, 2),(313, 3),(313, 5)," +
			"(314, 1),(314, 2),(314, 3),(314, 5)," +
			"(315, 1),(315, 2),(315, 3),(315, 5)," +
			"(316, 1),(316, 2),(316, 3),(316, 5)," +
			"(317, 1),(317, 2),(317, 3),(317, 5)," +
			"(318, 1),(318, 2),(318, 3),(318, 5)," +
			"(319, 1),(319, 2),(319, 3),(319, 5)," +
			"(320, 1),(320, 2),(320, 3),(320, 5)," +
			"(321, 1),(321, 2),(321, 3),(321, 5)," +
			"(322, 1),(322, 2),(322, 3),(322, 5)," +
			"(323, 1),(323, 2),(323, 3),(323, 5)," +
			"(364, 4),(364, 5)," +
			"(365, 4),(365, 5)," +
			"(366, 4),(366, 5)," +
			"(367, 4),(367, 5)," +
			"(368, 4),(368, 5)," +
			"(369, 4),(369, 5)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// Clutter

		db.update("INSERT INTO parameter VALUES " +
			"(249, 8, 1, 'Apply clutter adjustments', 'option', '', '', '<HTML>Adjust field-strength values for receiver clutter type and channel band, based on land-cover category at study point.</HTML>', false)," +
			"(205, 8, 2, 'Land cover database', 'pickfrom:2006:NLCD 2006:2011:NLCD 2011', '', '', '<HTML>Land cover database.</HTML>', false)," +
			"(250, 8, 3, 'Open land, low VHF/FM', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(251, 8, 4, 'Open land, high VHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(252, 8, 5, 'Open land, low UHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(253, 8, 6, 'Open land, high UHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(254, 8, 7, 'Agricultural, low VHF/FM', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(255, 8, 8, 'Agricultural, high VHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(256, 8, 9, 'Agricultural, low UHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(257, 8, 10, 'Agricultural, high UHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(258, 8, 11, 'Rangeland, low VHF/FM', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(259, 8, 12, 'Rangeland, high VHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(260, 8, 13, 'Rangeland, low UHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(261, 8, 14, 'Rangeland, high UHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(262, 8, 15, 'Water, low VHF/FM', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(263, 8, 16, 'Water, high VHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(264, 8, 17, 'Water, low UHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(265, 8, 18, 'Water, high UHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(266, 8, 19, 'Forest land, low VHF/FM', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(267, 8, 20, 'Forest land, high VHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(268, 8, 21, 'Forest land, low UHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(269, 8, 22, 'Forest land, high UHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(270, 8, 23, 'Wetland, low VHF/FM', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(271, 8, 24, 'Wetland, high VHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(272, 8, 25, 'Wetland, low UHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(273, 8, 26, 'Wetland, high UHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(274, 8, 27, 'Residential, low VHF/FM', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(275, 8, 28, 'Residential, high VHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(276, 8, 29, 'Residential, low UHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(277, 8, 30, 'Residential, high UHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(278, 8, 31, 'Mixed Urban / Buildings, low VHF/FM', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(279, 8, 32, 'Mixed Urban / Buildings, high VHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(280, 8, 33, 'Mixed Urban / Buildings, low UHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(281, 8, 34, 'Mixed Urban / Buildings, high UHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(282, 8, 35, 'Commercial / Industrial, low VHF/FM', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(283, 8, 36, 'Commercial / Industrial, high VHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(284, 8, 37, 'Commercial / Industrial, low UHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(285, 8, 38, 'Commercial / Industrial, high UHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(286, 8, 39, 'Snow and Ice, low VHF/FM', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(287, 8, 40, 'Snow and Ice, high VHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(288, 8, 41, 'Snow and Ice, low UHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(289, 8, 42, 'Snow and Ice, high UHF', 'decimal:-30:30', 'dB', 'U.S.:Canada:Mexico', '<HTML>Adjustment for clutter type and channel band.<HTML>', false)," +
			"(290, 8, 43, 'Open water', 'pickfrom:1:Open land:2:Agricultural:3:Rangeland:4:Water:5:Forest land:6:Wetland:7:Residential:8:Mixed Urban / Buildings:9:Commercial / Industrial:10:Snow and Ice', '', '', '<HTML>Clutter type for land-cover category.</HTML>', false)," +
			"(291, 8, 44, 'Perennial ice/snow', 'pickfrom:1:Open land:2:Agricultural:3:Rangeland:4:Water:5:Forest land:6:Wetland:7:Residential:8:Mixed Urban / Buildings:9:Commercial / Industrial:10:Snow and Ice', '', '', '<HTML>Clutter type for land-cover category.</HTML>', false)," +
			"(292, 8, 45, 'Developed, open space', 'pickfrom:1:Open land:2:Agricultural:3:Rangeland:4:Water:5:Forest land:6:Wetland:7:Residential:8:Mixed Urban / Buildings:9:Commercial / Industrial:10:Snow and Ice', '', '', '<HTML>Clutter type for land-cover category.</HTML>', false)," +
			"(293, 8, 46, 'Developed, low intensity', 'pickfrom:1:Open land:2:Agricultural:3:Rangeland:4:Water:5:Forest land:6:Wetland:7:Residential:8:Mixed Urban / Buildings:9:Commercial / Industrial:10:Snow and Ice', '', '', '<HTML>Clutter type for land-cover category.</HTML>', false)," +
			"(294, 8, 47, 'Developed, medium intensity', 'pickfrom:1:Open land:2:Agricultural:3:Rangeland:4:Water:5:Forest land:6:Wetland:7:Residential:8:Mixed Urban / Buildings:9:Commercial / Industrial:10:Snow and Ice', '', '', '<HTML>Clutter type for land-cover category.</HTML>', false)," +
			"(295, 8, 48, 'Developed, high intensity', 'pickfrom:1:Open land:2:Agricultural:3:Rangeland:4:Water:5:Forest land:6:Wetland:7:Residential:8:Mixed Urban / Buildings:9:Commercial / Industrial:10:Snow and Ice', '', '', '<HTML>Clutter type for land-cover category.</HTML>', false)," +
			"(296, 8, 49, 'Barren land (rock/sand/clay)', 'pickfrom:1:Open land:2:Agricultural:3:Rangeland:4:Water:5:Forest land:6:Wetland:7:Residential:8:Mixed Urban / Buildings:9:Commercial / Industrial:10:Snow and Ice', '', '', '<HTML>Clutter type for land-cover category.</HTML>', false)," +
			"(297, 8, 50, 'Deciduous forest', 'pickfrom:1:Open land:2:Agricultural:3:Rangeland:4:Water:5:Forest land:6:Wetland:7:Residential:8:Mixed Urban / Buildings:9:Commercial / Industrial:10:Snow and Ice', '', '', '<HTML>Clutter type for land-cover category.</HTML>', false)," +
			"(298, 8, 51, 'Evergreen forest', 'pickfrom:1:Open land:2:Agricultural:3:Rangeland:4:Water:5:Forest land:6:Wetland:7:Residential:8:Mixed Urban / Buildings:9:Commercial / Industrial:10:Snow and Ice', '', '', '<HTML>Clutter type for land-cover category.</HTML>', false)," +
			"(299, 8, 52, 'Mixed forest', 'pickfrom:1:Open land:2:Agricultural:3:Rangeland:4:Water:5:Forest land:6:Wetland:7:Residential:8:Mixed Urban / Buildings:9:Commercial / Industrial:10:Snow and Ice', '', '', '<HTML>Clutter type for land-cover category.</HTML>', false)," +
			"(300, 8, 53, 'Shrub/scrub', 'pickfrom:1:Open land:2:Agricultural:3:Rangeland:4:Water:5:Forest land:6:Wetland:7:Residential:8:Mixed Urban / Buildings:9:Commercial / Industrial:10:Snow and Ice', '', '', '<HTML>Clutter type for land-cover category.</HTML>', false)," +
			"(301, 8, 54, 'Grassland/herbaceous', 'pickfrom:1:Open land:2:Agricultural:3:Rangeland:4:Water:5:Forest land:6:Wetland:7:Residential:8:Mixed Urban / Buildings:9:Commercial / Industrial:10:Snow and Ice', '', '', '<HTML>Clutter type for land-cover category.</HTML>', false)," +
			"(302, 8, 55, 'Pasture/hay', 'pickfrom:1:Open land:2:Agricultural:3:Rangeland:4:Water:5:Forest land:6:Wetland:7:Residential:8:Mixed Urban / Buildings:9:Commercial / Industrial:10:Snow and Ice', '', '', '<HTML>Clutter type for land-cover category.</HTML>', false)," +
			"(303, 8, 56, 'Cultivated crops', 'pickfrom:1:Open land:2:Agricultural:3:Rangeland:4:Water:5:Forest land:6:Wetland:7:Residential:8:Mixed Urban / Buildings:9:Commercial / Industrial:10:Snow and Ice', '', '', '<HTML>Clutter type for land-cover category.</HTML>', false)," +
			"(304, 8, 57, 'Woody wetlands', 'pickfrom:1:Open land:2:Agricultural:3:Rangeland:4:Water:5:Forest land:6:Wetland:7:Residential:8:Mixed Urban / Buildings:9:Commercial / Industrial:10:Snow and Ice', '', '', '<HTML>Clutter type for land-cover category.</HTML>', false)," +
			"(305, 8, 58, 'Emergent herbaceous wetlands', 'pickfrom:1:Open land:2:Agricultural:3:Rangeland:4:Water:5:Forest land:6:Wetland:7:Residential:8:Mixed Urban / Buildings:9:Commercial / Industrial:10:Snow and Ice', '', '', '<HTML>Clutter type for land-cover category.</HTML>', false)");

		db.update("INSERT INTO template_parameter_data VALUES " +
			"(1, 249, 0, '0')," +
			"(1, 205, 0, '2006')," +
			"(1, 250, 0, '0'),(1, 250, 1, '0'),(1, 250, 2, '0')," +
			"(1, 251, 0, '0'),(1, 251, 1, '0'),(1, 251, 2, '0')," +
			"(1, 252, 0, '-4'),(1, 252, 1, '-4'),(1, 252, 2, '-4')," +
			"(1, 253, 0, '-5'),(1, 253, 1, '-5'),(1, 253, 2, '-5')," +
			"(1, 254, 0, '0'),(1, 254, 1, '0'),(1, 254, 2, '0')," +
			"(1, 255, 0, '0'),(1, 255, 1, '0'),(1, 255, 2, '0')," +
			"(1, 256, 0, '-5'),(1, 256, 1, '-5'),(1, 256, 2, '-5')," +
			"(1, 257, 0, '-6'),(1, 257, 1, '-6'),(1, 257, 2, '-6')," +
			"(1, 258, 0, '0'),(1, 258, 1, '0'),(1, 258, 2, '0')," +
			"(1, 259, 0, '0'),(1, 259, 1, '0'),(1, 259, 2, '0')," +
			"(1, 260, 0, '-3'),(1, 260, 1, '-3'),(1, 260, 2, '-3')," +
			"(1, 261, 0, '-6'),(1, 261, 1, '-6'),(1, 261, 2, '-6')," +
			"(1, 262, 0, '0'),(1, 262, 1, '0'),(1, 262, 2, '0')," +
			"(1, 263, 0, '0'),(1, 263, 1, '0'),(1, 263, 2, '0')," +
			"(1, 264, 0, '0'),(1, 264, 1, '0'),(1, 264, 2, '0')," +
			"(1, 265, 0, '0'),(1, 265, 1, '0'),(1, 265, 2, '0')," +
			"(1, 266, 0, '0'),(1, 266, 1, '0'),(1, 266, 2, '0')," +
			"(1, 267, 0, '0'),(1, 267, 1, '0'),(1, 267, 2, '0')," +
			"(1, 268, 0, '-5'),(1, 268, 1, '-5'),(1, 268, 2, '-5')," +
			"(1, 269, 0, '-8'),(1, 269, 1, '-8'),(1, 269, 2, '-8')," +
			"(1, 270, 0, '0'),(1, 270, 1, '0'),(1, 270, 2, '0')," +
			"(1, 271, 0, '0'),(1, 271, 1, '0'),(1, 271, 2, '0')," +
			"(1, 272, 0, '0'),(1, 272, 1, '0'),(1, 272, 2, '0')," +
			"(1, 273, 0, '0'),(1, 273, 1, '0'),(1, 273, 2, '0')," +
			"(1, 274, 0, '0'),(1, 274, 1, '0'),(1, 274, 2, '0')," +
			"(1, 275, 0, '0'),(1, 275, 1, '0'),(1, 275, 2, '0')," +
			"(1, 276, 0, '-5'),(1, 276, 1, '-5'),(1, 276, 2, '-5')," +
			"(1, 277, 0, '-7'),(1, 277, 1, '-7'),(1, 277, 2, '-7')," +
			"(1, 278, 0, '0'),(1, 278, 1, '0'),(1, 278, 2, '0')," +
			"(1, 279, 0, '0'),(1, 279, 1, '0'),(1, 279, 2, '0')," +
			"(1, 280, 0, '-6'),(1, 280, 1, '-6'),(1, 280, 2, '-6')," +
			"(1, 281, 0, '-6'),(1, 281, 1, '-6'),(1, 281, 2, '-6')," +
			"(1, 282, 0, '0'),(1, 282, 1, '0'),(1, 282, 2, '0')," +
			"(1, 283, 0, '0'),(1, 283, 1, '0'),(1, 283, 2, '0')," +
			"(1, 284, 0, '-5'),(1, 284, 1, '-5'),(1, 284, 2, '-5')," +
			"(1, 285, 0, '-6'),(1, 285, 1, '-6'),(1, 285, 2, '-6')," +
			"(1, 286, 0, '0'),(1, 286, 1, '0'),(1, 286, 2, '0')," +
			"(1, 287, 0, '0'),(1, 287, 1, '0'),(1, 287, 2, '0')," +
			"(1, 288, 0, '0'),(1, 288, 1, '0'),(1, 288, 2, '0')," +
			"(1, 289, 0, '0'),(1, 289, 1, '0'),(1, 289, 2, '0')," +
			"(1, 290, 0, '4')," +
			"(1, 291, 0, '10')," +
			"(1, 292, 0, '1')," +
			"(1, 293, 0, '7')," +
			"(1, 294, 0, '7')," +
			"(1, 295, 0, '8')," +
			"(1, 296, 0, '1')," +
			"(1, 297, 0, '5')," +
			"(1, 298, 0, '5')," +
			"(1, 299, 0, '5')," +
			"(1, 300, 0, '3')," +
			"(1, 301, 0, '3')," +
			"(1, 302, 0, '2')," +
			"(1, 303, 0, '2')," +
			"(1, 304, 0, '5')," +
			"(1, 305, 0, '6')");

		db.update("INSERT INTO parameter_study_type VALUES " +
			"(249, 1),(249, 2),(249, 3),(249, 4),(249, 5)," +
			"(205, 1),(205, 2),(205, 3),(205, 4),(205, 5)," +
			"(250, 1),(250, 2),(250, 3),(250, 4),(250, 5)," +
			"(251, 1),(251, 2),(251, 3)," +
			"(252, 1),(252, 2),(252, 3)," +
			"(253, 1),(253, 2),(253, 3)," +
			"(254, 1),(254, 2),(254, 3),(254, 4),(254, 5)," +
			"(255, 1),(255, 2),(255, 3)," +
			"(256, 1),(256, 2),(256, 3)," +
			"(257, 1),(257, 2),(257, 3)," +
			"(258, 1),(258, 2),(258, 3),(258, 4),(258, 5)," +
			"(259, 1),(259, 2),(259, 3)," +
			"(260, 1),(260, 2),(260, 3)," +
			"(261, 1),(261, 2),(261, 3)," +
			"(262, 1),(262, 2),(262, 3),(262, 4),(262, 5)," +
			"(263, 1),(263, 2),(263, 3)," +
			"(264, 1),(264, 2),(264, 3)," +
			"(265, 1),(265, 2),(265, 3)," +
			"(266, 1),(266, 2),(266, 3),(266, 4),(266, 5)," +
			"(267, 1),(267, 2),(267, 3)," +
			"(268, 1),(268, 2),(268, 3)," +
			"(269, 1),(269, 2),(269, 3)," +
			"(270, 1),(270, 2),(270, 3),(270, 4),(270, 5)," +
			"(271, 1),(271, 2),(271, 3)," +
			"(272, 1),(272, 2),(272, 3)," +
			"(273, 1),(273, 2),(273, 3)," +
			"(274, 1),(274, 2),(274, 3),(274, 4),(274, 5)," +
			"(275, 1),(275, 2),(275, 3)," +
			"(276, 1),(276, 2),(276, 3)," +
			"(277, 1),(277, 2),(277, 3)," +
			"(278, 1),(278, 2),(278, 3),(278, 4),(278, 5)," +
			"(279, 1),(279, 2),(279, 3)," +
			"(280, 1),(280, 2),(280, 3)," +
			"(281, 1),(281, 2),(281, 3)," +
			"(282, 1),(282, 2),(282, 3),(282, 4),(282, 5)," +
			"(283, 1),(283, 2),(283, 3)," +
			"(284, 1),(284, 2),(284, 3)," +
			"(285, 1),(285, 2),(285, 3)," +
			"(286, 1),(286, 2),(286, 3),(286, 4),(286, 5)," +
			"(287, 1),(287, 2),(287, 3)," +
			"(288, 1),(288, 2),(288, 3)," +
			"(289, 1),(289, 2),(289, 3)," +
			"(290, 1),(290, 2),(290, 3),(290, 4),(290, 5)," +
			"(291, 1),(291, 2),(291, 3),(291, 4),(291, 5)," +
			"(292, 1),(292, 2),(292, 3),(292, 4),(292, 5)," +
			"(293, 1),(293, 2),(293, 3),(293, 4),(293, 5)," +
			"(294, 1),(294, 2),(294, 3),(294, 4),(294, 5)," +
			"(295, 1),(295, 2),(295, 3),(295, 4),(295, 5)," +
			"(296, 1),(296, 2),(296, 3),(296, 4),(296, 5)," +
			"(297, 1),(297, 2),(297, 3),(297, 4),(297, 5)," +
			"(298, 1),(298, 2),(298, 3),(298, 4),(298, 5)," +
			"(299, 1),(299, 2),(299, 3),(299, 4),(299, 5)," +
			"(300, 1),(300, 2),(300, 3),(300, 4),(300, 5)," +
			"(301, 1),(301, 2),(301, 3),(301, 4),(301, 5)," +
			"(302, 1),(302, 2),(302, 3),(302, 4),(302, 5)," +
			"(303, 1),(303, 2),(303, 3),(303, 4),(303, 5)," +
			"(304, 1),(304, 2),(304, 3),(304, 4),(304, 5)," +
			"(305, 1),(305, 2),(305, 3),(305, 4),(305, 5)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// TV IX Check

		db.update("INSERT INTO parameter VALUES " +
			"(216, 12, 5, 'Maximum new interference', 'decimal:0:10', '%', '', '<HTML>Maximum amount of new interference created to full service and Class A stations, percent of population served.</HTML>', false)," +
			"(217, 12, 6, 'Maximum new interference to LPTV', 'decimal:0:10', '%', '', '<HTML>Maximum amount of new interference created to LPTV stations, percent of population served.</HTML>', false)," +
			"(211, 12, 7, 'Check proposal vs. baseline', 'option', '', '', '<HTML>Compare service contour and terrain-limited population of proposal to baseline record, if any, using following parameters.</HTML>', false)," +
			"(213, 12, 8, 'Maximum contour extension from baseline', 'decimal:0:10', '%', '', '<HTML>Maximum amount by which service contour may extend beyond the baseline contour, percent of contour distance.</HTML>', false)," +
			"(214, 12, 9, 'Maximum population reduction from baseline', 'decimal:0:10', '%', '', '<HTML>Maximum amount by which terrain-limitied population may be reduced from baseline, percent.</HTML>', false)");

		db.update("INSERT INTO template_parameter_data VALUES " +
			"(1, 216, 0, '0.5')," +
			"(1, 217, 0, '2')," +
			"(1, 211, 0, '1')," +
			"(1, 213, 0, '1')," +
			"(1, 214, 0, '5')");

		db.update("INSERT INTO parameter_study_type VALUES " +
			"(216, 2)," +
			"(217, 2)," +
			"(211, 2)," +
			"(213, 2)," +
			"(214, 2)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// Wireless

		db.update("INSERT INTO parameter VALUES " +
			"(341, 9, 1, 'Required D/U', 'decimal:-50:50', 'dB', '-5 to 0 MHz overlap:1 MHz overlap:2 MHz overlap:3 MHz overlap:4 MHz overlap:5 MHz overlap', '<HTML>Required D/U for wireless into TV interference.</HTML>', false)," +
			"(247, 9, 2, 'Culling distances', 'table:8:305 m:200 m:150 m:100 m:80 m:65 m:50 m:35 m:9:5 kW:4 kW:3 kW:2 kW:1 kW:0.75 kW:0.5 kW:0.25 kW:0.1 kW:decimal:1:500', '', '-5 to 0 MHz overlap:1 MHz overlap:2 MHz overlap:3 MHz overlap:4 MHz overlap:5 MHz overlap', '<HTML>Tables of culling distances in kilometers for wireless undesired stations.</HTML>', false)," +
			"(308, 9, 3, 'Cap alpha factor', 'option', '', '', '<HTML>Set a cap on the amount by which the required D/U for wireless interference to digital TV with spectral overlap >0 is increased as the desired signal nears the service threshold.</HTML>', false)," +
			"(342, 9, 4, 'Alpha factor cap', 'decimal:0:36.4', 'dB', '', '<HTML>Cap value for digital TV co-channel D/U adjustment.</HTML>', false)," +
			"(343, 9, 5, 'Average terrain profile resolution', 'decimal:1:50', 'pts/km', '', '<HTML>Profile resolution in points per kilometer for average terrain calculations for wireless stations.</HTML>', false)," +
			"(344, 9, 6, 'Propagation profile resolution', 'decimal:1:50', 'pts/km', '', '<HTML>Profile resolution in points per kilometer for propagation calculations for wireless stations.</HTML>', false)," +
			"(345, 9, 7, 'HAAT radial count', 'integer:4:360', '', '', '<HTML>Number of radials used to calculate HAAT when deriving AMSL height or verifying database HAAT for wireless stations.</HTML>', false)," +
			"(346, 9, 8, 'Depression angle method', 'pickfrom:1:Effective height:2:True geometry', '', '', '<HTML>Method of computing depression angle for wireless station elevation pattern lookup:<BR>Effective height - Use transmitter HAAT or height AGL with distance-squared approximation formula.<BR>True geometry - Compute exact angle from transmitter to receiver over curved earth.</HTML>', false)," +
			"(347, 9, 9, 'Use mechanical beam tilt', 'option', '', '', '<HTML>Apply mechanical beam tilt to wireless station elevation patterns.<BR>Electrical beam tilt is always applied.</HTML>', false)," +
			"(348, 9, 10, 'Mirror generic patterns', 'pickfrom:0:Never:1:Always', '', '', '<HTML>Mirror generic patterns around the depression angle of the maximum for wireless stations.<BR>If not selected, pattern is 1.0 at angles above the maximum.<BR>Non-generic patterns are always mirrored if needed.</HTML>', false)," +
			"(349, 9, 11, 'Minimum transmitter height AGL', 'decimal:0.5:50', 'm', '', '<HTML>Minimum transmitter height above ground for wireless stations.<BR>Transmitter height AMSL is increased as needed to meet this minimum.</HTML>', false)," +
			"(350, 9, 12, 'Undesired % location', 'decimal:0.01:99.99', '%', '', '<HTML>Propagation model statistical parameter, % location for undesired signals from wireless stations.</HTML>', false)," +
			"(351, 9, 13, 'Undesired % time', 'decimal:0.01:99.99', '%', '', '<HTML>Propagation model statistical parameter, % time for undesired signals from wireless stations.</HTML>', false)," +
			"(352, 9, 14, 'Undesired % confidence', 'decimal:0.01:99.99', '%', '', '<HTML>Propagation model statistical parameter, % confidence for undesired signals from wireless stations.</HTML>', false)," +
			"(353, 9, 15, 'Signal polarization', 'pickfrom:0:Horizontal:1:Vertical', '', '', '<HTML>Propagation model signal polarization for wireless stations.</HTML>', false)," +
			"(354, 9, 16, 'Service mode', 'pickfrom:0:Single-message:1:Individual:2:Mobile:3:Broadcast', '', '', '<HTML>Propagation model service mode for wireless stations.</HTML>', false)");

		db.update("INSERT INTO template_parameter_data VALUES " +
			"(1, 341, 0, '-33'),(1, 341, 1, '9.3'),(1, 341, 2, '12.1'),(1, 341, 3, '13.8'),(1, 341, 4, '15.1'),(1, 341, 5, '16')," +
			"(1, 247, 0, '" +
				"23,22,20,18,14,13,12,10,8," +
				"18,17,16,14,11,11,10,8,6," +
				"15,14,13,12,10,9,8,7,6," +
				"12,11,11,10,8,8,7,6,5," +
				"11,10,10,9,7,7,6,5,4," +
				"10,9,9,8,7,6,6,5,4," +
				"9,8,8,7,6,6,5,4,3," +
				"7,7,6,6,5,5,4,3,3')," +
			"(1, 247, 1, '" +
				"176,171,165,155,138,131,122,106,90," +
				"165,160,153,143,127,120,111,95,78," +
				"159,154,147,137,121,114,105,90,72," +
				"151,146,139,128,113,106,97,82,66," +
				"148,142,135,125,109,102,93,78,63," +
				"145,139,132,122,106,99,90,75,60," +
				"141,135,128,118,102,95,86,72,57," +
				"136,131,124,115,98,92,83,68,54')," +
			"(1, 247, 2, '" +
				"193,187,180,170,154,147,137,121,102," +
				"180,175,168,159,142,136,126,110,90," +
				"174,169,163,153,136,129,120,105,85," +
				"167,162,155,145,128,121,113,97,77," +
				"163,158,151,142,124,118,108,92,73," +
				"160,154,148,138,121,114,105,89,70," +
				"156,151,145,135,118,111,101,86,67," +
				"152,147,140,130,114,107,98,82,63')," +
			"(1, 247, 3, '" +
				"202,197,190,179,164,157,146,130,109," +
				"189,184,177,167,151,144,135,119,98," +
				"183,178,171,162,145,138,129,113,92," +
				"177,171,164,154,137,130,121,105,84," +
				"172,167,159,150,133,126,117,101,80," +
				"169,163,156,147,130,123,114,97,77," +
				"165,160,153,144,127,120,110,94,74," +
				"161,156,149,139,123,116,106,90,71')," +
			"(1, 247, 4, '" +
				"210,205,198,187,170,164,154,137,116," +
				"198,192,185,175,159,152,142,126,105," +
				"191,186,179,169,153,146,136,120,99," +
				"185,179,172,162,145,138,128,112,92," +
				"181,175,168,158,141,134,124,108,87," +
				"177,172,164,154,138,131,121,105,84," +
				"174,168,160,151,134,128,118,101,81," +
				"169,164,156,147,130,123,114,98,77')," +
			"(1, 247, 5, '" +
				"215,209,202,192,174,168,159,142,120," +
				"204,197,189,179,163,157,147,130,109," +
				"196,190,183,173,157,150,141,124,104," +
				"189,184,176,166,150,143,132,117,96," +
				"185,180,172,162,146,139,129,113,91," +
				"182,176,169,159,143,136,126,109,88," +
				"178,173,165,155,139,132,122,106,85," +
				"174,168,161,151,134,128,118,102,81')," +
			"(1, 308, 0, '1')," +
			"(1, 342, 0, '8')," +
			"(1, 343, 0, '10')," +
			"(1, 344, 0, '10')," +
			"(1, 345, 0, '8')," +
			"(1, 346, 0, '2')," +
			"(1, 347, 0, '1')," +
			"(1, 348, 0, '1')," +
			"(1, 349, 0, '10')," +
			"(1, 350, 0, '50')," +
			"(1, 351, 0, '10')," +
			"(1, 352, 0, '50')," +
			"(1, 353, 0, '1')," +
			"(1, 354, 0, '3')");

		db.update("INSERT INTO parameter_study_type VALUES " +
			"(341, 3)," +
			"(247, 3)," +
			"(308, 3)," +
			"(342, 3)," +
			"(343, 3)," +
			"(344, 3)," +
			"(345, 3)," +
			"(346, 3)," +
			"(347, 3)," +
			"(348, 3)," +
			"(349, 3)," +
			"(350, 3)," +
			"(351, 3)," +
			"(352, 3)," +
			"(353, 3)," +
			"(354, 3)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// TV6 <-> FM

		db.update("INSERT INTO parameter VALUES " +
			"(244, 10, 1, 'FM undesired culling distances', 'table:1:Dist. km:21:Ch. 200:201:202:203:204:205:206:207:208:209:210:211:212:213:214:215:216:217:218:219:220:decimal:1:500', '', '', '<HTML>Table of culling distances in km for FM to TV/DTV interference.</HTML>', false)," +
			"(246, 10, 2, 'FM to TV D/U curves', 'table:44:47 dBu:48:49:50:51:52:53:54:55:56:57:58:59:60:61:62:63:64:65:66:67:68:69:70:71:72:73:74:75:76:77:78:79:80:81:82:83:84:85:86:87:88:89:90:21:Ch. 200:201:202:203:204:205:206:207:208:209:210:211:212:213:214:215:216:217:218:219:220:decimal:-50:50', '', '', '<HTML>Lookup tables defining curves of required D/U values in dB for FM to TV interference.</HTML>', false)," +
			"(203, 10, 3, 'FM to DTV D/U method', 'pickfrom:1:Curves:3:Fixed', '', '', '<HTML>Method used to determine FM to DTV required D/U:<BR>Curves - FCC Rules 73.525 method, curves are extrapolated<BR>Fixed - Fixed D/U table values per FM channel.<HTML>', false)," +
			"(204, 10, 4, 'FM to DTV fixed D/U table', 'table:1:D/U dB:21:Ch. 200:201:202:203:204:205:206:207:208:209:210:211:212:213:214:215:216:217:218:219:220:decimal:-50:50', '', '', '<HTML>Lookup table of required D/U values in dB for FM to DTV interference using fixed method.</HTML>', false)," +
			"(245, 10, 5, 'FM undesired % time', 'decimal:0.01:99.99', '%', '', '<HTML>Propagation model statistical parameter, % time for undesired signals from FM to TV/DTV.</HTML>', false)," +
			"(242, 10, 6, 'DTV undesired culling distances', 'table:1:Dist. km:21:Ch. 200:201:202:203:204:205:206:207:208:209:210:211:212:213:214:215:216:217:218:219:220:decimal:1:500', '', '', '<HTML>Table of culling distances in km for DTV to FM interference.<BR>Note: TV into FM treats TV as an FM on channel 199 then applies normal FM<->FM rules.</HTML>', false)," +
			"(218, 10, 7, 'DTV to FM base D/U', 'decimal:-50:50', 'dB', '', '<HTML>Base required D/U for DTV to FM interference, adjusted by DTV emission mask and channel separation.<BR>Note: TV into FM treats TV as an FM on channel 199 then applies normal FM<->FM rules.<HTML>', false)," +
			"(243, 10, 8, 'TV/DTV undesired % time', 'decimal:0.01:99.99', '%', '', '<HTML>Propagation model statistical parameter, % time for undesired signals from TV/DTV to FM.</HTML>', false)");

		db.update("INSERT INTO template_parameter_data VALUES " +
			"(1, 244, 0, '400,265,257,246,235,225,211,196,196,196,196,196,195,193,187,180,177,174,166,159,154')," +
			"(1, 246, 0, '" +
				"12.5,-1,-3.8,-6.5,-9.3,-12.1,-16.5,-20.3,-20.3,-20.3,-20.3,-20.3,-21.8,-22.5,-24.7,-27,-28,-29,-32.5,-36,-39," +
				"12.8,-0.7,-3.4,-6.1,-8.8,-11.6,-15.3,-18.7,-18.7,-18.7,-18.7,-18.7,-20.3,-21.4,-22.9,-25.1,-26.3,-27.7,-31.1,-34.8,-37.7," +
				"13.2,-0.3,-3,-5.6,-8.4,-11.1,-14.2,-17.3,-17.3,-17.3,-17.3,-17.3,-19,-20.1,-21.4,-23.4,-24.6,-26.4,-29.7,-33.6,-36.3," +
				"13.5,0,-2.6,-5.2,-7.9,-10.7,-13.1,-16.1,-16.1,-16.1,-16.1,-16.1,-17.7,-18.9,-20,-21.8,-23.1,-25.1,-28.4,-32.3,-35," +
				"13.8,0.3,-2.2,-4.8,-7.5,-10.2,-12.2,-15,-15,-15,-15,-15,-16.6,-17.8,-18.7,-20.4,-21.6,-23.8,-27.1,-31.1,-33.8," +
				"14.1,0.6,-1.8,-4.3,-7.1,-9.8,-11.2,-13.9,-13.9,-13.9,-13.9,-13.9,-15.4,-16.7,-17.5,-18.9,-20.3,-22.5,-25.9,-29.9,-32.5," +
				"14.4,0.9,-1.5,-3.9,-6.6,-9.3,-10.4,-12.9,-12.9,-12.9,-12.9,-12.9,-14.4,-15.6,-16.2,-17.6,-19,-21.3,-24.6,-28.7,-31.3," +
				"14.7,1.2,-1.1,-3.5,-6.2,-8.8,-9.7,-12,-12,-12,-12,-12,-13.4,-14.6,-15,-16.4,-17.7,-20.1,-23.4,-27.5,-30.2," +
				"15,1.5,-0.8,-3.1,-5.8,-8.4,-8.9,-11.1,-11.1,-11.1,-11.1,-11.1,-12.4,-13.6,-13.9,-15.2,-16.5,-19,-22.2,-26.4,-29," +
				"15.3,1.8,-0.4,-2.7,-5.4,-8,-8.3,-10.2,-10.2,-10.2,-10.2,-10.2,-11.5,-12.7,-12.9,-14.1,-15.4,-17.9,-21.1,-25.4,-27.8," +
				"15.6,2.1,-0.1,-2.3,-4.9,-7.6,-7.7,-9.4,-9.4,-9.4,-9.4,-9.4,-10.5,-11.7,-12,-13,-14.4,-16.9,-20.1,-24.4,-26.7," +
				"15.9,2.4,0.2,-1.9,-4.5,-7.2,-7.2,-8.5,-8.5,-8.5,-8.5,-8.5,-9.6,-10.8,-11.1,-12.1,-13.4,-15.9,-19.2,-23.4,-25.7," +
				"16.2,2.7,0.6,-1.6,-4.1,-6.7,-6.6,-7.7,-7.7,-7.7,-7.7,-7.7,-8.8,-9.9,-10.3,-11.2,-12.5,-14.9,-18.3,-22.4,-24.7," +
				"16.4,2.9,0.9,-1.2,-3.7,-6.3,-6,-6.9,-6.9,-6.9,-6.9,-6.9,-8.1,-9.1,-9.5,-10.3,-11.7,-14,-17.4,-21.5,-23.7," +
				"16.7,3.2,1.2,-0.9,-3.4,-5.9,-5.5,-6.2,-6.2,-6.2,-6.2,-6.2,-7.4,-8.3,-8.8,-9.5,-10.9,-13.2,-16.6,-20.6,-22.7," +
				"16.9,3.4,1.5,-0.6,-3,-5.5,-5,-5.5,-5.5,-5.5,-5.5,-5.5,-6.7,-7.7,-8.1,-8.8,-10.2,-12.4,-15.8,-19.7,-21.8," +
				"17.2,3.7,1.7,-0.2,-2.7,-5.1,-4.5,-4.9,-4.9,-4.9,-4.9,-4.9,-6.1,-7.1,-7.4,-8.1,-9.6,-11.6,-15,-18.8,-20.8," +
				"17.4,3.9,2.1,0.1,-2.3,-4.7,-4.1,-4.4,-4.4,-4.4,-4.4,-4.4,-5.6,-6.5,-6.9,-7.5,-8.9,-10.8,-14.2,-18,-19.9," +
				"17.6,4.1,2.3,0.4,-2,-4.3,-3.7,-3.8,-3.8,-3.8,-3.8,-3.8,-5,-5.9,-6.3,-6.9,-8.3,-10.1,-13.5,-17.2,-19," +
				"17.8,4.3,2.5,0.6,-1.7,-4,-3.2,-3.3,-3.3,-3.3,-3.3,-3.3,-4.6,-5.5,-5.9,-6.4,-7.8,-9.5,-12.9,-16.6,-18.2," +
				"18,4.5,2.7,0.9,-1.4,-3.6,-2.8,-2.8,-2.8,-2.8,-2.8,-2.8,-4.2,-5.1,-5.5,-6.1,-7.4,-9,-12.4,-16.1,-17.6," +
				"18.1,4.6,2.9,1.1,-1.1,-3.3,-2.5,-2.5,-2.5,-2.5,-2.5,-2.5,-3.8,-4.7,-5.1,-5.7,-7.1,-8.6,-12,-15.7,-17.2," +
				"18.2,4.7,3.1,1.3,-0.8,-3.1,-2.2,-2.2,-2.2,-2.2,-2.2,-2.2,-3.4,-4.4,-4.9,-5.4,-6.8,-8.3,-11.7,-15.3,-16.8," +
				"18.4,4.9,3.2,1.5,-0.6,-2.8,-1.9,-1.9,-1.9,-1.9,-1.9,-1.9,-3.1,-4.1,-4.6,-5.2,-6.6,-8,-11.4,-14.9,-16.5," +
				"18.5,5,3.4,1.6,-0.4,-2.6,-1.7,-1.7,-1.7,-1.7,-1.7,-1.7,-2.8,-3.8,-4.4,-4.9,-6.4,-7.8,-11.2,-14.6,-16.3," +
				"18.6,5.1,3.5,1.8,-0.2,-2.3,-1.4,-1.4,-1.4,-1.4,-1.4,-1.4,-2.6,-3.6,-4.2,-4.8,-6.2,-7.6,-11,-14.4,-16," +
				"18.7,5.2,3.6,2,0,-2.1,-1.2,-1.2,-1.2,-1.2,-1.2,-1.2,-2.4,-3.4,-4.1,-4.6,-6,-7.4,-10.8,-14.2,-15.8," +
				"18.8,5.3,3.7,2.1,0.2,-1.8,-0.9,-0.9,-0.9,-0.9,-0.9,-0.9,-2.1,-3.2,-3.9,-4.4,-5.8,-7.3,-10.6,-14,-15.6," +
				"18.9,5.4,3.8,2.2,0.3,-1.6,-0.7,-0.7,-0.7,-0.7,-0.7,-0.7,-1.9,-3,-3.7,-4.3,-5.7,-7.1,-10.5,-13.8,-15.5," +
				"18.9,5.4,3.9,2.4,0.5,-1.4,-0.5,-0.5,-0.5,-0.5,-0.5,-0.5,-1.7,-2.8,-3.5,-4.1,-5.5,-6.9,-10.3,-13.6,-15.3," +
				"19,5.5,4,2.5,0.7,-1.3,-0.3,-0.3,-0.3,-0.3,-0.3,-0.3,-1.5,-2.6,-3.4,-3.9,-5.3,-6.7,-10.1,-13.5,-15.2," +
				"19.1,5.6,4.1,2.7,0.9,-1.1,-0.1,-0.1,-0.1,-0.1,-0.1,-0.1,-1.3,-2.4,-3.2,-3.8,-5.2,-6.6,-10,-13.4,-15," +
				"19.2,5.7,4.2,2.8,1,-0.8,0.1,0.1,0.1,0.1,0.1,0.1,-1.1,-2.2,-3,-3.6,-5,-6.4,-9.8,-13.2,-14.9," +
				"19.3,5.8,4.3,2.9,1.2,-0.6,0.3,0.3,0.3,0.3,0.3,0.3,-0.9,-2,-2.9,-3.4,-4.8,-6.3,-9.7,-13.1,-14.7," +
				"19.4,5.9,4.4,3,1.3,-0.4,0.5,0.5,0.5,0.5,0.5,0.5,-0.8,-1.8,-2.7,-3.3,-4.7,-6.1,-9.6,-13,-14.6," +
				"19.4,5.9,4.5,3.2,1.5,-0.2,0.6,0.6,0.6,0.6,0.6,0.6,-0.6,-1.6,-2.5,-3.2,-4.5,-5.9,-9.4,-12.8,-14.5," +
				"19.5,6,4.6,3.3,1.7,0,0.8,0.8,0.8,0.8,0.8,0.8,-0.4,-1.5,-2.4,-3,-4.4,-5.7,-9.3,-12.7,-14.4," +
				"19.6,6.1,4.6,3.4,1.9,0.2,1,1,1,1,1,1,-0.2,-1.3,-2.2,-2.9,-4.2,-5.6,-9.2,-12.5,-14.2," +
				"19.7,6.2,4.7,3.5,2,0.4,1.1,1.1,1.1,1.1,1.1,1.1,0,-1.1,-2,-2.7,-4.1,-5.5,-9.1,-12.4,-14.1," +
				"19.7,6.2,4.8,3.6,2.2,0.6,1.3,1.3,1.3,1.3,1.3,1.3,0.1,-1,-1.9,-2.6,-4,-5.4,-8.9,-12.3,-13.9," +
				"19.8,6.3,4.9,3.7,2.3,0.8,1.5,1.5,1.5,1.5,1.5,1.5,0.3,-0.8,-1.8,-2.4,-3.9,-5.2,-8.8,-12.2,-13.8," +
				"19.9,6.4,5,3.8,2.5,1,1.6,1.6,1.6,1.6,1.6,1.6,0.4,-0.7,-1.6,-2.3,-3.7,-5.1,-8.7,-12.1,-13.7," +
				"19.9,6.4,5,3.9,2.6,1.1,1.7,1.7,1.7,1.7,1.7,1.7,0.6,-0.5,-1.4,-2.1,-3.6,-4.9,-8.6,-12,-13.6," +
				"20,6.5,5.1,4,2.7,1.3,1.8,1.8,1.8,1.8,1.8,1.8,0.7,-0.4,-1.3,-2,-3.5,-4.8,-8.5,-11.9,-13.5')," +
			"(1, 203, 0, '1')," +
			"(1, 204, 0, '2,-20,-32,-40,-42,-43,-44,-44,-44,-45,-45,-46,-46,-46,-46,-47,-47,-48,-48,-49,-49')," +
			"(1, 245, 0, '10')," +
			"(1, 242, 0, '300,150,148,146,144,142,139,137,135,133,131,129,127,125,123,121,118,116,114,112,110')," +
			"(1, 218, 0, '20')," +
			"(1, 243, 0, '10')");

		db.update("INSERT INTO parameter_study_type VALUES " +
			"(244, 5)," +
			"(246, 5)," +
			"(203, 5)," +
			"(204, 5)," +
			"(245, 5)," +
			"(242, 5)," +
			"(218, 5)," +
			"(243, 5)");

		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		// Scenario

		db.update("INSERT INTO parameter VALUES " +
			"(355, 15, 1, 'Wireless frequency', 'decimal:50:800', 'MHz', '', '<HTML>Wireless station channel center frequency.</HTML>', true)," +
			"(356, 15, 2, 'Wireless bandwidth', 'decimal:0.1:20', 'MHz', '', '<HTML>Wireless station channel bandwidth.</HTML>', true)");

		db.update("INSERT INTO template_parameter_data VALUES " +
			"(1, 355, 0, '650')," +
			"(1, 356, 0, '5')");

		db.update("INSERT INTO parameter_study_type VALUES " +
			"(355, 3)," +
			"(356, 3)");

		// End of parameters.
		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Code to update a database from previous versions.  More than one update may be applied.  If canUpdateDb() says
	// the version number is known, then doUpdateDb() will be called to sequentially apply all updates, ending with
	// the database at the current version.  See DbInfo.

	private static void doUpdateDb(DbInfo theInfo, int version, StatusReporter status) throws SQLException {

		if (null != status) {
			status.setWaitMessage("Updating to version " + DATABASE_VERSION + ", please wait...");
		}

		boolean updateData = false, updateStudies = false, clearProperties = false;

		switch (version) {

			default:
				throw new SQLException("Unknown version number, cannot update database.");

			case 103000:
				update103002(theInfo);

			case 103002:
				update104000(theInfo);

			case 104000:
				update104001(theInfo);

			case 104001:
				update104002(theInfo);

			case 104002:
				update104003(theInfo);

			case 104003:
			case 104005:
				update104006(theInfo);
				clearProperties = true;

			case 104006:
				update104007(theInfo);

			case 104007:
				update104008(theInfo);
				updateStudies = true;

			case 104008:
			case 104009:
				update104011(theInfo);

			case 104011:
			case 104012:
				update104013(theInfo);

			case 104013:
			case 200000:
				update200001(theInfo);

			case 200001:
				update200002(theInfo);

			case 200002:
				update200003(theInfo);

			case 200003:
			case 200004:
				update200005(theInfo);

			case 200005:
				update200006(theInfo);

			case 200006:
				update200010(theInfo);

			case 200010:
				update200011(theInfo);

			case 200011:
				update200012(theInfo);

			case 200012:
				update200013(theInfo);

			case 200013:
				update200014(theInfo);

			case 200014:
				update200016(theInfo);

			case 200016:
				update200017(theInfo);

			case 200017:
				update200018(theInfo);

			case 200018:
				update200019(theInfo);

			case 200019:
				update200020(theInfo);

			case 200020:
				update200021(theInfo);

			case 200021:
				update200022(theInfo);

			case 200022:
				update200023(theInfo, status);

			case 200023:
			case 200024:
			case 201000:
				update201001(theInfo);

			case 201001:
				update201002(theInfo);

			case 201002:
			case 201003:
			case 202000:
				update202001(theInfo);

			case 202001:
				update202002(theInfo);

			case 202002:
				update202003(theInfo);

			case 202003:
			case 20200301:
				update20200302(theInfo);

			case 20200302:
				update20200400(theInfo);

			case 20200400:
				update20200401(theInfo);
				updateData = true;
		}

		// Do final updates as needed; update root data, set needs_update on all studies so engine clears caches and
		// re-derives source properties, and clear application properties to reset window positions and table layouts.

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		if (updateData) {
			doUpdateRootData(db);
		}

		if (updateStudies) {
			db.update("UPDATE study SET needs_update = 1");
		}

		if (clearProperties) {
			db.update("DELETE FROM application_property");
		}

		// Set new version number.

		db.update("UPDATE version SET version = " + DATABASE_VERSION);
	}

	// Update from 1.3 to 1.3.2

	private static void update103002(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Add population data for U.S. island territories (VI, GU, MP, AS).

// As of 2.0.17 the separate territory files are merged into the main files, data will be updated below

//		db.update("LOAD DATA LOCAL INFILE 'data/pop_usterr_2000.dat' INTO TABLE pop_us_2000 FIELDS TERMINATED BY '|' " +
//			"LINES TERMINATED BY '\\n'");
//		db.update("LOAD DATA LOCAL INFILE 'data/pop_usterr_2010.dat' INTO TABLE pop_us_2010 FIELDS TERMINATED BY '|' " +
//			"LINES TERMINATED BY '\\n'");

		// Alter parameter-related tables to create capability for parameters to have multiple values.

		db.update("ALTER TABLE parameter ADD COLUMN value_list VARCHAR(255) NOT NULL AFTER type");
		db.update("ALTER TABLE template_parameter_data ADD COLUMN value_index INT NOT NULL AFTER parameter_key");
		db.update("ALTER TABLE template_parameter_data DROP PRIMARY KEY");
		db.update("ALTER TABLE template_parameter_data ADD PRIMARY KEY (template_key, parameter_key, value_index)");

		// Update parameter data table structure in studies, but no changes to the parameter values here, single values
		// are automatically filled down to all multi-value indices when parameters are loaded.

		ArrayList<Integer> studyKeys = new ArrayList<Integer>();
		db.query("SELECT study_key FROM study");
		while (db.next()) {
			studyKeys.add(Integer.valueOf(db.getInt(1)));
		}

		for (Integer studyKey : studyKeys) {
			db.setDatabase(theInfo.dbName + "_" + studyKey);
			db.update("ALTER TABLE parameter_data ADD COLUMN value_index INT NOT NULL AFTER parameter_key");
			db.update("ALTER TABLE parameter_data DROP PRIMARY KEY");
			db.update("ALTER TABLE parameter_data ADD PRIMARY KEY (parameter_key, value_index)");
		}
	}

	// Update from 1.3.2 to 1.4

	private static void update104000(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Update country polygon table to identify actual border points, versus extra points added to close polygons.

		db.update("ALTER TABLE country_poly ADD COLUMN is_border BOOLEAN NOT NULL");
		db.update("UPDATE country_poly SET is_border = true");
		db.update("UPDATE country_poly SET is_border = false WHERE poly_seq = 2000 AND " +
			"((vertex_seq = 10991) OR (vertex_seq = 10992) OR (vertex_seq > 11429))");
		db.update("UPDATE country_poly SET is_border = false WHERE poly_seq = 3000 AND (vertex_seq > 39673)");

		// Add table listing FCC monitoring station coordinates.

		db.update("CREATE TABLE monitor_station (" +
			"name VARCHAR(255) NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL)");

		// List of land mobile protected locations.

		db.update("CREATE TABLE land_mobile (" +
			"name VARCHAR(255) NOT NULL," +
			"channel INT NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL)");

		// Rename tables and fields to generalize the concept of external station data beyond just CDBS.

		db.update("ALTER TABLE study CHANGE COLUMN cdbs_key ext_db_key INT NOT NULL");

		db.update("ALTER TABLE cdbs RENAME TO ext_db");
		db.update("ALTER TABLE ext_db CHANGE COLUMN cdbs_key ext_db_key INT NOT NULL");
		db.update("ALTER TABLE ext_db ADD COLUMN db_type INT NOT NULL AFTER ext_db_key");
		db.update("UPDATE ext_db SET db_type = " + ExtDb.DB_TYPE_CDBS);

		db.update("ALTER TABLE cdbs_key_sequence RENAME TO ext_db_key_sequence");
		db.update("ALTER TABLE ext_db_key_sequence CHANGE COLUMN cdbs_key ext_db_key INT NOT NULL");

		// Add study type value.

		db.update("ALTER TABLE study ADD COLUMN study_type INT NOT NULL AFTER share_count");
		db.update("UPDATE study SET study_type = 1");

		// Add report_preamble field to the study table, text to be pre-pended to a study engine output report.

		db.update("ALTER TABLE study ADD COLUMN report_preamble MEDIUMTEXT NOT NULL AFTER ext_db_key");

		// Changes to the template locking logic.  Add a permanent flag, copy locked to permanent, copy locked_in_study
		// to locked.  See comments in Template.

		db.update("ALTER TABLE template ADD COLUMN permanent BOOLEAN NOT NULL AFTER name");
		db.update("UPDATE template SET permanent = true WHERE locked");
		db.update("UPDATE template SET locked = true WHERE locked_in_study");

		// Add table to hold configuration settings for interference-check studies, see IxCheckSetup.

		db.update("CREATE TABLE ix_check_config (" +
			"template_key INT NOT NULL," +
			"ext_db_key INT NOT NULL," +
			"base_ext_db_key INT NOT NULL," +
			"output_flags VARCHAR(255) NOT NULL," +
			"percent_limit DOUBLE NOT NULL," +
			"percent_limit_lp DOUBLE NOT NULL)");

		// Create status table used by the web API to track interference-check studies running and completed.

		db.update("CREATE TABLE ix_check_status (" +
			"name VARCHAR(255) NOT NULL," +
			"running BOOLEAN NOT NULL," +
			"parameters VARCHAR(255) NOT NULL)");

		// Create a user record table to store persistent user-created records outside any study context.

		db.update("CREATE TABLE user_record (" +
			"user_record_id INT PRIMARY KEY," +
			"xml_data MEDIUMTEXT NOT NULL," +
			"facility_id INT NOT NULL," +
			"service_key INT NOT NULL," +
			"call_sign CHAR(12) NOT NULL," +
			"status CHAR(6) NOT NULL," +
			"channel INT NOT NULL," +
			"city CHAR(20) NOT NULL," +
			"state CHAR(2) NOT NULL)");

		db.update("CREATE TABLE user_record_id_sequence (" +
			"user_record_id INT NOT NULL)");
		db.update("INSERT INTO user_record_id_sequence VALUES (0)");

		// Support for LMS, create maps for CDBS/LMS codes to services.  The service keys are re-mapped in the source
		// tables for existing studies.

		db.update("CREATE TABLE cdbs_service (" +
			"cdbs_service_code CHAR(2) NOT NULL," +
			"service_key INT NOT NULL)");

		db.update("CREATE TABLE lms_service (" +
			"lms_service_code CHAR(6) NOT NULL," +
			"service_key INT NOT NULL)");

		// Update existing studies.

		ArrayList<Integer> studyKeys = new ArrayList<Integer>();
		ArrayList<Integer> extDbKeys = new ArrayList<Integer>();
		db.query("SELECT study_key, ext_db_key FROM study");
		while (db.next()) {
			studyKeys.add(Integer.valueOf(db.getInt(1)));
			extDbKeys.add(Integer.valueOf(db.getInt(2)));
		}

		for (int i = 0; i < studyKeys.size(); i++) {

			db.setDatabase(theInfo.dbName + "_" + studyKeys.get(i));

			// Add DRT flag to source table.

			db.update("ALTER TABLE source ADD COLUMN is_drt BOOLEAN NOT NULL AFTER service_key");

			// Add user record ID and external data key, allowing sources in the same study to come from multiple
			// external station data sets.

			db.update("ALTER TABLE source ADD COLUMN user_record_id INT NOT NULL AFTER locked");

			db.update("ALTER TABLE source ADD COLUMN ext_db_key INT NOT NULL AFTER user_record_id");
			db.update("UPDATE source SET ext_db_key = " + extDbKeys.get(i));

			// Support for generalizing external data record references, change type of antenna ID and application ID,
			// change latter to external record ID.

			db.update("ALTER TABLE source CHANGE COLUMN antenna_id antenna_id VARCHAR(255)");
			db.update("UPDATE source SET antenna_id = null WHERE antenna_id = '0'");

			db.update("ALTER TABLE source CHANGE COLUMN application_id ext_record_id VARCHAR(255)");
			db.update("UPDATE source SET ext_record_id = null WHERE ext_record_id = '0'");

			// Re-map service keys, see above.

			db.update("UPDATE source SET service_key = 1 WHERE service_key = 5");
			db.update("UPDATE source SET service_key = 5 WHERE service_key = 6");
			db.update("UPDATE source SET service_key = 6 WHERE service_key = 7");
			db.update("UPDATE source SET service_key = 7 WHERE service_key IN (8, 9)");
			db.update("UPDATE source SET service_key = 8 WHERE service_key IN (10, 11, 12, 13)");
			db.update("UPDATE source SET service_key = 9 WHERE service_key IN (14, 15, 16, 17, 18)");

			// Add permanent flags to scenario and scenario_source to support pairing, and create scenario_pair.

			db.update("ALTER TABLE scenario ADD COLUMN is_permanent BOOLEAN NOT NULL");
			db.update("ALTER TABLE scenario_source ADD COLUMN is_permanent BOOLEAN NOT NULL");

			db.update("CREATE TABLE scenario_pair (" +
				"description VARCHAR(10000) NOT NULL," +
				"scenario_key_a INT NOT NULL," +
				"source_key_a INT NOT NULL," +
				"scenario_key_b INT NOT NULL," +
				"source_key_b INT NOT NULL," +
				"scenario_key_base INT NOT NULL," +
				"source_key_base INT NOT NULL)");
		}
	}

	// Update from 1.4 to 1.4.1

	private static void update104001(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Remove the use counters from template and ext_db table, that was unnecessarily complicated, easier to just
		// check for references before deleting.  Also add a deleted flag to ext_db, the entries in that table remain
		// but are flagged once the database itself is dropped.  See ExtDb.

		db.update("ALTER TABLE template DROP COLUMN use_count");

		db.update("ALTER TABLE ext_db DROP COLUMN use_count");
		db.update("ALTER TABLE ext_db ADD COLUMN deleted BOOLEAN NOT NULL");

		// Add version number field to ext_db table, so code can continue supporting older imports even when new
		// imports have a different structure.  All existing databases are version 0.

		db.update("ALTER TABLE ext_db ADD COLUMN version INT NOT NULL AFTER db_type");

		// Add country and file number to searchable properties in the user record table.  Don't bother trying to
		// update these for existing records, it's not that important.  Also comment field.

		db.update("ALTER TABLE user_record ADD COLUMN country CHAR(2) NOT NULL");
		db.update("ALTER TABLE user_record ADD COLUMN file_number CHAR(22) NOT NULL");
		db.update("ALTER TABLE user_record ADD COLUMN comment VARCHAR(10000) NOT NULL");
	}

	// Update from 1.4.1 to 1.4.2

	private static void update104002(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Add rule summary field to the study table.

		db.update("ALTER TABLE study ADD COLUMN ix_rule_summary MEDIUMTEXT NOT NULL");

		// Add temporary NAD27 coordinate fields, main fields will be updated with NAD83 by study engine.  Eventually
		// the NAD27 fields will be removed, all UI and input/output will be NAD83, and legacy NAD27 (e.g. CDBS) will
		// be converted during record creation.

		ArrayList<Integer> studyKeys = new ArrayList<Integer>();
		db.query("SELECT study_key FROM study");
		while (db.next()) {
			studyKeys.add(Integer.valueOf(db.getInt(1)));
		}

		for (Integer theKey : studyKeys) {

			db.setDatabase(theInfo.dbName + "_" + theKey);

			db.update("ALTER TABLE source ADD COLUMN latitude_nad27 DOUBLE NOT NULL AFTER longitude");
			db.update("ALTER TABLE source ADD COLUMN longitude_nad27 DOUBLE NOT NULL AFTER latitude_nad27");
			db.update("UPDATE source SET latitude_nad27 = latitude, longitude_nad27 = longitude, needs_update = true");
		}
	}

	// Update from 1.4.2 to 1.4.3

	private static void update104003(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Add another station data set key to the IX check configuration, for a CDBS data set to use for AM station
		// searches when LMS data is used for the study; LMS does not contain AM station records.  If this key is 0
		// the study data set will be used if that is CDBS, otherwise the AM station search is not performed.

		db.update("ALTER TABLE ix_check_config ADD COLUMN am_ext_db_key INT NOT NULL AFTER ext_db_key");
	}

	// Update from 1.4.3 or 1.4.5 to 1.4.6

	private static void update104006(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Add record type to the service type and channel delta tables.

		db.update("ALTER TABLE service_type ADD COLUMN record_type INT NOT NULL AFTER service_type_key");
		db.update("ALTER TABLE channel_delta ADD COLUMN record_type INT NOT NULL AFTER channel_delta_key");

		// Remove the study-fixed flag from the parameters table.  That feature was designed to prevent inconsistent
		// results due to changing parameters that affect scenario-building.  But it was a solution to a problem that
		// didn't really exist and so an unnecessary inconvenience.  It was also becoming difficult to support due to
		// an expanding parameter set.  The presumption is that users are smart enough to understand the consequences
		// of changing parameters in an existing study.  When it is important for different users to get consistent
		// results, the template-locking features are an effective solution.

		db.update("ALTER TABLE parameter DROP COLUMN study_fixed");

		// Remove the maximum distance field from the channel delta table, similar to the study_fixed flag it was part
		// of a feature set meant to prevent inconsistent results when interference rules are edited after a scenario
		// is created, but it was more inconvenient that useful.  Now scenario-building searches just use the actual
		// current rules in a study, and of course if those are changed existing scenarios might not be valid; users
		// are expected to understand how it works.

		db.update("ALTER TABLE channel_delta DROP COLUMN maximum_distance");

		// Add scenario flag to parameters, create parameter to study type mapping table.

		db.update("ALTER TABLE parameter ADD COLUMN is_scenario_parameter BOOLEAN NOT NULL");

		db.update("CREATE TABLE parameter_study_type (" +
			"parameter_key INT NOT NULL," +
			"study_type INT NOT NULL," +
			"UNIQUE (parameter_key, study_type))");

		// Add record type to the user record table.  All existing records are TV.

		db.update("ALTER TABLE user_record ADD COLUMN record_type INT NOT NULL AFTER user_record_id");
		db.update("UPDATE user_record SET record_type = 1");

		// In study databases, add record type to the source table, also new fields for wireless and FM records.  Add
		// an actual HAAT field so that can be derived like AMSL.  Create the scenario parameter data table.

		ArrayList<Integer> studyKeys = new ArrayList<Integer>();
		db.query("SELECT study_key FROM study");
		while (db.next()) {
			studyKeys.add(Integer.valueOf(db.getInt(1)));
		}

		for (Integer theKey : studyKeys) {

			db.setDatabase(theInfo.dbName + "_" + theKey);

			db.update("ALTER TABLE source ADD COLUMN record_type INT NOT NULL AFTER source_key");
			db.update("UPDATE source SET record_type = 1");

			db.update("ALTER TABLE source ADD COLUMN station_class CHAR(3) NOT NULL AFTER is_drt");
			db.update("ALTER TABLE source ADD COLUMN sector_id CHAR(3) NOT NULL AFTER call_sign");

			db.update("ALTER TABLE source ADD COLUMN actual_overall_haat FLOAT NOT NULL AFTER overall_haat");
			db.update("UPDATE source SET actual_overall_haat = overall_haat");

			db.update(
			"CREATE TABLE scenario_parameter_data (" +
				"scenario_key INT NOT NULL," +
				"parameter_key INT NOT NULL," +
				"value_index INT NOT NULL," +
				"value VARCHAR(255) NOT NULL," +
				"PRIMARY KEY (scenario_key, parameter_key, value_index)" +
			")");
		}
	}

	// Update from 1.4.6 to 1.4.7

	private static void update104007(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Remove the new_study flag from study table, no longer serves any purpose.

		db.update("ALTER TABLE study DROP COLUMN new_study");

		// Add mechanical tilt orientation field to wireless data sets, the value is now in the CSV import format.  In
		// 1.4.6 the tilt orientation was set to the azimuth pattern orientation when needed.  Existing data sets are
		// modified in place accordingly.  That can be done because the wireless SQL table format is defined internally
		// by code; see ExtDbManager.createWirelessTableFiles() and ExtDb.openWirelessTableFiles().

		ArrayList<Integer> extDbKeys = new ArrayList<Integer>();
		db.query("SELECT ext_db_key FROM ext_db WHERE NOT deleted AND db_type = " + ExtDb.DB_TYPE_WIRELESS);
		while (db.next()) {
			extDbKeys.add(Integer.valueOf(db.getInt(1)));
		}

		for (Integer theKey : extDbKeys) {
			db.setDatabase(theInfo.dbName + "_wireless_" + theKey);
			db.update("ALTER TABLE base_station ADD COLUMN m_tilt_orientation FLOAT NOT NULL");
			db.update("UPDATE base_station SET m_tilt_orientation = orientation WHERE m_tilt <> 0.");
		}
	}

	// Update from 1.4.7 to 1.4.8

	private static void update104008(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Add IBOC flag and power fraction for FM to source table.

		ArrayList<Integer> studyKeys = new ArrayList<Integer>();
		db.query("SELECT study_key FROM study");
		while (db.next()) {
			studyKeys.add(Integer.valueOf(db.getInt(1)));
		}

		for (Integer theKey : studyKeys) {
			db.setDatabase(theInfo.dbName + "_" + theKey);
			db.update("ALTER TABLE source ADD COLUMN is_iboc BOOLEAN NOT NULL AFTER is_drt");
			db.update("ALTER TABLE source ADD COLUMN iboc_fraction FLOAT NOT NULL AFTER contour_erp");
			db.update("UPDATE source SET iboc_fraction = 0.01 WHERE record_type = 3");
		}
	}

	// Update from 1.4.8 or 1.4.9 to 1.4.11

	private static void update104011(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Change type of value columns in parameter data tables to allow longer strings.

		db.update("ALTER TABLE template_parameter_data CHANGE COLUMN value value VARCHAR(10000) NOT NULL");

		ArrayList<Integer> studyKeys = new ArrayList<Integer>();
		db.query("SELECT study_key FROM study");
		while (db.next()) {
			studyKeys.add(Integer.valueOf(db.getInt(1)));
		}

		for (Integer theKey : studyKeys) {
			db.setDatabase(theInfo.dbName + "_" + theKey);
			db.update("ALTER TABLE parameter_data CHANGE COLUMN value value VARCHAR(10000) NOT NULL");
			db.update("ALTER TABLE scenario_parameter_data CHANGE COLUMN value value VARCHAR(10000) NOT NULL");
		}
	}

	// Update from 1.4.11 or 1.4.12 to 1.4.13

	private static void update104013(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Add new fields to existing wireless station data sets, these are all optional so content may be blank.

		ArrayList<Integer> extDbKeys = new ArrayList<Integer>();
		db.query("SELECT ext_db_key FROM ext_db WHERE NOT deleted AND db_type = " + ExtDb.DB_TYPE_WIRELESS);
		while (db.next()) {
			extDbKeys.add(Integer.valueOf(db.getInt(1)));
		}

		for (Integer theKey : extDbKeys) {
			db.setDatabase(theInfo.dbName + "_wireless_" + theKey);
			db.update("ALTER TABLE base_station ADD COLUMN reference_number CHAR(22)");
			db.update("ALTER TABLE base_station ADD COLUMN city CHAR(20)");
			db.update("ALTER TABLE base_station ADD COLUMN state CHAR(2)");
			db.update("ALTER TABLE base_station ADD COLUMN country CHAR(2)");
			db.update("UPDATE base_station SET reference_number = '', city = '', state = '', country = ''");
		}
	}

	// Update from 1.4.13/2.0.0 to 2.0.1

	private static void update200001(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Add units column to parameter table.

		db.update("ALTER TABLE parameter ADD COLUMN units VARCHAR(255) NOT NULL AFTER type");
	}

	// Update from 2.0.1 to 2.0.2

	private static void update200002(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Drop the monitor station and land mobile tables, data is now in external data files for easier updating.

		db.update("DROP TABLE monitor_station");
		db.update("DROP TABLE land_mobile");
	}

	// Update from 2.0.2 to 2.0.3

	private static void update200003(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Change IX check setup table, baseline mode is now just a flag.

		db.update("ALTER TABLE ix_check_config DROP COLUMN base_ext_db_key");
		db.update("ALTER TABLE ix_check_config ADD COLUMN use_baseline BOOLEAN NOT NULL AFTER ext_db_key");
	}

	// Update from 2.0.3 or 2.0.4 to 2.0.5

	private static void update200005(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Create the output file configuration table.

		db.update("CREATE TABLE output_config (" +
			"config_key INT PRIMARY KEY," +
			"name VARCHAR(255) NOT NULL," +
			"codes VARCHAR(255) NOT NULL)");

		// Query the IX check configuration.  There can now be multiple named configurations so the current setup has
		// to be re-inserted after the new table is defined.  Note the limit percentages are now parameters, there is
		// no practical way to preserve the current settings.

		int templateKey = 0, extDbKey = 0, amExtDbKey = 0;
		boolean useBaseline = false;
		String outputCodes = "";

		db.query("SELECT template_key, ext_db_key, use_baseline, am_ext_db_key, output_flags FROM ix_check_config");
		if (db.next()) {
			templateKey = db.getInt(1);
			extDbKey = db.getInt(2);
			useBaseline = db.getBoolean(3);
			amExtDbKey = db.getInt(4);
			outputCodes = OutputConfig.updateLegacyCodes(db.getString(5));
		}

		// Drop and re-create the table.

		db.update("DROP TABLE ix_check_config");
		db.update("CREATE TABLE ix_check_config (" +
			"config_key INT PRIMARY KEY," +
			"name VARCHAR(255) NOT NULL," +
			"template_key INT NOT NULL," +
			"ext_db_key INT NOT NULL," +
			"use_baseline BOOLEAN NOT NULL," +
			"am_ext_db_key INT NOT NULL," +
			"output_config_key INT NOT NULL," +
			"output_config_codes VARCHAR(255) NOT NULL," +
			"is_default BOOLEAN NOT NULL)");

		// Insert the old configuration if needed, set the default flag.

		if (templateKey > 0) {
			db.update("INSERT INTO ix_check_config VALUES (1, 'Previous', " + templateKey + ", " + extDbKey +
				", " + useBaseline + ", " + amExtDbKey + ", 0, '" + outputCodes + "', true)");
		}
	}

	// Update from 2.0.5 to 2.0.6

	private static void update200006(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Add study mode and point set key to study table.

		db.update("ALTER TABLE study ADD COLUMN study_mode INT NOT NULL AFTER study_type");
		db.update("ALTER TABLE study ADD COLUMN point_set_key INT NOT NULL AFTER ext_db_key");
		db.update("UPDATE study SET study_mode = 1");

		// Create geography tables.

		db.update("CREATE TABLE geography (" +
			"geo_key INT NOT NULL PRIMARY KEY," +
			"geo_type INT NOT NULL," +
			"name VARCHAR(255) NOT NULL)");

		db.update("CREATE TABLE geo_key_sequence (" +
			"geo_key INT NOT NULL)");
		db.update("INSERT INTO geo_key_sequence VALUES (0)");

		db.update("CREATE TABLE geo_point_set (" +
			"geo_key INT NOT NULL," +
			"point_name VARCHAR(255) NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL," +
			"receive_height DOUBLE NOT NULL)");

		db.update("CREATE TABLE geo_circle (" +
			"geo_key INT NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL," +
			"radius DOUBLE NOT NULL)");

		db.update("CREATE TABLE geo_box (" +
			"geo_key INT NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL," +
			"width DOUBLE NOT NULL," +
			"height DOUBLE NOT NULL)");

		db.update("CREATE TABLE geo_polygon (" +
			"geo_key INT NOT NULL," +
			"vertex_key INT NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL)");
	}

	// Update from 2.0.6 to 2.0.10

	private static void update200010(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Change type of undesired percent columns in interference rule tables, can now be fractional.

		db.update("ALTER TABLE template_ix_rule CHANGE COLUMN undesired_time undesired_time FLOAT NOT NULL");

		ArrayList<Integer> studyKeys = new ArrayList<Integer>();
		db.query("SELECT study_key FROM study");
		while (db.next()) {
			studyKeys.add(Integer.valueOf(db.getInt(1)));
		}

		for (Integer theKey : studyKeys) {
			db.setDatabase(theInfo.dbName + "_" + theKey);
			db.update("ALTER TABLE ix_rule CHANGE COLUMN undesired_time undesired_time FLOAT NOT NULL");
		}
	}

	// Update from 2.0.10 to 2.0.11

	private static void update200011(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Add a date field to the ext_db table.  Scan the existing ID fields which always contain dates as strings,
		// albeit in varying formats, and parse those to set the new date field.  Also re-format the IDs to be the
		// date string followed by the primary key so all are consistent.

		db.update("ALTER TABLE ext_db ADD COLUMN db_date DATETIME NOT NULL AFTER db_type");

		ArrayList<Integer> dbKeys = new ArrayList<Integer>();
		ArrayList<String> dbIDs = new ArrayList<String>();
		ArrayList<String> dbDates = new ArrayList<String>();

		SimpleDateFormat cdbsDateFormat = new SimpleDateFormat("MM/dd/yyyy", Locale.US);
		DateFormat wirelessDateFormat = DateFormat.getDateInstance();

		SimpleDateFormat dbDateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

		Integer theKey;
		String theID, theDbDate, defDbDate = dbDateFormat.format(new java.util.Date(0));
		String[] parts;

		db.query("SELECT ext_db_key, id, db_type FROM ext_db");

		while (db.next()) {

			theKey = Integer.valueOf(db.getInt(1));
			theID = db.getString(2);
			theDbDate = defDbDate;

			switch (db.getInt(3)) {

				case ExtDb.DB_TYPE_CDBS:
				case ExtDb.DB_TYPE_CDBS_FM: {
					parts = theID.split("\\s+", 2);
					if (2 == parts.length) {
						try {
							theDbDate = dbDateFormat.format(cdbsDateFormat.parse(parts[0]));
							theID = parts[0] + " (" + theKey + ")";
						} catch (ParseException pe) {
						}
					}
					break;
				}

				case ExtDb.DB_TYPE_LMS: {
					theDbDate = theID;
					theID = theID + " (" + theKey + ")";
					break;
				}

				case ExtDb.DB_TYPE_WIRELESS: {
					parts = theID.split("\\s+", 3);
					if (3 == parts.length) {
						try {
							theDbDate = dbDateFormat.format(wirelessDateFormat.parse(parts[2]));
							theID = parts[2] + " (" + theKey + ")";
						} catch (ParseException pe) {
						}
					}
					break;
				}
			}

			dbKeys.add(theKey);
			dbIDs.add(theID);
			dbDates.add(theDbDate);
		}

		for (int i = 0; i < dbKeys.size(); i++) {
			db.update("UPDATE ext_db SET id = '" + dbIDs.get(i) + "', db_date = '" + dbDates.get(i) +
				"' WHERE ext_db_key = " + dbKeys.get(i));
		}

		// Interference-check study configuration logic is changed, it no longer uses explicit saved configurations.
		// Drop the config table, but first port over default settings for template and output config, those are now
		// set by assigning a fixed name to the template or config.

		db.query("SELECT template_key, output_config_codes FROM ix_check_config WHERE is_default");

		if (db.next()) {

			int theTempKey = db.getInt(1);
			String theOutCodes = db.getString(2);

			if (1 != theTempKey) {
				db.query("SELECT template_key FROM template WHERE UPPER(name) = '" +
					db.clean(StudyBuildIxCheck.DEFAULT_CONFIG_NAME.toUpperCase()) + "'");
				if (!db.next()) {
					db.update("UPDATE template SET name = '" + db.clean(StudyBuildIxCheck.DEFAULT_CONFIG_NAME) +
						"' WHERE template_key = " + String.valueOf(theTempKey));
				}
			}

			db.query("SELECT config_key FROM output_config WHERE UPPER(name) = '" +
				db.clean(StudyBuildIxCheck.DEFAULT_CONFIG_NAME.toUpperCase()) + "'");
			if (!db.next()) {
				int theConfigKey = 0;
				db.query("SELECT MAX(config_key) FROM output_config");
				if (db.next()) {
					theConfigKey = db.getInt(1);
				}
				theConfigKey++;
				db.update("INSERT INTO output_config VALUES (" + String.valueOf(theConfigKey) + ",'" +
					db.clean(StudyBuildIxCheck.DEFAULT_CONFIG_NAME) + "','" + theOutCodes + "')");
			}
		}

		db.update("DROP TABLE ix_check_config");

		// Change the interference-check status table and create a sequence for generating unique study names.  Do
		// not try to preserve the existing status, it is not that important.

		db.update("DROP TABLE ix_check_status");
		db.update("CREATE TABLE ix_check_status (" +
			"study_name VARCHAR(255) NOT NULL," +
			"study_id VARCHAR(10000) NOT NULL," +
			"run_date DATETIME NOT NULL)");

		db.update("CREATE TABLE ix_check_name_sequence (" +
			"name_key INT NOT NULL)");
		db.update("INSERT INTO ix_check_name_sequence VALUES (0)");

		// Saved output configs no longer use a key, the name is the identifier.

		db.update("ALTER TABLE output_config DROP COLUMN config_key");
	}

	// Update from 2.0.11 to 2.0.12

	private static void update200012(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Structure of parameters related to TV6<->FM mode changed, tables now include FM channel 200, delete values
		// for the altered parameters from existing studies causing them to revert to new defaults.  Also add a name
		// column to the scenario pair table, set names for existing records based on the scenario keys.

		ArrayList<Integer> studyKeys = new ArrayList<Integer>();
		db.query("SELECT study_key FROM study");
		while (db.next()) {
			studyKeys.add(Integer.valueOf(db.getInt(1)));
		}

		for (Integer theKey : studyKeys) {

			db.setDatabase(theInfo.dbName + "_" + theKey);

			db.update("DELETE FROM parameter_data WHERE parameter_key IN (242,244,246)");

			db.update("ALTER TABLE scenario_pair ADD COLUMN name VARCHAR(255) NOT NULL FIRST");
			db.update("UPDATE scenario_pair SET name = CONCAT('Pair_#', scenario_key_a, '_', scenario_key_b)");
		}
	}

	// Update from 2.0.12 to 2.0.13

	private static void update200013(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Add geography in-use table, update from current studies.

		db.update("CREATE TABLE study_geography (" +
			"study_key INT NOT NULL," +
			"geo_key INT NOT NULL," +
			"UNIQUE (study_key, geo_key))");

		db.update("INSERT INTO study_geography SELECT study_key, point_set_key FROM study WHERE point_set_key > 0");

		// Add fields for receive antenna and orientation to point set table.

		db.update("ALTER TABLE geo_point_set ADD COLUMN antenna_key INT NOT NULL");
		db.update("ALTER TABLE geo_point_set ADD COLUMN antenna_orientation DOUBLE NOT NULL");
		db.update("UPDATE geo_point_set SET antenna_orientation = -1");

		// Tables to hold custom receive antenna data.

		db.update("CREATE TABLE receive_antenna_index (" +
			"antenna_key INT NOT NULL," +
			"name VARCHAR(255))");

		db.update("CREATE TABLE antenna_key_sequence (" +
			"antenna_key INT NOT NULL)");
		db.update("INSERT INTO antenna_key_sequence VALUES (0)");

		db.update("CREATE TABLE receive_pattern (" +
			"antenna_key INT NOT NULL," +
			"frequency DOUBLE NOT NULL," +
			"azimuth DOUBLE NOT NULL," +
			"relative_field DOUBLE NOT NULL)");

		db.update("CREATE TABLE geography_receive_antenna (" +
			"geo_key INT NOT NULL," +
			"antenna_key INT NOT NULL," +
			"UNIQUE (geo_key, antenna_key))");

		// Edit lock point for geography and receive antenna editors.

		db.update("CREATE TABLE edit_lock (locked BOOLEAN NOT NULL)");
		db.update("INSERT INTO edit_lock VALUES (false)");
	}

	// Update from 2.0.13 to 2.0.14

	private static void update200014(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Add receive antenna gain column to index.

		db.update("ALTER TABLE receive_antenna_index ADD COLUMN gain DOUBLE NOT NULL");

		// Restructure geography tables, simple circle and box geographies now stored entirely in the geography table,
		// secondary tables only needed for multi-element geographies, move values as needed.  Also add modification
		// count to geography table to detect invalid service area caches.  Add sectors geography.

		db.update("ALTER TABLE geography ADD COLUMN latitude DOUBLE NOT NULL");
		db.update("ALTER TABLE geography ADD COLUMN longitude DOUBLE NOT NULL");
		db.update("ALTER TABLE geography ADD COLUMN radius DOUBLE NOT NULL");
		db.update("ALTER TABLE geography ADD COLUMN width DOUBLE NOT NULL");
		db.update("ALTER TABLE geography ADD COLUMN height DOUBLE NOT NULL");
		db.update("ALTER TABLE geography ADD COLUMN mod_count INT NOT NULL");

		db.update("UPDATE geography JOIN geo_circle USING (geo_key) SET " +
			"geography.latitude = geo_circle.latitude," +
			"geography.longitude = geo_circle.longitude," +
			"geography.radius = geo_circle.radius");
		db.update("DROP TABLE geo_circle");

		db.update("UPDATE geography JOIN geo_box USING (geo_key) SET " +
			"geography.latitude = geo_box.latitude," +
			"geography.longitude = geo_box.longitude," +
			"geography.width = geo_box.width," +
			"geography.height = geo_box.height");
		db.update("DROP TABLE geo_box");

		db.update("UPDATE geography JOIN geo_polygon USING (geo_key) SET " +
			"geography.latitude = geo_polygon.latitude," +
			"geography.longitude = geo_polygon.longitude " +
			"WHERE geo_polygon.vertex_key < 0");
		db.update("DELETE FROM geo_polygon WHERE vertex_key < 0");

		db.update("CREATE TABLE geo_sectors (" +
			"geo_key INT NOT NULL," +
			"azimuth DOUBLE NOT NULL," +
			"radius DOUBLE NOT NULL)");

		// Add service area mode and argument keys to source tables.

		ArrayList<Integer> studyKeys = new ArrayList<Integer>();
		db.query("SELECT study_key FROM study");
		while (db.next()) {
			studyKeys.add(Integer.valueOf(db.getInt(1)));
		}

		for (Integer theKey : studyKeys) {

			db.setDatabase(theInfo.dbName + "_" + theKey);

			db.update("ALTER TABLE source ADD COLUMN service_area_mode INT NOT NULL");
			db.update("ALTER TABLE source ADD COLUMN service_area_arg DOUBLE NOT NULL");
			db.update("ALTER TABLE source ADD COLUMN service_area_cl DOUBLE NOT NULL");
			db.update("ALTER TABLE source ADD COLUMN service_area_key INT NOT NULL");
			db.update("UPDATE source SET service_area_cl = -999");
		}
	}

	// Update from 2.0.14 to 2.0.16

	private static void update200016(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Add field to source table for DTS self-interference time delay.

		ArrayList<Integer> studyKeys = new ArrayList<Integer>();
		db.query("SELECT study_key FROM study");
		while (db.next()) {
			studyKeys.add(Integer.valueOf(db.getInt(1)));
		}

		for (Integer theKey : studyKeys) {

			db.setDatabase(theInfo.dbName + "_" + theKey);

			db.update("ALTER TABLE source ADD COLUMN dts_time_delay DOUBLE NOT NULL");
		}
	}

	// Update from 2.0.16 to 2.0.17

	private static void update200017(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Update Census data to add point ID and households columns, also flag longitude over-range duplicates.

// As of 2.0.23 all pop data files have changed, data will be updated below.

//		db.update("DROP TABLE pop_us_2000");
//		db.update("CREATE TABLE pop_us_2000 (" +
//			"id VARCHAR(255) NOT NULL," +
//			"duplicate BOOLEAN NOT NULL," +
//			"lat_index INT NOT NULL," +
//			"lon_index INT NOT NULL," +
//			"latitude DOUBLE NOT NULL," +
//			"longitude DOUBLE NOT NULL," +
//			"population INT NOT NULL," +
//			"households INT NOT NULL," +
//			"INDEX (lat_index)," +
//			"INDEX (lon_index))");
//		db.update("LOAD DATA LOCAL INFILE 'data/pop_us_2000.dat' INTO TABLE pop_us_2000 FIELDS TERMINATED BY '|' " +
//			"LINES TERMINATED BY '\\n'");

//		db.update("DROP TABLE pop_us_2010");
//		db.update("CREATE TABLE pop_us_2010 (" +
//			"id VARCHAR(255) NOT NULL," +
//			"duplicate BOOLEAN NOT NULL," +
//			"lat_index INT NOT NULL," +
//			"lon_index INT NOT NULL," +
//			"latitude DOUBLE NOT NULL," +
//			"longitude DOUBLE NOT NULL," +
//			"population INT NOT NULL," +
//			"households INT NOT NULL," +
//			"INDEX (lat_index)," +
//			"INDEX (lon_index))");
//		db.update("LOAD DATA LOCAL INFILE 'data/pop_us_2010.dat' INTO TABLE pop_us_2010 FIELDS TERMINATED BY '|' " +
//			"LINES TERMINATED BY '\\n'");

//		db.update("DROP TABLE pop_ca_2006");
//		db.update("CREATE TABLE pop_ca_2006 (" +
//			"id VARCHAR(255) NOT NULL," +
//			"duplicate BOOLEAN NOT NULL," +
//			"lat_index INT NOT NULL," +
//			"lon_index INT NOT NULL," +
//			"latitude DOUBLE NOT NULL," +
//			"longitude DOUBLE NOT NULL," +
//			"population INT NOT NULL," +
//			"households INT NOT NULL," +
//			"INDEX (lat_index)," +
//			"INDEX (lon_index))");
//		db.update("LOAD DATA LOCAL INFILE 'data/pop_ca_2006.dat' INTO TABLE pop_ca_2006 FIELDS TERMINATED BY '|' " +
//			"LINES TERMINATED BY '\\n'");

//		db.update("DROP TABLE pop_ca_2011");
//		db.update("CREATE TABLE pop_ca_2011 (" +
//			"id VARCHAR(255) NOT NULL," +
//			"duplicate BOOLEAN NOT NULL," +
//			"lat_index INT NOT NULL," +
//			"lon_index INT NOT NULL," +
//			"latitude DOUBLE NOT NULL," +
//			"longitude DOUBLE NOT NULL," +
//			"population INT NOT NULL," +
//			"households INT NOT NULL," +
//			"INDEX (lat_index)," +
//			"INDEX (lon_index))");
//		db.update("LOAD DATA LOCAL INFILE 'data/pop_ca_2011.dat' INTO TABLE pop_ca_2011 FIELDS TERMINATED BY '|' " +
//			"LINES TERMINATED BY '\\n'");

		// Mexican data not yet updated.  Next time.

// As of 2.0.23 data files and table for Mexico have changed, will be updated below.

//		db.update("ALTER TABLE pop_mx_2010 ADD COLUMN id VARCHAR(255) NOT NULL FIRST");
//		db.update("ALTER TABLE pop_mx_2010 ADD COLUMN duplicate BOOLEAN NOT NULL AFTER id");
//		db.update("ALTER TABLE pop_mx_2010 ADD COLUMN households INT NOT NULL AFTER population");

		// Add type to the output configuration table.

		db.update("ALTER TABLE output_config ADD COLUMN type INT NOT NULL FIRST");
		db.update("UPDATE output_config SET type = 1");

		// Add output configs to study table.

		db.update("ALTER TABLE study ADD COLUMN output_config_map_codes VARCHAR(255) NOT NULL AFTER point_set_key");
		db.update("ALTER TABLE study ADD COLUMN output_config_map_name VARCHAR(255) NOT NULL AFTER point_set_key");
		db.update("ALTER TABLE study ADD COLUMN output_config_file_codes VARCHAR(255) NOT NULL AFTER point_set_key");
		db.update("ALTER TABLE study ADD COLUMN output_config_file_name VARCHAR(255) NOT NULL AFTER point_set_key");
	}

	// Update from 2.0.17 to 2.0.18

	private static void update200018(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Add propagation model key to study table.  Set all to Longley-Rice.

		db.update("ALTER TABLE study ADD COLUMN propagation_model INT NOT NULL AFTER point_set_key");
		db.update("UPDATE study SET propagation_model = 1");

		// Create image color map tables and maps.

		db.update("CREATE TABLE color_map (" +
			"color_map_key INT PRIMARY KEY," +
			"name VARCHAR(255) NOT NULL," +
			"bg_color_h FLOAT NOT NULL," +
			"bg_color_s FLOAT NOT NULL," +
			"bg_color_v FLOAT NOT NULL)");

		db.update("CREATE TABLE color_map_data (" +
			"color_map_key INT NOT NULL," +
			"value DOUBLE NOT NULL," +
			"color_h FLOAT NOT NULL," +
			"color_s FLOAT NOT NULL," +
			"color_v FLOAT NOT NULL," +
			"gradient BOOLEAN NOT NULL)");

		db.update("INSERT INTO color_map VALUES " +
			"(1, 'Desired signal margin', 0.6, 1, 0.7)," +
			"(2, 'Worst-case D/U margin', 0.6, 1, 0.7)," +
			"(3, 'Wireless D/U margin', 0.6, 1, 0.7)," +
			"(4, 'Self-interference D/U margin', 0.6, 1, 0.7)," +
			"(5, 'Smallest margin', 0.6, 1, 0.7)");

		db.update("INSERT INTO color_map_data VALUES " +
			"(1, 0, 0, 1, 0.7, 0)," +
			"(1, 10, 0.1, 1, 0.7, 0)," +
			"(1, 20, 0.2, 1, 0.7, 0)," +
			"(1, 30, 0.3, 1, 0.7, 0)," +
			"(1, 40, 0.4, 1, 0.7, 0)," +
			"(2, -6, 0, 1, 0.7, 0)," +
			"(2, -3, 0.1, 1, 0.7, 0)," +
			"(2, 0, 0.2, 1, 0.7, 0)," +
			"(2, 3, 0.3, 1, 0.7, 0)," +
			"(2, 6, 0.4, 1, 0.7, 0)," +
			"(3, -6, 0, 1, 0.7, 0)," +
			"(3, -3, 0.1, 1, 0.7, 0)," +
			"(3, 0, 0.2, 1, 0.7, 0)," +
			"(3, 3, 0.3, 1, 0.7, 0)," +
			"(3, 6, 0.4, 1, 0.7, 0)," +
			"(4, -6, 0, 1, 0.7, 0)," +
			"(4, -3, 0.1, 1, 0.7, 0)," +
			"(4, 0, 0.2, 1, 0.7, 0)," +
			"(4, 3, 0.3, 1, 0.7, 0)," +
			"(4, 6, 0.4, 1, 0.7, 0)," +
			"(5, -6, 0, 1, 0.7, 0)," +
			"(5, -3, 0.1, 1, 0.7, 0)," +
			"(5, 0, 0.2, 1, 0.7, 0)," +
			"(5, 3, 0.3, 1, 0.7, 0)," +
			"(5, 6, 0.4, 1, 0.7, 0)");

		// Add the signal type table.  Only one type is supported currently, and the UI is only enabled if more rows
		// are inserted into this table manually after installation.

		db.update("CREATE TABLE signal_type (" +
			"signal_type_key INT NOT NULL PRIMARY KEY," +
			"name VARCHAR(255) NOT NULL," +
			"is_default BOOLEAN NOT NULL)");

		db.update("INSERT INTO signal_type VALUES" +
			"(1, 'ATSC', true)");

		// Add signal type to template interference rules, for digital TV service types set to ATSC.

		db.update("ALTER TABLE template_ix_rule ADD COLUMN signal_type_key INT NOT NULL AFTER service_type_key");
		db.update("ALTER TABLE template_ix_rule ADD COLUMN undesired_signal_type_key INT NOT NULL " +
			"AFTER undesired_service_type_key");
		db.update("UPDATE template_ix_rule SET signal_type_key = 1 WHERE service_type_key IN (1, 3, 5)");
		db.update("UPDATE template_ix_rule SET undesired_signal_type_key = 1 " +
			"WHERE undesired_service_type_key IN (1, 3, 5)");

		// Add signal type to interference rule and source tables in study, also DTS sectors list to source table.

		ArrayList<Integer> studyKeys = new ArrayList<Integer>();
		db.query("SELECT study_key FROM study");
		while (db.next()) {
			studyKeys.add(Integer.valueOf(db.getInt(1)));
		}

		for (Integer theKey : studyKeys) {

			db.setDatabase(theInfo.dbName + "_" + theKey);

			db.update("ALTER TABLE ix_rule ADD COLUMN signal_type_key INT NOT NULL AFTER service_type_key");
			db.update("ALTER TABLE ix_rule ADD COLUMN undesired_signal_type_key INT NOT NULL " +
				"AFTER undesired_service_type_key");
			db.update("UPDATE ix_rule SET signal_type_key = 1 WHERE service_type_key IN (1, 3, 5)");
			db.update("UPDATE ix_rule SET undesired_signal_type_key = 1 " +
				"WHERE undesired_service_type_key IN (1, 3, 5)");

			db.update("ALTER TABLE source ADD COLUMN signal_type_key INT NOT NULL AFTER file_number");
			db.update("UPDATE source SET signal_type_key = 1 WHERE service_key IN (1, 2, 3, 4, 8)");

			db.update("ALTER TABLE source ADD COLUMN dts_sectors VARCHAR(5000) NOT NULL AFTER dts_maximum_distance");
		}
	}

	// Update from 2.0.18 to 2.0.19

	private static void update200019(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Add lock column to ext_db table.

		db.update("ALTER TABLE ext_db ADD COLUMN locked BOOLEAN NOT NULL");
	}

	// Update from 2.0.19 to 2.0.20

	private static void update200020(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Rebuild color tables.

		db.update("DROP TABLE color_map");
		db.update("DROP TABLE color_map_data");

		db.update("CREATE TABLE color_map (" +
			"color_map_key INT PRIMARY KEY," +
			"bg_color_r INT NOT NULL," +
			"bg_color_g INT NOT NULL," +
			"bg_color_b INT NOT NULL)");

		db.update("CREATE TABLE color_map_data (" +
			"color_map_key INT NOT NULL," +
			"level DOUBLE NOT NULL," +
			"color_r INT NOT NULL," +
			"color_g INT NOT NULL," +
			"color_b INT NOT NULL)");

		db.update("INSERT INTO color_map VALUES " +
			"(1, 0, 80, 180)," +
			"(2, 0, 80, 180)," +
			"(3, 0, 80, 180)," +
			"(4, 0, 80, 180)," +
			"(5, 0, 80, 180)");

		db.update("INSERT INTO color_map_data VALUES " +
			"(1, 0, 180, 0, 0)," +
			"(1, 10, 180, 100, 0)," +
			"(1, 20, 140, 180, 0)," +
			"(1, 30, 40, 180, 0)," +
			"(1, 40, 0, 180, 70)," +
			"(2, -6, 180, 0, 0)," +
			"(2, -3, 180, 100, 0)," +
			"(2, 0, 140, 180, 0)," +
			"(2, 3, 40, 180, 0)," +
			"(2, 6, 0, 180, 70)," +
			"(3, -6, 180, 0, 0)," +
			"(3, -3, 180, 100, 0)," +
			"(3, 0, 140, 180, 0)," +
			"(3, 3, 40, 180, 0)," +
			"(3, 6, 0, 180, 70)," +
			"(4, -6, 180, 0, 0)," +
			"(4, -3, 180, 100, 0)," +
			"(4, 0, 140, 180, 0)," +
			"(4, 3, 40, 180, 0)," +
			"(4, 6, 0, 180, 68)," +
			"(5, -6, 180, 0, 0)," +
			"(5, -3, 180, 100, 0)," +
			"(5, 0, 140, 180, 0)," +
			"(5, 3, 40, 180, 0)," +
			"(5, 6, 0, 180, 70)");
	}

	// Update from 2.0.20 to 2.0.21

	private static class ConvertPoint extends GeoPoint {
		int sourceKey;
	}

	private static void update200021(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Add study area mode and key to study table.

		db.update("ALTER TABLE study ADD COLUMN study_area_mode INT NOT NULL AFTER propagation_model");
		db.update("UPDATE study SET study_area_mode = " + Study.STUDY_AREA_SERVICE);
		db.update("ALTER TABLE study ADD COLUMN study_area_geo_key INT NOT NULL AFTER study_area_mode");

		// Remove NAD27 coordinate columns from source table.  Coordinates are now converted to NAD83 when external
		// records are retrieved so everything is NAD83 end-to-end.  Existing source records that were never converted
		// by the study engine (newly-created or edited studies that haven't been run) have to be converted here.

		ArrayList<Integer> studyKeys = new ArrayList<Integer>();
		db.query("SELECT study_key FROM study");
		while (db.next()) {
			studyKeys.add(Integer.valueOf(db.getInt(1)));
		}

		ArrayList<ConvertPoint> points = new ArrayList<ConvertPoint>();
		ConvertPoint point;

		for (Integer theKey : studyKeys) {

			db.setDatabase(theInfo.dbName + "_" + theKey);

			db.query("SELECT source_key, latitude_nad27, longitude_nad27 FROM source WHERE needs_update");
			while (db.next()) {
				point = new ConvertPoint();
				point.sourceKey = db.getInt(1);
				point.latitude = db.getDouble(2);
				point.longitude = db.getDouble(3);
				points.add(point);
			}

			if (!points.isEmpty()) {
				for (ConvertPoint thePoint : points) {
					thePoint.convertFromNAD27();
					db.update("UPDATE source SET latitude = " + thePoint.latitude + ", longitude = " +
						thePoint.longitude + " WHERE source_key = " + thePoint.sourceKey);
				}
				points.clear();
			}

			db.update("ALTER TABLE source DROP COLUMN latitude_nad27");
			db.update("ALTER TABLE source DROP COLUMN longitude_nad27");
		}
	}

	// Update from 2.0.21 to 2.0.22

	private static void update200022(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Add study and source key columns to geography table to support scoped visibility in the editor.

		db.update("ALTER TABLE geography ADD COLUMN study_key INT NOT NULL");
		db.update("ALTER TABLE geography ADD COLUMN source_key INT NOT NULL");
	}

	// Update from 2.0.22 to 2.0.23

	private static void update200023(DbInfo theInfo, StatusReporter status) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Add import flag to version table.

		db.update("ALTER TABLE version ADD COLUMN do_import BOOLEAN NOT NULL");

		// Add mod count to study table.

		db.update("ALTER TABLE study ADD COLUMN mod_count INT NOT NULL AFTER needs_update");

		// Table for saved scenario-building searches.

		db.update("CREATE TABLE ext_db_search (" +
			"study_type INT NOT NULL," +
			"name VARCHAR(255) NOT NULL," +
			"is_desired BOOLEAN NOT NULL," +
			"disable_mx BOOLEAN NOT NULL," +
			"prefer_operating BOOLEAN NOT NULL," +
			"desired_only BOOLEAN NOT NULL," +
			"service_keys VARCHAR(255) NOT NULL," +
			"country_keys VARCHAR(255) NOT NULL," +
			"status_types VARCHAR(255) NOT NULL," +
			"radius DOUBLE NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL," +
			"additional_sql VARCHAR(20000) NOT NULL," +
			"auto_run BOOLEAN NOT NULL)");

		// Update pop data, block IDs screwed up for U.S. and Canada, finally added households to Mexico.

		if (null != status) {
			status.setWaitMessage("Loading U.S. 2000 Census data, please wait...");
		}
		db.update("DROP TABLE pop_us_2000");
		db.update("CREATE TABLE pop_us_2000 (" +
			"id VARCHAR(255) NOT NULL," +
			"duplicate BOOLEAN NOT NULL," +
			"lat_index INT NOT NULL," +
			"lon_index INT NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL," +
			"population INT NOT NULL," +
			"households INT NOT NULL," +
			"INDEX (lat_index)," +
			"INDEX (lon_index))");
			
	
		db.update(GetMySQLFilePath("pop_us_2000"));		
			

		if (null != status) {
			status.setWaitMessage("Loading U.S. 2010 Census data, please wait...");
		}
		db.update("DROP TABLE pop_us_2010");
		db.update("CREATE TABLE pop_us_2010 (" +
			"id VARCHAR(255) NOT NULL," +
			"duplicate BOOLEAN NOT NULL," +
			"lat_index INT NOT NULL," +
			"lon_index INT NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL," +
			"population INT NOT NULL," +
			"households INT NOT NULL," +
			"INDEX (lat_index)," +
			"INDEX (lon_index))");
		db.update(GetMySQLFilePath("pop_us_2010"));

		if (null != status) {
			status.setWaitMessage("Loading Canadian Census data, please wait...");
		}
		db.update("DROP TABLE pop_ca_2006");
		db.update("CREATE TABLE pop_ca_2006 (" +
			"id VARCHAR(255) NOT NULL," +
			"duplicate BOOLEAN NOT NULL," +
			"lat_index INT NOT NULL," +
			"lon_index INT NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL," +
			"population INT NOT NULL," +
			"households INT NOT NULL," +
			"INDEX (lat_index)," +
			"INDEX (lon_index))");
		db.update(GetMySQLFilePath("pop_ca_2006"));

		db.update("DROP TABLE pop_ca_2011");
		db.update("CREATE TABLE pop_ca_2011 (" +
			"id VARCHAR(255) NOT NULL," +
			"duplicate BOOLEAN NOT NULL," +
			"lat_index INT NOT NULL," +
			"lon_index INT NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL," +
			"population INT NOT NULL," +
			"households INT NOT NULL," +
			"INDEX (lat_index)," +
			"INDEX (lon_index))");
		db.update(GetMySQLFilePath("pop_ca_2011"));

		if (null != status) {
			status.setWaitMessage("Loading Mexican Census data, please wait...");
		}
		db.update("DROP TABLE pop_mx_2010");
		db.update("CREATE TABLE pop_mx_2010 (" +
			"id VARCHAR(255) NOT NULL," +
			"duplicate BOOLEAN NOT NULL," +
			"lat_index INT NOT NULL," +
			"lon_index INT NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL," +
			"population INT NOT NULL," +
			"households INT NOT NULL," +
			"INDEX (lat_index)," +
			"INDEX (lon_index))");
		db.update(GetMySQLFilePath("pop_mx_2010"));
	}

	// Update from 2.0.23, 2.0.24, or 2.1.0 to 2.1.1

	private static void update201001(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Add 2016 Canada population data.

		db.update("CREATE TABLE pop_ca_2016 (" +
			"id VARCHAR(255) NOT NULL," +
			"duplicate BOOLEAN NOT NULL," +
			"lat_index INT NOT NULL," +
			"lon_index INT NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL," +
			"population INT NOT NULL," +
			"households INT NOT NULL," +
			"INDEX (lat_index)," +
			"INDEX (lon_index))");

		db.update(GetMySQLFilePath("pop_ca_2016"));
	}

	// Update from 2.1.1 to 2.1.2

	private static void update201002(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Reset antenna identifying information on replication records.

		ArrayList<Integer> studyKeys = new ArrayList<Integer>();
		db.query("SELECT study_key FROM study");
		while (db.next()) {
			studyKeys.add(Integer.valueOf(db.getInt(1)));
		}

		for (Integer theKey : studyKeys) {

			db.setDatabase(theInfo.dbName + "_" + theKey);

			db.update("UPDATE source SET antenna_id = '', horizontal_pattern_name = " +
				"(CASE WHEN has_horizontal_pattern THEN '(replication)' ELSE '' END) WHERE original_source_key <> 0");
		}
	}

	// Update from 2.1.2, 2.1.3, 2.2.0 to 2.2.1

	private static void update202001(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Fixing possible mis-typing in IX check status index; discrepancy in update vs. installation code.

		db.update("ALTER TABLE ix_check_status CHANGE COLUMN run_date run_date DATETIME NOT NULL");
	}

	// Update from 2.2.1 to 2.2.2

	private static void update202002(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Add download flag to ext_db table.

		db.update("ALTER TABLE ext_db ADD COLUMN is_download BOOLEAN NOT NULL");

		// Remove import flag from version table, create XML auto-import index.

		db.update("ALTER TABLE version DROP COLUMN do_import");
		db.update("CREATE TABLE xml_import (" +
			"file_name VARCHAR(255) NOT NULL, " +
			"mod_time BIGINT NOT NULL, " +
			"length BIGINT NOT NULL)");
	}

	// Update from 2.2.2 to 2.2.3

	private static void update202003(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Change length of file number field, now being used as more of a general comment field.

		db.update("ALTER TABLE user_record CHANGE COLUMN file_number file_number VARCHAR(255) NOT NULL");

		ArrayList<Integer> studyKeys = new ArrayList<Integer>();
		db.query("SELECT study_key FROM study");
		while (db.next()) {
			studyKeys.add(Integer.valueOf(db.getInt(1)));
		}

		for (Integer theKey : studyKeys) {

			db.setDatabase(theInfo.dbName + "_" + theKey);

			db.update("ALTER TABLE source CHANGE COLUMN file_number file_number VARCHAR(255) NOT NULL");
		}
	}

	// Patch 2.2.3.2.

	private static void update20200302(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// And a new case to the map image output, and create defaults tables.

		db.update("INSERT INTO color_map VALUES " +
			"(6, 160, 0, 0)");

		db.update("INSERT INTO color_map_data VALUES " +
			"(6, -998, 0, 80, 180)," +
			"(6, 0, 220, 100, 100)," +
			"(6, 10, 220, 120, 0)," +
			"(6, 20, 140, 180, 0)," +
			"(6, 30, 40, 180, 0)," +
			"(6, 40, 0, 180, 70)");

		db.update("CREATE TABLE color_map_default (" +
			"color_map_key INT PRIMARY KEY," +
			"bg_color_r INT NOT NULL," +
			"bg_color_g INT NOT NULL," +
			"bg_color_b INT NOT NULL)");

		db.update("CREATE TABLE color_map_data_default (" +
			"color_map_key INT NOT NULL," +
			"level DOUBLE NOT NULL," +
			"color_r INT NOT NULL," +
			"color_g INT NOT NULL," +
			"color_b INT NOT NULL)");

		db.update("INSERT INTO color_map_default VALUES " +
			"(1, 0, 80, 180)," +
			"(2, 0, 80, 180)," +
			"(3, 0, 80, 180)," +
			"(4, 0, 80, 180)," +
			"(5, 0, 80, 180)," +
			"(6, 160, 0, 0)");

		db.update("INSERT INTO color_map_data_default VALUES " +
			"(1, 0, 180, 0, 0)," +
			"(1, 10, 180, 100, 0)," +
			"(1, 20, 140, 180, 0)," +
			"(1, 30, 40, 180, 0)," +
			"(1, 40, 0, 180, 70)," +
			"(2, -6, 180, 0, 0)," +
			"(2, -3, 180, 100, 0)," +
			"(2, 0, 140, 180, 0)," +
			"(2, 3, 40, 180, 0)," +
			"(2, 6, 0, 180, 70)," +
			"(3, -6, 180, 0, 0)," +
			"(3, -3, 180, 100, 0)," +
			"(3, 0, 140, 180, 0)," +
			"(3, 3, 40, 180, 0)," +
			"(3, 6, 0, 180, 70)," +
			"(4, -6, 180, 0, 0)," +
			"(4, -3, 180, 100, 0)," +
			"(4, 0, 140, 180, 0)," +
			"(4, 3, 40, 180, 0)," +
			"(4, 6, 0, 180, 68)," +
			"(5, -6, 180, 0, 0)," +
			"(5, -3, 180, 100, 0)," +
			"(5, 0, 140, 180, 0)," +
			"(5, 3, 40, 180, 0)," +
			"(5, 6, 0, 180, 70)," +
			"(6, -998, 0, 80, 180)," +
			"(6, 0, 220, 100, 100)," +
			"(6, 10, 220, 120, 0)," +
			"(6, 20, 140, 180, 0)," +
			"(6, 30, 40, 180, 0)," +
			"(6, 40, 0, 180, 70)");
	}

	// Update from 2.2.3.2 to 2.2.4.0

	private static void update20200400(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Add scenario type and parent scenario key to scenario table, and add the extended properties field to the
		// source table.  Most scenarios in most studies just get the default type and no properties.  TVIX studies
		// get special handling.  The first scenario is the proposal type, all others are interference type.  All of
		// the interference scenarios are made children of the proposal scenario, and have the permanent flag cleared.
		// The proposal source also gets identified with an attribute (also the original if it is a replication).
		// However that has a small chance of being wrong because it assumes the proposal is the one with the smaller
		// source key, which may not be true if the scenario has been edited.  But editing was only possible in the
		// previous release of 2.2.4 which was only used for testing and never distributed.  The users who have that
		// version will be warned that existing TVIX studies could  be broken by this update.  In any TVIX study
		// created by version 2.2.3 or earlier, this will always work correctly.

		ArrayList<Integer> studyKeys = new ArrayList<Integer>();
		ArrayList<Boolean> isTVIXFlags = new ArrayList<Boolean>();
		db.query("SELECT study_key, study_type FROM study");
		while (db.next()) {
			studyKeys.add(Integer.valueOf(db.getInt(1)));
			isTVIXFlags.add(Boolean.valueOf(db.getInt(2) == Study.STUDY_TYPE_TV_IX));
		}

		Integer theKey;
		boolean isTVIX;
		int srcKey;
		for (int rowIndex = 0; rowIndex < studyKeys.size(); rowIndex++) {

			theKey = studyKeys.get(rowIndex);
			isTVIX = isTVIXFlags.get(rowIndex).booleanValue();

			db.setDatabase(theInfo.dbName + "_" + theKey);

			db.update("ALTER TABLE scenario ADD COLUMN scenario_type INT NOT NULL DEFAULT 1 AFTER description");
			db.update("ALTER TABLE scenario ADD COLUMN parent_scenario_key INT NOT NULL DEFAULT 0");

			db.update("ALTER TABLE source ADD COLUMN attributes VARCHAR(50000) NOT NULL DEFAULT ''");

			db.update("UPDATE source SET attributes = '" + Source.ATTR_IS_BASELINE + "\n' WHERE status = '" +
				ExtDbRecordTV.BASELINE_STATUS + "'");

			if (isTVIX) {

				db.update("UPDATE scenario SET scenario_type = " + Scenario.SCENARIO_TYPE_TVIX_PROPOSAL +
					" WHERE scenario_key = 1");
				db.update("UPDATE scenario SET scenario_type = " + Scenario.SCENARIO_TYPE_TVIX_INTERFERENCE +
					", parent_scenario_key = 1, is_permanent = false WHERE scenario_key <> 1");

				db.query("SELECT source_key FROM scenario_source WHERE scenario_key = 1 ORDER BY 1");
				if (db.next()) {
					srcKey = db.getInt(1);
					db.update("UPDATE source SET attributes = CONCAT(attributes, '" + Source.ATTR_IS_PROPOSAL +
						"\n') WHERE source_key = " + srcKey);
					db.query("SELECT original_source_key FROM source WHERE source_key = " + srcKey);
					if (db.next()) {
						srcKey = db.getInt(1);
						if (srcKey > 0) {
							db.update("UPDATE source SET attributes = CONCAT(attributes, '" + Source.ATTR_IS_PROPOSAL +
								"\n') WHERE source_key = " + srcKey);
						}
					}
				}
			}
		}
	}

	// Update from 2.2.4.0 to 2.2.4.1

	private static void update20200401(DbInfo theInfo) throws SQLException {

		DbConnection db = theInfo.db;

		db.setDatabase(theInfo.dbName);

		// Some country polygon points for Canada should not be included in distance check.

		db.update("UPDATE country_poly SET is_border = false WHERE poly_seq = 2000 AND vertex_seq IN (1, 2, 3, 4)");

		// Fix a bug from 2.0.11; the update code didn't make this change to match the new installation code.  This
		// will have no effect (and no error) if the existing declaration is already correct.

		db.update("ALTER TABLE user_record CHANGE COLUMN xml_data xml_data MEDIUMTEXT NOT NULL");

		// Saved searches have a type property now instead of just desired/undesired flag, and a channel range.

		db.update("ALTER TABLE ext_db_search ADD COLUMN search_type INT NOT NULL DEFAULT " +
			ExtDbSearch.SEARCH_TYPE_DESIREDS + " AFTER is_desired");
		db.update("UPDATE ext_db_search SET search_type = " + ExtDbSearch.SEARCH_TYPE_UNDESIREDS +
			" WHERE NOT is_desired");
		db.update("ALTER TABLE ext_db_search DROP COLUMN is_desired");

		db.update("ALTER TABLE ext_db_search ADD COLUMN minimum_channel INT NOT NULL DEFAULT 0 AFTER longitude");
		db.update("ALTER TABLE ext_db_search ADD COLUMN maximum_channel INT NOT NULL DEFAULT 0 AFTER minimum_channel");
	}
}
