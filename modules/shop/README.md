# Pocket Shop

A shop that opens from an item in every player's bag, so you can sell your own items (custom
ones included) without placing an NPC. Other modules can link to it or list their items in it.
Stock and prices are set in `config/module.ini`.

A generated shop page in front of the core's own shop window, for L2JMobius
Interlude and servers built on the same module framework.

Point it at a list of item ids. It draws a page with their real icons, names and
prices, and hands the player to the native shop window to buy. If you get the
optional multisell list wrong, the page falls back to its own quantity buttons and
still sells.

The fallback purchase path is transactional: it charges Adena first, validates
the complete requested inventory capacity and weight, then creates the items. If
the preflight or delivery fails, both the money and any partial delivery are
rolled back. This is important on servers where a non-stackable quantity request
creates separate item objects; checking only the last returned object can leave
most of a failed purchase free.

- No core edits, no client edits
- No database tables
- Only stock items in the catalogue
- One reserved id: item `40000`, the paper that opens the shop. Nothing else.
- Everything configurable from `config/module.ini`

**Status:** tested on one server, the author's: an Interlude-based offline server
built on L2JMobius. It has not been tried anywhere else, so treat a different build
as untested and read the boot log after the first restart.

---

## Where this came from

This did not start as a shop module. It started as one corner of the **Beast Collar
Taming** module: the reagent store, where a player buys the two items that bind a
tame, a Broken Beast Flute for an ordinary creature and an Ancient Beast Bugle for
a raid creature.

Putting a shop inside a module with no core edits and no client edits turned out
to be a string of dead ends, and every one of them is why this module looks the way
it does:

- **Module HTML files are never loaded.** A module can ship `.htm` files and the
  server will not read them, so the page is generated in code.
- **There is no hook for clicking an NPC**, and no way to add a chat command. The
  shop is opened by double-clicking an item instead, which is what the paper is.
- **A normal buy list draws blank rows** for custom items, because its packet has no
  display id. A multisell window does send it, so the items show their real icons.
- **The multisell window refuses ordinary players** unless its list contains
  `<npc>-1</npc>`. It opens for a GM and fails for everyone else, which is a
  maddening thing to debug.
- **The client's HTML engine is strange.** Some markup crashes it, and some of it
  draws wrong without any error, including a button that tears in two when it is
  too wide.

When the reagent store finally worked, we pulled it out of the taming module and
made it its own module, with no taming code left in it. Any server can now use it
for any items. The two example rows replace the flute and the bugle, and
everything we learned the hard way is written down below, in the section on what
the HTML engine will not do and in the comments in `ShopFrontPage.java`.

If you run the Beast Collar Taming module as well, the two modules do not clash:
the paper here is item `40000`, and the taming module keeps its own ids in the
`9300-9399` block.

---

## Install

