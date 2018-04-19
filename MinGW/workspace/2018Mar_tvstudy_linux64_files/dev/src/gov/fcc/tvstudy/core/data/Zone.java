//
//  Zone.java
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.data;

import gov.fcc.tvstudy.core.*;

import java.util.*;
import java.sql.*;


//=====================================================================================================================
// Represents records from the zone table, and provides caching data factory methods.

public class Zone extends KeyedRecord {

	// Database properties:

	// key (super)   Zone key, unique, always > 0.
	// name (super)  Zone name, never null or empty.
	// zoneCode      One-letter code, never null or empty, unique.

	public final String zoneCode;


	//-----------------------------------------------------------------------------------------------------------------

	public Zone(int theKey, String theName, String theZoneCode) {

		super(theKey, theName);

		zoneCode = theZoneCode;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Accessor for individual records.

	public static Zone getZone(int theKey) {

		if (0 == theKey) {
			return nullObject;
		}

		for (Zone theZone : recordCache) {
			if (theKey == theZone.key) {
				return theZone;
			}
		}
		return nullObject;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static Zone getZone(String theZoneCode) {

		for (Zone theZone : recordCache) {
			if (theZone.zoneCode.equalsIgnoreCase(theZoneCode)) {
				return theZone;
			}
		}
		return nullObject;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static Zone getNullObject() {

		return nullObject;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static Zone getInvalidObject() {

		return invalidObject;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a list of zones for menus.

	public static ArrayList<KeyedRecord> getZones() {

		ArrayList<KeyedRecord> result = new ArrayList<KeyedRecord>();

		for (Zone theZone : recordCache) {
			result.add((KeyedRecord)theZone);
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a list of zones with the null object.

	public static ArrayList<KeyedRecord> getZonesWithNull() {

		ArrayList<KeyedRecord> result = new ArrayList<KeyedRecord>();

		result.add(nullObject);

		for (Zone theZone : recordCache) {
			result.add((KeyedRecord)theZone);
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a list of all zones as actual class.

	public static ArrayList<Zone> getAllZones() {

		return new ArrayList<Zone>(recordCache);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Fill the cache of zone records.

	private static Zone nullObject = new Zone(0, "(n/a)", "");
	private static Zone invalidObject = new Zone(-1, "???", "?");
	private static ArrayList<Zone> recordCache = new ArrayList<Zone>();

	public static void loadCache(DbConnection db) throws SQLException {

		recordCache.clear();

		db.query(
		"SELECT " +
			"zone_key, " +
			"name, " +
			"zone_code " +
		"FROM " +
			"zone " +
		"ORDER BY 1");

		while (db.next()) {
			recordCache.add(new Zone(
				db.getInt(1),
				db.getString(2),
				db.getString(3)));
		}
	}
}
