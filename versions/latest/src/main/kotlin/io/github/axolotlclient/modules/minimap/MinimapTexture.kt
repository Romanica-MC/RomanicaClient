/*
 * Copyright © 2026 moehreag <moehreag@gmail.com> & Contributors
 *
 * This file is part of AxolotlClient.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 *
 * For more information, see the LICENSE file.
 */

package io.github.axolotlclient.modules.minimap

import com.mojang.blaze3d.platform.NativeImage
import io.github.axolotlclient.AxolotlClientCommon
import it.unimi.dsi.fastutil.objects.Reference2IntMap
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap
import net.minecraft.client.multiplayer.ClientChunkCache
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.material.MapColor
import java.util.function.Supplier
import kotlin.math.abs
import kotlin.math.floor

/**
 * The minimap's chunk-colour cache (Alpine "minimap" module, parity spec M3).
 *
 * Conceptually the same as every chunk-colour minimap (Xaero/JourneyMap/Maple): a square
 * sample grid where each texel stores the [MapColor]-derived colour of the top-most
 * non-air block of one world column (shaded by slope and water depth), backed by a
 * [NativeImage]+[DynamicTexture] and re-uploaded only when a tick actually wrote
 * pixels — the exact pattern vanilla's map item uses (MapTextureManager).
 *
 * Updates are incremental: the grid is anchored to a zoom-quantised world position, so
 * walking shifts the existing content (array copies, no re-sampling) and only the exposed
 * edge strips are sampled. Chunk loads mark their grid region dirty, and a continuous
 * low-budget rolling sweep (at most [SWEEP_BUDGET] columns per tick, cursor-based) re-samples
 * the whole grid over a few seconds, picking up block changes within that delay. Sampling is
 * budgeted per tick so a full grid fill never hitches a frame.
 */
class MinimapTexture {

	var gridSize: Int = 0
		private set

	var zoom: Int = 2
		set(value) {
			if (value == field) {
				return
			}
			// The anchor is quantised to the zoom grid, so a zoom change invalidates every texel.
			field = value
			markAllDirty()
		}

	var anchorX: Long = 0L
		private set

	var anchorZ: Long = 0L
		private set

	private var colors = IntArray(0)
	private var heights = IntArray(0)
	private var dirty = BooleanArray(0)
	private var allDirty = true
	private var sweepCursor = 0
	private var sweepCompletions = 0L
	private var version = 0

	var texture: DynamicTexture? = null
		private set
	private var image: NativeImage? = null
	private var uploadPending = false
	private val colorCache: Reference2IntMap<BlockState> = Reference2IntOpenHashMap()
	private val scratchPos: BlockPos.MutableBlockPos = BlockPos.MutableBlockPos()

	var level: ClientLevel? = null
		set(value) {
			field = value
			reset()
		}

	fun textureId(): Identifier =
		Identifier.fromNamespaceAndPath(AxolotlClientCommon.MODID, "minimap/map/v$version")

	/** (Re)allocates the grid; called when the display size or rotation mode changes. */
	fun rebuild(gridSize: Int) {
		if (gridSize == this.gridSize && image != null) {
			return
		}
		this.gridSize = gridSize
		version++
		colors = IntArray(gridSize * gridSize)
		heights = IntArray(gridSize * gridSize)
		dirty = BooleanArray(gridSize * gridSize)
		if (texture != null) {
			texture!!.close() // DynamicTexture.close() also releases its NativeImage
		} else if (image != null) {
			image!!.close()
		}
		val newImage = NativeImage(gridSize, gridSize, false)
		image = newImage
		texture = DynamicTexture(Supplier { label() }, newImage)
		reset()
	}

	private fun label(): String = "imperiumclient minimap map texture"

	private fun reset() {
		colors.fill(0)
		heights.fill(Int.MIN_VALUE)
		markAllDirty()
	}

	fun markAllDirty() {
		allDirty = true
		uploadPending = true
	}

