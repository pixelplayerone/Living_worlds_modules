# Beast Collar Taming Module

A server-side creature-taming expansion for **L2J Mobius CT0 Interlude**.

This module turns eligible wild creatures into persistent, individual pets without modifying the client and without requiring edits to the core JAR. Each captured creature receives its own collar, profile, rolled attributes, skills, progression, equipment state, and history.

The module is designed to be installed, disabled, removed, and updated through the server's module system.

## Design goals

- Keep the original Interlude client unchanged.
- Avoid core JAR edits.
- Use existing client-known NPC appearances, icons, skills, and item records wherever a visual identity is needed.
- Give each captured creature an individual identity rather than treating every species as one generic pet.
- Keep the system understandable and configurable by server administrators.
- Fail safely when a client-side visual or native server hook is unavailable.

## What the module provides

### Creature capture

- Ordinary monsters can be captured with the Broken Beast Flute (`9300`).
- Raid creatures can be captured with the Ancient Beast Bugle (`9301`).
- Capture validates the target, level difference, health state, creature type, and configured chance.
- Fake players and invalid targets are rejected by the taming rules.
- A successful capture creates a Tamed Beast Collar (`9302`) and a persistent profile.

### Individual pet profiles

Every collar stores its own:

- Source NPC and species
- Pet name
- Level and rolled level cap
- Rarity
- Potential and growth
- Role and family
- Temperament
- Affinity
- Bond
- Awakening state
- Wounds and recovery state
- Inherited skill deck
- Custom equipment
- Event history
- Private synthetic NPC identity

Two creatures of the same species can therefore have different potential, growth, rarity, skills, affinity, temperament, and maximum level.

### Persistent collars

The collar is the pet's control item. It is bound to its owner and is not tradable, droppable, sellable, or depositable.

The collar opens a server-backed passport with pages for:

- Profile
- Skills
- Equipment
- Imprint and affinity
- Awakening
- History
- Commands
- Wear and summon state

The collar uses a client-known display identity so the untouched client can draw it. The module-owned item ID remains `9302`; the visual identity is only a client rendering fallback.

### Skills

The module can inherit safe active skills from the source creature and place them into a persistent skill deck. Skill slots unlock through progression and awakening.

Skills fire on their own. There are two paths, and neither of them is a button the
player presses to pick one technique:

1. Server-side auto-cast, which is the tame's casting surface and is on by default.
2. The native pet skill bar when the current client/server build accepts the
   pet-template parameters.

Auto-cast works the whole deck, not just the handful of techniques the client's pet
window can show. The bar is bound to four parameter names because that is what the
client's pet window offers, but the rotation reads every usable row - signature,
second signature, utility, advanced, awakening and the element and second-awakening
slots, up to eight - so the later techniques are actually used instead of being
silently dropped once the four visible buttons are full.

The collar page's per-technique `USE` buttons, and the `tamecollar_skill` bypass that
backed them, were removed. A manual fire could only ever disagree with auto-cast's
decision: it aimed at whatever the player happened to have selected rather than what
the beast was fighting, it burned a technique's cooldown on that one cast, and for the
self-targeted damage techniques the client had already replaced the player's selection
by the time the packet arrived. The deck page now reports unlock level, awakening state
and passivity per technique, which is what "it never used that" actually needed to be
distinguishable from "it was never going to".

Native pet-bar binding uses the existing client-supported parameter names such as:

```text
DDMagic
HealMagic
Buff
Buff1
```

The pet bar is different from the player's normal shortcut bar. The normal player action bar resolves skills against the player and has no pet-caster or collar-object field. Arbitrary custom pet skills therefore must not be placed in the player's normal skill toolbar.

### Awakening

- First awakening unlocks a permanent path at the configured level, normally level 30.
- Rare and higher rarities can receive additional awakening techniques.
- A second awakening can unlock the later technique and affinity-based proc.
- Awakening choices are stored per collar and survive relogging.

### Pet commands

The command page provides server-side commands such as:

- Follow
- Guard
- Attack target
- Hold
- Return

Commands are sent through validated module bypasses and affect only the collar's own summoned pet.

### Pet food, wounds, and bond

- Food uses configured stock item tiers.
- Hunger and feeding are handled through the module's pet-food logic.
- A pet that dies can return wounded.
- Wounded pets cannot be summoned until they are fed or healed.
- Bond and recovery events are recorded against the individual collar.

### Custom equipment

The equipment page supports three collar-bound slots:

- Weapon
- Armor
- Accessory

Equipment is taken from the owner's inventory, validated, and kept whole in a private per-player container (`TameGearVault`) while it is installed. The row in `tamed_pet_equipment` records only which object id is held. Because the real `Item` is moved rather than destroyed and rebuilt, an augment, a Shadow item's remaining mana and elemental attributes survive both removal and a restart. The item is invisible to the player while installed - it is parked on `ItemLocation.LEASE`, which no player-facing container reads - so it cannot be equipped, traded, sold or dropped off the tame. It is returned when removed, replaced or released.

Equipment bonuses are applied to the pet's calculated statistics. This functional equipment path does not require client changes.

Visible equipment is limited to the weapon in the tame's hand. A summon's spawn packet reads its weapon from the pet's own inventory, not from its NPC template: the `HandsDebug` log showed `template=5286 pet.getWeapon()=0` and nothing was drawn. So before a tame spawns, the weapon its wild species carried is placed in the pet's weapon slot (`ShowWildWeapon = true`). It is always the species' own weapon, because the model has animations for that weapon type, and a different type is what made earlier attempts slide and freeze. Creatures that never carried a weapon are left empty-handed, and a weapon installed on the collar affects stats only.

`HandsDebug = true` logs one line per summon with the wild weapon, the template's weapon, and what the pet reports (`pet.getWeapon()` is what the client is told to draw). Use it when a weapon is not drawing.

### Bestiary and history

The module records species discoveries and individual milestones, including capture, growth, awakening, bond, equipment, and other profile events.

## Module layout

```text
module.json                         Module metadata and resource registration
config/module.ini                   Runtime configuration
scripts/taming/                     Java module scripts
data/items/                        Flute, bugle, and collar definitions
  data/skills/                       Module-owned capture and pet skills
  data/multisell/                    Reagent list behind the store's window button
database/install.sql                Idempotent module database installation
database/remove.sql                 Module database removal
MODLOG.md                           This guide and changelog
```

## Installation

1. Use a compatible L2J Mobius CT0 Interlude server with the module framework enabled.
2. Copy the complete `taming` module directory into the server's module directory.
3. Check `config/module.ini` before enabling the module.
4. Ensure the module's reserved item, skill, and NPC ID ranges do not conflict with another module.
5. Start the server or reload modules through the supported module manager.
6. Confirm the module log reports:

```text
compiling module scripts
running database install
ready: synthetic NPC IDs
restored collar(s)
enabled
```

The database installer is intended to be safe on repeated module starts. Do not manually delete the module tables unless the removal script is being used intentionally.

## Important configuration

```ini
Enabled = true
NormalTamingItem = 9300
RaidTamingItem = 9301
CollarItemId = 9302

NormalChance = 10.0
NormalChanceCap = 10.0
RaidChance = 0.5
RaidChanceCap = 1.0
WoundedHpWeight = 0.4
LevelGapPenalty = 2.0
ChanceFloor = 0.0

LevelBonus = 5
LevelCap = 80

AutoCast = true
AutoCastIntervalMs = 1000
AutoCastMinGapMs = 2000
AutoCastSkillCooldownMs = 5000
AutoCastMeleeFirst = true
AutoCastHealPercent = 70

HandsDebug = false
```

The exact values can be changed in `module.ini`. Restart or reload the module after configuration changes when required by the module manager.

## Reagent dealer

**Double-click the Spell Paper in your bag.** Every character is given one on login and
on creation, so it is always there. The same store also opens with **.tamestore**, which
any player can use: a voiced command, the same kind as .tameprofile, not an admin one.

The page is an entrance, not a second shop. It shows both reagents with their real icons,
their names and their prices, then one **OPEN SHOP** button that opens multisell `9300`. The
window is the better surface - it has the client's own quantity box and its purchase is the
audited `MultiSellChoose` path - so the page does not try to compete with it. There are no
quantity buttons here in the normal case.

The exception is deliberate and automatic. `TamingData.verifyMultisellPrices` reads the list
at startup and decides whether the window is expected to work; `TamingData.isMultisellUsable`
carries that one bit. If the list is missing, has lost its `<npc>-1</npc>` entry, or is
mispriced against `module.ini`, the flag is false and the page stops being an entrance: it
says the window is unavailable and draws the reagents with 1 / 10 / 100 buttons that charge
`module.ini` directly. A broken list therefore costs the window and not the store. On a
healthy boot the player sees the OPEN SHOP button and nothing else.

### Why an item and not Adena

Adena was tried first, because it is in every bag and double-clicking it does nothing.
It does not work, and the reason is worth keeping.

Item handlers are reached from the `UseItem` packet, and **the Interlude client does not
send `UseItem` for currency**. No packet means no handler, whatever the server declares.
It was implemented properly first - the module redefined item 57 with a handler, which
`ItemData` does permit, since `load()` parses stock items, then module items, into one
map with a plain `Map.put` and never checks the id for collisions. It compiled, it
validated, and nothing happened in game. The override was deleted rather than left in
place redefining the game's currency for a feature that cannot fire.

Worth recording what that attempt cost, since it nearly shipped: a `Map.put` replaces
rather than merges, so redefining item 57 meant the module carrying its own copy of the
whole currency definition, and any `<set>` dropped from that copy changes Adena for
every player on the server.

### Why an item works

The collar is the proof, in this same build: it is an ordinary `EtcItem` with a handler
and double-clicking it opens the passport. `UseItem` does not consult `default_action`
before dispatching - it calls the handler for any `EtcItem` outside the paperdoll slot
range - so the click is not conditional on any action setting.

Item 9303 is that same shape. Its handler is `TameStoreCatalogue`.

### How the handler lookup works, because it is by name and not by id

- `ItemHandler.registerHandler(h)` does `_datatable.put(h.getClass().getSimpleName(), h)`
- `ItemHandler.getHandler(item)` does `_datatable.get(item.getHandlerName())`
- `EtcItem.getHandlerName()` is the item's `<set name="handler">` value

So the `val` in the item XML must stay exactly equal to the handler class's simple name.
If they ever disagree, `UseItem` logs a warning naming the handler and the item id and
the click does nothing. That warning is the first thing to look for.

### Why it is called "Spell Paper" in the bag

`AbstractItemPacket` writes `getDisplayId()` and **no name string**, so the client takes
both the icon and the name from the display id. Item 9303 borrows 1695, stock Spell
Paper, whose icon is `etc_piece_of_paper_white_i00` - a sheet of paper.

There is no server-side way to rename it. The same is true of the other module items:
the collar borrows 118 and is drawn as a necklace. Changing what an item is called means
changing its `displayId` to a stock item whose name reads better.

### How the player gets it

Granted, not sold - a shop you need an item to buy before you can buy anything is a
chicken-and-egg problem. `GrantCatalogue` checks the inventory by item id on every login
and creation, so nobody accumulates duplicates, and a player who destroys theirs gets it
back next login. It is not tradable, droppable, sellable or depositable.

### Why it is not a Pet Manager dialogue

This is worth recording because the opposite was believed for a while and it cannot
work. The module framework does accept an `html` resource root and the eleven
Pet Manager dialogues were written and shipped under it. None of them was ever served.

Only five loaders read a module resource root at all: `ItemData`, `SkillData`,
`MultisellData`, `NpcData` and `SpawnData`. `HtmCache` is not among them. `ModuleResourceType`
does have an `HTML` value, which is what made this look supported - the enum entry
exists and nothing consumes it. A module file would be cached under its own path
relative to `DATAPACK_ROOT`, something like `modules/taming/data/html/petmanager/30731.htm`,
while a dialogue asks `HtmCache` for `petmanager/30731.htm`. The two keys can never
match.

So the overrides were deleted along with the `html` resource declaration, rather than
left in the package looking like they worked.

Repurposing an NPC as a shopkeeper is not possible either, for a separate reason.
`ModuleHandlers` offers exactly six hooks - voiced command, admin command, bypass,
item, effect and target. Nothing fires when an NPC is clicked or talked to, so no
code in a module can intercept the interaction and open a page in its place.

What does work is what the collar passport already did: build the page in code and
send it with `NpcHtmlMessage`, with bypass links the registered handler picks up. That
is what the store does, which is why it needs no file and no NPC.

### Why a generated page and not a stock one

There is no way to make a stock dialogue file appear. The framework does not read
module HTML, so the only surface a module owns is a page it sends itself.

### Why a multisell and not a shop entry

A merchant buy list cannot carry a module item. The `BuyList` packet sends three shorts per row - item id, price, and an unlimited-stock marker - and no display id. The client draws a shop row's name and icon from its own `itemname-e.dat` and `etcitemgrp.dat`, so an item id it has never seen renders as a blank row that can still be clicked and bought.

A multisell has neither problem. The client keeps no multisell data of its own, so the window is drawn entirely from what the server sends, and `MultiSellList` includes each product's display id. That is the same mechanism that makes the reagents look right in the inventory.

Crafting was considered and rejected. `ModuleResourceType` has no `CRAFTS` value, so recipes could not ship in the module at all, and `RecipeItemMakeInfo` writes only a recipe id, MP, and a success flag - never an item id - meaning the client resolves what a recipe makes from its own tables. A custom recipe would draw blank for the same reason a buy list would.

The alternative is shipping patched `etcitemgrp.dat` and `itemname-e.dat`, which would make every player install client files and risk the client rejecting them. Nothing outside the module is needed for this route.

### What this buys

- A real window with icons, names, and - because both reagents are stackable - a quantity selector.
- The window's transaction is `MultiSellChoose`, the same audited path every other multisell in the game uses. Adena checking, weight, capacity, the item handover, and the system messages are all the core's.

### What it costs

- Two files hold these prices. `NormalTamingPrice` and `RaidTamingPrice` in `module.ini`
  charge the store buttons, and Adena has to be an ingredient of a multisell row, so
  the window's prices are in `data/multisell/9300.xml`. `TamingData.verifyMultisellPrices`
  reads that XML at startup and logs a warning if the two disagree, so a price changed
  in one file and not the other is reported at boot rather than quietly selling at the
  cheaper amount. A missing or unreadable list is reported the same way, and that is what
  clears `isMultisellUsable`, so the page falls back to its own buttons rather than offering
  a window that will not open.
- A reagent id changed in `module.ini` also has to change the matching `production`
  id in the list, which the same startup check reports.
- The multisell id `9300` is a module-owned id and must not collide with another list.
- **The list carries `<npcs><npc>-1</npc></npcs>`. Do not delete it, and do not replace it
  with a real NPC id.** This is the single line the whole store window depends on, and it is
  the opposite of what the surrounding reasoning used to say. `MultisellData.separateAndSend`
  refuses to send a list to an ordinary player unless `ListContainer.isNpcAllowed` says
  otherwise, and `isNpcAllowed(-1)` is true because the core looks the id up in a map the
  negative id is not in. Sending `-1` is what makes the list openable by everyone rather
  than by GMs only. It was added precisely because the list as originally written had no
  `<npcs>` block, which made it GM-only.

### Why the page leads with the window, and what the buttons are for now

The window has been watched working. A player with GM status off opened list `9300` through
the store page and bought from it, which retires the reason the page used to lead with its
own buttons: the worry was that the client might decline to draw a `displayId` it had never
been sent, leaving a blank row. It does not draw it blank. The page therefore leads with the
window and drops its quantity buttons.

The buttons were not deleted, they were demoted. They are drawn only when
`isMultisellUsable()` is false, so the failure mode is a page with different buttons on it
rather than a store that cannot sell. They still charge from `module.ini` and hand the item
over as a normal item, adding it before taking the Adena so a full inventory leaves the
player their money and a message instead of taking payment for nothing.

The bit is a startup reading, not a runtime probe. It cannot be wrong about whether the
window opens at runtime; the worst a wrong bit can do is draw the wrong page. That is the
trade for not re-reading and re-parsing the list on every store visit.

### Icons on the page, and why decoration is allowed to fail

Item icons on the store page are `<img src="Icon.*">` references. The client resolves these
against `systextures\Icon.utx`, which is a texture package inside the client, so the server
can only ever *name* a texture it already stocks. There is no server-side route to a custom
icon: no packet in the serverpackets package carries image bytes apart from `PledgeCrest`
and `AllyCrest`, `ItemTemplate` stores an icon *name* and never pixels, and
`ModuleResourceType` has no crest entry at all, so a module cannot supply crest art through
the framework either.

Every icon goes through one `iconTag` helper, which returns an empty string for an unusable
name. That is the whole safety argument. The store's buttons are `<button>` tags carrying
their own bypass and their own text, so the purchase path never depends on a picture
drawing: an unresolvable texture leaves a gap in the layout and nothing else. Decoration is
therefore allowed to fail, which is what makes it safe to decorate.

There is exactly one call site left: the reagent row, drawing at 32 by 32. Thirty-two is
what the client's own inventory uses for an item, so the page matches the rest of the game
instead of looking like a picture pasted into a web page, and the two reagents are hard to
tell apart in words ("flute" and "bugle") but obviously different at a glance. The size is
the named constant `REAGENT_ICON_SIZE` rather than a literal in the tag.

The icon name is read from `ItemTemplate.getIcon()` at page-draw time, so the shop hardcodes
no texture names at all: whatever icon the item carries is the icon drawn. An earlier version
hardcoded `icon.etc_flute_i00` and `icon.item_normal24` and also drew an Adena coin read from
item 57. The hardcoded names were dropped because they could drift from the item data they
describe, and the coin was dropped so the price is text only and the row is not three
pictures wide. Anything that cached an icon in a static was removed for the same reason as
the `adenaIcon` helper it replaced: this class loads when the module registers its bypass
handler, a different moment from `ItemData` being ready, and a null cached then would cost
every price on the page its icon for the life of the server.

### What this HTML engine does not support

Two things were tried on this page and are now permanently ruled out. Both were tried in
the same deploy as other changes, so neither is claimed to be diagnosed to the single
attribute; they are ruled out because the page is stable without them.

**Cell colours crash the client.** `bgcolor` on the table and on the header row, coloured
with the measured average of the Adena icon, crashed the client. The page now emits no cell
colours at all and takes its background from the client's own window. Do not reintroduce
`bgcolor` to any page in this module.

**Tiled textures cannot be made seamless.** The plan before cell colours was to tile 1 by 1
Adena swatches across the panel to get that brown from a texture the client really has. It
cannot be done: this engine will not butt images together with no pixel of gap, and any gap
at all draws a visible grid across the panel. A 1 by 1 image scaled up does render, which
is why the idea looked reasonable, and a 2 by 2 does not. Cell colours were the replacement
and they are not available either. So a textured or coloured panel background is not
reachable from this module, and the window background is the background.

A temporary diagnostic page, `tamepix`, existed to test these questions on the live client
and was removed once the answers were had. It had briefly been drawn under the shop button,
where a grid of tiny cells read as a black square and made the shop look broken.

