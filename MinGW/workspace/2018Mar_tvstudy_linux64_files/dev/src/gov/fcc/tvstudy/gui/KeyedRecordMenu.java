//
//  KeyedRecordMenu.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;

import gov.fcc.tvstudy.core.*;

import java.util.*;
import java.awt.*;
import java.awt.event.*;

import javax.swing.*;
import javax.swing.text.*;
import javax.swing.event.*;


//=====================================================================================================================
// A customized JComboBox for KeyedRecord (and subclasses) to provide a selection menu for a list of database records
// identified by a primary key and represented by a descriptive name.  The big added value here is that this will
// temporarily add an item to the list if given to setSelectedItem() and not already in the list, then remove it again
// when it is de-selected.

public class KeyedRecordMenu extends JComboBox<KeyedRecord> implements ItemListener {

	private HashMap<Integer, KeyedRecord> permanentItems;
	private KeyedRecord temporaryItem;

	private boolean stopListener = false;


	//-----------------------------------------------------------------------------------------------------------------

	public KeyedRecordMenu() {

		super();
		setFocusable(false);

		permanentItems = new HashMap<Integer, KeyedRecord>();

		addItemListener(this);
		setMaximumRowCount(30);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This will not allow duplicate items in the list.  Remember that KeyedRecord defines equals() as a key match.

	public KeyedRecordMenu(ArrayList<KeyedRecord> theItems) {

		super();
		setFocusable(false);

		permanentItems = new HashMap<Integer, KeyedRecord>();

		Integer k;
		for (KeyedRecord item : theItems) {
			k = Integer.valueOf(item.key);
			if (!permanentItems.containsKey(k)) {
				super.addItem(item);
				permanentItems.put(k, item);
			}
		}

		addItemListener(this);
		setMaximumRowCount(30);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Prevent the component from being editable, no write-ins allowed.

	public void setEditable(boolean flag) {

		super.setEditable(false);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Convenience.

	public void addAllItems(ArrayList<KeyedRecord> theItems) {

		for (KeyedRecord item : theItems) {
			addItem(item);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This will automatically move the temporary item to permanent status if appropriate.  Also see comments below
	// about always using the identical object in calls to the superclass.  Does not allow duplicate items.

	public void addItem(KeyedRecord item) {

		if (item instanceof KeyedRecord) {

			Integer k = Integer.valueOf(item.key);

			if (!permanentItems.containsKey(k)) {
				if (item.equals(temporaryItem)) {
					permanentItems.put(k, temporaryItem);
					temporaryItem = null;
				} else {
					super.addItem(item);
					permanentItems.put(k, item);
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void addItem(KeyedRecord item, int index) {

		if (item instanceof KeyedRecord) {

			Integer k = Integer.valueOf(item.key);

			if (!permanentItems.containsKey(k)) {
				if (item.equals(temporaryItem)) {
					permanentItems.put(k, temporaryItem);
					temporaryItem = null;
				} else {
					super.insertItemAt(item, index);
					permanentItems.put(k, item);
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void removeAllItems() {

		stopListener = true;

		super.removeAllItems();
		permanentItems.clear();
		temporaryItem = null;

		stopListener = false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Note this is careful to use the identical object first given to the superclass, see comments below.

	public void removeItem(KeyedRecord item) {

		if (item instanceof KeyedRecord) {

			Integer k = Integer.valueOf(item.key);

			if (permanentItems.containsKey(k)) {
				item = permanentItems.remove(k);
				super.removeItem(item);
			} else {
				if (item.equals(temporaryItem)) {
					stopListener = true;
					super.removeItem(temporaryItem);
					temporaryItem = null;
					stopListener = false;
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add the item if needed, possibly removing an earlier temporary first.  Note this is careful to always use the
	// identical original object (with matching key to the argument) in calls to the superclass.  Aside from paranoia
	// about how the model might match objects, there is a somewhat good reason for that.  It allows the client code
	// to use an argument object that might not have the same descriptive string as the original, without affecting
	// how the item is displayed.  The superclass might reasonably assume two objects that are the same according to
	// equals() will also give the same value from toString(), but KeyedRecord deliberately does not require that the
	// strings match.

	public void setSelectedItem(KeyedRecord item) {

		if (item instanceof KeyedRecord) {

			Integer k = Integer.valueOf(item.key);

			if (permanentItems.containsKey(k)) {
				item = permanentItems.get(k);
			} else {
				if (item.equals(temporaryItem)) {
					item = temporaryItem;
				} else {
					if (null != temporaryItem) {
						stopListener = true;
						super.removeItem(temporaryItem);
						temporaryItem = null;
						stopListener = false;
					}
					temporaryItem = item;
					super.addItem(item);
				}
			}

			super.setSelectedItem(item);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Determine if a key exists in the current list.

	public boolean containsKey(int theKey) {

		if (permanentItems.containsKey(Integer.valueOf(theKey))) {
			return true;
		}

		if ((null != temporaryItem) && (theKey == temporaryItem.key)) {
			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This is used when the client code is confident that the key is already in the list.  It won't fail if that is
	// not the case, but the temporary item displayed will be generic, and a run-time error could occur later if the
	// caller retrieves this object and tries to cast it to a subclass.

	public void setSelectedKey(int key) {

		setSelectedItem(new KeyedRecord(key, "(unknown)"));
	}


	//-----------------------------------------------------------------------------------------------------------------
	// For an index out of range use the closest end, or do nothing if the list is empty.

	public void setSelectedIndex(int theIndex) {

		int theCount = getItemCount();
		if (theCount > 0) {
			if (theIndex < 0) {
				theIndex = 0;
			} else {
				if (theIndex >= theCount) {
					theIndex = theCount - 1;
				}
			}
			super.setSelectedIndex(theIndex);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Convenience.

	public KeyedRecord getSelectedItem() {

		return (KeyedRecord)super.getSelectedItem();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int getSelectedKey() {

		KeyedRecord item = (KeyedRecord)super.getSelectedItem();

		if (null != item) {
			return item.key;
		}
		return 0;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getSelectedName() {

		KeyedRecord item = (KeyedRecord)super.getSelectedItem();

		if (null != item) {
			return item.name;
		}
		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The ItemListener interface.  When an item is selected, if there is a temporary item and it is not the one being
	// selected, remove the temporary.  First check the interlock flag, set when something else is programmatically
	// removing the temporary item and doesn't want this to interfere.  It's also set here during the removal just to
	// be safe, never know what might happen in the superclass.

	public void itemStateChanged(ItemEvent e) {

		if (stopListener) {
			return;
		}

		if (ItemEvent.SELECTED == e.getStateChange()) {
			KeyedRecord item = (KeyedRecord)e.getItem();
			if ((null != temporaryItem) && !item.equals(temporaryItem)) {
				stopListener = true;
				super.removeItem(temporaryItem);
				temporaryItem = null;
				stopListener = false;
			}
		}
	}
}
