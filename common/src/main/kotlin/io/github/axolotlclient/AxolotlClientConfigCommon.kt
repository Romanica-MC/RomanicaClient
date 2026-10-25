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

package io.github.axolotlclient

import java.time.format.DateTimeFormatter
import java.util.Locale

import io.github.axolotlclient.AxolotlClientConfig.api.options.Option
import io.github.axolotlclient.AxolotlClientConfig.api.options.OptionCategory
import io.github.axolotlclient.AxolotlClientConfig.api.util.Color
import io.github.axolotlclient.AxolotlClientConfig.api.util.Colors
import io.github.axolotlclient.AxolotlClientConfig.impl.options.BooleanOption
import io.github.axolotlclient.AxolotlClientConfig.impl.options.ColorOption
import io.github.axolotlclient.AxolotlClientConfig.impl.options.DoubleOption
import io.github.axolotlclient.AxolotlClientConfig.impl.options.EnumOption
import io.github.axolotlclient.AxolotlClientConfig.impl.options.FloatOption
import io.github.axolotlclient.AxolotlClientConfig.impl.options.IntegerOption
import io.github.axolotlclient.AxolotlClientConfig.impl.options.StringOption
import io.github.axolotlclient.bridge.events.Events
import io.github.axolotlclient.bridge.key.AxoKeybinding
import io.github.axolotlclient.bridge.key.AxoKeys
import io.github.axolotlclient.util.options.ForceableBooleanOption
import io.github.axolotlclient.util.options.GenericOption
import net.fabricmc.loader.api.FabricLoader

abstract class AxolotlClientConfigCommon {
    enum class MenuButtonMode {
        DISABLED,
        MODMENU {
            override fun showButton(): Boolean =
                !(FabricLoader.getInstance().isModLoaded("modmenu") && !FabricLoader.getInstance().isModLoaded("axolotlclient-modmenu"))
        },
        ALWAYS {
            override fun showButton(): Boolean = true
        };

        override fun toString(): String = "menu_button_mode." + super.toString().lowercase(Locale.ROOT)

        open fun showButton(): Boolean = false
    }

    enum class TabBadgeMode {
        BEFORE_NAME,
        BEFORE_NAME_ALIGNED,
        BEFORE_PING;

        override fun toString(): String = "tab_badge_mode." + super.toString().lowercase(Locale.ROOT)
    }

    /**
     * Block overlay highlight modes: outline lines only (vanilla default), a translucent fill
     * of the targeted block's faces, or both at once.
     */
    enum class OutlineMode {
        OUTLINE,
        FILL,
        BOTH;

        override fun toString(): String = "block_outlines.mode." + super.toString().lowercase(Locale.ROOT)
    }

    enum class NametagBackgroundMode {
        NONE,
        TRANSLUCENT,
        OPAQUE;

        override fun toString(): String = "nametagBackground." + super.toString().lowercase(Locale.ROOT)
    }

    enum class WeatherOverride {
        OFF,
        CLEAR,
        RAIN,
        THUNDER;

        override fun toString(): String = "weather." + super.toString().lowercase(Locale.ROOT)
    }

    /**
     * Hurt camera tilt modes: vanilla tilt, a scaled-down tilt (30%), or none at all.
     * Composes with the vanilla accessibility damage-tilt-strength slider.
     */
    enum class HurtCamMode {
        FULL,
        SUBTLE,
        OFF;

        override fun toString(): String = "hurt_cam." + super.toString().lowercase(Locale.ROOT)

        /**
         * Multiplier applied to the hurt-camera roll angle; OFF cancels the roll entirely.
         */
        fun tiltMultiplier(): Float = if (this == SUBTLE) 0.3f else 1.0f
    }

    /**
     * How the low-health tint is drawn: the vanilla vignette texture tinted towards the
     * configured colour, or a flat translucent fill of the whole screen.
     */
    enum class LowHpTintStyle {
        VIGNETTE,
        FLAT;

        override fun toString(): String = "low_hp_tint.style." + super.toString().lowercase(Locale.ROOT)
    }

