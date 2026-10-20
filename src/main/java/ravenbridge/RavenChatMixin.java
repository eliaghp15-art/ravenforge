package ravenbridge;

import net.minecraft.network.play.server.S02PacketChat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ravenforge.client.event.ClientChatReceivedEvent;

/** Forge fires this from the chat packet handler; so do we. */
@Mixin(targets = "net.minecraft.client.network.NetHandlerPlayClient", remap = false)
public class RavenChatMixin {
    @Inject(method = "handleChat", at = @At("HEAD"), require = 0, remap = false)
    private void ravenforge$chat(S02PacketChat packet, CallbackInfo callbackInfo) {
        if (packet == null) {
            return;
        }
        RavenForge.post(new ClientChatReceivedEvent(packet.getChatComponent(), packet.getType()));
    }
}
