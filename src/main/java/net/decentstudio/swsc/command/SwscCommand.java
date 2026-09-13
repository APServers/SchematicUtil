package net.decentstudio.swsc.command;

import net.decentstudio.swsc.schematic.McEditSchematicLoader;
import net.decentstudio.swsc.schematic.SpongeSchematicLoader;
import net.decentstudio.swsc.schematic.SwscSchematic;
import net.decentstudio.swsc.schematic.SwscSchematicIO;
import net.decentstudio.swsc.selection.SwscSelectionManager;
import net.decentstudio.swsc.tool.SwscToolManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SwscCommand extends CommandBase {

    @Override
    public String getName() { return "swsc"; }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/swsc <pos1|pos2|info|save <name>|load <name> [rotation] [x y z] [-a]|convert <name>|undo|list>";
    }

    @Override
    public int getRequiredPermissionLevel() { return 2; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Only players can use this command."));
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;

        if (args.length == 0) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + getUsage(sender)));
            return;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "pos1": {
                BlockPos pos = player.getPosition();
                SwscSelectionManager.setPos1(player.getUniqueID(), pos);
                msg(player, TextFormatting.GREEN, "Pos1: " + fmt(pos));
                break;
            }
            case "pos2": {
                BlockPos pos = player.getPosition();
                SwscSelectionManager.setPos2(player.getUniqueID(), pos);
                msg(player, TextFormatting.GREEN, "Pos2: " + fmt(pos));
                break;
            }
            case "info": {
                BlockPos p1 = SwscSelectionManager.getPos1(player.getUniqueID());
                BlockPos p2 = SwscSelectionManager.getPos2(player.getUniqueID());
                if (p1 == null || p2 == null) {
                    msg(player, TextFormatting.YELLOW, "Pos1: " + fmt(p1) + "  Pos2: " + fmt(p2));
                    break;
                }
                int dx = Math.abs(p1.getX() - p2.getX()) + 1;
                int dy = Math.abs(p1.getY() - p2.getY()) + 1;
                int dz = Math.abs(p1.getZ() - p2.getZ()) + 1;
                msg(player, TextFormatting.YELLOW, "Pos1: " + fmt(p1) + "  Pos2: " + fmt(p2)
                        + "  Size: " + dx + "x" + dy + "x" + dz + " (" + (dx * dy * dz) + " blocks)");
                break;
            }
            case "save": {
                if (args.length < 2) {
                    msg(player, TextFormatting.RED, "Usage: /swsc save <name>");
                    break;
                }
                BlockPos p1 = SwscSelectionManager.getPos1(player.getUniqueID());
                BlockPos p2 = SwscSelectionManager.getPos2(player.getUniqueID());
                if (p1 == null || p2 == null) {
                    msg(player, TextFormatting.RED, "Set pos1 and pos2 first.");
                    break;
                }
                String name = sanitize(args[1]);
                File outFile = schematicFile(server, name);
                boolean queued = SwscToolManager.queueSave(
                        p1, p2, player.getPosition(), outFile, player.getUniqueID(), player.getName());
                if (!queued) {
                    msg(player, TextFormatting.RED, "You already have a schematic job running. Wait for it to finish.");
                    break;
                }
                msg(player, TextFormatting.YELLOW, "Saving " + name + ".swsch queued...");
                break;
            }
            case "load": {
                if (args.length < 2) {
                    msg(player, TextFormatting.RED, "Usage: /swsc load <name> [rotation] [x y z] [-a]");
                    break;
                }
                String name = args[1];
                File file = resolveSchematicFile(server, name);
                if (file == null) {
                    msg(player, TextFormatting.RED, "Schematic not found: " + name);
                    break;
                }

                // "-a" (matches WorldEdit's //paste -a) skips air instead of pasting it —
                // can appear anywhere after the name. Default (no flag) pastes air too.
                boolean includeAir = true;
                List<String> rest = new ArrayList<>();
                for (int i = 2; i < args.length; i++) {
                    if ("-a".equalsIgnoreCase(args[i])) includeAir = false;
                    else rest.add(args[i]);
                }

                Rotation rotation = Rotation.NONE;
                BlockPos anchor = player.getPosition();
                int extra = rest.size();
                if (extra == 1) {
                    Rotation r = parseRotation(rest.get(0));
                    if (r == null) { msg(player, TextFormatting.RED, "Unknown rotation: " + rest.get(0)); break; }
                    rotation = r;
                } else if (extra == 3) {
                    anchor = new BlockPos(
                            parseInt(rest.get(0), Integer.MIN_VALUE, Integer.MAX_VALUE),
                            parseInt(rest.get(1), 0, 255),
                            parseInt(rest.get(2), Integer.MIN_VALUE, Integer.MAX_VALUE));
                } else if (extra == 4) {
                    Rotation r = parseRotation(rest.get(0));
                    if (r == null) { msg(player, TextFormatting.RED, "Unknown rotation: " + rest.get(0)); break; }
                    rotation = r;
                    anchor = new BlockPos(
                            parseInt(rest.get(1), Integer.MIN_VALUE, Integer.MAX_VALUE),
                            parseInt(rest.get(2), 0, 255),
                            parseInt(rest.get(3), Integer.MIN_VALUE, Integer.MAX_VALUE));
                } else if (extra != 0) {
                    msg(player, TextFormatting.RED, "Usage: /swsc load <name> [rotation] [x y z] [-a]");
                    break;
                }

                SwscSchematic schem = loadAny(file);
                if (schem == null) {
                    msg(player, TextFormatting.RED, "Failed to load schematic. Check server console.");
                    break;
                }
                warnUnresolved(player, file);

                boolean queued = SwscToolManager.queuePaste(
                        schem, anchor, rotation, includeAir, player.getUniqueID(), player.getName());
                if (!queued) {
                    msg(player, TextFormatting.RED, "You already have a schematic job running. Wait for it to finish.");
                    break;
                }
                int blockCount = includeAir ? schem.width * schem.height * schem.length : schem.nonAirCount();
                msg(player, TextFormatting.YELLOW, "Loading " + file.getName() + " at " + fmt(anchor)
                        + " rotation=" + rotation + (includeAir ? "" : " (skipping air)")
                        + " (" + blockCount + " blocks) queued...");
                break;
            }
            case "convert": {
                if (args.length < 2) {
                    msg(player, TextFormatting.RED, "Usage: /swsc convert <name>");
                    break;
                }
                File src = resolveSchematicFile(server, args[1]);
                if (src == null || !src.getName().endsWith(".schem")) {
                    msg(player, TextFormatting.RED, "Not a .schem file: " + args[1]);
                    break;
                }
                SwscSchematic schem = SpongeSchematicLoader.load(src);
                if (schem == null) {
                    msg(player, TextFormatting.RED, "Failed to read " + src.getName() + ". Check server console.");
                    break;
                }
                warnUnresolved(player, src);

                String baseName = src.getName().substring(0, src.getName().length() - ".schem".length());
                File outFile = schematicFile(server, sanitize(baseName));
                Map<String, Integer> palette = new LinkedHashMap<>();
                palette.put(SwscSchematicIO.blockKey(net.minecraft.init.Blocks.AIR.getDefaultState()), 0);
                int total = schem.width * schem.height * schem.length;
                short[] voxelIndices = new short[total];
                for (int i = 0; i < schem.nonAirCount(); i++) {
                    int voxel = schem.relY[i] * (schem.width * schem.length) + schem.relZ[i] * schem.width + schem.relX[i];
                    String key = SwscSchematicIO.blockKey(schem.states[i]);
                    Integer idx = palette.get(key);
                    if (idx == null) { idx = palette.size(); palette.put(key, idx); }
                    voxelIndices[voxel] = idx.shortValue();
                }
                List<int[]> teCoords = new ArrayList<>();
                List<net.minecraft.nbt.NBTTagCompound> teNbts = new ArrayList<>();
                for (int i = 0; i < schem.nonAirCount(); i++) {
                    if (schem.tileNbt[i] == null) continue;
                    teCoords.add(new int[]{schem.relX[i], schem.relY[i], schem.relZ[i]});
                    teNbts.add(schem.tileNbt[i]);
                }
                int count = SwscSchematicIO.write(outFile, schem.width, schem.height, schem.length,
                        schem.originOffsetX, schem.originOffsetY, schem.originOffsetZ,
                        palette, voxelIndices, teCoords, teNbts);
                if (count < 0) {
                    msg(player, TextFormatting.RED, "Failed to write " + outFile.getName() + ". Check server console.");
                } else {
                    msg(player, TextFormatting.GREEN, "Converted " + src.getName() + " -> " + outFile.getName()
                            + " (" + count + " non-air blocks)");
                }
                break;
            }
            case "undo": {
                boolean queued = SwscToolManager.queueUndo(player.getUniqueID(), player.getName());
                if (!queued) {
                    msg(player, TextFormatting.RED, "Nothing to undo, or you already have a schematic job running.");
                    break;
                }
                msg(player, TextFormatting.YELLOW, "Undoing last paste...");
                break;
            }
            case "list": {
                File dir = schematicDir(server);
                List<String> names = listNames(dir);
                if (names.isEmpty()) {
                    msg(player, TextFormatting.YELLOW, "No saved schematics in " + dir.getAbsolutePath());
                    break;
                }
                msg(player, TextFormatting.YELLOW, dir.getAbsolutePath() + " -> " + String.join(", ", names));
                break;
            }
            default:
                msg(player, TextFormatting.RED, getUsage(sender));
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "pos1", "pos2", "info", "save", "load", "convert", "undo", "list");
        }
        String sub = args[0].toLowerCase();
        if (args.length == 2 && ("load".equals(sub) || "save".equals(sub) || "convert".equals(sub))) {
            return getListOfStringsMatchingLastWord(args, listNames(schematicDir(server)));
        }
        if (args.length == 3 && "load".equals(sub)) {
            return getListOfStringsMatchingLastWord(args, "none", "cw90", "cw180", "ccw90");
        }
        return Collections.emptyList();
    }

    // ---- helpers ----

    private static SwscSchematic loadAny(File file) {
        String name = file.getName();
        if (name.endsWith(".schem")) return SpongeSchematicLoader.load(file);
        if (name.endsWith(".schematic")) return McEditSchematicLoader.load(file);
        return SwscSchematicIO.readSwsch(file);
    }

    /** .schem loads route through the flattened-name resolver — warn the player if anything had no 1.12.2 match. */
    private static void warnUnresolved(EntityPlayerMP player, File file) {
        if (!file.getName().endsWith(".schem")) return;
        List<String> unresolved = SpongeSchematicLoader.lastUnresolved;
        if (unresolved.isEmpty()) return;
        msg(player, TextFormatting.GOLD, unresolved.size() + " block state(s) had no 1.12.2 equivalent "
                + "and were replaced with air: " + String.join(", ", unresolved));
    }

    private static List<String> listNames(File dir) {
        if (!dir.exists() || dir.listFiles() == null) return Collections.emptyList();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".swsch") || n.endsWith(".schematic") || n.endsWith(".schem"));
        if (files == null || files.length == 0) return Collections.emptyList();
        return Arrays.stream(files)
                .map(File::getName)
                .collect(Collectors.toList());
    }

    private static File schematicDir(MinecraftServer server) {
        return new File(server.getDataDirectory(), "config/swsc");
    }

    private static File schematicFile(MinecraftServer server, String name) {
        return new File(schematicDir(server), name + ".swsch");
    }

    private File resolveSchematicFile(MinecraftServer server, String name) {
        File dir = schematicDir(server);
        if (name.contains(".")) {
            File f = new File(dir, name);
            if (f.exists()) return f;
        }
        File swsch = new File(dir, name + ".swsch");
        if (swsch.exists()) return swsch;
        File mcedit = new File(dir, name + ".schematic");
        if (mcedit.exists()) return mcedit;
        File sponge = new File(dir, name + ".schem");
        if (sponge.exists()) return sponge;
        return null;
    }

    private static Rotation parseRotation(String s) {
        switch (s.toLowerCase()) {
            case "0": case "none":  return Rotation.NONE;
            case "90": case "cw90": return Rotation.CLOCKWISE_90;
            case "180": case "cw180": return Rotation.CLOCKWISE_180;
            case "270": case "ccw90": return Rotation.COUNTERCLOCKWISE_90;
            default: return null;
        }
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private static String fmt(BlockPos p) {
        return p == null ? "not set" : p.getX() + " " + p.getY() + " " + p.getZ();
    }

    private static void msg(EntityPlayerMP player, TextFormatting color, String text) {
        player.sendMessage(new TextComponentString(color + text));
    }
}
