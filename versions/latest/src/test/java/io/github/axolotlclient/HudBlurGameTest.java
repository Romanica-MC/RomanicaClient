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

import io.github.axolotlclient.modules.blur.HudBlur;
import io.github.axolotlclient.modules.hud.HudManager;
import io.github.axolotlclient.modules.hud.gui.component.HudEntry;
import io.github.axolotlclient.modules.hud.gui.entry.AbstractHudEntry;
import io.github.axolotlclient.modules.hud.gui.hud.simple.FPSHud;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Wave 6 HUD background blur: with the master option and one entry's background_blur on, the
 * blur chain runs every in-world frame (world-only, before the GUI), exposes its blurred frame
 * to entries, and the entry blits it behind its background. A broken injection, shader or blit
 * crashes the render; turning the module off must stop the pass entirely (idle cost).
 */
public class HudBlurGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this suite is render-heavy, see ImperiumGametestSubsets
		GametestBookkeeping.executed(getClass().getSimpleName());
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();

			context.runOnClient(mc -> {
				HudBlur.getInstance().enabled.set(true);
				HudEntry fps = HudManager.getInstance().get(FPSHud.ID);
				fps.setEnabled(true);
				setOption(fps, "background", "true");
				setOption(fps, "background_blur", "true");
			});
			context.waitTicks(20);

			// (a)+(b): frames rendered without a crash and the pass actually ran.
			int processed = context.computeOnClient(mc -> HudBlur.framesProcessed());
			check(processed > 0, "the HUD blur chain must run while an entry wants blur, got " + processed + " processed frames");
			// (c): the persistent blurred frame is exposed to entries after a pass.
			check(context.computeOnClient(mc -> HudBlur.getBlurredView()) != null,
				"the blurred frame must be available to HUD entries after the pass ran");
			context.takeScreenshot("hudblur-enabled");

			// Off again: the pass must stop running (the idle gate) without breaking the frame.
			context.runOnClient(mc -> HudBlur.getInstance().enabled.set(false));
			context.waitTicks(10);
			int before = context.computeOnClient(mc -> HudBlur.framesProcessed());
			check(before >= processed, "the counter must not go backwards, " + processed + " -> " + before);
			context.waitTicks(10);
			int after = context.computeOnClient(mc -> HudBlur.framesProcessed());
			check(after == before, "the HUD blur chain must stop once the module is off, counter moved " + before + " -> " + after);
			check(context.computeOnClient(mc -> HudBlur.getBlurredView()) == null,
				"no blurred frame may be exposed while the module is off");
			context.takeScreenshot("hudblur-off");
		} finally {
			context.runOnClient(mc -> {
				HudBlur.getInstance().enabled.set(false);
				HudEntry fps = HudManager.getInstance().get(FPSHud.ID);
				if (fps != null) {
					fps.setEnabled(false);
					setOption(fps, "background_blur", "false");
				}
			});
		}
	}

	private static void setOption(HudEntry entry, String name, String value) {
		((AbstractHudEntry) entry).getAllOptions().getOptions().stream()
			.filter(option -> option.getName().equals(name))
			.findFirst()
			.ifPresent(option -> option.fromSerializedValue(value));
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
