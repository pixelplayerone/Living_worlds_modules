/*
 * TameProfileRepository.java
 *
 * JDBC repository for the clean-slate individual tame profile.
 * Runtime script source; Java 8 compatible.
 */
package taming;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.l2jmobius.gameserver.model.item.instance.Item;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.data.holders.PetData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.data.xml.SkillData;

public final class TameProfileRepository
{
	private static final Logger LOGGER = Logger.getLogger(TameProfileRepository.class.getName());

	/**
	 * Wound severity runs from healthy (zero) to this. Food steps it down one at
	 * a time, and the bond cost of .tameheal clears it outright.
	 */
	public static final int WOUND_MAX = 5;

	/**
	 * Whether a slot name belongs to the awakening family.
	 *
	 * <p>Rarity decides how many awakening techniques a beast is offered - one for a
	 * common beast, two for RARE and above, three for a legendary beast with skill
	 * potential 85 or more - and the extras are numbered AWAKENING_2 and
	 * AWAKENING_3. So this is a prefix match rather than an equality test against
	 * each possible number: a fourth technique added later is covered by the same
	 * rule instead of needing this method taught about it.
	 *
	 * <p>Every place that asks this question used to test for the exact string
	 * 'AWAKENING', and that was the whole of the "second awakening gives me both
	 * skills" report. The extras were not recognised as awakening techniques, so
	 * they were not gated: the insert treats anything not exactly 'AWAKENING' as an
	 * ordinary skill and enabled it outright, and neither awakening granted them.
	 * A rarity beast therefore appeared to hand out a second technique the moment
	 * it reached the unlock level, and the technique it should have been earning
	 * stayed dark forever.
	 *
	 * <p>Sits on the outer class because the rule is needed in three places with no
	 * loaded skill record in sight: the insert that decides whether a technique
	 * starts gated, the grants that open it, and the deck query that decides
	 * whether to show it as locked.
	 */
	public static boolean isAwakeningSlot(String slot)
	{
		return (slot != null) && slot.toUpperCase(java.util.Locale.ROOT).startsWith("AWAKENING");
	}

	/**
	 * The slot name of the technique a second awakening opens.
	 *
	 * <p>Written without the underscore the numbered first-awakening techniques use,
	 * and that is the whole point of it: 'AWAKENING2' is not 'the second technique a
	 * rare beast was rolled with' but 'the technique the second awakening grants'.
	 * The first awakening's alternatives are AWAKENING_2 and AWAKENING_3, chosen at
	 * capture time from the beast's rarity; this one is locked until the beast has
	 * been through a second awakening, whichever of the first set it picked.
	 */
	public static final String SECOND_AWAKENING_SLOT = "AWAKENING2";

	/**
	 * Whether a slot is a technique the first awakening opens.
	 *
	 * <p>Everything in the family except the second awakening's own slot. Needed as a
	 * separate question from {@link #isAwakeningSlot} because the first awakening
	 * used to grant the whole family on a LIKE 'AWAKENING%', which would hand out
	 * AWAKENING2 the moment the beast was first awakened - the technique the second
	 * awakening exists to grant, given away one awakening early.
	 */
	public static boolean isFirstAwakeningSlot(String slot)
	{
		return isAwakeningSlot(slot) && !isSecondAwakeningSlot(slot);
	}

	/**
	 * Whether a slot is the technique the second awakening opens.
	 */
	public static boolean isSecondAwakeningSlot(String slot)
	{
		return (slot != null) && SECOND_AWAKENING_SLOT.equalsIgnoreCase(slot);
	}

