package com.yucareux.tellus.client.widget.map;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.world.data.source.Geocoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public class PlaceSearchWidget extends EditBox {
   private static final int SEARCH_TEXT_OFFSET_X = 4;
   private final Geocoder geocoder;
   private final PlaceSearchWidget.SearchHandler searchHandler;
   private final ExecutorService executor = Executors.newSingleThreadExecutor(
      new ThreadFactoryBuilder().setNameFormat("tellus-geocoder-%d").setDaemon(true).build()
   );
   private long searchRequestId;
   private PlaceSearchWidget.State state = PlaceSearchWidget.State.OK;
   private volatile boolean closed;
   private String lastInputText = "";

   public PlaceSearchWidget(int x, int y, int width, int height, Geocoder geocoder, PlaceSearchWidget.SearchHandler searchHandler) {
      super(Minecraft.getInstance().font, x, y, width, height, Component.translatable("gui.earth.search"));
      this.geocoder = geocoder;
      this.searchHandler = searchHandler;
      this.setBordered(false);
      this.setMaxLength(256);
      this.setHint(Component.translatable("gui.earth.search"));
   }

   public void tick() {
      if (this.closed) {
         return;
      }

      String text = this.getValue().trim();
      if (!text.equals(this.lastInputText)) {
         this.lastInputText = text;
         this.state = PlaceSearchWidget.State.OK;
      }
   }

   public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      int x = this.getX();
      int y = this.getY();
      int width = this.getWidth();
      int height = this.getHeight();
      graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, -6250336);
      graphics.fill(x, y, x + width, y + height, this.state.getBackgroundColor());
      graphics.pose().pushMatrix();
      graphics.pose().translate((float)SEARCH_TEXT_OFFSET_X, (height - 8) / 2.0F - 1.0F);
      super.extractWidgetRenderState(graphics, mouseX, mouseY, delta);
      graphics.pose().popMatrix();
   }

   public boolean keyPressed( KeyEvent event) {
      if (!this.isFocused()) {
         return false;
      } else {
         int key = event.key();
         if (com.yucareux.tellus.client.ClientInputCompat.isAcceptKey(key)) {
            this.handleAccept();
            return true;
         } else if (super.keyPressed(event)) {
            this.state = PlaceSearchWidget.State.OK;
            return true;
         } else {
            return false;
         }
      }
   }

   public void close() {
      this.closed = true;
      this.searchRequestId++;
      this.executor.shutdownNow();
   }

   // The only place a search starts: Nominatim's usage policy forbids search-as-you-type.
   private void handleAccept() {
      if (this.closed) {
         return;
      }

      String text = this.getValue().trim();
      if (!text.isEmpty()) {
         long requestId = ++this.searchRequestId;
         try {
            this.executor.execute(() -> {
               try {
                  double[] coordinate = this.geocoder.get(text);
                  Minecraft.getInstance().execute(() -> {
                     if (this.isActiveRequest(requestId)) {
                        if (coordinate == null || coordinate.length < 2 || !isValidCoordinate(coordinate[0], coordinate[1])) {
                           this.state = PlaceSearchWidget.State.NOT_FOUND;
                        } else {
                           this.searchHandler.handle(coordinate[0], coordinate[1]);
                           this.state = PlaceSearchWidget.State.FOUND;
                        }
                     }
                  });
               } catch (Exception error) {
                  Tellus.LOGGER.error("Failed to find searched place {}", text, error);
                  Minecraft.getInstance().execute(() -> {
                     if (this.isActiveRequest(requestId)) {
                        this.state = PlaceSearchWidget.State.NOT_FOUND;
                     }
                  });
               }
            });
         } catch (RejectedExecutionException error) {
            if (!this.closed) {
               Tellus.LOGGER.error("Failed to schedule place search {}", text, error);
               this.state = PlaceSearchWidget.State.NOT_FOUND;
            }
         }
      }
   }

   private static boolean isValidCoordinate(double latitude, double longitude) {
      return Double.isFinite(latitude)
         && Double.isFinite(longitude)
         && latitude >= -90.0
         && latitude <= 90.0
         && longitude >= -180.0
         && longitude <= 180.0;
   }

   private boolean isActiveRequest(long requestId) {
      return !this.closed && requestId == this.searchRequestId;
   }

   public interface SearchHandler {
      void handle(double var1, double var3);
   }

   public static enum State {
      OK(-16777216),
      FOUND(-16759296),
      NOT_FOUND(-12189696);

      private final int backgroundColor;

      private State(int backgroundColor) {
         this.backgroundColor = backgroundColor;
      }

      public int getBackgroundColor() {
         return this.backgroundColor;
      }
   }
}
