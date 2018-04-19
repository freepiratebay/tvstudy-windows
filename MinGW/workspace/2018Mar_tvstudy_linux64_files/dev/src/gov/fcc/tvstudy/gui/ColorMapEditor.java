//
//  ColorMapEditor.java
//  TVStudy
//
//  Copyright (c) 2015-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.gui.*;

import java.util.*;
import java.sql.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.color.*;
import java.awt.geom.*;

import javax.swing.*;
import javax.swing.event.*;


//=====================================================================================================================
// Dialog to edit image output color maps.  There are fixed maps one for each possible quantity that can be shown in
// the image, this allows the colors and color-change levels to be edited for each but it does not allow entire maps
// to be added or deleted.  This is a standalone editor, when shown it will load all maps from the database, on close
// it will save all (regardless of actual edits).  UI is created for all maps that should exist, if any do not a set
// of defaults is used and will be saved, so all maps exist after a close.

public class ColorMapEditor extends AppDialog {

	public static final String WINDOW_TITLE = "Image Color Maps";

	private KeyedRecordMenu colorMapMenu;

	private HashMap<Integer, ColorMapPanel> colorMapPanels;
	private JPanel wrapperPanel;

	private static final double MIN_LEVEL = -30.;
	private static final double MAX_LEVEL = 100.;

	private static final int COUNT_INCREMENT = 20;

	private static final int COLOR_BUTTON_WIDTH = 48;
	private static final int COLOR_BUTTON_HEIGHT = 16;


	//-----------------------------------------------------------------------------------------------------------------

