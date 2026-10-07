/*
 * Copyright (c) 2013 L2jMobius
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package modules.hunting;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/** Builds the Community Board html for the hunting tab. Pure: all player facts arrive through {@link Env}. */
public final class HuntingPages
{
	public static final String CMD = "_bbs_hunting";
	public static final int RAID_PAGE_SIZE = 8;

	private static final String GOLD = "CDB67F";
	private static final String GREEN = "66FF66";
	private static final String GREY = "999999";
	private static final String ORANGE = "FFB266";

	/** A raid boss that exists in this world. */
	public static final class RaidInfo
	{
		public final int id;
		public final String name;
		public final int level;

		public RaidInfo(int id, String name, int level)
		{
			this.id = id;
			this.name = name;
			this.level = level;
		}
	}

	/** A grand boss: listed apart from the raid bosses. */
	public static final class GrandInfo
	{
		public final int id;
		public final String name;
		public final int level;
		public final int x;
		public final int y;

		GrandInfo(int id, String name, int level, int x, int y)
		{
			this.id = id;
			this.name = name;
			this.level = level;
			this.x = x;
			this.y = y;
		}
	}

	/** The open-world grand bosses, easiest first. Frintezza and the instance bosses are left out. */
	public static final List<GrandInfo> GRAND_BOSSES = java.util.Collections.unmodifiableList(java.util.Arrays.asList(new GrandInfo(29001, "Queen Ant", 40, -21610, 181594), new GrandInfo(29006, "Core", 50, 17726, 108915), new GrandInfo(29014, "Orfen", 50, 55024, 17368), new GrandInfo(29022, "Zaken", 60, 55312, 219168), new GrandInfo(29020, "Baium", 75, 116033, 17447), new GrandInfo(29019, "Antharas", 79, 185708, 114298), new GrandInfo(29028, "Valakas", 85, -105200, -253104)));

	/** @return the id a grand boss is listed under (Antharas has several forms), or 0 if this npc is not a listed grand boss */
	public static int grandId(int npcId)
	{
		final int id = ((npcId == 29066) || (npcId == 29067) || (npcId == 29068)) ? 29019 : npcId;
		for (GrandInfo g : GRAND_BOSSES)
		{
			if (g.id == id)
			{
				return id;
			}
		}
		return 0;
	}

	public static GrandInfo grandInfo(int id)
	{
		for (GrandInfo g : GRAND_BOSSES)
		{
			if (g.id == id)
			{
				return g;
			}
		}
		return null;
	}

	/** The player's saved contracts. */
	public interface Env
	{
		HuntingState state();
	}

	private final HuntingRules _rules;
	private final GroundIndex _grounds;
	private final ToIntFunction<String> _target;
	private final List<RaidInfo> _raids;
	private final NumberFormat _number = NumberFormat.getIntegerInstance(Locale.ROOT);

	public HuntingPages(HuntingRules rules, GroundIndex grounds, List<RaidInfo> raids)
	{
		_rules = rules;
		_grounds = grounds;
		_target = slug -> targetOf(rules, grounds, slug);
		_raids = new ArrayList<>(raids);
		_raids.sort((a, b) -> (a.level != b.level) ? Integer.compare(a.level, b.level) : a.name.compareToIgnoreCase(b.name));
	}

	public RaidInfo raid(int id)
	{
		for (RaidInfo r : _raids)
		{
			if (r.id == id)
			{
				return r;
			}
		}
		return null;
	}

	public int[] bandBossIds(int band)
	{
		final List<RaidInfo> list = raidsInBand(band);
		final int[] ids = new int[list.size()];
		for (int i = 0; i < ids.length; i++)
		{
			ids[i] = list.get(i).id;
		}
		return ids;
	}

	/** @return the amount for a reward column, or a dash when the adena rewards are switched off */
	private String money(int adena)
	{
		return _rules.paying() ? _number.format(adena) : "-";
	}

	public int bandReward(int band)
	{
		final List<RaidInfo> list = raidsInBand(band);
		final int[] levels = new int[list.size()];
		for (int i = 0; i < levels.length; i++)
		{
			levels[i] = list.get(i).level;
		}
		return _rules.raidBandReward(levels);
	}

	private List<RaidInfo> raidsInBand(int band)
	{
		final List<RaidInfo> out = new ArrayList<>();
		for (RaidInfo r : _raids)
		{
			if (HuntingRules.raidBandOf(r.level) == band)
			{
				out.add(r);
			}
		}
		return out;
	}

