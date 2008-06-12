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

package net.sf.mzmine.modules.peakpicking.twostep.massdetection;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.reflect.Constructor;
import java.text.NumberFormat;
import java.util.Iterator;
import java.util.Vector;
import java.util.logging.Logger;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ComboBoxModel;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.border.Border;
import javax.swing.border.EtchedBorder;

import net.sf.mzmine.data.DataPoint;
import net.sf.mzmine.data.Parameter;
import net.sf.mzmine.data.Peak;
import net.sf.mzmine.data.RawDataFile;
import net.sf.mzmine.data.Scan;
import net.sf.mzmine.data.impl.SimpleParameterSet;
import net.sf.mzmine.data.impl.SimplePeakList;
import net.sf.mzmine.data.impl.SimplePeakListRow;
import net.sf.mzmine.desktop.Desktop;
import net.sf.mzmine.main.MZmineCore;
import net.sf.mzmine.modules.peakpicking.twostep.TwoStepPickerParameters;
import net.sf.mzmine.modules.visualization.spectra.PeakListDataSet;
import net.sf.mzmine.modules.visualization.spectra.PlotMode;
import net.sf.mzmine.modules.visualization.spectra.ScanDataSet;
import net.sf.mzmine.modules.visualization.spectra.SpectraPlot;
import net.sf.mzmine.modules.visualization.spectra.SpectraToolBar;
import net.sf.mzmine.util.GUIUtils;
import net.sf.mzmine.util.Range;
import net.sf.mzmine.util.dialogs.ParameterSetupDialog;

import org.jfree.chart.labels.XYToolTipGenerator;

/**
 * This class extends ParameterSetupDialog class, including a spectraPlot. This
 * is used to preview how the selected mass detector and his parameters works
 * over the raw data file.
 */
