package net.decentstudio.swsc.schematic;

import net.minecraft.block.state.IBlockState;

/**
 * In-memory SWSC schematic — only non-air blocks stored as parallel arrays.
 */
public class SwscSchematic {

    public final int width;
    public final int height;
    public final int length;

    public final int[] relX;
    public final int[] relY;
    public final int[] relZ;
    public final IBlockState[] states;

    SwscSchematic(int width, int height, int length,
                  int[] relX, int[] relY, int[] relZ, IBlockState[] states) {
        this.width  = width;
        this.height = height;
        this.length = length;
        this.relX   = relX;
        this.relY   = relY;
        this.relZ   = relZ;
        this.states = states;
    }

    public int nonAirCount() {
        return states.length;
    }
}
