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

import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.axolotlclient.AxolotlClientCommon;
import io.github.axolotlclient.modules.legions.CannonTrajectory;
import io.github.axolotlclient.modules.legions.ExplosionBoxes;
import io.github.axolotlclient.util.FarmingProtections;
import io.github.axolotlclient.util.OverlayOpacity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Wave 3 module settings, exercised end to end:
 *
 * <ul>
 *   <li>explosion_boxes — the linger tracker math (live window, timeout expiry, fade),
 *       block-grid snapping, and a live primed-TNT box that must draw while the fuse
 *       burns, keep drawing after the detonation, and drop once the timeout passes;</li>
 *   <li>cannon_view — the toggle keybind flips the module, Hitboxes-style;</li>
 *   <li>farming protections — the bottom-of-stack veto decision on real placed cane and
 *       bamboo (bottom protected, top harvestable, toggle wins), plus the render-hide
 *       predicates the chunk mesh compiler consults;</li>
 *   <li>overlay opacity — the alpha math behind pumpkin/spyglass/frost scaling.</li>
 * </ul>
 *
 * <p>The pure-logic part runs in every subset; the world part is render-bound and skipped
 * on the headless "ci" subset like the other world suites. Every rendered frame with the
 * options enabled runs the new mixin paths, so a broken injection fails by crashing.</p>
 */
