package io.github.ranger3462.deepmines;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * The way in and out of the Mines. Whoever comes through a portal is taken on to the start floor (Custom Portal API
 * still makes its arrival portal, in the rock under the floors, but no one is left there), and where they came from is remembered: the
 * start floor's ladder up takes them back. Trips the Mines make themselves (ladders, {@code /deepmines floor}) go
 * straight where they're sent.
 */
@EventBusSubscriber(modid = DeepMines.MODID)
public final class MinesEntrance {
    /** Saved on players: where they were when they last went into the Mines. */
    public static final String ENTRY_TAG = DeepMines.MODID + ":entry";

    /** Players being moved by the Mines themselves, who shouldn't be taken to the start floor. */
    private static final Set<UUID> MOVING = new HashSet<>();
    /** Players on their way in by a portal, to take on to the start floor once they arrive. */
    private static final Set<UUID> ENTERING = new HashSet<>();

    private MinesEntrance() {}

    /** Moves a player to a spot in the Mines, without the portal arrival taking over. */
    static void teleportWithin(ServerPlayer player, ServerLevel level, BlockPos pos) {
        MOVING.add(player.getUUID());
        ENTERING.remove(player.getUUID());
        try {
            player.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, player.getYRot(), player.getXRot());
        } finally {
            MOVING.remove(player.getUUID());
        }
    }

    /** Takes a player out of the Mines, back where they came in from (or to their spawn point, if that's gone). */
    public static void leave(ServerPlayer player) {
        CompoundTag entry = player.getPersistentData().getCompound(ENTRY_TAG);
        ResourceLocation dimension = ResourceLocation.tryParse(entry.getString("dimension"));
        ServerLevel level = dimension == null ? null : player.server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        var pos = NbtUtils.readBlockPos(entry, "pos");
        if (level == null || level.dimension() == MinesPortal.MINES || pos.isEmpty()) {
            MinesReset.sendToSpawn(player);
            return;
        }
        BlockPos at = pos.get();
        player.teleportTo(level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, player.getYRot(), player.getXRot());
        // They come back out in the portal they went in by: give them time to step out of it.
        player.setPortalCooldown();
    }

    /**
     * Whether something heading into the Mines from elsewhere is let in: always when the Mines are moving it
     * themselves (or another mod's way in, {@link #enter}), and by the portal only while it's open ({@code portal}).
     */
    public static boolean letIn(boolean byTheMines, boolean portalOpen) {
        return byTheMines || portalOpen;
    }

    /** Whether the portal leads in, by the {@code portal} setting and whether Kingdom Sim is installed. */
    public static boolean portalOpen() {
        return Config.portalOpen(Config.PORTAL.get(), ModList.get().isLoaded("ddkingdom"));
    }

    /**
     * Takes a player into the Mines from wherever they are, by another mod's way in (Kingdom Sim's mine gate at the
     * Dwarven capital), whatever the {@code portal} setting: to their own Mines' start hall, and the ladder up there
     * brings them back here. Returns whether they went (not if the Mines aren't loaded, or they're already in).
     */
    public static boolean enter(ServerPlayer player) {
        ServerLevel mines = player.server.getLevel(MinesPortal.MINES);
        if (mines == null || player.level().dimension() == MinesPortal.MINES) {
            return false;
        }
        CompoundTag entry = new CompoundTag();
        entry.putString("dimension", player.level().dimension().location().toString());
        entry.put("pos", NbtUtils.writeBlockPos(player.blockPosition()));
        player.getPersistentData().put(ENTRY_TAG, entry);
        MinesFloors.visit(player, mines, MinesTeams.base(mines, player), MinesFloors.START);
        return true;
    }

    /** Someone heading into the Mines by a portal: turned back if the portal's closed, or else remember where from. */
    @SubscribeEvent
    public static void onTravel(EntityTravelToDimensionEvent event) {
        if (event.getDimension() != MinesPortal.MINES || event.getEntity().level().dimension() == MinesPortal.MINES) {
            return;
        }
        boolean byTheMines = event.getEntity() instanceof ServerPlayer moving && MOVING.contains(moving.getUUID());
        if (!letIn(byTheMines, portalOpen())) {
            event.setCanceled(true);
            if (event.getEntity() instanceof ServerPlayer player) {
                player.displayClientMessage(Component.translatable("message.deepmines.portal_closed"), true);
            }
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player) || byTheMines) {
            return;
        }
        CompoundTag entry = new CompoundTag();
        entry.putString("dimension", player.level().dimension().location().toString());
        entry.put("pos", NbtUtils.writeBlockPos(player.blockPosition()));
        player.getPersistentData().put(ENTRY_TAG, entry);
        ENTERING.add(player.getUUID());
    }

    /**
     * The start floor is the Mines' lobby, built with the world: when the server starts, if it isn't there yet (a new
     * world, or just after a reset), so no one waits on it.
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ServerLevel mines = event.getServer().getLevel(MinesPortal.MINES);
        if (mines != null) {
            MinesFloors.floor(mines, MinesFloors.BASE, MinesFloors.START, true);
        }
    }

    /** And once they're in, on to the start floor. */
    @SubscribeEvent
    public static void onArrive(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getTo() != MinesPortal.MINES || !(event.getEntity() instanceof ServerPlayer player) || !ENTERING.remove(player.getUUID())) {
            return;
        }
        ServerLevel mines = player.server.getLevel(MinesPortal.MINES);
        if (mines != null) {
            MinesFloors.visit(player, mines, MinesTeams.base(mines, player), MinesFloors.START);
        }
    }
}
