package dev.szo9k4.limbdamage.compat;

import dev.szo9k4.limbdamage.BodyPart;
import dev.szo9k4.limbdamage.config.LimbConfig;
import net.devra.accuratehitboxes.network.BlueprintManager;
import net.devra.accuratehitboxes.network.DynamicHitboxManager;
import net.devra.accuratehitboxes.util.BlueprintPose;
import net.devra.accuratehitboxes.util.IAccurateEntity;
import net.devra.accuratehitboxes.util.OrientedBoundingBox;
import net.devra.accuratehitboxes.util.SharedMemoryBridge;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Optional;

/**
 * Classifies a TACZ bullet hit directly from Accurate Hitboxes' real per-bone
 * {@link OrientedBoundingBox} data — the same skeleton AH itself raycasts against to find the
 * exact hit point — instead of BodyPartClassifier's approach of reconstructing an approximate
 * zone from a single flat hit position and the entity's external bounding box.
 *
 * Origin: ported from a companion prototype ("tacz-ahb-compat", same author, provided as a
 * reference) that solved the SAME "shoulder counts as headshot" problem this project has been
 * chasing via BodyPartClassifier — but by using real bone shape instead of tuning position
 * thresholds. Its core idea (ObbHeadClassifier): a head bone is roughly CUBIC (width ≈ height ≈
 * depth), and on a normal humanoid rig it's also the highest cubic bone on the whole skeleton —
 * two properties no other bone shares, so no height/lateral/forward tuning is needed to find it.
 *
 * Fixed two API drifts against the AH 2.3.1 jar this project builds against (the prototype
 * predates them):
 *  - OrientedBoundingBox's constructor gained a third parameter (Vec3 offset) — the 2-arg call
 *    from the prototype doesn't compile against 2.3.1 at all.
 *  - BlueprintManager.serverBlueprints is keyed by the STRING from BlueprintPose.getCacheKey(),
 *    not a raw ResourceLocation — the prototype's raw-ResourceLocation lookup always returned
 *    null (silently, since Map#get(Object) type-erases and just returns null on a
 *    never-.equals() key rather than failing to compile).
 * Cross-checked both against Accurate Hitboxes' OWN TaczEntityUtilMixin (decompiled from the same
 * 2.3.1 jar), which uses the exact 3-tier lookup order reproduced in {@link #resolveObbs}.
 *
 * Generalized from the prototype in two ways:
 *  - A full 4-zone classification (HEAD/BODY/ARMS/LEGS), not just a headshot boolean — LEGS by
 *    the hit bone's position in the skeleton's OWN vertical span (not an external box), ARMS by
 *    horizontal distance from the head bone's own world position (bone center to bone center —
 *    no "which face got hit" baseline offset to work around, unlike BodyPartClassifier's forward
 *    axis, because both points come from real bone transforms rather than a raycast-clipped
 *    surface).
 *  - Works for any entity with bone data, not just {@code Player} — the prototype's Player-only
 *    restriction looks like scope-limiting for its narrower PvP-headshot purpose, not a technical
 *    limit; AH's own extraction (Gecko/vanilla model bones) is entity-agnostic.
 *
 * Every caller MUST check {@link dev.szo9k4.limbdamage.LimbCompat#ACCURATE_HITBOXES_LOADED}
 * first — this class references AH types directly (compileOnly dependency) and must never be
 * loaded when AH isn't present.
 */
public final class ObbBodyPartClassifier {

    private static final float CUBIC_MAX_RATIO = 1.4f;

    public record Result(BodyPart part, Vec3 hitPos) {
    }

    private ObbBodyPartClassifier() {
    }

    /** Null if AH has no bone data available for this entity right now, or the ray doesn't
     *  land on any of its bones — caller should fall back to BodyPartClassifier in that case. */
    public static Result classify(Entity target, Projectile bullet, Vec3 startVec, Vec3 endVec) {
        List<OrientedBoundingBox> obbs = resolveObbs(target, bullet);
        if (obbs == null || obbs.isEmpty()) {
            return null;
        }

        double closestDistSq = Double.MAX_VALUE;
        Vec3 closestHit = null;
        OrientedBoundingBox closestObb = null;
        for (OrientedBoundingBox obb : obbs) {
            AABB expanded = obb.localBox.inflate(0.02);
            OrientedBoundingBox margin = new OrientedBoundingBox(expanded, obb.transform, obb.offset);
            Optional<Vec3> hit = margin.raycast(startVec, endVec);
            if (hit.isEmpty()) {
                continue;
            }
            double distSq = startVec.distanceToSqr(hit.get());
            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closestHit = hit.get();
                closestObb = obb;
            }
        }
        if (closestHit == null || closestObb == null) {
            return null;
        }

