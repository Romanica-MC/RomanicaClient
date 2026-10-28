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

import io.github.axolotlclient.modules.hud.HudManager;
import io.github.axolotlclient.modules.hud.gui.hud.simple.WaypointsHud;
import io.github.axolotlclient.modules.waypoints.Waypoint;
import io.github.axolotlclient.modules.waypoints.WaypointManager;
import io.github.axolotlclient.util.LightLevelOverlay;
import io.github.axolotlclient.util.WorldProjection;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * The world-space rendering foundation (the parity sheet's "no projection math in codebase"
 * gap): {@link WorldProjection} pure math, the 3D waypoint tracers riding on it, and the
 * light-level overlay.
 *
 * <p>The pure pins lock the projection contract (frustum bounds, behind-camera rejection,
 * center-of-screen for the look direction, yaw/pitch conventions, edge clamping/arrow
 * direction). The live part cross-checks the pure math against vanilla's own
 * {@code GameRenderer.projectPointToScreen} (same camera, same frame — if the FOV/aspect/sign
 * conventions drift even slightly, the NDC comparison fails), renders tracer markers for real
 * waypoints in a real world, and drives the light sampler through a midnight + torch scenario
 * where both the safe and unsafe colour paths provably engage.</p>
 */
public class WorldSpaceRenderingGameTest implements FabricClientGameTest {

	private static final int SCREEN_W = 1920;
	private static final int SCREEN_H = 1080;
	private static final double ASPECT = 16.0 / 9.0;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this suite is render-heavy, see ImperiumGametestSubsets
		GametestBookkeeping.executed(getClass().getSimpleName());
		pureMathPins();
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();

