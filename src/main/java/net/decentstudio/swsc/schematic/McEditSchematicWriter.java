package net.decentstudio.swsc.schematic;

import net.minecraft.block.Block;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * Writes legacy MCEdit/WorldEdit .schematic files (numeric block ID + metadata), the
 * counterpart to {@link McEditSchematicLoader}.
 * <p>
 * IMPORTANT: block IDs are assigned per-world by Forge's registry in mod-load order and are
 * NOT portable — a file written on one server may resolve to completely different blocks
 * when loaded elsewhere. Only ever load a file written here back on the same server (or one
 * with an identical mod/registry setup).
 * <p>
 * Entities aren't captured in this format — the vanilla Entities list stores absolute world
 * position/rotation in a shape this tool doesn't translate to/from. Pass only block and
 * tile-entity data; captured entities (see {@code includeEntities} elsewhere) are silently
 * dropped if present.
 */
public final class McEditSchematicWriter {

    private McEditSchematicWriter() {}

    /** @return non-air block count written, or -1 on error */
    public static int write(File outputFile, int width, int height, int length,
                             int originOffsetX, int originOffsetY, int originOffsetZ,
                             Map<String, Integer> palette, short[] voxelIndices,
                             List<int[]> teCoords, List<NBTTagCompound> teNbts) {
        String[] byIndex = new String[palette.size()];
        for (Map.Entry<String, Integer> e : palette.entrySet()) byIndex[e.getValue()] = e.getKey();

        int[] idByIndex = new int[byIndex.length];
        int[] metaByIndex = new int[byIndex.length];
        boolean anyWide = false;
        for (int i = 0; i < byIndex.length; i++) {
            String key = byIndex[i];
            int sep = key.lastIndexOf('#');
            String name = sep >= 0 ? key.substring(0, sep) : key;
            int meta = sep >= 0 ? Integer.parseInt(key.substring(sep + 1)) : 0;
            Block block = Block.getBlockFromName(name);
            int id = block == null ? 0 : Block.getIdFromBlock(block);
            idByIndex[i] = id;
            metaByIndex[i] = meta;
            if (id > 255) anyWide = true;
        }

        int total = width * height * length;
        byte[] blocks = new byte[total];
        byte[] data = new byte[total];
        byte[] addBlocks = anyWide ? new byte[(total + 1) / 2] : null;
        int nonAirCount = 0;

        for (int i = 0; i < total; i++) {
            int pIdx = voxelIndices[i] & 0xFFFF;
            int id   = (pIdx < idByIndex.length)   ? idByIndex[pIdx]   : 0;
            int meta = (pIdx < metaByIndex.length) ? metaByIndex[pIdx] : 0;
            if (id != 0) nonAirCount++;

            blocks[i] = (byte) (id & 0xFF);
            data[i]   = (byte) meta;
            if (addBlocks != null && id > 255) {
                int nibble  = (id >> 8) & 0x0F;
                int byteIdx = i >> 1;
                if ((i & 1) == 0) addBlocks[byteIdx] |= (byte) (nibble & 0x0F);
                else              addBlocks[byteIdx] |= (byte) (nibble << 4);
            }
        }

        NBTTagCompound root = new NBTTagCompound();
        root.setString("Materials", "Alpha");
        root.setShort("Width", (short) width);
        root.setShort("Height", (short) height);
        root.setShort("Length", (short) length);
        root.setByteArray("Blocks", blocks);
        root.setByteArray("Data", data);
        if (addBlocks != null) root.setByteArray("AddBlocks", addBlocks);

        // Mirror WorldEdit's convention (see McEditSchematicLoader): WEOffset = minCorner -
        // origin, i.e. the negation of our originOffset (origin - minCorner).
        root.setInteger("WEOffsetX", -originOffsetX);
        root.setInteger("WEOffsetY", -originOffsetY);
        root.setInteger("WEOffsetZ", -originOffsetZ);
        root.setInteger("WEOriginX", 0);
        root.setInteger("WEOriginY", 0);
        root.setInteger("WEOriginZ", 0);

        NBTTagList teList = new NBTTagList();
        for (int i = 0; i < teNbts.size(); i++) {
            int[] rc = teCoords.get(i);
            NBTTagCompound teNbt = teNbts.get(i).copy();
            teNbt.setInteger("x", rc[0]);
            teNbt.setInteger("y", rc[1]);
            teNbt.setInteger("z", rc[2]);
            teList.appendTag(teNbt);
        }
        root.setTag("TileEntities", teList);
        root.setTag("Entities", new NBTTagList());

        File parent = outputFile.getParentFile();
        if (parent != null) parent.mkdirs();

        try (OutputStream out = new FileOutputStream(outputFile)) {
            CompressedStreamTools.writeCompressed(root, out);
        } catch (IOException e) {
            System.err.println("[McEditSchematicWriter] Failed to write " + outputFile.getName() + ": " + e.getMessage());
            return -1;
        }

        System.out.println("[McEditSchematicWriter] Saved " + outputFile.getAbsolutePath()
                + " " + width + "x" + height + "x" + length
                + " non-air=" + nonAirCount
                + " tile-entities=" + teNbts.size());
        return nonAirCount;
    }
}
