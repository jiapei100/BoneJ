package org.bonej;

/**
 * EllipsoidFactor plugin for ImageJ
 * Copyright 2013 2014 2015 Michael Doube
 * 
 *This program is free software: you can redistribute it and/or modify
 *it under the terms of the GNU General Public License as published by
 *the Free Software Foundation, either version 3 of the License, or
 *(at your option) any later version.
 *
 *This program is distributed in the hope that it will be useful,
 *but WITHOUT ANY WARRANTY; without even the implied warranty of
 *MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *GNU General Public License for more details.
 *
 *You should have received a copy of the GNU General Public License
 *along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

import javax.vecmath.Color3f;
import javax.vecmath.Point3f;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.plugin.PlugIn;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.gui.GenericDialog;
import ij.gui.Plot;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
//import ij.measure.ResultsTable;
import ij3d.Image3DUniverse;

import org.doube.geometry.Trig;
import org.doube.geometry.Vectors;
import org.doube.geometry.Ellipsoid;
import org.doube.jama.Matrix;
import org.doube.skeleton.Skeletonize3D;
import org.doube.util.ArrayHelper;
import org.doube.util.ImageCheck;
import org.doube.util.Multithreader;
//import org.doube.util.ResultInserter;
import org.doube.util.UsageReporter;

import customnode.CustomLineMesh;
import customnode.CustomPointMesh;

/**
 * <p>
 * <b>Ellipsoid Factor</b>
 * </p>
 * <p>
 * ImageJ plugin to describe the local geometry of a binary image in an
 * oblate/prolate spheroid space. Uses Skeletonize3D to generate a 3D skeleton,
 * the points of which are used as centres for maximally inscribed ellipsoids.
 * The ellipsoid factor (EF) is a method for the local determination of the rod-
 * or plate-like nature of porous or spongy continua. EF at a point within a 3D
 * structure is defined as the difference in axis ratios of the greatest
 * ellipsoid that fits inside the structure and that contains the point of
 * interest, and ranges from −1 for strongly oblate (discus-shaped) ellipsoids,
 * to +1 for strongly prolate (javelin-shaped) ellipsoids. For an ellipsoid with
 * axes a ≤ b ≤ c, EF = a/b − b/c.
 * </p>
 * 
 * @see http://dx.doi.org/10.3389/fendo.2015.00015
 *      "The ellipsoid factor for quantification of rods, plates, and intermediate forms in 3D geometries"
 *      Frontiers in Endocrinology (2015)
 * @author Michael Doube
 * 
 */
public class EllipsoidFactor implements PlugIn, Comparator<Ellipsoid> {
	private int nVectors = 100;

	/**
	 * increment for vector searching in real units. Defaults to ~Nyquist
	 * sampling of a unit pixel
	 */
	private double vectorIncrement = 1 / 2.3;

	/**
	 * Number of skeleton points per ellipsoid. Sets the granularity of the
	 * ellipsoid fields.
	 */
	private int skipRatio = 50;
	private int contactSensitivity = 1;
	/** Safety value to prevent while() running forever */
	private int maxIterations = 100;

	/**
	 * maximum distance ellipsoid may drift from seed point. Defaults to voxel
	 * diagonal length
	 */
	private double maxDrift = Math.sqrt(3);
	// private ResultsTable rt;
	private Image3DUniverse universe;

	private double stackVolume;

	private ResultsTable debugLog, criticalPoints;

	// private ResultsTable ellipseScanLog;

	public void run(String arg) {
		if (!ImageCheck.checkEnvironment())
			return;
		ImagePlus imp = IJ.getImage();
		if (imp == null) {
			IJ.noImage();
			return;
		}
		ImageCheck ic = new ImageCheck();
		if (!ic.isBinary(imp) || !ic.isMultiSlice(imp)) {// ||
															// !ic.isVoxelIsotropic(imp,
															// 0.001)) {
			IJ.error("8-bit binary stack required.");
			return;
		}
		Calibration cal = imp.getCalibration();
		String units = cal.getUnits();
		final double pW = cal.pixelWidth;
		final double pH = cal.pixelHeight;
		final double pD = cal.pixelDepth;
		vectorIncrement *= Math.sqrt(3) * Math.min(pD, Math.min(pH, pW));
		maxDrift = Math.sqrt(pW * pW + pH * pH + pD * pD);
		stackVolume = pW * pH * pD * imp.getWidth() * imp.getHeight()
				* imp.getStackSize();
		GenericDialog gd = new GenericDialog("Setup");
		gd.addMessage("Sampling options");
		gd.addNumericField("Sampling_increment", vectorIncrement, 3, 8, units);
		gd.addNumericField("Vectors", nVectors, 0, 8, "");
		gd.addNumericField("Skeleton_points per ellipsoid", skipRatio, 0);
		gd.addNumericField("Contact sensitivity", contactSensitivity, 0, 4, "");
		gd.addNumericField("Maximum_iterations", maxIterations, 0);
		gd.addNumericField("Maximum_drift", maxDrift, 5, 8, units);

		gd.addMessage("\nOutput options");
		gd.addCheckbox("EF_image", true);
		gd.addCheckbox("Ellipsoid_ID_image", false);
		gd.addCheckbox("Volume_image", false);
		gd.addCheckbox("Axis_ratio_images", false);
		gd.addCheckbox("Flinn_peak_plot", true);
		gd.addNumericField("Gaussian_sigma", 2, 0, 4, "px");
		gd.addCheckbox("Flinn_plot", false);

		gd.addMessage("Ellipsoid Factor is beta software.\n"
				+ "Please report your experiences to the user group:\n"
				+ "http://bit.ly/bonej-group");
		gd.addHelp("http://bonej.org/ef");
		gd.showDialog();

		if (gd.wasCanceled())
			return;

		// if (!Interpreter.isBatchMode()) {
		vectorIncrement = gd.getNextNumber();
		nVectors = (int) Math.round(gd.getNextNumber());
		skipRatio = (int) Math.round(gd.getNextNumber());
		contactSensitivity = (int) Math.round(gd.getNextNumber());
		maxIterations = (int) Math.round(gd.getNextNumber());
		maxDrift = gd.getNextNumber();

		boolean doEFImage = gd.getNextBoolean();
		boolean doEllipsoidIDImage = gd.getNextBoolean();
		boolean doVolumeImage = gd.getNextBoolean();
		boolean doAxisRatioImages = gd.getNextBoolean();
		boolean doFlinnPeakPlot = gd.getNextBoolean();
		double gaussianSigma = gd.getNextNumber();
		boolean doFlinnPlot = gd.getNextBoolean();

		// }

		final double[][] unitVectors = Vectors.regularVectors(nVectors);
		int[][] skeletonPoints = skeletonPoints(imp);

		IJ.log("Found " + skeletonPoints.length + " skeleton points");

		if (IJ.debugMode) {
			universe = new Image3DUniverse();
			universe.show();
			debugLog = new ResultsTable();
			criticalPoints = new ResultsTable();
		}

		long start = System.currentTimeMillis();
		Ellipsoid[] ellipsoids = findEllipsoids(imp, skeletonPoints,
				unitVectors);
		long stop = System.currentTimeMillis();

		IJ.log("Found " + ellipsoids.length + " ellipsoids in "
				+ (stop - start) + " ms");

		start = System.currentTimeMillis();
		int[][] maxIDs = findMaxID(imp, ellipsoids);
		stop = System.currentTimeMillis();

		IJ.log("Found maximal ellipsoids in " + (stop - start) + " ms");

		double fractionFilled = calculateFillingEfficiency(maxIDs);
		IJ.log(IJ.d2s((fractionFilled * 100), 3)
				+ "% of foreground volume filled with ellipsoids");

		if (doVolumeImage) {
			ImagePlus volumes = displayVolumes(imp, maxIDs, ellipsoids);
			volumes.show();
			volumes.setDisplayRange(0,
					ellipsoids[(int) (0.05 * ellipsoids.length)].getVolume());
			IJ.run("Fire");
		}

		if (doAxisRatioImages) {
			ImagePlus middleOverLong = displayMiddleOverLong(imp, maxIDs,
					ellipsoids);
			middleOverLong.show();
			middleOverLong.setDisplayRange(0, 1);
			IJ.run("Fire");

			ImagePlus shortOverMiddle = displayShortOverMiddle(imp, maxIDs,
					ellipsoids);
			shortOverMiddle.show();
			shortOverMiddle.setDisplayRange(0, 1);
			IJ.run("Fire");
		}

		if (doEFImage) {
			ImagePlus eF = displayEllipsoidFactor(imp, maxIDs, ellipsoids);
			eF.show();
			eF.setDisplayRange(-1, 1);
			IJ.run("Fire");
		}

		if (doEllipsoidIDImage) {
			ImagePlus maxID = displayMaximumIDs(maxIDs, ellipsoids, imp);
			maxID.show();
			maxID.setDisplayRange(-ellipsoids.length / 2, ellipsoids.length);
		}

		if (doFlinnPlot) {
			ImagePlus flinnPlot = drawFlinnPlot(
					"Weighted-flinn-plot-" + imp.getTitle(), ellipsoids);
			flinnPlot.show();
		}

		if (doFlinnPeakPlot) {
			ImagePlus flinnPeaks = drawFlinnPeakPlot(
					"FlinnPeaks_" + imp.getTitle(), imp, maxIDs, ellipsoids,
					gaussianSigma, 512);
			flinnPeaks.show();
		}

		// ResultInserter ri = ResultInserter.getInstance();
		// ri.updateTable();
		if (IJ.debugMode) {
			debugLog.show("Ellipsoid variables");
			criticalPoints.show("Critical points");
		}
		// rt.show("Ellipsoid volumes");
		UsageReporter.reportEvent(this).send();
		IJ.showStatus("Ellipsoid Factor completed");
	}

