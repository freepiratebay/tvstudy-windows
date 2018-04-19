//
//  PickExtDbDialog.java
//  TVStudy
//
//  Copyright (c) 2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;
import gov.fcc.tvstudy.core.geo.*;
import gov.fcc.tvstudy.gui.*;

import java.util.*;
import java.io.*;
import java.sql.*;
import java.awt.*;
import java.awt.event.*;
import java.text.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.table.*;
import javax.swing.GroupLayout.*;
import javax.swing.filechooser.*;


//=====================================================================================================================
// Dialog to prompt user to pick external data sets to search during XML import operations, see e.g. StudyEditor.
// Caller provides list of data sets, a primary and alternate may be picked, or may be null.  Alternate can only be
// picked after primary, and can't be the same as the primary.

public class PickExtDbDialog extends AppDialog {

	private ArrayList<KeyedRecord> extDbList;

	private KeyedRecordMenu lookupExtDbMenu;
	private KeyedRecordMenu alternateExtDbMenu;

	// Return selections to caller.

	public int lookupExtDbKey;
	public int alternateExtDbKey;

	public boolean canceled;


	//-----------------------------------------------------------------------------------------------------------------

	public PickExtDbDialog(AppEditor theParent, String title, ArrayList<KeyedRecord> theList, Integer defaultKey) {

		super(theParent, title, Dialog.ModalityType.APPLICATION_MODAL);

		extDbList = theList;

		lookupExtDbMenu = new KeyedRecordMenu();
		lookupExtDbMenu.addItem(new KeyedRecord(0, "(none)"));
		lookupExtDbMenu.addAllItems(extDbList);
		lookupExtDbMenu.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					update();
					blockActionsEnd();
				}
			}
		});

		JPanel menuPanel = new JPanel();
		menuPanel.add(lookupExtDbMenu);
		menuPanel.setBorder(BorderFactory.createTitledBorder("Primary"));

		alternateExtDbMenu = new KeyedRecordMenu();
		alternateExtDbMenu.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				alternateExtDbKey = alternateExtDbMenu.getSelectedKey();
			}
		});

		JPanel alternatePanel = new JPanel();
		alternatePanel.add(alternateExtDbMenu);
		alternatePanel.setBorder(BorderFactory.createTitledBorder("Alternate"));

		JLabel infoLabel = new JLabel(
			"<HTML>Select data sets to resolve references to station data records by ID.<BR>" +
			"If no data sets are selected, by-reference elements are ignored.  Alternate<BR>" +
			"data will be search when a record is not found in the primary data.</HTML>");

		JPanel labelPanel = new JPanel();
		labelPanel.add(infoLabel);

		JButton okButton = new JButton("OK");
		okButton.setFocusable(false);
		okButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doOK();
			}
		});

		JButton cancelButton = new JButton("Cancel");
		cancelButton.setFocusable(false);
		cancelButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doCancel();
			}
		});

		JPanel menuP = new JPanel();
		menuP.add(menuPanel);

		JPanel altP = new JPanel();
		altP.add(alternatePanel);

		Box menus = Box.createVerticalBox();
		menus.add(menuP);
		menus.add(altP);

		JPanel mainPanel = new JPanel();
		mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
		mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 0, 20));
		mainPanel.add(labelPanel);
		mainPanel.add(Box.createVerticalStrut(10));
		mainPanel.add(menus);
		mainPanel.add(Box.createVerticalStrut(10));

		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttonPanel.add(cancelButton);
		buttonPanel.add(okButton);

		Container cp = getContentPane();
		cp.setLayout(new BorderLayout());
		cp.add(mainPanel, BorderLayout.CENTER);
		cp.add(buttonPanel, BorderLayout.SOUTH);

		pack();

		setLocationRelativeTo(theParent.getWindow());

		if ((null != defaultKey) && lookupExtDbMenu.containsKey(defaultKey.intValue())) {
			lookupExtDbMenu.setSelectedKey(defaultKey.intValue());
		}

		update();
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void update() {

		lookupExtDbKey = lookupExtDbMenu.getSelectedKey();

		alternateExtDbMenu.removeAllItems();
		alternateExtDbMenu.addItem(new KeyedRecord(0, "(none)"));

		if (lookupExtDbKey > 0) {

			for (KeyedRecord theItem : extDbList) {
				if (theItem.key != lookupExtDbKey) {
					alternateExtDbMenu.addItem(theItem);
				}
			}

			AppController.setComponentEnabled(alternateExtDbMenu, true);

			if (alternateExtDbMenu.containsKey(alternateExtDbKey)) {
				alternateExtDbMenu.setSelectedKey(alternateExtDbKey);
			} else {
				alternateExtDbKey = alternateExtDbMenu.getSelectedKey();
			}

		} else {

			AppController.setComponentEnabled(alternateExtDbMenu, false);

			alternateExtDbKey = 0;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doOK() {

		AppController.hideWindow(this);
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doCancel() {

		canceled = true;
		AppController.hideWindow(this);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillOpen() {

		blockActionsClear();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean windowShouldClose() {

		blockActionsSet();
		return true;
	}
}
