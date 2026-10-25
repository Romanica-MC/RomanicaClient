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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.axolotlclient.bridge.util.AxoIdentifier;
import io.github.axolotlclient.imperium.menu.ImperiumMenuScreen;
import io.github.axolotlclient.modules.minimap.MinimapHud;
import io.github.axolotlclient.modules.minimap.MinimapTexture;
import io.github.axolotlclient.modules.hud.HudManager;
import io.github.axolotlclient.util.FrameTimeSampler;
import io.github.axolotlclient.util.HudFrameCache;
import io.github.axolotlclient.util.PerformanceStats;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;

/**
 * M3 performance benchmarks: for each shipped feature, in a created singleplayer world with
 * vsync off and the vanilla framerate limit raised, warms up {@value #WARMUP_FRAMES} frames,
 * then measures {@value #MEASURE_FRAMES} frames with the feature OFF, then ON, using
 * {@code Minecraft.getFrameTimeNs()} — the same source the debug FPS overlay uses — via the
 * {@link FrameTimeSampler} per-frame hook. Average and P95 frame times for both phases go to
 * a JSON lines file under the gametest run directory ({@code benchmarks/}).
 *
 * <p>Every ON phase also asserts the feature actually did something (its counter in
 * {@link PerformanceStats} advanced / the HUD cache served frames) and that the game stayed
 * alive (the expected number of frames arrived). The numbers are local truth for the machine
 * that ran the test, not CI truth: a vsync- or GPU-bound scene hides CPU-side wins.
 */
public class PerformanceBenchmarkGameTest implements FabricClientGameTest {

	private static final int WARMUP_FRAMES = 100;
	private static final int MEASURE_FRAMES = 300;
	private static final long PHASE_TIMEOUT_NANOS = 120_000_000_000L; // 120s per phase

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private Path output;

