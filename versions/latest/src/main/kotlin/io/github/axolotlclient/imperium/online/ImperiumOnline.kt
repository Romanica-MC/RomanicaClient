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

package io.github.axolotlclient.imperium.online

import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.UUID
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import io.github.axolotlclient.AxolotlClientCommon
import io.github.axolotlclient.imperium.ImperiumClientSession
import io.github.axolotlclient.imperium.LegionStateRegistry
import io.github.axolotlclient.util.NetworkUtil
import io.github.axolotlclient.util.ThreadExecuter

/**
 * Wiring for the ImperiumMC online services (client half of backend issue #2): builds
 * the live [ClientApiAuth] (game-session join + bearer JWT) and [ClientApi] (authorized
 * fetches), starts auth once a real Microsoft session exists, re-mints on session
 * change (account switch/relog), and feeds the legion API value into
 * [LegionStateRegistry] as a fallback when the plugin's `imperiumclient:legion` channel
 * does not own the session.
 *
 * Nothing here is player-facing by design (no config, no UI): auth is automatic, the
 * token is memory-only, and every failure degrades to "online features unavailable".
 *
 * joinServer is the game's own call, the one vanilla makes during server login on 26.3:
 * `minecraft.services().sessionService().joinServer(user.profileId, user.accessToken, serverId)`
 * (com.mojang.authlib.minecraft.SessionService, authlib 10.0.77).
 */
object ImperiumOnline : ClientModInitializer {

	private val NETWORK_DOMAINS = listOf("romanica.net")

	/** Slow re-auth cadence after a failure (backend not deployed yet, API down). */
	private const val FAILURE_RETRY_TICKS = 20L * 60 * 5

	/** Session-watch cadence: detect account switches cheaply. */
	private const val SESSION_WATCH_TICKS = 100

	@JvmStatic
	lateinit var auth: ClientApiAuth
		private set

	@JvmStatic
	lateinit var api: ClientApi
		private set

	private var initializedAuth = false
	private var ticks = 0
	private var ticksSinceFailure = 0L
	private var failureLogged = false

	override fun onInitializeClient() {
		auth = ClientApiAuth(JavaHttpTransport(), MinecraftGameSession(), ThreadExecuter.service())
		api = ClientApi(auth, JavaHttpTransport(), ThreadExecuter.service())

		ClientTickEvents.END_CLIENT_TICK.register { client ->
			if (!initializedAuth) {
				// First tick with a user: kick the initial attempt (offline accounts skip
				// inside the state machine, never here).
				if (client.user != null) {
					initializedAuth = true
					attemptAuth("initial")
				}
				return@register
			}
			if (--ticks > 0) {
				return@register
			}
			ticks = SESSION_WATCH_TICKS
			watchSessionChange(client)
		}

		// After leaving any server the channels clear the registry; when not connected
		// to ImperiumMC the API value may (re-)fill it as the fallback source.
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> refreshLegionFallback() }