	// ===== pages =====

	public String home(Env env)
	{
		final HuntingState s = env.state();
		final StringBuilder b = new StringBuilder();
		b.append("<tr><td height=8></td></tr>");
		b.append("<tr><td align=center><font color=\"").append(GOLD).append("\">Go see the world. Your kills are tracked for you.</font></td></tr>");
		b.append("<tr><td height=8></td></tr>");
		b.append("<tr><td align=center>Extermination Contracts track your kills in every hunting ground.<br1>Bounties track every raid boss you slay.<br1>Grand Bosses have a list of their own. Claim each one once it is done.</td></tr>");
		b.append("<tr><td height=6></td></tr>");
		b.append("<tr><td align=center><font color=\"").append(GREY).append("\">Kills by your phantom party count for you.</font></td></tr>");
		b.append(buttonRow(btn("Extermination", CMD + " grounds"), btn("Bounties", CMD + " raids"), btn("Grand Bosses", CMD + " grand"), btn("My Progress", CMD + " mine")));
		final int ready = s.completeGroundContracts(_target) + s.readyRaidContracts();
		b.append("<tr><td align=center>Grounds finished: ").append(s.claimedGroundContracts()).append(" / ").append(_grounds.all().size()).append("   Bosses claimed: ").append(claimedBosses(s)).append(" / ").append(_raids.size()).append("   Grand bosses: ").append(claimedGrand(s)).append(" / ").append(GRAND_BOSSES.size());
		if (ready > 0)
		{
			b.append("   <font color=\"").append(GREEN).append("\">").append(ready).append(" ready to claim</font>");
		}
		b.append("</td></tr><tr><td height=6></td></tr>");
		b.append("<tr><td align=center><a action=\"bypass ").append(CMD).append(" clear\">Clear map marker</a></td></tr>");
		return frame("Hunting", b.toString());
	}

	/** @return the kills a ground's contract asks for, from its grade */
	public static int targetOf(HuntingRules rules, GroundIndex grounds, String slug)
	{
		final GroundIndex.Ground g = grounds.bySlug(slug);
		return rules.groundKills((g == null) ? 0 : GroundIndex.gradeOf(g));
	}

	public ToIntFunction<String> target()
	{
		return _target;
	}

	public java.util.List<String> bandGroundSlugs(int band)
	{
		final java.util.List<String> out = new ArrayList<>();
		for (GroundIndex.Ground g : _grounds.inGrade(band))
		{
			out.add(g.slug);
		}
		return out;
	}

	public int groundBandBonus(int band)
	{
		return _rules.groundBandReward(band);
	}

	private static String avg(GroundIndex.Ground g)
	{
		final double a = g.averageLevel();
		return (a == Math.rint(a)) ? Integer.toString((int) a) : Double.toString(a);
	}

	public GroundIndex.Ground ground(String slug)
	{
		return _grounds.bySlug(slug);
	}

	public String grounds(Env env)
	{
		final HuntingState s = env.state();
		final StringBuilder b = new StringBuilder("<tr><td height=6></td></tr><tr><td align=center><font color=\"").append(GOLD).append("\">Extermination Contracts</font></td></tr>");
		b.append("<tr><td align=center><font color=\"").append(GREY).append("\">Kill monsters inside a place and its progress fills in by itself.<br1>One reward per ground, once. Finish a whole range for a bonus.</font></td></tr><tr><td height=6></td></tr><tr><td align=center>");
		b.append("<table bgcolor=434343 width=400><tr><td width=150>Level range</td><td width=250 align=center>Done</td></tr></table><table width=400>");
		for (int band = 0; band < GroundIndex.GRADES; band++)
		{
			final List<GroundIndex.Ground> list = _grounds.inGrade(band);
			if (list.isEmpty())
			{
				continue;
			}
			int done = 0;
			int open = 0;
			for (GroundIndex.Ground g : list)
			{
				done += s.groundClaimed(g.slug) ? 1 : 0;
				open += s.hasGround(g.slug) ? 1 : 0;
			}
			b.append("<tr><td width=150><a action=\"bypass ").append(CMD).append(" gband ").append(band).append(" 1\">Level ").append(GroundIndex.gradeLabel(band)).append("</a></td>");
			b.append("<td width=250 align=center>").append(done).append(" / ").append(list.size()).append(open > 0 ? "   <font color=\"" + ORANGE + "\">" + open + " started</font>" : "").append(s.groundBandClaimed(band) ? "   <font color=\"" + GREY + "\">bonus paid</font>" : s.groundBandComplete(bandGroundSlugs(band)) ? "   <font color=\"" + GREEN + "\">bonus ready</font>" : "").append("</td></tr>");
		}
		b.append("</table></td></tr>").append(buttonRow(btn("Hunting", CMD)));
		return frame("Hunting", b.toString());
	}

