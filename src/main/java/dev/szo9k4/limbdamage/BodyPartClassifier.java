package dev.szo9k4.limbdamage;

import dev.szo9k4.limbdamage.config.LimbConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Turns a world-space hit position on a humanoid-proportioned entity into a {@link BodyPart}.
 *
 * Zone model (all as fractions of the entity's CURRENT bounding box, so crouching/babies/scaled
 * entities keep working). Horizontal offset from the body's own center axis is checked FIRST and
 * wins at every height, split into two components relative to the body's facing (yBodyRot):
 *
 *          +--+----+--+   1.0
 *          |A | HE |A |        sideways <= armMinLateral AND forward <= armMinForward
 *          |R | AD |R |                                  AND height >= headBottomFraction => HEAD
 *          +--+----+--+   0.78          sideways > armMinLateral OR forward > armMinForward
 *          |A | BO |A |                                  (at any height above legsTopFraction)
 *          |R | DY |R |                                                             => ARMS
 *          +--+----+--+   0.42
 *          |     LEGS  |        sideways/forward both within threshold, height <= legsTopFraction
 *          +-----------+   0.0                                                       => LEGS
 *
 * v1.0.3 briefly replaced this with a single omnidirectional radial distance (any direction,
 * same 0.24 threshold), to also catch zombie-family mobs raising both arms FORWARD rather than
 * hanging them at the sides. Real server logs from testing proved that wrong: a raycast can only
 * ever land on whichever surface faces the shooter, never the entity's true geometric depth
 * center — so EVERY front-facing hit, including a dead-center headshot, carries a baseline
 * "forward" offset roughly equal to half the hitbox's depth (~0.3 for a standard 0.6-wide
 * humanoid). A single 0.24 threshold applied to total radial distance was smaller than that
 * baseline, so it fired on every single hit regardless of aim — confirmed by a test session where
 * all ~20 logged hits classified ARMS, including ones aimed at the exact top of the head.
 *
 * v1.0.4 fixes this by keeping the two components SEPARATE with DIFFERENT thresholds:
 *  - "sideways" (perpendicular to yBodyRot): the original, empirically-validated 1.0.2 signal.
 *    Not affected by which face got hit, since it doesn't correlate with shooter direction.
 *  - "forward" (along yBodyRot): deliberately a much LOOSER threshold (armMinForward, defaults
 *    well above the ~0.3 front-surface baseline) — only fires for a limb that's CLEARLY extended
 *    well past the torso/head surface (a raised/reaching arm), not just "whichever surface faced
 *    the shooter this time."
 */
public final class BodyPartClassifier {

    /** Reference bounding-box width the armMinLateral/armMinForward config values are calibrated
     *  against (the vanilla player's). Wider/narrower humanoids scale the thresholds proportionally. */
    private static final double REFERENCE_WIDTH = 0.6;

    /** Full breakdown behind a classification, for debug logging (LOG_HITS) — lets you see the
     *  actual numbers instead of just the final zone when something looks wrong. All fields other
     *  than part/boxMinY/boxMaxY are NaN when the pose was non-upright (BODY by decline, not by
     *  measurement). */
    public record Diagnosis(BodyPart part, double heightFraction, double sidewaysOffset,
                             double forwardOffset, double sidewaysThreshold, double forwardThreshold,
                             double boxMinY, double boxMaxY) {
    }

    private BodyPartClassifier() {
    }

    /** Whether this entity should get locational damage at all, per config. */
    public static boolean isEligible(LivingEntity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        String idString = id.toString();

        if (LimbConfig.BLACKLIST_ENTITY_IDS.get().contains(idString)) {
            return false;
        }
        if (entity instanceof Player) {
            return LimbConfig.AFFECT_PLAYERS.get();
        }
        if (!LimbConfig.AFFECT_MOBS.get()) {
            return false;
        }
        if (LimbConfig.EXTRA_ENTITY_IDS.get().contains(idString)) {
            return true;
        }
        if (!LimbConfig.AUTO_DETECT_HUMANOID.get()) {
            return false;
        }
        // "Player-like proportions": the type's REGISTERED base dimensions, not the current
        // bounding box — a crouching or swimming zombie villager is still a humanoid.
        // EntityType#getDimensions() is that registered standing size, independent of pose.
        var dims = entity.getType().getDimensions();
        return dims.width >= 0.45f && dims.width <= 0.75f
                && dims.height >= 1.5f && dims.height <= 2.2f;
    }

