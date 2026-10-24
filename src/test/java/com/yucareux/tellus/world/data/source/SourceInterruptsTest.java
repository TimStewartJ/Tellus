package com.yucareux.tellus.world.data.source;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class SourceInterruptsTest {
   @Test
   void recognizesCancelledReadsThroughWrappers() {
      assertTrue(SourceInterrupts.isInterruption(new ClosedByInterruptException()));
      assertTrue(SourceInterrupts.isInterruption(new IOException("wrapped", new InterruptedException())));
      assertTrue(SourceInterrupts.isInterruption(new RuntimeException(new InterruptedIOException("read"))));
      assertTrue(SourceInterrupts.isInterruption(new CancellationException("cancelled")));
   }

   @Test
   void timeoutsAndOrdinaryFailuresAreNotCancellations() {
      assertFalse(SourceInterrupts.isInterruption(new SocketTimeoutException("slow host")));
      assertFalse(SourceInterrupts.isInterruption(new IOException("HTTP 503")));
      assertFalse(SourceInterrupts.isInterruption(null));
   }

   @Test
   void cancelledReadIsRethrownWithItsCause() {
      ClosedByInterruptException cause = new ClosedByInterruptException();
      CancellationException thrown = assertThrows(CancellationException.class, () -> SourceInterrupts.throwIfInterruption(cause));
      assertSame(cause, thrown.getCause());
      assertDoesNotThrow(() -> SourceInterrupts.throwIfInterruption(new IOException("corrupt tile")));
   }
}
