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

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Set;

import io.github.axolotlclient.imperium.enforcement.ChannelGuard;
import io.github.axolotlclient.imperium.enforcement.EnforcementConfig;
import io.github.axolotlclient.imperium.enforcement.EnforcementService;
import io.github.axolotlclient.imperium.enforcement.PayloadRules;
import io.github.axolotlclient.imperium.enforcement.mixin.ClientConnectionAccess;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.resources.Identifier;

/**
 * Scope + channel-guard + payload-rules tests for the local rules enforcement layer (W4-C),
 * plus live mixin-liveness evidence from the integrated-server world the harness runs.
 *
 * <p>What can and cannot be tested here, honestly: the harness connects the client to its own
 * integrated server (a memory connection), which by the scope rule can NEVER be in enforcement
 * scope. So the live path proves (a) the send hook is applied and firing (hookPulse grows while
 * connected), and (b) an out-of-scope connection is untouched even with the allowlist pointed
 * at it. The in-scope decisions are exercised through the same pure functions the hook calls
 * (address matching, channel allowlisting, register-payload filtering via the REAL Fabric
 * register class, sign/book rules). Live interception against a real romanica.net connection
 * is not gametest-drivable without DNS; the decision logic is.</p>
 */
public class LocalEnforcementGameTest implements FabricClientGameTest {

	private static final List<String> DEFAULT = List.of("romanica.net", "play.romanica.net");

	@Override
	public void runTest(ClientGameTestContext context) {
		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this suite is render-heavy, see ImperiumGametestSubsets
		GametestBookkeeping.executed(getClass().getSimpleName());
		scopeAddressMatching();
		channelAllowlist();
		registerListFiltering();
		fabricRegisterPayloadFiltering();
		signRules();
		bookRules();

		// Live evidence from the integrated-server world: the hook is applied, firing, and inert.
		try (var world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			long pulseBefore = EnforcementService.instance().hookPulse();
			context.waitTicks(40);
			long pulseAfter = EnforcementService.instance().hookPulse();
			check(pulseAfter > pulseBefore,
				"Connection.send hook must be applied and firing (pulse " + pulseBefore + " -> " + pulseAfter + ")");

			Connection connection = context.computeOnClient(this::liveConnection);
			check(connection != null, "client must be connected to the integrated server");
			// Even with the allowlist pointed at the local address, a memory connection is
			// never in scope: singleplayer/LAN enforcement is structurally impossible, and
			// there is no master toggle left to flip.
			String allowlist = EnforcementConfig.serverAllowlist.get();
			try {
				EnforcementConfig.serverAllowlist.set("localhost");
				check(!EnforcementService.instance().isConnectionInScope(connection),
					"memory connection (singleplayer) must never be in enforcement scope");
			} finally {
				EnforcementConfig.serverAllowlist.set(allowlist);
			}

			long actions = EnforcementService.instance().enforcedActions();
			check(actions == 0, "enforcement must never act on an out-of-scope connection (actions=" + actions + ")");

			staleEnabledKeyCannotDisable(context);
		}
	}

