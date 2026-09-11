package dev.szo9k4.limbdamage.event;

import dev.szo9k4.limbdamage.BodyPart;
import dev.szo9k4.limbdamage.BodyPartClassifier;
import dev.szo9k4.limbdamage.CombatLog;
import dev.szo9k4.limbdamage.HitLocationTracker;
import dev.szo9k4.limbdamage.LimbDamageMod;
import dev.szo9k4.limbdamage.config.LimbConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;
import java.util.Optional;

/**
 * Applies the per-body-part damage multiplier. Hit location is resolved in priority order:
 *
 *  1. EXACT — recorded by a precise source moments earlier in the same tick:
 *     ProjectileImpactEvent for every vanilla-pipeline projectile (arrows, tridents, snowballs,
 *     modded projectiles that call the standard onHit), and the TACZ getHitResult mixin for TACZ
 *     bullets. With Accurate Hitboxes installed, BOTH of those positions come from AH's
 *     model-geometry raycasts, so this tier is pixel-precise against the visible model for free.
 *  2. TRAJECTORY CLIP — the damage came from a projectile but nothing was recorded (some modded
 *     projectile with a custom damage path): clip the projectile's current flight segment
 *     against the target's bounding box and use the entry point. Bullets/arrows fly mostly flat,
 *     so even this approximation classifies head/legs correctly almost always; only the arm/body
 *     split loses some precision at the box edge.
 *  3. AIM RAY (melee, optional) — ray from the attacker's eyes along their view direction,
 *     clipped against the target's box. Where you're looking on the target is where the sword
 *     lands — the same assumption every locational-melee game makes.
 *
 * If none of those produce a position (explosions, magic, poison, fall damage, /kill...), the
 * damage passes through untouched — locational damage for location-less damage is meaningless.
 */
@Mod.EventBusSubscriber(modid = LimbDamageMod.MOD_ID)
public final class DamageHandler {

    private DamageHandler() {
    }

