//
//  TemplateEditor.java
//  TVStudy
//
//  Copyright (c) 2015-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui.editor;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;
import gov.fcc.tvstudy.gui.*;

import java.util.*;
import java.sql.*;
import java.awt.*;
import java.awt.event.*;
import java.text.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.table.*;


//=====================================================================================================================
// Template editor, allows editing parameters and interference rules in a template.  This is basically a stripped-down
// version of StudyEditor with only the rules and parameters UIs, see that class for further details.

public class TemplateEditor extends RootEditor {

	public static final String WINDOW_TITLE = "Template";

	private TemplateEditData template;

	private ArrayList<ParameterEditor> parameterEditors;

	private boolean hasNewParameters;

	private IxRuleTableModel ixRuleModel;
	private JTable ixRuleTable;

	private HashMap<Integer, IxRuleEditor> ixRuleEditors;

	private JButton editIxRuleButton;

	private JMenuItem editIxRuleMenuItem;
	private JMenuItem deleteIxRuleMenuItem;

	private JTabbedPane editorTabPane;
	private int lastSelectedTab = -1;

	private boolean dataChanged;

	// Disambiguation.

	private TemplateEditor outerThis = this;


	//-----------------------------------------------------------------------------------------------------------------

	public TemplateEditor(AppEditor theParent, TemplateEditData theTemplate) {

		super(theParent, WINDOW_TITLE);

		template = theTemplate;
		setTitleKey(template.template.key);

		// Create the parameter editor layout.

		parameterEditors = new ArrayList<ParameterEditor>();
		JComponent paramEdit = ParameterEditor.createEditorLayout(this, errorReporter, template.parameters,
			parameterEditors);

		if (!template.template.isLocked) {
			for (ParameterEditData theParameter : template.parameters) {
				if (theParameter.parameter.defaultsApplied) {
					hasNewParameters = true;
				}
			}
		}

		JPanel parameterEditPanel = new JPanel(new BorderLayout());
		if (hasNewParameters) {
			parameterEditPanel.setBorder(BorderFactory.createTitledBorder("Parameters (new in red)"));
		} else {
			parameterEditPanel.setBorder(BorderFactory.createTitledBorder("Parameters"));
		}
		parameterEditPanel.add(paramEdit, BorderLayout.CENTER);

		// Create the interference rule table and editor dialog.

		ixRuleModel = new IxRuleTableModel();
		ixRuleTable = ixRuleModel.createTable(editMenu);

		ixRuleTable.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent e) {
				if (2 == e.getClickCount()) {
					doOpenIxRule();
				}
			}
		});

		ixRuleTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
			public void valueChanged(ListSelectionEvent theEvent) {
				updateRuleControls();
			}
		});

		JPanel ixRulePanel = new JPanel(new BorderLayout());
		ixRulePanel.setBorder(BorderFactory.createTitledBorder("Interference Rules"));
		ixRulePanel.add(AppController.createScrollPane(ixRuleTable), BorderLayout.CENTER);
		ixRulePanel.add(ixRuleModel.filterPanel, BorderLayout.SOUTH);

		ixRuleEditors = new HashMap<Integer, IxRuleEditor>();

		// Create action buttons.

		JButton saveButton = new JButton("Save Template");
		saveButton.setFocusable(false);
		if (!template.template.isLocked) {
			saveButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent e) {
					saveIfNeeded("Save Template", false);
				}
			});
		} else {
			saveButton.setEnabled(false);
		}

		JButton saveCloseButton = new JButton("Save & Close");
		saveCloseButton.setFocusable(false);
		if (!template.template.isLocked) {
			saveCloseButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent e) {
					if (saveIfNeeded("Save Template", false) && windowShouldClose()) {
						AppController.hideWindow(outerThis);
					}
				}
			});
		} else {
			saveCloseButton.setEnabled(false);
		}

		JButton newIxRuleButton = new JButton("New");
		newIxRuleButton.setFocusable(false);
		newIxRuleButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				doNewIxRule();
			}
		});

		if (!template.template.isLocked) {
			editIxRuleButton = new JButton("Edit");
		} else {
			editIxRuleButton = new JButton("View");
		}
		editIxRuleButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				doOpenIxRule();
			}
		});

		// Do the layout.

		JPanel ruleLeftButPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		ruleLeftButPanel.add(newIxRuleButton);

		JPanel ruleRightButPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		ruleRightButPanel.add(editIxRuleButton);

		JPanel ruleButtonPanel = new JPanel();
		ruleButtonPanel.setLayout(new BoxLayout(ruleButtonPanel, BoxLayout.X_AXIS));
		ruleButtonPanel.add(ruleLeftButPanel);
		ruleButtonPanel.add(ruleRightButPanel);

		JPanel ruleEditPanel = new JPanel(new BorderLayout());
		ruleEditPanel.add(ixRulePanel, BorderLayout.CENTER);
		ruleEditPanel.add(ruleButtonPanel, BorderLayout.SOUTH);

		editorTabPane = new JTabbedPane();
		editorTabPane.addTab("Parameters", parameterEditPanel);
		editorTabPane.addTab("Rules", ruleEditPanel);

		editorTabPane.setSelectedIndex(0);

		editorTabPane.getModel().addChangeListener(new ChangeListener() {
			public void stateChanged(ChangeEvent theEvent) {
				updateFileMenu();
			}
		});

		JPanel saveButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		saveButtonPanel.add(saveButton);
		saveButtonPanel.add(saveCloseButton);

		Container cp = getContentPane();
		cp.setLayout(new BorderLayout());
		cp.add(editorTabPane, BorderLayout.CENTER);
		cp.add(saveButtonPanel, BorderLayout.SOUTH);

		pack();

		Dimension theSize = new Dimension(980, 650);
		setMinimumSize(theSize);
		setSize(theSize);

		// Do an initial update of UI state.

		updateFileMenu();
		updateDocumentName();
	}


	//=================================================================================================================
	// Table model for the interference rule table.  In-table editing is not supported.

	private class IxRuleTableModel extends AbstractTableModel implements TableFilterModel {

		private static final String IXRULE_COUNTRY_COLUMN = "Country D";
		private static final String IXRULE_SERVICE_COLUMN = "Service D";
		private static final String IXRULE_BAND_COLUMN = "Band D";
		private static final String IXRULE_USERVICE_COLUMN = "Service U";
		private static final String IXRULE_CHANNEL_COLUMN = "Channel U";
		private static final String IXRULE_MASK_COLUMN = "Mask U";
		private static final String IXRULE_OFFSET_COLUMN = "Offset";
		private static final String IXRULE_DISTANCE_COLUMN = "Distance";
		private static final String IXRULE_DU_COLUMN = "D/U";
		private static final String IXRULE_UTIME_COLUMN = "% Time U";

		private String[] columnNames = {
			IXRULE_COUNTRY_COLUMN,
			IXRULE_SERVICE_COLUMN,
			IXRULE_BAND_COLUMN,
			IXRULE_USERVICE_COLUMN,
			IXRULE_CHANNEL_COLUMN,
			IXRULE_MASK_COLUMN,
			IXRULE_OFFSET_COLUMN,
			IXRULE_DISTANCE_COLUMN,
			IXRULE_DU_COLUMN,
			IXRULE_UTIME_COLUMN
		};

		private static final int IXRULE_COUNTRY_INDEX = 0;
		private static final int IXRULE_SERVICE_INDEX = 1;
		private static final int IXRULE_BAND_INDEX = 2;
		private static final int IXRULE_USERVICE_INDEX = 3;
		private static final int IXRULE_CHANNEL_INDEX = 4;
		private static final int IXRULE_MASK_INDEX = 5;
		private static final int IXRULE_OFFSET_INDEX = 6;
		private static final int IXRULE_DISTANCE_INDEX = 7;
		private static final int IXRULE_DU_INDEX = 8;
		private static final int IXRULE_UTIME_INDEX = 9;

		private NumberFormat doubleFormatter;

		private TableFilterPanel filterPanel;


		//-------------------------------------------------------------------------------------------------------------

		private IxRuleTableModel() {

			super();

			doubleFormatter = NumberFormat.getInstance(Locale.US);
			doubleFormatter.setMinimumFractionDigits(2);
			doubleFormatter.setMaximumFractionDigits(2);
			doubleFormatter.setMinimumIntegerDigits(1);

			filterPanel = new TableFilterPanel(outerThis, this);
		}


		//-------------------------------------------------------------------------------------------------------------
		// Create and configure a JTable to present the model.  Set appropriate column widths.  Also this gets a custom
		// renderer so inactive rules have a different appearance, and some columns are right-aligned.

		private JTable createTable(EditMenu theEditMenu) {

			JTable theTable = new JTable(this);
			AppController.configureTable(theTable, theEditMenu);
			theTable.setAutoCreateRowSorter(true);

			DefaultTableCellRenderer theRend = new DefaultTableCellRenderer() {
				public Component getTableCellRendererComponent(JTable t, Object o, boolean s, boolean f, int r, int c) {

					JLabel comp = (JLabel)super.getTableCellRendererComponent(t, o, s, f, r, c);

					if (!s) {
						if (get(t.convertRowIndexToModel(r)).isActive) {
							comp.setForeground(Color.BLACK);
						} else {
							comp.setForeground(Color.GRAY.brighter());
						}
					}

					if ((IXRULE_DISTANCE_INDEX == c) || (IXRULE_DU_INDEX == c) || (IXRULE_UTIME_INDEX == c)) {
						comp.setHorizontalAlignment(SwingConstants.RIGHT);
					} else {
						comp.setHorizontalAlignment(SwingConstants.LEFT);
					}

					return comp;
				}
			};

			DefaultTableCellRenderer rHeadRend = (DefaultTableCellRenderer)((new JTableHeader()).getDefaultRenderer());
			rHeadRend.setHorizontalAlignment(SwingConstants.RIGHT);

			TableColumn theColumn = theTable.getColumn(IXRULE_COUNTRY_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[8]);

			theColumn = theTable.getColumn(IXRULE_SERVICE_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[8]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[15]);

			theColumn = theTable.getColumn(IXRULE_BAND_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[5]);

			theColumn = theTable.getColumn(IXRULE_USERVICE_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[8]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[15]);

			theColumn = theTable.getColumn(IXRULE_CHANNEL_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[10]);

			theColumn = theTable.getColumn(IXRULE_MASK_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[10]);

			theColumn = theTable.getColumn(IXRULE_OFFSET_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[8]);

			theColumn = theTable.getColumn(IXRULE_DISTANCE_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setHeaderRenderer(rHeadRend);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[8]);

			theColumn = theTable.getColumn(IXRULE_DU_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setHeaderRenderer(rHeadRend);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[8]);

			theColumn = theTable.getColumn(IXRULE_UTIME_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setHeaderRenderer(rHeadRend);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[5]);

			return theTable;
		}


		//-------------------------------------------------------------------------------------------------------------
		// Methods that change rows are just wrappers around IxRuleListData methods, but also call postChange().

		private int addOrReplace(IxRuleEditData theRule) {

			postChange(template.ixRuleData.addOrReplace(theRule));

			int modelIndex = template.ixRuleData.getLastRowChanged();
			if (modelIndex >= 0) {
				return filterPanel.reverseIndex[modelIndex];
			}
			return modelIndex;
		}


		//-------------------------------------------------------------------------------------------------------------

		private IxRuleEditData get(int rowIndex) {

			return template.ixRuleData.get(filterPanel.forwardIndex[rowIndex]);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void remove(int rowIndex) {

			postChange(template.ixRuleData.remove(filterPanel.forwardIndex[rowIndex]));
		}


		//-------------------------------------------------------------------------------------------------------------

		private void remove(int[] rows) {

			int[] modRows = new int[rows.length];
			for (int i = 0; i < rows.length; i++) {
				modRows[i] = filterPanel.forwardIndex[rows[i]];
			}
			postChange(template.ixRuleData.remove(modRows));
		}


		//-------------------------------------------------------------------------------------------------------------

		private void setActive(int rowIndex, boolean theFlag) {

			postChange(template.ixRuleData.setActive(filterPanel.forwardIndex[rowIndex], theFlag));
		}


		//-------------------------------------------------------------------------------------------------------------
		// Called after table data may have changed, fire appropriate change events.  Due to the filter the change may
		// not be visible at all to the filtered model, also an update may become an insert or delete if the change
		// caused the row to enter or leave the filtered model.

		private void postChange(boolean didChange) {

			if (didChange) {

				int modelIndex = template.ixRuleData.getLastRowChanged();
				int rowBefore = -1, rowAfter = -1;
				if ((modelIndex >= 0) && (modelIndex < filterPanel.reverseIndex.length)) {
					rowBefore = filterPanel.reverseIndex[modelIndex];
				}
				filterPanel.updateFilter();
				if ((modelIndex >= 0) && (modelIndex < filterPanel.reverseIndex.length)) {
					rowAfter = filterPanel.reverseIndex[modelIndex];
				}

				switch (template.ixRuleData.getLastChange()) {

					case ListDataChange.NO_CHANGE:
					default:
						break;

					case ListDataChange.ALL_CHANGE:
						fireTableDataChanged();
						break;

					case ListDataChange.INSERT:
						if (rowAfter >= 0) {
							fireTableRowsInserted(rowAfter, rowAfter);
						}
						break;

					case ListDataChange.UPDATE:
						if (rowBefore >= 0) {
							if (rowAfter >= 0) {
								fireTableRowsUpdated(rowBefore, rowAfter);
							} else {
								fireTableRowsDeleted(rowBefore, rowBefore);
							}
						} else {
							if (rowAfter >= 0) {
								fireTableRowsInserted(rowAfter, rowAfter);
							}
						}
						break;

					case ListDataChange.DELETE:
						if (rowBefore >= 0) {
							fireTableRowsDeleted(rowBefore, rowBefore);
						}
						break;
				}
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		private void dataWasChanged() {

			filterPanel.updateFilter();
			fireTableDataChanged();
		}


		//-------------------------------------------------------------------------------------------------------------

		public void filterDidChange() {

			fireTableDataChanged();
		}


		//-------------------------------------------------------------------------------------------------------------
		// Table model method implementations.

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
				case IXRULE_COUNTRY_INDEX:
				case IXRULE_SERVICE_INDEX:
				case IXRULE_BAND_INDEX:
				case IXRULE_USERVICE_INDEX:
				case IXRULE_CHANNEL_INDEX: {
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

			return template.ixRuleData.getRowCount();
		}


		//-------------------------------------------------------------------------------------------------------------

		public Object getValueAt(int rowIndex, int columnIndex) {

			return getUnfilteredValueAt(filterPanel.forwardIndex[rowIndex], columnIndex);
		}


		//-------------------------------------------------------------------------------------------------------------

		public String getUnfilteredValueAt(int rowIndex, int columnIndex) {

			IxRuleEditData theRule = template.ixRuleData.get(rowIndex);

			switch (columnIndex) {

				case IXRULE_COUNTRY_INDEX:
					return theRule.country.name;

				case IXRULE_SERVICE_INDEX:
					return theRule.serviceType.name;

				case IXRULE_BAND_INDEX:
					return theRule.channelBand.name;

				case IXRULE_USERVICE_INDEX:
					return theRule.undesiredServiceType.name;

				case IXRULE_CHANNEL_INDEX:
					return theRule.channelDelta.name;

				case IXRULE_MASK_INDEX:
					return theRule.emissionMask.name;

				case IXRULE_OFFSET_INDEX:
					switch (theRule.frequencyOffset) {

						case 0:
						default:
							return "(any)";

						case IxRule.FREQUENCY_OFFSET_WITHOUT:
							return "Without";

						case IxRule.FREQUENCY_OFFSET_WITH:
							return "With";
					}

				case IXRULE_DISTANCE_INDEX:
					return doubleFormatter.format(theRule.distance);

				case IXRULE_DU_INDEX:
					return doubleFormatter.format(theRule.requiredDU);

				case IXRULE_UTIME_INDEX:
					return String.valueOf(theRule.undesiredTime);
			}

			return "";
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create the file menu according to the selected tab in the layout.  Do nothing if selection has not changed.

	private void updateFileMenu() {

		int selectedTab = editorTabPane.getSelectedIndex();
		if (selectedTab == lastSelectedTab) {
			return;
		}
		lastSelectedTab = selectedTab;

		fileMenu.removeAll();

		switch (selectedTab) {

			case 0: {

				getRootPane().setDefaultButton(null);

				fileMenu.setText("Parameter");

				break;
			}

			case 1: {

				getRootPane().setDefaultButton(editIxRuleButton);

				fileMenu.setText("Rule");

				// Previous

				JMenuItem miPrevious = new JMenuItem("Previous");
				miPrevious.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_UP, AppController.MENU_SHORTCUT_KEY_MASK));
				miPrevious.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doPreviousRule();
					}
				});
				fileMenu.add(miPrevious);

				// Next

				JMenuItem miNext = new JMenuItem("Next");
				miNext.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, AppController.MENU_SHORTCUT_KEY_MASK));
				miNext.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doNextRule();
					}
				});
				fileMenu.add(miNext);

				// __________________________________

				fileMenu.addSeparator();

				// New...

				JMenuItem miNewRule = new JMenuItem("New...");
				miNewRule.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, AppController.MENU_SHORTCUT_KEY_MASK));
				if (!template.template.isLocked) {
					miNewRule.addActionListener(new ActionListener() {
						public void actionPerformed(ActionEvent theEvent) {
							doNewIxRule();
						}
					});
				} else {
					miNewRule.setEnabled(false);
				}
				fileMenu.add(miNewRule);

				// __________________________________

				fileMenu.addSeparator();

				// Edit/View

				if (!template.template.isLocked) {
					editIxRuleMenuItem = new JMenuItem("Edit");
				} else {
					editIxRuleMenuItem = new JMenuItem("View");
				}
				editIxRuleMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O,
					AppController.MENU_SHORTCUT_KEY_MASK));
				editIxRuleMenuItem.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doOpenIxRule();
					}
				});
				fileMenu.add(editIxRuleMenuItem);

				// Delete

				deleteIxRuleMenuItem = new JMenuItem("Delete");
				if (!template.template.isLocked) {
					deleteIxRuleMenuItem.addActionListener(new ActionListener() {
						public void actionPerformed(ActionEvent theEvent) {
							doDeleteIxRule();
						}
					});
				} else {
					deleteIxRuleMenuItem.setEnabled(false);
				}
				fileMenu.add(deleteIxRuleMenuItem);

				// __________________________________

				fileMenu.addSeparator();

				updateRuleControls();

				break;
			}
		}

		// Save Template

		JMenuItem miSave = new JMenuItem("Save Template");
		miSave.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, AppController.MENU_SHORTCUT_KEY_MASK));
		if (!template.template.isLocked) {
			miSave.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					saveIfNeeded("Save Template", false);
				}
			});
		} else {
			miSave.setEnabled(false);
		}
		fileMenu.add(miSave);

		// Save And Lock

		JMenuItem miSaveLock = new JMenuItem("Save And Lock");
		if (!template.template.isLocked) {
			miSaveLock.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doSaveAndLock();
				}
			});
		} else {
			miSaveLock.setEnabled(false);
		}
		fileMenu.add(miSaveLock);
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void updateRuleControls() {

		int rowCount = ixRuleTable.getSelectedRowCount();
		boolean eEdit = false, eDelete = false;

		if (1 == rowCount) {
			eEdit = true;
		}
		if (rowCount > 0) {
			eDelete = !template.template.isLocked;
		}

		editIxRuleButton.setEnabled(eEdit);
		editIxRuleMenuItem.setEnabled(eEdit);
		deleteIxRuleMenuItem.setEnabled(eDelete);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getDbID() {

		return template.dbID;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public RootEditor getRootEditor() {

		return this;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void updateDocumentName() {

		setDocumentName(DbCore.getHostDbName(getDbID()) + "/" + template.name);

		for (IxRuleEditor theEditor : ixRuleEditors.values()) {
			theEditor.updateDocumentName();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int getTemplateKey() {

		return template.template.key;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check validity in the model and editor state, see comments in the superclass.

	protected boolean isDataValid(String title) {

		if (!super.isDataValid(title)) {
			return false;
		}

		if (!commitCurrentField()) {
			return false;
		}

		errorReporter.setTitle(title);

		return template.isDataValid(errorReporter);
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected boolean isDataChanged() {

		dataChanged = template.isDataChanged();

		return dataChanged;
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected boolean save(String title) {

		if (!super.save(title)) {
			dataChanged = false;
			return false;
		}

		if (!dataChanged) {
			return false;
		}
		dataChanged = false;

		errorReporter.setTitle(title);

		BackgroundWorker<Boolean> theWorker = new BackgroundWorker<Boolean>(this, title) {
			protected Boolean doBackgroundWork(ErrorLogger errors) {
				return Boolean.valueOf(template.save(errors));
			}
		};

		errorReporter.clearMessages();

		Boolean result = theWorker.runWork("Saving template, please wait...", errorReporter);
		boolean ok = false;
		if (null != result) {
			ok = result.booleanValue();
		}

		if (ok) {
			
			updateDocumentName();

			parent.applyEditsFrom(this);

			errorReporter.showMessages();

			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean saveIfNeeded(String title, boolean confirm) {

		String errmsg = null;

		DbConnection db = DbCore.connectDb(getDbID());
		if (null != db) {
			try {

				db.query("SELECT template_key FROM template WHERE template_key = " + template.template.key);
				if (!db.next()) {
					errmsg = "The template does not exist.";
				}

			} catch (SQLException se) {
				errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
				db.reportError(se);
			}

			DbCore.releaseDb(db);

		} else {
			errmsg = "A connection to the database server cannot be established.";
		}

		if (null != errmsg) {
			AppController.beep();
			if (JOptionPane.YES_OPTION == JOptionPane.showConfirmDialog(this, errmsg +
					"\n\nThe template cannot be saved.  You may keep the window\n" +
					"open to view data.  Do you want to keep the window open?", title,
					JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE)) {
				return false;
			}
			return true;
		}

		return super.saveIfNeeded(title, confirm);
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doPreviousRule() {

		int rowCount = ixRuleTable.getRowCount();
		int rowIndex = ixRuleTable.getSelectedRow();
		if ((rowCount > 0) && (rowIndex != 0)) {
			if (rowIndex < 0) {
				rowIndex = rowCount - 1;
			} else {
				rowIndex--;
			}
			ixRuleTable.setRowSelectionInterval(rowIndex, rowIndex);
			ixRuleTable.scrollRectToVisible(ixRuleTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doNextRule() {

		int rowCount = ixRuleTable.getRowCount();
		int rowIndex = ixRuleTable.getSelectedRow();
		if ((rowCount > 0) && (rowIndex < (rowCount - 1))) {
			if (rowIndex < 0) {
				rowIndex = 0;
			} else {
				rowIndex++;
			}
			ixRuleTable.setRowSelectionInterval(rowIndex, rowIndex);
			ixRuleTable.scrollRectToVisible(ixRuleTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doNewIxRule() {

		if (template.template.isLocked) {
			return;
		}

		Integer theKey = template.getNewIxRuleKey();
		IxRuleEditor theEditor = new IxRuleEditor(this, 0, new IxRuleEditData(theKey));
		AppController.showWindow(theEditor);
		ixRuleEditors.put(theKey, theEditor);
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doOpenIxRule() {

		if (ixRuleTable.getSelectedRowCount() != 1) {
			return;
		}

		int rowIndex = ixRuleTable.convertRowIndexToModel(ixRuleTable.getSelectedRow());
		IxRuleEditData theRule = ixRuleModel.get(rowIndex);

		IxRuleEditor theEditor = ixRuleEditors.get(theRule.key);
		if (null != theEditor) {
			if (theEditor.isVisible()) {
				theEditor.toFront();
				return;
			}
			ixRuleEditors.remove(theRule.key);
		}

		theEditor = new IxRuleEditor(this, 0, theRule);
		AppController.showWindow(theEditor);
		ixRuleEditors.put(theRule.key, theEditor);
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doDeleteIxRule() {

		if (template.template.isLocked) {
			return;
		}

		if (0 == ixRuleTable.getSelectedRowCount()) {
			return;
		}

		int[] selRows = ixRuleTable.getSelectedRows();
		int[] rows = new int[selRows.length];
		IxRuleEditData theRule;
		IxRuleEditor theEditor;

		for (int i = 0; i < selRows.length; i++) {

			rows[i] = ixRuleTable.convertRowIndexToModel(selRows[i]);

			theRule = ixRuleModel.get(rows[i]);
			theEditor = ixRuleEditors.get(theRule.key);
			if (null != theEditor) {
				if (theEditor.isVisible() && !theEditor.cancel()) {
					AppController.beep();
					theEditor.toFront();
					return;
				}
				ixRuleEditors.remove(theRule.key);
			}
		}

		ixRuleModel.remove(rows);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save the template and set the locked flag.  If the template is not in use, may also set the lockedInStudy flag.
	// This will close the editor if successful so all open rule editors must be closed manually first.

	private void doSaveAndLock() {

		if (template.template.isLocked) {
			return;
		}

		for (IxRuleEditor theEditor : new ArrayList<IxRuleEditor>(ixRuleEditors.values())) {
			if (theEditor.isVisible()) {
				AppController.beep();
				theEditor.toFront();
				return;
			}
			ixRuleEditors.remove(theEditor.getIxRule().key);
		}

		if (!saveIfNeeded("Save Template", false)) {
			return;
		}

		String title = "Lock Template";
		errorReporter.setTitle(title);

		int refCount = 0;

		DbConnection db = DbCore.connectDb(getDbID(), errorReporter);
		if (null != db) {
			try {
				db.query("SELECT COUNT(*) FROM study WHERE template_key = " + template.template.key);
				db.next();
				refCount = db.getInt(1);
				DbCore.releaseDb(db);
			} catch (SQLException se) {
				DbCore.releaseDb(db);
				DbConnection.reportError(errorReporter, se);
				return;
			}
		} else {
			return;
		}

		class PromptDialog extends AppDialog {

			private JRadioButton lockTemplateAndStudyButton;
			private boolean canceled;

			private PromptDialog(String title, int theRefCount) {

				super(outerThis, title, Dialog.ModalityType.APPLICATION_MODAL);

				JRadioButton lockTemplateButton = new JRadioButton("Lock template only", true);

				lockTemplateAndStudyButton = new JRadioButton("Lock template and study settings", false);
				lockTemplateAndStudyButton.setEnabled(0 == theRefCount);

				ButtonGroup theGroup = new ButtonGroup();
				theGroup.add(lockTemplateButton);
				theGroup.add(lockTemplateAndStudyButton);

				JLabel infoLabel = new JLabel(
					"<HTML>Locking a template prevents it from being modified.  Once a template is<BR>" +
					"locked it cannot be unlocked.  Rules and parameters in studies based on<BR>" +
					"the template may also be locked so the study settings cannot be modified.<BR>" +
					"(That option is available only if the template is not currently in use).</HTML>");

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

				JPanel mainPanel = new JPanel();
				mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
				mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 0, 20));
				mainPanel.add(infoLabel);
				mainPanel.add(Box.createVerticalStrut(10));
				mainPanel.add(lockTemplateButton);
				mainPanel.add(lockTemplateAndStudyButton);

				JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
				buttonPanel.add(cancelButton);
				buttonPanel.add(okButton);

				Container cp = getContentPane();
				cp.setLayout(new BorderLayout());
				cp.add(mainPanel, BorderLayout.CENTER);
				cp.add(buttonPanel, BorderLayout.SOUTH);

				pack();

				setLocationRelativeTo(outerThis);
			}

			private void doOK() {
				AppController.hideWindow(this);
			}

			private void doCancel() {
				canceled = true;
				AppController.hideWindow(this);
			}
		}

		PromptDialog promptDialog = new PromptDialog(title, refCount);
		AppController.beep();
		AppController.showWindow(promptDialog);
		if (promptDialog.canceled) {
			return;
		}

		db = DbCore.connectDb(getDbID(), errorReporter);
		if (null == db) {
			return;
		}

		boolean doStudyLock = promptDialog.lockTemplateAndStudyButton.isSelected();

		boolean error = false;
		String errmsg = null;

		try {

			db.update("LOCK TABLES template WRITE, study WRITE");

			if (doStudyLock) {

				db.query("SELECT COUNT(*) FROM study WHERE template_key = " + template.template.key);
				db.next();
				refCount = db.getInt(1);

				if (0 == refCount) {

					db.update("UPDATE template SET locked = true, locked_in_study = true WHERE template_key = " +
						template.template.key);

				} else {
					error = true;
					errmsg = "The template is in use, it cannot be study-locked.";
				}

			} else {
				db.update("UPDATE template SET locked = true WHERE template_key = " + template.template.key);
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
			errorReporter.reportError(errmsg);
			return;
		}

		parent.applyEditsFrom(this);

		DbController.saveColumnWidths(getDbID(), getKeyTitle() + ".Rules", ixRuleTable);

		blockActionsSet();
		parent.editorClosing(this);
		AppController.hideWindow(this);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by rule editors when edits are applied.  Check first for rule uniqueness in the model.

	public boolean applyEditsFrom(AppEditor theEditor) {

		if (theEditor instanceof IxRuleEditor) {

			if (template.template.isLocked) {
				return false;
			}

			IxRuleEditor ruleEditor = (IxRuleEditor)theEditor;
			IxRuleEditData theRule = ruleEditor.getIxRule();
			if (ruleEditor != ixRuleEditors.get(theRule.key)) {
				return false;
			}

			if (template.ixRuleData.isIxRuleUnique(theRule, ruleEditor.getErrorReporter())) {

				int rowIndex = ixRuleModel.addOrReplace(theRule);

				if (rowIndex >= 0) {
					rowIndex = ixRuleTable.convertRowIndexToView(rowIndex);
					ixRuleTable.setRowSelectionInterval(rowIndex, rowIndex);
					ixRuleTable.scrollRectToVisible(ixRuleTable.getCellRect(rowIndex, 0, true));
				}

				return true;
			}

			return false;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by rule editors when closing.

	public void editorClosing(AppEditor theEditor) {

		if (theEditor instanceof IxRuleEditor) {
			ixRuleEditors.remove(((IxRuleEditor)theEditor).getIxRule().key, (IxRuleEditor)theEditor);
			return;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This will try to close the editor without saving changes or prompting the user; but it might fail if there are
	// dialogs showing that don't want to close.

	public boolean closeWithoutSave() {

		for (IxRuleEditor theEditor : new ArrayList<IxRuleEditor>(ixRuleEditors.values())) {
			if (theEditor.isVisible() && !theEditor.cancel()) {
				AppController.beep();
				theEditor.toFront();
				return false;
			}
			ixRuleEditors.remove(theEditor.getIxRule().key);
		}

		blockActionsSet();
		parent.editorClosing(this);
		AppController.hideWindow(this);

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Show a message if there are new template parameters.

	public void windowWillOpen() {

		DbController.restoreColumnWidths(getDbID(), getKeyTitle() + ".Rules", ixRuleTable);

		blockActionsClear();

		if (hasNewParameters) {
			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					errorReporter.setTitle("New Parameters");
					AppController.beep();
					errorReporter.reportMessage("New parameters have been added to the template.");
				}
			});
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean windowShouldClose() {

		boolean rulesLocked = template.template.isLocked;
		for (IxRuleEditor theEditor : new ArrayList<IxRuleEditor>(ixRuleEditors.values())) {
			if (theEditor.isVisible() && (!rulesLocked || !theEditor.cancel())) {
				AppController.beep();
				theEditor.toFront();
				return false;
			}
			ixRuleEditors.remove(theEditor.getIxRule().key);
		}

		if (!saveIfNeeded("Close Template", true)) {
			toFront();
			return false;
		}

		DbController.saveColumnWidths(getDbID(), getKeyTitle() + ".Rules", ixRuleTable);

		blockActionsSet();
		parent.editorClosing(this);

		return true;
	}
}
