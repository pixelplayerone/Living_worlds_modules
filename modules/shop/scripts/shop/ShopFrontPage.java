/*
 * ShopFrontPage.java
 *
 * Draws the shop and takes the purchases.
 *
 * READ README.md, "What this HTML engine will not do", BEFORE EDITING THIS FILE
 *
 * That section is not background. Three of the rules below exist because the
 * obvious alternative crashed a client or drew something broken, and two of them
 * look like perfectly reasonable HTML. This file is written so that a reader can
 * see which choices were deliberate:
 *
 *   - bgcolor is never used.                          (crashed the client)
 *   - rows are direct children of the one table.       (crashed the client)
 *   - no <img> is ever used to make a background.      (cannot be made seamless)
 *   - buttons are NOT closed.                          (a black square appears)
 *   - exactly ONE caption per button.                  (two overlap)
 *   - value is QUOTED; it may contain a space.        (copied from the reagent page)
 *   - buttons are never closed.                        (a black square appears)
 *   - a <font> follows on the SAME line, then a <br>.  (else a gap beside it)
 *   - spacer rows use colspan=3 between items.          (rows run together without)
 *   - buttons are 100x22 or narrower.                  (140 tore: body + stray square)
 *   - valign is never used.                            (untested, no reason to risk it)
 *
 * Every one of these is copied from the taming module's own pages, which are known
 * to render correctly on this client. If you need to change the markup, change one
 * of those at a time and restart. Changing four and guessing which one the client
 * objected to is how this module learned them the hard way.
 *
 * A NOTE ON CHECKING YOUR OWN OUTPUT
 *
 * A tag-balance check over the generated page is cheap and catches the class of
 * fault that both crashes above were. Because buttons are deliberately unclosed,
 * treat <button> as self-terminating or the check will report every one of them
 * as unbalanced and you will go hunting for a fault that is not there. With that
 * one exception every page this module can produce - shop, fallback, empty -
 * balances clean.
 */
package shop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.MultisellData;
import org.l2jmobius.gameserver.handler.IBypassHandler;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * The shop page and its direct-buy path.
 */
public class ShopFrontPage implements IBypassHandler
{
	/**
	 * Whether the native shop window was found usable at startup.
	 *
	 * <p>A single startup reading rather than a per-request one, on purpose. If the
	 * window is going to fail, it fails the same way every time, and re-reading the
	 * disk on every page view would buy nothing. The page changes shape once, at
	 * boot, and stays that shape for the session.
	 */
	private static boolean _windowUsable;

	private static String[] _commands = { "shop" };

	/** Called once by the entry point with the result of the startup probe. */
	public static void setWindowUsable(boolean usable)
	{
		_windowUsable = usable;
	}

	/** Bypass prefixes this handler answers to. Rebuilt once the config is read. */
	public static void setCommands(String command)
	{
		_commands = new String[]
		{
				command,
				"shop_buy",
				"shop_open_window"
		};
	}

	// ========================================================================
	//  Request routing
	// ========================================================================

	@Override
	public boolean onCommand(String command, Player player, Creature bypassOrigin)
	{
		final String prefix = ShopCatalogue.getInstance().command();
		if (command.startsWith("shop_buy "))
		{
			// parameter() indexes words after the command from zero:
			// shop_buy <itemId> <quantity> -> 0=itemId, 1=quantity.
			return buy(player, parameter(command, 0), parameter(command, 1));
		}
		if (command.startsWith("shop_open_window"))
		{
			return openWindow(player);
		}
		if (command.equals(prefix) || command.equals(prefix + " "))
		{
			openFor(player);
			return true;
		}
		return false;
	}

	// ========================================================================
	//  The page
	// ========================================================================

