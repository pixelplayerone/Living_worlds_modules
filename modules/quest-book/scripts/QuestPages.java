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
package modules.questbook;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

import modules.questbook.QuestIndex.Quest;

/** Builds the Community Board html for the quest book. Pure: all player and server facts arrive through {@link Env}. */
public final class QuestPages
{
	public static final String CMD = "_bbs_questbook";
	public static final int PAGE_SIZE = 12;
	public static final int SEARCH_LIMIT = 14;
	public static final int MAX_KILLS_SHOWN = 8;
	public static final int MAX_REWARDS_SHOWN = 10;

	public static final int STATUS_NONE = 0;
	public static final int STATUS_ACTIVE = 1;
	public static final int STATUS_DONE = 2;

	/** Level brackets offered on the front page. {from, to}; the last one is open ended. */
	static final int[][] BRACKETS =
	{
		{ 1, 19 },
		{ 20, 29 },
		{ 30, 39 },
		{ 40, 49 },
		{ 50, 59 },
		{ 60, 69 },
		{ 70, 85 }
	};

	private static final String GOLD = "CDB67F";
	private static final String GREEN = "66FF66";
	private static final String GREY = "999999";
	private static final String ORANGE = "FFB266";

	/** Everything that depends on who is looking or on the running server. */
	public interface Env
	{
		String itemName(int itemId);

		String npcName(int npcId);

		/** @return one of the STATUS_ constants for this player */
		int status(Quest quest);

		int playerLevel();

		/** @return ids of the quests the player currently has in progress */
		List<Integer> activeIds();
	}

	private final QuestIndex _index;
	private final NumberFormat _number = NumberFormat.getIntegerInstance(Locale.ROOT);

	public QuestPages(QuestIndex index)
	{
		_index = index;
	}

	// ===== pages =====

	public String home(Env env)
	{
		final StringBuilder b = new StringBuilder();
		b.append("<tr><td height=8></td></tr>");
		b.append("<tr><td align=center><font color=\"").append(GOLD).append("\">Every quest on this server: who gives it, what level, what it pays.</font></td></tr>");
		b.append("<tr><td height=8></td></tr>");
		b.append("<tr><td align=center><table><tr><td width=60>Search:</td><td><edit width=200 var=\"q\"></td>");
		b.append("<td><button value=\"Go\" action=\"bypass ").append(CMD).append(" search $q\" width=45 height=15 back=\"sek.cbui94\" fore=\"sek.cbui92\"></td>");
		b.append("</tr></table></td></tr>");
		b.append("<tr><td align=center><font color=\"").append(GREY).append("\">a quest name, or its number like 663</font></td></tr>");
		b.append("<tr><td height=10></td></tr>");
		b.append("<tr><td align=center><table bgcolor=434343 width=470><tr><td align=center>Browse by minimum level</td></tr></table>");
		b.append("<table width=470><tr>");
		for (int[] br : BRACKETS)
		{
			final int n = _index.byLevel(br[0], br[1]).size();
			b.append("<td width=67 align=center><a action=\"bypass ").append(CMD).append(" list ").append(br[0]).append(' ').append(br[1]).append(" 1\">").append(br[0]).append('-').append(br[1]).append("</a><br1><font color=\"").append(GREY).append("\">").append(n).append("</font></td>");
		}
		b.append("</tr></table>");
		final int unknown = _index.byLevel(0, 0).size();
		if (unknown > 0)
		{
			b.append("<a action=\"bypass ").append(CMD).append(" list 0 0 1\">Level not stated (").append(unknown).append(")</a>");
		}
		b.append("</td></tr>");
		final int lvl = env.playerLevel();
		final int from = Math.max(1, lvl - 6);
		b.append(buttonRow(btn("For my level", CMD + " list " + from + " " + lvl + " 1"), btn("Active (" + env.activeIds().size() + ")", CMD + " active"), btn("Clear map marker", CMD + " clear")));
		b.append("<tr><td align=center><font color=\"").append(GREY).append("\">").append(_index.size()).append(" quests. Levels and rewards are read from the quest scripts.</font></td></tr>");
		return frame("Quest Book", b.toString());
	}

