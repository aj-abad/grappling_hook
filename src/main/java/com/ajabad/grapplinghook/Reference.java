package com.ajabad.grapplinghook;

/**
 * Compile-time constants for the mod. Keep the modid lowercase and stable: it
 * is also the asset namespace (assets/grapplinghook/...) and the registry prefix.
 */
public final class Reference
{
    public static final String MODID = "grapplinghook";
    public static final String NAME = "Grappling Hook Mod";
    public static final String VERSION = "0.1.0";
    public static final String ACCEPTED_MC_VERSIONS = "[1.7.10]";

    // Fully-qualified proxy class names for @SidedProxy.
    public static final String CLIENT_PROXY = "com.ajabad.grapplinghook.proxy.ClientProxy";
    public static final String SERVER_PROXY = "com.ajabad.grapplinghook.proxy.CommonProxy";

    private Reference() {}
}