	private double calculateFillingEfficiency(final int[][] maxIDs) {
		final int l = maxIDs.length;
		final long[] foregroundCount = new long[l];
		final long[] filledCount = new long[l];

		final AtomicInteger ai = new AtomicInteger(0);
		Thread[] threads = Multithreader.newThreads();
		for (int thread = 0; thread < threads.length; thread++) {
			threads[thread] = new Thread(new Runnable() {
				public void run() {

					for (int i = ai.getAndIncrement(); i < l; i = ai
							.getAndIncrement()) {
						IJ.showStatus("Calculating filling effiency...");
						IJ.showProgress(i, l);
						int[] idSlice = maxIDs[i];
						final int len = idSlice.length;
						for (int j = 0; j < len; j++) {
							final int val = idSlice[j];
							if (val >= -1)
								foregroundCount[i]++;
							if (val >= 0)
								filledCount[i]++;
						}
					}
				}
			});
		}
		Multithreader.startAndJoin(threads);

		long sumForegroundCount = 0;
		long sumFilledCount = 0;

		for (int i = 0; i < l; i++) {
			sumForegroundCount += foregroundCount[i];
			sumFilledCount += filledCount[i];
		}

		long unfilled = sumForegroundCount - sumFilledCount;
		IJ.log(unfilled + " pixels unfilled with ellipsoids out of "
				+ sumForegroundCount + " total foreground pixels");

		return (double) sumFilledCount / (double) sumForegroundCount;
	}

	/**
	 * Display each ellipsoid's axis ratios in a scatter plot
	 * 
	 * @param title
	 * @param ellipsoids
	 * @return
	 */
	private ImagePlus drawFlinnPlot(String title, Ellipsoid[] ellipsoids) {

		final int l = ellipsoids.length;
		double[] aOverB = new double[l];
		double[] bOverC = new double[l];

		for (int i = 0; i < l; i++) {
			double[] radii = ellipsoids[i].getSortedRadii();
			aOverB[i] = radii[0] / radii[1];
			bOverC[i] = radii[1] / radii[2];
		}

		Plot plot = new Plot("Flinn Diagram of " + title, "b/c", "a/b");
		plot.setLimits(0, 1, 0, 1);
		plot.setSize(1024, 1024);
		plot.addPoints(bOverC, aOverB, Plot.CIRCLE);
		ImageProcessor plotIp = plot.getProcessor();
		ImagePlus plotImage = new ImagePlus("Flinn Diagram of " + title, plotIp);
		return plotImage;
	}

	/**
	 * Draw a Flinn diagram with each point given an intensity proportional to
	 * the volume of the structure with that axis ratio
	 * 
	 * @param title
	 * @param imp
	 * @param maxIDs
	 * @param ellipsoids
	 * @param sigma
	 * @param size
	 * 
	 * @return
	 */
	private ImagePlus drawFlinnPeakPlot(String title, ImagePlus imp,
			final int[][] maxIDs, final Ellipsoid[] ellipsoids, double sigma,
			int size) {

		final ImageStack stack = imp.getImageStack();
		final int w = stack.getWidth();
		final int h = stack.getHeight();
		final int d = stack.getSize();

		final float[][] ab = new float[d][];
		final float[][] bc = new float[d][];

		final AtomicInteger ai = new AtomicInteger(1);
		Thread[] threads = Multithreader.newThreads();
		for (int thread = 0; thread < threads.length; thread++) {
			threads[thread] = new Thread(new Runnable() {
				public void run() {

					for (int z = ai.getAndIncrement(); z <= d; z = ai
							.getAndIncrement()) {
						IJ.showStatus("Generating Flinn Diagram");
						IJ.showProgress(z, d);
						int[] idSlice = maxIDs[z];
						int l = 0;
						for (int y = 0; y < h; y++) {
							final int offset = y * w;
							for (int x = 0; x < w; x++)
								if (idSlice[offset + x] >= 0)
									l++;
						}
						float[] abl = new float[l];
						float[] bcl = new float[l];
						int j = 0;
						for (int y = 0; y < h; y++) {
							final int offset = y * w;
							for (int x = 0; x < w; x++) {
								final int i = offset + x;
								final int id = idSlice[i];
								double[] radii = new double[3];
								if (id >= 0) {
									radii = ellipsoids[id].getSortedRadii();
									abl[j] = (float) (radii[0] / radii[1]);
									bcl[j] = (float) (radii[1] / radii[2]);
									j++;
								}
							}
						}
						ab[z - 1] = abl;
						bc[z - 1] = bcl;
					}
				}
			});
		}
		Multithreader.startAndJoin(threads);

		int l = 0;
		for (float[] f : ab)
			l += f.length;

		float[] aOverB = new float[l];
		float[] bOverC = new float[l];

		int i = 0;
		for (float[] fl : ab) {
			for (float f : fl) {
				aOverB[i] = f;
				i++;
			}
		}
		i = 0;
		for (float[] fl : bc) {
			for (float f : fl) {
				bOverC[i] = f;
				i++;
			}
		}

		float[][] pixels = new float[size][size];

		for (int j = 0; j < l; j++) {
			final int x = (int) Math.floor((size - 1) * bOverC[j]);
			final int y = (int) Math.floor((size - 1) * (1 - aOverB[j]));
			pixels[x][y] += 1;
		}

		FloatProcessor fp = new FloatProcessor(pixels);
		if (sigma > 0)
			fp.blurGaussian(sigma);

		Calibration cal = new Calibration();
		cal.setXUnit("b/c");
		cal.setYUnit("a/b");
		cal.pixelWidth = 1.0 / (double) size;
		cal.pixelHeight = 1.0 / (double) size;
		cal.setInvertY(true);
		ImagePlus plot = new ImagePlus(title, fp);
		plot.setCalibration(cal);
		return plot;
	}

	private ImagePlus displayEllipsoidFactor(ImagePlus imp,
			final int[][] maxIDs, final Ellipsoid[] ellipsoids) {
		final ImageStack stack = imp.getImageStack();
		final int w = stack.getWidth();
		final int h = stack.getHeight();
		final int d = stack.getSize();

		final ImageStack efStack = new ImageStack(imp.getWidth(),
				imp.getHeight());

		final float[][] stackPixels = new float[d + 1][w * h];

		final AtomicInteger ai = new AtomicInteger(1);
		Thread[] threads = Multithreader.newThreads();
		for (int thread = 0; thread < threads.length; thread++) {
			threads[thread] = new Thread(new Runnable() {
				public void run() {
					for (int z = ai.getAndIncrement(); z <= d; z = ai
							.getAndIncrement()) {
						IJ.showStatus("Generating EF image");
						IJ.showProgress(z, d);
						int[] idSlice = maxIDs[z];
						float[] pixels = stackPixels[z];

						for (int y = 0; y < h; y++) {
							final int offset = y * w;
							for (int x = 0; x < w; x++) {
								final int i = offset + x;
								final int id = idSlice[i];
								if (id >= 0)
									pixels[i] = (float) ellipsoidFactor(ellipsoids[id]);
								else
									pixels[i] = Float.NaN;
							}
						}
					}
				}
			});
		}
		Multithreader.startAndJoin(threads);

		for (int z = 1; z <= d; z++)
			efStack.addSlice("" + z, stackPixels[z]);

		ImagePlus ef = new ImagePlus("EF-" + imp.getTitle(), efStack);
		ef.setCalibration(imp.getCalibration());
		return ef;
	}

	private ImagePlus displayShortOverMiddle(ImagePlus imp,
			final int[][] maxIDs, final Ellipsoid[] ellipsoids) {
		final ImageStack stack = imp.getImageStack();
		final int w = stack.getWidth();
		final int h = stack.getHeight();
		final int d = stack.getSize();

		final ImageStack smStack = new ImageStack(imp.getWidth(),
				imp.getHeight());

		final float[][] stackPixels = new float[d + 1][w * h];

		final AtomicInteger ai = new AtomicInteger(1);
		Thread[] threads = Multithreader.newThreads();
		for (int thread = 0; thread < threads.length; thread++) {
			threads[thread] = new Thread(new Runnable() {
				public void run() {
					for (int z = ai.getAndIncrement(); z <= d; z = ai
							.getAndIncrement()) {
						IJ.showStatus("Generating short/middle axis image");
						IJ.showProgress(z, d);
						int[] idSlice = maxIDs[z];
						float[] pixels = stackPixels[z];
						double[] radii = new double[3];
						for (int y = 0; y < h; y++) {
							final int offset = y * w;
							for (int x = 0; x < w; x++) {
								final int i = offset + x;
								final int id = idSlice[i];
								if (id >= 0) {
									radii = ellipsoids[id].getSortedRadii();
									pixels[i] = (float) (radii[0] / radii[1]);
								} else
									pixels[i] = Float.NaN;
							}
						}
					}
				}
			});
		}
		Multithreader.startAndJoin(threads);

		for (int z = 1; z <= d; z++)
			smStack.addSlice("" + z, stackPixels[z]);

		ImagePlus shortmid = new ImagePlus("Short_Mid-" + imp.getTitle(),
				smStack);
		shortmid.setCalibration(imp.getCalibration());
		return shortmid;
	}

