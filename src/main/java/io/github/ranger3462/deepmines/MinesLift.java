package io.github.ranger3462.deepmines;

import static io.github.ranger3462.deepmines.MinesSpec.START_LIFT;

import java.util.Optional;

import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonLayout.Cell;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The lobby lift: a winch in the start hall, east of the headframe, with its chain running up into the vault. It takes
 * a player straight down to any floor down to their level ({@link MinesLevels}), so after coming up to sell or restock
 * they don't climb back down floor by floor. Floors are reached by their ladder up, and built first if they need to be,
 * as by the ladders.
 * <p>
 * Using the winch lists the floors it goes to in chat, to click on; sneaking and using it goes straight to the deepest.
 * The clicks run {@code /deepmines lift <floor>}, which only works beside the lift.
 */
public final class MinesLift {
    /** How close to the lift a player must be for {@code /deepmines lift} to take them. */
    public static final double REACH = 6;
    /** How many floors each line of the list in chat has. */
    private static final int PER_LINE = 25;
    /** How high the chain over the winch goes, at most. */
    private static final int CHAIN = 24;

    private MinesLift() {}

    /** Where the lift stands in the start hall. */
    public static BlockPos pos(BlockPos base) {
        return MinesBuilder.roomMin(MinesFloors.center(base, MinesFloors.START), Cell.ORIGIN).offset(START_LIFT[0], START_LIFT[1], START_LIFT[2]);
    }

    /** Puts the lift in the start hall, whose corner is {@code hallMin}: the winch, and its chain up into the vault. */
    static void place(ServerLevel level, BlockPos hallMin) {
        BlockPos pos = hallMin.offset(START_LIFT[0], START_LIFT[1], START_LIFT[2]);
        level.setBlock(pos, ModBlocks.LIFT.get().defaultBlockState(), Block.UPDATE_CLIENTS);
        for (int y = 1; y <= CHAIN && level.getBlockState(pos.above(y)).isAir(); y++) {
            level.setBlock(pos.above(y), Blocks.CHAIN.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** Using the lift: the floors it goes to, in chat; or, sneaking, straight down to the deepest. */
    public static void use(ServerPlayer player, ServerLevel level, BlockPos base) {
        int deepest = Math.min(MinesLevels.get(level).level(player), Config.FLOORS.get());
        if (deepest < 1) {
            player.displayClientMessage(Component.translatable("message.deepmines.lift_none").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        if (player.isShiftKeyDown()) {
            goTo(player, level, base, deepest);
            return;
        }
        boolean team = MinesTeams.team(player).isPresent();
        player.sendSystemMessage(Component.translatable(team ? "message.deepmines.lift_team" : "message.deepmines.lift", deepest)
                .withStyle(ChatFormatting.GOLD));
        for (int first = 1; first <= deepest; first += PER_LINE) {
            MutableComponent line = Component.empty();
            for (int floor = first; floor <= Math.min(deepest, first + PER_LINE - 1); floor++) {
                line.append(Component.literal("[" + floor + "]").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + DeepMines.MODID + " lift " + floor))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("message.deepmines.lift_to", floor)))));
                line.append(" ");
            }
            player.sendSystemMessage(line);
        }
    }

    /**
     * Takes a player down by the lift to a floor, by its ladder up, if it's no deeper than their level. Returns where
     * they were sent, if anywhere.
     */
    public static Optional<BlockPos> goTo(ServerPlayer player, ServerLevel level, BlockPos base, int floor) {
        int deepest = Math.min(MinesLevels.get(level).level(player), Config.FLOORS.get());
        if (floor < 1 || floor > deepest) {
            player.displayClientMessage(Component.translatable("message.deepmines.lift_too_deep", floor, deepest), true);
            return Optional.empty();
        }
        return Optional.of(MinesFloors.visit(player, level, base, floor));
    }

    /** Whether a player is beside the lift, in the Mines' start hall. */
    public static boolean near(ServerPlayer player, BlockPos base) {
        return player.level().dimension() == MinesPortal.MINES && player.position().closerThan(pos(base).getCenter(), REACH);
    }
}
