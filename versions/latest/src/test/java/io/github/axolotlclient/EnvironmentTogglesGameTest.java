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

import com.mojang.blaze3d.platform.InputConstants;
import io.github.axolotlclient.AxolotlClientConfigCommon;
import io.github.axolotlclient.AxolotlClientConfigCommon.WeatherOverride;
import io.github.axolotlclient.modules.hud.HudManager;
import io.github.axolotlclient.modules.hud.gui.hud.simple.ToggleSprintHud;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * Exercises the Alpine-parity environment/toggle options end to end in a real world:
 * the client-side weather override (and that the integrated server is untouched),
 * the time changer's time multiplier (world time passes at N x visual speed),
 * the ToggleSprint double-tap sneak toggle, fly boost while holding the sprint key,
 * and the nametag editor scale/background render path.
 */
public class EnvironmentTogglesGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this suite is render-heavy, see ImperiumGametestSubsets
		GametestBookkeeping.executed(getClass().getSimpleName());
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();

			testWeatherOverride(context);
			testTimeMultiplier(context);
			testDoubleTapSneak(context);
			testFlyBoost(context);
			testNametagOptions(context);
		} finally {
			context.runOnClient(mc -> {
				AxolotlClient.config().weather.set(WeatherOverride.OFF);
				AxolotlClient.config().weatherIntensity.set(1.0F);
				AxolotlClient.config().timeMultiplier.set(1.0F);
				AxolotlClient.config().timeChangerEnabled.set(false);
				AxolotlClient.config().nametagScale.set(1.0D);
				AxolotlClient.config().nametagBackground.set(AxolotlClientConfigCommon.NametagBackgroundMode.TRANSLUCENT);
				AxolotlClient.config().showOwnNametag.set(false);
				mc.options.setCameraType(CameraType.FIRST_PERSON);
				mc.options.keySprint.setDown(false);
				var hud = toggleSprintHud();
				hud.getSneakToggled().set(false);
				hud.getSprintToggled().set(false);
				if (mc.player != null) {
					mc.player.getAbilities().flying = false;
				}
			});
		}
	}

	private void testWeatherOverride(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			AxolotlClient.config().weatherIntensity.set(0.75F);
			AxolotlClient.config().weather.set(WeatherOverride.RAIN);
		});
		context.waitTicks(2);
		check(context.computeOnClient(mc -> mc.level.getRainLevel(1.0F)) == 0.75F, "rain override should force the rain level to the configured intensity");
		check(context.computeOnClient(mc -> mc.level.getThunderLevel(1.0F)) == 0.0F, "rain override should not report thunder");
		check(context.computeOnClient(mc -> mc.level.isRaining()), "rain override should make the client level report raining");

		context.runOnClient(mc -> AxolotlClient.config().weather.set(WeatherOverride.THUNDER));
		context.waitTicks(2);
		check(context.computeOnClient(mc -> mc.level.getThunderLevel(1.0F)) == 0.75F, "thunder override should force the thunder level to the configured intensity");

		context.runOnClient(mc -> AxolotlClient.config().weather.set(WeatherOverride.CLEAR));
		context.waitTicks(2);
		check(context.computeOnClient(mc -> mc.level.getRainLevel(1.0F)) == 0.0F, "clear override should force the rain level to zero");

		// The integrated server shares the JVM: its own level must keep its own weather.
		float serverRain = context.computeOnClient(mc -> mc.getSingleplayerServer().overworld().getRainLevel(1.0F));
		check(serverRain != 0.75F, "the integrated server level must not be affected by the client weather override");
	}

	private void testTimeMultiplier(ClientGameTestContext context) {
		// Pin the starting state; the run directory keeps config between runs.
		context.runOnClient(mc -> {
			AxolotlClient.config().timeChangerEnabled.set(false);
			AxolotlClient.config().timeMultiplier.set(1.0F);
		});
		long vanillaDelta = clockDelta(context, 20);
		check(vanillaDelta >= 15 && vanillaDelta <= 30, "vanilla world time should advance about one tick per tick, got " + vanillaDelta);

		context.runOnClient(mc -> {
			AxolotlClient.config().timeMultiplier.set(5.0F);
			AxolotlClient.config().timeChangerEnabled.set(true);
		});
		context.waitTicks(2);
		long boostedDelta = clockDelta(context, 20);
		check(boostedDelta >= 70 && boostedDelta <= 160, "with a 5x time multiplier the world time should advance at about 5x speed, got " + boostedDelta + " (vanilla " + vanillaDelta + ")");
	}

	/**
	 * The visual world time is driven by what gets fed into the client clock manager
	 * ({@code ClientClockManager.tick}), so the delta of its last ticked game time is
	 * exactly what the time multiplier scales.
	 */
	private long clockDelta(ClientGameTestContext context, int ticks) {
		long before = clockTime(context);
		context.waitTicks(ticks);
		long after = clockTime(context);
		return after - before;
	}

	private long clockTime(ClientGameTestContext context) {
		return context.computeOnClient(mc -> {
			try {
				var manager = mc.level.clockManager();
				var field = manager.getClass().getDeclaredField("lastTickGameTime");
				field.setAccessible(true);
				return field.getLong(manager);
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException(e);
			}
		});
	}

	private void testDoubleTapSneak(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			var hud = toggleSprintHud();
			hud.setEnabled(true);
			hud.toggleSneak.set(true);
			hud.getSneakToggled().set(false);
			// Bind sneak to J so leftover config in the run directory cannot skew the test.
			mc.options.keyShift.setKey(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_J));
			KeyMapping.resetMapping();
		});

		// Two quick presses within the 250 ms window toggle sneaking on.
		context.getInput().pressKey(InputConstants.KEY_J);
		context.waitTicks(2);
		context.getInput().pressKey(InputConstants.KEY_J);
		context.waitTicks(2);
		check(context.computeOnClient(mc -> toggleSprintHud().getSneakToggled().get()), "a quick double-tap of the sneak key should toggle sneaking on");

		// Another quick double-tap toggles it back off.
		context.getInput().pressKey(InputConstants.KEY_J);
		context.waitTicks(2);
		context.getInput().pressKey(InputConstants.KEY_J);
		context.waitTicks(2);
		check(!context.computeOnClient(mc -> toggleSprintHud().getSneakToggled().get()), "a second quick double-tap should toggle sneaking off");

		// Presses further apart than the window must not toggle.
		context.getInput().pressKey(InputConstants.KEY_J);
		context.waitTicks(10);
		context.getInput().pressKey(InputConstants.KEY_J);
		context.waitTicks(2);
		check(!context.computeOnClient(mc -> toggleSprintHud().getSneakToggled().get()), "presses outside the double-tap window must not toggle sneaking");

		context.runOnClient(mc -> {
			mc.options.keyShift.setKey(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_LSHIFT));
			KeyMapping.resetMapping();
		});
	}

	private void testFlyBoost(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			var hud = toggleSprintHud();
			hud.setEnabled(true);
			hud.flyBoost.set(true);
			hud.flyBoostMultiplier.set(2.0F);
			mc.player.getAbilities().flying = true;
		});

		float base = flySpeed(context);
		float abilitySpeed = context.computeOnClient(mc -> mc.player.getAbilities().getFlyingSpeed());
		check(Math.abs(base - abilitySpeed) < 1.0E-4F, "without the sprint key held, flying speed should stay at the abilities value");

		context.runOnClient(mc -> mc.options.keySprint.setDown(true));
		float boosted = flySpeed(context);
		check(Math.abs(boosted - base * 2.0F) < 1.0E-4F, "holding the sprint key while flying should apply the fly boost multiplier");

		context.runOnClient(mc -> {
			mc.options.keySprint.setDown(false);
			toggleSprintHud().flyBoostMultiplier.set(1.0F);
		});
		context.runOnClient(mc -> mc.options.keySprint.setDown(true));
		float unboosted = flySpeed(context);
		check(Math.abs(unboosted - base) < 1.0E-4F, "a fly boost multiplier of 1.0 must not change the flying speed");

		context.runOnClient(mc -> {
			mc.options.keySprint.setDown(false);
			toggleSprintHud().flyBoostMultiplier.set(2.0F);
			mc.player.getAbilities().flying = false;
		});
		float grounded = flySpeed(context);
		check(Math.abs(grounded - base) > 1.0E-4F, "sanity: the non-flying speed should differ from the flying speed");

		// fly_boost_hold off: the boost applies the whole time you fly, sprint key or not.
		context.runOnClient(mc -> {
			mc.options.keySprint.setDown(false);
			mc.player.getAbilities().flying = true;
			toggleSprintHud().flyBoostHold.set(false);
		});
		float unheld = flySpeed(context);
		check(Math.abs(unheld - base * 2.0F) < 1.0E-4F, "with fly_boost_hold off the boost must apply without the sprint key");
		context.runOnClient(mc -> toggleSprintHud().flyBoostHold.set(true));
		float heldAgain = flySpeed(context);
		check(Math.abs(heldAgain - base) < 1.0E-4F, "restoring fly_boost_hold must re-require the sprint key");
		context.runOnClient(mc -> mc.player.getAbilities().flying = false);
	}

	private float flySpeed(ClientGameTestContext context) {
		return context.computeOnClient(mc -> {
			try {
				var method = Player.class.getDeclaredMethod("getFlyingSpeed");
				method.setAccessible(true);
				return (Float) method.invoke(mc.player);
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException(e);
			}
		});
	}

	private void testNametagOptions(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			AxolotlClient.config().nametagScale.set(1.5D);
			AxolotlClient.config().nametagBackground.set(AxolotlClientConfigCommon.NametagBackgroundMode.OPAQUE);
			AxolotlClient.config().showOwnNametag.set(true);
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		});
		// Renders frames with an own nametag at 1.5x scale and an opaque background;
		// the modified submitNameTag path must not blow up.
		context.waitTicks(10);
		context.takeScreenshot("environment-nametag-scale");
	}

	private static ToggleSprintHud toggleSprintHud() {
		return (ToggleSprintHud) HudManager.getInstance().get(ToggleSprintHud.ID);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