	fun isUploadPending(): Boolean = uploadPending

	fun clearUploadPending() {
		uploadPending = false
	}

	/** Marks the grid region covered by a loaded chunk for resampling. */
	fun markChunkDirty(pos: ChunkPos) {
		if (allDirty || level == null) {
			return
		}
		markWorldRectDirty(pos.minBlockX, pos.minBlockZ, pos.maxBlockX, pos.maxBlockZ)
	}

	private fun markWorldRectDirty(minX: Int, minZ: Int, maxX: Int, maxZ: Int) {
		val half = gridSize / 2
		val x0 = maxOf(0, worldToTexelX(minX) + half)
		val x1 = minOf(gridSize - 1, worldToTexelX(maxX) + half)
		val z0 = maxOf(0, worldToTexelZ(minZ) + half)
		val z1 = minOf(gridSize - 1, worldToTexelZ(maxZ) + half)
		for (z in z0..z1) {
			for (x in x0..x1) {
				dirty[z * gridSize + x] = true
			}
		}
	}

	// world -> grid-space texel (0 = grid centre), unclamped; floor, not truncate, so
	// negative offsets round toward -infinity exactly like the Java original.
	private fun worldToTexelX(worldX: Int): Int = floor((worldX - anchorX) / zoom.toDouble()).toInt()

	private fun worldToTexelZ(worldZ: Int): Int = floor((worldZ - anchorZ) / zoom.toDouble()).toInt()

	/** Runs once per client tick: recenters the grid on the player, then samples within budget. */
	fun tick(level: ClientLevel?, playerX: Double, playerZ: Double) {
		if (level == null) {
			return
		}
		if (this.level !== level) {
			this.level = level
		}
		if (image == null) {
			return
		}
		recenter(playerX, playerZ)

		// A full invalidation (first fill, teleport, zoom change) still spends the large
		// budget so the map fills quickly; the steady-state refresh is the rolling sweep
		// below, which costs a flat, tiny slice per tick instead of a rescan burst.
		var budget = if (allDirty) SAMPLE_BUDGET else SWEEP_BUDGET
		var wrote = false
		if (allDirty) {
			// Keep the old colours visible while the resample runs, so invalidations
			// don't blank the map; only teleports/zoom changes clear via reset()/big shifts.
			dirty.fill(true)
			allDirty = false
		}
		// Pass 1: explicitly dirty texels first (chunk loads, exposed edge strips).
		for (t in dirty.indices) {
			if (budget <= 0) {
				break
			}
			if (!dirty[t]) {
				continue
			}
			dirty[t] = false
			budget--
			val x = t % gridSize
			val z = t / gridSize
			val color = sampleColumn(x, z)
			colors[t] = color
			image!!.setPixel(x, z, color)
			wrote = true
		}
		// Pass 2: rolling sweep with whatever budget is left — every tick re-samples a
		// slice starting at the cursor, wrapping around, so the whole grid refreshes over
		// gridSize^2 / SWEEP_BUDGET ticks (a few seconds) without ever pausing a frame.
		while (budget > 0 && gridSize > 0) {
			val t = sweepCursor
			sweepCursor++
			if (sweepCursor >= colors.size) {
				sweepCursor = 0
				sweepCompletions++
			}
			if (dirty[t]) {
				dirty[t] = false
			}
			budget--
			val x = t % gridSize
			val z = t / gridSize
			val color = sampleColumn(x, z)
			colors[t] = color
			image!!.setPixel(x, z, color)
			wrote = true
		}
		if (wrote) {
			uploadPending = true
		}
	}

