package modules.worldtuner;

import java.util.Random;

import modules.worldtuner.WorldTuner.Plan;

/** Standalone checks for the tuning rules: no server needed. */
public final class WorldTunerTest
{
	private static int passed;
	private static int failed;

	private static void check(String name, boolean ok)
	{
		if (ok)
		{
			passed++;
		}
		else
		{
			failed++;
			System.out.println("FAIL: " + name);
		}
	}

	private static WorldTuner t(double respawn, double count, int min, int max, String regions, String exclude, String overrides)
	{
		return new WorldTuner(respawn, count, min, max, regions, exclude, overrides, 4);
	}

	/** Behaves like the server's Spawn: equal by coordinates, and the coordinates change when it spawns. */
	private static final class MovingSpawn
	{
		int x;

		MovingSpawn(int x)
		{
			this.x = x;
		}

		@Override
		public int hashCode()
		{
			return x;
		}

		@Override
		public boolean equals(Object o)
		{
			return (o instanceof MovingSpawn) && (((MovingSpawn) o).x == x);
		}
	}

	public static void main(String[] args)
	{
		final Random rnd = new Random(1);

		check("stock settings are inert", t(0, 1, 1, 85, "", "", "").isInert());
		check("a multiplier makes it live", !t(100, 1, 1, 85, "", "", "").isInert());
		check("an override makes it live", !t(0, 1, 1, 85, "", "", "20001:2:1").isInert());

		check("region from a stock path", WorldTuner.regionOf("data/spawns/Gludio/Wasteland.xml").equals("Gludio"));
		check("region from a windows path", WorldTuner.regionOf("data\\spawns\\Dion\\X.xml").equals("Dion"));
		check("no region for a loose file", WorldTuner.regionOf("somewhere/else.xml").isEmpty());

		// Count 2.0: exactly one extra everywhere.
		final WorldTuner x2 = t(0, 2.0, 1, 85, "", "", "");
		boolean allOne = true;
		for (int i = 0; i < 200; i++)
		{
			final Plan p = x2.decide(20001, 30, "Gludio", rnd);
			allOne &= (p.extraSpawns == 1) && !p.remove;
		}
		check("count 2.0 always adds exactly one", allOne);

		// Count 1.5: about half get an extra.
		final WorldTuner x15 = t(0, 1.5, 1, 85, "", "", "");
		int extras = 0;
		for (int i = 0; i < 4000; i++)
		{
			extras += x15.decide(20001, 30, "Gludio", rnd).extraSpawns;
		}
		check("count 1.5 adds about half (" + extras + "/4000)", (extras > 1800) && (extras < 2200));

		// Count 0.5: about half are switched off.
		final WorldTuner half = t(0, 0.5, 1, 85, "", "", "");
		int removed = 0;
		for (int i = 0; i < 4000; i++)
		{
			removed += half.decide(20001, 30, "Gludio", rnd).remove ? 1 : 0;
		}
		check("count 0.5 removes about half (" + removed + "/4000)", (removed > 1800) && (removed < 2200));
		check("count 0 removes all", t(0, 0, 1, 85, "", "", "").decide(1, 30, "", rnd).remove);

		// Cap on extras.
		check("extras capped", new WorldTuner(0, 50, 1, 85, "", "", "", 4).decide(1, 30, "", rnd).extraSpawns == 4);

		// Filters.
		final WorldTuner lv = t(0, 2.0, 20, 40, "", "", "");
		check("below the level range untouched", lv.decide(1, 19, "", rnd).isNoOp());
		check("above the level range untouched", lv.decide(1, 41, "", rnd).isNoOp());
		check("inside the level range changed", !lv.decide(1, 30, "", rnd).isNoOp());
		check("level range edges are inclusive", !lv.decide(1, 20, "", rnd).isNoOp() && !lv.decide(1, 40, "", rnd).isNoOp());

		final WorldTuner rg = t(0, 2.0, 1, 85, "Gludio, dion", "", "");
		check("listed region changed", !rg.decide(1, 30, "Gludio", rnd).isNoOp() && !rg.decide(1, 30, "Dion", rnd).isNoOp());
		check("other region untouched", rg.decide(1, 30, "Oren", rnd).isNoOp());
		check("region and level stack", t(0, 2.0, 50, 60, "Gludio", "", "").decide(1, 30, "Gludio", rnd).isNoOp());

		final WorldTuner ex = t(0, 2.0, 1, 85, "", "20001, 20002", "");
		check("excluded monster untouched", ex.decide(20001, 30, "", rnd).isNoOp() && ex.decide(20002, 30, "", rnd).isNoOp());
		check("other monster changed", !ex.decide(20003, 30, "", rnd).isNoOp());

		// Overrides win over filters and globals.
		final WorldTuner ov = t(0, 1.0, 50, 60, "Oren", "", "20001:3.0:300; 20002:1:100");
		final Plan o1 = ov.decide(20001, 10, "Gludio", rnd);
		check("override ignores level and region", (o1.extraSpawns == 2) && (Math.abs(o1.respawnMultiplier - 0.25) < 1e-9));
		final Plan o2 = ov.decide(20002, 10, "Gludio", rnd);
		check("override with count 1 only scales respawn", (o2.extraSpawns == 0) && !o2.remove && (Math.abs(o2.respawnMultiplier - 0.5) < 1e-9));
		check("exclude beats override", t(0, 1, 1, 85, "", "20001", "20001:3:0").decide(20001, 10, "", rnd).isNoOp());
		check("malformed overrides ignored", t(0, 2, 1, 85, "", "abc,,", "x:y;::;20005:2").decide(20005, 10, "", rnd).extraSpawns == 1);

		// Percent faster.
		check("30 percent faster = delay / 1.3", Math.abs(WorldTuner.multiplierOf(30) - (1 / 1.3)) < 1e-9);
		check("50 percent faster = delay / 1.5", Math.abs(WorldTuner.multiplierOf(50) - (1 / 1.5)) < 1e-9);
		check("100 percent faster = half the delay", Math.abs(WorldTuner.multiplierOf(100) - 0.5) < 1e-9);
		check("0 percent = stock", WorldTuner.multiplierOf(0) == 1.0);
		check("-50 percent = twice the delay", Math.abs(WorldTuner.multiplierOf(-50) - 2.0) < 1e-9);
		check("slowdown is clamped at ten times", Math.abs(WorldTuner.multiplierOf(-500) - 10.0) < 1e-9);
		check("30 percent faster on 60s is about 46s", WorldTuner.scaleDelay(60000, WorldTuner.multiplierOf(30), 5) == 46154);
		check("50 percent is not inert", !t(50, 1, 1, 85, "", "", "").isInert());
		check("override respawn omitted uses the global percent", Math.abs(t(100, 1, 1, 85, "", "", "20009:2").decide(20009, 10, "", rnd).respawnMultiplier - 0.5) < 1e-9);

		// Delay scaling.
		check("half delay", WorldTuner.scaleDelay(60000, 0.5, 5) == 30000);
		check("double delay", WorldTuner.scaleDelay(60000, 2.0, 5) == 120000);
		check("zero delay stays zero", WorldTuner.scaleDelay(0, 0.5, 5) == 0);
		check("floor honoured", WorldTuner.scaleDelay(10000, 0.01, 5) == 5000);
		check("no overflow", WorldTuner.scaleDelay(2000000000, 1000.0, 5) == Integer.MAX_VALUE);
		check("1.0 unchanged", WorldTuner.scaleDelay(12345, 1.0, 5) == 12345);

		// Tracking: a spawn that moves after being marked must still count as marked, and equal-looking spawns stay distinct.
		final java.util.Set<MovingSpawn> tuned = WorldTuner.newIdentitySet();
		final MovingSpawn a = new MovingSpawn(5);
		check("first sighting is new", tuned.add(a));
		a.x = 99; // spawning changed its coordinates
		check("a moved spawn is still recognised", !tuned.add(a));
		check("a different spawn at the same place is distinct", tuned.add(new MovingSpawn(99)));
		final java.util.Set<MovingSpawn> plain = new java.util.HashSet<>();
		final MovingSpawn b = new MovingSpawn(7);
		plain.add(b);
		b.x = 8;
		check("(why identity: a plain set loses a moved spawn)", plain.add(b));

		System.out.println(passed + " passed, " + failed + " failed");
		if (failed > 0)
		{
			System.exit(1);
		}
	}
}
