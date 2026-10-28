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

import io.github.axolotlclient.AxolotlClientConfigCommon.MenuTheme;
import io.github.axolotlclient.imperium.ImperiumClientSession;
import io.github.axolotlclient.imperium.menu.CarvedTheme;
import io.github.axolotlclient.imperium.menu.ImperiumMenuScreen;
import io.github.axolotlclient.imperium.menu.MenuEntry;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;

/**
 * CARVED theme icon coverage (issue #27): the card icon wells draw vanilla item sprites through
 * {@code GuiGraphicsExtractor.fakeItem}, and {@link ImperiumMenuGameTest} only photographs CARVED
 * from inside a world. Opening the menu from the title screen (no level loaded) exercises the
 * icon path with a null client level — the context the NPE was reported in. Both contexts must
 * render every card's icon well without crashing, and the theme must return to IMPERIUM so
 * later suites pin deterministic themes.
 */
public class CarvedThemeGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this suite is render-heavy, see ImperiumGametestSubsets
		GametestBookkeeping.executed(getClass().getSimpleName());

		// No world: the title-screen open is the untested CARVED context (client level == null).
		ImperiumClientSession.getInstance().reset();
		try {
			setTheme(context, MenuTheme.CARVED);
			context.setScreen(() -> new ImperiumMenuScreen(null));
			context.waitForScreen(ImperiumMenuScreen.class);
			// Leave the grid on screen long enough for every visible card's icon well to draw.
			context.waitTicks(20);
			assertGridHealthy(context);
			context.takeScreenshot("carved-theme-title-screen");
		} finally {
			context.setScreen(() -> null);
			context.waitTicks(2);
			ImperiumClientSession.getInstance().reset();
		}

		// In-world: the already-covered context stays pinned so the two cannot drift apart.
		try (var world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			setTheme(context, MenuTheme.CARVED);
			context.setScreen(() -> new ImperiumMenuScreen(null));
			context.waitForScreen(ImperiumMenuScreen.class);
			context.waitTicks(20);
			assertGridHealthy(context);
			context.takeScreenshot("carved-theme-in-world");
		} finally {
			// The run directory keeps config between runs; hand the next suite the default theme.
			setTheme(context, MenuTheme.IMPERIUM);
		}
	}

	/** The grid must be populated and every visible card must still resolve a non-empty icon. */
	private static void assertGridHealthy(ClientGameTestContext context) {
		int total = context.computeOnClient(mc -> menu(mc).visibleEntries().size());
		check(total > 20, "the CARVED menu should list the client's modules, got " + total);
		Boolean resolved = context.computeOnClient(mc -> {
			for (MenuEntry e : menu(mc).visibleEntries()) {
				if (CarvedTheme.iconFor(e).isEmpty()) return false;
			}
			return true;
		});
		check(Boolean.TRUE.equals(resolved), "every CARVED card icon must resolve to an item");
	}

	private static void setTheme(ClientGameTestContext context, MenuTheme theme) {
		context.runOnClient(mc -> AxolotlClient.config().menuTheme.set(theme));
	}

	private static ImperiumMenuScreen menu(Minecraft mc) {
		if (mc.gui.screen() instanceof ImperiumMenuScreen screen) return screen;
		throw new AssertionError("expected ImperiumMenuScreen, got " + mc.gui.screen());
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
