//
//  SourceEditData.java
//  TVStudy
//
//  Copyright (c) 2012-2018 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.editdata;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.geo.*;

import java.util.*;
import java.util.regex.*;
import java.io.*;
import java.sql.*;

import javax.xml.parsers.*;

import org.xml.sax.*;
import org.xml.sax.helpers.*;


//=====================================================================================================================
// Mutable model class to support editing of source records, see SourceEditor, also ScenarioEditor, SourceListData and
// StudyEditor, and to manipulate source data outside an existing study context.  This is an abstract superclass; a
// concrete subclass exists for each different record type, wrapping SourceTV, SourceWL, and SourceFM record objects.
// Core properties are immutable to ensure that arbitrary changes can't be made that would violate the object contract.
// The association of a source with a particular study and the source object primary key are immutable.  The key is a
// unique identifier throughout the models but is only unique within a particular study.  A source object can exist
// outside any study (the study property is null), but in that case the key is an arbitrary value unique only for the
// current application instance, and the object cannot be saved.  Properties such as the service and country, and for
// some record types the facility ID, are used by scenario-building logic that assumes those values are persistent and
// immutable, changing them directly could invalidate the state of existing scenarios.  The user record ID, or station
// data key and record ID, associate the source with a primary record that is presumed immutable.  That association
// cannot be changed arbitrarily, it implies the other immutable properties are identical to the underlying primary
// record, and if the isLocked property is true, that further implies ALL source properties are identical to the
// underlying record.  A source can be associated with a primary record in either locked or unlocked state, but
// unlocked sources are handled differently (e.g. see StudyEditData), and an unlocked source cannot be locked again
// since it may have been modified.  See the subclasses, particularily SourceEditDataTV, for more specific details.
// As with the Source class, common properties are here particularily pattern data.  The pattern data properties have
// special interpretation because the data is not automatically loaded with the record for performance reasons, it will
// be loaded on demand when needed by an editor.  So the pattern data properties are non-null only if the data has been
// loaded, in that case the *PatternChanged flags indicate if there were any actual changes.

// Source records have a general-purpose attributes field, represented in the database as a string in name=value
// format.  The attributes may be set on any object, even when locked.  Other code may use the attributes for any
// desired purpose.  For convenience and consistency names should be defined in Source.java, but those are not pre-
// determined or limited by either Source or this class.

public abstract class SourceEditData implements Record {

	public final StudyEditData study;
	public final String dbID;

	public final int recordType;

	public final Integer key;
	public final Service service;
	public final Country country;
	public final boolean isLocked;
	public final Integer userRecordID;
	public final Integer extDbKey;
	public final String extRecordID;

	public String callSign;
	public String city;
	public String state;
	public String fileNumber;
	public final GeoPoint location;
	public double heightAMSL;
	public double overallHAAT;
	public double peakERP;
	public String antennaID;
	public boolean hasHorizontalPattern;
	public AntPattern horizontalPattern;
	public boolean horizontalPatternChanged;
	public double horizontalPatternOrientation;
	public boolean hasVerticalPattern;
	public AntPattern verticalPattern;
	public boolean verticalPatternChanged;
	public double verticalPatternElectricalTilt;
	public double verticalPatternMechanicalTilt;
	public double verticalPatternMechanicalTiltOrientation;
	public boolean hasMatrixPattern;
	public AntPattern matrixPattern;
	public boolean matrixPatternChanged;
	public boolean useGenericVerticalPattern;

	// Attributes are set and retrieved through methods.

	protected final HashMap<String, String> attributes;
	protected boolean attributesChanged;

	// A temporary sequence used for objects not associated with an existing study.

	private static int nextTemporaryKey = 1;


	//-----------------------------------------------------------------------------------------------------------------
	// Constructor for use by subclasses, sets only the final properties.