		AxolotlClientCommon.getInstance().logger.debug("Romanica online services initialized")
	}

	/**
	 * One auth attempt with the single-line outcome log (no token material ever).
	 * On success also primes the legion fallback; failures arm the slow retry.
	 */
	private fun attemptAuth(what: String) {
		auth.authenticateAsync().whenComplete { state, _ ->
			when (state) {
				is ClientApiAuth.State.Authenticated ->
					AxolotlClientCommon.getInstance().logger.info("Romanica online session established ({})", what)
				is ClientApiAuth.State.OfflineSession -> {
					AxolotlClientCommon.getInstance().logger.debug("Romanica online auth skipped: no Microsoft session")
					return@whenComplete
				}
				is ClientApiAuth.State.Failed -> {
					ticksSinceFailure = 0
					if (!failureLogged) {
						// One line per failure streak, not one per attempt.
						failureLogged = true
						AxolotlClientCommon.getInstance().logger.info(
							"Romanica online features unavailable ({})", state.reason,
						)
					}
					return@whenComplete
				}
				else -> return@whenComplete
			}
			failureLogged = false
			refreshLegionFallback()
		}
	}

	/**
	 * Re-mint when the game session changed (account switch, launcher token refresh):
	 * the token is bound to the (profileId, accessToken) pair it was minted for.
	 */
	private fun watchSessionChange(client: Minecraft) {
		val minted = auth.authenticatedFor()
		if (minted != null) {
			val user = client.user ?: return
			if (minted != Pair(user.profileId, user.accessToken)) {
				auth.invalidate()
				attemptAuth("session-change")
			}
			return
		}
		// No token: retry the initial attempt slowly while the last one failed (the
		// offline-skip state never retries — nothing changed to make it succeed).
		if (auth.state() is ClientApiAuth.State.Failed) {
			ticksSinceFailure += SESSION_WATCH_TICKS
			if (ticksSinceFailure >= FAILURE_RETRY_TICKS && client.user != null) {
				attemptAuth("retry")
			}
		}
	}

	private fun refreshLegionFallback() {
		api.fetchLegion().thenAccept { result ->
			if (result is ClientApi.FetchResult.Ok) {
				LegionApiFallback.applyIfVacant(result.value, imperiumChannelOwnsSession())
			}
		}
	}

	/**
	 * True when the plugin's live channels own the HUD registries: connected to an
	 * ImperiumMC network server right now, or this session already answered the
	 * hello/features handshake. The API value must not fight the channel.
	 */
	private fun imperiumChannelOwnsSession(): Boolean {
		val client = Minecraft.getInstance() ?: return false
		if (ImperiumClientSession.serverResponded()) {
			return true
		}
		val raw = client.currentServer?.ip ?: return false
		var host = raw.trim().lowercase(Locale.ROOT)
		val colon = host.lastIndexOf(':')
		if (colon > 0 && host.indexOf(':') == colon) {
			host = host.substring(0, colon) // strip a :port (not IPv6)
		}
		while (host.endsWith(".")) {
			host = host.substring(0, host.length - 1)
		}
		return NETWORK_DOMAINS.any { host == it || host.endsWith(".$it") }
	}

	/**
	 * The legion fallback consumer. The channel stays authoritative: apply only when
	 * it does not own the session AND the registry is vacant (empty = not
	 * session-owned). onlineMembers and power are pinned to 0 — the API cannot see
	 * live presence or war power, and inventing numbers would be worse than none;
	 * the channel's first full push replaces the whole snapshot.
	 */
	object LegionApiFallback {

		@JvmStatic
		fun applyIfVacant(snapshot: ClientApi.LegionSnapshot, imperiumChannelOwnsSession: Boolean) {
			// Member names never fight the channel (it does not carry them yet), so they
			// are applied regardless of ownership — the tab-list group highlight depends
			// on them even while the channel owns the counts.
			if (snapshot.memberNames.isNotEmpty()) {
				LegionStateRegistry.setMembers(snapshot.memberNames)
			}
			if (imperiumChannelOwnsSession) {
				return
			}
			if (LegionStateRegistry.get() != null) {
				return
			}
			LegionStateRegistry.set(
				snapshot.displayName ?: snapshot.name,
				0,
				snapshot.memberCount,
				0L,
				snapshot.level,
			)
		}
	}

	/** The game's session as [ClientApiAuth] needs it. All reads are cheap + thread-safe. */
	private class MinecraftGameSession : ClientApiAuth.GameSession {

		override val hasOnlineSession: Boolean
			get() {
				val client = Minecraft.getInstance() ?: return false
				if (client.isOfflineDeveloperMode) {
					return false
				}
				val token = accessToken
				return token != null && token.isNotBlank() && token != "0"
			}

		override val profileId: UUID?
			get() = Minecraft.getInstance()?.user?.profileId

		override val username: String?
			get() = Minecraft.getInstance()?.user?.name

		override val accessToken: String?
			get() = Minecraft.getInstance()?.user?.accessToken

		override fun joinServer(serverId: String) {
			val client = Minecraft.getInstance() ?: throw IllegalStateException("client unavailable")
			val user = client.user
			client.services().sessionService().joinServer(user.profileId, user.accessToken, serverId)
		}
	}

	/** java.net.http transport (the game's own stack via [NetworkUtil]; no new dependency). */
	private class JavaHttpTransport : ClientApiAuth.HttpTransport {

		private val http = NetworkUtil.createHttpClient()

		override fun post(url: String, body: String, bearerToken: String?): ClientApiAuth.HttpResult =
			exchange(
				HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(10))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body)),
				bearerToken,
			)

		override fun get(url: String, bearerToken: String?): ClientApiAuth.HttpResult =
			exchange(
				HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(10))
					.GET(),
				bearerToken,
			)

		/** The bearer token is set as a header and never logged or embedded in errors. */
		private fun exchange(builder: HttpRequest.Builder, bearerToken: String?): ClientApiAuth.HttpResult {
			if (bearerToken != null) {
				builder.header("Authorization", "Bearer $bearerToken")
			}
			return try {
				val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
				ClientApiAuth.HttpResult.Response(response.statusCode(), response.body())
			} catch (e: Exception) {
				ClientApiAuth.HttpResult.TransportError(e.javaClass.simpleName)
			}
		}
	}
}
