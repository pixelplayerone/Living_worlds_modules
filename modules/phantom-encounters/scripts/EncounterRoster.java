package modules.phantomencounters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * Who comes: the class of a lone Pker, and the members of a party. Plain numbers in and out (class ids and role names),
 * so it tests on its own. A random source is passed in as a function from a bound to a number in [0, bound).
 */
final class EncounterRoster
{
	/** One phantom to send: the class (third-class id) and the role that picks its gear. */
	static final class Pick
	{
		final int classId;
		final String role;

		Pick(int classId, String role)
		{
			this.classId = classId;
			this.role = role;
		}

		@Override
		public String toString()
		{
			return classId + "/" + role;
		}
	}

	/** A kind of fighter, with the third classes that fit it. */
	enum Profile
	{
		MAGE("NUKER", 94, 103, 110, 95), // Archmage, Mystic Muse, Storm Screamer, Soultaker
		SUMMONER("NUKER", 96, 104, 111), // Arcana Lord, Elemental Master, Spectral Master
		ARCHER("ARCHER", 92, 102, 109), // Sagittarius, Moonlight Sentinel, Ghost Sentinel
		DAGGER("DAGGER", 93, 101, 108), // Adventurer, Wind Rider, Ghost Hunter
		DUELIST("WARRIOR", 88),
		TITAN("WARRIOR", 113),
		GRAND_KHAVATARI("MONK", 114),
		DREADNOUGHT("WARRIOR", 89),
		TANK("TANK", 90, 99, 91, 106), // Phoenix Knight, Eva's Templar, Hell Knight, Shillien Templar
		HEALER("HEALER", 97, 105, 112), // Cardinal, Eva's Saint, Shillien Saint
		SPOILER("BOUNTY_HUNTER", 117); // Fortune Seeker

		final String role;
		final int[] classes;

		Profile(String role, int... classes)
		{
			this.role = role;
			this.classes = classes;
		}

		Pick pick(IntUnaryOperator rnd)
		{
			return new Pick(classes[rnd.applyAsInt(classes.length)], role);
		}
	}

	/** The party types a PK party can be. */
	enum PartyType
	{
		ARCHER,
		MAGE,
		MELEE,
		MIXED
	}

	private static final Profile[] SOLO_PROFILES =
	{
		Profile.MAGE, Profile.SUMMONER, Profile.ARCHER, Profile.DAGGER, Profile.DUELIST, Profile.TITAN, Profile.GRAND_KHAVATARI, Profile.TANK, Profile.DREADNOUGHT
	};
	/** How often each solo profile comes: the seven likely ones twelve, tank and Dreadnought five. */
	private static final int[] SOLO_WEIGHTS = { 12, 12, 12, 12, 12, 12, 12, 5, 5 };

	private static final Profile[] PK_ARCHER = { Profile.ARCHER };
	private static final Profile[] PK_MAGE = { Profile.MAGE, Profile.SUMMONER };
	private static final Profile[] PK_MELEE = { Profile.DUELIST, Profile.TITAN, Profile.DAGGER, Profile.GRAND_KHAVATARI };
	private static final Profile[] PK_MIXED = { Profile.MAGE, Profile.SUMMONER, Profile.ARCHER, Profile.DAGGER, Profile.DUELIST, Profile.TITAN, Profile.GRAND_KHAVATARI };
	private static final Profile[] PVE_DPS = { Profile.MAGE, Profile.SUMMONER, Profile.ARCHER, Profile.DAGGER, Profile.DUELIST, Profile.TITAN, Profile.GRAND_KHAVATARI, Profile.DREADNOUGHT };

	/** The four Horsemen who always come: a Cardinal, a Titan, a Storm Screamer and a Duelist. */
	private static final Pick[] HORSEMEN_CORE =
	{
		new Pick(97, "HEALER"), // Cardinal
		new Pick(113, "WARRIOR"), // Titan
		new Pick(110, "NUKER"), // Storm Screamer
		new Pick(88, "WARRIOR") // Duelist
	};

	/** Where each Horseman beyond the fourth comes from: one pick per extra member. */
	private static final Pick[] HORSEMEN_EXTRA =
	{
		new Pick(93, "DAGGER"), // Adventurer
		new Pick(91, "TANK"), // Hell Knight
		new Pick(95, "NUKER"), // Soultaker
		new Pick(97, "HEALER"), // Cardinal
		new Pick(92, "ARCHER") // Sagittarius
	};

	private EncounterRoster()
	{
	}

