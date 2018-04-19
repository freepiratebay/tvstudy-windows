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

	// If a new study is created this is passed to createNewStudy(), but it is not used or verified here.

	public Integer studyFolderKey;

	// If both source and record are set, source is preferred.  The target may be replicated.

	public SourceEditData source;
	public ExtDbRecord record;

	public boolean replicate;
	public int replicationChannel;

	protected boolean initialized;

	private long logStartTime;

	private boolean aborted;


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
	// Set a "zero time" to merge timestamped messages with a study engine log.

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
	// Typically buildStudy() will be called  on a background thread, so subclasses must support an abort mechanism.
	// The buildStudy() implementation can poll isAborted(), or the subclass can override abort() which will be
	// called on the event thread.  When aborted, buildStudy() must cease operations and return as soon as possible.
	// If buildStudy() has operations that may not be gracefully stopped, e.g. external processes, abortWillCancel()
	// should be overridden to return false during those operations.  In that case the user will be prompted to
	// confirm the abort since it might lead to side-effects, also in that case the run thread will be interrupted.

	public boolean abortWillCancel() {

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void abort() {

		aborted = true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isAborted() {

		return aborted;
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