    /**
     * When the built-in frame limiter (performance category, M3) is allowed to cap the
     * framerate: never, only while the window is minimized, or whenever the window is
     * minimized or unfocused. The AFK caps escalate on top of whichever window state
     * applies; the limiter always defers to vanilla's inactivity throttling when vanilla
     * is already the stricter of the two (see io.github.axolotlclient.util.FrameLimiter).
     */
    enum class FrameLimiterMode {
        OFF,
        MINIMIZED,
        UNFOCUSED;

        override fun toString(): String = "limiter." + super.toString().lowercase(Locale.ROOT)

        /**
         * Whether the limiter's window-state condition holds; AFK caps may only apply on
         * top of this (OFF gates everything).
         */
        fun applies(minimized: Boolean, focused: Boolean): Boolean = when (this) {
            OFF -> false
            MINIMIZED -> minimized
            UNFOCUSED -> minimized || !focused
        }
    }

    /**
     * Crop growth stage overlay style: text ("3/8") or a small progress bar near the crosshair.
     */
    enum class FarmingOverlayStyle {
        TEXT,
        BAR;

        override fun toString(): String = "farming.style." + super.toString().lowercase(Locale.ROOT)
    }

    /**
     * Visual identity of the Imperium module menu: the UI v3 Imperium theme (bronze on warm
     * dark, docs/design/README.md), with the carved chiseled-stone theme and the original flat
     * dark-and-gold look kept as fallbacks.
     */
    enum class MenuTheme {
        IMPERIUM,
        CARVED,
        CLASSIC;

        override fun toString(): String = "menu.theme." + super.toString().lowercase(Locale.ROOT)
    }

    // options

    @JvmField
    val showOwnNametag = BooleanOption("showOwnNametag", false)
    @JvmField
    val useShadows = BooleanOption("useShadows", false)
    @JvmField
    val nametagBackground = EnumOption("nametagBackground", NametagBackgroundMode::class.java, NametagBackgroundMode.TRANSLUCENT)
    @JvmField
    val nametagScale = DoubleOption("nametagScale", 1.0, 0.5, 2.0)

    @JvmField
    val showBadges = BooleanOption("showBadges", true)
    @JvmField
    val customBadge = BooleanOption("customBadge", false)
    @JvmField
    val badgeText = StringOption("badgeText", "")
    @JvmField
    val tabBadgeMode = EnumOption("tab_badge_mode", TabBadgeMode::class.java, TabBadgeMode.BEFORE_NAME)

    @JvmField
    val timeChangerEnabled = ForceableBooleanOption("enabled", false)
    @JvmField
    val customTime = IntegerOption("time", 0, 0, 24000)
    @JvmField
    val timeMultiplier = FloatOption("time_multiplier", 1.0f, 0.1f, 10.0f)
    @JvmField
    val weather = EnumOption("weather", WeatherOverride::class.java, WeatherOverride.OFF)
    @JvmField
    val weatherIntensity = FloatOption("weather_intensity", 1.0f, 0.0f, 1.0f)

    @JvmField
    val dynamicFOV = BooleanOption("dynamicFov", true)
    @JvmField
    val fullBright = ForceableBooleanOption("fullBright", false)
    @JvmField
    val removeVignette = BooleanOption("removeVignette", false)
    @JvmField
    val lowFire = ForceableBooleanOption("lowFire", false)

    // fire_height (Alpine client grab-bag, wave 3): how far the first-person fire overlay is
    // pushed down while lowFire is on, in blocks (0.2 was the old fixed offset).
    @JvmField
    val fireHeight = FloatOption("fire_height", 0.2f, 0.0f, 1.0f)

    // pumpkin/spyglass/frost overlay opacity (Alpine client grab-bag, wave 3): percent of
    // the vanilla overlay alpha — 100 is vanilla, 0 removes the overlay entirely.
    @JvmField
    val pumpkinOpacity = IntegerOption("pumpkin_opacity", 100, 0, 100)
    @JvmField
    val spyglassOpacity = IntegerOption("spyglass_opacity", 100, 0, 100)
    @JvmField
    val frostOpacity = IntegerOption("frost_opacity", 100, 0, 100)
    @JvmField
    val hitColor = ColorOption("hitColor", Color(0x4DFF0000), { this.updateHitColor(it) })
    @JvmField
    val hitColorOnArmor = BooleanOption("hit_color_on_armor", false)

