//
//  ProcessPanel.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui.run;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.gui.*;

import java.util.*;
import java.util.logging.*;
import java.io.*;
import java.awt.*;
import java.awt.event.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;


//=====================================================================================================================
// Panel interface to manage a running host OS process and accumulate/display it's output.  This can handle any non-
// interactive, command-line-driven process, as well as interactive process that support a special prompt protocol.
// This is intended to be displayed in a window and remain visible as long as the process is running.  A manager must
// call pollProcess() often (several times a second seems good).  On the first call that will start the process, on
// later calls it updates output and process status.  The process can be aborted if running, or startup cancelled with
// killProcess().  Status is returned by isProcessRunning(), that will return true even before the process starts.
// Once isProcessRunning() returns false, didProcessFail() indicates if the process failed to start, was killed, or
// exited with a non-zero result.  After the process ends, hasOutput() will indicate if there is output accumulated,
// regardless of success or failure.  The output can be written to a Writer with writeOutputTo(), or the saveOutput()
// UI method will prompt the user to select a file and write the output.

// For interactive processes supporting the special protocol, a process controller object must be set, that is any
// object implementing ProcessController which defines methods for prompt-and-response interaction.  When the process
// writes out a full line (terminated by a newline) that starts with the string AppCore.ENGINE_PROMPT_PREFIX, this
// will call the controller to get the response and write it to the process.  See pollProcess().

// These are one-shot objects; the process can only be started once.  After the process has exited or been killed,
// only the output methods are functional.  Note those are not functional while the process is still running.  This
// assumes the process generates some output at fairly frequent intervals.  If there is no output for a long time this
// will assume the process is stalled and kill it.

// To avoid out-of-memory problems with very long run outputs, a temporary disk file is used to accumulate run output,
// and only a limited number of lines remain in the view for scrollback.  When the limit is reached older lines are
// removed.  The writeOutputTo() method copies the temp file contents to a Writer.  That is public so it can be used to
// combine output from several runs to a single file.  The file is not created until needed.

// This now has a pre-run state in which it can be displayed but will not attempt to run the process.  The process
// argument list is no longer set at construction, it is provided to setProcessArguments().  Before arguments are set,
// the panel is displayable and otherwise functional but it will not start a process.  New method displayLogMessage()
// can be used to log messages, appearing inline with process output, at any time.

public class ProcessPanel extends AppPanel {

	public static final int TEXT_AREA_ROWS = 15;
	public static final int TEXT_AREA_COLUMNS = 80;

	private String processName;
	private String password;

	private ArrayList<String> argumentList;

	private Process process;
	private boolean processStarted;
	private boolean processRunning;
	private boolean processFailed;

	private ProcessController controller;
	private boolean inPromptedState;
	private String promptResponse;
	private long lastResponseTime;
	private static final long CONFIRMATION_TIMEOUT = 2000;   // milliseconds
	private int responseAttempts;
	private static final int MAX_RESPONSE_ATTEMPTS = 3;

	private static final int READ_BUFFER_SIZE = 102400;
	private BufferedInputStream processOutput;
	private byte[] readBuffer;

	private StringBuilder outputBuffer;
	private boolean skipNextLine;
	private File outputFile;
	private boolean outputFileOpened;
	private FileWriter outputFileWriter;
	private static final int COPY_BUFFER_SIZE = 1048576;

	private JTextArea outputArea;
	private ArrayList<String> mergeLineStrings;
	private int lastOutputLineType;
	private static final int CR_LINE = -1;
	private static final int PROG_LINE = -2;
	private int lastOutputLineStart;
	private int scrollbackLineCount;
	private static final int MAX_SCROLLBACK_LINES = 15000;

	private JViewport outputViewport;
	private int autoScrollState;
	private boolean autoScrollLock;

	private long lastOutputTime;
	private long stuckProcessTimeout = 600000L;   // milliseconds

	private JLabel statusLabel;
	private JButton abortButton;
	private JPanel statusPanel;


