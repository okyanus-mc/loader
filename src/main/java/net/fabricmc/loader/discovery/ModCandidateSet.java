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

package net.fabricmc.loader.discovery;

import com.google.common.base.Joiner;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;

import java.util.*;
import java.util.stream.Collectors;

public class ModCandidateSet {
	private final String modId;
	private final Set<ModCandidate> depthZeroCandidates = new HashSet<>();
	private final Map<String, ModCandidate> candidates = new HashMap<>();

	private static Set<ModCandidate> createNewestVersionSet() {
		return new TreeSet<>((a, b) -> {
			Version av = a.getInfo().getVersion();
			Version bv = b.getInfo().getVersion();

			if (av instanceof SemanticVersion && bv instanceof SemanticVersion) {
				return ((SemanticVersion) bv).compareTo((SemanticVersion) av);
			} else {
				return 0;
			}
		});
	}

	public ModCandidateSet(String modId) {
		this.modId = modId;
	}

	public String getModId() {
		return modId;
	}

	public boolean add(ModCandidate candidate) {
		String version = candidate.getInfo().getVersion().getFriendlyString();
		ModCandidate oldCandidate = candidates.get(version);
		if (oldCandidate != null) {
			int oldDepth = oldCandidate.getDepth();
			int newDepth = candidate.getDepth();

			if (oldDepth <= newDepth) {
				return false;
			} else {
				candidates.remove(version);
				if (oldDepth > 0) {
					depthZeroCandidates.remove(oldCandidate);
				}
			}
		}

		candidates.put(version, candidate);
		if (candidate.getDepth() == 0) {
			depthZeroCandidates.add(candidate);
		}

		return true;
	}

	public boolean isUserProvided() {
		return !depthZeroCandidates.isEmpty();
	}

	public Collection<ModCandidate> toSortedSet() throws ModResolutionException {
		if (depthZeroCandidates.size() > 1) {
			Set<String> modVersionStrings = depthZeroCandidates.stream()
				.map((c) -> "[" + c.getInfo().getVersion() + " at " + c.getOriginUrl().getFile() + "]")
				.collect(Collectors.toSet());

			throw new ModResolutionException("Duplicate versions for mod ID '" + modId + "': " + Joiner.on(", ").join(modVersionStrings));
		} else if (depthZeroCandidates.size() == 1) {
			return depthZeroCandidates;
		} else if (candidates.size() > 1) {
			Set<ModCandidate> out = createNewestVersionSet();
			out.addAll(candidates.values());
			return out;
		} else {
			return Collections.singleton(candidates.values().iterator().next());
		}
	}
}