1. Copy the `shop` folder into `game\modules\`, so you end up with
   `game\modules\shop\`. The folder name must stay `shop`: the module finds its own
   multisell list and item definitions relative to it.
2. Restart.
3. Double-click the paper in your bag. That is the shop.

Every player is given the paper automatically, so there is nothing to hand out.
There is no database step and no core edit. The two example rows are live from the
first restart, so you can open the shop and buy something before changing
anything.

At boot, expect one harmless line from the XML parser naming `950000.xml`:

```
Error: URI=file:/.../modules/shop/data/multisell/950000.xml Line=NN: cvc-elt.1.a: Cannot find the declaration of element 'list'.
```

The server validates multisell files, and this one has no schema reference on
purpose. The list still loads, and this module's own startup line
(`multisell 950000 checked, 2 row(s) ...`) confirms it. The server prints the same
message for some of its own files.

### If it refuses to enable

`reserves` in `module.json` accepts **only `items`, `skills` and `npcs`**. A
`multisell` entry there is rejected at boot with:

```
Refused 'shop': reserve type 'multisell' has no id space;
only items, skills, and npcs may be reserved.
```

The multisell is loaded through `resources`, which is a separate key and does
support `multisell`. Registering it as a *resource* is correct; reserving an id
range for it is not something the framework has an id space for.

### If the log says "Skipping 'shop': no module.json"

The server found a folder called `shop` but there is no `module.json` directly
inside it. The cause is almost always one folder too many, from unzipping into a
folder that was already called `shop`:

```
game\modules\shop\shop\module.json     <- wrong, one level too deep
game\modules\shop\module.json           <- right
```

Move the inner `shop` folder up so that `module.json` sits directly inside
`game\modules\shop\`, then restart.

### If the paper's item id is already taken

The paper is item `40000`. If another module or datapack already uses that id,
pick a free one and change it in three places so they agree: the `id` in
`data/items/40000.xml`, the `reserves` range in `module.json`, and `OpenItemId` in
`config/module.ini`. To check an id is free, search your datapack, for example in
PowerShell from the `game` folder:

```
Select-String -Path data\stats\items\*.xml -Pattern 'id="40000"'
```

Likewise, pick a multisell id that is not already in `game\data\multisell\`.
`950000` is chosen only because the author's server had it free.

---

## Opening the shop

**You cannot open this shop by typing in chat.** This is worth understanding before
you try, because it is not obvious and the error message is nothing.

What the client actually does with a chat command:

| You type | Packet | Server looks up | Lookup key |
|---|---|---|---|
| `.foo` | `Say2` | nothing, it is plain chat | none |
| a client command | `BypassUserCmd` | `UserCommandHandler` | **an integer** |
| clicking an HTML link | `RequestBypassToServer` | `BypassHandler` | the link text |

A typed command has to arrive as an **integer** that the client already knows,
because the client decides which commands exist. A module cannot invent a new one:
`ModuleHandlers` offers `registerVoicedCommand`, `registerAdminCommand`,
`registerBypass`, `registerItem`, `registerEffect` and `registerTarget`, and none of
them is a plain chat command. `BypassHandler` is reachable only from NPC dialogs and
HTML links, never from typing.

So the shop is opened one of two ways. Either is fine, and they do not interfere.

### A. The paper (the default, and all you need)

This module ships its own opener: **item `40000`**, a piece of paper, defined in
`data/items/40000.xml`. Double-click it and the shop opens. `OpenItemId = 40000` is
already set, so this works on the first restart.

**Every player is given the paper.** At each login, and when a new character is
created, anyone who does not already carry one gets one. A player who destroys
theirs gets another at the next login. `GrantOpenItem = true` in `config/module.ini`
controls this; set it to `false` to hand the paper out yourself (an admin command, a
reward, a vendor). It has no effect when `OpenItemId = 0`.

How the paper is wired is worth knowing before you change it. `ItemHandler` keys
handlers by **name**, not by item id:

```
registerHandler(h) -> _datatable.put(h.getClass().getSimpleName(), h)
getHandler(item)   -> _datatable.get(item.getHandlerName())
```

So the last line of the item definition does not contain an id. It contains the
class name:

```xml
<set name="handler" val="ShopOpenItem" />
```

Rename `ShopOpenItem` and that line must change with it, or the paper silently stops
working. The core logs the class name it could not find, which is the thing to look
for if the paper ever does nothing.

To use a different item instead, change `OpenItemId` and point that item's XML at
`ShopOpenItem`. To remove this route entirely, set `OpenItemId = 0`; the NPC link
below keeps working, and the two are independent.

**The paper's name and icon are borrowed.** There is no server-side name override.
`AbstractItemPacket` writes the display id and no name string, so the client takes
the icon *and* the name from the display id. The item is defined as `Shop
Catalogue`, but a player sees whatever item `1695` is called in their bag. Pick a
different `displayId` if the stock name does not suit you.

### B. A link in an NPC dialog

Add this to any NPC's HTML in `game\data\html\`:

```html
<button value="OPEN SHOP" action="bypass -h shop" width=100 height=22
        back=sek.cbui94 fore=sek.cbui92><font color=D7DCE2>Open the shop window</font><br>Then a note on the next line
