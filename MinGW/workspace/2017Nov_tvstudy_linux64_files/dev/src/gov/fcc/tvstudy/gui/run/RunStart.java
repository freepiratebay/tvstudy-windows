//
//  RunStart.java
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui.run;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.gui.*;

import java.util.*;
import java.sql.*;
import java.awt.*;
import java.awt.event.*;
import java.text.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;


//=====================================================================================================================
// Abstract superclass for dialog setting up runs of the study engine for existing studies.  Uses an OptionsPanel to
// present basic UI for a run, including an output file configuration, memory limit, and run comment.  Concrete
// subclasses provide additional UI for specific types of studies and runs.  Subclasses create a RunPanel subclass
// object to set up and manage the run, then the study manager which opened this will pass that to a run manager.  The
// setStudy() method must be called before display to load study information and lock the study; that lock will be
// inherited by the study engine and later cleared by the run panel or study manager when the run completes.  Note the
// lock state is actually held in the RunPanel object, StudyLockHolder methods here just forward.

public abstract class RunStart extends AppDialog implements StudyLockHolder {

	protected OptionsPanel optionsPanel;

	protected JPanel buttonPanel;


	//-----------------------------------------------------------------------------------------------------------------
	// The subclass must take care of layout, this just provides the two panels with options and buttons.

	protected RunStart(AppEditor theParent, String theTitle) {

		super(theParent, theTitle, Dialog.ModalityType.MODELESS);

		optionsPanel = new OptionsPanel.RunStart(this);
		optionsPanel.setParent(this);

		// Buttons.

		JButton resetButton = new JButton("Clear");
		resetButton.setFocusable(false);
		resetButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doReset();
			}
		});

		JButton cancelButton = new JButton("Cancel");
		cancelButton.setFocusable(false);
		cancelButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				cancel();
			}
		});

		JButton applyButton = new JButton("Run");
		applyButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doApply();
			}
		});

		JPanel butL = new JPanel(new FlowLayout(FlowLayout.LEFT));
		butL.add(resetButton);

		JPanel butR = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		butR.add(cancelButton);
		butR.add(applyButton);

		buttonPanel = new JPanel();
		buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS));
		buttonPanel.add(butL);
		buttonPanel.add(butR);

		getRootPane().setDefaultButton(applyButton);

		setResizable(true);
		setLocationSaved(true);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void updateDocumentName() {

		RunPanel thePanel = getRunPanel();
		String docName;
		if ((null != thePanel) && (thePanel.studyKey > 0)) {
			docName = parent.getDocumentName() + "/" + thePanel.studyName;
		} else {
			docName = parent.getDocumentName();
		}

		setDocumentName(docName);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The implementation of this must validate the study key and lock the study.  Usually it will also create the
	// RunPanel object.  However for some run types this may actually load the full study object.

	public abstract boolean setStudy(int theStudyKey, ErrorReporter errors);


	//-----------------------------------------------------------------------------------------------------------------
	// Return the RunPanel object that will manage the run, it holds the lock state.

	public abstract RunPanel getRunPanel();


	//-----------------------------------------------------------------------------------------------------------------
	// StudyLockHolder methods.

	public int getStudyKey() {

		RunPanel thePanel = getRunPanel();
		if (null != thePanel) {
			return thePanel.studyKey;
		}
		return 0;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int getStudyLock() {

		RunPanel thePanel = getRunPanel();
		if (null != thePanel) {
			return thePanel.studyLock;
		}
		return 0;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int getLockCount() {

		RunPanel thePanel = getRunPanel();
		if (null != thePanel) {
			return thePanel.lockCount;
		}
		return 0;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void toFront() {

		super.toFront();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean closeWithoutSave() {

		return cancel();
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected void doReset() {

		optionsPanel.clearFields();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check UI and populate RunPanel properties.  Subclassess override to check local input, call super first.

	protected boolean validateInput() {

		RunPanel thePanel = getRunPanel();

		if ((null == thePanel) || (thePanel.studyKey <= 0) || thePanel.isInitialized()) {
			return false;
		}

		if (!optionsPanel.validateInput()) {
			return false;
		}

		thePanel.fileOutputConfig = optionsPanel.fileOutputConfig;
		thePanel.mapOutputConfig = optionsPanel.mapOutputConfig;
		thePanel.memoryFraction = optionsPanel.memoryFraction;
		thePanel.runComment = optionsPanel.comment;

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Apply action, first validate input.  Note this method is typically not overridden.  Subclasses should put extra
	// checks and confirmation UI in validateInput().

	protected void doApply() {

		if (!validateInput()) {
			return;
		}

		// Initialize the panel.  If this fails there is no recourse, cancel the dialog.

		RunPanel thePanel = getRunPanel();

		if (!thePanel.initialize(errorReporter)) {
			cancel();
			return;
		}

		// If available disk space on the cache partition appears low, show a warning.

		if (!thePanel.isDiskSpaceAvailable()) {
			AppController.beep();
			if (JOptionPane.YES_OPTION != JOptionPane.showConfirmDialog(this,
					"There may not be enough free disk space to complete this study run.\n" +
					"Do you want to start this run anyway?",
					getBaseTitle(), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)) {
				return;
			}
		}

		// Inform the parent, it will retrieve and show the run panel.  That will directly replace this window in the
		// parent's open study tracking state, so if this succeeds just hide, don't call editorClosing().

		if (parent.applyEditsFrom(this)) {
			blockActionsSet();
			AppController.hideWindow(this);
			return;
		}

		// The startup failed for some reason, clear state and close.

		cancel();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean cancel() {

		if (windowShouldClose()) {
			AppController.hideWindow(this);
			return true;
		}
		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillOpen() {

		optionsPanel.windowWillOpen();

		setLocationRelativeTo(getOwner());

		blockActionsClear();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The parent will clear the study lock if needed in editorClosing().

	public boolean windowShouldClose() {

		blockActionsSet();

		parent.editorClosing(this);

		return true;
	}
}