	//-----------------------------------------------------------------------------------------------------------------
	// If the name string is null, "Process" is used.  If the password string is non-null, the first output from the
	// process must be a password prompt containing "password" (case-insensitive), when that is seen the password will
	// be written back.  Output accumulation and display do not begin until the password is written.  If the password
	// argument is null, output processing begins as soon as the process starts.  By default this expects the process
	// to be entirely non-interactive, other than the possible password prompt.  However if a process controller is
	// set the ProcessController interface is used to control an interactive process which supports the prompting
	// protocol.  See below for details of the merge-line string list.

	public ProcessPanel(AppEditor theParent, String theProcessName, String thePassword,
			ArrayList<String> theMergeLineStrings) {

		super(theParent);

		if (null == theProcessName) {
			processName = "Process";
		} else {
			processName = theProcessName;
		}
		password = thePassword;

		if ((null != theMergeLineStrings) && !theMergeLineStrings.isEmpty()) {
			mergeLineStrings = theMergeLineStrings;
		}

		// Set up the UI, main element is a text area displaying output from the process.  All output is written to a
		// temporary disk file, that will be created when the first output needs to be written.  The most-recent lines
		// are displayed for scroll-back viewing.  Output is processed in whole lines terminated by newline or carriage
		// return, a buffer is used to hold partial lines for later processing.  The display may be condensed so lines
		// that repeat with incremental changes are over-written on a single line in the display.  That will occur when
		// any two or more sequential lines all have a contains() match to the same one of the strings provided in the
		// mergeLineStrings list.  Also sequential lines terminated by carriage return will overwrite on a single line
		// in the display, regardless of line content.

		outputBuffer = new StringBuilder();

		outputArea = new JTextArea(TEXT_AREA_ROWS, TEXT_AREA_COLUMNS);
		outputArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
		AppController.fixKeyBindings(outputArea);
		outputArea.setEditable(false);
		outputArea.setLineWrap(true);

		// The output text area has an auto-scroll behavior.  Initially the viewport follows new text as it is added.
		// If the user moves the scroll bar away from the bottom, auto-scroll stops; if the scroll bar is moved to the
		// bottom again, auto-scroll resumes.  See pollProcess().  Be sure the caret doesn't cause any scrolling.

		((DefaultCaret)outputArea.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);

		JScrollPane outPane = AppController.createScrollPane(outputArea);
		outputViewport = outPane.getViewport();

		outPane.getVerticalScrollBar().addAdjustmentListener(new AdjustmentListener() {
			public void adjustmentValueChanged(AdjustmentEvent theEvent) {
				if (autoScrollLock) {
					return;
				}
				if (outputViewport.getViewSize().height <= outputViewport.getExtentSize().height) {
					autoScrollState = 0;
				}
				if (0 == autoScrollState) {
					return;
				}
				Adjustable theAdj = theEvent.getAdjustable();
				boolean atBot = ((theAdj.getMaximum() - theAdj.getVisibleAmount() - theEvent.getValue()) < 10);
				if (1 == autoScrollState) {
					if (!atBot) {
						autoScrollState = 2;
					}
				} else {
					if (atBot) {
						autoScrollState = 1;
					}
				}
			}
		});

		// A status message.

		statusLabel = new JLabel(processName + " is starting");
		statusLabel.setPreferredSize(AppController.labelSize[60]);

		// Abort button.

		abortButton = new JButton("Abort");
		abortButton.setFocusable(false);
		abortButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				killProcess(true);
			}
		});

		// Layout.

		statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		statusPanel.add(abortButton);
		statusPanel.add(statusLabel);

		setLayout(new BorderLayout());

		add(outPane, BorderLayout.CENTER);
		add(statusPanel, BorderLayout.SOUTH);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Close and delete the temporary output file if needed.

	public void finalize() {

		if (null != outputFile) {

			if (null != outputFileWriter) {
				try {
					outputFileWriter.close();
				} catch (IOException ie) {
				}
				outputFileWriter = null;
			}

			outputFile.delete();
			outputFile = null;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set a process controller, see pollProcess().

	public void setProcessController(ProcessController theController) {

		controller = theController;
		inPromptedState = false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set a timeout in milliseconds to detect a stuck process, if no output at all for more than the set time the
	// process will be killed.  Set to 0 to disable the check.  Default is 10 minutes.

	public void setStuckProcessTimeout(long theTimeout) {

		stuckProcessTimeout = theTimeout;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Hide or show the abort button and status message.

	public void setStatusPanelVisible(boolean flag) {

		statusPanel.setVisible(flag);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set text in the status label, note there is no priority here, the most-recent message is always shown whether
	// set by this method or by internal state changes.

	public void setStatusMessage(String theMessage) {

		statusLabel.setText(theMessage);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Show an arbitrary message in the display area and write to the log file.  The message may contain multiple
	// lines, blank lines are ignored but otherwise all lines are parsed and displayed/logged as they would be during
	// output processing from a controlled process, see poll().

	public void displayLogMessage(String theMessage) {

		if (null == theMessage) {
			return;
		}

		int position = 0, nextPosition = 0, length = theMessage.length(), lineType = 0, stringIndex, e;
		char nextChar;
		String mesgKey, mesgData, line = null;

		while (position < length) {

			do {
				nextChar = theMessage.charAt(nextPosition);
			} while (('\n' != nextChar) && ('\r' != nextChar) && (++nextPosition < length));

			if (nextPosition > position) {
				if (nextPosition < length) {
					line = theMessage.substring(position, ++nextPosition);
				} else {
					line = theMessage.substring(position, nextPosition) + "\n";
				}
				position = nextPosition;
			} else {
				position = ++nextPosition;
				continue;
			}

			if ('\r' == nextChar) {
				lineType = PROG_LINE;
			} else {

				lineType = 0;
				if (null != mergeLineStrings) {
					for (stringIndex = 0; stringIndex < mergeLineStrings.size(); stringIndex++) {
						if (line.contains(mergeLineStrings.get(stringIndex))) {
							lineType = stringIndex + 1;
							break;
						}
					}
				}

				try {
					if (!outputFileOpened) {
						outputFileOpened = true;
						outputFile = File.createTempFile(processName, ".log");
						outputFileWriter = new FileWriter(outputFile);
					}
					if (null != outputFileWriter) {
						outputFileWriter.write(line);
					}
				} catch (IOException ie) {
				}
			}

			displayLine(line, lineType);
		}

		updateScroll();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set the argument list, if already set do nothing, otherwise the next pollProcess() will start the process.

	public void setProcessArguments(ArrayList<String> theArgumentList) {

		if (null == argumentList) {
			argumentList = theArgumentList;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Poll the process status and update the UI, this must be called frequently and only from the Swing event thread.
	// If arguments are not set just hold, otherwise start the process.  Return process status, true if running or
	// waiting to start, false if it failed to start, was killed, or exited.  Argument is current system time, in case
	// a set of polls of multiple processes need to appear synchronous.

	public boolean pollProcess(long now) throws IOException {

		if (!processStarted) {

			if (null == argumentList) {
				return true;
			}

			processStarted = true;

			try {
				ProcessBuilder pb = new ProcessBuilder(argumentList);
				pb.redirectErrorStream(true);
				process = pb.start();
				processRunning = true;
			} catch (Throwable t) {
				AppCore.log(AppCore.ERROR_MESSAGE, "Could not start process", t);
			}

			if (processRunning) {

				processOutput = new BufferedInputStream(process.getInputStream(), READ_BUFFER_SIZE);
				readBuffer = new byte[READ_BUFFER_SIZE];

				statusLabel.setText(processName + " is running");
				lastOutputTime = now;

			} else {

				processFailed = true;
				statusLabel.setText("*** " + processName + " failed to start ***");
				abortButton.setEnabled(false);
				if (null != controller) {
					controller.processFailed(this);
					inPromptedState = false;
				}

				return false;
			}
		}

		if (!processRunning) {
			return false;
		}

		// Collect more output, parse it if there is any.  Note the process status is not checked until there is no
		// more output; additional polls may be needed to collect all buffered output after exit.

		boolean sendPassword = false;

		if (collectOutput()) {

			lastOutputTime = now;

			// If still waiting to send the password check for the password prompt, that is any text containing
			// "password" case-insensitive, regardless of line termination.  The actual send of the password is done
			// later after any terminated lines are stripped out of the buffer.

			if ((null != password) && outputBuffer.toString().toLowerCase().contains("password")) {
				sendPassword = true;
			}

			// Parse lines from output buffer, nothing is processed (except recognizing the password prompt) until it
			// is a terminated line, meaning not all new output may be processed this time.  Lines are written to the
			// output file, except prompt lines and status message lines.  A fixed number of recent lines is also
			// displayed in the view, again not including prompts, but including progress messages (status messages
			// with ENGINE_PROGRESS_KEY are calculation progress messages written during lengthy operations so the
			// process does not appear to be stuck).  However in the view display, if sequential lines contain the
			// same string from the mergeLineStrings list, each overwrites the previous.  Also progress messages are
			// always overwritten by the next message regardless of content.  Both linefeed and carriage return
			// terminators are recognized.  A line terminated by carriage return also overwrites the previous line if
			// that had a carriage return, but carriage returns are translated to linefeeds for output and display.

			int newPosition = 0, newLength = outputBuffer.length(), newLineType = 0, stringIndex, e;
			char nextChar;
			String mesgKey, mesgData, newLine = null;

			while (newPosition < newLength) {

				do {
					nextChar = outputBuffer.charAt(newPosition);
				} while (('\n' != nextChar) && ('\r' != nextChar) && (++newPosition < newLength));

				if (newPosition < newLength) {

					newLineType = 0;

					if ('\r' == nextChar) {
						newLineType = CR_LINE;
						outputBuffer.setCharAt(newPosition, '\n');
					}

					newLine = outputBuffer.substring(0, ++newPosition);
					outputBuffer.delete(0, newPosition);
					newLength -= newPosition;
					newPosition = 0;

					// If still waiting to send a password or if the flag is set to skip a line, ignore the new line.
					// All output is discarded prior to sending the password.  See comments below.

					if ((null != password) || skipNextLine) {
						skipNextLine = false;
						continue;
					}

					// If a process controller is set, check the prompt-and-response state.  Initially watch for a line
					// that begins with the prompt prefix, when seen enter the prompted state and ask the controller
					// for the response, it will return null if the prompt is unknown or unexpected in which case kill
					// the process.  Otherwise the controller enters a pending state that must be terminated by either
					// a confirmation or failure.  The response is sent to the process with timeouts and retries hence
					// that is outside the line-parsing loop, see below.  The prompted state ends and the response is
					// confirmed once any output is seen after the response is sent.  If there is output before the
					// response is sent something is wrong, kill the process.

					if (null != controller) {

						if (inPromptedState) {

							if (responseAttempts > 0) {

								controller.processResponseConfirmed(this);
								inPromptedState = false;

							} else {

								AppCore.log(AppCore.ERROR_MESSAGE,
									"Unexpected output '" + newLine + "' from controlled process");
								killProcess(false);
								return false;
							}

						} else {

							if (newLine.startsWith(AppCore.ENGINE_PROMPT_PREFIX)) {

								promptResponse = controller.getProcessResponse(this, newLine);

								if (null == promptResponse) {

									AppCore.log(AppCore.ERROR_MESSAGE,
										"Unexpected prompt '" + newLine + "' from controlled process");
									killProcess(false);
									return false;

								} else {

									inPromptedState = true;
									lastResponseTime = 0;
									responseAttempts = 0;

									continue;
								}
							}
						}
					}

					// Check for a status message.  Here the only one of interest is the progress messages, others are
					// sent to the process controller if set, else ignored.  Progress messages are display-only.

					if (newLine.startsWith(AppCore.ENGINE_MESSAGE_PREFIX)) {
						e = newLine.indexOf('=');
						if (e < 0) {
							continue;
						}
						mesgKey = newLine.substring(AppCore.ENGINE_MESSAGE_PREFIX_LENGTH, e);
						mesgData = newLine.substring(e + 1);
						if (mesgKey.equals(AppCore.ENGINE_PROGRESS_KEY)) {
							newLine = mesgData;
							newLineType = PROG_LINE;
						} else {
							if (null != controller) {
								mesgData = mesgData.substring(0, (mesgData.length() - 1));
								controller.processStatusMessage(this, mesgKey, mesgData);
							}
							continue;
						}
					} else {

						// Have a new output line to be processed.  Write message to the output file, open if needed.

						try {
							if (!outputFileOpened) {
								outputFileOpened = true;
								outputFile = File.createTempFile(processName, ".log");
								outputFileWriter = new FileWriter(outputFile);
							}
							if (null != outputFileWriter) {
								outputFileWriter.write(newLine);
							}
						} catch (IOException ie) {
						}
					}

					// Display the new line.  First check for merging, overwrite or append as needed.  When appending
					// check the scrollback limit, remove the oldest line if needed.

					if ((0 == newLineType) && (null != mergeLineStrings)) {
						for (stringIndex = 0; stringIndex < mergeLineStrings.size(); stringIndex++) {
							if (newLine.contains(mergeLineStrings.get(stringIndex))) {
								newLineType = stringIndex + 1;
								break;
							}
						}
					}

					displayLine(newLine, newLineType);
				}
			}

			updateScroll();

		} else {

			// Check if the process has exited, check exit value and update status.

			if (!process.isAlive()) {

				processRunning = false;

				if (process.exitValue() != 0) {

					processFailed = true;
					statusLabel.setText("*** " + processName + " exited with an error ***");
					if (null != controller) {
						controller.processFailed(this);
					}

				} else {

					statusLabel.setText(processName + " complete");
					if (null != controller) {
						controller.processComplete(this);
					}
				}

				abortButton.setEnabled(false);
				inPromptedState = false;

				return false;
			}
		}

		// If stuck-process timeout is set and there has been no output from the process, kill it.

		if ((stuckProcessTimeout > 0) && ((now - lastOutputTime) > stuckProcessTimeout)) {

			AppCore.log(AppCore.ERROR_MESSAGE, "Process stalled, no output for timeout interval.");
			killProcess(false);

			return false;
		}

		// If the password needs to be written (see above), do it.  When a password is set all output is discarded
		// until the password is written.  The assumption is the user doesn't need to see the prompt or anything that
		// precedes it, which should be nothing.  Set a flag to ignore the next line of output, in case the password
		// echoes, and to suppress an initial blank line in the output.

		if (sendPassword) {

			OutputStream out = process.getOutputStream();
			out.write(password.getBytes());
			out.write("\n".getBytes());
			out.flush();

			password = null;
			skipNextLine = true;

			return true;
		}

		// If in the prompted state check to see if response needs to be sent or re-sent.  After sending, if the
		// response is not confirmed or failure detected (see above) within a timeout interval, send again, up to a
		// maximum number of attempts.  If retries are exceeded, kill the process.

		if (inPromptedState && ((now - lastResponseTime) > CONFIRMATION_TIMEOUT)) {

			if (++responseAttempts > MAX_RESPONSE_ATTEMPTS) {

				AppCore.log(AppCore.ERROR_MESSAGE, "No output from controlled process after sending prompt response");
				killProcess(false);
				return false;

			} else {

				lastResponseTime = now;

				OutputStream out = process.getOutputStream();
				out.write(promptResponse.getBytes());
				out.write(13);
				out.flush();
			}
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Display a line of text in the display area, possibly over-writing the previous line if that was a matching line
	// type or was a progress message line.

	private void displayLine(String newLine, int newLineType) {

		int outputEnd = outputArea.getDocument().getLength();

		if (((0 != newLineType) && (newLineType == lastOutputLineType)) || (PROG_LINE == lastOutputLineType)) {

			outputArea.replaceRange(newLine, lastOutputLineStart, outputEnd);

		} else {

			lastOutputLineStart = outputEnd;
			outputArea.append(newLine);
			scrollbackLineCount++;
		}

		lastOutputLineType = newLineType;
	}


	//-----------------------------------------------------------------------------------------------------------------	
	// Apply the scroll-back limit and the auto-scroll behavior, called after one or more calls to displayLine().
	// When the maximum scroll-back size is reached 10% of the lines are removed at once, removing line-by-line
	// causes excessive CPU load when lines are being displayed rapidly.

	private void updateScroll() {

		if (scrollbackLineCount > MAX_SCROLLBACK_LINES) {

			try {

				int removeLines = scrollbackLineCount / 10;
				int removeTo = outputArea.getLineEndOffset(removeLines);
				outputArea.getDocument().remove(0, removeTo);
				scrollbackLineCount -= removeLines;
				lastOutputLineStart -= removeTo;

			} catch (BadLocationException ble) {
				outputArea.setText("");
				scrollbackLineCount = 0;
				lastOutputLineStart = 0;
				lastOutputLineType = 0;
			}
		}

		autoScrollLock = true;

		outputViewport.validate();
		if (outputViewport.getViewSize().height <= outputViewport.getExtentSize().height) {
			autoScrollState = 0;
		} else {
			if (0 == autoScrollState) {
				autoScrollState = 1;
			}
			if (1 == autoScrollState) {
				outputViewport.scrollRectToVisible(
					new Rectangle(0, (outputViewport.getViewSize().height - 1), 1, 1));
			}
		}

		autoScrollLock = false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Cancel the process if it has not yet been started.  This moves the object to the run-completed state, any
	// subsequent pollProcess() does nothing and returns false.  If the process has been started this returns false.

	public boolean cancelProcess() {

		if (processStarted) {
			return false;
		}

		processStarted = true;
		processRunning = false;

		statusLabel.setText(processName + " complete");
		if (null != controller) {
			controller.processComplete(this);
		}

		abortButton.setEnabled(false);

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Kill the process, return true if it is killed or has already exited.  If the process is running this can show a
	// confirmation prompt, if the user says No (the default) this returns false.  If this is sent before the process
	// is started it simply cancels any attempt to start.

	public boolean killProcess(boolean confirm) {

		if (processStarted && !processRunning) {
			return true;
		}

		if (processStarted) {

			if (confirm) {
				AppController.beep();
				String[] opts = {"No", "Yes"};
				if (0 == JOptionPane.showOptionDialog(this,
						"This will forcibly terminate the running process.  Output\n" +
						"files may be incomplete and databases may be left in an\n" +
						"invalid state.  Are you sure you want to do this?",
						"Abort " + processName, 0, JOptionPane.WARNING_MESSAGE, null, opts, opts[0])) {
					return false;
				}
			}

			// Presumably polling continued while the dialog was up so conditions may have changed.

			if (!processRunning) {
				return true;
			}

			try {
				process.destroy();
			} catch (Throwable t) {
			}
			processRunning = false;
			finishOutput();

		} else {

			processStarted = true;
		}

		processFailed = true;
		statusLabel.setText("*** " + processName + " was aborted ***");
		abortButton.setEnabled(false);
		if (null != controller) {
			controller.processFailed(this);
			inPromptedState = false;
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Collect remaining output from the process, this should only be called after the process has exited.  Any output
	// is displayed verbatim, no extra processing, and written to the output file which will be created if needed.
	// The view is scrolled to the bottom regardless of current autoscroll state.  Ignore all errors.

	private void finishOutput() {

		try {
			collectOutput();
		} catch (IOException ie) {
		}

		if (outputBuffer.length() > 0) {

			outputBuffer.append('\n');

			String finalOutput = outputBuffer.toString();

			try {
				if (!outputFileOpened) {
					outputFileOpened = true;
					outputFile = File.createTempFile(processName, ".log");
					outputFileWriter = new FileWriter(outputFile);
				}
				if (null != outputFileWriter) {
					outputFileWriter.write(finalOutput);
				}
			} catch (IOException ie) {
			}

			outputArea.append(finalOutput);
		}

		outputViewport.validate();
		outputViewport.scrollRectToVisible(new Rectangle(0, (outputViewport.getViewSize().height - 1), 1, 1));
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Collect any new output from the process, add it to the buffer, return true if anything read, else false.

	private boolean collectOutput() throws IOException {

		boolean result = false;

		if (null == processOutput) {
			return result;
		}

		int count;
		while (processOutput.available() > 0) {
			count = processOutput.read(readBuffer);
			if (count > 0) {
				outputBuffer.append(new String(readBuffer, 0, count));
				result = true;
			}
		}

		if(result){
			outputBuffer.replace(0,outputBuffer.length(),outputBuffer.toString().replaceAll("\r",""));
			if(outputBuffer.length()<1)
				result = false;
		}
		
		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This returns true if the process has not yet started.

	public boolean isProcessRunning() {

		if (processStarted) {
			return processRunning;
		}
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean didProcessFail() {

		return processFailed;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean hasOutput() {

		return (!processRunning && (null != outputFile) && (null != outputFileWriter));
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Copy the output file contents, if any, to a writer.  This can only be used once, it closes the output file then
	// deletes the file.  Further messages (sent to displayLogMessage(), this can't be used while the process is still
	// running so there will be no more process output) are displayed but cannot be saved.

	public void writeOutputTo(Writer theWriter) throws IOException {

		if (processRunning || (null == outputFile) || (null == outputFileWriter)) {
			return;
		}

		try {
			outputFileWriter.close();
		} catch (IOException ie) {
		}
		outputFileWriter = null;

		IOException rethrow = null;

		try {

			FileReader theReader = new FileReader(outputFile);

			char[] cbuf = new char[COPY_BUFFER_SIZE];
			int len;

			do {
				len = theReader.read(cbuf, 0, COPY_BUFFER_SIZE);
				if (len > 0) {
					theWriter.write(cbuf, 0, len);
				}
			} while (len >= 0);

			theReader.close();

		} catch (IOException ie) {
			rethrow = ie;
		}

		outputFile.delete();
		outputFile = null;

		if (null != rethrow) {
			throw rethrow;
		}			
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save process output to a file.  This is a UI convenience method, the actual output method is writeOutputTo().

	public void saveOutput() {

		if (processRunning || (null == outputFile) || (null == outputFileWriter)) {
			return;
		}

		String title = "Save " + processName + " Log";
		errorReporter.setTitle(title);

		JFileChooser chooser = new JFileChooser(AppCore.getProperty(AppCore.LAST_FILE_DIRECTORY_KEY));
		chooser.setDialogType(JFileChooser.SAVE_DIALOG);
		chooser.setDialogTitle(title);
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		chooser.setMultiSelectionEnabled(false);

		File theFile = null;
		do {
			if (JFileChooser.APPROVE_OPTION != chooser.showDialog(this, "Save")) {
				return;
			}
			theFile = chooser.getSelectedFile();
			if (theFile.exists()) {
				AppController.beep();
				if (JOptionPane.YES_OPTION != JOptionPane.showConfirmDialog(this,
						"The file exists, do you want to replace it?", title, JOptionPane.YES_NO_OPTION,
						JOptionPane.WARNING_MESSAGE)) {
					theFile = null;
				}
			}
		} while (null == theFile);

		AppCore.setProperty(AppCore.LAST_FILE_DIRECTORY_KEY, theFile.getParentFile().getAbsolutePath());

		FileWriter theWriter = null;
		try {
			theWriter = new FileWriter(theFile);
		} catch (IOException ie) {
			errorReporter.reportError("Could not open the file:\n" + ie.getMessage());
			return;
		}

		try {
			writeOutputTo(theWriter);
		} catch (IOException ie) {
			errorReporter.reportError("Could not write to the file:\n" + ie.getMessage());
		}

		try {
			theWriter.close();
		} catch (IOException ie) {
		}
	}
}
