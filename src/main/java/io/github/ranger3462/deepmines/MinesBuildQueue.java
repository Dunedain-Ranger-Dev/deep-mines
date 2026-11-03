package io.github.ranger3462.deepmines;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Builds the Mines' floors a little at a time, so a deep floor of 25 rooms doesn't freeze the server: each tick it
 * works through the queued steps (a piece carved, a layer of it placed, its ore set) for up to {@link #BUDGET_MS}. A
 * player headed for a spot still being built is sent there once it's done ({@link #whenBuilt}).
 * <p>
 * Nothing is saved: if the server stops partway, the floor isn't marked built, and is built again (the same, from the
 * same seed) the next time someone reaches it.
 */
@EventBusSubscriber(modid = DeepMines.MODID)
public final class MinesBuildQueue {
    /** How long each tick may spend building, in milliseconds (a tick is 50). */
    public static final long BUDGET_MS = 15;

    /** How many jobs ahead get to {@link Job#prepare}, so their chunks are loaded by the time they're reached. */
    public static final int LOOK_AHEAD = 6;

    /** Some building to do a step at a time: {@link #step} does the next bit, and says whether that was the last. */
    public interface Job {
        boolean step(ServerLevel level);

        /** Gets ready to start, without waiting: asks for its chunks to be loaded, say. Called more than once. */
        default void prepare(ServerLevel level) {}

        /** Whether it can take its next step without waiting (on chunks loading, say). */
        default boolean ready(ServerLevel level) {
            return true;
        }
    }

    /** A job, and the space it builds in (if any). */
    private record Entry(ResourceKey<Level> level, @Nullable BoundingBox box, Job job, String description) {}

    private static final List<Entry> QUEUE = new ArrayList<>();

    private MinesBuildQueue() {}

    /** Queues building in {@code box}. */
    public static void add(ServerLevel level, BoundingBox box, Job job, String description) {
        QUEUE.add(new Entry(level.dimension(), box, job, description));
    }

    /** Queues something to do once everything queued before it is done. */
    public static void then(ServerLevel level, Runnable action, String description) {
        QUEUE.add(new Entry(level.dimension(), null, once(action), description));
    }

    /** Whether anything queued is yet to build at {@code pos}. */
    public static boolean building(ServerLevel level, BlockPos pos) {
        return lastCovering(level, pos) >= 0;
    }

    /** Runs {@code action} once nothing queued is yet to build at {@code pos}: now, if nothing is. */
    public static void whenBuilt(ServerLevel level, BlockPos pos, Runnable action) {
        int last = lastCovering(level, pos);
        if (last < 0) {
            action.run();
        } else {
            QUEUE.add(last + 1, new Entry(level.dimension(), null, once(action), "waiting on " + pos.toShortString()));
        }
    }

    /** How many jobs are queued. */
    public static int pending() {
        return QUEUE.size();
    }

    /** Does everything queued now, all at once, loading chunks as it goes (for tests). */
    public static void finish(MinecraftServer server) {
        while (!QUEUE.isEmpty()) {
            runNext(server);
        }
    }

    private static int lastCovering(ServerLevel level, BlockPos pos) {
        for (int i = QUEUE.size() - 1; i >= 0; i--) {
            Entry entry = QUEUE.get(i);
            if (entry.level().equals(level.dimension()) && entry.box() != null && entry.box().isInside(pos)) {
                return i;
            }
        }
        return -1;
    }

    private static Job once(Runnable action) {
        return level -> {
            action.run();
            return true;
        };
    }

    private static void runNext(MinecraftServer server) {
        Entry entry = QUEUE.get(0);
        ServerLevel level = server.getLevel(entry.level());
        if (level == null) {
            DeepMines.LOGGER.warn("Dropping {}: {} isn't loaded", entry.description(), entry.level().location());
            QUEUE.remove(0);
            return;
        }
        try {
            if (entry.job().step(level)) {
                QUEUE.remove(0);
            }
        } catch (RuntimeException e) {
            DeepMines.LOGGER.error("Failed to build {} in {}", entry.description(), entry.level().location(), e);
            QUEUE.remove(0);
        }
    }

    /**
     * Works through the queue for up to {@link #BUDGET_MS}, stopping early when the next job is waiting (on its chunks),
     * after letting the next few jobs get ready.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        for (int i = 0; i < Math.min(LOOK_AHEAD, QUEUE.size()); i++) {
            Entry entry = QUEUE.get(i);
            ServerLevel level = server.getLevel(entry.level());
            if (level != null) {
                entry.job().prepare(level);
            }
        }
        long end = System.nanoTime() + BUDGET_MS * 1_000_000;
        while (!QUEUE.isEmpty() && System.nanoTime() < end) {
            ServerLevel level = server.getLevel(QUEUE.get(0).level());
            if (level != null && !QUEUE.get(0).job().ready(level)) {
                break;
            }
            runNext(server);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        QUEUE.clear();
    }
}
