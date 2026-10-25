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

package io.github.axolotlclient.imperium.enforcement

import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import io.github.axolotlclient.AxolotlClientCommon
import io.github.axolotlclient.imperium.ImperiumClientSession
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.network.protocol.game.ServerboundEditBookPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket
import net.minecraft.world.effect.MobEffects

/**
 * The local rules enforcement layer (docs/wave4-roadmap.md W4-C) — the trusted-client
 * interceptor. All enforcement happens on OUTGOING packets before the socket, at the single
 * chokepoint every client-bound-to-server packet passes through
 * (`Connection.send(Packet, ChannelFutureListener, boolean)`, wrapped by
 * `ConnectionEnforcementMixin`).
 *
 * **Scope rule (non-negotiable):** the layer is completely inert unless the
 * connection's address matches the configured allowlist (hostname suffix match, dot boundary,
 * default exactly romanica.net / play.romanica.net). In singleplayer, on LAN (memory
 * connections never match), and on every other server no packet of any kind is touched. When
 * the master toggle is off the same inertness applies everywhere — the honest fallback is a
 * normal modded client under the server's normal anticheat.
 *
 * Three enforcement functions while in scope:
 * 1. **Movement sanitization** — outgoing
 *    `ServerboundMovePlayerPacket.Pos/PosRot` deltas are validated against
 *    [MovementEnvelope] (mirroring the plugin's CheatPreventionListener /
 *    MovementSimulationCheck thresholds) and CLAMPED to the envelope, never dropped, so a
 *    cheat mod cannot make the player move illegally and a legit player never desyncs.
 *    Movement additionally requires the ImperiumMC handshake to have answered this session
 *    ([ImperiumClientSession.serverResponded]) — on any other server at that
 *    address the clamp stays off and the server's own checks apply.
 * 2. **Exploit payload refusal** — sign and book payloads matching the server's
 *    own exploit checks ([PayloadRules]) are refused: the packet is cancelled and the
 *    refusal is logged once per session per type.
 * 3. **Outgoing channel guard** — only allowlisted plugin channels may be
 *    registered or spoken on ([ChannelGuard]); third-party channels are silently not
 *    sent, so mod fingerprints never reach the server's brand/channel review.
 *
 * Threading: `Connection.send` runs on the client thread for vanilla traffic but may
 * be called from other threads by mods, so all mutable state here is either volatile, atomic,
 * or guarded; the movement-envelope state is snapshotted once per client tick
 * ([snapshotMovementState]) and read lock-free by the send path.
 */
object EnforcementService {

	/** Grace windows mirroring the plugin's CheatPreventionListener exemptions (ms). */
	private const val TELEPORT_GRACE_MS = 2_000L
	private const val VELOCITY_GRACE_MS = 1_500L

	private val hookPulse = AtomicLong()
	private val enforcedActions = AtomicLong()

	/** Per-connection scope decision (weak: connections must not leak via the cache). */
	private val scopeCache = WeakHashMap<Connection, Boolean>()

	/** One log line per refusal type per session. */
	private val sessionLogged = ConcurrentHashMap.newKeySet<String>()

	/** Timestamp of the last sign packet allowed through (0 = none this session). */
	private val lastSignAcceptedMs = AtomicLong()

	// ── Movement path state (guarded by movementLock; written from the send path) ──
	private val movementLock = Any()
	private var lastSentX = 0.0
	private var lastSentY = 0.0
	private var lastSentZ = 0.0
	private var hasLastSent = false
	private var airborneTicks = 0
	private var teleportGraceUntil = 0L
	private var velocityGraceUntil = 0L

	/** Envelope input, snapshotted on the client thread each tick; null when not in a world. */
	@Volatile
	private var movementState: MovementEnvelope.State? = null

	@Volatile
	private var initialized = false

	@JvmStatic
	fun instance(): EnforcementService = this

	/** Registers the tick snapshot + session lifecycle hooks. Idempotent. */
	@Synchronized
	fun init() {
		if (initialized) {
			return
		}
		initialized = true

		ClientTickEvents.END_CLIENT_TICK.register { client -> snapshotMovementState(client) }

		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> resetSessionState() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
			resetSessionState()
			synchronized(scopeCache) { scopeCache.clear() }
		}