	private ImagePlus displayMiddleOverLong(ImagePlus imp,
			final int[][] maxIDs, final Ellipsoid[] ellipsoids) {
		final ImageStack stack = imp.getImageStack();
		final int w = stack.getWidth();
		final int h = stack.getHeight();
		final int d = stack.getSize();

		final ImageStack mlStack = new ImageStack(imp.getWidth(),
				imp.getHeight());

		final float[][] stackPixels = new float[d + 1][w * h];

		final AtomicInteger ai = new AtomicInteger(1);
		Thread[] threads = Multithreader.newThreads();
		for (int thread = 0; thread < threads.length; thread++) {
			threads[thread] = new Thread(new Runnable() {
				public void run() {
					for (int z = ai.getAndIncrement(); z <= d; z = ai
							.getAndIncrement()) {
						IJ.showStatus("Generating volume image");
						IJ.showProgress(z, d);
						int[] idSlice = maxIDs[z];
						float[] pixels = stackPixels[z];
						double[] radii = new double[3];
						for (int y = 0; y < h; y++) {
							final int offset = y * w;
							for (int x = 0; x < w; x++) {
								final int i = offset + x;
								final int id = idSlice[i];
								if (id >= 0) {
									radii = ellipsoids[id].getSortedRadii();
									pixels[i] = (float) (radii[1] / radii[2]);
								} else
									pixels[i] = Float.NaN;
							}
						}
					}
				}
			});
		}
		Multithreader.startAndJoin(threads);

		for (int z = 1; z <= d; z++)
			mlStack.addSlice("" + z, stackPixels[z]);

		ImagePlus midLong = new ImagePlus("Mid_Long-" + imp.getTitle(), mlStack);
		midLong.setCalibration(imp.getCalibration());
		return midLong;
	}

	private ImagePlus displayVolumes(ImagePlus imp, final int[][] maxIDs,
			final Ellipsoid[] ellipsoids) {
		final ImageStack stack = imp.getImageStack();
		final int w = stack.getWidth();
		final int h = stack.getHeight();
		final int d = stack.getSize();

		final ImageStack volStack = new ImageStack(imp.getWidth(),
				imp.getHeight());

		final float[][] stackPixels = new float[d + 1][w * h];

		final AtomicInteger ai = new AtomicInteger(1);
		Thread[] threads = Multithreader.newThreads();
		for (int thread = 0; thread < threads.length; thread++) {
			threads[thread] = new Thread(new Runnable() {
				public void run() {
					for (int z = ai.getAndIncrement(); z <= d; z = ai
							.getAndIncrement()) {
						IJ.showStatus("Generating volume image");
						IJ.showProgress(z, d);
						int[] idSlice = maxIDs[z];
						float[] pixels = stackPixels[z];
						for (int y = 0; y < h; y++) {
							final int offset = y * w;
							for (int x = 0; x < w; x++) {
								final int i = offset + x;
								final int id = idSlice[i];
								if (id >= 0) {
									pixels[i] = (float) ellipsoids[id]
											.getVolume();
								} else
									pixels[i] = Float.NaN;
							}
						}
					}
				}
			});
		}
		Multithreader.startAndJoin(threads);

		for (int z = 1; z <= d; z++)
			volStack.addSlice("" + z, stackPixels[z]);

		ImagePlus volImp = new ImagePlus("Volume-" + imp.getTitle(), volStack);
		volImp.setCalibration(imp.getCalibration());
		return volImp;
	}

	private ImagePlus displayMaximumIDs(int[][] biggestEllipsoid,
			Ellipsoid[] ellipsoids, ImagePlus imp) {

		ImageStack bigStack = new ImageStack(imp.getWidth(), imp.getHeight());
		for (int i = 1; i < biggestEllipsoid.length; i++) {
			int[] maxIDs = biggestEllipsoid[i];
			final int l = maxIDs.length;
			float[] pixels = new float[l];
			for (int j = 0; j < l; j++) {
				pixels[j] = (float) maxIDs[j];
			}
			bigStack.addSlice("" + i, pixels);
		}
		ImagePlus bigImp = new ImagePlus("Max-ID-" + imp.getTitle(), bigStack);
		bigImp.setCalibration(imp.getCalibration());
		return bigImp;
	}

	/**
	 * For each foreground pixel of the input image, find the ellipsoid of
	 * greatest volume
	 * 
	 * @param imp
	 * @param ellipsoids
	 * @return array containing the indexes of the biggest ellipsoids which
	 *         contain each point
	 */
	private int[][] findMaxID(ImagePlus imp, final Ellipsoid[] ellipsoids) {

		final ImageStack stack = imp.getImageStack();
		final int w = stack.getWidth();
		final int h = stack.getHeight();
		final int d = stack.getSize();

		Calibration cal = imp.getCalibration();
		final double vW = cal.pixelWidth;
		final double vH = cal.pixelHeight;
		final double vD = cal.pixelDepth;

		final int[][] biggest = new int[d + 1][w * h];

		final AtomicInteger ai = new AtomicInteger(1);
		Thread[] threads = Multithreader.newThreads();
		for (int thread = 0; thread < threads.length; thread++) {
			threads[thread] = new Thread(new Runnable() {
				public void run() {
					for (int z = ai.getAndIncrement(); z <= d; z = ai
							.getAndIncrement()) {
						IJ.showStatus("Finding biggest ellipsoid");
						IJ.showProgress(z, d);
						byte[] slicePixels = (byte[]) stack.getPixels(z);
						int[] bigSlice = biggest[z];
						Arrays.fill(bigSlice, -ellipsoids.length);
						final double zvD = z * vD;

						// find the subset of ellipsoids whose bounding box
						// intersects with z
						ArrayList<Ellipsoid> nearEllipsoids = new ArrayList<Ellipsoid>();
						final int n = ellipsoids.length;
						for (int i = 0; i < n; i++) {
							Ellipsoid e = ellipsoids[i];
							double[] zMinMax = e.getZMinAndMax();
							if (zvD >= zMinMax[0] && zvD <= zMinMax[1]) {
								Ellipsoid f = e.copy();
								f.id = i;
								nearEllipsoids.add(f);
							}
						}
						final int o = nearEllipsoids.size();
						Ellipsoid[] ellipsoidSubSet = new Ellipsoid[o];
						for (int i = 0; i < o; i++) {
							ellipsoidSubSet[i] = nearEllipsoids.get(i);
						}

						final int q = ellipsoidSubSet.length;
						for (int y = 0; y < h; y++) {
							final double yvH = y * vH;
							// find the subset of ellipsoids whose bounding box
							// intersects with y
							ArrayList<Ellipsoid> yEllipsoids = new ArrayList<Ellipsoid>();
							for (int i = 0; i < q; i++) {
								Ellipsoid e = ellipsoidSubSet[i];
								double[] yMinMax = e.getYMinAndMax();
								if (yvH >= yMinMax[0] && yvH <= yMinMax[1]) {
									yEllipsoids.add(e);
								}
							}

							final int r = yEllipsoids.size();
							Ellipsoid[] ellipsoidSubSubSet = new Ellipsoid[r];
							for (int i = 0; i < r; i++) {
								ellipsoidSubSubSet[i] = yEllipsoids.get(i);
							}

							final int offset = y * w;
							for (int x = 0; x < w; x++) {
								if (slicePixels[offset + x] == -1) {
									bigSlice[offset + x] = biggestEllipsoid(
											ellipsoidSubSubSet, x * vW, yvH,
											zvD);
								}
							}
						}

					}
				}
			});
		}
		Multithreader.startAndJoin(threads);
		return biggest;
	}

	/**
	 * Search the list of ellipsoids and return the index of the largest
	 * ellipsoid which contains the point x, y, z
	 * 
	 * @param ellipsoids
	 *            sorted in order of descending size and with id set to the sort
	 *            position of the whole set. This means that subsets may be
	 *            searched in sorted order and the ID which is returned is the
	 *            index of the ellipsoid in the full array of ellipsoids rather
	 *            than its index in the subset. The advantage is much faster
	 *            searching.
	 * @param x
	 * @param y
	 * @param z
	 * @return the index of the largest ellipsoid which contains this point, -1
	 *         if none of the ellipsoids contain the point
	 */
	private int biggestEllipsoid(Ellipsoid[] ellipsoids, double x, double y,
			double z) {
		final int l = ellipsoids.length;
		for (int i = 0; i < l; i++) {
			if (ellipsoids[i].contains(x, y, z))
				return ellipsoids[i].id;
		}
		return -1;
	}

	/**
	 * Using skeleton points as seeds, propagate along each vector until a
	 * boundary is hit. Use the resulting cloud of boundary points as input into
	 * an ellipsoid fit.
	 * 
	 * @param imp
	 * @param skeletonPoints
	 * @param unitVectors
	 * @return
	 */
	private Ellipsoid[] findEllipsoids(final ImagePlus imp,
			final int[][] skeletonPoints, final double[][] unitVectors) {
		final int nPoints = skeletonPoints.length;
		final Ellipsoid[] ellipsoids = new Ellipsoid[nPoints];

		// make sure array contains null in the non-calculated elements
		Arrays.fill(ellipsoids, null);

		final AtomicInteger ai = new AtomicInteger(0);
		final AtomicInteger counter = new AtomicInteger(0);
		Thread[] threads = Multithreader.newThreads();
		for (int thread = 0; thread < threads.length; thread++) {
			threads[thread] = new Thread(new Runnable() {
				public void run() {
					for (int i = ai.getAndAdd(skipRatio); i < nPoints; i = ai
							.getAndAdd(skipRatio)) {
						ellipsoids[i] = optimiseEllipsoid(imp,
								skeletonPoints[i], unitVectors, i);
						IJ.showProgress(counter.getAndAdd(skipRatio), nPoints);
					}
				}
			});
		}
		Multithreader.startAndJoin(threads);

		Ellipsoid[] sortedEllipsoids = ArrayHelper.removeNulls(ellipsoids);

		// Sort using this class' compare method
		Arrays.sort(sortedEllipsoids, this);

		return sortedEllipsoids;
	}

