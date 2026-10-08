/*
 * TameForge.java
 *
 * Gives every beast collar its own private NPC id, and files that collar's
 * individual profile under it.
 *
 * WHY THIS EXISTS
 * ---------------
 * Stock L2J keeps exactly one pet profile per NPC id, in a single table that
 * every player shares. A collar-bound system needs the opposite: many pets of
 * the same species, each with its own rolled stats, and the stock code has no
 * second key to tell them apart. The original build of this system answered
 * that by patching twenty core files so a collar id could be threaded through
 * every single lookup.
 *
 * This class answers it without patching anything. Stock Pet.spawnPet is handed
 * the NpcTemplate to summon from, so identity is decided at spawn time: the
 * captured creature's template is cloned, the clone is given a synthetic NPC id
 * reserved to this module, and the collar's own PetData is filed under that
 * same synthetic id. Every stock lookup then resolves against the correct
 * collar on its own, because the id it asks for is already unique to that
 * collar. The collar object id stays the real identity; the synthetic id is
 * only a runtime alias that lets stock code do its job.
 *
 * Only two private maps are touched, and only to add or remove entries:
 * NpcData._npcs and PetDataTable._pets. Nothing is replaced and no method is
 * patched, so the module is reversible by deleting its folder.
 */
package taming;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.data.holders.PetData;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.data.xml.PetDataTable;
import org.l2jmobius.gameserver.managers.ItemManager;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.instance.Pet;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.itemcontainer.PetInventory;

public final class TameForge
{
	private static final Logger LOGGER = Logger.getLogger(TameForge.class.getName());

	private static Map<Integer, NpcTemplate> _npcs;
	private static Map<Integer, PetData> _pets;

	// Guards every mutation of the two stock maps and the id cursor. The maps are
	// ordinary HashMaps owned by stock code, so writes from two threads at once
	// could corrupt them; serialising the module's own writes keeps that from
	// ever happening.
	private static final Object LOCK = new Object();

	private static int _low = 700000;
	private static int _high = 799999;
	private static final AtomicInteger _next = new AtomicInteger(700001);

	/**
	 * How long a tamed beast's corpse stays before the stock decay logic removes
	 * it. Stock DecayTaskManager uses NpcTemplate.getCorpseTime(), so every
	 * forged template carries this value. The default is what the original build
	 * used; the module config can lower or raise it.
	 */
	private static int _corpseTimeSeconds = 1200;

	/** Overrides the corpse time stamped on every forged template. */
	public static void setCorpseTime(int seconds)
	{
		_corpseTimeSeconds = seconds;
	}

	/** The corpse time currently stamped on forged templates, in seconds. */
	public static int getCorpseTimeSeconds()
	{
		return _corpseTimeSeconds;
	}

	// Weapon ids stamped onto every forged template's hands. 0 means "leave the
	// wild creature's own hands alone". The right hand is what the client draws
	// as the tame's active weapon; the left hand is a second weapon or shield.
	private static int _rightHandItem;
	private static int _leftHandItem;
	private static int _weaponEnchant;
	private static boolean _handsDebug;
	private static boolean _showWildWeapon = true;

	// What each synthetic template's right hand held in the wild, recorded when
	// it is forged. Guarded by LOCK. A creature whose entry is zero never carried
	// a weapon, so its model has no weapon animations and is left empty-handed.
	private static final Map<Integer, Integer> _bakedRightHand = new HashMap<>();

	/** Turns drawing the wild species' own weapon in the tame's hand on or off. */
	public static void setShowWildWeapon(boolean show)
	{
		_showWildWeapon = show;
	}

	/** Turns the one-line "what is in this tame's hands" log on or off. */
	public static void setHandsDebug(boolean debug)
	{
		_handsDebug = debug;
	}

	/** Sets the weapon the client draws in each of a tame's hands. */
	public static void setHandItems(int rightHandItem, int leftHandItem, int weaponEnchant)
	{
		_rightHandItem = Math.max(0, rightHandItem);
		_leftHandItem = Math.max(0, leftHandItem);
		_weaponEnchant = Math.max(0, Math.min(16, weaponEnchant));
	}

	/**
	 * Puts the weapon a player installed on one specific collar into that tame's
	 * right hand, so whichever weapon was chosen is the one the client draws. The
	 * template is a live object, so this also works on a tame that was already
	 * restored, without forging it again.
	 * <p>
	 * Only creatures that carried a weapon in the wild are given one. A model that
	 * never held a weapon has no weapon animations, and forcing one on it makes it
	 * slide while walking and freeze while attacking, so those tames are left
	 * empty-handed. Their gear still counts for stats.
	 */
	public static void stampWeapon(int syntheticNpcId, int weaponItemId, int enchantLevel)
	{
		if (syntheticNpcId <= 0)
		{
			return;
		}
		final NpcTemplate template;
		final int baked;
		synchronized (LOCK)
		{
			template = (_npcs == null) ? null : _npcs.get(syntheticNpcId);
			baked = _bakedRightHand.getOrDefault(syntheticNpcId, 0);
		}
		if ((template == null) || (baked <= 0))
		{
			return;
		}
		try
		{
			writeWeapon(template, weaponItemId, enchantLevel);
		}
		catch (ReflectiveOperationException e)
		{
			LOGGER.log(Level.WARNING, "could not stamp weapon " + weaponItemId + " on synthetic " + syntheticNpcId, e);
		}
	}

