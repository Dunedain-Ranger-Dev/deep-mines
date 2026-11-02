package io.github.ranger3462.deepmines;

import net.minecraft.core.Direction;

/**
 * The fixed dimensions every piece of the Mines follows, so any room fits any slot of a floor and doorways line up.
 * The Mines' rooms are bigger than Dungeon Delving's, with their doorways halfway up the walls: the rock under a
 * doorway is room to dig down into (or a pool to dive into), and the rock over it room to dig up.
 * <p>
 * Coordinates are local to a piece, counted from 0. In front of every doorway, a room keeps a landing: solid floor
 * just under the doorway and clear air over it, so a corridor always leads somewhere to stand, and a ladder fits there.
 */
public final class MinesSpec {
    // Rooms: 49 x 49 footprint, 40 tall, walls included. Odd, so a doorway can sit exactly in the middle of a side.
    public static final int ROOM_SIZE = 49;
    public static final int ROOM_HEIGHT = 40;
    public static final int MID = ROOM_SIZE / 2;

    // Doorways: a 5 wide, 4 tall hole in the middle of each room wall, halfway up.
    public static final int DOOR_WIDTH = 5;
    public static final int DOOR_HEIGHT = 4;
    public static final int DOOR_BOTTOM_Y = 18;
    /** First block of a doorway along a room side (blocks 22-26). */
    public static final int DOOR_START = (ROOM_SIZE - DOOR_WIDTH) / 2;
    /** The top of a room's floor wherever someone walks in at a doorway (just under it). */
    public static final int WALK_Y = DOOR_BOTTOM_Y - 1;

    // Landings: in front of each doorway, 11 wide and 5 deep from the wall, floor at WALK_Y and air up to the doorway's top.
    public static final int LANDING_START = DOOR_START - 3;
    public static final int LANDING_WIDTH = DOOR_WIDTH + 6;
    public static final int LANDING_DEPTH = 5;

    // Caps: a plug exactly filling one doorway. 5 wide (along x), 4 tall, 1 deep (along z).
    public static final int CAP_WIDTH = DOOR_WIDTH;
    public static final int CAP_HEIGHT = DOOR_HEIGHT;
    public static final int CAP_DEPTH = 1;

    // Corridors: 9 wide (along x), 8 tall, 31 long (along z), with rock two thick around a doorway-sized passage.
    public static final int TUNNEL_WIDTH = 9;
    public static final int TUNNEL_HEIGHT = 8;
    public static final int TUNNEL_LENGTH = 31;
    /** How thick a corridor's rock is around its passage. */
    public static final int TUNNEL_WALL = (TUNNEL_WIDTH - DOOR_WIDTH) / 2;
    /** First block of a corridor across a room side: centered on the doorway (blocks 20-28). */
    public static final int TUNNEL_START = (ROOM_SIZE - TUNNEL_WIDTH) / 2;
    /** Where a corridor's bottom sits in a room's frame: its passage lines up with the doorways. */
    public static final int TUNNEL_Y = DOOR_BOTTOM_Y - TUNNEL_WALL;

    /** Distance between neighboring rooms' corners on a floor: one room plus one corridor. */
    public static final int GRID_PITCH = ROOM_SIZE + TUNNEL_LENGTH;

    // The start floor's hall: where players stand, and where its ladders go.
    public static final int START_FLOOR_Y = 13;
    /** The start hall's ladder down to floor 1, and the ladder up out of the Mines, in the hall's own frame. */
    public static final int[] START_LADDER_DOWN = { MID, START_FLOOR_Y, 11 };
    public static final int[] START_LADDER_UP = { MID, START_FLOOR_Y, ROOM_SIZE - 12 };

    private MinesSpec() {}

    /** Whether a position along a room side is within the doorway (blocks 22-26). */
    public static boolean inDoorSpan(int i) {
        return i >= DOOR_START && i < DOOR_START + DOOR_WIDTH;
    }

    public static boolean inDoorHeight(int y) {
        return y >= DOOR_BOTTOM_Y && y < DOOR_BOTTOM_Y + DOOR_HEIGHT;
    }

    /** Whether a position along a room side is within a landing's width (blocks 19-29). */
    public static boolean inLandingSpan(int i) {
        return i >= LANDING_START && i < LANDING_START + LANDING_WIDTH;
    }

    /** The {@code {x, z}} of a spot {@code depth} blocks in from a room's {@code side} wall, {@code across} along it. */
    public static int[] fromWall(Direction side, int across, int depth) {
        int max = ROOM_SIZE - 1;
        return switch (side) {
            case NORTH -> new int[] { across, depth };
            case SOUTH -> new int[] { across, max - depth };
            case WEST -> new int[] { depth, across };
            default -> new int[] { max - depth, across };
        };
    }
}
