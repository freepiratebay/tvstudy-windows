//
//  GeoEditPanel.java
//  TVStudy
//
//  Copyright (c) 2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui.editor;

import gov.fcc.tvstudy.gui.*;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.geo.*;

import java.util.*;
import java.awt.*;
import java.awt.event.*;
import java.text.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.table.*;
import javax.swing.border.*;


//=====================================================================================================================
// Superclass for editing UIs for geography types.

public abstract class GeoEditPanel extends AppPanel {

	protected boolean canEdit;


	//-----------------------------------------------------------------------------------------------------------------

	protected GeoEditPanel(AppEditor theParent, boolean theEditFlag) {

		super(theParent);

		canEdit = theEditFlag;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Indicates all properties of the geography object may have changed.  Subclass may override.

	public void dataChanged() {
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Used by subclasses with table editors, stop cell editing in the table, return false on validation error.

	public boolean commitTableEdits() {
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Method to move selection and edit focus around in a table, used with editable tables in point set and polygon
	// editor panels.  The traverse boolean determines action when changing column runs off the end of a row.  If false
	// selection cycles around in the same row, if true selection shifts to an adjacent row.  If the row runs off the
	// top or bottom of the table when traversing, focus is transferred away from the table.  Changing row in the same
	// column always cycles, it never traverses.

	protected void moveTableSelection(JTable table, int rowDel, int colDel, boolean traverse) {

		boolean backward = (rowDel < 0) || (colDel < 0);

		int rowCount = table.getRowCount();
		int colCount = table.getColumnCount();

		// If the table is empty, transfer focus away.

		if (0 == rowCount) {
			if (backward) {
				table.transferFocusBackward();
			} else {
				table.transferFocus();
			}
			return;
		}

		// Determine current cell being edited or selected, or default.  If traverse is false, the default is (0, 0).
		// If traverse is true, if moving forward default to (0, 0), if backward, (rowCount - 1, colCount - 1).
		// In any case, the default is the cell location _after_ the deltas are applied.

		int row = table.getEditingRow();
		if (row < 0) {
			row = table.getSelectedRow();
		}
		int col = table.getEditingColumn();
		if (col < 0) {
			col = table.getSelectedColumn();
		}
		if ((row < 0) || (col < 0)) {
			if (traverse && backward) {
				row = rowCount - 1 - rowDel;
				col = colCount - 1 - colDel;
			} else {
				row = -rowDel;
				col = -colDel;
			}
		}

		// If a cell is active, stop or cancel editing.

		if (table.isEditing()) {
			if (canEdit) {
				table.getCellEditor().stopCellEditing();
			} else {
				table.getCellEditor().cancelCellEditing();
			}
		}

		// Do the move.  If traverse is true, when the row moves off the top or bottom, transfer focus away.

		row += rowDel;
		col += colDel;

		if (row < 0) {
			if (traverse) {
				table.transferFocusBackward();
				return;
			}
			row = rowCount - 1;
		}
		if (row >= rowCount) {
			if (traverse) {
				table.transferFocus();
				return;
			}
			row = 0;
		}
		if (col < 0) {
			if (traverse) {
				row--;
				if (row < 0) {
					table.transferFocusBackward();
					return;
				}
			}
			col = colCount - 1;
		}
		if (col >= colCount) {
			if (traverse) {
				row++;
				if (row >= rowCount) {
					table.transferFocus();
					return;
				}
			}
			col = 0;
		}

		// Move the selection and re-focus.

		table.changeSelection(row, col, false, false);

		// Automatically start editing if allowed.

		if (canEdit) {
			table.editCellAt(row, col);
			Component c = table.getEditorComponent();
			if (null != c) {
				c.requestFocusInWindow();
			}
		}
	}


	//=================================================================================================================

	public static class PointSetPanel extends GeoEditPanel {

		private GeoPointSet pointSet;

		private KeyedRecordMenu antennaMenu;

		private StudyPointsTableModel pointsModel;
		private JTable pointsTable;

		private JButton duplicateButton;
		private JButton deleteButton;


		//-------------------------------------------------------------------------------------------------------------

		public PointSetPanel(AppEditor theParent, Geography newGeo, boolean theEditFlag) {

			super(theParent, theEditFlag);

			pointSet = (GeoPointSet)newGeo;

			antennaMenu = new KeyedRecordMenu();

			pointsModel = new StudyPointsTableModel();
			pointsTable = pointsModel.createTable();

			// Modify the keyboard handling behavior of the table, so that arrow keys and tab move the selection and
			// edit focus around in the table.  The goal is when focus is on the table some cell is always editing.
			// First set up actions for moving around in the table, then map them to keys.  See moveTableSelection().

			String moveUp = "moveUp";
			String moveDown = "moveDown";
			String moveLeft = "moveLeft";
			String moveRight = "moveRight";
			String tabForward = "tabForward";
			String tabBackward = "tabBackward";

			ActionMap am = pointsTable.getActionMap();
			am.put(moveUp, new AbstractAction() {
				public void actionPerformed(ActionEvent e) {
					moveTableSelection(pointsTable, -1, 0, false);
				}
			});
			am.put(moveDown, new AbstractAction() {
				public void actionPerformed(ActionEvent e) {
					moveTableSelection(pointsTable, 1, 0, false);
				}
			});
			am.put(moveLeft, new AbstractAction() {
				public void actionPerformed(ActionEvent e) {
					moveTableSelection(pointsTable, 0, -1, false);
				}
			});
			am.put(moveRight, new AbstractAction() {
				public void actionPerformed(ActionEvent e) {
					moveTableSelection(pointsTable, 0, 1, false);
				}
			});
			am.put(tabForward, new AbstractAction() {
				public void actionPerformed(ActionEvent e) {
					moveTableSelection(pointsTable, 0, 1, true);
				}
			});
			am.put(tabBackward, new AbstractAction() {
				public void actionPerformed(ActionEvent e) {
					moveTableSelection(pointsTable, 0, -1, true);
				}
			});

			KeyStroke up = KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0);
			KeyStroke down = KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0);
			KeyStroke left = KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0);
			KeyStroke right = KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0);
			KeyStroke tab = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0);
			KeyStroke stab = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, Event.SHIFT_MASK);

			InputMap im = pointsTable.getInputMap(JComponent.WHEN_FOCUSED);
			im.put(up, moveUp);
			im.put(down, moveDown);
			im.put(left, moveLeft);
			im.put(right, moveRight);
			im.put(tab, tabForward);
			im.put(stab, tabBackward);

			im = pointsTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
			im.put(up, moveUp);
			im.put(down, moveDown);
			im.put(left, moveLeft);
			im.put(right, moveRight);
			im.put(tab, tabForward);
			im.put(stab, tabBackward);

			pointsTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
				public void valueChanged(ListSelectionEvent theEvent) {
					if (!theEvent.getValueIsAdjusting() && canEdit) {
						if (1 == pointsTable.getSelectedRowCount()) {
							duplicateButton.setEnabled(true);
							deleteButton.setEnabled(true);
						} else {
							duplicateButton.setEnabled(false);
							deleteButton.setEnabled(false);
						}
					}
				}
			});

			JPanel buttonPanel = null;

			if (canEdit) {

				JButton addButton = new JButton("Add");
				addButton.setFocusable(false);
				addButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doAddPoint();
					}
				});

				duplicateButton = new JButton("Duplicate");
				duplicateButton.setFocusable(false);
				duplicateButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doDuplicatePoint();
					}
				});
				duplicateButton.setEnabled(false);

				deleteButton = new JButton("Delete");
				deleteButton.setFocusable(false);
				deleteButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doDeletePoint();
					}
				});
				deleteButton.setEnabled(false);

				buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
				buttonPanel.add(addButton);
				buttonPanel.add(duplicateButton);
				buttonPanel.add(deleteButton);
			}

			setLayout(new BorderLayout());
			add(AppController.createScrollPane(pointsTable), BorderLayout.CENTER);
			AppController.setComponentEnabled(pointsTable, canEdit);
			if (null != buttonPanel) {
				add(buttonPanel, BorderLayout.SOUTH);
			}

			setBorder(BorderFactory.createTitledBorder("Study Points (NAD83)"));

			updateReceiveAntennas();
		}


		//-------------------------------------------------------------------------------------------------------------
		// Check if a receive antenna is in use.

		public boolean isReceiveAntennaInUse(int theAntKey) {

			for (GeoPointSet.StudyPoint thePoint : pointSet.points) {
				if ((null != thePoint.antenna) && (theAntKey == thePoint.antenna.key)) {
					return true;
				}
			}
			return false;
		}


		//-------------------------------------------------------------------------------------------------------------
		// Update receive antenna list.

		public void updateReceiveAntennas() {

			ArrayList<KeyedRecord> antennas = AntPattern.getReceiveAntennaList(getDbID(), errorReporter);
			if (null == antennas) {
				return;
			}

			int oldKey = antennaMenu.getSelectedKey();

			antennaMenu.removeAllItems();

			antennaMenu.addItem(GeoPointSet.GENERIC_ANTENNA);
			antennaMenu.addAllItems(antennas);

			if (antennaMenu.containsKey(oldKey)) {
				antennaMenu.setSelectedKey(oldKey);
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		public void dataChanged() {

			pointsModel.dataChanged();
		}


		//=============================================================================================================

		private class StudyPointsTableModel extends AbstractTableModel {

			private static final String NAME_COLUMN = "Name";
			private static final String NS_COLUMN = "NS";
			private static final String LATD_COLUMN = "Lat D";
			private static final String LATM_COLUMN = "Lat M";
			private static final String LATS_COLUMN = "Lat S";
			private static final String WE_COLUMN = "WE";
			private static final String LOND_COLUMN = "Lon D";
			private static final String LONM_COLUMN = "Lon M";
			private static final String LONS_COLUMN = "Lon S";
			private static final String HEIGHT_COLUMN = "Recv Ht";
			private static final String ANT_COLUMN = "Recv Ant";
			private static final String USE_ANT_ORIENT_COLUMN = "Fix Dir";
			private static final String ANT_ORIENT_COLUMN = "Dir";

			private String[] columnNames = {
				NAME_COLUMN,
				NS_COLUMN,
				LATD_COLUMN,
				LATM_COLUMN,
				LATS_COLUMN,
				WE_COLUMN,
				LOND_COLUMN,
				LONM_COLUMN,
				LONS_COLUMN,
				HEIGHT_COLUMN,
				ANT_COLUMN,
				USE_ANT_ORIENT_COLUMN,
				ANT_ORIENT_COLUMN
			};

			private static final int NAME_INDEX = 0;
			private static final int NS_INDEX = 1;
			private static final int LATD_INDEX = 2;
			private static final int LATM_INDEX = 3;
			private static final int LATS_INDEX = 4;
			private static final int WE_INDEX = 5;
			private static final int LOND_INDEX = 6;
			private static final int LONM_INDEX = 7;
			private static final int LONS_INDEX = 8;
			private static final int HEIGHT_INDEX = 9;
			private static final int ANT_INDEX = 10;
			private static final int USE_ANT_ORIENT_INDEX = 11;
			private static final int ANT_ORIENT_INDEX = 12;


			//---------------------------------------------------------------------------------------------------------
			// Create the table.  To support the altered editing behavior (see comments above regarding focus movement)
			// all columns get custom editors with, amoung other things, the edit click count set to one.

			private JTable createTable() {

				JTable theTable = new JTable(this);
				theTable.setColumnSelectionAllowed(false);
				theTable.getTableHeader().setReorderingAllowed(false);
				theTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

				TableColumnModel columnModel = theTable.getColumnModel();

				JTextField nameFld = new JTextField(15);
				AppController.fixKeyBindings(nameFld);
				nameFld.setBorder(new LineBorder(Color.BLACK));

				DefaultCellEditor nameEd = new DefaultCellEditor(nameFld);
				nameEd.setClickCountToStart(1);

				TableColumn theColumn = columnModel.getColumn(NAME_INDEX);
				theColumn.setCellEditor(nameEd);
				theColumn.setMinWidth(AppController.textFieldWidth[6]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[15]);

				KeyedRecordMenu nsMenu = new KeyedRecordMenu();
				nsMenu.addItem(new KeyedRecord(0, "N"));
				nsMenu.addItem(new KeyedRecord(1, "S"));
				DefaultCellEditor nsEd = new DefaultCellEditor(nsMenu);

				theColumn = columnModel.getColumn(NS_INDEX);
				theColumn.setCellEditor(nsEd);
				theColumn.setMinWidth(AppController.textFieldWidth[2]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[3]);

				JTextField numFld = new JTextField(8);
				AppController.fixKeyBindings(numFld);
				numFld.setBorder(new LineBorder(Color.BLACK));

				DefaultCellEditor numEd = new DefaultCellEditor(numFld);
				numEd.setClickCountToStart(1);

				theColumn = columnModel.getColumn(LATD_INDEX);
				theColumn.setCellEditor(numEd);
				theColumn.setMinWidth(AppController.textFieldWidth[3]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[6]);

				theColumn = columnModel.getColumn(LATM_INDEX);
				theColumn.setCellEditor(numEd);
				theColumn.setMinWidth(AppController.textFieldWidth[3]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[6]);

				theColumn = columnModel.getColumn(LATS_INDEX);
				theColumn.setCellEditor(numEd);
				theColumn.setMinWidth(AppController.textFieldWidth[3]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[7]);

				KeyedRecordMenu weMenu = new KeyedRecordMenu();
				weMenu.addItem(new KeyedRecord(0, "W"));
				weMenu.addItem(new KeyedRecord(1, "E"));
				DefaultCellEditor weEd = new DefaultCellEditor(weMenu);

				theColumn = columnModel.getColumn(WE_INDEX);
				theColumn.setCellEditor(weEd);
				theColumn.setMinWidth(AppController.textFieldWidth[2]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[3]);

				theColumn = columnModel.getColumn(LOND_INDEX);
				theColumn.setCellEditor(numEd);
				theColumn.setMinWidth(AppController.textFieldWidth[3]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[6]);

				theColumn = columnModel.getColumn(LONM_INDEX);
				theColumn.setCellEditor(numEd);
				theColumn.setMinWidth(AppController.textFieldWidth[3]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[6]);

				theColumn = columnModel.getColumn(LONS_INDEX);
				theColumn.setCellEditor(numEd);
				theColumn.setMinWidth(AppController.textFieldWidth[3]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[7]);

				theColumn = columnModel.getColumn(HEIGHT_INDEX);
				theColumn.setCellEditor(numEd);
				theColumn.setMinWidth(AppController.textFieldWidth[3]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[7]);

				DefaultCellEditor antEd = new DefaultCellEditor(antennaMenu);
				antEd.setClickCountToStart(1);

				theColumn = columnModel.getColumn(ANT_INDEX);
				theColumn.setCellEditor(antEd);
				theColumn.setMinWidth(AppController.textFieldWidth[5]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[8]);

				theColumn = columnModel.getColumn(USE_ANT_ORIENT_INDEX);
				theColumn.setMinWidth(AppController.textFieldWidth[3]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[3]);

				theColumn = columnModel.getColumn(ANT_ORIENT_INDEX);
				theColumn.setCellEditor(numEd);
				theColumn.setMinWidth(AppController.textFieldWidth[3]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[7]);

				return theTable;
			}


			//---------------------------------------------------------------------------------------------------------

			private int add(GeoPointSet.StudyPoint thePoint) {

				if (!canEdit) {
					return -1;
				}

				int rowIndex = pointSet.points.size();
				pointSet.points.add(thePoint);
				setDidEdit();

				fireTableRowsInserted(rowIndex, rowIndex);

				return rowIndex;
			}


			//---------------------------------------------------------------------------------------------------------

			private void remove(int rowIndex) {

				if (!canEdit) {
					return;
				}

				pointSet.points.remove(rowIndex);
				setDidEdit();

				fireTableRowsDeleted(rowIndex, rowIndex);
			}


			//---------------------------------------------------------------------------------------------------------

			private void dataChanged() {

				fireTableDataChanged();
			}


			//---------------------------------------------------------------------------------------------------------

			public int getColumnCount() {

				return columnNames.length;
			}


			//---------------------------------------------------------------------------------------------------------

			public String getColumnName(int columnIndex) {

				return columnNames[columnIndex];
			}


			//---------------------------------------------------------------------------------------------------------

			public int getRowCount() {

				return pointSet.points.size();
			}


			//---------------------------------------------------------------------------------------------------------

			public Class getColumnClass(int columnIndex) {

				switch (columnIndex) {
					case NS_INDEX:
					case WE_INDEX:
					case ANT_INDEX:
						return KeyedRecord.class;
					case NAME_INDEX:
					case LATD_INDEX:
					case LATM_INDEX:
					case LATS_INDEX:
					case LOND_INDEX:
					case LONM_INDEX:
					case LONS_INDEX:
					case HEIGHT_INDEX:
					case ANT_ORIENT_INDEX:
						return String.class;
					case USE_ANT_ORIENT_INDEX:
						return Boolean.class;
				}

				return Object.class;		
			}


			//---------------------------------------------------------------------------------------------------------

			public boolean isCellEditable(int rowIndex, int columnIndex) {

				if (!canEdit) {
					return false;
				}

				if (ANT_ORIENT_INDEX == columnIndex) {
					GeoPointSet.StudyPoint thePoint = pointSet.points.get(rowIndex);
					return thePoint.useAntennaOrientation;
				}

				return true;
			}


			//---------------------------------------------------------------------------------------------------------

			public Object getValueAt(int rowIndex, int columnIndex) {

				GeoPointSet.StudyPoint thePoint = pointSet.points.get(rowIndex);

				switch (columnIndex) {

					case NAME_INDEX:
						return thePoint.name;

					case NS_INDEX:
						return new KeyedRecord(thePoint.latitudeNS, ((0 == thePoint.latitudeNS) ? "N" : "S"));

					case LATD_INDEX:
						return String.valueOf(thePoint.latitudeDegrees);

					case LATM_INDEX:
						return String.valueOf(thePoint.latitudeMinutes);

					case LATS_INDEX:
						return AppCore.formatSeconds(thePoint.latitudeSeconds);

					case WE_INDEX:
						return new KeyedRecord(thePoint.longitudeWE, ((0 == thePoint.longitudeWE) ? "W" : "E"));

					case LOND_INDEX:
						return String.valueOf(thePoint.longitudeDegrees);

					case LONM_INDEX:
						return String.valueOf(thePoint.longitudeMinutes);

					case LONS_INDEX:
						return AppCore.formatSeconds(thePoint.longitudeSeconds);

					case HEIGHT_INDEX:
						return AppCore.formatHeight(thePoint.receiveHeight);

					case ANT_INDEX:
						return ((null != thePoint.antenna) ? thePoint.antenna : GeoPointSet.GENERIC_ANTENNA);

					case USE_ANT_ORIENT_INDEX:
						return Boolean.valueOf(thePoint.useAntennaOrientation);

					case ANT_ORIENT_INDEX:
						if (thePoint.useAntennaOrientation) {
							return AppCore.formatAzimuth(thePoint.antennaOrientation);
						}
						return "";
				}

				return "";
			}


			//---------------------------------------------------------------------------------------------------------

			public void setValueAt(Object value, int rowIndex, int columnIndex) {

				if (!canEdit || (rowIndex < 0) || (rowIndex >= pointSet.points.size())) {
					return;
				}

				GeoPointSet.StudyPoint thePoint = pointSet.points.get(rowIndex);

				errorReporter.setTitle("Edit Point");

				switch (columnIndex) {

					case NAME_INDEX: {
						String s = (String)value;
						if (!thePoint.name.equals(s)) {
							thePoint.name = s;
							setDidEdit();
						}
						return;
					}

					case NS_INDEX: {
						int ns = ((KeyedRecord)value).key;
						if (ns != thePoint.latitudeNS) {
							thePoint.latitudeNS = ns;
							thePoint.updateLatLon();
							setDidEdit();
						}
						break;
					}

					case WE_INDEX: {
						int we = ((KeyedRecord)value).key;
						if (we != thePoint.longitudeWE) {
							thePoint.longitudeWE = we;
							thePoint.updateLatLon();
							setDidEdit();
						}
						break;
					}

					case LATD_INDEX:
					case LATM_INDEX:
					case LATS_INDEX:
					case LOND_INDEX:
					case LONM_INDEX:
					case LONS_INDEX:
					case HEIGHT_INDEX:
					case ANT_ORIENT_INDEX: {
						setStudyPointField(thePoint, rowIndex, columnIndex, ((String)value).trim());
						return;
					}

					case ANT_INDEX: {
						KeyedRecord newAnt = (KeyedRecord)value;
						if (null == newAnt) {
							newAnt = GeoPointSet.GENERIC_ANTENNA;
						}
						if (null == thePoint.antenna) {
							thePoint.antenna = newAnt;
							setDidEdit();
						} else {
							if (newAnt.key != thePoint.antenna.key) {
								thePoint.antenna = newAnt;
								setDidEdit();
							}
						}
						return;
					}

					case USE_ANT_ORIENT_INDEX: {
						boolean newFlag = ((Boolean)value).booleanValue();
						if (newFlag != thePoint.useAntennaOrientation) {
							thePoint.useAntennaOrientation = newFlag;
							setDidEdit();
							fireTableRowsUpdated(rowIndex, rowIndex);
						}
						return;
					}
				}
			}


			//---------------------------------------------------------------------------------------------------------

			private void setStudyPointField(GeoPointSet.StudyPoint thePoint, int rowIndex, int columnIndex,
					String str) {

				if (!canEdit || (0 == str.length())) {
					return;
				}

				int deg, min, degmax;
				double d, sec, max;
				String lbl = "";

				try {
					d = Double.parseDouble(str);
				} catch (NumberFormatException ne) {
					errorReporter.reportValidationError("Input must be a number.");
					return;
				}

				switch (columnIndex) {

					case HEIGHT_INDEX: {
						if ((d < Geography.MIN_RECEIVE_HEIGHT) || (d > Geography.MAX_RECEIVE_HEIGHT)) {
							errorReporter.reportValidationError("Height must be in the range " +
								Geography.MIN_RECEIVE_HEIGHT + " to " + Geography.MAX_RECEIVE_HEIGHT + ".");
							return;
						}
						if (d != thePoint.receiveHeight) {
							thePoint.receiveHeight = d;
							setDidEdit();
						}
						return;
					}

					case ANT_ORIENT_INDEX: {
						if ((d < AntPattern.AZIMUTH_MIN) || (d > AntPattern.AZIMUTH_MAX)) {
							errorReporter.reportValidationError("Antenna orientation must be in the range " +
								AntPattern.AZIMUTH_MIN + " to " + AntPattern.AZIMUTH_MAX + ".");
							return;
						}
						if (d != thePoint.antennaOrientation) {
							thePoint.antennaOrientation = d;
							setDidEdit();
						}
						return;
					}

					case LATD_INDEX:
					case LATM_INDEX:
					case LATS_INDEX: {
						deg = thePoint.latitudeDegrees;
						min = thePoint.latitudeMinutes;
						sec = thePoint.latitudeSeconds;
						max = Source.LATITUDE_MAX;
						lbl = "Latitude";
						break;
					}

					case LOND_INDEX:
					case LONM_INDEX:
					case LONS_INDEX: {
						deg = thePoint.longitudeDegrees;
						min = thePoint.longitudeMinutes;
						sec = thePoint.longitudeSeconds;
						max = Source.LONGITUDE_MAX;
						lbl = "Longitude";
						break;
					}

					default: {
						return;
					}
				}

				degmax = (int)Math.floor(max);

				switch (columnIndex) {

					case LATD_INDEX:
					case LOND_INDEX: {
						deg = (int)Math.floor(d);
						if ((deg < 0) || (deg > degmax)) {
							errorReporter.reportValidationError(lbl + " must be in the range 0 to " + degmax + ".");
							return;
						}
						d -= (double)deg;
						if (d > (0.005 / 3600.)) {
							d *= 60.;
							min = (int)Math.floor(d);
							d -= (double)min;
							sec = d * 60.;
						}
						break;
					}

					case LATM_INDEX:
					case LONM_INDEX: {
						min = (int)Math.floor(d);
						if ((min < 0) || (min > 59)) {
							errorReporter.reportValidationError("Minutes must be in the range 0 to 59.");
							return;
						}
						d -= (double)min;
						if (d > (0.005 / 60.)) {
							sec = d * 60.;
						}
						break;
					}

					case LATS_INDEX:
					case LONS_INDEX: {
						sec = d;
						if ((sec < 0.) || (sec >= 60.)) {
							errorReporter.reportValidationError("Seconds must be in the range 0 to less than 60.");
							return;
						}
						break;
					}
				}

				if (sec > 59.995) {
					sec = 0.;
					if (++min > 59) {
						min = 0;
						++deg;
					}
				}

				d = (double)deg + ((double)min / 60.) + (sec / 3600.);
				if ((d < 0.) || (d > max)) {
					errorReporter.reportValidationError(lbl + " must be in the range 0 to " + max + ".");
					return;
				}

				boolean didChange = false, didChangeOther = false;

				switch (columnIndex) {

					case LATD_INDEX: {
						if (deg != thePoint.latitudeDegrees) {
							thePoint.latitudeDegrees = deg;
							didChange = true;
						}
						if (min != thePoint.latitudeMinutes) {
							thePoint.latitudeMinutes = min;
							didChange = true;
							didChangeOther = true;
						}
						if (sec != thePoint.latitudeSeconds) {
							thePoint.latitudeSeconds = sec;
							didChange = true;
							didChangeOther = true;
						}
						break;
					}

					case LATM_INDEX: {
						if (min != thePoint.latitudeMinutes) {
							thePoint.latitudeMinutes = min;
							didChange = true;
						}
						if (sec != thePoint.latitudeSeconds) {
							thePoint.latitudeSeconds = sec;
							didChange = true;
							didChangeOther = true;
						}
						break;
					}

					case LATS_INDEX: {
						if (sec != thePoint.latitudeSeconds) {
							thePoint.latitudeSeconds = sec;
							didChange = true;
						}
						break;
					}

					case LOND_INDEX: {
						if (deg != thePoint.longitudeDegrees) {
							thePoint.longitudeDegrees = deg;
							didChange = true;
						}
						if (min != thePoint.longitudeMinutes) {
							thePoint.longitudeMinutes = min;
							didChange = true;
							didChangeOther = true;
						}
						if (sec != thePoint.longitudeSeconds) {
							thePoint.longitudeSeconds = sec;
							didChange = true;
							didChangeOther = true;
						}
						break;
					}

					case LONM_INDEX: {
						if (min != thePoint.longitudeMinutes) {
							thePoint.longitudeMinutes = min;
							didChange = true;
						}
						if (sec != thePoint.longitudeSeconds) {
							thePoint.longitudeSeconds = sec;
							didChange = true;
							didChangeOther = true;
						}
						break;
					}

					case LONS_INDEX: {
						if (sec != thePoint.longitudeSeconds) {
							thePoint.longitudeSeconds = sec;
							didChange = true;
						}
						break;
					}
				}

				if (didChange) {
					thePoint.updateLatLon();
					setDidEdit();
				}
				if (didChangeOther) {
					fireTableRowsUpdated(rowIndex, rowIndex);
				}
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doAddPoint() {

			if (!canEdit || !commitTableEdits()) {
				return;
			}

			GeoPointSet.StudyPoint newPoint = new GeoPointSet.StudyPoint();
			newPoint.name = "";
			newPoint.receiveHeight = 10.;
			newPoint.antenna = GeoPointSet.GENERIC_ANTENNA;
			int rowIndex = pointsModel.add(newPoint);

			pointsTable.scrollRectToVisible(pointsTable.getCellRect(rowIndex, 0, true));
			pointsTable.setRowSelectionInterval(rowIndex, rowIndex);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doDuplicatePoint() {

			if (!canEdit || !commitTableEdits()) {
				return;
			}

			int rowIndex = pointsTable.getSelectedRow();
			if (rowIndex < 0) {
				return;
			}

			GeoPointSet.StudyPoint newPoint = pointSet.points.get(rowIndex).duplicate();
			newPoint.name = "";
			rowIndex = pointsModel.add(newPoint);

			pointsTable.scrollRectToVisible(pointsTable.getCellRect(rowIndex, 0, true));
			pointsTable.setRowSelectionInterval(rowIndex, rowIndex);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doDeletePoint() {

			if (!canEdit || !commitTableEdits()) {
				return;
			}

			int rowIndex = pointsTable.getSelectedRow();
			if (rowIndex < 0) {
				return;
			}

			pointsModel.remove(rowIndex);

			if (rowIndex >= pointsModel.getRowCount()) {
				rowIndex--;
			}

			if ((rowIndex >= 0) && (rowIndex < pointsModel.getRowCount())) {
				pointsTable.scrollRectToVisible(pointsTable.getCellRect(rowIndex, 0, true));
				pointsTable.setRowSelectionInterval(rowIndex, rowIndex);
			}
		}


		//-------------------------------------------------------------------------------------------------------------
		// Stop cell editing in the table, return false on validation error.

		public boolean commitTableEdits() {

			if (pointsTable.isEditing()) {
				if (canEdit) {
					errorReporter.clearErrors();
					pointsTable.getCellEditor().stopCellEditing();
					return !errorReporter.hasErrors();
				} else {
					pointsTable.getCellEditor().cancelCellEditing();
				}
			}
			return true;
		}
	}


	//=================================================================================================================

	public static class BoxPanel extends GeoEditPanel {

		private GeoBox box;

		private CoordinatePanel latitudePanel;
		private CoordinatePanel longitudePanel;
		private JTextField widthField;
		private JTextField heightField;


		//-------------------------------------------------------------------------------------------------------------

		public BoxPanel(AppEditor theParent, Geography newGeo, boolean theEditFlag) {

			super(theParent, theEditFlag);

			blockActionsStart();

			box = (GeoBox)newGeo;

			Runnable callBack = null;
			if (canEdit) {
				callBack = new Runnable() {
					public void run() {
						setDidEdit();
					}
				};
			}

			latitudePanel = new CoordinatePanel(parent, box.center, false, callBack);
			latitudePanel.setEnabled(canEdit);

			longitudePanel = new CoordinatePanel(parent, box.center, true, callBack);
			longitudePanel.setEnabled(canEdit);

			widthField = new JTextField(7);
			AppController.fixKeyBindings(widthField);

			if (canEdit) {

				widthField.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							String title = "Edit Width";
							String str = widthField.getText().trim();
							if (str.length() > 0) {
								double d = box.width;
								try {
									d = Double.parseDouble(str);
								} catch (NumberFormatException ne) {
									errorReporter.reportValidationError(title, "The width must be a number.");
								}
								if (d != box.width) {
									if ((d < Geography.MIN_DISTANCE) || (d > Geography.MAX_DISTANCE)) {
										errorReporter.reportValidationError(title, "The width must be between " +
											Geography.MIN_DISTANCE + " and " + Geography.MAX_DISTANCE + ".");
									} else {
										box.width = d;
										setDidEdit();
									}
								}
							}
							widthField.setText(AppCore.formatDistance(box.width));
							blockActionsEnd();
						}
					}
				});

			} else {
				AppController.setComponentEnabled(widthField, false);
			}

			widthField.addFocusListener(new FocusAdapter() {
				public void focusGained(FocusEvent theEvent) {
					Component comp = theEvent.getComponent();
					if (comp instanceof JTextField) {
						setCurrentField((JTextField)comp);
					}
				}
				public void focusLost(FocusEvent theEvent) {
					if (!theEvent.isTemporary()) {
						Component comp = theEvent.getComponent();
						if (comp instanceof JTextField) {
							((JTextField)comp).postActionEvent();
						}
					}
				}
			});

			JPanel widthPanel = new JPanel();
			widthPanel.setBorder(BorderFactory.createTitledBorder("Width, km"));
			widthPanel.add(widthField);

			heightField = new JTextField(7);
			AppController.fixKeyBindings(heightField);

			if (canEdit) {

				heightField.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							String title = "Edit Width";
							String str = heightField.getText().trim();
							if (str.length() > 0) {
								double d = box.height;
								try {
									d = Double.parseDouble(str);
								} catch (NumberFormatException ne) {
									errorReporter.reportValidationError(title, "The height must be a number.");
								}
								if (d != box.height) {
									if ((d < Geography.MIN_DISTANCE) || (d > Geography.MAX_DISTANCE)) {
										errorReporter.reportValidationError(title, "The height must be between " +
											Geography.MIN_DISTANCE + " and " + Geography.MAX_DISTANCE + ".");
									} else {
										box.height = d;
										setDidEdit();
									}
								}
							}
							heightField.setText(AppCore.formatDistance(box.height));
							blockActionsEnd();
						}
					}
				});

			} else {
				AppController.setComponentEnabled(heightField, false);
			}

			heightField.addFocusListener(new FocusAdapter() {
				public void focusGained(FocusEvent theEvent) {
					Component comp = theEvent.getComponent();
					if (comp instanceof JTextField) {
						setCurrentField((JTextField)comp);
					}
				}
				public void focusLost(FocusEvent theEvent) {
					if (!theEvent.isTemporary()) {
						Component comp = theEvent.getComponent();
						if (comp instanceof JTextField) {
							((JTextField)comp).postActionEvent();
						}
					}
				}
			});

			JPanel heightPanel = new JPanel();
			heightPanel.setBorder(BorderFactory.createTitledBorder("Height, km"));
			heightPanel.add(heightField);

			// Layout.

			JPanel coordPanel = new JPanel();
			coordPanel.setBorder(BorderFactory.createTitledBorder("Center Point"));
			coordPanel.add(latitudePanel);
			coordPanel.add(longitudePanel);

			JPanel dimPanel = new JPanel();
			dimPanel.add(widthPanel);
			dimPanel.add(heightPanel);

			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			add(coordPanel);
			add(dimPanel);
			add(Box.createVerticalGlue());

			// Initial update.

			dataChanged();

			blockActionsEnd();
		}


		//-------------------------------------------------------------------------------------------------------------

		public void dataChanged() {

			latitudePanel.update();
			longitudePanel.update();
			widthField.setText(AppCore.formatDistance(box.width));
			heightField.setText(AppCore.formatDistance(box.height));
		}
	}


	//=================================================================================================================

	public static class CirclePanel extends GeoEditPanel {

		private GeoCircle circle;

		private CoordinatePanel latitudePanel;
		private CoordinatePanel longitudePanel;
		private JTextField radiusField;


		//-------------------------------------------------------------------------------------------------------------

		public CirclePanel(AppEditor theParent, Geography newGeo, boolean theEditFlag) {

			super(theParent, theEditFlag);

			blockActionsStart();

			circle = (GeoCircle)newGeo;

			Runnable callBack = null;
			if (canEdit) {
				callBack = new Runnable() {
					public void run() {
						setDidEdit();
					}
				};
			}

			latitudePanel = new CoordinatePanel(parent, circle.center, false, callBack);
			latitudePanel.setEnabled(canEdit);

			longitudePanel = new CoordinatePanel(parent, circle.center, true, callBack);
			longitudePanel.setEnabled(canEdit);

			radiusField = new JTextField(7);
			AppController.fixKeyBindings(radiusField);

			if (canEdit) {

				radiusField.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							String title = "Edit Radius";
							String str = radiusField.getText().trim();
							if (str.length() > 0) {
								double d = circle.radius;
								try {
									d = Double.parseDouble(str);
								} catch (NumberFormatException ne) {
									errorReporter.reportValidationError(title, "The radius must be a number.");
								}
								if (d != circle.radius) {
									if ((d < Geography.MIN_DISTANCE) || (d > Geography.MAX_DISTANCE)) {
										errorReporter.reportValidationError(title, "The radius must be between " +
											Geography.MIN_DISTANCE + " and " + Geography.MAX_DISTANCE + ".");
									} else {
										circle.radius = d;
										setDidEdit();
									}
								}
							}
							radiusField.setText(AppCore.formatDistance(circle.radius));
							blockActionsEnd();
						}
					}
				});

			} else {
				AppController.setComponentEnabled(radiusField, false);
			}

			radiusField.addFocusListener(new FocusAdapter() {
				public void focusGained(FocusEvent theEvent) {
					Component comp = theEvent.getComponent();
					if (comp instanceof JTextField) {
						setCurrentField((JTextField)comp);
					}
				}
				public void focusLost(FocusEvent theEvent) {
					if (!theEvent.isTemporary()) {
						Component comp = theEvent.getComponent();
						if (comp instanceof JTextField) {
							((JTextField)comp).postActionEvent();
						}
					}
				}
			});

			JPanel radiusPanel = new JPanel();
			radiusPanel.setBorder(BorderFactory.createTitledBorder("Radius, km"));
			radiusPanel.add(radiusField);

			// Layout.

			JPanel coordPanel = new JPanel();
			coordPanel.setBorder(BorderFactory.createTitledBorder("Center Point"));
			coordPanel.add(latitudePanel);
			coordPanel.add(longitudePanel);

			JPanel dimPanel = new JPanel();
			dimPanel.add(radiusPanel);

			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			add(coordPanel);
			add(dimPanel);
			add(Box.createVerticalGlue());

			// Initial update.

			dataChanged();

			blockActionsEnd();
		}


		//-------------------------------------------------------------------------------------------------------------

		public void dataChanged() {

			latitudePanel.update();
			longitudePanel.update();
			radiusField.setText(AppCore.formatDistance(circle.radius));
		}
	}


	//=================================================================================================================

	public static class SectorsPanel extends GeoEditPanel {

		private GeoSectors sectors;

		private CoordinatePanel latitudePanel;
		private CoordinatePanel longitudePanel;

		private SectorsTableModel sectorsModel;
		private JTable sectorsTable;

		private JButton insertButton;
		private JButton deleteButton;


		//-------------------------------------------------------------------------------------------------------------
		// This may be used from a context where only the actual sectors list is being edited, center point and name
		// are irrelevant, so the center point editing UI can be suppressed.

		public SectorsPanel(AppEditor theParent, Geography newGeo, boolean theEditFlag) {

			super(theParent, theEditFlag);

			doSetup(newGeo, true);
		}


		//-------------------------------------------------------------------------------------------------------------

		public SectorsPanel(AppEditor theParent, Geography newGeo, boolean theEditFlag, boolean showCoords) {

			super(theParent, theEditFlag);

			doSetup(newGeo, showCoords);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doSetup(Geography newGeo, boolean showCoords) {
			
			sectors = (GeoSectors)newGeo;

			blockActionsStart();

			if (showCoords) {

				Runnable callBack = null;
				if (canEdit) {
					callBack = new Runnable() {
						public void run() {
							setDidEdit();
						}
					};
				}

				latitudePanel = new CoordinatePanel(parent, sectors.center, false, callBack);
				latitudePanel.setEnabled(canEdit);

				longitudePanel = new CoordinatePanel(parent, sectors.center, true, callBack);
				longitudePanel.setEnabled(canEdit);
			}

			sectorsModel = new SectorsTableModel();
			sectorsTable = sectorsModel.createTable();

			// See comments in PointSetPanel.

			String moveUp = "moveUp";
			String moveDown = "moveDown";
			String moveLeft = "moveLeft";
			String moveRight = "moveRight";
			String tabForward = "tabForward";
			String tabBackward = "tabBackward";

			ActionMap am = sectorsTable.getActionMap();
			am.put(moveUp, new AbstractAction() {
				public void actionPerformed(ActionEvent e) {
					moveTableSelection(sectorsTable, -1, 0, false);
				}
			});
			am.put(moveDown, new AbstractAction() {
				public void actionPerformed(ActionEvent e) {
					moveTableSelection(sectorsTable, 1, 0, false);
				}
			});
			am.put(moveLeft, new AbstractAction() {
				public void actionPerformed(ActionEvent e) {
					moveTableSelection(sectorsTable, 0, -1, false);
				}
			});
			am.put(moveRight, new AbstractAction() {
				public void actionPerformed(ActionEvent e) {
					moveTableSelection(sectorsTable, 0, 1, false);
				}
			});
			am.put(tabForward, new AbstractAction() {
				public void actionPerformed(ActionEvent e) {
					moveTableSelection(sectorsTable, 0, 1, true);
				}
			});
			am.put(tabBackward, new AbstractAction() {
				public void actionPerformed(ActionEvent e) {
					moveTableSelection(sectorsTable, 0, -1, true);
				}
			});

			KeyStroke up = KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0);
			KeyStroke down = KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0);
			KeyStroke left = KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0);
			KeyStroke right = KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0);
			KeyStroke tab = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0);
			KeyStroke stab = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, Event.SHIFT_MASK);

			InputMap im = sectorsTable.getInputMap(JComponent.WHEN_FOCUSED);
			im.put(up, moveUp);
			im.put(down, moveDown);
			im.put(left, moveLeft);
			im.put(right, moveRight);
			im.put(tab, tabForward);
			im.put(stab, tabBackward);

			im = sectorsTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
			im.put(up, moveUp);
			im.put(down, moveDown);
			im.put(left, moveLeft);
			im.put(right, moveRight);
			im.put(tab, tabForward);
			im.put(stab, tabBackward);

			sectorsTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
				public void valueChanged(ListSelectionEvent theEvent) {
					if (!theEvent.getValueIsAdjusting() && canEdit) {
						if (1 == sectorsTable.getSelectedRowCount()) {
							insertButton.setEnabled(true);
							deleteButton.setEnabled(true);
						} else {
							insertButton.setEnabled(false);
							deleteButton.setEnabled(false);
						}
					}
				}
			});

			// Buttons.

			JPanel buttonPanel = null;

			if (canEdit) {

				JButton addButton = new JButton("Add");
				addButton.setFocusable(false);
				addButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doAddSector();
					}
				});

				insertButton = new JButton("Insert");
				insertButton.setFocusable(false);
				insertButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doInsertSector();
					}
				});
				insertButton.setEnabled(false);

				deleteButton = new JButton("Delete");
				deleteButton.setFocusable(false);
				deleteButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doDeleteSector();
					}
				});
				deleteButton.setEnabled(false);

				buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
				buttonPanel.add(addButton);
				buttonPanel.add(insertButton);
				buttonPanel.add(deleteButton);
			}

			// Layout.

			JPanel coordPanel = null;
			if (showCoords) {
				coordPanel = new JPanel();
				coordPanel.setBorder(BorderFactory.createTitledBorder("Center Point"));
				coordPanel.add(latitudePanel);
				coordPanel.add(longitudePanel);
			}

			JPanel tablePanel = new JPanel(new BorderLayout());
			tablePanel.setBorder(BorderFactory.createTitledBorder("Sectors"));
			tablePanel.add(AppController.createScrollPane(sectorsTable), BorderLayout.CENTER);
			AppController.setComponentEnabled(sectorsTable, canEdit);
			if (null != buttonPanel) {
				tablePanel.add(buttonPanel, BorderLayout.SOUTH);
			}

			setLayout(new BorderLayout());
			if (null != coordPanel) {
				add(coordPanel, BorderLayout.NORTH);
			}
			add(tablePanel, BorderLayout.CENTER);

			// Update.

			dataChanged();

			blockActionsEnd();
		}


		//-------------------------------------------------------------------------------------------------------------

		public void dataChanged() {

			if (null != latitudePanel) {
				latitudePanel.update();
				longitudePanel.update();
			}
			sectorsModel.dataChanged();
		}


		//=============================================================================================================

		private class SectorsTableModel extends AbstractTableModel {

			private static final String AZIMUTH_COLUMN = "Azimuth";
			private static final String RADIUS_COLUMN = "Radius";

			private String[] columnNames = {
				AZIMUTH_COLUMN,
				RADIUS_COLUMN
			};

			private static final int AZIMUTH_INDEX = 0;
			private static final int RADIUS_INDEX = 1;


			//---------------------------------------------------------------------------------------------------------

			private JTable createTable() {

				JTable theTable = new JTable(this);
				theTable.setColumnSelectionAllowed(false);
				theTable.getTableHeader().setReorderingAllowed(false);
				theTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

				TableColumnModel columnModel = theTable.getColumnModel();

				JTextField numFld = new JTextField(8);
				AppController.fixKeyBindings(numFld);
				numFld.setBorder(new LineBorder(Color.BLACK));

				DefaultCellEditor numEd = new DefaultCellEditor(numFld);
				numEd.setClickCountToStart(1);

				TableColumn theColumn = columnModel.getColumn(AZIMUTH_INDEX);
				theColumn.setCellEditor(numEd);
				theColumn.setMinWidth(AppController.textFieldWidth[4]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[8]);

				theColumn = columnModel.getColumn(RADIUS_INDEX);
				theColumn.setCellEditor(numEd);
				theColumn.setMinWidth(AppController.textFieldWidth[4]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[8]);

				return theTable;
			}


			//---------------------------------------------------------------------------------------------------------

			private int add(GeoSectors.Sector theSector) {

				if (!canEdit) {
					return -1;
				}

				int rowIndex = sectors.sectors.size();
				sectors.sectors.add(theSector);
				setDidEdit();

				fireTableRowsInserted(rowIndex, rowIndex);

				return rowIndex;
			}


			//---------------------------------------------------------------------------------------------------------

			private void insert(int rowIndex, GeoSectors.Sector theSector) {

				if (!canEdit) {
					return;
				}

				sectors.sectors.add(rowIndex, theSector);
				setDidEdit();

				fireTableRowsInserted(rowIndex, rowIndex);
			}


			//---------------------------------------------------------------------------------------------------------

			private void remove(int rowIndex) {

				if (!canEdit) {
					return;
				}

				sectors.sectors.remove(rowIndex);
				setDidEdit();

				fireTableRowsDeleted(rowIndex, rowIndex);
			}


			//---------------------------------------------------------------------------------------------------------

			private void dataChanged() {

				fireTableDataChanged();
			}


			//---------------------------------------------------------------------------------------------------------

			public int getColumnCount() {

				return columnNames.length;
			}


			//---------------------------------------------------------------------------------------------------------

			public String getColumnName(int columnIndex) {

				return columnNames[columnIndex];
			}


			//---------------------------------------------------------------------------------------------------------

			public int getRowCount() {

				return sectors.sectors.size();
			}


			//---------------------------------------------------------------------------------------------------------

			public Class getColumnClass(int columnIndex) {

				switch (columnIndex) {
					case AZIMUTH_INDEX:
					case RADIUS_INDEX:
						return String.class;
				}

				return Object.class;		
			}


			//---------------------------------------------------------------------------------------------------------

			public boolean isCellEditable(int rowIndex, int columnIndex) {

				return canEdit;
			}


			//---------------------------------------------------------------------------------------------------------

			public Object getValueAt(int rowIndex, int columnIndex) {

				GeoSectors.Sector theSector = sectors.sectors.get(rowIndex);

				switch (columnIndex) {

					case AZIMUTH_INDEX:
						return AppCore.formatAzimuth(theSector.azimuth);

					case RADIUS_INDEX:
						return AppCore.formatDistance(theSector.radius);
				}

				return "";
			}


			//---------------------------------------------------------------------------------------------------------

			public void setValueAt(Object value, int rowIndex, int columnIndex) {

				if (!canEdit || (rowIndex < 0) || (rowIndex >= sectors.sectors.size())) {
					return;
				}

				GeoSectors.Sector theSector = sectors.sectors.get(rowIndex);

				errorReporter.setTitle("Edit Sector");

				String str = ((String)value).trim();
				if (0 == str.length()) {
					return;
				}

				switch (columnIndex) {

					case AZIMUTH_INDEX: {
						double d = theSector.azimuth;
						try {
							d = Double.parseDouble(str);
						} catch (NumberFormatException ne) {
							errorReporter.reportValidationError("Azimuth must be a number.");
							return;
						}
						if ((d < 0.) || (d >= 360.)) {
							errorReporter.reportValidationError("Azimuth must be in the range 0 to less " +
								"than 360.");
							return;
						}
						if (d != theSector.azimuth) {
							double prevAz = -1.;
							if (rowIndex > 0) {
								prevAz = sectors.sectors.get(rowIndex - 1).azimuth;
							}
							double nextAz = 361.;
							if (rowIndex < (sectors.sectors.size() - 1)) {
								nextAz = sectors.sectors.get(rowIndex + 1).azimuth;
							}
							if ((d < prevAz) || (d > nextAz)) {
								errorReporter.reportValidationError("Azimuth out of sequence.");
								return;
							}
							if (((d - prevAz) < 1.) || ((nextAz - d) < 1.)) {
								errorReporter.reportValidationError("Sectors must span at least 1 degree.");
								return;
							}
							theSector.azimuth = d;
							setDidEdit();
						}
						break;
					}

					case RADIUS_INDEX: {
						double d = theSector.radius;
						try {
							d = Double.parseDouble(str);
						} catch (NumberFormatException ne) {
							errorReporter.reportValidationError("Radius must be a number.");
							return;
						}
						if ((d < Geography.MIN_DISTANCE) || (d > Geography.MAX_DISTANCE)) {
							errorReporter.reportValidationError("Radius must be in the range " +
								Geography.MIN_DISTANCE + " to " + Geography.MAX_DISTANCE + ".");
							return;
						}
						if (d != theSector.radius) {
							theSector.radius = d;
							setDidEdit();
						}
						break;
					}
				}
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doAddSector() {

			if (!canEdit || !commitTableEdits()) {
				return;
			}

			double theAz = 0., prevAz = 0.;
			if (sectorsModel.getRowCount() > 0) {
				prevAz = sectors.sectors.get(sectorsModel.getRowCount() - 1).azimuth;
				if (prevAz <= 358.) {
					theAz = (prevAz + 360.) / 2.;
				} else {
					errorReporter.reportWarning("Add Sector", "Cannot add sector, must span at least 1 degree.");
					return;
				}
			}

			GeoSectors.Sector newSector = new GeoSectors.Sector();
			newSector.azimuth = theAz;
			newSector.radius = Geography.MIN_DISTANCE;
			int rowIndex = sectorsModel.add(newSector);

			sectorsTable.scrollRectToVisible(sectorsTable.getCellRect(rowIndex, 0, true));
			sectorsTable.setRowSelectionInterval(rowIndex, rowIndex);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doInsertSector() {

			if (!canEdit || !commitTableEdits()) {
				return;
			}

			int rowIndex = sectorsTable.getSelectedRow();
			if (rowIndex < 0) {
				return;
			}

			double theAz = 0., prevAz = 0., nextAz = sectors.sectors.get(rowIndex).azimuth;
			if (rowIndex > 0) {
				prevAz = sectors.sectors.get(rowIndex - 1).azimuth;
			}
			if ((nextAz - prevAz) >= 2.) {
				theAz = (nextAz + prevAz) / 2.;
			} else {
				errorReporter.reportWarning("Insert Sector", "Cannot insert sector, must span at least 1 degree.");
				return;
			}

			GeoSectors.Sector newSector = new GeoSectors.Sector();
			newSector.azimuth = theAz;
			newSector.radius = Geography.MIN_DISTANCE;
			sectorsModel.insert(rowIndex, newSector);

			sectorsTable.scrollRectToVisible(sectorsTable.getCellRect(rowIndex, 0, true));
			sectorsTable.setRowSelectionInterval(rowIndex, rowIndex);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doDeleteSector() {

			if (!canEdit || !commitTableEdits()) {
				return;
			}

			int rowIndex = sectorsTable.getSelectedRow();
			if (rowIndex < 0) {
				return;
			}

			sectorsModel.remove(rowIndex);

			if (rowIndex >= sectorsModel.getRowCount()) {
				rowIndex--;
			}

			if ((rowIndex >= 0) && (rowIndex < sectorsModel.getRowCount())) {
				sectorsTable.scrollRectToVisible(sectorsTable.getCellRect(rowIndex, 0, true));
				sectorsTable.setRowSelectionInterval(rowIndex, rowIndex);
			}
		}


		//-------------------------------------------------------------------------------------------------------------
		// Stop cell editing in the table, return false on validation error.

		public boolean commitTableEdits() {

			if (sectorsTable.isEditing()) {
				if (canEdit) {
					errorReporter.clearErrors();
					sectorsTable.getCellEditor().stopCellEditing();
					return !errorReporter.hasErrors();
				} else {
					sectorsTable.getCellEditor().cancelCellEditing();
				}
			}
			return true;
		}
	}


	//=================================================================================================================

	public static class PolygonPanel extends GeoEditPanel {

		private GeoPolygon polygon;

		private CoordinatePanel latitudePanel;
		private CoordinatePanel longitudePanel;

		private VertexPointsTableModel pointsModel;
		private JTable pointsTable;

		private JButton insertButton;
		private JButton deleteButton;
		private JButton setReferenceButton;


		//-------------------------------------------------------------------------------------------------------------

		public PolygonPanel(AppEditor theParent, Geography newGeo, boolean theEditFlag) {

			super(theParent, theEditFlag);

			blockActionsStart();

			polygon = (GeoPolygon)newGeo;

			Runnable callBack = null;
			if (canEdit) {
				callBack = new Runnable() {
					public void run() {
						setDidEdit();
					}
				};
			}

			latitudePanel = new CoordinatePanel(parent, polygon.reference, false, callBack);
			latitudePanel.setEnabled(canEdit);

			longitudePanel = new CoordinatePanel(parent, polygon.reference, true, callBack);
			longitudePanel.setEnabled(canEdit);

			pointsModel = new VertexPointsTableModel();
			pointsTable = pointsModel.createTable();

			// See comments in GeoPointSetPanel.

			String moveUp = "moveUp";
			String moveDown = "moveDown";
			String moveLeft = "moveLeft";
			String moveRight = "moveRight";
			String tabForward = "tabForward";
			String tabBackward = "tabBackward";

			ActionMap am = pointsTable.getActionMap();
			am.put(moveUp, new AbstractAction() {
				public void actionPerformed(ActionEvent e) {
					moveTableSelection(pointsTable, -1, 0, false);
				}
			});
			am.put(moveDown, new AbstractAction() {
				public void actionPerformed(ActionEvent e) {
					moveTableSelection(pointsTable, 1, 0, false);
				}
			});
			am.put(moveLeft, new AbstractAction() {
				public void actionPerformed(ActionEvent e) {
					moveTableSelection(pointsTable, 0, -1, false);
				}
			});
			am.put(moveRight, new AbstractAction() {
				public void actionPerformed(ActionEvent e) {
					moveTableSelection(pointsTable, 0, 1, false);
				}
			});
			am.put(tabForward, new AbstractAction() {
				public void actionPerformed(ActionEvent e) {
					moveTableSelection(pointsTable, 0, 1, true);
				}
			});
			am.put(tabBackward, new AbstractAction() {
				public void actionPerformed(ActionEvent e) {
					moveTableSelection(pointsTable, 0, -1, true);
				}
			});

			KeyStroke up = KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0);
			KeyStroke down = KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0);
			KeyStroke left = KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0);
			KeyStroke right = KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0);
			KeyStroke tab = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0);
			KeyStroke stab = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, Event.SHIFT_MASK);

			InputMap im = pointsTable.getInputMap(JComponent.WHEN_FOCUSED);
			im.put(up, moveUp);
			im.put(down, moveDown);
			im.put(left, moveLeft);
			im.put(right, moveRight);
			im.put(tab, tabForward);
			im.put(stab, tabBackward);

			im = pointsTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
			im.put(up, moveUp);
			im.put(down, moveDown);
			im.put(left, moveLeft);
			im.put(right, moveRight);
			im.put(tab, tabForward);
			im.put(stab, tabBackward);

			pointsTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
				public void valueChanged(ListSelectionEvent theEvent) {
					if (!theEvent.getValueIsAdjusting() && canEdit) {
						if (1 == pointsTable.getSelectedRowCount()) {
							insertButton.setEnabled(true);
							deleteButton.setEnabled(true);
						} else {
							insertButton.setEnabled(false);
							deleteButton.setEnabled(false);
						}
					}
				}
			});

			// Buttons.

			JPanel buttonPanel = null;

			if (canEdit) {

				JButton addButton = new JButton("Add");
				addButton.setFocusable(false);
				addButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doAddVertex();
					}
				});

				insertButton = new JButton("Insert");
				insertButton.setFocusable(false);
				insertButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doInsertVertex();
					}
				});
				insertButton.setEnabled(false);

				deleteButton = new JButton("Delete");
				deleteButton.setFocusable(false);
				deleteButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doDeleteVertex();
					}
				});
				deleteButton.setEnabled(false);

				setReferenceButton = new JButton("Center");
				setReferenceButton.setFocusable(false);
				setReferenceButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doSetReferencePoint();
					}
				});
				setReferenceButton.setEnabled(!polygon.points.isEmpty());


				buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
				buttonPanel.add(addButton);
				buttonPanel.add(insertButton);
				buttonPanel.add(deleteButton);
			}

			// Layout.

			JPanel coordPanel = new JPanel();
			coordPanel.setBorder(BorderFactory.createTitledBorder("Reference Point"));
			coordPanel.add(latitudePanel);
			coordPanel.add(longitudePanel);
			if (null != setReferenceButton) {
				coordPanel.add(setReferenceButton);
			}

			JPanel tablePanel = new JPanel(new BorderLayout());
			tablePanel.add(AppController.createScrollPane(pointsTable), BorderLayout.CENTER);
			AppController.setComponentEnabled(pointsTable, canEdit);
			if (null != buttonPanel) {
				tablePanel.add(buttonPanel, BorderLayout.SOUTH);
			}
			tablePanel.setBorder(BorderFactory.createTitledBorder("Vertex Points (NAD83)"));

			setLayout(new BorderLayout());
			add(coordPanel, BorderLayout.NORTH);
			add(tablePanel);

			// Update.

			dataChanged();

			blockActionsEnd();
		}


		//-------------------------------------------------------------------------------------------------------------

		public void dataChanged() {

			latitudePanel.update();
			longitudePanel.update();
			pointsModel.dataChanged();
		}


		//=============================================================================================================

		private class VertexPointsTableModel extends AbstractTableModel {

			private static final String NS_COLUMN = "NS";
			private static final String LATD_COLUMN = "Lat D";
			private static final String LATM_COLUMN = "Lat M";
			private static final String LATS_COLUMN = "Lat S";
			private static final String WE_COLUMN = "WE";
			private static final String LOND_COLUMN = "Lon D";
			private static final String LONM_COLUMN = "Lon M";
			private static final String LONS_COLUMN = "Lon S";

			private String[] columnNames = {
				NS_COLUMN,
				LATD_COLUMN,
				LATM_COLUMN,
				LATS_COLUMN,
				WE_COLUMN,
				LOND_COLUMN,
				LONM_COLUMN,
				LONS_COLUMN
			};

			private static final int NS_INDEX = 0;
			private static final int LATD_INDEX = 1;
			private static final int LATM_INDEX = 2;
			private static final int LATS_INDEX = 3;
			private static final int WE_INDEX = 4;
			private static final int LOND_INDEX = 5;
			private static final int LONM_INDEX = 6;
			private static final int LONS_INDEX = 7;


			//---------------------------------------------------------------------------------------------------------

			private JTable createTable() {

				JTable theTable = new JTable(this);
				theTable.setColumnSelectionAllowed(false);
				theTable.getTableHeader().setReorderingAllowed(false);
				theTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

				TableColumnModel columnModel = theTable.getColumnModel();

				KeyedRecordMenu nsMenu = new KeyedRecordMenu();
				nsMenu.addItem(new KeyedRecord(0, "N"));
				nsMenu.addItem(new KeyedRecord(1, "S"));
				DefaultCellEditor nsEd = new DefaultCellEditor(nsMenu);

				TableColumn theColumn = columnModel.getColumn(NS_INDEX);
				theColumn.setCellEditor(nsEd);
				theColumn.setMinWidth(AppController.textFieldWidth[2]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[3]);

				JTextField numFld = new JTextField(8);
				AppController.fixKeyBindings(numFld);
				numFld.setBorder(new LineBorder(Color.BLACK));

				DefaultCellEditor numEd = new DefaultCellEditor(numFld);
				numEd.setClickCountToStart(1);

				theColumn = columnModel.getColumn(LATD_INDEX);
				theColumn.setCellEditor(numEd);
				theColumn.setMinWidth(AppController.textFieldWidth[3]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[6]);

				theColumn = columnModel.getColumn(LATM_INDEX);
				theColumn.setCellEditor(numEd);
				theColumn.setMinWidth(AppController.textFieldWidth[3]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[6]);

				theColumn = columnModel.getColumn(LATS_INDEX);
				theColumn.setCellEditor(numEd);
				theColumn.setMinWidth(AppController.textFieldWidth[4]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[8]);

				KeyedRecordMenu weMenu = new KeyedRecordMenu();
				weMenu.addItem(new KeyedRecord(0, "W"));
				weMenu.addItem(new KeyedRecord(1, "E"));
				DefaultCellEditor weEd = new DefaultCellEditor(weMenu);

				theColumn = columnModel.getColumn(WE_INDEX);
				theColumn.setCellEditor(weEd);
				theColumn.setMinWidth(AppController.textFieldWidth[2]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[3]);

				theColumn = columnModel.getColumn(LOND_INDEX);
				theColumn.setCellEditor(numEd);
				theColumn.setMinWidth(AppController.textFieldWidth[3]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[6]);

				theColumn = columnModel.getColumn(LONM_INDEX);
				theColumn.setCellEditor(numEd);
				theColumn.setMinWidth(AppController.textFieldWidth[3]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[6]);

				theColumn = columnModel.getColumn(LONS_INDEX);
				theColumn.setCellEditor(numEd);
				theColumn.setMinWidth(AppController.textFieldWidth[4]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[8]);

				return theTable;
			}


			//---------------------------------------------------------------------------------------------------------

			private int add(GeoPolygon.VertexPoint thePoint) {

				if (!canEdit) {
					return -1;
				}

				int rowIndex = polygon.points.size();
				polygon.points.add(thePoint);
				setDidEdit();

				fireTableRowsInserted(rowIndex, rowIndex);

				setReferenceButton.setEnabled(true);

				return rowIndex;
			}


			//---------------------------------------------------------------------------------------------------------

			private void insert(int rowIndex, GeoPolygon.VertexPoint thePoint) {

				if (!canEdit) {
					return;
				}

				polygon.points.add(rowIndex, thePoint);
				setDidEdit();

				fireTableRowsInserted(rowIndex, rowIndex);

				setReferenceButton.setEnabled(true);
			}


			//---------------------------------------------------------------------------------------------------------

			private void remove(int rowIndex) {

				if (!canEdit) {
					return;
				}

				polygon.points.remove(rowIndex);
				setDidEdit();

				fireTableRowsDeleted(rowIndex, rowIndex);

				setReferenceButton.setEnabled(!polygon.points.isEmpty());
			}


			//---------------------------------------------------------------------------------------------------------

			private void dataChanged() {

				fireTableDataChanged();

				setReferenceButton.setEnabled(!polygon.points.isEmpty());
			}


			//---------------------------------------------------------------------------------------------------------

			public int getColumnCount() {

				return columnNames.length;
			}


			//---------------------------------------------------------------------------------------------------------

			public String getColumnName(int columnIndex) {

				return columnNames[columnIndex];
			}


			//---------------------------------------------------------------------------------------------------------

			public int getRowCount() {

				return polygon.points.size();
			}


			//---------------------------------------------------------------------------------------------------------

			public Class getColumnClass(int columnIndex) {

				switch (columnIndex) {
					case NS_INDEX:
					case WE_INDEX:
						return KeyedRecord.class;
					case LATD_INDEX:
					case LATM_INDEX:
					case LATS_INDEX:
					case LOND_INDEX:
					case LONM_INDEX:
					case LONS_INDEX:
						return String.class;
				}

				return Object.class;		
			}


			//---------------------------------------------------------------------------------------------------------

			public boolean isCellEditable(int rowIndex, int columnIndex) {

				return canEdit;
			}


			//---------------------------------------------------------------------------------------------------------

			public Object getValueAt(int rowIndex, int columnIndex) {

				GeoPolygon.VertexPoint thePoint = polygon.points.get(rowIndex);

				switch (columnIndex) {

					case NS_INDEX:
						return new KeyedRecord(thePoint.latitudeNS, ((0 == thePoint.latitudeNS) ? "N" : "S"));

					case LATD_INDEX:
						return String.valueOf(thePoint.latitudeDegrees);

					case LATM_INDEX:
						return String.valueOf(thePoint.latitudeMinutes);

					case LATS_INDEX:
						return AppCore.formatSeconds(thePoint.latitudeSeconds);

					case WE_INDEX:
						return new KeyedRecord(thePoint.longitudeWE, ((0 == thePoint.longitudeWE) ? "W" : "E"));

					case LOND_INDEX:
						return String.valueOf(thePoint.longitudeDegrees);

					case LONM_INDEX:
						return String.valueOf(thePoint.longitudeMinutes);

					case LONS_INDEX:
						return AppCore.formatSeconds(thePoint.longitudeSeconds);
				}

				return "";
			}


			//---------------------------------------------------------------------------------------------------------

			public void setValueAt(Object value, int rowIndex, int columnIndex) {

				if (!canEdit || (rowIndex < 0) || (rowIndex >= polygon.points.size())) {
					return;
				}

				GeoPolygon.VertexPoint thePoint = polygon.points.get(rowIndex);

				errorReporter.setTitle("Edit Vertex");

				switch (columnIndex) {

					case NS_INDEX: {
						int ns = ((KeyedRecord)value).key;
						if (ns != thePoint.latitudeNS) {
							thePoint.latitudeNS = ns;
							thePoint.updateLatLon();
							setDidEdit();
						}
						break;
					}

					case WE_INDEX: {
						int we = ((KeyedRecord)value).key;
						if (we != thePoint.longitudeWE) {
							thePoint.longitudeWE = we;
							thePoint.updateLatLon();
							setDidEdit();
						}
						break;
					}

					case LATD_INDEX:
					case LATM_INDEX:
					case LATS_INDEX:
					case LOND_INDEX:
					case LONM_INDEX:
					case LONS_INDEX: {
						setVertexPointField(thePoint, rowIndex, columnIndex, ((String)value).trim());
						return;
					}
				}
			}


			//---------------------------------------------------------------------------------------------------------

			private void setVertexPointField(GeoPolygon.VertexPoint thePoint, int rowIndex, int columnIndex,
					String str) {

				if (!canEdit || (0 == str.length())) {
					return;
				}

				int deg, min, degmax;
				double d, sec, max;
				String lbl = "";

				try {
					d = Double.parseDouble(str);
				} catch (NumberFormatException ne) {
					errorReporter.reportValidationError("Input must be a number.");
					return;
				}

				switch (columnIndex) {

					case LATD_INDEX:
					case LATM_INDEX:
					case LATS_INDEX: {
						deg = thePoint.latitudeDegrees;
						min = thePoint.latitudeMinutes;
						sec = thePoint.latitudeSeconds;
						max = Source.LATITUDE_MAX;
						lbl = "Latitude";
						break;
					}

					case LOND_INDEX:
					case LONM_INDEX:
					case LONS_INDEX: {
						deg = thePoint.longitudeDegrees;
						min = thePoint.longitudeMinutes;
						sec = thePoint.longitudeSeconds;
						max = Source.LONGITUDE_MAX;
						lbl = "Longitude";
						break;
					}

					default: {
						return;
					}
				}

				degmax = (int)Math.floor(max);

				switch (columnIndex) {

					case LATD_INDEX:
					case LOND_INDEX: {
						deg = (int)Math.floor(d);
						if ((deg < 0) || (deg > degmax)) {
							errorReporter.reportValidationError(lbl + " must be in the range 0 to " + degmax + ".");
							return;
						}
						d -= (double)deg;
						if (d > (0.005 / 3600.)) {
							d *= 60.;
							min = (int)Math.floor(d);
							d -= (double)min;
							sec = d * 60.;
						}
						break;
					}

					case LATM_INDEX:
					case LONM_INDEX: {
						min = (int)Math.floor(d);
						if ((min < 0) || (min > 59)) {
							errorReporter.reportValidationError("Minutes must be in the range 0 to 59.");
							return;
						}
						d -= (double)min;
						if (d > (0.005 / 60.)) {
							sec = d * 60.;
						}
						break;
					}

					case LATS_INDEX:
					case LONS_INDEX: {
						sec = d;
						if ((sec < 0.) || (sec >= 60.)) {
							errorReporter.reportValidationError("Seconds must be in the range 0 to less than 60.");
							return;
						}
						break;
					}
				}

				if (sec > 59.995) {
					sec = 0.;
					if (++min > 59) {
						min = 0;
						++deg;
					}
				}

				d = (double)deg + ((double)min / 60.) + (sec / 3600.);
				if ((d < 0.) || (d > max)) {
					errorReporter.reportValidationError(lbl + " must be in the range 0 to " + max + ".");
					return;
				}

				boolean didChange = false, didChangeOther = false;

				switch (columnIndex) {

					case LATD_INDEX: {
						if (deg != thePoint.latitudeDegrees) {
							thePoint.latitudeDegrees = deg;
							didChange = true;
						}
						if (min != thePoint.latitudeMinutes) {
							thePoint.latitudeMinutes = min;
							didChange = true;
							didChangeOther = true;
						}
						if (sec != thePoint.latitudeSeconds) {
							thePoint.latitudeSeconds = sec;
							didChange = true;
							didChangeOther = true;
						}
						break;
					}

					case LATM_INDEX: {
						if (min != thePoint.latitudeMinutes) {
							thePoint.latitudeMinutes = min;
							didChange = true;
						}
						if (sec != thePoint.latitudeSeconds) {
							thePoint.latitudeSeconds = sec;
							didChange = true;
							didChangeOther = true;
						}
						break;
					}

					case LATS_INDEX: {
						if (sec != thePoint.latitudeSeconds) {
							thePoint.latitudeSeconds = sec;
							didChange = true;
						}
						break;
					}

					case LOND_INDEX: {
						if (deg != thePoint.longitudeDegrees) {
							thePoint.longitudeDegrees = deg;
							didChange = true;
						}
						if (min != thePoint.longitudeMinutes) {
							thePoint.longitudeMinutes = min;
							didChange = true;
							didChangeOther = true;
						}
						if (sec != thePoint.longitudeSeconds) {
							thePoint.longitudeSeconds = sec;
							didChange = true;
							didChangeOther = true;
						}
						break;
					}

					case LONM_INDEX: {
						if (min != thePoint.longitudeMinutes) {
							thePoint.longitudeMinutes = min;
							didChange = true;
						}
						if (sec != thePoint.longitudeSeconds) {
							thePoint.longitudeSeconds = sec;
							didChange = true;
							didChangeOther = true;
						}
						break;
					}

					case LONS_INDEX: {
						if (sec != thePoint.longitudeSeconds) {
							thePoint.longitudeSeconds = sec;
							didChange = true;
						}
						break;
					}
				}

				if (didChange) {
					thePoint.updateLatLon();
					setDidEdit();
				}
				if (didChangeOther) {
					fireTableRowsUpdated(rowIndex, rowIndex);
				}
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doAddVertex() {

			if (!canEdit || !commitTableEdits()) {
				return;
			}

			GeoPolygon.VertexPoint newPoint = new GeoPolygon.VertexPoint();
			int rowIndex = pointsModel.add(newPoint);

			pointsTable.scrollRectToVisible(pointsTable.getCellRect(rowIndex, 0, true));
			pointsTable.setRowSelectionInterval(rowIndex, rowIndex);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doInsertVertex() {

			if (!canEdit || !commitTableEdits()) {
				return;
			}

			int rowIndex = pointsTable.getSelectedRow();
			if (rowIndex < 0) {
				return;
			}

			GeoPolygon.VertexPoint newPoint = new GeoPolygon.VertexPoint();
			pointsModel.insert(rowIndex, newPoint);

			pointsTable.scrollRectToVisible(pointsTable.getCellRect(rowIndex, 0, true));
			pointsTable.setRowSelectionInterval(rowIndex, rowIndex);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doDeleteVertex() {

			if (!canEdit || !commitTableEdits()) {
				return;
			}

			int rowIndex = pointsTable.getSelectedRow();
			if (rowIndex < 0) {
				return;
			}

			pointsModel.remove(rowIndex);

			if (rowIndex >= pointsModel.getRowCount()) {
				rowIndex--;
			}

			if ((rowIndex >= 0) && (rowIndex < pointsModel.getRowCount())) {
				pointsTable.scrollRectToVisible(pointsTable.getCellRect(rowIndex, 0, true));
				pointsTable.setRowSelectionInterval(rowIndex, rowIndex);
			}
		}


		//-------------------------------------------------------------------------------------------------------------
		// Stop cell editing in the table, return false on validation error.

		public boolean commitTableEdits() {

			if (pointsTable.isEditing()) {
				if (canEdit) {
					errorReporter.clearErrors();
					pointsTable.getCellEditor().stopCellEditing();
					return !errorReporter.hasErrors();
				} else {
					pointsTable.getCellEditor().cancelCellEditing();
				}
			}
			return true;
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doSetReferencePoint() {

			if (!canEdit || !commitTableEdits()) {
				return;
			}

			if (polygon.points.isEmpty()) {
				return;
			}

			double slat = 999., nlat = -999, elon = 999., wlon = -999.;
			for (GeoPolygon.VertexPoint thePoint : polygon.points) {
				if (thePoint.latitude < slat) {
					slat = thePoint.latitude;
				}
				if (thePoint.latitude > nlat) {
					nlat = thePoint.latitude;
				}
				if (thePoint.longitude < elon) {
					elon = thePoint.longitude;
				}
				if (thePoint.longitude > wlon) {
					wlon = thePoint.longitude;
				}
			}

			boolean didChange = false;

			double lat = (slat + nlat) / 2.;
			if (lat != polygon.reference.latitude) {
				polygon.reference.latitude = lat;
				didChange = true;
			}

			double lon = (elon + wlon) / 2.;
			if (lon != polygon.reference.longitude) {
				polygon.reference.longitude = lon;
				didChange = true;
			}

			if (didChange) {
				setDidEdit();
				polygon.reference.updateDMS();
				latitudePanel.update();
				longitudePanel.update();
			}
		}
	}
}
