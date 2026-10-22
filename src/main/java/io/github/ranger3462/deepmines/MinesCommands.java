package io.github.ranger3462.deepmines;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /deepmines when} shows anyone when the Mines reset. Operators also get {@code /deepmines reset}, which makes a
 * reset happen the next time the server starts.
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
                        })));
    }
}
