package net.decentstudio.swsc.schematic;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Reads legacy MCEdit/WorldEdit .schematic files (numeric block ID + metadata).
 *
 * IMPORTANT: block IDs are assigned per-world by Forge's registry in mod-load order and are
 * NOT portable — a file exported on one server may resolve to completely different blocks
 * on another. Only ever read a .schematic file on the same server it was exported from.
 */
public final class McEditSchematicLoader {

    private McEditSchematicLoader() {}

    public static SwscSchematic load(File file) {
        if (file == null || !file.exists()) {
            System.err.println("[McEditSchematicLoader] File not found: " + file);
            return null;
        }
        NBTTagCompound root;
        try (InputStream in = new FileInputStream(file)) {
            root = CompressedStreamTools.readCompressed(in);
        } catch (IOException e) {
            System.err.println("[McEditSchematicLoader] Failed to read " + file.getName() + ": " + e.getMessage());
            return null;
        }

        int width  = root.getShort("Width")  & 0xFFFF;
        int height = root.getShort("Height") & 0xFFFF;
        int length = root.getShort("Length") & 0xFFFF;
        if (width <= 0 || height <= 0 || length <= 0) {
            System.err.println("[McEditSchematicLoader] Invalid dimensions in " + file.getName());
            return null;
        }

        byte[] blocks = root.getByteArray("Blocks");
        byte[] data   = root.getByteArray("Data");
        byte[] addBlocks = root.hasKey("AddBlocks") ? root.getByteArray("AddBlocks") : null;

        // WorldEdit stores WEOffset = minCorner - origin (see SchematicWriter). Our convention
        // is originOffset = origin - minCorner, i.e. the negation. Plain MCEdit exports (no
        // WorldEdit) simply lack these tags, so origin correctly defaults to the min corner.
        int originOffsetX = root.hasKey("WEOffsetX") ? -root.getInteger("WEOffsetX") : 0;
        int originOffsetY = root.hasKey("WEOffsetY") ? -root.getInteger("WEOffsetY") : 0;
        int originOffsetZ = root.hasKey("WEOffsetZ") ? -root.getInteger("WEOffsetZ") : 0;

        int total = width * height * length;
        List<Integer>     tmpX  = new ArrayList<>();
        List<Integer>     tmpY  = new ArrayList<>();
        List<Integer>     tmpZ  = new ArrayList<>();
        List<IBlockState> tmpSt = new ArrayList<>();
        Map<BlockPos, Integer> posToIdx = new HashMap<>();

        for (int index = 0; index < total; index++) {
            int y =  index / (width * length);
            int z = (index % (width * length)) / width;
            int x =  index % width;

            int idLow = blocks[index] & 0xFF;
            int id;
            if (addBlocks != null) {
                int nibble = (index & 1) == 0
                        ? (addBlocks[index >> 1] & 0x0F)
                        : ((addBlocks[index >> 1] >> 4) & 0x0F);
                id = idLow | (nibble << 8);
            } else {
                id = idLow;
            }
            if (id == 0) continue; // air

            Block block = Block.getBlockById(id);
            if (block == null || block == Blocks.AIR) continue;

            int meta = data[index] & 0xFF;
            IBlockState state;
            try { state = block.getStateFromMeta(meta); }
            catch (Exception e) { state = block.getDefaultState(); }

            posToIdx.put(new BlockPos(x, y, z), tmpSt.size());
            tmpX.add(x); tmpY.add(y); tmpZ.add(z); tmpSt.add(state);
        }

        int count = tmpSt.size();
        int[] relX = new int[count];
        int[] relY = new int[count];
        int[] relZ = new int[count];
        IBlockState[]    states  = new IBlockState[count];
        NBTTagCompound[] tileNbt = new NBTTagCompound[count];
        for (int i = 0; i < count; i++) {
            relX[i] = tmpX.get(i);
            relY[i] = tmpY.get(i);
            relZ[i] = tmpZ.get(i);
            states[i] = tmpSt.get(i);
        }

        if (root.hasKey("TileEntities")) {
            NBTTagList teList = root.getTagList("TileEntities", 10);
            for (int i = 0; i < teList.tagCount(); i++) {
                NBTTagCompound teNbt = teList.getCompoundTagAt(i);
                BlockPos pos = new BlockPos(teNbt.getInteger("x"), teNbt.getInteger("y"), teNbt.getInteger("z"));
                Integer blockIdx = posToIdx.get(pos);
                if (blockIdx != null) {
                    NBTTagCompound copy = teNbt.copy();
                    copy.removeTag("x"); copy.removeTag("y"); copy.removeTag("z");
                    tileNbt[blockIdx] = copy;
                }
            }
        }

        System.out.println("[McEditSchematicLoader] Loaded " + file.getName()
                + " " + width + "x" + height + "x" + length + " non-air=" + count);
        return new SwscSchematic(width, height, length,
                originOffsetX, originOffsetY, originOffsetZ,
                relX, relY, relZ, states, tileNbt);
    }
}
