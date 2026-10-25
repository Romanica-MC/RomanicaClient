/*
 * Copyright © 2024 moehreag <moehreag@gmail.com> & Contributors
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

package io.github.axolotlclient.modules.legions

import java.util.ArrayList

import io.github.axolotlclient.AxolotlClientCommon
import io.github.axolotlclient.AxolotlClientConfig.api.options.OptionCategory
import io.github.axolotlclient.AxolotlClientConfig.api.util.Color
import io.github.axolotlclient.AxolotlClientConfig.impl.options.BooleanOption
import io.github.axolotlclient.AxolotlClientConfig.impl.options.ColorOption
import io.github.axolotlclient.AxolotlClientConfig.impl.options.FloatOption
import io.github.axolotlclient.AxolotlClientConfig.impl.options.IntegerOption
import io.github.axolotlclient.bridge.key.AxoKeybinding
import io.github.axolotlclient.bridge.key.AxoKeys
import io.github.axolotlclient.modules.AbstractModule
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.gizmos.Gizmos
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

/**
 * TNT trajectory prediction (Alpine "factions/cannon_view + cannon_playback" parity,
 * reimplemented for Legions): while the player holds TNT, the predicted flight arc of a TNT
 * launched along the look direction is drawn in the world, together with the blast box at the
 * predicted landing site; primed TNT already in flight within [range] gets the same
 * treatment from its live position and velocity (the playback half — where this shot is
 * going to come down).
 *
 * Physics is [TntTrajectory], a pure integrator with the exact 26.3 `PrimedTnt`
 * constants (gravity 0.04 before the move, air drag 0.98 after it — javap of the mapped jar).
 * The launch speed is an option because ImperiumMC cannons impart their own charge-dependent
 * velocity; 1.5 blocks/tick matches a strong hand throw.
 *
 * Rendering rides the same world-space gizmo path as the WE-CUI selection and the chunk
 * borders: `LevelRenderEvents.BEFORE_GIZMOS` opens the frame's render-thread gizmo
 * collection window and `Gizmos.line`/[Gizmos.cuboid] submit camera-independent
 * world-space geometry, drawn with the game's line pipelines (translucent lines are
 * depth-tested but never occlude). The landing box uses [ExplosionBoxes]' radius option
 * so the prediction and the live box around a burning TNT read as one visual system.
 */
class CannonTrajectory private constructor() : AbstractModule() {

    /** Launch speed along the look vector, in blocks per tick. */
    @JvmField
    val launchPower = FloatOption("cannontrajectory.power", 1.5f, 0.1f, 5.0f)

    /** Ticks to simulate; the vanilla fuse burns 80, so the default covers a full flight. */
    @JvmField
    val maxTicks = IntegerOption("cannontrajectory.max_ticks", TntTrajectory.DEFAULT_MAX_TICKS, 20, 200)

    /** Draw the predicted arc while holding TNT (Alpine's cannon_view trigger). */
    @JvmField
    val enabled = BooleanOption("enabled", true)

    /** Also predict the remaining flight of primed TNT entities within range. */
    @JvmField
    val predictLiveTnt = BooleanOption("cannontrajectory.predict_live", true)

    /** Camera distance up to which live primed TNT is predicted. */
    @JvmField
    val range = IntegerOption("cannontrajectory.range", 64, 8, 256)

    /** Draw the blast box at the predicted landing site. */
    @JvmField
    val showLandingBox = BooleanOption("cannontrajectory.landing_box", true)

    @JvmField
    val arcColor = ColorOption("cannontrajectory.arc_color", Color(255, 238, 170, 220))

    @JvmField
    val landingColor = ColorOption("cannontrajectory.landing_color", Color(255, 85, 85, 200))

    @JvmField
    val lineWidth = FloatOption("cannontrajectory.line_width", 2.0f, 0.5f, 8.0f)

    // factions/cannon_view (wave 3): a dedicated toggle keybind, Alpine-style — flip the
    // prediction on and off without opening the config (AutoFish toggle-key idiom).
    @JvmField
    val toggleKey = AxoKeybinding.create(AxoKeys.KEY_UNKNOWN, "key.cannon_view.toggle")

    private val category = OptionCategory.create("cannontrajectory")

    /** Last drawn waypoint count, for the gametest's render assertions. */
    private var lastDrawnSegments = 0

    /**
     * Live TNT snapshot, rebuilt on the client tick instead of per frame: the render
     * hook used to walk `entitiesForRendering()` (every entity, every frame) even with
     * zero TNT in the world (review pass-4 hot path #2). PrimedTnt physics advances at
     * 20 Hz, so a per-tick scan loses nothing visually; the render hook only integrates.
     */
    @Volatile
    private var liveTnt: List<TntSnapshot> = emptyList()

    private data class TntSnapshot(val x: Double, val y: Double, val z: Double, val dx: Double, val dy: Double, val dz: Double)

