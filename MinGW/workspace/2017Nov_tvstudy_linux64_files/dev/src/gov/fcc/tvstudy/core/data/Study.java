//
//  Study.java
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.data;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.geo.*;

import java.util.*;
import java.sql.*;
import java.text.*;
import java.io.*;


//=====================================================================================================================
// Data representation class for a study, and methods to obtain a study from the database.  Also static methods to
// create, duplicate, and delete study databases.  The only instance methods are for looking up parameter values.

public class Study extends KeyedRecord {

	// Study types.

	public static final int STUDY_TYPE_TV = 1;
	public static final int STUDY_TYPE_TV_IX = 2;
	public static final int STUDY_TYPE_TV_OET74 = 3;
	public static final int STUDY_TYPE_FM = 4;
	public static final int STUDY_TYPE_TV6_FM = 5;

	// Study modes.

	public static final int STUDY_MODE_GRID = 1;
	public static final int STUDY_MODE_POINTS = 2;

	// Study area modes in grid mode.

	public static final int STUDY_AREA_SERVICE = 1;
	public static final int STUDY_AREA_GEOGRAPHY = 2;
	public static final int STUDY_AREA_NO_BOUNDS = 3;

	// Values for the study_lock flag in the study table, these must be in sync with the study engine code!  All lock
	// states are exclusive except RUN_SHARE.  The UI application uses EDIT and ADMIN; the study engine uses RUN_EXCL
	// and RUN_SHARE.  See study engine code for details of run lock behavior.

	public static final int LOCK_NONE = 0;
	public static final int LOCK_EDIT = 1;
	public static final int LOCK_RUN_EXCL = 2;
	public static final int LOCK_RUN_SHARE = 3;
	public static final int LOCK_ADMIN = 4;

	// Database ID.

	public final String dbID;

	// Database properties:

	// key (super)            Study key, unique in database, always > 0.
	// name (super)           Name, never null or empty, unique in database.
	// description            Description, never null but may be empty.
	// studyType              Study type, STUDY_TYPE_*.
	// studyMode              Study mode, STUDY_MODE_*.
	// templateKey            Study template key.
	// templateName           Template name.
	// templateLocked         True if the template values (rules and parameters) are non-editable in the study.
	// extDbKey               Station data key, or null for a study with no default station data.
	// pointSetKey            Point set used in points mode.
	// propagationModel       Propagation model key, defined by engine code.
	// studyAreaMode          Mode for setting study area in grid mode, STUDY_AREA_*.
	// studyAreaGeoKey        Study area geography key in STUDY_AREA_GEOGRAPHY mode.
	// parameters             List of parameters, never null and never empty.
	// ixRules                List of interference rules, never null but may be empty.
	// sources                List of sources, may be may be empty but never null.
	// scenarios              List of scenarios, never null but may be empty.
	// scenarioPairs          Paired scenarios for comparison, see Scenario.ScenarioPair.
	// scenarioParameters     List of default scenario parameters for new scenarios, never empty, may be null if none.
	// reportPreamble         Report preamble text.  May be null or empty.
	// fileOutputConfigName   File output settings name, never null.
	// fileOutputConfigCodes  File output settings codes, never null.
	// mapOutputConfigName    Map output settings name, never null.
	// mapOutputConfigCodes   Map output settings codes, never null.

	// studyLock              Study lock flag, always begins as LOCK_EDIT, but can change during object lifetime.
	// lockCount              Lock change counter, increments any time there is a change to the lock flag.

	public final String description;
	public final int studyType;
	public final int studyMode;
	public final int templateKey;
	public final String templateName;
	public final boolean templateLocked;
	public final Integer extDbKey;
	public final int pointSetKey;
	public final int propagationModel;
	public final int studyAreaMode;
	public final int studyAreaGeoKey;
	public final ArrayList<Parameter> parameters;
	public final ArrayList<IxRule> ixRules;
	public final ArrayList<Source> sources;
	public final ArrayList<Scenario> scenarios;
	public final ArrayList<Scenario.ScenarioPair> scenarioPairs;
	public final ArrayList<Parameter> scenarioParameters;
	public final String reportPreamble;
	public final String fileOutputConfigName;
	public final String fileOutputConfigCodes;
	public final String mapOutputConfigName;
	public final String mapOutputConfigCodes;

	public int studyLock;
	public int lockCount;

	// Database record modification count.

	public final int modCount;

	// Max values for interference rule and scenario keys, updated when assigning new keys.

	public int maxIxRuleKey;
	public int maxScenarioKey;


	//-----------------------------------------------------------------------------------------------------------------
	// Text names for study types.

