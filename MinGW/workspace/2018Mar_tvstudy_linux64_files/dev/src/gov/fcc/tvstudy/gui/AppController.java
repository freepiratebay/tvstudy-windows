//
//  AppController.java
//  TVStudy
//
//  Copyright (c) 2012-2018 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;

import codeid.CodeID;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;
import gov.fcc.tvstudy.gui.editor.*;
import gov.fcc.tvstudy.gui.run.*;

import java.util.*;
import java.awt.*;
import java.awt.event.*;
import java.net.*;
import java.io.*;
import java.nio.charset.*;

import javax.swing.*;
import javax.swing.text.*;
import javax.swing.event.*;
import javax.swing.table.*;


//=====================================================================================================================
// Windowed application controller.  Provides window management and global application UI.  Most capabilities are in
// static methods and do not need an instance of the class.  However window management (WindowListener methods) does
// require an instance, so this class is instantiated on the Swing event thread as a private singleton.  All properties
// are static, and the public API is entirely static methods which will forward to the private instance when needed.

public class AppController implements WindowListener {

	// Constant used for assigning shortcut keys to menu items.

	public static final int MENU_SHORTCUT_KEY_MASK = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();

	// A color close to the normal gray window background (at least it is on MacOS with default LAF), used for non-
	// editable components, see setComponentEnabled().

	public static final Color DISABLED_TEXT_BACKGROUND = new Color(233, 233, 233);

	// Convenience for UI setup code, arrays of widths for JTextField or dimensions for JLabel per number of columns.

	public static final int SIZE_MAX_COLUMNS = 80;

	public static final int[] textFieldWidth = new int[SIZE_MAX_COLUMNS + 1];
	public static final Dimension[] labelSize = new Dimension[SIZE_MAX_COLUMNS + 1];

	// The private singleton instance.

	private static AppController controller = null;

	// Window management.

	private static Window currentWindow;

	private static ArrayList<AppFrame> windowList;
	private static boolean[] shortcutKeyInUse;


	//-----------------------------------------------------------------------------------------------------------------
	// Application startup, the constructor does all the work but must run on the event thread.

