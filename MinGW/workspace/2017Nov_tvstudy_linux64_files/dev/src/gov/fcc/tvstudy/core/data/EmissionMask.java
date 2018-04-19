//
//  EmissionMask.java
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.data;

import gov.fcc.tvstudy.core.*;

import java.util.*;
import java.sql.*;


//=====================================================================================================================
// Represents records from the emission_mask table, and provides caching data factory methods.

public class EmissionMask extends KeyedRecord {

	// Database properties:

	// key (super)       Emission mask key, unique, always > 0.
	// name (super)      Mask name, never null or empty.
	// emissionMaskCode  Abbreviation code used in external station data, never null, unique.

	public final String emissionMaskCode;


	//-----------------------------------------------------------------------------------------------------------------

	public EmissionMask(int theKey, String theName, String theEmissionMaskCode) {

		super(theKey, theName);

		emissionMaskCode = theEmissionMaskCode;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Accessor for individual records.

	public static EmissionMask getEmissionMask(int theKey) {

		if (0 == theKey) {
			return nullObject;
		}

		for (EmissionMask theMask : recordCache) {
			if (theKey == theMask.key) {
				return theMask;
			}
		}
		return nullObject;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static EmissionMask getEmissionMask(String theEmissionMaskCode) {

		for (EmissionMask theMask : recordCache) {
			if (theMask.emissionMaskCode.equalsIgnoreCase(theEmissionMaskCode)) {
				return theMask;
			}
		}
		return nullObject;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// See comments in ChannelBand for details.

	public static EmissionMask getNullObject() {

		return nullObject;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static EmissionMask getInvalidObject() {

		return invalidObject;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Because an emission mask property is required, under some conditions applying a reasonable default to non-
	// editable records is necessary.  The default object is identified during the table load, if that fails return
	// the null object.  That could cause validation failures, but it should because in that case the root db is bad.

	public static EmissionMask getDefaultObject() {

		return defaultObject;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a list of all masks demoted to KeyedRecord for menus.

	public static ArrayList<KeyedRecord> getEmissionMasks() {

		ArrayList<KeyedRecord> result = new ArrayList<KeyedRecord>();

		for (EmissionMask theMask : recordCache) {
			result.add((KeyedRecord)theMask);
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a list of all masks with the null object.

	public static ArrayList<KeyedRecord> getEmissionMasksWithNull() {

		ArrayList<KeyedRecord> result = new ArrayList<KeyedRecord>();

		result.add(nullObject);

		for (EmissionMask theMask : recordCache) {
			result.add((KeyedRecord)theMask);
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a list of all masks as actual class.

	public static ArrayList<EmissionMask> getAllEmissionMasks() {

		return new ArrayList<EmissionMask>(recordCache);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Fill the cache of emission mask objects.

	private static EmissionMask nullObject = new EmissionMask(0, "(n/a)", "");
	private static EmissionMask invalidObject = new EmissionMask(-1, "???", "?");
	private static EmissionMask defaultObject = nullObject;
	private static ArrayList<EmissionMask> recordCache = new ArrayList<EmissionMask>();

	public static void loadCache(DbConnection db) throws SQLException {

		recordCache.clear();

		db.query(
		"SELECT " +
			"emission_mask_key, " +
			"name, " +
			"emission_mask_code, " +
			"is_default " +
		"FROM " +
			"emission_mask " +
		"ORDER BY 1");

		EmissionMask theMask;

		while (db.next()) {

			theMask = new EmissionMask(
				db.getInt(1),
				db.getString(2),
				db.getString(3));

			if (db.getBoolean(4)) {
				defaultObject = theMask;
			}

			recordCache.add(theMask);
		}
	}
}
