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

import io.github.axolotlclient.imperium.ImperiumClientSession;
import io.github.axolotlclient.imperium.ServerGating;
import io.github.axolotlclient.modules.autoutils.AutoEat;
import io.github.axolotlclient.modules.autoutils.AutoWalk;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Logic tests for the v2 tiered features channel (docs/server-gated-features.md), run
 * headless like {@code ImperiumProtocolGameTest}. Pins the three tiers end to end:
 *
 * <ul>
 *   <li>parse: {@code allow}/{@code limited}/{@code deny} land in their sets and
 *       {@code limits} builds token buckets sized from the rate hints;</li>
 *   <li>v1 payloads keep working (no limited tier, no budgets);</li>
 *   <li>rate budget exhaustion: tokens run out, {@code hasBudget} flips, and
 *       {@code ServerGating.enforce} locks the limited-tier module with its rate tooltip
 *       while a QoL module stays unlocked (only a deny locks those);</li>
 *   <li>replacement packets and {@code reset()} clear everything (grants are
 *       per-session-authoritative).</li>
 * </ul>
 *
 * {@code ServerGating.tier} reads the live client for the singleplayer check, so the
 * enforce assertions run on the client thread; whether a world is open varies with the
 * other gametests and singleplayer counts as granted (rule 2), so expectations are
 * derived from the actual singleplayer state.
 */
