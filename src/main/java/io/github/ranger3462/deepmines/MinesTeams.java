package io.github.ranger3462.deepmines;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Team Mines ({@code teamMines}): with it on, each FTB Team has its own Mines, and each player in no team (or every
 * player, without FTB Teams) their own. Every Mines is in the same dimension, each in its own stretch of it,
 * {@link #REGION_SPACING} blocks south of the last, with solid rock between; their floors, ladders, lobby and lift
 * are their own, built the first time one of them goes in. Off, everyone shares the one Mines (region 0).
 * <ul>
 * <li>A team's Mines are only its members': anyone found on the floors of Mines that aren't theirs (they dug across,
 * or changed teams while inside) is sent back out.</li>
 * <li>Levels follow the same teams ({@link MinesLevels}): strict, solo only.</li>
 * <li>All Mines reset together, on the one schedule ({@link MinesReset}), and their regions are handed out afresh.</li>
 * </ul>
 */
@EventBusSubscriber(modid = DeepMines.MODID)
public final class MinesTeams {
    /** How far apart each team's Mines are, north to south. A floor's rooms stay within 512 blocks of its first. */
    public static final int REGION_SPACING = 4096;
    /** How often players are checked for being in Mines that aren't theirs, in ticks. */
    private static final int CHECK_INTERVAL = 20;
    /** Below this, players aren't checked: the portals' arrival spots are down there, in the shared rock. */
    private static final int CHECK_BELOW = MinesFloors.BASE.getY() - 10;

    /** The team a player is in, with Team Mines on: FTB Teams' party team, if it's installed. Swapped out by tests. */
    public static Function<ServerPlayer, Optional<UUID>> lookup = MinesTeams::fromFtb;

    private MinesTeams() {}

    public static boolean on() {
        return Config.TEAM_MINES.get();
    }

    /** A player's FTB party team, or none without FTB Teams. */
    public static Optional<UUID> fromFtb(ServerPlayer player) {
        return ModList.get().isLoaded("ftbteams") ? FtbTeamsLink.partyOf(player) : Optional.empty();
    }

    /** The team a player is in, as far as the Mines go: none with Team Mines off. */
    public static Optional<UUID> team(ServerPlayer player) {
        return on() ? lookup.apply(player) : Optional.empty();
    }

    /** Whose Mines a player uses: their team's, or their own if they're in none. Empty for the shared Mines. */
    public static Optional<UUID> owner(ServerPlayer player) {
        return on() ? Optional.of(team(player).orElse(player.getUUID())) : Optional.empty();
    }

    /** The region of a player's Mines: 0 for the shared Mines. */
    public static int region(ServerLevel level, ServerPlayer player) {
        return owner(player).map(owner -> MinesRegions.get(level).region(owner)).orElse(0);
    }

    /** Where a player's Mines' floor 1 is centered: {@link MinesFloors#BASE}, for the shared Mines. */
    public static BlockPos base(ServerLevel level, ServerPlayer player) {
        return baseOf(region(level, player));
    }

    public static BlockPos baseOf(int region) {
        return MinesFloors.BASE.south(region * REGION_SPACING);
    }

    /** Which region a spot is in. */
    public static int regionAt(BlockPos pos) {
        return Math.floorDiv(pos.getZ() - MinesFloors.BASE.getZ() + REGION_SPACING / 2, REGION_SPACING);
    }

    /** The base of the Mines a spot is in (for ladders and the lift, which belong to their own Mines). */
    public static BlockPos baseAt(BlockPos pos) {
        return baseOf(regionAt(pos));
    }

    /** Whether a player may be at a spot in the Mines: on their own Mines' floors, or down in the rock below them all. */
    public static boolean belongs(ServerLevel level, ServerPlayer player, BlockPos pos) {
        return pos.getY() < CHECK_BELOW || regionAt(pos) == region(level, player);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % CHECK_INTERVAL != 0) {
            return;
        }
        ServerLevel mines = event.getServer().getLevel(MinesPortal.MINES);
        if (mines == null) {
            return;
        }
        for (ServerPlayer player : mines.players()) {
            if (!player.isSpectator() && !belongs(mines, player, player.blockPosition())) {
                MinesEntrance.leave(player);
                player.sendSystemMessage(Component.translatable("message.deepmines.not_your_mines").withStyle(ChatFormatting.GRAY));
            }
        }
    }
}
