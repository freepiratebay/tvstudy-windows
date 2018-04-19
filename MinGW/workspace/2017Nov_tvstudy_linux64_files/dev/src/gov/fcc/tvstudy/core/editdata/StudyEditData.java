//
//  StudyEditData.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.editdata;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.geo.*;

import java.util.*;
import java.util.regex.*;
import java.sql.*;
import java.io.*;

import javax.xml.parsers.*;

import org.xml.sax.*;
import org.xml.sax.helpers.*;


//=====================================================================================================================
// Mutable data class to support the study editor.  This is also the top of the mutable data object hierarchy for the
// entire editor state, so it also has a method to save changed data back to the database.  See StudyManager and
// StudyEditor for use; however this is also able to exist and to operate outside of a UI, so there is no direct
// relationship to UI classes.

public class StudyEditData {

	// The study being edited.

	public Study study;

	// Database ID.

	public final String dbID;

	// Source keys are not a simple sequence, see getNewSourceKey().

	private int newSourceKey;
	private boolean[] sourceKeyMap;

	// Mutable properties, study name, description, mode, points set, area mode and geography, and output settings.

	public String name;
	public String description;

	public int studyMode;
	public int pointSetKey;
	public int propagationModel;
	public int studyAreaMode;
	public int studyAreaGeoKey;

	public OutputConfig fileOutputConfig;
	public OutputConfig mapOutputConfig;

	// Parameters.

	public final ArrayList<ParameterEditData> parameters;
	private HashMap<Integer, ParameterEditData> parameterMap;

	// Interference rules.

	public final IxRuleListData ixRuleData;

	// Primary map of all source data objects.

	private HashMap<Integer, SourceEditData> sources;

	private HashSet<Integer> addedSourceKeys;
	private HashSet<Integer> deletedSourceKeys;
	private ArrayList<SourceEditData> changedSources;

	// Indices for sources, see SourceIndex, addOrReplaceSource(), etc.

	private ArrayList<Integer> replicationOriginalKeys;

	private static final Integer USER_RECORD_INDEX_KEY = Integer.valueOf(-1);
	private SourceIndex userRecordIndex;

	private HashMap<Integer, SourceIndex> extDbsIndex;

	// Scenarios.

	public final ScenarioListData scenarioData;

	// See invalidate().

	private boolean invalidated;


	//-----------------------------------------------------------------------------------------------------------------
	// New studies can only be created by Study.createNewStudy() which creates the study record directly in the
	// database first, so an instance of this class will never exist without a backing database record object.

