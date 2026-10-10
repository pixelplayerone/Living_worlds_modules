package modules.phantommanagement;

import org.l2jmobius.gameserver.handler.IBypassHandler;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.modules.GameModule;
import org.l2jmobius.gameserver.modules.ModuleContext;

public final class PhantomManagementModule implements GameModule
{
	@Override
	public void onEnable(ModuleContext context)
	{
		if (!context.config().getBoolean("Enabled", false))
		{
			return; // Switch off: register nothing, behave as stock.
		}
		final PhantomPanel panel = new PhantomPanel();
		panel.configure(context.config());
		// Hidden maintainer switch: DebugErrors = True in config/module.ini shows handler errors in chat and the log.
		final boolean debugErrors = context.config().getBoolean("DebugErrors", false);
		context.handlers().registerVoicedCommand(new PhantomVoicedCommand(panel, debugErrors));
		context.handlers().registerBypass(new PhantomBypass(panel, debugErrors));
	}
}

/** `.phantom` in chat (or a macro) opens the window. */
final class PhantomVoicedCommand implements IVoicedCommandHandler
{
	private final PhantomPanel _panel;
	private final boolean _debugErrors;

	PhantomVoicedCommand(PhantomPanel panel, boolean debugErrors)
	{
		_panel = panel;
		_debugErrors = debugErrors;
	}

	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		if (!_debugErrors)
		{
			_panel.show(player, "home");
			return true;
		}
		try
		{
			_panel.show(player, "home");
		}
		catch (Throwable t)
		{
			System.err.println("[Phantom Management] Error opening the panel for " + player.getName() + ": " + t);
			t.printStackTrace();
			player.sendMessage("[Phantom] Error: " + t);
		}
		return true;
	}

	@Override
	public String[] getCommandList()
	{
		return new String[]
		{
			"phantom"
		};
	}
}

/** Every button in the window is a `bypass -h ph_...` that lands here. */
final class PhantomBypass implements IBypassHandler
{
	private static final String[] COMMANDS =
	{
		"ph_home",
		"ph_party",
		"ph_targets",
		"ph_phantoms",
		"ph_help",
		"ph_invite",
		"ph_drop",
		"ph_do",
		"ph_mem",
		"ph_more",
		"ph_buff",
		"ph_buffs",
		"ph_musicpage",
		"ph_music",
		"ph_cubics",
		"ph_cubic",
		"ph_tp",
		"ph_teleport",
		"ph_pull",
		"ph_size",
		"ph_find",
		"ph_findclass",
		"ph_craft",
		"ph_sex",
		"ph_view"
	};
	private final PhantomPanel _panel;
	private final boolean _debugErrors;

	PhantomBypass(PhantomPanel panel, boolean debugErrors)
	{
		_panel = panel;
		_debugErrors = debugErrors;
	}

	@Override
	public boolean onCommand(String command, Player player, Creature bypassOrigin)
	{
		if (!_debugErrors)
		{
			return handle(command, player);
		}
		try
		{
			return handle(command, player);
		}
		catch (Throwable t)
		{
			// An exception here would leave the client without a new page and the window would just close.
			System.err.println("[Phantom Management] Error handling '" + command + "' for " + player.getName() + ": " + t);
			t.printStackTrace();
			player.sendMessage("[Phantom] Error: " + t);
			try
			{
				_panel.show(player, "home");
			}
			catch (Throwable ignored)
			{
				// nothing else to do
			}
			return true;
		}
	}

	private boolean handle(String command, Player player)
	{
		final String[] a = command.trim().split("\\s+");
		final String cmd = a[0].toLowerCase();
		String page = cmd.substring("ph_".length()); // home / party / targets / phantoms / help
		switch (cmd)
		{
			case "ph_invite":
			{
				if (a.length > 1)
				{
					_panel.invite(player, a[1]);
				}
				else
				{
					player.sendMessage("Type a name first.");
				}
				// Keep the Phantoms tab open after inviting a waiting phantom. The current Friends/Recruit view is
				// preserved by PhantomPanel, so the list refreshes without jumping to Party.
				page = "phantoms";
				break;
			}
			case "ph_drop":
			{
				if (a.length > 1)
				{
					_panel.drop(player, a[1]);
				}
				else
				{
					player.sendMessage("Type a name first.");
				}
				page = "party";
				break;
			}
			case "ph_do":
			{
				if (a.length > 1)
				{
					_panel.group(player, a[1]);
				}
				page = (a.length > 2) ? a[2] : "home";
				break;
			}
			case "ph_mem":
			{
				if (a.length > 2)
				{
					_panel.member(player, a[1], a[2]);
				}
				page = "party";
				break;
			}
			case "ph_more":
			{
				if (a.length > 1)
				{
					_panel.openMember(player, a[1]);
				}
				page = "member";
				break;
			}
			case "ph_buff":
			{
				if (a.length > 2)
				{
					_panel.buff(player, a[1], a[2].replace('_', ' '));
				}
				page = "buffs";
				break;
			}
			case "ph_buffs":
			{
				if (a.length > 1)
				{
					_panel.openMember(player, a[1]);
				}
				page = "buffs";
				break;
			}
			case "ph_musicpage":
			{
				if (a.length > 1)
				{
					_panel.openMember(player, a[1]);
				}
				page = "music";
				break;
			}
			case "ph_music":
			{
				if (a.length > 2)
				{
					try
					{
						_panel.music(player, a[1], Integer.parseInt(a[2]));
					}
					catch (NumberFormatException e)
					{
						player.sendMessage("Invalid music skill.");
					}
				}
				page = "music";
				break;
			}
			case "ph_cubics":
			{
				if (a.length > 1)
				{
					_panel.openMember(player, a[1]);
				}
				page = "cubics";
				break;
			}
			case "ph_cubic":
			{
				if (a.length > 2)
				{
					_panel.cubic(player, a[1], a[2]);
				}
				page = "cubics";
				break;
			}
			case "ph_tp":
			{
				if (a.length > 1)
				{
					_panel.teleport(player, a[1]);
				}
				page = "teleport";
				break;
			}
			case "ph_teleport":
			{
				page = "teleport";
				break;
			}
			case "ph_pull":
			{
				if (a.length > 1)
				{
					_panel.pull(player, a[1]);
				}
				page = "party";
				break;
			}
			case "ph_size":
			{
				if (a.length > 1)
				{
					_panel.setPullSize(player, a[1]);
				}
				page = "home";
				break;
			}
			case "ph_find":
			{
				if (a.length > 1)
				{
					_panel.find(player, a[1], (a.length > 2) ? a[2] : "");
				}
				page = "phantoms";
				break;
			}
			case "ph_findclass":
			{
				if (a.length > 1)
				{
					_panel.findClass(player, a[1], (a.length > 2) ? a[2] : "");
				}
				page = "phantoms";
				break;
			}
			case "ph_craft":
			{
				_panel.craft(player, (a.length > 1) ? a[1] : null, (a.length > 2) ? a[2] : null);
				page = "phantoms";
				break;
			}
			case "ph_sex":
			{
				if (a.length > 1)
				{
					_panel.setCraftSex(player, a[1]);
				}
				page = "phantoms";
				break;
			}
			case "ph_view":
			{
				if (a.length > 1)
				{
					_panel.setView(player, a[1]);
				}
				page = "phantoms";
				break;
			}
			default:
			{
				break; // plain page switch
			}
		}
		_panel.show(player, page);
		return true;
	}

	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}
}