```

Note it is `bypass -h shop` with no dot. `Command = shop` in `module.ini` is the name
this link matches. It is **not** a chat command.

If you would rather skip the custom page entirely, a plain multisell link in an NPC
dialog needs no module code at all:

```html
<button value="OPEN SHOP" action="bypass -h Multisell 950000" width=100 height=22
        back=sek.cbui94 fore=sek.cbui92>
```

### Reloading

Module code is compiled and loaded **once, at boot**. There is no hot reload.
`ScriptManager.reloadAllScripts()` exists but only reloads *quest* scripts, and
`ModuleManager` exposes only `enableModules()` and `getHandles()`. Editing a `.java`
file needs a restart. Editing `config/module.ini` or the multisell XML also needs a
restart, because the multisell list in particular is read during the data phase.

---

## Configuring

Everything lives in `config/module.ini`. The file is heavily commented; this is the
short version.

| Key | What it does |
|---|---|
| `Enabled` | Turns the module off without removing it. |
| `Items` | The catalogue. See below. |
| `Command` | The name the NPC link matches (`bypass -h shop`). Not a chat command. |
| `OpenItemId` | The item that opens the shop. `0` removes the item route. |
| `GrantOpenItem` | Give that item to every player at login and character creation. |
| `MultisellId` | The native window's list id. `0` means the page sells by itself. |
| `UseWindow` | Whether to use the native window at all. |
| `PageTitle`, `PageIntro` | The heading and the line under it. Shipped as `EXAMPLE SHOP`. |
| `WindowNote`, `FallbackNote`, `WindowMissingNotice` | The short lines the page shows in each state. |
| `TableWidth`, `IconSize`, `ShowIcons`, `ShowPrices` | Layout. |
| `Quantities` | The quantity buttons of the fallback, for example `1,10,100`. |
| `TitleColor`, `LabelColor`, `NoteColor`, `PriceColor`, `NoticeColor` | Six hex digits each, validated on read. |

There is no `ButtonTextColor`, because a button caption cannot be coloured. There is
also **no background colour setting, on purpose**; see the HTML engine section.

### The catalogue

One line:

```
Items = <itemId>;<note>;<price>|<itemId>;<note>;<price>|...
```

| Field | Required | Meaning |
|---|---|---|
| `itemId` | yes | A stock item id. Name and icon are read from the item itself. |
| `note` | no | One short line under the name. Cannot contain `;` or `\|`. |
| `price` | no | Adena per unit. Empty or `0` draws no price at all. |

`|` separates rows and `;` separates fields. Leave a field empty to skip it:
`1146;;8000` is item 1146, no note, 8000 Adena.

A price of `0` means *unpriced*, which is a real state: an item whose price is set by
a quest or a merchant script. The page draws no number rather than drawing `0`.

Rows are validated at startup. A bad id, a negative price, or an item this server
does not have is reported by name in the log and dropped. Whatever survives is what
the page shows.

The module **never refuses to enable**, and that is deliberate. A fresh install has
to look like a working shop that is waiting for stock, not like a broken one, and an
empty `Items` key is a state somebody may deliberately be in. The page says so itself
and names the key to fill in. The log distinguishes why it is empty:

```
shop: Items is empty, so the shop will open and sell nothing until you fill it in.
```

```
shop: Items is set but nothing in it could be read, so the shop will sell nothing. Expected "1146;note;8000|1147;note;8000".
```

The first is a shop somebody has deliberately blanked. The second is a shop
somebody filled in and got wrong, and it deserves your attention.

> Verify your item ids in `game\data\stats\items\`. `4037` is *Coin of Luck*, not
> Soft Leather, which is the sort of thing worth checking rather than trusting.

### The two rows this ships with

`Items` is not empty. It holds two example rows, so a fresh install has something to
look at and something you can click:

```
Items = 1;Example row - change the id and this text;1000|1146;The note under the name - keep it to one line;25000
```

| Part | What it does |
|---|---|
| `1` | A Short Sword. The module asks the item data for the name and icon, so the page shows *Short Sword*; you never typed that. |
| `Example row - change the id and this text` | The grey line under the name. One line, no `;` and no `\|`. |
| `1000` | Adena per unit, right-aligned, with *Adena* under it. |
| `\|` | Separates the two rows. |
| `1146` | A Squire's Shirt, a different item **type** on purpose, so the page shows two visibly different icons. |
| `25000` | A deliberately round, deliberately wrong price. |

The point to take away: **a shop price has nothing to do with the item's own
price.** Item `1`'s own price is 768 and `1146`'s is 26. What a shop asks is whatever
you type here. Both are real starter items on any Interlude datapack, so the example
works out of the box. Replace the whole line with your own stock, or blank the key
and the shop opens and explains itself.

### The original use: two reagents

The shop this came from sold two items, listed on one line:

```
Items = 9300;Binds an ordinary beast.;10000|9301;Binds a raid.;250000
```

A row per item (a 32x32 icon, the name, your note in grey underneath, the price
right-aligned with "Adena" beneath it), a spacer row between items, then the OPEN
SHOP button with its explanation beside it and the window note under that.

The same two items then have to appear in the multisell list, or the button opens a
window that does not contain what the page promised. Prices in the two places must
match, or the module logs a drift warning at startup naming the row:

```xml
<item>
	<ingredient count="10000" id="57" />
	<production count="1" id="9300" />
