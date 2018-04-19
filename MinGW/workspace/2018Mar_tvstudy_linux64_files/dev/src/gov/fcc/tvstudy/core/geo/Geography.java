//
//  Geography.java
//  TVStudy
//
//  Copyright (c) 2016-2018 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.geo;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;

import java.util.*;
import java.sql.*;
import java.io.*;

import javax.xml.parsers.*;

import org.xml.sax.*;
import org.xml.sax.helpers.*;


//=====================================================================================================================
// Abstract superclass for geographies.  Subclasses define geographic data such as a set of study points.  These are
// created and edited outside a study context, then may be selected for various uses in a study.  See GeographyEditor.
// This works with the geography table in the root database which indexes all geographies, each subclass has it's own
// table for geographic data storage of that type.  Each object also has study and source key properties that control
// visibility in various editor scopes, see GeographyEditor.  Note those have no effect on use of the object in the
// study engine, only the primary key matters there.

public abstract class Geography {

	public static final double MIN_RECEIVE_HEIGHT = 0.;
	public static final double MAX_RECEIVE_HEIGHT = 1000.;

	public static final double MIN_DISTANCE = 1.;
	public static final double MAX_DISTANCE = 3000.;

	// Geography types.

	public static final int GEO_TYPE_POINT_SET = 1;
	public static final int GEO_TYPE_CIRCLE = 2;
	public static final int GEO_TYPE_BOX = 3;
	public static final int GEO_TYPE_POLYGON = 4;
	public static final int GEO_TYPE_SECTORS = 5;

	// Mode keys for UI behavior, may show all types, area types (excludes point set), or point set only.

	public static final int MODE_ALL = 1;
	public static final int MODE_AREA = 2;
	public static final int MODE_POINTS = 3;

	// Database ID.

	public final String dbID;

	// type       Type.
	// key        Key, null for a new object never saved.
	// studyKey   Study key for study-specific object, 0 for global.
	// sourceKey  Source key for study- and source-specific object, 0 for global or study-specific.
	// name       Name.

	public final int type;

	public Integer key;
	public int studyKey;
	public int sourceKey;
	public String name;

	// Database record modification count.

	public int modCount;


	//-----------------------------------------------------------------------------------------------------------------
	// For use by subclass constructors, also see getGeographies().

