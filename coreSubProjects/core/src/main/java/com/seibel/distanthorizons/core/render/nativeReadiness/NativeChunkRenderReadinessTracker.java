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

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Tracks how many native render sections are ready in each chunk column.
 * Platform-specific renderer hooks feed state transitions into this class.
 */
public class NativeChunkRenderReadinessTracker
{
	public static final NativeChunkRenderReadinessTracker INSTANCE = new NativeChunkRenderReadinessTracker();

	private final Long2IntOpenHashMap builtSectionCountsByChunk = new Long2IntOpenHashMap();
	private final ThreadLocal<Long> sectionCreationGeneration = new ThreadLocal<>();

	private long activeGeneration;
	private int expectedSectionsPerChunk;
	private boolean managerActive;
	private boolean sectionHookObserved;



	public synchronized long activate(int expectedSectionsPerChunk)
	{
		if (expectedSectionsPerChunk <= 0)
		{
			throw new IllegalArgumentException("Expected section count must be positive.");
		}

		this.activeGeneration++;
		this.expectedSectionsPerChunk = expectedSectionsPerChunk;
		this.managerActive = true;
		this.sectionHookObserved = false;
		this.builtSectionCountsByChunk.clear();
		return this.activeGeneration;
	}

	public synchronized void deactivate(long generation)
	{
		if (!this.managerActive || generation != this.activeGeneration)
		{
			return;
		}

		this.managerActive = false;
		this.sectionHookObserved = false;
		this.expectedSectionsPerChunk = 0;
		this.builtSectionCountsByChunk.clear();
	}

	public synchronized void onSectionStateChanged(
			long generation,
			int chunkX, int chunkZ,
			boolean wasBuilt, boolean isBuilt)
	{
		if (!this.managerActive || generation != this.activeGeneration)
		{
			return;
		}

		this.sectionHookObserved = true;
		if (wasBuilt == isBuilt)
		{
			return;
		}

		long chunkPos = packChunkPos(chunkX, chunkZ);
		int previousCount = this.builtSectionCountsByChunk.get(chunkPos);
		if (isBuilt)
		{
			this.builtSectionCountsByChunk.put(chunkPos, previousCount + 1);
		}
		else if (previousCount <= 1)
		{
			this.builtSectionCountsByChunk.remove(chunkPos);
		}
		else
		{
			this.builtSectionCountsByChunk.put(chunkPos, previousCount - 1);
		}
	}

	public void beginSectionCreation(long generation)
	{
		if (generation == 0L)
		{
			this.sectionCreationGeneration.remove();
		}
		else
		{
			this.sectionCreationGeneration.set(generation);
		}
	}

	public long captureSectionCreationGeneration()
	{
		Long generation = this.sectionCreationGeneration.get();
		return generation != null ? generation : 0L;
	}

	public void endSectionCreation(long generation)
	{
		Long activeCreation = this.sectionCreationGeneration.get();
		if (activeCreation != null && activeCreation == generation)
		{
			this.sectionCreationGeneration.remove();
		}
	}

	/**
	 * Writes {@code 0xFF} for fully built native chunk columns and {@code 0x00}
	 * for columns that still have at least one section pending.
	 *
	 * @return false when the renderer hooks have not established a usable state
	 */
	public synchronized boolean fillReadinessMask(
			int minChunkX, int minChunkZ,
			int width, int height,
			byte[] output)
	{
		int requiredLength = validateMaskDimensions(width, height, output);
		if (!this.isSupported())
		{
			return false;
		}

		int index = 0;
		for (int zOffset = 0; zOffset < height; zOffset++)
		{
			int chunkZ = minChunkZ + zOffset;
			for (int xOffset = 0; xOffset < width; xOffset++)
			{
				int chunkX = minChunkX + xOffset;
				int builtCount = this.builtSectionCountsByChunk.get(packChunkPos(chunkX, chunkZ));
				output[index++] = (byte) (builtCount >= this.expectedSectionsPerChunk ? 0xFF : 0x00);
			}
		}

		assert index == requiredLength;
		return true;
	}

	public synchronized boolean isSupported()
	{
		return this.managerActive
			&& this.sectionHookObserved
			&& this.expectedSectionsPerChunk > 0;
	}

	public synchronized int getBuiltSectionCount(int chunkX, int chunkZ)
	{
		return this.builtSectionCountsByChunk.get(packChunkPos(chunkX, chunkZ));
	}

	public synchronized int getExpectedSectionsPerChunk()
	{
		return this.expectedSectionsPerChunk;
	}

	private static int validateMaskDimensions(int width, int height, byte[] output)
	{
		if (width <= 0 || height <= 0)
		{
			throw new IllegalArgumentException("Mask dimensions must be positive.");
		}

		int requiredLength = Math.multiplyExact(width, height);
		if (output == null || output.length < requiredLength)
		{
			throw new IllegalArgumentException("Output mask is too small.");
		}

		return requiredLength;
	}

	private static long packChunkPos(int chunkX, int chunkZ)
	{
		return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
	}

}
