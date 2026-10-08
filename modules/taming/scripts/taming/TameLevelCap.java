package taming;

import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.model.actor.Summon;

/**
 * Enforces a beast's own level cap.
 *
 * <p>The cap used to live only in the generated stat table, which was not
 * enforcement of any kind. The table describes a beast up to its cap and is
 * clamped there, but nothing stopped the beast's <i>level</i> from climbing past
 * it: the core's own ceiling is {@code PetStat.getMaxLevel()}, which returns
 * {@code ExperienceData.getMaxPetLevel()} - one server-wide constant, the same for
 * every beast in the world and not per collar. So a beast capped at 62 grew
 * cheerfully to 80 and then sat there with its stat curve still pinned at 62,
 * which reads from the outside as a beast that is simultaneously overlevelled and
 * mysteriously weak.
 *
 * <p>There is no per-beast hook to lower that ceiling. {@code PetStat.getMaxLevel}
 * is public and non-final, but {@code PetStat} is constructed by {@code Pet} and
 * there is no supported way to substitute one for a single beast, so the cap is
 * enforced here instead.
 *
 * <p>The seam that makes it possible is the order inside
 * {@code PlayableStat.addExp(long)}: the experience event fires first, the core then
 * adds the gain itself, and only after that does it read {@code getExp()} back to work
 * out the new level and clamp it against the global ceiling. So a handler that lowers
 * experience during the event has that value picked up by the arithmetic that follows
 * instead of being overwritten by it - the core re-reads the field rather than working
 * from the figure it was handed, and there is no fight over who writes last.
 *
 * <p>Note the event fires while {@code getExp()} still holds the OLD total: the new one
 * is only passed as an argument on the event holder, never written to the creature yet.
 * That is why {@link #hold} clamps an already-exceeded total rather than trying to
 * predict the incoming gain - the gain is not available to it at that point.
 *
 * <p>Experience for a given level runs from {@code getExpForLevel(level)} up to
 * {@code getExpForLevel(level + 1) - 1}, so the highest total a capped beast can
 * hold is one short of the experience for the level above its cap. Sitting exactly
 * on {@code getExpForLevel(cap + 1)} would promote it, so the one-off matters and is
 * the kind of detail that produces a beast one level over its cap forever.
 */
final class TameLevelCap
{
	private TameLevelCap()
	{
	}

	/**
	 * Brings a beast back under its cap if it is already over it.
	 *
	 * <p>Needed because the cap was never enforced before this existed, so beasts
	 * captured earlier are legitimately sitting above it in the database. Without
	 * this they would stay above it permanently: a summon reloads the stored level,
	 * and the experience hook only ever runs on a gain.
	 *
	 * @return whether the beast had to be corrected
	 */
	public static boolean correct(Summon summon, TameProfile profile)
	{
		if (!isOurs(summon, profile))
		{
			return false;
		}
		final int cap = capOf(profile);
		if ((cap < 1) || (summon.getLevel() <= cap))
		{
			return false;
		}
		final long ceiling = expCeiling(cap);
		if (ceiling > 0)
		{
			summon.getStat().setExp(ceiling);
		}
		summon.getStat().setLevel((byte) cap);
		// The profile is what the collar, the profile window and the awakening gate
		// all read, so fixing only the live creature would leave all three still
		// reporting a level the beast is not allowed to be at.
		profile.setCurrentLevel(cap);
		TameProfileRepository.save(profile);
		return true;
	}

	/**
	 * Whether this summon is the beast this profile describes.
	 *
	 * <p>The cap is written straight onto a live creature, so a stale or mismatched
	 * profile would quietly pull an unrelated pet down to a level it never earned.
	 * The collar object id is the join that makes the two the same beast, and
	 * checking it costs nothing against the alternative.
	 */
	private static boolean isOurs(Summon summon, TameProfile profile)
	{
		if ((summon == null) || (profile == null) || !summon.isPet())
		{
			return false;
		}
		return profile.getCollarObjectId() == summon.getControlObjectId();
	}

	/**
	 * Holds a beast at its cap while it is gaining experience.
	 *
	 * <p>Called from the experience hook, before the core works out the new level.
	 * Below the cap this does nothing at all, which matters: the hook runs on every
	 * experience gain a beast ever earns, and a needless write on that path would
	 * be paid for by every summon in the game.
	 */
	public static void hold(Summon summon, TameProfile profile)
	{
		if (!isOurs(summon, profile))
		{
			return;
		}
		final int cap = capOf(profile);
		if (cap < 1)
		{
			return;
		}
		final long ceiling = expCeiling(cap);
		if ((ceiling > 0) && (summon.getStat().getExp() > ceiling))
		{
			summon.getStat().setExp(ceiling);
		}
	}

	/**
	 * The highest experience total that still leaves a beast at {@code cap}.
	 *
	 * <p>Returns -1 when the cap sits at the top of the experience table, where
	 * there is no next level to hold it back and the core's own clamp already does
	 * the work.
	 */
	private static long expCeiling(int cap)
	{
		final ExperienceData experience = ExperienceData.getInstance();
		if ((experience == null) || (cap + 1) >= experience.getMaxLevel())
		{
			return -1;
		}
		return experience.getExpForLevel(cap + 1) - 1;
	}

	/**
	 * The cap itself, clamped to something the experience table can express.
	 */
	private static int capOf(TameProfile profile)
	{
		final ExperienceData experience = ExperienceData.getInstance();
		int cap = profile.getMaxPetLevel();
		if ((experience != null) && (cap >= experience.getMaxLevel()))
		{
			cap = experience.getMaxLevel() - 1;
		}
		return cap;
	}
}
