//
//  Scenario.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.data;

import gov.fcc.tvstudy.core.*;

import java.util.*;
import java.sql.*;


//=====================================================================================================================
// Representation class for a scenario.  A scenario is dependent on a parent object, typically a Study, for the actual
// source objects, which are largely shared between scenarios.  This model just provides a list of the source record
// keys, and flags indicating how those sources are used during a study run.  Also may have a parameter list for study
// types that have per-scenario parameters.

public class Scenario extends KeyedRecord {

	// Scenario types.

	public static final int SCENARIO_TYPE_DEFAULT = 1;
	public static final int SCENARIO_TYPE_TVIX_PROPOSAL = 2;
	public static final int SCENARIO_TYPE_TVIX_INTERFERENCE = 3;

	// Database properties:

	// key (super)        Scenario key, unique within a database, always > 0.
	// name (super)       Name, never null or empty.
	// description        Description, never null but may be empty.
	// scenarioType       Type used in some study types, ignored in others.
	// isPermanent        If true the scenario cannot be removed from the model.
	// parameters         List of scenario parameters, may be null but never empty.
	// sourceList         List of sources in the scenario, just the keys and flags, never null or empty.

	// parentScenarioKey  Used in some study types for scenarios derived from other scenarios, or null.
	// childScenarios     List of derived scenarios for this scenario, or null.

	public final String description;
	public final int scenarioType;
	public final boolean isPermanent;
	public final ArrayList<Parameter> parameters;
	public final ArrayList<SourceListItem> sourceList;

	public final Integer parentScenarioKey;
	public final ArrayList<Scenario> childScenarios;


	//-----------------------------------------------------------------------------------------------------------------

	public Scenario(int theKey, String theName, String theDescription, int theType, boolean thePermFlag,
			ArrayList<Parameter> theParams, ArrayList<SourceListItem> theList, Integer theParentKey,
			ArrayList<Scenario> theChildScenarios) {

		super(theKey, theName);

		description = theDescription;
		scenarioType = theType;
		isPermanent = thePermFlag;
		parameters = theParams;
		sourceList = theList;

		parentScenarioKey = theParentKey;
		childScenarios = theChildScenarios;
	}


	//=================================================================================================================
	// Data class to hold a source key, desired and undesired flags, and a permanent flag.  The permanent flag will
	// prevent the entry from being removed from the scenario or the desired/undesired flags from being changed.  The
	// source record referenced by the entry may change, it is a role that is being protected not a specific source.

	public static class SourceListItem {

		public final int key;
		public final boolean isDesired;
		public final boolean isUndesired;
		public final boolean isPermanent;


		//-------------------------------------------------------------------------------------------------------------

		public SourceListItem(int theKey, boolean theDesFlag, boolean theUndFlag, boolean thePermFlag) {

			key = theKey;
			isDesired = theDesFlag;
			isUndesired = theUndFlag;
			isPermanent = thePermFlag;
		}
	}


	//=================================================================================================================
	// A scenario pairing links together two scenarios and a desired source in each for post-study comparison.  When
	// scenarios are paired the scenarios and source entries involved must also be marked permanent so they cannot be
	// altered or removed.

	public static class ScenarioPair {

		public final String name;
		public final String description;
		public final int scenarioKeyA;
		public final int sourceKeyA;
		public final int scenarioKeyB;
		public final int sourceKeyB;


		//-------------------------------------------------------------------------------------------------------------

