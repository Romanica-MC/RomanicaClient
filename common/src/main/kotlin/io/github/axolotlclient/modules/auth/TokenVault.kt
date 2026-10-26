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

package io.github.axolotlclient.modules.auth

import java.nio.charset.StandardCharsets
import java.util.Base64

import com.sun.jna.platform.win32.Crypt32Util

/**
 * Encrypts the sign-in tokens kept in `accounts.json`.
 *
 * Microsoft refresh tokens give access to the player's Minecraft and Xbox account, and
 * token stealers look for exactly this file. On Windows each token is sealed with DPAPI
 * (bound to the signed-in Windows user, readable by nobody else) and stored as
 * `dpapi:v1:<base64>`. Elsewhere, and if sealing fails, the value is stored as before and
 * the file is restricted to its owner instead (see [Accounts.save]).
 */
object TokenVault {
	private const val PREFIX = "dpapi:v1:"

	// Optional DPAPI entropy: a blob sealed for this purpose can't be opened as another app's secret.
	private val ENTROPY = "romanica-client/accounts.json".toByteArray(StandardCharsets.UTF_8)

	// CRYPTPROTECT_UI_FORBIDDEN: never show a Windows prompt.
	private const val UI_FORBIDDEN = 0x1

	val isWindows: Boolean = System.getProperty("os.name", "").lowercase().startsWith("windows")

	fun isSealed(value: String): Boolean = value.startsWith(PREFIX)

	/**
	 * Seals [value] on Windows. Returns the value unchanged when there is nothing to seal,
	 * on other systems, or if DPAPI is unavailable (the caller keeps working, just unsealed).
	 */
	fun seal(value: String): String {
		if (value.isEmpty() || isSealed(value) || !isWindows) return value
		return try {
			val blob = Crypt32Util.cryptProtectData(value.toByteArray(StandardCharsets.UTF_8), ENTROPY, UI_FORBIDDEN, "Romanica Client", null)
			PREFIX + Base64.getEncoder().encodeToString(blob)
		} catch (e: Throwable) {
			// No DPAPI (or no JNA platform classes): fall back to the file-permission protection.
			value
		}
	}

	/**
	 * Opens a value written by [seal]. Plain (legacy or non-Windows) values come back as they
	 * are; a sealed value that can't be opened here (another Windows user or PC) returns null.
	 */
	fun open(value: String): String? {
		if (!isSealed(value)) return value
		if (!isWindows) return null
		return try {
			val blob = Base64.getDecoder().decode(value.substring(PREFIX.length))
			String(Crypt32Util.cryptUnprotectData(blob, ENTROPY, UI_FORBIDDEN, null), StandardCharsets.UTF_8)
		} catch (e: Throwable) {
			null
		}
	}
}
