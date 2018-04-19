//
//  ErrorLogger.java
//  TVStudy
//
//  Copyright (c) 2012-2015 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core;

import java.util.*;
import java.io.*;


//=====================================================================================================================
// Class to manage error logging and reporting.  This may add messages to an internal log, write to a PrintStream,
// both, or neither.  Classes that do not use Swing use ErrorLogger directly, Swing-dependent classes use the subclass
// gui.ErrorReporter which adds options for dialog reporting and gui.RootEditor integration.  Even in a windowed
// environment ErrorLogger may be used to aggregate errors on background threads.  This class is fully synchronized,
// so it is thread-safe as long as the stream and log objects are not shared between instances.

// In addition to logging and real-time error reporting, an instance also has a secondary message log that can have
// arbitrary messages appended with the logMessage() method.  Those messages are never sent anywhere else or reported
// directly, and don't affect error handling in any way.  Messages can be retrieved if desired with the hasMessages()
// and getMessages() methods.

public class ErrorLogger {

	protected PrintStream errorStream;

	protected StringBuilder errorLog;

	protected boolean errorsReported;
	protected int lastErrorType = AppCore.INFORMATION_MESSAGE;

	protected StringBuilder messageLog;


	//-----------------------------------------------------------------------------------------------------------------
	// Instance constructors, default is just the internal log, or caller may specify stream and/or log.  Either/both
	// can be shared by multiple ErrorLogger instances.  Either/both may also be null, so a report-nothing instance is
	// also possible if desired.  That is still useful for the hasErrors() behavior and the secondary message log.

	public ErrorLogger() {

		errorLog = new StringBuilder();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public ErrorLogger(StringBuilder theErrorLog) {

		errorLog = theErrorLog;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public ErrorLogger(PrintStream theErrorStream) {

		errorStream = theErrorStream;
		errorLog = new StringBuilder();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public ErrorLogger(PrintStream theErrorStream, StringBuilder theErrorLog) {

		errorStream = theErrorStream;
		errorLog = theErrorLog;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Close and clear the stream output.  Other logging is unaffected.

	public synchronized void closeStream() {

		if (null != errorStream) {
			try {
				errorStream.close();
			} catch (Exception e) {
			};
			errorStream = null;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Methods to report a message.  The core method is not public so subclasses can alter behavior of the public API.

	public void reportError(String mesg, int type) {
		doReportError(mesg, type);
	}

	public void reportError(String mesg) {
		doReportError(mesg, AppCore.ERROR_MESSAGE);
	}

	public void reportWarning(String mesg) {
		doReportError(mesg, AppCore.WARNING_MESSAGE);
	}

	public void reportMessage(String mesg) {
		doReportError(mesg, AppCore.INFORMATION_MESSAGE);
	}

	protected synchronized void doReportError(String mesg, int type) {

		if ((null != errorStream) || (null != errorLog)) {

			StringBuilder theMesg = new StringBuilder();
			switch (type) {
				case AppCore.ERROR_MESSAGE:
					theMesg.append("ERROR: ");
					break;
				case AppCore.WARNING_MESSAGE:
					theMesg.append("Warning: ");
					break;
			}
			theMesg.append(mesg);
			theMesg.append('\n');

			if (null != errorStream) {
				errorStream.append(theMesg);
			}

			if (null != errorLog) {
				errorLog.append(theMesg);
			}
		}

		errorsReported = true;
		lastErrorType = type;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This is equivalent to reportWarning() here, it exists for subclasses that distinguish between normal errors and
	// those that occur during user input validation.

	public void reportValidationError(String mesg) {
		doReportError(mesg, AppCore.WARNING_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public synchronized String toString() {

		if (null != errorLog) {
			return errorLog.toString();
		}

		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------
	// A manager object can use the errorsReported flag to locally detect errors.  This clears the flag indicating
	// errors have been reported, but it does not actually clear the error log itself.

	public synchronized void clearErrors() {

		errorsReported = false;
		lastErrorType = AppCore.INFORMATION_MESSAGE;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public synchronized boolean hasErrors() {

		return errorsReported;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The type of the last error reported.  When reporting an accumulated log of errors the last one is probably the
	// most relevant to the overall result; a severe error will abort a process and will be the last thing logged.

	public synchronized int getLastErrorType() {

		return lastErrorType;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Auxiliary message logging API, just accumulate and return if asked.  Newlines are added here.

	public synchronized void logMessage(String theMessage) {

		if (null == messageLog) {
			messageLog = new StringBuilder(theMessage);
		} else {
			messageLog.append('\n');
			messageLog.append(theMessage);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public synchronized boolean hasMessages() {

		return (null != messageLog);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public synchronized String getMessages() {

		if (null == messageLog) {
			return "";
		}
		return messageLog.toString();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Unlike clearErrors() this actually clears the message log, meaning previous messages are gone.

	public synchronized void clearMessages() {

		messageLog = null;
	}
}
