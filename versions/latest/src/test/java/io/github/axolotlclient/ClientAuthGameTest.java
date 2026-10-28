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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonParser;
import io.github.axolotlclient.imperium.LegionStateRegistry;
import io.github.axolotlclient.imperium.online.ClientApi;
import io.github.axolotlclient.imperium.online.ClientApiAuth;
import io.github.axolotlclient.imperium.online.ImperiumOnline;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Pins the client half of the Mojang-session-check sign-in against in-memory fakes
 * (no real network can happen here — the real Microsoft flow is exercised only in
 * live play): the challenge -> joinServer -> verify state machine, offline-session
 * skip, every failure degrading to "unavailable, never a crash", the 401 refresh at
 * the fetch layer, single-flight auth, invalidate-supersedes-in-flight, and the
 * legion fallback's channel-priority rules.
 */
public class ClientAuthGameTest implements FabricClientGameTest {

	private static final UUID PROFILE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

	@Override
	public void runTest(ClientGameTestContext context) {
		GametestBookkeeping.executed(getClass().getSimpleName());
		happyPathChallengesJoinsVerifies();
		offlineSessionSkipsEntirely();
		failuresDegradeToOfflineNeverCrash();
		fetchRefreshesOnceOn401();
		singleFlightDeduplicatesConcurrentAuth();
		invalidateSupersedesInFlightFlow();
		legionFallbackRespectsChannelPriority();
	}

	// ── fakes ───────────────────────────────────────────────────────────────────────────────

	/** Runs everything on the caller thread: fully deterministic, no timing. */
	private static final Executor DIRECT = Runnable::run;

	/** Defers tasks until pumped; lets tests observe what was scheduled before it runs. */
	private static final class QueuedExecutor implements Executor {
		final List<Runnable> tasks = new ArrayList<>();

		@Override
		public void execute(Runnable command) {
			tasks.add(command);
		}

		void pump() {
			List<Runnable> pending = new ArrayList<>(tasks);
			tasks.clear();
			pending.forEach(Runnable::run);
		}
	}

	private static final class FakeSession implements ClientApiAuth.GameSession {
		volatile boolean online = true;
		String username = "TestPlayer";
		String accessToken = "msa-token-a";
		volatile String joinedServerId;
		/** Volatile: the mid-flight test spin-waits on this from another thread. */
		volatile int joinCalls;
		volatile boolean throwOnJoin;

		@Override
		public boolean getHasOnlineSession() {
			return online;
		}

		@Override
		public UUID getProfileId() {
			return PROFILE_ID;
		}

		@Override
		public String getUsername() {
			return username;
		}

		@Override
		public String getAccessToken() {
			return accessToken;
		}

		@Override
		public void joinServer(String serverId) {
			joinCalls++;
			if (throwOnJoin) {
				throw new RuntimeException("auth unavailable");
			}
			joinedServerId = serverId;
		}
	}

	/**
	 * Fake transport modelling the real backend's behavior: every challenge call
	 * answers the NEXT configured serverId (a fresh one per sign-in, never reused),
	 * and verify answers the token bound to the serverId it just received. GETs are
	 * keyed by bearer token; a response body of the form "HTTP &lt;status&gt;&lt;json&gt;"
	 * answers that status. Records every call so tests can assert the flow's shape.
	 */
	private static final class FakeTransport implements ClientApiAuth.HttpTransport {
		final List<String> calls = new ArrayList<>();
		final List<String> bearerTokensSeen = new ArrayList<>();
		String[] challengeServerIds = new String[0];
		int challengeCalls;
		final Map<String, String> tokenForServerId = new HashMap<>();
		final Map<String, Integer> postStatuses = new HashMap<>();
		final Map<String, String> rawPostBodies = new HashMap<>();
		final Map<String, String> getResponses = new HashMap<>();
		String networkErrorForUrl;
		CountDownLatch blockVerifyOn;

		static String url(String path) {
			return ClientApiAuth.API_BASE + path;
		}

