package net.decentstudio.swsc.schematic;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SwscLoader {

    private SwscLoader() {}

    public static SwscSchematic load(File file) {
        if (file == null || !file.exists()) {
            System.err.println("[SwscLoader] File not found: " + file);
            return null;
        }
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {

            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != 'S' || magic[1] != 'W' || magic[2] != 'S' || magic[3] != 'C') {
                System.err.println("[SwscLoader] Bad magic in " + file.getName());
                return null;
            }
            byte version = in.readByte();
            if (version != 1 && version != 2) {
                System.err.println("[SwscLoader] Unsupported version " + version + " in " + file.getName());
                return null;
            }

            int width       = in.readInt();
            int height      = in.readInt();
            int length      = in.readInt();
            int paletteSize = in.readInt();

            if (width <= 0 || height <= 0 || length <= 0 || paletteSize <= 0) {
                System.err.println("[SwscLoader] Invalid dimensions in " + file.getName());
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
                    System.err.println("[SwscLoader] Unknown block '" + name + "' — substituting air");
                    palette[i] = Blocks.AIR.getDefaultState();
                } else {
                    try { palette[i] = block.getStateFromMeta(meta); }
                    catch (Exception e) { palette[i] = block.getDefaultState(); }
                }
            }

            // Block data
            boolean wide  = paletteSize > 256;
            int     total = width * height * length;

            List<Integer>     tmpX  = new ArrayList<>();
            List<Integer>     tmpY  = new ArrayList<>();
            List<Integer>     tmpZ  = new ArrayList<>();
            List<IBlockState> tmpSt = new ArrayList<>();
            // map from "x,y,z" string → index in tmp arrays (for TE lookup)
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
                relX[i]  = tmpX.get(i);
                relY[i]  = tmpY.get(i);
                relZ[i]  = tmpZ.get(i);
                states[i] = tmpSt.get(i);
            }

            // Tile entities (version 2 only)
            if (version == 2) {
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
                            System.err.println("[SwscLoader] Failed to parse TE NBT at "
                                    + rx + "," + ry + "," + rz + ": " + e.getMessage());
                        }
                    }
                }
            }

            System.out.println("[SwscLoader] Loaded " + file.getName()
                    + " v" + version
                    + " " + width + "x" + height + "x" + length
                    + " palette=" + paletteSize + " non-air=" + count);
            return new SwscSchematic(width, height, length, relX, relY, relZ, states, tileNbt);

        } catch (IOException e) {
            System.err.println("[SwscLoader] Failed to read " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }
}