	public String groundBand(int band, int page, Env env)
	{
		final HuntingState s = env.state();
		final List<GroundIndex.Ground> all = _grounds.inGrade(band);
		final int pages = Math.max(1, (all.size() + RAID_PAGE_SIZE - 1) / RAID_PAGE_SIZE);
		final int p = Math.max(1, Math.min(pages, page));
		final StringBuilder b = new StringBuilder("<tr><td height=6></td></tr><tr><td align=center><font color=\"").append(GOLD).append("\">Extermination Contracts, level ").append(GroundIndex.gradeLabel(band)).append("</font></td></tr><tr><td align=center><font color=\"").append(GREY).append("\">Kill ").append(_number.format(_rules.groundKills(band))).append(" monsters in each ground. Progress is tracked automatically.</font></td></tr><tr><td height=4></td></tr><tr><td align=center>");
		b.append("<table bgcolor=434343 width=470><tr><td width=170>Hunting ground</td><td width=45>Avg</td><td width=55>Levels</td><td width=90 align=right>Reward</td><td width=110 align=center>Progress</td></tr></table><table width=470>");
		for (GroundIndex.Ground g : all.subList((p - 1) * RAID_PAGE_SIZE, Math.min(all.size(), p * RAID_PAGE_SIZE)))
		{
			b.append("<tr><td width=170><a action=\"bypass ").append(CMD).append(" trace ").append(g.slug).append("\">").append(esc(g.name)).append("</a></td>");
			b.append("<td width=45>").append(avg(g)).append("</td><td width=55>").append(g.minLevel).append("-").append(g.maxLevel).append("</td>");
			b.append("<td width=90 align=right>").append(money(_rules.groundReward(GroundIndex.gradeOf(g)))).append("</td><td width=110 align=center>");
			if (s.groundClaimed(g.slug))
			{
				b.append("<font color=\"").append(GREY).append("\">Claimed</font>");
			}
			else if (s.groundComplete(g.slug, _target))
			{
				b.append("<font color=\"").append(GREEN).append("\">Complete</font><br1>").append(smallBtn("Claim", CMD + " claimg " + g.slug));
			}
			else
			{
				b.append("<font color=\"").append(s.hasGround(g.slug) ? ORANGE : GREY).append("\">").append(_number.format(s.groundProgress(g.slug))).append(" / ").append(_number.format(_target.applyAsInt(g.slug))).append("</font>");
			}
			b.append("</td></tr>");
		}
		b.append("</table></td></tr><tr><td height=6></td></tr><tr><td align=center>");
		b.append("Finish all ").append(all.size()).append(_rules.paying() ? " for a bonus of " + _number.format(groundBandBonus(band)) + " adena:  " : " to complete the range:  ");
		if (s.groundBandClaimed(band))
		{
			b.append("<font color=\"").append(GREY).append("\">Claimed</font>");
		}
		else if (s.groundBandComplete(bandGroundSlugs(band)))
		{
			b.append(smallBtn("Claim bonus", CMD + " claimgband " + band));
		}
		else
		{
			int done = 0;
			for (GroundIndex.Ground g : all)
			{
				done += s.groundClaimed(g.slug) ? 1 : 0;
			}
			b.append("<font color=\"").append(ORANGE).append("\">").append(done).append(" / ").append(all.size()).append("</font>");
		}
		b.append("</td></tr><tr><td height=6></td></tr><tr><td align=center>").append(pager(n -> CMD + " gband " + band + " " + n, p, pages)).append("</td></tr>");
		b.append("<tr><td align=center><font color=\"").append(GREY).append("\">Click a name to mark the place on your map.<br1>Avg = average level of the ground; easiest first.</font></td></tr>");
		b.append(buttonRow(btn("Level ranges", CMD + " grounds"), btn("My Progress", CMD + " mine")));
		return frame("Hunting", b.toString());
	}

