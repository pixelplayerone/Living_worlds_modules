/*
 * TamingData.java
 *
 * Which creatures may be tamed, what it costs, and how hard it is.
 *
 * The original build read a data/taming/TamingData.xml file for per-species
 * overrides. That file is empty by default and every rule it could express was
 * already covered by the fallbacks, so the rules live in this module's own
 * config/module.ini instead. That keeps the module self-contained: no second
 * file to keep in step, and no path outside the module folder to find.
 */
package taming;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.modules.ModuleConfig;
import org.l2jmobius.gameserver.modules.ModulesConfig;

public class TamingData
{
	/**
	 * The module's own directory name under the modules root, used only to find the
	 * reagent list back for the price check at startup.
	 */
	private static final String MODULE_DIR = "taming";

	/** Adena's item id. The only currency a reagent row may be priced in. */
	private static final int ADENA_ID = 57;

	private final Map<Integer, TamingEntry> _entries = new HashMap<>();

	private int _normalItem = 9300;
	private int _raidItem = 9301;
	private int _normalPrice = 10000;
	private int _raidPrice = 250000;
	private int _reagentMultisellId = 9300;
	private boolean _multisellUsable;
	private double _normalChance = 35.0;
	private double _raidChance = 5.0;
	private int _levelBonus = 5;
	private int _levelCap = 80;
	private double _hpWeight = 0.4;
	private double _levelGapPenalty = 2.0;
	private double _chanceCap = 95.0;
	private double _chanceFloor = 0.0;
	private double _normalChanceCap = 10.0;
	private double _raidChanceCap = 1.0;

	private TamingData()
	{
	}

	/** Reads every rule from the module config. Called once, when the module starts. */
	public void configure(ModuleConfig config, Logger log)
	{
		_normalItem = config.getInt("NormalTamingItem", 9300);
		_raidItem = config.getInt("RaidTamingItem", 9301);
		_normalPrice = config.getInt("NormalTamingPrice", 10000);
		_raidPrice = config.getInt("RaidTamingPrice", 250000);
		_reagentMultisellId = config.getInt("ReagentMultisellId", 9300);
		_normalChance = config.getDouble("NormalChance", 35.0);
		_raidChance = config.getDouble("RaidChance", 5.0);
		_levelBonus = config.getInt("LevelBonus", 5);
		_levelCap = config.getInt("LevelCap", 80);
		_hpWeight = config.getDouble("WoundedHpWeight", 0.4);
		_levelGapPenalty = config.getDouble("LevelGapPenalty", 2.0);
		_chanceCap = config.getDouble("ChanceCap", 95.0);
		_chanceFloor = config.getDouble("ChanceFloor", 0.0);
		_normalChanceCap = config.getDouble("NormalChanceCap", 10.0);
		_raidChanceCap = config.getDouble("RaidChanceCap", 1.0);

		// Per-species overrides, listed by id so startup does not have to probe
		// every creature. For each listed id, add a matching section:
		//
		//   [Override.25044]
		//   Tier = RAID
		//   BaseChance = 10.0
		//   RequiredItem = 9301
		//   MaxPetLevel = 25
		_entries.clear();
		int added = 0;
		for (int npcId : parseIds(config.getString("OverrideIds", "")))
		{
			final String section = "Override." + npcId;
			_entries.put(npcId, new TamingEntry(npcId, String.valueOf(npcId), config.getString(section + ".Tier", "NORMAL").trim().toUpperCase(), config.getDouble(section + ".BaseChance", _normalChance), config.getInt(section + ".RequiredItem", _normalItem), config.getInt(section + ".MaxPetLevel", Math.min(_levelCap, npcId + _levelBonus))));
			added++;
		}
		log.info("taming rules: normal " + _normalChance + "% with item " + _normalItem + ", raid " + _raidChance + "% with item " + _raidItem + ", prices " + _normalPrice + "/" + _raidPrice + ", cap source+" + _levelBonus + " bounded at " + _levelCap + ", " + added + " species override(s)");
	}

	/** The reagent used on ordinary creatures, and what the Pet Managers charge for it. */
	public int getNormalItem()
	{
		return _normalItem;
	}

	public int getNormalPrice()
	{
		return _normalPrice;
	}

	/** The reagent used on raid creatures, and what the Pet Managers charge for it. */
	public int getRaidItem()
	{
		return _raidItem;
	}

	public int getRaidPrice()
	{
		return _raidPrice;
	}

	/**
	 * The id of the reagent list behind the store's OPEN SHOP button.
	 *
	 * <p>The list id is the list file's name: MultisellData.parseDocument reads it
	 * from the file name, not from the XML, so this and the file have to agree.
	 */
	public int getReagentMultisellId()
	{
		return _reagentMultisellId;
	}

