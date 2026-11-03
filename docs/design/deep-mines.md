# Deep Mines

The design for the mod (MINE-1). Written by the user, 2026-11-02, from the design doc attached to MINE-1; this file is
now the living copy, so change it here as decisions are made. Numbers are first guesses for tuning.

## The goal

A living space where players go to gather resources on a more frequent schedule than the overworld allows.

- **Entirely underground.**
- **Resets weekly, for everyone.** The Mines are one shared, communal space for all players.
- **Reached only from the Dwarven capital.** The dwarves start neutral to every player. Players raise their standing
  by doing quests for them, gathering rare ore in their mines.

## The Mines

- **About 3x the ore** of ordinary overworld caves.
- **Built with Dungeon Delving's generator:** rooms, linked by corridors in the style of YUNG's Better Mineshafts.
  Dungeon Delving's layout generator decides where a floor's rooms go and which are joined; the Mines' own builder
  (`MinesBuilder`) places their own, bigger pieces.
- **The start floor.** Floor 0, the same in every Mines: one dwarven hall where the portal brings players in, with a
  dais in the middle for the dwarf who'll give out quests, workshops along the walls, a ladder down to floor 1 and a
  ladder up back out to wherever they came in from. (Custom Portal API still makes its arrival portal in the caves
  below the floors, but no one is left there.)
