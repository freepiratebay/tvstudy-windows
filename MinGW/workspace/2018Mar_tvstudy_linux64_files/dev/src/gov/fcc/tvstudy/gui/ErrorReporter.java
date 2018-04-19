//
//  ErrorReporter.java
//  TVStudy
//
//  Copyright (c) 2012-2015 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.gui.*;
import gov.fcc.tvstudy.gui.editor.*;

import java.util.*;
import java.io.*;

import java.awt.*;
import java.awt.event.*;

import javax.swing.*;


//=====================================================================================================================
// Subclass of ErrorLogger for use in windowed interface.  This adds default display of errors in a dialog.  Also an
// instance can connect to a RootEditor for collecting errors during state validation with deferred reporting (see
// reportValidationError() in RootEditor).

public class ErrorReporter extends ErrorLogger {

	private RootEditor rootEditor;

	private Window parent;
	private String defaultTitle;

	private String title;


	//-----------------------------------------------------------------------------------------------------------------
	// Reports are by dialog so parent and title arguments are also needed.  When using this subclass, the internal
	// error log is usually not desired so is null by default.  Note the parent window is not required and may be null,
	// it is for window ownership and position only; all dialogs are shown with AppController.showMessage() which will
	// use a default behavior if the parent window is null.  If the default title is null there will never be a dialog
	// shown.  Otherwise the title can be changed for individual reports, the reporting methods all have forms that
	// take a title as argument, or the transient title can be provided with setTitle(), followed by clearTitle() to
	// return to default.  The transient title may be null to temporarily suppress dialogs.

	public ErrorReporter(RootEditor theEditor, Window theParent, String theTitle) {

		super((StringBuilder)null);

		rootEditor = theEditor;

		parent = theParent;
		defaultTitle = theTitle;

		title = defaultTitle;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public ErrorReporter(Window theParent, String theTitle) {

		super((StringBuilder)null);

		parent = theParent;
		defaultTitle = theTitle;

		title = defaultTitle;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public ErrorReporter(RootEditor theEditor, Window theParent, String theTitle, PrintStream theErrorStream,
			StringBuilder theErrorLog) {

		super(theErrorStream, theErrorLog);

		rootEditor = theEditor;

		parent = theParent;
		defaultTitle = theTitle;

		title = defaultTitle;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public Window getWindow() {

		return parent;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void setTitle(String theTitle) {

		if (null != defaultTitle) {
			title = theTitle;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void clearTitle() {

		title = defaultTitle;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getTitle() {

		return title;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Method to report an error during editor state validation.  If a RootEditor is set it handles the reporting, it
	// will show a dialog as appropriate; otherwise direct dialog output occurs depending on the title string.  Also
	// log this with the superclass as a warning.

	public void reportValidationError(String mesg) {
		reportValidationError(title, mesg);
	}

	public void reportValidationError(String theTitle, String mesg) {

		if (null == title) {
			theTitle = null;
		}

		if (null != rootEditor) {

			rootEditor.reportValidationError(parent, theTitle, mesg, AppCore.WARNING_MESSAGE);

		} else {

			if (null != theTitle) {
				doShowMessage(parent, mesg, theTitle, AppCore.WARNING_MESSAGE);
			}
		}

		doReportError(mesg, AppCore.WARNING_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Method to report an error from contexts other than input validation, this is never reported to the RootEditor.
	// It may show a dialog, with title string behavior as above, plus stream and log output as usual.  This includes
	// overrides of the superclass reporting methods so that classes using just the ErrorLogger API will still get
	// dialog reporting when used from a GUI context.

	public void reportError(String mesg, int type) {
		reportError(title, mesg, type);
	}

	public void reportError(String mesg) {
		reportError(title, mesg, AppCore.ERROR_MESSAGE);
	}

	public void reportError(String theTitle, String mesg) {
		reportError(theTitle, mesg, AppCore.ERROR_MESSAGE);
	}

	public void reportWarning(String mesg) {
		reportError(title, mesg, AppCore.WARNING_MESSAGE);
	}

	public void reportWarning(String theTitle, String mesg) {
		reportError(theTitle, mesg, AppCore.WARNING_MESSAGE);
	}

	public void reportMessage(String mesg) {
		reportError(title, mesg, AppCore.INFORMATION_MESSAGE);
	}

	public void reportMessage(String theTitle, String mesg) {
		reportError(theTitle, mesg, AppCore.INFORMATION_MESSAGE);
	}

	public void reportError(String theTitle, String mesg, int type) {

		if ((null != title) && (null != theTitle)) {
			doShowMessage(parent, mesg, theTitle, type);
		}

		doReportError(mesg, type);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Show the message log if it exists and dialogs are not disabled.

	public void showMessages() {
		showMessages(title);
	}

	public void showMessages(String theTitle) {

		if ((null != messageLog) && (null != title) && (null != theTitle)) {
			String mesg = messageLog.toString();
			doShowMessage(parent, mesg, theTitle, AppCore.INFORMATION_MESSAGE);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Display a message using AppController.showMessage() or showLongMessage() based on length.  Either line count or
	// line length may trigger the long-message form, which uses a scrollable text area.

	private static void doShowMessage(Component parent, String message, String title, int messageType) {

		int nLine = 0, nChar = 0, maxChar = 0;
		for (int i = 0; i < message.length(); i++) {
			if ('\n' == message.charAt(i)) {
				nLine++;
				if (nChar > maxChar) {
					maxChar = nChar;
				}
				nChar = 0;
			} else {
				nChar++;
			}
		}
		if (nChar > 0) {
			nLine++;
			if (nChar > maxChar) {
				maxChar = nChar;
			}
		}
		if ((maxChar > 120) || (nLine > 10)) {
			AppController.showLongMessage(parent, message, title, messageType);
		} else {
			AppController.showMessage(parent, message, title, messageType);
		}
	}
}