	/**
	 * The technique the second awakening just opened, if this beast has one.
	 *
	 * <p>Read back out of the deck rather than kept in memory, so it is the row the
	 * grant actually wrote. Returns null when the beast has no AWAKENING2 row at all
	 * - a beast whose rarity took the whole technique pool has nothing to be granted
	 * - and the caller says nothing rather than naming a skill that does not exist.
	 */
	public static org.l2jmobius.gameserver.model.skill.Skill findSecondAwakeningSkill(String uuid)
	{
		if (uuid == null)
		{
			return null;
		}
		final String sql = "SELECT skill_id, skill_level FROM tamed_pet_skill WHERE tame_uuid=? AND slot_type=? LIMIT 1";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setString(1, uuid);
			ps.setString(2, SECOND_AWAKENING_SLOT);
			try (ResultSet rs = ps.executeQuery())
			{
				if (rs.next())
				{
					return SkillData.getInstance().getSkill(TameSkillPolicy.petSkillId(rs.getInt("skill_id")), rs.getInt("skill_level"));
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to read the second awakening technique of " + uuid, e);
		}
		return null;
	}

	/**
	 * The profile columns, in {@link #bindProfile} order, with {@code active} left out
	 * because it is the literal 1 in both statements.
	 *
	 * <p>The insert and its upsert clause are both generated from this one list. They used to
	 * be two hand-written strings, which is exactly the arrangement where the update clause
	 * quietly falls one column behind the insert and nobody notices until a stat stops saving.
	 */
	private static final String[] PROFILE_COLUMNS =
	{
		"tame_uuid", "collar_object_id", "owner_id", "source_npc_id", "source_level", "source_type", "race", "family", "role", "rarity",
		"potential", "offense_potential", "defense_potential", "vitality_potential", "skill_potential", "growth_percent", "temperament",
		"affinity", "bond", "awakening_stage", "awakening_path", "current_level", "max_pet_level", "profile_seed", "profile_version",
		"pet_name", "base_hp", "base_mp", "base_patk", "base_pdef", "base_matk", "base_mdef", "conversion_multiplier", "wound_flags",
		"synthetic_npc_id", "worn"
	};

	private static final String PROFILE_UPSERT = buildProfileUpsert();

	/**
	 * Builds the insert with its own update clause, so a save updates in place instead of
	 * deleting the row and putting it back.
	 *
	 * <p>That distinction is the whole point, and it was a live data-loss bug. All three child
	 * tables hang off {@code tame_uuid} with {@code ON DELETE CASCADE}, so deleting the
	 * {@code tamed_pet} row silently destroyed the beast's skills, gear and history - and the
	 * old {@code save()} deleted and reinserted on every call. Its one caller is
	 * {@code TameLevelCap.correct()}, so every time a pet was summoned above its level cap the
	 * module corrected its level by wiping everything the beast had. An upsert touches no
	 * child rows at all.
	 *
	 * <p>{@code tame_uuid} is deliberately left out of the update list: it is the primary key,
	 * and rewriting it would orphan the child rows the upsert exists to protect.
	 */
	private static String buildProfileUpsert()
	{
		final StringBuilder columns = new StringBuilder();
		final StringBuilder values = new StringBuilder();
		for (int i = 0; i < PROFILE_COLUMNS.length; i++)
		{
			if (i > 0)
			{
				columns.append(", ");
				values.append(", ");
			}
			columns.append(PROFILE_COLUMNS[i]);
			values.append('?');
		}
		final StringBuilder sql = new StringBuilder("INSERT INTO tamed_pet (").append(columns).append(", active) VALUES (").append(values)
				.append(", 1) ON DUPLICATE KEY UPDATE ");
		for (int i = 1; i < PROFILE_COLUMNS.length; i++)
		{
			if (i > 1)
			{
				sql.append(", ");
			}
			sql.append(PROFILE_COLUMNS[i]).append("=VALUES(").append(PROFILE_COLUMNS[i]).append(')');
		}
		return sql.toString();
	}
	private static final String SKILL_INSERT = "INSERT INTO tamed_pet_skill (tame_uuid, skill_id, skill_level, slot_type, inheritance_quality, unlock_level, awakened, enabled) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
	private static final String BESTIARY_UPSERT = "INSERT INTO tamed_pet_bestiary (owner_id, source_npc_id, discovered, individuals_seen, best_potential, best_growth, highest_awakening, rare_skill_count) VALUES (?, ?, 1, 1, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE individuals_seen=individuals_seen+1, best_potential=GREATEST(best_potential, VALUES(best_potential)), best_growth=GREATEST(best_growth, VALUES(best_growth)), highest_awakening=GREATEST(highest_awakening, VALUES(highest_awakening)), rare_skill_count=rare_skill_count+VALUES(rare_skill_count)";
	private static final String HISTORY_INSERT = "INSERT INTO tamed_pet_history (tame_uuid, event_type, event_data) VALUES (?, ?, ?)";

	private TameProfileRepository()
	{
	}

	/**
	 * Writes a profile in place.
	 *
	 * <p>This used to delete the {@code tamed_pet} row for the collar and reinsert it, which
	 * took the beast's skills, gear and history with it every time: the child tables cascade off
	 * the primary key. Its only caller is {@code TameLevelCap.correct()}, so the loss was
	 * invisible until a pet was summoned over its level cap and came back stripped.
	 *
	 * <p>The upsert replaces the row's columns and never removes it, so the child rows stay
	 * attached to the same {@code tame_uuid}. If a save ever does arrive with a different uuid
	 * for a collar that already has one, the update clause leaves the stored key alone rather
	 * than rewriting it - keeping the children reachable beats matching the in-memory profile.
	 */
	public static boolean save(TameProfile profile)
	{
		if (profile == null)
		{
			return false;
		}
		try (Connection con = DatabaseFactory.getConnection())
		{
			con.setAutoCommit(false);
			try
			{
				try (PreparedStatement ps = con.prepareStatement(PROFILE_UPSERT))
				{
					bindProfile(ps, profile);
					ps.executeUpdate();
				}
				con.commit();
				return true;
			}
			catch (Exception e)
			{
				// The autoCommit(false) above is not undone by close(). Without this rollback the
				// connection returns to the pool still holding an open transaction and whichever
				// query borrows it next runs inside someone else's failed save.
				try
				{
					con.rollback();
				}
				catch (Exception suppressed)
				{
				}
				throw e;
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "TameProfileRepository: failed to save " + profile.getUuid(), e);
			return false;
		}
	}
	
	public static TameProfile load(int ownerId, int collarObjectId)
	{
		final String sql = "SELECT * FROM tamed_pet WHERE owner_id=? AND collar_object_id=? AND active=1";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setInt(1, ownerId);
			ps.setInt(2, collarObjectId);
			try (ResultSet rs = ps.executeQuery())
			{
				if (rs.next())
				{
					return readProfile(rs);
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "TameProfileRepository: failed to load collar " + collarObjectId, e);
		}
		return null;
	}

	/**
	 * Loads a profile by its collar alone, with no owner filter. The login
	 * restore path has only the collar to go on: the owner's id is not known yet
	 * at the moment stock code rebuilds the pet, and the collar object id is
	 * unique across the whole server.
	 */
	public static TameProfile loadByCollar(int collarObjectId)
	{
		final String sql = "SELECT * FROM tamed_pet WHERE collar_object_id=? AND active=1";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setInt(1, collarObjectId);
			try (ResultSet rs = ps.executeQuery())
			{
				if (rs.next())
				{
					return readProfile(rs);
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "TameProfileRepository: failed to load collar " + collarObjectId, e);
		}
		return null;
	}

	/**
	 * Reserves the private NPC id for a freshly captured collar. The row has to
	 * exist first, so this writes the id onto the already-saved profile.
	 */
	public static boolean assignSyntheticNpcId(int collarObjectId, int syntheticNpcId)
	{
		final String sql = "UPDATE tamed_pet SET synthetic_npc_id=? WHERE collar_object_id=?";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setInt(1, syntheticNpcId);
			ps.setInt(2, collarObjectId);
			return ps.executeUpdate() > 0;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "TameProfileRepository: failed to record synthetic id " + syntheticNpcId + " for collar " + collarObjectId, e);
			return false;
		}
	}

	/**
	 * Loads every active profile, for the admin health check. Profiles are small
	 * and the collar count is bounded by the private id band, so one read is
	 * cheaper than the bookkeeping to avoid it.
	 */
	public static List<TameProfile> loadAll()
	{
		final List<TameProfile> profiles = new ArrayList<>();
		final String sql = "SELECT * FROM tamed_pet WHERE active=1";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery())
		{
			while (rs.next())
			{
				profiles.add(readProfile(rs));
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "TameProfileRepository: failed to list every collar", e);
		}
		return profiles;
	}

	/**
	 * Reads the name the owner gave this tame through the stock client rename box.
	 * <p>
	 * The client writes that name into the stock {@code pets} row, keyed by the
	 * collar object id, which is the only record of it. The profile table is what
	 * this module reads, so the name has to be pulled across once or the next
	 * summon would stamp the profile's empty name back over it and the rename would
	 * look like it had silently reverted.
	 *
	 * @return the saved name, or an empty string when the tame has never been named
	 */
	public static String loadStockPetName(int collarObjectId)
	{
		final String sql = "SELECT name FROM pets WHERE item_obj_id=?";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setInt(1, collarObjectId);
			try (ResultSet rs = ps.executeQuery())
			{
				if (rs.next())
				{
					final String name = rs.getString(1);
					return (name == null) ? "" : name.trim();
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: no stock pet row for collar " + collarObjectId, e);
		}
		return "";
	}

	/**
	 * Finds the collar this owner last brought out, so a relog can put the same
	 * individual back instead of an arbitrary one.
	 * <p>
	 * A player may own many collars and every one of them is the same item id, so
	 * nothing in the stock pet data can tell them apart. This module's own row is
	 * the only record that says which collar was in play. Summoning writes to the
	 * profile (bond, level), which bumps {@code updated_at}, so the most recently
	 * touched row is the beast that was out. A wounded beast is skipped because it
	 * is not allowed to come out at all.
	 *
	 * @return the collar object id, or 0 when the owner has nothing ready
	 */
	public static int findLastSummonedCollar(int ownerId)
	{
		final String sql = "SELECT collar_object_id FROM tamed_pet WHERE owner_id=? AND active=1 AND wound_flags=0 ORDER BY updated_at DESC LIMIT 1";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setInt(1, ownerId);
			try (ResultSet rs = ps.executeQuery())
			{
				if (rs.next())
				{
					return rs.getInt(1);
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: no last-summoned collar for owner " + ownerId, e);
		}
		return 0;
	}

	/**
	 * Copies the stock pet name into the profile the first time a tame is named.
	 * <p>
	 * Only an empty profile name is filled in. A name already in the profile is
	 * never overwritten, so this can run on every summon without ever reverting a
	 * name the owner has already given.
	 *
	 * @return the name the tame should be summoned under
	 */
	public static String adoptStockPetName(int ownerId, int collarObjectId, String currentName)
	{
		final String existing = (currentName == null) ? "" : currentName.trim();
		if (!existing.isEmpty())
		{
			return existing;
		}
		final String saved = loadStockPetName(collarObjectId);
		if (saved.isEmpty())
		{
			// Still unnamed. The client's own rename box stays available.
			return "";
		}
		if (updateName(ownerId, collarObjectId, saved))
		{
			final TameProfile profile = load(ownerId, collarObjectId);
			if (profile != null)
			{
				profile.setPetName(saved);
			}
			logEvent(profile == null ? "" : profile.getUuid(), "RENAME", saved);
			LOGGER.info("collar " + collarObjectId + " took the name " + saved + " from the client's rename box");
		}
		return saved;
	}

	public static boolean updateName(int ownerId, int collarObjectId, String name)
	{
		final String sql = "UPDATE tamed_pet SET pet_name=? WHERE owner_id=? AND collar_object_id=?";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setString(1, name);
			ps.setInt(2, ownerId);
			ps.setInt(3, collarObjectId);
			return ps.executeUpdate() > 0;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "TameProfileRepository: failed to rename collar " + collarObjectId, e);
			return false;
		}
	}
	
	public static boolean rebind(int collarObjectId, int oldOwnerId, int newOwnerId)
	{
		if ((collarObjectId <= 0) || (oldOwnerId <= 0) || (newOwnerId <= 0) || (oldOwnerId == newOwnerId))
		{
			return false;
		}
		try (Connection con = DatabaseFactory.getConnection())
		{
			con.setAutoCommit(false);
			try (PreparedStatement profile = con.prepareStatement("UPDATE tamed_pet SET owner_id=? WHERE collar_object_id=? AND owner_id=?"); PreparedStatement pet = con.prepareStatement("UPDATE pets SET ownerId=? WHERE item_obj_id=?"))
			{
				profile.setInt(1, newOwnerId);
				profile.setInt(2, collarObjectId);
				profile.setInt(3, oldOwnerId);
				if (profile.executeUpdate() == 0)
				{
					con.rollback();
					return false;
				}
				pet.setInt(1, newOwnerId);
				pet.setInt(2, collarObjectId);
				pet.executeUpdate();
				con.commit();
				return true;
			}
			catch (Exception e)
			{
				con.rollback();
				throw e;
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "TameProfileRepository: failed to rebind collar " + collarObjectId, e);
			return false;
		}
	}
	
	public static boolean saveSkills(String uuid, List<TameSkillPolicy.Selection> selections)
	{
		if ((uuid == null) || (selections == null) || selections.isEmpty())
		{
			return true;
		}
		final String sql = "INSERT INTO tamed_pet_skill (tame_uuid, skill_id, skill_level, slot_type, inheritance_quality, unlock_level, awakened, enabled) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			for (TameSkillPolicy.Selection selection : selections)
			{
				if ((selection == null) || (selection.getSkill() == null))
				{
					continue;
				}
				ps.setString(1, uuid);
				ps.setInt(2, selection.getSkill().getId());
				ps.setInt(3, selection.getSkill().getLevel());
				ps.setString(4, selection.getSlot());
				ps.setString(5, selection.getQuality());
				ps.setInt(6, selection.getUnlockLevel());
				ps.setBoolean(7, selection.isAwakened());
				ps.setBoolean(8, !isAwakeningSlot(selection.getSlot()));
				ps.addBatch();
			}
			ps.executeBatch();
			return true;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "TameProfileRepository: failed to save skills for " + uuid, e);
			return false;
		}
	}
	
	/**
	 * Points an existing tame's SIGNATURE_1 row at a different technique.
	 *
	 * <p>Used only by {@link TameSkillPolicy}'s player-technique upgrade, and deliberately
	 * not {@link #saveSkills}: that method is a plain INSERT with no delete, so using it to
	 * "refresh" a deck would append a second SIGNATURE_1 row beside the first rather than
	 * replacing it. This touches exactly one row, and only that one, so an awakened beast
	 * keeps its chosen awakening path and its other techniques - SIGNATURE_1 is never an
	 * awakening slot.
	 *
	 * @return true when a row was actually changed, false when there was nothing to do or
	 *         the write failed. A tame whose deck is too short to have a SIGNATURE_1 row is
	 *         simply left alone.
	 */
	public static boolean rebindSignature(String uuid, int skillId, int skillLevel)
	{
		if ((uuid == null) || (skillId <= 0) || (skillLevel <= 0))
		{
			return false;
		}
		// ORDER BY ... LIMIT 1 is load-bearing. The primary key is
		// (tame_uuid, skill_id, slot_type), which permits more than one SIGNATURE_1 row per
		// tame as long as their skill ids differ, so rewriting all of them to a single id
		// would violate that key. Exactly one row is rewritten, chosen deterministically.
		final String sql = "UPDATE tamed_pet_skill SET skill_id = ?, skill_level = ? WHERE tame_uuid = ? AND slot_type = 'SIGNATURE_1' ORDER BY skill_id LIMIT 1";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setInt(1, skillId);
			ps.setInt(2, skillLevel);
			ps.setString(3, uuid);
			return ps.executeUpdate() > 0;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "TameProfileRepository: failed to rebind signature for " + uuid, e);
			return false;
		}
	}

	/**
	 * The technique currently bound to SIGNATURE_1, or -1 when the deck has no such row.
	 *
	 * <p>Ordered to match {@link #rebindSignature}, so a tame with more than one such row
	 * compares against the same row the write would change rather than an arbitrary one.
	 */
	public static int currentSignatureId(String uuid)
	{
		if (uuid == null)
		{
			return -1;
		}
		final String sql = "SELECT skill_id FROM tamed_pet_skill WHERE tame_uuid = ? AND slot_type = 'SIGNATURE_1' ORDER BY skill_id LIMIT 1";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setString(1, uuid);
			try (ResultSet rs = ps.executeQuery())
			{
				return rs.next() ? rs.getInt(1) : -1;
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "TameProfileRepository: failed to read signature for " + uuid, e);
			return -1;
		}
	}

	public static boolean saveSelection(String uuid, TameSkillPolicy.Selection selection)
	{
		if ((uuid == null) || (selection == null) || (selection.getSkill() == null))
		{
			return false;
		}
		final String sql = "INSERT INTO tamed_pet_skill (tame_uuid, skill_id, skill_level, slot_type, inheritance_quality, unlock_level, awakened, enabled) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setString(1, uuid);
			ps.setInt(2, selection.getSkill().getId());
			ps.setInt(3, selection.getSkill().getLevel());
			ps.setString(4, selection.getSlot());
			ps.setString(5, selection.getQuality());
			ps.setInt(6, selection.getUnlockLevel());
			ps.setBoolean(7, selection.isAwakened());
			ps.setBoolean(8, !isAwakeningSlot(selection.getSlot()));
			return ps.executeUpdate() > 0;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to save one skill for " + uuid, e);
			return false;
		}
	}

	public static boolean hasSkillSlot(String uuid, String slot)
	{
		if ((uuid == null) || (slot == null))
		{
			return false;
		}
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement("SELECT 1 FROM tamed_pet_skill WHERE tame_uuid=? AND slot_type=? LIMIT 1"))
		{
			ps.setString(1, uuid);
			ps.setString(2, slot);
			try (ResultSet rs = ps.executeQuery())
			{
				return rs.next();
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to check skill slot for " + uuid, e);
			return false;
		}
	}

	public static boolean addBond(int ownerId, int collarObjectId, int amount)
	{
		if (amount <= 0)
		{
			return false;
		}
		final String sql = "UPDATE tamed_pet SET bond=LEAST(10000, bond+?) WHERE owner_id=? AND collar_object_id=?";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setInt(1, amount);
			ps.setInt(2, ownerId);
			ps.setInt(3, collarObjectId);
			return ps.executeUpdate() > 0;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to add bond to collar " + collarObjectId, e);
			return false;
		}
	}
	
	/**
	 * Copies the live pet's level onto its profile.
	 * <p>
	 * The pet levels in game through its own generated experience curve, but
	 * nothing was ever writing that level back to the profile row. Two features
	 * read the profile's level instead of the pet's: the awakening SQL gates on
	 * {@code current_level>=30}, and the passport decides whether to print
	 * "Ready to awaken". Both were therefore permanently unreachable, because the
	 * stored level stayed at 1 from the moment of capture.
	 * <p>
	 * Called on every summon, where the pet already exists and its real level is
	 * known. It only ever moves the stored level forward, so a profile cannot be
	 * dragged backwards by a desummon that happened to catch a pet mid-respawn.
	 *
	 * @return true when the stored level actually changed
	 */
	public static boolean syncLevel(int ownerId, int collarObjectId, int level)
	{
		if (level < 1)
		{
			return false;
		}
		// GREATEST() keeps this monotonic even if two summons overlap.
		final String sql = "UPDATE tamed_pet SET current_level=GREATEST(current_level, ?) WHERE owner_id=? AND collar_object_id=? AND current_level<?";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setInt(1, level);
			ps.setInt(2, ownerId);
			ps.setInt(3, collarObjectId);
			ps.setInt(4, level);
			return ps.executeUpdate() > 0;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to sync level for collar " + collarObjectId, e);
			return false;
		}
	}

	public static boolean recover(int ownerId, int collarObjectId)
	{
		final String sql = "UPDATE tamed_pet SET wound_flags=0 WHERE owner_id=? AND collar_object_id=?";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setInt(1, ownerId);
			ps.setInt(2, collarObjectId);
			return ps.executeUpdate() > 0;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to recover collar " + collarObjectId, e);
			return false;
		}
	}

	public static boolean markWounded(int ownerId, int collarObjectId)
	{
		// A fresh death always sets the worst severity, so a wounded beast cannot
		// be nursed down a notch and then die again to end up lighter than before.
		final String sql = "UPDATE tamed_pet SET wound_flags=? WHERE owner_id=? AND collar_object_id=? AND active=1";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setInt(1, WOUND_MAX);
			ps.setInt(2, ownerId);
			ps.setInt(3, collarObjectId);
			return ps.executeUpdate() > 0;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to mark collar " + collarObjectId + " wounded", e);
			return false;
		}
	}

	/**
	 * Nursing wounds with food instead of bond. The severity is stepped down but
	 * never past healthy, so over-feeding a sound beast is harmless and the
	 * caller can always tell whether anything changed.
	 */
	public static int healWounds(int ownerId, int collarObjectId, int steps)
	{
		if ((steps <= 0) || (collarObjectId <= 0))
		{
			return 0;
		}
		final String sql = "UPDATE tamed_pet SET wound_flags=GREATEST(0, wound_flags-?) WHERE owner_id=? AND collar_object_id=? AND active=1 AND wound_flags>0";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setInt(1, steps);
			ps.setInt(2, ownerId);
			ps.setInt(3, collarObjectId);
			return ps.executeUpdate();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to heal collar " + collarObjectId + " wounds", e);
			return 0;
		}
	}

	/**
	 * Nursing a wounded beast out of its collar costs trust. The wound flag is
	 * cleared and the profile's bond is reduced by the given amount (never below
	 * zero) in one statement, so recovery always has a price.
	 */
	public static int recoverAtCost(int ownerId, int collarObjectId, int bondCost)
	{
		final String sql = "UPDATE tamed_pet SET wound_flags=0, bond=GREATEST(0, bond-?) WHERE owner_id=? AND collar_object_id=? AND wound_flags<>0 AND active=1";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setInt(1, bondCost);
			ps.setInt(2, ownerId);
			ps.setInt(3, collarObjectId);
			return ps.executeUpdate();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to recover collar " + collarObjectId + " at cost", e);
			return 0;
		}
	}

	/**
	 * Writes every permanent capture row in one transaction: the profile, the
	 * skill slots, the bestiary sighting and the capture history event. Either
	 * all of them land or none do, so a failure mid-way cannot leave an orphan
	 * profile that holds a synthetic band id nobody owns.
	 */
	public static boolean persistCapture(TameProfile profile, Collection<TameSkillPolicy.Selection> selections, int ownerId, int sourceNpcId, int potential, double growth, int awakeningStage, boolean rareInheritance, String captureEventData)
	{
		if (profile == null)
		{
			return false;
		}
		try (Connection con = DatabaseFactory.getConnection())
		{
			con.setAutoCommit(false);
			try
			{
				try (PreparedStatement ps = con.prepareStatement(PROFILE_UPSERT))
				{
					bindProfile(ps, profile);
					ps.executeUpdate();
				}
				if ((selections != null) && !selections.isEmpty())
				{
					try (PreparedStatement ps = con.prepareStatement(SKILL_INSERT))
					{
						for (TameSkillPolicy.Selection selection : selections)
						{
							if ((selection == null) || (selection.getSkill() == null))
							{
								continue;
							}
							ps.setString(1, profile.getUuid());
							ps.setInt(2, selection.getSkill().getId());
							ps.setInt(3, selection.getSkill().getLevel());
							ps.setString(4, selection.getSlot());
							ps.setString(5, selection.getQuality());
							ps.setInt(6, selection.getUnlockLevel());
							ps.setBoolean(7, selection.isAwakened());
							ps.setBoolean(8, !isAwakeningSlot(selection.getSlot()));
							ps.addBatch();
						}
						ps.executeBatch();
					}
				}
				try (PreparedStatement ps = con.prepareStatement(BESTIARY_UPSERT))
				{
					ps.setInt(1, ownerId);
					ps.setInt(2, sourceNpcId);
					ps.setInt(3, potential);
					ps.setDouble(4, growth);
					ps.setInt(5, awakeningStage);
					ps.setInt(6, rareInheritance ? 1 : 0);
					ps.executeUpdate();
				}
				try (PreparedStatement ps = con.prepareStatement(HISTORY_INSERT))
				{
					ps.setString(1, profile.getUuid());
					ps.setString(2, "CAPTURE");
					ps.setString(3, captureEventData == null ? "" : captureEventData);
					ps.executeUpdate();
				}
				con.commit();
				return true;
			}
			catch (Exception e)
			{
				con.rollback();
				throw e;
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "TameProfileRepository: failed to persist capture " + profile.getUuid(), e);
			return false;
		}
	}

	/**
	 * Removes every profile row for a collar. Used to clean up after a failed
	 * capture so the reserved band cannot leak, and by the release command.
	 */
	public static boolean deleteByCollar(int collarObjectId)
	{
		if (collarObjectId <= 0)
		{
			return false;
		}
		try (Connection con = DatabaseFactory.getConnection())
		{
			con.setAutoCommit(false);
			try
			{
				final String uuid = selectUuid(con, collarObjectId, 0);
				final boolean deleted = deleteRows(con, collarObjectId, uuid);
				if (deleted)
				{
					// Without this the delete is rolled back when the connection
					// closes, so a failed capture would leave its rows behind.
					con.commit();
				}
				else
				{
					con.rollback();
				}
				return deleted;
			}
			catch (Exception e)
			{
				con.rollback();
				throw e;
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to delete collar " + collarObjectId, e);
			return false;
		}
	}

	/**
	 * One tame whose collar item is no longer in the world.
	 */
	public static final class Orphan
	{
		public final int collarObjectId;
		public final int ownerId;
		public final String uuid;
		public final String petName;
		public final int syntheticNpcId;
		/** Why it was judged an orphan, in words a human can check. */
		public final String reason;

		Orphan(int collarObjectId, int ownerId, String uuid, String petName, int syntheticNpcId, String reason)
		{
			this.collarObjectId = collarObjectId;
			this.ownerId = ownerId;
			this.uuid = uuid;
			this.petName = petName;
			this.syntheticNpcId = syntheticNpcId;
			this.reason = reason;
		}

		@Override
		public String toString()
		{
			return "collar " + collarObjectId + " \"" + petName + "\" (uuid " + uuid + ", owner " + ownerId + ", synthetic npc " + syntheticNpcId + "): " + reason;
		}
	}

	/**
	 * Finds tames whose collar item no longer exists, which is what a destroyed
	 * collar or a deleted character leaves behind.
	 *
	 * <p>Nothing in this module can clean those up at the moment they happen. The
	 * capture path deletes a row when a capture fails, and the release command
	 * deletes one on request, but the two ways a collar actually tends to vanish -
	 * a player throwing it away, or the owner deleting the character - are stock
	 * server behaviour that never passes through here. The row then survives, gets
	 * read by hydrateAll on every boot, and is reported as a restored collar that
	 * does not exist. TameForge seeds its id band from MAX(synthetic_npc_id), so
	 * these also push the band forward permanently, which is why the count only
	 * ever grows.
	 *
	 * <p>Two conditions have to hold, and both are checked rather than assumed:
	 *
	 * <ul>
	 * <li>No row in {@code items} carries this collar's object id. The items table
	 * holds every item instance in the world, in a pack or otherwise, so an item
	 * that is genuinely still held cannot be missing from it.</li>
	 * <li>And if a row <em>does</em> exist under that object id but its item_id is
	 * not the configured collar, it is not this module's collar and the row is
	 * still treated as an orphan. This costs nothing and closes the one hole that
	 * would otherwise be catastrophic: an object id reused for an unrelated item
	 * would otherwise keep a phantom tame alive forever.</li>
	 * </ul>
	 *
	 * <p>Deliberately does not consider the owner. A tame whose owner character is
	 * gone is already caught by the collar being gone with the character, and
	 * joining the characters table in would make the query depend on a second
	 * table for no additional safety.
	 *
	 * <p>Reports rather than throws, and returns an empty list on any failure, so a
	 * database problem at startup cannot be mistaken for "there is nothing to
	 * clean" and silently skipped.
	 *
	 * @param collarItemId the configured collar item id, from {@code CollarItemId}
	 * @return the orphans found, never null
	 */
	public static List<Orphan> findOrphanedCollars(int collarItemId)
	{
		final List<Orphan> orphans = new ArrayList<>();
		final String sql = "SELECT t.collar_object_id, t.owner_id, t.tame_uuid, t.pet_name, t.synthetic_npc_id, i.item_id FROM tamed_pet t LEFT JOIN items i ON i.object_id = t.collar_object_id WHERE t.active = 1 ORDER BY t.collar_object_id";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery())
		{
			while (rs.next())
			{
				final int collarObjectId = rs.getInt("collar_object_id");
				// getObject and not getInt, because SQL NULL has to stay
				// distinguishable from a real item id of 0.
				final Object itemId = rs.getObject("item_id");
				final String uuid = rs.getString("tame_uuid");
				final String petName = rs.getString("pet_name");
				final int ownerId = rs.getInt("owner_id");
				final int syntheticNpcId = rs.getInt("synthetic_npc_id");
				final String reason;
				if (itemId == null)
				{
					reason = "no item with object id " + collarObjectId + " exists";
				}
				else if (((Integer) itemId).intValue() != collarItemId)
				{
					reason = "object id " + collarObjectId + " is now item " + itemId + ", not a collar (" + collarItemId + ")";
				}
				else
				{
					continue;
				}
				orphans.add(new Orphan(collarObjectId, ownerId, uuid, petName, syntheticNpcId, reason));
			}
		}
		catch (Exception e)
		{
			// Logged loudly rather than swallowed: an empty list here reads as
			// "nothing to clean", which is the opposite of what a broken query means.
			LOGGER.log(Level.SEVERE, "TameProfileRepository: could not look for orphaned collars, so none were removed. If this repeats, the collar list will keep growing.", e);
		}
		return orphans;
	}

	/**
	 * Deletes an orphan's rows using the same path as a failed capture, so the
	 * cascade and the child rows are handled identically no matter which route
	 * noticed the collar was gone.
	 *
	 * @return true if the profile row went
	 */
	public static boolean purgeOrphan(Orphan orphan)
	{
		return deleteByCollar(orphan.collarObjectId);
	}

	/**
	 * The release path for an owner who wants this creature gone for good. The
	 * rows are deleted transactionally and the caller is handed the synthetic
	 * npc id back so TameForge can free the band slot as well.
	 */
	public static int releaseCollar(int ownerId, int collarObjectId)
	{
		if ((ownerId <= 0) || (collarObjectId <= 0))
		{
			return 0;
		}
		try (Connection con = DatabaseFactory.getConnection())
		{
			con.setAutoCommit(false);
			try
			{
				final String uuid = selectUuid(con, collarObjectId, ownerId);
				final int syntheticNpcId = selectSyntheticNpcId(con, collarObjectId);
				if ((uuid == null) || !deleteRows(con, collarObjectId, uuid))
				{
					con.rollback();
					return 0;
				}
				con.commit();
				return syntheticNpcId;
			}
			catch (Exception e)
			{
				con.rollback();
				throw e;
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to release collar " + collarObjectId, e);
			return 0;
		}
	}

	private static String selectUuid(Connection con, int collarObjectId, int ownerId) throws Exception
	{
		final String sql = ownerId > 0 ? "SELECT tame_uuid FROM tamed_pet WHERE owner_id=? AND collar_object_id=? AND active=1" : "SELECT tame_uuid FROM tamed_pet WHERE collar_object_id=?";
		try (PreparedStatement ps = con.prepareStatement(sql))
		{
			if (ownerId > 0)
			{
				ps.setInt(1, ownerId);
				ps.setInt(2, collarObjectId);
			}
			else
			{
				ps.setInt(1, collarObjectId);
			}
			try (ResultSet rs = ps.executeQuery())
			{
				return rs.next() ? rs.getString("tame_uuid") : null;
			}
		}
	}

	private static int selectSyntheticNpcId(Connection con, int collarObjectId) throws Exception
	{
		try (PreparedStatement ps = con.prepareStatement("SELECT synthetic_npc_id FROM tamed_pet WHERE collar_object_id=?"))
		{
			ps.setInt(1, collarObjectId);
			try (ResultSet rs = ps.executeQuery())
			{
				return rs.next() ? rs.getInt("synthetic_npc_id") : 0;
			}
		}
	}

	private static boolean deleteRows(Connection con, int collarObjectId, String uuid) throws Exception
	{
		if (uuid != null)
		{
			try (PreparedStatement ps = con.prepareStatement("DELETE FROM tamed_pet_history WHERE tame_uuid=?"))
			{
				ps.setString(1, uuid);
				ps.executeUpdate();
			}
			try (PreparedStatement ps = con.prepareStatement("DELETE FROM tamed_pet_skill WHERE tame_uuid=?"))
			{
				ps.setString(1, uuid);
				ps.executeUpdate();
			}
			// Gear held in the vault has to go with the tame, or its item would
			// stay on ItemLocation.LEASE forever: still on disk, owned by a
			// player, and readable by no container at all. This only removes
			// items still parked in the vault; a release that already handed
			// the gear back moved it to the inventory first, so its loc is no
			// longer LEASE and this leaves it alone.
			try (PreparedStatement ps = con.prepareStatement("DELETE i FROM items i INNER JOIN tamed_pet_equipment e ON e.item_object_id=i.object_id WHERE e.tame_uuid=? AND i.loc='LEASE'"))
			{
				ps.setString(1, uuid);
				ps.executeUpdate();
			}
			try (PreparedStatement ps = con.prepareStatement("DELETE FROM tamed_pet_equipment WHERE tame_uuid=?"))
			{
				ps.setString(1, uuid);
				ps.executeUpdate();
			}
		}
		try (PreparedStatement ps = con.prepareStatement("DELETE FROM tamed_pet WHERE collar_object_id=?"))
		{
			ps.setInt(1, collarObjectId);
			return ps.executeUpdate() > 0;
		}
	}
	
	public static boolean awaken(int ownerId, int collarObjectId, String path)
	{
		final String normalized = path == null ? "" : path.trim().toUpperCase();
		if (!(normalized.equals("FANG") || normalized.equals("PACK") || normalized.equals("SHADOW") || normalized.equals("ARCANE") || normalized.equals("GUARDIAN")))
		{
			return false;
		}
		final String sql = "UPDATE tamed_pet SET awakening_stage=1, awakening_path=? WHERE owner_id=? AND collar_object_id=? AND current_level>=30 AND awakening_stage=0";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setString(1, normalized);
			ps.setInt(2, ownerId);
			ps.setInt(3, collarObjectId);
			if (ps.executeUpdate() == 0)
			{
				return false;
			}
			// Every first-awakening technique, because rarity decides how many of them a
			// beast has: one for a common beast, up to three for a legendary one. The
			// player chooses a path, not a technique, so all of the beast's own
			// techniques are granted together. Granting only the bare 'AWAKENING' row
			// left the rarer extras permanently dark.
			//
			// AWAKENING2 is excluded on purpose. It is the technique the second
			// awakening grants, and a LIKE 'AWAKENING%' matches it because that string
			// starts with AWAKENING - which is precisely why the slot is spelled without
			// the underscore the numbered techniques use.
			try (PreparedStatement skill = con.prepareStatement("UPDATE tamed_pet_skill s INNER JOIN tamed_pet p ON p.tame_uuid=s.tame_uuid SET s.awakened=1, s.enabled=1 WHERE p.owner_id=? AND p.collar_object_id=? AND s.slot_type LIKE 'AWAKENING%' AND s.slot_type <> ?"))
			{
				skill.setInt(1, ownerId);
				skill.setInt(2, collarObjectId);
				skill.setString(3, SECOND_AWAKENING_SLOT);
				skill.executeUpdate();
			}
			return true;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to awaken collar " + collarObjectId, e);
			return false;
		}
	}
	
	/**
	 * Second awakening: unlocks the affinity proc and nothing else.
	 *
	 * <p>Deliberately separate from {@link #awaken(int, int, String)} rather
	 * than a path argument, because the proc follows affinity and not the path,
	 * so there is no second choice for the player to get wrong. The first
	 * awakening's path is left alone - second awakening adds behaviour on top of
	 * the numbers it already granted.
	 *
	 * @param ownerId the collar's owner
	 * @param collarObjectId the collar
	 * @param requiredLevel level the tame must have reached
	 */
	public static boolean awakenSecond(int ownerId, int collarObjectId, int requiredLevel)
	{
		final String sql = "UPDATE tamed_pet SET awakening_stage=2 WHERE owner_id=? AND collar_object_id=? AND current_level>=? AND awakening_stage=1";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setInt(1, ownerId);
			ps.setInt(2, collarObjectId);
			ps.setInt(3, requiredLevel);
			if (ps.executeUpdate() == 0)
			{
				return false;
			}
		// Second awakening has a technique of its own to grant - AWAKENING2, rolled
		// into the deck at capture time and locked until now - on top of the affinity
		// proc, which is applied to the live summon and never stored in the deck.
		//
		// It also used to re-open the whole AWAKENING family here, because a beast
		// that had been through both awakenings showed its later techniques as still
		// locked. That is no longer needed and would be wrong: those techniques belong
		// to the first awakening, which grants them, and a second awakening that hands
		// out a second copy of what the first one gave makes the second one look like
		// a free reroll.
		try (PreparedStatement skill = con.prepareStatement("UPDATE tamed_pet_skill s INNER JOIN tamed_pet p ON p.tame_uuid=s.tame_uuid SET s.awakened=1, s.enabled=1 WHERE p.owner_id=? AND p.collar_object_id=? AND s.slot_type=?"))
			{
			skill.setInt(1, ownerId);
			skill.setInt(2, collarObjectId);
			skill.setString(3, SECOND_AWAKENING_SLOT);
			skill.executeUpdate();
			}
			return true;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to second-awaken collar " + collarObjectId, e);
			return false;
		}
	}

	public static boolean applyStoredSkills(PetData data, String uuid)
	{
		if ((data == null) || (uuid == null))
		{
			return false;
		}
		boolean found = false;
		final String sql = "SELECT skill_id, skill_level, unlock_level, enabled FROM tamed_pet_skill WHERE tame_uuid=? ORDER BY unlock_level, slot_type";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setString(1, uuid);
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					if (!rs.getBoolean("enabled"))
					{
						continue;
					}
						final int skillId = rs.getInt("skill_id");
						final int skillLevel = rs.getInt("skill_level");
						final int petSkillId = TameSkillPolicy.petSkillId(skillId);
						if ((skillId > 0) && (skillLevel > 0) && (SkillData.getInstance().getSkill(petSkillId, skillLevel) != null))
						{
							data.addNewSkill(petSkillId, skillLevel, rs.getInt("unlock_level"));
						found = true;
					}
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to apply stored skills for " + uuid, e);
		}
		return found;
	}
	
	/**
	 * One row of a tame's skill deck, in the order the deck is shown.
	 */
	public static final class DeckSkill
	{
		private final int _skillId;
		private final int _skillLevel;
		private final String _slot;
		private final String _quality;
		private final int _unlockLevel;
		private final boolean _awakened;
		private final boolean _enabled;

		DeckSkill(int skillId, int skillLevel, String slot, String quality, int unlockLevel, boolean awakened, boolean enabled)
		{
			_skillId = skillId;
			_skillLevel = skillLevel;
			_slot = slot;
			_quality = quality;
			_unlockLevel = unlockLevel;
			_awakened = awakened;
			_enabled = enabled;
		}

		public int getSkillId()
		{
			return _skillId;
		}

		public int getSkillLevel()
		{
			return _skillLevel;
		}

		public String getSlot()
		{
			return _slot;
		}

		public String getQuality()
		{
			return _quality;
		}

		public int getUnlockLevel()
		{
			return _unlockLevel;
		}

		public boolean isAwakened()
		{
			return _awakened;
		}

		public boolean isEnabled()
		{
			return _enabled;
		}

		/**
		 * True when this row is something the tame could actually cast, ignoring
		 * level for now. A token is only worth minting for a row that is a live
		 * active skill and is not waiting on the awakening choice.
		 */
		public boolean isCastable()
		{
			// Looked up through petSkillId, the same substitution the cast path uses,
			// so a row that the tame really can cast is never judged unusable here.
			final org.l2jmobius.gameserver.model.skill.Skill skill = SkillData.getInstance().getSkill(TameSkillPolicy.petSkillId(_skillId), _skillLevel);
			return _enabled && (skill != null) && skill.isActive() && !skill.isPassive();
		}
	}

	/**
	 * The tame's recorded skills in deck order.
	 *
	 * <p>The sort is deliberately the same {@code unlock_level, slot_type} the
	 * skill page uses, so deck position N here is the same skill the player sees
	 * in position N on the page, and token N casts that one. Anything that
	 * reorders the deck (an awakening, a re-forged collar) therefore moves the
	 * token instead of leaving it pointing at a skill that no longer exists.
	 */
	public static java.util.List<DeckSkill> getOrderedSkills(String uuid)
	{
		final java.util.List<DeckSkill> deck = new java.util.ArrayList<>();
		if (uuid == null)
		{
			return deck;
		}
		final String sql = "SELECT skill_id, skill_level, slot_type, inheritance_quality, unlock_level, awakened, enabled FROM tamed_pet_skill WHERE tame_uuid=? ORDER BY unlock_level, slot_type";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setString(1, uuid);
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					deck.add(new DeckSkill(rs.getInt("skill_id"), rs.getInt("skill_level"), rs.getString("slot_type"),
						rs.getString("inheritance_quality"), rs.getInt("unlock_level"), rs.getBoolean("awakened"), rs.getBoolean("enabled")));
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to load ordered skill deck for " + uuid, e);
		}
		return deck;
	}

	public static SkillRecord getSkillRecord(String uuid, int skillId, int skillLevel)
	{
		final String sql = "SELECT slot_type, inheritance_quality, unlock_level, awakened, enabled FROM tamed_pet_skill WHERE tame_uuid=? AND skill_id=? AND skill_level=? LIMIT 1";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setString(1, uuid);
			ps.setInt(2, skillId);
			ps.setInt(3, skillLevel);
			try (ResultSet rs = ps.executeQuery())
			{
				if (rs.next())
				{
					return new SkillRecord(rs.getString("slot_type"), rs.getString("inheritance_quality"), rs.getInt("unlock_level"), rs.getBoolean("awakened"), rs.getBoolean("enabled"));
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to validate skill for " + uuid, e);
		}
		return null;
	}

	public static boolean hasSkills(String uuid)
	{
		final String sql = "SELECT 1 FROM tamed_pet_skill WHERE tame_uuid=? LIMIT 1";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setString(1, uuid);
			try (ResultSet rs = ps.executeQuery())
			{
				return rs.next();
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to check skills for " + uuid, e);
			return false;
		}
	}

	public static String getSkillSummary(String uuid)
	{
		final StringBuilder result = new StringBuilder();
		final String sql = "SELECT skill_id, skill_level, slot_type, inheritance_quality, unlock_level FROM tamed_pet_skill WHERE tame_uuid=? ORDER BY unlock_level, slot_type";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setString(1, uuid);
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					final org.l2jmobius.gameserver.model.skill.Skill skill = SkillData.getInstance().getSkill(rs.getInt("skill_id"), rs.getInt("skill_level"));
					final String name = skill == null ? "Inherited technique" : skill.getName();
					result.append(escapeHtml(rs.getString("slot_type"))).append(" - ").append(escapeHtml(name)).append(" Lv.").append(rs.getInt("skill_level")).append(" - ").append(escapeHtml(rs.getString("inheritance_quality"))).append(" - unlock ").append(rs.getInt("unlock_level")).append("<br>");
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to load skills for " + uuid, e);
		}
		return result.length() == 0 ? "No inherited skills recorded.<br>" : result.toString();
	}

	/**
	 * The technique deck as a list, for the collar page.
	 *
	 * <p>Read-only by design. Every technique here is cast by {@link TameAutoCast}, which
	 * picks a target and a moment on its own, so there is nothing on this page for a
	 * player to press. The per-technique USE buttons that used to sit under each entry
	 * were removed rather than hidden: they duplicated the auto-cast decision with a
	 * manual one, and a manual fire could only ever disagree with it - picking the
	 * player's current selection instead of what the beast is fighting, and burning
	 * the technique's cooldown on that.
	 *
	 * <p>What the entries still show is whether the technique is unlocked and whether
	 * anything is gating it, because "it never used that" is otherwise indistinguishable
	 * from "it was never going to".
	 *
	 * @param uuid the profile
	 * @param currentLevel the level to judge unlock levels against
	 * @param summoned whether the tame is out right now, which only changes the wording
	 */
	public static String getSkillDeck(String uuid, int currentLevel, boolean summoned)
	{
		final StringBuilder result = new StringBuilder();
		final String sql = "SELECT skill_id, skill_level, slot_type, inheritance_quality, unlock_level, awakened, enabled FROM tamed_pet_skill WHERE tame_uuid=? ORDER BY unlock_level, slot_type";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setString(1, uuid);
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					final int skillId = rs.getInt("skill_id");
					final int skillLevel = rs.getInt("skill_level");
					final int petSkillId = TameSkillPolicy.petSkillId(skillId);
					final org.l2jmobius.gameserver.model.skill.Skill skill = SkillData.getInstance().getSkill(petSkillId, skillLevel);
					final String name = skill == null ? "Inherited technique" : skill.getName();
					final int unlockLevel = rs.getInt("unlock_level");
					final boolean awakeningSkill = isAwakeningSlot(rs.getString("slot_type"));
					final boolean usable = rs.getBoolean("enabled") && (!awakeningSkill || rs.getBoolean("awakened")) && (unlockLevel <= currentLevel) && (skill != null) && !skill.isPassive();
					final String status = usable ? (summoned ? "AUTO-CAST" : "READY WHEN SUMMONED") : (unlockLevel > currentLevel ? "UNLOCKS AT LEVEL " + unlockLevel : (skill != null && skill.isPassive() ? "PASSIVE" : "AWAKENING LOCKED"));
					final String quality = rs.getString("inheritance_quality");
					final String qualityColor = qualityColor(quality);
					final String statusColor = usable ? "7CFFB2" : "7D8590";
					result.append("<font color=6E7681>").append(escapeHtml(rs.getString("slot_type"))).append("</font> <font color=D7DCE2>").append(escapeHtml(name)).append(" Lv.").append(skillLevel).append("</font><br><font color=").append(qualityColor).append(">").append(escapeHtml(quality)).append("</font><font color=6E7681> - </font><font color=").append(statusColor).append(">").append(status).append("</font><br>");
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to build skill deck for " + uuid, e);
		}
		return result.length() == 0 ? "No inherited skills recorded.<br>" : result.toString();
	}

	public static final class SkillRecord
	{
		private final String _slot;
		private final String _quality;
		private final int _unlockLevel;
		private final boolean _awakened;
		private final boolean _enabled;

		private SkillRecord(String slot, String quality, int unlockLevel, boolean awakened, boolean enabled)
		{
			_slot = slot;
			_quality = quality;
			_unlockLevel = unlockLevel;
			_awakened = awakened;
			_enabled = enabled;
		}

		public int getUnlockLevel()
		{
			return _unlockLevel;
		}

		public boolean isAwakened()
		{
			return _awakened;
		}

		public boolean isEnabled()
		{
			return _enabled;
		}

	/**
	 * Whether this is an awakening technique rather than an ordinary skill.
	 *
	 * <p>Matches the whole {@code AWAKENING} family, not just the bare name. Rarity
	 * decides how many awakening techniques a beast is offered - one for a common
	 * beast, up to three for a legendary one with high skill potential - and the
	 * extras are numbered AWAKENING_2 and AWAKENING_3. Testing for the exact string
	 * meant those extras were classified as ordinary skills: unlocked the moment the
	 * beast hit the unlock level, drawable in the deck, and never touched by the
	 * awakening that was supposed to gate them. That is the "second awakening gives
	 * me both skills" report - the second technique was never behind the gate.
	 */
	public boolean isAwakening()
	{
		return isAwakeningSlot(_slot);
	}

	}

	private static String qualityColor(String quality)
	{
		if ("FAINT".equalsIgnoreCase(quality))
		{
			return "7D8590";
		}
		if ("REFINED".equalsIgnoreCase(quality))
		{
			return "B392F0";
		}
		if ("PRISMATIC".equalsIgnoreCase(quality))
		{
			return "F2CC60";
		}
		if ("NATURAL".equalsIgnoreCase(quality))
		{
			return "58A6FF";
		}
		if ("STABLE".equalsIgnoreCase(quality))
		{
			return "7EE787";
		}
		return "C9D1D9";
	}

	private static String escapeHtml(String value)
	{
		if (value == null)
		{
			return "";
		}
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
	

	public static Map<String, EquipmentRecord> loadCustomEquipment(String uuid)
	{
		final Map<String, EquipmentRecord> result = new LinkedHashMap<>();
		if (uuid == null)
		{
			return result;
		}
		// Every slot is read, not just the current names, so gear saved under the
		// old slot names is picked up and rewritten instead of being stranded.
		final String sql = "SELECT slot_type, item_id, enchant_level, custom_data, item_object_id FROM tamed_pet_equipment WHERE tame_uuid=? ORDER BY slot_type";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setString(1, uuid);
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					final String legacy = rs.getString("slot_type");
					final String slot = TameEquipmentCatalog.migrateSlot(legacy);
					final int itemId = rs.getInt("item_id");
					String customData = rs.getString("custom_data");
					final int enchant = rs.getInt("enchant_level");
					final int itemObjectId = rs.getInt("item_object_id");
					// Skip a slot we cannot read instead of letting it abandon the
					// whole load, which would make every other piece of gear look
					// like it had vanished too.
					if (slot == null)
					{
						continue;
					}
					final boolean legacyName = !slot.equals(legacy);
					// Old rows predate derived bonuses, so work one out now and
					// write it back, otherwise the gear would grant nothing.
					if (legacyName || ((customData == null) || customData.isEmpty()))
					{
						final String rebuilt = TameEquipmentCatalog.encodeBonus(itemId, enchant);
						if (rebuilt != null)
						{
							customData = rebuilt;
							// A legacy row only gets adopted while its slot is still
							// empty. Otherwise a leftover FANG row would overwrite the
							// weapon the player has already installed.
							if (!legacyName || !hasCustomEquipment(uuid, slot))
							{
								upgradeCustomEquipment(uuid, legacy, slot, itemId, enchant, customData);
							}
							else
							{
								continue;
							}
						}
					}
					result.put(slot, new EquipmentRecord(slot, itemId, enchant, customData, itemObjectId));
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to load custom equipment for " + uuid, e);
		}
		return result;
	}

	/** Whether a slot already holds gear under its current name. */
	private static boolean hasCustomEquipment(String uuid, String slot)
	{
		final String sql = "SELECT 1 FROM tamed_pet_equipment WHERE tame_uuid=? AND slot_type=? LIMIT 1";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setString(1, uuid);
			ps.setString(2, slot);
			try (ResultSet rs = ps.executeQuery())
			{
				return rs.next();
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to check existing gear for " + uuid, e);
			// Assume it is there, so a failed check can never clobber real gear.
			return true;
		}
	}

	/** Rewrites one legacy gear row under its current slot name. */
	private static boolean upgradeCustomEquipment(String uuid, String legacySlot, String slot, int itemId, int enchant, String customData)
	{
		if (slot == null)
		{
			return false;
		}
		final String sql = "INSERT INTO tamed_pet_equipment (tame_uuid, slot_type, item_id, enchant_level, custom_data) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE slot_type=VALUES(slot_type), item_id=VALUES(item_id), enchant_level=VALUES(enchant_level), custom_data=VALUES(custom_data)";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setString(1, uuid);
			ps.setString(2, slot);
			ps.setInt(3, itemId);
			ps.setInt(4, Math.max(0, Math.min(16, enchant)));
			ps.setString(5, customData);
			ps.executeUpdate();
			if (!slot.equals(legacySlot))
			{
				try (PreparedStatement drop = con.prepareStatement("DELETE FROM tamed_pet_equipment WHERE tame_uuid=? AND slot_type=?"))
				{
					drop.setString(1, uuid);
					drop.setString(2, legacySlot);
					drop.executeUpdate();
				}
			}
			return true;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to upgrade gear row " + legacySlot + " for " + uuid, e);
			return false;
		}
	}

	public static boolean saveCustomEquipment(String uuid, String slotType, int itemId, int enchantLevel, String customData, int itemObjectId)
	{
		if ((uuid == null) || (slotType == null) || (itemId <= 0))
		{
			return false;
		}
		try (Connection con = DatabaseFactory.getConnection())
		{
			con.setAutoCommit(false);
			try (PreparedStatement clear = con.prepareStatement("DELETE FROM tamed_pet_equipment WHERE tame_uuid=? AND slot_type=?"))
			{
				clear.setString(1, uuid);
				clear.setString(2, slotType);
				clear.executeUpdate();
			}
			try (PreparedStatement insert = con.prepareStatement("INSERT INTO tamed_pet_equipment (tame_uuid, slot_type, item_id, enchant_level, custom_data, item_object_id) VALUES (?, ?, ?, ?, ?, ?)"))
			{
				insert.setString(1, uuid);
				insert.setString(2, slotType);
				insert.setInt(3, itemId);
				insert.setInt(4, Math.max(0, Math.min(16, enchantLevel)));
				insert.setString(5, customData == null ? "" : customData);
				insert.setInt(6, Math.max(0, itemObjectId));
				insert.executeUpdate();
			}
			con.commit();
			return true;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to save custom equipment for " + uuid + " / " + slotType, e);
			return false;
		}
	}

	public static boolean clearCustomEquipment(String uuid, String slotType)
	{
		if ((uuid == null) || (slotType == null))
		{
			return false;
		}
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement("DELETE FROM tamed_pet_equipment WHERE tame_uuid=? AND slot_type=?"))
		{
			ps.setString(1, uuid);
			ps.setString(2, slotType);
			return ps.executeUpdate() > 0;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to clear custom equipment for " + uuid + " / " + slotType, e);
			return false;
		}
	}

	public static String getEquipmentSummary(String uuid)
	{
		final StringBuilder result = new StringBuilder();
		final String sql = "SELECT slot_type, item_id, enchant_level, custom_data FROM tamed_pet_equipment WHERE tame_uuid=? ORDER BY slot_type";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setString(1, uuid);
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					result.append(escapeHtml(rs.getString("slot_type"))).append(" - equipped");
					if (rs.getInt("enchant_level") > 0)
					{
						result.append(" +").append(rs.getInt("enchant_level"));
					}
					result.append("<br>");
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to load equipment for " + uuid, e);
		}
		return result.length() == 0 ? "No equipment recorded.<br>" : result.toString();
	}

	public static final class EquipmentRecord
	{
		private final String _slotType;
		private final int _itemId;
		private final int _enchantLevel;
		private final String _customData;
		private final int _itemObjectId;

		public EquipmentRecord(String slotType, int itemId, int enchantLevel, String customData, int itemObjectId)
		{
			_slotType = slotType;
			_itemId = itemId;
			_enchantLevel = enchantLevel;
			_customData = customData == null ? "" : customData;
			_itemObjectId = itemObjectId;
		}

		public String getSlotType()
		{
			return _slotType;
		}

		public int getItemId()
		{
			return _itemId;
		}

		public int getEnchantLevel()
		{
			return _enchantLevel;
		}

		public String getCustomData()
		{
			return _customData;
		}

		/** The object id of the item held in the vault, or 0 for a legacy row. */
		public int getItemObjectId()
		{
			return _itemObjectId;
		}
	}

	public static String getHistorySummary(String uuid)
	{
		final StringBuilder result = new StringBuilder();
		final String sql = "SELECT event_type, event_data, created_at FROM tamed_pet_history WHERE tame_uuid=? ORDER BY id DESC LIMIT 10";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setString(1, uuid);
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					final String eventType = rs.getString("event_type");
					final String label = "CAPTURE".equalsIgnoreCase(eventType) ? "Individual created" : "RENAME".equalsIgnoreCase(eventType) ? "Name changed" : "AWAKEN".equalsIgnoreCase(eventType) ? "Awakening recorded" : "RECOVERY".equalsIgnoreCase(eventType) ? "Wounds recovered" : "FEED".equalsIgnoreCase(eventType) ? "Feedings" : "Profile event recorded";
					result.append(escapeHtml(eventType)).append(" - ").append(label).append("<br>");
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to load history for " + uuid, e);
		}
		return result.length() == 0 ? "No history recorded.<br>" : result.toString();
	}

	public static void logEvent(String uuid, String eventType, String eventData)
	{
		final String sql = "INSERT INTO tamed_pet_history (tame_uuid, event_type, event_data) VALUES (?, ?, ?)";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setString(1, uuid);
			ps.setString(2, eventType);
			ps.setString(3, eventData == null ? "" : eventData);
			ps.executeUpdate();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to log " + eventType + " for " + uuid, e);
		}
	}
	
	public static String getBestiarySummary(int ownerId)
	{
		final StringBuilder result = new StringBuilder();
		final String sql = "SELECT source_npc_id, individuals_seen, best_potential, best_growth, highest_awakening, rare_skill_count FROM tamed_pet_bestiary WHERE owner_id=? ORDER BY last_seen_at DESC";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setInt(1, ownerId);
			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					final int npcId = rs.getInt("source_npc_id");
					final org.l2jmobius.gameserver.model.actor.templates.NpcTemplate template = NpcData.getInstance().getTemplate(npcId);
					final String name = template == null ? "Unknown creature" : template.getName();
						result.append("<font color=D7DCE2>").append(escapeHtml(name)).append("</font><br><font color=6E7681>seen ").append(rs.getInt("individuals_seen")).append(" - best potential ").append(rs.getInt("best_potential")).append(" - best growth ").append(String.format("%.2f%%", rs.getDouble("best_growth"))).append("<br>awakening ").append(rs.getInt("highest_awakening")).append(" - rare inheritances ").append(rs.getInt("rare_skill_count")).append("</font><br><br>");
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to load bestiary for " + ownerId, e);
		}
		return result.length() == 0 ? "No species discovered yet.<br>" : result.toString();
	}

	public static void recordBestiary(int ownerId, int npcId, int potential, double growth, int awakeningStage, boolean rareSkill)
	{
		final String sql = "INSERT INTO tamed_pet_bestiary (owner_id, source_npc_id, discovered, individuals_seen, best_potential, best_growth, highest_awakening, rare_skill_count) VALUES (?, ?, 1, 1, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE individuals_seen=individuals_seen+1, best_potential=GREATEST(best_potential, VALUES(best_potential)), best_growth=GREATEST(best_growth, VALUES(best_growth)), highest_awakening=GREATEST(highest_awakening, VALUES(highest_awakening)), rare_skill_count=rare_skill_count+VALUES(rare_skill_count)";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setInt(1, ownerId);
			ps.setInt(2, npcId);
			ps.setInt(3, potential);
			ps.setDouble(4, growth);
			ps.setInt(5, awakeningStage);
			ps.setInt(6, rareSkill ? 1 : 0);
			ps.executeUpdate();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to update bestiary for " + npcId, e);
		}
	}
	
	private static void bindProfile(PreparedStatement ps, TameProfile p) throws Exception
	{
		ps.setString(1, p.getUuid());
		ps.setInt(2, p.getCollarObjectId());
		ps.setInt(3, p.getOwnerId());
		ps.setInt(4, p.getSourceNpcId());
		ps.setInt(5, p.getSourceLevel());
		ps.setString(6, p.getSourceType());
		ps.setString(7, p.getRace());
		ps.setString(8, p.getFamily());
		ps.setString(9, p.getRole());
		ps.setString(10, p.getRarity());
		ps.setInt(11, p.getPotential());
		ps.setInt(12, p.getOffensePotential());
		ps.setInt(13, p.getDefensePotential());
		ps.setInt(14, p.getVitalityPotential());
		ps.setInt(15, p.getSkillPotential());
		ps.setDouble(16, p.getGrowthPercent());
		ps.setString(17, p.getTemperament());
		ps.setString(18, p.getAffinity());
		ps.setInt(19, p.getBond());
		ps.setInt(20, p.getAwakeningStage());
		ps.setString(21, p.getAwakeningPath());
		ps.setInt(22, p.getCurrentLevel());
		ps.setInt(23, p.getMaxPetLevel());
		ps.setLong(24, p.getProfileSeed());
		ps.setInt(25, p.getProfileVersion());
		ps.setString(26, p.getPetName());
		ps.setInt(27, p.getBaseHp());
		ps.setInt(28, p.getBaseMp());
		ps.setInt(29, p.getBasePAtk());
		ps.setInt(30, p.getBasePDef());
		ps.setInt(31, p.getBaseMAtk());
		ps.setInt(32, p.getBaseMDef());
		ps.setDouble(33, p.getConversionMultiplier());
		ps.setInt(34, p.getWoundFlags());
		ps.setInt(35, p.getSyntheticNpcId());
		// The insert writes 36 columns; 'active' is the literal 1 in the SQL and
		// 'worn' is this one, which is why the binding count differs from the
		// column count by exactly the active literal.
		ps.setBoolean(36, p.isWorn());
	}

	private static TameProfile readProfile(ResultSet rs) throws Exception
	{
		final TameProfile profile = new TameProfile(rs.getString("tame_uuid"), rs.getInt("collar_object_id"), rs.getInt("owner_id"), rs.getInt("source_npc_id"), rs.getInt("source_level"), rs.getString("source_type"), rs.getString("race"), rs.getString("family"), rs.getString("role"), rs.getString("rarity"), rs.getInt("potential"), rs.getInt("offense_potential"), rs.getInt("defense_potential"), rs.getInt("vitality_potential"), rs.getInt("skill_potential"), rs.getDouble("growth_percent"), rs.getString("temperament"), rs.getString("affinity"), rs.getInt("bond"), rs.getInt("awakening_stage"), rs.getString("awakening_path"), rs.getInt("current_level"), rs.getInt("max_pet_level"), rs.getLong("profile_seed"), rs.getInt("profile_version"), rs.getString("pet_name"), rs.getInt("base_hp"), rs.getInt("base_mp"), rs.getInt("base_patk"), rs.getInt("base_pdef"), rs.getInt("base_matk"), rs.getInt("base_mdef"), rs.getDouble("conversion_multiplier"), rs.getInt("wound_flags"));
		profile.setSyntheticNpcId(rs.getInt("synthetic_npc_id"));
		profile.setWorn(rs.getBoolean("worn"));
		return profile;
	}

	// --------------------------------------------------------------- worn collar ---
	//
	// "Wearing" a collar is this module's own state and the core has no part in it.
	//
	// A stock PET_COLLAR has no worn state at all. There is no equipment slot for
	// one, no appearance attached to it and no stat that changes when it is on - a
	// pet is either summoned or it is not, and that is the whole of stock's model.
	// There is also nothing to send: the item is in the player's inventory either
	// way, and no packet carries a worn flag.
	//
	// So the flag lives in this module's row on the profile, and it does two jobs
	// that stock cannot do:
	//
	//   - it marks which collar a recall answers. Recall brings back the worn
	//     beast, so a player who owns four collars and wears one gets that one
	//     rather than whichever row was touched most recently;
	//   - it decides what login restores. A worn beast comes back on its own; an
	//     unworn one stays in its collar until asked for.
	//
	// Wearing is per owner, not per collar: a beast belongs to whoever holds its
	// collar, and the collars are not tradable, so there is no case where two
	// players could claim the same one. Setting it therefore clears the owner's
	// other collars rather than checking them first.

	/**
	 * Marks one collar worn and everything else this owner owns as not worn.
	 *
	 * @return whether the collar being worn is the one that was already worn, which
	 *         is the caller's signal to take it off instead
	 */
	public static boolean wear(int ownerId, int collarObjectId)
	{
		final String sql = "UPDATE tamed_pet SET worn = (collar_object_id=?) WHERE owner_id=? AND active=1";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setInt(1, collarObjectId);
			ps.setInt(2, ownerId);
			ps.executeUpdate();
			return true;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to wear collar " + collarObjectId + " for owner " + ownerId, e);
			return false;
		}
	}

	/**
	 * Takes off whatever this owner is wearing, leaving all of them unworn.
	 *
	 * <p>Deliberately unconditional rather than flipping one row. There is exactly
	 * one worn collar per owner by construction, so "remove it" has an unambiguous
	 * answer, and a single statement cannot leave two collars worn if two happened to
	 * match.
	 */
	public static boolean unwear(int ownerId)
	{
		final String sql = "UPDATE tamed_pet SET worn=0 WHERE owner_id=? AND active=1 AND worn=1";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setInt(1, ownerId);
			return ps.executeUpdate() > 0;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to clear the worn collar for owner " + ownerId, e);
			return false;
		}
	}

	/**
	 * Finds the collar this owner is wearing, and only that one.
	 *
	 * <p>Strictly worn. This used to fall back to the most recently touched collar,
	 * which was there for the Recall Crystal: the crystal was a free-standing action
	 * and a player who had never worn anything would otherwise have found it did
	 * nothing on first use. The crystal is gone, and the fallback outlived its reason
	 * as a bug in the place it was never wanted - this is also what the login restore
	 * goes through, and login is not a request the player just made. Falling back
	 * there meant logging in re-summoned a beast whose collar was sitting unequipped
	 * in the inventory.
	 *
	 * <p>Recall still wants the forgiving behaviour, and now asks for it explicitly at
	 * the point of decision: {@code ResummonPet.findWornCollar} tries this first and
	 * then calls {@code findLastSummonedCollar}. Two callers, two different questions.
	 *
	 * @return the collar object id, or 0 when this owner is wearing nothing ready
	 */
	public static int findWornCollar(int ownerId)
	{
		final String sql = "SELECT collar_object_id FROM tamed_pet WHERE owner_id=? AND active=1 AND wound_flags=0 AND worn=1 LIMIT 1";
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			ps.setInt(1, ownerId);
			try (ResultSet rs = ps.executeQuery())
			{
				if (rs.next())
				{
					return rs.getInt(1);
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameProfileRepository: failed to find the worn collar for owner " + ownerId, e);
		}
		return 0;
	}
}
