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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One event setup, read from a config line: {@code Name | you, 1xhealer, 2xwarrior vs 5xany | kills=15, minutes=4, respawn=off}.
 * <p>
 * A side is a comma list. {@code you} is the player (one side only). Anything else is {@code [N x] what}, where what is
 * a role (tank, warrior, archer, dagger, monk, singer, dancer, nuker, healer, buffer, bounty, or any) or a class name
 * such as Gladiator or "Phoenix Knight". Options: {@code kills=N} (first team to N kills wins), {@code minutes=N} (time
 * limit), {@code respawn=on|off} (off: last team standing wins), {@code queue=on|off} (the bots facing you come one at a time).
 */
final class PvpScenario
{
	static final Set<String> ROLES = new HashSet<>(Arrays.asList("tank", "warrior", "archer", "dagger", "monk", "singer", "dancer", "nuker", "healer", "buffer", "bounty", "any"));

	/** N fighters of one role or class. */
	static final class Slot
	{
		final String token;
		final int count;

		Slot(String token, int count)
		{
			this.token = token;
			this.count = count;
		}

		boolean isRole()
		{
			return ROLES.contains(token);
		}
	}

	final String name;
	final List<Slot> blue = new ArrayList<>();
	final List<Slot> red = new ArrayList<>();
	/** 0: the player is not in the event, 1: on blue, 2: on red. */
	int you;
	int kills; // 0: use the default
	int minutes; // 0: use the default
	int respawn = -1; // -1: use the default, 0: off, 1: on
	/** The bots on the side without 'you' fight one at a time: the rest wait until the one before has fallen. */
	boolean queue;
	/** With {@link #queue}: both sides send one fighter at a time. */
	boolean queueBoth;
	/** One side only, everyone against everyone. All fighters are listed on {@link #blue}. */
	boolean ffa;
	String error;

	private PvpScenario(String name)
	{
		this.name = name;
	}

	int botsOn(boolean isBlue)
	{
		int n = 0;
		for (Slot s : isBlue ? blue : red)
		{
			n += s.count;
		}
		return n;
	}

	int sizeOf(boolean isBlue)
	{
		return botsOn(isBlue) + (((you == 1) && isBlue) || ((you == 2) && !isBlue) ? 1 : 0);
	}

	/** @return {@code "3 v 5"} style label, counting the player */
	String shape()
	{
		if (ffa)
		{
			return sizeOf(true) + " FFA";
		}
		return sizeOf(true) + " v " + sizeOf(false);
	}

	/**
	 * @param line the config value
	 * @param maxPerSide the most fighters one side may have, the player included
	 * @return the scenario; check {@link #error}
	 */
	static PvpScenario parse(String line, int maxPerSide)
	{
		return parse(line, maxPerSide, 30);
	}

	/**
	 * @param maxFfa the most fighters a free-for-all may have, the player included
	 */
	static PvpScenario parse(String line, int maxPerSide, int maxFfa)
	{
		final String[] parts = (line == null) ? new String[0] : line.split("\\|");
		if (parts.length < 2)
		{
			return failed("", "needs a name and two sides: Name | you vs 3xany");
		}
		final String name = parts[0].trim();
		final PvpScenario s = new PvpScenario(name);
		if (name.isEmpty())
		{
			s.error = "has no name";
			return s;
		}
		final String[] sides = parts[1].toLowerCase(Locale.ROOT).split("\\bvs\\b");
		if (sides.length > 2)
		{
			s.error = "needs exactly one 'vs' between the two sides";
			return s;
		}
		s.ffa = sides.length == 1;
		for (int i = 0; i < sides.length; i++)
		{
			for (String raw : sides[i].split(","))
			{
				final String t = raw.trim();
				if (t.isEmpty())
				{
					continue;
				}
				if (t.equals("you"))
				{
					if (s.you != 0)
					{
						s.error = "has 'you' more than once";
						return s;
					}
					s.you = i + 1;
					continue;
				}
				int count = 1;
				String what = t;
				final int x = t.indexOf('x');
				if ((x > 0) && t.substring(0, x).trim().matches("\\d{1,3}"))
				{
					count = Integer.parseInt(t.substring(0, x).trim());
					what = t.substring(x + 1).trim();
				}
				if ((count < 1) || what.isEmpty())
				{
					s.error = "has a bad entry '" + t + "'";
					return s;
				}
				(i == 0 ? s.blue : s.red).add(new Slot(what, count));
			}
		}
		if (s.ffa)
		{
			if (s.sizeOf(true) < 2)
			{
				s.error = "needs at least two fighters";
				return s;
			}
			if (s.sizeOf(true) > maxFfa)
			{
				s.error = "has more than " + maxFfa + " fighters";
				return s;
			}
		}
		else if ((s.sizeOf(true) < 1) || (s.sizeOf(false) < 1))
		{
			s.error = "has an empty side";
			return s;
		}
		if (!s.ffa && ((s.sizeOf(true) > maxPerSide) || (s.sizeOf(false) > maxPerSide)))
		{
			s.error = "has more than " + maxPerSide + " on a side";
			return s;
		}
		if (parts.length > 2)
		{
			for (String raw : parts[2].toLowerCase(Locale.ROOT).split(","))
			{
				final String o = raw.trim();
				if (o.isEmpty())
				{
					continue;
				}
				final String[] kv = o.split("=");
				if (kv.length != 2)
				{
					s.error = "has a bad option '" + o + "'";
					return s;
				}
				final String key = kv[0].trim();
				final String val = kv[1].trim();
				try
				{
					if (key.equals("kills"))
					{
						s.kills = Math.max(1, Math.min(999, Integer.parseInt(val)));
					}
					else if (key.equals("minutes"))
					{
						s.minutes = Math.max(1, Math.min(60, Integer.parseInt(val)));
					}
					else if (key.equals("respawn") && (val.equals("on") || val.equals("off")))
					{
						s.respawn = val.equals("on") ? 1 : 0;
					}
					else if (key.equals("queue") && (val.equals("on") || val.equals("off") || val.equals("both")))
					{
						s.queue = !val.equals("off");
						s.queueBoth = val.equals("both");
					}
					else
					{
						s.error = "has an unknown option '" + o + "'";
						return s;
					}
				}
				catch (NumberFormatException e)
				{
					s.error = "has a bad number in '" + o + "'";
					return s;
				}
			}
		}
		return s;
	}

	private static PvpScenario failed(String name, String error)
	{
		final PvpScenario s = new PvpScenario(name);
		s.error = error;
		return s;
	}

	/** Strips spaces, underscores and dashes and lowercases, so "Phoenix Knight" matches PHOENIX_KNIGHT. */
	static String squash(String text)
	{
		return (text == null) ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]", "");
	}
}
