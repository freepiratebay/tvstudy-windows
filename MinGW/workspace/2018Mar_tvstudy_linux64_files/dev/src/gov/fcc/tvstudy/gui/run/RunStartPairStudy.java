//
//  RunStartPairStudy.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui.run;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.gui.*;

import java.util.*;
import java.util.regex.*;
import java.sql.*;
import java.awt.*;
import java.awt.event.*;
import java.text.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;


//=====================================================================================================================
// UI for setting up a pair-wise study run.  This creates an instance of RunPanelPairStudy to manage the run, that in
// turn provides a StudyBuildPairStudy object that receives much of the UI here (other than the generic RunPanel bits
// which are handled by the RunStart superclass).  In setStudy() the selected study is opened for edit and the Study
// object given to the StudyBuildPairStudy object, it will actually make and save edits to the study.  See further
// details in those other classes.

public class RunStartPairStudy extends RunStart {

	public static final String WINDOW_TITLE = "Pair Study Run";

	private RunPanelPairStudy runPanel;

	// UI fields.

	private KeyedRecordMenu studyCountryMenu;
	private JTextField studyChannelsField;
	private JComboBox<String> runCountMenu;


	//-----------------------------------------------------------------------------------------------------------------

	public RunStartPairStudy(StudyManager theParent) {

		super(theParent, WINDOW_TITLE);

		runPanel = new RunPanelPairStudy(theParent, theParent.getDbID());

		// UI setup.  Menu for selecting the country being studied, only stations in the selected country are proxied
		// to the study channels, those in other countries are just studied for protection on their baseline channels.

		studyCountryMenu = new KeyedRecordMenu(Country.getCountries());
		studyCountryMenu.addItem(new KeyedRecord(0, "All countries"));

		// A field for entering study channels, multiple channels may be comma-separated.

		studyChannelsField = new JTextField(20);
		AppController.fixKeyBindings(studyChannelsField);

		// Pop-up list for selecting number of simultaneous study engine processes, see AppCore.initialize() for the
		// code determining the maximum.  The actual number of processes may be less than selected if the number of
		// scenarios is not large enough to justify parallel processing.  Note maxEngineProcessCount can be <=0 in
		// which case even one engine process will fail (see AppCore.initialize()), but in that case other checks
		// should mean this is never reached at all.  Regardless, this will always show at least a 1-process choice.

		runCountMenu = new JComboBox<String>();
		runCountMenu.setFocusable(false);
		runCountMenu.addItem("1");
		for (int runNumber = 2; runNumber <= AppCore.maxEngineProcessCount; runNumber++) {
			runCountMenu.addItem(String.valueOf(runNumber));
		}

		// Layout run settings UI.

		JLabel countryLabel = new JLabel("Study country");
		JLabel chansLabel = new JLabel("Study channel(s)");
		JLabel runsLabel = new JLabel("Max study processes");

		JPanel uiPanel = new JPanel();
		GroupLayout lo = new GroupLayout(uiPanel);
		lo.setAutoCreateGaps(true);
		lo.setAutoCreateContainerGaps(true);
		uiPanel.setLayout(lo);

		GroupLayout.ParallelGroup h1Group = lo.createParallelGroup(GroupLayout.Alignment.TRAILING);
		GroupLayout.ParallelGroup h2Group = lo.createParallelGroup();
		GroupLayout.SequentialGroup vGroup = lo.createSequentialGroup();

		h1Group.addComponent(countryLabel);
		h2Group.addComponent(studyCountryMenu);
		vGroup.addGroup(lo.createParallelGroup(GroupLayout.Alignment.BASELINE).
			addComponent(countryLabel).addComponent(studyCountryMenu));

		h1Group.addComponent(chansLabel);
		h2Group.addComponent(studyChannelsField);
		vGroup.addGroup(lo.createParallelGroup(GroupLayout.Alignment.BASELINE).
			addComponent(chansLabel).addComponent(studyChannelsField));

		h1Group.addComponent(runsLabel);
		h2Group.addComponent(runCountMenu);
		vGroup.addGroup(lo.createParallelGroup(GroupLayout.Alignment.BASELINE).
			addComponent(runsLabel).addComponent(runCountMenu));

		lo.setHorizontalGroup(lo.createSequentialGroup().addGroup(h1Group).addGroup(h2Group));
		lo.setVerticalGroup(vGroup);

		// Layout.

		JPanel wrapP = new JPanel();
		wrapP.add(uiPanel);

		JPanel mainPanel = new JPanel();
		mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
		mainPanel.add(wrapP);
		mainPanel.add(optionsPanel);

		Container cp = getContentPane();
		cp.setLayout(new BorderLayout());
		cp.add(mainPanel, BorderLayout.CENTER);
		cp.add(buttonPanel, BorderLayout.SOUTH);

		pack();

		setMinimumSize(getSize());
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Load the actual study, so it is locked for edit, and set it in the studyBuild object.

	public boolean setStudy(int theStudyKey, ErrorReporter errors) {

		if (isVisible() || (null != runPanel.studyBuild.baseStudy)) {
			return false;
		}

		final int theKey = theStudyKey;
		BackgroundWorker<Study> theWorker = new BackgroundWorker<Study>(this, WINDOW_TITLE) {
			protected Study doBackgroundWork(ErrorLogger errors) {
				return Study.getStudy(getDbID(), theKey, errors);
			}
		};
		Study theStudy = theWorker.runWork("Opening study, please wait...", errorReporter);
		if (null == theStudy) {
			return false;
		}

		runPanel.studyBuild.baseStudy = theStudy;

		runPanel.studyKey = theStudy.key;
		runPanel.studyName = theStudy.name;
		runPanel.studyType = theStudy.studyType;
		runPanel.studyLock = theStudy.studyLock;
		runPanel.lockCount = theStudy.lockCount;

		runPanel.runName = theStudy.name;

		updateDocumentName();

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public RunPanel getRunPanel() {

		return runPanel;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public StudyLockHolder getStudyLockHolder() {

		return runPanel;
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected void doReset() {

		super.doReset();

		studyCountryMenu.setSelectedIndex(0);
		studyChannelsField.setText("");
		runCountMenu.setSelectedIndex(0);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check local input state and set up run panel.  First call super.

	protected boolean validateInput() {

		if (!super.validateInput()) {
			return false;
		}

		runPanel.fileOutputConfig = optionsPanel.fileOutputConfig;
		runPanel.mapOutputConfig = optionsPanel.mapOutputConfig;

		// Get country selection.

		if (0 == studyCountryMenu.getSelectedKey()) {
			runPanel.studyBuild.studyCountry = null;
		} else {
			runPanel.studyBuild.studyCountry = (Country)studyCountryMenu.getSelectedItem();
		}

		// Parse out the channel list.  Commas and dashes for multiple channels and ranges are allowed.  Duplicates
		// are silently ignored.

		boolean error = false;
		TreeSet<Integer> theChannels = new TreeSet<Integer>();

		String inputString = studyChannelsField.getText().trim();
		if (inputString.length() > 0) {

			String[] parts = inputString.split(","), chans = null;

			int chan = 0, chan1 = 0, chan2 = 0;

			for (String part : parts) {

				if (part.contains("-")) {
					chans = part.split("-");
					if (2 == chans.length) {
						try {
							chan1 = Integer.parseInt(chans[0].trim());
						} catch (NumberFormatException ne) {
							error = true;
						}
						if (error) {
							break;
						}
						if ((chan1 < SourceTV.CHANNEL_MIN) || (chan1 > SourceTV.CHANNEL_MAX)) {
							error = true;
							break;
						}
						try {
							chan2 = Integer.parseInt(chans[1].trim());
						} catch (NumberFormatException ne) {
							error = true;
						}
						if (error) {
							break;
						}
						if ((chan2 < SourceTV.CHANNEL_MIN) || (chan2 > SourceTV.CHANNEL_MAX)) {
							error = true;
							break;
						}
						if (chan1 <= chan2) {
							for (chan = chan1; chan <= chan2; chan++) {
								theChannels.add(Integer.valueOf(chan));
							}
						} else {
							for (chan = chan2; chan <= chan1; chan++) {
								theChannels.add(Integer.valueOf(chan));
							}
						}
					} else {
						error = true;
						break;
					}

				} else {

					try {
						chan = Integer.parseInt(part.trim());
					} catch (NumberFormatException ne) {
						error = true;
					}
					if (error) {
						break;
					}
					if ((chan < SourceTV.CHANNEL_MIN) || (chan > SourceTV.CHANNEL_MAX)) {
						error = true;
						break;
					}
					theChannels.add(Integer.valueOf(chan));
				}
			}

		} else {
			error = true;
		}

		if (error || theChannels.isEmpty()) {
			errorReporter.reportWarning("Please enter valid study channels.\n" +
				"Use commas and dashes for multiple channels and ranges.");
			return false;
		}

		runPanel.studyBuild.studyChannels = new int[theChannels.size()];
		Iterator<Integer> it = theChannels.iterator();
		int i = 0;
		while (it.hasNext()) {
			runPanel.studyBuild.studyChannels[i++] = it.next().intValue();
		}

		// Get the engine process count.  This is distinct from the memory fraction setting that is in the superclass
		// OptionsPanel UI.  The count here determines the maximum number of separate engine processes that will be
		// used to run actual pair scenarios in parallel.  As a set those processes total the memoryFraction.  That may
		// cause fewer than the maximum number to run, see RunPanelPairStudy for details.

		runPanel.studyBuild.runCount = runCountMenu.getSelectedIndex() + 1;
		if (runPanel.studyBuild.runCount < 1) {
			runPanel.studyBuild.runCount = 1;
		}

		// Get confirmation if the memory fraction is less than 1.  Pair study post-processing is not memory-limited,
		// it will use as much as it needs based on the size of the study area.  For a large area it could easily need
		// all available memory; although for a small area many post-processing stages could safely be running.  With
		// the current design there is no reliable way to predict that, so just make the user decide.

		if (runPanel.memoryFraction < 1.) {
			AppController.beep();
			if (JOptionPane.YES_OPTION != JOptionPane.showConfirmDialog(this,
					"Running a pair study with less than maximum memory is not recommended.\n" +
					"The memory use limit does not apply to the post-processing phase of the\n" +
					"study.  If the study area is large, that phase may need all memory.\n" +
					"Do you want to start this run anyway?",
					getBaseTitle(), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)) {
				return false;
			}
		}

		return true;
	}
}
