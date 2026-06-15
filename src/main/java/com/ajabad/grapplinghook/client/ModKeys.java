package com.ajabad.grapplinghook.client;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

/**
 * Client key bindings for the grappling hook. Reading state happens in
 * {@link GrappleController}, which polls the physical keys directly.
 *
 * <p>{@link #EXTEND} is a real {@link KeyBinding} so it shows up in (and can be
 * rebound from) the vanilla Controls screen. Disconnect, by contrast, is a bare
 * keycode rather than a {@code KeyBinding}: 1.7.10 keeps a single
 * keycode&rarr;binding map ({@code KeyBinding.hash}), and registering a binding on
 * {@code KEY_LSHIFT} would evict vanilla's sneak binding from that slot, so
 * {@code keyBindSneak.pressed} would never update again -- silently killing sneak
 * and creative-flight descent. Polling the raw key instead lets disconnect and
 * sneak share Left Shift.
 */
@SideOnly(Side.CLIENT)
public final class ModKeys
{
    /** Hold to pay out / lengthen the cable (default Left Ctrl). */
    public static final KeyBinding EXTEND = new KeyBinding(
            "key.grapplinghook.extend", Keyboard.KEY_LCONTROL, "key.categories.grapplinghook");

    /**
     * Tap to instantly drop the hook with no added momentum (Left Shift). A raw
     * keycode, not a {@link KeyBinding}, so it coexists with vanilla sneak rather
     * than evicting it from the keybind hash (see the class note).
     */
    public static final int DISCONNECT_KEY = Keyboard.KEY_LSHIFT;

    public static void register()
    {
        ClientRegistry.registerKeyBinding(EXTEND);
    }

    private ModKeys() {}
}
