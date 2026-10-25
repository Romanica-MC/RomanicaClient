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

import java.io.FileNotFoundException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import java.util.function.Supplier

import com.github.mizosoft.methanol.FormBodyPublisher
import com.github.mizosoft.methanol.MediaType
import com.github.mizosoft.methanol.MultipartBodyPublisher
import com.google.common.hash.Hashing
import com.google.gson.JsonObject
import io.github.axolotlclient.AxolotlClientCommon
import io.github.axolotlclient.modules.auth.skin.Cape
import io.github.axolotlclient.modules.auth.skin.Skin
import io.github.axolotlclient.util.GsonHelper
import io.github.axolotlclient.util.JsonBuilders
import io.github.axolotlclient.util.Logger
import io.github.axolotlclient.util.NetworkUtil

// Partly oriented on In-Game-Account-Switcher by The-Fireplace, VidTu
class MSApi(private val accounts: Accounts, private val languageSupplier: Supplier<String>) {
    private val logger: Logger = AxolotlClientCommon.getInstance().logger
    private val client: HttpClient = NetworkUtil.createHttpClient()

    init {
        INSTANCE = this
    }

    fun startDeviceAuth(): CompletableFuture<Account> {
        val lang = languageSupplier.get().replace("_", "-").split("-")
        logger.debug("starting ms device auth flow")
        // https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-device-code#device-authorization-response
        val builder = HttpRequest.newBuilder()
            .POST(
                FormBodyPublisher.newBuilder()
                    .query("client_id", CLIENT_ID)
                    .query("scope", SCOPES).build()
            )
            .header("content-type", "application/x-www-form-urlencoded")
            .uri(URI.create(MS_DEVICE_CODE_LOGIN_URL + lang[0] + "-" + lang[1].uppercase(Locale.ROOT)))
        return requestJson(builder.build())
            .thenApplyAsync { obj ->
                val expiresIn = obj.get("expires_in").asInt
                val deviceCode = obj.get("device_code").asString
                val userCode = obj.get("user_code").asString
                val verificationUri = obj.get("verification_uri").asString
                val interval = obj.get("interval").asInt
                val message = obj.get("message").asString
                logger.debug("displaying device code to user")
                val data = DeviceFlowData(message, verificationUri, deviceCode, userCode, expiresIn, interval)
                accounts.displayDeviceCode(data)
                data
            }.thenComposeAsync { data ->
                logger.debug("waiting for user authorization...")
                val start = System.currentTimeMillis()
                waitLoop@ while (System.currentTimeMillis() - start < data.expiresIn * 1000L && !data.cancelled) {
                    if ((System.currentTimeMillis() - start) % data.interval == 0L) {
                        val requestBuilder = HttpRequest.newBuilder().POST(
                            FormBodyPublisher.newBuilder().query("client_id", CLIENT_ID)
                                .query("device_code", data.deviceCode)
                                .query("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                                .build()
                        )
                            .uri(URI.create(MS_TOKEN_LOGIN_URL))
                        val response = requestJson(requestBuilder.build()).join()

                        if (response.has("refresh_token") && response.has("access_token")) {
                            data.setStatus("auth.working")
                            return@thenComposeAsync authenticateFromMSTokens(
                                response.get("access_token").asString,
                                response.get("refresh_token").asString
                            ).thenApply { a ->
                                val index = accounts.accounts.indexOf(a)
                                val loginAccount: Account
                                if (index == -1) {
                                    accounts.accounts.add(a)
                                    loginAccount = a
                                } else {
                                    val prev = accounts.accounts[index]
                                    prev.authToken = a.authToken
                                    prev.expiration = a.expiration
                                    prev.msaToken = a.msaToken
                                    prev.name = a.name
                                    prev.refreshToken = a.refreshToken
                                    prev.clientId = a.clientId
                                    loginAccount = prev
                                }
                                accounts.login(loginAccount)
                                accounts.save()
                                data.setStatus("auth.finished")
                                loginAccount
                            }
                        }

                        if (response.has("error")) {
                            when (response.get("error").asString) {
                                "authorization_pending" -> continue@waitLoop
                                "bad_verification_code" -> throw IllegalStateException("Bad verification code! $response")
                                else -> {}
                            }
                        }
                    }
                }
                CompletableFuture.failedStage(TimeoutException())
            }
    }

    private fun authenticateFromMSTokens(accessToken: String, refreshToken: String): CompletableFuture<Account> =
        CompletableFuture.supplyAsync {
            logger.debug("getting xbl token... ")
            val xbl = authXbl(accessToken).join()
            logger.debug("getting xsts token...")
            val xsts = authXstsMC(xbl.token).join()
            logger.debug("getting mc auth token...")
            val mc = authMC(xsts.displayClaims.uhs, xsts.token).join()

            val profileJson = getMCProfile(mc.accessToken).join()
            if (profileJson.has("error") && "NOT_FOUND" == profileJson.get("error").asString) {
                AxolotlClientCommon.getInstance().notificationProvider
                    .addStatus("auth.notif.login.failed", "auth.notif.login.failed.no_profile")
                throw IllegalStateException()
            }
            logger.debug("retrieving entitlements...")
            if (!checkOwnership(mc.accessToken).join()) {
                AxolotlClientCommon.getInstance().notificationProvider
                    .addStatus("auth.notif.login.failed", "auth.notif.login.failed.no_entitlement")
                logger.warn("Failed to check for game ownership!")
                throw IllegalStateException()
            }
            logger.debug("getting profile...")
            val profile = MCProfile.get(profileJson)
            Account(profile.name, profile.id, mc.accessToken, mc.expiration, refreshToken, accessToken)
                .also { it.clientId = CLIENT_ID }
        }

    @JvmRecord
    data class MCProfile(val id: String, val name: String, val skins: List<OnlineSkin>, val capes: List<OnlineCape>) {
        class OnlineSkin(
            private val id: String,
            private val state: String,
            private val url: String,
            var classicVariant: Boolean,
            private val image: ByteArray,
            private val textureKey: String
        ) : Skin.Online {
            constructor(id: String, state: String, url: String, variant: String, image: ByteArray, textureKey: String) :
                this(id, state, url, VARIANT_CLASSIC == variant, image, textureKey)

            override fun classicVariant(): Boolean = classicVariant

            override fun classicVariant(classic: Boolean) {
                this.classicVariant = classic
            }

            override fun active(): Boolean = STATE_ACTIVE == state

            override fun equip(api: MSApi, account: Account): CompletableFuture<MCProfile> =
                api.setSkin(account, this)

            override fun supportsDownload(): Boolean = true

            fun id(): String = id

            fun state(): String = state

            override fun url(): String = url

            override fun sha256(): String = textureKey

            override fun image(): ByteArray = image

            override fun toString(): String =
                "OnlineSkin(id=$id, state=$state, url=$url, classicVariant=$classicVariant, image=${image.contentToString()}, textureKey=$textureKey)"

            companion object {
                const val VARIANT_CLASSIC = "CLASSIC"
                const val VARIANT_SLIM = "SLIM"
                const val STATE_ACTIVE = "ACTIVE"

                @JvmStatic
                fun get(objectNode: JsonObject): CompletableFuture<OnlineSkin> {
                    val url = objectNode.get("url").asString
                    return INSTANCE!!.client.sendAsync(
                        HttpRequest.newBuilder(URI.create(url)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray()
                    )
                        .thenApplyAsync { res ->
                            if (res.statusCode() == 200) {
                                res.body()
                            } else {
                                throw IllegalArgumentException("abnormal status: " + res.statusCode())
                            }
                        }.thenApply { bytes ->
                            OnlineSkin(
                                objectNode.get("id").asString,
                                objectNode.get("state").asString,
                                url,
                                objectNode.get("variant").asString, bytes,
                                Hashing.sha256().hashBytes(bytes).toString()
                            )
                        }
                }
            }
        }

        data class OnlineCape(
            private val capeId: String,
            private val state: String,
            private val capeUrl: String,
            private val capeAlias: String,
            private val capeImage: ByteArray,
            private val textureKey: String
        ) : Cape {
            override fun id(): String = capeId

            override fun alias(): String = capeAlias

            fun state(): String = state

            override fun url(): String = capeUrl

            override fun image(): ByteArray = capeImage

            override fun sha256(): String = textureKey

            override fun active(): Boolean = STATE_ACTIVE == state

            override fun equip(api: MSApi, account: Account): CompletableFuture<MCProfile> =
                api.showCape(account, this)

            companion object {
                const val STATE_ACTIVE = "ACTIVE"

                @JvmStatic
                fun get(objectNode: JsonObject): CompletableFuture<OnlineCape> {
                    val url = objectNode.get("url").asString
                    return INSTANCE!!.client.sendAsync(
                        HttpRequest.newBuilder(URI.create(url)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray()
                    )
                        .thenApplyAsync { res ->
                            if (res.statusCode() == 200) {
                                res.body()
                            } else {
                                throw IllegalArgumentException("abnormal status: " + res.statusCode())
                            }
                        }.thenApply { bytes ->
                            OnlineCape(
                                objectNode.get("id").asString, objectNode.get("state").asString,
                                url, objectNode.get("alias").asString, bytes,
                                Hashing.sha256().hashBytes(bytes).toString()
                            )
                        }
                }
            }
        }

        companion object {

            @JvmStatic
            fun get(json: JsonObject): MCProfile {
                val skinList = GsonHelper.jsonArrayToStream(json.getAsJsonArray("skins"))
                    .map { s -> OnlineSkin.get(s.asJsonObject) }
                    .toList()
                val capesList = GsonHelper.jsonArrayToStream(json.getAsJsonArray("capes"))
                    .map { s -> OnlineCape.get(s.asJsonObject) }
                    .toList()
                CompletableFuture.allOf(*skinList.toTypedArray())
                CompletableFuture.allOf(*capesList.toTypedArray())
                return MCProfile(
                    json.get("id").asString, json.get("name").asString,
                    skinList.map { it.join() }, capesList.map { it.join() }
                )
            }
        }
    }

    private fun authXbl(code: String): CompletableFuture<XblData> {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(XBL_AUTH_URL))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    JsonBuilders.JsonObject.create()
                        .field(
                            "Properties", JsonBuilders.JsonObject.create()
                                .field("AuthMethod", "RPS")
                                .field("SiteName", "user.auth.xboxlive.com")
                                .field("RpsTicket", "d=$code")
                        )
                        .field("RelyingParty", "http://auth.xboxlive.com")
                        .field("TokenType", "JWT").asString()
                )
            )
            .header("content-type", "application/json")
            .header("accept", "application/json")

        return requestJson(requestBuilder.build()).thenApply { response ->
            XblData(
                Instant.parse(response.get("IssueInstant").asString),
                Instant.parse(response.get("NotAfter").asString),
                response.get("Token").asString,
                XblData.DisplayClaims(
                    response.get("DisplayClaims").asJsonObject.get("xui").asJsonArray[0].asJsonObject.get("uhs").asString
                )
            )
        }
    }

