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

import java.nio.file.Files
import java.nio.file.Path

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * The client told every server it joined that it was "ImperiumClient", which singles
 * players out on other servers (decision 2026-10, plan section 11: report plain Fabric).
 * Nothing on the Romanica server reads the brand; it uses the imperiumclient: channels.
 */
class ClientBrandTest {

	@Test
	fun brandIsNotRewritten() {
		val mixins = Files.readString(Path.of("src/main/resources/axolotlclient.mixins.json"))
		assertFalse(mixins.contains("ClientBrandRetrieverMixin"), "the client brand must stay the loader's own (fabric)")
		assertFalse(Files.exists(Path.of("src/main/java/io/github/axolotlclient/mixin/ClientBrandRetrieverMixin.java")))
	}
}
