/*
 * TameReagentShop.java
 *
 * The reagent store: a generated page of buttons, opened by double-clicking the
 * catalogue item in the player's bag or by .tamestore.
 *
 * REACHABLE WITHOUT A PET MANAGER
 *
 * This started as links added to the eleven Pet Manager dialogues, which does not
 * work and cannot be made to work. The module framework registers an html resource
 * root, but only five loaders ever read it - ItemData, SkillData, MultisellData,
 * NpcData and SpawnData. HtmCache is not one of them. A module file would be cached
 * under its own path relative to DATAPACK_ROOT, something like
 * modules/taming/data/html/petmanager/30731.htm, while a dialogue asks for
 * petmanager/30731.htm. The keys can never match, so those overrides were never
 * served.
 *
 * There is also no hook for an NPC being clicked or talked to. ModuleHandlers offers
 * voiced commands, admin commands, bypass, item, effect and target - nothing that
 * fires on interaction - so an NPC cannot be repurposed as a shopkeeper either.
 *
 * What does work is what the collar passport already does: build the page in code
 * and send it with NpcHtmlMessage, with bypass links the handler picks up.
 *
 * THE MULTISELL WINDOW, AND WHY IT NEEDED A SENTINEL TO EXIST
 *
 * This started with no window at all, and the reason is worth keeping because it
 * looks like a permission and is not one. MultisellData.separateAndSend refuses to
 * send a list to an ordinary player unless the list says who may open it:
 *
 *     if (!list.isNpcAllowed(-1) && (npc == null || !list.isNpcAllowed(npc.getId()))) {
 *         if (player.isGM()) { player.sendMessage("... only gm are allowed ..."); }
 *         else { LOGGER.warning(...); return; }
 *     }
 *
 * The GM branch writes its message and then FALLS THROUGH to send the list, so a GM
 * always gets the window and an ordinary player never does. "Only GM are allowed"
 * was never a permission: it was the only branch left after the NPC test failed. A
 * list with no <npcs> block has a null _npcsAllowed set, so isNpcAllowed is false
 * for every id including the -1 that means "no NPC", and the test can never be
 * satisfied from an item handler.
 *
 * This module has no shopkeeper NPC to name, and adding one is not free: making an
 * NPC walk-up-and-clickable needs the page written to data/html/merchant, which is
 * outside this module and is not done here. The way out was the other branch of the
 * same test. isNpcAllowed(-1) is checked FIRST, and -1 is a literal set entry, so
 * the list ships
 *
 *     <npcs><npc>-1</npc></npcs>
 *
 * and the whole block is skipped for anyone. MultiSellChoose repeats the identical
 * test when the player buys and skips its own npc / objectId / instance /
 * 250-radius checks the same way, so a purchase completes and not merely the
 * window opening - which matters, because a window that opens and then refuses
 * every purchase would look like a completely different bug. It only works because
 * PreparedListContainer copies the _npcsAllowed set by reference instead of
 * rebuilding it.
 *
 * So the page keeps its own buttons AND the window is now offered as well. The
 * buttons are still the reliable surface: a button is text, and text renders. The
 * window is the one with icons the client draws itself and a quantity selector.
 * Neither is a downgrade of the other, which is why both stayed.
 *
 * STATUS: the mechanism above is confirmed by reading the core, but <npc>-1</npc> is
 * the first use of that sentinel in this server - none of the 90 stock lists use a
 * negative npc id. It is unverified until a normal player completes a real purchase
 * through it, and it is labelled that way in config/module.ini and in the list.
 *
 * WHY THE PAGE IS A TABLE
 *
 * The first version put bare <button> tags straight into <center>. The client draws
 * the L2UI_ch3.smallbutton2 texture as a fixed nine-slice, so stretching it to
 * width=150 tore the button in half, and text with no table to sit in was laid out
 * against the window edge rather than inside it. Both complaints were layout, not
 * data. This uses the same table-and-grid shape as the collar passport pages, which
 * are known to draw correctly, with short labels sized for the real texture.
 */
package taming;

import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.MultisellData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.handler.IBypassHandler;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

public class TameReagentShop implements IBypassHandler
{
	private static final String[] COMMANDS =
	{
			"tamestore",
			"tame_buy_reagent",
			"tame_reagent_window"
		};

