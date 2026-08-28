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

import com.seibel.distanthorizons.core.render.nativeReadiness.NativeChunkReadinessFadeState;
import org.junit.Assert;
import org.junit.Test;

public class NativeChunkReadinessFadeStateTest
{
	private static final long FADE_NANOS = 100L;

	@Test
	public void existingReadyChunkStartsFullyFadedOut()
	{
		NativeChunkReadinessFadeState state = new NativeChunkReadinessFadeState(FADE_NANOS, 10);
		byte[] output = update(state, -1, -1, new byte[] { (byte) 0xFF }, 0L);

		Assert.assertEquals(0xFF, output[0] & 0xFF);
	}

	@Test
	public void newlyReadyChunkFadesGradually()
	{
		NativeChunkReadinessFadeState state = new NativeChunkReadinessFadeState(FADE_NANOS, 10);
		update(state, 2, 3, new byte[] { 0 }, 0L);

		Assert.assertEquals(0, update(state, 2, 3, new byte[] { (byte) 0xFF }, 10L)[0] & 0xFF);
		int halfway = update(state, 2, 3, new byte[] { (byte) 0xFF }, 60L)[0] & 0xFF;
		Assert.assertTrue(halfway >= 127 && halfway <= 128);
		Assert.assertEquals(0xFF, update(state, 2, 3, new byte[] { (byte) 0xFF }, 110L)[0] & 0xFF);
	}

	@Test
	public void becomingUnreadyIsImmediate()
	{
		NativeChunkReadinessFadeState state = new NativeChunkReadinessFadeState(FADE_NANOS, 10);
		update(state, 2, 3, new byte[] { 0 }, 0L);
		update(state, 2, 3, new byte[] { (byte) 0xFF }, 10L);
		update(state, 2, 3, new byte[] { (byte) 0xFF }, 60L);

		Assert.assertEquals(0, update(state, 2, 3, new byte[] { 0 }, 61L)[0] & 0xFF);
	}

	@Test
	public void gridMovementDoesNotMixNegativeChunkStates()
	{
		NativeChunkReadinessFadeState state = new NativeChunkReadinessFadeState(FADE_NANOS, 10);
		update(state, -3, -2, new byte[] { 0, (byte) 0xFF }, 0L, 2, 1);
		update(state, -3, -2, new byte[] { (byte) 0xFF, (byte) 0xFF }, 10L, 2, 1);

		byte[] moved = update(state, -2, -2, new byte[] { (byte) 0xFF, (byte) 0xFF }, 60L, 2, 1);
		Assert.assertEquals(0xFF, moved[0] & 0xFF);
		Assert.assertEquals(0xFF, moved[1] & 0xFF);

		byte[] returned = update(state, -3, -2, new byte[] { (byte) 0xFF, (byte) 0xFF }, 60L, 2, 1);
		Assert.assertTrue((returned[0] & 0xFF) >= 127);
	}

	@Test
	public void staleEntriesArePruned()
	{
		NativeChunkReadinessFadeState state = new NativeChunkReadinessFadeState(FADE_NANOS, 1);
		update(state, 0, 0, new byte[] { 0 }, 0L);

		for (int pass = 1; pass <= 64; pass++)
		{
			update(state, 10, 10, new byte[] { 0 }, pass);
		}

		Assert.assertEquals(1, state.getTrackedChunkCount());
	}

	private static byte[] update(
			NativeChunkReadinessFadeState state,
			int minChunkX, int minChunkZ,
			byte[] raw, long nowNanos)
	{
		return update(state, minChunkX, minChunkZ, raw, nowNanos, 1, 1);
	}

	private static byte[] update(
			NativeChunkReadinessFadeState state,
			int minChunkX, int minChunkZ,
			byte[] raw, long nowNanos,
			int width, int height)
	{
		byte[] output = new byte[raw.length];
		state.update(minChunkX, minChunkZ, width, height, raw, output, nowNanos);
		return output;
	}

}
