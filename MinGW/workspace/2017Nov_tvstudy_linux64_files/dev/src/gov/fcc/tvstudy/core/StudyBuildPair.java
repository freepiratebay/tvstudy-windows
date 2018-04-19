//
//  StudyBuildPair.java
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
// StudyBuild subclass for building a pair-wise interference study.  This takes an existing study with a baseline
// scenario defining the set of stations to be evaluated for pair-wise interference.  Sets of pair-wise interference
// scenarios are generated and saved in that study (temporarily, they are deleted again at the end of the run) by
// replicating each desired station in the baseline to a study channel, then in turn replicating all other baseline
// stations with a potential interference relationship to the study station to co- and adjacent-channels and creating
// scenarios to isolate each resulting pairing.  The outputs from the study runs go to temporary cell-level files in a
// special format which are then post-processed to produce a final pairing contraint data set.  See RunPanelPairStudy
// for details.  The superclass properties for the target record and replication settings are not used here.

public class StudyBuildPair extends StudyBuild {

	// This is the base study used, it must already be locked for edit.

	public Study baseStudy;

	// Input set by UI.  If country is null all countries are studied.

	public Country studyCountry;
	public int[] studyChannels;
	public int runCount;

	// Information pulled from the baseline scenario, used for run setup and management.

	public int baselineScenarioKey;
	public int baselineSourceCount;

	// Scenario information generated during the build process.  The sorted scenario list is retrieved with accessor
	// getScenarioRunList(), which returns an ArrayDeque the caller can use to dispatch scenarios to multiple engines.

	public int scenarioCount;
	public int scenarioSourceCount;

	private TreeSet<ScenarioSortItem> sortedScenarios;

	// State used during build.

	private static final int MIN_SCENARIOS_PER_RUN = 5;

	private StudyEditData study;
	private ScenarioEditData baselineScenario;


	//-----------------------------------------------------------------------------------------------------------------

