package com.ajabad.grapplinghook;

import com.ajabad.grapplinghook.item.ItemGrapplingHook;

import cpw.mods.fml.common.registry.GameRegistry;

import net.minecraft.item.Item;

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
    }

    private ModItems() {}
}
