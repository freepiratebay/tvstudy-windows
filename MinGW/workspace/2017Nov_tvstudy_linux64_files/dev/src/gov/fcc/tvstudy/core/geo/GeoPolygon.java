//
//  GeoPolygon.java
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
// Model for editing polygon geographies.

public class GeoPolygon extends Geography {

	// type (super)  Always GEO_TYPE_POLYGON.
	// key (super)   Key, null for a new set never saved.
	// name (super)  Set name.
	// reference     Reference point.
	// points        Array of vertex points.

	public final GeoPoint reference;
	public ArrayList<VertexPoint> points;


	//-----------------------------------------------------------------------------------------------------------------
	// See superclass.

	public GeoPolygon(String theDbID) {

		super(theDbID, GEO_TYPE_POLYGON);

		reference = new GeoPoint();
		points = new ArrayList<VertexPoint>();
	}


	//=================================================================================================================
	// VertexPoint class.

	public static class VertexPoint extends GeoPoint {


		//-------------------------------------------------------------------------------------------------------------

		public VertexPoint duplicate() {

			VertexPoint newPoint = new VertexPoint();

			newPoint.setLatLon(this);

			return newPoint;
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean isDataValid(ErrorLogger errors) {

			if ((0. == latitude) || (0. == longitude)) {
				if (null != errors) {
					errors.reportValidationError("Vertex latitude and longitude must be provided.");
				}
				return false;
			}

			if ((latitude < Source.LATITUDE_MIN) || (latitude > Source.LATITUDE_MAX)) {
				if (null != errors) {
					errors.reportValidationError("Bad vertex latitude, must be " + Source.LATITUDE_MIN + " to " +
						Source.LATITUDE_MAX + " degrees.");
				}
				return false;
			}

			if ((longitude < Source.LONGITUDE_MIN) || (longitude > Source.LONGITUDE_MAX)) {
				if (null != errors) {
					errors.reportValidationError("Bad vertex longitude, must be " + Source.LONGITUDE_MIN + " to " +
						Source.LONGITUDE_MAX + " degrees.");
				}
				return false;
			}

			return true;
		}
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

		points = new ArrayList<VertexPoint>();
		VertexPoint point;
		String errmsg = null;

		try {
			db.query("SELECT latitude, longitude FROM geography WHERE geo_key = " + key);
			if (db.next()) {
				reference.setLatLon(db.getDouble(1), db.getDouble(2));
			} else {
				errmsg = "Polygon geography data not found for key " + key + ".";
			}
			if (null == errmsg) {
				db.query("SELECT latitude, longitude FROM geo_polygon WHERE geo_key = " + key +
					" ORDER BY vertex_key");
				while (db.next()) {
					point = new VertexPoint();
					point.setLatLon(db.getDouble(1), db.getDouble(2));
					points.add(point);
				}
				if (points.size() < 3) {
					errmsg = "Bad polygon geography data for key " + key + ".";
				}
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

		GeoPolygon newGeo = new GeoPolygon(dbID);

		super.duplicateTo(newGeo);

		newGeo.reference.setLatLon(reference);

		if (null != points) {
			newGeo.points = new ArrayList<VertexPoint>();
			for (VertexPoint point : points) {
				newGeo.points.add(point.duplicate());
			}
		}

		return newGeo;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isDataValid(ErrorLogger errors) {

		if (!super.isDataValid(errors)) {
			return false;
		}

		if ((0. == reference.latitude) || (0. == reference.longitude)) {
			if (null != errors) {
				errors.reportValidationError("Reference point latitude and longitude must be provided.");
			}
			return false;
		}

		if ((reference.latitude < Source.LATITUDE_MIN) || (reference.latitude > Source.LATITUDE_MAX)) {
			if (null != errors) {
				errors.reportValidationError("Bad reference latitude, must be " + Source.LATITUDE_MIN + " to " +
					Source.LATITUDE_MAX + " degrees.");
			}
			return false;
		}

		if ((reference.longitude < Source.LONGITUDE_MIN) || (reference.longitude > Source.LONGITUDE_MAX)) {
			if (null != errors) {
				errors.reportValidationError("Bad reference longitude, must be " + Source.LONGITUDE_MIN + " to " +
					Source.LONGITUDE_MAX + " degrees.");
			}
			return false;
		}

		if (points.size() < 4) {
			if (null != errors) {
				errors.reportValidationError("Polygon must contain at least 3 points.");
			}
			return false;
		}

		for (VertexPoint point : points) {
			if (!point.isDataValid(errors)) {
				return false;
			}
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save the polygon.  This simply deletes all then inserts all.  The vertex key is generated during the save, it
	// is used only to preserve point sequence on load.

	protected String saveGeography(DbConnection db) throws SQLException {

		String errmsg = super.saveGeography(db, reference, 0., 0., 0.);
		if (null != errmsg) {
			return errmsg;
		}

		db.update("DELETE FROM geo_polygon WHERE geo_key = " + key);

		StringBuilder query = new StringBuilder("INSERT INTO geo_polygon VALUES");
		int startLength = query.length();
		String sep = " (";

		int vertexKey = 0;

		for (VertexPoint point : points) {

			query.append(sep);
			query.append(String.valueOf(key));
			query.append(',');
			query.append(String.valueOf(vertexKey++));
			query.append(',');
			query.append(String.valueOf(point.latitude));
			query.append(',');
			query.append(String.valueOf(point.longitude));

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

		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// XML support.

	protected void writeAttributes(Writer xml) throws IOException {

		reference.writeAttributes(xml);
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected boolean parseAttributes(String element, Attributes attrs, ErrorLogger errors) {

		return reference.parseAttributes(element, attrs, errors);
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected boolean hasElements() {

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected void writeElements(Writer xml) throws IOException {

		for (VertexPoint thePoint : points) {
			xml.append("<VERTEX");
			thePoint.writeAttributes(xml);
			xml.append("/>\n");
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected boolean parseElement(String element, Attributes attrs, String content, ErrorLogger errors) {

		if (!element.equals("VERTEX")) {
			return true;
		}

		VertexPoint thePoint = new VertexPoint();
		if (!thePoint.parseAttributes(element, attrs, errors)) {
			return false;
		}

		points.add(thePoint);
		return true;
	}
}
