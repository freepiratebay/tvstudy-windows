//
//  RootEditor.java
//  TVStudy
//
//  Copyright (c) 2012-2015 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;

import gov.fcc.tvstudy.core.*;

import java.util.*;

import java.awt.*;
import java.awt.event.*;

import javax.swing.*;


//=====================================================================================================================
// Superclass for root editor classes, provides basic functionality that secondary editors use for error reporting,
// input and general data validation, and document save behaviors.

public abstract class RootEditor extends AppFrame {

	private boolean dataValid;

	private boolean validationErrorPending;
	private Window validationErrorParent;
	private String validationErrorTitle;
	private String validationErrorMessage;
	private int validationErrorType;


	//-----------------------------------------------------------------------------------------------------------------

	public RootEditor(AppEditor theParent, String theTitle) {

		super(theParent, theTitle);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public RootEditor getRootEditor() {

		return this;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check validity for model and editor state prior to a save.  Overrides must call super first, return false
	// immediately if this does (it might do something in the future), then do actual checks.  A validation error may
	// be reported when model state is not necessarily invalid but is indeterminate, i.e. if a modal dialog is showing
	// that may result in model changes when closed.  Also the validation check may trigger commit of pending edit
	// state, leading to a validation error from an action.  All errors must be reported with reportValidationError(),
	// see below.  The title string argument is for subclass implementations which may use that when reporting errors.
	// Any errors reported during this method call are deferred; displayValidationError(), clearValidationError(), or
	// save() must always be called after.  See saveIfNeeded() for the intended use of the mechanism.

	protected boolean isDataValid(String title) {

		dataValid = true;

		return dataValid;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Data validation failures always use this to report errors.  If called during a check started in isDataValid(),
	// the message display will be deferred and shown to the user later only if needed, i.e. if the user elects to
	// discard changes the error is not displayed; see saveIfNeeded().  Otherwise the message is displayed immediately.
	// During a validity check if there are multiple calls, only the first message will be displayed; the assumption is
	// the check will abort at the first failure, but that is not required.  The window argument sets the parent frame
	// for the message dialog, if null this is used.  If the title string is null no dialog will actually be displayed;
	// this can be called with null arguments during a validity check so the validationErrorPending flag is set to
	// block the save, but without a subsequent message dialog.

	public void reportValidationError(Window parent, String title, String message, int type) {

		if (validationErrorPending) {
			return;
		}

		if (null == parent) {
			parent = this;
		}

		if (dataValid) {

			dataValid = false;

			validationErrorPending = true;
			validationErrorParent = parent;
			validationErrorTitle = title;
			validationErrorMessage = message;
			validationErrorType = type;

		} else {

			if (null != title) {
				parent.toFront();
				AppController.showMessage(parent, message, title, type);
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check if a validation error has been reported in the current validity-check context.

	public boolean isValidationErrorPending() {

		return validationErrorPending;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Display and clear any pending validation error.

	private void displayValidationError() {

		if (null != validationErrorTitle) {
			validationErrorParent.toFront();
			AppController.showMessage(validationErrorParent, validationErrorMessage, validationErrorTitle,
				validationErrorType);
		}

		clearValidationError();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Clear pending error state and end the validity-check context.

	private void clearValidationError() {

		dataValid = false;

		validationErrorPending = false;
		validationErrorParent = null;
		validationErrorTitle = null;
		validationErrorMessage = null;
		validationErrorType = 0;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This method saves as-edited data.  Return is true if the save is successful, false if any error occurs or the
	// data is not saved for any reason.  This requires isDataValid() be called immediately prior so the dataValid flag
	// is true if all conditions are valid.  Subclasses must override to actually save, but must call super first and
	// immediately return false if this does.

	protected boolean save(String title) {

		boolean wasValid = dataValid;
		clearValidationError();
		return wasValid;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check for data changes in preparation for a save.  If desired, subclasses override this to do data-change tests.
	// As implemented this will always cause save() to be called, so if the subclass does not implement a change-
	// detection mechanism and just has save() always save everything, this does not need to be overridden.

	protected boolean isDataChanged() {

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Call to save changes if needed.  If the confirm argument is true and there are changes to save, the user is
	// prompted with options to save, discard, or cancel.  If the argument is false and there are changes this will
	// always save, unless an error occurs.  If there are no changes and no errors, this does nothing.  A validity
	// check is performed first, if that fails behave as if there are changes (assuming the model is initially valid,
	// invalid state implies there are changes, or attempted changes), but if it comes to actually saving, just report
	// the error and fail.  However with confirm true the user may say to discard changes, in which case the error is
	// ignored and never reported.  If the validity check succeeds then do a real data-change check.  A return of true
	// means there were no changes (or errors), the user said to discard changes (and ignore errors), or changes were
	// saved successfully; the caller may close the editor, reload state, whatever.  A false return means the user
	// canceled, there were validation errors, or the save failed; editor state must remain unchanged.

	public boolean saveIfNeeded(String title, boolean confirm) {

		boolean dataChanged = true;

		if (isDataValid(title)) {
			dataChanged = isDataChanged();
		}

		if (dataChanged) {

			if (confirm) {

				this.toFront();
				AppController.beep();
				int result = JOptionPane.showConfirmDialog(this, "Data modified, do you want to save the changes?",
					title, JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);

				if (JOptionPane.NO_OPTION == result) {
					clearValidationError();
					return true;
				}

				if (!dataValid) {
					displayValidationError();
					return false;
				}

				if (JOptionPane.CANCEL_OPTION == result) {
					clearValidationError();
					return false;
				}

			} else {

				if (!dataValid) {
					displayValidationError();
					return false;
				}
			}

			return save(title);
		}

		clearValidationError();
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Can be overridden to provide a programmatic way to silently discard all edits and close the editor, it should
	// close the editor and all dependent windows explicitly using hideWindow().  Return true if the close succeeds,
	// false if some state cannot safely be discarded or a dependent window can't be closed.

	public boolean closeWithoutSave() {

		return false;
	}
}
