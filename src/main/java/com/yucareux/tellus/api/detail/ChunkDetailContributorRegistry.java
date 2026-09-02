package com.yucareux.tellus.api.detail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide registry sampled immutably when an Earth generator is created.
 */
public final class ChunkDetailContributorRegistry {
   private static final org.slf4j.Logger LOGGER =
      org.slf4j.LoggerFactory.getLogger(ChunkDetailContributorRegistry.class);
   private static final ChunkDetailContributorRegistry GLOBAL = new ChunkDetailContributorRegistry();
   private final Map<String, ChunkDetailContributor> contributors = new TreeMap<>();
   private final AtomicLong epoch = new AtomicLong();
   private boolean sampled;

   private ChunkDetailContributorRegistry() {
   }

   public static ChunkDetailContributorRegistry global() {
      return GLOBAL;
   }

   public synchronized Registration register(String identifier, ChunkDetailContributor contributor) {
      String checkedIdentifier = requireIdentifier(identifier);
      Objects.requireNonNull(contributor, "contributor");
      validateContributor(contributor);
      if (this.contributors.putIfAbsent(checkedIdentifier, contributor) != null) {
         throw new IllegalStateException("Chunk-detail contributor already registered: " + checkedIdentifier);
      }
      if (this.sampled) {
         LOGGER.warn(
            "Chunk-detail contributor {} registered after an Earth generator sampled the registry; it applies only to generators created later",
            checkedIdentifier
         );
      }
      this.epoch.incrementAndGet();
      return new Registration(this, checkedIdentifier, contributor);
   }

   public synchronized Snapshot snapshot() {
      this.sampled = true;
      List<Entry> entries = new ArrayList<>(this.contributors.size());
      for (Map.Entry<String, ChunkDetailContributor> entry : this.contributors.entrySet()) {
         entries.add(new Entry(entry.getKey(), entry.getValue()));
      }
      return new Snapshot(this.epoch.get(), List.copyOf(entries));
   }

   private synchronized void unregister(String identifier, ChunkDetailContributor contributor) {
      if (this.contributors.remove(identifier, contributor)) {
         this.epoch.incrementAndGet();
      }
   }

   private static void validateContributor(ChunkDetailContributor contributor) {
      int halo = contributor.haloBlocks();
      if (halo < 0 || halo > 16) {
         throw new IllegalArgumentException("Chunk-detail contributor halo must be within 0..16 blocks");
      }
      Objects.requireNonNull(contributor.domains(), "contributor domains");
      if (contributor.domains().isEmpty()) {
         throw new IllegalArgumentException("Chunk-detail contributor must advertise at least one domain");
      }
      for (ChunkDetailDomain domain : contributor.domains()) {
         Objects.requireNonNull(domain, "contributor domain");
      }
   }

   private static String requireIdentifier(String value) {
      Objects.requireNonNull(value, "identifier");
      String normalized = value.strip().toLowerCase(java.util.Locale.ROOT);
      if (!normalized.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || normalized.length() > 128) {
         throw new IllegalArgumentException("Invalid chunk-detail contributor identifier: " + value);
      }
      return normalized;
   }

   public record Entry(String identifier, ChunkDetailContributor contributor) {
      public Entry {
         Objects.requireNonNull(identifier, "identifier");
         Objects.requireNonNull(contributor, "contributor");
      }
   }

   public record Snapshot(long epoch, List<Entry> entries) {
      public Snapshot {
         entries = List.copyOf(entries);
      }

      public boolean isEmpty() {
         for (Entry entry : this.entries) {
            if (isActive(entry)) {
               return false;
            }
         }
         return true;
      }

      public boolean uses(ChunkDetailDomain domain) {
         for (Entry entry : this.entries) {
            if (isActive(entry)
               && entry.contributor.domains().contains(domain)) {
               return true;
            }
         }
         return false;
      }

      private static boolean isActive(Entry entry) {
         try {
            return entry.contributor.active();
         } catch (RuntimeException error) {
            LOGGER.error(
               "Chunk-detail contributor {} failed its active-state probe",
               entry.identifier,
               error
            );
            return false;
         }
      }
   }

   public static final class Registration implements AutoCloseable {
      private final ChunkDetailContributorRegistry registry;
      private final String identifier;
      private final ChunkDetailContributor contributor;
      private boolean closed;

      private Registration(
         ChunkDetailContributorRegistry registry,
         String identifier,
         ChunkDetailContributor contributor
      ) {
         this.registry = registry;
         this.identifier = identifier;
         this.contributor = contributor;
      }

      public String identifier() {
         return this.identifier;
      }

      @Override
      public synchronized void close() {
         if (!this.closed) {
            this.closed = true;
            this.registry.unregister(this.identifier, this.contributor);
         }
      }
   }
}