		AxolotlClientCommon.getInstance().logger
			.info("Local rules enforcement layer ready (scoped to the configured Romanica allowlist)")
	}

	private fun resetSessionState() {
		sessionLogged.clear()
		lastSignAcceptedMs.set(0)
		synchronized(movementLock) {
			hasLastSent = false
			airborneTicks = 0
			teleportGraceUntil = 0
			velocityGraceUntil = 0
		}
	}

	// ── Scope ───────────────────────────────────────────────────────────────────

	/**
	 * True when this connection is an enforced connection: not a memory
	 * connection, and the remote address matches the allowlist. The decision is computed once
	 * per connection and cached; off-scope connections cost one map lookup per packet.
	 */
	fun isConnectionInScope(connection: Connection?): Boolean {
		if (connection == null || connection.isMemoryConnection) {
			return false
		}
		val cached: Boolean?
		cached = synchronized(scopeCache) { scopeCache[connection] }
		if (cached != null) {
			return cached
		}
		val allowed = computeAddressAllowed(connection)
		synchronized(scopeCache) { scopeCache.put(connection, allowed) }
		return allowed
	}

	private fun computeAddressAllowed(connection: Connection): Boolean {
		val remote: SocketAddress = connection.remoteAddress
		val socketHost = if (remote is InetSocketAddress) remote.hostString else null
		// The entered address (ServerData.ip) also carries the SRV hostname; benign off-thread
		// read of a stable field, at worst null on a race and the socket-host check above decides.
		val serverData = Minecraft.getInstance().currentServer
		return wouldBeInScope(socketHost, if (serverData == null) null else serverData.ip)
	}

	/**
	 * Pure scope decision over the two address sources a connection offers: the socket host and
	 * the entered server address. Test seam for the scope rule (no real DNS needed).
	 */
	fun wouldBeInScope(socketHost: String?, serverEntryIp: String?): Boolean {
		if (socketHost != null && isAddressAllowed(socketHost, EnforcementConfig.allowlist())) {
			return true
		}
		return serverEntryIp != null && isAddressAllowed(serverEntryIp, EnforcementConfig.allowlist())
	}

	/**
	 * Hostname suffix match on the dot boundary: `play.romanica.net` and
	 * `romanica.net` match the default list; `evilimperiummc.net` does not. Strips
	 * a `:port` suffix and IPv6 brackets, lowercases, ignores empty entries.
	 */
	@JvmStatic
	fun isAddressAllowed(rawHost: String?, allowlist: List<String>?): Boolean {
		if (rawHost == null || rawHost.isBlank() || allowlist == null || allowlist.isEmpty()) {
			return false
		}
		var host = rawHost.trim().lowercase(Locale.ROOT)
		if (host.startsWith("[")) {
			val close = host.indexOf(']')
			host = if (close > 0) host.substring(1, close) else host.substring(1)
		} else {
			val colon = host.lastIndexOf(':')
			// Strip a trailing :port only when it is the only colon (otherwise it is IPv6).
			if (colon > 0 && host.indexOf(':') == colon) {
				host = host.substring(0, colon)
			}
		}
		while (host.endsWith(".")) {
			host = host.substring(0, host.length - 1)
		}
		if (host.isEmpty()) {
			return false
		}
		for (entry in allowlist) {
			val allowed = (entry ?: "").trim().lowercase(Locale.ROOT)
			if (allowed.isEmpty()) {
				continue
			}
			if (host == allowed || host.endsWith(".$allowed")) {
				return true
			}
		}
		return false
	}

	// ── The chokepoint ──────────────────────────────────────────────────────────

	/**
	 * The single outgoing decision point, invoked by `ConnectionEnforcementMixin` for
	 * every packet on every connection.
	 *
	 * @return the packet to send (possibly a clamped replacement), or `null` to refuse the
	 *         packet (it is not sent at all).
	 */
	fun processOutgoing(connection: Connection?, packet: Packet<*>?): Packet<*>? {
		hookPulse.incrementAndGet()
		if (packet == null || !isConnectionInScope(connection)) {
			return packet
		}

		val move = packet as? ServerboundMovePlayerPacket
		if (move != null && move.hasPosition()) {
			return sanitizeMovement(move)
		}
		if (packet is ServerboundSignUpdatePacket) {
			return checkSignPacket(packet)
		}
		if (packet is ServerboundEditBookPacket) {
			return checkBookPacket(packet)
		}
		if (packet is ServerboundCustomPayloadPacket) {
			return guardCustomPayload(packet)
		}
		return packet
	}

	// ── 1. Movement sanitization ────────────────────────────────────────────────

	private fun sanitizeMovement(move: ServerboundMovePlayerPacket): Packet<*>? {
		// The movement clamp additionally requires the ImperiumMC handshake to have answered —
		// without first-party confirmation the client behaves like any other modded client.
		if (!ImperiumClientSession.getInstance().serverResponded()) {
			return move
		}
		val state = movementState ?: return move

		val x = move.getX(0.0)
		val y = move.getY(0.0)
		val z = move.getZ(0.0)

		synchronized(movementLock) {
			val now = System.currentTimeMillis()
			val inGrace = now < teleportGraceUntil || now < velocityGraceUntil
			if (!hasLastSent) {
				// First packet of the session / after a server teleport: establish the baseline.
				hasLastSent = true
				lastSentX = x
				lastSentY = y
				lastSentZ = z
				airborneTicks = if (move.isOnGround) 0 else airborneTicks + 1
				return move
			}

			val effective = if (inGrace) withGrace(state) else withAirborne(state, airborneTicks)
			val result = MovementEnvelope.clamp(x - lastSentX, y - lastSentY, z - lastSentZ, effective)

			val sentX = lastSentX + result.dx()
			val sentY = lastSentY + result.dy()
			val sentZ = lastSentZ + result.dz()
			lastSentX = sentX
			lastSentY = sentY
			lastSentZ = sentZ
			airborneTicks = if (move.isOnGround) 0 else airborneTicks + 1

			if (!result.clampedHorizontal() && !result.clampedVertical()) {
				return move
			}
			enforcedActions.incrementAndGet()
			logOnce(
				"movement_clamp",
				"clamped outgoing move delta to the movement envelope (dx={} dy={} dz={})",
				result.dx(), result.dy(), result.dz()
			)
			if (move is ServerboundMovePlayerPacket.PosRot) {
				return ServerboundMovePlayerPacket.PosRot(
					sentX, sentY, sentZ,
					move.getYRot(0.0f), move.getXRot(0.0f), move.isOnGround, move.horizontalCollision()
				)
			}
			return ServerboundMovePlayerPacket.Pos(sentX, sentY, sentZ, move.isOnGround, move.horizontalCollision())
		}
	}

	private fun withGrace(state: MovementEnvelope.State): MovementEnvelope.State =
		MovementEnvelope.State(
			state.sprinting(), state.onGround(), state.airborneTicks(),
			state.speedAmplifier(), state.jumpBoostAmplifier(), state.onIce(), state.inWater(),
			state.gliding(), state.riptiding(), state.vehicle(), state.flying(), state.levitation(),
			state.climbing(), state.onBouncy(), true
		)

	private fun withAirborne(state: MovementEnvelope.State, airborneTicks: Int): MovementEnvelope.State {
		if (state.airborneTicks() == airborneTicks) {
			return state
		}
		return MovementEnvelope.State(
			state.sprinting(), state.onGround(), airborneTicks,
			state.speedAmplifier(), state.jumpBoostAmplifier(), state.onIce(), state.inWater(),
			state.gliding(), state.riptiding(), state.vehicle(), state.flying(), state.levitation(),
			state.climbing(), state.onBouncy(), state.inGrace()
		)
	}

	/** Per-tick snapshot of the local player's movement state (client thread only). */
	private fun snapshotMovementState(client: Minecraft) {
		val player = client.player ?: run {
			movementState = null
			return
		}
		val speed = player.getEffect(MobEffects.SPEED)
		val jumpBoost = player.getEffect(MobEffects.JUMP_BOOST)
		val blockBelow = blockPath(player, -1)
		val blockAtFeet = blockPath(player, 0)

		movementState = MovementEnvelope.State(
			player.isSprinting,
			player.onGround(),
			airborneTicksSnapshot(),
			speed?.amplifier ?: -1,
			jumpBoost?.amplifier ?: -1,
			blockBelow != null && blockBelow.contains("ice"),
			player.isInWater,
			player.isFallFlying,
			player.isAutoSpinAttack,
			player.isPassenger,
			player.abilities.flying,
			player.hasEffect(MobEffects.LEVITATION),
			isClimbable(blockAtFeet) || isClimbable(blockBelow),
			blockBelow != null && (blockBelow.contains("slime") || blockBelow.contains("honey")),
			false
		)
	}

	private fun airborneTicksSnapshot(): Int = synchronized(movementLock) { airborneTicks }

	private fun blockPath(player: net.minecraft.client.player.LocalPlayer, yOffset: Int): String? {
		val state = player.level().getBlockState(player.blockPosition().offset(0, yOffset, 0))
		return BuiltInRegistries.BLOCK.getKey(state.block).path
	}

	private fun isClimbable(blockPath: String?): Boolean {
		if (blockPath == null) {
			return false
		}
		val path = blockPath
		return path.contains("ladder") || path.contains("vine") || path.contains("scaffolding")
	}

	/** Server position sync (teleport/lagback): re-seed the baseline and open the grace window. */
	fun onServerPositionSync() {
		synchronized(movementLock) {
			hasLastSent = false
			airborneTicks = 0
			teleportGraceUntil = System.currentTimeMillis() + TELEPORT_GRACE_MS
		}
	}

	/** Server-applied motion for OUR entity (knockback/explosion/piston): grace window. */
	fun onEntityMotion(entityId: Int, selfEntityId: Int) {
		if (entityId == selfEntityId) {
			synchronized(movementLock) {
				velocityGraceUntil = System.currentTimeMillis() + VELOCITY_GRACE_MS
			}
		}
	}

	// ── 2. Exploit payload refusal ──────────────────────────────────────────────

	private fun checkSignPacket(sign: ServerboundSignUpdatePacket): Packet<*>? {
		val violation = PayloadRules.signViolation(sign.lines())
		if (violation != null) {
			enforcedActions.incrementAndGet()
			logOnce(violation, "refused outgoing sign packet ({})", violation)
			return null
		}
		val now = System.currentTimeMillis()
		val last = lastSignAcceptedMs.get()
		if (!PayloadRules.signWithinRate(last, now)) {
			// Same player-visible outcome as the server's 1-per-2s cancel, without the VL entry.
			logOnce("sign_rate", "held outgoing sign packet inside the 2s sign-edit rate window")
			return null
		}
		lastSignAcceptedMs.compareAndSet(last, now)
		return sign
	}

	private fun checkBookPacket(book: ServerboundEditBookPacket): Packet<*>? {
		val violation = PayloadRules.bookViolation(book.pages(), book.title().orElse(null))
		if (violation != null) {
			enforcedActions.incrementAndGet()
			logOnce(violation, "refused outgoing book packet ({})", violation)
			return null
		}
		return book
	}

	// ── 3. Outgoing channel guard ───────────────────────────────────────────────

	private fun guardCustomPayload(packet: ServerboundCustomPayloadPacket): Packet<*>? {
		val payload: CustomPacketPayload = packet.payload()

		if (ChannelGuard.isFabricRegisterPayload(payload)) {
			val rebuilt = ChannelGuard.filterFabricRegisterPayload(payload)
			if (rebuilt == null) {
				enforcedActions.incrementAndGet()
				logOnce("channel_register_suppressed", "suppressed a plugin-channel registration with no allowed channels left")
				return null
			}
			if (rebuilt === payload) {
				if (ChannelGuard.isRegisterRebuildBroken()) {
					logOnce(
						"channel_register_filter_unavailable",
						"could not filter a channel registration payload (fabric internals changed); passing through"
					)
				}
				return packet
			}
			enforcedActions.incrementAndGet()
			logOnce("channel_register_filtered", "removed non-allowlisted channels from the registration announcement")
			return ServerboundCustomPayloadPacket(rebuilt as CustomPacketPayload)
		}

		val channel = payload.type().id().toString()
		if (!ChannelGuard.isChannelAllowed(channel)) {
			enforcedActions.incrementAndGet()
			logOnce("channel_blocked:$channel", "suppressed outgoing plugin channel {} (not on the enforcement allowlist)", channel)
			return null
		}
		return packet
	}

	// ── Logging + test hooks ────────────────────────────────────────────────────

	private fun logOnce(type: String, message: String, vararg args: Any?) {
		if (sessionLogged.add(type)) {
			AxolotlClientCommon.getInstance().logger.info("[Enforcement] $message", *args)
		}
	}

	/** Total calls into the send hook (any connection, scoped or not). Mixin-liveness evidence. */
	fun hookPulse(): Long = hookPulse.get()

	/** Times the layer actually acted on a scoped connection (clamp/refusal/suppression). */
	fun enforcedActions(): Long = enforcedActions.get()
}
