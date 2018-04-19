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
// that method is advisory only, conditions may change before canTaskStart() is called.

// Memory load values are between 0 and 1, nominally representing the fraction of total system memory the task will
// need.  CPU load values are the number of parallel threads or processes the task will create, which can be from 1
// to the number of available CPUs.  Simultaneous running tasks are allowed as long as the total memory load is 1 or
// less, and the total CPU load is equal or less than the system CPU count.  The assumption is that this application,
// and parallel tasks running within, may use 100% of system resources.  The UI thread and any host OS activity not
// related to a running task are assumed to be an insignificant load.  If the memory load is 0, only the CPU load is
// checked.  If a task has no significant impact on either memory or CPU, there is no need to use this class.

public class AppTask {

	public static final long MAX_WAIT_TIME = 2000L;

	private static final double MAX_MEMORY_LOAD = 1.001;

	private static ArrayList<AppTask> waitingQueue = new ArrayList<AppTask>();
	private static ArrayList<AppTask> runningList = new ArrayList<AppTask>();

	private static double currentMemoryLoad = 0.;
	private static int currentCPULoad = 0;

	private double memoryLoad;
	private int cpuLoad;
	private long lastPollTime;


	//-----------------------------------------------------------------------------------------------------------------
	// Check if a task can start.  Returns true if resources are available and no other task higher in the wait queue
	// could start now, otherwise return false and expect repeated calls later.  Regardless of initial return or number
	// of calls there must be a later call to taskDone().  However waiting processes are removed from the queue if they
	// have not been polled recently.  Return true for a task that is already in the running list.

	public static synchronized boolean canTaskStart(AppTask theTask) {

		if (runningList.contains(theTask)) {
			return true;
		}

		long now = System.currentTimeMillis();
		theTask.lastPollTime = now;

		boolean result = true;
		int pos = waitingQueue.indexOf(theTask);

		if (((currentMemoryLoad + theTask.memoryLoad) <= MAX_MEMORY_LOAD) &&
				((currentCPULoad + theTask.cpuLoad) <= AppCore.availableCPUCount)) {
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
						if (((currentMemoryLoad + waitingTask.memoryLoad) <= MAX_MEMORY_LOAD) &&
								((currentCPULoad + waitingTask.cpuLoad) <= AppCore.availableCPUCount)) {
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
			currentMemoryLoad += theTask.memoryLoad;
			currentCPULoad += theTask.cpuLoad;
			runningList.add(theTask);
		} else {
			if (pos < 0) {
				waitingQueue.add(theTask);
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Bump a task to the top of the waiting queue.  This will add the task to the queue even if not already there so
	// this does not have to be preceded by canTaskStart(), but it must be followed by such calls, and by taskDone().

	public static synchronized void bumpTask(AppTask theTask) {

		if (runningList.contains(theTask)) {
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
				currentMemoryLoad = 0.;
				currentCPULoad = 0;
			} else {
				currentMemoryLoad -= theTask.memoryLoad;
				currentCPULoad -= theTask.cpuLoad;
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static boolean isResourceAvailable(double theMemLoad) {
		return isResourceAvailable(theMemLoad, 1);
	}

	public static boolean isResourceAvailable(double theMemLoad, int theCPULoad) {

		return (((currentMemoryLoad + theMemLoad) <= MAX_MEMORY_LOAD) &&
			((currentCPULoad + theCPULoad) <= AppCore.availableCPUCount));
	}


	//-----------------------------------------------------------------------------------------------------------------

	public AppTask(double theMemLoad) {

		memoryLoad = theMemLoad;
		if (memoryLoad < 0.) {
			memoryLoad = 0.;
		}
		if (memoryLoad > 1.) {
			memoryLoad = 1.;
		}

		cpuLoad = 1;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public AppTask(double theMemLoad, int theCPULoad) {

		memoryLoad = theMemLoad;
		if (memoryLoad < 0.) {
			memoryLoad = 0.;
		}
		if (memoryLoad > 1.) {
			memoryLoad = 1.;
		}

		cpuLoad = theCPULoad;
		if (cpuLoad < 1) {
			cpuLoad = 1;
		}
		if (cpuLoad > AppCore.availableCPUCount) {
			cpuLoad = AppCore.availableCPUCount;
		}
	}
}
