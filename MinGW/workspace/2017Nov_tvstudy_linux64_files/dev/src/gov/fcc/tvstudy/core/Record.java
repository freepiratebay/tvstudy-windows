//
//  Record.java
//  TVStudy
//
//  Copyright (c) 2015-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core;


//=====================================================================================================================
// Interface of descriptive information for objects that can represent a station facility record, i.e. ExtDbRecord or
// SourceEditData.  Methods return a non-null formatted string description of the relevant property, for properties
// that are not set or not relevant an empty string is acceptable.  There may be a second form for some properties
// that returns a formatted string suitable for lexical sorting.  This is used in search and UI operations where
// different objects may need to be identified and described generically.  Note all records have a primary identifier
// of some sort but the specifics vary with class, so accessors are needed for both the ID and it's descriptive label.

public interface Record {


	//-----------------------------------------------------------------------------------------------------------------

	public String getRecordType();


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isSource();


	//-----------------------------------------------------------------------------------------------------------------

	public boolean hasRecordID();


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isReplication();


	//-----------------------------------------------------------------------------------------------------------------

	public String getStationData();


	//-----------------------------------------------------------------------------------------------------------------

	public String getRecordID();


	//-----------------------------------------------------------------------------------------------------------------

	public String getFacilityID();

	public String getSortFacilityID();


	//-----------------------------------------------------------------------------------------------------------------

	public String getService();

	public String getServiceCode();


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isDTS();


	//-----------------------------------------------------------------------------------------------------------------

	public String getSiteCount();


	//-----------------------------------------------------------------------------------------------------------------

	public String getCallSign();


	//-----------------------------------------------------------------------------------------------------------------

	public String getChannel();

	public String getSortChannel();

	public String getOriginalChannel();


	//-----------------------------------------------------------------------------------------------------------------

	public String getFrequency();


	//-----------------------------------------------------------------------------------------------------------------

	public String getCity();


	//-----------------------------------------------------------------------------------------------------------------

	public String getState();


	//-----------------------------------------------------------------------------------------------------------------

	public String getCountry();

	public String getCountryCode();

	public String getSortCountry();


	//-----------------------------------------------------------------------------------------------------------------

	public String getZone();


	//-----------------------------------------------------------------------------------------------------------------

	public String getStatus();

	public String getSortStatus();


	//-----------------------------------------------------------------------------------------------------------------

	public String getFileNumber();

	public String getARN();


	//-----------------------------------------------------------------------------------------------------------------

	public String getFrequencyOffset();


	//-----------------------------------------------------------------------------------------------------------------

	public String getEmissionMask();


	//-----------------------------------------------------------------------------------------------------------------

	public String getLatitude();


	//-----------------------------------------------------------------------------------------------------------------

	public String getLongitude();


	//-----------------------------------------------------------------------------------------------------------------

	public String getHeightAMSL();


	//-----------------------------------------------------------------------------------------------------------------

	public String getOverallHAAT();


	//-----------------------------------------------------------------------------------------------------------------

	public String getPeakERP();


	//-----------------------------------------------------------------------------------------------------------------

	public boolean hasHorizontalPattern();


	//-----------------------------------------------------------------------------------------------------------------

	public String getHorizontalPatternName();


	//-----------------------------------------------------------------------------------------------------------------

	public String getHorizontalPatternOrientation();
}
