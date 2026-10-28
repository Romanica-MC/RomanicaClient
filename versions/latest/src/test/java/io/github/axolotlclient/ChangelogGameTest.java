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

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.github.axolotlclient.imperium.ImperiumClientSession;
import io.github.axolotlclient.imperium.changelog.ChangelogEntry;
import io.github.axolotlclient.imperium.changelog.ChangelogScreen;
import io.github.axolotlclient.imperium.changelog.ChangelogService;
import io.github.axolotlclient.imperium.menu.ImperiumTitleScreen;
import kotlin.jvm.functions.Function2;
import kotlin.Unit;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * The title screen's "What's new" entry (issue #28): both click branches with the real
 * input path, the changelog view's render, and the parser's tolerance for the backend's
 * actual response shape. The backend is never contacted: {@link ChangelogService#forceForTest}
 * pins the fetch outcome, and the URL opener is replaced by a recorder, so CI proves the
 * website fallback without a desktop browser.
 */
public class ChangelogGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this suite is render-heavy, see ImperiumGametestSubsets
		GametestBookkeeping.executed(getClass().getSimpleName());

		parseBothContractShapes();
		malformedBodiesNeverThrow();
		unreadDecisionFollowsLastSeen();

		ImperiumClientSession.getInstance().reset();
		// The run directory keeps config between runs: a previous run's "seen" mark would
		// eat the unread dot this test seeds. Clear it like CarvedThemeGameTest resets its theme.
		context.runOnClient(mc -> AxolotlClient.config().changelogLastSeenId.set(""));
		AtomicReference<URI> opened = new AtomicReference<>();
		Function2<net.minecraft.client.gui.screens.Screen, URI, Unit> originalOpener = ChangelogService.getUrlOpener();
		ChangelogService.setUrlOpener((screen, uri) -> {
			opened.set(uri);
			return Unit.INSTANCE;
		});
		try {
			clickWithFailedFetchOpensWebsite(context, opened);
			clickWithLoadedEntriesOpensView(context);
			changelogViewRendersAndClearsTheDot(context, opened);
		} finally {
			context.setScreen(() -> null);
			context.waitTicks(2);
			ChangelogService.setUrlOpener(originalOpener);
			ChangelogService.forceForTest(ChangelogService.State.IDLE, List.of());
			ImperiumClientSession.getInstance().reset();
		}
	}

	/** Fetch failed (offline or no client entries yet): the click opens the website instead. */
	private void clickWithFailedFetchOpensWebsite(ClientGameTestContext context, AtomicReference<URI> opened) {
		ChangelogService.forceForTest(ChangelogService.State.FAILED, List.of());
		opened.set(null);
		context.setScreen(ImperiumTitleScreen::new);
		context.waitForScreen(ImperiumTitleScreen.class);
		context.waitTicks(10);
		context.takeScreenshot("whats-new-entry-title-screen");
		clickGui(context, context.computeOnClient(ChangelogGameTest::whatsNewCenter));
		context.waitTicks(5);
		check(URI.create(ChangelogService.WEBSITE_CHANGELOG_URL).equals(opened.get()),
			"a failed fetch must open " + ChangelogService.WEBSITE_CHANGELOG_URL + ", got " + opened.get());
		check(context.computeOnClient(mc -> mc.gui.screen()) instanceof ImperiumTitleScreen,
			"the website fallback must leave the title screen up, got " + context.computeOnClient(mc -> mc.gui.screen()));
	}

	/** Entries loaded: the same click opens the in-client changelog view. */
	private void clickWithLoadedEntriesOpensView(ClientGameTestContext context) {
		ChangelogService.forceForTest(ChangelogService.State.LOADED, sampleEntries());
		clickGui(context, context.computeOnClient(ChangelogGameTest::whatsNewCenter));
		context.waitTicks(5);
		check(context.computeOnClient(ChangelogGameTest::screenIsChangelog),
			"clicking What's new with a loaded changelog must open ChangelogScreen");
		context.waitTicks(10);
		context.takeScreenshot("whats-new-changelog-view");
	}

	/** The view lists both posts with unread dots, and going back clears the title dot. */
	private void changelogViewRendersAndClearsTheDot(ClientGameTestContext context, AtomicReference<URI> opened) {
		int cards = context.computeOnClient(mc -> screenAs(mc).cardsVisible());
		check(cards == 2, "the seeded changelog should render 2 cards, got " + cards);
		boolean dotOnEntry = context.computeOnClient(mc -> screenAs(mc).showsUnreadDot());
		check(dotOnEntry, "entries newer than the last-seen id must carry the unread dot");

		// the view's website pill goes through the same recorder, never a real browser
		opened.set(null);
		clickGui(context, context.computeOnClient(mc -> screenAs(mc).websitePillCenter()));
		context.waitTicks(3);
		check(URI.create(ChangelogService.WEBSITE_CHANGELOG_URL).equals(opened.get()),
			"the view's website pill must open " + ChangelogService.WEBSITE_CHANGELOG_URL + ", got " + opened.get());

		// opening the view marks the newest entry seen: the title screen dot clears after Back
		clickGui(context, context.computeOnClient(mc -> screenAs(mc).backPillCenter()));
		context.waitTicks(5);
		check(context.computeOnClient(mc -> mc.gui.screen()) instanceof ImperiumTitleScreen,
			"Back must return to the title screen");
		check(!context.computeOnClient(mc -> ChangelogService.hasUnread()),
			"after viewing, the newest entry counts as seen and the dot clears");
	}

	// ── pure parser checks ───────────────────────────────────────────────────────────────────

	/** The backend's real shape ({"posts":[...]}, curled 2026-10-28) and the task's array contract. */
	private void parseBothContractShapes() {
		String realShape = "{\"posts\":[{"
			+ "\"title\":\"October 27: fixes\",\"lead\":\"\",\"postedAt\":\"2026-10-27T23:19:38.270Z\","
			+ "\"sections\":[{\"name\":\"Fixes\",\"changes\":[\"- one thing\",\"- another\"]}]}]}";
		List<ChangelogEntry> real = ChangelogEntry.parseList(realShape);
		check(real.size() == 1, "the backend's {posts:[...]} shape must parse, got " + real.size());
		check("October 27: fixes".equals(real.get(0).getTitle()), "the title must come through");
		check(real.get(0).getSections().size() == 1 && real.get(0).getSections().get(0).getChanges().size() == 2,
			"section bullets must come through");
		check(!real.get(0).getId().isEmpty(), "posts without an id get a stable derived one");

		String arrayShape = "[{\"id\":\"cli-9\",\"date\":\"2026-10-26T00:00:00Z\",\"product\":\"client\","
			+ "\"version\":\"0.2.0-beta.27\",\"title\":\"Older post\",\"summary\":\"short\",\"links\":"
			+ "[{\"url\":\"https://romanica.net/changelog\"}]}]";
		List<ChangelogEntry> array = ChangelogEntry.parseList(arrayShape);
		check(array.size() == 1 && "cli-9".equals(array.get(0).getId()), "the bare-array contract shape must parse");
		check("0.2.0-beta.27".equals(array.get(0).getVersion()), "versions must come through when present");

		// newest first, derived ids are stable across runs (the unread-dot bookkeeping relies on it)
		List<ChangelogEntry> both = ChangelogEntry.parseList(
			"{\"posts\":[" + realShape.substring(realShape.indexOf('[') + 1, realShape.lastIndexOf(']'))
				+ "," + arrayShape.substring(1, arrayShape.length() - 1) + "]}");
		check(both.size() == 2, "both posts must parse together");
		check("October 27: fixes".equals(both.get(0).getTitle()), "newest post must sort first");
		check(both.get(0).getId().equals(ChangelogEntry.parseList(realShape).get(0).getId()),
			"derived ids must be stable across parses");
	}

	/** Garbage in, empty list out: the title screen must never crash on API errors (issue #28). */
	private void malformedBodiesNeverThrow() {
		for (String body : new String[]{"", "   ", "not json", "{\"posts\":null}", "[]", "{\"posts\":[1,true,null]}",
			"{\"posts\":[{\"title\":42}]}", "{\"posts\":\"nope\"}", "{'single':'quotes'}"}) {
			List<ChangelogEntry> parsed = ChangelogEntry.parseList(body);
			check(parsed.isEmpty(), "malformed body must parse to an empty list: " + body);
		}
	}

	/** Unread means: entries exist and the newest id differs from the last-seen id. */
	private void unreadDecisionFollowsLastSeen() {
		List<ChangelogEntry> entries = sampleEntries();
		check(ChangelogEntry.hasUnread(entries, "seen-already"), "a newer post than last-seen is unread");
		check(ChangelogEntry.hasUnread(entries, "cli-9"), "having only seen the older post leaves the newest unread");
		check(!ChangelogEntry.hasUnread(entries, "newest-1"), "seeing the newest post clears the dot");
		check(!ChangelogEntry.hasUnread(List.of(), ""), "an empty changelog is never unread");
	}

	private static List<ChangelogEntry> sampleEntries() {
		return List.of(
			new ChangelogEntry("newest-1", "October 27: what's new ships",
				"2026-10-27T12:00:00Z", "The title screen now shows what changed.", null, null,
				List.of(), List.of(new ChangelogEntry.Section("New", List.of("a what's new entry", "an unread dot")))),
			new ChangelogEntry("cli-9", "October 26: earlier fixes",
				"2026-10-26T09:00:00Z", "Shorter lead text.", "0.2.0-beta.27", "client",
				List.of("https://romanica.net/changelog"), List.of())
		);
	}

	// ── harness helpers (same voice as ImperiumMenuGameTest) ─────────────────────────────────

	private static void clickGui(ClientGameTestContext context, int[] guiPos) {
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		context.getInput().setCursorPos(guiPos[0] * scale, guiPos[1] * scale);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
	}

	private static int[] whatsNewCenter(Minecraft mc) {
		ImperiumTitleScreen title = requireTitle(mc);
		return new int[]{title.whatsNewX() + title.whatsNewW() / 2, title.whatsNewY() + 5};
	}

	private static ChangelogScreenProxy screenAs(Minecraft mc) {
		if (mc.gui.screen() instanceof ChangelogScreen screen) {
			return new ChangelogScreenProxy(screen);
		}
		throw new AssertionError("expected ChangelogScreen, got " + mc.gui.screen());
	}

	private static boolean screenIsChangelog(Minecraft mc) {
		return mc.gui.screen() instanceof ChangelogScreen;
	}

	private static ImperiumTitleScreen requireTitle(Minecraft mc) {
		if (mc.gui.screen() instanceof ImperiumTitleScreen title) {
			return title;
		}
		throw new AssertionError("expected ImperiumTitleScreen, got " + mc.gui.screen());
	}

	/** Java-side handle onto the screen's internal accessors, so the checks read one line each. */
	private record ChangelogScreenProxy(ChangelogScreen screen) {

		int cardsVisible() {
			return screen.cardCount();
		}

		boolean showsUnreadDot() {
			return screen.firstCardUnread();
		}

		int[] websitePillCenter() {
			return screen.websitePillCenter();
		}

		int[] backPillCenter() {
			return screen.backPillCenter();
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