	/**
	 * given a seed point, find the ellipsoid which best fits the binarised
	 * structure
	 * 
	 * @param imp
	 * @param is
	 * @param unitVectors
	 * @return ellipsoid fitting the point cloud of boundaries lying at the end
	 *         of vectors surrounding the seed point. If ellipsoid fitting
	 *         fails, returns null
	 */
	private Ellipsoid optimiseEllipsoid(final ImagePlus imp,
			int[] skeletonPoint, double[][] unitVectors, final int index) {

		IJ.showStatus("Optimising ellipsoids...");

		long start = System.currentTimeMillis();

		Calibration cal = imp.getCalibration();
		final double pW = cal.pixelWidth;
		final double pH = cal.pixelHeight;
		final double pD = cal.pixelDepth;

		ImageStack stack = imp.getImageStack();
		// final int stackSize = stack.getSize();
		final int w = stack.getWidth();
		final int h = stack.getHeight();
		final int d = stack.getSize();

		// cache slices into an array
		// ByteProcessor[] ips = new ByteProcessor[stackSize + 1];
		byte[][] pixels = new byte[d][w * h];
		for (int i = 0; i < d; i++) {
			// ips[i] = (ByteProcessor) stack.getProcessor(i);
			pixels[i] = (byte[]) stack.getProcessor(i + 1).getPixels();
		}

		// final int d = ips.length - 1;

		// centre point of vector field
		final double px = skeletonPoint[0] * pW;
		final double py = skeletonPoint[1] * pH;
		final double pz = skeletonPoint[2] * pD;

		// Instantiate a small spherical ellipsoid
		final double[][] orthogonalVectors = { { 1, 0, 0 }, { 0, 1, 0 },
				{ 0, 0, 1 } };

		Ellipsoid ellipsoid = new Ellipsoid(vectorIncrement, vectorIncrement,
				vectorIncrement, px, py, pz, orthogonalVectors);

		Vector<Double> volumeHistory = new Vector<Double>();
		volumeHistory.add(ellipsoid.getVolume());

		// dilate the sphere until it hits the background
		while (isContained(ellipsoid, pixels, pW, pH, pD, w, h, d)) {
			ellipsoid.dilate(vectorIncrement, vectorIncrement, vectorIncrement);
		}

		volumeHistory.add(ellipsoid.getVolume());

		// instantiate the ArrayList
		ArrayList<double[]> contactPoints = new ArrayList<double[]>();

		// get the points of contact
		// contactPoints = findContactPoints(ellipsoid, contactPoints, pixels,
		// pW,
		// pH, pD, w, h, d);
		contactPoints = findEllipseContactPoints(ellipsoid, pixels, pW, pH, pD,
				w, h, d);

		if (isInvalid(ellipsoid, contactPoints)) {
			IJ.log("Ellipsoid at (" + px + ", " + py + ", " + pz
					+ ") is invalid, nullifying at initial dilation");
			return null;
		}

		// find the mean unit vector pointing to the points of contact from the
		// centre
		double[] shortAxis = contactPointsMeanUnitVector(ellipsoid,
				contactPoints);

		// find an orthogonal axis
		final double[] xAxis = { 1, 0, 0 };
		double[] middleAxis = Vectors.crossProduct(shortAxis, xAxis);
		middleAxis = Vectors.norm(middleAxis);

		// find a mutually orthogonal axis by forming the cross product
		double[] longAxis = Vectors.crossProduct(shortAxis, middleAxis);
		longAxis = Vectors.norm(longAxis);

		// construct a rotation matrix
		double[][] rotation = { shortAxis, middleAxis, longAxis };
		rotation = Ellipsoid.transpose(rotation);

		// rotate ellipsoid to point this way...
		ellipsoid.setRotation(rotation);

		// shrink the ellipsoid slightly
		ellipsoid.contract(0.1);

		// dilate other two axes until number of contact points increases
		// by contactSensitivity number of contacts

		// int maxContacts = contactPoints.size() + contactSensitivity;
		while (contactPoints.size() < contactSensitivity) {
			ellipsoid.dilate(0, vectorIncrement, vectorIncrement);
			// contactPoints = findContactPoints(ellipsoid, contactPoints,
			// pixels,
			// pW, pH, pD, w, h, d);
			contactPoints = findEllipseContactPoints(ellipsoid, pixels, pW, pH,
					pD, w, h, d);
			if (isInvalid(ellipsoid, contactPoints)) {
				IJ.log("Ellipsoid at (" + px + ", " + py + ", " + pz
						+ ") is invalid, nullifying at initial oblation");
				return null;
			}
		}

		volumeHistory.add(ellipsoid.getVolume());

		// until ellipsoid is totally jammed within the structure, go through
		// cycles of contraction, wiggling, dilation
		// goal is maximal inscribed ellipsoid, maximal being defined by volume

		// store a copy of the 'best ellipsoid so far'
		Ellipsoid maximal = ellipsoid.copy();

		// alternately try each axis
		int totalIterations = 0;
		int noImprovementCount = 0;
		final int absoluteMaxIterations = maxIterations * 10;
		
		try{
		while (totalIterations < absoluteMaxIterations
				&& noImprovementCount < maxIterations) {

			// rotate a little bit
			ellipsoid = wiggle(ellipsoid);

			// contract until no contact
			ellipsoid = shrinkToFit(ellipsoid, contactPoints, pixels, pW, pH,
					pD, w, h, d);

			// dilate an axis
			double[] abc = threeWayShuffle();
			ellipsoid = inflateToFit(ellipsoid, contactPoints, abc[0], abc[1],
					abc[2], pixels, pW, pH, pD, w, h, d, px, py, pz);

			if (isInvalid(ellipsoid, contactPoints)) {
				IJ.log("Ellipsoid at (" + px + ", " + py + ", " + pz
						+ ") is invalid, nullifying after " + totalIterations
						+ " iterations");
				return null;
			}

			if (ellipsoid.getVolume() > maximal.getVolume())
				maximal = ellipsoid.copy();

			// bump a little away from the sides
			// contactPoints = findContactPoints(ellipsoid, contactPoints,
			// pixels,
			// pW, pH, pD, w, h, d);
			contactPoints = findEllipseContactPoints(ellipsoid, pixels, pW, pH,
					pD, w, h, d);
			if (contactPoints.size() > 0)
				ellipsoid = bump(ellipsoid, contactPoints, px, py, pz);
			// if can't bump then do a wiggle
			else
				ellipsoid = wiggle(ellipsoid);

			// contract
			ellipsoid = shrinkToFit(ellipsoid, contactPoints, pixels, pW, pH,
					pD, w, h, d);

			// dilate an axis
			abc = threeWayShuffle();
			ellipsoid = inflateToFit(ellipsoid, contactPoints, abc[0], abc[1],
					abc[2], pixels, pW, pH, pD, w, h, d, px, py, pz);

			if (isInvalid(ellipsoid, contactPoints)) {
				IJ.log("Ellipsoid at (" + px + ", " + py + ", " + pz
						+ ") is invalid, nullifying after " + totalIterations
						+ " iterations");
				return null;
			}

			if (ellipsoid.getVolume() > maximal.getVolume())
				maximal = ellipsoid.copy();

			// rotate a little bit
			ellipsoid = turn(ellipsoid, contactPoints, 0.1, pixels, pW, pH, pD,
					w, h, d);

			// contract until no contact
			ellipsoid = shrinkToFit(ellipsoid, contactPoints, pixels, pW, pH,
					pD, w, h, d);

			// dilate an axis
			abc = threeWayShuffle();
			ellipsoid = inflateToFit(ellipsoid, contactPoints, abc[0], abc[1],
					abc[2], pixels, pW, pH, pD, w, h, d, px, py, pz);

			if (isInvalid(ellipsoid, contactPoints)) {
				IJ.log("Ellipsoid at (" + px + ", " + py + ", " + pz
						+ ") is invalid, nullifying after " + totalIterations
						+ " iterations");
				return null;
			}

			if (ellipsoid.getVolume() > maximal.getVolume())
				maximal = ellipsoid.copy();

			// keep the maximal ellipsoid found
			ellipsoid = maximal.copy();
			// log its volume
			volumeHistory.add(ellipsoid.getVolume());

			// if the last value is bigger than the second-to-last value
			// reset the noImprovementCount
			// otherwise, increment it by 1.
			// if noImprovementCount exceeds a preset value the while() is
			// broken
			final int i = volumeHistory.size() - 1;
			if (volumeHistory.get(i) > volumeHistory.get(i - 1))
				noImprovementCount = 0;
			else
				noImprovementCount++;

			totalIterations++;
		}}catch (NullPointerException npe){
			IJ.log("Ellipsoid at (" + px + ", " + py + ", " + pz
					+ ") is invalid, nullifying after " + totalIterations
					+ " iterations");
			return null;
		}

		// this usually indicates that the ellipsoid
		// grew out of control for some reason
		if (totalIterations == absoluteMaxIterations) {
			IJ.log("Ellipsoid at (" + px + ", " + py + ", " + pz
					+ ") seems to be out of control, nullifying after "
					+ totalIterations + " iterations");
			return null;
		}
		// debug output for this ellipsoid
		if (IJ.debugMode) {
			// show in the 3D viewer
			display3D(ellipsoid, contactPoints, pixels, pW, pH, pD, w, h, d,
					px, py, pz);

			// add history to the ResultsTable
			// for (int i = 0; i < volumeHistory.size(); i++) {
			// rt.setValue("" + index, i, volumeHistory.get(i));
			// }
		}

		long stop = System.currentTimeMillis();

		IJ.log("Optimised ellipsoid in " + (stop - start) + " ms after "
				+ totalIterations + " iterations ("
				+ IJ.d2s((double) (stop - start) / totalIterations, 3)
				+ " ms/iteration)");

		return ellipsoid;
	}

