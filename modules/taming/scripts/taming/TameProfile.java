/*
 * TameProfile.java
 *
 * Persistent individual tame profile. Runtime script source; Java 8 compatible.
 */
package taming;

public class TameProfile
{
	private final String _uuid;
	private final int _collarObjectId;
	private final int _ownerId;
	private final int _sourceNpcId;
	private final int _sourceLevel;
	private final String _sourceType;
	private final String _race;
	private final String _family;
	private final String _role;
	private final String _rarity;
	private final int _potential;
	private final int _offensePotential;
	private final int _defensePotential;
	private final int _vitalityPotential;
	private final int _skillPotential;
	private final double _growthPercent;
	private final String _temperament;
	private final String _affinity;
	private final int _bond;
	private final int _awakeningStage;
	private final String _awakeningPath;
	private int _currentLevel;
	private final int _maxPetLevel;
	private final long _profileSeed;
	private final int _profileVersion;
	// Not final: a rename writes the name back to the profile row, and the loaded
	// object has to agree with that row or the next render shows the old name.
	private String _petName;
	private final int _baseHp;
	private final int _baseMp;
	private final int _basePAtk;
	private final int _basePDef;
	private final int _baseMAtk;
	private final int _baseMDef;
	private final double _conversionMultiplier;
	private final int _woundFlags;

	// The private NPC id this profile is filed under at runtime. It is an
	// implementation detail of TameForge, not part of the tame's identity: the
	// collar object id is the identity. It is assigned once at capture and kept
	// so the same pet comes back after a relog.
	private int _syntheticNpcId;
	// Whether this is the collar its owner is wearing. Not final because taking a
	// collar off is a decision the owner makes and both answers have to be renderable
	// from the same loaded profile.
	private boolean _worn;
	
	public TameProfile(String uuid, int collarObjectId, int ownerId, int sourceNpcId, int sourceLevel, String sourceType, String race, String family, String role, String rarity, int potential, int offensePotential, int defensePotential, int vitalityPotential, int skillPotential, double growthPercent, String temperament, String affinity, int bond, int awakeningStage, String awakeningPath, int currentLevel, int maxPetLevel, long profileSeed, int profileVersion, String petName, int baseHp, int baseMp, int basePAtk, int basePDef, int baseMAtk, int baseMDef, double conversionMultiplier, int woundFlags)
	{
		_uuid = uuid;
		_collarObjectId = collarObjectId;
		_ownerId = ownerId;
		_sourceNpcId = sourceNpcId;
		_sourceLevel = sourceLevel;
		_sourceType = sourceType;
		_race = race;
		_family = family;
		_role = role;
		_rarity = rarity;
		_potential = potential;
		_offensePotential = offensePotential;
		_defensePotential = defensePotential;
		_vitalityPotential = vitalityPotential;
		_skillPotential = skillPotential;
		_growthPercent = growthPercent;
		_temperament = temperament;
		_affinity = affinity;
		_bond = bond;
		_awakeningStage = awakeningStage;
		_awakeningPath = awakeningPath;
		_currentLevel = currentLevel;
		_maxPetLevel = maxPetLevel;
		_profileSeed = profileSeed;
		_profileVersion = profileVersion;
		_petName = petName;
		_baseHp = baseHp;
		_baseMp = baseMp;
		_basePAtk = basePAtk;
		_basePDef = basePDef;
		_baseMAtk = baseMAtk;
		_baseMDef = baseMDef;
		_conversionMultiplier = conversionMultiplier;
		_woundFlags = woundFlags;
	}
	
	public String getUuid() { return _uuid; }
	public int getCollarObjectId() { return _collarObjectId; }
	public int getOwnerId() { return _ownerId; }
	public int getSourceNpcId() { return _sourceNpcId; }
	public int getSourceLevel() { return _sourceLevel; }
	public String getSourceType() { return _sourceType; }
	public String getRace() { return _race; }
	public String getFamily() { return _family; }
	public String getRole() { return _role; }
	public String getRarity() { return _rarity; }
	public int getPotential() { return _potential; }
	public int getOffensePotential() { return _offensePotential; }
	public int getDefensePotential() { return _defensePotential; }
	public int getVitalityPotential() { return _vitalityPotential; }
	public int getSkillPotential() { return _skillPotential; }
	public double getGrowthPercent() { return _growthPercent; }
	public String getTemperament() { return _temperament; }
	public String getAffinity() { return _affinity; }
	public int getBond() { return _bond; }
	public int getAwakeningStage() { return _awakeningStage; }
	public String getAwakeningPath() { return _awakeningPath; }
	public int getCurrentLevel() { return _currentLevel; }
	public int getMaxPetLevel() { return _maxPetLevel; }
	public long getProfileSeed() { return _profileSeed; }
	public int getProfileVersion() { return _profileVersion; }
	public String getPetName() { return _petName; }

	/** Keeps the in-memory profile in step with a rename that was just saved. */
	public void setPetName(String petName) { _petName = petName; }

	/**
	 * Lowers the recorded level to the beast's cap.
	 *
	 * <p>The only reason this exists is a cap that was never enforced: beasts
	 * captured before it was are recorded above their own ceiling, and the profile
	 * is what the collar, the profile window and the second awakening gate all read.
	 * Correcting only the live creature would leave every one of them still showing
	 * the level that should never have been reached.
	 */
	public void setCurrentLevel(int currentLevel) { _currentLevel = currentLevel; }
	public int getBaseHp() { return _baseHp; }
	public int getBaseMp() { return _baseMp; }
	public int getBasePAtk() { return _basePAtk; }
	public int getBasePDef() { return _basePDef; }
	public int getBaseMAtk() { return _baseMAtk; }
	public int getBaseMDef() { return _baseMDef; }
	public double getConversionMultiplier() { return _conversionMultiplier; }
	public int getWoundFlags() { return _woundFlags; }
public int getSyntheticNpcId() { return _syntheticNpcId; }
public void setSyntheticNpcId(int syntheticNpcId) { _syntheticNpcId = syntheticNpcId; }

	/**
	 * Whether this is the collar its owner is wearing.
	 *
	 * <p>The core has no worn state for a pet collar, so this is entirely this
	 * module's: it decides which beast a recall answers and whether login brings
	 * the beast back on its own. There is no visible difference and no client
	 * involvement at all - the collar is in the inventory either way, and no packet
	 * carries a worn flag.
	 */
	public boolean isWorn() { return _worn; }
	public void setWorn(boolean worn) { _worn = worn; }
}
