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

package io.github.axolotlclient.imperium

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.axolotlclient.AxolotlClientCommon
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * The data push channels (clientbound) through which the ImperiumMC plugin feeds the client's
 * live HUDs. All follow the hello/features conventions: Bukkit plugin messaging, bare UTF-8
 * JSON (the shared [RAW_UTF8] codec), lenient parsing — a malformed packet is logged and
 * dropped, never a disconnect:
 *
 *  - `imperiumclient:legion` (docs/legions-tooling.md):
 *    `{"type":"full","name":..,"onlineMembers":..,"totalMembers":..,"power":..,"level":..}`,
 *    `{"type":"delta","onlineMembers":..,"power":..}` (absent fields keep the last value),
 *    `{"type":"none"}` — drives [LegionStateRegistry].
 *  - `imperiumclient:boosts`:
 *    `{"boosts":[{"id":..,"name":..,"endsAtEpochMs":..},..]}` — replace-semantics snapshot
 *    (an empty array clears), drives [BoostRegistry].
 *  - `imperiumclient:cooldowns`:
 *    `{"cooldowns":[{"id":..,"name":..,"totalMs":..,"remainingMs":..},..]}` — replace
 *    semantics, entries expire lazily, drives [ServerCooldownsRegistry] and the cooldowns HUD.
 */
object ImperiumChannels {

	private const val NAMESPACE = "imperiumclient"

	/** One channel payload: just the raw JSON; the type identifies the channel. */
	class ChannelPayload internal constructor(private val json: String, private val channel: String) :
		CustomPacketPayload {

		fun json(): String = json

		override fun type(): CustomPacketPayload.Type<*> =
			CustomPacketPayload.Type<ChannelPayload>(Identifier.fromNamespaceAndPath(NAMESPACE, channel))

		companion object {
			@JvmStatic
			fun codec(channel: String): StreamCodec<io.netty.buffer.ByteBuf, ChannelPayload> =
				ImperiumIntegration.RAW_UTF8.map(
					{ json: String -> ChannelPayload(json, channel) },
					{ obj: ChannelPayload -> obj.json() },
				)
		}
	}

	private val LEGION = CustomPacketPayload.Type<ChannelPayload>(id("legion"))
	private val BOOSTS = CustomPacketPayload.Type<ChannelPayload>(id("boosts"))
	private val COOLDOWNS = CustomPacketPayload.Type<ChannelPayload>(id("cooldowns"))

	private var initialized = false

	@Synchronized
	@JvmStatic
	fun init() {
		if (initialized) {
			return
		}
		initialized = true

		PayloadTypeRegistry.clientboundPlay().register(LEGION, ChannelPayload.codec("legion"))
		PayloadTypeRegistry.clientboundPlay().register(BOOSTS, ChannelPayload.codec("boosts"))
		PayloadTypeRegistry.clientboundPlay().register(COOLDOWNS, ChannelPayload.codec("cooldowns"))

		ClientPlayNetworking.registerGlobalReceiver(LEGION) { payload, ctx ->
			ctx.client().execute(Runnable { applyLegion(payload.json()) })
		}
		ClientPlayNetworking.registerGlobalReceiver(BOOSTS) { payload, ctx ->
			ctx.client().execute(Runnable { applyBoosts(payload.json()) })
		}
		ClientPlayNetworking.registerGlobalReceiver(COOLDOWNS) { payload, ctx ->
			ctx.client().execute(Runnable { applyCooldowns(payload.json()) })
		}

		// Session state resets with the connection (same hooks ImperiumIntegration uses).
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
			LegionStateRegistry.clear()
			BoostRegistry.clear()
			ServerCooldownsRegistry.clear()
		}

		AxolotlClientCommon.getInstance().logger.info("Romanica data channels ready (legion/boosts/cooldowns)")
	}

	private fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(NAMESPACE, path)

	private fun applyJson(json: String, what: String, body: (JsonObject) -> Unit) {
		try {
			val parsed = JsonParser.parseString(json)
			if (parsed.isJsonObject) {
				body(parsed.asJsonObject)
			}
		} catch (e: Exception) {
			AxolotlClientCommon.getInstance().logger.warn("Dropped malformed $what packet", e)
		}
	}

	private fun JsonObject.string(key: String): String? =
		get(key)?.takeIf { it.isJsonPrimitive }?.asString

	private fun JsonObject.intOr(key: String, default: Int): Int = runCatching { intOrNull(key) }.getOrNull() ?: default

	private fun JsonObject.intOrNull(key: String): Int? =
		get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

	private fun JsonObject.longOrNull(key: String): Long? =
		get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }

	/** Optional `members` username array of a full legion push; empty when absent. */
	private fun JsonObject.memberNames(): List<String> {
		val arr = get("members")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
		val names = ArrayList<String>(arr.size())
		for (element in arr) {
			element.takeIf { it.isJsonObject }?.asJsonObject?.string("username")?.let { names.add(it) }
		}
		return names
	}

	/** The legion packet's exact registry mapping (visible for tests). */
	@JvmStatic
	fun applyLegion(json: String) = applyJson(json, "legion") { obj ->
		when (obj.string("type")) {
			"full" -> {
				val name = obj.string("name") ?: return@applyJson
				LegionStateRegistry.set(
					name,
					obj.intOr("onlineMembers", 0),
					obj.intOr("totalMembers", 0),
					obj.longOrNull("power") ?: 0L,
					obj.intOr("level", 0),
				)
				// Member names ride the full push when the server includes them (the
				// plugin may add them later); absent = keep whatever the API fallback set.
				val members = obj.memberNames()
				if (members.isNotEmpty()) {
					LegionStateRegistry.setMembers(members)
				}
			}
			"delta" -> LegionStateRegistry.update(obj.intOrNull("onlineMembers"), obj.longOrNull("power"))
			"none" -> LegionStateRegistry.clear()
		}
	}

	/** The boosts packet's replace-semantics mapping (visible for tests). */
	@JvmStatic
	fun applyBoosts(json: String) = applyJson(json, "boosts") { obj ->
		val arr = obj.get("boosts")?.takeIf { it.isJsonArray }?.asJsonArray ?: return@applyJson
		val now = System.currentTimeMillis()
		// Replace semantics: everything not in the packet is gone.
		BoostRegistry.clear()
		for (element in arr) {
			if (!element.isJsonObject) continue
			val entry = element.asJsonObject
			val id = entry.string("id") ?: continue
			val name = entry.string("name") ?: id
			val endsAt = entry.longOrNull("endsAtEpochMs") ?: continue
			if (endsAt > now) {
				BoostRegistry.put(id, name, endsAt)
			}
		}
	}

	/** The cooldowns packet's mapping with lazy-expiry anchors (visible for tests). */
	@JvmStatic
	fun applyCooldowns(json: String) = applyJson(json, "cooldowns") { obj ->
		val arr = obj.get("cooldowns")?.takeIf { it.isJsonArray }?.asJsonArray ?: return@applyJson
		val now = System.currentTimeMillis()
		val entries = ArrayList<ServerCooldownsRegistry.Entry>(arr.size())
		for (element in arr) {
			if (!element.isJsonObject) continue
			val entry = element.asJsonObject
			val id = entry.string("id") ?: continue
			val name = entry.string("name") ?: id
			val remaining = entry.longOrNull("remainingMs") ?: continue
			if (remaining > 0) {
				entries.add(ServerCooldownsRegistry.Entry(id, name, entry.longOrNull("totalMs") ?: 0L, remaining))
			}
		}
		ServerCooldownsRegistry.put(entries, now)
	}

}
