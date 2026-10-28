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

import java.nio.charset.StandardCharsets;

import io.github.axolotlclient.imperium.BoostRegistry;
import io.github.axolotlclient.imperium.ImperiumChannels;
import io.github.axolotlclient.imperium.LegionStateRegistry;
import io.github.axolotlclient.imperium.ServerCooldownsRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Pins the three data push channels (imperiumclient:legion / boosts / cooldowns) against the
 * exact JSON the plugin's senders emit: decode through the shared RAW_UTF8 codec, registry
 * mapping (full/delta/none, replace semantics, lazy expiry) and lenient malformed handling.
 */
public class ImperiumChannelsGameTest implements FabricClientGameTest {

	private static final byte[] LEGION_FULL =
		("{\"type\":\"full\",\"name\":\"Spartans\",\"onlineMembers\":3,\"totalMembers\":12,"
			+ "\"power\":4200,\"level\":5}").getBytes(StandardCharsets.UTF_8);
	private static final byte[] LEGION_DELTA =
		"{\"type\":\"delta\",\"onlineMembers\":4,\"power\":4250}".getBytes(StandardCharsets.UTF_8);
	private static final byte[] LEGION_NONE = "{\"type\":\"none\"}".getBytes(StandardCharsets.UTF_8);

	@Override
	public void runTest(ClientGameTestContext context) {
		GametestBookkeeping.executed(getClass().getSimpleName());
		codecDecodesBareUtf8PerChannel();
		legionFullDeltaNone();
		boostsReplaceSemantics();
		cooldownsExpiryAnchors();
		malformedPacketsAreDropped();
	}

	private void codecDecodesBareUtf8PerChannel() {
		for (String channel : new String[]{"legion", "boosts", "cooldowns"}) {
			ByteBuf buf = Unpooled.wrappedBuffer(LEGION_NONE);
			ImperiumChannels.ChannelPayload payload =
				ImperiumChannels.ChannelPayload.codec(channel).decode(buf);
			check(buf.readableBytes() == 0, channel + ": decode must consume the whole buffer");
			check(payload.type().id().toString().equals("imperiumclient:" + channel),
				channel + ": payload type must carry its channel id");
		}
	}

	private void legionFullDeltaNone() {
		try {
			ImperiumChannels.applyLegion(new String(LEGION_FULL, StandardCharsets.UTF_8));
			var state = LegionStateRegistry.get();
			check(state != null, "full packet must set the legion state");
			check(state.name().equals("Spartans"), "legion name must round-trip");
			check(state.onlineMembers() == 3 && state.totalMembers() == 12, "member counts must round-trip");
			check(state.power() == 4200L && state.level() == 5, "power and level must round-trip");

			ImperiumChannels.applyLegion(new String(LEGION_DELTA, StandardCharsets.UTF_8));
			state = LegionStateRegistry.get();
			check(state.onlineMembers() == 4 && state.power() == 4250L, "delta must patch only its fields");
			check(state.totalMembers() == 12, "delta must keep absent fields");

			ImperiumChannels.applyLegion(new String(LEGION_NONE, StandardCharsets.UTF_8));
			check(LegionStateRegistry.get() == null, "none must clear the state");
		} finally {
			LegionStateRegistry.clear();
		}
	}

	private void boostsReplaceSemantics() {
		try {
			long endsAt = System.currentTimeMillis() + 60_000;
			ImperiumChannels.applyBoosts("{\"boosts\":[{\"id\":\"x2_sell\",\"name\":\"Double Sell\","
				+ "\"endsAtEpochMs\":" + endsAt + "},{\"id\":\"expired\",\"name\":\"Gone\","
				+ "\"endsAtEpochMs\":" + (System.currentTimeMillis() - 1000) + "}]}");
			check(BoostRegistry.active().size() == 1, "past-dated boosts must be dropped");
			check(BoostRegistry.active().get(0).name().equals("Double Sell"), "boost name must round-trip");

			ImperiumChannels.applyBoosts("{\"boosts\":[]}");
			check(BoostRegistry.active().isEmpty(), "an empty snapshot must clear the boosts");
		} finally {
			BoostRegistry.clear();
		}
	}

	private void cooldownsExpiryAnchors() {
		try {
			long now = System.currentTimeMillis();
			ImperiumChannels.applyCooldowns("{\"cooldowns\":[{\"id\":\"home\",\"name\":\"Home\","
				+ "\"totalMs\":60000,\"remainingMs\":30000}]}");
			var active = ServerCooldownsRegistry.active(now + 100);
			check(active.size() == 1, "the cooldown must be active");
			var cd = active.get(0);
			check(cd.remainingMs(now + 100) > 28_000 && cd.remainingMs(now + 100) < 31_000,
				"remaining must tick down from packet time");
			check(cd.remainingFraction(now + 100) > 0.4f && cd.remainingFraction(now + 100) < 0.6f,
				"the bar fraction must derive from totalMs");

			check(ServerCooldownsRegistry.active(now + 40_000).isEmpty(),
				"the cooldown must expire lazily once past its deadline");
		} finally {
			ServerCooldownsRegistry.clear();
		}
	}

	private void malformedPacketsAreDropped() {
		try {
			ImperiumChannels.applyLegion("not json");
			ImperiumChannels.applyBoosts("{\"boosts\":\"nope\"}");
			ImperiumChannels.applyCooldowns("{oops");
			check(LegionStateRegistry.get() == null, "malformed legion must not set state");
			check(BoostRegistry.active().isEmpty(), "malformed boosts must not set anything");
			check(ServerCooldownsRegistry.active(System.currentTimeMillis()).isEmpty(),
				"malformed cooldowns must not set anything");
		} finally {
			LegionStateRegistry.clear();
			BoostRegistry.clear();
			ServerCooldownsRegistry.clear();
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
