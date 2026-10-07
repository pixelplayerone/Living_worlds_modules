# Quest Book

A Community Board tab (Alt+B) that lists every quest on the server, so you can find the next one worth doing.
Browse-only: it changes nothing in the world.

## What it shows
- **Browse by minimum level** (1-19, 20-29 ... 70-85), or **For my level** for quests in the six levels below you
  up to your level.
- **Search** by name or by number (`Q663` or `663`).
- Each quest page shows the minimum level, whether it repeats, whether you have it **In progress** or **Done**,
  **who gives it** (click the name to mark that NPC on your minimap), the **monsters involved** (also clickable),
  and the **rewards**.
- **Active** lists the quests you are doing right now. **Clear map marker** removes the marker a click left.
- `.questbook` opens the book from chat.

## Install
1. Copy the `quest-book` folder into `game/modules/`.
2. Set `Enabled = True` in `config/module.ini`.
3. Restart.

## Configuration (`config/module.ini`)
- `Enabled` - master switch. Off registers nothing and the server is stock.
- `TabLabel` - text on the navigation button (default `Quest Book`).

## How accurate is it
The data is read from the quest scripts by `tools/gen_quest_data.py`, not from the client's quest journal. Levels
come from the first level check in the script, rewards are the items the script hands out (quest-only items left
out), and a quest paying one of several choices lists them all. A few quests do not state a level and sit under
"Level not stated". Treat it as a guide, not gospel. Regenerate after quest changes:
`python3 tools/gen_quest_data.py <game>/data > scripts/QuestData.java`.

## Remove
Disable it, stop the server, delete the folder. No ids or database tables.

## License
GNU General Public License v3.0.