	/**
	 * Draws the shop.
	 *
	 * <p>Public because there are two ways in - the chat command, and the optional
	 * item in {@code OpenItemId} - and they must not be able to drift apart.
	 */
	public static void openFor(Player player)
	{
		final ShopCatalogue catalogue = ShopCatalogue.getInstance();
		final boolean window = _windowUsable;
		final List<ShopCatalogue.Row> rows = catalogue.rows();

		final StringBuilder html = new StringBuilder(2000 + (rows.size() * 200));
		html.append("<html><body><center>");
		html.append("<font color=").append(catalogue.titleColor()).append('>').append(escape(catalogue.title())).append("</font><br>");
		if (!catalogue.intro().isEmpty())
		{
			html.append("<font color=").append(catalogue.noteColor()).append('>').append(escape(catalogue.intro())).append("</font><br>");
		}
		html.append("<br>");

		// One flat table. Rows are direct children of it and nothing else is.
		//
		// This used to be wrapped in a <tr><td> pair with the rows inside it, which
		// puts <tr> elements inside a <td>. That is malformed, this engine does not
		// reject it, and the client crashed. A table cell containing whole rows is
		// the shape to avoid.
		//
		// No bgcolor anywhere: it was tried on this table and on its rows, and it
		// crashed the client. The window's own background is the background.
		final int width = catalogue.tableWidth();
		html.append("<table width=").append(width).append('>');
		if (rows.isEmpty())
		{
			// A blank template has to look deliberate. An empty table with nothing in
			// it reads as a broken shop, so the empty state explains itself and says
			// exactly which file and which key to edit.
			html.append("<tr><td><font color=").append(catalogue.noticeColor())
					.append("><br>This shop is empty on purpose. Nothing is for sale yet.<br><br>")
					.append("Open <font color=").append(catalogue.labelColor())
					.append(">config/module.ini</font> and set the <font color=").append(catalogue.labelColor())
					.append(">Items</font> key:<br><br>")
					.append("Items = 57;Potion of Minor Healing;500<br><br>")
					.append("One entry per item, separated by a pipe: id, note, price.<br>")
					.append("An empty Items value is valid and is what ships.<br>")
					.append("</font></td></tr>");
		}
		for (int i = 0; i < rows.size(); i++)
		{
			if (i > 0)
			{
				// Spacer row between items, copied from the working taming shop page.
				// Without it the rows run together and the table reads as one block.
				html.append("<tr><td height=10 colspan=3></td></tr>");
			}
			html.append(row(catalogue, rows.get(i)));
		}
		html.append("</table><br>");

		if (rows.isEmpty())
		{
			// Nothing to buy, so no shop window and no quantity buttons. Drawing
			// them over an empty table would suggest the shop works when it cannot.
			html.append("</center></body></html>");
			player.sendPacket(new NpcHtmlMessage(0, html.toString()));
			return;
		}

		if (window)
		{
			// A break BEFORE the button, then the button, then its label on the
			// same line. This is the order TameCollarView uses and it is what fills
			// the width to the right of the caption.
			html.append("<br>");
			html.append(windowButton(catalogue));
		}
		else if (!catalogue.windowMissingNotice().isEmpty())
		{
			html.append("<font color=").append(catalogue.noticeColor()).append('>').append(escape(catalogue.windowMissingNotice())).append("</font><br>");
		}
		if (window && !catalogue.windowNote().isEmpty())
		{
			// A break HERE, after the explanatory text - never straight after the
			// button tag.
			html.append("<br>");
			html.append("<font color=").append(catalogue.noteColor()).append('>').append(escape(catalogue.windowNote())).append("</font>");
		}
		else if (window)
		{
			// The note is empty but the button still must not end the page.
			html.append("<br>");
			html.append("<font color=").append(catalogue.noteColor()).append(">&nbsp;</font>");
		}
		html.append("</center></body></html>");
		// Object id 0: this window belongs to the player, not to an NPC. There is
		// no shopkeeper here to own it.
		player.sendPacket(new NpcHtmlMessage(0, html.toString()));
	}