    private data class XblData(val issueInstant: Instant, val notAfter: Instant, val token: String, val displayClaims: DisplayClaims) {
        data class DisplayClaims(val uhs: String)
    }

    private fun authXstsMC(xblToken: String): CompletableFuture<XblData> {
        val body = JsonBuilders.JsonObject.create()
            .field(
                "Properties", JsonBuilders.JsonObject.create()
                    .field("SandboxId", "RETAIL")
                    .field("UserTokens", JsonBuilders.JsonArray.create().field(xblToken))
            )
            .field("RelyingParty", "rp://api.minecraftservices.com/")
            .field("TokenType", "JWT")
        return requestJson(
            HttpRequest.newBuilder().POST(HttpRequest.BodyPublishers.ofString(body.asString())).uri(URI.create(XBL_XSTS_AUTH_URL)).build()
        )
            .thenApply { response ->
                XblData(
                    Instant.parse(response.get("IssueInstant").asString),
                    Instant.parse(response.get("NotAfter").asString),
                    response.get("Token").asString,
                    XblData.DisplayClaims(
                        response.get("DisplayClaims").asJsonObject.get("xui").asJsonArray[0].asJsonObject.get("uhs").asString
                    )
                )
            }
    }

    private fun authMC(userhash: String, xsts: String): CompletableFuture<MCXblData> {
        val body = JsonBuilders.JsonObject.create().field("identityToken", "XBL3.0 x=$userhash;$xsts")
        return requestJson(
            HttpRequest.newBuilder(URI.create(MC_LOGIN_WITH_XBOX_URL)).POST(HttpRequest.BodyPublishers.ofString(body.asString())).build()
        )
            .thenApply { response ->
                MCXblData(
                    response.get("username").asString,
                    response.get("access_token").asString,
                    Instant.now().plus(response.get("expires_in").asLong, ChronoUnit.SECONDS)
                )
            }
    }

