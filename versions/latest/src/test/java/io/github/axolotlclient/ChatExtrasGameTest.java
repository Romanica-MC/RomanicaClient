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

import io.github.axolotlclient.imperium.ImperiumClientSession;
import io.github.axolotlclient.modules.chat.ChatHistoryCodec;
import io.github.axolotlclient.modules.chat.ChatPersistence;
import io.github.axolotlclient.modules.chat.ChatScreenshot;
import io.github.axolotlclient.modules.chat.QuickPhrases;
import io.github.axolotlclient.util.chat.ChatScrollSmoothing;
import io.github.axolotlclient.util.duck.ChatHudExtension;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

/**
 * Wave 5 chat_settings extras, exercised end to end on a real client:
 *
 * <ul>
 *   <li><strong>Smooth chat</strong> — the pure interpolation math (approach direction,
 *       frame-time sensitivity, snap) plus a live check that a burst of messages kicks the
 *       animation below its target and rendered frames carry it back to the target.</li>
 *   <li><strong>Persistent chat</strong> — the codec round-trip (styled components, sibling
 *       trees, the 100-line cap) and the module save/restore loop against the real sidecar
 *       file, including the no-duplicate rule.</li>
 *   <li><strong>Chat screenshot</strong> — the pure GUI-to-pixel crop-rect mapping including
 *       clamping (the capture itself needs a real frame; its plumbing is code-reviewed).</li>
 *   <li><strong>Quick phrases</strong> — a phrase pressed through the module's send path
 *       lands in the player's own chat history via the server round-trip (singleplayer's
 *       integrated server), i.e. exactly what the enforcement layer and a remote server
 *       would see; and a server deny locks the module off.</li>
 * </ul>
 */