	/**
	 * Quantity buttons per reagent, drawn only when the window is not carrying the
	 * shop.
	 *
	 * <p>They stand in for the multisell's quantity selector. Labels are kept to
	 * "1", "10" and "100" because anything longer does not fit the button texture
	 * without being clipped.
	 */
	private static final int[] QUANTITIES =
	{
			1, 10, 100
	};

	/**
	 * Buttons here are written the way the collar passport pages write them, because
	 * those pages are the only button markup in this module a player has confirmed
	 * renders correctly:
	 *
	 * <pre>
	 * &lt;button value=X action="bypass -h ..." width=100 height=22
	 *         back=sek.cbui94 fore=sek.cbui92&gt;
	 * </pre>
	 *
	 * <p>So: 100 by 22, no caption text after the tag, and no closing tag. The page
	 * puts its line break straight after the tag, as the passport pages do after
	 * REMOVE and the awaken-path buttons.
	 *
	 * <p>The width is the one that mattered. Asked for 140, the client drew a body of
	 * about 100 pixels and a separate 12 pixel square at the far end, with the label
	 * centred across the whole 140. A wider box tore into what looked like two
	 * buttons; closing the tag put a black square under it.
	 */
	private static final String OPEN_SHOP_BUTTON = "<button value=\"OPEN SHOP\" action=\"bypass -h tame_reagent_window\" width=100 height=22 back=sek.cbui94 fore=sek.cbui92>";

	/**
	 * Adena, so the store can say what a price is a price in without hardcoding a
	 * texture name. Resolved from the item rather than written as a literal, because
	 * a literal here would be one more thing that can be wrong and cannot be checked.
	 *
	 * <p>Looked up per call rather than cached in a static, deliberately. A static
	 * field would initialise at class load, and this class is loaded when the module
	 * registers its bypass handler, which is a different moment from ItemData being
	 * ready. A null there would be cached for the life of the server and every price
	 * on the page would quietly lose its coin.
	 */
/**
	 * Edge length of a reagent icon on the store page.
	 *
	 * <p>Thirty-two, because that is what the client's inventory uses and the point of
	 * showing the icon is that it looks like the rest of the game. A named constant
	 * rather than a literal in the tag, so the size is one thing to change and not a
	 * number buried in a string.
	 */
	private static final int REAGENT_ICON_SIZE = 32;

	@Override
	public boolean onCommand(String command, Player player, Creature bypassOrigin)
	{
		if (player == null)
		{
			return false;
		}
		if (command.startsWith("tame_buy_reagent"))
		{
			return buy(player, parameter(command, 0), parameter(command, 1));
		}
		if (command.startsWith("tame_reagent_window"))
		{
			return openWindow(player);
		}
		openFor(player);
		return true;
	}


	/**
	 * Opens the core's own multisell window for the reagent list.
	 *
	 * <p>This is the game's shop window, not a generated page: the client's quantity
	 * selector comes with it, and the purchase is the core's MultiSellChoose, so the
	 * Adena check, the weight, the capacity, the handover and the messages are all
	 * the core's rather than this module's.
	 *
	 * <p>The NPC argument is null on purpose. MultisellData refuses to send a list
	 * to an ordinary player unless the list says who may open it, and this module
	 * has no shopkeeper to name, so the list ships &lt;npc&gt;-1&lt;/npc&gt; and the
	 * core's own first test - isNpcAllowed(-1) - lets it through. Passing null with
	 * that entry present is the whole trick; without it, only a GM can open the
	 * window.
	 *
	 * <p>applyTaxes is false because the list names no castle, and this list is
	 * nowhere near one.
	 */
	private static boolean openWindow(Player player)
	{
		final int listId = TamingData.getInstance().getReagentMultisellId();
		MultisellData.getInstance().separateAndSend(listId, player, null, false);
		return true;
	}