</item>
<item>
	<ingredient count="250000" id="57" />
	<production count="1" id="9301" />
</item>
```

Copy the shape, not the ids: use items that exist on your server.

### Costume items

Short version, because this is the part people expect to be harder than it is:
**this shop has no concept of a costume.** It lists item ids. A costume goes in the
same line as a potion and nothing special is needed here.

There is also **no costume flag in the item data**, so do not go looking for one.
What decides whether an item *looks* like a costume is `displayId`:

> The client draws an item's icon and its name out of its own string tables, keyed by
> `displayId`. The server cannot override either one. So an item defined as whatever
> you like still reads on screen as whatever its `displayId` says it is.

The flute is the clearest example. In the item data it is item `9300`, named *Broken
Beast Flute*, but that name is the client string for `displayId 1770`. The server
never told the client to call it a flute; the display id did. Borrow a display id and
your item becomes that thing on screen. It is the same reason this module's own
paper, item `40000`, comes out as a plain sheet of paper: it is `displayId 1695`.

So, practically:

- To sell a costume, list it like anything else. Its appearance is decided by the
  time it reaches this module.
- `is_tradable` is the item's business, not the shop's.
- If the id does not exist on your server, the row is dropped **by name** at startup,
  so a typo produces a missing row and a log line naming the id, not a broken page.

---

## The multisell list

The native shop window is optional. With `MultisellId = 0` the page sells by itself
and everything works. The window is an upgrade, not a dependency. To use it, two
things must agree.

### 1. The file name is the list id

`data/multisell/950000.xml` **is** list `950000`. The core reads the id with
`Integer.parseInt(fileName.replaceAll(".xml", ""))`, so renaming the file renames the
list. `MultisellId = 950000` in `module.ini` must match the file name exactly.

### 2. The `-1` sentinel is not optional

```xml
<list>
  <npcs>
    <npc>-1</npc>
  </npcs>
  ...