- **Floors.** 100 of them below the start floor. Each has more rooms the deeper it is (3 to 5 down to floor 25, 6
  to 9 down to 50, 10 to 15 down to 75, then 16 to 25) joined by corridors, then a ladder down to the next. The deeper the floor, the better its ore (see [Ore by floor](#ore-by-floor)).
  - **Decided (MINE-14):** floors sit side by side, each in its own 1024-block stretch of the Mines east of the last,
    rather than stacked (100 stacked floors would need a dimension about 1,600 blocks tall). The ladder down in a
    floor's farthest room takes players to the next floor's first room, where a ladder up leads back. Ladders stand
    on the landing inside a doorway, a dead-end one if the room has one.
  - A floor is built the first time someone reaches it, so floors no one gets to cost nothing (a short pause, logged
    as "Built floor n of the Mines in ... ms").
- **Rooms (MINE-15, MINE-36).** 49 x 49 x 40, with the doorways halfway up the walls: every room is a block of rock
  with a cave carved in it at doorway height, rock below to dig down into, and rock above to dig up into. Inside every
  doorway is a landing (solid floor, clear air) so a corridor always leads somewhere to stand. Rooms and corridors are
  carved fresh when a floor is built, each from its own seed (`RoomCarver`), so no two are alike. The kinds (how often
  each comes up is a setting, a weight; a floor's first room is always common). A cave's floor rises and falls up to
  3 blocks, but stays level along the rail line. Its walls, roof and floor are worn into bulges and hollows by 3D
  noise, so they aren't straight lines, and its roof is smoothed of notches and slots. Ore is kept sparse: about 200
  blocks in a common room, 230 flooded, 310 vein, 340 quarry and 390 infested:
  - `common_` (50): the usual amount of ore. Half are caves: "dig down" ones have it mostly under the cave's floor,
    "dig up" ones (with lower caves) mostly overhead, and old abandoned drifts lead off the cave towards veins and end
    in a shaft sunk or raised towards more, showing where to dig. The other half are old workings laid out like a vein
    room's, with ore of every kind in ordinary veins.
  - `quarry_` (15): a stepped open pit, packed with ore around its walls. Its floor stays flat round the rim.
  - `flooded_` (15): a deep pool, level with the floor, to dive into; its ore is around the pool's walls and bottom.
  - `infested_` (20): spawners (zombies and skeletons until MINE-25 picks the Mines' own mobs), bones and cobwebs,
    and the usual amount of ore.
  - `vein_` (10): no cave, but timbered galleries (like the corridors) in from every doorway to a hall in the middle,
    with crosscuts off them ending at veins. Very large veins show in the galleries' walls and roofs, all of one ore,
    picked from the floor's table when the floor is built. About half again as much ore as a common room.
  - `tunnel_`: corridors, 11 x 10 x 31: a timbered mineshaft with a passage 7 wide and 6 high, with a little ore.
    Every 4 to 6 blocks a support: stripped spruce legs on upright barrels, a beam across their tops with 5 blocks
    under it (7 wide, over the legs), an upside-down spruce stair in each inside corner with spruce trapdoors flush
    against the beam and the leg beside it, and usually a lantern hanging from the middle of the beam. Vein rooms' galleries use the same supports.
  - `cap_`: plain rock, for doorways with no corridor.
  - `start_`: the start hall.
- **The rules every room keeps.** Sealed all round but its doorways, a landing inside each, a way to walk (or climb,
  or swim) from every doorway to every other, water only in flooded rooms, and spawners only in infested ones. A
  carved room that breaks them is carved again from the next seed, and as a last resort gets plank walkways from each
  doorway to the middle. The game tests carve 60 of every kind and check them all.
- **Pieces.** Only the fixed ones are saved: the start hall and the cap, generated by `MinePieceProvider`
  (`./gradlew runData`) into `data/deepmines/structure/mines/`. Hand-built rooms and corridors can be added there too
  (by a data pack, say), named by kind and the right size; a kind that has any uses one a quarter of the time
  (`handBuilt`). Saved pieces mark their ore with ore spots (`deepmines:ore_spot`), carved ones keep a list; either
  way each patch becomes a vein of one ore, picked from the floor's table, when the floor is built.
- **Rock.** Stone, with patches of andesite, diorite and granite (never speckled through it). From about floor 45 down
  a room is deepslate as often as vanilla's deepslate is at that height (all of them from floor 51), with tuff in
  place of the other stones and deepslate ore.
- **Infested caves.** Hostile mobs hold the caves: players fight them first, then mine freely.
  - Mostly undead, plus natural hostile cave creatures (to be decided: things like cave fishers).
  - No natural spawning (MINE-22). Mobs come from the infested rooms' spawners.

## Ore by floor

The floors follow vanilla's overworld ore by height, as if the 100 floors were a dig from sea level to bedrock:
floor 1 mines like Y 64 and floor 100 like Y -64, about 1.3 blocks of height per floor. The mix on each floor is
vanilla's ore features (veins per chunk x vein size x how likely they are at that height), leaving out air exposure,
biome-only ore (emerald, badlands gold, dripstone copper) and the big copper and iron veins.
[`ore-table.py`](ore-table.py) works it out for any floor; run it again after changing the rules.

The table is each ore's share of the ore on a floor. How much ore there is in total stays the same on every floor
(about 3x the overworld), so a deeper floor is better because of what it has, not because it has more of it.
Floors 50 and below are past vanilla's Y 0, so they're deepslate, with the deepslate ores.

| Floor | ~Y | Coal | Copper | Iron | Gold | Redstone | Lapis | Diamond | Mithril |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 64 | 50 | 40 | 6.2 | - | - | 4.6 | - | - |
| 5 | 59 | 46 | 44 | 6.1 | - | - | 4.6 | - | - |
| 6 | 58 | 44 | 44 | 6.1 | - | - | 4.5 | - | 0.5 |
| 10 | 52 | 39 | 47 | 9.9 | - | - | 4.3 | - | 0.6 |
| 20 | 39 | 29 | 42 | 24 | - | - | 4.3 | - | 0.7 |
| 30 | 27 | 20 | 33 | 39 | 1.7 | - | 5.8 | - | 0.9 |
| 40 | 14 | 9.5 | 22 | 45 | 5.4 | 7.6 | 8.9 | 0.4 | 1.0 |
| 50 | 1 | 0.6 | 16 | 41 | 12 | 9.8 | 16 | 3.6 | 1.2 |
| 60 | -12 | - | 4.5 | 29 | 21 | 12 | 15 | 16 | 1.4 |
| 70 | -25 | - | - | 13 | 26 | 17 | 13 | 29 | 1.5 |
| 80 | -38 | - | - | 12 | 16 | 31 | 8.6 | 31 | 1.7 |
| 90 | -51 | - | - | 8.3 | 13 | 45 | 6.1 | 26 | 1.8 |
| 100 | -64 | - | - | 6.8 | 6.2 | 56 | 5.1 | 24 | 2.0 |

Percent of the ore on that floor; "-" is under 0.05%. What that means as you go down:

- **Floors 1 to 15:** coal and copper, a little iron and lapis.
- **Floors 15 to 30:** iron takes over from coal.
- **Gold** from about floor 28, **redstone** and **diamond** from about floor 38.
- **Coal** runs out around floor 50 and **copper** around floor 62.
- **Floors 65 to 80** are the best for diamond and gold, and redstone takes over below that.

**Mithril** is the dwarves' rare ore, for their quests (Kingdom Sim, KING-20). It reuses Iron's Spells 'n Spellbooks'
Mithril ore. It's a small chance on every floor below 5, 0.5% of the ore on floor 6 rising to 2% on floor 100. The
pack lists Mithril as dungeon-only today, so that has to change first (MINE-35).

Other ore the pack places in the Mines comes on top of this table: Ice and Fire's silver and sapphire (DD-113, through
the `deepmines:in_mines` filter).

## Settings

| Setting | Default | What it does |
|---|---|---|
| Reset frequency | 7 days | How often the Mines reset |
| Floors | 100 | How many floors the Mines go down (`floors`) |
| Room mix | 50 / 15 / 15 / 20 / 10 | How often common, quarry, flooded, infested and vein rooms come up, as weights (`[rooms]`) |
| Hand-built rooms | 25% | How often a hand-built room or corridor is used instead of a carved one, if there are any (`handBuilt`) |
| Team Mines | Off (server-wide) | Ties the Mines to each FTB Team or party instead, to prevent griefing |
| Ore modifier | 1.0 | A global multiplier on how often ore appears |

## Where it stands

Built in 0.1.0 (MINE-2 to MINE-4), and tested in game on 2026-11-02:

- The Mines dimension, using vanilla cave terrain. Building the floors with Dungeon Delving replaces that terrain.
- A deepslate-brick portal that anyone can build anywhere. That conflicts with entry from the Dwarven capital only.
- The weekly reset and its warnings (`resetDays`, default 7), shared by everyone. This part of the goal is done.

## Open questions

1. **The Dwarven capital doesn't exist yet.** Kingdom Sim's capitals belong to the monster nations and the kingdom,
   and player standing is designed (Kingdom Sim's `realm-politics.md`) but not built. Until both exist, what's the way
   in? Keep the deepslate portal behind a setting?
   - The capital, standing and the entrance moved to Kingdom Sim (KING-18). The portal setting stays here (MINE-26).
2. **Depending on Dungeon Delving.** Deep Mines works on its own today. Building the floors with Dungeon Delving's
   generator makes it a required dependency, unless the generator moves into a shared library.
   - **Decided:** Dungeon Delving is a required dependency. Deep Mines uses its layout generator for each floor's
     rooms and joins (`MinesFloors`, MINE-13). Since MINE-15 the Mines' rooms are bigger than Dungeon Delving's, so
     Deep Mines loads and places its own pieces (`MinesPieces`, `MinesBuilder`, sizes in `MinesSpec`).
3. **Better Mineshafts corridors.** YUNG's mineshafts are built by its own structure code, so its corridors may not
   be reusable as pieces. If not, we build our own corridor pieces in that style.
   - **Answered (MINE-12): we build our own.** Better Mineshafts 5.1.1 has no template files: every corridor, turn
     and side room is drawn block by block in Java (`SmallTunnel`, `BigTunnel` and the rest), sized as it goes and
     carved into whatever stone is there. Its pieces aren't an API, and each needs its own mineshaft config and
     YUNG's API, so placing one inside our grid would break with any update. Its license (LGPL-3.0) isn't the
     problem. Our corridors are ordinary `tunnel_` pieces (11 x 10 x 31 since MINE-36), built in its style: plank floors,
     log legs and fence supports, rails, the odd chest minecart. Its per-biome block palettes in
     `data/bettermineshafts/worldgen/structure/` (oak, spruce, jungle, mesa and so on) are a good guide to the look.
   - While the Mines use overworld biomes and cave terrain, Better Mineshafts' own mineshafts probably generate in
     them too. That goes away when the floors replace the terrain (MINE-16), which should turn structures off.
4. **Ore below floor 3.** How many floors are there, and where do gold, redstone, lapis, diamond and the dwarves'
   rare ore come in?
   - **Decided:** 100 floors, following vanilla's ore by height from Y 64 down (see [Ore by floor](#ore-by-floor)).
     The rare ore is Mithril, a small chance on every floor below 5. The percentages are a first pass for tuning.
5. **The 3x baseline.** Measure ore per block of open cave in a vanilla overworld world, so "3x" is a number we can
   test.
6. **Natural cave mobs.** Which ones, and from which mod? None are in the pack yet.
   - Left open: existing mobs to find, or new ones to design. Undead only until then.
7. **Team Mines.** One region per team inside the same dimension (the way Dungeon Delving gives each run a region),
   or a dimension per team? And does each team get its own reset?
   - **Decided:** each team gets its own region. A reset wipes the whole dimension, so every team shares the
     schedule.
8. **Staying cleared.** Once players clear a cave, does it stay clear until the next reset?

## Tickets

Each goal has its own task under the Deep Mines project in Jira, with sub-tasks for the steps:

| Goal | Task | Steps |
|---|---|---|
| Floors built with Dungeon Delving's generator | MINE-6 | MINE-11 to MINE-16 |
| Ore: about 3x, better the deeper you go | MINE-7 | MINE-17 to MINE-21, MINE-35 |
| Infested caves: fight first, then mine | MINE-8 | MINE-22 to MINE-25 |
| Access only from the Dwarven capital | MINE-9 | MINE-26 here; the rest is Kingdom Sim's KING-18 (KING-19 to 21) |
| Team Mines (setting) | MINE-10 | MINE-30 to MINE-33 |
| Weekly reset for everyone; reset frequency setting | MINE-4 | Built |

The floors (MINE-6) come first: the ore and mob work both place markers in its pieces.