    private data class MCXblData(val username: String, val accessToken: String, val expiration: Instant)

    private fun checkOwnership(accessToken: String): CompletableFuture<Boolean> =
        requestJson(
            HttpRequest
                .newBuilder(URI.create("https://api.minecraftservices.com/entitlements/mcstore"))
                .header("Authorization", "Bearer $accessToken").build()
        )
            .thenApply { res ->
                GsonHelper.jsonArrayToStream(res.get("items").asJsonArray)
                    .anyMatch { e ->
                        e.isJsonObject && e.asJsonObject.has("name") &&
                            "game_minecraft" == e.asJsonObject.get("name").asString
                    }
            }

    private fun getMCProfile(accessToken: String): CompletableFuture<JsonObject> =
        requestJson(
            HttpRequest.newBuilder().GET()
                .uri(URI.create("https://api.minecraftservices.com/minecraft/profile"))
                .header("Authorization", "Bearer $accessToken").build()
        )

    fun refresh(account: Account): CompletableFuture<Account> {
        // A refresh token from another Azure app (accounts saved before the switch
        // to Romanica's app) can't be refreshed here: ask the player to sign in
        // again instead of sending it and surfacing an "unexpected response".
        if (!isIssuedByThisApp(account)) {
            logger.info("Account ${account.name} was signed in with a previous app, asking to sign in again.")
            return accounts.showAccountsExpiredScreen(account)
        }
        logger.debug("refreshing auth code...")
        return requestJson(
            HttpRequest
                .newBuilder(URI.create(MS_TOKEN_LOGIN_URL))
                .POST(
                    FormBodyPublisher.newBuilder()
                        .query("client_id", CLIENT_ID)
                        .query("refresh_token", account.refreshToken)
                        .query("scope", SCOPES)
                        .query("grant_type", "refresh_token").build()
                )
                .header("Accept", "application/json").build()
        )
            .thenCompose { response ->
                if (response.has("error_codes")) {
                    val errorCode = response.get("error_codes").asJsonArray[0].asInt
                    if (errorCode == 70000 || errorCode == 70012) {
                        return@thenCompose accounts.showAccountsExpiredScreen(account)
                    } else {
                        logger.warn("Login error, unexpected response: $response")
                        AxolotlClientCommon.getInstance().notificationProvider
                            .addStatus("auth.notif.refresh.error", "auth.notif.refresh.error.unexpected_response")
                        throw IllegalArgumentException()
                    }
                }
                logger.debug("authenticating...")
                authenticateFromMSTokens(
                    response.get("access_token").asString,
                    response.get("refresh_token").asString
                ).thenApply { refreshed ->
                    account.refreshToken = refreshed.refreshToken
                    account.authToken = refreshed.authToken
                    account.name = refreshed.name
                    account.msaToken = refreshed.msaToken
                    account.expiration = refreshed.expiration
                    account.clientId = refreshed.clientId
                    accounts.save()
                    account
                }
            }
    }