		@Override
		public ClientApiAuth.HttpResult post(String url, String body, String bearerToken) {
			if (blockVerifyOn != null && url.endsWith("/api/client/auth/verify")) {
				await(blockVerifyOn);
			}
			calls.add("POST " + url);
			if (networkErrorForUrl != null && networkErrorForUrl.equals(url)) {
				return new ClientApiAuth.HttpResult.TransportError("ConnectException");
			}
			if (url.endsWith("/api/client/auth/challenge")) {
				Integer status = postStatuses.get("challenge");
				if (status != null) {
					return new ClientApiAuth.HttpResult.Response(status, rawPostBodies.get("challenge"));
				}
				String serverId = challengeServerIds[Math.min(challengeCalls, challengeServerIds.length - 1)];
				challengeCalls++;
				// The backend's v2 convention wraps payloads as {"data":{...}} (respond.ok) —
				// the mock serves the WRAPPED shape so the parse pin matches production.
				return new ClientApiAuth.HttpResult.Response(200,
					"{\"data\":{\"serverId\":\"" + serverId + "\",\"expiresIn\":300}}");
			}
			if (url.endsWith("/api/client/auth/verify")) {
				Integer status = postStatuses.get("verify");
				if (status != null) {
					return new ClientApiAuth.HttpResult.Response(status, null);
				}
				String serverId = JsonParser.parseString(body).getAsJsonObject().get("serverId").getAsString();
				String token = tokenForServerId.get(serverId);
				if (token == null) {
					return new ClientApiAuth.HttpResult.Response(401, "{\"error\":\"unknown serverId\"}");
				}
				return new ClientApiAuth.HttpResult.Response(200, "{\"data\":{\"token\":\"" + token + "\",\"expiresIn\":3600}}");
			}
			return new ClientApiAuth.HttpResult.Response(404, null);
		}

		@Override
		public ClientApiAuth.HttpResult get(String url, String bearerToken) {
			calls.add("GET " + url);
			bearerTokensSeen.add(bearerToken);
			if (networkErrorForUrl != null && networkErrorForUrl.equals(url)) {
				return new ClientApiAuth.HttpResult.TransportError("ConnectException");
			}
			String body = getResponses.get(bearerToken == null ? "" : bearerToken);
			if (body == null) {
				return new ClientApiAuth.HttpResult.Response(401, "{}");
			}
			if (body.startsWith("HTTP ")) {
				int status = Integer.parseInt(body.substring(5, 8));
				return new ClientApiAuth.HttpResult.Response(status, body.substring(8));
			}
			return new ClientApiAuth.HttpResult.Response(200, body);
		}

		private static void await(CountDownLatch latch) {
			try {
				if (!latch.await(5, TimeUnit.SECONDS)) {
					throw new AssertionError("verify latch never released");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("interrupted while blocked on the verify latch", e);
			}
		}
	}

	private static ClientApiAuth auth(FakeTransport transport, FakeSession session) {
		return new ClientApiAuth(transport, session, DIRECT);
	}

	// ── the state machine ───────────────────────────────────────────────────────────────────

	private void happyPathChallengesJoinsVerifies() {
		FakeTransport transport = new FakeTransport();
		FakeSession session = new FakeSession();
		transport.challengeServerIds = new String[]{"sid-123"};
		transport.tokenForServerId.put("sid-123", "jwt-1");
		ClientApiAuth auth = auth(transport, session);

		ClientApiAuth.State state = auth.authenticateAsync().join();

		check(state instanceof ClientApiAuth.State.Authenticated, "happy path must authenticate");
		check("jwt-1".equals(((ClientApiAuth.State.Authenticated) state).getToken()), "the verify token must be kept");
		check("jwt-1".equals(auth.token()), "token() must expose the in-memory token");
		check(session.joinCalls == 1, "joinServer must run exactly once");
		check("sid-123".equals(session.joinedServerId), "joinServer must receive the challenge's serverId");
		check(transport.calls.size() == 2, "exactly challenge + verify calls must happen");
		check(transport.calls.get(0).endsWith("/api/client/auth/challenge"), "challenge must be called first");
		check(transport.calls.get(1).endsWith("/api/client/auth/verify"), "verify must be called second");
		kotlin.Pair<UUID, String> minted = auth.authenticatedFor();
		check(minted != null && PROFILE_ID.equals(minted.getFirst()) && "msa-token-a".equals(minted.getSecond()),
			"the token must be bound to the session it was minted for");
	}

	private void offlineSessionSkipsEntirely() {
		FakeTransport transport = new FakeTransport();
		FakeSession session = new FakeSession();
		session.online = false;
		ClientApiAuth auth = auth(transport, session);

		ClientApiAuth.State state = auth.authenticateAsync().join();

		check(state instanceof ClientApiAuth.State.OfflineSession, "offline accounts must skip gracefully");
		check(transport.calls.isEmpty(), "offline skip must not touch the network");
		check(session.joinCalls == 0, "offline skip must not call joinServer");
		check(auth.token() == null, "offline skip must not mint a token");
	}