	protected SourceEditData(StudyEditData theStudy, String theDbID, int theRecordType, Integer theKey,
			Service theService, Country theCountry, boolean theIsLocked, Integer theUserRecordID, Integer theExtDbKey,
			String theExtRecordID) {

		super();

		study = theStudy;
		dbID = theDbID;

		recordType = theRecordType;

		key = theKey;
		service = theService;
		country = theCountry;
		isLocked = theIsLocked;
		userRecordID = theUserRecordID;
		extDbKey = theExtDbKey;
		extRecordID = theExtRecordID;

		location = new GeoPoint();

		attributes = new HashMap<String, String>();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Accessor for the underlying record object.  May be null.

	public abstract Source getSource();


	//-----------------------------------------------------------------------------------------------------------------
	// Create an object of appropriate subclass for an existing source record from a study database.

	public static SourceEditData getInstance(StudyEditData theStudy, Source theSource) {

		switch (theSource.recordType) {

			case Source.RECORD_TYPE_TV: {
				return new SourceEditDataTV(theStudy, (SourceTV)theSource);
			}

			case Source.RECORD_TYPE_WL: {
				return new SourceEditDataWL(theStudy, (SourceWL)theSource);
			}

			case Source.RECORD_TYPE_FM: {
				return new SourceEditDataFM(theStudy, (SourceFM)theSource);
			}
		}

		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Make a source object from an external record object.

	public static SourceEditData makeSource(ExtDbRecord record, StudyEditData study, boolean isLocked) {
		return makeSource(record, study, isLocked, null);
	}

	public static SourceEditData makeSource(ExtDbRecord record, StudyEditData study, boolean isLocked,
			ErrorLogger errors) {

		switch (record.recordType) {
			case Source.RECORD_TYPE_TV: {
				return SourceEditDataTV.makeSourceTV((ExtDbRecordTV)record, study, isLocked, errors);
			}
			case Source.RECORD_TYPE_WL: {
				return SourceEditDataWL.makeSourceWL((ExtDbRecordWL)record, study, isLocked, errors);
			}
			case Source.RECORD_TYPE_FM: {
				return SourceEditDataFM.makeSourceFM((ExtDbRecordFM)record, study, isLocked, errors);
			}
		}

		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int hashCode() {

		return key.hashCode();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean equals(Object other) {

		return (null != other) && ((SourceEditData)other).key.equals(key);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String toString() {

		return getCallSign() + " " + getChannel() + " " + getServiceCode() + " " + getStatus() + " " +
			getCity() + " " + getState();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get the next temporary key, used for sources not part of an existing study.

	protected static synchronized Integer getTemporaryKey() {

		return Integer.valueOf(nextTemporaryKey++);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Make an identical copy of an instance, used by editors to support cancel/undo actions.

	public abstract SourceEditData copy();


	//-----------------------------------------------------------------------------------------------------------------
	// Methods to derive a new source from an existing source, optionally changing the locked flag and study context.
	// See SourceEditDataTV for typical implementations.

	public SourceEditData deriveSource(boolean newIsLocked) {
		return deriveSource(study, newIsLocked, null);
	}

	public SourceEditData deriveSource(boolean newIsLocked, ErrorLogger errors) {
		return deriveSource(study, newIsLocked, errors);
	}

	public SourceEditData deriveSource(StudyEditData newStudy, boolean newIsLocked) {
		return deriveSource(newStudy, newIsLocked, null);
	}

	public abstract SourceEditData deriveSource(StudyEditData newStudy, boolean newIsLocked, ErrorLogger errors);


	//-----------------------------------------------------------------------------------------------------------------
	// Overridden by subclasses that have a geography property.

	public boolean isGeographyInUse(int theGeoKey) {
		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int getGeographyKey() {
		return 0;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Accessors for the attributes.  Attributes may or may not have a value; boolean attributes can simply be a test
	// for non-null return from getAttribute().

	public void setAttribute(String name) {

		attributes.put(name, "");
		attributesChanged = true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void setAttribute(String name, String value) {

		attributes.put(name, value);
		attributesChanged = true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getAttribute(String name) {

		return attributes.get(name);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String removeAttribute(String name) {

		return attributes.remove(name);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Attributes can be set by copying from another map or parsing from string representation.  These are used by the
	// subclasses when constructing new objects, so attributesChanged is not set.  This may include all attributes, or
	// only non-transient attributes.  Transient attributes are relevant only in the study context where the source was
	// first created, and so are not included when exporting to XML.

	protected void setAllAttributes(HashMap<String, String> theAttrs) {

		attributes.clear();
		attributes.putAll(theAttrs);
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected void setAllAttributesNT(HashMap<String, String> theAttrs) {

		attributes.clear();
		for (Map.Entry<String, String> e : theAttrs.entrySet()) {
			if (!e.getKey().startsWith(Source.TRANSIENT_ATTR_PREFIX)) {
				attributes.put(e.getKey(), e.getValue());
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected void setAllAttributes(String theAttrs) {

		doParseAttributes(theAttrs, false);
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected void setAllAttributesNT(String theAttrs) {

		doParseAttributes(theAttrs, true);
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doParseAttributes(String theAttrs, boolean ntOnly) {

		attributes.clear();

		String name, value;
		int e;
		for (String attr : theAttrs.split("\\n")) {
			e = attr.indexOf('=');
			if (e > 0) {
				name = attr.substring(0, e).trim();
				value = attr.substring(e + 1).trim();
			} else {
				name = attr.trim();
				value = "";
			}
			if (ntOnly && name.startsWith(Source.TRANSIENT_ATTR_PREFIX)) {
				continue;
			}
			attributes.put(name, value);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get string representation of attributes, for save and export code.  Can include all, or only non-transient.

	protected String getAllAttributes() {

		return formatAttributes(false);
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected String getAllAttributesNT() {

		return formatAttributes(true);
	}


	//-----------------------------------------------------------------------------------------------------------------

	private String formatAttributes(boolean ntOnly) {

		if (attributes.isEmpty()) {
			return "";
		}

		StringBuilder result = new StringBuilder();
		String value;
		for (Map.Entry<String, String> e : attributes.entrySet()) {
			if (ntOnly && e.getKey().startsWith(Source.TRANSIENT_ATTR_PREFIX)) {
				continue;
			}
			result.append(e.getKey());
			value = e.getValue();
			if (value.length() > 0) {
				result.append('=');
				result.append(value);
			}
			result.append('\n');
		}

		return result.toString();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Validity and change checks per usual pattern.  Subclasses will usually override to check additional properties,
	// if so they must call super if all other properties are valid, but if other invalidities are detected first this
	// does not have to be called, there are no side-effects here.  The subclass implementation can check isLocked and
	// immediately return true for a non-editable record.

	public boolean isDataValid() {
		return isDataValid(null);
	}

	public boolean isDataValid(ErrorLogger errors) {

		if (isLocked) {
			return true;
		}

		if (service.key < 1) {
			if (null != errors) {
				errors.reportValidationError("A service must be selected.");
			}
			return false;
		}
		if (country.key < 1) {
			if (null != errors) {
				errors.reportValidationError("A country must be selected.");
			}
			return false;
		}
		if (0 == callSign.length()) {
			if (null != errors) {
				errors.reportValidationError("A call sign or site ID must be provided.");
			}
			return false;
		}

		// To detect failure to enter coordinate values on new data, 0 is illegal for latitude and longitude.  That
		// means legitimate coordinates exactly on the equator or central meridian are rejected.  But the odds of such
		// coordinates actually being needed are very very small, whereas the odds of a user forgetting some fields on
		// new data entry are not so small.  Easy workaround if ever needed is to just enter coordinate 0-00-00.01.

		if ((0. == location.latitude) || (0. == location.longitude)) {
			if (null != errors) {
				errors.reportValidationError("Latitude and longitude must be provided.");
			}
			return false;
		}
		if ((location.latitude < Source.LATITUDE_MIN) || (location.latitude > Source.LATITUDE_MAX)) {
			if (null != errors) {
				errors.reportValidationError("Bad latitude, must be " + Source.LATITUDE_MIN + " to " +
					Source.LATITUDE_MAX + " degrees.");
			}
			return false;
		}
		if ((location.longitude < Source.LONGITUDE_MIN) || (location.longitude > Source.LONGITUDE_MAX)) {
			if (null != errors) {
				errors.reportValidationError("Bad longitude, must be " + Source.LONGITUDE_MIN + " to " +
					Source.LONGITUDE_MAX + " degrees.");
			}
			return false;
		}
		if ((heightAMSL < Source.HEIGHT_MIN) || (heightAMSL > Source.HEIGHT_MAX)) {
			if (null != errors) {
				errors.reportValidationError("Bad height AMSL, must be " + Source.HEIGHT_MIN + " to " +
					Source.HEIGHT_MAX + ".");
			}
			return false;
		}
		if ((overallHAAT < Source.HEIGHT_MIN) || (overallHAAT > Source.HEIGHT_MAX)) {
			if (null != errors) {
				errors.reportValidationError("Bad HAAT, must be " + Source.HEIGHT_MIN + " to " + Source.HEIGHT_MAX +
					".");
			}
			return false;
		}
		if ((peakERP < Source.ERP_MIN) || (peakERP > Source.ERP_MAX)) {
			if (null != errors) {
				errors.reportValidationError("Bad ERP, must be " + Source.ERP_MIN + " to " + Source.ERP_MAX + ".");
			}
			return false;
		}

		// Check the horizontal pattern.

		if (hasHorizontalPattern) {
			if (null != horizontalPattern) {
				if ((AntPattern.PATTERN_TYPE_HORIZONTAL != horizontalPattern.type) || !horizontalPattern.isSimple()) {
					if (null != errors) {
						errors.reportValidationError("Bad azimuth pattern, invalid object type or format.");
					}
					return false;
				}
				if (!horizontalPattern.isDataValid(errors)) {
					return false;
				}
			}
		} else {
			horizontalPattern = null;
		}

		// Azimuth and elevation pattern orientation and tilt values are always checked even if there is currently no
		// actual pattern data set; these are preserved regardless.

		if ((horizontalPatternOrientation < 0.) || (horizontalPatternOrientation >= 360.)) {
			if (null != errors) {
				errors.reportValidationError("Bad azimuth pattern orientation, must be 0 to less than 360.");
			}
			return false;
		}

		// Check vertical pattern similar to horizontal.

		if (hasVerticalPattern) {
			if (null != verticalPattern) {
				if ((AntPattern.PATTERN_TYPE_VERTICAL != verticalPattern.type) || !verticalPattern.isSimple()) {
					if (null != errors) {
						errors.reportValidationError("Bad elevation pattern, invalid object type or format.");
					}
					return false;
				}
				if (!verticalPattern.isDataValid(errors)) {
					return false;
				}
			}
		} else {
			verticalPattern = null;
		}

		if ((verticalPatternElectricalTilt < AntPattern.TILT_MIN) ||
				(verticalPatternElectricalTilt > AntPattern.TILT_MAX)) {
			if (null != errors) {
				errors.reportValidationError("Bad elevation pattern electrical tilt, must be " + AntPattern.TILT_MIN +
					" to " + AntPattern.TILT_MAX + ".");
			}
			return false;
		}
		if ((verticalPatternMechanicalTilt < AntPattern.TILT_MIN) ||
				(verticalPatternMechanicalTilt > AntPattern.TILT_MAX)) {
			if (null != errors) {
				errors.reportValidationError("Bad elevation pattern mechanical tilt, must be " + AntPattern.TILT_MIN +
					" to " + AntPattern.TILT_MAX + ".");
			}
			return false;
		}
		if ((verticalPatternMechanicalTiltOrientation < 0.) ||
				(verticalPatternMechanicalTiltOrientation >= 360.)) {
			if (null != errors) {
				errors.reportValidationError("Bad elevation pattern mechanical tilt orientation, " +
					"must be 0 to less than 360.");
			}
			return false;
		}

		// Check matrix pattern, as above.

		if (hasMatrixPattern) {
			if (null != matrixPattern) {
				if ((AntPattern.PATTERN_TYPE_VERTICAL != matrixPattern.type) || matrixPattern.isSimple()) {
					if (null != errors) {
						errors.reportValidationError("Bad elevation pattern, invalid object type or format.");
					}
					return false;
				}
				if (!matrixPattern.isDataValid(errors)) {
					return false;
				}
			}
		} else {
			matrixPattern = null;
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The attributes map can always be edited, but otherwise a locked record is non-editable and so cannot have
	// changes to individual properties.

	public boolean isDataChanged() {

		if (attributesChanged) {
			return true;
		}

		if (isLocked) {
			return false;
		}

		Source theSource = getSource();
		if (service.key != theSource.service.key) {
			return true;
		}
		if (country.key != theSource.country.key) {
			return true;
		}
		if (!callSign.equals(theSource.callSign)) {
			return true;
		}
		if (!city.equals(theSource.city)) {
			return true;
		}
		if (!state.equals(theSource.state)) {
			return true;
		}
		if (!fileNumber.equals(theSource.fileNumber)) {
			return true;
		}
		if (!location.equals(theSource.location)) {
			return true;
		}
		if (heightAMSL != theSource.heightAMSL) {
			return true;
		}
		if (overallHAAT != theSource.overallHAAT) {
			return true;
		}
		if (peakERP != theSource.peakERP) {
			return true;
		}
		if (hasHorizontalPattern != theSource.hasHorizontalPattern) {
			return true;
		}
		if ((null != horizontalPattern) && !horizontalPattern.name.equals(theSource.horizontalPatternName)) {
			return true;
		}
		if (horizontalPatternChanged) {
			return true;
		}
		if (horizontalPatternOrientation != theSource.horizontalPatternOrientation) {
			return true;
		}
		if (hasVerticalPattern != theSource.hasVerticalPattern) {
			return true;
		}
		if ((null != verticalPattern) && !verticalPattern.name.equals(theSource.verticalPatternName)) {
			return true;
		}
		if (verticalPatternChanged) {
			return true;
		}
		if (verticalPatternElectricalTilt != theSource.verticalPatternElectricalTilt) {
			return true;
		}
		if (verticalPatternMechanicalTilt != theSource.verticalPatternMechanicalTilt) {
			return true;
		}
		if (verticalPatternMechanicalTiltOrientation != theSource.verticalPatternMechanicalTiltOrientation) {
			return true;
		}
		if (hasMatrixPattern != theSource.hasMatrixPattern) {
			return true;
		}
		if ((matrixPattern != null) && !matrixPattern.name.equals(theSource.matrixPatternName)) {
			return true;
		}
		if (matrixPatternChanged) {
			return true;
		}
		if (useGenericVerticalPattern != theSource.useGenericVerticalPattern) {
			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save this record into source tables in a study, user record table, or import data set table.  Which of those is
	// current is up to the caller, this just needs an open database connection set to the correct context.  The code
	// in the subclasses can assume that isDataValid() and isDataChanged() have both previously been called, that is
	// the responsibility of the caller.  See e.g. StudyEditData.save().

	public abstract void save(DbConnection db) throws SQLException;


	//-----------------------------------------------------------------------------------------------------------------
	// Save pattern data, called from subclass save() implementations.

	protected void savePatterns(DbConnection db) throws SQLException {

		StringBuilder query;
		int startLength;
		String sep;

		if (horizontalPatternChanged) {

			db.update("DELETE FROM source_horizontal_pattern WHERE source_key=" + key);

			if (null != horizontalPattern) {

				query = new StringBuilder(
				"INSERT INTO source_horizontal_pattern (" +
					"source_key," +
					"azimuth," +
					"relative_field) " +
				"VALUES");
				startLength = query.length();
				sep = " (";
				for (AntPattern.AntPoint thePoint : horizontalPattern.getPoints()) {
					query.append(sep);
					query.append(String.valueOf(key));
					query.append(',');
					query.append(String.valueOf(thePoint.angle));
					query.append(',');
					query.append(String.valueOf(thePoint.relativeField));
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
		}

		if (verticalPatternChanged) {

			db.update("DELETE FROM source_vertical_pattern WHERE source_key=" + key);

			if (null != verticalPattern) {

				query = new StringBuilder(
				"INSERT INTO source_vertical_pattern (" +
					"source_key," +
					"depression_angle," +
					"relative_field) " +
				"VALUES");
				startLength = query.length();
				sep = " (";
				for (AntPattern.AntPoint thePoint : verticalPattern.getPoints()) {
					query.append(sep);
					query.append(String.valueOf(key));
					query.append(',');
					query.append(String.valueOf(thePoint.angle));
					query.append(',');
					query.append(String.valueOf(thePoint.relativeField));
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
		}

		if (matrixPatternChanged) {

			db.update("DELETE FROM source_matrix_pattern WHERE source_key=" + key);

			if (null != matrixPattern) {

				query = new StringBuilder(
				"INSERT INTO source_matrix_pattern (" +
					"source_key," +
					"azimuth," +
					"depression_angle," +
					"relative_field) " +
				"VALUES");
				startLength = query.length();
				sep = " (";
				for (AntPattern.AntSlice theSlice : matrixPattern.getSlices()) {
					for (AntPattern.AntPoint thePoint : theSlice.points) {
						query.append(sep);
						query.append(String.valueOf(key));
						query.append(',');
						query.append(String.valueOf(theSlice.value));
						query.append(',');
						query.append(String.valueOf(thePoint.angle));
						query.append(',');
						query.append(String.valueOf(thePoint.relativeField));
						if (query.length() > DbCore.MAX_QUERY_LENGTH) {
							query.append(')');
							db.update(query.toString());
							query.setLength(startLength);
							sep = " (";
						} else {
							sep = "),(";
						}
					}
				}
				if (query.length() > startLength) {
					query.append(')');
					db.update(query.toString());
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set and retrieve a comment for a source.  This is not part of the main data model, it is stored directly in the
	// database.  This is a non-critical auxiliary function that has no concurrency protection and ignores errors.
	// Currently it only works for sources derived from user records, however it takes the source object as argument
	// so the capability may be expanded in the future with additional backing stores using various other keys.

	private static HashMap<String, HashMap<Integer, String>> userRecordCommentCaches =
		new HashMap<String, HashMap<Integer, String>>();

	public static void setSourceComment(SourceEditData theSource, String theComment) {

		if (null == theSource.userRecordID) {
			return;
		}

		if (null == theComment) {
			theComment = "";
		} else {
			theComment = theComment.trim();
		}

		HashMap<Integer, String> theCache = userRecordCommentCaches.get(theSource.dbID);
		if (null == theCache) {
			theCache = new HashMap<Integer, String>();
			userRecordCommentCaches.put(theSource.dbID, theCache);
		}
		theCache.put(theSource.userRecordID, theComment);

		DbConnection db = DbCore.connectDb(theSource.dbID);
		if (null != db) {
			try {
				db.update("UPDATE user_record SET comment = '" + db.clean(theComment) + "' WHERE user_record_id = " +
					theSource.userRecordID);
			} catch (SQLException se) {
				db.reportError(se);
			}
			DbCore.releaseDb(db);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// A not-found condition from the query puts an empty string in the cache.  Any error returns an empty string.

	public static String getSourceComment(SourceEditData theSource) {

		if (null == theSource.userRecordID) {
			return "";
		}

		HashMap<Integer, String> theCache = userRecordCommentCaches.get(theSource.dbID);
		if (null == theCache) {
			theCache = new HashMap<Integer, String>();
			userRecordCommentCaches.put(theSource.dbID, theCache);
		}

		String theComment = theCache.get(theSource.userRecordID);
		if (null != theComment) {
			return theComment;
		}

		theComment = "";

		DbConnection db = DbCore.connectDb(theSource.dbID);
		if (null != db) {
			try {
				db.query("SELECT comment FROM user_record WHERE user_record_id = " + theSource.userRecordID);
				if (db.next()) {
					theComment = db.getString(1);
				}
			} catch (SQLException se) {
				db.reportError(se);
			}
			DbCore.releaseDb(db);
		}

		theCache.put(theSource.userRecordID, theComment);

		return theComment;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save this source as a user record for use outside any specific study context, e.g. see IxCheckAPI and RecordAPI.
	// If the save is successful, a new source representing the user record is returned.  That source will have a new
	// user record ID, no study context, and is always locked.  User records are not editable, they are permanent and
	// immutable just like external station data records, so user record sources can be shared; see StudyEditData for
	// details.  This is typically called on a new source just created from user input, however it may be used on any
	// record, except for a replication record.  Since the result does not have a study context, replication is not
	// possible.  First do a validity check, source must be valid to be saved.

	public SourceEditData saveAsUserRecord() {
		return saveAsUserRecord(null);
	}

	public SourceEditData saveAsUserRecord(ErrorLogger errors) {

		if (isReplication()) {
			if (null != errors) {
				errors.reportError("Replication record cannot be saved as a user record.");
			}
			return null;
		}

		if (!isDataValid(errors)) {
			return null;
		}

		// Create the XML data.  This needs an error logger for trapping errors in the writer, create one if needed.

		if (null != errors) {
			errors.clearErrors();
		} else {
			errors = new ErrorLogger(null, null);
		}

		StringWriter theWriter = new StringWriter();

		try {
			writeToXML(theWriter, errors);
		} catch (IOException e) {
			errors.reportError(e.toString());
		}

		if (errors.hasErrors()) {
			return null;
		}

		String sourceData = theWriter.toString();

		// Save the new data, assign a record ID in the process.  The full source data is saved as XML, however a
		// subset of source properties are also saved as separate fields in the record table to support SQL searches.
		// Note an empty comment is added to the cache, see getRecordComment().

		Integer theUserRecordID = null;
		boolean error = false;
		String errmsg = "";

		DbConnection db = DbCore.connectDb(dbID, errors);
		if (null != db) {

			HashMap<Integer, String> theCache = userRecordCommentCaches.get(dbID);
			if (null == theCache) {
				theCache = new HashMap<Integer, String>();
				userRecordCommentCaches.put(dbID, theCache);
			}

			try {

				db.update("LOCK TABLES user_record WRITE, user_record_id_sequence WRITE");

				db.update("UPDATE user_record_id_sequence SET user_record_id = user_record_id + 1");
				db.query("SELECT user_record_id FROM user_record_id_sequence");
				db.next();
				theUserRecordID = Integer.valueOf(db.getInt(1));

				int facID = 0, chan = 0;
				switch (recordType) {
					case Source.RECORD_TYPE_TV: {
						facID = ((SourceEditDataTV)this).facilityID;
						chan = ((SourceEditDataTV)this).channel;
						break;
					}
					case Source.RECORD_TYPE_FM: {
						facID = ((SourceEditDataFM)this).facilityID;
						chan = ((SourceEditDataFM)this).channel;
						break;
					}
				}

				db.update(
				"INSERT INTO user_record (" +
					"user_record_id," +
					"record_type," +
					"xml_data," +
					"facility_id," +
					"service_key," +
					"call_sign," +
					"status," +
					"channel," +
					"city," +
					"state," +
					"country," +
					"file_number," +
					"comment) " +
				"VALUES (" +
					theUserRecordID + "," +
					recordType + "," +
					"'" + db.clean(sourceData) + "'," +
					facID + "," +
					service.key + "," +
					"'" + db.clean(getCallSign()) + "'," +
					"'" + db.clean(getStatus()) + "'," +
					chan + "," +
					"'" + db.clean(getCity()) + "'," +
					"'" + db.clean(getState()) + "'," +
					"'" + db.clean(getCountryCode()) + "'," +
					"'" + db.clean(getFileNumber()) + "'," +
					"'')");

				theCache.put(theUserRecordID, "");

			} catch (SQLException se) {
				error = true;
				errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
				db.reportError(se);
			}

			try {
				db.update("UNLOCK TABLES");
			} catch (SQLException se) {
				db.reportError(se);
			}

			DbCore.releaseDb(db);

			if (error) {
				errors.reportError(errmsg);
				return null;
			}

		} else {
			return null;
		}

		return readSourceFromXML(dbID, new StringReader(sourceData), null, theUserRecordID, errors);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Delete a user record.

	public static boolean deleteUserRecord(String theDbID, Integer theUserRecordID) {
		return deleteUserRecord(theDbID, theUserRecordID, null);
	}

	public static boolean deleteUserRecord(String theDbID, Integer theUserRecordID, ErrorLogger errors) {

		if (null == theUserRecordID) {
			return false;
		}

		boolean error = false;
		String errmsg = "";

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null != db) {

			try {

				db.update("LOCK TABLES user_record WRITE");

				db.update("DELETE FROM user_record WHERE user_record_id = " + theUserRecordID);

			} catch (SQLException se) {
				error = true;
				errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
				db.reportError(se);
			}

			try {
				db.update("UNLOCK TABLES");
			} catch (SQLException se) {
				db.reportError(se);
			}

			DbCore.releaseDb(db);

			if (error) {
				errors.reportError(errmsg);
				return false;
			}

			HashMap<Integer, String> theCache = userRecordCommentCaches.get(theDbID);
			if (null != theCache) {
				theCache.remove(theUserRecordID);
			}

			return true;

		} else {
			return false;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Do a search of the user record table.  Note errors parsing the XML are ignored here, those just cause the
	// record to not be included in the results.  This must always match a specific record type and is assumed to be
	// occurring outside any study context; user records are never added to a study en masse so a multi-record query
	// is always a pre-search in advance of the user selecting specific records to actually be added to a study.

	public static ArrayList<SourceEditData> findUserRecords(String theDbID, int recordType, String query) {
		return findUserRecords(theDbID, recordType, query, null);
	}

	public static ArrayList<SourceEditData> findUserRecords(String theDbID, int recordType, String query,
			ErrorLogger errors) {

		ArrayList<SourceEditData> results = new ArrayList<SourceEditData>();

		SourceEditData theSource;
		Integer theID;

		String whrStr;
		if ((null != query) && (query.length() > 0)) {
			whrStr =
			"WHERE " +
				"(" + query + ") AND ";
		} else {
			whrStr =
			"WHERE ";
		}

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null != db) {

			HashMap<Integer, String> theCache = userRecordCommentCaches.get(theDbID);
			if (null == theCache) {
				theCache = new HashMap<Integer, String>();
				userRecordCommentCaches.put(theDbID, theCache);
			}

			// The XML parser always needs a logger, use a dummy so it doesn't have to always create a temporary one.

			ErrorLogger xmlErrors = new ErrorLogger(null, null);

			try {

				db.query(
				"SELECT " +
					"user_record_id, " +
					"xml_data, " +
					"comment " +
				"FROM " +
					"user_record " +
				whrStr +
					"(record_type = " + recordType + ")");

				while (db.next()) {
					theID = Integer.valueOf(db.getInt(1));
					theSource = readSourceFromXML(theDbID, new StringReader(db.getString(2)), null, theID, xmlErrors);
					if (null != theSource) {
						results.add(theSource);
						theCache.put(theID, db.getString(3));
					}
				}

				DbCore.releaseDb(db);

			} catch (SQLException se) {
				DbCore.releaseDb(db);
				results = null;
				db.reportError(errors, se);
			}
		}

		return results;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Load a source from the user record table.  Note record comments are included in the queries and added to the
	// comment cache directly, see getRecordComment().

	public static SourceEditData findUserRecord(String theDbID, Integer theUserRecordID) {
		return findUserRecord(theDbID, null, theUserRecordID, null);
	}

	public static SourceEditData findUserRecord(String theDbID, Integer theUserRecordID, ErrorLogger errors) {
		return findUserRecord(theDbID, null, theUserRecordID, errors);
	}

	public static SourceEditData findUserRecord(String theDbID, StudyEditData theStudy, Integer theUserRecordID) {
		return findUserRecord(theDbID, theStudy, theUserRecordID, null);
	}

	public static SourceEditData findUserRecord(String theDbID, StudyEditData theStudy, Integer theUserRecordID,
			ErrorLogger errors) {

		String sourceData = null;
		boolean notfound = false;

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null != db) {

			HashMap<Integer, String> theCache = userRecordCommentCaches.get(theDbID);
			if (null == theCache) {
				theCache = new HashMap<Integer, String>();
				userRecordCommentCaches.put(theDbID, theCache);
			}

			try {
				db.query("SELECT xml_data, comment FROM user_record WHERE user_record_id = " + theUserRecordID);
				if (db.next()) {
					sourceData = db.getString(1);
					theCache.put(theUserRecordID, db.getString(2));
				} else {
					notfound = true;
				}
				DbCore.releaseDb(db);
			} catch (SQLException se) {
				DbCore.releaseDb(db);
				DbConnection.reportError(errors, se);
				return null;
			}
		}

		if (notfound) {
			if (null != errors) {
				errors.reportError("User record not found for record ID '" + theUserRecordID + "'.");
			}
			return null;
		}

		return readSourceFromXML(theDbID, new StringReader(sourceData), theStudy, theUserRecordID, errors);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Do a search of a generic import data set, which are stored using Source tables exactly as in a study database.
	// The query methods in Source are used to retrieve records which are then wrapped in SourceEditData objects.
	// Although stored using internal formats, these data sets are managed in the UI like other specific-format import
	// data sets hence the API is similar to those; see ExtDb and ExtDbRecord for details on the concepts and patterns.

	public static LinkedList<SourceEditData> findImportRecords(ExtDb extDb, String query) {
		return findImportRecords(extDb, query, null, 0., 0., null);
	}

	public static LinkedList<SourceEditData> findImportRecords(ExtDb extDb, String query, ErrorLogger errors) {
		return findImportRecords(extDb, query, null, 0., 0., errors);
	}

	public static LinkedList<SourceEditData> findImportRecords(ExtDb extDb, String query, GeoPoint searchCenter,
			double searchRadius, double kmPerDegree) {
		return findImportRecords(extDb, query, searchCenter, searchRadius, kmPerDegree, null);
	}

	public static LinkedList<SourceEditData> findImportRecords(ExtDb extDb, String query, GeoPoint searchCenter,
			double searchRadius, double kmPerDegree, ErrorLogger errors) {

		if (!extDb.isGeneric()) {
			return new LinkedList<SourceEditData>();
		}

		DbConnection db = extDb.connectDb(errors);
		if (null == db) {
			return null;
		}

		// First retrieve Source subclass objects matching the SQL, the distance search criteria can't be done in SQL
		// so that has to be done separately below.

		ArrayList<Source> theSources = new ArrayList<Source>();

		try {

			switch (extDb.recordType) {

				case Source.RECORD_TYPE_TV: {
					SourceTV.getSources(db, extDb.dbID, extDb.dbName, query, theSources);
					break;
				}

				case Source.RECORD_TYPE_WL: {
					SourceWL.getSources(db, extDb.dbID, extDb.dbName, query, theSources);
					break;
				}

				case Source.RECORD_TYPE_FM: {
					SourceFM.getSources(db, extDb.dbID, extDb.dbName, query, theSources);
					break;
				}
			}

			extDb.releaseDb(db);

		} catch (SQLException se) {
			extDb.releaseDb(db);
			DbConnection.reportError(errors, se);
			return null;
		}

		// Apply distance search as needed, create SourceEditData objects.

		LinkedList<SourceEditData> results = new LinkedList<SourceEditData>();

		SourceEditData newSource;
		boolean skip;

		for (Source theSource : theSources) {

			if ((null != searchCenter) && (searchRadius > 0.)) {
				skip = true;
				if (theSource.service.isDTS) {
					for (SourceTV dtsSource : ((SourceTV)theSource).dtsSources) {
						if (searchCenter.distanceTo(dtsSource.location, kmPerDegree) <= searchRadius) {
							skip = false;
							break;
						}
					}
				} else {
					if (searchCenter.distanceTo(theSource.location, kmPerDegree) <= searchRadius) {
						skip = false;
					}
				}
				if (skip) {
					continue;
				}
			}

			newSource = getInstance(null, theSource);
			if (null != newSource) {
				results.add(newSource);
			}
		}

		return results;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Load a specific source from a generic import record table by record ID.

	public static SourceEditData findImportRecord(ExtDb extDb, String theRecordID) {
		return findImportRecord(extDb, null, theRecordID, null);
	}

	public static SourceEditData findImportRecord(ExtDb extDb, String theRecordID, ErrorLogger errors) {
		return findImportRecord(extDb, null, theRecordID, errors);
	}

	public static SourceEditData findImportRecord(ExtDb extDb, StudyEditData theStudy, String theRecordID) {
		return findImportRecord(extDb, theStudy, theRecordID, null);
	}

	public static SourceEditData findImportRecord(ExtDb extDb, StudyEditData theStudy, String theRecordID,
			ErrorLogger errors) {

		if (!extDb.isGeneric()) {
			return null;
		}

		StringBuilder query = new StringBuilder();
		try {
			addRecordIDQuery(extDb.type, theRecordID, query, false);
		} catch (IllegalArgumentException ie) {
			if (null != errors) {
				errors.logMessage(ie.getMessage());
			}
			return null;
		}

		LinkedList<SourceEditData> theSources = findImportRecords(extDb, query.toString(), null, 0., 0., errors);

		if (null == theSources) {
			return null;
		}
		if (theSources.isEmpty()) {
			if (null != errors) {
				errors.logMessage("Record not found for record ID '" + theRecordID + "'.");
			}
			return null;
		}

		return theSources.getFirst();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Methods to support composing query clauses for findUserRecords() and findImportRecords().  These are patterned
	// after like-named methods in ExtDbRecord and use constants and some support methods from that class, see there
	// for details.  Note neither user nor import record tables are versioned so there is no version argument here.  If
	// the dbType is DB_TYPE_NOT_SET this is for the user record table, otherwise for a generic import data set.  There
	// are only a few minor difference in syntax between those two cases.  Generic import sets are stored in native
	// Source tables as in a study database, user records in a special root database table where most of the data is
	// embedded as XML, however the searchable field names in the user record table match those in the Source tables.

	public static boolean addRecordIDQuery(int dbType, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if ((null == str) || (0 == str.length())) {
			return false;
		}

		if (!ExtDb.isGeneric(dbType) && (ExtDb.DB_TYPE_NOT_SET != dbType)) {
			throw new IllegalArgumentException(ExtDbRecord.BAD_TYPE_MESSAGE);
		}

		if (ExtDb.DB_TYPE_NOT_SET == dbType) {
			int theID = 0;
			try {
				theID = Integer.parseInt(str);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("The user record ID must be a number.");
			}
			if (theID <= 0) {
				throw new IllegalArgumentException("The user record ID must be greater than 0.");
			}
		}

		if (combine) {
			query.append(" AND ");
		}

		if (ExtDb.DB_TYPE_NOT_SET == dbType) {
			query.append("(user_record_id = ");
			query.append(str);
		} else {
			query.append("(ext_record_id = '");
			query.append(str);
			query.append("'");
		}
		query.append(')');

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Search by file number.

	public static boolean addFileNumberQuery(int dbType, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if ((null == str) || (0 == str.length())) {
			return false;
		}

		String[] parts = ExtDbRecord.parseFileNumber(str);

		return addFileNumberQuery(dbType, parts[0], parts[1], query, combine);
	}

	public static boolean addFileNumberQuery(int dbType, String prefix, String arn, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if (!ExtDb.isGeneric(dbType) && (ExtDb.DB_TYPE_NOT_SET != dbType)) {
			throw new IllegalArgumentException(ExtDbRecord.BAD_TYPE_MESSAGE);
		}

		if (combine) {
			query.append(" AND ");
		}

		if (prefix.length() > 0) {
			query.append("(UPPER(file_number) = '");
			query.append(prefix);
			query.append(arn);
		} else {
			query.append("(UPPER(file_number) LIKE '%");
			query.append(arn);
		}
		query.append("')");

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query clause to search by facility ID.

	public static boolean addFacilityIDQuery(int dbType, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if ((null == str) || (0 == str.length())) {
			return false;
		}

		int facilityID = 0;
		try {
			facilityID = Integer.parseInt(str);
		} catch (NumberFormatException ne) {
			throw new IllegalArgumentException("The facility ID must be a number.");
		}

		return addFacilityIDQuery(dbType, facilityID, query, combine);
	}

	public static boolean addFacilityIDQuery(int dbType, int facilityID, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if (!ExtDb.isGeneric(dbType) && (ExtDb.DB_TYPE_NOT_SET != dbType)) {
			throw new IllegalArgumentException(ExtDbRecord.BAD_TYPE_MESSAGE);
		}

		if (combine) {
			query.append(" AND ");
		}

		query.append("(facility_id = ");
		query.append(String.valueOf(facilityID));
		query.append(')');

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query clause to search by service.

	public static boolean addServiceQuery(int dbType, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if ((null == str) || (0 == str.length())) {
			return false;
		}

		Service theService = Service.getService(str);
		if (null == theService) {
			throw new IllegalArgumentException("Unknown service code.");
		}

		return addServiceQuery(dbType, theService.key, query, combine);
	}

	public static boolean addServiceQuery(int dbType, int serviceKey, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if (!ExtDb.isGeneric(dbType) && (ExtDb.DB_TYPE_NOT_SET != dbType)) {
			throw new IllegalArgumentException(ExtDbRecord.BAD_TYPE_MESSAGE);
		}

		if (combine) {
			query.append(" AND ");
		}

		query.append("(service_key = ");
		query.append(String.valueOf(serviceKey));
		query.append(')');

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query search clause for call sign.

	public static boolean addCallSignQuery(int dbType, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if ((null == str) || (0 == str.length())) {
			return false;
		}

		if (!ExtDb.isGeneric(dbType) && (ExtDb.DB_TYPE_NOT_SET != dbType)) {
			throw new IllegalArgumentException(ExtDbRecord.BAD_TYPE_MESSAGE);
		}

		if (combine) {
			query.append(" AND ");
		}

		query.append("(UPPER(call_sign) REGEXP '^D*");
		query.append(DbConnection.clean(str.toUpperCase()));
		query.append(".*')");

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query search clause for a specific channel search.

	public static boolean addChannelQuery(int dbType, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {
		return addChannelQuery(dbType, str, 0, 0, query, combine);
	}

	public static boolean addChannelQuery(int dbType, String str, int minimumChannel, int maximumChannel,
			StringBuilder query, boolean combine) throws IllegalArgumentException {

		if ((null == str) || (0 == str.length())) {
			return false;
		}

		int channel = 0;
		try {
			channel = Integer.parseInt(str);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("The channel must be a number.");
		}

		return addChannelQuery(dbType, channel, minimumChannel, maximumChannel, query, combine);
	}

	public static boolean addChannelQuery(int dbType, int channel, StringBuilder query, boolean combine)
			throws IllegalArgumentException {
		return addChannelQuery(dbType, channel, 0, 0, query, combine);
	}

	public static boolean addChannelQuery(int dbType, int channel, int minimumChannel, int maximumChannel,
			StringBuilder query, boolean combine) throws IllegalArgumentException {

		if ((minimumChannel > 0) && (maximumChannel > 0) &&
				((channel < minimumChannel) || (channel > maximumChannel))) {
			throw new IllegalArgumentException("The channel must be in the range " + minimumChannel + " to " +
				maximumChannel + ".");
		}

		if (!ExtDb.isGeneric(dbType) && (ExtDb.DB_TYPE_NOT_SET != dbType)) {
			throw new IllegalArgumentException(ExtDbRecord.BAD_TYPE_MESSAGE);
		}

		if (combine) {
			query.append(" AND ");
		}

		query.append("(channel = ");
		query.append(String.valueOf(channel));
		query.append(')');

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query search clause for a channel range search.

	public static boolean addChannelRangeQuery(int dbType, int minimumChannel, int maximumChannel, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if (!ExtDb.isGeneric(dbType) && (ExtDb.DB_TYPE_NOT_SET != dbType)) {
			throw new IllegalArgumentException(ExtDbRecord.BAD_TYPE_MESSAGE);
		}

		if (combine) {
			query.append(" AND ");
		}

		query.append("(channel BETWEEN ");
		query.append(String.valueOf(minimumChannel));
		query.append(" AND ");
		query.append(String.valueOf(maximumChannel));
		query.append(')');

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query search clause for matching a list of channels.

	public static boolean addMultipleChannelQuery(int dbType, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if (!ExtDb.isGeneric(dbType) && (ExtDb.DB_TYPE_NOT_SET != dbType)) {
			throw new IllegalArgumentException(ExtDbRecord.BAD_TYPE_MESSAGE);
		}

		if (combine) {
			query.append(" AND ");
		}

		query.append("(channel IN ");
		query.append(str);
		query.append(')');

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query search clause for status.

	public static boolean addStatusQuery(int dbType, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if ((null == str) || (0 == str.length())) {
			return false;
		}

		return addStatusQuery(dbType, ExtDbRecord.getStatusType(str), query, combine);
	}

	public static boolean addStatusQuery(int dbType, int statusType, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		switch (statusType) {

			case ExtDbRecord.STATUS_TYPE_STA:
			case ExtDbRecord.STATUS_TYPE_CP:
			case ExtDbRecord.STATUS_TYPE_LIC:
			case ExtDbRecord.STATUS_TYPE_APP:
			case ExtDbRecord.STATUS_TYPE_EXP: {
				break;
			}

			default: {
				throw new IllegalArgumentException("Unknown status code.");
			}
		}

		if (!ExtDb.isGeneric(dbType) && (ExtDb.DB_TYPE_NOT_SET != dbType)) {
			throw new IllegalArgumentException(ExtDbRecord.BAD_TYPE_MESSAGE);
		}

		if (combine) {
			query.append(" AND ");
		}

		query.append("(UPPER(status) = '");
		query.append(ExtDbRecord.STATUS_CODES[statusType]);
		query.append("')");

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add query clause for city name search.

	public static boolean addCityQuery(int dbType, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if ((null == str) || (0 == str.length())) {
			return false;
		}

		if (!ExtDb.isGeneric(dbType) && (ExtDb.DB_TYPE_NOT_SET != dbType)) {
			throw new IllegalArgumentException(ExtDbRecord.BAD_TYPE_MESSAGE);
		}

		if (combine) {
			query.append(" AND ");
		}

		query.append("(UPPER(city) LIKE '%");
		query.append(DbConnection.clean(str.toUpperCase()).replace('*', '%'));
		query.append("%')");

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add query clause for state code search.

	public static boolean addStateQuery(int dbType, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if ((null == str) || (0 == str.length())) {
			return false;
		}

		if (!ExtDb.isGeneric(dbType) && (ExtDb.DB_TYPE_NOT_SET != dbType)) {
			throw new IllegalArgumentException(ExtDbRecord.BAD_TYPE_MESSAGE);
		}

		if (combine) {
			query.append(" AND ");
		}

		query.append("(UPPER(state) = '");
		query.append(DbConnection.clean(str.toUpperCase()));
		query.append("')");

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add clause for country search.

	public static boolean addCountryQuery(int dbType, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		Country country = Country.getCountry(str);
		if (null == country) {
			throw new IllegalArgumentException("Unknown country code.");
		}

		return addCountryQuery(dbType, country, query, combine);
	}

	public static boolean addCountryQuery(int dbType, int countryKey, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		Country country = Country.getCountry(countryKey);
		if (null == country) {
			throw new IllegalArgumentException("Unknown country key.");
		}

		return addCountryQuery(dbType, country, query, combine);
	}

	public static boolean addCountryQuery(int dbType, Country country, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if (!ExtDb.isGeneric(dbType) && (ExtDb.DB_TYPE_NOT_SET != dbType)) {
			throw new IllegalArgumentException(ExtDbRecord.BAD_TYPE_MESSAGE);
		}

		if (combine) {
			query.append(" AND ");
		}

		if (ExtDb.DB_TYPE_NOT_SET == dbType) {
			query.append("(UPPER(country) = '");
			query.append(country.countryCode);
			query.append("')");
		} else {
			query.append("(country_key = ");
			query.append(country.key);
			query.append(")");
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Encode source data as an XML description, usually as part of a scenario structure but this may also be used
	// for a standalone source export.  Locked sources based on station data records are usually exported by reference
	// using the record ID, without any other attributes.  Those will be re-loaded on import.  The station data key is
	// specific to the database installation so is not valid in an export context.  On import, external data references
	// are resolved against whatever station data is current for the import context.  That means the resulting source
	// may not be identical to that appearing in the export context, and import may fail if the export and import
	// contexts are based on different station data types.  However some cross-data cases can be resolved so the
	// decision is left up to the import code.  All of that is desired behavior for scenario exports since the purpose
	// is to move a scenario "in concept" from one study to another, not necessarily moving identical parameters.
	// Unlocked sources whether based on a data record or not, and locked sources not based on a data record, are
	// exported with all attributes and so are fully reconstructed on import.  However if an unlocked source was based
	// on a data record and is later reverted after import, it may revert to a different state than in the original
	// export context.  For a replication source, the original source is actually exported with an extra attribute
	// providing the replication channel.  On import, the original source is first reconstructed from the exported
	// element, then the replication source is re-derived from that.  If the standalone flag is true this is not part
	// of a scenario export.  In that case the desired/undesired flags are irrelevant and those arguments are ignored.
	// The exported record will always be unlocked regardless of it's actual state, hence all parameters will always
	// be explicit, and the record ID will never be included in any case.  When a standalone-exported record is
	// imported again it is always an isolated editable record, see RecordFind.  Note this is also used to store user
	// records internally, those are always locked but the parser will force the locked flag when it knows it is
	// parsing internal data for a user record.  A user record ID is never included in an export, that is valid only
	// for a specific database installation.

	public boolean writeToXML(Writer xml) throws IOException {
		return writeToXML(xml, null);
	}

	public boolean writeToXML(Writer xml, ErrorLogger errors) throws IOException {

		xml.append("<TVSTUDY VERSION=\"" + ParseXML.XML_VERSION + "\">\n");
		boolean result = writeToXML(xml, true, false, false, errors);
		xml.append("</TVSTUDY>\n");

		return result;
	}

	public boolean writeToXML(Writer xml, boolean isDesiredFlag, boolean isUndesiredFlag) throws IOException {
		return writeToXML(xml, false, isDesiredFlag, isUndesiredFlag, null);
	}

	public boolean writeToXML(Writer xml, boolean isDesiredFlag, boolean isUndesiredFlag, ErrorLogger errors)
			throws IOException {
		return writeToXML(xml, false, isDesiredFlag, isUndesiredFlag, errors);
	}

	protected abstract boolean writeToXML(Writer xml, boolean standalone, boolean isDesiredFlag,
			boolean isUndesiredFlag, ErrorLogger errors) throws IOException;


	//-----------------------------------------------------------------------------------------------------------------
	// Write multiple isolated sources to an XML file.

	public static boolean writeSourcesToXML(Writer xml, ArrayList<SourceEditData> sources) throws IOException {
		return writeSourcesToXML(xml, sources, null);
	}

	public static boolean writeSourcesToXML(Writer xml, ArrayList<SourceEditData> sources, ErrorLogger errors)
			throws IOException {

		xml.append("<TVSTUDY VERSION=\"" + ParseXML.XML_VERSION + "\">\n");
		boolean result = true;
		for (SourceEditData theSource : sources) {
			if (!theSource.writeToXML(xml, true, false, false, errors)) {
				result = false;
				break;
			}
		}
		xml.append("</TVSTUDY>\n");

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by the subclass implementation of writeToXML(), write out the superclass property attributes to a tag
	// being written for an element, then close the tag and add non-transient attributes (in the element content) and
	// nested pattern data elements as needed.  That may involve actually loading the pattern data if needed.

	protected void writeAttributes(Writer xml, ErrorLogger errors) throws IOException {

		location.writeAttributes(xml);

		xml.append(" HAMSL=\"" + AppCore.formatHeight(heightAMSL) + '"');
		xml.append(" HAAT=\"" + AppCore.formatHeight(overallHAAT) + '"');
		xml.append(" ERP=\"" + AppCore.formatERP(peakERP) + '"');
		if ((null != antennaID) && (antennaID.length() > 0)) {
			xml.append(" ANTENNA_ID=\"" + antennaID + '"');
		}
		xml.append(" USE_GENERIC=\"" + useGenericVerticalPattern + '"');

		// Orientation and tilt parameters are always included, they don't apply in all cases but the values are
		// preserved in case they might be relevant again if the patterns are changed.

		xml.append(" APAT_ORIENT=\"" + AppCore.formatAzimuth(horizontalPatternOrientation) + '"');
		xml.append(" EPAT_ETILT=\"" + AppCore.formatDepression(verticalPatternElectricalTilt) + '"');
		xml.append(" EPAT_MTILT=\"" + AppCore.formatDepression(verticalPatternMechanicalTilt) + '"');
		xml.append(" EPAT_ORIENT=\"" + AppCore.formatDepression(verticalPatternMechanicalTiltOrientation) + '"');

		// Try to load pattern data as needed.  If that fails an error is reported but the XML is still created with
		// the pattern(s) reverted to omni/generic.

		Source source = getSource();

		boolean hasHpat = hasHorizontalPattern;
		if (hasHpat) {
			if ((null == horizontalPattern) && (null != source)) {
				horizontalPattern = source.getHorizontalPattern(errors);
			}
			if (null == horizontalPattern) {
				hasHpat = false;
			}
		}
		xml.append(" HAS_APAT=\"" + hasHpat + '"');
		if (hasHpat) {
			xml.append(" APAT_NAME=\"" + AppCore.xmlclean(horizontalPattern.name) + '"');
		}

		boolean hasVpat = hasVerticalPattern && !hasMatrixPattern;
		if (hasVpat) {
			if ((null == verticalPattern) && (null != source)) {
				verticalPattern = source.getVerticalPattern(errors);
			}
			if (null == verticalPattern) {
				hasVpat = false;
			}
		}
		xml.append(" HAS_EPAT=\"" + hasVpat + '"');
		if (hasVpat) {
			xml.append(" EPAT_NAME=\"" + AppCore.xmlclean(verticalPattern.name) + '"');
		}

		boolean hasMpat = hasMatrixPattern;
		if (hasMpat) {
			if ((null == matrixPattern) && (null != source)) {
				matrixPattern = source.getMatrixPattern(errors);
			}
			if (null == matrixPattern) {
				hasMpat = false;
			}
		}
		xml.append(" HAS_MPAT=\"" + hasMpat + '"');
		if (hasMpat) {
			xml.append(" MPAT_NAME=\"" + AppCore.xmlclean(matrixPattern.name) + '"');
		}

		xml.append(">\n");

		// Write all non-transient attributes.

		xml.append(AppCore.xmlclean(getAllAttributesNT()));

		// Write pattern data elements as needed.

		if (hasHpat) {
			xml.append("<APAT>\n");
			for (AntPattern.AntPoint point : horizontalPattern.getPoints()) {
				xml.append(AppCore.formatAzimuth(point.angle));
				xml.append(',');
				xml.append(AppCore.formatRelativeField(point.relativeField));
				xml.append('\n');
			}
			xml.append("</APAT>\n");
		}

		if (hasVpat) {
			xml.append("<EPAT>\n");
			for (AntPattern.AntPoint point : verticalPattern.getPoints()) {
				xml.append(AppCore.formatDepression(point.angle));
				xml.append(',');
				xml.append(AppCore.formatRelativeField(point.relativeField));
				xml.append('\n');
			}
			xml.append("</EPAT>\n");
		}

		if (hasMpat) {
			xml.append("<MPAT>\n");
			for (AntPattern.AntSlice slice : matrixPattern.getSlices()) {
				for (AntPattern.AntPoint point : slice.points) {
					xml.append(AppCore.formatAzimuth(slice.value));
					xml.append(',');
					xml.append(AppCore.formatDepression(point.angle));
					xml.append(',');
					xml.append(AppCore.formatRelativeField(point.relativeField));
					xml.append('\n');
				}
			}
			xml.append("</MPAT>\n");
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Parse XML to obtain a user record, this is private, the only public way to obtain user records is with the
	// findUserRecord*() methods.  Currently there is no need for a single-source XML parser outside this class, to
	// generalize this for other purposes significant changes would be needed in ParseXML.  Other classes can use
	// readSourcesFromXML() instead which is more generalized.

	private static SourceEditData readSourceFromXML(String theDbID, Reader xml, StudyEditData theStudy,
			Integer theUserRecordID, ErrorLogger errors) {

		if (null != errors) {
			errors.clearErrors();
		} else {
			errors = new ErrorLogger(null, null);
		}

		try {

			ParseXML handler = new ParseXML(theStudy, theDbID, theUserRecordID, errors);

			XMLReader xReader = XMLReaderFactory.createXMLReader("org.apache.xerces.parsers.SAXParser");
			xReader.setContentHandler(handler);
			xReader.parse(new InputSource(xml));

			if (null == handler.source) {
				errors.reportError("Invalid XML structure in user record data.");
				return null;
			}

			return handler.source;

		} catch (SAXException se) {
			String msg = se.getMessage();
			if ((null != msg) && (msg.length() > 0)) {
				errors.reportError("XML error: " + msg);
			}
		} catch (Throwable t) {
			AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
			errors.reportError("An unexpected error occurred: " + t);
		}

		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Parse XML to obtain all source elements, optionally filtered by record or study type.  Create a temporary error
	// logger if one is not provided so parsing code doesn't have to worry about a null logger.  An external data set
	// key may be provided to use for resolving external record ID references found in the XML.

	public static ArrayList<SourceEditData> readSourcesFromXML(String theDbID, Reader xml, Integer theExtDbKey,
			Integer theAltExtDbKey, int theRecordType, int theStudyType) {
		return readSourcesFromXML(theDbID, xml, theExtDbKey, theAltExtDbKey, theRecordType, theStudyType, null);
	}

	public static ArrayList<SourceEditData> readSourcesFromXML(String theDbID, Reader xml, Integer theExtDbKey,
			Integer theAltExtDbKey, int theRecordType, int theStudyType, ErrorLogger errors) {

		if (null != errors) {
			errors.clearErrors();
		} else {
			errors = new ErrorLogger(null, null);
		}

		try {

			ParseXML handler = new ParseXML(theDbID, theExtDbKey, theAltExtDbKey, theRecordType, theStudyType, errors);

			XMLReader xReader = XMLReaderFactory.createXMLReader("org.apache.xerces.parsers.SAXParser");
			xReader.setContentHandler(handler);
			xReader.parse(new InputSource(xml));

			if ((null == handler.sources) || handler.sources.isEmpty()) {
				if (handler.hadStudy) {
					errors.reportWarning("No compatible records found.");
				} else {
					errors.reportWarning("No recognized XML structure found.");
				}
				return null;
			}

			return handler.sources;

		} catch (SAXException se) {
			String msg = se.getMessage();
			if ((null != msg) && (msg.length() > 0)) {
				errors.reportError("XML error: " + msg);
			}
		} catch (Throwable t) {
			AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
			errors.reportError("An unexpected error occurred: " + t);
		}

		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Parse XML attributes to superclass properties, called under subclass makeSourceWithAttributes??().  The element
	// is passed for error messages as this may be parsing different containing elements.  Note the call sign attribute
	// is not handled here, even though that is a superclass property the subclass must handle that because it is not
	// always an explicit attribute.

	protected boolean parseAttributes(String element, Attributes attrs, ErrorLogger errors) {

		// Latitude and longitude are required.

		if (!location.parseAttributes(element, attrs, recordType, errors)) {
			return false;
		}

		// Height AMSL is required.  Note Source.HEIGHT_DERIVE code may appear here, but that is in the valid range.

		heightAMSL = Source.HEIGHT_MIN - 1.;
		String str = attrs.getValue("HAMSL");
		if (null != str) {
			try {
				heightAMSL = Double.parseDouble(str);
			} catch (NumberFormatException nfe) {
			}
		}
		if ((heightAMSL < Source.HEIGHT_MIN) || (heightAMSL > Source.HEIGHT_MAX)) {
			if (null != errors) {
				errors.reportError("Missing or bad HAMSL attribute in " + element + " tag.");
			}
			return false;
		}

		// HAAT is not required, zero is valid.

		str = attrs.getValue("HAAT");
		if (null != str) {
			overallHAAT = Source.HEIGHT_MIN - 1.;
			try {
				overallHAAT = Double.parseDouble(str);
			} catch (NumberFormatException nfe) {
			}
			if ((overallHAAT < Source.HEIGHT_MIN) || (overallHAAT > Source.HEIGHT_MAX)) {
				if (null != errors) {
					errors.reportError("Bad HAAT attribute in " + element + " tag.");
				}
				return false;
			}
		}

		// Peak ERP is required.

		peakERP = Source.ERP_MIN - 1.;
		str = attrs.getValue("ERP");
		if (null != str) {
			try {
				peakERP = Double.parseDouble(str);
			} catch (NumberFormatException nfe) {
			}
		}
		if ((peakERP < Source.ERP_MIN) || (peakERP > Source.ERP_MAX)) {
			if (null != errors) {
				errors.reportError("Missing or bad ERP attribute in " + element + " tag.");
			}
			return false;
		}

		// Antenna ID is not required, null is valid.

		antennaID = attrs.getValue("ANTENNA_ID");

		// Azimuth pattern orientation is not required, any number is valid, do a modulo 360.

		str = attrs.getValue("APAT_ORIENT");
		if (null != str) {
			try {
				horizontalPatternOrientation = Math.IEEEremainder(Double.parseDouble(str), 360.);
				if (horizontalPatternOrientation < 0.) horizontalPatternOrientation += 360.;
			} catch (NumberFormatException nfe) {
				if (null != errors) {
					errors.reportError("Bad APAT_ORIENT attribute in " + element + " tag.");
				}
				return false;
			}
		}

		// Elevation pattern tilt values are not required, zero is valid.

		str = attrs.getValue("EPAT_ETILT");
		if (null != str) {
			verticalPatternElectricalTilt = AntPattern.TILT_MIN - 1.;
			try {
				verticalPatternElectricalTilt = Double.parseDouble(str);
			} catch (NumberFormatException nfe) {
			}
			if ((verticalPatternElectricalTilt < AntPattern.TILT_MIN) ||
					(verticalPatternElectricalTilt > AntPattern.TILT_MAX)) {
				if (null != errors) {
					errors.reportError("Bad EPAT_ETILT attribute in " + element + " tag.");
				}
				return false;
			}
		}

		str = attrs.getValue("EPAT_MTILT");
		if (null != str) {
			verticalPatternMechanicalTilt = AntPattern.TILT_MIN - 1.;
			try {
				verticalPatternMechanicalTilt = Double.parseDouble(str);
			} catch (NumberFormatException nfe) {
			}
			if ((verticalPatternMechanicalTilt < AntPattern.TILT_MIN) ||
					(verticalPatternMechanicalTilt > AntPattern.TILT_MAX)) {
				if (null != errors) {
					errors.reportError("Bad EPAT_MTILT attribute in " + element + " tag.");
				}
				return false;
			}
		}

		// Elevation pattern mechanical tilt orientation is required if tilt is non-zero, else is not required.

		str = attrs.getValue("EPAT_ORIENT");
		if (null != str) {
			try {
				verticalPatternMechanicalTiltOrientation = Math.IEEEremainder(Double.parseDouble(str), 360.);
				if (verticalPatternMechanicalTiltOrientation < 0.)
					verticalPatternMechanicalTiltOrientation += 360.;
			} catch (NumberFormatException nfe) {
				if (null != errors) {
					errors.reportError("Bad EPAT_ORIENT attribute in " + element + " tag.");
				}
				return false;
			}
		} else {
			if (0. != verticalPatternMechanicalTilt) {
				if (null != errors) {
					errors.reportError("Missing EPAT_ORIENT attribute in " + element + " tag.");
				}
				return false;
			}
		}

		// Generic pattern flag is not required, default to true.

		str = attrs.getValue("USE_GENERIC");
		useGenericVerticalPattern = ((null == str) || Boolean.parseBoolean(str));

		// Pattern flags are not required, default to false.  However a matrix pattern is mutually-exclusive with a
		// vertical pattern so the matrix pattern flag gets priority.

		str = attrs.getValue("HAS_APAT");
		hasHorizontalPattern = ((null != str) && Boolean.parseBoolean(str));

		str = attrs.getValue("HAS_MPAT");
		hasMatrixPattern = ((null != str) && Boolean.parseBoolean(str));

		if (!hasMatrixPattern) {
			str = attrs.getValue("HAS_EPAT");
			hasVerticalPattern = ((null != str) && Boolean.parseBoolean(str));
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Build comment text intended to appear as a tool-tip pop-up in various display tables.  This shows items from
	// the original data set record that were put in attributes, comment lines provided by subclass override of the
	// getComments() method, and comment text for user records.

	public String makeCommentText() {

		StringBuilder s = new StringBuilder();
		String pfx = "<HTML>";

		ArrayList<String> comments = getComments();
		if ((null != comments) && !comments.isEmpty()) {
			for (String theComment : comments) {
				s.append(pfx);
				s.append(theComment);
				pfx = "<BR>";
			}
		}

		String str = getSourceComment(this);
		if ((null != str) && (str.length() > 0)) {
			s.append(pfx);
			String[] words = str.split("\\s+");
			int wl, ll = 0;
			for (int i = 0; i < words.length; i++) {
				wl = words[i].length(); 
				if (wl > 0) {
					s.append(words[i]);
					ll += wl;
					if (ll > 35) {
						s.append("<BR>");
						ll = 0;
					} else {
						s.append(" ");
					}
				}
			}
		}

		if (s.length() > 0) {
			s.append("</HTML>");
			return s.toString();
		}

		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// May be overridden by subclass to provide lines for the comment text.

	protected ArrayList<String> getComments() {

		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Many methods in the Record interface will be overridden by the subclasses.

	public String getRecordType() {

		if (null != userRecordID) {
			return "User " + Source.getRecordTypeName(recordType);
		}
		if (null != extDbKey) {
			return ExtDb.getExtDbTypeName(dbID, extDbKey);
		}
		return "New " + Source.getRecordTypeName(recordType);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isSource() {

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean hasRecordID() {

		return ((null != userRecordID) || ((null != extDbKey) && (null != extRecordID)));
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isReplication() {

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getStationData() {

		if (null != userRecordID) {
			return "User record";
		}
		if (null != extDbKey) {
			return ExtDb.getExtDbDescription(dbID, extDbKey);
		}
		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getRecordID() {

		if (null != userRecordID) {
			return String.valueOf(userRecordID);
		}
		if ((null != extDbKey) && (null != extRecordID)) {
			return extRecordID;
		}
		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getFacilityID() {

		return "";
	}

	public String getSortFacilityID() {

		return "0";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getService() {

		return service.name;
	}

	public String getServiceCode() {

		return service.serviceCode;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isDTS() {

		return service.isDTS;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getSiteCount() {

		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getCallSign() {

		return callSign;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getChannel() {

		return "";
	}

	public String getSortChannel() {

		return "0";
	}

	public String getOriginalChannel() {

		return getChannel();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getFrequency() {

		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getCity() {

		return city;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getState() {

		return state;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getCountry() {

		return country.name;
	}

	public String getCountryCode() {

		return country.countryCode;
	}

	public String getSortCountry() {

		return String.valueOf(country.key);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getZone() {

		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getStatus() {

		return "";
	}

	public String getSortStatus() {

		return "0";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getFileNumber() {

		return fileNumber;
	}

	public String getARN() {

		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getSequenceDate() {

		String s = getAttribute(Source.ATTR_SEQUENCE_DATE);
		if (null != s) {
			return s;
		}
		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getSortSequenceDate() {

		java.util.Date d = AppCore.parseDate(getAttribute(Source.ATTR_SEQUENCE_DATE));
		if (null != d) {
			return String.format(Locale.US, "%013d", d.getTime());
		}
		return "9999999999999";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getFrequencyOffset() {

		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getEmissionMask() {

		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getLatitude() {

		return AppCore.formatLatitude(location.latitude);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getLongitude() {

		return AppCore.formatLongitude(location.longitude);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getHeightAMSL() {

		return AppCore.formatHeight(heightAMSL) + " m";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getOverallHAAT() {

		return AppCore.formatHeight(overallHAAT) + " m";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getPeakERP() {

		return AppCore.formatERP(peakERP) + " kW";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean hasHorizontalPattern() {

		return hasHorizontalPattern;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getHorizontalPatternName() {

		if (hasHorizontalPattern) {
			String theName = "unknown";
			if (null != horizontalPattern) {
				theName = horizontalPattern.name;
			} else {
				Source theSource = getSource();
				if (null != theSource) {
					theName = theSource.horizontalPatternName;
				}
			}
			if ((null != antennaID) && (antennaID.length() > 0)) {
				return theName + " (ID " + antennaID + ")";
			}
			return theName;
		}
		return "Omnidirectional";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getHorizontalPatternOrientation() {

		if (hasHorizontalPattern) {
			return AppCore.formatAzimuth(horizontalPatternOrientation) + " deg";
		}
		return "";
	}
}
