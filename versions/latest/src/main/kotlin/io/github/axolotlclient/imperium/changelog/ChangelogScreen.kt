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

package io.github.axolotlclient.imperium.changelog

import kotlin.math.max
import kotlin.math.min

import io.github.axolotlclient.imperium.theme.ImperiumFonts
import io.github.axolotlclient.imperium.theme.ImperiumTheme as Theme
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.Mth

/**
 * The client changelog behind the title screen's "What's new" entry (issue #28): newest
 * first, one card per post with its date, lead and section bullets, in the menu shell's
 * design voice. Entries the player has not seen yet carry an accent dot; opening the view
 * marks everything shown as seen so the title screen's dot clears.
 *
 * <p>Entries come in through the constructor so the gametest can render a deterministic
 * list without network; production passes [ChangelogService.entries]. Wrapping is
 * measured once per [init]/[resize], never per frame; cards outside the scroll band are
 * culled before any drawing work.</p>
 */
class ChangelogScreen(
	private val parent: Screen?,
	entries: List<ChangelogEntry>,
	/** False on the test path, where seen-state must not be consumed by a screenshot run. */
	private val markSeen: Boolean
) : Screen(Component.translatable("imperium.changelog.title")) {

	constructor(parent: Screen?) : this(parent, ChangelogService.entries, markSeen = true)

	// snapshot taken when the view opens: the dots this visit shows, before markSeenThrough
	private val unreadIds: Set<String>
	private val posts: List<ChangelogEntry> = entries
	private var cards: List<Card> = emptyList()
	private var scroll = 0.0

	init {
		val lastSeen = ChangelogService.lastSeenId()
		val unread = mutableSetOf<String>()
		for (entry in posts) {
			if (entry.id == lastSeen) break
			unread.add(entry.id)
		}
		unreadIds = unread
		if (markSeen) {
			posts.firstOrNull()?.let { ChangelogService.markSeenThrough(it) }
		}
	}

	override fun init() {
		buildCards()
	}

	override fun resize(width: Int, height: Int) {
		super.resize(width, height)
		buildCards()
	}

	override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
		super.extractRenderState(graphics, mouseX, mouseY, delta)

		// dimmed page like the menu shell's overlay cards
		Theme.roundRect(graphics, 0, 0, width, height, Theme.OVERLAY_SHADOW, 0f)

		graphics.text(
			font, ImperiumFonts.semibold(title.string),
			width / 2 - ImperiumFonts.width(font, title.string, ImperiumFonts.BARLOW_SEMIBOLD) / 2,
			HEADER_H / 2 - 5, Theme.OVERLAY_TEXT, false
		)

		drawSitePill(graphics, mouseX, mouseY)
		drawBackPill(graphics, mouseX, mouseY)

		if (cards.isEmpty()) {
			val empty = Component.translatable("imperium.changelog.empty").string
			graphics.text(
				font, ImperiumFonts.label(empty),
				width / 2 - ImperiumFonts.width(font, empty) / 2, height / 2,
				Theme.OVERLAY_TEXT_SECONDARY, false
			)
			return
		}

		val top = HEADER_H + 4
		val bottom = height - FOOTER_H
		graphics.enableScissor(0, top, width, bottom)
		for (card in cards) {
			if (card.y + card.height < top || card.y > bottom) {
				continue // culled before any work (issue #3)
			}
			drawCard(graphics, card)
		}
		graphics.disableScissor()
		drawScrollbar(graphics, top, bottom)
	}

	private fun drawCard(graphics: GuiGraphicsExtractor, card: Card) {
		Theme.roundRect(graphics, card.x, card.y, card.w, card.height, Theme.CARD_BG, 6f)
		Theme.roundOutline(graphics, card.x, card.y, card.w, card.height, Theme.CARD_BORDER, 6f)

		var y = card.y + 7
		if (card.entry.id in unreadIds) {
			// the "What's new" dot, on the card too, so the player sees which posts are new
			graphics.fill(card.x + card.w - 12, y + 3, card.x + card.w - 8, y + 7, Theme.TEXT_ACCENT)
		}
		graphics.text(
			font, ImperiumFonts.label(card.dateText),
			card.x + card.w - 8 - ImperiumFonts.width(font, card.dateText), y,
			Theme.TEXT_TERTIARY, false
		)
		graphics.text(font, ImperiumFonts.semibold(card.entry.title), card.x + 8, y, Theme.OVERLAY_TEXT, false)
		y += 12
		val version = card.entry.version
		if (version != null) {
			val badge = "v$version"
			Theme.roundRect(graphics, card.x + 8, y, ImperiumFonts.width(font, badge) + 8, 11, Theme.SECONDARY, 5f)
			graphics.text(font, ImperiumFonts.label(badge), card.x + 12, y + 2, Theme.TEXT_ACCENT, false)
			y += 14
		}
		for (line in card.summaryLines) {
			graphics.text(font, line, card.x + 8, y, Theme.TEXT_SECONDARY)
			y += LINE_H
		}
		for (section in card.sections) {
			graphics.text(font, ImperiumFonts.semibold(section.name), card.x + 8, y + 2, Theme.TEXT_ACCENT, false)
			y += 14
			for ((bullet, line) in section.lines) {
				if (bullet) {
					graphics.text(font, ImperiumFonts.label("-"), card.x + 8, y, Theme.TEXT_TERTIARY, false)
				}
				graphics.text(font, line, card.x + 16, y, Theme.TEXT_SECONDARY)
				y += LINE_H
			}
		}
	}

	private fun drawSitePill(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		val label = Component.translatable("imperium.changelog.website").string
		val w = ImperiumFonts.width(font, label, ImperiumFonts.BARLOW_SEMIBOLD) + 16
		val x = width - w - INSET
		val y = HEADER_H / 2 - 9
		val hovered = hit(x, y, w, 18, mouseX, mouseY)
		Theme.roundRect(graphics, x, y, w, 18, if (hovered) Theme.OVERLAY_CONTROL_HOVER else Theme.OVERLAY_CONTROL, 9f)
		Theme.roundOutline(graphics, x, y, w, 18, Theme.OVERLAY_BORDER, 9f)
		graphics.text(font, ImperiumFonts.semibold(label), x + 8, y + 5, Theme.OVERLAY_TEXT, false)
	}

	private fun drawBackPill(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		val label = Component.translatable("imperium.changelog.back").string
		val w = ImperiumFonts.width(font, label, ImperiumFonts.BARLOW_SEMIBOLD) + 16
		val x = INSET
		val y = HEADER_H / 2 - 9
		val hovered = hit(x, y, w, 18, mouseX, mouseY)
		Theme.roundRect(graphics, x, y, w, 18, if (hovered) Theme.OVERLAY_CONTROL_HOVER else Theme.OVERLAY_CONTROL, 9f)
		Theme.roundOutline(graphics, x, y, w, 18, Theme.OVERLAY_BORDER, 9f)
		graphics.text(font, ImperiumFonts.semibold(label), x + 8, y + 5, Theme.OVERLAY_TEXT, false)
	}

	private fun drawScrollbar(graphics: GuiGraphicsExtractor, top: Int, bottom: Int) {
		val max = maxScroll()
		if (max <= 0.0) {
			return
		}
		val track = bottom - top
		val thumbH = max(24, (track * track / (track + contentHeight())).toInt())
		val thumbY = top + ((track - thumbH) * (scroll / max)).toInt()
		Theme.roundRect(graphics, width - 4, thumbY, 3, thumbH, Theme.OVERLAY_TEXT_SECONDARY, 1.5f)
	}

	// ── layout (measured once per resize; the render path only draws) ────────────────────────

	/** Font-backed wrapping must happen after the screen's font exists, hence not in the ctor. */
	private fun buildCards() {
		val contentW = cardW()
		var y = HEADER_H + 4 + 6
		val built = ArrayList<Card>(posts.size)
		for (entry in posts) {
			val card = Card(entry, contentW)
			card.x = cardX()
			card.w = contentW
			card.y = y
			y += card.height + CARD_GAP
			built.add(card)
		}
		cards = built
		scroll = Mth.clamp(scroll, 0.0, maxScroll())
	}

	private fun cardX(): Int = width / 2 - cardW() / 2

	private fun cardW(): Int = min(440, width - INSET * 2 - 8)

	private fun contentHeight(): Int =
		if (cards.isEmpty()) 0 else cards.last().y + cards.last().height - (HEADER_H + 4 + 6)

	private fun maxScroll(): Double =
		max(0.0, (contentHeight() - (height - HEADER_H - 4 - FOOTER_H)).toDouble())

	// ── input ────────────────────────────────────────────────────────────────────────────────

	override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
		scroll = Mth.clamp(scroll - scrollY * SCROLL_STEP, 0.0, maxScroll())
		return true
	}

	override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
		val mx = event.x().toInt()
		val my = event.y().toInt()
		val siteLabel = Component.translatable("imperium.changelog.website").string
		val siteW = ImperiumFonts.width(font, siteLabel, ImperiumFonts.BARLOW_SEMIBOLD) + 16
		if (hit(width - siteW - INSET, HEADER_H / 2 - 9, siteW, 18, mx, my)) {
			ChangelogService.openWebsiteChangelog(this)
			return true
		}
		val backLabel = Component.translatable("imperium.changelog.back").string
		val backW = ImperiumFonts.width(font, backLabel, ImperiumFonts.BARLOW_SEMIBOLD) + 16
		if (hit(INSET, HEADER_H / 2 - 9, backW, 18, mx, my)) {
			onClose()
			return true
		}
		return super.mouseClicked(event, doubleClick)
	}

	override fun keyPressed(event: KeyEvent): Boolean {
		if (event.isEscape) {
			onClose()
			return true
		}
		return super.keyPressed(event)
	}

	override fun onClose() {
		minecraft!!.gui.setScreen(parent)
	}

	override fun isPauseScreen(): Boolean = false

	// ── helpers ──────────────────────────────────────────────────────────────────────────────

	private fun hit(x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int): Boolean =
		mx >= x && mx < x + w && my >= y && my < y + h

	/** A laid-out changelog post: measured once per resize, drawn every frame. */
	private inner class Card(entry: ChangelogEntry, contentW: Int) {
		val entry: ChangelogEntry = entry
		var x = 0
		var y = 0
		var w = 0
		var height: Int

		val dateText: String = formatDay(entry.postedAt)
		val summaryLines: List<FormattedCharSequence> = wrap(entry.summary, contentW)

		// (drawBullet, line): the first wrapped line of every change carries the dash
		val sections: List<SectionBlock> = entry.sections.map { section ->
			SectionBlock(
				section.name,
				section.changes.flatMap { change ->
					wrap(change, contentW - 8).mapIndexedTo(ArrayList()) { i, seq -> Pair(i == 0, seq) }
				}
			)
		}

		init {
			var inner = 12 + summaryLines.size * LINE_H
			for (section in sections) {
				inner += 14 + section.lines.size * LINE_H
			}
			if (entry.version != null) inner += 14
			height = inner + 7
		}
	}

	private inner class SectionBlock(val name: String, val lines: List<Pair<Boolean, FormattedCharSequence>>)

	private fun wrap(text: String, maxWidth: Int): List<FormattedCharSequence> =
		if (text.isBlank()) emptyList() else font.split(FormattedText.of(text), max(40, maxWidth - 8))

	// ── gametest accessors (plain-public like ImperiumMenuScreen's, read the live layout) ───

	fun cardCount(): Int = cards.size

	fun firstCardUnread(): Boolean = cards.firstOrNull()?.let { it.entry.id in unreadIds } ?: false

	fun websitePillCenter(): IntArray {
		val w = ImperiumFonts.width(font, Component.translatable("imperium.changelog.website").string, ImperiumFonts.BARLOW_SEMIBOLD) + 16
		return intArrayOf(width - w - INSET + w / 2, HEADER_H / 2)
	}

	fun backPillCenter(): IntArray {
		val w = ImperiumFonts.width(font, Component.translatable("imperium.changelog.back").string, ImperiumFonts.BARLOW_SEMIBOLD) + 16
		return intArrayOf(INSET + w / 2, HEADER_H / 2)
	}

	companion object {

		private const val INSET = 10
		private const val HEADER_H = 28
		private const val FOOTER_H = 14
		private const val CARD_GAP = 8

		/** One wrapped text row, matching the menu shell's dense body text. */
		private const val LINE_H = 10

		// a couple of text rows per wheel notch, like the menu grid's scroll feel
		private const val SCROLL_STEP = 80.0

		/** "2026-10-27T23:19:38.270Z" -> "2026-10-27"; anything else passes through. */
		@JvmStatic
		fun formatDay(postedAt: String): String =
			if (postedAt.length >= 10 && postedAt[4] == '-' && postedAt[7] == '-') postedAt.substring(0, 10) else postedAt
	}
}