	public String list(int from, int to, int page, Env env)
	{
		final List<Quest> all = _index.byLevel(from, to);
		final int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		final int p = Math.max(1, Math.min(pages, page));
		final StringBuilder b = new StringBuilder();
		final String title = (from == 0) ? "Level not stated" : ("Level " + from + (to > from ? "-" + to : ""));
		b.append("<tr><td height=6></td></tr><tr><td align=center><font color=\"").append(GOLD).append("\">").append(title).append("</font>  (").append(all.size()).append(" quests)</td></tr>");
		b.append("<tr><td height=4></td></tr><tr><td align=center>");
		if (all.isEmpty())
		{
			b.append("No quests here.");
		}
		else
		{
			b.append(table(all.subList((p - 1) * PAGE_SIZE, Math.min(all.size(), p * PAGE_SIZE)), env));
		}
		b.append("</td></tr><tr><td height=6></td></tr><tr><td align=center>").append(pager(n -> CMD + " list " + from + " " + to + " " + n, p, pages)).append("</td></tr>");
		b.append(buttonRow(btn("Quest Book", CMD)));
		return frame("Quest Book", b.toString());
	}

	public String search(String text, Env env)
	{
		final List<Quest> hits = _index.search(text, SEARCH_LIMIT);
		final StringBuilder b = new StringBuilder("<tr><td height=6></td></tr><tr><td align=center>");
		if (hits.isEmpty())
		{
			b.append("Nothing matches \"").append(esc(text)).append("\".");
		}
		else
		{
			b.append("<font color=\"").append(GOLD).append("\">Matches for \"").append(esc(text)).append("\"</font><br>").append(table(hits, env));
			if (hits.size() >= SEARCH_LIMIT)
			{
				b.append("<br><font color=\"").append(GREY).append("\">Showing the first ").append(SEARCH_LIMIT).append(". Type more of the name to narrow it down.</font>");
			}
		}
		b.append("</td></tr>").append(buttonRow(btn("Quest Book", CMD)));
		return frame("Quest Book", b.toString());
	}

	public String active(Env env)
	{
		final StringBuilder b = new StringBuilder("<tr><td height=6></td></tr><tr><td align=center><font color=\"").append(GOLD).append("\">Quests in progress</font></td></tr><tr><td height=4></td></tr><tr><td align=center>");
		final java.util.ArrayList<Quest> mine = new java.util.ArrayList<>();
		for (int id : env.activeIds())
		{
			final Quest q = _index.quest(id);
			if (q != null)
			{
				mine.add(q);
			}
		}
		if (mine.isEmpty())
		{
			b.append("You have no quests in progress.");
		}
		else
		{
			b.append(table(mine, env));
		}
		b.append("</td></tr>").append(buttonRow(btn("Quest Book", CMD)));
		return frame("Quest Book", b.toString());
	}