	/**
	 * A player must not be able to disable the layer by editing the config file (issue #15 item 11):
	 * the option is gone from the category, and a stale {@code enabled: false} key in the file is
	 * ignored by the loader (it only reads keys that match a registered option), leaving the hook
	 * firing and the scope rule intact.
	 */
	private void staleEnabledKeyCannotDisable(ClientGameTestContext context) {
		check(EnforcementConfig.category.getOptions().stream().noneMatch(o -> "enabled".equals(o.getName())),
			"EnforcementConfig must not expose an 'enabled' option");
		try {
			EnforcementConfig.class.getField("enabled");
			throw new AssertionError("EnforcementConfig must not have a public 'enabled' field");
		} catch (NoSuchFieldException expected) {
			// the toggle is gone entirely, not merely hidden
		}
		check(EnforcementConfig.serverAllowlist != null, "the allowlist option stays for deployment scoping");

		try {
			java.nio.file.Path file = io.github.axolotlclient.AxolotlClientCommon.getInstance().getMainConfigFile();
			com.google.gson.JsonObject root;
			if (java.nio.file.Files.exists(file)) {
				root = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
			} else {
				java.nio.file.Files.createDirectories(file.getParent());
				root = new com.google.gson.JsonObject();
			}
			// The file layout is {"version": ..., "config": {<categories>}}; the stale key has to
			// land where the old option used to be read from.
			if (!root.has("version")) {
				root.addProperty("version", "11.0.0");
			}
			com.google.gson.JsonObject config = root.has("config")
				? root.getAsJsonObject("config") : new com.google.gson.JsonObject();
			root.add("config", config);
			com.google.gson.JsonObject enforcement = config.has("enforcement")
				? config.getAsJsonObject("enforcement") : new com.google.gson.JsonObject();
			enforcement.addProperty("enabled", false);
			enforcement.addProperty("server_allowlist", EnforcementConfig.DEFAULT_ALLOWLIST);
			config.add("enforcement", enforcement);
			java.nio.file.Files.writeString(file, root.toString());

			// The real reload path a config screen or /reload equivalent takes.
			io.github.axolotlclient.AxolotlClientCommon.getInstance().getConfigManager().load();

			check(EnforcementService.instance().wouldBeInScope("play.romanica.net", null),
				"the stale enabled:false key must not disable scope after a config reload");
			long pulseBefore = EnforcementService.instance().hookPulse();
			context.waitTicks(20);
			long pulseAfter = EnforcementService.instance().hookPulse();
			check(pulseAfter > pulseBefore,
				"the send hook must keep firing after a config reload with enabled:false in the file (pulse "
					+ pulseBefore + " -> " + pulseAfter + ")");
		} catch (java.io.IOException failure) {
			throw new AssertionError("writing the stale enabled key into the config file failed", failure);
		} finally {
			// Leave the run directory without the stale key so later runs start clean.
			try {
				java.nio.file.Path file = io.github.axolotlclient.AxolotlClientCommon.getInstance().getMainConfigFile();
				if (java.nio.file.Files.exists(file)) {
					com.google.gson.JsonObject root = com.google.gson.JsonParser
						.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
					if (root.has("config") && root.getAsJsonObject("config").has("enforcement")) {
						root.getAsJsonObject("config").getAsJsonObject("enforcement").remove("enabled");
						java.nio.file.Files.writeString(file, root.toString());
					}
					io.github.axolotlclient.AxolotlClientCommon.getInstance().getConfigManager().load();
				}
			} catch (java.io.IOException ignored) {
				// Cleanup only; a failure here must not mask the real assertions.
			}
		}
	}

	private void scopeAddressMatching() {
		// Exact and dot-boundary suffix matches.
		check(EnforcementService.isAddressAllowed("romanica.net", DEFAULT), "exact apex match");
		check(EnforcementService.isAddressAllowed("play.romanica.net", DEFAULT), "subdomain match");
		check(EnforcementService.isAddressAllowed("eu.play.romanica.net", DEFAULT), "deep subdomain match");
		// Port / case / trailing-dot normalization.
		check(EnforcementService.isAddressAllowed("romanica.net:25565", DEFAULT), "port stripped");
		check(EnforcementService.isAddressAllowed("ROMANICA.NET", DEFAULT), "case-insensitive");
		check(EnforcementService.isAddressAllowed("Romanica.net.", DEFAULT), "trailing dot stripped");
		// Non-matches: the dot boundary is the security property.
		check(!EnforcementService.isAddressAllowed("evilromanica.net", DEFAULT), "suffix-in-label must NOT match");
		check(!EnforcementService.isAddressAllowed("notromanica.net", DEFAULT), "prefix-in-label must NOT match");
		check(!EnforcementService.isAddressAllowed("romanica.net.evil.com", DEFAULT), "domain-contains must NOT match");
		check(!EnforcementService.isAddressAllowed("example.org", DEFAULT), "unrelated host must NOT match");
		check(!EnforcementService.isAddressAllowed("[::1]:25565", DEFAULT), "IPv6 loopback must NOT match");
		check(!EnforcementService.isAddressAllowed(null, DEFAULT), "null host must NOT match");
		check(!EnforcementService.isAddressAllowed("romanica.net", List.of()), "empty allowlist matches nothing");
		// A custom allowlist governs instead of the default.
		check(EnforcementService.isAddressAllowed("example.org", List.of("example.org")), "custom allowlist entry works");
		check(!EnforcementService.isAddressAllowed("romanica.net", List.of("example.org")), "custom allowlist excludes others");

		// The service seam with the live (pinned default) config.
		String allowlist = EnforcementConfig.serverAllowlist.get();
		try {
			EnforcementConfig.serverAllowlist.set(EnforcementConfig.DEFAULT_ALLOWLIST);
			check(EnforcementService.instance().wouldBeInScope("play.romanica.net", null), "socket host alone scopes in");
			check(EnforcementService.instance().wouldBeInScope(null, "romanica.net"), "server-entry ip alone scopes in");
			check(!EnforcementService.instance().wouldBeInScope("example.org", "example.org"), "both sources out of scope stays out");
		} finally {
			EnforcementConfig.serverAllowlist.set(allowlist);
		}
	}

