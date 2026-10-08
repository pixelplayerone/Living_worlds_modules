package modules.arenadueling;

import java.util.HashMap;
import java.util.Map;

public class ArenaRulesTest
{
	private static int checks;
	private static int failures;

	private static void check(boolean ok, String what)
	{
		checks++;
		if (!ok)
		{
			failures++;
			System.out.println("FAIL: " + what);
		}
	}

	public static void main(String[] args)
	{
		check(ArenaRules.key("Eva's Templar").equals("evastemplar"), "key strips punctuation");
		check(ArenaRules.key("  Phoenix  Knight ").equals("phoenixknight"), "key strips spaces");
		check(ArenaRules.key(null).isEmpty(), "key of null");
		final Map<String, Integer> names = new HashMap<>();
		names.put("titan", 113);
		names.put("phoenixknight", 90);
		check(ArenaRules.classIdFor("Titan", names) == 113, "class by name");
		check(ArenaRules.classIdFor("phoenix-knight", names) == 90, "class by punctuated name");
		check(ArenaRules.classIdFor("banana", names) == 0, "unknown class");

		check(ArenaRules.parseAmount("50000") == 50000, "plain amount");
		check(ArenaRules.parseAmount("50k") == 50000, "k amount");
		check(ArenaRules.parseAmount("1m") == 1_000_000, "m amount");
		check(ArenaRules.parseAmount("2.5M") == 2_500_000, "decimal m amount");
		check(ArenaRules.parseAmount("1,000") == 1000, "comma amount");
		check(ArenaRules.parseAmount("abc") == 0, "junk amount");
		check(ArenaRules.parseAmount("-5") == 0, "negative amount");
		check(ArenaRules.parseAmount("") == 0, "empty amount");
		check(ArenaRules.parseAmount(null) == 0, "null amount");
		check(ArenaRules.parseAmount("NaN") == 0, "NaN amount");

		final int[] allowed = { 10000, 50000, 100000 };
		check(ArenaRules.allowedStake(50000, allowed) == 50000, "allowed stake");
		check(ArenaRules.allowedStake(60000, allowed) == 0, "disallowed stake");
		check(ArenaRules.allowedStake(0, allowed) == 0, "zero stake");

		check(ArenaRules.verdict(Boolean.TRUE, false, true) == ArenaRules.Verdict.WIN, "win");
		check(ArenaRules.verdict(Boolean.FALSE, false, true) == ArenaRules.Verdict.LOSE, "loss");
		check(ArenaRules.verdict(null, true, true) == ArenaRules.Verdict.LOSE, "cancel with the phantom still there forfeits");
		check(ArenaRules.verdict(null, true, false) == ArenaRules.Verdict.REFUND, "cancel with the phantom gone refunds");
		check(ArenaRules.verdict(null, false, true) == ArenaRules.Verdict.REFUND, "timeout refunds");
		check(ArenaRules.verdict(Boolean.TRUE, true, false) == ArenaRules.Verdict.WIN, "a result beats the cancel flag");
		check(ArenaRules.fee(100000, 10) == 10000, "10 percent fee");
		check(ArenaRules.fee(100000, 0) == 0, "no fee");
		check(ArenaRules.fee(100000, 99) == 50000, "fee capped at 50 percent");
		check(ArenaRules.fee(-5, 10) == 0, "no negative fee");
		check(ArenaRules.winnings(100000, 10, Integer.MAX_VALUE) == 90000, "win pays stake less fee");
		check(ArenaRules.winnings(100000, 10, 30000) == 30000, "win limited by room");
		check(ArenaRules.winnings(100000, 10, 0) == 0, "no room, no winnings");
		check(ArenaRules.winnings(100000, 10, -5) == 0, "negative room, no winnings");
		check(ArenaRules.winRoom(0, 999) == Integer.MAX_VALUE, "cap 0 is unlimited");
		check(ArenaRules.winRoom(500000, 200000) == 300000, "room is what is left");
		check(ArenaRules.winRoom(500000, 900000) == 0, "room never negative");

		check(ArenaRules.stakeLive(1000, 999), "stake live before expiry");
		check(!ArenaRules.stakeLive(1000, 1000), "stake dead at expiry");

		check(ArenaRules.regularsToAdd(0, 3, 10) == 3, "add up to the wanted");
		check(ArenaRules.regularsToAdd(3, 3, 10) == 0, "none when full");
		check(ArenaRules.regularsToAdd(5, 3, 10) == 0, "never negative");
		check(ArenaRules.regularsToAdd(0, 3, 2) == 2, "limited by room");
		check(ArenaRules.regularsToAdd(0, 3, -1) == 0, "no room");

		for (int roll = 0; roll <= 4; roll++)
		{
			final int level = ArenaRules.regularLevel(40, 2, roll);
			check((level >= 38) && (level <= 42), "level within spread " + level);
		}
		check(ArenaRules.regularLevel(40, 2, 99) == 42, "roll clamped high");
		check(ArenaRules.regularLevel(40, 2, -5) == 38, "roll clamped low");
		check(ArenaRules.regularLevel(1, 2, 0) == 1, "level never below 1");
		check(ArenaRules.regularLevel(40, 0, 0) == 40, "no spread");

		check(!ArenaRules.sendHome(0, 100000, 60000), "never empty, nobody to send");
		check(!ArenaRules.sendHome(50000, 100000, 60000), "still within grace");
		check(ArenaRules.sendHome(40000, 100000, 60000), "past grace");

		check(ArenaRules.sparPair(1, 0, 0) == null, "one cannot spar");
		check(ArenaRules.sparPair(0, 0, 0) == null, "none cannot spar");
		for (int n = 2; n <= 6; n++)
		{
			for (int r1 = 0; r1 < n; r1++)
			{
				for (int r2 = 0; r2 < n - 1; r2++)
				{
					final int[] pair = ArenaRules.sparPair(n, r1, r2);
					check((pair != null) && (pair[0] != pair[1]) && (pair[0] >= 0) && (pair[1] >= 0) && (pair[0] < n) && (pair[1] < n), "spar pair distinct and in range " + n + "/" + r1 + "/" + r2);
				}
			}
		}
		System.out.println("Ran " + checks + " checks, " + failures + " failure(s).");
		if (failures > 0)
		{
			System.exit(1);
		}
	}
}
