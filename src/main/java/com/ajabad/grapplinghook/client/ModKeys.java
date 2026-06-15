package com.ajabad.grapplinghook.client;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

/**
 * Client key bindings for the grappling hook. Reading state happens in
 * {@link GrappleController}; these are registered so they appear in (and can be
 * rebound from) the vanilla Controls screen.
 */
@SideOnly(Side.CLIENT)
public final class ModKeys
{
    /** Hold to pay out / lengthen the cable (default Left Ctrl). */
    public static final KeyBinding EXTEND = new KeyBinding(
            "key.grapplinghook.extend", Keyboard.KEY_LCONTROL, "key.categories.grapplinghook");

    /** Tap to instantly drop the hook with no added momentum (default Left Shift). */
    public static final KeyBinding DISCONNECT = new KeyBinding(
            "key.grapplinghook.disconnect", Keyboard.KEY_LSHIFT, "key.categories.grapplinghook");

    public static void register()
    {
        ClientRegistry.registerKeyBinding(EXTEND);
        ClientRegistry.registerKeyBinding(DISCONNECT);
    }

    private ModKeys() {}
}