	public StudyBuildPair(String theDbID) {

		super(theDbID);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The study provided must be appropriate for this, validity checks are done here.  Superclass initialize is not
	// called since the superclass properties are not used.  The study must be set and locked for edit.

	public boolean initialize(ErrorLogger errors) {

		if (initialized) {
			return true;
		}

		if ((null == baseStudy) || (Study.LOCK_EDIT != baseStudy.studyLock)) {
			if (null != errors) {
				errors.reportError("Cannot build study, invalid settings.");
			}
			return false;
		}

		// Make sure study channels provided, and no duplicates.

		if ((null == studyChannels) || (0 == studyChannels.length)) {
			if (null != errors) {
				errors.reportError("Cannot build study, no channels selected.");
			}
			return false;
		}

		int i, j;
		for (i = 0; i < studyChannels.length - 1; i++) {
			for (j = i + 1; j < studyChannels.length; j++) {
				if (studyChannels[i] == studyChannels[j]) {
					if (null != errors) {
						errors.reportError("Cannot build study, duplicate channels selected.");
					}
					return false;
				}
			}
		}

		// Silently fix bad process count.  It may be further modified during the build due to MAX_SCENARIOS_PER_RUN.
		// Note maxEngineProcessCount might be <=0 in which case even one process won't run because there is not enough
		// total system memory or the executable isn't properly installed; but it's up to other code to check for that
		// and not even get here in that case.  Here, just be sure runCount is never less than 1.

		if (runCount > AppCore.maxEngineProcessCount) {
			runCount = AppCore.maxEngineProcessCount;
		}
		if (runCount < 1) {
			runCount = 1;
		}

		// Determine if the study is appropriate for a pair run, the required conditions are:
		//   - Must be a general-purpose TV study
		//   - Uses global grid mode
		//   - Cell size >= 0.5 km
		//   - Contains exactly one scenario
		//   - Scenario has at least two study sources, and all study sources:
		//     - have unique facility IDs
		//     - have coordinates in appropriate range
		// The coordinate check is based on the restriction that all coverage study points must fall in the ranges of
		// 0-75 degrees north latitude and 0-180 degrees west longitude.  If coordinates are outside 0-75/0-180, that
		// is an immediate failure.  If close (less than 2 degrees) to the edge of that area, a further check is done
		// using the rule-extra-distance value, which is a worst-case contour distance.

		if (Study.STUDY_TYPE_TV != baseStudy.studyType) {
			if (null != errors) {
				errors.reportError("Cannot build study, a general-purpose TV study must be selected.");
			}
			return false;
		}

		// Create the editable study object, needed for the checks and later used for the build.  If an error occurs
		// the study object is invalidated.  The caller must release the study lock if this fails.

		try {
			study = new StudyEditData(baseStudy);
		} catch (Throwable t) {
			AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
			if (null != errors) {
				errors.reportError("Cannot build study, unexpected error:\n" + t);
			}
			return false;
		}

		if ((Study.STUDY_MODE_GRID != study.studyMode) || (Parameter.GRID_TYPE_GLOBAL != study.getGridType())) {
			if (null != errors) {
				errors.reportError("Cannot build study, the study must use global grid mode.");
			}
			study.invalidate();
			return false;
		}

		if (study.getCellSize() < 0.5) {
			if (null != errors) {
				errors.reportError("Cannot build study, the cell size must be 0.5 km or larger.");
			}
			study.invalidate();
			return false;
		}

		if (1 != baseStudy.scenarios.size()) {
			if (null != errors) {
				errors.reportError("Cannot build study, there must be only one scenario to set the baseline.");
			}
			study.invalidate();
			return false;
		}

		baselineScenario = study.scenarioData.get(0);

		baselineScenarioKey = baselineScenario.key.intValue();
		baselineSourceCount = baselineScenario.sourceData.getDesiredSourceCount();

		HashSet<Integer> facilityIDs = new HashSet<Integer>();
		SourceEditDataTV theSourceTV;
		int sourceCount = 0;
		double lonMax, extDist, chkDist, kmPerDeg = study.getKilometersPerDegree();
		boolean fails;

		for (SourceEditData theSource : baselineScenario.sourceData.getDesiredSources()) {

			if (Source.RECORD_TYPE_TV != theSource.recordType) {
				if (null != errors) {
					errors.reportError("Cannot build study, the baseline scenario must contain only TV stations.");
				}
				study.invalidate();
				return false;
			}
			theSourceTV = (SourceEditDataTV)theSource;

			if (theSourceTV.facilityID <= 0) {
				if (null != errors) {
					errors.reportError("Cannot build study, facility IDs must be > 0.");
				}
				study.invalidate();
				return false;
			}

			if (!facilityIDs.add(Integer.valueOf(theSourceTV.facilityID))) {
				if (null != errors) {
					errors.reportError("Cannot build study, stations must only appear once in the baseline scenario.");
				}
				study.invalidate();
				return false;
			}

			if ((theSource.location.latitude < 0.) || (theSource.location.latitude > 75.) ||
					(theSource.location.longitude < 0.) || (theSource.location.longitude > 180.)) {
				if (null != errors) {
					errors.reportError("Cannot build study, stations must be in 0-75 N. latitude and " +
						"0-180 W. longitude.");
				}
				study.invalidate();
				return false;
			}

			lonMax = 2. / Math.cos(theSource.location.latitude * GeoPoint.DEGREES_TO_RADIANS);
			if ((theSource.location.latitude < 2.) || (theSource.location.latitude > 73.) ||
					(theSource.location.longitude < lonMax) || (theSource.location.longitude > (180. - lonMax))) {

				fails = false;
				extDist = theSourceTV.getRuleExtraDistance();

				chkDist = theSource.location.distanceTo(0., theSource.location.longitude, kmPerDeg);
				if (chkDist < extDist) {
					fails = true;
				} else {
					chkDist = theSource.location.distanceTo(75., theSource.location.longitude, kmPerDeg);
					if (chkDist < extDist) {
						fails = true;
					} else {
						chkDist = theSource.location.distanceTo(theSource.location.latitude, 0., kmPerDeg);
						if (chkDist < extDist) {
							fails = true;
						} else {
							chkDist = theSource.location.distanceTo(theSource.location.latitude, 180., kmPerDeg);
							if (chkDist < extDist) {
								fails = true;
							}
						}
					}
				}

				if (fails) {
					if (null != errors) {
						errors.reportError("Cannot build study, station coverage areas must be entirely in " +
							"0-75 N. latitude and 0-180 W. longitude.");
					}
					study.invalidate();
					return false;
				}
			}

			sourceCount++;
		}

		if (sourceCount < 2) {
			if (null != errors) {
				errors.reportError("Cannot build study, the baseline scenario must include at least two stations.");
			}
			study.invalidate();
			return false;
		}

		// Check the selected channels, must be in range for the study.

		int minChannel = study.getMinimumChannel(), maxChannel = study.getMaximumChannel();

		for (i = 0; i < studyChannels.length; i++) {
			if ((studyChannels[i] < minChannel) || (studyChannels[i] > maxChannel)) {
				if (null != errors) {
					errors.reportError("Cannot build study, channels must be in the range " + minChannel + " to " +
						maxChannel + ".");
				}
				study.invalidate();
				return false;
			}
		}

		// All good, final setup.

		sortedScenarios = new TreeSet<ScenarioSortItem>();

		initialized = true;

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Build the study.  Create the pair scenarios, save changes to the study.  Note isDataChanged() has to be called
	// before save() due to side-effects.  The StatusLogger is not currently used here.

	public Study buildStudy(StatusLogger status, ErrorLogger errors) {

		if (!initialized) {
			return null;
		}

		// If the new scenario count is 0 that is an error as continuing with save and run is pointless. Check the
		// abort flag before doing the save.

		boolean error = true;

		if (createStudyScenarios(errors)) {
			if (0 == scenarioCount) {
				if (null != errors) {
					errors.reportError("Cannot build study, no station pairs found.");
				}
			} else {
				if (!isAborted()) {
					study.isDataChanged();
					if (study.save(errors)) {
						baseStudy = study.study;
						error = false;
					}
				}
			}
		}

		// Regardless of success or failure, the editable study model is invalidated, there will be no further edits
		// and saves with that object.  The restoreStudy() operations use direct queries.

		study.invalidate();

		if (error) {
			return null;
		}

		return baseStudy;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Return a list of the scenarios to run.

	public ArrayDeque<ScenarioSortItem> getScenarioRunList() {

		ArrayDeque<ScenarioSortItem> scenarioRunList = new ArrayDeque<ScenarioSortItem>();

		Iterator<ScenarioSortItem> scenarioIterator = sortedScenarios.iterator();
		while (scenarioIterator.hasNext()) {
			scenarioRunList.add(scenarioIterator.next());
		}

		return scenarioRunList;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Restore the study to previous conditions (more or less), delete all of the pair scenarios and save.  The
	// replication sources used by those scenarios are not removed; those, and associated caches, may be useful for
	// subsequent re-runs.  Start by changing the study lock back to edit.  Errors are reported and will cause a false
	// return, but there really isn't much to be done in that case other than proceed anyway.

	public boolean restoreStudy(ErrorLogger errors) {

		if (!initialized) {
			return false;
		}

		boolean error = false;
		String errorMessage = "";

		String rootName = DbCore.getDbName(dbID);

		DbConnection db = DbCore.connectDb(dbID, errors);
		if (null != db) {
			try {

				db.update("LOCK TABLES study WRITE");

				db.query("SELECT study_lock, lock_count FROM study WHERE study_key = " + baseStudy.key);

				if (db.next()) {

					if ((db.getInt(1) == baseStudy.studyLock) && (db.getInt(2) == baseStudy.lockCount)) {

						db.update("UPDATE study SET study_lock = " + Study.LOCK_EDIT +
							", lock_count = lock_count + 1, share_count = 0 WHERE study_key = " + baseStudy.key);
						baseStudy.studyLock = Study.LOCK_EDIT;
						baseStudy.lockCount++;

					} else {
						error = true;
						errorMessage = "The study lock was modified.";
					}

				} else {
					error = true;
					errorMessage = "The study was deleted.";
				}

			} catch (SQLException se) {
				error = true;
				errorMessage = DbConnection.ERROR_TEXT_PREFIX + se;
				db.reportError(se);
			}

			try {
				db.update("UNLOCK TABLES");
			} catch (SQLException se) {
				db.reportError(se);
			}

			// Scenario removal is done directly in the database rather than modifying the StudyEditData object and
			// using save(), mainly because that would be slightly risky since that object's state and the database are
			// out of sync now due to changes made by the engine runs; hypothetically save() could revert those
			// changes.  It shouldn't, but why take the chance when this delete is simple.

			if (!error) {

				try {

					db.setDatabase(rootName + "_" + baseStudy.key);

					db.update("DELETE FROM scenario_parameter_data WHERE scenario_key <> " + baselineScenario.key);
					db.update("DELETE FROM scenario_source WHERE scenario_key <> " + baselineScenario.key);
					db.update("DELETE FROM scenario WHERE scenario_key <> " + baselineScenario.key);

				} catch (SQLException se) {
					error = true;
					errorMessage = DbConnection.ERROR_TEXT_PREFIX + se;
					db.reportError(se);
				}
			}

			DbCore.releaseDb(db);

			if (error && (null != errors)) {
				errors.reportError("Could not restore the study database:\n" + errorMessage);
			}

		} else {
			error = true;
		}

		return !error;
	}


	//=================================================================================================================
	// Data class used with a TreeSet to sort scenarios created in createStudyScenarios().  Comparison first checks
	// the coordinate value, if equal, forwards to the scenario key.  Hash code and equality always forward to the key.

	public static class ScenarioSortItem implements Comparable {

		public Integer key;
		public int sourceCount;

		private double coordinate;


		//-------------------------------------------------------------------------------------------------------------

		private ScenarioSortItem(ScenarioEditData theScenario, SourceEditDataTV theSource, boolean useLatitude) {

			key = theScenario.key;

			if (useLatitude) {
				coordinate = theSource.location.latitude;
			} else {
				coordinate = theSource.location.longitude;
			}

			sourceCount = theScenario.sourceData.getRowCount();
		}


		//-------------------------------------------------------------------------------------------------------------

		public int hashCode() {

			return key.hashCode();
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean equals(Object other) {

			return (null != other) && key.equals(((ScenarioSortItem)other).key);
		}


		//-------------------------------------------------------------------------------------------------------------

		public int compareTo(Object other) {

			if (null == other) {
				return 0;
			}

			ScenarioSortItem theOther = (ScenarioSortItem)other;
			if (coordinate < theOther.coordinate) {
				return -1;
			} else {
				if (coordinate > theOther.coordinate) {
					return 1;
				} else {
					return key.compareTo(theOther.key);
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create scenarios in the study.

	// Each study country station in the baseline scenario may create one scenario per study channel.  Each of those
	// gets a source for the station replicated on the study channel and flagged desired.  Then other stations in the
	// baseline scenario that match interference rules for co-channel and adjacent channels (digital service only) are
	// added as undesired-only sources.  During that search all stations are included, in any country.  Each station is
	// replicated to all potential interfering channels and the resulting sources are studied simultaneously.  That
	// means the interference-free coverage totals from the study are meaningless, but the purpose is to obtain all of
	// the low-level study point data needed in one run.

	// Each foreign station in the baseline scenario may create one study scenario.  In those scenarios, the foreign
	// station is considered exactly as it appears in the baseline scenario, it is never replicated to a study channel
	// (although it may already be a replication source in the baseline if desired, and if it is analog it will be
	// replicated to digital on the same channel).  The undesired search is similar to above, however only study
	// country stations are considered in that search.

	// In any search, only stations flagged as desired in the baseline scenario are considered.  The baseline scenario
	// may contain other undesired-only stations as part of defining baseline interference-free coverage for studied
	// stations; those other undesired-only stations will not be included in the pair-study scenarios.  If any scenario
	// ends up without any undesired stations, it is not included.

	// All sources in the pair study scenarios will be digital.  If a station is currently analog it will be replicated
	// to digital service on the same channel if needed.

	// This returns false if an error occurs.  The number of scenarios created is placed in property scenarioCount;
	// that could legitimately be zero, caller should check that as proceeding with an actual run would be pointless.
	// The study data model is modified directly, so an error return may leave the model in a partially-modified state.
	// The assumption is any error will abort the entire study and model changes will never be saved to the database.

	private boolean createStudyScenarios(ErrorLogger errors) {

		double kmPerDeg = study.getKilometersPerDegree();
		boolean chkDTSDist = study.getCheckIndividualDTSDistance();
		int minChannel = study.getMinimumChannel();
		int maxChannel = study.getMaximumChannel(); 

		ArrayList<SourceEditDataTV> theSources = new ArrayList<SourceEditDataTV>();
		for (SourceEditData aSource : baselineScenario.sourceData.getDesiredSources()) {
			theSources.add((SourceEditDataTV)aSource);
		}

		// Only need active rules for digital-to-digital for co- or adjacent-channel relationships.

		ArrayList<IxRuleEditData> theRules = new ArrayList<IxRuleEditData>();
		for (IxRuleEditData theRule : study.ixRuleData.getRows()) {
			if (!theRule.isActive) {
				continue;
			}
			if (!theRule.serviceType.digital || !theRule.undesiredServiceType.digital) {
				continue;
			}
			if ((theRule.channelDelta.delta < -1) || (theRule.channelDelta.delta > 1)) {
				continue;
			}
			theRules.add(theRule);
		}

		// This builds a list of scenario keys that will be studied by the engine processes.  The scenarios will be fed
		// to the engines interactively using the ProcessController interface, see ProcessPanel and getResponse(), so
		// load is dynamically balanced between multiple engines for minimum run-time.  The scenarios are sorted by
		// coordinates of the study station, using either latitude or longitude depending on which spans the greater
		// range, to optimize use of the various caches in the study engine.

		sortedScenarios.clear();

		double minLatitude = 999., maxLatitude = -999., minLongitude = 999., maxLongitude = -999.;

		for (SourceEditDataTV theSource : theSources) {
			if (theSource.location.latitude < minLatitude) {
				minLatitude = theSource.location.latitude;
			}
			if (theSource.location.latitude > maxLatitude) {
				maxLatitude = theSource.location.latitude;
			}
			if (theSource.location.longitude < minLongitude) {
				minLongitude = theSource.location.longitude;
			}
			if (theSource.location.longitude > maxLongitude) {
				maxLongitude = theSource.location.longitude;
			}
		}

		boolean useLatitude = false;
		if ((maxLatitude - minLatitude) > (maxLongitude - minLongitude)) {
			useLatitude = true;
		}

		ScenarioSortItem theItem;

		// Editable sources, sources with no underlying primary record, and replication sources based on those are
		// normally never be shared between scenarios.  However the temporary scenarios being created here will never
		// be seen by the user.  That makes it safe to share those sources, and there is a substantial benefit to doing
		// that.  The sources must be indexed locally since the StudyEditData object considers them non-shareable.
		// Only the replications have to be indexed, the originals will come from the baseline scenario.  Since this
		// can include sources with no external record ID the original source key is used in the index rather than
		// the record ID.  All channels must be indexed not just the study channels, any channel may be needed when
		// studying a non-U.S. station.

		int maxChans = (maxChannel - minChannel) + 1;
		ArrayList<HashMap<Integer, SourceEditDataTV>> sharedReplications =
			new ArrayList<HashMap<Integer, SourceEditDataTV>>(maxChans);
		for (int i = 0; i < maxChans; i++) {
			sharedReplications.add(new HashMap<Integer, SourceEditDataTV>());
		}

		// Ready to start building scenarios.  In the first pass, consider only stations in the study country.  If the
		// source in the baseline scenario is already a replication source, get the original.

		scenarioCount = 0;
		scenarioSourceCount = 0;

		int stationCount = 0, pairCount = 0;
		boolean firstChannel, countStation, countPair;

		SourceEditData aSource;
		SourceEditDataTV originalSource, studySource, otherOriginalSource, ixSource;
		ArrayList<Scenario.SourceListItem> newSourceItems = new ArrayList<Scenario.SourceListItem>();
		ScenarioEditData newScenario;
		double distance;
		int serviceTypeKey, countryKey, frequencyOffsetKey, delta, ixChannel, ixServiceTypeKey, ixFrequencyOffsetKey,
			emissionMaskKey;
		IxRuleEditData ixRule;

		for (SourceEditDataTV theSource : theSources) {

			if ((null != studyCountry) && (studyCountry.key != theSource.country.key)) {
				continue;
			}

			if (null == theSource.originalSourceKey) {
				originalSource = theSource;
			} else {
				aSource = study.getSource(theSource.originalSourceKey);
				if ((null == aSource) || (Source.RECORD_TYPE_TV != aSource.recordType)) {
					if (null != errors) {
						errors.reportError("An original record needed for replication does not exist.");
					}
					return false;
				}
				originalSource = (SourceEditDataTV)aSource;
			}

			// Loop over the study channels.  Get a source for the study station on the channel, if the source is
			// digital and already on the study channel use it directly, else replicate.  Only count station pairs for
			// the statistics report when searching the first channel, the same pairs will be found for all others.

			firstChannel = true;

			for (int channel : studyChannels) {

				if ((channel == originalSource.channel) && originalSource.service.serviceType.digital) {
					studySource = originalSource;
				} else {
					studySource = null;
					if (originalSource.isLocked && (null != originalSource.extDbKey) &&
							(null != originalSource.extRecordID)) {
						studySource = study.findSharedReplicationSource(originalSource.extDbKey,
							originalSource.extRecordID, channel);
					} else {
						studySource = sharedReplications.get(channel - minChannel).get(originalSource.key);
					}
					if (null == studySource) {
						studySource = originalSource.replicate(channel, errors);
						if (null == studySource) {
							return false;
						}
						study.addOrReplaceSource(studySource);
						if (!originalSource.isLocked || (null == originalSource.extRecordID)) {
							sharedReplications.get(channel - minChannel).put(originalSource.key, studySource);
						}
					}
				}

				newSourceItems.add(new Scenario.SourceListItem(studySource.key.intValue(), true, false, false));

				// Get keys used for matching interference rules.

				serviceTypeKey = studySource.service.serviceType.key;
				countryKey = studySource.country.key;
				frequencyOffsetKey = studySource.frequencyOffset.key;

				// Loop to find undesired stations.  Of course don't consider a station against itself.  Facility IDs
				// are assumed unique in the baseline, see comments above.  Get original if already a replication.

				countStation = firstChannel;

				for (SourceEditDataTV otherSource : theSources) {

					if (isAborted()) {
						return false;
					}

					if (otherSource.facilityID == studySource.facilityID) {
						continue;
					}

					if (null == otherSource.originalSourceKey) {
						otherOriginalSource = otherSource;
					} else {
						aSource = study.getSource(otherSource.originalSourceKey);
						if ((null == aSource) || (Source.RECORD_TYPE_TV != aSource.recordType)) {
							if (null != errors) {
								errors.reportError("An original record needed for replication does not exist.");
							}
							return false;
						}
						otherOriginalSource = (SourceEditDataTV)aSource;
					}

					// Get keys for matching rules.  If the potential undesired source is not digital, match according
					// to the service that will be assigned when switching to digital, see StudyEditData.replicate().

					if (otherOriginalSource.service.serviceType.digital) {
						ixServiceTypeKey = otherOriginalSource.service.serviceType.key;
						ixFrequencyOffsetKey = otherOriginalSource.frequencyOffset.key;
						emissionMaskKey = otherOriginalSource.emissionMask.key;
					} else {
						ixServiceTypeKey = otherOriginalSource.service.digitalService.serviceType.key;
						ixFrequencyOffsetKey = 0;
						if (otherOriginalSource.service.digitalService.serviceType.needsEmissionMask) {
							emissionMaskKey = EmissionMask.getDefaultObject().key;
						} else {
							emissionMaskKey = 0;
						}
					}

					// Compute distance between sources.  Note this is not just a simple separation distance, it is a
					// minimum separation distance reduced by an appropriate rule extra distance so it is suitable for
					// direct comparison to an interference rule distance limit.  When DTS is involved the calculation
					// checks multiple transmitter points each with it's own extra distance and finds the minimum.

					distance = distanceBetween(studySource, otherOriginalSource, kmPerDeg, chkDTSDist);

					// Loop over the interfering channel range, skip invalid channels and cross-band relationships.

					countPair = firstChannel;

					for (delta = -1; delta <= 1; delta++) {

						ixChannel = channel + delta;

						if ((ixChannel < minChannel) || (ixChannel > maxChannel)) {
							continue;
						}

						if (channel < 5) {
							if (ixChannel > 4) {
								continue;
							}
						} else {
							if (channel < 7) {
								if ((ixChannel < 5) || (ixChannel > 6)) {
									continue;
								}
							} else {
								if (channel < 14) {
									if ((ixChannel < 7) || (ixChannel > 13)) {
										continue;
									}
								}
							}
						}

						// Find a matching rule.

						ixRule = null;

						for (IxRuleEditData theRule : theRules) {

							if (theRule.country.key != countryKey) {
								continue;
							}
							if (theRule.serviceType.key != serviceTypeKey) {
								continue;
							}
							if (theRule.undesiredServiceType.key != ixServiceTypeKey) {
								continue;
							}
							if (delta != theRule.channelDelta.delta) {
								continue;
							}
							if ((channel < theRule.channelBand.firstChannel) ||
									(channel > theRule.channelBand.lastChannel)) {
								continue;
							}
							if ((IxRule.FREQUENCY_OFFSET_WITH == theRule.frequencyOffset) &&
									((0 == frequencyOffsetKey) || (0 == ixFrequencyOffsetKey) ||
									(frequencyOffsetKey == ixFrequencyOffsetKey))) {
								continue;
							}
							if ((IxRule.FREQUENCY_OFFSET_WITHOUT == theRule.frequencyOffset) &&
									((0 != frequencyOffsetKey) && (0 != ixFrequencyOffsetKey) &&
									(frequencyOffsetKey != ixFrequencyOffsetKey))) {
								continue;
							}
							if ((0 != theRule.emissionMask.key) &&
									((0 == emissionMaskKey) || (theRule.emissionMask.key != emissionMaskKey))) {
								continue;
							}

							ixRule = theRule;
							break;
						}

						// If no rule, skip it.  If rule found, check distance.

						if ((null == ixRule) || (distance > ixRule.distance)) {
							continue;
						}

						// Source is included, create source for the channel, replicate if needed.

						if (countStation) {
							stationCount++;
							countStation = false;
						}
						if (countPair) {
							pairCount++;
							countPair = false;
						}

						if ((ixChannel == otherOriginalSource.channel) &&
								otherOriginalSource.service.serviceType.digital) {
							ixSource = otherOriginalSource;
						} else {
							ixSource = null;
							if (otherOriginalSource.isLocked && (null != otherOriginalSource.extDbKey) &&
									(null != otherOriginalSource.extRecordID)) {
								ixSource = study.findSharedReplicationSource(otherOriginalSource.extDbKey,
									otherOriginalSource.extRecordID, ixChannel);
							} else {
								ixSource = sharedReplications.get(ixChannel - minChannel).get(otherOriginalSource.key);
							}
							if (null == ixSource) {
								ixSource = otherOriginalSource.replicate(ixChannel, errors);
								if (null == ixSource) {
									return false;
								}
								study.addOrReplaceSource(ixSource);
								if (!otherOriginalSource.isLocked || (null == otherOriginalSource.extRecordID)) {
									sharedReplications.get(ixChannel - minChannel).
										put(otherOriginalSource.key, ixSource);
								}
							}
						}

						newSourceItems.add(new Scenario.SourceListItem(ixSource.key.intValue(), false, true, false));
					}
				}

				// If any undesired sources were added, create the new scenario.  Use the same name and description for
				// all; the constructor will automatically append the scenario key to make the name unique.

				if (newSourceItems.size() > 1) {

					newScenario = new ScenarioEditData(study, "Pair", "Pair study", newSourceItems);
					study.scenarioData.addOrReplace(newScenario);

					theItem = new ScenarioSortItem(newScenario, studySource, useLatitude);
					sortedScenarios.add(theItem);

					scenarioCount++;
					scenarioSourceCount += theItem.sourceCount;
				}

				newSourceItems.clear();

				firstChannel = false;
			}
		}

		// Second pass, for stations outside the study country.  The study channels aren't involved, each station is
		// considered only as it actually appears in the baseline scenario, except for replication to digital if
		// needed.  The undesired search considers only stations in the study country.

		for (SourceEditDataTV theSource : theSources) {

			if ((null == studyCountry) || (studyCountry.key == theSource.country.key)) {
				continue;
			}

			int channel = theSource.channel;
			if (theSource.service.serviceType.digital) {
				studySource = theSource;
			} else {
				if (null == theSource.originalSourceKey) {
					originalSource = theSource;
				} else {
					aSource = study.getSource(theSource.originalSourceKey);
					if ((null == aSource) || (Source.RECORD_TYPE_TV != aSource.recordType)) {
						if (null != errors) {
							errors.reportError("An original record needed for replication does not exist.");
						}
						return false;
					}
					originalSource = (SourceEditDataTV)aSource;
				}
				studySource = null;
				if (originalSource.isLocked && (null != originalSource.extDbKey) &&
						(null != originalSource.extRecordID)) {
					studySource = study.findSharedReplicationSource(originalSource.extDbKey,
						originalSource.extRecordID, channel);
				} else {
					studySource = sharedReplications.get(channel - minChannel).get(originalSource.key);
				}
				if (null == studySource) {
					studySource = originalSource.replicate(channel, errors);
					if (null == studySource) {
						return false;
					}
					study.addOrReplaceSource(studySource);
					if (!originalSource.isLocked || (null == originalSource.extRecordID)) {
						sharedReplications.get(channel - minChannel).put(originalSource.key, studySource);
					}
				}
			}

			newSourceItems.add(new Scenario.SourceListItem(studySource.key.intValue(), true, false, false));

			serviceTypeKey = studySource.service.serviceType.key;
			countryKey = studySource.country.key;
			frequencyOffsetKey = studySource.frequencyOffset.key;

			countStation = true;

			for (SourceEditDataTV otherSource : theSources) {

				if (isAborted()) {
					return false;
				}

				if (studyCountry.key != otherSource.country.key) {
					continue;
				}

				if (null == otherSource.originalSourceKey) {
					otherOriginalSource = otherSource;
				} else {
					aSource = study.getSource(otherSource.originalSourceKey);
					if ((null == aSource) || (Source.RECORD_TYPE_TV != aSource.recordType)) {
						if (null != errors) {
							errors.reportError("An original record needed for replication does not exist.");
						}
						return false;
					}
					otherOriginalSource = (SourceEditDataTV)aSource;
				}

				if (otherOriginalSource.service.serviceType.digital) {
					ixServiceTypeKey = otherOriginalSource.service.serviceType.key;
					ixFrequencyOffsetKey = otherOriginalSource.frequencyOffset.key;
					emissionMaskKey = otherOriginalSource.emissionMask.key;
				} else {
					ixServiceTypeKey = otherOriginalSource.service.digitalService.serviceType.key;
					ixFrequencyOffsetKey = 0;
					if (otherOriginalSource.service.digitalService.serviceType.needsEmissionMask) {
						emissionMaskKey = EmissionMask.getDefaultObject().key;
					} else {
						emissionMaskKey = 0;
					}
				}

				distance = distanceBetween(studySource, otherOriginalSource, kmPerDeg, chkDTSDist);

				countPair = true;

				for (delta = -1; delta <= 1; delta++) {

					ixChannel = channel + delta;

					if ((ixChannel < minChannel) || (ixChannel > maxChannel)) {
						continue;
					}

					if (channel < 5) {
						if (ixChannel > 4) {
							continue;
						}
					} else {
						if (channel < 7) {
							if ((ixChannel < 5) || (ixChannel > 6)) {
								continue;
							}
						} else {
							if (channel < 14) {
								if ((ixChannel < 7) || (ixChannel > 13)) {
									continue;
								}
							}
						}
					}

					ixRule = null;

					for (IxRuleEditData theRule : theRules) {

						if (theRule.country.key != countryKey) {
							continue;
						}
						if (theRule.serviceType.key != serviceTypeKey) {
							continue;
						}
						if (theRule.undesiredServiceType.key != ixServiceTypeKey) {
							continue;
						}
						if (delta != theRule.channelDelta.delta) {
							continue;
						}
						if ((channel < theRule.channelBand.firstChannel) ||
								(channel > theRule.channelBand.lastChannel)) {
							continue;
						}
						if ((IxRule.FREQUENCY_OFFSET_WITH == theRule.frequencyOffset) &&
								((0 == frequencyOffsetKey) || (0 == ixFrequencyOffsetKey) ||
								(frequencyOffsetKey == ixFrequencyOffsetKey))) {
							continue;
						}
						if ((IxRule.FREQUENCY_OFFSET_WITHOUT == theRule.frequencyOffset) &&
								((0 != frequencyOffsetKey) && (0 != ixFrequencyOffsetKey) &&
								(frequencyOffsetKey != ixFrequencyOffsetKey))) {
							continue;
						}
						if ((0 != theRule.emissionMask.key) &&
								((0 == emissionMaskKey) || (theRule.emissionMask.key != emissionMaskKey))) {
							continue;
						}

						ixRule = theRule;
						break;
					}

					if ((null == ixRule) || (distance > ixRule.distance)) {
						continue;
					}

					if (countStation) {
						stationCount++;
						countStation = false;
					}
					if (countPair) {
						pairCount++;
						countPair = false;
					}

					if ((ixChannel == otherOriginalSource.channel) &&
							otherOriginalSource.service.serviceType.digital) {
						ixSource = otherOriginalSource;
					} else {
						ixSource = null;
						if (otherOriginalSource.isLocked && (null != otherOriginalSource.extDbKey) &&
								(null != otherOriginalSource.extRecordID)) {
							ixSource = study.findSharedReplicationSource(otherOriginalSource.extDbKey,
								otherOriginalSource.extRecordID, ixChannel);
						} else {
							ixSource = sharedReplications.get(ixChannel - minChannel).get(otherOriginalSource.key);
						}
						if (null == ixSource) {
							ixSource = otherOriginalSource.replicate(ixChannel, errors);
							if (null == ixSource) {
								return false;
							}
							study.addOrReplaceSource(ixSource);
							if (!otherOriginalSource.isLocked || (null == otherOriginalSource.extRecordID)) {
								sharedReplications.get(ixChannel - minChannel).put(otherOriginalSource.key, ixSource);
							}
						}
					}

					newSourceItems.add(new Scenario.SourceListItem(ixSource.key.intValue(), false, true, false));
				}
			}

			if (newSourceItems.size() > 1) {

				newScenario = new ScenarioEditData(study, "Pair", "Pair study", newSourceItems);
				study.scenarioData.addOrReplace(newScenario);

				theItem = new ScenarioSortItem(newScenario, studySource, useLatitude);
				sortedScenarios.add(theItem);

				scenarioCount++;
				scenarioSourceCount += theItem.sourceCount;
			}

			newSourceItems.clear();
		}

		// Check the run count based on the total number of scenarios.  Due to startup overhead in each engine process,
		// too many runs for too few scenarios would actually be slower.  The initial value of runCount is the maximum,
		// this will only reduce the count.

		if (scenarioCount > 0) {
			int maxRunCount = scenarioCount / MIN_SCENARIOS_PER_RUN;
			if (maxRunCount < 1) {
				maxRunCount = 1;
			}
			if (maxRunCount < runCount) {
				runCount = maxRunCount;
			}
		}

		// Report statistics as log messages to the error logger, caller will display them as desired.

		if (null != errors) {
			errors.logMessage("Stations in baseline scenario: " + AppCore.formatCount(theSources.size()));
			errors.logMessage("   Stations involved in pairs: " + AppCore.formatCount(stationCount));
			errors.logMessage("   Unique pairs to be studied: " + AppCore.formatCount(pairCount));
			errors.logMessage("         Pair study scenarios: " + AppCore.formatCount(scenarioCount));
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Compute a distance between a desired and undesired source, reduced by the rule extra distance for the desired,
	// see getRuleExtraDistance() in SourceEditDataTV.  This can easily return a negative number.  This can get
	// complicated for DTS sources, the distance needed is a minimum to any one desired DTS transmitter, possibly
	// checking individual undesired transmitters as well.  See ExtDbRecordTV.addUndesireds() for details.

	private double distanceBetween(SourceEditDataTV desired, SourceEditDataTV undesired, double kmPerDeg,
			boolean chkDTSDist) {

		double dist = 99999., extdist, dist1;

		if (desired.isParent) {
			for (SourceEditDataTV dtsDesired : desired.getDTSSources()) {
				if (dtsDesired.siteNumber > 0) {
					extdist = dtsDesired.getRuleExtraDistance();
					if (undesired.isParent && chkDTSDist) {
						for (SourceEditDataTV dtsUndesired : undesired.getDTSSources()) {
							if (dtsUndesired.siteNumber > 0) {
								dist1 = dtsDesired.location.distanceTo(dtsUndesired.location, kmPerDeg) - extdist;
								if (dist1 < dist) {
									dist = dist1;
								}
							}
						}
					} else {
						dist1 = dtsDesired.location.distanceTo(undesired.location, kmPerDeg) - extdist;
						if (dist1 < dist) {
							dist = dist1;
						}
					}
				}
			}
		} else {
			extdist = desired.getRuleExtraDistance();
			if (undesired.isParent && chkDTSDist) {
				for (SourceEditDataTV dtsUndesired : undesired.getDTSSources()) {
					if (dtsUndesired.siteNumber > 0) {
						dist1 = desired.location.distanceTo(dtsUndesired.location, kmPerDeg) - extdist;
						if (dist1 < dist) {
							dist = dist1;
						}
					}
				}
			} else {
				dist = desired.location.distanceTo(undesired.location, kmPerDeg) - extdist;
			}
		}

		return dist;
	}
}
