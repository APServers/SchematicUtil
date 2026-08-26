package net.decentstudio.swsc.selection;

import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-player pos1/pos2 selection for the schematic tool. */
public final class SwscSelectionManager {

    private static final Map<UUID, BlockPos> POS1 = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> POS2 = new ConcurrentHashMap<>();

    private SwscSelectionManager() {}

    public static void setPos1(UUID player, BlockPos pos) { POS1.put(player, pos); }
    public static void setPos2(UUID player, BlockPos pos) { POS2.put(player, pos); }
    public static BlockPos getPos1(UUID player) { return POS1.get(player); }
    public static BlockPos getPos2(UUID player) { return POS2.get(player); }
}
