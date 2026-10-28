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

import io.github.axolotlclient.modules.hud.HudManager;
import io.github.axolotlclient.modules.hud.gui.hud.vanilla.TitleHud;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * Titles HUD coverage (issue #15 item 2): a coloured (gold + red sections via Component
 * literals, not legacy codes) and deliberately over-long title plus subtitle must render
 * through the TitleHud entry without the colours collapsing to one flat colour and without
 * the text running outside the entry: long text wraps to further centered lines and the
 * entry height grows to fit them.
 *
 * <p>The title is set through the real vanilla path ({@code Hud.setTitle}), which the
 * InGameHudMixin forwards into the entry, so the state flow exercised here is exactly the
 * one a server's /title command drives.</p>
 */
public class TitlesGameTest implements FabricClientGameTest {

	private static final String LONG_TITLE_TEXT =
		"The Romanica salutes its builders across every corner of the realm and beyond the horizon";
	private static final String LONG_SUBTITLE_TEXT =
		"a coloured subtitle that is also far too long to fit on a single line";

	@Override
	public void runTest(ClientGameTestContext context) {
		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this suite is render-heavy, see ImperiumGametestSubsets
		GametestBookkeeping.executed(getClass().getSimpleName());
		try (var world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();

			// The run directory keeps config between runs; pin the entry on like a player would.
			context.runOnClient(mc -> titleHud().setEnabled(true));
			// No fade, long stay: the title is fully opaque and on screen for the whole check.
			context.runOnClient(mc -> mc.gui.hud.setTimes(0, 400, 0));

			Component title = Component.literal("The ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal("Romanica ").withStyle(ChatFormatting.RED))
				.append(Component.literal(LONG_TITLE_TEXT.substring(13)).withStyle(ChatFormatting.GOLD));
			Component subtitle = Component.literal("a ").withStyle(ChatFormatting.YELLOW)
				.append(Component.literal("coloured").withStyle(ChatFormatting.AQUA))
				.append(Component.literal(LONG_SUBTITLE_TEXT.substring(11)).withStyle(ChatFormatting.YELLOW));

			context.runOnClient(mc -> {
				mc.gui.hud.setTitle(title);
				mc.gui.hud.setSubtitle(subtitle);
			});
			context.waitTicks(20);
			context.takeScreenshot("titles-coloured-wrapped");

			int widest = context.computeOnClient(mc -> titleHud().lastRenderedMaxLineWidth());
			check(widest > 0, "the title entry should have drawn something (widest line " + widest + ")");
			int contentWidth = context.computeOnClient(mc -> titleHud().getContentWidth());
			check(widest <= contentWidth,
				"wrapped title lines must stay inside the entry (widest " + widest + " > content " + contentWidth + ")");

			int height = context.computeOnClient(mc -> titleHud().getHeight());
			int initial = context.computeOnClient(mc -> titleHud().getInitialContentHeight());
			check(height > initial,
				"the over-long title must wrap to more lines and grow the entry (height " + height + " <= initial " + initial + ")");

			// The flat colour path must not have swallowed the sections: gold and red resolve
			// to different RGB values, so the styled component still carries its formatting.
			check(title.getStyle().getColor() != null && !title.getSiblings().isEmpty(),
				"the title component carries per-section styles");
			check(title.getStyle().getColor().getValue()
					!= title.getSiblings().get(0).getStyle().getColor().getValue(),
				"the title component keeps distinct per-section colours");

			// Clearing returns the entry to its editor placeholder size.
			context.runOnClient(mc -> mc.gui.hud.clearTitles());
			context.waitTicks(10);
			int clearedHeight = context.computeOnClient(mc -> titleHud().getHeight());
			check(clearedHeight == initial,
				"clearTitles should shrink the entry back to the placeholder height (got " + clearedHeight + ")");
		}
	}

	private static TitleHud titleHud() {
		TitleHud hud = (TitleHud) HudManager.getInstance().get(TitleHud.ID);
		if (hud == null) {
			throw new AssertionError("TitleHud not registered");
		}
		return hud;
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
