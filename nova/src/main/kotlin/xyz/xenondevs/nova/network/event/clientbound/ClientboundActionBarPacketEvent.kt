package xyz.xenondevs.nova.network.event.clientbound

import net.kyori.adventure.text.Component
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket
import org.bukkit.entity.Player
import xyz.xenondevs.nova.network.event.PlayerPacketEvent
import xyz.xenondevs.nova.util.component.adventure.toAdventureComponent
import xyz.xenondevs.nova.util.component.adventure.toNMSComponent

class ClientboundActionBarPacketEvent(
    player: Player,
    packet: ClientboundSetActionBarTextPacket
) : PlayerPacketEvent<ClientboundSetActionBarTextPacket>(player, packet) {
    
    @Suppress("UNNECESSARY_SAFE_CALL") // packet.components is actually nullable
    var text: Component = packet.`adventure$text`
        ?: packet.components?.toAdventureComponent()
        ?: packet.text.toAdventureComponent()
        set(value) {
            field = value
            changed = true
        }
    
    override fun buildChangedPacket(): ClientboundSetActionBarTextPacket {
        return ClientboundSetActionBarTextPacket(text.toNMSComponent())
    }
    
}