//
//  StudyBuildWireless.java
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
// StudyBuild subclass to manage building a wireless-to-TV interference study.  Properties are set directly then
// initialize() is called, followed by buildStudy().

public class StudyBuildWireless extends StudyBuild {

	public int templateKey;

	public ExtDb wirelessExtDb;
	public ExtDb maskingExtDb;

	public String frequency;
	public String bandwidth;


	//-----------------------------------------------------------------------------------------------------------------

	public StudyBuildWireless(String theDbID) {

		super(theDbID);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Verify all properties are valid.

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

		// If description not set one will be generated, set null if empty.

		if (null != studyDescription) {
			studyDescription = studyDescription.trim();
			if (0 == studyDescription.length()) {
				studyDescription = null;
			}
		}

		// All other properties must be valid.

		if ((templateKey <= 0) || (null == wirelessExtDb) || wirelessExtDb.deleted ||
				((null != maskingExtDb) && maskingExtDb.deleted)) {
			if (null != errors) {
				errors.reportError("Cannot build study, invalid settings.");
			}
			return false;
		}

		// Check parameter values, remove empty strings.

		if (null != frequency) {
			frequency = frequency.trim();
			if (0 == frequency.length()) {
				frequency = null;
			}
		}

		if (null != bandwidth) {
			bandwidth = bandwidth.trim();
			if (0 == bandwidth.length()) {
				bandwidth = null;
			}
		}

		initialized = true;

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Build and open the new study.  The study default data set will be the set containing the selected record, or
	// if that is null the set selected for the TV undesired search.  If both are null the study does not have a
	// default; wireless data sets cannot be study defaults.  Note the StatusLogger is currenlty not being used.

	public Study buildStudy(StatusLogger status, ErrorLogger errors) {

		if (!initialized) {
			if (null != errors) {
				errors.reportError("Cannot build study, object has not been initialized.");
			}
			return null;
		}

		Integer defExtDbKey = null;
		if (null != record) {
			defExtDbKey = record.extDb.key;
		} else {
			if ((null != source) && (null != source.extDbKey)) {
				defExtDbKey = source.extDbKey;
			} else {
				if (null != maskingExtDb) {
					defExtDbKey = maskingExtDb.key;
				}
			}
		}

		Integer theKey = Study.createNewStudy(dbID, studyName, Study.STUDY_TYPE_TV_OET74, templateKey, defExtDbKey,
			studyFolderKey, errors);
		if (null == theKey) {
			return null;
		}

		Study theStudy = Study.getStudy(dbID, theKey, errors);
		if (null == theStudy) {
			return null;
		}

		// Create the editable study object and desired TV source object, replicate if needed.

		StudyEditData study = null;
		boolean result = false;

		try {

			study = new StudyEditData(theStudy);

			SourceEditDataTV theSource = null;
			if (null != source) {
				theSource = (SourceEditDataTV)source.deriveSource(study, source.isLocked, errors);
			} else {
				ExtDbRecordTV theRecord = (ExtDbRecordTV)record;
				theSource = SourceEditDataTV.makeSourceTV(theRecord, study, true, errors);
			}

			if ((null != theSource) && replicate) {
				study.addOrReplaceSource(theSource);
				theSource = theSource.replicate(replicationChannel, errors);
			}

			// Check channel range for the study, could not be done until the study was created.

			if (null != theSource) {

				int minChannel = study.getMinimumChannel(), maxChannel = study.getMaximumChannel();
				if ((theSource.channel < minChannel) || (theSource.channel > maxChannel)) {
					if (null != errors) {
						errors.reportError("Cannot build study, the channel must be in the range " + minChannel +
							" to " + maxChannel + ".");
					}
				} else {

					// Create a scenario and add the desired source.  The source is flagged permanent so it can't be
					// deleted or have the desired flag changed, also the undesired flag is not set, since no other
					// desireds can exist that flag is meaningless.

					ScenarioEditData scenario = new ScenarioEditData(study, theSource.getCallSign() + " wireless IX");
					scenario.description = "Wireless interference to " + theSource.toString();

					scenario.sourceData.addOrReplace(theSource, true, false, true);

					study.scenarioData.addOrReplace(scenario);

					// Set the study description, if none provided just use the scenario description.

					if (null == studyDescription) {
						study.description = scenario.description;
					} else {
						study.description = studyDescription;
					}

					// Set wireless parameters in the scenario.

					ParameterEditData theParam = scenario.getParameter(Parameter.PARAM_SCEN_WL_FREQ);
					if (null != theParam) {
						for (int i = 0; i < theParam.parameter.valueCount; i++) {
							theParam.value[i] = frequency;
						}
					}
					theParam = scenario.getParameter(Parameter.PARAM_SCEN_WL_BW);
					if (null != theParam) {
						for (int i = 0; i < theParam.parameter.valueCount; i++) {
							theParam.value[i] = bandwidth;
						}
					}

					// Add undesired wireless records to the scenario, and optionally TV undesireds for masking
					// inteference.  If all goes well, save the study.  Check for abort along the way, this is usually
					// running on a secondary thread so that flag may be set from another thread.

					int wlCount = 0, tvCount = 0;

					if (!isAborted()) {
						wlCount = ExtDbRecordWL.addRecords(wirelessExtDb, scenario, ExtDbSearch.SEARCH_TYPE_UNDESIREDS,
							"", null, 0., errors);
					}

					if (!isAborted() && (wlCount >= 0) && (null != maskingExtDb)) {
						StringBuilder q = new StringBuilder();
						ExtDbRecordTV.addServiceTypeQueryTV(maskingExtDb.type, maskingExtDb.version,
							ExtDbRecord.FLAG_MATCH_SET, ExtDbRecord.FLAG_MATCH_ANY, q, false);
						ExtDbRecordTV.addRecordTypeQueryTV(maskingExtDb.type, maskingExtDb.version, false, q, true);
						tvCount = ExtDbRecordTV.addRecords(maskingExtDb, false, scenario,
							ExtDbSearch.SEARCH_TYPE_UNDESIREDS, q.toString(), null, 0., 0, 0, false, true, true, true,
							errors);
					}

					if (!isAborted() && (wlCount >= 0) && (tvCount >= 0)) {
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
}
