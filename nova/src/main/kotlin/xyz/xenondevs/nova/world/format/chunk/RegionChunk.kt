package xyz.xenondevs.nova.world.format.chunk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.minecraft.world.level.GameRules
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.scheduler.BukkitTask
import xyz.xenondevs.cbf.CBF
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.cbf.io.ByteReader
import xyz.xenondevs.cbf.io.ByteWriter
import xyz.xenondevs.commons.collections.putOrRemove
import xyz.xenondevs.commons.collections.takeUnlessEmpty
import xyz.xenondevs.nova.LOGGER
import xyz.xenondevs.nova.tileentity.TileEntity
import xyz.xenondevs.nova.tileentity.vanilla.VanillaTileEntity
import xyz.xenondevs.nova.util.AsyncExecutor
import xyz.xenondevs.nova.util.ceilDiv
import xyz.xenondevs.nova.util.runTaskTimer
import xyz.xenondevs.nova.util.serverLevel
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.ChunkPos
import xyz.xenondevs.nova.world.block.DefaultBlocks
import xyz.xenondevs.nova.world.block.NovaTileEntityBlock
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.format.BlockStateIdResolver
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.logging.Level
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToLong
import kotlin.random.Random

internal class RegionChunk(
    val pos: ChunkPos,
    private val sections: Array<RegionChunkSection<NovaBlockState>> = Array(getSectionCount(pos.world!!)) { RegionChunkSection(BlockStateIdResolver) },
    private val vanillaTileEntityData: MutableMap<BlockPos, Compound> = HashMap(),
    private val tileEntityData: MutableMap<BlockPos, Compound> = HashMap(),
) {
    
    private val lock = ReentrantReadWriteLock(true)
    val isEnabled = AtomicBoolean(false)
    
    private val world = pos.world!!
    private val level = world.serverLevel
    private val minHeight = world.minHeight
    private val maxHeight = world.maxHeight
    private val sectionCount = getSectionCount(world)
    
    private val vanillaTileEntities: MutableMap<BlockPos, VanillaTileEntity> = HashMap()
    private val tileEntities: MutableMap<BlockPos, TileEntity> = ConcurrentHashMap()
    
    private var tick = 0
    private var asyncTickerSupervisor: Job? = null
    private var tileEntityAsyncTickers: HashMap<TileEntity, Job>? = null
    private var syncTicker: BukkitTask? = null
    
    init {
        initVanillaTileEntities()
        initNovaTileEntities()
    }
    
    /**
     * Initializes the [VanillaTileEntities][VanillaTileEntity] in this chunk.
     */
    private fun initVanillaTileEntities() {
        for ((pos, data) in vanillaTileEntityData) {
            try {
                val type: VanillaTileEntity.Type = data["type"]!!
                vanillaTileEntities[pos] = type.constructor(pos, data)
            } catch (t: Throwable) {
                LOGGER.log(Level.SEVERE, "Failed to initialize vanilla tile entity pos=$pos, data=$data")
            }
        }
    }
    
    /**
     * Initializes the [TileEntities][TileEntity] in this chunk.
     */
    private fun initNovaTileEntities() {
        for ((pos, data) in tileEntityData) {
            val blockState = getBlockState(pos)
            if (blockState == null) {
                LOGGER.log(Level.SEVERE, "Failed to initialize tile entity at $pos because there is no block state")
                return
            }
            
            val block = blockState.block as? NovaTileEntityBlock
            if (blockState.block == DefaultBlocks.UNKNOWN) {
                // nova:unknown is the only non-tile-entity block that is allowed to have tile-entity data
                continue
            } else if (block == null) {
                LOGGER.log(Level.SEVERE, "Failed to initialize tile entity at $pos because ${blockState.block} is not a tile entity type")
                return
            }
            
            try {
                tileEntities[pos] = block.tileEntityConstructor(pos, blockState, data)
            } catch (t: Throwable) {
                LOGGER.log(Level.SEVERE, "Failed to initialize tile entity pos=$pos, blockState=$blockState, data=$data", t)
            }
        }
    }
    
    /**
     * Gets the [NovaBlockState] at the given [pos].
     */
    fun getBlockState(pos: BlockPos): NovaBlockState? =
        getSection(pos.y)[pos.x and 0xF, pos.y and 0xF, pos.z and 0xF]
    
    /**
     * Sets the [BlockState][state] at the given [pos].
     */
    fun setBlockState(pos: BlockPos, state: NovaBlockState?) {
        getSection(pos.y)[pos.x and 0xF, pos.y and 0xF, pos.z and 0xF] = state
        
        if (state != null) {
            lock.read { tileEntities[pos]?.blockState = state }
        }
    }
    
    /**
     * Gets the [VanillaTileEntity] at the given [pos].
     */
    fun getVanillaTileEntity(pos: BlockPos): VanillaTileEntity? =
        lock.read { vanillaTileEntities[pos] }
    
    /**
     * Gets a snapshot of all [VanillaTileEntities][VanillaTileEntity] in this chunk.
     */
    fun getVanillaTileEntities(): List<VanillaTileEntity> =
        lock.read { ArrayList(vanillaTileEntities.values) }
    
    /**
     * Sets the [VanillaTileEntity][vte] at the given [pos].
     */
    fun setVanillaTileEntity(pos: BlockPos, vte: VanillaTileEntity?) =
        lock.write { vanillaTileEntities.putOrRemove(pos, vte) }
    
    /**
     * Gets the [TileEntity] at the given [pos].
     */
    fun getTileEntity(pos: BlockPos): TileEntity? =
        lock.read { tileEntities[pos] }
    
    /**
     * Gets a snapshot of all [TileEntities][TileEntity] in this chunk.
     */
    fun getTileEntities(): List<TileEntity> =
        lock.read { tileEntities.values.toList() }
    
    /**
     * Sets the [tileEntity] at the given [pos].
     */
    fun setTileEntity(pos: BlockPos, tileEntity: TileEntity?): TileEntity? = lock.write {
        val previous: TileEntity?
        if (tileEntity == null) {
            previous = tileEntities.remove(pos)
            tileEntityData.remove(pos)
        } else {
            previous = tileEntities.put(pos, tileEntity)
            tileEntityData[pos] = tileEntity.data
            
            // fixme: isEnabled can be set to true in enable() while this method is running,
            //  which would cause the tile-entity to be enabled twice
            if (isEnabled.get()) {
                tileEntity.handleEnable()
                launchAndRegisterAsyncTicker(asyncTickerSupervisor!!, tileEntity)
            }
        }
        
        if (previous != null) {
            previous.handleDisable()
            cancelAndUnregisterAsyncTicker(previous)
        }
        
        return previous
    }
    
    /**
     * Gets the [RegionChunkSection] at the given [y] coordinate.
     */
    private fun getSection(y: Int): RegionChunkSection<NovaBlockState> {
        require(y in minHeight..maxHeight) { "Invalid y coordinate $y" }
        return sections[(y - minHeight) shr 4]
    }
    
    /**
     * Enables this RegionChunk.
     *
     * Enabling a RegionChunk activates synchronous and asynchronous tile-entity ticking, as well as random ticks and
     * also calls [TileEntity.handleEnable].
     */
    fun enable() {
        if (isEnabled.getAndSet(true))
            return
        
        lock.write {
            // enable sync ticking
            syncTicker = runTaskTimer(0, 1, ::tick)
            
            // enable async ticking
            val supervisor = SupervisorJob(AsyncExecutor.SUPERVISOR)
            asyncTickerSupervisor = supervisor
            tileEntityAsyncTickers = tileEntities.values.associateWithTo(HashMap()) { launchAsyncTicker(supervisor, it) }
            
            // load models
            for ((pos, tileEntity) in tileEntities)
                tileEntity.blockState.modelProvider.load(pos)
        }
        
        tileEntities.values.forEach(TileEntity::handleEnable)
    }
    
    /**
     * Disables this RegionChunk.
     *
     * Disabling a RegionChunk deactivates synchronous and asynchronous tile-entity ticking, as well as random ticks and
     * also calls [TileEntity.handleDisable].
     */
    fun disable() {
        if (!isEnabled.getAndSet(false))
            return
        
        lock.write {
            // disable sync ticking
            syncTicker?.cancel()
            syncTicker = null
            
            // disable async ticking
            asyncTickerSupervisor?.cancel("Chunk ticking disabled")
            tileEntityAsyncTickers = null
            
            // unload models
            for ((pos, tileEntity) in tileEntities)
                tileEntity.blockState.modelProvider.unload(pos)
        }
        
        tileEntities.values.forEach(TileEntity::handleDisable)
    }
    
    private fun tick() {
        tick++
        
        // tile-entity ticks
        for (tileEntity in tileEntities.values) {
            val interval = 20 - tileEntity.block.syncTickrate
            if (interval == 0 || tick % interval == 0) {
                try {
                    if (tileEntity.isEnabled) // this should prevent tile-entities that were removed during ticking from being ticked
                        tileEntity.handleTick()
                } catch (t: Throwable) {
                    LOGGER.log(Level.SEVERE, "An exception occurred while ticking tile entity $tileEntity", t)
                }
            }
        }
        
        // random ticks
        val randomTickSpeed = level.gameRules.getInt(GameRules.RULE_RANDOMTICKING)
        if (randomTickSpeed > 0) {
            for (section in sections) {
                section.lock.read {
                    if (section.isEmpty())
                        return@read
                    
                    repeat(randomTickSpeed) {
                        val x = Random.nextInt(0, 16)
                        val y = Random.nextInt(0, 16)
                        val z = Random.nextInt(0, 16)
                        val blockState = section[x, y, z]
                        if (blockState != null) {
                            val pos = BlockPos(world, pos.x + x, minHeight + y, pos.z + z)
                            try {
                                blockState.block.handleRandomTick(pos, blockState)
                            } catch (t: Throwable) {
                                LOGGER.log(Level.SEVERE, "An exception occurred while ticking block $blockState at $pos", t)
                            }
                        }
                    }
                }
            }
        }
    }
    
    private fun launchAndRegisterAsyncTicker(context: CoroutineContext, tileEntity: TileEntity) {
        tileEntityAsyncTickers?.put(tileEntity, launchAsyncTicker(context, tileEntity))
    }
    
    private fun cancelAndUnregisterAsyncTicker(tileEntity: TileEntity) {
        tileEntityAsyncTickers?.remove(tileEntity)?.cancel("Tile entity removed")
    }
    
    private fun launchAsyncTicker(context: CoroutineContext, tileEntity: TileEntity): Job =
        CoroutineScope(context).launch {
            var startTime: Long
            while (true) {
                startTime = System.currentTimeMillis()
                
                withContext(NonCancellable) { tileEntity.handleAsyncTick() }
                
                val globalTickRate = Bukkit.getServerTickManager().tickRate
                val msPerTick = (1000 / tileEntity.block.asyncTickrate * (globalTickRate / 20)).roundToLong()
                val delayTime = msPerTick - (System.currentTimeMillis() - startTime)
                if (delayTime > 0)
                    delay(delayTime)
            }
        }
    
    /**
     * Writes this chunk to the given [writer].
     */
    fun write(writer: ByteWriter): Boolean = lock.read {
        // acquire read locks for all sections
        for (section in sections) section.lock.readLock().lock()
        
        try {
            if (vanillaTileEntityData.isEmpty() && tileEntityData.isEmpty() && sections.all { it.isEmpty() })
                return false
            
            vanillaTileEntities.values.forEach(VanillaTileEntity::saveData)
            tileEntities.values.forEach(TileEntity::saveData)
            
            CBF.write(vanillaTileEntityData.takeUnlessEmpty(), writer)
            CBF.write(tileEntityData.takeUnlessEmpty(), writer)
            
            val sectionBitmask = BitSet(sectionCount)
            val sectionsBuffer = ByteArrayOutputStream()
            val sectionsWriter = ByteWriter.fromStream(sectionsBuffer)
            
            for ((sectionIdx, section) in sections.withIndex()) {
                sectionBitmask.set(sectionIdx, section.write(sectionsWriter))
            }
            
            writer.writeInt(sectionCount)
            writer.writeBytes(Arrays.copyOf(sectionBitmask.toByteArray(), sectionCount.ceilDiv(8)))
            writer.writeBytes(sectionsBuffer.toByteArray())
            
            return true
        } finally {
            // release read locks for all sections
            for (section in sections) section.lock.readLock().unlock()
        }
    }
    
    companion object {
        
        fun read(pos: ChunkPos, reader: ByteReader): RegionChunk {
            val vanillaTileEntityData = CBF.read<HashMap<BlockPos, Compound>>(reader) ?: HashMap()
            val tileEntityData = CBF.read<HashMap<BlockPos, Compound>>(reader) ?: HashMap()
            
            val sectionCount = reader.readInt()
            val sectionsBitmask = BitSet.valueOf(reader.readBytes(sectionCount.ceilDiv(8)))
            
            val sections = Array(sectionCount) { sectionIdx ->
                if (sectionsBitmask.get(sectionIdx))
                    RegionChunkSection.read(BlockStateIdResolver, reader)
                else RegionChunkSection(BlockStateIdResolver)
            }
            
            return RegionChunk(pos, sections, vanillaTileEntityData, tileEntityData)
        }
        
        private fun getSectionCount(world: World): Int =
            (world.maxHeight - world.minHeight) shr 4
        
    }
    
}