## Client and core boundary

The following are intentionally not changed by this module:

- Client system files
- NPC client tables
- Client textures or meshes
- Client skill definitions
- Core JAR source or compiled JAR files
- Player shortcut packet behavior

The module may use the module framework and narrowly scoped reflection against exposed runtime objects to create per-collar synthetic templates. This is not a core edit, but it remains sensitive to changes in private field names between Mobius revisions.

## Known limitations

### Player action bar

Arbitrary pet skills cannot safely be placed in the player's normal action bar. The player action-bar packet resolves the skill against the player and does not carry a pet or collar target. Borrowing stock skill IDs creates incorrect names, icons, caster behavior, or real player skills.

Use the collar page, native pet bar, or auto-cast instead.

### Visible pet equipment

Functional stat bonuses are separate from client rendering. A weapon or armor item can affect pet stats even when the creature model cannot display it.

The client may not visibly render equipment on wolves, insects, dragons, raid models, slimes, or other non-humanoid creatures. Complete armor sets are especially model-dependent.

### Client-known visual identities

A custom server NPC or item ID has no client model by itself. When a visual is required, the module must borrow an identity already known to the untouched client. Do not remove deliberate `displayId` fallbacks from the collar or reagent item definitions.

### Affinity auras

The aura is a bit in the `abnormalEffect` field of `CharInfo`, chosen from the 60 fixed
values of `AbnormalVisualEffect`. Three things follow, and all three were established by
reading the core rather than by guessing:

- **A beast can wear several at once.** `effectsFor` returns a collection, so the affinity
  effect, the awakened overlay and a rarity effect stack rather than compete. This is
  layering by effect choice, not by scale, because scale is not reachable (see below).
- **Rarity auras ship as `NONE`, deliberately.** `RarityEffectRare` and
  `RarityEffectLegendary` exist and are wired, and both default to no aura. The reason is
  blast radius, not indecision: a rarity aura applies to every rare beast in the game at
  once, so auditioning one by name is the wrong tool and shipping an unwatched name would
  dress a few hundred beasts with something that might turn out to be wrong. This is the
  same lesson as `ULTIMATE_DEFENCE` as holy, one tier up.
- **The rarity effects are in the undo set.** `ownedEffects` collects everything the module
  might have applied, so a config change can be undone. A rarity effect left out of it would
  survive a reload and could never be taken off a beast that had been dressed while it was
  configured - it would not be a cosmetic setting but a permanent one-way change.

- **The vocabulary is closed.** There is no way to point this at a raid boss's aura. Boss
  auras are client effect data referenced by skills, a different mechanism from the
  abnormal-status bitmask this module uses. A boss effect id is not an
  `AbnormalVisualEffect` and cannot be made into one.
- **Size cannot be changed.** Every packet in the serverpackets package was checked for a
  scale field and there are none. The client draws each effect at a size fixed in its own
  data. No command or config value makes an aura bigger. `BIG_BODY` and `BIG_HEAD` scale
  the creature, not the glow.
- **A name is not a look.** The enum says which skill data uses an effect, not what it
  draws on an arbitrary beast model. Six names have now been ruled out by looking, and
  they failed in three distinct ways worth recognising: pinned to the spot in the world so
  the beast walks out of it (`MAGIC_SQUARE`, `GHOST_STUN`), drawing nothing at all
  (`FROZEN_PILLAR`, `ULTIMATE_DEFENCE`, and per the live client `MP_SHIELD` and
  `SPEED_DOWN`), and rendering as the wrong idea (`DOT_BLEEDING` reads as a wound).

The whole `DOT_*` family is damage-over-time visuals, and a DoT visual pulses on the tick
that drives the damage. `DOT_FIRE_AREA` was reported as a flamethrower that appears,
disappears and reappears. That is the effect working as designed, so it cannot be tuned
away - an affinity wanting a steady glow has to use something outside `DOT_`. `EARTH` is
currently on `DOT_SOIL` and is in the same family.

### Chosen affinity effects, as decided in game

> **Superseded.** This table records an early pass and is kept for the reasoning, not as the
> current configuration. The values the module ships are the `AffinityEffect*` lines in
> `module.ini`; see "Final picks" below and the "Affinity looks fall back to effects only"
> entry in the changelog for the current state.

These are the values picked by actually looking at them on a beast, not by reading names.
Several differ from the first suggestions, and the reasons are the point: the effect that
*reads* as an element on paper is not the effect that reads as one on the model.

| Affinity | Effect | Note |
| --- | --- | --- |
| Water | `SEIZURE1` or `SEIZURE2` | both work, either is fine |
| Wind | `FLOATING_ROOT` | |
| Earth | `ROOT` | |
| Dark | `FLESH_STONE` | |
| Holy | `DANCE_ROOT` | |
| Fire | **undecided** | the only one still open |

`GHOST_STUN` does draw for fire, but it is pinned to the spot in the world, so the beast
walks out of it. That is the same pinning that ruled it out as a water candidate.

Two values are claimed by awakening and must not be reused as affinities:

- `BIG_BODY` is the first awaken overlay. `BIG_HEAD` was considered and **rejected** - it
  was not the look wanted, and first awaken has one overlay, not two.
- `SLEEP` is the **second** awaken overlay, added when second awakening went in.

### Item effects are real, but they are not auras

The question came up of whether item effects could supply fire. They exist, and the core
sends them:

- `AbstractItemPacket` writes `ItemInfo.getEnchant()` and `ItemInfo.getAugmentationBonus()`.
- The client turns enchant level into the familiar weapon and armour glow, and resolves the
  augmentation id against `variationeffectgrp-e.dec` (20537 bytes, a repeating record table
  covering weapon and body variants).

Neither can be added to the aura lab as another ON button, and the reason is structural
rather than a missing feature: **both effects are properties of an item, not of a creature.**
There is no item to attach one to on a plain monster or a rival player, so a lab that
targets any creature cannot use them. They are also a small fixed set of looks chosen by
item type and enchant level, not an open vocabulary of effects.

The one place they would work is the player's own beast, which can hold a weapon - an
enchanted or augmented pet weapon glows. That is a weapon glow, though, not an aura around
the body, and it would modify a real item to achieve it. Treat it as a separate feature to
ask for deliberately, not as a fire affinity.

**What an echo crystal actually triggers: nothing visible.** Worth writing down, because it
is a natural wrong guess and it was checked rather than assumed.

There are two generations of them and neither draws:

- The classic crystals (831-835, plus the Carol and Birthday/Wedding variants) carry **no
  skill and no handler**. They are `immediate_effect`, stackable, non-tradable materials -
  pure inputs to the augmentation interface.
- The "Theme" crystals (4411-4417) *do* carry skills - 2067-2073 - but those skills are named
  `Item - Theme Sound Crystal` and `Item - Race Sound Crystal`. They play **audio**. No visual.

What people are remembering seeing is the **glow on the augmented item itself**, and that is
not the crystal's doing. The crystal is a reroller: given an already-augmented item it re-rolls
which stat bonus the item carries. The glow belongs to the item's augmentation id, and it is
drawn whether or not a crystal was ever involved. Fail the reroll and nothing changes and
nothing new lights up.

**So the glow cannot be taken standalone.** Two independent reasons, either of which is
enough. It is an item field, so there has to be an item: the lab deliberately points at plain
monsters and rival players, which have no augmentation and no mesh to glow. And even on the
one creature that can hold a weapon, it would draw on the weapon mesh rather than around the
body. Taking it as a fire affinity would also mean writing to a real item, which was
explicitly ruled out.

The table itself, for anyone who wants to look: `variationeffectgrp-e.dec` holds **288 rows**,
and every one of them is named `Augmented`. They are told apart by id, not by name, so there
is no list of 288 looks to choose from - there are 288 numbers to try. The row id is the third
int of the record and is the value that arrives as `ItemInfo.getAugmentationBonus()`.

### Boss and skill effects are a third mechanism, and not reachable cheaply

The vocabulary of this aura is genuinely closed, but not because the client only knows 24
visuals. There is a third system: skill effect data, which is what boss auras actually use.
It was not pursued at first because this file was believed to be encrypted - `skillgrp.dec` is
3.2 MB with no readable effect names, where `Engine.dll` and `variationeffectgrp-e.dec` both
yielded their structure. **That belief was wrong and has been corrected.**

`skillgrp.dec` is **not encrypted**. It begins with the `tt` magic and its strings are
UTF-16LE, not ASCII - the readable content is `icon.skill0003` written in wide characters.
A search for ASCII effect names finds nothing and looks exactly like an encrypted blob. It is
a plain table.

The correction matters, because it changes the cost. Reading the table is straightforward, and
it yields **skill id to effect id** mappings - the effect is a number, not a name, so there is
still no way to learn what an id *looks* like from this file. That part of the difficulty was
real.

### What the encryption tooling can and cannot reach

`open-l2encdec` (ritsuwastaken, with prebuilt Windows binaries) was fetched and tried against
`lineageeffect.u`, 2006536 bytes, header `Lineage2Ver411`. **It cannot decode it, and that is
by construction rather than a missing key.** Its RSA path is:

```
zlib.compress(whole file)  ->  rsa.encrypt(entire compressed blob)
```

RSA-1024 carries about 117 bytes. The tool is built for L2 `.ini` config files, where the
whole payload fits in one RSA operation. A 2 MB game table cannot go through that path at all,
which is why 411, 412, 413 and 414 all report "Failed to decrypt file" and the legacy flag
hangs. Wrong tool for the job, not a wrong key.

`lineageeffect.u` therefore uses the other L2 scheme: the chunked format, where the payload is
split into 4096-byte blocks and each is wrapped separately with a key held in the client
executable. That needs a different tool and the Interlude key extracted from `L2.exe`. It was
not worth doing, for the reason in the next section.

Two tables were confirmed already plain and readable: `mobskillanimgrp.dec` (magic `W`, 14
fields, extracted to `mobskillanim_fixed.csv`) and `variationeffectgrp-e.dec` (288 rows).

### Skill effects, the third visual system, and how to audition them

Reached from the aura lab's SKILLS button. A separate page rather than more rows on the first
one, because the two lists differ by two orders of magnitude and a shared page would mean
either one enormous page or a confusing index space.

**What is in the list.** Every base-game skill id from the core's own
`SkillData.getBaseGameSkillIds()`, level 1, filtered to what a bench may safely cast. The
counting done against the server's own XMLs: 2715 base skills, 986 of them `A2` continuous,
**902 of those named, damage-free and with a real duration** - and that is the size of the
page. Nothing here can hurt a target; `isDamage` is filtered out.

The filter and why each clause is there:

- `isDamage` out - the point is to look at a picture, not to fight with one.
- passive, toggle, dance, self-kill out - none draw a cast effect on a target, and a suicide
  skill would rather not.
- continuous only, and only with a positive duration - an instant effect leaves nothing on
  screen to judge.
- named - a button with no caption cannot be reported back afterwards.

**Ordering is a hint, not a filter.** Element-sounding names sort first - fire, flame, burn,
blaze, ember, pyro, ignit, heat, torch, inferno, meteor, lava - giving **26 of them** on the
opening pages: Flame Chant, Aura Fire Wide, Chill Flame, Blaze Quake, Seal of Flame, Fire
Vortex, Bless of Fire, Dance of Fire, Master's Blessing - Prophecy of Fire, and so on. The
other ~876 follow alphabetically. Nothing is hidden by this, so a wrong guess costs a page
of scrolling rather than a missing button.

**`activateSkill`, not `useMagic`, and that is the whole trick.** The normal casting path
checks whether the caster knows the skill, whether the target is in range, and whether there
is enough MP. All three are correct for a real cast and all three are wrong for a bench,
where the skill is being tried *precisely because* the player does not have it and the
target may be across the map. `activateSkill` skips those gates and still sends the cast
packet, which is the only part that makes the client draw anything.

**OFF works by handle, not by id.** After casting, the buff is looked up with
`EffectList.getBuffInfoBySkillId` and that live `BuffInfo` is kept, so OFF can call
`EffectList.remove(SkillFinishType.REMOVED, buff)`. Re-deriving it from the skill id alone
would be ambiguous once two skills are up. The message after each cast says whether anything
actually stuck - "it stuck, OFF will take it off" versus "this one was only a flash" - which
is the single most useful thing the bench can tell you, because most cast effects are
one-shot and only some loop.

Expect most of these to be flashes. A skill's cast animation is played once at cast time;
the persistent creature visuals in this game come from the 24-value abnormal bitmask, which
is already exhausted. This page is here to find the exceptions, not because it is expected to
supply them.

The goal was always to find out what an effect looks like, and that question cannot be answered
from a data file even when one is readable. An effect id in `skillgrp.dec` is a number; the
number-to-texture mapping lives in the encrypted table; and even the decrypted table would give
a texture path, not a verdict on whether it reads as fire on a particular beast model.

The thing that *would* answer it needs no cryptography at all: **let the player audition skill
effects.** The server sends only a skill id and the client resolves the visual from its own
data, so the server never needs to know what the effect looks like. That is now built - see
*Skill effects, the third visual system* above. It turns "guess an effect id from a table" into
"click it and look at it", which is the whole reason the lab exists.

##### Holy, settled: it was never a skill, and it was on the bench the whole time

The winning find was Set Hero - a skill, which is exactly the category already ruled out.
It is not a false lead though. Set Hero's own XML says:

```
<skill id="395" ...>            (and 396, and 442 Sonic Barrier, 443 Force Barrier)
    <abnormalVisualEffect>INVINCIBILITY</abnormalVisualEffect>
```

**INVINCIBILITY is `AbnormalVisualEffect` 35, not 24.** The `Engine.dll` struct that
motivated the "only the first 24 render" reading describes the *regular* field. The enum has
60 values split across two fields, and the special/event field above 24 renders perfectly
well - INVINCIBILITY is proof, seen live.

So Set Hero was never a separate visual system. Casting it was a slow, roundabout way of
setting one bit in a mask the bench already writes directly. `AffinityEffectHoly` is now
`INVINCIBILITY`, and the two of them are the same picture.

Worth recording plainly, because it cost real time: the enum was treated as two lists when
it is one list with a divider in it. The 24-value boundary is a packet-layout fact, not a
rendering limit.

### Fire, and what it took: the effect is in the impact packet, not in any mask

`NPC Prominence` (4100) throws a blast that no bit in the abnormal mask can produce, because
the client draws it from the skill's launch data when the skill hits. The visual lives in the
`MagicSkillLaunched` packet, not in a bitmask field.

`MagicSkillLaunched(Creature caster, int skillId, int level, Collection<WorldObject> targets)`
is a **pure presentation packet**. The core never invokes the skill, so there is no damage
calculation, no MP cost, no buff, and no effect-list entry. The client receives an id it
already knows how to draw and plays it at the target's position.

That is the whole trick, and it is why this needed no new data files:

- **HIT FX page**, third page of the bench, **384 named damage skills**, 30 of them
  element-named and therefore first. The inverse of the SKILL EFFECTS filter on purpose -
  that page excluded `isDamage` because casting it would hurt the beast, and here the damage
  is never executed at all.
- Clicking one repeats its impact on the target every 1500ms until OFF.
- **The effect follows the beast.** The target is re-resolved from its object id on every
  tick and the packet is drawn at wherever the creature is standing *now*. This is the
  direct answer to the GHOST_STUN complaint - that circle is stuck to the ground because the
  bitmask pins it to a spot, and nothing in the mask can be made to track a moving creature.
  A repeated launch packet has no such limitation, because it is not pinned to anything.
- Broadcast from the creature, so it is visible to everyone nearby, not just the owner.
- Two loops that cannot leak: dropped when the player logs off, dropped when the target is
  gone. Daemon thread, same as the auto-cast scheduler.

Fire is now reachable through the same mechanism as every other affinity, with no data-file
change and no client edit.

### Final picks, and the one thing that could not be made permanent

| Affinity | Effect | Ordinal | Field |
|---|---|---|---|
| Fire | `GHOST_STUN` | 21 | regular |
| Water | `SEIZURE1` | 23 | regular |
| Wind | `FLOATING_ROOT` | 19 | regular |
| Earth | `ROOT` | 11 | regular |
| Dark | `DOT_POISON` | 3 | regular |
| Holy | `INVINCIBILITY` | 35 | special |
| First awakening | `BIG_BODY` | 18 | regular |
| Second awakening | `SLEEP` | 9 | regular |

All eight checked for mask collisions against each other: **no two share a bit**, so they
can be layered on one beast without one silently clearing another. Worth stating because
the mask is an OR with no ownership - two effects on the same bit would fight, and the
second one applied would look like the first had failed.

`INVINCIBILITY` is the only one of the eight in the special field, which is why it needed
the second int. It rides along independently of the other seven.

The second awakening is a **new** setting, `AwakenedEffectSecond`. There was only
`AwakenedEffect` before, and one setting cannot express "one overlay at stage one, a
different one at stage two" - it can only pick a single look for every awakened beast.
Both overlays now layer, so a stage-two beast wears `BIG_BODY` and `SLEEP` at once. The
second is not a swap because the first awakening is something that happened rather than a
state the second one undid, and a beast that lost its stage-one look the moment it grew
stronger reads as a downgrade.

#### "Make them permanent" - what was built, and what it is not

`AffinityPermanent` puts a cleared aura back within one second. It is **not** immunity,
and the reason is structural rather than a missing feature:

The mask is a plain OR of bits with **no owner recorded against them**. So
`stopAbnormalVisualEffect` clears a bit whoever set it, and there is no flag on that call
to mean "not this one". A live skill that draws the same effect - `SEIZURE1` and `ROOT`
both appear in real skill data - will take the aura off when it ends, and nothing in this
module can veto it.

What the guard does buy is that a dispel, a death, a skill expiring or a restart cannot
leave a beast permanently naked. That was the real failure mode worth fixing.

Two implementation notes:

- The poll is cheap when nothing happened: `hasAbnormalVisualEffect` says the bit is
  still set, so the core's dirty check builds no packet. A no-op tick costs nothing on
  the wire.
- It is built on `effectsFor` rather than a cached copy, so a beast that awakens gains its
  new overlays without needing an apply pass to land at the right moment. The cost is one
  profile read per player per tick, which is why the interval is floored at 500ms.

### GHOST_STUN follows the beast now, and why it could not simply be re-applied

The pinned circle is not stubbornness in the client and it is not a matter of frequency.
Setting the same bit again provably does nothing, at any rate:

`Creature.startAbnormalVisualEffect` ORs the mask in, compares against the value it just
had, and **returns without sending anything when nothing changed** - confirmed in the
bytecode. So a loop that re-applies GHOST_STUN every millisecond would produce exactly
zero packets. The idea that it needed a faster re-apply was wrong, and no amount of
tuning the interval would have found that out; the packet is never built.

The only thing that moves the picture is **clearing the bit and setting it again**. The
client is told to take the effect down, forgets the anchor, and builds it fresh wherever
the creature is standing. That is a `stopAbnormalVisualEffect` immediately followed by a
`startAbnormalVisualEffect`, once per tick.

Which means the request for "every 0.00000000ms" has a real floor under it, and the floor
is not politeness:

