//
//  AppEditor.java
//  TVStudy
//
//  Copyright (c) 2015-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;

import java.awt.*;

import javax.swing.*;


//=====================================================================================================================
// Interface for objects that provide editing context.  Usually objects implementing this are also Window subclasses.
// See AppFrame for further discussion.

public interface AppEditor {


	//-----------------------------------------------------------------------------------------------------------------

	public String getDbID();


	//-----------------------------------------------------------------------------------------------------------------

	public RootEditor getRootEditor();


	//-----------------------------------------------------------------------------------------------------------------
	// This returns a window appropriate as owner for dialogs dependent to the editor.  Typically just returns "this".

	public Window getWindow();


	//-----------------------------------------------------------------------------------------------------------------
	// Likewise a title appropriate for dialogs, because getWindow().getTitle() is bad if getWindow() returns null.

	public String getTitle();


	//-----------------------------------------------------------------------------------------------------------------

	public ErrorReporter getErrorReporter();


	//-----------------------------------------------------------------------------------------------------------------

	public boolean blockActions();


	//-----------------------------------------------------------------------------------------------------------------

	public void blockActionsStart();


	//-----------------------------------------------------------------------------------------------------------------

	public void blockActionsEnd();


	//-----------------------------------------------------------------------------------------------------------------

	public void blockActionsSet();


	//-----------------------------------------------------------------------------------------------------------------

	public void blockActionsClear();


	//-----------------------------------------------------------------------------------------------------------------

	public void setDocumentName(String theName);


	//-----------------------------------------------------------------------------------------------------------------

	public String getDocumentName();


	//-----------------------------------------------------------------------------------------------------------------

	public void updateDocumentName();


	//-----------------------------------------------------------------------------------------------------------------

	public void setCurrentField(JTextField theField);


	//-----------------------------------------------------------------------------------------------------------------

	public JTextField getCurrentField();


	//-----------------------------------------------------------------------------------------------------------------

	public boolean commitCurrentField();


	//-----------------------------------------------------------------------------------------------------------------

	public void setDidEdit();


	//-----------------------------------------------------------------------------------------------------------------

	public boolean applyEditsFrom(AppEditor theEditor);


	//-----------------------------------------------------------------------------------------------------------------

	public void editorClosing(AppEditor theEditor);
}
