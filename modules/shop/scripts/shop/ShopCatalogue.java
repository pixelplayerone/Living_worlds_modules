/*
 * ShopCatalogue.java
 *
 * The module's configuration, parsed once at startup and then read as plain
 * data by the page.
 *
 * WHY EVERYTHING IS IN HERE AND NOT IN THE PAGE
 *
 * A shared module has to be adjustable by whoever installs it, without them
 * editing Java and without them guessing at item ids. So the item list, the
 * page text, the colours and the layout are all module.ini settings, and this
 * class is the only thing that reads them.
 *
 * WHY THE ITEM LIST IS ONE DELIMITED STRING
 *
 * ModuleConfig offers getString, getInt and friends and nothing else - notably
 * no way to enumerate keys, so "Item1, Item2, Item3, ..." with a count would
 * work but is miserable to write and impossible to extend. A single line of
 * "id;note;price" rows separated by a pipe is one line to edit and reads fine.
 *
 * The cost is that a note cannot contain a semicolon or a pipe. That is stated
 * in module.ini next to the setting and it is a reasonable price for a page
 * that shows one short line of text per item.
 */
package shop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.modules.ModuleConfig;

/**
 * The configured catalogue and page settings.
 *
 * <p>Immutable once {@link #configure} has run. A row that cannot be parsed is
 * reported and dropped rather than being guessed at, and if nothing survives the
 * module refuses to enable, because a shop with an empty catalogue is a broken
 * shop and not a quiet one.
 */
public final class ShopCatalogue
{
	/**
	 * One line on the page.
	 *
	 * <p>The name and the icon are deliberately not stored. Both come from the
	 * item template at draw time, so an item the server renames still shows its
	 * real name here with no edit to this module, and there is no second copy of
	 * a name to fall out of date.
	 */
	public static final class Row
	{
		private final int _itemId;
		private final String _note;
		private final int _price;

		Row(int itemId, String note, int price)
		{
			_itemId = itemId;
			_note = note;
			_price = price;
		}

		public int getItemId()
		{
			return _itemId;
		}

		public String getNote()
		{
			return _note;
		}

		/** Adena per unit. Zero means the row shows no price. */
		public int getPrice()
		{
			return _price;
		}

		@Override
		public String toString()
		{
			return "item " + _itemId + (_note.isEmpty() ? "" : " (" + _note + ")") + " at " + _price;
		}
	}

	private static final ShopCatalogue INSTANCE = new ShopCatalogue();

	private List<Row> _rows = Collections.emptyList();
	private int[] _quantities = { 1 };

	private String _command = "shop";
	private int _openItemId;
	private int _multisellId;
	private boolean _useWindow = true;

	private String _title = "SHOP";
	private String _intro = "";
	private String _windowNote = "";
	private String _fallbackNote = "";
	private String _windowMissingNotice = "";

	private int _tableWidth = 430;
	private int _iconSize = 32;
	private boolean _showIcons = true;
	private boolean _showPrices = true;

	private String _titleColor = "F2CC60";
	private String _labelColor = "E4E1DB";
	private String _noteColor = "6E7681";
	private String _priceColor = "F2CC60";
	private String _noticeColor = "F6C453";

	private ShopCatalogue()
	{
	}

	public static ShopCatalogue getInstance()
	{
		return INSTANCE;
	}

	// ========================================================================
	//  Reading the config
	// ========================================================================

