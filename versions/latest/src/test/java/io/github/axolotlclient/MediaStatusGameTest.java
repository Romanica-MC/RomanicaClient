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

import com.google.gson.JsonParser;
import io.github.axolotlclient.imperium.media.MediaConfig;
import io.github.axolotlclient.imperium.media.MediaStatus;
import io.github.axolotlclient.imperium.media.MediaStatusRegistry;
import io.github.axolotlclient.imperium.media.SpotifyLocalPoller;
import io.github.axolotlclient.imperium.media.SpotifyLocalStatus;
import io.github.axolotlclient.modules.hud.HudManager;
import io.github.axolotlclient.modules.hud.gui.component.HudEntry;
import io.github.axolotlclient.modules.hud.gui.hud.simple.NowPlayingHud;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Now-playing media shell tests (W5-B), headless in the gametest harness. Three families:
 *
 * <ul>
 *   <li><strong>{@link MediaStatus} math:</strong> progress interpolation between polls,
 *       clamping, fraction, and the "track — artist" title line.</li>
 *   <li><strong>Registry + parsing:</strong> the HUD's data source hides on unplayable or
 *       cleared snapshots; the Spotify local status document parses in both known shapes and
 *       returns null (never throws) for junk.</li>
 *   <li><strong>HUD wiring in a world:</strong> seeding the registry makes the Now Playing HUD
 *       draw, clearing it hides the entry again — the exact contract a future provider relies
 *       on. The local HTTP endpoint itself is NOT exercised here (Spotify is not installed on
 *       the build machine); see docs/media-and-emotes.md for what that means.</li>
 * </ul>
 */