	protected Geography(String theDbID, int theType) {

		dbID = theDbID;
		type = theType;
		name = "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String toString() {

		return name;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int hashCode() {

		return ((null != key) ? key.hashCode() : super.hashCode());
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean equals(Object other) {

		return (null != other) && ((null != key) ? key.equals(((Geography)other).key) : super.equals(other));
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Text names for geography types.

	public static String getTypeName(int theType) {

		switch (theType) {
			case GEO_TYPE_POINT_SET: {
				return "Point set";
			}
			case GEO_TYPE_CIRCLE: {
				return "Circle";
			}
			case GEO_TYPE_BOX: {
				return "Box";
			}
			case GEO_TYPE_SECTORS: {
				return "Sectors";
			}
			case GEO_TYPE_POLYGON: {
				return "Polygon";
			}
			default: {
				return "???";
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Lists of geography types.  Point sets are conceptually different in where and how they are used versus other
	// types, so this can provide a list of all types, all types but point set, or only point set, per mode argument.

	public static ArrayList<KeyedRecord> getTypes(int theMode) {

		ArrayList<KeyedRecord> result = new ArrayList<KeyedRecord>();

		if ((MODE_ALL == theMode) || (MODE_POINTS == theMode)) {
			result.add(new KeyedRecord(GEO_TYPE_POINT_SET, getTypeName(GEO_TYPE_POINT_SET)));
		}
		if ((MODE_ALL == theMode) || (MODE_AREA == theMode)) {
			result.add(new KeyedRecord(GEO_TYPE_CIRCLE, getTypeName(GEO_TYPE_CIRCLE)));
			result.add(new KeyedRecord(GEO_TYPE_BOX, getTypeName(GEO_TYPE_BOX)));
			result.add(new KeyedRecord(GEO_TYPE_SECTORS, getTypeName(GEO_TYPE_SECTORS)));
			result.add(new KeyedRecord(GEO_TYPE_POLYGON, getTypeName(GEO_TYPE_POLYGON)));
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a list of geography objects of a specified type, for display in the editor.  This returns subclass objects
	// however only type, keys, and name are defined, the object's loadData() must be called to retrieve the type-
	// specific data.  Search is restricted by scope using study and source key arguments.  If the study key is 0 only
	// objects with both study and source key zero are shown, those are global.  If the study key is specified and the
	// source key is zero, global objects are shown, plus objects matching the study key with source key zero.  If the
	// study and source keys are both specified, global objects, study-wide objects, and source-specific objects are
	// shown.  If the study key is zero the source key argument is ignored.

	public static ArrayList<Geography> getGeographies(String theDbID, int theType, int theStudyKey, int theSourceKey) {
		return getGeographies(theDbID, theType, theStudyKey, theSourceKey, null);
	}

	public static ArrayList<Geography> getGeographies(String theDbID, int theType, int theStudyKey, int theSourceKey,
			ErrorLogger errors) {

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return null;
		}

		ArrayList<Geography> result = new ArrayList<Geography>();

		String whrStr = "";
		if (0 == theStudyKey) {
			whrStr = "(study_key = 0) AND (source_key = 0)";
		} else {
			if (0 == theSourceKey) {
				whrStr = "((study_key = 0) OR (study_key = " + theStudyKey + ")) AND (source_key = 0)";
			} else {
				whrStr = "((study_key = 0) OR (study_key = " + theStudyKey +
					")) AND ((source_key = 0) OR (source_key = " + theSourceKey + "))";
			}
		}
		whrStr = whrStr + " AND (geo_type = " + theType + ")";

		try {

			db.query("SELECT geo_type, geo_key, study_key, source_key, name, mod_count FROM geography WHERE " +
				whrStr + " ORDER BY geo_key DESC");

			Geography geo;

			while (db.next()) {
				switch (db.getInt(1)) {
					case GEO_TYPE_POINT_SET:
						geo = new GeoPointSet(theDbID);
						break;
					case GEO_TYPE_BOX:
						geo = new GeoBox(theDbID);
						break;
					case GEO_TYPE_CIRCLE:
						geo = new GeoCircle(theDbID);
						break;
					case GEO_TYPE_SECTORS:
						geo = new GeoSectors(theDbID);
						break;
					case GEO_TYPE_POLYGON:
						geo = new GeoPolygon(theDbID);
						break;
					default:
						continue;
				}
				geo.key = Integer.valueOf(db.getInt(2));
				geo.studyKey = db.getInt(3);
				geo.sourceKey = db.getInt(4);
				geo.name = db.getString(5);
				geo.modCount = db.getInt(6);
				result.add(geo);
			}

			DbCore.releaseDb(db);

		} catch (SQLException se) {
			DbCore.releaseDb(db);
			result = null;
			DbConnection.reportError(errors, se);
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get geography key by name, mainly used for checking name uniqueness.  Returns 0 on not found, -1 on error.

	public static int getKeyForName(String theDbID, String theName) {
		return getKeyForName(theDbID, theName, null);
	}

	public static int getKeyForName(String theDbID, String theName, ErrorLogger errors) {

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return 0;
		}

		int theKey = 0;

		try {

			db.query("SELECT geo_key FROM geography WHERE UPPER(name) = '" +
				db.clean(theName.toUpperCase()) + "'");
			if (db.next()) {
				theKey = db.getInt(1);
			}

			DbCore.releaseDb(db);

		} catch (SQLException se) {
			DbCore.releaseDb(db);
			DbConnection.reportError(errors, se);
			theKey = -1;
		}

		return theKey;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get geography type for a given key, see GeographyEditor.setScope().  Returns 0 on not found or error.

	public static int getTypeForKey(String theDbID, int theKey) {
		return getTypeForKey(theDbID, theKey, null);
	}

	public static int getTypeForKey(String theDbID, int theKey, ErrorLogger errors) {

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return 0;
		}

		int theType = 0;

		try {

			db.query("SELECT geo_type FROM geography WHERE geo_key = " + theKey);
			if (db.next()) {
				theType = db.getInt(1);
			}

			DbCore.releaseDb(db);

		} catch (SQLException se) {
			DbCore.releaseDb(db);
			DbConnection.reportError(errors, se);
		}

		return theType;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a KeyedRecord list of geographies for UI selection menus.  Study and source context are provided as in
	// getGeographies() above.  A mode argument is also provided to customize the list to an appropriate set of types.

	public static ArrayList<KeyedRecord> getGeographyList(String theDbID, int theStudyKey, int theSourceKey,
			int theMode) {
		return getGeographyList(theDbID, theStudyKey, theSourceKey, theMode, null);
	}

	public static ArrayList<KeyedRecord> getGeographyList(String theDbID, int theStudyKey, int theSourceKey,
			int theMode, ErrorLogger errors) {

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return null;
		}

		ArrayList<KeyedRecord> result = new ArrayList<KeyedRecord>();

		String whrStr = "";
		if (0 == theStudyKey) {
			whrStr = "(study_key = 0) AND (source_key = 0)";
		} else {
			if (0 == theSourceKey) {
				whrStr = "((study_key = 0) OR (study_key = " + theStudyKey + ")) AND (source_key = 0)";
			} else {
				whrStr = "((study_key = 0) OR (study_key = " + theStudyKey +
					")) AND ((source_key = 0) OR (source_key = " + theSourceKey + "))";
			}
		}
		if (MODE_POINTS == theMode) {
			whrStr = whrStr + " AND (geo_type = " + GEO_TYPE_POINT_SET + ")";
		} else {
			if (MODE_AREA == theMode) {
				whrStr = whrStr + " AND (geo_type <> " + GEO_TYPE_POINT_SET + ")";
			}
		}

		try {

			db.query("SELECT geo_key, name FROM geography WHERE " + whrStr + " ORDER BY geo_key DESC");

			while (db.next()) {
				result.add(new KeyedRecord(db.getInt(1), db.getString(2)));
			}

			DbCore.releaseDb(db);

		} catch (SQLException se) {
			DbCore.releaseDb(db);
			result = null;
			DbConnection.reportError(errors, se);
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check a new geography name for validity.  Optionally an existing name may be passed, if the new name matches the
	// old ignoring case, it is immediately approved without further checks.  That is done because of the uniqueness
	// behavior that will automatically append a suffix to the name comprised of a special character and the geography
	// key if needed to make the name unique at the time of a concurrent-safe uniqueness check during save.  To make
	// that work the special character can never be used in a new name.  But since uniqueness is case-insensitive,
	// changes to an existing name, which may already contain the special character, that only change case can be
	// allowed since they can't result in a non-unique name.  If the new name does not match the old, or there is no
	// old name, the new name is checked for length, for the presence of the special uniqueness character, and finally
	// an optional advisory check for uniqueness is done.  By the time the name is actually saved it may no longer be
	// unique, however in that case the uniqueness suffix will be added automatically.

	public static boolean checkGeographyName(String dbID, String newName) {
		return checkGeographyName(dbID, newName, null, true, null);
	}

	public static boolean checkGeographyName(String dbID, String newName, ErrorLogger errors) {
		return checkGeographyName(dbID, newName, null, true, errors);
	}

	public static boolean checkGeographyName(String dbID, String newName, boolean checkUnique) {
		return checkGeographyName(dbID, newName, null, checkUnique, null);
	}

	public static boolean checkGeographyName(String dbID, String newName, boolean checkUnique, ErrorLogger errors) {
		return checkGeographyName(dbID, newName, null, checkUnique, errors);
	}

	public static boolean checkGeographyName(String dbID, String newName, String oldName) {
		return checkGeographyName(dbID, newName, oldName, true, null);
	}

	public static boolean checkGeographyName(String dbID, String newName, String oldName, ErrorLogger errors) {
		return checkGeographyName(dbID, newName, oldName, true, errors);
	}

	public static boolean checkGeographyName(String dbID, String newName, String oldName, boolean checkUnique,
			ErrorLogger errors) {

		if ((null != oldName) && (oldName.length() > 0)) {
			if (newName.equalsIgnoreCase(oldName)) {
				return true;
			}
		}

		if (0 == newName.length()) {
			if (null != errors) {
				errors.reportValidationError("Please provide a name for the template.");
			}
			return false;
		}

		if (newName.length() > DbCore.NAME_MAX_LENGTH) {
			if (null != errors) {
				errors.reportValidationError("The geography name cannot be more than " + DbCore.NAME_MAX_LENGTH +
					" characters long.");
			}
			return false;
		}

		if (newName.contains(String.valueOf(DbCore.NAME_UNIQUE_CHAR))) {
			if (null != errors) {
				errors.reportValidationError("The geography name cannot contain the character '" +
					DbCore.NAME_UNIQUE_CHAR + "'");
			}
			return false;
		}

		if (checkUnique) {
			int key = getKeyForName(dbID, newName, errors);
			if (key < 0) {
				return false;
			}
			if (key > 0) {
				if (null != errors) {
					errors.reportValidationError("That geography name is already in use, please try again.");
				}
				return false;
			}
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Load type-specific data from the database.  May overwrite as-modified properties.

	public boolean loadData() {
		return loadData(null);
	}

	public abstract boolean loadData(ErrorLogger errors);


	//-----------------------------------------------------------------------------------------------------------------
	// Make a new object with copies of all properties, it should be a deep copy.  This can be used at any time
	// regardless of validity or previous loadData().  Subclass must call super.duplicateTo().

	public abstract Geography duplicate();

	protected void duplicateTo(Geography newGeo) {

		newGeo.studyKey = studyKey;
		newGeo.sourceKey = sourceKey;

		newGeo.name = name + " copy";
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check validity.  Call super first, return false if it does.

	public boolean isDataValid() {
		return isDataValid(null);
	}

	public boolean isDataValid(ErrorLogger errors) {

		if (null == name) {
			name = "";
		}
		name = name.trim();
		if (0 == name.length()) {
			if (null != errors) {
				errors.reportValidationError("A geography name must be provided.");
			}
			return false;
		}
		if (name.length() > DbCore.NAME_MAX_LENGTH) {
			name = name.substring(0, DbCore.NAME_MAX_LENGTH);
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save, caller should have checked validity.  Set up a database connection, lock tables, and call saveGeography().
	// If this is an existing geography, first check if any studies using it are in run-lock states, if so the save is
	// aborted.  The study locks must also protect any geographies used by the studies.  Because of the design of the
	// geography editor this check-on-save approach is not a major inconvenience for the user; the as-edited state
	// will remain in the editor so the user can just do the save again once the running studies complete.

	public boolean save() {
		return save(null);
	}

	public boolean save(ErrorLogger errors) {

		DbConnection db = DbCore.connectDb(dbID, errors);
		if (null == db) {
			return false;
		}

		String errmsg = null;

		try {

			String tbl = "";
			switch (type) {
				case GEO_TYPE_POINT_SET:
					tbl = "geo_point_set WRITE, ";
					break;
				case GEO_TYPE_SECTORS:
					tbl = "geo_sectors WRITE, ";
					break;
				case GEO_TYPE_POLYGON:
					tbl = "geo_polygon WRITE, ";
					break;
			}
			db.update("LOCK TABLES geography WRITE, geo_key_sequence WRITE, " + tbl +
				"study WRITE, study_geography WRITE");

			if (null != key) {
				db.query(
				"SELECT " +
					"COUNT(*) " +
				"FROM " +
					"study_geography " +
					"JOIN study USING (study_key) " +
				"WHERE " +
					"(study_geography.geo_key = " + key + ") " +
					"AND (study.study_lock IN (" + Study.LOCK_RUN_EXCL + "," + Study.LOCK_RUN_SHARE + "))");
				if (db.next() && (db.getInt(1) > 0)) {
					errmsg = "Changes cannot be saved now, the geography is in use by a running study.\n" +
						"Try again after study runs are finished.";
				}
			}

			if (null == errmsg) {
				errmsg = saveGeography(db);
			}

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

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The common part of a save, create or update the geography record.  Caller provides an open database connection
	// with the geography tables already locked.  First check for name uniqueness and append the key to the name to
	// make it unique if needed.  Then get a new key if needed else delete the existing record, then insert a new
	// record.  Note if the record had been deleted this will put it back.  Returns null on success else an error
	// message string.  Must be overridden to save type-specific data, after calling super first and aborting on
	// error return.

	protected abstract String saveGeography(DbConnection db) throws SQLException;

	protected String saveGeography(DbConnection db, GeoPoint thePoint, double theRadius, double theWidth,
			double theHeight) throws SQLException {

		db.query("SELECT geo_key FROM geography WHERE UPPER(name) = '" + db.clean(name.toUpperCase()) + "'");
		boolean appendKey = false;
		if (db.next()) {
			if (null == key) {
				appendKey = true;
			} else {
				appendKey = (db.getInt(1) != key.intValue());
			}
		}

		if (null == key) {
			db.update("UPDATE geo_key_sequence SET geo_key = geo_key + 1");
			db.query("SELECT geo_key FROM geo_key_sequence");
			db.next();
			key = Integer.valueOf(db.getInt(1));
		} else {
			db.update("DELETE FROM geography WHERE geo_key = " + key);
			modCount++;
		}

		if (appendKey) {
			name = name + " " + DbCore.NAME_UNIQUE_CHAR + key;
		}

		db.update(
		"INSERT INTO geography (" +
			"geo_key, " +
			"study_key, " +
			"source_key, " +
			"geo_type, " +
			"name, " +
			"latitude, " +
			"longitude, " +
			"radius, " +
			"width, " +
			"height, " +
			"mod_count) " +
		"VALUES (" +
			key + "," +
			studyKey + "," +
			sourceKey + "," +
			type + "," +
			"'" + db.clean(name) + "', " +
			thePoint.latitude + ", " +
			thePoint.longitude + ", " +
			theRadius + ", " +
			theWidth + ", " +
			theHeight + ", " +
			modCount + ")");

		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Delete a geography.  First check if it is in use by existing studies.  Note this just checks as-saved study
	// state, the caller has to check active UI directly to find out about usage in unsaved state.

	public static boolean deleteGeography(String theDbID, int geoKey) {
		return deleteGeography(theDbID, geoKey, null);
	}

	public static boolean deleteGeography(String theDbID, int geoKey, ErrorLogger errors) {

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return false;
		}

		String errmsg = null;
		int refCount = 0, errtyp = AppCore.ERROR_MESSAGE;

		try {

			db.update("LOCK TABLES geography WRITE, geo_point_set WRITE, geo_polygon WRITE, geo_sectors WRITE, " +
				"geography_receive_antenna WRITE, study_geography WRITE");

			db.query("SELECT COUNT(*) FROM study_geography WHERE geo_key = " + geoKey);
			db.next();
			refCount = db.getInt(1);

			if (0 == refCount) {

				db.update("DELETE FROM geography WHERE geo_key = " + geoKey);
				db.update("DELETE FROM geo_point_set WHERE geo_key = " + geoKey);
				db.update("DELETE FROM geo_sectors WHERE geo_key = " + geoKey);
				db.update("DELETE FROM geo_polygon WHERE geo_key = " + geoKey);
				db.update("DELETE FROM geography_receive_antenna WHERE geo_key = " + geoKey);

			} else {
				errmsg = "The geography is in use and cannot be deleted.";
				errtyp = AppCore.WARNING_MESSAGE;
			}

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
				errors.reportError(errmsg, errtyp);
			}
			return false;
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update geographies for a new duplicated study.  This must be called after the new study database has been
	// created and populated with all records duplicated from the original; this will not access the original study.
	// Study-specific geographies are duplicated and geography keys updated.  Keys on source records are updated
	// directly in the new study database, however this assumes the new study record itself has not yet been written
	// so keys for that are updated in memory.  This will also add entries to the study_geography table for all keys
	// referenced by the new study, including global geographies that are not duplicated.  Since the new study record
	// does not exist yet the study is effectively invisible so there is no need to do any locking on that database.
	// First get the set of geographies that need to be duplicated, that may be empty, along the way also get the
	// geography types to avoid re-querying later.  Then do the duplications.

	public static void duplicateStudyGeographies(DbConnection db, String rootName, int studyKey,
			Study.GeographyKeys studyGeoKeys) throws SQLException {

		db.setDatabase(rootName);

		HashMap<Integer, Integer> toDupe = new HashMap<Integer, Integer>();
		db.query("SELECT geo_key, geo_type FROM geography WHERE ((geo_key IN (SELECT DISTINCT service_area_key FROM " +
			rootName + "_" + studyKey + ".source)) OR (geo_key = " + studyGeoKeys.pointSetKey + ") OR (geo_key = " +
			studyGeoKeys.areaKey + ")) AND (study_key > 0)");
		while (db.next()) {
			toDupe.put(Integer.valueOf(db.getInt(1)), Integer.valueOf(db.getInt(2)));
		}

		if (!toDupe.isEmpty()) {
		
			db.update(
			"LOCK TABLES " +
				"geography WRITE, " +
				"geography AS old WRITE, " +
				"geo_key_sequence WRITE, " +
				"geo_point_set WRITE, " +
				"geo_point_set AS old_point WRITE, " +
				"geography_receive_antenna WRITE, " +
				"geo_polygon WRITE, " +
				"geo_polygon AS old_poly WRITE, " +
				"geo_sectors WRITE, " +
				"geo_sectors AS old_sectors WRITE, " +
				"study_geography WRITE, " +
				rootName + "_" + studyKey + ".source WRITE");

			int oldKey, newKey, geoType;

			db.query("SELECT geo_key FROM geo_key_sequence");
			db.next();
			newKey = db.getInt(1);
			db.update("UPDATE geo_key_sequence SET geo_key = geo_key + " + toDupe.size());

			for (Integer dupeKey : toDupe.keySet()) {

				oldKey = dupeKey.intValue();
				geoType = toDupe.get(dupeKey).intValue();
				newKey++;

				db.update(
				"INSERT INTO geography (" +
					"geo_key," +
					"study_key," +
					"source_key," +
					"geo_type," +
					"name," +
					"latitude," +
					"longitude," +
					"radius," +
					"width," +
					"height," +
					"mod_count) " +
				"SELECT " +
					newKey + " AS geo_key," +
					studyKey + " AS study_key," +
					"old.source_key," +
					"old.geo_type," +
					"CONCAT(old.name, ' " + DbCore.NAME_UNIQUE_CHAR + newKey + "')," +
					"old.latitude," +
					"old.longitude," +
					"old.radius," +
					"old.width," +
					"old.height," +
					"0 AS mod_count " +
				"FROM " +
					"geography AS old " +
				"WHERE " +
					"old.geo_key = " + oldKey);

				switch (geoType) {

					case GEO_TYPE_POINT_SET: {

						db.update(
						"INSERT INTO geo_point_set (" +
							"geo_key," +
							"point_name," +
							"latitude," +
							"longitude," +
							"receive_height," +
							"antenna_key," +
							"antenna_orientation) " +
						"SELECT " +
							newKey + " AS geo_key," +
							"old_point.point_name," +
							"old_point.latitude," +
							"old_point.longitude," +
							"old_point.receive_height," +
							"old_point.antenna_key," +
							"old_point.antenna_orientation " +
						"FROM " +
							"geo_point_set AS old_point " +
						"WHERE " +
							"old_point.geo_key = " + oldKey);

						db.update(
						"INSERT INTO geography_receive_antenna (" +
							"geo_key," +
							"antenna_key) " +
						"SELECT DISTINCT " +
							"geo_key," +
							"antenna_key " +
						"FROM " +
							"geo_point_set " +
						"WHERE " +
							"geo_key = " + newKey + " " +
							"AND antenna_key > 0");

						break;
					}

					case GEO_TYPE_POLYGON: {

						db.update(
						"INSERT INTO geo_polygon (" +
							"geo_key," +
							"vertex_key," +
							"latitude," +
							"longitude) " +
						"SELECT " +
							newKey + " AS geo_key," +
							"old_poly.vertex_key," +
							"old_poly.latitude," +
							"old_poly.longitude " +
						"FROM " +
							"geo_polygon AS old_poly " +
						"WHERE " +
							"old_poly.geo_key = " + oldKey);

						break;
					}

					case GEO_TYPE_SECTORS: {

						db.update(
						"INSERT INTO geo_sectors (" +
							"geo_key," +
							"azimuth," +
							"radius) " +
						"SELECT " +
							newKey + " AS geo_key," +
							"old_sectors.azimuth," +
							"old_sectors.radius " +
						"FROM " +
							"geo_sectors AS old_sectors " +
						"WHERE " +
							"old_sectors.geo_key = " + oldKey);

						break;
					}
				}

				if (oldKey == studyGeoKeys.pointSetKey) {
					studyGeoKeys.pointSetKey = newKey;
				} else {
					if (oldKey == studyGeoKeys.areaKey) {
						studyGeoKeys.areaKey = newKey;
					} else {
						db.update("UPDATE " + rootName + "_" + studyKey + ".source SET service_area_key = " + newKey +
							" WHERE service_area_key = " + oldKey);
					}
				}
			}

		} else {
			db.update("LOCK TABLES study_geography WRITE, " + rootName + "_" + studyKey + ".source WRITE");
		}

		// Write study_geography entries for all keys.

		HashSet<Integer> geoKeys = new HashSet<Integer>();
		db.query("SELECT DISTINCT service_area_key FROM " + rootName + "_" + studyKey + ".source " +
			"WHERE service_area_key > 0");
		while (db.next()) {
			geoKeys.add(Integer.valueOf(db.getInt(1)));
		}
		if (studyGeoKeys.pointSetKey > 0) {
			geoKeys.add(Integer.valueOf(studyGeoKeys.pointSetKey));
		}
		if (studyGeoKeys.areaKey > 0) {
			geoKeys.add(Integer.valueOf(studyGeoKeys.areaKey));
		}

		if (!geoKeys.isEmpty()) {
			StringBuilder query = new StringBuilder("INSERT INTO study_geography (study_key, geo_key) VALUES");
			int startLength = query.length();
			String sep = " (";
			for (Integer theKey : geoKeys) {
				query.append(sep);
				query.append(String.valueOf(studyKey));
				query.append(',');
				query.append(String.valueOf(theKey));
				if (query.length() > DbCore.MAX_QUERY_LENGTH) {
					query.append(')');
					db.update(query.toString());
					query.setLength(startLength);
					sep = " (";
				} else {
					sep = "),(";
				}
			}
			if (query.length() > startLength) {
				query.append(')');
				db.update(query.toString());
			}
		}

		db.update("UNLOCK TABLES");
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Delete geography records belonging to a particular study, or sources within that study.  This is called after
	// the study or sources have been deleted so this can safely assume the geographies are no longer in use.  Any
	// geography linked to a study or source becomes entirely inaccessible once the study or source is deleted, since
	// an editor for such geographies can only be opened from within a study editor for the study.  That also means
	// the study lock implicitly extends to study-specific geographies so the global geography edit lock is not needed
	// here.  If sources were deleted a source key list is provided, that is a string formatted for an IN clause.  If
	// that argument is null the entire study has been deleted.

	public static void deleteStudyGeographies(DbConnection db, String theDbName, int theStudyKey,
			String sourceKeyList) throws SQLException {

		if (null == sourceKeyList) {
			db.query("SELECT geo_key FROM " + theDbName + ".geography WHERE study_key = " + theStudyKey);
		} else {
			db.query("SELECT geo_key FROM " + theDbName + ".geography WHERE study_key = " + theStudyKey +
				" AND source_key IN " + sourceKeyList);
		}

		StringBuilder q = new StringBuilder();
		String sep = "(";

		while (db.next()) {
			q.append(sep);
			q.append(String.valueOf(db.getInt(1)));
			sep = ",";
		}

		if (0 == q.length()) {
			return;
		}

		q.append(")");

		String geoKeyList = q.toString();

		db.update("DELETE FROM " + theDbName + ".geography WHERE geo_key IN " + geoKeyList);
		db.update("DELETE FROM " + theDbName + ".geo_point_set WHERE geo_key IN " + geoKeyList);
		db.update("DELETE FROM " + theDbName + ".geo_sectors WHERE geo_key IN " + geoKeyList);
		db.update("DELETE FROM " + theDbName + ".geo_polygon WHERE geo_key IN " + geoKeyList);
		db.update("DELETE FROM " + theDbName + ".geography_receive_antenna WHERE geo_key IN " + geoKeyList);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Write geography to an XML file, must be valid.

	public boolean writeToXML(Writer xml) {
		return writeToXML(xml, null);
	}

	public boolean writeToXML(Writer xml, ErrorLogger errors) {

		if (!isDataValid(errors)) {
			return false;
		}

		try {

			xml.append("<GEOGRAPHY TYPE=\"");
			xml.append(String.valueOf(type));
			xml.append("\" NAME=\"");
			xml.append(name);
			xml.append('"');

			writeAttributes(xml);

			if (hasElements()) {

				xml.append(">\n");
				writeElements(xml);
				xml.append("</GEOGRAPHY>\n");

			} else {
				xml.append("/>\n");
			}

		} catch (IOException ie) {
			if (null != errors) {
				errors.reportError("Could not write to the file:\n" + ie.getMessage());
			}
			return false;
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Parse an XML source to load a geography.  All the work is done in the parsing handler.

	public static Geography readGeographyFromXML(String theDbID, Reader xml) {
		return readGeographyFromXML(theDbID, xml, null);
	}

	public static Geography readGeographyFromXML(String theDbID, Reader xml, ErrorLogger errors) {

		if (null != errors) {
			errors.clearErrors();
		} else {
			errors = new ErrorLogger(null, null);
		}

		GeographyXMLHandler handler = new GeographyXMLHandler(theDbID, errors);
		try {
			XMLReader xReader = XMLReaderFactory.createXMLReader("org.apache.xerces.parsers.SAXParser");
			xReader.setContentHandler(handler);
			xReader.parse(new InputSource(xml));
		} catch (SAXException se) {
			String msg = se.getMessage();
			if ((null != msg) && (msg.length() > 0)) {
				errors.reportError("XML error: " + msg);
			}
		} catch (Throwable t) {
			AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
			errors.reportError("An unexpected error occurred:\n" + t);
		}

		return handler.geography;
	}


	//=================================================================================================================
	// XML parsing handler.  This will parse until one complete GEOGRAPHY element has been processed, set that in the
	// property to be returned, then ignore the rest of the input.

	private static class GeographyXMLHandler extends DefaultHandler {

		private String dbID;
		private ErrorLogger errors;

		// The geography, or null.

		private Geography geography;
		private boolean hasElements;

		// Stack of element names, attribute sets, and content buffers for nested elements.

		private ArrayDeque<String> elements;
		private ArrayDeque<Attributes> attributes;
		private ArrayDeque<StringWriter> buffers;

		// Once set, all further input is ignored.

		private boolean ignoreAll;


		//-------------------------------------------------------------------------------------------------------------
		// All errors are send to the ErrorLogger using reportError().  After reporting an error the parsing methods
		// will throw an exception to abort parsing, but the exception itself does not have a specific error message.

		private GeographyXMLHandler(String theDbID, ErrorLogger theErrors) {

			super();

			dbID = theDbID;
			errors = theErrors;

			elements = new ArrayDeque<String>();
			attributes = new ArrayDeque<Attributes>();
			buffers = new ArrayDeque<StringWriter>();
		}


		//-------------------------------------------------------------------------------------------------------------
		// Start of element.  Push the element name, attributes, and a new content buffer on to the stacks.

		public void startElement(String nameSpc, String locName, String qName, Attributes attrs) throws SAXException {

			if (ignoreAll) {
				return;
			}

			elements.push(qName);
			attributes.push(attrs);
			buffers.push(new StringWriter());

			// Start of a new geography.  Name and type are required, branch to subclass to parse other attributes.

			if (qName.equals("GEOGRAPHY")) {

				if (geography != null) {
					errors.reportError("GEOGRAPHY elements may not be nested");
					throw new SAXException();
				}

				int theType = 0;
				String str = attrs.getValue("TYPE");
				if (null != str) {
					try {
						theType = Integer.parseInt(str);
					} catch (NumberFormatException nfe) {
					}
				}

				switch (theType) {
					case GEO_TYPE_POINT_SET: {
						geography = new GeoPointSet(dbID);
						break;
					}
					case GEO_TYPE_CIRCLE: {
						geography = new GeoCircle(dbID);
						break;
					}
					case GEO_TYPE_BOX: {
						geography = new GeoBox(dbID);
						break;
					}
					case GEO_TYPE_SECTORS: {
						geography = new GeoSectors(dbID);
						break;
					}
					case GEO_TYPE_POLYGON: {
						geography = new GeoPolygon(dbID);
						break;
					}
				}
				if (null == geography) {
					errors.reportError("Missing or bad TYPE attribute in GEOGRAPHY tag");
					throw new SAXException();
				}

				geography.name = attrs.getValue("NAME");
				if (null == geography.name) {
					errors.reportError("Missing NAME attribute in GEOGRAPHY tag");
					geography = null;
					throw new SAXException();
				}
				if (!checkGeographyName(dbID, geography.name, false)) {
					errors.reportError("Bad NAME attribute in GEOGRAPHY tag");
					geography = null;
					throw new SAXException();
				}

				if (!geography.parseAttributes(qName, attrs, errors)) {
					geography = null;
					throw new SAXException();
				}

				hasElements = geography.hasElements();

				return;
			}
		}


		//-------------------------------------------------------------------------------------------------------------
		// Add content characters to the buffer for the current element.

		public void characters(char[] ch, int start, int length) {

			if (!buffers.isEmpty()) {
				buffers.peek().write(ch, start, length);
			}
		}


		//-------------------------------------------------------------------------------------------------------------
		// End of element, pop the element name and content off the stacks, check for overlap (the superclass may do
		// that, I'm not sure, but it doesn't hurt to check again).

		public void endElement(String nameSpc, String locName, String qName) throws SAXException {

			if (ignoreAll) {
				return;
			}

			String element = elements.pop();
			Attributes attrs = attributes.pop();
			String content = buffers.pop().toString().trim();

			if (!element.equals(qName)) {
				errors.reportError("Overlapping elements not allowed");
				throw new SAXException();
			}

			// The end of a GEOGRAPHY element, the parsed object must be valid, set flag to ignore the rest.

			if (element.equals("GEOGRAPHY")) {
				if ((null == geography) || !geography.isDataValid(errors)) {
					throw new SAXException();
				}
				ignoreAll = true;
				return;
			}

			// Pass element to subclass if needed.

			if (hasElements) {
				if (!geography.parseElement(element, attrs, content, errors)) {
					throw new SAXException();
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Subclasses usually override writeAttributes() and parseAttributes() for attributes in the GEOGRAPHY tag.  For
	// some types that is all that will be needed, for others there are no attributes.  If nested elements are needed,
	// overried hasElements() to return true, writeElements() to write the data, and parseElement() to parse the data.

	protected void writeAttributes(Writer xml) throws IOException {
	}

	protected boolean parseAttributes(String element, Attributes attrs, ErrorLogger errors) {
		return true;
	}

	protected boolean hasElements() {
		return false;
	}

	protected void writeElements(Writer xml) throws IOException {
	}

	protected boolean parseElement(String element, Attributes attrs, String content, ErrorLogger errors) {
		return true;
	}
}
