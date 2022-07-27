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

import java.util.Arrays;

import org.apache.datasketches.SketchesStateException;

/**
 * The Sorted View provides a view of the data retained by the sketch that would be cumbersome to get any other way.
 * One can iterate of the contents of the sketch, but the result is not sorted.
 * Trying to use getQuantiles would be very cumbersome since one doesn't know what ranks to use to supply the
 * getQuantiles method.  Even worse, suppose it is a large sketch that has retained 1000 values from a stream of
 * millions (or billions).  One would have to execute the getQuantiles method many thousands of times, and using
 * trial &amp; error, try to figure out what the sketch actually has retained.
 *
 * <p>The data from a Sorted view is an unbiased sample of the input stream that can be used for other kinds of
 * analysis not directly provided by the sketch.  A good example comparing two sketches using the Kolmogorov-Smirnov
 * test. One needs this sorted view for the test.</p>
 *
 * <p>This sorted view can also be used for multiple getRank and getQuantile queries once it has been created.
 * Because it takes some computational work to create this sorted view, it doesn't make sense to create this sorted view
 * just for single getRank queries.  For the first getQuantile queries, it must be created. But for all queries
 * after the first, assuming the sketch has not been updated, the getQuantile and getRank queries are very fast.</p>
 *
 * @author Kevin Lang
 * @author Alexander Saydakov
 */
public final class KllDoublesSketchSortedView {

  private final long n_;
  private final double[] items_;
  private final long[] weights_; //comes in as weights, converted to cumulative weights
  private final int[] levels_;
  private int numLevels_;

  // assumes that all levels are sorted including level 0
  @SuppressWarnings("deprecation")
  KllDoublesSketchSortedView(final double[] items, final int[] levels, final int numLevels,
      final long n, final boolean cumulative, final boolean inclusive) {
    n_ = n;
    final int numItems = levels[numLevels] - levels[0];
    items_ = new double[numItems];
    weights_ = new long[numItems + 1]; // one more is intentional
    levels_ = new int[numLevels + 1];
    populateFromSketch(items, levels, numLevels, numItems);
    blockyTandemMergeSort(items_, weights_, levels_, numLevels_);
    if (cumulative) {
      KllQuantilesHelper.convertToPrecedingCumulative(weights_, inclusive);
    }
  }

  //For testing only. Allows testing of getQuantile without a sketch.
  KllDoublesSketchSortedView(final double[] items, final long[] weights, final long n) {
    n_ = n;
    items_ = items;
    weights_ = weights; //must be size of items + 1
    levels_ = null;  //not used by test
    numLevels_ = 0;  //not used by test
  }

  @SuppressWarnings("deprecation")
  public double getQuantile(final double rank) {
    if (weights_[weights_.length - 1] < n_) {
      throw new SketchesStateException("getQuantile must be used with cumulative view only");
    }
    final long pos = KllQuantilesHelper.posOfRank(rank, n_);
    return approximatelyAnswerPositonalQuery(pos);
  }

  public KllDoublesSketchSortedViewIterator iterator() {
    return new KllDoublesSketchSortedViewIterator(items_, weights_);
  }

  private static void blockyTandemMergeSort(final double[] items, final long[] weights,
      final int[] levels, final int numLevels) {
    if (numLevels == 1) { return; }

    // duplicate the input in preparation for the "ping-pong" copy reduction strategy.
    final double[] itemsTmp = Arrays.copyOf(items, items.length);
    final long[] weightsTmp = Arrays.copyOf(weights, items.length); // don't need the extra one here

    blockyTandemMergeSortRecursion(itemsTmp, weightsTmp, items, weights, levels, 0, numLevels);
  }

