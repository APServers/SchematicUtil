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
import net.minecraft.world.gen.structure.StructureBoundingBox;
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
    private static final Map<UUID, UndoData> LAST_PASTE = new ConcurrentHashMap<>();
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
                                      boolean includeAir, UUID playerUuid, String playerName) {
        if (!PENDING_PLAYERS.add(playerUuid)) return false;
        QUEUE.add(new PasteJob(schematic, anchor, rotation, includeAir, playerUuid, playerName));
        return true;
    }

    /** @return false if this player already has a schematic job queued/running, or has nothing to undo */
    public static boolean queueUndo(UUID playerUuid, String playerName) {
        UndoData data = LAST_PASTE.get(playerUuid);
        if (data == null) return false;
        if (!PENDING_PLAYERS.add(playerUuid)) return false;
        LAST_PASTE.remove(playerUuid);
        QUEUE.add(new UndoJob(data, playerUuid, playerName));
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
                        + "Saved " + outputFile.getAbsolutePath() + " (" + width + "x" + height + "x" + length
                        + ", " + count + " non-air blocks) in " + String.format("%.1f", elapsed) + "s"));
            }
        }
    }

    // ---- Paste: rotation + origin-anchored, tick-budgeted ----

    private static class PasteJob extends Job {
        private final SwscSchematic schematic;
        private final BlockPos anchor;
        private final Rotation rotation;
        private final boolean includeAir;

        // includeAir mode pastes over the schematic's full width*height*length volume
        // (WorldEdit's default, no -a flag) instead of only the stored non-air voxels —
        // this dense grid is built once from the schematic's sparse arrays.
        private IBlockState[] denseStates;
        private NBTTagCompound[] denseTileNbt;
        private int denseWidth, denseLength;

        private int total;
        private BlockPos[] savedPos;
        private IBlockState[] savedPrevState;
        private NBTTagCompound[] savedPrevTe;

        // Bounding box of everything placed so far, swept for scheduled ticks after every
        // tick's batch — without this, a gravity block (sand/gravel) placed before the block
        // that will support it (later in this same multi-tick paste) can fall away mid-paste.
        private int minX, minY, minZ, maxX, maxY, maxZ;
        private boolean anyPlaced;

        PasteJob(SwscSchematic schematic, BlockPos anchor, Rotation rotation, boolean includeAir,
                 UUID playerUuid, String playerName) {
            super(playerUuid, playerName);
            this.schematic = schematic;
            this.anchor = anchor;
            this.rotation = rotation;
            this.includeAir = includeAir;
        }

        @Override
        void initialize() {
            if (includeAir) {
                denseWidth = schematic.width;
                denseLength = schematic.length;
                total = schematic.width * schematic.height * schematic.length;
                denseStates = new IBlockState[total];
                Arrays.fill(denseStates, Blocks.AIR.getDefaultState());
                denseTileNbt = new NBTTagCompound[total];
                int nonAir = schematic.nonAirCount();
                for (int i = 0; i < nonAir; i++) {
                    int idx = schematic.relY[i] * (denseWidth * denseLength)
                            + schematic.relZ[i] * denseWidth + schematic.relX[i];
                    denseStates[idx] = schematic.states[i];
                    denseTileNbt[idx] = schematic.tileNbt[i];
                }
            } else {
                total = schematic.nonAirCount();
            }
            savedPos = new BlockPos[total];
            savedPrevState = new IBlockState[total];
            savedPrevTe = new NBTTagCompound[total];
        }

        @Override
        boolean processTick(World world) {
            int end = Math.min(cursor + PASTE_BLOCKS_PER_TICK, total);
            while (cursor < end) {
                int rx, ry, rz;
                IBlockState rawState;
                NBTTagCompound rawTe;
                if (includeAir) {
                    int y =  cursor / (denseWidth * denseLength);
                    int z = (cursor % (denseWidth * denseLength)) / denseWidth;
                    int x =  cursor % denseWidth;
                    rx = x - schematic.originOffsetX;
                    ry = y - schematic.originOffsetY;
                    rz = z - schematic.originOffsetZ;
                    rawState = denseStates[cursor];
                    rawTe = denseTileNbt[cursor];
                } else {
                    rx = schematic.relX[cursor] - schematic.originOffsetX;
                    ry = schematic.relY[cursor] - schematic.originOffsetY;
                    rz = schematic.relZ[cursor] - schematic.originOffsetZ;
                    rawState = schematic.states[cursor];
                    rawTe = schematic.tileNbt[cursor];
                }
                BlockPos pos = anchor.add(rotate(rx, ry, rz, rotation));

                savedPos[cursor] = pos;
                savedPrevState[cursor] = world.getBlockState(pos);
                TileEntity existingTe = world.getTileEntity(pos);
                if (existingTe != null) {
                    savedPrevTe[cursor] = existingTe.writeToNBT(new NBTTagCompound());
                }

                IBlockState state = rawState.withRotation(rotation);
                // flag 18 = 2 (send to clients) | 16 (no observer updates). Neighbour
                // notifications (flag 1) are intentionally omitted — they'd trigger
                // neighbourChanged on torches/signs which call canPlaceAt and drop
                // themselves if their support block hasn't been placed yet.
                world.setBlockState(pos, state, 18);
                applyNbt(world, pos, rawTe);

                if (!anyPlaced) {
                    anyPlaced = true;
                    minX = maxX = pos.getX();
                    minY = maxY = pos.getY();
                    minZ = maxZ = pos.getZ();
                } else {
                    minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
                    minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
                    minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
                }
                cursor++;
            }
            if (anyPlaced) {
                // Cancel any fall/liquid ticks scheduled inside the region so far this tick,
                // same trick vanilla's Template uses to keep structures intact while loading.
                world.getPendingBlockUpdates(new StructureBoundingBox(minX, minY, minZ, maxX, maxY, maxZ), true);
            }
            sendProgress(total, "Pasting");
            return cursor >= total;
        }

        private void applyNbt(World world, BlockPos pos, NBTTagCompound nbt) {
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
            LAST_PASTE.put(playerUuid, new UndoData(savedPos, savedPrevState, savedPrevTe));

            EntityPlayerMP player = FMLCommonHandler.instance()
                    .getMinecraftServerInstance().getPlayerList().getPlayerByUUID(playerUuid);
            if (player == null) return;
            double elapsed = (System.currentTimeMillis() - startMs) / 1000.0;
            player.sendMessage(new TextComponentString(TextFormatting.GREEN
                    + "Paste complete. " + total + " blocks in "
                    + String.format("%.1f", elapsed) + "s"));
        }
    }

    // ---- Undo: restores the block/tile-entity state captured right before the last paste ----

    private static final class UndoData {
        final BlockPos[] positions;
        final IBlockState[] prevStates;
        final NBTTagCompound[] prevTileNbt;

        UndoData(BlockPos[] positions, IBlockState[] prevStates, NBTTagCompound[] prevTileNbt) {
            this.positions = positions;
            this.prevStates = prevStates;
            this.prevTileNbt = prevTileNbt;
        }
    }

    private static class UndoJob extends Job {
        private final UndoData data;

        private int minX, minY, minZ, maxX, maxY, maxZ;
        private boolean anyPlaced;

        UndoJob(UndoData data, UUID playerUuid, String playerName) {
            super(playerUuid, playerName);
            this.data = data;
        }

        @Override
        boolean processTick(World world) {
            int total = data.positions.length;
            int end = Math.min(cursor + PASTE_BLOCKS_PER_TICK, total);
            while (cursor < end) {
                BlockPos pos = data.positions[cursor];
                world.setBlockState(pos, data.prevStates[cursor], 18);
                NBTTagCompound teNbt = data.prevTileNbt[cursor];
                if (teNbt != null) {
                    TileEntity te = world.getTileEntity(pos);
                    if (te != null) {
                        NBTTagCompound copy = teNbt.copy();
                        copy.setInteger("x", pos.getX());
                        copy.setInteger("y", pos.getY());
                        copy.setInteger("z", pos.getZ());
                        te.readFromNBT(copy);
                        world.markChunkDirty(pos, te);
                    }
                }

                if (!anyPlaced) {
                    anyPlaced = true;
                    minX = maxX = pos.getX();
                    minY = maxY = pos.getY();
                    minZ = maxZ = pos.getZ();
                } else {
                    minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
                    minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
                    minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
                }
                cursor++;
            }
            if (anyPlaced) {
                world.getPendingBlockUpdates(new StructureBoundingBox(minX, minY, minZ, maxX, maxY, maxZ), true);
            }
            sendProgress(total, "Undoing");
            return cursor >= total;
        }

        @Override
        void onFinished() {
            EntityPlayerMP player = FMLCommonHandler.instance()
                    .getMinecraftServerInstance().getPlayerList().getPlayerByUUID(playerUuid);
            if (player == null) return;
            double elapsed = (System.currentTimeMillis() - startMs) / 1000.0;
            player.sendMessage(new TextComponentString(TextFormatting.GREEN
                    + "Undo complete. " + data.positions.length + " blocks restored in "
                    + String.format("%.1f", elapsed) + "s"));
        }
    }
}
