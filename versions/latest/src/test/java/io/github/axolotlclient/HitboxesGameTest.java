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
import io.github.axolotlclient.modules.hitboxes.Hitboxes;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.CameraType;

/**
 * Wave 5 hitboxes: the keybind toggle flips the module in-game, and with a mob plus a dropped
 * item in view every rendered frame runs the gizmo emission (a broken injection or draw call
 * crashes the render). The screenshots show the boxes, eye lines and colours outside F3.
 */
public class HitboxesGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this suite is render-heavy, see ImperiumGametestSubsets
		GametestBookkeeping.executed(getClass().getSimpleName());
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();

			// A pig (mob colour + eye line) and dropped items (item colour) right in front of
			// the player; the player itself supplies the player colour in third person.
			world.getServer().runCommand("execute as @p at @s run summon pig ^ ^ ^3");
			world.getServer().runCommand("execute as @p at @s run summon item ^-1 ^ ^2 {Item:{id:\"minecraft:diamond\",count:1}}");
			world.getServer().runCommand("execute as @p at @s run summon item ^1 ^ ^2 {Item:{id:\"minecraft:gold_ingot\",count:1}}");
			context.waitTicks(10);

			// The keybind toggles the module while in-game (no screen open). setKey alone does
			// not rebuild the static routing map — same as the controls screen, reset it.
			context.runOnClient(mc -> {
				((KeyMapping) (Object) Hitboxes.getInstance().toggleKey)
					.setKey(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_H));
				KeyMapping.resetMapping();
			});
			context.waitTicks(2);
			context.getInput().pressKey(InputConstants.KEY_H);
			context.waitTicks(5);
			check(context.computeOnClient(mc -> Hitboxes.getInstance().enabled.get()),
				"the hitboxes keybind must toggle the module on");

			// Frames with the boxes in view; F3 is not involved anywhere.
			context.takeScreenshot("hitboxes-first-person");
			world.getServer().runCommand("execute as @p at @s run tp @s ^ ^ ^ ~30 ~");
			context.waitTicks(5);
			context.takeScreenshot("hitboxes-turned");

			// Third person adds the player's own box (the first-person skip is vanilla behaviour).
			context.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
			context.waitTicks(5);
			context.takeScreenshot("hitboxes-third-person");

			// Off again — the gizmos must stop emitting without breaking the frame.
			context.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
			context.getInput().pressKey(InputConstants.KEY_H);
			context.waitTicks(5);
			check(!context.computeOnClient(mc -> Hitboxes.getInstance().enabled.get()),
				"the hitboxes keybind must toggle the module off");
			context.takeScreenshot("hitboxes-off");
		} finally {
			context.runOnClient(mc -> Hitboxes.getInstance().enabled.set(false));
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
