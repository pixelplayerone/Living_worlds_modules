/*
 * TameEquipmentCatalog.java
 *
 * The tame gear layer. Three persistent, collar-bound slots that accept any
 * piece of equipment the player owns, so a tame can be geared with whatever the
 * player wants instead of one hand-picked item per slot.
 *
 * There is no per-item table any more. The bonus is derived from the installed
 * item itself: its real reference price puts it on a saturating curve, and its
 * enchant level adds a flat step. The derived category and bonus are written
 * into the equipment row when the item is installed, so applying gear at summon
 * time is pure arithmetic and needs no template lookup.
 *
 * Slots are deliberately separate from vanilla PetInventory/PET_EQUIP slots.
 */
package taming;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.instance.Item;

public final class TameEquipmentCatalog
{
	public static final String SLOT_WEAPON = "WEAPON";
	public static final String SLOT_ARMOR = "ARMOR";
	public static final String SLOT_TRINKET = "TRINKET";

	private static final String[] SLOT_ORDER = { SLOT_WEAPON, SLOT_ARMOR, SLOT_TRINKET };

	/**
	 * Per-slot ceilings. A tame wearing three full sets tops out around seventy
	 * percent, which is a real reward without letting gear replace the creature's
	 * own level growth and potential.
	 */
	private static final double CAP_WEAPON = 0.30;
	private static final double CAP_ARMOR = 0.25;
	private static final double CAP_TRINKET = 0.18;

	/**
	 * The reference price that earns half of a slot's cap. The curve is
	 * cap * price / (price + half), so it always stays under the cap and a
	 * priceless item is never an exploit.
	 */
	private static final double HALF_WEAPON = 120000.0;
	private static final double HALF_ARMOR = 150000.0;
	private static final double HALF_TRINKET = 80000.0;

	/** Added per enchant level, inside the slot cap. */
	private static final double ENCHANT_STEP = 0.012;

	private TameEquipmentCatalog()
	{
	}

	public static String[] getSlotOrder()
	{
		return SLOT_ORDER.clone();
	}

	/** True if the given text names one of the three slots. */
	public static boolean isSlot(String slot)
	{
		return canonical(slot) != null;
	}

	/** The stored name for a slot, or null when the text is not a slot. */
	public static String canonical(String slot)
	{
		if (slot == null)
		{
			return null;
		}
		for (String known : SLOT_ORDER)
		{
			if (known.equalsIgnoreCase(slot))
			{
				return known;
			}
		}
		return null;
	}

	/**
	 * Maps a stored slot name onto the current one. The three slots used to be
	 * called FANG, HIDE and CORE and held one fixed item each; they were reused
	 * for weapon, armour and accessory, so old rows are renamed in place rather
	 * than being abandoned.
	 */
	public static String migrateSlot(String stored)
	{
		final String known = canonical(stored);
		if (known != null)
		{
			return known;
		}
		if ("FANG".equalsIgnoreCase(stored))
		{
			return SLOT_WEAPON;
		}
		if ("HIDE".equalsIgnoreCase(stored))
		{
			return SLOT_ARMOR;
		}
		if ("CORE".equalsIgnoreCase(stored))
		{
			return SLOT_TRINKET;
		}
		return null;
	}

	public static String labelFor(String slot)
	{
		if (SLOT_WEAPON.equals(slot))
		{
			return "WEAPON";
		}
		if (SLOT_ARMOR.equals(slot))
		{
			return "ARMOR";
		}
		if (SLOT_TRINKET.equals(slot))
		{
			return "ACCESSORY";
		}
		return "GEAR";
	}

	private static double capFor(String category)
	{
		if (SLOT_WEAPON.equals(category))
		{
			return CAP_WEAPON;
		}
		if (SLOT_ARMOR.equals(category))
		{
			return CAP_ARMOR;
		}
		return CAP_TRINKET;
	}

	private static double halfPriceFor(String category)
	{
		if (SLOT_WEAPON.equals(category))
		{
			return HALF_WEAPON;
		}
		if (SLOT_ARMOR.equals(category))
		{
			return HALF_ARMOR;
		}
		return HALF_TRINKET;
	}

	/** The category an item contributes its stats through. This is taken from the
	 * item itself and not from the slot it was installed in, so a weapon may be
	 * placed in any slot and still grant attack.
	 */
	public static String classify(Item item)
	{
		if ((item == null) || (item.getTemplate() == null))
		{
			return null;
		}
		return classify(item.getTemplate());
	}

	private static String classify(ItemTemplate template)
	{
		if (template == null)
		{
			return null;
		}
		if (template.isWeapon())
		{
			return SLOT_WEAPON;
		}
		if (template.isArmor())
		{
			return SLOT_ARMOR;
		}
		return SLOT_TRINKET;
	}

	/** The bonus this item grants in its own category. */
	public static double bonusFor(Item item, int enchantLevel)
	{
		if (item == null)
		{
			return 0.0;
		}
		return bonusFor(item.getTemplate(), enchantLevel);
	}

	/** Same bonus, worked out from just an item id. Used when restoring gear. */
	public static double bonusForItemId(int itemId, int enchantLevel)
	{
		return bonusFor((itemId <= 0) ? null : ItemData.getInstance().getTemplate(itemId), enchantLevel);
	}

