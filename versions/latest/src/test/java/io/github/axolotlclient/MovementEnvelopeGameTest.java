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

import io.github.axolotlclient.imperium.enforcement.MovementEnvelope;
import io.github.axolotlclient.imperium.enforcement.MovementEnvelope.Result;
import io.github.axolotlclient.imperium.enforcement.MovementEnvelope.State;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Pure unit tests for {@link MovementEnvelope} (W4-C). Runs headless in the client gametest
 * harness like ImperiumProtocolGameTest — no world, just the math. Two families:
 *
 * <ul>
 *   <li><strong>Never clamps vanilla:</strong> every vanilla-achievable per-tick delta
 *       (walk, sprint, sprint-jump burst, ice, speed potion, jump boost, step-up, terminal
 *       fall, elytra/vehicle/fly, water) passes untouched.</li>
 *   <li><strong>Always clamps the server's flag cases:</strong> deltas above the
 *       CheatPreventionListener flat ceiling (scaled by the 0.98 safety factor) come back
 *       clamped to exactly that ceiling, direction preserved, and are therefore strictly below
 *       what the plugin would flag.</li>
 * </ul>
 */
public class MovementEnvelopeGameTest implements FabricClientGameTest {

	private static final double EPSILON = 1e-9;

	@Override
	public void runTest(ClientGameTestContext context) {
		GametestBookkeeping.executed(getClass().getSimpleName());
		vanillaSpeedsNeverClamp();
		cheatSpeedsClampToTheServerEdge();
		verticalModel();
		exemptStates();
		graceWindowDefers();
		clampPreservesDirection();
		legacyTierReferencesSitBelowTheCeiling();
	}

	private void vanillaSpeedsNeverClamp() {
		// Plain walk / sprint on the ground.
		check(!clamp(0.216, 0.0, 0.0, grounded(false, 0)).clampedHorizontal(), "vanilla walk 0.216 must not clamp");
		check(!clamp(0.281, 0.0, 0.0, grounded(true, 0)).clampedHorizontal(), "vanilla sprint 0.281 must not clamp");
		// Sneaking / walking diagonally (0.216 * sqrt(2)/2 per axis, still under).
		check(!clamp(0.15, 0.0, 0.15, grounded(false, 0)).clampedHorizontal(), "diagonal walk must not clamp");
		// Sprint jump burst (~0.56) inside the 2-tick burst window.
		check(!clamp(0.56, 0.42, 0.0, airborne(true, 1)).clampedHorizontal(), "sprint-jump burst 0.56 must not clamp");
		// Decaying arc mid-hop and landing momentum.
		check(!clamp(0.50, -0.10, 0.0, airborne(true, 5)).clampedHorizontal(), "mid-arc 0.50 must not clamp");
		check(!clamp(0.45, 0.0, 0.0, grounded(true, 0)).clampedHorizontal(), "post-hop grounded momentum 0.45 must not clamp");
		// Ice sprint: plugin iceMult 1.4 allowance, vanilla sustained ice sprint fits.
		check(!clamp(0.60, 0.0, 0.0, iceSprint()).clampedHorizontal(), "ice sprint 0.60 must not clamp");
		// Speed II sprint: 0.42 * 1.4 * 1.3 * 0.98 = 0.752 ceiling.
		check(!clamp(0.70, 0.0, 0.0, speedPotionSprint(1)).clampedHorizontal(), "Speed II sprint 0.70 must not clamp");
		// Water: sprint-swim with dolphin's grace / depth strider (~0.5-0.9).
		check(!clamp(0.90, 0.0, 0.0, water()).clampedHorizontal(), "sprint-swim 0.90 must not clamp");
	}

