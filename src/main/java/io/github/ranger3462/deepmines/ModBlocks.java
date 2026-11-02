package io.github.ranger3462.deepmines;

import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Deep Mines' blocks: the ladders between floors. */
public final class ModBlocks {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(DeepMines.MODID);

    public static final DeferredBlock<Block> LADDER_DOWN = BLOCKS.register("ladder_down", () -> new MineLadderBlock(true));
    public static final DeferredBlock<Block> LADDER_UP = BLOCKS.register("ladder_up", () -> new MineLadderBlock(false));

    private ModBlocks() {}

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
