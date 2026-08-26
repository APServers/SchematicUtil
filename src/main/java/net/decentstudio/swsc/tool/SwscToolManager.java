package net.decentstudio.swsc.tool;

import net.decentstudio.swsc.schematic.SwscSchematic;
import net.decentstudio.swsc.schematic.SwscSchematicIO;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Tick-budgeted engine for saving/pasting schematics: register one instance on the Forge
 * event bus and queue jobs from anywhere (a command, or another mod's own service code).
 * <p>
 * Only the world-scan (save) and block placement (paste) are spread across ticks; file
 * (de)serialization itself is pure in-memory work and happens in one shot once the scan
 * finishes — see {@link SwscSchematicIO}.
 */
public class SwscToolManager {

    private static final int SAVE_VOXELS_PER_TICK  = 4000;
    private static final int PASTE_BLOCKS_PER_TICK = 2000;
    private static final long PROGRESS_INTERVAL_MS  = 2000;

    private static final Queue<Job> QUEUE = new ConcurrentLinkedQueue<>();
    private static final Set<UUID> PENDING_PLAYERS = ConcurrentHashMap.newKeySet();
    private static Job active = null;

    /** @return false if this player already has a schematic job queued/running */
    public static boolean queueSave(BlockPos pos1, BlockPos pos2, BlockPos playerPos,
                                     File outputFile, UUID playerUuid, String playerName) {
        if (!PENDING_PLAYERS.add(playerUuid)) return false;
        QUEUE.add(new ScanSaveJob(pos1, pos2, playerPos, outputFile, playerUuid, playerName));
        return true;
    }

    /** @return false if this player already has a schematic job queued/running */
    public static boolean queuePaste(SwscSchematic schematic, BlockPos anchor, Rotation rotation,
                                      UUID playerUuid, String playerName) {
        if (!PENDING_PLAYERS.add(playerUuid)) return false;
        QUEUE.add(new PasteJob(schematic, anchor, rotation, playerUuid, playerName));
        return true;
    }

    public static boolean hasPendingJob(UUID playerUuid) {
        return PENDING_PLAYERS.contains(playerUuid);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (active == null) {
            active = QUEUE.poll();
            if (active != null) {
                active.startMs = System.currentTimeMillis();
                active.initialize();
            }
        }
        if (active == null) return;

        World world = FMLCommonHandler.instance().getMinecraftServerInstance().getWorld(0);
        if (world == null) return;

        boolean done;
        try {
            done = active.processTick(world);
        } catch (Exception e) {
            System.err.println("[SwscTool] Job failed for " + active.playerName + ": " + e);
            e.printStackTrace();
            notifyError(active, "Internal error, check server console.");
            PENDING_PLAYERS.remove(active.playerUuid);
            active = null;
            return;
        }

        if (done) {
            Job finished = active;
            try {
                finished.onFinished();
            } catch (Exception e) {
                System.err.println("[SwscTool] Finish step failed for " + finished.playerName + ": " + e);
                e.printStackTrace();
            }
            PENDING_PLAYERS.remove(finished.playerUuid);
            active = QUEUE.poll();
            if (active != null) {
                active.startMs = System.currentTimeMillis();
                active.initialize();
            }
        }
    }

    private static void notifyError(Job job, String text) {
        EntityPlayerMP player = FMLCommonHandler.instance()
                .getMinecraftServerInstance().getPlayerList().getPlayerByUUID(job.playerUuid);
        if (player != null) player.sendMessage(new TextComponentString(TextFormatting.RED + text));
    }

    /** Exact vanilla Template#transformedBlockPos formula (that method is private, so it's replicated here). Mirror is never used by this tool. */
    private static BlockPos rotate(int x, int y, int z, Rotation rotation) {
        switch (rotation) {
            case CLOCKWISE_90:        return new BlockPos(-z, y, x);
            case CLOCKWISE_180:       return new BlockPos(-x, y, -z);
            case COUNTERCLOCKWISE_90: return new BlockPos(z, y, -x);
            default:                  return new BlockPos(x, y, z);
        }
    }

    // ---- Job base ----

    private abstract static class Job {
        final UUID playerUuid;
        final String playerName;
        long startMs;
        long lastProgressMs;
        int cursor = 0;

        Job(UUID playerUuid, String playerName) {
            this.playerUuid = playerUuid;
            this.playerName = playerName;
        }

        void initialize() {}

        abstract boolean processTick(World world);

        /** Called once, right after processTick first returns true. */
        void onFinished() {}

        void sendProgress(int total, String verb) {
            long now = System.currentTimeMillis();
            if (now - lastProgressMs < PROGRESS_INTERVAL_MS) return;
            lastProgressMs = now;
            EntityPlayerMP player = FMLCommonHandler.instance()
                    .getMinecraftServerInstance().getPlayerList().getPlayerByUUID(playerUuid);
            if (player == null) return;
            player.sendMessage(new TextComponentString(TextFormatting.YELLOW
                    + verb + ": " + cursor + " / " + total));
        }
    }

    // ---- Save: tick-budgeted world scan, then a single in-memory write ----

    private static class ScanSaveJob extends Job {
        private final BlockPos min, max;
        private final BlockPos playerPos;
        private final File outputFile;
        private int width, height, length, total;

        private final Map<String, Integer> palette = new LinkedHashMap<>();
        private short[] voxelIndices;
        private final List<int[]> teCoords = new ArrayList<>();
        private final List<NBTTagCompound> teNbts = new ArrayList<>();

        ScanSaveJob(BlockPos pos1, BlockPos pos2, BlockPos playerPos,
                    File outputFile, UUID playerUuid, String playerName) {
            super(playerUuid, playerName);
            this.min = new BlockPos(
                    Math.min(pos1.getX(), pos2.getX()),
                    Math.min(pos1.getY(), pos2.getY()),
                    Math.min(pos1.getZ(), pos2.getZ()));
            this.max = new BlockPos(
                    Math.max(pos1.getX(), pos2.getX()),
                    Math.max(pos1.getY(), pos2.getY()),
                    Math.max(pos1.getZ(), pos2.getZ()));
            this.playerPos = playerPos;
            this.outputFile = outputFile;
        }

        @Override
        void initialize() {
            width  = max.getX() - min.getX() + 1;
            height = max.getY() - min.getY() + 1;
            length = max.getZ() - min.getZ() + 1;
            total = width * height * length;
            voxelIndices = new short[total];
            palette.put(SwscSchematicIO.blockKey(Blocks.AIR.getDefaultState()), 0);
        }

        @Override
        boolean processTick(World world) {
            int end = Math.min(cursor + SAVE_VOXELS_PER_TICK, total);
            while (cursor < end) {
                int x =  cursor % width;
                int z = (cursor / width) % length;
                int y =  cursor / (width * length);
                BlockPos abs = min.add(x, y, z);
                IBlockState state = world.getBlockState(abs);
                if (state.getBlock() != Blocks.AIR) {
                    String key = SwscSchematicIO.blockKey(state);
                    Integer idx = palette.get(key);
                    if (idx == null) {
                        idx = palette.size();
                        palette.put(key, idx);
                    }
                    voxelIndices[cursor] = idx.shortValue();

                    TileEntity te = world.getTileEntity(abs);
                    if (te != null) {
                        NBTTagCompound nbt = te.writeToNBT(new NBTTagCompound());
                        nbt.removeTag("x"); nbt.removeTag("y"); nbt.removeTag("z");
                        // Multiblock structures (e.g. tile entities that track a master
                        // position elsewhere in the same schematic) store an absolute world
                        // long. Convert to a relative delta so paste can reconstruct the
                        // correct absolute position regardless of where it's placed, and so
                        // rotation can be applied to it too.
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
                }
                cursor++;
            }
            sendProgress(total, "Scanning");
            return cursor >= total;
        }

        @Override
        void onFinished() {
            int originOffsetX = playerPos.getX() - min.getX();
            int originOffsetY = playerPos.getY() - min.getY();
            int originOffsetZ = playerPos.getZ() - min.getZ();

            int count = SwscSchematicIO.write(outputFile, width, height, length,
                    originOffsetX, originOffsetY, originOffsetZ,
                    palette, voxelIndices, teCoords, teNbts);

            EntityPlayerMP player = FMLCommonHandler.instance()
                    .getMinecraftServerInstance().getPlayerList().getPlayerByUUID(playerUuid);
            if (player == null) return;
            double elapsed = (System.currentTimeMillis() - startMs) / 1000.0;
            if (count < 0) {
                player.sendMessage(new TextComponentString(TextFormatting.RED
                        + "Failed to save " + outputFile.getName() + ". Check server console."));
            } else {
                player.sendMessage(new TextComponentString(TextFormatting.GREEN
                        + "Saved " + outputFile.getName() + " (" + width + "x" + height + "x" + length
                        + ", " + count + " non-air blocks) in " + String.format("%.1f", elapsed) + "s"));
            }
        }
    }

    // ---- Paste: rotation + origin-anchored, tick-budgeted ----

    private static class PasteJob extends Job {
        private final SwscSchematic schematic;
        private final BlockPos anchor;
        private final Rotation rotation;

        PasteJob(SwscSchematic schematic, BlockPos anchor, Rotation rotation,
                 UUID playerUuid, String playerName) {
            super(playerUuid, playerName);
            this.schematic = schematic;
            this.anchor = anchor;
            this.rotation = rotation;
        }

        @Override
        boolean processTick(World world) {
            int total = schematic.nonAirCount();
            int end = Math.min(cursor + PASTE_BLOCKS_PER_TICK, total);
            while (cursor < end) {
                int rx = schematic.relX[cursor] - schematic.originOffsetX;
                int ry = schematic.relY[cursor] - schematic.originOffsetY;
                int rz = schematic.relZ[cursor] - schematic.originOffsetZ;
                BlockPos pos = anchor.add(rotate(rx, ry, rz, rotation));

                IBlockState state = schematic.states[cursor].withRotation(rotation);
                // flag 18 = 2 (send to clients) | 16 (no observer updates). Neighbour
                // notifications (flag 1) are intentionally omitted — they'd trigger
                // neighbourChanged on torches/signs which call canPlaceAt and drop
                // themselves if their support block hasn't been placed yet.
                world.setBlockState(pos, state, 18);
                applyNbt(world, pos, cursor);
                cursor++;
            }
            sendProgress(total, "Pasting");
            return cursor >= total;
        }

        private void applyNbt(World world, BlockPos pos, int i) {
            NBTTagCompound nbt = schematic.tileNbt[i];
            if (nbt == null) return;
            TileEntity te = world.getTileEntity(pos);
            if (te == null) return;
            NBTTagCompound copy = nbt.copy();
            copy.setInteger("x", pos.getX());
            copy.setInteger("y", pos.getY());
            copy.setInteger("z", pos.getZ());
            if (copy.hasKey("_swsc_masterRelPos", 4)) {
                BlockPos delta = BlockPos.fromLong(copy.getLong("_swsc_masterRelPos"));
                BlockPos rotatedDelta = rotate(delta.getX(), delta.getY(), delta.getZ(), rotation);
                copy.setLong("masterPos", pos.add(rotatedDelta).toLong());
                copy.removeTag("_swsc_masterRelPos");
            }
            te.readFromNBT(copy);
            world.markChunkDirty(pos, te);
        }

        @Override
        void onFinished() {
            EntityPlayerMP player = FMLCommonHandler.instance()
                    .getMinecraftServerInstance().getPlayerList().getPlayerByUUID(playerUuid);
            if (player == null) return;
            double elapsed = (System.currentTimeMillis() - startMs) / 1000.0;
            player.sendMessage(new TextComponentString(TextFormatting.GREEN
                    + "Paste complete. " + schematic.nonAirCount() + " blocks in "
                    + String.format("%.1f", elapsed) + "s"));
        }
    }
}
