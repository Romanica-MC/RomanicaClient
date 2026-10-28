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

import io.github.axolotlclient.AxolotlClient
import io.github.axolotlclient.AxolotlClientCommon
import io.github.axolotlclient.util.ThreadExecuter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.slf4j.LoggerFactory

/**
 * Fetches the client changelog behind the title screen's "What's new" entry (issue #28)
 * and tracks which entry the player last saw, so the entry can carry an unread dot.
 *
 * <p>Nothing here may ever break the title screen: the fetch runs off-thread and swallows
 * every failure into {@link State#FAILED}; a failed (or empty) fetch sends the click to
 * {@link #WEBSITE_CHANGELOG_URL} in the browser instead of opening the in-client view.
 * {@link #urlOpener} exists so the gametest can prove that fallback without a desktop.</p>
 */
object ChangelogService {

	private val LOGGER = LoggerFactory.getLogger("RomanicaClient")

	const val LIST_URL = "https://api.romanica.net/api/v2/changelog/list?product=client"
	const val WEBSITE_CHANGELOG_URL = "https://romanica.net/changelog"

	enum class State { IDLE, LOADING, LOADED, FAILED }

	@Volatile
	var state: State = State.IDLE
		private set

	/** Newest-first entries from the last successful fetch; empty otherwise. */
	@Volatile
	var entries: List<ChangelogEntry> = emptyList()
		private set

	@Volatile
	private var fetchedOnce = false

	/**
	 * Opens a URI outside the game, anchored to the screen that asked for it (MC's own
	 * confirm-link dialog, the same voice as the title screen's other links). Overridable
	 * so the gametest can verify the fallback without a desktop browser on CI; production
	 * code never reassigns it.
	 */
	@Volatile
	@JvmStatic
	var urlOpener: (net.minecraft.client.gui.screens.Screen?, URI) -> Unit =
		{ screen, uri ->
			if (screen != null) {
				net.minecraft.client.gui.screens.ConfirmLinkScreen.confirmLinkNow(screen, uri)
			}
		}

	/**
	 * Asks the backend for the client changelog; results land in [entries]/[state].
	 * Once per process, like the title screen's player count: a failed first attempt
	 * keeps the website fallback and is not retried silently in the background.
	 */
	fun refresh() {
		if (fetchedOnce || state == State.LOADING) {
			return
		}
		fetchedOnce = true
		state = State.LOADING
		ThreadExecuter.scheduleTask {
			// A superseded request never wins (the gametest pins state mid-flight).
			if (state != State.LOADING) {
				return@scheduleTask
			}
			try {
				val request = HttpRequest.newBuilder(URI.create(LIST_URL))
					.timeout(Duration.ofSeconds(4))
					.GET()
					.build()
				val response = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(4))
					.build()
					.send(request, HttpResponse.BodyHandlers.ofString())
				if (response.statusCode() !in 200..299) {
					LOGGER.warn("Changelog list returned HTTP {}; falling back to the website", response.statusCode())
					state = State.FAILED
					return@scheduleTask
				}
				val parsed = ChangelogEntry.parseList(response.body())
				if (parsed.isEmpty()) {
					// The endpoint carries no client entries yet: same treatment as offline.
					state = State.FAILED
					return@scheduleTask
				}
				entries = parsed
				state = State.LOADED
			} catch (e: Exception) {
				// Offline / slow / malformed: the title screen just never shows the dot.
				LOGGER.warn("Could not load the client changelog; falling back to the website", e)
				state = State.FAILED
			}
		}
	}

	/** The id of the newest entry the player has opened the view for. */
	fun lastSeenId(): String = AxolotlClient.config().changelogLastSeenId.get()

	/** True while a fetched list exists and the player has not seen its newest entry. */
	@JvmStatic
	fun hasUnread(): Boolean = state == State.LOADED && ChangelogEntry.hasUnread(entries, lastSeenId())

	/** Marks everything up to [newest] as seen; config writes go through the config system. */
	fun markSeenThrough(newest: ChangelogEntry) {
		if (lastSeenId() == newest.id) {
			return
		}
		AxolotlClient.config().changelogLastSeenId.set(newest.id)
		AxolotlClientCommon.getInstance().saveConfig()
	}

	/** Opens the website changelog when the in-client view cannot be shown. */
	fun openWebsiteChangelog(screen: net.minecraft.client.gui.screens.Screen?) {
		urlOpener(screen, URI.create(WEBSITE_CHANGELOG_URL))
	}

	/**
	 * Pins the fetch outcome so the gametest can click both "What's new" branches (view
	 * and website fallback) without depending on the real backend. Production code never
	 * calls this.
	 */
	@JvmStatic
	fun forceForTest(newState: State, newEntries: List<ChangelogEntry>) {
		state = newState
		entries = newEntries
	}
}
