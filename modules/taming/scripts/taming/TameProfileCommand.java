/*
 * TameProfileCommand.java
 *
 * Server-side profile inspection for the current owner's summoned tame.
 * Runtime script source; Java 8 compatible.
 */
package taming;

import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.handler.VoicedCommandHandler;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.actor.instance.Pet;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

public class TameProfileCommand implements IVoicedCommandHandler
{
	private static final String[] COMMANDS = { "tameprofile", "tameinfo", "tameskills", "tamesummon", "tameawaken", "tameheal", "tamefeed", "tamebestiary", "tameautocast", "tamestore" };
	
	public static void main(String[] args)
	{
		VoicedCommandHandler.getInstance().registerHandler(new TameProfileCommand());
		IVoicedCommandHandler.LOGGER.info("TameProfileCommand: profile controls registered.");
	}
	
	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		if ((player == null) || (command == null))
		{
			return false;
		}

		if (command.startsWith("tamestore"))
		{
			// The reagent store has its own bypass handler for the buttons on the
			// page, but it is opened from here so the whole player-facing surface
			// stays in one voiced command list.
			return new TameReagentShop().onCommand("tamestore", player, null);
		}

		if (command.startsWith("tameautocast"))
		{
			return handleAutoCast(player, params);
		}
		if (command.startsWith("tamebestiary"))
		{
			showBestiary(player);
			return true;
		}
		if (command.startsWith("tamesummon"))
		{
			try
			{
				final int collarObjectId = Integer.parseInt(params == null ? "0" : params.trim());
				final org.l2jmobius.gameserver.model.item.instance.Item collar = player.getInventory().getItemByObjectId(collarObjectId);
				if ((collar == null) || (collar.getId() != TamingManager.getCollarItemId()) || (TameProfileRepository.load(player.getObjectId(), collarObjectId) == null))
				{
					player.sendMessage("That collar is not in your inventory or has no tame profile.");
					return false;
				}
				return DynamicPetSummon.summon(player, collar);
			}
			catch (Exception e)
			{
				player.sendMessage("Invalid collar reference.");
				return false;
			}
		}
		if (command.startsWith("tameskills"))
		{
			if ((player == null) || !player.hasSummon() || !player.getSummon().isPet())
			{
				player.sendMessage("Summon your tame first.");
				return false;
			}
			final org.l2jmobius.gameserver.model.item.instance.Item skillCollar = player.getInventory().getItemByObjectId(player.getSummon().getControlObjectId());
			return skillCollar != null && TameCollarView.openSkills(player, skillCollar);
		}
		if (command.startsWith("tameheal"))
		{
			return handleHeal(player, params);
		}
		if (command.startsWith("tamefeed"))
		{
			// Feeding is handled before the summon gate, because a wounded beast
			// cannot be summoned and food is how its wounds are nursed.
			return handleFeed(player, params);
		}
		if (command.startsWith("tameprofile") && (params != null) && isReleaseSubcommand(params))
		{
			return handleRelease(player, params.trim().substring("release".length()).trim());
		}
		if ((player == null) || !player.hasSummon() || !player.getSummon().isPet())
		{
			if (player != null)
			{
				player.sendMessage("Summon your tame first.");
			}
			return false;
		}
		final Summon summon = player.getSummon();
		final TameProfile profile = TameProfileRepository.load(player.getObjectId(), summon.getControlObjectId());
		if (profile == null)
		{
			player.sendMessage("This Pet has no individual profile.");
			return false;
		}
		final String normalized = params == null ? "" : params.trim();
		if (command.startsWith("tameawaken"))
		 {
				// The gate below is checked against the stored level by the awakening
				// SQL, so the summon must be copied across first or a beast that
				// levelled while out would be refused.
				TameProfileRepository.syncLevel(player.getObjectId(), summon.getControlObjectId(), summon.getLevel());
				final org.l2jmobius.gameserver.model.item.instance.Item awakeningCollar = player.getInventory().getItemByObjectId(summon.getControlObjectId());
				TameCollarView.ensureProfileSkills(profile, awakeningCollar);
				if (summon.getLevel() < 30)
			{
				player.sendMessage("This tame must reach level 30 before awakening.");
			}
			else if (TameProfileRepository.awaken(player.getObjectId(), summon.getControlObjectId(), normalized))
			{
				TameProfileRepository.logEvent(profile.getUuid(), "AWAKEN", "path=" + normalized.toUpperCase());
				player.sendMessage("Your tame has awakened through the " + normalized.toUpperCase() + " path.");
			}
			else
			{
				player.sendMessage("Awakening failed. Use FANG, PACK, SHADOW, ARCANE, or GUARDIAN at level 30+.");
			}
			return true;
		}
		final Item collar = player.getInventory().getItemByObjectId(summon.getControlObjectId());
		if (collar != null)
		{
			return openFromCollar(player, collar);
		}
		final StringBuilder html = new StringBuilder(2200);
		html.append("<html><body><center>");
		html.append("<font color=LEVEL>Creature Passport</font><br>");
		html.append("<font color=AAAAAA>").append(escape(summon.getName())).append("</font><br><br>");
		