	/** Shifts the grid so its centre stays on the zoom-quantised player position. */
	private fun recenter(playerX: Double, playerZ: Double) {
		val targetX = Math.round(playerX / zoom) * zoom.toLong()
		val targetZ = Math.round(playerZ / zoom) * zoom.toLong()
		val shiftX = ((targetX - anchorX) / zoom).toInt()
		val shiftZ = ((targetZ - anchorZ) / zoom).toInt()
		if (shiftX == 0 && shiftZ == 0) {
			return
		}
		anchorX = targetX
		anchorZ = targetZ
		if (abs(shiftX) >= gridSize || abs(shiftZ) >= gridSize) {
			// Teleport-scale jump: the old content is from a completely different place, blank it.
			colors.fill(0)
			heights.fill(Int.MIN_VALUE)
			markAllDirty()
			return
		}
		if (allDirty) {
			return
		}
		shiftBy(shiftX, shiftZ)
	}

	private fun shiftBy(shiftX: Int, shiftZ: Int) {
		val oldColors = colors.clone()
		val oldHeights = heights.clone()
		val oldDirty = dirty.clone()
		colors.fill(0)
		heights.fill(Int.MIN_VALUE)
		dirty.fill(true)
		for (z in 0 until gridSize) {
			val srcZ = z - shiftZ
			if (srcZ < 0 || srcZ >= gridSize) {
				continue
			}
			for (x in 0 until gridSize) {
				val srcX = x - shiftX
				if (srcX < 0 || srcX >= gridSize) {
					continue
				}
				val dst = z * gridSize + x
				val src = srcZ * gridSize + srcX
				colors[dst] = oldColors[src]
				heights[dst] = oldHeights[src]
				dirty[dst] = oldDirty[src]
			}
		}
		uploadPending = true
	}

	/** Sub-texel player offset inside the centre texel, in grid-space texel units. */
	fun playerOffsetX(playerX: Double): Double = (playerX - anchorX) / zoom.toDouble()

	fun playerOffsetZ(playerZ: Double): Double = (playerZ - anchorZ) / zoom.toDouble()

	/** Samples the top-most non-air block of one column; returns ARGB (0 alpha = unloaded). */
	private fun sampleColumn(gridX: Int, gridZ: Int): Int {
		val half = gridSize / 2
		val blockX = anchorX.toInt() + (gridX - half) * zoom
		val blockZ = anchorZ.toInt() + (gridZ - half) * zoom
		val cache = level!!.chunkSource
		val chunk: LevelChunk? =
			cache.getChunk(blockX shr 4, blockZ shr 4, ChunkStatus.FULL, false)
		if (chunk == null) {
			heights[gridZ * gridSize + gridX] = Int.MIN_VALUE
			return 0
		}
		val sections = chunk.sections
		val minY = level!!.minY
		val baseX = blockX and 15
		val baseZ = blockZ and 15
		for (s in sections.indices.reversed()) {
			val section = sections[s] ?: continue
			if (section.hasOnlyAir()) {
				continue
			}
			for (y in 15 downTo 0) {
				val worldY = minY + s * 16 + y
				val state = section.getBlockState(baseX, y, baseZ)
				if (state.isAir) {
					continue
				}
				heights[gridZ * gridSize + gridX] = worldY
				return columnColor(state, worldY, blockX, blockZ, gridX, gridZ)
			}
		}
		heights[gridZ * gridSize + gridX] = Int.MIN_VALUE
		return 0
	}

	private fun columnColor(state: BlockState, topY: Int, blockX: Int, blockZ: Int, gridX: Int, gridZ: Int): Int {
		val base = baseColor(state, blockX, topY, blockZ)
		if (base == 0) {
			return 0
		}
		if (state.block === Blocks.WATER) {
			var depth = 1
			val cache: ClientChunkCache = level!!.chunkSource
			val chunk: LevelChunk? =
				cache.getChunk(blockX shr 4, blockZ shr 4, ChunkStatus.FULL, false)
			if (chunk != null) {
				val minY = level!!.minY
				var y = topY - 1
				while (y >= minY && depth < MAX_WATER_DEPTH_SHADE) {
					val below = sectionState(chunk, blockX, y, blockZ, minY) ?: break
					if (below.block !== Blocks.WATER) {
						break
					}
					depth++
					y--
				}
			}
			return shade(base, 1.0f - 0.07f * depth)
		}
		return shade(base, slopeFactor(gridX, gridZ, topY))
	}

