//
//  SourceListData.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.editdata;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;

import java.util.*;
import java.io.*;
import java.text.*;


//=====================================================================================================================
// Data model for the source list in a scenario.  This is different than the usual pattern, because although this
// object is stored in a ScenarioEditData object, it is really managed by the parent StudyEditData object rather than
// the scenario model.  The reason is that the SourceEditData objects are shared between scenarios, the StudyEditData
// instance manages the master map of those objects so they can be shared.  This has two editable columns, the flags
// which indicate whether the source is studied as a desired, undesired, both, or neither (allowing inactive records in
// a scenario for reference or later use).  Validity requires at least one desired flag set true.

public class SourceListData implements ListDataChange {

	private StudyEditData study;

	private int scenarioType;

	private ArrayList<SourceItem> modelRows;

	private HashSet<Integer> addedKeys;
	private HashSet<Integer> deletedKeys;

	// For ListDataChange implementation.

	private int lastChange;
	private int lastRow;

	// See SourceItem.

	private int nextItemKey;


	//-----------------------------------------------------------------------------------------------------------------
	// Build the local list of SourceEditData objects by retrieving shared objects from the study model by key.  If
	// any keys are not found in the map, they go in the deletedKeys set.  The inner class SourceItem holds a source
	// key and the related desired and undesired flags, and a reference to the source itself.  That is a parallel class
	// to Scenario.SourceListItem which has only the key and flags.

