package modules.arenadueling;

import java.util.Map;

/**
 * The plain rules of arena dueling: names, stakes, who gets paid, who spars. Numbers and strings in and out, so they
 * test on their own.
 */
final class ArenaRules
{
	private ArenaRules()
	{
	}

	/** @return {@code text} lower-cased with everything but letters and digits removed ("Eva's Templar" becomes "evastemplar") */
	static String key(String text)
	{
		if (text == null)
		{
			return "";
		}
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < text.length(); i++)
		{
			final char c = Character.toLowerCase(text.charAt(i));
			if (Character.isLetterOrDigit(c))
			{
				out.append(c);
			}
		}
		return out.toString();
	}

	/** @return the class id for what the player typed, or 0 if it names no class; {@code names} maps {@link #key} names to ids */
	static int classIdFor(String typed, Map<String, Integer> names)
	{
		final Integer id = names.get(key(typed));
		return (id == null) ? 0 : id;
	}

	/**
	 * Reads an adena amount: "50000", "50k", "1m", "2.5m".
	 * @return the amount, or 0 if the text is not a number
	 */
	static long parseAmount(String text)
	{
		if (text == null)
		{
			return 0;
		}
		String t = text.trim().toLowerCase().replace(",", "").replace("_", "");
		if (t.isEmpty())
		{
			return 0;
		}
		double scale = 1;
		final char last = t.charAt(t.length() - 1);
		if (last == 'k')
		{
			scale = 1_000;
			t = t.substring(0, t.length() - 1);
		}
		else if (last == 'm')
		{
			scale = 1_000_000;
			t = t.substring(0, t.length() - 1);
		}
		try
		{
			final double value = Double.parseDouble(t);
			if ((value <= 0) || Double.isNaN(value) || Double.isInfinite(value))
			{
				return 0;
			}
			return Math.round(value * scale);
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	/** @return {@code amount} if it is one of the allowed stakes, otherwise 0 */
	static int allowedStake(long amount, int[] allowed)
	{
		for (int stake : allowed)
		{
			if (stake == amount)
			{
				return stake;
			}
		}
		return 0;
	}

	/** How a locked stake settles. */
	enum Verdict
	{
		WIN,
		LOSE,
		REFUND
	}

	/**
	 * Decides what happens to a stake that was locked when the duel was set up.
	 * @param playerWon {@code TRUE} if the player won, {@code FALSE} if they lost, {@code null} if nobody did
	 * @param cancelled the duel was cancelled (a player walked away, fought something else, logged out)
	 * @param opponentPresent the phantom is still there, so the cancel was the player's doing
	 * @return a win, a loss (a cancel with the phantom still there is a forfeit), or a refund (a timeout, or the phantom vanished)
	 */
	static Verdict verdict(Boolean playerWon, boolean cancelled, boolean opponentPresent)
	{
		if (playerWon != null)
		{
			return playerWon.booleanValue() ? Verdict.WIN : Verdict.LOSE;
		}
		return (cancelled && opponentPresent) ? Verdict.LOSE : Verdict.REFUND;
	}

	/** @return the house's cut of a stake, {@code feePercent} (0-50) of it */
	static int fee(int stake, int feePercent)
	{
		return (int) (((long) Math.max(0, stake) * Math.max(0, Math.min(50, feePercent))) / 100);
	}

	/** @return what a win pays on top of the stake coming back: the stake less the house's cut, never more than {@code room} (what is left of the hourly allowance, see {@link #winRoom}) */
	static int winnings(int stake, int feePercent, long room)
	{
		final long net = Math.max(0, stake - fee(stake, feePercent));
		return (int) Math.max(0, Math.min(net, room));
	}

	/** @return how much more the player may win in the current window: unlimited when {@code cap} is 0 or less */
	static long winRoom(long cap, long wonInWindow)
	{
		return (cap <= 0) ? Integer.MAX_VALUE : Math.max(0, cap - wonInWindow);
	}

	/** @return {@code true} if a pending stake is still good */
	static boolean stakeLive(long expiresAt, long now)
	{
		return now < expiresAt;
	}

	/** @return how many more regulars an arena needs, never negative and never past {@code room} */
	static int regularsToAdd(int have, int want, int room)
	{
		return Math.max(0, Math.min(want - have, room));
	}

	/** @return the level a regular comes at: the player's, give or take {@code spread}; {@code roll} is in [0, 2 * spread] */
	static int regularLevel(int playerLevel, int spread, int roll)
	{
		final int s = Math.max(0, spread);
		return Math.max(1, playerLevel - s + Math.max(0, Math.min(2 * s, roll)));
	}

	/** @return {@code true} if an arena that has been empty since {@code emptySince} should send its regulars home */
	static boolean sendHome(long emptySince, long now, long graceMs)
	{
		return (emptySince > 0) && ((now - emptySince) >= graceMs);
	}

	/**
	 * Picks two different duelists from {@code free} to spar.
	 * @param roll1 a roll in [0, free)
	 * @param roll2 a roll in [0, free - 1)
	 * @return the two indices, or {@code null} if there are fewer than two
	 */
	static int[] sparPair(int free, int roll1, int roll2)
	{
		if (free < 2)
		{
			return null;
		}
		final int a = Math.max(0, Math.min(free - 1, roll1));
		int b = Math.max(0, Math.min(free - 2, roll2));
		if (b >= a)
		{
			b++;
		}
		return new int[] { a, b };
	}
}
