# Phantom Management - maintainer notes

`.phantom` opens an in-game HTML window (Home / Party / Targets / Phantoms) that drives the existing phantom party system.
Every button is a `bypass -h ph\\\_...` command handled by this module.

## Files

|  File  |  Purpose  |  
|  -  |  -  |  
|  `module.json`  |  Manifest. Entry point `modules.phantommanagement.PhantomManagementModule`, apiVersion 1, no database, no reserved ids.  |  
|  `config/module.ini`  |  Only setting: `Enabled`.  |  
|  `scripts/PhantomManagementModule.java`  |  Entry point. Registers the `.phantom` voiced command and the `ph\\\_\\\*` bypass handler.  |  
|  `scripts/PhantomPanel.java`  |  All window pages, button actions, auto-refresh and buff/heal/music logic.  |  

## Enable / disable

Edit `config/module.ini` and restart. With `Enabled = False` the entry point returns before registering anything,
so the server behaves as stock. Deleting the module folder has the same effect.

## Registered handlers

* Voiced command: `.phantom`
* Bypass commands: `ph\\\_home`, `ph\\\_party`, `ph\\\_targets`, `ph\\\_phantoms`, `ph\\\_help`, `ph\\\_invite`, `ph\\\_drop`,
`ph\\\_do`, `ph\\\_mem`, `ph\\\_more`, `ph\\\_buff`, `ph\\\_buffs`, `ph\\\_musicpage`, `ph\\\_music`, `ph\\\_cubics`,
`ph\\\_cubic`, `ph\\\_tp`, `ph\\\_teleport`, `ph\\\_pull`, `ph\\\_size`, `ph\\\_find`, `ph\\\_findclass`, `ph\\\_craft`,
`ph\\\_sex`, `ph\\\_view`

## What the window contains

* **Home:** Party Stance (Hold / Assist / Farm / Follow), Camp (Set Camp, End Camp, Stop Pull, Pull 1/2/3 and the
current puller), Party Control (Buff Me, Buff All, Recharge, Stop MP Up, Heal, Heal Full, Stand All, Sit All,
Loot, Cubics?, Weapons?, Travel, Disband).

* **Party:** up to 8 members, each with role, level, HP %, **Manage** and **Drop**.

* **Manage (per Phantom):** common stance buttons plus Pull (Tanks and DPS only); class-specific sections for
Support (Bishop, Prophet, Shillien Elder, Elven Elder, Warcryer, Overlord), Singer, Dancer, Cubics
(Temple Knight, Shillien Knight) and Weapons (Gladiator, Warlord, Destroyer, Bounty Hunter, Warsmith).
Buttons that do not apply to the class are not drawn.

* **Buffs / Songs / Dances / Cubics pages:** per-Phantom lists built from the skills the Phantom actually knows.

* **Travel:** 15 destinations (Talking Island, Elven, Dark Elf, Orc, Dwarf villages, Gludin, Gludio, Dion, Giran,
Oren, Aden, Heine, Hunter's Village, Rune, Goddard).

* **Targets:** your target (name, level, HP %), Raid Gate (Tank Attack, All Attack, Tank Holds, Stop Fight) and
Raid Orders (Heal, Buff All, Resurrect, Recharge, Stop MP, Stand All, Sit All, Loot).

* **Phantoms:** Recruit and Friends sub-tabs. Both list the 31 Interlude second classes by race (Human, Elf, Dark Elf,
Orc, Dwarf) on two pages. Recruit puts the Phantom in the *Waiting for your invite* list; Friends creates a
persistent Friend with chosen gender, name and class.

## Behaviour worth knowing

* **Buff Me refresh:** repeats the current singer/dancer rotation through exact song/dance requests, so a
second Buff Me works even while the Phantoms still carry their own song/dance effects.

* **Heal Full:** the selected healer keeps healing the target every \~1.8 s until the target is full, with a
60-second safety stop. One loop per player (a new click replaces the old one); it also stops when the healer
or the target leaves the party.

* **Stand All / Sit All:** Stand All keeps all Phantoms standing (no MP rest) until Sit All is pressed. The loop stops
by itself when the player goes offline or has no Phantoms left in the party.

* **Auto-refresh:** the Phantoms, Party, Targets and detail pages refresh themselves while open.

## Safety rules

* **Ownership:** a player can only command Phantoms that belong to them (Manage, Drop, buffs, heals, stance, Stand All,
  recharge, music...). Other members of the party are listed without buttons. The owner is read from the core
  (`PhantomPartyManager.getRecruitOwner` / `PhantomBuddyManager.getBuddyOwner`), the same owner the core's own
  party-chat commands check.
* **Friends:** per-player limit (`MaxFriendsPerPlayer`), cooldown, two-click confirmation, letters/digits only
  (max 16), the server's `ForbiddenNames` (Player.ini) and reserved fake-player names plus `ExtraForbiddenNames`, and only the 31 classes of the tab. Friends created here and later removed from the friend list still count toward the limit, because their character stays saved; deleting and re-creating cannot pile up characters.
* **Cleanup:** every 60 s the module forgets the panel data of players who are no longer online.

## Core access

The module uses the public `PhantomPartyManager`, `PhantomBuddyManager`, `PhantomManager` and `PhantomBuffs`
APIs for most actions. It does **not** modify any core file.

It also reads and writes a few private fields of `PhantomPartyManager` and its `Member` / `Camp` inner classes
through reflection (`\\\_members`, `\\\_camps`, `healNow`, `rechargeTarget`, `assist`, `following`, `holding`,
`noSitUntil`, `pulling`) to implement Heal Full, Recharge, Stand All and pull state. If those fields are renamed
in the core, the module logs `\\\[Phantom Management] Core support reflection unavailable` and those features stop
working. Moving them behind public hooks in the core would remove this dependency.

## Language note

All window text is in English.

