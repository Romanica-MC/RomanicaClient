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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-run record of the client gametests whose body actually executed, written to
 * {@code gametests-executed.txt} in the run directory. The fabric client gametest
 * framework logs nothing about which tests ran, so a suite that silently runs zero
 * tests (the #15/#24 failure mode: a broken filter, a lost entrypoint) would otherwise
 * finish with BUILD SUCCESSFUL. The CI gametest job reads this file, prints the count
 * in the job summary and fails when it is missing, zero or below the number of
 * registered tests.
 *
 * <p>Each gametest calls {@link #executed} as the first statement of its body after
 * the "ci" subset guard, so the count only ever names tests that really ran in this
 * JVM; the file is rewritten from the in-memory set on every call, so it can never
 * contain entries from a previous run sharing the same run directory.</p>
 */
public final class GametestBookkeeping {

	private static final Logger LOGGER = LoggerFactory.getLogger("axolotlclient-test");

	/** Written into the gametest run directory (the client's working directory). */
	private static final Path COUNT_FILE = Path.of("gametests-executed.txt");

	/** Sorted so the written file is stable and diff-able between runs. */
	private static final Set<String> EXECUTED = new ConcurrentSkipListSet<>();

	private GametestBookkeeping() {
	}

	/** Clears any count left behind by a previous run sharing this run directory. */
	public static void resetForNewRun() {
		EXECUTED.clear();
		try {
			Files.deleteIfExists(COUNT_FILE);
		} catch (IOException e) {
			LOGGER.warn("Could not clear the previous gametest count: {}", e.toString());
		}
	}

	/**
	 * Records one gametest whose body is about to execute and rewrites the count file,
	 * so a mid-suite crash still leaves an honest partial count for CI to fail on.
	 */
	public static void executed(String testClass) {
		EXECUTED.add(testClass);
		List<String> snapshot = new ArrayList<>(EXECUTED);
		StringBuilder out = new StringBuilder();
		for (String name : snapshot) {
			out.append(name).append('\n');
		}
		try {
			Files.writeString(COUNT_FILE, out.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.warn("Could not write the gametest count file: {}", e.toString());
		}
		LOGGER.info("[gametest-count] {} {}", snapshot.size(), testClass);
	}
}
