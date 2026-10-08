package taming;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.data.holders.PetData;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.data.xml.PetDataTable;

/**
 * Hands this module's own pet stat data to the core's pet table.
 *
 * <p>Without this, every collar beast breaks the server's own packets. The chain
 * has no guard in it anywhere: {@code PetInfo.writeImpl} and
 * {@code PetStatusUpdate.writeImpl} both ask the beast for its experience bar,
 * that reaches {@code PetStat.getExpForLevel}, which asks
 * {@code PetDataTable.getPetLevelData(Summon.getId(), level)}, and the lookup
 * answers null for an id it has never seen. The caller dereferences it
 * immediately, so the packet write throws - no pet window, no experience bar, no
 * regeneration ticks.
 *
 * <p>The id in that lookup is {@code Summon.getId()}, which returns the template's
 * npc id rather than the object id, so one registration covers a beast for good.
 *
 * <p><b>What is registered is this module's own data, not a copy of anything.</b>
 * {@code PetProfileGenerator} already builds a complete per-level curve for a
 * beast - health, attack and defence derived from the captured creature's
 * template and diluted by its rarity, plus the experience thresholds from the
 * owner's curve. {@code TameForge} keeps that data in its own map for the
 * duration of the summon and it never reaches {@code PetDataTable}, which is
 * precisely the gap this closes. Copying a stock curve here instead would be a
 * category error: {@code PetStat} reads {@code getPetMaxHP()} and the rest out of
 * this very object, so a borrowed curve silently turns every beast into a copy of
 * the species it was borrowed from.
 *
 * <p>Data normally arrives from {@code data/stats/pets}, and that door is shut:
 * {@code PetDataTable.load()} calls {@code parseDatapackDirectory("data/stats/pets")},
 * which resolves to {@code new File(".", "data/stats/pets")} - a literal path with
 * no module resource registry and no merge - and {@code ModuleResourceType} has no
 * PETS entry. That is what leaves this as the only door.
 *
 * <p>{@code PetData._maxLevel} has no public setter; the XML parser is what fills
 * it in normally. It is reached reflectively, and it is not optional: it is what
 * {@code getPetLevelData} clamps against, so leaving it at zero would clamp every
 * level to a row that does not exist - the same null one step removed, and one that
 * would only ever surface on high-level beasts where it looks like a levelling bug
 * rather than a data one.
 */
final class TamePetDataRegistry
{
	private static final Logger LOGGER = Logger.getLogger(TamePetDataRegistry.class.getName());

	private static Map<Integer, PetData> _pets;
	private static Field _maxLevel;

	/** Ids already registered, so a resummon does no work. */
	private static final Map<Integer, Boolean> REGISTERED = new java.util.concurrent.ConcurrentHashMap<>();

	/** Whether the reflective setup succeeded. False makes every call a no-op. */
	private static boolean _usable;

	private static boolean _initialised;

	private TamePetDataRegistry()
	{
	}

	/**
	 * Publishes a beast's generated stat data under its synthetic id.
	 *
	 * @param syntheticNpcId the forged template id the beast is summoned under
	 * @param data the per-level curve {@code PetProfileGenerator} built for it
	 */
	public static synchronized void register(int syntheticNpcId, PetData data)
	{
		if ((syntheticNpcId <= 0) || (data == null) || REGISTERED.containsKey(Integer.valueOf(syntheticNpcId)))
		{
			return;
		}
		if (!init())
		{
			return;
		}
		try
		{
			final int maxLevel = highestLevel(data);
			if (maxLevel < 1)
			{
				LOGGER.warning("beast " + syntheticNpcId + " was generated with no level data and cannot be described to the client");
				return;
			}
			_maxLevel.setInt(data, maxLevel);
			_pets.put(Integer.valueOf(syntheticNpcId), data);
			REGISTERED.put(Integer.valueOf(syntheticNpcId), Boolean.TRUE);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "could not publish pet stat data for beast " + syntheticNpcId, e);
		}
	}

	/**
	 * The highest level the generator actually produced.
	 *
	 * <p>Derived from the data rather than passed in, so it cannot drift from what
	 * the beast really has. The generator clamps its range to the owner's level cap,
	 * which is the point: a beast is never given levels it is not meant to reach,
	 * and the table's clamp then has nothing to do beyond catching a stale client.
	 */
	private static int highestLevel(PetData data)
	{
		int ceiling = 80;
		if (ExperienceData.getInstance() != null)
		{
			ceiling = ExperienceData.getInstance().getMaxLevel();
		}
		int highest = 0;
		for (int level = 1; level <= ceiling; level++)
		{
			if (data.getPetLevelData(level) == null)
			{
				break;
			}
			highest = level;
		}
		return highest;
	}

	/**
	 * Resolves the private map and the private max-level field once.
	 *
	 * <p>Never throws. A summon path that cannot extend the table still has a beast
	 * to spawn, and saying so once at startup is worth more than an exception on
	 * every summon.
	 */
	@SuppressWarnings("unchecked")
	private static boolean init()
	{
		if (_initialised)
		{
			return _usable;
		}
		_initialised = true;
		try
		{
			final Field pets = PetDataTable.class.getDeclaredField("_pets");
			pets.setAccessible(true);
			_pets = (Map<Integer, PetData>) pets.get(PetDataTable.getInstance());

			_maxLevel = PetData.class.getDeclaredField("_maxLevel");
			_maxLevel.setAccessible(true);

			_usable = true;
		}
		catch (NoSuchFieldException e)
		{
			LOGGER.log(Level.WARNING, "pet stat data cannot be published: " + e.getMessage() + ". Beasts will fail to describe themselves to the client.", e);
			_usable = false;
		}
		catch (RuntimeException | IllegalAccessException e)
		{
			LOGGER.log(Level.WARNING, "pet stat data cannot be reached on this core", e);
			_usable = false;
		}
		return _usable;
	}

	/**
	 * Whether the table could be reached at all. Reported by the diagnostic command
	 * so a silent failure here shows up before a player meets it.
	 */
	public static boolean isUsable()
	{
		return init();
	}

	/**
	 * How many beasts have been published this session. A rising number with nothing
	 * on screen means something is summoning through a path that skipped this.
	 */
	public static int registeredCount()
	{
		return REGISTERED.size();
	}
}
