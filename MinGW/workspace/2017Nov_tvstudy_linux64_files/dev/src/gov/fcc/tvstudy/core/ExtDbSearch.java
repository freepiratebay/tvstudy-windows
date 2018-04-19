//
//  ExtDbSearch.java
//  TVStudy
//
//  Copyright (c) 2016-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core;

import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;
import gov.fcc.tvstudy.core.geo.*;

import java.util.*;
import java.text.*;
import java.sql.*;


//=====================================================================================================================
// Utility class for managing saved searches used for scenario-building.  See ExtDbSearchDialog for the UI, also see
// OutputConfig for details on the pattern used for maintaining the saved state in the root database.

public class ExtDbSearch {

	// Search types.

	public static final int SEARCH_TYPE_DESIREDS = 1;
	public static final int SEARCH_TYPE_UNDESIREDS = 2;
	public static final int SEARCH_TYPE_PROTECTEDS = 3;

	// Names for default searches shown in scenario editor UI.  Here so they are reserved, can't be saved.

	public static final String DEFAULT_DESIREDS_SEARCH_NAME = "Desireds";
	public static final String DEFAULT_UNDESIREDS_SEARCH_NAME = "Undesireds";
	public static final String DEFAULT_PROTECTEDS_SEARCH_NAME = "Protecteds";

	public final int studyType;

	public String name;

	public int searchType;
	public boolean disableMX;
	public boolean preferOperating;
	public boolean desiredOnly;

	public HashSet<Integer> serviceKeys;
	public HashSet<Integer> countryKeys;
	public HashSet<Integer> statusTypes;

	public double radius;
	public GeoPoint center;

	public int minimumChannel;
	public int maximumChannel;

	public String additionalSQL;

	public boolean autoRun;


	//-----------------------------------------------------------------------------------------------------------------
	// The study type cannot change after construction, searches for each type are a separate namespace.

