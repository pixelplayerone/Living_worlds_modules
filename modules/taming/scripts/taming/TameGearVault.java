/*
 * TameGearVault.java
 *
 * A private, player-inaccessible container that holds a tame's installed gear.
 *
 * The old equipment layer destroyed the item on install and rebuilt a plain one
 * from (item_id, enchant) on removal. That is lossless only for the two numbers
 * it copied: an augment, a Shadow item's remaining mana and elemental attributes
 * were silently thrown away. There is no way to snapshot those onto the row -
 * Item has no Shadow-mana setter and no element getters - so the item itself has
 * to be kept whole, and the slot row only records which item it is
 * (TameProfileRepository's item_object_id column).
 *
 * The container is a stock ItemContainer parked on ItemLocation.LEASE: a
 * location no player-facing container ever reads (Inventory restores INVENTORY
 * and PAPERDOLL only, the warehouses read WAREHOUSE, a pet inventory reads PET),
 * which Item explicitly treats as persistent. That keeps the item in the world
 * database, invisible to the player, so the client can never equip, trade, sell
 * or drop it while it is on a tame.
 *
 * One vault per player, cached for the session and dropped on logout. restore()
 * is inherited: it loads every LEASE item owned by the player and registers it
 * with the world, which brings the augments and Shadow mana back with it.
 */
package taming;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.enums.ItemLocation;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.itemcontainer.ItemContainer;

public final class TameGearVault extends ItemContainer
{
	private static final Map<Integer, TameGearVault> VAULTS = new ConcurrentHashMap<>();

	private final Player _owner;
	private boolean _restored;

	private TameGearVault(Player owner)
	{
		_owner = owner;
	}

	/**
	 * The vault for a player, loading it from the database the first time it is
	 * asked for in a session. Restoring lazily means no login hook is required
	 * for correctness, but one is registered anyway so the items are live before
	 * anything else touches them.
	 */
	public static TameGearVault of(Player player)
	{
		if (player == null)
		{
			return null;
		}
		final TameGearVault vault = VAULTS.computeIfAbsent(player.getObjectId(), id -> new TameGearVault(player));
		vault.restoreOnce();
		return vault;
	}

	/** Drops the cached vault so a stale Player reference cannot outlive a session. */
	public static void forget(Player player)
	{
		if (player != null)
		{
			VAULTS.remove(player.getObjectId());
		}
	}

	private synchronized void restoreOnce()
	{
		if (!_restored)
		{
			_restored = true;
			restore();
		}
	}

	@Override
	protected Creature getOwner()
	{
		return _owner;
	}

	@Override
	protected ItemLocation getBaseLocation()
	{
		return ItemLocation.LEASE;
	}

	/**
	 * Moves an item out of the player's inventory and into this vault. The same
	 * Item instance is moved, so its object id, augment, enchant and Shadow mana
	 * all come along. Returns the item now held, or null if it could not be moved.
	 */
	public Item deposit(Item item, Player actor)
	{
		if ((item == null) || (actor == null) || (actor.getInventory() == null))
		{
			return null;
		}
		return actor.getInventory().transferItem(ItemProcessType.TRANSFER, item.getObjectId(), item.getCount(), this, actor, null);
	}

	/**
	 * Moves an item held here back into the player's inventory. Returns the item
	 * handed back, or null when there was no room for it (the item stays here).
	 */
	public Item withdraw(Item item, Player actor)
	{
		if ((item == null) || (actor == null) || (actor.getInventory() == null))
		{
			return null;
		}
		return transferItem(ItemProcessType.TRANSFER, item.getObjectId(), item.getCount(), actor.getInventory(), actor, null);
	}
}