	public SourceListData(StudyEditData theStudy, Scenario theScenario) {

		doInit(theStudy);

		if (null != theScenario) {

			scenarioType = theScenario.scenarioType;

			SourceItem newItem;

			for (Scenario.SourceListItem theItem : theScenario.sourceList) {
				newItem = new SourceItem(theStudy, theItem);
				if (null == newItem.source) {
					deletedKeys.add(newItem.key);
				} else {
					modelRows.add(newItem);
				}
			}

		} else {
			scenarioType = Scenario.SCENARIO_TYPE_DEFAULT;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This is used when creating a new scenario, see ScenarioEditData, also duplicate() below.

	public SourceListData(StudyEditData theStudy, int theType, ArrayList<Scenario.SourceListItem> theItems) {

		doInit(theStudy);

		scenarioType = theType;

		if (null != theItems) {

			SourceItem newItem;

			for (Scenario.SourceListItem theItem : theItems) {
				newItem = new SourceItem(theStudy, theItem);
				if (null == newItem.source) {
					deletedKeys.add(newItem.key);
				} else {
					modelRows.add(newItem);
					addedKeys.add(newItem.key);
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Constructor common init.

	private void doInit(StudyEditData theStudy) {

		study = theStudy;

		modelRows = new ArrayList<SourceItem>();

		addedKeys = new HashSet<Integer>();
		deletedKeys = new HashSet<Integer>();

		nextItemKey = 1;
	}


	//=================================================================================================================
	// Model row data class, see comments above.  Equality follows the source key, the model cannot have two entries
	// representing the same source.  However these also have an identity key, unique and persistent for the lifetime
	// of the SourceListData object, that allows an item in a scenario to be tracked even if the source changes.  Since
	// these objects are persistent they are private, the Scenario.SourceListItem is the public class, to optimize the
	// accessors these objects also cache an instance of the public class with current properties.

	private class SourceItem {

		private int itemKey;

		private Integer key;
		private SourceEditData source;

		private boolean isDesired;
		private boolean isUndesired;

		private final boolean isPermanent;

		private Scenario.SourceListItem sourceListItem;


		//-------------------------------------------------------------------------------------------------------------

		private SourceItem(StudyEditData theStudy, Scenario.SourceListItem theItem) {

			itemKey = nextItemKey++;

			key = Integer.valueOf(theItem.key);
			source = theStudy.getSource(key);

			isDesired = theItem.isDesired;
			isUndesired = theItem.isUndesired;

			isPermanent = theItem.isPermanent;

			sourceListItem = theItem;
		}


		//-------------------------------------------------------------------------------------------------------------

		private SourceItem(SourceEditData theSource, boolean theDesFlag, boolean theUndFlag) {

			itemKey = nextItemKey++;

			key = theSource.key;
			source = theSource;

			isDesired = theDesFlag;
			isUndesired = theUndFlag;

			isPermanent = false;

			updateSourceListItem();
		}


		//-------------------------------------------------------------------------------------------------------------
		// This constructor form is the only way to set the permanent flag, after construction it is immutable.  It
		// only makes sense to establish permanence at construction, the purpose is to make some of the structure of
		// the study non-editable, although not necessarily the content.

		private SourceItem(SourceEditData theSource, boolean theDesFlag, boolean theUndFlag, boolean thePermFlag) {

			itemKey = nextItemKey++;

			key = theSource.key;
			source = theSource;

			isDesired = theDesFlag;
			isUndesired = theUndFlag;

			isPermanent = thePermFlag;

			updateSourceListItem();
		}


		//-------------------------------------------------------------------------------------------------------------

		private void updateSourceListItem() {

			sourceListItem = new Scenario.SourceListItem(key.intValue(), isDesired, isUndesired, isPermanent);
		}


		//-------------------------------------------------------------------------------------------------------------

		public int hashCode() {

			return key.hashCode();
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean equals(Object other) {

			return (null != other) && ((SourceItem)other).key.equals(key);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Make a deep-copy duplicate of this object for use in a new scenario, see ScenarioEditData.duplicate().  This
	// includes making duplicates of any non-shareable (unlocked) sources and adding those to the study.  That gets
	// complicated for a replication of an unlocked source; the unlocked original has to be duplicated first, then a
	// new replication created based on that duplicate.

	public SourceListData duplicate() {
		return duplicate(null);
	}

	public SourceListData duplicate(ErrorLogger errors) {

		ArrayList<SourceEditData> newSources = new ArrayList<SourceEditData>();
		ArrayList<Scenario.SourceListItem> newItems = new ArrayList<Scenario.SourceListItem>();

		SourceEditData theSource, origSource;
		SourceEditDataTV theSourceTV;
		int repChan;

		for (SourceItem theItem : modelRows) {

			theSource = theItem.source;

			if (!theSource.isLocked) {

				theSource = theSource.deriveSource(false, errors);
				if (null == theSource) {
					return null;
				}
				newSources.add(theSource);

			} else {

				if (Source.RECORD_TYPE_TV == theSource.recordType) {
					theSourceTV = (SourceEditDataTV)theSource;
					if (null != theSourceTV.originalSourceKey) {

						origSource = study.getSource(theSourceTV.originalSourceKey);
						if ((null == origSource) || (Source.RECORD_TYPE_TV != origSource.recordType)) {
							if (null != errors) {
								errors.reportError("An original record needed for replication does not exist.");
							}
							return null;
						}

						if (!origSource.isLocked) {

							repChan = theSourceTV.channel;
							theSourceTV = (SourceEditDataTV)((SourceEditDataTV)origSource).deriveSource(false, errors);
							if (null == theSourceTV) {
								return null;
							}
							newSources.add(theSourceTV);
							theSourceTV = theSourceTV.replicate(repChan, errors);
							if (null == theSourceTV) {
								return null;
							}
							newSources.add(theSourceTV);
							theSource = theSourceTV;
						}
					}
				}
			}

			// The permanent flag is always false for all entries in the duplicate.  Permanence is related to the
			// conceptual role of a given source in the scenario, and in the overall study, and that role does not
			// apply in a duplicate.  The permanent attribute can only be established when creating new entries.

			newItems.add(new Scenario.SourceListItem(theSource.key.intValue(), theItem.isDesired, theItem.isUndesired,
				false));
		}

		for (SourceEditData newSource : newSources) {
			study.addOrReplaceSource(newSource);
		}

		// The duplicated scenario will always be the default type, special types can't be created as duplicates.

		return new SourceListData(study, Scenario.SCENARIO_TYPE_DEFAULT, newItems);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Two sources and thus two list items with the same key are the same.  To preserve the persistent item key the
	// item objects are updated in place if there is already an item for the source key.  If the permanent flag is true
	// on an existing item the flags cannot change.

	public boolean addOrReplace(SourceEditData theSource, boolean theDesFlag, boolean theUndFlag) {
		return addOrReplace(new SourceItem(theSource, theDesFlag, theUndFlag, false));
	}

	public boolean addOrReplace(SourceEditData theSource, boolean theDesFlag, boolean theUndFlag, boolean thePermFlag) {
		return addOrReplace(new SourceItem(theSource, theDesFlag, theUndFlag, thePermFlag));
	}

	private boolean addOrReplace(SourceItem theItem) {

		lastRow = modelRows.indexOf(theItem);
		if (lastRow >= 0) {

			SourceItem oldItem = modelRows.get(lastRow);
			oldItem.source = theItem.source;
			if (!oldItem.isPermanent) {
				oldItem.isDesired = theItem.isDesired;
				oldItem.isUndesired = theItem.isUndesired;
				oldItem.updateSourceListItem();
			}
			lastChange = ListDataChange.UPDATE;

		} else {

			lastRow = modelRows.size();
			modelRows.add(theItem);
			if (!deletedKeys.remove(theItem.key)) {
				addedKeys.add(theItem.key);
			}
			lastChange = ListDataChange.INSERT;
		}

		study.addOrReplaceSource(theItem.source);

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// A permanent item cannot be removed.

	public boolean remove(int rowIndex) {

		lastChange = ListDataChange.NO_CHANGE;
		lastRow = -1;

		SourceItem theItem = modelRows.get(rowIndex);
		if (theItem.isPermanent) {
			return false;
		}

		modelRows.remove(rowIndex);
		if (!addedKeys.remove(theItem.key)) {
			deletedKeys.add(theItem.key);
		}

		// Special rule for interference scenarios in TV IX check studies.  The sources in those scenarios are always
		// shared, even if they are editable, because the scenarios are auto-built from other scenarios.  Hence the
		// source does not need to be removed from the study (and must not be if editable, since the study doesn't
		// support sharing editable sources and would assume those are scenario-specific).

		if (Scenario.SCENARIO_TYPE_TVIX_INTERFERENCE != scenarioType) {
			study.removeSource(theItem.source);
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

		SourceItem theItem;
		boolean didRemove = false;

		for (i = 0; i < rows.length; i++) {

			theItem = modelRows.get(rows[i]);
			if (theItem.isPermanent) {
				continue;
			}

			modelRows.remove(rows[i]);
			if (!addedKeys.remove(theItem.key)) {
				deletedKeys.add(theItem.key);
			}

			if (Scenario.SCENARIO_TYPE_TVIX_INTERFERENCE != scenarioType) {
				study.removeSource(theItem.source);
			}

			didRemove = true;
		}

		if (didRemove) {
			lastChange = ListDataChange.ALL_CHANGE;
			lastRow = 0;
		}

		return didRemove;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This does not affect the flags, just changes the source at the row.  It may be a different object with the same
	// key, or an object with a different key.  The permanent flag has no effect here; the source can be changed on a
	// permanent entry.  It is the entry's conceptual role that is permanent, not the specific record in that role.

	public boolean set(int rowIndex, SourceEditData newSource) {

		SourceItem theItem = modelRows.get(rowIndex);

		if (theItem.key.equals(newSource.key)) {

			theItem.source = newSource;

			study.addOrReplaceSource(newSource);

		} else {

			SourceEditData oldSource = theItem.source;

			theItem.key = newSource.key;
			theItem.source = newSource;
			theItem.updateSourceListItem();

			if (!addedKeys.remove(oldSource.key)) {
				deletedKeys.add(oldSource.key);
			}
			if (!deletedKeys.remove(newSource.key)) {
				addedKeys.add(newSource.key);
			}

			// When the source is actually changing, the new source is added before the old is removed so the study
			// model will know not to delete the old entirely if the new is a replication of the old.

			study.addOrReplaceSource(newSource);
			if (Scenario.SCENARIO_TYPE_TVIX_INTERFERENCE != scenarioType) {
				study.removeSource(oldSource);
			}
		}

		lastChange = ListDataChange.UPDATE;
		lastRow = rowIndex;

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Desired and undesired flags cannot be changed on a permanent entry.  Desired cannot be set on wireless records.

	public boolean setIsDesired(int rowIndex, boolean flag) {

		lastChange = ListDataChange.NO_CHANGE;
		lastRow = -1;

		SourceItem theItem = modelRows.get(rowIndex);
		if (theItem.isPermanent || (Source.RECORD_TYPE_WL == theItem.source.recordType)) {
			return false;
		}

		theItem.isDesired = flag;
		theItem.updateSourceListItem();

		lastChange = ListDataChange.UPDATE;
		lastRow = rowIndex;

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean setIsUndesired(int rowIndex, boolean flag) {

		lastChange = ListDataChange.NO_CHANGE;
		lastRow = -1;

		SourceItem theItem = modelRows.get(rowIndex);
		if (theItem.isPermanent) {
			return false;
		}

		theItem.isUndesired = flag;
		theItem.updateSourceListItem();

		lastChange = ListDataChange.UPDATE;
		lastRow = rowIndex;

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int indexOfSourceKey(Integer theKey) {

		for (int rowIndex = 0; rowIndex < modelRows.size(); rowIndex++) {
			if (modelRows.get(rowIndex).key.equals(theKey)) {
				return rowIndex;
			}
		}
		return -1;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public Scenario.SourceListItem get(int rowIndex) {

		return modelRows.get(rowIndex).sourceListItem;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public SourceEditData getSource(int rowIndex) {

		return modelRows.get(rowIndex).source;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// See SourceItem comments above.

	public int getItemKeyForSourceKey(int theKey) {

		for (SourceItem theItem : modelRows) {
			if (theKey == theItem.key.intValue()) {
				return theItem.itemKey;
			}
		}

		return -1;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int getSourceKeyForItemKey(int theKey) {

		for (SourceItem theItem : modelRows) {
			if (theKey == theItem.itemKey) {
				return theItem.key.intValue();
			}
		}

		return -1;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public ArrayList<Scenario.SourceListItem> getRows() {

		ArrayList<Scenario.SourceListItem> result = new ArrayList<Scenario.SourceListItem>(modelRows.size());

		for (SourceItem theItem : modelRows) {
			result.add(theItem.sourceListItem);
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get sources, optionally filtered by record type.

	public ArrayList<SourceEditData> getSources() {
		return getSources(0);
	}

	public ArrayList<SourceEditData> getSources(int recordType) {

		ArrayList<SourceEditData> result = new ArrayList<SourceEditData>();

		for (SourceItem theItem : modelRows) {
			if ((0 == recordType) || (theItem.source.recordType == recordType)) {
				result.add(theItem.source);
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Return a desired source if and only if there is exactly one such source, otherwise return null.  Optionally
	// filtered by record type.

	public SourceEditData getDesiredSource() {
		return getDesiredSource(0);
	}

	public SourceEditData getDesiredSource(int recordType) {

		SourceEditData result = null;

		for (SourceItem theItem : modelRows) {
			if (theItem.isDesired && ((0 == recordType) || (theItem.source.recordType == recordType))) {
				if (null != result) {
					result = null;
					break;
				}
				result = theItem.source;
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get sources flagged as desireds, optionally filtered by record type.

	public ArrayList<SourceEditData> getDesiredSources() {
		return getDesiredSources(0);
	}

	public ArrayList<SourceEditData> getDesiredSources(int recordType) {

		ArrayList<SourceEditData> result = new ArrayList<SourceEditData>();

		for (SourceItem theItem : modelRows) {
			if (theItem.isDesired && ((0 == recordType) || (theItem.source.recordType == recordType))) {
				result.add(theItem.source);
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check for desired sources, optionally filtered by record type.

	public boolean hasDesiredSources() {
		return hasDesiredSources(0);
	}

	public boolean hasDesiredSources(int recordType) {

		for (SourceItem theItem : modelRows) {
			if (theItem.isDesired && ((0 == recordType) || (theItem.source.recordType == recordType))) {
				return true;
			}
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int getDesiredSourceCount() {

		int theCount = 0;

		for (SourceItem theItem : modelRows) {
			if (theItem.isDesired) {
				theCount++;
			}
		}

		return theCount;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Pattern as above for undesired sources.

	public ArrayList<SourceEditData> getUndesiredSources() {
		return getUndesiredSources(0);
	}

	public ArrayList<SourceEditData> getUndesiredSources(int recordType) {

		ArrayList<SourceEditData> result = new ArrayList<SourceEditData>();

		for (SourceItem theItem : modelRows) {
			if (theItem.isUndesired && ((0 == recordType) || (theItem.source.recordType == recordType))) {
				result.add(theItem.source);
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean hasUndesiredSources() {
		return hasUndesiredSources(0);
	}

	public boolean hasUndesiredSources(int recordType) {

		for (SourceItem theItem : modelRows) {
			if (theItem.isUndesired && ((0 == recordType) || (theItem.source.recordType == recordType))) {
				return true;
			}
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int getUndesiredSourceCount() {

		int theCount = 0;

		for (SourceItem theItem : modelRows) {
			if (theItem.isUndesired) {
				theCount++;
			}
		}

		return theCount;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Individual source objects are not checked for validity, see disucussion in StudyEditor.isDataValid().

	public boolean isDataValid() {
		return isDataValid(null);
	}

	public boolean isDataValid(ErrorLogger errors) {

		if (modelRows.isEmpty()) {
			if (null != errors) {
				errors.reportValidationError("At least one station must be added to every scenario.");
			}
			return false;
		}

		for (SourceItem theItem : modelRows) {
			if (theItem.isDesired) {
				return true;
			}
		}
		if (null != errors) {
			errors.reportValidationError("At least one desired station must be in every scenario.");
		}
		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This does not call isDataChanged() on the source objects, the study model will do that directly to avoid
	// repeatedly polling sources that are shared between scenarios.

	public boolean isDataChanged() {

		if (!addedKeys.isEmpty()) {
			return true;
		}

		if (!deletedKeys.isEmpty()) {
			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Test only for sources added or removed, currently the same as a full data-changed test.  See ScenarioEditData.

	public boolean isScenarioChanged() {

		return isDataChanged();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void didSave() {

		addedKeys.clear();
		deletedKeys.clear();
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


	//-----------------------------------------------------------------------------------------------------------------
	// Write all source data to XML.  Some data may still be written even if something is invalid or an error occurs,
	// see SourceEditData.writeToXML() for details.  The permanent flag is not exported, that is conceptually dependent
	// on the role of the source within a specific scenario and is not relevent when importing to a different context.

	public boolean writeSourcesToXML(Writer xml) throws IOException {
		return writeSourcesToXML(xml, null);
	}

	public boolean writeSourcesToXML(Writer xml, ErrorLogger errors) throws IOException {

		for (SourceItem theItem : modelRows) {
			if (!theItem.source.writeToXML(xml, theItem.isDesired, theItem.isUndesired, errors)) {
				return false;
			}
		}

		return true;
	}
}