	public static void main(String args[]) throws Exception {
		SwingUtilities.invokeAndWait(new Runnable() {
			public void run() {
				new AppController();
			}
		});
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Methods for window management and other UI operations.  First is the method used to show a managed window, take
	// control of the window and make it visible.  This will block on modal dialogs.  A window shown by this method
	// must always be hidden with hideWindow().  Usually that will be triggered automatically, when the window is sent
	// a window-closing event.  For modal dialogs, hideWindow() must be used in place of setVisible(false) to end the
	// modal state.  For JFrame and JDialog windows, the default close operation is set to do-nothing so the Swing
	// window-closing behavior doesn't conflict with the one provided here.  If the window is a AppFrame instance, it
	// gets added to the list of open windows to appear in the Window menu of it and any other AppFrame windows, see
	// windowActivated() and updateCurrentWindowMenu() in the instance methods.  This will first make the title of the
	// window unique vs. everything already in the window list, and may also set a shortcut key to bring the window
	// forward, see updateCurrentWindowMenu().  For AppFrame or AppDialog windows, this also may restore the window
	// position and size using the window title as a property key.  Also calls the windowWillOpen() method in AppFrame
	// or AppDialog to do any last-minute UI setup before the window becomes visible.

	public static void showWindow(Window win) {

		if (win.isVisible()) {
			return;
		}

		win.addWindowListener(controller);

		if (win instanceof JFrame) {
			((JFrame)win).setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		} else {
			if (win instanceof JDialog) {
				((JDialog)win).setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
			}
		}

		if (win instanceof AppFrame) {

			AppFrame awin = (AppFrame)win;

			int sc = -1;
			for (int i = 0; i < 10; i++) {
				if (!shortcutKeyInUse[i]) {
					sc = i;
					shortcutKeyInUse[i] = true;
					break;
				}
			}
			awin.setShortcutKey(sc);

			String baseTitle = awin.getBaseTitle();
			ArrayList<Integer> otherKeys = new ArrayList<Integer>();
			for (AppFrame f : windowList) {
				if (f.getBaseTitle().equals(baseTitle)) {
					otherKeys.add(Integer.valueOf(f.getTitleKey()));
				}
			}

			int locX = awin.getLocation().x;
			int locY = awin.getLocation().y;

			// Attempt to preserve the title uniqueness key as is, change it only if there is a conflict.

			int tKey = awin.getTitleKey();
			if (otherKeys.contains(Integer.valueOf(tKey))) {
				tKey = 0;
				while (otherKeys.contains(Integer.valueOf(tKey))) {
					tKey++;
					locX += 20;
					locY += 20;
				}
				awin.setTitleKey(tKey);
			}

			if (awin.getLocationSaved()) {
				DbController.restoreWindowLocation(awin, awin.getDbID(), awin.getKeyTitle(), locX, locY);
			}

			windowList.add(awin);

			awin.windowWillOpen();

		} else {

			if (win instanceof AppDialog) {

				AppDialog awin = (AppDialog)win;

				if (awin.getLocationSaved()) {
					DbController.restoreWindowLocation(awin, awin.getDbID(), awin.getKeyTitle(), awin.getLocation().x,
						awin.getLocation().y);
				}

				awin.windowWillOpen();
			}
		}

		win.setVisible(true);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static boolean hasOpenWindows() {

		return !windowList.isEmpty();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Hide a window previously shown.  The window will be hidden but will not be disposed, unless it is a AppFrame
	// or AppDialog that requests dispose-on-close.  Also if it is a AppFrame or AppDialog, save window position and
	// size as needed.  Usually a window is hidden when it is current, but if not update the current window menu.
	// Also call windowWillClose() on AppFrame or AppDialog.

	public static void hideWindow(Window win) {

		if (!win.isVisible()) {
			return;
		}

		boolean doLogin = false;

		win.removeWindowListener(controller);

		if (win instanceof AppFrame) {
			AppFrame awin = (AppFrame)win;

			if (windowList.contains(awin)) {

				if (awin.getShortcutKey() >= 0) {
					shortcutKeyInUse[awin.getShortcutKey()] = false;
				}

				if (awin.getLocationSaved()) {
					DbController.saveWindowLocation(awin, awin.getDbID(), awin.getKeyTitle());
				}

				windowList.remove(awin);
				if ((null != currentWindow) && (awin != currentWindow)) {
					controller.updateCurrentWindowMenu();
				}

				doLogin = windowList.isEmpty();
			}

			awin.windowWillClose();

			if (awin.getDisposeOnClose()) {
				awin.dispose();
			} else {
				awin.setVisible(false);
			}

		} else {

			if (win instanceof AppDialog) {
				AppDialog awin = (AppDialog)win;

				if (awin.getLocationSaved()) {
					DbController.saveWindowLocation(awin, awin.getDbID(), awin.getKeyTitle());
				}

				awin.windowWillClose();

				if (awin.getDisposeOnClose()) {
					awin.dispose();
				} else {
					awin.setVisible(false);
				}

			} else {

				win.setVisible(false);
			}
		}

		if (win == currentWindow) {
			currentWindow = null;
		}

		// Automatically open the login dialog when the last top-level window closes (determined above).  When the
		// login dialog is closed and there are no other top-level windows, save local properties and exit.  When no
		// managed top-level windows are open the login dialog will not allow itself to be closed until there are no
		// other open windows at all, managed or otherwise.  This protocol ensures the JVM shuts down once the login
		// dialog is gone and can't be brought back, regardless of how the JVM exit behavior is configured.  The Quit
		// button in the login dialog is always enabled based on whether or not there are any top-level windows.

		if (doLogin) {
			DbController.showLogin(true);
		} else {
			if (DbController.isLoginWindow(win) && windowList.isEmpty()) {
				System.exit(0);
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Next is a collection of UI support and convenience methods.  First, methods to set and check the enabled state
	// of a UI component.  In this context enabled sets whether or not the component can be used to change property
	// values; not-enabled means read-only but not necessarily empty, data may still be displayed.  The goal is to make
	// the appearance more consistent between different components regardless of whether they have a setEditable() or
	// only a setEnabled().  Text components get setEditable() and a change of background color, tables setEnabled()
	// and change of background and grid colors.  Unfortunately there isn't anything similar for checkboxes and combo
	// boxes (need to write some custom subclasses), so for now those just get setEnabled().  Note text components also
	// have caret visibility set directly, setEditable() does not update that as setEnabled() would.

	public static void setComponentEnabled(Component c, boolean e) {

		if (c instanceof JTextComponent) {

			JTextComponent tc = (JTextComponent)c;
			tc.setEditable(e);
			if (e) {
				tc.setBackground(Color.WHITE);
				tc.getCaret().setVisible(tc.isFocusOwner());
			} else {
				tc.setBackground(DISABLED_TEXT_BACKGROUND);
				tc.getCaret().setVisible(false);
			}

		} else {

			if (c instanceof JTable) {

				JTable tb = (JTable)c;
				Container p = tb.getParent();
				tb.setEnabled(e);
				if (e) {
					p.setBackground(Color.WHITE);
					tb.setBackground(Color.WHITE);
					tb.setGridColor(Color.WHITE);
				} else {
					p.setBackground(DISABLED_TEXT_BACKGROUND);
					tb.setBackground(DISABLED_TEXT_BACKGROUND);
					tb.setGridColor(DISABLED_TEXT_BACKGROUND);
				}

			} else {

				c.setEnabled(e);
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static boolean isComponentEnabled(Component c) {

		if (c instanceof JTextComponent) {
			return ((JTextComponent)c).isEditable();
		} else {
			return c.isEnabled();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Modify a component's key bindings to remove customization of focus traversal keystrokes and use of the keys
	// reserved for list navigation in the editors (command-uparrow and command-downarrow).  Due to sharing of the
	// input maps calling this on any one component will probably affect all components of similar class, but the
	// removal of custom traversal keys has to be done on every component so this is always called regardless.

	private static int[] maps = null;
	private static KeyStroke[] keys = null;

	public static void fixKeyBindings(JComponent c) {

		if (null == maps) {

			maps = new int[3];
			maps[0] = JComponent.WHEN_IN_FOCUSED_WINDOW;
			maps[1] = JComponent.WHEN_FOCUSED;
			maps[2] = JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT;

			keys = new KeyStroke[4];
			keys[0] = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0);
			keys[1] = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, Event.SHIFT_MASK);
			keys[2] = KeyStroke.getKeyStroke(KeyEvent.VK_UP, MENU_SHORTCUT_KEY_MASK);
			keys[3] = KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, MENU_SHORTCUT_KEY_MASK);
		}

		for (int mi : maps) {
			InputMap m = c.getInputMap(mi);
			while (null != m) {
				for (KeyStroke ks : keys) {
					m.remove(ks);
				}
				m = m.getParent();
			}
		}

		c.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null);
		c.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Standard configuration for a JTable.  If the EditMenu argument is non-null, multi-row selection is allowed and
	// the table is set as the menu's target table.  The table is never made focusable; JTable's behavior when focused
	// is inappropriate for the UI style in this application.  EditMenu will act on the table directly as needed.

	public static void configureTable(JTable theTable) {
		configureTable(theTable, null);
	}

	public static void configureTable(JTable theTable, EditMenu theEditMenu) {

		theTable.setFocusable(false);
		theTable.setColumnSelectionAllowed(false);
		theTable.getTableHeader().setReorderingAllowed(false);

		if (null != theEditMenu) {
			theTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
			theEditMenu.setTargetTable(theTable);
		} else {
			theTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Standard setup of a JScrollPane.  If the component is not a table, also set the scroll increment.  JScrollPane
	// will adjust scrolling automatically for tables based on row height.

	public static JScrollPane createScrollPane(Component c) {
		return createScrollPane(c, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
	}

	public static JScrollPane createScrollPane(Component c, int vsbPolicy, int hsbPolicy) {

		JScrollPane sp = new JScrollPane(c, vsbPolicy, hsbPolicy);

		if (!(c instanceof JTable)) {
			sp.getVerticalScrollBar().setUnitIncrement(16);
		}

		return sp;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Show preferences dialog.

	private static PrefsDialog prefsDialog = null;

	public static void showPreferences() {

		if (null == prefsDialog) {
			prefsDialog = new PrefsDialog();
		}
		if (!prefsDialog.isVisible()) {
			showWindow(prefsDialog);
		} else {
			prefsDialog.toFront();
		}
	}


	//=================================================================================================================
	// Preferences dialog.

	private static class PrefsDialog extends AppDialog {

		private static final String WINDOW_TITLE = "TVStudy Preferences";

		private JComboBox<String> defaultEngineMemoryLimitMenu;
		private JCheckBox autoDeletePreviousDownloadCheckBox;
		private JCheckBox ixCheckIncludeForeignDefaultCheckBox;
		private JCheckBox ixCheckDefaultCPExcludesBLCheckBox;
		private JCheckBox ixCheckDefaultExcludeNewLPTVCheckBox;
		private JCheckBox studyManagerNameColumnFirstCheckBox;
		private JCheckBox showDbNameCheckBox;

		private JButton okButton;


		//-------------------------------------------------------------------------------------------------------------

		private PrefsDialog() {

			super(null, null, WINDOW_TITLE, Dialog.ModalityType.MODELESS);

			defaultEngineMemoryLimitMenu = new JComboBox<String>();
			defaultEngineMemoryLimitMenu.setFocusable(false);
			defaultEngineMemoryLimitMenu.addItem("All");
			for (int frac = 2; frac <= AppCore.maxEngineProcessCount; frac++) {
				defaultEngineMemoryLimitMenu.addItem("1/" + String.valueOf(frac));
			}

			JPanel memFracPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
			memFracPanel.add(new JLabel("   Default study engine memory use"));
			memFracPanel.add(defaultEngineMemoryLimitMenu);

			autoDeletePreviousDownloadCheckBox =
				new JCheckBox("Delete previous downloaded station data after new download");
			autoDeletePreviousDownloadCheckBox.setFocusable(false);

			ixCheckIncludeForeignDefaultCheckBox =
				new JCheckBox("IX check study: Include non-U.S. stations");
			ixCheckIncludeForeignDefaultCheckBox.setFocusable(false);

			ixCheckDefaultCPExcludesBLCheckBox =
				new JCheckBox("IX check study: CP records exclude baseline");
			ixCheckDefaultCPExcludesBLCheckBox.setFocusable(false);

			ixCheckDefaultExcludeNewLPTVCheckBox =
				new JCheckBox("IX check study: Exclude new LPTV station records");
			ixCheckDefaultExcludeNewLPTVCheckBox.setFocusable(false);

			studyManagerNameColumnFirstCheckBox =
				new JCheckBox("Show study name column first in the study manager");
			studyManagerNameColumnFirstCheckBox.setFocusable(false);

			if (AppCore.Debug) {
				showDbNameCheckBox = new JCheckBox("Show root database name field in login dialog");
				showDbNameCheckBox.setFocusable(false);
			}

			JPanel prefsPanel = new JPanel();
			prefsPanel.setLayout(new BoxLayout(prefsPanel, BoxLayout.Y_AXIS));

			prefsPanel.add(memFracPanel);

			JPanel boxP = new JPanel(new FlowLayout(FlowLayout.LEFT));
			boxP.add(autoDeletePreviousDownloadCheckBox);
			prefsPanel.add(boxP);

			boxP = new JPanel(new FlowLayout(FlowLayout.LEFT));
			boxP.add(ixCheckIncludeForeignDefaultCheckBox);
			prefsPanel.add(boxP);

			boxP = new JPanel(new FlowLayout(FlowLayout.LEFT));
			boxP.add(ixCheckDefaultCPExcludesBLCheckBox);
			prefsPanel.add(boxP);

			boxP = new JPanel(new FlowLayout(FlowLayout.LEFT));
			boxP.add(ixCheckDefaultExcludeNewLPTVCheckBox);
			prefsPanel.add(boxP);

			boxP = new JPanel(new FlowLayout(FlowLayout.LEFT));
			boxP.add(studyManagerNameColumnFirstCheckBox);
			prefsPanel.add(boxP);

			if (AppCore.Debug) {
				boxP = new JPanel(new FlowLayout(FlowLayout.LEFT));
				boxP.add(showDbNameCheckBox);
				prefsPanel.add(boxP);
			}

			okButton = new JButton("OK");
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
			
			JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
			buttonPanel.add(cancelButton);
			buttonPanel.add(okButton);

			Container cp = getContentPane();
			cp.add(prefsPanel, BorderLayout.CENTER);
			cp.add(buttonPanel, BorderLayout.SOUTH);

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

		private void doOK() {

			AppCore.setPreference(AppCore.PREF_DEFAULT_ENGINE_MEMORY_LIMIT,
				String.valueOf(defaultEngineMemoryLimitMenu.getSelectedIndex()));

			AppCore.setPreference(AppCore.CONFIG_AUTO_DELETE_PREVIOUS_DOWNLOAD,
				String.valueOf(autoDeletePreviousDownloadCheckBox.isSelected()));

			AppCore.setPreference(AppCore.CONFIG_TVIX_INCLUDE_FOREIGN_DEFAULT,
				String.valueOf(ixCheckIncludeForeignDefaultCheckBox.isSelected()));

			AppCore.setPreference(AppCore.PREF_TVIX_DEFAULT_CP_EXCLUDES_BL,
				String.valueOf(ixCheckDefaultCPExcludesBLCheckBox.isSelected()));

			AppCore.setPreference(AppCore.PREF_TVIX_DEFAULT_EXCLUDE_NEW_LPTV,
				String.valueOf(ixCheckDefaultExcludeNewLPTVCheckBox.isSelected()));

			AppCore.setPreference(AppCore.PREF_STUDY_MANAGER_NAME_COLUMN_FIRST,
				String.valueOf(studyManagerNameColumnFirstCheckBox.isSelected()));

			if (AppCore.Debug) {
				AppCore.setPreference(AppCore.CONFIG_SHOW_DB_NAME, String.valueOf(showDbNameCheckBox.isSelected()));
			}

			if (windowShouldClose()) {
				hideWindow(this);
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doCancel() {

			if (windowShouldClose()) {
				hideWindow(this);
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		public void windowWillOpen() {

			String str = AppCore.getPreference(AppCore.PREF_DEFAULT_ENGINE_MEMORY_LIMIT);
			if (null != str) {
				int lim = 0;
				try {
					lim = Integer.parseInt(str);
				} catch (NumberFormatException ne) {
				}
				if (lim > AppCore.maxEngineProcessCount) {
					lim = AppCore.maxEngineProcessCount;
				}
				if (lim < 0) {
					lim = 0;
				}
				defaultEngineMemoryLimitMenu.setSelectedIndex(lim);
			} else {
				defaultEngineMemoryLimitMenu.setSelectedIndex(0);
			}

			str = AppCore.getPreference(AppCore.CONFIG_AUTO_DELETE_PREVIOUS_DOWNLOAD);
			if (null != str) {
				autoDeletePreviousDownloadCheckBox.setSelected(Boolean.valueOf(str).booleanValue());
			} else {
				autoDeletePreviousDownloadCheckBox.setSelected(false);
			}

			str = AppCore.getPreference(AppCore.CONFIG_TVIX_INCLUDE_FOREIGN_DEFAULT);
			if (null != str) {
				ixCheckIncludeForeignDefaultCheckBox.setSelected(Boolean.valueOf(str).booleanValue());
			} else {
				ixCheckIncludeForeignDefaultCheckBox.setSelected(false);
			}

			str = AppCore.getPreference(AppCore.PREF_TVIX_DEFAULT_CP_EXCLUDES_BL);
			if (null != str) {
				ixCheckDefaultCPExcludesBLCheckBox.setSelected(Boolean.valueOf(str).booleanValue());
			} else {
				ixCheckDefaultCPExcludesBLCheckBox.setSelected(false);
			}

			str = AppCore.getPreference(AppCore.PREF_TVIX_DEFAULT_EXCLUDE_NEW_LPTV);
			if (null != str) {
				ixCheckDefaultExcludeNewLPTVCheckBox.setSelected(Boolean.valueOf(str).booleanValue());
			} else {
				ixCheckDefaultExcludeNewLPTVCheckBox.setSelected(false);
			}

			str = AppCore.getPreference(AppCore.PREF_STUDY_MANAGER_NAME_COLUMN_FIRST);
			if (null != str) {
				studyManagerNameColumnFirstCheckBox.setSelected(Boolean.valueOf(str).booleanValue());
			} else {
				studyManagerNameColumnFirstCheckBox.setSelected(false);
			}

			if (AppCore.Debug) {
				str = AppCore.getPreference(AppCore.CONFIG_SHOW_DB_NAME);
				if (null != str) {
					showDbNameCheckBox.setSelected(Boolean.valueOf(str).booleanValue());
				} else {
					showDbNameCheckBox.setSelected(false);
				}
			}

			getRootPane().setDefaultButton(okButton);

			blockActionsClear();
		}


		//-------------------------------------------------------------------------------------------------------------

		public void windowWillClose() {

			blockActionsSet();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Open TVStudy full manual PDF.  No interactive help at the moment.

	public static void showHelp() {

		try {
			Desktop.getDesktop().open(new File(AppCore.helpDirectoryPath + File.separator + "manual.pdf"));
		} catch (Throwable t) {
			AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
			showMessage("Cannot open the documentation file:\n" + t.getMessage(), "Documentation Not Available",
				AppCore.ERROR_MESSAGE);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Show about message.

	public static void showAbout() {

		showMessage(
			"GUI Version " + AppCore.APP_VERSION_STRING + " (" + CodeID.ID + ")\n" +
			"Engine Version " + AppCore.engineVersionString + "\n" +
			"Database Version " + AppCore.formatVersion(DbCore.DATABASE_VERSION) + "\n" +
			"Support Files " + AppCore.fileCheckID, "About TVStudy");
	}

	
	//-----------------------------------------------------------------------------------------------------------------
	// Check for updates.

	public static void showUpdate() {

		String title = "Check for Updates";

		BackgroundWorker<String> theWorker = new BackgroundWorker<String>(null, title) {
			protected String doBackgroundWork(ErrorLogger reporter) {

				boolean error = false;
				String newVersion = "", errmsg = "";

				try {

					String str = AppCore.getConfig(AppCore.CONFIG_VERSION_CHECK_URL);
					if (null == str) {
						error = true;
						errmsg = "Configuration error, no URL for version check.";
					} else {

						URL url = new URL(str);
						BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()));
						newVersion = br.readLine();

						int v = AppCore.parseVersion(newVersion);
						if (v < 0) {
							error = true;
							errmsg = "The update check did not return a valid version number.";
						} else {
							if (v <= AppCore.APP_VERSION) {
								newVersion = "";
							}
						}
					}

				} catch (Throwable t) {
					error = true;
					errmsg = t.getMessage();
					AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
				}

				if (error) {
					reporter.reportWarning("An error occurred while checking for updates:\n" + errmsg +
						"\nPlease check again later.");
					return null;
				}

				return newVersion;
			}
		};

		ErrorReporter reporter = new ErrorReporter(null, title);
		String newVersion = theWorker.runWork("Checking for updates, please wait...", reporter);
		if (null == newVersion) {
			return;
		}

		if (0 == newVersion.length()) {
			reporter.reportMessage("TVStudy is up to date.");
		} else {
			reporter.reportMessage("New version " + newVersion + " of TVStudy is available.\n" +
				"Please visit the TVStudy website to download.");
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Verify installation of study engine database files, done with an external command-line tool.

	public static void checkInstall() {

		ArrayList<String> arguments = new ArrayList<String>();
		arguments.add(AppCore.libDirectoryPath + File.separator + "check_install");
		arguments.add(AppCore.workingDirectoryPath);
		RunPanelProcess theRun = new RunPanelProcess("Installation Check", arguments);
		theRun.memoryFraction = 0.;
		if (theRun.initialize(null)) {
			RunManager.addRunPanel(theRun);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Convenience methods to beep and show message dialogs.  Currently just wrappers around JOptionPane.

	public static void beep() {

		Toolkit.getDefaultToolkit().beep();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Show a message dialog, just a wrapper around JOptionPane.

	public static void showMessage(Object message, String title) {
		showMessage((Component)null, message, title, AppCore.INFORMATION_MESSAGE);
	}

	public static void showMessage(Object message, String title, int messageType) {
		showMessage((Component)null, message, title, messageType);
	}

	public static void showMessage(Component parent, Object message, String title) {
		showMessage(parent, message, title, AppCore.INFORMATION_MESSAGE);
	}

	public static void showMessage(Component parent, Object message, String title, int messageType) {

		int type = JOptionPane.INFORMATION_MESSAGE;
		if (AppCore.WARNING_MESSAGE == messageType) {
			type = JOptionPane.WARNING_MESSAGE;
			beep();
		} else {
			if (AppCore.ERROR_MESSAGE == messageType) {
				type = JOptionPane.ERROR_MESSAGE;
				beep();
			}
		}

		JOptionPane.showMessageDialog(parent, message, title, type);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static void showLongMessage(String message, String title) {
		showLongMessage((Component)null, message, title, AppCore.INFORMATION_MESSAGE);
	}

	public static void showLongMessage(String message, String title, int messageType) {
		showLongMessage((Component)null, message, title, messageType);
	}

	public static void showLongMessage(Component parent, String message, String title) {
		showLongMessage(parent, message, title, AppCore.INFORMATION_MESSAGE);
	}

	public static void showLongMessage(Component parent, String message, String title, int messageType) {

		JTextArea ta = new JTextArea(15, 80);
		ta.setFont(new Font("Monospaced", Font.PLAIN, 12));
		ta.setLineWrap(true);
		ta.setWrapStyleWord(true);
		setComponentEnabled(ta, false);
		ta.setText(message);

		showMessage(parent, createScrollPane(ta), title, messageType);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This is a singleton class, make sure it stays that way.

	public AppController() {

		if (null != controller) {
			throw new RuntimeException("Application controller already exists");
		}
		controller = this;

		// Initialize application support.

		AppCore.initialize(System.getProperty("user.dir"), true, true);

		if (null != AppCore.fileCheckError) {
			showMessage(AppCore.fileCheckError + "\nPlease install the correct support files.", "Installation Error",
				AppCore.ERROR_MESSAGE);
			return;
		}

		// Set up for window management.

		windowList = new ArrayList<AppFrame>();
		shortcutKeyInUse = new boolean[10];
		for (int i = 0; i < 10; i++) {
			shortcutKeyInUse[i] = false;
		}

		// Add a key event dispatcher to process the window-close keystroke.  This ensures window close will always
		// work for any window made visible with showWindow().  The menu item in the Window menu is unreliable because
		// key events may not always be dispatched to the menus, i.e. in modal dialogs without menus.

		KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(new KeyEventDispatcher() {
			public boolean dispatchKeyEvent(KeyEvent theEvent) {
				if (!theEvent.isConsumed() && (null != currentWindow) &&
						(theEvent.getID() == KeyEvent.KEY_PRESSED) &&
						(theEvent.getModifiers() == MENU_SHORTCUT_KEY_MASK) &&
						(theEvent.getKeyCode() == KeyEvent.VK_W)) {
					theEvent.consume();
					SwingUtilities.invokeLater(new Runnable() {
						public void run() {
							if (null != currentWindow) {
								currentWindow.dispatchEvent(new WindowEvent(currentWindow, WindowEvent.WINDOW_CLOSING));
							}
						}
					});
					return true;
				}
				return false;
			}
		});

		// Default dismiss time for tool-tips is way too short.

		ToolTipManager.sharedInstance().setDismissDelay(15000);

		// Set up arrays of sizes for convenience during UI setup.

		JTextField jtf = new JTextField(1);
		JPanel jp = new JPanel();
		jp.add(jtf);
		jp.doLayout();
		int fieldSizeBaseWidth = jtf.getWidth();
		jtf.setColumns(2);
		jp.doLayout();
		int fieldSizeWidthIncrement = jtf.getWidth() - fieldSizeBaseWidth;

		JLabel jl = new JLabel("XXXXXXXXXX");
		jp = new JPanel();
		jp.add(jl);
		jp.doLayout();
		int labelSizeWidthIncrement = jl.getWidth() / 10;
		int labelSizeHeight = jl.getHeight();

		for (int nc = 0; nc <= SIZE_MAX_COLUMNS; nc++) {
			textFieldWidth[nc] = fieldSizeBaseWidth + ((nc - 1) * fieldSizeWidthIncrement);
			labelSize[nc] = new Dimension((nc * labelSizeWidthIncrement), labelSizeHeight);
		}

		// If debugging is on, show a debug info dialog.

		if (AppCore.Debug) {

			showWindow(new AppDialog(null, null, "Debug Info", Dialog.ModalityType.MODELESS) {

				private JLabel totalMemoryLabel = new JLabel();
				private JLabel freeMemoryLabel = new JLabel();
				private JLabel diskSpaceLabel = new JLabel();
				private JLabel windowWidthLabel = new JLabel();
				private JLabel windowHeightLabel = new JLabel();

				private javax.swing.Timer debugTimer = new javax.swing.Timer(5000, new ActionListener() {
					public void actionPerformed(ActionEvent e) {
						updateDebugInfo();
					}
				});

				private void updateDebugInfo() {
					long theValue = Runtime.getRuntime().totalMemory() / 1000000L;
					totalMemoryLabel.setText(" total memory = " + theValue + " MB ");
					theValue = Runtime.getRuntime().freeMemory() / 1000000L;
					freeMemoryLabel.setText(" free memory = " + theValue + " MB ");
					theValue = (new File(AppCore.workingDirectoryPath)).getFreeSpace() / 1000000L;
					diskSpaceLabel.setText(" free disk space = " + theValue + " MB ");
					Window win = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
					if (null == win) {
						windowWidthLabel.setText(" window width = (no focus) ");
						windowHeightLabel.setText(" window height = (no focus) ");
					} else {
						windowWidthLabel.setText(" window width = " + win.getWidth() + " ");
						windowHeightLabel.setText(" window height = " + win.getHeight() + " ");
					}
				}

				public String getDbID() {
					return null;
				}

				public RootEditor getRootEditor() {
					return null;
				}

				public void windowWillOpen() {
					updateDebugInfo();
					Container cp = getContentPane();
					cp.setLayout(new BoxLayout(cp, BoxLayout.Y_AXIS));
					long theValue = Runtime.getRuntime().maxMemory() / 1000000L;
					cp.add(new JLabel(" max memory = " + theValue + " MB "));
					cp.add(totalMemoryLabel);
					cp.add(freeMemoryLabel);
					cp.add(diskSpaceLabel);
					cp.add(windowWidthLabel);
					cp.add(windowHeightLabel);
					cp.add(new JLabel(" default charset = " + Charset.defaultCharset().displayName() + " "));
					pack();
					debugTimer.start();
				}

				public void windowWillClose() {
					debugTimer.stop();
				}
			});
		}

		// Show the database login dialog.

		DbController.showLogin(true);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// WindowListener methods.

	public void windowOpened(WindowEvent theEvent) {
	}


	//-----------------------------------------------------------------------------------------------------------------
	// When a AppFrame or AppDialog is closing, give it a chance to cancel the close.

	public void windowClosing(WindowEvent theEvent) {

		Window win = theEvent.getWindow();
		if (((win instanceof AppFrame) && !((AppFrame)win).windowShouldClose()) ||
				((win instanceof AppDialog) && !((AppDialog)win).windowShouldClose())) {
			return;
		}

		hideWindow(win);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This shouldn't occur, it means dispose() was called on a window that should have been hidden with hideWindow().

	public void windowClosed(WindowEvent theEvent) {

		hideWindow(theEvent.getWindow());
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Activation and de-activation update currentWindow, and also it's Window menu as needed.

	public void windowActivated(WindowEvent theEvent) {

		currentWindow = theEvent.getWindow();
		updateCurrentWindowMenu();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowDeactivated(WindowEvent theEvent) {

		if (theEvent.getWindow() == currentWindow) {
			currentWindow = null;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowIconified(WindowEvent theEvent) {
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowDeiconified(WindowEvent theEvent) {
	}


	//-----------------------------------------------------------------------------------------------------------------
	// If the current window is an AppFrame, update it's window menu to show the current list of windows.  The menu
	// items added are also given to each of the windows listed, using setWindowMenuItem(), so changes to any window's
	// title will always be applied directly to the menu items in the foreground window's menu.

	private void updateCurrentWindowMenu() {

		if (!(currentWindow instanceof AppFrame)) {
			return;
		}

		JMenu theMenu = ((AppFrame)currentWindow).getWindowMenu();

		theMenu.removeAll();

		JMenuItem miCloseWindow = new JMenuItem("Close Window");
		miCloseWindow.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (null != currentWindow) {
					currentWindow.dispatchEvent(new WindowEvent(currentWindow, WindowEvent.WINDOW_CLOSING));
				}
			}
		});
		miCloseWindow.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_W, MENU_SHORTCUT_KEY_MASK));
		theMenu.add(miCloseWindow);

		JMenuItem miMinWindow = new JMenuItem("Minimize");
		miMinWindow.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if ((null != currentWindow) && (currentWindow instanceof Frame)) {
					((Frame)currentWindow).setExtendedState(Frame.ICONIFIED);
				}
			}
		});
		miMinWindow.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_M, MENU_SHORTCUT_KEY_MASK));
		theMenu.add(miMinWindow);

		theMenu.addSeparator();

		JMenuItem miLogin = new JMenuItem("Open Database...");
		miLogin.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				DbController.showLogin(false);
			}
		});
		miLogin.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_L, MENU_SHORTCUT_KEY_MASK));
		theMenu.add(miLogin);

		theMenu.addSeparator();

		JMenuItem miWindow;

		for (AppFrame f : windowList) {

			if (f == currentWindow) {
				miWindow = new JCheckBoxMenuItem();
				miWindow.setSelected(true);
			} else {
				miWindow = new JMenuItem();
			}

			f.setWindowMenuItem(miWindow);

			if (f.getShortcutKey() >= 0) {
				miWindow.setAccelerator(KeyStroke.getKeyStroke(Integer.toString(f.getShortcutKey()).charAt(0),
					MENU_SHORTCUT_KEY_MASK));
			}

			final AppFrame win = f;
			miWindow.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					win.toFront();
				}
			});

			theMenu.add(miWindow);
		}
	}
}
