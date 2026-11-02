package io.github.ranger3462.deepmines.datagen;

import io.github.ranger3462.deepmines.DeepMines;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/** Registers Deep Mines' data generators. Run them with {@code ./gradlew runData}. */
@EventBusSubscriber(modid = DeepMines.MODID)
public final class DataGenerators {
    private DataGenerators() {}

    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        event.getGenerator().addProvider(event.includeServer(), new MinePieceProvider(event.getGenerator().getPackOutput()));
    }
}
