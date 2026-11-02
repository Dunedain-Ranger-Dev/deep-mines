package io.github.ranger3462.deepmines;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * A placement filter, {@code deepmines:in_mines}, that only lets a feature place in the Mines. The Mines use the
 * overworld's biomes, so a biome can't tell them apart: a pack adds this to an ore's placed feature to move that ore
 * out of the overworld and into the Mines, e.g. Ice and Fire's silver and sapphire (MINE-34).
 */
public final class InMinesPlacement extends PlacementFilter {
    public static final InMinesPlacement INSTANCE = new InMinesPlacement();
    public static final MapCodec<InMinesPlacement> CODEC = MapCodec.unit(INSTANCE);

    private static final DeferredRegister<PlacementModifierType<?>> TYPES = DeferredRegister.create(Registries.PLACEMENT_MODIFIER_TYPE, DeepMines.MODID);
    public static final DeferredHolder<PlacementModifierType<?>, PlacementModifierType<InMinesPlacement>> TYPE =
            TYPES.register("in_mines", () -> () -> CODEC);

    private InMinesPlacement() {}

    static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }

    @Override
    protected boolean shouldPlace(PlacementContext context, RandomSource random, BlockPos pos) {
        return context.getLevel().getLevel().dimension() == MinesPortal.MINES;
    }

    @Override
    public PlacementModifierType<?> type() {
        return TYPE.get();
    }
}
