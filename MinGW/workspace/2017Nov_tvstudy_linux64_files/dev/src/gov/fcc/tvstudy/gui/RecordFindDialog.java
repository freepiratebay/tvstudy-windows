//
//  RecordFindDialog.java
//  TVStudy
//
//  Copyright (c) 2017 Hammett & Edison, Inc.  All rights reserved.

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
// Dialog to display a RecordFindPanel for a simple search, result available from getSelectedRecord().

public class RecordFindDialog extends AppDialog {

	public static final String WINDOW_TITLE = "Search Station Data";

	private RecordFindPanel findPanel;

	private JButton okButton;
	public boolean canceled;

	private RecordFindDialog outerThis = this;


	//-----------------------------------------------------------------------------------------------------------------

	public RecordFindDialog(AppEditor theParent, int theRecordType) {
		super(theParent, WINDOW_TITLE, Dialog.ModalityType.APPLICATION_MODAL);
		doSetup(theRecordType);
	}

	public RecordFindDialog(AppEditor theParent, String theTitle, int theRecordType) {
		super(theParent, theTitle, Dialog.ModalityType.APPLICATION_MODAL);
		doSetup(theRecordType);
	}

	private void doSetup(int theRecordType) {

		findPanel = new RecordFindPanel(this, new Runnable() {public void run() {findPanelSelectionChanged();}},
			theRecordType, false);

		okButton = new JButton("OK");
		okButton.setFocusable(false);
		okButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				AppController.hideWindow(outerThis);
			}
		});
		okButton.setEnabled(false);

		JButton cancelButton = new JButton("Cancel");
		cancelButton.setFocusable(false);
		cancelButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				canceled = true;
				AppController.hideWindow(outerThis);
			}
		});

		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttonPanel.add(cancelButton);
		buttonPanel.add(okButton);

		Container cp = getContentPane();
		cp.setLayout(new BorderLayout());
		cp.add(findPanel, BorderLayout.CENTER);
		cp.add(buttonPanel, BorderLayout.SOUTH);

		getRootPane().setDefaultButton(findPanel.getDefaultButton());

		pack();

		setResizable(true);

		Dimension theSize = new Dimension(1170, 500);
		setSize(theSize);
		setMinimumSize(theSize);

		setLocationRelativeTo(getOwner());

		updateDocumentName();
	}


	//-----------------------------------------------------------------------------------------------------------------

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

	public void updateDocumentName() {

		setDocumentName(parent.getDocumentName());

		findPanel.updateDocumentName();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void findPanelSelectionChanged() {

		okButton.setEnabled(null != findPanel.getSelectedRecord());
	}


	//-----------------------------------------------------------------------------------------------------------------

	public Record getSelectedRecord() {

		return findPanel.getSelectedRecord();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillOpen() {

		findPanel.windowWillOpen();

		blockActionsClear();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean windowShouldClose() {

		if (!findPanel.windowShouldClose()) {
			return false;
		}

		blockActionsSet();
		canceled = true;
		return true;
	}
}
