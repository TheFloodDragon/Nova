package xyz.xenondevs.nova.tileentity

import org.bukkit.block.BlockFace
import org.bukkit.entity.ArmorStand
import xyz.xenondevs.nova.material.NovaMaterial
import xyz.xenondevs.nova.network.Network
import xyz.xenondevs.nova.network.NetworkManager
import xyz.xenondevs.nova.network.NetworkType
import xyz.xenondevs.nova.network.energy.EnergyConnectionType
import xyz.xenondevs.nova.network.energy.EnergyStorage
import java.util.*
import kotlin.math.min

abstract class EnergyTileEntity(material: NovaMaterial, armorStand: ArmorStand)
    : TileEntity(material, armorStand), EnergyStorage {
    
    protected abstract val defaultEnergyConfig: MutableMap<BlockFace, EnergyConnectionType>
    
    protected var energy: Int = retrieveData("energy") { 0 }
        set(value) {
            field = value
            hasEnergyChanged = true
        }
    protected var hasEnergyChanged = true
    override val networks = EnumMap<NetworkType, MutableMap<BlockFace, Network>>(NetworkType::class.java)
    override val energyConfig: MutableMap<BlockFace, EnergyConnectionType> by lazy { retrieveData("energyConfig") { defaultEnergyConfig } }
    override val providedEnergy: Int
        get() = energy
    override val requestedEnergy = 0
    
    override fun addEnergy(energy: Int) {
        this.energy += energy
    }
    
    override fun removeEnergy(energy: Int) {
        this.energy -= energy
    }
    
    override fun saveData() {
        super.saveData()
        storeData("energy", energy)
        storeData("energyConfig", energyConfig)
    }
    
    override fun handleInitialized(first: Boolean) {
        NetworkManager.handleEndPointAdd(this)
    }
    
    override fun handleRemoved(unload: Boolean) {
        NetworkManager.handleEndPointRemove(this, unload)
    }
    
}