- Each tick sends **two packets to every player who can see the beast**. Per player per
  tick, not per second - a beast visible to twenty people is forty packets per tick.
- The effect is destroyed and rebuilt each time. Below roughly 150ms the rebuild lands on
  top of the previous one often enough that it visibly stutters, which looks worse than
  the original pinned circle rather than better.

So `AffinityFollowMs` is floored at 150 and the honest description is that this is the
point where it stops getting worse, not a clean fix. It is off by default and per-affinity,
because an aura that already travels - the DOT family, SEIZURE - does not need any of this.

The registry is filled by `apply`, not derived per tick. `TameProfileRepository.load` is a
live SQL query, and resolving a profile several times a second per player to decide which
bit to set is not a cost worth paying for a cosmetic effect.

Fire is now `GHOST_STUN` with follow on, which is the first affinity that needed the
mechanism at all - every other one is in the family that already travels.

### Flesh Stone cannot be dark, and the freeze is the effect working

FLESH_STONE was tried for DARK and rejected: it freezes the beast's animation, so the model
slides along the ground instead of walking.

That is not a bug to fix. FLESH_STONE is the flesh-to-stone line - the petrification
stun - and a stun pose drawn on a creature that is still moving will read as sliding on
any model, at any rate, with or without follow. The look and the stutter are the same
effect. Keeping one means keeping the other.

Dark is `DOT_POISON`: in the DOT family, which is the only one confirmed to be drawn
attached to the creature rather than pinned to a spot, and a venom overlay reads as dark
without drawing a wound the way BLEEDING was found to. It is the shipped `AffinityEffectDark`
value.

### Why decrypting anything was not on the critical path

The thing that *would* answer it is cheap and needs no cryptography at all: **let the player
audition skill effects.** The server sends only a skill id and the client resolves the visual
from its own data, so the server never needs to know what the effect looks like. The module
already grants skills to pets and already has a skill bar. Exposing the skill-effect path to the
aura lab turns "guess an effect id from a table" into "click it and look at it" - which is the
whole reason the lab exists.

Because of all the above, `.tameaura` exists as a page of buttons rather than a name typed
from memory. It sets any of the 60 values on any creature immediately, without the reboot
that editing `module.ini` and restarting would cost per candidate. Audition, then write the
winner into the config. OFF clears it, and a module reload drops it. It never persists.

A note on the section below, since the heading is stronger than the evidence: it is called
"the client only renders 24 of the 60 values" and that is the working conclusion from
`Engine.dll`, but it was written *before* the lab existed and the lab exists precisely to
test it. Treat 24 as a strong prior, not a closed question.

### The client only renders 24 of the 60 values

This is the single most useful thing found about aura selection, and it explains why the
vocabulary feels so hostile. It was recovered from the client itself, not inferred.

`Engine.dll` contains a 24-field struct whose members are named `FnAbnormalStat_*`, in
this exact order:

```
dot_bleeding  dot_poison  dot_fire  dot_water  dot_wind  dot_soil
stun  sleep  silence  root  paralyze  flesh_stone  dot_mp  bighead
dot_fire_area  change_texture  bigbody  floating_root  dance_root
ghost_stun  stealth  seizure1  seizure2  magic_square
```

That order is identical to the declaration order of `AbnormalVisualEffect` for ordinals
1 through 24. The client's regular `_abnormalVisualEffects` bitmask therefore has exactly
24 renderable slots, and no value outside that struct can draw anything in that field.
Every later name - `FREEZING`, `SHAKE`, `BLIND`, `VP_UP`, `VP_KEEP`, `DEATH_MARK`,
`STIGMA_OF_SILEN`, `FROZEN_PILLAR`, `TIME_BOMB`, `MP_SHIELD`, `NAVIT_ADVENT`, the
`CHANGE_*` family and the `BR_*` tail - is dead weight in the regular field.

This is corroborated by six independent live observations, and it is worth keeping because
it converts guesswork into a lookup:

| value | in struct | observed |
| --- | --- | --- |
| `DOT_FIRE_AREA`, `DOT_SOIL`, `SEIZURE1`, `SEIZURE2` | yes | renders, follows the beast |
| `MAGIC_SQUARE`, `GHOST_STUN` | yes | renders, but pinned to the spot |
| `MP_SHIELD`, `SPEED_DOWN`, `FROZEN_PILLAR` | no | draws nothing |

**The ordinal rule is not safe to generalise past 24.** `ULTIMATE_DEFENCE`(28) and the rest
of the tail set the *special* and *event* fields instead of the regular one, and those are
handled by different client code with no name table recovered so far. `INVINCIBILITY`(34)
is outside the struct and nevertheless renders, so "ordinal >= 25 means nothing" is wrong
for the special range. Only `INVINCIBILITY` is confirmed there; `ULTIMATE_DEFENCE` is
confirmed dead.

So the practical shortlist for an affinity is the 24 minus the failures: `SEIZURE1`,
`SEIZURE2`, `STUN`, `SLEEP`, `SILENCE`, `ROOT`, `PARALYZE`, `FLESH_STONE`, `DOT_MP`,
`BIG_HEAD`, `DOT_FIRE`, `DOT_BLEEDING`, `BIG_BODY`, `FLOATING_ROOT`, `DANCE_ROOT`,
`STEALTH`.

### `BIG_BODY` and `BIG_HEAD` are reserved for first awaken

Decided: both are the awakening reward, not an affinity aura. They are the strongest
values in the enum because they are not particle effects at all - they scale the creature's
own mesh, so they cannot pulse and cannot fail to follow it.

That leaves a bit-ownership problem to settle before awakening ships. `BIG_BODY` is a single
bit per creature and the visual bitmask has no ownership and no reference count (see
`startAbnormalVisualEffect` below), so if awakening sets it and an affinity also sets it,
whichever clears last removes it for both. One owner has to be chosen deliberately.

`BIG_BODY` is also cosmetic-only: the client will draw a larger beast while the server hit
box stays its normal size, so enemies path through where it appears to be. Expect to see it.

### The flicker cannot be tuned from the server

`DOT_BLEEDING` pulses slowly and was wanted for `FIRE` because a red pulse on a large beast
reads as burning. It cannot be slowed or shortened from this side. The module never goes
through `BuffInfo`; it calls `startAbnormalVisualEffect` once, which sets a bit and
broadcasts. There is no duration, no rate and no re-application, so there is no parameter to
adjust. The bit carries no payload at all, unlike a skill cast effect which can vary by level
or move `effectPoint`. `abnormalTime` would not help even if it applied, since it governs how
long a buff lasts rather than how fast an effect animates.

Working theory for the mechanism: the `dot_*` values are burst animations rather than loops,
so each re-broadcast of the beast's state restarts the burst and the effect blinks. Loop
values such as `SEIZURE1` survive a restart invisibly, which matches them looking steady.
The test that distinguishes the two causes is to leave the beast completely still: if the
flicker stops or slows markedly, it is re-broadcast driven; if the rhythm is unchanged, it
is the effect's own animation and nothing will alter it.

### The aura lab: `.tameaura`

`.tameaura` opens a page with a button for every one of the 60 `AbnormalVisualEffect`
values, plus `ON` and `OFF` at width 100. It is the tool for choosing affinities, and it
replaces editing `module.ini` and rebooting once per candidate.

**Why it did nothing before.** `TameDiagCommand` registered `tameaura` as an **admin**
command, so it answered to `//tameaura`. Its own error messages told the player to use
`.tameaura`, which is a voiced form and was never registered, so the dot form failed
silently. Voiced and admin names live in separate registries, so the fix was to register
the lab as a voiced command and leave the admin one alone - `//tameaura <NAME>` still
auditions a value directly for anyone who prefers typing it.

**Why it is a page and not just a name argument.** A name typed from memory is a name
mistyped, and the values that matter are the ones nobody would guess. Every value is on the
page, including the ones believed to draw nothing: that belief comes from a struct recovered
out of `Engine.dll`, and if the recovery is wrong it is hiding a working effect, which is
exactly the kind of mistake a button grid is supposed to catch.

**One at a time.** Each click clears the previous audition before setting the next. The
older `.tameaura <NAME>` layered a candidate on top of the existing auras so it could be
judged against what it would displace, which is right for auditioning a replacement and
wrong for a bench: with 60 buttons, leftovers from the previous click are noise, and a
value that only looks good stacked is not what will ship.

**It targets.** The effect goes on whatever the player has selected, falling back to their
own summoned pet. Being able to point it at a plain unmodified monster, a rival player or a
boss matters, because "does this render at all" and "does this render on a collared beast"
are different questions and the second can confound the first.

`OFF` clears only the value this page set, only on the creature it was set on. The bitmask
cannot be interrogated for what is set, so the page has to remember, and that memory is a
per-player map that does not survive a reload - the right default for a tool meant to be
used and abandoned. A consequence worth knowing: a lab effect and a real buff using the same
value are the same bit, so auditioning `DOT_BLEEDING` on a creature under Capture Penalty
means `OFF` clears the penalty's visual too. Audition on something quiet.

Markup is copied from the two button shapes already confirmed on this client - the 100 by 22
shop button for `ON`/`OFF`, the 65 by 21 passport grid button for the values - and it keeps
the reagent store's hard-won page rules: no bgcolor, no header rows, rows as direct children
of the table, no valign.

**The 8192 character ceiling, and why the page is paginated.** `AbstractHtmlPacket.setHtml`
compares the html against `sipush 8192` and, when it is longer, logs `Html is too long! this
will crash the client!` and then **truncates it with `substring(0, 8192)`**. It does not
refuse, and it does not tell the player; it hands the client half a document. The first
version of this page put all 60 buttons in one grid, measured 12274 characters, and was cut
off partway down - so it drew nothing usable and logged the warning on every click.

The value list is therefore chunked to fit, with the page count derived from the real length
of the built markup rather than from a constant, so adding a value cannot silently
reintroduce the overflow. Measured with the shipped code: 3 pages, widest 6364 characters,
shortest button 165 and longest 185. The page still checks its own length before sending and
warns if it is ever over, because the core's response to being over is to fail quietly.

**A note on reading a bypass argument.** The nth-word helper used elsewhere in this module
walks spaces and gives up when it runs off the end, so it returns `""` for the last word. That
is harmless where the last word is never the one being read, and silently wrong for a button
whose only argument is the last word - every value button on this page sent exactly one word,
so every one of them reported that no value had been picked. The lab reads everything after
the first space instead. Worth remembering before copying that helper somewhere the argument
can be the final word.

Implementation note that will bite anyone extending this: `IBypassHandler` and
`IVoicedCommandHandler` both declare `String[] getCommandList()`, so one class cannot
implement both and serve two registries. `TameAuraLab` is the voiced half and
`TameAuraLab.Bypass` is the bypass half. Written as one class the page would have drawn
perfectly and every button on it would have done nothing.

### Audition checklist

Run these in game before deciding anything further. All of them are free and need no reboot.

- [ ] `BIG_BODY` - does the creature scale, and does the scale propagate to a particle
      effect already on it? This decides whether "make it big" is usable at all.
- [ ] `DOT_FIRE` - never tested. `DOT_FIRE_AREA` is the flamethrower and pulses; the plain
      value is a different slot and may be a steady flame.
- [ ] `DOT_MP` - never tested, and is not `MP_SHIELD`, which draws nothing.
- [ ] `STIGMA_OF_SILEN`, `NAVIT_ADVENT`, `DEATH_MARK`, `FREEZING`, `VP_UP`, `VP_KEEP`,
      `TIME_BOMB`, `CHANGE_VES_S/C/D` - expected to draw nothing, being outside the struct.
      Worth confirming once so the shortlist above can be trusted.

**This list is superseded.** Everything marked untested has since been tested in game. The
chosen affinities are tabulated in *Chosen affinity effects, as decided in game* above; fire
is the only one left open, and the untested candidates that might have filled it were
`DOT_FIRE`, `DOT_MP` and the values outside the struct. `DOT_FIRE` and `DOT_MP` have since
been ruled out by looking, as has `GHOST_STUN` as anything but a fire candidate, because it
pins the beast to the spot. Do not re-add rows to this list; add a decision to the table
above instead.

### Known defects in the audition hint text

Not yet fixed; recorded so they are not rediscovered as surprises. All three are in
`TameDiagCommand`, which is the old admin command - the `.tameaura` **page** does not share
them, because it lists the real enum rather than a hand-written shortlist.

- `TameDiagCommand.report` lists `STIGMA_OF_SILEN`, `NAVIT_ADVENT`, `DEATH_MARK`,
  `FREEZING`, `VP_UP`, `VP_KEEP`, `TIME_BOMB` and the `CHANGE_VES_*` names under "Worth
  trying". All are outside the client's struct, so the command is steering an operator
  towards values that cannot render. The shortlist should be rebuilt from the 24.
- The same method describes `FROZEN_PILLAR` as pinned to the spot, while elsewhere in this
  file it is recorded as drawing nothing. It is outside the struct; "nothing" is the
  accurate description.
- The error path for an unrecognised name tells the user to run `.tameaura list`, but no
  `list` argument is implemented. Bare `.tameaura` is what prints the report.

### Buttons carry the page they were made on

Every action on the lab page ends in a page index - `tame_aura_on STUN 1`, `tame_aura_off 1`,
`tame_aura_reapply 1`, `tame_aura_page 2` - and the page is redrawn at that index after the
click. It did not used to, and the symptom was maddening rather than broken: clicking a value
on page two or three dumped the player back on page one, so working down a list of candidates
meant paging forward, clicking, and paging forward again to reach the one next to the one they
just tried.

The index is in the link rather than remembered in a map for two reasons. A remembered page is
wrong the instant a reload drops the map, and it is wrong for two people using the same
client. A link carries its own context.

Two consequences worth knowing before adding another button to this page:

- **Pagination now measures with a stand-in index of 99**, not with a real one. The action
  embeds the page number, so page 10 is one character wider than page 9; measuring with real
  indices would let the page count change shape the moment the index gained a digit. Cells are
  measured with `PAGE_PROBE` and rebuilt with the real index when drawn. Measured with the
  shipped code: 3 pages, widest 6366 characters worst case, against the 8192 ceiling.
- **ON is its own bypass (`tame_aura_reapply`), not an empty argument.** ON used to send
  `tame_aura_on` with no value and rely on an empty name meaning "the current one", which
  shared a code path with a malformed value click. Splitting them means an unparseable action
  is reported as such instead of silently doing nothing.

### Reading the core: how the visual bitmask actually works

Established from `javap` on `Creature` and `BuffInfo`, and it governs several decisions
above.

- `startAbnormalVisualEffect(boolean, AbnormalVisualEffect...)` and its `stop` counterpart
  are a plain OR and a plain AND-NOT into three `int` fields: `_abnormalVisualEffects`,
  `_abnormalVisualEffectsSpecial` and `_abnormalVisualEffectsEvent`. There is no owner, no
  reference count, no timer and no link to any buff.
- The `boolean` argument is read exactly once, at the end, to decide whether to call
  `updateAbnormalEffect()`. It means "broadcast now", nothing else. A comment in
  `TameVisual` previously described it as the flag the core uses for effects that outlive
  their own skill, which is not what it does; the durability comes from there being no timer
  at all.
- Because there is no ownership, any `stopAbnormalVisualEffect` for a value clears it
  whoever set it. `BuffInfo.removeAbnormalVisualEffects` does exactly that when a buff ends.
  In this datapack only skill 5098 "Capture Penalty" carries `SEIZURE1`/`SEIZURE2`, so a
  beast under it loses its affinity aura when the 60 second penalty expires, and nothing
  re-applies it until the next `TameVisual.apply`, which happens only on summon, on the
  collar page and from the manager.
- `TameVisual.apply` returns early when the wanted set is empty, before the clear, so a
  beast whose affinity resolves to no aura keeps a stale one rather than losing it.

### Orphaned tame rows

Nothing in this module is told when a collar stops existing. `deleteByCollar` is only reached from a failed capture and from the release command, but the two ways a collar usually goes away are stock server behaviour that never passes through here: the player destroys it, or the owner deletes the character. The row then survives, `TameForge.hydrateAll` reads it on every boot, and the log reports a restored collar that does not exist.

The damage is cumulative. `TameForge` seeds its next synthetic npc id from `MAX(synthetic_npc_id)` over surviving rows, so orphans also push the reserved band forward and those slots are never reclaimed — reusing a released id would change how an existing beast is addressed, so a slot is spent for good once allocated.

`OrphanCollarSweep` at startup finds tamed pets whose collar object id is no longer in `items`, or whose object id has been reused by a different item, and either reports or deletes them. It defaults to `report`, because it removes saved player data and the first run should be read rather than trusted; an unrecognised value also reports, so a typo cannot delete pets. The bestiary is per owner and per species, so it is deliberately kept.

Deleting through `deleteByCollar` means the cascade is identical to the failed-capture path: `tamed_pet_skill`, `tamed_pet_equipment` and `tamed_pet_history` all carry `ON DELETE CASCADE` on `tame_uuid` and are also deleted explicitly.

### Native pet-bar compatibility

The native pet bar depends on the current Mobius client/server build retaining the expected parameter names and pet action path. If a later build changes those names, the collar HTML and auto-cast paths remain the safer fallbacks.

### Transfer

Collars are not tradable in the current design. Transfer support was not retained because the former transfer hook was never called by the running server and would have created a false sense of safety.

If collar trading is ever enabled, ownership transfer must be implemented and tested as a complete transaction before enabling it publicly.

## Troubleshooting

### The collar is invisible

Check that the collar still contains its client-known display identity. The server-owned item ID and the client display identity are separate values.

### A reagent will not buy, or buys the wrong thing

Check:

- `.tamestore` opens a page at all. If it does not, the module did not finish
  enabling, or `TameProfileCommand` is not registered.
- The button path charges `NormalTamingPrice` / `RaidTamingPrice` from `module.ini`. The
  window path charges whatever `data/multisell/<ReagentMultisellId>.xml` says. If the
  two disagree the server logs a warning at boot naming the row, so check the log
  before assuming the window is wrong.
- If a row is missing from the window, the item id in `data/multisell` no longer
  matches `NormalTamingItem` or `RaidTamingItem` in `module.ini`. A multisell row whose
  production id does not exist is dropped at load.
- A window purchase the server refuses logs `MultiSellChoose` and names the player,
  list, and entry. The usual causes are Adena, weight, and capacity.

### The reagent looks wrong in the inventory

### A skill button does nothing

Check:

- The pet is summoned
- The correct collar is open
- The skill has reached its unlock level
- The awakening path has been selected if required
- The pet has enough HP and MP
- A valid target is selected for a damage skill
- The collar page bypass uses command, collar object ID, skill ID, and skill level

### A black square appears below a collar `USE` button

The collar HTML must use the old Interlude-compatible button form without an explicit empty closing tag:

```html
<button value="USE" action="..." width=100 height=22 back=sek.cbui94 fore=sek.cbui92>
```

Do not write it as an empty `button></button>` element.

A black icon in the ordinary player skill window is a different issue and may be caused by granting a module-only skill ID to the player. The collar HTML and the player skill list must be diagnosed separately.

### A weapon disappears after resummon

