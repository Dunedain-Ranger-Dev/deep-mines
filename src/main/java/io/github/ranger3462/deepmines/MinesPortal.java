package io.github.ranger3462.deepmines;

import net.kyrptonaught.customportalapi.CustomPortalApiRegistry;
import net.kyrptonaught.customportalapi.CustomPortalRegistrationEvent;
import net.kyrptonaught.customportalapi.api.CustomPortalBuilder;
import net.kyrptonaught.customportalapi.util.PortalLink;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The way into the Mines: a nether-style portal framed with deepslate bricks and lit with flint and steel, from
 * Custom Portal API Reforged. Anyone can build one (players for themselves, admins somewhere public). Going through
 * makes a linked portal in a cave in the Mines, and the same portal leads back.
 */
public final class MinesPortal {
    public static final ResourceKey<Level> MINES = ResourceKey.create(Registries.DIMENSION, DeepMines.id("mines"));
    public static final Block FRAME = Blocks.DEEPSLATE_BRICKS;
    /** Arrival portals in the Mines are placed in caves between these heights, well under the bedrock roof. */
    public static final int ARRIVAL_TOP_Y = 40;
    public static final int ARRIVAL_BOTTOM_Y = -40;
    /** A dim lantern gold. */
    private static final int TINT = 0xC08A3E;

    private MinesPortal() {}

    static void register(CustomPortalRegistrationEvent event) {
        event.register(CustomPortalBuilder.beginPortal()
                .frameBlock(FRAME)
                .destDimID(MINES.location())
                .tintColor(TINT));
        PortalLink link = CustomPortalApiRegistry.getPortalLinkFromBase(FRAME);
        if (link != null) {
            link.portalSearchYTop = ARRIVAL_TOP_Y;
            link.portalSearchYBottom = ARRIVAL_BOTTOM_Y;
        } else {
            DeepMines.LOGGER.error("The Mines portal didn't register; the Mines can't be reached");
        }
    }
}
