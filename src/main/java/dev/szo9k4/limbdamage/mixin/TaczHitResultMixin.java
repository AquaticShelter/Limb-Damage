package dev.szo9k4.limbdamage.mixin;

import dev.szo9k4.limbdamage.BodyPart;
import dev.szo9k4.limbdamage.BodyPartClassifier;
import dev.szo9k4.limbdamage.HitLocationTracker;
import dev.szo9k4.limbdamage.LimbCompat;
import dev.szo9k4.limbdamage.LimbDamageMod;
import dev.szo9k4.limbdamage.compat.ObbBodyPartClassifier;
import dev.szo9k4.limbdamage.config.LimbConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;

/**
 * Tier-1 exact recorder for TACZ bullets: reads the EntityKineticBullet.EntityResult that
 * com.tacz.guns.util.EntityUtil#getHitResult returns for each (bullet, candidate entity) pair
 * and records its hit position for DamageHandler to consume when the damage lands moments later.
 *
 * Why this exists at all: TACZ bullets do NOT go through the vanilla Projectile#onHit pipeline —
 * no ProjectileImpactEvent ever fires for them — and TACZ's own gun events don't carry the hit
 * position either, so this method's return value is the only place the exact location exists.
 *
 * Why @At("RETURN") and mixin priority 2000 (in limbdamage.mixins.json): Accurate Hitboxes
 * injects into this same method at HEAD, cancelling with its own model-precise EntityResult.
 * A cancelling HEAD inject materializes as a real RETURN opcode in the transformed bytecode, and
 * a LATER-applied mixin's @At("RETURN") wraps every RETURN opcode present at its apply time —
 * so by applying after AH (priority 2000 > AH's default 1000), this capture sees BOTH paths:
 * AH's early return (model-precise hit, including arm hits outside the vanilla bounding box)
 * and TACZ's original return (plain TACZ hit detection when AH is absent or has no blueprint).
 *
 * Everything about the EntityResult is read reflectively (cached MethodHandles) — combined with
 * the string class target and @Pseudo, this mod compiles and runs with zero TACZ presence; the
 * mixin plugin additionally skips applying this class entirely when TACZ isn't installed.
 *
 * The reflective reads target TACZ 1.1.x's accessors (getEntity/getHitVec) with a direct-field
 * fallback (entity/hitVec) — verified against the EntityResult 3-arg constructor signature
 * (entity, hitVec, headshot) that Accurate Hitboxes' own TACZ mixin calls. If a future TACZ
 * reshapes the class, resolution fails once, logs once, and the mod silently degrades to the
 * trajectory-clip fallback tier — never crashes.
 *
 * SECOND job, same injection: correcting the headshot flag on that same EntityResult before TACZ
 * ever reads it. Accurate Hitboxes computes that flag with a pure vertical check (entity-relative
 * Y within eye height +-0.25) and no lateral check at all, so a raised/model-precise shoulder or
 * upper-arm hit that happens to land in that vertical band gets stamped "headshot" even though
 * it's nowhere near the head sideways — and TACZ applies its headshot damage bonus from that flag
 * BEFORE LivingHurtEvent ever fires, so DamageHandler's own ARMS multiplier would only be scaling
 * an already-inflated number, never fully undoing the bonus. Rebuilding the EntityResult here
 * (this mixin already runs AFTER AH at this exact @At("RETURN"), see priority note above) fixes
 * it at the source using BodyPartClassifier's zone model, which DOES check lateral offset. Only
 * done while the target is upright (STANDING/CROUCHING): BodyPartClassifier deliberately declines
 * to guess during swimming/crawling/sleeping, so AH's original verdict is left untouched there.
 *
 * THIRD job, from 1.1.1: when Accurate Hitboxes is loaded and has real per-bone geometry for this
 * exact hit available, classify directly off that (ObbBodyPartClassifier) instead of the
 * position-only heuristic above — see that class's doc for why it's more accurate (real bone
 * shape/position, not a reconstructed guess from one flat hit point). This needs the target
 * method's own parameters (bulletEntity, entity, startVec, endVec), captured here by declaring
 * them ahead of the CallbackInfoReturnable — all four are vanilla/base types (Projectile, Entity,
 * Vec3), so capturing them costs nothing towards the "zero TACZ compile dependency" this mixin
 * otherwise maintains; only ObbBodyPartClassifier itself (behind LimbCompat.ACCURATE_HITBOXES_LOADED)
 * touches actual Accurate Hitboxes types. When OBB classification succeeds, its result is
 * authoritative — recorded into HitLocationTracker as a pre-classified BodyPart that DamageHandler
 * uses as-is, skipping the position heuristic for that hit entirely. Falls through to the second
 * job's behavior whenever OBB data isn't available for this particular hit.
 */
@Pseudo
@Mixin(targets = "com.tacz.guns.util.EntityUtil", remap = false)
public class TaczHitResultMixin {

    @Unique
    private static volatile MethodHandle limbdamage$getEntity;
    @Unique
    private static volatile MethodHandle limbdamage$getHitVec;
    @Unique
    private static volatile boolean limbdamage$resolveFailed;

    @Unique
    private static volatile MethodHandle limbdamage$ctor;
    @Unique
    private static volatile boolean limbdamage$ctorResolveFailed;