	private String name(Ellipsoid ellipsoid) {
		double[] c = ellipsoid.getCentre();
		String name = c[0] + " " + c[1] + " " + c[2];
		return name;
	}

	private double[] threeWayShuffle() {
		double[] a = { 0, 0, 0 };
		double rand = Math.random();
		if (rand < 1.0 / 3.0)
			a[0] = 1;
		else if (rand >= 2.0 / 3.0)
			a[2] = 1;
		else
			a[1] = 1;
		return a;
	}

	/**
	 * Check whether this ellipsoid is sensible
	 * 
	 * @param ellipsoid
	 * @param contactPoints
	 * @return true if half or more of the surface points are outside the image
	 *         stack (contactPoints will be null), or if the volume of the
	 *         ellipsoid exceeds that of the image stack
	 */
	private boolean isInvalid(Ellipsoid ellipsoid,
			ArrayList<double[]> contactPoints) {

		// double[][] surfacePoints = ellipsoid.getSurfacePoints(nVectors);
		// int outOfBoundsCount = 0;
		// final int half = nVectors / 2;
		// for (double[] p : surfacePoints) {
		// if (isOutOfBounds((int) (p[0] / pW), (int) (p[1] / pD),
		// (int) (p[2] / pH), w, h, d))
		// outOfBoundsCount++;
		// if (outOfBoundsCount > half)
		// return true;
		// }

		if (ellipsoid == null || contactPoints == null)
			return true;

		double volume = ellipsoid.getVolume();
		if (volume > stackVolume)
			return true;

		return false;
	}

	/**
	 * Display an ellipsoid in the 3D viewer
	 * 
	 * @param ellipsoid
	 * @param ips
	 * @param pW
	 * @param pH
	 * @param pD
	 * @param w
	 * @param h
	 * @param d
	 * @param px
	 * @param py
	 * @param pz
	 */
	private void display3D(Ellipsoid ellipsoid,
			ArrayList<double[]> contactPoints, byte[][] pixels, double pW,
			double pH, double pD, int w, int h, int d, double px, double py,
			double pz) {
		// contactPoints = findContactPoints(ellipsoid, contactPoints, pixels,
		// pW,
		// pH, pD, w, h, d);

		contactPoints = findEllipseContactPoints(ellipsoid, pixels, pW, pH, pD,
				w, h, d);

		ArrayList<Point3f> contactPointsf = new ArrayList<Point3f>(
				contactPoints.size());
		for (double[] p : contactPoints) {
			Point3f point = new Point3f((float) p[0], (float) p[1],
					(float) p[2]);
			contactPointsf.add(point);
		}
		double[][] pointCloud = ellipsoid.getSurfacePoints(100);

		List<Point3f> pointList = new ArrayList<Point3f>();
		for (int p = 0; p < pointCloud.length; p++) {
			if (pointCloud[p] == null)
				continue;
			Point3f e = new Point3f();
			e.x = (float) pointCloud[p][0];
			e.y = (float) pointCloud[p][1];
			e.z = (float) pointCloud[p][2];
			pointList.add(e);
		}
		CustomPointMesh mesh = new CustomPointMesh(pointList);
		mesh.setPointSize(2.0f);
		Color3f cColour = new Color3f((float) (px / pW) / w, (float) (py / pH)
				/ h, (float) (pz / pD) / d);
		mesh.setColor(cColour);

		CustomPointMesh contactPointMesh = new CustomPointMesh(contactPointsf);
		contactPointMesh.setPointSize(2.5f);
		Color3f invColour = new Color3f(1 - cColour.x, 1 - cColour.y,
				1 - cColour.z);
		contactPointMesh.setColor(invColour);

		final double[] torque = calculateTorque(ellipsoid, contactPoints);
		final double[] c = ellipsoid.getCentre();

		List<Point3f> torqueList = new ArrayList<Point3f>();
		torqueList.add(new Point3f((float) c[0], (float) c[1], (float) c[2]));
		torqueList.add(new Point3f((float) (torque[0] + c[0]),
				(float) (torque[1] + c[1]), (float) (torque[2] + c[2])));
		CustomLineMesh torqueLine = new CustomLineMesh(torqueList);
		Color3f blue = new Color3f((float) 0.0, (float) 0.0, (float) 1.0);
		torqueLine.setColor(blue);

		// Axis-aligned bounding box
		double[] box = ellipsoid.getAxisAlignedBoundingBox();
		float[] b = { (float) box[0], (float) box[1], (float) box[2],
				(float) box[3], (float) box[4], (float) box[5] };
		List<Point3f> aabb = new ArrayList<Point3f>();
		aabb.add(new Point3f(b[0], b[2], b[4]));
		aabb.add(new Point3f(b[1], b[2], b[4]));

		aabb.add(new Point3f(b[0], b[2], b[4]));
		aabb.add(new Point3f(b[0], b[3], b[4]));

		aabb.add(new Point3f(b[0], b[2], b[4]));
		aabb.add(new Point3f(b[0], b[2], b[5]));

		aabb.add(new Point3f(b[0], b[3], b[4]));
		aabb.add(new Point3f(b[0], b[3], b[5]));

		aabb.add(new Point3f(b[0], b[3], b[5]));
		aabb.add(new Point3f(b[0], b[2], b[5]));

		aabb.add(new Point3f(b[0], b[3], b[4]));
		aabb.add(new Point3f(b[1], b[3], b[4]));

		aabb.add(new Point3f(b[1], b[3], b[4]));
		aabb.add(new Point3f(b[1], b[3], b[5]));

		aabb.add(new Point3f(b[0], b[3], b[5]));
		aabb.add(new Point3f(b[1], b[3], b[5]));

		aabb.add(new Point3f(b[0], b[2], b[5]));
		aabb.add(new Point3f(b[1], b[2], b[5]));

		aabb.add(new Point3f(b[1], b[2], b[4]));
		aabb.add(new Point3f(b[1], b[3], b[4]));

		aabb.add(new Point3f(b[1], b[2], b[4]));
		aabb.add(new Point3f(b[1], b[2], b[5]));

		aabb.add(new Point3f(b[1], b[2], b[5]));
		aabb.add(new Point3f(b[1], b[3], b[5]));

		// ellipses

		List<Point3f> points3D = new ArrayList<Point3f>();

		ArrayList<double[]> points = findEllipseContactPoints(ellipsoid,
				pixels, pW, pH, pD, w, h, d);

		CustomPointMesh ellipseMesh = null;
		if (points != null) {
			for (double[] p : points) {
				points3D.add(new Point3f((float) p[0], (float) p[1],
						(float) p[2]));
			}

			ellipseMesh = new CustomPointMesh(points3D);
		}

		final String name = name(ellipsoid);

		try {
			universe.addCustomMesh(mesh, "Point cloud " + name).setLocked(true);
			universe.addCustomMesh(contactPointMesh,
					"Contact points of " + name).setLocked(true);
			universe.addCustomMesh(torqueLine, "Torque of " + name).setLocked(
					true);
			universe.addLineMesh(aabb, new Color3f(1.0f, 0.0f, 0.0f),
					"AABB of " + name, false).setLocked(true);
			if (ellipseMesh != null)
				universe.addCustomMesh(ellipseMesh, "Ellipses of " + name)
						.setLocked(true);
			// universe.addLineMesh(points3D, new Color3f(1.0f, 1.0f, 0.0f),
			// "Ellipse regions of " + name, true).setLocked(true);

		} catch (Exception e) {
			IJ.log("Something went wrong adding meshes to 3D viewer:\n"
					+ e.getMessage());
		}
	}

	/**
	 * Rotate the ellipsoid theta radians around the unit vector formed by the
	 * sum of torques effected by unit normals acting on the surface of the
	 * ellipsoid
	 * 
	 * @param ellipsoid
	 * @param theta
	 * @param ips
	 * @param pW
	 * @param pH
	 * @param pD
	 * @param w
	 * @param h
	 * @param d
	 * @return
	 */
	private Ellipsoid turn(Ellipsoid ellipsoid,
			ArrayList<double[]> contactPoints, double theta, byte[][] pixels,
			double pW, double pH, double pD, int w, int h, int d) {

		// contactPoints = findContactPoints(ellipsoid, contactPoints, pixels,
		// pW,
		// pH, pD, w, h, d);

		contactPoints = findEllipseContactPoints(ellipsoid, pixels, pW, pH, pD,
				w, h, d);

		if (contactPoints == null)
			return null;

		if (contactPoints.size() > 0) {
			double[] torque = calculateTorque(ellipsoid, contactPoints);
			ellipsoid = rotateAboutAxis(ellipsoid, Vectors.norm(torque), theta);
		}
		return ellipsoid;
	}

