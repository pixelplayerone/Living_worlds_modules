/*
 * DynamicPetSummon.java
 *
 * Runtime item handler for the persistent tamed-beast collar.
 */
package taming;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.data.holders.PetData;
import org.l2jmobius.gameserver.handler.IItemHandler;
import org.l2jmobius.gameserver.model.actor.Playable;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Pet;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.network.serverpackets.PetItemList;

public class DynamicPetSummon implements IItemHandler
{
	private static final Logger LOGGER = Logger.getLogger(DynamicPetSummon.class.getName());

	@Override
	public boolean onItemUse(Playable playable, Item item, boolean forceUse)
	{
		if (!playable.isPlayer())
		{
			return false;
		}

		final Player player = playable.asPlayer();
		final Item ownedItem = player.getInventory().getItemByObjectId(item.getObjectId());
		if ((ownedItem == null) || (ownedItem != item) || (item.getId() != TamingManager.getCollarItemId()))
		{
			LOGGER.warning("DynamicPetSummon: rejected collar object " + item.getObjectId() + " for " + player);
			return false;
		}

		// The only thing that can summon a beast is the beast's own collar, and a
		// wounded beast stays inside. This is what the recall skill and the collar
		// UI both route through, so every summoning path shares the same rules.
		return TameProfileCommand.openFromCollar(player, item);
	}

	/**
	 * Returns the first collar in this player's inventory whose tame is ready to
	 * come out: bonded, active and not wounded. Used by Beast Recall, which has
	 * no idea which collar the player means, instead of arbitrarily picking the
	 * first collar-slot item like a vanilla item lookup would.
	 */
	public static org.l2jmobius.gameserver.model.item.instance.Item findUsableCollar(Player player)
	{
		if (player == null)
		{
			return null;
		}
		for (org.l2jmobius.gameserver.model.item.instance.Item collar : player.getInventory().getAllItemsByItemId(TamingManager.getCollarItemId()))
		{
			final TameProfile profile = TameProfileRepository.load(player.getObjectId(), collar.getObjectId());
			if ((profile != null) && (profile.getWoundFlags() == 0))
			{
				return collar;
			}
		}
		return null;
	}

	/**
	 * Returns any collar bound to a tame in this player's inventory, wounded or
	 * not. Used by the recovery command so a wounded beast can always be healed
	 * even though it cannot be summoned.
	 */
	public static org.l2jmobius.gameserver.model.item.instance.Item findAnyCollar(Player player)
	{
		if (player == null)
		{
			return null;
		}
		for (org.l2jmobius.gameserver.model.item.instance.Item collar : player.getInventory().getAllItemsByItemId(TamingManager.getCollarItemId()))
		{
			if (TameProfileRepository.load(player.getObjectId(), collar.getObjectId()) != null)
			{
				return collar;
			}
		}
		return null;
	}

	/**
	 * Returns the collar this player is wearing, if it is still in the inventory and
	 * its beast is ready to come out.
	 *
	 * <p>Strict on purpose. This is the "the owner said which beast they meant"
	 * lookup, and a caller that falls back to an arbitrary collar asks for a beast
	 * nobody chose. Login uses it exactly as it is; recall, which is a request the
	 * player did make, falls back on its own.
	 */
	public static org.l2jmobius.gameserver.model.item.instance.Item findWornCollar(Player player)
	{
		if (player == null)
		{
			return null;
		}
		final int collarObjectId = TameProfileRepository.findWornCollar(player.getObjectId());
		if (collarObjectId <= 0)
		{
			return null;
		}
		final org.l2jmobius.gameserver.model.item.instance.Item collar = player.getInventory().getItemByObjectId(collarObjectId);
		if ((collar == null) || (collar.getId() != TamingManager.getCollarItemId()))
		{
			// The worn collar is not in this inventory: dropped, destroyed or moved. The
			// row is left alone rather than reassigned, so a later wear press heals it.
			return null;
		}
		final TameProfile profile = TameProfileRepository.load(player.getObjectId(), collarObjectId);
		if ((profile == null) || (profile.getWoundFlags() != 0))
		{
			return null;
		}
		return collar;
	}