			vanillaProjectionCrossCheck(context);
			testWaypointTracers(context);
			testLightLevelOverlay(context, world);
		} finally {
			resetState(context);
		}
	}

	// ---------------------------------------------------------------- pure math

	private void pureMathPins() {
		WorldProjection center = WorldProjection.of(0, 0, 0, 0, 0, 70, ASPECT, SCREEN_W, SCREEN_H);
		double tan35 = Math.tan(Math.toRadians(35));

		// The look direction projects dead center.
		WorldProjection.ScreenPos ahead = center.project(0, 0, 10);
		check(!ahead.behindCamera(), "a point along the look direction must not be behind the camera");
		check(near(ahead.x(), SCREEN_W / 2.0), "look direction must project to the horizontal center, got " + ahead.x());
		check(near(ahead.y(), SCREEN_H / 2.0), "look direction must project to the vertical center, got " + ahead.y());

		// Frustum bounds: at 70 degrees vertical FOV, +-tan(35) vertical offset (scaled by the
		// aspect for the horizontal axis) lands exactly on the screen borders. Yaw 0 faces +Z,
		// screen-right is -X (west), so a -X offset moves the marker right.
		check(near(center.project(-10 * ASPECT * tan35, 0, 10).x(), SCREEN_W, 0.5), "right FOV bound must be the right screen edge");
		check(near(center.project(10 * ASPECT * tan35, 0, 10).x(), 0, 0.5), "left FOV bound must be the left screen edge");
		check(near(center.project(0, 10 * tan35, 10).y(), 0, 0.5), "upper FOV bound must be the top screen edge");
		check(near(center.project(0, -10 * tan35, 10).y(), SCREEN_H, 0.5), "lower FOV bound must be the bottom screen edge");

		// Behind-camera rejection, including the exact eye position.
		check(center.project(0, 0, -5).behindCamera(), "a point behind the camera must be rejected");
		check(center.project(0, 0, 0).behindCamera(), "the eye position itself has zero depth and must count as behind");

		// Yaw convention: yaw 90 faces -X (west), so a western point is centered, and the exact
		// opposite direction is behind the camera (a merely perpendicular point sits ON the
		// camera plane, where the depth sign is float noise — not a stable pin).
		WorldProjection yawed = WorldProjection.of(0, 0, 0, 90, 0, 70, ASPECT, SCREEN_W, SCREEN_H);
		check(near(yawed.project(-10, 0, 0).x(), SCREEN_W / 2.0), "yaw 90 must face west (-X)");
		check(yawed.project(10, 0, 0).behindCamera(), "east must be behind the camera at yaw 90 (west-facing)");

		// Pitch convention: pitch 90 looks straight down; a point directly below the eye centers
		// (an off-axis point would legitimately sit off-centre — screen-right stays west), and a
		// point south of it appears above the screen centre (the horizon stays at screen-up).
		WorldProjection down = WorldProjection.of(0, 0, 0, 0, 90, 70, ASPECT, SCREEN_W, SCREEN_H);
		WorldProjection.ScreenPos below = down.project(0, -4, 0);
		check(!below.behindCamera() && near(below.x(), SCREEN_W / 2.0) && near(below.y(), SCREEN_H / 2.0, 1.0),
			"straight down must center a point below the eye");
		check(down.project(0.5, -4, 10.5).y() < SCREEN_H / 2.0, "looking straight down at yaw 0, south must sit above the screen centre");

		// Light math: spawn-safe is light > 7 after the sky-darkening scale.
		check(LightLevelOverlay.effectiveLight(3, 15, 4) == 11, "effective light must be max(block, sky - darken)");
		check(LightLevelOverlay.isSpawnSafe(0, 15, 0), "open sky at full brightness must be spawn-safe");
		check(!LightLevelOverlay.isSpawnSafe(0, 15, 11), "open sky at midnight must be spawn-unsafe");
		check(LightLevelOverlay.isSpawnSafe(8, 0, 15), "block light 8 crosses the threshold and must be safe");
		check(!LightLevelOverlay.isSpawnSafe(7, 0, 0), "block light 7 must still be unsafe (light > 7 is the rule)");

		// Edge clamping: a far-left point clamps to the left border with an arrow pointing left.
		WorldProjection.ScreenPos farLeft = center.project(500, 0, 10);
		check(!farLeft.onScreen(SCREEN_W, SCREEN_H), "a point this far east must be off-screen");
		WorldProjection.ScreenPos clamped = WorldProjection.clampToEdges(farLeft, SCREEN_W, SCREEN_H, 16);
		check(near(clamped.x(), 16, 0.5) && near(clamped.y(), SCREEN_H / 2.0, 0.5),
			"a straight-left point must clamp onto the left border at the vertical centre");
		double leftAngle = WorldProjection.edgeDirection(farLeft, SCREEN_W, SCREEN_H);
		check(Math.cos(leftAngle) < -0.99, "the edge arrow for a left point must point left, angle " + leftAngle);

		// Behind-camera turn direction: facing +Z, a point behind to the west needs a right turn.
		WorldProjection.ScreenPos behindWest = center.project(-50, 0, -100);
		check(behindWest.behindCamera(), "the point must report behind-camera");
		check(Math.cos(WorldProjection.edgeDirection(behindWest, SCREEN_W, SCREEN_H)) > 0.5,
			"behind-west must produce a rightward arrow (west is screen-right at yaw 0)");
		WorldProjection.ScreenPos behindEast = center.project(50, 0, -100);
		check(Math.cos(WorldProjection.edgeDirection(behindEast, SCREEN_W, SCREEN_H)) < -0.5,
			"behind-east must produce a leftward arrow");

		// Distance formatting shared by HUD list and tracers (locale-stable via Locale.ROOT).
		check("42m".equals(WaypointsHud.formatDistance(42.4)), "sub-kilometre distances render as metres");
		String km = WaypointsHud.formatDistance(1234);
		check("1.2km".equals(km), "kilometre distances render compact, got '" + km + "'");
	}

	// ---------------------------------------------------------------- live: projection vs vanilla

	/**
	 * The pure math must agree with vanilla's own projector on the live camera: same frame,
	 * NDC-space comparison, points built from the camera's actual basis vectors. This pins the
	 * FOV source (vertical, degrees), the aspect and both screen axes' signs.
	 */
	private void vanillaProjectionCrossCheck(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			WorldProjection mine = WorldProjection.capture(mc);
			Camera camera = mc.gameRenderer.mainCamera();
			Vec3 eye = camera.position();
			int w = mc.getWindow().getGuiScaledWidth();
			int h = mc.getWindow().getGuiScaledHeight();
			check(w > 0 && h > 0, "the client must have a GUI-scaled size");

			List<Vec3> points = new ArrayList<>();
			for (double forward : new double[]{5, 15, 40}) {
				points.add(offset(eye, camera, forward, 0, 0));
				points.add(offset(eye, camera, forward, 2, 1));
				points.add(offset(eye, camera, forward, -3, 2));
				points.add(offset(eye, camera, forward, 1, -2));
			}
			for (Vec3 world : points) {
				Vec3 vanilla = mc.gameRenderer.projectPointToScreen(world);
				WorldProjection.ScreenPos projected = mine.project(world.x, world.y, world.z);
				check(!projected.behindCamera(), "a point in front of the camera must project, at " + world);
				double myNdcX = 2.0 * projected.x() / w - 1.0;
				double myNdcY = 1.0 - 2.0 * projected.y() / h;
				check(Math.abs(myNdcX - vanilla.x) < 0.02,
					"ndcX drift vs vanilla at " + world + ": " + myNdcX + " vs " + vanilla.x);
				check(Math.abs(myNdcY - vanilla.y) < 0.02,
					"ndcY drift vs vanilla at " + world + ": " + myNdcY + " vs " + vanilla.y);
			}
		});
	}

	/** eye + forward*f + right*right + up*up, using the camera's own basis (right = -left). */
	private static Vec3 offset(Vec3 eye, Camera camera, double forward, double right, double up) {
		var f = camera.forwardVector();
		var l = camera.leftVector();
		var u = camera.upVector();
		return new Vec3(
			eye.x + f.x() * forward - l.x() * right + u.x() * up,
			eye.y + f.y() * forward - l.y() * right + u.y() * up,
			eye.z + f.z() * forward - l.z() * right + u.z() * up);
	}

	// ---------------------------------------------------------------- live: tracers

	private void testWaypointTracers(ClientGameTestContext context) {
		WaypointsHud entry = (WaypointsHud) HudManager.getInstance().get(WaypointsHud.ID);
		check(entry != null, "the waypoints HUD entry must be registered");

		BlockPos feet = context.computeOnClient(mc -> mc.player.blockPosition());
		String dimension = context.computeOnClient(mc -> WaypointsHud.currentDimension(mc.player));
		WaypointManager manager = WaypointManager.getInstance();
		context.runOnClient(mc -> {
			entry.setEnabled(true);
			entry.tracers.set(true);
			manager.add(new Waypoint("Look Target", feet.getX() + 0.5, feet.getY(), feet.getZ() + 12,
				dimension, 0xFF55FF55, true, false));
			manager.setDeathPoint(feet.getX() - 8, feet.getY(), feet.getZ() - 6, dimension,
				Waypoint.DEATH_COLOR, true, 1);
		});
		check(manager.getForDimension(dimension).size() == 2, "both waypoints must be in the current dimension");

		// Look south (+Z) straight at the first waypoint: diamond + label near the centre, the
		// death point behind-east clamps to the left edge with an arrow.
		context.runOnClient(mc -> {
			mc.player.setYRot(0);
			mc.player.setXRot(5);
		});
		context.waitTicks(15);
		context.takeScreenshot("worldspace-tracers-on-screen");

		// Turn away: both waypoints are behind the camera now — edge-clamped arrows only.
		context.runOnClient(mc -> {
			mc.player.setYRot(180);
			mc.player.setXRot(0);
		});
		context.waitTicks(15);
		context.takeScreenshot("worldspace-tracers-edge-clamped");

		// Tracers off: the frame must render without the markers (option gate).
		context.runOnClient(mc -> entry.tracers.set(false));
		context.waitTicks(5);
		context.runOnClient(mc -> entry.tracers.set(true));
	}

	// ---------------------------------------------------------------- live: light overlay

	private void testLightLevelOverlay(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockPos feet = context.computeOnClient(mc -> mc.player.blockPosition());

		// Daylight baseline: the radius around the player samples quickly (r=8 -> 289 columns).
		context.runOnClient(mc -> {
			AxolotlClient.config().lightOverlay.set(true);
			AxolotlClient.config().lightOverlayRadius.set(8);
		});
		context.waitTicks(10);
		check(LightLevelOverlay.cachedSampleCount() > 100,
			"the sampler should have covered most of the 8-radius disc, got " + LightLevelOverlay.cachedSampleCount());
		LightLevelOverlay.Sample own = LightLevelOverlay.cachedSample(feet.getX(), feet.getZ());
		check(own != null && own.safe(), "open ground at full daylight must sample spawn-safe");

		// Midnight: sky light no longer helps, the same column flips to unsafe.
		world.getServer().runOnServer(server -> moveClock(server, ClockTimeMarkers.MIDNIGHT));
		context.waitTicks(40);
		LightLevelOverlay.Sample night = LightLevelOverlay.cachedSample(feet.getX(), feet.getZ());
		check(night != null && !night.safe(), "open ground at midnight must sample spawn-unsafe");

		// A torch nearby lights its surroundings above the threshold again.
		world.getServer().runOnServer(server -> {
			int torchX = feet.getX() + 2;
			int torchZ = feet.getZ();
			int y = server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, torchX, torchZ);
			server.overworld().setBlockAndUpdate(new BlockPos(torchX, y, torchZ), Blocks.TORCH.defaultBlockState());
		});
		context.waitTicks(40);
		LightLevelOverlay.Sample torchLit = LightLevelOverlay.cachedSample(feet.getX() + 2, feet.getZ());
		check(torchLit != null && torchLit.safe(), "the column lit by the torch must sample spawn-safe");
		int blockLight = context.computeOnClient(mc ->
			mc.level.getBrightness(LightLayer.BLOCK, new BlockPos(feet.getX() + 2, torchLit.y() + 1, feet.getZ())));
		check(blockLight >= 13, "the torch should put block light ~14 next to it, got " + blockLight);

		// Angle the camera down so the screenshot actually showcases the ground tints
		// (a horizon view at midnight makes translucent plates nearly invisible).
		context.runOnClient(mc -> mc.player.setXRot(55));
		context.waitTicks(5);
		int emitted = LightLevelOverlay.lastEmittedMarkers();
		check(emitted > 0, "the render path must emit markers each frame while enabled, got " + emitted);
		context.takeScreenshot("worldspace-light-overlay-midnight");

		// Off: the sampler stops and the cache drains.
		context.runOnClient(mc -> AxolotlClient.config().lightOverlay.set(false));
		context.waitTicks(3);
		check(LightLevelOverlay.cachedSampleCount() == 0, "disabling the overlay must clear the sample cache");
	}

	private static void moveClock(MinecraftServer server, net.minecraft.resources.ResourceKey<net.minecraft.world.clock.ClockTimeMarker> marker) {
		Holder<net.minecraft.world.clock.WorldClock> clock =
			server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
		server.overworld().clockManager().moveToTimeMarker(clock, marker);
	}

	// ---------------------------------------------------------------- cleanup

	private void resetState(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			WaypointManager.getInstance().getWaypoints().clear();
			WaypointManager.getInstance().save();
			AxolotlClient.config().lightOverlay.set(false);
			AxolotlClient.config().lightOverlayRadius.set(16);
		});
	}

	private static boolean near(double value, double expected) {
		return near(value, expected, 0.01);
	}

	private static boolean near(double value, double expected, double tolerance) {
		return Math.abs(value - expected) <= tolerance;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
