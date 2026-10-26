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

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccountClientIdTest {

	@Test
	fun signsInWithRomanicasOwnAzureApp() {
		// Upstream's app (938592fc-...) is not ours; never sign players in through it.
		assertEquals("8b0c9e33-b6ce-40ec-ac30-c85a35dd8163", MSApi.CLIENT_ID)
	}

	@Test
	fun accountSavedBeforeTheSwitchMustSignInAgain() {
		// Shape of an accounts.json entry written by every release before the swap.
		val saved = JsonObject().apply {
			addProperty("uuid", "069a79f444e94726a5befca90e38aaf5")
			addProperty("name", "Notch")
			addProperty("authToken", "a".repeat(500))
			addProperty("msToken", "m")
			addProperty("refreshToken", "token-issued-to-the-old-app")
			addProperty("expiration", 0L)
		}
		val account = Account.deserialize(saved)
		assertEquals("", account.clientId)
		assertFalse(MSApi.isIssuedByThisApp(account))
		// The account itself survives the update; only its sign-in is redone.
		assertEquals("Notch", account.name)
	}

	@Test
	fun issuingAppSurvivesSaveAndLoad() {
		val account = Account("Notch", "069a79f4-44e9-4726-a5be-fca90e38aaf5", Account.OFFLINE_TOKEN)
		account.clientId = MSApi.CLIENT_ID
		val reloaded = Account.deserialize(account.serialize())
		assertEquals(MSApi.CLIENT_ID, reloaded.clientId)
		assertTrue(MSApi.isIssuedByThisApp(reloaded))
	}
}
