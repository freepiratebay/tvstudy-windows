//
//  CoordinatePanel.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui.editor;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.geo.*;
import gov.fcc.tvstudy.gui.*;

import java.util.*;
import java.awt.*;
import java.awt.event.*;
import java.text.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;


//=====================================================================================================================
// UI for editing a geographic coordinate (latitude or longitude) with separate degrees, minutes, and seconds fields.
// Uses a GeoPoint model object directly.

public class CoordinatePanel extends AppPanel {

	private GeoPoint point;
	private boolean isLongitude;

	private JComboBox<String> directionMenu;
	private JTextField degreesField;
	private JTextField minutesField;
	private JTextField secondsField;

	private boolean enabled;


	//-----------------------------------------------------------------------------------------------------------------
	// If isLon is true then editing longitude, else latitude.  That affects labelling and range checks.  The Runnable
	// object is a call-back for reporting changes, run() is called on that object whenever the coordinate value is
	// edited through the UI.  The call back object may be null if dynamic updating is not needed.

	public CoordinatePanel(AppEditor theParent, GeoPoint thePoint, boolean isLon, Runnable theCallBack) {

		super(theParent, theCallBack);

		point = thePoint;
		isLongitude = isLon;

		blockActionsStart();

		// The separate degrees, minutes, and seconds field share a listener for range checks and formatting.  The
		// fields will have a small integer (as text) set as their action command for identification.

		ActionListener dmsFieldListener = new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {

				int theField = 0;
				try {
					theField = Integer.parseInt(theEvent.getActionCommand());
				} catch (NumberFormatException ne) {
				}
				if ((theField < 1) || (theField > 3)) {
					return;
				}

				if (enabled && blockActions()) {

					String title = "Edit Coordinates", str = "";

					switch (theField) {
						case 1: {
							str = degreesField.getText().trim();
							break;
						}
						case 2: {
							str = minutesField.getText().trim();
							break;
						}
						case 3: {
							str = secondsField.getText().trim();
							break;
						}
					}

					if (str.length() > 0) {

						int deg, min;
						double sec;

						if (isLongitude) {
							deg = point.longitudeDegrees;
							min = point.longitudeMinutes;
							sec = point.longitudeSeconds;
						} else {
							deg = point.latitudeDegrees;
							min = point.latitudeMinutes;
							sec = point.latitudeSeconds;
						}

						try {

							double d = Double.parseDouble(str);
							boolean bad = false;
							switch (theField) {
								case 1: {
									deg = (int)Math.floor(d);
									int max = 0;
									if (isLongitude) {
										max = (int)Math.floor(Source.LONGITUDE_MAX);
									} else {
										max = (int)Math.floor(Source.LATITUDE_MAX);
									}
									if ((deg < 0) || (deg > max)) {
										errorReporter.reportValidationError(title,
											"Degrees must be in the range 0 to " + max + ".");
										bad = true;
									} else {
										d -= (double)deg;
										if (d > (0.005 / 3600.)) {
											d *= 60.;
											min = (int)Math.floor(d);
											d -= (double)min;
											sec = d * 60.;
										}
									}
									break;
								}
								case 2: {
									min = (int)Math.floor(d);
									if ((min < 0) || (min > 59)) {
										errorReporter.reportValidationError(title,
											"Minutes must be in the range 0 to 59.");
										bad = true;
									} else {
										d -= (double)min;
										if (d > (0.005 / 60.)) {
											sec = d * 60.;
										}
									}
									break;
								}
								case 3: {
									sec = d;
									if ((sec < 0.) || (sec >= 60.)) {
										errorReporter.reportValidationError(title,
											"Seconds must be in the range 0 to less than 60.");
										bad = true;
									}
									break;
								}
							}

							if (!bad) {

								if (sec > 59.995) {
									sec = 0.;
									if (++min > 59) {
										min = 0;
										++deg;
									}
								}

								boolean didChange = false;
								d = (double)deg + ((double)min / 60.) + (sec / 3600.);
								if (isLongitude) {
									if ((d < 0.) || (d > Source.LONGITUDE_MAX)) {
										errorReporter.reportValidationError(title,
											"Longitude must be in the range 0 to " + Source.LONGITUDE_MAX + ".");
									} else {
										if (deg != point.longitudeDegrees) {
											point.longitudeDegrees = deg;
											didChange = true;
										}
										if (min != point.longitudeMinutes) {
											point.longitudeMinutes = min;
											didChange = true;
										}
										if (sec != point.longitudeSeconds) {
											point.longitudeSeconds = sec;
											didChange = true;
										}
									}
								} else {
									if ((d < 0.) || (d > Source.LATITUDE_MAX)) {
										errorReporter.reportValidationError(title,
											"Latitude must be in the range 0 to " + Source.LATITUDE_MAX + ".");
									} else {
										if (deg != point.latitudeDegrees) {
											point.latitudeDegrees = deg;
											didChange = true;
										}
										if (min != point.latitudeMinutes) {
											point.latitudeMinutes = min;
											didChange = true;
										}
										if (sec != point.latitudeSeconds) {
											point.latitudeSeconds = sec;
											didChange = true;
										}
									}
								}

								if (didChange) {
									point.updateLatLon();
									if (null != callBack) {
										callBack.run();
									}
								}
							}

						} catch (NumberFormatException ne) {
							errorReporter.reportValidationError(title, "Input must be a number.");
						}
					}

					blockActionsEnd();
				}

				update();
			}
		};

