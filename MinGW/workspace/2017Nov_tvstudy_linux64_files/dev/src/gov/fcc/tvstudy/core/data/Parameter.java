//
//  Parameter.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.data;

import gov.fcc.tvstudy.core.*;

import java.util.*;
import java.sql.*;


//=====================================================================================================================
// Data representation class for a study parameter.  This also provides properties that represent interpretations of
// the parameter value based on the type definition.

public class Parameter extends KeyedRecord {

	// Maximum value count for multi-value parameters.

	public static final int MAX_VALUE_COUNT = 6;

	// Keys and some values for parameters needed in the UI code, see e.g. StudyEditData.  These must be in sync with
	// the database; the names used here also match those used in the study engine C code, for consistency.  This is
	// not a complete list of keys, the complete list is in the engine code header file.

	public static final int PARAM_GRID_TYPE = 2;
	public static final int PARAM_CELL_SIZE = 4;
	public static final int PARAM_TRUST_DA_FLAG = 18;
	public static final int PARAM_PATH_TERR_RES = 32;
	public static final int PARAM_EARTH_SPH_DIST = 200;
	public static final int PARAM_MAX_DIST = 215;
	public static final int PARAM_CL_VLO_DTV = 80;
	public static final int PARAM_CL_VHI_DTV = 82;
	public static final int PARAM_CL_UHF_DTV = 84;
	public static final int PARAM_CL_VLO_DTV_LP = 86;
	public static final int PARAM_CL_VHI_DTV_LP = 88;
	public static final int PARAM_CL_UHF_DTV_LP = 90;
	public static final int PARAM_CL_VLO_NTSC = 92;
	public static final int PARAM_CL_VHI_NTSC = 94;
	public static final int PARAM_CL_UHF_NTSC = 96;
	public static final int PARAM_CL_VLO_NTSC_LP = 98;
	public static final int PARAM_CL_VHI_NTSC_LP = 100;
	public static final int PARAM_CL_UHF_NTSC_LP = 102;
	public static final int PARAM_USE_DIPOLE_CL = 104;
	public static final int PARAM_DIPOLE_CENTER_CL = 106;
	public static final int PARAM_CURV_DTV = 107;
	public static final int PARAM_CURV_NTSC = 108;
	public static final int PARAM_CL_FM = 357;
	public static final int PARAM_CL_FM_B = 358;
	public static final int PARAM_CL_FM_B1 = 359;
	public static final int PARAM_CL_FM_ED = 360;
	public static final int PARAM_CL_FM_LP = 361;
	public static final int PARAM_CL_FM_TX = 362;
	public static final int PARAM_CURV_FM = 363;
	public static final int PARAM_TV6_FM_DIST = 244;
	public static final int PARAM_TV6_FM_DIST_DTV = 242;
	public static final int PARAM_RUL_EXT_DST_L = 335;
	public static final int PARAM_RUL_EXT_ERP_L = 336;
	public static final int PARAM_RUL_EXT_DST_LM = 337;
	public static final int PARAM_RUL_EXT_ERP_M = 338;
	public static final int PARAM_RUL_EXT_DST_MH = 339;
	public static final int PARAM_RUL_EXT_ERP_H = 340;
	public static final int PARAM_RUL_EXT_DST_H = 210;
	public static final int PARAM_RUL_EXT_MAX = 189;
	public static final int PARAM_CO_CHAN_MX_DIST = 212;
	public static final int PARAM_GEN_VPAT = 219;
	public static final int PARAM_MEX_ERP_D_VLO = 220;
	public static final int PARAM_MEX_HAAT_D_VLO = 221;
	public static final int PARAM_MEX_ERP_D_VHI = 222;
	public static final int PARAM_MEX_HAAT_D_VHI = 223;
	public static final int PARAM_MEX_ERP_D_UHF = 224;
	public static final int PARAM_MEX_HAAT_D_UHF = 225;
	public static final int PARAM_MEX_ERP_N_VLO = 226;
	public static final int PARAM_MEX_HAAT_N_VLO = 227;
	public static final int PARAM_MEX_ERP_N_VHI = 228;
	public static final int PARAM_MEX_HAAT_N_VHI = 229;
	public static final int PARAM_MEX_ERP_N_UHF = 230;
	public static final int PARAM_MEX_HAAT_N_UHF = 231;
	public static final int PARAM_MIN_CHANNEL = 324;
	public static final int PARAM_MAX_CHANNEL = 325;
	public static final int PARAM_DTS_DIST_CHECK = 326;
	public static final int PARAM_MEX_ERP_FM_A = 232;
	public static final int PARAM_MEX_HAAT_FM_A = 233;
	public static final int PARAM_MEX_ERP_FM_B = 234;
	public static final int PARAM_MEX_HAAT_FM_B = 235;
	public static final int PARAM_MEX_ERP_FM_B1 = 236;
	public static final int PARAM_MEX_HAAT_FM_B1 = 237;
	public static final int PARAM_MEX_ERP_FM_C = 238;
	public static final int PARAM_MEX_HAAT_FM_C = 239;
	public static final int PARAM_MEX_ERP_FM_C1 = 240;
	public static final int PARAM_MEX_HAAT_FM_C1 = 241;
	public static final int PARAM_WL_CULL_DIST = 247;
	public static final int PARAM_SCEN_WL_FREQ = 355;
	public static final int PARAM_SCEN_WL_BW = 356;

	public static final int GRID_TYPE_LOCAL = 1;
	public static final int GRID_TYPE_GLOBAL = 2;

	public static final int CURVE_FCC_F50 = 1;
	public static final int CURVE_FCC_F10 = 2;
	public static final int CURVE_FCC_F90 = 3;

	public static final int WL_OVERLAP_COUNT = 6;

	public static final int WL_CULL_HAAT_COUNT = 8;
	public static final double[] wirelessCullHAAT = {
		305., 200., 150., 100., 80., 65., 50., 35.
	};