	/** Base opaque colour for a block state (0 = transparent on the map), cached per interned state. */
	private fun baseColor(state: BlockState, blockX: Int, worldY: Int, blockZ: Int): Int {
		if (colorCache.containsKey(state)) {
			return colorCache.getInt(state)
		}
		val mapColor = state.getMapColor(level!!, scratchPos.set(blockX, worldY, blockZ))
		val base = if (mapColor == null || mapColor === MapColor.NONE) 0 else 0xFF000000.toInt() or mapColor.col
		colorCache.put(state, base)
		return base
	}

	private fun sectionState(
		chunk: LevelChunk,
		blockX: Int, worldY: Int, blockZ: Int, minY: Int
	): BlockState? {
		val index = (worldY - minY) shr 4
		val sections = chunk.sections
		if (index < 0 || index >= sections.size) {
			return null
		}
		val section = sections[index] ?: return null
		return section.getBlockState(blockX and 15, worldY and 15, blockZ and 15)
	}

	/** Classic map embossing: lit when lower than the north/west neighbours, shaded when higher. */
	private fun slopeFactor(gridX: Int, gridZ: Int, topY: Int): Float {
		val north = neighborHeight(gridX, gridZ - 1)
		val west = neighborHeight(gridX - 1, gridZ)
		var slope = 0
		if (north != Int.MIN_VALUE) {
			slope += topY.compareTo(north)
		}
		if (west != Int.MIN_VALUE) {
			slope += topY.compareTo(west)
		}
		if (slope > 0) {
			return 1.0f - 0.11f * slope
		}
		if (slope < 0) {
			return 1.0f - 0.06f * slope
		}
		return 1.0f
	}

	private fun neighborHeight(gridX: Int, gridZ: Int): Int {
		if (gridX < 0 || gridX >= gridSize || gridZ < 0 || gridZ >= gridSize) {
			return Int.MIN_VALUE
		}
		return heights[gridZ * gridSize + gridX]
	}

	/** Number of texels that currently hold a sampled colour (for tests and diagnostics). */
	fun sampledTexels(): Int = colors.count { (it ushr 24) != 0 }

	/** True while any texel still awaits its first sample after a fill/reset. */
	fun hasPendingWork(): Boolean = allDirty || dirty.any { it }

	/**
	 * How many times the rolling sweep has wrapped past the end of the grid. Exposed for the
	 * benchmark/tests: a value above zero proves the sweep refreshes texels even when
	 * nothing marks them dirty.
	 */
	fun sweepCompletions(): Long = sweepCompletions

	companion object {
		/** Columns sampled per tick at most; a 192x192 grid fills in ~5 ticks. */
		private const val SAMPLE_BUDGET = 8192

		/**
		 * Columns the rolling refresh sweep samples per tick. Chosen so a 128x128 grid (the
		 * default map) fully refreshes in 32 ticks and a 192x192 grid (rotated square) in 72
		 * ticks — comparable staleness to the old 40-tick full rescan — while the per-tick
		 * cost stays flat and tiny instead of arriving as a multi-millisecond burst.
		 */
		private const val SWEEP_BUDGET = 512
		private const val MAX_WATER_DEPTH_SHADE = 8


		private fun shade(argb: Int, factor: Float): Int {
			if (factor >= 0.999f && factor <= 1.001f) {
				return argb
			}
			val a = (argb ushr 24) and 0xFF
			val r = clampChannel((((argb ushr 16) and 0xFF) * factor).toInt())
			val g = clampChannel((((argb ushr 8) and 0xFF) * factor).toInt())
			val b = clampChannel(((argb and 0xFF) * factor).toInt())
			return (a shl 24) or (r shl 16) or (g shl 8) or b
		}

		private fun clampChannel(value: Int): Int = if (value < 0) 0 else minOf(value, 255)
	}
}
