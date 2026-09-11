package dev.szo9k4.limbdamage;

import net.minecraftforge.fml.ModList;

/**
 * Single source of truth for "is Accurate Hitboxes actually installed right now". ModList#isLoaded
 * touches no Accurate Hitboxes classes itself, so checking this is always safe even when AH is
 * absent. Every other class that references AH types (ObbBodyPartClassifier) must only ever be
 * reached from behind this flag — Java classes load lazily on first active use, so as long as
 * nothing calls into ObbBodyPartClassifier when this is false, that class never gets loaded and
 * its AH type references never get resolved, so there's no NoClassDefFoundError.
 */
public final class LimbCompat {

    public static final boolean ACCURATE_HITBOXES_LOADED = ModList.get().isLoaded("accuratehitboxes");

    private LimbCompat() {
    }
}
