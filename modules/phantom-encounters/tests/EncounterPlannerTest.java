package modules.phantomencounters;

import modules.phantomencounters.EncounterPlanner.Kind;

public class EncounterPlannerTest
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

	public static void main(String[] args)
	{
		// Levels: wimp 2-3 under, normie same, hard +3, horsemen +5, pker +11.
		for (int roll = 0; roll < 50; roll++)
		{
			final int wimp = EncounterPlanner.levelFor(50, -3, -2, roll);
			truth((wimp == 47) || (wimp == 48), "wimp 2-3 under: " + wimp);
			eq(50, EncounterPlanner.levelFor(50, 0, 0, roll), "normie same level");
			eq(53, EncounterPlanner.levelFor(50, 3, 3, roll), "hard +3");
			eq(55, EncounterPlanner.levelFor(50, 5, 5, roll), "horsemen +5");
			eq(61, EncounterPlanner.levelFor(50, 11, 11, roll), "pker +11");
			final int n = EncounterPlanner.enchantIn(0, 3, roll);
			truth((n >= 0) && (n <= 3), "normie 0-3: " + n);
			final int h = EncounterPlanner.enchantIn(3, 4, roll);
			truth((h == 3) || (h == 4), "hard 3-4: " + h);
			final int hm = EncounterPlanner.enchantIn(5, 7, roll);
			truth((hm >= 5) && (hm <= 7), "horsemen 5-7: " + hm);
			eq(16, EncounterPlanner.enchantIn(16, 16, roll), "pker is a full +16");
			eq(0, EncounterPlanner.enchantIn(0, 0, roll), "wimp +0");
		}
		eq(80, EncounterPlanner.levelFor(78, 11, 11, 3), "level clamped to 80");
		eq(1, EncounterPlanner.levelFor(3, -3, -3, 5), "level clamped to 1");
		eq(52, EncounterPlanner.levelFor(50, 2, 2, -7), "negative roll is safe");
		final int reversed = EncounterPlanner.levelFor(50, 3, -3, 4);
		truth((reversed >= 47) && (reversed <= 53), "reversed offsets still land in range: " + reversed);
		eq(30, EncounterPlanner.enchantIn(99, 99, 1), "enchant capped at 30");
		eq(0, EncounterPlanner.enchantIn(-5, -2, 1), "negative enchant floors at 0");

		// Group sizes.
		eq(1, EncounterPlanner.groupSize(Kind.WIMP, 1, 4, 9), "wimp solo");
		eq(5, EncounterPlanner.groupSize(Kind.NORMIE, 5, 4, 9), "normie matches the party");
		eq(9, EncounterPlanner.groupSize(Kind.HARD, 9, 4, 9), "hard matches a party of 9");
		eq(9, EncounterPlanner.groupSize(Kind.WIMP, 12, 4, 9), "capped");
		eq(4, EncounterPlanner.groupSize(Kind.HORSEMEN, 1, 4, 9), "horsemen solo still four");
		eq(4, EncounterPlanner.groupSize(Kind.HORSEMEN, 3, 4, 9), "horsemen small party still four");
		eq(7, EncounterPlanner.groupSize(Kind.HORSEMEN, 7, 4, 9), "horsemen match a bigger party");
		eq(9, EncounterPlanner.groupSize(Kind.HORSEMEN, 20, 4, 9), "horsemen capped");
		eq(1, EncounterPlanner.groupSize(Kind.PKER, 9, 4, 9), "pker is always one");
		eq(1, EncounterPlanner.groupSize(Kind.WIMP, 0, 4, 0), "never fewer than one");

		// Delays.
		eq(100L, EncounterPlanner.delayMs(100, 200, 0), "delay low end");
		eq(199L, EncounterPlanner.delayMs(100, 200, 999), "delay high end");
		eq(150L, EncounterPlanner.delayMs(100, 200, 500), "delay middle");
		eq(100L, EncounterPlanner.delayMs(100, 100, 700), "equal range");
		eq(100L, EncounterPlanner.delayMs(100, 50, 700), "reversed range collapses");
		eq(100L, EncounterPlanner.delayMs(100, 200, -5), "negative roll clamps");

		// Which kind is due: the rarest due kind wins; 0 means not running.
		eq(-1, EncounterPlanner.pickDue(new long[] { 0, 0, 0, 0, 0 }, 1000), "nothing running");
		eq(-1, EncounterPlanner.pickDue(new long[] { 2000, 2000, 0, 0, 0 }, 1000), "nothing due yet");
		eq(1, EncounterPlanner.pickDue(new long[] { 500, 900, 5000, 0, 0 }, 1000), "normie is the rarest due");
		eq(4, EncounterPlanner.pickDue(new long[] { 500, 900, 800, 700, 1000 }, 1000), "pker beats everything when due");
		eq(2, EncounterPlanner.pickDue(new long[] { 500, 900, 1000, 5000, 0 }, 1000), "due exactly now counts");
		eq(0, EncounterPlanner.pickDue(new long[] { 1, 0, 0, 0, 0 }, 1000), "only wimp");

		// After one starts: its own timer restarts, and nothing else starts inside the quiet time.
		final long[] due = { 500, 600, 90_000, 0, 5_000_000 };
		EncounterPlanner.afterStart(due, 1, 2_000_000, 300_000);
		eq(2_000_000L, due[1], "fired kind gets its next time");
		eq(300_000L, due[0], "a due kind waits out the quiet time");
		eq(300_000L, due[2], "another due kind waits out the quiet time");
		eq(0L, due[3], "a kind that is not running stays off");
		eq(5_000_000L, due[4], "a later kind stays put");
		final long[] none = { 0, 0, 0, 0, 0 };
		EncounterPlanner.afterStart(none, 0, 1000, 0);
		eq(1000L, none[0], "zero quiet time changes nothing else");

		// Quiet: pushes running timers out, leaves off ones alone.
		final long[] q = { 100, 0, 900, 50_000 };
		EncounterPlanner.quiet(q, 1000);
		eq(1000L, q[0], "quiet pushes early timer");
		eq(0L, q[1], "quiet leaves off timer");
		eq(1000L, q[2], "quiet pushes timer before the end");
		eq(50_000L, q[3], "quiet leaves later timer");

		// Contest dice: percent in hundredths.
		eq(false, EncounterPlanner.contestRolled(0, 0), "0% never");
		eq(false, EncounterPlanner.contestRolled(-5, 0), "negative never");
		eq(true, EncounterPlanner.contestRolled(2.0, 0), "2% roll 0");
		eq(true, EncounterPlanner.contestRolled(2.0, 199), "2% roll 199");
		eq(false, EncounterPlanner.contestRolled(2.0, 200), "2% roll 200");
		eq(true, EncounterPlanner.contestRolled(100, 9999), "100% always");
		eq(true, EncounterPlanner.contestRolled(500, 9999), "over 100% clamps");
		eq(false, EncounterPlanner.contestRolled(0.5, 50), "0.5% roll 50");
		int wins = 0;
		for (int r = 0; r < 10000; r++)
		{
			if (EncounterPlanner.contestRolled(2.0, r))
			{
				wins++;
			}
		}
		eq(200, wins, "2% is exactly 200 of 10000");

		// Contest readiness.
		eq(true, EncounterPlanner.contestReady(1000, 1000, 1000, 0, 2), "ready at the edge");
		eq(false, EncounterPlanner.contestReady(999, 0, 1000, 0, 2), "quiet blocks");
		eq(false, EncounterPlanner.contestReady(999, 1000, 0, 0, 2), "cooldown blocks");
		eq(false, EncounterPlanner.contestReady(5000, 0, 0, 2, 2), "full blocks");
		eq(true, EncounterPlanner.contestReady(5000, 0, 0, 1, 2), "room allows");

		// Farming areas.
		final int[] c = { 100, 100, 50, 1000, -1000, 10 };
		eq(true, FarmingGrounds.contains(c, 100, 100), "center in");
		eq(true, FarmingGrounds.contains(c, 150, 100), "edge in");
		eq(false, FarmingGrounds.contains(c, 151, 100), "just out");
		eq(true, FarmingGrounds.contains(c, 1000, -1005), "second circle");
		eq(false, FarmingGrounds.contains(c, 0, 0), "far out");
		eq(false, FarmingGrounds.contains(new int[0], 0, 0), "empty list");
		truth(FarmingGroundData.CIRCLES.length == (838 * 3), "838 generated circles");

		System.out.println(checks + " checks, " + failed + " failed");
		System.exit(failed == 0 ? 0 : 1);
	}
}
