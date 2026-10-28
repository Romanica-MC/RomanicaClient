/*
 * Copyright © 2024 moehreag <moehreag@gmail.com> & Contributors
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

import io.github.axolotlclient.bridge.internal.BridgeValidationPostprocessor;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AxolotlClientTest implements ClientModInitializer {
	private static final Logger logger = LoggerFactory.getLogger("axolotlclient-test");

	@Override
	public void onInitializeClient() {
		BridgeValidationPostprocessor.validate();
		// The gametest bootstrap: make the active suite subset visible in the run log
		// (ImperiumGametestSubsets explains how the ci subset is selected).
		logger.info("Client gametests: subset '{}'", ImperiumGametestSubsets.describe());
		// The run directory survives between runs (configs, saves), and so would the
		// executed-count file: a run whose tests all vanished would otherwise be judged
		// by the previous run's count. Only the gametest launch sets this property.
		if (Boolean.getBoolean("fabric.client.gametest")) {
			GametestBookkeeping.resetForNewRun();
		}
	}
}
