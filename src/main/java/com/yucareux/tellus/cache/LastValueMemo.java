package com.yucareux.tellus.cache;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One-entry, per-thread memo placed in front of a shared cache of immutable values.
 *
 * <p>Dense terrain loops sample the same tile or block for hundreds of consecutive columns, and every
 * lookup in a size-bounded Guava cache pays hashing plus recency-queue bookkeeping that contends
 * across worker threads. Remembering the last key/value per thread answers those repeats without
 * touching the shared cache. Values must be immutable; {@link #invalidateAll()} must be called
 * whenever the backing cache is cleared so stale entries cannot outlive it.
 */
public final class LastValueMemo<K, V> {
   private final ThreadLocal<Slot<K, V>> slot = ThreadLocal.withInitial(Slot::new);
   private final AtomicLong generation = new AtomicLong();

   /** Returns the memoized value for {@code key} on this thread, or {@code null}. */
   public V get(K key) {
      Slot<K, V> current = this.slot.get();
      return current.generation == this.generation.get() && Objects.equals(current.key, key) ? current.value : null;
   }

   /** Remembers {@code value} for {@code key} on this thread. A {@code null} value clears the slot. */
   public V put(K key, V value) {
      Slot<K, V> current = this.slot.get();
      current.generation = this.generation.get();
      current.key = value == null ? null : key;
      current.value = value;
      return value;
   }

   /** Drops every thread's memoized entry. */
   public void invalidateAll() {
      this.generation.incrementAndGet();
   }

   private static final class Slot<K, V> {
      private K key;
      private V value;
      private long generation = Long.MIN_VALUE;
   }
}
