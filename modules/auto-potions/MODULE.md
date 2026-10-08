# Auto Potions

One command, `.pots`, turns automatic potion drinking on and off.

While it is on, CP, HP and MP potions are drunk the moment a stat drops under its line, as fast as each potion's own reuse allows (CP and mana potions every half second). If the best potion is cooling down or has run out, the next one in the list is used.

## Commands
- `.pots` toggles it on and off. `.pots on`, `.pots off`, `.pots status`.
- `.pots hp 60`, `.pots mp 50`, `.pots cp 95` change the line for that stat (percent, 1 to 100).
- `.pots cp off` stops drinking that kind.

Settings are per player. Whether it is on, and your CP, HP and MP lines (including ones turned off), are remembered across logins (`RememberAcrossLogin`).

## Config (`config/module.ini`)
`Enabled` (False), `TickMillis` (250), `DefaultCpPercent` (90), `DefaultHpPercent` (70), `DefaultMpPercent` (70), `CpParallelIds` (empty), `HpParallelIds` (1540,1539), `MpParallelIds` (empty), `CpItemIds` (5592,5591), `HpItemIds` (1061,1060, the fallback), `MpItemIds` (728), `AllowInOlympiad` (False), `Messages` (False), `OutOfPotionsWarning` (True), `RememberAcrossLogin` (True).

## How it differs from the stock `.apon` / `.apoff`
The stock auto-potion (`AutoPotions.ini`) is left alone and can run beside it. This module checks four times a second instead of once, so CP and mana potions can be spammed at their real 0.5 s reuse. It also falls through to the next potion in the list while the first cools down (the stock one stops at the first), has no per-use chat message, and one `.pots` command toggles it.

## Potion timers and when it will not drink
A potion is drunk only when its own reuse, its shared reuse group and its skill reuse are all over, and the module starts the item's reuse timer after each drink the way a click does, so it never goes faster than a click would (Quick Healing and Greater CP potions about twice a second) and a slow potion such as the Greater Healing Potion is not retried every tick. It does not drink while you are stunned, asleep, afraid, paralyzed, trading or in a private store.

## Limits
It only drinks potions already in the inventory. It does not buy or craft them.

Parallel potions: everything in a `...ParallelIds` list is drunk whenever it is off reuse, each on its own timer, so Quick (0.5 s) and Greater (10 s) Healing Potions run together. The `...ItemIds` list is the fallback, used only when you carry none of the parallel ones.
