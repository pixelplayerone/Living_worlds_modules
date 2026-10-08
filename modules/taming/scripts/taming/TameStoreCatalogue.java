/*
 * TameStoreCatalogue.java
 *
 * The reagent store as an item: double-click the catalogue in your bag, it opens.
 *
 * WHY THIS EXISTS RATHER THAN A CHAT COMMAND OR AN NPC
 *
 * Three routes were tried or ruled out and the reasoning is kept here so the next
 * person does not spend a day re-testing them.
 *
 * 1. A link on the eleven Pet Manager dialogues. Impossible. The module framework
 *    does register an "html" resource root, and ModuleResourceType even has an
 *    HTML value, which is what makes it look supported - but only five loaders
 *    read a module root at all: ItemData, SkillData, MultisellData, NpcData and
 *    SpawnData. HtmCache is not one of them. A module file would be cached as
 *    modules/taming/data/html/petmanager/30731.htm while a dialogue asks for
 *    petmanager/30731.htm, so the keys can never match and those overrides were
 *    never served.
 *
 * 2. Repurposing an NPC as a shopkeeper. Also impossible. ModuleHandlers exposes
 *    six hooks - voiced command, admin command, bypass, item, effect and target.
 *    Nothing fires when an NPC is clicked or talked to, so a module cannot open a
 *    page in place of a stock dialogue.
 *
 * 3. Double-clicking Adena. This looked even more promising, because Adena is in
 *    every bag and clicking it does nothing. It was implemented properly - the
 *    module redefined item 57 with a handler, which ItemData permits, since it
 *    parses stock items then module items into one map with a plain Map.put - and
 *    it does not work. The handler is reached from the UseItem packet, and the
 *    client does not send UseItem for currency. No packet, no handler, regardless
 *    of what the server declares. That override was deleted rather than left
 *    redefining the game's currency for something that can never fire.
 *
 * What is left is this: an item handler, which is a supported hook the module
 * already uses for the collar. The collar opens the passport on a double-click in
 * this same build, so the mechanism is proven rather than assumed.
 *
 * HOW THE LOOKUP WORKS
 *
 * By name, not by id, which is the part that is easy to get wrong:
 *
 *   ItemHandler.registerHandler(h)  ->  _datatable.put(h.getClass().getSimpleName(), h)
 *   ItemHandler.getHandler(item)   ->  _datatable.get(item.getHandlerName())
 *   EtcItem.getHandlerName()       ->  the item's <set name="handler"> value
 *
 * So this class's simple name has to equal the val on item 9303 in
 * data/items/9300-9399.xml. If they ever disagree, UseItem logs a warning naming
 * the handler and the item id, and the click does nothing - that warning is the
 * first thing to look for.
 *
 * UseItem does not consult default_action before calling a handler. It calls the
 * handler for any EtcItem that is not a paperdoll slot, so this does not need a
 * default_action and the click is not conditional on one.
 */
package taming;

import org.l2jmobius.gameserver.handler.IItemHandler;
import org.l2jmobius.gameserver.model.actor.Playable;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.instance.Item;

public class TameStoreCatalogue implements IItemHandler
{
	/** The item this handler is attached to. Must match the id in data/items. */
	private static final int CATALOGUE_ID = 9303;

	@Override
	public boolean onItemUse(Playable playable, Item item, boolean withPet)
	{
		// A pet cannot be carrying this and there is no reason to trust the type.
		if (!(playable instanceof Player) || (item == null) || (item.getId() != CATALOGUE_ID))
		{
			return false;
		}
		final Player player = (Player) playable;
		// The catalogue is not tradable, but ownership is still checked rather than
		// assumed. Item keeps its owner in a private _owner with no public getter,
		// so this compares ids.
		if (item.getOwnerId() != player.getObjectId())
		{
			return false;
		}
		// Ctrl-press is passed through as withPet. It is ignored: a catalogue has
		// no second meaning to hold back.
		TameReagentShop.openFor(player);
		return true;
	}
}