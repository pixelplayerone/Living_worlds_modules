# Hunting

A Community Board tab (Alt+B, or `.hunting`) with two kinds of one-time contracts, both paid in adena. Progress is
saved per character and survives restarts.

## Extermination Contracts
One for each of the server's 74 named hunting grounds, in seven grades by the ground's average level (1-19, 20-39,
40-51, 52-60, 61-75, 76-79, 80+). There is nothing to accept: kill monsters inside a ground and the progress counts
by itself, then press Claim once it hits the target. Click a name to mark the place on your map. Finishing every
ground in a grade unlocks a one-time section bonus.

| Grade | Kills | Reward per ground | Section bonus |
|---|---|---|---|
| 1-19 | 100 | 100k | 1M |
| 20-39 | 250 | 250k | 2.5M |
| 40-51 | 350 | 500k | 5M |
| 52-60 | 500 | 1M | 10M |
| 61-75 | 600 | 2M | 15M |
| 76-79 | 750 | 3M | 30M |
| 80+ | 1000 | 5M | 40M |

## Bounties
Raid bosses grouped into 16 level ranges, one reward per boss, for every raid boss a player can actually fight
(including event, quest, or script spawns). Bosses nothing ever spawns are left out. Kill a boss and it is marked
slain by itself (kills from before install do not count), then claim. Clearing every boss of a level range unlocks
a section bonus.

## Grand Bosses
The open-world grand bosses (Queen Ant, Core, Orfen, Zaken, Baium, Antharas, Valakas) have their own list, same
rules: slain automatically, claim once.

## Install
1. Copy the `hunting` folder into `game/modules/`.
2. Set `Enabled = True` in `config/module.ini`.
3. Restart.

## Configuration (`config/module.ini`)
- `Enabled` - master switch. False means the server is stock.
- `TabLabel` - text on the navigation button (default `Hunting`).
- `AdenaRewards` - False keeps contracts counting and claimable but pays nothing and hides the amounts.
- `GroundKills` / `GroundRewards` / `GroundSectionRewards` - one number per grade, in grade order.
- `RaidAdenaFactor` - bounty reward = boss level x boss level x this.
- `RaidBandBonus` - share of a level range's total paid for clearing all its bosses (0.5 = half again, 0 = none).
- `GrandAdenaFactor` - grand boss reward = boss level x boss level x this.

## Notes
- Kills by your partied phantoms are credited to you. Raid minions and instance monsters do not count. Where
  grounds overlap, a kill counts for each.
- The ground list is baked from the server's own hunting-ground data by `tools/gen_ground_data.py`; quest-only
  areas are left out.
- Each player's kills and claims are read and saved under one lock, so a kill and a claim at the same moment can
  no longer overwrite each other.

## Remove
Disable it, stop the server, delete the folder. Progress is kept in player variables; it is not deleted. No ids or
database tables.

## License
GNU General Public License v3.0.
