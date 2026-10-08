/*
 * TamingManager.java
 *
 * Creates a collar-bound pet from a captured wild creature.
 *
 * Conversion reads the captured creature's template and its live combat values,
 * rolls one individual profile for the new physical collar, gives that collar
 * its own synthetic npc id, and hands the pair to the stock Pet.spawnPet. The
 * stock pet then behaves normally in every respect: window, feeding, inventory,
 * following, skills, and the pet's own saved state.
 *
 * Nothing here is patched into the game. The only unusual step is that the pet is
 * summoned from a cloned template carrying a private id, which is what lets the
 * stock pet code resolve this collar's own profile without a second lookup key.
 */
package taming;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.data.holders.PetData;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.instance.Pet;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.network.serverpackets.PetItemList;

public class TamingManager
{
	private static final Logger LOGGER = Logger.getLogger(TamingManager.class.getName());

	private static int _collarItemId = 9302;

	/** The collar item the module hands out. Read from config at startup. */
	public static void setCollarItemId(int collarItemId)
	{
		_collarItemId = collarItemId;
	}

	public static int getCollarItemId()
	{
		return _collarItemId;
	}

	private TamingManager()
	{
	}

	public boolean convertToPet(Player player, Monster wildMob, TamingEntry entry)
	{
		if (player.hasSummon() || player.isMounted())
		{
			player.sendMessage("You already have a pet/summon out. Dismiss it first.");
			return false;
		}

		final int sourceNpcId = wildMob.getId();
		// A new capture starts unnamed on purpose. Stock pet code only offers the
		// client's rename box while a pet has no name, so naming it here would close
		// that option for good. The species is still recorded in source_type and is
		// shown in the passport, so nothing is lost.
		final String petName = "";
		final int x = wildMob.getX();
		final int y = wildMob.getY();
		final int z = wildMob.getZ();
		final int sourceLevel = wildMob.getLevel();

		// Read the wild mob's own real stats BEFORE deleting it. Reading these
		// after deleteMe() would give broken or zeroed values.
		final double baseHp = wildMob.getMaxHp();
		final double baseMp = wildMob.getMaxMp();
		final double basePAtk = wildMob.getPAtk(null);
		final double basePDef = wildMob.getPDef(null);
		final double baseMAtk = wildMob.getMAtk(null, null);
		final double baseMDef = wildMob.getMDef(null, null);

		Item collar = null;
		Pet pet = null;
		int syntheticNpcId = 0;
		try
		{
			final NpcTemplate sourceTemplate = wildMob.getTemplate();
			if (sourceTemplate == null)
			{
				LOGGER.warning("TamingManager: no NpcTemplate found for npcId " + sourceNpcId);
				player.sendMessage("Something went wrong creating your pet. Please contact an admin.");
				return false;
			}

			// The collar is created first: its object id is the identity of this pet
			// instance. Species npc ids are shared by every player who tames the same
			// creature and must never be used as the profile key.
			collar = player.getInventory().addItem(ItemProcessType.REWARD, _collarItemId, 1, player, wildMob);
			if (collar == null)
			{
				LOGGER.warning("TamingManager: failed to create collar for npcId " + sourceNpcId);
				player.sendMessage("Could not create the pet collar. The taming attempt was cancelled.");
				return false;
			}

			// Roll one deterministic individual profile for this physical collar.
			final double conversionMultiplier = PetProfileGenerator.getConversionMultiplier(entry.getTier());
			final TameProfile profile = TameProfileFactory.create(collar.getObjectId(), player.getObjectId(), sourceNpcId, sourceLevel, sourceTemplate.getType(), petName, conversionMultiplier, entry.getMaxPetLevel(), baseHp, baseMp, basePAtk, basePDef, baseMAtk, baseMDef, sourceTemplate, entry.getTier());

			// Give the collar its own private npc id, and keep it. The same id has to
			// come back on the next server start or the pet will not be restorable.
			syntheticNpcId = TameForge.allocate();
			profile.setSyntheticNpcId(syntheticNpcId);

			// Roll the skill slots once so everything below sees the same rolls,
			// then write the profile, slots, bestiary sighting and history event as
			// a single transaction. Nobody can ever observe a half-written
			// individual: the band id, the rows and the inventory collar either all
			// exist together or not at all.
			final java.util.List<TameSkillPolicy.Selection> selections = TameSkillPolicy.select(sourceTemplate, profile);
			boolean rareInheritance = false;
			for (TameSkillPolicy.Selection selection : selections)
			{
				if ((selection != null) && "RARE_INHERITANCE".equals(selection.getSlot()))
				{
					rareInheritance = true;
					break;
				}
			}
			if (!TameProfileRepository.persistCapture(profile, selections, player.getObjectId(), sourceNpcId, profile.getPotential(), profile.getGrowthPercent(), profile.getAwakeningStage(), rareInheritance, "npc=" + sourceNpcId + ";family=" + profile.getFamily() + ";rarity=" + profile.getRarity() + ";potential=" + profile.getPotential() + ";synthetic=" + syntheticNpcId))
			{
				throw new IllegalStateException("could not persist the tame capture " + profile.getUuid());
			}

			// Clone the creature's template under the private id, so the untouched
			// client still draws the captured creature but the id is this collar's.
			final NpcTemplate forgedTemplate = TameForge.forge(sourceNpcId, syntheticNpcId);
			if (forgedTemplate == null)
			{
				throw new IllegalStateException("could not clone the template for npcId " + sourceNpcId);
			}

			final PetData data = PetProfileGenerator.generate(profile, forgedTemplate);
			TameForge.register(syntheticNpcId, data);

			// The capture path spawns the beast itself rather than going through the
			// collar's summon, so it publishes its stat data separately - otherwise the
			// first packet describing a freshly caught beast throws while being
			// written and the client is left with a half-drawn pet. The data published
			// is the same curve that was just generated from the captured template.
			TamePetDataRegistry.register(syntheticNpcId, data);

			pet = Pet.spawnPet(forgedTemplate, player, collar);
			if (pet == null)
			{
				throw new IllegalStateException("Pet.spawnPet returned null for npcId " + sourceNpcId);
			}

			pet.setName(profile.getPetName());
			pet.setShowSummonAnimation(true);

			if (!pet.isRespawned())
			{
				// Set the generated starting level before restoring HP and MP so the
				// initial vitals use the profile's own level-one values.
				pet.getStat().setLevel((byte) 1);
				pet.fullRestore();
				pet.setCurrentFed(pet.getMaxFed());
			}

			pet.setRunning();
			if (!pet.isRespawned())
			{
				pet.storeMe();
			}

			// The wild mob is removed only after a real pet, collar and profile exist.
			//
			// deleteMe() alone takes the creature out of the world without telling its
			// spawn, so a boss or raid caught this way never came back: the world lost it
			// and the respawn timer was never started. Restarting the spawn's own timer
			// before the delete puts it back on the schedule the npc data already declares,
			// which is what "catch it and it respawns later" has to mean.
			final org.l2jmobius.gameserver.model.spawns.Spawn originSpawn = wildMob.getSpawn();
			if (originSpawn != null)
			{
				try
				{
					originSpawn.startRespawn();
				}
				catch (RuntimeException e)
				{
					// A spawn with no respawn delay configured (0) is legitimate; log and let
					// the delete stand rather than failing a capture that already succeeded.
					LOGGER.log(Level.FINE, "TamingManager: could not restart respawn for spawn " + originSpawn.getId(), e);
				}
			}
			wildMob.deleteMe();

			collar.setEnchantLevel(pet.getLevel());
			player.setPet(pet);
			// Before spawnMe, so the first packet the client gets already has the weapon.
			TameForge.equipVisibleGear(pet, syntheticNpcId);
			pet.spawnMe(x, y, z);
			// Same reason as the re-summon path: after spawnMe, so the client is told.
			TameVisual.apply(pet, profile);
			pet.startFeed();
			pet.setFollowStatus(true);
			player.sendPacket(new PetItemList(pet.getInventory().getItems()));
			pet.broadcastStatusUpdate();

			player.sendMessage(profile.getPetName() + " has been tamed! " + profile.getRarity() + " " + profile.getFamily() + " / " + profile.getRole() + ". Potential " + profile.getPotential() + "/100, growth " + profile.getGrowthPercent() + "%, affinity " + profile.getAffinity() + ", temperament " + profile.getTemperament() + ". Use .tameprofile for the full passport.");
			return true;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "TamingManager: error converting npcId " + sourceNpcId + " to a pet", e);
			// Give the private id back so a failed capture does not leak the band.
			if (syntheticNpcId != 0)
			{
				TameForge.release(syntheticNpcId);
			}
			// Drop any partial rows the capture may have written before it failed.
			if (collar != null)
			{
				TameProfileRepository.deleteByCollar(collar.getObjectId());
			}
			if (pet != null)
			{
				pet.deleteMe(player);
			}
			else if ((collar != null) && (player.getInventory().getItemByObjectId(collar.getObjectId()) == collar))
			{
				player.destroyItem(ItemProcessType.DESTROY, collar, 1, wildMob, false);
			}
			player.sendMessage("Pet creation failed safely; your taming item was refunded.");
			return false;
		}
	}

	public static TamingManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		private static final TamingManager INSTANCE = new TamingManager();
	}
}
