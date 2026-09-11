package dev.szo9k4.limbdamage.mixin;

import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Skips the TACZ hit-result mixin when TACZ isn't installed. @Pseudo on the mixin itself would
 * already prevent a hard crash on a missing target class, but skipping cleanly here avoids even
 * the load attempt and the confusing "target was not found" log noise on TACZ-less setups.
 */
public final class LimbDamageMixinPlugin implements IMixinConfigPlugin {

    private static boolean taczPresent;

    @Override
    public void onLoad(String mixinPackage) {
        LoadingModList modList = LoadingModList.get();
        taczPresent = modList != null && modList.getModFileById("tacz") != null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("TaczHitResultMixin")) {
            return taczPresent;
        }
        return true;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
