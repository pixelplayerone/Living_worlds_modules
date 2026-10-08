/*
 * TameDiagCommand.java
 *
 * Admin check that the module is actually working, without needing to capture
 * anything first. It answers three questions: are the saved collars back in
 * place after a restart, does a chosen species still have a usable template, and
 * how much of the private npc id band is spent.
 *
 * Usage: //tamediag [speciesNpcId]
 */
package taming;

import org.l2jmobius.gameserver.data.holders.PetData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.data.xml.PetDataTable;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.skill.AbnormalVisualEffect;

public class TameDiagCommand implements IAdminCommandHandler
{
private static final String[] COMMANDS =
	{
			"tamediag",
			"tameaura"
	};

	@Override
	public boolean onCommand(String command, Player player)
	{
		if (command.startsWith("tameaura"))
		{
			return aura(command, player);
		}
		return collars(command, player);
	}

	/**
	 * Auditions a candidate aura on the caller's own beast.
	 *
	 * <p>This exists because a name in the AbnormalVisualEffect enum says which skill
	 * data uses an effect, not what it draws on an arbitrary beast model, and the
	 * only way to settle that is to look. The alternative is editing module.ini and
	 * rebooting once per candidate, which is six affinities times however many tries
	 * each, and reboot fatigue is how a config ends up with whatever was tried last
	 * rather than what was chosen.
	 *
	 * <p>The candidate is appended to whatever the beast is already wearing instead
	 * of replacing it, so a replacement for the affinity aura can be judged against
	 * the one it would displace rather than in isolation.
	 */
	private static boolean aura(String command, Player player)
	{
		final Summon summon = player.getSummon();
		if ((summon == null) || !summon.isPet())
		{
			player.sendMessage("Summon a tamed beast first, then use .tameaura.");
			return true;
		}
		final int collarObjectId = TameProfileRepository.findLastSummonedCollar(player.getObjectId());
		if (collarObjectId <= 0)
		{
			player.sendMessage("No collar is recorded as summoned for you.");
			return true;
		}
		final TameProfile profile = TameProfileRepository.load(player.getObjectId(), collarObjectId);
		if (profile == null)
		{
			player.sendMessage("That summon has no collar profile, so there is nothing to dress.");
			return true;
		}
		// Guard rather than trust: the lookup is by owner and last-summoned collar, so
		// a stale record could resolve to a profile for a beast that is not the one
		// standing here. The synthetic npc id is what the summon was built from, so
		// if it disagrees, refuse rather than dress the wrong creature.
		if (profile.getSyntheticNpcId() != summon.getId())
		{
			player.sendMessage("That summon (npc " + summon.getId() + ") does not match collar " + collarObjectId + " (npc " + profile.getSyntheticNpcId() + "). Not touching it.");
			return true;
		}
		final String argument = argument(command);
		if (argument.isEmpty())
		{
			report(player, profile);
			return true;
		}
		if (("off".equalsIgnoreCase(argument)) || ("none".equalsIgnoreCase(argument)))
		{
			TameVisual.preview(summon, profile, null);
			player.sendMessage("Audition cleared. The beast is back to its configured auras.");
			return true;
		}
		final AbnormalVisualEffect effect;
		try
		{
			effect = AbnormalVisualEffect.valueOf(argument.toUpperCase(java.util.Locale.ROOT));
		}
		catch (IllegalArgumentException e)
		{
			player.sendMessage("\"" + argument + "\" is not an AbnormalVisualEffect. Use .tameaura list to see all " + AbnormalVisualEffect.values().length + ".");
			return true;
		}
		TameVisual.preview(summon, profile, effect);
		player.sendMessage("Now auditioning " + effect.name() + " on top of the configured auras. If it looks right, put AffinityEffect<Affinity> = " + effect.name() + " in config/module.ini and reboot. .tameaura off to take it off.");
		return true;
	}