		row(html, "Species", speciesName(profile));
			row(html, "Source level", String.valueOf(profile.getSourceLevel()));
			row(html, "Source type", profile.getSourceType());
			row(html, "Race", profile.getRace());
		matchupRow(html, profile);
		row(html, "Family", profile.getFamily());
		row(html, "Role", profile.getRole());
		coloredRow(html, "Rarity", profile.getRarity(), rarityColor(profile.getRarity()));
row(html, "Potential", profile.getPotential() + "/100");
		
			row(html, "Growth", String.format("%.2f%%", profile.getGrowthPercent()));
			affinityRow(html, profile);
				row(html, "Imprint", TameIdentity.imprintGrade(profile));
		row(html, "Temperament", profile.getTemperament());
		row(html, "Bond", String.valueOf(profile.getBond()));
			row(html, "Awakening", awakeningStatus(profile));

			row(html, "Condition", woundStatus(profile.getWoundFlags()));
					row(html, "Level", summon.getLevel() + " / " + profile.getMaxPetLevel());
		row(html, "HP", String.valueOf(summon.getMaxHp()));
		row(html, "MP", String.valueOf(summon.getMaxMp()));
		row(html, "P. Attack", String.valueOf((int) summon.getPAtk(null)));
		row(html, "P. Defense", String.valueOf((int) summon.getPDef(null)));
		row(html, "M. Attack", String.valueOf((int) summon.getMAtk(null, null)));
		row(html, "M. Defense", String.valueOf((int) summon.getMDef(null, null)));
		html.append("<br>");
		html.append("<font color=LEVEL>Inherited Skills</font><br>");
		html.append(TameProfileRepository.getSkillSummary(profile.getUuid()));
						html.append("<br><font color=6E7681>Use the collar window or .tameskills to open the skill deck.</font>");

