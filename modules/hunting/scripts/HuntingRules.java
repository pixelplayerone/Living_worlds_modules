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
package modules.hunting;

/** The contract rules: bands, tiers and reward maths. Pure, so it can be unit tested on its own. */
public final class HuntingRules
{
	/** Raid boss level ranges: 20-24, 25-29 ... 75-79, 80-81, 82-84, 85-86, 87+. A boss below 20 would sit in the first range. */
	public static final int[] RAID_BAND_MIN =
	{
		20,
		25,
		30,
		35,
		40,
		45,
		50,
		55,
		60,
		65,
		70,
		75,
		80,
		82,
		85,
		87
	};
	public static final int RAID_BANDS = RAID_BAND_MIN.length;

	private static final int[] DEFAULT_GROUND_KILLS =
	{
		100,
		250,
		350,
		500,
		600,
		750,
		1000
	};

	private final int[] _groundKills;
	private static final int[] DEFAULT_GROUND_REWARDS =
	{
		100000,
		250000,
		500000,
		1000000,
		2000000,
		3000000,
		5000000
	};

	private static final int[] DEFAULT_GROUND_SECTION_REWARDS =
	{
		1000000,
		2500000,
		5000000,
		10000000,
		15000000,
		30000000,
		40000000
	};

	private final int[] _groundRewards;
	private final int[] _groundSectionRewards;
	private final double _raidFactor;
	private final double _raidBandBonus;
	private final double _grandFactor;
	private final boolean _paying;

	public HuntingRules(String groundKills, String groundRewards, String groundSectionRewards, double raidFactor, double raidBandBonus, double grandFactor)
	{
		this(groundKills, groundRewards, groundSectionRewards, raidFactor, raidBandBonus, grandFactor, true);
	}

	/** @param paying {@code false} turns every adena reward off: contracts still count and can be claimed, they just pay nothing */
	public HuntingRules(String groundKills, String groundRewards, String groundSectionRewards, double raidFactor, double raidBandBonus, double grandFactor, boolean paying)
	{
		_paying = paying;
		_groundKills = parseKills(groundKills);
		_groundRewards = parseRewards(groundRewards, DEFAULT_GROUND_REWARDS);
		_groundSectionRewards = parseRewards(groundSectionRewards, DEFAULT_GROUND_SECTION_REWARDS);
		_raidFactor = Math.max(0, raidFactor);
		_raidBandBonus = Math.max(0, raidBandBonus);
		_grandFactor = Math.max(0, grandFactor);
	}

	/** @return {@code false} when the adena rewards are switched off in the config */
	public boolean paying()
	{
		return _paying;
	}

	/** @return the raid range (0-based) a boss level falls in */
	public static int raidBandOf(int level)
	{
		int band = 0;
		for (int i = 0; i < RAID_BANDS; i++)
		{
			if (level >= RAID_BAND_MIN[i])
			{
				band = i;
			}
		}
		return band;
	}

	public static String raidBandLabel(int band)
	{
		final int b = Math.max(0, Math.min(RAID_BANDS - 1, band));
		return (b == (RAID_BANDS - 1)) ? (RAID_BAND_MIN[b] + "+") : (RAID_BAND_MIN[b] + "-" + (RAID_BAND_MIN[b + 1] - 1));
	}

	private static int[] parseKills(String csv)
	{
		try
		{
			final String[] p = csv.split(",");
			final int[] out = new int[p.length];
			for (int i = 0; i < p.length; i++)
			{
				out[i] = Math.max(1, Integer.parseInt(p[i].trim()));
			}
			return (out.length > 0) ? out : DEFAULT_GROUND_KILLS;
		}
		catch (RuntimeException e)
		{
			return DEFAULT_GROUND_KILLS;
		}
	}

	/** @return how many kills a hunting ground contract in this grade asks for; a grade past the end of the list uses the last entry */
	public int groundKills(int band)
	{
		return _groundKills[Math.max(0, Math.min(_groundKills.length - 1, band))];
	}

	private static int[] parseRewards(String csv, int[] fallback)
	{
		try
		{
			final String[] p = csv.split(",");
			final int[] out = new int[p.length];
			for (int i = 0; i < p.length; i++)
			{
				out[i] = Math.max(0, Integer.parseInt(p[i].trim()));
			}
			return (out.length > 0) ? out : fallback;
		}
		catch (RuntimeException e)
		{
			return fallback;
		}
	}

	/** @return the adena for a hunting ground in this grade; a grade past the end of the list pays the last entry */
	public int groundReward(int band)
	{
		return _paying ? _groundRewards[Math.max(0, Math.min(_groundRewards.length - 1, band))] : 0;
	}

	/** @return the adena for a one-time grand boss bounty: level squared x the grand boss factor */
	public int grandReward(int bossLevel)
	{
		return _paying ? (int) Math.min(Integer.MAX_VALUE, Math.round((double) bossLevel * bossLevel * _grandFactor)) : 0;
	}

	/** @return the section bonus for finishing every ground of a grade; a grade past the end of the list pays the last entry */
	public int groundBandReward(int grade)
	{
		return _paying ? _groundSectionRewards[Math.max(0, Math.min(_groundSectionRewards.length - 1, grade))] : 0;
	}

	/** @return the adena for a one-time raid boss contract: level squared x the factor */
	public int raidReward(int bossLevel)
	{
		return _paying ? (int) Math.min(Integer.MAX_VALUE, Math.round((double) bossLevel * bossLevel * _raidFactor)) : 0;
	}

	/** @return the section bonus for clearing every raid boss of a band: a share of what the band's bosses paid */
	public int raidBandReward(int[] bossLevels)
	{
		if (!_paying)
		{
			return 0;
		}
		double sum = 0;
		for (int level : bossLevels)
		{
			sum += (double) level * level * _raidFactor;
		}
		return (int) Math.min(Integer.MAX_VALUE, Math.round(sum * _raidBandBonus));
	}
}