	private void channelAllowlist() {
		// Vanilla + standard client namespaces + our own channels pass.
		check(ChannelGuard.isChannelAllowed("minecraft:brand"), "minecraft:brand allowed");
		check(ChannelGuard.isChannelAllowed("minecraft:register"), "minecraft:register allowed");
		check(ChannelGuard.isChannelAllowed("fabric:api"), "fabric:* allowed");
		check(ChannelGuard.isChannelAllowed("c:register"), "c:register (fabric's modern register channel) allowed");
		check(ChannelGuard.isChannelAllowed("lunar:style"), "lunar:* allowed");
		check(ChannelGuard.isChannelAllowed("lunarclient:hello"), "lunarclient:* allowed");
		check(ChannelGuard.isChannelAllowed("imperiumclient:hello"), "imperiumclient:* allowed");
		check(ChannelGuard.isChannelAllowed("worldedit:cui"), "worldedit:cui allowed");
		// Third-party mod channels are suppressed on our server.
		check(!ChannelGuard.isChannelAllowed("sodium:options"), "sodium channel suppressed");
		check(!ChannelGuard.isChannelAllowed("meteor:main"), "meteor channel suppressed");
		check(!ChannelGuard.isChannelAllowed("hypixel:auxiliary"), "hypixel channel suppressed");
		check(!ChannelGuard.isChannelAllowed("wdl|init"), "world-downloader channel suppressed");
		check(!ChannelGuard.isChannelAllowed("worldedit:other"), "only worldedit:cui is allowed, not worldedit:*");
		check(!ChannelGuard.isChannelAllowed(""), "empty channel refused");
	}

	private void registerListFiltering() {
		List<String> filtered = ChannelGuard.filterChannelNames(List.of(
			"minecraft:brand", "imperiumclient:hello", "worldedit:cui", "sodium:options", "meteor:main"));
		check(filtered.equals(List.of("minecraft:brand", "imperiumclient:hello", "worldedit:cui")),
			"register list keeps exactly the allowlisted channels, order preserved: " + filtered);
		check(ChannelGuard.filterChannelNames(List.of("meteor:main")).isEmpty(), "all-third-party list filters to empty");
	}

