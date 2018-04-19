//
//  GeoPlotPanel.java
//  TVStudy
//
//  Copyright (c) 2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui.editor;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.geo.*;

import java.util.*;
import java.awt.*;
import java.awt.event.*;
import java.text.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.table.*;
import javax.swing.border.*;


//=====================================================================================================================
// Plot a geography.  Very basic, for polygon just plot lat-lon as x-y, but scale longitude by cos(latitude).  This is
// used primarily by GeographyEditor, but can be used anywhere.  Various set methods are used to change what is being
// drawn, setters are provided for a full Geography object or lists of study points, sectors, or polygon vertex points.
// Any of the set methods will take a null argument to clear the plot.

public class GeoPlotPanel extends Canvas {

	private static final int SYMBOL_SIZE = 3;

	private int plotType;
	private double latitude;
	private double longitude;
	private double radius;
	private double width;
	private double height;
	private ArrayList<GeoPointSet.StudyPoint> studyPoints;
	private ArrayList<GeoSectors.Sector> sectors;
	private ArrayList<GeoPolygon.VertexPoint> vertexPoints;


	//-----------------------------------------------------------------------------------------------------------------

	public void setGeography(Geography theGeo) {

		latitude = 0.;
		longitude = 0.;
		radius = 0.;
		width = 0.;
		height = 0.;
		studyPoints = null;
		sectors = null;
		vertexPoints = null;

		if (null == theGeo) {
			plotType = 0;
		} else {

			plotType = theGeo.type;

			switch (plotType) {

				case Geography.GEO_TYPE_POINT_SET: {
					studyPoints = ((GeoPointSet)theGeo).points;
					break;
				}

				case Geography.GEO_TYPE_CIRCLE: {
					latitude = ((GeoCircle)theGeo).center.latitude;
					longitude = ((GeoCircle)theGeo).center.longitude;
					radius = ((GeoCircle)theGeo).radius;
					break;
				}

				case Geography.GEO_TYPE_BOX: {
					latitude = ((GeoBox)theGeo).center.latitude;
					longitude = ((GeoBox)theGeo).center.longitude;
					width = ((GeoBox)theGeo).width;
					height = ((GeoBox)theGeo).height;
					break;
				}

				case Geography.GEO_TYPE_SECTORS: {
					latitude = ((GeoSectors)theGeo).center.latitude;
					longitude = ((GeoSectors)theGeo).center.longitude;
					sectors = ((GeoSectors)theGeo).sectors;
					break;
				}

				case Geography.GEO_TYPE_POLYGON: {
					latitude = ((GeoPolygon)theGeo).reference.latitude;
					longitude = ((GeoPolygon)theGeo).reference.longitude;
					vertexPoints = ((GeoPolygon)theGeo).points;
					break;
				}
			}
		}

		repaint();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void setStudyPoints(ArrayList<GeoPointSet.StudyPoint> thePoints) {

		latitude = 0.;
		longitude = 0.;
		radius = 0.;
		width = 0.;
		height = 0.;
		sectors = null;
		vertexPoints = null;

		if (null == thePoints) {
			plotType = 0;
			studyPoints = null;
		} else {
			plotType = Geography.GEO_TYPE_POINT_SET;
			studyPoints = thePoints;
		}

		repaint();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void setSectors(ArrayList<GeoSectors.Sector> theSectors) {

		latitude = 0.;
		longitude = 0.;
		radius = 0.;
		width = 0.;
		height = 0.;
		studyPoints = null;
		vertexPoints = null;

		if (null == theSectors) {
			plotType = 0;
			sectors = null;
		} else {
			plotType = Geography.GEO_TYPE_SECTORS;
			sectors = theSectors;
		}

		repaint();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void setVertexPoints(ArrayList<GeoPolygon.VertexPoint> thePoints) {

		latitude = 0.;
		longitude = 0.;
		radius = 0.;
		width = 0.;
		height = 0.;
		studyPoints = null;
		sectors = null;

		if (null == thePoints) {
			plotType = 0;
			vertexPoints = null;
		} else {
			plotType = Geography.GEO_TYPE_POLYGON;
			vertexPoints = thePoints;
		}

		repaint();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void paint(Graphics og) {

		Graphics2D g = (Graphics2D)og;
		g.setColor(Color.BLACK);

		double wid = (double)getWidth(), hgt = (double)getHeight();
		double xs = wid * 0.9, ys = hgt * 0.9, xo = wid * 0.05, yo = hgt * 0.05;

		int ix, iy;

		switch (plotType) {

			default: {
				return;
			}

			case Geography.GEO_TYPE_POINT_SET: {

				double slat = 999., nlat = -999., elon = 999., wlon = -999.;

				for (GeoPointSet.StudyPoint thePoint : studyPoints) {

					if ((0. == thePoint.latitude) || (0. == thePoint.longitude)) {
						continue;
					}

					if (thePoint.latitude < slat) {
						slat = thePoint.latitude;
					}
					if (thePoint.latitude > nlat) {
						nlat = thePoint.latitude;
					}
					if (thePoint.longitude < elon) {
						elon = thePoint.longitude;
					}
					if (thePoint.longitude > wlon) {
						wlon = thePoint.longitude;
					}
				}

				if ((999. == slat) || (999. == elon)) {
					return;
				}

				double lonscl = Math.cos(((nlat + slat) / 2.) * GeoPoint.DEGREES_TO_RADIANS), scl = 1.;
				if ((wlon - elon) > 0.) {
					if ((nlat - slat) > 0.) {
						double xscl = xs / ((wlon - elon) * lonscl);
						double yscl = ys / (nlat - slat);
						if (xscl < yscl) {
							scl = xscl;
							yo += (ys - ((nlat - slat) * scl)) / 2.;
						} else {
							scl = yscl;
							xo += (xs - (((wlon - elon) * lonscl) * scl)) / 2.;
						}
					} else {
						scl = xs / ((wlon - elon) * lonscl);
						yo += (ys - ((nlat - slat) * scl)) / 2.;
					}
				} else {
					if ((nlat - slat) > 0.) {
						scl = ys / (nlat - slat);
						xo += (xs - (((wlon - elon) * lonscl) * scl)) / 2.;
					} else {
						scl = 1.;
						xo += xs / 2.;
						yo += ys / 2.;
					}
				}

				double x, y;
				int ix1, iy1;

				g.setStroke(new BasicStroke((float)1.));

				for (GeoPointSet.StudyPoint thePoint : studyPoints) {

					if ((0. == thePoint.latitude) || (0. == thePoint.longitude)) {
						continue;
					}

					x = xo + ((wlon - thePoint.longitude) * lonscl * scl);
					y = hgt - (yo + ((thePoint.latitude - slat) * scl));
					ix = (int)Math.rint(x);
					iy = (int)Math.rint(y);

					g.drawLine((ix - SYMBOL_SIZE), iy, (ix + SYMBOL_SIZE), iy);
					g.drawLine(ix, (iy - SYMBOL_SIZE), ix, (iy + SYMBOL_SIZE));

					if (thePoint.useAntennaOrientation) {

						ix1 = (int)Math.rint(x + ((3. * (double)SYMBOL_SIZE) *
							Math.sin(thePoint.antennaOrientation * GeoPoint.DEGREES_TO_RADIANS)));
						iy1 = (int)Math.rint(y - ((3. * (double)SYMBOL_SIZE) *
							Math.cos(thePoint.antennaOrientation * GeoPoint.DEGREES_TO_RADIANS)));

						g.drawLine(ix, iy, ix1, iy1);
					}
				}

				return;
			}

			case Geography.GEO_TYPE_CIRCLE: {

				if (radius <= 0.) {
					return;
				}

				double diam = 2. * radius;
				double xscl = xs / diam;
				double yscl = ys / diam;
				double scl = (xscl < yscl) ? xscl : yscl;
				int siz = (int)Math.rint(diam * scl);
				ix = (int)Math.rint(xo + ((xs - (diam * scl)) / 2.));
				iy = (int)Math.rint(hgt - (yo + ((ys - (diam * scl)) / 2.) + (diam * scl)));

				g.setStroke(new BasicStroke((float)3., BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

				g.drawOval(ix, iy, siz, siz);

				if ((0. == latitude) || (0. == longitude)) {
					return;
				}

				ix = (int)Math.rint(xo + (xs / 2.));
				iy = (int)Math.rint(hgt - (yo + (ys / 2.)));

				break;
			}

			case Geography.GEO_TYPE_BOX: {

				if ((width <= 0.) || (height <= 0.)) {
					return;
				}

				double xscl = xs / width;
				double yscl = ys / height;
				double scl = (xscl < yscl) ? xscl : yscl;

				ix = (int)Math.rint(xo + ((xs - (width * scl)) / 2.));
				iy = (int)Math.rint(hgt - (yo + ((ys - (height * scl)) / 2.) + (height * scl)));
				int iwid = (int)Math.rint(width * scl);
				int ihgt = (int)Math.rint(height * scl);

				g.setStroke(new BasicStroke((float)3., BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

				g.drawRect(ix, iy, iwid, ihgt);

				if ((0. == latitude) || (0. == longitude)) {
					return;
				}

				ix = (int)Math.rint(xo + (xs / 2.));
				iy = (int)Math.rint(hgt - (yo + (ys / 2.)));

				break;
			}

			case Geography.GEO_TYPE_SECTORS: {

				double maxrad = 0.;

				for (GeoSectors.Sector theSector : sectors) {
					if (theSector.radius > maxrad) {
						maxrad = theSector.radius;
					}
				}

				if (maxrad <= 0.) {
					return;
				}

				double x = xo + (xs / 2.);
				double y = hgt - (yo + (ys / 2.));

				double diam = 2. * maxrad;
				double xscl = xs / diam;
				double yscl = ys / diam;
				double scl = (xscl < yscl) ? xscl : yscl;

				g.setStroke(new BasicStroke((float)3., BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

				double sr, cr;
				int i, n = sectors.size(), iaz, iang, siz, ix1, iy1, ix2, iy2;
				GeoSectors.Sector sect0, sect1;

				for (i = 0; i < n; i++) {

					sect0 = sectors.get(i);
					if (i < (n - 1)) {
						sect1 = sectors.get(i + 1);
					} else {
						sect1 = sectors.get(0);
					}
					diam = sect0.radius * 2.;
					ix = (int)Math.rint(xo + ((xs - (diam * scl)) / 2.));
					iy = (int)Math.rint(hgt - (yo + ((ys - (diam * scl)) / 2.) + (diam * scl)));
					siz = (int)Math.rint(diam * scl);
					iaz = (int)Math.rint(90. - sect0.azimuth);
					if (sect1.azimuth > sect0.azimuth) {
						iang = (int)Math.rint(sect0.azimuth - sect1.azimuth);
					} else {
						iang = (int)Math.rint(sect0.azimuth - sect1.azimuth - 360.);
					}

					g.drawArc(ix, iy, siz, siz, iaz, iang);

					sr = Math.sin(sect1.azimuth * GeoPoint.DEGREES_TO_RADIANS);
					cr = Math.cos(sect1.azimuth * GeoPoint.DEGREES_TO_RADIANS);
					ix1 = (int)Math.rint(x + (sect0.radius * scl * sr));
					iy1 = (int)Math.rint(y - (sect0.radius * scl * cr));
					ix2 = (int)Math.rint(x + (sect1.radius * scl * sr));
					iy2 = (int)Math.rint(y - (sect1.radius * scl * cr));

					g.drawLine(ix1, iy1, ix2, iy2);
				}

				if ((0. == latitude) || (0. == longitude)) {
					return;
				}

				ix = (int)Math.rint(x);
				iy = (int)Math.rint(y);

				break;
			}

			case Geography.GEO_TYPE_POLYGON: {

				if (vertexPoints.size() < 2) {
					return;
				}

				double slat = 999., nlat = -999., elon = 999., wlon = -999.;

				for (GeoPolygon.VertexPoint thePoint : vertexPoints) {

					if ((0. == thePoint.latitude) || (0. == thePoint.longitude)) {
						continue;
					}

					if (thePoint.latitude < slat) {
						slat = thePoint.latitude;
					}
					if (thePoint.latitude > nlat) {
						nlat = thePoint.latitude;
					}
					if (thePoint.longitude < elon) {
						elon = thePoint.longitude;
					}
					if (thePoint.longitude > wlon) {
						wlon = thePoint.longitude;
					}
				}

				if ((999. == slat) || (999. == elon)) {
					return;
				}

				if ((latitude != 0.) && (longitude != 0.)) {
					if (latitude < slat) {
						slat = latitude;
					}
					if (latitude > nlat) {
						nlat = latitude;
					}
					if (longitude < elon) {
						elon = longitude;
					}
					if (longitude > wlon) {
						wlon = longitude;
					}
				}

				double lonscl = Math.cos(((nlat + slat) / 2.) * GeoPoint.DEGREES_TO_RADIANS), scl = 1.;
				if ((wlon - elon) > 0.) {
					if ((nlat - slat) > 0.) {
						double xscl = xs / ((wlon - elon) * lonscl);
						double yscl = ys / (nlat - slat);
						if (xscl < yscl) {
							scl = xscl;
							yo += (ys - ((nlat - slat) * scl)) / 2.;
						} else {
							scl = yscl;
							xo += (xs - (((wlon - elon) * lonscl) * scl)) / 2.;
						}
					} else {
						scl = xs / ((wlon - elon) * lonscl);
						yo += (ys - ((nlat - slat) * scl)) / 2.;
					}
				} else {
					if ((nlat - slat) > 0.) {
						scl = ys / (nlat - slat);
						xo += (xs - (((wlon - elon) * lonscl) * scl)) / 2.;
					} else {
						scl = 1.;
						xo += xs / 2.;
						yo += ys / 2.;
					}
				}

				g.setStroke(new BasicStroke((float)3., BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

				int ix0 = 0, iy0 = 0, ixl = 0, iyl = 0;
				boolean first = true;

				for (GeoPolygon.VertexPoint thePoint : vertexPoints) {

					if ((0. == thePoint.latitude) || (0. == thePoint.longitude)) {
						continue;
					}

					ix = (int)Math.rint(xo + ((wlon - thePoint.longitude) * lonscl * scl));
					iy = (int)Math.rint(hgt - (yo + ((thePoint.latitude - slat) * scl)));

					if (first) {
						ix0 = ix;
						iy0 = iy;
						first = false;
					} else {
						g.drawLine(ixl, iyl, ix, iy);
					}

					ixl = ix;
					iyl = iy;
				}

				if ((ixl != ix0) || (iyl != iy0)) {
					g.drawLine(ixl, iyl, ix0, iy0);
				}

				if ((0. == latitude) || (0. == longitude)) {
					return;
				}

				ix = (int)Math.rint(xo + ((wlon - longitude) * lonscl * scl));
				iy = (int)Math.rint(hgt - (yo + ((latitude - slat) * scl)));

				break;
			}
		}

		g.setStroke(new BasicStroke((float)1.));

		g.drawLine((ix - SYMBOL_SIZE), iy, (ix + SYMBOL_SIZE), iy);
		g.drawLine(ix, (iy - SYMBOL_SIZE), ix, (iy + SYMBOL_SIZE));
	}
}
