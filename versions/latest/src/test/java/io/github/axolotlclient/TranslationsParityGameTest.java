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

package io.github.axolotlclient;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Locale parity check, run headless in the client gametest harness (no world needed). The
 * client's lang files ship spread over several jars (latest's resources plus the nested
 * common jar), and Minecraft merges every {@code assets/axolotlclient/lang/<locale>.json}
 * on the class stack at language reload — a key missing from a locale simply falls back to
 * English, but a key present ONLY in a locale renders as its raw key text. So: missing keys
 * in a locale are allowed, stale keys (not in any {@code en_us.json}) are not.
 */
public class TranslationsParityGameTest implements FabricClientGameTest {

	private static final String LANG_PATH = "assets/axolotlclient/lang/";

	// fr_fr now ships only from common (upstream's partial French); it obeys the same rule.
	private static final String[] LOCALES = {"pt_br", "de_de", "es_es", "fr_fr"};

	@Override
	public void runTest(ClientGameTestContext context) {
		Set<String> english = loadMergedKeys("en_us");
		check(!english.isEmpty(), "at least one en_us.json must be on the gametest classpath");

		for (String locale : LOCALES) {
			Set<String> keys = loadMergedKeys(locale);
			check(!keys.isEmpty(), locale + ".json must ship with the client resources");
			List<String> stale = keys.stream().filter(key -> !english.contains(key)).sorted().toList();
			check(stale.isEmpty(), locale + ".json contains keys that no en_us.json defines"
				+ " (they would render as raw key text, not English): " + stale);
		}
	}

	// The class loader enumerates every classpath entry providing the file; this is the same
	// merged view the game resolves translations against.
	private Set<String> loadMergedKeys(String locale) {
		Set<String> keys = new HashSet<>();
		try {
			Enumeration<URL> resources = getClass().getClassLoader().getResources(LANG_PATH + locale + ".json");
			while (resources.hasMoreElements()) {
				try (var reader = new InputStreamReader(resources.nextElement().openStream(), StandardCharsets.UTF_8)) {
					JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();
					keys.addAll(object.keySet());
				}
			}
		} catch (IOException e) {
			throw new AssertionError("failed to read " + locale + ".json from the classpath", e);
		}
		return keys;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