	public static final int WL_CULL_ERP_COUNT = 9;
	public static final double[] wirelessCullERP = {
		5., 4., 3., 2., 1., 0.75, 0.5, 0.25, 0.1
	};

	public static final double MIN_CELL_SIZE = 0.1;
	public static final double MAX_CELL_SIZE = 10.;

	public static final double MIN_PATH_TERR_RES = 1.;
	public static final double MAX_PATH_TERR_RES = 50.;

	// Database properties:

	// key (super)   Parameter key, globally unique and always > 0.
	// name (super)  Name, never null or empty.

	// valueCount    Number of values in parameter.
	// valueName     Value name(s).
	// units         Value units label, may be blank.
	// value         Value(s), some or all may be null.
	// defaultValue  Default value(s), these are never null.

	// description   Description, never null but may be empty.
	// groupName     Used to group related parameters in the UI.
	// enablesGroup  True if option parameter enables/disables all subsequent parameters in the same group.  Note an
	//               enabling parameter must be single-value.
	// isScenario    True if this is a scenario parameter.

	public final int valueCount;
	public final String[] valueName;
	public final String units;
	public final String[] value;
	public final String[] defaultValue;

	public final String description;
	public final String groupName;
	public final boolean enablesGroup;
	public final boolean isScenario;

	// Properties for use by the editing UI, derived from the value and the type description in the database record.

	public static final int TYPE_STRING = 1;
	public static final int TYPE_INTEGER = 2;
	public static final int TYPE_DECIMAL = 3;
	public static final int TYPE_OPTION = 4;
	public static final int TYPE_PICKFROM = 5;
	public static final int TYPE_DATE = 6;

	public final boolean isTable;
	public final String[] tableRowLabels;
	public final String[] tableColumnLabels;

	public final int type;

	public final int minIntegerValue;
	public final int maxIntegerValue;

	public final double minDecimalValue;
	public final double maxDecimalValue;

	public final ArrayList<KeyedRecord> pickfromItems;

	public final int[][] integerTableValue;
	public final double[][] decimalTableValue;

	public final int[] integerValue;
	public final double[] decimalValue;
	public final boolean[] optionValue;
	public final int[] pickfromIndex;
	public final java.util.Date[] dateValue;

	// This flag is true if default values were set in checkValues().  In that case the parameter will be saved to the
	// database whether values are edited or not, so the study database always has values for all parameters even if
	// those are the current defaults.  That allows defaults to be changed later without affecting existing studies.

	public boolean defaultsApplied;


	//-----------------------------------------------------------------------------------------------------------------
	// This constructor is used when updating to as-saved data.  It does not check values or apply defaults like the
	// other constructor below or follow-up method checkValues() which are used whe reading parameters from the
	// database.  This assumes the value and default value arrays are complete and correct.  See ParameterEditData.

