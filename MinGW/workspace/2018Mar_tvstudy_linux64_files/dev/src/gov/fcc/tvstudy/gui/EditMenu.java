//
//  EditMenu.java
//  TVStudy
//
//  Copyright (c) 2012-2015 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;

import java.util.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.*;

import javax.swing.*;
import javax.swing.text.*;


//=====================================================================================================================
// Create a menu with cut, copy, paste, and select-all items.  Also, implement those actions in a reasonable way for
// non-focusable tables.  The action handler first looks for an existing action tied to the keystroke in the focus
// component's keyboard map, if found the event is forwarded to that action.  If not found, then if a table has been
// set by setTargetTable(), an appropriate behavior is implemented here and the table is modified directly.  If the
// focus owner does not respond to the keystroke and no table has been set, this just beeps.

public class EditMenu extends JMenu {

	private KeyStroke cutKeyStroke;
	private KeyStroke copyKeyStroke;
	private KeyStroke pasteKeyStroke;
	private KeyStroke selectAllKeyStroke;

	private JTable targetTable;


	//-----------------------------------------------------------------------------------------------------------------

	public EditMenu() {

		super("Edit");

		cutKeyStroke = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_X, AppController.MENU_SHORTCUT_KEY_MASK);
		copyKeyStroke = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, AppController.MENU_SHORTCUT_KEY_MASK);
		pasteKeyStroke = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V, AppController.MENU_SHORTCUT_KEY_MASK);
		selectAllKeyStroke = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_A, AppController.MENU_SHORTCUT_KEY_MASK);

		JMenuItem menuItem;

		menuItem = new JMenuItem("Cut");
		menuItem.setAccelerator(cutKeyStroke);
		menuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				runAction(theEvent, cutKeyStroke);
			}
		});
		add(menuItem);

		menuItem = new JMenuItem("Copy");
		menuItem.setAccelerator(copyKeyStroke);
		menuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				runAction(theEvent, copyKeyStroke);
			}
		});
		add(menuItem);

		menuItem = new JMenuItem("Paste");
		menuItem.setAccelerator(pasteKeyStroke);
		menuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				runAction(theEvent, pasteKeyStroke);
			}
		});
		add(menuItem);

		menuItem = new JMenuItem("Select All");
		menuItem.setAccelerator(selectAllKeyStroke);
		menuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				runAction(theEvent, selectAllKeyStroke);
			}
		});
		add(menuItem);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void setTargetTable (JTable theTable) {

		targetTable = theTable;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Handle a menu action.  Check for an action in the focus component's keyboard map first.  That isn't redundant;
	// for a keyboard event the focus owner usually gets it first and would respond directly, however if the menu item
	// is selected by mouse action the event comes here first.  If no action is found in the keymap, check for a target
	// table and apply the event there; only copy and select-all are currently supported.  Otherwise, beep.

	private void runAction(ActionEvent theEvent, KeyStroke keyStroke) {

		Action theAction = null;
		Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
		if (focusOwner instanceof JComponent) {
			Object keyCommand = ((JComponent)focusOwner).getInputMap().get(keyStroke);
			if (null != keyCommand) {
				theAction = ((JComponent)focusOwner).getActionMap().get(keyCommand);
				if (null != theAction) {
					theAction.actionPerformed(theEvent);
					return;
				}
			}
		}

		if (null == targetTable) {
			AppController.beep();
			return;
		}

		// Cut and paste not currently supported for tables.

		if ((keyStroke == cutKeyStroke) || (keyStroke == pasteKeyStroke)) {
			AppController.beep();
			return;
		}

		// Copy uses an Excel-compatible tab-separated format.

		if (keyStroke == copyKeyStroke) {

			int[] rows = targetTable.getSelectedRows();
			if (0 == rows.length) {
				AppController.beep();
				return;
			}

			int col, ncol = targetTable.getColumnCount();
			StringBuilder sbf = new StringBuilder();
			Object value;
			String str, sep;
			for (int i = 0; i < rows.length; i++) {
				sep = "";
				for (col = 0; col < ncol; col++) {
					value = targetTable.getValueAt(rows[i], col);
					if (null == value) {
						str = "";
					} else {
						str = value.toString();
					}
					str = str.replace('"', '\'');
					if (str.contains("\t")) {
						str = "\"" + str + "\"";
					}
					sbf.append(sep + str);
					sep = "\t";
				}
				sbf.append("\n");
			}

			StringSelection stsel = new StringSelection(sbf.toString());
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(stsel, stsel);

			return;
		}

		// Select all.

		if (keyStroke == selectAllKeyStroke) {
			targetTable.setRowSelectionInterval(0, (targetTable.getRowCount() - 1));
			return;
		}

		AppController.beep();
	}
}
