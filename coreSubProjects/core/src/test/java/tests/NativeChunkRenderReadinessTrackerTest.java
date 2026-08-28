/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package tests;

import com.seibel.distanthorizons.core.render.nativeReadiness.NativeChunkRenderReadinessTracker;
import org.junit.Assert;
import org.junit.Test;

public class NativeChunkRenderReadinessTrackerTest
{
	@Test
	public void requiresManagerAndSectionHooks()
	{
		NativeChunkRenderReadinessTracker tracker = new NativeChunkRenderReadinessTracker();
		byte[] mask = new byte[1];

		Assert.assertFalse(tracker.fillReadinessMask(0, 0, 1, 1, mask));
		long generation = tracker.activate(2);
		Assert.assertFalse(tracker.fillReadinessMask(0, 0, 1, 1, mask));

		tracker.onSectionStateChanged(generation, 0, 0, false, false);
		Assert.assertTrue(tracker.fillReadinessMask(0, 0, 1, 1, mask));
		Assert.assertEquals(0, mask[0] & 0xFF);

		tracker.deactivate(generation);
		Assert.assertFalse(tracker.fillReadinessMask(0, 0, 1, 1, mask));
	}

	@Test
	public void columnBecomesReadyOnlyWhenEverySectionIsBuilt()
	{
		NativeChunkRenderReadinessTracker tracker = new NativeChunkRenderReadinessTracker();
		long generation = tracker.activate(3);

		tracker.onSectionStateChanged(generation, 4, -2, false, true);
		tracker.onSectionStateChanged(generation, 4, -2, false, true);
		Assert.assertEquals(2, tracker.getBuiltSectionCount(4, -2));

		byte[] mask = new byte[1];
		Assert.assertTrue(tracker.fillReadinessMask(4, -2, 1, 1, mask));
		Assert.assertEquals(0, mask[0] & 0xFF);

		tracker.onSectionStateChanged(generation, 4, -2, false, true);
		tracker.fillReadinessMask(4, -2, 1, 1, mask);
		Assert.assertEquals(0xFF, mask[0] & 0xFF);

		tracker.onSectionStateChanged(generation, 4, -2, true, false);
		tracker.fillReadinessMask(4, -2, 1, 1, mask);
		Assert.assertEquals(0, mask[0] & 0xFF);
	}

	@Test
	public void unchangedCallbacksDoNotDoubleCount()
	{
		NativeChunkRenderReadinessTracker tracker = new NativeChunkRenderReadinessTracker();
		long generation = tracker.activate(1);

		tracker.onSectionStateChanged(generation, -7, -9, false, true);
		tracker.onSectionStateChanged(generation, -7, -9, true, true);
		tracker.onSectionStateChanged(generation, -7, -9, false, false);

		Assert.assertEquals(1, tracker.getBuiltSectionCount(-7, -9));
	}

	@Test
	public void fillsGridInXThenZOrderIncludingNegativeCoordinates()
	{
		NativeChunkRenderReadinessTracker tracker = new NativeChunkRenderReadinessTracker();
		long generation = tracker.activate(1);
		tracker.onSectionStateChanged(generation, -2, -3, false, true);
		tracker.onSectionStateChanged(generation, 0, -2, false, true);

		byte[] mask = new byte[6];
		Assert.assertTrue(tracker.fillReadinessMask(-2, -3, 3, 2, mask));

		Assert.assertArrayEquals(new byte[] {
			(byte) 0xFF, 0, 0,
			0, 0, (byte) 0xFF,
		}, mask);
	}

	@Test
	public void staleManagerCannotDeactivateReplacement()
	{
		NativeChunkRenderReadinessTracker tracker = new NativeChunkRenderReadinessTracker();
		long oldGeneration = tracker.activate(2);
		long newGeneration = tracker.activate(1);

		tracker.deactivate(oldGeneration);
		tracker.onSectionStateChanged(oldGeneration, 1, 1, true, false);
		tracker.onSectionStateChanged(newGeneration, 1, 1, false, true);

		byte[] mask = new byte[1];
		Assert.assertTrue(tracker.fillReadinessMask(1, 1, 1, 1, mask));
		Assert.assertEquals(0xFF, mask[0] & 0xFF);

		tracker.deactivate(newGeneration);
		Assert.assertFalse(tracker.isSupported());
	}

	@Test
	public void staleSectionsCannotClearReplacementCounts()
	{
		NativeChunkRenderReadinessTracker tracker = new NativeChunkRenderReadinessTracker();
		long oldGeneration = tracker.activate(1);
		tracker.onSectionStateChanged(oldGeneration, 5, 6, false, true);

		long newGeneration = tracker.activate(1);
		tracker.onSectionStateChanged(newGeneration, 5, 6, false, true);
		tracker.onSectionStateChanged(oldGeneration, 5, 6, true, false);

		byte[] mask = new byte[1];
		Assert.assertTrue(tracker.fillReadinessMask(5, 6, 1, 1, mask));
		Assert.assertEquals(0xFF, mask[0] & 0xFF);
	}

	@Test
	public void sectionCreationCapturesItsOwningManager()
	{
		NativeChunkRenderReadinessTracker tracker = new NativeChunkRenderReadinessTracker();
		long oldGeneration = tracker.activate(1);
		tracker.beginSectionCreation(oldGeneration);
		Assert.assertEquals(oldGeneration, tracker.captureSectionCreationGeneration());
		tracker.endSectionCreation(oldGeneration);
		Assert.assertEquals(0L, tracker.captureSectionCreationGeneration());

		long newGeneration = tracker.activate(1);
		tracker.beginSectionCreation(oldGeneration);
		Assert.assertEquals(oldGeneration, tracker.captureSectionCreationGeneration());
		tracker.endSectionCreation(oldGeneration);

		tracker.beginSectionCreation(newGeneration);
		Assert.assertEquals(newGeneration, tracker.captureSectionCreationGeneration());
		tracker.endSectionCreation(newGeneration);
	}

}
