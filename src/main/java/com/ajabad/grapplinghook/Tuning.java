package com.ajabad.grapplinghook;

/**
 * Central home for every tweakable grappling-hook constant. Kept in one place so
 * the feel can be tuned in-game without hunting through the physics code.
 *
 * <p>Units are Minecraft's: distances in blocks, speeds in blocks/tick (20 t/s).
 */
public final class Tuning
{
    // --- Flight (mirrors a fully-charged bow arrow) -------------------------
    /** Launch speed of the fired hook. Full-charge bow == 2.0 * 1.5 == 3.0. */
    public static final double LAUNCH_SPEED = 4.5D;
    /** Per-tick gravity applied to the hook in flight (vanilla arrow == 0.05). */
    public static final double ARROW_GRAVITY = 0.05D;
    /** Per-tick horizontal/vertical drag in flight (vanilla arrow == 0.99). */
    public static final double AIR_DRAG = 0.99D;
    /** Hard cap on flight time before a never-hit shot self-destructs. */
    public static final int MAX_FLIGHT_TICKS = 200;
    /** Max distance a fired hook may travel before a miss is cleaned up instantly. */
    public static final double MAX_GRAPPLE_RANGE = 64.0D;
    /** Backstop teardown distance (squared) between owner and hook. */
    public static final double MAX_RANGE_SQ = 256.0D * 256.0D;

    // --- Tether / cable -----------------------------------------------------
    /** Slack added to the measured hit distance when the cable forms. */
    public static final double LEEWAY = 1.0D;
    /** Smallest the active (player-side) segment may be reeled to. */
    public static final double MIN_ACTIVE_LENGTH = 1.0D;
    /** Upper bound on cable length (the extend control caps out here). */
    public static final double MAX_CABLE_LENGTH = 128.0D;
    /** How close to full extension counts as "taut" (for swing-jump detection). */
    public static final double TAUT_EPSILON = 0.5D;

    // --- Swing --------------------------------------------------------------
    /** Tangential acceleration WASD adds to a swing each tick. */
    public static final double SWING_ACCEL = 0.03D;

    // --- Reel-in (hold use key) / extend (hold extend key) ------------------
    /** Blocks/tick the cable shortens while reeling. */
    public static final double REEL_SPEED = 0.15D;
    /** Extra inward pull velocity applied while reeling. */
    public static final double REEL_PULL = 0.18D;
    /** Blocks/tick the cable lengthens while the extend key is held. */
    public static final double EXTEND_SPEED = 0.15D;

    // --- Yank (left-click while stuck) --------------------------------------
    /** Yank speed per block of taut cable (anchor-to-player straight pull; slack ignored). */
    public static final double YANK_K = 0.14D;
    /** Upper bound on yank speed regardless of cable length. */
    public static final double YANK_MAX_SPEED = 4D;
    /** How long (ticks) after a yank the jump-launch remains available. */
    public static final int YANK_FLIGHT_TICKS = 40;
    /** Blocks the yank aims above the anchor, to arc over lips and obstacles. */
    public static final double YANK_RISE = 2.5D;

    // --- Mob latch (hook strikes a living entity) ---------------------------
    /** Impact damage dealt to a struck mob, in half-hearts (6.0 == 3 hearts). */
    public static final float MOB_HIT_DAMAGE = 6.0F;
    /**
     * Mob-yank speed per block of straight player-to-mob cable (mirrors {@link #YANK_K},
     * kept separate so the player-yank and mob-yank can be tuned independently).
     */
    public static final double MOB_YANK_K = 0.14D;
    /** Upper bound on the mob-yank speed regardless of cable length (mirrors {@link #YANK_MAX_SPEED}). */
    public static final double MOB_YANK_MAX_SPEED = 4.0D;
    /** Blocks the mob-yank aims above the player, arcing the mob up over lips (mirrors {@link #YANK_RISE}). */
    public static final double MOB_YANK_RISE = 2.5D;
    /**
     * Smallest fraction of a tick's demanded inward pull the dragged mob must actually
     * cover; below this the mob is judged obstructed and the cable snaps. 0.25 == it
     * must close at least a quarter of the gap, else terrain is holding it back.
     */
    public static final double MOB_DRAG_MIN_PROGRESS = 0.25D;
    /**
     * Minimum inward pull (blocks) a tick must demand before the snap check runs at all,
     * so rope jitter while the mob sits at full extension never breaks the cable.
     */
    public static final double MOB_DRAG_MIN_PULL = 0.05D;