	/**
	 * Whether the shop window is expected to work, decided once at startup.
	 *
	 * <p>True only if the list was found, carries the &lt;npc&gt;-1&lt;/npc&gt; entry
	 * without which the core refuses it for every ordinary player, and has at least
	 * one row this module recognises.
	 *
	 * <p>The store uses it to decide what to draw. If the window is expected to work
	 * the page offers the window alone, which is the better surface: it has the
	 * client's own quantity box and the core's MultiSellChoose transaction. If the
	 * list is missing or unusable the page falls back to drawing the reagents with
	 * the module's own quantity buttons, so a broken list costs the window and not
	 * the store. Nothing here re-checks the list at runtime; this is a startup
	 * reading, and the worst it can be wrong about is which page gets drawn.
	 */
	public boolean isMultisellUsable()
	{
		return _multisellUsable;
	}

	/**
	 * Compares the reagent list's prices against this module's, and reports drift.
	 *
	 * <p>A reagent price lives in two files because it has to. The store's own
	 * buttons charge NormalTamingPrice and RaidTamingPrice from the config; the
	 * window charges whatever the multisell row says, because Adena has to be an
	 * ingredient of a row and the row is the only place that can be expressed. Two
	 * copies of one number will drift the first time somebody edits only one of
	 * them, and the symptom would be the window quietly selling at whichever is
	 * cheaper. So it is checked at startup and the row is named in the warning.
	 *
	 * <p>Also reports a row whose <production> id is not one of the two configured
	 * reagents, which is what a reagent id renamed in the config alone looks like
	 * from here. A row is dropped by the core at load if its production item does
	 * not exist, so that shows up as a reagent missing from the window.
	 *
	 * <p>Read-only and best effort: a missing or unreadable list is reported and
	 * disables only the window. The buttons keep working and nothing here is fatal,
	 * because a store that cannot price-check itself should still open.
	 */
	public void verifyMultisellPrices(Logger log)
	{
		_multisellUsable = false;
		final File modulesRoot = ModulesConfig.getModulesRoot();
		if (modulesRoot == null)
		{
			// Only reachable if the framework is half-initialised. Nothing here is
			// worth an exception at startup, and the store works without the window.
			log.warning("taming: the modules root is not available, so the store window's prices were not checked against the config. The store's own buttons still work.");
			return;
		}
		final File list = new File(modulesRoot, MODULE_DIR + File.separator + "data" + File.separator + "multisell" + File.separator + _reagentMultisellId + ".xml");
		if (!list.isFile())
		{
			log.warning("taming: reagent list " + list.getPath() + " is missing, so the store window button will not open. The store's own buttons still work.");
			return;
		}
		final String xml;
		try
		{
			xml = new String(Files.readAllBytes(list.toPath()), StandardCharsets.UTF_8);
		}
		catch (Exception e)
		{
			// Reported, never thrown: an unreadable list is a warning, not a reason
			// to refuse a player their reagents.
			log.warning("taming: could not read " + list.getPath() + " (" + e + "), so the store window's prices were not checked against the config. The store's own buttons still work.");
			return;
		}
		if (!xml.contains("<npc>-1</npc>"))
		{
			// Without the sentinel the core refuses the list for every ordinary player
			// and only a GM can open it, which is the bug this list exists to avoid.
			log.warning("taming: " + list.getName() + " has no <npc>-1</npc> entry, so MultisellData will refuse the store window for every player who is not a GM. The window button needs it; see the comments in that file.");
			return;
		}
		final Matcher rows = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL).matcher(xml);
		int found = 0;
		int bad = 0;
		int usable = 0;
		while (rows.find())
		{
			found++;
			final String row = rows.group(1);
			final Integer production = attribute(tagAttributes(row, "production"), "id");
			final Integer cost = adenaCost(row);
			if (production == null)
			{
// Deliberately not claiming what the core does with the row. MultisellData.verify
				// reports a production it cannot resolve; whether the row then loads, is
				// skipped, or is left to fail at purchase was not established by reading
				// the core, so it is not asserted here.
				log.warning("taming: a row in " + list.getName() + " has no <production> id, which MultisellData will report against at load.");
				bad++;
				continue;
			}
			final int expected;
			if (production == _normalItem)
			{
				expected = _normalPrice;
			}
			else if (production == _raidItem)
			{
				expected = _raidPrice;
			}
			else
			{
				log.warning("taming: a row in " + list.getName() + " produces item " + production + ", which is neither NormalTamingItem (" + _normalItem + ") nor RaidTamingItem (" + _raidItem + "). Rename it in both this module's items and the list.");
				bad++;
				continue;
			}
			if (cost == null)
			{
				log.warning("taming: the row producing item " + production + " in " + list.getName() + " is not priced in Adena (id " + ADENA_ID + "), so the window will refuse every purchase of it.");
				bad++;
			}
			else if (cost != expected)
			{
				log.warning("taming: price drift. " + list.getName() + " sells item " + production + " for " + cost + " Adena but the config says " + expected + " (NormalTamingPrice/RaidTamingPrice). Players pay whichever is lower; change both files together.");
				bad++;
			}
			else
			{
				// Parsed, sentinel present, priced in Adena and matching the config.
				// This is the only condition under which the window is offered.
				usable++;
			}
		}
		if (found == 0)
		{
			log.warning("taming: " + list.getName() + " has no <item> rows, so the store window would open empty.");
		}
		if (usable > 0)
		{
			_multisellUsable = true;
		}
		if (bad == 0)
		{
			log.info("taming: reagent list " + _reagentMultisellId + " checked, " + found + " row(s) at " + _normalPrice + "/" + _raidPrice + " Adena, matching the config.");
		}
		if (!_multisellUsable)
		{
			log.warning("taming: the shop window is not usable (" + (found - usable) + " of " + found + " row(s) rejected). The store page will fall back to its own quantity buttons.");
		}
	}

	/** The Adena total of one multisell row, or null if it is not priced in Adena. */
	private static Integer adenaCost(String row)
	{
		final Matcher ingredients = Pattern.compile("<ingredient\\s+([^>]*?)/?>").matcher(row);
		while (ingredients.find())
		{
			final String attributes = ingredients.group(1);
			final Integer id = attribute(attributes, "id");
			if ((id == null) || (id != ADENA_ID))
			{
				continue;
			}
			return attribute(attributes, "count");
		}
		return null;
	}

	/** The attribute text of the first self-closing tag of this kind, or null. */
	private static String tagAttributes(String row, String tag)
	{
		final Matcher matcher = Pattern.compile("<" + tag + "\\s+([^>]*?)/?>").matcher(row);
		return matcher.find() ? matcher.group(1) : null;
	}

	/**
	 * One integer attribute, by name rather than by position.
	 *
	 * <p>Attribute order is not fixed in either file, so
	 * {@code <production count="1" id="9300"/>} and
	 * {@code <production id="9300" count="1"/>} both have to parse. The leading
	 * {@code \b} stops {@code id} matching the tail of a longer name.
	 */
	private static Integer attribute(String attributes, String name)
	{
		if (attributes == null)
		{
			return null;
		}
		final Matcher matcher = Pattern.compile("\\b" + name + "\\s*=\\s*\"(\\d+)\"").matcher(attributes);
		return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
	}

