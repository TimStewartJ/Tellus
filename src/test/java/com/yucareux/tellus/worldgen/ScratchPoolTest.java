package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ScratchPoolTest {
   @Test
   void reusesReleasedScratchInsteadOfAllocating() {
      AtomicInteger created = new AtomicInteger();
      ScratchPool<int[]> pool = new ScratchPool<>(() -> new int[created.incrementAndGet()], 2, 2);

      int[] first = pool.acquire();
      pool.release(first);
      int[] second = pool.acquire();

      assertSame(first, second);
      assertEquals(1, created.get());
   }

   @Test
   void keepsAtMostTheRetainedNumberOfIdleCopies() {
      AtomicInteger created = new AtomicInteger();
      ScratchPool<Object> pool = new ScratchPool<>(() -> {
         created.incrementAndGet();
         return new Object();
      }, 3, 1);

      Object a = pool.acquire();
      Object b = pool.acquire();
      Object c = pool.acquire();
      pool.release(a);
      pool.release(b);
      pool.release(c);
      Object reused = pool.acquire();
      Object fresh = pool.acquire();

      assertSame(a, reused);
      assertNotSame(b, fresh);
      assertNotSame(c, fresh);
      assertEquals(4, created.get());
   }

   @Test
   void blocksSolvesBeyondTheConcurrencyUntilOneFinishes() throws Exception {
      ScratchPool<Object> pool = new ScratchPool<>(Object::new, 1, 1);
      Object held = pool.acquire();
      CountDownLatch started = new CountDownLatch(1);
      AtomicReference<Object> waiter = new AtomicReference<>();
      Thread thread = new Thread(() -> {
         started.countDown();
         waiter.set(pool.acquire());
      });
      thread.start();
      assertTrue(started.await(5, TimeUnit.SECONDS));
      thread.join(200L);
      assertTrue(thread.isAlive(), "the second solve waits while the only permit is held");
      assertEquals(null, waiter.get());

      pool.release(held);
      thread.join(5000L);
      assertFalse(thread.isAlive());
      assertSame(held, waiter.get());
   }

   @Test
   void returnsThePermitWhenAllocationFails() {
      AtomicInteger attempts = new AtomicInteger();
      ScratchPool<Object> pool = new ScratchPool<>(() -> {
         if (attempts.incrementAndGet() == 1) {
            throw new IllegalStateException("allocation failed");
         }
         return new Object();
      }, 1, 0);

      assertThrows(IllegalStateException.class, pool::acquire);
      Object scratch = pool.acquire();
      pool.release(scratch);
      assertEquals(2, attempts.get());
   }

   @Test
   void concurrencyFollowsAnExplicitSettingWithinBounds() {
      String property = "tellus.test.scratchPoolConcurrency";
      try {
         System.setProperty(property, "5");
         assertEquals(5, ScratchPool.concurrency(property, 100, 8));
         System.setProperty(property, "500");
         assertEquals(64, ScratchPool.concurrency(property, 100, 8));
         System.setProperty(property, "0");
         assertEquals(1, ScratchPool.concurrency(property, 100, 8));
      } finally {
         System.clearProperty(property);
      }
   }

   @Test
   void defaultConcurrencyIsAtLeastTwoAndAtMostOnePerProcessor() {
      int processors = Runtime.getRuntime().availableProcessors();
      int small = ScratchPool.concurrency("tellus.test.unsetScratchPoolConcurrency", Integer.MAX_VALUE, 8);
      int large = ScratchPool.concurrency("tellus.test.unsetScratchPoolConcurrency", 1, 1);

      assertEquals(2, small);
      assertEquals(Math.max(2, processors), large);
   }
}
