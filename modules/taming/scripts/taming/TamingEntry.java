/*
 * TamingEntry.java
 *
 * WHERE THIS FILE GOES (into your LIVE, running server folder):
 *   L2J-Offline-OneClick/game/data/scripts/handlers/taming/TamingEntry.java
 *
 * Moved from java/org/l2jmobius/gameserver/taming/ into the scripts
 * folder. Scripts are compiled fresh by the server on every startup -
 * no ant/JDK rebuild needed for anything in here anymore.
 */
package taming;

public class TamingEntry
{
	private final int _npcId;
	private final String _name;
	private final String _tier;
	private final double _baseChance;
	private final int _requiredItem;
	private final int _maxPetLevel;

	public TamingEntry(int npcId, String name, String tier, double baseChance, int requiredItem, int maxPetLevel)
	{
		_npcId = npcId;
		_name = name;
		_tier = tier;
		_baseChance = baseChance;
		_requiredItem = requiredItem;
		_maxPetLevel = maxPetLevel;
	}

	public int getNpcId()
	{
		return _npcId;
	}

	public String getName()
	{
		return _name;
	}

	public String getTier()
	{
		return _tier;
	}

	/**
	 * Whether this creature is on the raid tier.
	 *
	 * <p>The two tiers no longer roll the same way - they take different caps and, for an
	 * ordinary creature, no modifiers at all - so the string comparison this used to leave
	 * to the call site is worth having in one named place rather than repeated.
	 *
	 * @return true for RAID, false for anything else
	 */
	public boolean isRaid()
	{
		return "RAID".equalsIgnoreCase(_tier);
	}

	public double getBaseChance()
	{
		return _baseChance;
	}

	public int getRequiredItem()
	{
		return _requiredItem;
	}

	public int getMaxPetLevel()
	{
		return _maxPetLevel;
	}
}
