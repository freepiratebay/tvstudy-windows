//
//  DateSelectionPanel.java
//  TVStudy
//
//  Copyright (c) 2003-2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;

import gov.fcc.tvstudy.core.*;

import java.util.*;
import java.text.*;
import java.awt.*;
import java.awt.event.*;

import javax.swing.*;
import javax.swing.border.*;

import java.net.URL;


//=====================================================================================================================
// Creates a panel to manage a date UI.  The date is displayed in a label, next to that is a button that pops up a
// dialog for selecting a date from a calendar view, see the CalendarDialog class.  Value can be set as text or as
// a date object, when set as text multiple parsers are used to allow varying format, but the displayed and returned
// format is fixed, see setText() and getText().  Options to restrict the date include preventing it from being null,
// and preventing it from being in the future, or on or before a lockout date.  The no-future and lockout settings
// only apply to UI selection; the date can always be set programmatically to anything.  However the no-null behavior
// applies to the setter as well, if nulls are not allowed the panel will always have a date, null cannot be set.
// The default behaviors are to allow nulls, not allow future dates, and have no lockout date.

// Note there are some convolutions here involving precision.  Logically, this is about selecting a date as a whole
// calendar day, regardless of time-of-day.  To make that work, internally a Calendar object is used to zero out all
// time-of-day fields for the date as set.  However, so that the caller can just compare Date objects to determine if
// the set date was actually changed, yet not have to worry about zeroing time-of-day itself before setting here,
// this will retain the original Date object set (or derived by a parser if setText() is used) and will return that
// unless some operation, i.e. an actual UI change, occurs which changes the day selected.

public class DateSelectionPanel extends AppPanel {

	private static final ImageIcon setButtonIcon;
	static {
		URL imgUrl = ClassLoader.getSystemResource("gov/fcc/tvstudy/gui/CalendarButtonIcon.png");
		if (null != imgUrl) {
			setButtonIcon = new ImageIcon(imgUrl);
		} else {
			setButtonIcon = null;
		}
	}

	private Date currentDate;
	private boolean allowNullDate;
	private boolean allowFutureDate;
	private Date lockoutDate;

	private Date lastDateSet;

	private JLabel dateLabel;
	private JLabel dayLabel;

	private JButton setButton;

	private DateSelectionPanel outerThis = this;


	//-----------------------------------------------------------------------------------------------------------------
	// If a title string is provided, the panel is always bordered with that title; otherwise the border flag controls
	// whether or not the panel is bordered.  The showDay flag causes a second label to be shown with the day of week.

	public DateSelectionPanel(AppEditor theParent, String title, boolean showDay) {
		super(theParent);
		makePanel(title, true, showDay);
	}

	public DateSelectionPanel(AppEditor theParent, String title, boolean showDay, Runnable theCallBack) {
		super(theParent, theCallBack);
		makePanel(title, true, showDay);
	}

	public DateSelectionPanel(AppEditor theParent, boolean border, boolean showDay) {
		super(theParent);
		makePanel(null, border, showDay);
	}

	public DateSelectionPanel(AppEditor theParent, boolean border, boolean showDay, Runnable theCallBack) {
		super(theParent, theCallBack);
		makePanel(null, border, showDay);
	}

