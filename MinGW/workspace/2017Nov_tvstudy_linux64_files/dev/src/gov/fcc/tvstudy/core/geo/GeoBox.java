//
//  GeoBox.java
//  TVStudy
//
//  Copyright (c) 2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.geo;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;

import java.util.*;
import java.sql.*;
import java.io.*;

import org.xml.sax.*;


//=====================================================================================================================
// Model for editing box geographies.  The Geo prefix on the class name is used to avoid collisions with AWT and Swing
// classes, it does not appear in method names.

public class GeoBox extends Geography {

	// type (super)  Always GEO_TYPE_BOX.
	// key (super)   Key, null for a new object never saved.
	// name (super)  Circle name.
	// center        Center point.
	// width         Width in km.
	// height        Height in km.

	public final GeoPoint center;
	public double width;
	public double height;


	//-----------------------------------------------------------------------------------------------------------------
	// See superclass.

	public GeoBox(String theDbID) {

		super(theDbID, GEO_TYPE_BOX);

		center = new GeoPoint();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Load the type-specific data for this object.  See superclass.

	public boolean loadData(ErrorLogger errors) {

		if (null == key) {
			return false;
		}

		DbConnection db = DbCore.connectDb(dbID, errors);
		if (null == db) {
			return false;
		}

		String errmsg = null;

		try {
			db.query("SELECT latitude, longitude, width, height FROM geography WHERE geo_key = " + key);
			if (db.next()) {
				center.setLatLon(db.getDouble(1), db.getDouble(2));
				width = db.getDouble(3);
				height = db.getDouble(4);
			} else {
				errmsg = "Box geography data not found for key " + key + ".";
			}
		} catch (SQLException se) {
			errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
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

	public Geography duplicate() {

		GeoBox newGeo = new GeoBox(dbID);

		super.duplicateTo(newGeo);

		newGeo.center.setLatLon(center);
		newGeo.width = width;
		newGeo.height = height;

		return newGeo;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isDataValid(ErrorLogger errors) {

		if (!super.isDataValid(errors)) {
			return false;
		}

		if ((0. == center.latitude) || (0. == center.longitude)) {
			if (null != errors) {
				errors.reportValidationError("Box center latitude and longitude must be provided.");
			}
			return false;
		}

		if ((center.latitude < Source.LATITUDE_MIN) || (center.latitude > Source.LATITUDE_MAX)) {
			if (null != errors) {
				errors.reportValidationError("Bad box center latitude, must be " + Source.LATITUDE_MIN + " to " +
					Source.LATITUDE_MAX + " degrees.");
			}
			return false;
		}

		if ((center.longitude < Source.LONGITUDE_MIN) || (center.longitude > Source.LONGITUDE_MAX)) {
			if (null != errors) {
				errors.reportValidationError("Bad box center longitude, must be " + Source.LONGITUDE_MIN + " to " +
					Source.LONGITUDE_MAX + " degrees.");
			}
			return false;
		}

		if ((width < MIN_DISTANCE) || (width > MAX_DISTANCE) || (height < MIN_DISTANCE) || (height > MAX_DISTANCE)) {
			if (null != errors) {
				errors.reportValidationError("Box width and height must be between " + MIN_DISTANCE + " and " +
					MAX_DISTANCE + ".");
			}
			return false;
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save the box.  See superclass.

	protected String saveGeography(DbConnection db) throws SQLException {

		return super.saveGeography(db, center, 0., width, height);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// XML support.

	protected void writeAttributes(Writer xml) throws IOException {

		center.writeAttributes(xml);

		xml.append(" WIDTH=\"");
		xml.append(AppCore.formatDistance(width));
		xml.append("\" HEIGHT=\"");
		xml.append(AppCore.formatDistance(height));
		xml.append('"');
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected boolean parseAttributes(String element, Attributes attrs, ErrorLogger errors) {

		if (!center.parseAttributes(element, attrs, errors)) {
			return false;
		}

		String str = attrs.getValue("WIDTH");
		width = MIN_DISTANCE - 1.;
		if (null != str) {
			try {
				width = Double.parseDouble(str);
			} catch (NumberFormatException nfe) {
			}
		}
		if ((width < MIN_DISTANCE) || (width > MAX_DISTANCE)) {
			if (null != errors) {
				errors.reportError("Bad WIDTH attribute in " + element + " tag.");
			}
			return false;
		}

		str = attrs.getValue("HEIGHT");
		height = MIN_DISTANCE - 1.;
		if (null != str) {
			try {
				height = Double.parseDouble(str);
			} catch (NumberFormatException nfe) {
			}
		}
		if ((height < MIN_DISTANCE) || (height > MAX_DISTANCE)) {
			if (null != errors) {
				errors.reportError("Bad HEIGHT attribute in " + element + " tag.");
			}
			return false;
		}

		return true;
	}
}
