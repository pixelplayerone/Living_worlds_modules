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
package modules.worldtuner;

import java.util.HashMap;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** The tuning rules: pure decisions with no server classes, so they can be unit tested on their own. */
public final class WorldTuner
{
	/** What to do with one spawn point. */
	public static final class Plan
	{
		public final double respawnMultiplier;
		public final int extraSpawns;
		public final boolean remove;

		Plan(double respawnMultiplier, int extraSpawns, boolean remove)
		{
			this.respawnMultiplier = respawnMultiplier;
			this.extraSpawns = extraSpawns;
			this.remove = remove;
		}

		public boolean isNoOp()
		{
			return (respawnMultiplier == 1.0) && (extraSpawns == 0) && !remove;
		}
	}

	public static final Plan NOTHING = new Plan(1.0, 0, false);

	private final double _respawn;
	private final double _count;
	private final int _minLevel;
	private final int _maxLevel;
	private final Set<String> _regions;
	private final Set<Integer> _exclude;
	private final Map<Integer, double[]> _overrides;
	private final int _maxExtra;

	public WorldTuner(double respawnPercent, double count, int minLevel, int maxLevel, String regions, String exclude, String overrides, int maxExtra)
	{
		_respawn = multiplierOf(respawnPercent);
		_count = Math.max(0, count);
		_minLevel = minLevel;
		_maxLevel = maxLevel;
		_regions = new HashSet<>();
		for (String r : split(regions))
		{
			_regions.add(r.toLowerCase(Locale.ROOT));
		}
		_exclude = new HashSet<>();
		for (String e : split(exclude))
		{
			try
			{
				_exclude.add(Integer.parseInt(e));
			}
			catch (NumberFormatException ex)
			{
				// Ignore a typo in the list rather than refuse to start.
			}
		}
		_overrides = new HashMap<>();
		for (String o : split(overrides.replace(';', ',')))
		{
			final String[] p = o.split(":");
			try
			{
				_overrides.put(Integer.parseInt(p[0].trim()), new double[]
				{
					(p.length > 1) ? Double.parseDouble(p[1].trim()) : _count,
					(p.length > 2) ? multiplierOf(Double.parseDouble(p[2].trim())) : _respawn
				});
			}
			catch (RuntimeException ex)
			{
				// Ignore a malformed entry.
			}
		}
		_maxExtra = Math.max(0, maxExtra);
	}

	/**
	 * Turns "N percent faster" into the number the respawn delay is multiplied by. 30 means respawns happen 30% faster
	 * (delay / 1.3), 100 means twice as fast, 0 is stock, and a negative number slows them down (-50 = half speed).
	 * The slowest allowed is -90, which is ten times slower.
	 */
	public static double multiplierOf(double percentFaster)
	{
		return 1.0 / (1.0 + (Math.max(-90.0, percentFaster) / 100.0));
	}

	/**
	 * A thread-safe set that compares by object identity. The server's spawn points compare by coordinates and their
	 * coordinates can change while they spawn, which makes an ordinary set lose track of them.
	 */
	public static <T> Set<T> newIdentitySet()
	{
		return Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<T, Boolean>()));
	}

	private static String[] split(String csv)
	{
		if ((csv == null) || csv.trim().isEmpty())
		{
			return new String[0];
		}
		final String[] parts = csv.split(",");
		for (int i = 0; i < parts.length; i++)
		{
			parts[i] = parts[i].trim();
		}
		return parts;
	}

	/** @return {@code true} if these settings can never change anything (the module then registers nothing) */
	public boolean isInert()
	{
		return (Math.abs(_respawn - 1.0) < 1e-9) && (_count == 1.0) && _overrides.isEmpty();
	}

	/** The region of a spawn file: the folder under {@code spawns}, e.g. {@code data/spawns/Gludio/Wasteland.xml} is {@code Gludio}. */
	public static String regionOf(String spawnFile)
	{
		if (spawnFile == null)
		{
			return "";
		}
		final String[] parts = spawnFile.replace('\\', '/').split("/");
		for (int i = 0; i < (parts.length - 2); i++)
		{
			if (parts[i].equalsIgnoreCase("spawns"))
			{
				return parts[i + 1];
			}
		}
		return "";
	}

	/**
	 * Decides what to do with one spawn point.
	 * @param npcId the monster id
	 * @param level the monster level
	 * @param region the spawn file's region folder
	 * @param random the random source (a fixed one in tests)
	 */
	public Plan decide(int npcId, int level, String region, Random random)
	{
		if (_exclude.contains(npcId))
		{
			return NOTHING;
		}
		double count = _count;
		double respawn = _respawn;
		final double[] override = _overrides.get(npcId);
		if (override != null)
		{
			count = override[0]; // an override applies to that monster everywhere, whatever the level or region
			respawn = override[1];
		}
		else
		{
			if ((level < _minLevel) || (level > _maxLevel))
			{
				return NOTHING;
			}
			if (!_regions.isEmpty() && !_regions.contains(region.toLowerCase(Locale.ROOT)))
			{
				return NOTHING;
			}
		}
		count = Math.max(0, count);
		if (count < 1.0)
		{
			// Fewer: each spawn point has a (1 - count) chance of being switched off for good.
			final boolean remove = random.nextDouble() >= count;
			return remove ? new Plan(respawn, 0, true) : new Plan(respawn, 0, false);
		}
		final int whole = (int) Math.floor(count);
		final double frac = count - whole;
		int extra = (whole - 1) + ((random.nextDouble() < frac) ? 1 : 0);
		extra = Math.min(extra, _maxExtra);
		return new Plan(respawn, extra, false);
	}

	/** Scales a respawn delay in milliseconds. A delay of 0 (no respawn) stays 0; a scaled delay never drops under the floor. */
	public static int scaleDelay(int delayMs, double multiplier, int floorSeconds)
	{
		if ((delayMs <= 0) || (multiplier == 1.0))
		{
			return delayMs;
		}
		final long scaled = Math.round(delayMs * multiplier);
		return (int) Math.max(Math.max(1, floorSeconds) * 1000L, Math.min(Integer.MAX_VALUE, scaled));
	}
}
