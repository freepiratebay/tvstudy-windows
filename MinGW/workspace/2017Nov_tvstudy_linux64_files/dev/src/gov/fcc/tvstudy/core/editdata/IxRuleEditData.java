//
//  IxRuleEditData.java
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.editdata;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;

import java.util.*;


//=====================================================================================================================
// Mutable model class to support editing an interference rule.

public class IxRuleEditData {

	public IxRule ixRule;

	public final boolean isLocked;

	public Integer key;
	public Country country;
	public ServiceType serviceType;
	public SignalType signalType;
	public ServiceType undesiredServiceType;
	public SignalType undesiredSignalType;
	public ChannelDelta channelDelta;
	public ChannelBand channelBand;
	public int frequencyOffset;
	public EmissionMask emissionMask;
	public double distance;
	public double requiredDU;
	public double undesiredTime;
	public boolean isActive;


	//-----------------------------------------------------------------------------------------------------------------
	// The locked flag comes from the study template, if true the rule is not editable in any UI.

	public IxRuleEditData(IxRule theIxRule, boolean theLocked) {

		super();

		ixRule = theIxRule;

		isLocked = theLocked;

		key = Integer.valueOf(ixRule.key);
		country = ixRule.country;
		serviceType = ixRule.serviceType;
		signalType = ixRule.signalType;
		undesiredServiceType = ixRule.undesiredServiceType;
		undesiredSignalType = ixRule.undesiredSignalType;
		channelDelta = ixRule.channelDelta;
		channelBand = ixRule.channelBand;
		frequencyOffset = ixRule.frequencyOffset;
		emissionMask = ixRule.emissionMask;
		distance = ixRule.distance;
		requiredDU = ixRule.requiredDU;
		undesiredTime = ixRule.undesiredTime;
		isActive = ixRule.isActive;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// When creating a new rule caller must provide primary key, and a new rule cannot be locked.

	public IxRuleEditData(Integer theKey) {

		super();

		ixRule = null;

		isLocked = false;

		key = theKey;
		country = Country.getInvalidObject();
		serviceType = ServiceType.getInvalidObject();
		signalType = SignalType.getNullObject();
		undesiredServiceType = ServiceType.getInvalidObject();
		undesiredSignalType = SignalType.getNullObject();
		channelDelta = ChannelDelta.getInvalidObject();
		channelBand = ChannelBand.getNullObject();
		frequencyOffset = 0;
		emissionMask = EmissionMask.getNullObject();
		isActive = true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	private IxRuleEditData(boolean theLocked) {

		super();

		isLocked = theLocked;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public IxRuleEditData copy() {

		IxRuleEditData theCopy = new IxRuleEditData(isLocked);

		theCopy.ixRule = ixRule;
		theCopy.key = key;
		theCopy.country = country;
		theCopy.serviceType = serviceType;
		theCopy.signalType = signalType;
		theCopy.undesiredServiceType = undesiredServiceType;
		theCopy.undesiredSignalType = undesiredSignalType;
		theCopy.channelDelta = channelDelta;
		theCopy.channelBand = channelBand;
		theCopy.frequencyOffset = frequencyOffset;
		theCopy.emissionMask = emissionMask;
		theCopy.distance = distance;
		theCopy.requiredDU = requiredDU;
		theCopy.undesiredTime = undesiredTime;
		theCopy.isActive = isActive;

		return theCopy;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int hashCode() {

		return key.hashCode();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean equals(Object other) {

		return (null != other) && ((IxRuleEditData)other).key.equals(key);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This is not the entire set of properties, just the identifying ones that determine uniqueness in the rule set.

	public String toString() {

		StringBuilder str = new StringBuilder();
		String sep = "";

		if (country.key > 0) {
			str.append(sep);
			str.append(country.name);
			sep = " ";
		}

		if (serviceType.key > 0) {
			str.append(sep);
			str.append(serviceType.name);
			sep = " ";
		}

		if (signalType.key > 0) {
			str.append(sep);
			str.append(signalType.name);
			sep = " ";
		}

		if (undesiredServiceType.key > 0) {
			str.append(sep);
			str.append(undesiredServiceType.name);
			sep = " ";
		}

		if (undesiredSignalType.key > 0) {
			str.append(sep);
			str.append(undesiredSignalType.name);
			sep = " ";
		}

		if (channelDelta.key > 0) {
			str.append(sep);
			str.append(channelDelta.name);
			sep = " ";
		}

		if (channelBand.key > 0) {
			str.append(sep);
			str.append(channelBand.name);
			sep = " ";
		}

		if (IxRule.FREQUENCY_OFFSET_WITHOUT == frequencyOffset) {
			str.append(sep);
			str.append("without frequency offset");
			sep = " ";
		}
		if (IxRule.FREQUENCY_OFFSET_WITH == frequencyOffset) {
			str.append(sep);
			str.append("with frequency offset");
			sep = " ";
		}

		if (emissionMask.key > 0) {
			str.append(sep);
			str.append(emissionMask.name);
			sep = " ";
		}

		return str.toString();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Perform validation checks and optionally report errors.

	public boolean isDataValid() {
		return isDataValid(null);
	}

	public boolean isDataValid(ErrorLogger errors) {

		if (country.key < 1) {
			if (null != errors) {
				errors.reportValidationError("A desired country must be selected.");
			}
			return false;
		}
		if (serviceType.key < 1) {
			if (null != errors) {
				errors.reportValidationError("A desired service type must be selected.");
			}
			return false;
		}
		if (serviceType.digital) {
			if (0 == signalType.key) {
				if (null != errors) {
					errors.reportValidationError("A desired signal type must be selected.");
				}
				return false;
			}
		} else {
			if (signalType.key != 0) {
				signalType = SignalType.getNullObject();
			}
		}
		if (undesiredServiceType.key < 1) {
			if (null != errors) {
				errors.reportValidationError("An undesired service type must be selected.");
			}
			return false;
		}
		if (undesiredServiceType.digital) {
			if (0 == undesiredSignalType.key) {
				if (null != errors) {
					errors.reportValidationError("An undesired signal type must be selected.");
				}
				return false;
			}
		} else {
			if (undesiredSignalType.key != 0) {
				undesiredSignalType = SignalType.getNullObject();
			}
		}

		// Enforce the dependency between undesired service, channel delta, and emission mask.

		if (undesiredServiceType.needsEmissionMask && (1 == Math.abs(channelDelta.delta))) {
			if (0 == emissionMask.key) {
				emissionMask = EmissionMask.getInvalidObject();
			}
		} else {
			if (emissionMask.key != 0) {
				emissionMask = EmissionMask.getNullObject();
			}
		}

		if (channelDelta.key < 1) {
			if (null != errors) {
				errors.reportValidationError("An undesired channel must be selected.");
			}
			return false;
		}
		if (channelBand.key < 0) {
			if (null != errors) {
				errors.reportValidationError("A channel band must be selected.");
			}
			return false;
		}
		if (emissionMask.key < 0) {
			if (null != errors) {
				errors.reportValidationError("An undesired emission mask must be selected.");
			}
			return false;
		}
		if ((distance < IxRule.DISTANCE_MIN) || (distance > IxRule.DISTANCE_MAX)) {
			if (null != errors) {
				errors.reportValidationError("Bad distance limit, must be " + IxRule.DISTANCE_MIN + " to " +
					IxRule.DISTANCE_MAX + ".");
			}
			return false;
		}
		if ((requiredDU < IxRule.DU_MIN) || (requiredDU > IxRule.DU_MAX)) {
			if (null != errors) {
				errors.reportValidationError("Bad required D/U, must be " + IxRule.DU_MIN + " to " + IxRule.DU_MAX +
					".");
			}
			return false;
		}
		if ((undesiredTime < IxRule.PERCENT_TIME_MIN) || (undesiredTime > IxRule.PERCENT_TIME_MAX)) {
			if (null != errors) {
				errors.reportValidationError("Bad undesired % time, must be " + IxRule.PERCENT_TIME_MIN + " to " +
					IxRule.PERCENT_TIME_MAX + ".");
			}
			return false;
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check for data changes.  This assumes validity has already been checked, see StudyEditor.

	public boolean isDataChanged() {

		if (null == ixRule) {
			return true;
		}

		if (isLocked) {
			return false;
		}

		if (country.key != ixRule.country.key) {
			return true;
		}
		if (serviceType.key != ixRule.serviceType.key) {
			return true;
		}
		if (signalType.key != ixRule.signalType.key) {
			return true;
		}
		if (undesiredServiceType.key != ixRule.undesiredServiceType.key) {
			return true;
		}
		if (undesiredSignalType.key != ixRule.undesiredSignalType.key) {
			return true;
		}
		if (channelDelta.key != ixRule.channelDelta.key) {
			return true;
		}
		if (channelBand.key != ixRule.channelBand.key) {
			return true;
		}
		if (frequencyOffset != ixRule.frequencyOffset) {
			return true;
		}
		if (emissionMask.key != ixRule.emissionMask.key) {
			return true;
		}
		if (distance != ixRule.distance) {
			return true;
		}
		if (requiredDU != ixRule.requiredDU) {
			return true;
		}
		if (undesiredTime != ixRule.undesiredTime) {
			return true;
		}
		if (isActive != ixRule.isActive) {
			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This is called after the as-edited data has been saved to the database, commit the edited state to a new record
	// representation object.  This assumes the data is valid and has been saved.

	public void didSave() {

		ixRule = new IxRule(key.intValue(), country, serviceType, signalType, undesiredServiceType,
			undesiredSignalType, channelDelta, channelBand, frequencyOffset, emissionMask, distance, requiredDU,
			undesiredTime, isActive);
	}
}