        BodyPart part = classifyBone(closestObb, obbs);
        if (part == null) {
            return null;
        }
        return new Result(part, closestHit);
    }

    /** Same 3-tier lookup, in the same priority order, as Accurate Hitboxes' own
     *  TaczEntityUtilMixin: live per-entity bridge, then per-(viewer,entity) dynamic hitboxes
     *  (viewer = the bullet's owner — whoever's client actually saw and reported this pose),
     *  then a static blueprint anchored to the entity's current position/yaw as a last resort. */
    private static List<OrientedBoundingBox> resolveObbs(Entity target, Projectile bullet) {
        if (target instanceof IAccurateEntity accurateEntity) {
            List<OrientedBoundingBox> live = accurateEntity.accuratehitboxes$getHitboxes();
            if (live != null && !live.isEmpty()) {
                return live;
            }
        }

        List<OrientedBoundingBox> bridge = SharedMemoryBridge.get(target.getUUID());
        if (bridge != null && !bridge.isEmpty()) {
            return bridge;
        }

        Entity owner = bullet.getOwner();
        if (owner != null) {
            List<OrientedBoundingBox> dynamic = DynamicHitboxManager.get(
                    owner.getUUID(), target.getUUID(), target.level().getGameTime());
            if (dynamic != null && !dynamic.isEmpty()) {
                return dynamic;
            }
        }

        String cacheKey = BlueprintPose.getCacheKey(target);
        List<OrientedBoundingBox> blueprint = BlueprintManager.serverBlueprints.get(cacheKey);
        if ((blueprint == null || blueprint.isEmpty()) && !cacheKey.equals(BlueprintPose.getBaseCacheKey(target))) {
            blueprint = BlueprintManager.serverBlueprints.get(BlueprintPose.getBaseCacheKey(target));
        }
        if (blueprint == null || blueprint.isEmpty()) {
            return null;
        }

        float yaw = target instanceof LivingEntity living ? living.yBodyRot : target.getYRot();
        Matrix4f worldTransform = BlueprintPose.createWorldTransform(target, yaw);
        return blueprint.stream()
                .map(obb -> new OrientedBoundingBox(
                        obb.localBox,
                        new Matrix4f(worldTransform).mul(obb.transform),
                        target.position()))
                .toList();
    }

    private static BodyPart classifyBone(OrientedBoundingBox hitObb, List<OrientedBoundingBox> allObbs) {
        OrientedBoundingBox headObb = null;
        float maxCubicY = Float.NEGATIVE_INFINITY;
        for (OrientedBoundingBox obb : allObbs) {
            if (!isCubic(obb.localBox)) {
                continue;
            }
            float y = worldCenterY(obb);
            if (y > maxCubicY) {
                maxCubicY = y;
                headObb = obb;
            }
        }
        if (headObb == null) {
            // No cube-shaped bone anywhere on this rig -- can't identify a head, so this
            // classifier has nothing reliable to say. Let the caller fall back.
            return null;
        }

        float hitY = worldCenterY(hitObb);
        if (isCubic(hitObb.localBox) && maxCubicY - hitY <= LimbConfig.OBB_HEAD_Y_TOLERANCE.get()) {
            return BodyPart.HEAD;
        }

        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (OrientedBoundingBox obb : allObbs) {
            float y = worldCenterY(obb);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        float span = maxY - minY;
        if (span > 1.0E-4f && (hitY - minY) / span <= LimbConfig.OBB_LEG_HEIGHT_FRACTION.get()) {
            return BodyPart.LEGS;
        }

        Vector3f headCenter = worldCenter(headObb);
        Vector3f hitCenter = worldCenter(hitObb);
        double dx = hitCenter.x - headCenter.x;
        double dz = hitCenter.z - headCenter.z;
        double horizontalOffset = Math.sqrt(dx * dx + dz * dz);
        return horizontalOffset > LimbConfig.OBB_ARM_OFFSET.get() ? BodyPart.ARMS : BodyPart.BODY;
    }

    private static boolean isCubic(AABB localBox) {
        double xSize = localBox.maxX - localBox.minX;
        double ySize = localBox.maxY - localBox.minY;
        double zSize = localBox.maxZ - localBox.minZ;
        double maxDim = Math.max(xSize, Math.max(ySize, zSize));
        double minDim = Math.min(xSize, Math.min(ySize, zSize));
        if (minDim <= 0.0) {
            return false;
        }
        return maxDim / minDim <= CUBIC_MAX_RATIO;
    }

    private static Vector3f worldCenter(OrientedBoundingBox obb) {
        float lx = (float) ((obb.localBox.minX + obb.localBox.maxX) * 0.5);
        float ly = (float) ((obb.localBox.minY + obb.localBox.maxY) * 0.5);
        float lz = (float) ((obb.localBox.minZ + obb.localBox.maxZ) * 0.5);
        return obb.transform.transformPosition(lx, ly, lz, new Vector3f());
    }

    private static float worldCenterY(OrientedBoundingBox obb) {
        return worldCenter(obb).y;
    }
}
