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

import io.github.axolotlclient.modules.hud.HudManager;
import io.github.axolotlclient.modules.minimap.MinimapHud;
import io.github.axolotlclient.modules.waypoints.Waypoint;
import io.github.axolotlclient.modules.waypoints.WaypointManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;

/**
 * Exercises the minimap end to end in a real singleplayer world: the chunk-colour grid
 * actually samples terrain (MapColor pipeline + client chunk access), the anchor tracks
 * the player with zoom-quantised recentering, movement shifts content instead of blanking
 * it, zoom changes trigger a full resample, grid sizing follows shape/rotation, the render
 * path registers the dynamic texture, and waypoints + the circle mask render without
 * throwing.
 */
public class MinimapGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this suite is render-heavy, see ImperiumGametestSubsets
		GametestBookkeeping.executed(getClass().getSimpleName());
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();

			MinimapHud hud = hud();
			context.runOnClient(mc -> {
				hud.setEnabled(true);
				hud.zoom.set(1);
				hud.mapSize.set(128);
				hud.shape.set(MinimapHud.Shape.SQUARE);
				hud.rotation.set(MinimapHud.Rotation.NORTH_LOCK);
				hud.onBoundsUpdate();
			});

			testGridFillsFromRealTerrain(context);
			testAnchorTracksPlayer(context);
			testMovementShiftsContent(context, world);
			testZoomChangeResamples(context);
			testGridSizingFollowsShapeAndRotation(context);
			testRenderPathAndWaypoints(context);
		} finally {
			context.runOnClient(mc -> {
				MinimapHud hud = hud();
				hud.setEnabled(false);
				hud.zoom.set(2);
				hud.mapSize.set(128);
				hud.shape.set(MinimapHud.Shape.SQUARE);
				hud.rotation.set(MinimapHud.Rotation.NORTH_LOCK);
				WaypointManager.getInstance().getWaypoints().removeIf(w -> w.name().equals("minimap-test"));
				WaypointManager.getInstance().save();
			});
		}
	}

	private void testGridFillsFromRealTerrain(ClientGameTestContext context) {
		context.waitTicks(30); // full 128x128 grid at 8192 texels/tick fills in ~2 ticks
		int sampled = context.computeOnClient(mc -> hud().getTexture().sampledTexels());
		check(sampled > 128 * 128 / 2,
			"minimap grid must sample real terrain, got " + sampled + "/16384 texels");
		check(!context.computeOnClient(mc -> hud().getTexture().hasPendingWork()),
			"grid should finish sampling within the tick budget");
	}

	private void testAnchorTracksPlayer(ClientGameTestContext context) {
		double playerX = context.computeOnClient(mc -> mc.player.getX());
		long anchorX = context.computeOnClient(mc -> hud().getTexture().getAnchorX());
		check(Math.abs(anchorX - playerX) <= 0.5,
			"anchor must sit on the zoom-quantised player position (anchor=" + anchorX + ", player=" + playerX + ")");
	}

	private void testMovementShiftsContent(ClientGameTestContext context, TestSingleplayerContext world) {
		long anchorBefore = context.computeOnClient(mc -> hud().getTexture().getAnchorX());
		int sampledBefore = context.computeOnClient(mc -> hud().getTexture().sampledTexels());
		world.getServer().runCommand("tp @p ~16 ~ ~");
		context.waitFor(mc -> mc.player.getX() - 0.5 > anchorBefore + 8);
		context.waitTicks(3);
		long anchorAfter = context.computeOnClient(mc -> hud().getTexture().getAnchorX());
		int sampledAfter = context.computeOnClient(mc -> hud().getTexture().sampledTexels());
		check(anchorAfter - anchorBefore >= 15 && anchorAfter - anchorBefore <= 17,
			"16-block teleport must shift the anchor by ~16 (got " + (anchorAfter - anchorBefore) + ")");
		check(sampledAfter >= sampledBefore - 256,
			"shifting must reuse sampled content, not blank the map (before=" + sampledBefore + ", after=" + sampledAfter + ")");
	}

	private void testZoomChangeResamples(ClientGameTestContext context) {
		context.runOnClient(mc -> hud().zoom.set(2));
		context.waitTicks(12); // zoom change invalidates every texel; 16384 texels at 8192/tick
		int sampled = context.computeOnClient(mc -> hud().getTexture().sampledTexels());
		check(sampled > 128 * 128 * 3 / 10,
			"zoom change must trigger a full resample (got " + sampled + " texels)");
		check(!context.computeOnClient(mc -> hud().getTexture().hasPendingWork()),
			"resample must complete within the waited ticks");
		long anchorX = context.computeOnClient(mc -> hud().getTexture().getAnchorX());
		check(anchorX % 2 == 0, "anchor must quantise to the zoom grid at zoom 2 (got " + anchorX + ")");
		context.runOnClient(mc -> hud().zoom.set(1));
		context.waitTicks(12);
	}

	private void testGridSizingFollowsShapeAndRotation(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			hud().shape.set(MinimapHud.Shape.SQUARE);
			hud().rotation.set(MinimapHud.Rotation.FOLLOW_CAMERA);
		});
		context.waitTicks(2);
		check(context.computeOnClient(mc -> hud().getTexture().getGridSize()) == 192,
			"square + follow-camera needs the oversize grid (128*1.5)");
		context.runOnClient(mc -> hud().shape.set(MinimapHud.Shape.CIRCLE));
		context.waitTicks(2);
		check(context.computeOnClient(mc -> hud().getTexture().getGridSize()) == 128,
			"circle never needs the oversize grid");
	}

	private void testRenderPathAndWaypoints(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			Minecraft client = Minecraft.getInstance();
			WaypointManager.getInstance().add(new Waypoint("minimap-test", client.player.getX() + 10,
				client.player.getY(), client.player.getZ(), client.player.level().dimension().identifier().toString(),
				0xFF55FF55, true, false));
		});
		context.waitTicks(10); // frames render with the HUD enabled: blit, dots, label, mask, arrow, north
		check(context.computeOnClient(mc -> hud().isRegistered()),
			"the render path must have registered the map texture with the texture manager");
	}

	private static MinimapHud hud() {
		return (MinimapHud) HudManager.getInstance().get(MinimapHud.ID);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