Check the collar's saved equipment row and confirm that the module reapplies saved equipment after forging the synthetic template. Read the `hands of pet` line in the server log (`HandsDebug`): `wild=0` means the creature never carried a weapon, so none is drawn on purpose, and `pet.getWeapon()=0` with `wild` above 0 means the weapon was not placed in the pet's slot.

### A pet appears as the wrong creature or at the wrong scale

Use the module diagnostic command or log to compare:

- Source NPC ID
- Synthetic NPC ID
- Forged template
- Pet data profile
- Client display identity

A source template must exist before a collar can be forged. A stale spawned instance may need to be removed before the corrected template becomes visible.

### Trying an aura without a reboot

`.tameaura` opens the aura lab page described above. The older single-value form is still
available as the admin command `//tameaura <NAME>`. On the page, clearing is the OFF button;
there is no `off` argument on either surface, and the hint text suggesting one is recorded
above as a defect.

For the page, the preconditions are far looser than the collar passport has, because the
bench does not need a profile: it only needs something to point at. A target, or a summoned
pet, or nothing at all - the page draws either way and the buttons report the problem.

For `//tameaura <NAME>`, which goes through the profile and the collar, the command checks
all of this and is worth knowing when it refuses:

- A tamed beast must be summoned and `isPet()` must be true.
- A collar must be recorded as the last one summoned for that character.
- The recorded profile's synthetic NPC id must equal the id of the beast standing there. A
  stale record is refused rather than dressing the wrong creature.

Only a value written into `module.ini` as `AffinityEffect<Affinity>` survives a reboot.

## Safe maintenance rules

- Do not edit the core JAR to solve a module problem without first verifying that the module framework cannot expose the required hook.
- Do not modify client files for visual convenience.
- Do not reuse a stock item or skill ID as a custom logical identity unless the client and server behavior have both been tested.
- Do not remove a `displayId` fallback because the stock equivalent appears not to use one.
- Do not write a module-wide weapon into `PetRightHandItem` after testing only one creature model.
- Keep database installation idempotent.
- Keep every collar operation owner-validated by collar object ID.
- Keep skill validation shared between HTML casting, native pet-bar binding, and auto-cast.
- Mark new observations as confirmed, suspected, or unverified instead of presenting them as facts.

## Current status

The module is suitable for controlled testing and public alpha packaging when the server owner has verified the exact client build.

Working foundations include:

- Server-side capture
- Persistent collar profiles
- Individual rolled identity
- Taming reagents
- Pet summon and resummon
- Pet progression and experience sharing
- Skill deck and awakening
- Server-side auto-cast rotation (role-ordered skills, healing, per-technique cooldown)
- Collar HTML passport
- Pet commands
- Food, wounds, and bond
- Functional collar-bound equipment
- Bestiary and history persistence
- Module-only installation without core JAR edits

The main boundaries are client rendering limitations, the player action-bar protocol, native pet-bar compatibility across Mobius revisions, and visible equipment on arbitrary creature models.

## Changelog

### Affinity looks fall back to effects only, and Dark returns to DOT_POISON

Until the animation glitches are sorted, the affinity skill-cast layer is retired and every
affinity draws from its bitmask effect alone. This reverses the two-layer setup recorded
under "Affinity skill auras (visual-only casts)": nothing casts for a look any more, so a
beast's aura is one bitmask and nothing else.

- **Every `AffinitySkill*` is `NONE`.** Fire (`3631` Void Flow), Wind (`19` Double Shot),
  Holy (`7` Sonic Storm) and Dark (`3091` Item Skill: Poison) used to be bound; those ids are
  kept in `module.ini` as retired notes, not as live values. `TameSkillAura` is still in the
  tree and still works - it is the config that is switched off, so restoring one is a line
  edit and a reboot, not a code change.
- **Dark wears `DOT_POISON` again.** With the cast gone the bitmask has to carry the look, so
  `AffinityEffectDark` is `DOT_POISON` rather than `NONE`. It is a DOT_ effect, so it pulses
  on the tick instead of holding steady; that is the effect working, not a fault.
- **The rest are unchanged:** FIRE `ROOT`, EARTH `DOT_SOIL`, WATER `SEIZURE1`, WIND
  `SEIZURE2`, HOLY `DANCE_ROOT`. Fire, water, wind and holy already matched what was chosen in
  game; only dark moved.
- **Why:** the skill-cast auras are where the animation glitches come from, and the bitmask
  effects are the ones confirmed to follow the creature. This is a deliberate temporary
  state, not a final choice - the cast layer comes back when the glitches are fixed.

### Auto-cast aim, the fire aura, and the cast-speed ceiling

- **The owner-aim AI dip is fixed, and its cause was a write, not a read.**
  `TameAutoCast` opens every pass by aiming a support technique at the owner
  (`chooseTarget` returns the player for any non-damage, non-debuff skill) and then wrote
  that resolved target into `summon.setTarget(...)`. The pet's AI, having just been told it
  has no enemy, dropped its ATTACK intention and walked back to the player instead of
  fighting - which read as the beast refusing to engage. `setTarget` is now written only
  when the technique is actually offensive (`candidate.skill.isDamage() || isDebuff()`),
  so a support pass aims without ever pointing the AI back at the owner.
- **`alreadyApplied` now checks both the aim and the beast.** A buff that resolves onto
  the caster (`isTargetSelf()`) used to be judged only against the aim target, so a pet
  re-casting a support technique could genuinely believe it had not been applied. It now
  answers "is this technique already on the creature that would wear it", taking either
  the owner or the summon depending on where the effect lands.
- **Fire aura is `ROOT`, not `NONE` and not the flamethrower.** `TameVisual.defaults`
  maps FIRE to `AbnormalVisualEffect.ROOT` and `AffinityEffectFire` is `ROOT`. The
  DOT family was ruled out as a burn: `DOT_FIRE_AREA` pulses on its damage-over-time tick,
  so the beast stood in a flamethrower that lit, died and relit rather than holding a
  steady glow, and the grounded effects were ruled out the other way - the beast walked
  out of them. ROOT is one of the handful already confirmed to follow the creature.
- **`PetCastSpeedCap` is 300.** A mage mob's own cast speed is the ceiling to stay under:
  a cap above it would put every pure-caster beast ahead of the very mobs the speed came
  from, which surfaces first as a cast animation too quick to read. 300 sits just under
  that; the same reasoning is restated in `module.ini` next to the value.

### Role-ordered inherited skills

The native template still supplies the deck, but the order the slots are filled from no
longer ignores what the beast is for.

- **Before:** every pick after the safe list was built - the signature fallback, UTILITY,
  ADVANCED and AWAKENING - walked the candidates in skill-id order. Id order is arbitrary
  for a creature: a SUPPORT drew whatever non-damage technique its species happened to
  define under the lowest id, and a MAGE could take a physical tap for its advanced slot
  purely because that id came first.
- **Now:** `orderForRole` stably sorts the safe list by `roleRank` before the slots are
  taken. MAGE puts magic damage first and magic before physical, ARCHER puts physical
  ranged techniques ahead of everything, SUPPORT puts the non-damage techniques (heals and
  buffs) at the head of the pool so its "real job" skills are the first ones handed out,
  and anything else - FIGHTER, BALANCED, an empty or unknown role - ranks everything 0,
  which with a stable sort is byte-for-byte the old id order. Nothing is ever filtered;
  a species whose whole pool is the "wrong" flavour still fills every slot it can.
- **It cannot rewrite an existing beast.** The order only decides how `select` hands out
  techniques. `TameCollarView` skips any slot a profile already has, and `refreshSignature`
  rewrites SIGNATURE_1 alone, so the change shapes decks that have not been forged yet and
  leaves established ones standing.
- **Verified against the real monster catalogue.** `RoleOrderProbe` fed 1230 loaded
  monster skills through the sort for every role. FIGHTER, BALANCED and an unknown role
  came back in the identical id order (asserted element-by-element, not eyeballed); MAGE's
  head pick is a magic skill; ARCHER's head is a physical technique of 900 range. One
  caveat is worth logging: in the sandbox this build cannot instantiate damage effect
  handlers, so `Skill.isDamage()` reads false for all 1230 skills and the damage-flavoured
  tie-breaks (SUPPORT's "non-damage first", MAGE's "magic damage first") only engage on a
  live server, where the shipped code already depends on `isDamage` elsewhere.

### Item feasibility: Monster Only gear, textures, and fused armor

Three open questions answered from the actual datapack and the core jar, so this stands as
the finding rather than a reminder.

- **`Monster Only(...)` items are equippable by a player.** The datapack has 40 items
  named `Monster*`, 34 of them `Monster Only(...)` (6715-6723, 6917-6919, 7014, 7560,
  8203-8211 and neighbours), every one `type="Weapon"` or armor with real stats, a real
  icon (`icon.weapon_monster_i00`) and `for_npc="true"`. `ItemTemplate.isForNpc()` is
  referenced by exactly four classes in the core - `FakePlayerAppearanceFactory`,
  `FakePlayerGearFilter`, `ItemTemplate` itself and `RequestPetUseItem`. No player give or
  equip path consults it, so the flag gates fake-player gear and pet item use only. A
  player can hold and wear these; the icons render from the inventory.
- **The server cannot send a texture.** Across 9182 item `<set>` writes the only
  appearance attribute is `icon` - a texture *name* the client resolves against its own
  data. `documentation.txt` confirms the item surface stops at `bodypart`,
  `default_action`, `weapon_type`, `armor_type`, `etcitem_type` and `mp_consume`. The
  server sends an item id and an icon name and the client draws the 3D model from its own
  tables, so "change the pet's armor texture from /module" is not a reachable lever; the
  one appearance control the module has is which item id a tame wears.
- **Two armors cannot be fused half-and-half.** A fused mesh and texture would live in the
  client's `.utx`/`.gr2` data, which the server cannot ship and the module deliberately
  does not touch. Out of reach, not missing a setting.

### Monster Only gear can now be handed out from the aura lab

The finding above said a player can hold and wear the datapack's 34 `Monster Only(...)`
items, but nothing handed one to anybody: they are not sold, not dropped and not
craftable. The aura lab gained a **GEAR** page (`.tameaura` then the third navigation row)
where every item has a **GIVE** button that puts one copy in the player's bag.

- **Give, not wear.** A first cut of the page also put a **WEAR** button on each row. It
  turned out to be pointless: the player can already wear these items once they have one, so
  the page only needs to hand them over. The button was removed and the remaining one was
  renamed from **TAME** to **GIVE**, because the copy lands in the player's bag and nothing
  about it touches the pet.
- **The gift is the only route the collar has.** Installing a Monster Only item directly on
  a pet does not work - the beast rejects it as unusable. The collar's **TAME GEAR** page
  reads the item off the owner's bag when it equips it, so the copy has to pass through the
  player first. That is why the page gives to the player and does not try to install.
- **It hands over an ordinary item.** The grant goes through
  `player.addItem(ItemProcessType.REWARD, itemId, 1, player, false)`, the same path as a
  quest reward, so what lands is a real item with a real inventory row that can be dropped,
  traded or destroyed.
- **The id list is literal, not name-matched.** `MONSTER_GEAR_IDS` holds the 34 ids
  (6715-6723, 6917-6919, 7014, 7560, 8203-8222). A scan for names starting `Monster Only`
  would have to walk the item table on every page draw and would change its answer if the
  client's name table changed; the ids are stable, so the literal list is cheaper and more
  honest. A hand-written `tame_aura_give` link is validated against the same list, so it
  cannot pull any other item id through the command.
- **The captions strip the `Monster Only(...)` wrapper.** Every name is wider than the
  button, and the wrapper is the part that carries no information, so `gearName` keeps what
  is inside the brackets and trims it. The full name is in the chat message after the click.
- **It is behind the existing GM gate.** The page is drawn through the lab's bypass handler,
  which already refuses anyone who is not `isGM()`, so no new access check was needed. This
  is the module's test bench, and handing out unobtainable gear is a staff action.

### Correctness and permission fixes

Nine confirmed bugs, all fixed. The level-cap one was silent data loss.

- **A level-cap correction destroyed the beast's skills, gear and history.**
  `TameProfileRepository.save()` deleted the `tamed_pet` row for the collar and reinserted it.
  All three child tables hang off `tame_uuid` with `ON DELETE CASCADE`, so the delete took the
  beast's `tamed_pet_skill`, `tamed_pet_equipment` and `tamed_pet_history` rows with it. Its
  only caller is `TameLevelCap.correct()`, so every time a pet was summoned above its level cap
  the module "fixed" its level by wiping everything else it had. The save is now an upsert
  (`INSERT ... ON DUPLICATE KEY UPDATE`) built from one shared column list, so it updates in
  place and no child row is ever removed. `persistCapture()` uses the same upsert.
- **`save()` could not roll back.** It set `autoCommit(false)` and never undid it on failure,
  returning the connection to the pool still holding an open transaction. Added the rollback.
- **`.tameautocast off` switched auto-cast off for the entire server.** It wrote a single
  static flag, so any player could silence every tame on the server. The flag is now config-only;
  a player's own choice is a per-player mute (`isEnabledFor` / `setMutedFor`), cleared on login.
- **`.tameaura` was open to any player.** It was registered as a voiced command and never asked
  who was asking. The page casts a rebuilt copy of any skill in the table at whatever the
  caster has selected - including another player - bypassing the MP cost, cooldown, range and
  level requirements. Now gated on `isGM()` at every entry point (voiced command, bypass
  buttons and the shared `open()`).
- **`.tamesummon` had no state guards.** Refused now while the player is dead, in combat,
  casting, or in an Olympiad match. The last was the significant one: bond grows with damage
  dealt, so summoning inside a match farmed bond on a character meant to be duelling alone.
  Login restore uses an `onLogin` overload and is not subject to these guards.
- **Grand bosses were not blocked.** `isType("GrandBoss")` - Valakas, Antharas and anything
  else the worldserver marks that way - is now refused. Ordinary `BOSS`/`RAIDBOSS` stay
  capturable.
- **A caught boss never came back.** `wildMob.deleteMe()` removed the creature without telling
  its spawn, so no respawn timer was started and the boss was simply gone from the world. The
  spawn's own timer is now restarted before the delete, putting it back on the schedule the
  npc data already declares.
- **Relogging could bring back the wrong beast, or a phantom one.** The module's own login
  restore is correctly scoped to the worn collar, but the stock core has a *second* restore in
  `Player.onActionRequest()` that runs afterwards. It resolves the saved summon through
  `CharSummonTable` and then `PetDataTable.getPetDataByItemId(the collar's item id)`. Every
  collar shares item id 9302, so that lookup answered with whichever profile carried the id
  first. A player who had a beast out but was not wearing its collar came back to a
  default-named beast that was not theirs, while their own collar sat untouched in the bag.
  The module now removes the player's core saved-pet entry on login when it resolves to a
  collar, before the stock restore is ever consulted. The worn-collar restore is unchanged,
  and ordinary pets (wolves, hatchlings) are left alone because their saved item is not the
  collar. No core edit and no client data are involved.
- **Installing tame gear destroyed the item and rebuilt a plain one, losing its augment and
  Shadow mana.** `TameEquipmentManager.equip` called `destroyItem` on the real item and kept
  only `item_id`, `enchant` and a derived bonus; removal rebuilt a fresh item from those two
  numbers. Anything not expressible as (id, enchant) was gone: augmentation, a Shadow item's
  remaining mana, elemental attributes. It could not be snapshotted onto the row either - the
  core `Item` exposes no Shadow-mana setter and no element getters. The item is now moved whole
  into a private LEASE container owned by the player (`TameGearVault`, a stock `ItemContainer`)
  and moved back out on removal; `tamed_pet_equipment` gained an `item_object_id` column to link
  the slot to the held item. A row written before the vault (object id 0) still rebuilds from id
  and enchant, so nothing existing changes and nothing new can be lost. Release and replace now
  return the held item before their rows are deleted. No core edit.
- Compile is clean (`exit 0`). `RaceProbe`, `CapProbe`, `CloneProbe` and `SignatureProbe` all
  pass.

### Reported but not reproducible

Checked against the source; no change made, because the reported behaviour is not what the code
does.

The two equipment reports that used to sit here, "releasing a pet deletes its equipment" and
"pet gear destroys and recreates the item, losing the augment," were re-examined and both turned
out to be real. Release did delete the gear, and install did destroy the item and rebuild it from
(id, enchant), so any augment or Shadow mana on tame gear was lost. Both are fixed now - see the
equipment-vault entry under "Correctness and permission fixes" above. Nothing else is outstanding
here.

### Module rebuild

- Reorganized the system as a standalone module.
- Moved runtime configuration into `config/module.ini`.
- Added module metadata, reserved ID ranges, and idempotent database installation.
- Removed the need for direct core-file edits in the supported module build.

### Collar and profile system

- Added collar-bound individual profiles.
- Added synthetic per-collar NPC identities.
- Added persistence and startup hydration.
- Added profile, skill, equipment, awakening, history, command, and identity pages.

### Skill system

- Added persistent inherited skills.
- Added level and awakening gates.
- Added safe server-side collar casting.
- Added native pet-bar binding where supported.
- Removed the unsafe player-toolbar skill-grant approach.
- Added cleanup for legacy shortcut-bar grants from earlier experiments.

### Equipment system

- Added weapon, armor, and accessory slots.
- Added inventory validation and item return handling.
- Added persistent stat bonuses.
- Added saved weapon reapplication during synthetic template restoration.
- Visible weapon: the wild species' own weapon is placed in the pet's weapon slot before spawn (`ShowWildWeapon`). The collar's weapon is not lent, and creatures that never carried a weapon get nothing.
- Added `HandsDebug` to log what each summoned tame holds.

### Reagent dealer

- Removed the unreliable custom reagent dealer.
- Reagent access is a store page opened by `.tamestore`, a voiced command any player
  can use, and it stays inside the module.
- The page is generated in code and sent with `NpcHtmlMessage`, the same way the collar
  passport pages are. It has to be: the module framework accepts an `html` resource
  root but `HtmCache` never reads one, so module dialogue files are never served. The
  eleven Pet Manager overrides built on that assumption were deleted.
- Repurposing an NPC was evaluated and is not possible: `ModuleHandlers` has no
  talk or interact hook, so nothing can open a page in place of a stock dialogue.
- Added module multisell `data/multisell/9300.xml`, selling the flute for 10,000 Adena
  and the bugle for 250,000 Adena. It is opened by the store's OPEN SHOP button, which
  draws both with icons and a quantity selector.
- **Corrected in a later pass:** the list does carry an `<npcs>` block, holding
  `<npc>-1</npc>`. Without it the core refuses the list for every player who is not a GM,
  because `isNpcAllowed(-1)` is the only thing that makes a command-opened list sendable.
  The original reasoning above was backwards; the `-1` entry is load-bearing and must stay.
- Confirmed working on a running server by a player with GM status off, who opened the list
  and bought from it. The window is the primary store surface from that point on.
- The store's direct-buy buttons, which charge `module.ini` and hand the item over as a
  normal item, are no longer drawn in the normal case. They remain as the automatic
  fallback drawn only when `isMultisellUsable()` is false, and they still add the item
  before taking Adena so a full inventory costs the player nothing.
