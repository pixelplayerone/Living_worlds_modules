/*
 * TameEquipmentManager.java
 *
 * The tame equipment layer. Equipment is changed while the tame is dismissed and
 * applied on the next summon, keeping the active Pet object and native Pet
 * inventory untouched.
 *
 * The installed item is kept whole in TameGearVault rather than destroyed and
 * rebuilt, so an augment, a Shadow item's remaining mana and elemental
 * attributes survive both removal and a restart. The equipment row only records
 * which object id is held, so a row written before the vault existed (object id
 * 0) still falls back to the old rebuild-from-id behaviour and loses nothing
 * that was not already lost when it was written.
 */
package taming;

import java.util.Map;

import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.instance.Item;

public final class TameEquipmentManager
{
	private TameEquipmentManager()
	{
	}

	public static boolean equip(Player player, int collarObjectId, String slotType, int itemObjectId)
	{
		if ((player == null) || (collarObjectId <= 0) || (itemObjectId <= 0))
		{
			return false;
		}
		if (player.hasSummon())
		{
			player.sendMessage("Dismiss your tame before changing its equipment.");
			return true;
		}
		final String slot = TameEquipmentCatalog.canonical(slotType);
		if (slot == null)
		{
			player.sendMessage("That tame equipment slot does not exist.");
			return true;
		}
		final TameProfile profile = getProfile(player, collarObjectId);
		final Item item = player.getInventory().getItemByObjectId(itemObjectId);
		if ((profile == null) || (item == null) || !isGear(item))
		{
			player.sendMessage("That item is not equipment a tame can wear.");
			return true;
		}
		// The item has to be free to hand over. Anything the player is wearing has
		// to come off first, or depositing it would silently unequip it for them.
		if (item.isEquipped())
		{
			player.sendMessage("Take " + item.getTemplate().getName() + " off first, then install it on your tame.");
			return true;
		}
		// A weapon belongs in the weapon slot, armour in the armour slot, and
		// jewellery in the accessory slot. The bonus follows the item, so putting
		// a sword in the armour slot would only ever have been confusing.
		final String itemCategory = TameEquipmentCatalog.classify(item);
		if (!slot.equals(itemCategory))
		{
			player.sendMessage(item.getTemplate().getName() + " is " + categoryWord(itemCategory) + ", so it does not go in the " + TameEquipmentCatalog.labelFor(slot) + " slot.");
			return true;
		}

		final TameProfileRepository.EquipmentRecord old = TameProfileRepository.loadCustomEquipment(profile.getUuid()).get(slot);
		if ((old != null) && (old.getItemId() == item.getId()) && (old.getEnchantLevel() == item.getEnchantLevel()))
		{
			player.sendMessage("That equipment is already installed.");
			return true;
		}

		// Move the real item into the vault instead of destroying it. The same Item
		// instance travels with its augment, mana and attributes intact.
		final TameGearVault vault = TameGearVault.of(player);
		if ((vault == null) || (vault.deposit(item, player) == null))
		{
			player.sendMessage("The equipment item is no longer in your inventory.");
			return true;
		}
		if (!TameProfileRepository.saveCustomEquipment(profile.getUuid(), slot, item.getId(), item.getEnchantLevel(), TameEquipmentCatalog.encodeBonus(item, item.getEnchantLevel()), item.getObjectId()))
		{
			vault.withdraw(item, player);
			player.sendMessage("Equipment was not saved; the item was returned.");
			return true;
		}
		if (old != null)
		{
			// The gear being replaced has to come back, otherwise installing new
			// gear would destroy the old one.
			if (returnInstalled(player, vault, old) == null)
			{
				// It could not be handed back, so put the old gear on record again
				// and hand the new item back rather than letting the replacement
				// destroy it.
				TameProfileRepository.saveCustomEquipment(profile.getUuid(), slot, old.getItemId(), old.getEnchantLevel(), old.getCustomData(), old.getItemObjectId());
				vault.withdraw(item, player);
				player.sendMessage("The replaced equipment could not be returned, so it was restored and the new item was returned.");
				return true;
			}
		}
		// A weapon in the weapon slot is also the weapon the client draws, so the
		// tame's hand is updated here rather than waiting for the next summon.
		// Creatures that never carried a weapon ignore it and stay empty-handed.
		if (TameEquipmentCatalog.SLOT_WEAPON.equals(slot))
		{
			TameForge.stampWeapon(profile.getSyntheticNpcId(), item.getId(), item.getEnchantLevel());
		}
		player.sendMessage(item.getTemplate().getName() + " installed in the " + TameEquipmentCatalog.labelFor(slot) + " slot. Summon the tame to apply its bonuses.");
		if (TameEquipmentCatalog.SLOT_WEAPON.equals(slot) && !TameForge.canShowWeapon(profile.getSyntheticNpcId()))
		{
			player.sendMessage("This creature never carried a weapon, so it will not be drawn holding it. The bonuses still apply.");
		}
		return true;
	}

	private static boolean isGear(Item item)
	{
		if ((item == null) || (item.getTemplate() == null))
		{
			return false;
		}
		// Anything the player can wear themselves a tame can wear: weapons,
		// armour, and the accessory type that covers rings and necklaces.
		return item.getTemplate().isWeapon() || item.getTemplate().isArmor() || (item.getTemplate().getType2() == ItemTemplate.TYPE2_ACCESSORY);
	}

