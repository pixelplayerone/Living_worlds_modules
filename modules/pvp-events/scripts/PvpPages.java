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
package modules.pvpevents;

import java.util.List;
import java.util.Locale;

/** The Community Board pages of the event module. */
final class PvpPages
{
	static final String CMD = "_bbs_pvpevent";
	private static final String GOLD = "CDB67F";
	private static final String GREY = "999999";
	private static final String BLUE = "6699FF";
	private static final String RED = "E06060";
	private static final String GREEN = "99CC66";

	/** What is running right now, for the status block. */
	static final class Live
	{
		String scenario;
		boolean preparing;
		int secondsLeft;
		int blueKills;
		int redKills;
		int killLimit;
		boolean youIn;
		boolean ffa;
		String topName;
		int topKills;
	}

	/** The outcome of an event that has ended. */
	static final class Result
	{
		String scenario;
		/** True for blue, false for red, null for a draw. */
		Boolean winnerBlue;
		String reason;
		int seconds;
		PvpScore score;
		boolean youIn;
		boolean youBlue;
		long reward;
		boolean ffa;
		/** FFA: the winner's name, or null for none. */
		String winnerName;
		boolean youWon;
	}

	private PvpPages()
	{
	}

	static String home(List<PvpScenario> scenarios, List<String> broken, String arena, Live live, boolean hasLast, boolean botsAvailable)
	{
		final StringBuilder b = new StringBuilder();
		b.append("<tr><td align=center><font color=\"").append(GREY).append("\">Arena: </font>").append(esc(arena == null ? "none found" : arena)).append("</td></tr>");
		if (!botsAvailable)
		{
			b.append("<tr><td height=6></td></tr><tr><td align=center><font color=\"").append(RED).append("\">Bots are off. Turn on FakePlayers and PhantomPvpEnabled in config/Custom/FakePlayers.ini.</font></td></tr>");
		}
		if (live != null)
		{
			b.append("<tr><td height=6></td></tr><tr><td align=center><font color=\"").append(GOLD).append("\">Running: ").append(esc(live.scenario)).append("</font></td></tr>");
			b.append("<tr><td align=center>").append(live.preparing ? "Starting in " + live.secondsLeft + "s" : live.secondsLeft + "s left");
			if (live.ffa)
			{
				b.append("   ").append(live.topKills > 0 ? "Top: " + esc(clip(live.topName, 14)) + " " + live.topKills : "No kills yet");
			}
			else
			{
				b.append("   <font color=\"").append(BLUE).append("\">Blue ").append(live.blueKills).append("</font> - <font color=\"").append(RED).append("\">Red ").append(live.redKills).append("</font>");
			}
			b.append(live.killLimit > 0 ? "   <font color=\"" + GREY + "\">first to " + live.killLimit + "</font>" : "").append("</td></tr>");
			b.append("<tr><td height=4></td></tr>").append(buttons(button("Refresh", CMD + " home"), button("Stop event", CMD + " stop")));
		}
		b.append("<tr><td height=8></td></tr><tr><td align=center><font color=\"").append(GOLD).append("\">Scenarios</font></td></tr>");
		if (scenarios.isEmpty())
		{
			b.append("<tr><td align=center><font color=\"").append(GREY).append("\">No scenario is configured.</font></td></tr>");
		}
		else
		{
			b.append("<tr><td align=center><table width=470>");
			for (int i = 0; i < scenarios.size(); i++)
			{
				final PvpScenario s = scenarios.get(i);
				final String role = (s.you == 0) ? "watch" : (s.ffa ? "<font color=\"" + GREEN + "\">you: in</font>" : (s.you == 1) ? "<font color=\"" + BLUE + "\">you: blue</font>" : "<font color=\"" + RED + "\">you: red</font>");
				b.append("<tr><td width=170>").append(esc(clip(s.name, 24))).append("</td><td width=55>").append(s.shape()).append("</td><td width=95>").append(role).append("</td><td width=70>")
					.append(live == null ? "<button value=\"Start\" action=\"bypass " + CMD + " start " + i + "\" width=60 height=22 back=\"L2UI_CH3.Button.bigbutton2_down\" fore=\"L2UI_CH3.Button.bigbutton2\">" : "").append("</td></tr>");
			}
			b.append("</table></td></tr>");
		}
		for (String error : broken)
		{
			b.append("<tr><td align=center><font color=\"").append(RED).append("\">").append(esc(clip(error, 70))).append("</font></td></tr>");
		}
		if (hasLast)
		{
			b.append("<tr><td height=8></td></tr>").append(buttons(button("Last result", CMD + " last")));
		}
		return frame("PvP Events", b.toString());
	}

