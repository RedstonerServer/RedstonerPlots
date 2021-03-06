package com.redstoner.plots

import com.redstoner.plots.math.Vec2i
import com.redstoner.plots.math.clamp
import com.redstoner.plots.math.even
import com.redstoner.plots.math.umod
import org.bukkit.*
import org.bukkit.block.*
import org.bukkit.entity.Entity
import org.bukkit.generator.BlockPopulator
import org.bukkit.generator.ChunkGenerator
import java.util.*
import kotlin.coroutines.experimental.buildIterator
import kotlin.reflect.KClass

abstract class PlotGenerator : ChunkGenerator(), PlotProvider {
    abstract val world: PlotWorld

    abstract val factory: GeneratorFactory

    abstract override fun generateChunkData(world: World?, random: Random?, chunkX: Int, chunkZ: Int, biome: BiomeGrid?): ChunkData

    abstract fun populate(world: World?, random: Random?, chunk: Chunk?)

    abstract override fun getFixedSpawnLocation(world: World?, random: Random?): Location

    override fun getDefaultPopulators(world: World?): MutableList<BlockPopulator> {
        return Collections.singletonList(object : BlockPopulator() {
            override fun populate(world: World?, random: Random?, chunk: Chunk?) {
                this@PlotGenerator.populate(world, random, chunk)
            }
        })
    }

    abstract fun updateOwner(plot: Plot)

    abstract fun getBottomCoord(plot: Plot): Vec2i

    abstract fun getHomeLocation(plot: Plot): Location

    abstract fun setBiome(plot: Plot, biome: Biome)

    abstract fun getEntities(plot: Plot): Collection<Entity>

    abstract fun getBlocks(plot: Plot, yRange: IntRange = 0..255): Iterator<Block>

}

interface GeneratorFactory {
    companion object GeneratorFactories {
        private val map: MutableMap<String, GeneratorFactory> = HashMap()

        fun registerFactory(generator: GeneratorFactory): Boolean = map.putIfAbsent(generator.name, generator) == null

        fun getFactory(name: String): GeneratorFactory? = map.get(name)

        init {
            registerFactory(DefaultPlotGenerator.Factory)
        }

    }

    val name: String

    val optionsClass: KClass<out GeneratorOptions>

    fun newPlotGenerator(worldName: String, options: GeneratorOptions): PlotGenerator

}

class DefaultPlotGenerator(name: String, private val o: DefaultGeneratorOptions) : PlotGenerator() {
    override val world: PlotWorld by lazy { getWorld(name)!! }
    override val factory = Factory

    companion object Factory : GeneratorFactory {
        override val name get() = "default"
        override val optionsClass get() = DefaultGeneratorOptions::class
        override fun newPlotGenerator(worldName: String, options: GeneratorOptions): PlotGenerator {
            return DefaultPlotGenerator(worldName, options as DefaultGeneratorOptions)
        }
    }

    val sectionSize = o.plotSize + o.pathSize
    val pathOffset = (if (o.pathSize % 2 == 0) o.pathSize + 2 else o.pathSize + 1) / 2
    val makePathMain = o.pathSize > 2
    val makePathAlt = o.pathSize > 4

    private inline fun <T> generate(chunkX: Int,
                                    chunkZ: Int,
                                    floor: T, wall:
                                    T, pathMain: T,
                                    pathAlt: T,
                                    fill: T,
                                    setter: (Int, Int, Int, T) -> Unit) {

        val floorHeight = o.floorHeight
        val plotSize = o.plotSize
        val sectionSize = sectionSize
        val pathOffset = pathOffset
        val makePathMain = makePathMain
        val makePathAlt = makePathAlt

        // plot bottom x and z
        // umod is unsigned %: the result is always >= 0
        val pbx = ((chunkX shl 4) - o.offsetX) umod sectionSize
        val pbz = ((chunkZ shl 4) - o.offsetZ) umod sectionSize

        var curHeight: Int
        var x: Int
        var z: Int
        for (cx in 0..15) {
            for (cz in 0..15) {
                x = (pbx + cx) % sectionSize - pathOffset
                z = (pbz + cz) % sectionSize - pathOffset
                curHeight = floorHeight

                val type = when {
                    (x in 0 until plotSize && z in 0 until plotSize) -> floor
                    (x in -1..plotSize && z in -1..plotSize) -> {
                        curHeight++
                        wall
                    }
                    (makePathAlt && x in -2 until plotSize + 2 && z in -2 until plotSize + 2) -> pathAlt
                    (makePathMain) -> pathMain
                    else -> {
                        curHeight++
                        wall
                    }
                }

                for (y in 0 until curHeight) {
                    setter(cx, y, cz, fill)
                }
                setter(cx, curHeight, cz, type)
            }
        }
    }

