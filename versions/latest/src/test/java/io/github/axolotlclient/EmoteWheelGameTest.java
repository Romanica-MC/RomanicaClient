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
import java.util.concurrent.CopyOnWriteArrayList;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.axolotlclient.imperium.emotes.Emote;
import io.github.axolotlclient.imperium.emotes.EmoteDispatch;
import io.github.axolotlclient.imperium.emotes.EmoteDispatcher;
import io.github.axolotlclient.imperium.emotes.EmoteWheel;
import io.github.axolotlclient.imperium.emotes.EmoteWheelConfig;
import io.github.axolotlclient.imperium.emotes.EmoteWheelScreen;
import io.github.axolotlclient.imperium.emotes.Emotes;
import io.github.axolotlclient.imperium.emotes.LocalEmoteDispatcher;
import io.github.axolotlclient.imperium.menu.CarvedTheme;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;

/**
 * Emote wheel shell tests (W5-A). Two halves, both in the headless gametest harness:
 *
 * <ul>
 *   <li><strong>Pure shell:</strong> slot parsing (built-ins, custom triples, junk), the
 *       dispatcher seam swap/reset, and that the local dispatcher really calls its log and
 *       toast hooks.</li>
 *   <li><strong>Wheel UX in a world:</strong> holding the keybind opens the carved wheel,
 *       pointing at a slot selects it, releasing the key sends that emote through the
 *       installed dispatcher and closes the wheel (without the stuck-key reopen), clicking a
 *       slot sends immediately, and ESC cancels without sending. Screenshots at each step.</li>
 * </ul>
 */
