package io.github.ranger3462.deepmines;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /deepmines when} shows anyone when the Mines reset. Operators also get {@code /deepmines reset}, which makes a
 * reset happen the next time the server starts, and {@code /deepmines floor <number>}, which goes to a floor of the
 * Mines (building it if no one has been there yet); floor 0 is the start floor.
 */
@EventBusSubscriber(modid = DeepMines.MODID)
public final class MinesCommands {
    private MinesCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(DeepMines.MODID)
                .then(Commands.literal("when").executes(context -> {
                    MinesReset.warn(context.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("reset")
                        .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> {
                            MinesReset.get().makeDue();
                            context.getSource().sendSuccess(() -> Component.translatable("message.deepmines.reset_scheduled"), true);
                            return 1;
                        }))
                .then(Commands.literal("floor")
                        .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("number", IntegerArgumentType.integer(MinesFloors.START)).executes(context -> {
                            int floor = IntegerArgumentType.getInteger(context, "number");
                            ServerLevel mines = context.getSource().getServer().getLevel(MinesPortal.MINES);
                            if (mines == null || floor > Config.FLOORS.get()) {
                                context.getSource().sendFailure(Component.translatable("message.deepmines.no_floor", floor, Config.FLOORS.get()));
                                return 0;
                            }
                            MinesFloors.visit(context.getSource().getPlayerOrException(), mines, MinesFloors.BASE, floor);
                            return 1;
                        }))));
    }
}