- The store page was redrawn to match the collar passport pages - `sek.cbui94`/`sek.cbui92`
  at the sizes the passport already uses, and its gold/dim/body font colours. The previous
  page stretched `L2UI_ch3.smallbutton2` to `width=150`, which tore because that texture is
  a fixed nine-slice, and reagent names and icons were hardcoded in the page.
- Removed the `xsi:noNamespaceSchemaLocation` hint from the list's root element. A relative
  `../xsd/multisell.xsd` resolves against the module folder, where there is no xsd
  directory, so the parser logged `Cannot find the declaration of element 'list'` on every
  boot for a file that was loading correctly.
- Corrected a wrong claim about the affinity config keys. The six boot lines
  `Config 'AffinityEffectFIRE' not found` are the deliberate multi-spelling lookup in
  `TamingModule.readEffect` trying its first candidate before the one that matches
  (`AffinityEffectFire`). They are expected and harmless. Affinity auras were never
  silently null - the four that appear are the correct behaviour, and there was no
  case-mismatch bug to fix.
- Added `.tameaura` to audition any of the 60 `AbnormalVisualEffect` values on the
  caller's own beast without a reboot, with a shortlist grouped by how each candidate is
  known to fail. It clears the affinity aura on module reload.
- Established that aura size is not adjustable: no packet in the serverpackets package has
  a scale field, and boss auras are not reachable through this enum because they are
  skill-referenced client effect data rather than abnormal-status bits.
- `TamingData.verifyMultisellPrices` compares the multisell XML against the `module.ini`
  prices at startup, because two files now hold these prices and a drift between them
  would let a player buy at the cheaper one. It also sets `isMultisellUsable()`, which is
  what the store page reads to decide whether to offer the window or fall back.
- Removed the reflective buy-list adapter. It could only ever append rows the client drew as blanks, because `BuyList` carries no display id.
- Crafting was evaluated and rejected: the module framework has no `CRAFTS` resource type, and the craft packet never sends an item id, so the client would resolve a custom recipe against its own tables and draw it blank.
- Kept reagent acquisition deterministic and shop-based rather than drop-based.

### Affinity skill auras (visual-only casts)

> **Retired.** Every `AffinitySkill*` is now `NONE`; the cast layer is off until the animation
> glitches are fixed and only the bitmask effects draw. See "Affinity looks fall back to
> effects only" in the changelog. The account below is the history of the feature, not its
> current state.

- Replaced Holy's `INVINCIBILITY` bitmask with a cosmetic cast of skill `453` (`Escape Shackle`),
  because the aura enum records which effect a skill uses and does not promise that effect
  renders on a given creature model - `INVINCIBILITY` drew nothing at all.
- Added the same route for Dark, initially with skill `1157` (`Body To Mind`).
- Dark's bitmask aura is now `NONE`. The poison dot was drawing on top of the cast; the cast is
  the whole look.
- **Dark was briefly moved from `1157` to `453` and moved back.** The premise was wrong: it
  looked like dark had not been wired, but `AffinityEffectDark = NONE` is the *bitmask* line
  and is deliberately `NONE` for both holy and dark because the skill cast replaces it. The
  real binding was `AffinitySkillDark`, and dark had been wired the whole time.
- The two affinities are on different ids on purpose, and the difference is `isMagic`, which
  the rebuild copies from the real skill rather than forcing:
  - `453` `Escape Shackle` has no `<isMagic>` tag, so `isMagic=0`, and the client draws it as
    a pure cosmetic with no cast. This is holy's, and it is wanted that way.
  - `1157` `Body To Mind` is `<isMagic>1</isMagic>`, so `isMagic=1`, and the client runs a
    real magic cast *and* plays its `ManaHeal` effect after the animation. That post-animation
    effect is the part worth looking at on dark.
  Forcing both to the same `isMagic` would have drawn the wrong one of the two, which is the
  kind of thing that looks like a broken aura rather than a wrong value.
- The ManaHeal that plays on dark is **drawn by the client, not applied by the server.** The
  rebuild carries no effects (`effects=0`, verified across all 2682), so the beast is not
  actually recovering any mana. Nothing that looks like a benefit here is a benefit.
- Worth being blunt about how all of this was found: by pressing buttons in the aura lab's
  SKILL VISUALS page. The config only ever holds a number, and a number says nothing about
  whether it is the effect you pictured. Two reboots and a wrong "fix" came first.
- The route rebuilds each skill from its real id, name and level with **no effects attached**,
  so the client draws the genuine animation while the server grants no stats, no damage and no
  condition change. Verified against the shipped jar: `effects=0`, `mp=0`, `hp=0`,
  `hitTime=0`, `reuse=0`, `targetType=SELF`.
- `hitTime` is forced to `0` so the beast never enters a real cast, never shows a cast bar and
  never stalls while the animation plays.
- Rebuilt skills are session-only in memory and are never written to the database.

#### Rebuilding a Skill by hand: the keys that are not what they look like

`Skill`'s constructor reads about sixty keys out of a `StatSet`. Most are plain numbers, and
`StatSet`'s numeric getters parse a string - so the single value `"0"` satisfies `getInt`,
`getByte`, `getFloat` and `getBoolean` alike. That is what makes a "fill everything with zero"
rebuild look correct until it is not.

Two groups of keys break that assumption, and both cost a restart to find:

- **Six keys are enums, not numbers.** `getEnum` insists on a constant name, and `"0"` is not
  one. The complete set, read out of the constructor's bytecode rather than guessed:
  `operateType`, `trait`, `abnormalType`, `targetType`, `basicProperty`,
  `abnormalVisualEffect`. Every one has a `NONE` except `operateType`/`targetType`, which take
  the self-cast the rebuild wants anyway. An earlier attempt paired these keys with the wrong
  getters, decided `isMagic` and `power` were enums, never mentioned `trait`, and every build
  died on `Enum value of type TraitType required, but found: 0`.
  - Note `basicProperty` is a `BaseStat`, not an `Element`. It has a `NONE`. Guessing it was
    an element - and reasoning that no element is called `NONE` - would have deleted a key that
    was fine as it stood.
- **`affectLimit` is not a number either.** It is read as a string, split on `-`, and both
  halves are `parseInt`d, so it wants `"0-0"`. `"0"` throws. Omitting it is better than either:
  the constructor's field is already a zero-filled `int[2]`, so absence gives the same answer
  without the trap.

Also worth recording because it is not obvious: `isMagic` is read with `getInt`, so `"1"` is
valid there - but it is copied from the real skill rather than forced on, because the magic and
physical cast routes animate differently and the two chosen skills disagree on it.

The finished key set is now verified by building it against the real jar rather than by
restarting the server to find out. The build was also proven through the module's own
`values()` method via reflection, so the thing tested is the thing that ships.

### Affinity auras: why Dark looked permanently busy and Holy did not

Not a binding mistake. The two skills simply differ, and the difference is visible:

```
453  Escape Shackle   no isMagic in its XML  -> client shows no casting animation
1157 Body To Mind    isMagic=1              -> client shows the casting animation
```

The aura cast is one packet, `MagicSkillUse`, and the client answers it with the caster's
casting animation **only** for magic-type skills - judged from the client's *own* copy of the
skill tables. So the same module code, on the same tick, produced a calm idle aura on Holy and
a permanent casting loop on Dark, with nothing in `module.ini` to tell the two apart.

Worth being explicit that the earlier `isMagic=0` override could not have fixed this. It
changes the flag on the server's `Skill` object; the client decides from its own data, so the
flag has to be kept off the wire. That was proven on a live client - the toggle dropped the
flag and the client still cast.

The fix keeps the cast gesture off the wire for magic-type affinity skills by sending
`MagicSkillLaunched` alone, which the client plays as an effect with no cast to begin. This is
the same packet the hit-effects page already sends, and the reason that page can show an
effect without anyone gesturing. The real skill *level* is sent rather than a flat 1, because
the client looks the animation up by id and level.

Scoped deliberately: **only magic-type skills take the quiet path**, so a non-magic affinity
like Holy is unaffected and cannot change appearance from turning this on. That matters,
because Holy is the one that already looked right.

```
AffinitySilentMagic = true
```

### Final affinity bindings, and the awaken overlays turned off

> **Historical.** The skill-cast bindings below are retired (all `AffinitySkill*` are `NONE`);
> this is the record of how they were chosen. See "Affinity looks fall back to effects only"
> in the changelog for the current state.

All four bound skills are `isMagic = false`, so **none of them casts** and
`AffinitySilentMagic` has nothing to do for any of them:

```
 3631 Void Flow             isMagic=false  ONE   hit 1900
 3093 Item Skill: Silence   isMagic=false  ONE   hit 0
  453 Escape Shackle        isMagic=false  SELF  hit 4000
 3091 Item Skill: Poison    isMagic=false  ONE   hit 0
```

Wind and dark fell back to Silence and Poison because those are the skills their awakened
procs already use (`TameAwakenedProc`: wind 3093 Silence, dark 3091 Poison), so each affinity
now wears its own proc as a look. That is safe rather than a shortcut: the proc skills are
applied with effects stripped, so the aura only draws the animation and the proc still does
exactly what it did before. Same ids on both paths, one with effects and one without.

`AffinityEffectEarth` moved from `ROOT` to `DOT_SOIL`.

Both awaken overlays are now `NONE`. `AwakenedEffect` was `BIG_BODY`, which scaled the whole
creature rather than the aura - a big glowing beast, not a big glow - and `AwakenedEffectSecond`
was `SLEEP`. The awaken stage still decides which proc fires; it no longer decides what the
beast looks like.

### Stacking: already supported, up to five layers

This was asked as though it might not be possible. It is, and it has been all along -
`TameVisual` collects a `List<AbnormalVisualEffect>` and every layer is null-checked, so a
`NONE` source simply contributes nothing:

1. the affinity effect (`AffinityEffect<X>`)
2. `AwakenedEffect`, when stage > 0 - now `NONE`
3. `AwakenedEffectSecond`, when stage > 1 - now `NONE`
4. the rarity effect (`RarityEffect*`)
5. the lab's audition preview, so a candidate can be judged against what the beast is
   already wearing rather than in isolation

The client stacks them; the module does not choose between them. The one rule is **one effect
per source** - a beast never carries two copies of the same visual, and `LEGENDARY` wins over
the common tier rather than layering with it.

So the ceiling is a beast wearing affinity + rarity + preview at once, which with the awaken
overlays off is three. If it ever looks like effects are being *replaced* rather than added,
the thing to check is a second `AbnormalVisualEffect` with the same underlying bit, not the
layering - the layer list is additive by construction.

`TameSkillAura.AFFINITIES` was `{ "HOLY", "DARK" }` and `TamingModule` only read skill ids for
those two, so **wind and fire had no skill aura at all** - their look was whatever
`AffinityEffect*` bitmask carried, and fire was `NONE` since the GHOST_STUN ghost was turned
off. Both are now the full six, matching `TameVisual`:

```
FIRE, EARTH, WATER, WIND, HOLY, DARK
```

Current bindings, all verified against the loaded datapack:

```
 5185 Production: Magic-type Guard  isMagic=false  target=ONE   hit 4000  reuse 6000
 1101 Blaze Quake                  isMagic=TRUE   target=AURA  hit 4000  reuse 15000
  396 Heroic Berserker              isMagic=false  target=SELF  hit 1000  reuse 1200000
  453 Escape Shackle                isMagic=false  target=SELF  hit 4000  reuse 6000
 1157 Body To Mind                 isMagic=TRUE   target=SELF  hit 4000  reuse 20000
```

`1101 Blaze Quake` is magic-type and would have cast on fire, which is what
`AffinitySilentMagic` exists for. Setting that to `false` puts fire back to casting.

Note the reuse delays are the *originals'* and are not enforced here - the rebuilt skills
carry the real id and level but `reuse=0`, and the aura's own `AffinitySkillMs` gap is what
paces it.

### SKILL VISUALS reworked: player damage skills in four categories

The page listed all 2682 named skills from `getBaseGameSkillIds`. That pool is mostly NPC
and pet abilities nobody would put on a collared beast, and finding anything in it meant
reading past a thousand names to guess a keyword. It now lists **player skills that deal
damage**, split across four tabs.

Source of the pool, and why the obvious filter does not work:

- **Pool** - the union of all 89 class skill trees via
  `SkillTreeData.getCompleteClassSkillTree`. That is 622 unique ids after dedup (the same
  id sits in a base tree and every subclass that keeps it, so it is collected once through
  a `seen` set).
- **`Skill.isDamage()` is unusable here** - it returns false for all 622 player skills. The
  flag is not populated by this datapack, so filtering on it yields an empty page that
  looks like a bug in the page.
- **`getEffects` / `hasEffectType` are unusable here too** - this engine build has **no
  concrete effect handler classes at all**. `model/effects/` in `GameServer.jar` contains
  only `AbstractEffect`, `EffectFlag`, `EffectTaskInfo`, `EffectTickTask` and `EffectType`.
  Every instantiation logs `Requested unexistent effect handler: PhysicalDamage` and
  returns nothing, so the runtime cannot classify skills by their effects.

So "deals damage" is read from the datapack instead, by the new
`TamePlayerDamageSkills`. One regex pass over `data/stats/skills` - the chunked
`NNNNN-NNNNN.xml` files, which is where the core reads them from too
(`new File(".", "data/stats/skills")`) - collecting ids whose block declares one of
`PhysicalDamage`, `MagicalDamage`, `EnergyDamage`, `MagicalDamageMp`, `StaticDamage` or
`PhysicalDamageHpLink`. Comments are stripped first: the per-level comments in those files
routinely mention damage in prose, and a skill must not be classified by its own comment.
A failed or empty scan falls back to the runtime check and logs which source it used, so a
moved directory degrades to a shorter list rather than a silently wrong one.

Result: **95 skills**, from 622 player ids, after dropping 82 passives and 445 non-damage
skills.

```
magic     0-40 ->  5      magic    151-700 -> 14
magic    41-150 ->  2      magic    701+    -> 29
physical 0-40  -> 30      physical 151-700 ->  9
physical 41-150 ->  0      physical 701+    ->  6
```

**Tabs** (new bypass `tame_aura_cat <which> <page>`, two rows of three above the fold):

- `ALL` 95, `MAGIC` 50, `PHYSICAL` 45
- `SHORT` 37, `LONG` 58

Magic/physical and short/long are two axes, so the tabs overlap rather than partition:
each half accounts for all 95 and a skill is reachable either way. `SHORT` is cast range
<= 150.

**A cast range of -1 is not a bug and not "very long".** It means the skill names no range
at all, which is what the self-centred area attacks use - Whirlwind, Thunder Storm, Sword
 Symphony, Psycho Symphony, Poison Blade Dance and Demonic Blade Dance all report -1. Those
are the most promising rows on the page for an aura, because they are already drawn around
the caster rather than at something in front of it. They count as short, being the opposite
of long range, and the page says so.

**The NOT magic toggle is gone.** It set `isMagic=0` on the rebuilt skill and claimed on the
page that this "drops the casting gesture". A live test showed the client gesturing anyway,
because the server's copy of that flag is not the one the client reads. Rather than keep a
control that promises something it cannot deliver, the audition now plays the quiet way
unconditionally: for a summon, a bare `MagicSkillLaunched` carrying the real id and level,
which is exactly what the affinity auras do with `AffinitySilentMagic` on. A bench that
gestures differently from the shipping behaviour is not a bench. Non-summon targets still
go through `doCast`, which cannot be quieted without becoming a different method; auditioning
on a plain monster is the diagnostic case rather than the shipping one.

### Balance: compounding growth was unbounded, raid and high-level tames were absurd

Reported as: weak tames are fine, raid and high-level tames are broken and make every
player DPS pointless. The cause was not tuning, it was a power of a power.

A tame's stats are the wild mob's own stats, times the module's factor stack, times
`(1 + growthPercent)^(level - 1)` (`PetProfileGenerator`). `growthPercent` is rolled per
individual in `TameProfileFactory` as `1.25 + rand*5.75 + potential/100`, so **1.25% to
8.00% per level**:

```
 level   g=1.25%    g=3.0%    g=5.0%    g=8.0%
    20       1.3x      1.8x      2.5x      4.3x
    62       2.1x      6.1x     19.6x    109.4x
    80       2.7x     10.3x     47.2x    437.0x
```

Two multipliers were scaling together and multiplying: the tier of whatever the beast ate,
and its level. The `RAID`/`BOSS` conversion coefficient of `0.50` did nothing about it,
because it was being multiplied by up to 437x. A raid-boss tame at level 80 landed at
**218x a raid boss's own PAtk**.

That also explains the asymmetry in the report exactly. Weak tames were not "fine because
they were balanced" - they were fine because their captured base was tiny, so even 437x of
a wolf is still a wolf. Nothing was actually correct; the weak pets were just hiding the
same runaway.

The fix caps the **result**, not the rate:

```java
double growth = Math.min(Math.pow(1.0 + growthPerLevel, level - 1), growthCeiling);
```

Capping the rate was rejected deliberately: it would make every roll above the cap
identical and erase the difference a growth potential is supposed to make. Capping the
result keeps each beast's own curve and its own roll, and bounds only the top.

`RAID`/`BOSS` conversion went `0.50 -> 0.35 -> 0.2333`. That number was previously a rounding
error against the runaway; now that growth is bounded it is the thing that actually decides how
frightening a raid tame is, and 0.35 left a max-growth raid tame at `1.05x` the boss it was taken
from. The current figure is chosen so the product lands at **`0.70x`**:

```
0.2333 x 3.0 = 0.70     (read as "0.2333 x 3.0", not as "0.70")
```

Setting the coefficient to `0.70` itself would give `2.10x` - the number people feel is the
*product* of the coefficient and the capped growth term, and only one of those two is the
coefficient.

The lower clamp under it also had to move. `safeMultiplier` was
`Math.max(0.35, ...)`, and 0.35 sat *exactly* on the old coefficient, so every attempt to
weaken a raid tame further was silently undone before it reached a stat line. It is now
`0.20`, documented as a sanity floor against a zero or negative multiplier rather than a
balance control.

Measured effect, before -> after:

| tier | level | growth roll | before | after |
|---|---|---|---|---|
| RAID/BOSS | 80 | 8.00% | 218.50x | **0.70x** |
| RAID/BOSS | 62 | 8.00% | 54.68x | **0.70x** |
| RAID/BOSS | 80 | 5.00% | 23.60x | **0.70x** |
| NORMAL | 62 | 1.25% | 1.71x | 1.71x |
| NORMAL | 80 | 1.25% | 2.13x | 2.13x |
| NORMAL | 20 | 5.00% | 2.02x | 2.02x |

The weak cases are **bit-identical**. Only the runaway is touched, which is what makes this
safe to ship without re-earning everyone's trust in the numbers.

Config: `PetGrowthCeiling = 3.0`, range 1.0..20.0, out-of-range values refused and logged
rather than clamped. Read at module enable so a typo is reported once at boot.