	/**
	 * Calculate the mean unit vector between the ellipsoid's centroid and
	 * contact points
	 * 
	 * @param ellipsoid
	 * @param contactPoints
	 * @return
	 */
	private double[] contactPointsMeanUnitVector(Ellipsoid ellipsoid,
			ArrayList<double[]> contactPoints) {

		final int nPoints = contactPoints.size();

		if (nPoints < 1)
			throw new IllegalArgumentException(
					"Need at least one contact point");

		double xSum = 0;
		double ySum = 0;
		double zSum = 0;

		final double[] c = ellipsoid.getCentre();
		final double cx = c[0];
		final double cy = c[1];
		final double cz = c[2];

		double[] p = new double[3];
		for (int i = 0; i < nPoints; i++) {
			p = contactPoints.get(i);

			final double pxcx = p[0] - cx;
			final double pycy = p[1] - cy;
			final double pzcz = p[2] - cz;

			final double l = Trig.distance3D(pxcx, pycy, pzcz);

			xSum += pxcx / l;
			ySum += pycy / l;
			zSum += pzcz / l;
		}

		final double xMean = xSum / nPoints;
		final double yMean = ySum / nPoints;
		final double zMean = zSum / nPoints;

		final double l = Trig.distance3D(xMean, yMean, zMean);

		double[] unitVector = { xMean / l, yMean / l, zMean / l };
		return unitVector;
	}

	/**
	 * Calculate the torque of unit normals acting at the contact points
	 * 
	 * @param ellipsoid
	 * @param contactPoints
	 * @return
	 */
	private double[] calculateTorque(Ellipsoid ellipsoid,
			ArrayList<double[]> contactPoints) {

		final double[] pc = ellipsoid.getCentre();
		final double cx = pc[0];
		final double cy = pc[1];
		final double cz = pc[2];

		final double[] r = ellipsoid.getRadii();
		final double a = r[0];
		final double b = r[1];
		final double c = r[2];

		final double s = 2 / (a * a);
		final double t = 2 / (b * b);
		final double u = 2 / (c * c);

		final double[][] rot = ellipsoid.getRotation();
		final double[][] inv = (new Matrix(rot)).inverse().getArrayCopy();

		double t0 = 0;
		double t1 = 0;
		double t2 = 0;

		for (double[] p : contactPoints) {
			// translate point to centre on origin
			final double px = p[0] - cx;
			final double py = p[1] - cy;
			final double pz = p[2] - cz;

			// derotate the point
			final double x = inv[0][0] * px + inv[0][1] * py + inv[0][2] * pz;
			final double y = inv[1][0] * px + inv[1][1] * py + inv[1][2] * pz;
			final double z = inv[2][0] * px + inv[2][1] * py + inv[2][2] * pz;

			// calculate the unit normal on the centred and derotated ellipsoid
			final double nx = s * x;
			final double ny = t * y;
			final double nz = u * z;
			final double length = Trig.distance3D(nx, ny, nz);
			final double unx = nx / length;
			final double uny = ny / length;
			final double unz = nz / length;

			// rotate the normal back to the original ellipsoid
			final double ex = rot[0][0] * unx + rot[0][1] * uny + rot[0][2]
					* unz;
			final double ey = rot[1][0] * unx + rot[1][1] * uny + rot[1][2]
					* unz;
			final double ez = rot[2][0] * unx + rot[2][1] * uny + rot[2][2]
					* unz;

			final double[] torqueVector = Vectors.crossProduct(px, py, pz, ex,
					ey, ez);

			t0 += torqueVector[0];
			t1 += torqueVector[1];
			t2 += torqueVector[2];

		}
		double[] torque = { -t0, -t1, -t2 };
		return torque;
	}

	/**
	 * Rotate the ellipsoid theta radians around an arbitrary unit vector
	 * 
	 * @param ellipsoid
	 * @param axis
	 * @param theta
	 * @see http://en.wikipedia.org/wiki/Rotation_matrix#
	 *      Rotation_matrix_from_axis_and_angle
	 * @return
	 */
	private Ellipsoid rotateAboutAxis(Ellipsoid ellipsoid, double[] axis,
			final double theta) {

		final double sin = Math.sin(theta);
		final double cos = Math.cos(theta);
		final double cos1 = 1 - cos;
		final double x = axis[0];
		final double y = axis[1];
		final double z = axis[2];
		final double xy = x * y;
		final double xz = x * z;
		final double yz = y * z;
		final double xsin = x * sin;
		final double ysin = y * sin;
		final double zsin = z * sin;
		final double xycos1 = xy * cos1;
		final double xzcos1 = xz * cos1;
		final double yzcos1 = yz * cos1;
		double[][] rotation = {
				{ cos + x * x * cos1, xycos1 - zsin, xzcos1 + ysin },
				{ xycos1 + zsin, cos + y * y * cos1, yzcos1 - xsin },
				{ xzcos1 - ysin, yzcos1 + xsin, cos + z * z * cos1 }, };

		ellipsoid.rotate(rotation);

		return ellipsoid;
	}

	/**
	 * 
	 * @param ellipsoid
	 * @param ips
	 * @param pW
	 * @param pH
	 * @param pD
	 * @param w
	 * @param h
	 * @param d
	 * @return
	 */
	private Ellipsoid shrinkToFit(Ellipsoid ellipsoid,
			ArrayList<double[]> contactPoints, byte[][] pixels, double pW,
			double pH, double pD, int w, int h, int d) {

		// get the contact points
		// contactPoints = findContactPoints(ellipsoid, contactPoints, pixels,
		// pW,
		// pH, pD, w, h, d);
		contactPoints = findEllipseContactPoints(ellipsoid, pixels, pW, pH, pD,
				w, h, d);

		if (contactPoints == null)
			return null;

		// get the unit vectors to the contact points
		double[][] unitVectors = findContactUnitVectors(ellipsoid,
				contactPoints);

		// contract until no contact
		int safety = 0;
		while (contactPoints.size() > 0 && safety <= maxIterations) {
			ellipsoid.scale(0.99);
			contactPoints = findContactPoints(ellipsoid, contactPoints,
					unitVectors, pixels, pW, pH, pD, w, h, d);
			safety++;
		}

		ellipsoid.scale(0.95);

		return ellipsoid;
	}

	private double[][] findContactUnitVectors(Ellipsoid ellipsoid,
			ArrayList<double[]> contactPoints) {
		double[][] unitVectors = new double[contactPoints.size()][3];
		final double[] c = ellipsoid.getCentre();
		final double cx = c[0];
		final double cy = c[1];
		final double cz = c[2];

		double[] p = new double[3];
		for (int i = 0; i < contactPoints.size(); i++) {
			p = contactPoints.get(i);
			final double pxcx = p[0] - cx;
			final double pycy = p[1] - cy;
			final double pzcz = p[2] - cz;

			final double l = Trig.distance3D(pxcx, pycy, pzcz);
			final double x = pxcx / l;
			final double y = pycy / l;
			final double z = pzcz / l;
			double[] u = { x, y, z };
			unitVectors[i] = u;
		}
		return unitVectors;
	}

	/**
	 * 
	 * @param ellipsoid
	 * @param a
	 * @param b
	 * @param c
	 * @param ips
	 * @param pW
	 * @param pH
	 * @param pD
	 * @param w
	 * @param h
	 * @param d
	 * @param pz
	 * @param py
	 * @param px
	 * @return
	 */
	private Ellipsoid inflateToFit(Ellipsoid ellipsoid,
			ArrayList<double[]> contactPoints, double a, double b, double c,
			byte[][] pixels, double pW, double pH, double pD, int w, int h,
			int d, double px, double py, double pz) {

		// contactPoints = findContactPoints(ellipsoid, contactPoints, pixels,
		// pW,
		// pH, pD, w, h, d);

		contactPoints = findEllipseContactPoints(ellipsoid, pixels, pW, pH, pD,
				w, h, d);

		if (contactPoints == null)
			return null;

		final double av = a * vectorIncrement;
		final double bv = b * vectorIncrement;
		final double cv = c * vectorIncrement;

		int safety = 0;
		while (contactPoints.size() < contactSensitivity
				&& safety < maxIterations) {
			ellipsoid.dilate(av, bv, cv);
			// contactPoints = findContactPoints(ellipsoid, contactPoints,
			// pixels,
			// pW, pH, pD, w, h, d);
			contactPoints = findEllipseContactPoints(ellipsoid, pixels, pW, pH,
					pD, w, h, d);
			if (contactPoints == null)
				return null;
			safety++;
		}

		return ellipsoid;
	}

	/**
	 * 
	 * @param ellipsoid
	 * @param contactPoints
	 * @param px
	 * @param py
	 * @param pz
	 * @return
	 */
	private Ellipsoid bump(Ellipsoid ellipsoid,
			ArrayList<double[]> contactPoints, double px, double py, double pz) {

		final double displacement = vectorIncrement / 2;

		final double[] c = ellipsoid.getCentre();
		final double[] vector = contactPointsMeanUnitVector(ellipsoid,
				contactPoints);
		final double x = c[0] + vector[0] * displacement;
		final double y = c[1] + vector[1] * displacement;
		final double z = c[2] + vector[2] * displacement;

		if (Trig.distance3D(px, py, pz, x, y, z) < maxDrift)
			ellipsoid.setCentroid(x, y, z);

		return ellipsoid;
	}

	/**
	 * Rotate the ellipsoid by a small random amount
	 * 
	 * @param ellipsoid
	 */
	private Ellipsoid wiggle(Ellipsoid ellipsoid) {

		double b = nudge(0.1);
		double c = nudge(0.1);
		double a = Math.sqrt(1 - b * b - c * c);

		// zeroth column, should be very close to [1, 0, 0]^T (mostly x)
		double[] zerothColumn = { a, b, c };

		// form triangle in random plane
		double[] vector = Vectors.randomVectors(1)[0];

		// first column, should be very close to [0, 1, 0]^T
		double[] firstColumn = Vectors.norm(Vectors.crossProduct(zerothColumn,
				vector));

		// second column, should be very close to [0, 0, 1]^T
		double[] secondColumn = Vectors.norm(Vectors.crossProduct(zerothColumn,
				firstColumn));

		double[][] rotation = { zerothColumn, firstColumn, secondColumn };

		// array has subarrays as rows, need them as columns
		rotation = Ellipsoid.transpose(rotation);

		ellipsoid.rotate(rotation);

		return ellipsoid;
	}

