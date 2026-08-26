package net.decentstudio.swsc;

import net.decentstudio.swsc.command.SwscCommand;
import net.decentstudio.swsc.tool.SwscToolManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

@Mod(modid = SwscMod.MODID, name = "SWSC Schematic Tool", version = SwscMod.VERSION,
        acceptableRemoteVersions = "*")
public class SwscMod {

    public static final String MODID   = "swsc";
    public static final String VERSION = "2.0.0";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new SwscToolManager());
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new SwscCommand());
    }
}
