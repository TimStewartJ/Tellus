package com.seibel.distanthorizons.core.generation.queues;

import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import org.junit.Assert;
import org.junit.Test;

public class WorldGenerationQueuePriorityTest
{
	@Test
	public void priorityAvailabilityWinsBeforeDistance()
	{
		Assert.assertTrue(WorldGenerationQueue.shouldPreferTask(
			10_000, IDhApiWorldGenerator.GENERATION_PRIORITY,
			1, IDhApiWorldGenerator.GENERATION_READY));
		Assert.assertFalse(WorldGenerationQueue.shouldPreferTask(
			1, IDhApiWorldGenerator.GENERATION_READY,
			10_000, IDhApiWorldGenerator.GENERATION_PRIORITY));
		Assert.assertTrue(WorldGenerationQueue.shouldPreferTask(
			1, IDhApiWorldGenerator.GENERATION_READY,
			2, IDhApiWorldGenerator.GENERATION_READY));
	}

	@Test
	public void priorityAncestorIsDiscoveredBeforeItIsRequestedDirectly()
	{
		long requestedPos = DhSectionPos.encode((byte) 6, -33, 17);
		long priorityPos = WorldGenerationQueue.findPriorityRetrievalPos(
			requestedPos,
			(byte) 24,
			(pos, dataDetail) -> dataDetail == 6
				? IDhApiWorldGenerator.GENERATION_PRIORITY
				: IDhApiWorldGenerator.GENERATION_READY);

		Assert.assertEquals(12, DhSectionPos.getDetailLevel(priorityPos));
		Assert.assertEquals(DhSectionPos.convertToDetailLevel(requestedPos, (byte) 12), priorityPos);
	}

	@Test
	public void retrievalPositionIsUnchangedWithoutPriorityAvailability()
	{
		long requestedPos = DhSectionPos.encode((byte) 8, 9, -11);

		Assert.assertEquals(
			requestedPos,
			WorldGenerationQueue.findPriorityRetrievalPos(
				requestedPos,
				(byte) 24,
				(pos, dataDetail) -> IDhApiWorldGenerator.GENERATION_READY));
	}
}