    override fun generateChunkData(world: World?, random: Random?, chunkX: Int, chunkZ: Int, biome: BiomeGrid?): ChunkData {
        val out = Bukkit.createChunkData(world)
        generate(chunkX, chunkZ, o.floorType, o.wallType, o.pathMainType, o.pathAltType, o.fillType) { x, y, z, type ->
            out.setBlock(x, y, z, type.intId, type.data)
        }
        return out
    }

    override fun populate(world: World?, random: Random?, chunk: Chunk?) {
        generate(chunk!!.x, chunk.z, o.floorType.data, o.wallType.data, o.pathMainType.data, o.pathAltType.data, o.fillType.data) { x, y, z, type ->
            if (type == 0.toByte()) chunk.getBlock(x, y, z).setData(type, false)
        }
    }

    override fun getFixedSpawnLocation(world: World?, random: Random?): Location {
        val fix = if (o.plotSize.even) 0.5 else 0.0
        return Location(world, o.offsetX + fix, o.floorHeight + 1.0, o.offsetZ + fix)
    }

    override fun plotAt(x: Int, z: Int): Plot? {
        val sectionSize = sectionSize
        val plotSize = o.plotSize
        val absX = x - o.offsetX - pathOffset
        val absZ = z - o.offsetZ - pathOffset
        val modX = absX umod sectionSize
        val modZ = absZ umod sectionSize
        if (0 <= modX && modX < plotSize && 0 <= modZ && modZ < plotSize) {
            return world.plotByID((absX - modX) / sectionSize, (absZ - modZ) / sectionSize)
        }
        return null
    }

    override fun getBottomCoord(plot: Plot): Vec2i = Vec2i(sectionSize * plot.coord.x + pathOffset + o.offsetX,
            sectionSize * plot.coord.z + pathOffset + o.offsetZ)

    override fun getHomeLocation(plot: Plot): Location {
        val bottom = getBottomCoord(plot)
        return Location(world.world, bottom.x.toDouble(), o.floorHeight + 1.0, bottom.z + (o.plotSize - 1) / 2.0, -90F, 0F)
    }

    override fun updateOwner(plot: Plot) {
        val world = this.world.world
        val b = getBottomCoord(plot)

        val wallBlock = world.getBlockAt(b.x - 1, o.floorHeight + 1, b.z - 1)
        val signBlock = world.getBlockAt(b.x - 2, o.floorHeight + 1, b.z - 1)
        val skullBlock = world.getBlockAt(b.x - 1, o.floorHeight + 2, b.z - 1)

        val owner = plot.data?.owner
        if (owner == null) {
            o.wallType.setBlock(wallBlock)
            BlockType.AIR.setBlock(signBlock)
            BlockType.AIR.setBlock(skullBlock)
        } else {
            val wallBlockType = o.wallType.copy(when (o.wallType.material) {
                Material.CARPET -> Material.WOOL
                Material.STEP -> Material.DOUBLE_STEP
                Material.WOOD_STEP -> Material.WOOD_DOUBLE_STEP
                else -> o.wallType.material
            }.id.toShort())
            wallBlockType.setBlock(wallBlock)

            BlockType(Material.WALL_SIGN, 4.toByte()).setBlock(signBlock)
            val sign = signBlock.state as Sign
            sign.setLine(0, plot.id)
            sign.setLine(2, owner.playerName)
            sign.update()

            BlockType(Material.SKULL, 1.toByte()).setBlock(skullBlock)
            val skull = skullBlock.state as Skull
            if (owner.uuid != null) {
                skull.owningPlayer = owner.offlinePlayer
            } else {
                skull.owner = owner.name
            }
            skull.rotation = BlockFace.WEST
            skull.update()
        }
    }

    override fun setBiome(plot: Plot, biome: Biome) {
        val world = this.world.world
        val b = getBottomCoord(plot)
        val plotSize = o.plotSize
        for (x in b.x until b.x + plotSize) {
            for (z in b.z until b.z + plotSize) {
                world.setBiome(x, z, biome)
            }
        }
    }

    override fun getEntities(plot: Plot): Collection<Entity> {
        val world = this.world.world
        val b = getBottomCoord(plot)
        val plotSize = o.plotSize
        val center = Location(world, (b.x + plotSize) / 2.0, 128.0, (b.z + plotSize) / 2.0)
        return world.getNearbyEntities(center, plotSize / 2.0 + 0.2, 128.0, plotSize / 2.0 + 0.2)
    }

    override fun getBlocks(plot: Plot, yRange: IntRange): Iterator<Block> = buildIterator {
        val range = yRange.clamp(0, 255)
        val world = this@DefaultPlotGenerator.world.world
        val b = getBottomCoord(plot)
        val plotSize = o.plotSize
        for (x in b.x until b.x + plotSize) {
            for (z in b.z until b.z + plotSize) {
                for (y in range) {
                    yield(world.getBlockAt(x, y, z))
                }
            }
        }
    }

}