	/**
	 * One item row, plus its quantity buttons when the page is the shop.
	 *
	 * <p>The quantity buttons are drawn only when the native window is not carrying
	 * the transaction, which is the whole reason they exist: when the window works
	 * it brings its own quantity box, and two of them on one screen is just noise.
	 */
	private static String row(ShopCatalogue catalogue, ShopCatalogue.Row row)
	{
		final StringBuilder html = new StringBuilder(220);
		final ItemTemplate template = ItemData.getInstance().getTemplate(row.getItemId());
		final String name = (template == null) ? ("Item " + row.getItemId()) : template.getName();
		final String icon = (template == null) ? null : template.getIcon();

		html.append("<tr>");
		if (catalogue.showIcons())
		{
			html.append("<td width=40 align=center>");
			html.append(iconTag(icon, catalogue.iconSize(), catalogue.iconSize()));
			html.append("</td>");
		}
		html.append("<td width=216><font color=").append(catalogue.labelColor()).append('>').append(escape(name)).append("</font>");
		if (!row.getNote().isEmpty())
		{
			html.append("<br><font color=").append(catalogue.noteColor()).append('>').append(escape(row.getNote())).append("</font>");
		}
		html.append("</td>");
		if (catalogue.showPrices())
		{
			// A price of zero means "not priced here", which is a real state: an
			// item whose price is set by a quest or a merchant script. Drawing 0
			// there would be a lie.
			html.append("<td width=130 align=right>");
			if (row.getPrice() > 0)
			{
				html.append("<font color=").append(catalogue.priceColor()).append('>').append(row.getPrice()).append("</font><br>");
			}
			html.append("<font color=").append(catalogue.noteColor()).append(">Adena</font></td>");
		}
		html.append("</tr>");

		if (!_windowUsable)
		{
			html.append("<tr><td><font color=").append(catalogue.noteColor()).append(">Amount:</font>");
			for (final int quantity : catalogue.quantities())
			{
				html.append(' ');
				html.append(quantityButton(catalogue, row.getItemId(), quantity));
			}
			html.append("</td></tr>");
		}
		return html.toString();
	}

	/**
	 * The button that opens the native shop window.
	 *
	 * <p>The caption is {@code OPEN SHOP}, quoted, short enough to fit
	 * between the ends of the 100x22 button.
	 *
	 * <p>Because the button is never closed, the tag that follows it is a SIBLING,
	 * not a child. The sentence explaining the button therefore continues on the
	 * same line and fills the width to the right of it, so the caption never sits
	 * alone beside an empty half-button.
	 *
	 * <p>This whole method is a copy of the shop button on the taming module's
	 * reagent page, down to the quoted value and the trailing {@code <br>}.
	 */
	private static String windowButton(ShopCatalogue catalogue)
	{
		return button(catalogue, WINDOW_BUTTON_CAPTION, "shop_open_window", WINDOW_BUTTON_WIDTH)
				+ "<font color=D7DCE2>Open the shop window</font>";
	}

	/**
	 * One quantity button.
	 *
	 * <p>Same shape as the window button, for the same reasons. 52 wide rather than
	 * 100 because the caption is two or three characters and a button stretched
	 * around that tears.
	 */
	private static String quantityButton(ShopCatalogue catalogue, int itemId, int quantity)
	{
		return button(catalogue, String.valueOf(quantity), "shop_buy " + itemId + ' ' + quantity, 52);
	}

	/**
	 * The one place a button is built.
	 *
	 * <p>The shape is the one the author's other module uses on its pages, which
	 * renders correctly on this client. The width is the one deliberate difference,
	 * and it is on purpose; see the note on the width constant below.
	 *
	 * <pre>
	 * &lt;button value="OPEN SHOP" action="bypass -h shop_open_window" width=100 height=22 back=sek.cbui94 fore=sek.cbui92&gt;&lt;font color=D7DCE2&gt;Open the shop window&lt;/font&gt;
	 * </pre>
	 *
	 * <p>Four details, none of them reasoned about:
	 *
	 * <ul>
	 * <li>The caption goes in {@code value}, <b>quoted</b>, and it may contain a
	 * space: {@code value="OPEN SHOP"}.</li>
	 * <li>It is <b>never closed</b>. The taming module closes none of its buttons,
	 * and closing one puts a black square under it on this client.</li>
	 * <li>What follows the tag is a {@code <font>} - an <b>explanation, not a
	 * second caption</b> - and because the button is unclosed that font is a
	 * sibling, laid out on the same line immediately to the right of the button,
	 * which is what fills the button's width.</li>
	 * <li>There is <b>no {@code <br>} straight after the tag</b>. The break comes
	 * after that font, never between the tag and the font.</li>
	 * </ul>
	 *
	 * <p>The button texture draws about 100 pixels wide however wide the tag asks
	 * for. Asked for 140, a screenshot showed a 100 pixel body and a separate 12
	 * pixel square at the far end, with the caption centred on the whole 140. So
	 * the width stays at or under 100, and the caption has to fit inside it. The captions here are all short by
	 * construction: {@code OPEN SHOP} for the shop button, one to three digits for
	 * the quantity buttons.
	 */
	private static String button(ShopCatalogue catalogue, String caption, String bypass, int width)
	{
		return "<button value=\"" + escape(caption) + "\" action=\"bypass -h " + bypass + "\" width=" + width
				+ " height=22 back=sek.cbui94 fore=sek.cbui92>";
	}

