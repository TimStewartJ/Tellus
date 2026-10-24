package com.yucareux.tellus.compat;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.WorldClock;

public final class MinecraftClockAccess {
   private MinecraftClockAccess() {}

   public static long totalTicks(ServerLevel level, Holder<WorldClock> clock) {
      return level.clockManager().getTotalTicks(clock);
   }
}
