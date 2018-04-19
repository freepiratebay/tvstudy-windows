//
//  Country.java
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.data;

import gov.fcc.tvstudy.core.*;

import java.util.*;
import java.sql.*;


//=====================================================================================================================
// Represents records from the country table, and provides caching data factory methods.

public class Country extends KeyedRecord {

	// Static values for country keys, must match database contents!

	public static final int US = 1;
	public static final int CA = 2;
	public static final int MX = 3;

	public static final int MAX_COUNTRY = 3;

	// Database properties:

	// key (super)   Country key, unique, always > 0.
	// name (super)  Country name, never null or empty.
	// countryCode   Two-letter abbreviation code, never null, unique.

	public final String countryCode;


	//-----------------------------------------------------------------------------------------------------------------

	public Country(int theKey, String theName, String theCountryCode) {

		super(theKey, theName);

		countryCode = theCountryCode;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Accessors for individual countries.

	public static Country getCountry(int theKey) {

		return recordKeyCache.get(Integer.valueOf(theKey));
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static Country getCountry(String theCountryCode) {

		return recordCodeCache.get(theCountryCode);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// See comments in ChannelBand for details.  There is no null stand-in here, country must always be non-null.

	public static Country getInvalidObject() {

		return invalidObject;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a list of all countries, cast as KeyedRecord objects for pop-up menus.

	public static ArrayList<KeyedRecord> getCountries() {

		ArrayList<KeyedRecord> result = new ArrayList<KeyedRecord>();

		for (Country theCountry : recordCache) {
			result.add((KeyedRecord)theCountry);
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get the full list as Country objects.

	public static ArrayList<Country> getAllCountries() {

		return new ArrayList<Country>(recordCache);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Fill the cache of country objects.

	private static Country invalidObject = new Country(-1, "???", "??");
	private static ArrayList<Country> recordCache = new ArrayList<Country>();
	private static HashMap<Integer, Country> recordKeyCache = new HashMap<Integer, Country>();
	private static HashMap<String, Country> recordCodeCache = new HashMap<String, Country>();

	public static void loadCache(DbConnection db) throws SQLException {

		recordCache.clear();
		recordKeyCache.clear();
		recordCodeCache.clear();

		db.query(
		"SELECT " +
			"country_key, " +
			"name, " +
			"country_code " +
		"FROM " +
			 "country " +
		"ORDER BY 1");

		Country theCountry;

		while (db.next()) {

			theCountry = new Country(
				db.getInt(1),
				db.getString(2),
				db.getString(3));

			recordCache.add(theCountry);
			recordKeyCache.put(Integer.valueOf(theCountry.key), theCountry);
			recordCodeCache.put(theCountry.countryCode, theCountry);
		}
	}
}
