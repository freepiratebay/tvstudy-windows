//
//  CalendarDialog.java
//  TVStudy
//
//  Copyright (c) 2003-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;

import gov.fcc.tvstudy.core.*;

import java.util.*;
import java.text.*;
import java.awt.*;
import java.awt.event.*;

import javax.swing.*;


//=====================================================================================================================
// A calendar date-selection dialog.

public class CalendarDialog extends AppDialog {

	private static final int YEAR_MENU_RANGE = 4;

	private Calendar currentCalendar;
	private boolean allowNullDate;
	private boolean allowFutureDate;
	private int lockoutYear;
	private int lockoutMonth;
	private int lockoutDay;

	private Date lastDateSet;

	private Calendar displayCalendar;
	private JComboBox<String> monthMenu;
	private JComboBox<String> yearMenu;
	private int yearMenuBaseYear;
	private ArrayList<JToggleButton> buttons;
	private JButton todayButton;
	private JButton clearButton;

	public boolean canceled;

	private boolean windowClosing;

	private CalendarDialog outerThis = this;


	//-----------------------------------------------------------------------------------------------------------------

	public CalendarDialog(AppEditor theParent) {

		super(theParent, "Select Date", Dialog.ModalityType.APPLICATION_MODAL);

		allowNullDate = true;

		// The displayCalendar indicates the month and year being displayed in the calendar, the day of the month is
		// always set to 1 so the day of week of that first day is available, and the time of day is set 0:00:00.000.

		displayCalendar = Calendar.getInstance();
		displayCalendar.set(Calendar.DATE, 1);
		displayCalendar.set(Calendar.HOUR_OF_DAY, 0);
		displayCalendar.set(Calendar.MINUTE, 0);
		displayCalendar.set(Calendar.SECOND, 0);
		displayCalendar.set(Calendar.MILLISECOND, 0);

		// Set up the UI.  Month and year selection menus at the top.

		String months[] = {
			"January",
			"February",
			"March",
			"April",
			"May",
			"June",
			"July",
			"August",
			"September",
			"October",
			"November",
			"December"
		};
		monthMenu = new JComboBox<String>(months);
		monthMenu.setFocusable(false);
		monthMenu.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if (blockActions()) {
					displayCalendar.set(Calendar.MONTH, (monthMenu.getSelectedIndex() + Calendar.JANUARY));
					updateDisplay();
					blockActionsEnd();
				}
			}
		});
		monthMenu.setMaximumRowCount(12);

		yearMenu = new JComboBox<String>();
		yearMenu.setFocusable(false);
		yearMenu.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if (blockActions()) {
					displayCalendar.set(Calendar.YEAR, (yearMenu.getSelectedIndex() + yearMenuBaseYear));
					updateDisplay();
					blockActionsEnd();
				}
			}
		});
		yearMenu.setMaximumRowCount((2 * YEAR_MENU_RANGE) + 1);
		yearMenuBaseYear = displayCalendar.get(Calendar.YEAR) - YEAR_MENU_RANGE;

		JPanel navP = new JPanel();
		navP.add(monthMenu);
		navP.add(yearMenu);

		// Day-of-week labels.

		JPanel lblP = new JPanel();
		lblP.setLayout(new GridLayout(1, 7, 0, 0));
		lblP.add(new JLabel("Sun", SwingConstants.CENTER));
		lblP.add(new JLabel("Mon", SwingConstants.CENTER));
		lblP.add(new JLabel("Tue", SwingConstants.CENTER));
		lblP.add(new JLabel("Wed", SwingConstants.CENTER));
		lblP.add(new JLabel("Thu", SwingConstants.CENTER));
		lblP.add(new JLabel("Fri", SwingConstants.CENTER));
		lblP.add(new JLabel("Sat", SwingConstants.CENTER));

		// Set up the grid of calendar day buttons.

		buttons = new ArrayList<JToggleButton>();

		Dimension theSize = new Dimension(35, 35);
		Insets theMarg = new Insets(0, 0, 0, 0);
		JToggleButton but;

		JPanel calP = new JPanel();
		calP.setLayout(new GridLayout(6, 7, 0, 0));

		// Button action handler, if the button was not already selected (meaning it is selected now, after the click),
		// update the current date per the button and the displayed month, otherwise re-select the button (the selected
		// button can't be toggled off by clicking, see the Clear button below).  In any case, close the dialog.  All
		// other buttons are de-selected so if one of those was selected it toggles to the off state; that may seem
		// unnecessary, however the result does get re-painted and is briefly visible before the dialog disappears.

		ActionListener butAct = new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				JToggleButton but = (JToggleButton)e.getSource();
				if (buttons.contains(but)) {
					for (JToggleButton b : buttons) {
						if (!b.equals(but)) {
							b.setSelected(false);
						}
					}
					if (but.isSelected()) {
						int newDay = buttons.indexOf(but) -
							(displayCalendar.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY) + 1;
						currentCalendar = Calendar.getInstance();
						currentCalendar.setTime(displayCalendar.getTime());
						currentCalendar.set(Calendar.DATE, newDay);
					} else {
						but.setSelected(true);
					}
					windowClosing = true;
					AppController.hideWindow(outerThis);
				}
			}
		};

		for (int day = 0; day < 42; day++) {

			but = new JToggleButton(" ");
			but.setFocusable(false);

			but.setMargin(theMarg);
			but.setPreferredSize(theSize);
			but.setMinimumSize(theSize);
			but.setMaximumSize(theSize);

			buttons.add(but);
			calP.add(but);

			but.addActionListener(butAct);
		}

		// Clear and cancel buttons.  The clear button is hidden if null dates are not allowed, see setNullAllowed().
		// Also "Today" button that sets today's date (button will be disabled if locked out, see setLockoutDate()).

		todayButton = new JButton("Today");
		todayButton.setFocusable(false);
		todayButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				doSetDate(new Date());
				windowClosing = true;
				AppController.hideWindow(outerThis);
			}
		});

		clearButton = new JButton("Clear");
		clearButton.setFocusable(false);
		clearButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				doSetDate(null);
				windowClosing = true;
				AppController.hideWindow(outerThis);
			}
		});

		JButton canButton = new JButton("Cancel");
		canButton.setFocusable(false);
		canButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				doSetDate(lastDateSet);
				canceled = true;
				windowClosing = true;
				AppController.hideWindow(outerThis);
			}
		});

		JPanel butP = new JPanel();
		butP.add(todayButton);
		butP.add(clearButton);
		butP.add(canButton);

		// Final layout, initial display update.

		Container cp = getContentPane();
		cp.setLayout(new BoxLayout(cp, BoxLayout.PAGE_AXIS));
		cp.add(navP);
		cp.add(lblP);
		cp.add(calP);
		cp.add(butP);

		pack();

		updateDisplay();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set whether or not the selected date can be null (no selection).  If set false when the date is null, set today.

	public void setNullAllowed(boolean nullOk) {

		allowNullDate = nullOk;
		clearButton.setVisible(nullOk);
		if (!allowNullDate && (null == currentCalendar)) {
			doSetDate(new Date());
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set whether or not the selected date can be in the future.  This only applies to UI selection through the day
	// buttons; programmatic sets can still select any date.  Refresh the display as this may cause some day buttons
	// to be enabled or disabled.

	public void setFutureAllowed(boolean futureOk) {

		allowFutureDate = futureOk;
		updateDisplay();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set a lockout date, the date selected through the UI cannot be on or before this date, but as with the future
	// date restriction this does not affect programmatic sets.  Always update the display.  Must check to see if the
	// lock date is in the past, if not the "Today" button is disabled.

	public void setLockoutDate(Date lockDate) {

		if (null == lockDate) {

			todayButton.setEnabled(true);
			lockoutYear = 0;
			lockoutMonth = 0;
			lockoutDay = 0;

		} else {

			Calendar theCal = Calendar.getInstance();
			theCal.set(Calendar.HOUR_OF_DAY, 0);
			theCal.set(Calendar.MINUTE, 0);
			theCal.set(Calendar.SECOND, 0);
			theCal.set(Calendar.MILLISECOND, 0);
			todayButton.setEnabled(lockDate.before(theCal.getTime()));
			theCal.setTime(lockDate);
			lockoutYear = theCal.get(Calendar.YEAR);
			lockoutMonth = theCal.get(Calendar.MONTH);
			lockoutDay = theCal.get(Calendar.DATE);
		}

		updateDisplay();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Public setter, remembers the last value set to use for the cancel action.

	public void setDate(Date theDate) {

		lastDateSet = theDate;
		doSetDate(theDate);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Combined setter for all properties, to avoid unnecessary display updates.

	public void setAllState(boolean nullOk, boolean futureOk, Date lockDate, Date theDate) {

		allowNullDate = nullOk;
		clearButton.setVisible(nullOk);

		allowFutureDate = futureOk;

		if (null == lockDate) {

			todayButton.setEnabled(true);
			lockoutYear = 0;
			lockoutMonth = 0;
			lockoutDay = 0;

		} else {

			Calendar theCal = Calendar.getInstance();
			theCal.set(Calendar.HOUR_OF_DAY, 0);
			theCal.set(Calendar.MINUTE, 0);
			theCal.set(Calendar.SECOND, 0);
			theCal.set(Calendar.MILLISECOND, 0);
			todayButton.setEnabled(lockDate.before(theCal.getTime()));
			theCal.setTime(lockDate);
			lockoutYear = theCal.get(Calendar.YEAR);
			lockoutMonth = theCal.get(Calendar.MONTH);
			lockoutDay = theCal.get(Calendar.DATE);
		}

		lastDateSet = theDate;

		doSetDate(theDate);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Private setter, set the current calendar day, null is allowed depending on allowNullDate, if false setting null
	// sets today.  Time-of-day parts are zeroed.  The display month always follows the date set, even if all the
	// buttons in that month would be disabled.  Then update the display.

	private void doSetDate(Date theDate) {

		if (!allowNullDate && (null == theDate)) {
			theDate = new Date();
		}

		int newDay;
		if (null == theDate) {
			displayCalendar.setTime(new Date());
			newDay = 0;
		} else {
			displayCalendar.setTime(theDate);
			newDay = displayCalendar.get(Calendar.DATE);
		}
		displayCalendar.set(Calendar.DATE, 1);
		displayCalendar.set(Calendar.HOUR_OF_DAY, 0);
		displayCalendar.set(Calendar.MINUTE, 0);
		displayCalendar.set(Calendar.SECOND, 0);
		displayCalendar.set(Calendar.MILLISECOND, 0);

		if (newDay > 0) {
			currentCalendar = Calendar.getInstance();
			currentCalendar.setTime(displayCalendar.getTime());
			currentCalendar.set(Calendar.DATE, newDay);
		} else {
			currentCalendar = null;
		}

		updateDisplay();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public Date getDate() {

		if (null != currentCalendar) {
			return currentCalendar.getTime();
		}
		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// If the dialog was canceled, either with the Cancel button or with a close action via the manager, the previous
	// date given to setDate() is restored and canceled is set true.

	public void windowWillOpen() {

		canceled = false;
		windowClosing = false;

		blockActionsClear();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean windowShouldClose() {

		if (!windowClosing) {
			doSetDate(lastDateSet);
			canceled = true;
		}

		blockActionsSet();

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update the display.  Set the month label, then update the button grid to set date text and enable/disable, and
	// also to select the button corresponding to currentCalendar if that is set and it's month is being displayed.
	// If future dates are not allowed, any buttons after today are disabled.  Similarily if a lockout date is set,
	// any buttons on or before that date are disabled.  Any month can be displayed, but all buttons may be disabled.

	private void updateDisplay() {

		int dispYr = displayCalendar.get(Calendar.YEAR);
		int dispMo = displayCalendar.get(Calendar.MONTH);

		int disableOnOrBefore = 0;
		if ((dispYr < lockoutYear) || ((dispYr == lockoutYear) && (dispMo < lockoutMonth))) {
			disableOnOrBefore = 999;
		} else {
			if ((dispYr == lockoutYear) && (dispMo == lockoutMonth)) {
				disableOnOrBefore = lockoutDay;
			}
		}

		int disableAfter = 999;
		if (!allowFutureDate) {
			Calendar nowCal = Calendar.getInstance();
			int nowYr = nowCal.get(Calendar.YEAR);
			int nowMo = nowCal.get(Calendar.MONTH);
			if ((dispYr > nowYr) || ((dispYr == nowYr) && (dispMo > nowMo))) {
				disableAfter = 0;
			} else {
				if ((dispYr == nowYr) && (dispMo == nowMo)) {
					disableAfter = nowCal.get(Calendar.DATE);
				}
			}
		}

		blockActionsStart();

		monthMenu.setSelectedIndex(dispMo);
		yearMenuBaseYear = dispYr - YEAR_MENU_RANGE;
		yearMenu.removeAllItems();
		for (int y = 0; y < ((2 * YEAR_MENU_RANGE) + 1); y++) {
			yearMenu.addItem(String.valueOf(yearMenuBaseYear + y));
		}
		yearMenu.setSelectedIndex(YEAR_MENU_RANGE);

		blockActionsEnd();

		int selectedDay = 0;
		if ((null != currentCalendar) &&
				(currentCalendar.get(Calendar.YEAR) == dispYr) && (currentCalendar.get(Calendar.MONTH) == dispMo)) {
			selectedDay = currentCalendar.get(Calendar.DATE);
		}

		int lastDay = displayCalendar.getActualMaximum(Calendar.DATE);
		int theDay = 1 - (displayCalendar.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY);

		for (JToggleButton b : buttons) {
			if ((theDay < 1) || (theDay > lastDay)) {
				b.setText(" ");
				b.setSelected(false);
				b.setEnabled(false);
			} else {
				b.setText(String.valueOf(theDay));
				b.setSelected(theDay == selectedDay);
				b.setEnabled((theDay > disableOnOrBefore) && (theDay <= disableAfter));
			}
			theDay++;
		}

		// Not sure why this is necessary, but without it there are frequent glitches after a month or year change
		// where some (but not all) buttons fail to redraw.

		repaint();
	}
}
