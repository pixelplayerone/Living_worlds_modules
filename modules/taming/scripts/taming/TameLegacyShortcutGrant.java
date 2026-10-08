/*
 * TameLegacyShortcutGrant.java
 *
 * Removes the shortcut-bar grants that earlier builds of this module handed out.
 *
 * An earlier version pinned a tame's techniques to the player's shortcut bar. That
 * could only be done with four real stock skill ids, because the client draws a
 * shortcut button from its own data and has to be able to name and draw it. Those
 * four ids are ordinary playable skills, so granting them wrote rows into the
 * character's own skill list and the player could then use them themselves. The
 * feature was removed because of that, which leaves the rows behind on anyone who
 * tested it.
 *
 * The rows are worth clearing rather than ignoring: without the module's effect
 * attached, id 3025 is back to being a stock damage skill the character was never
 * meant to have, and it shows up in the client's own skill window.
 *
 * Two properties matter here. First, this only ever removes level 1 grants of these
 * exact ids, so a character who later earned the same id at a higher level from a
 * real class tree, or was given it by an admin on purpose, is left alone. Second,
 * it is keyed on the ids this module itself used, so nothing outside the module is
 * touched. If the same id turns out to be legitimate for some class, deleting the
 * entry from {@link #LEGACY_IDS} stops this from ever considering it again.
 */
package taming;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.skill.Skill;

public final class TameLegacyShortcutGrant
{
	/**
	 * Every id this module has ever written into a character's skill list for the
	 * shortcut bar, including the ids from the builds that predate the four that
	 * survived longest. Grouped so the reason for each group survives with it.
	 */
	private static final int[] LEGACY_IDS =
	{
			// The four the shortcut bar settled on, one per technique slot. All four
			// are revoked now: 3025 is a real damage skill nobody meant to have, and
			// 4549 and 7000 were capture and recall pressed as borrowed stock ids.
			// Capture and recall are both reagent items now, so none of the four is
			// still granted.
			3025, 4549, 5090, 7000,
			// Earlier auditioned candidates that were granted while probing for a
			// usable set, and never removed.
			1030, 1065, 1197, 1241, 1302, 3808,
		// Shot-style ids tried as debuff buttons.
		2039, 2047, 2061, 2150,
		// The module's own taming and recall skills. These are no longer granted to
		// the player at all: capture is pressed through the stock trigger ids above,
		// and 9300/9303 exist only for the reagent items to fire. A character who
		// tested before that switch still has one as a known skill, and because the
		// client has no data for either id it draws a blank entry in the skill
		// window - worse than the stock-label compromise, because it looks broken
		// rather than merely mislabelled.
		//
		// Revoking the player's copy does not affect the reagent: the item carries
		// its own <skills> reference and fires the skill server-side whether or not
		// the character knows it.
		9300, 9303
	};

	private static final List<Integer> IDS = Collections.unmodifiableList(Arrays.stream(LEGACY_IDS).boxed().collect(java.util.stream.Collectors.toList()));

	private TameLegacyShortcutGrant()
	{
	}

	/**
	 * @return the ids this module can clean up, for a status line or a support answer
	 */
	public static List<Integer> ids()
	{
		return IDS;
	}

	/**
	 * Revokes any level 1 grant of a legacy id from this character.
	 *
	 * <p>Called once per login. It reads the character's live skill list rather
	 * than the database, so the row and the in-memory skill are removed together
	 * and the client's own skill window is corrected without a relog.
	 *
	 * @param player the character to clean
	 * @param keep   ids that must survive, however they were granted. Two of these
	 *               ids are the taming and recall triggers now, and the rest are
	 *               the module's own ids; revoking either would break the module on
	 *               every login, which is worse than a stray entry.
	 * @return how many grants were revoked
	 */
	public static int revoke(Player player, java.util.Set<Integer> keep)
	{
		if (player == null)
		{
			return 0;
		}

		int revoked = 0;
		for (int legacyId : LEGACY_IDS)
		{
			if ((keep != null) && keep.contains(Integer.valueOf(legacyId)))
			{
				continue;
			}
			// getKnownSkill answers what this character actually holds, which is the
			// point: a higher level of the same id came from somewhere else and must
			// survive.
			final Skill granted = player.getKnownSkill(legacyId);
			if ((granted != null) && (granted.getLevel() == 1))
			{
				player.removeSkill(granted, true);
				revoked++;
			}
		}
		return revoked;
	}
}
