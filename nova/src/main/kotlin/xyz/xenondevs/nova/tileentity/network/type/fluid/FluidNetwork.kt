package xyz.xenondevs.nova.tileentity.network.type.fluid

import xyz.xenondevs.commons.collections.firstInstanceOfOrNull
import xyz.xenondevs.commons.collections.getOrSet
import xyz.xenondevs.commons.provider.Provider
import xyz.xenondevs.commons.provider.immutable.combinedProvider
import xyz.xenondevs.commons.provider.immutable.map
import xyz.xenondevs.nova.data.config.MAIN_CONFIG
import xyz.xenondevs.nova.tileentity.network.Network
import xyz.xenondevs.nova.tileentity.network.NetworkData
import xyz.xenondevs.nova.tileentity.network.node.NetworkEndPoint
import xyz.xenondevs.nova.tileentity.network.type.fluid.channel.FluidNetworkChannel
import xyz.xenondevs.nova.tileentity.network.type.fluid.holder.FluidHolder
import kotlin.math.min
import kotlin.math.roundToLong

class FluidNetwork(networkData: NetworkData) : Network, NetworkData by networkData {
    
    private val channels: Array<FluidNetworkChannel?> = arrayOfNulls(CHANNEL_AMOUNT)
    private val transferRate: Long
    
    private var nextChannel = 0
    
    init {
        var transferRate = DEFAULT_TRANSFER_RATE
        
        for ((node, faces) in networkData.nodes.values) {
            if (node is NetworkEndPoint) {
                val fluidHolder = node.holders.firstInstanceOfOrNull<FluidHolder>()
                    ?: continue
                
                for ((face, channelId) in fluidHolder.channels) {
                    if (face in faces) {
                        val channel = channels.getOrSet(channelId, ::FluidNetworkChannel)
                        channel.addHolder(fluidHolder, face)
                    }
                }
            } else if (node is FluidBridge) {
                transferRate = min(transferRate, node.fluidTransferRate)
            }
        }
        
        this.transferRate = transferRate
        
        for (channel in channels)
            channel?.createDistributor()
    }
    
    override fun handleTick() {
        val startingChannel = nextChannel
        var amountLeft = transferRate
        do {
            amountLeft = channels[nextChannel]?.distributeFluids(amountLeft) ?: amountLeft
            
            nextChannel++
            if (nextChannel >= channels.size) nextChannel = 0
        } while (amountLeft != 0L && nextChannel != startingChannel)
    }
    
    companion object {
        
        private val FLUID_NETWORK = MAIN_CONFIG.node("network", "fluid")
        val TICK_DELAY_PROVIDER: Provider<Int> = FLUID_NETWORK.entry<Int>("tick_delay")
        val DEFAULT_TRANSFER_RATE: Long by combinedProvider(FLUID_NETWORK.entry<Double>("default_transfer_rate"), TICK_DELAY_PROVIDER)
            .map { (defaultTransferRate, tickDelay) -> (defaultTransferRate * tickDelay).roundToLong() }
            .map { defaultTransferRate -> if (defaultTransferRate < 0) Long.MAX_VALUE else defaultTransferRate }
        val CHANNEL_AMOUNT: Int by FLUID_NETWORK.entry<Int>("channel_amount")
        
    }
    
}
