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

import java.util.Locale
import java.util.function.BooleanSupplier
import java.util.function.Consumer

import io.github.axolotlclient.AxolotlClientConfig.api.options.OptionCategory
import net.minecraft.world.item.ItemStack

/**
 * One card in the Imperium menu. `isEnabled`/`setEnabled` are null for entries that
 * have settings but no on/off state (for example the general options page).
 */
class MenuEntry(
	private val id: String,
	private val name: String,
	private val category: MenuCategory,
	val settings: OptionCategory,
	private val isEnabled: BooleanSupplier?,
	private val setEnabled: Consumer<Boolean>?
) {

	/**
	 * Icon resolved once at menu build (pass-4 hot path #3): specificIconFor walked
	 * ~80 substring checks per visible card per frame before this cache existed.
	 * MenuEntry is immutable in id/name/category, so the icon never changes.
	 */
	var cachedIcon: ItemStack? = null
		private set

	fun cacheIcon(icon: ItemStack) {
		if (cachedIcon == null) cachedIcon = icon
	}

	fun id(): String = id

	fun name(): String = name

	fun category(): MenuCategory = category

	fun settings(): OptionCategory = settings

	fun hasToggle(): Boolean = isEnabled != null && setEnabled != null

	fun enabled(): Boolean = hasToggle() && isEnabled!!.asBoolean

	fun toggle() {
		if (hasToggle()) {
			setEnabled!!.accept(!isEnabled!!.asBoolean)
		}
	}

	fun matches(query: String): Boolean {
		if (query.isBlank()) return true
		val q = query.lowercase(Locale.ROOT).trim()
		return name.lowercase(Locale.ROOT).contains(q) || id.lowercase(Locale.ROOT).contains(q)
	}

	override fun equals(other: Any?): Boolean =
		this === other || (other is MenuEntry && id == other.id && name == other.name && category == other.category
			&& settings == other.settings && isEnabled == other.isEnabled && setEnabled == other.setEnabled)

	override fun hashCode(): Int {
		var result = id.hashCode()
		result = 31 * result + name.hashCode()
		result = 31 * result + category.hashCode()
		result = 31 * result + settings.hashCode()
		result = 31 * result + (isEnabled?.hashCode() ?: 0)
		result = 31 * result + (setEnabled?.hashCode() ?: 0)
		return result
	}

	override fun toString(): String =
		"MenuEntry[id=$id, name=$name, category=$category]"
}