	/**
	 * generate a random number between -a and +a
	 * 
	 * @param a
	 * @return
	 */
	private double nudge(double a) {
		return Math.random() * (a + a) - a;
	}

	// private ArrayList<double[]> findContactPoints(Ellipsoid ellipsoid,
	// ArrayList<double[]> contactPoints, byte[][] pixels,
	// final double pW, final double pH, final double pD, final int w,
	// final int h, final int d) {
	// final double[][] unitVectors = Vectors.regularVectors(nVectors);
	// return findContactPoints(ellipsoid, contactPoints, unitVectors, pixels,
	// pW, pH, pD, w, h, d);
	// }

	private ArrayList<double[]> findContactPoints(Ellipsoid ellipsoid,
			ArrayList<double[]> contactPoints, double[][] unitVectors,
			byte[][] pixels, double pW, double pH, double pD, int w, int h,
			int d) {
		contactPoints.clear();
		double[][] points = ellipsoid.getSurfacePoints(unitVectors);
		final int nPoints = points.length;
		double[] p = new double[3];
		for (int i = 0; i < nPoints; i++) {
			p = points[i];
			final int x = (int) Math.floor(p[0] / pW);
			final int y = (int) Math.floor(p[1] / pH);
			final int z = (int) Math.floor(p[2] / pD);
			if (isOutOfBounds(x, y, z, w, h, d))
				continue;
			if (pixels[z][y * w + x] != -1)
				contactPoints.add(p);
		}
		return contactPoints;
	}

	/**
	 * Return the 6 variables a - g of the standard ellipse formed by the
	 * intersection of an ellipsoid and the xy plane at z, found simply by
	 * substituting the value for z into the ellipsoid equation. Does not check
	 * if the plane intersects the ellipsoid: use Ellipsoid.getZMinAndMax()
	 * prior to calling this method.
	 * 
	 * <p>
	 * <i>ax</i><sup>2</sup> + 2<i>bxy</i> + <i>cy</i><sup>2</sup> + 2<i>dx</i>
	 * + 2<i>fy</i> + <i>g</i> = 0
	 * </p>
	 * 
	 * @param e
	 *            Ellipsoid equation variables
	 * @param z
	 *            position of xy plane
	 * @return 6 element double with variables in order a, b, c, d, f, g.
	 */
	private double[] getEllipseAtZ(double[] e, double z) {
		final double ea = e[0];
		final double eb = e[1];
		final double ec = e[2];
		final double ed = e[3];
		final double ef = e[4];
		final double eg = e[5];
		final double eh = e[6];
		final double ej = e[7];
		final double ek = e[8];

		double a = ea;
		double b = ed;
		double c = eb;
		double d = z * ef + eh;
		double f = z * eg + ej;
		double g = z * (2 * ek + ec * z) - 1;

		double[] ellipse = { a, b, c, d, f, g };

		return ellipse;
	}

	private ArrayList<double[]> findEllipseContactPoints(Ellipsoid ellipsoid,
			byte[][] pixels, double pW, double pH, double pD, int sw, int sh,
			int sd) {

		final double invP = 1 / pW;
		Ellipsoid scaledEllipsoid = ellipsoid.copy();
		// put ellipsoid into pixel scale
		scaledEllipsoid.scale(invP);
		double[] centre = ellipsoid.getCentre();
		scaledEllipsoid.setCentroid(centre[0] * invP, centre[1] * invP,
				centre[2] * invP);

		double[] zLimits = scaledEllipsoid.getZMinAndMax();
		double zMin = zLimits[0];
		final double zInc = pD / pW;
		zMin = zInc * (Math.floor(zMin / zInc) + 1);
		// put zMin into integer terms for array lookup
		final double zMax = zLimits[1];
		final double[] eEq = scaledEllipsoid.getEquation();

		final double ea = eEq[0];
		final double eb = eEq[1];
		final double ec = eEq[2];
		final double ed = eEq[3];
		final double ef = eEq[4];
		final double eg = eEq[5];
		final double eh = eEq[6];
		final double ej = eEq[7];
		final double ek = eEq[8];

		if (IJ.debugMode) {
			debugLog.incrementCounter();
			debugLog.addLabel(name(ellipsoid));
			debugLog.addValue("x²", eEq[0]);
			debugLog.addValue("y²", eEq[1]);
			debugLog.addValue("z²", eEq[2]);
			debugLog.addValue("2xy", eEq[3]);
			debugLog.addValue("2xz", eEq[4]);
			debugLog.addValue("2yz", eEq[5]);
			debugLog.addValue("2x", eEq[6]);
			debugLog.addValue("2y", eEq[7]);
			debugLog.addValue("2z", eEq[8]);
		}

		final double a = ea;
		final double b = ed;
		final double c = eb;

		final double b2ac = b * b - a * c;

		final double A = -a;
		final double B = -b * 2; // da Silva uses the doubled value
		final double C = -c;

		final double A2 = A + A;
		final double B2 = B + B;
		final double C2 = C + C;
		final double B_2 = B / 2;

		final double k1 = -B / C2;
		final double k2 = -B / A2;
		final double k3 = (A2 - B) / (C2 - B);
		final double k4 = (-A2 - B) / (C2 + B);

		final double abck1 = A + B * k1 + C * k1 * k1;
		final double abck2 = A * k2 * k2 + B * k2 + C;
		final double abck3 = A + B * k3 + C * k3 * k3;
		final double abck4 = A + B * k4 + C * k4 * k4;

		double Fn_n = C2;
		double Fw_w = A2;
		double Fs_s = C2;
		double Fn_nw = C2 - B;
		double Fnw_n = Fn_nw;
		double Fw_nw = A2 - B;
		double Fnw_w = Fw_nw;
		double Fw_sw = A2 + B;
		double Fsw_w = Fw_sw;
		double Fnw_nw = A2 - B2 + C2;
		double Fsw_sw = A2 + B2 + C2;
		double Fs_sw = C2 + B;
		double Fsw_s = Fs_sw;

		final double cross1 = B - A;
		final double cross2 = A - B + C;
		final double cross3 = A + B + C;
		final double cross4 = A + B;

		ArrayList<double[]> contactPoints = new ArrayList<double[]>();

		int nPoints = 0;
		int nOOBPoints = 0;
		boolean inStackZLimits = true;

		// z has to be stepped in xy plane pixel units
		for (double z = zMin; z <= zMax; z += zInc) {

			int zInt = (int) Math.floor(z / zInc);

			byte[] slice = null;
			// we haven't got into the image stack yet
			// or we are beyond the end of the available pixels
			if (zInt < 0 || zInt >= sd)
				inStackZLimits = false;
			else {
				inStackZLimits = true;
				slice = pixels[zInt];
			}

			final double d = z * ef + eh;
			final double f = z * eg + ej;
			double g = z * (2 * ek + ec * z) - 1;

			final double x0 = (c * d - b * f) / b2ac;
			final double y0 = (a * f - b * d) / b2ac;

			// translate g (d and f = 0 and a, b , c stay the same)
			g += a * x0 * x0 + 2 * b * x0 * y0 + c * y0 * y0 + 2 * d * x0 + 2
					* f * y0;

			// use variable names same as Da Silva (1989)
			// http://cs.brown.edu/research/pubs/theses/masters/1989/dasilva.pdf
			final double D = -g;

			double Xv = Math.sqrt(-D / abck1);
			if (Double.isNaN(Xv))
				Xv = 0;
			final double Yv = k1 * Xv;

			double Yh = Math.sqrt(-D / abck2);
			if (Double.isNaN(Yh))
				Yh = 0;
			final double Xh = k2 * Yh;

			// Xr always positive (to right of origin)
			double Xr = Math.sqrt(-D / abck3);
			if (Double.isNaN(Xr))
				Xr = 0;
			double Yr = k3 * Xr;
			if (Yr < 0)
				Yr = -Yr;

			// Xl always negative (to left of origin)
			double Xl = -Math.sqrt(-D / abck4);
			if (Double.isNaN(Xl))
				Xl = 0;
			double Yl = k4 * Xl;
			if (Yl < 0)
				Yl *= -1;

			if (IJ.debugMode) {
				criticalPoints.incrementCounter();
				criticalPoints.addLabel(name(ellipsoid));
				criticalPoints.addValue("Xv", Xv);
				criticalPoints.addValue("Yv", Yv);
				criticalPoints.addValue("Xr", Xr);
				criticalPoints.addValue("Yr", Yr);
				criticalPoints.addValue("Xh", Xh);
				criticalPoints.addValue("Yh", Yh);
				criticalPoints.addValue("Xl", Xl);
				criticalPoints.addValue("Yl", Yl);
			}

			final double XV = Math.round(Xv);
			final double YV = Math.round(Yv);
			final double YR = Math.round(Yr);
			final double XH = Math.round(Xh);
			final double XL = Math.round(Xl);

			// starting position
			double x = XV;
			double y = YV;

			final double Xinit = x - 0.5;
			final double Yinit = y + 1;

			double Fn = C2 * Yinit + B * Xinit + C;
			double Fnw = Fn - A2 * Xinit - B * Yinit + (A - B);
			double d1 = (A * Xinit * Xinit) + (B * Xinit * Yinit)
					+ (C * Yinit * Yinit) + D;

			// region 1
			while (y < YR) {

				nOOBPoints += updateContactPixelList(contactPoints,
						inStackZLimits, x, y, z, x0, y0, pW, sw, sh, slice);

				y += 1;
				nPoints += 2;
				if (d1 < 0 || (Fn - Fnw) < cross1) {
					d1 += Fn;
					Fn += Fn_n;
					Fnw += Fnw_n;
				} else {
					x -= 1;
					d1 += Fnw;
					Fn += Fn_nw;
					Fnw += Fnw_nw;
				}
			}

			double Fw = Fnw - Fn + A + B + B_2;
			Fnw += (A - C);
			double d2 = d1 + ((Fw - Fn + C) / 2 + (A + C) / 4 - A);

			// region2
			while (x > XH) {

				nOOBPoints += updateContactPixelList(contactPoints,
						inStackZLimits, x, y, z, x0, y0, pW, sw, sh, slice);

				x -= 1;
				nPoints += 2;
				if (d2 < 0 || (Fnw - Fw) < cross2) {
					y += 1;
					d2 += Fnw;
					Fw += Fw_nw;
					Fnw += Fnw_nw;
				} else {
					d2 += Fw;
					Fw += Fw_w;
					Fnw += Fnw_w;
				}
			}

			double d3 = d2 + Fw - Fnw + C2 - B;
			Fw += B;
			double Fsw = Fw - Fnw + Fw + C2 + C2 - B;

			// region 3
			while (x > XL) {

				nOOBPoints += updateContactPixelList(contactPoints,
						inStackZLimits, x, y, z, x0, y0, pW, sw, sh, slice);

				x -= 1;
				nPoints += 2;
				if (d3 < 0 || Fsw - Fw > cross3) {
					d3 += Fw;
					Fw += Fw_w;
					Fsw += Fsw_w;
				} else {
					y -= 1;
					d3 += Fsw;
					Fw += Fw_sw;
					Fsw += Fsw_sw;
				}
			}

			double Fs = Fsw - Fw - B;
			double d4 = d3 - Fsw / 2 + Fs + A - (A + C - B) / 4;
			Fsw += C - A;
			Fs += C - B_2;
			final double minusYV = -YV;

			// region 4
			while (y > minusYV) {

				nOOBPoints += updateContactPixelList(contactPoints,
						inStackZLimits, x, y, z, x0, y0, pW, sw, sh, slice);

				y -= 1;
				nPoints += 2;
				if (d4 < 0 || Fsw - Fs < cross4) {
					x -= 1;
					d4 += Fsw;
					Fs += Fs_sw;
					Fsw += Fsw_sw;
				} else {
					d4 += Fs;
					Fs += Fs_s;
					Fsw += Fsw_s;
				}
			}

			nOOBPoints += updateContactPixelList(contactPoints, inStackZLimits,
					x, y, z, x0, y0, pW, sw, sh, slice);

			nPoints += 2;
		}

		if (nPoints == 0)
			return contactPoints;

		if (nOOBPoints / nPoints > 0.5)
			return null;

		return contactPoints;
	}

