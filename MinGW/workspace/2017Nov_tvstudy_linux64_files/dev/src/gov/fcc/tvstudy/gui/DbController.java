//
//  DbController.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;
import gov.fcc.tvstudy.core.geo.*;
import gov.fcc.tvstudy.gui.editor.*;
import gov.fcc.tvstudy.gui.run.*;

import java.util.*;
import java.sql.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.text.*;

import javax.swing.*;
import javax.swing.text.*;
import javax.swing.event.*;
import javax.swing.table.*;


//=====================================================================================================================
// GUI database connection controller.  Provides user interface for installing, managing, and opening databases.
// Methods in DbCore provide the actual installation and update code, which no longer requires the GUI, see DbUtil.

public class DbController {

	private static boolean showDbName;

	// Database login dialog.

	private static LoginDialog loginDialog;

	// Open database manager dialogs.

	private static HashMap<String, ManagerDialog> dbManagers = new HashMap<String, ManagerDialog>();


	//=================================================================================================================
	// Login dialog class.

	private static class LoginDialog extends AppDialog {

		private static final String WINDOW_TITLE = "Open Database";

		private JTextField hostField;
		private JTextField nameField;
		private JTextField userField;
		private JPasswordField passField;

		private JButton quitButton;


		//-------------------------------------------------------------------------------------------------------------

