package io.github.ranger3462.deepmines;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * The way in and out of the Mines. Whoever comes through a portal is taken on to the start floor (Custom Portal API
 * still makes its arrival portal in the caves, but no one is left there), and where they came from is remembered: the
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

    /** Someone heading into the Mines by a portal: remember where from. */
    @SubscribeEvent
    public static void onTravel(EntityTravelToDimensionEvent event) {
        if (event.getDimension() != MinesPortal.MINES || !(event.getEntity() instanceof ServerPlayer player) || MOVING.contains(player.getUUID())
                || player.level().dimension() == MinesPortal.MINES) {
            return;
        }
        CompoundTag entry = new CompoundTag();
        entry.putString("dimension", player.level().dimension().location().toString());
        entry.put("pos", NbtUtils.writeBlockPos(player.blockPosition()));
        player.getPersistentData().put(ENTRY_TAG, entry);
        ENTERING.add(player.getUUID());
    }

    /** And once they're in, on to the start floor. */
    @SubscribeEvent
    public static void onArrive(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getTo() != MinesPortal.MINES || !(event.getEntity() instanceof ServerPlayer player) || !ENTERING.remove(player.getUUID())) {
            return;
        }
        ServerLevel mines = player.server.getLevel(MinesPortal.MINES);
        if (mines != null) {
            MinesFloors.visit(player, mines, MinesFloors.BASE, MinesFloors.START);
        }
    }
}