	/**
	 * Check pixels at (x,y) and (-x, -y) and add it to the list of contact
	 * points if it is background. If it is out of bounds add it to the out of
	 * bounds counter
	 * 
	 * @param contactPoints
	 *            list of contact points, added if point is background and not
	 *            out of bounds
	 * @param inStackZLimits
	 *            false if out of bounds in z direction
	 * @param x
	 *            pixel coordinate
	 * @param y
	 *            pixel coordinate
	 * @param z
	 *            coordinate (in pixel units)
	 * @param x0
	 *            centre offset
	 * @param y0
	 *            centre offset
	 * @param pW
	 *            pixel width (in real units)
	 * @param w
	 *            stack width (in pixels)
	 * @param h
	 *            stack height (in pixels)
	 * @param slice
	 *            pixel data array
	 * @return number of out of bounds pixels
	 */
	private int updateContactPixelList(ArrayList<double[]> contactPoints,
			boolean inStackZLimits, double x, double y, double z, double x0,
			double y0, double pW, int w, int h, byte[] slice) {
		int nOOBPoints = 0;
		if (!inStackZLimits) {
			nOOBPoints += 2;
		} else {
			int xPos = centre(x, x0);
			int yPos = centre(y, y0);

			if (isOutOfBounds(xPos, yPos, w, h)) {
				nOOBPoints++;
			} else {
				if (isBackground(xPos, yPos, w, slice)) {
					contactPoints.add(new double[] { (x + x0) * pW,
							(y + y0) * pW, z * pW });
				}
			}

			xPos = centre(-x, x0);
			yPos = centre(-y, y0);

			if (isOutOfBounds(xPos, yPos, w, h)) {
				nOOBPoints++;
			} else {
				if (isBackground(xPos, yPos, w, slice)) {
					contactPoints.add(new double[] { (-x + x0) * pW,
							(-y + y0) * pW, z * pW });
				}
			}
		}
		return nOOBPoints;
	}

	/**
	 * move a coordinate to the centre and convert to int
	 * 
	 * @param x
	 * @param x0
	 * @return
	 */
	private int centre(double x, double x0) {
		return (int) Math.floor(x + x0);
	}

	private boolean isBackground(int x, int y, int w, byte[] slice) {
		return (slice[y * w + x] != -1);
	}

	private boolean isContained(Ellipsoid ellipsoid, byte[][] pixels,
			final double pW, final double pH, final double pD, final int w,
			final int h, final int d) {

		ArrayList<double[]> contactPoints = findEllipseContactPoints(ellipsoid,
				pixels, pW, pH, pD, w, h, d);

		if (contactPoints == null || contactPoints.size() > 0)
			return false;

		//
		// double[][] points = ellipsoid.getSurfacePoints(nVectors);
		// for (double[] p : points) {
		// final int x = (int) Math.floor(p[0] / pW);
		// final int y = (int) Math.floor(p[1] / pH);
		// final int z = (int) Math.floor(p[2] / pD);
		// if (isOutOfBounds(x, y, z, w, h, d))
		// continue;
		// if (pixels[z][y * w + x] != -1)
		// return false;
		// }
		return true;
	}

	/**
	 * return true if 3D pixel coordinate is out of image bounds
	 * 
	 * @param x
	 * @param y
	 * @param z
	 * @param w
	 * @param h
	 * @param d
	 * @return
	 */
	private boolean isOutOfBounds(int x, int y, int z, int w, int h, int d) {
		if (x < 0 || x >= w || y < 0 || y >= h || z < 0 || z >= d)
			return true;
		else
			return false;
	}

	/**
	 * return true if 2D (x,y) pixel coordinate is out of image bounds
	 * 
	 * @param x
	 * @param y
	 * @param w
	 * @param h
	 * @return
	 */
	private boolean isOutOfBounds(int x, int y, int w, int h) {
		if (x < 0 || x >= w || y < 0 || y >= h)
			return true;
		else
			return false;
	}

	private int[][] skeletonPoints(ImagePlus imp) {
		Skeletonize3D sk = new Skeletonize3D();
		ImagePlus skeleton = sk.getSkeleton(imp);
		final ImageStack skeletonStack = skeleton.getStack();

		final int d = imp.getStackSize();
		final int h = imp.getHeight();
		final int w = imp.getWidth();

		// Bare ArrayList is not thread safe for concurrent add() operations.
		final List<int[]> list = Collections
				.synchronizedList(new ArrayList<int[]>());

		final AtomicInteger ai = new AtomicInteger(1);
		Thread[] threads = Multithreader.newThreads();
		for (int thread = 0; thread < threads.length; thread++) {
			threads[thread] = new Thread(new Runnable() {
				public void run() {
					for (int z = ai.getAndIncrement(); z <= d; z = ai
							.getAndIncrement()) {
						byte[] slicePixels = (byte[]) skeletonStack
								.getPixels(z);
						for (int y = 0; y < h; y++) {
							final int offset = y * w;
							for (int x = 0; x < w; x++) {
								if (slicePixels[offset + x] == -1) {
									final int[] array = { x, y, z - 1 };
									list.add(array);
								}
							}
						}
					}
				}
			});
		}
		Multithreader.startAndJoin(threads);

		if (IJ.debugMode)
			IJ.log("Skeleton point ArrayList contains " + list.size()
					+ " points");

		int[][] skeletonPoints = list.toArray(new int[list.size()][]);

		return skeletonPoints;
	}

	/**
	 * Calculate the ellipsoid factor of this ellipsoid as a / b - b / c where a
	 * < b < c and a, b and c are the ellipsoid semi axis lengths (radii). This
	 * formulation places more rod-like ellipsoids towards 1 and plate-like
	 * ellipsoids towards -1. Ellipsoids of EF = 0 have equal a:b and b:c ratios
	 * so are midway between plate and rod. Spheres are a special case of EF =
	 * 0.
	 * 
	 * @param ellipsoid
	 * @return the ellipsoid factor
	 */
	private double ellipsoidFactor(Ellipsoid ellipsoid) {
		double[] radii = ellipsoid.getSortedRadii();
		final double a = radii[0];
		final double b = radii[1];
		final double c = radii[2];
		double ef = a / b - b / c;
		return ef;
	}

	/**
	 * Compare Ellipsoids by volume.
	 * 
	 * Sorting based on this method will result in Ellipsoids sorted in order of
	 * <b>descending</b> volume.
	 * 
	 */
	public int compare(Ellipsoid o1, Ellipsoid o2) {
		return Double.compare(o2.getVolume(), o1.getVolume());
	}

}