	public String raids(Env env)
	{
		final HuntingState s = env.state();
		final StringBuilder b = new StringBuilder("<tr><td height=6></td></tr><tr><td align=center><font color=\"").append(GOLD).append("\">Bounties</font></td></tr>");
		b.append("<tr><td align=center><font color=\"").append(GREY).append("\">One reward per boss, once. Clear a whole range for a bonus.</font></td></tr><tr><td height=6></td></tr><tr><td align=center>");
		b.append("<table bgcolor=434343 width=400><tr><td width=150>Level range</td><td width=250 align=center>Done</td></tr></table><table width=400>");
		boolean any = false;
		for (int band = 0; band < HuntingRules.RAID_BANDS; band++)
		{
			final List<RaidInfo> list = raidsInBand(band);
			if (list.isEmpty())
			{
				continue;
			}
			any = true;
			int done = 0;
			for (RaidInfo r : list)
			{
				done += (s.raidState(r.id) == HuntingState.RAID_CLAIMED) ? 1 : 0;
			}
			b.append("<tr><td width=150><a action=\"bypass ").append(CMD).append(" rband ").append(band).append(" 1\">Level ").append(HuntingRules.raidBandLabel(band)).append("</a></td>");
			b.append("<td width=250 align=center>").append(done).append(" / ").append(list.size()).append(s.raidBandClaimed(band) ? "   <font color=\"" + GREY + "\">bonus paid</font>" : s.raidBandComplete(bandBossIds(band)) ? "   <font color=\"" + GREEN + "\">bonus ready</font>" : "").append("</td></tr>");
		}
		if (!any)
		{
			b.append("<tr><td>No raid bosses found in this world.</td></tr>");
		}
		b.append("</table></td></tr>").append(buttonRow(btn("Hunting", CMD)));
		return frame("Hunting", b.toString());
	}

	public String raidBand(int band, int page, Env env)
	{
		final HuntingState s = env.state();
		final List<RaidInfo> all = raidsInBand(band);
		final int pages = Math.max(1, (all.size() + RAID_PAGE_SIZE - 1) / RAID_PAGE_SIZE);
		final int p = Math.max(1, Math.min(pages, page));
		final StringBuilder b = new StringBuilder("<tr><td height=6></td></tr><tr><td align=center><font color=\"").append(GOLD).append("\">Level ").append(HuntingRules.raidBandLabel(band)).append(" bounties</font></td></tr><tr><td height=4></td></tr><tr><td align=center>");
		b.append("<table bgcolor=434343 width=470><tr><td width=40>Lvl</td><td width=200>Boss</td><td width=100 align=right>Reward</td><td width=130 align=center>Status</td></tr></table><table width=470>");
		for (RaidInfo r : all.subList((p - 1) * RAID_PAGE_SIZE, Math.min(all.size(), p * RAID_PAGE_SIZE)))
		{
			b.append("<tr><td width=40>").append(r.level).append("</td>");
			b.append("<td width=200><a action=\"bypass ").append(CMD).append(" trace ").append(r.id).append("\">").append(esc(r.name)).append("</a></td>");
			b.append("<td width=100 align=right>").append(money(_rules.raidReward(r.level))).append("</td><td width=130 align=center>");
			switch (s.raidState(r.id))
			{
				case HuntingState.RAID_READY:
					b.append("<font color=\"").append(GREEN).append("\">Slain</font><br1>").append(smallBtn("Claim", CMD + " claimr " + r.id));
					break;
				case HuntingState.RAID_CLAIMED:
					b.append("<font color=\"").append(GREY).append("\">Claimed</font>");
					break;
				default:
					b.append("<font color=\"").append(GREY).append("\">Not slain</font>");
			}
			b.append("</td></tr>");
		}
		b.append("</table></td></tr><tr><td height=6></td></tr><tr><td align=center>");
		b.append("Clear all ").append(all.size()).append(_rules.paying() ? " for a bonus of " + _number.format(bandReward(band)) + " adena:  " : " to complete the range:  ");
		if (s.raidBandClaimed(band))
		{
			b.append("<font color=\"").append(GREY).append("\">Claimed</font>");
		}
		else if (s.raidBandComplete(bandBossIds(band)))
		{
			b.append(smallBtn("Claim bonus", CMD + " claimband " + band));
		}
		else
		{
			b.append("<font color=\"").append(ORANGE).append("\">").append(doneIn(s, all)).append(" / ").append(all.size()).append("</font>");
		}
		b.append("</td></tr><tr><td height=6></td></tr><tr><td align=center>").append(pager(n -> CMD + " rband " + band + " " + n, p, pages)).append("</td></tr>");
		b.append("<tr><td align=center><font color=\"").append(GREY).append("\">click a boss name to mark it on your map</font></td></tr>");
		b.append(buttonRow(btn("Level ranges", CMD + " raids"), btn("My Progress", CMD + " mine")));
		return frame("Hunting", b.toString());
	}