		public ScenarioPair(String theName, String theDesc, int theScenKeyA, int theSrcKeyA, int theScenKeyB,
				int theSrcKeyB) {

			name = theName;
			description = theDesc;
			scenarioKeyA = theScenKeyA;
			sourceKeyA = theSrcKeyA;
			scenarioKeyB = theScenKeyB;
			sourceKeyB = theSrcKeyB;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve a list of all scenarios in a database, see e.g. Study.  Caller provides an open connection and handles
	// exceptions.  The study type is passed so scenario parameters can be loaded correctly.  Also the default scenario
	// parameters are provided in case an existing scenario does not have parameters, in which case the defaults are
	// applied.  That is necessary to support adding scenario parameters to an existing study type that had none.

	public static ArrayList<Scenario> getScenarios(DbConnection db, String theDbName, String rootName, int templateKey,
			int studyType, ArrayList<Parameter> defaultParameters) throws SQLException {

		// Load the lists of source keys and flags per scenario.

		db.setDatabase(theDbName);

		db.query(
		"SELECT " +
			"scenario_key, " +
			"source_key, " +
			"is_desired, " +
			"is_undesired, " +
			"is_permanent " +
		"FROM " +
			"scenario_source " +
		"ORDER BY " +
			"1, 2");

		HashMap<Integer, ArrayList<SourceListItem>> sourceListMap = new HashMap<Integer, ArrayList<SourceListItem>>();

		int scenarioKey, lastScenarioKey = 0;
		ArrayList<SourceListItem> sourceList = null;

		while (db.next()) {

			scenarioKey = db.getInt(1);
			if (scenarioKey != lastScenarioKey) {
				sourceList = new ArrayList<SourceListItem>();
				sourceListMap.put(Integer.valueOf(scenarioKey), sourceList);
				lastScenarioKey = scenarioKey;
			}

			sourceList.add(new SourceListItem(
				db.getInt(2),
				db.getBoolean(3),
				db.getBoolean(4),
				db.getBoolean(5)));
		}

		// Get scenario parameters.

		HashMap<Integer, ArrayList<Parameter>> paramsMap =
			Parameter.getScenarioParameters(db, theDbName, rootName, templateKey, studyType);

		// Get the scenarios.  First query for child scenarios, build lists by matching parent key.

		db.setDatabase(theDbName);

		HashMap<Integer, ArrayList<Scenario>> childMap = new HashMap<Integer, ArrayList<Scenario>>();

		db.query(
		"SELECT " +
			"scenario_key, " +
			"name, " +
			"description, " +
			"scenario_type, " +
			"is_permanent, " +
			"parent_scenario_key " +
		"FROM " +
			"scenario " +
		"WHERE " +
			"parent_scenario_key <> 0 " +
		"ORDER BY 6, 1");

		Integer theKey;
		ArrayList<Parameter> params;
		int parentKey, lastParentKey = 0;
		ArrayList<Scenario> theChildScenarios = null;

		while (db.next()) {

			theKey = Integer.valueOf(db.getInt(1));

			sourceList = sourceListMap.get(theKey);
			if (null == sourceList) {
				continue;
			}

			params = paramsMap.get(theKey);
			if (null == params) {
				params = defaultParameters;
			}

			parentKey = db.getInt(6);
			if (parentKey != lastParentKey) {
				theChildScenarios = new ArrayList<Scenario>();
				childMap.put(Integer.valueOf(parentKey), theChildScenarios);
				lastParentKey = parentKey;
			}

			theChildScenarios.add(new Scenario(
				theKey.intValue(),
				db.getString(2),
				db.getString(3),
				db.getInt(4),
				db.getBoolean(5),
				params,
				sourceList,
				Integer.valueOf(parentKey),
				null));
		}

		// Main query for all non-child scenarios, add child lists from the map along the way.

		ArrayList<Scenario> result = new ArrayList<Scenario>();

		db.setDatabase(theDbName);

		db.query(
		"SELECT " +
			"scenario_key, " +
			"name, " +
			"description, " +
			"scenario_type, " +
			"is_permanent " +
		"FROM " +
			"scenario " +
		"WHERE " +
			"parent_scenario_key = 0 " +
		"ORDER BY 1");

		while (db.next()) {

			theKey = Integer.valueOf(db.getInt(1));

			sourceList = sourceListMap.get(theKey);
			if (null == sourceList) {
				continue;
			}

			params = paramsMap.get(theKey);
			if (null == params) {
				params = defaultParameters;
			}

			result.add(new Scenario(
				theKey.intValue(),
				db.getString(2),
				db.getString(3),
				db.getInt(4),
				db.getBoolean(5),
				params,
				sourceList,
				null,
				childMap.get(theKey)));
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve a list of all scenario pairs in a database.

	public static ArrayList<ScenarioPair> getScenarioPairs(DbConnection db, String theDbName) throws SQLException {

		ArrayList<ScenarioPair> result = new ArrayList<ScenarioPair>();

		db.setDatabase(theDbName);

		db.query(
		"SELECT " +
			"name, " +
			"description, " +
			"scenario_key_a, " +
			"source_key_a, " +
			"scenario_key_b, " +
			"source_key_b " +
		"FROM " +
			"scenario_pair " +
		"ORDER BY 1");

		while (db.next()) {
			result.add(new ScenarioPair(
				db.getString(1),
				db.getString(2),
				db.getInt(3),
				db.getInt(4),
				db.getInt(5),
				db.getInt(6)));
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create the scenario tables in a new database.

	public static void createTables(DbConnection db, String theDbName) throws SQLException {

		db.setDatabase(theDbName);

		db.update(
		"CREATE TABLE scenario (" +
			"scenario_key INT NOT NULL PRIMARY KEY, " +
			"name VARCHAR(255) NOT NULL, " +
			"description VARCHAR(10000) NOT NULL, " +
			"scenario_type INT NOT NULL DEFAULT 1, " +
			"is_permanent BOOLEAN NOT NULL, " +
			"parent_scenario_key INT NOT NULL DEFAULT 0" +
		")");

		db.update(
		"CREATE TABLE scenario_source (" +
			"scenario_key INT NOT NULL, " +
			"source_key INT NOT NULL, " +
			"is_desired BOOLEAN NOT NULL, " +
			"is_undesired BOOLEAN NOT NULL, " +
			"is_permanent BOOLEAN NOT NULL, " +
			"PRIMARY KEY (scenario_key, source_key)" +
		")");

		db.update(
		"CREATE TABLE scenario_pair (" +
			"name VARCHAR(255) NOT NULL, " +
			"description VARCHAR(10000) NOT NULL, " +
			"scenario_key_a INT NOT NULL, " +
			"source_key_a INT NOT NULL, " +
			"scenario_key_b INT NOT NULL, " +
			"source_key_b INT NOT NULL" +
		")");
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create all tables and copy contents from another database.

	public static void copyTables(DbConnection db, String theDbName, String fromDbName) throws SQLException {

		createTables(db, theDbName);

		db.update(
		"INSERT INTO scenario (" +
			"scenario_key, " +
			"name, " +
			"description, " +
			"scenario_type, " +
			"is_permanent, " +
			"parent_scenario_key) " +
		"SELECT " +
			"scenario_key, " +
			"name, " +
			"description, " +
			"scenario_type, " +
			"is_permanent, " +
			"parent_scenario_key " +
		"FROM " +
			fromDbName + ".scenario");

		db.update(
		"INSERT INTO scenario_source (" +
			"scenario_key, " +
			"source_key, " +
			"is_desired, " +
			"is_undesired, " +
			"is_permanent) " +
		"SELECT " +
			"scenario_key, " +
			"source_key, " +
			"is_desired, " +
			"is_undesired, " +
			"is_permanent " +
		"FROM " +
			fromDbName + ".scenario_source");

		db.update(
		"INSERT INTO scenario_pair (" +
			"name, " +
			"description, " +
			"scenario_key_a, " +
			"source_key_a, " +
			"scenario_key_b, " +
			"source_key_b) " +
		"SELECT " +
			"name, " +
			"description, " +
			"scenario_key_a, " +
			"source_key_a, " +
			"scenario_key_b, " +
			"source_key_b " +
		"FROM " +
			fromDbName + ".scenario_pair");
	}
}
