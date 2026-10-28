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

package io.github.axolotlclient.imperium.changelog

import com.google.gson.JsonParser
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * One changelog post behind the title screen's "What's new" entry (issue #28).
 *
 * <p>The API's real shape (api.romanica.net/api/v2/changelog/list, curled 2026-10-28) is
 * {@code {"posts":[{title, lead, postedAt, sections:[{name, changes[]}]]}}} with no id;
 * the task contract also allows a bare array with {@code id/date/summary/links}. The
 * parser accepts both: ids are the post's own {@code id} when present, otherwise a stable
 * digest of its date and title, so the unread-dot bookkeeping survives a backend that
 * never grows an id field.</p>
 */
data class ChangelogEntry(
	val id: String,
	val title: String,
	val postedAt: String,
	val summary: String,
	val version: String?,
	val product: String?,
	val links: List<String>,
	val sections: List<Section>
) {

	data class Section(val name: String, val changes: List<String>)

	/** Epoch millis of the post's date; posts without a parseable date sort last. */
	val sortKey: Long = try {
		Instant.parse(postedAt).toEpochMilli()
	} catch (_: DateTimeParseException) {
		Long.MIN_VALUE
	}

	companion object {

		/**
		 * Parses a changelog response into newest-first entries. Never throws: a
		 * malformed or half-shaped body yields an empty list (the title screen must not
		 * crash on API errors, issue #28), and empty output sends the click-through to
		 * the website instead.
		 */
		@JvmStatic
		fun parseList(body: String): List<ChangelogEntry> {
			if (body.isBlank()) return emptyList()
			val posts = try {
				val root = JsonParser.parseString(body)
				val array = when {
					root.isJsonArray -> root.asJsonArray
					root.isJsonObject && root.asJsonObject.has("posts") && root.asJsonObject.get("posts").isJsonArray ->
						root.asJsonObject.getAsJsonArray("posts")
					else -> return emptyList()
				}
				array.mapNotNull { element ->
					if (element == null || !element.isJsonObject) null else fromObject(element.asJsonObject)
				}
			} catch (_: Exception) {
				return emptyList()
			}
			return posts.sortedWith(
				compareByDescending<ChangelogEntry> { it.sortKey }.thenBy { posts.indexOf(it) }
			)
		}

		/** Deterministic stand-in id for posts the backend gives no id of its own. */
		@JvmStatic
		fun stableId(postedAt: String, title: String): String {
			val digest = MessageDigest.getInstance("SHA-256")
				.digest("$postedAt\n$title".toByteArray(Charsets.UTF_8))
			return buildString {
				for (i in 0 until 8) append("%02x".format(digest[i].toInt() and 0xFF))
			}
		}

		private fun fromObject(post: com.google.gson.JsonObject): ChangelogEntry? {
			val title = post.string("title") ?: post.string("name") ?: return null
			val postedAt = post.string("postedAt") ?: post.string("date") ?: ""
			val id = post.string("id") ?: stableId(postedAt, title)
			val links = when {
				post.has("links") && post.get("links").isJsonArray -> post.getAsJsonArray("links")
					.mapNotNull { link ->
						when {
							link == null || !link.isJsonObject -> link?.takeIf { it.isJsonPrimitive }?.asString
							else -> link.asJsonObject.string("url")
						}
					}
				else -> emptyList()
			}
			val sections = if (post.has("sections") && post.get("sections").isJsonArray)
				post.getAsJsonArray("sections").mapNotNull { section ->
					if (section == null || !section.isJsonObject) return@mapNotNull null
					val obj = section.asJsonObject
					Section(
						obj.string("name") ?: "",
						if (obj.has("changes") && obj.get("changes").isJsonArray)
							obj.getAsJsonArray("changes").mapNotNull { change ->
								change?.takeIf { it.isJsonPrimitive }?.asString?.removePrefix("- ")
							}
						else emptyList()
					)
				}
			else emptyList()
			return ChangelogEntry(
				id = id,
				title = title,
				postedAt = postedAt,
				summary = post.string("summary") ?: post.string("lead") ?: "",
				version = post.string("version"),
				product = post.string("product"),
				links = links,
				sections = sections
			)
		}

		/** True when the player has not yet seen the newest of these entries. */
		@JvmStatic
		fun hasUnread(entries: List<ChangelogEntry>, lastSeenId: String): Boolean {
			val newest = entries.firstOrNull() ?: return false
			return newest.id != lastSeenId
		}
	}
}

private fun com.google.gson.JsonObject.string(key: String): String? {
	val value = get(key) ?: return null
	// Strings only: a number or bool where the contract wants text means the post is
	// garbage, and skipping it beats rendering "42" as a changelog title.
	return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null
}
