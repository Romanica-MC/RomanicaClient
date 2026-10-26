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

package io.github.axolotlclient.imperium.privacy

import io.github.axolotlclient.imperium.enforcement.ChannelGuard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The client sent its hello (client and game version) and announced its imperiumclient:
 * channels to every server it joined. Off the Romanica network they must not go out.
 */
class OwnChannelsOffNetworkTest {

	/** Same shape as Fabric's CommonRegisterPayload (version(), protocol(), channels(), ctor). */
	class FakeRegister(private val version: Int, private val protocol: String, private val channels: Set<*>) {
		fun version(): Int = version
		fun protocol(): String = protocol
		fun channels(): Set<*> = channels
	}

	@Test
	fun ownChannelsAreRecognised() {
		assertTrue(ChannelGuard.isOwnChannel("imperiumclient:hello"))
		assertTrue(ChannelGuard.isOwnChannel("ImperiumClient:features"))
		assertFalse(ChannelGuard.isOwnChannel("minecraft:brand"))
		assertFalse(ChannelGuard.isOwnChannel("fabric:registry/sync"))
	}

	@Test
	fun registerAnnouncementDropsOnlyOurChannels() {
		val payload = FakeRegister(1, "play", linkedSetOf("imperiumclient:hello", "imperiumclient:features", "sodium:config", "fabric:x"))
		val rebuilt = ChannelGuard.filterFabricRegisterPayload(payload) { !ChannelGuard.isOwnChannel(it) } as FakeRegister
		assertEquals(setOf("sodium:config", "fabric:x"), rebuilt.channels())
	}

	@Test
	fun announcementOfOnlyOurChannelsIsSuppressed() {
		val payload = FakeRegister(1, "play", setOf("imperiumclient:hello"))
		assertNull(ChannelGuard.filterFabricRegisterPayload(payload) { !ChannelGuard.isOwnChannel(it) })
	}

}