public class EmoteWheelGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this suite is render-heavy, see ImperiumGametestSubsets
		GametestBookkeeping.executed(getClass().getSimpleName());
		pureShellTests();
		wheelUxTests(context);
	}

	private void pureShellTests() {
		// slot parsing: built-ins resolve case/space-insensitively
		check(Emotes.parseSlot("").isEmpty(), "blank slot must be empty");
		check(Emotes.parseSlot("wave").orElseThrow().equals(Emotes.WAVE), "bare built-in id resolves");
		check(Emotes.parseSlot("  SALUTE ").orElseThrow().equals(Emotes.SALUTE), "built-in lookup tolerates case/spaces");
		// custom triple: id;name;color
		Emote custom = Emotes.parseSlot("dance;Dance;#00FF00").orElseThrow();
		check(custom.id().equals("dance") && custom.name().equals("Dance") && custom.color() == 0xFF00FF00,
			"custom slot parses id/name/color, got " + custom);
		// an unknown bare id is not usable (would be un-sendable on the future channel)
		check(Emotes.parseSlot("backflip").isEmpty(), "unknown bare id must not resolve");
		check(Emotes.parseSlot(";nope").isEmpty(), "slot without an id must not resolve");
		// color fallback keeps the bronze brand instead of an invisible slot
		check(Emotes.parseColor("zzz") == CarvedTheme.BRONZE, "bad color falls back to bronze");
		check(Emotes.parseColor("#AaBbCc") == 0xFFAABBCC, "#RRGGBB parses");
		// default slots load exactly the four built-ins, in order
		List<Emote> defaults = Emotes.parseSlots(List.of("wave", "salute", "cheer", "sit", "", "", "", ""));
		check(defaults.equals(Emotes.builtIns()), "default slots are the four built-ins in order");

		// the seam: swapping the dispatcher re-routes wheel sends; reset restores the local one
		List<String> sent = new CopyOnWriteArrayList<>();
		EmoteDispatch.set(emote -> sent.add(emote.id()));
		EmoteDispatch.dispatch(Emotes.CHEER);
		check(sent.equals(List.of("cheer")), "dispatch through swapped dispatcher, got " + sent);
		EmoteDispatch.reset();
		check(EmoteDispatch.get() instanceof LocalEmoteDispatcher, "reset restores the local dispatcher");

		// the local dispatcher composes log + toast for the same emote
		List<String> logged = new ArrayList<>(), toasted = new ArrayList<>();
		LocalEmoteDispatcher recording = new LocalEmoteDispatcher() {
			@Override
			protected void log(Emote emote) {
				logged.add(emote.id());
			}

			@Override
			protected void showToast(Emote emote) {
				toasted.add(emote.id());
			}
		};
		recording.dispatch(Emotes.SIT);
		check(logged.equals(List.of("sit")) && toasted.equals(List.of("sit")),
			"local dispatcher logs and toasts the sent emote, logged=" + logged + " toasted=" + toasted);
	}

	private void wheelUxTests(ClientGameTestContext context) {
		// The run directory keeps config between runs; pin the wheel state for determinism.
		context.runOnClient(mc -> {
			EmoteWheelConfig.emotes.set(true);
			String[] defaults = {"wave", "salute", "cheer", "sit", "", "", "", ""};
			for (int i = 0; i < defaults.length; i++) {
				EmoteWheelConfig.setSlot(i + 1, defaults[i]);
			}
		});

		List<String> sent = new CopyOnWriteArrayList<>();
		context.runOnClient(mc -> EmoteDispatch.set(emote -> sent.add(emote.id())));
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();

			check(EmoteWheel.slots().equals(Emotes.builtIns()), "wheel loads the four built-in emotes");

			// hold the keybind -> the wheel opens over the world
			context.getInput().holdKey(EmoteWheel.OPEN_WHEEL);
			context.waitForScreen(EmoteWheelScreen.class);
			context.waitTicks(5);
			context.takeScreenshot("emote-wheel-open");

			// pointing at a slot selects it (slot 1 = salute, 3 o'clock with four emotes)
			double scale = context.computeOnClient(Minecraft::getWindow).getGuiScale();
			int[] slot1 = context.computeOnClient(mc -> wheel(mc).slotCenter(1));
			context.getInput().setCursorPos(slot1[0] * scale, slot1[1] * scale);
			context.waitTicks(3);
			check(context.computeOnClient(mc -> wheel(mc).selectedSlot()) == 1,
				"pointer on slot 1 should select it");
			context.takeScreenshot("emote-wheel-highlight");

			// dead zone: the hub itself selects nothing
			check(context.computeOnClient(mc -> wheel(mc).slotAt(mc.getWindow().getGuiScaledWidth() / 2d,
				mc.getWindow().getGuiScaledHeight() / 2d)) == -1, "wheel centre must be the dead zone");

			// releasing the key sends the selected emote and closes the wheel
			context.getInput().releaseKey(EmoteWheel.OPEN_WHEEL);
			context.waitTicks(3);
			check(context.computeOnClient(mc -> mc.gui.screen() == null), "wheel should close on key release");
			check(sent.equals(List.of("salute")), "releasing on slot 1 should send salute, got " + sent);
			context.takeScreenshot("emote-wheel-after-send");

			// the held key must not re-open the wheel after close (stuck-key regression)
			context.waitTicks(10);
			check(context.computeOnClient(mc -> mc.gui.screen() == null), "wheel must not reopen after close");

			// clicking a slot sends immediately, without waiting for the key release
			// (release first: the test input ignores a hold for a key it already holds)
			context.getInput().releaseKey(EmoteWheel.OPEN_WHEEL);
			context.waitTicks(2);
			context.getInput().holdKey(EmoteWheel.OPEN_WHEEL);
			context.waitForScreen(EmoteWheelScreen.class);
			context.waitTicks(3);
			int[] slot0 = context.computeOnClient(mc -> wheel(mc).slotCenter(0));
			context.getInput().setCursorPos(slot0[0] * scale, slot0[1] * scale);
			context.waitTicks(2);
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(3);
			check(sent.equals(List.of("salute", "wave")), "clicking slot 0 should send wave, got " + sent);
			check(context.computeOnClient(mc -> mc.gui.screen() == null), "wheel should close after click send");

			// ESC cancels without sending
			context.getInput().releaseKey(EmoteWheel.OPEN_WHEEL);
			context.waitTicks(2);
			context.getInput().holdKey(EmoteWheel.OPEN_WHEEL);
			context.waitForScreen(EmoteWheelScreen.class);
			context.waitTicks(3);
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitTicks(3);
			check(context.computeOnClient(mc -> mc.gui.screen() == null), "ESC should close the wheel");
			check(sent.equals(List.of("salute", "wave")), "ESC must cancel without sending, got " + sent);

			context.getInput().releaseKey(EmoteWheel.OPEN_WHEEL);
		} finally {
			context.runOnClient(mc -> EmoteDispatch.reset());
		}
	}

	private static EmoteWheelScreen wheel(Minecraft mc) {
		if (mc.gui.screen() instanceof EmoteWheelScreen screen) return screen;
		throw new AssertionError("expected EmoteWheelScreen, got " + mc.gui.screen());
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
