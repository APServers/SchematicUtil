package net.decentstudio.swsc.schematic;

import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Reads Sponge Schematic Format (.schem) files, as produced by modern WorldEdit — schema
 * versions 1, 2 and 3 are supported. Every block's namespaced blockstate string is resolved
 * against the live 1.12.2 Forge block registry via {@link LegacyBlockResolver}; anything
 * that can't be matched is replaced with air and reported, never silently dropped.
 */
public final class SpongeSchematicLoader {

    private SpongeSchematicLoader() {}

    /** Distinct blockstate strings that had no 1.12.2 match on the most recent load. */
    public static List<String> lastUnresolved = Collections.emptyList();

    public static SwscSchematic load(File file) {
        if (file == null || !file.exists()) {
            System.err.println("[SpongeSchematicLoader] File not found: " + file);
            return null;
        }
        NBTTagCompound root;
        try (InputStream in = new FileInputStream(file)) {
            root = CompressedStreamTools.readCompressed(in);
        } catch (IOException e) {
            System.err.println("[SpongeSchematicLoader] Failed to read " + file.getName() + ": " + e.getMessage());
            return null;
        }
        if (root.hasKey("Schematic", 10)) root = root.getCompoundTag("Schematic");

        int version = root.hasKey("Version") ? root.getInteger("Version") : 1;
        NBTTagCompound data = (version >= 3 && root.hasKey("Blocks", 10)) ? root.getCompoundTag("Blocks") : root;

        int width  = root.getShort("Width")  & 0xFFFF;
        int height = root.getShort("Height") & 0xFFFF;
        int length = root.getShort("Length") & 0xFFFF;
        if (width <= 0 || height <= 0 || length <= 0) {
            System.err.println("[SpongeSchematicLoader] Invalid dimensions in " + file.getName());
            return null;
        }

        int[] offset = root.hasKey("Offset") ? root.getIntArray("Offset") : new int[]{0, 0, 0};
        int originOffsetX = offset.length > 0 ? -offset[0] : 0;
        int originOffsetY = offset.length > 1 ? -offset[1] : 0;
        int originOffsetZ = offset.length > 2 ? -offset[2] : 0;

        String paletteKey = data.hasKey("Palette", 10) ? "Palette" : null;
        String blockDataKey = data.hasKey("BlockData") ? "BlockData" : (data.hasKey("Data") ? "Data" : null);
        if (paletteKey == null || blockDataKey == null) {
            System.err.println("[SpongeSchematicLoader] Missing Palette/BlockData in " + file.getName());
            return null;
        }

        NBTTagCompound paletteNbt = data.getCompoundTag(paletteKey);
        int paletteSize = 0;
        for (String key : paletteNbt.getKeySet()) {
            paletteSize = Math.max(paletteSize, paletteNbt.getInteger(key) + 1);
        }

        Set<String> unresolved = new LinkedHashSet<>();
        LegacyBlockResolver.Resolved[] palette = new LegacyBlockResolver.Resolved[paletteSize];
        for (String key : paletteNbt.getKeySet()) {
            int idx = paletteNbt.getInteger(key);
            palette[idx] = LegacyBlockResolver.resolve(key, unresolved);
        }
        lastUnresolved = new ArrayList<>(unresolved);

        byte[] blockDataBytes = data.getByteArray(blockDataKey);
        int total = width * height * length;
        int widthLength = width * length;

        // Decode the varint block-data stream and discard air in the same pass — an
        // intermediate "one int per voxel" buffer (regardless of air) is the single biggest
        // allocation for a large/dense schematic and has blown the heap on big builds before
        // any actual (non-air) block count is even known.
        IntBuffer tmpX = new IntBuffer();
        IntBuffer tmpY = new IntBuffer();
        IntBuffer tmpZ = new IntBuffer();
        List<IBlockState>    tmpSt      = new ArrayList<>();
        List<NBTTagCompound> tmpExtraTe = new ArrayList<>();
        Map<BlockPos, Integer> posToIdx = new HashMap<>();

        int cursor = 0;
        for (int index = 0; index < total; index++) {
            int value = 0, shift = 0;
            while (true) {
                if (cursor >= blockDataBytes.length) {
                    System.err.println("[SpongeSchematicLoader] BlockData truncated in " + file.getName());
                    return null;
                }
                byte b = blockDataBytes[cursor++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }

            LegacyBlockResolver.Resolved resolved = (value >= 0 && value < palette.length) ? palette[value] : null;
            if (resolved == null || resolved.state.getBlock() == net.minecraft.init.Blocks.AIR) continue;

            int y =  index / widthLength;
            int z = (index % widthLength) / width;
            int x =  index % width;

            posToIdx.put(new BlockPos(x, y, z), tmpSt.size());
            tmpX.add(x); tmpY.add(y); tmpZ.add(z);
            tmpSt.add(resolved.state);
            tmpExtraTe.add(resolved.extraTeFields);
        }

        int count = tmpSt.size();
        int[] relX = tmpX.toArray();
        int[] relY = tmpY.toArray();
        int[] relZ = tmpZ.toArray();
        IBlockState[]    states  = tmpSt.toArray(new IBlockState[0]);
        NBTTagCompound[] tileNbt = new NBTTagCompound[count];

        String teListKey = data.hasKey("BlockEntities", 9) ? "BlockEntities" : (data.hasKey("TileEntities", 9) ? "TileEntities" : null);
        if (teListKey != null) {
            NBTTagList teList = data.getTagList(teListKey, 10);
            for (int i = 0; i < teList.tagCount(); i++) {
                NBTTagCompound teNbt = teList.getCompoundTagAt(i);
                if (!teNbt.hasKey("Pos", 11) || !teNbt.hasKey("Id", 8)) continue;
                int[] pos = teNbt.getIntArray("Pos");
                if (pos.length < 3) continue;
                BlockPos bp = new BlockPos(pos[0], pos[1], pos[2]);
                Integer blockIdx = posToIdx.get(bp);
                if (blockIdx == null) continue;

                NBTTagCompound copy = teNbt.copy();
                copy.setString("id", teNbt.getString("Id"));
                copy.removeTag("Id");
                copy.removeTag("Pos");
                if (copy.hasKey("SkullOwner", 10)) {
                    NBTBase owner = copy.getTag("SkullOwner");
                    copy.setTag("Owner", owner);
                    copy.removeTag("SkullOwner");
                }
                tileNbt[blockIdx] = copy;
            }
        }

        for (int i = 0; i < count; i++) {
            NBTTagCompound extraTeFields = tmpExtraTe.get(i);
            if (extraTeFields == null) continue;
            if (tileNbt[i] == null) {
                tileNbt[i] = extraTeFields.copy();
            } else {
                for (String key : extraTeFields.getKeySet()) {
                    tileNbt[i].setTag(key, extraTeFields.getTag(key));
                }
            }
        }

        if (!unresolved.isEmpty()) {
            System.out.println("[SpongeSchematicLoader] " + unresolved.size()
                    + " distinct block state(s) had no 1.12.2 equivalent, replaced with air: " + unresolved);
        }
        System.out.println("[SpongeSchematicLoader] Loaded " + file.getName()
                + " v" + version + " " + width + "x" + height + "x" + length + " non-air=" + count);

        return new SwscSchematic(width, height, length,
                originOffsetX, originOffsetY, originOffsetZ,
                relX, relY, relZ, states, tileNbt);
    }

    /** Growable int[] — avoids the per-element boxing overhead of ArrayList&lt;Integer&gt; for large builds. */
    private static final class IntBuffer {
        private int[] data = new int[1024];
        private int size = 0;

        void add(int value) {
            if (size == data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size++] = value;
        }

        int[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }
}