private static Set<Integer> parseIds(String value)
	{
		final Set<Integer> ids = new LinkedHashSet<>();
		for (String part : value.split("[,;\\s]+"))
		{
			if (part.isEmpty())
			{
				continue;
			}
			try
			{
				ids.add(Integer.valueOf(part.trim()));
			}
			catch (NumberFormatException e)
			{
				// A typo in the list must not stop the module from starting.
			}
		}
		return ids;
	}

	public double getHpWeight()
	{
		return _hpWeight;
	}

	public double getLevelGapPenalty()
	{
		return _levelGapPenalty;
	}

	public double getChanceCap()
	{
		return _chanceCap;
	}

	/**
	 * The chance is never allowed below this, so a level gap large enough to drive the
	 * attempt negative becomes flatly impossible rather than wrapping.
	 *
	 * <p>Deliberately 0 rather than the 1 this used to be hardcoded to. A floor of 1 quietly
	 * raised any attempt computed below it, which made a sub-1% base unreachable and, worse,
	 * meant a raid more than a few levels above the player still had a 1% floor to sit on -
	 * so the level-gap penalty could never actually make a raid untameable. At 0 the penalty
	 * does what it says.
	 */
	public double getChanceFloor()
	{
		return _chanceFloor;
	}

	/**
	 * The ceiling for an ordinary creature, which is a flat roll - the chance is this number
	 * whether the target is at full health or nearly dead and whether it is above or below the
	 * player.
	 *
	 * <p>Kept separate from {@link #getChanceCap()} because the two tiers now behave
	 * differently enough that one global cap cannot describe both: raids take modifiers and
	 * stop at {@link #getRaidChanceCap()}, ordinary creatures take nothing at all.
	 */
	public double getNormalChanceCap()
	{
		return _normalChanceCap;
	}

	/**
	 * The ceiling for a raid. The wounded and level-gap modifiers still apply below it, so a
	 * weakened raid is easier than a fresh one but can never climb past this.
	 */
	public double getRaidChanceCap()
	{
		return _raidChanceCap;
	}

	/**
	 * The rule for one creature. An explicit override wins; otherwise any regular
	 * monster can be attempted, and raids are harder and cost the rarer item.
	 */
	public TamingEntry getEntry(Monster monster)
	{
		final TamingEntry override = _entries.get(monster.getId());
		if (override != null)
		{
			return override;
		}
		final boolean raid = monster.isRaid();
		return new TamingEntry(monster.getId(), monster.getName(), raid ? "RAID" : "NORMAL", raid ? _raidChance : _normalChance, raid ? _raidItem : _normalItem, Math.min(_levelCap, monster.getLevel() + _levelBonus));
	}

	public static TamingData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		private static final TamingData INSTANCE = new TamingData();
	}
}