	/**
	 * The shop button's caption and width, named so they can be quoted exactly.
	 *
	 * <p>These are reported in the boot log by {@code ShopFrontModule}. They are the
	 * quickest way to tell a live build from a stale one: if the button on screen
	 * does not read exactly this, the server is running older code and the markup on
	 * disk is not what the client is being sent.
	 */
	static final String WINDOW_BUTTON_CAPTION = "OPEN SHOP";

	/** Width of the shop button. See windowButton's notes for why not 140. */
	static final int WINDOW_BUTTON_WIDTH = 100;

	// ========================================================================
	//  Opening the native window
	// ========================================================================

	/**
	 * Hands the player to the core's own shop window.
	 *
	 * <p>The NPC argument is null on purpose. {@code MultisellData.separateAndSend}
	 * refuses to send a list to an ordinary player unless the list names who may
	 * open it, and there is no shopkeeper here to name, so the list ships
	 * {@code <npc>-1</npc>} and the core's first test lets it through. Passing
	 * null with that entry present is the whole trick; without it only a GM can
	 * open the window.
	 *
	 * <p>{@code applyTaxes} is false because the sample list names no castle.
	 */
	private static boolean openWindow(Player player)
	{
		final ShopCatalogue catalogue = ShopCatalogue.getInstance();
		final int listId = catalogue.multisellId();
		if (!_windowUsable)
		{
			// Reachable only by hand-typing the bypass. The page never draws the
			// button when the probe failed, but the bypass is still routable.
			openFor(player);
			return true;
		}
		MultisellData.getInstance().separateAndSend(listId, player, null, false);
		return true;
	}

	// ========================================================================
	//  Direct purchase
	// ========================================================================

	/**
	 * Buys a quantity of one configured item, or refuses it by name.
	 *
	 * <p>This is the fallback path. It exists so that a misconfigured multisell
	 * costs the nice window instead of the shop, and it is the only thing that
	 * sells anything when {@code MultisellId = 0}.
	 *
	 * <p>The payment is taken before delivery, then the inventory is preflighted
	 * for both slots and weight before anything is created. Every failure after
	 * payment refunds the full amount. This order matters when the server is
	 * configured to create non-stackable quantity purchases as separate items:
	 * inspecting only the last returned instance is not a safe quantity check.
	 */
	private static boolean buy(Player player, String idParameter, String quantityParameter)
	{
		final ShopCatalogue catalogue = ShopCatalogue.getInstance();
		final int itemId;
		try
		{
			itemId = Integer.parseInt(idParameter);
		}
		catch (NumberFormatException e)
		{
			player.sendMessage("That is not something this shop sells.");
			return false;
		}
		final int unitPrice = catalogue.priceOf(itemId);
		if (unitPrice < 0)
		{
			// Anything not on the page is refused rather than priced. The prices
			// live in a config, and there is no reason to trust a bypass asking for
			// an id that is not on the list.
			player.sendMessage("That is not something this shop sells.");
			return false;
		}
		if (unitPrice == 0)
		{
			// The row is marked unpriced, so the page drew no number. Charging the
			// one this path invents would be worse than refusing.
			player.sendMessage("That item is not sold for a set price here. Use the shop window.");
			return false;
		}
		final ItemTemplate template = ItemData.getInstance().getTemplate(itemId);
		if (template == null)
		{
			player.sendMessage("That item is not available in this shop.");
			return false;
		}
		final String label = (template == null) ? ("item " + itemId) : template.getName();

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
		// Clamped rather than rejected: a hand-typed bypass asking for a million is
		// not worth failing outright, it is worth quoting the real total, which the
		// Adena check below then refuses if the player cannot actually pay.
		final long total = (long) unitPrice * quantity;
		if (total > Integer.MAX_VALUE)
		{
			player.sendMessage("That is too many to buy at once.");
			return false;
		}
		if (player.getAdena() < total)
		{
			player.sendMessage("You need " + total + " Adena for " + quantity + "x " + label + ", and you have " + player.getAdena() + ".");
			return false;
		}

		if (!player.reduceAdena(ItemProcessType.BUY, (int) total, null, true))
		{
			player.sendMessage("The purchase could not be completed.");
			return false;
		}

		// The native helper accounts for stackability: one slot for a stackable
		// item, quantity slots for a non-stackable item. The weight helper checks
		// the complete requested quantity, not only the last object returned by
		// addItem().
		final boolean hasCapacity = player.getInventory().validateCapacityByItemId(itemId, quantity);
		final boolean hasWeight = player.getInventory().validateWeightByItemId(itemId, quantity);
		if (!hasCapacity || !hasWeight)
		{
			player.addAdena(ItemProcessType.REFUND, (int) total, null, true);
			player.sendMessage("You cannot carry " + quantity + "x " + label + ". Your Adena was refunded.");
			return false;
		}

		final Map<Integer, Long> before = inventorySnapshot(player, itemId);
		player.getInventory().addItem(ItemProcessType.BUY, itemId, quantity, player, null);
		final long delivered = inventoryTotal(player, itemId) - snapshotTotal(before);
		if (delivered != quantity)
		{
			// A second guard remains for a race or a build whose addItem path has
			// different capacity semantics. Roll back every object/count created by
			// this request; never infer delivery from only the last item when
			// MultipleItemDrop is enabled.
			rollbackSnapshotDelta(player, itemId, before);
			player.addAdena(ItemProcessType.REFUND, (int) total, null, true);
			player.sendMessage("The purchase could not be completed and your Adena was refunded.");
			return false;
		}
		player.sendMessage("You buy " + quantity + "x " + label + " for " + total + " Adena.");
		openFor(player);
		return true;
	}