	public static String getStudyTypeName(int theType) {

		switch (theType) {
			case STUDY_TYPE_TV: {
				return "General-purpose TV";
			}
			case STUDY_TYPE_TV_IX: {
				return "TV Interference Check";
			}
			case STUDY_TYPE_TV_OET74: {
				return "Wireless->TV Interference";
			}
			case STUDY_TYPE_FM: {
				return "General-purpose FM";
			}
			case STUDY_TYPE_TV6_FM: {
				return "TV6<->FM Interference";
			}
			default: {
				return "???";
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// List of study types, optionally filtered by record type.

	public static ArrayList<KeyedRecord> getStudyTypes() {
		return getStudyTypes(0);
	}

	public static ArrayList<KeyedRecord> getStudyTypes(int recordType) {

		ArrayList<KeyedRecord> result = new ArrayList<KeyedRecord>();

		if ((0 == recordType) || isRecordTypeAllowed(STUDY_TYPE_TV, recordType)) {
			result.add(new KeyedRecord(STUDY_TYPE_TV, getStudyTypeName(STUDY_TYPE_TV)));
		}

		if ((0 == recordType) || isRecordTypeAllowed(STUDY_TYPE_TV_IX, recordType)) {
			result.add(new KeyedRecord(STUDY_TYPE_TV_IX, getStudyTypeName(STUDY_TYPE_TV_IX)));
		}

		if ((0 == recordType) || isRecordTypeAllowed(STUDY_TYPE_TV_OET74, recordType)) {
			result.add(new KeyedRecord(STUDY_TYPE_TV_OET74, getStudyTypeName(STUDY_TYPE_TV_OET74)));
		}

		if ((0 == recordType) || isRecordTypeAllowed(STUDY_TYPE_FM, recordType)) {
			result.add(new KeyedRecord(STUDY_TYPE_FM, getStudyTypeName(STUDY_TYPE_FM)));
		}

		if ((0 == recordType) || isRecordTypeAllowed(STUDY_TYPE_TV6_FM, recordType)) {
			result.add(new KeyedRecord(STUDY_TYPE_TV6_FM, getStudyTypeName(STUDY_TYPE_TV6_FM)));
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static int getDefaultRecordType(int studyType) {

		switch (studyType) {
			case STUDY_TYPE_TV: {
				return Source.RECORD_TYPE_TV;
			}
			case STUDY_TYPE_TV_IX: {
				return Source.RECORD_TYPE_TV;
			}
			case STUDY_TYPE_TV_OET74: {
				return Source.RECORD_TYPE_TV;
			}
			case STUDY_TYPE_FM: {
				return Source.RECORD_TYPE_FM;
			}
			case STUDY_TYPE_TV6_FM: {
				return Source.RECORD_TYPE_TV;
			}
		}

		return Source.RECORD_TYPE_TV;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static boolean canUsePointsMode(int studyType) {

		switch (studyType) {
			case STUDY_TYPE_TV: {
				return true;
			}
			case STUDY_TYPE_TV_IX: {
				return false;
			}
			case STUDY_TYPE_TV_OET74: {
				return true;
			}
			case STUDY_TYPE_FM: {
				return true;
			}
			case STUDY_TYPE_TV6_FM: {
				return true;
			}
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Determine whether a study type allows a record type.

	public static boolean isRecordTypeAllowed(int studyType, int recordType) {

		switch (studyType) {
			case STUDY_TYPE_TV: {
				return (Source.RECORD_TYPE_TV == recordType);
			}
			case STUDY_TYPE_TV_IX: {
				return (Source.RECORD_TYPE_TV == recordType);
			}
			case STUDY_TYPE_TV_OET74: {
				return ((Source.RECORD_TYPE_TV == recordType) || (Source.RECORD_TYPE_WL == recordType));
			}
			case STUDY_TYPE_FM: {
				return (Source.RECORD_TYPE_FM == recordType);
			}
			case STUDY_TYPE_TV6_FM: {
				return ((Source.RECORD_TYPE_TV == recordType) || (Source.RECORD_TYPE_FM == recordType));
			}
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public Study(String theDbID, int theKey, String theName, String theDescription, int theStudyType, int theStudyMode,
			int theTemplateKey, String theTemplateName, boolean isTemplateLocked, Integer theExtDbKey,
			int thePointSetKey, int thePropModel, int theAreaMode, int theAreaGeoKey,
			ArrayList<Parameter> theParameters, ArrayList<IxRule> theIxRules, ArrayList<Source> theSources,
			ArrayList<Scenario> theScenarios, ArrayList<Scenario.ScenarioPair> thePairs,
			ArrayList<Parameter> theScenarioParameters, String theReport, String theFileOutputConfigName,
			String theFileOutputConfigCodes, String theMapOutputConfigName, String theMapOutputConfigCodes,
			int theStudyLock, int theLockCount, int theModCount, int theMaxIxRuleKey, int theMaxScenarioKey) {

		super(theKey, theName);

		dbID = theDbID;

		description = theDescription;
		studyType = theStudyType;
		studyMode = theStudyMode;
		templateKey = theTemplateKey;
		templateName = theTemplateName;
		templateLocked = isTemplateLocked;
		extDbKey = theExtDbKey;
		pointSetKey = thePointSetKey;
		propagationModel = thePropModel;
		studyAreaMode = theAreaMode;
		studyAreaGeoKey = theAreaGeoKey;
		parameters = theParameters;
		ixRules = theIxRules;
		sources = theSources;
		scenarios = theScenarios;
		scenarioPairs = thePairs;
		scenarioParameters = theScenarioParameters;
		reportPreamble = theReport;
		fileOutputConfigName = theFileOutputConfigName;
		fileOutputConfigCodes = theFileOutputConfigCodes;
		mapOutputConfigName = theMapOutputConfigName;
		mapOutputConfigCodes = theMapOutputConfigCodes;

		studyLock = theStudyLock;
		lockCount = theLockCount;

		modCount = theModCount;

		maxIxRuleKey = theMaxIxRuleKey;
		maxScenarioKey = theMaxScenarioKey;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve a study object by key.  There is no factory method to retrieve a list of all studies; due to the amount
	// of data that could involve, the entire set of study objects is never loaded by the application.  This method
	// assumes the study object is being retrieved to be used immediately in an editor or for some manipulation of the
	// study database.  It checks the study lock flag in the database and will fail if the study is already locked, if
	// not this will set an EDIT lock before returning the study object.  The lock must always be cleared again later,
	// typically by calling unlockStudy().  The database version number is re-checked here.  That was checked when the
	// database was first opened, however it is possible this is an older application version and the database was
	// subsequently updated or is being updated; see DbCore.openDb().

	public static Study getStudy(String theDbID, int theKey) {
		return getStudy(theDbID, theKey, null);
	}

	public static Study getStudy(String theDbID, int theKey, ErrorLogger errors) {

		String rootName = DbCore.getDbName(theDbID);
		String theDbName = rootName + "_" + theKey;

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return null;
		}

		Study result = null;

		boolean error = false, lockSet = false, isTempLock = false;
		String errmsg = "", theName = String.valueOf(theKey), theStudyName = "", theDescription = "",
			theTemplateName = "", theReport = "", theOutFileName = "", theOutFileCodes = "", theOutMapName = "",
			theOutMapCodes = "";
		int theLockCount = 0, theStudyType = 0, theStudyMode = 0, theTemplateKey = 0, thePropModel = 0,
			theAreaMode = 0, thePointSetKey = 0, theAreaGeoKey = 0, theModCount = 0, errtyp = AppCore.ERROR_MESSAGE;
		Integer theExtDbKey = null;

		// The study table is exclusively locked while checking and updating the persistent locking properties in the
		// study record (study_lock and lock_count).  To minimize delay to other clients the table lock is released as
		// soon as possible, before the rest of the study data queries are performed.  The assumption is that all
		// client applications will follow this locking protocol.  Also this is careful to track errors but not report
		// them immediately, in a GUI setting the ErrorLogger object may be an ErrorReporter and show a modal dialog,
		// so it is not used until after the table lock is released.  Note the query does an inner join to the template
		// table, if the template_key is not valid the study does not exist.

		try {

			db.update("LOCK TABLES study WRITE, version WRITE, template WRITE");

			db.query(
			"SELECT " +
				"study.name, " +
				"version.version, " +
				"study.study_lock, " +
				"study.lock_count, " +
				"study.description, " +
				"study.study_type, " +
				"study.study_mode, " +
				"study.template_key, " +
				"template.name, " +
				"template.locked_in_study, " +
				"study.ext_db_key, " +
				"study.point_set_key, " +
				"study.propagation_model, " +
				"study.study_area_mode, " +
				"study.study_area_geo_key, " +
				"study.output_config_file_name, " +
				"study.output_config_file_codes, " +
				"study.output_config_map_name, " +
				"study.output_config_map_codes, " +
				"study.report_preamble, " +
				"study.mod_count " +
			"FROM " +
				"study " +
				"JOIN version " +
				"JOIN template USING (template_key) " +
			"WHERE " +
				"study_key = " + theKey);

			if (db.next()) {

				theStudyName = db.getString(1);
				theName = "'" + theStudyName + "'";

				if (DbCore.DATABASE_VERSION == db.getInt(2)) {

					if (LOCK_NONE == db.getInt(3)) {

						theLockCount = db.getInt(4);

						theDescription = db.getString(5);
						theStudyType = db.getInt(6);
						theStudyMode = db.getInt(7);
						theTemplateKey = db.getInt(8);
						theTemplateName = db.getString(9);
						isTempLock = db.getBoolean(10);
						int k = db.getInt(11);
						if (k > 0) {
							theExtDbKey = Integer.valueOf(k);
						}
						thePointSetKey = db.getInt(12);
						thePropModel = db.getInt(13);
						theAreaMode = db.getInt(14);
						theAreaGeoKey = db.getInt(15);
						theOutFileName = db.getString(16);
						theOutFileCodes = db.getString(17);
						theOutMapName = db.getString(18);
						theOutMapCodes = db.getString(19);
						theReport = db.getString(20);
						theModCount = db.getInt(21);

						db.update("UPDATE study SET study_lock = " + LOCK_EDIT +
							", lock_count = lock_count + 1, share_count = 0 WHERE study_key = " + theKey);
						lockSet = true;
						theLockCount++;

						db.update("UNLOCK TABLES");

						// Get primary key max values.

						int theMaxRuleKey = 0, theMaxScenKey = 0;

						db.query("SELECT MAX(ix_rule_key) FROM " + theDbName + ".ix_rule");
						if (db.next()) {
							theMaxRuleKey = db.getInt(1);
						}

						db.query("SELECT MAX(scenario_key) FROM " + theDbName + ".scenario");
						if (db.next()) {
							theMaxScenKey = db.getInt(1);
						}

						// Get default scenario parameters, if any, this is often null.

						ArrayList<Parameter> defScenarioParams =
							Parameter.getDefaultScenarioParameters(db, rootName, theTemplateKey, theStudyType);

						// Load sources.  All record types are loaded regardless of study type; this must load all
						// records otherwise keys could be duplicated.  Restrictions on record type per study type
						// must be applied when new sources are added to the model.

						ArrayList<Source> theSources = new ArrayList<Source>();
						SourceTV.getSources(db, theDbID, theDbName, theSources);
						SourceWL.getSources(db, theDbID, theDbName, theSources);
						SourceFM.getSources(db, theDbID, theDbName, theSources);

						result = new Study(
							theDbID,
							theKey,
							theStudyName,
							theDescription,
							theStudyType,
							theStudyMode,
							theTemplateKey,
							theTemplateName,
							isTempLock,
							theExtDbKey,
							thePointSetKey,
							thePropModel,
							theAreaMode,
							theAreaGeoKey,
							Parameter.getParameters(db, theDbName, rootName, theTemplateKey, theStudyType),
							IxRule.getIxRules(db, theDbName, rootName, theTemplateKey, theStudyType),
							theSources,
							Scenario.getScenarios(db, theDbName, rootName, theTemplateKey, theStudyType,
								defScenarioParams),
							Scenario.getScenarioPairs(db, theDbName),
							defScenarioParams,
							theReport,
							theOutFileName,
							theOutFileCodes,
							theOutMapName,
							theOutMapCodes,
							LOCK_EDIT,
							theLockCount,
							theModCount,
							theMaxRuleKey,
							theMaxScenKey);

					} else {
						error = true;
						errmsg = "The study is in use by another application.";
						errtyp = AppCore.WARNING_MESSAGE;
					}

				} else {
					error = true;
					errmsg = "The database version is incorrect.";
				}

			} else {
				error = true;
				errmsg = "The study does not exist.";
			}

		} catch (SQLException se) {
			error = true;
			errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
			db.reportError(se);
		}

		// Make sure locks are cleared; the persistent study lock remains set if there was no error.

		try {
			db.update("UNLOCK TABLES");
			if (error && lockSet) {
				db.update("UPDATE " + rootName + ".study SET study_lock = " + LOCK_NONE +
					", lock_count = lock_count + 1, share_count = 0 WHERE study_key = " + theKey);
			}
		} catch (SQLException se) {
			db.reportError(se);
		}

		DbCore.releaseDb(db);

		if (error) {
			if (null != errors) {
				errors.reportError("Could not open study " + theName + ":\n" + errmsg, errtyp);
			}
			return null;
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Look up a study by name and open it if found.

	public static Study getStudy(String theDbID, String theStudyName) {
		return getStudy(theDbID, theStudyName, null);
	}

	public static Study getStudy(String theDbID, String theStudyName, ErrorLogger errors) {

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return null;
		}

		boolean error = false;
		String errmsg = "";
		int errtyp = AppCore.ERROR_MESSAGE;

		int studyKey = 0;

		try {

			db.query("SELECT study_key FROM study WHERE name = '" + db.clean(theStudyName) + "'");

			if (db.next()) {
				studyKey = db.getInt(1);
			} else {
				error = true;
				errmsg = "Study name not found.";
				errtyp = AppCore.WARNING_MESSAGE;
			}

		} catch (SQLException se) {
			error = true;
			errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
			db.reportError(se);
		}

		DbCore.releaseDb(db);

		if (error) {
			if (null != errors) {
				errors.reportError("Could not open study '" + theStudyName + "':\n" + errmsg, errtyp);
			}
			return null;
		}

		return getStudy(theDbID, studyKey, errors);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create a new study with specified name, type, template, and optional station data (the extDbKey argument may be
	// null for no default).  A new study database is created and the interference rules and parameters are initialized
	// from the template.  The new study key is returned, or null on error.  The template must exist or this will fail.
	// The station data key is just an advisory default and an invalid key will not cause any errors later so it is not
	// checked.  This assumes basic validity checks have been done on the new study name including uniqueness, e.g.
	// using DbCore.checkStudyName().  However concurrent operations could still duplicate names so a concurrent-safe
	// uniqueness check is done here.  Optionally, in the case of a name collision this can make the new name unique
	// by appending the study key, which is guaranteed to be unique.

	public static Integer createNewStudy(String theDbID, String studyName, int studyType, int templateKey,
			Integer extDbKey) {
		return createNewStudy(theDbID, studyName, studyType, templateKey, extDbKey, false, null);
	}

	public static Integer createNewStudy(String theDbID, String studyName, int studyType, int templateKey,
			Integer extDbKey, ErrorLogger errors) {
		return createNewStudy(theDbID, studyName, studyType, templateKey, extDbKey, false, errors);
	}

	public static Integer createNewStudy(String theDbID, String studyName, int studyType, int templateKey,
			Integer extDbKey, boolean makeUnique) {
		return createNewStudy(theDbID, studyName, studyType, templateKey, extDbKey, makeUnique, null);
	}

	public static Integer createNewStudy(String theDbID, String studyName, int studyType, int templateKey,
			Integer extDbKey, boolean makeUnique, ErrorLogger errors) {

		String rootName = DbCore.getDbName(theDbID);

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return null;
		}

		boolean error = false;
		String errmsg = "";
		int studyKey = 0, errtyp = AppCore.ERROR_MESSAGE;

		// Lock tables, check template key.

		try {

			db.update("LOCK TABLES study WRITE, template WRITE, study_key_sequence WRITE");

			db.query("SELECT template_key FROM template WHERE template_key = " + templateKey);
			if (!db.next()) {
				error = true;
				errmsg = "Template key " + templateKey + " does not exist.";
			}

			// Check for name uniqueness, set flag to append key if needed and allowed.

			boolean appendKey = false;

			if (!error) {

				db.query("SELECT study_key FROM study WHERE UPPER(name) = '" + db.clean(studyName.toUpperCase()) + "'");

				if (db.next()) {
					if (makeUnique) {
						appendKey = true;
					} else {
						error = true;
						errmsg = "Study name '" + studyName + "' already exists.";
						errtyp = AppCore.WARNING_MESSAGE;
					}
				}
			}

			// Get the study key, append to name if needed.

			if (!error) {

				db.update("UPDATE study_key_sequence SET study_key = study_key + 1");
				db.query("SELECT study_key FROM study_key_sequence");
				db.next();
				studyKey = db.getInt(1);

				if (appendKey) {
					studyName = studyName + " (" + String.valueOf(studyKey) + ")";
				}

				// Insert the study table record.  The database can't be created with LOCK TABLES in effect, so the
				// study record is inserted with the lock set so nothing should try to access during creation.

				db.update(
				"INSERT INTO study (" +
					"study_key, " +
					"name, " +
					"description, " +
					"study_lock, " +
					"lock_count, " +
					"share_count, " +
					"study_type, " +
					"study_mode, " +
					"needs_update, " +
					"mod_count, " +
					"template_key, " +
					"ext_db_key, " +
					"point_set_key, " +
					"propagation_model, " +
					"study_area_mode, " +
					"study_area_geo_key, " +
					"output_config_file_name, " +
					"output_config_file_codes, " +
					"output_config_map_name, " +
					"output_config_map_codes, " +
					"report_preamble, " +
					"parameter_summary, " +
					"ix_rule_summary) " +
				"VALUES (" +
					studyKey + ", "  +
					"'" + db.clean(studyName) + "', " +
					"'', " +
					Study.LOCK_ADMIN + ", " +
					"1, " +
					"0, " +
					studyType + ", " +
					STUDY_MODE_GRID + ", " +
					"false, " +
					"0, " +
					templateKey + ", " +
					((null != extDbKey) ? String.valueOf(extDbKey) : "0") + ", " +
					"0, " +
					"1, " +
					STUDY_AREA_SERVICE + ", " +
					"0, " +
					"'', " +
					"'', " +
					"'', " +
					"'', " +
					"'', " +
					"'', " +
					"'')");

				// Release table locks and create the study database and tables.

				db.update("UNLOCK TABLES");

				String dbName = rootName + "_" + studyKey;

				db.update("CREATE DATABASE " + dbName);

				Parameter.createTables(db, dbName, rootName, templateKey, studyType);
				IxRule.createTables(db, dbName, rootName, templateKey, studyType);
				Scenario.createTables(db, dbName);
				Source.createTables(db, dbName);
			}

		} catch (SQLException se) {
			error = true;
			errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
			db.reportError(se);

		} catch (Throwable t) {
			error = true;
			errmsg = "An unexpected error occurred:\n" + t;
			AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
		}

		// Make sure table locks are released.  If the create succeeded, clear the study lock.  If not, delete the
		// record and database.

		try {
			db.update("UNLOCK TABLES");
			db.setDatabase(rootName);
			if (!error) {
				db.update("UPDATE study SET study_lock = " + Study.LOCK_NONE +
					", lock_count = lock_count + 1, share_count = 0 WHERE study_key = " + studyKey);
			} else {
				if (studyKey > 0) {
					db.update("DELETE FROM study WHERE study_key = " + studyKey);
					db.update("DROP DATABASE IF EXISTS " + rootName + "_" + studyKey);
				}
			}
		} catch (SQLException se) {
			db.reportError(se);
		}

		DbCore.releaseDb(db);

		if (error) {
			if (null != errors) {
				errors.reportError("Could not create new study:\n" + errmsg, errtyp);
			}
			return null;
		}

		return Integer.valueOf(studyKey);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create a duplicate of an existing study, copying everything but the name.  As with creating a new study, this
	// works directly on the database.  The study being duplicated must not be locked.  The new study key is returned,
	// or null on error.  See discussion in createNewStudy().

	public static Integer duplicateStudy(String theDbID, int oldKey, String newName) {
		return duplicateStudy(theDbID, oldKey, newName, null);
	}

	public static Integer duplicateStudy(String theDbID, int oldKey, String newName, ErrorLogger errors) {

		String rootName = DbCore.getDbName(theDbID);

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return null;
		}

		boolean error = false, lockSet = false;
		String errmsg = "", theName = String.valueOf(oldKey);
		int studyKey = 0, errtyp = AppCore.ERROR_MESSAGE;

		try {

			db.update("LOCK TABLES study WRITE, version WRITE, template WRITE, study_key_sequence WRITE");

			db.query(
			"SELECT " +
				"study.name, " +
				"version.version, " +
				"study.study_lock, " +
				"study.lock_count, " +
				"study.description, " +
				"study.study_type, " +
				"study.study_mode, " +
				"study.needs_update, " +
				"study.template_key, " +
				"study.ext_db_key, " +
				"study.point_set_key, " +
				"study.propagation_model, " +
				"study.study_area_mode, " +
				"study.study_area_geo_key, " +
				"study.output_config_file_name, " +
				"study.output_config_file_codes, " +
				"study.output_config_map_name, " +
				"study.output_config_map_codes, " +
				"study.report_preamble, " +
				"study.parameter_summary, " +
				"study.ix_rule_summary " +
			"FROM " +
				"study " +
				"JOIN version " +
			"WHERE " +
				"study_key = " + oldKey);

			if (db.next()) {

				theName = "'" + db.getString(1) + "'";

				if (DbCore.DATABASE_VERSION == db.getInt(2)) {

					if (Study.LOCK_NONE == db.getInt(3)) {

						int lockCount = db.getInt(4);

						String description = db.getString(5);
						int studyType = db.getInt(6);
						int studyMode = db.getInt(7);
						boolean needsUpdate = db.getBoolean(8);
						int templateKey = db.getInt(9);
						int extDbKey = db.getInt(10);
						int pointSetKey = db.getInt(11);
						int propModel = db.getInt(12);
						int areaMode = db.getInt(13);
						int areaGeoKey = db.getInt(14);
						String outFileName = db.getString(15);
						String outFileCodes = db.getString(16);
						String outMapName = db.getString(17);
						String outMapCodes = db.getString(18);
						String reportPre = db.getString(19);
						String paramSummary = db.getString(20);
						String ruleSummary = db.getString(21);

						db.update("UPDATE study SET study_lock = " + Study.LOCK_ADMIN +
							", lock_count = lock_count + 1, share_count = 0 WHERE study_key = " + oldKey);
						lockSet = true;
						lockCount++;

						db.update("UPDATE study_key_sequence SET study_key = study_key + 1");
						db.query("SELECT study_key FROM study_key_sequence");
						db.next();
						studyKey = db.getInt(1);

						db.update("UNLOCK TABLES");

						// Create the new database, copy all tables.

						String dbName = rootName + "_" + studyKey;
						String fromName = rootName + "_" + oldKey;

						db.update("CREATE DATABASE " + dbName);

						Parameter.copyTables(db, dbName, fromName);
						IxRule.copyTables(db, dbName, fromName);
						Source.copyTables(db, dbName, fromName);
						Scenario.copyTables(db, dbName, fromName);

						// Check the lock to be sure it did not change, check name for uniqueness, and write the new
						// study record.

						db.setDatabase(rootName);
						db.update("LOCK TABLES study WRITE, study_geography WRITE");

						db.query("SELECT study_lock, lock_count FROM study WHERE study_key = " + oldKey);

						if (db.next()) {

							if ((db.getInt(1) == Study.LOCK_ADMIN) && (db.getInt(2) == lockCount)) {

								db.query("SELECT study_key FROM study WHERE UPPER(name) = '" +
									db.clean(newName.toUpperCase()) + "'");
								if (!db.next()) {

									db.update(
									"INSERT INTO study (" +
										"study_key, " +
										"name, " +
										"description, " +
										"study_lock, " +
										"lock_count, " +
										"share_count, " +
										"study_type, " +
										"study_mode, " +
										"needs_update, " +
										"mod_count, " +
										"template_key, " +
										"ext_db_key, " +
										"point_set_key, " +
										"propagation_model, " +
										"study_area_mode, " +
										"study_area_geo_key, " +
										"output_config_file_name, " +
										"output_config_file_codes, " +
										"output_config_map_name, " +
										"output_config_map_codes, " +
										"report_preamble, " +
										"parameter_summary, " +
										"ix_rule_summary) " +
									"VALUES (" +
										studyKey + ", "  +
										"'" + db.clean(newName) + "', " +
										"'" + db.clean(description) + "', " +
										Study.LOCK_NONE + ", " +
										"0, " +
										"0, " +
										studyType + ", " +
										studyMode + ", " +
										needsUpdate + ", " +
										"0, " +
										templateKey + ", " +
										extDbKey + ", " +
										pointSetKey + ", " +
										propModel + ", " +
										areaMode + ", " +
										areaGeoKey + ", " +
										"'" + db.clean(outFileName) + "', " +
										"'" + db.clean(outFileCodes) + "', " +
										"'" + db.clean(outMapName) + "', " +
										"'" + db.clean(outMapCodes) + "', " +
										"'" + db.clean(reportPre) + "', " +
										"'" + db.clean(paramSummary) + "', " +
										"'" + db.clean(ruleSummary) + "')");

									if (pointSetKey > 0) {
										db.update("INSERT INTO study_geography VALUES (" + studyKey + "," +
											pointSetKey + ")");
									}

									if (areaGeoKey > 0) {
										db.update("INSERT INTO study_geography VALUES (" + studyKey + "," +
											areaGeoKey + ")");
									}

								} else {
									error = true;
									errmsg = "Study name '" + newName + "' already exists.";
									errtyp = AppCore.WARNING_MESSAGE;
								}

							} else {
								lockSet = false;
								error = true;
								errmsg = "The study lock was modified.";
							}

						} else {
							lockSet = false;
							error = true;
							errmsg = "The study was deleted.";
						}

					} else {
						error = true;
						errmsg = "The study is in use by another application.";
						errtyp = AppCore.WARNING_MESSAGE;
					}

				} else {
					error = true;
					errmsg = "The database version is incorrect.";
				}

			} else {
				error = true;
				errmsg = "The study does not exist.";
			}

		} catch (SQLException se) {
			error = true;
			errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
			db.reportError(se);
		}

		// Be sure locks are released.  If an error occurred drop the database.

		try {
			db.update("UNLOCK TABLES");
			db.setDatabase(rootName);
			if (lockSet) {
				db.update("UPDATE study SET study_lock = " + Study.LOCK_NONE +
					", lock_count = lock_count + 1, share_count = 0 WHERE study_key = " + oldKey);
			}
			if (error && (studyKey > 0)) {
				db.update("DROP DATABASE IF EXISTS " + rootName + "_" + studyKey);
			}
		} catch (SQLException se) {
			db.reportError(se);
		}

		DbCore.releaseDb(db);

		if (error) {
			if (null != errors) {
				errors.reportError("Could not duplicate study " + theName + ":\n" + errmsg, errtyp);
			}
			return null;
		}

		return Integer.valueOf(studyKey);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Delete a study.  If the lockCount is >0 the study will be deleted only if it has a matching lock, if lockCount
	// is 0 the study will be deleted only if it is unlocked.

	public static boolean deleteStudy(String theDbID, int studyKey) {
		return deleteStudy(theDbID, studyKey, 0, null);
	}

	public static boolean deleteStudy(String theDbID, int studyKey, int lockCount) {
		return deleteStudy(theDbID, studyKey, lockCount, null);
	}

	public static boolean deleteStudy(String theDbID, int studyKey, ErrorLogger errors) {
		return deleteStudy(theDbID, studyKey, 0, errors);
	}

	public static boolean deleteStudy(String theDbID, int studyKey, int lockCount, ErrorLogger errors) {

		String rootName = DbCore.getDbName(theDbID);

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return false;
		}

		boolean error = false;
		String errmsg = "", theName = String.valueOf(studyKey);
		int errtyp = AppCore.ERROR_MESSAGE;

		// No need to set the persistent lock here, just be sure the lock state matches appropriately to start.  Note
		// it is not an error if the study does not exist, just continue.  Also delete all geography records linked to
		// the study, see Geography.deleteStudyGeographies().

		try {

			db.update("LOCK TABLES study WRITE, study_geography WRITE");

			db.query(
			"SELECT " +
				"name, " +
				"study_lock, " +
				"lock_count " +
			"FROM " +
				"study " +
			"WHERE " +
				"study_key = " + studyKey);

			if (db.next()) {

				theName = "'" + db.getString(1) + "'";

				if (((0 == lockCount) && (Study.LOCK_NONE == db.getInt(2))) || (db.getInt(3) == lockCount)) {

					db.update("DELETE FROM study WHERE study_key = " + studyKey);
					db.update("DELETE FROM study_geography WHERE study_key = " + studyKey);

					db.update("UNLOCK TABLES");

					db.update("DROP DATABASE IF EXISTS " + rootName + "_" + studyKey);

					Geography.deleteStudyGeographies(db, rootName, studyKey, null);

				} else {
					error = true;
					errmsg = "The study is in use by another application.";
					errtyp = AppCore.WARNING_MESSAGE;
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

		if (error) {
			if (null != errors) {
				errors.reportError("Could not delete study " + theName + ":\n" + errmsg, errtyp);
			}
			return false;
		}

		// Delete study engine cache files.

		AppCore.deleteStudyCache(theDbID, studyKey);

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Clear the lock on a study.  If the lockCount argument is >0 the lock is cleared only if the current lock count
	// in the database matches that value, also in that case if the current lock is shared this may not clear the lock
	// entirely, if the share count is more than 1 that is just decremented.  If the lockCount argument is 0 this will
	// completely clear the lock state regardless, that is done only at specific user instruction to clear a "stuck"
	// lock.  When the lock is cleared the lock count is incremented so any concurrency violation will be detected by
	// other clients.  If the study is not currently locked this silently does nothing.

	public static boolean unlockStudy(String theDbID, int theKey, int lockCount) {
		return unlockStudy(theDbID, theKey, lockCount, null);
	}

	public static boolean unlockStudy(String theDbID, int theKey, int lockCount, ErrorLogger errors) {

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return false;
		}

		boolean error = false;
		String errmsg = "", theName = String.valueOf(theKey);
		int errtyp = AppCore.ERROR_MESSAGE;

		try {

			db.update("LOCK TABLES study WRITE");

			db.query("SELECT name, study_lock, lock_count, share_count FROM study WHERE study_key = " + theKey);
			if (db.next()) {

				theName = "'" + db.getString(1) + "'";
				int theLock = db.getInt(2);
				int theLockCount = db.getInt(3);
				int theShareCount = db.getInt(4);

				if (LOCK_NONE != theLock) {

					if (0 == lockCount) {
						db.update("UPDATE study SET study_lock = " + LOCK_NONE +
							", lock_count = lock_count + 1, share_count = 0 WHERE study_key = " + theKey);
					} else {

						if (theLockCount == lockCount) {

							if ((LOCK_RUN_SHARE != theLock) || (theShareCount <= 1)) {

								db.update("UPDATE study SET study_lock = " + LOCK_NONE +
									", lock_count = lock_count + 1, share_count = 0 WHERE study_key = " + theKey);

							} else {

								db.update("UPDATE study SET share_count = share_count - 1 " +
									"WHERE study_key = " + theKey);
							}

						} else {
							error = true;
							errmsg = "The study lock ID does not match.";
						}
					}
				}

			} else {
				error = true;
				errmsg = "The study does not exist.";
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

		if (error) {
			if (null != errors) {
				errors.reportError("Could not unlock study " + theName + ":\n" + errmsg, errtyp);
			}
			return false;
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Find the study key for a study name, return 0 if not found, -1 for error.  This is mainly used to make sure new
	// study names are unique; see comments in DbCore.checkStudyName().  Match is case-insensitive.

	public static int getStudyKeyForName(String theDbID, String theName) {
		return getStudyKeyForName(theDbID, theName, null);
	}

	public static int getStudyKeyForName(String theDbID, String theName, ErrorLogger errors) {

		int result = -1;

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null != db) {
			try {

				db.query("SELECT study_key FROM study WHERE UPPER(name) = '" + db.clean(theName.toUpperCase()) + "'");
				if (db.next()) {
					result = db.getInt(1);
				} else {
					result = 0;
				}

				DbCore.releaseDb(db);

			} catch (SQLException se) {
				DbCore.releaseDb(db);
				DbConnection.reportError(errors, se);
			}
		}

		return result;
	}
}