		// Create the direction menu (north/south for latitude, west/east for longitude).

		directionMenu = new JComboBox<String>();
		directionMenu.setFocusable(false);
		if (isLongitude) {
			directionMenu.addItem("W");
			directionMenu.addItem("E");
		} else {
			directionMenu.addItem("N");
			directionMenu.addItem("S");
		}

		directionMenu.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					int newDir = directionMenu.getSelectedIndex();
					boolean didChange = false;
					if (isLongitude) {
						if (newDir != point.longitudeWE) {
							point.longitudeWE = newDir;
							didChange = true;
						}
					} else {
						if (newDir != point.latitudeNS) {
							point.latitudeNS = newDir;
							didChange = true;
						}
					}
					if (didChange) {
						point.updateLatLon();
						if (null != callBack) {
							callBack.run();
						}
					}
					blockActionsEnd();
				}
			}
		});

		// Create editing fields for degrees, minutes, and seconds.

		degreesField = new JTextField(3);
		AppController.fixKeyBindings(degreesField);

		degreesField.setActionCommand("1");
		degreesField.addActionListener(dmsFieldListener);

		degreesField.addFocusListener(new FocusAdapter() {
			public void focusGained(FocusEvent theEvent) {
				setCurrentField(degreesField);
			}
			public void focusLost(FocusEvent theEvent) {
				if (!theEvent.isTemporary()) {
					degreesField.postActionEvent();
				}
			}
		});

		minutesField = new JTextField(3);
		AppController.fixKeyBindings(minutesField);

		minutesField.setActionCommand("2");
		minutesField.addActionListener(dmsFieldListener);

		minutesField.addFocusListener(new FocusAdapter() {
			public void focusGained(FocusEvent theEvent) {
				setCurrentField(minutesField);
			}
			public void focusLost(FocusEvent theEvent) {
				if (!theEvent.isTemporary()) {
					minutesField.postActionEvent();
				}
			}
		});

		secondsField = new JTextField(6);
		AppController.fixKeyBindings(secondsField);

		secondsField.setActionCommand("3");
		secondsField.addActionListener(dmsFieldListener);

		secondsField.addFocusListener(new FocusAdapter() {
			public void focusGained(FocusEvent theEvent) {
				setCurrentField(secondsField);
			}
			public void focusLost(FocusEvent theEvent) {
				if (!theEvent.isTemporary()) {
					secondsField.postActionEvent();
				}
			}
		});

		// Lay out the panel, also gets a titled border.

		if (isLongitude) {
			setBorder(BorderFactory.createTitledBorder("Longitude (NAD83)"));
		} else {
			setBorder(BorderFactory.createTitledBorder("Latitude (NAD83)"));
		}
		add(directionMenu);
		add(degreesField);
		add(new JLabel("°"));
		add(minutesField);
		add(new JLabel("'"));
		add(secondsField);
		add(new JLabel("\""));

		update();

		enabled = true;

		blockActionsEnd();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void update() {

		blockActionsStart();

		if (isLongitude) {

			directionMenu.setSelectedIndex(point.longitudeWE);
			degreesField.setText(String.valueOf(point.longitudeDegrees));
			minutesField.setText(String.valueOf(point.longitudeMinutes));
			secondsField.setText(AppCore.formatSeconds(point.longitudeSeconds));

		} else {

			directionMenu.setSelectedIndex(point.latitudeNS);
			degreesField.setText(String.valueOf(point.latitudeDegrees));
			minutesField.setText(String.valueOf(point.latitudeMinutes));
			secondsField.setText(AppCore.formatSeconds(point.latitudeSeconds));
		}

		blockActionsEnd();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isLongitude() {

		return isLongitude;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void setEnabled(boolean flag) {

		AppController.setComponentEnabled(directionMenu, flag);
		AppController.setComponentEnabled(degreesField, flag);
		AppController.setComponentEnabled(minutesField, flag);
		AppController.setComponentEnabled(secondsField, flag);

		enabled = flag;
	}
}