		html.append("</center></body></html>");
		player.sendPacket(new NpcHtmlMessage(0, html.toString()));
		return true;
	}

	/**
	 * {@code .tameautocast [on|off]}. With no argument this only reports the current
	 * setting. The collar page shows the same state, so this is the chat-side version
	 * of the line already on the window.
	 *
	 * <p>This only ever touches the asking player's own mute. It used to write the
	 * server-wide flag, which meant any player could switch every tame on the server off
	 * for as long as they liked.
	 */
	private static boolean handleAutoCast(Player player, String params)
	{
		final String arg = (params == null) ? "" : params.trim().toLowerCase();
		if (arg.isEmpty() || "on".equals(arg) || "off".equals(arg))
		{
			if (!arg.isEmpty())
			{
				TameAutoCast.setMutedFor(player.getObjectId(), "off".equals(arg));
			}
			player.sendMessage("Auto-cast: " + (TameAutoCast.isEnabledFor(player.getObjectId()) ? "ON - your tame uses its techniques by itself." : "OFF - your tame will not use its techniques. Type .tameautocast on to re-enable."));
			return true;
		}
		player.sendMessage("Usage: .tameautocast [on|off]");
		return true;
	}

	private static boolean handleHeal(Player player, String params)
	{
		final Item collar = resolveCollar(player, params);
		if (collar == null)
		{
			player.sendMessage("Heal a collar directly: .tameheal <collar object id>, or keep a wounded tame's collar in your inventory.");
			return false;
		}
		final TameProfile profile = TameProfileRepository.load(player.getObjectId(), collar.getObjectId());
		if (profile == null)
		{
			player.sendMessage("That collar isn't bonded to any tamed creature.");
			return false;
		}
		// The profile keeps its own copy of the level, and it can only be brought up
		// to date by a summon. A beast that gained levels while already out would
		// otherwise show a stale level here, so refresh it before anything that
		// displays or gates on the value.
		final Summon livePet = player.getSummon();
		if (livePet != null && livePet.isPet() && livePet.getControlObjectId() == collar.getObjectId())
		{
			TameProfileRepository.syncLevel(player.getObjectId(), collar.getObjectId(), livePet.getLevel());
		}
		final TameProfile current = TameProfileRepository.load(player.getObjectId(), collar.getObjectId());
		if (current == null)
		{
			player.sendMessage("That collar's profile could not be read.");
			return false;
		}
		if (current.getWoundFlags() == 0)
		{
			player.sendMessage("Your tame isn't wounded.");
			return true;
		}
		// Nursing a dead-beat beast out of its collar always costs trust.
		if (TameProfileRepository.recoverAtCost(player.getObjectId(), collar.getObjectId(), 500) > 0)
		{
			TameProfileRepository.logEvent(profile.getUuid(), "RECOVERY", "wounds=cleared;cost=500");
			player.sendMessage("Your tame's wounds are healed, but the recovery cost it 500 bond. Feed it to rebuild its bond.");
		}
		else
		{
			player.sendMessage("Recovery failed; this collar has no wounded tame.");
		}
		return true;
	}

	private static boolean handleRelease(Player player, String rest)
	{
		final String[] parts = rest.split("\\s+");
		if ((parts.length < 1) || parts[0].isEmpty())
		{
			player.sendMessage("Use .tameprofile release <collar object id> [confirm] to permanently release a creature.");
			return false;
		}
		final Item collar = player.getInventory().getItemByObjectId(parseIntSafe(parts[0]));
		if ((collar == null) || (collar.getId() != TamingManager.getCollarItemId()))
		{
			player.sendMessage("No such collar in your inventory.");
			return false;
		}
		final TameProfile profile = TameProfileRepository.load(player.getObjectId(), collar.getObjectId());
		if (profile == null)
		{
			player.sendMessage("That collar isn't bonded to a tamed creature.");
			return false;
		}
		final boolean confirmed = (parts.length >= 2) && "confirm".equalsIgnoreCase(parts[1]);
		if (!confirmed)
		{
			player.sendMessage("This permanently releases " + profile.getPetName() + " and destroys the collar. Retype with 'confirm' to proceed.");
			return false;
		}
		// Only now is the release actually going ahead, so the pet is dismissed
		// here rather than on the dry run.
		if ((player.getSummon() != null) && player.getSummon().isPet() && (player.getSummon().getControlObjectId() == collar.getObjectId()))
		{
			player.getSummon().unSummon(player);
		}
		// The gear goes back before the rows are deleted. The items are held whole
		// in the vault, so returning them first is what keeps a release from
		// destroying an augmented or Shadow piece the player handed to the beast.
		if (!TameEquipmentManager.returnAll(player, profile.getUuid()))
		{
			player.sendMessage("Release cancelled: the tame's equipment could not be returned.");
			return false;
		}
		final int syntheticNpcId = TameProfileRepository.releaseCollar(player.getObjectId(), collar.getObjectId());
		if (syntheticNpcId != 0)
		{
			TameForge.release(syntheticNpcId);
			player.destroyItem(ItemProcessType.DESTROY, collar, 1, player, false);
			player.sendMessage(profile.getPetName() + " has been released into the wild. The collar is destroyed.");
		}
		else
		{
			player.sendMessage("Release failed; please report this to an admin.");
		}
		return true;
	}

	private static boolean handleFeed(Player player, String params)
	{
		// .tamefeed [amount] [collar object id]. With no arguments it feeds the
		// tame that is already out, which is the common case.
		int amount = 1;
		final Item collar;
		final String[] words = (params == null) ? new String[0] : params.trim().split("\\s+");
		if (words.length >= 2)
		{
			final int parsedAmount = parseIntSafe(words[0]);
			amount = (parsedAmount > 0) ? Math.min(20, parsedAmount) : 1;
			collar = collarByObjectId(player, words[1]);
		}
		else if (words.length == 1)
		{
			// One word is a collar id if it names one, otherwise it is the amount.
			final Item byId = collarByObjectId(player, words[0]);
			if (byId != null)
			{
				collar = byId;
			}
			else
			{
				final int parsedAmount = parseIntSafe(words[0]);
				amount = (parsedAmount > 0) ? Math.min(20, parsedAmount) : 1;
				collar = activeCollar(player);
			}
		}
		else
		{
			collar = activeCollar(player);
		}
		if (collar == null)
		{
			player.sendMessage("Feed a collar directly: .tamefeed [1-20] <collar object id>, or summon your tame first.");
			return false;
		}
		final TameProfile profile = TameProfileRepository.load(player.getObjectId(), collar.getObjectId());
		if (profile == null)
		{
			player.sendMessage("That collar isn't bonded to any tamed creature.");
			return false;
		}
		// A boss tame eats hatchling food, everything else eats wolf food. The item
		// id and the amount it restores both come from the same tier, so the food
		// the command eats is exactly the food the pet's own gauge accepts.
		final TameFood food = TameFood.forProfile(profile);
		final int have = player.getInventory().getInventoryItemCount(food.getItemId(), -1);
		if (have <= 0)
		{
			player.sendMessage(profile.getPetName() + " eats " + food.getName() + ", and you have none left. Buy it from The Ancient War Oath store.");
			return true;
		}
		final int used = Math.min(amount, have);
		if (!player.destroyItemByItemId(ItemProcessType.DESTROY, food.getItemId(), used, player, false))
		{
			return false;
		}
		// Bond is the tame's upkeep currency; it feeds straight back into its
		// battle stats, so regular feeding is what keeps an individual sharp.
		TameProfileRepository.addBond(player.getObjectId(), collar.getObjectId(), used * 2);

		// Food nurses wounds as well as filling the belly, one step per item, so
		// bond is the fast way out of an injury rather than the only one.
		final int healedSteps = Math.min(used, Math.max(0, profile.getWoundFlags()));
		final int healed = (healedSteps > 0) ? TameProfileRepository.healWounds(player.getObjectId(), collar.getObjectId(), healedSteps) : 0;

		final Summon summon = player.getSummon();
		if ((summon != null) && summon.isPet() && (summon.getControlObjectId() == collar.getObjectId()))
		{
			if (summon instanceof Pet)
			{
				final Pet pet = (Pet) summon;
				pet.setCurrentFed(Math.min(pet.getMaxFed(), pet.getCurrentFed() + (food.getRestore() * used)));
			}
			summon.setCurrentHp(Math.min(summon.getMaxHp(), summon.getCurrentHp() + (summon.getMaxHp() * 0.02 * used)));
			summon.setCurrentMp(Math.min(summon.getMaxMp(), summon.getCurrentMp() + (summon.getMaxMp() * 0.02 * used)));
		}
		TameProfileRepository.logEvent(profile.getUuid(), "FEED", "food=" + used + ";wound_steps=" + healedSteps);
		final StringBuilder message = new StringBuilder();
		message.append(profile.getPetName()).append(" eats ").append(used).append(" food: bond +").append(used * 2);
		if (healed > 0)
		{
			message.append(", wounds nursed by ").append(healedSteps).append(" step").append((healedSteps == 1) ? "" : "s");
			if (healedSteps >= profile.getWoundFlags())
			{
				message.append(" and is now healthy enough to summon");
			}
		}
		message.append('.');
		player.sendMessage(message.toString());
		return true;
	}

	private static Item resolveCollar(Player player, String params)
	{
		if (params != null)
		{
			try
			{
				final Item item = player.getInventory().getItemByObjectId(parseIntSafe(params.trim()));
				if ((item != null) && (item.getId() == TamingManager.getCollarItemId()))
				{
					return item;
				}
			}
			catch (Exception ignored)
			{
			}
		}
		if ((player.getSummon() != null) && player.getSummon().isPet())
		{
			final Item active = player.getInventory().getItemByObjectId(player.getSummon().getControlObjectId());
			if (active != null)
			{
				return active;
			}
		}
		return DynamicPetSummon.findAnyCollar(player);
	}

	private static int parseIntSafe(String value)
	{
		try
		{
			return Integer.parseInt(value == null ? "" : value.trim());
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	/**
	 * True only when the first word is exactly the release subcommand, so a
	 * typo like "released" cannot slip into the release handler.
	 */
	private static boolean isReleaseSubcommand(String params)
	{
		final String[] words = params.trim().split("\\s+");
		return (words.length > 0) && "release".equalsIgnoreCase(words[0]);
	}

	/** The player's own taming collar with this object id, or null. */
	private static Item collarByObjectId(Player player, String text)
	{
		final int objectId = parseIntSafe(text);
		if (objectId <= 0)
		{
			return null;
		}
		final Item item = player.getInventory().getItemByObjectId(objectId);
		return ((item != null) && (item.getId() == TamingManager.getCollarItemId())) ? item : null;
	}

	/** The collar of the tame that is out right now, or null. */
	private static Item activeCollar(Player player)
	{
		if ((player.getSummon() == null) || !player.getSummon().isPet())
		{
			return null;
		}
		final Item item = player.getInventory().getItemByObjectId(player.getSummon().getControlObjectId());
		return ((item != null) && (item.getId() == TamingManager.getCollarItemId())) ? item : null;
	}

	public static boolean openFromCollar(Player player, Item collar)
	{
		if ((player == null) || (collar == null) || (collar.getId() != TamingManager.getCollarItemId()))
		{
			return false;
		}
		final TameProfile profile = TameProfileRepository.load(player.getObjectId(), collar.getObjectId());
		if (profile == null)
		{
			player.sendMessage("This collar is not bonded to a tamed creature.");
			return false;
		}
		final StringBuilder html = new StringBuilder(4200);
		html.append("<html><body><center>");
html.append("<font color=F2CC60>CREATURE COLLAR</font><br>");
			html.append("<font color=FFFFFF>").append(escape(profile.getPetName())).append("</font><br>");
			html.append("<font color=").append(rarityColor(profile.getRarity())).append(">").append(escape(profile.getRarity())).append("</font><font color=6E7681> ").append(escape(profile.getFamily())).append(" / ").append(escape(profile.getRole())).append("</font><br><br>");
			navigation(html, collar.getObjectId(), profile);
			section(html, "IDENTITY");
		
			row(html, "Species", speciesName(profile) + " / source level " + profile.getSourceLevel());
		row(html, "Type / race", profile.getSourceType() + " / " + profile.getRace());
		row(html, "Family / role", profile.getFamily() + " / " + profile.getRole());
		row(html, "Level", profile.getCurrentLevel() + " / " + profile.getMaxPetLevel());
		row(html, "Bond", String.valueOf(profile.getBond()));
		html.append("<br>");
		section(html, "INDIVIDUAL TRAITS");
		
		coloredRow(html, "Rarity", profile.getRarity(), rarityColor(profile.getRarity()));
		row(html, "Potential", profile.getPotential() + " / 100");
		row(html, "Growth", String.format("%.2f%%", profile.getGrowthPercent()));
			affinityRow(html, profile);
				row(html, "Imprint", TameIdentity.imprintGrade(profile));
		row(html, "Temperament", profile.getTemperament());
			row(html, "Potential", profile.getPotential() + " / 100");
		html.append("<br>");
		section(html, "PROGRESSION & STATE");
		
			row(html, "Awakening", awakeningStatus(profile));
		row(html, "Next milestone", profile.getCurrentLevel() < 10 ? "Skill unlock at level 10" : profile.getCurrentLevel() < 20 ? "Skill unlock at level 20" : profile.getCurrentLevel() < 30 ? "Awakening unlock at level 30" : "Further growth available");
			row(html, "Condition", woundStatus(profile.getWoundFlags()));
		html.append("<br>");
			final Summon activeSummon = (player.hasSummon() && player.getSummon().isPet() && (player.getSummon().getControlObjectId() == collar.getObjectId())) ? player.getSummon() : null;
			if (activeSummon != null)
			{
				section(html, "LIVE COMBAT PROFILE");
				row(html, "HP / MP", Math.round(activeSummon.getMaxHp()) + " / " + Math.round(activeSummon.getMaxMp()));
				row(html, "P. Attack / Defense", Math.round(activeSummon.getStat().getPAtk(null)) + " / " + Math.round(activeSummon.getStat().getPDef(null)));
				row(html, "M. Attack / Defense", Math.round(activeSummon.getStat().getMAtk(null, null)) + " / " + Math.round(activeSummon.getStat().getMDef(null, null)));
				row(html, "Equipment", "Applied to this summoned Pet");
			}
			else
			{
				section(html, "STORED BASE PROFILE");
				row(html, "HP / MP", profile.getBaseHp() + " / " + profile.getBaseMp());
				row(html, "P. Attack / Defense", profile.getBasePAtk() + " / " + profile.getBasePDef());
				row(html, "M. Attack / Defense", profile.getBaseMAtk() + " / " + profile.getBaseMDef());
				row(html, "Equipment", "Summon this tame to view live equipped stats");
			}
				html.append("<br>");
				html.append("</center></body></html>");
		player.sendPacket(new NpcHtmlMessage(0, html.toString()));
		return true;
	}

	private static void navigation(StringBuilder html, int collarObjectId, TameProfile profile)
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
		gridButton(html, profile.isWorn() ? "TAKE OFF" : "WEAR", "bypass -h tamecollar_wear " + collarObjectId);
		html.append("</tr></table><br>");
	}

	private static void gridButton(StringBuilder html, String label, String action)
	{
		html.append("<td width=67 align=center><button value=").append(label).append(" action=\"").append(action).append("\" width=65 height=21 back=L2UI_ch3.smallbutton2_over fore=L2UI_ch3.smallbutton2></td>");
	}

	private static String awakeningStatus(TameProfile profile)
	{
		if (!profile.getAwakeningPath().isEmpty())
		{
			return profile.getAwakeningPath();
		}
		return profile.getAwakeningStage() > 0 ? "Awakened" : (profile.getCurrentLevel() >= 30 ? "Ready to awaken" : "Unawakened");
	}

	private static String woundStatus(int woundFlags)
	{
		if (woundFlags <= 0)
		{
			return "Healthy";
		}
		if (woundFlags >= TameProfileRepository.WOUND_MAX)
		{
			return "Badly wounded (feed to nurse, or .tameheal for 500 bond)";
		}
		return "Wounded " + woundFlags + "/" + TameProfileRepository.WOUND_MAX + " (feed to nurse)";
	}

	private static String speciesName(TameProfile profile)
	{
		try
		{
			final NpcTemplate template = NpcData.getInstance().getTemplate(profile.getSourceNpcId());
			return template == null ? "Unknown creature" : template.getName();
		}
		catch (Exception e)
		{
			return "Unknown creature";
		}
	}

	private static void section(StringBuilder html, String title)
	{
		html.append("<font color=LEVEL>").append(escape(title)).append("</font><br>");
	}

	private static void row(StringBuilder html, String label, String value)
	{
		html.append("<font color=6E7681>").append(escape(label)).append(":</font> <font color=D7DCE2>").append(escape(value)).append("</font><br>");
	}

	private static void coloredRow(StringBuilder html, String label, String value, String color)
	{
		html.append("<font color=6E7681>").append(escape(label)).append(":</font> <font color=").append(color).append(">").append(escape(value)).append("</font><br>");
	}

	/**
	 * Affinity with the stat it sharpens named next to it, in the affinity's own
	 * colour, so the passport says the same thing the collar page does.
	 */
	private static void affinityRow(StringBuilder html, TameProfile profile)
	{
		final String affinity = TameIdentity.effectiveAffinity(profile);
		final String stat = TameIdentity.statLabel(affinity);
		coloredRow(html, "Affinity", stat.isEmpty() ? affinity : (affinity + " - " + stat), TameIdentity.affinityColor(affinity));
	}
	
	/**
	 * The matchup circle, named as both ends of the edge rather than as a bare race, because
	 * "Race: ANIMAL" on its own tells a player nothing they could act on.
	 *
	 * <p>Omitted entirely for a beast outside the circle - players' races, mercenaries, siege
	 * weapons - rather than shown as an empty or "none" row, so the passport does not imply a
	 * mechanic that does not apply to it.
	 */
	private static void matchupRow(StringBuilder html, TameProfile profile)
	{
		final String matchup = TameRaceAffinity.describe(profile);
		if (matchup.isEmpty())
		{
			return;
		}
		coloredRow(html, "Matchup", matchup, "A8B0BA");
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
	
	private static void showBestiary(Player player)
	{
			final StringBuilder html = new StringBuilder(3200);
			html.append("<html><body><center><table width=300 bgcolor=\"0B0D10\" cellpadding=4><tr><td align=center><font color=F2CC60>CREATURE BESTIARY</font><br><font color=6E7681>Species discovered by this owner</font></td></tr></table><br>");
			html.append(TameProfileRepository.getBestiarySummary(player.getObjectId()));
			html.append("</center></body></html>");
			player.sendPacket(new NpcHtmlMessage(0, html.toString()));
		}

		@Override
		public String[] getCommandList()
		{
		return COMMANDS;
	}
}
