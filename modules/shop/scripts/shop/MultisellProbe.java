/*
 * MultisellProbe.java
 *
 * Decides, once at startup, whether the native shop window is actually usable,
 * and explains itself when it is not.
 *
 * WHY THIS EXISTS
 *
 * The window button is the nice part of this module, and it fails in a way that
 * is very hard to read from the outside. Two of its failure modes are silent:
 *
 *   - a list with no <npc>-1</npc> opens for a GM and for nobody else, so the
 *     module looks fine while being tested and broken in production;
 *   - a list whose prices have drifted from the config sells at whichever is
 *     cheaper, which is a revenue bug nobody notices until they notice.
 *
 * So the list is read and judged here, at boot, and anything wrong with it is
 * logged in plain words. When the answer is no, the page draws its own quantity
 * buttons instead and the shop keeps working - a bad list costs the window, not
 * the shop.
 *
 * WHY IT PARSES THE FILE BY HAND INSTEAD OF ASKING THE CORE
 *
 * MultisellData can tell us a list loaded. It cannot tell us that the list is
 * priced for ordinary players, because that decision is made at send time
 * against a player, not at load time. Reading the file is the only way to check
 * the thing that actually breaks.
 */
package shop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.l2jmobius.gameserver.modules.ModulesConfig;

/**
 * Startup check on the configured multisell list.
 *
 * <p>Read-only and best effort. Every failure is a log line and a {@code false},
 * never an exception: a shop that cannot check its own window should still open.
 */
public final class MultisellProbe
{
	/** This module's directory name under the modules root. */
	private static final String MODULE_DIR = "shop";

	/**
	 * Adena. Every price in this module and in the sample list is in Adena
	 * because that is what {@code reduceAdena} and {@code getAdena} operate on;
	 * a different currency would need a different purchase path, not just a
	 * different number here.
	 */
	private static final int ADENA_ID = 57;

	private static final Pattern ROW = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
	private static final Pattern PRODUCTION = Pattern.compile("<production\\s+([^>]*)/?>");
	private static final Pattern INGREDIENT = Pattern.compile("<ingredient\\s+([^>]*)/?>");
	private static final Pattern COUNT = Pattern.compile("count=\"(\\d+)\"");
	private static final Pattern ID = Pattern.compile("id=\"(\\d+)\"");

	private MultisellProbe()
	{
	}

	/**
	 * Checks the configured list and reports what it found.
	 *
	 * @return true if the window may be offered to players
	 */
	public static boolean check(ShopCatalogue catalogue, java.util.logging.Logger log)
	{
		final int listId = catalogue.multisellId();
		if (!catalogue.useWindow())
		{
			log.info("shop: UseWindow is false, so the shop window button will not be drawn. The page still sells.");
			return false;
		}
		if (listId <= 0)
		{
			log.info("shop: MultisellId is 0, so the page sells directly and the native shop window is not used. This is a supported setup, not a degraded one.");
			return false;
		}
		final File root = ModulesConfig.getModulesRoot();
		if (root == null)
		{
			// Only reachable if the module framework is half-initialised. Not worth
			// an exception at startup.
			log.warning("shop: the modules root is not available, so multisell " + listId + " was not checked. The page will sell directly.");
			return false;
		}
		final File list = new File(root, MODULE_DIR + File.separator + "data" + File.separator + "multisell" + File.separator + listId + ".xml");
		if (!list.isFile())
		{
			log.warning("shop: multisell " + listId + " is not on disk at " + list.getPath() + ", so the window button will not be drawn. Remember the list id IS the file name, and MultisellId in module.ini has to match it. The page will sell directly.");
			return false;
		}
		final String xml;
		try
		{
			xml = new String(Files.readAllBytes(list.toPath()), StandardCharsets.UTF_8);
		}
		catch (Exception e)
		{
			log.warning("shop: could not read " + list.getPath() + " (" + e + "), so the window button will not be drawn. The page will sell directly.");
			return false;
		}
		// The single most important check in this file. See the comments in the
		// sample list for what MultisellData.separateAndSend does with it.
		if (!xml.contains("<npc>-1</npc>"))
		{
			log.warning("shop: " + list.getName() + " has no <npc>-1</npc> entry. MultisellData.separateAndSend tests isNpcAllowed(-1) first and refuses the list for every player who is not a GM, so the window would open for you during testing and for nobody else. Add the sentinel. The page will sell directly until you do.");
			return false;
		}

		final Matcher rows = ROW.matcher(xml);
		int found = 0;
		int priced = 0;
		int drifted = 0;
		int configured = 0;
		while (rows.find())
		{
			found++;
			final String row = rows.group(1);
			final Matcher productionTag = PRODUCTION.matcher(row);
			if (!productionTag.find())
			{
				log.warning("shop: a row in " + list.getName() + " has no <production> id and will be dropped by the core at load.");
				continue;
			}
			final Integer production = integer(ID, productionTag.group(1));
			if (production == null)
			{
				continue;
			}
			final Integer cost = adenaCost(row);
			if (cost == null)
			{
				log.warning("shop: the row producing item " + production + " is not priced in Adena (id " + ADENA_ID + "), so the window will refuse every purchase of it.");
				continue;
			}
			priced++;
			// Compare with the config only for items this shop actually lists. A
			// shared module's list is allowed to carry rows for other merchants.
			final int configuredPrice = catalogue.priceOf(production);
			if (configuredPrice < 0)
			{
				continue;
			}
			configured++;
			if ((configuredPrice > 0) && (cost != configuredPrice))
			{
				drifted++;
				log.warning("shop: price drift. " + list.getName() + " sells item " + production + " for " + cost + " Adena but module.ini says " + configuredPrice + ". The two paths charge different amounts and a player pays whichever is cheaper. Change both, or drop the row from the list and let the page's own buttons handle it.");
			}
		}
		if (found == 0)
		{
			log.warning("shop: " + list.getName() + " has no <item> rows, so the window would open empty.");
			return false;
		}
		if (priced == 0)
		{
			log.warning("shop: no row in " + list.getName() + " is priced in Adena, so the window cannot complete a purchase. The page will sell directly.");
			return false;
		}
		if (drifted > 0)
		{
			// Not fatal. Both paths work; they just disagree, which is worth saying
			// loudly but is not a reason to take the window away.
			log.warning("shop: multisell " + listId + " checked, " + found + " row(s), but " + drifted + " of " + configured + " configured item(s) disagree with module.ini. Both work; fix the prices so they match.");
		}
		else
		{
			log.info("shop: multisell " + listId + " checked, " + found + " row(s), all priced in Adena and matching module.ini.");
		}
		return true;
	}

	/**
	 * The Adena cost of one multisell row, or null if it is not priced in Adena.
	 *
	 * <p>A row can carry several ingredients; the Adena one is the price. If there
	 * is no Adena ingredient the row is not a purchase this module can describe,
	 * and saying so is more useful than guessing.
	 */
	private static Integer adenaCost(String row)
	{
		final Matcher ingredients = INGREDIENT.matcher(row);
		while (ingredients.find())
		{
			final String attributes = ingredients.group(1);
			final Integer id = integer(ID, attributes);
			final Integer count = integer(COUNT, attributes);
			if ((id != null) && (count != null) && (id == ADENA_ID))
			{
				return count;
			}
		}
		return null;
	}

	private static Integer integer(Pattern pattern, String text)
	{
		final Matcher matcher = pattern.matcher(text);
		if (!matcher.find())
		{
			return null;
		}
		try
		{
			return Integer.valueOf(matcher.group(1));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}
}