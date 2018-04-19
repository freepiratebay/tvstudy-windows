//
//  TextInputDialog.java
//  TVStudy
//
//  Copyright (c) 2016-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;

import java.util.*;
import java.awt.*;
import java.awt.event.*;
import java.text.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;


//=====================================================================================================================
// Utility input dialog for text input using a line-wrapping scrollable text area.

public class TextInputDialog extends AppDialog {

	private JTextArea inputArea;

	public boolean canceled;

	private TextInputDialog outerThis = this;


	//-----------------------------------------------------------------------------------------------------------------

	public TextInputDialog(AppEditor theParent, String title, String label) {

		super(theParent, title, Dialog.ModalityType.APPLICATION_MODAL);

		inputArea = new JTextArea(5, 40);
		AppController.fixKeyBindings(inputArea);
		inputArea.setLineWrap(true);
		inputArea.setWrapStyleWord(true);

		JPanel inputPanel = new JPanel();
		inputPanel.setBorder(BorderFactory.createTitledBorder(label));
		inputPanel.add(AppController.createScrollPane(inputArea));

		JButton okButton = new JButton("OK");
		okButton.setFocusable(false);
		okButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				AppController.hideWindow(outerThis);
			}
		});

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
		cp.add(inputPanel, BorderLayout.CENTER);
		cp.add(buttonPanel, BorderLayout.SOUTH);

		pack();

		setLocationRelativeTo(getOwner());
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void setInput(String theInput) {

		inputArea.setText(theInput);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getInput() {

		if (canceled) {
			return null;
		}

		return inputArea.getText().trim();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillOpen() {

		inputArea.requestFocusInWindow();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean windowShouldClose() {

		canceled = true;
		return true;
	}
}