	private static double bonusFor(ItemTemplate template, int enchantLevel)
	{
		final String category = classify(template);
		if (category == null)
		{
			return 0.0;
		}
		final double cap = capFor(category);
		final double half = halfPriceFor(category);
		final double price = Math.max(0, template.getReferencePrice());
		final double fromPrice = cap * (price / (price + half));
		final double fromEnchant = Math.max(0, Math.min(16, enchantLevel)) * ENCHANT_STEP;
		return Math.min(cap, fromPrice + fromEnchant);
	}

	/**
	 * Packs the category and the derived bonus into the string kept in the
	 * equipment row, so nothing has to be looked up again on every summon.
	 */
	public static String encodeBonus(Item item, int enchantLevel)
	{
		if (item == null)
		{
			return null;
		}
		return encodeBonus(item.getId(), enchantLevel);
	}

	/** Same, from just an item id, for gear that is already in the database. */
	public static String encodeBonus(int itemId, int enchantLevel)
	{
		final String category = classify((itemId <= 0) ? null : ItemData.getInstance().getTemplate(itemId));
		if (category == null)
		{
			return null;
		}
		return category + "|" + bonusForItemId(itemId, enchantLevel);
	}

	private static String decodeCategory(String customData)
	{
		final int split = (customData == null) ? -1 : customData.indexOf('|');
		if (split <= 0)
		{
			return null;
		}
		return canonical(customData.substring(0, split));
	}

	private static double decodeBonus(String customData)
	{
		final int split = (customData == null) ? -1 : customData.indexOf('|');
		if (split <= 0)
		{
			return 0.0;
		}
		try
		{
			return Math.max(0.0, Double.parseDouble(customData.substring(split + 1)));
		}
		catch (NumberFormatException e)
		{
			return 0.0;
		}
	}

	/**
	 * Applies every installed piece of gear. Weapons feed attack, armour feeds
	 * defence and a little health, and everything else spreads across both. The
	 * result is a set of multipliers on the tame's own generated stats.
	 */
	public static void apply(java.util.Map<String, TameProfileRepository.EquipmentRecord> equipment, double[] factors)
	{
		if ((equipment == null) || (factors == null) || (factors.length < 6))
		{
			return;
		}
		for (TameProfileRepository.EquipmentRecord record : equipment.values())
		{
			if (record == null)
			{
				continue;
			}
			final String category = decodeCategory(record.getCustomData());
			final double bonus = decodeBonus(record.getCustomData());
			if ((category == null) || (bonus <= 0.0))
			{
				continue;
			}
			if (SLOT_WEAPON.equals(category))
			{
				factors[2] *= 1.0 + bonus; // physical attack
				factors[4] *= 1.0 + bonus; // magical attack
			}
			else if (SLOT_ARMOR.equals(category))
			{
				factors[3] *= 1.0 + bonus; // physical defence
				factors[5] *= 1.0 + bonus; // magical defence
				factors[0] *= 1.0 + (bonus * 0.4); // health
				factors[1] *= 1.0 + (bonus * 0.3); // mana
			}
			else
			{
				factors[2] *= 1.0 + bonus;
				factors[3] *= 1.0 + bonus;
				factors[4] *= 1.0 + bonus;
				factors[5] *= 1.0 + bonus;
			}
		}
	}

	/**
	 * The gear a player could install right now, best first, so the collar page
	 * can offer real choices instead of one fixed item per slot.
	 */
	public static List<Item> candidates(Player player, String slot, int limit)
	{
		final String wanted = canonical(slot);
		final List<Item> found = new ArrayList<>();
		if ((player == null) || (player.getInventory() == null) || (wanted == null))
		{
			return found;
		}
		for (Item item : player.getInventory().getItems())
		{
			if (item == null)
			{
				continue;
			}
			// Anything a player can wear on themselves a tame can wear, and only
			// into the slot that matches what the item actually is. Gear the
			// player is already wearing is skipped, otherwise the page would
			// offer to hand over the sword that is in their hand.
			if (item.isEquipped() || !item.getTemplate().isWeapon() && !item.getTemplate().isArmor() && !isWearableTrinket(item))
			{
				continue;
			}
			if (wanted.equals(classify(item)))
			{
				found.add(item);
			}
		}
		Collections.sort(found, new Comparator<Item>()
		{
			@Override
			public int compare(Item left, Item right)
			{
				final int byPrice = Integer.compare(right.getTemplate().getReferencePrice(), left.getTemplate().getReferencePrice());
				return (byPrice != 0) ? byPrice : Integer.compare(left.getId(), right.getId());
			}
		});
		if ((limit > 0) && (found.size() > limit))
		{
			return new ArrayList<>(found.subList(0, limit));
		}
		return found;
	}

	/**
	 * Rings, necklaces, earrings and bracelets are TYPE2_ACCESSORY. They are the
	 * only non weapon, non armour things worth counting as gear.
	 */
	private static boolean isWearableTrinket(Item item)
	{
		try
		{
			return item.getTemplate().getType2() == ItemTemplate.TYPE2_ACCESSORY;
		}
		catch (RuntimeException e)
		{
			return false;
		}
	}
}