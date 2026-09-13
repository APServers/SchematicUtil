package net.decentstudio.swsc.schematic;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Reads/writes .swsch files.
 * <p>
 * Format:
 *   HEADER  : magic(4) + version(1) + width(4) + height(4) + length(4)
 *             [+ originOffsetX(4) + originOffsetY(4) + originOffsetZ(4), version >= 3 only]
 *             + paletteSize(4)
 *   PALETTE : paletteSize x [ nameLen(2) + name(UTF-8) + meta(1) ]
 *   BLOCKS  : width*height*length x [ paletteIdx (1 or 2 bytes depending on palette size) ]
 *             (Y-outer, Z-mid, X-inner)
 *   TEs     : teCount(4) x [ relX(2) + relY(2) + relZ(2) + nbtLen(4) + nbt(bytes) ]  (version >= 2 only)
 * <p>
 * Version 1/2 files (no stored origin offset — the original house-schematic format) are
 * read transparently; the origin then defaults to the selection's min corner (0,0,0).
 * <p>
 * Pure (de)serialization only — no World access, so this never needs tick budgeting.
 */
public final class SwscSchematicIO {

    private SwscSchematicIO() {}

    public static String blockKey(IBlockState state) {
        Block block = state.getBlock();
        String name = Block.REGISTRY.getNameForObject(block).toString();
        int meta = block.getMetaFromState(state);
        return meta == 0 ? name : name + "#" + meta;
    }