	public Parameter(int theKey, String theName, String[] theValueName, String theUnits, String[] theValue,
			String[] theDefaultValue, String theDescription, String theGroupName, boolean theEnablesGroup,
			boolean isScen, boolean theIsTable, String[] theTableRowLabels, String[] theTableColumnLabels, int theType,
			int theMinIntegerValue, int theMaxIntegerValue, double theMinDecimalValue, double theMaxDecimalValue,
			ArrayList<KeyedRecord> thePickfromItems, int[][] theIntegerTableValue, double[][] theDecimalTableValue,
			int[] theIntegerValue, double[] theDecimalValue, boolean[] theOptionValue, int[] thePickfromIndex,
			java.util.Date[] theDateValue) {

		super(theKey, theName);

		valueCount = theValueName.length;

		valueName = new String[valueCount];
		units = theUnits;
		value = new String[valueCount];
		defaultValue = new String[valueCount];

		int valueIndex;
		for (valueIndex = 0; valueIndex < valueCount; valueIndex++) {
			valueName[valueIndex] = theValueName[valueIndex];
			value[valueIndex] = theValue[valueIndex];
			defaultValue[valueIndex] = theDefaultValue[valueIndex];
		}

		description = theDescription;
		groupName = theGroupName;
		enablesGroup = (theEnablesGroup && (1 == valueCount) && (TYPE_OPTION == theType));
		isScenario = isScen;

		isTable = theIsTable;
		tableRowLabels = theTableRowLabels;
		tableColumnLabels = theTableColumnLabels;

		type = theType;

		minIntegerValue = theMinIntegerValue;
		maxIntegerValue = theMaxIntegerValue;

		minDecimalValue = theMinDecimalValue;
		maxDecimalValue = theMaxDecimalValue;

		pickfromItems = thePickfromItems;

		// Allocate new arrays and copy values.

		int[][] newIntegerTableValue = null;
		double[][] newDecimalTableValue = null;
		int[] newIntegerValue = null;
		double[] newDecimalValue = null;
		boolean[] newOptionValue = null;
		int[] newPickfromIndex = null;
		java.util.Date[] newDateValue = null;

		if (isTable) {

			int tableSize = tableRowLabels.length * tableColumnLabels.length, tableIndex;

			switch (type) {

				case TYPE_INTEGER: {
					newIntegerTableValue = new int[valueCount][tableSize];
					for (valueIndex = 0; valueIndex < valueCount; valueIndex++) {
						for (tableIndex = 0; tableIndex < tableSize; tableIndex++) {
							newIntegerTableValue[valueIndex][tableIndex] = theIntegerTableValue[valueIndex][tableIndex];
						}
					}
					break;
				}

				case TYPE_DECIMAL: {
					newDecimalTableValue = new double[valueCount][tableSize];
					for (valueIndex = 0; valueIndex < valueCount; valueIndex++) {
						for (tableIndex = 0; tableIndex < tableSize; tableIndex++) {
							newDecimalTableValue[valueIndex][tableIndex] = theDecimalTableValue[valueIndex][tableIndex];
						}
					}
					break;
				}
			}

		} else {

			switch (type) {

				case TYPE_INTEGER: {
					newIntegerValue = new int[valueCount];
					for (valueIndex = 0; valueIndex < valueCount; valueIndex++) {
						newIntegerValue[valueIndex] = theIntegerValue[valueIndex];
					}
					break;
				}

				case TYPE_DECIMAL: {
					newDecimalValue = new double[valueCount];
					for (valueIndex = 0; valueIndex < valueCount; valueIndex++) {
						newDecimalValue[valueIndex] = theDecimalValue[valueIndex];
					}
					break;
				}

				case TYPE_OPTION: {
					newOptionValue = new boolean[valueCount];
					for (valueIndex = 0; valueIndex < valueCount; valueIndex++) {
						newOptionValue[valueIndex] = theOptionValue[valueIndex];
					}
					break;
				}

				case TYPE_PICKFROM: {
					newIntegerValue = new int[valueCount];
					newPickfromIndex = new int[valueCount];
					for (valueIndex = 0; valueIndex < valueCount; valueIndex++) {
						newIntegerValue[valueIndex] = theIntegerValue[valueIndex];
						newPickfromIndex[valueIndex] = thePickfromIndex[valueIndex];
					}
					break;
				}

				case TYPE_DATE: {
					newDateValue = new java.util.Date[valueCount];
					for (valueIndex = 0; valueIndex < valueCount; valueIndex++) {
						newDateValue[valueIndex] = theDateValue[valueIndex];
					}
					break;
				}
			}
		}

		integerTableValue = newIntegerTableValue;
		decimalTableValue = newDecimalTableValue;
		integerValue = newIntegerValue;
		decimalValue = newDecimalValue;
		optionValue = newOptionValue;
		pickfromIndex = newPickfromIndex;
		dateValue = newDateValue;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Convenience to make a deep copy of a parameter using the previous constructor.  See getScenarioParameters().

	private Parameter copy() {

		return new Parameter(key, name, valueName, units, value, defaultValue, description, groupName, enablesGroup,
			isScenario, isTable, tableRowLabels, tableColumnLabels, type, minIntegerValue, maxIntegerValue,
			minDecimalValue, maxDecimalValue, pickfromItems, integerTableValue, decimalTableValue, integerValue,
			decimalValue, optionValue, pickfromIndex, dateValue);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This constructor parses a type-definition string and value name list from the database table to define the
	// parameter structure and all type-related properties.  This does not complain about errors in the type or value
	// name list syntax, it will just use appropriate defaults.  Also this does not set any of the value, defaultValue,
	// or type-specific array content.  The value and defaultValue arrays must be populated by direct assignment,
	// followed by a call to checkValues() to check values, apply defaults, and parse as needed.

	private Parameter(int theKey, String theName, String theUnits, String theDescription, String theGroupName,
			int enablingKey, boolean isScen, String theTypeDefinition, String theValueNameList) {

		super(theKey, theName);

		units = theUnits;
		description = theDescription;
		groupName = theGroupName;
		isScenario = isScen;

		// Parse the type string, default type is string if definition is empty or unknown (or "string").

		boolean theIsTable = false;
		String[] theRowLabels = null;
		String[] theColLabels = null;

		int theType = TYPE_STRING;

		int theMinIntegerValue = 0;
		int theMaxIntegerValue = 0;

		double theMinDecimalValue = 0.;
		double theMaxDecimalValue = 0.;

		ArrayList<KeyedRecord> thePickfromItems = null;

		String[] tokens = theTypeDefinition.split(":");
		int tokenCount = tokens.length, tokenIndex = 0;

		String typename = tokens[tokenIndex++].trim().toLowerCase();

		// The table type is an overlay, it is followed by row and column counts and labels and then a normal value
		// type declaration as for non-table parameters.  That type must be integer or decimal.  First check the counts
		// and labels and be sure there is one more token for the actual type, if anything is wrong the parameter
		// just reverts to a generic string type since any parameter can be managed as such in the editor.

		if (typename.equals("table")) {

			int rowCount = 0, colCount = 0, startRowIndex = 0, startColIndex = 0, i, j;

			if ((tokenIndex < tokenCount) && (tokens[tokenIndex].length() > 0)) {
				try {
					rowCount = Integer.parseInt(tokens[tokenIndex]);
				} catch (NumberFormatException ne) {
				}
				tokenIndex++;

				if (rowCount > 0) {
					startRowIndex = tokenIndex;
					tokenIndex += rowCount;

					if ((tokenIndex < tokenCount) && (tokens[tokenIndex].length() > 0)) {
						try {
							colCount = Integer.parseInt(tokens[tokenIndex]);
						} catch (NumberFormatException ne) {
						}
						tokenIndex++;

						if (colCount > 0) {
							startColIndex = tokenIndex;
							tokenIndex += colCount;

							if (tokenIndex < tokenCount) {

								theIsTable = true;
								typename = tokens[tokenIndex++].trim().toLowerCase();

								theRowLabels = new String[rowCount];
								for (i = 0, j = startRowIndex; i < rowCount; i++, j++) {
									theRowLabels[i] = tokens[j];
								}

								theColLabels = new String[colCount];
								for (i = 0, j = startColIndex; i < colCount; i++, j++) {
									theColLabels[i] = tokens[j];
								}
							}
						}
					}
				}
			}
		}

		// Integer value.  May include minimum and maximum limits.

		if (typename.equals("integer")) {

			theType = TYPE_INTEGER;

			if ((tokenIndex < tokenCount) && (tokens[tokenIndex].length() > 0)) {
				try {
					theMinIntegerValue = Integer.parseInt(tokens[tokenIndex]);
				} catch (NumberFormatException ne) {
					theMinIntegerValue = Integer.MIN_VALUE;
				}
				tokenIndex++;
			} else {
				theMinIntegerValue = Integer.MIN_VALUE;
			}

			if ((tokenIndex < tokenCount) && (tokens[tokenIndex].length() > 0)) {
				try {
					theMaxIntegerValue = Integer.parseInt(tokens[tokenIndex]);
				} catch (NumberFormatException ne) {
					theMaxIntegerValue = Integer.MAX_VALUE;
				}
				tokenIndex++;
			} else {
				theMaxIntegerValue = Integer.MAX_VALUE;
			}
		}

		// Decimal value, double-precision floating-point.  May include minimum and maximum limits.

		if (typename.equals("decimal")) {

			theType = TYPE_DECIMAL;

			if ((tokenIndex < tokenCount) && (tokens[tokenIndex].length() > 0)) {
				try {
					theMinDecimalValue = Double.parseDouble(tokens[tokenIndex]);
				} catch (NumberFormatException ne) {
					theMinDecimalValue = Double.MIN_VALUE;
				}
				tokenIndex++;
			} else {
				theMinDecimalValue = Double.MIN_VALUE;
			}

			if ((tokenIndex < tokenCount) && (tokens[tokenIndex].length() > 0)) {
				try {
					theMaxDecimalValue = Double.parseDouble(tokens[tokenIndex]);
				} catch (NumberFormatException ne) {
					theMaxDecimalValue = Double.MAX_VALUE;
				}
				tokenIndex++;
			} else {
				theMaxDecimalValue = Double.MAX_VALUE;
			}
		}

		// Option value, true/false flag.  Stored in the database as "0" or "1".

		if (typename.equals("option")) {

			theType = TYPE_OPTION;
		}

		// Pick-from list.  The value is an integer chosen from a list of (value, name) items in the type string.

		if (typename.equals("pickfrom")) {

			thePickfromItems = new ArrayList<KeyedRecord>();
			int itemKey = 0;
			while ((tokenIndex + 1) < tokenCount) {
				try {
					itemKey = Integer.parseInt(tokens[tokenIndex]);
				} catch (NumberFormatException ne) {
					tokenIndex += 2;
					continue;
				}
				thePickfromItems.add(new KeyedRecord(itemKey, tokens[tokenIndex + 1]));
				tokenIndex += 2;
			}

			if (thePickfromItems.isEmpty()) {
				thePickfromItems = null;
			} else {
				theType = TYPE_PICKFROM;
			}
		}

		// Date value.

		if (typename.equals("date")) {

			theType = TYPE_DATE;
		}

		// A table must be integer or decimal, if not revert to a generic string parameter.

		if (theIsTable && (TYPE_INTEGER != theType) && (TYPE_DECIMAL != theType)) {

			theIsTable = false;
			theRowLabels = null;
			theColLabels = null;

			theType = TYPE_STRING;

			thePickfromItems = null;
		}

		// Parse the value name list, set the value count.  The name for a single-value paramter is not used in the
		// UI (although it does appear in the parameter summary) so that may be, and usually is, an empty string.

		valueName = theValueNameList.split(":");
		valueCount = valueName.length;
		value = new String[valueCount];
		defaultValue = new String[valueCount];

		// Final assignments.  Group-enabling parameters must be option type and single-valued.

		enablesGroup = ((theKey == enablingKey) && (1 == valueCount) && (TYPE_OPTION == theType));

		isTable = theIsTable;
		tableRowLabels = theRowLabels;
		tableColumnLabels = theColLabels;

		type = theType;

		minIntegerValue = theMinIntegerValue;
		maxIntegerValue = theMaxIntegerValue;

		minDecimalValue = theMinDecimalValue;
		maxDecimalValue = theMaxDecimalValue;

		pickfromItems = thePickfromItems;

		defaultsApplied = false;

		// Allocate the type-specific storage arrays as needed for the type.

		int[][] newIntegerTableValue = null;
		double[][] newDecimalTableValue = null;
		int[] newIntegerValue = null;
		double[] newDecimalValue = null;
		boolean[] newOptionValue = null;
		int[] newPickfromIndex = null;
		java.util.Date[] newDateValue = null;

		if (isTable) {

			int tableSize = tableRowLabels.length * tableColumnLabels.length;

			switch (type) {

				case TYPE_INTEGER: {
					newIntegerTableValue = new int[valueCount][tableSize];
					break;
				}

				case TYPE_DECIMAL: {
					newDecimalTableValue = new double[valueCount][tableSize];
					break;
				}
			}

		} else {

			switch (type) {

				case TYPE_INTEGER: {
					newIntegerValue = new int[valueCount];
					break;
				}

				case TYPE_DECIMAL: {
					newDecimalValue = new double[valueCount];
					break;
				}

				case TYPE_OPTION: {
					newOptionValue = new boolean[valueCount];
					break;
				}

				case TYPE_PICKFROM: {
					newIntegerValue = new int[valueCount];
					newPickfromIndex = new int[valueCount];
					break;
				}

				case TYPE_DATE: {
					newDateValue = new java.util.Date[valueCount];
					break;
				}
			}
		}

		integerTableValue = newIntegerTableValue;
		decimalTableValue = newDecimalTableValue;
		integerValue = newIntegerValue;
		decimalValue = newDecimalValue;
		optionValue = newOptionValue;
		pickfromIndex = newPickfromIndex;
		dateValue = newDateValue;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check the set of values for completeness, including defaults, and parse the value into type-specific properties.
	// This is used along with the second constructor form above, called after the value[] and defaultValue[] arrays
	// are populated by direct assignment.  If anything goes wrong including parse errors this returns false, the
	// parameter is bad and should be discarded.  First check default values, all must be non-null.

	private boolean checkValues() {

		int valueIndex;

		for (valueIndex = 0; valueIndex < valueCount; valueIndex++) {
			if (null == defaultValue[valueIndex]) {
				return false;
			}
		}

		// Special rule, if the parameter is multi-value but only a single value at index 0 is non-null, that value is
		// automatically copied down to all other indices.  This allows single-value parameters to be redefined as
		// multi-value without breaking existing studies or future import of older XML data.

		if ((valueCount > 1) && (null != value[0]) && (null == value[1])) {
			for (valueIndex = 2; valueIndex < valueCount; valueIndex++) {
				if (null != value[valueIndex]) {
					break;
				}
			}
			if (valueIndex >= valueCount) {
				for (valueIndex = 1; valueIndex < valueCount; valueIndex++) {
					value[valueIndex] = value[0];
				}
				defaultsApplied = true;
			}
		}

		// Apply defaults if needed, if any one index in a multi-value parameter is null defaults are applied to all.
		// If defaults are set the defaultsApplied flag is true so the parameter is saved regardless of edits.

		for (valueIndex = 0; valueIndex < valueCount; valueIndex++) {
			if (null == value[valueIndex]) {
				break;
			}
		}
		if (valueIndex < valueCount) {
			for (valueIndex = 0; valueIndex < valueCount; valueIndex++) {
				value[valueIndex] = defaultValue[valueIndex];
			}
			defaultsApplied = true;
		}

		// Finally, parse the values to set the type-specific properties.  For a table parameter, the individual table
		// values are concatenated in the parameter value separated by commas, in row-major order.  If such a value
		// list is short that is a fatal error.  If it is long, extra values are ignored.

		if (isTable) {

			int tableSize = tableRowLabels.length * tableColumnLabels.length, tableIndex;
			String[] tableValues;

			for (valueIndex = 0; valueIndex < valueCount; valueIndex++) {

				tableValues = value[valueIndex].split(",");
				if (tableValues.length < tableSize) {
					return false;
				}

				for (tableIndex = 0; tableIndex < tableSize; tableIndex++) {

					switch (type) {

						case TYPE_INTEGER: {
							try {
								integerTableValue[valueIndex][tableIndex] = Integer.parseInt(tableValues[tableIndex]);
							} catch (NumberFormatException ne) {
								return false;
							}
							break;
						}

						case TYPE_DECIMAL: {
							try {
								decimalTableValue[valueIndex][tableIndex] = Double.parseDouble(tableValues[tableIndex]);
							} catch (NumberFormatException ne) {
								return false;
							}
							break;
						}
					}
				}
			}

		} else {

			for (valueIndex = 0; valueIndex < valueCount; valueIndex++) {

				switch (type) {

					case TYPE_INTEGER: {
						try {
							integerValue[valueIndex] = Integer.parseInt(value[valueIndex]);
						} catch (NumberFormatException ne) {
							return false;
						}
						break;
					}

					case TYPE_DECIMAL: {
						try {
							decimalValue[valueIndex] = Double.parseDouble(value[valueIndex]);
						} catch (NumberFormatException ne) {
							return false;
						}
						break;
					}

					case TYPE_OPTION: {
						optionValue[valueIndex] = value[valueIndex].equals("1");
						break;
					}

					case TYPE_PICKFROM: {
						try {
							integerValue[valueIndex] = Integer.parseInt(value[valueIndex]);
						} catch (NumberFormatException ne) {
							return false;
						}
						int i;
						for (i = pickfromItems.size() - 1; i > 0; i--) {
							if (integerValue[valueIndex] == pickfromItems.get(i).key) {
								break;
							}
						}
						pickfromIndex[valueIndex] = i;
						break;
					}

					// Dates are allowed to be null for "not set", represented by empty value strings.

					case TYPE_DATE: {
						if (value[valueIndex].length() > 0) {
							dateValue[valueIndex] = AppCore.parseDate(value[valueIndex]);
							if (null == dateValue[valueIndex]) {
								return false;
							}
						}
						break;
					}
				}
			}
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve parameters, caller provides an open database connection.  The parameter structure is always in a root
	// database with default values provided from a template, so the root database name and template key are required.
	// Optionally, actual values may be loaded from another database, typically a study database, in which case the
	// database name and study type are also provided to obtain values and filter parameters by type. This will also
	// use the default template in the root database to get defaults for parameters not otherwise set.  If the database
	// name is null that means the template itself is being edited, so the parameter values come from the template data
	// rather than those being defaults, and defaults come from the default template.  When loading the template itself
	// all parameters are loaded including scenario parameters, otherwise scenario parameters are ignored here.

	public static ArrayList<Parameter> getParameters(DbConnection db, String theDbName, String rootName,
			int templateKey, int studyType) throws SQLException {

		db.setDatabase(rootName);

		int parameterKey, valueIndex;
		Parameter theParameter;
		ArrayList<Parameter> parameterList = new ArrayList<Parameter>();
		HashMap<Integer, Parameter> parameterMap = new HashMap<Integer, Parameter>();

		String restrict = "";
		if (null != theDbName) {
			restrict =
				"JOIN parameter_study_type USING (parameter_key) " +
			"WHERE " +
				"(parameter_study_type.study_type = " + studyType + ") " +
				"AND NOT parameter.is_scenario_parameter ";
		}

		db.query(
		"SELECT " +
			"parameter.parameter_key, " +
			"parameter.name, " +
			"parameter.units, " +
			"parameter.description, " +
			"parameter_group.name, " +
			"parameter_group.enabling_parameter_key, " +
			"parameter.is_scenario_parameter, " +
			"parameter.type, " +
			"parameter.value_list " +
		"FROM " +
			"parameter " +
			"JOIN parameter_group USING (group_key) " +
		restrict +
		"ORDER BY " +
			"parameter_group.list_order, " +
			"parameter.list_order");

		while (db.next()) {
			parameterKey = db.getInt(1);
			theParameter = new Parameter(
				parameterKey,
				db.getString(2),
				db.getString(3),
				db.getString(4),
				db.getString(5),
				db.getInt(6),
				db.getBoolean(7),
				db.getString(8),
				db.getString(9));
			parameterList.add(theParameter);
			parameterMap.put(Integer.valueOf(parameterKey), theParameter);
		}

		// Obtaining the parameter values has become a bit complicated.  The current parameter values are in the named
		// database, however that may not have values for all parameters if new ones have been added since the data
		// was last saved.  In that case default values will be used.  The defaults may be in the template used to
		// create the database, but that also may not have all parameters if any have been added since the template
		// was last saved.  The final source is the default template (template_key = 1) which will always have values
		// for all parameters.  If any defaults are still null after all of this, checkValues() will fail.  Defaults
		// must always be present even if values are already set, to support the reset-to-defaults action in the UI.
		// Note the special case mentioned above when no value database is named, the template provides the actual
		// values not defaults, and defaults come only from the default template.

		if (null != theDbName) {

			db.setDatabase(theDbName);

			db.query(
			"SELECT " +
				"parameter_key, " +
				"value_index, " +
				"value " +
			"FROM " +
				"parameter_data");

			while (db.next()) {
				theParameter = parameterMap.get(Integer.valueOf(db.getInt(1)));
				if (null != theParameter) {
					valueIndex = db.getInt(2);
					if ((valueIndex >= 0) && (valueIndex < theParameter.valueCount)) {
						theParameter.value[valueIndex] = db.getString(3);
					}
				}
			}
		}

		db.setDatabase(rootName);

		db.query(
		"SELECT " +
			"parameter_key, " +
			"value_index, " +
			"value " +
		"FROM " +
			"template_parameter_data " +
		"WHERE " +
			"template_key = " + templateKey);

		while (db.next()) {
			theParameter = parameterMap.get(Integer.valueOf(db.getInt(1)));
			if (null != theParameter) {
				valueIndex = db.getInt(2);
				if ((valueIndex >= 0) && (valueIndex < theParameter.valueCount)) {
					if (null == theDbName) {
						theParameter.value[valueIndex] = db.getString(3);
					} else {
						theParameter.defaultValue[valueIndex] = db.getString(3);
					}
				}
			}
		}

		if ((templateKey > 1) || (null == theDbName)) {

			db.query(
			"SELECT " +
				"parameter_key, " +
				"value_index, " +
				"value " +
			"FROM " +
				"template_parameter_data " +
			"WHERE " +
				"template_key = 1");

			while (db.next()) {
				theParameter = parameterMap.get(Integer.valueOf(db.getInt(1)));
				if (null != theParameter) {
					valueIndex = db.getInt(2);
					if ((valueIndex >= 0) && (valueIndex < theParameter.valueCount)) {
						if (null == theParameter.defaultValue[valueIndex]) {
							theParameter.defaultValue[valueIndex] = db.getString(3);
						}
					}
				}
			}
		}

		// Call checkValues() for all parameters, remove any that fail.

		Iterator<Parameter> it = parameterList.iterator();
		while (it.hasNext()) {
			theParameter = it.next();
			if (!theParameter.checkValues()) {
				it.remove();
			}
		}
				
		return parameterList;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve the scenario parameters for a given database.  This is done in bulk for all scenarios to make the load
	// more efficient.  The set of parameters and defaults is the same for every scenario, only the values vary.

	public static HashMap<Integer, ArrayList<Parameter>> getScenarioParameters(DbConnection db, String theDbName,
			String rootName, int templateKey, int studyType) throws SQLException {

		HashMap<Integer, ArrayList<Parameter>> result = new HashMap<Integer, ArrayList<Parameter>>();

		// Get the scenario parameters for the study type, if there are none return an empty result.

		HashMap<Integer, Parameter> parameterMap = getScenarioParameterMap(db, rootName, templateKey, studyType);
		if (parameterMap.isEmpty()) {
			return result;
		}

		// Load the parameter values, parameters are copied for each scenario and values populated, checkValues() is
		// then called and if that succeeds the parameter is added to the list for a scenario.  If all parameters fail
		// checkValues() no entry will exist in the map for the scenario.

		db.setDatabase(theDbName);

		db.query(
		"SELECT " +
			"scenario.scenario_key, " +
			"parameter.parameter_key, " +
			"scenario_parameter_data.value_index, " +
			"scenario_parameter_data.value " +
		"FROM " +
			"scenario " +
			"JOIN " + rootName + ".parameter " +
			"JOIN " + rootName + ".parameter_study_type USING (parameter_key) " +
			"LEFT JOIN scenario_parameter_data ON ((scenario_parameter_data.scenario_key = scenario.scenario_key) " +
				" AND (scenario_parameter_data.parameter_key = parameter.parameter_key)) " +
		"WHERE " +
			"(parameter_study_type.study_type = " + studyType + ") " +
			"AND parameter.is_scenario_parameter " +
		"ORDER BY " +
			"1, 2, 3");

		int scenarioKey, lastScenarioKey = 0, parameterKey, lastParameterKey = 0, valueIndex;
		ArrayList<Parameter> parameterList = null;
		Parameter theParameter = null;

		while (db.next()) {

			scenarioKey = db.getInt(1);
			if (scenarioKey != lastScenarioKey) {
				if (null != parameterList) {
					if ((null != theParameter) && theParameter.checkValues()) {
						parameterList.add(theParameter);
					}
					if (!parameterList.isEmpty()) {
						result.put(Integer.valueOf(lastScenarioKey), parameterList);
					}
				}
				parameterList = new ArrayList<Parameter>();
				lastScenarioKey = scenarioKey;
				theParameter = null;
				lastParameterKey = 0;
			}

			parameterKey = db.getInt(2);
			if (parameterKey != lastParameterKey) {
				if ((null != theParameter) && theParameter.checkValues()) {
					parameterList.add(theParameter);
				}
				theParameter = parameterMap.get(Integer.valueOf(parameterKey));
				if (null != theParameter) {
					theParameter = theParameter.copy();
				}
				lastParameterKey = parameterKey;
			}

			if (null != theParameter) {
				valueIndex = db.getInt(3);
				if ((valueIndex >= 0) && (valueIndex < theParameter.valueCount)) {
					theParameter.value[valueIndex] = db.getString(4);
				}
			}
		}

		if (null != parameterList) {
			if ((null != theParameter) && theParameter.checkValues()) {
				parameterList.add(theParameter);
			}
			if (!parameterList.isEmpty()) {
				result.put(Integer.valueOf(lastScenarioKey), parameterList);
			}
		}
				
		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Return a list of scenario parameters for the study type with defaults applied, used when creating new scenarios.
	// A null return means there are no scenario parameters for the study type.

	public static ArrayList<Parameter> getDefaultScenarioParameters(DbConnection db, String rootName, int templateKey,
			int studyType) throws SQLException {

		HashMap<Integer, Parameter> theMap = getScenarioParameterMap(db, rootName, templateKey, studyType);

		ArrayList<Parameter> result = new ArrayList<Parameter>();
		for (Parameter theParameter : theMap.values()) {
			if (theParameter.checkValues()) {
				result.add(theParameter);
			}
		}

		if (result.isEmpty()) {
			return null;
		}
		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get the set of scenario parameters for a study type with defaults loaded but no values, the objects returned
	// must have checkValues() called before ultimately being used.  See previous methods.

	private static HashMap<Integer, Parameter> getScenarioParameterMap(DbConnection db, String rootName,
			int templateKey, int studyType) throws SQLException {

		db.setDatabase(rootName);

		HashMap<Integer, Parameter> result = new HashMap<Integer, Parameter>();

		db.query(
		"SELECT " +
			"parameter.parameter_key, " +
			"parameter.name, " +
			"parameter.units, " +
			"parameter.description, " +
			"parameter_group.name, " +
			"parameter_group.enabling_parameter_key, " +
			"parameter.is_scenario_parameter, " +
			"parameter.type, " +
			"parameter.value_list " +
		"FROM " +
			"parameter " +
			"JOIN parameter_group USING (group_key) " +
			"JOIN parameter_study_type USING (parameter_key) " +
		"WHERE " +
			"(parameter_study_type.study_type = " + studyType + ") " +
			"AND parameter.is_scenario_parameter " +
		"ORDER BY " +
			"parameter_group.list_order, " +
			"parameter.list_order");

		int parameterKey;

		while (db.next()) {
			parameterKey = db.getInt(1);
			result.put(Integer.valueOf(parameterKey), new Parameter(
				parameterKey,
				db.getString(2),
				db.getString(3),
				db.getString(4),
				db.getString(5),
				db.getInt(6),
				db.getBoolean(7),
				db.getString(8),
				db.getString(9)));
		}

		// If there are no per-scenario parameters return an empty map.

		if (result.isEmpty()) {
			return result;
		}

		// Load default values for the parameters.

		Parameter theParameter;
		int valueIndex;

		db.query(
		"SELECT " +
			"parameter_key, " +
			"value_index, " +
			"value " +
		"FROM " +
			"template_parameter_data " +
		"WHERE " +
			"template_key = " + templateKey);

		while (db.next()) {
			theParameter = result.get(Integer.valueOf(db.getInt(1)));
			if (null != theParameter) {
				valueIndex = db.getInt(2);
				if ((valueIndex >= 0) && (valueIndex < theParameter.valueCount)) {
					theParameter.defaultValue[valueIndex] = db.getString(3);
				}
			}
		}

		if (templateKey > 1) {

			db.query(
			"SELECT " +
				"parameter_key, " +
				"value_index, " +
				"value " +
			"FROM " +
				"template_parameter_data " +
			"WHERE " +
				"template_key = 1");

			while (db.next()) {
				theParameter = result.get(Integer.valueOf(db.getInt(1)));
				if (null != theParameter) {
					valueIndex = db.getInt(2);
					if ((valueIndex >= 0) && (valueIndex < theParameter.valueCount)) {
						if (null == theParameter.defaultValue[valueIndex]) {
							theParameter.defaultValue[valueIndex] = db.getString(3);
						}
					}
				}
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a parameter value from a template, if the template does not exist or does not provide the value, get it from
	// the default template.  A null return means the parameter key or value index were invalid.

	public static String getTemplateParameterValue(String dbID, int templateKey, int parameterKey, int valueIndex) {
		return getTemplateParameterValue(dbID, templateKey, parameterKey, valueIndex, null);
	}

	public static String getTemplateParameterValue(String dbID, int templateKey, int parameterKey, int valueIndex,
			ErrorLogger errors) {

		String result = null;

		DbConnection db = DbCore.connectDb(dbID, errors);
		if (null != db) {
			try {

				db.query(
				"SELECT " +
					"value " +
				"FROM " +
					"template_parameter_data " +
				"WHERE " +
					"(template_key = " + templateKey + ") " +
					"AND (parameter_key = " + parameterKey + ") " +
					"AND (value_index = " + valueIndex + ")");
				if (db.next()) {
					result = db.getString(1);
				} else {

					db.query(
					"SELECT " +
						"value " +
					"FROM " +
						"template_parameter_data " +
					"WHERE " +
						"(template_key = 1) " +
						"AND (parameter_key = " + parameterKey + ") " +
						"AND (value_index = " + valueIndex + ")");
					if (db.next()) {
						result = db.getString(1);
					}
				}

				DbCore.releaseDb(db);

			} catch (SQLException se) {
				DbCore.releaseDb(db);
				DbConnection.reportError(errors, se);
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Turn a list of parameter objects into a formatted text summary.  This is kept in the study table record in the
	// database for use in text reports generated by the study engine, so the engine doesn't have to deal with the UI
	// elements in the parameter table (i.e. the pick-from lists).  Note if a group-enabling parameter is seen and that
	// is false, other subsequent parameters in the same group are disabled so are not included.  See ParameterEditor.
	// Note table parameters are currently invisible; that may not ever change here.

	public static String makeParameterSummary(ArrayList<Parameter> parameters) {

		int len, vlen, tlen, maxvlen, maxtlen = 0, valueIndex, i;
		for (Parameter theParam : parameters) {
			if (theParam.isTable) {
				continue;
			}
			len = theParam.name.length();
			maxvlen = 0;
			tlen = len;
			if (theParam.valueCount > 1) {
				for (valueIndex = 0; valueIndex < theParam.valueCount; valueIndex++) {
					vlen = theParam.valueName[valueIndex].length();
					if (vlen > maxvlen) {
						maxvlen = vlen;
					}
				}
				tlen = len + maxvlen + 2;
			}
			if (tlen > maxtlen) {
				maxtlen = tlen;
			}
		}

		StringBuilder sum = new StringBuilder();
		String lastGroupName = "";
		boolean skipGroup = false;

		for (Parameter theParam : parameters) {

			if (theParam.isTable) {
				continue;
			}

			if (!theParam.groupName.equals(lastGroupName)) {
				sum.append('\n');
				sum.append(theParam.groupName);
				sum.append('\n');
				lastGroupName = theParam.groupName;
				skipGroup = false;
			}

			if (skipGroup) {
				continue;
			}

			len = theParam.name.length();
			maxvlen = 0;
			tlen = len;
			if (theParam.valueCount > 1) {
				for (valueIndex = 0; valueIndex < theParam.valueCount; valueIndex++) {
					vlen = theParam.valueName[valueIndex].length();
					if (vlen > maxvlen) {
						maxvlen = vlen;
					}
				}
				tlen = len + maxvlen + 2;
			}
			for (i = tlen; i < maxtlen; i++) {
				sum.append(' ');
			}
			sum.append(theParam.name);
			sum.append(": ");

			for (valueIndex = 0; valueIndex < theParam.valueCount; valueIndex++) {

				if (theParam.valueCount > 1) {
					vlen = theParam.valueName[valueIndex].length();
					for (i = vlen; i < maxvlen; i++) {
						sum.append(' ');
					}
					sum.append(theParam.valueName[valueIndex]);
					sum.append(": ");
				}

				switch (theParam.type) {

					case TYPE_STRING:
					case TYPE_INTEGER:
					case TYPE_DECIMAL:
					case TYPE_DATE:
					default: {
						sum.append(theParam.value[valueIndex]);
						break;
					}

					case TYPE_OPTION: {
						if (theParam.optionValue[valueIndex]) {
							sum.append("Yes");
						} else {
							sum.append("No");
							if (theParam.enablesGroup) {
								skipGroup = true;
							}
						}
						break;
					}

					case TYPE_PICKFROM: {
						sum.append(theParam.pickfromItems.get(theParam.pickfromIndex[valueIndex]).name);
						break;
					}
				}

				sum.append(' ');
				sum.append(theParam.units);

				if (valueIndex < (theParam.valueCount - 1)) {
					sum.append('\n');
					for (i = tlen; i < maxtlen; i++) {
						sum.append(' ');
					}
					for (i = 0; i < len; i++) {
						sum.append(' ');
					}
					sum.append("  ");
				}
			}

			sum.append('\n');
		}

		return sum.toString();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create the parameter data tables in a new database and set initial values from a template in a root database,
	// appropriately filtered for a specified study type.  Note the template need not be complete; if some parameters
	// don't get values from the template, the parameter default value will be applied by the editor.  If the root
	// name is null or template key is 0, no data is copied.

	public static void createTables(DbConnection db, String theDbName, String rootName, int templateKey, int studyType)
			throws SQLException {

		db.setDatabase(theDbName);

		db.update(
		"CREATE TABLE parameter_data (" +
			"parameter_key INT NOT NULL," +
			"value_index INT NOT NULL," +
			"value VARCHAR(10000) NOT NULL," +
			"PRIMARY KEY (parameter_key, value_index)" +
		")");

		db.update(
		"CREATE TABLE scenario_parameter_data (" +
			"scenario_key INT NOT NULL," +
			"parameter_key INT NOT NULL," +
			"value_index INT NOT NULL," +
			"value VARCHAR(10000) NOT NULL," +
			"PRIMARY KEY (scenario_key, parameter_key, value_index)" +
		")");

		if ((null != rootName) && (templateKey > 0)) {

			db.update(
			"INSERT INTO parameter_data (" +
				"parameter_key," +
				"value_index," +
				"value) " +
			"SELECT " +
				"template_parameter_data.parameter_key," +
				"template_parameter_data.value_index," +
				"template_parameter_data.value " +
			"FROM " +
				rootName + ".template_parameter_data " +
				"JOIN " + rootName + ".parameter USING (parameter_key) " +
				"JOIN " + rootName + ".parameter_study_type USING (parameter_key) " +
			"WHERE " +
				"template_parameter_data.template_key = " + templateKey + " " +
				"AND (parameter_study_type.study_type = " + studyType + ") " +
				"AND NOT parameter.is_scenario_parameter");
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create all tables and copy contents from another database.

	public static void copyTables(DbConnection db, String theDbName, String fromDbName) throws SQLException {

		createTables(db, theDbName, null, 0, 0);

		db.update(
		"INSERT INTO parameter_data (" +
			"parameter_key," +
			"value_index," +
			"value) " +
		"SELECT " +
			"parameter_key," +
			"value_index," +
			"value " +
		"FROM " +
			fromDbName + ".parameter_data");

		db.update(
		"INSERT INTO scenario_parameter_data (" +
			"scenario_key," +
			"parameter_key," +
			"value_index," +
			"value) " +
		"SELECT " +
			"scenario_key," +
			"parameter_key," +
			"value_index," +
			"value " +
		"FROM " +
			fromDbName + ".scenario_parameter_data");
	}
}
