//
//  AppPanel.java
//  TVStudy
//
//  Copyright (c) 2015-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;

import java.util.*;

import java.awt.*;
import java.awt.event.*;

import javax.swing.*;


//=====================================================================================================================
// Panel superclass for providing modular UI layouts.  An optional callback runnable can be provided, that is meant to
// be used by the subclass for live updating of the parent's model.  This is also designed to support "third party"
// editors displaying a panel with input that is not used directly by the displaying editor but meant for another
// editor, typically the displaying editor's parent.  To support that, parentage may be partially re-directed after
// construction by setParent().  The primary editor needing the panel's input is the parent set at construction, that
// handles callback as well as providing database ID and root editor.  The displaying editor sets itself as parent
// when it shows the panel, redirecting just error reporting and UI interlock functions.

public class AppPanel extends JPanel implements AppEditor {

	protected AppEditor originalParent;

	protected AppEditor parent;
	protected ErrorReporter errorReporter;
	protected Runnable callBack;


	//-----------------------------------------------------------------------------------------------------------------

	public AppPanel(AppEditor theParent) {

		super();

		originalParent = theParent;

		parent = originalParent;
		errorReporter = parent.getErrorReporter();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public AppPanel(AppEditor theParent, Runnable theCallBack) {

		super();

		originalParent = theParent;

		parent = originalParent;
		errorReporter = parent.getErrorReporter();
		callBack = theCallBack;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The parent editor can be changed temporarily, re-directing some of the methods below and changing the error
	// reporting object.  This can be cleared by setting null, reverting to the parent set at construction.

	public void setParent(AppEditor theParent) {

		if (null == theParent) {
			parent = originalParent;
		} else {
			parent = theParent;
		}
		errorReporter = parent.getErrorReporter();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Enable/disable the state of the UI.

	public void setEnabled(boolean flag) {
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by displaying editor when window is opening or trying to close.

	public void windowWillOpen() {
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean windowShouldClose() {
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Reset state of UI back to defaults.

	public void clearFields() {
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by displaying editor when an object is selected in other UI that may affect the panel's state.

	public void selectionChanged(Object newSelection) {
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by displaying editor when it is applying edits.  Return false if validation fails.

	public boolean validateInput() {
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// AppEditor methods forward to one of the parents, some the original, some the current.

	public String getDbID() {
		return originalParent.getDbID();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public RootEditor getRootEditor() {
		return originalParent.getRootEditor();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public Window getWindow() {
		return parent.getWindow();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getTitle() {
		return parent.getTitle();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public ErrorReporter getErrorReporter() {
		return errorReporter;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean blockActions() {
		return parent.blockActions();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void blockActionsStart() {
		parent.blockActionsStart();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void blockActionsEnd() {
		parent.blockActionsEnd();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void blockActionsSet() {
		parent.blockActionsSet();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void blockActionsClear() {
		parent.blockActionsClear();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void setDocumentName(String theName) {
		originalParent.setDocumentName(theName);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getDocumentName() {
		return originalParent.getDocumentName();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void updateDocumentName() {
		originalParent.updateDocumentName();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void setCurrentField(JTextField theField) {
		parent.setCurrentField(theField);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public JTextField getCurrentField() {
		return parent.getCurrentField();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean commitCurrentField() {
		return parent.commitCurrentField();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void setDidEdit() {
		originalParent.setDidEdit();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean applyEditsFrom(AppEditor theEditor) {
		return originalParent.applyEditsFrom(theEditor);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void editorClosing(AppEditor theEditor) {
		originalParent.editorClosing(theEditor);
	}
}