public class SessionTiersGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		GametestBookkeeping.executed(getClass().getSimpleName());
		try {
			v2ParseLandsAllThreeTiers();
			v1PayloadsKeepWorking();
			rateBudgetExhaustsAndLocks(context);
			qoLModulesOnlyLockOnDeny(context);
			replacementPacketAndResetClearEverything();
			malformedLimitsAreIgnored();
			disconnectScreenMixinLoads(context);
		} finally {
			ImperiumClientSession.getInstance().reset();
			AutoWalk.getInstance().enabled.setForceOff(false, "");
			AutoEat.getInstance().enabled.setForceOff(false, "");
		}
	}

	/**
	 * Smoke-checks the countdown carrier: a real DisconnectedScreen must init with the mixin
	 * applied (a broken inject target here would only surface on a live disconnect) and,
	 * with no server remembered, stay label-free. The label-attach branch needs a real
	 * multiplayer session and is exercised by the module's compile-checked path only.
	 */
	private void disconnectScreenMixinLoads(ClientGameTestContext context) {
		context.setScreen(() -> new net.minecraft.client.gui.screens.DisconnectedScreen(
			null, net.minecraft.network.chat.Component.empty(), net.minecraft.network.chat.Component.empty()));
		context.waitTicks(2);
		context.waitForScreen(net.minecraft.client.gui.screens.DisconnectedScreen.class);
		context.runOnClient(mc -> mc.gui.setScreen(null));
	}

	private void v2ParseLandsAllThreeTiers() {
		ImperiumClientSession session = ImperiumClientSession.getInstance();
		session.applyFeatures("{\"version\":2,"
			+ "\"allow\":[\"auto_fish\"],"
			+ "\"limited\":[\"auto_walk\",\"auto_click\"],"
			+ "\"deny\":[\"freelook\"],"
			+ "\"limits\":{\"auto_walk\":{\"per_hour\":30},\"auto_click\":{\"per_minute\":12}}}");
		try {
			check(session.isAllowed("auto_fish"), "allow id must be allowed");
			check(session.isLimited("auto_walk") && session.isLimited("auto_click"), "limited ids must be limited");
			check(session.isDenied("freelook"), "deny id must be denied");
			check(session.serverResponded(), "a v2 packet marks the server as responded");
			check(session.getBudgetTokens("auto_walk") == 30.0, "per_hour budget starts full at its capacity");
			check(session.getBudgetTokens("auto_click") == 12.0, "per_minute budget starts full at its capacity");
			check(session.getBudgetTokens("freelook") == Double.POSITIVE_INFINITY,
				"an id without a limits entry has no budget (unbounded)");
		} finally {
			session.reset();
		}
	}

	private void v1PayloadsKeepWorking() {
		ImperiumClientSession session = ImperiumClientSession.getInstance();
		try {
			session.applyFeatures("{\"version\":1,\"allow\":[\"auto_fish\"],\"deny\":[\"freelook\"]}");
			check(session.isAllowed("auto_fish"), "v1 allow must keep working");
			check(session.isDenied("freelook"), "v1 deny must keep working");
			check(!session.isLimited("auto_walk"), "v1 payload has no limited tier");
			check(session.getBudgetTokens("auto_walk") == Double.POSITIVE_INFINITY, "v1 payload has no budgets");
			check(session.tryConsumeBudget("auto_fish"), "v1 grants are not rate-budgeted");
		} finally {
			session.reset();
		}
	}

	private void rateBudgetExhaustsAndLocks(ClientGameTestContext context) {
		ImperiumClientSession session = ImperiumClientSession.getInstance();
		// per_minute 2: capacity 2, refill far too slow to matter within the test.
		session.applyFeatures("{\"version\":2,\"limited\":[\"auto_walk\"],\"limits\":{\"auto_walk\":{\"per_minute\":2}}}");
		check(session.tryConsumeBudget(ServerGating.AUTO_WALK), "first activation must spend a token");
		check(session.tryConsumeBudget(ServerGating.AUTO_WALK), "second activation must spend the last token");
		check(!session.hasBudget(ServerGating.AUTO_WALK), "budget must be exhausted after the capacity was spent");
		check(!session.tryConsumeBudget(ServerGating.AUTO_WALK), "a third activation must be refused");
		check(session.getBudgetTokens("auto_walk") < 1.0, "remaining tokens must be below one");

		context.runOnClient(client -> {
			var option = AutoWalk.getInstance().enabled;
			ServerGating.enforce(option, ServerGating.AUTO_WALK, AutoWalk.LOCK_REASON_KEY, AutoWalk.RATE_REASON_KEY);
			check(option.isForceOff(), "an exhausted limited tier must lock the module");
			check(AutoWalk.RATE_REASON_KEY.equals(option.getReason()),
				"the lock text must be the module's rate-budget tooltip");
		});

		// A replacement packet rebuilds the bucket full — the server is authoritative per send.
		session.applyFeatures("{\"version\":2,\"limited\":[\"auto_walk\"],\"limits\":{\"auto_walk\":{\"per_minute\":2}}}");
		check(session.hasBudget(ServerGating.AUTO_WALK), "a replacement features packet must refill the budget");
	}

	private void qoLModulesOnlyLockOnDeny(ClientGameTestContext context) {
		ImperiumClientSession session = ImperiumClientSession.getInstance();
		try {
			// No packet at all: QoL modules stay on (the singleplayer-style default).
			context.runOnClient(client -> {
				var option = AutoEat.getInstance().enabled;
				ServerGating.enforceQoL(option, ServerGating.AUTO_EAT, AutoEat.LOCK_REASON_KEY);
				check(!option.isForceOff(), "a QoL module must stay unlocked without any features packet");
			});

			// A v2 packet without a deny: still unlocked, tier is not GRANTED-required for QoL.
			session.applyFeatures("{\"version\":2,\"allow\":[\"auto_fish\"],\"deny\":[]}");
			context.runOnClient(client -> {
				var option = AutoEat.getInstance().enabled;
				ServerGating.enforceQoL(option, ServerGating.AUTO_EAT, AutoEat.LOCK_REASON_KEY);
				check(!option.isForceOff(), "a v2 packet without a deny must not lock a QoL module");
			});

			// A deny force-offs it, regardless of singleplayer (the safe default).
			session.applyFeatures("{\"version\":2,\"allow\":[],\"deny\":[\"auto_eat\"]}");
			context.runOnClient(client -> {
				var option = AutoEat.getInstance().enabled;
				ServerGating.enforceQoL(option, ServerGating.AUTO_EAT, AutoEat.LOCK_REASON_KEY);
				check(option.isForceOff(), "a deny must lock a QoL module");
				check(AutoEat.LOCK_REASON_KEY.equals(option.getReason()), "the QoL lock text must be preserved");
			});
		} finally {
			session.reset();
			context.runOnClient(client -> AutoEat.getInstance().enabled.setForceOff(false, ""));
		}
	}

	private void replacementPacketAndResetClearEverything() {
		ImperiumClientSession session = ImperiumClientSession.getInstance();
		session.applyFeatures("{\"version\":2,\"limited\":[\"auto_walk\"],\"limits\":{\"auto_walk\":{\"per_minute\":2}}}");
		session.applyFeatures("{\"version\":1,\"allow\":[],\"deny\":[]}");
		check(!session.isLimited("auto_walk"), "a v1 replacement packet must clear the limited tier");
		check(session.getBudgetTokens("auto_walk") == Double.POSITIVE_INFINITY,
			"a v1 replacement packet must drop the budgets");
		session.reset();
		check(!session.serverResponded(), "reset clears the server-responded marker");
		check(!session.isLimited("auto_walk") && !session.isAllowed("auto_walk"), "reset clears the tiers");
	}

	private void malformedLimitsAreIgnored() {
		ImperiumClientSession session = ImperiumClientSession.getInstance();
		try {
			session.applyFeatures("{\"version\":2,\"limited\":[\"auto_walk\"],\"limits\":"
				+ "{\"auto_walk\":{\"per_hour\":\"lots\"},\"bogus\":{\"per_hour\":5},\"zero\":{\"per_hour\":0}}}");
			check(session.isLimited("auto_walk"), "the limited tier itself must parse");
			check(session.getBudgetTokens("auto_walk") == Double.POSITIVE_INFINITY,
				"a non-numeric rate hint must not build a budget");
			check(session.getBudgetTokens("zero") == Double.POSITIVE_INFINITY,
				"a zero rate hint must not build a budget");
			check(session.hasBudget("auto_walk") && session.tryConsumeBudget("auto_walk"),
				"a limited id without a usable rate hint is unbounded");
		} finally {
			session.reset();
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