	/**
	 * Returns the collar this player last brought out, if it is still in the
	 * inventory and its beast is ready to come out.
	 * <p>
	 * This is the collar-aware replacement for picking an arbitrary one. Stock pet
	 * restore identifies a saved pet by its collar's <em>item id</em>, and every
	 * collar is the same item, so the stock lookup resolves an arbitrary beast
	 * whenever a player owns more than one. Only this module's profile can say
	 * which collar was actually out.
	 */
	public static org.l2jmobius.gameserver.model.item.instance.Item findLastSummonedCollar(Player player)
	{
		if (player == null)
		{
			return null;
		}
		final int collarObjectId = TameProfileRepository.findLastSummonedCollar(player.getObjectId());
		if (collarObjectId <= 0)
		{
			return findUsableCollar(player);
		}
		final org.l2jmobius.gameserver.model.item.instance.Item collar = player.getInventory().getItemByObjectId(collarObjectId);
		if ((collar == null) || (collar.getId() != TamingManager.getCollarItemId()))
		{
			// The row outlived the item, so fall back rather than summon nothing.
			return findUsableCollar(player);
		}
		final TameProfile profile = TameProfileRepository.load(player.getObjectId(), collarObjectId);
		if ((profile == null) || (profile.getWoundFlags() != 0))
		{
			return findUsableCollar(player);
		}
		return collar;
	}

	/**
	 * Summons exactly the collar object supplied by the owner-side passport.
	 *
	 * <p>The template is cloned under the collar's own stored synthetic id rather
	 * than the species id, so the pet that appears is the same individual that was
	 * captured, with the same rolled stats, even after a restart.
	 *
	 * <p>This is the player-driven entry point and it enforces the state a call requires.
	 * Login restore goes through {@link #summon(Player, Item, boolean)} with
	 * {@code onLogin}, because relogging must not be refused by a guard meant for players
	 * pressing a button.
	 */
	public static boolean summon(Player player, Item item)
	{
		return summon(player, item, false);
	}