    override fun init() {
        category.add(enabled, launchPower, maxTicks, predictLiveTnt, range, showLandingBox, arcColor, landingColor, lineWidth)
        AxolotlClientCommon.getInstance().getConfig().addCategory(category)
        toggleKey.`br$registerOnConsumeClick` {
            enabled.toggle()
            AxolotlClientCommon.getInstance().saveConfig()
        }

        ClientTickEvents.END_CLIENT_TICK.register {
            if (!enabled.get() || !predictLiveTnt.get()) {
                liveTnt = emptyList()
                return@register
            }
            val client = Minecraft.getInstance()
            val level = client.level
            if (level == null) {
                liveTnt = emptyList()
                return@register
            }
            val maxDistSq = range.get().toDouble() * range.get()
            val camera = client.gameRenderer.mainCamera().position()
            val found = ArrayList<TntSnapshot>(4)
            for (entity in level.entitiesForRendering()) {
                if (entity !is PrimedTnt) continue
                if (entity.distanceToSqr(camera) > maxDistSq) continue
                val pos = entity.position()
                val delta = entity.deltaMovement
                found.add(TntSnapshot(pos.x, pos.y, pos.z, delta.x, delta.y, delta.z))
            }
            liveTnt = found
        }

        LevelRenderEvents.BEFORE_GIZMOS.register(LevelRenderEvents.BeforeGizmos { context ->
            if (!enabled.get()) {
                return@BeforeGizmos
            }
            val client = Minecraft.getInstance()
            val level = client.level
            val player = client.player
            if (level == null || player == null) {
                return@BeforeGizmos
            }
            val flights = ArrayList<Flight>()
            if (holdingTnt(player)) {
                val eye = player.getEyePosition()
                val look = player.lookAngle
                flights.add(
                    Flight(
                        eye.x, eye.y, eye.z,
                        look.x * launchPower.get(), look.y * launchPower.get(), look.z * launchPower.get(),
                        arcColor.get().toInt()
                    )
                )
            }
            // PrimedTnt's entity origin sits at its feet and its physics tick from
            // there, so the prediction integrates the origin, like the entity does.
            // The scan itself lives on the client tick (see liveTnt) — the render
            // hook only integrates the snapshot.
            for (t in liveTnt) {
                flights.add(Flight(t.x, t.y, t.z, t.dx, t.dy, t.dz, arcColor.get().toInt()))
            }
            if (flights.isEmpty()) {
                lastDrawnSegments = 0
                return@BeforeGizmos
            }
            val solid = worldSolidCheck(level)
            var segments = 0
            context.levelRenderer().collectPerFrameRenderThreadGizmos().use {
                for (flight in flights) {
                    segments += drawFlight(flight, solid)
                }
            }
            lastDrawnSegments = segments
        })
    }

    /** Number of gizmo segments submitted by the most recent frame (gametest evidence). */
    fun getLastDrawnSegments(): Int = lastDrawnSegments

    /**
     * Integrates and submits one flight: the arc as connected gizmo lines (every sampled point,
     * one line per pair) and, on collision, the landing blast box. Returns the segment count.
     */
    private fun drawFlight(flight: Flight, solid: TntTrajectory.SolidCheck): Int {
        val result = TntTrajectory.integrate(
            flight.x, flight.y, flight.z, flight.vx, flight.vy, flight.vz, maxTicks.get(), solid
        )
        val points = result.points
        var segments = 0
        val color = flight.color
        val width = lineWidth.get()
        for (i in 1 until points.size) {
            val from = points[i - 1]
            val to = points[i]
            Gizmos.line(Vec3(from.x, from.y, from.z), Vec3(to.x, to.y, to.z), color, width)
            segments++
        }
        if (result.collided && showLandingBox.get()) {
            // Visual merge with ExplosionBoxes: same radius its live boxes use, so the
            // prediction frame and the burning-TNT box line up exactly.
            val radius = ExplosionBoxes.getInstance().radius.get()
            val landing = result.landing
            val cx = landing.x
            val cy = landing.y + 0.5
            val cz = landing.z
            Gizmos.cuboid(
                AABB(cx - radius, cy - radius, cz - radius, cx + radius, cy + radius, cz + radius),
                GizmoStyle.stroke(landingColor.get().toInt(), width)
            )
            segments++
        }
        return segments
    }

    /** One predicted flight: launch state plus the color its arc is drawn with. */
    internal class Flight(
        @JvmField val x: Double, @JvmField val y: Double, @JvmField val z: Double,
        @JvmField val vx: Double, @JvmField val vy: Double, @JvmField val vz: Double,
        @JvmField val color: Int
    )

    companion object {
        private val instance = CannonTrajectory()

        @JvmStatic
        fun getInstance(): CannonTrajectory = instance

        @JvmStatic
        fun holdingTnt(player: LocalPlayer): Boolean =
            player.mainHandItem.`is`(Items.TNT) || player.offhandItem.`is`(Items.TNT)

        /**
         * The world's answer to [TntTrajectory.SolidCheck]: the block containing the point
         * must render solid. Unloaded chunks answer "not solid" (the flight simply continues).
         */
        @JvmStatic
        fun worldSolidCheck(level: ClientLevel): TntTrajectory.SolidCheck =
            TntTrajectory.SolidCheck { x, y, z -> level.getBlockState(BlockPos.containing(x, y, z)).isSolid }
    }
}
