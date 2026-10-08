# Beast Collar Taming

Turn eligible wild creatures into persistent, **individual** pets. Every capture produces a collar with its own
rolled profile, so two creatures of the same species can differ in rarity, potential, growth, affinity,
temperament, maximum level, and inherited skills. No client patch and no core JAR edits: each collar is given its
own private NPC id so the stock pet code resolves the right individual.

This is the largest module in the collection and the first with its own database tables. Read this page and
`config/module.ini` before enabling it.

## Capture
- **Ordinary monsters:** Broken Beast Flute (item `9300`).
- **Raid creatures:** Ancient Beast Bugle (item `9301`).
- Capture validates the target, level difference, health state, creature type, and a configured chance. Fake
  players and invalid targets are rejected.
- Success creates a **Tamed Beast Collar** (item `9302`) and a persistent profile for that individual.

## Each pet is individual
A collar stores its own source species, name, level and rolled level cap, rarity, potential and growth, role and
family, temperament, affinity, bond, awakening state, wounds, inherited skill deck, equipment, event history, and a
private synthetic NPC identity. Progression, two-stage awakening, affinity effects, and rarity effects are all per
collar.

## The reagent store
Every character is given a catalogue item (a "Spell Paper", id `9303`) on login and creation. **Double-click it**,
or type **`.tamestore`**, to open the reagent store: it shows both reagents with their real icons, names, and
prices, then an **OPEN SHOP** button that opens the multisell (the client's own quantity box and audited purchase
path). If the multisell list is missing or mispriced, the page falls back to its own 1/10/100 buttons so the store
still works.

## Commands
Voiced chat commands any player can use:

- `.tamestore` open the reagent store.
- `.tameprofile` / `.tameinfo` the collar passport and details.
- `.tamesummon` summon the collared pet.
- `.tameskills` / `.tameawaken` / `.tameaura` / `.tameautocast` the pet's skills, awakening, aura, and auto-cast.
- `.tamefeed` / `.tameheal` feeding and healing.
- `.tamebestiary` your capture record.
- `.tamediag` diagnostics.

## Requirements
A compatible L2J Mobius CT0 Interlude server with the module framework enabled (this module uses the framework's
generic pet/identity hooks). It also creates database tables on enable (see below), so the server's database must
be reachable.

## Install
1. Copy the `taming` folder into `game/modules/`.
2. Review `config/module.ini` (it is extensive) and confirm the reserved ranges do not clash with another module:
   items `9300-9399`, skills `9300-9399`, NPCs `700000-799999`.
3. It ships enabled (`Enabled = true`). Start the server. On enable the module runs `database/install.sql`
   (idempotent) before its code, so its tables exist when it loads.
4. Confirm the log reports the database install, synthetic NPC ids ready, restored collars, and enabled.

## Configuration (`config/module.ini`)
Everything the module reads is in this one file. The main groups:
- **Item ids and store:** reagent and collar ids, catalogue grant, prices, reagent multisell id.
- **Capture difficulty:** normal and raid chances and caps, wounded-HP weight, level-gap penalty, chance floor.
- **Level ceiling, per-species rules, pet behaviour, wounds.**
- **Hand gear** and **auto-cast** (interval, melee-first, heal percent).
- **Balance and stat ceilings** (HP, P/M Atk and Def, crit, speeds, accuracy, evasion).
- **Visuals, affinity effects and skills, awakening and rarity effects, race matchup.**
- **Experience share** (owner/pet split) and the **private NPC id band**.
- **OrphanCollarSweep:** defaults to `report` (finds and logs tame rows whose collar is gone, deletes nothing),
  because clearing them removes saved player data. Read the log before switching it to `true`.

## Database and removal
It owns five tables: `tamed_pet`, `tamed_pet_skill`, `tamed_pet_equipment`, `tamed_pet_history`,
`tamed_pet_bestiary`. To remove the module: disable it, stop the server, and delete the `taming` folder. The tables
are **kept** (they hold player pets). Dropping them is a separate, explicit, opt-in step via `database/remove.sql`,
never automatic.

## Ids and reserves
Items `9300-9399`, skills `9300-9399`, NPCs `700000-799999`. Reagents 9300/9301, collar 9302, catalogue 9303.

## License
GNU General Public License v3.0.
