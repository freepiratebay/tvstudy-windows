//
//  AppFrame.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;

import gov.fcc.tvstudy.core.*;

import java.util.*;

import java.awt.*;
import java.awt.event.*;

import javax.swing.*;


//=====================================================================================================================
// Abstract superclass for editor windows, provides basic setup of menus, and support for window control by the
// application controller.  An AppFrame window must be displayed by sending it to AppController.showWindow()!  The
// controller does the important window management, calling windowWillOpen() and windowWillClose() as needed.

// For menus, the Window and Help menus are always included in the menu bar.  The windowMenu should not be modified
// directly, everything to manage that is either here or in the controller.  The helpMenu has general help and about
// items added here, subclasses may add more specific help items during construction.

// The File, Edit, and Extra menus are optional, each has a matching method to determine if it is present or not,
// those are showsFileMenu(), showsEditMenu(), and showsExtraMenu().  By default the File and Edit menus are shown,
// the Extra menu is not shown.  Subclasses override the various methods to return true or false as needed.  The File
// and Extra menu names may be changed by overriding getFileMenuName() and getExtraMenuName().

// When the Edit menu is included, everything needed is provided by the EditMenu class, subclasses generally should not
// modify editMenu directly.

// When the File or Extra menus are included, fileMenu and extraMenu are created here but are initially empty, for
// those menus the subclass is entirely responsible for building/re-building the menus as needed.

public abstract class AppFrame extends JFrame implements AppEditor {

	protected AppEditor parent;

	protected ErrorReporter errorReporter;

	private int blockActionsCount;

	protected String documentName;

	protected String baseTitle;

	protected int titleKey;
	protected int shortcutKey;

	protected String keyTitle;
	protected String displayTitle;

	protected JMenu fileMenu;
	protected EditMenu editMenu;
	protected JMenu extraMenu;
	protected JMenu windowMenu;
	protected JMenu helpMenu;

	protected JMenuItem windowMenuItem;

	private boolean locationSaved;
	private boolean disposeOnClose;

	private JTextField currentField;


	//-----------------------------------------------------------------------------------------------------------------
	// If a subclass is meant to be the top of a hierarchy it may implement a constructor that does not take a parent
	// and then call super with a null parent.  However that subclass must also override getDbID(), the implementation
	// here in the superclass will throw an exception if the parent is null.  In most cases that override will return a
	// non-null value, automatically providing the database ID to all dependent windows.  (Subclasses further down a
	// hierarchy may also override getDbID() again if appropriate.)  However if a hierarchy or standalone top window
	// will do no actual database access, getDbID() may return null.  The database ID is also used for saving and
	// restoring window-related properties, however AppController handles the case of a null return from getDbID() by
	// falling back to a local file-based property store.  If appropriate, a top-level window may also override
	// getRootEditor(), but that is always optional.  The root editor for a database object may be further down the
	// window hierarchy, or it may not exist at all.  All code must handle a null return from getRootEditor().

