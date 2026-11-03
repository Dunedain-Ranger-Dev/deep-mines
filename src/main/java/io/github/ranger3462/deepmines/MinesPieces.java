package io.github.ranger3462.deepmines;

import static io.github.ranger3462.deepmines.MinesSpec.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;

/**
 * The Mines' saved pieces, from {@code data/deepmines/structure/mines/} in the mod and data packs, sorted into pools by
 * the start of their file names. The mod itself only has the start hall and the cap: rooms and corridors are carved
 * fresh ({@link RoomCarver}), but hand-built ones put here (by a data pack, say) are mixed in. Pieces of the wrong size
 * are skipped (and listed), so a bad piece can't break a floor.
 */
public final class MinesPieces {
    /** The folder the Mines' pieces live in, inside {@code data/deepmines/structure/}. */
    public static final String FOLDER = "mines";

    /** What a piece is, by the start of its file name. */
    public enum Kind {
        /** The start floor's hall, where players come in. */
        START("start_", true),
        /** A room with the usual amount of ore. */
        COMMON("common_", true),
        /** A room packed with ore. */
        QUARRY("quarry_", true),
        /** A room with a deep pool to dive into, with ore around it. */
        FLOODED("flooded_", true),
        /** A room held by mobs from spawners. */
        INFESTED("infested_", true),
        /** A corridor between two rooms, with a little ore. */
        TUNNEL("tunnel_", false),
        /** A plug of plain rock for a doorway with no corridor. */
        CAP("cap_", false);

        private final String prefix;
        private final boolean room;

        Kind(String prefix, boolean room) {
            this.prefix = prefix;
            this.room = room;
        }

        public String prefix() {
            return prefix;
        }

        public boolean room() {
            return room;
        }

        /** Whether pieces of this kind are carved fresh by {@link RoomCarver}, rather than only loaded. */
        public boolean carved() {
            return this != START && this != CAP;
        }

        /** The size every piece of this kind must be. */
        public Vec3i size() {
            return switch (this) {
                case TUNNEL -> new Vec3i(TUNNEL_WIDTH, TUNNEL_HEIGHT, TUNNEL_LENGTH);
                case CAP -> new Vec3i(CAP_WIDTH, CAP_HEIGHT, CAP_DEPTH);
                default -> new Vec3i(ROOM_SIZE, ROOM_HEIGHT, ROOM_SIZE);
            };
        }

        public static Optional<Kind> of(String fileName) {
            for (Kind kind : values()) {
                if (fileName.startsWith(kind.prefix)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    private final Map<ResourceLocation, Kind> all;
    private final Map<Kind, List<ResourceLocation>> pools;
    private final List<String> skipped;

    private MinesPieces(Map<ResourceLocation, Kind> all, Map<Kind, List<ResourceLocation>> pools, List<String> skipped) {
        this.all = all;
        this.pools = pools;
        this.skipped = skipped;
    }

    public static MinesPieces load(MinecraftServer server) {
        String resourceFolder = "structure/" + FOLDER;
        Map<ResourceLocation, Kind> all = new TreeMap<>();
        Map<Kind, List<ResourceLocation>> pools = new EnumMap<>(Kind.class);
        List<String> skipped = new ArrayList<>();
        server.getResourceManager().listResources(resourceFolder, file -> file.getPath().endsWith(".nbt")).keySet().stream().sorted().forEach(file -> {
            if (!file.getNamespace().equals(DeepMines.MODID)) {
                return;
            }
            String name = file.getPath().substring(resourceFolder.length() + 1, file.getPath().length() - ".nbt".length());
            Kind.of(name).ifPresent(kind -> {
                ResourceLocation id = DeepMines.id(FOLDER + "/" + name);
                Vec3i size = server.getStructureManager().get(id).map(template -> template.getSize()).orElse(Vec3i.ZERO);
                if (!size.equals(kind.size())) {
                    skipped.add(id + " is " + size.toShortString() + ", not " + kind.size().toShortString());
                    DeepMines.LOGGER.warn("Skipping Mines piece {}: it is {}, a {} must be {}", id, size.toShortString(), kind, kind.size().toShortString());
                    return;
                }
                all.put(id, kind);
                pools.computeIfAbsent(kind, k -> new ArrayList<>()).add(id);
            });
        });
        return new MinesPieces(Collections.unmodifiableMap(all), pools, skipped);
    }

    /** Every piece that loaded, with its kind. */
    public Map<ResourceLocation, Kind> all() {
        return all;
    }

    /** Pieces that were the wrong size, and why. */
    public List<String> skipped() {
        return skipped;
    }

    /** The pieces of a kind. Carved kinds usually have none: any there are are hand-built, mixed in with the carved ones. */
    public List<ResourceLocation> pool(Kind kind) {
        return pools.getOrDefault(kind, List.of());
    }

    public ResourceLocation pick(Kind kind, RandomSource random) {
        List<ResourceLocation> pool = pool(kind);
        if (pool.isEmpty()) {
            throw new IllegalStateException("The Mines have no " + kind.prefix() + " pieces");
        }
        return pool.get(random.nextInt(pool.size()));
    }
}
