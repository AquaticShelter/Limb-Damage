package dev.szo9k4.limbdamage.event;

import dev.szo9k4.limbdamage.HitLocationTracker;
import dev.szo9k4.limbdamage.LimbDamageMod;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Tier-1 exact recorder for every projectile that goes through the standard vanilla pipeline
 * (arrows, tridents, snowballs, and modded projectiles that call Projectile#onHit normally).
 * ProjectileImpactEvent fires with the EntityHitResult BEFORE the projectile applies its damage,
 * so the position lands in the tracker just in time for DamageHandler's LivingHurtEvent moments
 * later in the same call stack.
 *
 * When Accurate Hitboxes is installed, its ProjectileUtil mixin is what produced this
 * EntityHitResult in the first place — meaning getLocation() here is AH's exact model-geometry
 * intersection point (including arm hits OUTSIDE the vanilla bounding box), not just a box clip.
 * No AH-specific code needed on this side; the precision arrives through the vanilla type.
 *
 * LOWEST priority: if another mod cancels the impact (shield mods etc.), no damage will follow —
 * recording anyway would be harmless (destructive read + 2-tick expiry in the tracker), but
 * running last also means we never record for impacts that aren't going to happen.
 */
@Mod.EventBusSubscriber(modid = LimbDamageMod.MOD_ID)
public final class VanillaProjectileHandler {

    private VanillaProjectileHandler() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        if (event.isCanceled() || event.getProjectile().level().isClientSide()) {
            return;
        }
        if (event.getRayTraceResult() instanceof EntityHitResult entityHit) {
            HitLocationTracker.record(entityHit.getEntity(), entityHit.getLocation());
        }
    }
}
