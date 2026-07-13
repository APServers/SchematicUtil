package net.decentstudio.swsc.schematic;

import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;

/**
 * In-memory SWSC schematic — only non-air blocks stored as parallel arrays.
 * tileNbt[i] is non-null when block i has tile-entity data (skulls, signs, big_sign, etc.)
 */
public class SwscSchematic {

    public final int width;
    public final int height;
    public final int length;

    public final int[] relX;
    public final int[] relY;
    public final int[] relZ;
    public final IBlockState[]    states;
    public final NBTTagCompound[] tileNbt; // parallel with above; null entry = no TE

    SwscSchematic(int width, int height, int length,
                  int[] relX, int[] relY, int[] relZ,
                  IBlockState[] states, NBTTagCompound[] tileNbt) {
        this.width   = width;
        this.height  = height;
        this.length  = length;
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
