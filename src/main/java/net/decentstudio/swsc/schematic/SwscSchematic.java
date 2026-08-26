package net.decentstudio.swsc.schematic;

import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;

/**
 * In-memory SWSC schematic — only non-air blocks stored as parallel arrays.
 * tileNbt[i] is non-null when block i has tile-entity data (skulls, signs, etc.)
 */
public class SwscSchematic {

    public final int width;
    public final int height;
    public final int length;

    /**
     * Anchor point (player position at save time), relative to the min corner of the
     * selection, block-granularity. This is the WorldEdit-style paste anchor: on paste,
     * this point lands on the target position and the rest of the structure is placed
     * relative to it. Schematics read from a legacy v1/v2 file (no stored offset) or
     * from a plain MCEdit export default this to the min corner (0,0,0).
     */
    public final int originOffsetX;
    public final int originOffsetY;
    public final int originOffsetZ;

    public final int[] relX;
    public final int[] relY;
    public final int[] relZ;
    public final IBlockState[]    states;
    public final NBTTagCompound[] tileNbt; // parallel with above; null entry = no TE

    public SwscSchematic(int width, int height, int length,
                          int originOffsetX, int originOffsetY, int originOffsetZ,
                          int[] relX, int[] relY, int[] relZ,
                          IBlockState[] states, NBTTagCompound[] tileNbt) {
        this.width   = width;
        this.height  = height;
        this.length  = length;
        this.originOffsetX = originOffsetX;
        this.originOffsetY = originOffsetY;
        this.originOffsetZ = originOffsetZ;
        this.relX    = relX;
        this.relY    = relY;
        this.relZ    = relZ;
        this.states  = states;
        this.tileNbt = tileNbt;
    }

    public int nonAirCount() {
        return states.length;
    }
}
