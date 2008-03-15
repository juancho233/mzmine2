/*
 * Copyright 2006-2008 The MZmine Development Team
 * 
 * This file is part of MZmine.
 * 
 * MZmine is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 * 
 * MZmine is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * MZmine; if not, write to the Free Software Foundation, Inc., 51 Franklin St,
 * Fifth Floor, Boston, MA 02110-1301 USA
 */

package net.sf.mzmine.modules.alignment.fragment;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Logger;

import net.sf.mzmine.data.ParameterSet;
import net.sf.mzmine.data.PeakList;
import net.sf.mzmine.data.PeakListRow;
import net.sf.mzmine.data.impl.SimpleParameterSet;
import net.sf.mzmine.data.impl.SimplePeakList;
import net.sf.mzmine.io.RawDataFile;
import net.sf.mzmine.main.MZmineCore;
import net.sf.mzmine.modules.batchmode.BatchStepAlignment;
import net.sf.mzmine.taskcontrol.Task;
import net.sf.mzmine.taskcontrol.TaskGroup;
import net.sf.mzmine.taskcontrol.TaskGroupListener;
import net.sf.mzmine.taskcontrol.TaskListener;
import net.sf.mzmine.taskcontrol.Task.TaskStatus;
import net.sf.mzmine.userinterface.Desktop;
import net.sf.mzmine.userinterface.Desktop.MZmineMenu;
import net.sf.mzmine.userinterface.dialogs.ExitCode;
import net.sf.mzmine.userinterface.dialogs.ParameterSetupDialog;
import net.sf.mzmine.util.PeakListRowSorter;
import net.sf.mzmine.util.PeakListRowSorter.SortingDirection;
import net.sf.mzmine.util.PeakListRowSorter.SortingProperty;

/**
 * This aligner first divides m/z range to fragments and then aligns peaks for
 * each fragment independently.
 * 
 */
