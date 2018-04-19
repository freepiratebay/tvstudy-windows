//
//  FrequencyOffset.java
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.data;

import gov.fcc.tvstudy.core.*;

import java.util.*;
import java.sql.*;


//=====================================================================================================================
// Represents records from the frequency_offset table, and provides caching data factory methods.

public class FrequencyOffset extends KeyedRecord {

	// Database properties:

	// key (super)          Frequency offset key, unique, always > 0.
	// name (super)         Offset name, never null or empty.
	// frequencyOffsetCode  Single-letter code used in external station data.

	public final String frequencyOffsetCode;


	//-----------------------------------------------------------------------------------------------------------------

	public FrequencyOffset(int theKey, String theName, String theFrequencyOffsetCode) {

		super(theKey, theName);

		frequencyOffsetCode = theFrequencyOffsetCode;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Accessor for individual records.

	public static FrequencyOffset getFrequencyOffset(int theKey) {

		if (0 == theKey) {
			return nullObject;
		}

		for (FrequencyOffset theOffset : recordCache) {
			if (theKey == theOffset.key) {
				return theOffset;
			}
		}
		return nullObject;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static FrequencyOffset getFrequencyOffset(String theFrequencyOffsetCode) {

		for (FrequencyOffset theOffset : recordCache) {
			if (theOffset.frequencyOffsetCode.equalsIgnoreCase(theFrequencyOffsetCode)) {
				return theOffset;
			}
		}
		return nullObject;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static FrequencyOffset getNullObject() {

		return nullObject;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static FrequencyOffset getInvalidObject() {

		return invalidObject;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a list of all offsets.

	public static ArrayList<KeyedRecord> getFrequencyOffsets() {

		ArrayList<KeyedRecord> result = new ArrayList<KeyedRecord>();

		for (FrequencyOffset theOffset : recordCache) {
			result.add((KeyedRecord)theOffset);
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a list of all offsets including the null object.

	public static ArrayList<KeyedRecord> getFrequencyOffsetsWithNull() {

		ArrayList<KeyedRecord> result = new ArrayList<KeyedRecord>();

		result.add(nullObject);

		for (FrequencyOffset theOffset : recordCache) {
			result.add((KeyedRecord)theOffset);
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a list of all offsets as actual class objects.

	public static ArrayList<FrequencyOffset> getAllFrequencyOffsets() {

		return new ArrayList<FrequencyOffset>(recordCache);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Fill the cache of offset records.

	private static FrequencyOffset nullObject = new FrequencyOffset(0, "(none)", "");
	private static FrequencyOffset invalidObject = new FrequencyOffset(-1, "???", "?");
	private static ArrayList<FrequencyOffset> recordCache = new ArrayList<FrequencyOffset>();

	public static void loadCache(DbConnection db) throws SQLException {

		recordCache.clear();

		db.query(
		"SELECT " +
			"frequency_offset_key, " +
			"name, " +
			"offset_code " +
		"FROM " +
			"frequency_offset " +
		"ORDER BY 1");

		while (db.next()) {
			recordCache.add(new FrequencyOffset(
				db.getInt(1),
				db.getString(2),
				db.getString(3)));
		}
	}
}
