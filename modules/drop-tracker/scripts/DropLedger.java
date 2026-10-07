package modules.droptracker;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The bookkeeping behind a Drop Tracker run, with no game classes in it so it can be tested on its own.
 * <p>
 * It tracks a set of members, each with an item snapshot taken when they began to count (the baseline) and the most
 * recent snapshot taken while they were present. A member who leaves (logs out, despawns, leaves the party) is frozen
 * at the last snapshot taken while they were still there: the server empties a departed character's inventory, so a
 * snapshot taken afterwards would read as "everything used up". A member who comes back starts a new segment from a
 * fresh baseline and the earlier segment is kept. The run's gain is the sum of every member's segments.
 */
final class DropLedger
{
	private static final class Member
	{
		Map<Integer, Long> base;
		Map<Integer, Long> last;
		final Map<Integer, Long> banked = new HashMap<>();
		boolean active;
	}

	private final Map<Integer, Member> _members = new HashMap<>();

	/**
	 * Brings the ledger up to date with who is present right now.
	 * @param live every member present and readable this moment, by id, with a fresh snapshot of their items
	 */
	synchronized void update(Map<Integer, Map<Integer, Long>> live)
	{
		final Set<Integer> seen = new HashSet<>();
		for (Map.Entry<Integer, Map<Integer, Long>> e : live.entrySet())
		{
			if (e.getValue() == null)
			{
				continue; // unreadable: handled like absent, never as an empty inventory
			}
			seen.add(e.getKey());
			final Member member = _members.get(e.getKey());
			if (member == null)
			{
				final Member fresh = new Member();
				fresh.base = e.getValue();
				fresh.last = e.getValue();
				fresh.active = true;
				_members.put(e.getKey(), fresh);
			}
			else if (!member.active)
			{
				member.base = e.getValue(); // came back: a new segment from now
				member.last = e.getValue();
				member.active = true;
			}
			else
			{
				member.last = e.getValue();
			}
		}
		for (Map.Entry<Integer, Member> e : _members.entrySet())
		{
			if (e.getValue().active && !seen.contains(e.getKey()))
			{
				close(e.getValue());
			}
		}
	}

	/** Freezes everyone at their last snapshot (the run is over, or its owner is gone). */
	synchronized void closeAll()
	{
		for (Member member : _members.values())
		{
			if (member.active)
			{
				close(member);
			}
		}
	}

	synchronized boolean isActive(int id)
	{
		final Member member = _members.get(id);
		return (member != null) && member.active;
	}

	/** @return every member ever tracked in this run */
	synchronized int total()
	{
		return _members.size();
	}

	/** @return members who are no longer being tracked */
	synchronized int departed()
	{
		int count = 0;
		for (Member member : _members.values())
		{
			if (!member.active)
			{
				count++;
			}
		}
		return count;
	}

	/** @return the net change of each item id over the whole run, summed over every member and segment */
	synchronized Map<Integer, Long> delta()
	{
		final Map<Integer, Long> total = new HashMap<>();
		for (Member member : _members.values())
		{
			for (Map.Entry<Integer, Long> e : member.banked.entrySet())
			{
				total.merge(e.getKey(), e.getValue(), Long::sum);
			}
			if (member.active)
			{
				addDiff(total, member.last, member.base);
			}
		}
		return total;
	}

	private static void close(Member member)
	{
		addDiff(member.banked, member.last, member.base);
		member.active = false;
	}

	/** target += (after - before) for every item id in either map. */
	private static void addDiff(Map<Integer, Long> target, Map<Integer, Long> after, Map<Integer, Long> before)
	{
		for (Map.Entry<Integer, Long> e : after.entrySet())
		{
			target.merge(e.getKey(), e.getValue() - before.getOrDefault(e.getKey(), 0L), Long::sum);
		}
		for (Map.Entry<Integer, Long> e : before.entrySet())
		{
			if (!after.containsKey(e.getKey()))
			{
				target.merge(e.getKey(), -e.getValue(), Long::sum);
			}
		}
	}
}
