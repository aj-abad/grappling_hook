# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A **Minecraft 1.7.10 Forge** mod adding a single item — a grappling hook — with full
swing physics: fire a hook that sticks into blocks or latches onto mobs, then swing,
reel, rappel, yank, wall-jump, and swing-jump off the resulting cable. All gameplay
hangs off one item and one entity.

> The `README.md` is **stale** — it describes the repo as an empty boilerplate "with no
> content yet." That hasn't been true for a while; the mod below is fully implemented.
> Trust the code and this file over the README.

## Build & run

Java 8 is the **mod** toolchain; Gradle itself runs on any JDK 8–21. RetroFuturaGradle
(RFG) provisions the deobfuscated Forge 1.7.10 dev environment and can auto-fetch Java 8.

```powershell
.\gradlew.bat build        # -> build\libs\grapplinghook-<version>.jar  (the loadable mod)
.\gradlew.bat runClient    # dev client with the mod loaded
.\gradlew.bat runServer    # dev dedicated server
```

- The reobfuscated `grapplinghook-<version>.jar` is what goes in `.minecraft/mods/`. The
  `-dev.jar` is deobfuscated and only runs inside the Gradle dev environment.
- **First build is slow**: RFG decompiles Minecraft (needs `-Xmx4G`, already set in `gradle.properties`).
- **No automated test suite exists.** Verification is manual: `runClient`, craft the hook,
  and try the mechanics. There is nothing to "run a single test" against.
- After a build, the exact 1.7.10 MCP-mapped **vanilla source** is at
  `build/rfg/minecraft-src/java/` — read it there when you need to understand a `func_*`
  call or vanilla behavior (e.g. `EntityArrow`, `RenderFish`, `RenderLiving`).

## Architecture: the three physics domains (read this first)

The hardest thing to keep straight is **what runs where**. Physics is split across three
domains by necessity, and getting this wrong causes desync, jitter, or rubber-banding:

1. **Flight & retract — both sides.** `EntityGrapplingHook.onUpdate` integrates flight
   (`tickFlying`) and retract (`tickRetracting`) on **client and server**, like a vanilla
   arrow. The client *predicts* the parabola; the server is authoritative for the
   FLYING→STUCK transition, mob hits, and entity removal.
2. **Swing / reel / extend / yank / wall-jump — client-only.** `client/GrappleController`
   moves `mc.thePlayer` directly each client tick. The player's own movement is already
   client-authoritative in 1.7.10, so the rope constraint just edits local motion/position.
   It reports back to the server only the discrete intents the server must enact (drop the
   hook, retract, mob reel/yank).
3. **Mob-latch drag — server-authoritative.** `EntityGrapplingHook.tickLatched`/`yankTarget`
   move *another* entity (the latched mob), which can't be client-predicted, so they run
   server-side. The client just rides the cord's far end onto the mob for rendering.

**Networking is client→server intents only** (`network/`). There is no server→client custom
sync beyond ordinary entity tracking + DataWatcher. Message handlers run on the **Netty
thread**, which must not touch world state in this 1.7.10 build (predates `IThreadListener`),
so every handler enqueues a `Runnable` via `ServerEventHandler.schedule(...)`; the queue is
drained on the main server thread each `ServerTickEvent`.

## The hook entity & its state machine

`entity/EntityGrapplingHook` is the shared, synced anchor. State lives in a DataWatcher byte
and drives `onUpdate`'s switch:

| State (`DW_STATE`=16) | Value | Behavior |
|---|---|---|
| `STATE_FLYING` | 0 | Arrow-style parabola; raycasts blocks + scans mobs each tick |
| `STATE_STUCK` | 1 | Motion zeroed; pinned to the synced anchor; client drives swing |
| `STATE_RETRACTING` | 2 | Reels back to the owner's hand, tail-first; caught near the hand |
| `STATE_LATCHED` | 3 | Bit a living mob; server drags it on a leash |

**DataWatcher slots:** `16` state, `17` owner entity id, `18/19/20` anchor X/Y/Z (floats),
`21` latched mob's entity id (or −1). The anchor is synced explicitly because a motionless
STUCK entity sends no position packets — entity tracking alone can't carry it.