**Existing beasts need no migration.** `DynamicPetSummon` calls
`PetProfileGenerator.generatePersisted` on every summon, which recomputes the whole stat
table from the stored base values through `individualFactors` and the growth term. The base
numbers in the collar data are untouched; a beast simply picks up the new ceiling the next
time it is called out.

The honest cost: a beast that rolled maximum growth is fully grown by about level 15,
while the minimum roll never reaches the ceiling and is still climbing at the level cap. So
"high level" stops being a power spike. What separates one tame from another becomes the
factors that are individually bounded and meant to be - potentials, temperament, affinity,
imprint, equipment, awakening.

### Balance: absolute stat ceilings, because bounding the multiple was not enough

Reported as: cap the other stats too. HP had a ceiling; PAtk, MAtk, PDef, MDef, accuracy,
evasion, crit rate, move speed, attack speed and cast speed did not.

The growth ceiling bounds a **multiple**, and that is only sufficient while the number it
multiplies is ordinary. It stops being sufficient the moment the base is not - a raid boss
begins an order of magnitude above a normal mob, so `3x` that base is still a value the game
cannot answer. The asymmetry behind the original runaway was never really about the
multiplier. It was that a wolf at `437x` is still a wolf while a raid boss at `3x` is not.

So the multiple keeps its job of deciding the **shape** of a beast's progression, and the
ceilings decide where each individual stat stops. Applied after growth and after conversion,
at the last point before the number reaches the client.

Two families, because the stats are read from two different places:

| stat | read from | capped in |
|---|---|---|
| HP 30000, PAtk 22000, MAtk 22000, PDef 3000, MDef 3000 | the tame's own generated level table (`PetLevelData`, via `org_*`) | `PetProfileGenerator` |
| crit rate 450, attack speed 950, cast speed 310, move speed 200 | the cloned `NpcTemplate` (`baseCritRate`, `basePAtkSpd`, `baseMAtkSpd`, `baseRunSpd`) | `TameForge.cloneWithId` |
| accuracy 180, evasion 130 | neither - computed by the core from level and DEX | `TameForge`, as a derived DEX ceiling |

The second family exists at all because those four are **not in the level table**. They come
from the NPC template the tame is forged onto, and that template is a copy of whatever wild
creature was eaten. A boss-species tame therefore starts from the boss's own crit rate and the
boss's own speeds, and no amount of this module's growth touches any of them.

Move speed also caps walk, swim and fly speed to the same figure, so no movement mode can be
the one that breaks the ceiling - a tame that could not run at the cap but walked faster than
it could run would be nonsense.

### Accuracy and evasion are configured as themselves, and DEX is derived from them

Neither has a template key. The core computes both in its own formula functions, from level
and DEX:

```
evasion  = level + 6*sqrt(DEX) + (level - 67)                      [above level 69]
accuracy = level + 6*sqrt(DEX) + (level - 76) + (level - 69)        [above 77, above 69]
```

DEX is the only input either takes beyond level, and DEX does come from the template, so that
is the one place the value can be bounded. Config exposes `PetAccuracyCap` and
`PetEvasionCap` - the numbers an owner actually wants to reason about - and the DEX ceiling
written to the template is **derived** from them and from this server's own pet level cap, at
every tame.

Derived rather than configured on purpose. Both formulas climb with level, so the DEX that
produces a given accuracy depends entirely on how high a tame can get. A hardcoded DEX would
be a silent balance bug the moment the level cap moved, and nothing about the number itself
would reveal it: it would just quietly stop working. Raising the level cap is therefore safe
here. Both ceilings are solved rather than assuming evasion is the tighter one, because a
generous accuracy and a tight evasion should follow the tighter whichever it is.

At the defaults on a server capped at pet level 80 this derives DEX `38`:

| | at level 80 | ceiling |
|---|---|---|
| evasion | `80 + 6*sqrt(38) + 13` = **130** | 130 |
| accuracy | `80 + 6*sqrt(38) + 15` = **132** | 180 |

One honest edge case: a ceiling set *below* roughly (level cap + its level extras) cannot be
met by any DEX value, and the module then leaves DEX alone rather than writing a bogus number.
That case is silent by design - there is nothing useful to write - but it means a very low
accuracy or evasion cap does nothing rather than erroring.

`levelCap()` catches `Throwable` rather than `RuntimeException` on purpose. A missing
`experience.xml` fails the `ExperienceData` singleton's class initializer, which surfaces as
`ExceptionInInitializerError` first and `NoClassDefFoundError` on every call after - neither is
a `RuntimeException`, so a narrower catch would let a missing data file escape and take a
tame's accuracy cap with it.

Two things about the number it returns are worth writing down, because both are silent and
both cost accuracy and evasion for no reason when missed:

- **The off-by-one is real and the cap comes back one higher than the level.** `ExperienceData`
  parses the `maxPetLevel` attribute and adds 1 before storing it, using it as an exclusive
  table bound. `experience.xml` says `maxPetLevel="80"`, so `getMaxPetLevel()` returns **81**,
  and treating that as the highest reachable level derives against a level no tame can ever
  be. That quietly derived DEX **34** instead of **38**, leaving evasion at 128 against a 130
  ceiling - under the cap, not on it, with nothing in the log to say so.
- **The answer is 0 or 1 unless the player config is already loaded.** While parsing, the
  singleton clamps its own `maxPetLevel` against the static
  `PlayerConfig.PLAYER_MAXIMUM_LEVEL`, which is still 0 if nothing has called
  `PlayerConfig.load()` yet. The clamp then reduces the honest 80 to nothing, and the
  singleton reports 1 without complaining - it does not look like a failure, the derivation
  just runs against level 1 and every headroom comes out enormous. In the live server
  `PlayerConfig` is loaded long before any module's `onLoad`, so this only bites standalone
  probes and tool harnesses; `levelCap()` still guards it and falls back to 80 with a warning
  rather than trusting the artifact.

All caps share one contract: **a ceiling and not a target.** A wolf's few hundred HP and its
hundred-odd PAtk reach none of them, so weak and mid creatures are bit-identical to before and
only the genuinely broken high end is held down. A mid-boss tame sitting exactly on one of
these numbers is normal, not a sign the cap is too low. Each cap is independent, `0` disables
that one alone, and out-of-range values are refused and logged rather than clamped - a typo
that silently became `1000` would remove content from the game, and one that became `200000`
would leave the original problem in place while looking like it had been fixed.

Verified by probe against a boss-species template with every capped stat deliberately absurd
(crit 1200, PAtkSpd 2000, MAtkSpd 900, DEX 900, run 350, walk 300, swim 320, fly 400): all
eight capped to their ceilings; `basePAtk`, `baseHpMax` and `baseMCritRate` untouched; a weak
species passed through the same cap pass unchanged; a cap set to `0` disabled only itself;
`setPAtkCap(-5)` refused with a log line. Accuracy and evasion checked at levels 1, 40, 69,
70, 77, 78 and 80, plus confirmation that the derived DEX follows a changed evasion ceiling
(`38` at 130, `200` at 200) and refuses to invent a value when the ceiling is unreachable.

Both probes stand the player config up before they touch anything, because of the clamping
above. Without it `ExperienceData` reports a level cap of 1 in a bare JVM and every
derived-cap assertion in `CapProbe` measures the load order rather than the code - which is
exactly how the DEX 34 above survived long enough to be written down as if it were the
intended number.

### Raid tames now read the current raid coefficient, so balancing reaches existing beasts

`conversionMultiplier` is stored per tame at capture time and `generatePersisted` was using
that stored value. Changing the raid coefficient therefore only ever affected **newly captured**
pets - every existing boss tame kept whatever it was captured with, and a balance change
reached new players' beasts but not old ones.

Boss tames now read the current `RAID` coefficient at generation time instead of the stored
one. Every raid and boss tier maps to the same branch of the coefficient table, so re-reading
it reproduces exactly what a boss captured today would get - this cannot drift a stored tame
away from its own tier. Non-boss tames still use their stored coefficient, since those tiers
have not been under review and the freshness argument matters much less for a coefficient
nobody has changed.

A balance decision that only applies to beasts captured after it was made is not much of a
balance decision.

### Tames now fight with real player techniques, and existing tames get them on next summon

Asked for: real player skills on tames. What a tame could use before was its own species'
inherited techniques plus a short hand-written list of class buffs. The damage moves were
whatever the wild mob happened to have on its NPC template, which for most mobs is nothing
impressive.

`TamePlayerDamageSkills` already indexed the 95 active player damage skills for the SKILL
VISUALS page, so `TameSkillPolicy.playerSignature` draws from that same catalogue instead of
a hand-kept whitelist. All 95 are eligible, and that is a measured result rather than an
assumption:

```
active player damage skills = 95
  of those, declare <condition> = 0
  of those, mention weapon      = 0
  >>> condition-free subset    = 95
```

The condition check was verified against the datapack as a whole first, because a filter that
cannot match anything looks exactly like a filter that found nothing to reject. There are
**zero** `<condition>` tags in every skill file - this datapack does not use them - and none
of the 95 mention a weapon. So the pool needs no curation and cannot hand a tame a technique
it could never satisfy.

The pick is seeded from the individual's profile seed, the same way the awakening pool is, so
two wolves of the same role keep the same technique for life instead of rerolling every
summon. Role decides the flavour the way it already does for awakening: ARCHER gets physical
ranged, MAGE and SUPPORT get magic, anything else gets physical. Bosses inherit the existing
no-AOE, no-suicide, 1200-power-cap rule.

Verified by calling the shipped private method by reflection rather than reimplementing its
filters, 60 seeds per role:

| role | distinct techniques | misses | boss misses | range |
|---|---|---|---|---|
| ARCHER | 15 | 0 | 0 | 500-900 |
| MAGE | 35 | 0 | 0 | -1..900 |
| SUPPORT | 35 | 0 | 0 | -1..900 |
| WARRIOR | 32 | 0 | 0 | -1..900 |

Seed stability confirmed: the same seed always resolves to the same technique.

**Additive, not a replacement.** The signature slot goes to the player technique; the beast's
own inherited techniques still fill UTILITY, ADVANCED and the awakening slots. A species still
looks and plays like its species, it just also fights with one borrowed player technique.

### Existing decks are corrected on summon, by one row

A deck is written once, at capture, by `TameProfileRepository.saveSkills`. So every tame forged
before this existed would have kept its old signature forever - the feature would have looked
like it only applied to newly captured beasts.

`TameSkillPolicy.refreshSignature` runs on summon and compares the stored SIGNATURE_1 row
against what the current rule names, rewriting only on a mismatch. That makes it idempotent
and self-healing: the first summon after the change fixes the one row, later summons write
nothing, and a future change to the rules needs no migration because decks correct themselves.

Two details that were not incidental:

- `saveSkills` was **not** reused for this. It is a plain INSERT with no delete, so calling it
  to "refresh" a deck appends a second SIGNATURE_1 row beside the first instead of replacing
  it. `rebindSignature` is a targeted UPDATE that touches exactly one row, and SIGNATURE_1 is
  never an awakening slot, so an awakened beast keeps its chosen path.
- The primary key is `(tame_uuid, skill_id, slot_type)`, which permits more than one
  SIGNATURE_1 row per tame as long as the ids differ. Rewriting all of them to a single id
  would violate that key, so the statement is `ORDER BY skill_id LIMIT 1` and the read that
  drives it is ordered identically - the comparison sees the same row the write would change.

`enabled` is deliberately not rewritten, so a player who turned a technique off keeps it off.
Config: `PlayerDamageSkills = true`.

### Reagent shop: a partial fill used to become free items

`TameReagentShop.buy` added the items first and then inspected the single `Item` that
`Inventory.addItem` returns. That reference is not a reliable answer to "did we get them
all":

- a **stackable** add merges into one existing object and returns it with the full count
- a **non-stackable** add of N creates **N separate objects** and returns only the last one,
  count 1, regardless of N

So for any non-stackable reagent and any quantity above one, `reagent.getCount() < quantity`
was always true. The rollback that followed destroyed exactly the returned object, and Adena
was only ever taken on the success branch, which was never reached. Asking for 50 got 49 for
free. The add-before-pay ordering was not the cause - the last-item-only rollback was - but
both were replaced.

Replayed against a simulated inventory, old code:

| stackable | qty | returned | held after | Adena spent | |
|---|---|---|---|---|---|
| yes | 1 | 1 | 1 | 10000 | OK |
| yes | 50 | 50 | 50 | 500000 | OK |
| **no** | 5 | 1 | **4** | **0** | **4 free items** |
| **no** | 50 | 1 | **49** | **0** | **49 free items** |

Fixed by trusting the inventory rather than the return value:

1. `validateCapacityByItemId` and `validateWeightByItemId` up front, so the ordinary
   full-bag case is refused before any money moves. Weight is checked separately and is not
   implied by the slot check - these reagents weigh 20 each, so a player can pass the slot
   check and still be refused by the core for weight.
2. Adena taken once delivery is known.
3. Delivery measured by counting the player's held id **either side** of the add, summed
   across every object holding that id.
4. A short delivery is unwound completely: `destroyItemByItemId(REFUND, itemId, delivered)`
   removes that many across all objects regardless of how they were split, and the Adena for
   exactly those is returned. Cancelling the whole purchase is what the player-facing message
   already promised.

New code, same cases: **consistent** on all six - on success the player holds exactly `qty`
and paid exactly `qty * price`; on a short fill they hold nothing and paid nothing.

`getInventoryItemCount` was deliberately not used for the count: its second argument is an
enchant level, not a type filter, so passing a count there would silently count the wrong set.

### Skill sound cannot be suppressed from a module

Asked whether these could be made silent, since some skills carry an audible effect and being
able to ignore that would widen the choice of aura a lot. It cannot, and the reason is
structural rather than a missing feature:

- `Skill` has **no sound field and no sound accessor at all**. Searching the whole class for
  anything matching `sound` returns nothing.
- Neither `MagicSkillUse` nor `MagicSkillLaunched` carries a sound field or a mute flag.
  `MagicSkillUse`'s fields are exactly: skillId, skillLevel, hitTime, reuseDelay, creature,
  target, critical.
- Across the entire server, the only sound-related packet is **`PlaySound`**, whose enum entry
  is `PLAY_SOUND` - and it *plays* a sound. There is no counterpart that stops one.

So the server can add sound to an event but has no vocabulary for removing it. Whether a given
skill is audible is decided entirely by the client's own tables, which means a loud skill stays
loud no matter how the server sends it, and a silent skill stays silent. The only real lever
for volume is the client's own settings.

Worth being explicit that this does **not** narrow the catalogue: any id can be bound, and the
practical constraint is that some of them are noisy.

`game\modules\taming\nonmagic-skills.txt` lists every base-game skill the server reports as
`isMagic = false` - the ones that will not produce a casting animation on a tame. 1887 of 2682
named skills; the 795 magic-type ones are excluded. Columns: id, name, level, targetType,
hitTime.

Generated by `C:\Temp2\opencode\skillprobe\NonMagicList.java`, which loads the real datapack
and reads `Skill.isMagic()` - the same flag the aura code branches on, so the list cannot
drift from the behaviour. Note it is a proxy for the client's own tables, not a guarantee of
what renders; the SKILL VISUALS page remains the arbiter.

Of the 1887, 1259 are `SELF` and 1007 are `SELF` with no cast time - that last group is the
aura-shaped subset, since a repeating cosmetic wants something instant and self-targeted.

Running the probe needs the working directory set to the `game` subdirectory, because the
datapack root resolves to `data\...` while the files live at `game\data\...`. From the repo
root it loads 0 skills and reports `named=0`, which looks like a filter bug and is not one.

### Page navigation moved above the fold

`SKILLS`, `HIT FX`, `re-apply` and `clear` were below the full grid of values, and `BACK` /
`TOGGLE` were a tall single column on the skills page. Every button rebuilds the html, and a
rebuilt page lands the client back at the **top** of the document - so the loop was: scroll
down, click, get thrown to the top, scroll down again.

Both pages now lead with a two-up row of navigation buttons at 210 wide against
`WIDE_BUTTON`'s 100, above the fold and above the explanatory text. The `TOGGLE` caption was
also shortened from "playing as: NOT magic" to "NOT magic" / "as written", because a 100-pixel
button clips that text rather than wrapping it.

### RIDE: investigated, built, tested, then scrapped

**There is no riding code in this module.** A `RIDE` / `STRIDER` / `DISMOUNT` set of buttons
was written, wired, compiled and live-tested against the client, produced nothing usable, and
was then removed again on request. Nothing remains - no bypasses, no handlers, no methods, no
buttons. This section is kept because the findings are expensive to re-derive and they answer
questions that would otherwise be re-asked.

What was tried, in order:

1. `mount(Summon)` with the selected creature. The tame reported
   `npc 700010 sent to the client as 1700010 type 0`, then **dismissed itself**.
2. The same via `mount(npcId, mountObjectId, withFeed)` with the creature's *template* id.
   The tame reported `template npc 700006 sent as 1700006 type 0`. **Nothing drew.**
3. A `ride as strider` control, mounting as npc `12526` so the packet would carry
   `rideType 1` with an id the client certainly knows. Abandoned along with the rest rather
   than run.

Findings, all verified from bytecode rather than reasoned about:

**`Player` has two mount overloads and they are not interchangeable.**

| overload | npc id it sends | siege gate | unsummons |
|---|---|---|---|
| `mount(Summon)` | `summon.getId()` - **runtime** | yes, fails closed | **yes** |
| `mount(npcId, mountObjectId, withFeed)` | the argument you pass | no | no |

- `mount(Summon)` opens with `if (!ALLOW_MOUNTS_DURING_SIEGE && isInsideZone(SIEGE))`, then
  requires `findByNpcId(id) == STRIDER` plus castle ownership. It **fails closed**, so a boss
  can never pass it in a siege zone even with `ALLOW_MOUNTS_DURING_SIEGE` on. It also ends
  with `summon.unSummon(this)` - that is how a strider works, you mount the pet and the pet
  goes away - which on a boss meant RIDE dismissed the boss.
- The id overload has no siege branch and does not unsummon, so the tame survives being
  ridden. Its signature is `(int npcId, int mountObjectId, boolean withFeed)`, **not**
  `(npcId, level, force)`, and mount level is not ours to choose: it passes the **player's**
  level to `setMount`, not the creature's.

**For a tame, `getId()` and `getTemplate().getId()` are both useless to a client.** Both
returned values in the `700006`-style range, because `Pet.spawnPet(NpcTemplate, Player,
Item)` is handed an already-cloned template. The original id (`29028` for Valakas) is held by
the taming code and is **not reachable from inside a module**. So no amount of id handling
inside this module produces a boss id on the wire - the `29028` fix that looked like the
answer was, on the evidence of test 2, not the answer.

**Mount type and mount id cannot be chosen independently through the public API.**
`setMount(npcId, level)` derives `_mountType` from `findByNpcId(npcId)` and sets
`_mountNpcId` to the same argument. `MountType.findByNpcId` reads `CategoryData`, which holds
exactly four ids:

```
STRIDER      12526, 12527, 12528
WYVERN_GROUP 12621
```

