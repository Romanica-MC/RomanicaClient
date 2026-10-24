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
import io.github.axolotlclient.api.util.UUIDHelper

internal object AccountHead {
	// Stored accounts carry undashed UUIDs, which UUID.fromString rejects: parsing them that
	// way threw on every frame and drew the default skin for UUID(0, 0) for everyone.
	fun uuidOf(raw: String?): UUID? {
		if (raw.isNullOrBlank()) return null
		return try {
			UUIDHelper.fromUndashed(raw)
		} catch (e: IllegalArgumentException) {
			null
		}
	}
}