public class ModulesWave3GameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		GametestBookkeeping.executed(getClass().getSimpleName());
		explosionBoxMath();
		overlayOpacityMath();
		farmingStackLogic();
		renderHideLogic();

		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this part needs a rendered world
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			cannonViewKeybindToggles(context);
			liveExplosionBoxLinger(world, context);
			liveCreeperBox(world, context);
			liveFarmingVeto(world, context);
		}
	}

	// ------------------------------------------------------------------ explosion boxes math

	private void explosionBoxMath() {
		ExplosionBoxes boxes = ExplosionBoxes.getInstance();
		try {
			boxes.clearTracked();

			// live window: refreshed every frame while the entity renders
			check(ExplosionBoxes.isLive(0), "a just-recorded box is live");
			check(ExplosionBoxes.isLive(ExplosionBoxes.LIVE_WINDOW_MS - 1), "a box within the live window is live");
			check(!ExplosionBoxes.isLive(ExplosionBoxes.LIVE_WINDOW_MS + 1), "a box past the live window is not live");

			// timeout expiry, boundary included
			check(!ExplosionBoxes.isExpired(999, 1000), "999ms age survives a 1000ms timeout");
			check(ExplosionBoxes.isExpired(1001, 1000), "1001ms age is expired at a 1000ms timeout");
			check(ExplosionBoxes.isExpired(1, 0), "timeout 0 expires immediately after the live window");

			// fade: 1 at detonation, 0 at the end of the window, clamped in between
			check(ExplosionBoxes.lingerFade(0, 1000) == 1.0F, "fade starts at full");
			check(Math.abs(ExplosionBoxes.lingerFade(500, 1000) - 0.5F) < 0.001F, "fade halves at half the timeout");
			check(ExplosionBoxes.lingerFade(1000, 1000) == 0.0F, "fade ends at zero");
			check(ExplosionBoxes.lingerFade(0, 0) == 0.0F, "no timeout means no lingering fill");
			check(ExplosionBoxes.lingerFade(2000, 1000) == 0.0F, "fade never goes negative");

			// block-grid snapping
			check(ExplosionBoxes.snap(false, 12.34) == 12.34, "block_center off keeps the raw position");
			check(ExplosionBoxes.snap(true, 12.34) == 12.5, "block_center snaps to the containing block centre");
			check(ExplosionBoxes.snap(true, -0.7) == -0.5, "snapping works through negative coordinates");

			// tracker: co-located boxes refresh (one entry per burning TNT), others append
			long now = System.currentTimeMillis();
			boxes.track(new ExplosionBoxes.Box(1, 65, 1, 4, 0x4DFF5555, now));
			boxes.track(new ExplosionBoxes.Box(1.1, 65.2, 0.9, 4, 0x4DFF5555, now + 5));
			check(boxes.getRecentBoxes().size() == 1, "a re-recorded box refreshes instead of duplicating");
			check(boxes.getRecentBoxes().get(0).stamp() == now + 5, "the refreshed entry carries the new stamp");
			boxes.track(new ExplosionBoxes.Box(50, 65, 50, 4, 0x4DFF5555, now + 6));
			check(boxes.getRecentBoxes().size() == 2, "a distant box is tracked separately");

			// pruning against the configured timeout
			boxes.timeout.set(1000);
			boxes.track(new ExplosionBoxes.Box(1, 65, 1, 4, 0x4DFF5555, now)); // stale again
			int pruned = boxes.pruneExpired(now + 5000);
			check(pruned >= 1 && boxes.getRecentBoxes().stream()
				.noneMatch(b -> b.stamp() == now), "prune drops entries older than the timeout");
		} finally {
			boxes.timeout.set(1000);
			boxes.clearTracked();
		}
	}

	// ------------------------------------------------------------------ overlay opacity math

	private void overlayOpacityMath() {
		check(OverlayOpacity.factor(100) == 1.0F, "100% is the vanilla factor");
		check(OverlayOpacity.factor(0) == 0.0F, "0% removes the overlay");
		check(OverlayOpacity.factor(150) == 1.0F, "factors clamp at 100%");
		check(OverlayOpacity.factor(-5) == 0.0F, "factors clamp at 0%");
		check(Math.abs(OverlayOpacity.scaleAlpha(1.0F, 50) - 0.5F) < 0.0001F, "vanilla alpha halves at 50%");
		int scaled = OverlayOpacity.scaleArgb(0xFF191919, 50);
		check(((scaled >>> 24) & 0xFF) == 127, "ARGB alpha scales by the percent (255 -> 127 at 50%)");
		check((scaled & 0x00FFFFFF) == 0x00191919, "ARGB colour channels are untouched");
		check(OverlayOpacity.scaleArgb(0xFF000000, 0) >>> 24 == 0, "0% kills the fill alpha");
	}

	// ------------------------------------------------------------------ farming stack veto

	private void farmingStackLogic() {
		BlockState cane = Blocks.SUGAR_CANE.defaultBlockState();
		BlockState bamboo = Blocks.BAMBOO.defaultBlockState();
		BlockState dirt = Blocks.DIRT.defaultBlockState();
		BlockState air = Blocks.AIR.defaultBlockState();
		var config = AxolotlClient.config();
		boolean caneDefault = config.farmingProtectBottomCane.get();
		boolean bambooDefault = config.farmingProtectBottomBamboo.get();
		try {
			config.farmingProtectBottomCane.set(true);
			config.farmingProtectBottomBamboo.set(true);

			check(FarmingProtections.isProtectedStack(cane, dirt, cane),
				"bottom cane with cane above is protected");
			check(!FarmingProtections.isProtectedStack(cane, cane, cane),
				"a middle/bottom-of-dirt-column cane below another cane is harvestable");
			check(!FarmingProtections.isProtectedStack(cane, dirt, air),
				"a lonely cane can be removed entirely");
			check(FarmingProtections.isProtectedStack(bamboo, dirt, bamboo),
				"bottom bamboo with bamboo above is protected");
			check(!FarmingProtections.isProtectedStack(bamboo, bamboo, air),
				"top bamboo is harvestable");
			check(!FarmingProtections.isProtectedStack(dirt, dirt, cane),
				"non-crop blocks never trip the veto");

			config.farmingProtectBottomCane.set(false);
			check(!FarmingProtections.isProtectedStack(cane, dirt, cane),
				"the cane toggle disables the veto");
			config.farmingProtectBottomBamboo.set(false);
			check(!FarmingProtections.isProtectedStack(bamboo, dirt, bamboo),
				"the bamboo toggle disables the veto");
		} finally {
			config.farmingProtectBottomCane.set(caneDefault);
			config.farmingProtectBottomBamboo.set(bambooDefault);
		}
	}

	// ------------------------------------------------------------------ farming render hides

	private void renderHideLogic() {
		var config = AxolotlClient.config();
		boolean cactusDefault = config.farmingDisableCactusRendering.get();
		boolean stringDefault = config.farmingDisableStringRendering.get();
		try {
			config.farmingDisableCactusRendering.set(false);
			config.farmingDisableStringRendering.set(false);
			check(!FarmingProtections.shouldHideBlockState(Blocks.CACTUS.defaultBlockState()),
				"cactus renders with the toggle off");
			check(!FarmingProtections.shouldHideBlockState(Blocks.TRIPWIRE.defaultBlockState()),
				"string renders with the toggle off");

			config.farmingDisableCactusRendering.set(true);
			config.farmingDisableStringRendering.set(true);
			check(FarmingProtections.shouldHideBlockState(Blocks.CACTUS.defaultBlockState()),
				"cactus is hidden from the chunk mesh with the toggle on");
			check(FarmingProtections.shouldHideBlockState(Blocks.TRIPWIRE.defaultBlockState()),
				"tripwire is hidden from the chunk mesh with the toggle on");
			check(!FarmingProtections.shouldHideBlockState(Blocks.STONE.defaultBlockState()),
				"unrelated blocks are never hidden");
		} finally {
			config.farmingDisableCactusRendering.set(cactusDefault);
			config.farmingDisableStringRendering.set(stringDefault);
		}
	}

	// ------------------------------------------------------------------ world: cannon_view keybind

	private void cannonViewKeybindToggles(ClientGameTestContext context) {
		CannonTrajectory module = CannonTrajectory.getInstance();
		boolean original = module.enabled.get();
		try {
			context.runOnClient(mc -> {
				((KeyMapping) (Object) module.toggleKey)
					.setKey(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_J));
				KeyMapping.resetMapping();
			});
			context.waitTicks(2);
			boolean start = context.computeOnClient(mc -> module.enabled.get());
			context.getInput().pressKey(InputConstants.KEY_J);
			context.waitTicks(5);
			check(context.computeOnClient(mc -> module.enabled.get()) != start,
				"the cannon_view keybind must toggle the trajectory module");
		} finally {
			// The consume-click handler persists the toggled state, so the restore has to
			// persist too — otherwise the NEXT suite run boots with the module disabled
			// and CannonTrajectoryGameTest fails on a stale config file.
			context.runOnClient(mc -> {
				module.enabled.set(original);
				AxolotlClientCommon.getInstance().saveConfig();
			});
		}
	}

	// ------------------------------------------------------------------ world: explosion boxes

	private void liveExplosionBoxLinger(TestSingleplayerContext world, ClientGameTestContext context) {
		ExplosionBoxes boxes = ExplosionBoxes.getInstance();
		int originalTimeout = boxes.timeout.get();
		boolean originalCreepers = boxes.creepers.get();
		try {
			boxes.timeout.set(1000);
			boxes.creepers.set(false); // isolate the TNT box
			boxes.clearTracked();

			// A primed TNT well away from the player: the box must draw for the whole fuse.
			// (26.3 renamed the fuse NBT key to lowercase "fuse".)
			world.getServer().runCommand("execute as @p at @s run summon minecraft:tnt ^ ^ ^8 {fuse:40}");
			context.waitTicks(10);
			check(context.computeOnClient(mc -> boxes.getLastDrawnBoxes()) >= 1,
				"the live TNT box must draw while the fuse burns");

			// Past detonation (fuse 40) but inside the 1000 ms linger: still drawn.
			context.waitTicks(45);
			check(context.computeOnClient(mc -> boxes.getLastDrawnBoxes()) >= 1,
				"the box must linger after the detonation");

			// Timeout 0: nothing lingers past the live window.
			boxes.timeout.set(0);
			context.waitTicks(3);
			check(context.computeOnClient(mc -> boxes.getLastDrawnBoxes()) == 0,
				"timeout 0 must drop the lingering boxes");
		} finally {
			boxes.timeout.set(originalTimeout);
			boxes.creepers.set(originalCreepers);
			boxes.clearTracked();
		}
	}

	private void liveCreeperBox(TestSingleplayerContext world, ClientGameTestContext context) {
		ExplosionBoxes boxes = ExplosionBoxes.getInstance();
		int originalTimeout = boxes.timeout.get();
		boolean originalCreepers = boxes.creepers.get();
		try {
			boxes.timeout.set(0);
			boxes.creepers.set(true);
			boxes.clearTracked();

			world.getServer().runCommand("execute as @p at @s run summon minecraft:creeper ^ ^ ^6 {NoAI:1b}");
			context.waitTicks(10);
			check(context.computeOnClient(mc -> boxes.getLastDrawnBoxes()) >= 1,
				"a creeper in range must get a blast box while creeper boxes are on");

			boxes.creepers.set(false);
			context.waitTicks(3);
			check(context.computeOnClient(mc -> boxes.getLastDrawnBoxes()) == 0,
				"the creeper box must stop drawing when the toggle is off");
			world.getServer().runCommand("kill @e[type=minecraft:creeper,limit=1]");
		} finally {
			boxes.timeout.set(originalTimeout);
			boxes.creepers.set(originalCreepers);
			boxes.clearTracked();
		}
	}

	// ------------------------------------------------------------------ world: farming veto

	private void liveFarmingVeto(TestSingleplayerContext world, ClientGameTestContext context) {
		var config = AxolotlClient.config();
		boolean caneDefault = config.farmingProtectBottomCane.get();
		boolean cactusDefault = config.farmingDisableCactusRendering.get();
		try {
			config.farmingProtectBottomCane.set(true);

			BlockPos base = context.computeOnClient(mc -> mc.player.blockPosition().east(4));
			// A survivable layout: cane needs water next to its dirt (otherwise the placed
			// cane pops right back off on the neighbour update), cactus needs sand.
			setBlock(world, base, Blocks.DIRT);
			setBlock(world, base.east(), Blocks.WATER);
			setBlock(world, base.above(), Blocks.SUGAR_CANE);
			setBlock(world, base.above(2), Blocks.SUGAR_CANE);
			setBlock(world, base.east(3), Blocks.SAND);
			setBlock(world, base.east(3).above(), Blocks.CACTUS);
			context.waitTicks(5);

			check(context.computeOnClient(mc -> FarmingProtections.shouldVetoBreak(base.above())),
				"the placed bottom cane must be vetoed while cane grows above");
			check(!context.computeOnClient(mc -> FarmingProtections.shouldVetoBreak(base.above(2))),
				"the placed top cane must stay harvestable");

			config.farmingProtectBottomCane.set(false);
			check(!context.computeOnClient(mc -> FarmingProtections.shouldVetoBreak(base.above())),
				"the veto must lift when the cane toggle is off");

			// The render-hide re-mesh path must not crash a live world (the toggle's
			// change listener flags a re-mesh; the client tick drains it).
			config.farmingDisableCactusRendering.set(true);
			context.waitTicks(10);
			context.takeScreenshot("wave3-hidden-cactus");
		} finally {
			config.farmingProtectBottomCane.set(caneDefault);
			config.farmingDisableCactusRendering.set(cactusDefault);
			context.waitTicks(5);
		}
	}

	private void setBlock(TestSingleplayerContext world, BlockPos pos, net.minecraft.world.level.block.Block block) {
		world.getServer().runCommand(String.format(Locale.ROOT, "setblock %d %d %d minecraft:%s",
			pos.getX(), pos.getY(), pos.getZ(),
			builtInRegistryName(block)));
	}

	private static String builtInRegistryName(net.minecraft.world.level.block.Block block) {
		return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getPath();
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
