package dev.szo9k4.limbdamage.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * Common config — damage is computed server-side (LivingHurtEvent), so only the server's copy
 * matters for gameplay; keeping it a COMMON (not SERVER) config means the same file also exists
 * on clients for singleplayer/LAN without any syncing complexity.
 */
public final class LimbConfig {

    public static final ForgeConfigSpec SPEC;

    // Per-part damage multipliers
    public static final ForgeConfigSpec.DoubleValue HEAD_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue BODY_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue ARMS_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue LEGS_MULTIPLIER;

    // Which entities get locational damage
    public static final ForgeConfigSpec.BooleanValue AFFECT_PLAYERS;
    public static final ForgeConfigSpec.BooleanValue AFFECT_MOBS;
    public static final ForgeConfigSpec.BooleanValue AUTO_DETECT_HUMANOID;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXTRA_ENTITY_IDS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BLACKLIST_ENTITY_IDS;

    // Zone geometry (fractions of the entity's current bounding-box height / lateral distance)
    public static final ForgeConfigSpec.DoubleValue LEGS_TOP_FRACTION;
    public static final ForgeConfigSpec.DoubleValue HEAD_BOTTOM_FRACTION;
    public static final ForgeConfigSpec.DoubleValue ARM_MIN_LATERAL;
    public static final ForgeConfigSpec.DoubleValue ARM_MIN_FORWARD;

    // Accurate Hitboxes per-bone (OBB) classification — only relevant when AH is installed
    public static final ForgeConfigSpec.BooleanValue USE_OBB_CLASSIFICATION;
    public static final ForgeConfigSpec.DoubleValue OBB_HEAD_Y_TOLERANCE;
    public static final ForgeConfigSpec.DoubleValue OBB_LEG_HEIGHT_FRACTION;
    public static final ForgeConfigSpec.DoubleValue OBB_ARM_OFFSET;

    // Which damage kinds participate
    public static final ForgeConfigSpec.BooleanValue APPLY_TO_MELEE;

    // Debug
    public static final ForgeConfigSpec.BooleanValue LOG_HITS;
    public static final ForgeConfigSpec.BooleanValue ANNOUNCE_KILLS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("Locational damage multipliers, COD-style. Final damage = incoming damage",
                "x the multiplier of whichever body zone the hit landed in. Only applies when a",
                "hit LOCATION is known (bullets, arrows and other projectiles always have one;",
                "melee gets one from the attacker's aim ray if applyToMelee is on). Explosions,",
                "fire, poison, fall damage etc. have no meaningful hit location and are never",
                "modified.")
                .push("multipliers");

        HEAD_MULTIPLIER = builder
                .comment("Head zone. Default 1.0 = unchanged, deliberately: TACZ already applies",
                        "its own headshot multiplier before this mod ever sees the damage, so",
                        "anything above 1.0 here would stack ON TOP of that.")
                .defineInRange("head", 1.0, 0.0, 10.0);

        BODY_MULTIPLIER = builder
                .comment("Torso zone (the center strip between the arm zones).")
                .defineInRange("body", 1.0, 0.0, 10.0);

        ARMS_MULTIPLIER = builder
                .comment("Arm zones (torso-height, offset to the sides of the torso).")
                .defineInRange("arms", 0.8, 0.0, 10.0);

        LEGS_MULTIPLIER = builder
                .comment("Leg zone (everything below the torso).")
                .defineInRange("legs", 0.8, 0.0, 10.0);

        builder.pop();
        builder.comment("Which entities take locational damage.").push("entities");

        AFFECT_PLAYERS = builder
                .comment("Apply locational damage to players.")
                .define("affectPlayers", true);

        AFFECT_MOBS = builder
                .comment("Apply locational damage to non-player mobs (only those that qualify via",
                        "autoDetectHumanoid or extraEntityIds below).")
                .define("affectMobs", true);

