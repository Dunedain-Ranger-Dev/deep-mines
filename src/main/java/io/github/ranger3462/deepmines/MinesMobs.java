package io.github.ranger3462.deepmines;

import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import io.github.ranger3462.deepmines.MinesPieces.Kind;
import io.github.ranger3462.dungeondelving.dungeon.SpawnerMobs;
import io.github.ranger3462.dungeondelving.dungeon.SpawnerWaves;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

/**
 * Who holds the Mines. The dwarves' old mines have been overrun, and the deeper the floor, the older and darker what
 * holds it: four tiers of {@link #FLOORS_PER_TIER} floors (pests and scavengers, then goblin warbands and trolls, then
 * the restless dead, then the deep dead), as in {@code infestedMobs}.
 * <ul>
 * <li>Infested rooms' spawners each get a mob from the floor's tier, and are Dungeon Delving's wave spawners: the first
 * time a player comes near, one sends a wave of its mob and breaks, so a cleared room stays clear until the reset.</li>
 * <li>On the deepest tier, now and then an infested room is a Ferrous Wroughtnaut's hall instead (Mowzie's Mobs).</li>
 * <li>Now and then a vein room has a grottol in it (Mowzie's Mobs), and a common room a vein goblin trader (Goblin
 * Traders) or a few cave critters (Critters and Companions).</li>
 * </ul>
 * Mobs from mods that aren't installed are skipped. Nothing spawns in the Mines naturally: mobs only come from the
 * rooms.
 */
@EventBusSubscriber(modid = DeepMines.MODID)
public final class MinesMobs {
    /** How many floors each tier of mobs holds. */
    public static final int FLOORS_PER_TIER = 25;
    public static final int MAX_TIER = 4;

    /** {@code infestedMobs}' default: Dungeon Delving's {@code "mob=weight@tiers"} entries. */
    public static final List<String> DEFAULT_INFESTED = List.of(
            // Floors 1 to 25: pests and scavengers.
            "dungeondelving:kobold_scavenger=30@1", "dungeondelving:kobold_looter=20@1-2", "dungeondelving:kobold_packmaster=8@1-2",
            "minecraft:silverfish=20@1", "minecraft:cave_spider=20@1-2",
            // 26 to 50: goblin warbands and trolls, settled in.
            "dungeondelving:goblin=25@2", "dungeondelving:goblin_archer=15@2-3", "dungeondelving:goblin_gladiator=10@2-3",
            "dungeondelving:goblin_pyro=8@2-3", "dungeondelving:goblin_shaman=8@2-3", "iceandfire:troll=12@2-3",
            // 51 to 75: the restless dead.
            "dungeondelving:skeleton_swordsman=20@3", "dungeondelving:skeleton_knight=15@3-4", "dungeondelving:skeleton_mage=12@3-4",
            "dungeondelving:stray_swordsman=8@3", "dungeondelving:bogged_swordsman=8@3", "dungeondelving:wither_skeleton_archer=6@3-4",
            "dungeondelving:wither_skeleton_mage=6@3-4",
            // 76 and below: the deep dead.
            "iceandfire:dread_thrall=25@4", "iceandfire:dread_ghoul=20@4", "iceandfire:dread_knight=12@4", "iceandfire:dread_scuttler=8@4",
            "iceandfire:dread_beast=6@4", "iceandfire:dread_lich=3@4");

    public static final ResourceLocation WROUGHTNAUT = ResourceLocation.fromNamespaceAndPath("mowziesmobs", "ferrous_wroughtnaut");
    public static final ResourceLocation GROTTOL = ResourceLocation.fromNamespaceAndPath("mowziesmobs", "grottol");
    public static final ResourceLocation TRADER = ResourceLocation.fromNamespaceAndPath("goblintraders", "vein_goblin_trader");
    public static final List<ResourceLocation> CRITTERS = List.of(ResourceLocation.fromNamespaceAndPath("crittersandcompanions", "stag_beetle"),
            ResourceLocation.fromNamespaceAndPath("crittersandcompanions", "jumping_spider"));

    /** One in this many infested rooms on the deepest tier is a Wroughtnaut's hall. */
    public static final int HALL_ODDS = 6;
    /** One in this many vein rooms has a grottol. */
    public static final int GROTTOL_ODDS = 3;
    /** One in this many common rooms has a vein goblin trader. */
    public static final int TRADER_ODDS = 20;
    /** One in this many common rooms has a critter or two. */
    public static final int CRITTER_ODDS = 3;

