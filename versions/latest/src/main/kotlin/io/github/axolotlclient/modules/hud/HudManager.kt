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

package io.github.axolotlclient.modules.hud

import io.github.axolotlclient.bridge.render.AxoRenderContext
import io.github.axolotlclient.imperium.menu.ImperiumMenuScreen
import io.github.axolotlclient.modules.hud.gui.component.HudEntry
import io.github.axolotlclient.modules.hud.gui.hud.KeystrokeHud
import io.github.axolotlclient.modules.hud.gui.hud.PackDisplayHud
import io.github.axolotlclient.modules.hud.gui.hud.PlayerHud
import io.github.axolotlclient.modules.hud.gui.hud.simple.BreadcrumbsHud
import io.github.axolotlclient.modules.hud.gui.hud.simple.CooldownsHud
import io.github.axolotlclient.modules.hud.gui.hud.simple.LegionHud
import io.github.axolotlclient.modules.hud.gui.hud.simple.BoostsHud
import io.github.axolotlclient.modules.hud.gui.hud.simple.BlockCoordinatesHud
import io.github.axolotlclient.modules.hud.gui.hud.simple.EntityCountHud
import io.github.axolotlclient.modules.hud.gui.hud.simple.NowPlayingHud
import io.github.axolotlclient.modules.hud.gui.hud.simple.WaypointsHud
import io.github.axolotlclient.modules.hud.gui.hud.vanilla.ActionBarHud
import io.github.axolotlclient.modules.hud.gui.hud.vanilla.BossBarHud
import io.github.axolotlclient.modules.hud.gui.hud.vanilla.CrosshairHud
import io.github.axolotlclient.modules.hud.gui.hud.vanilla.DebugCountersHud
import io.github.axolotlclient.modules.hud.gui.hud.vanilla.HotbarHud
import io.github.axolotlclient.modules.hud.gui.hud.vanilla.ScoreboardHud
import io.github.axolotlclient.modules.hud.gui.hud.vanilla.TitleHud
import io.github.axolotlclient.modules.minimap.MinimapHud
import io.github.axolotlclient.util.HudFrameCache
import net.minecraft.client.Minecraft
import net.minecraft.util.profiling.Profiler
import java.util.function.BooleanSupplier

/**
 * This implementation of Hud modules is based on KronHUD.
 * <a href="https://github.com/DarkKronicle/KronHUD">Github Link.</a>
 *
 * <p>License: GPL-3.0</p>
 */
class HudManager : HudManagerCommon() {

    override fun openScreen() {
        Minecraft.getInstance().gui.setScreen(ImperiumMenuScreen(null))
    }

    override fun addExtraHud() {
        HudManagerCommon.debugScreenShown = BooleanSupplier { Minecraft.getInstance().gui.hud.getDebugOverlay().showDebugScreen() }
        add(ActionBarHud())
        add(BossBarHud())
        add(CrosshairHud())
        add(DebugCountersHud())
        add(HotbarHud())
        add(ScoreboardHud())
        add(TitleHud())
        add(CooldownsHud())
        add(BlockCoordinatesHud())
        add(EntityCountHud())
        add(io.github.axolotlclient.modules.hud.gui.hud.simple.ObsidianCountHud())
        add(io.github.axolotlclient.modules.hud.gui.hud.simple.PlaytimeHud())
        add(io.github.axolotlclient.modules.hud.gui.hud.simple.PotionCountHud())
        add(io.github.axolotlclient.modules.hud.gui.hud.simple.StopwatchHud())
        add(WaypointsHud())
        add(MinimapHud())
        add(BoostsHud())
        add(LegionHud())
        add(BreadcrumbsHud())
        add(NowPlayingHud())
        add(KeystrokeHud())
        add(PackDisplayHud())
        add(PlayerHud())
    }

    override fun render(context: AxoRenderContext, delta: Float) {
        val mc = Profiler.get()
        mc.push("Hud render")
        if (hudsEnabled() && Minecraft.getInstance().gui.screen() !is HudEditScreen) {
            // hud_frame_limit: cacheable entries (see HudEntry.dynamicContent) re-record at
            // most N times per second and are re-submitted from the recording in between;
            // dynamic entries always render live. Mirrors HudManagerCommon.render's z-sorted
            // loop, partitioned so the cache can wrap each cacheable entry's render. The
            // ordered list is the reusable scratch (no per-frame copy allocation).
            val ordered = fillRenderOrder()
            ordered.sortWith(Comparator.comparingInt { obj: HudEntry -> obj.getZLevel() })
            HudFrameCache.render(context, delta, ordered)
        }
        mc.pop()
    }

    override fun closeScreen() {
        val screen = Minecraft.getInstance().gui.screen()
        screen?.onClose()
    }

    companion object {
        // getInstance() is inherited from HudManagerCommon (its Kotlin companion JvmStatic is
        // final and cannot be static-hidden); this field only forces eager singleton
        // construction when THIS class is loaded - the FeatureDisabler/Kotlin-companion trap.
        @JvmField
        val instance = HudManager()

        /**
         * Class-loading accessor: touching this method runs the eager val above. The inherited
         * companion getInstance() resolves against HudManagerCommon WITHOUT loading this class, so
         * callers that must guarantee construction (module registration) go through here instead.
         */
        @JvmStatic
        fun construct(): HudManager = instance

        // Kotlin callers cannot reach the BASE companion's getInstance through the subclass
        // name the way Java could (and the base variant resolves without loading this class,
        // which is exactly the wave-5a trap). This own-companion delegate both fixes the
        // Kotlin call shape and guarantees the eager construction on first use.
        @JvmStatic
        fun getInstance(): HudManager = instance
    }
}