	public ColorMapEditor(AppEditor theParent) {

		super(theParent, WINDOW_TITLE, Dialog.ModalityType.APPLICATION_MODAL);

		colorMapMenu = new KeyedRecordMenu();
		colorMapPanels = new HashMap<Integer, ColorMapPanel>();

		String[] mapNames = OutputConfig.getFlagNames(OutputConfig.CONFIG_TYPE_MAP)[OutputConfig.MAP_OUT_IMAGE];
		for (int mapKey = 1; mapKey < mapNames.length; mapKey++) {
			colorMapMenu.addItem(new KeyedRecord(mapKey, mapNames[mapKey]));
			colorMapPanels.put(Integer.valueOf(mapKey), new ColorMapPanel(mapKey));
		}

		colorMapMenu.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				ColorMapPanel newPanel = colorMapPanels.get(Integer.valueOf(colorMapMenu.getSelectedKey()));
				wrapperPanel.removeAll();
				wrapperPanel.add(newPanel);
				newPanel.revalidate();
				wrapperPanel.repaint();
			}
		});

		wrapperPanel = new JPanel();
		wrapperPanel.setLayout(new BoxLayout(wrapperPanel, BoxLayout.X_AXIS));
		wrapperPanel.setMinimumSize(new Dimension(0, 400));

		wrapperPanel.add(colorMapPanels.get(Integer.valueOf(colorMapMenu.getSelectedKey())));

		// Buttons.

		JButton saveButton = new JButton("Save");
		saveButton.setFocusable(false);
		saveButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doSave();
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

		JPanel menuP = new JPanel();
		menuP.add(colorMapMenu);

		JPanel butP = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		butP.add(cancelButton);
		butP.add(saveButton);

		Container cp = getContentPane();
		cp.setLayout(new BorderLayout());
		cp.add(menuP, BorderLayout.NORTH);
		cp.add(wrapperPanel, BorderLayout.CENTER);
		cp.add(butP, BorderLayout.SOUTH);

		pack();

		Dimension theSize = getSize();
		theSize.height = 400;
		setMinimumSize(theSize);
		setSize(theSize);

		setResizable(true);
		setLocationSaved(true);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void updateDocumentName() {

		setDocumentName(parent.getDocumentName());
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doSave() {

		if (!commitCurrentField()) {
			return;
		}

		errorReporter.setTitle("Save Color Maps");

		String errmsg = null;

		DbConnection db = DbCore.connectDb(getDbID(), errorReporter);
		if (null != db) {
			try {

				db.update("LOCK TABLES color_map WRITE, color_map_data WRITE");

				Color theColor;
				StringBuilder q = new StringBuilder();
				String sep;

				for (ColorMapPanel mapPanel : colorMapPanels.values()) {

					db.update("DELETE FROM color_map WHERE color_map_key = " + mapPanel.mapKey);
					db.update("DELETE FROM color_map_data WHERE color_map_key = " + mapPanel.mapKey);

					theColor = mapPanel.backgroundColor;

					q.setLength(0);
					q.append("INSERT INTO color_map VALUES (");
					q.append(String.valueOf(mapPanel.mapKey));
					q.append(',');
					q.append(theColor.getRed());
					q.append(',');
					q.append(theColor.getGreen());
					q.append(',');
					q.append(theColor.getBlue());
					q.append(')');

					db.update(q.toString());

					q.setLength(0);
					q.append("INSERT INTO color_map_data VALUES ");
					sep = "(";

					for (int ci = 0; ci < mapPanel.colorCount; ci++) {

						theColor = mapPanel.colors[ci];

						q.append(sep);
						q.append(String.valueOf(mapPanel.mapKey));
						q.append(',');
						q.append(String.valueOf(mapPanel.levels[ci]));
						q.append(',');
						q.append(theColor.getRed());
						q.append(',');
						q.append(theColor.getGreen());
						q.append(',');
						q.append(theColor.getBlue());

						sep = "),(";
					}

					q.append(')');

					db.update(q.toString());
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
				return;
			}
		}
		
		AppController.hideWindow(this);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillOpen() {

		SwingUtilities.invokeLater(new Runnable() {
			public void run() {

				errorReporter.setTitle("Load Color Maps");

				DbConnection db = DbCore.connectDb(getDbID(), errorReporter);
				if (null != db) {
					try {

						db.query("SELECT color_map_key, bg_color_r, bg_color_g, bg_color_b FROM color_map");

						ColorMapPanel mapPanel = null;

						while (db.next()) {
							mapPanel = colorMapPanels.get(Integer.valueOf(db.getInt(1)));
							if (null != mapPanel) {
								mapPanel.backgroundColor = new Color(db.getInt(2), db.getInt(3), db.getInt(4));
							}
						}

						db.query("SELECT color_map_key, level, color_r, color_g, color_b " +
							"FROM color_map_data ORDER BY 1, 2");

						int theKey, lastKey = -1, colIndex;
						mapPanel = null;

						while (db.next()) {

							theKey = db.getInt(1);
							if (theKey != lastKey) {
								mapPanel = colorMapPanels.get(Integer.valueOf(theKey));
								if (null != mapPanel) {
									mapPanel.colorCount = 0;
								}
								lastKey = theKey;
							}

							if (null != mapPanel) {

								colIndex = mapPanel.colorCount++;
								mapPanel.expandArrays();

								mapPanel.levels[colIndex] = db.getDouble(2);
								mapPanel.colors[colIndex] = new Color(db.getInt(3), db.getInt(4), db.getInt(5));
							}
						}

						// Repeat, for default values.

						db.query("SELECT color_map_key, bg_color_r, bg_color_g, bg_color_b FROM color_map_default");

						mapPanel = null;

						while (db.next()) {
							mapPanel = colorMapPanels.get(Integer.valueOf(db.getInt(1)));
							if (null != mapPanel) {
								mapPanel.defaultBackgroundColor = new Color(db.getInt(2), db.getInt(3), db.getInt(4));
							}
						}

						db.query("SELECT color_map_key, level, color_r, color_g, color_b " +
							"FROM color_map_data_default ORDER BY 1, 2");

						lastKey = -1;
						mapPanel = null;

						while (db.next()) {

							theKey = db.getInt(1);
							if (theKey != lastKey) {
								mapPanel = colorMapPanels.get(Integer.valueOf(theKey));
								if (null != mapPanel) {
									mapPanel.defaultColorCount = 0;
								}
								lastKey = theKey;
							}

							if (null != mapPanel) {

								colIndex = mapPanel.defaultColorCount++;
								mapPanel.expandDefaultArrays();

								mapPanel.defaultLevels[colIndex] = db.getDouble(2);
								mapPanel.defaultColors[colIndex] = new Color(db.getInt(3), db.getInt(4), db.getInt(5));
							}
						}

					} catch (SQLException se) {
						db.reportError(errorReporter, se);
					}

					DbCore.releaseDb(db);
				}

				for (ColorMapPanel thePanel : colorMapPanels.values()) {
					thePanel.update();
				}
			}
		});
	}


	//=================================================================================================================
	// Panel to edit one color map.

	private class ColorMapPanel extends JPanel {

		private int mapKey;

		private Color backgroundColor;
		private int colorCount;
		private double[] levels;
		private Color[] colors;

		private Color defaultBackgroundColor;
		private int defaultColorCount;
		private double[] defaultLevels;
		private Color[] defaultColors;

		private ColorPanel backgroundPanel;
		private ColorPanel[] colorPanels;

		private JPanel editorPanel;

		private JButton removeButton;


		//-------------------------------------------------------------------------------------------------------------

		private ColorMapPanel(int theMapKey) {

			super();

			mapKey = theMapKey;

			// Create data storage.

			levels = new double[COUNT_INCREMENT];
			colors = new Color[COUNT_INCREMENT];

			defaultLevels = new double[COUNT_INCREMENT];
			defaultColors = new Color[COUNT_INCREMENT];

			colorPanels = new ColorPanel[COUNT_INCREMENT];

			// Set default conditions.

			backgroundColor = Color.BLUE;
			colorCount = 1;
			levels[0] = 0.;
			colors[0] = Color.GREEN;

			defaultBackgroundColor = Color.BLUE;
			defaultColorCount = 1;
			defaultLevels[0] = 0.;
			defaultColors[0] = Color.GREEN;

			// Color panels created as needed in update().

			backgroundPanel = new ColorPanel(-1);

			editorPanel = new JPanel();
			editorPanel.setLayout(new BoxLayout(editorPanel, BoxLayout.Y_AXIS));

			editorPanel.add(backgroundPanel);

			JPanel wrapperP = new JPanel();
			wrapperP.add(editorPanel);

			// Buttons.

			JButton addButton = new JButton("Add");
			addButton.setFocusable(false);
			addButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doAddColor();
				}
			});

			removeButton = new JButton("Remove");
			removeButton.setFocusable(false);
			removeButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doRemoveColor();
				}
			});

			JButton defaultsButton = new JButton("Defaults");
			defaultsButton.setFocusable(false);
			defaultsButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doSetDefaults();
				}
			});

			// Do the layout.

			JPanel lButPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
			lButPanel.add(addButton);
			lButPanel.add(removeButton);

			JPanel rButPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
			rButPanel.add(defaultsButton);

			Box butBox = Box.createHorizontalBox();
			butBox.add(lButPanel);
			butBox.add(rButPanel);

			setLayout(new BorderLayout());
			add(AppController.createScrollPane(wrapperP), BorderLayout.CENTER);
			add(butBox, BorderLayout.SOUTH);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doAddColor() {

			if (!commitCurrentField()) {
				return;
			}

			int newIndex = colorCount++;
			expandArrays();

			levels[newIndex] = levels[newIndex - 1] + 3.;
			colors[newIndex] = Color.GREEN;

			update();
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doRemoveColor() {

			if (!commitCurrentField()) {
				return;
			}

			if (colorCount < 1) {
				return;
			}

			colorCount--;

			update();
		}


		//-------------------------------------------------------------------------------------------------------------
		// Reset the map to default values.

		private void doSetDefaults() {

			backgroundColor = defaultBackgroundColor;

			colorCount = defaultColorCount;
			expandArrays();

			for (int i = 0; i < defaultColorCount; i++) {
				levels[i] = defaultLevels[i];
				colors[i] = defaultColors[i];
			}

			update();
		}


		//-------------------------------------------------------------------------------------------------------------
		// Called after model data is set or changed, colorCount may have changed.

		private void update() {

			for (int i = colorCount; i < colorPanels.length; i++) {
				if (null != colorPanels[i]) {
					colorPanels[i].setVisible(false);
				}
			}

			backgroundPanel.update();

			for (int i = 0; i < colorCount; i++) {
				if (null == colorPanels[i]) {
					colorPanels[i] = new ColorPanel(i);
					editorPanel.add(colorPanels[i]);
					colorPanels[i].revalidate();
				} else {
					colorPanels[i].setVisible(true);
				}
				colorPanels[i].update();
			}

			editorPanel.repaint();

			removeButton.setEnabled(colorCount > 1);
		}


		//-------------------------------------------------------------------------------------------------------------
		// Called after colorCount changed and may have increased.

		private void expandArrays() {

			if (colorCount <= levels.length) {
				return;
			}

			int newLength = colorCount + COUNT_INCREMENT;

			double[] newLevels = new double[newLength];
			Color[] newColors = new Color[newLength];
			ColorPanel[] newColorPanels = new ColorPanel[newLength];

			for (int i = 0; i < levels.length; i++) {
				newLevels[i] = levels[i];
				newColors[i] = colors[i];
				newColorPanels[i] = colorPanels[i];
			}

			levels = newLevels;
			colors = newColors;
			colorPanels = newColorPanels;
		}


		//-------------------------------------------------------------------------------------------------------------
		// As above, for the default arrays.

		private void expandDefaultArrays() {

			if (defaultColorCount <= defaultLevels.length) {
				return;
			}

			int newLength = defaultColorCount + COUNT_INCREMENT;

			double[] newLevels = new double[newLength];
			Color[] newColors = new Color[newLength];

			for (int i = 0; i < defaultLevels.length; i++) {
				newLevels[i] = defaultLevels[i];
				newColors[i] = defaultColors[i];
			}

			defaultLevels = newLevels;
			defaultColors = newColors;
		}


		//=============================================================================================================
		// Panel to edit one color in a map, different behavior for background.

		private class ColorPanel extends JPanel {

			private int colorIndex;

			private JTextField levelField;
			private JButton colorButton;


			//---------------------------------------------------------------------------------------------------------

			private ColorPanel(int theIndex) {

				colorIndex = theIndex;

				setLayout(new FlowLayout(FlowLayout.LEFT));

				if (colorIndex < 0) {

					add(new JLabel("Background"));

				} else {

					levelField = new JTextField(5);
					AppController.fixKeyBindings(levelField);

					levelField.addActionListener(new ActionListener() {
						public void actionPerformed(ActionEvent theEvent) {
							String title = "Edit Level";
							String str = levelField.getText().trim();
							if (str.length() > 0) {
								double d = levels[colorIndex];
								try {
									d = Double.parseDouble(str);
									d = Math.rint(d * 100.) / 100.;
								} catch (NumberFormatException ne) {
									errorReporter.reportValidationError(title,
										"The color change level must be a number.");
								}
								if (d != levels[colorIndex]) {
									double minLevel, maxLevel;
									if (0 == colorIndex) {
										minLevel = MIN_LEVEL;
									} else {
										minLevel = levels[colorIndex - 1];
									}
									if ((colorCount - 1) == colorIndex) {
										maxLevel = MAX_LEVEL;
									} else {
										maxLevel = levels[colorIndex + 1];
									}
									if ((d <= minLevel) || (d >= maxLevel)) {
										errorReporter.reportValidationError(title,
											"The color change level must be between " + minLevel + " and " +
											maxLevel + ".");
									} else {
										levels[colorIndex] = d;
									}
								}
							}
							levelField.setText(AppCore.formatDecimal(levels[colorIndex], 2));
						}
					});

					levelField.addFocusListener(new FocusAdapter() {
						public void focusGained(FocusEvent theEvent) {
							setCurrentField(levelField);
						}
						public void focusLost(FocusEvent theEvent) {
							if (!theEvent.isTemporary()) {
								levelField.postActionEvent();
							}
						}
					});

					add(levelField);
				}

				colorButton = new JButton(new ColorButtonIcon());
				colorButton.setFocusable(false);
				colorButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (!commitCurrentField()) {
							return;
						}
						Color oldColor;
						if (colorIndex < 0) {
							oldColor = backgroundColor;
						} else {
							oldColor = colors[colorIndex];
						}
						Color newColor = JColorChooser.showDialog(colorButton, "Pick Color", oldColor);
						if (null != newColor) {
							colorButton.setBackground(newColor);
							if (colorIndex < 0) {
								backgroundColor = newColor;
							} else {
								colors[colorIndex] = newColor;
							}
						}
					}
				});

				add(colorButton);
			}


			//---------------------------------------------------------------------------------------------------------

			private void update() {

				if (colorIndex < 0) {
					colorButton.setBackground(backgroundColor);
				} else {
					colorButton.setBackground(colors[colorIndex]);
					levelField.setText(AppCore.formatDecimal(levels[colorIndex], 2));
				}
			}
		}
	}


	//=================================================================================================================
	// Icon for color buttons, solid-filled rounded rectangle.  Uses component background color.

	private class ColorButtonIcon implements Icon {


		//-------------------------------------------------------------------------------------------------------------

		public int getIconWidth() {

			return COLOR_BUTTON_WIDTH;
		}


		//-------------------------------------------------------------------------------------------------------------

		public int getIconHeight() {

			return COLOR_BUTTON_HEIGHT;
		}


		//-------------------------------------------------------------------------------------------------------------

		public void paintIcon(Component c, Graphics g, int x, int y) {

			Graphics2D g2 = (Graphics2D)g;
			g2.setColor(c.getBackground());
			double wid = (double)COLOR_BUTTON_WIDTH, hgt = (double)COLOR_BUTTON_HEIGHT, arc = wid / 8.;
			g2.fill(new RoundRectangle2D.Double((double)x, (double)y, wid, hgt, arc, arc));
		}
	}
}