	private void failuresDegradeToOfflineNeverCrash() {
		// Network failure on the challenge.
		FakeTransport transport = new FakeTransport();
		transport.networkErrorForUrl = FakeTransport.url("/api/client/auth/challenge");
		ClientApiAuth.State state = auth(transport, new FakeSession()).authenticateAsync().join();
		check(state instanceof ClientApiAuth.State.Failed, "challenge network failure must fail, not throw");
		check("challenge-network".equals(((ClientApiAuth.State.Failed) state).getReason()), "reason must be a code");

		// HTTP failure (the routes do not exist yet pre-cutover -> 404).
		transport = new FakeTransport();
		transport.postStatuses.put("challenge", 404);
		state = auth(transport, new FakeSession()).authenticateAsync().join();
		check(state instanceof ClientApiAuth.State.Failed && "challenge-http-404".equals(((ClientApiAuth.State.Failed) state).getReason()),
			"challenge 404 must degrade to unavailable");

		// Malformed challenge body.
		transport = new FakeTransport();
		transport.postStatuses.put("challenge", 200);
		transport.rawPostBodies.put("challenge", "{\"oops\":true}");
		state = auth(transport, new FakeSession()).authenticateAsync().join();
		check(state instanceof ClientApiAuth.State.Failed && "challenge-shape".equals(((ClientApiAuth.State.Failed) state).getReason()),
			"garbage challenge body must degrade to unavailable");

		// The game's own joinServer failing (session servers unreachable).
		transport = new FakeTransport();
		transport.challengeServerIds = new String[]{"sid-x"};
		FakeSession session = new FakeSession();
		session.throwOnJoin = true;
		state = auth(transport, session).authenticateAsync().join();
		check(state instanceof ClientApiAuth.State.Failed && "join-server".equals(((ClientApiAuth.State.Failed) state).getReason()),
			"a joinServer exception must degrade to unavailable, never crash");

		// Verify refusing the sign-in (backend could not confirm hasJoinedServer).
		transport = new FakeTransport();
		transport.challengeServerIds = new String[]{"sid-x"};
		transport.postStatuses.put("verify", 401);
		state = auth(transport, new FakeSession()).authenticateAsync().join();
		check(state instanceof ClientApiAuth.State.Failed && "verify-http-401".equals(((ClientApiAuth.State.Failed) state).getReason()),
			"verify 401 must degrade to unavailable");
	}

	// ── the fetch layer ─────────────────────────────────────────────────────────────────────

	private void fetchRefreshesOnceOn401() {
		FakeTransport transport = new FakeTransport();
		FakeSession session = new FakeSession();
		// Challenge #1 answers sid-old -> jwt-old; that token then "expires" (GET 401).
		// The re-mint gets a FRESH challenge (challenge #2 answers sid-new -> jwt-new)
		// whose token reads the real backend legion shape — the fake models the
		// backend issuing a new serverId per sign-in, exactly like the real one.
		transport.challengeServerIds = new String[]{"sid-old", "sid-new"};
		transport.tokenForServerId.put("sid-old", "jwt-old");
		transport.tokenForServerId.put("sid-new", "jwt-new");
		transport.getResponses.put("jwt-old", "HTTP 401{}");
		transport.getResponses.put("jwt-new",
			("{\"legion\":{\"name\":\"spartans\",\"displayName\":\"Spartans\",\"level\":3,"
				+ "\"ownerUuid\":\"owner\",\"xp\":1200,\"maxMembers\":20},"
				+ "\"members\":[{\"uuid\":\"a\",\"username\":\"A\",\"role\":\"OWNER\"},"
				+ "{\"uuid\":\"b\",\"username\":\"B\",\"role\":\"MEMBER\"}],"
				+ "\"projects\":{\"completed\":[],\"contributions\":[]}}"));
		ClientApi api = new ClientApi(auth(transport, session), transport, DIRECT);

		ClientApi.FetchResult<ClientApi.LegionSnapshot> result = api.fetchLegion().join();

		check(result instanceof ClientApi.FetchResult.Ok, "401 + re-mint + retry must surface the refetch");
		ClientApi.LegionSnapshot legion = ((ClientApi.FetchResult.Ok<ClientApi.LegionSnapshot>) result).getValue();
		check("Spartans".equals(legion.getDisplayName()) && "spartans".equals(legion.getName()),
			"legion names must round-trip from the real backend shape");
		check(legion.getMemberCount() == 2, "memberCount must come from the members array");
		check(legion.getLevel() == 3 && legion.getXp() == 1200L && legion.getMaxMembers() == 20,
			"level/xp/maxMembers must round-trip");
		check(transport.bearerTokensSeen.size() == 2, "exactly two GETs must happen (stale, then refreshed)");
		check("jwt-old".equals(transport.bearerTokensSeen.get(0)) && "jwt-new".equals(transport.bearerTokensSeen.get(1)),
			"the retry must carry the re-minted token");

		// Offline session: fetch reports AuthUnavailable without any network.
		FakeSession offline = new FakeSession();
		offline.online = false;
		ClientApi offlineApi = new ClientApi(auth(transport, offline), transport, DIRECT);
		transport.bearerTokensSeen.clear();
		ClientApi.FetchResult<?> offlineResult = offlineApi.fetchBalances().join();
		check(offlineResult == ClientApi.FetchResult.AuthUnavailable.INSTANCE,
			"offline session fetch must degrade to AuthUnavailable");
		check(transport.bearerTokensSeen.isEmpty(), "offline session fetch must not send any GET");
	}

