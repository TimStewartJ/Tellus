package com.yucareux.tellus.world.data.source;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.util.concurrent.CancellationException;

/**
 * Tells a cancelled request apart from a failed source. Distant Horizons cancels generation by
 * interrupting its worker, and an interrupted thread's file read fails with
 * {@link ClosedByInterruptException}; neither may delete a valid cache file or mark a tile as
 * unavailable for the requests that were not cancelled.
 */
public final class SourceInterrupts {
   private SourceInterrupts() {
   }

   public static boolean isInterruption(Throwable error) {
      for (Throwable current = error; current != null; current = current.getCause() == current ? null : current.getCause()) {
         if (current instanceof InterruptedException
            || current instanceof ClosedByInterruptException
            || current instanceof CancellationException
            || current instanceof InterruptedIOException && !(current instanceof SocketTimeoutException)) {
            return true;
         }
      }
      return false;
   }

   /** Rethrows a cancelled read as a {@link CancellationException}, so it is never taken for an invalid cache file. */
   public static void throwIfInterruption(Throwable error) {
      if (isInterruption(error)) {
         CancellationException cancelled = new CancellationException("Tellus source read cancelled");
         cancelled.initCause(error);
         throw cancelled;
      }
   }
}
