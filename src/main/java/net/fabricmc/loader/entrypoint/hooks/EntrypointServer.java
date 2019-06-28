/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.entrypoint.hooks;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.FabricLoader;
import net.fabricmc.loader.api.EntrypointException;

import java.io.File;

public final class EntrypointServer {
	public static void start(File runDir, Object gameInstance) {
		if (runDir == null) {
			runDir = new File(".");
		}

		FabricLoader.INSTANCE.instantiateMods(runDir, gameInstance);
		EntrypointUtils.logErrors("main", FabricLoader.INSTANCE.getEntrypoints("main", ModInitializer.class), ModInitializer::onInitialize);
		EntrypointUtils.logErrors("server", FabricLoader.INSTANCE.getEntrypoints("server", DedicatedServerModInitializer.class), DedicatedServerModInitializer::onInitializeServer);
	}
}