	private void singleFlightDeduplicatesConcurrentAuth() {
		FakeTransport transport = new FakeTransport();
		transport.challengeServerIds = new String[]{"sid-1"};
		transport.tokenForServerId.put("sid-1", "jwt-1");
		QueuedExecutor executor = new QueuedExecutor();
		ClientApiAuth auth = new ClientApiAuth(transport, new FakeSession(), executor);

		CompletableFuture<ClientApiAuth.State> first = auth.authenticateAsync();
		CompletableFuture<ClientApiAuth.State> second = auth.authenticateAsync();

		check(executor.tasks.size() == 1, "concurrent auth attempts must share one flow");
		executor.pump();
		check(first.join() instanceof ClientApiAuth.State.Authenticated, "the shared flow must authenticate");
		check(second.join() instanceof ClientApiAuth.State.Authenticated, "the joining caller must see the same outcome");
	}

	private void invalidateSupersedesInFlightFlow() {
		FakeTransport transport = new FakeTransport();
		FakeSession session = new FakeSession();
		transport.challengeServerIds = new String[]{"sid-stale"};
		transport.tokenForServerId.put("sid-stale", "jwt-stale");
		CountDownLatch verifyBlocked = new CountDownLatch(1);
		transport.blockVerifyOn = verifyBlocked;
		ClientApiAuth auth = new ClientApiAuth(transport, session, DIRECT);

		// In-flight flow on a worker thread: challenge answered, join done, verify
		// blocked on the latch — the exact window invalidate() must win.
		CompletableFuture<ClientApiAuth.State> flow = CompletableFuture.supplyAsync(
			() -> auth.authenticateAsync().join(), CompletableFuture.delayedExecutor(0, TimeUnit.MILLISECONDS));
		waitForJoin(session);
		auth.invalidate();
		verifyBlocked.countDown();
		ClientApiAuth.State state = flow.join();

		check(state instanceof ClientApiAuth.State.Failed, "a superseded flow must report failure to its caller");
		check("superseded".equals(((ClientApiAuth.State.Failed) state).getReason()),
			"the superseded flow must be identified, not published");
		check(auth.token() == null, "a superseded flow must never publish its token");
		check(auth.authenticatedFor() == null, "a superseded flow must not record its mint");
		check(auth.state() instanceof ClientApiAuth.State.NotStarted, "post-invalidate state must be a clean slate");
	}

	// ── the legion fallback ─────────────────────────────────────────────────────────────────

	private void legionFallbackRespectsChannelPriority() {
		try {
			ClientApi.LegionSnapshot snapshot = new ClientApi.LegionSnapshot(
				"spartans", "Spartans", 3, 1200L, 20, "owner", 2, java.util.List.of("Owner", "Leonidas"));

			// Channel owns the session (connected to ImperiumMC): never apply the state…
			ImperiumOnline.LegionApiFallback.applyIfVacant(snapshot, true);
			check(LegionStateRegistry.get() == null, "the API value must never fight the live channel");
			// …but member names are channel-absent data and still land (tab group highlight).
			check(LegionStateRegistry.isMember("leonidas"), "member names apply even while the channel owns the session");
			check(!LegionStateRegistry.isMember("stranger"), "non-members must not match");
			LegionStateRegistry.clear();

			// Not connected, registry vacant: the fallback fills it.
			ImperiumOnline.LegionApiFallback.applyIfVacant(snapshot, false);
			LegionStateRegistry.LegionState state = LegionStateRegistry.get();
			check(state != null, "the fallback must fill a vacant registry");
			check("Spartans".equals(state.name()), "display name preferred, channel-style fields pinned");
			check(state.onlineMembers() == 0, "presence is channel-only; the API cannot see it");
			check(state.totalMembers() == 2 && state.level() == 3, "member count and level must round-trip");
			check(state.power() == 0L, "war power is channel-only; the API cannot see it");
			check(LegionStateRegistry.isMember("Owner"), "member names round-trip case-insensitively");

			// Registry occupied (a channel push owns it): the fallback stands down.
			LegionStateRegistry.set("Spartans", 3, 12, 4200L, 5);
			ImperiumOnline.LegionApiFallback.applyIfVacant(
				new ClientApi.LegionSnapshot("others", null, 9, 1L, 9, null, 9, java.util.List.of()), false);
			check("Spartans".equals(LegionStateRegistry.get().name()), "an occupied registry must win over the API");
		} finally {
			LegionStateRegistry.clear();
		}
	}

	// ── helpers ─────────────────────────────────────────────────────────────────────────────

	/** Spins (bounded) until the fake session saw its joinServer call. */
	private static void waitForJoin(FakeSession session) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (session.joinCalls == 0) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("joinServer was never reached");
			}
			Thread.onSpinWait();
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
