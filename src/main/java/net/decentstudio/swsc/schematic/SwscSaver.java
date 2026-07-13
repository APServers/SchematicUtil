package net.decentstudio.swsc.schematic;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * SWSC format version 2:
 *   HEADER  : magic(4) + version(1) + width(4) + height(4) + length(4) + paletteSize(4)
 *   PALETTE : paletteSize × [ nameLen(2) + name(UTF-8) + meta(1) ]
 *   BLOCKS  : width*height*length × [ paletteIdx (1 or 2 bytes) ]  (Y-outer, Z-mid, X-inner)
 *   TEs     : teCount(4) × [ relX(2) + relY(2) + relZ(2) + nbtLen(4) + nbt(bytes) ]
 */
public class SwscSaver {

    private SwscSaver() {}

    /** @return non-air block count written, or -1 on error */
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
        for (int y = 0; y < height; y++)
            for (int z = 0; z < length; z++)
                for (int x = 0; x < width; x++) {
                    IBlockState s = world.getBlockState(new BlockPos(minX + x, minY + y, minZ + z));
                    if (s.getBlock() != Blocks.AIR)
                        palette.computeIfAbsent(blockKey(s), k -> palette.size());
                }

        List<String> paletteList = new ArrayList<>(palette.keySet());
        boolean wide = palette.size() > 256;

        // Collect tile-entity NBT (relative coords → stripped NBT)
        List<int[]>         teCoords = new ArrayList<>();
        List<NBTTagCompound> teNbts  = new ArrayList<>();
        for (int y = 0; y < height; y++)
            for (int z = 0; z < length; z++)
                for (int x = 0; x < width; x++) {
                    BlockPos abs = new BlockPos(minX + x, minY + y, minZ + z);
                    TileEntity te = world.getTileEntity(abs);
                    if (te == null) continue;
                    NBTTagCompound nbt = te.writeToNBT(new NBTTagCompound());
                    // strip absolute coords — will be re-inserted on paste
                    nbt.removeTag("x"); nbt.removeTag("y"); nbt.removeTag("z");
                    teCoords.add(new int[]{x, y, z});
                    teNbts.add(nbt);
                }

        outputFile.getParentFile().mkdirs();
        int nonAirCount = 0;

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(outputFile)))) {

            // Header
            out.write(new byte[]{'S', 'W', 'S', 'C'});
            out.writeByte(2); // version 2
            out.writeInt(width);
            out.writeInt(height);
            out.writeInt(length);
            out.writeInt(palette.size());

            // Palette
            for (String key : paletteList) {
                int sep = key.lastIndexOf('#');
                String name = sep >= 0 ? key.substring(0, sep) : key;
                int    meta = sep >= 0 ? Integer.parseInt(key.substring(sep + 1)) : 0;
                byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
                out.writeShort(nameBytes.length);
                out.write(nameBytes);
                out.writeByte(meta);
            }

            // Pass 2: block data
            for (int y = 0; y < height; y++)
                for (int z = 0; z < length; z++)
                    for (int x = 0; x < width; x++) {
                        IBlockState s = world.getBlockState(new BlockPos(minX + x, minY + y, minZ + z));
                        int idx = s.getBlock() == Blocks.AIR ? 0 : palette.get(blockKey(s));
                        if (idx != 0) nonAirCount++;
                        if (wide) out.writeShort(idx);
                        else      out.writeByte(idx);
                    }

            // Tile entities
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
            System.err.println("[SwscSaver] Failed to write " + outputFile.getName() + ": " + e.getMessage());
            return -1;
        }

        System.out.println("[SwscSaver] Saved " + outputFile.getName()
                + " " + width + "x" + height + "x" + length
                + " palette=" + palette.size()
                + " non-air=" + nonAirCount
                + " tile-entities=" + teNbts.size());
        return nonAirCount;
    }

    private static String blockKey(IBlockState state) {
        Block block = state.getBlock();
        String name = Block.REGISTRY.getNameForObject(block).toString();
        int meta = block.getMetaFromState(state);
        return meta == 0 ? name : name + "#" + meta;
    }
}