	public StudyEditData(Study theStudy) {

		study = theStudy;
		dbID = study.dbID;

		// See getNewSourceKey() for details.  

		sourceKeyMap = new boolean[65536];
		updateSourceKeyMap();

		// Copy mutable properties.

		name = study.name;
		description = study.description;

		studyMode = study.studyMode;
		pointSetKey = study.pointSetKey;
		propagationModel = study.propagationModel;
		studyAreaMode = study.studyAreaMode;
		studyAreaGeoKey = study.studyAreaGeoKey;

		fileOutputConfig = OutputConfig.getOrMakeConfig(dbID, OutputConfig.CONFIG_TYPE_FILE,
			study.fileOutputConfigName, study.fileOutputConfigCodes);
		mapOutputConfig = OutputConfig.getOrMakeConfig(dbID, OutputConfig.CONFIG_TYPE_MAP, study.mapOutputConfigName,
			study.mapOutputConfigCodes);

		// Create the parameters.  Also kept in a map for random-access, some specific parameters have named value
		// accessors for use by other code, see e.g. getKilometersPerDegree().

		parameters = new ArrayList<ParameterEditData>();
		parameterMap = new HashMap<Integer, ParameterEditData>();

		ParameterEditData theEditParam;
		for (Parameter theParameter : study.parameters) {
			theEditParam = new ParameterEditData(theParameter, study.templateLocked);
			parameters.add(theEditParam);
			parameterMap.put(Integer.valueOf(theParameter.key), theEditParam);
		}

		// Create the interference rule data model.

		ixRuleData = new IxRuleListData(study.ixRules, study.templateLocked);

		// Create objects to display/edit all sources, shared by scenario editors.  These all go in a lookup map keyed
		// by the source key, see addOrReplaceSource() and getSource().

		sources = new HashMap<Integer, SourceEditData>();
		SourceEditData newSource;
		for (Source theSource : study.sources) {
			newSource = SourceEditData.getInstance(this, theSource);
			sources.put(newSource.key, newSource);
		}

		addedSourceKeys = new HashSet<Integer>();
		deletedSourceKeys = new HashSet<Integer>();
		changedSources = new ArrayList<SourceEditData>();

		// Index data, see addOrReplaceSource(), rebuildSourceIndex().

		replicationOriginalKeys = new ArrayList<Integer>();

		userRecordIndex = new SourceIndex(USER_RECORD_INDEX_KEY);

		extDbsIndex = new HashMap<Integer, SourceIndex>();

		rebuildSourceIndex();

		// Create the scenario data manager.

		scenarioData = new ScenarioListData(this);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String toString() {

		return name;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Every model object in the graph holds a reference back to this one, including transient objects, so the risk of
	// lingering references is high.  Because the conceptual relationship between a lock flag in the root database and
	// a specific instance of this class is fundamental, this needs a definitive means to make the instance unable to
	// commit further changes to the database when the lock has been cleared.  This cannot be un-done.  It does not
	// prevent the object from continuing to function as an editor model; it will just prevent any database save.

	public synchronized void invalidate() {

		invalidated = true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Provide primary key values for new interference rule, scenario, and source objects.  This is based on the
	// assumption that the study record in the database currently has a persistent lock set, and that will remain set
	// as long as this object exists; see Study.getStudy().  Since no other object or application instance will modify
	// the study database with the lock set, the new keys assigned here are guaranteed unique.  Interference rule and
	// scenario keys are 32-bit integers throughout the database and applications, so those are assigned sequentially
	// and never re-used.  The range of those should never be exhausted by any reasonable conditions, if somehow it
	// happens, an exception will be thrown.

	public synchronized Integer getNewIxRuleKey() {

		if (++(study.maxIxRuleKey) <= 0) {
			study.maxIxRuleKey = -1;
			throw new RuntimeException("Interference rule key range exhausted");
		}
		return Integer.valueOf(study.maxIxRuleKey);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public synchronized Integer getNewScenarioKey() {

		if (++(study.maxScenarioKey) <= 0) {
			study.maxScenarioKey = -1;
			throw new RuntimeException("Scenario key range exhausted");
		}
		return Integer.valueOf(study.maxScenarioKey);
	}


	//-----------------------------------------------------------------------------------------------------------------
  	// Source keys are a bit more complicated.  To manage memory use, the study engine limits source keys to unsigned
	// 16-bit integer range, so the sequential range could be exhausted under reasonable usage patterns that create
	// and delete a large number of sources (for example, see StudyRunPair).  Assuming removeAllUnusedSources() is
	// used appropriately, there may be available keys within the range and those keys can safely be re-used.  The
	// only persistent external references are in the study engine cache which has very thorough checks to detect when
	// cache data doesn't really belong to a source record should the key for a different source inadvertently match.
	// So source key assignment uses a map that tracks keys in use to allow non-sequential key assignment.  If no key
	// is available return null, the caller must always deal with that case.  Once a key is returned it is no longer
	// available, even if the caller never actually uses it.  Also when sources are removed their keys remain in use
	// according to the map.  During any editing session, the assumption that two source objects with the same key
	// represent the same database record is fundamental, so keys must never be re-used in a session.  Immediately
	// after a save the key map is rebuilt so only keys actually in use by database records are flagged, that will
	// make deleted and unused keys available for re-use.

	public synchronized Integer getNewSourceKey() {

		int lastKey = newSourceKey;
		do {
			if (++newSourceKey > 65535) {
				newSourceKey = 1;
			}
		} while (sourceKeyMap[newSourceKey] && (newSourceKey != lastKey));

		if (newSourceKey == lastKey) {
			return null;
		}

		sourceKeyMap[newSourceKey] = true;
		return Integer.valueOf(newSourceKey);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update the source key map to match the current primary source objects.  This must only be called when the
	// database and in-memory states are identical, meaning at construction or immediately after a save.

	private void updateSourceKeyMap() {

		sourceKeyMap[0] = true;
		for (int i = 1; i < 65536; i++) {
			sourceKeyMap[i] = false;
		}

		newSourceKey = 0;
		SourceTV theSourceTV;
		for (Source theSource : study.sources) {
			if (theSource.key > newSourceKey) {
				newSourceKey = theSource.key;
			}
			sourceKeyMap[theSource.key] = true;
			if (Source.RECORD_TYPE_TV == theSource.recordType) {
				theSourceTV = (SourceTV)theSource;
				if (theSourceTV.isParent) {
					for (SourceTV dtsSource : theSourceTV.dtsSources) {
						if (dtsSource.key > newSourceKey) {
							newSourceKey = dtsSource.key;
						}
						sourceKeyMap[dtsSource.key] = true;
					}
				}
			}
		}
	}




	//=================================================================================================================
	// Class used to maintain a source index for a particular database key.  Sources from external station data that
	// are non-editable can be shared between scenarios, an index object is used to track shareable sources by data key
	// and record ID.  If the key is set to USER_RECORD_INDEX_KEY, that is a special case used to track user-entered
	// sources identified by the user record ID.

	private class SourceIndex {

		private Integer dbKey;

		private HashMap<String, SourceEditData> sharedSources;
		private ArrayList<HashMap<String, SourceEditDataTV>> sharedReplicationSources;


		//-------------------------------------------------------------------------------------------------------------

		private SourceIndex(Integer theKey) {

			dbKey = theKey;

			sharedSources = new HashMap<String, SourceEditData>();

			int maxChans = (SourceTV.CHANNEL_MAX - SourceTV.CHANNEL_MIN) + 1;
			sharedReplicationSources = new ArrayList<HashMap<String, SourceEditDataTV>>(maxChans);
			for (int i = 0; i < maxChans; i++) {
				sharedReplicationSources.add(new HashMap<String, SourceEditDataTV>());
			}
		}


		//-------------------------------------------------------------------------------------------------------------
		// This will not add a source that cannot be shared, or does not belong in this index.  Note a replication
		// TV source cannot be shared unless the original source it replicates is also shareable.

		private void addOrReplace(SourceEditData theSource) {

			if (!theSource.isLocked) {
				return;
			}

			String theID = null;
			if (dbKey.equals(USER_RECORD_INDEX_KEY)) {
				theID = String.valueOf(theSource.userRecordID);
			} else {
				if (dbKey.equals(theSource.extDbKey)) {
					theID = theSource.extRecordID;
				}
			}
			if (null == theID) {
				return;
			}

			if (Source.RECORD_TYPE_TV != theSource.recordType) {
				sharedSources.put(theID, theSource);
			} else {
				SourceEditDataTV theSourceTV = (SourceEditDataTV)theSource;
				if (null == theSourceTV.originalSourceKey) {
					sharedSources.put(theID, theSource);
				} else {
					SourceEditData origSource = sources.get(theSourceTV.originalSourceKey);
					if ((null != origSource) && origSource.isLocked) {
						sharedReplicationSources.get(theSourceTV.channel - SourceTV.CHANNEL_MIN).put(theID, theSourceTV);
					}
				}
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		private boolean contains(SourceEditData theSource) {

			if (!theSource.isLocked) {
				return false;
			}

			String theID = null;
			if (dbKey.equals(USER_RECORD_INDEX_KEY)) {
				theID = String.valueOf(theSource.userRecordID);
			} else {
				if (dbKey.equals(theSource.extDbKey)) {
					theID = theSource.extRecordID;
				}
			}
			if (null == theID) {
				return false;
			}

			if (Source.RECORD_TYPE_TV != theSource.recordType) {
				return sharedSources.containsKey(theID);
			} else {
				SourceEditDataTV theSourceTV = (SourceEditDataTV)theSource;
				if (null == theSourceTV.originalSourceKey) {
					return sharedSources.containsKey(theID);
				} else {
					return sharedReplicationSources.get(theSourceTV.channel - SourceTV.CHANNEL_MIN).containsKey(theID);
				}
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		private void clear() {

			sharedSources.clear();
			for (HashMap<String, SourceEditDataTV> theMap : sharedReplicationSources) {
				theMap.clear();
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		private SourceEditData getSource(String theID) {

			return sharedSources.get(theID);
		}


		//-------------------------------------------------------------------------------------------------------------

		private SourceEditDataTV getReplicationSource(String theID, int channel) {

			if ((channel < SourceTV.CHANNEL_MIN) || (channel > SourceTV.CHANNEL_MAX)) {
				return null;
			}
			return sharedReplicationSources.get(channel - SourceTV.CHANNEL_MIN).get(theID);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This API functions to support table model behavior for source lists in scenario editors, but maintaining a
	// common backing store of shared objects.  See SourceListData.  This will replace an existing object assuming
	// the new one has been modified, and if not it makes no difference as two source objects with the same key are
	// interchangeable.  A source can be shared between scenarios if it is locked and is based on a user record or an
	// external station data record, see SourceIndex.  If the source is a TV replication, the original must also be
	// locked for the source to be shareable.  Shared replication sources are keyed by primary ID and channel.  The
	// keys for sources that are originals for replication are tracked separately to prevent inadvertent deletes, that
	// is done with a list not a set because there may be multiple replications from the same original.  Note an
	// original must always be added to the model first before any replication based on it, otherwise behavior may not
	// be correct.  This now allows sources from mulitple external data sets to be mixed in one study, shared source
	// indices are maintained for each data key seen.

	public synchronized void addOrReplaceSource(SourceEditData theSource) {

		if (null == sources.put(theSource.key, theSource)) {
			if (!deletedSourceKeys.remove(theSource.key)) {
				addedSourceKeys.add(theSource.key);
			}
			if (Source.RECORD_TYPE_TV == theSource.recordType) {
				SourceEditDataTV theSourceTV = (SourceEditDataTV)theSource;
				if (null != theSourceTV.originalSourceKey) {
					replicationOriginalKeys.add(theSourceTV.originalSourceKey);
				}
			}
		}

		if (theSource.isLocked) {

			if (null != theSource.userRecordID) {

				userRecordIndex.addOrReplace(theSource);

			} else {

				if ((null != theSource.extDbKey) && (null != theSource.extRecordID)) {

					SourceIndex theIndex = extDbsIndex.get(theSource.extDbKey);
					if (null == theIndex) {
						theIndex = new SourceIndex(theSource.extDbKey);
						extDbsIndex.put(theSource.extDbKey, theIndex);
					}

					theIndex.addOrReplace(theSource);
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a source edit object by key.

	public synchronized SourceEditData getSource(Integer theKey) {

		return sources.get(theKey);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Find an existing shareable source matching a user record ID.

	public synchronized SourceEditData findSharedSource(Integer userRecordID) {

		if (null == userRecordID) {
			return null;
		}

		return userRecordIndex.getSource(String.valueOf(userRecordID));
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Find a shareable TV replication source by user record ID and replication channel.

	public synchronized SourceEditDataTV findSharedReplicationSource(Integer userRecordID, int channel) {

		if (null == userRecordID) {
			return null;
		}

		return userRecordIndex.getReplicationSource(String.valueOf(userRecordID), channel);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Find an existing shareable source matching a station data key and record ID.

	public synchronized SourceEditData findSharedSource(Integer extDbKey, String extRecordID) {

		if ((null == extDbKey) || (null == extRecordID)) {
			return null;
		}

		SourceIndex theIndex = extDbsIndex.get(extDbKey);
		if (null == theIndex) {
			return null;
		}

		return theIndex.getSource(extRecordID);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Find a shareable TV replication source by station data key, record ID, and replication channel.

	public synchronized SourceEditDataTV findSharedReplicationSource(Integer extDbKey, String extRecordID,
			int channel) {

		if ((null == extDbKey) || (null == extRecordID)) {
			return null;
		}

		SourceIndex theIndex = extDbsIndex.get(extDbKey);
		if (null == theIndex) {
			return null;
		}

		return theIndex.getReplicationSource(extRecordID, channel);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Given an external data set key, populate maps with existing sources in the study model for that data set, if
	// any.  Used during XML import, see ParseXML.  Even if the data set has no sources currently in the study, the
	// shared replication maps are still initialized with empty objects.  First clear any existing map content.

	public synchronized void loadSharedSourceIndex(int theKey, HashMap<String, SourceEditData> theSources,
			ArrayList<HashMap<String, SourceEditDataTV>> theReplicationSources) {

		theSources.clear();
		theReplicationSources.clear();

		SourceIndex theIndex = extDbsIndex.get(theKey);

		if (null != theIndex) {

			theSources.putAll(theIndex.sharedSources);

			for (HashMap<String, SourceEditDataTV> channelMap : theIndex.sharedReplicationSources) {
				theReplicationSources.add(new HashMap<String, SourceEditDataTV>(channelMap));
			}

		} else {

			int maxChans = (SourceTV.CHANNEL_MAX - SourceTV.CHANNEL_MIN) + 1;
			for (int i = 0; i < maxChans; i++) {
				theReplicationSources.add(new HashMap<String, SourceEditDataTV>());
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// A source that can be shared between scenarios is not actually removed, even if it is no longer in any scenario
	// it stays in the model for possible future re-use, preserving any calculation results that may have been cached
	// by the study engine.  Also sources that are originals for TV replication sources must not be removed.

	public synchronized void removeSource(SourceEditData theSource) {

		SourceIndex theIndex = null;

		if (null != theSource.userRecordID) {
			theIndex = userRecordIndex;
		} else {
			if (null != theSource.extDbKey) {
				theIndex = extDbsIndex.get(theSource.extDbKey);
			}
		}

		if ((null != theIndex) && theIndex.contains(theSource)) {
			return;
		}

		if (replicationOriginalKeys.contains(theSource.key)) {
			return;
		}

		sources.remove(theSource.key);
		if (!addedSourceKeys.remove(theSource.key)) {
			deletedSourceKeys.add(theSource.key);
		}

		// If the source just removed was a replication, remove one reference to the original.  Then check the original
		// to see if it should also be removed because it was being preserved only to support the source just removed.

		if (Source.RECORD_TYPE_TV == theSource.recordType) {
			SourceEditDataTV theSourceTV = (SourceEditDataTV)theSource;
			if (null != theSourceTV.originalSourceKey) {

				replicationOriginalKeys.remove(theSourceTV.originalSourceKey);

				SourceEditData origSource = sources.get(theSourceTV.originalSourceKey);
				if (null != origSource) {

					if ((null != theIndex) && theIndex.contains(origSource)) {
						return;
					}

					if (replicationOriginalKeys.contains(origSource.key)) {
						return;
					}

					sources.remove(origSource.key);
					if (!addedSourceKeys.remove(origSource.key)) {
						deletedSourceKeys.add(origSource.key);
					}
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Delete all sources that have no references (they do not appear in any scenario and are not needed as originals
	// for TV replication sources that appear in any scenario).  Shareable sources stay in the study even when removed
	// from all scenarios, see removeSource() above.  But over time that may lead to running out of source keys, see
	// getNewSourceKey().  Also this is a more precise means of clearing cache; only individual cache files for deleted
	// sources will be deleted, at the next save.  This does not depend on the current shared source index state, it
	// scans scenarios directly and then rebuilds the index.

	public synchronized void removeAllUnusedSources() {

		HashSet<Integer> sourceKeysInUse = new HashSet<Integer>();
		SourceEditDataTV theSourceTV;
		for (ScenarioEditData theScenario : scenarioData.getRows()) {
			for (SourceEditData theSource : theScenario.sourceData.getSources()) {
				sourceKeysInUse.add(theSource.key);
				if (Source.RECORD_TYPE_TV == theSource.recordType) {
					theSourceTV = (SourceEditDataTV)theSource;
					if (null != theSourceTV.originalSourceKey) {
						sourceKeysInUse.add(theSourceTV.originalSourceKey);
					}
				}
			}
		}

		Iterator<SourceEditData> it = sources.values().iterator();
		SourceEditData theSource;
		while (it.hasNext()) {
			theSource = it.next();
			if (!sourceKeysInUse.contains(theSource.key)) {
				it.remove();
				if (!addedSourceKeys.remove(theSource.key)) {
					deletedSourceKeys.add(theSource.key);
				}
			}
		}

		rebuildSourceIndex();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Companion to removeAllUnusedSources(), count how many sources would be removed by that method.

	public synchronized int getUnusedSourceCount() {

		int result = 0;

		HashSet<Integer> sourceKeysInUse = new HashSet<Integer>();
		SourceEditDataTV theSourceTV;
		for (ScenarioEditData theScenario : scenarioData.getRows()) {
			for (SourceEditData theSource : theScenario.sourceData.getSources()) {
				sourceKeysInUse.add(theSource.key);
				if (Source.RECORD_TYPE_TV == theSource.recordType) {
					theSourceTV = (SourceEditDataTV)theSource;
					if (null != theSourceTV.originalSourceKey) {
						sourceKeysInUse.add(theSourceTV.originalSourceKey);
					}
				}
			}
		}

		for (SourceEditData theSource : sources.values()) {
			if (!sourceKeysInUse.contains(theSource.key)) {
				result++;
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Rebuild the source indexing, including the list of replication originals and the shareable source maps.

	private void rebuildSourceIndex() {

		replicationOriginalKeys.clear();

		userRecordIndex.clear();

		extDbsIndex.clear();

		Integer theKey;
		SourceIndex theIndex;
		SourceEditDataTV theSourceTV;

		for (SourceEditData theSource : sources.values()) {

			if (Source.RECORD_TYPE_TV == theSource.recordType) {
				theSourceTV = (SourceEditDataTV)theSource;
				if (null != theSourceTV.originalSourceKey) {
					replicationOriginalKeys.add(theSourceTV.originalSourceKey);
				}
			}

			if (theSource.isLocked) {

				if (null != theSource.userRecordID) {

					userRecordIndex.addOrReplace(theSource);

				} else {

					if ((null != theSource.extDbKey) && (null != theSource.extRecordID)) {

						theIndex = extDbsIndex.get(theSource.extDbKey);
						if (null == theIndex) {
							theIndex = new SourceIndex(theSource.extDbKey);
							extDbsIndex.put(theSource.extDbKey, theIndex);
						}

						theIndex.addOrReplace(theSource);
					}
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Determine if a geography is in use by the as-edited study.

	public boolean isGeographyInUse(int theGeoKey) {

		if ((Study.STUDY_MODE_POINTS == studyMode) && (theGeoKey == pointSetKey)) {
			return true;
		}

		if ((Study.STUDY_AREA_GEOGRAPHY == studyAreaMode) && (theGeoKey == studyAreaGeoKey)) {
			return true;
		}

		for (SourceEditData theSource : sources.values()) {
			if (theSource.isGeographyInUse(theGeoKey)) {
				return true;
			}
		}

		return false;
	};


	//-----------------------------------------------------------------------------------------------------------------
	// Check validity for the model.  The source objects are not checked here or in the scenario models.  They are
	// assumed initially valid, and when new sources are added or existing ones edited, the scenario and source editors
	// will check validity at the point of entry and will not allow invalid data to be set in the model.  Likewise
	// with interference rules and parameters.  However there are also validity checks that apply to an entire scenario
	// and to the entire set of interference rules, so the table models have validity check methods.

	public boolean isDataValid() {
		return isDataValid(null);
	}

	public boolean isDataValid(ErrorLogger errors) {

		if (invalidated) {
			if (null != errors) {
				errors.reportError("Object has been invalidated.");
			}
			return false;
		}

		// Check for valid combination of study mode properties.

		if ((Study.STUDY_MODE_GRID != studyMode) && (Study.STUDY_MODE_POINTS != studyMode)) {
			if (null != errors) {
				errors.reportError("Invalid study mode.");
			}
			return false;
		}
		if (Study.STUDY_MODE_GRID == studyMode) {
			if ((Study.STUDY_AREA_SERVICE != studyAreaMode) && (Study.STUDY_AREA_GEOGRAPHY != studyAreaMode) &&
					(Study.STUDY_AREA_NO_BOUNDS != studyAreaMode)) {
				if (null != errors) {
					errors.reportError("Invalid study area mode.");
				}
				return false;
			}
			if (Study.STUDY_AREA_GEOGRAPHY == studyAreaMode) {
				if (studyAreaGeoKey <= 0) {
					if (null != errors) {
						errors.reportError("A study area geography must be selected.");
					}
					return false;
				}
			}
		} else {
			if (pointSetKey <= 0) {
				if (null != errors) {
					errors.reportError("A study point set must be selected.");
				}
				return false;
			}
		}

		if (!ixRuleData.isDataValid(errors)) {
			return false;
		}

		if (!scenarioData.isDataValid(errors)) {
			return false;
		}

		if (!fileOutputConfig.isValid()) {
			if (null != errors) {
				errors.reportError("Invalid output file settings.");
			}
			return false;
		}

		if (!mapOutputConfig.isValid()) {
			if (null != errors) {
				errors.reportError("Invalid map output settings.");
			}
			return false;
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check for data changes and prepare for a save.  Must be called immediately before save(); if this returns false
	// there is no need to call save().

	public boolean isDataChanged() {

		if (invalidated) {
			return false;
		}

		// The table model isDataChanged() methods have necessary side-effects, so all must always be called.  Likewise
		// all source objects must be checked, to build a list of all that have changes needing to be saved.

		boolean dataChanged = false;

		if (ixRuleData.isDataChanged()) {
			dataChanged = true;
		}

		if (scenarioData.isDataChanged()) {
			dataChanged = true;
		}

		// Build list of all changed or new sources.  See addOrReplaceSource() and removeSource().

		changedSources.clear();
		for (SourceEditData theSource : sources.values()) {
			if (theSource.isDataChanged() || addedSourceKeys.contains(theSource.key)) {
				changedSources.add(theSource);
				dataChanged = true;
			}
		}

		if (dataChanged) {
			return true;
		}

		if (!deletedSourceKeys.isEmpty()) {
			return true;
		}

		for (ParameterEditData theParam : parameters) {
			if (theParam.isDataChanged()) {
				return true;
			}
		}

		if (!name.equals(study.name)) {
			return true;
		}
		if (!description.equals(study.description)) {
			return true;
		}
		if (studyMode != study.studyMode) {
			return true;
		}
		if ((Study.STUDY_MODE_POINTS == studyMode) && (pointSetKey != study.pointSetKey)) {
			return true;
		}
		if (propagationModel != study.propagationModel) {
			return true;
		}
		if (studyAreaMode != study.studyAreaMode) {
			return true;
		}
		if ((Study.STUDY_AREA_GEOGRAPHY == studyAreaMode) && (studyAreaGeoKey != study.studyAreaGeoKey)) {
			return true;
		}
		if (fileOutputConfig.isNull()) {
			if (study.fileOutputConfigName.length() > 0) {
				return true;
			}
		} else {
			if (!fileOutputConfig.name.equals(study.fileOutputConfigName)) {
				return true;
			}
			if (fileOutputConfig.isUnsaved() && !fileOutputConfig.getCodes().equals(study.fileOutputConfigCodes)) {
				return true;
			}
		}
		if (mapOutputConfig.isNull()) {
			if (study.mapOutputConfigName.length() > 0) {
				return true;
			}
		} else {
			if (!mapOutputConfig.name.equals(study.mapOutputConfigName)) {
				return true;
			}
			if (mapOutputConfig.isUnsaved() && !mapOutputConfig.getCodes().equals(study.mapOutputConfigCodes)) {
				return true;
			}
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This method saves as-edited data to the database, and commits changes throughout the model if the save succeeds.
	// Note this assumes isDataChanged() was called immediately prior and returned true, with no chance for intervening
	// model changes!  The report preamble is optional, if it is not provided any existing text in the database is not
	// changed.  Saves made through the editing UI do not set or alter that text, however saves done programmatically
	// when some special study types are being created may set that text, e.g. see StudyBuildIxCheck.  When present in
	// the study table, that text is included in output reports from the study engine.

	public boolean save() {
		return save(null, null);
	}

	public boolean save(ErrorLogger errors) {
		return save(errors, null);
	}

	public boolean save(String reportPreamble) {
		return save(null, reportPreamble);
	}

	public synchronized boolean save(ErrorLogger errors, String reportPreamble) {

		if (invalidated) {
			if (null != errors) {
				errors.reportError("Study save failed, object has been invalidated.");
			}
			return false;
		}

		String rootName = DbCore.getDbName(dbID);

		DbConnection db = DbCore.connectDb(dbID, errors);
		if (null != db) {
			try {

				// Confirm the study still exists and is properly locked.

				boolean error = false;
				String errmsg = "";

				db.query("SELECT study_lock, lock_count FROM study WHERE study_key = " + study.key);
				if (db.next()) {
					if ((Study.LOCK_EDIT != db.getInt(1)) || (study.lockCount != db.getInt(2))) {
						error = true;
						errmsg = "Study save failed, lock has been modified.";
					}
				} else {
					error = true;
					errmsg = "Study save failed, study does not exist.";
				}

				if (error) {
					DbCore.releaseDb(db);
					if (null != errors) {
						errors.reportError(errmsg);
					}
					return false;
				}

				db.setDatabase(rootName + "_" + study.key);

				// Save parameter changes.  Changes to parameters will trigger an update on all sources.  First delete
				// any old value(s), then insert the new.  That is generally the approach to saving anything here;
				// delete and insert rather than update.  In this case it avoids a lot of hassle when the number of
				// values for a particular parameter changes.

				boolean updateAll = false;

				int valueIndex;

				for (ParameterEditData theParameter : parameters) {

					if (theParameter.isDataChanged()) {

						db.update("DELETE FROM parameter_data WHERE parameter_key = " + theParameter.parameter.key);

						for (valueIndex = 0; valueIndex < theParameter.parameter.valueCount; valueIndex++) {
							db.update(
							"INSERT INTO parameter_data ( " +
								"parameter_key," +
								"value_index," +
								"value) " +
							"VALUES (" +
								theParameter.parameter.key + "," +
								valueIndex + "," +
								"'" + db.clean(theParameter.value[valueIndex]) + "')");
						}

						theParameter.didSave();

						updateAll = true;
					}
				}

				// Save changes to interference rules.  Start by removing deleted rules.  Any change to rules will
				// change the mod count on the study record so all caches are invalidated; that might not always be
				// necessary depending on the specific change, but it's too complicated to figure out when it isn't.

				int modCount = study.modCount;

				HashSet<Integer> delKeys = ixRuleData.getDeletedKeys();

				if (!delKeys.isEmpty()) {

					db.update("DELETE FROM ix_rule WHERE ix_rule_key IN " + makeKeyList(delKeys));

					modCount = study.modCount + 1;
				}

				// Save new or modified rules, if modified this begins with a delete of the existing rule.  Then in
				// any case a new record is inserted.  If this is a new rule the key should not exist, that assumes
				// the study lock guarantees there are no concurrent modifications to the database so new keys assigned
				// with getNewIxRuleKey() cannot exist.  Since the tables do not have enforced referential integrity
				// all changes can be saved this way, as a delete-insert.

				for (IxRuleEditData theRule : ixRuleData.getChangedRows()) {

					if (null != theRule.ixRule) {
						db.update("DELETE FROM ix_rule WHERE ix_rule_key = " + theRule.key);
					}

					db.update("INSERT INTO ix_rule (" +
						"ix_rule_key," +
						"country_key," +
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
						"undesired_time," +
						"is_active) " +
					"VALUES (" +
						theRule.key + "," +
						theRule.country.key + "," +
						theRule.serviceType.key + "," +
						theRule.signalType.key + "," +
						theRule.undesiredServiceType.key + "," +
						theRule.undesiredSignalType.key + "," +
						theRule.channelDelta.key + "," +
						theRule.channelBand.key + "," +
						theRule.frequencyOffset + "," +
						theRule.emissionMask.key + "," +
						theRule.distance + "," +
						theRule.requiredDU + "," +
						theRule.undesiredTime + "," +
						theRule.isActive + ")");

					theRule.didSave();

					modCount = study.modCount + 1;
				}

				ixRuleData.didSave();

				// Save changes to scenarios.  First remove any deleted scenarios.  Child scenarios complicate this,
				// do a separate query for scenarios that are children to the deleted scenarios, and delete those too.

				delKeys = scenarioData.getDeletedKeys();
				String keyList;

				if (!delKeys.isEmpty()) {

					keyList = makeKeyList(delKeys);
					db.update("DELETE FROM scenario WHERE scenario_key IN " + keyList);
					db.update("DELETE FROM scenario_source WHERE scenario_key IN " + keyList);
					db.update("DELETE FROM scenario_parameter_data WHERE scenario_key IN " + keyList);

					delKeys = new HashSet<Integer>();
					db.query("SELECT scenario_key FROM scenario WHERE parent_scenario_key IN " + keyList);
					while (db.next()) {
						delKeys.add(Integer.valueOf(db.getInt(1)));
					}

					if (!delKeys.isEmpty()) {
						keyList = makeKeyList(delKeys);
						db.update("DELETE FROM scenario WHERE scenario_key IN " + keyList);
						db.update("DELETE FROM scenario_source WHERE scenario_key IN " + keyList);
						db.update("DELETE FROM scenario_parameter_data WHERE scenario_key IN " + keyList);
					}
				}

				// Save changed scenarios.

				for (ScenarioEditData theScenario : scenarioData.getChangedRows()) {
					theScenario.save(db);
					if (theScenario.didChildScenariosChange()) {
						for (ScenarioEditData childScenario : theScenario.getChildScenarios()) {
							childScenario.save(db);
						}
					}
					theScenario.didSave();
				}

				// Save scenario pairs if changed, this also is removed and re-inserted entirely.

				StringBuilder query;
				int startLength;
				String sep;

				ArrayList<Scenario.ScenarioPair> scenarioPairs = scenarioData.getScenarioPairs();

				if (scenarioData.didScenarioPairsChange()) {

					db.update("DELETE FROM scenario_pair");

					query = new StringBuilder(
					"INSERT INTO scenario_pair (" +
						"name, " +
						"description, " +
						"scenario_key_a, " +
						"source_key_a, " +
						"scenario_key_b, " +
						"source_key_b) " +
					"VALUES");
					startLength = query.length();
					sep = " (";
					for (Scenario.ScenarioPair thePair : scenarioPairs) {
						query.append(sep);
						query.append('\'');
						query.append(db.clean(thePair.name));
						query.append("','");
						query.append(db.clean(thePair.description));
						query.append("',");
						query.append(String.valueOf(thePair.scenarioKeyA));
						query.append(',');
						query.append(String.valueOf(thePair.sourceKeyA));
						query.append(',');
						query.append(String.valueOf(thePair.scenarioKeyB));
						query.append(',');
						query.append(String.valueOf(thePair.sourceKeyB));
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
				}

				scenarioData.didSave();

				// Save source changes, first delete sources.  A bit complicated to deal with possible DTS records in
				// the delete, have to query for additional keys for DTS sub-records since only the parent keys appear
				// directly in the delete list.  Also delete associated source-specific geographies.  Note this does
				// not have to acquire the global geography edit lock to use the delete method in Geography.  The
				// study edit lock implicitly extends to geography records that are private to this study since those
				// are not visible in any editor context other than the study editor currently holding the study lock.

				if (!deletedSourceKeys.isEmpty()) {

					keyList = makeKeyList(deletedSourceKeys);
					boolean didAdd = false;
					db.query("SELECT source_key FROM source WHERE parent_source_key IN " + keyList);
					while (db.next()) {
						deletedSourceKeys.add(Integer.valueOf(db.getInt(1)));
						didAdd = true;
					}
					if (didAdd) {
						keyList = makeKeyList(deletedSourceKeys);
					}

					db.update("DELETE FROM source_horizontal_pattern WHERE source_key IN " + keyList);
					db.update("DELETE FROM source_vertical_pattern WHERE source_key IN " + keyList);
					db.update("DELETE FROM source_matrix_pattern WHERE source_key IN " + keyList);
					db.update("DELETE FROM source WHERE source_key IN " + keyList);

					Geography.deleteStudyGeographies(db, rootName, study.key, keyList);
				}

				// Save changed and new source records.  The update flag is always set on save.

				for (SourceEditData theSource : changedSources) {
					theSource.save(db);
				}

				addedSourceKeys.clear();
				deletedSourceKeys.clear();
				changedSources.clear();

				// If study mode, point set, propagation model, or area mode change, set update all flag and increment
				// the study record mod count.  Note the point set and area geography keys are ignored if the related
				// mode is not points or area, but the property values are not changed.

				int thePointSetKey = 0;
				if (Study.STUDY_MODE_POINTS == studyMode) {
					thePointSetKey = pointSetKey;
				}
				int theStudyAreaGeoKey = 0;
				if (Study.STUDY_AREA_GEOGRAPHY == studyAreaMode) {
					theStudyAreaGeoKey = studyAreaGeoKey;
				}

				if ((studyMode != study.studyMode) || (thePointSetKey != study.pointSetKey) ||
						(propagationModel != study.propagationModel) || (studyAreaMode != study.studyAreaMode) ||
						(theStudyAreaGeoKey != study.studyAreaGeoKey)) {
					updateAll = true;
					modCount = study.modCount + 1;
				}

				// If an all-sources update is needed, set all source record update flags.

				if (updateAll) {
					db.update("UPDATE source SET needs_update = true");
				}

				// Save list of geography keys in use.

				HashSet<Integer> geoKeys = new HashSet<Integer>();
				if (thePointSetKey > 0) {
					geoKeys.add(Integer.valueOf(thePointSetKey));
				}
				if (theStudyAreaGeoKey > 0) {
					geoKeys.add(Integer.valueOf(theStudyAreaGeoKey));
				}
				int theGeoKey;
				for (SourceEditData theSource : sources.values()) {
					theGeoKey = theSource.getGeographyKey();
					if (theGeoKey > 0) {
						geoKeys.add(Integer.valueOf(theGeoKey));
					}
				}

				db.setDatabase(rootName);

				db.update("LOCK TABLES study_geography WRITE");
				db.update("DELETE FROM study_geography WHERE study_key = " + study.key);

				if (!geoKeys.isEmpty()) {
					query = new StringBuilder("INSERT INTO study_geography (study_key, geo_key) VALUES");
					startLength = query.length();
					sep = " (";
					for (Integer theKey : geoKeys) {
						query.append(sep);
						query.append(String.valueOf(study.key));
						query.append(',');
						query.append(String.valueOf(theKey));
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
				}

				db.update("UNLOCK TABLES");

				// Save the study name, if changed.  Do a concurrent-safe uniqueness check here, although uniqueness
				// was checked when the new name was entered in the UI, other names may have changed since then.

				if (!name.equals(study.name)) {
					db.update("LOCK TABLES study WRITE");
					db.query("SELECT study_key FROM study WHERE UPPER(name) = '" + db.clean(name.toUpperCase()) +
						"' AND study_key <> " + study.key);
					if (db.next()) {
						errors.logMessage("Name change was not saved, a study with the new name already exists.");
						name = study.name;
					} else {
						db.update("UPDATE study SET name = '" + db.clean(name) + "' WHERE study_key = " + study.key);
					}
					db.update("UNLOCK TABLES");
				}

				// Finally, save the description, report preamble (optional, see above), output settings, and parameter
				// and rules summaries, then update the study data object to the final as-saved state.

				ArrayList<Parameter> theParameters = new ArrayList<Parameter>();
				for (ParameterEditData theParameter : parameters) {
					theParameters.add(theParameter.parameter);
				}

				ArrayList<IxRule> theRules = ixRuleData.getRules();

				ArrayList<Source> theSources = new ArrayList<Source>();
				for (SourceEditData theSource : sources.values()) {
					theSources.add(theSource.getSource());
				}

				ArrayList<Scenario> theScenarios = scenarioData.getScenarios();

				String fileConfName = "", fileConfCodes = "", mapConfName = "", mapConfCodes = "";
				if (!fileOutputConfig.isNull()) {
					fileConfName = fileOutputConfig.name;
					fileConfCodes = fileOutputConfig.getCodes();
				}
				if (!mapOutputConfig.isNull()) {
					mapConfName = mapOutputConfig.name;
					mapConfCodes = mapOutputConfig.getCodes();
				}

				db.update(
				"UPDATE study SET " +
					"description = '" + db.clean(description) + "'," +
					"study_mode = " + studyMode + "," +
					"mod_count = " + modCount + "," +
					"point_set_key = " + thePointSetKey + "," +
					"propagation_model = " + propagationModel + "," +
					"study_area_mode = " + studyAreaMode + "," +
					"study_area_geo_key = " + theStudyAreaGeoKey + "," +
					"output_config_file_name = '" + db.clean(fileConfName) + "'," +
					"output_config_file_codes = '" + db.clean(fileConfCodes) + "'," +
					"output_config_map_name = '" + db.clean(mapConfName) + "'," +
					"output_config_map_codes = '" + db.clean(mapConfCodes) + "'," +
					"parameter_summary = '" + db.clean(Parameter.makeParameterSummary(theParameters)) + "'," +
					"ix_rule_summary = '" + db.clean(IxRule.makeIxRuleSummary(theRules)) + "' " +
				"WHERE " +
					"study_key = " + study.key);

				// The report preamble must be updated separately, if the argument is null existing text remains.  This
				// is set when some special study types are first created, later edits made to those studies must not
				// change or clear that initial text.

				if (null != reportPreamble) {
					db.update("UPDATE study SET report_preamble = '" + db.clean(reportPreamble) +
						"' WHERE study_key = " + study.key);
				} else {
					reportPreamble = study.reportPreamble;
				}

				DbCore.releaseDb(db);

				study = new Study(dbID, study.key, name, description, study.studyType, studyMode, study.templateKey,
					study.templateName, study.templateLocked, study.extDbKey, thePointSetKey, propagationModel,
					studyAreaMode, theStudyAreaGeoKey, theParameters, theRules, theSources, theScenarios,
					scenarioPairs, study.scenarioParameters, reportPreamble, fileConfName, fileConfCodes, mapConfName,
					mapConfCodes, study.studyLock, study.lockCount, modCount, study.maxIxRuleKey,
					study.maxScenarioKey);

				// Rebuild the source key in-use map, and purge the study cache of files for sources that don't exist.

				updateSourceKeyMap();

				AppCore.purgeStudyCache(dbID, study.key, sourceKeyMap);

			} catch (SQLException se) {
				try {
					db.update("UNLOCK TABLES");
				} catch (SQLException se1) {
					db.reportError(se1);
				}
				DbCore.releaseDb(db);
				DbConnection.reportError(errors, se);
				return false;
			}

		} else {
			return false;
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Make a set of integer keys into a comma-separated string for a query.

	public static String makeKeyList(HashSet<Integer> theKeys) {

		StringBuilder theList = new StringBuilder();
		boolean first = true;

		for (Integer theKey : theKeys) {
			if (first) {
				theList.append('(');
				first = false;
			} else {
				theList.append(',');
			}
			theList.append(theKey);
		}
		theList.append(')');

		return theList.toString();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Write a list of scenarios to an XML output.  The TVSTUDY root element provides the version number attribute.
	// That element was not present prior to version 1.3, there was no explict version on older exports.  Adding this
	// element as of 1.3 does two things.  It allows application versions 1.3 and later to verify compatibility, and it
	// breaks import of 1.3 or later exports on all older application versions since they will fail at the unknown
	// TVSTUDY element.  The 1.3 and later import code will handle files without this element, the code will do various
	// indirect checks for version differences as needed, see below.  There is of course a more correct way to version
	// XML using a DTD, but since that was not done at the beginning adding it now would not cause newer XML format
	// versions to fail on import to older application versions, so this alternative method is necessary.

	public boolean writeScenariosToXML(Writer xml, ArrayList<ScenarioEditData> theScenarios) {
		return writeScenariosToXML(xml, theScenarios, null);
	}

	public boolean writeScenariosToXML(Writer xml, ArrayList<ScenarioEditData> theScenarios, ErrorLogger errors) {

		try {
			xml.append("<TVSTUDY VERSION=\"" + ParseXML.XML_VERSION + "\">\n");
			for (ScenarioEditData theScenario : theScenarios) {
				if (!theScenario.writeToXML(xml, errors)) {
					return false;
				}
			}
			xml.append("</TVSTUDY>\n");
		} catch (IOException ie) {
			if (null != errors) {
				errors.reportError("Could not write to the file:\n" + ie.getMessage());
			}
			return false;
		} catch (Throwable t) {
			AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
			if (null != errors) {
				errors.reportError("An unexpected error occurred:\n" + t);
			}
			return false;
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Parse an XML source to build scenario(s) and add to the study.  All the work is done in the parsing handler.
	// The parser requires an error logger, if the caller doesn't care about errors create a temporary logger.

	public Integer readScenariosFromXML(Integer extDbKey, Integer altExtDbKey, Reader xml) {
		return readScenariosFromXML(extDbKey, altExtDbKey, xml, null);
	}

	public Integer readScenariosFromXML(Integer extDbKey, Integer altExtDbKey, Reader xml, ErrorLogger errors) {

		if (null != errors) {
			errors.clearErrors();
		} else {
			errors = new ErrorLogger(null, null);
		}

		try {

			ParseXML handler = new ParseXML(this, extDbKey, altExtDbKey, errors);

			XMLReader xReader = XMLReaderFactory.createXMLReader("org.apache.xerces.parsers.SAXParser");
			xReader.setContentHandler(handler);
			xReader.parse(new InputSource(xml));

			if (0 == handler.scenarioCount) {
				if (handler.hadStudy) {
					errors.reportWarning("No compatible scenarios found.");
				} else {
					errors.reportWarning("No recognized XML structure found.");
				}
				return null;
			}

			return Integer.valueOf(handler.scenarioCount);

		} catch (SAXException se) {
			String msg = se.getMessage();
			if ((null != msg) && (msg.length() > 0)) {
				errors.reportError("XML error: " + msg);
			}
		} catch (Throwable t) {
			AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
			errors.reportError("An unexpected error occurred:\n" + t);
		}

		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve parameter by key.

	public ParameterEditData getParameter(int theKey) {

		return parameterMap.get(Integer.valueOf(theKey));
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Named accessors for specific study parameters that are used by other code.  Note there are no error checks, and
	// all parameters are assumed to exist.  A missing parameter will silently return a zero/false value.

	public int getGridType() {
		return getIntegerParameter(Parameter.PARAM_GRID_TYPE, 0);
	}

	public double getCellSize() {
		return getDecimalParameter(Parameter.PARAM_CELL_SIZE, 0);
	}

	public double getKilometersPerDegree() {
		return getDecimalParameter(Parameter.PARAM_EARTH_SPH_DIST, 0);
	}

	public double getMaximumDistance() {
		return getDecimalParameter(Parameter.PARAM_MAX_DIST, 0);
	}

	public double getContourVloDigital(int valueIndex) {
		return getDecimalParameter(Parameter.PARAM_CL_VLO_DTV, valueIndex);
	}

	public double getContourVhiDigital(int valueIndex) {
		return getDecimalParameter(Parameter.PARAM_CL_VHI_DTV, valueIndex);
	}

	public double getContourUhfDigital(int valueIndex) {
		return getDecimalParameter(Parameter.PARAM_CL_UHF_DTV, valueIndex);
	}

	public double getContourVloDigitalLPTV(int valueIndex) {
		return getDecimalParameter(Parameter.PARAM_CL_VLO_DTV_LP, valueIndex);
	}

	public double getContourVhiDigitalLPTV(int valueIndex) {
		return getDecimalParameter(Parameter.PARAM_CL_VHI_DTV_LP, valueIndex);
	}

	public double getContourUhfDigitalLPTV(int valueIndex) {
		return getDecimalParameter(Parameter.PARAM_CL_UHF_DTV_LP, valueIndex);
	}

	public double getContourVloAnalog(int valueIndex) {
		return getDecimalParameter(Parameter.PARAM_CL_VLO_NTSC, valueIndex);
	}

	public double getContourVhiAnalog(int valueIndex) {
		return getDecimalParameter(Parameter.PARAM_CL_VHI_NTSC, valueIndex);
	}

	public double getContourUhfAnalog(int valueIndex) {
		return getDecimalParameter(Parameter.PARAM_CL_UHF_NTSC, valueIndex);
	}

	public double getContourVloAnalogLPTV(int valueIndex) {
		return getDecimalParameter(Parameter.PARAM_CL_VLO_NTSC_LP, valueIndex);
	}

	public double getContourVhiAnalogLPTV(int valueIndex) {
		return getDecimalParameter(Parameter.PARAM_CL_VHI_NTSC_LP, valueIndex);
	}

	public double getContourUhfAnalogLPTV(int valueIndex) {
		return getDecimalParameter(Parameter.PARAM_CL_UHF_NTSC_LP, valueIndex);
	}

	public boolean getUseDipoleCont(int valueIndex) {
		return getOptionParameter(Parameter.PARAM_USE_DIPOLE_CL, valueIndex);
	}

	public double getDipoleCenterFreqCont(int valueIndex) {
		return getDecimalParameter(Parameter.PARAM_DIPOLE_CENTER_CL, valueIndex);
	}

	public int getCurveSetDigital(int valueIndex) {
		return getIntegerParameter(Parameter.PARAM_CURV_DTV, valueIndex);
	}

	public int getCurveSetAnalog(int valueIndex) {
		return getIntegerParameter(Parameter.PARAM_CURV_NTSC, valueIndex);
	}

	public double getContourFM(int valueIndex) {
		return getDecimalParameter(Parameter.PARAM_CL_FM, valueIndex);
	}

	public double getContourFMB(int valueIndex) {
		return getDecimalParameter(Parameter.PARAM_CL_FM_B, valueIndex);
	}

	public double getContourFMB1(int valueIndex) {
		return getDecimalParameter(Parameter.PARAM_CL_FM_B1, valueIndex);
	}

	public double getContourFMED(int valueIndex) {
		return getDecimalParameter(Parameter.PARAM_CL_FM_ED, valueIndex);
	}

	public double getContourFMLP(int valueIndex) {
		return getDecimalParameter(Parameter.PARAM_CL_FM_LP, valueIndex);
	}

	public double getContourFMTX(int valueIndex) {
		return getDecimalParameter(Parameter.PARAM_CL_FM_TX, valueIndex);
	}

	public int getCurveSetFM(int valueIndex) {
		return getIntegerParameter(Parameter.PARAM_CURV_FM, valueIndex);
	}

	public double getRuleExtraDistanceLow() {
		return getDecimalParameter(Parameter.PARAM_RUL_EXT_DST_L, 0);
	}

	public double getRuleExtraDistanceERPLow() {
		return getDecimalParameter(Parameter.PARAM_RUL_EXT_ERP_L, 0);
	}

	public double getRuleExtraDistanceLowMedium() {
		return getDecimalParameter(Parameter.PARAM_RUL_EXT_DST_LM, 0);
	}

	public double getRuleExtraDistanceERPMedium() {
		return getDecimalParameter(Parameter.PARAM_RUL_EXT_ERP_M, 0);
	}

	public double getRuleExtraDistanceMediumHigh() {
		return getDecimalParameter(Parameter.PARAM_RUL_EXT_DST_MH, 0);
	}

	public double getRuleExtraDistanceERPHigh() {
		return getDecimalParameter(Parameter.PARAM_RUL_EXT_ERP_H, 0);
	}

	public double getRuleExtraDistanceHigh() {
		return getDecimalParameter(Parameter.PARAM_RUL_EXT_DST_H, 0);
	}

	public boolean getUseMaxRuleExtraDistance() {
		return getOptionParameter(Parameter.PARAM_RUL_EXT_MAX, 0);
	}

	public double getCoChannelMxDistance() {
		return getDecimalParameter(Parameter.PARAM_CO_CHAN_MX_DIST, 0);
	}

	public int getMinimumChannel() {
		return getIntegerParameter(Parameter.PARAM_MIN_CHANNEL, 0);
	}

	public int getMaximumChannel() {
		return getIntegerParameter(Parameter.PARAM_MAX_CHANNEL, 0);
	}

	public boolean getTrustPatternFlag() {
		return getOptionParameter(Parameter.PARAM_TRUST_DA_FLAG, 0);
	}

	public boolean getUseGenericVpat(int valueIndex) {
		return getOptionParameter(Parameter.PARAM_GEN_VPAT, valueIndex);
	}

	public double getDefaultMexicanDigitalVloERP() {
		return getDecimalParameter(Parameter.PARAM_MEX_ERP_D_VLO, 0);
	}

	public double getDefaultMexicanDigitalVloHAAT() {
		return getDecimalParameter(Parameter.PARAM_MEX_HAAT_D_VLO, 0);
	}

	public double getDefaultMexicanDigitalVhiERP() {
		return getDecimalParameter(Parameter.PARAM_MEX_ERP_D_VHI, 0);
	}

	public double getDefaultMexicanDigitalVhiHAAT() {
		return getDecimalParameter(Parameter.PARAM_MEX_HAAT_D_VHI, 0);
	}

	public double getDefaultMexicanDigitalUhfERP() {
		return getDecimalParameter(Parameter.PARAM_MEX_ERP_D_UHF, 0);
	}

	public double getDefaultMexicanDigitalUhfHAAT() {
		return getDecimalParameter(Parameter.PARAM_MEX_HAAT_D_UHF, 0);
	}

	public double getDefaultMexicanAnalogVloERP() {
		return getDecimalParameter(Parameter.PARAM_MEX_ERP_N_VLO, 0);
	}

	public double getDefaultMexicanAnalogVloHAAT() {
		return getDecimalParameter(Parameter.PARAM_MEX_HAAT_N_VLO, 0);
	}

	public double getDefaultMexicanAnalogVhiERP() {
		return getDecimalParameter(Parameter.PARAM_MEX_ERP_N_VHI, 0);
	}

	public double getDefaultMexicanAnalogVhiHAAT() {
		return getDecimalParameter(Parameter.PARAM_MEX_HAAT_N_VHI, 0);
	}

	public double getDefaultMexicanAnalogUhfERP() {
		return getDecimalParameter(Parameter.PARAM_MEX_ERP_N_UHF, 0);
	}

	public double getDefaultMexicanAnalogUhfHAAT() {
		return getDecimalParameter(Parameter.PARAM_MEX_HAAT_N_UHF, 0);
	}

	public boolean getCheckIndividualDTSDistance() {
		return getOptionParameter(Parameter.PARAM_DTS_DIST_CHECK, 0);
	}

	public double getDefaultMexicanFMAERP() {
		return getDecimalParameter(Parameter.PARAM_MEX_ERP_FM_A, 0);
	}

	public double getDefaultMexicanFMAHAAT() {
		return getDecimalParameter(Parameter.PARAM_MEX_HAAT_FM_A, 0);
	}

	public double getDefaultMexicanFMBERP() {
		return getDecimalParameter(Parameter.PARAM_MEX_ERP_FM_B, 0);
	}

	public double getDefaultMexicanFMBHAAT() {
		return getDecimalParameter(Parameter.PARAM_MEX_HAAT_FM_B, 0);
	}

	public double getDefaultMexicanFMB1ERP() {
		return getDecimalParameter(Parameter.PARAM_MEX_ERP_FM_B1, 0);
	}

	public double getDefaultMexicanFMB1HAAT() {
		return getDecimalParameter(Parameter.PARAM_MEX_HAAT_FM_B1, 0);
	}

	public double getDefaultMexicanFMCERP() {
		return getDecimalParameter(Parameter.PARAM_MEX_ERP_FM_C, 0);
	}

	public double getDefaultMexicanFMCHAAT() {
		return getDecimalParameter(Parameter.PARAM_MEX_HAAT_FM_C, 0);
	}

	public double getDefaultMexicanFMC1ERP() {
		return getDecimalParameter(Parameter.PARAM_MEX_ERP_FM_C1, 0);
	}

	public double getDefaultMexicanFMC1HAAT() {
		return getDecimalParameter(Parameter.PARAM_MEX_HAAT_FM_C1, 0);
	}

	public double[] getTV6FMDistance() {
		return getDecimalTableParameter(Parameter.PARAM_TV6_FM_DIST, 0);
	}

	public double[] getTV6FMDistanceDtv() {
		return getDecimalTableParameter(Parameter.PARAM_TV6_FM_DIST_DTV, 0);
	}

	public double[] getWirelessCullDistance(int valueIndex) {
		return getDecimalTableParameter(Parameter.PARAM_WL_CULL_DIST, valueIndex);
	}

	private int getIntegerParameter(int parameterKey, int valueIndex) {
		ParameterEditData theParam = parameterMap.get(Integer.valueOf(parameterKey));
		if ((null == theParam) || theParam.parameter.isTable || (valueIndex < 0) ||
				(valueIndex >= theParam.parameter.valueCount) || (null == theParam.integerValue)) {
			return 0;
		}
		return theParam.integerValue[valueIndex];
	}

	private int[] getIntegerTableParameter(int parameterKey, int valueIndex) {
		ParameterEditData theParam = parameterMap.get(Integer.valueOf(parameterKey));
		if ((null == theParam) || !theParam.parameter.isTable || (valueIndex < 0) ||
				(valueIndex >= theParam.parameter.valueCount) || (null == theParam.integerTableValue)) {
			return null;
		}
		return theParam.integerTableValue[valueIndex];
	}

	private double getDecimalParameter(int parameterKey, int valueIndex) {
		ParameterEditData theParam = parameterMap.get(Integer.valueOf(parameterKey));
		if ((null == theParam) || theParam.parameter.isTable || (valueIndex < 0) ||
				(valueIndex >= theParam.parameter.valueCount) || (null == theParam.decimalValue)) {
			return 0.;
		}
		return theParam.decimalValue[valueIndex];
	}

	private double[] getDecimalTableParameter(int parameterKey, int valueIndex) {
		ParameterEditData theParam = parameterMap.get(Integer.valueOf(parameterKey));
		if ((null == theParam) || !theParam.parameter.isTable || (valueIndex < 0) ||
				(valueIndex >= theParam.parameter.valueCount) || (null == theParam.decimalTableValue)) {
			return null;
		}
		return theParam.decimalTableValue[valueIndex];
	}

	private boolean getOptionParameter(int parameterKey, int valueIndex) {
		ParameterEditData theParam = parameterMap.get(Integer.valueOf(parameterKey));
		if ((null == theParam) || (valueIndex < 0) || (valueIndex >= theParam.parameter.valueCount)) {
			return false;
		}
		return theParam.optionValue[valueIndex];
	}

	private java.util.Date getDateParameter(int parameterKey, int valueIndex) {
		ParameterEditData theParam = parameterMap.get(Integer.valueOf(parameterKey));
		if ((null == theParam) || (valueIndex < 0) || (valueIndex >= theParam.parameter.valueCount)) {
			return null;
		}
		return theParam.dateValue[valueIndex];
	}
}
