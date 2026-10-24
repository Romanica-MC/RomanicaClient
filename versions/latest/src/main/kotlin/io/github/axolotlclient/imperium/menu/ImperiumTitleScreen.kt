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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

import io.github.axolotlclient.AxolotlClient
import io.github.axolotlclient.AxolotlClientCommon
import io.github.axolotlclient.AxolotlClientConfig.impl.util.ConfigStyles
import io.github.axolotlclient.imperium.theme.ImperiumFonts
import io.github.axolotlclient.imperium.theme.ImperiumTheme as Theme
import io.github.axolotlclient.modules.auth.Auth
import io.github.axolotlclient.util.ThreadExecuter
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.options.OptionsScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth

/**
 * The v3 title screen (docs/design/README.md, mockup 04) over the vanilla rotating panorama:
 * an account switcher top-left, a row of 40px icon circles top-right (Modules, the client's
 * settings, romanica.net, the store, Discord, the vanilla menu), the emblem and wordmark
 * centred, a 400x50 Play Romanica button carrying the live player count from
 * romanica.net/api/server/status (silent when offline), and the Options/Quit row.
 *
 * Sizes are the spec's CSS measurements halved, like the menu shell. [TitleScreenMixin] swaps
 * every vanilla [TitleScreen] for this screen unless the player asked for the vanilla menu via
 * the last icon circle (a one-shot flag, so returning to the title restores this layout).
 */
class ImperiumTitleScreen : TitleScreen(false) {

	// hover fades per hot element (200 ms colour, faster scale), like the menu cards
	private val hoverT = HashMap<String, Float>()

	// press fades (100 ms): the spec's 0.966 press scale on the hand-drawn buttons
	private val pressT = HashMap<String, Float>()
	private var pressedKey: String? = null
	private var lastFrameMs = 0L

	init {
		fetchPlayerCount()
	}

	override fun init() {
		// no super.init(): the vanilla button layout (and its mixins) stays dormant; every
		// control here is drawn and hit-tested by hand in the spec's styling
	}

	// ── layout ───────────────────────────────────────────────────────────────────────────────

	private fun accountX(): Int = INSET

	private fun accountY(): Int = INSET

	private fun accountName(): String {
		val account = Auth.getInstance().getCurrent() ?: return "Offline"
		return account.name
	}

	private fun accountSubtitle(): String = Component.translatable("imperium.title.switch_account").string

	private fun accountW(): Int =
		maxOf(
			ImperiumFonts.width(font, accountName(), ImperiumFonts.BARLOW_SEMIBOLD),
			ImperiumFonts.width(font, accountSubtitle())
		) + HEAD + 5 + CHEVRON_W + 12

	private fun emblemH(): Int = if (height >= 300) EMBLEM_H else EMBLEM_H_COMPACT

	private fun stackTop(): Int {
		val stack = emblemH() + 6 + WORDMARK_H + 12 + PLAY_H + 5 + ROW_H
		return max(30, (height - stack) / 2 - 10)
	}

	private fun emblemY(): Int = stackTop()

	private fun wordmarkY(): Int = emblemY() + emblemH() + 6

	private fun playY(): Int = wordmarkY() + WORDMARK_H + 12

	private fun rowY(): Int = playY() + PLAY_H + 5

	private fun playX(): Int = width / 2 - PLAY_W / 2

	private fun optionX(): Int = width / 2 - PLAY_W / 2

	private fun quitX(): Int = width / 2 + 5

	/** x of the idx-th top-right circle (0 = leftmost of the row). */
	private fun circleX(idx: Int): Int = width - INSET - CIRCLE_D - idx * (CIRCLE_D + CIRCLE_GAP)

	private fun circleY(): Int = INSET

	// ── rendering ─────────────────────────────────────────────────────────────────────────────