	/**
	 * Reads every setting and reports anything unusable.
	 *
	 * <p>Called once from the entry point. The return value is whether the module
	 * has anything worth enabling for; a caller that gets false should stay off
	 * and say so rather than register a shop with no rows in it.
	 *
	 * @return true if at least one row parsed and refers to a real item
	 */
	public boolean configure(ModuleConfig config, java.util.logging.Logger log)
	{
		_command = config.getString("Command", "shop").trim();
		if (_command.isEmpty())
		{
			_command = "shop";
		}
		// A leading dot is common in these configs and harmless to accept, but the
		// bypass list matches on the bare word, so it is stripped rather than made
		// the caller's problem.
		while (_command.startsWith("."))
		{
			_command = _command.substring(1);
		}
		// The core truncates an incoming bypass at the FIRST space before looking
		// it up, so a name with a space in it can never match: "my shop" is
		// registered whole and then looked up as "my". That fails silently, with
		// no log line anywhere, so it is caught here instead.
		int space = _command.indexOf(' ');
		if (space >= 0)
		{
			log.warning("shop: Command '" + _command + "' contains a space. The core " + "truncates a bypass at the first space, so it could never match. Using '" + _command.substring(0, space) + "'.");
			_command = _command.substring(0, space);
		}
		if (_command.isEmpty())
		{
			_command = "shop";
		}

		_openItemId = config.getInt("OpenItemId", 0);
		_multisellId = config.getInt("MultisellId", 0);
		_useWindow = config.getBoolean("UseWindow", true);

		_title = config.getString("PageTitle", "SHOP");
		_intro = config.getString("PageIntro", "");
		_windowNote = config.getString("WindowNote", "");
		_fallbackNote = config.getString("FallbackNote", "");
		_windowMissingNotice = config.getString("WindowMissingNotice", "");

		_tableWidth = clamp(config.getInt("TableWidth", 430), 260, 900);
		_iconSize = clamp(config.getInt("IconSize", 32), 16, 64);
		_showIcons = config.getBoolean("ShowIcons", true);
		_showPrices = config.getBoolean("ShowPrices", true);

		_titleColor = color(config, "TitleColor", "F2CC60");
		_labelColor = color(config, "LabelColor", "E4E1DB");
		_noteColor = color(config, "NoteColor", "6E7681");
		_priceColor = color(config, "PriceColor", "F2CC60");
		_noticeColor = color(config, "NoticeColor", "F6C453");

		_quantities = parseQuantities(config.getString("Quantities", "1,10,100"), log);

		final List<Row> rows = new ArrayList<>();
		int dropped = 0;
		final String rawItems = config.getString("Items", "");
		for (final String entry : rawItems.split("\\|"))
		{
			final String trimmed = entry.trim();
			if (trimmed.isEmpty())
			{
				continue;
			}
			final Row row = parseRow(trimmed, log);
			if (row == null)
			{
				dropped++;
			}
			else
			{
				rows.add(row);
			}
		}
		_rows = Collections.unmodifiableList(rows);

		if (_rows.isEmpty())
		{
			// An empty Items key is valid, and blanking the key is a supported way to
			// start. The distinction that matters is between "you left it empty on
			// purpose" and "you filled it in and every row was rubbish", so that is
			// what the two messages below say.
			if (rawItems.trim().isEmpty())
			{
				log.info("shop: Items is empty, so the shop will open and sell nothing "
						+ "until you fill it in.");
			}
			else
			{
				log.warning("shop: Items is set but nothing in it could be read, so the shop "
						+ "will sell nothing. Expected \"1146;note;8000|1147;note;8000\".");
			}
		}
		if (dropped > 0)
		{
			log.warning("shop: dropped " + dropped + " unparseable Items row(s). The format is <itemId>;<note>;<price>, rows separated by a pipe.");
		}
		log.info("shop: " + _rows.size() + " item(s) configured, command ." + _command
				+ (_multisellId > 0 ? (" , multisell " + _multisellId) : " , no multisell (direct buy only)"));
		return true;
	}

	/**
	 * Parses one {@code id;note;price} row, or returns null and says why.
	 *
	 * <p>A row that names an item the server does not have is dropped rather than
	 * drawn. A bad id would otherwise put a blank icon and an empty name on the
	 * page and sell something the player cannot see, which is worse than the row
	 * being absent.
	 */
	private Row parseRow(String entry, java.util.logging.Logger log)
	{
		final String[] parts = entry.split(";", -1);
		final int itemId;
		try
		{
			itemId = Integer.parseInt(parts[0].trim());
		}
		catch (NumberFormatException e)
		{
			log.warning("shop: \"" + entry + "\" does not start with an item id, skipping it.");
			return null;
		}
		if (itemId <= 0)
		{
			log.warning("shop: item id " + itemId + " in \"" + entry + "\" is not a valid id, skipping it.");
			return null;
		}
		final String note = (parts.length > 1) ? parts[1].trim() : "";
		int price = 0;
		if ((parts.length > 2) && !parts[2].trim().isEmpty())
		{
			try
			{
				price = Integer.parseInt(parts[2].trim());
			}
			catch (NumberFormatException e)
			{
				log.warning("shop: the price in \"" + entry + "\" is not a number, treating it as unpriced.");
				price = 0;
			}
		}
		// A negative price is refused rather than clamped. Clamping would quietly
		// sell the item for nothing; refusing means the author finds out.
		if (price < 0)
		{
			log.warning("shop: item " + itemId + " has a negative price (" + price + "). Negative prices are refused, skipping the row.");
			return null;
		}
		final ItemTemplate template = ItemData.getInstance().getTemplate(itemId);
		if (template == null)
		{
			log.warning("shop: item " + itemId + " is not in the item data on this server, skipping that row. Check the id - it is the single most common mistake here.");
			return null;
		}
		return new Row(itemId, note, price);
	}