    /** @return non-air block count written, or -1 on error */
    public static int write(File outputFile, int width, int height, int length,
                             int originOffsetX, int originOffsetY, int originOffsetZ,
                             Map<String, Integer> palette, short[] voxelIndices,
                             List<int[]> teCoords, List<NBTTagCompound> teNbts) {
        String[] byIndex = new String[palette.size()];
        for (Map.Entry<String, Integer> e : palette.entrySet()) byIndex[e.getValue()] = e.getKey();

        boolean wide = palette.size() > 256;
        int nonAirCount = 0;
        for (short idx : voxelIndices) if (idx != 0) nonAirCount++;

        File parent = outputFile.getParentFile();
        if (parent != null) parent.mkdirs();

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(outputFile)))) {

            out.write(new byte[]{'S', 'W', 'S', 'C'});
            out.writeByte(3);
            out.writeInt(width);
            out.writeInt(height);
            out.writeInt(length);
            out.writeInt(originOffsetX);
            out.writeInt(originOffsetY);
            out.writeInt(originOffsetZ);
            out.writeInt(byIndex.length);

            for (String key : byIndex) {
                int sep = key.lastIndexOf('#');
                String name = sep >= 0 ? key.substring(0, sep) : key;
                int    meta = sep >= 0 ? Integer.parseInt(key.substring(sep + 1)) : 0;
                byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
                out.writeShort(nameBytes.length);
                out.write(nameBytes);
                out.writeByte(meta);
            }

            for (short idx : voxelIndices) {
                if (wide) out.writeShort(idx);
                else      out.writeByte(idx);
            }

            out.writeInt(teNbts.size());
            for (int i = 0; i < teNbts.size(); i++) {
                int[] rc = teCoords.get(i);
                out.writeShort(rc[0]);
                out.writeShort(rc[1]);
                out.writeShort(rc[2]);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                CompressedStreamTools.write(teNbts.get(i), new DataOutputStream(baos));
                byte[] nbtBytes = baos.toByteArray();
                out.writeInt(nbtBytes.length);
                out.write(nbtBytes);
            }

        } catch (IOException e) {
            System.err.println("[SwscSchematicIO] Failed to write " + outputFile.getName() + ": " + e.getMessage());
            return -1;
        }

        System.out.println("[SwscSchematicIO] Saved " + outputFile.getAbsolutePath()
                + " " + width + "x" + height + "x" + length
                + " palette=" + byIndex.length
                + " non-air=" + nonAirCount
                + " tile-entities=" + teNbts.size());
        return nonAirCount;
    }

    /**
     * Reads only the schematic header and returns [width, length].
     * Returns null if the file is missing, unreadable, or has an invalid magic.
     */
    public static int[] readDimensions(File file) {
        if (file == null || !file.exists()) return null;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != 'S' || magic[1] != 'W' || magic[2] != 'S' || magic[3] != 'C') return null;
            in.readByte(); // version
            int width  = in.readInt();
            in.readInt(); // height (unused)
            int length = in.readInt();
            return (width > 0 && length > 0) ? new int[]{width, length} : null;
        } catch (IOException e) {
            return null;
        }
    }

    public static SwscSchematic readSwsch(File file) {
        if (file == null || !file.exists()) {
            System.err.println("[SwscSchematicIO] File not found: " + file);
            return null;
        }
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {

            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != 'S' || magic[1] != 'W' || magic[2] != 'S' || magic[3] != 'C') {
                System.err.println("[SwscSchematicIO] Bad magic in " + file.getName());
                return null;
            }
            byte version = in.readByte();
            if (version < 1 || version > 3) {
                System.err.println("[SwscSchematicIO] Unsupported version " + version + " in " + file.getName());
                return null;
            }

            int width  = in.readInt();
            int height = in.readInt();
            int length = in.readInt();

            int originOffsetX = 0, originOffsetY = 0, originOffsetZ = 0;
            if (version >= 3) {
                originOffsetX = in.readInt();
                originOffsetY = in.readInt();
                originOffsetZ = in.readInt();
            }

            int paletteSize = in.readInt();
            if (width <= 0 || height <= 0 || length <= 0 || paletteSize <= 0) {
                System.err.println("[SwscSchematicIO] Invalid dimensions in " + file.getName());
                return null;
            }

            IBlockState[] palette = new IBlockState[paletteSize];
            for (int i = 0; i < paletteSize; i++) {
                int nameLen = in.readUnsignedShort();
                byte[] nameBytes = new byte[nameLen];
                in.readFully(nameBytes);
                String name = new String(nameBytes, StandardCharsets.UTF_8);
                int meta = in.readUnsignedByte();
                Block block = Block.getBlockFromName(name);
                if (block == null) {
                    System.err.println("[SwscSchematicIO] Unknown block '" + name + "' — substituting air");
                    palette[i] = Blocks.AIR.getDefaultState();
                } else {
                    try { palette[i] = block.getStateFromMeta(meta); }
                    catch (Exception e) { palette[i] = block.getDefaultState(); }
                }
            }

            boolean wide  = paletteSize > 256;
            int     total = width * height * length;

            List<Integer>     tmpX  = new ArrayList<>();
            List<Integer>     tmpY  = new ArrayList<>();
            List<Integer>     tmpZ  = new ArrayList<>();
            List<IBlockState> tmpSt = new ArrayList<>();
            Map<String, Integer> posToIdx = new HashMap<>();

            for (int idx = 0; idx < total; idx++) {
                int pIdx  = wide ? in.readUnsignedShort() : in.readUnsignedByte();
                IBlockState state = (pIdx < paletteSize) ? palette[pIdx] : Blocks.AIR.getDefaultState();
                if (state == null || state.getBlock() == Blocks.AIR) continue;

                int xRel =  idx % width;
                int zRel = (idx / width) % length;
                int yRel =  idx / (width * length);

                posToIdx.put(xRel + "," + yRel + "," + zRel, tmpSt.size());
                tmpX.add(xRel); tmpY.add(yRel); tmpZ.add(zRel); tmpSt.add(state);
            }

            int count = tmpSt.size();
            int[] relX = new int[count];
            int[] relY = new int[count];
            int[] relZ = new int[count];
            IBlockState[]    states  = new IBlockState[count];
            NBTTagCompound[] tileNbt = new NBTTagCompound[count];

            for (int i = 0; i < count; i++) {
                relX[i]   = tmpX.get(i);
                relY[i]   = tmpY.get(i);
                relZ[i]   = tmpZ.get(i);
                states[i] = tmpSt.get(i);
            }

            if (version >= 2) {
                int teCount = in.readInt();
                for (int i = 0; i < teCount; i++) {
                    int rx = in.readUnsignedShort();
                    int ry = in.readUnsignedShort();
                    int rz = in.readUnsignedShort();
                    int nbtLen = in.readInt();
                    byte[] nbtBytes = new byte[nbtLen];
                    in.readFully(nbtBytes);
                    Integer blockIdx = posToIdx.get(rx + "," + ry + "," + rz);
                    if (blockIdx != null) {
                        try {
                            tileNbt[blockIdx] = CompressedStreamTools.read(
                                    new DataInputStream(new ByteArrayInputStream(nbtBytes)));
                        } catch (Exception e) {
                            System.err.println("[SwscSchematicIO] Failed to parse TE NBT at "
                                    + rx + "," + ry + "," + rz + ": " + e.getMessage());
                        }
                    }
                }
            }

            System.out.println("[SwscSchematicIO] Loaded " + file.getName()
                    + " v" + version
                    + " " + width + "x" + height + "x" + length
                    + " palette=" + paletteSize + " non-air=" + count);
            return new SwscSchematic(width, height, length,
                    originOffsetX, originOffsetY, originOffsetZ,
                    relX, relY, relZ, states, tileNbt);

        } catch (IOException e) {
            System.err.println("[SwscSchematicIO] Failed to read " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }
}
