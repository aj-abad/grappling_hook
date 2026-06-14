package com.ajabad.grapplinghook;

/**
 * Item registration + crafting recipes. Invoked from
 * {@link com.ajabad.grapplinghook.proxy.CommonProxy#preInit}. Register items
 * with {@code GameRegistry.registerItem} and recipes with {@code GameRegistry.addRecipe}.
 */
public final class ModItems
{
    public static void register()
    {
        // No items yet. Example:
        //   Item myItem = new Item().setUnlocalizedName("my_item")
        //       .setTextureName(Reference.MODID + ":my_item");
        //   GameRegistry.registerItem(myItem, "my_item");
    }

    private ModItems() {}
}