public class MassDetectorSetupDialog extends ParameterSetupDialog implements
		ActionListener, PropertyChangeListener {

	private Logger logger = Logger.getLogger(this.getClass().getName());

	private RawDataFile previewDataFile;
	private RawDataFile[] dataFiles;
	private String[] fileNames;

	// Dialog components
	private JPanel pnlPlotXY, pnlLocal;
	private JComboBox comboDataFileName, comboScanNumber;
	private JCheckBox preview;
	private JButton nextScanBtn;
	private JButton prevScanBtn;
	private int indexComboFileName;

	// Currently loaded scan
	private Scan currentScan;
	private String[] currentScanNumberlist;
	private int[] listScans;

	// XYPlot
	private SpectraToolBar toolBar;
	private SpectraPlot spectrumPlot;
	private ScanDataSet scanDataSet;
	private PeakListDataSet peaksDataSet;

	// Mass Detector;
	private MassDetector massDetector;
	private SimpleParameterSet mdParameters;
	private int massDetectorTypeNumber;

	// Desktop
	private Desktop desktop = MZmineCore.getDesktop();

	/**
	 * @param parameters
	 * @param massDetectorTypeNumber
	 */
	public MassDetectorSetupDialog(TwoStepPickerParameters parameters,
			int massDetectorTypeNumber) {

		super(TwoStepPickerParameters.massDetectorNames[massDetectorTypeNumber]
				+ "'s parameter setup dialog ", parameters
				.getMassDetectorParameters(massDetectorTypeNumber));

		dataFiles = MZmineCore.getCurrentProject().getDataFiles();
		this.massDetectorTypeNumber = massDetectorTypeNumber;
		peaksDataSet = null;

		if (dataFiles.length != 0) {

			if (desktop.getSelectedDataFiles().length != 0)
				previewDataFile = desktop.getSelectedDataFiles()[0];
			else
				previewDataFile = dataFiles[0];

			// Parameters of local mass detector to get preview values
			mdParameters = parameters.getMassDetectorParameters(
					massDetectorTypeNumber).clone();

			// List of scan to apply mass detector
			listScans = previewDataFile.getScanNumbers(1);
			currentScanNumberlist = new String[listScans.length];
			for (int i = 0; i < listScans.length; i++)
				currentScanNumberlist[i] = String.valueOf(listScans[i]);

			fileNames = new String[dataFiles.length];

			for (int i = 0; i < dataFiles.length; i++) {
				fileNames[i] = dataFiles[i].getFileName();
				if (fileNames[i].equals(previewDataFile.getFileName()))
					indexComboFileName = i;
			}

			// Set a listener in all parameters's fields to add functionality to
			// this dialog
			Component[] fields = pnlFields.getComponents();
			for (Component field : fields) {
				field.addPropertyChangeListener("value", this);
				if (field instanceof JCheckBox)
					((JCheckBox) field).addActionListener(this);
				if (field instanceof JComboBox)
					((JComboBox) field).addActionListener(this);
			}

			// Add all complementary components for this dialog
			addComponentsPnl();
			add(pnlLocal);
			pack();
			setLocationRelativeTo(MZmineCore.getDesktop().getMainFrame());
		}
	}

	/**
	 * This function set all the information into the plot chart
	 * 
	 * @param scanNumber
	 */
	private void loadScan(final int scanNumber) {
		// Formats
		NumberFormat rtFormat = MZmineCore.getRTFormat();
		NumberFormat mzFormat = MZmineCore.getMZFormat();
		NumberFormat intensityFormat = MZmineCore.getIntensityFormat();

		currentScan = previewDataFile.getScan(scanNumber);
		scanDataSet = new ScanDataSet(currentScan);

		toolBar.setPeaksButtonEnabled(true);
		spectrumPlot.setDataSets(scanDataSet, peaksDataSet);

		// Set plot mode only if it hasn't been set before
		if (spectrumPlot.getPlotMode() == PlotMode.UNDEFINED)
			// if the scan is centroided, switch to centroid mode
			if (currentScan.isCentroided()) {
				spectrumPlot.setPlotMode(PlotMode.CENTROID);
				toolBar.setCentroidButton(false);
			} else {
				spectrumPlot.setPlotMode(PlotMode.CONTINUOUS);
				toolBar.setCentroidButton(true);
			}

		// Set window and plot titles

		String title = "[" + previewDataFile.toString() + "] scan #"
				+ currentScan.getScanNumber();

		String subTitle = "MS" + currentScan.getMSLevel() + ", RT "
				+ rtFormat.format(currentScan.getRetentionTime());

		DataPoint basePeak = currentScan.getBasePeak();
		if (basePeak != null) {
			subTitle += ", base peak: " + mzFormat.format(basePeak.getMZ())
					+ " m/z ("
					+ intensityFormat.format(basePeak.getIntensity()) + ")";
		}
		spectrumPlot.setTitle(title, subTitle);

	}

	public void actionPerformed(ActionEvent event) {

		Object src = event.getSource();
		String command = event.getActionCommand();

		if (src == btnOK) {
			super.actionPerformed(event);
		}

		if (src == btnCancel) {
			dispose();
		}

		if ((src == comboScanNumber) 
				|| ((src instanceof JCheckBox) && (src != preview))
				|| ((src instanceof JComboBox) && (src != comboDataFileName))) {
			int ind = comboScanNumber.getSelectedIndex();
			setPeakListDataSet(ind);
			loadScan(listScans[ind]);
		}

		if (src == comboDataFileName) {
			int ind = comboDataFileName.getSelectedIndex();
			if (ind >= 0) {
				previewDataFile = dataFiles[ind];
				listScans = previewDataFile.getScanNumbers(1);
				currentScanNumberlist = new String[listScans.length];
				for (int i = 0; i < listScans.length; i++)
					currentScanNumberlist[i] = String.valueOf(listScans[i]);
				ComboBoxModel model = new DefaultComboBoxModel(
						currentScanNumberlist);
				comboScanNumber.setModel(model);
				comboScanNumber.setSelectedIndex(0);

				setPeakListDataSet(0);
				loadScan(listScans[0]);
			}
		}

		if (src == preview) {
			if (preview.isSelected()) {
				int ind = comboScanNumber.getSelectedIndex();
				pnlLocal.add(pnlPlotXY, BorderLayout.CENTER);
				add(pnlLocal);
				pack();
				comboDataFileName.setEnabled(true);
				comboScanNumber.setEnabled(true);
				nextScanBtn.setEnabled(true);
				prevScanBtn.setEnabled(true);
				setPeakListDataSet(ind);
				loadScan(listScans[ind]);
				this.setResizable(true);
				setLocationRelativeTo(MZmineCore.getDesktop().getMainFrame());
			} else {
				pnlLocal.remove(pnlPlotXY);
				add(pnlLocal);
				pack();
				comboDataFileName.setEnabled(false);
				comboScanNumber.setEnabled(false);
				nextScanBtn.setEnabled(false);
				prevScanBtn.setEnabled(false);
				this.setResizable(false);
				setLocationRelativeTo(MZmineCore.getDesktop().getMainFrame());
			}
		}

		if (command.equals("PREVIOUS_SCAN")) {
			int ind = comboScanNumber.getSelectedIndex() - 1;
			if (ind >= 0)
				comboScanNumber.setSelectedIndex(ind);
		}

		if (command.equals("NEXT_SCAN")) {
			int ind = comboScanNumber.getSelectedIndex() + 1;
			if (ind < (listScans.length - 1))
				comboScanNumber.setSelectedIndex(ind);
		}

	}

	/**
	 * First get the actual values in the form, upgrade parameters for our local
	 * mass detector. After calculate all possible peaks, we create a new
	 * PeakListSet for the selected DataFile and Scan in the form.
	 * 
	 * @param ind
	 */
	public void setPeakListDataSet(int ind) {

		SimplePeakList newPeakList = new SimplePeakList(previewDataFile
				+ "_singleScanPeak", previewDataFile);
		buildParameterSetMassDetector();
		String massDetectorClassName = TwoStepPickerParameters.massDetectorClasses[massDetectorTypeNumber];

		try {
			Class massDetectorClass = Class.forName(massDetectorClassName);
			Constructor massDetectorConstruct = massDetectorClass
					.getConstructors()[0];
			massDetector = (MassDetector) massDetectorConstruct
					.newInstance(mdParameters);
		} catch (Exception e) {
			desktop
					.displayErrorMessage("Error trying to make an instance of mass detector "
							+ massDetectorClassName);
			logger.finest("Error trying to make an instance of mass detector "
					+ massDetectorClassName);
			return;
		}

		Scan scan = previewDataFile.getScan(listScans[ind]);
		MzPeak[] mzValues = massDetector.getMassValues(scan);

		if (mzValues == null) {
			peaksDataSet = null;
			return;
		}

		Vector<Peak> pickedDataPoint = new Vector<Peak>();

		for (MzPeak mzPeak : mzValues) {
			pickedDataPoint.add(new FakePeak(scan.getScanNumber(), mzPeak));
		}

		int newPeakID = 1;
		for (Peak finishedPeak : pickedDataPoint) {
			SimplePeakListRow newRow = new SimplePeakListRow(newPeakID);
			newPeakID++;
			newRow.addPeak(previewDataFile, finishedPeak, finishedPeak);
			newPeakList.addRow(newRow);
		}

		peaksDataSet = new PeakListDataSet(previewDataFile, scan
				.getScanNumber(), newPeakList);
		freeMemory();
	}

	/**
	 * This function collect all the information from the form's filed and build
	 * the ParameterSet.
	 * 
	 */
	void buildParameterSetMassDetector() {
		Iterator<Parameter> paramIter = parametersAndComponents.keySet()
				.iterator();
		while (paramIter.hasNext()) {
			Parameter p = paramIter.next();

			try {

				Object[] possibleValues = p.getPossibleValues();
				if (possibleValues != null) {
					JComboBox combo = (JComboBox) parametersAndComponents
							.get(p);
					mdParameters.setParameterValue(p, possibleValues[combo
							.getSelectedIndex()]);
					continue;
				}

				switch (p.getType()) {
				case INTEGER:
					JFormattedTextField intField = (JFormattedTextField) parametersAndComponents
							.get(p);
					Integer newIntValue = ((Number) intField.getValue())
							.intValue();
					mdParameters.setParameterValue(p, newIntValue);
					break;
				case FLOAT:
					JFormattedTextField doubleField = (JFormattedTextField) parametersAndComponents
							.get(p);
					Float newFloatValue = ((Number) doubleField.getValue())
							.floatValue();
					mdParameters.setParameterValue(p, newFloatValue);
					break;
				case RANGE:
					JPanel panel = (JPanel) parametersAndComponents.get(p);
					JFormattedTextField minField = (JFormattedTextField) panel
							.getComponent(0);
					JFormattedTextField maxField = (JFormattedTextField) panel
							.getComponent(2);
					float minValue = ((Number) minField.getValue())
							.floatValue();
					float maxValue = ((Number) maxField.getValue())
							.floatValue();
					Range rangeValue = new Range(minValue, maxValue);
					mdParameters.setParameterValue(p, rangeValue);
					break;
				case STRING:
					JTextField stringField = (JTextField) parametersAndComponents
							.get(p);
					mdParameters.setParameterValue(p, stringField.getText());
					break;
				case BOOLEAN:
					JCheckBox checkBox = (JCheckBox) parametersAndComponents
							.get(p);
					Boolean newBoolValue = checkBox.isSelected();
					mdParameters.setParameterValue(p, newBoolValue);
					break;
				}

			} catch (Exception invalidValueException) {
				desktop.displayMessage(invalidValueException.getMessage());
				return;
			}

		}
	}

	public void propertyChange(PropertyChangeEvent e) {
		if (preview.isSelected()) {
			int ind = comboScanNumber.getSelectedIndex();
			setPeakListDataSet(ind);
			loadScan(listScans[ind]);
		}
	}

	/**
	 * This function add all the additional components for this dialog over the
	 * original ParameterSetupDialog.
	 * 
	 */
	private void addComponentsPnl() {

		// Button's parameters
		String leftArrow = new String(new char[] { '\u2190' });
		String rightArrow = new String(new char[] { '\u2192' });

		// Elements of pnlpreview
		JPanel pnlpreview = new JPanel(new BorderLayout());
		preview = new JCheckBox(" Show preview of mass peak detection ");
		preview.addActionListener(this);

		JSeparator line = new JSeparator();

		pnlpreview.add(line, BorderLayout.NORTH);
		pnlpreview.add(preview, BorderLayout.CENTER);

		// Elements of pnlLab
		JPanel pnlLab = new JPanel();
		pnlLab.setLayout(new BoxLayout(pnlLab, BoxLayout.Y_AXIS));
		pnlLab.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

		JLabel lblFileSelected = new JLabel("Data file ");
		JLabel lblScanNumber = new JLabel("Scan number ");

		pnlLab.add(lblFileSelected);
		pnlLab.add(Box.createVerticalStrut(25));
		pnlLab.add(lblScanNumber);

		// Elements of pnlFlds
		JPanel pnlFlds = new JPanel();
		pnlFlds.setLayout(new BoxLayout(pnlFlds, BoxLayout.Y_AXIS));
		pnlFlds.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

		comboDataFileName = new JComboBox(fileNames);
		comboDataFileName.setSelectedIndex(indexComboFileName);
		comboDataFileName.setBackground(Color.WHITE);
		comboDataFileName.addActionListener(this);
		comboDataFileName.setEnabled(false);

		comboScanNumber = new JComboBox(currentScanNumberlist);
		comboScanNumber.setSelectedIndex(0);
		comboScanNumber.setBackground(Color.WHITE);
		comboScanNumber.addActionListener(this);
		comboScanNumber.setEnabled(false);

		pnlFlds.add(comboDataFileName);
		pnlFlds.add(Box.createVerticalStrut(10));

		// --> Elements of pnlScanArrows
		JPanel pnlScanArrows = new JPanel();
		pnlScanArrows.setLayout(new BoxLayout(pnlScanArrows, BoxLayout.X_AXIS));

		prevScanBtn = GUIUtils.addButton(pnlScanArrows, leftArrow, null,
				(ActionListener) this, "PREVIOUS_SCAN");
		prevScanBtn.setFont(new Font("SansSerif", Font.BOLD, 14));
		prevScanBtn.setEnabled(false);

		pnlScanArrows.add(Box.createHorizontalStrut(5));
		pnlScanArrows.add(comboScanNumber);
		pnlScanArrows.add(Box.createHorizontalStrut(5));

		nextScanBtn = GUIUtils.addButton(pnlScanArrows, rightArrow, null,
				(ActionListener) this, "NEXT_SCAN");
		nextScanBtn.setFont(new Font("SansSerif", Font.BOLD, 14));
		nextScanBtn.setEnabled(false);
		// <--

		pnlFlds.add(pnlScanArrows);

		// Elements of pnlSpace
		JPanel pnlSpace = new JPanel();
		pnlSpace.setLayout(new BoxLayout(pnlSpace, BoxLayout.Y_AXIS));
		pnlSpace.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

		pnlSpace.add(Box.createHorizontalStrut(50));

		// Put all together
		JPanel pnlFileNameScanNumber = new JPanel(new BorderLayout());

		pnlFileNameScanNumber.add(pnlpreview, BorderLayout.NORTH);
		pnlFileNameScanNumber.add(pnlLab, BorderLayout.WEST);
		pnlFileNameScanNumber.add(pnlFlds, BorderLayout.CENTER);
		pnlFileNameScanNumber.add(pnlSpace, BorderLayout.EAST);

		// Panel for XYPlot
		pnlPlotXY = new JPanel(new BorderLayout());
		Border one = BorderFactory.createEtchedBorder(EtchedBorder.RAISED);
		Border two = BorderFactory.createEmptyBorder(10, 10, 10, 10);
		pnlPlotXY.setBorder(BorderFactory.createCompoundBorder(one, two));
		pnlPlotXY.setBackground(Color.white);

		spectrumPlot = new SpectraPlot(this);
		MzPeakToolTipGenerator mzPeakToolTipGenerator = new MzPeakToolTipGenerator();
		spectrumPlot
				.setPeakToolTipGenerator((XYToolTipGenerator) mzPeakToolTipGenerator);
		pnlPlotXY.add(spectrumPlot, BorderLayout.CENTER);

		toolBar = new SpectraToolBar(spectrumPlot);
		spectrumPlot.setRelatedToolBar(toolBar);
		pnlPlotXY.add(toolBar, BorderLayout.EAST);

		labelsAndFields.add(pnlFileNameScanNumber, BorderLayout.SOUTH);

		// Complete panel for this dialog including pnlPlotXY
		pnlLocal = new JPanel(new BorderLayout());

		pnlLocal.add(pnlAll, BorderLayout.WEST);
	}
	
	protected void freeMemory() {
		System.gc();
		System.runFinalization();

	}

}