</list>
```

`MultisellData.separateAndSend` refuses to send a list to a player unless
`ListContainer.isNpcAllowed` says otherwise:

```java
if (!list.isNpcAllowed(-1) && (npc == null || !list.isNpcAllowed(npc.getId()))) {
    if (player.isGM()) { player.sendMessage("... only gm are allowed ..."); }
    else { return; }                    // ordinary player stops here
}
```

`isNpcAllowed(-1)` is the first test and is a literal `contains(-1)`, giving three
states:

| `<npcs>` block | Result |
|---|---|
| absent | allowed set is null, so GM only |
| `<npc>SOMEID</npc>` | only that NPC instance |
| `<npc>-1</npc>` | first test passes, the block is skipped, the list goes to anyone |

There is no NPC behind this shop. It is opened from an item or an HTML link, so `npc`
is always null and only the `-1` branch can let the list through.

The GM branch does not stop the send either; it writes a message and falls through.
So "only gm are allowed" was never a permission, just the branch left standing after
the NPC test failed.

`MultiSellChoose` repeats the identical test **at purchase time**. So `-1` is what lets
a purchase *complete*, not merely the window open. Getting this wrong gives the worst
symptom: a window that opens and then refuses every purchase.

### Prices live in two places

The prices in `module.ini` charge the page's own buttons. The prices in the XML charge
the window. The module compares them at startup and logs a warning naming any row
that disagrees. They are separate because a multisell row's cost has to be expressed
as an ingredient, and the ingredient is an item id. **Keep them together**: a player
pays whichever path is cheaper, and that is a revenue bug nobody notices until they
notice.

---

## What this HTML engine will not do

This is the section to read before you change any markup. Every item here was found
the hard way, on a live client, and two of them **crash the client** rather than
merely looking wrong. The engine does not validate and does not report malformed
markup. It renders whatever you hand it and then sometimes dies.

### Crashes the client

**`bgcolor`.** A coloured panel was tried, with `bgcolor` on the table and on the
header row, using a colour measured from the Adena icon. The client crashed. Removed.
Do not add a background colour back to any page.

**A `<tr>` inside a `<td>`.** Wrapping rows in a `<tr><td>` pair so they sit in a cell
is malformed nesting. The client crashed. Rows must be **direct children** of the
table.

Both were shipped together by accident, which cost a debugging cycle each, because
changing several things at once means you cannot tell which one the client objected
to.

### Cannot be made to work

**Tiling images into a background.** The plan before `bgcolor` was to tile 1x1 Adena
swatches across the panel. It cannot be done: the engine will not butt images
together with no pixel of gap, and *any* gap draws a visible grid across the whole
panel. A coloured or textured panel background is not reachable from a module at all;
the client's window background is the background.

**Custom icons.** `<img src="Icon.something">` works, and the client resolves it
against `systextures\Icon.utx`, a texture package inside the client. The server can
therefore only ever *name* a texture the client already stocks:

- no server packet carries image bytes except `PledgeCrest` and `AllyCrest`
- `ItemTemplate` stores an icon *name*, never pixels
- `ModuleResourceType` has no crest entry, so a module cannot supply crest art through
  the framework either
- `displayId` selects one stock client identity; there is no crop, offset, scale or
  tile geometry anywhere in the item packets

You can draw a stock icon at any size. You cannot draw your own.

### Draws something wrong

The reliable way to get this right is not to reason about it. The collar passport
pages in the original taming module were the one piece of button markup a player had
confirmed renders correctly on this client, and this module's markup follows them. If
something draws wrong, compare against a known-good page rather than inventing an
explanation; the explanations are easy to get confidently wrong.

What is known, and observed:

**A button wider than about 100px tears into what looks like two buttons.** At
`width=140` the client drew a 100px body and a separate 12px square at the far end,
with the caption centred across the whole 140. That is why the shop button is 100
wide.

**Two captions on one button.** Do not put a caption in `value` *and* a second one in
a `<font>`. Both get drawn and they land on top of each other. Text may follow the
tag, but as an explanation, not as a second caption.

**`</button>` puts a black square under the button.** Not a typo in the samples here.
Buttons in this module are deliberately left unclosed.

**A caption too long for the width runs out past the border.** The button texture is
not clipped or wrapped; a caption that does not fit is drawn continuing past the edge.
The captions here are short by construction: one short phrase for the shop button and
one to three digits for the quantity buttons.

**Long explanatory lines run out past the edge of the dialog.** Keep them to about
one short sentence.

**Rows with no spacer between them run together into one block.** A
`<tr><td height=10 colspan=3></td></tr>` between items is what separates them.

**`valign` is untested.** It is not used anywhere in this module. Do not assume.

### The button recipe

This module emits exactly this shape:

```html
<br><button value="OPEN SHOP" action="bypass -h your_command" width=100 height=22
        back=sek.cbui94 fore=sek.cbui92><font color=D7DCE2>Open the shop window</font><br>Then a note on the next line
