package io.github.ranger3462.deepmines;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/**
 * Deep Mines: a cave dimension for heavy mining, wiped on a schedule, so the overworld doesn't have to be strip-mined.
 * Players get there through a portal they build (see {@link MinesPortal}); resets are in {@link MinesReset}.
 */
@Mod(DeepMines.MODID)
public class DeepMines {
    public static final String MODID = "deepmines";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DeepMines(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modBus.addListener(MinesPortal::register);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