  private static void blockyTandemMergeSortRecursion(
      final double[] itemsSrc, final long[] weightsSrc,
      final double[] itemsDst, final long[] weightsDst,
      final int[] levels, final int startingLevel, final int numLevels) {
    if (numLevels == 1) { return; }
    final int numLevels1 = numLevels / 2;
    final int numLevels2 = numLevels - numLevels1;
    assert numLevels1 >= 1;
    assert numLevels2 >= numLevels1;
    final int startingLevel1 = startingLevel;
    final int startingLevel2 = startingLevel + numLevels1;
    // swap roles of src and dst
    blockyTandemMergeSortRecursion(
        itemsDst, weightsDst,
        itemsSrc, weightsSrc,
        levels, startingLevel1, numLevels1);
    blockyTandemMergeSortRecursion(
        itemsDst, weightsDst,
        itemsSrc, weightsSrc,
        levels, startingLevel2, numLevels2);
    tandemMerge(
        itemsSrc, weightsSrc,
        itemsDst, weightsDst,
        levels,
        startingLevel1, numLevels1,
        startingLevel2, numLevels2);
  }

  private static void tandemMerge(
      final double[] itemsSrc, final long[] weightsSrc,
      final double[] itemsDst, final long[] weightsDst,
      final int[] levelStarts,
      final int startingLevel1, final int numLevels1,
      final int startingLevel2, final int numLevels2) {
    final int fromIndex1 = levelStarts[startingLevel1];
    final int toIndex1 = levelStarts[startingLevel1 + numLevels1]; // exclusive
    final int fromIndex2 = levelStarts[startingLevel2];
    final int toIndex2 = levelStarts[startingLevel2 + numLevels2]; // exclusive
    int iSrc1 = fromIndex1;
    int iSrc2 = fromIndex2;
    int iDst = fromIndex1;

    while (iSrc1 < toIndex1 && iSrc2 < toIndex2) {
      if (itemsSrc[iSrc1] < itemsSrc[iSrc2]) {
        itemsDst[iDst] = itemsSrc[iSrc1];
        weightsDst[iDst] = weightsSrc[iSrc1];
        iSrc1++;
      } else {
        itemsDst[iDst] = itemsSrc[iSrc2];
        weightsDst[iDst] = weightsSrc[iSrc2];
        iSrc2++;
      }
      iDst++;
    }
    if (iSrc1 < toIndex1) {
      System.arraycopy(itemsSrc, iSrc1, itemsDst, iDst, toIndex1 - iSrc1);
      System.arraycopy(weightsSrc, iSrc1, weightsDst, iDst, toIndex1 - iSrc1);
    } else if (iSrc2 < toIndex2) {
      System.arraycopy(itemsSrc, iSrc2, itemsDst, iDst, toIndex2 - iSrc2);
      System.arraycopy(weightsSrc, iSrc2, weightsDst, iDst, toIndex2 - iSrc2);
    }
  }

  @SuppressWarnings("deprecation")
  private double approximatelyAnswerPositonalQuery(final long pos) {
    assert pos >= 0;
    assert pos < n_;
    final int index = KllQuantilesHelper.chunkContainingPos(weights_, pos);
    return items_[index];
  }

  private void populateFromSketch(final double[] srcItems, final int[] srcLevels,
      final int numLevels, final int numItems) {
    final int offset = srcLevels[0];
    System.arraycopy(srcItems, offset, items_, 0, numItems);
    int srcLevel = 0;
    int dstLevel = 0;
    long weight = 1;
    while (srcLevel < numLevels) {
      final int fromIndex = srcLevels[srcLevel] - offset;
      final int toIndex = srcLevels[srcLevel + 1] - offset; // exclusive
      if (fromIndex < toIndex) { // if equal, skip empty level
        Arrays.fill(weights_, fromIndex, toIndex, weight);
        levels_[dstLevel] = fromIndex;
        levels_[dstLevel + 1] = toIndex;
        dstLevel++;
      }
      srcLevel++;
      weight *= 2;
    }
    weights_[numItems] = 0;
    numLevels_ = dstLevel;
  }

}
