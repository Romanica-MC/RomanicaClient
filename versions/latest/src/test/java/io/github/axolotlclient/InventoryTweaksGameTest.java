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

import io.github.axolotlclient.mixin.AbstractContainerScreenAccessor;
import io.github.axolotlclient.modules.inventoryTweaks.CompactCounts;
import io.github.axolotlclient.modules.inventoryTweaks.InventoryTweaks;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Wave 5 inventory_tweaks: the pure compact-count formatter, then the live toggles in a real
 * world — an effect-backed open inventory proves the panel suppression (showsActiveEffects
 * flips), the container recentering (leftPos equals the centered value) and the compact count
 * rendering path. Every rendered frame while an option is on runs its mixin, so a broken
 * injection fails the test by crashing the render.
 */
public class InventoryTweaksGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this suite is render-heavy, see ImperiumGametestSubsets
		GametestBookkeeping.executed(getClass().getSimpleName());
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();

			testCompactFormat(context);
			testEffectPanelAndCentering(context);
			testCompactCountsLive(context);
		} finally {
			context.runOnClient(mc -> {
				InventoryTweaks tweaks = InventoryTweaks.getInstance();
				tweaks.hideVanillaPotionHud.set(false);
				tweaks.keepCentered.set(false);
				tweaks.compactItemCounts.set(false);
			});
		}
	}

	/** Pure string math, no game state. */
	private void testCompactFormat(ClientGameTestContext context) {
		check(CompactCounts.format(1).equals("1"), "count 1 stays \"1\"");
		check(CompactCounts.format(64).equals("64"), "count 64 stays \"64\"");
		check(CompactCounts.format(9_999).equals("9999"), "just below the threshold stays plain");
		check(CompactCounts.format(10_000).equals("10k"), "the threshold itself compacts");
		check(CompactCounts.format(10_100).equals("10.1k"), "10,100 keeps one decimal");
		check(CompactCounts.format(12_340).equals("12.3k"), "12,340 rounds to \"12.3k\"");
		check(CompactCounts.format(12_000).equals("12k"), "a zero decimal is dropped");
		check(CompactCounts.format(123_456).equals("123k"), "six digits drop the decimal");
		check(CompactCounts.format(999_999).equals("999k"), "just below a million stays in k");
		check(CompactCounts.format(1_000_000).equals("1m"), "a million compacts to \"1m\"");
		check(CompactCounts.format(1_234_567).equals("1.2m"), "1,234,567 renders \"1.2m\"");
		check(CompactCounts.format(123_000_000).equals("123m"), "large values keep the suffix");

		// The toggle routes through the module: off must be exactly vanilla's string.
		context.runOnClient(mc -> {
			InventoryTweaks tweaks = InventoryTweaks.getInstance();
			tweaks.compactItemCounts.set(false);
			check(tweaks.formatCount(12_340).equals("12340"), "with the toggle off the count stays plain");
			tweaks.compactItemCounts.set(true);
			check(tweaks.formatCount(12_340).equals("12.3k"), "with the toggle on the count compacts");
		});
	}

	/**
	 * With an active effect and an open inventory, vanilla draws the effect panel beside the
	 * container ({@code showsActiveEffects()} true). keep_centered must suppress it and leave
	 * the container exactly centered; hide_vanilla_potion_hud additionally removes the icons
	 * from the screen corner.
	 */
	private void testEffectPanelAndCentering(ClientGameTestContext context) {
		context.runOnClient(mc -> mc.player.addEffect(new MobEffectInstance(MobEffects.SPEED, 20 * 120, 0)));
		context.waitTicks(2);

		InventoryTweaks tweaks = InventoryTweaks.getInstance();

		// Baseline: no tweak options, the panel shows and showsActiveEffects is true.
		context.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
		context.waitTicks(3);
		context.waitForScreen(InventoryScreen.class);
		check(context.computeOnClient(mc -> mc.gui.screen().showsActiveEffects()),
			"with effects and free space the vanilla panel should show");
		context.takeScreenshot("inventory-tweaks-panel-vanilla");

		// keep_centered: panel suppressed, screen reports no effects (icons fall back to the
		// corner) and the container is exactly centered.
		context.runOnClient(mc -> tweaks.keepCentered.set(true));
		reopenInventory(context);
		check(!context.computeOnClient(mc -> mc.gui.screen().showsActiveEffects()),
			"keep_centered must suppress the in-inventory effect panel");
		check(context.computeOnClient(mc -> {
			InventoryScreen screen = (InventoryScreen) mc.gui.screen();
			AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
			return accessor.axolotlclient$getLeftPos() == (screen.width - accessor.axolotlclient$getImageWidth()) / 2;
		}), "keep_centered must leave the container exactly centered");
		context.takeScreenshot("inventory-tweaks-keep-centered");

		// Plus hide_vanilla_potion_hud: no vanilla effect icons anywhere.
		context.runOnClient(mc -> tweaks.hideVanillaPotionHud.set(true));
		reopenInventory(context);
		check(!context.computeOnClient(mc -> mc.gui.screen().showsActiveEffects()),
			"the hidden vanilla HUD must also suppress the panel");
		context.takeScreenshot("inventory-tweaks-hide-effects");

		// The HUD corner icons are cancelled by the same toggle — proven live by the frames
		// rendered while no screen is open (extractEffects runs there and must return early).
		context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE);
		context.waitTicks(5);
		context.takeScreenshot("inventory-tweaks-hide-effects-world");
	}

	private void testCompactCountsLive(ClientGameTestContext context) {
		// A stack far beyond 10k renders through the compact formatter in the open inventory
		// (itemCount's String.valueOf redirect) — frames render, the slot shows "64k". The
		// oversized stack is placed server-side (the insertion paths cap at the stack maximum,
		// setItem does not) so the client really carries count 64,000.
		context.runOnClient(mc -> {
			InventoryTweaks.getInstance().compactItemCounts.set(true);
			ServerPlayer player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
			if (player != null) {
				player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 64_000));
			}
		});
		context.waitTicks(5);
		context.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
		context.waitTicks(3);
		context.waitForScreen(InventoryScreen.class);
		check(context.computeOnClient(mc -> {
			for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
				net.minecraft.world.item.ItemStack stack = mc.player.getInventory().getItem(i);
				if (stack.getItem() == Items.DIAMOND && stack.getCount() == 64_000) {
					return true;
				}
			}
			return false;
		}), "the oversized test stack should be in the inventory");
		context.takeScreenshot("inventory-tweaks-compact-counts");
	}

	private void reopenInventory(ClientGameTestContext context) {
		context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE);
		context.waitTicks(2);
		context.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
		context.waitTicks(3);
		context.waitForScreen(InventoryScreen.class);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
