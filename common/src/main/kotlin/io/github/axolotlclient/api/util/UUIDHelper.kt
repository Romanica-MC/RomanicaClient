/*
 * Copyright © 2025 moehreag <moehreag@gmail.com> & Contributors
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

package io.github.axolotlclient.api.util

import io.github.axolotlclient.api.API
import io.github.axolotlclient.util.CachedAPI
import io.github.axolotlclient.util.GsonHelper
import io.github.axolotlclient.util.NetworkUtil
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

object UUIDHelper {
    // Lazy so the pure helpers (fromUndashed, toUndashed) work without the client bootstrapped.
    private val CLIENT: HttpClient by lazy { NetworkUtil.createHttpClient() }

    @JvmField
    val USERNAME_TO_UUID: CachedAPI<String, String> =
        create("https://api.mojang.com/users/profiles/minecraft/", "id", "username -> uuid")

    @JvmField
    val UUID_TO_USERNAME: CachedAPI<String, String> =
        create("https://sessionserver.mojang.com/session/minecraft/profile/", "name", "uuid -> username")

    @JvmStatic
    fun tryGetUsernameAsync(uuid: String): CompletableFuture<String> {
        return UUID_TO_USERNAME.getAsync(uuid).thenApply { s -> s.orElse(uuid) }
    }

    @JvmStatic
    fun fromUndashed(uuid: String): UUID {
        return UUID.fromString(
            uuid.trim().replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})".toRegex(), "$1-$2-$3-$4-$5")
        )
    }

    @JvmStatic
    fun ensureUuidOpt(nameOrUuid: String?): CompletableFuture<Optional<String>> {
        if (nameOrUuid == null || nameOrUuid.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty())
        }
        return try {
            CompletableFuture.completedFuture(Optional.of(API.sanitizeUUID(nameOrUuid)))
        } catch (e: IllegalArgumentException) {
            USERNAME_TO_UUID.getAsync(nameOrUuid)
        }
    }

    @JvmStatic
    fun ensureUuid(nameOrUuid: String?): CompletableFuture<String> {
        return ensureUuidOpt(nameOrUuid).thenApply { s -> s.orElse(nameOrUuid) }
    }

    @JvmStatic
    fun toUndashed(uuid: UUID): String {
        return API.sanitizeUUID(uuid.toString())
    }

    private fun create(endpoint: String, jsonKey: String, log: String): CachedAPI<String, String> {
        return CachedAPI(
            CachedAPI.APIHandler { value ->
                val uri = try {
                    URI.create(endpoint + value)
                } catch (e: Exception) {
                    return@APIHandler CompletableFuture.completedFuture(Optional.empty())
                }
                val req = uri.let { java.net.http.HttpRequest.newBuilder(it).GET().build() }

                CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenApply { res ->
                        val obj = GsonHelper.fromJson(res.body())
                        if (obj.has(jsonKey)) {
                            return@thenApply Optional.of(obj.get(jsonKey).asString)
                        }

                        if (API.getInstance().apiOptions.detailedLogging.get()) {
                            API.getInstance().logger.warn("Conversion {} failed: {}", log, obj)
                        }

                        Optional.empty()
                    }
            },
            4096, false
        )
    }
}
