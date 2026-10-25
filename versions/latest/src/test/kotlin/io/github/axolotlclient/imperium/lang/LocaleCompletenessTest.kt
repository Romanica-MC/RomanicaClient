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

package io.github.axolotlclient.imperium.lang

import java.io.File
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Every locale this module ships must translate every key of its en_us.json. A partial file
 * makes a language look supported while a third of the menu falls back to English.
 */
class LocaleCompletenessTest {

	private val langDir = dir("romanica.langDir")
	private val commonLangDir = dir("romanica.commonLangDir")

	private val english = load(File(langDir, "en_us.json"))
	private val locales = langDir.listFiles { f -> f.name.endsWith(".json") && !f.name.startsWith("en_us") }!!
		.sortedBy { it.name }

	@Test
	fun atLeastOneLocaleShips() {
		assertTrue(english.size() > 0, "en_us.json is empty")
		assertTrue(locales.isNotEmpty(), "no locale files found in $langDir")
	}

	@Test
	fun everyLocaleHasEveryEnglishKey() {
		val problems = locales.mapNotNull { file ->
			val keys = load(file).keySet()
			val missing = english.keySet().filter { it !in keys }
			if (missing.isEmpty()) null else "${file.name} is missing ${missing.size} of ${english.size()} keys: $missing"
		}
		if (problems.isNotEmpty()) fail<Unit>(problems.joinToString("\n"))
	}

	@Test
	fun noLocaleKeyIsUnknownToEnglish() {
		// The game merges this module's lang files with common's, so a key may come from either.
		val known = english.keySet() + load(File(commonLangDir, "en_us.json")).keySet()
		val problems = locales.mapNotNull { file ->
			val stale = load(file).keySet().filter { it !in known }
			if (stale.isEmpty()) null else "${file.name} has keys no en_us.json defines (they render as raw keys): $stale"
		}
		if (problems.isNotEmpty()) fail<Unit>(problems.joinToString("\n"))
	}

	@Test
	fun translationsKeepPlaceholdersAndLineBreaks() {
		val problems = mutableListOf<String>()
		for (file in locales) {
			val locale = load(file)
			for (key in english.keySet()) {
				if (!locale.has(key)) continue
				val en = english.get(key).asString
				val tr = locale.get(key).asString
				if (tokens(en) != tokens(tr)) problems += "${file.name} $key: ${tokens(en)} vs ${tokens(tr)}"
			}
		}
		if (problems.isNotEmpty()) fail<Unit>(problems.joinToString("\n"))
	}

	private fun tokens(s: String): Map<String, Int> =
		TOKEN.findAll(s).groupingBy { it.value }.eachCount()

	private companion object {
		val TOKEN = Regex("""%(\d+\$)?[sd]|<br>""")

		fun dir(property: String): File {
			val path = System.getProperty(property) ?: error("$property is not set; run through Gradle")
			return File(path).also { check(it.isDirectory) { "$property=$path is not a directory" } }
		}

		fun load(file: File): JsonObject = file.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
	}
}
