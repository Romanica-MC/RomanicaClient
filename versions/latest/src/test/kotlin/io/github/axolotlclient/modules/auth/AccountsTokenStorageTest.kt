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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.CompletableFuture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

/**
 * accounts.json held Microsoft refresh tokens in plain text (2026-10-25 gap review B7).
 * Saved files must not contain a readable token on Windows, and must be owner-only elsewhere.
 * Temp directories only: never the player's real config.
 */
class AccountsTokenStorageTest {

	@TempDir
	lateinit var dir: Path

	private val refresh = "M.C123_refresh-token-" + "r".repeat(200)
	private val auth = "eyJ-minecraft-access-token-" + "a".repeat(500)
	private val msa = "EwB-ms-access-token-" + "m".repeat(300)

	private inner class TestAccounts(private val file: Path) : Accounts() {
		override val accountsSaveFile: Path get() = file
		override fun login(account: Account) {}
		override fun showAccountsExpiredScreen(account: Account): CompletableFuture<Account> = CompletableFuture.completedFuture(account)
		override fun displayDeviceCode(data: DeviceFlowData) {}
	}

	private fun savedFile(): Path {
		val file = dir.resolve("accounts.json")
		val accounts = TestAccounts(file)
		accounts.addAccount(Account("Notch", "069a79f444e94726a5befca90e38aaf5", auth, Instant.now(), refresh, msa))
		accounts.save()
		return file
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	fun windowsFileHoldsNoReadableToken() {
		val text = Files.readString(savedFile())
		assertFalse(text.contains(refresh), "refresh token must not be readable in accounts.json")
		assertFalse(text.contains(auth), "access token must not be readable in accounts.json")
		assertFalse(text.contains(msa), "Microsoft token must not be readable in accounts.json")
		assertTrue(text.contains("dpapi:v1:"))
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	fun windowsSealedTokenOpensForTheSameUser() {
		val sealed = TokenVault.seal(refresh)
		assertTrue(TokenVault.isSealed(sealed))
		assertEquals(refresh, TokenVault.open(sealed))
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	fun elsewhereFileIsOwnerOnly() {
		val file = savedFile()
		assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file))
	}

	@Test
	fun plainLegacyValuesStillLoad() {
		assertEquals(refresh, TokenVault.open(refresh))
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	fun sealedValueCannotBeOpenedWithoutDpapi() {
		assertNull(TokenVault.open("dpapi:v1:AAAA"))
	}

	@Test
	fun saveLeavesNoTempFileBehind() {
		savedFile()
		assertFalse(Files.exists(dir.resolve("accounts.json.tmp")))
	}
}
