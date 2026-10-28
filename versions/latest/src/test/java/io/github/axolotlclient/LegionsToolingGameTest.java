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

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.axolotlclient.imperium.LegionStateRegistry;
import io.github.axolotlclient.modules.legions.Breadcrumb;
import io.github.axolotlclient.modules.legions.BreadcrumbManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Logic tests for the Legions tooling (docs/legions-tooling.md), run headless in the client
 * gametest harness — registry and serialization only, no world needed. This pins the two
 * contracts the future {@code imperiumclient:legion} channel and the breadcrumb persistence
 * rely on: the shell setter/getter/clear semantics, and the JSON blob shape
 * BreadcrumbManager round-trips through its {@code breadcrumbs_data} option.
 */
public class LegionsToolingGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this suite is render-heavy, see ImperiumGametestSubsets
		GametestBookkeeping.executed(getClass().getSimpleName());
		try {
			legionRegistryShellSemantics();
			breadcrumbJsonRoundTrip();
			breadcrumbExpiryMath();
			breadcrumbManagerParsesAndPrunes();
		} finally {
			LegionStateRegistry.clear();
			BreadcrumbManager.getInstance().clearAll();
		}
	}

	private void legionRegistryShellSemantics() {
		check(LegionStateRegistry.get() == null, "registry starts empty (no legion -> hidden HUD)");

		LegionStateRegistry.set("Spartans", 3, 12, 4200, 5);
		LegionStateRegistry.LegionState state = LegionStateRegistry.get();
		check(state != null && state.name().equals("Spartans") && state.onlineMembers() == 3
			&& state.totalMembers() == 12 && state.power() == 4200 && state.level() == 5,
			"set must store the full snapshot");

		LegionStateRegistry.update(7, null);
		state = LegionStateRegistry.get();
		check(state != null && state.onlineMembers() == 7 && state.power() == 4200,
			"delta update patches only the carried fields");

		LegionStateRegistry.clear();
		check(LegionStateRegistry.get() == null, "clear returns to the no-legion state");

		LegionStateRegistry.update(1, 1L);
		check(LegionStateRegistry.get() == null, "update without a snapshot must not invent state");
	}

	private void breadcrumbJsonRoundTrip() {
		Breadcrumb crumb = new Breadcrumb("7", 1.5, 64.0, -3.25, "minecraft:the_nether", 1234567890L);
		JsonObject json = crumb.toJson();
		check(json.get("name").getAsString().equals("7")
			&& json.get("x").getAsDouble() == 1.5
			&& json.get("y").getAsDouble() == 64.0
			&& json.get("z").getAsDouble() == -3.25
			&& json.get("dimension").getAsString().equals("minecraft:the_nether")
			&& json.get("droppedAt").getAsLong() == 1234567890L,
			"toJson must carry every field of the persisted shape");

		Breadcrumb parsed = Breadcrumb.fromJson(JsonParser.parseString(json.toString()).getAsJsonObject());
		check(parsed.equals(crumb), "toJson/fromJson round trip must be lossless");

		Breadcrumb defaulted = Breadcrumb.fromJson(new JsonObject());
		check(defaulted.dimension().equals("minecraft:overworld") && defaulted.name().equals("?"),
			"fromJson tolerates a malformed entry with safe defaults");
	}

	private void breadcrumbExpiryMath() {
		long now = System.currentTimeMillis();
		check(!new Breadcrumb("1", 0, 0, 0, "minecraft:overworld", now - 4 * 60_000).isExpired(now,
			BreadcrumbManager.Expiry.MINUTES_5.maxAgeMs), "4-minute crumb survives a 5-minute expiry");
		check(new Breadcrumb("2", 0, 0, 0, "minecraft:overworld", now - 5 * 60_000).isExpired(now,
			BreadcrumbManager.Expiry.MINUTES_5.maxAgeMs), "5-minute crumb is expired at the boundary");
		check(!new Breadcrumb("3", 0, 0, 0, "minecraft:overworld", now - 999 * 60_000).isExpired(now,
			BreadcrumbManager.Expiry.OFF.maxAgeMs), "OFF never expires anything");
		check(BreadcrumbManager.Expiry.MINUTES_15.maxAgeMs == 15 * 60_000L
			&& BreadcrumbManager.Expiry.MINUTES_60.maxAgeMs == 60 * 60_000L,
			"expiry enum carries minute-based windows");
	}

	private void breadcrumbManagerParsesAndPrunes() {
		BreadcrumbManager manager = BreadcrumbManager.getInstance();
		// Feed the persisted blob through the exact option the config loads/saves.
		manager.getDataOption().set("[{\"name\":\"1\",\"x\":10,\"y\":20,\"z\":30,"
			+ "\"dimension\":\"minecraft:overworld\",\"droppedAt\":" + (System.currentTimeMillis() - 60_000) + "},"
			+ "{\"name\":\"2\",\"x\":0,\"y\":0,\"z\":0,\"dimension\":\"minecraft:the_nether\","
			+ "\"droppedAt\":" + (System.currentTimeMillis() - 999 * 60_000) + "}]");
		manager.loadFromOption();
		check(manager.getBreadcrumbs().size() == 2, "loadFromOption parses every entry of the blob");

		List<Breadcrumb> overworld = manager.getForDimension("minecraft:overworld",
			BreadcrumbManager.Expiry.MINUTES_5.maxAgeMs);
		check(overworld.size() == 1 && overworld.getFirst().name().equals("1"),
			"dimension filter returns only that dimension's crumbs");

		List<Breadcrumb> nether = manager.getForDimension("minecraft:the_nether",
			BreadcrumbManager.Expiry.MINUTES_5.maxAgeMs);
		check(nether.isEmpty() && manager.getBreadcrumbs().size() == 1,
			"expired crumbs are pruned globally as a side effect of the read");
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
