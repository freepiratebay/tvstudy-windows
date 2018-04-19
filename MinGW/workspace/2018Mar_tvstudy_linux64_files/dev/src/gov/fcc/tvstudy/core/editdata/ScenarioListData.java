//
//  ScenarioListData.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.editdata;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;

import java.util.*;
import java.text.*;


//=====================================================================================================================
// Data model for a list of scenarios in a study.

public class ScenarioListData implements ListDataChange {

	private StudyEditData study;

	private ArrayList<ScenarioEditData> modelRows;

	private HashSet<Integer> addedKeys;
	private HashSet<Integer> deletedKeys;
	private ArrayList<ScenarioEditData> changedRows;

	private ArrayList<ScenarioPairItem> scenarioPairs;
	private boolean pairsChanged;

	// For ListDataChange implementation.

	private int lastChange;
	private int lastRow;


	//-----------------------------------------------------------------------------------------------------------------

	public ScenarioListData(StudyEditData theStudy) {

		study = theStudy;

		modelRows = new ArrayList<ScenarioEditData>();
		for (Scenario theScenario : study.study.scenarios) {
			modelRows.add(new ScenarioEditData(study, theScenario));
		}

		addedKeys = new HashSet<Integer>();
		deletedKeys = new HashSet<Integer>();
		changedRows = new ArrayList<ScenarioEditData>();

		scenarioPairs = new ArrayList<ScenarioPairItem>();
		ScenarioPairItem theItem;
		for (Scenario.ScenarioPair thePair : study.study.scenarioPairs) {
			theItem = createScenarioPair(thePair.name, thePair.description, thePair.scenarioKeyA, thePair.sourceKeyA,
				thePair.scenarioKeyB, thePair.sourceKeyB);
			if (null != theItem) {
				scenarioPairs.add(theItem);
			} else {
				pairsChanged = true;
			}
		}

		lastChange = ListDataChange.ALL_CHANGE;
		lastRow = 0;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This does not check the permanent flag.  Permanences does not prevent the content of the scenario from being
	// modified, it just makes sure data for the scenario key always continues to exist.  The only change this might
	// try to prevent would be one that changed the permanent flag of an existing scenario; however that should never
	// happen since the permanent flag is immutable.  Note this assumes any sources in the scenario have already been
	// added directly to the study model.

	public boolean addOrReplace(ScenarioEditData theScenario) {

		lastRow = modelRows.indexOf(theScenario);
		if (lastRow >= 0) {

			modelRows.set(lastRow, theScenario);
			lastChange = ListDataChange.UPDATE;

		} else {

			lastRow = modelRows.size();
			modelRows.add(theScenario);
			if (!deletedKeys.remove(theScenario.key)) {
				addedKeys.add(theScenario.key);
			}
			lastChange = ListDataChange.INSERT;
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// When a scenario is deleted, all unlocked sources in that scenario are removed from the study; unlocked sources
	// are never shared so only appear in the one scenario.  A permanent scenario cannot be deleted.  If the deleted
	// scenario is part of any scenario pairings, the pair records are also automatically deleted.

	public boolean remove(int rowIndex) {

		lastChange = ListDataChange.NO_CHANGE;
		lastRow = -1;

		ScenarioEditData theScenario = modelRows.get(rowIndex);
		if (theScenario.isPermanent) {
			return false;
		}

		modelRows.remove(rowIndex);
		if (!addedKeys.remove(theScenario.key)) {
			deletedKeys.add(theScenario.key);
		}

		// Special case for interference scenarios in TV IX check studies.  These are auto-built based on another
		// scenario and are non-editable.  That means the records are always shared, and that may include shared
		// editable records, which is illegal in any other context and not supported by the study model.  Hence
		// sources are not removed from the study in this case.

		if (Scenario.SCENARIO_TYPE_TVIX_INTERFERENCE != theScenario.scenarioType) {
			for (SourceEditData theSource : theScenario.sourceData.getSources()) {
				study.removeSource(theSource);
			}
		}

		lastChange = ListDataChange.DELETE;
		lastRow = rowIndex;

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean remove(int[] rows) {

		lastChange = ListDataChange.NO_CHANGE;
		lastRow = -1;

		if (0 == rows.length) {
			return false;
		}

		if (1 == rows.length) {
			return remove(rows[0]);
		}

		int i, j, m, r;
		for (i = 0; i < rows.length - 1; i++) {
			m = i;
			for (j = i + 1; j < rows.length; j++) {
				if (rows[j] > rows[m]) {
					m = j;
				}
			}
			if (m != i) {
				r = rows[i];
				rows[i] = rows[m];
				rows[m] = r;
			}
		}

		ScenarioEditData theScenario;
		boolean didRemove = false;

		for (i = 0; i < rows.length; i++) {

			theScenario = modelRows.get(rows[i]);
			if (theScenario.isPermanent) {
				continue;
			}

			modelRows.remove(rows[i]);
			if (!addedKeys.remove(theScenario.key)) {
				deletedKeys.add(theScenario.key);
			}

			didRemove = true;

			if (Scenario.SCENARIO_TYPE_TVIX_INTERFERENCE != theScenario.scenarioType) {
				for (SourceEditData theSource : theScenario.sourceData.getSources()) {
					study.removeSource(theSource);
				}
			}
		}

		if (didRemove) {
			lastChange = ListDataChange.ALL_CHANGE;
			lastRow = 0;
		}

		return didRemove;
	}


	//=================================================================================================================
	// Pair items are stored in the database by source key, but it is not the specific source that is paired, it is
	// the conceptual role of that source in the scenario.  The specific source associated with that role may change,
	// which may require updating the pair data.  This class is used to store the original source keys for a pair as
	// well as the unique key identifying the item in the scenario (see SourceListData).  These will be checked and
	// updated as needed in isDataChanged().

	private class ScenarioPairItem {

		private String name;
		private String description;

		private ScenarioEditData scenarioA;
		private int sourceKeyA;
		private int itemKeyA;

		private ScenarioEditData scenarioB;
		private int sourceKeyB;
		private int itemKeyB;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create a scenario pair item, this may fail if any of the scenario or source references don't resolve.

	private ScenarioPairItem createScenarioPair(String theName, String theDescription, int theScenarioKeyA,
			int theSourceKeyA, int theScenarioKeyB, int theSourceKeyB) {

		ScenarioPairItem scenarioItem = new ScenarioPairItem();
		scenarioItem.name = theName;
		scenarioItem.description = theDescription;

		Integer theKey = Integer.valueOf(theScenarioKeyA);
		ScenarioEditData pairScenario = null;
		for (ScenarioEditData theScenario : modelRows) {
			if (theScenario.key.equals(theKey)) {
				pairScenario = theScenario;
				break;
			}
			pairScenario = theScenario.getChildScenario(theKey);
			if (null != pairScenario) {
				break;
			}
		}
		if (null == pairScenario) {
			return null;
		}
		scenarioItem.scenarioA = pairScenario;

		theKey = Integer.valueOf(theScenarioKeyB);
		pairScenario = null;
		for (ScenarioEditData theScenario : modelRows) {
			if (theScenario.key.equals(theKey)) {
				pairScenario = theScenario;
				break;
			}
			pairScenario = theScenario.getChildScenario(theKey);
			if (null != pairScenario) {
				break;
			}
		}
		if (null == pairScenario) {
			return null;
		}
		scenarioItem.scenarioB = pairScenario;

		scenarioItem.sourceKeyA = theSourceKeyA;
		scenarioItem.itemKeyA = scenarioItem.scenarioA.sourceData.getItemKeyForSourceKey(theSourceKeyA);
		if (scenarioItem.itemKeyA < 0) {
			return null;
		}

		scenarioItem.sourceKeyB = theSourceKeyB;
		scenarioItem.itemKeyB = scenarioItem.scenarioB.sourceData.getItemKeyForSourceKey(theSourceKeyB);
		if (scenarioItem.itemKeyB < 0) {
			return null;
		}

		return scenarioItem;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean addScenarioPair(String theName, String theDescription, int theScenarioKeyA, int theSourceKeyA,
			int theScenarioKeyB, int theSourceKeyB) {

		ScenarioPairItem theItem = createScenarioPair(theName, theDescription, theScenarioKeyA, theSourceKeyA,
			theScenarioKeyB, theSourceKeyB);
		if (null == theItem) {
			return false;
		}

		scenarioPairs.add(theItem);
		pairsChanged = true;

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public ScenarioEditData get(int rowIndex) {

		return modelRows.get(rowIndex);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a scenario by name; the name should always be unique within the study.  The test is case-insensitive, see
	// comments in DbCore.checkScenarioName().

	public ScenarioEditData get(String theName) {

		for (ScenarioEditData theScenario : modelRows) {
			if (theScenario.name.equalsIgnoreCase(theName)) {
				return theScenario;
			}
		}

		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public ArrayList<ScenarioEditData> getRows() {

		return new ArrayList<ScenarioEditData>(modelRows);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int indexOf(ScenarioEditData theScenario) {

		return modelRows.indexOf(theScenario);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check individual scenarios for validity.

	public boolean isDataValid() {
		return isDataValid(null);
	}

	public boolean isDataValid(ErrorLogger errors) {

		for (ScenarioEditData theScenario : modelRows) {
			if (!theScenario.isDataValid(errors)) {
				return false;
			}
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isDataChanged() {

		boolean dataChanged = false;

		HashSet<Integer> allKeys = new HashSet<Integer>();
		changedRows.clear();
		for (ScenarioEditData theScenario : modelRows) {
			allKeys.add(theScenario.key);
			theScenario.getChildScenarioKeys(allKeys);
			if (theScenario.isDataChanged() || addedKeys.contains(theScenario.key)) {
				changedRows.add(theScenario);
				dataChanged = true;
			}
		}

		// Check the list of scenario pairs for changes in the source keys, or for scenarios that no longer exist,
		// update or remove as needed.

		Iterator<ScenarioPairItem> it = scenarioPairs.iterator();
		ScenarioPairItem thePair;
		int theKey;

		while (it.hasNext()) {

			thePair = it.next();

			if (!allKeys.contains(thePair.scenarioA.key) || !allKeys.contains(thePair.scenarioB.key)) {
				it.remove();
				pairsChanged = true;
				continue;
			}

			theKey = thePair.scenarioA.sourceData.getSourceKeyForItemKey(thePair.itemKeyA);
			if (theKey < 0) {
				it.remove();
				pairsChanged = true;
				continue;
			}
			if (theKey != thePair.sourceKeyA) {
				thePair.sourceKeyA = theKey;
				pairsChanged = true;
			}

			theKey = thePair.scenarioB.sourceData.getSourceKeyForItemKey(thePair.itemKeyB);
			if (theKey < 0) {
				it.remove();
				pairsChanged = true;
				continue;
			}
			if (theKey != thePair.sourceKeyB) {
				thePair.sourceKeyB = theKey;
				pairsChanged = true;
			}
		}

		if (dataChanged || pairsChanged) {
			return true;
		}

		if (!addedKeys.isEmpty()) {
			return true;
		}
		if (!deletedKeys.isEmpty()) {
			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public HashSet<Integer> getDeletedKeys() {

		return deletedKeys;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public ArrayList<ScenarioEditData> getChangedRows() {

		return changedRows;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean didScenarioPairsChange() {

		return pairsChanged;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void didSave() {

		addedKeys.clear();
		deletedKeys.clear();
		changedRows.clear();
		pairsChanged = false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public ArrayList<Scenario> getScenarios() {

		ArrayList<Scenario> result = new ArrayList<Scenario>();
		for (ScenarioEditData theScenario : modelRows) {
			result.add(theScenario.scenario);
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This is the only way to access pair data, because currently there is no UI for scenario pairings, they are
	// created programmatically.  The only need to access pair data is when saving, see StudyEditData.save().

	public ArrayList<Scenario.ScenarioPair> getScenarioPairs() {

		ArrayList<Scenario.ScenarioPair> thePairs = new ArrayList<Scenario.ScenarioPair>();
		for (ScenarioPairItem thePair : scenarioPairs) {
			thePairs.add(new Scenario.ScenarioPair(thePair.name, thePair.description, thePair.scenarioA.key.intValue(),
				thePair.sourceKeyA, thePair.scenarioB.key.intValue(), thePair.sourceKeyB));
		}

		return thePairs;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int getRowCount() {

		return modelRows.size();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// ListDataChange methods.

	public int getLastChange() {

		return lastChange;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int getLastRowChanged() {

		return lastRow;
	}
}