So the public API can only ever mount one of those four creatures. Anything else rides out as
`type 0`, which the client refused to draw. Getting a boss would have meant writing
`_mountNpcId` and `_mountType` as two separate reflective fields on `Player` - `_mountType` is
private with no setter, and so is `_mountNpcId`.

**There is no second mount packet to fill in.** The `Ride` packet is built entirely from
player state, which is what closed off that workaround:

```
_objectId  = player.getObjectId()
_mounted   = player.isMounted()
_rideType  = player.getMountType().ordinal()
_rideNpcId = player.getMountNpcId() + 1000000
```

Still absent from both overloads: no "is this creature mountable" check, no summon lookup by
id, no ownership test. `mountPlayer(Summon)` does check `summon.isMountable()`, which is why
the normal in-game route refuses a boss - but reaching that path was never the plan.

Why it was dropped: it needed reflection into core privates for every field that mattered,
the ids it could produce were not the creature the user actually wanted, and the client's
mount tables (`npcgrp.dec`, `skillgrp.dec`) are encrypted so the outcome could never be
predicted - only observed, one restart at a time.

Also worth recording, because it was wrong here first: **the concrete skill effects are not in
`GameServer.jar`.** That jar has only the framework - `model/effects/` is five files
(`AbstractEffect`, `EffectFlag`, `EffectTaskInfo`, `EffectTickTask`, `EffectType`) and zero
implementations. All 146 live in the scripts tree at
`data/scripts/handlers/skill/effects/`, registered by `EffectMasterHandler.java`, which is
handed to `ScriptEngine` as a path. Searching only the jar produced a confident and wrong
"this effect does not exist on this server" - the jar is not the whole server, and an empty
result from it means nothing.

#### The pet skill tab cannot be changed from a module

The strider shows two skills in the pet UI window, and the obvious hope is that a module can
put every pet skill there instead. It cannot, and the reason is structural rather than a
missing permission.

`PetInfo` sends **no skill list at all**. Every method it calls was dumped: the only `Skill`
references are stat getters (`getMAtk`, `getMDef`, `getCriticalHit`) plus
`getAbnormalVisualEffects` and a few template getters. Across the entire server the only
packets carrying skill lists are `SkillList`, `AcquireSkillList`, `ExEnchantSkillList` and
`PledgeSkillList` - none of them pet-related.

So that window is drawn from the client's own tables, keyed by npc id, and those are
encrypted. A module can change what a summon *casts* freely - that is the entire
`TameSkillAura` route - but it cannot change what the client *lists*. Behaviour and
presentation are separate problems here and only the first one is reachable.

The request was for Body To Mind's *post-cast effect* without its casting gesture, on the
reasoning that holy already looks like a pure cosmetic and dark does not.

**A toggle, not a config key, and the reason matters.** Whether forcing `isMagic=0` drops the
gesture but keeps the effect cannot be answered from the server. The client has its own table
for skill 1157 and draws from that; the flag this module sends is a second, separate input. So
it is a button on the page rather than a setting in `module.ini`: the answer is two presses,
and a setting would have meant a restart per guess.

Server side it provably works, and the copies are cached per `(id, isMagic)` rather than per
id, so asking for the non-magic build cannot hand back the magic one:

```
1157 as written   isMagic=true  effects=0 mp=0 hp=0 hitTime=0
1157 non-magic    isMagic=false effects=0 mp=0 hp=0 hitTime=0
1157 -> override changed the build: true | separate object: true
```

**What the probe turned up that was not the question:** of the four skills in the datapack with
a `ManaHeal` effect, **1157 is the only magic one**. 453, 417, 2245 and 2288 all come out
`isMagic=false`, so the toggle does literally nothing to them - `differs: false`. Two of them
(`2245`, `2288`) carry a raw `isMagic` of `2` in the XML, which the core reads as *not* magic.
So "try it on the other ManaHeal skills" was never going to be a useful experiment, and the
toggle only has one skill it can act on.

All fifteen builds stayed inert: `effects=0 mp=0 hp=0 hitTime=0`. The override did not open a
hole.

**Rejected: raising `hitTime` to 1s.** It was proposed alongside the override, on the reasoning
that a second of cast time would separate the phases. It would, but it is the wrong trade:

- The ManaHeal visual already plays *after* the animation at `hitTime=0` - that is where the
  preference for it came from. There is no mush to separate.
- A non-zero `hitTime` puts the beast into a real cast: standing still for a second, with a
  cast bar, every four seconds. That is precisely what zeroing `hitTime` was for, and the note
  on that line in `module.ini` says so.
- With the gesture already suppressed by `isMagic=0`, that second would most likely be a
  visible pause doing nothing before the effect lands.

Left at `0`. If the effect turns out to be too quick to read once the gesture is gone, `hitTime`
is the lever for that and it is a one-line change - but it should be raised because the visual
is unreadable, not because the phases need separating.

The SKILL EFFECTS page is now SKILL VISUALS, and the change is bigger than a rename.

**What it does now:** lists **every named skill in the data** - 2682 of them, alphabetically
- and pressing one plays that skill's animation on your target and does nothing else.

**How.** It reuses `TameSkillAura.visualOnly`, the same rebuild the holy and dark affinity
auras cast. The sixty-odd keys that rebuild needs are the part that is easy to get wrong, and
this is deliberately one copy of that knowledge rather than two. The rebuilt skill keeps the
real id and level, so the client draws the real animation, and carries no effects, so nothing
happens.

**Verified across the whole catalogue, not one skill:**

```
catalogue size = 2682      (2694 base game ids; 12 dropped as unnamed)
rebuilt cleanly = 2682
could not build  = 0
rebuilt WITH effects = 0
```

That last line is the safety property, and it is measured over every button on the page. It is
what lets the page hold all 2682 without any of them being dangerous to press.

**Why this replaced the all-skills grant.** The grant crashed the server twice and was written
twice to fix it. This route has none of the problems that made it attractive in the first
place: nothing is granted, so nothing is written to the character; nothing consults magic level
or a precondition, because the *creature* casts and the skill belongs to nobody; and there is
nothing to clean up afterwards, because nothing is applied.

**Two route details:**

- `useMagic` is not on `Creature`. It is on `Summon`, and for the tame - which is what this is
  nearly always pointed at - the page calls it, which is the exact call the affinity auras
  make. Anything else falls back to `Creature.doCast`, which is `beginCast` and nothing more:
  the broadcast that draws the effect.
- `useMagic` checks passive, already-casting and reuse, but it does **not** check whether the
  caster knows the skill. That is the whole reason it is usable here, and it is worth knowing
  before someone "fixes" it by adding a check.

**Catalogue filter reduced to one test:** the skill has a name. The old filter also excluded
passives, toggles, dances, suicide and damage skills, and anything without a positive
abnormal time, because casting them on a live target was a bad idea. That reasoning was
correct for what the page did then. It is wrong now: this is a list of things to look at, and
the only honest way to find out whether one draws anything is to press it. Filtering guesses
which ones are worth looking at, and every guess is a skill that can never be found.

Levels are read from the top down rather than assuming level 1 exists - some skills do not
have one, and asking for level 1 alone would silently drop them from a page whose purpose is to
contain everything.

Ordering is plain alphabetical by name. The elemental-first ordering was worth it when the
list was a few hundred buff names and the question was "which of these draws a glow"; with 2682
entries the list is read by looking for a specific name.

**Removed with it:** the buff handle, the OFF button, `tame_aura_skill_off`, and the whole
`//give_all_skills` + `//setclass` button set. There is no longer anything to remove, because
nothing sticks.

**Kept, and still expected to draw nothing:** HIT FX. It sends a bare launch packet, which is
not the same thing as a cast - that is the finding that started all of this. The two pages are
not expected to agree.

### All-skills grant in the aura lab - REMOVED, do not restore

The grant button is gone, and so is the `//give_all_skills` + `//setclass` button set that
briefly replaced it. Neither should be reinstated.

**Why the grant crashed:** twice. The first version called `addSkill` per skill; that was
rewritten to write the known-skill map directly and send the list once, on the reasoning that
the per-call stat bookkeeping was the cost. It crashed again anyway. No stack trace was produced
either time, which is the worst combination: repeatable, and unexplained.

The conclusion worth keeping is not "write the map more carefully". It is that a diagnostic
button should not be the thing discovering what a couple of thousand skills costs on a live
game thread. A crash that logs nothing about itself will be back the next time anything is
added to that path, and it will be blamed on whatever changed most recently.

**Why the admin commands went too:** SKILL VISUALS, above, does the job better and needs
nothing granted. The admin route also had a cost nobody wants by accident - `//give_all_skills`
**writes to the database**, where the original grant was session-only.

Two findings from that attempt are kept here because they are properties of the core, not of
this module, and the next person to wire up any admin command from a button will hit both:

- **Admin commands read their victim from `activeChar.getTarget()`.** A button supplies no
  target, so they silently do nothing unless one is set. Any such button has to set the target
  and then put the previous one back - restoring rather than clearing, because a player who was
  targeting something did not ask to lose it over a button.
- **Class ids are ids, not ordinals.** `PlayerClass` is built with two integers per constant
  and `getId()` returns the second, so the enum's ordering is *not* its ids. `//setclass`
  validates against `getId()`: ids 58 to 87 are ordinals and are rejected, and Duelist is `88`,
  not `58`. Third classes are 88 to 118, per the datapack's `classList.xml`.

### Visual and client safety

- Restored the collar's client-known display identity.
- Removed an obsolete dealer silhouette override that forced an incorrect fallback appearance.
- Removed the empty HTML button terminator that rendered as a black square below skill buttons.
- Kept all changes server-side and module-owned.
- Closed every `<button>` on the reagent page. An unclosed `<button>` was the single cause of
  three separate symptoms there: the `OPEN SHOP` caption appearing twice, the button text
  spilling outside its cell, and `CLOSE` sending the wrong page. The engine did not report
  the malformed nesting, it just rendered it.
- Removed the `tamepix` texture probe and all of its scaffolding, including the layout
  comparison swatches and the `border` overload of `iconTag`.
- Ruled out `bgcolor` on this client: a coloured panel crashed it. The page emits no cell
  colours.
- Ruled out seamless tiling of client textures into a panel background: the engine cannot
  butt images together without a gap, and every gap draws a grid.
- Set the reagent icon to 32 by 32, the size the client's own inventory uses.
- Reduced the shop's hardcoded texture names to none, reading each icon from its item
  template instead, and dropped the Adena coin so prices are text.
- `CLOSE` now sends an empty page and closes, rather than a "thank you" page.
- Reagent store: the `OPEN SHOP` button is 100 wide, not 140. At 140 the client drew a 100 pixel body plus a separate 12 pixel square. The "Open the shop window" line under it is removed.
  
  ## Final cleanup pass
  
  - **Four template caps were silently not applied.** `TameForge.cloneWithId` builds the clone by
    copying the stock template's attribute map, capping the entries in that map, and handing it to
    the stock `NpcTemplate` constructor - and then copying every non-final field across from the
    original template. `_baseCritRate`, `_basePAtkSpd`, `_baseMAtkSpd` and `_baseDEX` are private
    and non-final on `CreatureTemplate`, so that copy ran straight back over the capped values: a
    boss-species tame kept the boss's own crit rate, attack speeds and DEX, and every ceiling still
    read as configured. The movement-speed caps were unaffected because those live in the final
    `_moveType`, which the loop already skipped. The loop now skips the four capped fields by name,
    and the reason is recorded next to the ceilings rather than only at the loop, since the list is
    only meaningful together with the attributes those ceilings write to.
  - **New probe: `CloneProbe`.** `CapProbe` checks the attribute map, which is only an intermediate:
    it proved the caps were written but not that they survived into the finished template. This is
    exactly the gap the bug above lived in. `CloneProbe` calls `cloneWithId` for real and reads the
    clone back through `CreatureTemplate`'s public getters, and also asserts the wild stock template
    is untouched (a shared template that got capped would silently nerf every wild creature of that
    species in the world), that a weak species comes through unchanged, that `0` disables one cap
    without disabling the rest, and that a cap changed after a template exists shows on the next
    clone. Neuting the guard reproduces 6 failures, so it is testing the thing it claims to.
  - **Removed the manual skill `USE` buttons**, the `tamecollar_skill` bypass and its handler, and
    with them `castSkill`, `describeRejection` and `named`. See *Skills* above for why a manual fire
    could only disagree with auto-cast. `TameSkillBar`'s javadoc no longer refers to the deleted
    method; the gates it applies at bind time are the same ones, now the only ones.
  - **Removed the `World aura:` line from the imprint page.** It named an `AbnormalVisualEffect` for
    the player to have no way of checking, on a page whose job is showing what the collar rolled.
    The aura itself is untouched - `TameVisual` still applies it, still guards it, still answers
    `.tameaura`. `TameVisual.effectName` is kept, because `.tameaura`'s own output still uses it.
  - **Awakening button captions are 140 wide, not 120.** `sek.cbui94` places its caption from a
    fixed offset baked into the texture, so a button narrower than the caption's natural extent
    renders the word left of centre. `<center>` and `align=center` centre the *button* in the page,
    which is a different thing from centring the *caption* in the button, and neither can move a
    caption the texture has already placed. `AWAKENING_BUTTON_OPEN` / `AWAKENING_BUTTON_CLOSE` now
    hold the two halves so the caption can be escaped into the middle of the `value` attribute.
    `AWAKEN AGAIN` uses the same pair instead of its own hand-written markup.
  - **Second awakening verified, and kept.** The concern that it was never registered does not hold
    up against the bytecode: `Creature.doAttack` reads its `_triggerSkills` map directly, filters it
    on `OptionSkillType.ATTACK` (or `CRITICAL`), rolls `Rnd.get(100) < getChance()` and calls
    `makeTriggerCast` on the skill. All six proc ids resolve in the shipped datapack
    (`03000-03099.xml`): 3083 Slow, 3085 Stun, 3091 Poison, 3092 Bleed, 3093 Silence,
    3137 Duel Weakness. `Options.apply` is the only other writer of that map and it targets
    `Player`, so a `Summon` needs `TameAwakenedProc.apply` to fill it, which is what both the
    summon path and the experience path do. It is worth keeping as the one behavioural difference
