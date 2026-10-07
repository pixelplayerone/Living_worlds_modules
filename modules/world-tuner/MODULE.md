# World Tuner

Scales how fast monsters respawn and how many monsters each spawn point holds, for the whole world or just part of it. Every setting is a line in `config/module.ini`; edit it and restart the server.

- **RespawnSpeedPercent**: how much faster monsters respawn. 50 (the default) = 50% faster, 100 = twice as fast, 0 = stock, negative = slower.
- **SpawnCountMultiplier**: 2.0 = every spawn point gets one extra monster. Fractions work by chance (1.5 gives about half the spawn points an extra, 0.5 switches off about half for good).
- **MinLevel / MaxLevel**: only change monsters in this level range.
- **Regions**: only change these regions (the folder names in `data/spawns`, e.g. `Gludio, Dion`). Empty = everywhere.
- **ExcludeNpcIds**: monsters that are never changed.
- **Overrides**: per-monster `id:count:respawnSpeedPercent`, e.g. `20001:2.0:50`. These win over the level range and regions.

Raid bosses, minions, instance monsters, town NPCs and spawns made by quests or scripts are never touched. With `Enabled = False`, or with the speed bonus at 0, the count multiplier at 1.0 and no overrides, the server behaves as stock.

## How it works

Modules start before the spawn lists load, so World Tuner waits for the server's "started" event, which fires after every spawn list is loaded and spawned. It then makes one pass over the spawn table. For each eligible spawn point it scales the respawn delay and, for a count above 1, adds extra spawn points of the same kind. Each extra is a separate spawn point near the original (within `ExtraSpawnSpread`), with the original's territory, name and AI settings, so it respawns at its own spot. The log prints a summary line when the pass finishes.

Spawn points that are skipped: raid and grand bosses, instance monsters, anything that never respawns, day/night spawns that are not out when the server starts, and spawns made by quests or scripts.

## Notes

- Denser or sparser zones change how fights play out, including how your phantoms handle groups and aggro. Try it on the test server first.
- Quest monsters get more plentiful or scarcer too.
