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

import java.io.IOException
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.Comparator
import java.util.LinkedHashMap
import java.util.Optional
import java.util.UUID
import java.util.function.BooleanSupplier

import com.google.common.base.Preconditions
import com.google.gson.stream.JsonWriter
import io.github.axolotlclient.AxolotlClientCommon
import io.github.axolotlclient.AxolotlClientConfig.api.options.Option
import io.github.axolotlclient.AxolotlClientConfig.api.options.OptionCategory
import io.github.axolotlclient.AxolotlClientConfig.api.util.Colors
import io.github.axolotlclient.AxolotlClientConfig.impl.options.BooleanOption
import io.github.axolotlclient.AxolotlClientConfig.impl.options.ColorOption
import io.github.axolotlclient.AxolotlClientConfig.impl.options.FloatOption
import io.github.axolotlclient.bridge.events.Events
import io.github.axolotlclient.bridge.key.AxoKeybinding
import io.github.axolotlclient.bridge.key.AxoKeys
import io.github.axolotlclient.bridge.render.AxoRenderContext
import io.github.axolotlclient.bridge.render.AxoWindow
import io.github.axolotlclient.bridge.util.AxoIdentifier
import io.github.axolotlclient.bridge.util.AxoProfiler
import io.github.axolotlclient.config.profiles.ProfileAware
import io.github.axolotlclient.modules.AbstractCommonModule
import io.github.axolotlclient.modules.hud.gui.component.HudEntry
import io.github.axolotlclient.modules.hud.gui.component.Positionable
import io.github.axolotlclient.modules.hud.gui.entry.AbstractHudEntry
import io.github.axolotlclient.modules.hud.gui.hud.CompassHud
import io.github.axolotlclient.modules.hud.gui.hud.CoordsHud
import io.github.axolotlclient.modules.hud.gui.hud.IPHud
import io.github.axolotlclient.modules.hud.gui.hud.IconHud
import io.github.axolotlclient.modules.hud.gui.hud.MemoryHud
import io.github.axolotlclient.modules.hud.gui.hud.MouseMovementHud
import io.github.axolotlclient.modules.hud.gui.hud.simple.PlayerCountHud
import io.github.axolotlclient.modules.hud.gui.hud.PlayerHudCommon
import io.github.axolotlclient.modules.hud.gui.hud.PotionsHud
import io.github.axolotlclient.modules.hud.gui.hud.item.ArmorHud
import io.github.axolotlclient.modules.hud.gui.hud.item.ArrowHud
import io.github.axolotlclient.modules.hud.gui.hud.item.InventoryHud
import io.github.axolotlclient.modules.hud.gui.hud.item.ItemUpdateHud
import io.github.axolotlclient.modules.hud.gui.hud.simple.CPSHud
import io.github.axolotlclient.modules.hud.gui.hud.simple.ComboHud
import io.github.axolotlclient.modules.hud.gui.hud.simple.CustomHudEntry
import io.github.axolotlclient.modules.hud.gui.hud.simple.DayCounterHud
import io.github.axolotlclient.modules.hud.gui.hud.simple.FPSHud
import io.github.axolotlclient.modules.hud.gui.hud.simple.IRLTimeHud
import io.github.axolotlclient.modules.hud.gui.hud.simple.InfoHud
import io.github.axolotlclient.modules.hud.gui.hud.simple.PingHud
import io.github.axolotlclient.modules.hud.gui.hud.simple.ReachHud
import io.github.axolotlclient.modules.hud.gui.hud.simple.SpeedHud
import io.github.axolotlclient.modules.hud.gui.hud.simple.TPSHud
import io.github.axolotlclient.modules.hud.gui.hud.simple.ToggleSprintHud
import io.github.axolotlclient.modules.hud.gui.hud.simple.XPHud
import io.github.axolotlclient.modules.hud.gui.hud.vanilla.PlayerTabOverlayHud
import io.github.axolotlclient.modules.hud.gui.hud.vanilla.SubtitlesHudHud
import io.github.axolotlclient.modules.hud.gui.layout.SnapAnchorType
import io.github.axolotlclient.modules.hud.snapping.SnappingHelper
import io.github.axolotlclient.modules.hud.util.Rectangle
import io.github.axolotlclient.modules.hypixel.bedwars.BedwarsMod
import io.github.axolotlclient.util.GsonHelper
import io.github.axolotlclient.util.options.GenericOption

