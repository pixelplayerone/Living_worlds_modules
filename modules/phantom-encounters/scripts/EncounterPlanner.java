package modules.phantomencounters;

/**
 * The module's decisions that need no game world: which kind is due, how strong its actors are, how many there are,
 * and when the next one comes. Plain numbers in and out, so they test on their own.
 */
final class EncounterPlanner
{
	/** The kinds of encounter, easiest first. The order is the order of every per-kind setting. */
	enum Kind
	{
		WIMP,
		NORMIE,
		HARD,
		PK_PARTY,
		HORSEMEN,
		PKER,
		PVE_PARTY
	}

	private EncounterPlanner()
	{
	}

	/**
	 * The actor's level: the player's level plus an offset picked from [minOffset, maxOffset], clamped to [1, 80].
	 * @param roll any number (the caller's random roll)
	 */
	static int levelFor(int playerLevel, int minOffset, int maxOffset, int roll)
	{
		final int lo = Math.min(minOffset, maxOffset);
		final int hi = Math.max(minOffset, maxOffset);
		return Math.max(1, Math.min(80, playerLevel + lo + (Math.abs(roll) % ((hi - lo) + 1))));
	}

	/**
	 * The enchant on the actor's weapon and armor: a value in [min, max] (clamped to 0-30).
	 * @param roll any number (the caller's random roll)
	 */
	static int enchantIn(int min, int max, int roll)
	{
		final int a = Math.max(0, Math.min(30, min));
		final int b = Math.max(0, Math.min(30, max));
		final int lo = Math.min(a, b);
		final int hi = Math.max(a, b);
		return lo + (Math.abs(roll) % ((hi - lo) + 1));
	}

	/**
	 * How many actors an encounter sends. Wimp, Normie and Hard match the party (at least one); the Horsemen match it
	 * too but never fewer than {@code horsemenMin}; the lone Pker is always one. Capped at {@code cap}.
	 * @param partySize members in the player's party, counting the player (1 when solo)
	 */
	static int groupSize(Kind kind, int partySize, int horsemenMin, int cap)
	{
		final int limit = Math.max(1, cap);
		switch (kind)
		{
			case PKER:
			{
				return 1;
			}
			case HORSEMEN:
			{
				return Math.max(1, Math.min(limit, Math.max(partySize, horsemenMin)));
			}
			default:
			{
				return Math.max(1, Math.min(limit, partySize));
			}
		}
	}

	/** @return a delay in [minMs, maxMs] for a roll in [0, 1000). A reversed range collapses to minMs. */
	static long delayMs(long minMs, long maxMs, int roll)
	{
		if (maxMs <= minMs)
		{
			return Math.max(0, minMs);
		}
		return minMs + (((maxMs - minMs) * Math.max(0, Math.min(999, roll))) / 1000);
	}

	/**
	 * Which kind to send now.
	 * @param due when each kind is next due (milliseconds), by kind; 0 means that kind is not running for this player
	 * @return the index of the rarest kind that is due, or -1 when none is
	 */
	static int pickDue(long[] due, long now)
	{
		int pick = -1;
		for (int i = 0; i < due.length; i++)
		{
			if ((due[i] > 0) && (now >= due[i]))
			{
				pick = i; // later kinds are rarer: the rarest due kind wins
			}
		}
		return pick;
	}

	/**
	 * Bookkeeping after a kind has been sent: its own timer restarts, and nothing else may start before the quiet time
	 * is over, so kinds that were due together do not arrive back to back.
	 */
	static void afterStart(long[] due, int fired, long nextForFired, long quietUntil)
	{
		due[fired] = nextForFired;
		quiet(due, quietUntil);
	}

	/** Pushes every running timer that would fire before {@code quietUntil} out to it (a timer of 0 is off and stays off). */
	static void quiet(long[] due, long quietUntil)
	{
		for (int i = 0; i < due.length; i++)
		{
			if ((due[i] > 0) && (due[i] < quietUntil))
			{
				due[i] = quietUntil;
			}
		}
	}

	/**
	 * Whether this kill in a farming area wins the dice: {@code chancePercent} out of 100, rolled in hundredths of a percent.
	 * @param roll a number in [0, 10000)
	 */
	static boolean contestRolled(double chancePercent, int roll)
	{
		if (chancePercent <= 0)
		{
			return false;
		}
		return Math.max(0, Math.min(9999, roll)) < (Math.min(100.0, chancePercent) * 100.0);
	}

	/** Whether a contest may start now: this player's own cooldown and the shared quiet time are over, and there is room. */
	static boolean contestReady(long now, long cooldownUntil, long quietUntil, int active, int maxActive)
	{
		return (now >= cooldownUntil) && (now >= quietUntil) && (active < maxActive);
	}
}
