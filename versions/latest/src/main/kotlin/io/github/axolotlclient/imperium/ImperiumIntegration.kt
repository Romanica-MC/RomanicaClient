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

package io.github.axolotlclient.imperium

import io.github.axolotlclient.AxolotlClientCommon
import io.netty.buffer.ByteBuf
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.nio.charset.StandardCharsets

/**
 * The client half of the ImperiumMC handshake (roadmap M5 slice 1; the server half is
 * ImperiumClientIntegrationService in the ImperiumMC plugin). Two channels:
 *
 *  - `imperiumclient:hello` (serverbound, this class sends): a JSON introduction
 *    {"version":"<mod version>","features":[...]} sent right after join so the server
 *    can mark the session first-party and reply with grants immediately.
 *  - `imperiumclient:features` (clientbound, this class receives): the server's
 *    gated-module grant/deny packet per docs/server-gated-features.md. Applied to
 *    [ImperiumClientSession]; without it everything gated stays locked — the
 *    protocol's own safe default.
 *
 * Follows the FeatureDisabler channel pattern (PayloadTypeRegistry + custom payload
 * record) so it composes with the Fabric networking stack rather than raw plugin messages.
 */
object ImperiumIntegration {

	private const val NAMESPACE = "imperiumclient"
	private val HELLO = CustomPacketPayload.Type<HelloPayload>(Identifier.fromNamespaceAndPath(NAMESPACE, "hello"))
	private val FEATURES = CustomPacketPayload.Type<FeaturesPayload>(Identifier.fromNamespaceAndPath(NAMESPACE, "features"))

	private var initialized = false

	@Synchronized
	@JvmStatic
	fun init() {
		if (initialized) {
			return
		}
		initialized = true

		// Serverbound hello: the server marks the session first-party on this channel.
		PayloadTypeRegistry.serverboundPlay().register(HELLO, HelloPayload.CODEC)
		// Clientbound features: grants/denies from the server.
		PayloadTypeRegistry.clientboundPlay().register(FEATURES, FeaturesPayload.CODEC)

		ClientPlayConnectionEvents.JOIN.register { _, sender, _ ->
			ImperiumClientSession.getInstance().reset()
			sender?.sendPacket(HelloPayload(helloJson()))
		}

		ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
			ImperiumClientSession.getInstance().reset()
		}

		ClientPlayNetworking.registerGlobalReceiver(FEATURES) { payload, ctx ->
			ctx.client().execute(Runnable { ImperiumClientSession.getInstance().applyFeatures(payload.json()) })
		}

		AxolotlClientCommon.getInstance().logger.info("Romanica integration ready (hello/features channels)")
	}

	/**
	 * {"version":"0.1.0-alpha.1","game":"26.3","features":["protocol_v1"]} — the mod version
	 * (AxolotlClientCommon.VERSION, from fabric.mod.json at build time), the game version, and
	 * the feature flags the client currently implements that a server may care about.
	 */
	private fun helloJson(): String =
		"{\"version\":\"" + jsonSafe(AxolotlClientCommon.VERSION) + "\"," +
			"\"game\":\"" + jsonSafe(AxolotlClientCommon.GAME_VERSION) + "\"," +
			"\"features\":[\"protocol_v1\"]}"

	private fun jsonSafe(s: String?): String =
		if (s == null) "" else s.replace("\\", "\\\\").replace("\"", "\\\"")

	/**
	 * RAW UTF-8 stream codec for both payloads. The ImperiumMC plugin speaks Bukkit plugin
	 * messaging, which carries BARE UTF-8 JSON — no length prefix, no NBT framing. A
	 * `ByteBufCodecs.fromCodec(Codec.STRING)` reads NBT on decode and fails on bare
	 * bytes, DISCONNECTING the player (found in live testing against the plugin's exact
	 * output; see docs/dev-notes.md "Server protocol"). Encode writes the raw bytes; decode
	 * consumes the whole remaining buffer as UTF-8.
	 */
	val RAW_UTF8: StreamCodec<ByteBuf, String> = object : StreamCodec<ByteBuf, String> {
		override fun decode(buf: ByteBuf): String {
			val bytes = ByteArray(buf.readableBytes())
			buf.readBytes(bytes)
			return String(bytes, StandardCharsets.UTF_8)
		}

		override fun encode(buf: ByteBuf, value: String) {
			buf.writeBytes(value.toByteArray(StandardCharsets.UTF_8))
		}
	}

	class HelloPayload(private val json: String) : CustomPacketPayload {

		fun json(): String = json

		override fun type(): CustomPacketPayload.Type<*> = HELLO

		companion object {
			@JvmField
			val CODEC: StreamCodec<ByteBuf, HelloPayload> = RAW_UTF8.map({ HelloPayload(it) }, { it.json() })
		}
	}

	class FeaturesPayload(private val json: String) : CustomPacketPayload {

		fun json(): String = json

		override fun type(): CustomPacketPayload.Type<*> = FEATURES

		companion object {
			@JvmField
			val CODEC: StreamCodec<ByteBuf, FeaturesPayload> = RAW_UTF8.map({ FeaturesPayload(it) }, { it.json() })
		}
	}
}
