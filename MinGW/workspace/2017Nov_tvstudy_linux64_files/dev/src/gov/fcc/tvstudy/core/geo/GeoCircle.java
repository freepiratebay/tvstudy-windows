//
//  GeoCircle.java
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
// Model for editing circle geographies.

public class GeoCircle extends Geography {

	// type (super)  Always GEO_TYPE_CIRCLE.
	// key (super)   Key, null for a new object never saved.
	// name (super)  Circle name.
	// center        Center point.
	// radius        Radius in km.

	public final GeoPoint center;
	public double radius;


	//-----------------------------------------------------------------------------------------------------------------
	// See superclass.

	public GeoCircle(String theDbID) {

		super(theDbID, GEO_TYPE_CIRCLE);

		center = new GeoPoint();
	}


	//-----------------------------------------------------------------------------------------------------------------

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
			db.query("SELECT latitude, longitude, radius FROM geography WHERE geo_key = " + key);
			if (db.next()) {
				center.setLatLon(db.getDouble(1), db.getDouble(2));
				radius = db.getDouble(3);
			} else {
				errmsg = "Circle geography data not found for key " + key + ".";
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

		GeoCircle newGeo = new GeoCircle(dbID);

		super.duplicateTo(newGeo);

		newGeo.center.setLatLon(center);
		newGeo.radius = radius;

		return newGeo;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isDataValid(ErrorLogger errors) {

		if (!super.isDataValid(errors)) {
			return false;
		}

		if ((0. == center.latitude) || (0. == center.longitude)) {
			if (null != errors) {
				errors.reportValidationError("Circle center latitude and longitude must be provided.");
			}
			return false;
		}

		if ((center.latitude < Source.LATITUDE_MIN) || (center.latitude > Source.LATITUDE_MAX)) {
			if (null != errors) {
				errors.reportValidationError("Bad circle center latitude, must be " + Source.LATITUDE_MIN + " to " +
					Source.LATITUDE_MAX + " degrees.");
			}
			return false;
		}

		if ((center.longitude < Source.LONGITUDE_MIN) || (center.longitude > Source.LONGITUDE_MAX)) {
			if (null != errors) {
				errors.reportValidationError("Bad circle center longitude, must be " + Source.LONGITUDE_MIN + " to " +
					Source.LONGITUDE_MAX + " degrees.");
			}
			return false;
		}

		if ((radius < MIN_DISTANCE) || (radius > MAX_DISTANCE)) {
			if (null != errors) {
				errors.reportValidationError("Circle radius must be provided and between " + MIN_DISTANCE + " and " +
					MAX_DISTANCE + ".");
			}
			return false;
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save the circle.  See comments in superclass.

	protected String saveGeography(DbConnection db) throws SQLException {

		return super.saveGeography(db, center, radius, 0., 0.);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// XML support.

	protected void writeAttributes(Writer xml) throws IOException {

		center.writeAttributes(xml);

		xml.append(" RADIUS=\"");
		xml.append(AppCore.formatDistance(radius));
		xml.append('"');
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected boolean parseAttributes(String element, Attributes attrs, ErrorLogger errors) {

		if (!center.parseAttributes(element, attrs, errors)) {
			return false;
		}

		String str = attrs.getValue("RADIUS");
		radius = MIN_DISTANCE - 1.;
		if (null != str) {
			try {
				radius = Double.parseDouble(str);
			} catch (NumberFormatException nfe) {
			}
		}
		if ((radius < MIN_DISTANCE) || (radius > MAX_DISTANCE)) {
			if (null != errors) {
				errors.reportError("Bad RADIUS attribute in " + element + " tag.");
			}
			return false;
		}

		return true;
	}
}
