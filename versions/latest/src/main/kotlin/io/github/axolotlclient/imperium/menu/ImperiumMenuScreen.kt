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

package io.github.axolotlclient.imperium.menu

import kotlin.math.max
import kotlin.math.min
import java.util.Locale

import com.mojang.blaze3d.platform.InputConstants
import io.github.axolotlclient.AxolotlClient
import io.github.axolotlclient.AxolotlClientCommon
import io.github.axolotlclient.AxolotlClientConfig.api.options.Option
import io.github.axolotlclient.AxolotlClientConfig.api.options.OptionCategory
import io.github.axolotlclient.AxolotlClientConfig.impl.options.BooleanOption
import io.github.axolotlclient.AxolotlClientConfig.impl.util.ConfigStyles
import io.github.axolotlclient.AxolotlClientConfigCommon
import io.github.axolotlclient.bridge.render.AxoRenderContext
import io.github.axolotlclient.bridge.util.AxoI18n
import io.github.axolotlclient.config.screen.ProfilesScreen
import io.github.axolotlclient.imperium.ServerGating
import io.github.axolotlclient.imperium.hud.ImperiumHudEditScreen
import io.github.axolotlclient.imperium.theme.ImperiumFonts
import io.github.axolotlclient.imperium.theme.ImperiumTheme as Theme
import io.github.axolotlclient.modules.hud.HudEditScreen
import io.github.axolotlclient.modules.hud.gui.component.HudEntry
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import org.jetbrains.annotations.VisibleForTesting

/**
 * The Imperium module menu, UI v3 (docs/design/README.md): a full-screen shell with a 270px
 * sidebar (135 GUI px), category segments, search and an enabled-count badge, and a 3-column
 * grid of module cards in four states (default / hover / enabled / blocked-by-server). Clicking
 * a card toggles the module; its gear opens the module's settings INLINE — v3 setting rows
 * (44 CSS px / 22 GUI px, section headers, half-width boolean pairs, a reset icon per row)
 * built from the same ConfigStyles widgets Axolotl's config screens use.
 *
 * All v3 sizes are the spec's CSS measurements halved (the mockups are 1920x1080 CSS px =
 * GUI px at 1080p / GUI scale 2), so item icons land on their native 16px sprite.
 *
 * The older CARVED (chiseled stone, [CarvedTheme]) and CLASSIC (flat dark-and-gold) themes
 * keep their original centered-panel rendering as fallbacks.
 */
