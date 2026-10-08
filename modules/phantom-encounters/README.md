# Phantom Encounters

PvP danger for a solo player. While you play in the open field, phantoms occasionally come for you and fight once:
each actor exists only to PvP you, fights until you die or it dies, then leaves. Nothing keeps roaming.

## The five kinds
| Kind | Level vs you | Gear | Group | Behaviour |
|---|---|---|---|---|
| Wimp | 2-3 lower | +0 | your party size | Walks up and attacks on arrival. |
| Normie | same | +0 to +3 | your party size | Walks up and attacks on arrival. |
| Hard | +3 | +3 to +4 | your party size | Waits until you stand still or fight a monster, then strikes. |
| 4Horsemen | +5 | +5 to +7 | min 4 | Attacks the moment it reaches you. |
| AssMuncher | +11 | full +16 | always 1 | The extinction event: one named phantom. |

## When they happen
Each player has an independent timer per kind, with per-kind unlock levels and random waits (Wimp/Normie ~30-40 min,
Hard 2-3 h, Horsemen 4.5-5.5 h, AssMuncher 7-9 h). After any encounter starts, nothing else starts for a gap, and at
most a couple run server-wide at once. You are skipped in towns, peace/no-PvP/siege zones, duels, stores, instances,
the Olympiad, and while dead. A kind's clock starts the first time you are eligible, so there is no ambush on login.

## How it plays
Actors spawn 650-900 units away on walkable ground, approach, fight one fight, then end: kill the group and you are
paid; die and the survivors leave; escape or time out and they leave. The same actor never returns. Actors are not
red-named, so phantoms killed by players never drop items, and you keep your gear under the normal PK rules.

## Rewards
Adena only, paid once when the whole group is down: Wimp 50k, Normie 100k, Hard 200k, 4Horsemen 350k,
AssMuncher 1,000,000 (all configurable).

## Contested Farming Zones (optional)
Set `ContestedZones = True`. Each monster kill inside one of the farming-area circles rolls a chance to bring a
party that wants the spot: a PvE party (asks first, attacks if you refuse) or a PK party, each with its own reward.
Only normal monsters count, not raids or minions, not in instances, and a cooldown keeps it from repeating.

## Requirements
Needs FakePlayers and `PhantomPvpEnabled = True` in `config/Custom/FakePlayers.ini`.

## Install
1. Copy the `phantom-encounters` folder into `game/modules/`.
2. Set `Enabled = True` in `config/module.ini`.
3. Restart.

## Configuration (`config/module.ini`)
- Global: `Enabled`, `MinPlayerLevel`, `GapMinutes`, `MaxActive`, `MaxActors`, `HorsemenMinSize`, `PkerName`,
  `PkerClasses`, red-name and escape chances, `ApproachSeconds`, `FightSeconds`, `StillSeconds`.
- Per kind (`Wimp`, `Normie`, `Hard`, `Horsemen`, `Pker`, parties): `MinMinutes`/`MaxMinutes` (0 = off),
  `MinPlayerLevel`, `LevelMin`/`LevelMax`, `EnchantMin`/`EnchantMax`, `AdenaReward`.
- Contested: `ContestedZones`, `ContestedChancePercent`, `ContestedPvePercent`, `PveAskPercent`,
  `ContestedCooldownMinutes`.
- Testing: `TestCommands = True` enables `.enc` commands to force encounters.

## Remove
Disable it, stop the server, delete the folder. No ids or database tables; it stores nothing.

## License
GNU General Public License v3.0.