    // hit_color/color_trim (Alpine parity): also flash armour TRIMS with the hit colour.
    @JvmField
    val hitColorOnTrims = BooleanOption("hit_color_on_trims", false)

    @JvmField
    val minimalViewBob = BooleanOption("minimalViewBob", false)
    @JvmField
    val hurtCam = EnumOption("hurt_cam", HurtCamMode::class.java, HurtCamMode.FULL)
    @JvmField
    val hideChat = BooleanOption("hide_chat", false)

    // clear_water (Alpine parity): push the underwater fog out towards the render distance.
    @JvmField
    val clearWater = BooleanOption("clear_water", false)

    // clear_water/disable_liquid_fov: skip vanilla's fluid FOV shrink while underwater.
    @JvmField
    val clearWaterDisableLiquidFov = BooleanOption("clear_water.disable_liquid_fov", false)

    // clear_water/overlay_transparency: alpha of the underwater screen overlay, in percent
    // (10 matches vanilla's 0.1; 0 removes the overlay entirely). Gated by clear_water.
    @JvmField
    val waterOverlayTransparency = IntegerOption("clear_water.overlay_transparency", 10, 0, 100)

    // low_hp_tint (Alpine parity): red vignette/tint when below a health threshold.
    // The tint colour's alpha is the peak intensity reached at 1 hp.
    @JvmField
    val lowHpTint = BooleanOption("enabled", false)
    @JvmField
    val lowHpThreshold = IntegerOption("low_hp_threshold", 6, 1, 19)
    @JvmField
    val lowHpTintStyle = EnumOption("low_hp_tint_style", LowHpTintStyle::class.java, LowHpTintStyle.VIGNETTE)
    @JvmField
    val lowHpTintColor = ColorOption("low_hp_tint_color", Color(0x66FF0000))

    // low_hp_tint/adaptive_opacity: ramp the intensity with health (threshold -> 1 hp); when
    // off, the tint draws at the colour's full alpha the whole time it is below the threshold.
    // min_opacity floors the ramped alpha, in percent of the tint colour's alpha.
    @JvmField
    val lowHpAdaptiveOpacity = BooleanOption("low_hp_tint.adaptive_opacity", true)
    @JvmField
    val lowHpMinOpacity = IntegerOption("low_hp_tint.min_opacity", 0, 0, 100)

    // farming (Alpine parity): crop growth stage overlay near the crosshair.
    @JvmField
    val farmingOverlay = BooleanOption("enabled", false)
    @JvmField
    val farmingHoeOnly = BooleanOption("farming.hoe_only", false)
    @JvmField
    val farmingStyle = EnumOption("farming.style", FarmingOverlayStyle::class.java, FarmingOverlayStyle.TEXT)

    // farming protections (Alpine parity, wave 3): veto breaking the bottom block of a
    // still-growing cane/bamboo stack (one misclick would fell the whole plant), plus
    // render-hides for cactus and tripwire string. The vetoes are enforced client-side in
    // MultiPlayerGameModeMixin (no break packet is ever sent); the render-hides are
    // enforced in SectionCompilerMixin, and a toggle flip re-meshes the world through
    // onFarmingRenderHidesChanged (the chunk mesh is otherwise static, baked geometry).
    @JvmField
    val farmingProtectBottomCane = BooleanOption("farming.prevent_breaking_bottom_cane", true)
    @JvmField
    val farmingProtectBottomBamboo = BooleanOption("farming.prevent_breaking_bottom_bamboo", true)
    @JvmField
    val farmingDisableCactusRendering = BooleanOption("farming.disable_cactus_rendering", false) { onFarmingRenderHidesChanged() }
    @JvmField
    val farmingDisableStringRendering = BooleanOption("farming.disable_string_rendering", false) { onFarmingRenderHidesChanged() }

    // chunk_borders (Alpine parity): chunk (and optional region) boundary gizmo lines.
    @JvmField
    val chunkBorders = BooleanOption("enabled", false)
    @JvmField
    val regionBorders = BooleanOption("chunk_borders.regions", false)
    @JvmField
    val chunkBorderColor = ColorOption("chunk_borders.color", Color(0x80FFFF00.toInt()))
    @JvmField
    val regionBorderColor = ColorOption("chunk_borders.region_color", Color(0x80FF5555.toInt()))

