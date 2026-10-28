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

import io.github.axolotlclient.imperium.ImperiumIntegration;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Wire-contract test for the ImperiumMC handshake, run headless in the client gametest
 * harness (no world needed — codec and session logic only). The plugin speaks Bukkit
 * plugin messaging: BARE UTF-8 JSON, no length prefix, no NBT framing (docs/dev-notes.md
 * "Server protocol"). We feed the codecs the exact byte shapes the plugin's
 * ImperiumClientIntegrationService produces/consumes, pinning both sides of the wire.
 *
 * The regression this guards: ByteBufCodecs.fromCodec(Codec.STRING) reads NBT on decode,
 * failed on bare bytes and DISCONNECTED the player. RAW_UTF8 must consume the whole
 * remaining buffer as UTF-8 and encode without framing.
 */
public class ImperiumProtocolGameTest implements FabricClientGameTest {

	// The plugin's exact features payload (ImperiumClientProtocol.buildFeaturesJson output,
	// pinned byte-for-byte in the plugin's ImperiumClientProtocolTest).
	private static final byte[] PLUGIN_FEATURES =
		"{\"version\":1,\"allow\":[\"auto_fish\"],\"deny\":[\"freelook\"]}"
			.getBytes(StandardCharsets.UTF_8);

	@Override
	public void runTest(ClientGameTestContext context) {
		GametestBookkeeping.executed(getClass().getSimpleName());
		featuresDecodeConsumesBareUtf8WithoutNbt();
		featuresEncodeIsRawUtf8RoundTrip();
		helloEncodeCarriesNoNbtPrefix();
		sessionAppliesPluginGrantsAndResets();
		malformedFeaturesKeepEverythingLocked();
	}

	private void featuresDecodeConsumesBareUtf8WithoutNbt() {
		ByteBuf buf = Unpooled.wrappedBuffer(PLUGIN_FEATURES);
		ImperiumIntegration.FeaturesPayload payload = ImperiumIntegration.FeaturesPayload.CODEC.decode(buf);
		check(buf.readableBytes() == 0, "decode must consume the whole buffer (no length prefix on the wire)");
		check(payload.json().contains("\"auto_fish\""), "granted module id must survive the decode");
	}

	private void featuresEncodeIsRawUtf8RoundTrip() {
		ImperiumIntegration.FeaturesPayload payload = new ImperiumIntegration.FeaturesPayload(
			new String(PLUGIN_FEATURES, StandardCharsets.UTF_8));
		ByteBuf buf = Unpooled.buffer();
		ImperiumIntegration.FeaturesPayload.CODEC.encode(buf, payload);
		check(buf.readableBytes() == PLUGIN_FEATURES.length, "encode writes raw bytes, no framing");
		byte[] out = new byte[buf.readableBytes()];
		buf.readBytes(out);
		check(new String(out, StandardCharsets.UTF_8).equals(new String(PLUGIN_FEATURES, StandardCharsets.UTF_8)),
			"encode/decode round trip must be byte-identical");
	}

	private void helloEncodeCarriesNoNbtPrefix() {
		ByteBuf buf = Unpooled.buffer();
		ImperiumIntegration.HelloPayload.CODEC.encode(buf,
			new ImperiumIntegration.HelloPayload("{\"version\":\"0.1.0\"}"));
		// NBT-framed strings open with a byte-length/type marker; raw UTF-8 starts with '{' (0x7B).
		check(buf.getByte(0) == '{', "first byte must be the JSON open brace, not an NBT marker");
	}

	private void sessionAppliesPluginGrantsAndResets() {
		var session = io.github.axolotlclient.imperium.ImperiumClientSession.getInstance();
		try {
			session.applyFeatures(new String(PLUGIN_FEATURES, StandardCharsets.UTF_8));
			check(session.isAllowed("auto_fish"), "granted module must be allowed");
			check(session.isDenied("freelook"), "denied module must be denied");
			check(!session.isAllowed("auto_click"), "ungranted gated module stays locked");

			// A second packet REPLACES the grant set (server is authoritative per send).
			session.applyFeatures("{\"version\":1,\"allow\":[],\"deny\":[]}");
			check(!session.isAllowed("auto_fish"), "empty re-grant must revoke everything");

			// Disconnect resets (protocol rule 2).
			session.reset();
			check(!session.serverResponded(), "reset clears the server-responded marker");
			check(!session.isDenied("freelook"), "reset clears denies");
		} finally {
			session.reset();
		}
	}

	private void malformedFeaturesKeepEverythingLocked() {
		var session = io.github.axolotlclient.imperium.ImperiumClientSession.getInstance();
		try {
			session.applyFeatures("not json at all");
			check(!session.serverResponded(), "garbage must not count as a server response");
			check(!session.isAllowed("auto_fish"), "garbage keeps everything locked");
		} finally {
			session.reset();
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
