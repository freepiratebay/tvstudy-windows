//
//  StudyBuildTV6FM.java
//  TVStudy
//
//  Copyright (c) 2016-2018 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core;

import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;
import gov.fcc.tvstudy.core.geo.*;

import java.util.*;
import java.sql.*;
import java.io.*;
import java.text.*;


//=====================================================================================================================
// StudyBuild subclass to manage building a TV channel 6 <-> FM interference study.  Properties are set directly then
// initialize() is called, followed by buildStudy().

public class StudyBuildTV6FM extends StudyBuild {

	public int templateKey;

	public ExtDb fmExtDb;
	public ExtDb tvExtDb;

	private StudyEditData study;
	private SourceEditDataTV targetSource;


	//-----------------------------------------------------------------------------------------------------------------

	public StudyBuildTV6FM(String theDbID) {

		super(theDbID);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean initialize(ErrorLogger errors) {

		if (initialized) {
			return true;
		}

		if (!super.initialize(errors)) {
			return false;
		}

		// The study name must be set.

		if (null != studyName) {
			studyName = studyName.trim();
		}
		if ((null == studyName) || (0 == studyName.length())) {
			if (null != errors) {
				errors.reportError("Cannot build study, missing name.");
			}
			return false;
		}

		// If description is not set one will be generated, make it null if empty.

		if (null != studyDescription) {
			studyDescription = studyDescription.trim();
			if (0 == studyDescription.length()) {
				studyDescription = null;
			}
		}

		// All other properties must be valid.  TV station data may be null to skip TV undesired searches.

		if ((templateKey <= 0) ||
				(null == fmExtDb) || fmExtDb.deleted || (Source.RECORD_TYPE_FM != fmExtDb.recordType) ||
				((null != tvExtDb) && (tvExtDb.deleted || (Source.RECORD_TYPE_TV != tvExtDb.recordType)))) {
			if (null != errors) {
				errors.reportError("Cannot build study, invalid settings.");
			}
			return false;
		}

		initialized = true;

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Build the study.  The StatusLogger is not currently being used.

	public Study buildStudy(StatusLogger status, ErrorLogger errors) {

		if (!initialized) {
			if (null != errors) {
				errors.reportError("Cannot build study, object has not been initialized.");
			}
			return null;
		}

		// Create and open the new study.

		Integer defExtDbKey = null;
		if (null != record) {
			defExtDbKey = record.extDb.key;
		} else {
			if ((null != source) && (null != source.extDbKey)) {
				defExtDbKey = source.extDbKey;
			} else {
				if (null != tvExtDb) {
					defExtDbKey = tvExtDb.key;
				}
			}
		}

		Integer theKey = Study.createNewStudy(dbID, studyName, Study.STUDY_TYPE_TV6_FM, templateKey, defExtDbKey,
			studyFolderKey, errors);
		if (null == theKey) {
			return null;
		}

		Study theStudy = Study.getStudy(dbID, theKey, errors);
		if (null == theStudy) {
			return null;
		}

		// Create the editable study object and desired TV source object, replicate if needed.

		boolean result = false;

		try {

			study = new StudyEditData(theStudy);

			if (null != source) {
				targetSource = (SourceEditDataTV)source.deriveSource(study, source.isLocked, errors);
			} else {
				ExtDbRecordTV theRecord = (ExtDbRecordTV)record;
				targetSource = SourceEditDataTV.makeSourceTV(theRecord, study, true, errors);
			}

			if ((null != targetSource) && replicate) {
				study.addOrReplaceSource(targetSource);
				targetSource = targetSource.replicate(replicationChannel, errors);
			}

			// Check channel range for the study.

			if (null != targetSource) {

				if (6 != targetSource.channel) {
					if (null != errors) {
						errors.reportError("Cannot build study, the study record must be on channel 6.");
					}
				} else {

					// Build the scenario, save the study.

					if (buildScenario(errors)) {
						study.isDataChanged();
						if (study.save(errors)) {
							result = true;
							theStudy = study.study;
						}
					}
				}
			}

		} catch (Throwable t) {
			AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
			if (null != errors) {
				errors.reportError("Cannot build study, unexpected error: " + t);
			}
		}

		// Always invalidate the study object to be safe.  If anything went wrong, delete the study.

		if (null != study) {
			study.invalidate();
		}

		// Check for an abort before returning success.

		if (isAborted()) {
			if (null != errors) {
				errors.reportError("Study build canceled");
			}
			result = false;
		}

		if (result) {
			return theStudy;
		}

		Study.deleteStudy(dbID, theStudy.key, theStudy.lockCount, errors);
		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The study will contain one scenario that considers interference both to and from the target TV record, from and
	// to FM records.  Also third-party undesireds will be included, both TV and FM.  The target is always both desired
	// and undesired.  Other TV records added are only undesireds.  FM records may be both desired and undesired, or
	// just undesired, depending on culling distance checks for each case.

	private boolean buildScenario(ErrorLogger errors) {

		double kmPerDeg = study.getKilometersPerDegree();

		// Create the scenario and add that to the study, then add the target.

		ScenarioEditData scenario = new ScenarioEditData(study, targetSource.getCallSign() + " and FM IX");
		scenario.description = "Interference between " + targetSource.toString() + " and NCE FM stations";

		scenario.sourceData.addOrReplace(targetSource, true, true, true);

		study.scenarioData.addOrReplace(scenario);

		// Set the study description, if none provided use the scenario description.

		if (null == studyDescription) {
			study.description = scenario.description;
		} else {
			study.description = studyDescription;
		}

		// First add third-party undesired TV records that may interfere with the target, can do this with the normal
		// undesireds method in ExtDbRecordTV.  The scenario will only have one desired TV, the target.  There will be
		// a separate search later specifically for other TV channel 6 records that may interfere with desired FMs.
		// This is optional, this and the later TV6 search may be skipped.

		StringBuilder q;

		if (null != tvExtDb) {

			q = new StringBuilder();

			try {
				ExtDbRecordTV.addServiceTypeQueryTV(tvExtDb.type, tvExtDb.version, ExtDbRecord.FLAG_MATCH_SET,
					ExtDbRecord.FLAG_MATCH_ANY, q, false);
				ExtDbRecordTV.addRecordTypeQueryTV(tvExtDb.type, tvExtDb.version, false, q, true);
			} catch (IllegalArgumentException ie) {
			}

			if (ExtDbRecordTV.addRecords(tvExtDb, false, scenario, ExtDbSearch.SEARCH_TYPE_UNDESIREDS, q.toString(),
					null, 0., 0, 0, false, true, true, true, errors) < 0) {
				return false;
			}

			// Check for an abort, this is usually running on a secondary thread.

			if (isAborted()) {
				return false;
			}
		}

		// Do a search for FM records to pick up both undesireds to the target TV and desireds that need protection
		// from the target.  Both directions consider at least all NCE FM channels but apply different per-channel
		// culling distances.  For the undesired test, those are distances in a parameter table with defaults from FCC
		// rules 73.525.  Although those were developed for analog TV, they are reasonable for the distance check in
		// either case.  However the D/U curve-lookup portion of 73.525 may or may not be used for a digital, see the
		// study engine code for details.  To identify protected FMs from the TV as undesired, if the TV is analog it
		// is studied as a full-service FM on channel 199 using the normal FM-to-FM interference rules.  Note that may
		// include channels outside the NCE FM range due to the 53/54-channel rules.  For that case a culling distance
		// table is built first from the rules, that is built even if the target is digital because the table will also
		// be needed later in the search for other undesired TV channel 6 records.  If the TV is digital, parameters
		// provide a DTV to FM culling distance table, with defaults from the National Public Radio study "Comparison
		// of FM Broadcast Signal Interference Areas with Current Digital Television Receivers on Channel 6 to Analog
		// TV Receivers Assumed in 47 CFR 73.525", September 5, 2008.

		double[] cullToTV = study.getTV6FMDistance();

		double[] cullDTVtoFM = study.getTV6FMDistanceDtv();

		int ci, maxChans = (SourceFM.CHANNEL_MAX - SourceFM.CHANNEL_MIN) + 1;
		double[] cullTVtoFM = new double[maxChans];
		for (IxRuleEditData theRule : study.ixRuleData.getActiveRows()) {
			if (Source.RECORD_TYPE_FM == theRule.serviceType.recordType) {
				ci = 199 + theRule.channelDelta.delta - SourceFM.CHANNEL_MIN;
				if ((ci >= 0) && (ci < maxChans) && (theRule.distance > cullTVtoFM[ci])) {
					cullTVtoFM[ci] = theRule.distance;
				}
			}
		}

		double[] cullToFM;
		if (targetSource.service.isDigital()) {
			cullToFM = cullDTVtoFM;
		} else {
			cullToFM = cullTVtoFM;
		}

		// Build the search query.  Include all channels which have a non-zero culling distance in the FM to TV table,
		// or whichever of the DTV to FM or TV to FM tables apply to the target.  Exclude archived records.

		StringBuilder chans = new StringBuilder();
		char sep = '(';
		for (ci = 0; ci < maxChans; ci++) {
			if (((ci < cullToTV.length) && (cullToTV[ci] > 0.)) || ((ci < cullToFM.length) && (cullToFM[ci] > 0.))) {
				chans.append(sep);
				chans.append(String.valueOf(ci + SourceFM.CHANNEL_MIN));
				sep = ',';
			}
		}
		chans.append(')');

		q = new StringBuilder();

		try {
			ExtDbRecordFM.addMultipleChannelQueryFM(fmExtDb.type, fmExtDb.version, chans.toString(), q, false);
			ExtDbRecordFM.addServiceTypeQueryFM(fmExtDb.type, fmExtDb.version, ExtDbRecord.FLAG_MATCH_SET, q, true);
			ExtDbRecordFM.addRecordTypeQueryFM(fmExtDb.type, fmExtDb.version, false, q, true);
		} catch (IllegalArgumentException ie) {
		}

		// Do the search.

		LinkedList<ExtDbRecordFM> fmRecords = ExtDbRecordFM.findRecordsFM(fmExtDb, q.toString(), errors);
		if (null == fmRecords) {
			return false;
		}

		if (isAborted()) {
			return false;
		}

		// Apply an MX selection process, see comments in ExtDbRecordTV.removeAllMX().

		Comparator<ExtDbRecordFM> fmComp = new Comparator<ExtDbRecordFM>() {
			public int compare(ExtDbRecordFM theRecord, ExtDbRecordFM otherRecord) {
				if (theRecord.isPreferredRecord(otherRecord, true)) {
					return -1;
				}
				return 1;
			}
		};

		Collections.sort(fmRecords, fmComp);

		ExtDbRecordFM fmRecord;
		ListIterator<ExtDbRecordFM> fmLit;

		int recCount = fmRecords.size() - 1;
		for (int recIndex = 0; recIndex < recCount; recIndex++) {
			fmRecord = fmRecords.get(recIndex);
			fmLit = fmRecords.listIterator(recIndex + 1);
			while (fmLit.hasNext()) {
				if (ExtDbRecordFM.areRecordsMX(fmRecord, fmLit.next(), true, 0., 0.)) {
					fmLit.remove();
					recCount--;
				}
			}
		}

		// Scan the list and add records based on either/both of the culling distances.  If the distance passes either
		// distance check the record will be an undesired, if it passes the TV to FM check it is also a desired.  Keep
		// a separate list of the records that will be desireds to use those in the channel 6 search below.  Note the
		// FM to TV check is a straight site-to-site distance test.  The TV to FM check is a site-to-point check so
		// the rule-extra-distance (worst-case contour distance) is added to the culling distance.

		double distToFM, distToTV, dist;
		boolean include, isDesired;
		SourceEditDataFM fmSource;

		if (null != errors) {
			errors.clearErrors();
		} else {
			errors = new ErrorLogger(null, null);
		}

		ArrayList<SourceEditDataFM> fmSources = new ArrayList<SourceEditDataFM>();

		for (ExtDbRecordFM theRecord : fmRecords) {

			ci = theRecord.channel - SourceFM.CHANNEL_MIN;

			if (ci < cullToFM.length) {
				distToFM = cullToFM[ci];
			} else {
				distToFM = 0.;
			}

			if (ci < cullToTV.length) {
				distToTV = cullToTV[ci];
			} else {
				distToTV = 0.;
			}

			// Check distance, see comments in ExtDbRecordTV.addUndesireds().

			include = false;
			isDesired = false;

			if (targetSource.isParent) {
				for (SourceEditDataTV dtsSource : targetSource.getDTSSources()) {
					if (dtsSource.siteNumber > 0) {
						dist = dtsSource.location.distanceTo(theRecord.location, kmPerDeg);
						if (dist <= (distToFM + dtsSource.getRuleExtraDistance())) {
							include = true;
							isDesired = true;
							break;
						} else {
							if (dist <= distToTV) {
								include = true;
							}
						}
					}
				}
			} else {
				dist = targetSource.location.distanceTo(theRecord.location, kmPerDeg);
				if (dist <= (distToFM + targetSource.getRuleExtraDistance())) {
					include = true;
					isDesired = true;
				} else {
					if (dist <= distToTV) {
						include = true;
					}
				}
			}

			if (include) {

				fmSource = (SourceEditDataFM)SourceEditData.makeSource(theRecord, study, true, errors);
				if (null == fmSource) {
					if (errors.hasErrors()) {
						return false;
					}
					continue;
				}

				scenario.sourceData.addOrReplace(fmSource, isDesired, true);

				if (isDesired) {
					fmSources.add(fmSource);
				}
			}
		}

		if (isAborted()) {
			return false;
		}

		// Next, search for TV channel 6 records that are undesireds to the desired FMs.  Optional.

		if (null != tvExtDb) {

			q = new StringBuilder();

			try {
				ExtDbRecordTV.addChannelQueryTV(tvExtDb.type, tvExtDb.version, 6, 0, 0, q, false);
				ExtDbRecordTV.addServiceTypeQueryTV(tvExtDb.type, tvExtDb.version, ExtDbRecord.FLAG_MATCH_SET,
					ExtDbRecord.FLAG_MATCH_ANY, q, true);
				ExtDbRecordTV.addRecordTypeQueryTV(tvExtDb.type, tvExtDb.version, false, q, true);
			} catch (IllegalArgumentException ie) {
			}

			// Do the search.

			LinkedList<ExtDbRecordTV> tvRecords = ExtDbRecordTV.findRecordsTV(tvExtDb, q.toString(), errors);
			if (null == tvRecords) {
				return false;
			}

			if (isAborted()) {
				return false;
			}

			// Apply an MX selection process, see comments in ExtDbRecordTV.removeAllMX(), this time also exclude
			// records already in the scenario, or those MX to anything already in the scenario.

			ArrayList<SourceEditData> tvSources = scenario.sourceData.getSources(Source.RECORD_TYPE_TV);

			ListIterator<ExtDbRecordTV> tvLit = tvRecords.listIterator(0);
			ExtDbRecordTV tvRecord;
			SourceEditDataTV tvSource;

			while (tvLit.hasNext()) {
				tvRecord = tvLit.next();
				for (SourceEditData theSource : tvSources) {
					tvSource = (SourceEditDataTV)theSource;
					if (tvRecord.extRecordID.equals(tvSource.extRecordID) ||
							ExtDbRecordTV.areRecordsMX(tvRecord, tvSource, true, 0., 0.)) {
						tvLit.remove();
						break;
					}
				}
			}

			Comparator<ExtDbRecordTV> tvComp = new Comparator<ExtDbRecordTV>() {
				public int compare(ExtDbRecordTV theRecord, ExtDbRecordTV otherRecord) {
					if (theRecord.isPreferredRecord(otherRecord, true)) {
						return -1;
					}
					return 1;
				}
			};

			Collections.sort(tvRecords, tvComp);

			recCount = tvRecords.size() - 1;
			for (int recIndex = 0; recIndex < recCount; recIndex++) {
				tvRecord = tvRecords.get(recIndex);
				tvLit = tvRecords.listIterator(recIndex + 1);
				while (tvLit.hasNext()) {
					if (ExtDbRecordTV.areRecordsMX(tvRecord, tvLit.next(), true, 0., 0.)) {
						tvLit.remove();
						recCount--;
					}
				}
			}

			// Now add any undesired TV channel 6 records that pass the distance check to one of the desired FM
			// sources.  The distance check includes the rule extra distance in this case.

			for (ExtDbRecordTV theRecord : tvRecords) {

				if (theRecord.service.isDigital()) {
					cullToFM = cullDTVtoFM;
				} else {
					cullToFM = cullTVtoFM;
				}

				include = false;

				for (SourceEditDataFM theSource : fmSources) {

					ci = theSource.channel - SourceFM.CHANNEL_MIN;

					if (ci < cullToFM.length) {
						distToFM = cullToFM[ci];
					} else {
						distToFM = 0.;
					}

					if (theRecord.service.isDTS) {
						for (ExtDbRecordTV dtsRecord : theRecord.dtsRecords) {
							dist = dtsRecord.location.distanceTo(theSource.location, kmPerDeg);
							if (dist <= (distToFM + dtsRecord.getRuleExtraDistance(study))) {
								include = true;
								break;
							}
						}
					} else {
						dist = theRecord.location.distanceTo(theSource.location, kmPerDeg);
						if (dist <= (distToFM + theRecord.getRuleExtraDistance(study))) {
							include = true;
						}
					}

					if (include) {
						break;
					}
				}

				if (include) {

					tvSource = (SourceEditDataTV)SourceEditData.makeSource(theRecord, study, true, errors);
					if (null == tvSource) {
						if (errors.hasErrors()) {
							return false;
						}
						continue;
					}

					scenario.sourceData.addOrReplace(tvSource, false, true);
				}
			}

			if (isAborted()) {
				return false;
			}
		}

		// Finally, add FM undesireds for the FM desireds with the normal method in ExtDbRecordFM.

		q = new StringBuilder();

		try {
			ExtDbRecordFM.addServiceTypeQueryFM(fmExtDb.type, fmExtDb.version, ExtDbRecord.FLAG_MATCH_SET, q, false);
			ExtDbRecordFM.addRecordTypeQueryFM(fmExtDb.type, fmExtDb.version, false, q, true);
		} catch (IllegalArgumentException ie) {
		}

		if (ExtDbRecordFM.addRecords(fmExtDb, scenario, ExtDbSearch.SEARCH_TYPE_UNDESIREDS, q.toString(), null, 0.,
				0, 0, false, true, true, true, errors) < 0) {
			return false;
		}

		return true;
	}
}