        AUTO_DETECT_HUMANOID = builder
                .comment("Automatically treat mobs with player-like proportions as humanoid:",
                        "bounding box width 0.45-0.75 and height 1.5-2.2 blocks. This covers",
                        "zombies, skeletons, villagers, pillagers, piglins, drowned, husks,",
                        "strays, vindicators, evokers, witches, zombified piglins, and most",
                        "modded humanoids, without listing them all by hand.")
                .define("autoDetectHumanoid", true);

        EXTRA_ENTITY_IDS = builder
                .comment("Entity ids to ALWAYS treat as humanoid regardless of proportions,",
                        "e.g. [\"minecraft:iron_golem\", \"somemod:soldier\"]. The standard",
                        "humanoid zone fractions are applied to their bounding box.")
                .defineList("extraEntityIds", List.of(), entry -> entry instanceof String);

        BLACKLIST_ENTITY_IDS = builder
                .comment("Entity ids to NEVER apply locational damage to, even if they match the",
                        "auto-detection. Takes priority over everything else.")
                .defineList("blacklistEntityIds", List.of(), entry -> entry instanceof String);

        builder.pop();
        builder.comment("Zone geometry, as fractions of the target's CURRENT bounding box — so the",
                "same numbers keep working for crouching players (shorter box), baby zombies",
                "(much smaller box), and scaled entities (Pehkui etc.).",
                "Vanilla player reference (1.8 tall): legs are model-wise 0..0.75 (fraction",
                "0.417), torso 0.75..1.5 (0.417..0.833), head 1.5..1.8 (0.833+). The default",
                "headBottomFraction of 0.78 (y=1.404) deliberately sits slightly below the model",
                "head so it lines up with where TACZ draws ITS headshot line (eye height +-0.25 =",
                "y 1.37..1.87) — that way a bullet TACZ calls a headshot lands in this mod's HEAD",
                "zone too, and the two systems never disagree about the same shot.")
                .push("geometry");

        LEGS_TOP_FRACTION = builder
                .comment("Hits at or below this fraction of the bounding-box height are LEGS.")
                .defineInRange("legsTopFraction", 0.42, 0.0, 1.0);

        HEAD_BOTTOM_FRACTION = builder
                .comment("Hits at or above this fraction of the bounding-box height are HEAD.")
                .defineInRange("headBottomFraction", 0.78, 0.0, 1.0);

        ARM_MIN_LATERAL = builder
                .comment("Checked at EVERY height, not just the torso band: hits whose SIDEWAYS",
                        "distance from the body's facing axis (yBodyRot) exceeds this (in blocks,",
                        "scaled by boundingBoxWidth/0.6) count as ARMS, even inside the head or",
                        "legs height range — a shoulder can sit at head height without being",
                        "anywhere near the head laterally, so this always wins over the height",
                        "band. 0.24 ~= the vanilla player torso's half-width (0.25), so anything",
                        "landing outside the torso silhouette to either side is an arm. With",
                        "Accurate Hitboxes installed, arm hits land at lateral 0.25..0.5 (arms",
                        "visually stick out past the vanilla bounding box, and AH actually",
                        "detects hits out there); without it, hits clip to the vanilla box so the",
                        "arm zone is the narrow 0.24..0.3 strip at the box's edge. Only measures",
                        "sideways offset — see armMinForward for the forward/backward axis.")
                .defineInRange("armMinLateral", 0.24, 0.0, 2.0);

        ARM_MIN_FORWARD = builder
                .comment("Same idea as armMinLateral, but for the FRONT/BACK axis (along the",
                        "body's facing) instead of sideways. Deliberately much LOOSER than",
                        "armMinLateral by default: any hit on the front (or back) of a torso/head",
                        "is naturally offset from the box's center by roughly half the hitbox's",
                        "depth (~0.3 for a standard 0.6-wide humanoid) simply because a raycast",
                        "can only ever land on whichever surface faces the shooter, never the",
                        "true geometric center — that's not a limb, it's just which side got hit.",
                        "This threshold needs to sit safely ABOVE that baseline so it only fires",
                        "for a limb CLEARLY extended forward past the torso/head surface (e.g. a",
                        "zombie-family mob's raised arm), not every ordinary frontal hit. If",
                        "unsure, leave this alone and tune armMinLateral first with logHits=true;",
                        "only lower this if you have log evidence of a specific forward-reaching",
                        "limb being misread as HEAD/BODY.")
                .defineInRange("armMinForward", 0.55, 0.0, 2.0);

