package dev.necauqua.mods.cleanse.mixin;

import dev.necauqua.mods.cleanse.Cleanse;
import net.minecraft.client.Minecraft;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public final class MinecraftMixin {

    @Inject(method = "doWorldLoad", at = @At("RETURN"))
    private void doWorldLoad(String p_261891_, LevelStorageAccess p_261564_, PackRepository p_261826_, WorldStem p_261470_, boolean p_261465_, CallbackInfo ci) {
        Cleanse.enterWorld();
    }
}
