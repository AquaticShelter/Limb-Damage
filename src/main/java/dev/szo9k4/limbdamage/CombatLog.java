package dev.szo9k4.limbdamage;

import net.minecraft.world.entity.Entity;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records the most recent classified hit landed on each entity — body part, multiplier, damage,
 * attacker name, weapon name — so a death message moments later can say more than vanilla's
 * "X was slain by Y". Overwritten on every hit (not just fatal ones), keyed by victim UUID.
 *
 * LivingDeathEvent fires synchronously right after the fatal LivingHurtEvent finishes processing
 * on the same thread, so by the time death is handled the entry here IS the killing blow — no
 * need to match it up against a specific damage instance.
 *
 * Most hits never lead to death, so entries mostly just get overwritten by the next hit on the
 * same target and never get consumed — bounded by "distinct entities hit", not by hit count, but
 * a target that's hit once and then never dies (healed, logs off, despawns) leaves a stale entry
 * behind forever without the sweep below.
 */
public final class CombatLog {

    private static final long MAX_AGE_TICKS = 20L * 30L; // 30s: generous, this only needs to
    // outlive "hit, then something else finishes them off a few seconds later" — not meant to be
    // a real expiry window, just cleanup for hits that never led to a death at all.

    public record Entry(BodyPart part, double multiplier, float damage, String attackerName,
                         String weaponName, long gameTime) {
    }

    private static final Map<UUID, Entry> LAST_HIT = new ConcurrentHashMap<>();

    private CombatLog() {
    }

    public static void record(Entity victim, Entry entry) {
        if (victim == null || entry == null || victim.level().isClientSide()) {
            return;
        }
        LAST_HIT.put(victim.getUUID(), entry);
    }

    /** Destructive read: a death should only ever be explained once. */
    public static Entry consume(Entity victim) {
        return LAST_HIT.remove(victim.getUUID());
    }

    /** Cheap periodic sweep so entries for targets that were hit but never died (healed, logged
     *  off, despawned) don't accumulate forever on long-running servers. Called once per server
     *  tick alongside HitLocationTracker's sweep. */
    public static void sweep(long currentGameTime) {
        if (LAST_HIT.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Entry>> iterator = LAST_HIT.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (currentGameTime - entry.gameTime() > MAX_AGE_TICKS) {
                iterator.remove();
            }
        }
    }
}
