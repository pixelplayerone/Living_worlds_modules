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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * One player's contracts. Pure data with a plain-text form, so it can be saved as a player variable and unit tested.
 * <p>
 * Hunting grounds are keyed by the ground's slug and hold their kill count; a claimed one is closed for good.
 * Bounties are keyed by boss id and move slain (ready), then claimed; a claimed one never comes back either. Nothing needs to be accepted: progress starts with the first kill.
 */
public final class HuntingState
{
	public static final char RAID_ACCEPTED = 'A';
	public static final char RAID_READY = 'R';
	public static final char RAID_CLAIMED = 'C';
	public static final char RAID_NONE = 'N';

	private final Map<String, Integer> _grounds = new LinkedHashMap<>(); // slug -> kills so far
	private final Set<String> _groundsClaimed = new LinkedHashSet<>();
	private final Set<Integer> _groundBandsClaimed = new LinkedHashSet<>(); // bands whose section bonus was paid
	private final Set<Integer> _raidBandsClaimed = new LinkedHashSet<>(); // bands whose section bonus was paid
	private final Map<Integer, Character> _raids = new LinkedHashMap<>(); // boss id -> state

	public static HuntingState parse(String grounds, String raids)
	{
		final HuntingState s = new HuntingState();
		if ((grounds != null) && !grounds.isEmpty())
		{
			for (String part : grounds.split(","))
			{
				try
				{
					final String[] p = part.split("\\.");
					if (p[0].isEmpty())
					{
						continue;
					}
					if (p[0].startsWith("B"))
					{
						final int band = Integer.parseInt(p[0].substring(1));
						if ((band >= 0) && (band < GroundIndex.GRADES))
						{
							s._groundBandsClaimed.add(band);
						}
					}
					else if ("C".equals(p[1]))
					{
						s._groundsClaimed.add(p[0]);
					}
					else
					{
						s._grounds.put(p[0], Math.max(0, Integer.parseInt(p[1])));
					}
				}
				catch (RuntimeException e)
				{
					// Skip a damaged entry; keep the rest.
				}
			}
		}
		if ((raids != null) && !raids.isEmpty())
		{
			for (String part : raids.split(","))
			{
				try
				{
					final String[] p = part.split("\\.");
					if (p[0].startsWith("B"))
					{
						final int band = Integer.parseInt(p[0].substring(1));
						if ((band >= 0) && (band < HuntingRules.RAID_BANDS))
						{
							s._raidBandsClaimed.add(band);
						}
						continue;
					}
					final char c = p[1].charAt(0);
					if ((c == RAID_ACCEPTED) || (c == RAID_READY) || (c == RAID_CLAIMED))
					{
						s._raids.put(Integer.parseInt(p[0]), c);
					}
				}
				catch (RuntimeException e)
				{
					// Skip a damaged entry; keep the rest.
				}
			}
		}
		return s;
	}

	public String groundsText()
	{
		final StringBuilder b = new StringBuilder();
		for (Map.Entry<String, Integer> e : _grounds.entrySet())
		{
			if (b.length() > 0)
			{
				b.append(',');
			}
			b.append(e.getKey()).append('.').append(e.getValue());
		}
		for (String slug : _groundsClaimed)
		{
			if (b.length() > 0)
			{
				b.append(',');
			}
			b.append(slug).append(".C");
		}
		for (int band : _groundBandsClaimed)
		{
			if (b.length() > 0)
			{
				b.append(',');
			}
			b.append('B').append(band).append(".C");
		}
		return b.toString();
	}

	public String raidsText()
	{
		final StringBuilder b = new StringBuilder();
		for (Map.Entry<Integer, Character> e : _raids.entrySet())
		{
			if (b.length() > 0)
			{
				b.append(',');
			}
			b.append(e.getKey()).append('.').append(e.getValue());
		}
		for (int band : _raidBandsClaimed)
		{
			if (b.length() > 0)
			{
				b.append(',');
			}
			b.append('B').append(band).append(".C");
		}
		return b.toString();
	}

	// ===== hunting ground contracts =====

	public boolean hasGround(String slug)
	{
		return _grounds.containsKey(slug);
	}

	public boolean groundClaimed(String slug)
	{
		return _groundsClaimed.contains(slug);
	}

	public int groundProgress(String slug)
	{
		final Integer p = _grounds.get(slug);
		return (p == null) ? 0 : p;
	}

