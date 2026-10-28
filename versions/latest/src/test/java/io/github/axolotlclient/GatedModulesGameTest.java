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

import io.github.axolotlclient.imperium.BoostRegistry;
import io.github.axolotlclient.imperium.ImperiumClientSession;
import io.github.axolotlclient.imperium.ServerGating;
import io.github.axolotlclient.mixin.FishingHookAccessor;
import io.github.axolotlclient.modules.enchantedmc.AutoClick;
import io.github.axolotlclient.modules.enchantedmc.AutoFish;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;

/**
 * Logic tests for the server-gated modules (docs/server-gated-features.md), run in the
 * client gametest harness. {@code ServerGating.enforce} touches the client, so the gate
 * assertions run on the client thread via {@code runOnClient}; whether the harness has a
 * singleplayer world open varies with the other gametests, and singleplayer counts as
 * granted (rule 2), so each expectation is derived from the actual singleplayer state
 * instead of assuming one.
 *
 * This pins the enforcement the whole design rests on: an ungranted module cannot be
 * enabled even by writing the option directly, a grant (or singleplayer) opens it, a deny
 * beats both, and an external block_mods ban survives a grant.
 */
public class GatedModulesGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		GametestBookkeeping.executed(getClass().getSimpleName());
		boolean singleplayer = context.computeOnClient(Minecraft::hasSingleplayerServer);
		try {
			gateLocksUngrantedModule(context, singleplayer);
			grantOpensModule(context);
			externalBanSurvivesGrant(context);
			denyBeatsGrant(context);
			biteFlagAccessorResolves();
			boostRegistryPrunesAndSorts();
			recastDelayStaysInRange();
			clickIntervalStaysInRange();
		} finally {
			ImperiumClientSession.getInstance().reset();
			AutoFish.getInstance().enabled.setForceOff(false, "");
			AutoClick.getInstance().enabled.setForceOff(false, "");
			BoostRegistry.clear();
		}
	}

	private void gateLocksUngrantedModule(ClientGameTestContext context, boolean singleplayer) {
		ImperiumClientSession.getInstance().reset();
		context.runOnClient(client -> {
			var option = AutoFish.getInstance().enabled;
			ServerGating.enforce(option, ServerGating.AUTO_FISH, AutoFish.LOCK_REASON_KEY);
			check(option.isForceOff() == !singleplayer,
				"without a features packet the gate must follow singleplayer (locked=" + !singleplayer + ")");
			if (!singleplayer) {
				check(AutoFish.LOCK_REASON_KEY.equals(option.getReason()),
					"locked option must carry the lock-reason tooltip");
			}
			option.set(true); // a user toggling the card writes the underlying value...
			check(!option.get() || singleplayer, "...but the gate must keep an ungranted module reading disabled");

			ServerGating.enforce(AutoClick.getInstance().enabled, ServerGating.AUTO_CLICK, AutoClick.LOCK_REASON_KEY);
			check(AutoClick.getInstance().enabled.isForceOff() == !singleplayer, "auto_click gates identically");
		});
	}

	private void grantOpensModule(ClientGameTestContext context) {
		ImperiumClientSession.getInstance().applyFeatures("{\"version\":1,\"allow\":[\"auto_fish\"],\"deny\":[]}");
		context.runOnClient(client -> {
			var option = AutoFish.getInstance().enabled;
			ServerGating.enforce(option, ServerGating.AUTO_FISH, AutoFish.LOCK_REASON_KEY);
			check(!option.isForceOff(), "a granted module (or singleplayer) must unlock");
			option.set(true);
			check(option.get(), "a granted module can be enabled");
		});
	}

	private void externalBanSurvivesGrant(ClientGameTestContext context) {
		ImperiumClientSession.getInstance().applyFeatures("{\"version\":1,\"allow\":[\"auto_click\"],\"deny\":[]}");
		// The block_mods channel (FeatureDisabler) force-offs with its own reason.
		AutoClick.getInstance().enabled.setForceOff(true, "ban_reason");
		context.runOnClient(client -> {
			var option = AutoClick.getInstance().enabled;
			ServerGating.enforce(option, ServerGating.AUTO_CLICK, AutoClick.LOCK_REASON_KEY);
			check(option.isForceOff(), "a block_mods ban must survive a features grant (or singleplayer)");
			check("ban_reason".equals(option.getReason()), "the ban's own reason must be preserved");
		});
	}

	private void denyBeatsGrant(ClientGameTestContext context) {
		ImperiumClientSession.getInstance().applyFeatures("{\"version\":1,\"allow\":[\"auto_fish\"],\"deny\":[\"auto_fish\"]}");
		context.runOnClient(client -> {
			var option = AutoFish.getInstance().enabled;
			ServerGating.enforce(option, ServerGating.AUTO_FISH, AutoFish.LOCK_REASON_KEY);
			check(option.isForceOff(), "deny must beat allow and singleplayer (the safe default)");
		});
	}

	/**
	 * Forces the FishingHook class to load through the mixin pipeline and reads the
	 * accessor: this validates the DATA_BITING binding auto-fish polls. A wrong target
	 * name fails the mixin apply here instead of at first cast in game.
	 */
	private void biteFlagAccessorResolves() {
		Object accessor = FishingHookAccessor.axolotlclient$dataBiting();
		check(accessor != null, "DATA_BITING accessor must resolve on the mixin-applied FishingHook class");
	}

	private void boostRegistryPrunesAndSorts() {
		long now = System.currentTimeMillis();
		BoostRegistry.clear();
		BoostRegistry.put("b", "B", now + 60_000);
		BoostRegistry.put("a", "A", now + 120_000);
		BoostRegistry.put("expired", "Expired", now - 1_000);
		List<BoostRegistry.ActiveBoost> active = BoostRegistry.active();
		check(active.size() == 2, "expired boosts must be pruned");
		check(active.get(0).id().equals("b") && active.get(1).id().equals("a"), "boosts sort soonest-ending first");
	}

	private void recastDelayStaysInRange() {
		AutoFish fish = AutoFish.getInstance();
		int min = Math.min(fish.delayMin.get(), fish.delayMax.get());
		int max = Math.max(fish.delayMin.get(), fish.delayMax.get());
		for (int i = 0; i < 200; i++) {
			int delay = fish.nextRecastDelayMs();
			check(delay >= min && delay <= max, "recast delay must stay inside [min,max] (got " + delay + ")");
		}
	}

	private void clickIntervalStaysInRange() {
		AutoClick click = AutoClick.getInstance();
		int minCps = Math.min(click.cpsMin.get(), click.cpsMax.get());
		int maxCps = Math.max(click.cpsMin.get(), click.cpsMax.get());
		long lowest = (long) Math.floor(1000.0 / maxCps * 0.8);
		long highest = (long) Math.ceil(1000.0 / minCps * 1.2);
		for (int i = 0; i < 200; i++) {
			long interval = click.nextIntervalMs();
			check(interval >= Math.max(50, lowest) && interval <= highest,
				"click interval must stay within the jittered CPS range (got " + interval + "ms)");
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
