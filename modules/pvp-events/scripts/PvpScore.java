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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The score of one event: kills, deaths and damage for every fighter. Thread safe. */
final class PvpScore
{
	/** One fighter's line. */
	static final class Entry
	{
		final int id;
		final String name;
		final boolean blue;
		final boolean bot;
		final String label;
		int kills;
		int deaths;
		long dealt;
		long taken;
		long healed;

		Entry(int id, String name, boolean blue, boolean bot, String label)
		{
			this.id = id;
			this.name = name;
			this.blue = blue;
			this.bot = bot;
			this.label = label;
		}
	}

	private final Map<Integer, Entry> _entries = new LinkedHashMap<>();
	private int _blueKills;
	private int _redKills;
	private final boolean _ffa;

	PvpScore()
	{
		this(false);
	}

	/** @param ffa everyone against everyone: any kill of another fighter counts, any damage between two fighters counts */
	PvpScore(boolean ffa)
	{
		_ffa = ffa;
	}

	synchronized void add(int id, String name, boolean blue, boolean bot, String label)
	{
		_entries.put(id, new Entry(id, name, blue, bot, label));
	}

	/** A fighter was replaced by a new one: its score carries over to the new id. */
	synchronized void replace(int oldId, int newId, String newName)
	{
		final Entry old = _entries.remove(oldId);
		if (old == null)
		{
			return;
		}
		final Entry made = new Entry(newId, newName, old.blue, old.bot, old.label);
		made.kills = old.kills;
		made.deaths = old.deaths;
		made.dealt = old.dealt;
		made.taken = old.taken;
		made.healed = old.healed;
		_entries.put(newId, made);
	}

	synchronized boolean has(int id)
	{
		return _entries.containsKey(id);
	}

	/**
	 * A fighter went down.
	 * @param killerId the one who did it, or any id that is not in the event (a monster, nobody)
	 * @param victimId the one who died
	 * @return {@code true} if it counted as a kill for the other team
	 */
	synchronized boolean kill(int killerId, int victimId)
	{
		final Entry victim = _entries.get(victimId);
		if (victim == null)
		{
			return false;
		}
		victim.deaths++;
		final Entry killer = _entries.get(killerId);
		if ((killer == null) || (_ffa ? (killer == victim) : (killer.blue == victim.blue)))
		{
			return false;
		}
		killer.kills++;
		if (killer.blue)
		{
			_blueKills++;
		}
		else
		{
			_redKills++;
		}
		return true;
	}

	/** Records damage between fighters of opposite teams. */
	synchronized void damage(int dealerId, int targetId, long amount)
	{
		final Entry dealer = _entries.get(dealerId);
		final Entry target = _entries.get(targetId);
		if ((dealer == null) || (target == null) || (_ffa ? (dealer == target) : (dealer.blue == target.blue)) || (amount <= 0))
		{
			return;
		}
		dealer.dealt += amount;
		target.taken += amount;
	}

	/** Records HP a fighter restored: on a teammate or on itself (a free-for-all counts only itself). */
	synchronized void heal(int healerId, int targetId, long amount)
	{
		final Entry healer = _entries.get(healerId);
		final Entry target = _entries.get(targetId);
		if ((healer == null) || (target == null) || (amount <= 0))
		{
			return;
		}
		if ((healer != target) && (_ffa || (healer.blue != target.blue)))
		{
			return;
		}
		healer.healed += amount;
	}

	synchronized int kills(boolean blue)
	{
		return blue ? _blueKills : _redKills;
	}

	/** @return {@code true} for blue, {@code false} for red, or {@code null} for a draw */
	synchronized Boolean leader()
	{
		if (_blueKills == _redKills)
		{
			return null;
		}
		return _blueKills > _redKills;
	}

	/** @return the fighter with the most kills (then most damage), or {@code null} if nobody has a kill */
	synchronized Entry top()
	{
		Entry best = null;
		for (Entry e : _entries.values())
		{
			if ((e.kills > 0) && ((best == null) || (e.kills > best.kills) || ((e.kills == best.kills) && (e.dealt > best.dealt))))
			{
				best = e;
			}
		}
		return best;
	}

	/** @return the fighters of one team, most kills first, then most damage */
	synchronized List<Entry> rows(boolean blue)
	{
		final List<Entry> out = new ArrayList<>();
		for (Entry e : _entries.values())
		{
			if (e.blue == blue)
			{
				out.add(e);
			}
		}
		Collections.sort(out, new Comparator<Entry>()
		{
			@Override
			public int compare(Entry a, Entry b)
			{
				if (a.kills != b.kills)
				{
					return Integer.compare(b.kills, a.kills);
				}
				return Long.compare(b.dealt, a.dealt);
			}
		});
		return out;
	}

	synchronized Entry get(int id)
	{
		return _entries.get(id);
	}
}