    /** LOWEST priority: runs after other mods have made their own adjustments to the same event
     *  (TACZ's headshot multiplier is applied even earlier — before hurt() is ever called — so
     *  it's already inside getAmount() regardless), meaning our multiplier applies to the final
     *  agreed-upon number rather than being overwritten by someone else's setAmount afterwards. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingHurt(LivingHurtEvent event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide()) {
            return;
        }
        if (!BodyPartClassifier.isEligible(target)) {
            return;
        }

        HitInfo hitInfo = resolveHitPosition(event, target);
        if (hitInfo == null) {
            return;
        }

        BodyPart part;
        BodyPartClassifier.Diagnosis diagnosis = null;
        if (hitInfo.preclassifiedPart() != null) {
            part = hitInfo.preclassifiedPart();
        } else {
            diagnosis = BodyPartClassifier.diagnose(target, hitInfo.position());
            part = diagnosis.part();
        }
        double multiplier = BodyPartClassifier.multiplier(part);

        float before = event.getAmount();
        float after = (float) (before * multiplier);

        // Logged before the no-op early-return below on purpose: when tuning zone boundaries you
        // need to see HEAD/BODY classifications too, and those are x1.0 by default. Includes the
        // raw numbers behind the call (heightFraction/offset/threshold/box bounds) when the
        // heuristic was actually used, so a wrong-looking classification can be root-caused from
        // the log alone; hits classified directly from Accurate Hitboxes' bone data instead just
        // say so, since there's no heuristic breakdown to show for those.
        if (LimbConfig.LOG_HITS.get()) {
            if (diagnosis != null) {
                LimbDamageMod.LOGGER.info(
                        "[LimbDamage] {} hit on {} at {} (x{}): {} -> {} "
                                + "[heightFraction={}, boxY=[{},{}], sideways={}/{}, forward={}/{}]",
                        part, target.getName().getString(), hitInfo.position(), multiplier, before, after,
                        diagnosis.heightFraction(), diagnosis.boxMinY(), diagnosis.boxMaxY(),
                        diagnosis.sidewaysOffset(), diagnosis.sidewaysThreshold(),
                        diagnosis.forwardOffset(), diagnosis.forwardThreshold());
            } else {
                LimbDamageMod.LOGGER.info(
                        "[LimbDamage] {} hit on {} at {} (x{}): {} -> {} [source=AH-OBB]",
                        part, target.getName().getString(), hitInfo.position(), multiplier, before, after);
            }
        }

        if (LimbConfig.ANNOUNCE_KILLS.get()) {
            Entity attacker = event.getSource().getEntity();
            String attackerName = attacker != null ? attacker.getName().getString() : null;
            String weaponName = attacker instanceof LivingEntity livingAttacker
                    && !livingAttacker.getMainHandItem().isEmpty()
                    ? livingAttacker.getMainHandItem().getHoverName().getString()
                    : null;
            CombatLog.record(target, new CombatLog.Entry(part, multiplier, after, attackerName,
                    weaponName, target.level().getGameTime()));
        }

        if (Math.abs(multiplier - 1.0) < 1.0E-6) {
            return;
        }
        event.setAmount(after);
    }

    /** Supplements (doesn't replace) vanilla's own death message — this mod never touches that
     *  message directly (no mixin into Player#die/CombatTracker), it just sends its own extra
     *  line right alongside it, so it can't conflict with anything else customizing deaths. */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!LimbConfig.ANNOUNCE_KILLS.get()) {
            return;
        }
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide() || !(victim.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        CombatLog.Entry lastHit = CombatLog.consume(victim);
        if (lastHit == null) {
            return;
        }

        StringBuilder line = new StringBuilder("[LimbDamage] ")
                .append(victim.getName().getString())
                .append(" died \u2014 ")
                .append(lastHit.part().name().toLowerCase(Locale.ROOT))
                .append(" hit");
        if (lastHit.attackerName() != null) {
            line.append(" from ").append(lastHit.attackerName());
        }
        if (lastHit.weaponName() != null) {
            line.append(" (").append(lastHit.weaponName()).append(')');
        }
        line.append(String.format(Locale.ROOT, " for %.1f dmg (x%.2f)", lastHit.damage(), lastHit.multiplier()));

        serverLevel.getServer().getPlayerList().broadcastSystemMessage(Component.literal(line.toString()), false);
    }

    /** Carries a resolved hit position plus, when a source could tell us directly (Accurate
     *  Hitboxes' per-bone OBB data via the TACZ mixin), the BodyPart that already was — in which
     *  case onLivingHurt uses it as-is instead of re-deriving one via BodyPartClassifier. */
    private record HitInfo(Vec3 position, BodyPart preclassifiedPart) {
    }

    private static HitInfo resolveHitPosition(LivingHurtEvent event, LivingEntity target) {
        // Tier 1: exact recorded location (vanilla projectile impact / TACZ bullet raycast).
        HitLocationTracker.Consumed recorded = HitLocationTracker.consume(target);
        if (recorded != null) {
            return new HitInfo(recorded.position(), recorded.preclassifiedPart());
        }

        Entity direct = event.getSource().getDirectEntity();

        // Tier 2: projectile trajectory clip.
        if (direct instanceof Projectile projectile) {
            Vec3 clipped = clipAgainstBox(target,
                    projectile.position().subtract(projectile.getDeltaMovement()),
                    projectile.position().add(projectile.getDeltaMovement().scale(2.0)));
            return clipped != null ? new HitInfo(clipped, null) : null;
        }

        // Tier 3: melee aim ray.
        if (LimbConfig.APPLY_TO_MELEE.get() && direct instanceof LivingEntity attacker && direct == event.getSource().getEntity()) {
            double reach = attacker.distanceTo(target) + 3.0;
            Vec3 eye = attacker.getEyePosition();
            Vec3 end = eye.add(attacker.getViewVector(1.0f).scale(reach));
            Vec3 clipped = clipAgainstBox(target, eye, end);
            return clipped != null ? new HitInfo(clipped, null) : null;
        }

        return null;
    }

    /** Entry point of the segment into the target's (slightly inflated) bounding box, or the
     *  segment start if it begins inside, or null on a clean miss (e.g. a melee swing whose aim
     *  ray doesn't actually cross the target — sweeping-edge splash hits do this; passing those
     *  through unmodified is the honest answer, since there IS no meaningful hit location). */
    private static Vec3 clipAgainstBox(LivingEntity target, Vec3 start, Vec3 end) {
        AABB box = target.getBoundingBox().inflate(0.1);
        if (box.contains(start)) {
            return start;
        }
        Optional<Vec3> clip = box.clip(start, end);
        return clip.orElse(null);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            long gameTime = event.getServer().overworld().getGameTime();
            HitLocationTracker.sweep(gameTime);
            CombatLog.sweep(gameTime);
        }
    }
}