	/** One player's no-repeat draw of solo profiles: a bag of weighted tickets, emptied before it is refilled. */
	static final class SoloBag
	{
		private final List<Integer> _tickets = new ArrayList<>();
		private int _last = -1;

		private List<Integer> unlike()
		{
			final List<Integer> other = new ArrayList<>();
			for (int index = 0; index < _tickets.size(); index++)
			{
				if (_tickets.get(index) != _last)
				{
					other.add(index);
				}
			}
			return other;
		}

		/** @return the profile for the next lone Pker. The same profile never comes twice in a row. */
		synchronized Profile next(IntUnaryOperator rnd)
		{
			List<Integer> other = unlike();
			if (other.isEmpty())
			{
				// Empty, or only the last profile's tickets are left: add a fresh bag so the next draw can differ.
				for (int i = 0; i < SOLO_PROFILES.length; i++)
				{
					for (int n = 0; n < SOLO_WEIGHTS[i]; n++)
					{
						_tickets.add(i);
					}
				}
				other = unlike();
			}
			final int at = other.isEmpty() ? rnd.applyAsInt(_tickets.size()) : other.get(rnd.applyAsInt(other.size()));
			final int profile = _tickets.remove(at);
			_last = profile;
			return SOLO_PROFILES[profile];
		}
	}

	/** @return the lone Pker's pick for a profile. */
	static Pick solo(Profile profile, IntUnaryOperator rnd)
	{
		return profile.pick(rnd);
	}

	/** @return a random PK party type, all four equally likely. */
	static PartyType pkType(IntUnaryOperator rnd)
	{
		return PartyType.values()[rnd.applyAsInt(PartyType.values().length)];
	}

	/**
	 * A PK party. One tank half the time, a healer for each full four members half the time each, and the rest are
	 * damage dealers from the party type's pool. Never fewer than one damage dealer.
	 */
	static List<Pick> pkParty(int size, PartyType type, IntUnaryOperator rnd)
	{
		final int total = Math.max(1, size);
		final List<Pick> party = new ArrayList<>();
		int tanks = (total >= 3) && (rnd.applyAsInt(2) == 0) ? 1 : 0;
		int healers = 0;
		for (int slot = 0; slot < (total / 4); slot++)
		{
			if (rnd.applyAsInt(2) == 0)
			{
				healers++;
			}
		}
		while (((tanks + healers) >= total) && ((tanks + healers) > 0))
		{
			if (healers > 0)
			{
				healers--;
			}
			else
			{
				tanks--;
			}
		}
		if (tanks > 0)
		{
			party.add(Profile.TANK.pick(rnd));
		}
		for (int i = 0; i < healers; i++)
		{
			party.add(Profile.HEALER.pick(rnd));
		}
		final Profile[] pool = poolFor(type);
		while (party.size() < total)
		{
			party.add(pool[rnd.applyAsInt(pool.length)].pick(rnd));
		}
		return party;
	}

	/**
	 * A PvE party that wants the player's spot: one tank, one healer, one spoiler, and one damage dealer for every member
	 * of the player's party (the player counts as one), from any damage class.
	 */
	static List<Pick> pveParty(int playerPartySize, IntUnaryOperator rnd)
	{
		final List<Pick> party = new ArrayList<>();
		party.add(Profile.TANK.pick(rnd));
		party.add(Profile.HEALER.pick(rnd));
		party.add(Profile.SPOILER.pick(rnd));
		for (int i = 0; i < Math.max(1, playerPartySize); i++)
		{
			party.add(PVE_DPS[rnd.applyAsInt(PVE_DPS.length)].pick(rnd));
		}
		return party;
	}

	/**
	 * The Horsemen for a group of {@code size}: the four who always come (fewer if the group is smaller), and for every
	 * member beyond four one more drawn at random from the five extras, none twice.
	 */
	static List<Pick> horsemen(int size, IntUnaryOperator rnd)
	{
		final int count = Math.max(1, size);
		final List<Pick> group = new ArrayList<>(Arrays.asList(HORSEMEN_CORE).subList(0, Math.min(HORSEMEN_CORE.length, count)));
		final List<Pick> left = new ArrayList<>(Arrays.asList(HORSEMEN_EXTRA));
		while ((group.size() < count) && !left.isEmpty())
		{
			group.add(left.remove(rnd.applyAsInt(left.size())));
		}
		return group;
	}

