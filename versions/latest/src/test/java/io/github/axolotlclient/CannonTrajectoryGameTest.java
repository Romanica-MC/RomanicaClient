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

import io.github.axolotlclient.modules.legions.CannonTrajectory;
import io.github.axolotlclient.modules.legions.TntTrajectory;
import io.github.axolotlclient.modules.legions.TntTrajectory.Point;
import io.github.axolotlclient.modules.legions.TntTrajectory.Result;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Pins the cannon trajectory tool. Two halves:
 *
 * <ul>
 *   <li><strong>Pure physics</strong> ({@link TntTrajectory}, no world): the closed-form
 *       horizontal range under drag, the apex shape of a straight-up shot, terminal fall
 *       displacement, and collision stops against a floor and a wall — the constants are the
 *       ones javap found in 26.3's {@code PrimedTnt} (gravity 0.04 before the move, drag 0.98
 *       after it).</li>
 *   <li><strong>Live rendering</strong> in a real world: holding TNT submits gizmo arc
 *       segments every frame (Gizmos.line throws outside a collection window, so surviving
 *       frames plus a non-zero segment count prove the BEFORE_GIZMOS path), and a summoned
 *       primed TNT gets its remaining flight predicted.</li>
 * </ul>
 */
public class CannonTrajectoryGameTest implements FabricClientGameTest {

	private static final double EPSILON = 1e-9;
	private static final TntTrajectory.SolidCheck NEVER_SOLID = (x, y, z) -> false;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this suite is render-heavy, see ImperiumGametestSubsets
		GametestBookkeeping.executed(getClass().getSimpleName());
		launchPointAndHorizon();
		horizontalRangeMatchesClosedForm();
		straightUpShotHasASingleApex();
		fallApproachesTerminalDisplacement();
		floorCollisionStopsAboveGround();
		wallCollisionStopsBeforeWall();

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			CannonTrajectory module = CannonTrajectory.getInstance();

			// Hold TNT in the main hand -> the predicted arc must render every frame. The
			// generous window matters: right after join the first rendered level frames can
			// lag behind chunk upload, and the assertion reads whatever the LAST frame drew.
			context.runOnClient(mc -> mc.player.getInventory().setSelectedItem(new ItemStack(Items.TNT)));
			context.waitTicks(30);
			int heldSegments = context.computeOnClient(mc -> module.getLastDrawnSegments());
			check(heldSegments > 0, "holding TNT must submit trajectory segments, got " + heldSegments);
			context.takeScreenshot("cannon-trajectory-held-tnt");

			// A primed TNT in flight gets its remaining arc + landing box predicted.
			// Summoned well away from the player so its blast cannot kill them mid-test.
			world.getServer().runCommand("summon minecraft:tnt ~12 ~8 ~12");
			context.waitTicks(5);
			context.runOnClient(mc -> mc.player.getInventory().setSelectedItem(ItemStack.EMPTY));
			context.waitTicks(5);
			int liveSegments = context.computeOnClient(mc -> module.getLastDrawnSegments());
			check(liveSegments > 0, "a primed TNT nearby must submit prediction segments, got " + liveSegments);
			context.takeScreenshot("cannon-trajectory-live-tnt");

