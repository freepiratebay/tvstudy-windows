//
//  IxRuleListData.java
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.editdata;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;

import java.util.*;
import java.text.*;


//=====================================================================================================================
// Model for an interference rule data list, this manages a list of IxRuleEditData objects.  This is typically used by
// a TableModel subclass in the GUI editor.  It provides basic list management, last-change tracking, and checks for
// model changes and validity.

public class IxRuleListData implements ListDataChange {

	private final boolean isLocked;

	private ArrayList<IxRuleEditData> modelRows;

	private HashSet<Integer> addedKeys;
	private HashSet<Integer> deletedKeys;
	private ArrayList<IxRuleEditData> changedRows;

	// For ListDataChange implementation.

	private int lastChange;
	private int lastRow;


	//-----------------------------------------------------------------------------------------------------------------

	public IxRuleListData(ArrayList<IxRule> theRules, boolean theLocked) {

		isLocked = theLocked;

		modelRows = new ArrayList<IxRuleEditData>();
		for (IxRule theRule : theRules) {
			if (null != theRule) {
				modelRows.add(new IxRuleEditData(theRule, isLocked));
			}
		}

		addedKeys = new HashSet<Integer>();
		deletedKeys = new HashSet<Integer>();
		changedRows = new ArrayList<IxRuleEditData>();

		lastChange = ListDataChange.ALL_CHANGE;
		lastRow = 0;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check uniqueness of a rule.  This must be called before a new or edited rule is applied with addOrReplace().
	// To allow edits of existing rules, this will ignore the same rule if found based on matching key.  This assumes
	// the rule is otherwise valid, meaning newRule.isDataValid() has been called and returned true.

	public boolean isIxRuleUnique(IxRuleEditData newRule) {
		return isIxRuleUnique(newRule, null);
	}

	public boolean isIxRuleUnique(IxRuleEditData newRule, ErrorLogger errors) {

		for (IxRuleEditData theRule : modelRows) {

			if (newRule.key.equals(theRule.key)) {
				continue;
			}

			if ((newRule.country.key == theRule.country.key) &&
					(newRule.serviceType.key == theRule.serviceType.key) &&
					(newRule.undesiredServiceType.key == theRule.undesiredServiceType.key) &&
					(newRule.channelDelta.key == theRule.channelDelta.key) &&
					((0 == newRule.channelBand.key) || (0 == theRule.channelBand.key) ||
						(newRule.channelBand.key == theRule.channelBand.key)) &&
					((0 == newRule.frequencyOffset) || (0 == theRule.frequencyOffset) ||
						(newRule.frequencyOffset == theRule.frequencyOffset)) &&
					(newRule.emissionMask.key == theRule.emissionMask.key)) {

				if (null != errors) {
					errors.reportValidationError("Duplicate interference rule.");
				}
				return false;
			}
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This will first match the object against the list, if found meaning matching key (see IxRuleEditData.equals())
	// replace the existing object, otherwise add it as a new row and update the addedKeys and deletedKeys sets.  Note
	// those sets are maintained primarily to ensure differences in the list are detected after any possible changes,
	// including undo operations.  If both addedKeys and deletedKeys are empty, the list contains the same set of rules
	// as at the start based on matching keys, though it may be different objects or objects with modified properties.
	// Note all the methods that may alter the list will return true if anything changed, false if not, and will also
	// set lastChange and lastRow appropriately.

	public boolean addOrReplace(IxRuleEditData theRule) {

		lastChange = ListDataChange.NO_CHANGE;
		lastRow = -1;

		if (isLocked) {
			return false;
		}

		lastRow = modelRows.indexOf(theRule);
		if (lastRow >= 0) {

			modelRows.set(lastRow, theRule);
			lastChange = ListDataChange.UPDATE;

		} else {

			lastRow = modelRows.size();
			modelRows.add(theRule);
			if (!deletedKeys.remove(theRule.key)) {
				addedKeys.add(theRule.key);
			}
			lastChange = ListDataChange.INSERT;
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean remove(int rowIndex) {

		lastChange = ListDataChange.NO_CHANGE;
		lastRow = -1;

		if (isLocked) {
			return false;
		}

		IxRuleEditData theRule = modelRows.remove(rowIndex);
		if (!addedKeys.remove(theRule.key)) {
			deletedKeys.add(theRule.key);
		}
		lastChange = ListDataChange.DELETE;
		lastRow = rowIndex;

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean remove(int[] rows) {

		lastChange = ListDataChange.NO_CHANGE;
		lastRow = -1;

		if (isLocked) {
			return false;
		}

		if (0 == rows.length) {
			return false;
		}

		if (1 == rows.length) {
			remove(rows[0]);
			lastChange = ListDataChange.DELETE;
			lastRow = rows[0];
			return true;
		}

		int i, j, m, r;
		for (i = 0; i < rows.length - 1; i++) {
			m = i;
			for (j = i + 1; j < rows.length; j++) {
				if (rows[j] > rows[m]) {
					m = j;
				}
			}
			if (m != i) {
				r = rows[i];
				rows[i] = rows[m];
				rows[m] = r;
			}
		}

		IxRuleEditData theRule;

		for (i = 0; i < rows.length; i++) {

			theRule = modelRows.remove(rows[i]);
			if (!addedKeys.remove(theRule.key)) {
				deletedKeys.add(theRule.key);
			}
		}

		lastChange = ListDataChange.ALL_CHANGE;
		lastRow = 0;

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public IxRuleEditData get(int rowIndex) {

		return modelRows.get(rowIndex);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public ArrayList<IxRuleEditData> getRows() {

		return new ArrayList<IxRuleEditData>(modelRows);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public ArrayList<IxRuleEditData> getActiveRows() {

		ArrayList<IxRuleEditData> result = new ArrayList<IxRuleEditData>();

		for (IxRuleEditData theRule : modelRows) {
			if (theRule.isActive) {
				result.add(theRule);
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set the active flag on a rule.  The change may affect more that just the indicated rule.  Sets of related rules
	// according to the logic in isDataValid() must be toggled together, else the validity check would fail.  So if the
	// rule has an emission mask or is offset-specific, search for other matching rules and set them to match.

	public boolean setActive(int rowIndex, boolean theFlag) {

		IxRuleEditData theRule = modelRows.get(rowIndex);

		theRule.isActive = theFlag;
		lastChange = ListDataChange.UPDATE;
		lastRow = rowIndex;

		int theCount = modelRows.size();

		if (theRule.emissionMask.key > 0) {

			IxRuleEditData otherRule;

			for (int otherIndex = 0; otherIndex < theCount; otherIndex++) {

				if (otherIndex == rowIndex) {
					continue;
				}
				otherRule = modelRows.get(otherIndex);

				if ((otherRule.country.key == theRule.country.key) &&
						(otherRule.serviceType.key == theRule.serviceType.key) &&
						(otherRule.undesiredServiceType.key == theRule.undesiredServiceType.key) &&
						(otherRule.channelDelta.key == theRule.channelDelta.key) &&
						(otherRule.channelBand.key == theRule.channelBand.key) &&
						((0 == otherRule.frequencyOffset) || (0 == theRule.frequencyOffset) ||
							(otherRule.frequencyOffset == theRule.frequencyOffset))) {

					otherRule.isActive = theFlag;
					lastChange = ListDataChange.ALL_CHANGE;
					lastRow = 0;
				}
			}
		}

		if (0 != theRule.frequencyOffset) {

			IxRuleEditData otherRule;

			for (int otherIndex = 0; otherIndex < theCount; otherIndex++) {

				if (otherIndex == rowIndex) {
					continue;
				}
				otherRule = modelRows.get(otherIndex);

				if ((otherRule.country.key == theRule.country.key) &&
						(otherRule.serviceType.key == theRule.serviceType.key) &&
						(otherRule.undesiredServiceType.key == theRule.undesiredServiceType.key) &&
						(otherRule.channelDelta.key == theRule.channelDelta.key) &&
						(otherRule.channelBand.key == theRule.channelBand.key) &&
						(otherRule.emissionMask.key == theRule.emissionMask.key)) {

					otherRule.isActive = theFlag;
					lastChange = ListDataChange.ALL_CHANGE;
					lastRow = 0;
				}
			}
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check for validity.  Individual rules are not checked here, the editors will check those before they are set in
	// the model.  However this needs to check for wider issues in the full set of rules.  Specifically, if one rule
	// specifies an emission mask, there must be other rules that are identical except for having the other mask types
	// selected, so there is a complete set defining behavior for all possible masks.  Likewise with offset-specific
	// rules, if one rule requires offset, another must exist that is identical except for not requiring offset.  Note
	// this can make the simplifying assumption that there are never any duplicate rules, see isIxRuleUnique() above.
	// These checks ignore inactive rules, see toggleActive() above.

	public boolean isDataValid() {
		return isDataValid(null);
	}

	public boolean isDataValid(ErrorLogger errors) {

		IxRuleEditData theRule, otherRule;
		int theIndex, otherIndex, theCount = modelRows.size();

		boolean[] masksChecked = new boolean[theCount];
		boolean[] offsetsChecked = new boolean[theCount];

		ArrayList<KeyedRecord> theMasks = EmissionMask.getEmissionMasks();
		int maskIndex, maskCount = theMasks.size();
		int[] maskKeys = new int[maskCount];
		for (maskIndex = 0; maskIndex < maskCount; maskIndex++) {
			maskKeys[maskIndex] = theMasks.get(maskIndex).key;
		}
		boolean[] maskFlags = new boolean[maskCount];

		boolean allFound = false;

		for (theIndex = 0; theIndex < theCount; theIndex++) {

			theRule = modelRows.get(theIndex);
			if (!theRule.isActive) {
				continue;
			}

			if (!masksChecked[theIndex] && (theRule.emissionMask.key > 0)) {

				// To do the mask check have to keep a check-off-as-seen list of the possible mask keys.  Once all
				// have been found stop looking, see above regarding duplicates (there aren't any).

				for (maskIndex = 0; maskIndex < maskCount; maskIndex++) {
					if (maskKeys[maskIndex] == theRule.emissionMask.key) {
						maskFlags[maskIndex] = true;
					} else {
						maskFlags[maskIndex] = false;
					}
				}
				allFound = false;

				for (otherIndex = theIndex + 1; otherIndex < theCount; otherIndex++) {

					otherRule = modelRows.get(otherIndex);

					if (otherRule.isActive && (otherRule.country.key == theRule.country.key) &&
							(otherRule.serviceType.key == theRule.serviceType.key) &&
							(otherRule.undesiredServiceType.key == theRule.undesiredServiceType.key) &&
							(otherRule.channelDelta.key == theRule.channelDelta.key) &&
							(otherRule.channelBand.key == theRule.channelBand.key) &&
							((0 == otherRule.frequencyOffset) || (0 == theRule.frequencyOffset) ||
								(otherRule.frequencyOffset == theRule.frequencyOffset))) {

						allFound = true;

						for (maskIndex = 0; maskIndex < maskCount; maskIndex++) {
							if (maskKeys[maskIndex] == otherRule.emissionMask.key) {
								maskFlags[maskIndex] = true;
							} else {
								if (!maskFlags[maskIndex]) {
									allFound = false;
								}
							}
						}

						masksChecked[otherIndex] = true;

						if (allFound) {
							break;
						}
					}
				}

				if (!allFound) {
					if (null != errors) {
						errors.reportValidationError("Incomplete rule set, for rule:\n" + theRule.toString() +
							"\nMust have matching active rules for all other emission mask types.");
					}
					return false;
				}
			}

			masksChecked[theIndex] = true;

			// Offset check is much simpler because there just needs to be one other rule that matches, don't even have
			// to check it's value for the offset flag because the uniqueness check guarantees it's always the other.

			if (!offsetsChecked[theIndex] && (0 != theRule.frequencyOffset)) {

				allFound = false;

				for (otherIndex = theIndex + 1; otherIndex < theCount; otherIndex++) {

					otherRule = modelRows.get(otherIndex);

					if (otherRule.isActive && (otherRule.country.key == theRule.country.key) &&
							(otherRule.serviceType.key == theRule.serviceType.key) &&
							(otherRule.undesiredServiceType.key == theRule.undesiredServiceType.key) &&
							(otherRule.channelDelta.key == theRule.channelDelta.key) &&
							(otherRule.channelBand.key == theRule.channelBand.key) &&
							(otherRule.emissionMask.key == theRule.emissionMask.key)) {

						allFound = true;

						offsetsChecked[otherIndex] = true;

						break;
					}
				}

				if (!allFound) {
					String str = null;
					if (IxRule.FREQUENCY_OFFSET_WITH == theRule.frequencyOffset) {
						str = "without frequency offset";
					} else {
						str = "with frequency offset";
					}
					if (null != errors) {
						errors.reportValidationError("Incomplete rule set, for rule:\n" + theRule.toString() +
							"\nMust have matching active rule " + str + ".");
					}
					return false;
				}
			}

			offsetsChecked[theIndex] = true;
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check if anything has changed in the model.  Always call isDataChanged() on all rows to build the list of all
	// changed or new objects needed during the data save process.

	public boolean isDataChanged() {

		changedRows.clear();

		if (isLocked) {
			return false;
		}

		boolean dataChanged = false;

		for (IxRuleEditData theRule : modelRows) {
			if (theRule.isDataChanged() || addedKeys.contains(theRule.key)) {
				changedRows.add(theRule);
				dataChanged = true;
			}
		}

		if (dataChanged) {
			return true;
		}

		if (!deletedKeys.isEmpty()) {
			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get set of deleted keys.  Only keys in the original un-edited list will appear here.

	public HashSet<Integer> getDeletedKeys() {

		return deletedKeys;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get all rules that have changed, isDataChanged() must always be called first.  Note this may contain objects
	// that are not themselves edited, this will include all objects referenced by the addedKeys set so it always
	// contains everything that needs to be saved.  See isDataChanged().

	public ArrayList<IxRuleEditData> getChangedRows() {

		return changedRows;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called after data has been saved, make current state un-edited.  The individual row objects must have a similar
	// method called directly, the save process must retrieve the changedRows list and iterate through calling
	// didSave() on the objects, after also saving the individual changes.

	public void didSave() {

		addedKeys.clear();
		deletedKeys.clear();
		changedRows.clear();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Return full list of the underlying data representation objects.  This assumes all the objects are committed to
	// the database (didSave() has been called on all new or changed objects).

	public ArrayList<IxRule> getRules() {

		ArrayList<IxRule> result = new ArrayList<IxRule>();
		for (IxRuleEditData theRule : modelRows) {
			result.add(theRule.ixRule);
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int getRowCount() {

		return modelRows.size();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// ListDataChange methods.

	public int getLastChange() {

		return lastChange;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int getLastRowChanged() {

		return lastRow;
	}
}
