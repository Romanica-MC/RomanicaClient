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

import io.github.axolotlclient.modules.autoutils.AutoEat;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Exercises Auto-Eat end to end in a real world with real food and real hunger (both sides
 * of the integrated server, so the eat actually completes): the pure food choice over the
 * hotbar, the swap to the best stack, the vanilla use-item path running the full eat, and
 * the swap back to the previously held slot.
 */
public class AutoEatGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this suite is render-heavy, see ImperiumGametestSubsets
		GametestBookkeeping.executed(getClass().getSimpleName());
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();

			// Pin the module's options (the run directory keeps config between runs).
			context.runOnClient(mc -> {
				AutoEat.getInstance().enabled.set(true);
				AutoEat.getInstance().enabled.setForceOff(false, "");
				AutoEat.getInstance().hungerThreshold.set(14);
				AutoEat.getInstance().swapBack.set(true);
			});

			// Real food in the hotbar (bread slot 1, cooked beef slot 3) and real hunger on
			// BOTH the client and the integrated server — the server refuses to start an eat
			// it considers full, and the eat's nutrition lands server-side.
			context.runOnClient(mc -> {
				var serverPlayer = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
				serverPlayer.getInventory().setItem(1, new ItemStack(Items.BREAD));
				serverPlayer.getInventory().setItem(3, new ItemStack(Items.COOKED_BEEF));
				serverPlayer.getFoodData().setFoodLevel(10);
				mc.player.getFoodData().setFoodLevel(10);
			});
			context.waitTicks(5); // let the inventory sync reach the client

			// The pure choice: cooked beef (nutrition 8) beats bread (nutrition 5).
			check(context.computeOnClient(mc -> AutoEat.chooseFoodSlot(mc.player.getInventory())) == 3,
				"the best hotbar food is the cooked beef in slot 3");

			// Below the threshold (10 < 14): the module selects the food and starts the eat
			// through the vanilla right-click path.
			context.waitTicks(3);
			check(context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot()) == 3,
				"hunger below the threshold must select the best food slot");
			check(context.computeOnClient(mc -> mc.player.isUsingItem()),
				"the module must run the eat through the vanilla use-item path");

			// A full eat is 32 ticks; wait well past it.
			context.waitTicks(60);
			check(context.computeOnClient(mc -> mc.player.getFoodData().getFoodLevel()) > 10,
				"the eat must actually restore hunger on both sides");
			check(context.computeOnClient(mc -> !mc.player.isUsingItem()),
				"the eat must have completed, not stuck");
			check(context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot()) == 0,
				"swap_back must restore the previously held slot");

			// Above the threshold nothing happens even with food in hand.
			context.runOnClient(mc -> {
				var serverPlayer = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
				serverPlayer.getInventory().setItem(3, new ItemStack(Items.COOKED_BEEF));
				serverPlayer.getFoodData().setFoodLevel(19);
				mc.player.getInventory().setSelectedSlot(3);
			});
			context.waitTicks(5);
			check(context.computeOnClient(mc -> !mc.player.isUsingItem()),
				"hunger above the threshold must not start an eat");
			context.takeScreenshot("auto-eat-satisfied");
		} finally {
			context.runOnClient(mc -> {
				AutoEat.getInstance().enabled.setForceOff(false, "");
				if (mc.player != null) {
					mc.player.getInventory().setSelectedSlot(0);
				}
				restoreHunger(mc);
			});
		}
	}

	private static void restoreHunger(Minecraft mc) {
		if (mc.player != null) {
			mc.player.getFoodData().setFoodLevel(20);
		}
		if (mc.getSingleplayerServer() != null && !mc.getSingleplayerServer().getPlayerList().getPlayers().isEmpty()) {
			mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).getFoodData().setFoodLevel(20);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
