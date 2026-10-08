package modules.phantomencounters;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.IntUnaryOperator;

import modules.phantomencounters.EncounterRoster.PartyType;
import modules.phantomencounters.EncounterRoster.Pick;
import modules.phantomencounters.EncounterRoster.Profile;
import modules.phantomencounters.EncounterRoster.SoloBag;

public class EncounterRosterTest
{
	static int checks;
	static int failed;

	static void eq(Object expected, Object actual, String what)
	{
		checks++;
		if (!expected.equals(actual))
		{
			failed++;
			System.out.println("FAIL " + what + ": expected " + expected + " got " + actual);
		}
	}

	static void truth(boolean condition, String what)
	{
		eq(true, condition, what);
	}

	static int count(List<Pick> roster, String role)
	{
		return EncounterRoster.roles(roster).getOrDefault(role, 0);
	}

	public static void main(String[] args)
	{
		final Random random = new Random(7);
		final IntUnaryOperator rnd = random::nextInt;

		// Solo bag: no repeat in a row across many refills, every profile shows up, weights hold over a full bag.
		final SoloBag bag = new SoloBag();
		Profile last = null;
		final Set<Profile> seen = new HashSet<>();
		int titans = 0;
		int tanks = 0;
		for (int i = 0; i < 94 * 20; i++)
		{
			final Profile p = bag.next(rnd);
			truth(p != last, "no repeat in a row at draw " + i);
			last = p;
			seen.add(p);
			if (p == Profile.TITAN)
			{
				titans++;
			}
			if (p == Profile.TANK)
			{
				tanks++;
			}
		}
		eq(9, seen.size(), "all nine solo profiles come up");
		truth(titans > (tanks * 2), "a likely profile comes more than a tank: " + titans + " vs " + tanks);
		final SoloBag fresh = new SoloBag();
		final int[] counts = new int[Profile.values().length];
		for (int i = 0; i < 94; i++)
		{
			counts[fresh.next(i2 -> 0).ordinal()]++;
		}
		truth(counts[Profile.TITAN.ordinal()] > 0, "a full bag holds titans");
		eq(0, counts[Profile.HEALER.ordinal()], "healers never come solo");
		eq(0, counts[Profile.SPOILER.ordinal()], "spoilers never come solo");

		// The Pker is a Titan or a Duelist, and both come.
		final Set<Integer> pker = new HashSet<>();
		for (int i = 0; i < 100; i++)
		{
			final Pick p = EncounterRoster.pker(rnd);
			truth((p.classId == 113) || (p.classId == 88), "pker is a titan or a duelist: " + p);
			eq("WARRIOR", p.role, "pker fights as a warrior");
			pker.add(p.classId);
		}
		eq(2, pker.size(), "both pker classes come up");

		// PK parties.
		for (int size = 1; size <= 9; size++)
		{
			for (PartyType type : PartyType.values())
			{
				for (int trial = 0; trial < 60; trial++)
				{
					final List<Pick> party = EncounterRoster.pkParty(size, type, rnd);
					eq(size, party.size(), "pk party size " + size);
					truth(count(party, "TANK") <= 1, "at most one tank");
					truth(count(party, "HEALER") <= (size / 4), "healers per four: size " + size);
					final int dps = size - count(party, "TANK") - count(party, "HEALER");
					truth(dps >= 1, "at least one damage dealer");
					for (Pick pick : party)
					{
						truth(pick.classId != 89, "no Dreadnought in a pk party");
						truth(pick.classId != 117, "no Fortune Seeker in a pk party");
						if ("TANK".equals(pick.role) || "HEALER".equals(pick.role))
						{
							continue;
						}
						if (type == PartyType.ARCHER)
						{
							eq("ARCHER", pick.role, "archer party dps are archers");
						}
						if (type == PartyType.MAGE)
						{
							eq("NUKER", pick.role, "mage party dps are mages or summoners");
						}
						if (type == PartyType.MELEE)
						{
							truth(!"NUKER".equals(pick.role) && !"ARCHER".equals(pick.role), "melee party has no casters or archers");
						}
					}
				}
			}
		}
		int withTank = 0;
		for (int trial = 0; trial < 400; trial++)
		{
			if (count(EncounterRoster.pkParty(6, PartyType.MIXED, rnd), "TANK") == 1)
			{
				withTank++;
			}
		}
		truth((withTank > 140) && (withTank < 260), "a tank about half the time: " + withTank + "/400");
		for (int trial = 0; trial < 50; trial++)
		{
			eq(0, count(EncounterRoster.pkParty(3, PartyType.MIXED, rnd), "HEALER"), "no healer slot under four");
		}

		// PvE parties: tank, healer, spoiler, and one damage dealer per member of the player's party.
		for (int partySize = 1; partySize <= 9; partySize++)
		{
			final List<Pick> party = EncounterRoster.pveParty(partySize, rnd);
			eq(3 + partySize, party.size(), "pve party matches the player's party: " + partySize);
			eq(1, count(party, "TANK"), "pve party has one tank");
			eq(1, count(party, "HEALER"), "pve party has one healer");
			eq(1, count(party, "BOUNTY_HUNTER"), "pve party has one spoiler");
		}
		boolean dreadnought = false;
		for (int trial = 0; trial < 200; trial++)
		{
			for (Pick pick : EncounterRoster.pveParty(5, rnd))
			{
				dreadnought |= (pick.classId == 89);
			}
		}
		truth(dreadnought, "Dreadnoughts are allowed in a pve party");

		// Horsemen: the same four always, then one random extra for each member beyond four.
		final int[] core = { 97, 113, 110, 88 };
		for (int size = 1; size <= 9; size++)
		{
			final List<Pick> group = EncounterRoster.horsemen(size, rnd);
			eq(size, group.size(), "horsemen size " + size);
			for (int i = 0; (i < 4) && (i < size); i++)
			{
				eq(core[i], group.get(i).classId, "horseman " + i + " is fixed at size " + size);
			}
			final Set<Integer> picked = new HashSet<>();
			for (int i = 4; i < size; i++)
			{
				final int id = group.get(i).classId;
				truth((id == 93) || (id == 91) || (id == 95) || (id == 97) || (id == 92), "extra horseman from the pool: " + id);
				truth(picked.add(id), "no extra horseman twice at size " + size);
			}
		}
		final Set<Integer> extras = new HashSet<>();
		for (int trial = 0; trial < 200; trial++)
		{
			extras.add(EncounterRoster.horsemen(5, rnd).get(4).classId);
		}
		eq(5, extras.size(), "every extra comes up for the fifth horseman");

		// Rolls.
		truth(EncounterRoster.pveRolled(70, 69), "70% pve: roll 69 is pve");
		truth(!EncounterRoster.pveRolled(70, 70), "70% pve: roll 70 is a pk party");
		truth(EncounterRoster.asksFirst(50, 49), "50% ask: roll 49 asks");
		truth(!EncounterRoster.asksFirst(50, 50), "50% ask: roll 50 ambushes");
		eq(4, EncounterRoster.capped(EncounterRoster.pveParty(9, rnd), 4).size(), "roster capped");

		// Rare all-alike parties.
		truth(EncounterRoster.noveltyRolled(4.0, 399), "4% novelty: roll 399 hits");
		truth(!EncounterRoster.noveltyRolled(4.0, 400), "4% novelty: roll 400 misses");
		truth(!EncounterRoster.noveltyRolled(0, 0), "0% novelty never hits");
		final Set<EncounterRoster.Theme> themes = new HashSet<>();
		for (int trial = 0; trial < 600; trial++)
		{
			final int size = 3 + (trial % 7);
			final EncounterRoster.Novelty n = EncounterRoster.novelty(size, rnd);
			themes.add(n.theme);
			eq(size, n.roster.size(), "novelty party size");
			final Set<String> roles = new HashSet<>();
			final Set<Integer> classes = new HashSet<>();
			for (Pick pick : n.roster)
			{
				roles.add(pick.role);
				classes.add(pick.classId);
				truth(pick.classId != 97 && pick.classId != 105 && pick.classId != 112, "no healer in an all-alike party");
			}
			if (n.theme == EncounterRoster.Theme.ARCHERS)
			{
				eq(1, roles.size(), "all archers share a role");
				truth(roles.contains("ARCHER"), "archers are archers");
			}
			if (n.theme == EncounterRoster.Theme.MAGES)
			{
				truth(roles.contains("NUKER") && (roles.size() == 1), "mages are casters");
			}
			if (n.theme == EncounterRoster.Theme.TANKS)
			{
				truth(roles.contains("TANK") && (roles.size() == 1), "tanks are tanks");
			}
			if (n.theme == EncounterRoster.Theme.DWARVES)
			{
				eq(1, classes.size(), "dwarves are all Fortune Seekers");
				truth(classes.contains(117), "dwarves are Fortune Seekers");
			}
			if (n.theme == EncounterRoster.Theme.ONE_CLASS)
			{
				eq(1, classes.size(), "one class means one class");
			}
			if (n.theme == EncounterRoster.Theme.MELEE)
			{
				truth(!roles.contains("NUKER") && !roles.contains("ARCHER") && !roles.contains("HEALER"), "melee party has no casters, archers or healers");
			}
		}
		eq(6, themes.size(), "every novelty theme comes up");

		System.out.println("Ran " + checks + " checks, " + failed + " failure(s).");
		System.exit(failed == 0 ? 0 : 1);
	}
}
