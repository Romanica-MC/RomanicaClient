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

import java.util.ArrayList;
import java.util.List;

import io.github.axolotlclient.AxolotlClientConfigCommon.MenuTheme;
import io.github.axolotlclient.imperium.ImperiumClientSession;
import io.github.axolotlclient.imperium.hud.ImperiumHudEditScreen;
import io.github.axolotlclient.imperium.menu.CarvedTheme;
import io.github.axolotlclient.imperium.menu.ImperiumMenuScreen;
import io.github.axolotlclient.imperium.menu.ImperiumTitleScreen;
import io.github.axolotlclient.imperium.menu.MenuCategory;
import io.github.axolotlclient.imperium.menu.MenuEntry;
import io.github.axolotlclient.modules.hud.HudEditScreen;
import io.github.axolotlclient.modules.hud.HudManager;
import io.github.axolotlclient.modules.hud.gui.component.HudEntry;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import com.mojang.blaze3d.platform.InputConstants;

public class ImperiumMenuGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this suite is render-heavy, see ImperiumGametestSubsets
		GametestBookkeeping.executed(getClass().getSimpleName());
		// The run directory keeps config between runs; pin the theme so screenshots are deterministic.
		setTheme(context, MenuTheme.IMPERIUM);
		// The review's acceptance pins the screenshots to GUI scale 2 (the spec's measurements
		// are CSS px at 1080p / scale 2).
		double guiScale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		check(guiScale == 2.0, "menu screenshots must be taken at GUI scale 2, got " + guiScale);

		titleShellScreenshot(context);
		blockedCardOnTitleScreen(context);

		// Keybinds only fire in-game with no screen open, so test the menu from a world like a player would.
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			context.takeScreenshot("imperium-ingame-hud");

			context.getInput().pressKey(InputConstants.KEY_RSHIFT);
			context.waitForScreen(ImperiumMenuScreen.class);
			context.waitTicks(5);
			// Pin the backdrop blur explicitly: the menu's dim must sit over the vanilla
			// menu-blur pass while the option is on (the shell's soft world backdrop).
			context.runOnClient(mc -> mc.options.menuBackgroundBlurriness().set(5));
			context.waitTicks(5);
			context.takeScreenshot("imperium-menu-all");

			int total = context.computeOnClient(mc -> menu(mc).visibleEntries().size());
			check(total > 20, "menu should list the client's modules, got " + total);

			// Every module card must resolve its OWN vanilla item (the keyword mapping), not the
			// category fallback; the test fails naming the offenders.
			List<String> missing = context.computeOnClient(mc -> {
				List<String> offenders = new ArrayList<>();
				for (MenuEntry e : menu(mc).visibleEntries()) {
					if (CarvedTheme.specificIconFor(e) == null) {
						offenders.add(e.id());
					}
				}
				return offenders;
			});
			check(missing.isEmpty(), "every module card needs its own item icon, missing: " + missing);

			// The spec's acceptance: the shell at GUI scales 2 AND 3 (measurements are CSS px at
			// scale 2; scale 3 must not break the layout).
			withGuiScale(context, 3, () -> {
				context.waitTicks(5);
				context.takeScreenshot("imperium-menu-scale3");
				context.runOnClient(mc -> menu(mc).setSearchQuery("fps"));
				context.waitTicks(3);
				int fpsAt3 = context.computeOnClient(mc -> indexOfAny(menu(mc), "fps"));
				check(fpsAt3 >= 0, "searching \"fps\" should show the FPS card at GUI scale 3");
				// At scale 3 this window is only 160 GUI px tall: the searched card can sit below
				// the fold behind the wrapped segment chips, so the panel opens through the same
				// hook a gear click ends in (openSettings) instead of raw coordinates.
				int fpsAt3Card = fpsAt3;
				context.runOnClient(mc -> menu(mc).openSettings(fpsAt3Card));
				context.waitTicks(3);
				check(context.computeOnClient(mc -> menu(mc).isSettingsPanelOpen()), "the settings panel should open at GUI scale 3");
				context.takeScreenshot("imperium-menu-settings-scale3");
				context.getInput().pressKey(InputConstants.KEY_ESCAPE);
				context.waitTicks(3);
				context.runOnClient(mc -> menu(mc).setSearchQuery(""));
				context.waitTicks(2);
			});

			// Blur off: the same frame falls back to the plain surface dim over the sharp world.
			context.runOnClient(mc -> mc.options.menuBackgroundBlurriness().set(0));
			context.waitTicks(5);
			context.takeScreenshot("imperium-menu-blur-off");
			context.runOnClient(mc -> mc.options.menuBackgroundBlurriness().set(5));
			context.waitTicks(3);

			// Selecting a sidebar category moves the sliding pill.
			clickGui(context, context.computeOnClient(mc -> menu(mc).categoryCenter(MenuCategory.RENDER)));
			context.waitTicks(3);
			context.takeScreenshot("imperium-menu-category");

			// The category segments filter the same way.
			clickGui(context, context.computeOnClient(mc -> menu(mc).segmentCenter(MenuCategory.CHAT)));
			context.waitTicks(3);
			check(context.computeOnClient(mc -> menu(mc).visibleEntries().stream()
				.allMatch(e -> e.category() == MenuCategory.CHAT)), "the CHAT segment should filter to chat modules");

			// The FPS card lives in the HUD category, so return to ALL before searching.
			clickGui(context, context.computeOnClient(mc -> menu(mc).categoryCenter(MenuCategory.ALL)));
			context.waitTicks(3);

			context.getInput().typeChars("fps");
			context.waitTicks(3);
			int fpsIndex = context.computeOnClient(mc -> indexOf(menu(mc), "fps"));
			check(fpsIndex >= 0, "searching \"fps\" should show the FPS HUD card");
			context.takeScreenshot("imperium-menu-search");

			// The run directory keeps config between runs, so pin the starting state.
			context.runOnClient(mc -> fpsHud().setEnabled(false));
			boolean before = context.computeOnClient(mc -> menu(mc).visibleEntries().get(fpsIndex).enabled());
			check(!before, "FPS HUD should start disabled");
			clickGui(context, context.computeOnClient(mc -> menu(mc).cardBodyCenter(fpsIndex)));
			context.waitTicks(3);
			boolean after = context.computeOnClient(mc -> menu(mc).visibleEntries().get(fpsIndex).enabled());
			check(after, "clicking the disabled FPS card should enable it");
			context.takeScreenshot("imperium-menu-toggled");

			// The power glyph toggles like the card body.
			clickGui(context, context.computeOnClient(mc -> menu(mc).cardPowerCenter(fpsIndex)));
			context.waitTicks(3);
			check(!context.computeOnClient(mc -> menu(mc).visibleEntries().get(fpsIndex).enabled()),
				"the power glyph should toggle the module off again");

			// Pinning sorts the card first and shows the pin tag; unpinning restores the order.
			clickGui(context, context.computeOnClient(mc -> menu(mc).cardPinCenter(fpsIndex)));
			context.waitTicks(3);
			check(context.computeOnClient(mc -> menu(mc).visibleEntries().get(0).name().toLowerCase().contains("fps")),
				"pinning the FPS card should sort it first");
			context.takeScreenshot("imperium-menu-pinned");
			clickGui(context, context.computeOnClient(mc -> {
				int idx = indexOfAny(menu(mc), "fps");
				return menu(mc).cardPinCenter(idx);
			}));
			context.waitTicks(3);
			// Clear the search so the whole grid is back before asserting the sort order.
			context.runOnClient(mc -> menu(mc).setSearchQuery(""));
			context.waitTicks(2);
			int fpsFull = context.computeOnClient(mc -> indexOf(menu(mc), "fps"));
			check(fpsFull > 0, "unpinning should restore the alphabetical order");

			// CARVED and CLASSIC are the fallback themes; prove they still render and return.
			// A theme switch mid-session does not re-init the screen, so relayout to rebuild
			// the legacy footer's themed buttons before photographing them.
			setTheme(context, MenuTheme.CARVED);
			context.runOnClient(mc -> menu(mc).resize(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()));
			context.waitTicks(3);
			context.takeScreenshot("imperium-menu-carved");
			setTheme(context, MenuTheme.CLASSIC);
			context.runOnClient(mc -> menu(mc).resize(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()));
			context.waitTicks(3);
			context.takeScreenshot("imperium-menu-classic");
			setTheme(context, MenuTheme.IMPERIUM);
			context.runOnClient(mc -> menu(mc).resize(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()));
			context.waitTicks(3);

			// The gear now opens the module's settings INLINE over the card grid, not a new screen.
			// Re-search so the FPS card is the (visible) first card before clicking its gear.
			context.runOnClient(mc -> menu(mc).setSearchQuery("fps"));
			context.waitTicks(2);
			int fpsCard = context.computeOnClient(mc -> indexOfAny(menu(mc), "fps"));
			check(fpsCard >= 0, "searching \"fps\" should show the FPS card again");
			clickGui(context, context.computeOnClient(mc -> menu(mc).cardGearCenter(fpsCard)));
			context.waitTicks(3);
			check(context.computeOnClient(mc -> menu(mc).isSettingsPanelOpen()), "the gear click should open the inline settings panel");
			int[] fpsPanel = context.computeOnClient(mc -> {
				var m = menu(mc);
				return new int[]{m.settingsRowCount(), m.settingsWidgetCount()};
			});
			check(fpsPanel[0] > 0, "the FPS settings panel should list options, got " + fpsPanel[0]);
			check(fpsPanel[1] == fpsPanel[0], "every settings row should be a live widget, got " + fpsPanel[1] + "/" + fpsPanel[0]);
			context.takeScreenshot("imperium-menu-fps-settings");

			// The first Escape closes the panel; the menu stays open and the grid still toggles.
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitTicks(3);
			check(!context.computeOnClient(mc -> menu(mc).isSettingsPanelOpen()), "ESC should close the settings panel, not the menu");
			check(context.computeOnClient(mc -> mc.gui.screen() instanceof ImperiumMenuScreen), "the menu should survive ESC closing the panel");
			context.runOnClient(mc -> fpsHud().setEnabled(false));
			clickGui(context, context.computeOnClient(mc -> menu(mc).cardBodyCenter(fpsCard)));
			context.waitTicks(3);
			check(context.computeOnClient(mc -> {
				int idx = indexOfAny(menu(mc), "fps");
				return idx >= 0 && menu(mc).visibleEntries().get(idx).enabled();
			}), "clicking the FPS card should still toggle it after the panel closed");

			// A long option list scrolls inside the panel and survives a window resize.
			context.runOnClient(mc -> menu(mc).setSearchQuery("minimap"));
			context.waitTicks(2);
			int miniIndex = context.computeOnClient(mc -> indexOfAny(menu(mc), "minimap"));
			check(miniIndex >= 0, "searching \"minimap\" should show the minimap card");
			context.runOnClient(mc -> menu(mc).openSettings(miniIndex));
			context.waitTicks(3);
			check(context.computeOnClient(mc -> menu(mc).settingsRowCount()) >= 12, "the minimap settings should be a long list");
			context.takeScreenshot("imperium-menu-settings-minimap");
			// CLASSIC keeps its own flat panel styling for the settings overlay.
			setTheme(context, MenuTheme.CLASSIC);
			context.waitTicks(2);
			context.takeScreenshot("imperium-menu-settings-classic");
			setTheme(context, MenuTheme.IMPERIUM);
			context.waitTicks(2);
			int[] band = context.computeOnClient(mc -> menu(mc).settingsScrollCenter());
			context.getInput().setCursorPos(band[0] * guiScale, band[1] * guiScale);
			context.getInput().scroll(0, -8);
			context.waitTicks(3);
			check(context.computeOnClient(mc -> menu(mc).settingsScrollAmount()) > 0, "the wheel should scroll the settings rows");
			context.takeScreenshot("imperium-menu-settings-scrolled");
			// Drive Screen.resize directly: the harness's real window resize drops the player out
			// of the world in this environment, and the panel rebuild is the behavior under test.
			int[] guiSize = context.computeOnClient(mc -> new int[]{mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
			context.runOnClient(mc -> menu(mc).resize(guiSize[0] + 60, guiSize[1] + 40));
			context.waitTicks(3);
			check(context.computeOnClient(mc -> menu(mc).isSettingsPanelOpen()), "resizing the window should keep the settings panel open");
			check(context.computeOnClient(mc -> menu(mc).settingsRowCount()) >= 12, "the resized panel should keep its option rows");
			check(context.computeOnClient(mc -> menu(mc).settingsWidgetCount()) >= 12, "the resized panel should rebuild its widgets as live children");
			context.runOnClient(mc -> menu(mc).resize(guiSize[0], guiSize[1]));
			context.waitTicks(3);

			// Clicking a sidebar category closes the panel and shows that category's grid.
			clickGui(context, context.computeOnClient(mc -> menu(mc).categoryCenter(MenuCategory.ALL)));
			context.waitTicks(3);
			check(!context.computeOnClient(mc -> menu(mc).isSettingsPanelOpen()), "clicking a sidebar category should close the settings panel");
			context.runOnClient(mc -> menu(mc).setSearchQuery(""));
			context.waitTicks(2);
			check(context.computeOnClient(mc -> menu(mc).visibleEntries().size()) > 20, "the full module grid should be back after closing the panel");

			// Escape closes the PANEL first; only a second Escape closes the whole menu.
			context.runOnClient(mc -> menu(mc).openSettings(0));
			context.waitTicks(2);
			check(context.computeOnClient(mc -> menu(mc).isSettingsPanelOpen()), "openSettings should reopen the panel");
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitTicks(2);
			check(!context.computeOnClient(mc -> menu(mc).isSettingsPanelOpen()), "the first ESC should close the panel");
			check(context.computeOnClient(mc -> mc.gui.screen() instanceof ImperiumMenuScreen), "the first ESC should leave the menu open");
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitTicks(2);
			check(context.computeOnClient(mc -> !(mc.gui.screen() instanceof ImperiumMenuScreen)), "a second ESC should close the menu");

			context.setScreen(() -> null);
			context.waitTicks(5);
			context.takeScreenshot("imperium-ingame-after-toggle");

			// 26.3 numbers mouse buttons SDL-style (left = 1); a left drag must move the element, not open its settings.
			context.runOnClient(mc -> {
				fpsHud().setEnabled(true);
				fpsHud().onBoundsUpdate();
			});
			// The v2 editor (theme Imperium, pinned above) wraps the same drag machinery; the
			// drag must keep working through the new layer.
			context.setScreen(() -> new ImperiumHudEditScreen());
			context.waitTicks(5);
			context.takeScreenshot("imperium-hud-editor");
			int[] start = context.computeOnClient(mc -> {
				var bounds = fpsHud().getTrueBounds();
				return new int[]{bounds.x() + bounds.width() / 2, bounds.y() + bounds.height() / 2};
			});
			double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
			context.getInput().setCursorPos(start[0] * scale, start[1] * scale);
			// The editor picks move/resize from the hovered element while rendering, so let a frame see the hover first.
			context.waitTicks(2);
			context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(2);
			context.getInput().setCursorPos((start[0] + 40) * scale, (start[1] + 30) * scale);
			context.waitTicks(2);
			context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(3);
			check(context.computeOnClient(mc -> mc.gui.screen() instanceof HudEditScreen), "left-dragging a HUD element should not leave the HUD editor");
			int movedX = context.computeOnClient(mc -> fpsHud().getTrueBounds().x() + fpsHud().getTrueBounds().width() / 2);
			check(Math.abs(movedX - start[0]) >= 20, "dragging the FPS HUD should move it (from x=" + start[0] + " to " + movedX + ")");
			context.takeScreenshot("imperium-hud-editor-dragged");
		}
	}

	/**
	 * The v3 title shell: constructing a vanilla TitleScreen must swap in the Imperium layout
	 * (TitleScreenMixin), and the live player-count parser handles real and broken payloads.
	 */
	private void titleShellScreenshot(ClientGameTestContext context) {
		try {
			context.setScreen(() -> new TitleScreen());
			context.waitTicks(5);
			check(context.computeOnClient(mc -> mc.gui.screen() instanceof ImperiumTitleScreen),
				"a vanilla TitleScreen should be swapped for the Imperium title shell");
			context.waitTicks(10);
			context.takeScreenshot("imperium-title");
			String parsed = ImperiumTitleScreen.parsePlayerCount("{\"players\":{\"online\":12,\"max\":50}}");
			check("12 / 50".equals(parsed), "the status parser should read players.online/max, got " + parsed);
			check(ImperiumTitleScreen.parsePlayerCount("<html>offline</html>") == null,
				"the status parser should fail gracefully on a non-JSON body");
		} finally {
			context.setScreen(() -> null);
			context.waitTicks(2);
		}
	}

	/**
	 * With no world open, an ungranted gated module is server-blocked: the card renders the
	 * blocked state (red tint, block glyph, reason line) and cannot be toggled.
	 */
	private void blockedCardOnTitleScreen(ClientGameTestContext context) {
		try {
			ImperiumClientSession.getInstance().reset();
			context.setScreen(() -> new ImperiumMenuScreen(null));
			context.waitForScreen(ImperiumMenuScreen.class);
			context.waitTicks(5);
			context.runOnClient(mc -> menu(mc).setSearchQuery("autofish"));
			context.waitTicks(3);
			int fishIndex = context.computeOnClient(mc -> indexOfAny(menu(mc), "autofish"));
			check(fishIndex >= 0, "searching \"autofish\" should show the auto-fish card");
			check(context.computeOnClient(mc -> menu(mc).isCardBlocked(fishIndex)),
				"the auto-fish card should render blocked without a server grant");
			check(!context.computeOnClient(mc -> menu(mc).visibleEntries().get(fishIndex).enabled()),
				"a blocked module must not read as enabled");
			clickGui(context, context.computeOnClient(mc -> menu(mc).cardBodyCenter(fishIndex)));
			context.waitTicks(3);
			check(!context.computeOnClient(mc -> menu(mc).visibleEntries().get(fishIndex).enabled()),
				"clicking a blocked card must not enable the module");
			context.takeScreenshot("imperium-menu-blocked");
		} finally {
			context.setScreen(() -> null);
			context.waitTicks(2);
			ImperiumClientSession.getInstance().reset();
		}
	}

	private static void setTheme(ClientGameTestContext context, MenuTheme theme) {
		context.runOnClient(mc -> AxolotlClient.config().menuTheme.set(theme));
	}

	private static HudEntry fpsHud() {
		return HudManager.getInstance().getEntries().stream()
			.filter(e -> e.getId().toString().endsWith("fpshud"))
			.findFirst().orElseThrow(() -> new AssertionError("FPS HUD not registered"));
	}

	private static ImperiumMenuScreen menu(Minecraft mc) {
		if (mc.gui.screen() instanceof ImperiumMenuScreen screen) return screen;
		throw new AssertionError("expected ImperiumMenuScreen, got " + mc.gui.screen());
	}

	private static int indexOf(ImperiumMenuScreen screen, String query) {
		var entries = screen.visibleEntries();
		for (int i = 0; i < entries.size(); i++) {
			MenuEntry e = entries.get(i);
			if (e.category().name().equals("HUD") && e.name().toLowerCase().contains(query)) return i;
		}
		return -1;
	}

	// Like indexOf, but matches the id too and ignores the category (the minimap lives in RENDER).
	private static int indexOfAny(ImperiumMenuScreen screen, String query) {
		var entries = screen.visibleEntries();
		for (int i = 0; i < entries.size(); i++) {
			MenuEntry e = entries.get(i);
			if (e.name().toLowerCase().contains(query) || e.id().toLowerCase().contains(query)) return i;
		}
		return -1;
	}

	// TestInput positions the cursor in window pixels; menu layout is in GUI-scaled units.
	private static void clickGui(ClientGameTestContext context, int[] guiPos) {
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		context.getInput().setCursorPos(guiPos[0] * scale, guiPos[1] * scale);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
	}

	/**
	 * Runs [body] with the window at a different GUI scale, then restores the original. The real
	 * window resize drops the player out of the world here, so the open screen is relaid out via
	 * [net.minecraft.client.gui.screens.Screen.resize] instead (the same route a real scale change
	 * takes through Minecraft.resizeDisplay).
	 */
	private static void withGuiScale(ClientGameTestContext context, int scale, Runnable body) {
		int previous = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		context.runOnClient(mc -> {
			mc.getWindow().setGuiScale(scale);
			if (mc.gui.screen() != null) {
				mc.gui.screen().resize(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
			}
		});
		context.waitTicks(5);
		try {
			body.run();
		} finally {
			context.runOnClient(mc -> {
				mc.getWindow().setGuiScale(previous);
				if (mc.gui.screen() != null) {
					mc.gui.screen().resize(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
				}
			});
			context.waitTicks(3);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