	/** Exercises the REAL Fabric register payload through the reflective filter (fabric-api is present here). */
	private void fabricRegisterPayloadFiltering() {
		Class<?> payloadClass;
		try {
			payloadClass = Class.forName("net.fabricmc.fabric.impl.networking.CommonRegisterPayload");
		} catch (ClassNotFoundException absent) {
			// Fabric networking not on the runtime classpath (should not happen in this mod's
			// environment); the pure list filter above already covers the decision logic.
			return;
		}
		try {
			Set<Identifier> channels = new java.util.LinkedHashSet<>(List.of(
				Identifier.fromNamespaceAndPath("imperiumclient", "features"),
				Identifier.fromNamespaceAndPath("worldedit", "cui"),
				Identifier.fromNamespaceAndPath("sodium", "options")));
			Constructor<?> constructor = payloadClass.getConstructor(int.class, String.class, Set.class);
			Object payload = constructor.newInstance(1, "play", channels);

			check(ChannelGuard.isFabricRegisterPayload(payload), "fabric register payload recognized");
			Object filtered = ChannelGuard.filterFabricRegisterPayload(payload);
			check(filtered != null && filtered != payload, "payload rebuilt with third-party channels removed");
			@SuppressWarnings("unchecked")
			Set<Identifier> kept = (Set<Identifier>) filtered.getClass().getMethod("channels").invoke(filtered);
			check(kept.size() == 2 && kept.contains(Identifier.fromNamespaceAndPath("imperiumclient", "features"))
				&& kept.contains(Identifier.fromNamespaceAndPath("worldedit", "cui")),
				"kept exactly the allowed channels: " + kept);

			// An all-allowed payload passes through untouched.
			Set<Identifier> allowed = new java.util.LinkedHashSet<>(List.of(
				Identifier.fromNamespaceAndPath("fabric", "networking")));
			Object clean = constructor.newInstance(1, "play", allowed);
			check(ChannelGuard.filterFabricRegisterPayload(clean) == clean, "all-allowed payload is not rebuilt");

			// An all-suppressed payload is dropped entirely (null).
			Set<Identifier> banned = new java.util.LinkedHashSet<>(List.of(
				Identifier.fromNamespaceAndPath("meteor", "main")));
			Object dirty = constructor.newInstance(1, "play", banned);
			check(ChannelGuard.filterFabricRegisterPayload(dirty) == null, "all-suppressed payload is dropped");
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError("fabric register payload filtering failed", failure);
		}
	}

	private void signRules() {
		// Line length: 50 legal (plugin MAX_SIGN_LINE_LENGTH), 51 refused.
		check(PayloadRules.signViolation(List.of("", "x".repeat(50), "")) == null, "50-char sign line legal");
		check("sign_line_oversize".equals(PayloadRules.signViolation(List.of("x".repeat(51)))),
			"51-char sign line refused (server cancels + VLs it)");
		// Packet-layer total payload ceiling (crash-exploit signature): lines must each stay
		// under the per-line limit so the total rule is what trips.
		check("sign_payload_oversize".equals(PayloadRules.signViolation(java.util.Collections.nCopies(11, "x".repeat(46)))),
			"506-char sign payload refused (11 lines of 46)");
		// Rate: 1 per 2 s, mirroring the server's SignChangeEvent cancel.
		check(PayloadRules.signWithinRate(0, System.currentTimeMillis()), "first sign of the session allowed");
		check(PayloadRules.signWithinRate(System.currentTimeMillis() - 2100, System.currentTimeMillis()), "edit after 2.1 s allowed");
		check(!PayloadRules.signWithinRate(System.currentTimeMillis() - 500, System.currentTimeMillis()), "edit after 0.5 s held");
	}

	private void bookRules() {
		check(PayloadRules.bookViolation(List.of("page"), "A title") == null, "normal book legal");
		check("book_title_oversize".equals(PayloadRules.bookViolation(List.of("page"), "t".repeat(17))), "17-char title refused");
		check(PayloadRules.bookViolation(List.of("page"), "t".repeat(16)) == null, "16-char title legal");
		check("book_pages_oversize".equals(PayloadRules.bookViolation(java.util.Collections.nCopies(101, "p"), null)),
			"101 pages refused");
		check(PayloadRules.bookViolation(java.util.Collections.nCopies(100, "p"), null) == null, "100 pages legal");
		check("book_page_oversize".equals(PayloadRules.bookViolation(List.of("x".repeat(8193)), null)), "8193-char page refused");
		check(PayloadRules.bookViolation(List.of("x".repeat(8192)), null) == null, "8192-char page legal");
		check("book_non_ascii".equals(PayloadRules.bookViolation(List.of("é".repeat(101)), null)),
			"101 non-ASCII chars per page refused (unicode dupe signature)");
		check(PayloadRules.bookViolation(List.of("é".repeat(100)), null) == null, "100 non-ASCII chars legal");
	}

	private Connection liveConnection(Minecraft client) {
		var listener = client.getConnection();
		return listener == null ? null : ((ClientConnectionAccess) listener).imperium$connection();
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
