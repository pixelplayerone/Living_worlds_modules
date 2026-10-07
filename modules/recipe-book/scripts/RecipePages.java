/*
 * Copyright (c) 2013 L2jMobius
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package modules.recipebook;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;

import modules.recipebook.RecipeIndex.Category;
import modules.recipebook.RecipeIndex.DropSource;
import modules.recipebook.RecipeIndex.Ingredient;
import modules.recipebook.RecipeIndex.QuestSource;
import modules.recipebook.RecipeIndex.Recipe;

/**
 * Builds the recipe book's Community Board pages as html strings. Pure: everything player-specific comes in through
 * {@link Env}, so the pages are unit-tested without a server. Every page carries the {@code %navigation%} marker the
 * board handler fills in with the board's own navigation column.
 */
public final class RecipePages
{
	public static final String CMD = "_bbs_recipebook";
	public static final int PAGE_SIZE = 10;
	public static final int DROP_PAGE_SIZE = 8;
	public static final int USE_PAGE_SIZE = 10;
	public static final int SEARCH_LIMIT = 14;
	public static final int MAX_GOALS = 15;

	private static final String GOLD = "CDB67F";
	private static final String GREEN = "66FF66";
	private static final String RED = "FF6666";
	private static final String GREY = "999999";

	/** Everything that depends on who is looking, supplied by the board handler. */
	public interface Env
	{
		/** @return how many of the item the player holds (inventory plus warehouse) */
		long have(int itemId);

		/** @return the drop chance in percent after the server's rates, capped at 100 */
		double chance(DropSource source, int itemId);

		/** @return the player's goal recipes, as scroll ids, in the order they were added */
		List<Integer> goals();

		/** @return {@code true} if the player already knows this recipe */
		boolean learned(Recipe recipe);
	}