    @Inject(method = "getHitResult", at = @At("RETURN"), remap = false, require = 0)
    private static void limbdamage$captureHitResult(
            Projectile bulletEntity, Entity entityParam, Vec3 startVec, Vec3 endVec,
            CallbackInfoReturnable<Object> cir) {
        Object result = cir.getReturnValue();
        if (result == null || limbdamage$resolveFailed) {
            return;
        }
        try {
            if (limbdamage$getEntity == null) {
                limbdamage$resolve(result.getClass());
            }
            Object entity = limbdamage$getEntity.invoke(result);
            Object hitVec = limbdamage$getHitVec.invoke(result);
            if (!(entity instanceof Entity target) || !(hitVec instanceof Vec3 position)) {
                return;
            }

            // Third job: prefer Accurate Hitboxes' real per-bone geometry when it's available —
            // see class doc. Never touches ObbBodyPartClassifier (or any AH type) unless AH is
            // actually loaded.
            ObbBodyPartClassifier.Result obbResult = null;
            if (LimbCompat.ACCURATE_HITBOXES_LOADED && LimbConfig.USE_OBB_CLASSIFICATION.get()) {
                obbResult = ObbBodyPartClassifier.classify(target, bulletEntity, startVec, endVec);
            }

            Vec3 finalPosition = obbResult != null ? obbResult.hitPos() : position;
            HitLocationTracker.record(target, finalPosition, obbResult != null ? obbResult.part() : null);

            // Second job: correct the headshot flag in-place. Prefer the OBB verdict when we have
            // one (it's authoritative — real bone shape, not a position guess); otherwise fall
            // back to BodyPartClassifier, only where it actually has an opinion (upright poses).
            if (target instanceof LivingEntity living) {
                Boolean correctedHeadshot = null;
                if (obbResult != null) {
                    correctedHeadshot = obbResult.part() == BodyPart.HEAD;
                } else {
                    Pose pose = living.getPose();
                    boolean upright = pose == Pose.STANDING || pose == Pose.CROUCHING;
                    if (upright) {
                        correctedHeadshot = BodyPartClassifier.classify(living, finalPosition) == BodyPart.HEAD;
                    }
                }
                if (correctedHeadshot != null) {
                    Object corrected = limbdamage$rebuildResult(result.getClass(), entity, finalPosition, correctedHeadshot);
                    if (corrected != null) {
                        cir.setReturnValue(corrected);
                    }
                }
            }
        } catch (Throwable t) {
            limbdamage$resolveFailed = true;
            LimbDamageMod.LOGGER.warn("[LimbDamage] Could not read TACZ EntityResult ({}); "
                    + "TACZ bullets will use the trajectory-clip fallback instead of exact hit positions.",
                    t.toString());
        }
    }

    @Unique
    private static synchronized void limbdamage$resolve(Class<?> resultClass) throws ReflectiveOperationException {
        if (limbdamage$getEntity != null) {
            return;
        }
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle entityHandle;
        MethodHandle hitVecHandle;
        try {
            entityHandle = lookup.unreflect(resultClass.getMethod("getEntity"));
            hitVecHandle = lookup.unreflect(resultClass.getMethod("getHitVec"));
        } catch (NoSuchMethodException noGetters) {
            var entityField = resultClass.getDeclaredField("entity");
            var hitVecField = resultClass.getDeclaredField("hitVec");
            entityField.setAccessible(true);
            hitVecField.setAccessible(true);
            entityHandle = lookup.unreflectGetter(entityField);
            hitVecHandle = lookup.unreflectGetter(hitVecField);
        }
        limbdamage$getHitVec = hitVecHandle;
        limbdamage$getEntity = entityHandle; // set last: acts as the "resolved" flag
    }

    /**
     * Rebuilds the EntityResult with a corrected headshot flag, via the same 3-arg
     * (entity, hitVec, headshot) constructor Accurate Hitboxes itself uses to build the original.
     * Resolution is lazy and cached; any failure here degrades to "don't override" permanently —
     * this correction is a bonus on top of the exact-position capture above, never worth risking
     * that capture over, and never worth spamming the log on every subsequent shot.
     */
    @Unique
    private static Object limbdamage$rebuildResult(Class<?> resultClass, Object entity, Object hitVec, boolean headshot) {
        if (limbdamage$ctorResolveFailed) {
            return null;
        }
        try {
            if (limbdamage$ctor == null) {
                limbdamage$resolveCtor(resultClass, entity, hitVec);
            }
            if (limbdamage$ctor == null) {
                return null;
            }
            return limbdamage$ctor.invokeWithArguments(entity, hitVec, headshot);
        } catch (Throwable t) {
            limbdamage$ctorResolveFailed = true;
            LimbDamageMod.LOGGER.warn("[LimbDamage] Could not rebuild TACZ EntityResult to correct "
                    + "its headshot flag ({}); shoulder/arm hits inside TACZ's headshot height band "
                    + "will keep counting as headshots until TACZ or Accurate Hitboxes changes shape.",
                    t.toString());
            return null;
        }
    }

    /** Finds the (entity, hitVec, boolean) constructor by matching runtime-assignable parameter
     *  types rather than hardcoding TACZ's exact class objects, staying consistent with the rest
     *  of this mixin's "resolve reflectively, degrade gracefully" approach. */
    @Unique
    private static synchronized void limbdamage$resolveCtor(Class<?> resultClass, Object entity, Object hitVec)
            throws ReflectiveOperationException {
        if (limbdamage$ctor != null || limbdamage$ctorResolveFailed) {
            return;
        }
        for (Constructor<?> candidate : resultClass.getDeclaredConstructors()) {
            Class<?>[] params = candidate.getParameterTypes();
            if (params.length == 3
                    && params[0].isInstance(entity)
                    && params[1].isInstance(hitVec)
                    && (params[2] == boolean.class || params[2] == Boolean.class)) {
                candidate.setAccessible(true);
                limbdamage$ctor = MethodHandles.lookup().unreflectConstructor(candidate);
                return;
            }
        }
        limbdamage$ctorResolveFailed = true;
    }
}
