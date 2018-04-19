//
//  AppDialog.java
//  TVStudy
//
//  Copyright (c) 2012-2015 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;

import java.util.*;

import java.awt.*;
import java.awt.event.*;

import javax.swing.*;


//=====================================================================================================================
// Dialog superclass with some of the same behaviors as AppFrame, see that and AppController for details.

public class AppDialog extends JDialog implements AppEditor {

	protected AppEditor parent;

	protected ErrorReporter errorReporter;

	private int blockActionsCount;

	protected String baseTitle;
	protected String documentName;
	protected String displayTitle;

	private boolean locationSaved;
	private boolean disposeOnClose;

	private JTextField currentField;


	//-----------------------------------------------------------------------------------------------------------------
	// Usually the parent editor's window is the owner but that can be different, mainly so owner may be null.

	public AppDialog(AppEditor theParent, String theTitle, Dialog.ModalityType modality) {
		super(theParent.getWindow(), theTitle, modality);
		doSetup(theParent, theTitle);
	}

	public AppDialog(AppEditor theParent, Window theOwner, String theTitle, Dialog.ModalityType modality) {
		super(theOwner, theTitle, modality);
		doSetup(theParent, theTitle);
	}

	private void doSetup(AppEditor theParent, String theTitle) {

		parent = theParent;

		errorReporter = new ErrorReporter(getRootEditor(), this, theTitle);

		blockActionsCount = -1;

		baseTitle = theTitle;
		displayTitle = theTitle;

		locationSaved = false;
		disposeOnClose = true;

		setResizable(false);

		setLocation(new Point(120, 60));
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getDbID() {
		return parent.getDbID();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public RootEditor getRootEditor() {
		return parent.getRootEditor();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public Window getWindow() {
		return this;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public ErrorReporter getErrorReporter() {
		return errorReporter;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean blockActions() {

		if (blockActionsCount != 0) {
			return false;
		}
		blockActionsStart();
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void blockActionsStart() {

		if (blockActionsCount >= 0) {
			blockActionsCount++;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void blockActionsEnd() {

		if (blockActionsCount > 0) {
			blockActionsCount--;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void blockActionsSet() {

		blockActionsCount = -1;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void blockActionsClear() {

		blockActionsCount = 0;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void setDocumentName(String theName) {
		documentName = theName;
		updateTitles();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getDocumentName() {
		return documentName;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void updateDocumentName() {
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void setCurrentField(JTextField theField) {
		currentField = theField;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public JTextField getCurrentField() {
		return currentField;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean commitCurrentField() {
		if (null != currentField) {
			errorReporter.clearErrors();
			currentField.postActionEvent();
			return !errorReporter.hasErrors();
		}
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void setDidEdit() {
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean applyEditsFrom(AppEditor theEditor) {
		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void editorClosing(AppEditor theEditor) {
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This has the AppFrame behavior of a base title and document name producing a display title, but does not have
	// the title key or shortcut key properties.  The base title is used as a save/restore key.

	public String getBaseTitle() {
		return baseTitle;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getKeyTitle() {
		return baseTitle;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void setTitle(String theTitle) {
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getTitle() {
		return displayTitle;
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected void updateTitles() {

		if ((null == documentName) || (0 == documentName.length())) {
			displayTitle = baseTitle;
		} else {
			displayTitle = baseTitle + ": " + documentName;
		}

		super.setTitle(displayTitle);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void setLocationSaved(boolean s) {
		locationSaved = s;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean getLocationSaved() {
		return locationSaved;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void setDisposeOnClose(boolean d) {
		disposeOnClose = d;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean getDisposeOnClose() {
		return disposeOnClose;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillOpen() {
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean windowShouldClose() {
		return true;
	}
}