```

- `width=100 height=22`
- Caption in `value`, **quoted**; `value="OPEN SHOP"` may contain a space
- **No closing tag**
- `back` / `fore` unquoted
- A `<font color=D7DCE2>` label straight after the tag, then a `<br>`

Because the button is never closed, the `<font>` after it is a **sibling**, not a
child. It is laid out on the same line, to the right of the button:

```
  [OPEN SHOP] Open the shop window
  The shop window takes the quantity and the Adena itself.
```

---

## Changing the markup

**Change one thing per restart.**

This is the single most useful piece of advice here. A client crash gives you no
stack trace, no log line and no error, just a dead client. If you change four things
and restart, and it crashes, you have four suspects and no information. If you change
one, you have an answer. The same applies to visual faults: a page that "looks wrong"
is usually several faults stacked, and fixing them in one pass teaches you nothing
about which was which.

The restart cost is what makes this annoying, and it cannot be avoided: module
classes are compiled in memory at boot and there is no reload API.

### Verifying without a client

You can check the structure before a player ever sees it. Generate the page and run a
tag-balance check over it:

- `<br>` and `<img>` are void; they have no closing tag
- `<button>` is deliberately never closed here, so treat it as self-terminating, or
  the check reports every button as unbalanced
- every other tag must nest and close in order
- every `<tr>` must be a direct child of a `<table>`
- a `<td>` must not contain a `<tr>`

This tells you whether the page is well-formed, which is where both crashes above
started. It will not tell you whether the client *likes* it. Only a client can.

---

## Troubleshooting

**The window will not open for players, but it opened for me.**
Almost certainly a missing `<npc>-1</npc>`. GM bypasses the check. The boot log names
the problem explicitly.

**The window opens but every purchase is refused.**
Same cause. `MultiSellChoose` re-tests at purchase time.

**The window is empty.**
The list has no `<item>` rows, or its file name does not match `MultisellId`.

**A row is missing from the window.**
The core drops a row whose `<production>` item does not exist on the server.

**Nothing opens the shop.**
Expected if nothing references it. The module can be running perfectly with a clean
boot log and still be unreachable. Check **Opening the shop** above, and check that
`OpenItemId` is not `0`.

**A click on the paper does nothing.**
The `val` in the item definition is not `ShopOpenItem`. The `UseItem` warning names
the handler it looked for.

**Players are not getting the paper.**
Check `GrantOpenItem = true` and `OpenItemId` is above `0`. If the item is not
defined, the boot log has one warning saying so. A player with a full bag is skipped
and logged by name; the next login tries again.

**The log says `Item item_id=N not known` at login.**
Players still carry an item the server no longer defines, usually an old paper after
you changed `OpenItemId`. It is a harmless warning and the row is skipped. To clear
it, stop the server, back up the database, and delete those rows from the `items`
table.

**The module did not appear in the boot log.**
It was refused, or skipped. The reason is in the log: usually the nested-folder
mistake above, or an `Items` line that parsed to nothing.

**The client crashes when the page opens.**
Read *What this HTML engine will not do*, and check you have not nested a `<tr>`
inside a `<td>` or added a `bgcolor`.

---

## Files

| Path | Purpose |
|---|---|
| `module.json` | Manifest. Entry point, item and multisell resource roots, the one reserved item id. |
| `config/module.ini` | Everything configurable. |
| `data/items/40000.xml` | The paper. Opens the shop when double-clicked. |
| `data/multisell/950000.xml` | The sample list. The file name is the id. |
| `scripts/shop/ShopFrontModule.java` | Entry point. Reads config, runs the probe, registers handlers, hands out the paper. |
| `scripts/shop/ShopCatalogue.java` | Config parsing and validation. |
| `scripts/shop/MultisellProbe.java` | Startup check on the list; explains itself when it fails. |
| `scripts/shop/ShopFrontPage.java` | The page, the button recipe, the purchase path. |
| `scripts/shop/ShopOpenItem.java` | The open-from-an-item handler. |

---

## Licence

Do what you like with it. If you improve the markup, please leave a note in
`ShopFrontPage.java` saying what you changed and what you observed; the comments in
there are the only record of why several of those lines look the way they do.
