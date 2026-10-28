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

import io.github.axolotlclient.AxolotlClientConfigCommon.FrameLimiterMode;
import io.github.axolotlclient.util.FrameLimiter;
import io.github.axolotlclient.util.PerformanceHides;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Exercises the M3 performance slice end to end:
 *
 * <ul>
 * <li>the frame limiter's pure state machine ({@link FrameLimiter#capFor} and the
 * {@link FrameLimiter#applyCap} vanilla-deferral rule), including the AFK thresholds,</li>
 * <li>the live guard that the limiter never touches the framerate while a client gametest
 * runs (so screenshot-driven tests can never be slowed by it),</li>
 * <li>the render-hide toggles on real summoned entities: primed TNT with its flash
 * suppressed, hopper items and a hopper minecart hidden, a distant cow beyond the entity
 * render distance hidden while near entities, the TNT and the player stay visible.</li>
 * </ul>
 *
 * <p>Every frame rendered while a toggle is on runs the corresponding mixin path, so a
 * broken injection fails the test by crashing the render.
 */
public class PerformanceGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		GametestBookkeeping.executed(getClass().getSimpleName());
		testLimiterStateMachine();

		// The world half summons entities and renders frames with the hide toggles on;
		// the headless "ci" subset keeps only the pure limiter math (see ImperiumGametestSubsets).
		if (ImperiumGametestSubsets.ci()) return;

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			testLimiterNeverThrottlesGameTests(context);
			testRenderHideToggles(context, world);
		} finally {
			context.runOnClient(mc -> {
				AxolotlClient.config().frameLimiter.set(FrameLimiterMode.OFF);
				AxolotlClient.config().disableTntFlash.set(false);
				AxolotlClient.config().hideLightningBolts.set(false);
				AxolotlClient.config().hideHoppers.set(false);
				AxolotlClient.config().hideDistantEntities.set(false);
				AxolotlClient.config().entityRenderDistance.set(64);
			});
		}
	}

	private void testLimiterStateMachine() {
		// The mode gates everything: OFF never caps, MINIMIZED ignores a merely unfocused
		// window, UNFOCUSED ignores a focused one.
		check(FrameLimiter.capFor(FrameLimiterMode.OFF, true, false, Long.MAX_VALUE, 30, 15, 60, 5, 300) == FrameLimiter.NO_CAP,
			"OFF must never cap, not even minimized after ten minutes idle");
		check(FrameLimiter.capFor(FrameLimiterMode.MINIMIZED, false, false, 600_000L, 30, 15, 60, 5, 300) == FrameLimiter.NO_CAP,
			"MINIMIZED must not cap a window that is only unfocused");
		check(FrameLimiter.capFor(FrameLimiterMode.UNFOCUSED, false, true, 0L, 30, 15, 60, 5, 300) == FrameLimiter.NO_CAP,
			"UNFOCUSED must not cap a focused window with fresh input");
		// AFK tiers only escalate on top of the mode's window condition; a focused window
		// stays unlimited by us (vanilla's own inactivity option owns that case).
		check(FrameLimiter.capFor(FrameLimiterMode.UNFOCUSED, false, true, 600_000L, 30, 15, 60, 5, 300) == FrameLimiter.NO_CAP,
			"a focused window must stay uncapped by us even after ten minutes idle");

		// Background cap while the window condition holds (minimized counts as unfocused too).
		check(FrameLimiter.capFor(FrameLimiterMode.MINIMIZED, true, false, 0L, 30, 15, 60, 5, 300) == 30,
			"a minimized window takes the background cap");
		check(FrameLimiter.capFor(FrameLimiterMode.UNFOCUSED, false, false, 0L, 30, 15, 60, 5, 300) == 30,
			"an unfocused window takes the background cap");
		check(FrameLimiter.capFor(FrameLimiterMode.UNFOCUSED, true, true, 0L, 30, 15, 60, 5, 300) == 30,
			"a minimized-but-focused window still takes the background cap");

		// AFK thresholds are inclusive, and the long tier replaces the short one.
		check(FrameLimiter.capFor(FrameLimiterMode.UNFOCUSED, false, false, 59_999L, 30, 15, 60, 5, 300) == 30,
			"one tick before the AFK delay the background cap still applies");
		check(FrameLimiter.capFor(FrameLimiterMode.UNFOCUSED, false, false, 60_000L, 30, 15, 60, 5, 300) == 15,
			"exactly at the AFK delay the AFK cap applies");
		check(FrameLimiter.capFor(FrameLimiterMode.UNFOCUSED, true, false, 299_999L, 30, 15, 60, 5, 300) == 15,
			"one tick before the long AFK delay the AFK cap still applies");
		check(FrameLimiter.capFor(FrameLimiterMode.UNFOCUSED, true, false, 300_000L, 30, 15, 60, 5, 300) == 5,
			"exactly at the long AFK delay the long AFK cap applies");
		check(FrameLimiter.capFor(FrameLimiterMode.MINIMIZED, true, false, 600_000L, 30, 15, 60, 5, 300) == 5,
			"a minimized window escalates to the long AFK cap too");

		// The strictest applicable cap wins, not the latest tier.
		check(FrameLimiter.capFor(FrameLimiterMode.UNFOCUSED, false, false, 300_000L, 10, 30, 60, 20, 300) == 10,
			"a stricter background cap must beat a looser AFK cap");

		// The cap never drops below 1 FPS.
		check(FrameLimiter.capFor(FrameLimiterMode.MINIMIZED, true, false, 600_000L, 0, 0, 1, 0, 1) == 1,
			"even all-zero configured caps clamp to 1 FPS");

		// Vanilla deferral: ours applies only when stricter, and never raises a limit.
		check(FrameLimiter.applyCap(120, FrameLimiter.NO_CAP) == 120, "no own cap leaves the vanilla limit alone");
		check(FrameLimiter.applyCap(120, 30) == 30, "a stricter own cap applies");
		check(FrameLimiter.applyCap(120, 200) == 120, "a looser own cap must never raise the limit");
		check(FrameLimiter.applyCap(10, 30) == 10, "when vanilla throttles harder, vanilla wins");
		check(FrameLimiter.applyCap(10, 5) == 5, "when we throttle harder, we win");
		check(FrameLimiter.applyCap(0, 30) == 0, "an invalid vanilla limit passes through untouched");

		// Idle tracking is monotonic and clamped.
		FrameLimiter.noteInput(1_000L);
		check(FrameLimiter.idleMillis(1_500L) == 500L, "idle time is the distance to the last input");
		check(FrameLimiter.idleMillis(500L) == 0L, "idle time never goes negative");

		// The serialized enum form profiles will carry.
		AxolotlClient.config().frameLimiter.fromSerializedValue("limiter.unfocused");
		check(AxolotlClient.config().frameLimiter.get() == FrameLimiterMode.UNFOCUSED,
			"limiter must deserialize 'limiter.unfocused' to UNFOCUSED");
		AxolotlClient.config().frameLimiter.fromSerializedValue("limiter.minimized");
		check(AxolotlClient.config().frameLimiter.get() == FrameLimiterMode.MINIMIZED,
			"limiter must deserialize 'limiter.minimized' to MINIMIZED");
		AxolotlClient.config().frameLimiter.set(FrameLimiterMode.OFF);
	}

	/**
	 * While a client gametest runs, {@code FramerateLimitTrackerMixin} must leave the
	 * framerate limit completely alone, or every screenshot-driven test in this suite
	 * would suddenly render at the capped rate.
	 */
	private void testLimiterNeverThrottlesGameTests(ClientGameTestContext context) {
		// The property the mixin keys off must actually be set while gametests run;
		// otherwise the comparison below would pass vacuously on a focused window.
		check(System.getProperty("fabric.client.gametest") != null,
			"the client gametest launcher must set the fabric.client.gametest property the limiter checks");
		int before = context.computeOnClient(mc -> mc.getFramerateLimitTracker().getFramerateLimit());
		context.runOnClient(mc -> AxolotlClient.config().frameLimiter.set(FrameLimiterMode.UNFOCUSED));
		context.waitTicks(5);
		int during = context.computeOnClient(mc -> mc.getFramerateLimitTracker().getFramerateLimit());
		check(before == during, "the limiter must not touch the framerate during a client gametest ("
			+ before + " before, " + during + " with UNFOCUSED set)");
	}

	private void testRenderHideToggles(ClientGameTestContext context, TestSingleplayerContext world) {
		context.runOnClient(mc -> {
			AxolotlClient.config().disableTntFlash.set(true);
			AxolotlClient.config().hideLightningBolts.set(true);
			AxolotlClient.config().hideHoppers.set(true);
			AxolotlClient.config().hideDistantEntities.set(true);
			// A tight radius so the summoned cow (~40 blocks out) is clearly beyond it.
			AxolotlClient.config().entityRenderDistance.set(16);
		});

		// A matrix of entities around the player: near TNT (must stay visible), a dropped
		// hopper and a hopper minecart (hidden by hide_hoppers), a distant cow (hidden by
		// hide_distant_entities) and a lightning bolt (hidden by hide_lightning_bolts).
		world.getServer().runCommand("summon minecraft:tnt ~10 ~ ~10");
		world.getServer().runCommand("summon minecraft:item ~2 ~ ~2 {Item:{id:\"minecraft:hopper\",count:1}}");
		world.getServer().runCommand("summon minecraft:hopper_minecart ~4 ~ ~4");
		world.getServer().runCommand("summon minecraft:cow ~40 ~ ~40");
		// Lightning bolts only live a couple of ticks; assert on it if the client saw it at all.
		world.getServer().runCommand("summon minecraft:lightning_bolt ~20 ~ ~20");
		context.waitTicks(20);

		ItemEntity hopperItem = context.computeOnClient(this::findHopperItem);
		check(hopperItem != null, "the summoned hopper item should exist on the client");
		MinecartHopper hopperCart = context.computeOnClient(this::findHopperCart);
		check(hopperCart != null, "the summoned hopper minecart should exist on the client");
		Entity cow = context.computeOnClient(this::findCow);
		check(cow != null, "the summoned cow should exist on the client");
		PrimedTnt tnt = context.computeOnClient(this::findTnt);
		check(tnt != null, "the summoned TNT should still be primed on the client");

		check(context.computeOnClient(mc -> PerformanceHides.shouldSkipEntity(hopperItem, cameraX(mc), cameraZ(mc))),
			"a dropped hopper must be skipped while hide_hoppers is on");
		check(context.computeOnClient(mc -> PerformanceHides.shouldSkipEntity(hopperCart, cameraX(mc), cameraZ(mc))),
			"a hopper minecart must be skipped while hide_hoppers is on");
		check(context.computeOnClient(mc -> PerformanceHides.shouldSkipEntity(cow, cameraX(mc), cameraZ(mc))),
			"a cow beyond the entity render distance must be skipped");
		check(!context.computeOnClient(mc -> PerformanceHides.shouldSkipEntity(tnt, cameraX(mc), cameraZ(mc))),
			"nearby TNT must stay visible");
		check(context.computeOnClient(mc -> !PerformanceHides.shouldSkipEntity(mc.player, cameraX(mc), cameraZ(mc))),
			"the player must never be hidden from their own view");
		LightningBolt bolt = context.computeOnClient(this::findLightningBolt);
		if (bolt != null) {
			check(context.computeOnClient(mc -> PerformanceHides.shouldSkipEntity(bolt, cameraX(mc), cameraZ(mc))),
				"a lightning bolt must be skipped while hide_lightning_bolts is on");
		}

		// Render frames with everything enabled: mid-fuse TNT proves the flash-suppressed
		// submit path runs without crashing, and the hidden entities prove the visibility
		// gate holds up against real render states.
		context.waitTicks(10);
		context.takeScreenshot("performance-hide-toggles-on");

		// Disable and verify the guard restores everything.
		context.runOnClient(mc -> {
			AxolotlClient.config().hideHoppers.set(false);
			AxolotlClient.config().hideDistantEntities.set(false);
			AxolotlClient.config().hideLightningBolts.set(false);
			AxolotlClient.config().disableTntFlash.set(false);
		});
		context.waitTicks(10);
		check(!context.computeOnClient(mc -> PerformanceHides.shouldSkipEntity(hopperItem, cameraX(mc), cameraZ(mc))),
			"a dropped hopper must render again once hide_hoppers is off");
		Entity cowAgain = context.computeOnClient(this::findCow);
		check(cowAgain == null || !context.computeOnClient(mc -> PerformanceHides.shouldSkipEntity(cowAgain, cameraX(mc), cameraZ(mc))),
			"a distant cow must render again once hide_distant_entities is off");
		context.takeScreenshot("performance-hide-toggles-off");

		// Let the remaining fuse burn out away from anything the later tests need.
		context.waitTicks(60);
	}

	private double cameraX(Minecraft mc) {
		return mc.gameRenderer.mainCamera().position().x;
	}

	private double cameraZ(Minecraft mc) {
		return mc.gameRenderer.mainCamera().position().z;
	}

	private ItemEntity findHopperItem(Minecraft mc) {
		if (mc.level == null) return null;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity instanceof ItemEntity item && item.getItem().is(Items.HOPPER)) {
				return item;
			}
		}
		return null;
	}

	private MinecartHopper findHopperCart(Minecraft mc) {
		if (mc.level == null) return null;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity instanceof MinecartHopper cart) {
				return cart;
			}
		}
		return null;
	}

	private Entity findCow(Minecraft mc) {
		if (mc.level == null) return null;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity instanceof Cow) {
				return entity;
			}
		}
		return null;
	}

	private PrimedTnt findTnt(Minecraft mc) {
		if (mc.level == null) return null;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity instanceof PrimedTnt tnt) {
				return tnt;
			}
		}
		return null;
	}

	private LightningBolt findLightningBolt(Minecraft mc) {
		if (mc.level == null) return null;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity instanceof LightningBolt bolt) {
				return bolt;
			}
		}
		return null;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
