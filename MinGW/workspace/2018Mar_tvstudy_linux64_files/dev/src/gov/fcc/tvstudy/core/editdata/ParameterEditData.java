//
//  ParameterEditData.java
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.editdata;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;

import java.util.*;


//=====================================================================================================================
// Mutable data class to support editing of a parameter.  The isLocked flag is a copy of the templateLocked flag from
// the parent study or template object.

public class ParameterEditData {

	public Parameter parameter;

	public final boolean isLocked;

	public String[] value;

	public int[][] integerTableValue;
	public double[][] decimalTableValue;

	public int[] integerValue;
	public double[] decimalValue;
	public boolean[] optionValue;
	public int[] pickfromIndex;
	public java.util.Date[] dateValue;


	//-----------------------------------------------------------------------------------------------------------------

	public ParameterEditData(Parameter theParameter, boolean theLocked) {

		super();

		parameter = theParameter;

		isLocked = theLocked;

		value = new String[parameter.valueCount];

		int valueIndex;
		for (valueIndex = 0; valueIndex < parameter.valueCount; valueIndex++) {
			value[valueIndex] = parameter.value[valueIndex];
		}

		if (parameter.isTable) {

			int tableSize = parameter.tableRowLabels.length * parameter.tableColumnLabels.length, tableIndex;

			switch (parameter.type) {

				case Parameter.TYPE_INTEGER: {
					integerTableValue = new int[parameter.valueCount][tableSize];
					for (valueIndex = 0; valueIndex < parameter.valueCount; valueIndex++) {
						for (tableIndex = 0; tableIndex < tableSize; tableIndex++) {
							integerTableValue[valueIndex][tableIndex] =
								parameter.integerTableValue[valueIndex][tableIndex];
						}
					}
					break;
				}

				case Parameter.TYPE_DECIMAL: {
					decimalTableValue = new double[parameter.valueCount][tableSize];
					for (valueIndex = 0; valueIndex < parameter.valueCount; valueIndex++) {
						for (tableIndex = 0; tableIndex < tableSize; tableIndex++) {
							decimalTableValue[valueIndex][tableIndex] =
								parameter.decimalTableValue[valueIndex][tableIndex];
						}
					}
					break;
				}
			}

		} else {

			switch (parameter.type) {

				case Parameter.TYPE_INTEGER: {
					integerValue = new int[parameter.valueCount];
					for (valueIndex = 0; valueIndex < parameter.valueCount; valueIndex++) {
						integerValue[valueIndex] = parameter.integerValue[valueIndex];
					}
					break;
				}

				case Parameter.TYPE_DECIMAL: {
					decimalValue = new double[parameter.valueCount];
					for (valueIndex = 0; valueIndex < parameter.valueCount; valueIndex++) {
						decimalValue[valueIndex] = parameter.decimalValue[valueIndex];
					}
					break;
				}

				case Parameter.TYPE_OPTION: {
					optionValue = new boolean[parameter.valueCount];
					for (valueIndex = 0; valueIndex < parameter.valueCount; valueIndex++) {
						optionValue[valueIndex] = parameter.optionValue[valueIndex];
					}
					break;
				}

				case Parameter.TYPE_PICKFROM: {
					integerValue = new int[parameter.valueCount];
					pickfromIndex = new int[parameter.valueCount];
					for (valueIndex = 0; valueIndex < parameter.valueCount; valueIndex++) {
						integerValue[valueIndex] = parameter.integerValue[valueIndex];
						pickfromIndex[valueIndex] = parameter.pickfromIndex[valueIndex];
					}
					break;
				}

				case Parameter.TYPE_DATE: {
					dateValue = new java.util.Date[parameter.valueCount];
					for (valueIndex = 0; valueIndex < parameter.valueCount; valueIndex++) {
						dateValue[valueIndex] = parameter.dateValue[valueIndex];
					}
					break;
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Make a copy.

	public ParameterEditData copy() {

		ParameterEditData theCopy = new ParameterEditData(parameter, isLocked);

		int valueIndex;
		for (valueIndex = 0; valueIndex < parameter.valueCount; valueIndex++) {
			theCopy.value[valueIndex] = value[valueIndex];
		}

		if (parameter.isTable) {

			int tableSize = parameter.tableRowLabels.length * parameter.tableColumnLabels.length, tableIndex;

			switch (parameter.type) {

				case Parameter.TYPE_INTEGER: {
					for (valueIndex = 0; valueIndex < parameter.valueCount; valueIndex++) {
						for (tableIndex = 0; tableIndex < tableSize; tableIndex++) {
							theCopy.integerTableValue[valueIndex][tableIndex] =
								integerTableValue[valueIndex][tableIndex];
						}
					}
					break;
				}

				case Parameter.TYPE_DECIMAL: {
					for (valueIndex = 0; valueIndex < parameter.valueCount; valueIndex++) {
						for (tableIndex = 0; tableIndex < tableSize; tableIndex++) {
							theCopy.decimalTableValue[valueIndex][tableIndex] =
								decimalTableValue[valueIndex][tableIndex];
						}
					}
					break;
				}
			}

		} else {

			switch (parameter.type) {

				case Parameter.TYPE_INTEGER: {
					for (valueIndex = 0; valueIndex < parameter.valueCount; valueIndex++) {
						theCopy.integerValue[valueIndex] = integerValue[valueIndex];
					}
					break;
				}

				case Parameter.TYPE_DECIMAL: {
					for (valueIndex = 0; valueIndex < parameter.valueCount; valueIndex++) {
						theCopy.decimalValue[valueIndex] = decimalValue[valueIndex];
					}
					break;
				}

				case Parameter.TYPE_OPTION: {
					for (valueIndex = 0; valueIndex < parameter.valueCount; valueIndex++) {
						theCopy.optionValue[valueIndex] = optionValue[valueIndex];
					}
					break;
				}

				case Parameter.TYPE_PICKFROM: {
					for (valueIndex = 0; valueIndex < parameter.valueCount; valueIndex++) {
						theCopy.integerValue[valueIndex] = integerValue[valueIndex];
						theCopy.pickfromIndex[valueIndex] = pickfromIndex[valueIndex];
					}
					break;
				}

				case Parameter.TYPE_DATE: {
					for (valueIndex = 0; valueIndex < parameter.valueCount; valueIndex++) {
						theCopy.dateValue[valueIndex] = dateValue[valueIndex];
					}
					break;
				}
			}
		}

		return theCopy;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Test if the value has been changed.  If the underlying parameter value had defaults applied when it was loaded
	// this will always return true, the parameter values are incomplete in the database and it must be saved.  Saving
	// the default values to each study database allows future changes to the defaults without altering past study
	// results.  Note for that reason a parameter in a template-locked study may need to be saved, but if no defaults
	// were applied a locked parameter will never be saved.

	public boolean isDataChanged() {

		if (parameter.defaultsApplied) {
			return true;
		}

		if (isLocked) {
			return false;
		}

		for (int valueIndex = 0; valueIndex < parameter.valueCount; valueIndex++) {
			if (!value[valueIndex].equals(parameter.value[valueIndex])) {
				return true;
			}
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Reset a value to default.  Assume the default value strings are always valid.  Do nothing if locked.

	public void setDefaultValue(int valueIndex) {

		if (isLocked) {
			return;
		}

		value[valueIndex] = parameter.defaultValue[valueIndex];

		if (parameter.isTable) {

			int tableSize = parameter.tableRowLabels.length * parameter.tableColumnLabels.length, tableIndex;

			String[] tableValues = value[valueIndex].split(",");

			for (tableIndex = 0; tableIndex < tableValues.length; tableIndex++) {

				switch (parameter.type) {

					case Parameter.TYPE_INTEGER: {
						try {
							integerTableValue[valueIndex][tableIndex] = Integer.parseInt(tableValues[tableIndex]);
						} catch (NumberFormatException ne) {
							integerTableValue[valueIndex][tableIndex] = 0;
						}
						break;
					}

					case Parameter.TYPE_DECIMAL: {
						try {
							decimalTableValue[valueIndex][tableIndex] = Double.parseDouble(tableValues[tableIndex]);
						} catch (NumberFormatException ne) {
							decimalTableValue[valueIndex][tableIndex] = 0.;
						}
						break;
					}
				}
			}

			for (; tableIndex < tableSize; tableIndex++) {

				switch (parameter.type) {

					case Parameter.TYPE_INTEGER: {
						integerTableValue[valueIndex][tableIndex] = 0;
						break;
					}

					case Parameter.TYPE_DECIMAL: {
						decimalTableValue[valueIndex][tableIndex] = 0.;
						break;
					}
				}
			}

		
		} else {

			switch (parameter.type) {

				case Parameter.TYPE_INTEGER: {
					try {
						integerValue[valueIndex] = Integer.parseInt(value[valueIndex]);
					} catch (NumberFormatException ne) {
						integerValue[valueIndex] = 0;
					}
					break;
				}

				case Parameter.TYPE_DECIMAL: {
					try {
						decimalValue[valueIndex] = Double.parseDouble(value[valueIndex]);
					} catch (NumberFormatException ne) {
						decimalValue[valueIndex] = 0.;
					}
					break;
				}

				case Parameter.TYPE_OPTION: {
					optionValue[valueIndex] = value[valueIndex].equals("1");
					break;
				}

				case Parameter.TYPE_PICKFROM: {
					try {
						integerValue[valueIndex] = Integer.parseInt(value[valueIndex]);
						int i;
						for (i = parameter.pickfromItems.size() - 1; i > 0; i--) {
							if (integerValue[valueIndex] == parameter.pickfromItems.get(i).key) {
								break;
							}
						}
						pickfromIndex[valueIndex] = i;
					} catch (NumberFormatException ne) {
						pickfromIndex[valueIndex] = 0;
					}
					break;
				}

				case Parameter.TYPE_DATE: {
					if (value[valueIndex].length() > 0) {
						dateValue[valueIndex] = AppCore.parseDate(value[valueIndex]);
						if (null == dateValue[valueIndex]) {
							dateValue[valueIndex] = new java.util.Date(0);
						}
					} else {
						dateValue[valueIndex] = null;
					}
					break;
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called after data save, make state un-edited with current data.

	public void didSave() {

		parameter = new Parameter(parameter.key, parameter.name, parameter.valueName, parameter.units, value,
			parameter.defaultValue, parameter.description, parameter.groupName, parameter.enablesGroup,
			parameter.isScenario, parameter.isTable, parameter.tableRowLabels, parameter.tableColumnLabels,
			parameter.type, parameter.minIntegerValue, parameter.maxIntegerValue, parameter.minDecimalValue,
			parameter.maxDecimalValue, parameter.pickfromItems, integerTableValue, decimalTableValue, integerValue,
			decimalValue, optionValue, pickfromIndex, dateValue);
	}
}
