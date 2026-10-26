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

import java.lang.reflect.Constructor
import java.util.LinkedHashSet
import java.util.Locale

/**
 * Outgoing plugin-channel guard (docs/wave4-roadmap.md W4-C). On ImperiumMC servers only the
 * approved channel set may be registered or spoken on; third-party mod channels are silently
 * not sent, so mod fingerprints never reach the server's brand/channel review
 * (the plugin's `ClientFingerprintListener` — banned/suspicious channel lists and the
 * `minecraft:register` payload review).
 *
 * Allowlist shape (owner spec): vanilla brand/channels, the standard Fabric client channels
 * (both the `fabric:` and modern `c:` namespaces, which carry Fabric's own
 * register/unregister protocol), `lunar:`/`lunarclient:` style channels,
 * `imperiumclient:*` (our handshake), and WorldEdit CUI. Everything a third-party mod
 * would register or send — `sodium:`, `meteor:`, `hypixel:`, … — is refused
 * while scoped to our server, and untouched everywhere else.
 *
 * Kept free of Minecraft imports except through reflection so the decision logic is
 * unit-testable; the Fabric register-payload rebuild uses reflection
 * (`net.fabricmc.fabric.impl.networking.CommonRegisterPayload`) so this module never
 * hard-links Fabric networking internals. If the reflective rebuild ever fails the payload is
 * passed through unfiltered (fail-open: never break legitimate networking) and the failure is
 * reported once so it can be fixed.
 */
object ChannelGuard {

	/** The Fabric API's register/unregister payload class (channel `c:register`). */
	private const val FABRIC_REGISTER_PAYLOAD_CLASS =
		"net.fabricmc.fabric.impl.networking.CommonRegisterPayload"

	/** Namespaces always allowed on an enforced server (vanilla + standard client namespaces). */
	private val ALLOWED_NAMESPACES = setOf(
		"minecraft", // vanilla brand/register/unregister and every vanilla channel
		"fabric", // Fabric API client channels
		"c", // modern "standard client channels" namespace (c:register et al.)
		"lunar", "lunarclient", // Lunar-style client channels
		"imperiumclient" // our own handshake (hello/features) and future client channels
	)

	/** Individual non-namespaced channels allowed besides the namespaces above. */
	private val ALLOWED_CHANNELS = setOf(
		"worldedit:cui" // WorldEdit CUI protocol (docs/wecui-server.md)
	)

	@Volatile
	private var registerRebuildBroken = false

	/** True when the full channel id (`namespace:path`) may be used on our server. */
	@JvmStatic
	fun isChannelAllowed(channel: String?): Boolean {
		if (channel == null || channel.isEmpty()) {
			return false
		}
		val normalized = channel.lowercase(Locale.ROOT)
		if (ALLOWED_CHANNELS.contains(normalized)) {
			return true
		}
		val separator = normalized.indexOf(':')
		val namespace = if (separator > 0) normalized.substring(0, separator) else normalized
		return ALLOWED_NAMESPACES.contains(namespace)
	}

	/**
	 * True for our own `imperiumclient:` channels. Off the Romanica network these are never
	 * announced or sent, so other servers can't tell a player is running this client.
	 */
	@JvmStatic
	fun isOwnChannel(channel: String?): Boolean =
		channel != null && channel.lowercase(Locale.ROOT).startsWith("imperiumclient:")

	/** Filters a channel-name list (as carried by register/unregister payloads) to the allowlist. */
	@JvmStatic
	fun filterChannelNames(channels: List<String>): MutableList<String> {
		val kept = ArrayList<String>(channels.size)
		for (channel in channels) {
			if (channel != null && channel.isNotBlank() && isChannelAllowed(channel)) {
				kept.add(channel)
			}
		}
		return kept
	}

	/** True when the reflective Fabric register rebuild has failed at least once (test hook). */
	@JvmStatic
	fun isRegisterRebuildBroken(): Boolean = registerRebuildBroken

	/**
	 * Filters a Fabric `CommonRegisterPayload` (register/unregister announcement) to the
	 * allowlisted channels.
	 *
	 * @return the same instance when nothing needed filtering; a rebuilt payload when some
	 *         channels were removed; `null` when the filtered set is empty (suppress the
	 *         whole announcement); the original payload on a reflective failure (fail-open).
	 */
	@JvmStatic
	@JvmOverloads
	fun filterFabricRegisterPayload(payload: Any, keep: (String) -> Boolean = ::isChannelAllowed): Any? {
		try {
			val payloadClass = payload.javaClass
			val version = payloadClass.getMethod("version")
			val protocol = payloadClass.getMethod("protocol")
			val channels = payloadClass.getMethod("channels")
			val channelSet = channels.invoke(payload) as Set<*>

			val kept = ArrayList<Any?>(channelSet.size)
			var changed = false
			for (channel in channelSet) {
				if (keep(channel.toString())) {
					kept.add(channel)
				} else {
					changed = true
				}
			}
			if (!changed) {
				return payload
			}
			if (kept.isEmpty()) {
				return null
			}
			val constructor = payloadClass.getConstructor(Int::class.javaPrimitiveType, String::class.java, Set::class.java) as Constructor<Any>
			// Rebuild with a LinkedHashSet to keep announcement order stable.
			val rebuilt = LinkedHashSet(kept)
			return constructor.newInstance(version.invoke(payload), protocol.invoke(payload), rebuilt)
		} catch (failure: ReflectiveOperationException) {
			registerRebuildBroken = true
			return payload
		} catch (failure: LinkageError) {
			registerRebuildBroken = true
			return payload
		} catch (failure: ClassCastException) {
			registerRebuildBroken = true
			return payload
		}
	}

	/** True when the object is a Fabric register/unregister announcement payload. */
	@JvmStatic
	fun isFabricRegisterPayload(payload: Any?): Boolean =
		payload != null && payload.javaClass.name == FABRIC_REGISTER_PAYLOAD_CLASS
}