    // --- Retract (left-click while flying) ----------------------------------
    /** Blocks/tick the hook flies back toward the hand. */
    public static final double RETRACT_SPEED = 2.5D;
    /** Distance at which a retracting hook is caught and removed. */
    public static final double RETRACT_CATCH_DIST = 1.5D;
    /** Time (ticks, 20/s) over which a retracting cord eases from drooped to taut. */
    public static final double RETRACT_TAUT_TICKS = 6.0D;

    // --- Jump release -------------------------------------------------------
    /** Horizontal momentum added along the swing direction on release. */
    public static final double JUMP_BOOST = 0.5D;
    /** Upward momentum added on release (~vanilla jump velocity). */
    public static final double JUMP_UP = 0.5D;
    /**
     * Minimum tangential (swing) speed, in blocks/tick, required to swing-jump.
     * Below this a mid-air jump does nothing, so a taut-but-motionless rope can't be
     * jumped off by accident -- use the Disconnect key to drop it instead.
     */
    public static final double SWING_JUMP_MIN_SPEED = 0.2D;

    // --- Wall jump (jump while tethered against a wall) ----------------------
    /** Horizontal away-from-wall impulse; tuned to clear ~1.5 blocks. */
    public static final double WALL_JUMP_H = 0.5D;
    /** Upward impulse of a wall jump. */
    public static final double WALL_JUMP_UP = 0.5D;
    /**
     * Minimum cos(angle) between the player's horizontal look and the horizontal
     * direction to the anchor for a jump to count as a wall jump (vs a swing jump).
     * 0.5 == a ~60-degree cone: the player must be looking roughly at the wall the
     * anchor sits on. Looking away from it makes the same jump a swing jump.
     */
    public static final double WALL_JUMP_FACING_DOT = 0.5D;

    // --- Yank FoV punch (purely cosmetic) -----------------------------------
    // Vanilla smooths the FoV multiplier (~0.5/tick lerp) and clamps it to 1.5, so
    // these are over-driven and decay slowly enough for the swell to read clearly.
    /** FoV-multiplier bonus from a full-force yank (scales with yank speed). */
    public static final double FOV_PUNCH_MAX = 0.5D;
    /** Per-tick decay of the yank FoV punch back to zero. */
    public static final double FOV_PUNCH_DECAY = 0.33D;

    // --- Rendering ----------------------------------------------------------
    /** Half-thickness of the drawn cord, in blocks. */
    public static final float CORD_WIDTH = 0.05F;
    /** Cord strand colours, matching the vanilla lead/leash (RenderLiving). */
    public static final int CORD_COLOR_LIGHT = 0x7F664C; // lead's (0.5, 0.4, 0.3)
    public static final int CORD_COLOR_DARK  = 0x594735; // lead's (0.35, 0.28, 0.21)
    /** Sub-segments each cord span is split into, for the drooping curve. */
    public static final int CORD_SUBDIVISIONS = 16;
    /** Multiplier on the slack-derived droop of the anchored active span. */
    public static final double CORD_SAG_SCALE = 1.0D;
    /** Droop of the in-flight cord as a fraction of its length (a loose throw). */
    public static final double CORD_FLIGHT_SAG = 0.06D;
    /** Maximum droop depth in blocks, so long/slack spans don't sag absurdly. */
    public static final double CORD_SAG_MAX = 2.5D;
    /** How far above a block's surface the resting cord floats, in blocks. */
    public static final double CORD_GROUND_CLEARANCE = 0.06D;

    private Tuning() {}
}
