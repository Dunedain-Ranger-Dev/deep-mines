package io.github.ranger3462.deepmines;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The ladders between the Mines' floors: the ladder down sits in a floor's farthest cave, the ladder up in its first.
 * Using one takes the player to the next floor (building it if no one has been there yet) or back to the one above.
 * They can't be broken.
 */
public class MineLadderBlock extends Block {
    private final boolean down;

    public MineLadderBlock(boolean down) {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).sound(SoundType.LADDER).strength(-1.0f, 3600000.0f)
                .noLootTable().noOcclusion());
        this.down = down;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel server && player instanceof ServerPlayer serverPlayer) {
            int floor = MinesFloors.floorAt(MinesFloors.BASE, pos);
            if (down) {
                MinesFloors.descend(serverPlayer, server, MinesFloors.BASE, floor);
            } else {
                MinesFloors.ascend(serverPlayer, server, MinesFloors.BASE, floor);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}
