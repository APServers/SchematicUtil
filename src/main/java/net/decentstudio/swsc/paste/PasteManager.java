package net.decentstudio.swsc.paste;

import net.decentstudio.swsc.schematic.SwscSchematic;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PasteManager {

    private static final int BLOCKS_PER_TICK  = 500;
    private static final int PROGRESS_INTERVAL_MS = 2000;

    private static final ConcurrentLinkedQueue<PasteJob> queue = new ConcurrentLinkedQueue<>();
    private static PasteJob active = null;

    public static void queue(SwscSchematic schematic, BlockPos origin,
                             UUID playerUuid, String playerName) {
        queue.add(new PasteJob(schematic, origin, playerUuid, playerName));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (active == null) {
            active = queue.poll();
            if (active != null) active.startMs = System.currentTimeMillis();
        }
        if (active == null) return;

        World world = FMLCommonHandler.instance().getMinecraftServerInstance().getWorld(0);
        if (world == null) return;

        boolean done = active.tick(world);
        if (done) {
            PasteJob finished = active;
            active = null;
            notifyPlayer(finished, world);
            active = queue.poll();
            if (active != null) active.startMs = System.currentTimeMillis();
        }
    }

    private static void notifyPlayer(PasteJob job, World world) {
        EntityPlayerMP player = FMLCommonHandler.instance()
                .getMinecraftServerInstance().getPlayerList()
                .getPlayerByUUID(job.playerUuid);
        if (player == null) return;
        long elapsed = System.currentTimeMillis() - job.startMs;
        player.sendMessage(new TextComponentString(
                TextFormatting.GREEN + "Вставка завершена. "
                + job.schematic.nonAirCount() + " блоков за "
                + String.format("%.1f", elapsed / 1000.0) + " сек"));
    }

    // ---- inner job ----

    private static class PasteJob {
        final SwscSchematic schematic;
        final BlockPos origin;
        final UUID playerUuid;
        final String playerName;
        long startMs;
        long lastProgressMs;
        int cursor = 0;
        int phase  = 0; // 0=place, 1=neighbour pass

        PasteJob(SwscSchematic schematic, BlockPos origin, UUID playerUuid, String playerName) {
            this.schematic  = schematic;
            this.origin     = origin;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
        }

        boolean tick(World world) {
            if (phase == 0) {
                int end = Math.min(cursor + BLOCKS_PER_TICK, schematic.nonAirCount());
                while (cursor < end) {
                    world.setBlockState(pos(cursor), schematic.states[cursor], 2);
                    cursor++;
                }
                sendProgress(world);
                if (cursor >= schematic.nonAirCount()) {
                    cursor = 0;
                    phase  = 1;
                }
                return false;
            }

            // Phase 1: notify neighbours for non-full-cube blocks (torches, signs, etc.)
            int checked = 0;
            while (cursor < schematic.nonAirCount() && checked < BLOCKS_PER_TICK) {
                IBlockState state = schematic.states[cursor];
                if (!state.isFullBlock()) {
                    world.setBlockState(pos(cursor), state, 3);
                }
                cursor++;
                checked++;
            }
            return cursor >= schematic.nonAirCount();
        }

        private BlockPos pos(int i) {
            return new BlockPos(
                    origin.getX() + schematic.relX[i],
                    origin.getY() + schematic.relY[i],
                    origin.getZ() + schematic.relZ[i]);
        }

        private void sendProgress(World world) {
            long now = System.currentTimeMillis();
            if (now - lastProgressMs < PROGRESS_INTERVAL_MS) return;
            lastProgressMs = now;
            EntityPlayerMP player = FMLCommonHandler.instance()
                    .getMinecraftServerInstance().getPlayerList()
                    .getPlayerByUUID(playerUuid);
            if (player == null) return;
            player.sendMessage(new TextComponentString(
                    TextFormatting.YELLOW + "Вставка: " + cursor
                    + " / " + schematic.nonAirCount() + " блоков"));
        }
    }
}
