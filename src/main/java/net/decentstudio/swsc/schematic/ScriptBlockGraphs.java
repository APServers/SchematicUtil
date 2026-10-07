package net.decentstudio.swsc.schematic;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Makes narutoscripts' ScriptableBlock survive a schematic round trip.
 * <p>
 * The tile entity's own NBT does not contain the scripts. It only lists {@code GraphIds} and
 * {@code SourceX/Y/Z} (the position the graphs live at); the graphs themselves are files in
 * {@code <world>/data/scriptable_blocks/[DIM<dim>/]<x>_<y>_<z>/graph_<id>.dat}. Vanilla Ctrl+Pick Block
 * works because the copy is placed in the same world, so the block can copy the files from
 * {@code SourceX/Y/Z} on its first tick. A schematic can be pasted elsewhere (another world,
 * another server), where that source no longer exists.
 * <p>
 * So: on save the graph files are embedded into the tile NBT under {@link #KEY} (and
 * {@code SourceX/Y/Z} dropped), and on paste they are written back as the files of the block's
 * new position <i>before</i> {@code readFromNBT}, so the block just loads them on its first tick
 * and never takes its "copied from SourceX/Y/Z" branch. This class depends on the mod only
 * through those NBT keys and file layout, so it needs no dependency on narutoscripts.
 */
public final class ScriptBlockGraphs {

    private static final String KEY = "_swsc_graphs";
    private static final String FOLDER = "scriptable_blocks";

    private ScriptBlockGraphs() {}

    /**
     * Call on a freshly serialized tile entity NBT at world position {@code pos}, before writing
     * it into a schematic (also fine for undo snapshots). No-op for anything that isn't a
     * scriptable block / door upper half.
     */
    public static void embed(World world, BlockPos pos, NBTTagCompound nbt) {
        // Door upper half: LowerX/Y/Z is an absolute position, and always the block right below
        // (which is also the block's own fallback when it's absent), so just drop it.
        if (nbt.hasKey("LowerX") && nbt.hasKey("LowerY") && nbt.hasKey("LowerZ")) {
            nbt.removeTag("LowerX");
            nbt.removeTag("LowerY");
            nbt.removeTag("LowerZ");
        }

        if (!nbt.hasKey("GraphIds", 9) || !nbt.hasKey("SourceX")) return;

        NBTTagCompound graphs = new NBTTagCompound();
        NBTTagList ids = nbt.getTagList("GraphIds", 10);
        for (int i = 0; i < ids.tagCount(); i++) {
            String id = ids.getCompoundTagAt(i).getString("id");
            if (!isSafeId(id)) continue;
            NBTTagCompound graph = readGraph(world, pos, id);
            if (graph != null) graphs.setTag(id, graph);
        }
        nbt.setTag(KEY, graphs);
        nbt.removeTag("SourceX");
        nbt.removeTag("SourceY");
        nbt.removeTag("SourceZ");
    }

    /**
     * Call on the (already copied) tile NBT right before {@code readFromNBT} at {@code pos}.
     * Writes embedded graphs out as that position's graph files and strips the private tag.
     * Schematics saved before this existed have no embedded graphs and are left untouched.
     */
    public static void restore(World world, BlockPos pos, NBTTagCompound nbt) {
        if (!nbt.hasKey(KEY, 10)) return;
        NBTTagCompound graphs = nbt.getCompoundTag(KEY);
        nbt.removeTag(KEY);
        nbt.removeTag("SourceX");
        nbt.removeTag("SourceY");
        nbt.removeTag("SourceZ");

        File dir = blockDir(world, pos);
        File[] old = dir.listFiles((d, name) -> name.startsWith("graph_") && name.endsWith(".dat"));
        if (old != null) for (File f : old) f.delete();

        for (String id : graphs.getKeySet()) {
            if (!isSafeId(id)) continue;
            writeGraph(world, pos, id, graphs.getCompoundTag(id));
        }
    }

    // Graph ids end up in a file name; a hand-edited schematic must not be able to escape the dir.
    private static boolean isSafeId(String id) {
        return !id.isEmpty() && !id.contains("..") && id.indexOf('/') < 0 && id.indexOf('\\') < 0
                && id.indexOf(':') < 0 && id.indexOf('\0') < 0;
    }

    // Same layout as narutoscripts' BlockGraphFileManager: overworld at the root, other dimensions
    // in a DIM<id> subfolder. Keep the two in sync.
    private static File blockDir(World world, BlockPos pos) {
        File root = new File(new File(world.getSaveHandler().getWorldDirectory(), "data"), FOLDER);
        int dim = world.provider.getDimension();
        if (dim != 0) root = new File(root, "DIM" + dim);
        File dir = new File(root, pos.getX() + "_" + pos.getY() + "_" + pos.getZ());
        dir.mkdirs();
        return dir;
    }

    private static File graphFile(World world, BlockPos pos, String id) {
        return new File(blockDir(world, pos), "graph_" + id + ".dat");
    }

    private static NBTTagCompound readGraph(World world, BlockPos pos, String id) {
        File f = graphFile(world, pos, id);
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            return CompressedStreamTools.readCompressed(in);
        } catch (IOException e) {
            System.err.println("[SwscTool] Failed to read script graph '" + id + "' at " + pos + ": " + e);
            return null;
        }
    }

    private static void writeGraph(World world, BlockPos pos, String id, NBTTagCompound graph) {
        try (FileOutputStream out = new FileOutputStream(graphFile(world, pos, id))) {
            CompressedStreamTools.writeCompressed(graph, out);
        } catch (IOException e) {
            System.err.println("[SwscTool] Failed to write script graph '" + id + "' at " + pos + ": " + e);
        }
    }
}
