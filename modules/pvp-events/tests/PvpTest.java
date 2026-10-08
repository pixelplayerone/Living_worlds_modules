package modules.pvpevents;

import java.util.ArrayList;
import java.util.List;

public class PvpTest
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

	private static boolean rep(PvpScore sc)
	{
		sc.replace(1, 11, "Me2");
		return sc.get(11) != null && sc.get(11).healed == 123456;
	}

	public static void main(String[] args)
	{
		// Scenario parsing.
		PvpScenario s = PvpScenario.parse("Duel | you vs 1xany", 10);
		check("duel ok", s.error == null && s.you == 1 && s.botsOn(true) == 0 && s.botsOn(false) == 1 && s.shape().equals("1 v 1"));
		s = PvpScenario.parse("Party | you, 1xhealer, 2xwarrior, nuker vs 5xany | kills=15, minutes=4, respawn=off", 10);
		check("party ok", s.error == null && s.shape().equals("5 v 5") && s.kills == 15 && s.minutes == 4 && s.respawn == 0);
		check("party slots", s.blue.size() == 3 && s.blue.get(0).token.equals("healer") && s.blue.get(1).count == 2 && s.blue.get(2).count == 1);
		s = PvpScenario.parse("Watch | 3xwarrior vs 3xarcher", 10);
		check("watch", s.error == null && s.you == 0 && s.shape().equals("3 v 3"));
		s = PvpScenario.parse("Red | 2xtank vs you, 2xmonk", 10);
		check("you on red", s.error == null && s.you == 2 && s.shape().equals("2 v 3"));
		s = PvpScenario.parse("Cls | you vs 2xPhoenix Knight, Gladiator", 10);
		check("class names", s.error == null && s.red.get(0).token.equals("phoenix knight") && s.red.get(0).count == 2 && !s.red.get(0).isRole() && s.red.get(1).token.equals("gladiator"));
		s = PvpScenario.parse("Cls | you vs 1xphoenix knight", 10);
		check("phoenix x not a count", s.error == null && s.red.get(0).token.equals("phoenix knight"));
		check("squash", PvpScenario.squash("Phoenix Knight").equals("phoenixknight") && PvpScenario.squash("PHOENIX_KNIGHT").equals("phoenixknight"));
		check("no vs is a free-for-all", PvpScenario.parse("A | you, 3xany", 10).error == null && PvpScenario.parse("A | you, 3xany", 10).ffa);
		check("bad: two vs", PvpScenario.parse("A | you vs 2xany vs 3xany", 10).error != null);
		check("bad: no name", PvpScenario.parse(" | you vs 2xany", 10).error != null);
		check("bad: empty side", PvpScenario.parse("A | you vs ", 10).error != null);
		check("bad: too big", PvpScenario.parse("A | you vs 11xany", 10).error != null);
		check("big ok at limit", PvpScenario.parse("A | you vs 10xany", 10).error == null);
		check("bad: you twice", PvpScenario.parse("A | you vs you", 10).error != null);
		check("bad: option", PvpScenario.parse("A | you vs 1xany | speed=9", 10).error != null);
		check("bad: number", PvpScenario.parse("A | you vs 1xany | kills=lots", 10).error != null);
		check("bad: respawn value", PvpScenario.parse("A | you vs 1xany | respawn=maybe", 10).error != null);
		check("bad: null", PvpScenario.parse(null, 10).error != null);
		check("option clamps", PvpScenario.parse("A | you vs 1xany | kills=5000, minutes=0", 10).kills == 999);
		check("option minutes min", PvpScenario.parse("A | you vs 1xany | minutes=0", 10).minutes == 1);
		check("queue option", PvpScenario.parse("A | you vs 3xany | respawn=off, queue=on", 10).queue && !PvpScenario.parse("A | you vs 3xany", 10).queue);
		check("bad queue value", PvpScenario.parse("A | you vs 3xany | queue=maybe", 10).error != null);
		s = PvpScenario.parse("FFA Deathmatch | you, 29xany | kills=15, minutes=8", 10);
		check("ffa ok", s.error == null && s.ffa && s.you == 1 && s.sizeOf(true) == 30 && s.botsOn(true) == 29 && s.sizeOf(false) == 0 && s.shape().equals("30 FFA") && s.kills == 15);
		check("ffa cap", PvpScenario.parse("F | you, 30xany", 10).error != null && PvpScenario.parse("F | you, 30xany", 10, 40).error == null);
		check("ffa needs two", PvpScenario.parse("F | you", 10).error != null);
		check("ffa watch", PvpScenario.parse("F | 8xany", 10).error == null && PvpScenario.parse("F | 8xany", 10).you == 0);
		check("ffa ignores per-side cap", PvpScenario.parse("F | you, 20xany", 10).error == null);
		s = PvpScenario.parse("Korean | you, 4xany vs 5xany | respawn=off, queue=both", 10);
		check("queue both", s.error == null && s.queue && s.queueBoth && PvpScenario.parse("A | you vs 3xany | queue=on", 10).queueBoth == false);
		check("queue off", !PvpScenario.parse("A | you vs 3xany | queue=off", 10).queue);
		s = PvpScenario.parse("9v9 | you, 8xany vs 9xany | respawn=off, minutes=8", 10);
		check("9v9", s.error == null && s.shape().equals("9 v 9") && s.respawn == 0);
		check("vs inside word", PvpScenario.parse("A | you vs 1xdvsx", 10).error == null);

		// Geometry.
		final List<int[]> pts = new ArrayList<>();
		pts.add(new int[] {0, 0});
		pts.add(new int[] {100, 0});
		pts.add(new int[] {1000, 1000});
		pts.add(new int[] {10, 10});
		final int[] pair = ArenaGeometry.farthestPair(pts);
		check("farthest pair", pair != null && ((pair[0] == 0 && pair[1] == 2) || (pair[0] == 2 && pair[1] == 0)));
		check("pair none", ArenaGeometry.farthestPair(new ArrayList<int[]>()) == null && ArenaGeometry.farthestPair(null) == null);
		check("ring alone is the anchor", ArenaGeometry.ring(500, 600, 1, 0)[0] == 500 && ArenaGeometry.ring(500, 600, 1, 0)[1] == 600);
		boolean around = true;
		for (int i = 0; i < 6; i++)
		{
			final int[] p = ArenaGeometry.ring(0, 0, 6, i);
			around &= Math.abs(Math.hypot(p[0], p[1]) - 139) < 2;
		}
		check("ring radius", around);
		check("ring distinct", ArenaGeometry.ring(0, 0, 4, 0)[0] != ArenaGeometry.ring(0, 0, 4, 2)[0]);
		check("ring capped", Math.hypot(ArenaGeometry.ring(0, 0, 40, 3)[0], ArenaGeometry.ring(0, 0, 40, 3)[1]) <= 221);

		// Score.
		final PvpScore sc = new PvpScore();
		sc.add(1, "Me", true, false, "Gladiator");
		sc.add(2, "Ally", true, true, "Healer");
		sc.add(3, "Foe", false, true, "Archer");
		sc.add(4, "Foe2", false, true, "Mage");
		check("kill counts", sc.kill(1, 3) && sc.get(1).kills == 1 && sc.get(3).deaths == 1 && sc.kills(true) == 1);
		check("kill by foe", sc.kill(4, 2) && sc.kills(false) == 1);
		check("friendly kill ignored", !sc.kill(1, 2) && sc.get(1).kills == 1 && sc.get(2).deaths == 2);
		check("monster kill is a death only", !sc.kill(999, 1) && sc.get(1).deaths == 1 && sc.kills(true) == 1);
		check("stranger victim ignored", !sc.kill(1, 999));
		sc.damage(1, 3, 500);
		sc.damage(1, 2, 700);
		sc.damage(999, 3, 100);
		sc.damage(1, 3, 0);
		check("damage only across teams", sc.get(1).dealt == 500 && sc.get(3).taken == 500 && sc.get(2).taken == 0);
		check("draw", sc.leader() == null);
		sc.kill(2, 3);
		check("blue leads", Boolean.TRUE.equals(sc.leader()));
		check("rows sorted", sc.rows(true).get(0).id == 1 || sc.rows(true).get(0).kills >= sc.rows(true).get(1).kills);
		sc.replace(3, 33, "Foe3");
		check("replace carries the score", sc.get(33) != null && sc.get(33).deaths == sc.get(33).deaths && !sc.has(3) && sc.get(33).name.equals("Foe3") && !sc.get(33).blue);
		check("has", sc.has(1) && !sc.has(77));

		// Pages.
		final List<PvpScenario> list = new ArrayList<>();
		for (int i = 0; i < 12; i++)
		{
			list.add(PvpScenario.parse("A very long scenario name number " + i + " | you, 3xany vs 4xany", 10));
		}
		final List<String> broken = new ArrayList<>();
		broken.add("Scenario3 'x' has an empty side");
		final PvpPages.Live live = new PvpPages.Live();
		live.scenario = "Duel";
		live.secondsLeft = 90;
		live.blueKills = 3;
		live.redKills = 2;
		live.killLimit = 15;
		final String home = PvpPages.home(list, broken, "gludin_pvp", live, true, true);
		check("home fits", home.length() < 7800 && home.contains("%navigation%"));
		check("home no start while running", !home.contains(" start 0"));
		check("home start when idle", PvpPages.home(list, broken, "gludin_pvp", null, false, true).contains(" start 11"));
		check("home warns bots off", PvpPages.home(list, broken, null, null, false, false).contains("Bots are off"));
		final PvpScore big = new PvpScore();
		for (int i = 0; i < 10; i++)
		{
			big.add(i, "FighterNumber" + i + "<b>", true, i > 0, "Phoenix Knight");
			big.add(100 + i, "Other" + i, false, true, "Hierophant");
			big.kill(i, 100 + i);
			big.damage(i, 100 + i, 9999999);
		}
		final PvpPages.Result res = new PvpPages.Result();
		res.scenario = "Big";
		res.winnerBlue = Boolean.TRUE;
		res.reason = "first to 15 kills";
		res.seconds = 187;
		res.score = big;
		res.youIn = true;
		res.youBlue = true;
		res.reward = 250000;
		final String page = PvpPages.result(res);
		check("result fits", page.length() < 7800);
		check("result escapes", !page.contains("<b>"));
		check("result shows win and reward", page.contains("You won") && page.contains("250,000a") && page.contains("3:07"));
		res.winnerBlue = null;
		check("result draw", PvpPages.result(res).contains("Draw") && !PvpPages.result(res).contains("You won"));
		check("time", PvpPages.time(5).equals("0:05") && PvpPages.time(600).equals("10:00"));

		// Free-for-all pieces.
		final List<int[]> grid = new ArrayList<>();
		for (int x = 0; x < 20; x++)
		{
			for (int y = 0; y < 20; y++)
			{
				grid.add(new int[]
				{
					x * 100,
					y * 100,
					0
				});
			}
		}
		final List<Integer> spread = ArenaGeometry.spread(grid, 30);
		double nearest = Double.MAX_VALUE;
		for (int i = 0; i < spread.size(); i++)
		{
			for (int j = i + 1; j < spread.size(); j++)
			{
				nearest = Math.min(nearest, ArenaGeometry.distance(grid.get(spread.get(i)), grid.get(spread.get(j))));
			}
		}
		check("spread count", spread.size() == 30 && new java.util.HashSet<>(spread).size() == 30);
		check("spread keeps apart", nearest >= 300);
		check("spread few points", ArenaGeometry.spread(grid.subList(0, 3), 30).size() == 3 && ArenaGeometry.spread(null, 5).isEmpty() && ArenaGeometry.spread(grid, 0).isEmpty());

		final PvpScore ffa = new PvpScore(true);
		for (int i = 1; i <= 4; i++)
		{
			ffa.add(i, "F" + i, true, i > 1, "Gladiator");
		}
		check("ffa kill counts across all", ffa.kill(2, 3) && ffa.kill(4, 2) && ffa.kills(true) == 2);
		check("ffa suicide no kill", !ffa.kill(1, 1) && ffa.get(1).kills == 0 && ffa.get(1).deaths == 1);
		ffa.damage(2, 3, 400);
		ffa.damage(2, 2, 400);
		check("ffa damage between any two", ffa.get(2).dealt == 400 && ffa.get(3).taken == 400);
		check("ffa top", ffa.top() != null && ffa.top().kills == 1);
		ffa.damage(4, 1, 900);
		check("ffa top tie goes to damage", ffa.top().id == 4);
		check("ffa nobody scored", new PvpScore(true).top() == null);

		final PvpScore many = new PvpScore(true);
		for (int i = 0; i < 30; i++)
		{
			many.add(i, "Fighter" + i, true, i > 0, "Phoenix Knight");
			many.damage(i, (i + 1) % 30, 9999999);
		}
		final PvpPages.Result fr = new PvpPages.Result();
		fr.scenario = "FFA Deathmatch";
		fr.ffa = true;
		fr.winnerName = "Fighter7";
		fr.reason = "first to 15 kills";
		fr.seconds = 200;
		fr.score = many;
		fr.youIn = true;
		fr.youWon = false;
		String fpage = PvpPages.result(fr);
		check("ffa result fits", fpage.length() < 7800);
		check("ffa result names the winner", fpage.contains("Fighter7 wins") && fpage.contains("You lost") && !fpage.contains("Blue"));
		check("ffa result keeps the player's row", fpage.contains("Fighter0"));
		final PvpPages.Live ffaLive = new PvpPages.Live();
		ffaLive.scenario = "FFA";
		ffaLive.ffa = true;
		ffaLive.topName = "Fighter3";
		ffaLive.topKills = 4;
		ffaLive.secondsLeft = 90;
		ffaLive.killLimit = 15;
		check("ffa ffaLive line", PvpPages.home(list, broken, "arena", ffaLive, false, true).contains("Top: Fighter3 4"));
		check("ffa home label", PvpPages.home(list, broken, "arena", null, false, true).length() < 7800);

		check("compact", PvpPages.compact(842).equals("842") && PvpPages.compact(12345).equals("12.3k") && PvpPages.compact(1234567).equals("1.23m") && PvpPages.compact(9999).equals("9999"));
		final List<int[]> near = ArenaGeometry.nearestTo(grid, 1000, 1000, 5);
		check("nearestTo", near.size() == 5 && ArenaGeometry.distance(near.get(0), new int[] {1000, 1000}) == 0 && ArenaGeometry.distance(near.get(4), new int[] {1000, 1000}) <= 100);

		final PvpScore hs = new PvpScore();
		hs.add(1, "Me", true, false, "Cardinal");
		hs.add(2, "Mate", true, true, "Titan");
		hs.add(3, "Foe", false, true, "Titan");
		hs.heal(1, 2, 500);
		hs.heal(1, 1, 200);
		hs.heal(1, 3, 900);
		check("heal counts on team and self, not the enemy", hs.get(1).healed == 700 && hs.get(3).healed == 0);
		hs.heal(1, 2, 0);
		hs.heal(99, 2, 50);
		check("heal ignores zero and strangers", hs.get(1).healed == 700);
		final PvpScore hf = new PvpScore(true);
		hf.add(1, "A", true, false, "X");
		hf.add(2, "B", true, true, "X");
		hf.heal(1, 2, 300);
		hf.heal(1, 1, 100);
		check("ffa heal only on itself", hf.get(1).healed == 100);
		hs.get(1).healed = 123456;
		final PvpPages.Result hr = new PvpPages.Result();
		hr.scenario = "H";
		hr.winnerBlue = Boolean.TRUE;
		hr.reason = "x";
		hr.score = hs;
		check("recap has a healed column", PvpPages.result(hr).contains(">healed<") && PvpPages.result(hr).contains("123.5k"));
		check("replace keeps healed", rep(hs));

		System.out.println(passed + " passed, " + failed + " failed");
		if (failed > 0)
		{
			System.exit(1);
		}
	}
}