	/** Gives a tame back the weapon its wild species carried, which is what removing collar gear does. */
	public static void restoreBakedWeapon(int syntheticNpcId)
	{
		final int baked;
		synchronized (LOCK)
		{
			baked = _bakedRightHand.getOrDefault(syntheticNpcId, 0);
		}
		stampWeapon(syntheticNpcId, baked, 0);
	}

	/** True when this tame's species carried a weapon in the wild, so a collar weapon can be drawn on it. */
	public static boolean canShowWeapon(int syntheticNpcId)
	{
		synchronized (LOCK)
		{
			return _bakedRightHand.getOrDefault(syntheticNpcId, 0) > 0;
		}
	}

	/**
	 * Writes the held weapon and its enchant glow into a forged template. An id of
	 * zero clears the hand again.
	 * <p>
	 * This only changes what the template says. A summon's spawn packet takes its
	 * weapon from {@code Pet.getWeapon()}, which reads the pet's own inventory, so
	 * the template alone does not draw anything on a pet (HandsDebug shows
	 * {@code template=X, getWeapon()=0}). {@link #equipVisibleGear} is what makes
	 * the weapon appear.
	 */
	private static void writeWeapon(NpcTemplate template, int weaponItemId, int enchantLevel) throws ReflectiveOperationException
	{
		final Field rhand = NpcTemplate.class.getDeclaredField("_rhandId");
		rhand.setAccessible(true);
		rhand.setInt(template, Math.max(0, weaponItemId));
		final Field enchant = NpcTemplate.class.getDeclaredField("_weaponEnchant");
		enchant.setAccessible(true);
		enchant.setInt(template, Math.max(0, Math.min(16, enchantLevel)));
	}

	/**
	 * Reads the weapon out of a tame's saved gear and puts it in its hand. Called
	 * while the collar is being restored so a reloaded tame walks out already
	 * holding what the player gave it.
	 */
	public static void applySavedGear(String tameUuid, int syntheticNpcId)
	{
		if ((tameUuid == null) || (syntheticNpcId <= 0))
		{
			return;
		}
		final TameProfileRepository.EquipmentRecord weapon = TameProfileRepository.loadCustomEquipment(tameUuid).get(TameEquipmentCatalog.SLOT_WEAPON);
		if (weapon == null)
		{
			return;
		}
		stampWeapon(syntheticNpcId, weapon.getItemId(), weapon.getEnchantLevel());
	}

	/**
	 * Draws the weapon the tame's wild species carried in its hand.
	 * <p>
	 * The spawn packet reads a pet's weapon from {@code Pet.getWeapon()}, which asks
	 * the pet's own inventory, so the weapon has to sit in the pet's weapon
	 * paperdoll slot. The item lent is always the species' OWN weapon and never the
	 * collar's: the creature's model has animations for the weapon type it was
	 * made for, and lending a different type is what made earlier attempts slide
	 * while walking and freeze while attacking. A species that never carried a
	 * weapon gets nothing. Call this before the pet is spawned so the first packet
	 * the client sees already has the weapon in it.
	 * <p>
	 * The copy is lent, not given: {@code Pet.unSummon} throws the pet's inventory
	 * away, and it is rebuilt from the template on every summon.
	 */
	public static void equipVisibleGear(Pet pet, int syntheticNpcId)
	{
		if (!_showWildWeapon || (pet == null))
		{
			return;
		}
		final int baked;
		synchronized (LOCK)
		{
			baked = _bakedRightHand.getOrDefault(syntheticNpcId, 0);
		}
		if (baked <= 0)
		{
			return;
		}
		final PetInventory inventory = pet.getInventory();
		if (inventory == null)
		{
			return;
		}
		// A pet that died holding a lent weapon keeps it, so clear first.
		clearLentGear(pet, inventory);
		try
		{
			final Item lent = ItemManager.createItem(ItemProcessType.REFUND, baked, 1, pet, null);
			if (lent == null)
			{
				LOGGER.warning("TameForge: item " + baked + " does not exist, so it cannot be drawn in the hand of pet " + pet.getObjectId());
				return;
			}
			lent.setOwnerId(pet.getObjectId());
			// addItem registers it in the container, setPaperdollItem then moves it
			// into the slot the spawn packet reads.
			containerAddItem().invoke(inventory, lent);
			inventory.setPaperdollItem(PET_WEAPON_SLOT, lent);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameForge: could not put weapon " + baked + " in the hand of pet " + pet.getObjectId(), e);
		}
	}

