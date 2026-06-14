package com.ajabad.grapplinghook;

import com.ajabad.grapplinghook.item.ItemGrapplingHook;

import cpw.mods.fml.common.registry.GameRegistry;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * Item registration + crafting recipes. Invoked from
 * {@link com.ajabad.grapplinghook.proxy.CommonProxy#preInit}. Register items
 * with {@code GameRegistry.registerItem} and recipes with {@code GameRegistry.addRecipe}.
 */
public final class ModItems
{
    public static Item grapplingHook;

    public static void register()
    {
        grapplingHook = new ItemGrapplingHook();
        GameRegistry.registerItem(grapplingHook, "grappling_hook");

        // Shaped recipe (A = arrow, S = string, B = bow):
        //   A . .
        //   . S .
        //   . . B
        GameRegistry.addRecipe(
                new ItemStack(grapplingHook, 1, ItemGrapplingHook.META_PRIMED),
                "A  ",
                " S ",
                "  B",
                'A', Items.arrow,
                'S', Items.string,
                'B', Items.bow);
    }

    private ModItems() {}
}