	/**
	 * Draws the store.
	 *
	 * <p>Public because there are two ways in and they must not be able to drift
	 * apart: the .tamestore command, and double-clicking the catalogue item, which
	 * reaches here through TameStoreCatalogue.
	 *
	 * <p>What the page draws depends on one startup reading. The reagent window is
	 * the better surface - the client's own quantity box, the core's
	 * MultiSellChoose transaction - so when it is expected to work this page is only
	 * a way into it and says so, with the reagents listed for information. When the
	 * list is missing, unsentineled or mispriced, the page instead draws the
	 * reagents with the module's own quantity buttons, so a broken list costs the
	 * window and not the store. That check is TamingData.isMultisellUsable and it
	 * is made once at startup; if it is ever wrong the cost is a page with the
	 * wrong buttons on it, not a store that cannot sell.
	 *
	 * <p>The layout follows the collar passport pages rather than a plain button
	 * list: sek.cbui94/sek.cbui92 at the sizes the passport already uses, and the
	 * same gold/dim/body font colours. The earlier version used
	 * L2UI_ch3.smallbutton2 stretched to width=150, which tore because that texture
	 * is a fixed nine-slice, and put text with no table to sit in against the
	 * window edge.
	 */
	public static void openFor(Player player)
	{
		final TamingData data = TamingData.getInstance();
		final boolean window = data.isMultisellUsable();
		final StringBuilder html = new StringBuilder(2000);
		html.append("<html><body><center>");
		// Plain text above a flat table. Deliberately the least structure this page can
		// get away with:
		//
		//  - no bgcolor. Tried, the client crashed. Ruled out.
		//  - no header or description rows. The heading and the description are centred
		//    font lines above the table, as the collar passport pages do it.
		//  - rows are direct children of the table. They used to be wrapped in a
		//    <tr><td> pair, which puts <tr> inside a <td>; that nesting is malformed
		//    and the engine does not reject it, so it drew and then crashed.
		//  - no valign. Not used by any other page in this module.
		//
		// If this page ever misbehaves again, add one of those back on its own and
		// test it on its own. Do not add two at a time.
		html.append("<font color=F2CC60>BEAST TAMING REAGENTS</font><br>");
		html.append("<font color=6E7681>A flute binds an ordinary beast. A bugle binds a raid.</font><br><br>");
		html.append("<table width=430>");
		reagentRow(html, data.getNormalItem(), "Ordinary creatures", data.getNormalPrice(), window);
		html.append("<tr><td height=10 colspan=3></td></tr>");
		reagentRow(html, data.getRaidItem(), "Raid creatures", data.getRaidPrice(), window);
		html.append("</table><br>");
		if (window)
		{
			// The button tag is left unclosed and the line break goes straight after
			// it, the way the passport pages end their REMOVE and awaken-path buttons.
			// There is no caption text under it: the button says OPEN SHOP itself.
			html.append("<br>");
			html.append(OPEN_SHOP_BUTTON);
			html.append("<br>");
			html.append("<font color=6E7681>The shop window takes the quantity and the Adena itself.</font>");
		}
		else
		{
			// The window is not expected to work, so this page has to be the shop.
			// Reaching this means startup logged why - see TamingData.verifyMultisellPrices.
			html.append("<font color=F6C453>The shop window is unavailable, so buy here instead.</font><br>");
			html.append("<font color=6E7681>Amount:</font><br>");
		}
		// No CLOSE button. The window already has the client's own close control in
		// its title bar, and a second one that had to be pressed to get out of a shop
		// was a worse answer than the one the client was already offering. Nothing
		// needs to close the window from the server side, so there is no bypass for it.
		html.append("</center></body></html>");
		// Object id 0: the window belongs to the player, not to an NPC. The collar
		// passport pages are sent the same way.
		player.sendPacket(new NpcHtmlMessage(0, html.toString()));
	}

	// ============================================================================

	/**
	 * One reagent row: icon, name, what it is for, and the price.
	 *
	 * <p>Three columns and three rows of markup, all of it the same shape the collar
	 * pages already use successfully. The icon is drawn at 32, which is the size the
	 * client's own inventory uses, so the page matches the rest of the game and the two
	 * reagents are easy to tell apart at a glance. The name and the icon both come from
	 * the item rather than being written here, so the page cannot drift away from what
	 * the reagent actually is in the bag.
	 *
	 * <p>The quantity buttons are only drawn when the window is not carrying the shop,
	 * which is the whole reason they are still here.
	 */
	private static void reagentRow(StringBuilder html, int itemId, String use, int price, boolean window)
	{
		final ItemTemplate template = ItemData.getInstance().getTemplate(itemId);
		final String icon = (template == null) ? null : template.getIcon();
		final String name = (template == null) ? ("Item " + itemId) : template.getName();
		html.append("<tr>");
		html.append("<td width=40 align=center>");
		html.append(iconTag(icon, REAGENT_ICON_SIZE, REAGENT_ICON_SIZE));
		html.append("</td>");
		html.append("<td width=216><font color=E4E1DB>").append(escape(name)).append("</font><br>");
		html.append("<font color=6E7681>").append(escape(use)).append("</font></td>");
		html.append("<td width=130 align=right><font color=F2CC60>").append(price).append("</font><br>");
		html.append("<font color=6E7681>Adena</font></td>");
		html.append("</tr>");
		if (!window)
		{
			// Amount row, drawn under its reagent so the two cannot be confused. Same
			// button shape as the passport pages: inner font, no closing tag.
			html.append("<tr><td><font color=6E7681>Amount:</font>");
			for (final int quantity : QUANTITIES)
			{
				html.append(" <button value=\"").append(quantity).append("\" action=\"bypass -h tame_buy_reagent ").append(itemId).append(' ').append(quantity).append("\" width=52 height=22 back=sek.cbui94 fore=sek.cbui92><font color=D7DCE2>").append(quantity).append("</font>");
			}
			html.append("</td></tr>");
		}
	}

