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

import java.util.List;

import io.github.axolotlclient.util.duck.ChatHudExtension;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

/**
 * Exercises the chat_settings pipeline end to end on a real client: every option feeds the same
 * {@code ChatComponent.addMessage} wrap the live chat uses, and the assertions read back the
 * actual chat history via the {@link ChatHudExtension} duck interface, so a pass means the
 * transformations really ran (not that a helper returned true).
 *
 * Covered: timestamps (gray [HH:MM] prefix), the empty-message filter, own-name highlight
 * (gold-tinted sibling carrying the player's name), and the peek height hook (held peek key
 * switches the chat to focused height). Hover highlight is render-mouse dependent and is
 * verified at the code level only.
 */
public class ChatGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this suite is render-heavy, see ImperiumGametestSubsets
		GametestBookkeeping.executed(getClass().getSimpleName());
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();

			timestampsPrefixMessages(context);
			emptyFilterDropsBlankMessages(context);
			nameHighlightTintsOwnNameGold(context);
			peekKeyExpandsChatHeight(context);
		} finally {
			context.runOnClient(mc -> {
				AxolotlClient.config().chatTimestamps.set(false);
				AxolotlClient.config().chatEmptyFilter.set(false);
				AxolotlClient.config().chatNameHighlight.set(false);
				AxolotlClient.config().chatPeek.set(false);
				((KeyMapping) (Object) AxolotlClient.config().peekChatKey).setDown(false);
			});
		}
	}

	private void timestampsPrefixMessages(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			AxolotlClient.config().chatTimestamps.set(true);
			mc.gui.hud.getChat().addClientSystemMessage(Component.literal("timestamp probe"));
			GuiMessage last = history(mc).getFirst();
			check(last.content().getString().matches("\\[\\d{2}:\\d{2}] timestamp probe"),
				"timestamps must prefix the message with a [HH:MM] stamp, got: " + last.content().getString());
			// The prefix lives in its own gray sibling so message styling is untouched.
			check(!last.content().getSiblings().isEmpty()
				&& TextColor.fromLegacyFormat(ChatFormatting.GRAY).equals(last.content().getSiblings().getFirst().getStyle().getColor()),
				"timestamp prefix must be a gray sibling");
			AxolotlClient.config().chatTimestamps.set(false);
		});
	}

	private void emptyFilterDropsBlankMessages(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			AxolotlClient.config().chatEmptyFilter.set(true);
			int before = history(mc).size();
			mc.gui.hud.getChat().addClientSystemMessage(Component.literal("   "));
			check(history(mc).size() == before, "whitespace-only message must be dropped entirely");
			AxolotlClient.config().chatEmptyFilter.set(false);
			mc.gui.hud.getChat().addClientSystemMessage(Component.literal("   "));
			check(history(mc).size() == before + 1, "with the filter off, blank messages pass through");
		});
	}

	private void nameHighlightTintsOwnNameGold(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			AxolotlClient.config().chatNameHighlight.set(true);
			String name = mc.getUser().getName();
			mc.gui.hud.getChat().addClientSystemMessage(Component.literal("hey " + name + " look"));
			GuiMessage last = history(mc).getFirst();
			check(last.content().getString().equals("hey " + name + " look"),
				"highlight must not change the visible text");
			List<Component> siblings = last.content().getSiblings();
			boolean goldName = siblings.stream().anyMatch(s -> s.getString().equals(name)
				&& TextColor.fromLegacyFormat(ChatFormatting.GOLD).equals(s.getStyle().getColor()));
			check(goldName, "the own-name segment must be a sibling tinted gold");
			AxolotlClient.config().chatNameHighlight.set(false);
		});
	}

	private void peekKeyExpandsChatHeight(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			AxolotlClient.config().chatPeek.set(true);
			ChatComponent chat = mc.gui.hud.getChat();
			int baseline = chat.getLinesPerPage();
			((KeyMapping) (Object) AxolotlClient.config().peekChatKey).setDown(true);
			try {
				int peeked = chat.getLinesPerPage();
				check(peeked > baseline,
					"held peek key must raise the chat to focused height (" + baseline + " -> " + peeked + ")");
			} finally {
				((KeyMapping) (Object) AxolotlClient.config().peekChatKey).setDown(false);
			}
			check(chat.getLinesPerPage() == baseline, "releasing peek must restore unfocused height");
		});
	}

	private static List<GuiMessage> history(Minecraft mc) {
		return ((ChatHudExtension) mc.gui.hud.getChat()).axolotlclient$getMessages();
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
