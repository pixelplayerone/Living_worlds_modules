# Recipe Book

A Community Board tab (Alt+B) that turns the recipe data into a progression guide: what can I craft, what does it
need, what do I already hold, and where does each missing piece come from. Browse-only: it changes nothing in the
world and adds no items, skills or NPCs.

## What goes in the book

Only recipes a player can actually obtain. A recipe is listed when its recipe scroll is

- dropped or spoiled by at least one monster in the NPC data, or
- rewarded by a quest.

A recipe with neither is left out, whatever its success rate. This is a source test, not a "100%" test: most 100%
recipes do drop from monsters and stay in. On the stock datapack that is about 549 of 871 recipes; the server log
prints the exact numbers when the book is first opened.

## Pages

- **Front page** - search box, plus a category (Weapons, Armor, Jewelry, Other) by grade grid of recipe counts.
- **List** - recipes of one category and grade, 10 per page, with craft level, success rate, and where the scroll
  comes from (Drop, Quest or both). A green star marks recipes the player already knows.
- **Recipe** - product, craft level, dwarven/common, success rate and MP; where to get the scroll (quest and the
  top monster sources); every ingredient with how many are needed against how many the player holds
  (inventory + warehouse, red when short, green when covered); Add to goals.
- **Ingredient / item** - which monsters drop or spoil it (level, chance, type; clicking a monster marks it on the
  minimap), which quests reward it, and which recipes in the book use it.
- **Search** - one box matches recipe, product, scroll and ingredient names.
- **My Goals** - up to 15 pinned recipes, a "Recipes to find" list (scrolls neither held nor learned), and one
  combined shopping list of every ingredient: need, have, missing, most-missing first.

Goals are stored per character in the player variable `RecipeBookGoals`.

## Requirements

- The core change "Modules can add Community Board pages and navigation tabs" (`registerBoard`,
  `registerBoardTab`, and the `%moduleTabs%` marker in `data/html/CommunityBoard/Custom/navigation.html`). Rebuild
  the server jar and copy the updated `navigation.html` into the server's `data/html/CommunityBoard/Custom/`.
- The Community Board must be enabled (`EnableCommunityBoard = True` in General.ini).

## Install

Delete any old `modules/recipe-book` folder, copy this folder to the server's `modules/` directory, set
`Enabled = True` in `config/module.ini`, restart. `.recipebook` in chat also opens the book, and `.clearmark` removes the marker a monster trace left on the map (the front page has a Clear map marker link too). Tracing a new monster replaces the previous marker.

## Configuration (`config/module.ini`)

| Key | Default | Meaning |
|---|---|---|
| `Enabled` | `False` | Master switch. Off registers nothing; the server is stock. |
| `TabLabel` | `Recipe Book` | Text on the navigation button. |

## How it knows things

- Recipes, items and monster drops are read from the loaded datapack the first time anyone opens the book
  (index built once, lazily), so edits to the XML files show up after a restart.
- Chances shown are the base drop chance times the server's rates (death, spoil and raid multipliers and per-item
  overrides, capped at 100%). Premium and buff bonuses are not included.
- Quest rewards are not in a data file, they live in the quest scripts. `scripts/QuestSources.java` is generated
  by `tools/gen_quest_sources.py` from the quests' reward calls (`giveItems`, `rewardItems`, `addItem`,
  `new ItemHolder`). Regenerate it if you add or change quests:
  `python3 tools/gen_quest_sources.py <server>/data > scripts/QuestSources.java`

## Known limits (first version)

- Merchant sources are not indexed. An ingredient nobody drops says "look for it at a merchant, or craft it".
- Any monster template with the drop counts as a source, even one that never spawns; the minimap marker says so
  when it cannot find a spawn.
- Quest sources only name the quest, not the NPC to talk to.
- Scripts are compiled by the server at Java 8 source level, so no records, `var` or pattern matching in here.