	private void cheatSpeedsClampToTheServerEdge() {
		// Grounded sprint ceiling: 0.42 * 1.3 * 0.98 = 0.535... (below the plugin's 0.546 flag edge).
		Result speed = clamp(1.20, 0.0, 0.0, grounded(true, 0));
		check(speed.clampedHorizontal(), "grounded 1.2/t must clamp");
		checkEquals(MovementEnvelope.horizontalCeiling(grounded(true, 0)), speed.dx(), "clamped to the ceiling");
		check(speed.dx() < 0.42 * 1.3, "clamped horizontal is strictly below the plugin's flag edge");

		// Grounded walk ceiling 0.4116: a no-sprint 0.8/t hack clamps there.
		Result walk = clamp(0.8, 0.0, 0.0, grounded(false, 0));
		check(walk.clampedHorizontal() && walk.dx() < 0.42, "walk-speed hack clamps under the base ceiling");

		// Airborne sustained (past the burst window): ceiling without the 1.7 burst term.
		State sustainedAir = airborne(true, 6);
		Result fly = clamp(2.0, 0.0, 0.0, sustainedAir);
		check(fly.clampedHorizontal(), "sustained airborne 2.0/t must clamp");
		check(fly.dx() < 0.42 * 1.3, "sustained airborne clamps under the no-burst flag edge");

		// A burst-window tick gets the 1.7 allowance but not beyond it.
		State burst = airborne(true, 2);
		Result burstClamp = clamp(3.0, 0.3, 0.0, burst);
		check(burstClamp.clampedHorizontal(), "3.0/t rising in the burst window must still clamp");
		check(burstClamp.dx() < 0.42 * 1.3 * 1.7, "burst clamp stays under the plugin's burst allowance");
		// The burst allowance needs a RISING tick (plugin isJumpBurst dy > 0.1): a falling tick in
		// the window obeys the sustained ceiling, so a cheat cannot spend it while descending.
		Result falling = clamp(0.80, -0.3, 0.0, airborne(true, 1));
		check(falling.clampedHorizontal(), "0.8/t falling must clamp despite being in the burst window");
		checkEquals(MovementEnvelope.horizontalCeiling(airborne(true, 1), -0.3), falling.dx(),
			"falling tick clamps at the sustained ceiling");

		// Water clamp exists but sits above every vanilla swim speed.
		Result swim = clamp(4.0, 0.0, 0.0, water());
		check(swim.clampedHorizontal() && swim.dx() <= MovementEnvelope.WATER_CEILING, "water speed clamps at the water ceiling");
	}

	private void verticalModel() {
		// Jump impulse (0.42) and grounded step-up (0.5-0.6) are legal.
		check(!clamp(0.0, 0.42, 0.0, airborne(false, 0)).clampedVertical(), "jump impulse 0.42 must not clamp");
		check(!clamp(0.0, 0.60, 0.0, grounded(false, 0)).clampedVertical(), "stair step 0.60 must not clamp");
		// Jump Boost II first airborne tick: 0.42 + 0.2 = 0.62 legal; airborne arc ceiling 0.75.
		check(!clamp(0.0, 0.62, 0.0, airborne(1, 1)).clampedVertical(), "Jump Boost II arc must not clamp");
		// Vanilla terminal fall.
		check(!clamp(0.0, -3.92, 0.0, airborne(false, 40)).clampedVertical(), "terminal fall -3.92 must not clamp");
		// Fly signature: sustained 0.9/t ascent clamps to the airborne arc ceiling (0.55), which
		// is exactly the plugin's MAX_JUMP_BASE flag edge — strictly before any flag.
		Result up = clamp(0.0, 0.9, 0.0, airborne(false, 10));
		check(up.clampedVertical(), "0.9/t ascent must clamp");
		checkEquals(MovementEnvelope.upwardCeiling(airborne(false, 10)), up.dy(), "ascent clamped to the arc ceiling");
		checkEquals(MovementEnvelope.JUMP_UP_BASE, up.dy(), "sustained ascent clamps at the plugin's arc edge");
		// Fall beyond terminal clamps to 4.0.
		Result down = clamp(0.0, -12.0, 0.0, airborne(false, 10));
		check(down.clampedVertical() && down.dy() == -MovementEnvelope.FALL_TERMINAL, "beyond-terminal fall clamps to 4.0");
		// Water ascent (bubble columns) and slime bounces have their own generous ceilings.
		check(!clamp(0.0, 0.9, 0.0, water()).clampedVertical(), "bubble-column ascent 0.9 must not clamp");
		check(!clamp(0.0, 0.9, 0.0, bouncy()).clampedVertical(), "slime bounce 0.9 must not clamp");
		// Climbing faster than FastClimbCheck's 0.4 ceiling clamps at it.
		Result climb = clamp(0.0, 0.8, 0.0, climbing());
		check(climb.clampedVertical() && climb.dy() == MovementEnvelope.CLIMB_CEILING, "fast climb clamps at the plugin's climb ceiling");
	}

	private void exemptStates() {
		// Elytra firework chains (~1.7/t), blue-ice boats (2.0/t), creative flight all pass.
		check(!clamp(1.7, 0.3, 0.0, gliding()).clampedHorizontal(), "elytra 1.7/t must not clamp");
		check(!clamp(2.0, 0.0, 0.0, vehicle()).clampedHorizontal(), "boat-on-ice 2.0/t must not clamp");
		check(!clamp(1.5, 0.8, 0.0, flying()).clampedHorizontal(), "creative fly 1.5/t must not clamp");
		// But protocol-breaking speeds still clamp in exempt states.
		check(clamp(30.0, 0.0, 0.0, gliding()).clampedHorizontal(), "30/t elytra teleport must clamp");
		check(clamp(30.0, 0.0, 0.0, vehicle()).clampedHorizontal(), "30/t vehicle teleport must clamp");
	}

