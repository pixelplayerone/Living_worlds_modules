/*
 * TameFood.java
 *
 * One food tier per tame, plus the meal curve that makes hunger reachable.
 *
 * Tames eat the real stock PetFood items rather than a module-only stand-in,
 * so the client's own food handling, icons and shop prices all keep working.
 * A boss tame eats hatchling food, everything else eats wolf food.
 */
package taming;

public final class TameFood
{
	/** Food For Wolves. Stock skill 2048 restores a flat 100. */
	public static final int WOLF_ITEM_ID = 2515;
	public static final int WOLF_RESTORE = 100;
	private static final String WOLF_NAME = "Food For Wolves";

	/** Food For Hatchling. Stock skill 2063 restores a flat 150. */
	public static final int HATCHLING_ITEM_ID = 4038;
	public static final int HATCHLING_RESTORE = 150;
	private static final String HATCHLING_NAME = "Food For Hatchling";

	/**
	 * Every stock pet file in this game ships hungry_limit 55. Without it a
	 * generated pet has no threshold at all and the hunger gauge is decorative.
	 */
	public static final int HUNGRY_LIMIT = 55;

	// Meal capacities track the two stock files this tier borrows its feel from:
	// 12077_Wolf runs 248 -> 11745 over 87 levels, 12311_Hatchling_of_the_Wind
	// runs 492 -> 7747 over the same span.
	private static final int WOLF_BASE_MEAL = 250;
	private static final int WOLF_MEAL_STEP = 134;
	private static final int HATCHLING_BASE_MEAL = 500;
	private static final int HATCHLING_MEAL_STEP = 85;

	private static final TameFood WOLF = new TameFood(WOLF_ITEM_ID, WOLF_RESTORE, WOLF_NAME, WOLF_BASE_MEAL, WOLF_MEAL_STEP);
	private static final TameFood HATCHLING = new TameFood(HATCHLING_ITEM_ID, HATCHLING_RESTORE, HATCHLING_NAME, HATCHLING_BASE_MEAL, HATCHLING_MEAL_STEP);

	private final int _itemId;
	private final int _restore;
	private final String _name;
	private final int _baseMeal;
	private final int _mealStep;

	private TameFood(int itemId, int restore, String name, int baseMeal, int mealStep)
	{
		_itemId = itemId;
		_restore = restore;
		_name = name;
		_baseMeal = baseMeal;
		_mealStep = mealStep;
	}

	/**
	 * True when the profile was rolled off a raid or grand boss. This is the
	 * same test TameProfileFactory.classifyFamily uses to file a tame under the
	 * BOSS family, kept in one place so the food tier cannot drift away from
	 * the rarity tier.
	 */
	public static boolean isBoss(TameProfile profile)
	{
		return (profile != null) && ("BOSS".equals(profile.getFamily())
				|| "RaidBoss".equalsIgnoreCase(profile.getSourceType())
				|| "GrandBoss".equalsIgnoreCase(profile.getSourceType()));
	}

	public static TameFood wolf()
	{
		return WOLF;
	}

	public static TameFood hatchling()
	{
		return HATCHLING;
	}

	public static TameFood forProfile(TameProfile profile)
	{
		return isBoss(profile) ? HATCHLING : WOLF;
	}

	public int getItemId()
	{
		return _itemId;
	}

	/** How much one item puts back on the hunger gauge. */
	public int getRestore()
	{
		return _restore;
	}

	public String getName()
	{
		return _name;
	}

	/** Meal capacity at one pet level, so a tame carries more as it grows. */
	public int maxMeal(int level)
	{
		return _baseMeal + (_mealStep * (Math.max(1, level) - 1));
	}

	/**
	 * Drain per feed tick, taken from the stock files rather than invented: a
	 * level 87 wolf holds 11745 and burns 46 in battle and 8 at rest.
	 */
	public int consumeInBattle(int level)
	{
		return Math.max(2, maxMeal(level) / 250);
	}

	public int consumeWhileNormal(int level)
	{
		return Math.max(2, maxMeal(level) / 1400);
	}
}
