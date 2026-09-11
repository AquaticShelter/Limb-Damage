package dev.szo9k4.limbdamage;

import dev.szo9k4.limbdamage.config.LimbConfig;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * COD-style locational damage on top of the hit locations already flowing through the game:
 * arms/legs take reduced damage (20% less by default), per-part multipliers configurable, for
 * players and humanoid-proportioned mobs. Pairs with Accurate Hitboxes for model-precise hit
 * positions (both the vanilla projectile pipeline and TACZ bullets receive AH's raycast points
 * automatically when it's installed); degrades gracefully to trajectory/aim-ray approximation
 * without it. All logic is server-side (LivingHurtEvent); installing on clients is harmless and
 * only matters for singleplayer.
 */
@Mod(LimbDamageMod.MOD_ID)
public final class LimbDamageMod {
    public static final String MOD_ID = "limbdamage";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public LimbDamageMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, LimbConfig.SPEC);
    }
}
