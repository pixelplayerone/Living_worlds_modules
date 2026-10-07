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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** The quest dataset: pure data and lookups, no server classes, so it can be unit tested on its own. */
public final class QuestIndex
{
	public static final int REPEAT_NO = 0;
	public static final int REPEAT_YES = 1;
	public static final int REPEAT_UNKNOWN = 2;

	/** One quest as read from its script. */
	public static final class Quest
	{
		public final int id;
		public final String script;
		public final String name;
		/** Minimum level, or 0 when the script does not say. */
		public final int minLevel;
		public final int repeat;
		public final int[] startNpcs;
		public final int[] kills;
		/** Reward items as {itemId, count}; count 0 means the script did not give a plain number. */
		public final int[][] rewards;
		public final long exp;
		public final long sp;

		public Quest(int id, String script, String name, int minLevel, int repeat, int[] startNpcs, int[] kills, int[][] rewards, long exp, long sp)
		{
			this.id = id;
			this.script = script;
			this.name = name;
			this.minLevel = minLevel;
			this.repeat = repeat;
			this.startNpcs = startNpcs;
			this.kills = kills;
			this.rewards = rewards;
			this.exp = exp;
			this.sp = sp;
		}
	}

	private final Map<Integer, Quest> _byId = new TreeMap<>();
	private final List<Quest> _all = new ArrayList<>();

	public QuestIndex(List<Quest> quests)
	{
		for (Quest q : quests)
		{
			if (_byId.put(q.id, q) == null)
			{
				_all.add(q);
			}
		}
		_all.sort((a, b) -> (a.minLevel != b.minLevel) ? Integer.compare(a.minLevel, b.minLevel) : a.name.compareToIgnoreCase(b.name));
	}

	/** Builds the index from the generated {@link QuestData} rows. */
	public static QuestIndex fromRows(String[][] rows)
	{
		final List<Quest> list = new ArrayList<>();
		for (String[] r : rows)
		{
			try
			{
				final List<int[]> rewards = new ArrayList<>();
				for (String pair : r[7].isEmpty() ? new String[0] : r[7].split(","))
				{
					final String[] kv = pair.split(":");
					rewards.add(new int[]
					{
						Integer.parseInt(kv[0]),
						Integer.parseInt(kv[1])
					});
				}
				list.add(new Quest(Integer.parseInt(r[0]), r[1], r[2], Integer.parseInt(r[3]), Integer.parseInt(r[4]), ints(r[5]), ints(r[6]), rewards.toArray(new int[0][]), Long.parseLong(r[8]), Long.parseLong(r[9])));
			}
			catch (RuntimeException e)
			{
				// A malformed generated row costs one quest, never the whole book.
			}
		}
		return new QuestIndex(list);
	}

	private static int[] ints(String csv)
	{
		if (csv.isEmpty())
		{
			return new int[0];
		}
		final String[] parts = csv.split(",");
		final int[] out = new int[parts.length];
		for (int i = 0; i < parts.length; i++)
		{
			out[i] = Integer.parseInt(parts[i].trim());
		}
		return out;
	}

	public int size()
	{
		return _all.size();
	}

	public Quest quest(int id)
	{
		return _byId.get(id);
	}

	/** Quests whose minimum level is within [from, to], ordered by level then name. Level 0 (unknown) is {@code from == to == 0}. */
	public List<Quest> byLevel(int from, int to)
	{
		final List<Quest> out = new ArrayList<>();
		for (Quest q : _all)
		{
			if ((q.minLevel >= from) && (q.minLevel <= to))
			{
				out.add(q);
			}
		}
		return out;
	}

	/** Case-insensitive name search; matches on the quest name or on "Q123"/"123". */
	public List<Quest> search(String text, int limit)
	{
		final String needle = text.trim().toLowerCase(Locale.ROOT);
		if (needle.isEmpty())
		{
			return Collections.emptyList();
		}
		final String digits = needle.startsWith("q") ? needle.substring(1) : needle;
		final List<Quest> out = new ArrayList<>();
		for (Quest q : _all)
		{
			if (q.name.toLowerCase(Locale.ROOT).contains(needle) || (!digits.isEmpty() && digits.chars().allMatch(Character::isDigit) && Integer.toString(q.id).equals(digits)))
			{
				out.add(q);
				if (out.size() >= limit)
				{
					break;
				}
			}
		}
		return out;
	}
}
