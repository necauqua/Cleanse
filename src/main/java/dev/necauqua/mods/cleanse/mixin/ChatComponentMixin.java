package dev.necauqua.mods.cleanse.mixin;

import dev.necauqua.mods.cleanse.Cleanse;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public final class ChatComponentMixin {

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;ILnet/minecraft/client/GuiMessageTag;Z)V", at = @At("HEAD"), cancellable = true)
    private void addMessage(Component component, MessageSignature p_241566_, int p_240583_, GuiMessageTag p_240624_, boolean p_240558_, CallbackInfo ci) {
        if (Cleanse.filterOut(component)) {
            ci.cancel();
        }
    }
}