	public int claimedGroundContracts()
	{
		return _groundsClaimed.size();
	}

	public boolean groundComplete(String slug, ToIntFunction<String> target)
	{
		return hasGround(slug) && (groundProgress(slug) >= target.applyAsInt(slug));
	}

	/** @return {@code true} if a kill in this ground still counts: it is not claimed and not yet at its target */
	public boolean wantsKillIn(String slug, ToIntFunction<String> target)
	{
		return !groundClaimed(slug) && (groundProgress(slug) < target.applyAsInt(slug));
	}

	/** Counts one kill in a ground; the first kill starts its progress. @return {@code true} if this kill finished the ground */
	public boolean addKill(String slug, ToIntFunction<String> target)
	{
		if (!wantsKillIn(slug, target))
		{
			return false;
		}
		final int p = groundProgress(slug) + 1;
		_grounds.put(slug, p);
		return p >= target.applyAsInt(slug);
	}

	/** @return the sum of progress over open contracts; used only to pick save moments */
	public int groundProgressTotal()
	{
		int n = 0;
		for (int v : _grounds.values())
		{
			n += v;
		}
		return n;
	}

	/** @return {@code true} if the contract was complete and is now closed for good; the caller pays the reward */
	public boolean claimGround(String slug, ToIntFunction<String> target)
	{
		if (!groundComplete(slug, target))
		{
			return false;
		}
		_grounds.remove(slug);
		_groundsClaimed.add(slug);
		return true;
	}

	public boolean groundBandClaimed(int band)
	{
		return _groundBandsClaimed.contains(band);
	}

	/** @return {@code true} if every ground in the list has been claimed (an empty list is never complete) */
	public boolean groundBandComplete(java.util.List<String> slugs)
	{
		if (slugs.isEmpty())
		{
			return false;
		}
		for (String slug : slugs)
		{
			if (!groundClaimed(slug))
			{
				return false;
			}
		}
		return true;
	}

	/** @return {@code true} if the section bonus is paid now; once per band, only when the whole band is claimed */
	public boolean claimGroundBand(int band, java.util.List<String> slugs)
	{
		if (_groundBandsClaimed.contains(band) || !groundBandComplete(slugs))
		{
			return false;
		}
		_groundBandsClaimed.add(band);
		return true;
	}

	public int completeGroundContracts(ToIntFunction<String> target)
	{
		int n = 0;
		for (Map.Entry<String, Integer> e : _grounds.entrySet())
		{
			if (e.getValue() >= target.applyAsInt(e.getKey()))
			{
				n++;
			}
		}
		return n;
	}

	// ===== raid contracts =====

	public char raidState(int bossId)
	{
		final Character c = _raids.get(bossId);
		return (c == null) ? RAID_NONE : c;
	}

	/** Marks a boss slain the first time it is killed. @return {@code true} if this kill is new */
	public boolean raidKilled(int bossId)
	{
		if ((raidState(bossId) == RAID_NONE) || (raidState(bossId) == RAID_ACCEPTED))
		{
			_raids.put(bossId, RAID_READY);
			return true;
		}
		return false;
	}

	/** @return {@code true} if a ready contract was claimed; it is then closed for good */
	public boolean claimRaid(int bossId)
	{
		if (raidState(bossId) == RAID_READY)
		{
			_raids.put(bossId, RAID_CLAIMED);
			return true;
		}
		return false;
	}

	public boolean raidBandClaimed(int band)
	{
		return _raidBandsClaimed.contains(band);
	}

	/** @return {@code true} if every boss id in the list has been claimed (an empty list is never complete) */
	public boolean raidBandComplete(int[] bossIds)
	{
		if (bossIds.length == 0)
		{
			return false;
		}
		for (int id : bossIds)
		{
			if (raidState(id) != RAID_CLAIMED)
			{
				return false;
			}
		}
		return true;
	}

	/** @return {@code true} if the section bonus was paid now; it is paid once per band, only when the whole band is claimed */
	public boolean claimRaidBand(int band, int[] bossIds)
	{
		if (_raidBandsClaimed.contains(band) || !raidBandComplete(bossIds))
		{
			return false;
		}
		_raidBandsClaimed.add(band);
		return true;
	}

	public int readyRaidContracts()
	{
		int n = 0;
		for (char c : _raids.values())
		{
			if (c == RAID_READY)
			{
				n++;
			}
		}
		return n;
	}


}