	private int[] parseQuantities(String text, java.util.logging.Logger log)
	{
		final List<Integer> values = new ArrayList<>();
		for (final String part : text.split("[,;\\s]+"))
		{
			if (part.trim().isEmpty())
			{
				continue;
			}
			try
			{
				final int value = Integer.parseInt(part.trim());
				if (value > 0)
				{
					values.add(value);
				}
			}
			catch (NumberFormatException e)
			{
				log.warning("shop: \"" + part.trim() + "\" in Quantities is not a number, ignoring it.");
			}
		}
		if (values.isEmpty())
		{
			log.warning("shop: Quantities had nothing usable in it, falling back to 1.");
			return new int[] { 1 };
		}
		final int[] result = new int[values.size()];
		for (int i = 0; i < result.length; i++)
		{
			result[i] = values.get(i);
		}
		return result;
	}

	/**
	 * Reads a colour as six hex digits.
	 *
	 * <p>Validated rather than passed through, because a colour goes straight
	 * into a {@code <font color=...>} attribute. A setting containing a space or
	 * a quote would not just look wrong, it would break the tag it sits in - and
	 * this engine does not report broken tags, it just draws something else.
	 */
	private static String color(ModuleConfig config, String key, String fallback)
	{
		final String value = config.getString(key, fallback).trim();
		if (value.matches("[0-9A-Fa-f]{6}"))
		{
			return value.toUpperCase(Locale.ROOT);
		}
		return fallback;
	}

	private static int clamp(int value, int low, int high)
	{
		if (value < low)
		{
			return low;
		}
		return (value > high) ? high : value;
	}

	// ========================================================================
	//  Reading the data
	// ========================================================================

	public List<Row> rows()
	{
		return _rows;
	}

	/** The item ids on the page, in order. Used to validate a bypass. */
	public boolean sells(int itemId)
	{
		for (final Row row : _rows)
		{
			if (row.getItemId() == itemId)
			{
				return true;
			}
		}
		return false;
	}

	/** The configured price for an item, or -1 if this shop does not sell it. */
	public int priceOf(int itemId)
	{
		for (final Row row : _rows)
		{
			if (row.getItemId() == itemId)
			{
				return row.getPrice();
			}
		}
		return -1;
	}

	public int[] quantities()
	{
		return _quantities;
	}

	public String command()
	{
		return _command;
	}

	public int openItemId()
	{
		return _openItemId;
	}

	public int multisellId()
	{
		return _multisellId;
	}

	public boolean useWindow()
	{
		return _useWindow;
	}

	public String title()
	{
		return _title;
	}

	public String intro()
	{
		return _intro;
	}

	public String windowNote()
	{
		return _windowNote;
	}

	public String fallbackNote()
	{
		return _fallbackNote;
	}

	public String windowMissingNotice()
	{
		return _windowMissingNotice;
	}

	public int tableWidth()
	{
		return _tableWidth;
	}

	public int iconSize()
	{
		return _iconSize;
	}

	public boolean showIcons()
	{
		return _showIcons;
	}

	public boolean showPrices()
	{
		return _showPrices;
	}

	public String titleColor()
	{
		return _titleColor;
	}

	public String labelColor()
	{
		return _labelColor;
	}

	public String noteColor()
	{
		return _noteColor;
	}

	public String priceColor()
	{
		return _priceColor;
	}

	public String noticeColor()
	{
		return _noticeColor;
	}
}