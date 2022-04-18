/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.datasketches.kll;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import org.apache.datasketches.SketchesArgumentException;
import org.apache.datasketches.memory.Memory;
import org.testng.annotations.Test;

@SuppressWarnings("javadoc")
public class KllDoublesSketchTest {

  private static final double PMF_EPS_FOR_K_8 = 0.35; // PMF rank error (epsilon) for k=8
  private static final double PMF_EPS_FOR_K_128 = 0.025; // PMF rank error (epsilon) for k=128
  private static final double PMF_EPS_FOR_K_256 = 0.013; // PMF rank error (epsilon) for k=256
  private static final double NUMERIC_NOISE_TOLERANCE = 1E-6;

  @Test
  public void empty() {
    final KllDoublesSketch sketch = new KllDoublesSketch();
    sketch.update(Double.NaN); // this must not change anything
    assertTrue(sketch.isEmpty());
    assertEquals(sketch.getN(), 0);
    assertEquals(sketch.getNumRetained(), 0);
    assertTrue(Double.isNaN(sketch.getRank(0)));
    assertTrue(Double.isNaN(sketch.getMinValue()));
    assertTrue(Double.isNaN(sketch.getMaxValue()));
    assertTrue(Double.isNaN(sketch.getQuantile(0.5)));
    assertNull(sketch.getQuantiles(new double[] {0}));
    assertNull(sketch.getPMF(new double[] {0}));
    assertNotNull(sketch.toString(true, true));
    assertNotNull(sketch.toString());
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void getQuantileInvalidArg() {
    final KllDoublesSketch sketch = new KllDoublesSketch();
    sketch.update(1);
    sketch.getQuantile(-1.0);
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void getQuantilesInvalidArg() {
    final KllDoublesSketch sketch = new KllDoublesSketch();
    sketch.update(1);
    sketch.getQuantiles(new double[] {2.0});
  }

  @Test
  public void oneItem() {
    final KllDoublesSketch sketch = new KllDoublesSketch();
    sketch.update(1);
    assertFalse(sketch.isEmpty());
    assertEquals(sketch.getN(), 1);
    assertEquals(sketch.getNumRetained(), 1);
    assertEquals(sketch.getRank(1), 0.0);
    assertEquals(sketch.getRank(2), 1.0);
    assertEquals(sketch.getMinValue(), 1f);
    assertEquals(sketch.getMaxValue(), 1f);
    assertEquals(sketch.getQuantile(0.5), 1f);
  }

  @Test
  public void manyItemsEstimationMode() {
    final KllDoublesSketch sketch = new KllDoublesSketch();
    final int n = 1000000;
    for (int i = 0; i < n; i++) {
      sketch.update(i);
      assertEquals(sketch.getN(), i + 1);
    }

    // test getRank
    for (int i = 0; i < n; i++) {
      final double trueRank = (double) i / n;
      assertEquals(sketch.getRank(i), trueRank, PMF_EPS_FOR_K_256, "for value " + i);
    }

    // test getPMF
    final double[] pmf = sketch.getPMF(new double[] {n / 2}); // split at median
    assertEquals(pmf.length, 2);
    assertEquals(pmf[0], 0.5, PMF_EPS_FOR_K_256);
    assertEquals(pmf[1], 0.5, PMF_EPS_FOR_K_256);

    assertEquals(sketch.getMinValue(), 0f); // min value is exact
    assertEquals(sketch.getQuantile(0), 0f); // min value is exact
    assertEquals(sketch.getMaxValue(), n - 1f); // max value is exact
    assertEquals(sketch.getQuantile(1), n - 1f); // max value is exact

    // check at every 0.1 percentage point
    final double[] fractions = new double[1001];
    final double[] reverseFractions = new double[1001]; // check that ordering doesn't matter
    for (int i = 0; i <= 1000; i++) {
      fractions[i] = (double) i / 1000;
      reverseFractions[1000 - i] = fractions[i];
    }
    final double[] quantiles = sketch.getQuantiles(fractions);
    final double[] reverseQuantiles = sketch.getQuantiles(reverseFractions);
    double previousQuantile = 0;
    for (int i = 0; i <= 1000; i++) {
      final double quantile = sketch.getQuantile(fractions[i]);
      assertEquals(quantile, quantiles[i]);
      assertEquals(quantile, reverseQuantiles[1000 - i]);
      assertTrue(previousQuantile <= quantile);
      previousQuantile = quantile;
    }
}

  @Test
  public void getRankGetCdfGetPmfConsistency() {
    final KllDoublesSketch sketch = new KllDoublesSketch();
    final int n = 1000;
    final double[] values = new double[n];
    for (int i = 0; i < n; i++) {
      sketch.update(i);
      values[i] = i;
    }
    final double[] ranks = sketch.getCDF(values);
    final double[] pmf = sketch.getPMF(values);
    double sumPmf = 0;
    for (int i = 0; i < n; i++) {
      assertEquals(ranks[i], sketch.getRank(values[i]), NUMERIC_NOISE_TOLERANCE,
          "rank vs CDF for value " + i);
      sumPmf += pmf[i];
      assertEquals(ranks[i], sumPmf, NUMERIC_NOISE_TOLERANCE, "CDF vs PMF for value " + i);
    }
    sumPmf += pmf[n];
    assertEquals(sumPmf, 1.0, NUMERIC_NOISE_TOLERANCE);
    assertEquals(ranks[n], 1.0, NUMERIC_NOISE_TOLERANCE);
  }

  @Test
  public void merge() {
    final KllDoublesSketch sketch1 = new KllDoublesSketch();
    final KllDoublesSketch sketch2 = new KllDoublesSketch();
    final int n = 10000;
    for (int i = 0; i < n; i++) {
      sketch1.update(i);
      sketch2.update(2 * n - i - 1);
    }

    assertEquals(sketch1.getMinValue(), 0.0f);
    assertEquals(sketch1.getMaxValue(), n - 1f);

    assertEquals(sketch2.getMinValue(), n);
    assertEquals(sketch2.getMaxValue(), 2f * n - 1f);

    sketch1.merge(sketch2);

    assertFalse(sketch1.isEmpty());
    assertEquals(sketch1.getN(), 2L * n);
    assertEquals(sketch1.getMinValue(), 0f);
    assertEquals(sketch1.getMaxValue(), 2f * n - 1);
    assertEquals(sketch1.getQuantile(0.5), n, n * PMF_EPS_FOR_K_256);
  }

  @Test
  public void mergeLowerK() {
    final KllDoublesSketch sketch1 = new KllDoublesSketch(256);
    final KllDoublesSketch sketch2 = new KllDoublesSketch(128);
    final int n = 10000;
    for (int i = 0; i < n; i++) {
      sketch1.update(i);
      sketch2.update(2 * n - i - 1);
    }

    assertEquals(sketch1.getMinValue(), 0.0f);
    assertEquals(sketch1.getMaxValue(), n - 1f);

    assertEquals(sketch2.getMinValue(), n);
    assertEquals(sketch2.getMaxValue(), 2f * n - 1f);

    assertTrue(sketch1.getNormalizedRankError(false) < sketch2.getNormalizedRankError(false));
    assertTrue(sketch1.getNormalizedRankError(true) < sketch2.getNormalizedRankError(true));
    sketch1.merge(sketch2);

    // sketch1 must get "contaminated" by the lower K in sketch2
    assertEquals(sketch1.getNormalizedRankError(false), sketch2.getNormalizedRankError(false));
    assertEquals(sketch1.getNormalizedRankError(true), sketch2.getNormalizedRankError(true));

    assertFalse(sketch1.isEmpty());
    assertEquals(sketch1.getN(), 2 * n);
    assertEquals(sketch1.getMinValue(), 0f);
    assertEquals(sketch1.getMaxValue(), 2f * n - 1f);
    assertEquals(sketch1.getQuantile(0.5), n, n * PMF_EPS_FOR_K_128);
  }

  @Test
  public void mergeEmptyLowerK() {
    final KllDoublesSketch sketch1 = new KllDoublesSketch(256);
    final KllDoublesSketch sketch2 = new KllDoublesSketch(128);
    final int n = 10000;
    for (int i = 0; i < n; i++) {
      sketch1.update(i);
    }

    // rank error should not be affected by a merge with an empty sketch with lower K
    final double rankErrorBeforeMerge = sketch1.getNormalizedRankError(true);
    sketch1.merge(sketch2);
    assertEquals(sketch1.getNormalizedRankError(true), rankErrorBeforeMerge);

    assertFalse(sketch1.isEmpty());
    assertEquals(sketch1.getN(), n);
    assertEquals(sketch1.getMinValue(), 0f);
    assertEquals(sketch1.getMaxValue(), n - 1f);
    assertEquals(sketch1.getQuantile(0.5), n / 2f, n / 2 * PMF_EPS_FOR_K_256);

    //merge the other way
    sketch2.merge(sketch1);
    assertFalse(sketch1.isEmpty());
    assertEquals(sketch1.getN(), n);
    assertEquals(sketch1.getMinValue(), 0f);
    assertEquals(sketch1.getMaxValue(), n - 1f);
    assertEquals(sketch1.getQuantile(0.5), n / 2f, n / 2 * PMF_EPS_FOR_K_256);
  }

  @Test
  public void mergeExactModeLowerK() {
    final KllDoublesSketch sketch1 = new KllDoublesSketch(256);
    final KllDoublesSketch sketch2 = new KllDoublesSketch(128);
    final int n = 10000;
    for (int i = 0; i < n; i++) {
      sketch1.update(i);
    }
    sketch2.update(1);

    // rank error should not be affected by a merge with a sketch in exact mode with lower K
    final double rankErrorBeforeMerge = sketch1.getNormalizedRankError(true);
    sketch1.merge(sketch2);
    assertEquals(sketch1.getNormalizedRankError(true), rankErrorBeforeMerge);
  }

  @Test
  public void mergeMinMinValueFromOther() {
    final KllDoublesSketch sketch1 = new KllDoublesSketch();
    final KllDoublesSketch sketch2 = new KllDoublesSketch();
    sketch1.update(1);
    sketch2.update(2);
    sketch2.merge(sketch1);
    assertEquals(sketch2.getMinValue(), 1.0F);
  }

  @Test
  public void mergeMinAndMaxFromOther() {
    final KllDoublesSketch sketch1 = new KllDoublesSketch();
    for (int i = 0; i < 1000000; i++) {
      sketch1.update(i);
    }
    final KllDoublesSketch sketch2 = new KllDoublesSketch();
    sketch2.merge(sketch1);
    assertEquals(sketch2.getMinValue(), 0F);
    assertEquals(sketch2.getMaxValue(), 999999F);
  }

  @SuppressWarnings("unused")
  @Test(expectedExceptions = SketchesArgumentException.class)
  public void kTooSmall() {
    new KllDoublesSketch(BaseKllSketch.MIN_K - 1);
  }

  @SuppressWarnings("unused")
  @Test(expectedExceptions = SketchesArgumentException.class)
  public void kTooLarge() {
    new KllDoublesSketch(BaseKllSketch.MAX_K + 1);
  }

  @Test
  public void minK() {
    final KllDoublesSketch sketch = new KllDoublesSketch(BaseKllSketch.MIN_K);
    for (int i = 0; i < 1000; i++) {
      sketch.update(i);
    }
    assertEquals(sketch.getK(), BaseKllSketch.MIN_K);
    assertEquals(sketch.getQuantile(0.5), 500, 500 * PMF_EPS_FOR_K_8);
  }

  @Test
  public void maxK() {
    final KllDoublesSketch sketch = new KllDoublesSketch(BaseKllSketch.MAX_K);
    for (int i = 0; i < 1000; i++) {
      sketch.update(i);
    }
    assertEquals(sketch.getK(), BaseKllSketch.MAX_K);
    assertEquals(sketch.getQuantile(0.5), 500, 500 * PMF_EPS_FOR_K_256);
  }

  @Test
  public void serializeDeserializeEmpty() {
    final KllDoublesSketch sketch1 = new KllDoublesSketch();
    final byte[] bytes = sketch1.toByteArray();
    final KllDoublesSketch sketch2 = KllDoublesSketch.heapify(Memory.wrap(bytes));
    assertEquals(bytes.length, sketch1.getSerializedSizeBytes());
    assertTrue(sketch2.isEmpty());
    assertEquals(sketch2.getNumRetained(), sketch1.getNumRetained());
    assertEquals(sketch2.getN(), sketch1.getN());
    assertEquals(sketch2.getNormalizedRankError(false), sketch1.getNormalizedRankError(false));
    assertTrue(Double.isNaN(sketch2.getMinValue()));
    assertTrue(Double.isNaN(sketch2.getMaxValue()));
    assertEquals(sketch2.getSerializedSizeBytes(), sketch1.getSerializedSizeBytes());
  }

  @Test
  public void serializeDeserializeOneItem() {
    final KllDoublesSketch sketch1 = new KllDoublesSketch();
    sketch1.update(1);
    final byte[] bytes = sketch1.toByteArray();
    final KllDoublesSketch sketch2 = KllDoublesSketch.heapify(Memory.wrap(bytes));
    assertEquals(bytes.length, sketch1.getSerializedSizeBytes());
    assertFalse(sketch2.isEmpty());
    assertEquals(sketch2.getNumRetained(), 1);
    assertEquals(sketch2.getN(), 1);
    assertEquals(sketch2.getNormalizedRankError(false), sketch1.getNormalizedRankError(false));
    assertFalse(Double.isNaN(sketch2.getMinValue()));
    assertFalse(Double.isNaN(sketch2.getMaxValue()));
    assertEquals(sketch2.getSerializedSizeBytes(), 8 + Double.BYTES);
  }

  @Test
  public void serializeDeserialize() {
    final KllDoublesSketch sketch1 = new KllDoublesSketch();
    final int n = 1000;
    for (int i = 0; i < n; i++) {
      sketch1.update(i);
    }
    final byte[] bytes = sketch1.toByteArray();
    final KllDoublesSketch sketch2 = KllDoublesSketch.heapify(Memory.wrap(bytes));
    assertEquals(bytes.length, sketch1.getSerializedSizeBytes());
    assertFalse(sketch2.isEmpty());
    assertEquals(sketch2.getNumRetained(), sketch1.getNumRetained());
    assertEquals(sketch2.getN(), sketch1.getN());
    assertEquals(sketch2.getNormalizedRankError(false), sketch1.getNormalizedRankError(false));
    assertEquals(sketch2.getMinValue(), sketch1.getMinValue());
    assertEquals(sketch2.getMaxValue(), sketch1.getMaxValue());
    assertEquals(sketch2.getSerializedSizeBytes(), sketch1.getSerializedSizeBytes());
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void outOfOrderSplitPoints() {
    final KllDoublesSketch sketch = new KllDoublesSketch();
    sketch.update(0);
    sketch.getCDF(new double[] {1, 0});
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void nanSplitPoint() {
    final KllDoublesSketch sketch = new KllDoublesSketch();
    sketch.update(0);
    sketch.getCDF(new double[] {Double.NaN});
  }

  @Test
  public void getMaxSerializedSizeBytes() {
    final int sizeBytes =
        KllDoublesSketch.getMaxSerializedSizeBytes(BaseKllSketch.DEFAULT_K, 1_000_000_000);
    assertEquals(sizeBytes, 6184);
  }

  @Test
  public void checkUbOnNumLevels() {
    assertEquals(KllHelper.ubOnNumLevels(0), 1);
  }

  @Test
  public void checkIntCapAux() {
    int lvlCap = KllHelper.levelCapacity(10, 61, 0, 8);
    assertEquals(lvlCap, 8);
    lvlCap = KllHelper.levelCapacity(10, 61, 60, 8);
    assertEquals(lvlCap, 10);
  }

  @Test
  public void checkSuperLargeKandLevels() {
    //This is beyond what the sketch can be configured for.
    final int size = KllHelper.computeTotalItemCapacity(1 << 29, 8, 61);
    assertEquals(size, 1_610_612_846);
  }

  @Test
  public void getQuantiles() {
    final KllDoublesSketch sketch = new KllDoublesSketch();
    sketch.update(1);
    sketch.update(2);
    sketch.update(3);
    final double[] quantiles1 = sketch.getQuantiles(new double[] {0, 0.5, 1});
    final double[] quantiles2 = sketch.getQuantiles(3);
    assertEquals(quantiles1, quantiles2);
    assertEquals(quantiles1[0], 1f);
    assertEquals(quantiles1[1], 2f);
    assertEquals(quantiles1[2], 3f);
  }

}