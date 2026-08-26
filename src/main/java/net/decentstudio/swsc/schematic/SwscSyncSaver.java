package net.decentstudio.swsc.schematic;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Synchronous, single-tick save: scans the whole selection and writes it in one call, with
 * origin offset fixed at (0,0,0) — i.e. the file's anchor is the selection's min corner, same
 * as a plain (no-WorldEdit-tag) legacy v1/v2 schematic. Intended for small, admin-triggered
 * captures (e.g. authoring a fixed-layout template) where tick-budgeting isn't worth the
 * complexity. For anything player-facing or potentially large, use the tick-budgeted
 * {@link net.decentstudio.swsc.tool.SwscToolManager#queueSave} instead.
 */
public final class SwscSyncSaver {

    private SwscSyncSaver() {}

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
        int total  = width * height * length;

        Map<String, Integer> palette = new LinkedHashMap<>();
        palette.put(SwscSchematicIO.blockKey(Blocks.AIR.getDefaultState()), 0);
        short[] voxelIndices = new short[total];
        List<int[]>          teCoords = new ArrayList<>();
        List<NBTTagCompound> teNbts   = new ArrayList<>();

        int cursor = 0;
        for (int y = 0; y < height; y++)
            for (int z = 0; z < length; z++)
                for (int x = 0; x < width; x++, cursor++) {
                    BlockPos abs = new BlockPos(minX + x, minY + y, minZ + z);
                    IBlockState state = world.getBlockState(abs);
                    if (state.getBlock() == Blocks.AIR) continue;

                    String key = SwscSchematicIO.blockKey(state);
                    Integer idx = palette.get(key);
                    if (idx == null) {
                        idx = palette.size();
                        palette.put(key, idx);
                    }
                    voxelIndices[cursor] = idx.shortValue();

                    TileEntity te = world.getTileEntity(abs);
                    if (te == null) continue;
                    NBTTagCompound nbt = te.writeToNBT(new NBTTagCompound());
                    nbt.removeTag("x"); nbt.removeTag("y"); nbt.removeTag("z");
                    if (nbt.hasKey("masterPos", 4)) {
                        BlockPos masterAbs = BlockPos.fromLong(nbt.getLong("masterPos"));
                        int dmx = masterAbs.getX() - abs.getX();
                        int dmy = masterAbs.getY() - abs.getY();
                        int dmz = masterAbs.getZ() - abs.getZ();
                        nbt.removeTag("masterPos");
                        nbt.setLong("_swsc_masterRelPos", new BlockPos(dmx, dmy, dmz).toLong());
                    }
                    teCoords.add(new int[]{x, y, z});
                    teNbts.add(nbt);
                }

        return SwscSchematicIO.write(outputFile, width, height, length,
                0, 0, 0, palette, voxelIndices, teCoords, teNbts);
    }
}