    // chunk_borders/line_thickness: gizmo line width of the chunk cell (region lines are one
    // pixel heavier so they stay distinguishable at any thickness).
    @JvmField
    val chunkBorderThickness = IntegerOption("chunk_borders.line_thickness", 2, 1, 10)

    // light_level (Photon parity): keybind-toggled spawn-safety overlay tinting block tops by
    // effective light level (green = light > 7 after the sky-darkening scale, red = mobs can spawn).
    @JvmField
    val lightOverlay = BooleanOption("enabled", false)
    @JvmField
    val lightOverlayRadius = IntegerOption("light_level.radius", 16, 8, 32)
    @JvmField
    val lightOverlaySafeColor = ColorOption("light_level.safe_color", Color(0xA000E000.toInt()))
    @JvmField
    val lightOverlayUnsafeColor = ColorOption("light_level.unsafe_color", Color(0xA0FF3000.toInt()))

    // performance (Alpine parity, M3): ImperiumClient's own frame limiter, replacing Dynamic
    // FPS, plus cheap render-hide toggles. The limiter defers to vanilla's inactivity
    // throttling whenever vanilla is already the stricter of the two.
    @JvmField
    val frameLimiter = EnumOption("limiter", FrameLimiterMode::class.java, FrameLimiterMode.OFF)
    @JvmField
    val backgroundFps = IntegerOption("background_fps", 30, 1, 60)
    @JvmField
    val afkFps = IntegerOption("afk_fps", 15, 1, 60)
    @JvmField
    val afkDelay = IntegerOption("afk_delay", 60, 1, 3600)
    @JvmField
    val longAfkFps = IntegerOption("long_afk_fps", 5, 1, 60)
    @JvmField
    val longAfkDelay = IntegerOption("long_afk_delay", 300, 1, 7200)
    @JvmField
    val disableTntFlash = BooleanOption("disable_tnt_flash", false)
    @JvmField
    val hideLightningBolts = BooleanOption("hide_lightning_bolts", false)
    @JvmField
    val hideHoppers = BooleanOption("hide_hoppers", false)
    @JvmField
    val hideDistantEntities = BooleanOption("hide_distant_entities", false)
    @JvmField
    val entityRenderDistance = IntegerOption("entity_render_distance", 64, 16, 128)

    // hud_frame_limit (Alpine parity, M3): HUD elements whose content does not change on its
    // own re-record into the GUI render state at most this many times per second; between
    // records the previously recorded elements are replayed unchanged. 0 disables the cache.
    @JvmField
    val hudFrameLimit = IntegerOption("hud_frame_limit", 0, 0, 120)

    // text_render_distance (Alpine parity, M3): sign and name-tag text is not rendered beyond
    // this distance. 0 keeps vanilla behaviour (everything in range renders).
    @JvmField
    val textRenderDistance = IntegerOption("text_render_distance", 0, 0, 128)

    // disable_tnt_expansion (Alpine parity, M3): primed TNT keeps its normal size during the
    // final swell instead of scaling up shortly before exploding.
    @JvmField
    val disableTntExpansion = BooleanOption("disable_tnt_expansion", false)

    // optimize_spawners / disable_enchant_table_books (Alpine parity, M3): skip the spinning
    // mob inside monster spawners and the floating book above enchanting tables.
    @JvmField
    val optimizeSpawners = BooleanOption("optimize_spawners", false)
    @JvmField
    val disableEnchantTableBooks = BooleanOption("disable_enchant_table_books", false)

    @JvmField
    val compactChat = BooleanOption("compact_chat", false)

    // compact_chat sub-settings (Alpine profile M6): time-based stacking re-finds the
    // earlier duplicate anywhere in the chat within the window instead of only at the
    // head; ignore_blank keeps whitespace-only lines from ever stacking; ignoring line
    // breaks normalizes embedded newlines before comparing.
    @JvmField
    val compactTimeBased = BooleanOption("compact_time_based", false)
    @JvmField
    val compactTimeWindow = IntegerOption("compact_time_window", 5, 1, 60)
    @JvmField
    val compactIgnoreBlank = BooleanOption("compact_ignore_blank", true)
    @JvmField
    val compactIgnoreLineBreaks = BooleanOption("compact_ignore_line_breaks", true)