between the two awakenings, with the caveat already documented in `TameAwakenedProc`: the chance
      is in percentage points, not a fraction.
    - **A beast capped at 80 stopped at level 79 with its bar pinned at 99.99%.** This is the worst
      bug in the module and it was invisible from the outside. `PlayableStat.addExp` clamps a pet's
      experience against `getExpForLevel(getMaxLevel())`, and `PetStat.getMaxLevel()` is
      `ExperienceData.getMaxPetLevel()`, which counts *up* from the `maxPetLevel` attribute - it
      reports 81 for a cap of 80. So the core asks the table for the row one past the real ceiling.
      That read goes through `PetDataTable.getPetLevelData`, which quietly clamps the requested level
      down to `PetData._maxLevel`, and `TamePetDataRegistry` had set that to the highest level it
      generated - 80. The lookup landed back on level 80's own row and handed the core
      `4200000000` instead of `6300000000`, so the core pinned experience at `4199999999`: one point
      short of level 80, forever, with nothing in the log.
      Nothing about it looks like a data problem. The profile said 80, the collar said 80, and the
      beast filled its bar and sat there. `PetData._maxLevel` is not the pet's level cap; it is the
      size of the table, and the two only ever agreed by accident.
      `PetProfileGenerator` now generates one row past the beast's cap. That row is a threshold, not
      a level: it is unreachable as a level (the core's own level walk refuses to pass
      `getMaxLevel() - 1`, and `TameLevelCap` holds the beast anyway), and it exists so the core's
      clamp reads a real next-level threshold instead of the cap's own. The row's `exp` also had to
      stop clamping at `getMaxLevel() - 1`, which would have handed it the cap's threshold again and
      reinstated the stall.
      Verified by replaying the core's own arithmetic - `PetDataTable`'s clamp, `PetStat`'s two
      methods and both halves of `PlayableStat.addExp` - against a real `PetData`:
      | | table rows | core was handed | settled at | result |
      |---|---|---|---|---|
      | before, cap 80 | 1..80 | `4200000000` (the cap's own row) | `4199999999` | stuck at 79 |
      | after, cap 80 | 1..81 | `6300000000` | `6299999999` | reaches 80 |
      | after, cap 62 | 1..63 | `189363788` | `189363787` | reaches 62, unchanged |
      | after, cap 1 | 1..2 | `68` | `67` | reaches 1, unchanged |
      Lower caps were never affected, because for them the clamped lookup happened to land on a row
      whose threshold was the right one to stop at. Only a beast capped at the full ceiling hit it.
    - **`TameLevelCap`'s javadoc described the wrong order of operations.** It claimed the
      experience event fires *after* the new total is written. The bytecode has it the other way
      round: `PlayableStat.addExp` notifies `ON_PLAYABLE_EXP_CHANGED` first, and only then adds the
      gain and writes the field. The event carries the old and new totals as arguments, but
      `getExp()` still reads the old one throughout the callback. The clamp still works, and for a
      better reason than the one documented: the core re-reads `getExp()` after the event returns,
      so a value lowered during the event is picked up by the arithmetic instead of being
      overwritten. `hold` cannot see the incoming gain, which is why it clamps a total that is
      already over rather than predicting one.
    - **The thirteen-race matchup circle.** Every beast is strong against one race, weak
      against one other, and even against the remaining eleven:
      `ANIMAL -> BUG -> PLANT -> BEAST -> HUMANOID -> UNDEAD -> DIVINE -> DEMONIC -> FAIRY
      -> CONSTRUCT -> GIANT -> DRAGON -> ELEMENTAL -> ANIMAL`. Twelve percent damage up
      against the next race, eight percent down against the previous, eleven neutrals. No
      race is strictly better than any other, so no collar is dead on arrival.
      These are the core's own races - `org.l2jmobius.gameserver.model.actor.enums.creature.Race`
      declares exactly these thirteen creature races and `CreatureTemplate.getRace()` reports
      one for every creature in the world. That is the only reason a matchup table is possible
      under the read-only-datapack rule: the data already exists and nothing had to be invented.
      Races outside the circle (players' races, mercenaries, castle guards, siege weapons, the
      `NONE` placeholder) can be rolled onto a profile by `TameProfileFactory`, so they are
      handled explicitly and fight on even terms rather than being left to fail a lookup.
    - **The circle is not a buff, and the reason is worth writing down.** It was going to be a
      permanent self-buff - the obvious reading of "a pet always has this racial bonus". That
      cannot work here, for two separate reasons.
      First, a buff is unconditional: an effect on the beast applies to every target for as
      long as it lasts, so a permanent "strong against insects" buff is just a flat damage
      bonus for owning an animal and would fire just as hard against a dragon. Second, this
      engine has no effect type that keys damage off the target's race at all. All 41
      `EffectType` values were checked and none of them does, so there is no such buff to find
      in the datapack. `EffectHandler` on this build also resolves no concrete handlers for
      stock effects, which is the same dead end already documented in `TamePlayerDamageSkills`.
      The hook that does work is `CreatureStat.calcStat`, which walks a per-stat `Calculator`
      owned by the creature being asked and hands every function in it the creature plus the
      value so far. `Formulas.calcPhysDam` calls `getPAtk` during the damage calculation
      itself, so a function on the beast's own `POWER_ATTACK` calculator sees the swing it is
      part of and can read the beast's current target at that instant. `AbstractFunction` is
      public with a single abstract `calc`, so this is a supported extension point and not a
      reflection hack.
      The result is better than the buff that was asked for: no duration to expire, no icon to
      flicker, nothing to recast, and no way for it to be wrong after a retarget.
    - **The circle cannot leak into other creatures.** `NPC_STD_CALCULATOR` is a shared static
      array, so adding a function to it would have scaled every NPC in the world. It is not
      reachable that way: `Creature`'s constructor gives every non-NPC its own private
      `new Calculator(existing)` copies, and `getCalculators()` hands back the instance field
      itself rather than a copy. A function installed on one beast's calculator is invisible to
      every other creature. `unapply` is called before each install and identifies its own
      functions by class, so a resummon or a module reload replaces rather than stacks - the
      second copy would have doubled the bonus. It copies the function array before removing,
      because `removeFunc` rewrites the array being walked and removing in place skips entries.
    - **Two limits, stated rather than glossed.** The bonus follows the beast's *current*
      target, which is what the core's own AI uses to decide what it is attacking; `doAttack`
      does not set `_target` itself, so a swing aimed at something other than the current
      target would be read against the current one. And the multiplier is applied before the
      server's own attack cap is taken (`getPAtk` caps the result of `calcStat` against
      `MAX_PATK`), so it scales a capped value instead of pushing past the cap.
    - **Proved by `RaceProbe`**, which checks the circle as pure rules: 13 races in, each
      strong against exactly one and weak against exactly one and even against eleven, exact
      `1.12` / `0.92` on the two edges, no self-advantage, direction held on all 169 ordered
      pairs (a one-sided edge would make a fight depend on who swings first), the ten
      non-circle races untouched from both sides, profile-string parsing including
      `Race.name()` case and padding with junk and null rejected, and config fallback for an
      unparseable or out-of-range percentage. It reads the circle back through the module's own
      accessors rather than a second copy of the table, so the two cannot drift apart.
    - **Affinity and the race circle are kept apart on purpose.** Affinity remains a flat,
      target-independent bonus on one stat, described as exactly that. The collar page's
      affinity block used to carry a comment justifying itself by claiming no per-target
      rescaling was possible anywhere in this build; that reason is now stale and the comment
      was replaced with the real distinction, so the two systems are not read as one mechanic.
    - **Shown where the player will actually look.** A `RACE CIRCLE` block on the collar
      identity page naming the race, what it is strong against and what outmatches it; a
      `Matchup` row on the creature passport next to `Race`; and a live line in the
      diagnostic command that reads through the same path the damage calculation uses, so the
      number on screen cannot disagree with what the server is doing. The passport row is
      omitted entirely for a beast outside the circle rather than shown blank, so the page
      never advertises a mechanic that does not apply.
    - **Not scaled by rarity.** A rarer beast is already better on every other axis, and letting
      rarity reach these numbers would make the circle unreadable exactly where it matters
      most. `RaceAdvantagePct` and `RaceDisadvantagePct` are plain percentages with no
      rarity tier behind them.
  
  - **The aura casting animation is gone, and it was never per pet.** It came from which
    *skill* an affinity was bound to, not from the beast. `TameSkillAura.cast()` picks
    between two packets: `useMagic`, which broadcasts `MagicSkillUse` and makes the client
    play the caster's animation, and `MagicSkillLaunched` on its own, which says "this
    skill landed on these targets" so the effect animates with no cast to begin. The choice
    was gated on `skill.isMagic()`, on the assumption that the gesture is a consequence of
    the skill being magic-type. **It is not - it is a consequence of the packet.** So the
    gate left exactly the wrong affinities busy: Holy was bound to `Escape Shackle` (453),
    which carries no `isMagic` at all, as do `Double Shot` (19) and `Sonic Storm` (7), and
    all three looped the gesturing path. A beast of another affinity, or one dressed before
    that binding existed, never hit it - which is why it looked like it was per pet.
    `module.ini` asserted the opposite in as many words ("carries no isMagic -> client shows
    no cast animation"), and that claim is what produced the bug; it is corrected in place.
    The quiet path is now taken for **every** affinity, and `AffinitySilentMagic = FALSE`
    remains as the escape hatch back to the old behaviour. This is the one setting here that
    cannot be verified from the server side, since the animation is drawn by the client.
- **Wind and light now wear the requested looks.** `AffinitySkillWind = 19` (Double Shot)
    and `AffinitySkillHoly = 7` (Sonic Storm). Both are real weapon skills - `PhysicalDamage`
    and `EnergyDamage` - picked purely for how they look. That is safe because the rebuild
    drops every effect (a directly constructed `Skill` has an empty effect list by
    construction) and forces all costs to zero, so what reaches the client is an animation
    and nothing lands. Neither consumes MP and neither has a reuse delay in the loop;
    `AffinitySkillMs` alone sets the pace.
- **Awakening buttons are 100 wide, and the caption is padded to compensate.** `sek.cbui94`
    bakes its caption offset for a 140-wide button, so narrowing to 100 leaves the caption
    drawn 40px left of where it was - 20px of it on the left of centre. `<center>` and
    `align=center` cannot fix that, since they centre the *button* in the page rather than
    the *caption* in the button, and neither can move a caption the texture has already
    placed. The only remaining lever is the caption string itself, so
    `AWAKENING_CAPTION_PAD` prepends three spaces to every awakening caption to buy the 20px
    back. **Three is a guess and is documented as one** - the caption is drawn by the client
    and the font metrics live there, so this is the one number here that cannot be derived
    server-side. If the word reads right of centre, drop one; if left, add one. It is a
    single constant and nothing else depends on it. Both the first and second awakening
    buttons use the same pair, so `AWAKEN AGAIN` is covered by the same change.
- **Module author is now `HumblePie`** in `module.json`, replacing "Living World community".
    Display name and description are untouched.
- **Pet skill reach is addressed by granting longer-reaching techniques, not by widening
    existing ones.** Reach genuinely cannot be widened here: `SkillData` hands out one
    shared `Skill` instance per id, so raising `castRange` on it would change every creature
    in the game using that technique - the same shared-object hazard `CloneProbe` guards
    against for `CreatureTemplate`. A private rebuilt copy is not available either, because
    the rebuild `TameSkillAura` uses works precisely *because* a directly constructed `Skill`
    has an empty effect list, and a signature technique has to keep its effects to deal
    damage. So the lever is which technique is granted. `TameSkillPolicy.preferReach` drops
    anything shorter than `SignatureMinRange` (900, the stock bow range and roughly where a
    technique stops being a melee swipe) and then sorts the survivors by `castRange` and
    keeps the top `SignatureReachBand` (12) for the seeded roll. Taking only the single
    longest would hand every archer the same technique; taking the whole pool would leave
    the complaint where it started. A floor that empties a species' pool falls back to the
    unfiltered pool and logs it, because a tame that silently loses its signature is worse
    than one keeping a short-range technique. Deterministic per profile, so a beast keeps its
    technique for life. Only the SIGNATURE slot is affected; inherited techniques belong to
    the species and are left alone.

- **Capture difficulty is 10% normal / 0.5% raid, and the hardcoded 1/95 clamp is gone.**
    `TameMonster` clamped the computed chance to `Math.max(1.0, Math.min(95.0, chance))` with
    both bounds written in as literals, which quietly made the two documented settings
    unreachable: a raid configured below 1% was silently raised back to exactly 1% before the
    roll, and raising `ChanceCap` above 95 did nothing. `ChanceFloor` (default 0) and
    `ChanceCap` are now read from config. **This is what makes 0.5% real** - at the old floor
    of 1 the requested value would have been rounded up to 1% with nothing in the log to say
    so. The same method also hardcoded `0.4` and `2.0` rather than reading
    `WoundedHpWeight` and `LevelGapPenalty`, so changing either of those in module.ini had no
    effect at all; both now come from `TamingData`, whose getters already existed and were
    unused. No per-species `[Override.*]` sections exist, so these two globals govern every
    creature.
    - **The floor change is a behaviour cliff worth knowing about.** At 0.5% base with
      `LevelGapPenalty = 2.0`, a raid at or below the player's level rolls 0.5%, but a raid
      **one level above** computes to `0.5 - 2.0 = -1.5` and clamps to 0 - flatly impossible.
      Under the old floor of 1 that same raid sat at 1% and was always at least possible. That
      is the honest reading of "a raid one level above you is out of reach", but it is a hard
      edge rather than a gradient. If raids should stay merely rare instead of impossible above
      you, either raise `RaidChance` or drop `LevelGapPenalty`; both are one line.
- **Two affinity auras changed.** `AffinityEffectWind` is now `SEIZURE2`, the sibling of the
    `SEIZURE1` water already wears, so the two read as one family without being identical -
    and unlike `DOT_SOIL` on earth it is outside the `DOT_` family, so it should hold steady
    rather than pulse. `AffinityEffectHoly` is now `DANCE_ROOT` in place of `NONE`.
    **Neither is confirmed on screen**, and that is stated in the file next to each. Holy was
    `NONE` because `INVINCIBILITY` was tried there first and drew nothing at all; `DANCE_ROOT`
    is chosen on the same untested reasoning that already failed once - it is body-rooted
    rather than ground-anchored, so it should travel with the beast, but an enum name is not
    a rendering. `.tameaura DANCE_ROOT` and `.tameaura SEIZURE2` settle it without a reboot.
    The skill-cast layer is separate and untouched, so holy wears both its cast and this.

- **The two capture tiers no longer roll the same way.** An ordinary creature is now a flat
    `NormalChanceCap` - no wounded bonus raises it, no level gap lowers it, so the configured
    number is exactly the number that happens and no combination of conditions quietly moves
    it. A raid keeps both modifiers, because weakening one first and it outlevelling you are
    both things that should matter against a boss, but is clamped to its own `RaidChanceCap`
    so beating a raid down to a sliver never turns it into an ordinary capture. Net effect:
    **ordinary 10% flat, raid 0.5% rising to at most 1%.**
    This needed per-tier caps rather than one global one, so `TamingEntry.isRaid()` was added
    to hold the tier test the call site used to make with a bare string compare. `ChanceCap`
    is now read and never applied; it is kept in module.ini, documented as superseded, so an
    existing config does not fail to parse.
- **`WoundedHpWeight` and `LevelGapPenalty` now apply to raids only.** They were also being
    read by nothing - `TameMonster` hardcoded `0.4` and `2.0` - so before this they had never
    affected any roll at all, on either tier. They are not dead settings now, but they are
    narrower than they look: changing either does nothing to ordinary creatures.

- **900 is the hard ceiling on technique reach in this datapack, and it was measured rather
    than assumed.** `ReachProbe` reads the whole 95-skill player damage catalogue against real
    loaded data: the `castRange` spread is `-1`x11, `40`x24, `150`x2, `400`x6, `500`x8, `600`x8,
    `700`x1, `750`x5, `900`x30 - **and nothing at all reaches 1200 or 1500**. So "grant
    longer-reaching techniques" cannot deliver more than 900 however it is tuned, because 900
    is the furthest anything here throws. This is the honest limit of that approach and it is
    worth stating plainly: the feature makes an archer's signature consistent and long-ranged,
    not longer-ranged than before. `SignatureMinRange` above 900 would empty every pool and
    fall back to the warning path.
  - **The floor is applied to ARCHER only.** Applied to every role it is a bug, not a tuning
    choice: because 900 is the maximum, "prefer the longest reach" and "must reach at all"
    become the same instruction, and every role collapses onto the 900-range list. Measured
    before the fix, `WARRIOR` and `ARCHER` were drawing from an **identical** technique set -
    a melee pet shooting bows, because the floor had forced it there. `SignatureProbe` after
    the fix: ARCHER 6 distinct all at 900, MAGE/SUPPORT 35 distinct, WARRIOR 32 distinct and
    back on melee (`Triple Slash`, `Stunning Fist`), seed-stability held throughout.
  - **The band no longer truncates a tied pool.** With every candidate at the same
    `castRange` there is no reach to prefer, so sorting is a no-op and `subList(0, band)` would
    have cut the pool to an arbitrary slice in catalogue order while appearing to have chosen
    on distance. `hasSpread` gates the truncation on the candidates actually differing.
- **`ReachProbe` is the retained regression for this**, alongside `SignatureProbe`. Both must be
    run from the `game` directory: `SkillData` resolves `data/stats/skills` relative to the
    working directory, and run from the repository root both probes silently load **0** skills
    and report a full set of nulls and an empty catalogue - a green-looking failure that proves
    nothing. `SignatureProbe` in particular prints `range=[2147483647..0]` in that state.

### Auto-cast rotation, melee follow-through, technique cooldowns, and race class kits

A pass over how a summoned beast actually fights, after testing showed four separate
problems that all looked like "the pet is dumb". Every change here is module-side; no
core edit, no client file, and the whole module still compiles clean.

- **A tamed beast stopped attacking after two or three swings.** The cause is in the
  core's `SummonAI`, not here. Its attack loop is only re-armed by a ready-to-act
  notification that a melee swing schedules for itself, and `thinkAttack` returns
  *without scheduling the next one* whenever the beast has to step to its target
  (`maybeMoveToPawn`). Once that one notification is missed, the intention is still
  `ATTACK` but nothing drives it any more, so the beast stands there until the player
  presses attack again - which is what re-arms it. The server already has a fix for
  exactly this shape of problem: its phantom players re-assert the attack every tick
  instead of trusting the loop (`PhantomCombatActions.maintainAttack`).
  `TameAutoCast` now does the same on the ticker it already runs, in `maintainAttack`.
  It is deliberately conservative: it only ever acts while the beast has an actual AI
  attack target (an explicit stop clears that target, so this can never resurrect a
  fight the player called off), and it does nothing while the beast is casting,
  attacking or disabled, so it cannot interrupt one of the module's own casts.

- **A support technique landed on the monster.** `Summon.useMagic` resolves the real
  cast target from the skill's `TargetType`, and for the common `ONE`/`TARGET` kinds
  from `summon.getTarget()` - synchronously, before `useMagic` returns. Support used to
  be left alone on the theory that its `TargetType` picks the owner by itself, which is
  only true for `SELF`/`OWNER_PET`/`PARTY`: a plain `ONE` buff resolved from
  `getTarget()` instead, so a fighting beast buffed the mob it was hitting. A cast is
  now aimed at its intended target for the duration of the call and the beast's mark is
  restored afterwards, in a `try/finally` so a failed cast cannot leave the target
  pointing at its owner. The offensive target is left in place - it is the mark the
  beast is already fighting.

- **The cast order was "whatever lands first", which is the wrong question.** The
  priority is now a property of the technique's role: a heal first (and only when an
  ally is actually hurt), then damage, then debuffs, then everything else. A healing
  technique is aimed at whichever of the beast and its owner is hurt *worse by health
  fraction* - a 200-of-300 beast outranks a 900-of-1000 owner - and is not cast at all
  above `AutoCastHealPercent` (default 70). This is what stops a support beast wasting a
  heal on a full-health owner while its own HP drains, and from opening every fight with
  a debuff and leaving the damage skill on cooldown. The classification is one shared
  predicate (`isOffensive`/`isHealing`) so the target choice, priority, movement gate,
  cooldown and target-restore cannot disagree about which techniques are offensive.

- **Auto-cast now reads the whole deck, not the four bar buttons.** The native pet bar
  is bound to four parameter names because that is what the client's pet window offers,
  but the rotation had been reusing that same four-row list, so the advanced and
  awakening techniques were silently ignored once the four visible slots were full.
  `TameSkillBar.resolveAll` returns every usable row up to eight, and auto-cast uses it;
  the manual bar keeps its smaller, client-limited set.

- **A beast with plenty of MP leaned on one technique forever.** The minimum gap stops a
  tame casting *fast*, but not casting the *same* skill every gap, and monster and boss
  techniques almost never carry a reuse delay, so nothing did. `AutoCastSkillCooldownMs`
  (default 5000) puts a cooldown on each technique, so the beast works through its deck
  instead. **Healing is exempt**: an emergency heal is never held back by a rotation
  timer.

- **"Cast, step in, cast, step in".** From range a beast fired an offensive technique
  while walking to its target, which read as a stutter. With `AutoCastMeleeFirst` (on by
  default) an offensive technique waits until the beast has actually stopped at its
  target, so it closes and fights and a technique punctuates the swings rather than
  starting a stand-off. Support - a buff or a heal - is not gated on movement. The
  knob exists so a beast meant to fight at range can be configured that way, accepting
  the stutter back.

- **Races now give a class, not just a role.** `TameSkillPolicy.classAwakeningPool` used
  to draw from a role-only whitelist, so every beast of a role fielded the same buffs.
  It is now the role's defining technique (Heal for SUPPORT, Acumen for MAGE, Guidance
  for ARCHER, Might otherwise) added first and never shuffled, so no beast loses its
  defining technique to a dice roll, followed by a race-flavoured kit - two or three
  condition-free, weapon-independent Interlude techniques per race. Rarity still decides
  how many of the pool a beast awakens, so a race reads as a tendency rather than a
  fixed loadout. All ids are the same hand-whitelisted records the pool already used; no
  new skill ids were introduced.

- **Verified in a live test, and what is not.** After this pass the server owner confirmed
  on a live client: a heal lands on the hurt creature rather than on the mark; a melee
  beast closes in and does **not** cast while walking; a single technique spaces out per
  `AutoCastSkillCooldownMs` instead of repeating; and two same-role, different-race tames
  field different kits. The melee follow-through (`maintainAttack`) was confirmed earlier,
  before the rest of this pass. The module still compiles clean (`javac`, `exit 0`).
- **Not yet observed individually, same code path.** Two things ride the verified paths
  but were not called out on their own in the live test: that the advanced and awakening
  slots (rows 5-8) actually fire in a fight, above the four the manual bar shows, and that
  a heal is exempt from `AutoCastSkillCooldownMs` (the spacing check used a damage
  technique). Both are exercised by the same loop that passed, so they are suspected-work,
  not suspected-broken.
- **Remaining limits, none of them regressions.** Class kits apply to **newly tamed**
  beasts only - the deck is built at capture, so existing tames keep their old kits until
  reforged (the one exception remains `refreshSignature`, which rewrites `SIGNATURE_1`
  only). Race kits cover the thirteen-race circle; player-race monsters (`HUMAN`, `ORC`,
  `ELF`, `DARK_ELF`, `DWARF`) and `SIEGE_WEAPON` fall through to the role technique alone.
  If `isMoving()` flaps at melee range on a given datapack, `AutoCastMeleeFirst = false` is
  the escape hatch. Out-of-range `AutoCastHealPercent` and `AutoCastSkillCooldownMs`
  values keep their defaults rather than clamping, on purpose, so a typo cannot make
  every beast immortal or silent.

## Final note

This module is intentionally built around the limits of the original Interlude client. When a feature cannot be expressed through an existing server hook or a client-known packet field, the safe behavior is to keep the feature server-side, provide a clear fallback, and document the limitation rather than force a fragile client workaround.