	private void makePanel(String title, boolean border, boolean showDay) {

		allowNullDate = true;

		if ((null != title) && (title.length() > 0)) {
			setBorder(BorderFactory.createTitledBorder(title));
		} else {
			if (border) {
				setBorder(BorderFactory.createEtchedBorder());	
			}
		}

		dateLabel = new JLabel();
		dateLabel.setHorizontalAlignment(SwingConstants.CENTER);
		dateLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		Dimension theSize = AppController.labelSize[10];
		dateLabel.setPreferredSize(theSize);
		dateLabel.setMinimumSize(theSize);
		dateLabel.setMaximumSize(theSize);

		if (showDay) {
			dayLabel = new JLabel();
			dayLabel.setHorizontalAlignment(SwingConstants.CENTER);
			dayLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
			dayLabel.setPreferredSize(theSize);
			dayLabel.setMinimumSize(theSize);
			dayLabel.setMaximumSize(theSize);
		}

		if (null != setButtonIcon) {
			setButton = new JButton(setButtonIcon);
			theSize = new Dimension(29, 29);
			setButton.setPreferredSize(theSize);
			setButton.setMinimumSize(theSize);
			setButton.setMaximumSize(theSize);
		} else {
			setButton = new JButton("Set");
		}
		setButton.setFocusable(false);

		// When the button is clicked, show a calendar dialog.  Unless the dialog was cancelled, run the callback.

		setButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				CalendarDialog calDialog = new CalendarDialog(parent);
				calDialog.setAllState(allowNullDate, allowFutureDate, lockoutDate, currentDate);
				calDialog.setLocationRelativeTo(outerThis);
				AppController.showWindow(calDialog);
				doSetDate(calDialog.getDate());
				if (!calDialog.canceled && (null != callBack)) {
					callBack.run();
				}
			}
		});

		// Lay out the panel.

		if (showDay) {
			Box b = Box.createVerticalBox();
			b.add(dateLabel);
			b.add(dayLabel);
			add(b);
		} else {
			add(dateLabel);
		}
		add(setButton);

		setAlignmentX(Component.CENTER_ALIGNMENT);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Enabled state affects only the button.

	// OK, this one is truly bizarre and a new low for Swing bugs.  Enabling and disabling the _button_ was causing
	// the panel's _border_ to change state, and not correctly either.  When the button was disabled the border turned
	// gray, but when the button was enabled again, the border remained gray.  Sticking a repaint() here would at
	// least ungray the border on enable; small comfort.  Deferring the button setEnabled() fixes it entirely.  WTF.

	public void setEnabled(boolean e) {

		final boolean flag = e;
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				setButton.setEnabled(flag);
			}
		});
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isEnabled() {

		return setButton.isEnabled();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void setToolTipText(String theText) {

		setButton.setToolTipText(theText);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set whether or not the date can be null, if set false and the current date is null, set it to today.  Note that
	// is considered a change to the date, not a set, so doSetDate() is used directly.

	public void setNullAllowed(boolean nullOk) {

		allowNullDate = nullOk;
		if (!allowNullDate && (null == currentDate)) {
			doSetDate(getToday());
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isNullAllowed() {

		return allowNullDate;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set whether or not a date in the future can be selected.  This applies only to the UI; the date set through
	// setDate() or setText() is never range-restricted.

	public void setFutureAllowed(boolean futureOk) {

		allowFutureDate = futureOk;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isFutureAllowed() {

		return allowFutureDate;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set the lockout date, the selected date cannot be on or before this day.  As with the future dates restriction,
	// this only applies to the UI, any date can still be set programmatically.  See CalendarDialog for details.

	public void setLockoutDate(Date theDate) {

		if (null != theDate) {
			lockoutDate = getDay(theDate);
		} else {
			lockoutDate = null;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public Date getLockoutDate() {

		return lockoutDate;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set the date as text, multiple formats are supported, see AppCore.  An empty string or a parse failure means
	// date is set null, see setDate() for further details.

	public void setText(String theText) {

		Date theDate = null;
		theText = theText.trim();
		if (theText.length() > 0) {
			theDate = AppCore.parseDate(theText);
		}
		setDate(theDate);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This can just return the label text, see setDate().

	public String getText() {

		return dateLabel.getText().trim();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Public setter for the date, retains the value in lastDateSet, and zeros time-of-day parts.

	public void setDate(Date theDate) {

		lastDateSet = theDate;
		if (null != theDate) {
			theDate = getDay(theDate);
		}
		doSetDate(theDate);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Public setter for the date that does not remeber the new value as last-set; allows the caller to alter the date
	// while still preserving an original value for comparison by isDateChanged().

	public void changeDate(Date theDate) {

		if (null != theDate) {
			theDate = getDay(theDate);
		}
		doSetDate(theDate);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Private setter, change the date, and update the display label.  Null is valid only if allowNullDate is true,
	// otherwise attempting to set null sets today.  This assumes the date has already been truncated with getDay().

	private void doSetDate(Date theDate) {

		if (!allowNullDate && (null == theDate)) {
			theDate = getToday();
		}

		currentDate = theDate;
		if (null != currentDate) {
			dateLabel.setText(AppCore.formatDate(currentDate));
			if (null != dayLabel) {
				dayLabel.setText(AppCore.formatDay(currentDate));
			}
		} else {
			dateLabel.setText(" ");
			if (null != dayLabel) {
				dayLabel.setText(" ");
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This will always return the current, truncated version of the date even if there was no change, the caller
	// should use isDateChanged() to determine if there was really a change to the represented date.

	public Date getDate() {

		return currentDate;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Compare the current date to the last value set, considering only the date parts.

	public boolean isDateChanged() {

		if (null == lastDateSet) {
			if (null == currentDate) {
				return false;
			}
			return true;
		} else {
			if (null == currentDate) {
				return true;
			}
		}

		Calendar cal = Calendar.getInstance();
		cal.setTime(lastDateSet);
		int dy = cal.get(Calendar.DAY_OF_YEAR);
		int yr = cal.get(Calendar.YEAR);
		cal.setTime(currentDate);
		if ((cal.get(Calendar.DAY_OF_YEAR) != dy) || (cal.get(Calendar.YEAR) != yr)) {
			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Method modifies a Date to have a precision of one day; uses a Calendar to zero all time-of-day parts.

	private Date getDay(Date theDate) {

		Calendar theCal = Calendar.getInstance();
		theCal.setTime(theDate);
		theCal.set(Calendar.HOUR_OF_DAY, 0);
		theCal.set(Calendar.MINUTE, 0);
		theCal.set(Calendar.SECOND, 0);
		theCal.set(Calendar.MILLISECOND, 0);

		return theCal.getTime();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Returns "now" with day-level precision.

	private Date getToday() {

		Calendar theCal = Calendar.getInstance();
		theCal.set(Calendar.HOUR_OF_DAY, 0);
		theCal.set(Calendar.MINUTE, 0);
		theCal.set(Calendar.SECOND, 0);
		theCal.set(Calendar.MILLISECOND, 0);

		return theCal.getTime();
	}
}
