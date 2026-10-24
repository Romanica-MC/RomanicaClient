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

package io.github.axolotlclient.imperium.menu

import java.util.UUID
import io.github.axolotlclient.modules.auth.Account
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AccountHeadTest {

	private val dashed = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")

	@Test
	fun resolvesTheUndashedUuidAccountsStore() {
		// Account's convenience constructor strips the dashes, and Microsoft profiles arrive
		// undashed, so this is the format every stored account has.
		val account = Account("Notch", dashed.toString(), Account.OFFLINE_TOKEN)
		assertEquals("069a79f444e94726a5befca90e38aaf5", account.uuid)
		assertEquals(dashed, AccountHead.uuidOf(account.uuid))
	}

	@Test
	fun stillAcceptsDashedUuids() {
		assertEquals(dashed, AccountHead.uuidOf(dashed.toString()))
	}

	@Test
	fun noAccountOrGarbageGivesNull() {
		assertNull(AccountHead.uuidOf(null))
		assertNull(AccountHead.uuidOf(""))
		assertNull(AccountHead.uuidOf("not-a-uuid"))
	}
}
