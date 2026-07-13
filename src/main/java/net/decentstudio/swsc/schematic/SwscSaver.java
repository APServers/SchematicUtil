package net.decentstudio.swsc.schematic;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SwscSaver {

    private SwscSaver() {}

    /**
     * Saves the region between pos1 and pos2 (inclusive) to outputFile.
     * @return number of non-air blocks written, or -1 on error
     */
    public static int save(World world, BlockPos pos1, BlockPos pos2, File outputFile) {
        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());

        int width  = maxX - minX + 1;
        int height = maxY - minY + 1;
        int length = maxZ - minZ + 1;

        // Pass 1: build palette (air always index 0)
        Map<String, Integer> palette = new LinkedHashMap<>();
        palette.put(blockKey(Blocks.AIR.getDefaultState()), 0);

        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    IBlockState state = world.getBlockState(new BlockPos(minX + x, minY + y, minZ + z));
                    if (state.getBlock() == Blocks.AIR) continue;
                    String key = blockKey(state);
                    palette.computeIfAbsent(key, k -> palette.size());
                }
            }
        }

        List<String> paletteList = new ArrayList<>(palette.keySet());
        boolean wide = palette.size() > 256;
        int nonAirCount = 0;

        outputFile.getParentFile().mkdirs();
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(outputFile)))) {

            // Header
            out.write(new byte[]{'S', 'W', 'S', 'C'});
            out.writeByte(1);
            out.writeInt(width);
            out.writeInt(height);
            out.writeInt(length);
            out.writeInt(palette.size());

            // Palette
            for (String key : paletteList) {
                int sep = key.lastIndexOf('#');
                String name;
                int meta;
                if (sep >= 0) {
                    name = key.substring(0, sep);
                    meta = Integer.parseInt(key.substring(sep + 1));
                } else {
                    name = key;
                    meta = 0;
                }
                byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
                out.writeShort(nameBytes.length);
                out.write(nameBytes);
                out.writeByte(meta);
            }

            // Pass 2: block data (Y outer, Z middle, X inner)
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < length; z++) {
                    for (int x = 0; x < width; x++) {
                        IBlockState state = world.getBlockState(new BlockPos(minX + x, minY + y, minZ + z));
                        int idx;
                        if (state.getBlock() == Blocks.AIR) {
                            idx = 0;
                        } else {
                            idx = palette.get(blockKey(state));
                            nonAirCount++;
                        }
                        if (wide) out.writeShort(idx);
                        else      out.writeByte(idx);
                    }
                }
            }

        } catch (IOException e) {
            System.err.println("[SwscSaver] Failed to write " + outputFile.getName() + ": " + e.getMessage());
            return -1;
        }

        System.out.println("[SwscSaver] Saved " + outputFile.getName()
                + " " + width + "x" + height + "x" + length
                + " palette=" + palette.size() + " non-air=" + nonAirCount);
        return nonAirCount;
    }

    private static String blockKey(IBlockState state) {
        Block block = state.getBlock();
        String name = Block.REGISTRY.getNameForObject(block).toString();
        int meta = block.getMetaFromState(state);
        return meta == 0 ? name : name + "#" + meta;
    }
}
