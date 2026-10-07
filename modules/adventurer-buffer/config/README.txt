# Adventurer Buffer 0.4.3

Town support NPCs for Living Worlds Interlude. Server owners choose Basic, Mid or
an editable Custom buff set. Players use four controls: **Buff Me**, **Buff Party**,
**Remove My Buffs** and **Remove Party Buffs**, plus individual buff choices.

## Compatibility

Living Worlds module API 1; developed and locally tested with the v0.1.26 server
integration. The module references native skills and NPC appearance; no client patch
or client assets are included. Other server versions or modified skill data require
separate validation. NPC ID 59850 is reserved by this module. Declared conflicts:
`newbie-town-buffer` and `buff-limits`; do not run conflicting modules together.
Sixteen town placements are supplied beside Gatekeepers. This is not a claim that
every placement, tier, mixed-party combination and edge case was exhaustively tested.
The owner reported the installed 0.4.2 module working well. Version 0.4.3 adds only
a server-owner settings help footer; its native visual check remains pending.

## Configuration

Edit `config/module.ini`. Settings load at startup: use a controlled server restart,
not a client restart or hot reload. Back up your current configuration first.

| Setting | Release value | Meaning |
|---|---|---|
| Enabled | True | Starts on server startup; set False to disable. |
| Strength | 1 | 1 Basic, 2 Mid, 3 Custom. No Strength 4. |
| MinLevel | 1 | Minimum recipient level, inclusive. |
| MaxLevel | 32 | Maximum recipient level, inclusive. |
| BuffDurationSeconds | 3600 | Duration for newly accepted module effects. |

The configured level range must satisfy `1 <= MinLevel <= MaxLevel <= server maximum`
(80 on the tested pack). Duration must be an integer from 1 to 86400 seconds;
zero/permanent durations are not supported.

| Hours | Seconds |
|---|---:|
| 1 | 3600 |
| 2 | 7200 |
| 3 | 10800 |
| 4 | 14400 |
| 5 | 18000 |
| 6 | 21600 |
| 7 | 25200 |
| 8 | 28800 |

All three strengths use the configured duration, including short native songs,
dances and special buffs. Existing effects are not rewritten when settings change.
Shared skill definitions and unrelated native casts are not modified.

## Three strengths

**1 - Basic:** twelve rank-one support buffs, using native per-effect stacking.

**2 - Mid:** the same twelve foundation buffs at rank two, plus twelve songs/dances.

**3 - Custom:** the single comma-separated `CustomBuffs` list. It starts with twelve
maximum ordinary-rank foundation buffs, Greater Might 3, Chant of Victory 1,
Blessing of Queen 13, Gift of Seraphim 13 and the same twelve music effects as Mid.
This is **16 regular effects + 12 music effects**. Chant of Victory reduces movement
speed by **20%**. There is no automatic class or companion-role selection.

Each default entry is named in the config comments. Edit the numeric list to replace
the entire Custom selection; for example:

```ini
Strength = 3
CustomBuffs = 1068:2,1040:2,264:1
```

This selects Might 2, Shield 2 and Song of Earth 1. See
`config/SUPPORTED-BUFFS.txt` for all supported IDs, ranks and exclusive families.
To change a special-family choice, replace its entry rather than adding a second
member of the same family. Omitted `CustomBuffs` uses the supplied default; an empty
selected Custom list is invalid. Inactive Custom edits are ignored at Strength 1/2.
Unsupported IDs/ranks, duplicates, family conflicts and oversized recipes refuse
startup before NPC/handler registration. Previous experimental Strength 4 must be
changed to 3; old HighGreater/HighProphecy/HighQueen/HighSeraphim keys are not used.

## Limits and effect behavior

Custom recipes allow at most 20 regular buffs and 12 songs/dances. Actual recipient
caps are also checked for Mid/Custom. The default Custom set leaves four regular
slots at stock capacity, but existing unrelated effects may prevent the set fitting.
An edited music selection can conflict with music already present.

Basic attempts effects individually through native stacking. Mid/Custom preflight
each recipient: an existing different skill ID in a requested exclusive family or
insufficient slots skips that recipient's selected set and reports why. Other
eligible recipients continue. Same-ID requests use native abnormal-power rules:
lower power preserves the old effect and its remaining duration; equal/higher
power can replace it and refresh to the configured duration. Rank alone is not a
preservation guarantee. Queen/Seraphim ranks 3-13 share power 3 and main stat bonuses,
so rank 13 -> 3 may replace/refresh; other native metadata differs across those ranks.
Changing from Custom to Mid does not clear special buffs already present.

Party actions include the requester and eligible nearby, controlled, clientless
companions, not ordinary human party members. Level, range, control and session
checks can skip or interrupt recipients. Messages count submitted skill calls,
not guaranteed landed effects; earlier accepted effects are not rolled back.

**Removal uses broad native cleanup**, including buffs, debuffs, toggles and indefinite
active effects on selected eligible characters and their current summons. Use it
only when you intend that cleanup; it is not limited to this module's buffs.

## Installation and updates

1. Stop the server in a planned maintenance window. Back up the existing module,
   configuration and relevant server data using your normal backup process. Keep
   module backups outside the server's auto-discovered `game/modules` directory.
2. Place the single `adventurer-buffer` module directory under `game/modules` using
   the server's supported module installation process. Avoid double-nesting or
   installing a second copy. Preserve owner-edited settings/placements on upgrades;
   do not blindly overwrite them with release defaults.
3. Review `config/module.ini`, the level range, duration, Custom recipe and declared
   conflicts. The release ships enabled; set `Enabled = False` if you want to
   keep it disabled. Restart the server normally.
4. Check startup logs for successful module loading, 16 owned NPCs and no compile,
   configuration, conflicting-ID or duplicate-module errors before players use it.

## Smoke tests and rollback

On a suitable test character: open the NPC dialogue; confirm all four controls and
individual choices; test Buff Me and duration countdown. With controlled companions,
test Buff Party and confirm an ineligible companion is skipped without blocking others.
For Custom, check all three pages of the default 28-effect list, the expected effects
and Victory's movement penalty. Check configured level boundaries and existing
buff-family/slot conflicts. Coordinate any settings changes and restarts.

Deliberately test both removal controls only with effects you intend to clear.
Native expiry requires observing expiry, not merely a timer label; use a separately
planned short-duration test if necessary, then restore your chosen duration.

If loading or behavior fails: stop the server, restore the backed-up module and its
configuration, restart, and verify previous behavior. Keep logs for diagnosis.
Restoring files does not undo effects already granted; allow expiry or perform
intentional cleanup. Do not modify shared engine files to work around a failure.

## License and credits

See `LICENSE` and `NOTICE.md` for GPLv3 distribution and upstream attribution.
