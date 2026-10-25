package com.yucareux.tellus.worldgen;

import java.awt.image.BufferedImage;

/**
 * Diagnostics: an orthographic oblique view of generated block columns (terrain top, water top, falling
 * curtains), drawn front to back with a y-buffer per screen column. Blocks are sampled nearest, so the view
 * looks like the game's: ledges, steps and walls show as they would on screen.
 */
final class WaterRender3d {
   static final byte DRY = 0, WET = 1, BANK = 2, FALLING = 3, OCEAN = 4;

   private WaterRender3d() {
   }

   /**
    * @param azimuthDegrees direction the camera looks along, clockwise from north (+z is south)
    * @param elevationDegrees downward tilt of the view
    * @param pixelsPerBlock horizontal size of a block on screen
    */
   static BufferedImage render(int size, int[] terrain, int[] water, byte[] kind, int x0, int z0, int span,
      double azimuthDegrees, double elevationDegrees, double pixelsPerBlock) {
      double az = Math.toRadians(azimuthDegrees), el = Math.toRadians(elevationDegrees);
      // Forward along the ground (x east, z south), right is 90 degrees clockwise.
      double fx = Math.sin(az), fz = -Math.cos(az), rx = Math.cos(az), rz = Math.sin(az);
      double cx = x0 + span * 0.5, cz = z0 + span * 0.5;
      double sinEl = Math.sin(el), cosEl = Math.cos(el);
      int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
      for (int z = z0; z < z0 + span; z++) for (int x = x0; x < x0 + span; x++) {
         int i = z * size + x, top = Math.max(terrain[i], kind[i] == DRY || kind[i] == BANK ? terrain[i] : water[i]);
         minY = Math.min(minY, terrain[i]); maxY = Math.max(maxY, top);
      }
      double half = span * 0.5 * Math.sqrt(2.0);
      int width = (int)Math.ceil(half * 2.0 * pixelsPerBlock);
      double vMin = (minY - 1) * cosEl - half * sinEl, vMax = (maxY + 2) * cosEl + half * sinEl;
      int height = (int)Math.ceil((vMax - vMin) * pixelsPerBlock);
      height = Math.min(height, 6000);
      BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
      int sky = 0x9FC8F0;
      for (int py = 0; py < height; py++) for (int px = 0; px < width; px++) image.setRGB(px, py, sky);
      double step = 0.25;
      for (int px = 0; px < width; px++) {
         double u = px / pixelsPerBlock - half;
         int yBuffer = height; // lowest screen row drawn so far (rows grow downwards)
         for (double t = -half; t <= half; t += step) {
            double wx = cx + rx * u + fx * t, wz = cz + rz * u + fz * t;
            int bx = (int)Math.floor(wx), bz = (int)Math.floor(wz);
            if (bx < x0 || bz < z0 || bx >= x0 + span || bz >= z0 + span) continue;
            int i = bz * size + bx;
            byte k = kind[i];
            boolean wet = k == WET || k == FALLING || k == OCEAN;
            int ground = terrain[i];
            int top = wet ? Math.max(ground, water[i]) : ground;
            // Top face of the column (y + 1 is the block top).
            double v = (top + 1) * cosEl + t * sinEl;
            int row = (int)Math.floor(height - (v - vMin) * pixelsPerBlock);
            if (row >= yBuffer) continue;
            int from = Math.max(0, row);
            // The block's top face spans a quarter-block step; the rest of the fill below it is the side.
            double topFaceV = (top + 1) * cosEl + (t - step) * sinEl;
            int topFaceRow = (int)Math.floor(height - (topFaceV - vMin) * pixelsPerBlock);
            int color = color(size, terrain, water, kind, bx, bz, x0, z0, span, wet, top, ground);
            for (int py = from; py < Math.min(yBuffer, height); py++) {
               boolean side = py > topFaceRow + 1;
               int c = side ? sideColor(color, wet, k == FALLING, py, bx, bz) : color;
               image.setRGB(px, py, c);
            }
            yBuffer = Math.max(0, from);
            if (yBuffer == 0) break;
         }
      }
      return image;
   }

   private static int color(int size, int[] terrain, int[] water, byte[] kind, int x, int z, int x0, int z0, int span,
      boolean wet, int top, int ground) {
      int i = z * size + x;
      if (kind[i] == FALLING) return 0xE8F4FF;
      if (wet) {
         int depth = Math.max(0, water[i] - ground);
         double d = Math.min(1.0, depth / 10.0);
         int r = (int)(60 - 40 * d), g = (int)(120 - 50 * d), b = (int)(220 - 60 * d);
         // Surface steps read as white foam edges.
         if (hasLowerWetNeighbor(size, water, kind, x, z, x0, z0, span)) { r = 200; g = 225; b = 245; }
         return r << 16 | g << 8 | b;
      }
      // Slope from the neighbours: steep ground is stone, gentle ground grass; sun from the north-west.
      int east = terrain[z * size + Math.min(x0 + span - 1, x + 1)], west = terrain[z * size + Math.max(x0, x - 1)];
      int south = terrain[Math.min(z0 + span - 1, z + 1) * size + x], north = terrain[Math.max(z0, z - 1) * size + x];
      int slope = Math.max(Math.max(Math.abs(east - ground), Math.abs(west - ground)), Math.max(Math.abs(south - ground), Math.abs(north - ground)));
      double light = Math.max(0.55, Math.min(1.15, 0.9 + (west - east + north - south) * 0.04));
      int base;
      if (kind[i] == BANK && slope <= 1) base = 0xC8B878;
      else if (slope >= 3) base = 0x8A8A8A;
      else if (slope == 2) base = 0x6E8E4E;
      else base = 0x5FA046;
      return scale(base, light);
   }

   private static boolean hasLowerWetNeighbor(int size, int[] water, byte[] kind, int x, int z, int x0, int z0, int span) {
      int i = z * size + x;
      int[] dx = {1, -1, 0, 0}, dz = {0, 0, 1, -1};
      for (int d = 0; d < 4; d++) {
         int nx = x + dx[d], nz = z + dz[d];
         if (nx < x0 || nz < z0 || nx >= x0 + span || nz >= z0 + span) continue;
         int n = nz * size + nx;
         if ((kind[n] == WET || kind[n] == FALLING) && water[n] < water[i]) return true;
      }
      return false;
   }

   private static int sideColor(int color, boolean wet, boolean falling, int py, int x, int z) {
      if (falling) return (py + x + z) % 3 == 0 ? 0xFFFFFF : 0xC8E0F8;
      if (wet) return scale(color, 0.85);
      // Dirt and stone faces of exposed sides.
      return scale(0x7A6A55, 0.75);
   }

   private static int scale(int rgb, double f) {
      int r = (int)Math.min(255, (rgb >> 16 & 255) * f), g = (int)Math.min(255, (rgb >> 8 & 255) * f), b = (int)Math.min(255, (rgb & 255) * f);
      return r << 16 | g << 8 | b;
   }
}