        builder.pop();
        builder.push("obbClassification");

        USE_OBB_CLASSIFICATION = builder
                .comment("When Accurate Hitboxes is installed AND has real per-bone geometry for",
                        "the target this tick, classify directly from that (see",
                        "ObbBodyPartClassifier) instead of the geometry.* heuristic above — no",
                        "flat bounding box, no reconstructing position from a raycast-clipped",
                        "surface point, just the actual bone that was hit. Falls back to the",
                        "geometry.* heuristic whenever OBB data isn't available (AH not",
                        "installed, or nothing cached for this entity/tick yet). Set false to",
                        "always use the geometry.* heuristic, even with AH installed.")
                .define("useObbClassification", true);

        OBB_HEAD_Y_TOLERANCE = builder
                .comment("A hit bone counts as HEAD if it's roughly cube-shaped (see the fixed",
                        "1.4 width/height/depth ratio in ObbBodyPartClassifier) AND its world Y",
                        "center is within this many blocks of the highest cube-shaped bone on the",
                        "same skeleton — i.e. \"the topmost cubic bone, or close enough to it\".",
                        "Two cube-ish bones close in height (a head plus a raised cubic-ish hand,",
                        "say) is the main reason this isn't just \"the single tallest cubic bone",
                        "wins\": some slack is needed, but not so much it swallows a genuinely",
                        "separate limb.")
                .defineInRange("obbHeadYTolerance", 0.35, 0.0, 2.0);

        OBB_LEG_HEIGHT_FRACTION = builder
                .comment("A non-head bone counts as LEGS if its world Y center falls in the",
                        "bottom this fraction of the skeleton's OWN vertical span (highest bone",
                        "center to lowest bone center on this entity right now) — relative to the",
                        "actual rig, not an external bounding box, so this stays correct through",
                        "crouching, scaling, baby variants, whatever the skeleton is actually",
                        "doing this tick.")
                .defineInRange("obbLegHeightFraction", 0.35, 0.0, 1.0);

        OBB_ARM_OFFSET = builder
                .comment("A non-head, non-leg bone counts as ARMS if its world-space horizontal",
                        "(X/Z) distance from the head bone's own horizontal position exceeds",
                        "this many blocks, else BODY. This compares bone CENTER to bone CENTER",
                        "(both real 3D points from AH's own bone transforms) rather than a",
                        "raycast-clipped surface point against a box center, so it doesn't carry",
                        "the \"which face got hit\" baseline offset that geometry.armMinForward",
                        "has to work around — this can be tuned much tighter. Starting value is a",
                        "reasonable guess, not yet validated against real combat logs; tune with",
                        "logHits=true like everything else here.")
                .defineInRange("obbArmOffset", 0.2, 0.0, 2.0);

        builder.pop();
        builder.push("damageKinds");

        APPLY_TO_MELEE = builder
                .comment("Also apply locational damage to melee hits, using the attacker's aim",
                        "ray against the target to estimate the hit location. Projectiles don't",
                        "need this — they always carry an exact hit position.")
                .define("applyToMelee", true);

        builder.pop();
        builder.push("debug");

        LOG_HITS = builder
                .comment("Log every classified hit (part, multiplier, before/after damage) to the",
                        "server console. For testing zone boundaries; noisy, keep off normally.")
                .define("logHits", false);

        ANNOUNCE_KILLS = builder
                .comment("Broadcast an extra chat line on death naming the body part, attacker,",
                        "weapon (attacker's main-hand item at the moment of the hit), damage, and",
                        "multiplier of the killing blow — alongside vanilla's own death message,",
                        "never replacing it. Also needed for the killing blow to be recorded at",
                        "all, since only a classified hit ever gets logged here.")
                .define("announceKills", true);

        builder.pop();

        SPEC = builder.build();
    }

    private LimbConfig() {
    }
}