	/**
	 * Hands back the item a slot is holding.
	 *
	 * <p>For a vault-backed row the real item is moved out of the vault, keeping
	 * its augment, mana and attributes. A legacy row (object id 0) predates the
	 * vault and has no item to move, so it is rebuilt from its id and enchant
	 * exactly as before - which is the most those rows can give back.
	 *
	 * @return the item now in the player's inventory, or null if it could not be
	 *         returned (no room, or the item vanished).
	 */
	private static Item returnInstalled(Player player, TameGearVault vault, TameProfileRepository.EquipmentRecord record)
	{
		if ((record == null) || (vault == null))
		{
			return null;
		}
		if (record.getItemObjectId() > 0)
		{
			final Item held = vault.getItemByObjectId(record.getItemObjectId());
			if (held != null)
			{
				return vault.withdraw(held, player);
			}
		}
		// No item in the vault: an old row, or an orphaned one. Rebuild it as the
		// old code always did.
		return returnRefund(player, record.getItemId(), record.getEnchantLevel());
	}

	/**
	 * Hands a gear item back at the enchant level it was taken at. The plain
	 * addItem call would always return it at +0.
	 */
	private static Item returnRefund(Player player, int itemId, int enchantLevel)
	{
		return player.addItem(ItemProcessType.REFUND, itemId, 1, Math.max(0, Math.min(16, enchantLevel)), player, false);
	}

	/** How a category is named when telling a player their item is in the wrong slot. */
	private static String categoryWord(String category)
	{
		if (TameEquipmentCatalog.SLOT_WEAPON.equals(category))
		{
			return "a weapon";
		}
		if (TameEquipmentCatalog.SLOT_ARMOR.equals(category))
		{
			return "armour";
		}
		return "an accessory";
	}

	public static boolean unequip(Player player, int collarObjectId, String slotType)
	{
		if ((player == null) || (collarObjectId <= 0))
		{
			return false;
		}
		if (player.hasSummon())
		{
			player.sendMessage("Dismiss your tame before changing its equipment.");
			return true;
		}
		final String slot = TameEquipmentCatalog.canonical(slotType);
		final TameProfile profile = getProfile(player, collarObjectId);
		if ((profile == null) || (slot == null))
		{
			player.sendMessage("That tame or equipment slot is not valid.");
			return true;
		}
		final TameProfileRepository.EquipmentRecord record = TameProfileRepository.loadCustomEquipment(profile.getUuid()).get(slot);
		if (record == null)
		{
			player.sendMessage("That equipment slot is already empty.");
			return true;
		}
		if (!player.getInventory().validateCapacity(1))
		{
			player.sendMessage("You need room in your inventory for the equipment you are removing.");
			return true;
		}
		final TameGearVault vault = TameGearVault.of(player);
		if (!TameProfileRepository.clearCustomEquipment(profile.getUuid(), slot))
		{
			player.sendMessage("Equipment could not be removed.");
			return true;
		}
		final Item returned = returnInstalled(player, vault, record);
		if (returned == null)
		{
			TameProfileRepository.saveCustomEquipment(profile.getUuid(), slot, record.getItemId(), record.getEnchantLevel(), record.getCustomData(), record.getItemObjectId());
			player.sendMessage("The item could not be returned, so the equipment was restored.");
			return true;
		}
		player.sendMessage("Equipment removed from the " + TameEquipmentCatalog.labelFor(slot) + " slot.");
		// Clearing the weapon slot gives the tame back the weapon its wild species
		// carried, or an empty hand if it never carried one.
		if (TameEquipmentCatalog.SLOT_WEAPON.equals(slot))
		{
			TameForge.restoreBakedWeapon(profile.getSyntheticNpcId());
		}
		return true;
	}

	/**
	 * Hands every installed item for one tame back before its rows are deleted.
	 * Called on release so the gear is never lost with the beast.
	 *
	 * <p>Capacity is checked up front, so the item should never fail to come
	 * back. It returns false without changing anything when there is no room,
	 * letting the caller cancel the release rather than absorb the gear.
	 */
	public static boolean returnAll(Player player, String tameUuid)
	{
		if ((player == null) || (tameUuid == null))
		{
			return false;
		}
		final Map<String, TameProfileRepository.EquipmentRecord> equipment = TameProfileRepository.loadCustomEquipment(tameUuid);
		if (equipment.isEmpty())
		{
			return true;
		}
		if (!player.getInventory().validateCapacity(equipment.size()))
		{
			player.sendMessage("Make room in your inventory for " + equipment.size() + " piece(s) of equipment before releasing this tame.");
			return false;
		}
		final TameGearVault vault = TameGearVault.of(player);
		for (TameProfileRepository.EquipmentRecord record : equipment.values())
		{
			if (returnInstalled(player, vault, record) == null)
			{
				// Unreachable with capacity checked, but never proceed silently.
				return false;
			}
		}
		return true;
	}

	private static TameProfile getProfile(Player player, int collarObjectId)
	{
		final Item collar = player.getInventory().getItemByObjectId(collarObjectId);
		return ((collar != null) && (collar.getId() == TamingManager.getCollarItemId())) ? TameProfileRepository.load(player.getObjectId(), collarObjectId) : null;
	}
}