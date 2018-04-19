//
//  RecordFind.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;

import java.util.*;
import java.sql.*;
import java.awt.*;
import java.awt.event.*;
import java.text.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;


//=====================================================================================================================
// Window to display a RecordFindPanel, optionally along with an accessory panel for supplementary input e.g. one of
// the OptionsPanel subclasses.  This can operate either from a context where a selected record is applied back to a
// parent editor using the applyEditsFrom() method in AppEditor, or in a stand-alone role for general browsing of
// station data outside any specific editing context.  The RecordFindPanel UI is quite elaborate and can have side-
// effects, e.g. creation of user records or export to XML, regardless of context.

public class RecordFind extends AppFrame {

	public static final String WINDOW_TITLE = "Search Station Data";

	private RecordFindPanel findPanel;

	private AppPanel accessoryPanel;

	private boolean canApplyNew;
	private boolean closeOnApply;

	// UI components.

	private JPanel accessoryWrapperPanel;

	// Buttons, see setApply().

	private JButton closeButton;
	private JButton[] applyButtons;
	private JPanel buttonPanel;

	// Results.

	public SourceEditData source;
	public ExtDbRecord record;

	public int applyButtonID;

	// Disambiguation.

	private RecordFind outerThis = this;


	//-----------------------------------------------------------------------------------------------------------------
	// The title can be customized if needed.  Other arguments are forwarded to RecordFindPanel, see details there.

	public RecordFind(AppEditor theParent, int theStudyType, int theRecordType) {
		super(theParent, WINDOW_TITLE);
		doSetup(theStudyType, theRecordType);
	}

	public RecordFind(AppEditor theParent, String theTitle, int theStudyType, int theRecordType) {
		super(theParent, theTitle);
		doSetup(theStudyType, theRecordType);
	}

