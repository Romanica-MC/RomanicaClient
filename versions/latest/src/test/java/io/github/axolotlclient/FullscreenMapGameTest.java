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
import io.github.axolotlclient.modules.minimap.FullscreenMap;
import io.github.axolotlclient.modules.minimap.FullscreenMapScreen;
import io.github.axolotlclient.modules.waypoints.Waypoint;
import io.github.axolotlclient.modules.waypoints.WaypointManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Exercises the fullscreen map end to end in a real singleplayer world: the M keybind opens
 * the screen, the shared chunk-colour sampler actually fills its bigger grid from real
 * terrain, the view starts centred on the player, waypoint dots + labels draw, dragging pans
 * the view, the scroll wheel changes the zoom (with a full resample), and M closes it again.
 */
public class FullscreenMapGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this suite is render-heavy, see ImperiumGametestSubsets
		GametestBookkeeping.executed(getClass().getSimpleName());
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();

			context.runOnClient(mc -> {
				WaypointManager.getInstance().add(new Waypoint("map-test",
					mc.player.getX() + 40, mc.player.getY(), mc.player.getZ() - 25,
					mc.player.level().dimension().identifier().toString(), 0xFF55FF55, true, false));
			});

			try {
				// Terrain completeness is checked at zoom 1 (the 256x256 grid then spans
				// ~256x256 blocks, nearly all of it inside the loaded area); the interactive
				// part and screenshots run at the default coarse zoom 4.
				context.runOnClient(mc -> FullscreenMap.getInstance().zoom.set(1));
				context.getInput().pressKey(InputConstants.KEY_M);
				context.waitForScreen(FullscreenMapScreen.class);
				// 256x256 texels at the sampler's 8192/tick budget fill in ~8 ticks.
				context.waitTicks(35);

				testTerrainSampled(context);
				context.runOnClient(mc -> FullscreenMap.getInstance().zoom.set(4));
				context.waitTicks(12); // zoom change re-samples the whole grid

				testViewStartsOnPlayer(context);
				testWaypointsDraw(context);
				context.takeScreenshot("fullscreen-map-open");

				testDragPans(context);
				context.takeScreenshot("fullscreen-map-panned");

				testScrollZooms(context);
				context.takeScreenshot("fullscreen-map-zoomed");

				// M closes the map again.
				context.getInput().pressKey(InputConstants.KEY_M);
				context.waitFor(mc -> mc.gui.screen() == null);
				context.waitTicks(3);
			} finally {
				context.runOnClient(mc -> {
					if (mc.gui.screen() instanceof FullscreenMapScreen) {
						mc.gui.setScreen(null);
					}
					FullscreenMap.getInstance().zoom.set(4);
					WaypointManager.getInstance().getWaypoints().removeIf(w -> w.name().equals("map-test"));
					WaypointManager.getInstance().save();
				});
			}
		}
	}

	private void testTerrainSampled(ClientGameTestContext context) {
		int sampled = context.computeOnClient(mc -> screen(mc).getMapTexture().sampledTexels());
		check(sampled > 256 * 256 / 3,
			"at zoom 1 the fullscreen grid must sample the loaded terrain, got " + sampled + "/65536 texels");
		check(!context.computeOnClient(mc -> screen(mc).getMapTexture().hasPendingWork()),
			"the grid should finish sampling within the tick budget");
	}

	private void testViewStartsOnPlayer(ClientGameTestContext context) {
		double[] player = context.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getZ()});
		double centerX = context.computeOnClient(mc -> screen(mc).getViewCenterX());
		double centerZ = context.computeOnClient(mc -> screen(mc).getViewCenterZ());
		check(Math.abs(centerX - player[0]) <= 0.5 && Math.abs(centerZ - player[1]) <= 0.5,
			"the map must open centred on the player (center=" + centerX + "/" + centerZ
				+ ", player=" + player[0] + "/" + player[1] + ")");
	}

	private void testWaypointsDraw(ClientGameTestContext context) {
		int drawn = context.computeOnClient(mc -> screen(mc).getDrawnWaypoints());
		check(drawn >= 1, "the nearby test waypoint must be drawn as a dot, got " + drawn);
	}

	private void testDragPans(ClientGameTestContext context) {
		double beforeX = context.computeOnClient(mc -> screen(mc).getViewCenterX());
		double beforeZ = context.computeOnClient(mc -> screen(mc).getViewCenterZ());
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		int[] size = context.computeOnClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
		// Drag from the window centre towards bottom-right: the view centre moves north-west
		// of the old one (content follows the cursor).
		double cx = size[0] / 2.0D, cy = size[1] / 2.0D;
		context.getInput().setCursorPos(cx, cy);
		context.waitTicks(2);
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
		context.getInput().setCursorPos(cx + 80 * scale, cy + 60 * scale);
		context.waitTicks(2);
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
		double afterX = context.computeOnClient(mc -> screen(mc).getViewCenterX());
		double afterZ = context.computeOnClient(mc -> screen(mc).getViewCenterZ());
		check(afterX < beforeX - 10 && afterZ < beforeZ - 10,
			"dragging south-east must pan the view north-west (x: " + beforeX + "->" + afterX
				+ ", z: " + beforeZ + "->" + afterZ + ")");
	}

	private void testScrollZooms(ClientGameTestContext context) {
		context.getInput().scroll(1.0D);
		context.waitTicks(12); // zoom change invalidates every texel; ~8 ticks to resample
		check(context.computeOnClient(mc -> FullscreenMap.getInstance().zoom.get()) == 3,
			"scrolling up must zoom in from 4 to 3 blocks per pixel");
		// At zoom 3 most of the grid is outside the loaded area, so only require that the
		// resample actually ran to completion over loaded terrain.
		int sampled = context.computeOnClient(mc -> screen(mc).getMapTexture().sampledTexels());
		check(sampled > 2000 && !context.computeOnClient(mc -> screen(mc).getMapTexture().hasPendingWork()),
			"the resample after zooming must refill the loaded terrain, got " + sampled);
	}

	private static FullscreenMapScreen screen(net.minecraft.client.Minecraft mc) {
		if (mc.gui.screen() instanceof FullscreenMapScreen screen) {
			return screen;
		}
		throw new AssertionError("expected FullscreenMapScreen, got " + mc.gui.screen());
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