/**
 * This implementation of Hud modules is based on KronHUD.
 * <a href="https://github.com/DarkKronicle/KronHUD">Github Link.</a>
 *
 * <p>License: GPL-3.0</p>
 */
abstract class HudManagerCommon : AbstractCommonModule(), ProfileAware {
    private val key: AxoKeybinding = AxoKeybinding.create(AxoKeys.KEY_RSHIFT, "key.openHud")
    private val toggleHud: AxoKeybinding = AxoKeybinding.create(AxoKeys.KEY_UNKNOWN, "key.toggle_hud")
    private val hudCategory: OptionCategory = OptionCategory.create("hud")
    private val hudEditScreenCategory: OptionCategory = OptionCategory.create("hudEditScreen")
    private val snapping = BooleanOption("snapping", true)
    private val enabled = BooleanOption("enabled", true)
    @JvmField
    val hudLinkCreationEnabled = BooleanOption("hud_link_creation_enabled", true)
    private val hudLinkLineWidth = FloatOption("hud.hud_link_line_width", 3f, 1f, 10f)
    @JvmField
    val grabCornerColor = ColorOption("rescale_grab_corner_color", Colors.PINK)
    private val entries: MutableMap<AxoIdentifier, HudEntry> = LinkedHashMap()
    private val visitedEntries: ArrayDeque<HudEntry> = ArrayDeque()

    init {
        Preconditions.checkState(instance == null, "singleton already initialized")
        // bad antipattern, but whatever
        instance = this
    }

    override fun init() {
        key.`br$registerOnConsumeClick`(Runnable { openScreen() })
        toggleHud.`br$registerOnConsumeClick`(Runnable {
            enabled.toggle()
            AxolotlClientCommon.getInstance().saveConfig()
        })
        AxolotlClientCommon.getInstance().getConfig().addCategory(hudCategory)
        hudCategory.add(enabled, grabCornerColor, hudLinkCreationEnabled, hudLinkLineWidth)
        hudEditScreenCategory.add(snapping)
        AxolotlClientCommon.getInstance().getConfig().hidden.add(hudEditScreenCategory)
        add(PingHud())
        add(FPSHud())
        add(CPSHud())
        add(ArmorHud())
        add(PotionsHud())
        add(ToggleSprintHud())
        add(IPHud())
        add(IconHud())
        add(SpeedHud())
        add(CoordsHud())
        add(ArrowHud())
        add(ItemUpdateHud())
        add(IRLTimeHud())
        add(ReachHud())
        add(MemoryHud())
        add(PlayerCountHud())
        add(CompassHud())
        add(TPSHud())
        add(ComboHud())
        add(MouseMovementHud())
        add(DayCounterHud())
        add(InfoHud())
        add(InventoryHud())
        add(XPHud())
        add(PlayerTabOverlayHud())
        add(SubtitlesHudHud())

        addExtraHud()

        addNonConfigured(BedwarsMod.getInstance().upgradesOverlay)
        addNonConfigured(BedwarsMod.getInstance().resourceOverlay)
        addNonConfigured(BedwarsMod.getInstance().statsOverlay)
        addNonConfigured(BedwarsMod.getInstance().sessionStatsOverlay)

        entries.values.forEach { it.init() }

        hudCategory.add(
            GenericOption("hud.dependency_links", "hud.dependency_links.clear") {
                entries.values.forEach { it.clearBoundsDependencies() }
                saveHudDependencyLinks()
            }
        )
        hudCategory.add(
            GenericOption("hud.custom_entry", "hud.custom_entry.add") {
                val entry = CustomHudEntry(
                    AxoIdentifier.of(AxolotlClientCommon.MODID, "custom_hud/" + UUID.randomUUID())
                )
                entry.setEnabled(true)
                entry.init()
                entry.onBoundsUpdate()
                entries[entry.getId()] = entry
                hudCategory.add(entry.getAllOptions(), false)
                client.`br$reinitScreen`()
                saveCustomEntries()
            }
        )

        Events.CLIENT_START.register(Runnable {
            loadCustomEntries()
            loadHudDependencyLinks()
        })
        Events.CLIENT_STOP.register(Runnable {
            saveCustomEntries()
            saveHudDependencyLinks()
        })
    }

