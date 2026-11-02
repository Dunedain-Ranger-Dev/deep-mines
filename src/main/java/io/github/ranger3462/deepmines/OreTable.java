package io.github.ranger3462.deepmines;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Which ore a floor of the Mines has, from {@code docs/design/ore-table.py} (see the design doc's "Ore by floor"):
 * vanilla's overworld ore features as if the floors were a dig from Y 64 (floor 1) down to Y -64 (the last floor),
 * plus Mithril from floor 6 down. The same rules as the script, so the two give the same table.
 */
public final class OreTable {
    /** The ores, with their stone and deepslate blocks. */
    public enum Ore {
        COAL("minecraft:coal_ore", "minecraft:deepslate_coal_ore"),
        COPPER("minecraft:copper_ore", "minecraft:deepslate_copper_ore"),
        IRON("minecraft:iron_ore", "minecraft:deepslate_iron_ore"),
        GOLD("minecraft:gold_ore", "minecraft:deepslate_gold_ore"),
        REDSTONE("minecraft:redstone_ore", "minecraft:deepslate_redstone_ore"),
        LAPIS("minecraft:lapis_ore", "minecraft:deepslate_lapis_ore"),
        DIAMOND("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"),
        /** Iron's Spells 'n Spellbooks' (MINE-35); left out where that mod isn't installed. */
        MITHRIL("irons_spellbooks:mithril_ore", "irons_spellbooks:deepslate_mithril_ore");

        private final ResourceLocation stone;
        private final ResourceLocation deepslate;

        Ore(String stone, String deepslate) {
            this.stone = ResourceLocation.parse(stone);
            this.deepslate = ResourceLocation.parse(deepslate);
        }

        /** The ore's block in stone or in deepslate; air if its mod isn't installed. */
        public Block block(boolean deep) {
            ResourceLocation id = deep ? deepslate : stone;
            return BuiltInRegistries.BLOCK.containsKey(id) ? BuiltInRegistries.BLOCK.get(id) : Blocks.AIR;
        }

        public boolean installed() {
            return BuiltInRegistries.BLOCK.containsKey(stone);
        }
    }

    /** One vanilla ore feature: veins per chunk, blocks per vein, and how likely each height is. */
    private record Feature(double veins, double size, DoubleUnaryOperator height) {}

    private static final Map<Ore, List<Feature>> FEATURES = new EnumMap<>(Map.of(
            Ore.COAL, List.of(new Feature(20, 17, tri(0, 192))),
            Ore.COPPER, List.of(new Feature(16, 10, tri(-16, 112))),
            Ore.IRON, List.of(new Feature(10, 9, tri(-24, 56)), new Feature(10, 4, uni(-64, 72))),
            Ore.GOLD, List.of(new Feature(4, 9, tri(-64, 32)), new Feature(0.5, 9, uni(-64, -48))),
            Ore.REDSTONE, List.of(new Feature(4, 8, uni(-64, 15)), new Feature(8, 8, tri(-96, -32))),
            Ore.LAPIS, List.of(new Feature(2, 7, tri(-32, 32)), new Feature(4, 7, uni(-64, 64))),
            Ore.DIAMOND, List.of(new Feature(7, 4, tri(-144, 16)), new Feature(1 / 9.0, 12, tri(-144, 16)), new Feature(4, 8, tri(-144, 16)),
                    new Feature(2, 8, uni(-64, -4)))));

    private OreTable() {}

    private static DoubleUnaryOperator tri(double lo, double hi) {
        double mid = (lo + hi) / 2;
        double half = (hi - lo) / 2;
        return y -> Math.max(0, 1 - Math.abs(y - mid) / half) / half;
    }

    private static DoubleUnaryOperator uni(double lo, double hi) {
        return y -> lo <= y && y <= hi ? 1 / (hi - lo + 1) : 0;
    }

    /** The overworld height a floor mines like: Y 64 on floor 1, Y -64 on the last of 100. */
    public static double floorY(int floor) {
        return 64 - (floor - 1) * 128.0 / 99;
    }

    /** Mithril's share, in percent: none down to floor 5, then 0.5% on floor 6 rising to 2% on floor 100. */
    public static double mithril(int floor) {
        return floor <= 5 ? 0 : 0.5 + 1.5 * (floor - 6) / 94;
    }

    /** Each ore's share of a floor's ore, in percent (adding up to 100). */
    public static Map<Ore, Double> mix(int floor) {
        double y = floorY(floor);
        Map<Ore, Double> weights = new EnumMap<>(Ore.class);
        double total = 0;
        for (var entry : FEATURES.entrySet()) {
            double weight = entry.getValue().stream().mapToDouble(f -> f.veins() * f.size() * f.height().applyAsDouble(y)).sum();
            weights.put(entry.getKey(), weight);
            total += weight;
        }
        double rest = 100 - mithril(floor);
        Map<Ore, Double> mix = new EnumMap<>(Ore.class);
        for (var entry : weights.entrySet()) {
            mix.put(entry.getKey(), entry.getValue() / total * rest);
        }
        mix.put(Ore.MITHRIL, mithril(floor));
        return mix;
    }

    /** Picks an ore for a vein on a floor, by its share (skipping ores whose mod isn't installed). */
    public static Ore pick(int floor, RandomSource random) {
        Map<Ore, Double> mix = mix(floor);
        mix.keySet().removeIf(ore -> !ore.installed());
        double roll = random.nextDouble() * mix.values().stream().mapToDouble(Double::doubleValue).sum();
        for (var entry : mix.entrySet()) {
            roll -= entry.getValue();
            if (roll < 0) {
                return entry.getKey();
            }
        }
        return Ore.IRON;
    }

    /**
     * How likely a floor's rooms are to be deepslate rather than stone: none above vanilla's Y 8, all of them below Y 0,
     * and a mix in between (about floors 45 to 50), as in the overworld.
     */
    public static double deepslateChance(int floor) {
        if (floor < 1) {
            return 0;
        }
        return Math.max(0, Math.min(1, (8 - floorY(floor)) / 8));
    }
}