	public ExtDbSearch(int theStudyType) {

		studyType = theStudyType;

		name = "";

		searchType = SEARCH_TYPE_DESIREDS;

		serviceKeys = new HashSet<Integer>();
		countryKeys = new HashSet<Integer>();
		statusTypes = new HashSet<Integer>();

		center = new GeoPoint();

		additionalSQL = "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public ExtDbSearch copy() {

		ExtDbSearch newSearch = new ExtDbSearch(studyType);

		newSearch.name = name;

		newSearch.searchType = searchType;
		newSearch.disableMX = disableMX;
		newSearch.preferOperating = preferOperating;
		newSearch.desiredOnly = desiredOnly;

		for (Integer theKey : serviceKeys) {
			newSearch.serviceKeys.add(theKey);
		}
		for (Integer theKey : countryKeys) {
			newSearch.countryKeys.add(theKey);
		}
		for (Integer theType : statusTypes) {
			newSearch.statusTypes.add(theType);
		}

		newSearch.radius = radius;
		newSearch.center.setLatLon(center);

		newSearch.additionalSQL = additionalSQL;

		newSearch.autoRun = autoRun;

		return newSearch;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String toString() {

		if (null == name) {
			return "";
		}
		if (autoRun) {
			return name + " (auto)";
		}
		return name;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int hashCode() {

		if (null == name) {
			return 0;
		}
		return (name + String.valueOf(studyType)).hashCode();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean equals(Object other) {

		return (null != other) && (null != name) && (studyType == ((ExtDbSearch)other).studyType) &&
			name.equalsIgnoreCase(((ExtDbSearch)other).name);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save search to the database and cache.  If a search with the same name exists that is replaced.

	public boolean save(String theDbID) {
		return save(theDbID, null);
	}

	public synchronized boolean save(String theDbID, ErrorLogger errors) {

		if (null != name) {
			name = name.trim();
		}
		if ((null == name) || (0 == name.length()) || name.equalsIgnoreCase(DEFAULT_DESIREDS_SEARCH_NAME) ||
				name.equalsIgnoreCase(DEFAULT_UNDESIREDS_SEARCH_NAME) ||
				name.equalsIgnoreCase(DEFAULT_PROTECTEDS_SEARCH_NAME)) {
			if (null != errors) {
				errors.reportError("Search save failed, invalid name.");
			}
			return false;
		}

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return false;
		}

		String errmsg = null;

		try {

			StringBuilder s = new StringBuilder();
			String sep = "";
			for (Integer theKey : serviceKeys) {
				s.append(sep);
				s.append(String.valueOf(theKey));
				sep = ",";
			}
			String serviceList = s.toString();

			s = new StringBuilder();
			sep = "";
			for (Integer theKey : countryKeys) {
				s.append(sep);
				s.append(String.valueOf(theKey));
				sep = ",";
			}
			String countryList = s.toString();

			s = new StringBuilder();
			sep = "";
			for (Integer theType : statusTypes) {
				s.append(sep);
				s.append(String.valueOf(theType));
				sep = ",";
			}
			String statusList = s.toString();

			db.update("LOCK TABLES ext_db_search WRITE");

			db.update("DELETE FROM ext_db_search WHERE study_type = " + studyType + " AND UPPER(name) = '" +
				db.clean(name.toUpperCase()) + "'");

			db.update("INSERT INTO ext_db_search VALUES (" +
				studyType + ", " +
				"'" + db.clean(name) + "', " +
				searchType + ", " +
				disableMX + ", " +
				preferOperating + ", " +
				desiredOnly + ", " +
				"'" + serviceList + "', " +
				"'" + countryList + "', " +
				"'" + statusList + "', " +
				radius + ", " +
				center.latitude + ", " +
				center.longitude + ", " +
				minimumChannel + ", " +
				maximumChannel + ", " +
				"'" + db.clean(additionalSQL) + "', " +
				autoRun + ")");

		} catch (SQLException se) {
			errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
			db.reportError(se);
		}

		try {
			db.update("UNLOCK TABLES");
		} catch (SQLException se) {
			db.reportError(se);
		}

		DbCore.releaseDb(db);

		if (null != errmsg) {
			if (null != errors) {
				errors.reportError(errmsg);
			}
			return false;
		}

		TreeMap<String, ExtDbSearch> theMap = dbCache.get(theDbID);
		if (null == theMap) {
			theMap = loadCache(theDbID);
		} else {
			theMap.put(name.toUpperCase() + String.valueOf(studyType), this.copy());
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get saved searches, load the cache first if needed.  The return may be empty but is never null, errors are
	// ignored here.

	public static synchronized ArrayList<ExtDbSearch> getSearches(String theDbID, int theStudyType) {

		ArrayList<ExtDbSearch> result = new ArrayList<ExtDbSearch>();

		TreeMap<String, ExtDbSearch> theMap = dbCache.get(theDbID);
		if (null == theMap) {
			theMap = loadCache(theDbID);
		}

		for (ExtDbSearch theSearch : theMap.values()) {
			if (theStudyType == theSearch.studyType) {
				result.add(theSearch.copy());
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a search by name, return null on not found but no error reporting.  Immediately return null for a null or
	// empty name.

	public static ExtDbSearch getSearch(String theDbID, int theStudyType, String theName) {

		if (null != theName) {
			theName = theName.trim();
		}
		if ((null == theName) || (0 == theName.length())) {
			return null;
		}

		TreeMap<String, ExtDbSearch> theMap = dbCache.get(theDbID);
		if (null == theMap) {
			theMap = loadCache(theDbID);
		}

		ExtDbSearch result = theMap.get(theName.toUpperCase() + String.valueOf(theStudyType));
		if (null != result) {
			result = result.copy();
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Load cache from database.  This does not return null, if an error occurs an empty map is returned, however that
	// map is not placed in the cache map so another attempt will be made to load the next time that is accessed.

	private static HashMap<String, TreeMap<String, ExtDbSearch>> dbCache =
		new HashMap<String, TreeMap<String, ExtDbSearch>>();

	private static TreeMap<String, ExtDbSearch> loadCache(String theDbID) {

		TreeMap<String, ExtDbSearch> result = new TreeMap<String, ExtDbSearch>();
		ExtDbSearch search;

		DbConnection db = DbCore.connectDb(theDbID);
		if (null != db) {
			try {

				db.query("SELECT " +
					"study_type, " +
					"name, " +
					"search_type, " +
					"disable_mx, " +
					"prefer_operating, " +
					"desired_only, " +
					"service_keys, " +
					"country_keys, " +
					"status_types, " +
					"radius, " +
					"latitude, " +
					"longitude, " +
					"minimum_channel, " +
					"maximum_channel, " +
					"additional_sql, " +
					"auto_run " +
				"FROM " +
					"ext_db_search");

				while (db.next()) {

					search = new ExtDbSearch(db.getInt(1));

					search.name = db.getString(2);

					search.searchType = db.getInt(3);
					search.disableMX = db.getBoolean(4);
					search.preferOperating = db.getBoolean(5);
					search.desiredOnly = db.getBoolean(6);

					for (String s : db.getString(7).split(",")) {
						try {
							search.serviceKeys.add(Integer.valueOf(s));
						} catch (NumberFormatException nfe) {
						}
					}
					for (String s : db.getString(8).split(",")) {
						try {
							search.countryKeys.add(Integer.valueOf(s));
						} catch (NumberFormatException nfe) {
						}
					}
					for (String s : db.getString(9).split(",")) {
						try {
							search.statusTypes.add(Integer.valueOf(s));
						} catch (NumberFormatException nfe) {
						}
					}

					search.radius = db.getDouble(10);
					search.center.setLatLon(db.getDouble(11), db.getDouble(12));

					search.minimumChannel = db.getInt(13);
					search.maximumChannel = db.getInt(14);

					search.additionalSQL = db.getString(15);

					search.autoRun = db.getBoolean(16);

					result.put(search.name.toUpperCase() + String.valueOf(search.studyType), search);
				}

				dbCache.put(theDbID, result);

			} catch (SQLException se) {
				db.reportError(se);
			}

			DbCore.releaseDb(db);
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Delete a saved search.

	public static boolean deleteSearch(String theDbID, int theStudyType, String theName) {
		return deleteSearch(theDbID, theStudyType, theName, null);
	}

	public static synchronized boolean deleteSearch(String theDbID, int theStudyType, String theName,
			ErrorLogger errors) {

		if ((null == theName) || (0 == theName.length())) {
			return true;
		}

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return false;
		}

		try {

			db.update("DELETE FROM ext_db_search WHERE study_type = " + theStudyType + " AND UPPER(name) = '" +
				db.clean(theName.toUpperCase()) + "'");

			DbCore.releaseDb(db);

		} catch (SQLException se) {
			DbCore.releaseDb(db);
			DbConnection.reportError(errors, se);
			return false;
		}

		TreeMap<String, ExtDbSearch> theMap = dbCache.get(theDbID);
		if (null != theMap) {
			theMap.remove(theName.toUpperCase() + String.valueOf(theStudyType));
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Build the WHERE clause for this search for a specific data set.  Always restrict to current and pending records
	// (if the record type supports those), but nothing else has to be selected so it is possible to match every
	// current record in the data.  In the unlikely event of an error this will return null.  Note this does not add
	// a clause for the channel range.  Context-specific search methods may use additional channel restrictions of
	// varying complexity, so the channel values are passed in arguments allowing the search code to apply those in
	// the most appropriate manner.  Also the radius search has to be applied to the query results in code logic so
	// those too are passed as arguments.

	public String makeQuery(ExtDb extDb, boolean useBaseline) {
		return makeQuery(extDb, useBaseline, null);
	}

	public String makeQuery(ExtDb extDb, boolean useBaseline, ErrorLogger errors) {

		StringBuilder q = new StringBuilder();

		boolean useBL = (useBaseline && extDb.hasBaseline());
		boolean isGen = extDb.isGeneric();

		boolean hasCrit = false;

		try {

			if (!useBL) {
				hasCrit = ExtDbRecord.addRecordTypeQuery(extDb.type, extDb.version, true, false, q, false);
			}

			boolean first = true;
			Country country;
			for (Integer theKey : countryKeys) {
				country = Country.getCountry(theKey.intValue());
				if (null != country) {
					if (first) {
						if (!hasCrit) {
							q.append('(');
						} else {
							q.append(" AND (");
						}
						first = false;
					} else {
						q.append(" OR ");
					}
					if (useBL) {
						ExtDbRecordTV.addBaselineCountryQuery(extDb.type, extDb.version, country, q, false);
					} else {
						if (isGen) {
							SourceEditData.addCountryQuery(extDb.type, country, q, false);
						} else {
							ExtDbRecord.addCountryQuery(extDb.type, extDb.version, country, q, false);
						}
					}
				}
			}
			if (!first) {
				q.append(')');
				hasCrit = true;
			}

			first = true;
			Service service;
			for (Integer theKey : serviceKeys) {
				service = Service.getService(theKey.intValue());
				if (null != service) {
					if (first) {
						if (!hasCrit) {
							q.append('(');
						} else {
							q.append(" AND (");
						}
						first = false;
					} else {
						q.append(" OR ");
					}
					if (useBL) {
						ExtDbRecordTV.addBaselineServiceQuery(extDb.type, extDb.version, service.key, q, false);
					} else {
						if (isGen) {
							SourceEditData.addServiceQuery(extDb.type, service.key, q, false);
						} else {
							ExtDbRecord.addServiceQuery(extDb.type, extDb.version, service.key, q, false);
						}
					}
				}
			}
			if (!first) {
				q.append(')');
				hasCrit = true;
			}

			if (!useBL) {
				first = true;
				for (Integer theType : statusTypes) {
					if (first) {
						if (!hasCrit) {
							q.append('(');
						} else {
							q.append(" AND (");
						}
						first = false;
					} else {
						q.append(" OR ");
					}
					if (isGen) {
						SourceEditData.addStatusQuery(extDb.type, theType.intValue(), q, false);
					} else {
						ExtDbRecord.addStatusQuery(extDb.type, extDb.version, theType.intValue(), q, false);
					}
				}
				if (!first) {
					q.append(')');
					hasCrit = true;
				}
			}

		} catch (IllegalArgumentException ie) {
			if (null != errors) {
				errors.reportError(ie.toString());
			}
			return null;
		}

		// Add any additional SQL with AND.

		if (additionalSQL.length() > 0) {
			if (!hasCrit) {
				q.append('(');
			} else {
				q.append(" AND (");
			}
			q.append(additionalSQL);
			q.append(')');
			hasCrit = true;
		}

		return q.toString();
	}
}