	/** The lone Pker: a Titan or a Duelist, evenly. */
	static Pick pker(IntUnaryOperator rnd)
	{
		return (rnd.applyAsInt(2) == 0) ? Profile.TITAN.pick(rnd) : Profile.DUELIST.pick(rnd);
	}

	/** The themes of a rare all-alike party. */
	enum Theme
	{
		ARCHERS,
		MAGES,
		MELEE,
		TANKS,
		DWARVES,
		ONE_CLASS
	}

	/** A rare party where everyone is alike: no healer, no tank unless it is the theme. */
	static final class Novelty
	{
		final Theme theme;
		final List<Pick> roster;

		Novelty(Theme theme, List<Pick> roster)
		{
			this.theme = theme;
			this.roster = roster;
		}
	}

	private static final Profile[] NOVELTY_MELEE = { Profile.DUELIST, Profile.TITAN, Profile.DAGGER, Profile.GRAND_KHAVATARI, Profile.DREADNOUGHT };
	private static final Profile[] NOVELTY_MAGES = { Profile.MAGE, Profile.SUMMONER };
	private static final Profile[] NOVELTY_ONE_CLASS = { Profile.MAGE, Profile.SUMMONER, Profile.ARCHER, Profile.DAGGER, Profile.DUELIST, Profile.TITAN, Profile.GRAND_KHAVATARI, Profile.TANK, Profile.DREADNOUGHT, Profile.SPOILER };

	/** @return {@code true} when a party turns out to be a rare all-alike one; {@code roll} is in [0, 10000), percent may be fractional. */
	static boolean noveltyRolled(double percent, int roll)
	{
		return roll < (Math.max(0, Math.min(100, percent)) * 100);
	}

	/** A rare all-alike party of {@code size}: all archers, all mages, all melee, all tanks, all dwarves, or six of one class. */
	static Novelty novelty(int size, IntUnaryOperator rnd)
	{
		final int total = Math.max(1, size);
		final Theme theme = Theme.values()[rnd.applyAsInt(Theme.values().length)];
		final List<Pick> party = new ArrayList<>();
		if (theme == Theme.ONE_CLASS)
		{
			final Pick one = NOVELTY_ONE_CLASS[rnd.applyAsInt(NOVELTY_ONE_CLASS.length)].pick(rnd);
			for (int i = 0; i < total; i++)
			{
				party.add(one);
			}
			return new Novelty(theme, party);
		}
		final Profile[] pool;
		switch (theme)
		{
			case ARCHERS:
			{
				pool = PK_ARCHER;
				break;
			}
			case MAGES:
			{
				pool = NOVELTY_MAGES;
				break;
			}
			case MELEE:
			{
				pool = NOVELTY_MELEE;
				break;
			}
			case TANKS:
			{
				pool = new Profile[] { Profile.TANK };
				break;
			}
			default:
			{
				pool = new Profile[] { Profile.SPOILER };
				break;
			}
		}
		for (int i = 0; i < total; i++)
		{
			party.add(pool[rnd.applyAsInt(pool.length)].pick(rnd));
		}
		return new Novelty(theme, party);
	}

	/** @return {@code true} when a contested spot gets a PvE party (otherwise a PK party), for a roll in [0, 100). */
	static boolean pveRolled(int pvePercent, int roll)
	{
		return roll < Math.max(0, Math.min(100, pvePercent));
	}

	/** @return {@code true} when a PvE party asks first (otherwise it ambushes), for a roll in [0, 100). */
	static boolean asksFirst(int askPercent, int roll)
	{
		return roll < Math.max(0, Math.min(100, askPercent));
	}

	/** @return the whole roster trimmed to at most {@code cap} members, keeping the order. */
	static List<Pick> capped(List<Pick> roster, int cap)
	{
		final int limit = Math.max(1, cap);
		return (roster.size() <= limit) ? roster : new ArrayList<>(roster.subList(0, limit));
	}

	/** @return how many of each role the roster holds (for tests and logs). */
	static Map<String, Integer> roles(List<Pick> roster)
	{
		final Map<String, Integer> counts = new HashMap<>();
		for (Pick pick : roster)
		{
			counts.merge(pick.role, 1, Integer::sum);
		}
		return counts;
	}

	private static Profile[] poolFor(PartyType type)
	{
		switch (type)
		{
			case ARCHER:
			{
				return PK_ARCHER;
			}
			case MAGE:
			{
				return PK_MAGE;
			}
			case MELEE:
			{
				return PK_MELEE;
			}
			default:
			{
				return PK_MIXED;
			}
		}
	}
}