	private void doSetup(int theStudyType, int theRecordType) {

		findPanel = new RecordFindPanel(this, new Runnable() {public void run() {findPanelSelectionChanged();}},
			theStudyType, theRecordType);

		// See setAccessoryPanel().

		accessoryWrapperPanel = new JPanel();

		// Buttons.

		closeButton = new JButton("Close");
		closeButton.setFocusable(false);
		closeButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				closeIfPossible();
			}
		});

		// Do the layout.

		buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttonPanel.add(closeButton);

		Box bottomBox = Box.createVerticalBox();
		bottomBox.add(accessoryWrapperPanel);
		bottomBox.add(buttonPanel);

		Container cp = getContentPane();
		cp.setLayout(new BorderLayout());
		cp.add(findPanel, BorderLayout.CENTER);
		cp.add(bottomBox, BorderLayout.SOUTH);

		getRootPane().setDefaultButton(findPanel.getDefaultButton());

		pack();

		Dimension theSize = new Dimension(1170, 700);
		setSize(theSize);
		setMinimumSize(theSize);

		// Build the Record menu.

		fileMenu.removeAll();
		findPanel.addMenuItems(fileMenu);

		updateDocumentName();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void dispose() {

		if (null != accessoryPanel) {
			accessoryWrapperPanel.removeAll();
			accessoryPanel.setParent(null);
			accessoryPanel = null;
		}

		super.dispose();
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected boolean showsFileMenu() {

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected String getFileMenuName() {

		return "Record";
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected boolean showsEditMenu() {

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Configuration methods forward to the find panel.

	public void setDefaultExtDbKey(Integer theKey) {

		findPanel.setDefaultExtDbKey(theKey);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void setTVChannelRange(int theMinChannel, int theMaxChannel) {

		findPanel.setTVChannelRange(theMinChannel, theMaxChannel);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void setNote(String theNote) {

		findPanel.setNote(theNote);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set an accessory panel, an AppPanel subclass providing additional UI appropriate to context.  This and other
	// configuration methods only function when the window is not visible.

	public void setAccessoryPanel(AppPanel thePanel) {

		if (isVisible()) {
			return;
		}

		if (null != accessoryPanel) {
			accessoryWrapperPanel.removeAll();
			accessoryPanel.setParent(null);
		}

		accessoryPanel = thePanel;

		if (null != accessoryPanel) {
			accessoryPanel.setParent(this);
			accessoryWrapperPanel.add(accessoryPanel);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public AppPanel getAccessoryPanel() {

		return accessoryPanel;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set the apply behavior.  A close/cancel button always appears, but one or more apply buttons may also appear.
	// There is no difference in behavior here for multiple buttons but the applyButtonID property is set according
	// to which button is pressed so the calling context can change behavior.  If the label array is null or empty,
	// apply is disabled and the other arguments are ignored.  Otherwise each label creates an apply button.  The
	// canApplyNew flag determines if a new record may be applied.  The closeOnApply flag determines if the first
	// successful apply by any button also closes, if not the dialog remains open until the close button is pressed.
	// That is labeled "Close" if there are no apply buttons or closeOnApply is false, else it is labeled "Cancel".

	public void setApply(String[] buttonLabels, boolean theNewFlag, boolean theCloseFlag) {

		if (isVisible()) {
			return;
		}

		buttonPanel.removeAll();
		buttonPanel.add(closeButton);

		if ((null == buttonLabels) || (0 == buttonLabels.length)) {

			canApplyNew = false;
			closeOnApply = false;

			closeButton.setText("Close");

			applyButtons = null;

		} else {

			canApplyNew = theNewFlag;
			closeOnApply = theCloseFlag;

			if (closeOnApply) {
				closeButton.setText("Cancel");
			} else {
				closeButton.setText("Close");
			}

			applyButtons = new JButton[buttonLabels.length];
			for (int i = 0; i < buttonLabels.length; i++) {
				applyButtons[i] = new JButton(buttonLabels[i]);
				applyButtons[i].setFocusable(false);
				final int id = i + 1;
				applyButtons[i].addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doApply(id);
					}
				});
				buttonPanel.add(applyButtons[i]);
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void updateDocumentName() {

		setDocumentName(parent.getDocumentName());

		findPanel.updateDocumentName();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called when the selection changes in the find panel, update other UI.

	public void findPanelSelectionChanged() {

		if (null != accessoryPanel) {
			accessoryPanel.selectionChanged(findPanel.getSelectedRecord());
		}

		boolean eApply = findPanel.canApplySelection(canApplyNew);

		if (null != accessoryPanel) {
			accessoryPanel.setEnabled(eApply);
		}

		if (null != applyButtons) {
			for (JButton theBut : applyButtons) {
				theBut.setEnabled(eApply);
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// If apply is not enabled, do nothing.  Otherwise check if the record selected in the find panel can be applied.
	// If so, check with the accessory panel (if any) to confirm valid settings there.

	private void doApply(int theID) {

		if ((null == applyButtons) || !findPanel.canApplySelection(canApplyNew)) {
			return;
		}

		errorReporter.clearTitle();

		if ((null != accessoryPanel) && !accessoryPanel.validateInput()) {
			return;
		}

		Record theRecord = findPanel.getSelectedRecord();
		if (theRecord.isSource()) {
			source = (SourceEditData)theRecord;
			record = null;
		} else {
			source = null;
			record = (ExtDbRecord)theRecord;
		}

		// Inform the parent, if success flag the item as applied.

		applyButtonID = theID;
		if (!parent.applyEditsFrom(this)) {
			return;
		}

		findPanel.selectionWasApplied();

		// Close if needed.

		if (closeOnApply) {
			closeIfPossible();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillOpen() {

		findPanel.windowWillOpen();

		if (null != accessoryPanel) {
			accessoryPanel.windowWillOpen();
			accessoryPanel.setEnabled(false);
		}

		blockActionsClear();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean windowShouldClose() {

		if (!findPanel.windowShouldClose()) {
			return false;
		}

		if (null != accessoryPanel) {
			if (!accessoryPanel.windowShouldClose()) {
				return false;
			}
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillClose() {

		findPanel.windowWillClose();

		if (null != accessoryPanel) {
			accessoryPanel.windowWillClose();
		}

		blockActionsSet();
		parent.editorClosing(this);
	}
}
