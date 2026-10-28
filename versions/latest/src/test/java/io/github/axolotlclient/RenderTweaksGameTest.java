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

import com.google.gson.JsonObject;
import io.github.axolotlclient.AxolotlClientConfigCommon.HurtCamMode;
import io.github.axolotlclient.config.migration.impl.V11Migration;
import io.github.axolotlclient.util.ChunkBorders;
import io.github.axolotlclient.util.ClearWater;
import io.github.axolotlclient.util.FarmingOverlay;
import io.github.axolotlclient.util.LowHpTint;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Exercises the Alpine-parity wave3 render tweaks end to end in a real world:
 * hurt_cam (enum + V11 migration of the old boolean), low_hp_tint (intensity scaling +
 * live overlay frames), clear_water (fog distance math + a live underwater frame),
 * farming (crop stage text/progress + the live crosshair-targeted overlay) and
 * chunk_borders (cell math + live gizmo frames).
 *
 * <p>Every frame the client renders while an option is enabled runs the corresponding
 * mixin path, so a broken injection or draw call fails the test by crashing the render.
 */
public class RenderTweaksGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this suite is render-heavy, see ImperiumGametestSubsets
		GametestBookkeeping.executed(getClass().getSimpleName());
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();

			testHurtCam(context);
			testLowHpTint(context);
			testClearWater(context, world);
			testFarmingOverlay(context, world);
			testChunkBorders(context);
		} finally {
			context.runOnClient(mc -> {
				AxolotlClient.config().hurtCam.set(HurtCamMode.FULL);
				AxolotlClient.config().clearWater.set(false);
				AxolotlClient.config().clearWaterDisableLiquidFov.set(false);
				AxolotlClient.config().waterOverlayTransparency.set(10);
				AxolotlClient.config().lowHpTint.set(false);
				AxolotlClient.config().lowHpThreshold.set(6);
				AxolotlClient.config().lowHpTintStyle.set(AxolotlClientConfigCommon.LowHpTintStyle.VIGNETTE);
				AxolotlClient.config().lowHpAdaptiveOpacity.set(true);
				AxolotlClient.config().lowHpMinOpacity.set(0);
				AxolotlClient.config().farmingOverlay.set(false);
				AxolotlClient.config().farmingHoeOnly.set(false);
				AxolotlClient.config().farmingStyle.set(AxolotlClientConfigCommon.FarmingOverlayStyle.TEXT);
				AxolotlClient.config().chunkBorders.set(false);
				AxolotlClient.config().regionBorders.set(false);
			});
		}
	}

	private void testHurtCam(ClientGameTestContext context) {
		// Enum behaviour: FULL keeps the vanilla angle, SUBTLE keeps 30%, OFF cancels (multiplier unused).
		check(HurtCamMode.FULL.tiltMultiplier() == 1.0F, "FULL must keep the vanilla tilt");
		check(Math.abs(HurtCamMode.SUBTLE.tiltMultiplier() - 0.3F) < 1.0E-6F, "SUBTLE must keep 30% of the tilt");
		check(HurtCamMode.OFF.tiltMultiplier() == 1.0F, "OFF is handled by cancelling bobHurt, not by scaling");

		// The serialized form the migration writes must be the form EnumOption parses (toString-based).
		AxolotlClient.config().hurtCam.fromSerializedValue("hurt_cam.off");
		check(AxolotlClient.config().hurtCam.get() == HurtCamMode.OFF, "hurt_cam must deserialize 'hurt_cam.off' to OFF");
		AxolotlClient.config().hurtCam.fromSerializedValue("hurt_cam.subtle");
		check(AxolotlClient.config().hurtCam.get() == HurtCamMode.SUBTLE, "hurt_cam must deserialize 'hurt_cam.subtle' to SUBTLE");

		// V11 migration: the legacy noHurtCam boolean becomes the enum.
		check(migrateNoHurtCam(true).equals("hurt_cam.off"), "V11 must turn noHurtCam=true into hurt_cam OFF");
		check(migrateNoHurtCam(false).equals("hurt_cam.full"), "V11 must turn noHurtCam=false into hurt_cam FULL");

		// Damage the player for real so bobHurt runs with a live hurt tilt in every mode
		// (SUBTLE goes through the angle-scaling injection, OFF through the cancel).
		for (HurtCamMode mode : HurtCamMode.values()) {
			context.runOnClient(mc -> AxolotlClient.config().hurtCam.set(mode));
			damageClientPlayer(context);
			context.waitTicks(12);
		}
	}

	private String migrateNoHurtCam(boolean legacyValue) {
		JsonObject root = new JsonObject();
		JsonObject rendering = new JsonObject();
		rendering.addProperty("noHurtCam", legacyValue);
		root.add("rendering", rendering);
		new V11Migration().apply(root);
		check(!rendering.has("noHurtCam"), "V11 must remove the legacy noHurtCam key");
		return rendering.get("hurt_cam").getAsString();
	}

	private void damageClientPlayer(ClientGameTestContext context) {
		// Creative players are invulnerable, so hop into survival for the hit and straight
		// back out (staying in survival would let natural regen resync health later).
		context.runOnClient(mc -> {
			ServerLevel level = mc.getSingleplayerServer().overworld();
			ServerPlayer player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
			if (player != null) {
				player.setGameMode(GameType.SURVIVAL);
				player.hurtServer(level, level.damageSources().generic(), 1.0F);
				player.setGameMode(GameType.CREATIVE);
			}
		});
		context.waitTicks(2);
	}

	private void testLowHpTint(ClientGameTestContext context) {
		// The ramp: nothing at the threshold, ~0.5 at the midpoint, full at 1 hp, clamped below.
		check(LowHpTint.intensityFor(6.0F, 6) == 0.0F, "the tint must not draw at the threshold");
		check(Math.abs(LowHpTint.intensityFor(5.0F, 6) - 0.2F) < 1.0E-6F, "5 of 6 hp should be 20% intensity");
		check(Math.abs(LowHpTint.intensityFor(3.5F, 6) - 0.5F) < 1.0E-6F, "3.5 of 6 hp should be 50% intensity");
		check(LowHpTint.intensityFor(1.0F, 6) == 1.0F, "1 hp should be full intensity");
		check(LowHpTint.intensityFor(0.5F, 6) == 1.0F, "below 1 hp the intensity must clamp to full");
		check(LowHpTint.intensityFor(0.5F, 1) == 0.5F, "a threshold of 1 must ramp within a single half-heart");

		// adaptive_opacity/min_opacity: the effective alpha ramps with health (floored by
		// min_opacity) and, with adaptive off, stays at the colour's full alpha throughout.
		check(LowHpTint.alphaFor(5.0F, 6, true, 0, 102) == Math.round(102 * 0.2F), "adaptive alpha must follow the ramp");
		check(LowHpTint.alphaFor(5.0F, 6, true, 50, 102) == 51, "min_opacity must floor the ramped alpha (50% of 102)");
		check(LowHpTint.alphaFor(5.0F, 6, false, 0, 102) == 102, "with adaptive off the alpha must stay at the base");
		check(LowHpTint.alphaFor(1.0F, 6, true, 50, 102) == 102, "full ramp must not be capped below the base alpha");

		// Live: enable the tint and set the player's health server-side (a client-side set
		// gets resynced away), then render frames in both styles.
		context.runOnClient(mc -> {
			AxolotlClient.config().lowHpTint.set(true);
			AxolotlClient.config().lowHpThreshold.set(6);
			ServerPlayer player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
			player.setGameMode(GameType.SURVIVAL);
			player.setHealth(3.0F);
		});
		context.waitTicks(3);
		float health = context.computeOnClient(mc -> mc.player.getHealth());
		// Natural regeneration may already have ticked the value up slightly, so verify the
		// live intensity sits strictly inside the ramp instead of pinning the exact number.
		check(health >= 3.0F && health < 6.0F, "the player should be below the 6 hp threshold, got " + health);
		float liveIntensity = LowHpTint.intensityFor(health, 6);
		check(liveIntensity > 0.0F && liveIntensity <= 0.61F, "a player at " + health + " of 6 hp should be inside the tint ramp, got " + liveIntensity);
		context.takeScreenshot("rendertweaks-low-hp-vignette");
		context.runOnClient(mc -> AxolotlClient.config().lowHpTintStyle.set(AxolotlClientConfigCommon.LowHpTintStyle.FLAT));
		context.waitTicks(10);
		context.takeScreenshot("rendertweaks-low-hp-flat");
		context.runOnClient(mc -> {
			ServerPlayer player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
			player.setGameMode(GameType.CREATIVE);
			player.setHealth(20.0F);
		});
	}

	private void testClearWater(ClientGameTestContext context, TestSingleplayerContext world) {
		// Fog math on vanilla defaults (start -8, end 96): off leaves them untouched.
		FogData untouched = vanillaWaterFog();
		ClearWater.apply(untouched, 192.0F);
		check(untouched.environmentalStart == -8.0F && untouched.environmentalEnd == 96.0F,
			"with clear_water off the water fog must stay vanilla");

		// On: the fog is pushed out towards the render distance and the sky/cloud ends follow.
		context.runOnClient(mc -> AxolotlClient.config().clearWater.set(true));
		FogData cleared = vanillaWaterFog();
		ClearWater.apply(cleared, 192.0F);
		check(Math.abs(cleared.environmentalEnd - 192.0F * ClearWater.END_DISTANCE_FRACTION) < 1.0E-4F,
			"clear_water must push the water fog end to near the render distance");
		check(Math.abs(cleared.environmentalStart - cleared.environmentalEnd * ClearWater.START_DISTANCE_FRACTION) < 1.0E-4F,
			"clear_water must push the water fog start with the end");
		check(cleared.skyEnd == cleared.environmentalEnd && cleared.cloudEnd == cleared.environmentalEnd,
			"clear_water must re-sync the sky and cloud fog ends");

		// With a tiny render distance the biome fog (96) must not shrink below vanilla.
		FogData small = vanillaWaterFog();
		ClearWater.apply(small, 32.0F);
		check(small.environmentalEnd == 96.0F, "clear_water must not shrink the fog below the vanilla end");

		// disable_liquid_fov and overlay_transparency are clear_water sub-settings: they only
		// act while the module itself is on (clear_water is on from the block above).
		context.runOnClient(mc -> {
			AxolotlClient.config().clearWaterDisableLiquidFov.set(true);
			AxolotlClient.config().waterOverlayTransparency.set(0);
		});
		check(ClearWater.disablesLiquidFov(), "disable_liquid_fov must act while clear_water and its own toggle are on");
		check(ClearWater.waterOverlayAlpha(0.1F) == 0.0F, "an overlay transparency of 0 must remove the overlay");
		context.runOnClient(mc -> AxolotlClient.config().waterOverlayTransparency.set(100));
		check(ClearWater.waterOverlayAlpha(0.1F) == 1.0F, "an overlay transparency of 100 must be solid");
		context.runOnClient(mc -> AxolotlClient.config().clearWater.set(false));
		check(!ClearWater.disablesLiquidFov(), "disable_liquid_fov must stay off while clear_water is off");
		check(Math.abs(ClearWater.waterOverlayAlpha(0.1F) - 0.1F) < 1.0E-6F, "overlay alpha must stay vanilla while clear_water is off");
		context.runOnClient(mc -> AxolotlClient.config().waterOverlayTransparency.set(10));

		// Live: submerge the camera so WaterFogEnvironment.setupFog (and the mixin) runs each frame.
		// Block changes must go through the server thread (setBlockAndUpdate on the render
		// thread deadlocks on chunk loading).
		context.runOnClient(mc -> {
			AxolotlClient.config().clearWater.set(true);
			mc.player.setPos(mc.player.getX(), mc.player.getY() + 0.01, mc.player.getZ());
		});
		BlockPos pos = context.computeOnClient(mc ->
			BlockPos.containing(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
		world.getServer().runOnServer(server -> {
			ServerLevel level = server.overworld();
			for (int i = 1; i <= 3; i++) {
				level.setBlockAndUpdate(pos.above(i), Blocks.WATER.defaultBlockState());
			}
		});
		context.runOnClient(mc -> mc.player.setPos(pos.getX() + 0.5, pos.getY() + 1.5, pos.getZ() + 0.5));
		context.waitTicks(20);
		check(context.computeOnClient(mc -> mc.gameRenderer.mainCamera().getFluidInCamera()) == FogType.WATER,
			"the camera should be submerged for the underwater fog test");
		context.takeScreenshot("rendertweaks-clear-water");
		context.runOnClient(mc -> AxolotlClient.config().clearWater.set(false));
		// Drain a volume around the pool, not just the column — the placed sources spread
		// flowing water onto the floor while submerged, and that flows straight back into
		// any column-only cleanup.
		world.getServer().runOnServer(server -> {
			ServerLevel level = server.overworld();
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					for (int dy = 0; dy <= 4; dy++) {
						level.setBlockAndUpdate(pos.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
					}
				}
			}
		});
		context.runOnClient(mc -> mc.player.setPos(pos.getX() + 0.5, pos.getY() + 0.2, pos.getZ() + 0.5));
		context.waitTicks(10);
	}

	private FogData vanillaWaterFog() {
		FogData data = new FogData();
		data.environmentalStart = -8.0F;
		data.environmentalEnd = 96.0F;
		data.skyEnd = 96.0F;
		data.cloudEnd = 96.0F;
		return data;
	}

	private void testFarmingOverlay(ClientGameTestContext context, TestSingleplayerContext world) {
		// Stage text and progress read straight off a CropBlock state.
		BlockState wheat3 = Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 3);
		check("3/8".equals(FarmingOverlay.stageText(wheat3)), "wheat at age 3 must render as 3/8, got " + FarmingOverlay.stageText(wheat3));
		check("7/8".equals(FarmingOverlay.stageText(Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7))), "fully grown wheat must render as 7/8");
		check(Math.abs(FarmingOverlay.progress(wheat3) - 3.0F / 7.0F) < 1.0E-6F, "wheat at age 3 must be 3/7 progress");
		check(FarmingOverlay.stageText(Blocks.STONE.defaultBlockState()) == null, "non-crop blocks have no stage text");
		check(FarmingOverlay.progress(Blocks.STONE.defaultBlockState()) < 0.0F, "non-crop blocks have negative progress");

		// Hoes are data-driven in 26.3; the *_hoe id check must catch them and only them.
		check(FarmingOverlay.isHoldingHoe(new ItemStack(Items.IRON_HOE)), "an iron hoe must be recognised as a hoe");
		check(FarmingOverlay.isHoldingHoe(new ItemStack(Items.NETHERITE_HOE)), "a netherite hoe must be recognised as a hoe");
		check(!FarmingOverlay.isHoldingHoe(new ItemStack(Items.STONE)), "stone must not be recognised as a hoe");
		check(!FarmingOverlay.isHoldingHoe(ItemStack.EMPTY), "an empty hand must not be recognised as a hoe");

		// Live: plant a crop at the player's feet, look down at it and let the overlay render frames.
		context.runOnClient(mc -> {
			AxolotlClient.config().farmingOverlay.set(true);
			AxolotlClient.config().farmingHoeOnly.set(false);
			mc.player.setXRot(90.0F);
		});
		// The crop goes in on the server thread (see testClearWater for why), on farmland so
		// it does not immediately pop off again. Any leftover water around the spot is
		// drained first so the crop cannot be flooded by back-flow.
		BlockPos feet = context.computeOnClient(mc -> mc.player.blockPosition());
		world.getServer().runOnServer(server -> {
			ServerLevel level = server.overworld();
			for (int dy = 0; dy <= 3; dy++) {
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (level.getFluidState(feet.offset(dx, dy, dz)).is(net.minecraft.tags.FluidTags.WATER)) {
							level.setBlockAndUpdate(feet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
						}
					}
				}
			}
			level.setBlockAndUpdate(feet.below(), Blocks.FARMLAND.defaultBlockState());
			level.setBlockAndUpdate(feet, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 3));
		});
		// Re-anchor the player on the crop and look straight down at it.
		context.runOnClient(mc -> {
			mc.player.setPos(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
			mc.player.setXRot(90.0F);
		});
		context.waitTicks(15);
		String serverBlock = world.getServer().computeOnServer(server ->
			net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(server.overworld().getBlockState(feet).getBlock()).getPath());
		String clientBlock = context.computeOnClient(mc ->
			net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(feet).getBlock()).getPath());
		check("wheat".equals(serverBlock), "the server should hold wheat at " + feet + ", got " + serverBlock);
		check("wheat".equals(clientBlock), "the client should have synced wheat at " + feet + ", got " + clientBlock);
		check("3/8".equals(context.computeOnClient(mc -> FarmingOverlay.stageText(mc.level.getBlockState(feet)))),
			"the crop at " + feet + " should read as 3/8");
		check(context.computeOnClient(mc -> mc.hitResult) instanceof BlockHitResult, "the crosshair should be on the crop block");
		context.takeScreenshot("rendertweaks-farming-text");
		context.runOnClient(mc -> AxolotlClient.config().farmingStyle.set(AxolotlClientConfigCommon.FarmingOverlayStyle.BAR));
		context.waitTicks(10);
		context.takeScreenshot("rendertweaks-farming-bar");

		// hoe_only with an empty hand must make the overlay early-out before touching the graphics.
		context.runOnClient(mc -> AxolotlClient.config().farmingHoeOnly.set(true));
		context.runOnClient(mc -> FarmingOverlay.render(null));
		context.runOnClient(mc -> AxolotlClient.config().farmingHoeOnly.set(false));
	}

	private void testChunkBorders(ClientGameTestContext context) {
		// Cell math, including negative coordinates.
		check(ChunkBorders.chunkBase(20) == 16, "block 20 is in the chunk starting at 16");
		check(ChunkBorders.chunkBase(-1) == -16, "block -1 is in the chunk starting at -16");
		check(ChunkBorders.chunkBase(16) == 16, "block 16 is the start of its chunk");
		check(ChunkBorders.regionBase(-1) == -512, "block -1 is in the region starting at -512");
		check(ChunkBorders.regionBase(600) == 512, "block 600 is in the region starting at 512");
		check(ChunkBorders.regionBase(512) == 512, "block 512 is the start of its region");
		check(ChunkBorders.REGION_SIZE == 512, "a region is 512 blocks (32 chunks)");

		// Live: emit lines for real while frames render — Gizmos.line throws without an active
		// collector, so gizmo lines can only ever be produced from inside the collection scope.
		context.runOnClient(mc -> {
			AxolotlClient.config().chunkBorders.set(true);
			AxolotlClient.config().regionBorders.set(true);
		});
		context.waitTicks(15);
		context.takeScreenshot("rendertweaks-chunk-borders");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
