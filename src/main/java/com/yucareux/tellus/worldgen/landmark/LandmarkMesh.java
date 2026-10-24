package com.yucareux.tellus.worldgen.landmark;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * A landmark's triangle mesh, written by {@code tools/landmark-convert}: metres with Minecraft's axes (x east, y up,
 * z south), the base at y = 0 and the centre of the bounding box at x = z = 0. Meshes made from textured models
 * (TLM2) also give every triangle a block from a small palette; plain meshes (TLM1) are painted by height bands.
 * Palette entries can be fine detail (cable hangers) that only shows in worlds with enough blocks per metre.
 */
public final class LandmarkMesh {
   private static final int MAX_VERTICES = 4_000_000;
   private static final int MAX_TRIANGLES = 8_000_000;
   private static final int MAX_BLOCKS = 255;

   private final float[] positions;
   private final int[] indices;
   private final float[] min;
   private final float[] max;
   private final String[] blocks;
   private final float[] blockMinBlocksPerMeter;
   private final byte[] triangleBlocks;

   LandmarkMesh(float[] positions, int[] indices) {
      this(positions, indices, null, null, null);
   }

   LandmarkMesh(float[] positions, int[] indices, String[] blocks, float[] blockMinBlocksPerMeter, byte[] triangleBlocks) {
      this.positions = positions;
      this.indices = indices;
      this.blocks = blocks;
      this.blockMinBlocksPerMeter = blockMinBlocksPerMeter;
      this.triangleBlocks = triangleBlocks;
      this.min = new float[]{Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
      this.max = new float[]{-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
      for (int i = 0; i < positions.length; i++) {
         this.min[i % 3] = Math.min(this.min[i % 3], positions[i]);
         this.max[i % 3] = Math.max(this.max[i % 3], positions[i]);
      }
   }

   public static LandmarkMesh read(InputStream stream) throws IOException {
      DataInputStream in = new DataInputStream(new GZIPInputStream(stream, 1 << 16));
      byte[] magic = new byte[4];
      in.readFully(magic);
      String format = new String(magic, StandardCharsets.US_ASCII);
      if (!"TLM1".equals(format) && !"TLM2".equals(format)) {
         throw new IOException("Not a Tellus landmark mesh");
      }
      int vertexCount = in.readInt();
      int triangleCount = in.readInt();
      if (vertexCount < 3 || vertexCount > MAX_VERTICES || triangleCount < 1 || triangleCount > MAX_TRIANGLES) {
         throw new IOException("Landmark mesh size out of range: " + vertexCount + " vertices, " + triangleCount + " triangles");
      }
      float[] min = new float[3];
      float[] max = new float[3];
      for (int axis = 0; axis < 3; axis++) {
         min[axis] = in.readFloat();
      }
      for (int axis = 0; axis < 3; axis++) {
         max[axis] = in.readFloat();
      }
      float[] positions = new float[vertexCount * 3];
      for (int i = 0; i < positions.length; i++) {
         int axis = i % 3;
         positions[i] = min[axis] + in.readUnsignedShort() / 65535.0F * (max[axis] - min[axis]);
      }
      int[] indices = new int[triangleCount * 3];
      int previous = 0;
      for (int i = 0; i < indices.length; i++) {
         int zigzag = readVarInt(in);
         int index = previous + (zigzag >>> 1 ^ -(zigzag & 1));
         if (index < 0 || index >= vertexCount) {
            throw new IOException("Landmark mesh index out of range: " + index);
         }
         indices[i] = index;
         previous = index;
      }
      if ("TLM1".equals(format)) {
         return new LandmarkMesh(positions, indices);
      }
      int blockCount = in.readInt();
      if (blockCount < 1 || blockCount > MAX_BLOCKS) {
         throw new IOException("Landmark mesh block palette out of range: " + blockCount);
      }
      String[] blocks = new String[blockCount];
      float[] minBlocksPerMeter = new float[blockCount];
      for (int i = 0; i < blockCount; i++) {
         blocks[i] = in.readUTF();
         minBlocksPerMeter[i] = in.readFloat();
      }
      byte[] triangleBlocks = new byte[triangleCount];
      in.readFully(triangleBlocks);
      for (byte block : triangleBlocks) {
         if ((block & 0xFF) >= blockCount) {
            throw new IOException("Landmark mesh block index out of range: " + (block & 0xFF));
         }
      }
      return new LandmarkMesh(positions, indices, blocks, minBlocksPerMeter, triangleBlocks);
   }

   private static int readVarInt(DataInputStream in) throws IOException {
      int value = 0;
      for (int shift = 0; shift < 35; shift += 7) {
         int b = in.readUnsignedByte();
         value |= (b & 0x7F) << shift;
         if ((b & 0x80) == 0) {
            return value;
         }
      }
      throw new IOException("Malformed landmark mesh varint");
   }

   public int triangleCount() {
      return this.indices.length / 3;
   }

   /** Coordinate {@code axis} (0 x, 1 y, 2 z) of corner {@code corner} of triangle {@code triangle}. */
   public float coordinate(int triangle, int corner, int axis) {
      return this.positions[this.indices[triangle * 3 + corner] * 3 + axis];
   }

   public float min(int axis) {
      return this.min[axis];
   }

   public float max(int axis) {
      return this.max[axis];
   }

   public float height() {
      return this.max[1];
   }

   /** Whether the mesh names a block for every triangle (TLM2); otherwise it is painted by height bands. */
   public boolean hasBlocks() {
      return this.blocks != null;
   }

   /** Block id paths of the palette (minecraft namespace); empty for band-painted meshes. */
   public String[] blocks() {
      return this.blocks == null ? new String[0] : this.blocks.clone();
   }

   /** Palette index of a triangle's block. */
   public int triangleBlock(int triangle) {
      return this.triangleBlocks == null ? 0 : this.triangleBlocks[triangle] & 0xFF;
   }

   /** Whether a triangle shows in a world with this many blocks per metre (fine detail drops out of coarse worlds). */
   public boolean showsAt(int triangle, double blocksPerMeter) {
      return this.blockMinBlocksPerMeter == null || this.blockMinBlocksPerMeter[this.triangleBlocks[triangle] & 0xFF] <= blocksPerMeter;
   }
}