	/**
	 * The reagent's own name from its item template.
	 *
	 * <p>Used by the purchase messages so that what a player is told they just
	 * bought is the same string the store page drew above the button. An unknown id
	 * degrades to "item 9300" rather than to nothing.
	 */
	private static String reagentName(int itemId)
	{
		final ItemTemplate template = ItemData.getInstance().getTemplate(itemId);
		return (template == null) ? ("item " + itemId) : template.getName();
	}

/**
 * Builds one &lt;img&gt; tag for a client texture, or nothing at all when the name
 * is unusable.
 *
 * <p>Item icon names are stored with an {@code icon.} prefix, and the reference
 * the client resolves is package {@code Icon} plus the texture name, so the prefix
 * has to be capitalised. That is the same rewrite stock NPC dialogues rely on.
 *
 * <p>Every icon on the store page goes through here, so there is one place where
 * the rewriting happens and one place where a missing icon is tolerated.
 *
 * <p>Returning an empty string for an unusable name is the whole safety argument.
 * A texture the client cannot resolve leaves a gap in the layout and nothing else,
 * and nothing on this page depends on an image: the buttons are text and carry their
 * own bypass, so the purchase path works whether or not any picture draws. Decoration
 * is therefore allowed to fail, which is what makes it safe to decorate.
 *
 * @param icon the stored icon name, may be null
 * @param width drawn width in pixels
 * @param height drawn height in pixels
 */
private static String iconTag(String icon, int width, int height)
	{
		if ((icon == null) || icon.trim().isEmpty())
		{
			return "";
		}
		return "<img src=\"" + escape(icon.trim().replaceFirst("^icon\\.", "Icon.")) + "\" width=" + width + " height=" + height + ">";
	}

/**
 * Escapes the three characters that would break a tag or an attribute.
 */
private static String escape(String text)
{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	/**
	 * Buys a quantity of one reagent, or refuses it by name.
	 *
	 * <p>Order matters here, and the previous order was an item-donation bug.
	 *
	 * <p>It used to add the items first and inspect the single {@link Item} that
	 * {@code addItem} returns. For a non-stackable item a single request creates a separate
	 * object per unit, and the returned reference is only the last of them - count 1, no
	 * matter that fifty were just created. So the "did we get them all" test failed on any
	 * quantity above one, and the rollback that followed removed exactly one item. A player
	 * asking for 50 got 49 free, and the 50-item message about what they bought was never
	 * reached. Nothing about the Adena timing caused it; the last-item-only rollback did.
	 *
	 * <p>So delivery is measured by counting the inventory instead of trusting the returned
	 * reference, the shortfall is removed by item id rather than by object, and the Adena is
	 * taken only once delivery is known. A partial fill is cancelled completely - the items
	 * that did fit are given back and charged for nothing - which is what the old message
	 * already promised.
	 */
	private static boolean buy(Player player, String idParameter, String quantityParameter)
	{
		final TamingData data = TamingData.getInstance();
		final int itemId;
		try
		{
			itemId = Integer.parseInt(idParameter);
		}
		catch (NumberFormatException e)
		{
			player.sendMessage("That is not a reagent I sell.");
			return false;
		}
		final String label;
		final int unitPrice;
		if (itemId == data.getNormalItem())
		{
			label = reagentName(itemId);
			unitPrice = data.getNormalPrice();
		}
		else if (itemId == data.getRaidItem())
		{
			label = reagentName(itemId);
			unitPrice = data.getRaidPrice();
		}
		else
		{
			// Anything else is refused rather than priced. A reagent is one of two
			// configured values and there is no reason to trust a third.
			player.sendMessage("That is not a reagent I sell.");
			return false;
		}
		int quantity;
		try
		{
			quantity = Integer.parseInt(quantityParameter);
		}
		catch (NumberFormatException e)
		{
			quantity = 1;
		}
		if (quantity < 1)
		{
			quantity = 1;
		}
		// Clamped rather than rejected. A hand-typed bypass asking for a million is
		// not worth failing; it is worth quoting the real total, which the Adena check
		// below then refuses if the player cannot actually pay.
		final long total = (long) unitPrice * quantity;
		if (total > Integer.MAX_VALUE)
		{
			player.sendMessage("That is too many to buy at once.");
			return false;
		}
		// Reported before capacity, because being too poor is the likelier of the two and
		// it is the one the player can act on by going and earning.
		if (player.getAdena() < total)
		{
			player.sendMessage("You need " + total + " Adena for " + quantity + "x " + label + ", and you have " + player.getAdena() + ".");
			return false;
		}
		// Checked up front so the ordinary failure - a full bag, or not enough weight - costs
		// the player nothing at all. validateCapacityByItemId asks for one slot of a stackable
		// and for the full count of a non-stackable, so a 50-reagent request against 30 free
		// slots is refused here rather than half-delivered below.
		//
		// Weight is a separate check and is not implied by the slot check: these reagents
		// weigh 20 each, so a player with 100 free slots and no capacity left can still be
		// refused by the core for a quantity that passed validateCapacity. Asking both up
		// front means the message is the accurate one.
		if (!player.getInventory().validateCapacityByItemId(itemId, quantity) || !player.getInventory().validateWeightByItemId(itemId, quantity))
		{
			player.sendMessage("You cannot carry " + quantity + "x " + label + " - you are out of inventory space or weight.");
			return false;
		}
		if (!player.reduceAdena(ItemProcessType.BUY, (int) total, null, true))
		{
			// The authoritative check. The one above is for the message; this is the one
			// that decides, and it cannot be raced because nothing else runs in between
			// on this thread.
			player.sendMessage("You do not have " + total + " Adena.");
			return false;
		}
		// Counted either side of the add so the result is a fact about the inventory rather
		// than an inference from whichever object the core happened to return.
		final int before = countInInventory(player, itemId);
		player.getInventory().addItem(ItemProcessType.BUY, itemId, quantity, player, null);
		final int delivered = Math.max(0, countInInventory(player, itemId) - before);
		if (delivered < quantity)
		{
			// Short delivery. Unwind all of it: the items that did land are removed by item
			// id, so every one of them goes regardless of how many objects they were split
			// across, and the Adena for exactly those is returned. Cancelling the whole
			// purchase rather than keeping a part fill is what the message below promises.
			if (delivered > 0)
			{
				player.getInventory().destroyItemByItemId(ItemProcessType.REFUND, itemId, delivered, player, null);
			}
			final long refund = (long) unitPrice * delivered;
			if (refund > 0)
			{
				player.addAdena(ItemProcessType.REFUND, (int) refund, null, true);
			}
			if (delivered == 0)
			{
				player.sendMessage("You cannot carry " + quantity + "x " + label + " - you are out of inventory space or weight.");
			}
			else
			{
				player.sendMessage("You can only carry " + delivered + " of those. Nothing was bought.");
			}
			return false;
		}
		player.sendMessage("You buy " + quantity + "x " + label + " for " + total + " Adena.");
		openFor(player);
		return true;
	}

	/**
	 * How many of one item id the player is carrying in total.
	 *
	 * <p>Summed across every object holding that id, so it is correct for a stackable in one
	 * pile and for a non-stackable spread over as many objects as the count. Deliberately not
	 * {@code Item.getCount()} on one object, and not {@code getInventoryItemCount}, whose
	 * second argument is an enchant level rather than a type filter.
	 */
	private static int countInInventory(Player player, int itemId)
	{
		int total = 0;
		for (Item item : player.getInventory().getItems())
		{
			if ((item != null) && (item.getId() == itemId))
			{
				total += item.getCount();
			}
		}
		return total;
	}

	/** Reads the nth space-separated word of a bypass command, or "" if absent. */
	private static String parameter(String command, int index)
	{
		int position = 0;
		for (int i = 0; i <= index; i++)
		{
			final int space = command.indexOf(' ', position);
			if (space < 0)
			{
				return "";
			}
			position = space + 1;
		}
		final int end = command.indexOf(' ', position);
		return (end < 0 ? command.substring(position) : command.substring(position, end)).trim();
	}

	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}
}