	/**
	 * @param onLogin true for the restore-on-login path, which skips the player-state guards
	 */
	public static boolean summon(Player player, Item item, boolean onLogin)
	{
		if ((player == null) || (item == null) || (item.getId() != TamingManager.getCollarItemId()))
		{
			return false;
		}
		final Item ownedItem = player.getInventory().getItemByObjectId(item.getObjectId());
		if ((ownedItem == null) || (ownedItem != item))
		{
			return false;
		}
		if (player.hasSummon() || player.isMounted())
		{
			player.sendMessage("You already have a pet/summon out. Dismiss it first.");
			return false;
		}
		// A beast called out on demand is a way out of a bad situation, so these are
		// refused rather than queued. None of the four were checked before, and each was
		// a distinct abuse: summoning mid-fight to pull, heal or break off a duel;
		// summoning in an Olympiad match, where bond grows with damage dealt, so the
		// summon farmed bond on a character meant to be duelling alone; summoning while
		// dead or mid-cast, both of which put a second creature into an action the
		// player was not actually in a position to take.
		if (!onLogin)
		{
			if (player.isDead())
			{
				player.sendMessage("You cannot call your beast while you are dead.");
				return false;
			}
			if (player.isInCombat())
			{
				player.sendMessage("You cannot call your beast while you are in combat.");
				return false;
			}
			if (player.isInOlympiadMode())
			{
				player.sendMessage("You cannot call your beast in an Olympiad match.");
				return false;
			}
			if (player.isCastingNow())
			{
				player.sendMessage("You cannot call your beast while you are casting.");
				return false;
			}
		}

		final TameProfile profile = TameProfileRepository.load(player.getObjectId(), item.getObjectId());
		if (profile == null)
		{
			player.sendMessage("This collar isn't bonded to any tamed creature.");
			return false;
		}
		if (profile.getWoundFlags() != 0)
		{
			// A dead (or wounded) beast has to be nursed back before it will
			// answer the call again. This is what makes a lost fight costly.
			player.sendMessage("Your beast is recovering from serious wounds and will not come out yet. Heal it with .tameheal first.");
			return false;
		}

		final int syntheticNpcId = profile.getSyntheticNpcId();
		if (syntheticNpcId <= 0)
		{
			LOGGER.warning("DynamicPetSummon: collar " + item.getObjectId() + " has no synthetic npc id and cannot be summoned");
			player.sendMessage("This collar's record is incomplete. Please contact an admin.");
			return false;
		}

		NpcTemplate forgedTemplate = null;
		try
		{
			// Re-forging is cheap and idempotent, and it repairs a template that was
			// dropped by a reload without touching the profile.
			forgedTemplate = TameForge.forge(profile.getSourceNpcId(), syntheticNpcId);
			if (forgedTemplate == null)
			{
				player.sendMessage("Something went wrong summoning your pet. Please contact an admin.");
				return false;
			}

			// Re-forging creates a fresh template. Restore any collar-bound weapon
			// before the pet is spawned, otherwise a weapon installed earlier is
			// lost visually after dismiss/resummon or a module reload.
			TameForge.applySavedGear(profile.getUuid(), syntheticNpcId);

			// Before the bind below, and deliberately before the pet exists: the deck is
			// written once at capture, so this is where a tame forged before real player
			// techniques existed picks one up. It is idempotent and touches only the
			// SIGNATURE_1 row, so an awakened beast keeps its chosen path.
			TameSkillPolicy.refreshSignature(profile);

			// Before the pet exists, because the bar lives on the template and the
			// core reads it off summon.getTemplate() when a bar button is pressed.
			TameSkillBar.bind(forgedTemplate, profile);

			final PetData regeneratedData = PetProfileGenerator.generatePersisted(profile, forgedTemplate);
			TameForge.register(syntheticNpcId, regeneratedData);

		// Pet stat data has to exist for the synthetic id before the beast is
		// spawned. The first packet that describes it asks the core for its
		// experience bar, and the core looks that up by npc id with no null check
		// anywhere on the way, so a missing entry throws while writing the packet
		// rather than at some convenient later point. This publishes the curve that
		// was just regenerated from the beast's own profile - health, attack and
		// defence from its captured template, diluted by rarity - so what the core
		// reports is the beast rather than some stock species standing in for it.
		TamePetDataRegistry.register(syntheticNpcId, regeneratedData);

			final Pet pet = Pet.spawnPet(forgedTemplate, player, item);
			if (pet == null)
			{
				TameForge.release(syntheticNpcId);
				player.sendMessage("Something went wrong summoning your pet. Please contact an admin.");
				return false;
			}

			pet.setShowSummonAnimation(true);
			// A beast captured before the cap was enforced can be stored above it, and
			// the stock summon restores that level from the collar. Bring it back
			// under its own ceiling before anything reads it, then let the collar
			// write below carry the corrected level back into storage.
			TameLevelCap.correct(pet, profile);
			// A capture starts unnamed so the client's own rename box is offered, and
			// that box is the only way a name can be typed in. Once the owner has used
			// it the name lives in the stock pets row, so pull it across here or the
			// empty profile name would overwrite it and the rename would appear to
			// revert on every summon.
			final String summonName = TameProfileRepository.adoptStockPetName(player.getObjectId(), item.getObjectId(), profile.getPetName());
			pet.setName(summonName);
			if (!pet.isRespawned())
			{
				pet.fullRestore();
				pet.setCurrentFed(pet.getMaxFed());
			}
			pet.setRunning();
			if (!pet.isRespawned())
			{
				pet.storeMe();
			}

		item.setEnchantLevel(pet.getLevel());
		player.setPet(pet);
		// Before spawnMe, so the first packet the client gets already has the weapon.
		TameForge.equipVisibleGear(pet, syntheticNpcId);
		pet.spawnMe(player.getX() + 30, player.getY() + 30, player.getZ());
		// After spawnMe on purpose: the effect is a server-side bitmask that rides out
		// in the summon info packet, so a client that already knows about this pet
		// would never be told about it otherwise.
		TameVisual.apply(pet, profile);
		pet.startFeed();
			pet.setFollowStatus(true);
			// Reports what ended up in the tame's hands (HandsDebug in module.ini).
			TameForge.logHands(pet, syntheticNpcId);
			player.sendPacket(new PetItemList(pet.getInventory().getItems()));
			pet.broadcastStatusUpdate();
			TameProfileRepository.addBond(player.getObjectId(), item.getObjectId(), 1);
			// The pet's own level lives in the generated experience curve, but the
			// awakening gate and the passport both read the profile's copy of it, so it
			// has to be brought across or those two stay stuck at "level 1" forever.
			TameProfileRepository.syncLevel(player.getObjectId(), item.getObjectId(), pet.getLevel());
			return true;
		}
		catch (Exception e)
		{
			LOGGER.log(java.util.logging.Level.SEVERE, "DynamicPetSummon: error resummoning collar " + item.getObjectId(), e);
			TameForge.release(syntheticNpcId);
			player.sendMessage("Something went wrong summoning your pet. Please contact an admin.");
			return false;
		}
	}
}

