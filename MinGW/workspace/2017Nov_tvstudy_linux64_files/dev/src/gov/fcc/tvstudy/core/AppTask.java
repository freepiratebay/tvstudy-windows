//
//  AppTask.java
//  TVStudy
//
//  Copyright (c) 2015 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core;

import java.util.*;


//=====================================================================================================================
// Class to manage a general-purpose task queue to limit load on system resources.  When some other object wants to
// begin a task that may cause significant load, an instance of this class is created and canTaskStart() is called.  If
// that returns true, the caller may (and presumably will) immediately begin the task; in that case it is added to the
// running list.  If canTaskStart() returns false, the caller must wait and try again later, presumably repeating the
// call on a timer loop.  In that case the task is added to a waiting queue.  Eventually canTaskStart() will return
// true, usually approval is given in queued order of first call but that may vary due to specific load requirements.
// If canTaskStart() has been called at least once, taskDone() must be called when the task is complete or will never
// be started.  Use isResourceAvailable() to test whether resources are currently available for a given load, however
// that method is advisory only, conditions may change before canTaskStart() is called.  Load values are 0. to 1.

public class AppTask {

	public static final long MAX_WAIT_TIME = 2000L;

	private static final double MAX_LOAD = 1.001;

	private static ArrayList<AppTask> waitingQueue = new ArrayList<AppTask>();
	private static ArrayList<AppTask> runningList = new ArrayList<AppTask>();

	private static double currentLoad = 0.;

	private double load;
	private long lastPollTime;


	//-----------------------------------------------------------------------------------------------------------------
	// Check if a task can start.  Returns true if resources are available and no other task higher in the wait queue
	// could start now, otherwise return false and expect repeated calls later.  Regardless of initial return or number
	// of calls there must be a later call to taskDone().  However waiting processes are removed from the queue if they
	// have not been polled recently.  A task with load 0. may always start and is not added to the running list,
	// likewise a task that is already running.

	public static synchronized boolean canTaskStart(AppTask theTask) {

		long now = System.currentTimeMillis();
		theTask.lastPollTime = now;

		if ((0. == theTask.load) || runningList.contains(theTask)) {
			return true;
		}

		int pos = waitingQueue.indexOf(theTask);

		boolean result = true;

		if ((currentLoad + theTask.load) <= MAX_LOAD) {
			if (!waitingQueue.isEmpty() && (0 != pos)) {
				AppTask waitingTask;
				int max = ((pos < 0) ? waitingQueue.size() : pos);
				for (int i = 0; i < max; i++) {
					waitingTask = waitingQueue.get(i);
					if ((now - waitingTask.lastPollTime) > MAX_WAIT_TIME) {
						waitingQueue.remove(i);
						i--;
						max--;
					} else {
						if ((currentLoad + waitingTask.load) <= MAX_LOAD) {
							result = false;
							break;
						}
					}
				}
			}
		} else {
			result = false;
		}

		if (result) {
			if (pos >= 0) {
				waitingQueue.remove(pos);
			}
			currentLoad += theTask.load;
			runningList.add(theTask);
		} else {
			if (pos < 0) {
				waitingQueue.add(theTask);
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Bump a task to the top of the waiting list.  Must be followed by a canTaskStart() call.

	public static synchronized void bumpTask(AppTask theTask) {

		if ((0. == theTask.load) || runningList.contains(theTask)) {
			return;
		}

		theTask.lastPollTime = System.currentTimeMillis();

		waitingQueue.remove(theTask);
		waitingQueue.add(0, theTask);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static synchronized void taskDone(AppTask theTask) {

		waitingQueue.remove(theTask);
		if (runningList.remove(theTask)) {
			if (runningList.isEmpty()) {
				currentLoad = 0.;
			} else {
				currentLoad -= theTask.load;
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static boolean isResourceAvailable(double theLoad) {

		return ((currentLoad + theLoad) <= MAX_LOAD);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public AppTask(double theLoad) {

		load = theLoad;
	}
}
