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

import com.mojang.blaze3d.platform.InputConstants;
import io.github.axolotlclient.modules.inventorySearch.InventorySearch;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Exercises the inventory search overlay end to end on a real container screen: the bound key
 * opens it over the open inventory, key events are swallowed while it holds the input (E must
 * not close the inventory), typed characters reach the field, matching slots are counted, and
 * Escape dismisses the overlay without closing the container.
 */
public class InventorySearchGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this suite is render-heavy, see ImperiumGametestSubsets
		GametestBookkeeping.executed(getClass().getSimpleName());
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();

			// Give the inventory something to find, then open it.
			context.runOnClient(mc -> mc.player.getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE)));
			context.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
			context.waitTicks(5);
			context.waitForScreen(InventoryScreen.class);

			// Keybinds never fire while a screen is open, so the container screen itself must pick
			// the bound key up. Bind F, press it, and expect the overlay.
			context.runOnClient(mc -> ((KeyMapping) (Object) InventorySearch.getInstance().searchKey)
				.setKey(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_F)));
			context.getInput().pressKey(InputConstants.KEY_F);
			context.waitTicks(3);
			check(context.computeOnClient(mc -> InventorySearch.getInstance().isActive()),
				"the search key should open the overlay on an open container screen");
			context.takeScreenshot("inventory-search-open");

			// E closes the vanilla inventory through keyPressed; while the overlay holds the input
			// that key must be swallowed instead.
			context.getInput().pressKey(InputConstants.KEY_E);
			context.waitTicks(3);
			check(context.computeOnClient(mc -> mc.gui.screen() instanceof InventoryScreen),
				"pressing E while searching must not close the container");
			check(context.computeOnClient(mc -> InventorySearch.getInstance().isActive()),
				"pressing E while searching must keep the overlay open");

			// Reopen for a clean field, then type. Typed characters land in the query.
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitTicks(2);
			context.getInput().pressKey(InputConstants.KEY_F);
			context.waitTicks(3);
			check(context.computeOnClient(mc -> InventorySearch.getInstance().query().isEmpty()),
				"a freshly opened overlay should start with an empty query");
			context.getInput().typeChars("e");
			context.waitTicks(2);
			check(context.computeOnClient(mc -> InventorySearch.getInstance().query().equals("e")),
				"typed characters should land in the search field");
			check(context.computeOnClient(mc -> mc.gui.screen() instanceof InventoryScreen),
				"typing while searching must not leak into the container");

			// "Diamond Pickaxe" contains an e.
			check(context.computeOnClient(mc -> InventorySearch.getInstance().matchingSlots(mc.gui.screen())) >= 1,
				"query \"e\" should match the pickaxe");
			context.takeScreenshot("inventory-search-highlight");

			// A nonsense query matches nothing.
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitTicks(2);
			check(!context.computeOnClient(mc -> InventorySearch.getInstance().isActive()),
				"Escape should dismiss the search overlay");
			check(context.computeOnClient(mc -> mc.gui.screen() instanceof InventoryScreen),
				"dismissing the overlay must not close the container");
			context.getInput().pressKey(InputConstants.KEY_F);
			context.waitTicks(3);
			context.getInput().typeChars("zzz");
			context.waitTicks(2);
			check(context.computeOnClient(mc -> InventorySearch.getInstance().matchingSlots(mc.gui.screen())) == 0,
				"a nonsense query should match no slots");
			context.takeScreenshot("inventory-search-no-matches");
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