class ImperiumMenuScreen(private val parent: Screen?) :
	Screen(Component.translatable("imperium.menu.title")) {
	// CLASSIC palette
	private val bg = 0xE6100D10.toInt()
	private val sidebarColor = 0xF0181316.toInt()
	private val card = 0xFF211A1F.toInt()
	private val cardHover = 0xFF2B2228.toInt()
	private val gold = 0xFFD4AF37.toInt()
	private val crimson = 0xFF8E1F2F.toInt()
	private val textColor = 0xFFEDE6DA.toInt()
	private val muted = 0xFF9A8F86.toInt()
	private val toggleOff = 0xFF4A4045.toInt()
	private val settingsBg = 0xF0141013.toInt()

	// CARVED palette (see CarvedTheme for the full ramp)
	private val sidebarCarved = 0xF013100D.toInt()
	private val cardNameOn = 0xFFE0D1B8.toInt()
	private val cardNameOff = 0xFFC7BEB2.toInt()
	private val settingsTextCarved = 0xFFCFC5B4.toInt()

	private var allEntries: List<MenuEntry> = listOf()
	private var visible: List<MenuEntry> = listOf()
	private var category: MenuCategory = lastCategory
	private var query = ""
	private var scroll = 0.0
	private lateinit var search: EditBox
	private lateinit var editHudButton: ImperiumButton
	private lateinit var profilesButton: ImperiumButton
	private lateinit var allSettingsButton: ImperiumButton

	// v3 adaptive metrics (recomputed in init: the sidebar compresses on short windows and the
	// segment chips wrap when the content column cannot hold them in one row).
	private var navItemH = V3_NAV_H
	private var navGap = V3_NAV_GAP
	private var navTop = 0
	private var footerCompact = false
	private var navScroll = 0.0
	private var segmentBands = 1

	/** x/y of each segment chip, indexed by [MenuCategory] ordinal. */
	private var segmentPos = arrayOfNulls<IntArray>(0)

	// Cached per-frame text: category chip labels/widths and the header strings are
	// translation lookups + allocations that used to run every frame (once per chip for
	// the labels). Rebuilt on layout (init/resize) and when the settings panel toggles,
	// which are the only things that change them.
	private var segmentLabelsCache: List<String> = emptyList()
	private var segmentWidthCache = IntArray(0)
	private var headerCacheW = -1
	private var headerTitleTrunc = ""
	private var headerDescriptionTrunc = ""

	// The enabled-count badge: the count is cheap to recompute, the translatable format
	// is not, so only the string rebuilds when the count actually changes.
	private var enabledBadgeCount = -1
	private var enabledBadgeText = ""

	// v3 motion state: the sliding nav pill, per-card hover fades and per-control press fades.
	private var pillFromY = 0f
	private var pillY = 0f
	private var pillStartMs = 0L
	private var pillInit = false
	private val hoverT = HashMap<String, Float>()
	private val pressT = HashMap<String, Float>()

	/** The hand-drawn control currently held by the mouse (its press fade runs to 1). */
	private var pressedKey: String? = null
	private var lastFrameMs = 0L

	// The inline settings panel. While open, the card grid is not drawn and the search box
	// is parked (removed from the widget tree) so nothing steals input or peeks out from
	// under the panel; closing restores it and saves the config.
	private var settingsEntry: MenuEntry? = null

	// Callback hook for the HUD editor's context menu: the element whose settings the editor
	// asked to open, consumed by init() right after the entries are built.
	private var pendingHudEntry: HudEntry? = null
	private val settingsRows = ArrayList<SettingsRow>()
	private var settingsScroll = 0.0

	private var panelX = 0
	private var panelY = 0
	private var panelW = 0
	private var panelH = 0

	override fun init() {
		allEntries = MenuEntries.build()

		panelW = min(width - 24, 560)
		panelH = min(height - 24, 340)
		panelX = (width - panelW) / 2
		panelY = (height - panelH) / 2

		if (v3()) {
			layoutV3Sidebar()
			layoutV3Segments()
			search = EditBox(
				font, searchX() + V3_SEARCH_ICON_W + 4, searchY() + 4,
				V3_SEARCH_W - V3_SEARCH_ICON_W - 12, V3_SEARCH_H - 8,
				Component.translatable("imperium.menu.search")
			)
			search.setHint(Component.translatable("imperium.menu.search"))
			search.setBordered(false)
			search.setTextColor(Theme.TEXT)
			search.setValue(query)
			search.setResponder { value: String ->
				query = value
				scroll = 0.0
				refilter()
			}
			if (settingsEntry == null) {
				addRenderableWidget(search)
				// Typing should search straight away.
				setInitialFocus(search)
			} else {
				// Window resized while the settings panel is open: rebuild its widgets at the
				// new layout but keep the panel (and the scroll position) open.
				buildSettingsRows(settingsEntry!!.settings())
				addSettingsWidgets()
				clampSettingsScroll()
				layoutSettingsWidgets()
			}
			refilter()
			openPendingHudEntrySettings()
			return
		}

		search = EditBox(font, panelX + panelW - 138, panelY + 7, 130, 16, Component.translatable("imperium.menu.search"))
		search.setHint(Component.translatable("imperium.menu.search"))
		search.setValue(query)
		search.setResponder { value: String ->
			query = value
			scroll = 0.0
			refilter()
		}
		val buttonY = panelY + panelH - FOOTER_H + 4
		val buttonX = panelX + SIDEBAR_W + GAP
		editHudButton = ImperiumButton(
			buttonX, buttonY, 80, 20, Component.translatable("imperium.menu.edit_hud")
		) { minecraft!!.gui.setScreen(ImperiumHudEditScreen(this)) }
		profilesButton = ImperiumButton(
			buttonX + 84, buttonY, 80, 20, Component.translatable("imperium.menu.profiles")
		) { minecraft!!.gui.setScreen(ProfilesScreen(this)) }
		allSettingsButton = ImperiumButton(
			buttonX + 168, buttonY, 90, 20, Component.translatable("imperium.menu.all_settings")
		) { minecraft!!.gui.setScreen(ConfigStyles.createScreen(this, AxolotlClientCommon.getInstance().configManager.root)) }

		if (settingsEntry == null) {
			addRenderableWidget(search)
			setInitialFocus(search)
			addRenderableWidget(editHudButton)
			addRenderableWidget(profilesButton)
			addRenderableWidget(allSettingsButton)
		} else {
			buildSettingsRows(settingsEntry!!.settings())
			addSettingsWidgets()
			clampSettingsScroll()
			layoutSettingsWidgets()
		}

		refilter()
		openPendingHudEntrySettings()
	}

	/**
	 * Consumes a settings request from the HUD editor's context menu (see
	 * [openWithHudSettings]): finds the element's card and opens its inline settings panel.
	 */
	private fun openPendingHudEntrySettings() {
		val hud = pendingHudEntry ?: return
		pendingHudEntry = null
		val index = visible.indexOfFirst { it.id() == "hud/" + hud.getId().toString() }
		if (index >= 0) {
			openSettings(index)
		}
	}

	private fun refilter() {
		visible = allEntries
			.filter { e: MenuEntry -> category == MenuCategory.ALL || e.category() == category }
			.filter { e: MenuEntry -> e.matches(query) }
			.sortedWith(compareByDescending<MenuEntry> { pinnedIds.contains(it.id()) }.thenBy { it.name().lowercase(Locale.ROOT) })
		scroll = Mth.clamp(scroll, 0.0, maxScroll())
	}

	private fun v3(): Boolean =
		AxolotlClient.config().menuTheme.get() == AxolotlClientConfigCommon.MenuTheme.IMPERIUM

	private fun reduceMotion(): Boolean = AxolotlClient.config().menuReduceMotion.get()

	// ── v3 geometry ──────────────────────────────────────────────────────────────────────────

	/** Chooses the sidebar's density: full spec sizes, or compressed when the window is short. */
	private fun layoutV3Sidebar() {
		navTop = V3_PAD + V3_HEADER_H + V3_NAV_TOP
		val navCount = MenuCategory.entries.size
		footerCompact = height < 460
		val footerBlock = if (footerCompact) V3_PILL_COMPACT else 3 * V3_PILL_H + 2 * V3_PILL_GAP
		val available = height - navTop - footerBlock - V3_PILL_TOP - V3_PAD
		var itemH = V3_NAV_H
		var gap = V3_NAV_GAP
		if (navCount * itemH + (navCount - 1) * gap > available) {
			gap = 2
			itemH = Mth.clamp((available - (navCount - 1) * gap) / navCount, 16, V3_NAV_H)
		}
		navItemH = itemH
		navGap = gap
		navScroll = 0.0
	}

	/** Packs the category chips into centred rows that fit the content column. */
	private fun layoutV3Segments() {
		val labels = segmentLabels()
		// Cache the RAW label widths (what segmentWidth callers add the chip padding to);
		// the row packing below works on the padded widths.
		segmentLabelsCache = labels
		segmentWidthCache = IntArray(labels.size) { ImperiumFonts.width(font, labels[it], ImperiumFonts.BARLOW_SEMIBOLD) }
		val widths = segmentWidthCache.map { it + V3_SEG_PAD * 2 }
		val maxRowW = contentW()
		val pos = arrayOfNulls<IntArray>(labels.size)
		var row = 0
		var rowStart = 0
		var i = 1
		while (i <= labels.size) {
			val rowW = (rowStart until i).sumOf { widths[it] } + (i - rowStart - 1) * V3_SEG_GAP
			if (i == labels.size || rowW + widths[i] + V3_SEG_GAP > maxRowW) {
				// centre this row [rowStart, i)
				val used = (rowStart until i).sumOf { widths[it] } + (i - rowStart - 1) * V3_SEG_GAP
				var x = V3_SIDEBAR_W + (maxRowW - used) / 2
				for (j in rowStart until i) {
					pos[j] = intArrayOf(x, bandY() + row * (V3_SEG_H + V3_SEG_GAP))
					x += widths[j] + V3_SEG_GAP
				}
				row++
				rowStart = i
			}
			i++
		}
		segmentPos = pos
		segmentBands = row
	}

	private fun segmentLabels(): List<String> =
		MenuCategory.entries.map { c: MenuCategory -> Component.translatable(c.translationKey()).string }

	/**
	 * A segment chip's label width in the UI font (the chips render in Barlow SemiBold).
	 * Reads the layout-time cache — building it per call re-translated every label. The
	 * lazy fill only covers a theme flip landing without an init pass; normally
	 * layoutV3Segments ran in init.
	 */
	private fun segmentWidth(index: Int): Int {
		ensureSegmentCache()
		return segmentWidthCache[index]
	}

	private fun ensureSegmentCache() {
		if (segmentLabelsCache.isEmpty()) {
			layoutV3Segments()
		}
	}

	private fun searchX(): Int = width - V3_CONTENT_PAD - V3_SEARCH_W

	private fun searchY(): Int = V3_HEADER_Y + 2

	/** First chip row's y; the settings back pill shares this line. */
	private fun bandY(): Int = V3_CONTENT_Y + V3_CONTENT_PAD_TOP

	private fun contentX(): Int = if (v3()) V3_SIDEBAR_W + V3_CONTENT_PAD else panelX + SIDEBAR_W + GAP

	private fun contentY(): Int = if (v3()) V3_CONTENT_Y else panelY + HEADER_H + GAP

	private fun contentW(): Int =
		if (v3()) width - V3_SIDEBAR_W - V3_CONTENT_PAD * 2 else panelW - SIDEBAR_W - GAP * 2

	/** The cards/rows list starts below the segment chips (or the back pill line). */
	private fun listTopInset(): Int = V3_CONTENT_PAD_TOP + segmentBands * (V3_SEG_H + V3_SEG_GAP) + 6

	private fun contentH(): Int =
		if (v3()) height - contentY() - listTopInset() - V3_LIST_BOTTOM_PAD
		else panelH - HEADER_H - FOOTER_H - GAP

	private fun columns(): Int {
		if (!v3()) {
			return max(2, (contentW() + GAP) / (120 + GAP))
		}
		return if (contentW() >= 3 * V3_MIN_CARD_W + 2 * V3_CARD_GAP) 3 else 2
	}

	private fun cardH(): Int = if (v3()) V3_CARD_H else CARD_H

	private fun cardGap(): Int = if (v3()) V3_CARD_GAP else GAP

	private fun cardW(): Int = (contentW() - cardGap() * (columns() - 1)) / columns()

	private fun cardX(index: Int): Int = contentX() + (index % columns()) * (cardW() + cardGap())

	private fun cardY(index: Int): Int = contentY() + listTopInset() + (index / columns()) * (cardH() + cardGap()) - scroll.toInt()

	private fun maxScroll(): Double {
		val rows = (visible.size + columns() - 1) / columns()
		return max(0.0, rows * (cardH() + cardGap()) - cardGap() - contentH().toDouble())
	}

	private fun inContent(x: Double, y: Double): Boolean =
		x >= contentX() && x < contentX() + contentW() && y >= contentY() && y < contentY() + contentH()

	// v3 card internals (relative to the card's top-left).

	private fun wellX(x: Int): Int = x + 8

	private fun wellY(y: Int): Int = y + (cardH() - V3_WELL) / 2

	private fun cardNameRight(x: Int): Int = x + cardW() - 8

	private fun cardTagX(x: Int): Int = x + 8 + V3_WELL + 5

	/** X of an action glyph's left edge, left to right: power, pin, settings. */
	private fun actionX(x: Int, which: Int): Int = x + cardW() - 8 - (2 - which) * 15 - 10

	private fun actionY(y: Int): Int = y + cardH() - 12

	// v3 nav geometry.

	private fun navIndex(target: MenuCategory): Int {
		var index = 0
		for (c in MenuCategory.entries) {
			if (c == target) break
			index++
		}
		return index
	}

	private fun navItemY(index: Int): Int = navTop + index * (navItemH + navGap)

	private fun navClipBottom(): Int = if (footerCompact) height - V3_PAD - V3_PILL_COMPACT - V3_PILL_TOP
	else height - V3_PAD - (3 * V3_PILL_H + 2 * V3_PILL_GAP) - V3_PILL_TOP

	private fun maxNavScroll(): Double {
		val navBottom = navTop + MenuCategory.entries.size * navItemH + (MenuCategory.entries.size - 1) * navGap
		return max(0.0, (navBottom - navClipBottom()).toDouble())
	}

	@VisibleForTesting
	fun visibleEntries(): List<MenuEntry> = visible

	/** Centre of the card body (away from the actions), in GUI coordinates. */
	@VisibleForTesting
	fun cardBodyCenter(index: Int): IntArray = intArrayOf(cardX(index) + cardW() / 3, cardY(index) + cardH() / 2)

	/** Centre of the card's settings gear, in GUI coordinates. */
	@VisibleForTesting
	fun cardGearCenter(index: Int): IntArray =
		intArrayOf(actionX(cardX(index), 2) + 5, actionY(cardY(index)) + 5)

	/** Centre of the card's power glyph, in GUI coordinates. */
	@VisibleForTesting
	fun cardPowerCenter(index: Int): IntArray =
		intArrayOf(actionX(cardX(index), 0) + 5, actionY(cardY(index)) + 5)

	/** Centre of the card's pin glyph, in GUI coordinates. */
	@VisibleForTesting
	fun cardPinCenter(index: Int): IntArray =
		intArrayOf(actionX(cardX(index), 1) + 4, actionY(cardY(index)) + 3)

	/** True when the card renders in the blocked-by-server state ([ServerGating] denies it). */
	@VisibleForTesting
	fun isCardBlocked(index: Int): Boolean =
		index >= 0 && index < visible.size && isBlocked(visible[index])

	/** Centre of a sidebar category row, in GUI coordinates. */
	@VisibleForTesting
	fun categoryCenter(target: MenuCategory): IntArray {
		if (!v3()) {
			val index = navIndex(target)
			val y = panelY + HEADER_H + 6 + index * ROW_H
			return intArrayOf(panelX + SIDEBAR_W / 2, y + ROW_H / 2)
		}
		val y = navItemY(navIndex(target)) - navScroll.toInt()
		return intArrayOf(V3_SIDEBAR_W / 2, y + navItemH / 2)
	}

	/** Centre of a category segment chip, in GUI coordinates (v3 only). */
	@VisibleForTesting
	fun segmentCenter(target: MenuCategory): IntArray {
		val pos = segmentPos[target.ordinal] ?: intArrayOf(contentX(), bandY())
		return intArrayOf(pos[0] + V3_SEG_PAD, pos[1] + V3_SEG_H / 2)
	}

	// gating

	private fun isBlocked(entry: MenuEntry): Boolean {
		val gateId = GATE_IDS[entry.id()] ?: return false
		return ServerGating.tier(gateId) == ServerGating.Tier.LOCKED
	}

	private fun enabledCount(): Int = allEntries.count { it.enabled() }

	// inline settings panel

	/** Top of the settings panel's scrollable row band. */
	private fun settingsScrollTop(): Int = contentY() + listTopInset()

	private fun settingsScrollBottom(): Int = contentY() + contentH()

	private fun settingsWidgetX(): Int = contentX() + contentW() - SETTINGS_WIDGET_W - 10

	private fun settingsContentHeight(): Int {
		var y = 0
		for (row in settingsRows) {
			y += rowAdvance(row)
		}
		return y
	}

	/** Vertical space a row occupies: paired rows share a band with their partner. */
	private fun rowAdvance(row: SettingsRow): Int = when {
		row.header != null -> settingsHeaderH()
		row.paired -> 0
		else -> SETTINGS_ROW_H
	}

	private fun settingsHeaderH(): Int = if (v3()) V3_SETTINGS_HEADER_H else SETTINGS_HEADER_H

	private fun maxSettingsScroll(): Double =
		max(0.0, (settingsContentHeight() - (settingsScrollBottom() - settingsScrollTop())).toDouble())

	private fun clampSettingsScroll() {
		settingsScroll = Mth.clamp(settingsScroll, 0.0, maxSettingsScroll())
	}

	private fun settingsBackX(): Int = contentX() + contentW() - SETTINGS_BACK_W - 6

	private fun settingsBackY(): Int = contentY() + 3

	private fun overSettingsBack(x: Double, y: Double): Boolean =
		x >= settingsBackX() && x < settingsBackX() + SETTINGS_BACK_W && y >= settingsBackY() && y < settingsBackY() + SETTINGS_BACK_H

	/** Left x and width of the row's pill: full width, or one half of a boolean pair. */
	private fun rowRect(row: SettingsRow): IntArray =
		if (row.pairRight) intArrayOf(contentX() + halfRowW() + V3_PAIR_GAP, halfRowW())
		else intArrayOf(contentX(), if (row.paired) halfRowW() else contentW())

	private fun halfRowW(): Int = (contentW() - V3_PAIR_GAP) / 2

	/**
	 * Opens the given visible card's settings inline, over the card grid. The library widgets set
	 * the options as soon as they are used; the config is saved when the panel closes.
	 */
	@VisibleForTesting
	fun openSettings(visibleIndex: Int) {
		if (visibleIndex < 0 || visibleIndex >= visible.size) return
		val entry = visible[visibleIndex]
		if (settingsEntry != null) {
			stripSettingsWidgets()
		} else {
			// Park the search box: while the panel is open nothing else may take input or
			// render outside the scroll band's scissor.
			removeWidget(search)
		}
		// A previously focused option widget is about to be discarded.
		clearFocus()
		settingsEntry = entry
		headerCacheW = -1 // the header switches to the module name; rebuild the cache
		settingsScroll = 0.0
		buildSettingsRows(entry.settings())
		addSettingsWidgets()
		layoutSettingsWidgets()
	}

	/** Closes the inline settings panel, restores the grid widgets and saves the config. */
	@VisibleForTesting
	fun closeSettingsPanel() {
		if (settingsEntry == null) return
		stripSettingsWidgets()
		settingsEntry = null
		headerCacheW = -1 // the header switches back to the menu title; rebuild the cache
		settingsScroll = 0.0
		addRenderableWidget(search)
		setInitialFocus(search)
		AxolotlClientCommon.getInstance().saveConfig()
	}

	@VisibleForTesting
	fun isSettingsPanelOpen(): Boolean = settingsEntry != null

	/** Number of option rows (one per option, sub-category headers excluded). */
	@VisibleForTesting
	fun settingsRowCount(): Int = settingsRows.count { row: SettingsRow -> row.option != null }

	/** Number of option widgets that are live children of this screen. */
	@VisibleForTesting
	fun settingsWidgetCount(): Int = settingsRows.count { row: SettingsRow ->
		val widget = row.widget
		widget != null && children().contains(widget)
	}

	/** Centre of the panel's back button, in GUI coordinates (legacy themes). */
	@VisibleForTesting
	fun settingsBackCenter(): IntArray = intArrayOf(settingsBackX() + SETTINGS_BACK_W / 2, settingsBackY() + SETTINGS_BACK_H / 2)

	/** How far the settings rows are currently scrolled down, in pixels. */
	@VisibleForTesting
	fun settingsScrollAmount(): Double = settingsScroll

	/** A point over the settings row band (safe scroll target), in GUI coordinates. */
	@VisibleForTesting
	fun settingsScrollCenter(): IntArray = intArrayOf(contentX() + contentW() / 2, settingsScrollTop() + 24)

	/** Sets the search box contents as if typed (test hook; refilters the grid). */
	@VisibleForTesting
	fun setSearchQuery(value: String) {
		search.setValue(value)
	}

	private fun buildSettingsRows(category: OptionCategory) {
		settingsRows.clear()
		appendSettingsRows(category, 0)
		if (v3()) {
			// Mark half-width pairs: two consecutive boolean options share one 44px band.
			var i = 0
			while (i < settingsRows.size) {
				val row = settingsRows[i]
				if (row.option != null && row.option is BooleanOption && i + 1 < settingsRows.size &&
					settingsRows[i + 1].header == null && settingsRows[i + 1].option is BooleanOption
				) {
					row.paired = true
					settingsRows[i + 1].pairRight = true
					i += 2
				} else {
					i++
				}
			}
		}
	}

	private fun appendSettingsRows(category: OptionCategory, indent: Int) {
		for (option in category.options) {
			val width = if (v3() && option is BooleanOption) V3_CHECK_W else SETTINGS_WIDGET_W
			settingsRows.add(SettingsRow(null, option, ConfigStyles.createWidget(0, 0, width, SETTINGS_WIDGET_H, option), indent))
		}
		for (sub in category.subCategories) {
			settingsRows.add(SettingsRow(AxoI18n.translate(sub.name), null, null, indent))
			appendSettingsRows(sub, indent + 1)
		}
	}

	private fun addSettingsWidgets() {
		for (row in settingsRows) {
			row.widget?.let { addRenderableWidget(it) }
		}
	}

	private fun stripSettingsWidgets() {
		for (row in settingsRows) {
			row.widget?.let { removeWidget(it) }
		}
		settingsRows.clear()
	}

	/** Places every option widget for the current scroll; rows outside the band are hidden. */
	private fun layoutSettingsWidgets() {
		val top = settingsScrollTop()
		val bottom = settingsScrollBottom()
		var y = 0
		for (row in settingsRows) {
			val widget = row.widget
			if (widget != null) {
				val wy = top + y - settingsScroll.toInt()
				val rightEdge = if (v3()) rowRect(row)[0] + rowRect(row)[1] - V3_RESET_W - 7 else settingsWidgetX() + SETTINGS_WIDGET_W
				widget.setX(rightEdge - widget.width)
				widget.setY(wy + (SETTINGS_ROW_H - SETTINGS_WIDGET_H) / 2)
				widget.visible = wy + SETTINGS_ROW_H > top && wy < bottom
			}
			y += rowAdvance(row)
		}
	}

	/** Resets one option to its default and swaps in a fresh widget that shows the new value. */
	private fun resetOption(row: SettingsRow) {
		val option = row.option ?: return
		option.setDefault()
		val widget = row.widget ?: return
		removeWidget(widget)
		val width = if (v3() && option is BooleanOption) V3_CHECK_W else SETTINGS_WIDGET_W
		row.widget = ConfigStyles.createWidget(0, 0, width, SETTINGS_WIDGET_H, option)
		row.widget?.let { addRenderableWidget(it) }
		layoutSettingsWidgets()
	}

	// rendering

	override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
		if (v3()) {
			extractBackgroundV3(graphics)
			return
		}
		super.extractBackground(graphics, mouseX, mouseY, delta)
		if (carved()) {
			CarvedTheme.carvedPanel(graphics, panelX, panelY, panelW, panelH)
			// sidebar rail
			graphics.fill(panelX + 1, panelY + HEADER_H, panelX + SIDEBAR_W - 1, panelY + panelH - 1, sidebarCarved)
			graphics.fill(panelX + SIDEBAR_W - 1, panelY + HEADER_H, panelX + SIDEBAR_W, panelY + panelH - 1, CarvedTheme.BRONZE_DARK)
			CarvedTheme.carvedRule(graphics, panelX, panelY + HEADER_H - 2, panelW)
			CarvedTheme.carvedRule(graphics, panelX, panelY + panelH - FOOTER_H, panelW)
			if (settingsEntry != null) {
				CarvedTheme.carvedPanel(graphics, contentX(), contentY(), contentW(), contentH())
			}
			return
		}
		val ctx = graphics as AxoRenderContext
		ctx.`br$fillRectRound`(panelX, panelY, panelW, panelH, bg, 6f)
		ctx.`br$fillRectRoundVarying`(panelX, panelY + HEADER_H, SIDEBAR_W, panelH - HEADER_H, sidebarColor, 0f, 6f, 0f, 0f)
		graphics.fill(panelX, panelY + HEADER_H - 1, panelX + panelW, panelY + HEADER_H, crimson)
		if (settingsEntry != null) {
			ctx.`br$fillRectRound`(contentX(), contentY(), contentW(), contentH(), settingsBg, 4f)
		}
	}

	/**
	 * The v3 shell's background stratum: everything that must sit UNDER the option widgets
	 * (card bodies, row pills, the search pill).
	 *
	 * In-world the vanilla background layers are replaced by the spec's own recipe: the soft
	 * world blur ([extractBlurredBackground] places the frame's blur boundary here, running the
	 * vanilla menu-blur post pass over everything drawn before this screen) under the spec's
	 * surface dim - no vanilla menu texture, so the blurred world reads through exactly as the
	 * CSS mockup's `.dim` intends. The blur honours the vanilla "Menu Background Blurriness"
	 * option (at 0 the dim falls back to plain translucency) and, because the boundary only
	 * exists while this screen draws it, it costs nothing when no screen is open. The
	 * HUD-element blur (HudBlur) is a separate, earlier pass and composes under this untouched.
	 * Over the title screen (no world) the vanilla panorama and its blur stay, via super.
	 */
	private fun extractBackgroundV3(graphics: GuiGraphicsExtractor) {
		if (minecraft == null || minecraft!!.level == null) {
			super.extractBackground(graphics, 0, 0, 0f)
		} else {
			extractBlurredBackground(graphics)
		}
		graphics.fill(0, 0, width, height, Theme.SURFACE)

		// sidebar layer and its 1px component border
		graphics.fill(0, 0, V3_SIDEBAR_W, height, Theme.LAYER)
		graphics.fill(V3_SIDEBAR_W, 0, V3_SIDEBAR_W + 1, height, Theme.COMPONENT)

		// search pill under the (borderless) EditBox
		Theme.roundRect(graphics, searchX(), searchY(), V3_SEARCH_W, V3_SEARCH_H, Theme.COMPONENT_BG, V3_SEARCH_H / 2f)
		Theme.roundOutline(graphics, searchX(), searchY(), V3_SEARCH_W, V3_SEARCH_H, Theme.COMPONENT, V3_SEARCH_H / 2f)

		if (settingsEntry != null) {
			extractSettingsRowsBackgroundV3(graphics)
		} else {
			extractCardsBackgroundV3(graphics)
		}
	}

	/** Card bodies (state colours, hover fade, scale) in the background stratum. */
	private fun extractCardsBackgroundV3(graphics: GuiGraphicsExtractor) {
		if (visible.isEmpty()) return
		graphics.enableScissor(contentX(), contentY(), contentX() + contentW(), contentY() + contentH())
		for (i in visible.indices) {
			val entry = visible[i]
			val x = cardX(i)
			val y = cardY(i)
			if (y + cardH() < contentY() || y > contentY() + contentH()) continue
			val t = hoverFade(entry.id())
			val scale = controlScale(min(1f, t * 2), pressFade("card:${entry.id()}"))
			val pose = graphics.pose()
			pose.pushMatrix()
			pose.translate((x + cardW() / 2).toFloat(), (y + cardH() / 2).toFloat())
			pose.scale(scale, scale)
			pose.translate(-(x + cardW() / 2).toFloat(), -(y + cardH() / 2).toFloat())
			val blocked = isBlocked(entry)
			val on = entry.enabled() && !blocked
			val body: Int
			val border: Int
			when {
				blocked -> {
					body = Theme.CARD_BLOCKED_BG
					border = Theme.CARD_BLOCKED_BORDER
				}
				on -> {
					body = lerpColor(Theme.CARD_ON_BG, Theme.CARD_HOVER_BG, t * 0.5f)
					border = lerpColor(Theme.CARD_ON_BORDER, Theme.ACCENT_SOFT, t)
				}
				else -> {
					body = lerpColor(Theme.CARD_BG, Theme.CARD_HOVER_BG, t)
					border = lerpColor(Theme.CARD_BORDER, Theme.ACCENT_SOFT, t)
				}
			}
			Theme.roundRect(graphics, x, y, cardW(), cardH(), body, V3_CARD_R)
			Theme.roundOutline(graphics, x, y, cardW(), cardH(), border, V3_CARD_R)
			val well = when {
				blocked -> Theme.ICON_WELL_BLOCKED
				on -> Theme.ICON_WELL_ON
				else -> Theme.ICON_WELL
			}
			Theme.roundRect(graphics, wellX(x), wellY(y), V3_WELL, V3_WELL, well, V3_WELL_R)
			pose.popMatrix()
		}
		graphics.disableScissor()
	}

	/** v3 setting-row chrome under the widgets: row pills, search-hit halos. */
	private fun extractSettingsRowsBackgroundV3(graphics: GuiGraphicsExtractor) {
		val top = settingsScrollTop()
		val bottom = settingsScrollBottom()
		graphics.enableScissor(contentX(), top, contentX() + contentW(), bottom)
		var y = top - settingsScroll.toInt()
		for (row in settingsRows) {
			if (row.header != null) {
				y += settingsHeaderH()
				continue
			}
			val rect = rowRect(row)
			if (isRowSearchHit(row)) {
				// search-hit halo: a secondary ring just outside the row
				Theme.roundRect(graphics, rect[0] - 1, y - 1, rect[1] + 2, SETTINGS_ROW_H + 2, Theme.ROW_FOUND_HALO, V3_ROW_R + 1f)
			}
			Theme.roundRect(graphics, rect[0], y, rect[1], SETTINGS_ROW_H, Theme.ROW_BG, V3_ROW_R)
			Theme.roundOutline(
				graphics, rect[0], y, rect[1], SETTINGS_ROW_H,
				if (isRowSearchHit(row)) Theme.ACCENT_HALF else Theme.CARD_BORDER, V3_ROW_R
			)
			y += rowAdvance(row)
		}
		graphics.disableScissor()
	}

	override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
		if (v3()) {
			extractRenderStateV3(graphics, mouseX, mouseY, delta)
			return
		}
		if (settingsEntry != null) {
			// The option widgets are real children, so they render (and hover) inside this pass:
			// scissor them to the row band so no row bleeds over the panel's title or footer.
			graphics.enableScissor(contentX(), settingsScrollTop(), contentX() + contentW(), settingsScrollBottom())
			super.extractRenderState(graphics, mouseX, mouseY, delta)
			graphics.disableScissor()
			if (carved()) {
				CarvedTheme.engravedCaps(
					graphics, font, Component.translatable("imperium.menu.title").string,
					panelX + 10, panelY + 11, CarvedTheme.BRONZE_LIGHT
				)
			} else {
				graphics.text(font, Component.translatable("imperium.menu.title"), panelX + 10, panelY + 11, gold, false)
			}
			extractSidebar(graphics, mouseX, mouseY)
			extractSettingsPanel(graphics, mouseX, mouseY)
			return
		}
		super.extractRenderState(graphics, mouseX, mouseY, delta)
		if (carved()) {
			CarvedTheme.engravedCaps(
				graphics, font, Component.translatable("imperium.menu.title").string,
				panelX + 10, panelY + 11, CarvedTheme.BRONZE_LIGHT
			)
		} else {
			graphics.text(font, Component.translatable("imperium.menu.title"), panelX + 10, panelY + 11, gold, false)
		}
		extractSidebar(graphics, mouseX, mouseY)
		extractCards(graphics, mouseX, mouseY)
	}

	private fun extractSidebar(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		var y = panelY + HEADER_H + 6
		for (c in MenuCategory.entries) {
			val selected = c == category
			val hovered = mouseX >= panelX && mouseX < panelX + SIDEBAR_W && mouseY >= y && mouseY < y + ROW_H
			if (carved()) {
				if (selected) {
					// the row is pressed into the rail: shadow top/left, catch-light bottom/right
					graphics.fill(panelX + 1, y, panelX + SIDEBAR_W - 1, y + ROW_H, 0x33A8823F)
					graphics.fill(panelX + 1, y, panelX + SIDEBAR_W - 1, y + 1, 0x660B0908)
					graphics.fill(panelX + 1, y, panelX + 2, y + ROW_H, 0x660B0908)
					graphics.fill(panelX + 1, y + ROW_H - 1, panelX + SIDEBAR_W - 1, y + ROW_H, CarvedTheme.BRONZE_DARK)
					// carved marker: a bronze wedge anchored to the rail's edge
					graphics.fill(panelX + 2, y + 2, panelX + 5, y + ROW_H - 2, CarvedTheme.BRONZE)
					graphics.fill(panelX + 2, y + 2, panelX + 5, y + 3, CarvedTheme.BRONZE_LIGHT)
				} else if (hovered) {
					graphics.fill(panelX + 1, y, panelX + SIDEBAR_W - 1, y + ROW_H, 0x1ACEA464)
				}
				CarvedTheme.engravedText(
					graphics, font, Component.translatable(c.translationKey()).string.uppercase(Locale.ROOT),
					panelX + 9, y + 5, if (selected) CarvedTheme.BRONZE_LIGHT else settingsTextCarved
				)
			} else {
				if (selected) {
					graphics.fill(panelX, y, panelX + 2, y + ROW_H, gold)
					graphics.fill(panelX + 2, y, panelX + SIDEBAR_W, y + ROW_H, 0x30D4AF37)
				} else if (hovered) {
					graphics.fill(panelX, y, panelX + SIDEBAR_W, y + ROW_H, 0x18FFFFFF)
				}
				graphics.text(font, Component.translatable(c.translationKey()), panelX + 10, y + 5, if (selected) gold else textColor, false)
			}
			y += ROW_H
		}
	}

	private fun extractCards(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		val ctx = graphics as AxoRenderContext
		val carved = carved()
		if (visible.isEmpty()) {
			if (carved) {
				CarvedTheme.engravedText(
					graphics, font, Component.translatable("imperium.menu.no_results").string,
					contentX() + contentW() / 2 - font.width(Component.translatable("imperium.menu.no_results").string) / 2,
					contentY() + contentH() / 2 - 4, CarvedTheme.MUTED
				)
			} else {
				graphics.centeredText(
					font, Component.translatable("imperium.menu.no_results"),
					contentX() + contentW() / 2, contentY() + contentH() / 2 - 4, muted
				)
			}
			return
		}
		graphics.enableScissor(contentX(), contentY(), contentX() + contentW(), contentY() + contentH())
		val w = cardW()
		for (i in visible.indices) {
			val x = cardX(i)
			val y = cardY(i)
			if (y + cardH() < contentY() || y > contentY() + contentH()) continue
			val entry = visible[i]
			val hovered = inContent(mouseX.toDouble(), mouseY.toDouble()) &&
				mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + cardH()
			val enabled = entry.enabled()
			if (carved) {
				CarvedTheme.raisedTile(graphics, x, y, w, cardH(), hovered, enabled)
				drawCarvedCardContent(graphics, entry, x, y, w, hovered, enabled)
			} else {
				ctx.`br$fillRectRound`(x, y, w, cardH(), if (hovered) cardHover else card, 4f)
				if (enabled) {
					ctx.`br$outlineRectRound`(x, y, w, cardH(), gold, 4f)
				}
				graphics.text(font, font.plainSubstrByWidth(entry.name(), w - 12), x + 6, y + 7, textColor, false)
				if (entry.hasToggle()) {
					val tx = x + 6
					val ty = y + cardH() - 15
					ctx.`br$fillRectRound`(tx, ty, 22, 10, if (enabled) gold else toggleOff, 5f)
					ctx.`br$fillRectRound`(if (enabled) tx + 13 else tx + 1, ty + 1, 8, 8, 0xFFFFFFFF.toInt(), 4f)
					graphics.text(
						font, Component.translatable(if (enabled) "options.on" else "options.off"),
						tx + 27, ty + 1, if (enabled) gold else muted, false
					)
				}
			}

			val gx = x + w - GEAR_SIZE - 5
			val gy = y + cardH() - GEAR_SIZE - 4
			val gearHovered = hovered && mouseX >= gx && mouseX < gx + GEAR_SIZE && mouseY >= gy && mouseY < gy + GEAR_SIZE
			graphics.text(
				font, "⚙", gx + 2, gy + 2,
				if (gearHovered) (if (carved) CarvedTheme.BRONZE else gold) else (if (carved) CarvedTheme.MUTED else muted), false
			)
		}
		graphics.disableScissor()

		val max = maxScroll()
		if (max > 0) {
			val trackX = contentX() + contentW() + 2
			val thumbH = max(16, (contentH() * (contentH() / (contentH() + max))).toInt())
			val thumbY = contentY() + ((contentH() - thumbH) * (scroll / max)).toInt()
			if (carved) {
				graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, CarvedTheme.BRONZE_DARK)
				graphics.fill(trackX, thumbY, trackX + 1, thumbY + thumbH, CarvedTheme.BRONZE)
			} else {
				graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0x80D4AF37.toInt())
			}
		}
	}

	/**
	 * Carved card interior: item sprite in a fixed left column, the module name beside it, and
	 * the bronze/marble toggle under the name.
	 */
	private fun drawCarvedCardContent(graphics: GuiGraphicsExtractor, entry: MenuEntry, x: Int, y: Int, w: Int, hovered: Boolean, enabled: Boolean) {
		val iconX = x + 5
		val iconY = y + 5
		CarvedTheme.carvedSlot(graphics, iconX, iconY, ICON_SIZE)
		graphics.fakeItem(CarvedTheme.iconFor(entry), iconX, iconY)

		val textX = iconX + ICON_SIZE + 4
		val nameColor = if (enabled) cardNameOn else cardNameOff
		graphics.text(
			font, font.plainSubstrByWidth(entry.name(), w - (textX - x) - GEAR_SIZE - 4),
			textX, y + 7, if (hovered) CarvedTheme.MARBLE else nameColor, false
		)

		if (entry.hasToggle()) {
			val tx = textX - 1
			val ty = y + cardH() - 15
			CarvedTheme.carvedToggle(graphics, tx, ty, enabled)
			graphics.text(
				font, Component.translatable(if (enabled) "options.on" else "options.off"), tx + 27, ty + 1,
				if (enabled) CarvedTheme.BRONZE else CarvedTheme.MUTED, false
			)
		}
	}

	/**
	 * The legacy inline settings panel: module name and a back button in a title band, then one
	 * row per option (translated label left, themed widget right) with sub-categories as indented
	 * groups under a small engraved header.
	 */
	private fun extractSettingsPanel(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		val ctx = graphics as AxoRenderContext
		val carved = carved()
		val top = settingsScrollTop()
		val bottom = settingsScrollBottom()

		// title band
		if (carved) {
			CarvedTheme.engravedCaps(graphics, font, settingsEntry!!.name(), contentX() + 8, contentY() + 6, CarvedTheme.BRONZE_LIGHT)
			CarvedTheme.carvedRule(graphics, contentX() + 2, contentY() + SETTINGS_TITLE_H - 2, contentW() - 4)
		} else {
			graphics.text(font, settingsEntry!!.name(), contentX() + 8, contentY() + 6, gold, false)
			graphics.fill(contentX() + 4, contentY() + SETTINGS_TITLE_H - 1, contentX() + contentW() - 4, contentY() + SETTINGS_TITLE_H, crimson)
		}

		// back button, drawn by hand so it sits above every scrolled row
		val backHovered = overSettingsBack(mouseX.toDouble(), mouseY.toDouble())
		val bx = settingsBackX()
		val by = settingsBackY()
		val backLabel = "< " + Component.translatable("imperium.menu.settings_back").string
		if (carved) {
			CarvedTheme.raisedTile(graphics, bx, by, SETTINGS_BACK_W, SETTINGS_BACK_H, backHovered, false)
			CarvedTheme.engravedText(graphics, font, backLabel, bx + 6, by + 3, if (backHovered) CarvedTheme.BRONZE_LIGHT else CarvedTheme.MUTED)
		} else {
			ctx.`br$fillRectRound`(bx, by, SETTINGS_BACK_W, SETTINGS_BACK_H, if (backHovered) cardHover else card, 3f)
			graphics.text(font, backLabel, bx + 6, by + 3, if (backHovered) gold else textColor, false)
		}

		// rows (labels and headers; the widgets are children rendering between these draws)
		graphics.enableScissor(contentX(), top, contentX() + contentW(), bottom)
		var y = top - settingsScroll.toInt()
		val widgetX = settingsWidgetX()
		for (row in settingsRows) {
			if (row.header != null) {
				val hx = contentX() + 6 + row.indent * SETTINGS_INDENT
				if (carved) {
					CarvedTheme.engravedText(graphics, font, row.header.uppercase(Locale.ROOT), hx, y + 4, CarvedTheme.BRONZE)
					graphics.fill(hx, y + SETTINGS_HEADER_H - 3, contentX() + contentW() - 10, y + SETTINGS_HEADER_H - 2, 0x40A8823F)
				} else {
					graphics.text(font, row.header.uppercase(Locale.ROOT), hx, y + 4, gold, false)
					graphics.fill(hx, y + SETTINGS_HEADER_H - 3, contentX() + contentW() - 10, y + SETTINGS_HEADER_H - 2, 0x30D4AF37)
				}
				y += SETTINGS_HEADER_H
				continue
			}
			val hovered = mouseX >= contentX() && mouseX < contentX() + contentW() && mouseY >= y && mouseY < y + SETTINGS_ROW_H
			val labelX = contentX() + 8 + row.indent * SETTINGS_INDENT
			val label = font.plainSubstrByWidth(AxoI18n.translate(row.option!!.name), widgetX - labelX - 6)
			if (hovered) {
				graphics.fill(contentX() + 2, y, contentX() + contentW() - 2, y + SETTINGS_ROW_H, if (carved) 0x12CEA464 else 0x10FFFFFF)
			}
			graphics.text(
				font, label, labelX, y + (SETTINGS_ROW_H - 8) / 2,
				if (carved) (if (hovered) CarvedTheme.MARBLE else settingsTextCarved) else textColor, false
			)
			y += SETTINGS_ROW_H
		}
		graphics.disableScissor()

		// scrollbar, in the same voice as the grid's
		val max = maxSettingsScroll()
		if (max > 0) {
			val trackX = contentX() + contentW() + 2
			val bandH = bottom - top
			val thumbH = max(16, (bandH * (bandH / (bandH + max))).toInt())
			val thumbY = top + ((bandH - thumbH) * (settingsScroll / max)).toInt()
			if (carved) {
				graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, CarvedTheme.BRONZE_DARK)
				graphics.fill(trackX, thumbY, trackX + 1, thumbY + thumbH, CarvedTheme.BRONZE)
			} else {
				graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0x80D4AF37.toInt())
			}
		}
	}

	// ── v3 rendering ─────────────────────────────────────────────────────────────────────────

	private fun extractRenderStateV3(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
		advanceHoverFades(mouseX, mouseY)
		// A theme switch mid-session may have created the legacy footer buttons; park them.
		if (::editHudButton.isInitialized) {
			editHudButton.visible = false
			profilesButton.visible = false
			allSettingsButton.visible = false
		}
		if (settingsEntry != null) {
			graphics.enableScissor(contentX(), settingsScrollTop(), contentX() + contentW(), settingsScrollBottom())
		}
		super.extractRenderState(graphics, mouseX, mouseY, delta)
		if (settingsEntry != null) {
			graphics.disableScissor()
		}

		extractSidebarV3(graphics, mouseX, mouseY)
		extractHeaderV3(graphics)
		if (settingsEntry != null) {
			extractSettingsPanelV3(graphics, mouseX, mouseY)
		} else {
			extractSegmentsV3(graphics, mouseX, mouseY)
			extractCardsV3(graphics, mouseX, mouseY)
		}
	}

	private fun extractSidebarV3(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		// header: emblem and the two-line wordmark
		graphics.blit(RenderPipelines.GUI_TEXTURED, Theme.EMBLEM, V3_PAD, V3_PAD, 0f, 0f, V3_HEADER_H, V3_HEADER_H, 128, 128, 128, 128)
		Theme.trackedCaps(graphics, font, "ROMANICA", V3_PAD + V3_HEADER_H + 6, V3_PAD + 4, Theme.TEXT_ACCENT)
		Theme.trackedCaps(graphics, font, "CLIENT", V3_PAD + V3_HEADER_H + 6, V3_PAD + 15, Theme.TEXT_SECONDARY)

		val selectedIndex = navIndex(category)
		val targetY = navItemY(selectedIndex) - navScroll.toInt()
		if (!pillInit) {
			pillFromY = targetY.toFloat()
			pillY = targetY.toFloat()
			pillStartMs = now()
			pillInit = true
		}
		val t = if (reduceMotion()) 1f else Mth.clamp((now() - pillStartMs) / V3_PILL_MS, 0f, 1f)
		pillY = pillFromY + (targetY - pillFromY) * easeOut(t)

		// nav items (clipped when a very short window compresses them toward the footer)
		graphics.enableScissor(0, navTop - 2, V3_SIDEBAR_W, navClipBottom() + 2)
		// the sliding pill: soft glow ring, primary fill, secondary border
		val pillX = V3_PAD
		val pillW = V3_SIDEBAR_W - V3_PAD * 2
		Theme.roundRect(graphics, pillX - 2, pillY.toInt() - 1, pillW + 4, navItemH + 2, Theme.SECONDARY, (navItemH + 2) / 2f)
		Theme.roundRect(graphics, pillX, pillY.toInt(), pillW, navItemH, Theme.PRIMARY, navItemH / 2f)
		Theme.roundOutline(graphics, pillX, pillY.toInt(), pillW, navItemH, Theme.SECONDARY, navItemH / 2f)
		for (i in MenuCategory.entries.indices) {
			val c = MenuCategory.entries[i]
			val y = navItemY(i) - navScroll.toInt()
			if (y + navItemH < navTop - 2 || y > navClipBottom() + 2) continue
			val selected = i == selectedIndex
			val hovered = mouseX < V3_SIDEBAR_W && mouseY >= y && mouseY < y + navItemH
			val glyph = glyphFor(c)
			val iconX = V3_PAD + 10
			val iconY = y + (navItemH - Theme.glyphHeight(glyph)) / 2
			Theme.drawGlyph(graphics, glyph, iconX, iconY, if (selected) Theme.TEXT else Theme.TEXT_SECONDARY)
			val label = Component.translatable(c.translationKey()).string
			val labelX = iconX + 13
			val labelY = y + (navItemH - font.lineHeight + 1) / 2
			val text = ImperiumFonts.truncate(font, label, V3_SIDEBAR_W - V3_PAD - labelX - 4)
			graphics.text(
				font, ImperiumFonts.label(text), labelX, labelY,
				if (selected || hovered) Theme.TEXT else Theme.TEXT_SECONDARY, false
			)
		}
		graphics.disableScissor()

		// footer pills: edit hud / profiles / all settings
		val actions = arrayOf(
			Component.translatable("imperium.menu.edit_hud").string to Theme.GLYPH_HUD,
			Component.translatable("imperium.menu.profiles").string to Theme.GLYPH_PERSON,
			Component.translatable("imperium.menu.all_settings").string to Theme.GLYPH_GEAR
		)
		if (footerCompact) {
			val y = height - V3_PAD - V3_PILL_COMPACT
			var x = V3_PAD
			for (i in actions.indices) {
				val (_, glyph) = actions[i]
				val hovered = mxInPill(mouseX, mouseY, x, y, V3_PILL_COMPACT, V3_PILL_COMPACT)
				val t = controlFade("pill:$i", hovered)
				val scale = controlScale(t, pressFade("pill:$i"))
				poseScale(graphics, x, y, V3_PILL_COMPACT, V3_PILL_COMPACT, scale) {
					Theme.roundRect(graphics, x, y, V3_PILL_COMPACT, V3_PILL_COMPACT, lerpColor(Theme.LAYER, Theme.PRIMARY_HOVER, t), V3_PILL_COMPACT / 2f)
					Theme.roundOutline(graphics, x, y, V3_PILL_COMPACT, V3_PILL_COMPACT, lerpColor(Theme.COMPONENT, Theme.ACCENT_SOFT, t), V3_PILL_COMPACT / 2f)
					Theme.drawGlyph(graphics, glyph, x + (V3_PILL_COMPACT - glyph[0].length) / 2, y + (V3_PILL_COMPACT - glyph.size) / 2, if (t > 0.5f) Theme.TEXT else Theme.TEXT_SECONDARY)
				}
				x += V3_PILL_COMPACT + V3_SEG_GAP
			}
		} else {
			val w = V3_SIDEBAR_W - V3_PAD * 2
			var y = height - V3_PAD - 3 * V3_PILL_H - 2 * V3_PILL_GAP
			for (i in actions.indices) {
				val (label, glyph) = actions[i]
				val hovered = mxInPill(mouseX, mouseY, V3_PAD, y, w, V3_PILL_H)
				val t = controlFade("pill:$i", hovered)
				val scale = controlScale(t, pressFade("pill:$i"))
				poseScale(graphics, V3_PAD, y, w, V3_PILL_H, scale) {
					Theme.roundRect(graphics, V3_PAD, y, w, V3_PILL_H, lerpColor(Theme.LAYER, Theme.PRIMARY_HOVER, t), V3_PILL_H / 2f)
					Theme.roundOutline(graphics, V3_PAD, y, w, V3_PILL_H, lerpColor(Theme.COMPONENT, Theme.ACCENT_SOFT, t), V3_PILL_H / 2f)
					Theme.drawGlyph(graphics, glyph, V3_PAD + 8, y + (V3_PILL_H - glyph.size) / 2, if (t > 0.5f) Theme.TEXT else Theme.TEXT_SECONDARY)
					val text = ImperiumFonts.truncate(font, label, V3_SIDEBAR_W - V3_PAD - (V3_PAD + 22) - 4)
					graphics.text(
						font, ImperiumFonts.semibold(text), V3_PAD + 22, y + (V3_PILL_H - font.lineHeight + 1) / 2,
						if (t > 0.5f) Theme.TEXT else Theme.TEXT_SECONDARY, false
					)
				}
				y += V3_PILL_H + V3_PILL_GAP
			}
		}
	}

	private fun extractHeaderV3(graphics: GuiGraphicsExtractor) {
		// the header column squeezes on very small windows (GUI scale 3 on an 854x480 frame):
		// both lines truncate to the space between the sidebar and the search pill.
		val headerRight = if (settingsEntry == null) searchX() - 6 else width - V3_CONTENT_PAD
		val headerW = headerRight - (V3_SIDEBAR_W + V3_CONTENT_PAD)
		if (headerW != headerCacheW) {
			headerCacheW = headerW
			val title: String
			val description: String
			if (settingsEntry != null) {
				title = settingsEntry!!.name()
				description = Component.translatable(settingsEntry!!.category().translationKey()).string
			} else {
				title = Component.translatable("imperium.menu.header.title").string
				description = Component.translatable("imperium.menu.header.description").string
			}
			headerTitleTrunc = ImperiumFonts.truncate(font, title, headerW, ImperiumFonts.BARLOW_SEMIBOLD)
			headerDescriptionTrunc = ImperiumFonts.truncate(font, description, headerW)
		}
		graphics.text(font, ImperiumFonts.semibold(headerTitleTrunc), V3_SIDEBAR_W + V3_CONTENT_PAD, V3_HEADER_Y, Theme.TEXT, false)
		graphics.text(font, ImperiumFonts.label(headerDescriptionTrunc), V3_SIDEBAR_W + V3_CONTENT_PAD, V3_HEADER_Y + 11, Theme.TEXT_SECONDARY, false)

		if (settingsEntry == null) {
			// search affordance over the pill + the enabled-count badge
			Theme.drawGlyph(graphics, Theme.GLYPH_SEARCH, searchX() + 7, searchY() + 4, Theme.TEXT_TERTIARY)
			val count = enabledCount()
			if (count != enabledBadgeCount) {
				enabledBadgeCount = count
				enabledBadgeText = Component.translatable("imperium.menu.enabled_count", count).string
			}
			val text = enabledBadgeText
			val w = ImperiumFonts.width(font, text, ImperiumFonts.BARLOW_SEMIBOLD) + 22
			val x = searchX() - 6 - w
			Theme.roundRect(graphics, x, V3_HEADER_Y + 3, w, V3_BADGE_H, Theme.SECONDARY, V3_BADGE_H / 2f)
			Theme.roundOutline(graphics, x, V3_HEADER_Y + 3, w, V3_BADGE_H, Theme.ACCENT_SOFT, V3_BADGE_H / 2f)
			Theme.drawGlyph(graphics, Theme.GLYPH_BOLT, x + 6, V3_HEADER_Y + 3 + (V3_BADGE_H - Theme.GLYPH_BOLT.size) / 2, Theme.TEXT_ACCENT)
			graphics.text(font, ImperiumFonts.semibold(text), x + 14, V3_HEADER_Y + 3 + (V3_BADGE_H - font.lineHeight + 1) / 2, Theme.TEXT_ACCENT, false)
		}
	}

	private fun extractSegmentsV3(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		ensureSegmentCache()
		val labels = segmentLabelsCache
		for (i in MenuCategory.entries.indices) {
			val c = MenuCategory.entries[i]
			val selected = c == category
			val pos = segmentPos[i] ?: continue
			val x = pos[0]
			val y = pos[1]
			val w = segmentWidth(i) + V3_SEG_PAD * 2
			val hovered = !selected && mxInPill(mouseX, mouseY, x, y, w, V3_SEG_H)
			val t = controlFade("seg:$i", hovered)
			val scale = controlScale(t, pressFade("seg:$i"))
			poseScale(graphics, x, y, w, V3_SEG_H, scale) {
				if (selected) {
					Theme.roundRect(graphics, x, y, w, V3_SEG_H, Theme.PRIMARY, V3_SEG_H / 2f)
					Theme.roundOutline(graphics, x + 1, y + 1, w - 2, V3_SEG_H - 2, Theme.ACCENT_SOFT, (V3_SEG_H - 2) / 2f)
				} else {
					Theme.roundRect(graphics, x, y, w, V3_SEG_H, lerpColor(0x00000000.toInt(), Theme.PRIMARY_HOVER, t), V3_SEG_H / 2f)
					Theme.roundOutline(graphics, x, y, w, V3_SEG_H, lerpColor(0x00000000.toInt(), Theme.COMPONENT, t * 0.6f), V3_SEG_H / 2f)
				}
				graphics.text(
					font, ImperiumFonts.semibold(labels[i]), x + V3_SEG_PAD, y + (V3_SEG_H - font.lineHeight + 1) / 2,
					if (selected || t > 0.5f) Theme.TEXT else Theme.TEXT_SECONDARY, false
				)
			}
		}
	}

	private fun extractCardsV3(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		if (visible.isEmpty()) {
			graphics.centeredText(
				font, ImperiumFonts.label(Component.translatable("imperium.menu.no_results").string),
				contentX() + contentW() / 2, contentY() + listTopInset() + contentH() / 2 - 4, Theme.TEXT_TERTIARY
			)
			return
		}
		graphics.enableScissor(contentX(), contentY(), contentX() + contentW(), contentY() + contentH())
		for (i in visible.indices) {
			val entry = visible[i]
			val x = cardX(i)
			val y = cardY(i)
			if (y + cardH() < contentY() || y > contentY() + contentH()) continue
			val t = hoverFade(entry.id())
			val scale = controlScale(min(1f, t * 2), pressFade("card:${entry.id()}"))
			val pose = graphics.pose()
			pose.pushMatrix()
			pose.translate((x + cardW() / 2).toFloat(), (y + cardH() / 2).toFloat())
			pose.scale(scale, scale)
			pose.translate(-(x + cardW() / 2).toFloat(), -(y + cardH() / 2).toFloat())

			// vanilla item icon at its native 16px inside the well
			graphics.fakeItem(CarvedTheme.iconFor(entry), wellX(x) + 3, wellY(y) + 3)

			val blocked = isBlocked(entry)
			val on = entry.enabled() && !blocked
			val hovered = inContent(mouseX.toDouble(), mouseY.toDouble()) &&
				mouseX >= x && mouseX < x + cardW() && mouseY >= y && mouseY < y + cardH()

			// name, top-right
			val nameColor = if (on || hovered || t > 0.5f) Theme.TEXT else Theme.TEXT_TERTIARY
			val name = ImperiumFonts.truncate(font, entry.name(), cardNameRight(x) - cardTagX(x))
			graphics.text(font, ImperiumFonts.label(name), cardNameRight(x) - ImperiumFonts.width(font, name), y + 6, nameColor, false)

			// reason / tag line, bottom-left
			val pinned = pinnedIds.contains(entry.id())
			val tag: String? = when {
				blocked -> Component.translatable("imperium.menu.blocked").string
				pinned -> Component.translatable("imperium.menu.pin").string
				else -> null
			}
			if (tag != null) {
				val tagText = ImperiumFonts.truncate(font, tag, cardW() - (cardTagX(x) - x) - 3 * 15 - 4)
				graphics.text(
					font, ImperiumFonts.label(tagText), cardTagX(x), y + cardH() - 13,
					if (blocked) Theme.BLOCKED_TAG else Theme.TEXT_DISABLED, false
				)
			}

			// action glyphs, bottom-right: power / pin / settings
			val powerColor = when {
				blocked -> Theme.ERROR
				on -> Theme.TEXT_ACCENT
				else -> Theme.TEXT_DISABLED
			}
			Theme.drawGlyph(graphics, Theme.GLYPH_POWER, actionX(x, 0), actionY(y), powerColor)
			Theme.drawGlyph(graphics, Theme.GLYPH_PIN, actionX(x, 1), actionY(y), if (pinned) Theme.TEXT_ACCENT else Theme.TEXT_DISABLED)
			val gearHovered = hovered && mouseX >= actionX(x, 2) && mouseX < actionX(x, 2) + 10
			Theme.drawGlyph(
				graphics, Theme.GLYPH_GEAR, actionX(x, 2), actionY(y),
				if (gearHovered) Theme.TEXT_ACCENT else Theme.TEXT_DISABLED
			)
			if (blocked) {
				// state is never colour-alone: a block mark beside the power glyph
				Theme.drawGlyph(graphics, Theme.GLYPH_BLOCK, actionX(x, 0) - 12, actionY(y), Theme.ERROR)
			}
			pose.popMatrix()
		}
		graphics.disableScissor()

		val max = maxScroll()
		if (max > 0) {
			val trackX = contentX() + contentW() - 2
			val thumbH = max(16, (contentH() * (contentH() / (contentH() + max))).toInt())
			val thumbY = contentY() + ((contentH() - thumbH) * (scroll / max)).toInt()
			Theme.roundRect(graphics, trackX, thumbY, 2, thumbH, Theme.ACCENT_HALF, 1f)
		}
	}

	/** The v3 settings panel chrome: back pill, section headers, labels, reset icons. */
	private fun extractSettingsPanelV3(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		// back pill on the segment line
		val bx = contentX()
		val by = bandY() - 2
		val bw = 44
		val bh = V3_SEG_H + 2
		val backHovered = mxInPill(mouseX, mouseY, bx, by, bw, bh)
		val bt = controlFade("back", backHovered)
		val bScale = controlScale(bt, pressFade("back"))
		poseScale(graphics, bx, by, bw, bh, bScale) {
			Theme.roundRect(graphics, bx, by, bw, bh, lerpColor(Theme.LAYER, Theme.PRIMARY_HOVER, bt), bh / 2f)
			Theme.roundOutline(graphics, bx, by, bw, bh, lerpColor(Theme.COMPONENT, Theme.ACCENT_SOFT, bt), bh / 2f)
			val text = ImperiumFonts.truncate(font, "< " + Component.translatable("imperium.menu.settings_back").string, bw - 12, ImperiumFonts.BARLOW_SEMIBOLD)
			graphics.text(
				font, ImperiumFonts.semibold(text), bx + 8,
				by + (bh - font.lineHeight) / 2, if (bt > 0.5f) Theme.TEXT else Theme.TEXT_SECONDARY, false
			)
		}

		val top = settingsScrollTop()
		val bottom = settingsScrollBottom()
		graphics.enableScissor(contentX(), top, contentX() + contentW(), bottom)
		var y = top - settingsScroll.toInt()
		for (row in settingsRows) {
			if (row.header != null) {
				// section header: 14x2 accent dash + label
				graphics.fill(contentX() + 2, y + 4, contentX() + 16, y + 6, Theme.TEXT_ACCENT)
				graphics.text(font, ImperiumFonts.semibold(row.header.uppercase(Locale.ROOT)), contentX() + 21, y + 1, Theme.TEXT_SECONDARY, false)
				y += settingsHeaderH()
				continue
			}
			val rect = rowRect(row)
			val hovered = mxInPill(mouseX, mouseY, rect[0], y, rect[1], SETTINGS_ROW_H)
			if (hovered) {
				Theme.roundRect(graphics, rect[0], y, rect[1], SETTINGS_ROW_H, Theme.ROW_HOVER_BG, V3_ROW_R)
				Theme.roundOutline(graphics, rect[0], y, rect[1], SETTINGS_ROW_H, Theme.ACCENT_SOFT, V3_ROW_R)
			}
			val labelX = rect[0] + 8 + row.indent * SETTINGS_INDENT
			val resetX = rect[0] + rect[1] - V3_RESET_W - 7
			val widgetLeft = row.widget?.x ?: (resetX - 1)
			val label = ImperiumFonts.truncate(font, AxoI18n.translate(row.option!!.name), max(8, widgetLeft - labelX - 4))
			graphics.text(
				font, ImperiumFonts.label(label), labelX, y + (SETTINGS_ROW_H - font.lineHeight + 1) / 2,
				if (hovered) Theme.TEXT else Theme.TEXT_SECONDARY, false
			)
			// reset icon at the row end
			val resetHovered = mouseX >= resetX && mouseX < resetX + 9 && mouseY >= y && mouseY < y + SETTINGS_ROW_H
			Theme.drawGlyph(
				graphics, Theme.GLYPH_RESET, resetX, y + (SETTINGS_ROW_H - 9) / 2,
				if (resetHovered) Theme.TEXT_ACCENT else Theme.TEXT_DISABLED
			)
			y += rowAdvance(row)
		}
		graphics.disableScissor()

		val max = maxSettingsScroll()
		if (max > 0) {
			val trackX = contentX() + contentW() - 2
			val bandH = bottom - top
			val thumbH = max(16, (bandH * (bandH / (bandH + max))).toInt())
			val thumbY = top + ((bandH - thumbH) * (settingsScroll / max)).toInt()
			Theme.roundRect(graphics, trackX, thumbY, 2, thumbH, Theme.ACCENT_HALF, 1f)
		}
	}

	private fun isRowSearchHit(row: SettingsRow): Boolean {
		if (query.isBlank() || row.option == null) return false
		val label = AxoI18n.translate(row.option!!.name).lowercase(Locale.ROOT)
		return label.contains(query.lowercase(Locale.ROOT).trim())
	}

	// hover/press fades: 200 ms ease-out colour toward the hovered state, 100 ms for the scale
	// (the spec's control motion: 1.033 hover, 0.966 press, the nav pill sliding in 125 ms).

	private fun hoverFade(id: String): Float = hoverT[id] ?: 0f

	/** A press fade for hand-drawn controls (cards, pills, segments), 1 while held. */
	private fun pressFade(key: String): Float = pressT[key] ?: 0f

	private fun controlScale(t: Float, p: Float): Float =
		if (reduceMotion()) 1f else 1f + 0.033f * easeOut(t) - 0.067f * easeOut(p)

	/** A hovered test for the hand-drawn controls (GUI ints from the mouse position). */
	private fun mxInPill(mouseX: Int, mouseY: Int, x: Int, y: Int, w: Int, h: Int): Boolean =
		mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h

	/** The control's hover fade value, snapped when "Reduce motion" is on. */
	private fun controlFade(key: String, hovered: Boolean): Float {
		if (reduceMotion()) return if (hovered) 1f else 0f
		return hoverT[key] ?: 0f
	}

	/** Draws [block] scaled by [scale] around the rect's centre. */
	private inline fun poseScale(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, scale: Float, block: () -> Unit) {
		if (scale == 1f) {
			block()
			return
		}
		val pose = graphics.pose()
		pose.pushMatrix()
		pose.translate((x + w / 2).toFloat(), (y + h / 2).toFloat())
		pose.scale(scale, scale)
		pose.translate(-(x + w / 2).toFloat(), -(y + h / 2).toFloat())
		block()
		pose.popMatrix()
	}

	private fun advanceHoverFades(mouseX: Int, mouseY: Int) {
		val now = now()
		val dt = if (lastFrameMs == 0L) 0f else Mth.clamp((now - lastFrameMs) / 1000f, 0f, 0.1f)
		lastFrameMs = now
		if (reduceMotion()) {
			return
		}
		val targets = LinkedHashMap<String, Float>()
		if (settingsEntry == null && visible.isNotEmpty()) {
			for (i in visible.indices) {
				val entry = visible[i]
				val x = cardX(i)
				val y = cardY(i)
				if (y + cardH() < contentY() || y > contentY() + contentH()) continue
				val hovered = inContent(mouseX.toDouble(), mouseY.toDouble()) &&
					mouseX >= x && mouseX < x + cardW() && mouseY >= y && mouseY < y + cardH()
				targets[entry.id()] = if (hovered) 1f else 0f
			}
			// category segments
			for (i in MenuCategory.entries.indices) {
				val pos = segmentPos[i] ?: continue
				val w = segmentWidth(i) + V3_SEG_PAD * 2
				targets["seg:$i"] = if (category != MenuCategory.entries[i] && mxInPill(mouseX, mouseY, pos[0], pos[1], w, V3_SEG_H)) 1f else 0f
			}
		}
		if (settingsEntry != null) {
			targets["back"] = if (overBackPillV3(mouseX, mouseY)) 1f else 0f
		} else if (footerCompact) {
			val y = height - V3_PAD - V3_PILL_COMPACT
			for (i in 0 until 3) {
				val x = V3_PAD + i * (V3_PILL_COMPACT + V3_SEG_GAP)
				targets["pill:$i"] = if (mxInPill(mouseX, mouseY, x, y, V3_PILL_COMPACT, V3_PILL_COMPACT)) 1f else 0f
			}
		} else {
			val w = V3_SIDEBAR_W - V3_PAD * 2
			var y = height - V3_PAD - 3 * V3_PILL_H - 2 * V3_PILL_GAP
			for (i in 0 until 3) {
				targets["pill:$i"] = if (mxInPill(mouseX, mouseY, V3_PAD, y, w, V3_PILL_H)) 1f else 0f
				y += V3_PILL_H + V3_PILL_GAP
			}
		}
		for ((key, target) in targets) {
			val t = hoverT[key] ?: 0f
			val next = if (t < target) min(target, t + dt / 0.2f) else max(target, t - dt / 0.2f)
			if (next <= 0f) hoverT.remove(key) else hoverT[key] = next
		}
		// press fades run on their own faster curve toward "the control that is held"
		for (key in pressT.keys.toList()) {
			val target = if (key == pressedKey) 1f else 0f
			val t = pressT[key] ?: 0f
			val next = if (t < target) min(target, t + dt / 0.1f) else max(target, t - dt / 0.1f)
			if (next <= 0f) pressT.remove(key) else pressT[key] = next
		}
		val pressed = pressedKey
		if (pressed != null) {
			pressT.putIfAbsent(pressed, 0f)
		}
	}

	private fun now(): Long = System.currentTimeMillis()

	private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t)

	private fun lerpColor(from: Int, to: Int, t: Float): Int {
		if (t <= 0f) return from
		if (t >= 1f) return to
		val fa = (from ushr 24) and 0xFF
		val fr = (from ushr 16) and 0xFF
		val fg = (from ushr 8) and 0xFF
		val fb = from and 0xFF
		val ta = (to ushr 24) and 0xFF
		val tr = (to ushr 16) and 0xFF
		val tg = (to ushr 8) and 0xFF
		val tb = to and 0xFF
		return ((fa + (ta - fa) * t).toInt() shl 24) or ((fr + (tr - fr) * t).toInt() shl 16) or
			((fg + (tg - fg) * t).toInt() shl 8) or (fb + (tb - fb) * t).toInt()
	}

	private fun glyphFor(c: MenuCategory): Array<String> = when (c) {
		MenuCategory.ALL -> Theme.GLYPH_ALL
		MenuCategory.HUD -> Theme.GLYPH_HUD
		MenuCategory.RENDER -> Theme.GLYPH_RENDER
		MenuCategory.CHAT -> Theme.GLYPH_CHAT
		MenuCategory.WORLD -> Theme.GLYPH_WORLD
		MenuCategory.UTILITY -> Theme.GLYPH_UTILITY
		MenuCategory.AUTOMATION -> Theme.GLYPH_AUTOMATION
		MenuCategory.SERVER -> Theme.GLYPH_SERVER
	}

	// input

	override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
		val mx = event.x()
		val my = event.y()
		if (v3()) {
			return mouseClickedV3(event, mx.toInt(), my.toInt())
		}
		if (settingsEntry != null) {
			// the back button wins over any row scrolled under it
			if (overSettingsBack(mx, my)) {
				closeSettingsPanel()
				return true
			}
			// widgets only answer inside the row band; the title band never leaks clicks to them
			if (my >= settingsScrollTop() && my < settingsScrollBottom() && super.mouseClicked(event, doubleClick)) return true
		} else if (super.mouseClicked(event, doubleClick)) return true

		var y = panelY + HEADER_H + 6
		for (c in MenuCategory.entries) {
			if (mx >= panelX && mx < panelX + SIDEBAR_W && my >= y && my < y + ROW_H) {
				if (settingsEntry != null) closeSettingsPanel()
				category = c
				lastCategory = c
				scroll = 0.0
				refilter()
				return true
			}
			y += ROW_H
		}

		if (settingsEntry != null || !inContent(mx, my)) return false
		for (i in visible.indices) {
			val x = cardX(i)
			val cy = cardY(i)
			if (mx < x || mx >= x + cardW() || my < cy || my >= cy + cardH()) continue
			val entry = visible[i]
			val gx = x + cardW() - GEAR_SIZE - 5
			val gy = cy + cardH() - GEAR_SIZE - 4
			val onGear = mx >= gx && mx < gx + GEAR_SIZE && my >= gy && my < gy + GEAR_SIZE
			if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT || onGear || !entry.hasToggle()) {
				openSettings(i)
			} else {
				entry.toggle()
			}
			return true
		}
		return false
	}

	private fun mouseClickedV3(event: MouseButtonEvent, mx: Int, my: Int): Boolean {
		if (settingsEntry != null) {
			// the back pill wins over any row scrolled under it
			if (overBackPillV3(mx, my)) {
				pressedKey = "back"
				closeSettingsPanel()
				return true
			}
			if (my >= settingsScrollTop() && my < settingsScrollBottom()) {
				val hit = resetHitV3(mx, my)
				if (hit != null) {
					resetOption(hit)
					return true
				}
				if (super.mouseClicked(event, false)) return true
			}
		} else if (super.mouseClicked(event, false)) return true

		// sidebar nav items
		for (i in MenuCategory.entries.indices) {
			val y = navItemY(i) - navScroll.toInt()
			if (mx < V3_SIDEBAR_W && my >= y && my < y + navItemH) {
				if (settingsEntry != null) closeSettingsPanel()
				selectCategory(MenuCategory.entries[i])
				return true
			}
		}

		// footer action pills
		if (mx < V3_SIDEBAR_W && mx >= V3_PAD) {
			if (footerCompact) {
				val y = height - V3_PAD - V3_PILL_COMPACT
				if (my >= y && my < y + V3_PILL_COMPACT && mx < V3_PAD + 3 * V3_PILL_COMPACT + 2 * V3_SEG_GAP) {
					pressedKey = "pill:" + (mx - V3_PAD) / (V3_PILL_COMPACT + V3_SEG_GAP)
					when ((mx - V3_PAD) / (V3_PILL_COMPACT + V3_SEG_GAP)) {
						0 -> {
							minecraft!!.gui.setScreen(ImperiumHudEditScreen(this))
							return true
						}
						1 -> {
							minecraft!!.gui.setScreen(ProfilesScreen(this))
							return true
						}
						2 -> {
							minecraft!!.gui.setScreen(ConfigStyles.createScreen(this, AxolotlClientCommon.getInstance().configManager.root))
							return true
						}
					}
				}
			} else {
				var y = height - V3_PAD - 3 * V3_PILL_H - 2 * V3_PILL_GAP
				for (i in 0 until 3) {
					if (my >= y && my < y + V3_PILL_H) {
						pressedKey = "pill:$i"
						when (i) {
							0 -> minecraft!!.gui.setScreen(ImperiumHudEditScreen(this))
							1 -> minecraft!!.gui.setScreen(ProfilesScreen(this))
							2 -> minecraft!!.gui.setScreen(ConfigStyles.createScreen(this, AxolotlClientCommon.getInstance().configManager.root))
						}
						return true
					}
					y += V3_PILL_H + V3_PILL_GAP
				}
			}
		}

		// category segments
		if (settingsEntry == null) {
			for (i in MenuCategory.entries.indices) {
				val pos = segmentPos[i] ?: continue
				val w = segmentWidth(i) + V3_SEG_PAD * 2
				if (my >= pos[1] && my < pos[1] + V3_SEG_H && mx >= pos[0] && mx < pos[0] + w) {
					pressedKey = "seg:$i"
					selectCategory(MenuCategory.entries[i])
					return true
				}
			}
		}

		if (settingsEntry != null || !inContent(mx.toDouble(), my.toDouble())) return false
		for (i in visible.indices) {
			val x = cardX(i)
			val cy = cardY(i)
			if (mx < x || mx >= x + cardW() || my < cy || my >= cy + cardH()) continue
			val entry = visible[i]
			pressedKey = "card:${entry.id()}"
			if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT || !entry.hasToggle()) {
				openSettings(i)
				return true
			}
			// action glyphs: power toggles, pin pins, gear opens settings
			if (my >= actionY(cy) - 2 && my < actionY(cy) + 12) {
				if (mx >= actionX(x, 2) - 2 && mx < actionX(x, 2) + 12) {
					openSettings(i)
					return true
				}
				if (mx >= actionX(x, 1) - 2 && mx < actionX(x, 1) + 12) {
					togglePin(entry)
					return true
				}
				if (mx >= actionX(x, 0) - 2 && mx < actionX(x, 0) + 12) {
					if (!isBlocked(entry)) entry.toggle()
					return true
				}
			}
			if (!isBlocked(entry)) entry.toggle()
			return true
		}
		return false
	}

	private fun selectCategory(c: MenuCategory) {
		pillFromY = pillY
		pillStartMs = now()
		category = c
		lastCategory = c
		scroll = 0.0
		refilter()
	}

	private fun togglePin(entry: MenuEntry) {
		if (!pinnedIds.add(entry.id())) {
			pinnedIds.remove(entry.id())
		}
		refilter()
	}

	private fun overBackPillV3(mx: Int, my: Int): Boolean {
		val bx = contentX()
		val by = bandY() - 2
		return mx >= bx && mx < bx + 44 && my >= by && my < by + V3_SEG_H + 2
	}

	private fun resetHitV3(mx: Int, my: Int): SettingsRow? {
		var y = settingsScrollTop() - settingsScroll.toInt()
		for (row in settingsRows) {
			if (row.header != null) {
				y += settingsHeaderH()
				continue
			}
			if (row.option != null && my >= y && my < y + SETTINGS_ROW_H) {
				val rect = rowRect(row)
				val resetX = rect[0] + rect[1] - V3_RESET_W - 7
				if (mx >= resetX && mx < resetX + 9) {
					return if (row.paired && mx >= rect[0] + rect[1]) {
						nextOptionRow(row) ?: row
					} else {
						row
					}
				}
				if (!row.paired && !row.pairRight) {
					break
				}
			}
			y += rowAdvance(row)
		}
		return null
	}

	private fun nextOptionRow(row: SettingsRow): SettingsRow? {
		val index = settingsRows.indexOf(row)
		if (index < 0 || index + 1 >= settingsRows.size) return null
		val next = settingsRows[index + 1]
		return if (next.option != null) next else null
	}

	override fun mouseReleased(event: MouseButtonEvent): Boolean {
		// the press scale (0.966) holds only while the control is held
		pressedKey = null
		return super.mouseReleased(event)
	}

	override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
		if (settingsEntry != null) {
			if (mouseX >= contentX() && mouseX < contentX() + contentW() && mouseY >= settingsScrollTop() && mouseY < settingsScrollBottom()) {
				settingsScroll = Mth.clamp(settingsScroll - scrollY * SETTINGS_ROW_H / 2, 0.0, maxSettingsScroll())
				layoutSettingsWidgets()
				return true
			}
			return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
		}
		if (v3() && mouseX >= 0 && mouseX < V3_SIDEBAR_W && maxNavScroll() > 0) {
			navScroll = Mth.clamp(navScroll - scrollY * navItemH / 2, 0.0, maxNavScroll())
			return true
		}
		if (inContent(mouseX, mouseY)) {
			scroll = Mth.clamp(scroll - scrollY * (cardH() + cardGap()) / 2, 0.0, maxScroll())
			return true
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
	}

	override fun keyPressed(event: KeyEvent): Boolean {
		// The first Escape closes the settings panel; only the second closes the menu.
		if (settingsEntry != null && event.isEscape) {
			closeSettingsPanel()
			return true
		}
		return super.keyPressed(event)
	}

	override fun onClose() {
		minecraft!!.gui.setScreen(parent)
	}

	override fun isPauseScreen(): Boolean = false

	/** One row of the settings panel: either a sub-category header or an option with its widget. */
	private class SettingsRow(
		@JvmField val header: String?,
		@JvmField val option: Option<*>?,
		widget: AbstractWidget?,
		@JvmField val indent: Int
	) {
		@JvmField
		var widget: AbstractWidget? = widget

		/** v3: first of a half-width boolean pair (shares a band with the next row). */
		@JvmField
		var paired = false

		/** v3: second of a half-width boolean pair. */
		@JvmField
		var pairRight = false
	}

	companion object {
		/**
		 * Callback hook for the HUD editor v2's context menu: builds the menu with [parent] and
		 * flags [hud]'s settings panel to open as soon as the menu initialises.
		 */
		@JvmStatic
		fun openWithHudSettings(parent: Screen?, hud: HudEntry): ImperiumMenuScreen {
			val screen = ImperiumMenuScreen(parent)
			screen.pendingHudEntry = hud
			return screen
		}

		private const val HEADER_H = 30
		private const val FOOTER_H = 28
		private const val SIDEBAR_W = 92
		private const val ROW_H = 18
		private const val CARD_H = 44
		private const val GAP = 6
		private const val GEAR_SIZE = 12
		private const val ICON_SIZE = 16

		// Inline settings panel metrics (all inside the content area, right of the sidebar).
		private const val SETTINGS_TITLE_H = 20
		private const val SETTINGS_ROW_H = 22
		private const val SETTINGS_HEADER_H = 16
		private const val SETTINGS_WIDGET_W = 110
		private const val SETTINGS_WIDGET_H = 20
		private const val SETTINGS_INDENT = 8
		private const val SETTINGS_BACK_W = 46
		private const val SETTINGS_BACK_H = 14

		// v3 shell metrics: the spec's CSS sizes halved (mockups are 1920x1080 = GUI px at scale 2).
		private const val V3_SIDEBAR_W = 135        // 270 px sidebar
		private const val V3_PAD = 15               // 30 px sidebar padding
		private const val V3_HEADER_H = 28          // sidebar header block
		private const val V3_NAV_TOP = 12
		private const val V3_NAV_H = 24             // 48 px nav items, radius 26 -> 13
		private const val V3_NAV_GAP = 4
		private const val V3_PILL_H = 16            // 32 px footer pills
		private const val V3_PILL_GAP = 5
		private const val V3_PILL_TOP = 6
		private const val V3_PILL_COMPACT = 14
		private const val V3_CONTENT_PAD = 22       // 44 px content padding
		private const val V3_CONTENT_PAD_TOP = 4
		private const val V3_HEADER_Y = 15          // content header at 314/30 -> +22/+15
		private const val V3_CONTENT_Y = 50         // content from y 100
		private const val V3_SEG_H = 18             // 36 px category segments, radius 18 -> 9
		private const val V3_SEG_PAD = 8
		private const val V3_SEG_GAP = 2
		private const val V3_CARD_H = 39            // 78 px cards, radius 22 -> 11
		private const val V3_CARD_R = 11f
		private const val V3_CARD_GAP = 8           // 16 px grid gap
		private const val V3_MIN_CARD_W = 80
		private const val V3_WELL = 23              // 46 px icon well, radius 14 -> 7
		private const val V3_WELL_R = 7f
		private const val V3_SEARCH_W = 150         // 300 px search pill, height 36 -> 18
		private const val V3_SEARCH_H = 18
		private const val V3_SEARCH_ICON_W = 9
		private const val V3_BADGE_H = 16
		private const val V3_ROW_R = 6f             // 44 px rows, radius 12 -> 6
		private const val V3_SETTINGS_HEADER_H = 10 // 28x2 accent dash + 14 px label
		private const val V3_PAIR_GAP = 6
		private const val V3_CHECK_W = 36
		private const val V3_RESET_W = 9
		private const val V3_LIST_BOTTOM_PAD = 7
		private const val V3_PILL_MS = 125f         // nav indicator slide

		/** Menu entry ids of the server-gated modules, mapped to their protocol gate ids. */
		private val GATE_IDS = mapOf(
			"autofish" to ServerGating.AUTO_FISH,
			"autoclick" to ServerGating.AUTO_CLICK,
			"autowalk" to ServerGating.AUTO_WALK,
			"autoeat" to ServerGating.AUTO_EAT,
			"autotool" to ServerGating.AUTO_TOOL,
			"autoarmor" to ServerGating.AUTO_ARMOR,
			"autoreconnect" to ServerGating.AUTO_RECONNECT,
			"quickphrases" to ServerGating.QUICK_PHRASES
		)

		private var lastCategory = MenuCategory.ALL

		/** Pinned module ids (session-scoped): pinned cards sort first and show the pin glyph. */
		private val pinnedIds = HashSet<String>()

		private fun carved(): Boolean =
			AxolotlClient.config().menuTheme.get() == AxolotlClientConfigCommon.MenuTheme.CARVED
	}
}
