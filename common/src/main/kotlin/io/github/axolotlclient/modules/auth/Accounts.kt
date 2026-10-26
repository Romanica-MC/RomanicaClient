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

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.github.axolotlclient.AxolotlClientCommon
import io.github.axolotlclient.AxolotlClientConfig.api.options.OptionCategory
import io.github.axolotlclient.util.GsonHelper
import io.github.axolotlclient.util.Logger

abstract class Accounts {
    val category: OptionCategory = OptionCategory.create("auth")

    val accounts: MutableList<Account> = ArrayList()
    private var current: Account? = null

    fun getCurrent(): Account? = current

    fun setCurrent(account: Account?) {
        current = account
    }

    fun load() {
        val legacy = AxolotlClientCommon.resolveConfigFile("../accounts.json")
        val saveFile = accountsSaveFile
        if (Files.exists(legacy)) {
            try {
                if (Files.exists(saveFile)) {
                    Files.move(legacy, legacy.resolveSibling("accounts.json.old"))
                    logger.info("Renaming legacy accounts save file since new one exists already.")
                } else {
                    Files.createDirectories(saveFile.parent)
                    Files.move(legacy, saveFile)
                }
            } catch (e: IOException) {
                logger.warn("Failed to move legacy accounts file to new location", e)
            }
        }
        if (Files.exists(saveFile)) {
            var foundPlainTokens = false
            try {
                val list = Files.newBufferedReader(saveFile).use { GsonHelper.GSON.fromJson(it, JsonObject::class.java) }
                if (list != null) {
                    list.get("accounts").asJsonArray.forEach { jsonElement ->
                        val obj = jsonElement.asJsonObject
                        if (TOKEN_FIELDS.any { obj.has(it) && obj.get(it).asString.isNotEmpty() && !TokenVault.isSealed(obj.get(it).asString) }) {
                            foundPlainTokens = true
                        }
                        if (openTokens(obj)) {
                            accounts.add(Account.deserialize(obj))
                        } else {
                            logger.warn("Skipping a saved account whose sign-in can't be decrypted on this PC/Windows user; sign in again to add it back.")
                        }
                    }
                }
            } catch (e: IOException) {
                logger.warn("Failed to load accounts file!", e)
            }
            // Migrate files written before tokens were encrypted, and remove the legacy copy,
            // which still holds them in plain text.
            if (foundPlainTokens && TokenVault.isWindows) save()
            restrictToOwner(saveFile)
            deleteLegacyCopy(legacy.resolveSibling("accounts.json.old"))
        } else {
            try {
                Files.createDirectories(accountsSaveFile.parent)
                Files.createFile(accountsSaveFile)
            } catch (e: IOException) {
                logger.warn("Failed to create accounts file", e)
            }
        }
    }

    protected open val accountsSaveFile: Path
        get() = AxolotlClientCommon.resolveConfigFile("accounts.json")

    fun addAccount(account: Account) {
        accounts.add(account)
    }

    abstract fun login(account: Account)

    fun removeAccount(account: Account) {
        accounts.remove(account)
        save()
    }

    fun save() {
        val array = JsonArray()
        accounts.forEach { account -> array.add(sealTokens(account.serialize())) }
        val obj = JsonObject()
        obj.add("accounts", array)
        try {
            val file = accountsSaveFile
            Files.createDirectories(file.parent)
            // Write a sibling temp file and swap it in, so a crash mid-write never leaves a
            // half-written accounts file.
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, GsonHelper.GSON.toJson(obj))
            restrictToOwner(tmp)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            logger.error("Failed to save acounts config!", e)
        }
    }

    private fun sealTokens(obj: JsonObject): JsonObject {
        TOKEN_FIELDS.forEach { field ->
            if (obj.has(field)) obj.addProperty(field, TokenVault.seal(obj.get(field).asString))
        }
        return obj
    }

    /** Opens sealed tokens in place; false when one can't be opened on this PC/user. */
    private fun openTokens(obj: JsonObject): Boolean {
        TOKEN_FIELDS.forEach { field ->
            if (obj.has(field)) {
                val opened = TokenVault.open(obj.get(field).asString) ?: return false
                obj.addProperty(field, opened)
            }
        }
        return true
    }

    // Where the OS supports it, only the file's owner may read it (the protection on
    // systems without DPAPI, and a second layer on top of it elsewhere).
    private fun restrictToOwner(file: Path) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        } catch (e: UnsupportedOperationException) {
            // Windows: no POSIX permissions; the user profile folder and DPAPI protect the file.
        } catch (e: IOException) {
            logger.warn("Could not restrict accounts file permissions", e)
        }
    }

    private fun deleteLegacyCopy(file: Path) {
        if (!Files.exists(file)) return
        try {
            // Overwrite before deleting so the old tokens don't linger in the file's blocks.
            Files.write(file, ByteArray(Files.size(file).toInt()))
            Files.delete(file)
            logger.info("Removed the legacy accounts.json.old copy (it held sign-in tokens in plain text).")
        } catch (e: IOException) {
            logger.warn("Could not remove legacy accounts.json.old", e)
        }
    }

    private companion object {
        val TOKEN_FIELDS = listOf("authToken", "msToken", "refreshToken")
    }

    private val logger: Logger
        get() = AxolotlClientCommon.getInstance().logger

    protected fun isContained(uuid: String): Boolean =
        accounts.any { account -> account.uuid == uuid }

    fun allowOfflineAccounts(): Boolean =
        accounts.isNotEmpty() && !accounts.all { it.isOffline }

    abstract fun showAccountsExpiredScreen(account: Account): CompletableFuture<Account>

    abstract fun displayDeviceCode(data: DeviceFlowData)
}