	public String grand(Env env)
	{
		final HuntingState s = env.state();
		final StringBuilder b = new StringBuilder("<tr><td height=6></td></tr><tr><td align=center><font color=\"").append(GOLD).append("\">Grand Bosses</font></td></tr>");
		b.append("<tr><td align=center><font color=\"").append(GREY).append("\">The big ones, kept apart from the raid bosses. One reward each, once.<br1>Click a name to mark it on your map.</font></td></tr><tr><td height=6></td></tr><tr><td align=center>");
		b.append("<table bgcolor=434343 width=470><tr><td width=40>Lvl</td><td width=170>Boss</td><td width=120 align=right>Reward</td><td width=140 align=center>Status</td></tr></table><table width=470>");
		for (GrandInfo g : GRAND_BOSSES)
		{
			b.append("<tr><td width=40>").append(g.level).append("</td><td width=170><a action=\"bypass ").append(CMD).append(" trace g").append(g.id).append("\">").append(esc(g.name)).append("</a></td>");
			b.append("<td width=120 align=right>").append(money(_rules.grandReward(g.level))).append("</td><td width=140 align=center>");
			switch (s.raidState(g.id))
			{
				case HuntingState.RAID_READY:
					b.append("<font color=\"").append(GREEN).append("\">Slain</font><br1>").append(smallBtn("Claim", CMD + " claimgr " + g.id));
					break;
				case HuntingState.RAID_CLAIMED:
					b.append("<font color=\"").append(GREY).append("\">Claimed</font>");
					break;
				default:
					b.append("<font color=\"").append(GREY).append("\">Not slain</font>");
			}
			b.append("</td></tr>");
		}
		b.append("</table></td></tr>").append(buttonRow(btn("Hunting", CMD), btn("My Progress", CMD + " mine")));
		return frame("Hunting", b.toString());
	}

	public String mine(Env env)
	{
		final HuntingState s = env.state();
		final StringBuilder b = new StringBuilder("<tr><td height=6></td></tr><tr><td align=center><font color=\"").append(GOLD).append("\">My progress</font></td></tr><tr><td align=center><font color=\"").append(GREY).append("\">Hunting grounds you have started and bosses waiting to be claimed.</font></td></tr><tr><td height=4></td></tr><tr><td align=center><table width=470>");
		boolean any = false;
		for (int grade = 0; grade < GroundIndex.GRADES; grade++)
		{
			for (GroundIndex.Ground g : _grounds.inGrade(grade))
			{
				if (!s.hasGround(g.slug) || s.groundClaimed(g.slug))
				{
					continue;
				}
				any = true;
				b.append("<tr><td width=290><a action=\"bypass ").append(CMD).append(" trace ").append(g.slug).append("\">").append(esc(g.name)).append("</a> (Lv ").append(g.minLevel).append("-").append(g.maxLevel).append(")   ");
				if (s.groundComplete(g.slug, _target))
				{
					b.append("<font color=\"").append(GREEN).append("\">Complete</font></td><td width=180 align=center>").append(smallBtn("Claim", CMD + " claimg " + g.slug));
				}
				else
				{
					b.append("<font color=\"").append(ORANGE).append("\">").append(_number.format(s.groundProgress(g.slug))).append(" / ").append(_number.format(_target.applyAsInt(g.slug))).append("</font></td><td width=180></td>");
				}
				b.append("</td></tr>");
			}
		}
		for (RaidInfo r : _raids)
		{
			if (s.raidState(r.id) != HuntingState.RAID_READY)
			{
				continue;
			}
			any = true;
			b.append("<tr><td width=290><a action=\"bypass ").append(CMD).append(" trace ").append(r.id).append("\">").append(esc(r.name)).append("</a> (Lv ").append(r.level).append(")   <font color=\"").append(GREEN).append("\">Slain</font></td><td width=180 align=center>").append(smallBtn("Claim", CMD + " claimr " + r.id)).append("</td></tr>");
		}
		for (GrandInfo g : GRAND_BOSSES)
		{
			if (s.raidState(g.id) != HuntingState.RAID_READY)
			{
				continue;
			}
			any = true;
			b.append("<tr><td width=290><a action=\"bypass ").append(CMD).append(" trace g").append(g.id).append("\">").append(esc(g.name)).append("</a> (Lv ").append(g.level).append(", grand boss)   <font color=\"").append(GREEN).append("\">Slain</font></td><td width=180 align=center>").append(smallBtn("Claim", CMD + " claimgr " + g.id)).append("</td></tr>");
		}
		if (!any)
		{
			b.append("<tr><td align=center>Nothing yet. Go hunting and it will show up here.</td></tr>");
		}
		b.append("</table></td></tr>").append(buttonRow(btn("Extermination", CMD + " grounds"), btn("Bounties", CMD + " raids")));
		return frame("Hunting", b.toString());
	}