	@Override
	public void runTest(ClientGameTestContext context) {
		// The benchmark renders hundreds of uncapped frames per feature and its scene
		// sync is sensitive to a starved client; it is developer tooling, not a release
		// gate. Run explicitly with -Dimperiumclient.gametest.subset=benchmark.
		if (!ImperiumGametestSubsets.benchmark()) {
			return;
		}
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			// Let the window scale and frame pacing settle before any measurement: a freshly
			// started client can still be resizing/settling its framebuffer, which would make
			// every phase noisier than the effects being measured.
			context.waitTicks(100);
			output = FabricLoader.getInstance().getGameDir().resolve("benchmarks/performance-benchmarks.jsonl");
			try {
				Files.createDirectories(output.getParent());
				Files.deleteIfExists(output);
			} catch (IOException e) {
				throw new AssertionError("failed to prepare the benchmark output file", e);
			}

			boolean vsync = context.computeOnClient(mc -> mc.options.enableVsync().get());
			int framerateLimit = context.computeOnClient(mc -> mc.options.framerateLimit().get());
			try {
				// Uncap the framerate so CPU-side differences are visible instead of hidden
				// behind vsync pacing; restored in the finally below.
				setFpsOptions(context, false, 260);
				benchHudFrameLimit(context, world);
				benchTextRenderDistance(context, world);
				benchDisableTntExpansion(context, world);
				benchOptimizeSpawners(context, world);
				benchDisableEnchantTableBooks(context, world);
				benchMinimap(context, world);
				benchHudTree(context, world);
				benchMenuScreen(context, world);
				benchChat(context, world);
			} finally {
				setFpsOptions(context, vsync, framerateLimit);
				FrameTimeSampler.clear();
				resetOptions(context);
			}
		}
	}

	private void setFpsOptions(ClientGameTestContext context, boolean vsync, int framerateLimit) {
		context.runOnClient(mc -> {
			mc.options.enableVsync().set(vsync);
			mc.options.framerateLimit().set(framerateLimit);
		});
	}

	private void resetOptions(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			AxolotlClient.config().hudFrameLimit.set(0);
			AxolotlClient.config().textRenderDistance.set(0);
			AxolotlClient.config().disableTntExpansion.set(false);
			AxolotlClient.config().optimizeSpawners.set(false);
			AxolotlClient.config().disableEnchantTableBooks.set(false);
			AxolotlClient.config().smoothChat.set(false);
			if (mc.gui.screen() != null) {
				mc.gui.setScreen(null);
			}
			for (String[] hud : TREE_HUDS) {
				setHudEnabled(hud[0], hud[1], false);
			}
			setHudEnabled("imperiumclient", "minimaphud", false);
		});
		HudFrameCache.resetCounters();
		PerformanceStats.reset();
	}

	// --- feature benchmarks ---

	/**
	 * hud_frame_limit: a HUD-heavy cacheable set (armor + inventory + IP + icon, the item
	 * renderers) plus two live data HUDs (FPS, CPS) so the measurement covers both halves of
	 * the cache.
	 */
	private void benchHudFrameLimit(ClientGameTestContext context, TestSingleplayerContext world) {
		String[][] huds = {
			{"kronhud", "armorhud"},
			{AxolotlClientCommon.MODID, "inventoryhud"},
			{"kronhud", "iphud"},
			{AxolotlClientCommon.MODID, "iconhud"},
			{"kronhud", "fpshud"},
			{"kronhud", "cpshud"}
		};
		context.runOnClient(mc -> {
			for (String[] hud : huds) {
				setHudEnabled(hud[0], hud[1], true);
			}
		});
		// Give the item HUDs something real to render.
		world.getServer().runCommand("item replace entity @s armor.head with diamond_helmet");
		world.getServer().runCommand("item replace entity @s armor.chest with diamond_chestplate");
		world.getServer().runCommand("item replace entity @s armor.legs with diamond_leggings");
		world.getServer().runCommand("item replace entity @s armor.feet with diamond_boots");
		world.getServer().runCommand("give @s diamond 64");
		world.getServer().runCommand("give @s golden_apple 16");
		context.waitTicks(10);

		// OFF: no cache, every entry renders live. The counters reset inside the setup: a
		// previous suite run that crashed mid-benchmark can leave hud_frame_limit persisted
		// as 30, and the couple of frames rendered before this set(0) legitimately replay.
		Stats off = measure(context, "hud_frame_limit", "off", () -> {
			context.runOnClient(mc -> AxolotlClient.config().hudFrameLimit.set(0));
			HudFrameCache.resetCounters();
		});
		check(HudFrameCache.replayedFrames() == 0, "with hud_frame_limit off no frame may be served from a recording");

		// ON: cacheable entries re-record at 30 Hz and are replayed between records. A frame
		// slower than the limit legitimately re-records every frame, so the replay demand only
		// applies while the measured frame rate actually exceeds the limit; the first world of
		// a fresh client can stay under it for a long time (worldgen meshing), so retry once
		// after extra settling before failing.
		Stats on = null;
		for (int attempt = 0; attempt < 2; attempt++) {
			HudFrameCache.resetCounters();
			on = measure(context, "hud_frame_limit", "on", () ->
				context.runOnClient(mc -> AxolotlClient.config().hudFrameLimit.set(30)));
			if (HudFrameCache.replayedFrames() > 0 || on.avg >= 1000.0 / 45.0) {
				break;
			}
			context.waitTicks(200);
		}
		check(HudFrameCache.replayedFrames() > 0 || on.avg >= 1000.0 / 45.0,
			"with hud_frame_limit 30 and cacheable HUDs enabled, frames faster than the limit must be served from the recording (records: "
				+ HudFrameCache.recordedFrames() + ", avg frame " + round(on.avg) + " ms, last invalidation: " + HudFrameCache.lastInvalidation() + ")");
		check(HudFrameCache.recordedFrames() > 0, "the HUD cache must re-record at least once while enabled");
		record(context, "hud_frame_limit", off, on, "cacheable=armor,inventory,ip,icon + live=fps,cps @30Hz");

		context.runOnClient(mc -> {
			for (String[] hud : huds) {
				setHudEnabled(hud[0], hud[1], false);
			}
		});
	}

	/**
	 * text_render_distance: fourteen signs and fourteen named armour stands (hologram style)
	 * in a ring 22 to 40 blocks out — all beyond a 16-block limit, all inside entity render
	 * range.
	 */
	private void benchTextRenderDistance(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockPos center = prepareScene(context, world, 1);
		world.getServer().runOnServer(server -> {
			ServerLevel level = server.overworld();
			List<Component> lines = List.of(Component.literal("Imperium"), Component.literal("Imperium"),
				Component.literal("Imperium"), Component.literal("Imperium"));
			SignText text = new SignText(lines, lines, DyeColor.BLACK, false);
			for (int i = 0; i < 14; i++) {
				int distance = 22 + (i % 7) * 3;
				double angle = (Math.PI * 2 * i) / 14.0;
				int x = (int) Math.round(Math.cos(angle) * distance);
				int z = (int) Math.round(Math.sin(angle) * distance);
				BlockPos signPos = center.offset(x, 1, z);
				int rotation = Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(-x, -z)) / 22.5), 16);
				level.setBlockAndUpdate(signPos, Blocks.OAK_SIGN.defaultBlockState()
					.setValue(net.minecraft.world.level.block.StandingSignBlock.ROTATION, rotation));
				if (level.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
					sign.setText(text, SignTextSlot.FRONT);
				}
				ArmorStand stand = new ArmorStand(EntityTypes.ARMOR_STAND, level);
				if (stand != null) {
					stand.setPos(signPos.getX() + 0.5, signPos.getY(), signPos.getZ() + 0.5);
					// Visible stands: extraction of invisible entities' name tags is not
					// guaranteed in 26.3 (modern holograms use text displays), so the scene
					// uses plainly rendered, named stands — the nametag-distance path is the
					// same one player names and mob names go through.
					stand.setInvisible(false);
					stand.setNoBasePlate(true);
					stand.setCustomName(Component.literal("Imperium Hologram"));
					stand.setCustomNameVisible(true);
					level.addFreshEntity(stand);
				}
			}
		});
		// Look outward so a good share of the ring is inside the frustum.
		context.runOnClient(mc -> {
			mc.player.setXRot(-5.0F);
			mc.player.setYRot(0.0F);
		});
		context.waitTicks(20);
		int stands = context.computeOnClient(mc -> {
			int found = 0;
			for (var entity : mc.level.entitiesForRendering()) {
				if (entity instanceof ArmorStand) {
					found++;
				}
			}
			return found;
		});
		check(stands >= 10, "the armour-stand scene should be visible on the client, found " + stands + " stands");
		Stats off = measure(context, "text_render_distance", "off", () ->
			context.runOnClient(mc -> AxolotlClient.config().textRenderDistance.set(0)));
		check(PerformanceStats.signTextSkippedCount() == 0 && PerformanceStats.nameTagsSkippedCount() == 0,
			"with text_render_distance off nothing may be skipped");

		PerformanceStats.reset();
		Stats on = measure(context, "text_render_distance", "on", () ->
			context.runOnClient(mc -> AxolotlClient.config().textRenderDistance.set(16)));
		check(PerformanceStats.signTextSkippedCount() > 0, "sign text beyond 16 blocks must be skipped while on");
		String standDiag = context.computeOnClient(mc -> {
			for (var entity : mc.level.entitiesForRendering()) {
				if (entity instanceof ArmorStand stand) {
					double distance = mc.gameRenderer.mainCamera().position().distanceTo(stand.position());
					return "stand at " + stand.blockPosition() + " name=" + stand.hasCustomName()
						+ " nameVisible=" + stand.isCustomNameVisible() + " dist=" + (int) distance
						+ " camPos=" + mc.gameRenderer.mainCamera().position()
						+ " playerYaw=" + (int) mc.player.getYRot();
				}
			}
			return "no stands found client-side";
		});
		check(PerformanceStats.nameTagsSkippedCount() > 0, "armour-stand nametags beyond 16 blocks must be skipped while on (skipped: "
			+ PerformanceStats.nameTagsSkippedCount() + ", submitNameDisplay calls seen: " + PerformanceStats.nameTagDisplaysSeenCount()
			+ "; " + standDiag + ")");
		record(context, "text_render_distance", off, on, "14 signs + 14 visible named armor stands at 22-40 blocks, limit 16");
	}

	/**
	 * disable_tnt_expansion: a ring of long-fuse primed TNT. The swell path only runs during
	 * the final second of the fuse, so the perf delta is expected to be noise — the toggle is
	 * a cannon-mode visual, and the measurement proves it costs nothing; a short-fuse TNT is
	 * summoned during the ON phase to prove the suppression actually fires.
	 */
	private void benchDisableTntExpansion(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockPos center = prepareScene(context, world, 2);
		List<String> commands = new ArrayList<>();
		for (int i = 0; i < 24; i++) {
			double angle = (Math.PI * 2 * i) / 24.0;
			int x = (int) Math.round(Math.cos(angle) * 6);
			int z = (int) Math.round(Math.sin(angle) * 6);
			commands.add("summon minecraft:tnt " + pos(center.offset(x, 1, z)) + " {fuse:160}");
		}
		runCommands(world, commands);
		context.waitTicks(20);

		Stats off = measure(context, "disable_tnt_expansion", "off", () ->
			context.runOnClient(mc -> AxolotlClient.config().disableTntExpansion.set(false)));
		check(PerformanceStats.tntSwellSuppressedCount() == 0, "no swell may be suppressed while the toggle is off");

		// Cool down between the two back-to-back uncapped phases: sustained load downclocks
		// the GPU and would otherwise show up as a fake ON-phase cost (the toggle itself is a
		// single boolean check per TNT submit outside the final fuse second).
		context.waitTicks(100);

		PerformanceStats.reset();
		Stats on = measure(context, "disable_tnt_expansion", "on", () ->
			context.runOnClient(mc -> AxolotlClient.config().disableTntExpansion.set(true)));
		// Exercise the actual swell branch: one TNT inside its final second.
		world.getServer().runCommand("summon minecraft:tnt " + pos(center.offset(6, 1, 6)) + " {fuse:9}");
		context.waitTicks(10);
		check(PerformanceStats.tntSwellSuppressedCount() > 0,
			"a TNT in its final second must have the swell suppressed while the toggle is on");
		record(context, "disable_tnt_expansion", off, on, "24 primed TNT, fuse 160 (swell only in the final second)");

		// Remove the ring before it can explode and crater the later scenes.
		world.getServer().runCommand("kill @e[type=minecraft:tnt]");
		context.waitTicks(20);
	}

	/**
	 * optimize_spawners: sixteen monster spawners in an 8-block ring, each extracting a
	 * spinning display entity every frame while on.
	 */
	private void benchOptimizeSpawners(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockPos center = prepareScene(context, world, 3);
		placeRing(world, center, 16, 8, () -> Blocks.SPAWNER.defaultBlockState());
		// The freshly built platform chunks sync a beat after the server placement; poll
		// instead of assuming a fixed wait.
		int spawners = 0;
		long spawnerDeadline = System.nanoTime() + 20_000_000_000L;
		while (true) {
			int found = context.computeOnClient(mc -> {
				int count = 0;
				for (int i = 0; i < 16; i++) {
					double angle = (Math.PI * 2 * i) / 16.0;
					int x = (int) Math.round(Math.cos(angle) * 8);
					int z = (int) Math.round(Math.sin(angle) * 8);
					if (mc.level.getBlockState(center.offset(x, 0, z)).getBlock() == Blocks.SPAWNER) {
						count++;
					}
				}
				return count;
			});
			spawners = Math.max(spawners, found);
			if (spawners >= 12 || System.nanoTime() > spawnerDeadline) {
				break;
			}
			context.waitTicks(10);
		}
		check(spawners >= 12, "the spawner scene should be synced on the client, found " + spawners + " spawners");

		Stats off = measure(context, "optimize_spawners", "off", () ->
			context.runOnClient(mc -> AxolotlClient.config().optimizeSpawners.set(false)));
		check(PerformanceStats.spawnerEntitiesSkippedCount() == 0, "no spawner entity may be skipped while off");

		PerformanceStats.reset();
		Stats on = measure(context, "optimize_spawners", "on", () ->
			context.runOnClient(mc -> AxolotlClient.config().optimizeSpawners.set(true)));
		check(PerformanceStats.spawnerEntitiesSkippedCount() > 0, "spawner display entities must be skipped while on");
		record(context, "optimize_spawners", off, on, "16 monster spawners in an 8-block ring");
	}

	/**
	 * disable_enchant_table_books: sixteen enchanting tables two blocks out, their floating
	 * books rendered (and animated) every frame while on.
	 */
	private void benchDisableEnchantTableBooks(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockPos center = prepareScene(context, world, 4);
		placeRing(world, center, 16, 2, () -> Blocks.ENCHANTING_TABLE.defaultBlockState());
		context.waitTicks(40); // the books take a moment to open

		Stats off = measure(context, "disable_enchant_table_books", "off", () ->
			context.runOnClient(mc -> AxolotlClient.config().disableEnchantTableBooks.set(false)));
		check(PerformanceStats.enchantBooksSkippedCount() == 0, "no enchanting table book may be skipped while off");

		PerformanceStats.reset();
		Stats on = measure(context, "disable_enchant_table_books", "on", () ->
			context.runOnClient(mc -> AxolotlClient.config().disableEnchantTableBooks.set(true)));
		check(PerformanceStats.enchantBooksSkippedCount() > 0, "enchanting table books must be skipped while on");
		record(context, "disable_enchant_table_books", off, on, "16 enchanting tables in a 2-block ring (books open)");
	}

	// --- hot-spot scenes (issue #22 pass: render-thread work the shipped-feature scenes
	// do not cover) ---

	/** The HUD-heavy "default config" set the hud_tree scene renders (confirmed entry ids). */
	private static final String[][] TREE_HUDS = {
		{"kronhud", "fpshud"}, {"kronhud", "cpshud"}, {"kronhud", "pinghud"}, {"kronhud", "tpshud"},
		{"kronhud", "coordshud"}, {"kronhud", "compasshud"}, {"kronhud", "armorhud"},
		{"kronhud", "potionshud"}, {"kronhud", "togglesprint"}, {"kronhud", "iphud"},
		{"kronhud", "speedhud"}, {"kronhud", "arrowhud"}, {"kronhud", "itemupdatehud"},
		{"kronhud", "irltimehud"}, {"kronhud", "reachhud"}, {"kronhud", "combohud"},
		{"kronhud", "mousemovementhud"}, {"kronhud", "playerhud"},
		{"axolotlclient", "inventoryhud"}, {"axolotlclient", "iconhud"},
		{"imperiumclient", "obsidiancounthud"}, {"imperiumclient", "playtimehud"}
	};

	/**
	 * minimap_hud: the chunk-colour minimap with its grid sampled and re-uploaded from the
	 * client tick. Measured over tick windows (several seconds) rather than a fixed frame
	 * count, because the cost pattern is periodic work on the tick thread — a full-grid
	 * re-sample burst — which a short frame window can miss entirely. P95 and especially the
	 * max frame time carry the signal; the average mostly measures the blit.
	 */
	private void benchMinimap(ClientGameTestContext context, TestSingleplayerContext world) {
		prepareScene(context, world, 5);
		context.runOnClient(mc -> setHudEnabled("imperiumclient", "minimaphud", false));
		context.waitTicks(40);

		Stats off = measureTicks(context, "minimap_hud", "off", () ->
			context.runOnClient(mc -> setHudEnabled("imperiumclient", "minimaphud", false)), 20, 100);

		context.runOnClient(mc -> setHudEnabled("imperiumclient", "minimaphud", true));
		// Fill the grid before measuring: the initial full fill after enable is a one-time
		// cost, the periodic refresh is the recurring one being measured.
		context.waitTicks(80);
		long sweepsBefore = context.computeOnClient(mc -> minimapTexture(mc).sweepCompletions());
		int texels = context.computeOnClient(mc -> minimapTexture(mc).sampledTexels());
		check(texels > 0, "the minimap grid should have sampled columns, got " + texels);
		Stats on = measureTicks(context, "minimap_hud", "on", () -> {}, 20, 100);
		long sweepsAfter = context.computeOnClient(mc -> minimapTexture(mc).sweepCompletions());
		// The rolling sweep is the steady-state refresh: with nothing marking texels dirty,
		// it must still have wrapped the whole grid at least once in a 120-tick window.
		check(sweepsAfter > sweepsBefore,
			"the rolling refresh sweep must keep re-sampling the grid while enabled (completions "
				+ sweepsBefore + " -> " + sweepsAfter + ")");
		record(context, "minimap_hud", off, on, "128px square minimap, static player, 120-tick windows");

		context.runOnClient(mc -> setHudEnabled("imperiumclient", "minimaphud", false));
	}

	private static MinimapTexture minimapTexture(Minecraft mc) {
		var entry = HudManager.getInstance().get(AxoIdentifier.of("imperiumclient", "minimaphud"));
		check(entry instanceof MinimapHud, "minimap entry should be a MinimapHud");
		return ((MinimapHud) entry).getTexture();
	}

	/**
	 * hud_tree: our whole HUD render pass with a HUD-heavy enabled set (22 entries), versus
	 * the global hud.enabled toggle off. The delta is the mod's own HUD cost per frame —
	 * the frame loop, the entry list handling, and every enabled entry's render.
	 */
	private void benchHudTree(ClientGameTestContext context, TestSingleplayerContext world) {
		prepareScene(context, world, 6);
		context.runOnClient(mc -> {
			for (String[] hud : TREE_HUDS) {
				setHudEnabled(hud[0], hud[1], true);
			}
		});
		world.getServer().runCommand("item replace entity @s armor.head with diamond_helmet");
		world.getServer().runCommand("item replace entity @s armor.chest with diamond_chestplate");
		world.getServer().runCommand("item replace entity @s armor.legs with diamond_leggings");
		world.getServer().runCommand("item replace entity @s armor.feet with diamond_boots");
		world.getServer().runCommand("give @s diamond 64");
		context.waitTicks(20);

		Stats off = measure(context, "hud_tree", "off", () -> context.runOnClient(mc ->
			HudManager.getInstance().setHudsEnabled(false)));
		Stats on = measure(context, "hud_tree", "on", () -> context.runOnClient(mc ->
			HudManager.getInstance().setHudsEnabled(true)));
		record(context, "hud_tree", off, on, "22 HUD entries enabled (text readouts + item HUDs)");

		context.runOnClient(mc -> {
			HudManager.getInstance().setHudsEnabled(true);
			for (String[] hud : TREE_HUDS) {
				setHudEnabled(hud[0], hud[1], false);
			}
		});
	}

	/**
	 * menu_screen: the Imperium menu open over the world versus no screen. The delta is the
	 * menu's own per-frame render cost (sidebar, category chips, card grid, header) on top
	 * of the world render.
	 */
	private void benchMenuScreen(ClientGameTestContext context, TestSingleplayerContext world) {
		prepareScene(context, world, 7);

		Stats off = measure(context, "menu_screen", "off", () -> context.runOnClient(mc -> {
			if (mc.gui.screen() != null) {
				mc.gui.setScreen(null);
			}
		}));
		Stats on = measure(context, "menu_screen", "on", () -> context.runOnClient(mc ->
			mc.gui.setScreen(new ImperiumMenuScreen(null))));
		boolean stillOpen = context.computeOnClient(mc -> mc.gui.screen() instanceof ImperiumMenuScreen);
		check(stillOpen, "the menu screen must still be open after the measured window");
		context.runOnClient(mc -> mc.gui.setScreen(null));
		record(context, "menu_screen", off, on, "Imperium menu (v3 theme) open over the world");
	}

	/**
	 * chat_hud: chat history filled and visible, smooth chat off versus on. Bounds the
	 * ChatHudMixin per-frame wrap cost; in steady state (no animation in flight) the
	 * expected delta is inside the noise band — the number exists so "noise" is a measured
	 * claim, not an assumption.
	 */
	private void benchChat(ClientGameTestContext context, TestSingleplayerContext world) {
		prepareScene(context, world, 8);
		List<String> commands = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			commands.add("tellraw @s {\"text\":\"benchmark chat line " + i + "\",\"color\":\"white\"}");
		}
		runCommands(world, commands);
		context.waitTicks(20);

		Stats off = measure(context, "chat_hud", "off", () -> context.runOnClient(mc ->
			AxolotlClient.config().smoothChat.set(false)));
		Stats on = measure(context, "chat_hud", "on", () -> context.runOnClient(mc ->
			AxolotlClient.config().smoothChat.set(true)));
		record(context, "chat_hud", off, on, "40 message history visible, no animation in flight");
	}

	// --- helpers ---

	private static String pos(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	private void runCommands(TestSingleplayerContext world, List<String> commands) {
		world.getServer().runOnServer(server -> {
			for (String command : commands) {
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
			}
		});
	}

	/**
	 * Builds a clean 91x91 stone platform high in the sky, far from every earlier scene
	 * (TNT craters, walls, trees), and teleports the player onto it. Every benchmark scene
	 * gets its own platform so the OFF and ON phases see identical, uncluttered geometry;
	 * the 45-block half-width keeps the 22-40 block sign and armour-stand ring on the floor
	 * (a ring wider than the platform falls off the edge and out of the camera's view).
	 */
	private BlockPos prepareScene(ClientGameTestContext context, TestSingleplayerContext world, int sceneIndex) {
		BlockPos center = new BlockPos(sceneIndex * 300, 100, 0);
		java.util.UUID playerId = context.computeOnClient(mc -> mc.player.getUUID());
		world.getServer().runOnServer(server -> {
			ServerLevel level = server.overworld();
			for (int dx = -45; dx <= 45; dx++) {
				for (int dz = -45; dz <= 45; dz++) {
					level.setBlockAndUpdate(center.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
					for (int dy = 0; dy <= 6; dy++) {
						level.setBlockAndUpdate(center.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
					}
				}
			}
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player != null) {
				player.teleportTo(level, center.getX() + 0.5, center.getY(), center.getZ() + 0.5,
					java.util.Set.of(), 0.0F, -5.0F, false);
			}
		});
		world.getConnection().waitForChunksRender();
		context.waitTicks(20);
		return center;
	}

	private void placeRing(TestSingleplayerContext world, BlockPos center, int count, int radius,
						   Supplier<BlockState> block) {
		world.getServer().runOnServer(server -> {
			ServerLevel level = server.overworld();
			for (int i = 0; i < count; i++) {
				double angle = (Math.PI * 2 * i) / (double) count;
				int x = (int) Math.round(Math.cos(angle) * radius);
				int z = (int) Math.round(Math.sin(angle) * radius);
				level.setBlockAndUpdate(center.offset(x, 0, z), block.get());
			}
		});
	}

	private void setHudEnabled(String namespace, String path, boolean enabled) {
		var entry = HudManager.getInstance().get(AxoIdentifier.of(namespace, path));
		check(entry != null, "HUD entry " + namespace + ":" + path + " should exist");
		entry.setEnabled(enabled);
	}

	/**
	 * Installs the frame sampler, applies the phase setup, waits through the warmup and the
	 * measurement window, and returns the stats of the measured frames.
	 */
	private Stats measure(ClientGameTestContext context, String feature, String phase, Runnable applySetup) {
		List<Long> samples = Collections.synchronizedList(new ArrayList<>());
		FrameTimeSampler.install(samples::add);
		applySetup.run();
		long deadline = System.nanoTime() + PHASE_TIMEOUT_NANOS;
		while (samples.size() < WARMUP_FRAMES + MEASURE_FRAMES) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError(feature + "/" + phase + ": only " + samples.size() + " of "
					+ (WARMUP_FRAMES + MEASURE_FRAMES) + " frames arrived within the timeout — rendering is not alive");
			}
			context.waitTicks(5);
		}
		FrameTimeSampler.clear();
		List<Long> measured = new ArrayList<>(samples.subList(WARMUP_FRAMES, WARMUP_FRAMES + MEASURE_FRAMES));
		double[] millis = measured.stream().mapToDouble(ns -> ns / 1_000_000.0).sorted().toArray();
		return new Stats(millis);
	}

	/**
	 * Tick-windowed variant of {@link #measure} for cost that lives on the client tick
	 * (periodic sampling bursts, timers): collects frames across a wall-clock window of
	 * game ticks instead of a fixed frame count, so periodic spikes always land inside the
	 * measured span. The first {@code warmupTicks} are discarded; the frame stats cover the
	 * following {@code measureTicks} window.
	 */
	private Stats measureTicks(ClientGameTestContext context, String feature, String phase, Runnable applySetup,
							   int warmupTicks, int measureTicks) {
		List<Long> samples = Collections.synchronizedList(new ArrayList<>());
		FrameTimeSampler.install(samples::add);
		applySetup.run();
		final int slice = 5;
		int totalSlices = (warmupTicks + measureTicks) / slice;
		int warmupSlices = warmupTicks / slice;
		int[] marks = new int[totalSlices + 1];
		long deadline = System.nanoTime() + PHASE_TIMEOUT_NANOS;
		for (int i = 1; i <= totalSlices; i++) {
			context.waitTicks(slice);
			marks[i] = samples.size();
			check(System.nanoTime() <= deadline || marks[i] > marks[i - 1],
				feature + "/" + phase + ": frames stopped arriving — rendering is not alive");
		}
		FrameTimeSampler.clear();
		check(marks[totalSlices] > marks[warmupSlices],
			feature + "/" + phase + ": no frames measured across " + measureTicks + " ticks");
		List<Long> measured = new ArrayList<>(samples.subList(marks[warmupSlices], marks[totalSlices]));
		double[] millis = measured.stream().mapToDouble(ns -> ns / 1_000_000.0).sorted().toArray();
		return new Stats(millis);
	}

	private void record(ClientGameTestContext context, String feature, Stats off, Stats on, String scene) {
		int fps = context.computeOnClient(Minecraft::getFps);
		check(fps > 0, "the client must still report a live FPS after " + feature);
		JsonObject line = new JsonObject();
		line.addProperty("feature", feature);
		line.addProperty("scene", scene);
		line.addProperty("frames", MEASURE_FRAMES);
		JsonObject offJson = new JsonObject();
		offJson.addProperty("avg_ms", round(off.avg));
		offJson.addProperty("p95_ms", round(off.p95));
		offJson.addProperty("max_ms", round(off.max));
		offJson.addProperty("fps_avg", round(1000.0 / off.avg));
		JsonObject onJson = new JsonObject();
		onJson.addProperty("avg_ms", round(on.avg));
		onJson.addProperty("p95_ms", round(on.p95));
		onJson.addProperty("max_ms", round(on.max));
		onJson.addProperty("fps_avg", round(1000.0 / on.avg));
		line.add("off", offJson);
		line.add("on", onJson);
		line.addProperty("delta_avg_ms", round(off.avg - on.avg));
		line.addProperty("delta_fps", round(1000.0 / on.avg - 1000.0 / off.avg));
		try (BufferedWriter writer = Files.newBufferedWriter(output, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
			writer.write(GSON.toJson(line));
			writer.newLine();
		} catch (IOException e) {
			throw new AssertionError("failed to append the benchmark result for " + feature, e);
		}
	}

	private static double round(double value) {
		return Math.round(value * 1000.0) / 1000.0;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static final class Stats {
		final double avg;
		final double p95;
		final double max;

		Stats(double[] sortedMillis) {
			double sum = 0;
			for (double v : sortedMillis) {
				sum += v;
				check(v > 0.0 && v < 1000.0, "frame time out of a sane range: " + v + " ms");
			}
			this.avg = sum / sortedMillis.length;
			this.p95 = sortedMillis[(int) Math.min(sortedMillis.length - 1, Math.floor(sortedMillis.length * 0.95))];
			this.max = sortedMillis[sortedMillis.length - 1];
		}
	}
}