	static String result(Result r)
	{
		final StringBuilder b = new StringBuilder();
		b.append("<tr><td align=center><font color=\"").append(GOLD).append("\">").append(esc(r.scenario)).append("</font></td></tr>");
		if (r.ffa)
		{
			final String win = (r.winnerName == null) ? "<font color=\"" + GREY + "\">No winner</font>" : "<font color=\"" + GOLD + "\">" + esc(clip(r.winnerName, 20)) + " wins</font>";
			b.append("<tr><td align=center>").append(win).append("   <font color=\"").append(GREY).append("\">").append(esc(r.reason)).append(", ").append(time(r.seconds)).append("</font></td></tr>");
			if (r.youIn && (r.winnerName != null))
			{
				b.append("<tr><td align=center>").append(r.youWon ? "<font color=\"" + GREEN + "\">You won.</font>" : "<font color=\"" + RED + "\">You lost.</font>").append(r.reward > 0 ? " Reward: " + adena(r.reward) : "").append("</td></tr>");
			}
			b.append("<tr><td height=6></td></tr>");
			table(b, "Fighters", GOLD, topRows(r.score.rows(true), 16));
			b.append("<tr><td height=6></td></tr>").append(buttons(button("Back", CMD + " home")));
			return frame("PvP Events", b.toString());
		}
		final String win = (r.winnerBlue == null) ? "<font color=\"" + GREY + "\">Draw</font>" : (r.winnerBlue ? "<font color=\"" + BLUE + "\">Blue wins</font>" : "<font color=\"" + RED + "\">Red wins</font>");
		b.append("<tr><td align=center>").append(win).append("   <font color=\"").append(GREY).append("\">").append(esc(r.reason)).append(", ").append(time(r.seconds)).append("</font></td></tr>");
		b.append("<tr><td align=center><font color=\"").append(BLUE).append("\">Blue ").append(r.score.kills(true)).append("</font> - <font color=\"").append(RED).append("\">Red ").append(r.score.kills(false)).append("</font></td></tr>");
		if (r.youIn && (r.winnerBlue != null))
		{
			final boolean won = r.winnerBlue.booleanValue() == r.youBlue;
			b.append("<tr><td align=center>").append(won ? "<font color=\"" + GREEN + "\">You won.</font>" : "<font color=\"" + RED + "\">You lost.</font>").append(r.reward > 0 ? " Reward: " + adena(r.reward) : "").append("</td></tr>");
		}
		b.append("<tr><td height=6></td></tr>");
		table(b, "Blue", BLUE, r.score.rows(true));
		table(b, "Red", RED, r.score.rows(false));
		b.append("<tr><td height=6></td></tr>").append(buttons(button("Back", CMD + " home")));
		return frame("PvP Events", b.toString());
	}

	/** The first {@code max} rows, plus the player's own row if it is further down (the client page limit is 8192 characters). */
	static List<PvpScore.Entry> topRows(List<PvpScore.Entry> rows, int max)
	{
		if (rows.size() <= max)
		{
			return rows;
		}
		final List<PvpScore.Entry> out = new java.util.ArrayList<>(rows.subList(0, max));
		for (int i = max; i < rows.size(); i++)
		{
			if (!rows.get(i).bot)
			{
				out.add(rows.get(i));
			}
		}
		return out;
	}

	private static void table(StringBuilder b, String title, String color, List<PvpScore.Entry> rows)
	{
		// 430 wide in a 500 frame: the board's scroll bar takes the right edge, so a wider table gets its last column clipped.
		b.append("<tr><td align=center><table width=430><tr><td width=98><font color=\"").append(color).append("\">").append(title).append("</font></td><td width=72><font color=\"").append(GREY).append("\">role</font></td><td width=24 align=right><font color=\"")
			.append(GREY).append("\">K</font></td><td width=24 align=right><font color=\"").append(GREY).append("\">D</font></td><td width=70 align=right><font color=\"").append(GREY).append("\">dealt</font></td><td width=70 align=right><font color=\"").append(GREY)
			.append("\">taken</font></td><td width=70 align=right><font color=\"").append(GREY).append("\">healed</font></td></tr>");
		for (PvpScore.Entry e : rows)
		{
			b.append("<tr><td>").append(e.bot ? "" : "<font color=\"" + GREEN + "\">").append(esc(clip(e.name, 11))).append(e.bot ? "" : "</font>").append("</td><td><font color=\"").append(GREY).append("\">").append(esc(clip(e.label, 9))).append("</font></td><td align=right>")
				.append(e.kills).append("</td><td align=right>").append(e.deaths).append("</td><td align=right>").append(compact(e.dealt)).append("</td><td align=right>").append(compact(e.taken)).append("</td><td align=right>").append(compact(e.healed)).append("</td></tr>");
		}
		b.append("</table></td></tr>");
	}

	/** 842 stays 842, 12,345 becomes 12.3k, 1,234,567 becomes 1.23m: short enough to fit its column. */
	static String compact(long n)
	{
		final long a = Math.abs(n);
		if (a < 10000)
		{
			return Long.toString(n);
		}
		if (a < 1000000)
		{
			return String.format(Locale.ROOT, "%.1fk", n / 1000.0);
		}
		return String.format(Locale.ROOT, "%.2fm", n / 1000000.0);
	}

	static String notice(String text)
	{
		return frame("PvP Events", "<tr><td height=20></td></tr><tr><td align=center>" + esc(text) + "</td></tr><tr><td height=10></td></tr>" + buttons(button("Back", CMD + " home")));
	}

	private static String button(String label, String bypass)
	{
		return "<button value=\"" + label + "\" action=\"bypass " + bypass + "\" width=110 height=26 back=\"L2UI_CH3.Button.bigbutton2_down\" fore=\"L2UI_CH3.Button.bigbutton2\">";
	}

	private static String buttons(String... buttons)
	{
		final StringBuilder b = new StringBuilder("<tr><td align=center><table><tr>");
		for (String button : buttons)
		{
			b.append("<td>").append(button).append("</td>");
		}
		return b.append("</tr></table></td></tr>").toString();
	}

	static String time(int seconds)
	{
		return (seconds / 60) + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
	}

	static String num(long n)
	{
		final String digits = Long.toString(Math.abs(n));
		final StringBuilder b = new StringBuilder();
		for (int i = 0; i < digits.length(); i++)
		{
			if ((i > 0) && (((digits.length() - i) % 3) == 0))
			{
				b.append(',');
			}
			b.append(digits.charAt(i));
		}
		return ((n < 0) ? "-" : "") + b;
	}

	static String adena(long n)
	{
		return num(n) + "a";
	}

	static String clip(String text, int max)
	{
		if (text == null)
		{
			return "";
		}
		return (text.length() <= max) ? text : (text.substring(0, Math.max(0, max - 1)) + "~");
	}

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
