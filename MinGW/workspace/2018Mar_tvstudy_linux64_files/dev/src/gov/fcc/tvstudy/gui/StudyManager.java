//
//  StudyManager.java
//  TVStudy
//
//  Copyright (c) 2012-2018 Hammett & Edison, Inc.  All rights reserved.

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

	// Database ID and host name (for document title).

	private String dbID;
	private String dbName;

	// Model of all studies and folders in the database, see getItems().

	private HashMap<Integer, StudyListItem> studyItemMap;
	private HashMap<Integer, StudyListItem> folderItemMap;
	private Integer currentFolderKey;
	private boolean showFolders;

	// Model and table displaying list of studies.

	private StudyListTableModel studyListModel;
	private JTable studyListTable;

	// Map of other objects that manage locked studies, typically study editors and run windows.

	private HashMap<Integer, StudyLockHolder> lockHolders;

	// Label displaying free disk space.

	private JLabel freeSpaceLabel;

	// New study pop-up menu.

	private KeyedRecordMenu newStudyMenu;

	// Move to folder menu.

	private JMenu moveToFolderMenu;

	// Menu items.

	private JMenuItem hideShowFoldersMenuItem;
	private JMenuItem newFolderMenuItem;
	private JMenuItem renameFolderMenuItem;
	private JMenuItem duplicateStudyMenuItem;
	private JMenuItem openStudyMenuItem;
	private JMenuItem deleteStudyMenuItem;
	private JMenuItem runStudyMenuItem;
	private JMenuItem pairStudyMenuItem;
	private JMenuItem clearCacheMenuItem;
	private JMenuItem unlockStudyMenuItem;
	private JMenuItem clearEditLockMenuItem;

	// Contextual pop-up menu for study table, and menu items including move to folder menu.

	private JPopupMenu studyListTablePopupMenu;

	private JMenu cmMoveToFolderMenu;

	private JMenuItem cmDuplicateStudyMenuItem;
	private JMenuItem cmOpenStudyMenuItem;
	private JMenuItem cmDeleteStudyMenuItem;
	private JMenuItem cmRunStudyMenuItem;

	// Buttons.

	private JButton openStudyButton;
	private JButton runStudyButton;

	// Dependent windows and modeless dialogs.

	private TemplateManager templateManager;
	private ExtDbManager extDbManager;
	private RecordFind stationDataViewer;
	private RecordFind studyBuildWizard;

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

	// See doDeleteItem() and editorClosing().

	private boolean holdStudyLocks;

	// Disambiguation.

	private StudyManager outerThis = this;


	//-----------------------------------------------------------------------------------------------------------------
	// Each registered database is associated with one, and only one, instance of StudyManager.  This will show the
	// manager for a given ID, bringing it to front if it already exists, or creating it if needed.  The database
	// must already be registered in DbCore before a new manager can be created.  The constructor here is private, as
	// is the map of existing managers, so this method is the only way to create/show a manager.

	private static HashMap<String, StudyManager> studyManagers = new HashMap<String, StudyManager>();

	public static boolean showManager(String theDbID) {

		StudyManager theManager = studyManagers.get(theDbID);
		if (null != theManager) {
			theManager.toFront();
			return true;
		}

		if (DbCore.isDbRegistered(theDbID)) {
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

		studyItemMap = new HashMap<Integer, StudyListItem>();
		folderItemMap = new HashMap<Integer, StudyListItem>();
		currentFolderKey = Integer.valueOf(0);
		showFolders = true;

		studyListModel = new StudyListTableModel();
		studyListTable = studyListModel.createTable(editMenu);

		studyListTable.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent e) {
				if (2 == e.getClickCount()) {
					doOpenItem(null);
				}
			}
			public void mousePressed(MouseEvent e) {
				if (e.isPopupTrigger()) {
					studyListTablePopupMenu.show(e.getComponent(), e.getX(), e.getY());
				}
			}
			public void mouseReleased(MouseEvent e) {
				if (e.isPopupTrigger()) {
					studyListTablePopupMenu.show(e.getComponent(), e.getX(), e.getY());
				}
			}
		});

		studyListTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
			public void valueChanged(ListSelectionEvent theEvent) {
				updateControls();
			}
		});

		// The name and type columns in the table can have position reversed by a preference.

		String str = AppCore.getPreference(AppCore.PREF_STUDY_MANAGER_NAME_COLUMN_FIRST);
		if ((null == str) || !Boolean.valueOf(str).booleanValue()) {
			studyListTable.moveColumn(0, 1);
		}

		JPanel listPanel = new JPanel(new BorderLayout());
		listPanel.setBorder(BorderFactory.createTitledBorder("Studies"));
		listPanel.add(AppController.createScrollPane(studyListTable), BorderLayout.CENTER);
		listPanel.add(studyListModel.filterPanel, BorderLayout.SOUTH);

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
				doOpenItem(null);
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
				updateStudyList(false, true, false);
			}
		});
		fileMenu.add(miRefresh);

		// __________________________________

		fileMenu.addSeparator();

		// Hide/Show Folders

		hideShowFoldersMenuItem = new JMenuItem("Hide Folders");
		hideShowFoldersMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (showFolders) {
					hideShowFoldersMenuItem.setText("Show Folders");
					showFolders = false;
				} else {
					hideShowFoldersMenuItem.setText("Hide Folders");
					showFolders = true;
				}
				updateStudyList(false, false, true);
			}
		});
		fileMenu.add(hideShowFoldersMenuItem);

		// New Folder...

		newFolderMenuItem = new JMenuItem("New Folder...");
		newFolderMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, AppController.MENU_SHORTCUT_KEY_MASK));
		newFolderMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doCreateFolder();
			}
		});
		fileMenu.add(newFolderMenuItem);

		// Rename Folder...

		renameFolderMenuItem = new JMenuItem("Rename Folder...");
		renameFolderMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doRenameFolder();
			}
		});
		fileMenu.add(renameFolderMenuItem);

		// Move To Folder -> (this will be populated in getItems())

		moveToFolderMenu = new JMenu("Move To Folder");
		fileMenu.add(moveToFolderMenu);

		// __________________________________

		fileMenu.addSeparator();

		// New Study ->

		JMenu meNew = new JMenu("New Study");

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
				doOpenItem(null);
			}
		});
		fileMenu.add(openStudyMenuItem);

		// Delete

		deleteStudyMenuItem = new JMenuItem("Delete");
		deleteStudyMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doDeleteItem();
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

		// Build the study table contextual popup menu, subset of actions from the file menu.

		studyListTablePopupMenu = new JPopupMenu();

		cmMoveToFolderMenu = new JMenu("Move To Folder");
		studyListTablePopupMenu.add(cmMoveToFolderMenu);

		cmOpenStudyMenuItem = new JMenuItem("Open");
		cmOpenStudyMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doOpenItem(null);
			}
		});
		studyListTablePopupMenu.add(cmOpenStudyMenuItem);

		cmRunStudyMenuItem = new JMenuItem("Run...");
		cmRunStudyMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doRunStudy(false);
			}
		});
		studyListTablePopupMenu.add(cmRunStudyMenuItem);

		cmDuplicateStudyMenuItem = new JMenuItem("Duplicate...");
		cmDuplicateStudyMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doDuplicateStudy();
			}
		});
		studyListTablePopupMenu.add(cmDuplicateStudyMenuItem);

		cmDeleteStudyMenuItem = new JMenuItem("Delete");
		cmDeleteStudyMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doDeleteItem();
			}
		});
		studyListTablePopupMenu.add(cmDeleteStudyMenuItem);

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
		boolean eMove = false, eRename = false, eOpen = false, eDuplicate = false, eDelete = false, eRun = false,
			eRunPair = false, eClear = false, eUnlock = false;

		if (1 == rowCount) {

			StudyListItem theItem =
				studyListModel.get(studyListTable.convertRowIndexToModel(studyListTable.getSelectedRow()));
			StudyLockHolder lockHolder = null;
			if (!theItem.isFolder) {
				lockHolder = lockHolders.get(theItem.key);
			}

			if (null != lockHolder) {

				eMove = true;
				eOpen = true;
				if (Study.LOCK_EDIT == lockHolder.getStudyLock()) {
					eDelete = true;
					eClear = true;
				}

			} else {

				if (theItem.isFolder) {

					if (theItem.isLink) {
						eOpen = true;
					} else {
						eMove = true;
						eRename = true;
						eOpen = true;
						eDelete = (0 == theItem.items.size());
					}

				} else {

					eMove = true;
					eOpen = true;
					eDuplicate = true;
					eDelete = true;
					eRun = (AppCore.maxEngineProcessCount > 0);
					eRunPair = (eRun && (Study.STUDY_TYPE_TV == theItem.studyType));
					eClear = true;
					eUnlock = true;
				}
			}

		} else {

			if (rowCount > 1) {

				eMove = true;
				eDelete = true;
				eClear = true;
			}
		}

		moveToFolderMenu.setEnabled(eMove && showFolders);
		cmMoveToFolderMenu.setEnabled(eMove && showFolders);
		newFolderMenuItem.setEnabled(showFolders);
		renameFolderMenuItem.setEnabled(eRename);

		openStudyMenuItem.setEnabled(eOpen);
		cmOpenStudyMenuItem.setEnabled(eOpen);
		openStudyButton.setEnabled(eOpen);

		duplicateStudyMenuItem.setEnabled(eDuplicate);
		cmDuplicateStudyMenuItem.setEnabled(eDuplicate);

		deleteStudyMenuItem.setEnabled(eDelete);
		cmDeleteStudyMenuItem.setEnabled(eDelete);

		runStudyMenuItem.setEnabled(eRun);
		cmRunStudyMenuItem.setEnabled(eRun);
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

		dbName = DbCore.getHostDbName(dbID);

		StudyListItem theFolder = folderItemMap.get(currentFolderKey);
		if ((null != theFolder) && theFolder.folderKey.intValue() != 0) {
			if (theFolder.parentFolderKey.intValue() != 0) {
				setDocumentName(dbName + "/.../" + theFolder.name);
			} else {
				setDocumentName(dbName + "/" + theFolder.name);
			}
		} else {
			setDocumentName(dbName);
		}

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
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This is a little bit of a hack.  As set by updateDocumentName() above, the full document name given to the
	// superclass may include a folder name.  However the folder name should not appear as a component in the document
	// names of dependent windows.  So this is overridden to return just the base name without any folder part.

	public String getDocumentName() {

		return dbName;
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

	public static boolean closeGeographyScopeIfPossible(String theDbID, int theStudyKey, int theSourceKey) {

		StudyManager theManager = studyManagers.get(theDbID);
		if (null == theManager) {
			return true;
		}

		if ((null != theManager.geographyEditor) && theManager.geographyEditor.isVisible()) {
			return theManager.geographyEditor.closeScopeIfPossible(theStudyKey, theSourceKey);
		}
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// As above, but may discard pending edits.

	public static boolean closeGeographyScopeWithoutSave(String theDbID, int theStudyKey, int theSourceKey) {

		StudyManager theManager = studyManagers.get(theDbID);
		if (null == theManager) {
			return true;
		}

		if ((null != theManager.geographyEditor) && theManager.geographyEditor.isVisible()) {
			return theManager.geographyEditor.closeScopeWithoutSave(theStudyKey, theSourceKey);
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

		private boolean isFolder;
		private boolean isLink;
		private Integer folderKey;
		private Integer parentFolderKey;
		private ArrayList<StudyListItem> items;

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

			if (isFolder) {
				return false;
			}
			return ((System.currentTimeMillis() - lastCacheCheck) > CACHE_CHECK_INTERVAL);
		}


		//-------------------------------------------------------------------------------------------------------------
		// Update the cache size.  This is a non-synchronized wrapper because getStudyCacheSize() can take a long time.

		private void updateCacheSize() {

			if (isFolder) {
				return;
			}
			setCacheSize(AppCore.getStudyCacheSize(dbID, key.intValue()));
		}


		//-------------------------------------------------------------------------------------------------------------

		private synchronized void setCacheSize(long newSize) {

			if (isFolder) {
				return;
			}
			cacheSize = newSize;
			lastCacheCheck = System.currentTimeMillis();
		}


		//-------------------------------------------------------------------------------------------------------------

		private synchronized void clearCacheSize() {

			if (isFolder) {
				return;
			}
			cacheSize = -1;
			lastCacheCheck = 0;
		}
	}


	//=================================================================================================================
	// Table model for the study list.

	private class StudyListTableModel extends AbstractTableModel implements TableFilterModel {

		private static final String STUDY_NAME_COLUMN = "Name";
		private static final String STUDY_TYPE_COLUMN = "Type";
		private static final String STUDY_TEMPLATE_COLUMN = "Template";
		private static final String STUDY_EXT_DB_NAME_COLUMN = "Default Station Data";
		private static final String STUDY_CACHE_SIZE_COLUMN = "Cache Size";

		private String[] columnNames = {
			STUDY_NAME_COLUMN,
			STUDY_TYPE_COLUMN,
			STUDY_TEMPLATE_COLUMN,
			STUDY_EXT_DB_NAME_COLUMN,
			STUDY_CACHE_SIZE_COLUMN
		};

		private static final int STUDY_NAME_INDEX = 0;
		private static final int STUDY_TYPE_INDEX = 1;
		private static final int STUDY_TEMPLATE_INDEX = 2;
		private static final int STUDY_EXT_DB_NAME_INDEX = 3;
		private static final int STUDY_CACHE_SIZE_INDEX = 4;

		private ArrayList<StudyListItem> modelRows;

		private TableFilterPanel filterPanel;


		//-------------------------------------------------------------------------------------------------------------

		private StudyListTableModel() {

			super();

			modelRows = new ArrayList<StudyListItem>();

			filterPanel = new TableFilterPanel(outerThis, this);
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

					StudyListItem theItem = modelRows.get(filterPanel.forwardIndex[t.convertRowIndexToModel(r)]);

					if (theItem.isFolder) {
						if (!s) {
							comp.setForeground(Color.MAGENTA);
						}
						comp.setToolTipText(null);
						return comp;
					}

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

			TableColumn theColumn = theTable.getColumn(STUDY_NAME_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[16]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[25]);

			theColumn = theTable.getColumn(STUDY_TYPE_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[12]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[16]);

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

			// Use a customized row sorter so the type and cache size columns sort numerically.

			TableStringConverter theConverter = new TableStringConverter() {
				public String toString(TableModel theModel, int rowIndex, int columnIndex) {

					StudyListItem theItem = modelRows.get(rowIndex);

					if (theItem.isFolder) {

						switch (columnIndex) {

							case STUDY_NAME_INDEX:
								return theItem.name;

							case STUDY_TYPE_INDEX:
								if (theItem.isLink) {
									return "";
								} else {
									return String.format("0%06d", theItem.items.size());
								}
						}

					} else {

						switch (columnIndex) {

							case STUDY_NAME_INDEX:
								return theItem.name;

							case STUDY_TYPE_INDEX:
								return String.format("1%06d", theItem.studyType);

							case STUDY_TEMPLATE_INDEX:
								return theItem.templateName;

							case STUDY_EXT_DB_NAME_INDEX:
								return theItem.extDbName;

							case STUDY_CACHE_SIZE_INDEX:
								return String.format(Locale.US, "%018d", theItem.getCacheSize());
						}
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
		// Set a new list of items in the model, queue study items needing cache update.  Optionally the table filter
		// may be cleared, if a new item was just created the filter is cleared to be sure the item will be visible.
		// Except when viewing the top folder, every view starts with a link item to the current folder's parent.

		private void setItems(ArrayList<StudyListItem> newItems, boolean clearFilter) {

			modelRows.clear();

			if (null == newItems) {
				filterPanel.clearFilter();
				fireTableDataChanged();
				return;
			}

			if (currentFolderKey.intValue() > 0) {
				StudyListItem backItem = new StudyListItem();
				backItem.isFolder = true;
				backItem.isLink = true;
				backItem.folderKey = Integer.valueOf(0);
				backItem.name = "(back)";
				StudyListItem theItem = folderItemMap.get(currentFolderKey);
				if (null != theItem) {
					theItem = folderItemMap.get(theItem.parentFolderKey);
					if (null != theItem) {
						backItem.folderKey = theItem.folderKey;
						backItem.name = "(back to " + theItem.name + ")";
					}
				}
				modelRows.add(backItem);
			}

			for (StudyListItem newItem : newItems) {
				modelRows.add(newItem);
				if (!newItem.isFolder && newItem.isCacheSizeExpired()) {
					addUpdateItem(newItem);
				}
			}

			if (clearFilter) {
				filterPanel.clearFilter();
			} else {
				filterPanel.updateFilter();
			}
			fireTableDataChanged();
		}


		//-------------------------------------------------------------------------------------------------------------

		public void filterDidChange() {

			fireTableDataChanged();
		}


		//-------------------------------------------------------------------------------------------------------------

		private void itemWasChanged(int rowIndex) {

			fireTableRowsUpdated(rowIndex, rowIndex);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void itemWasChanged(StudyListItem theItem) {

			int rowIndex = indexOf(theItem);
			if (rowIndex >= 0) {
				fireTableRowsUpdated(rowIndex, rowIndex);
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		private int indexOf(StudyListItem findItem) {

			int rowIndex = 0;
			for (StudyListItem theItem : modelRows) {
				if (theItem.isFolder) {
					if (!theItem.isLink && findItem.isFolder && theItem.folderKey.equals(findItem.folderKey)) {
						return filterPanel.reverseIndex[rowIndex];
					}
				} else {
					if (!findItem.isFolder && theItem.key.equals(findItem.key)) {
						return filterPanel.reverseIndex[rowIndex];
					}
				}
				rowIndex++;
			}

			return -1;
		}


		//-------------------------------------------------------------------------------------------------------------

		private int indexOfStudy(Integer studyKey) {

			int rowIndex = 0;
			for (StudyListItem theItem : modelRows) {
				if (!theItem.isFolder && theItem.key.equals(studyKey)) {
					return filterPanel.reverseIndex[rowIndex];
				}
				rowIndex++;
			}

			return -1;
		}


		//-------------------------------------------------------------------------------------------------------------

		private StudyListItem get(int rowIndex) {

			return modelRows.get(filterPanel.forwardIndex[rowIndex]);
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

		public boolean filterByColumn(int columnIndex) {

			switch (columnIndex) {
				case STUDY_TYPE_INDEX:
				case STUDY_TEMPLATE_INDEX:
				case STUDY_EXT_DB_NAME_INDEX: {
					return true;
				}
			}

			return false;
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean collapseFilterChoices(int columnIndex) {

			return false;
		}


		//-------------------------------------------------------------------------------------------------------------

		public int getRowCount() {

			return filterPanel.forwardIndex.length;
		}


		//-------------------------------------------------------------------------------------------------------------

		public int getUnfilteredRowCount() {

			return modelRows.size();
		}


		//-------------------------------------------------------------------------------------------------------------

		public Object getValueAt(int rowIndex, int columnIndex) {

			return getCellValue(filterPanel.forwardIndex[rowIndex], columnIndex, false);
		}


		//-------------------------------------------------------------------------------------------------------------

		public String getUnfilteredValueAt(int rowIndex, int columnIndex) {

			return getCellValue(rowIndex, columnIndex, true);
		}
			

		//-------------------------------------------------------------------------------------------------------------

		private String getCellValue(int rowIndex, int columnIndex, boolean forFilter) {

			StudyListItem theItem = modelRows.get(rowIndex);

			if (theItem.isFolder) {

				switch (columnIndex) {

					case STUDY_NAME_INDEX:
						return theItem.name;

					case STUDY_TYPE_INDEX:
						if (theItem.isLink) {
							return "";
						} else {
							if (forFilter) {
								return "Folder";
							} else {
								return "Folder (" + theItem.items.size() + " items)";
							}
						}
				}

			} else {

				switch (columnIndex) {

					case STUDY_NAME_INDEX:
						return theItem.name;

					case STUDY_TYPE_INDEX:
						return Study.getStudyTypeName(theItem.studyType);

					case STUDY_TEMPLATE_INDEX:
						return theItem.templateName;

					case STUDY_EXT_DB_NAME_INDEX:
						return theItem.extDbName;

					case STUDY_CACHE_SIZE_INDEX:
						return AppCore.formatBytes(theItem.getCacheSize());
				}
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

			updateStudyList(true, true, false);

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

	private void updateStudyList(boolean fromTimer, boolean preserveSelection, boolean clearFilter) {

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

		studyListModel.setItems(list, clearFilter);

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
	// Retrieve all studies in the database, build the study folder items, and return the item list for the currently-
	// viewed folder.  If anything goes wrong the existing model is not changed and null is returned.

	private ArrayList<StudyListItem> getItems(ErrorReporter errors) {

		HashMap<Integer, StudyListItem> newStudyMap = new HashMap<Integer, StudyListItem>();
		HashMap<Integer, StudyListItem> newFolderMap = new HashMap<Integer, StudyListItem>();

		// Always create a top-level folder with key 0, it does not appear explicitly in the folder table (or if it
		// does, that entry will be ignored).  Any items with unknown folder keys will be placed in this folder.

		StudyListItem topFolder = new StudyListItem();
		topFolder.isFolder = true;
		topFolder.folderKey = Integer.valueOf(0);
		topFolder.name = dbName;
		topFolder.parentFolderKey = topFolder.folderKey;
		topFolder.items = new ArrayList<StudyListItem>();

		StudyListItem theFolder, theStudy;
		Integer theKey;
		ExtDb theDb;

		DbConnection db = DbCore.connectDb(dbID, errors);
		if (null != db) {
			try {

				// Load the folder list.  Ignore invalid folder keys.  If in flat view skip this, only the top folder
				// exists in that case and all studies go in that folder.

				if (showFolders) {

					db.query(
					"SELECT " +
						"folder_key, " +
						"name, " +
						"parent_folder_key " +
					"FROM " +
						"folder " +
					"ORDER BY 1");

					while (db.next()) {

						theKey = Integer.valueOf(db.getInt(1));
						if (theKey.intValue() > 0) {
							theFolder = new StudyListItem();
							theFolder.isFolder = true;
							theFolder.folderKey = theKey;
							theFolder.name = db.getString(2);
							theFolder.parentFolderKey = Integer.valueOf(db.getInt(3));
							theFolder.items = new ArrayList<StudyListItem>();
							newFolderMap.put(theKey, theFolder);
						}
					}

					// Verify parent folder keys and place folders into parent item list.

					for (StudyListItem theItem : newFolderMap.values()) {
						theFolder = newFolderMap.get(theItem.parentFolderKey);
						if (null == theFolder) {
							theFolder = topFolder;
							theItem.parentFolderKey = theFolder.folderKey;
						}
						theFolder.items.add(theItem);
					}
				}

				// Now put the top folder in the map for easier lookup later.

				newFolderMap.put(topFolder.folderKey, topFolder);

				// Load the studies.  Re-use existing item objects when possible to preserve cache size information.

				db.query(
				"SELECT " +
					"study.study_key, " +
					"study.name, " +
					"study.description, " +
					"study.study_lock, " +
					"study.lock_count, " +
					"study.study_type, " +
					"template.name, " +
					"study.ext_db_key, " +
					"study.folder_key " +
				"FROM " +
					"study " +
					"JOIN template USING (template_key) " +
				"ORDER BY 1");

				while (db.next()) {

					theKey = Integer.valueOf(db.getInt(1));

					theStudy = studyItemMap.get(theKey);
					if (null == theStudy) {
						theStudy = new StudyListItem();
						theStudy.key = theKey;
						theStudy.cacheSize = -1;
					}

					theStudy.name = db.getString(2);
					theStudy.description = db.getString(3);
					theStudy.studyLock = db.getInt(4);
					theStudy.lockCount = db.getInt(5);
					theStudy.studyType = db.getInt(6);
					theStudy.templateName = db.getString(7);

					theStudy.extDbName = "";
					theKey = Integer.valueOf(db.getInt(8));
					if (theKey.intValue() > 0) {
						theStudy.extDbName = ExtDb.getExtDbDescription(dbID, theKey);
					}

					newStudyMap.put(theStudy.key, theStudy);

					if (showFolders) {
						theFolder = newFolderMap.get(Integer.valueOf(db.getInt(9)));
						if (null == theFolder) {
							theFolder = topFolder;
						}
					} else {
						theFolder = topFolder;
					}
					theFolder.items.add(theStudy);
				}

				// Success.

				studyItemMap = newStudyMap;
				folderItemMap = newFolderMap;

				DbCore.releaseDb(db);

			} catch (SQLException se) {
				DbCore.releaseDb(db);
				DbConnection.reportError(errors, se);
			}
		}

		// Verify the currently-viewed folder, if it no longer exists show the top.

		if (showFolders) {
			theFolder = folderItemMap.get(currentFolderKey);
		} else {
			theFolder = null;
		}
		if (null == theFolder) {
			currentFolderKey = topFolder.folderKey;
			theFolder = topFolder;
		}

		// Update the move-to-folder menu.

		moveToFolderMenu.removeAll();
		cmMoveToFolderMenu.removeAll();

		if (showFolders) {
			addFolderAndDescend(topFolder, "");
		}

		return theFolder.items;
	}

	private void addFolderAndDescend(StudyListItem theFolder, String prefix) {

		JMenuItem miMove = new JMenuItem(prefix + theFolder.name);
		final int toFolderKey = theFolder.folderKey.intValue();
		miMove.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doMoveToFolder(toFolderKey);
			}
		});
		moveToFolderMenu.add(miMove);

		JMenuItem cmMiMove = new JMenuItem(prefix + theFolder.name);
		cmMiMove.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doMoveToFolder(toFolderKey);
			}
		});
		cmMoveToFolderMenu.add(cmMiMove);

		for (StudyListItem theItem : theFolder.items) {
			if (theItem.isFolder && !theItem.isLink) {
				addFolderAndDescend(theItem, prefix + "  ");
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Re-query the database record for a study or folder, update item properties.  Return the list item as updated,
	// or null if the study/folder record no longer exists (the item is removed from the list) or an error occurs.

	private StudyListItem checkItem(int rowIndex, ErrorReporter errors) {

		StudyListItem theItem = studyListModel.get(rowIndex);
		if (theItem.isLink) {
			return theItem;
		}

		boolean error = false, notfound = false;
		int theKey;
		ExtDb theDb;

		DbConnection db = DbCore.connectDb(dbID, errors);
		if (null != db) {
			try {

				if (theItem.isFolder) {

					db.query("SELECT name FROM folder WHERE folder_key = " + theItem.folderKey);
					if (db.next()) {
						theItem.name = db.getString(1);
					} else {
						notfound = true;
					}

				} else {

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
				if (theItem.isFolder) {
					errors.reportError("The folder does not exist.");
				} else {
					errors.reportError("The study does not exist.");
				}
			}
			updateStudyList(false, false, false);
			return null;
		}

		if (error) {
			return null;
		}

		studyListModel.itemWasChanged(rowIndex);

		// Queue it for cache update if needed.

		if (!theItem.isFolder && theItem.isCacheSizeExpired()) {
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


	//-----------------------------------------------------------------------------------------------------------------
	// Create a new empty folder.

	private void doCreateFolder() {

		String title = "Create Folder";
		errorReporter.setTitle(title);

		DbConnection db;
		String folderName, errmsg;
		boolean exists, error;
		Integer folderKey;

		while (true) {

			folderName = (String)(JOptionPane.showInputDialog(this, "Enter a name for the new folder", title,
				JOptionPane.QUESTION_MESSAGE, null, null, ""));
			if (null == folderName) {
				return;
			}
			folderName = folderName.trim();
			if (0 == folderName.length()) {
				continue;
			}
			if (folderName.length() > DbCore.NAME_MAX_LENGTH) {
				folderName = folderName.substring(0, DbCore.NAME_MAX_LENGTH);
			}

			db = DbCore.connectDb(dbID, errorReporter);
			if (null == db) {
				return;
			}

			exists = false;
			error = false;
			errmsg = "";

			try {

				db.update("LOCK TABLES folder WRITE");

				db.query("SELECT folder_key FROM folder WHERE UPPER(name) = '" + db.clean(folderName.toUpperCase()) +
					"'");
				if (db.next()) {
					exists = true;
				} else {
					if (currentFolderKey.intValue() > 0) {
						db.query("SELECT folder_key FROM folder WHERE folder_key = " + currentFolderKey);
						if (!db.next()) {
							error = true;
							errmsg = "The enclosing folder does not exist.";
						}
					}
					if (!error) {
						db.query("SELECT MAX(folder_key) FROM folder");
						db.next();
						folderKey = Integer.valueOf(db.getInt(1) + 1);
						db.update("INSERT INTO folder VALUES (" + folderKey + ",'" + db.clean(folderName) + "'," +
							currentFolderKey + ")");
					}
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

			DbCore.releaseDb(db);

			if (error) {
				errorReporter.reportError("Cannot create folder:\n" + errmsg);
				return;
			}

			if (exists) {
				errorReporter.reportWarning("That folder name is already in use, please try again.");
				continue;
			}

			break;
		}

		updateStudyList(false, true, true);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Rename a folder.

	private void doRenameFolder() {

		if (studyListTable.getSelectedRowCount() != 1) {
			return;
		}

		String title = "Rename Folder";
		errorReporter.setTitle(title);

		int rowIndex = studyListTable.convertRowIndexToModel(studyListTable.getSelectedRow());
		StudyListItem theItem = checkItem(rowIndex, errorReporter);
		if ((null == theItem) || !theItem.isFolder || theItem.isLink) {
			return;
		}

		DbConnection db;
		String newName, errmsg;
		boolean exists, error;

		while (true) {

			newName = (String)(JOptionPane.showInputDialog(this, "Enter a new name for the folder", title,
				JOptionPane.QUESTION_MESSAGE, null, null, theItem.name));
			if (null == newName) {
				return;
			}
			newName = newName.trim();
			if (0 == newName.length()) {
				continue;
			}
			if (newName.equals(theItem.name)) {
				return;
			}

			db = DbCore.connectDb(dbID, errorReporter);
			if (null == db) {
				return;
			}

			exists = false;
			error = false;
			errmsg = "";

			try {

				db.update("LOCK TABLES folder WRITE");

				db.query("SELECT folder_key FROM folder WHERE UPPER(name) = '" + db.clean(newName.toUpperCase()) +
					"'");
				if (db.next() && (db.getInt(1) != theItem.folderKey.intValue())) {
					exists = true;
				} else {
					db.update("UPDATE folder SET name = '" + db.clean(newName) + "' WHERE folder_key = " +
						theItem.folderKey);
				}

				theItem.name = newName;

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

			DbCore.releaseDb(db);

			if (error) {
				errorReporter.reportError("Cannot rename folder:\n" + errmsg);
				return;
			}

			if (exists) {
				errorReporter.reportWarning("That folder name is already in use, please try again.");
				continue;
			}

			break;
		}

		studyListModel.itemWasChanged(rowIndex);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Move items into a selected folder.  If the destination is the current folder this is a no-op.

	private void doMoveToFolder(int toFolderKey) {

		int rowCount = studyListTable.getSelectedRowCount();
		if (0 == rowCount) {
			return;
		}
		if (currentFolderKey.intValue() == toFolderKey) {
			return;
		}

		String title = "Move To Folder";
		errorReporter.setTitle(title);

		StudyListItem toFolder = folderItemMap.get(Integer.valueOf(toFolderKey));

		// Build a set of folder keys for the path to the destination folder.  If a folder to be moved is in this set
		// it can't be moved since that would create a loop, in that case the folder is skipped, if it was the only
		// item a warning is shown.  If the destination folder or links are selected those are silently ignored.

		HashSet<Integer> folderPathKeys = new HashSet<Integer>();
		StudyListItem parentFolder = folderItemMap.get(toFolder.parentFolderKey);
		while (parentFolder.folderKey.intValue() > 0) {
			folderPathKeys.add(parentFolder.folderKey);
			parentFolder = folderItemMap.get(parentFolder.parentFolderKey);
		}
		boolean showLoopWarning = false;

		HashSet<Integer> studyKeys = new HashSet<Integer>();
		HashSet<Integer> folderKeys = new HashSet<Integer>();

		int[] selRows = studyListTable.getSelectedRows();
		StudyListItem theItem;

		for (int i = 0; i < selRows.length; i++) {
			theItem = studyListModel.get(studyListTable.convertRowIndexToModel(selRows[i]));
			if (theItem.isFolder) {
				if (theItem.isLink || (theItem.folderKey.intValue() == toFolderKey)) {
					continue;
				}
				if (folderPathKeys.contains(theItem.folderKey)) {
					showLoopWarning = true;
					continue;
				}
				folderKeys.add(theItem.folderKey);
			} else {
				studyKeys.add(theItem.key);
			}
		}
		if (folderKeys.isEmpty() && studyKeys.isEmpty()) {
			if (showLoopWarning) {
				errorReporter.reportWarning("A folder can't be moved to it's own sub-folder.");
			}
			return;
		}

		DbConnection db = DbCore.connectDb(dbID, errorReporter);
		if (null == db) {
			return;
		}

		boolean error = false;
		String errmsg = "";

		try {

			db.update("LOCK TABLES study WRITE, folder WRITE");

			if (toFolderKey > 0) {
				db.query("SELECT folder_key FROM folder WHERE folder_key = " + toFolderKey);
				if (!db.next()) {
					error = true;
					errmsg = "The destination folder does not exist.";
				}
			}

			if (!error) {

				if (!studyKeys.isEmpty()) {
					db.update("UPDATE study SET folder_key = " + toFolderKey + " WHERE study_key IN " +
						db.makeKeyList(studyKeys));
				}

				if (!folderKeys.isEmpty()) {
					db.update("UPDATE folder SET parent_folder_key = " + toFolderKey + " WHERE folder_key IN " +
						db.makeKeyList(folderKeys));
				}
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

		DbCore.releaseDb(db);

		updateStudyList(false, false, false);

		if (error) {
			errorReporter.reportError("Cannot move items to folder:\n" + errmsg);
			return;
		}
	}


	//=================================================================================================================
	// Dialog for input to create a new study, see doCreateNewStudy().  Provides UI for entering study name, selecting
	// study template, and selecting an external station data set.  The study name will be checked for uniqueness
	// before this will close.  The station data set choices may be restricted based on record type.

	private class StudyCreateNew extends AppDialog implements ExtDbListener {

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
					cancel();
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
			if (!DbCore.checkStudyName(theName, dbID, true, errorReporter)) {
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
				extDbKey = theDb.key;
			} else {
				extDbKey = null;
			}

			canceled = false;
			AppController.hideWindow(this);
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean cancel() {

			canceled = true;
			AppController.hideWindow(this);
			return true;
		}


		//-------------------------------------------------------------------------------------------------------------

		public void updateExtDbList() {

			SwingUtilities.invokeLater(new Runnable() {
				public void run() {

					ArrayList<KeyedRecord> list = ExtDb.getExtDbList(dbID, recordType);
					if (null == list) {
						return;
					}

					int selectKey = extDbMenu.getSelectedKey();
					extDbMenu.removeAllItems();
					extDbMenu.addItem(new KeyedRecord(0, "(none)"));
					if (!list.isEmpty()) {
						extDbMenu.addAllItems(list);
					}
					if (extDbMenu.containsKey(selectKey)) {
						extDbMenu.setSelectedKey(selectKey);
					}
				}
			});
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

			ExtDb.addListener(this);

			blockActionsClear();

			SwingUtilities.invokeLater(new Runnable() {
				public void run() {

					errorReporter.setTitle("Load Template List");

					ArrayList<KeyedRecord> list = Template.getTemplateInfoList(dbID, errorReporter);
					if (null != list) {
						templateMenu.addAllItems(list);
					}

					errorReporter.setTitle("Load Station Data List");

					extDbMenu.addItem(new KeyedRecord(0, "(none)"));
					list = ExtDb.getExtDbList(dbID, recordType, errorReporter);
					if (null != list) {
						extDbMenu.addAllItems(list);
					}
				}
			});
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean windowShouldClose() {

			canceled = true;
			return true;
		}


		//-------------------------------------------------------------------------------------------------------------

		public void windowWillClose() {

			ExtDb.removeListener(this);

			blockActionsSet();
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
			theBuild.studyFolderKey = currentFolderKey;

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
					theDialog.extDbKey, currentFolderKey, errors);
			}
		};

		errorReporter.clearMessages();

		Integer studyKey = theWorker.runWork("Creating new study, please wait...", errorReporter);
		if (null == studyKey) {
			return;
		}

		errorReporter.showMessages();

		// Update the study list, select the new study, and proceed directly to opening it.

		updateStudyList(false, false, true);

		int rowIndex = studyListModel.indexOfStudy(studyKey);
		if (rowIndex < 0) {
			return;
		}

		rowIndex = studyListTable.convertRowIndexToView(rowIndex);
		studyListTable.setRowSelectionInterval(rowIndex, rowIndex);
		studyListTable.scrollRectToVisible(studyListTable.getCellRect(rowIndex, 0, true));

		doOpenItem(null);
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
				theBuild.studyFolderKey = currentFolderKey;

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
				theBuild.studyFolderKey = currentFolderKey;

				studyBuildWizard = new RecordFind(this, title, 0, Source.RECORD_TYPE_TV);
				studyBuildWizard.setNote("Select protected TV station record for the new study");
				studyBuildWizard.setAccessoryPanel(new OptionsPanel.WirelessStudy(this, theBuild));
				studyBuildWizard.setApply(optButtons, true, true);

				break;
			}

			case Study.STUDY_TYPE_TV6_FM: {

				StudyBuildTV6FM theBuild = new StudyBuildTV6FM(dbID);
				theBuild.studyFolderKey = currentFolderKey;

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
		StudyListItem theItem = checkItem(rowIndex, errorReporter);
		if ((null == theItem) || theItem.isFolder) {
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

		// Study- and source-scope geographies for the original study will also be duplicated, acquire the geography
		// edit lock.  If the lock is already set by this manager it is safe to proceed.  Even if the geography editor
		// is visible here it can't concurrently save changes during the duplicate, and the duplicate is done directly
		// in the database using as-saved state so there is no need to worry about pending state in the editor.

		boolean clearLock = false;
		if (!editLockSet) {
			if (!acquireEditLock()) {
				return;
			}
			clearLock = true;
		}

		String theName = "";
		do {
			theName = (String)(JOptionPane.showInputDialog(this, "Enter a name for the new study", title,
				JOptionPane.QUESTION_MESSAGE, null, null, theItem.name));
			if (null == theName) {
				if (clearLock) {
					clearEditLock();
				}
				return;
			}
			theName = theName.trim();
		} while (!DbCore.checkStudyName(theName, dbID, true, errorReporter));

		final int oldKey = theItem.key.intValue();
		final String newName = theName;

		BackgroundWorker<Integer> theWorker = new BackgroundWorker<Integer>(this, title) {
			protected Integer doBackgroundWork(ErrorLogger errors) {
				return Study.duplicateStudy(dbID, oldKey, newName, errors);
			}
		};

		Integer theKey = theWorker.runWork("Duplicating study, please wait...", errorReporter);
		if (null != theKey) {

			updateStudyList(false, false, false);

			rowIndex = studyListModel.indexOfStudy(theKey);
			if (rowIndex >= 0) {
				rowIndex = studyListTable.convertRowIndexToView(rowIndex);
				studyListTable.setRowSelectionInterval(rowIndex, rowIndex);
				studyListTable.scrollRectToVisible(studyListTable.getCellRect(rowIndex, 0, true));
			}
		}

		if (clearLock) {
			clearEditLock();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Open a study editor for the selected study, or open a folder (display it's items in the table).  For a study,
	// if some lock holder for that study already exists bring it's containing window to the front, regardless of what
	// it is (editor, run manager, etc.).  This may also create an editor for an already-open study passed as argument;
	// that is used from doCreateNewStudy().

	private void doOpenItem(Study theStudy) {

		String title = "Open";
		errorReporter.setTitle(title);

		int rowIndex = -1;
		StudyListItem theItem = null;

		if (null == theStudy) {

			if (studyListTable.getSelectedRowCount() != 1) {
				return;
			}

			rowIndex = studyListTable.convertRowIndexToModel(studyListTable.getSelectedRow());
			theItem = checkItem(rowIndex, errorReporter);
			if (null == theItem) {
				return;
			}

			// When opening a folder, check for link items and load the real item.

			if (theItem.isFolder) {
				if (theItem.isLink) {
					theItem = folderItemMap.get(theItem.folderKey);
					if (null == theItem) {
						theItem = folderItemMap.get(Integer.valueOf(0));
					}
				}
				currentFolderKey = theItem.folderKey;
				studyListModel.setItems(theItem.items, true);
				updateControls();
				updateDocumentName();
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

			updateStudyList(false, false, true);

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
		StudyListItem theItem = checkItem(rowIndex, errorReporter);
		if ((null == theItem) || theItem.isFolder) {
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
	// Delete the selected studies and folders.  For studies, confirm that each one is to be deleted, if there is an
	// existing lock holder try to close it.  Folders are deleted only if empty, with no confirmation prompt, else are
	// ignored silently.  The deletes can take significant time, i.e. due to cache file deletes, so use a background
	// thread worker to do the actual deleting once the list of items to delete has been confirmed.

	private void doDeleteItem() {

		String title = "Delete";
		errorReporter.setTitle(title);

		int rowCount = studyListTable.getSelectedRowCount();
		if (0 == rowCount) {
			return;
		}

		final ArrayList<StudyListItem> toDelete = new ArrayList<StudyListItem>();

		int[] selRows = studyListTable.getSelectedRows();

		StudyListItem theItem;
		StudyLockHolder lockHolder;
		boolean noclose, showConfirm = true, showFolderNotEmpty = false;
		int nmore;
		String msg;
		String[] opts, opts1 = {"Yes", "No", "Cancel"}, optsN = {"Yes", "No", "Cancel", "Yes to all"};

		for (int i = 0; i < selRows.length; i++) {

			theItem = studyListModel.get(studyListTable.convertRowIndexToModel(selRows[i]));

			if (theItem.isFolder) {
				if (theItem.isLink) {
					continue;
				}
				if (0 == theItem.items.size()) {
					toDelete.add(theItem);
				} else {
					showFolderNotEmpty = true;
				}
				continue;
			}

			if (showConfirm) {
				AppController.beep();
				nmore = selRows.length - i - 1;
				if (nmore > 0) {
					msg = "Are you sure you want to delete study '" + theItem.name + "' and " + nmore + " other " +
						((nmore > 1) ? "items?" : "item?");
					opts = optsN;
				} else {
					msg = "Are you sure you want to delete study '" + theItem.name + "'?";
					opts = opts1;
				}
				switch (JOptionPane.showOptionDialog(this, msg, title, JOptionPane.DEFAULT_OPTION,
						JOptionPane.WARNING_MESSAGE, null, opts, opts[0])) {
					case 0:
						break;
					case 1:
					default:
						continue;
					case 2:
						return;
					case 3:
						showConfirm = false;
						break;
				}
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

		if (showFolderNotEmpty) {
			errorReporter.reportWarning("Some folders could not be deleted because they are not empty.");
		}

		if (toDelete.isEmpty()) {
			return;
		}

		BackgroundWorker<Object> theWorker = new BackgroundWorker<Object>(this, title) {
			protected Object doBackgroundWork(ErrorLogger errors) {
				deleteItems(errors, toDelete);
				return null;
			}
		};

		theWorker.runWork("Deleting studies, please wait...", errorReporter);

		updateStudyList(false, false, false);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called on the background thread.

	private void deleteItems(ErrorLogger errors, ArrayList<StudyListItem> toDelete) {

		boolean error = false, notfound = false;
		String errmsg = "";
		int theLock = 0, theLockCount = 0;
		DbConnection db;
		String rootName = DbCore.getDbName(dbID);

		// Delete items one at a time, so each can be checked individually in a concurrent-safe manner.

		for (StudyListItem theItem : toDelete) {

			db = DbCore.connectDb(dbID, errors);
			if (null == db) {
				return;
			}

			error = false;
			errmsg = "";
			notfound = false;

			try {

				if (theItem.isFolder) {

					// Confirm a folder is empty before deleting.

					db.update("LOCK TABLES study WRITE, folder WRITE");

					db.query("SELECT COUNT(*) FROM study WHERE folder_key = " + theItem.folderKey);
					if (db.next() && (db.getInt(1) > 0)) {
						error = true;
						errmsg = "The folder is not empty.";
					} else {
						db.query("SELECT COUNT(*) FROM folder WHERE parent_folder_key = " + theItem.folderKey);
						if (db.next() && (db.getInt(1) > 0)) {
							error = true;
							errmsg = "The folder is not empty.";
						} else {
							db.update("DELETE FROM folder WHERE folder_key = " + theItem.folderKey);
						}
					}

				} else {

					// Check the study lock state, OK if it is unlocked or locked by an open window with an edit lock.
					// If unlocked, lock it.  If locked by another application, fail.  If the study no longer exists,
					// nothing more is done other than removing it from the UI list.

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
						theLockCount = db.getInt(2);

						if (Study.LOCK_NONE == theLock) {

							db.update("UPDATE study SET study_lock = " + Study.LOCK_ADMIN +
								", lock_count = lock_count + 1 WHERE study_key = " + theItem.key);
							theLockCount++;

						} else {

							if (!lockHolders.containsKey(theItem.key) || (Study.LOCK_EDIT != theLock) ||
									(theLock != theItem.studyLock) || (theLockCount != theItem.lockCount)) {
								error = true;
								errmsg = "The study is in use by another application.";
							}
						}

					} else {
						notfound = true;
					}
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

			DbCore.releaseDb(db);

			if (error) {
				errors.reportError("Cannot delete '" + theItem.name + "':\n" + errmsg);
				continue;
			}

			// Delete the study, and any study-related properties.

			if (!theItem.isFolder && !notfound) {
				if (Study.deleteStudy(dbID, theItem.key.intValue(), theLockCount, errors)) {
					DbCore.deleteProperty(dbID, StudyEditor.WINDOW_TITLE + " " + theItem.key + "%");
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Delete study engine cache files for selected study or studies.  No confirmation; an unintended delete is not
	// serious, cached data will simply be re-calculated.  Can be slow, use a background thread.  Just ignore any
	// folders that appear in the selection.

	private void doClearCache() {

		String title = "Clear Cache";
		errorReporter.setTitle(title);

		int rowCount = studyListTable.getSelectedRowCount();
		if (0 == rowCount) {
			return;
		}

		final ArrayList<StudyListItem> toClear = new ArrayList<StudyListItem>();
		int[] selRows = studyListTable.getSelectedRows();
		StudyListItem theItem;
		for (int i = 0; i < selRows.length; i++) {
			theItem = studyListModel.get(studyListTable.convertRowIndexToModel(selRows[i]));
			if (!theItem.isFolder) {
				toClear.add(theItem);
			}
		}

		BackgroundWorker<Object> theWorker = new BackgroundWorker<Object>(this, title) {
			protected Object doBackgroundWork(ErrorLogger errors) {
				clearCaches(errors, toClear);
				return null;
			}
		};

		theWorker.runWork("Deleting cache files, please wait...", errorReporter);

		updateStudyList(false, true, false);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// See comments in deleteItems(), this will lock the study during the cache clear, unless it is open in a window
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
		StudyListItem theItem = checkItem(rowIndex, errorReporter);
		if ((null == theItem) || theItem.isFolder) {
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

		checkItem(rowIndex, null);
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
	// replaces the start dialog in the open study tracking then the panel is handed off to the run manager.  The
	// start dialog will hide without calling editorClosing when this returns success.

	public boolean applyEditsFrom(AppEditor theEditor) {

		if (!isVisible()) {
			return true;
		}

		if (theEditor instanceof RunStart) {

			RunStart theDialog = (RunStart)theEditor;
			Integer theKey = Integer.valueOf(theDialog.getStudyKey());
			if (theDialog != lockHolders.get(theKey)) {
				return false;
			}

			RunPanel theRun = theDialog.getRunPanel();
			StudyLockHolder theHolder = theDialog.getStudyLockHolder();
			if ((null == theRun) || (null == theHolder)) {
				return false;
			}

			RunManager.addRunPanel(theRun);

			lockHolders.put(theKey, theHolder);

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
				updateStudyList(false, true, false);
			}

			int rowIndex = studyListModel.indexOfStudy(theKey);
			if (rowIndex >= 0) {
				studyListModel.get(rowIndex).clearCacheSize();
				checkItem(rowIndex, null);
			}

			return true;
		}

		// If the study build dialog sent this, retrieve the selected record from the dialog, get options from the
		// accessory panel, retrieve the run panel, and give it to the run manager.  The data viewer RecordFind dialog
		// should never call this.  Note in this case the run panel is not yet a lock holder because the study does
		// not exist.  The run panel will use the StudyBuild object to create the study, then once it exists and is
		// locked the panel calls back here so the generic StudyLockHolder case above will add it as a lock holder.

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
							doOpenItem(theStudy);
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

				RunPanelStudy theRun = new RunPanelStudy(this, dbID);
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
				RunManager.addRunPanel(theRun);
			}

			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Sent by editors and run panels when they are done with a study; clear the study lock if needed (some holders
	// clear the lock themselves), then update the list item.  If the holdStudyLocks flag is true the lock is never
	// cleared; see doDeleteItem().  Also sent by other dependent modeless dialogs when closing.

	public void editorClosing(AppEditor theEditor) {

		if (!isVisible()) {
			return;
		}

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

				StudyListItem theItem = studyItemMap.get(theKey);
				if (null != theItem) {
					if (didUnlock) {
						theItem.studyLock = Study.LOCK_NONE;
						theItem.lockCount = theLockCount + 1;
					} else {
						theItem.studyLock = theStudyLock;
						theItem.lockCount = theLockCount;
					}
					studyListModel.itemWasChanged(theItem);
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

		if (isVisible()) {
			return;
		}

		DbCore.openDb(dbID, this);

		DbController.restoreColumnWidths(dbID, getKeyTitle(), studyListTable);

		blockActionsClear();

		SwingUtilities.invokeLater(new Runnable() {
			public void run() {

				errorReporter.setTitle("Load Study List");
				studyListModel.setItems(getItems(errorReporter), true);
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
	// Can only close if there are no study lock holders (e.g. open editor windows, except for run panels which can
	// continue to exist in the run manager after this window is closed.  However those are told to discard reference
	// to this manager.  Some other windows and dialogs will be told to close if possible but those calls can fail if
	// there are other dependent windows open or unsaved state.

	public boolean windowShouldClose() {

		if (!isVisible()) {
			return true;
		}

		for (StudyLockHolder theHolder : lockHolders.values()) {
			if (!(theHolder instanceof RunPanel)) {
				AppController.beep();
				theHolder.toFront();
				return false;
			}
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

		for (StudyLockHolder theHolder : lockHolders.values()) {
			if (!theHolder.studyManagerClosing()) {
				AppController.beep();
				theHolder.toFront();
				return false;
			}
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillClose() {

		studyItemMap.clear();
		folderItemMap.clear();

		lockHolders.clear();

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

		// Remove this manager from the map and close the database.

		studyManagers.remove(dbID);
		DbCore.closeDb(dbID, this);
	}
}