	public String notice(String message)
	{
		return frame("Hunting", "<tr><td height=30></td></tr><tr><td align=center>" + esc(message) + "</td></tr>" + buttonRow(btn("Hunting", CMD)));
	}

	// ===== pieces =====

	private static int claimedGrand(HuntingState s)
	{
		int n = 0;
		for (GrandInfo g : GRAND_BOSSES)
		{
			n += (s.raidState(g.id) == HuntingState.RAID_CLAIMED) ? 1 : 0;
		}
		return n;
	}

	private int claimedBosses(HuntingState s)
	{
		return doneIn(s, _raids);
	}

	private static int doneIn(HuntingState s, List<RaidInfo> list)
	{
		int n = 0;
		for (RaidInfo r : list)
		{
			n += (s.raidState(r.id) == HuntingState.RAID_CLAIMED) ? 1 : 0;
		}
		return n;
	}

	private static String pager(IntFunction<String> cmd, int page, int pages)
	{
		if (pages <= 1)
		{
			return "";
		}
		final StringBuilder b = new StringBuilder();
		b.append(page > 1 ? "<a action=\"bypass " + cmd.apply(page - 1) + "\">&lt;&lt; prev</a>" : "<font color=\"" + GREY + "\">&lt;&lt; prev</font>");
		b.append("    Page ").append(page).append(" / ").append(pages).append("    ");
		b.append(page < pages ? "<a action=\"bypass " + cmd.apply(page + 1) + "\">next &gt;&gt;</a>" : "<font color=\"" + GREY + "\">next &gt;&gt;</font>");
		return b.toString();
	}

	private static String btn(String label, String bypass)
	{
		return "<button value=\"" + label + "\" action=\"bypass " + bypass + "\" width=110 height=26 back=\"L2UI_CH3.Button.bigbutton2_down\" fore=\"L2UI_CH3.Button.bigbutton2\">";
	}

	private static String smallBtn(String label, String bypass)
	{
		return "<button value=\"" + label + "\" action=\"bypass " + bypass + "\" width=80 height=22 back=\"L2UI_CH3.Button.bigbutton2_down\" fore=\"L2UI_CH3.Button.bigbutton2\">";
	}

	private static String buttonRow(String... buttons)
	{
		final StringBuilder b = new StringBuilder("<tr><td height=10></td></tr><tr><td align=center><table><tr>");
		for (String button : buttons)
		{
			b.append("<td width=120 align=center>").append(button).append("</td>");
		}
		return b.append("</tr></table></td></tr><tr><td height=10></td></tr>").toString();
	}

	/** Escapes the characters the client's html parser treats as markup. */
	static String esc(String text)
	{
		if (text == null)
		{
			return "";
		}
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	private static String frame(String title, String body)
	{
		return "<html><body><table width=500><tr><td height=10></td></tr></table>"
			+ "<table width=10><tr><td>%navigation%</td><td><center>"
			+ "<table border=0 bgcolor=\"000000\" cellpadding=0 cellspacing=0 width=500 height=415>"
			+ "<tr><td height=15></td></tr><tr><td height=25 align=\"center\"><font color=\"" + GOLD + "\">" + title + "</font></td></tr>"
			+ "<tr><td><center><img src=\"L2UI.SquareGray\" width=500 height=1></center></td></tr>"
			+ body
			+ "<tr><td height=14></td></tr></table>"
			+ "<table border=0 bgcolor=\"000000\" cellpadding=0 cellspacing=0 width=500><tr><td height=20 align=center><font color=696969>LINEAGE II - COMMUNITY BOARD</font></td></tr></table>"
			+ "</center></td></tr></table></body></html>";
	}
}