Two **sync gotchas** are load-bearing (don't "simplify" them away):

- **Exact launch velocity via `IEntityAdditionalSpawnData`.** `LAUNCH_SPEED` is `4.5` b/t, but
  the vanilla spawn/velocity packets clamp each component to ±3.9 b/t. So the true velocity is
  written in `writeSpawnData`/`readSpawnData`; the client then integrates the *identical*
  parabola and needs no per-tick position correction. Entity is registered like an arrow
  (`trackingRange 64, updateFrequency 20, sendVelocityUpdates false`) — see `ModEntities`. The
  per-tick correction this avoids *was* the old flight jitter.
- **`setPositionAndRotation2` is overridden to pin a STUCK hook.** The tracker force-resyncs a
  motionless entity (~every 3s); vanilla's apply step runs collision resolution that ejects the
  block-embedded hook onto the surface, then `onUpdate` snaps it back next tick → recurring
  vertical jitter. The override pins it to the synced anchor and skips vanilla handling.

**Teardown** (any of these resets the held stack to primed and removes the hook):
owner invalid / out of `MAX_RANGE_SQ`, flight exceeds `MAX_FLIGHT_TICKS` or `MAX_GRAPPLE_RANGE`,
player death / item toss / logout (`ServerEventHandler`), or an explicit release/disconnect.

## Item states: primed ↔ fired

`item/ItemGrapplingHook` carries state in the **stack metadata** (`setHasSubtypes(true)`, not
durability): `META_PRIMED`=0 (ready, `hookPrimed.png`), `META_FIRED`=1 (spent, `hookFired.png`).
Right-click fires only a *primed* hook (plays `grapplinghook:fire`, spawns the entity server-side,
flips the stack to fired). The icon swaps on metadata.

Because a hook entity never persists its owner, a still-"fired" stack on disk is always stale.
The fired→primed reset is therefore enforced in **several places** — keep them consistent if you
touch the lifecycle: entity teardown (`resetOwnerStack`), `ServerEventHandler` (death/toss/logout
+ a symmetric **login** reset for stacks saved mid-grapple), and the client release paths
(`primeHeldHookLocally` for instant feedback before the server confirms).

## Player actions (the mechanics)

All client triggers live in `GrappleController.onClientTick` / `onLeftClick`. "Mid-air" means
airborne this tick *and* last tick (rejects the ground-jump frame).

- **Fire** — right-click a primed hook (`ItemGrapplingHook.onItemRightClick`).
- **Swing + pump** — when STUCK and airborne, `applyConstraintAndSwing` clamps the body center
  onto a sphere of `activeLength` around the active pivot via `moveEntity` (respects block
  collision), strips only *outward* velocity (gravity becomes a swing), and `applySwing` adds a
  tangential WASD push (`SWING_ACCEL`) so you can pump/steer. On the ground the rope only caps
  distance (no pump).
- **Reel in** — hold the **use key** while STUCK: `applyReel` shrinks the cable down to
  `consumedLength + MIN_ACTIVE_LENGTH`; the constraint drags the player inward.
- **Extend / rappel** — hold **Extend** (default Left Ctrl) while STUCK: lengthens the cable up
  to `MAX_CABLE_LENGTH`. Only actively pays the player *downward* (rappels) when taut, airborne,
  and not creative-flying *and* the rope points down; otherwise it just banks slack.
- **Yank** — left-click while STUCK: fling toward the anchor (aim raised by `YANK_RISE`), speed
  `min(YANK_K * tautLength, YANK_MAX_SPEED)` where `tautLength` excludes spooled-out slack. Then
  enters a free **yank-flight** window (`YANK_FLIGHT_TICKS`); the hook is dropped immediately.
- **Jump-launch** — during yank-flight, jump → `launchBoost` (`JUMP_BOOST` along heading +
  `JUMP_UP` up).
- **Swing-jump** — mid-air jump while taut *and* swinging at ≥ `SWING_JUMP_MIN_SPEED`: release
  with a `launchBoost`. A taut-but-motionless rope does **not** swing-jump (use Disconnect).
- **Wall-jump** — mid-air jump while looking into any adjacent solid wall (cone set by
  `WALL_JUMP_FACING_DOT`, probed on all 4 cardinals so corners work): kick off that wall
  (`WALL_JUMP_H` out + `WALL_JUMP_UP` up) with the cable **still attached** (turns into an arc).
- **Disconnect** — tap **Left Shift**: instant drop, momentum untouched (good for stepping onto
  a ledge mid-climb).
- **Retract** — left-click while the hook is still FLYING: sends `MsgRetract`; server flips to
  RETRACTING and the hook reels home.

### Mob latch

If the flight ray passes through a living mob's (expanded) box before any block, the hook
**latches** instead of sticking (`findHitMob`/`onHitMob`): deals `MOB_HIT_DAMAGE` (6.0 = 3
hearts), and if the mob survives, leashes it at the current gap + `LEEWAY`. While latched
(server-side `tickLatched`): the mob is kept within `cableLength` of the player; holding **use**
reels it in (forwarded as `MsgReel`, sent only on edges); left-click **yanks** the mob toward
the player (`MsgYankMob` → `yankTarget`, mirrors the player yank with `MOB_YANK_*`). If terrain
stops the mob from closing the gap (progress < `MOB_DRAG_MIN_PROGRESS` of a demanded pull ≥
`MOB_DRAG_MIN_PULL`), the cable **snaps**.

## The cable model (wrap / unwrap around geometry)

`client/CableModel` represents the rope as a polyline of **pivots**: index 0 is the fixed
anchor, the last is the "active" pivot (the current swing fulcrum nearest the player),
intermediates are corners the cord has wrapped around. `cableLength` is the total budget;
`consumedLength()` is spent on fixed wrapped segments; `activeLength()` is what's left for the
player-side span. Each tick `update()` first **unwraps** corners the player can see past, then
**wraps** a new corner if the active segment is obstructed.

`util/BlockGeometry` reads the **real, state-aware** collision shape so wraps land on the true
corners of slabs/stairs/fences (not the block grid), and the rendered cord rests on the actual
top surface. NB (per project memory): 1.7.10's `rayTraceBlocks` already honors slab/stair/fence
shapes — only the corner-snap needed the shape-aware fix.

> Wrapped bends are **local to the swinging player only**. Remote players see a correct straight
> hand→anchor cord (renderer fallback); the wrapped sync was intentionally omitted (cosmetic,
> LAN-only). See the NOTE in `ModNetwork`.

## Rendering

`client/render/RenderGrapplingHook` draws the hook as a vanilla-style arrow (ported from
`RenderArrow`) and the cord from the held item's tip (hand maths ported from `RenderFish`,
works in 1st/3rd person). The cord is a thick two-quad tube through the pivots, **lit per cord
point** from the world (so a buried hook doesn't render the cord black) and colored in the
vanilla lead/leash browns. Droop is physically motivated: an anchored span sags by its **slack**
(taut → straight), in-flight cord gets a small loose droop, retracting eases droop→taut, and a
freshly-stuck cord eases flight-droop→slack-sag (smoothstep over `*_TICKS` constants). The
drooping belly is lifted to rest on the floor via `BlockGeometry.surfaceTopBelow`.

`renderSlack` and `renderPivots` are client-only fields on the entity, populated by the
controller each tick (`null` ⇒ draw a straight cord).

## Input & keybinds (`client/ModKeys`)

- **Extend** is a real `KeyBinding` (default Left Ctrl) — shows in the vanilla Controls screen.
- **Disconnect** is a **raw keycode** (`Keyboard.KEY_LSHIFT`), *not* a `KeyBinding`. 1.7.10 keeps
  one `keycode→binding` map (`KeyBinding.hash`); registering a binding on Left Shift would evict
  vanilla sneak from that slot and silently break sneak + creative descent. The controller polls
  the physical key instead (and reads Extend via its raw keycode too) so they coexist with vanilla.

## Tuning constants

**`Tuning.java` is the single source of truth and is exhaustively commented** — read it directly;
don't trust hardcoded values copied elsewhere. The quick index below is grouped as the file is;
values drift, so confirm against the source. The gameplay "feel" is entirely in this file by
design (tweak here, not in the physics code).

| Group | Constants | Notes |
|---|---|---|
| Flight | `LAUNCH_SPEED`(4.5), `ARROW_GRAVITY`(0.05), `AIR_DRAG`(0.99), `MAX_FLIGHT_TICKS`(200), `MAX_GRAPPLE_RANGE`(64), `MAX_RANGE_SQ` | `LAUNCH_SPEED` > the 3.9 b/t packet clamp → spawn-data velocity |
| Tether | `LEEWAY`(1.0), `MIN_ACTIVE_LENGTH`(1.0), `MAX_CABLE_LENGTH`(128), `TAUT_EPSILON`(0.5) | |
| Swing | `SWING_ACCEL`(0.03) | tangential WASD push |
| Reel/extend | `REEL_SPEED`(0.15), `EXTEND_SPEED`(0.15), `REEL_PULL`(0.18) | **`REEL_PULL` is currently unused** |
| Yank | `YANK_K`(0.14), `YANK_MAX_SPEED`(4.0), `YANK_FLIGHT_TICKS`(40), `YANK_RISE`(2.5) | |
| Mob latch | `MOB_HIT_DAMAGE`(6.0), `MOB_YANK_K`, `MOB_YANK_MAX_SPEED`, `MOB_YANK_RISE`, `MOB_DRAG_MIN_PROGRESS`(0.25), `MOB_DRAG_MIN_PULL`(0.05) | mirror the player-yank constants, tuned separately |
| Retract | `RETRACT_SPEED`(2.5), `RETRACT_CATCH_DIST`(1.5), `RETRACT_TAUT_TICKS`(6) | |
| Jump | `JUMP_BOOST`(0.5), `JUMP_UP`(0.5), `SWING_JUMP_MIN_SPEED`(0.2) | |
| Wall jump | `WALL_JUMP_H`(0.5), `WALL_JUMP_UP`(0.5), `WALL_JUMP_FACING_DOT`(0.5) | dot = ~60° facing cone |
| FoV punch | `FOV_PUNCH_MIN/MAX/REF_SPEED/DECAY` | cosmetic; **decay must stay slow** (see below) |
| Rendering | `CORD_WIDTH`, `CORD_COLOR_LIGHT/DARK`, `CORD_SUBDIVISIONS`, `CORD_*_SAG*`, `CORD_FLIGHT_SAG`, `CORD_GROUND_CLEARANCE`, `CORD_SETTLE_TICKS` | |

**FoV punch caveat** (`client/ClientEffects`): vanilla feeds `FOVUpdateEvent.newfov` through a
0.5/tick smoothing filter (clamped to 1.5×). The punch only becomes visible if it stays high for
several ticks, so `FOV_PUNCH_DECAY` is deliberately slow — a fast decay collapses the punch before
the filter can lift it and it looks dead.

## Registration & wiring

- Entry point `ModGrapplingHook` (`@Mod`) dispatches the FML lifecycle to a `@SidedProxy` pair.
- `proxy/CommonProxy` is server-safe and **must not** reference (or transitively load) any
  client-only type (`net.minecraft.client.*`, `Render*`, `org.lwjgl.input.*`). Those live in
  `proxy/ClientProxy`. This split is what lets the dedicated server run.
- `preInit` → `ModItems.register()` (item + crafting recipe), `ModEntities.register()`,
  `ModNetwork.init()`. `init` registers event handlers (`ServerEventHandler` on both buses;
  client adds `ClientInputHandler` + `ClientEffects`).
- **Recipe** (`ModItems`): I=iron, P=piston, S=string, B=bow — `["II ", "IPS", " SB"]`.
- **Custom sound** (per project memory): `fire.ogg` lives in `assets/grapplinghook/sounds/`,
  declared in `sounds.json`, played as `"grapplinghook:fire"`. `SoundHandler` auto-loads the
  domain — no registration call needed.

## Conventions & gotchas

- **Obfuscation names**: this is MCP-mapped 1.7.10, so some vanilla methods are still SRG names
  (`func_147447_a` = world raycast, `func_147461_a` = block collision boxes). They're correct, not
  typos.
- **Dead code, currently unused**: `Tuning.REEL_PULL` (defined, never read) and `util/Vector3`
  (referenced only by itself; the live code uses Minecraft's `Vec3`). Don't assume either is wired
  in — remove or wire them deliberately.
- `modid` is `grapplinghook` (lowercase) and doubles as the asset namespace + registry prefix;
  keep it stable. Versions for `mcmod.info` are injected at build time from `build.gradle.kts`.