	private void graceWindowDefers() {
		// Velocity/teleport window: the plugin exempts these; so does the envelope, entirely.
		State grace = new State(true, false, 0, -1, -1, false, false, false, false, false, false, false, false, false, true);
		Result knockback = clamp(2.5, 0.6, 0.0, grace);
		check(!knockback.clampedHorizontal() && !knockback.clampedVertical(), "grace window must not clamp (velocity/teleport)");
	}

	private void clampPreservesDirection() {
		// dx:dz = 4:3 scaled to the ceiling keeps the ratio (no axis snap).
		State sprint = grounded(true, 0);
		Result diagonal = clamp(0.8, 0.0, 0.6, sprint);
		check(diagonal.clampedHorizontal(), "diagonal overspeed must clamp");
		checkEquals(diagonal.dx() / diagonal.dz(), 0.8 / 0.6, "direction ratio preserved");
		double horizontal = Math.sqrt(diagonal.dx() * diagonal.dx() + diagonal.dz() * diagonal.dz());
		checkEquals(MovementEnvelope.horizontalCeiling(sprint), horizontal, "clamped vector length equals the ceiling");
		// Horizontal scale and vertical clamp compose on the same packet.
		Result both = clamp(0.8, 0.9, 0.6, airborne(true, 8));
		check(both.clampedHorizontal() && both.clampedVertical(), "both axes clamp independently");
	}

	private void legacyTierReferencesSitBelowTheCeiling() {
		// The task's vanilla tier numbers are documented references: prove they sit inside every
		// corresponding ceiling so no vanilla tier can ever be clamped by construction.
		check(MovementEnvelope.WALK <= MovementEnvelope.horizontalCeiling(grounded(false, 0)), "walk tier under grounded ceiling");
		check(MovementEnvelope.SPRINT <= MovementEnvelope.horizontalCeiling(grounded(true, 0)), "sprint tier under sprint ceiling");
		check(MovementEnvelope.SPRINT_JUMP <= MovementEnvelope.horizontalCeiling(airborne(true, 0)), "sprint-jump tier under burst ceiling");
		// And the ceilings are strictly under the plugin's flag edges (clamps BEFORE the flag).
		check(MovementEnvelope.horizontalCeiling(grounded(true, 0)) < 0.42 * 1.3, "sprint ceiling under flag edge");
		check(MovementEnvelope.horizontalCeiling(airborne(true, 0)) < 0.42 * 1.3 * 1.7, "burst ceiling under burst flag edge");
		check(MovementEnvelope.horizontalCeiling(iceSprint()) < 0.42 * 1.3 * 1.4, "ice ceiling under ice flag edge");
	}

	// ── Helpers ─────────────────────────────────────────────────────────────────

	private static Result clamp(double dx, double dy, double dz, State state) {
		return MovementEnvelope.clamp(dx, dy, dz, state);
	}

	private static State grounded(boolean sprinting, int airborneTicks) {
		return new State(sprinting, true, airborneTicks, -1, -1, false, false, false, false, false, false, false, false, false, false);
	}

	private static State airborne(boolean sprinting, int airborneTicks) {
		return new State(sprinting, false, airborneTicks, -1, -1, false, false, false, false, false, false, false, false, false, false);
	}

	private static State airborne(int jumpBoostAmplifier, int airborneTicks) {
		return new State(false, false, airborneTicks, -1, jumpBoostAmplifier, false, false, false, false, false, false, false, false, false, false);
	}

	private static State iceSprint() {
		return new State(true, true, 0, -1, -1, true, false, false, false, false, false, false, false, false, false);
	}

	private static State speedPotionSprint(int amplifier) {
		return new State(true, true, 0, amplifier, -1, false, false, false, false, false, false, false, false, false, false);
	}

	private static State water() {
		return new State(true, false, 0, -1, -1, false, true, false, false, false, false, false, false, false, false);
	}

	private static State bouncy() {
		return new State(false, false, 0, -1, -1, false, false, false, false, false, false, false, false, true, false);
	}

	private static State climbing() {
		return new State(false, false, 0, -1, -1, false, false, false, false, false, false, false, true, false, false);
	}

	private static State gliding() {
		return new State(false, false, 0, -1, -1, false, false, true, false, false, false, false, false, false, false);
	}

	private static State vehicle() {
		return new State(false, true, 0, -1, -1, false, false, false, false, true, false, false, false, false, false);
	}

	private static State flying() {
		return new State(false, true, 0, -1, -1, false, false, false, false, false, true, false, false, false, false);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private static void checkEquals(double expected, double actual, String message) {
		check(Math.abs(expected - actual) < 1e-6, message + " (expected " + expected + ", got " + actual + ")");
	}
}