	/** Drops any weapon or armour the pet is holding, so a lent weapon never stacks up or outlives its pet. */
	private static void clearLentGear(Pet pet, PetInventory inventory)
	{
		for (Item item : inventory.getItems())
		{
			if ((item == null) || (item.getTemplate() == null) || (!item.getTemplate().isWeapon() && !item.getTemplate().isArmor()))
			{
				continue;
			}
			try
			{
				pet.destroyItem(ItemProcessType.DESTROY, item.getObjectId(), item.getCount(), pet, false);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, "TameForge: could not clear lent gear from pet " + pet.getObjectId(), e);
			}
		}
	}

	/**
	 * The paperdoll index the spawn packet's weapon lookup reads. {@code Pet.getWeapon()}
	 * asks its inventory for {@code getPaperdollItem(7)}. It is a legacy index and
	 * does not agree with {@code BodyPart.R_HAND.ordinal()}, which is 10.
	 */
	private static final int PET_WEAPON_SLOT = 7;

	private static Method _containerAddItem;

	/** Inventory.addItem is protected, so it has to be reached reflectively. */
	private static Method containerAddItem() throws ReflectiveOperationException
	{
		if (_containerAddItem == null)
		{
			final Method add = Class.forName("org.l2jmobius.gameserver.model.itemcontainer.Inventory").getDeclaredMethod("addItem", Item.class);
			add.setAccessible(true);
			_containerAddItem = add;
		}
		return _containerAddItem;
	}

	/**
	 * Logs what the spawn is being fed for this tame's hands: the weapon its wild
	 * species carried, what the forged template holds now, and what the pet itself
	 * reports. Turn on HandsDebug in module.ini when a weapon is not drawing.
	 */
	public static void logHands(Pet pet, int syntheticNpcId)
	{
		if (!_handsDebug || (pet == null))
		{
			return;
		}
		try
		{
			final NpcTemplate template;
			final int baked;
			synchronized (LOCK)
			{
				template = (_npcs == null) ? null : _npcs.get(syntheticNpcId);
				baked = _bakedRightHand.getOrDefault(syntheticNpcId, 0);
			}
			final int held = (template == null) ? -1 : rightHandOf(template);
			LOGGER.info("hands of pet " + pet.getObjectId() + " (synthetic " + syntheticNpcId + "): wild=" + baked + " template=" + held + " pet.getWeapon()=" + pet.getWeapon());
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "TameForge: could not read hands of synthetic " + syntheticNpcId, e);
		}
	}

	private static int rightHandOf(NpcTemplate template) throws ReflectiveOperationException
	{
		final Field rhand = NpcTemplate.class.getDeclaredField("_rhandId");
		rhand.setAccessible(true);
		return rhand.getInt(template);
	}

	private TameForge()
	{
	}

	/**
	 * Binds the two private maps and works out where fresh synthetic ids start.
	 * Throws if the build does not expose them, so the module refuses to enable
	 * rather than half working.
	 */
	public static void init(int low, int high) throws ReflectiveOperationException
	{
		_low = low;
		_high = high;
		_npcs = npcMap();
		_pets = petMap();
		_next.set(highestUsed() + 1);
		if (_next.get() < low)
		{
			_next.set(low);
		}
		LOGGER.info("ready: synthetic npc ids " + _low + "-" + _high + ", next free id " + _next.get() + ", " + _npcs.size() + " stock templates and " + _pets.size() + " stock profiles visible");
	}

	private static int highestUsed()
	{
		int highest = _low - 1;
		try (Connection connection = DatabaseFactory.getConnection();
			PreparedStatement statement = connection.prepareStatement("SELECT MAX(synthetic_npc_id) FROM tamed_pet"))
		{
			try (ResultSet result = statement.executeQuery())
			{
				// MAX over an empty or all-zero set comes back as SQL NULL, which
				// getInt would report as 0. Only a real row counts as a used id.
				if (result.next() && (result.getObject(1) != null))
				{
					highest = result.getInt(1);
				}
				else
				{
					LOGGER.info("no saved synthetic ids yet, starting at " + (_low + 1));
					return _low;
				}
			}
		}
		catch (Exception e)
		{
			// A fresh install may not have the table yet. Starting from the bottom
			// of the reserved band is safe because the band belongs to this module.
			LOGGER.info("synthetic id lookup unavailable, starting at " + (_low + 1));
			return _low;
		}
		return highest;
	}

	/** Reserves the next free synthetic id. Ids are never reused within a band. */
	public static int allocate()
	{
		synchronized (LOCK)
		{
			final int id = _next.getAndIncrement();
			if ((id < _low) || (id > _high))
			{
				throw new IllegalStateException("synthetic npc id band " + _low + "-" + _high + " is exhausted; widen the reserve in module.json");
			}
			return id;
		}
	}

	/**
	 * Clones the wild creature's template under a synthetic id and registers the
	 * clone so the stock NpcData returns it. Returns null when the species has no
	 * template at all, which is the only unrecoverable case.
	 */
	public static NpcTemplate forge(int sourceNpcId, int syntheticNpcId) throws ReflectiveOperationException
	{
		synchronized (LOCK)
		{
			final NpcTemplate original = NpcData.getInstance().getTemplate(sourceNpcId);
			if (original == null)
			{
				LOGGER.warning("no NpcTemplate for npcId " + sourceNpcId + ", cannot forge " + syntheticNpcId);
				return null;
			}
			// Read before the clone is built: setHandItems may overwrite the clone's
			// hands, and the wild species' own weapon is what decides whether this
			// tame can be shown holding one.
			_bakedRightHand.put(syntheticNpcId, rightHandOf(original));
			final NpcTemplate clone = cloneWithId(original, syntheticNpcId);
			_npcs.put(syntheticNpcId, clone);
			return clone;
		}
	}

	/**
	 * Builds a copy of the given template carrying a synthetic id. The clone is
	 * constructed from the original's own stat set, so every final field is
	 * already identical, and the remaining writable fields are copied over to
	 * catch anything the parser set after construction.
	 *
	 * <p>The display id, name, model, level, race, collision and AI are all left
	 * exactly as the wild creature had them. That is what makes the untouched
	 * client draw the correct creature for a private, synthetic id.
	 */
	private static NpcTemplate cloneWithId(NpcTemplate original, int syntheticNpcId) throws ReflectiveOperationException
	{
		// The stock constructor reads its attributes from a StatSet, and that set
		// has to carry the id, so the original's backing map is copied and the
		// synthetic id written in before construction.
		final StatSet source = (StatSet) readField(original, "_parameters");
		@SuppressWarnings("unchecked")
		final Map<String, Object> attributes = new HashMap<>((Map<String, Object>) readField(source, "_set"));
		attributes.put("id", syntheticNpcId);
		capTemplateStats(attributes);

		final NpcTemplate clone = new NpcTemplate(new StatSet(attributes));
		for (Class<?> type = original.getClass(); (type != null) && (type != Object.class); type = type.getSuperclass())
		{
			for (Field field : type.getDeclaredFields())
			{
				final int modifiers = field.getModifiers();
				if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers))
				{
					continue; // already correct from the constructor
				}
				// _parameters is skipped on purpose and set below. Copying it would
				// hand the clone the *stock* template's StatSet instance, which is
				// unmodifiable and shared with every wild creature of this species -
				// so it would both block the skill bar and risk corrupting the NPC.
				if ("_parameters".equals(field.getName()))
				{
					continue;
				}

				// So are the four fields the stock constructor derives from the attributes
				// capTemplateStats just rewrote. They are private and non-final on
				// CreatureTemplate, so the copy below ran straight over the capped values
				// and put the species' own crit rate, attack speeds and DEX back: the
				// constructor was doing the capping and then this loop undid it. The
				// movement speeds are not here because they live in the final _moveType,
				// which this loop already leaves alone.
				if (CAPPED_FIELDS.contains(field.getName()))
				{
					continue; // already capped by the constructor
				}

				field.setAccessible(true);
				field.set(clone, field.get(original));
			}
		}

		// NpcTemplate's constructor never assigns _parameters (only setParameters
		// does), so without this the clone would have none at all. Built from the
		// same attributes the constructor read, plus the synthetic id, and mutable
		// so TameSkillBar can bind the pet skill bar onto it.
		clone.setParameters(new StatSet(attributes));

		final Field id = NpcTemplate.class.getDeclaredField("_id");
		id.setAccessible(true);
		id.setInt(clone, syntheticNpcId);
		setCorpseTime(clone, _corpseTimeSeconds);
		setHandItems(clone);
		return clone;
	}

	/**
	 * Absolute ceilings on the stats a tame inherits straight from the wild creature's
	 * own template, in the units the template stores them. Set from {@code PetCritRateCap},
	 * {@code PetMoveSpeedCap}, {@code PetPAtkSpeedCap} and {@code PetCastSpeedCap} in
	 * module.ini. Accuracy and evasion are configured as themselves ({@code PetAccuracyCap},
	 * {@code PetEvasionCap}) and reach the template as a DEX ceiling derived from those two.
	 *
	 * <p>This is a second family of stats from the ones in {@code PetProfileGenerator}, and it
	 * exists because of where each one is read from. A tame's HP and its four attack and
	 * defence values come out of its own generated level table, so those can be capped while
	 * the table is being built. Crit rate, both attack speeds and movement speed do not come
	 * from the level table at all - they come from the NPC template the tame is forged onto,
	 * and that template is a copy of whatever wild creature was eaten.
	 *
	 * <p>So a tame of a boss species starts from the boss's own crit rate and the boss's own
	 * speeds, and none of this module's growth ever touches them. That is why these need
	 * bounding on their own rather than being covered by the growth ceiling.
	 *
	 * <p>0 disables any single cap. Out-of-range values are refused and logged rather than
	 * clamped, matching the level-table caps.
	 */
	private static volatile int critRateCap = 450;
	private static volatile int moveSpeedCap = 200;
	private static volatile int pAtkSpeedCap = 950;
	private static volatile int castSpeedCap = 300;

	/**
	 * The four non-final fields {@link #capTemplateStats(Map)} reaches through the constructor,
	 * by name as {@code CreatureTemplate} declares them.
	 *
	 * <p>Kept beside the ceilings rather than next to the clone loop that has to honour them,
	 * because the list is only meaningful together with the attributes those ceilings are
	 * written to: a fifth capped attribute with a non-final field of its own would have to be
	 * added in both places, and the failure mode of forgetting is silent - the tame simply
	 * keeps the species' own numbers and every cap still reads as configured.
	 *
	 * <p>The movement speeds are deliberately absent: they are held in {@code _moveType}, which
	 * is final, so the copy loop skips them anyway.
	 */
	private static final java.util.Set<String> CAPPED_FIELDS = java.util.Collections.unmodifiableSet(new java.util.HashSet<>(java.util.Arrays.asList("_baseCritRate", "_basePAtkSpd", "_baseMAtkSpd", "_baseDEX")));

