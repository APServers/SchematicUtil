package net.decentstudio.swsc.command;

import net.decentstudio.swsc.paste.PasteManager;
import net.decentstudio.swsc.schematic.SwscLoader;
import net.decentstudio.swsc.schematic.SwscSaver;
import net.decentstudio.swsc.schematic.SwscSchematic;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.FMLCommonHandler;

import javax.annotation.Nullable;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SwscCommand extends CommandBase {

    private static final Map<UUID, BlockPos> POS1 = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> POS2 = new ConcurrentHashMap<>();

    @Override
    public String getName() { return "swsc"; }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/swsc <pos1|pos2|info|save <name>|load <name> [x y z]|list>";
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
                POS1.put(player.getUniqueID(), pos);
                msg(player, TextFormatting.GREEN, "Pos1: " + fmt(pos));
                break;
            }
            case "pos2": {
                BlockPos pos = player.getPosition();
                POS2.put(player.getUniqueID(), pos);
                msg(player, TextFormatting.GREEN, "Pos2: " + fmt(pos));
                break;
            }
            case "info": {
                BlockPos p1 = POS1.get(player.getUniqueID());
                BlockPos p2 = POS2.get(player.getUniqueID());
                if (p1 == null || p2 == null) {
                    msg(player, TextFormatting.YELLOW,
                            "Pos1: " + fmt(p1) + "  Pos2: " + fmt(p2));
                    break;
                }
                int dx = Math.abs(p1.getX() - p2.getX()) + 1;
                int dy = Math.abs(p1.getY() - p2.getY()) + 1;
                int dz = Math.abs(p1.getZ() - p2.getZ()) + 1;
                msg(player, TextFormatting.YELLOW,
                        "Pos1: " + fmt(p1) + "  Pos2: " + fmt(p2)
                        + "  Размер: " + dx + "x" + dy + "x" + dz
                        + " (" + (dx * dy * dz) + " блоков)");
                break;
            }
            case "save": {
                if (args.length < 2) {
                    msg(player, TextFormatting.RED, "Usage: /swsc save <name>");
                    break;
                }
                BlockPos p1 = POS1.get(player.getUniqueID());
                BlockPos p2 = POS2.get(player.getUniqueID());
                if (p1 == null || p2 == null) {
                    msg(player, TextFormatting.RED, "Сначала установи pos1 и pos2.");
                    break;
                }
                String name = sanitize(args[1]);
                File outFile = schematicFile(server, name);
                msg(player, TextFormatting.YELLOW, "Сохранение " + name + ".swsch ...");
                int count = SwscSaver.save(player.world, p1, p2, outFile);
                if (count < 0) {
                    msg(player, TextFormatting.RED, "Ошибка сохранения. Смотри консоль.");
                } else {
                    int dx = Math.abs(p1.getX() - p2.getX()) + 1;
                    int dy = Math.abs(p1.getY() - p2.getY()) + 1;
                    int dz = Math.abs(p1.getZ() - p2.getZ()) + 1;
                    msg(player, TextFormatting.GREEN,
                            "Сохранено: " + name + ".swsch  "
                            + dx + "x" + dy + "x" + dz
                            + "  non-air: " + count);
                }
                break;
            }
            case "load": {
                if (args.length < 2) {
                    msg(player, TextFormatting.RED, "Usage: /swsc load <name> [x y z]");
                    break;
                }
                String name = sanitize(args[1]);
                File inFile = schematicFile(server, name);
                if (!inFile.exists()) {
                    msg(player, TextFormatting.RED, "Файл не найден: " + name + ".swsch");
                    break;
                }
                SwscSchematic schem = SwscLoader.load(inFile);
                if (schem == null) {
                    msg(player, TextFormatting.RED, "Не удалось загрузить схематик. Смотри консоль.");
                    break;
                }
                BlockPos origin;
                if (args.length >= 5) {
                    int ox = parseInt(args[2], Integer.MIN_VALUE, Integer.MAX_VALUE);
                    int oy = parseInt(args[3], 0, 255);
                    int oz = parseInt(args[4], Integer.MIN_VALUE, Integer.MAX_VALUE);
                    origin = new BlockPos(ox, oy, oz);
                } else {
                    BlockPos p1 = POS1.get(player.getUniqueID());
                    if (p1 == null) {
                        msg(player, TextFormatting.RED, "Укажи координаты или установи pos1.");
                        break;
                    }
                    origin = p1;
                }
                PasteManager.queue(schem, origin, player.getUniqueID(), player.getName());
                msg(player, TextFormatting.YELLOW,
                        "Вставка " + name + ".swsch в " + fmt(origin)
                        + "  (" + schem.nonAirCount() + " блоков) поставлена в очередь.");
                break;
            }
            case "list": {
                File dir = schematicDir(server);
                if (!dir.exists() || dir.listFiles() == null) {
                    msg(player, TextFormatting.YELLOW, "Нет сохранённых схематиков.");
                    break;
                }
                File[] files = dir.listFiles((d, n) -> n.endsWith(".swsch"));
                if (files == null || files.length == 0) {
                    msg(player, TextFormatting.YELLOW, "Нет сохранённых схематиков.");
                    break;
                }
                String list = Arrays.stream(files)
                        .map(f -> f.getName().replace(".swsch", ""))
                        .collect(Collectors.joining(", "));
                msg(player, TextFormatting.YELLOW, "Схематики: " + list);
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
            return getListOfStringsMatchingLastWord(args, "pos1", "pos2", "info", "save", "load", "list");
        }
        String sub = args[0].toLowerCase();
        if (args.length == 2 && ("load".equals(sub) || "save".equals(sub))) {
            File dir = schematicDir(server);
            if (dir.exists() && dir.listFiles() != null) {
                File[] files = dir.listFiles((d, n) -> n.endsWith(".swsch"));
                if (files != null) {
                    List<String> names = Arrays.stream(files)
                            .map(f -> f.getName().replace(".swsch", ""))
                            .collect(Collectors.toList());
                    return getListOfStringsMatchingLastWord(args, names);
                }
            }
        }
        return Collections.emptyList();
    }

    // ---- helpers ----

    private static File schematicDir(MinecraftServer server) {
        return new File(server.getDataDirectory(), "config/swsc");
    }

    private static File schematicFile(MinecraftServer server, String name) {
        return new File(schematicDir(server), name + ".swsch");
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private static String fmt(BlockPos p) {
        return p == null ? "не задана" : p.getX() + " " + p.getY() + " " + p.getZ();
    }

    private static void msg(EntityPlayerMP player, TextFormatting color, String text) {
        player.sendMessage(new TextComponentString(color + text));
    }
}
