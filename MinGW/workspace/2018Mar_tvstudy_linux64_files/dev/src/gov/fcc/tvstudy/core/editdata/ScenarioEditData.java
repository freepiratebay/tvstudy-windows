//
//  ScenarioEditData.java
//  TVStudy
//
//  Copyright (c) 2012-2018 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.editdata;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.geo.*;

import java.util.*;
import java.io.*;
import java.sql.*;


//=====================================================================================================================
// Mutable data model for a scenario, see ScenarioEditor and ScenarioListData.  Note this keeps the mutable source
// list in the form of a SourceListData object; see that class for details on how the source objects are obtained.

public class ScenarioEditData {

	public final StudyEditData study;

	public Scenario scenario;

	public final Integer key;

	public String name;
	public String description;

	public final int scenarioType;
	public final boolean isPermanent;

	public ArrayList<ParameterEditData> parameters;
	private HashMap<Integer, ParameterEditData> parameterMap;

	public final SourceListData sourceData;

	public final Integer parentScenarioKey;
	private HashMap<Integer, ScenarioEditData> childScenarios;
	private boolean childScenariosChanged;


	//-----------------------------------------------------------------------------------------------------------------
	// Create an object for an existing scenario.  The scenario must not be null.

	public ScenarioEditData(StudyEditData theStudy, Scenario theScenario) {

		super();

		study = theStudy;
		scenario = theScenario;

		key = Integer.valueOf(theScenario.key);

		name = theScenario.name;
		description = theScenario.description;

		scenarioType = theScenario.scenarioType;
		isPermanent = theScenario.isPermanent;

		doParameterSetup(theScenario.parameters);

		sourceData = new SourceListData(theStudy, theScenario);

		parentScenarioKey = theScenario.parentScenarioKey;
		if (null != theScenario.childScenarios) {
			childScenarios = new HashMap<Integer, ScenarioEditData>(theScenario.childScenarios.size());
			for (Scenario theChild : theScenario.childScenarios) {
				childScenarios.put(Integer.valueOf(theChild.key), new ScenarioEditData(theStudy, theChild));
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create an object for new scenario.  In all new-object constructor forms, the name must always be provided and
	// will be checked for uniqueness, if needed a suffix is added.  See DbCore.checkScenarioName().

	public ScenarioEditData(StudyEditData theStudy, String theName) {

		super();

		study = theStudy;
		scenario = null;

		key = theStudy.getNewScenarioKey();

		if (null != theStudy.scenarioData.get(theName)) {
			theName = theName + " " + DbCore.NAME_UNIQUE_CHAR + String.valueOf(key);
		}
		name = theName;
		description = "";

		scenarioType = Scenario.SCENARIO_TYPE_DEFAULT;
		isPermanent = false;

		doParameterSetup(theStudy.study.scenarioParameters);

		sourceData = new SourceListData(theStudy, (Scenario)null);

		parentScenarioKey = null;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This constructor creates a new scenario from a source item list.

	public ScenarioEditData(StudyEditData theStudy, String theName, String theDescription,
			ArrayList<Scenario.SourceListItem> theItems) {

		super();

		study = theStudy;
		scenario = null;

		key = theStudy.getNewScenarioKey();

		if (null != theStudy.scenarioData.get(theName)) {
			theName = theName + " " + DbCore.NAME_UNIQUE_CHAR + String.valueOf(key);
		}
		name = theName;
		description = theDescription;

		scenarioType = Scenario.SCENARIO_TYPE_DEFAULT;
		isPermanent = false;

		doParameterSetup(theStudy.study.scenarioParameters);

		sourceData = new SourceListData(theStudy, scenarioType, theItems);

		parentScenarioKey = null;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// As above but allow the type and permanent flag to be set.  Those can only be established when a new object is
	// created.  The purpose of the permanent concept is to make a portion of the structure of the study permanently
	// non-editable, so it doesn't make sense to be able to change that flag later.

	public ScenarioEditData(StudyEditData theStudy, String theName, String theDescription, boolean thePermFlag,
			ArrayList<Scenario.SourceListItem> theItems) {

		study = theStudy;
		scenario = null;

		key = theStudy.getNewScenarioKey();

		if (null != theStudy.scenarioData.get(theName)) {
			theName = theName + " " + DbCore.NAME_UNIQUE_CHAR + String.valueOf(key);
		}
		name = theName;
		description = theDescription;

		scenarioType = Scenario.SCENARIO_TYPE_DEFAULT;
		isPermanent = thePermFlag;

		doParameterSetup(theStudy.study.scenarioParameters);

		sourceData = new SourceListData(theStudy, scenarioType, theItems);

		parentScenarioKey = null;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public ScenarioEditData(StudyEditData theStudy, String theName, String theDescription, int theType,
			boolean thePermFlag, ArrayList<Scenario.SourceListItem> theItems) {

		study = theStudy;
		scenario = null;

		key = theStudy.getNewScenarioKey();

		if (null != theStudy.scenarioData.get(theName)) {
			theName = theName + " " + DbCore.NAME_UNIQUE_CHAR + String.valueOf(key);
		}
		name = theName;
		description = theDescription;

		scenarioType = theType;
		isPermanent = thePermFlag;

		doParameterSetup(theStudy.study.scenarioParameters);

		sourceData = new SourceListData(theStudy, scenarioType, theItems);

		parentScenarioKey = null;
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doParameterSetup(ArrayList<Parameter> theParameters) {

		if (null != theParameters) {

			parameters = new ArrayList<ParameterEditData>();
			parameterMap = new HashMap<Integer, ParameterEditData>();

			ParameterEditData theEditParam;
			for (Parameter theParameter : theParameters) {
				theEditParam = new ParameterEditData(theParameter, study.study.templateLocked);
				parameters.add(theEditParam);
				parameterMap.put(Integer.valueOf(theParameter.key), theEditParam);
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Constructor used by duplicate() and to create child scenarios, just set final properties.

	private ScenarioEditData(StudyEditData theStudy, Integer theKey, int theType, boolean thePermFlag,
			ArrayList<ParameterEditData> theParameters, SourceListData theSourceData, Integer theParentKey) {

		super();

		study = theStudy;
		key = theKey;
		scenarioType = theType;
		isPermanent = thePermFlag;
		parameters = theParameters;
		sourceData = theSourceData;
		parentScenarioKey = theParentKey;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create a new scenario with most properties copied from the current, but with a new name and key.  This does a
	// deep copy of the source model, which may include copying some non-shareable source objects as well.  Note the
	// permanent flag is always false on a duplicate, see discussion on the constructor above that can set the flag.
	// Also the type always reverts to default, special scenario types must always be newly-created.  Likewise child
	// scenarios are not duplicated.

	public ScenarioEditData duplicate(String newName) {
		return duplicate(newName, null);
	}

	public ScenarioEditData duplicate(String newName, ErrorLogger errors) {

		ArrayList<ParameterEditData> newParameters = null;
		if (null != parameters) {
			newParameters = new ArrayList<ParameterEditData>();
			for (ParameterEditData theParameter : parameters) {
				newParameters.add(theParameter.copy());
			}
		}

		SourceListData newSourceData = sourceData.duplicate(errors);
		if (null == newSourceData) {
			return null;
		}

		ScenarioEditData newScenario = new ScenarioEditData(study, study.getNewScenarioKey(),
			Scenario.SCENARIO_TYPE_DEFAULT, false, newParameters, newSourceData, null);

		newScenario.name = newName;
		newScenario.description = description;

		return newScenario;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int hashCode() {

		return key.hashCode();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean equals(Object other) {

		return (null != other) && ((ScenarioEditData)other).key.equals(key);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String toString() {

		return name;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add records to the scenario using a station data search based on an ExtDbSearch object.  This is mostly a
	// convenience wrapper for the record-type-specific methods in the ExtDbRecord and SourceEditData subclasses, see
	// there for details.  This returns the count of records added, 0 is valid, -1 means an error occurred.  The
	// useBaseline flag is only meaningful for some TV data set types, the argument is ignored in other cases.

	public int addRecords(ExtDb extDb, boolean useBaseline, ExtDbSearch search) {
		return addRecords(extDb, useBaseline, search, null);
	}

	public int addRecords(ExtDb extDb, boolean useBaseline, ExtDbSearch search, ErrorLogger errors) {

		String query = search.makeQuery(extDb, useBaseline, errors);
		if (null == query) {
			return -1;
		}

		if (extDb.isGeneric()) {

			switch (extDb.recordType) {
				case Source.RECORD_TYPE_TV: {
					return SourceEditDataTV.addRecords(extDb, this, search.searchType, query, search.center,
						search.radius, search.minimumChannel, search.maximumChannel, search.disableMX,
						search.mxFacilityIDOnly, !search.desiredOnly, errors);
				}
				case Source.RECORD_TYPE_WL: {
					return SourceEditDataWL.addRecords(extDb, this, search.searchType, query, search.center,
						search.radius, errors);
				}
				case Source.RECORD_TYPE_FM: {
					return SourceEditDataFM.addRecords(extDb, this, search.searchType, query, search.center,
						search.radius, search.minimumChannel, search.maximumChannel, search.disableMX,
						search.mxFacilityIDOnly, !search.desiredOnly, errors);
				}
			}

		} else {

			switch (extDb.recordType) {
				case Source.RECORD_TYPE_TV: {
					return ExtDbRecordTV.addRecords(extDb, useBaseline, this, search.searchType, query, search.center,
						search.radius, search.minimumChannel, search.maximumChannel, search.disableMX,
						search.mxFacilityIDOnly, search.preferOperating, !search.desiredOnly, errors);
				}
				case Source.RECORD_TYPE_WL: {
					return ExtDbRecordWL.addRecords(extDb, this, search.searchType, query, search.center,
						search.radius, errors);
				}
				case Source.RECORD_TYPE_FM: {
					return ExtDbRecordFM.addRecords(extDb, this, search.searchType, query, search.center,
						search.radius, search.minimumChannel, search.maximumChannel, search.disableMX,
						search.mxFacilityIDOnly, search.preferOperating, !search.desiredOnly, errors);
				}
			}
		}

		return 0;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create a child scenario dependent on this scenario.  The child list can only be modified in limited ways; new
	// scenarios can be added, and all existing child scenarios can be removed.  These do not appear in the UI and are
	// never edited directly, they are built programatically based on the parent.  Note the name is not checked for
	// uniqueness; the code generating these scenarios must make sure name collisions do not occur.  This scenario
	// must not have a parent; only top-level scenarios can have child scenarios.

	public ScenarioEditData addChildScenario(String theName, String theDescription, int theType,
			ArrayList<Scenario.SourceListItem> theItems) {

		if (null != parentScenarioKey) {
			return null;
		}

		Integer newKey = study.getNewScenarioKey();
		ScenarioEditData newScenario = new ScenarioEditData(study, newKey, theType, false, null,
			new SourceListData(study, theType, theItems), key);

		if (null == childScenarios) {
			childScenarios = new HashMap<Integer, ScenarioEditData>();
		}
		childScenarios.put(newKey, newScenario);
		childScenariosChanged = true;

		newScenario.name = theName;
		newScenario.description = theDescription;

		newScenario.doParameterSetup(study.study.scenarioParameters);

		return newScenario;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public ScenarioEditData getChildScenario(Integer theKey) {

		if (null != childScenarios) {
			return childScenarios.get(theKey);
		}
		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void removeAllChildScenarios() {

		if (null != childScenarios) {
			childScenarios = null;
			childScenariosChanged = true;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean didChildScenariosChange() {

		return childScenariosChanged;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public HashSet<Integer> getChildScenarioKeys(HashSet<Integer> theKeys) {

		if (null != childScenarios) {
			if (null == theKeys) {
				theKeys = new HashSet<Integer>(childScenarios.keySet());
			} else {
				theKeys.addAll(childScenarios.keySet());
			}
		}
		return theKeys;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public ArrayList<ScenarioEditData> getChildScenarios() {

		if (null != childScenarios) {
			return new ArrayList<ScenarioEditData>(childScenarios.values());
		}
		return new ArrayList<ScenarioEditData>();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int getChildScenarioCount() {

		if (null != childScenarios) {
			return childScenarios.size();
		}
		return 0;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isDataValid() {
		return isDataValid(null);
	}

	public boolean isDataValid(ErrorLogger errors) {

		if (!sourceData.isDataValid(errors)) {
			return false;
		}

		if (0 == name.length()) {
			if (null != errors) {
				errors.reportValidationError("A name must be entered for every scenario.");
			}
			return false;
		}

		if (null != childScenarios) {
			for (ScenarioEditData theChild : childScenarios.values()) {
				if (!theChild.isDataValid(errors)) {
					return false;
				}
			}
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The source model does not have the backing Scenario object to check for changes in the study flags, so that is
	// done here if the source model reports no other changes.  The permanent flag is not checked, it is immutable.
	// Permanence can only be established when an entry is first created, and cannot be revoked.

	public boolean isDataChanged() {

		if (null == scenario) {
			return true;
		}

		if (!name.equals(scenario.name)) {
			return true;
		}
		if (!description.equals(scenario.description)) {
			return true;
		}

		if (null != parameters) {
			for (ParameterEditData theParam : parameters) {
				if (theParam.isDataChanged()) {
					return true;
				}
			}
		}

		if (sourceData.isDataChanged()) {
			return true;
		}

		int count = scenario.sourceList.size();
		if (sourceData.getRowCount() != count) {
			return true;
		}
		Scenario.SourceListItem oldItem, newItem;
		for (int i = 0; i < count; i++) {
			oldItem = scenario.sourceList.get(i);
			newItem = sourceData.get(i);
			if ((newItem.isDesired != oldItem.isDesired) || (newItem.isUndesired != oldItem.isUndesired)) {
				return true;
			}
		}

		// Individual child scenarios are never edited directly, see addChildScenario().

		if (childScenariosChanged) {
			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Test if the scenario has changed in any way that means child scenarios need to be rebuilt.  This is true if the
	// scenario has never been saved, if child scenarios have never been built, if sources have been added or removed,
	// or if flags have been changed.

	public boolean isScenarioChanged() {

		if ((null == scenario) || (null == childScenarios)) {
			return true;
		}

		if (sourceData.isScenarioChanged()) {
			return true;
		}

		int count = scenario.sourceList.size();
		if (sourceData.getRowCount() != count) {
			return true;
		}
		Scenario.SourceListItem oldItem, newItem;
		for (int i = 0; i < count; i++) {
			oldItem = scenario.sourceList.get(i);
			newItem = sourceData.get(i);
			if ((newItem.isDesired != oldItem.isDesired) || (newItem.isUndesired != oldItem.isUndesired)) {
				return true;
			}
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save this scenario, called from StudyEditData.save().  The list of source keys and flags is removed and re-
	// inserted entirely, likewise the set of scenario parameter values (if any).  If there are changes to the child
	// scenario list, delete all existing child scenarios as well.  The study save code will be calling save on the
	// current child scenarios after this.

	public void save(DbConnection db) throws SQLException {

		if (null != scenario) {

			db.update("DELETE FROM scenario WHERE scenario_key = " + key);
			db.update("DELETE FROM scenario_source WHERE scenario_key = " + key);
			db.update("DELETE FROM scenario_parameter_data WHERE scenario_key = " + key);

			if (childScenariosChanged) {
				db.update("DELETE FROM scenario_source WHERE scenario_key IN " +
					"(SELECT scenario_key FROM scenario WHERE parent_scenario_key = " + key + ")");
				db.update("DELETE FROM scenario_parameter_data WHERE scenario_key IN " +
					"(SELECT scenario_key FROM scenario WHERE parent_scenario_key = " + key + ")");
				db.update("DELETE FROM scenario WHERE parent_scenario_key = " + key);
			}
		}

		db.update(
		"INSERT INTO scenario (" +
			"scenario_key, " +
			"name, " +
			"description, " +
			"scenario_type, " +
			"is_permanent, " +
			"parent_scenario_key) " +
		"VALUES (" +
			key + "," +
			"'" + db.clean(name) + "'," +
			"'" + db.clean(description) + "'," +
			scenarioType + "," +
			isPermanent + "," +
			((null == parentScenarioKey) ? "0" : String.valueOf(parentScenarioKey)) + ")");

		StringBuilder query = new StringBuilder(
		"INSERT INTO scenario_source (" +
			"scenario_key, " +
			"source_key, " +
			"is_desired, " +
			"is_undesired, " +
			"is_permanent) " +
		"VALUES");
		int startLength = query.length();
		String sep = " (";
		for (Scenario.SourceListItem theItem : sourceData.getRows()) {
			query.append(sep);
			query.append(String.valueOf(key));
			query.append(',');
			query.append(String.valueOf(theItem.key));
			query.append(',');
			query.append(String.valueOf(theItem.isDesired));
			query.append(',');
			query.append(String.valueOf(theItem.isUndesired));
			query.append(',');
			query.append(String.valueOf(theItem.isPermanent));
			if (query.length() > DbCore.MAX_QUERY_LENGTH) {
				query.append(')');
				db.update(query.toString());
				query.setLength(startLength);
				sep = " (";
			} else {
				sep = "),(";
			}
		}
		if (query.length() > startLength) {
			query.append(')');
			db.update(query.toString());
		}

		if ((null != parameters) && !parameters.isEmpty()) {
			query = new StringBuilder(
			"INSERT INTO scenario_parameter_data (" +
				"scenario_key," +
				"parameter_key," +
				"value_index," +
				"value) " +
			"VALUES");
			startLength = query.length();
			sep = " (";
			for (ParameterEditData theParameter : parameters) {
				for (int valueIndex = 0; valueIndex < theParameter.parameter.valueCount; valueIndex++) {
					query.append(sep);
					query.append(String.valueOf(key));
					query.append(',');
					query.append(String.valueOf(theParameter.parameter.key));
					query.append(',');
					query.append(String.valueOf(valueIndex));
					query.append(",'");
					query.append(db.clean(theParameter.value[valueIndex]));
					query.append('\'');
					if (query.length() > DbCore.MAX_QUERY_LENGTH) {
						query.append(')');
						db.update(query.toString());
						query.setLength(startLength);
						sep = " (";
					} else {
						sep = "),(";
					}
				}
			}
			if (query.length() > startLength) {
				query.append(')');
				db.update(query.toString());
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void didSave() {

		ArrayList<Parameter> theParams = null;
		if (null != parameters) {
			theParams = new ArrayList<Parameter>();
			for (ParameterEditData theParam : parameters) {
				theParam.didSave();
				theParams.add(theParam.parameter);
			}
		}

		ArrayList<Scenario> theChildScenarios = null;
		if (null != childScenarios) {
			theChildScenarios = new ArrayList<Scenario>();
			for (ScenarioEditData theChild : childScenarios.values()) {
				theChild.didSave();
				theChildScenarios.add(theChild.scenario);
			}
		}
		childScenariosChanged = false;

		sourceData.didSave();

		scenario = new Scenario(key.intValue(), name, description, scenarioType, isPermanent, theParams,
			sourceData.getRows(), parentScenarioKey, theChildScenarios);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Encode scenario data as XML.  Check validity first.  The permanent flag is not part of the export.  That is
	// conceptually dependent on the role of the scenario within a specific study, and so would not be relevent when
	// importing to a different study.  Also parameter values are not part of the export, those always reset to
	// defaults for the study to which the scenario is being imported.  Child scenarios are also not exported.

	public boolean writeToXML(Writer xml) throws IOException {
		return writeToXML(xml, null);
	}

	public boolean writeToXML(Writer xml, ErrorLogger errors) throws IOException {

		if (!isDataValid(errors)) {
			return false;
		}

		xml.append("<SCENARIO NAME=\"" + AppCore.xmlclean(name) + "\">\n");
		xml.append("<DESCRIPTION>" + AppCore.xmlclean(description) + "</DESCRIPTION>\n");

		if (null != parameters) {
			int valueIndex;
			for (ParameterEditData theParam : parameters) {
				xml.append("<PARAMETER KEY=\"" + theParam.parameter.key + "\">\n");
				for (valueIndex = 0; valueIndex < theParam.parameter.valueCount; valueIndex++) {
					if (null != theParam.value[valueIndex]) {
						xml.append("<VALUE INDEX=\"" + valueIndex + "\">" +
							AppCore.xmlclean(theParam.value[valueIndex]) + "</VALUE>\n");
					}
				}
				xml.append("</PARAMETER>\n");
			}
		}

		if (!sourceData.writeSourcesToXML(xml, errors)) {
			return false;
		}

		xml.append("</SCENARIO>\n");

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve parameter by key.

	public ParameterEditData getParameter(int theKey) {

		return parameterMap.get(Integer.valueOf(theKey));
	}
}