	/**
	 * What the beast is wearing now, and the candidates worth trying.
	 *
	 * <p>The list is grouped by how the effect failed rather than alphabetically,
	 * because the failures are the useful part: the DOT family pulses by design, and
	 * the effects ruled out for holy and dark failed in two distinct ways that are
	 * worth being able to recognise again.
	 */
	private static void report(Player player, TameProfile profile)
	{
		final String affinity = TameIdentity.effectiveAffinity(profile);
		final boolean awakened = profile.getAwakeningStage() > 0;
		// The race circle is included here because this is the only output that describes the
		// beast as it is right now, and the matchup is the one thing about it that changes
		// with what it is hitting. Read through the same path the damage calculation uses,
		// so this line cannot disagree with what the server is actually doing.
		final String current = TameRaceAffinity.describeCurrent(player.getSummon(), profile);
		if (!current.isEmpty())
		{
			player.sendMessage("race circle: " + current);
		}
		player.sendMessage("affinity " + affinity + ", world aura: " + TameVisual.effectName(affinity, awakened) + (awakened ? " (plus the awakened aura)" : ""));
		final AbnormalVisualEffect previewing = TameVisual.previewing();
		player.sendMessage(previewing == null ? "nothing is being auditioned." : ("auditioning " + previewing.name() + " on top."));
		player.sendMessage("The DOT_* family pulses, which is why DOT_FIRE_AREA flickers. Prefer a steady effect.");
		player.sendMessage("Drawn pinned to the spot, so the beast walks out of it: " + names("MAGIC_SQUARE", "GHOST_STUN", "FROZEN_PILLAR"));
		player.sendMessage("Drew nothing at all on a beast model: " + names("ULTIMATE_DEFENCE", "MP_SHIELD", "SPEED_DOWN"));
		player.sendMessage("Worth trying: " + names("SEIZURE1", "SEIZURE2", "STIGMA_OF_SILEN", "NAVIT_ADVENT", "DEATH_MARK", "INVINCIBILITY", "FREEZING", "VP_UP", "VP_KEEP", "TIME_BOMB", "CHANGE_VES_S", "CHANGE_VES_C", "CHANGE_VES_D"));
		player.sendMessage("There are " + AbnormalVisualEffect.values().length + " in total. .tameaura <NAME> to try one, .tameaura off to clear.");
	}

	private static String names(String... candidates)
	{
		final StringBuilder sb = new StringBuilder();
		for (final String candidate : candidates)
		{
			try
			{
				AbnormalVisualEffect.valueOf(candidate);
			}
			catch (IllegalArgumentException e)
			{
				// A name that stops being real should not take the whole line with it.
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(", ");
			}
			sb.append(candidate);
		}
		return sb.toString();
	}

	/** The argument after the command name, if any. */
	private static String argument(String command)
	{
		final int space = command.indexOf(' ');
		return (space < 0) ? "" : command.substring(space + 1).trim();
	}

	private boolean collars(String command, Player player)
	{
		int collars = 0;
		int broken = 0;
		for (TameProfile profile : TameProfileRepository.loadAll())
		{
			collars++;
			final int id = profile.getSyntheticNpcId();
			final NpcTemplate template = NpcData.getInstance().getTemplate(id);
			final PetData data = PetDataTable.getInstance().getPetData(id);
			if ((template == null) || (data == null))
			{
				broken++;
				player.sendMessage("BROKEN collar " + profile.getCollarObjectId() + " -> npc " + id + ": " + ((template == null) ? "no template" : "no profile"));
				continue;
			}
			player.sendMessage("ok collar " + profile.getCollarObjectId() + " -> npc " + id + " (species " + profile.getSourceNpcId() + ") " + data.getPetLevelData(1).getPetMaxHP() + " hp at level 1, drawn as " + template.getName() + " display " + template.getDisplayId());
		}
		if (collars == 0)
		{
			player.sendMessage("no collars saved yet, so there is nothing to restore");
		}

		final int probeSpecies = probe(command);
		final NpcTemplate species = NpcData.getInstance().getTemplate(probeSpecies);
		if (species == null)
		{
			player.sendMessage("probe: species " + probeSpecies + " has no template at all, and can never be tamed");
		}
		else
		{
			player.sendMessage("probe: species " + probeSpecies + " is " + species.getName() + " display " + species.getDisplayId() + " level " + species.getLevel() + ", a tame of it will look exactly like that");
		}

		player.sendMessage("private npc ids used " + TameForge.used() + " of " + TameForge.capacity() + (broken > 0 ? ", " + broken + " BROKEN" : ", all collars healthy"));
		return true;
	}

	private static int probe(String command)
	{
		// The admin dispatcher passes the whole command line, so an optional
		// species id may follow the command name.
		final String[] parts = command.trim().split("\\s+");
		if (parts.length > 1)
		{
			try
			{
				return Integer.parseInt(parts[1]);
			}
			catch (NumberFormatException e)
			{
				// fall through to the default probe
			}
		}
		return 20001;
	}

	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}
}
