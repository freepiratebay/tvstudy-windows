//
//  StatusLogger.java
//  TVStudy
//
//  Copyright (c) 2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core;


//=====================================================================================================================
// Interface for objects than can accept status and log messages from background processes for display in UI.  Allows
// UI classes to get updates from core methods.  Supports a cancel-operation feature, code may poll isCanceled() and
// abort if it returns true, however that behavior is optional on both sides.

public interface StatusLogger {


	//-----------------------------------------------------------------------------------------------------------------
	// Report immediate status to be displayed in a transient UI context distinct from a running message log.

	public void reportStatus(String message);


	//-----------------------------------------------------------------------------------------------------------------
	// Write a message to a persistent log, which may or may not be immediately visible in UI.

	public void logMessage(String message);


	//-----------------------------------------------------------------------------------------------------------------
	// Write a transient message that should appear in sequence with persistent messages if the log is immediately
	// visible in UI, otherwise may be discarded.  When these do appear they should not persist in the log, subsequent
	// showMessage() calls overwrite previous until the next logMessage() overwrites that with a permanent message.

	public void showMessage(String message);


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isCanceled();
}