	public String view(Quest q, Env env)
	{
		final StringBuilder b = new StringBuilder();
		b.append("<tr><td height=6></td></tr><tr><td align=center><font color=\"").append(GOLD).append("\">").append(esc(q.name)).append("</font>  (Q").append(q.id).append(")</td></tr>");
		b.append("<tr><td align=center>");
		b.append(q.minLevel > 0 ? "Level " + q.minLevel + "+" : "Level not stated");
		b.append("   ").append(repeatLabel(q));
		b.append("   ").append(statusLabel(env.status(q)));
		b.append("</td></tr><tr><td height=8></td></tr>");

		b.append("<tr><td align=center><font color=\"").append(GOLD).append("\">Who gives it</font></td></tr><tr><td align=center>");
		if (q.startNpcs.length == 0)
		{
			b.append("The script names no starting NPC.");
		}
		for (int i = 0; i < Math.min(3, q.startNpcs.length); i++)
		{
			b.append(npcLink(q.startNpcs[i], env)).append("<br1>");
		}
		if (q.startNpcs.length > 0)
		{
			b.append("<font color=\"").append(GREY).append("\">click a name to mark it on your map</font>");
		}
		b.append("</td></tr><tr><td height=8></td></tr>");

		if (q.kills.length > 0)
		{
			b.append("<tr><td align=center><font color=\"").append(GOLD).append("\">Monsters involved</font></td></tr><tr><td align=center>");
			for (int i = 0; i < Math.min(MAX_KILLS_SHOWN, q.kills.length); i++)
			{
				b.append(npcLink(q.kills[i], env)).append("<br1>");
			}
			if (q.kills.length > MAX_KILLS_SHOWN)
			{
				b.append("<font color=\"").append(GREY).append("\">and ").append(q.kills.length - MAX_KILLS_SHOWN).append(" more</font>");
			}
			b.append("</td></tr><tr><td height=8></td></tr>");
		}

		b.append("<tr><td align=center><font color=\"").append(GOLD).append("\">Rewards</font></td></tr><tr><td align=center>");
		boolean any = false;
		if (q.exp > 0)
		{
			b.append(_number.format(q.exp)).append(" Exp, ").append(_number.format(q.sp)).append(" SP<br1>");
			any = true;
		}
		for (int i = 0; i < Math.min(MAX_REWARDS_SHOWN, q.rewards.length); i++)
		{
			b.append(esc(env.itemName(q.rewards[i][0])));
			if (q.rewards[i][1] > 1)
			{
				b.append(" x").append(_number.format(q.rewards[i][1]));
			}
			b.append("<br1>");
			any = true;
		}
		if (!any)
		{
			b.append("The script lists no plain item or exp reward.");
		}
		else
		{
			b.append("<font color=\"").append(GREY).append("\">Some quests pay one of several choices; all are listed.</font>");
		}
		b.append("</td></tr>");
		final String back = (q.minLevel == 0) ? CMD + " list 0 0 1" : CMD;
		b.append(buttonRow(btn("Active", CMD + " active"), btn("Quest Book", back)));
		return frame("Quest Book", b.toString());
	}

	public String notice(String message)
	{
		return frame("Quest Book", "<tr><td height=30></td></tr><tr><td align=center>" + esc(message) + "</td></tr>" + buttonRow(btn("Quest Book", CMD)));
	}

	// ===== pieces =====

	private String table(List<Quest> quests, Env env)
	{
		final StringBuilder b = new StringBuilder();
		b.append("<table bgcolor=434343 width=470><tr><td width=50>Lvl</td><td width=320>Quest</td><td width=100 align=center>Status</td></tr></table>");
		b.append("<table width=470>");
		for (Quest q : quests)
		{
			b.append("<tr><td width=50>").append(q.minLevel > 0 ? Integer.toString(q.minLevel) : "?").append("</td>");
			b.append("<td width=320><a action=\"bypass ").append(CMD).append(" view ").append(q.id).append("\">").append(esc(q.name)).append("</a></td>");
			b.append("<td width=100 align=center>").append(statusLabel(env.status(q))).append("</td></tr>");
		}
		return b.append("</table>").toString();
	}

	private String npcLink(int npcId, Env env)
	{
		return "<a action=\"bypass " + CMD + " trace " + npcId + "\">" + esc(env.npcName(npcId)) + "</a>";
	}

	private static String statusLabel(int status)
	{
		switch (status)
		{
			case STATUS_ACTIVE:
				return "<font color=\"" + ORANGE + "\">In progress</font>";
			case STATUS_DONE:
				return "<font color=\"" + GREEN + "\">Done</font>";
			default:
				return "<font color=\"" + GREY + "\">-</font>";
		}
	}

	private static String repeatLabel(Quest q)
	{
		switch (q.repeat)
		{
			case QuestIndex.REPEAT_YES:
				return "Repeatable";
			case QuestIndex.REPEAT_NO:
				return "One time";
			default:
				return "";
		}
	}

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

	private static String btn(String label, String bypass)
	{
		return "<button value=\"" + label + "\" action=\"bypass " + bypass + "\" width=110 height=26 back=\"L2UI_CH3.Button.bigbutton2_down\" fore=\"L2UI_CH3.Button.bigbutton2\">";
	}

	private static String buttonRow(String... buttons)
	{
		final StringBuilder b = new StringBuilder("<tr><td height=10></td></tr><tr><td align=center><table><tr>");
		for (String button : buttons)
		{
			b.append("<td width=120 align=center>").append(button).append("</td>");
		}
		return b.append("</tr></table></td></tr><tr><td height=10></td></tr>").toString();
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