	public AppFrame(AppEditor theParent, String theTitle) {

		super(theTitle);

		parent = theParent;

		errorReporter = new ErrorReporter(getRootEditor(), this, theTitle);

		blockActionsCount = -1;

		baseTitle = theTitle;

		shortcutKey = -1;

		keyTitle = theTitle;
		displayTitle = theTitle;

		if (showsFileMenu()) {
			fileMenu = new JMenu(getFileMenuName());
		}

		if (showsEditMenu()) {
			editMenu = new EditMenu();
		}

		if (showsExtraMenu()) {
			extraMenu = new JMenu(getExtraMenuName());
		}

		windowMenu = new JMenu("Window");

		// Build the standard help menu.

		helpMenu = new JMenu("Help");

		// About TVStudy

		JMenuItem miAbout = new JMenuItem("About TVStudy");
		miAbout.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_PERIOD, AppController.MENU_SHORTCUT_KEY_MASK));
		miAbout.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				AppController.showAbout();
			}
		});
		helpMenu.add(miAbout);

		// Check for Updates

		JMenuItem miUpdate = new JMenuItem("Check for Updates");
		miUpdate.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				AppController.showUpdate();
			}
		});
		helpMenu.add(miUpdate);

		// __________________________________

		helpMenu.addSeparator();

		// Check Installation

		JMenuItem miCheck = new JMenuItem("Check Installation");
		miCheck.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				AppController.checkInstall();
			}
		});
		helpMenu.add(miCheck);

		// __________________________________

		helpMenu.addSeparator();

		// Documentation

		JMenuItem miHelp = new JMenuItem("Documentation");
		miHelp.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, AppController.MENU_SHORTCUT_KEY_MASK));
		miHelp.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				AppController.showHelp();
			}
		});
		helpMenu.add(miHelp);

		// __________________________________

		helpMenu.addSeparator();

		// Preferences

		JMenuItem miPrefs = new JMenuItem("Preferences");
		miPrefs.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, AppController.MENU_SHORTCUT_KEY_MASK));
		miPrefs.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				AppController.showPreferences();
			}
		});
		helpMenu.add(miPrefs);

		// Build the menu bar.

		JMenuBar mBar = new JMenuBar();
		if (null != fileMenu) {
			mBar.add(fileMenu);
		}
		if (null != editMenu) {
			mBar.add(editMenu);
		}
		if (null != extraMenu) {
			mBar.add(extraMenu);
		}
		mBar.add(windowMenu);
		mBar.add(helpMenu);

		setJMenuBar(mBar);

		locationSaved = true;
		disposeOnClose = true;

		setLocation(new Point(100, 40));
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Return the database ID for content being edited or displayed in this window.  This deliberately does not check
	// if the parent is null; as discussed above, in that case the subclass must override this to return a value, so
	// if that override does not exist this should throw an exception.

	public String getDbID() {

		return parent.getDbID();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Return the RootEditor instance, as discussed above this may be null.

	public RootEditor getRootEditor() {

		if (null == parent) {
			return null;
		}
		return parent.getRootEditor();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This is really just a convenience to avoid a cast when the object is typed as an AppEditor.

	public Window getWindow() {

		return this;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Each window has it's own error reporting object by default, but this could be overridden to share.  Subclasses
	// generally use the instance property directly.

	public ErrorReporter getErrorReporter() {

		return errorReporter;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// UI interlock used to prevent undesired side-effects in action listeners.  Particularily needed for JComboBox
	// which fires it's action on programmatic sets of the selection.  Action listeners call blockActions(), if it
	// returns false they must do nothing, which generally means discarding input and re-setting view state.  If
	// blockActions() returns true, input is processed and model state updated as appropriate, ending with a call to
	// blockActionsEnd().  UI setup code may call blockActionsStart() and blockActionsEnd() to make sure actions are
	// blocked and stay blocked during that setup.  Even more emphatic is blockActionsSet() and blockActionsClear(),
	// when blockActionsSet() is called actions remain blocked permanently and other methods have no effect until
	// blockActionsClear() is called.  In fact the initialized state of this is for the set condition, meaning any
	// subclass using this protocol must call blockActionsClear() at some point, typically in windowWillOpen().  Also
	// good practice is to call blockActionsSet() again when closing i.e. in windowWillClose().

	public boolean blockActions() {

		if (blockActionsCount != 0) {
			return false;
		}
		blockActionsStart();
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void blockActionsStart() {

		if (blockActionsCount >= 0) {
			blockActionsCount++;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void blockActionsEnd() {

		if (blockActionsCount > 0) {
			blockActionsCount--;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void blockActionsSet() {

		blockActionsCount = -1;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void blockActionsClear() {

		blockActionsCount = 0;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set the document name display for this frame.  See updateDocumentName() below.

	public void setDocumentName(String theName) {

		documentName = theName;
		updateTitles();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getDocumentName() {

		return documentName;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This is used when the name display for this frame is based on hierarchical context.  Typically implementation
	// will call getDocumentName() on it's document and then extend that name appropriately, also passing the update
	// along to dependent dialogs.  Assuming each new window is constructed with a parent window as it's document the
	// result is a name path that lengthens as the windows "drill down" into document data.

	public void updateDocumentName() {
	}


	//-----------------------------------------------------------------------------------------------------------------
	// These methods are a workaround for a buggy focus management system in Swing.  Set a text field as current,
	// typically on a focus-gained event on the field, and when commitCurrentField() is called the field will be sent
	// a postActionEvent() to fire it's listeners and process uncommitted edits.  This is necessary because sending
	// postActionEvent() on a focus-lost event is unreliable since that event is never sent in some situations.  In at
	// least some of those situations querying for the current focus owner is also unreliable, the focus manager and
	// getFocusOwner() both report there is no focus owner, even though the last field to receive a focus-gained never
	// received a focus-lost.  It is not a case of the event being sent as temporary, the event is just never sent.
	// The main reason for this workaround is because that situation occurs when a menu item is picked.  Focus-lost is
	// never sent, but focus owner checks return null.  That means the item's listeners have no way of finding a text
	// field that might have uncommitted edits.  Proving this is a bug and not just a matter of interpretation is the
	// fact that clicking on a _non-focusable_ button _does_ sent a focus-lost, then a focus-gained again after the
	// button's listeners are done.  If it works like that for buttons but not menu items, it's broken.  The field can
	// be cleared by setting null, however that isn't necessary and typically subclass code doesn't bother.  Since the
	// only place to do that would be focus-lost listeners what would be the point anyway, those are hit-or-miss.  The
	// field action listeners just have to be able to deal with an action event even when they don't have focus.

	public void setCurrentField(JTextField theField) {

		currentField = theField;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public JTextField getCurrentField() {

		return currentField;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Returns true if the commit succeeds, false on error.  Assumes any errors in the listeners will be sent to this
	// window's error reporter using reportValidationError().

	public boolean commitCurrentField() {

		if (null != currentField) {
			errorReporter.clearErrors();
			currentField.postActionEvent();
			return !errorReporter.hasErrors();
		}
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Used by subclasses that implement a basic "dirty flag" method of determining when edits need to be saved.

	public void setDidEdit() {
	}


	//-----------------------------------------------------------------------------------------------------------------
	// These methods can be used by subclasses that need to know when dependent windows change content or close.  The
	// apply-edits method may perform many actions beyond updating model, so it has a return value to indicate failure;
	// any errors will be reported with the error reporter from the editor sending this.

	public boolean applyEditsFrom(AppEditor theEditor) {

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void editorClosing(AppEditor theEditor) {
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The "base" title is set at construction, it is a generic class title.  The "key" title is the base title with a
	// numerical suffix added to make it unique among all open windows, so that title string can be used as a key to
	// save and restore window position and size.  The manager will set the title key property as needed when a window
	// is shown.  If a document name is set, the "display" title is the base title with that name appended, otherwise
	// the display title is the key title.  The superclass gets the display title with an added suffix depicting the
	// menu shortcut key, if set.

	public String getBaseTitle() {

		return baseTitle;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getKeyTitle() {

		return keyTitle;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The title cannot be set directly, it can only be manipulated by the other property setters.

	public void setTitle(String theTitle) {
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getTitle() {

		return displayTitle;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The title key is an integer value that should be unique among all open windows with the same base title.  0 is
	// a legal value however it is not included as part of the actual key title string.

	public void setTitleKey(int theKey) {

		titleKey = theKey;
		updateTitles();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int getTitleKey() {

		return titleKey;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The shortcut key is a single digit 0-9, unique among all windows (of any class) currently in the window menu.

	public void setShortcutKey(int theKey) {

		if ((theKey < 0) || (theKey > 9)) {
			theKey = -1;
		}
		shortcutKey = theKey;
		updateTitles();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int getShortcutKey() {

		return shortcutKey;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update the derived titles, then set the superclass title and the window menu item if defined.

	protected void updateTitles() {

		if (0 == titleKey) {
			keyTitle = baseTitle;
		} else {
			keyTitle = baseTitle + " " + titleKey;
		}

		if ((null == documentName) || (0 == documentName.length())) {
			displayTitle = keyTitle;
		} else {
			displayTitle = baseTitle + ": " + documentName;
		}

		String theTitle = displayTitle;
		if (shortcutKey >= 0) {
			if (InputEvent.CTRL_MASK == AppController.MENU_SHORTCUT_KEY_MASK) {
				theTitle = theTitle + "  Ctrl-" + shortcutKey;
			} else {
				theTitle = theTitle + "  ⌘" + shortcutKey;
			}
		}
		super.setTitle(theTitle);

		if (null != windowMenuItem) {
			windowMenuItem.setText(displayTitle);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Defaults are to show the File and Edit menu but not the Extra menu.  The File and Extra menu names can be
	// changed, Edit and Window can't.  The order is always File - Edit - Extra - Window - Help.

	protected boolean showsFileMenu() {

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected String getFileMenuName() {

		return "File";
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected boolean showsEditMenu() {

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected boolean showsExtraMenu() {

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected String getExtraMenuName() {

		return "Extra";
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Provides the window menu to the manager for updating.

	public JMenu getWindowMenu() {

		return windowMenu;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The manager will set the item from the window menu that represents this window, for title updates.

	public void setWindowMenuItem(JMenuItem theItem) {

		windowMenuItem = theItem;
		if (null != theItem) {
			theItem.setText(displayTitle);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public JMenuItem getWindowMenuItem() {

		return windowMenuItem;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Determines if the manager will save and restore the window location and size based on the final unique window
	// title, the default is true.

	public void setLocationSaved(boolean s) {

		locationSaved = s;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean getLocationSaved() {

		return locationSaved;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The window manager checks this when hiding a window, and will dispose the window if this is set true.  Most
	// subclasses are top-level editors and should be disposed when closed so the default is true, can be set false
	// for windows that are repeatedly hidden and re-shown.

	public void setDisposeOnClose(boolean d) {

		disposeOnClose = d;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean getDisposeOnClose() {

		return disposeOnClose;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by the manager just before the window is made visible; the title has been changed and the window is in
	// the manager's window list.  Subclasses may override to do any last-minute window setup.

	public void windowWillOpen() {
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by the manager when a window-closing event arrives, also by other UI actions that may want to initiate
	// a close.  If this returns true the window can be hidden, if false the close must be aborted.  Subclasses may
	// override this to check for unsaved changes, close dependent windows, etc.

	public boolean windowShouldClose() {

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by the manager just before the window is hidden.  Subclasses may override to do final cleanup.

	public void windowWillClose() {
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Convenience.

	public boolean closeIfPossible() {

		if (!isVisible()) {
			return true;
		}
		if (windowShouldClose()) {
			AppController.hideWindow(this);
			return true;
		}
		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Mostly a convenience, but may need to be overridden if dependent windows need to be closed.  May return false
	// indicating state could not be discarded and the close must abort.

	public boolean closeWithoutSave() {

		if (!isVisible()) {
			return true;
		}
		AppController.hideWindow(this);
		return true;
	}
}