public class ChatExtrasGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this suite is render-heavy, see ImperiumGametestSubsets
		GametestBookkeeping.executed(getClass().getSimpleName());
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();

			smoothScrollMath();
			smoothChatAnimatesLiveHistory(context);
			historyCodecRoundTrip();
			persistenceSaveRestoreLoop(context);
			cropRectMath();
			quickPhraseSendDrivesRealChat(context);
			quickPhraseDenyLocksModule(context);
		} finally {
			context.runOnClient(mc -> {
				AxolotlClient.config().persistentChat.set(true);
				AxolotlClient.config().smoothChat.set(true);
				QuickPhrases.getInstance().enabled.setForceOff(false, "");
				QuickPhrases.getInstance().phrase1.set("gg");
				QuickPhrases.getInstance().phrase2.set("");
				ImperiumClientSession.getInstance().reset();
				mc.gui.hud.getChat().clearMessages(true);
			});
		}
	}

	// ── Smooth chat ──────────────────────────────────────────────────────────────

	private void smoothScrollMath() {
		// Monotone approach toward the target, never past it.
		float current = 0.0F;
		for (int i = 0; i < 40; i++) {
			float next = ChatScrollSmoothing.step(current, 5.0F, 16L, 120);
			check(next >= current - ChatScrollSmoothing.SNAP_THRESHOLD && next <= 5.0F + ChatScrollSmoothing.SNAP_THRESHOLD,
				"smooth step must approach the target monotonically, got " + current + " -> " + next);
			current = next;
		}
		check(Math.abs(current - 5.0F) < 0.5F, "40 frames of 16 ms must be settled near the target, got " + current);

		// Same-frame re-sampling is a no-op (the background and text render passes).
		check(ChatScrollSmoothing.step(2.0F, 4.0F, 0L, 120) == 2.0F, "a zero-dt step must not advance the animation");

		// Snap when close enough.
		check(ChatScrollSmoothing.step(4.995F, 5.0F, 16L, 120) == 5.0F, "near-target steps must snap");

		// Frame-time aware: a longer frame covers proportionally more of the transition.
		float shortFrame = ChatScrollSmoothing.step(0.0F, 5.0F, 8L, 120);
		float longFrame = ChatScrollSmoothing.step(0.0F, 5.0F, 60L, 120);
		check(longFrame > shortFrame, "a longer frame must advance the animation further");

		// Sub-line helpers: fraction stays in [0,1), the window base floors and clamps at 0.
		check(ChatScrollSmoothing.fraction(3.75F) == 0.75F, "fraction must be the sub-line remainder");
		check(ChatScrollSmoothing.fraction(-0.4F) == 0.6F, "negative (slide-in) positions carry a fraction too");
		check(ChatScrollSmoothing.windowBase(-0.4F) == 0, "window base must clamp at the newest line");
		check(ChatScrollSmoothing.windowBase(3.75F) == 3, "window base must floor");
		check(ChatScrollSmoothing.pixelOffset(3.5F, 10.0F) == 5.0F, "pixel offset is the fraction of one line height");
	}

	/**
	 * Live half of smooth chat: with the option on, a burst of messages must kick the
	 * animation below its target (the slide-in), and rendered frames must carry it back
	 * toward the pinned-bottom target. Reads the animation through the same duck interface
	 * the render path writes through.
	 */
	private void smoothChatAnimatesLiveHistory(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			AxolotlClient.config().smoothChat.set(true);
			ChatComponent chat = mc.gui.hud.getChat();
			chat.clearMessages(true);
			chat.resetChatScroll();
			for (int i = 0; i < 3; i++) {
				chat.addClientSystemMessage(Component.literal("smooth probe " + i));
			}
			// add and read in the same client-tick: no frame has stepped the animation yet.
			float afterBurst = ((ChatHudExtension) chat).axolotlclient$getSmoothScrollCurrent();
			check(afterBurst < 0.0F,
				"a burst at the bottom must start the animation below the target (slide-in), got " + afterBurst);
		});
		// Let real frames render: the public render hook steps the animation each frame.
		context.waitTicks(5);
		context.runOnClient(mc -> {
			float afterFrames = ((ChatHudExtension) mc.gui.hud.getChat()).axolotlclient$getSmoothScrollCurrent();
			check(afterFrames > -3.0F, "rendered frames must advance the slide-in toward the target, got " + afterFrames);
			mc.gui.hud.getChat().clearMessages(true);
		});
	}

	// ── Persistent chat ──────────────────────────────────────────────────────────

	private void historyCodecRoundTrip() {
		// The live history is newest-first (index 0 = newest); build the fixture the same way.
		List<GuiMessage> messages = new ArrayList<>();
		var tree = Component.literal("head");
		tree.append(Component.literal("+tail"));
		messages.add(new GuiMessage(300, tree, null, GuiMessageSource.PLAYER, null));
		messages.add(new GuiMessage(200, Component.literal("styled").withStyle(ChatFormatting.GOLD), null, GuiMessageSource.SYSTEM_SERVER, null));
		messages.add(new GuiMessage(100, Component.literal("plain line"), null, GuiMessageSource.SYSTEM_CLIENT, null));
		for (int i = 0; i < 98; i++) {
			messages.add(new GuiMessage(400 + i, Component.literal("filler " + i), null, GuiMessageSource.SYSTEM_CLIENT, null));
		}
		check(messages.size() == ChatHistoryCodec.MAX_LINES + 1, "fixture must overflow the cap by exactly one");

		List<GuiMessage> restored = ChatHistoryCodec.fromJson(ChatHistoryCodec.toJson(messages), 5_000);
		check(restored.size() == ChatHistoryCodec.MAX_LINES,
			"codec must cap the history at " + ChatHistoryCodec.MAX_LINES + ", got " + restored.size());
		// The one dropped message is the oldest filler (filler 97); the kept tail is filler 96.
		check(restored.get(restored.size() - 1).content().getString().equals("filler 96"),
			"the oldest kept message must be the newest-kept filler");
		GuiMessage restoredTree = restored.get(0);
		check(restoredTree.content().getString().equals("head+tail"), "round-trip must preserve sibling trees");
		GuiMessage styled = restored.get(1);
		check(styled.content().getString().equals("styled"), "round-trip must preserve the visible text");
		check(TextColor.fromLegacyFormat(ChatFormatting.GOLD).equals(styled.content().getStyle().getColor()),
			"round-trip must preserve the style (gold color)");
		GuiMessage plain = restored.get(2);
		check(plain.content().getString().equals("plain line"), "round-trip must preserve plain literals");
		check(restored.stream().allMatch(m -> m.addedTime() <= 5_000 - 20),
			"restored addedTime must be clamped to at least 20 ticks old");
	}

	/** Save/restore through the real sidecar file and the live chat hud. */
	private void persistenceSaveRestoreLoop(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			AxolotlClient.config().persistentChat.set(true);
			doubleClear(mc);
			mc.gui.hud.getChat().addClientSystemMessage(Component.literal("persist me"));
			ChatPersistence.getInstance().save();
		});

		context.runOnClient(mc -> {
			// Simulate the relog: vanilla starts a fresh chat; the module's restore refills it.
			doubleClear(mc);
			check(history(mc).isEmpty(), "after clears the live history must be empty");
			ChatPersistence.getInstance().restore();
			check(!history(mc).isEmpty() && history(mc).getFirst().content().getString().equals("persist me"),
				"restore must refill the empty history from the sidecar");
			// A second restore must not duplicate (non-empty history is left alone).
			ChatPersistence.getInstance().restore();
			check(history(mc).stream().filter(m -> m.content().getString().equals("persist me")).count() == 1,
				"restore must be a no-op when history is not empty");
		});

		context.runOnClient(mc -> {
			ChatPersistence.getInstance().clear();
			check(history(mc).stream().noneMatch(m -> m.content().getString().equals("persist me")),
				"clear must wipe the live history");
			ChatPersistence.getInstance().restore();
			check(history(mc).stream().noneMatch(m -> m.content().getString().equals("persist me")),
				"clear must delete the sidecar too");
		});
	}

	/**
	 * clearMessages flushes the chat listener's delayed queue into the history before
	 * clearing, so one clear can leave freshly-flushed lines behind; the second one ends
	 * with a genuinely empty history (no packets interleave inside a single runOnClient).
	 */
	private static void doubleClear(Minecraft mc) {
		mc.gui.hud.getChat().clearMessages(true);
		mc.gui.hud.getChat().clearMessages(true);
	}

	// ── Chat screenshot ──────────────────────────────────────────────────────────

	private void cropRectMath() {
		// 1920x1080 framebuffer at GUI scale 2 -> 960x540 GUI units; a 320x160 chat sits
		// above the 40 px bottom margin.
		var rect = ChatScreenshot.cropRect(1920, 1080, 2.0, 320, 160, 540, 2);
		check(rect.x() == 0, "crop starts at the left screen edge");
		check(rect.y() == (540 - 40 - 160 - 2) * 2, "crop top must map GUI units to pixels");
		check(rect.width() == (320 + 2) * 2, "crop width must pad to the right edge (left pad clamps at x=0)");
		check(rect.y() + rect.height() == (540 - 40 + 2) * 2, "crop bottom must sit under the chat margin");

		// Clamping: a chat bigger than the image collapses to the image bounds, never outside.
		var clamped = ChatScreenshot.cropRect(200, 100, 2.0, 320, 160, 540, 2);
		check(clamped.x() >= 0 && clamped.y() >= 0
			&& clamped.x() + clamped.width() <= 200 && clamped.y() + clamped.height() <= 100,
			"crop rect must stay inside the image");
		check(clamped.width() >= 1 && clamped.height() >= 1, "clamped crop must stay non-empty");
	}

	// ── Quick phrases ────────────────────────────────────────────────────────────

	private void quickPhraseSendDrivesRealChat(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			ImperiumClientSession.getInstance().reset();
			QuickPhrases.getInstance().enabled.set(true);
			QuickPhrases.getInstance().enabled.setForceOff(false, "");
			QuickPhrases.getInstance().phrase1.set("imperium quick phrase probe");
			mc.gui.hud.getChat().clearMessages(true);
			// The exact call a keybind press performs.
			QuickPhrases.getInstance().sendPhrase(QuickPhrases.getInstance().phrase1);
		});
		context.waitTicks(10); // client -> integrated server -> broadcast -> client chat listener
		context.runOnClient(mc -> {
			boolean seen = history(mc).stream().anyMatch(m -> m.content().getString().contains("imperium quick phrase probe"));
			check(seen, "the phrase must reach chat through the normal send path (server round-trip)");
		});

		// Empty slots are silent no-ops.
		context.runOnClient(mc -> {
			int before = history(mc).size();
			QuickPhrases.getInstance().phrase2.set("   ");
			QuickPhrases.getInstance().sendPhrase(QuickPhrases.getInstance().phrase2);
			check(history(mc).size() == before, "an empty phrase slot must send nothing");
		});
	}

	private void quickPhraseDenyLocksModule(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			ImperiumClientSession.getInstance().applyFeatures("{\"version\":2,\"deny\":[\"quick_phrases\"]}");
			QuickPhrases.getInstance().tick(); // what every client tick does
			check(QuickPhrases.getInstance().enabled.isForceOff(),
				"a server deny must force-off the quick phrases module");
			QuickPhrases.getInstance().phrase1.set("locked probe");
			int before = history(mc).size();
			QuickPhrases.getInstance().sendPhrase(QuickPhrases.getInstance().phrase1);
			check(history(mc).size() == before, "a locked module's keybind must send nothing");
		});
	}

	// ── helpers ──────────────────────────────────────────────────────────────────

	private static List<GuiMessage> history(Minecraft mc) {
		return ((ChatHudExtension) mc.gui.hud.getChat()).axolotlclient$getMessages();
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