    private fun requestJson(request: HttpRequest): CompletableFuture<JsonObject> =
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { res -> GsonHelper.fromJson(res.body()) }

    fun getProfile(account: Account): CompletableFuture<MCProfile> =
        getMCProfile(account.authToken).thenApply { this.extractProfile(it) }

    @JvmRecord
    data class SkinBundle(
        val name: String,
        val id: String,
        val skin: CompletableFuture<ByteArray>,
        val skinKey: String,
        val classicModel: Boolean
    )

    fun getTextures(uuid: String): CompletableFuture<SkinBundle?> =
        requestJson(
            HttpRequest.newBuilder().GET()
                .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/$uuid"))
                .build()
        ).thenApply { json ->
            val name = json.get("name").asString
            val id = json.get("id").asString
            val properties = json.get("properties").asJsonArray
            for (e in properties) {
                if (e.isJsonObject) {
                    val obj = e.asJsonObject
                    if (obj.has("name") && "textures" == obj.get("name").asString) {
                        val b64 = obj.get("value").asString
                        val props = GsonHelper.fromJson(String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8))
                        val textures = props.get("textures").asJsonObject
                        if (textures.has("SKIN")) {
                            val skinObj = textures.get("SKIN").asJsonObject
                            val skinUrl = skinObj.get("url").asString
                            val skin = client.sendAsync(
                                HttpRequest.newBuilder().uri(URI.create(skinUrl)).GET().build(),
                                HttpResponse.BodyHandlers.ofByteArray()
                            )
                                .thenApply { obj: HttpResponse<ByteArray> -> obj.body() }
                            val skinKey = skinUrl.substring(skinUrl.lastIndexOf("/") + 1)
                            var classicModel = true
                            if (skinObj.has("metadata")) {
                                val metadata = skinObj.get("metadata").asJsonObject
                                val model = metadata.get("model").asString
                                classicModel = MCProfile.OnlineSkin.VARIANT_CLASSIC.lowercase(Locale.ROOT) == model.lowercase(
                                    Locale.ROOT
                                )
                            }
                            return@thenApply SkinBundle(name, id, skin, skinKey, classicModel)
                        }
                    }
                }
            }
            null
        }

    private fun extractProfile(profileJson: JsonObject): MCProfile {
        if (profileJson.has("error") && "NOT_FOUND" == profileJson.get("error").asString) {
            throw IllegalStateException("profile not found")
        }
        if (!profileJson.has("id")) {
            logger.warn("Unexpected profile response: {}", profileJson)
            throw IllegalStateException("unexpected error")
        }
        return MCProfile.get(profileJson)
    }

    fun setSkin(account: Account, skin: Skin.Online): CompletableFuture<MCProfile> =
        setSkin(account, skin.classicVariant(), skin.url())

    fun setSkin(account: Account, wide: Boolean, url: String): CompletableFuture<MCProfile> =
        requestJson(
            HttpRequest.newBuilder()
                .uri(URI.create("https://api.minecraftservices.com/minecraft/profile/skins"))
                .header("Authorization", "Bearer " + account.authToken)
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        JsonBuilders.JsonObject.create()
                            .field(
                                "variant",
                                if (wide) MCProfile.OnlineSkin.VARIANT_CLASSIC else MCProfile.OnlineSkin.VARIANT_SLIM
                            )
                            .field("url", url).asString()
                    )
                ).build()
        )
            .thenApply { this.extractProfile(it) }

    fun uploadAndSetSkin(account: Account, wide: Boolean, image: ByteArray): CompletableFuture<MCProfile> =
        uploadAndSetSkin(
            account, MultipartBodyPublisher.newBuilder()
                .textPart("variant", if (wide) "classic" else "slim")
                .formPart("file", HttpRequest.BodyPublishers.ofByteArray(image)).build()
        )

    fun uploadAndSetSkin(account: Account, skin: Skin.LocalSkin): CompletableFuture<MCProfile> =
        try {
            uploadAndSetSkin(
                account, MultipartBodyPublisher.newBuilder()
                    .textPart("variant", if (skin.classicVariant()) "classic" else "slim")
                    .filePart("file", skin.file(), MediaType.IMAGE_PNG).build()
            )
        } catch (e: FileNotFoundException) {
            throw RuntimeException(e)
        }

    private fun uploadAndSetSkin(account: Account, publisher: MultipartBodyPublisher): CompletableFuture<MCProfile> =
        requestJson(
            HttpRequest.newBuilder()
                .uri(URI.create("https://api.minecraftservices.com/minecraft/profile/skins"))
                .header("Authorization", "Bearer " + account.authToken)
                .POST(publisher).build()
        )
            .thenApply { this.extractProfile(it) }

    fun resetSkin(account: Account): CompletableFuture<MCProfile> =
        requestJson(
            HttpRequest.newBuilder().DELETE()
                .uri(URI.create("https://api.minecraftservices.com/minecraft/profile/skins/active"))
                .header("Authorization", "Bearer " + account.authToken)
                .build()
        )
            .thenApply { this.extractProfile(it) }

    fun hideCape(account: Account): CompletableFuture<MCProfile> =
        requestJson(
            HttpRequest.newBuilder().DELETE()
                .uri(URI.create("https://api.minecraftservices.com/minecraft/profile/capes/active"))
                .header("Authorization", "Bearer " + account.authToken)
                .build()
        )
            .thenApply { this.extractProfile(it) }

    fun showCape(account: Account, cape: MCProfile.OnlineCape): CompletableFuture<MCProfile> =
        requestJson(
            HttpRequest.newBuilder()
                .uri(URI.create("https://api.minecraftservices.com/minecraft/profile/capes/active"))
                .header("Authorization", "Bearer " + account.authToken)
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        JsonBuilders.JsonObject.create().field("capeId", cape.id()).asString()
                    )
                ).build()
        )
            .thenApply { this.extractProfile(it) }

    companion object {
        // Romanica's own Azure app (device-code sign-in enabled). Never another client's ID.
        const val CLIENT_ID = "8b0c9e33-b6ce-40ec-ac30-c85a35dd8163"

        @JvmStatic
        fun isIssuedByThisApp(account: Account): Boolean = account.clientId == CLIENT_ID

        private const val SCOPES = "XboxLive.signin offline_access"
        private const val XBL_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate"
        private const val MS_DEVICE_CODE_LOGIN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode?mkt="
        private const val MS_TOKEN_LOGIN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
        private const val XBL_XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
        private const val MC_LOGIN_WITH_XBOX_URL = "https://api.minecraftservices.com/authentication/login_with_xbox"

        @JvmField
        var INSTANCE: MSApi? = null
    }
}