	private static Map<Integer, Long> inventorySnapshot(Player player, int itemId)
	{
		final Map<Integer, Long> snapshot = new HashMap<>();
		for (final Item item : player.getInventory().getItems())
		{
			if (item.getId() == itemId)
			{
				snapshot.put(item.getObjectId(), Long.valueOf(item.getCount()));
			}
		}
		return snapshot;
	}

	private static long snapshotTotal(Map<Integer, Long> snapshot)
	{
		long total = 0;
		for (long count : snapshot.values())
		{
			total += count;
		}
		return total;
	}

	private static long inventoryTotal(Player player, int itemId)
	{
		long total = 0;
		for (final Item item : player.getInventory().getItems())
		{
			if (item.getId() == itemId)
			{
				total += item.getCount();
			}
		}
		return total;
	}

	private static void rollbackSnapshotDelta(Player player, int itemId, Map<Integer, Long> before)
	{
		// destroyItem mutates the inventory, so iterate over a detached copy.
		for (final Item item : new ArrayList<>(player.getInventory().getItems()))
		{
			if (item.getId() != itemId)
			{
				continue;
			}
			final long previous = before.containsKey(item.getObjectId()) ? before.get(item.getObjectId()) : 0L;
			final long added = item.getCount() - previous;
			if (added > 0)
			{
				player.getInventory().destroyItem(ItemProcessType.REFUND, item, (int) Math.min(added, Integer.MAX_VALUE), player, null);
			}
		}
	}

	// ========================================================================
	//  Small helpers
	// ========================================================================

	/**
	 * Builds one {@code <img>} tag for a client texture, or nothing when the name
	 * is unusable.
	 *
	 * <p>Item icon names are stored with an {@code icon.} prefix and the reference
	 * the client resolves is package {@code Icon}, so the prefix has to be
	 * capitalised. This is the same rewrite stock NPC dialogues rely on.
	 *
	 * <p>Returning an empty string for an unusable name is the safety argument. A
	 * texture the client cannot resolve leaves a gap in the layout and nothing
	 * else, and nothing on this page depends on a picture drawing: the buttons are
	 * text and carry their own bypass, so the purchase works either way.
	 * Decoration is therefore allowed to fail, which is what makes it safe.
	 *
	 * <p>An icon is a reference to a texture the client already has. There is no
	 * server-side route to a custom one - see README, "Custom icons".
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
	 * Escapes the characters that would break a tag or an attribute.
	 *
	 * <p>Every configured string goes through here on its way to the page, because
	 * {@code module.ini} is edited by hand by whoever installs the module and a
	 * stray {@code <} in a note is not a thing this module should be able to do.
	 */
	private static String escape(String text)
	{
		if ((text == null) || text.isEmpty())
		{
			return "";
		}
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
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
		return (end < 0) ? command.substring(position) : command.substring(position, end).trim();
	}

	@Override
	public String[] getCommandList()
	{
		return _commands;
	}
}
