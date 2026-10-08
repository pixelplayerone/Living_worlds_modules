# Auto Potions

One command, `.pots`, turns automatic potion drinking on and off. While it is on, CP, HP, and MP potions are drunk
the moment a stat drops under its line, as fast as each potion's own reuse allows, with fallbacks when you run out.

## Commands
- `.pots` toggles it on and off. Also `.pots on`, `.pots off`, `.pots status`.
- `.pots hp 60`, `.pots mp 50`, `.pots cp 95` set the line for that stat (percent, 1 to 100).
- `.pots cp off` stops drinking that kind.

Settings are per player. Whether it is on, and your CP/HP/MP lines, are remembered across logins.

## How it works
It checks four times a second, so CP and mana potions can be used at their real 0.5s reuse. For each stat it drinks
every potion in a "parallel" list whenever it is off reuse (so a Quick Healing Potion and a Greater Healing Potion
run together on their own timers), and falls through to a fallback list only when you carry none of the parallel
ones. It respects each potion's own reuse, shared reuse group, and skill reuse, so it never goes faster than a
click. It will not drink while you are stunned, asleep, afraid, paralyzed, trading, or in a private store, and it
only uses potions already in your inventory (it never buys or crafts them).

## Alongside the stock auto-potion
The stock `.apon` / `.apoff` (`AutoPotions.ini`) is left alone and can run beside this. This module checks more
often, falls through to the next potion while the first cools down, and toggles with one `.pots` command.

## Install
1. Copy the `auto-potions` folder into `game/modules/`.
2. Set `Enabled = True` in `config/module.ini`.
3. Restart.

## Configuration (`config/module.ini`)
- `Enabled`, `TickMillis` (250).
- Default lines: `DefaultCpPercent`, `DefaultHpPercent`, `DefaultMpPercent`.
- Potions: `CpParallelIds`, `HpParallelIds` (default 1540,1539), `MpParallelIds`; fallbacks `CpItemIds` (5592,5591),
  `HpItemIds` (1061,1060), `MpItemIds` (728).
- `AllowInOlympiad`, `Messages`, `OutOfPotionsWarning`, `RememberAcrossLogin`.

## Remove
Disable it, stop the server, delete the folder. No ids or database tables.

## License
GNU General Public License v3.0.
