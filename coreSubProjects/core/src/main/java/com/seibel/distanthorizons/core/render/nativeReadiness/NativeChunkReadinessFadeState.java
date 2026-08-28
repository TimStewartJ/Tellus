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

package com.seibel.distanthorizons.core.render.nativeReadiness;

import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Converts binary native chunk readiness into a short temporal fade.
 * Becoming unready is immediate so DH can cover a missing native chunk again.
 */
public class NativeChunkReadinessFadeState
{
	public static final long DEFAULT_FADE_DURATION_NANOS = 350_000_000L;
	private static final int DEFAULT_STALE_PASS_COUNT = 120;

	private final Long2ObjectOpenHashMap<ChunkState> chunkStates = new Long2ObjectOpenHashMap<>();
	private final long fadeDurationNanos;
	private final int stalePassCount;

	private long updatePass;



	public NativeChunkReadinessFadeState()
	{
		this(DEFAULT_FADE_DURATION_NANOS, DEFAULT_STALE_PASS_COUNT);
	}

	public NativeChunkReadinessFadeState(long fadeDurationNanos, int stalePassCount)
	{
		if (fadeDurationNanos <= 0)
		{
			throw new IllegalArgumentException("Fade duration must be positive.");
		}
		if (stalePassCount <= 0)
		{
			throw new IllegalArgumentException("Stale pass count must be positive.");
		}

		this.fadeDurationNanos = fadeDurationNanos;
		this.stalePassCount = stalePassCount;
	}

	public void update(
			int minChunkX, int minChunkZ,
			int width, int height,
			byte[] rawReadinessMask,
			byte[] fadedReadinessMask,
			long nowNanos)
	{
		int requiredLength = validateMaskDimensions(width, height, rawReadinessMask, fadedReadinessMask);
		this.updatePass++;

		int index = 0;
		for (int zOffset = 0; zOffset < height; zOffset++)
		{
			int chunkZ = minChunkZ + zOffset;
			for (int xOffset = 0; xOffset < width; xOffset++)
			{
				int chunkX = minChunkX + xOffset;
				long chunkPos = packChunkPos(chunkX, chunkZ);
				boolean ready = (rawReadinessMask[index] & 0xFF) == 0xFF;

				ChunkState state = this.chunkStates.get(chunkPos);
				if (state == null)
				{
					state = new ChunkState(ready, ready ? 0xFF : 0x00, nowNanos, this.updatePass);
					this.chunkStates.put(chunkPos, state);
				}
				else
				{
					state.lastSeenPass = this.updatePass;
					updateState(state, ready, nowNanos);
				}

				fadedReadinessMask[index++] = (byte) state.fadeValue;
			}
		}

		assert index == requiredLength;
		if ((this.updatePass & 0x3F) == 0)
		{
			this.pruneStaleStates();
		}
	}

	public void clear()
	{
		this.chunkStates.clear();
		this.updatePass = 0;
	}

	public int getTrackedChunkCount()
	{
		return this.chunkStates.size();
	}

	private void updateState(ChunkState state, boolean ready, long nowNanos)
	{
		if (!ready)
		{
			state.ready = false;
			state.fadeValue = 0;
			state.readySinceNanos = nowNanos;
			return;
		}

		if (!state.ready)
		{
			state.ready = true;
			state.fadeValue = 0;
			state.readySinceNanos = nowNanos;
			return;
		}

		if (state.fadeValue == 0xFF)
		{
			return;
		}

		long elapsedNanos = Math.max(0L, nowNanos - state.readySinceNanos);
		state.fadeValue = (int) Math.min(0xFFL, elapsedNanos * 0xFFL / this.fadeDurationNanos);
	}

	private void pruneStaleStates()
	{
		ObjectIterator<Long2ObjectMap.Entry<ChunkState>> iterator =
			this.chunkStates.long2ObjectEntrySet().fastIterator();
		while (iterator.hasNext())
		{
			ChunkState state = iterator.next().getValue();
			if (this.updatePass - state.lastSeenPass > this.stalePassCount)
			{
				iterator.remove();
			}
		}
	}

	private static int validateMaskDimensions(
			int width, int height,
			byte[] rawReadinessMask,
			byte[] fadedReadinessMask)
	{
		if (width <= 0 || height <= 0)
		{
			throw new IllegalArgumentException("Mask dimensions must be positive.");
		}

		int requiredLength = Math.multiplyExact(width, height);
		if (rawReadinessMask == null || rawReadinessMask.length < requiredLength)
		{
			throw new IllegalArgumentException("Raw readiness mask is too small.");
		}
		if (fadedReadinessMask == null || fadedReadinessMask.length < requiredLength)
		{
			throw new IllegalArgumentException("Faded readiness mask is too small.");
		}

		return requiredLength;
	}

	private static long packChunkPos(int chunkX, int chunkZ)
	{
		return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
	}

	private static class ChunkState
	{
		private boolean ready;
		private int fadeValue;
		private long readySinceNanos;
		private long lastSeenPass;

		private ChunkState(boolean ready, int fadeValue, long readySinceNanos, long lastSeenPass)
		{
			this.ready = ready;
			this.fadeValue = fadeValue;
			this.readySinceNanos = readySinceNanos;
			this.lastSeenPass = lastSeenPass;
		}
	}

}
