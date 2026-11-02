package io.github.ranger3462.deepmines;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Deep Mines' blocks: the ladders between floors, and the ore spot pieces mark their ore with. */
public final class ModBlocks {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(DeepMines.MODID);

    public static final DeferredBlock<Block> LADDER_DOWN = BLOCKS.register("ladder_down", () -> new MineLadderBlock(true));
    public static final DeferredBlock<Block> LADDER_UP = BLOCKS.register("ladder_up", () -> new MineLadderBlock(false));
    /**
     * Where a piece's ore goes. Each connected patch of ore spots becomes one vein when a floor is built, of an ore
     * picked from that floor's table ({@link OreTable}). Only seen in the pieces themselves.
     */
    public static final DeferredBlock<Block> ORE_SPOT = BLOCKS.registerSimpleBlock("ore_spot",
            BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(3.0f, 3.0f).requiresCorrectToolForDrops());

    private ModBlocks() {}

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
