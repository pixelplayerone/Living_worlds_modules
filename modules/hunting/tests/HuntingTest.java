package modules.hunting;

import java.util.Arrays;

/** Standalone checks for the hunting tab: no server needed. Run: see TESTING.md. */
public final class HuntingTest
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

	public static void main(String[] args)
	{
		final HuntingRules r = new HuntingRules("100,250,350,500,600,750,1000", "100000,250000,500000,1000000,2000000,3000000,5000000", "1000000,2500000,5000000,10000000,15000000,30000000,40000000", 400.0, 0.5, 2000.0);
		check("raid ranges", (HuntingRules.RAID_BANDS == 16) && (HuntingRules.raidBandOf(19) == 0) && (HuntingRules.raidBandOf(20) == 0) && (HuntingRules.raidBandOf(24) == 0) && (HuntingRules.raidBandOf(25) == 1) && (HuntingRules.raidBandOf(39) == 3) && (HuntingRules.raidBandOf(40) == 4) && (HuntingRules.raidBandOf(79) == 11) && (HuntingRules.raidBandOf(80) == 12) && (HuntingRules.raidBandOf(81) == 12) && (HuntingRules.raidBandOf(82) == 13) && (HuntingRules.raidBandOf(84) == 13) && (HuntingRules.raidBandOf(85) == 14) && (HuntingRules.raidBandOf(86) == 14) && (HuntingRules.raidBandOf(87) == 15) && (HuntingRules.raidBandOf(90) == 15));
		check("raid range labels", HuntingRules.raidBandLabel(0).equals("20-24") && HuntingRules.raidBandLabel(1).equals("25-29") && HuntingRules.raidBandLabel(11).equals("75-79") && HuntingRules.raidBandLabel(12).equals("80-81") && HuntingRules.raidBandLabel(13).equals("82-84") && HuntingRules.raidBandLabel(14).equals("85-86") && HuntingRules.raidBandLabel(15).equals("87+"));
		check("ground rewards by grade", (r.groundReward(0) == 100000) && (r.groundReward(1) == 250000) && (r.groundReward(2) == 500000) && (r.groundReward(3) == 1000000) && (r.groundReward(4) == 2000000) && (r.groundReward(5) == 3000000) && (r.groundReward(6) == 5000000));
		check("grade past the list pays the last", (r.groundReward(9) == 5000000) && (r.groundReward(-1) == 100000));
		check("bad reward config falls back", new HuntingRules("x", "x", "x", 400.0, 0.5, 2000.0).groundReward(6) == 5000000);
		check("kills by grade", (r.groundKills(0) == 100) && (r.groundKills(1) == 250) && (r.groundKills(2) == 350) && (r.groundKills(3) == 500) && (r.groundKills(4) == 600) && (r.groundKills(5) == 750) && (r.groundKills(6) == 1000) && (r.groundKills(8) == 1000));
		check("section bonuses by grade", (r.groundBandReward(0) == 1000000) && (r.groundBandReward(1) == 2500000) && (r.groundBandReward(2) == 5000000) && (r.groundBandReward(3) == 10000000) && (r.groundBandReward(4) == 15000000) && (r.groundBandReward(5) == 30000000) && (r.groundBandReward(6) == 40000000) && (r.groundBandReward(9) == 40000000));
		check("bad section config falls back", new HuntingRules("x", "x", "x", 400.0, 0.5, 2000.0).groundBandReward(6) == 40000000);
		check("bad kills config falls back", new HuntingRules("x", "x", "x", 400.0, 0.5, 2000.0).groundKills(6) == 1000);
		check("raid reward", r.raidReward(50) == 1000000);
		check("band reward is half the bosses' pay", r.raidBandReward(new int[] { 23, 27 }) == Math.round(((23 * 23) + (27 * 27)) * 400.0 * 0.5));

		final GroundIndex idx = GroundIndex.fromRows(GroundData.ROWS);
		check("grounds loaded", idx.all().size() >= 60);
		boolean sane = true;
		for (GroundIndex.Ground g : idx.all())
		{
			sane &= (g.minLevel >= 1) && (g.maxLevel >= g.minLevel) && !g.slug.contains(".") && !g.slug.contains(",") && g.slug.matches("[a-z]+") && !g.name.contains("Quest");
		}
		check("every ground is sane", sane);
		check("slugs unique", idx.all().stream().map(g -> g.slug).distinct().count() == idx.all().size());
		final GroundIndex.Ground agony = idx.bySlug("nosuchplace");
		check("lookup by slug", (agony == null) && (idx.bySlug("crumamarshlands") != null));
		final GroundIndex.Ground any = idx.all().get(10);
		check("ground contains its own centre, not far away", any.contains(any.centerX(), any.centerY()) && !any.contains(any.centerX() + 900000, any.centerY()));
		check("at() finds the ground", idx.at(any.centerX(), any.centerY()).contains(any));
		check("every ground sits in a grade", idx.inGrade(GroundIndex.gradeOf(any)).contains(any));

		check("kill targets follow the grade", (HuntingPages.targetOf(r, idx, "forgeofgods") == 750) && (HuntingPages.targetOf(r, idx, "primevalisle") == 1000) && (HuntingPages.targetOf(r, idx, "talkingisland") == 100) && (HuntingPages.targetOf(r, idx, "crumamarshlands") == 250) && (HuntingPages.targetOf(r, idx, "giantscave") == 500) && (HuntingPages.targetOf(r, idx, "tanorcanyon") == 350) && (HuntingPages.targetOf(r, idx, "wallofargos") == 600));
		final String slug = any.slug;
		final java.util.function.ToIntFunction<String> tg = sl -> HuntingPages.targetOf(r, idx, sl);
		final int need = tg.applyAsInt(slug);
		final HuntingState s = new HuntingState();
		check("nothing to accept: a fresh player has no progress", !s.hasGround(slug) && (s.groundProgress(slug) == 0));
		check("first kill starts it by itself", s.wantsKillIn(slug, tg) && !s.addKill(slug, tg) && s.hasGround(slug) && (s.groundProgress(slug) == 1));
		for (int i = 0; i < (need - 2); i++)
		{
			s.addKill(slug, tg);
		}
		check("one short is not complete", !s.groundComplete(slug, tg) && !s.claimGround(slug, tg));
		check("last kill completes", s.addKill(slug, tg) && s.groundComplete(slug, tg));
		check("no progress past target", !s.addKill(slug, tg) && (s.groundProgress(slug) == need) && !s.wantsKillIn(slug, tg));
		check("claim closes it for good", s.claimGround(slug, tg) && s.groundClaimed(slug) && !s.hasGround(slug) && !s.wantsKillIn(slug, tg) && !s.addKill(slug, tg) && !s.claimGround(slug, tg));
		final HuntingState t = HuntingState.parse(s.groundsText(), s.raidsText());
		check("claimed survives save and load", t.groundClaimed(slug) && t.groundsText().equals(s.groundsText()));
		s.addKill("alpha", tg);
		s.addKill("beta", tg);
		final HuntingState t2 = HuntingState.parse(s.groundsText(), "");
		check("progress survives save and load", t2.hasGround("alpha") && (t2.groundProgress("alpha") == 1) && t2.hasGround("beta") && t2.groundClaimed(slug));
		final HuntingState dmg = HuntingState.parse("a.5,junk,,b.C,.3", "");
		check("damaged entries skipped", dmg.hasGround("a") && dmg.groundClaimed("b") && !dmg.hasGround("junk"));
		final HuntingState two = new HuntingState();
		two.addKill("a", tg);
		two.addKill("b", tg);
		check("grounds track side by side", (two.groundProgress("a") == 1) && (two.groundProgress("b") == 1));

		check("grand boss reward", (r.grandReward(40) == 3200000) && (r.grandReward(85) == 14450000));
		check("grand list", (HuntingPages.GRAND_BOSSES.size() == 7) && (HuntingPages.grandId(29019) == 29019) && (HuntingPages.grandId(29066) == 29019) && (HuntingPages.grandId(29068) == 29019) && (HuntingPages.grandId(29028) == 29028) && (HuntingPages.grandId(29045) == 0) && (HuntingPages.grandId(25001) == 0));
		final HuntingState q = new HuntingState();
		check("a boss you never killed is not slain", (q.raidState(25001) == 'N') && !q.claimRaid(25001));
		check("kill marks it slain by itself", q.raidKilled(25001) && (q.raidState(25001) == 'R'));
		check("a second kill changes nothing", !q.raidKilled(25001) && (q.raidState(25001) == 'R'));
		check("raid claim once", q.claimRaid(25001) && !q.claimRaid(25001));
		check("claimed raid never returns", !q.raidKilled(25001) && (q.raidState(25001) == 'C'));
		q.raidKilled(7);
		final HuntingState q2 = HuntingState.parse("", q.raidsText());
		check("raids survive save and load", (q2.raidState(25001) == 'C') && (q2.raidState(7) == 'R') && q2.raidsText().equals(q.raidsText()));
		check("ready count", q2.readyRaidContracts() == 1);

		check("band reward is half the bosses' pay", r.raidBandReward(new int[] { 23, 27 }) == Math.round((23 * 23 + 27 * 27) * 400.0 * 0.5));
		final HuntingState bs = new HuntingState();
		final int[] ids = { 1, 2 };
		check("empty band never complete", !bs.raidBandComplete(new int[0]) && !bs.claimRaidBand(2, new int[0]));
		bs.raidKilled(1);
		bs.claimRaid(1);
		check("half cleared: no bonus", !bs.raidBandComplete(ids) && !bs.claimRaidBand(2, ids));
		bs.raidKilled(2);
		check("slain but unclaimed: no bonus", !bs.claimRaidBand(2, ids));
		bs.claimRaid(2);
		check("all claimed: bonus once", bs.raidBandComplete(ids) && bs.claimRaidBand(2, ids) && !bs.claimRaidBand(2, ids));
		final HuntingState bs2 = HuntingState.parse("", bs.raidsText());
		check("bonus survives save and load", bs2.raidBandClaimed(2) && !bs2.claimRaidBand(2, ids) && (bs2.raidState(1) == 'C') && bs2.raidsText().equals(bs.raidsText()));

		final HuntingPages pages = new HuntingPages(r, idx, Arrays.asList(new HuntingPages.RaidInfo(1, "Boss <A>", 21), new HuntingPages.RaidInfo(2, "Boss B", 24), new HuntingPages.RaidInfo(3, "Boss C", 55)));
		final HuntingState e = new HuntingState();
		e.addKill(any.slug, tg);
		final HuntingPages.Env env = () -> e;
		final int gb = GroundIndex.gradeOf(any);
		boolean small = true;
		for (String h : new String[] { pages.home(env), pages.grounds(env), pages.groundBand(gb, 1, env), pages.raids(env), pages.raidBand(0, 1, env), pages.mine(env), pages.notice("x") })
		{
			small &= h.length() < 16000;
		}
		check("pages under the packet limit", small);
		boolean allBands = true;
		for (int band = 0; band < HuntingRules.RAID_BANDS; band++)
		{
			for (int pg = 1; pg <= 3; pg++)
			{
				allBands &= pages.groundBand(band % GroundIndex.GRADES, pg, env).length() < 16000;
				allBands &= pages.raidBand(band, pg, env).length() < 16000;
			}
		}
		check("every ground and boss page under the limit", allBands);
		check("ground shows its progress and no buttons to accept", pages.groundBand(gb, 1, env).contains("1 / " + HuntingPages.targetOf(r, idx, any.slug)) && !pages.groundBand(gb, 1, env).contains("acceptg") && !pages.groundBand(gb, 1, env).contains("abandong") && !pages.raidBand(0, 1, env).contains("acceptr"));
		check("my progress lists a started ground", pages.mine(env).contains(any.name.replace("&", "&amp;")) && pages.mine(env).contains("My progress"));
		check("names are linked to the map", pages.groundBand(gb, 1, env).contains("trace " + any.slug));
		check("raid name escaped", pages.raidBand(0, 1, env).contains("Boss &lt;A&gt;") && !pages.raidBand(0, 1, env).contains("Boss <A>"));
		check("raids sorted by level in band", pages.raidBand(0, 1, env).indexOf("Boss &lt;A") < pages.raidBand(0, 1, env).indexOf("Boss B"));
		check("section bonus shown", pages.raidBand(0, 1, env).contains("for a bonus of"));
		final HuntingState full = new HuntingState();
		for (int id : new int[] { 1, 2 })
		{
			full.raidKilled(id);
			full.claimRaid(id);
		}
		check("claim bonus button when band cleared", pages.raidBand(0, 1, () -> full).contains("claimband 0") && pages.raids(() -> full).contains("bonus ready"));
		final java.util.List<String> bandSlugs = new java.util.ArrayList<>();
		for (GroundIndex.Ground g : idx.inGrade(0))
		{
			bandSlugs.add(g.slug);
		}

		final HuntingState gb0 = new HuntingState();
		check("empty band never complete", !gb0.groundBandComplete(new java.util.ArrayList<String>()));
		check("not all claimed: no bonus", !gb0.claimGroundBand(0, bandSlugs));
		for (String sl : bandSlugs)
		{
			for (int i = 0; i < tg.applyAsInt(sl); i++)
			{
				gb0.addKill(sl, tg);
			}
			if (sl != bandSlugs.get(bandSlugs.size() - 1))
			{
				gb0.claimGround(sl, tg);
			}
		}
		check("one still unclaimed: no bonus", !gb0.claimGroundBand(0, bandSlugs));
		gb0.claimGround(bandSlugs.get(bandSlugs.size() - 1), tg);
		check("all claimed: bonus once", gb0.groundBandComplete(bandSlugs) && gb0.claimGroundBand(0, bandSlugs) && !gb0.claimGroundBand(0, bandSlugs));
		final HuntingState gb1 = HuntingState.parse(gb0.groundsText(), "");
		check("ground bonus survives save and load", gb1.groundBandClaimed(0) && !gb1.claimGroundBand(0, bandSlugs) && gb1.groundsText().equals(gb0.groundsText()));
		check("band page offers the claim", pages.groundBand(0, 1, () -> gb1).contains("Claimed") && pages.groundBand(0, 1, () -> gb0).length() > 0);
		final HuntingState gb2 = HuntingState.parse(gb0.groundsText().replaceAll(",B0\\.C", ""), "");
		check("claim bonus button when band cleared", pages.groundBand(0, 1, () -> gb2).contains("claimgband 0") && pages.grounds(() -> gb2).contains("bonus ready"));
		check("seven grades", (GroundIndex.GRADES == 7) && GroundIndex.gradeLabel(0).equals("1-19") && GroundIndex.gradeLabel(2).equals("40-51") && GroundIndex.gradeLabel(5).equals("76-79") && GroundIndex.gradeLabel(6).equals("80+"));
		int total = 0;
		for (int gr = 0; gr < GroundIndex.GRADES; gr++)
		{
			total += idx.inGrade(gr).size();
		}
		check("every ground is in exactly one grade", total == idx.all().size());
		boolean ordered = true;
		for (int gr = 0; gr < GroundIndex.GRADES; gr++)
		{
			double last = 0;
			for (GroundIndex.Ground x : idx.inGrade(gr))
			{
				ordered &= x.averageLevel() >= last;
				last = x.averageLevel();
				ordered &= (x.averageLevel() >= GroundIndex.GRADE_MIN[gr]) && ((gr == (GroundIndex.GRADES - 1)) || (x.averageLevel() < GroundIndex.GRADE_MIN[gr + 1]));
			}
		}
		check("grades ordered easiest first and bounded by average level", ordered);
		check("known grounds land in the right grade", (GroundIndex.gradeOf(idx.bySlug("talkingisland")) == 0) && (GroundIndex.gradeOf(idx.bySlug("crumamarshlands")) == 1) && (GroundIndex.gradeOf(idx.bySlug("seaofspores")) == 2) && (GroundIndex.gradeOf(idx.bySlug("dragonvalley")) == 3) && (GroundIndex.gradeOf(idx.bySlug("wallofargos")) == 4) && (GroundIndex.gradeOf(idx.bySlug("forgeofgods")) == 5) && (GroundIndex.gradeOf(idx.bySlug("primevalisle")) == 6) && (GroundIndex.gradeOf(idx.bySlug("chapelguards")) == 6) && (GroundIndex.gradeOf(idx.bySlug("monasteryofsilence")) == 6) && (idx.inGrade(6).size() == 3) && (idx.inGrade(5).size() == 8));
		check("page shows the average level", pages.groundBand(1, 1, env).contains("Avg") && pages.groundBand(0, 1, env).contains("<td width=45>6.5</td>"));
		final HuntingState gr = new HuntingState();
		check("grand boss slain by itself and claimed once", gr.raidKilled(29019) && (gr.raidState(29019) == 'R') && gr.claimRaid(29019) && !gr.claimRaid(29019) && !gr.raidKilled(29019));
		final HuntingState gr2 = HuntingState.parse("", gr.raidsText());
		check("grand claim survives save and load", gr2.raidState(29019) == 'C');
		gr2.raidKilled(29028);
		final String gp = pages.grand(() -> gr2);
		check("grand page lists bosses and states", gp.contains("Queen Ant") && gp.contains("Valakas") && gp.contains("Claimed") && gp.contains("claimgr 29028") && gp.contains("trace g29019") && gp.length() < 16000);
		check("grand bosses are not in the raid lists", !pages.raids(env).contains("Valakas") && !pages.raidBand(7, 1, env).contains("Baium"));
		check("my progress shows a slain grand boss", pages.mine(() -> gr2).contains("Valakas"));
		boolean fits = true;
		final java.util.List<String> allPages = new java.util.ArrayList<>(Arrays.asList(pages.home(env), pages.grounds(env), pages.raids(env), pages.grand(env), pages.mine(env), pages.notice("x")));
		for (int pg = 0; pg < GroundIndex.GRADES; pg++)
		{
			allPages.add(pages.groundBand(pg, 1, env));
		}
		for (int pg = 0; pg < HuntingRules.RAID_BANDS; pg++)
		{
			allPages.add(pages.raidBand(pg, 1, env));
		}
		for (String h : allPages)
		{
			for (String line : h.replaceAll("<[^>]*>", "\n").split("\n"))
			{
				if (line.trim().length() > 78)
				{
					fits = false;
					System.out.println("too wide for the board: " + line.trim());
				}
			}
		}
		check("no text line is wider than the board", fits);
		check("labels", pages.home(env).contains("Extermination") && pages.home(env).contains("Bounties"));
		check("notice escaped", pages.notice("hi <b>").contains("hi &lt;b&gt;"));

		// Adena rewards switched off: nothing is owed, the amounts are hidden, the contracts still work.
		final HuntingRules off = new HuntingRules("100,250,350,500,600,750,1000", "100000,250000,500000,1000000,2000000,3000000,5000000", "1000000,2500000,5000000,10000000,15000000,30000000,40000000", 400.0, 0.5, 2000.0, false);
		check("rewards off pay nothing", !off.paying() && (off.groundReward(3) == 0) && (off.groundBandReward(3) == 0) && (off.raidReward(50) == 0) && (off.grandReward(80) == 0) && (off.raidBandReward(new int[] { 23, 27 }) == 0));
		check("rewards on by default", r.paying() && (r.groundReward(0) == 100000));
		check("rewards off keep the kill targets", off.groundKills(6) == 1000);

		{
			// A kill and a claim for one player share a lock, so neither saves an older copy over the other.
			check("same player, same lock", PlayerLocks.of(268435456 + 7) == PlayerLocks.of(268435456 + 7));
			final String raceSlug = "testground";
			final java.util.function.ToIntFunction<String> goal = x -> 4000;
			final String[] store = { "", "" };
			final Object lock = PlayerLocks.of(12345);
			final int[] claims = { 0 };
			final java.util.concurrent.atomic.AtomicInteger counted = new java.util.concurrent.atomic.AtomicInteger();
			final java.util.List<Thread> threads = new java.util.ArrayList<>();
			for (int n = 0; n < 4; n++)
			{
				threads.add(new Thread(() ->
				{
					for (int i = 0; i < 1000; i++)
					{
						synchronized (lock)
						{
							final HuntingState st = HuntingState.parse(store[0], store[1]);
							if (st.wantsKillIn(raceSlug, goal))
							{
								st.addKill(raceSlug, goal);
								counted.incrementAndGet();
							}
							store[0] = st.groundsText();
							store[1] = st.raidsText();
						}
					}
				}));
			}
			threads.add(new Thread(() ->
			{
				for (int i = 0; i < 200000; i++)
				{
					synchronized (lock)
					{
						final HuntingState st = HuntingState.parse(store[0], store[1]);
						if (st.claimGround(raceSlug, goal))
						{
							claims[0]++;
						}
						store[0] = st.groundsText();
						store[1] = st.raidsText();
					}
				}
			}));
			for (Thread th : threads)
			{
				th.start();
			}
			for (Thread th : threads)
			{
				try
				{
					th.join();
				}
				catch (InterruptedException ie)
				{
					Thread.currentThread().interrupt();
				}
			}
			final HuntingState done = HuntingState.parse(store[0], store[1]);
			check("every kill is counted, none lost", counted.get() == 4000);
			check("a contract is claimed exactly once under load", (claims[0] == 1) && done.groundClaimed(raceSlug) && !done.hasGround(raceSlug));

		}

		System.out.println(passed + " passed, " + failed + " failed");
		if (failed > 0)
		{
			System.exit(1);
		}
	}
}
