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

    /**
     * Non-player entities captured alongside the blocks (e.g. placed NPCs). Empty arrays when
     * the schematic was saved/loaded without entity capture (older files, or the McEdit/Sponge
     * loaders, which don't support this). Positions are relative to the same min-corner frame as
     * relX/relY/relZ, but double-precision since entities aren't block-aligned.
     */
    public final double[] entityRelX;
    public final double[] entityRelY;
    public final double[] entityRelZ;
    public final float[]  entityYaw;
    public final float[]  entityPitch;
    public final NBTTagCompound[] entityNbt;

    public SwscSchematic(int width, int height, int length,
                          int originOffsetX, int originOffsetY, int originOffsetZ,
                          int[] relX, int[] relY, int[] relZ,
                          IBlockState[] states, NBTTagCompound[] tileNbt) {
        this(width, height, length, originOffsetX, originOffsetY, originOffsetZ,
                relX, relY, relZ, states, tileNbt,
                new double[0], new double[0], new double[0], new float[0], new float[0], new NBTTagCompound[0]);
    }

    public SwscSchematic(int width, int height, int length,
                          int originOffsetX, int originOffsetY, int originOffsetZ,
                          int[] relX, int[] relY, int[] relZ,
                          IBlockState[] states, NBTTagCompound[] tileNbt,
                          double[] entityRelX, double[] entityRelY, double[] entityRelZ,
                          float[] entityYaw, float[] entityPitch, NBTTagCompound[] entityNbt) {
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
        this.entityRelX = entityRelX;
        this.entityRelY = entityRelY;
        this.entityRelZ = entityRelZ;
        this.entityYaw = entityYaw;
        this.entityPitch = entityPitch;
        this.entityNbt = entityNbt;
    }

    public int nonAirCount() {
        return states.length;
    }

    public int entityCount() {
        return entityNbt.length;
    }
}
