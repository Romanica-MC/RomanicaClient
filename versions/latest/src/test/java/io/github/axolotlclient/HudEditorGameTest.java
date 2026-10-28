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
import io.github.axolotlclient.AxolotlClient;
import io.github.axolotlclient.AxolotlClientConfigCommon.MenuTheme;
import io.github.axolotlclient.imperium.hud.HudEditorAnchors;
import io.github.axolotlclient.imperium.hud.ImperiumHudEditScreen;
import io.github.axolotlclient.imperium.menu.ImperiumMenuScreen;
import io.github.axolotlclient.modules.hud.HudManager;
import io.github.axolotlclient.modules.hud.gui.component.HudEntry;
import io.github.axolotlclient.modules.hud.gui.layout.AnchorPoint;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;

/**
 * The HUD editor v2 acceptance run (docs/design/README.md, "HUD editor"): selects an element
 * through the new selection model, drives the keyboard nudging and the bounded undo/redo
 * stack, exercises the context menu (anchor picker, reset, settings-through-the-menu), and
 * screenshots the editor with the snap guides, toolbar and help panel visible.
 */
public class HudEditorGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this suite needs rendering
		GametestBookkeeping.executed(getClass().getSimpleName());
		setTheme(context, MenuTheme.IMPERIUM);
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();

			// Park the FPS HUD at a known quiet spot so every click target is deterministic.
			context.runOnClient(mc -> {
				HudEntry fps = fpsHud();
				fps.setEnabled(true);
				fps.clearBoundsDependencies();
				fps.setPos((int) (mc.getWindow().getGuiScaledWidth() * 0.3), (int) (mc.getWindow().getGuiScaledHeight() * 0.3));
				fps.setScale(1f);
				HudEditorAnchors.apply(fps, AnchorPoint.TOP_LEFT);
				fps.onBoundsUpdate();
				// The run directory keeps config between runs; the snap-guides step needs snapping on.
				if (!HudManager.getInstance().isSnappingEnabled()) {
					HudManager.getInstance().toggleSnapping();
				}
			});

			context.setScreen(() -> new ImperiumHudEditScreen(null));
			context.waitTicks(5);
			context.takeScreenshot("hud-editor-v2-toolbar");

			helpPanelToggles(context);

			snappingChipToggles(context);

			selectAndNudge(context, scale);

			dragWithGuides(context, scale);

			contextMenu(context, scale);
		}
	}

	/** The circular "?" button opens the shortcut panel; the H key toggles it too. */
	private void helpPanelToggles(ClientGameTestContext context) {
		int[] help = context.computeOnClient(mc -> editor(mc).helpButtonCenter());
		clickGui(context, scale(context), help);
		context.waitTicks(3);
		check(context.computeOnClient(mc -> editor(mc).isHelpOpen()), "the ? button should open the help panel");
		context.takeScreenshot("hud-editor-v2-help");

		clickGui(context, scale(context), help);
		context.waitTicks(2);
		check(!context.computeOnClient(mc -> editor(mc).isHelpOpen()), "clicking ? again should close the help panel");

		context.getInput().pressKey(InputConstants.KEY_H);
		context.waitTicks(2);
		check(context.computeOnClient(mc -> editor(mc).isHelpOpen()), "H should toggle the help panel back on");
		context.getInput().pressKey(InputConstants.KEY_H);
		context.waitTicks(2);
		check(!context.computeOnClient(mc -> editor(mc).isHelpOpen()), "H should close it again");
	}

	/** The Snapping chip flips the global snapping option (and back). */
	private void snappingChipToggles(ClientGameTestContext context) {
		boolean before = context.computeOnClient(mc -> HudManager.getInstance().isSnappingEnabled());
		clickGui(context, scale(context), context.computeOnClient(mc -> editor(mc).chipCenter("snapping")));
		context.waitTicks(2);
		check(context.computeOnClient(mc -> HudManager.getInstance().isSnappingEnabled()) != before,
			"the Snapping chip should flip snapping");
		clickGui(context, scale(context), context.computeOnClient(mc -> editor(mc).chipCenter("snapping")));
		context.waitTicks(2);
		check(context.computeOnClient(mc -> HudManager.getInstance().isSnappingEnabled()) == before,
			"the Snapping chip should flip snapping back");
	}

	/** Click-select, arrow nudges (1px / Shift 10px), coalescing undo, redo, Delete-hides. */
	private void selectAndNudge(ClientGameTestContext context, double scale) {
		clickGui(context, scale, fpsCenter(context));
		context.waitTicks(2);
		String selected = context.computeOnClient(mc -> editor(mc).selectedEntryId());
		check(selected != null && selected.endsWith("fpshud"), "clicking an element should select it, got " + selected);
		check(context.computeOnClient(mc -> editor(mc).undoDepth()) == 0,
			"selecting an element without moving it must not record history");

		int x0 = context.computeOnClient(mc -> fpsHud().getTrueBounds().x());
		context.getInput().pressKey(InputConstants.KEY_LEFT);
		context.waitTicks(2);
		check(context.computeOnClient(mc -> fpsHud().getTrueBounds().x()) == x0 - 1, "an arrow should nudge one pixel");
		context.getInput().pressKey(InputConstants.KEY_LEFT);
		context.waitTicks(1);
		context.getInput().pressKey(InputConstants.KEY_LEFT);
		context.waitTicks(2);
		check(context.computeOnClient(mc -> fpsHud().getTrueBounds().x()) == x0 - 3, "three left arrows should move three pixels");
		check(context.computeOnClient(mc -> editor(mc).undoDepth()) == 1,
			"a nudge burst should coalesce into one undo entry, got " + context.computeOnClient(mc -> editor(mc).undoDepth()));
		context.takeScreenshot("hud-editor-v2-selected");

		undo(context);
		check(context.computeOnClient(mc -> fpsHud().getTrueBounds().x()) == x0, "Ctrl+Z should undo the whole nudge burst");
		check(context.computeOnClient(mc -> editor(mc).redoDepth()) == 1, "undo should feed the redo stack");
		redo(context);
		check(context.computeOnClient(mc -> fpsHud().getTrueBounds().x()) == x0 - 3, "Ctrl+Y should redo the nudge burst");

		context.getInput().holdShift();
		context.getInput().pressKey(InputConstants.KEY_RIGHT);
		context.getInput().releaseShift();
		context.waitTicks(2);
		check(context.computeOnClient(mc -> fpsHud().getTrueBounds().x()) == x0 + 7, "Shift+Arrow should nudge ten pixels");

		undo(context);
		check(context.computeOnClient(mc -> fpsHud().getTrueBounds().x()) == x0, "undo should cover the shifted nudge too");

		// Delete hides the selected element; undo brings it back.
		context.getInput().pressKey(InputConstants.KEY_DELETE);
		context.waitTicks(2);
		check(!context.computeOnClient(mc -> fpsHud().isEnabled()), "Delete should disable the selected element");
		undo(context);
		check(context.computeOnClient(mc -> fpsHud().isEnabled()), "undo should re-enable the hidden element");

		// Re-select for the drag below (hide cleared the selection).
		clickGui(context, scale, fpsCenter(context));
		context.waitTicks(2);
		check(context.computeOnClient(mc -> editor(mc).selectedEntryId()) != null, "the element should be selectable again");
	}

	/**
	 * Drag the element to the exact screen centre with the help panel open (H): the screenshot
	 * shows the accent snap guides, the snap hint pill, the selection handles, the toolbar and
	 * the help panel together.
	 */
	private void dragWithGuides(ClientGameTestContext context, double scale) {
		int[] start = fpsCenter(context);
		int[] centre = context.computeOnClient(mc -> new int[]{
			mc.getWindow().getGuiScaledWidth() / 2, mc.getWindow().getGuiScaledHeight() / 2
		});
		context.getInput().setCursorPos(start[0] * scale, start[1] * scale);
		context.waitTicks(2); // the editor picks move/resize from the hovered element while rendering
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
		context.getInput().pressKey(InputConstants.KEY_H); // help stays reachable mid-drag
		context.getInput().setCursorPos(centre[0] * scale, centre[1] * scale);
		context.waitTicks(3);
		int[] landed = context.computeOnClient(mc -> {
			var b = fpsHud().getTrueBounds();
			return new int[]{b.x() + b.width() / 2, b.y() + b.height() / 2};
		});
		check(Math.abs(landed[0] - centre[0]) <= 4 && Math.abs(landed[1] - centre[1]) <= 4,
			"dragging to the centre should snap the element's centre to it, landed " + landed[0] + "," + landed[1]);
		check(context.computeOnClient(mc -> editor(mc).isHelpOpen()), "H should keep the help panel open mid-drag");
		context.takeScreenshot("hud-editor-v2-guides");
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
		context.getInput().pressKey(InputConstants.KEY_H); // close the panel so later left clicks pass through
		context.waitTicks(2);
		check(context.computeOnClient(mc -> editor(mc).undoDepth()) == 1, "a real drag should record one undo entry");

		// Step off the centre so later right-clicks cannot hit the crosshair instead.
		context.getInput().holdShift();
		context.getInput().pressKey(InputConstants.KEY_DOWN);
		context.waitTicks(1);
		context.getInput().pressKey(InputConstants.KEY_RIGHT);
		context.waitTicks(1);
		context.getInput().pressKey(InputConstants.KEY_RIGHT);
		context.getInput().releaseShift();
		context.waitTicks(2);
	}

	/** Context menu: anchor picker, reset, settings through the menu, done chip. */
	private void contextMenu(ClientGameTestContext context, double scale) {
		rightClickGui(context, scale, fpsCenter(context));
		context.waitTicks(2);
		check(context.computeOnClient(mc -> editor(mc).isContextMenuOpen()), "right-clicking an element should open the context menu");
		String menuTarget = context.computeOnClient(mc -> editor(mc).contextMenuEntryId());
		check(menuTarget != null && menuTarget.endsWith("fpshud"),
			"the context menu should target the clicked element, got " + menuTarget);
		context.takeScreenshot("hud-editor-v2-context");

		// 3x3 anchor picker: pick the centre cell; the anchor option changes, undo restores it.
		int[] cell = context.computeOnClient(mc -> editor(mc).contextMenuAnchorCenter(AnchorPoint.MIDDLE_MIDDLE));
		clickGui(context, scale, cell);
		context.waitTicks(2);
		check(context.computeOnClient(mc -> HudEditorAnchors.current(fpsHud())) == AnchorPoint.MIDDLE_MIDDLE,
			"the anchor picker should set the anchor");
		check(context.computeOnClient(mc -> editor(mc).isContextMenuOpen()),
			"picking an anchor should keep the menu open for comparison");
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitTicks(2);
		check(!context.computeOnClient(mc -> editor(mc).isContextMenuOpen()), "ESC should close the context menu");
		undo(context);
		check(context.computeOnClient(mc -> HudEditorAnchors.current(fpsHud())) == AnchorPoint.TOP_LEFT,
			"undo should restore the previous anchor");

		// Reset returns the element to its configured default bounds; undo brings it back.
		rightClickGui(context, scale, fpsCenter(context));
		context.waitTicks(2);
		int beforeReset = context.computeOnClient(mc -> fpsHud().getTrueBounds().x());
		clickGui(context, scale, context.computeOnClient(mc -> editor(mc).contextMenuActionCenter("reset")));
		context.waitTicks(2);
		check(context.computeOnClient(mc -> fpsHud().getTrueBounds().x()) <= 1
				&& context.computeOnClient(mc -> fpsHud().getTrueBounds().y()) <= 1,
			"Reset should return the element to its default bounds");
		undo(context);
		check(context.computeOnClient(mc -> fpsHud().getTrueBounds().x()) == beforeReset,
			"undo should restore the pre-reset position");

		// Settings opens the menu's inline settings page for this element (callback hook).
		rightClickGui(context, scale, fpsCenter(context));
		context.waitTicks(2);
		clickGui(context, scale, context.computeOnClient(mc -> editor(mc).contextMenuActionCenter("settings")));
		context.waitTicks(3);
		check(context.computeOnClient(mc -> mc.gui.screen() instanceof ImperiumMenuScreen),
			"Settings should open the Imperium menu");
		check(context.computeOnClient(mc -> ((ImperiumMenuScreen) mc.gui.screen()).isSettingsPanelOpen()),
			"the menu should open with the element's settings panel showing");
		context.takeScreenshot("hud-editor-v2-menu-settings");
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitTicks(2);
		check(context.computeOnClient(mc -> !((ImperiumMenuScreen) mc.gui.screen()).isSettingsPanelOpen()),
			"the first ESC should close the settings panel");
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitTicks(2);
		check(context.computeOnClient(mc -> mc.gui.screen() instanceof ImperiumHudEditScreen),
			"the second ESC should return to the editor");

		// Done closes the editor.
		clickGui(context, scale, context.computeOnClient(mc -> editor(mc).chipCenter("done")));
		context.waitTicks(2);
		check(context.computeOnClient(mc -> mc.gui.screen() == null), "the Done chip should close the editor");
	}

	// ── helpers ────────────────────────────────────────────────────────────────────────────────

	private static void undo(ClientGameTestContext context) {
		context.getInput().holdControl();
		context.getInput().pressKey(InputConstants.KEY_Z);
		context.getInput().releaseControl();
		context.waitTicks(2);
	}

	private static void redo(ClientGameTestContext context) {
		context.getInput().holdControl();
		context.getInput().pressKey(InputConstants.KEY_Y);
		context.getInput().releaseControl();
		context.waitTicks(2);
	}

	private static int[] fpsCenter(ClientGameTestContext context) {
		return context.computeOnClient(mc -> {
			var b = fpsHud().getTrueBounds();
			return new int[]{b.x() + b.width() / 2, b.y() + b.height() / 2};
		});
	}

	private static HudEntry fpsHud() {
		return HudManager.getInstance().getEntries().stream()
			.filter(e -> e.getId().toString().endsWith("fpshud"))
			.findFirst().orElseThrow(() -> new AssertionError("FPS HUD not registered"));
	}

	private static ImperiumHudEditScreen editor(Minecraft mc) {
		if (mc.gui.screen() instanceof ImperiumHudEditScreen screen) return screen;
		throw new AssertionError("expected the HUD editor, got " + mc.gui.screen());
	}

	// TestInput positions the cursor in window pixels; the editor layout is GUI-scaled.
	private static void clickGui(ClientGameTestContext context, double scale, int[] guiPos) {
		context.getInput().setCursorPos(guiPos[0] * scale, guiPos[1] * scale);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
	}

	private static void rightClickGui(ClientGameTestContext context, double scale, int[] guiPos) {
		context.getInput().setCursorPos(guiPos[0] * scale, guiPos[1] * scale);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
	}

	private static double scale(ClientGameTestContext context) {
		return context.computeOnClient(mc -> mc.getWindow().getGuiScale());
	}

	private static void setTheme(ClientGameTestContext context, MenuTheme theme) {
		context.runOnClient(mc -> AxolotlClient.config().menuTheme.set(theme));
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
