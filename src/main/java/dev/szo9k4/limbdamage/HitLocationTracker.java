package dev.szo9k4.limbdamage;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived map of "the exact position where <target> was just hit, and — when a source could
 * tell us directly — which BodyPart that already was". Written by whichever precise source fires
 * first (vanilla ProjectileImpactEvent, or the TACZ getHitResult mixin) and consumed moments
 * later — same server tick, synchronously — by DamageHandler's LivingHurtEvent.
 *
 * The BodyPart field exists because the TACZ mixin can now classify directly from Accurate
 * Hitboxes' real per-bone geometry (ObbBodyPartClassifier) when it's available — in which case
 * DamageHandler should use THAT classification as-is rather than re-deriving one from the
 * position via BodyPartClassifier's heuristic. It's null whenever no such source was available
 * (no AH, no bone data cached this tick, vanilla-projectile tier, melee tier), in which case
 * DamageHandler falls back to the heuristic exactly as before.
 *
 * Keyed by target UUID rather than by projectile: TACZ applies damage from inside its own bullet
 * tick where the bullet is still mid-flight, so matching "damage source entity == projectile at
 * position X" doesn't hold there; the target is the one stable identity across both pipelines.
 *
 * Entries expire after a couple of ticks (they're normally consumed within the same tick — the
 * expiry is purely a safety net for a hit result that never turned into damage, e.g. a bullet
 * whose damage got cancelled by another mod) and are also consumed destructively on read, so one
 * recorded location can never be accidentally reused for a later, unrelated damage event against
 * the same target (e.g. the fire tick right after being shot with an incendiary round).
 */
public final class HitLocationTracker {

    private static final long MAX_AGE_TICKS = 2;

    public record Consumed(Vec3 position, BodyPart preclassifiedPart) {
    }

    private record Entry(Vec3 position, BodyPart preclassifiedPart, long gameTime) {
    }

    private static final Map<UUID, Entry> RECENT_HITS = new ConcurrentHashMap<>();

    private HitLocationTracker() {
    }

    /** Convenience overload for sources that only have a position (no bone-level classification
     *  available) — vanilla projectiles, or the TACZ mixin when Accurate Hitboxes/OBB data isn't
     *  available for this particular hit. */
    public static void record(Entity target, Vec3 hitPosition) {
        record(target, hitPosition, null);
    }

    public static void record(Entity target, Vec3 hitPosition, BodyPart preclassifiedPart) {
        if (target == null || hitPosition == null || target.level().isClientSide()) {
            return;
        }
        RECENT_HITS.put(target.getUUID(), new Entry(hitPosition, preclassifiedPart, target.level().getGameTime()));
    }

    /** Destructive read: returns and removes the recorded hit for this target if one was recorded
     *  within the last couple of ticks, else null. */
    public static Consumed consume(Entity target) {
        Entry entry = RECENT_HITS.remove(target.getUUID());
        if (entry == null) {
            return null;
        }
        long age = target.level().getGameTime() - entry.gameTime();
        return age >= 0 && age <= MAX_AGE_TICKS ? new Consumed(entry.position(), entry.preclassifiedPart()) : null;
    }

    /** Cheap periodic sweep so entries for never-damaged targets don't accumulate forever on
     *  long-running servers. Called once per server tick from the mod's tick handler. */
    public static void sweep(long currentGameTime) {
        if (RECENT_HITS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Entry>> iterator = RECENT_HITS.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (currentGameTime - entry.gameTime() > MAX_AGE_TICKS) {
                iterator.remove();
            }
        }
    }
}
