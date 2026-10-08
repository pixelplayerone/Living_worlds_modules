/*
 * TameCollarView.java
 *
 * Server-backed PROFILE / SKILLS / HISTORY pages for one validated collar.
 */
package taming;

import org.l2jmobius.gameserver.data.holders.PetData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.data.xml.PetDataTable;
import org.l2jmobius.gameserver.handler.IBypassHandler;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;


public class TameCollarView implements IBypassHandler
{
	private static final String[] COMMANDS =
	{
"tamecollar_view",
		"tamecollar_awaken",
				"tamecollar_equipment",
					"tamecollar_command"
		};

	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}

	@Override
	public boolean onCommand(String command, Player player, Creature bypassOrigin)
	{
		if (player == null)
		{
			return false;
		}
		final String[] parts = command.trim().split("\\s+");
		try
		{
			if ("tamecollar_view".equalsIgnoreCase(parts[0]))
			{
				final Item collar = getCollar(player, parts, 1);
				if (collar == null)
				{
					return false;
				}
				final String view = parts.length > 2 ? parts[2] : "profile";
				if ("skills".equalsIgnoreCase(view))
				{
					return showSkills(player, collar);
				}
					if ("history".equalsIgnoreCase(view))
					{
						return showHistory(player, collar);
					}
					if ("awakening".equalsIgnoreCase(view))
					{
						return showAwakening(player, collar);
					}
						if ("equipment".equalsIgnoreCase(view))
						{
							return showEquipment(player, collar);
						}
					if ("commands".equalsIgnoreCase(view))
					{
						return showCommands(player, collar);
					}
					if ("identity".equalsIgnoreCase(view) || "imprint".equalsIgnoreCase(view) || "affinity".equalsIgnoreCase(view))
					{
						return showIdentity(player, collar);
					}
					return TameProfileCommand.openFromCollar(player, collar);
			}
if ("tamecollar_awaken".equalsIgnoreCase(parts[0]) && (parts.length >= 3))
					{
						final Item collar = getCollar(player, parts, 1);
						if (collar == null)
						{
							return false;
						}
						return awaken(player, collar, parts[2]);
					}
						if ("tamecollar_equipment".equalsIgnoreCase(parts[0]) && (parts.length >= 3))
						{
							final Item collar = getCollar(player, parts, 1);
							if (collar == null)
							{
								return false;
							}
							return equipmentAction(player, collar, parts);
						}
						if ("tamecollar_command".equalsIgnoreCase(parts[0]) && (parts.length >= 3))
						{
							final Item collar = getCollar(player, parts, 1);
							if (collar == null)
							{
								return false;
							}
					TameCommandManager.execute(player, collar, parts[2]);
					// Back to the technique panel rather than re-rendering the command
					// list: the command already reported itself in chat, and leaving the
					// player on a page they have to navigate out of is what made the
					// window feel like it kept vanishing.
					return showSkills(player, collar);
						}

		}
		catch (NumberFormatException e)
		{
			player.sendMessage("Invalid collar skill reference.");
		}
		return false;
	}

	private static Item getCollar(Player player, String[] parts, int index)
	{
		if (parts.length <= index)
		{
			player.sendMessage("Invalid collar reference.");
			return null;
		}
		final Item collar = player.getInventory().getItemByObjectId(Integer.parseInt(parts[index]));
		if ((collar == null) || (collar.getId() != TamingManager.getCollarItemId()) || (TameProfileRepository.load(player.getObjectId(), collar.getObjectId()) == null))
		{
			player.sendMessage("That collar is not in your inventory or has no tame profile.");
			return null;
		}
		return collar;
	}

	public static boolean openSkills(Player player, Item collar)
		{
			return showSkills(player, collar);
		}

	/**
	 * The technique page, and the one place its shape is decided.
	 *
	 * <p>One page, always the full deck. A compact/expanded toggle was tried and
	 * removed again: the compact form hid the technique names behind an "Expand"
	 * button, which was the wrong trade when the whole reason this window exists is
	 * that it is the only surface where a technique's real name, its state and a
	 * button to fire it are all visible at once. There is no second floating window
	 * either - NpcHtmlMessage is the only HTML packet the client has - so a minimised
	 * mode could never have been anything but a smaller copy of this page.
	 *
	 * <p>The window is draggable by its header and casting never re-sends this page,
	 * so it stays where the player put it.
	 */

		private static boolean awaken(Player player, Item collar, String path)
		{
			// Refresh the stored level from the live summon first, otherwise a beast that
			// levelled while already out is still judged against the level recorded at
			// its last summon and would be refused for no reason.
			final Summon livePet = player.getSummon();
			if (livePet != null && livePet.isPet() && livePet.getControlObjectId() == collar.getObjectId())
			{
				TameProfileRepository.syncLevel(player.getObjectId(), collar.getObjectId(), livePet.getLevel());
			}
			final TameProfile profile = TameProfileRepository.load(player.getObjectId(), collar.getObjectId());
			if (profile == null)
			{
				return false;
			}
			ensureSpeciesSkills(profile, collar);
			// SECOND is the second awakening, not a first-awakening path. It is
			// dispatched before the level-30 check because that gate belongs to the
			// first awakening and this one is gated much later.
			if ("SECOND".equals(path))
			{
				if (profile.getAwakeningStage() != 1)
				{
					player.sendMessage("This tame cannot awaken a second time yet.");
					return showAwakening(player, collar);
				}
				final int required = TameAwakenedProc.getLevel();
				if (profile.getCurrentLevel() < required)
				{
					player.sendMessage("This tame must reach level " + required + " before awakening again.");
					return showAwakening(player, collar);
				}
				if (TameProfileRepository.awakenSecond(player.getObjectId(), collar.getObjectId(), required))
				{
					TameProfileRepository.logEvent(profile.getUuid(), "AWAKEN", "path=" + String.valueOf(profile.getAwakeningPath()) + " stage=2");
					final TameProfile awakened = TameProfileRepository.load(player.getObjectId(), collar.getObjectId());
					// Named rather than left for the player to find on the skills page: the
					// second awakening grants a technique as well as the proc, and a grant
					// nobody is told about reads as the button having done nothing.
					player.sendMessage("Your tame awakened a second time. Its hits can now inflict " + TameAwakenedProc.describe(profile) + ".");
					final Skill technique = (awakened != null) ? TameProfileRepository.findSecondAwakeningSkill(awakened.getUuid()) : null;
					if (technique != null)
					{
						player.sendMessage("It has also learned " + technique.getName() + ".");
					}
					if ((awakened != null) && (livePet != null) && livePet.isPet() && (livePet.getControlObjectId() == collar.getObjectId()))
					{
						TameAwakenedProc.apply(livePet, awakened, livePet.getLevel());
					}
				}
				else
				{
					player.sendMessage("Second awakening failed. Choose it only once.");
				}
				return showAwakening(player, collar);
			}
			if (profile.getCurrentLevel() < 30)
			{
				player.sendMessage("This tame must reach level 30 before awakening.");
				return showAwakening(player, collar);
			}
		if (TameProfileRepository.awaken(player.getObjectId(), collar.getObjectId(), path))
		{
			TameProfileRepository.logEvent(profile.getUuid(), "AWAKEN", "path=" + path.toUpperCase());
			player.sendMessage("Your tame awakened through the " + path.toUpperCase() + " path.");
			// Put the new look on the beast that is already standing there. The
			// profile read above predates the awakening, so it is reloaded rather than
			// reused, and the summon is only touched if it is this collar's.
			final TameProfile awakened = TameProfileRepository.load(player.getObjectId(), collar.getObjectId());
			if ((awakened != null) && (livePet != null) && livePet.isPet() && (livePet.getControlObjectId() == collar.getObjectId()))
			{
				TameVisual.apply(livePet, awakened);
			}
		}
			else
			{
				player.sendMessage("Awakening failed. Choose an available path only once.");
			}
			return showAwakening(player, collar);
		}

	public static void ensureProfileSkills(TameProfile profile, Item collar)
	{
		if ((profile != null) && (collar != null))
		{
			ensureSpeciesSkills(profile, collar);
		}
	}

	private static boolean showSkills(Player player, Item collar)
	{
		final TameProfile profile = TameProfileRepository.load(player.getObjectId(), collar.getObjectId());
		if (profile == null)
		{
			return false;
		}
		ensureSpeciesSkills(profile, collar);
		final boolean summoned = player.hasSummon() && player.getSummon().isPet() && (player.getSummon().getControlObjectId() == collar.getObjectId());
		final int level = summoned ? player.getSummon().getLevel() : profile.getCurrentLevel();
		final StringBuilder html = new StringBuilder(2800);
		html.append("<html><body><center>");
		header(html, profile);
		navigation(html, collar.getObjectId(), "skills", profile);
		html.append("<font color=LEVEL>SKILL DECK</font><br><font color=6E7681>Dynamic abilities and natural traits</font><br><br>");
		// Stated on the page rather than left to memory: "the tame did not use
		// anything" has too many possible causes to debug from silence, and whether
		// auto-cast is even on is the first one to rule out.
		html.append("<font color=6E7681>Auto-cast is ").append(TameAutoCast.isEnabledFor(player.getObjectId()) ? "<font color=7CFFB2>ON</font>" : "<font color=7D8590>OFF</font>").append(". </font><font color=6E7681>.tameautocast toggles it for you.</font><br><br>");
		if (!summoned)
		{
			html.append("<font color=7D8590>Summon this tame to cast an unlocked technique.</font><br><br>");
		}
		html.append(TameProfileRepository.getSkillDeck(profile.getUuid(), level, summoned));
		html.append("</center></body></html>");
		player.sendPacket(new NpcHtmlMessage(0, html.toString()));
		return true;
	}

	private static void ensureSpeciesSkills(TameProfile profile, Item collar)
	{
		final NpcTemplate template = NpcData.getInstance().getTemplate(profile.getSourceNpcId());
		if (template == null)
		{
			return;
		}
		final boolean hadSkills = TameProfileRepository.hasSkills(profile.getUuid());
		final java.util.List<TameSkillPolicy.Selection> selections = TameSkillPolicy.select(template, profile);
		if (selections.isEmpty())
		{
			return;
		}
		final PetData data = PetDataTable.getInstance().getPetData(profile.getSyntheticNpcId());
		for (TameSkillPolicy.Selection selection : selections)
		{
			if (TameProfileRepository.hasSkillSlot(profile.getUuid(), selection.getSlot()))
			{
				continue;
			}
			if (hadSkills ? TameProfileRepository.saveSelection(profile.getUuid(), selection) : TameProfileRepository.saveSkills(profile.getUuid(), selections))
			{
				if (data != null)
				{
					if (hadSkills)
					{
							data.addNewSkill(TameSkillPolicy.petSkillId(selection.getSkill().getId()), selection.getSkill().getLevel(), selection.getUnlockLevel());
					}
					else
					{
						TameSkillPolicy.register(data, template, profile);
					}
				}
			}
			if (!hadSkills)
			{
				break;
			}
		}
	}

		private static boolean showCommands(Player player, Item collar)
		{
			final TameProfile profile = TameProfileRepository.load(player.getObjectId(), collar.getObjectId());
			if (profile == null)
			{
				return false;
			}
			final boolean summoned = player.hasSummon() && player.getSummon().isPet() && (player.getSummon().getControlObjectId() == collar.getObjectId());
			final StringBuilder html = new StringBuilder(2600);
			html.append("<html><body><center>");
			header(html, profile);
			navigation(html, collar.getObjectId(), "commands", profile);
			html.append("<font color=F2CC60>COMMAND PANEL</font><br><font color=6E7681>Control this individual tame</font><br><br>");
			row(html, "Current order", displayCommand(TameCommandManager.getCommand(player, collar.getObjectId())));
			row(html, "Temperament", profile.getTemperament());
			row(html, "Bond", String.valueOf(profile.getBond()));
			html.append("<br>");
			commandButton(html, collar.getObjectId(), "FOLLOW", "FOLLOW", "Stay with the owner");
			commandButton(html, collar.getObjectId(), "GUARD", "GUARD", "Protect the owner");
			commandButton(html, collar.getObjectId(), "ATTACK", "ATTACK TARGET", "Attack the selected creature");
			commandButton(html, collar.getObjectId(), "HOLD", "HOLD", "Hold this position");
			commandButton(html, collar.getObjectId(), "RETURN", "RETURN", "Return to the owner");
			html.append("<br><font color=6E7681>");
			html.append(summoned ? "Commands affect the summoned tame immediately." : "Summon this tame before issuing a command.");
			html.append("</font><br>");
			backToSkills(html, collar.getObjectId());
			html.append("</center></body></html>");
			player.sendPacket(new NpcHtmlMessage(0, html.toString()));
			return true;
		}

		/**
		 * Explicit way back to the technique panel from a page that is not it.
		 *
		 * <p>Both those pages re-render on arrival, which resets the window's scroll
		 * and drag position. Actions taken on them now return here by themselves, but a
		 * link is still needed for looking at them without acting.
		 */
		private static void backToSkills(StringBuilder html, int collarObjectId)
		{
			html.append("<button value=TECHNIQUES action=\"bypass -h tamecollar_view ").append(collarObjectId).append(" skills\" width=110 height=22 back=sek.cbui94 fore=sek.cbui92><font color=D7DCE2>Back to techniques</font>");
		}

		private static void commandButton(StringBuilder html, int collarObjectId, String command, String label, String detail)
		{
			html.append("<button value=").append(command).append(" action=\"bypass -h tamecollar_command ").append(collarObjectId).append(' ').append(command).append("\" width=90 height=22 back=sek.cbui94 fore=sek.cbui92><font color=D7DCE2>").append(escape(label)).append("</font> <font color=6E7681>").append(escape(detail)).append("</font><br><br>");
		}

		private static String displayCommand(String command)
		{
			if ("GUARD".equals(command))
			{
				return "GUARD OWNER";
			}
			if ("ATTACK".equals(command))
			{
				return "ATTACK TARGET";
			}
			if ("HOLD".equals(command))
			{
				return "HOLD POSITION";
			}
			return command;
		}

		private static boolean equipmentAction(Player player, Item collar, String[] parts)
		{
			final String action = parts[2];
			if ("equip".equalsIgnoreCase(action) && (parts.length >= 5))
			{
				TameEquipmentManager.equip(player, collar.getObjectId(), parts[3], Integer.parseInt(parts[4]));
			}
			else if ("unequip".equalsIgnoreCase(action) && (parts.length >= 4))
			{
				TameEquipmentManager.unequip(player, collar.getObjectId(), parts[3]);
			}
			else
			{
				player.sendMessage("Invalid tame equipment action.");
			}
			// Straight back to the technique panel, so equipping gear cannot cost the
			// player the window they had parked where they wanted it.
			return showSkills(player, collar);
		}

		private static boolean showIdentity(Player player, Item collar)
		{
			final TameProfile profile = TameProfileRepository.load(player.getObjectId(), collar.getObjectId());
			if (profile == null)
			{
				return false;
			}
			final String grade = TameIdentity.imprintGrade(profile);
			final String affinity = TameIdentity.effectiveAffinity(profile);
			final StringBuilder html = new StringBuilder(3000);
			html.append("<html><body><center>");
			header(html, profile);
			navigation(html, collar.getObjectId(), "identity", profile);
			html.append("<font color=F2CC60>INDIVIDUAL IMPRINT</font><br><font color=6E7681>What this collar remembers</font><br><br>");
			html.append("<font color=7D8590>Grade: </font><font color=").append(TameIdentity.imprintColor(grade)).append(">").append(escape(grade)).append("</font><br>");
			html.append("<font color=7D8590>Imprint score: </font><font color=D7DCE2>").append(TameIdentity.imprintScore(profile)).append(" / 100</font><br>");
			html.append("<font color=7D8590>Resonance: </font><font color=7EE787>+").append(String.format(java.util.Locale.ROOT, "%.2f", TameIdentity.imprintStatFactor(profile) * 100.0)).append("%</font><br>");
		sentence(html, "applied on top of potential, growth and equipment.", "6E7681");
		html.append("<br>");
		sentence(html, TameIdentity.imprintDescription(grade), "6E7681");
		html.append("<br>");
		sentence(html, "Resonance is derived from this collar's own rolls, so it never changes. It is added on top of potential, growth and equipment, not instead of them.", "6E7681");
		html.append("<br>");
		// Affinity is shown as what it actually is: a rolled identity trait that
		// sharpens one stat. It is deliberately NOT described as a damage
		// matchup - that is the race circle's job, and it is described on its
		// own below. Affinity stays a flat, target-independent bonus so the two
		// systems cannot be confused for one another.
		html.append("<font color=F2CC60>AFFINITY</font><br>");
		html.append("<font color=7D8590>Primary affinity: </font><font color=").append(TameIdentity.affinityColor(affinity)).append(">").append(escape(affinity)).append("</font><br>");
		// The bonus is named in its own colour rather than left inside a sentence,
		// because this is the line a player scans for when comparing two beasts.
		final String affinityStat = TameIdentity.statLabel(affinity);
		if (!affinityStat.isEmpty())
		{
			html.append("<font color=7D8590>Bonuses: </font><font color=").append(TameIdentity.affinityColor(affinity)).append(">").append(escape(affinityStat)).append("</font><br>");
			sentence(html, TameIdentity.affinityDescription(affinity), "6E7681");
			html.append("<br>");
		}
		else
		{
			sentence(html, TameIdentity.affinityDescription(affinity), "6E7681");
		}
		// The race circle, on the same page, because it is the other half of this beast's
		// identity and it is the part that decides what to send it against. Only shown for
		// a beast that is actually in the circle: a blank heading on a human would be
		// advertising a mechanic that does not apply to it.
		final String matchup = TameRaceAffinity.describe(profile);
		if (!matchup.isEmpty())
		{
			html.append("<br>");
			html.append("<font color=F2CC60>RACE CIRCLE</font><br>");
			html.append("<font color=7D8590>Race: </font><font color=D7DCE2>").append(escape(String.valueOf(profile.getRace()))).append("</font><br>");
			html.append("<font color=7D8590>Strong against: </font><font color=7EE787>").append(escape(TameRaceAffinity.preyOf(TameRaceAffinity.raceOf(profile)).name())).append("</font><br>");
			html.append("<font color=7D8590>Weak against: </font><font color=D2694B>").append(escape(TameRaceAffinity.nemesisOf(TameRaceAffinity.raceOf(profile)).name())).append("</font><br>");
			sentence(html, "Deals " + String.format(java.util.Locale.ROOT, "%.0f", TameRaceAffinity.getAdvantagePercent()) + "% more damage to its strong race and "
				+ String.format(java.util.Locale.ROOT, "%.0f", TameRaceAffinity.getDisadvantagePercent()) + "% less to the one that outmatches it. Even against all other races.", "6E7681");
			html.append("<br>");
			sentence(html, "This follows whatever the beast is hitting right now. There is no buff to cast and nothing to switch.", "6E7681");
			html.append("<br>");
		}
		// The proc is listed here as well as on the awakening page, because this is
		// the page a player opens to compare two beasts and a behaviour nobody can
		// see until they fight is worse than one they cannot see at all.
		if (TameAwakenedProc.isUnlocked(profile))
		{
			html.append("<font color=7D8590>Second awakening: </font><font color=F6C453>").append(escape(TameAwakenedProc.describe(profile))).append("</font>");
		}
		else if (profile.getAwakeningStage() > 0)
		{
			html.append("<font color=7D8590>Second awakening: </font><font color=7D8590>locked until level ").append(TameAwakenedProc.getLevel()).append("</font>");
		}
			html.append("</center></body></html>");
			player.sendPacket(new NpcHtmlMessage(0, html.toString()));
			return true;
		}

		private static boolean showEquipment(Player player, Item collar)
		{
			final TameProfile profile = TameProfileRepository.load(player.getObjectId(), collar.getObjectId());
			if (profile == null)
			{
				return false;
			}
			final java.util.Map<String, TameProfileRepository.EquipmentRecord> equipped = TameProfileRepository.loadCustomEquipment(profile.getUuid());
			final StringBuilder html = new StringBuilder(6000);
			html.append("<html><body><center>");
			header(html, profile);
			navigation(html, collar.getObjectId(), "equipment", profile);
			html.append("<font color=F2CC60>TAME GEAR</font><br>");
			sentence(html, "Three persistent slots. Any weapon, armour or accessory.", "6E7681");
			html.append("<br>");
			for (String slot : TameEquipmentCatalog.getSlotOrder())
			{
				equipmentSlot(html, player, collar, equipped, slot);
			}
			html.append("<br><font color=7D8590>Dismiss the tame before changing gear.</font><br>");
			html.append("<font color=6E7681>Bonuses apply on the next summon.</font><br>");
			backToSkills(html, collar.getObjectId());
			html.append("</center></body></html>");
			player.sendPacket(new NpcHtmlMessage(0, html.toString()));
			return true;
		}

		private static void equipmentSlot(StringBuilder html, Player player, Item collar, java.util.Map<String, TameProfileRepository.EquipmentRecord> equipped, String slot)
		{
			final TameProfileRepository.EquipmentRecord record = equipped.get(slot);
			html.append("<font color=F2CC60>").append(TameEquipmentCatalog.labelFor(slot)).append("</font><br>");
			if (record != null)
			{
				final org.l2jmobius.gameserver.model.item.ItemTemplate template = org.l2jmobius.gameserver.data.xml.ItemData.getInstance().getTemplate(record.getItemId());
				html.append("<font color=D7DCE2>").append(escape(template == null ? "Unknown item" : template.getName()));
				if (record.getEnchantLevel() > 0)
				{
					html.append(" +").append(record.getEnchantLevel());
				}
				html.append("</font><br>");
				html.append("<button value=REMOVE action=\"bypass -h tamecollar_equipment ").append(collar.getObjectId()).append(" unequip ").append(slot).append("\" width=72 height=22 back=sek.cbui94 fore=sek.cbui92><br><br>");
				return;
			}
			html.append("<font color=7D8590>EMPTY</font><br>");
			final java.util.List<org.l2jmobius.gameserver.model.item.instance.Item> choices = TameEquipmentCatalog.candidates(player, slot, 6);
			if (choices.isEmpty())
			{
				html.append("<font color=6E7681>No ").append(escape(TameEquipmentCatalog.labelFor(slot).toLowerCase(java.util.Locale.ROOT))).append(" in your inventory.</font><br><br>");
				return;
			}
			for (org.l2jmobius.gameserver.model.item.instance.Item choice : choices)
			{
				html.append("<font color=D7DCE2>").append(escape(choice.getTemplate().getName()));
				if (choice.getEnchantLevel() > 0)
				{
					html.append(" +").append(choice.getEnchantLevel());
				}
				html.append("</font> <button value=EQUIP action=\"bypass -h tamecollar_equipment ").append(collar.getObjectId()).append(" equip ").append(slot).append(' ').append(choice.getObjectId()).append("\" width=52 height=20 back=sek.cbui94 fore=sek.cbui92><br>");
			}
			html.append("<br>");
		}

		private static boolean showAwakening(Player player, Item collar)
		{
			final TameProfile profile = TameProfileRepository.load(player.getObjectId(), collar.getObjectId());
			if (profile == null)
			{
				return false;
			}
			final StringBuilder html = new StringBuilder(3000);
			html.append("<html><body><center>");
			header(html, profile);
			navigation(html, collar.getObjectId(), "awakening", profile);
			html.append("<font color=F2CC60>AWAKENING</font><br><font color=6E7681>Choose one permanent evolution path</font><br><br>");
			if (profile.getAwakeningStage() >= TameAwakenedProc.STAGE)
			{
				html.append("<font color=F6C453>FULLY AWAKENED</font><br><font color=D7DCE2>").append(escape(profile.getAwakeningPath())).append(" path</font><br><font color=6E7681>On-hit: ").append(escape(TameAwakenedProc.describe(profile))).append("</font><br><br>");
			}
			else if (profile.getAwakeningStage() > 0)
			{
				html.append("<font color=F6C453>AWAKENED</font><br><font color=D7DCE2>").append(escape(profile.getAwakeningPath())).append(" path</font><br><br>");
				secondAwakening(html, profile, collar.getObjectId());
			}
			else if (profile.getCurrentLevel() < 30)
			{
				html.append("<font color=7D8590>LOCKED</font><br><font color=D7DCE2>Reach level 30 to awaken.</font><br><font color=6E7681>Current level: ").append(profile.getCurrentLevel()).append(" / 30</font><br><br>");
			}
			else
			{
				html.append("<font color=D7DCE2>Select a path:</font><br><br>");
				awakeningButton(html, collar.getObjectId(), "FANG", "Attack focus", "Physical attack resonance");
				awakeningButton(html, collar.getObjectId(), "PACK", "Vitality focus", "HP and defense resonance");
				awakeningButton(html, collar.getObjectId(), "SHADOW", "Predator focus", "Attack and defense resonance");
				awakeningButton(html, collar.getObjectId(), "ARCANE", "Mystic focus", "Magic attack and MP resonance");
				awakeningButton(html, collar.getObjectId(), "GUARDIAN", "Fortress focus", "HP and physical defense resonance");
			}
			html.append("</center></body></html>");
			player.sendPacket(new NpcHtmlMessage(0, html.toString()));
			return true;
		}

		/**
		 * Second awakening has no path buttons, because the proc follows affinity
		 * and there is nothing to choose. It is a single confirmation, and the
		 * page says so rather than showing an empty list of paths.
		 */
		private static void secondAwakening(StringBuilder html, TameProfile profile, int collarObjectId)
		{
			final int required = TameAwakenedProc.getLevel();
			if (profile.getCurrentLevel() < required)
			{
				html.append("<font color=7D8590>SECOND AWAKENING LOCKED</font><br><font color=D7DCE2>Reach level ").append(required).append(" to awaken again.</font><br><font color=6E7681>Current level: ").append(profile.getCurrentLevel()).append(" / ").append(required).append("</font><br><br>");
				return;
			}
		html.append("<font color=F2CC60>SECOND AWAKENING</font><br>");
		sentence(html, "The affinity proc follows the beast's affinity, not its path, so there is nothing to choose. This awakening also opens the beast's second technique.", "6E7681");
		html.append("<br>");
		html.append("<font color=D7DCE2>On a normal hit, this beast may inflict:</font><br><font color=F6C453>").append(escape(TameAwakenedProc.describe(profile))).append("</font><br><br>");
		html.append(AWAKENING_BUTTON_OPEN).append(AWAKENING_CAPTION_PAD).append("AWAKEN AGAIN\" action=\"bypass -h tamecollar_awaken ").append(collarObjectId).append(" SECOND").append(AWAKENING_BUTTON_CLOSE).append("<br>");
		sentence(html, "The technique stays locked until you use this, and applies to this beast and to any beast at level " + required + " or above.", "6E7681");
		}

		/**
		 * One awakening path, as a button with its explanation underneath.
		 *
		 * <p>The label and the effect used to sit inside the button element together,
		 * which is why these buttons appeared to split in two. The client takes the
		 * caption from the {@code value} attribute and ignores element content
		 * entirely, so the markup inside was drawn as loose text after the button -
		 * the path name twice, once as the button caption and once again beside it,
		 * and the tail of the sentence hanging off the 82px button rather than
		 * wrapping to the window.
		 *
		 * <p>So the caption goes in the attribute, where the client reads it, and the
		 * explanation is a wrapped line of its own below. It also stops the caption
		 * depending on markup: a caption is short by design, and the client truncates
		 * anything longer than the button anyway.
		 */
		private static void awakeningButton(StringBuilder html, int collarObjectId, String path, String title, String effect)
		{
			html.append(AWAKENING_BUTTON_OPEN);
			html.append(AWAKENING_CAPTION_PAD);
			html.append(escape(title));
			html.append("\" action=\"bypass -h tamecollar_awaken ").append(collarObjectId).append(' ').append(path).append(AWAKENING_BUTTON_CLOSE);
			sentence(html, effect, "6E7681");
			html.append("<br>");
		}

		private static boolean showHistory(Player player, Item collar)
		{
		final TameProfile profile = TameProfileRepository.load(player.getObjectId(), collar.getObjectId());
		if (profile == null)
		{
			return false;
		}
		final StringBuilder html = new StringBuilder(2200);
		html.append("<html><body><center>");
		header(html, profile);
		navigation(html, collar.getObjectId(), "history", profile);
		html.append("<font color=LEVEL>RECENT HISTORY</font><br><font color=6E7681>Recorded milestones</font><br><br>");
		
		html.append(TameProfileRepository.getHistorySummary(profile.getUuid()));
		html.append("<br>");
		html.append("</center></body></html>");
		player.sendPacket(new NpcHtmlMessage(0, html.toString()));
		return true;
	}

	private static void header(StringBuilder html, TameProfile profile)
	{
html.append("<font color=F2CC60>CREATURE COLLAR</font><br><font color=D7DCE2>");
		html.append(escape(profile.getPetName())).append("</font><br><font color=").append(rarityColor(profile.getRarity())).append(">");
		html.append(escape(profile.getRarity())).append("</font><font color=6E7681> ").append(escape(profile.getFamily())).append(" / ").append(escape(profile.getRole()));
		html.append("</font><br><br>");
	}

		private static void navigation(StringBuilder html, int collarObjectId, String selected, TameProfile profile)
		{
			html.append("<table width=270 border=0><tr>");
			gridButton(html, "PROFILE", "bypass -h tamecollar_view " + collarObjectId + " profile");
			gridButton(html, "SKILLS", "bypass -h tamecollar_view " + collarObjectId + " skills");
			gridButton(html, "EQUIP", "bypass -h tamecollar_view " + collarObjectId + " equipment");
			gridButton(html, "IMPRINT", "bypass -h tamecollar_view " + collarObjectId + " identity");
			html.append("</tr><tr>");
			gridButton(html, "AWAKEN", "bypass -h tamecollar_view " + collarObjectId + " awakening");
			gridButton(html, "HISTORY", "bypass -h tamecollar_view " + collarObjectId + " history");
			gridButton(html, "CMD", "bypass -h tamecollar_view " + collarObjectId + " commands");
			// One button, both directions: pressing the worn collar's own button takes
			// it off, which is why there is no second label for removing it.
			gridButton(html, profile.isWorn() ? "TAKE OFF" : "WEAR", "bypass -h tamecollar_wear " + collarObjectId);
			html.append("</tr></table><br>");
		}

		private static void gridButton(StringBuilder html, String label, String action)
		{
			html.append("<td width=67 align=center><button value=").append(label).append(" action=\"").append(action).append("\" width=65 height=21 back=L2UI_ch3.smallbutton2_over fore=L2UI_ch3.smallbutton2></td>");
		}

	private static void row(StringBuilder html, String label, String value)
	{
		html.append("<font color=7D8590>").append(escape(label)).append(": </font><font color=D7DCE2>").append(escape(value)).append("</font><br>");
	}

	private static String rarityColor(String rarity)
	{
		if ("UNCOMMON".equalsIgnoreCase(rarity))
		{
			return "5BE37D";
		}
		if ("RARE".equalsIgnoreCase(rarity))
		{
			return "58A6FF";
		}
		if ("EPIC".equalsIgnoreCase(rarity))
		{
			return "C084FC";
		}
		if ("LEGENDARY".equalsIgnoreCase(rarity))
		{
			return "F6C453";
		}
		return "A8B0BA";
	}

	private static String escape(String value)
	{
		if (value == null)
		{
			return "";
		}
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	/**
	 * How many characters fit across this window.
	 *
	 * <p>The client draws NpcHtmlMessage in a window with no text wrapping of its
	 * own: a line longer than the window is simply cut off at the edge. Every
	 * sentence in this module is therefore emitted through here, because the
	 * alternative is prose that runs off the side of the page depending on how long
	 * the roll happened to be - which is why the imprint page read as broken for
	 * some collars and not others.
	 *
	 * <p>Derived from the 270px navigation table and the ~5.7px average glyph width
	 * of the client's dialog font, with a little slack so a full line is never the
	 * thing that clips.
	 */
	private static final int LINE_WIDTH = 44;

	/**
	 * The opening and closing halves of an awakening path button.
	 *
	 * <p>Split in two so the caption can be escaped into the middle of the {@code value}
	 * attribute without the attribute's own quoting being built out of concatenated
	 * string literals at every call site.
	 *
	 * <p>{@code width=140} rather than the 120 this used to be: {@code sek.cbui94} draws its
	 * caption from a fixed left offset baked into the texture, so a button narrower than the
	 * caption's natural extent puts the word left of centre no matter what the surrounding
	 * markup says. {@code <center>} and {@code align=center} centre the <em>button</em> inside
	 * the page, which is a different thing entirely from centring the <em>caption</em> inside
	 * the button, and neither of them can move a caption the texture has already placed.
	 *
	 * <p>Which leaves the caption string itself as the only lever, and that is what
	 * {@link #AWAKENING_CAPTION_PAD} is for: leading spaces inside the {@code value} push the
	 * drawn text right by as much as the button lost in width. The offset is baked, so the
	 * fix has to come from the text the client is told to draw at that offset.
	 */
	private static final String AWAKENING_BUTTON_OPEN = "<button value=\"";

	private static final String AWAKENING_BUTTON_CLOSE = "\" width=100 height=24 back=sek.cbui94 fore=sek.cbui92><br>";

	/**
	 * Leading spaces on every awakening caption.
	 *
	 * <p>The buttons were 140 wide, which is what {@code sek.cbui94} bakes its caption offset
	 * for. Narrowing to 100 leaves the offset alone, so the caption is drawn 40px left of
	 * where it would sit at 140 - 20px of it on the left of centre. These spaces buy that 20px
	 * back by shifting the text, at roughly 6px each in the client font, so three is the
	 * starting guess rather than a measured value.
	 *
	 * <p>It is a guess, and it is the one number here that cannot be derived from the server
	 * side, because the caption is drawn by the client and the font metrics live there. Three
	 * is the number to try first; if the word reads as slightly right of centre, drop one, and
	 * if it reads left of centre, add one. Nothing else about the button depends on it.
	 */
	private static final String AWAKENING_CAPTION_PAD = "   ";

	/**
	 * Escapes and wraps a sentence to {@link #LINE_WIDTH}, appending line breaks.
	 *
	 * <p>Breaks on spaces rather than mid-word, because a hyphen-free client font
	 * splits a long word into something unreadable, while a space is a natural break
	 * the eye accepts. A single word longer than the limit is emitted whole rather
	 * than chopped - the identifiers that appear here are short, and silently
	 * cutting one in half would make a beast's grade look misspelled.
	 */
	private static void sentence(StringBuilder html, String text, String color)
	{
		if ((text == null) || text.trim().isEmpty())
		{
			return;
		}
		final String[] words = text.trim().split("\\s+");
		final StringBuilder line = new StringBuilder(LINE_WIDTH);
		for (String word : words)
		{
			if ((line.length() > 0) && ((line.length() + 1 + word.length()) > LINE_WIDTH))
			{
				html.append(paragraph(line.toString(), color));
				line.setLength(0);
			}
			if (line.length() > 0)
			{
				line.append(' ');
			}
			line.append(word);
		}
		if (line.length() > 0)
		{
			html.append(paragraph(line.toString(), color));
		}
	}

	/**
	 * One wrapped line.
	 */
	private static String paragraph(String line, String color)
	{
		return "<font color=" + color + ">" + escape(line) + "</font><br>";
	}
}