	private final RecipeIndex _index;
	private final DecimalFormat _chance = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ROOT));

	public RecipePages(RecipeIndex index)
	{
		_index = index;
	}

	// ===== pages =====

	public String home(Env env)
	{
		final StringBuilder b = new StringBuilder();
		b.append("<tr><td height=8></td></tr>");
		b.append("<tr><td align=center><font color=\"").append(GOLD).append("\">Every recipe you can actually get: dropped by a monster or rewarded by a quest.</font></td></tr>");
		b.append("<tr><td height=8></td></tr>");
		b.append("<tr><td align=center><table><tr><td width=60>Search:</td><td><edit width=200 var=\"q\"></td>");
		b.append("<td><button value=\"Go\" action=\"bypass ").append(CMD).append(" search $q\" width=45 height=15 back=\"sek.cbui94\" fore=\"sek.cbui92\"></td>");
		b.append("</tr></table></td></tr>");
		b.append("<tr><td align=center><font color=\"").append(GREY).append("\">a recipe, a product or an ingredient name</font></td></tr>");
		b.append("<tr><td height=10></td></tr>");
		b.append("<tr><td align=center><table bgcolor=434343 width=470><tr><td width=90>Browse</td>");
		for (int g = 0; g < RecipeIndex.GRADES.length; g++)
		{
			b.append("<td width=60 align=center>").append(RecipeIndex.GRADES[g].equals("No Grade") ? "NG" : RecipeIndex.GRADES[g]).append("</td>");
		}
		b.append("</tr></table>");
		b.append("<table width=470>");
		for (Category c : Category.values())
		{
			b.append("<tr><td width=90><font color=\"").append(GOLD).append("\">").append(c.label()).append("</font></td>");
			for (int g = 0; g < RecipeIndex.GRADES.length; g++)
			{
				final int n = _index.count(c, g);
				b.append("<td width=60 align=center>");
				if (n > 0)
				{
					b.append("<a action=\"bypass ").append(CMD).append(" list ").append(c.name()).append(' ').append(g).append(" 1\">").append(n).append("</a>");
				}
				else
				{
					b.append("<font color=\"").append(GREY).append("\">-</font>");
				}
				b.append("</td>");
			}
			b.append("</tr>");
		}
		b.append("</table></td></tr>");
		b.append(buttonRow(btn("My Goals (" + env.goals().size() + ")", CMD + " goals")));
		b.append("<tr><td height=8></td></tr>");
		b.append("<tr><td align=center><a action=\"bypass ").append(CMD).append(" clear\">Clear map marker</a></td></tr>");
		b.append("<tr><td height=4></td></tr>");
		b.append("<tr><td align=center><font color=\"").append(GREY).append("\">").append(_index.size()).append(" recipes in the book</font></td></tr>");
		return frame("Recipe Book", b.toString());
	}

	public String list(Category category, int grade, int page, Env env)
	{
		final List<Recipe> all = _index.list(category, grade);
		final int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		final int p = Math.max(1, Math.min(pages, page));
		final StringBuilder b = new StringBuilder();
		b.append("<tr><td align=center><font color=\"").append(GOLD).append("\">").append(category.label()).append(" - ").append(gradeLabel(grade)).append(" (").append(all.size()).append(")</font></td></tr>");
		b.append("<tr><td height=6></td></tr><tr><td align=center>");
		b.append(recipeTable(all.subList((p - 1) * PAGE_SIZE, Math.min(all.size(), p * PAGE_SIZE)), env));
		b.append("</td></tr><tr><td height=8></td></tr><tr><td align=center>");
		b.append(pager(n -> CMD + " list " + category.name() + " " + grade + " " + n, p, pages));
		b.append("</td></tr><tr><td height=8></td></tr><tr><td align=center>").append(homeLink()).append("</td></tr>");
		return frame("Recipe Book", b.toString());
	}

	public String search(String query, Env env)
	{
		final List<Recipe> found = _index.search(query);
		final StringBuilder b = new StringBuilder();
		b.append("<tr><td align=center><font color=\"").append(GOLD).append("\">Search: ").append(esc(query == null ? "" : query.trim())).append("</font></td></tr>");
		b.append("<tr><td height=6></td></tr><tr><td align=center>");
		if (found.isEmpty())
		{
			b.append("Nothing in the book matches that. Recipes nobody can obtain are not listed.");
		}
		else
		{
			final List<Recipe> shown = found.subList(0, Math.min(SEARCH_LIMIT, found.size()));
			b.append(recipeTable(shown, env));
			if (found.size() > SEARCH_LIMIT)
			{
				b.append("<br><font color=\"").append(GREY).append("\">").append(found.size()).append(" matches, showing ").append(SEARCH_LIMIT).append(". Type more of the name to narrow it down.</font>");
			}
		}
		b.append("</td></tr><tr><td height=8></td></tr><tr><td align=center>").append(homeLink()).append("</td></tr>");
		return frame("Recipe Book", b.toString());
	}

	public String view(Recipe r, Env env)
	{
		final StringBuilder b = new StringBuilder();
		final boolean goal = env.goals().contains(r.scrollId);
		b.append("<tr><td align=center><font color=\"").append(GOLD).append("\">").append(esc(r.productName));
		if (r.productCount > 1)
		{
			b.append(" x").append(r.productCount);
		}
		b.append("</font></td></tr>");
		b.append("<tr><td align=center><font color=\"").append(GREY).append("\">").append(esc(_index.nameOf(r.scrollId))).append(env.learned(r) ? "  -  learned" : "").append("</font></td></tr>");
		b.append("<tr><td height=4></td></tr><tr><td align=center>");
		b.append("Craft level <font color=\"").append(GOLD).append("\">").append(r.craftLevel).append("</font>   ");
		b.append(r.dwarven ? "Dwarven" : "Common").append("   ");
		b.append("Success <font color=\"").append(GOLD).append("\">").append(r.successRate).append("%</font>");
		if (r.mpCost > 0)
		{
			b.append("   MP ").append(r.mpCost);
		}
		b.append("</td></tr><tr><td height=6></td></tr>");

		// Where to get the recipe itself.
		b.append("<tr><td align=center><font color=\"").append(GOLD).append("\">Where to get the recipe</font></td></tr><tr><td align=center>");
		for (QuestSource q : _index.questsOf(r.scrollId))
		{
			b.append(questLine("Quest: ", q));
		}
		final List<DropSource> drops = _index.dropsOf(r.scrollId);
		if (!drops.isEmpty())
		{
			b.append("<table>");
			for (int i = 0; i < Math.min(3, drops.size()); i++)
			{
				b.append(dropRow(drops.get(i), r.scrollId, env));
			}
			b.append("</table>");
			if (drops.size() > 3)
			{
				b.append("<a action=\"bypass ").append(CMD).append(" item ").append(r.scrollId).append(" 1\">all ").append(drops.size()).append(" sources</a>");
			}
		}
		b.append("</td></tr><tr><td height=6></td></tr>");

		// Ingredients with have / need.
		b.append("<tr><td align=center><font color=\"").append(GOLD).append("\">Ingredients</font></td></tr><tr><td align=center>");
		b.append("<table bgcolor=434343 width=440><tr><td width=270>Item</td><td width=60 align=right>Need</td><td width=70 align=right>Have</td></tr></table>");
		b.append("<table width=440>");
		for (Ingredient ingredient : r.ingredients)
		{
			final long have = env.have(ingredient.itemId());
			final String color = (have >= ingredient.count()) ? GREEN : RED;
			b.append("<tr><td width=270><a action=\"bypass ").append(CMD).append(" item ").append(ingredient.itemId()).append(" 1\">").append(esc(_index.nameOf(ingredient.itemId()))).append("</a></td>");
			b.append("<td width=60 align=right>").append(ingredient.count()).append("</td>");
			b.append("<td width=70 align=right><font color=\"").append(color).append("\">").append(have).append("</font></td></tr>");
		}
		b.append("</table></td></tr>");
		b.append(buttonRow(goal ? btn("Remove goal", CMD + " goal del " + r.scrollId) : btn("Add to goals", CMD + " goal add " + r.scrollId), btn("My Goals", CMD + " goals"), btn("Back", CMD + " list " + r.category.name() + " " + r.grade + " 1")));
		return frame("Recipe Book", b.toString());
	}

	public String item(int itemId, int page, Env env)
	{
		return item(itemId, page, 1, env);
	}

	public String item(int itemId, int page, int usePage, Env env)
	{
		final StringBuilder b = new StringBuilder();
		b.append("<tr><td align=center><font color=\"").append(GOLD).append("\">").append(esc(_index.nameOf(itemId))).append("</font></td></tr>");
		final Recipe taught = _index.recipe(itemId);
		if (taught != null)
		{
			b.append("<tr><td align=center>Teaches <a action=\"bypass ").append(CMD).append(" view ").append(itemId).append("\">").append(esc(taught.productName)).append("</a></td></tr>");
		}
		b.append("<tr><td height=6></td></tr><tr><td align=center><font color=\"").append(GOLD).append("\">Where to get it</font></td></tr><tr><td align=center>");
		final List<QuestSource> quests = _index.questsOf(itemId);
		for (QuestSource q : quests)
		{
			b.append(questLine("Quest reward: ", q));
		}
		final List<DropSource> drops = _index.dropsOf(itemId);
		if (drops.isEmpty() && quests.isEmpty())
		{
			b.append("No monster drops this. Look for it at a merchant, or craft it.");
		}
		else if (!drops.isEmpty())
		{
			final int pages = Math.max(1, (drops.size() + DROP_PAGE_SIZE - 1) / DROP_PAGE_SIZE);
			final int p = Math.max(1, Math.min(pages, page));
			b.append("<table bgcolor=434343 width=450><tr><td width=40>Lvl</td><td width=210>Monster</td><td width=80 align=right>Chance</td><td width=60 align=center>Type</td></tr></table>");
			b.append("<table width=450>");
			for (int i = (p - 1) * DROP_PAGE_SIZE; i < Math.min(drops.size(), p * DROP_PAGE_SIZE); i++)
			{
				b.append(dropRow(drops.get(i), itemId, env));
			}
			b.append("</table><br>").append(pager(n -> CMD + " item " + itemId + " " + n + " " + usePage, p, pages));
		}
		b.append("</td></tr>");

		final List<Recipe> uses = _index.usedIn(itemId);
		if (!uses.isEmpty())
		{
			final int usePages = Math.max(1, (uses.size() + USE_PAGE_SIZE - 1) / USE_PAGE_SIZE);
			final int up = Math.max(1, Math.min(usePages, usePage));
			final int dropPage = Math.max(1, page);
			b.append("<tr><td height=10></td></tr><tr><td align=center><font color=\"").append(GOLD).append("\">Used in ").append(uses.size()).append(uses.size() == 1 ? " recipe" : " recipes").append("</font></td></tr><tr><td align=center>");
			b.append("<table width=450>");
			for (int i = (up - 1) * USE_PAGE_SIZE; i < Math.min(uses.size(), up * USE_PAGE_SIZE); i++)
			{
				final Recipe u = uses.get(i);
				b.append("<tr><td width=40 align=center><font color=\"").append(GREY).append("\">").append(gradeShort(u.grade)).append("</font></td>");
				b.append("<td width=400><a action=\"bypass ").append(CMD).append(" view ").append(u.scrollId).append("\">").append(esc(u.productName)).append("</a></td></tr>");
			}
			b.append("</table>");
			if (usePages > 1)
			{
				b.append("<br>").append(pager(n -> CMD + " item " + itemId + " " + dropPage + " " + n, up, usePages));
			}
			b.append("</td></tr>");
		}
		b.append("<tr><td height=8></td></tr><tr><td align=center>").append(homeLink()).append("</td></tr>");
		return frame("Recipe Book", b.toString());
	}

	public String goals(Env env)
	{
		final List<Integer> ids = new ArrayList<>();
		for (int id : env.goals())
		{
			if (_index.recipe(id) != null)
			{
				ids.add(id);
			}
		}
		final StringBuilder b = new StringBuilder();
		b.append("<tr><td align=center><font color=\"").append(GOLD).append("\">My Goals (").append(ids.size()).append("/").append(MAX_GOALS).append(")</font></td></tr><tr><td height=6></td></tr>");
		if (ids.isEmpty())
		{
			b.append("<tr><td align=center>Nothing pinned yet. Open a recipe and press Add to goals.</td></tr>");
		}
		else
		{
			b.append("<tr><td align=center><table width=450>");
			for (int id : ids)
			{
				final Recipe r = _index.recipe(id);
				b.append("<tr><td width=36 align=center><font color=\"").append(GREY).append("\">").append(gradeShort(r.grade)).append("</font></td><td width=290><a action=\"bypass ").append(CMD).append(" view ").append(id).append("\">").append(esc(r.productName)).append("</a>").append(env.learned(r) ? " <font color=\"" + GREEN + "\">(learned)</font>" : "").append("</td>");
				b.append("<td width=80 align=right><a action=\"bypass ").append(CMD).append(" goal del ").append(id).append("\">remove</a></td></tr>");
			}
			b.append("</table></td></tr><tr><td height=8></td></tr>");

			// Recipes still to find (scroll not held and not learned).
			final StringBuilder find = new StringBuilder();
			for (int id : ids)
			{
				final Recipe r = _index.recipe(id);
				if (!env.learned(r) && (env.have(id) < 1))
				{
					find.append("<a action=\"bypass ").append(CMD).append(" item ").append(id).append(" 1\">").append(esc(_index.nameOf(id))).append("</a><br1>");
				}
			}
			if (find.length() > 0)
			{
				b.append("<tr><td align=center><font color=\"").append(GOLD).append("\">Recipes to find</font></td></tr><tr><td align=center>").append(find).append("</td></tr><tr><td height=6></td></tr>");
			}

			final Map<Integer, Long> totals = _index.totals(ids);
			final List<Integer> order = new ArrayList<>(totals.keySet());
			order.sort((x, y) ->
			{
				final long mx = Math.max(0, totals.get(x) - env.have(x));
				final long my = Math.max(0, totals.get(y) - env.have(y));
				return (mx != my) ? Long.compare(my, mx) : Integer.compare(x, y);
			});
			b.append("<tr><td align=center><font color=\"").append(GOLD).append("\">Shopping list</font></td></tr><tr><td align=center>");
			b.append("<table bgcolor=434343 width=450><tr><td width=190>Item</td><td width=70 align=right>Need</td><td width=70 align=right>Have</td><td width=80 align=right>Missing</td></tr></table><table width=450>");
			int shown = 0;
			for (int itemId : order)
			{
				if (shown++ >= 22)
				{
					break;
				}
				final long need = totals.get(itemId);
				final long have = env.have(itemId);
				final long missing = Math.max(0, need - have);
				b.append("<tr><td width=190><a action=\"bypass ").append(CMD).append(" item ").append(itemId).append(" 1\">").append(esc(_index.nameOf(itemId))).append("</a></td>");
				b.append("<td width=70 align=right>").append(need).append("</td><td width=70 align=right>").append(have).append("</td>");
				b.append("<td width=80 align=right><font color=\"").append(missing == 0 ? GREEN : RED).append("\">").append(missing == 0 ? "ok" : String.valueOf(missing)).append("</font></td></tr>");
			}
			b.append("</table>");
			if (order.size() > 22)
			{
				b.append("<font color=\"").append(GREY).append("\">+").append(order.size() - 22).append(" more items</font>");
			}
			b.append("</td></tr>").append(buttonRow(btn("Clear all", CMD + " goal clear")));
		}
		b.append("<tr><td height=8></td></tr><tr><td align=center>").append(homeLink()).append("</td></tr>");
		return frame("Recipe Book", b.toString());
	}

	public String notice(String message)
	{
		return frame("Recipe Book", "<tr><td height=30></td></tr><tr><td align=center>" + esc(message) + "</td></tr><tr><td height=10></td></tr><tr><td align=center>" + homeLink() + "</td></tr>");
	}

	// ===== pieces =====

	private String recipeTable(List<Recipe> recipes, Env env)
	{
		final StringBuilder b = new StringBuilder();
		b.append("<table bgcolor=434343 width=450><tr><td width=36 align=center>Gr</td><td width=244>Recipe</td><td width=40 align=center>Lvl</td><td width=50 align=center>%</td><td width=60 align=center>From</td></tr></table>");
		b.append("<table width=450>");
		for (Recipe r : recipes)
		{
			final boolean quest = !_index.questsOf(r.scrollId).isEmpty();
			final boolean drop = !_index.dropsOf(r.scrollId).isEmpty();
			b.append("<tr><td width=36 align=center><font color=\"").append(GREY).append("\">").append(gradeShort(r.grade)).append("</font></td>");
			b.append("<td width=244><a action=\"bypass ").append(CMD).append(" view ").append(r.scrollId).append("\">").append(esc(r.productName)).append("</a>");
			if (env.learned(r))
			{
				b.append(" <font color=\"").append(GREEN).append("\">*</font>");
			}
			b.append("</td><td width=40 align=center>").append(r.craftLevel).append("</td><td width=50 align=center>").append(r.successRate).append("</td>");
			b.append("<td width=60 align=center>").append(drop && quest ? "Drop+Q" : (quest ? "Quest" : "Drop")).append("</td></tr>");
		}
		b.append("</table>");
		return b.toString();
	}

	private String dropRow(DropSource d, int itemId, Env env)
	{
		final StringBuilder b = new StringBuilder();
		b.append("<tr><td width=40>").append(d.level()).append("</td><td width=210><a action=\"bypass ").append(CMD).append(" trace ").append(d.npcId()).append("\">").append(esc(d.npcName())).append("</a>");
		if (d.raid())
		{
			b.append(" <font color=\"").append(RED).append("\">(boss)</font>");
		}
		b.append("</td><td width=80 align=right>").append(_chance.format(env.chance(d, itemId))).append("%</td><td width=60 align=center>").append(d.spoil() ? "Spoil" : "Drop").append("</td></tr>");
		return b.toString();
	}

	/** One page-turner line. {@code cmd} builds the full bypass for a given page number. */
	private static String pager(IntFunction<String> cmd, int page, int pages)
	{
		if (pages <= 1)
		{
			return "";
		}
		final StringBuilder b = new StringBuilder();
		b.append(page > 1 ? "<a action=\"bypass " + cmd.apply(page - 1) + "\">&lt;&lt; prev</a>" : "<font color=\"" + GREY + "\">&lt;&lt; prev</font>");
		b.append("    Page ").append(page).append(" / ").append(pages).append("    ");
		b.append(page < pages ? "<a action=\"bypass " + cmd.apply(page + 1) + "\">next &gt;&gt;</a>" : "<font color=\"" + GREY + "\">next &gt;&gt;</font>");
		return b.toString();
	}

	/** A board-style button (same look as the navigation column), one fixed size so rows line up. */
	private static String btn(String label, String bypass)
	{
		return "<button value=\"" + label + "\" action=\"bypass " + bypass + "\" width=110 height=26 back=\"L2UI_CH3.Button.bigbutton2_down\" fore=\"L2UI_CH3.Button.bigbutton2\">";
	}

	/** Buttons side by side in equal cells, with breathing room above and below. */
	private static String buttonRow(String... buttons)
	{
		final StringBuilder b = new StringBuilder("<tr><td height=10></td></tr><tr><td align=center><table><tr>");
		for (String button : buttons)
		{
			b.append("<td width=120 align=center>").append(button).append("</td>");
		}
		return b.append("</tr></table></td></tr><tr><td height=10></td></tr>").toString();
	}

	private static String homeLink()
	{
		return "<a action=\"bypass " + CMD + "\">Recipe Book home</a>";
	}

	private static String gradeLabel(int grade)
	{
		return (grade == 0) ? "No Grade" : (RecipeIndex.GRADES[grade] + "-grade");
	}

	private static String gradeShort(int grade)
	{
		return (grade == 0) ? "NG" : RecipeIndex.GRADES[grade];
	}

	/** One quest line; the name is a link that marks the starting NPC on the map when we know who that is. */
	private static String questLine(String prefix, QuestSource q)
	{
		// Two lines: "Quest: Name (Q123)" then "Starts with NPC". The whole first line is the link, because the client
		// breaks the line around every link and would otherwise put the prefix, name and number on separate lines.
		final String label = esc(prefix + q.name() + " (Q" + q.questId() + ")");
		final StringBuilder b = new StringBuilder();
		if (q.startNpcId() > 0)
		{
			b.append("<a action=\"bypass ").append(CMD).append(" trace ").append(q.startNpcId()).append("\">").append(label).append("</a><br1>");
			b.append("Starts with ").append(esc(q.startNpcName())).append("<br1>");
		}
		else
		{
			b.append(label).append("<br1>");
		}
		return b.toString();
	}

	/** Escapes the characters the client's html parser treats as markup. */
	static String esc(String text)
	{
		if (text == null)
		{
			return "";
		}
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	private static String frame(String title, String body)
	{
		return "<html><body><table width=500><tr><td height=10></td></tr></table>"
			+ "<table width=10><tr><td>%navigation%</td><td><center>"
			+ "<table border=0 bgcolor=\"000000\" cellpadding=0 cellspacing=0 width=500 height=415>"
			+ "<tr><td height=15></td></tr><tr><td height=25 align=\"center\"><font color=\"" + GOLD + "\">" + title + "</font></td></tr>"
			+ "<tr><td><center><img src=\"L2UI.SquareGray\" width=500 height=1></center></td></tr>"
			+ body
			+ "<tr><td height=14></td></tr></table>"
			+ "<table border=0 bgcolor=\"000000\" cellpadding=0 cellspacing=0 width=500><tr><td height=20 align=center><font color=696969>LINEAGE II - COMMUNITY BOARD</font></td></tr></table>"
			+ "</center></td></tr></table></body></html>";
	}
}
