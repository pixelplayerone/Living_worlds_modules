/*
 * ShopFrontModule.java
 *
 * Entry point. Read the config, check the optional shop window, register one
 * bypass handler, get out of the way.
 *
 * There is no database table, no skill and no core edit anywhere in this
 * module, and the only id it reserves is the opener paper. It draws a page and
 * forwards a purchase. That is
 * deliberate: a shared module that needs setup is a shared module nobody sets up.
 *
 * The one thing worth reading before changing this is README.md, and the section
 * that matters is "What this HTML engine will not do".
 */
package shop;

import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerCreate;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogin;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.modules.GameModule;
import org.l2jmobius.gameserver.modules.ModuleContext;

public class ShopFrontModule implements GameModule
{
	@Override
	public void onEnable(ModuleContext context)
	{
		final java.util.logging.Logger log = context.logging();

		if (!context.config().getBoolean("Enabled", true))
		{
			log.info("disabled in module.ini");
			return;
		}

		final ShopCatalogue catalogue = ShopCatalogue.getInstance();
		catalogue.configure(context.config(), log);

		if (catalogue.rows().isEmpty())
		{
			// A blank catalogue is what this module ships with, so it is a normal
			// state and not a reason to refuse. The module still enables and the
			// page still opens; the page just explains that nothing is configured
			// yet and which key to fill in. Refusing here would mean a fresh
			// install looked identical to a broken one.
			log.info("shop: catalogue is empty, so the shop opens but sells nothing. "
					+ "Set the Items key in config/module.ini to add stock.");
		}

		// The window is checked once here rather than per request, so the page's
		// shape is decided at boot and does not change under a player mid-session.
		final boolean window = MultisellProbe.check(catalogue, log);
		ShopFrontPage.setWindowUsable(window);
		ShopFrontPage.setCommands(catalogue.command());

		context.handlers().registerBypass(new ShopFrontPage());

		// Registered unconditionally rather than behind OpenItemId, because the
		// lookup is by class name at the moment the player clicks rather than at
		// parse time. Enabling it later needs no restart of the data phase, and
		// registering a handler nothing points at costs nothing. The handler itself
		// checks OpenItemId and does nothing when it is 0.
		context.handlers().registerItem(new ShopOpenItem());

		// Hand the paper to every player, the same way a quest hands out a starter
		// item: a shop you must buy the key to before you can buy anything is a
		// chicken and egg problem. It is checked once here, not per login, so a
		// missing item definition is one warning at boot and not one per player.
		final int paperId = catalogue.openItemId();
		if ((paperId > 0) && context.config().getBoolean("GrantOpenItem", true))
		{
			if (ItemData.getInstance().getTemplate(paperId) == null)
			{
				log.warning("shop: GrantOpenItem is on but item " + paperId + " is not defined, so it cannot be handed out."
						+ " Check data/items and the reserves entry in module.json.");
			}
			else
			{
				context.events().onPlayers(EventType.ON_PLAYER_LOGIN, (OnPlayerLogin event) -> grantPaper(event.getPlayer(), paperId, log));
				context.events().onPlayers(EventType.ON_PLAYER_CREATE, (OnPlayerCreate event) -> grantPaper(event.getPlayer(), paperId, log));
			}
		}

		if (!catalogue.rows().isEmpty())
		{
			log.info("shop: ready. Double-click item " + catalogue.openItemId() + " to open the shop."
					+ (window ? " The native shop window is available and its button is drawn." : " Selling directly from the page."));
			// The button caption is named here on purpose. It is the one string on the
			// page a player will quote back when something draws wrong, and it changes
			// whenever the button markup is touched. Without it in the log the only way
			// to tell which build is live is to restart and look, which is exactly the
			// mistake this line exists to prevent.
			log.info("shop: build marker - window button is value=\"" + ShopFrontPage.WINDOW_BUTTON_CAPTION
					+ "\" width " + ShopFrontPage.WINDOW_BUTTON_WIDTH + ", unclosed, no <br> straight after the tag."
					+ " If the button on screen reads anything else, the server is running an older build: restart it.");
		}
	}

	/**
	 * Gives the player the paper if they do not already hold one. The paper is not
	 * stackable on purpose, so a player who has one is never given a second, and a
	 * player who destroyed theirs simply gets another at the next login.
	 */
	private static void grantPaper(Player player, int itemId, java.util.logging.Logger log)
	{
		if (player.getInventory().getItemByItemId(itemId) != null)
		{
			return;
		}
		if (player.getInventory().addItem(ItemProcessType.REWARD, itemId, 1, player, null) == null)
		{
			// The bag was full. The next login tries again.
			log.warning("shop: could not give the shop paper to " + player.getName() + "; the inventory is full");
		}
	}

	@Override
	public void onDisable(ModuleContext context)
	{
		// Nothing to stop. No threads, no scheduler, no cached state that outlives
		// the module classloader - which is the usual reason this method exists.
	}
}