    /**
     * Classifies a world-space hit position. Returns BODY (the 1.0-multiplier neutral zone by
     * default config) when the pose makes height-fraction zones meaningless — a swimming/
     * crawling/flying/sleeping humanoid is horizontal, so "high on the box" no longer means
     * "head"; guessing there would be worse than declining to.
     */
    public static BodyPart classify(LivingEntity target, Vec3 hitPos) {
        return diagnose(target, hitPos).part();
    }

    /** Same classification, with the intermediate numbers exposed for debugging. */
    public static Diagnosis diagnose(LivingEntity target, Vec3 hitPos) {
        Pose pose = target.getPose();
        boolean upright = pose == Pose.STANDING || pose == Pose.CROUCHING;

        AABB box = target.getBoundingBox();
        double height = box.maxY - box.minY;

        if (!upright || height <= 1.0E-4) {
            return new Diagnosis(BodyPart.BODY, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, box.minY, box.maxY);
        }

        // Clamp into the box first: precise hit points from Accurate Hitboxes can legitimately
        // sit OUTSIDE the vanilla bounding box (limbs stick out past it), which is fine
        // horizontally but the height fraction should still be measured within 0..1.
        double heightFraction = Math.max(0.0, Math.min(1.0, (hitPos.y - box.minY) / height));

        double centerX = (box.minX + box.maxX) * 0.5;
        double centerZ = (box.minZ + box.maxZ) * 0.5;
        double relX = hitPos.x - centerX;
        double relZ = hitPos.z - centerZ;

        // Body yaw (yBodyRot), not head yaw. Right vector for yaw ψ is (cos ψ, 0, sin ψ) in
        // Minecraft's convention; forward is that rotated 90°: (-sin ψ, 0, cos ψ).
        double yawRadians = Math.toRadians(target.yBodyRot);
        double rightX = Math.cos(yawRadians);
        double rightZ = Math.sin(yawRadians);
        double forwardX = -rightZ;
        double forwardZ = rightX;

        double sidewaysOffset = Math.abs(relX * rightX + relZ * rightZ);
        double forwardOffset = Math.abs(relX * forwardX + relZ * forwardZ);

        double width = box.maxX - box.minX;
        double sidewaysThreshold = LimbConfig.ARM_MIN_LATERAL.get() * (width / REFERENCE_WIDTH);
        double forwardThreshold = LimbConfig.ARM_MIN_FORWARD.get() * (width / REFERENCE_WIDTH);

        BodyPart part;
        if (sidewaysOffset > sidewaysThreshold || forwardOffset > forwardThreshold) {
            part = BodyPart.ARMS;
        } else if (heightFraction >= LimbConfig.HEAD_BOTTOM_FRACTION.get()) {
            part = BodyPart.HEAD;
        } else if (heightFraction <= LimbConfig.LEGS_TOP_FRACTION.get()) {
            part = BodyPart.LEGS;
        } else {
            part = BodyPart.BODY;
        }
        return new Diagnosis(part, heightFraction, sidewaysOffset, forwardOffset,
                sidewaysThreshold, forwardThreshold, box.minY, box.maxY);
    }

    public static double multiplier(BodyPart part) {
        return switch (part) {
            case HEAD -> LimbConfig.HEAD_MULTIPLIER.get();
            case BODY -> LimbConfig.BODY_MULTIPLIER.get();
            case ARMS -> LimbConfig.ARMS_MULTIPLIER.get();
            case LEGS -> LimbConfig.LEGS_MULTIPLIER.get();
        };
    }
}
