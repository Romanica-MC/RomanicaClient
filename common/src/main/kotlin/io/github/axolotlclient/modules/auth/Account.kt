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

package io.github.axolotlclient.modules.auth

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture

import com.google.gson.JsonObject

open class Account(
    var name: String,
    val uuid: String,
    var authToken: String,
    var expiration: Instant,
    var refreshToken: String,
    var msaToken: String
) {
    constructor(name: String, uuid: String, accessToken: String) :
        this(name, uuid.replace("-", ""), accessToken, Instant.EPOCH, "", "")

    private constructor(uuid: String, name: String, authToken: String, msaToken: String, refreshToken: String, expiration: Long) :
        this(name, uuid, authToken, Instant.ofEpochSecond(expiration), refreshToken, msaToken)

    /**
     * The Azure app that issued [refreshToken]. Refresh tokens only work with the
     * app that issued them; accounts saved before Romanica's own app (or offline
     * accounts) have none, and must sign in again rather than refresh.
     */
    var clientId: String = ""

    fun refresh(auth: MSApi): CompletableFuture<Account> {
        if (isOffline) return CompletableFuture.failedFuture(UnsupportedOperationException())
        return auth.refresh(this)
    }

    val isOffline: Boolean
        get() = authToken == OFFLINE_TOKEN || authToken.length < 400

    fun serialize(): JsonObject {
        val obj = JsonObject()
        obj.addProperty("uuid", uuid)
        obj.addProperty("name", name)
        obj.addProperty("authToken", authToken)
        obj.addProperty("msToken", msaToken)
        obj.addProperty("refreshToken", refreshToken ?: "")
        obj.addProperty("expiration", expiration?.epochSecond ?: 0)
        obj.addProperty("clientId", clientId)
        return obj
    }

    val isExpired: Boolean
        get() = expiration.isBefore(Instant.now())

    fun needsRefresh(): Boolean = Instant.now().isAfter(expiration.minus(2, ChronoUnit.HOURS))

    override fun equals(other: Any?): Boolean =
        other is Account && uuid == other.uuid

    override fun hashCode(): Int = uuid.hashCode()

    companion object {
        const val OFFLINE_TOKEN: String = "AxolotlClient/Offline"

        fun deserialize(objectNode: JsonObject): Account {
            val uuid = objectNode.get("uuid").asString
            val name = objectNode.get("name").asString
            val authToken = objectNode.get("authToken").asString
            val refreshToken = objectNode.get("refreshToken").asString
            val msaToken = if (objectNode.has("msToken")) objectNode.get("msToken").asString else ""
            val expiration = objectNode.get("expiration").asLong
            return Account(uuid, name, authToken, msaToken, refreshToken, expiration).also {
                it.clientId = if (objectNode.has("clientId")) objectNode.get("clientId").asString else ""
            }
        }
    }
}