    private MinesMobs() {}

    /** A floor's tier of mobs, 1 to {@link #MAX_TIER}. */
    public static int tier(int floor) {
        return Math.clamp(1 + (floor - 1) / FLOORS_PER_TIER, 1, MAX_TIER);
    }

    /** A mob for an infested room's spawner on this floor. */
    public static Optional<EntityType<?>> pick(ServerLevel level, int floor, RandomSource random) {
        return SpawnerMobs.pick(level, random, Config.INFESTED_MOBS.get(), tier(floor)).map(Holder::value);
    }

    /** Fills a piece just built on a floor with its mobs: its spawners, if it has any, and whatever else lives there. */
    public static void populate(ServerLevel level, Kind kind, int floor, BoundingBox box, List<BlockPos> spawners, RandomSource random) {
        EntityType<?> wroughtnaut = installed(level, WROUGHTNAUT);
        if (kind == Kind.INFESTED && tier(floor) == MAX_TIER && wroughtnaut != null && !spawners.isEmpty() && random.nextInt(HALL_ODDS) == 0) {
            // A Wroughtnaut's hall: it stands where the first spawner was, and the room has no other mobs.
            spawners.forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS));
            spawn(level, wroughtnaut, spawners.get(0));
            return;
        }
        for (BlockPos pos : spawners) {
            if (level.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner) {
                Optional<EntityType<?>> mob = pick(level, floor, random);
                if (mob.isPresent()) {
                    spawner.setEntityId(mob.get(), random);
                    spawner.setChanged();
                    SpawnerWaves.mark(level, pos, tier(floor));
                }
            }
        }
        if (kind == Kind.VEIN && random.nextInt(GROTTOL_ODDS) == 0) {
            placeIn(level, box, installed(level, GROTTOL), random);
        }
        if (kind == Kind.COMMON) {
            if (random.nextInt(TRADER_ODDS) == 0) {
                placeIn(level, box, installed(level, TRADER), random);
            }
            if (random.nextInt(CRITTER_ODDS) == 0) {
                for (int i = 0; i < 1 + random.nextInt(2); i++) {
                    placeIn(level, box, installed(level, CRITTERS.get(random.nextInt(CRITTERS.size()))), random);
                }
            }
        }
    }

    /** A mob, if its mod is installed. */
    @Nullable
    static EntityType<?> installed(ServerLevel level, ResourceLocation id) {
        return level.registryAccess().registryOrThrow(Registries.ENTITY_TYPE).getOptional(id).orElse(null);
    }

    /**
     * Puts a mob somewhere open on the floor of a piece: standing on something solid, out of the water, with room for
     * it. Returns it, or null if it's not installed or nowhere was found.
     */
    @Nullable
    public static Entity placeIn(ServerLevel level, BoundingBox box, @Nullable EntityType<?> type, RandomSource random) {
        if (type == null) {
            return null;
        }
        for (int attempt = 0; attempt < 64; attempt++) {
            int x = box.minX() + 4 + random.nextInt(Math.max(1, box.getXSpan() - 8));
            int z = box.minZ() + 4 + random.nextInt(Math.max(1, box.getZSpan() - 8));
            for (int y = box.minY() + 1; y < box.maxY() - 2; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (level.getBlockState(pos.below()).isSolid() && level.getFluidState(pos).isEmpty() && level.getFluidState(pos.below()).isEmpty()
                        && level.noCollision(type.getSpawnAABB(x + 0.5, y, z + 0.5))) {
                    return spawn(level, type, pos);
                }
            }
        }
        return null;
    }

    /** Spawns a mob that stays put until someone deals with it: it won't despawn when players are away. */
    @Nullable
    private static Entity spawn(ServerLevel level, EntityType<?> type, BlockPos pos) {
        Entity entity = type.spawn(level, pos, MobSpawnType.STRUCTURE);
        if (entity instanceof Mob mob) {
            mob.setPersistenceRequired();
        }
        return entity;
    }

    /** Whether a spawn of this kind in this dimension is stopped: nothing spawns in the Mines naturally. */
    public static boolean blocks(ResourceKey<Level> dimension, MobSpawnType type) {
        return dimension == MinesPortal.MINES && (type == MobSpawnType.NATURAL || type == MobSpawnType.CHUNK_GENERATION || type == MobSpawnType.PATROL);
    }

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (blocks(event.getLevel().getLevel().dimension(), event.getSpawnType())) {
            event.setSpawnCancelled(true);
        }
    }
}
