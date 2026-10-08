/*
 * TameExperienceShare.java
 *
 * Decides how a kill's experience is divided between an owner and their tame.
 * Runtime script source; Java 8 compatible.
 */
package taming;

/**
 * Experience share for collar tames.
 * <p>
 * Stock pet code treats the owner's share and the pet's share as one number
 * taken from a single pool: the owner keeps {@code get_exp_type} percent and the
 * pet is handed whatever is left. That is a trade, not a share, so it can never
 * give both sides the whole kill.
 * <p>
 * This module keeps the owner at the full percentage, which leaves the tame with
 * nothing through that path, and grants the tame its own full share from the same
 * kill here instead. Both sides therefore earn the entire amount and nothing is
 * taken away from either of them.
 * <p>
 * The tame's share is granted from {@code ON_CREATURE_KILLED}, so it counts kills
 * the same way the owner's experience does: only when the tame is alive, not
 * dead, and within the same range limit stock uses.
 */
public final class TameExperienceShare
{
	/** Share of the kill's experience the owner keeps. */
	private static int _ownerExpType = 100;

	/**
	 * Share of the kill's experience the tame should end up with, as a percentage
	 * of the amount the owner actually gained.
	 * <p>
	 * This is the target total, not an amount to add on top. Stock already hands
	 * the tame {@code 100 - ownerExpType} percent of the pool before this module
	 * runs, so only the shortfall is granted here. Treating it as an addition would
	 * quietly overpay the tame at every setting except 100/100.
	 */
	private static int _tamePercent = 100;

	/**
	 * Logs every grant the module makes. The kill event gives very little away when
	 * a share silently fails, and the usual causes (a dead tame, an out of range
	 * summon, a kill credited to another player's pet) are all invisible in game.
	 */
	private static boolean _debug = false;

	private TameExperienceShare()
	{
	}

	/**
	 * Applies the configured shares.
	 *
	 * @param ownerExpType percent of the kill the owner keeps
	 * @param tamePercent percent of the owner's own gain the tame also receives
	 */
	public static void configure(int ownerExpType, int tamePercent)
	{
		_ownerExpType = Math.max(0, Math.min(100, ownerExpType));
		_tamePercent = Math.max(0, Math.min(1000, tamePercent));
	}

	public static void setDebug(boolean debug)
	{
		_debug = debug;
	}

	/** Whether grants are written to the server log. */
	public static boolean debug()
	{
		return _debug;
	}

	/** The value written into each forged pet's {@code get_exp_type}. */
	public static int ownerExpType()
	{
		return _ownerExpType;
	}

	/**
	 * Extra share to grant the tame on top of what stock already gave it, as a plain
	 * multiplier of the owner's own gain.
	 * <p>
	 * Stock's split leaves the tame {@code 100 - ownerExpType} percent of the pool.
	 * Only the difference between that and the configured target is still owed, so a
	 * 50/50 setting grants nothing extra (the tame already has its half) while
	 * 100/100 grants a full additional share, since stock leaves it empty.
	 */
	public static double tameFactor()
	{
		final int alreadyGranted = 100 - _ownerExpType;
		final int shortfall = _tamePercent - alreadyGranted;
		return Math.max(0, shortfall) / 100.0;
	}

	/** Human readable form of the current split, for startup logging. */
	public static String describe()
	{
		final int alreadyGranted = 100 - _ownerExpType;
		return "owner " + _ownerExpType + "% (stock gives tame " + alreadyGranted + "%), tame target " + _tamePercent + "% of the owner's gain, extra grant " + (tameFactor() * 100.0) + "%";
	}
}