public class FragmentAligner implements BatchStepAlignment, TaskListener,
		ActionListener {

	private Logger logger = Logger.getLogger(this.getClass().getName());

	private FragmentAlignerParameters parameters;

	private Desktop desktop;

	private ArrayList<PeakList> fragmentResults;

	private ArrayList<Task> startedTasks;

	// This task and taskgroup concatenate fragment results and signal
	// completion of the whole alignment method to caller
	private ConcatenateFragmentsTask concatenateFragmentsTask;
	private TaskGroup concatenateFragmentsTaskGroup;

	/**
	 * @see net.sf.mzmine.main.MZmineModule#initModule(net.sf.mzmine.main.MZmineCore)
	 */
	public void initModule() {

		this.desktop = MZmineCore.getDesktop();

		parameters = new FragmentAlignerParameters();

		desktop.addMenuItem(MZmineMenu.ALIGNMENT, toString(), this, null,
				KeyEvent.VK_F, false, true);

	}

	public String toString() {
		return "Fragment aligner";
	}

	/**
	 * @see net.sf.mzmine.main.MZmineModule#getParameterSet()
	 */
	public ParameterSet getParameterSet() {
		return parameters;
	}

	public void setParameters(ParameterSet parameters) {
		this.parameters = (FragmentAlignerParameters) parameters;
	}

	/**
	 * @see net.sf.mzmine.modules.BatchStep#setupParameters(net.sf.mzmine.data.ParameterSet)
	 */
	public ExitCode setupParameters(ParameterSet currentParameters) {
		ParameterSetupDialog dialog = new ParameterSetupDialog(
				"Please set parameter values for " + toString(),
				(SimpleParameterSet) currentParameters);
		dialog.setVisible(true);
		return dialog.getExitCode();
	}

	/**
	 * @see java.awt.event.ActionListener#actionPerformed(java.awt.event.ActionEvent)
	 */
	public void actionPerformed(ActionEvent e) {

		PeakList[] peakLists = desktop.getSelectedPeakLists();

		if (peakLists.length == 0) {
			desktop
					.displayErrorMessage("Please select peak lists for alignment");
			return;
		}

		// Setup parameters
		ExitCode exitCode = setupParameters(parameters);
		if (exitCode != ExitCode.OK)
			return;

		runModule(null, peakLists, parameters.clone(), null);

	}

	public void taskStarted(Task task) {
	}

	public synchronized void taskFinished(Task task) {

		concatenateFragmentsTask.taskFinished(task);

		startedTasks.remove(task);

		if (task.getStatus() == Task.TaskStatus.FINISHED) {

			// Check if a task has been canceled
			for (Task t : startedTasks)
				// If yes, then cancel all tasks
				if (t.getStatus() == TaskStatus.CANCELED) {
					for (Task tt : startedTasks) {
						tt.cancel();
					}
					break;
				}

			// All fragments done?
			if (startedTasks.size() == 0) {

				// Run a task to concatenate fragment results
				concatenateFragmentsTaskGroup.start();

			}

		}

		if (task.getStatus() == Task.TaskStatus.CANCELED) {

			logger.info("Fragment aliger canceled.");

			// Cancel tasks for all other fragments
			for (Task t : startedTasks)
				t.cancel();

			// Run task just to signal finishing the task group.
			concatenateFragmentsTaskGroup.start();

		}

		if (task.getStatus() == Task.TaskStatus.ERROR) {

			// Cancel tasks for all other fragments
			for (Task t : startedTasks)
				t.cancel();

			String msg = "Error while aligning peak lists: "
					+ task.getErrorMessage();
			logger.severe(msg);
			desktop.displayErrorMessage(msg);

			// Run task just to signal finishing the task group.
			concatenateFragmentsTaskGroup.start();

		}

	}

	protected void addFragmentResult(PeakList peakList) {
		fragmentResults.add(peakList);
	}

	/**
	 * @see net.sf.mzmine.modules.BatchStep#runModule(net.sf.mzmine.io.RawDataFile[],
	 *      net.sf.mzmine.data.PeakList[], net.sf.mzmine.data.ParameterSet,
	 *      net.sf.mzmine.taskcontrol.TaskGroupListener)
	 */
	public TaskGroup runModule(RawDataFile[] dataFiles, PeakList[] peakLists,
			ParameterSet parameters, TaskGroupListener taskGroupListener) {

		this.parameters = (FragmentAlignerParameters) parameters;

		// check peak lists
		if ((peakLists == null) || (peakLists.length == 0)) {
			desktop
					.displayErrorMessage("Please select peak lists for alignment");
			return null;
		}

		// Collect m/z and rt values for all rows in all peak lists and sort
		// them
		int numberOfRows = 0;
		for (PeakList peakList : peakLists)
			numberOfRows += peakList.getNumberOfRows();

		float[] mzValues = new float[numberOfRows];
		float[] rtValues = new float[numberOfRows];
		int valueIndex = 0;
		for (PeakList peakList : peakLists)
			for (PeakListRow row : peakList.getRows()) {
				mzValues[valueIndex] = row.getAverageMZ();
				rtValues[valueIndex] = row.getAverageRT();
				valueIndex++;
			}
		Arrays.sort(mzValues);
		Arrays.sort(rtValues);

		// Find all possible boundaries between fragments
		float mzTolerance = (Float) this.parameters
				.getParameterValue(FragmentAlignerParameters.MZTolerance);

		ArrayList<Float> allMZFragmentLimits = new ArrayList<Float>();
		ArrayList<Integer> allMZFragmentPeaks = new ArrayList<Integer>();
		int prevIndex = 0;
		for (valueIndex = 0; valueIndex < (mzValues.length - 1); valueIndex++) {
			float mzDiff = mzValues[valueIndex + 1] - mzValues[valueIndex];
			if (mzDiff > mzTolerance) {
				allMZFragmentLimits.add(mzValues[valueIndex]);
				allMZFragmentPeaks.add((valueIndex - prevIndex));
				prevIndex = valueIndex;
			}
		}

		logger.finest("Found " + allMZFragmentLimits.size()
				+ " possible m/z positions for fragment boundaries.");

		// Filter fragments if too many
		int maxNumberOfFragments = (Integer) this.parameters
				.getParameterValue(FragmentAlignerParameters.MaxFragments);

		ArrayList<Float> filteredMZFragmentLimits;

		if (maxNumberOfFragments < allMZFragmentLimits.size()) {

			filteredMZFragmentLimits = new ArrayList<Float>();

			int peaksPerFragment = (int) Math.ceil((float) mzValues.length
					/ (float) maxNumberOfFragments);

			int sumPeaks = 0;
			for (int mzFragmentIndex = 0; mzFragmentIndex < allMZFragmentLimits
					.size(); mzFragmentIndex++) {

				sumPeaks += allMZFragmentPeaks.get(mzFragmentIndex);
				if (sumPeaks >= peaksPerFragment) {
					filteredMZFragmentLimits.add(allMZFragmentLimits
							.get(mzFragmentIndex));
					sumPeaks = 0;
				}

			}

		} else {
			filteredMZFragmentLimits = allMZFragmentLimits;
		}

		logger.finest("Using " + (filteredMZFragmentLimits.size() + 1)
				+ " fragments.");

		/*
		 * for (float mz : filteredMZFragmentLimits) { System.out.print("" + mz + ",
		 * "); } System.out.println();
		 */

		Float[] mzFragmentLimits = filteredMZFragmentLimits
				.toArray(new Float[0]);

		// Initialize a temp peak list for each fragment and peak list
		PeakList[][] peakListsForFragments = new PeakList[allMZFragmentLimits
				.size() + 1][peakLists.length];
		for (int mzFragmentIndex = 0; mzFragmentIndex < (mzFragmentLimits.length + 1); mzFragmentIndex++) {
			for (int peakListIndex = 0; peakListIndex < peakLists.length; peakListIndex++) {
				PeakList peakList = peakLists[peakListIndex];
				peakListsForFragments[mzFragmentIndex][peakListIndex] = new SimplePeakList(
						peakList.toString() + " fragment  "
								+ (mzFragmentIndex + 1) + " of "
								+ (mzFragmentLimits.length + 1), peakList
								.getRawDataFiles());
			}
		}

		// Divide rows of each peak list to fragments
		for (int peakListIndex = 0; peakListIndex < peakLists.length; peakListIndex++) {
			PeakList peakList = peakLists[peakListIndex];

			PeakListRow[] peakListRows = peakList.getRows();
			Arrays.sort(peakListRows, new PeakListRowSorter(SortingProperty.MZ,
					SortingDirection.Ascending));

			int mzFragmentIndex = 0;
			for (PeakListRow peakListRow : peakListRows) {

				float mz = peakListRow.getAverageMZ();

				// Move to next fragment if possible and necessary
				while ((mzFragmentIndex < mzFragmentLimits.length)
						&& (mzFragmentLimits[mzFragmentIndex] < mz)) {
					mzFragmentIndex++;
				}

				peakListsForFragments[mzFragmentIndex][peakListIndex]
						.addRow(peakListRow);

			}

		}

		// Initialize concatenate task and task group
		fragmentResults = new ArrayList<PeakList>();
		concatenateFragmentsTask = new ConcatenateFragmentsTask(
				fragmentResults, this.parameters);
		concatenateFragmentsTaskGroup = new TaskGroup(concatenateFragmentsTask,
				null, taskGroupListener);

		// Start a task for each fragment
		startedTasks = new ArrayList<Task>();
		for (int mzFragmentIndex = 0; mzFragmentIndex < (mzFragmentLimits.length + 1); mzFragmentIndex++) {
			peakLists = peakListsForFragments[mzFragmentIndex];
			String name;
			if (mzFragmentIndex < mzFragmentLimits.length) {
				name = "m/z up to " + mzFragmentLimits[mzFragmentIndex];
			} else {
				if (mzFragmentLimits.length > 0)
					name = "m/z after "
							+ mzFragmentLimits[mzFragmentLimits.length - 1];
				else
					name = "single fragment";
			}

			Task t = new AlignFragmentTask(peakLists, this.parameters, name,
					this);
			startedTasks.add(t);
			MZmineCore.getTaskController().addTask(t, this);
		}

		return concatenateFragmentsTaskGroup;
	}

}
