//
//  StudyManager.java
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
import java.text.*;
import java.io.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.table.*;
import javax.swing.GroupLayout.*;


//=====================================================================================================================
// Study manager, allows creating and deleting studies, opening study editors, and running studies in a particular
// database.  Maintains a current list of studies in the database through automatic timed updates.

public class StudyManager extends AppFrame {

	public static final String WINDOW_TITLE = "Study Manager";

	// Database ID.

	private String dbID;

	// Model and table displaying list of studies.

	private StudyListTableModel studyListModel;
	private JTable studyListTable;

	// Map of other objects that manage locked studies, typically study editors and run windows.

	private HashMap<Integer, StudyLockHolder> lockHolders;

	// Label displaying free disk space.

	private JLabel freeSpaceLabel;

	// New study pop-up menu.

	private KeyedRecordMenu newStudyMenu;

	// Menu items.

	private JMenuItem duplicateStudyMenuItem;
	private JMenuItem openStudyMenuItem;
	private JMenuItem deleteStudyMenuItem;
	private JMenuItem runStudyMenuItem;
	private JMenuItem pairStudyMenuItem;
	private JMenuItem clearCacheMenuItem;
	private JMenuItem unlockStudyMenuItem;
	private JMenuItem clearEditLockMenuItem;

	// Buttons.

	private JButton openStudyButton;
	private JButton runStudyButton;

	// Dependent windows and modeless dialogs.

	private TemplateManager templateManager;
	private ExtDbManager extDbManager;
	private RecordFind stationDataViewer;
	private RecordFind studyBuildWizard;
	private StudyRunManager studyRunManager;

	private GeographyEditor geographyEditor;
	private ReceiveAntennaEditor receiveAntennaEditor;
	private boolean editLockSet;

	// A timer used to update the list, a background thread is used for some updates.

	private static final int TIMER_INTERVAL = 2000;           // milliseconds
	private static final int UPDATE_INTERVAL = 60000;
	private static final int CACHE_CHECK_INTERVAL = 600000;

	private javax.swing.Timer updateTimer;

	private long lastListUpdate;

	private Thread updateThread;
	private ArrayDeque<StudyListItem> updateItems;
	private ArrayDeque<StudyListItem> updatedItems;

	// See doDeleteStudy() and editorClosing().

	private boolean holdStudyLocks;

	// Disambiguation.

	private StudyManager outerThis = this;


	//-----------------------------------------------------------------------------------------------------------------
	// Each open database connection is associated with one, and only one, visible instance of StudyManager.  This
	// will show the manager for a given ID, bringing it to front if it already exists, or creating it if needed.  The
	// database connection must already be open in DbCore before a new manager can be created.  The constructor here
	// is private, as is the map of existing managers, so this method is the only way to create/show a manager.

	private static HashMap<String, StudyManager> studyManagers = new HashMap<String, StudyManager>();

	public static boolean showManager(String theDbID) {

		StudyManager theManager = studyManagers.get(theDbID);
		if (null != theManager) {
			theManager.toFront();
			return true;
		}

		if (DbCore.isDbOpen(theDbID)) {
			theManager = new StudyManager(theDbID);
			studyManagers.put(theDbID, theManager);
			AppController.showWindow(theManager);
			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Much of the initial setup does not occur until the window is about to be shown, see windowWillOpen().  This
	// window is the top of all open windows and window hierarchies for the specified database.

	private StudyManager(String theDbID) {

		super(null, WINDOW_TITLE);

		dbID = theDbID;

		// Create model and table for the list.

		studyListModel = new StudyListTableModel();
		studyListTable = studyListModel.createTable(editMenu);

		studyListTable.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent e) {
				if (2 == e.getClickCount()) {
					doOpenStudy(null);
				}
			}
		});

		studyListTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
			public void valueChanged(ListSelectionEvent theEvent) {
				updateControls();
			}
		});

		JPanel listPanel = new JPanel(new BorderLayout());
		listPanel.setBorder(BorderFactory.createTitledBorder("Studies"));
		listPanel.add(AppController.createScrollPane(studyListTable), BorderLayout.CENTER);

		// Keeps track of other objects with open studies.

		lockHolders = new HashMap<Integer, StudyLockHolder>();

		// Display of free disk space.

		freeSpaceLabel = new JLabel("Available disk space XXXXXXXX");

		// Timer for automatic updates and background thread queues, see doUpdates().

		updateTimer = new javax.swing.Timer(TIMER_INTERVAL, new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doUpdates();
			}
		});

		updateItems = new ArrayDeque<StudyListItem>();
		updatedItems = new ArrayDeque<StudyListItem>();

		// Study creation pop-up menu.  There are two codes paths for creating studies, doCreateNewStudy() creates a
		// new empty study and immediately opens it in an edtor, doRunNewStudy() shows a dialog UI for the user to
		// choose a target record and study options, then a study run panel is shown to create and built the study and
		// scenarios, and immediately run the study.  Some study types can be created both ways.

		newStudyMenu = new KeyedRecordMenu();
		newStudyMenu.addItem(new KeyedRecord(0, "New Study..."));
		newStudyMenu.addItem(new KeyedRecord(Study.STUDY_TYPE_TV, Study.getStudyTypeName(Study.STUDY_TYPE_TV)));
		newStudyMenu.addItem(new KeyedRecord(-Study.STUDY_TYPE_TV_IX, Study.getStudyTypeName(Study.STUDY_TYPE_TV_IX)));
		newStudyMenu.addItem(new KeyedRecord(Study.STUDY_TYPE_TV_IX, Study.getStudyTypeName(Study.STUDY_TYPE_TV_IX) +
			" (advanced)"));
		newStudyMenu.addItem(new KeyedRecord(-Study.STUDY_TYPE_TV_OET74,
			Study.getStudyTypeName(Study.STUDY_TYPE_TV_OET74)));
		newStudyMenu.addItem(new KeyedRecord(Study.STUDY_TYPE_TV_OET74,
			Study.getStudyTypeName(Study.STUDY_TYPE_TV_OET74) + " (advanced)"));
		newStudyMenu.addItem(new KeyedRecord(Study.STUDY_TYPE_FM, Study.getStudyTypeName(Study.STUDY_TYPE_FM)));
		newStudyMenu.addItem(new KeyedRecord(-Study.STUDY_TYPE_TV6_FM,
			Study.getStudyTypeName(Study.STUDY_TYPE_TV6_FM)));
		newStudyMenu.addItem(new KeyedRecord(Study.STUDY_TYPE_TV6_FM,
			Study.getStudyTypeName(Study.STUDY_TYPE_TV6_FM) + " (advanced)"));

		newStudyMenu.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					int theKey = newStudyMenu.getSelectedKey();
					newStudyMenu.setSelectedIndex(0);
					blockActionsEnd();
					if (theKey > 0) {
						doCreateNewStudy(theKey);
					} else {
						if (theKey < 0) {
							doRunNewStudy(-theKey);
						}
					}
				}
			}
		});

		// Buttons.

		openStudyButton = new JButton("Open");
		openStudyButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doOpenStudy(null);
			}
		});

		runStudyButton = new JButton("Run");
		runStudyButton.setFocusable(false);
		runStudyButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doRunStudy(false);
			}
		});

		// Do the layout.

		JPanel lButP = new JPanel(new FlowLayout(FlowLayout.LEFT));
		lButP.add(newStudyMenu);

		JPanel cButP = new JPanel();
		cButP.add(freeSpaceLabel);

		JPanel rButP = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		rButP.add(runStudyButton);
		rButP.add(openStudyButton);

		JPanel butP = new JPanel();
		butP.setLayout(new BoxLayout(butP, BoxLayout.X_AXIS));
		butP.add(lButP);
		butP.add(cButP);
		butP.add(rButP);

		Container cp = getContentPane();
		cp.setLayout(new BorderLayout());
		cp.add(listPanel, BorderLayout.CENTER);
		cp.add(butP, BorderLayout.SOUTH);

		getRootPane().setDefaultButton(openStudyButton);

		pack();

		Dimension theSize = new Dimension(790, 500);
		setMinimumSize(theSize);
		setSize(theSize);

		// Build the file menu.

		fileMenu.removeAll();

		// Previous

		JMenuItem miPrevious = new JMenuItem("Previous");
		miPrevious.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_UP, AppController.MENU_SHORTCUT_KEY_MASK));
		miPrevious.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doPrevious();
			}
		});
		fileMenu.add(miPrevious);

		// Next

		JMenuItem miNext = new JMenuItem("Next");
		miNext.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, AppController.MENU_SHORTCUT_KEY_MASK));
		miNext.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doNext();
			}
		});
		fileMenu.add(miNext);

		// __________________________________

		fileMenu.addSeparator();

		// Refresh List

		JMenuItem miRefresh = new JMenuItem("Refresh List");
		miRefresh.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				updateStudyList(false, true);
			}
		});
		fileMenu.add(miRefresh);

		// __________________________________

		fileMenu.addSeparator();

		// New study ->

		JMenu meNew = new JMenu("New study");

		JMenuItem miNew = new JMenuItem(Study.getStudyTypeName(Study.STUDY_TYPE_TV));
		miNew.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doCreateNewStudy(Study.STUDY_TYPE_TV);
			}
		});
		meNew.add(miNew);

		miNew = new JMenuItem(Study.getStudyTypeName(Study.STUDY_TYPE_TV_IX));
		miNew.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doRunNewStudy(Study.STUDY_TYPE_TV_IX);
			}
		});
		meNew.add(miNew);

		miNew = new JMenuItem(Study.getStudyTypeName(Study.STUDY_TYPE_TV_IX) + " (advanced)");
		miNew.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doCreateNewStudy(Study.STUDY_TYPE_TV_IX);
			}
		});
		meNew.add(miNew);

		miNew = new JMenuItem(Study.getStudyTypeName(Study.STUDY_TYPE_TV_OET74));
		miNew.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doRunNewStudy(Study.STUDY_TYPE_TV_OET74);
			}
		});
		meNew.add(miNew);

		miNew = new JMenuItem(Study.getStudyTypeName(Study.STUDY_TYPE_TV_OET74) + " (advanced)");
		miNew.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doCreateNewStudy(Study.STUDY_TYPE_TV_OET74);
			}
		});
		meNew.add(miNew);

		miNew = new JMenuItem(Study.getStudyTypeName(Study.STUDY_TYPE_FM));
		miNew.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doCreateNewStudy(Study.STUDY_TYPE_FM);
			}
		});
		meNew.add(miNew);

		miNew = new JMenuItem(Study.getStudyTypeName(Study.STUDY_TYPE_TV6_FM));
		miNew.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doRunNewStudy(Study.STUDY_TYPE_TV6_FM);
			}
		});
		meNew.add(miNew);

		miNew = new JMenuItem(Study.getStudyTypeName(Study.STUDY_TYPE_TV6_FM) + " (advanced)");
		miNew.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doCreateNewStudy(Study.STUDY_TYPE_TV6_FM);
			}
		});
		meNew.add(miNew);

		fileMenu.add(meNew);

		// __________________________________

		fileMenu.addSeparator();

		// Duplicate...

		duplicateStudyMenuItem = new JMenuItem("Duplicate...");
		duplicateStudyMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doDuplicateStudy();
			}
		});
		fileMenu.add(duplicateStudyMenuItem);

		// Open

		openStudyMenuItem = new JMenuItem("Open");
		openStudyMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, AppController.MENU_SHORTCUT_KEY_MASK));
		openStudyMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doOpenStudy(null);
			}
		});
		fileMenu.add(openStudyMenuItem);

		// Delete

		deleteStudyMenuItem = new JMenuItem("Delete");
		deleteStudyMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doDeleteStudy();
			}
		});
		fileMenu.add(deleteStudyMenuItem);

		// __________________________________

		fileMenu.addSeparator();

		// Run...

		runStudyMenuItem = new JMenuItem("Run...");
		runStudyMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, AppController.MENU_SHORTCUT_KEY_MASK));
		runStudyMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doRunStudy(false);
			}
		});
		fileMenu.add(runStudyMenuItem);

		// Run Pair Study...

		pairStudyMenuItem = new JMenuItem("Run Pair Study...");
		pairStudyMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doRunStudy(true);
			}
		});
		fileMenu.add(pairStudyMenuItem);

		// __________________________________

		fileMenu.addSeparator();

		// Clear Cache

		clearCacheMenuItem = new JMenuItem("Clear Cache");
		clearCacheMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doClearCache();
			}
		});
		fileMenu.add(clearCacheMenuItem);

		// Unlock

		unlockStudyMenuItem = new JMenuItem("Unlock");
		unlockStudyMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doUnlockStudy();
			}
		});
		fileMenu.add(unlockStudyMenuItem);

		// Build the extra menu.

		extraMenu.removeAll();

		// View Station Data

		JMenuItem miExtDbView = new JMenuItem("View Station Data");
		miExtDbView.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, AppController.MENU_SHORTCUT_KEY_MASK));
		miExtDbView.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doShowStationDataViewer();
			}
		});
		extraMenu.add(miExtDbView);

		// Station Data Manager

		JMenuItem miExtDb = new JMenuItem("Station Data Manager");
		miExtDb.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doShowExtDbManager();
			}
		});
		extraMenu.add(miExtDb);

		// __________________________________

		extraMenu.addSeparator();

		// Template Manager

		JMenuItem miTemplates = new JMenuItem("Template Manager");
		miTemplates.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doShowTemplateManager();
			}
		});
		extraMenu.add(miTemplates);

		// __________________________________

		extraMenu.addSeparator();

		// Geography Editor

		JMenuItem miGeo = new JMenuItem("Geography Editor");
		miGeo.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				showGeographyEditor(dbID, 0, "", 0, "", Geography.MODE_ALL, 0);
			}
		});
		extraMenu.add(miGeo);

		// Receive Antenna Editor

		JMenuItem miAnt = new JMenuItem("Receive Antenna Editor");
		miAnt.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				showReceiveAntennaEditor(dbID);
			}
		});
		extraMenu.add(miAnt);

		// Clear Edit Lock

		clearEditLockMenuItem = new JMenuItem("Clear Edit Lock");
		clearEditLockMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doClearEditLock();
			}
		});
		extraMenu.add(clearEditLockMenuItem);

		// __________________________________

		extraMenu.addSeparator();

		// Output Settings...

		JMenuItem miOut = new JMenuItem("Output Settings...");
		miOut.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				AppController.showWindow(new OutputConfigDialog(outerThis));
			}
		});
		extraMenu.add(miOut);

		// Image Color Maps...

		JMenuItem miColor = new JMenuItem("Image Color Maps...");
		miColor.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				AppController.showWindow(new ColorMapEditor(outerThis));
			}
		});
		extraMenu.add(miColor);

		// Initial update of UI control state.

		updateControls();
		updateDocumentName();
		updateFreeSpace();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update the state of menu items and buttons per current table selection.  If a single study is selected and has
	// a window, open is enabled (brings the existing window front); duplicate, run, and unlock are disabled; delete
	// and clear are enabled if the window holds an edit lock, else disabled.  When a window has an edit lock it will
	// respond to closeWithoutSave() so the study can be deleted, and clearing cache is safe during editing.  If a
	// single study is selected and does not have a window, all actions are enabled since the true state of the study
	// record in the database is unknown, status information in the list item may be old.  All UI action methods will
	// independently verify and set the study lock.  If multiple studies are selected only delete and clear are
	// enabled, but they are always enabled.

	private void updateControls() {

		int rowCount = studyListTable.getSelectedRowCount();
		boolean eOpen = false, eDuplicate = false, eDelete = false, eRun = false, eRunPair = false, eClear = false,
			eUnlock = false;

		if (1 == rowCount) {

			StudyListItem theItem =
				studyListModel.get(studyListTable.convertRowIndexToModel(studyListTable.getSelectedRow()));
			StudyLockHolder lockHolder = lockHolders.get(theItem.key);

			if (null != lockHolder) {

				eOpen = true;
				if (Study.LOCK_EDIT == lockHolder.getStudyLock()) {
					eDelete = true;
					eClear = true;
				}

			} else {

				eOpen = true;
				eDuplicate = true;
				eDelete = true;
				eRun = (AppCore.maxEngineProcessCount > 0);
				eRunPair = (eRun && (Study.STUDY_TYPE_TV == theItem.studyType));
				eClear = true;
				eUnlock = true;
			}

		} else {

			if (rowCount > 1) {

				eDelete = true;
				eClear = true;
			}
		}

		openStudyMenuItem.setEnabled(eOpen);
		openStudyButton.setEnabled(eOpen);

		duplicateStudyMenuItem.setEnabled(eDuplicate);

		deleteStudyMenuItem.setEnabled(eDelete);

		runStudyMenuItem.setEnabled(eRun);
		runStudyButton.setEnabled(eRun);

		pairStudyMenuItem.setEnabled(eRunPair);

		clearCacheMenuItem.setEnabled(eClear);

		unlockStudyMenuItem.setEnabled(eUnlock);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getDbID() {

		return dbID;
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected String getFileMenuName() {

		return "Study";
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected boolean showsExtraMenu() {

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected String getExtraMenuName() {

		return "Database";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void updateDocumentName() {

		setDocumentName(DbCore.getHostDbName(dbID));

		if (null != templateManager) {
			templateManager.updateDocumentName();
		}
		if (null != extDbManager) {
			extDbManager.updateDocumentName();
		}
		if (null != geographyEditor) {
			geographyEditor.updateDocumentName();
		}
		if (null != receiveAntennaEditor) {
			receiveAntennaEditor.updateDocumentName();
		}
		if (null != stationDataViewer) {
			stationDataViewer.updateDocumentName();
		}
		if (null != studyBuildWizard) {
			studyBuildWizard.updateDocumentName();
		}
		if (null != studyRunManager) {
			studyRunManager.updateDocumentName();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Show geography editor for a specified object scope, see GeographyEditor.  This may fail if the editor is
	// visible and the scope change fails due to a failed or cancelled save of an edited object.  This and the receive
	// antenna editor APIs are static because manager objects are private, however thease actually operate on a
	// specific manager instance.  A geography key may be provided, it will be selected in the editor if possible.

	public static boolean showGeographyEditor(String theDbID, int theStudyKey, String theStudyName, int theSourceKey,
			String theSourceName, int theMode, int theGeoKey) {

		StudyManager theManager = studyManagers.get(theDbID);
		if (null == theManager) {
			return false;
		}

		if ((null != theManager.geographyEditor) && theManager.geographyEditor.isVisible()) {
			theManager.geographyEditor.toFront();
			return theManager.geographyEditor.setScope(theStudyKey, theStudyName, theSourceKey, theSourceName,
				theMode, theGeoKey);
		}

		if (!theManager.acquireEditLock()) {
			return false;
		}

		if (null == theManager.geographyEditor) {
			theManager.geographyEditor = new GeographyEditor(theManager);
		}

		theManager.geographyEditor.setScope(theStudyKey, theStudyName, theSourceKey, theSourceName, theMode,
			theGeoKey);
		AppController.showWindow(theManager.geographyEditor);
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by other editors that may have shown the geography editor, to update names associated with scope keys.
	// If the editor is no longer showing or is now in a different scope it will just ignore this.

	public static void updateGeographyEditor(String theDbID, int theStudyKey, String theStudyName, int theSourceKey,
			String theSourceName) {

		StudyManager theManager = studyManagers.get(theDbID);
		if ((null != theManager) && (null != theManager.geographyEditor)) {
			theManager.geographyEditor.updateScope(theStudyKey, theStudyName, theSourceKey, theSourceName);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Determine if a geography is in use by an open study.

	public static boolean isGeographyInUse(String theDbID, int theGeoKey) {

		StudyManager theManager = studyManagers.get(theDbID);
		if (null == theManager) {
			return false;
		}

		for (StudyLockHolder lockHolder : theManager.lockHolders.values()) {
			if (lockHolder instanceof StudyEditor) {
				if (((StudyEditor)lockHolder).isGeographyInUse(theGeoKey)) {
					return true;
				}
			}
		}
		return false;
	};


	//-----------------------------------------------------------------------------------------------------------------
	// Called by GeographyEditor when edits are saved or geographies deleted, forward to all open StudyEditors.

	public static void updateGeographies(String theDbID) {

		StudyManager theManager = studyManagers.get(theDbID);
		if (null == theManager) {
			return;
		}

		for (StudyLockHolder lockHolder : theManager.lockHolders.values()) {
			if (lockHolder instanceof StudyEditor) {
				((StudyEditor)lockHolder).updateGeographies();
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called when a study or source editor that opened the geography editor for it's scope is now closing, forward
	// this to the geography editor if visible.  If that is still in the same scope it will attempt to close.  Return
	// is true if the geography editor closed, or didn't need to because the scope did not match.  Return is false if
	// the scope matched but the editor could not close e.g. the user cancelled a save.

	public static boolean geographyScopeShouldClose(String theDbID, int theStudyKey, int theSourceKey) {

		StudyManager theManager = studyManagers.get(theDbID);
		if (null == theManager) {
			return false;
		}

		if ((null != theManager.geographyEditor) && theManager.geographyEditor.isVisible()) {
			return theManager.geographyEditor.scopeShouldClose(theStudyKey, theSourceKey);
		}
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Show receive antenna editor.

	public static boolean showReceiveAntennaEditor(String theDbID) {

		StudyManager theManager = studyManagers.get(theDbID);
		if (null == theManager) {
			return false;
		}

		if ((null != theManager.receiveAntennaEditor) && theManager.receiveAntennaEditor.isVisible()) {
			theManager.receiveAntennaEditor.toFront();
			return true;
		}

		if (!theManager.acquireEditLock()) {
			return false;
		}

		if (null == theManager.receiveAntennaEditor) {
			theManager.receiveAntennaEditor = new ReceiveAntennaEditor(theManager);
		}

		AppController.showWindow(theManager.receiveAntennaEditor);
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Determine if a receive antenna is in use by geography edit state.

	public static boolean isReceiveAntennaInUse(String theDbID, int theAntKey) {

		StudyManager theManager = studyManagers.get(theDbID);
		if (null == theManager) {
			return false;
		}

		if (null != theManager.geographyEditor) {
			return theManager.geographyEditor.isReceiveAntennaInUse(theAntKey);
		}
		return false;
	};


	//-----------------------------------------------------------------------------------------------------------------
	// Called by ReceiveAntennaEditor when edits are saved or antennas deleted, forward to GeographyEditor if open.

	public static void updateReceiveAntennas(String theDbID) {

		StudyManager theManager = studyManagers.get(theDbID);
		if (null == theManager) {
			return;
		}

		if (null != theManager.geographyEditor) {
			theManager.geographyEditor.updateReceiveAntennas();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Geographies and receive antennas are used by reference and so are essentially extensions of study state.  Key
	// lists in the database are the primary means of concurrency protection, but those can only protect saved state.
	// Additional checks of as-edited UI state must also be done via isGeographyInUse() and isReceiveAntennaInUse().
	// However, those cannot check state in another application concurrently editing the same database; hence such
	// concurrent editing has to be prevented with a separate locking protocol.  It would be unnecessarily complex to
	// lock individual geographies and receive antennas so a single lock for the entire state of both is used, this
	// ensures only one application can have an active GeographyEditor and/or ReceiveAntennaEditor at a time.  In case
	// of application crash, the lock can be cleared manually.

	private boolean acquireEditLock() {

		if (editLockSet) {
			return true;
		}

		DbConnection db = DbCore.connectDb(dbID, errorReporter);
		if (null == db) {
			return false;
		}

		String errmsg = null;

		try {
			db.update("LOCK TABLES edit_lock WRITE");
			db.query("SELECT locked FROM edit_lock");
			db.next();
			if (!db.getBoolean(1)) {
				db.update("UPDATE edit_lock SET locked = true");
				editLockSet = true;
			}
		} catch (SQLException se) {
			errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
			db.reportError(se);
		}

		try {
			db.update("UNLOCK TABLES");
		} catch (SQLException se) {
			db.reportError(se);
		}

		DbCore.releaseDb(db);

		if (null != errmsg) {
			errorReporter.reportError(errmsg);
			return false;
		}

		if (!editLockSet) {
			errorReporter.reportWarning(
				"Another application is currently editing geographies or\n" +
				"receive antennas in this database.  Please try again later.");
			return false;
		}

		clearEditLockMenuItem.setEnabled(false);
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void clearEditLock() {

		DbConnection db = DbCore.connectDb(dbID);
		if (null != db) {
			try {
				db.update("UPDATE edit_lock SET locked = false");
			} catch (SQLException se) {
				db.reportError(se);
			}
			DbCore.releaseDb(db);
		}

		editLockSet = false;
		clearEditLockMenuItem.setEnabled(true);
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doClearEditLock() {

		if (editLockSet) {
			return;
		}

		String title = "Clear Edit Lock";
		errorReporter.setTitle(title);

		AppController.beep();
		if (JOptionPane.YES_OPTION != JOptionPane.showConfirmDialog(this,
				"This will clear the editing lock for geography data and receive antennas.\n" +
				"Do this only if the lock was not cleared due to an application crash or\n" +
				"network failure.  If this is done while another application is still\n" +
				"editing the data, studies may be corrupted and future runs may fail.\n\n" +
				"Do you want to continue?", title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)) {
			return;
		}

		clearEditLock();
	}


	//=================================================================================================================
	// Data class for the study list.  Methods to handle synchronized update of the cache size on a background thread.

	private class StudyListItem {

		private Integer key;

		private String name;
		private String description;
		private int studyLock;
		private int lockCount;
		private int studyType;
		private String templateName;
		private String extDbName;

		private long cacheSize;
		private long lastCacheCheck;


		//-------------------------------------------------------------------------------------------------------------

		private synchronized long getCacheSize() {

			return cacheSize;
		}


		//-------------------------------------------------------------------------------------------------------------
		// Check if cache size update is needed based on an interval from the last update.

		private synchronized boolean isCacheSizeExpired() {

			return ((System.currentTimeMillis() - lastCacheCheck) > CACHE_CHECK_INTERVAL);
		}


		//-------------------------------------------------------------------------------------------------------------
		// Update the cache size.  This is a non-synchronized wrapper because getStudyCacheSize() can take a long time.

		private void updateCacheSize() {

			setCacheSize(AppCore.getStudyCacheSize(dbID, key.intValue()));
		}


		//-------------------------------------------------------------------------------------------------------------

		private synchronized void setCacheSize(long newSize) {

			cacheSize = newSize;
			lastCacheCheck = System.currentTimeMillis();
		}


		//-------------------------------------------------------------------------------------------------------------

		private synchronized void clearCacheSize() {

			cacheSize = -1;
			lastCacheCheck = 0;
		}
	}


	//=================================================================================================================
	// Table model for the study list.

	private class StudyListTableModel extends AbstractTableModel {

		private static final String STUDY_TYPE_COLUMN = "Type";
		private static final String STUDY_NAME_COLUMN = "Name";
		private static final String STUDY_TEMPLATE_COLUMN = "Template";
		private static final String STUDY_EXT_DB_NAME_COLUMN = "Default Station Data";
		private static final String STUDY_CACHE_SIZE_COLUMN = "Cache Size";

		private String[] columnNames = {
			STUDY_TYPE_COLUMN,
			STUDY_NAME_COLUMN,
			STUDY_TEMPLATE_COLUMN,
			STUDY_EXT_DB_NAME_COLUMN,
			STUDY_CACHE_SIZE_COLUMN
		};

		private static final int STUDY_TYPE_INDEX = 0;
		private static final int STUDY_NAME_INDEX = 1;
		private static final int STUDY_TEMPLATE_INDEX = 2;
		private static final int STUDY_EXT_DB_NAME_INDEX = 3;
		private static final int STUDY_CACHE_SIZE_INDEX = 4;

		private ArrayList<StudyListItem> modelRows;


		//-------------------------------------------------------------------------------------------------------------

		private StudyListTableModel() {

			super();

			modelRows = new ArrayList<StudyListItem>();
		}


		//-------------------------------------------------------------------------------------------------------------
		// The table gets a custom renderer that sets text color based on status information, blue for open editor,
		// red for running, gray for locked by some other application.  The status could be out-of-date so the display
		// states are advisory, except when the study is locked by an open window belonging to this application so the
		// status is certain and UI controls may be disabled according to the state of the selected item; see
		// updateControls().

		private JTable createTable(EditMenu theEditMenu) {

			JTable theTable = new JTable(this);
			AppController.configureTable(theTable, theEditMenu);

			DefaultTableCellRenderer theRend = new DefaultTableCellRenderer() {
				public Component getTableCellRendererComponent(JTable t, Object o, boolean s, boolean f, int r,
					int c) {

					JLabel comp = (JLabel)super.getTableCellRendererComponent(t, o, s, f, r, c);

					StudyListItem theItem = modelRows.get(t.convertRowIndexToModel(r));
					StudyLockHolder lockHolder = lockHolders.get(theItem.key);
					if (!s) {
						if (null != lockHolder) {
							switch (lockHolder.getStudyLock()) {
								case Study.LOCK_NONE:
								default:
									comp.setForeground(Color.BLACK);
									break;
								case Study.LOCK_EDIT:
								case Study.LOCK_ADMIN:
									comp.setForeground(Color.GREEN.darker());
									break;
								case Study.LOCK_RUN_EXCL:
								case Study.LOCK_RUN_SHARE:
									comp.setForeground(Color.RED);
									break;
							}
						} else {
							if (Study.LOCK_NONE != theItem.studyLock) {
								comp.setForeground(Color.GRAY);
							} else {
								comp.setForeground(Color.BLACK);
							}
						}
					}

					// The name column gets the study description, if non-empty, as tool-tip text.

					int col = t.convertColumnIndexToModel(c);
					String desc = theItem.description.trim();
					if ((STUDY_NAME_INDEX == col) && (desc.length() > 0)) {
						if (!s) {
							comp.setForeground(Color.BLUE);
						}
						String[] words = desc.split("\\s+");
						boolean started = false;
						StringBuilder ttt = new StringBuilder();
						int wl, ll = 0;
						for (int i = 0; i < words.length; i++) {
							wl = words[i].length(); 
							if (wl > 0) {
								if (!started) {
									ttt.append("<HTML>");
									started = true;
								}
								ttt.append(words[i]);
								ll += wl;
								if (ll > 35) {
									ttt.append("<BR>");
									ll = 0;
								} else {
									ttt.append(" ");
								}
							}
						}
						if (started) {
							ttt.append("</HTML>");
							comp.setToolTipText(ttt.toString());
						} else {
							comp.setToolTipText(null);
						}
					} else {
						comp.setToolTipText(null);
					}

					return comp;
				}
			};

			TableColumn theColumn = theTable.getColumn(STUDY_TYPE_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[12]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[16]);

			theColumn = theTable.getColumn(STUDY_NAME_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[16]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[25]);

			theColumn = theTable.getColumn(STUDY_TEMPLATE_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[10]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[14]);

			theColumn = theTable.getColumn(STUDY_EXT_DB_NAME_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[14]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[18]);

			theColumn = theTable.getColumn(STUDY_CACHE_SIZE_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[6]);

			// Use a customized row sorter so the cache size column sorts numerically.

			TableStringConverter theConverter = new TableStringConverter() {
				public String toString(TableModel theModel, int rowIndex, int columnIndex) {

					StudyListItem theItem = modelRows.get(rowIndex);

					switch (columnIndex) {

						case STUDY_TYPE_INDEX:
							return String.valueOf(theItem.studyType);

						case STUDY_NAME_INDEX:
							return theItem.name;

						case STUDY_TEMPLATE_INDEX:
							return theItem.templateName;

						case STUDY_EXT_DB_NAME_INDEX:
							return theItem.extDbName;

						case STUDY_CACHE_SIZE_INDEX:
							return String.format(Locale.US, "%018d", theItem.getCacheSize());
					}

					return "";
				}
			};

			TableRowSorter<StudyListTableModel> theSorter = new TableRowSorter<StudyListTableModel>(this);
			theSorter.setStringConverter(theConverter);
			theTable.setRowSorter(theSorter);

			return theTable;
		}


		//-------------------------------------------------------------------------------------------------------------
		// When a new list is set, it is merged with the existing list based on matching study keys.  Existing objects
		// are re-used to preserve the state of the cache size property.  Other properties are updated from the new
		// objects, but the order of items is determined by the new list.  Also queue or re-queue items needing cache
		// size update.  First move existing items to a map by key.

		private void setItems(ArrayList<StudyListItem> newItems) {

			HashMap<Integer, StudyListItem> oldItems = new HashMap<Integer, StudyListItem>();
			for (StudyListItem theItem : modelRows) {
				oldItems.put(theItem.key, theItem);
			}

			// Clear the model, and the update queues.

			modelRows.clear();

			clearUpdateItems();
			clearUpdatedItems();

			// Scan the new list and merge with old objects.  Ignore duplicates, no two items can have the same key.

			if ((null != newItems) && !newItems.isEmpty()) {

				HashSet<Integer> allKeys = new HashSet<Integer>(newItems.size());
				StudyListItem theItem;

				for (StudyListItem newItem : newItems) {

					if (!allKeys.add(newItem.key)) {
						continue;
					}

					theItem = oldItems.remove(newItem.key);

					if (null == theItem) {

						theItem = newItem;

					} else {

						theItem.name = newItem.name;
						theItem.description = newItem.description;
						theItem.studyLock = newItem.studyLock;
						theItem.lockCount = newItem.lockCount;
						theItem.studyType = newItem.studyType;
						theItem.templateName = newItem.templateName;
						theItem.extDbName = newItem.extDbName;
					}

					modelRows.add(theItem);

					if (theItem.isCacheSizeExpired()) {
						addUpdateItem(theItem);
					}
				}
			}

			fireTableDataChanged();
		}


		//-------------------------------------------------------------------------------------------------------------

		private void remove(int rowIndex) {

			modelRows.remove(rowIndex);
			fireTableRowsDeleted(rowIndex, rowIndex);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void remove(StudyListItem theItem) {

			int rowIndex = modelRows.indexOf(theItem);
			if (rowIndex >= 0) {
				modelRows.remove(rowIndex);
				fireTableRowsDeleted(rowIndex, rowIndex);
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		private void itemWasChanged(int rowIndex) {

			fireTableRowsUpdated(rowIndex, rowIndex);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void itemWasChanged(StudyListItem theItem) {

			int rowIndex = modelRows.indexOf(theItem);
			if (rowIndex >= 0) {
				fireTableRowsUpdated(rowIndex, rowIndex);
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		private int indexOf(StudyListItem theItem) {

			return modelRows.indexOf(theItem);
		}


		//-------------------------------------------------------------------------------------------------------------

		private int indexOfStudy(Integer studyKey) {

			int rowIndex = 0;
			for (StudyListItem theItem : modelRows) {
				if (theItem.key.equals(studyKey)) {
					return rowIndex;
				}
				rowIndex++;
			}

			return -1;
		}


		//-------------------------------------------------------------------------------------------------------------

		private StudyListItem get(int rowIndex) {

			return modelRows.get(rowIndex);
		}


		//-------------------------------------------------------------------------------------------------------------

		public int getColumnCount() {

			return columnNames.length;
		}


		//-------------------------------------------------------------------------------------------------------------

		public String getColumnName(int columnIndex) {

			return columnNames[columnIndex];
		}


		//-------------------------------------------------------------------------------------------------------------

		public int getRowCount() {

			return modelRows.size();
		}
			

		//-------------------------------------------------------------------------------------------------------------

		public Object getValueAt(int rowIndex, int columnIndex) {

			StudyListItem theItem = modelRows.get(rowIndex);

			switch (columnIndex) {

				case STUDY_TYPE_INDEX:
					return Study.getStudyTypeName(theItem.studyType);

				case STUDY_NAME_INDEX:
					return theItem.name;

				case STUDY_TEMPLATE_INDEX:
					return theItem.templateName;

				case STUDY_EXT_DB_NAME_INDEX:
					return theItem.extDbName;

				case STUDY_CACHE_SIZE_INDEX:
					return AppCore.formatBytes(theItem.getCacheSize());
			}

			return "";
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The item updating mechanism.  A background thread is used along with two FIFO queues, updateItems for input to
	// the thread, updatedItems for output.  The thread polls items off the input queue, updates them, and pushes them
	// on the output queue.  When the input queue is empty, the thread exits.  This method is called regularily by a
	// timer on the Swing event thread.  It first checks if it is time to update the entire list, if so just clear the
	// output queue since all rows will be updated.  Otherwise poll items off the output queue and update table rows.

	private void doUpdates() {

		updateFreeSpace();

		if ((System.currentTimeMillis() - lastListUpdate) > UPDATE_INTERVAL) {

			updateStudyList(true, true);

		} else {

			StudyListItem theItem;
			do {
				theItem = getUpdatedItem();
				if (null != theItem) {
					studyListModel.itemWasChanged(theItem);
				}
			} while (theItem != null);
		}

		// If the input queue is empty or the background thread is already running, that's all.

		if (isUpdateItemsEmpty() || ((null != updateThread) && updateThread.isAlive())) {
			return;
		}

		// Start a new background thread, update items on the queue.  Always check each item to see if it really needs
		// update; an item might have inadvertently been queued more than once.

		updateThread = new Thread(new Runnable() {
			public void run() {
				StudyListItem theItem;
				do {
					theItem = getUpdateItem();
					if ((null != theItem) && theItem.isCacheSizeExpired()) {
						theItem.updateCacheSize();
						addUpdatedItem(theItem);
					}
				} while (null != theItem);
			}
		});
		updateThread.start();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Synchronized access to the background thread queues.

	private synchronized void addUpdateItem(StudyListItem newItem) {

		updateItems.add(newItem);
	}


	//-----------------------------------------------------------------------------------------------------------------

	private synchronized StudyListItem getUpdateItem() {

		return updateItems.poll();
	}


	//-----------------------------------------------------------------------------------------------------------------

	private synchronized boolean isUpdateItemsEmpty() {

		return updateItems.isEmpty();
	}


	//-----------------------------------------------------------------------------------------------------------------

	private synchronized void clearUpdateItems() {

		updateItems.clear();
	}


	//-----------------------------------------------------------------------------------------------------------------

	private synchronized void addUpdatedItem(StudyListItem newItem) {

		updatedItems.add(newItem);
	}


	//-----------------------------------------------------------------------------------------------------------------

	private synchronized StudyListItem getUpdatedItem() {

		return updatedItems.poll();
	}


	//-----------------------------------------------------------------------------------------------------------------

	private synchronized void clearUpdatedItems() {

		updatedItems.clear();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update the study list to current database contents, optionally preserving the table selection.  If this is
	// called from the timer it won't show any error UI.  Note the last update timestamp is set after an attempted
	// updated even if an error occurs, so repeated attempts are throttled in case of database connection problems.
	// If an error does occur, the existing list is not changed.

	private void updateStudyList(boolean fromTimer, boolean preserveSelection) {

		String title = "Refresh Study List";

		ErrorReporter errors = null;
		if (!fromTimer) {
			errorReporter.setTitle(title);
			errors = errorReporter;
		}

		ArrayList<StudyListItem> list = getItems(errors);
		lastListUpdate = System.currentTimeMillis();
		if (null == list) {
			return;
		}

		int[] selRows = null;
		HashSet<StudyListItem> selectedItems = null;

		if (preserveSelection && (studyListTable.getSelectedRowCount() > 0)) {
			selRows = studyListTable.getSelectedRows();
			selectedItems = new HashSet<StudyListItem>(selRows.length);
			for (int i = 0; i < selRows.length; i++) {
				selectedItems.add(studyListModel.get(studyListTable.convertRowIndexToModel(selRows[i])));
			}
		}

		studyListTable.clearSelection();

		studyListModel.setItems(list);

		if (selectedItems != null) {

			int n = 0, i, j, m, t;

			for (StudyListItem theItem : selectedItems) {
				i = studyListModel.indexOf(theItem);
				if (i >= 0) {
					selRows[n++] = studyListTable.convertRowIndexToView(i);
				}
			}

			if (n > 0) {

				for (i = 0; i < n - 1; i++) {
					m = i;
					for (j = i + 1; j < n; j++) {
						if (selRows[j] < selRows[m]) {
							m = j;
						}
					}
					if (m != i) {
						t = selRows[i];
						selRows[i] = selRows[m];
						selRows[m] = t;
					}
				}

				m = selRows[0];
				for (i = 1; i < n; i++) {
					if (selRows[i] > (selRows[i - 1] + 1)) {
						studyListTable.addRowSelectionInterval(m, selRows[i - 1]);
						m = selRows[i];
					}
				}
				studyListTable.addRowSelectionInterval(m, selRows[n - 1]);
			}
		}

		updateControls();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update display of free space on disk.  There are checks when study runs are started to estimate whether enough
	// space is available for a study, but if space appears very low in absolute here, change the label color.

	private void updateFreeSpace() {

		AppCore.FreeSpaceInfo theInfo = AppCore.getFreeSpace(dbID);

		if (theInfo.sameFileStore) {
			freeSpaceLabel.setText("Available disk space " + AppCore.formatBytes(theInfo.totalFreeSpace));
		} else {
			freeSpaceLabel.setText("Available disk space " + AppCore.formatBytes(theInfo.totalFreeSpace) +
				" (cache " + AppCore.formatBytes(theInfo.cacheFreeSpace) + ", output " +
				AppCore.formatBytes(theInfo.outputFreeSpace) + ")");
		}

		if ((theInfo.cacheFreeSpace < 10000000000L) || (theInfo.outputFreeSpace < 10000000000L)) {
			freeSpaceLabel.setForeground(Color.RED);
		} else {
			freeSpaceLabel.setForeground(Color.BLACK);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve a list of all studies in the database.

	private ArrayList<StudyListItem> getItems(ErrorReporter errors) {

		ArrayList<StudyListItem> result = new ArrayList<StudyListItem>();

		StudyListItem theItem;
		int theKey;
		ExtDb theDb;

		DbConnection db = DbCore.connectDb(dbID, errors);
		if (null != db) {
			try {

				db.query(
				"SELECT " +
					"study.study_key, " +
					"study.name, " +
					"study.description, " +
					"study.study_lock, " +
					"study.lock_count, " +
					"study.study_type, " +
					"template.name, " +
					"study.ext_db_key " +
				"FROM " +
					"study " +
					"JOIN template USING (template_key) " +
				"ORDER BY 1");

				while (db.next()) {

					theItem = new StudyListItem();

					theItem.key = Integer.valueOf(db.getInt(1));
					theItem.name = db.getString(2);
					theItem.description = db.getString(3);
					theItem.studyLock = db.getInt(4);
					theItem.lockCount = db.getInt(5);
					theItem.studyType = db.getInt(6);
					theItem.templateName = db.getString(7);

					theItem.extDbName = "";
					theKey = db.getInt(8);
					if (theKey > 0) {
						theItem.extDbName = ExtDb.getExtDbDescription(dbID, Integer.valueOf(theKey));
					}

					theItem.cacheSize = -1;

					result.add(theItem);
				}

				DbCore.releaseDb(db);

			} catch (SQLException se) {
				DbCore.releaseDb(db);
				DbConnection.reportError(errors, se);
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Re-query the database record for a study, update list item properties.  Return the study list item as updated,
	// or null if the study record no longer exists (the item is removed from the list) or an error occurs.

	private StudyListItem checkStudy(int rowIndex, ErrorReporter errors) {

		StudyListItem theItem = studyListModel.get(rowIndex);

		// Update properties from the database.

		boolean error = false, notfound = false;
		int theKey;
		ExtDb theDb;

		DbConnection db = DbCore.connectDb(dbID, errors);
		if (null != db) {
			try {

				db.query(
				"SELECT " +
					"study.name, " +
					"study.description, " +
					"study.study_lock, " +
					"study.lock_count, " +
					"template.name, " +
					"study.ext_db_key " +
				"FROM " +
					"study " +
					"JOIN template USING (template_key) " +
				"WHERE " +
					"study.study_key = " + theItem.key);

				if (db.next()) {

					theItem.name = db.getString(1);
					theItem.description = db.getString(2);
					theItem.studyLock = db.getInt(3);
					theItem.lockCount = db.getInt(4);
					theItem.templateName = db.getString(5);

					theItem.extDbName = "";
					theKey = db.getInt(6);
					if (theKey > 0) {
						theItem.extDbName = ExtDb.getExtDbDescription(dbID, Integer.valueOf(theKey));
					}

				} else {
					notfound = true;
				}

				DbCore.releaseDb(db);

			} catch (SQLException se) {
				DbCore.releaseDb(db);
				error = true;
				DbConnection.reportError(errors, se);
			}

		} else {
			return null;
		}

		if (notfound) {
			if (null != errors) {
				errors.reportError("The study does not exist.");
			}
			studyListModel.remove(rowIndex);
			return null;
		}

		if (error) {
			return null;
		}

		studyListModel.itemWasChanged(rowIndex);

		// Queue it for cache update if needed.

		if (theItem.isCacheSizeExpired()) {
			addUpdateItem(theItem);
		}

		return theItem;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Move selection up or down in the table.

	private void doPrevious() {

		int size = studyListTable.getRowCount();
		int rowIndex = studyListTable.getSelectedRow();
		if ((size > 0) && (rowIndex != 0)) {
			if (rowIndex < 0) {
				rowIndex = size - 1;
			} else {
				rowIndex--;
			}
			studyListTable.setRowSelectionInterval(rowIndex, rowIndex);
			studyListTable.scrollRectToVisible(studyListTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doNext() {

		int size = studyListTable.getRowCount();
		int rowIndex = studyListTable.getSelectedRow();
		if ((size > 0) && (rowIndex < (size - 1))) {
			if (rowIndex < 0) {
				rowIndex = 0;
			} else {
				rowIndex++;
			}
			studyListTable.setRowSelectionInterval(rowIndex, rowIndex);
			studyListTable.scrollRectToVisible(studyListTable.getCellRect(rowIndex, 0, true));
		}
	}


	//=================================================================================================================
	// Dialog for input to create a new study, see doCreateNewStudy().  Provides UI for entering study name, selecting
	// study template, and selecting an external station data set.  The study name will be checked for uniqueness
	// before this will close.  The station data set choices may be restricted based on record type.

	private class StudyCreateNew extends AppDialog {

		private int recordType;

		private JTextField nameField;
		private KeyedRecordMenu templateMenu;
		private KeyedRecordMenu extDbMenu;

		private boolean canceled;

		private String studyName;
		private int templateKey;
		private Integer extDbKey;


		//-------------------------------------------------------------------------------------------------------------

		private StudyCreateNew(String title, int theRecordType) {

			super(outerThis, title, Dialog.ModalityType.APPLICATION_MODAL);

			recordType = theRecordType;

			// Create the name field.

			nameField = new JTextField(20);
			AppController.fixKeyBindings(nameField);

			JPanel namePanel = new JPanel();
			namePanel.setBorder(BorderFactory.createTitledBorder("Study Name"));
			namePanel.add(nameField);

			// Menus for the template and station data selections, these will be populated later, see windowWillOpen().

			templateMenu = new KeyedRecordMenu();
			templateMenu.setPrototypeDisplayValue(new KeyedRecord(0, "XyXyXyXyXyXyXyXyXyXyXyX"));

			JPanel templatePanel = new JPanel();
			templatePanel.setBorder(BorderFactory.createTitledBorder("Template"));
			templatePanel.add(templateMenu);

			extDbMenu = new KeyedRecordMenu();
			extDbMenu.setPrototypeDisplayValue(new KeyedRecord(0, "XyXyXyXyXyXyXyXyXyXyXyX"));

			JPanel extDbPanel = new JPanel();
			extDbPanel.setBorder(BorderFactory.createTitledBorder("Default Station Data"));
			extDbPanel.add(extDbMenu);

			// Buttons.

			JButton okButton = new JButton("Create");
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

			// Do the layout.

			JPanel topPanel = new JPanel();
			topPanel.add(namePanel);

			JPanel midPanel = new JPanel();
			midPanel.add(templatePanel);
			midPanel.add(extDbPanel);

			JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
			buttonPanel.add(cancelButton);
			buttonPanel.add(okButton);

			Container cp = getContentPane();
			cp.setLayout(new BoxLayout(cp, BoxLayout.Y_AXIS));
			cp.add(topPanel);
			cp.add(midPanel);
			cp.add(buttonPanel);

			getRootPane().setDefaultButton(okButton);

			pack();

			setMinimumSize(getSize());

			setResizable(true);
			setLocationSaved(true);
		}


		//-------------------------------------------------------------------------------------------------------------

		public void updateDocumentName() {

			setDocumentName(parent.getDocumentName());
		}


		//-------------------------------------------------------------------------------------------------------------
		// Check inputs, close if valid.  The menu selections cannot be invalid, see windowWillOpen(), the dialog
		// will not appear unless those menus are populated.

		private void doOK() {

			errorReporter.clearTitle();

			String theName = nameField.getText().trim();
			if (!DbCore.checkStudyName(theName, dbID, errorReporter)) {
				return;
			}
			studyName = theName;

			templateKey = templateMenu.getSelectedKey();
			if (templateKey <= 0) {
				errorReporter.reportWarning("Please choose a study template.");
				return;
			}

			int theKey = extDbMenu.getSelectedKey();
			if (theKey > 0) {
				ExtDb theDb = ExtDb.getExtDb(dbID, Integer.valueOf(theKey));
				if ((null == theDb) || theDb.deleted) {
					errorReporter.reportWarning("That station data set has been deleted, please select another.");
					return;
				}
				extDbKey = theDb.dbKey;
			} else {
				extDbKey = null;
			}

			canceled = false;

			blockActionsSet();
			AppController.hideWindow(this);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doCancel() {

			canceled = true;

			blockActionsSet();
			AppController.hideWindow(this);
		}


		//-------------------------------------------------------------------------------------------------------------

		public void windowWillOpen() {

			studyName = "";
			templateKey = 0;
			extDbKey = null;

			nameField.setText("");
			nameField.requestFocusInWindow();

			templateMenu.removeAllItems();
			extDbMenu.removeAllItems();

			setLocationRelativeTo(getOwner());

			canceled = false;

			blockActionsClear();

			SwingUtilities.invokeLater(new Runnable() {
				public void run() {

					errorReporter.setTitle("Load Template List");

					ArrayList<KeyedRecord> list = Template.getTemplateInfoList(dbID, errorReporter);
					if (null != list) {
						templateMenu.addAllItems(list);
					}

					errorReporter.setTitle("Load Station Data List");

					list = ExtDb.getExtDbList(dbID, recordType, errorReporter);
					if (null != list) {
						extDbMenu.addAllItems(list);
					}
					extDbMenu.addItem(new KeyedRecord(0, "(None)"));
				}
			});
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean windowShouldClose() {

			canceled = true;

			blockActionsSet();
			return true;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create and open a new study.  Show a StudyCreateNew dialog to get name, template, and default station data,
	// then create a new empty study and open it in an editor.

	private void doCreateNewStudy(int theStudyType) {

		String title = "Create " + Study.getStudyTypeName(theStudyType) + " Study";
		errorReporter.setTitle(title);

		// A TV IX study created this way uses the study build UI and related code due to the complexity of the initial
		// setup and the fact that this study type can never be entirely empty.  However the resulting study is still
		// incomplete and manual edits must be made before it can run.  See doRunNewStudy().

		if (Study.STUDY_TYPE_TV_IX == theStudyType) {

			if ((null != studyBuildWizard) && studyBuildWizard.isVisible()) {
				AppController.beep();
				studyBuildWizard.toFront();
				return;
			}

			StudyBuildIxCheck theBuild = new StudyBuildIxCheck(dbID);
			theBuild.buildFullStudy = false;
			theBuild.loadDefaults();

			studyBuildWizard = new RecordFind(this, title, 0, Source.RECORD_TYPE_TV);
			if (null != theBuild.extDb) {
				studyBuildWizard.setDefaultExtDbKey(theBuild.extDb.key);
			}
			studyBuildWizard.setNote("Select proposal TV station record for the new study");
			studyBuildWizard.setAccessoryPanel(new OptionsPanel.IxCheckStudy(this, theBuild));
			studyBuildWizard.setApply(new String[] {"Create"}, true, true);

			AppController.showWindow(studyBuildWizard);
			return;
		}

		// All other study types can be created empty.

		int recordType = Source.RECORD_TYPE_TV;
		if (Study.STUDY_TYPE_FM == theStudyType) {
			recordType = Source.RECORD_TYPE_FM;
		}

		final StudyCreateNew theDialog = new StudyCreateNew(title, recordType);
		AppController.showWindow(theDialog);
		if (theDialog.canceled) {
			return;
		}

		// Create the new study.

		final int studyType = theStudyType;
		BackgroundWorker<Integer> theWorker = new BackgroundWorker<Integer>(this, title) {
			protected Integer doBackgroundWork(ErrorLogger errors) {
				return Study.createNewStudy(dbID, theDialog.studyName, studyType, theDialog.templateKey,
					theDialog.extDbKey, errors);
			}
		};

		errorReporter.clearMessages();

		Integer studyKey = theWorker.runWork("Creating new study, please wait...", errorReporter);
		if (null == studyKey) {
			return;
		}

		errorReporter.showMessages();

		// Update the study list, select the new study, and proceed directly to opening it.

		updateStudyList(false, false);

		int rowIndex = studyListModel.indexOfStudy(studyKey);
		if (rowIndex < 0) {
			return;
		}

		rowIndex = studyListTable.convertRowIndexToView(rowIndex);
		studyListTable.setRowSelectionInterval(rowIndex, rowIndex);
		studyListTable.scrollRectToVisible(studyListTable.getCellRect(rowIndex, 0, true));

		doOpenStudy(null);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Build and run a new study.  This is for study types that show a more complex initial UI and then have study
	// scenarios built programmatically, followed by immediately running the study.  The UI is presented in a
	// RecordFind dialog with an OptionPanel subclass specific to the study type set as an accessory panel.  The
	// actual process of building the study is managed by a StudyBuild subclass object set up by the option panel,
	// the StudyBuild object is in turn managed by a RunPanel displayed in the study run manager.  This will set up
	// the RecordFind dialog, the rest happens in applyEditsFrom().

	private void doRunNewStudy(int theStudyType) {

		if ((null != studyBuildWizard) && studyBuildWizard.isVisible()) {
			AppController.beep();
			studyBuildWizard.toFront();
			return;
		}

		String title = "Build " + Study.getStudyTypeName(theStudyType) + " Study";
		errorReporter.setTitle(title);

		// Normally the dialog will show two buttons, "Build" and "Build & Run".  However if run UI is disabled due
		// to some problem with the study engine (see AppCore.initialize()), only "Build" will appear.

		String[] optButtons;
		if (AppCore.maxEngineProcessCount > 0) {
			optButtons = new String[] {"Build", "Build & Run"};
		} else {
			optButtons = new String[] {"Build"};
		}

		switch (theStudyType) {

			default: {
				return;
			}

			case Study.STUDY_TYPE_TV_IX: {

				StudyBuildIxCheck theBuild = new StudyBuildIxCheck(dbID);
				theBuild.loadDefaults();

				studyBuildWizard = new RecordFind(this, title, 0, Source.RECORD_TYPE_TV);
				if (null != theBuild.extDb) {
					studyBuildWizard.setDefaultExtDbKey(theBuild.extDb.key);
				}
				studyBuildWizard.setNote("Select proposal TV station record for the new study");
				studyBuildWizard.setAccessoryPanel(new OptionsPanel.IxCheckStudy(this, theBuild));
				studyBuildWizard.setApply(optButtons, false, true);

				break;
			}

			case Study.STUDY_TYPE_TV_OET74: {

				StudyBuildWireless theBuild = new StudyBuildWireless(dbID);

				studyBuildWizard = new RecordFind(this, title, 0, Source.RECORD_TYPE_TV);
				studyBuildWizard.setNote("Select protected TV station record for the new study");
				studyBuildWizard.setAccessoryPanel(new OptionsPanel.WirelessStudy(this, theBuild));
				studyBuildWizard.setApply(optButtons, true, true);

				break;
			}

			case Study.STUDY_TYPE_TV6_FM: {

				StudyBuildTV6FM theBuild = new StudyBuildTV6FM(dbID);

				studyBuildWizard = new RecordFind(this, title, 0, Source.RECORD_TYPE_TV);
				studyBuildWizard.setNote("Select TV channel 6 station record for the new study");
				studyBuildWizard.setAccessoryPanel(new OptionsPanel.TV6FMStudy(this, theBuild));
				studyBuildWizard.setApply(optButtons, true, true);

				break;
			}
		}

		AppController.showWindow(studyBuildWizard);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Duplicate an existing study.  Make sure the study is not open, check study status, prompt for a new name, make
	// sure it is unique, do the duplication, then open the new study.

	private void doDuplicateStudy() {

		if (studyListTable.getSelectedRowCount() != 1) {
			return;
		}

		String title = "Duplicate Study";
		errorReporter.setTitle(title);

		int rowIndex = studyListTable.convertRowIndexToModel(studyListTable.getSelectedRow());
		StudyListItem theItem = checkStudy(rowIndex, errorReporter);
		if (null == theItem) {
			return;
		}

		StudyLockHolder lockHolder = lockHolders.get(theItem.key);
		if (null != lockHolder) {
			if (lockHolder.isVisible()) {
				AppController.beep();
				lockHolder.toFront();
				return;
			}
			lockHolders.remove(theItem.key);
		}

		if (Study.LOCK_NONE != theItem.studyLock) {
			errorReporter.reportWarning("Could not duplicate study '" + theItem.name + "':\n" +
				"The study is in use by another application.");
			return;
		}

		String theName = "";
		do {
			theName = (String)(JOptionPane.showInputDialog(this, "Enter a name for the new study", title,
				JOptionPane.QUESTION_MESSAGE, null, null, theItem.name));
			if (null == theName) {
				return;
			}
			theName = theName.trim();
		} while (!DbCore.checkStudyName(theName, dbID, errorReporter));

		final int oldKey = theItem.key.intValue();
		final String newName = theName;

		BackgroundWorker<Integer> theWorker = new BackgroundWorker<Integer>(this, title) {
			protected Integer doBackgroundWork(ErrorLogger errors) {
				return Study.duplicateStudy(dbID, oldKey, newName, errors);
			}
		};

		Integer theKey = theWorker.runWork("Duplicating study, please wait...", errorReporter);
		if (null == theKey) {
			return;
		}

		updateStudyList(false, false);

		rowIndex = studyListModel.indexOfStudy(theKey);
		if (rowIndex < 0) {
			return;
		}

		rowIndex = studyListTable.convertRowIndexToView(rowIndex);
		studyListTable.setRowSelectionInterval(rowIndex, rowIndex);
		studyListTable.scrollRectToVisible(studyListTable.getCellRect(rowIndex, 0, true));
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Open a study editor for the selected study.  If some lock holder for that study already exists bring it's
	// containing window to the front, regardless of what it is (editor, run manager, etc.).  This may also create an
	// editor for an already-open study passed as argument; that is used from doCreateNewStudy().

	private void doOpenStudy(Study theStudy) {

		String title = "Open Study";
		errorReporter.setTitle(title);

		int rowIndex = -1;
		StudyListItem theItem = null;

		if (null == theStudy) {

			if (studyListTable.getSelectedRowCount() != 1) {
				return;
			}

			rowIndex = studyListTable.convertRowIndexToModel(studyListTable.getSelectedRow());
			theItem = checkStudy(rowIndex, errorReporter);
			if (null == theItem) {
				return;
			}

			StudyLockHolder lockHolder = lockHolders.get(theItem.key);
			if (null != lockHolder) {
				if (lockHolder.isVisible()) {
					lockHolder.toFront();
					return;
				}
				lockHolders.remove(theItem.key);
			}

			final int theKey = theItem.key.intValue();

			BackgroundWorker<Study> theWorker = new BackgroundWorker<Study>(this, title) {
				protected Study doBackgroundWork(ErrorLogger errors) {
					return Study.getStudy(dbID, theKey, errors);
				}
			};

			theStudy = theWorker.runWork("Opening study, please wait...", errorReporter);
			if (null == theStudy) {
				return;
			}

		// When a study is passed as argument assume it is newly-created and not already in the study list, which also
		// means it cannot already have an open editor or other lock holder.

		} else {

			updateStudyList(false, false);

			rowIndex = studyListModel.indexOfStudy(Integer.valueOf(theStudy.key));
			if (rowIndex < 0) {
				return;
			}

			int i = studyListTable.convertRowIndexToView(rowIndex);
			studyListTable.setRowSelectionInterval(i, i);
			studyListTable.scrollRectToVisible(studyListTable.getCellRect(i, 0, true));

			theItem = studyListModel.get(rowIndex);
		}

		// Creating the StudyEditData model and the StudyEditor has a higher-than-usual risk of unexpected failure
		// such as running out of heap memory.  Catch anything that might be thrown, report it and unlock the study.

		RootEditor theEditor;
		try {
			theEditor = new StudyEditor(this, new StudyEditData(theStudy));
		} catch (Throwable t) {
			Study.unlockStudy(dbID, theStudy.key, theStudy.lockCount);
			AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
			errorReporter.reportError("Unexpected error:\n" + t);
			return;
		}

		theItem.studyLock = theStudy.studyLock;
		theItem.lockCount = theStudy.lockCount;

		AppController.showWindow(theEditor);
		lockHolders.put(theItem.key, (StudyLockHolder)theEditor);

		studyListModel.itemWasChanged(rowIndex);
		updateControls();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Run a study.  A RunStart dialog does most of the work such as locking and pre-checks, see RunStart.setStudy().
	// The dialog creates a RunPanel which is passed to the run manager when the start dialog reports it is ready, see
	// applyEditsFrom().  Normal and pair study runs only differ in the RunStart subclass used.  Also if checks done
	// at startup in AppCore.initialize() indicate the engine is either not installed properly or there is not enough
	// memory for even one engine process to run, all run UI is disabled.

	private void doRunStudy(boolean pairRun) {

		if (AppCore.maxEngineProcessCount < 1) {
			return;
		}

		if (studyListTable.getSelectedRowCount() != 1) {
			return;
		}

		errorReporter.setTitle(RunStartStudy.WINDOW_TITLE);

		int rowIndex = studyListTable.convertRowIndexToModel(studyListTable.getSelectedRow());
		StudyListItem theItem = checkStudy(rowIndex, errorReporter);
		if (null == theItem) {
			return;
		}

		StudyLockHolder lockHolder = lockHolders.get(theItem.key);
		if (null != lockHolder) {
			if (lockHolder.isVisible()) {
				AppController.beep();
				lockHolder.toFront();
				return;
			}
			lockHolders.remove(theItem.key);
		}

		RunStart theDialog = null;
		if (pairRun) {
			theDialog = new RunStartPairStudy(this);
		} else {
			theDialog = new RunStartStudy(this);
		}

		if (!theDialog.setStudy(theItem.key.intValue(), errorReporter)) {
			return;
		}
		AppController.showWindow(theDialog);

		theItem.studyLock = theDialog.getStudyLock();
		theItem.lockCount = theDialog.getLockCount();

		lockHolders.put(theItem.key, theDialog);

		studyListModel.itemWasChanged(rowIndex);
		updateControls();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Delete the selected study or studies.  Confirm that each one is to be deleted, if there is an existing lock
	// holder try to close it.  The deletes can take significant time, i.e. due to cache file deletes, so use a
	// background thread worker to do the actual deleting once the list of studies to delete has been confirmed.

	private void doDeleteStudy() {

		String title = "Delete Study";
		errorReporter.setTitle(title);

		int rowCount = studyListTable.getSelectedRowCount();
		if (0 == rowCount) {
			return;
		}

		final ArrayList<StudyListItem> toDelete = new ArrayList<StudyListItem>();

		int[] selRows = studyListTable.getSelectedRows();
		StudyListItem theItem;
		StudyLockHolder lockHolder;
		int result;
		boolean noclose;

		for (int i = 0; i < selRows.length; i++) {

			theItem = studyListModel.get(studyListTable.convertRowIndexToModel(selRows[i]));

			AppController.beep();
			result = JOptionPane.showConfirmDialog(this, "Are you sure you want to delete study '" +
				theItem.name + "'?", title, JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
			if (JOptionPane.YES_OPTION != result) {
				if (JOptionPane.CANCEL_OPTION == result) {
					return;
				}
				continue;
			}

			// Close an open study window if needed.  Set the holdStudyLocks flag so when the window calls back to
			// editorClosing() during the close, that method will not release the study lock.

			lockHolder = lockHolders.get(theItem.key);
			if (null != lockHolder) {
				if (lockHolder.isVisible()) {
					holdStudyLocks = true;
					noclose = !lockHolder.closeWithoutSave();
					holdStudyLocks = false;
					if (noclose) {
						errorReporter.reportWarning("Could not close a window for study '" + theItem.name + "'.\n" +
							"Please close the window manually and try again.");
						continue;
					}
				}
				lockHolders.remove(theItem.key);
			}

			toDelete.add(theItem);
		}

		if (toDelete.isEmpty()) {
			return;
		}

		BackgroundWorker<Object> theWorker = new BackgroundWorker<Object>(this, title) {
			protected Object doBackgroundWork(ErrorLogger errors) {
				deleteStudies(errors, toDelete);
				return null;
			}
		};

		theWorker.runWork("Deleting studies, please wait...", errorReporter);

		updateStudyList(false, true);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called on the background thread.

	private void deleteStudies(ErrorLogger errors, ArrayList<StudyListItem> toDelete) {

		boolean error = false, notfound = false;
		String errmsg = "";
		int theLock = 0;
		DbConnection db;
		String rootName = DbCore.getDbName(dbID);

		for (StudyListItem theItem : toDelete) {

			db = DbCore.connectDb(dbID, errors);
			if (null == db) {
				return;
			}

			error = false;
			errmsg = "";
			notfound = false;

			// Check the study lock state, OK if it is unlocked or locked by an open window with an edit lock.  If
			// unlocked, lock it.  If locked by another application, fail.  If the study no longer exists ignore it.

			try {

				db.update("LOCK TABLES study WRITE");

				db.query(
				"SELECT " +
					"study_lock, " +
					"lock_count " +
				"FROM " +
					"study " +
				"WHERE " +
					"study_key = " + theItem.key);

				if (db.next()) {

					theLock = db.getInt(1);

					if (Study.LOCK_NONE == theLock) {

						db.update("UPDATE study SET study_lock = " + Study.LOCK_ADMIN +
							", lock_count = lock_count + 1 WHERE study_key = " + theItem.key);

					} else {

						if (!lockHolders.containsKey(theItem.key) || (Study.LOCK_EDIT != theLock) ||
								(theLock != theItem.studyLock) || (db.getInt(2) != theItem.lockCount)) {					
							error = true;
							errmsg = "The study is in use by another application.";
						}
					}

				} else {
					notfound = true;
				}

			} catch (SQLException se) {
				error = true;
				errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
				db.reportError(se);
			}

			try {
				db.update("UNLOCK TABLES");
			} catch (SQLException se) {
				db.reportError(se);
			}

			if (error) {
				DbCore.releaseDb(db);
				errors.reportError("Cannot delete study '" + theItem.name + "':\n" + errmsg);
				continue;
			}

			if (notfound) {
				DbCore.releaseDb(db);
				continue;
			}

			// Delete the study table record and drop the database.

			try {

				db.update("DELETE FROM study WHERE study_key = " + theItem.key);
				db.update("DROP DATABASE IF EXISTS " + rootName + "_" + theItem.key);

			} catch (SQLException se) {
				db.reportError(se);
			}

			DbCore.releaseDb(db);

			// Delete study engine cache files and application properties.

			AppCore.deleteStudyCache(dbID, theItem.key.intValue());

			DbCore.deleteProperty(dbID, StudyEditor.WINDOW_TITLE + " " + theItem.key + "%");

			final StudyListItem listItem = theItem;
			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					studyListModel.remove(listItem);
				}
			});
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Delete study engine cache files for selected study or studies.  No confirmation; an unintended delete is not
	// serious, cached data will simply be re-calculated.  Can be slow, use a background thread.

	private void doClearCache() {

		String title = "Clear Cache";
		errorReporter.setTitle(title);

		int rowCount = studyListTable.getSelectedRowCount();
		if (0 == rowCount) {
			return;
		}

		final ArrayList<StudyListItem> toClear = new ArrayList<StudyListItem>();
		int[] selRows = studyListTable.getSelectedRows();
		for (int i = 0; i < selRows.length; i++) {
			toClear.add(studyListModel.get(studyListTable.convertRowIndexToModel(selRows[i])));
		}

		BackgroundWorker<Object> theWorker = new BackgroundWorker<Object>(this, title) {
			protected Object doBackgroundWork(ErrorLogger errors) {
				clearCaches(errors, toClear);
				return null;
			}
		};

		theWorker.runWork("Deleting cache files, please wait...", errorReporter);

		updateStudyList(false, true);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// See comments in deleteStudies(), this will lock the study during the cache clear, unless it is open in a window
	// with an edit lock, in that case the cache can be cleared safely under that existing lock.

	private void clearCaches(ErrorLogger errors, ArrayList<StudyListItem> toClear) {

		boolean error = false, notfound = false, lockSet = false;
		String errmsg = "";
		DbConnection db;

		for (StudyListItem theItem : toClear) {

			db = DbCore.connectDb(dbID, errors);
			if (null == db) {
				return;
			}

			error = false;
			errmsg = "";
			notfound = false;
			lockSet = false;

			try {

				db.update("LOCK TABLES study WRITE");

				db.query("SELECT study_lock, lock_count FROM study WHERE study_key = " + theItem.key);

				if (db.next()) {

					int theLock = db.getInt(1);

					if (Study.LOCK_NONE == theLock) {

						theItem.lockCount = db.getInt(2);
						db.update("UPDATE study SET study_lock = " + Study.LOCK_ADMIN +
							", lock_count = lock_count + 1 WHERE study_key = " + theItem.key);
						lockSet = true;
						theItem.studyLock = Study.LOCK_ADMIN;
						theItem.lockCount++;
						final StudyListItem listItem = theItem;
						SwingUtilities.invokeLater(new Runnable() {
							public void run() {
								studyListModel.itemWasChanged(listItem);
							}
						});

					} else {

						if (!lockHolders.containsKey(theItem.key) || (Study.LOCK_EDIT != theLock) ||
								(theLock != theItem.studyLock) || (db.getInt(2) != theItem.lockCount)) {					
							error = true;
							errmsg = "The study is in use by another application.";
						}
					}

				} else {
					notfound = true;
				}

			} catch (SQLException se) {
				error = true;
				errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
				db.reportError(se);
			}

			try {
				db.update("UNLOCK TABLES");
			} catch (SQLException se) {
				db.reportError(se);
			}

			if (error) {
				DbCore.releaseDb(db);
				errors.reportError("Cache cannot be cleared for study '" + theItem.name + "':\n" + errmsg);
				continue;
			}

			if (notfound) {
				DbCore.releaseDb(db);
				continue;
			}

			AppCore.deleteStudyCache(dbID, theItem.key.intValue());

			theItem.clearCacheSize();

			if (lockSet) {
				try {
					db.update("UPDATE study SET study_lock = " + Study.LOCK_NONE +
						", lock_count = lock_count + 1 WHERE study_key = " + theItem.key);
					theItem.studyLock = Study.LOCK_NONE;
					theItem.lockCount++;
					final StudyListItem listItem = theItem;
					SwingUtilities.invokeLater(new Runnable() {
						public void run() {
							studyListModel.itemWasChanged(listItem);
						}
					});
				} catch (SQLException se) {
					db.reportError(se);
				}
			}

			DbCore.releaseDb(db);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Clear a study lock that didn't get cleared properly due to an app crash (or a command-Q on MacOS, grrr...).

	private void doUnlockStudy() {

		String title = "Unlock Study";
		errorReporter.setTitle(title);

		if (studyListTable.getSelectedRowCount() != 1) {
			return;
		}

		int rowIndex = studyListTable.convertRowIndexToModel(studyListTable.getSelectedRow());
		StudyListItem theItem = checkStudy(rowIndex, errorReporter);
		if (null == theItem) {
			return;
		}

		StudyLockHolder lockHolder = lockHolders.get(theItem.key);
		if (null != lockHolder) {
			if (lockHolder.isVisible()) {
				AppController.beep();
				lockHolder.toFront();
				return;
			}
			lockHolders.remove(theItem.key);
		}

		AppController.beep();
		if (JOptionPane.YES_OPTION != JOptionPane.showConfirmDialog(this,
				"This will clear the lock on the study.  Do this only if the study was\n" +
				"not closed properly due to an application crash or network failure;\n" +
				"if this is done when another application is still using the study,\n" +
				"that application will fail and the data could become corrupted.\n\n" +
				"Do you want to continue?", title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)) {
			return;
		}

		Study.unlockStudy(dbID, theItem.key.intValue(), 0, errorReporter);

		checkStudy(rowIndex, null);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Show template manager window.  The window is completely standalone, it does not report any edits or closings.
	// The window object is re-used, it will update it's state automatically when re-shown.

	private void doShowTemplateManager() {

		if ((null != templateManager) && templateManager.isVisible()) {
			templateManager.toFront();
			return;
		}

		if (null == templateManager) {
			templateManager = new TemplateManager(this);
		}

		AppController.showWindow(templateManager);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Show station data manager window, as above.

	private void doShowExtDbManager() {

		if ((null != extDbManager) && extDbManager.isVisible()) {
			extDbManager.toFront();
			return;
		}

		if (null == extDbManager) {
			extDbManager = new ExtDbManager(this);
		}

		AppController.showWindow(extDbManager);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Show a dialog for general browsing of station data.

	private void doShowStationDataViewer() {

		if ((null != stationDataViewer) && stationDataViewer.isVisible()) {
			stationDataViewer.toFront();
			return;
		}

		stationDataViewer = new RecordFind(this, "View Station Data", 0, 0);
		AppController.showWindow(stationDataViewer);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by study editors, run windows, etc., after a save or other operation that might have changed the study
	// information being displayed or needs to trigger some subsequent action.  First for a RunStart dialog, the user
	// has made run settings and the dialog has created a RunPanel object that now takes over the study lock.  That
	// replaces the start dialog in the open study tracking then the panel is handed off to the study run manager.
	// The start dialog will hide without calling editorClosing when this returns success.

	public boolean applyEditsFrom(AppEditor theEditor) {

		if (theEditor instanceof RunStart) {

			RunStart theDialog = (RunStart)theEditor;
			Integer theKey = Integer.valueOf(theDialog.getStudyKey());
			if (theDialog != lockHolders.get(theKey)) {
				return false;
			}

			RunPanel theRun = theDialog.getRunPanel();
			if (null == theRun) {
				return false;
			}

			doStartStudyRun(theRun);

			lockHolders.put(theKey, theRun);

			return true;
		}

		// For any other StudyLockHolder this could mean the study information e.g. lock state may have changed, also
		// this could be from a run panel that just finished creating a new study and needs to be added to the lock
		// holder state.  Do that if needed, then in any case update table display and trigger a cache usage update.

		if (theEditor instanceof StudyLockHolder) {

			StudyLockHolder theHolder = (StudyLockHolder)theEditor;
			Integer theKey = Integer.valueOf(theHolder.getStudyKey());
			if (!lockHolders.containsKey(theKey)) {
				lockHolders.put(theKey, theHolder);
				updateStudyList(false, true);
			}

			int rowIndex = studyListModel.indexOfStudy(theKey);
			if (rowIndex >= 0) {
				studyListModel.get(rowIndex).clearCacheSize();
				checkStudy(rowIndex, null);
			}

			return true;
		}

		// If the study start dialog sent this, retrieve the selected record from the dialog, get options from the
		// accessory panel, retrieve the run panel, and give it to the run manager.  The data viewer RecordFind dialog
		// should never call this.  Note in this case the run panel is not yet a lock holder because the study does
		// not exist.  The run panel will use the StudyBuild object to create the study, then once it exists and is
		// locked the panel calls back here so the previous generic StudyLockHolder case will add it as a lock holder.

		if (theEditor instanceof RecordFind) {

			RecordFind theFinder = (RecordFind)theEditor;

			if (theFinder != studyBuildWizard) {
				return false;
			}

			ErrorReporter theReporter = theFinder.getErrorReporter();

			// Confirm the selected record is TV, for all auto-run study types the target must be a TV.

			int recordType = 0;
			if (null != theFinder.source) {
				recordType = theFinder.source.recordType;
			} else {
				if (null != theFinder.record) {
					recordType = theFinder.record.recordType;
				}
			}
			if (Source.RECORD_TYPE_TV != recordType) {
				theReporter.reportError("That record type is not allowed here.");
				return false;
			}

			// The OptionsPanel subclass specific to the study type provides a StudyBuild subclass object, or objects,
			// used to build the study, or studies.  There may be multiple objects in some cases due to replicating the
			// study record to multiple channels, each replication channel will create and run a separate study.  See
			// OptionsPanel, also RunPanelStudy.  A special case for an IX check study with the buildFullStudy option
			// in the original build object set false, in that case this came from doCreateNewStudy() rather than
			// doRunNewStudy(), the new study is created here and immediately opened in an editor.

			OptionsPanel theOptions = (OptionsPanel)studyBuildWizard.getAccessoryPanel();

			if (theOptions instanceof OptionsPanel.IxCheckStudy) {

				final StudyBuildIxCheck theBuild = ((OptionsPanel.IxCheckStudy)theOptions).getStudyBuild();
				if (!theBuild.buildFullStudy) {

					theBuild.source = studyBuildWizard.source;
					theBuild.record = studyBuildWizard.record;

					BackgroundWorker<Study> theWorker = new BackgroundWorker<Study>(theFinder, theFinder.getTitle()) {
						protected Study doBackgroundWork(ErrorLogger errors) {
							if (!theBuild.initialize(errors)) {
								return null;
							}
							return theBuild.buildStudy(errors);
						}
					};

					final Study theStudy = theWorker.runWork("Creating study, please wait...", theReporter);
					if (null == theStudy) {
						return false;
					}

					SwingUtilities.invokeLater(new Runnable() {
						public void run() {
							doOpenStudy(theStudy);
						}
					});

					return true;
				}
			}

			ArrayList<StudyBuild> studyBuilds = theOptions.getStudyBuilds();
			if (null == studyBuilds) {
				theReporter.reportError("Invalid study configuration.");
				return false;
			}

			ArrayList<RunPanelStudy> theRuns = new ArrayList<RunPanelStudy>();

			for (StudyBuild studyBuild : studyBuilds) {

				studyBuild.source = studyBuildWizard.source;
				studyBuild.record = studyBuildWizard.record;

				RunPanelStudy theRun = new RunPanelStudy(this);
				theRun.studyBuild = studyBuild;

				// The dialog always has a "Build" button, if a run is possible a second "Build & Run" button will also
				// be shown, if that was clicked set the runAfterBuild flag.  The dialog should not show "Build & Run"
				// if a run is not possible, but check directly again here to be safe.  See AppCore.initialize().

				if (AppCore.maxEngineProcessCount > 0) {
					theRun.runAfterBuild = (2 == studyBuildWizard.applyButtonID);
				} else {
					theRun.runAfterBuild = false;
				}

				theRun.fileOutputConfig = theOptions.fileOutputConfig;
				theRun.mapOutputConfig = theOptions.mapOutputConfig;
				theRun.memoryFraction = theOptions.memoryFraction;
				theRun.runComment = theOptions.comment;

				if (!theRun.initialize(theReporter)) {
					return false;
				}

				theRuns.add(theRun);
			}

			for (RunPanelStudy theRun : theRuns) {
				doStartStudyRun(theRun);
			}

			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Show the study run manager and add a new run panel.

	private void doStartStudyRun(RunPanel theRun) {

		if (null == studyRunManager) {
			studyRunManager = new StudyRunManager(this);
		}
		if (!studyRunManager.isVisible()) {
			AppController.showWindow(studyRunManager);
		} else {
			studyRunManager.toFront();
		}

		studyRunManager.addRunPanel(theRun);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Sent by editors and run panels when they are done with a study; clear the study lock if needed (some holders
	// clear the lock themselves), then update the list item.  If the holdStudyLocks flag is true the lock is never
	// cleared; see doDeleteStudy().  Also sent by other dependent modeless dialogs when closing.

	public void editorClosing(AppEditor theEditor) {

		if (theEditor instanceof StudyLockHolder) {

			StudyLockHolder lockHolder = (StudyLockHolder)theEditor;
			int theStudyKey = lockHolder.getStudyKey();
			Integer theKey = Integer.valueOf(theStudyKey);

			if (lockHolders.remove(theKey, lockHolder)) {

				int theStudyLock = lockHolder.getStudyLock();
				int theLockCount = lockHolder.getLockCount();

				boolean didUnlock = false;
				if ((Study.LOCK_NONE != theStudyLock) && !holdStudyLocks) {
					didUnlock = Study.unlockStudy(dbID, theStudyKey, theLockCount);
				}

				int rowIndex = studyListModel.indexOfStudy(theKey);
				if (rowIndex >= 0) {
					StudyListItem theItem = studyListModel.get(rowIndex);
					if (didUnlock) {
						theItem.studyLock = Study.LOCK_NONE;
						theItem.lockCount = theLockCount + 1;
					} else {
						theItem.studyLock = theStudyLock;
						theItem.lockCount = theLockCount;
					}
					studyListModel.itemWasChanged(rowIndex);
					updateControls();
				}
			}

			return;
		}

		// RecordFind may be the station data viewer or study start, either case just clear state.

		if (theEditor instanceof RecordFind) {

			RecordFind theFinder = (RecordFind)theEditor;

			if (theFinder == stationDataViewer) {
				stationDataViewer = null;
				return;
			}

			if (theFinder == studyBuildWizard) {
				studyBuildWizard = null;
				return;
			}
		}

		// Geography editor and receive antenna editor share a root data lock, as long as one or the other is visible
		// the lock is held, when both are gone it is released.  See acquireEditLock().

		if (theEditor == geographyEditor) {
			if ((null == receiveAntennaEditor) || !receiveAntennaEditor.isVisible()) {
				clearEditLock();
			}
			return;
		}

		if (theEditor == receiveAntennaEditor) {
			if ((null == geographyEditor) || !geographyEditor.isVisible()) {
				clearEditLock();
			}
			return;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
  	// Once the window is open, load the initial list and start the update timer.  Also show a message if run UI will
	// be disabled because of a problem with the study engine, see AppCore.initialize().

	public void windowWillOpen() {

		DbController.restoreColumnWidths(dbID, getKeyTitle(), studyListTable);

		blockActionsClear();

		SwingUtilities.invokeLater(new Runnable() {
			public void run() {

				errorReporter.setTitle("Load Study List");
				studyListModel.setItems(getItems(errorReporter));
				lastListUpdate = System.currentTimeMillis();

				updateTimer.start();

				if (AppCore.maxEngineProcessCount < 1) {
					errorReporter.setTitle("Study Runs Disabled");
					if (AppCore.maxEngineProcessCount < 0) {
						errorReporter.reportWarning(
							"The study engine executable file is not properly installed.\n" +
							"Editing may occur however no study runs can be performed.");
					} else {
						errorReporter.reportWarning(
							"There is not enough memory for the study engine to run.\n" +
							"Editing may occur however no study runs can be performed.");
					}
				}
			}
		});
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Can only close if there are no other windows open.  Some other windows and dialogs will be told to close if
	// possible but those calls can fail if there are other dependent windows open or unsaved state.  Ultimately this
	// will not allow a close until there are no open windows at all belonging to this manager's database.

	public boolean windowShouldClose() {

		if (!lockHolders.isEmpty()) {
			AppController.beep();
			lockHolders.values().iterator().next().toFront();
			return false;
		}

		if (null != templateManager) {
			if (templateManager.isVisible() && !templateManager.closeIfPossible()) {
				return false;
			}
			templateManager.dispose();
			templateManager = null;
		}

		if (null != extDbManager) {
			if (extDbManager.isVisible() && !extDbManager.closeIfPossible()) {
				return false;
			}
			extDbManager.dispose();
			extDbManager = null;
		}

		if (null != stationDataViewer) {
			if (stationDataViewer.isVisible() && !stationDataViewer.closeIfPossible()) {
				AppController.beep();
				stationDataViewer.toFront();
				return false;
			}
			stationDataViewer = null;
		}

		if (null != geographyEditor) {
			if (geographyEditor.isVisible() && !geographyEditor.closeIfPossible()) {
				return false;
			}
			geographyEditor.dispose();
			geographyEditor = null;
		}

		if (null != receiveAntennaEditor) {
			if (receiveAntennaEditor.isVisible() && !receiveAntennaEditor.closeIfPossible()) {
				return false;
			}
			receiveAntennaEditor.dispose();
			receiveAntennaEditor = null;
		}

		if (null != studyBuildWizard) {
			if (studyBuildWizard.isVisible() && !studyBuildWizard.closeIfPossible()) {
				AppController.beep();
				studyBuildWizard.toFront();
				return false;
			}
			studyBuildWizard = null;
		}

		if (null != studyRunManager) {
			if (studyRunManager.isVisible() && !studyRunManager.closeIfPossible()) {
				AppController.beep();
				studyRunManager.toFront();
				return false;
			}
			studyRunManager.dispose();
			studyRunManager = null;
		}

		DbController.saveColumnWidths(dbID, getKeyTitle(), studyListTable);

		blockActionsSet();

		// Stop the timer, clear the queues and wait for the background thread to exit.

		updateTimer.stop();

		clearUpdateItems();

		if (null != updateThread) {
			while (updateThread.isAlive()) {
				try {
					updateThread.join();
				} catch (InterruptedException ie) {
				}
			}
			updateThread = null;
		}

		clearUpdatedItems();

		// The final step below is closing the database, which will make it difficult for AppController.hideWindow()
		// to save this window's size and location, so bend encapsulation a bit and do that here if needed.  Then
		// clear the flag so hideWindow() doesn't try to do it again, that attempt currently would do nothing, but
		// that might change to some less pleasant side-effect in the future so avoid it to be safe.

		if (getLocationSaved()) {
			DbController.saveWindowLocation(this, dbID, getKeyTitle());
			setLocationSaved(false);
		}

		// Remove this manager from the map and close the underlying database connection.

		studyManagers.remove(dbID);
		DbCore.closeDb(dbID);

		return true;
	}
}
