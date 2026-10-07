/*
 * ShopOpenItem.java
 *
 * Optional second way into the shop: double-click an item, it opens.
 *
 * This is disabled by default (OpenItemId = 0 in module.ini) because it is the
 * only part of this module that needs something the admin has to supply - an item
 * of their own. The chat command needs nothing.
 *
 * HOW THE LOOKUP WORKS, BECAUSE IT IS BY NAME AND NOT BY ID
 *
 *   ItemHandler.registerHandler(h)  ->  _datatable.put(h.getClass().getSimpleName(), h)
 *   ItemHandler.getHandler(item)   ->  _datatable.get(item.getHandlerName())
 *   EtcItem.getHandlerName()       ->  the item's <set name="handler"> value
 *
 * So this class's SIMPLE NAME has to equal the handler string in the item's
 * definition:
 *
 *   <set name="handler" val="ShopOpenItem" />
 *
 * If the two ever disagree, UseItem logs a warning naming the handler and the item
 * id, and the click does nothing. That warning is the first thing to look for.
 *
 * Unlike a skill's effects, this lookup happens when the player clicks rather than
 * when the item is parsed, so registering the handler during module enable is early
 * enough. No data phase ordering problem here.
 */
package shop;

import org.l2jmobius.gameserver.handler.IItemHandler;
import org.l2jmobius.gameserver.model.actor.Playable;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.instance.Item;

public class ShopOpenItem implements IItemHandler
{
	@Override
	public boolean onItemUse(Playable playable, Item item, boolean withPet)
	{
		// A pet cannot be carrying this, and there is no reason to trust the type.
		if (!(playable instanceof Player) || (item == null))
		{
			return false;
		}
		final Player player = (Player) playable;
		final int openItemId = ShopCatalogue.getInstance().openItemId();
		// Checked against the config rather than a constant, because the whole point
		// of the setting is that the admin chooses the id. A constant here would
		// silently never fire on any item but one.
		if (openItemId <= 0)
		{
			return false;
		}
		if (item.getId() != openItemId)
		{
			return false;
		}
		// Ownership is checked rather than assumed. Item keeps its owner in a private
		// field with no public getter, so this compares ids.
		if (item.getOwnerId() != player.getObjectId())
		{
			return false;
		}
		// withPet is Ctrl-press, and is ignored: a shop list has no second meaning to
		// hold back.
		ShopFrontPage.openFor(player);
		return true;
	}
}