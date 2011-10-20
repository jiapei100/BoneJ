package org.doube.geometry;

import static org.junit.Assert.*;

import org.junit.Test;

public class CentroidTest {

	double[] oneDa = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
			17, 18, 19, 20 };
	double[] oneDb = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
			17, 18, 19 };
	double[] oneDc = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
			17 };
	double[] oneDd = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 };
	double[] oneDe = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 };
	double[] oneDf = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14 };
	double[] oneDg = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13 };
	double[] oneDh = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 };
	double[] oneDi = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11 };
	double[] oneDj = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 };

	double[][] badNd = { oneDa, oneDb, oneDc, oneDd, oneDe, oneDf, oneDg,
			oneDh, oneDi, oneDj };

	double[][] goodNd = { oneDa, oneDa, oneDa, oneDa, oneDa, oneDa, oneDa,
			oneDa, oneDa };

	@Test
	public void testGetCentroidDoubleArrayArray() {
		try {
			Centroid.getCentroid(badNd);
			fail("Should throw IllegalArgumentException");
		} catch (IllegalArgumentException e) {
		}

		//A 20-D centroid
		double[] centroid = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
				15, 16, 17, 18, 19, 20 };
		assertArrayEquals(centroid, Centroid.getCentroid(goodNd), 1E-9);
		
		
		
	}

	@Test
	public void testGetCentroidDoubleArray() {
		assertEquals(10.5, Centroid.getCentroid(oneDa), 1E-9);
		assertEquals(10.0, Centroid.getCentroid(oneDb), 1E-9);
		assertEquals(9.0, Centroid.getCentroid(oneDc), 1E-9);
		assertEquals(8.5, Centroid.getCentroid(oneDd), 1E-9);
		assertEquals(8.0, Centroid.getCentroid(oneDe), 1E-9);
		assertEquals(7.5, Centroid.getCentroid(oneDf), 1E-9);
		assertEquals(7.0, Centroid.getCentroid(oneDg), 1E-9);
		assertEquals(6.5, Centroid.getCentroid(oneDh), 1E-9);
		assertEquals(6.0, Centroid.getCentroid(oneDi), 1E-9);
		assertEquals(5.5, Centroid.getCentroid(oneDj), 1E-9);
	}

}