    @JvmField
    val chatTimestamps = BooleanOption("chat_timestamps", false)

    /** Chat timestamp layouts (Alpine "timestamp_format", M6). */
    enum class ChatTimestampFormat(private val pattern: String) {
        HH_MM("HH:mm"),
        HH_MM_SS("HH:mm:ss"),
        HH_MM_12("h:mm a");

        fun formatter(): DateTimeFormatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT)

        override fun toString(): String = "chat_timestamps.format." + name.lowercase(Locale.ROOT)
    }

    @JvmField
    val chatTimestampFormat = EnumOption("chat_timestamps.format", ChatTimestampFormat::class.java, ChatTimestampFormat.HH_MM)
    @JvmField
    val chatEmptyFilter = BooleanOption("empty_chat_filter", false)
    @JvmField
    val chatNameHighlight = BooleanOption("name_highlight", false)
    @JvmField
    val chatNamePing = BooleanOption("name_highlight_ping", true)
    @JvmField
    val chatHoverHighlight = BooleanOption("hover_highlight", false)
    @JvmField
    val chatHoverHighlightColor = ColorOption("hover_highlight_color", Color(0x33FFFFFF))
    @JvmField
    val chatPeek = BooleanOption("peek_chat", false)
    @JvmField
    val peekChatKey: AxoKeybinding

    // smooth_chat: the chat scroll offset lerps toward its target over a short window instead
    // of snapping line-by-line (new messages slide in, wheel scrolls glide).
    @JvmField
    val smoothChat = BooleanOption("smooth_chat", true)
    @JvmField
    val smoothChatDuration = IntegerOption("smooth_chat_duration_ms", 120, 50, 400)

    // persistent_chat: the visible chat history is serialized on disconnect and restored on
    // the next join, so it survives relogs.
    @JvmField
    val persistentChat = BooleanOption("persistent_chat", true)
    @JvmField
    val clearChatHistoryKey: AxoKeybinding

    // chat_screenshot_button: draws a small camera button on the chat screen that saves a
    // cropped screenshot of just the chat region.
    @JvmField
    val chatScreenshotButton = BooleanOption("chat_screenshot_button", true)

    @JvmField
    val enableCustomOutlines = BooleanOption("enabled", false)
    @JvmField
    val outlineColor = ColorOption("color", Color(0x66000000))
    @JvmField
    val outlineWidth = IntegerOption("outlineWidth", 1, 1, 15)
    @JvmField
    val outlineMode = EnumOption("block_outlines.mode", OutlineMode::class.java, OutlineMode.OUTLINE)
    @JvmField
    val outlineFill = BooleanOption("block_outlines.fill", false)
    @JvmField
    val outlineFillColor = ColorOption("block_outlines.fill_color", Colors.WINE_RED.withAlpha(165))

    @JvmField
    val customWindowTitle = BooleanOption("customWindowTitle", true) { this.updateWindowTitle(it) }
    @JvmField
    val confirmDisconnect = BooleanOption("confirm_disconnect", false)

    @JvmField
    val general: OptionCategory = OptionCategory.create("general")
    @JvmField
    val nametagOptions: OptionCategory = OptionCategory.create("nametagOptions")
    @JvmField
    val rendering: OptionCategory = OptionCategory.create("rendering")
    @JvmField
    val outlines: OptionCategory = OptionCategory.create("blockOutlines")
    @JvmField
    val timeChanger: OptionCategory = OptionCategory.create("timeChanger")
    @JvmField
    val chatSettings: OptionCategory = OptionCategory.create("chatSettings")
    @JvmField
    val lowHpTintCategory: OptionCategory = OptionCategory.create("lowHpTint")
    @JvmField
    val farming: OptionCategory = OptionCategory.create("farming")
    @JvmField
    val chunkBordersCategory: OptionCategory = OptionCategory.create("chunkBorders")
    @JvmField
    val lightOverlayCategory: OptionCategory = OptionCategory.create("lightLevelOverlay")
    @JvmField
    val performance: OptionCategory = OptionCategory.create("performance")

    @JvmField
    val creditsBGM = BooleanOption("creditsBGM", true)
    @JvmField
    val debugLogOutput = BooleanOption("debugLogOutput", false)

    @JvmField
    val noRain = BooleanOption("noRain", false)

    @JvmField
    val noAltIcons = BooleanOption("no_alt_icons", false)

    @JvmField
    val config: OptionCategory = OptionCategory.create("config")
    @JvmField
    val hidden: OptionCategory = OptionCategory.create("storedOptions")
    @JvmField
    val someNiceBackground = BooleanOption("defNoSecret", false)
    @JvmField
    val datetimeFormat = StringOption("datetime_format", "yyyy/MM/dd HH:mm:ss") { s: String ->
        dateTimeFormatter = DateTimeFormatter.ofPattern(s)
    }
    @JvmField
    val titleScreenOptionButtonMode = EnumOption("title_screen_button_mode", MenuButtonMode::class.java, MenuButtonMode.MODMENU)
    @JvmField
    val gameMenuScreenOptionButtonMode = EnumOption("game_menu_screen_button_mode", MenuButtonMode::class.java, MenuButtonMode.MODMENU)

    // menu.theme: Imperium (UI v3) is the default, CARVED/CLASSIC stay as fallbacks.
    @JvmField
    val menuTheme = EnumOption("menu.theme", MenuTheme::class.java, MenuTheme.IMPERIUM)

    // Spec "Reduce motion": disables every menu animation (nav pill slide, hover colour/scale).
    @JvmField
    val menuReduceMotion = BooleanOption("menu.reduce_motion", false)

    @JvmField
    var dateTimeFormatter: DateTimeFormatter? = null

    init {
        config.add(general)
        config.add(rendering)
        config.add(chatSettings)
        config.add(hidden)

        chatSettings.add(compactChat)
        chatSettings.add(compactTimeBased, compactTimeWindow)
        chatSettings.add(compactIgnoreBlank, compactIgnoreLineBreaks)
        chatSettings.add(chatTimestamps)
        chatSettings.add(chatTimestampFormat)
        chatSettings.add(chatEmptyFilter)
        chatSettings.add(chatNameHighlight, chatNamePing)
        chatSettings.add(chatHoverHighlight, chatHoverHighlightColor)
        chatSettings.add(chatPeek)
        chatSettings.add(smoothChat, smoothChatDuration)
        chatSettings.add(persistentChat)
        chatSettings.add(chatScreenshotButton)

        rendering.add(outlines)
        rendering.add(nametagOptions)

        nametagOptions.add(showOwnNametag)
        nametagOptions.add(useShadows)
        nametagOptions.add(nametagBackground)
        nametagOptions.add(nametagScale)

        general.add(customWindowTitle)
        general.add(debugLogOutput)

        general.add(datetimeFormat)
        general.add(titleScreenOptionButtonMode)
        general.add(gameMenuScreenOptionButtonMode)
        general.add(menuTheme)
        general.add(menuReduceMotion)
        general.add(confirmDisconnect)

        timeChanger.add(timeChangerEnabled)
        timeChanger.add(customTime)
        timeChanger.add(timeMultiplier)
        timeChanger.add(GenericOption("time_changer.preset.day", "time_changer.set_preset") { customTime.set(1000) })
        timeChanger.add(GenericOption("time_changer.preset.noon", "time_changer.set_preset") { customTime.set(6000) })
        timeChanger.add(GenericOption("time_changer.preset.night", "time_changer.set_preset") { customTime.set(13000) })
        timeChanger.add(GenericOption("time_changer.preset.midnight", "time_changer.set_preset") { customTime.set(18000) })
        timeChanger.add(weather)
        timeChanger.add(weatherIntensity)

        outlines.add(enableCustomOutlines)
        outlines.add(outlineColor)
        outlines.add(outlineWidth, outlineMode, outlineFill, outlineFillColor)

        rendering.add(timeChanger)

        rendering.add(
            dynamicFOV,
            fullBright,
            removeVignette,
            lowFire,
            fireHeight,
            pumpkinOpacity,
            spyglassOpacity,
            frostOpacity,
            minimalViewBob,
            hurtCam,
            clearWater,
            clearWaterDisableLiquidFov,
            waterOverlayTransparency,
            noRain,
            hideChat,
            hitColor,
            hitColorOnArmor,
            hitColorOnTrims
        )

        lowHpTintCategory.add(lowHpTint)
        lowHpTintCategory.add(lowHpThreshold)
        lowHpTintCategory.add(lowHpTintStyle)
        lowHpTintCategory.add(lowHpTintColor)
        lowHpTintCategory.add(lowHpAdaptiveOpacity)
        lowHpTintCategory.add(lowHpMinOpacity)
        rendering.add(lowHpTintCategory)

        farming.add(farmingOverlay)
        farming.add(farmingHoeOnly)
        farming.add(farmingStyle)
        farming.add(farmingProtectBottomCane)
        farming.add(farmingProtectBottomBamboo)
        farming.add(farmingDisableCactusRendering)
        farming.add(farmingDisableStringRendering)
        rendering.add(farming)

        chunkBordersCategory.add(chunkBorders)
        chunkBordersCategory.add(regionBorders)
        chunkBordersCategory.add(chunkBorderThickness)
        chunkBordersCategory.add(chunkBorderColor)
        chunkBordersCategory.add(regionBorderColor)
        rendering.add(chunkBordersCategory)

        lightOverlayCategory.add(lightOverlay)
        lightOverlayCategory.add(lightOverlayRadius)
        lightOverlayCategory.add(lightOverlaySafeColor)
        lightOverlayCategory.add(lightOverlayUnsafeColor)
        rendering.add(lightOverlayCategory)

        performance.add(frameLimiter, backgroundFps, afkFps, afkDelay, longAfkFps, longAfkDelay)
        performance.add(disableTntFlash, hideLightningBolts, hideHoppers, hideDistantEntities, entityRenderDistance)
        performance.add(hudFrameLimit, textRenderDistance)
        performance.add(disableTntExpansion, optimizeSpawners, disableEnchantTableBooks)
        config.add(performance)

        hidden.add(creditsBGM, someNiceBackground, noAltIcons)

        AxoKeybinding.create(AxoKeys.KEY_UNKNOWN, "toggle_hide_chat").`br$registerOnConsumeClick`(Runnable {
            hideChat.toggle()
            AxolotlClientCommon.getInstance().saveConfig()
        })
        peekChatKey = AxoKeybinding.create(AxoKeys.KEY_UNKNOWN, "peek_chat")
        clearChatHistoryKey = AxoKeybinding.create(AxoKeys.KEY_UNKNOWN, "clear_chat_history")
        AxoKeybinding.create(AxoKeys.KEY_UNKNOWN, "toggle_fullbright").`br$registerOnConsumeClick`(Runnable {
            fullBright.toggle()
            AxolotlClientCommon.getInstance().saveConfig()
        })
        AxoKeybinding.create(AxoKeys.KEY_UNKNOWN, "toggle_chunk_borders").`br$registerOnConsumeClick`(Runnable {
            chunkBorders.toggle()
            AxolotlClientCommon.getInstance().saveConfig()
        })
        AxoKeybinding.create(AxoKeys.KEY_F7, "toggle_light_overlay").`br$registerOnConsumeClick`(Runnable {
            lightOverlay.toggle()
            AxolotlClientCommon.getInstance().saveConfig()
        })

        Events.TICK.register(Runnable {
            if (hitColor.getOriginal().isChroma) {
                updateHitColor(hitColor.get())
            }
        })
    }

    fun getDateTimeFormatter(): DateTimeFormatter {
        if (dateTimeFormatter == null) {
            dateTimeFormatter = DateTimeFormatter.ofPattern(datetimeFormat.get())
        }

        return dateTimeFormatter!!
    }

    fun add(option: Option<*>) {
        config.add(option)
    }

    fun addCategory(cat: OptionCategory) {
        config.add(cat)
    }

    fun getConfig(): OptionCategory = config

    protected abstract fun updateWindowTitle(useCustom: Boolean)

    protected abstract fun updateHitColor(color: Color)

    /**
     * Fired whenever a farming render-hide toggle changes; the version-side override
     * re-meshes the world so the (statically baked) chunk geometry picks the change up.
     */
    protected open fun onFarmingRenderHidesChanged() {
    }

    companion object {
        @JvmStatic
        fun instance(): AxolotlClientConfigCommon = AxolotlClientCommon.getInstance().getConfig()
    }
}
