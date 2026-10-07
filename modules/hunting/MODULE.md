# Hunting

A Community Board tab (Alt+B, or `.hunting`) with two kinds of one-time contracts, both paid in adena.

**Extermination Contracts.** One for each of the server's 74 named hunting grounds, in seven grades by the ground's average level: 1-19, 20-39, 40-51, 52-60, 61-75, 76-79 and 80+ (the three level-80 grounds). Inside a grade the grounds are listed easiest first, with the average level shown. There is nothing to accept: kill monsters inside a ground and its progress counts by itself, and you press Claim once it hits the target. Click a name to mark the place on your map.

| Grade | Kills | Reward per ground | Section bonus |
|---|---|---|---|
| 1-19 | 100 | 100k | 1M |
| 20-39 | 250 | 250k | 2.5M |
| 40-51 | 350 | 500k | 5M |
| 52-60 | 500 | 1M | 10M |
| 61-75 | 600 | 2M | 15M |
| 76-79 | 750 | 3M | 30M |
| 80+ | 1000 | 5M | 40M |

Finish every ground in a grade and its section bonus can be claimed once. All of it is in `config/module.ini` (`GroundKills`, `GroundRewards`, `GroundSectionRewards`, `RaidAdenaFactor`, `RaidBandBonus`, `GrandAdenaFactor`). Set `AdenaRewards = False` to turn every adena payout off: contracts still count and can be claimed, nothing is paid, and the reward amounts are hidden in the tab. The aim is to get you to see every place you can level.

**Bounties.** Raid bosses grouped into 16 level ranges (20-24, 25-29 ... 75-79, 80-81, 82-84, 85-86, 87+), one reward per boss, every raid boss a player can really fight, including ones an event, quest or script spawns (Lilith, Anakim, Sailren, Daimon and the like; players find those out for themselves). Raid bosses that nothing in the server ever spawns are left out. Kill a boss and it is marked slain by itself (kills before the module was installed don't count), then claim. Clear every boss of a level range and a section bonus (`RaidBandBonus`, half again what that range paid) can be claimed once.

The grounds come from the server's own hunting-ground list (`PhantomPopulations.xml`), baked into `scripts/GroundData.java` by `tools/gen_ground_data.py`. Quest-only areas are left out.

**Grand Bosses.** The open-world grand bosses are kept out of the Bounties list and have a list of their own: Queen Ant, Core, Orfen, Zaken, Baium, Antharas and Valakas (Frintezza is an instance and is left out). Same rules: slain automatically, claim once. Reward = level x level x `GrandAdenaFactor` (2000).

Progress and claimed contracts are saved in player variables (`HuntingGrounds`, `HuntingRaids`), so they survive restarts. Kills by your partied phantoms are credited to you by the core. Raid minions and instance monsters do not count. Where grounds overlap, a kill counts for each. With `Enabled = False` the module registers nothing.

A player's kills and claims are read, changed and saved under one lock per player, so a kill landing at the same moment as a claim can no longer save an older copy over it (which let a contract be claimed twice and could drop a kill).
