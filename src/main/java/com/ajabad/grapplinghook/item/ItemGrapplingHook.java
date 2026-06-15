package com.ajabad.grapplinghook.item;

import com.ajabad.grapplinghook.Reference;
import com.ajabad.grapplinghook.entity.EntityGrapplingHook;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;

/**
 * The grappling hook item.
 *
 * <p>Its look is driven by the stack's metadata:
 * <ul>
 *   <li>{@link #META_PRIMED} (0) &mdash; ready to fire, renders {@code hookPrimed.png}.</li>
 *   <li>{@link #META_FIRED}  (1) &mdash; already launched, renders {@code hookFired.png}.</li>
 * </ul>
 *
 * <p>Right-clicking plays the exact sound vanilla uses when an egg/snowball is
 * thrown ({@code random.bow} with a randomized pitch; see {@code ItemEgg
 * #onItemRightClick}) and flips the stack from primed to fired, which swaps the
 * rendered icon.
 */
public class ItemGrapplingHook extends Item
{
    /** Metadata for a primed (ready) hook &mdash; the default state. */
    public static final int META_PRIMED = 0;
    /** Metadata for a fired (spent) hook. */
    public static final int META_FIRED = 1;

    @SideOnly(Side.CLIENT)
    private IIcon primedIcon;
    @SideOnly(Side.CLIENT)
    private IIcon firedIcon;

    public ItemGrapplingHook()
    {
        setUnlocalizedName("grappling_hook");
        setMaxStackSize(1);
        setHasSubtypes(true); // metadata is state, not a damage/durability bar
        setCreativeTab(CreativeTabs.tabTools);
        setFull3D(); // held as an angled 3D item (like the bow/tools), not a flat sprite
    }

    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player)
    {
        // Only a primed hook fires. A fired hook's right-click is consumed here so
        // it can't re-fire; the continuous "hold to reel" action is read client-side
        // from the use key (see GrappleController), not from this once-per-press hook.
        if (stack.getItemDamage() == META_PRIMED)
        {
            // Vanilla bow-release sound, randomized like an egg/snowball throw.
            world.playSoundAtEntity(player, "random.bow", 0.5F,
                    0.4F / (itemRand.nextFloat() * 0.4F + 0.8F));

            if (!world.isRemote)
            {
                world.spawnEntityInWorld(new EntityGrapplingHook(world, player));
            }

            stack.setItemDamage(META_FIRED);
        }

        return stack;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister register)
    {
        this.primedIcon = register.registerIcon(Reference.MODID + ":hookPrimed");
        this.firedIcon = register.registerIcon(Reference.MODID + ":hookFired");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconFromDamage(int damage)
    {
        return damage == META_FIRED ? this.firedIcon : this.primedIcon;
    }
}
