package io.github.ranger3462.deepmines;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The Mines' reset schedule. Once the configured number of days has passed since the last reset, the Mines are wiped
 * the next time the server starts, before the dimension loads, by deleting its saved chunks. Players are warned when
 * they arrive and every few minutes after, and anyone who logs back in to Mines that were reset meanwhile is sent to
 * their spawn point. Portals in other dimensions keep working: the next trip through makes a fresh portal in the Mines.
 */
@EventBusSubscriber(modid = DeepMines.MODID)
public final class MinesReset {
    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;
    private static final long HOUR_MILLIS = 60L * 60 * 1000;
    /** How often players in the Mines are reminded of the reset, in ticks (5 minutes). */
    private static final int WARN_INTERVAL = 5 * 60 * 20;
    /** Saved on players: which version of the Mines (how many resets in) they last went into. */
    public static final String ENTERED_TAG = DeepMines.MODID + ":entered_after_resets";

    private static MinesReset current = new MinesReset(null, 0, 0);

    private final Path file;
    private long lastReset;
    private int resets;

    MinesReset(Path file, long lastReset, int resets) {
        this.file = file;
        this.lastReset = lastReset;
        this.resets = resets;
    }

    public static MinesReset get() {
        return current;
    }

    /** How many times the Mines have been reset. */
    public int resets() {
        return resets;
    }

    /** Whether a reset is due at {@code now}: the interval is on, and that many days have passed since the last one. */
    public static boolean due(long lastReset, long now, int intervalDays) {
        return intervalDays > 0 && now - lastReset >= intervalDays * DAY_MILLIS;
    }

    /** Milliseconds until a reset becomes due (0 or less if it already is). Only meaningful while resets are on. */
    public static long untilDue(long lastReset, long now, int intervalDays) {
        return lastReset + intervalDays * DAY_MILLIS - now;
    }

    /** Deletes a dimension's saved data (chunks, entities and points of interest), so it generates fresh. */
    public static void wipe(Path dimensionFolder) throws IOException {
        if (!Files.isDirectory(dimensionFolder)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dimensionFolder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /** Makes a reset due now, so it happens the next time the server starts (for testing). */
    public void makeDue() {
        lastReset = 0;
        save();
    }

    /** Tells a player when the Mines will be reset. */
    public static void warn(ServerPlayer player) {
        int days = Config.RESET_DAYS.get();
        if (days <= 0) {
            return;
        }
        long left = untilDue(current.lastReset, System.currentTimeMillis(), days);
        Component message;
        if (left < HOUR_MILLIS) {
            message = Component.translatable("message.deepmines.reset_next");
        } else {
            long hours = left / HOUR_MILLIS;
            message = Component.translatable("message.deepmines.reset_in", hours / 24, hours % 24);
        }
        player.sendSystemMessage(message.copy().withStyle(ChatFormatting.GOLD));
    }

    /** Sends a player to their spawn point (their bed's dimension and spot, if they have one), out of the Mines. */
    static void sendToSpawn(ServerPlayer player) {
        MinecraftServer server = player.server;
        ServerLevel level = server.getLevel(player.getRespawnDimension());
        BlockPos pos = player.getRespawnPosition();
        if (level == null || pos == null || level.dimension() == MinesPortal.MINES) {
            level = server.overworld();
            BlockPos spawn = level.getSharedSpawnPos();
            pos = new BlockPos(spawn.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ()), spawn.getZ());
        }
        player.teleportTo(level, pos.getX() + 0.5, pos.getY() + 0.1, pos.getZ() + 0.5, player.getYRot(), 0.0f);
    }

    private void save() {
        if (file == null) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putLong("last_reset", lastReset);
        tag.putInt("resets", resets);
        try {
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(tag, file);
        } catch (IOException e) {
            DeepMines.LOGGER.error("Failed to save the Mines reset schedule to {}", file, e);
        }
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        MinecraftServer server = event.getServer();
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path file = worldRoot.resolve(DeepMines.MODID).resolve("mines.dat");
        long now = System.currentTimeMillis();
        MinesReset state = new MinesReset(file, now, 0);
        if (Files.exists(file)) {
            try {
                CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                state = new MinesReset(file, tag.getLong("last_reset"), tag.getInt("resets"));
            } catch (IOException e) {
                DeepMines.LOGGER.error("Failed to read the Mines reset schedule from {}, starting fresh", file, e);
            }
        }
        if (due(state.lastReset, now, Config.RESET_DAYS.get())) {
            // The Mines haven't loaded yet, so their files are safe to delete.
            try {
                wipe(DimensionType.getStorageFolder(MinesPortal.MINES, worldRoot));
                state.lastReset = now;
                state.resets++;
                DeepMines.LOGGER.info("Reset the Mines (reset {})", state.resets);
            } catch (IOException e) {
                DeepMines.LOGGER.error("Failed to reset the Mines", e);
            }
        }
        state.save();
        current = state;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        current = new MinesReset(null, 0, 0);
    }

    /** Arriving in the Mines: remember which version they are, and say when they reset. */
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getTo() == MinesPortal.MINES && event.getEntity() instanceof ServerPlayer player) {
            player.getPersistentData().putInt(ENTERED_TAG, current.resets);
            player.sendSystemMessage(Component.translatable("message.deepmines.arrive").withStyle(ChatFormatting.GRAY));
            warn(player);
        }
    }

    /** Anyone who logs back in to Mines that were reset while they were away is sent to their spawn point. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().dimension() != MinesPortal.MINES) {
            return;
        }
        if (player.getPersistentData().getInt(ENTERED_TAG) != current.resets) {
            sendToSpawn(player);
            player.sendSystemMessage(Component.translatable("message.deepmines.collapsed").withStyle(ChatFormatting.GRAY));
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % WARN_INTERVAL != 0) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (player.level().dimension() == MinesPortal.MINES) {
                warn(player);
            }
        }
    }
}