public class MediaStatusGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this suite is render-heavy, see ImperiumGametestSubsets
		GametestBookkeeping.executed(getClass().getSimpleName());
		statusMath();
		registryAndParsing();
		hudWiring(context);
	}

	private void statusMath() {
		MediaStatus playing = new MediaStatus("Sandstorm", "Darude", true, 10_000, 60_000, 1_000);
		check(playing.progressMs(3_000) == 12_000, "playing position advances with wall clock");
		check(playing.progressMs(500) == 10_000, "clock before fetch time does not rewind position");
		check(playing.progressMs(120_000) == 60_000, "position clamps to track length");
		check(Math.abs(playing.fraction(21_000) - 0.5f) < 1e-3, "fraction is midpoint at half length");
		check(playing.titleLine().equals("Sandstorm — Darude"), "title line joins track and artist");

		MediaStatus paused = new MediaStatus("Sandstorm", "Darude", false, 10_000, 60_000, 1_000);
		check(paused.progressMs(120_000) == 10_000, "paused position freezes");

		MediaStatus noArtist = new MediaStatus("Only Track", "", true, 0, 0, 0);
		check(noArtist.titleLine().equals("Only Track"), "missing artist falls back to track alone");
		check(!noArtist.playable(), "zero length is not playable");
		check(noArtist.fraction(0) == 0, "zero length gives fraction 0");

		MediaStatus negative = new MediaStatus("T", "A", true, -5, -5, 0);
		check(negative.positionMs() == 0 && negative.lengthMs() == 0, "negative positions clamp to zero");
	}

	private void registryAndParsing() {
		MediaStatusRegistry.clear();
		check(MediaStatusRegistry.current() == null, "registry starts hidden");

		MediaStatusRegistry.update(new MediaStatus("", "Artist", true, 0, 60_000, 0));
		check(MediaStatusRegistry.current() == null, "unplayable snapshot hides the HUD");

		MediaStatus good = new MediaStatus("Track", "Artist", true, 1_000, 60_000, 2_000);
		MediaStatusRegistry.update(good);
		check(MediaStatusRegistry.current().equals(good), "playable snapshot publishes");

		MediaStatusRegistry.clear();
		check(MediaStatusRegistry.current() == null, "clear hides the HUD");

		// classic local-API document (track_resource / artist_resource / seconds)
		String classic = """
			{"playing":true,"playing_position":42.5,
			 "track":{"track_resource":{"name":"Sandstorm"},"artist_resource":{"name":"Darude"},"length":213}}""";
		MediaStatus parsed = SpotifyLocalStatus.parse(JsonParser.parseString(classic), 5_000);
		check(parsed != null && parsed.playing() && parsed.track().equals("Sandstorm")
			&& parsed.artist().equals("Darude") && parsed.positionMs() == 42_500 && parsed.lengthMs() == 213_000,
			"classic spotify local document parses, got " + parsed);

		// paused variant
		MediaStatus paused = SpotifyLocalStatus.parse(JsonParser.parseString(
			"{\"playing\":false,\"playing_position\":1.0,\"track\":{\"track_resource\":{\"name\":\"X\"},\"length\":10}}"), 0);
		check(paused != null && !paused.playing(), "paused document parses");

		// *_ms / artists-array variant
		MediaStatus msVariant = SpotifyLocalStatus.parse(JsonParser.parseString("""
			{"playing":true,"position_ms":15000,"duration_ms":90000,
			 "track":{"name":"Flat","artists":[{"name":"Array Artist"}]}}"""), 0);
		check(msVariant != null && msVariant.track().equals("Flat") && msVariant.artist().equals("Array Artist")
			&& msVariant.positionMs() == 15_000 && msVariant.lengthMs() == 90_000,
			"*_ms + artists[] variant parses, got " + msVariant);

		// junk never throws and never surfaces: string document, empty object, nameless track
		check(SpotifyLocalStatus.parse(JsonParser.parseString("\"just a string\""), 0) == null, "string document is null");
		check(SpotifyLocalStatus.parse(JsonParser.parseString("{}"), 0) == null, "empty document is null");
		check(SpotifyLocalStatus.parse(JsonParser.parseString(
			"{\"playing\":true,\"track\":{\"artist_resource\":{\"name\":\"Nope\"}}}"), 0) == null, "track without a name is null");
		check(SpotifyLocalPoller.parse("<html>500</html>") == null, "html junk body is null");
		check(SpotifyLocalPoller.parse(classic) != null, "poller body parser delegates to the pure parser");
	}

	private void hudWiring(ClientGameTestContext context) {
		// keep the (default-off) poller out of the test's way; the registry is the seam under test
		context.runOnClient(mc -> MediaConfig.media.set(false));
		HudEntry hud = nowPlayingHud();
		boolean wasEnabled = context.computeOnClient(mc -> hud.isEnabled());
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();

			context.runOnClient(mc -> hud.setEnabled(true));
			MediaStatusRegistry.clear();
			context.waitTicks(5);
			context.takeScreenshot("nowplaying-hud-hidden");
			check(context.computeOnClient(mc -> MediaStatusRegistry.current() == null), "HUD idle before seeding");

			// a provider publishing a snapshot is all it takes to light the HUD up
			context.runOnClient(mc -> MediaStatusRegistry.update(MediaStatus.now("Sandstorm", "Darude", true, 30_000, 213_000)));
			context.waitTicks(5);
			check(context.computeOnClient(mc -> MediaStatusRegistry.current() != null), "seeded status is live");
			context.takeScreenshot("nowplaying-hud-seeded");

			// clearing hides it again — the provider-error contract
			context.runOnClient(mc -> MediaStatusRegistry.clear());
			context.waitTicks(5);
			check(context.computeOnClient(mc -> MediaStatusRegistry.current() == null), "cleared status hides the HUD");
			context.takeScreenshot("nowplaying-hud-cleared");
		} finally {
			// leave the entry as this run found it, so later gametests see the default layout
			context.runOnClient(mc -> hud.setEnabled(wasEnabled));
		}
	}

	private static HudEntry nowPlayingHud() {
		return HudManager.getInstance().getEntries().stream()
			.filter(e -> e.getId().toString().endsWith("nowplayinghud"))
			.findFirst().orElseThrow(() -> new AssertionError("Now Playing HUD not registered"));
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
