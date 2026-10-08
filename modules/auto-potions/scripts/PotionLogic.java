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
package modules.autopotions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToLongFunction;

/** The potion toggle's rules: one player's settings, the chat command, and which potion to drink. Pure, so it can be unit tested on its own. */
final class PotionLogic
{
	static final int CP = 0;
	static final int HP = 1;
	static final int MP = 2;
	static final String[] NAMES =
	{
		"CP",
		"HP",
		"MP"
	};

	/** What {@link #choose} found. */
	static final int NONE_OWNED = -2;
	static final int ALL_ON_COOLDOWN = -1;

	private PotionLogic()
	{
	}

	/** One player's toggle: on or off, and the percent each kind is drunk below (0 = never). */
	static final class Settings
	{
		boolean on;
		final int[] percent = new int[3];
		/** set once a kind has run out so the warning is not repeated every tick; cleared when it recovers */
		final boolean[] warned = new boolean[3];

		Settings(int cp, int hp, int mp)
		{
			percent[CP] = clamp(cp);
			percent[HP] = clamp(hp);
			percent[MP] = clamp(mp);
		}

		String status()
		{
			final StringBuilder b = new StringBuilder("Potions are ").append(on ? "ON" : "OFF").append(".");
			for (int k = 0; k < 3; k++)
			{
				b.append(' ').append(NAMES[k]).append(' ').append((percent[k] <= 0) ? "off" : ("below " + percent[k] + "%")).append(k < 2 ? "," : "");
			}
			return b.toString();
		}
	}

	static int clamp(int percent)
	{
		return Math.max(0, Math.min(100, percent));
	}

	/** @return {@code true} if a stat at this percent is under the line and should be topped up */
	static boolean needs(int currentPercent, int thresholdPercent)
	{
		return (thresholdPercent > 0) && (currentPercent < thresholdPercent);
	}

	/**
	 * Applies the chat command: {@code ""} toggles, {@code on}, {@code off}, {@code status}, or {@code hp 60},
	 * {@code mp off}, {@code cp 95} to change a line.
	 * @return the message to show the player
	 */
	static String apply(Settings s, String params)
	{
		final String p = (params == null) ? "" : params.trim().toLowerCase();
		if (p.isEmpty())
		{
			s.on = !s.on;
			return s.status();
		}
		if (p.equals("on") || p.equals("off"))
		{
			s.on = p.equals("on");
			return s.status();
		}
		if (p.equals("status"))
		{
			return s.status();
		}
		final String[] a = p.split("\\s+");
		if (a.length == 2)
		{
			int kind = -1;
			for (int k = 0; k < 3; k++)
			{
				if (NAMES[k].toLowerCase().equals(a[0]))
				{
					kind = k;
				}
			}
			if (kind >= 0)
			{
				if (a[1].equals("off"))
				{
					s.percent[kind] = 0;
					return s.status();
				}
				try
				{
					final int v = Integer.parseInt(a[1].replace("%", ""));
					if ((v < 1) || (v > 100))
					{
						return "Use a percent from 1 to 100, or off.";
					}
					s.percent[kind] = v;
					return s.status();
				}
				catch (NumberFormatException e)
				{
					return "Use a percent from 1 to 100, or off.";
				}
			}
		}
		return "Potions: .pots (toggle)   .pots on / off / status   .pots hp 60   .pots mp 50   .pots cp 95   (a kind can be set to off)";
	}

	/** @return the three lines as {@code "cp,hp,mp"} (0 = off), for storing with the player */
	static String encode(Settings s)
	{
		return s.percent[CP] + "," + s.percent[HP] + "," + s.percent[MP];
	}

	/** Restores lines saved by {@link #encode}; anything that is not three numbers leaves the lines as they are. */
	static void decode(Settings s, String saved)
	{
		if (saved == null)
		{
			return;
		}
		final String[] a = saved.trim().split(",");
		if (a.length != 3)
		{
			return;
		}
		final int[] v = new int[3];
		try
		{
			for (int k = 0; k < 3; k++)
			{
				v[k] = Integer.parseInt(a[k].trim());
				if ((v[k] < 0) || (v[k] > 100))
				{
					return;
				}
			}
		}
		catch (NumberFormatException e)
		{
			return;
		}
		for (int k = 0; k < 3; k++)
		{
			s.percent[k] = v[k];
		}
	}

	/** @return the longest of the waits that keep a potion from being drunk (its own reuse, its shared group, its skill); stamps that are absent count as 0 */
	static long reuseLeft(long itemMs, long groupMs, long skillMs)
	{
		return Math.max(0, Math.max(itemMs, Math.max(groupMs, skillMs)));
	}

	/** @return the potion ids from a comma list, in order, ignoring anything that is not a number */
	static int[] parseIds(String csv)
	{
		final List<Integer> ids = new ArrayList<>();
		if (csv != null)
		{
			for (String part : csv.split(","))
			{
				try
				{
					final int id = Integer.parseInt(part.trim());
					if (id > 0)
					{
						ids.add(id);
					}
				}
				catch (NumberFormatException e)
				{
					// skip it
				}
			}
		}
		final int[] out = new int[ids.size()];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = ids.get(i);
		}
		return out;
	}

	/**
	 * Picks the potion to drink: the first one in the priority list that the player has and that is not on reuse, so a
	 * potion that is cooling down never blocks the next one.
	 * @param ids potion item ids, best first
	 * @param count how many of an id the player carries
	 * @param reuseLeftMs how long until an id can be used again
	 * @return the item id, {@link #ALL_ON_COOLDOWN} if the player has some but none can be used yet, or {@link #NONE_OWNED}
	 */
	static int choose(int[] ids, IntToLongFunction count, IntToLongFunction reuseLeftMs)
	{
		boolean owned = false;
		for (int id : ids)
		{
			if (count.applyAsLong(id) <= 0)
			{
				continue;
			}
			owned = true;
			if (reuseLeftMs.applyAsLong(id) <= 0)
			{
				return id;
			}
		}
		return owned ? ALL_ON_COOLDOWN : NONE_OWNED;
	}

	/** @return the ids the player carries that are off reuse right now, in list order; each can be drunk this tick */
	static int[] ready(int[] ids, IntToLongFunction count, IntToLongFunction reuseLeftMs)
	{
		final List<Integer> out = new ArrayList<>();
		for (int id : ids)
		{
			if ((count.applyAsLong(id) > 0) && (reuseLeftMs.applyAsLong(id) <= 0))
			{
				out.add(id);
			}
		}
		final int[] r = new int[out.size()];
		for (int i = 0; i < r.length; i++)
		{
			r[i] = out.get(i);
		}
		return r;
	}

	/** @return {@code true} if the player carries at least one of the ids */
	static boolean owns(int[] ids, IntToLongFunction count)
	{
		for (int id : ids)
		{
			if (count.applyAsLong(id) > 0)
			{
				return true;
			}
		}
		return false;
	}
}