			// Empty hands and no TNT left in range: nothing is drawn.
			context.waitTicks(90); // the summoned fuse (80 ticks) burns out
			int idleSegments = context.computeOnClient(mc -> module.getLastDrawnSegments());
			check(idleSegments == 0, "no held TNT and no live TNT must draw nothing, got " + idleSegments);
		} finally {
			context.runOnClient(mc -> {
				if (mc.player != null) {
					mc.player.getInventory().setSelectedItem(ItemStack.EMPTY);
				}
			});
		}
	}

	private void launchPointAndHorizon() {
		Result result = TntTrajectory.integrate(1, 2, 3, 0, 0, 0, 37, NEVER_SOLID);
		Point first = result.points().getFirst();
		check(first.x() == 1 && first.y() == 2 && first.z() == 3 && first.tick() == 0,
			"the launch position must be point 0 of the flight");
		check(result.points().size() == 38, "37 ticks of free flight must sample 38 points, got "
			+ result.points().size());
		check(!result.collided() && result.ticks() == 37, "a free flight runs the whole horizon");
	}

	/**
	 * Drag applies uniformly, so horizontal motion decouples from gravity: after T unobstructed
	 * ticks the displacement is v*(1-d^T)/(1-d) with d = 0.98 — an independent closed form the
	 * integrator must match exactly.
	 */
	private void horizontalRangeMatchesClosedForm() {
		Result result = TntTrajectory.integrate(0, 1000, 0, 2, 0, 0, 100, NEVER_SOLID);
		double expected = TntTrajectory.horizontalDistance(2, 100);
		check(expected > 86 && expected < 87, "sanity: 2 blocks/tick for 100 ticks covers ~86.7"
			+ " blocks (drag caps the total at v/(1-d) = 100), got " + expected);
		check(Math.abs(result.points().getLast().x() - expected) < EPSILON,
			"horizontal range must match the closed form, got " + result.points().getLast().x()
				+ " expected " + expected);
		check(Math.abs(result.points().getLast().z()) < EPSILON, "no z drift without z velocity");
	}

	private void straightUpShotHasASingleApex() {
		Result result = TntTrajectory.integrate(0, 100, 0, 0, 3, 0, 100, NEVER_SOLID);
		double apex = Double.NEGATIVE_INFINITY;
		int apexTick = -1;
		boolean falling = false;
		for (Point p : result.points()) {
			if (falling && p.y() > apex) {
				check(false, "the arc must rise once and then fall (rose again at tick " + p.tick() + ")");
			}
			if (p.y() > apex) {
				apex = p.y();
				apexTick = p.tick();
			} else {
				falling = true;
			}
		}
		check(apexTick > 30 && apexTick < 60, "a 3/tick shot peaks mid-flight, got tick " + apexTick);
		check(apex > 100 + 50 && apex < 100 + 65, "apex height ~58 blocks, got " + (apex - 100));
		double finalY = result.points().getLast().y();
		check(finalY < apex - 40, "after 100 ticks the shot has fallen well past its apex (apex="
			+ apex + ", y(100)=" + finalY + ")");
	}

	/**
	 * Terminal displacement: the per-tick step follows the move velocity's fixed point
	 * {@code -g/(1-d)} = -2.0 blocks (gravity before the move, drag after it; the post-drag
	 * velocity reads -1.96, the classic quoted figure).
	 */
	private void fallApproachesTerminalDisplacement() {
		// Convergence is geometric at 0.98/tick, so give it 500 ticks (error < 1e-4).
		Result result = TntTrajectory.integrate(0, 10000, 0, 0, 0, 0, 500, NEVER_SOLID);
		var points = result.points();
		double lastStep = points.getLast().y() - points.get(points.size() - 2).y();
		check(Math.abs(lastStep - TntTrajectory.TERMINAL_DISPLACEMENT_PER_TICK) < 0.01,
			"fall step must converge to the terminal displacement (-2.0), got " + lastStep);
		check(Math.abs(TntTrajectory.TERMINAL_DISPLACEMENT_PER_TICK + 2.0) < 1e-9,
			"terminal displacement is -2 blocks/tick");
	}

	private void floorCollisionStopsAboveGround() {
		TntTrajectory.SolidCheck floor = (x, y, z) -> y <= 64;
		Result result = TntTrajectory.integrate(0, 100, 0, 3, 0, 0, 100, floor);
		check(result.collided(), "the arc must stop at the floor");
		check(result.ticks() < 100, "the collision happens before the horizon");
		for (Point p : result.points()) {
			check(p.y() > 64, "no sampled point may be inside the floor (tick " + p.tick() + " at y=" + p.y() + ")");
		}
		check(result.landing().y() > 64 && result.landing().y() < 64 + 2.2,
			"the landing point is the last airborne sample within one step of the floor, got " + result.landing().y());
	}

	private void wallCollisionStopsBeforeWall() {
		TntTrajectory.SolidCheck wall = (x, y, z) -> x >= 10;
		Result result = TntTrajectory.integrate(0, 100, 0, 2, 0, 0, 100, wall);
		check(result.collided(), "the horizontal shot must stop at the wall");
		// Closed form: x after 5 ticks is 2*(1-0.98^5)/0.02 = 9.608 (< 10), tick 6 would be 11.02 (>= 10).
		check(result.points().size() == 6, "the flight stops after 5 moves, got " + result.points().size());
		check(Math.abs(result.landing().x() - TntTrajectory.horizontalDistance(2, 5)) < EPSILON,
			"the wall stop must happen exactly at the last in-front-of-the-wall point");
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