		private LoginDialog() {

			super(null, null, WINDOW_TITLE, Dialog.ModalityType.MODELESS);
			setDisposeOnClose(false);

			// Create entry fields.

			hostField = new JTextField(10);
			AppController.fixKeyBindings(hostField);

			nameField = new JTextField(10);
			AppController.fixKeyBindings(nameField);

			userField = new JTextField(10);
			AppController.fixKeyBindings(userField);

			passField = new JPasswordField(10);
			AppController.fixKeyBindings(passField);

			// Buttons.  Open database for editing and running studies.

			JButton openBut = new JButton("Open");
			openBut.setFocusable(false);
			openBut.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doOpenDb(false);
				}
			});

			// Open a dialog for management, e.g. upgrade, uninstall, etc.

			JButton manBut = new JButton("Manage");
			manBut.setFocusable(false);
			manBut.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doOpenDb(true);
				}
			});

			// See AppController.hideWindow(); when the login dialog is the last window open, that will exit the JVM.

			quitButton = new JButton("Quit");
			quitButton.setFocusable(false);
			quitButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (loginDialog.windowShouldClose()) {
						AppController.hideWindow(loginDialog);
					}
				}
			});

			// Dialog layout.

			JLabel hostLabel = new JLabel("Host");
			JLabel nameLabel = new JLabel("Database");
			JLabel userLabel = new JLabel("User");
			JLabel passLabel = new JLabel("Password");

			JPanel fieldsP = new JPanel();
			GroupLayout lo = new GroupLayout(fieldsP);
			lo.setAutoCreateGaps(true);
			lo.setAutoCreateContainerGaps(true);
			fieldsP.setLayout(lo);

			if (showDbName) {

				lo.setHorizontalGroup(lo.createSequentialGroup().
					addGroup(lo.createParallelGroup(GroupLayout.Alignment.TRAILING).
						addComponent(hostLabel).
						addComponent(nameLabel).
						addComponent(userLabel).
						addComponent(passLabel)).
					addGroup(lo.createParallelGroup().
						addComponent(hostField).
						addComponent(nameField).
						addComponent(userField).
						addComponent(passField)));

				lo.setVerticalGroup(lo.createSequentialGroup().
					addGroup(lo.createParallelGroup(GroupLayout.Alignment.BASELINE).
						addComponent(hostLabel).
						addComponent(hostField)).
					addGroup(lo.createParallelGroup(GroupLayout.Alignment.BASELINE).
						addComponent(nameLabel).
						addComponent(nameField)).
					addGroup(lo.createParallelGroup(GroupLayout.Alignment.BASELINE).
						addComponent(userLabel).
						addComponent(userField)).
					addGroup(lo.createParallelGroup(GroupLayout.Alignment.BASELINE).
						addComponent(passLabel).
						addComponent(passField)));

			} else {

				lo.setHorizontalGroup(lo.createSequentialGroup().
					addGroup(lo.createParallelGroup(GroupLayout.Alignment.TRAILING).
						addComponent(hostLabel).
						addComponent(userLabel).
						addComponent(passLabel)).
					addGroup(lo.createParallelGroup().
						addComponent(hostField).
						addComponent(userField).
						addComponent(passField)));

				lo.setVerticalGroup(lo.createSequentialGroup().
					addGroup(lo.createParallelGroup(GroupLayout.Alignment.BASELINE).
						addComponent(hostLabel).
						addComponent(hostField)).
					addGroup(lo.createParallelGroup(GroupLayout.Alignment.BASELINE).
						addComponent(userLabel).
						addComponent(userField)).
					addGroup(lo.createParallelGroup(GroupLayout.Alignment.BASELINE).
						addComponent(passLabel).
						addComponent(passField)));
			}

			JPanel butPl = new JPanel(new FlowLayout(FlowLayout.LEFT));
			butPl.add(manBut);

			JPanel butPr = new JPanel(new FlowLayout(FlowLayout.RIGHT));
			butPr.add(quitButton);
			butPr.add(openBut);

			JPanel butP = new JPanel();
			butP.add(butPl);
			butP.add(butPr);

			Container cp = getContentPane();
			cp.setLayout(new BorderLayout());
			cp.add(fieldsP, BorderLayout.CENTER);
			cp.add(butP, BorderLayout.SOUTH);

			getRootPane().setDefaultButton(openBut);

			pack();

			setMinimumSize(getSize());

			setResizable(true);
			setLocationSaved(true);
		}


		//-------------------------------------------------------------------------------------------------------------

		public String getDbID() {
			return null;
		}


		//-------------------------------------------------------------------------------------------------------------

		public RootEditor getRootEditor() {
			return null;
		}


		//-------------------------------------------------------------------------------------------------------------
		// Check input fields, attempt to open a connection, then open a study manager for full access, or a database
		// manager dialog.  If a window of either type is already open for the database it will be brought to the front
		// regardless of what was requested, only one or the other may be open at a time.

		private void doOpenDb(boolean doManage) {

			// Always clear the password field regardless of other action.

			final String thePass = new String(passField.getPassword());
			passField.setText("");

			// The host name must always be non-empty.  The user and password are optional if the manager dialog is
			// requested; if either is empty, there will be no attempt to actually connect.  That allows the manager
			// dialog to be opened for an inaccessible host, for local cache management only.  Otherwise, both user and
			// password must be non-empty; the password for an actual login can never be blank.  The database name is
			// is always optional, if blank a default is used.  If a name is entered it must only contain letters and
			// digits, no punctuation or whitespace allowed.

			final String theHost = hostField.getText().trim().toLowerCase();
			if (0 == theHost.length()) {
				errorReporter.reportWarning("Please provide a database host name.");
				hostField.requestFocusInWindow();
				return;
			}

			String dbname = nameField.getText().trim().toLowerCase();
			if ((null == dbname) || (0 == dbname.length())) {
				dbname = DbCore.DEFAULT_DB_NAME;
			} else {
				for (int i = 0; i < dbname.length(); i++) {
					if (!Character.isLetterOrDigit(dbname.charAt(i))) {
						if (showDbName) {
							errorReporter.reportWarning("Invalid database name, only letters and numbers allowed.");
							nameField.requestFocusInWindow();
							return;
						} else {
							dbname = DbCore.DEFAULT_DB_NAME;
						}
					}
				}
			}
			final String theName = dbname;

			final String theUser = userField.getText().trim();
			if ((0 == theUser.length()) && !doManage) {
				errorReporter.reportWarning("Please provide a user name.");
				userField.requestFocusInWindow();
				return;
			}

			if ((0 == thePass.length()) && !doManage) {
				errorReporter.reportWarning("Please provide the password.\nBlank passwords are not allowed.");
				passField.requestFocusInWindow();
				return;
			}

			// Attempt the connection, also update cache size if the manager dialog will be shown.  That will be shown
			// if requested, if the database is not installed or needs update, or if errors occur.  See DbCore.DbInfo.

			final boolean willManage = doManage;
			BackgroundWorker<DbCore.DbInfo> worker = new BackgroundWorker<DbCore.DbInfo>(this, WINDOW_TITLE) {
				protected DbCore.DbInfo doBackgroundWork(ErrorLogger errors) {
					DbCore.DbInfo result = new DbCore.DbInfo(theHost, theName, theUser, thePass);
					if (willManage || result.needsManage) {
						result.updateCacheSize();
					}
					return result;
				}
			};
			DbCore.DbInfo theInfo = worker.runWork("Connecting to database, please wait...", errorReporter);
			if ((null == theInfo) || theInfo.connectionFailed) {
				errorReporter.reportError("Cannot connect to the database.");
				if ((null == theInfo) || !doManage) {
					return;
				}
			}

			// Check for an existing study or database manager, bring to front.  Note there are unusual cases in which
			// an existing manager dialog may be identified by either the hostname or UUID, so try both.  Study
			// managers will never be keyed by anything but the UUID as those can't be opened until the UUID is valid.

			ManagerDialog theDbManager = dbManagers.get(theInfo.dbID);
			if (null == theDbManager) {
				theDbManager = dbManagers.get(theInfo.dbHostname);
			}
			if (null != theDbManager) {
				theDbManager.toFront();
				return;
			}

			if (StudyManager.showManager(theInfo.dbID)) {
				if (doManage) {
					errorReporter.reportWarning("All other windows for that database must be closed first.");
				}
				AppController.hideWindow(this);
				return;
			}

			// Open a new study manager or database manager for the database.  When a manager dialog opens, the login
			// dialog is not closed since that might shut down the JVM, see windowShouldClose().  If a study manager is
			// opened from the db manager dialog, it will close both itself and the login dialog if that is still open.

			if (doManage || theInfo.needsManage) {

				theDbManager = new ManagerDialog(theInfo);
				dbManagers.put(theInfo.dbID, theDbManager);
				AppController.showWindow(theDbManager);
				return;

			} else {

				if (DbCore.openDb(theInfo, errorReporter)) {
					if (StudyManager.showManager(theInfo.dbID)) {
						AppController.hideWindow(this);
					}
					return;
				}
			}

			passField.requestFocusInWindow();
		}


		//-------------------------------------------------------------------------------------------------------------
		// When dialog opens, set host and user if previously saved, set focus appropriately.

		public void windowWillOpen() {

			String theHost = AppCore.getProperty(DbCore.DEFAULT_HOST_KEY);
			if (null == theHost) {
				theHost = "";
			}
			hostField.setText(theHost);

			String theName = AppCore.getProperty(DbCore.DEFAULT_NAME_KEY);
			if ((null == theName) || (0 == theName.length())) {
				theName = DbCore.DEFAULT_DB_NAME;
			}
			nameField.setText(theName);

			String theUser = AppCore.getProperty(DbCore.DEFAULT_USER_KEY);
			if (null == theUser) {
				theUser = "";
			}
			userField.setText(theUser);

			passField.setText("");

			if (0 == theHost.length()) {
				hostField.requestFocusInWindow();
			} else {
				if (0 == theUser.length()) {
					userField.requestFocusInWindow();
				} else {
					passField.requestFocusInWindow();
				}
			}

			blockActionsClear();
		}


		//-------------------------------------------------------------------------------------------------------------
		// If there are no managed windows (meaning no windows with Window menus) but there are still other visible
		// windows, don't let the login dialog close since there would be no way to get it back.  When the dialog
		// closes with no managed windows open the JVM will exit, see AppController.hideWindow(), the point is to not
		// let that happen until all windows, managed or not, are closed.

		public boolean windowShouldClose() {

			if (AppController.hasOpenWindows()) {
				blockActionsSet();
				return true;
			}

			for (Window w : Window.getWindows()) {
				if ((w != this) && w.isVisible()) {
					AppController.showMessage(this, "Please close all other open windows first.", "Quit TVStudy",
						AppCore.INFORMATION_MESSAGE);
					w.toFront();
					return false;
				}
			}

			blockActionsSet();
			return true;
		}
	}


	//=================================================================================================================
	// Manager dialog class.  Note this will deal with the possibility that the dbID changes during the time the dialog
	// is showing.  That can happen when the ID is initially set to the hostname because of a failed connection that
	// later succeeds, or in the case of updating a database from the version just before UUIDs were first assigned.
	// Store the original ID at the time of construction; the dialog is in the managers map by that key, so even if the
	// dbID does change later this can still find itself in the map.

	private static class ManagerDialog extends AppDialog {

		private static final String WINDOW_TITLE = "Manage Database";

		private DbCore.DbInfo dbInfo;
		private String originalDbID;

		private JLabel versionLabel;
		private JLabel statusLabel;
		private JLabel cacheLabel;

		private JButton installButton;
		private JButton clearCacheButton;
		private JButton unlockAllButton;
		private JButton updateButton;

		private JButton reloadButton;

		private JButton openButton;


		//-------------------------------------------------------------------------------------------------------------

		private ManagerDialog(DbCore.DbInfo theInfo) {

			super(null, null, WINDOW_TITLE, Dialog.ModalityType.MODELESS);

			dbInfo = theInfo;
			originalDbID = theInfo.dbID;

			// Label fields display information.

			JLabel hostLabel = new JLabel(dbInfo.dbHostname);
			JLabel nameLabel = new JLabel(dbInfo.dbName);
			versionLabel = new JLabel(AppCore.formatVersion(dbInfo.version));
			statusLabel = new JLabel("XXXXXXXXXXXXXXXXXXXX");
			cacheLabel = new JLabel("999.99 GB");

			// Buttons, install button is install or uninstall, label changes as needed see updateControls().

			installButton = new JButton("Uninstall");
			installButton.setFocusable(false);
			installButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (dbInfo.hasRoot) {
						doUninstall();
					} else {
						doInstall();
					}
				}
			});

			clearCacheButton = new JButton("Clear Cache");
			clearCacheButton.setFocusable(false);
			clearCacheButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doClearCache();
				}
			});

			unlockAllButton = new JButton("Unlock All");
			unlockAllButton.setFocusable(false);
			unlockAllButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doUnlockAll();
				}
			});

			updateButton = new JButton("Update");
			updateButton.setFocusable(false);
			updateButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doUpdate();
				}
			});

			// Reload root data button appears only in debug mode, used during development to allow root data e.g.
			// parameters to be edited without having to bump version number.

			if (AppCore.Debug) {
				reloadButton = new JButton("Reload Root Data");
				reloadButton.setFocusable(false);
				reloadButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doReload();
					}
				});
			}

			openButton = new JButton("Open");
			openButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doOpen();
				}
			});

			JButton canBut = new JButton("Done");
			canBut.setFocusable(false);
			canBut.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doCancel();
				}
			});

			// Dialog layout.

			JLabel hostLbl = new JLabel("Host:");
			JLabel nameLbl = new JLabel("Database:");
			JLabel versLbl = new JLabel("Version:");
			JLabel statLbl = new JLabel("Status:");
			JLabel cachLbl = new JLabel("Cache size:");

			JPanel infoP = new JPanel();
			GroupLayout lo = new GroupLayout(infoP);
			lo.setAutoCreateGaps(true);
			lo.setAutoCreateContainerGaps(true);
			infoP.setLayout(lo);

			lo.setHorizontalGroup(lo.createSequentialGroup().
				addGroup(lo.createParallelGroup(GroupLayout.Alignment.TRAILING).
					addComponent(hostLbl).
					addComponent(nameLbl).
					addComponent(versLbl).
					addComponent(statLbl).
					addComponent(cachLbl)).
				addGroup(lo.createParallelGroup().
					addComponent(hostLabel).
					addComponent(nameLabel).
					addComponent(versionLabel).
					addComponent(statusLabel).
					addComponent(cacheLabel)));

			lo.setVerticalGroup(lo.createSequentialGroup().
				addGroup(lo.createParallelGroup(GroupLayout.Alignment.BASELINE).
					addComponent(hostLbl).
					addComponent(hostLabel)).
				addGroup(lo.createParallelGroup(GroupLayout.Alignment.BASELINE).
					addComponent(nameLbl).
					addComponent(nameLabel)).
				addGroup(lo.createParallelGroup(GroupLayout.Alignment.BASELINE).
					addComponent(versLbl).
					addComponent(versionLabel)).
				addGroup(lo.createParallelGroup(GroupLayout.Alignment.BASELINE).
					addComponent(statLbl).
					addComponent(statusLabel)).
				addGroup(lo.createParallelGroup(GroupLayout.Alignment.BASELINE).
					addComponent(cachLbl).
					addComponent(cacheLabel)));

			JPanel butP1 = new JPanel();
			butP1.add(unlockAllButton);
			butP1.add(updateButton);

			JPanel butP2 = new JPanel();
			butP2.add(installButton);
			butP2.add(clearCacheButton);

			JPanel butP3 = new JPanel(new FlowLayout(FlowLayout.RIGHT));
			butP3.add(canBut);
			butP3.add(openButton);

			Container cp = getContentPane();
			cp.setLayout(new BoxLayout(cp, BoxLayout.Y_AXIS));
			cp.add(infoP);
			cp.add(butP1);
			cp.add(butP2);
			if (AppCore.Debug) {
				JPanel butPx = new JPanel();
				butPx.add(reloadButton);
				cp.add(butPx);
			}
			cp.add(butP3);

			getRootPane().setDefaultButton(openButton);

			pack();

			setMinimumSize(getSize());

			setResizable(true);
			setLocationSaved(true);

			updateControls();
		}


		//-------------------------------------------------------------------------------------------------------------

		public String getDbID() {
			return null;
		}


		//-------------------------------------------------------------------------------------------------------------

		public RootEditor getRootEditor() {
			return null;
		}


		//-------------------------------------------------------------------------------------------------------------
		// Update information and control state.  See DbCore.DbInfo for details of the various flags.

		private void updateControls() {

			versionLabel.setText(AppCore.formatVersion(dbInfo.version));

			statusLabel.setText(dbInfo.statusText);

			if (dbInfo.canInstall) {
				installButton.setText("Install");
				installButton.setEnabled(true);
			} else {
				if (dbInfo.canUninstall) {
					installButton.setText("Uninstall");
					installButton.setEnabled(true);
				} else {
					installButton.setEnabled(false);
				}
			}

			if (dbInfo.cacheSize > 0) {
				if (dbInfo.cacheSize >= 1e9) {
					cacheLabel.setText(String.format(Locale.US, "%.2f GB", ((double)dbInfo.cacheSize / 1.e9)));
				} else {
					if (dbInfo.cacheSize >= 1e6) {
						cacheLabel.setText(String.format(Locale.US, "%.1f MB", ((double)dbInfo.cacheSize / 1.e6)));
					} else {
						cacheLabel.setText(String.format(Locale.US, "%.0f kB", ((double)dbInfo.cacheSize / 1.e3)));
					}
				}
				clearCacheButton.setEnabled(true);
			} else {
				cacheLabel.setText("Empty");
				clearCacheButton.setEnabled(false);
			}

			unlockAllButton.setEnabled(dbInfo.canUnlock);

			updateButton.setEnabled(dbInfo.canUpdate);

			if (AppCore.Debug) {
				reloadButton.setEnabled(dbInfo.canOpen);
			}

			openButton.setEnabled(dbInfo.canOpen);

			// Display any error message that occurred during the last update of the DbInfo object.

			if (null != dbInfo.lookupErrorMessage) {
				SwingUtilities.invokeLater(new Runnable() {
					public void run() {
						AppController.showMessage(dbManagers.get(originalDbID), dbInfo.lookupErrorMessage,
							WINDOW_TITLE, AppCore.ERROR_MESSAGE);
						dbInfo.lookupErrorMessage = null;
					}
				});
			}
		}


		//-------------------------------------------------------------------------------------------------------------
		// Install a new database on a host that does not have one.  Note during the creation process the version
		// number is set to 0 which will cause other install, update, or open attempts to fail, see DbCore.installDb().

		private void doInstall() {

			if (!dbInfo.canInstall) {
				return;
			}

			String title = "Install Database";
			errorReporter.setTitle(title);

			BackgroundWorker<Object> worker = new BackgroundWorker<Object>(this, title) {
				protected Object doBackgroundWork(ErrorLogger errors) {
					DbCore.installDb(dbInfo, this, errors);
					return null;
				}
			};

			worker.runWork("Installing database, please wait...", errorReporter);

			updateControls();
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doUninstall() {

			if (!dbInfo.canUninstall) {
				return;
			}

			String title = "Uninstall Database";
			errorReporter.setTitle(title);

			AppController.beep();
			if (JOptionPane.YES_OPTION != JOptionPane.showConfirmDialog(this,
					"This will delete the '" + dbInfo.dbName + "' database from the server, all studies\n" +
					"and other saved data in that database will be lost.  If other applications\n" +
					"are still using the database, those applications will fail.\n\n" +
					"Do you want to continue?", title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)) {
				return;
			}

			BackgroundWorker<Object> worker = new BackgroundWorker<Object>(this, title) {
				protected Object doBackgroundWork(ErrorLogger errors) {
					DbCore.uninstallDb(dbInfo, errors);
					dbInfo.updateCacheSize();
					return null;
				}
			};

			worker.runWork("Uninstalling database, please wait...", errorReporter);

			updateControls();
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doClearCache() {

			if (dbInfo.cacheSize <= 0) {
				return;
			}

			String title = "Clear Cache";
			errorReporter.setTitle(title);

			BackgroundWorker<Object> worker = new BackgroundWorker<Object>(this, title) {
				protected String doBackgroundWork(ErrorLogger errors) {
					AppCore.deleteStudyCache(dbInfo.dbID, 0);
					dbInfo.updateCacheSize();
					return null;
				}
			};
			worker.runWork("Deleting cache files, please wait...", errorReporter);

			updateControls();
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doUnlockAll() {

			if (!dbInfo.canUnlock) {
				return;
			}

			String title = "Unlock All Studies";
			errorReporter.setTitle(title);

			AppController.beep();
			if (JOptionPane.YES_OPTION != JOptionPane.showConfirmDialog(this,
					"This will clear locks on all studies.  Do this only if studies were\n" +
					"not closed properly due to application crashes or network failures;\n" +
					"if this is done when other applications are still using any studies,\n" +
					"those applications will fail and data could become corrupted.\n\n" +
					"Do you want to continue?", title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)) {
				return;
			}

			DbConnection db = dbInfo.db;

			if (db.connect(dbInfo.dbName, errorReporter)) {
				try {

					db.update("UPDATE study SET study_lock = " + Study.LOCK_NONE +
						", lock_count = lock_count + 1, share_count = 0");

					dbInfo.update();

					db.close();

				} catch (SQLException se) {
					db.close();
					db.reportError(errorReporter, se);
				}
			}

			updateControls();
		}


		//-------------------------------------------------------------------------------------------------------------
		// Update the database to the current version.

		private void doUpdate() {

			if (!dbInfo.canUpdate) {
				return;
			}

			String title = "Update Database";
			errorReporter.setTitle(title);

			BackgroundWorker<Object> worker = new BackgroundWorker<Object>(this, title) {
				protected Object doBackgroundWork(ErrorLogger errors) {
					DbCore.updateDb(dbInfo, this, errors);
					return null;
				}
			};

			worker.runWork("Updating database, please wait...", errorReporter);

			updateControls();
		}


		//-------------------------------------------------------------------------------------------------------------
		// Reload the root data in a current-version database.  Only available in debug mode.

		private void doReload() {

			if (!AppCore.Debug || !dbInfo.canOpen) {
				return;
			}

			String title = "Reload Root Data";
			errorReporter.setTitle(title);

			BackgroundWorker<Object> worker = new BackgroundWorker<Object>(this, title) {
				protected Object doBackgroundWork(ErrorLogger errors) {
					DbCore.updateRootData(dbInfo, errors);
					return null;
				}
			};

			worker.runWork("Reloading root data, please wait...", errorReporter);

			updateControls();
		}


		//-------------------------------------------------------------------------------------------------------------
		// Open the database for editing.  Close this dialog, create the study manager and related state, and if the
		// login dialog is still open, close it too.  Note the login dialog will always still be open if this manager
		// dialog is the only other window open.

		private void doOpen() {

			if (!DbCore.openDb(dbInfo, errorReporter)) {
				return;
			}

			dbManagers.remove(originalDbID);
			AppController.hideWindow(this);

			if (StudyManager.showManager(dbInfo.dbID)) {
				if (loginDialog.isVisible()) {
					AppController.hideWindow(loginDialog);
				}
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doCancel() {

			dbManagers.remove(originalDbID);
			blockActionsSet();
			AppController.hideWindow(this);
		}


		//-------------------------------------------------------------------------------------------------------------

		public void windowWillOpen() {

			blockActionsClear();
		}


		//-------------------------------------------------------------------------------------------------------------
		// When closing, remove from the manager dialog list.

		public boolean windowShouldClose() {

			dbManagers.remove(originalDbID);
			blockActionsSet();
			return true;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Show the login dialog to open a new database connection.  On first call, do setup.  The argument determines
	// whether or not the Quit button is enabled; see LoginDialog.windowShouldClose().

	public static void showLogin(boolean canQuit) {

		if (null != loginDialog) {

			if (loginDialog.isVisible()) {
				return;
			}

		} else {

			String str = AppCore.getPreference(AppCore.CONFIG_SHOW_DB_NAME);
			showDbName = ((null != str) && Boolean.valueOf(str).booleanValue());

			loginDialog = new LoginDialog();
		}

		loginDialog.quitButton.setEnabled(canQuit);
		AppController.showWindow(loginDialog);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The application controller gives the login dialog some special treatement, uses this to identify it.

	public static boolean isLoginWindow(Window win) {

		return win == loginDialog;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save and restore window location, and size if variable, from properties.

	public static void saveWindowLocation(Window win, String theDbID, String key) {

		DbCore.setIntegerProperty(theDbID, key + ".x", Integer.valueOf(win.getLocation().x));
		DbCore.setIntegerProperty(theDbID, key + ".y", Integer.valueOf(win.getLocation().y));

		boolean doSize = false;
		if (win instanceof Frame) {
			doSize = ((Frame)win).isResizable();
		} else {
			if (win instanceof Dialog) {
				doSize = ((Dialog)win).isResizable();
			}
		}
		if (doSize) {
			DbCore.setIntegerProperty(theDbID, key + ".sizex", Integer.valueOf(win.getWidth()));
			DbCore.setIntegerProperty(theDbID, key + ".sizey", Integer.valueOf(win.getHeight()));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Default location must be provided in case the keys do not exist in properties.

	public static void restoreWindowLocation(Window win, String theDbID, String key, int locX, int locY) {

		Integer savex = DbCore.getIntegerProperty(theDbID, key + ".x");
		Integer savey = DbCore.getIntegerProperty(theDbID, key + ".y");
		if ((savex != null) && (savey != null)) {
			locX = savex.intValue();
			locY = savey.intValue();
		}
		win.setLocation(locX, locY);

		boolean doSize = false;
		if (win instanceof Frame) {
			doSize = ((Frame)win).isResizable();
		} else {
			if (win instanceof Dialog) {
				doSize = ((Dialog)win).isResizable();
			}
		}
		if (doSize) {
			savex = DbCore.getIntegerProperty(theDbID, key + ".sizex");
			savey = DbCore.getIntegerProperty(theDbID, key + ".sizey");
			if ((savex != null) && (savey != null)) {
				win.setSize(savex.intValue(), savey.intValue());
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save and restore table column widths in properties.  A base name is provided, column identifiers are appended to
	// make the individual property keys.

	public static void saveColumnWidths(String theDbID, String baseName, JTable table) {

		TableColumnModel theColumnModel = table.getColumnModel();
		int columnIndex, columnCount = theColumnModel.getColumnCount();
		TableColumn theColumn;
		for (columnIndex = 0; columnIndex < columnCount; columnIndex++) {
			theColumn = theColumnModel.getColumn(columnIndex);
			DbCore.setIntegerProperty(theDbID, baseName + "." + theColumn.getIdentifier() + ".columnWidth",
				Integer.valueOf(theColumn.getWidth()));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static void restoreColumnWidths(String theDbID, String baseName, JTable table) {

		TableColumnModel theColumnModel = table.getColumnModel();
		int columnIndex, columnCount = theColumnModel.getColumnCount();
		TableColumn theColumn;
		Integer theValue;
		for (columnIndex = 0; columnIndex < columnCount; columnIndex++) {
			theColumn = theColumnModel.getColumn(columnIndex);
			theValue = DbCore.getIntegerProperty(theDbID, baseName + "." + theColumn.getIdentifier() + ".columnWidth");
			if (null != theValue) {
				theColumn.setPreferredWidth(theValue.intValue());
			}
		}
	}
}
