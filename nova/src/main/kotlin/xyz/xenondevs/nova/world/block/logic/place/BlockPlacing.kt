package xyz.xenondevs.nova.world.block.logic.place

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockMultiPlaceEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.nova.data.world.WorldDataManager
import xyz.xenondevs.nova.data.world.block.state.NovaBlockState
import xyz.xenondevs.nova.integration.protection.ProtectionManager
import xyz.xenondevs.nova.player.WrappedPlayerInteractEvent
import xyz.xenondevs.nova.util.advance
import xyz.xenondevs.nova.util.concurrent.CombinedBooleanFuture
import xyz.xenondevs.nova.util.concurrent.runIfTrueOnSimilarThread
import xyz.xenondevs.nova.util.facing
import xyz.xenondevs.nova.util.isInsideWorldRestrictions
import xyz.xenondevs.nova.util.isUnobstructed
import xyz.xenondevs.nova.util.item.isActuallyInteractable
import xyz.xenondevs.nova.util.item.isReplaceable
import xyz.xenondevs.nova.util.item.novaItem
import xyz.xenondevs.nova.util.placeVanilla
import xyz.xenondevs.nova.util.registerEvents
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.util.serverPlayer
import xyz.xenondevs.nova.util.yaw
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.BlockManager
import xyz.xenondevs.nova.world.block.NovaBlock
import xyz.xenondevs.nova.world.block.context.BlockPlaceContext
import xyz.xenondevs.nova.world.block.limits.TileEntityLimits
import xyz.xenondevs.nova.world.pos
import java.util.concurrent.CompletableFuture

internal object BlockPlacing : Listener {
    
    fun init() {
        registerEvents()
    }
    
    @EventHandler(ignoreCancelled = true)
    private fun handleBlockPlace(event: BlockPlaceEvent) {
        // Prevent players from placing blocks where there are actually already blocks form Nova
        // This can happen when the hitbox material is replaceable, like as structure void
        event.isCancelled = WorldDataManager.getBlockState(event.block.pos) is NovaBlockState
    }
    
    @EventHandler(ignoreCancelled = true)
    private fun handleBlockPlace(event: BlockMultiPlaceEvent) {
        // Prevent players from placing blocks where there are actually already blocks form Nova
        // This can happen when the hitbox material is replaceable, like as structure void
        event.isCancelled = event.replacedBlockStates.any { WorldDataManager.getBlockState(it.location.pos) is NovaBlockState }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    private fun handleInteract(wrappedEvent: WrappedPlayerInteractEvent) {
        if (wrappedEvent.actionPerformed)
            return
        
        val event = wrappedEvent.event
        val action = event.action
        val player = event.player
        if (action == Action.RIGHT_CLICK_BLOCK) {
            val handItem = event.item
            val block = event.clickedBlock!!
            
            if (!block.type.isActuallyInteractable() || player.isSneaking) {
                val novaItem = handItem?.novaItem
                val novaBlock = novaItem?.block
                if (novaBlock != null) {
                    placeNovaBlock(wrappedEvent, novaBlock)
                } else if (
                    BlockManager.hasBlockState(block.pos) // the block placed against is from Nova
                    && block.type.isReplaceable() // and will be replaced without special behavior
                    && novaItem == null
                    && handItem?.type?.isBlock == true // a vanilla block material is used 
                ) placeVanillaBlock(wrappedEvent)
            }
        }
    }
    
    private fun placeNovaBlock(wrappedEvent: WrappedPlayerInteractEvent, material: NovaBlock) {
        val event = wrappedEvent.event
        val player = event.player
        val handItem = event.item!!
        val playerLocation = player.location
        
        event.isCancelled = true
        wrappedEvent.actionPerformed = true
        
        val clicked = event.clickedBlock!!
        val placeLoc: Location =
            if (clicked.type.isReplaceable() && !BlockManager.hasBlockState(clicked.pos))
                clicked.location
            else clicked.location.advance(event.blockFace)
        
        if (!placeLoc.isInsideWorldRestrictions() || !placeLoc.block.isUnobstructed(material.vanillaBlockMaterial, player))
            return
        
        val futures = ArrayList<CompletableFuture<Boolean>>()
        futures += ProtectionManager.canPlace(player, handItem, placeLoc)
        material.multiBlockLoader
            ?.invoke(placeLoc.pos)
            ?.forEach {
                val multiBlockLoc = it.location
                if (!multiBlockLoc.isInsideWorldRestrictions())
                    return
                futures += ProtectionManager.canPlace(player, handItem, multiBlockLoc)
            }
        material.placeCheck
            ?.invoke(player, handItem, placeLoc.apply { yaw = playerLocation.facing.oppositeFace.yaw })
            ?.also(futures::add)
        
        CombinedBooleanFuture(futures).runIfTrueOnSimilarThread {
            if (!canPlace(player, handItem, placeLoc.pos, placeLoc.clone().advance(event.blockFace.oppositeFace).pos))
                return@runIfTrueOnSimilarThread
            
            val ctx = BlockPlaceContext(
                placeLoc.pos, handItem,
                player, player.location, player.uniqueId,
                event.clickedBlock!!.pos, event.blockFace
            )
            
            val result = TileEntityLimits.canPlace(ctx)
            if (result.allowed) {
                BlockManager.placeBlockState(material, ctx)
                
                if (player.gameMode != GameMode.CREATIVE) handItem.amount--
                runTask { player.swingHand(event.hand!!) }
            } else {
                player.sendMessage(Component.text(result.message, NamedTextColor.RED))
            }
        }
    }
    
    private fun placeVanillaBlock(wrappedEvent: WrappedPlayerInteractEvent) {
        val event = wrappedEvent.event
        val player = event.player
        val handItem = event.item!!
        val placedOn = event.clickedBlock!!.pos
        val block = event.clickedBlock!!.location.advance(event.blockFace).pos

        event.isCancelled = true
        wrappedEvent.actionPerformed = true
        
        ProtectionManager.canPlace(player, handItem, block.location).runIfTrueOnSimilarThread {
            if (canPlace(player, handItem, block, placedOn)) {
                val placed = block.block.placeVanilla(player.serverPlayer, handItem, true)
                if (placed && player.gameMode != GameMode.CREATIVE) {
                    player.inventory.setItem(event.hand!!, handItem.apply { amount -= 1 })
                }
            }
        }
    }
    
    private fun canPlace(player: Player, item: ItemStack, block: BlockPos, placedOn: BlockPos): Boolean {
        if (
            player.gameMode == GameMode.SPECTATOR
            || !block.location.isInsideWorldRestrictions()
            || !block.block.type.isReplaceable()
            || WorldDataManager.getBlockState(block) != null
        ) return false
        
        if (player.gameMode == GameMode.ADVENTURE)
            return placedOn.block.type.key in item.itemMeta.placeableKeys
        
        return true
    }
    
}
