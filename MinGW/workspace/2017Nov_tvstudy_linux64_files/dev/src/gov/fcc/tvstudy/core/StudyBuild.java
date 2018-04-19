//
//  StudyBuild.java
//  TVStudy
//
//  Copyright (c) 2016-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core;

import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;


//=====================================================================================================================
// Abstract superclass for objects that run an automatic study build process, e.g. IX check.  Used by StudyRunPanel.
// This will always involve a target record which may be a source or a data set record, and several common properties
// for run configuration.  See StudyManager and StudyRunPanel.

public abstract class StudyBuild {

	public final String dbID;

	// Study name and description may be set before initializing, or generated during study build.  Any validation is
	// the responsibility of the subclass initialize().

	public String studyName;
	public String studyDescription;

	// If both source and record are set, source is preferred.  The target may be replicated.

	public SourceEditData source;
	public ExtDbRecord record;

	public boolean replicate;
	public int replicationChannel;

	protected boolean initialized;

	// Subclass may set a "zero time" to merge timestamped messages with engine log, see setLogStartTime(),
	// getLogStartTime(), and timestampMessage().

	private long logStartTime;

	// Typically buildStudy() runs on a background thread and can take a considerable time.  Code in the buildStudy()
	// implementation should check isAborted() and abort promptly, also override abort() if appropriate.

	private boolean abort;


	//-----------------------------------------------------------------------------------------------------------------

	protected StudyBuild(String theDbID) {

		dbID = theDbID;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called once properties are set, does validation and setup.  If this fails, to be safe the object should be
	// discarded.  But subclasses may support repeat calls to initialize() with altered properties if desired, the
	// superclass does not forbid that.  However as soon as this returns true the properties must not be altered and
	// this should not be called again.  This must be overridden; the subclass must set the initialized flag.

	public boolean initialize() {
		return initialize(null);
	}

	public boolean initialize(ErrorLogger errors) {

		if (initialized) {
			return true;
		}

		// If a source object is provided ignore the record object, also confirm the source is valid.  Otherwise a
		// record object must be provided.

		if (null != source) {
			record = null;
			if (!source.isDataValid()) {
				if (null != errors) {
					errors.reportError("Cannot build study, invalid source object.");
				}
				return false;
			}
		} else {
			if (null == record) {
				if (null != errors) {
					errors.reportError("Cannot build study, missing record object.");
				}
				return false;
			}
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isInitialized() {

		return initialized;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set the log start time to the current time.  Can only be done once.

	protected void setLogStartTime() {

		if (0L == logStartTime) {
			logStartTime = System.currentTimeMillis();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public long getLogStartTime() {

		return logStartTime;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Prefix a message with a timestamp formatted to match the study engine logs.  If the log start time is not set
	// just return the argument.

	public String timestampMessage(String message) {

		if (logStartTime > 0L) {
			long theTime = System.currentTimeMillis() - logStartTime;
			int hours = (int)(theTime / 3600000L);
			int minutes = (int)((theTime % 3600000L) / 60000L);
			int seconds = (int)((theTime % 60000L) / 1000L);
			int millis = (int)(theTime % 1000L);
			return String.format("%3d:%02d:%02d.%03d - %s", hours, minutes, seconds, millis, message);
		}

		return message;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This is a request only, it is dependent on the buildStudy() implementation checking the abort flag.

	public void abort() {

		abort = true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isAborted() {

		return abort;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called to build the study.  Returns the study model (complete, as edited) or null on error.

	public Study buildStudy() {
		return buildStudy(null, null);
	}

	public Study buildStudy(ErrorLogger errors) {
		return buildStudy(null, errors);
	}

	public Study buildStudy(StatusLogger status) {
		return buildStudy(status, null);
	}

	public abstract Study buildStudy(StatusLogger status, ErrorLogger errors);
}