	override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
		// panorama + blur + menu background, unchanged
		super.extractBackground(graphics, mouseX, mouseY, delta)
	}

	override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
		advanceFades(mouseX, mouseY)

		drawAccountSwitcher(graphics, mouseX, mouseY)
		drawCircles(graphics, mouseX, mouseY)

		// centre column: emblem, wordmark
		val eh = emblemH()
		graphics.blit(RenderPipelines.GUI_TEXTURED, Theme.EMBLEM, width / 2 - eh / 2, emblemY(), 0f, 0f, eh, eh, 128, 128, 128, 128)
		drawWordmark(graphics, width / 2, wordmarkY() + WORDMARK_H / 2)

		drawBigButton(
			graphics, mouseX, mouseY, playX(), playY(), PLAY_W, PLAY_H, "play",
			Component.translatable("imperium.title.play").string,
			playerCountLine(), true
		)
		drawBigButton(
			graphics, mouseX, mouseY, optionX(), rowY(), ROW_BTN_W, ROW_H, "options",
			Component.translatable("options.title").string, null, false
		)
		drawBigButton(
			graphics, mouseX, mouseY, quitX(), rowY(), ROW_BTN_W, ROW_H, "quit",
			Component.translatable("menu.quit").string, null, false
		)

		// footer: client version left, Mojang disclaimer centred; the version truncates to the
		// left half so it never runs into the centred line on narrow windows
		val copyright = Component.translatable("imperium.title.copyright").string
		val copyrightW = ImperiumFonts.width(font, copyright)
		val version = ImperiumFonts.truncate(font, "Romanica Client " + AxolotlClientCommon.VERSION, width / 2 - copyrightW / 2 - 8)
		graphics.text(
			font, ImperiumFonts.label(version), INSET, height - FOOTER_H,
			Theme.OVERLAY_TEXT_SECONDARY, false
		)
		graphics.text(
			font, ImperiumFonts.label(copyright), width / 2 - copyrightW / 2, height - FOOTER_H,
			Theme.OVERLAY_TEXT_SECONDARY, false
		)
	}

	private fun drawAccountSwitcher(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		val x = accountX()
		val y = accountY()
		val w = accountW()
		val hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ACCOUNT_H
		val t = fade("account", hovered)
		Theme.roundRect(graphics, x, y, w, ACCOUNT_H, lerpColor(Theme.OVERLAY_CONTROL, Theme.OVERLAY_CONTROL_HOVER, t), ACCOUNT_H / 2f)
		Theme.roundOutline(graphics, x, y, w, ACCOUNT_H, lerpColor(Theme.OVERLAY_BORDER, Theme.OVERLAY_BORDER_HOVER, t), ACCOUNT_H / 2f)

		// the player's head: Auth serves the default skin for this UUID until the real one loads
		val account = Auth.getInstance().getCurrent()
		val uuid = AccountHead.uuidOf(account?.uuid)
		val texture = if (account != null && uuid != null) {
			Auth.getInstance().getSkinTexture(account)
		} else {
			DefaultPlayerSkin.get(UUID(0L, 0L)).body().texturePath()
		}
		PlayerFaceExtractor.extractRenderState(graphics, texture, x + 2, y + 1, HEAD, true, false, -1)

		graphics.text(font, ImperiumFonts.semibold(accountName()), x + HEAD + 5, y + 2, Theme.OVERLAY_TEXT, false)
		graphics.text(font, ImperiumFonts.label(accountSubtitle()), x + HEAD + 5, y + 11, Theme.OVERLAY_TEXT_SECONDARY, false)
		Theme.drawGlyph(graphics, Theme.GLYPH_CHEVRON, x + w - CHEVRON_W - 6, y + (ACCOUNT_H - 4) / 2, Theme.OVERLAY_TEXT_SECONDARY)
	}

	private fun drawWordmark(graphics: GuiGraphicsExtractor, cx: Int, cy: Int) {
		// the Cinzel-style wordmark, approximated: tracked capitals at 2x (see the font note
		// in docs/design - no new TTF binaries ship with the client)
		val w = Theme.trackedWidth(font, "ROMANICA") * 2
		val pose = graphics.pose()
		pose.pushMatrix()
		pose.translate((cx - w / 2).toFloat(), (cy - WORDMARK_H / 2).toFloat())
		pose.scale(2f, 2f)
		Theme.trackedCaps(graphics, font, "ROMANICA", 0, 0, Theme.TEXT_ACCENT)
		pose.popMatrix()
	}

	private fun drawCircles(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		for (i in CIRCLE_ACTIONS.indices) {
			val action = CIRCLE_ACTIONS[i]
			val x = circleX(CIRCLE_ACTIONS.size - 1 - i)
			val y = circleY()
			val hovered = mouseX >= x && mouseX < x + CIRCLE_D && mouseY >= y && mouseY < y + CIRCLE_D
			val t = fade("circle$i", hovered)
			val scale = (1f + 0.1f * easeOut(t)) - 0.134f * easeOut(pressFade("circle$i"))
			val pose = graphics.pose()
			pose.pushMatrix()
			pose.translate((x + CIRCLE_D / 2).toFloat(), (y + CIRCLE_D / 2).toFloat())
			pose.scale(scale, scale)
			pose.translate(-(x + CIRCLE_D / 2).toFloat(), -(y + CIRCLE_D / 2).toFloat())
			Theme.roundRect(graphics, x, y, CIRCLE_D, CIRCLE_D, lerpColor(Theme.OVERLAY_CONTROL, Theme.OVERLAY_CONTROL_HOVER, t), CIRCLE_D / 2f)
			Theme.roundOutline(graphics, x, y, CIRCLE_D, CIRCLE_D, lerpColor(Theme.OVERLAY_BORDER, Theme.OVERLAY_BORDER_HOVER, t), CIRCLE_D / 2f)
			Theme.drawGlyph(
				graphics, action.second, x + (CIRCLE_D - action.second[0].length) / 2,
				y + (CIRCLE_D - action.second.size) / 2,
				if (t > 0.5f) Theme.TEXT else Theme.OVERLAY_TEXT_SECONDARY
			)
			pose.popMatrix()
			// hover label under the circle
			if (t > 0.05f) {
				val label = Component.translatable(action.first).string.uppercase()
				graphics.text(
					font, ImperiumFonts.semibold(label), x + CIRCLE_D / 2 - ImperiumFonts.width(font, label, ImperiumFonts.BARLOW_SEMIBOLD) / 2, y + CIRCLE_D + 3,
					lerpColor(0x00000000.toInt(), Theme.OVERLAY_TEXT, t), false
				)
			}
		}
	}

	/** The main button: primary fill for Play, ghost for Options/Quit; optional count line. */
	private fun drawBigButton(
		graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int,
		x: Int, y: Int, w: Int, h: Int, id: String, label: String, sub: String?, accent: Boolean
	) {
		val hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h
		val t = fade(id, hovered)
		val scale = (1f + 0.033f * easeOut(t)) - 0.067f * easeOut(pressFade(id))
		val pose = graphics.pose()
		pose.pushMatrix()
		pose.translate((x + w / 2).toFloat(), (y + h / 2).toFloat())
		pose.scale(scale, scale)
		pose.translate(-(x + w / 2).toFloat(), -(y + h / 2).toFloat())
		if (accent) {
			// glow just outside the fill
			Theme.roundRect(graphics, x - 2, y - 2, w + 4, h + 4, lerpColor(0x00000000.toInt(), Theme.OVERLAY_GLOW, t), (h + 4) / 2f)
			Theme.roundRect(graphics, x, y, w, h, lerpColor(Theme.PRIMARY, Theme.PRIMARY_HOVER, t), h / 2f)
			Theme.roundOutline(graphics, x, y, w, h, lerpColor(Theme.SECONDARY, Theme.ACCENT_SOFT, t), h / 2f)
		} else {
			Theme.roundRect(graphics, x, y, w, h, lerpColor(0x66000000.toInt(), Theme.PRIMARY_HOVER, t), h / 2f)
			Theme.roundOutline(graphics, x, y, w, h, lerpColor(Theme.COMPONENT, Theme.ACCENT_SOFT, t), h / 2f)
		}
		val labelW = ImperiumFonts.width(font, label, ImperiumFonts.BARLOW_SEMIBOLD)
		if (sub == null) {
			graphics.text(font, ImperiumFonts.semibold(label), x + w / 2 - labelW / 2, y + (h - font.lineHeight + 1) / 2, Theme.TEXT, false)
		} else {
			graphics.text(font, ImperiumFonts.semibold(label), x + w / 2 - labelW / 2, y + 3, Theme.TEXT, false)
			graphics.text(font, ImperiumFonts.label(sub), x + w / 2 - ImperiumFonts.width(font, sub) / 2, y + h - 11, Theme.TEXT_SECONDARY, false)
		}
		pose.popMatrix()
	}

	// ── input ────────────────────────────────────────────────────────────────────────────────

	override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
		val mx = event.x().toInt()
		val my = event.y().toInt()

		if (mx >= accountX() && mx < accountX() + accountW() && my >= accountY() && my < accountY() + ACCOUNT_H) {
			pressedKey = "account"
			minecraft!!.gui.setScreen(io.github.axolotlclient.modules.auth.AccountsScreen(this))
			return true
		}

		for (i in CIRCLE_ACTIONS.indices) {
			val action = CIRCLE_ACTIONS[i]
			val x = circleX(CIRCLE_ACTIONS.size - 1 - i)
			val y = circleY()
			if (mx >= x - 2 && mx < x + CIRCLE_D + 2 && my >= y - 2 && my < y + CIRCLE_D + 2) {
				pressedKey = "circle$i"
				when (action.third) {
					Action.MODULES -> minecraft!!.gui.setScreen(ImperiumMenuScreen(this))
					Action.CLIENT -> minecraft!!.gui.setScreen(
						ConfigStyles.createScreen(this, AxolotlClientCommon.getInstance().configManager.root)
					)
					Action.SITE -> openLink(SITE_URL)
					Action.STORE -> openLink(STORE_URL)
					Action.DISCORD -> openLink(DISCORD_URL)
					Action.VANILLA -> {
						showVanillaMenu = true
						minecraft!!.gui.setScreen(TitleScreen(false))
					}
				}
				return true
			}
		}

		if (my >= playY() && my < playY() + PLAY_H && mx >= playX() && mx < playX() + PLAY_W) {
			pressedKey = "play"
			val server = ServerData("Romanica", SERVER_ADDRESS, ServerData.Type.OTHER)
			ConnectScreen.startConnecting(
				this, minecraft!!, ServerAddress.parseString(SERVER_ADDRESS), server, false, null
			)
			return true
		}
		if (my >= rowY() && my < rowY() + ROW_H) {
			if (mx >= optionX() && mx < optionX() + ROW_BTN_W) {
				pressedKey = "options"
				minecraft!!.gui.setScreen(OptionsScreen(this, minecraft!!.options))
				return true
			}
			if (mx >= quitX() && mx < quitX() + ROW_BTN_W) {
				pressedKey = "quit"
				minecraft!!.stop()
				return true
			}
		}
		return super.mouseClicked(event, doubleClick)
	}

	override fun mouseReleased(event: MouseButtonEvent): Boolean {
		// the press scale (0.966) holds only while the control is held
		pressedKey = null
		return super.mouseReleased(event)
	}

	private fun openLink(url: String) {
		ConfirmLinkScreen.confirmLinkNow(this, URI.create(url))
	}

	// ── animation helpers (same voice as the menu shell) ─────────────────────────────────────

	private fun fade(id: String, hovered: Boolean): Float {
		if (AxolotlClient.config().menuReduceMotion.get()) {
			return if (hovered) 1f else 0f
		}
		return hoverT[id] ?: 0f
	}

	private fun pressFade(id: String): Float {
		if (AxolotlClient.config().menuReduceMotion.get()) {
			return if (pressedKey == id) 1f else 0f
		}
		return pressT[id] ?: 0f
	}

	private fun advanceFades(mouseX: Int, mouseY: Int) {
		val now = System.currentTimeMillis()
		val dt = if (lastFrameMs == 0L) 0f else Mth.clamp((now - lastFrameMs) / 1000f, 0f, 0.1f)
		lastFrameMs = now
		if (AxolotlClient.config().menuReduceMotion.get()) {
			return
		}
		fun hit(x: Int, y: Int, w: Int, h: Int): Boolean =
			mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h

		val targets = HashMap<String, Boolean>()
		targets["account"] = hit(accountX(), accountY(), accountW(), ACCOUNT_H)
		for (i in CIRCLE_ACTIONS.indices) {
			val x = circleX(CIRCLE_ACTIONS.size - 1 - i)
			targets["circle$i"] = hit(x, circleY(), CIRCLE_D, CIRCLE_D)
		}
		targets["play"] = hit(playX(), playY(), PLAY_W, PLAY_H)
		targets["options"] = hit(optionX(), rowY(), ROW_BTN_W, ROW_H)
		targets["quit"] = hit(quitX(), rowY(), ROW_BTN_W, ROW_H)
		for ((id, hovered) in targets) {
			val t = hoverT[id] ?: 0f
			val target = if (hovered) 1f else 0f
			val next = if (t < target) min(target, t + dt / 0.2f) else max(target, t - dt / 0.2f)
			if (next <= 0f) hoverT.remove(id) else hoverT[id] = next
		}
		// press fades: 100 ms toward whichever control is held
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

	private enum class Action { MODULES, CLIENT, SITE, STORE, DISCORD, VANILLA }

	companion object {
		private const val INSET = 10
		private const val ACCOUNT_H = 22
		private const val HEAD = 18
		private const val CHEVRON_W = 8
		private const val CIRCLE_D = 20
		private const val CIRCLE_GAP = 5
		private const val EMBLEM_H = 125
		private const val EMBLEM_H_COMPACT = 90
		private const val WORDMARK_H = 18
		private const val PLAY_W = 200
		private const val PLAY_H = 25
		private const val ROW_H = 25
		private const val ROW_BTN_W = 97
		private const val FOOTER_H = 14
		private const val SERVER_ADDRESS = "play.romanica.net"
		private const val SITE_URL = "https://romanica.net"
		private const val STORE_URL = "https://romanica.net/store"
		private const val DISCORD_URL = "https://romanica.net/discord"

		/** One-shot: the last click asked for the vanilla menu; the next title swap skips. */
		@JvmStatic
		@Volatile
		var showVanillaMenu = false

		private val CIRCLE_ACTIONS = listOf(
			Triple("imperium.title.circle.modules", Theme.GLYPH_ALL, Action.MODULES),
			Triple("imperium.title.circle.client", Theme.GLYPH_GEAR, Action.CLIENT),
			Triple("imperium.title.circle.site", Theme.GLYPH_WORLD, Action.SITE),
			Triple("imperium.title.circle.store", Theme.GLYPH_STORE, Action.STORE),
			Triple("imperium.title.circle.discord", Theme.GLYPH_CHAT, Action.DISCORD),
			Triple("imperium.title.circle.vanilla", Theme.GLYPH_MENU, Action.VANILLA)
		)

		// live player count, fetched once per process; null until (and unless) it arrives
		@Volatile
		private var playerCount: String? = null

		@Volatile
		private var fetchedCount = false

		private fun playerCountLine(): String? {
			val count = playerCount ?: return null
			return Component.translatable("imperium.title.players", count).string
		}

		private fun fetchPlayerCount() {
			if (fetchedCount) {
				return
			}
			fetchedCount = true
			ThreadExecuter.scheduleTask {
				try {
					val request = HttpRequest.newBuilder(URI.create("https://romanica.net/api/server/status"))
						.timeout(java.time.Duration.ofSeconds(4))
						.GET()
						.build()
					val response = HttpClient.newBuilder()
						.connectTimeout(java.time.Duration.ofSeconds(4))
						.build()
						.send(request, HttpResponse.BodyHandlers.ofString())
					playerCount = parsePlayerCount(response.body())
				} catch (e: Exception) {
					// offline / slow / malformed: the play button simply carries no count
					playerCount = null
				}
			}
		}

		/** Reads players.online / players.max out of the status JSON without a JSON dependency. */
		@JvmStatic
		fun parsePlayerCount(body: String): String? {
			val online = regexGroup(body, """"online"\s*:\s*(\d+)""") ?: return null
			val max = regexGroup(body, """"max"\s*:\s*(\d+)""")
			return if (max == null) online else "$online / $max"
		}

		private fun regexGroup(body: String, pattern: String): String? {
			val m = java.util.regex.Pattern.compile(pattern).matcher(body)
			return if (m.find()) m.group(1) else null
		}
	}
}
