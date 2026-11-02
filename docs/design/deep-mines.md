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
- **Built with Dungeon Delving's generator:** caves, linked by corridors in the style of YUNG's Better Mineshafts.
- **Floors.** Each floor has a couple of caves and corridors, then a ladder down to the next. The deeper the floor,
  the better its ore. For example:

  | Floor | Copper | Coal | Iron |
  |---|---|---|---|
  | 1 | 50% | 40% | 10% |
  | 2 | 45% | 45% | 10% |
  | 3 | 40% | 45% | 15% |

- **Infested caves.** Hostile mobs hold the caves: players fight them first, then mine freely.
  - Mostly undead, plus natural hostile cave creatures (to be decided: things like cave fishers).
  - No natural spawning. Mobs come from Dungeon Delving's common-room spawning (mob markers in the cave pieces).

## Settings

| Setting | Default | What it does |
|---|---|---|
| Reset frequency | 7 days | How often the Mines reset |
| Team Mines | Off (server-wide) | Ties the Mines to each FTB Team or party instead, to prevent griefing |
| Ore modifier | 1.0 | A global multiplier on how often ore appears |

## Where it stands

Built in 0.1.0 (MINE-2 to MINE-4), not yet tested in game:

- The Mines dimension, using vanilla cave terrain. Building the floors with Dungeon Delving replaces that terrain.
- A deepslate-brick portal that anyone can build anywhere. That conflicts with entry from the Dwarven capital only.
- The weekly reset and its warnings (`resetDays`, default 7), shared by everyone. This part of the goal is done.

## Open questions

1. **The Dwarven capital doesn't exist yet.** Kingdom Sim's capitals belong to the monster nations and the kingdom,
   and player standing is designed (Kingdom Sim's `realm-politics.md`) but not built. Until both exist, what's the way
   in? Keep the deepslate portal behind a setting?
2. **Depending on Dungeon Delving.** Deep Mines works on its own today. Building the floors with Dungeon Delving's
   generator makes it a required dependency, unless the generator moves into a shared library.
3. **Better Mineshafts corridors.** YUNG's mineshafts are built by its own structure code, so its corridors may not
   be reusable as pieces. If not, we build our own corridor pieces in that style.
4. **Ore below floor 3.** How many floors are there, and where do gold, redstone, lapis, diamond and the dwarves'
   rare ore come in?
5. **The 3x baseline.** Measure ore per block of open cave in a vanilla overworld world, so "3x" is a number we can
   test.
6. **Natural cave mobs.** Which ones, and from which mod? None are in the pack yet.
7. **Team Mines.** One region per team inside the same dimension (the way Dungeon Delving gives each run a region),
   or a dimension per team? And does each team get its own reset?
8. **Staying cleared.** Once players clear a cave, does it stay clear until the next reset?

## Tickets

Each goal has its own task under the Deep Mines project in Jira, with sub-tasks for the steps:

| Goal | Task | Steps |
|---|---|---|
| Floors built with Dungeon Delving's generator | MINE-6 | MINE-11 to MINE-16 |
| Ore: about 3x, better the deeper you go | MINE-7 | MINE-17 to MINE-21 |
| Infested caves: fight first, then mine | MINE-8 | MINE-22 to MINE-25 |
| Access only from the Dwarven capital | MINE-9 | MINE-26 to MINE-29 |
| Team Mines (setting) | MINE-10 | MINE-30 to MINE-33 |
| Weekly reset for everyone; reset frequency setting | MINE-4 | Built |

The floors (MINE-6) come first: the ore and mob work both place markers in its pieces.