    override fun lateInit() {
        entries.values.forEach { it.postConfigLoad() }
        if (AxoWindow.getWindow() == null) {
            Events.CLIENT_READY.register(Runnable { refreshAllBounds() })
        } else {
            refreshAllBounds()
        }
    }

    private fun loadHudDependencyLinks() {
        try {
            val path = AxolotlClientCommon.resolveProfileConfigFile(HUD_DEPENDENCIES_SAVE_FILE_NAME)
            if (Files.exists(path)) {
                @Suppress("UNCHECKED_CAST")
                val obj = GsonHelper.read(Files.readString(path)) as Map<String, Any>
                obj.forEach { (name, o) ->
                    val hudId = AxoIdentifier.parse(name)
                    val hud = get(hudId) ?: return@forEach
                    @Suppress("UNCHECKED_CAST")
                    val deps = o as Map<String, Any>
                    if (deps.containsKey("x")) {
                        @Suppress("UNCHECKED_CAST")
                        val xs = deps["x"] as Map<String, String>
                        xs.forEach { (id, type) ->
                            val dep = get(AxoIdentifier.parse(id)) ?: return@forEach
                            val anchorType = SnapAnchorType.fromName(type) ?: return@forEach
                            hud.addBoundsDependency(dep, anchorType)
                        }
                    }
                    if (deps.containsKey("y")) {
                        @Suppress("UNCHECKED_CAST")
                        val ys = deps["y"] as Map<String, String>
                        ys.forEach { (id, type) ->
                            val dep = get(AxoIdentifier.parse(id)) ?: return@forEach
                            val anchorType = SnapAnchorType.fromName(type) ?: return@forEach
                            hud.addBoundsDependency(dep, anchorType)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AxolotlClientCommon.getInstance().logger.warn("Failed to load hud dependency links!", e)
        }
    }

    fun saveHudDependencyLinks() {
        try {
            val path = AxolotlClientCommon.resolveProfileConfigFile(HUD_DEPENDENCIES_SAVE_FILE_NAME)
            Files.createDirectories(path.getParent())
            val writer = Files.newBufferedWriter(path)
            val json = JsonWriter(writer)
            json.beginObject()
            for (entry in entries.entries) {
                val hudEntry = entry.value
                val dependenciesX = hudEntry.getDependenciesX()
                val dependenciesY = hudEntry.getDependenciesY()
                if (dependenciesX.isEmpty() && dependenciesY.isEmpty()) continue
                json.name(hudEntry.getId().toString())
                json.beginObject()
                if (dependenciesX.isNotEmpty()) {
                    json.name("x").beginObject()
                    for (e in dependenciesX.entries) {
                        json.name(e.key.getId().toString()).value(e.value.getName())
                    }
                    json.endObject()
                }
                if (dependenciesY.isNotEmpty()) {
                    json.name("y").beginObject()
                    for (e in dependenciesY.entries) {
                        json.name(e.key.getId().toString()).value(e.value.getName())
                    }
                    json.endObject()
                }
                json.endObject()
            }
            json.endObject()
            json.close()
        } catch (e: Exception) {
            AxolotlClientCommon.getInstance().logger.warn("Failed to save hud dependency links!", e)
        }
    }

    fun loadCustomEntries() {
        try {
            val path = AxolotlClientCommon.resolveProfileConfigFile(CUSTOM_MODULE_SAVE_FILE_NAME)
            if (Files.exists(path)) {
                @Suppress("UNCHECKED_CAST")
                val obj = GsonHelper.read(Files.readString(path)) as List<Any>
                obj.forEach { o ->
                    @Suppress("UNCHECKED_CAST")
                    val values = o as Map<String, Any>
                    val id: AxoIdentifier = if (values.containsKey("id")) {
                        AxoIdentifier.parse(values["id"] as String)
                    } else {
                        AxoIdentifier.of(AxolotlClientCommon.MODID, "custom_hud/" + UUID.randomUUID())
                    }
                    val entry = CustomHudEntry(id)
                    entry.getAllOptions().getOptions().forEach { opt ->
                        if (values.containsKey(opt.getName())) {
                            opt.fromSerializedValue(values[opt.getName()] as String)
                        }
                    }
                    entries[entry.getId()] = entry
                    hudCategory.add(entry.getAllOptions(), false)
                    entry.init()
                    entry.onBoundsUpdate()
                }
            }
        } catch (e: IOException) {
            AxolotlClientCommon.getInstance().logger.warn("Failed to load custom hud modules!", e)
        }
    }

    fun saveCustomEntries() {
        try {
            val path = AxolotlClientCommon.resolveProfileConfigFile(CUSTOM_MODULE_SAVE_FILE_NAME)
            Files.createDirectories(path.getParent())
            val writer = Files.newBufferedWriter(path)
            val json = JsonWriter(writer)
            json.beginArray()
            for (entry in entries.entries) {
                val hudEntry = entry.value
                if (hudEntry is CustomHudEntry) {
                    json.beginObject()
                    json.name("id").value(hudEntry.getId().toString())
                    for (opt in hudEntry.getCategory()!!.getOptions()) {
                        val value = opt.toSerializedValue()
                        if (value != null) {
                            json.name(opt.getName())
                            json.value(value)
                        }
                    }
                    json.endObject()
                }
            }
            json.endArray()
            json.close()
        } catch (e: IOException) {
            AxolotlClientCommon.getInstance().logger.warn("Failed to save custom hud modules!", e)
        }
    }

    final override fun tick() {
        AxoProfiler.get().`br$push`("hud_modules")
        entries.values.stream()
            .filter { hudEntry -> hudEntry.isEnabled() && hudEntry.tickable() }
            .forEach { hudEntry ->
                AxoProfiler.get().`br$push`(hudEntry.getNameKey())
                AxoProfiler.get().`br$push`("tick")
                hudEntry.tick()
                AxoProfiler.get().`br$pop`()
                AxoProfiler.get().`br$pop`()
            }
        AxoProfiler.get().`br$pop`()
    }

    fun add(entry: AbstractHudEntry): HudManagerCommon {
        addNonConfigured(entry)
        hudCategory.add(entry.getAllOptions())
        return this
    }

    fun addNonConfigured(entry: AbstractHudEntry): HudManagerCommon {
        entries[entry.getId()] = entry
        return this
    }

    fun refreshAllBounds() {
        for (entry in getEntries()) {
            entry.onBoundsUpdate()
        }
    }

    fun getEntries(): MutableList<HudEntry> {
        if (entries.isNotEmpty()) {
            return ArrayList(entries.values)
        }
        return ArrayList()
    }

    /**
     * Refills the reusable per-frame render order (the scratch list below) with every entry
     * and returns it. The render pass used to allocate a fresh copy of the entry map per
     * frame; the copy is only needed because the caller sorts it, and sorting does not
     * mutate the map, so one scratch list per manager serves every frame. Callers must not
     * retain the returned list past the current frame.
     */
    protected fun fillRenderOrder(): MutableList<HudEntry> {
        renderOrderScratch.clear()
        renderOrderScratch.addAll(entries.values)
        return renderOrderScratch
    }

    /** Scratch for [fillRenderOrder]; render-thread only (same thread that mutates [entries]). */
    private val renderOrderScratch = ArrayList<HudEntry>()

    fun get(identifier: AxoIdentifier): HudEntry? = entries[identifier]

    fun removeEntry(identifier: AxoIdentifier) {
        val removed = entries.remove(identifier)
        if (removed != null) {
            hudCategory.getSubCategories().remove(removed.getCategory())
        }
    }

    fun hudsEnabled(): Boolean = enabled.get()

    /**
     * Sets the global HUD toggle. Exists for the performance benchmark (hud_tree scene),
     * which measures the whole HUD pass on and off; player-facing code toggles via the
     * keybinding.
     */
    fun setHudsEnabled(value: Boolean) {
        enabled.set(value)
    }

    open fun render(context: AxoRenderContext, delta: Float) {
        if (!hudsEnabled()) return
        val ordered = fillRenderOrder()
        ordered.sortBy { it.getZLevel() }
        for (hud in ordered) {
            if (hud.isEnabled() && !hud.isHidden()) {
                hud.render(context, delta)
            }
        }
    }

    fun getEntryXY(x: Int, y: Int): Optional<HudEntry> {
        for (entry in getMoveableEntries()) {
            val bounds = entry.getTrueBounds()
            if (bounds.x() <= x && bounds.x() + bounds.width() >= x && bounds.y() <= y &&
                bounds.y() + bounds.height() >= y
            ) {
                return Optional.of(entry)
            }
        }
        return Optional.empty()
    }

    fun getMoveableEntries(): MutableList<HudEntry> {
        if (entries.isNotEmpty()) {
            return entries.values.stream()
                .filter { entry -> entry.isEnabled() && entry.movable() }
                .collect(java.util.stream.Collectors.toCollection { ArrayList() })
        }
        return ArrayList()
    }

    fun renderPlaceholder(context: AxoRenderContext, delta: Float) {
        for (hud in getEntries()) {
            if (hud.isEnabled()) {
                hud.renderPlaceholder(context, delta)
            }
        }
        for (hud in getEntries()) {
            if (hud.isEnabled()) {
                SnappingHelper.renderLinks(context, hud, hudLinkLineWidth.get() / 2f)
            }
        }
    }

    fun getAllBounds(): MutableList<Rectangle> =
        getMoveableEntries()
            .stream()
            .map { positionable: Positionable -> positionable.getTrueBounds() }
            .collect(java.util.stream.Collectors.toCollection { ArrayList() })

    override fun reloadConfig() {
        entries.entries.removeIf { entry ->
            val value = entry.value
            if (value is CustomHudEntry) {
                hudCategory.getSubCategoryMap().remove(value.getAllOptions())
                true
            } else {
                false
            }
        }
        entries.values.forEach { it.clearBoundsDependencies() }
        loadCustomEntries()
        loadHudDependencyLinks()
        for (hud in getEntries()) {
            if (hud is ProfileAware) {
                hud.reloadConfig()
            }
        }
    }

    override fun saveConfig() {
        saveCustomEntries()
        saveHudDependencyLinks()
        for (hud in getEntries()) {
            if (hud is ProfileAware) {
                hud.saveConfig()
            }
        }
    }

    fun updateBoundsDependencies(origin: HudEntry) {
        if (visitedEntries.contains(origin)) return
        visitedEntries.push(origin)
        val entries = HudManagerCommon.getInstance()!!.getMoveableEntries()
        entries.remove(origin)
        entries.removeIf { e ->
            visitedEntries.stream().anyMatch { v ->
                v.dependsOnY(e).or { v.dependsOnX(e) }.isPresent
            }
        }
        for (entry in entries) {
            entry.dependsOnX(origin).ifPresent { type -> type.updatePosX(origin, entry) }
            entry.dependsOnY(origin).ifPresent { type -> type.updatePosY(origin, entry) }
        }
        visitedEntries.pop()
    }

    fun isSnappingEnabled(): Boolean = snapping.get()

    fun toggleSnapping() {
        snapping.toggle()
        AxolotlClientCommon.getInstance().saveConfig()
    }

    protected abstract fun openScreen()

    protected abstract fun addExtraHud()

    abstract fun closeScreen()

    companion object {
        const val HUD_RESCALE_GRAB_TOLERANCE: Int = 5
        private const val HUD_DEPENDENCIES_SAVE_FILE_NAME = "hud_dependencies.json"
        private const val CUSTOM_MODULE_SAVE_FILE_NAME = "custom_hud.json"

        // Set by the version-specific HudManager; common code can't see the F3 overlay.
        @JvmStatic
        var debugScreenShown: BooleanSupplier = BooleanSupplier { false }

        private var instance: HudManagerCommon? = null

        @JvmStatic
        fun getInstance(): HudManagerCommon? = instance

        @JvmStatic
        fun isDebugScreenShown(): Boolean = debugScreenShown.asBoolean
    }
}