/**
	 * Ceilings on a tame's accuracy and evasion, in points.
	 *
	 * <p>Neither has a template key of its own. {@code CreatureStat.getAccuracy()} asks the
	 * {@code ACCURACY_COMBAT} calculator, whose only contribution is {@code FuncAtkAccuracy}:
	 * {@code level + 6 * sqrt(DEX)}, then {@code + (level - 76)} above level 77 and
	 * {@code + (level - 69)} above level 69. {@code CreatureStat.getEvasionRate} asks
	 * {@code EVASION_RATE}, whose non-player branch in {@code FuncAtkEvasion} is the same
	 * {@code level + 6 * sqrt(DEX)} plus {@code (level - 67)} above level 69.
	 *
	 * <p>So these two are configured the way a player would expect - as accuracy and evasion -
	 * and the DEX ceiling that actually gets written to the template is derived from them. See
	 * {@link #deriveDexCap()}.
	 */
	private static volatile int accuracyCap = 180;
	private static volatile int evasionCap = 130;

	/**
	 * The crit rate ceiling in force, in the template's own units.
	 */
	public static int getCritRateCap()
	{
		return critRateCap;
	}

	/**
	 * The accuracy ceiling in force, in points.
	 */
	public static int getAccuracyCap()
	{
		return accuracyCap;
	}

	/**
	 * The evasion ceiling in force, in points.
	 */
	public static int getEvasionCap()
	{
		return evasionCap;
	}

	/**
	 * The DEX ceiling currently derived from the accuracy and evasion ceilings, or 0 when
	 * neither is in force.
	 */
	public static int getDexCap()
	{
		return deriveDexCap();
	}

	/**
	 * The move speed ceiling in force, in the template's own units.
	 */
	public static int getMoveSpeedCap()
	{
		return moveSpeedCap;
	}

	/**
	 * The attack speed ceiling in force, in the template's own units.
	 */
	public static int getPAtkSpeedCap()
	{
		return pAtkSpeedCap;
	}

	/**
	 * The cast speed ceiling in force, in the template's own units.
	 */
	public static int getCastSpeedCap()
	{
		return castSpeedCap;
	}

	public static void setCritRateCap(int value)
	{
		critRateCap = templateCap("PetCritRateCap", value, critRateCap);
	}

	public static void setMoveSpeedCap(int value)
	{
		moveSpeedCap = templateCap("PetMoveSpeedCap", value, moveSpeedCap);
	}

	public static void setPAtkSpeedCap(int value)
	{
		pAtkSpeedCap = templateCap("PetPAtkSpeedCap", value, pAtkSpeedCap);
	}

	public static void setCastSpeedCap(int value)
	{
		castSpeedCap = templateCap("PetCastSpeedCap", value, castSpeedCap);
	}

	public static void setAccuracyCap(int value)
	{
		accuracyCap = templateCap("PetAccuracyCap", value, accuracyCap);
	}

	public static void setEvasionCap(int value)
	{
		evasionCap = templateCap("PetEvasionCap", value, evasionCap);
	}

	/**
	 * Works out the largest DEX that keeps both accuracy and evasion inside their ceilings at
	 * this server's actual pet level cap.
	 *
	 * <p>Derived rather than configured on purpose. Both formulas are
	 * {@code level + 6*sqrt(DEX)} plus level-dependent extras, so the DEX that produces a given
	 * accuracy depends entirely on the level the tame can reach. A fixed DEX number would be a
	 * silent balance bug the moment the level cap moved, and there is no way to notice it from
	 * the number itself - it would just quietly stop working.
	 *
	 * <p>So the ceilings are the thing an owner sets, and the DEX that satisfies them at the
	 * worst level a tame can actually be is computed from those ceilings and the real level cap.
	 *
	 * <p>Evasion is nearly always the binding constraint, because its level extras are larger
	 * than accuracy's for the same level. Both are still solved here rather than assuming it,
	 * because if a server owner sets a generous accuracy and a tight evasion the answer should
	 * follow the tighter one and that is not guaranteed to hold for every pair of values.
	 *
	 * @return the DEX ceiling, or 0 when neither ceiling is in force
	 */
	private static int deriveDexCap()
	{
		if ((accuracyCap <= 0) && (evasionCap <= 0))
		{
			return 0;
		}
		final int levelCap = levelCap();
		// Both formulas are level + 6*sqrt(DEX) + extras, so for a ceiling C the largest DEX
		// that fits is ((C - level - extras) / 6)^2. Taking the smaller of the two answers is
		// what satisfies both ceilings at once.
		double allowance = Double.MAX_VALUE;
		if (accuracyCap > 0)
		{
			allowance = Math.min(allowance, accuracyHeadroom(levelCap));
		}
		if (evasionCap > 0)
		{
			allowance = Math.min(allowance, evasionHeadroom(levelCap));
		}
		if (allowance <= 0.0)
		{
			// The level cap alone already exceeds the ceiling, so no DEX value can help:
			// even DEX 0 would be over. Refuse rather than write a bogus number, and let the
			// caller leave the attribute alone - that is the honest outcome for a config whose
			// ceiling is unreachable at this server's level cap.
			return 0;
		}
		final int dex = (int) Math.floor((allowance / 6.0) * (allowance / 6.0));
		return Math.max(0, dex);
	}

	/**
	 * How much of the accuracy ceiling is left over at a given level once the level-dependent
	 * part of the formula is accounted for.
	 */
	private static double accuracyHeadroom(int level)
	{
		double extras = 0.0;
		if (level > 77) { extras += (level - 76); }
		if (level > 69) { extras += (level - 69); }
		return accuracyCap - level - extras;
	}

	/**
	 * How much of the evasion ceiling is left over at a given level, for the non-player branch
	 * of {@code FuncAtkEvasion} that every tame takes.
	 */
	private static double evasionHeadroom(int level)
	{
		final double extras = (level > 69) ? (level - 67) : 0.0;
		return evasionCap - level - extras;
	}

	/**
	 * The highest level a tame can actually reach on this server.
	 *
	 * <p>Deliberately the pet level cap rather than the player one: a tame is capped by what
	 * the core allows a pet, and using the player cap would leave headroom that the tame can
	 * never actually reach.
	 *
	 * <p>The one subtracted off the end matters. {@code ExperienceData} reads the
	 * {@code maxPetLevel} attribute out of experience.xml and stores it one higher than the
	 * level it names - it parses the value and adds 1 before assigning, using it as an
	 * exclusive table bound. So an attribute of {@code maxPetLevel="80"}, which plainly
	 * means a tame tops out at level 80, comes back out of {@code getMaxPetLevel()} as 81.
	 * Taking that at face value derives the ceiling against a level no tame can reach,
	 * which quietly costs a few DEX and leaves accuracy and evasion a little under their
	 * configured ceilings for no reason.
	 */
	private static int levelCap()
	{
		try
		{
			final int cap = ExperienceData.getInstance().getMaxPetLevel() - 1;
			// A cap below 1 means the answer is an artifact of load order, not a level cap.
			//
			// ExperienceData clamps its own maxPetLevel attribute to
			// PlayerConfig.PLAYER_MAXIMUM_LEVEL while parsing, so whenever this runs before
			// the player config is loaded that static byte is still 0 and the honest 80 from
			// experience.xml gets clamped down to nothing useful. The singleton does not
			// complain while it happens, it just answers with the clamped value.
			//
			// Taking that at face value is worse than the missing-file case the catch below
			// exists for, because nothing looks wrong: the derivation runs against level 1,
			// every headroom comes out enormous, and the "cannot happen" DEX cap becomes one
			// that very much can.
			if (cap >= 1)
			{
				return cap;
			}
			LOGGER.warning("the pet level cap reads as " + cap + ", so the experience or player config is not loaded yet; assuming 80 for the derived DEX cap");
		}
		catch (Throwable t)
		{
			// Deliberately Throwable, not RuntimeException. If the experience data is missing
			// the singleton's class initializer fails, and that surfaces as
			// ExceptionInInitializerError on the first call and NoClassDefFoundError on every
			// call after it - neither of which is a RuntimeException, so a narrower catch here
			// would let a missing data file escape and take a tame's accuracy cap with it.
		}
		// 80 is this engine's own pet ceiling in practice, so it is a safe answer for the
		// arithmetic: it is the same number the derived cap came out against when these
		// defaults were chosen.
		return 80;
	}

	/**
	 * Range check shared by every template-level ceiling setter.
	 *
	 * <p>0 means uncapped. Anything else must be a value this engine would ever actually
	 * store, and anything outside that is refused with the offending setting's name rather
	 * than quietly clamped, for the same reason the level-table caps behave that way.
	 */
	private static int templateCap(String name, int value, int current)
	{
		if (value == 0)
		{
			return 0;
		}
		if ((value < 1) || (value > 1000000))
		{
			LOGGER.warning(name + " " + value + " is outside 1..1000000 and was ignored; staying at " + current);
			return current;
		}
		return value;
	}

	/**
	 * Applies the template-level ceilings to the attribute map a clone is about to be built
	 * from, before the stock constructor reads it.
	 *
	 * <p>Written into the attributes rather than onto the finished clone because those fields
	 * are what the constructor reads, and for the speeds they are {@code final} afterwards and
	 * cannot be corrected later. Every entry is a ceiling and not a target: an ordinary wolf or
	 * lizard is nowhere near these numbers and is bit-identical to before.
	 *
	 * <p>Walk speed is capped at the same figure as run speed rather than being left alone.
	 * They are separate keys, and a tame that could not run at the cap but could walk faster
	 * than it can run would be nonsense.
	 */
	private static void capTemplateStats(Map<String, Object> attributes)
	{
		capAttribute(attributes, "baseCritRate", critRateCap);
		capAttribute(attributes, "basePAtkSpd", pAtkSpeedCap);
		capAttribute(attributes, "baseMAtkSpd", castSpeedCap);
		// Derived from the accuracy and evasion ceilings and this server's level cap, rather
		// than being a number of its own. See deriveDexCap.
		capAttribute(attributes, "baseDEX", deriveDexCap());
		capAttribute(attributes, "baseRunSpd", moveSpeedCap);
		capAttribute(attributes, "baseWalkSpd", moveSpeedCap);
		// Swim and fly speeds are held to the same figure so that no movement mode can be
		// the one that breaks the ceiling.
		capAttribute(attributes, "baseSwimRunSpd", moveSpeedCap);
		capAttribute(attributes, "baseSwimWalkSpd", moveSpeedCap);
		capAttribute(attributes, "baseFlyRunSpd", moveSpeedCap);
		capAttribute(attributes, "baseFlyWalkSpd", moveSpeedCap);
	}

	/**
	 * Caps one numeric template attribute in place.
	 *
	 * <p>Skipped entirely when the ceiling is 0, and when the species never had the key, since
	 * a missing attribute is the engine's own default and inventing one would change species
	 * that were never out of bounds.
	 */
	private static void capAttribute(Map<String, Object> attributes, String key, int ceiling)
	{
		if ((ceiling <= 0) || !attributes.containsKey(key))
		{
			return;
		}
		final Object current = attributes.get(key);
		final double value;
		if (current instanceof Number)
		{
			value = ((Number) current).doubleValue();
		}
		else
		{
			try
			{
				value = Double.parseDouble(String.valueOf(current).trim());
			}
			catch (NumberFormatException e)
			{
				return; // not a number this engine would have produced; leave it alone
			}
		}
		if (value > ceiling)
		{
			attributes.put(key, Integer.valueOf(ceiling));
		}
	}

	/**
	 * Applies the optional module-wide hand defaults to a forged template. Left
	 * hand is the shield or second weapon, and the enchant value is the glow the
	 * client draws around the weapon. All three are written into the private fields
	 * the stock client packet builder reads, so nothing in the core is touched.
	 * With every default at zero the wild creature's own hands are left alone.
	 *
	 * @see #writeWeapon(NpcTemplate, int, int)
	 */
	private static void setHandItems(NpcTemplate template) throws ReflectiveOperationException
	{
		if ((_rightHandItem <= 0) && (_leftHandItem <= 0) && (_weaponEnchant <= 0))
		{
			return;
		}
		if (_rightHandItem > 0)
		{
			final Field rhand = NpcTemplate.class.getDeclaredField("_rhandId");
			rhand.setAccessible(true);
			rhand.setInt(template, _rightHandItem);
		}
		if (_leftHandItem > 0)
		{
			final Field lhand = NpcTemplate.class.getDeclaredField("_lhandId");
			lhand.setAccessible(true);
			lhand.setInt(template, _leftHandItem);
		}
		if (_weaponEnchant > 0)
		{
			final Field enchant = NpcTemplate.class.getDeclaredField("_weaponEnchant");
			enchant.setAccessible(true);
			enchant.setInt(template, _weaponEnchant);
		}
	}

	/**
	 * Files the corpse time onto a forged template. The wild creature's own
	 * corpse time is a few seconds and its template has already been built, so
	 * the value is written directly into the private field the stock decay task
	 * manager reads. No core code is changed.
	 */
	private static void setCorpseTime(NpcTemplate template, int seconds) throws ReflectiveOperationException
	{
		final Field corpseTime = NpcTemplate.class.getDeclaredField("_corpseTime");
		corpseTime.setAccessible(true);
		corpseTime.setInt(template, seconds);
	}

	/** Files one collar's profile under its own synthetic id. */
	public static void register(int syntheticNpcId, PetData data)
	{
		synchronized (LOCK)
		{
			_pets.put(syntheticNpcId, data);
		}
	}

	/**
	 * Removes a synthetic template and profile. Called when a collar is deleted
	 * so the reserved band does not fill with entries nobody owns.
	 */
	public static void release(int syntheticNpcId)
	{
		synchronized (LOCK)
		{
			_npcs.remove(syntheticNpcId);
			_pets.remove(syntheticNpcId);
			_bakedRightHand.remove(syntheticNpcId);
		}
	}

	public static boolean isForged(int npcId)
	{
		synchronized (LOCK)
		{
			return _npcs.containsKey(npcId);
		}
	}

	/** How many private npc ids the band holds in total. */
	public static int capacity()
	{
		return _high - _low;
	}

	/** How many private npc ids are currently handed out, live or saved. */
	public static int used()
	{
		return _next.get() - (_low + 1);
	}

	@SuppressWarnings("unchecked")
	private static Map<Integer, NpcTemplate> npcMap() throws ReflectiveOperationException
	{
		final Field field = NpcData.class.getDeclaredField("_npcs");
		field.setAccessible(true);
		return (Map<Integer, NpcTemplate>) field.get(NpcData.getInstance());
	}

	@SuppressWarnings("unchecked")
	private static Map<Integer, PetData> petMap() throws ReflectiveOperationException
	{
		final Field field = PetDataTable.class.getDeclaredField("_pets");
		field.setAccessible(true);
		return (Map<Integer, PetData>) field.get(PetDataTable.getInstance());
	}

	private static Object readField(Object target, String name) throws ReflectiveOperationException
	{
		final Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	/**
	 * Rebuilds every saved collar's template and profile so that a pet saved in
	 * the stock summons table can be restored by the stock login code. Runs once
	 * at startup, before any player connects.
	 */
	public static void hydrateAll()
	{
		int restored = 0;
		int failed = 0;
		try (Connection connection = DatabaseFactory.getConnection();
			PreparedStatement statement = connection.prepareStatement("SELECT tame_uuid, source_npc_id, synthetic_npc_id, collar_object_id FROM tamed_pet WHERE active = 1"))
		{
			try (ResultSet result = statement.executeQuery())
			{
				while (result.next())
				{
					final int syntheticNpcId = result.getInt("synthetic_npc_id");
					final int sourceNpcId = result.getInt("source_npc_id");
					final int collarObjectId = result.getInt("collar_object_id");
					try
					{
						final NpcTemplate template = forge(sourceNpcId, syntheticNpcId);
						if (template == null)
						{
							failed++;
							continue;
						}
						final TameProfile profile = TameProfileRepository.loadByCollar(collarObjectId);
						if (profile == null)
						{
							failed++;
							continue;
						}
						final PetData data = PetProfileGenerator.generatePersisted(profile, template);
						if (data == null)
						{
							failed++;
							continue;
						}
register(syntheticNpcId, data);
					// The chosen weapon goes in the hand before anyone logs in,
					// so a restored tame already looks equipped.
					applySavedGear(profile.getUuid(), syntheticNpcId);
					restored++;
					}
					catch (Exception e)
					{
						failed++;
						LOGGER.log(Level.WARNING, "could not restore collar " + collarObjectId + " (synthetic " + syntheticNpcId + ")", e);
					}
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "collar restore failed, tamed pets will not come back after a relog until this is fixed", e);
			return;
		}
		LOGGER.info("restored " + restored + " collar(s) from the database" + (failed > 0 ? ", " + failed + " could not be restored" : ""));
	}
}
