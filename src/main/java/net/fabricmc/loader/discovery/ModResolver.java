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
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.common.jimfs.PathType;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.FabricLoader;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.launch.common.FabricLauncherBase;
import net.fabricmc.loader.metadata.LoaderModMetadata;
import net.fabricmc.loader.metadata.ModMetadataParser;
import net.fabricmc.loader.metadata.NestedJarEntry;
import net.fabricmc.loader.util.FileSystemUtil;
import net.fabricmc.loader.util.UrlConversionException;
import net.fabricmc.loader.util.UrlUtil;
import net.fabricmc.loader.util.sat4j.core.VecInt;
import net.fabricmc.loader.util.sat4j.minisat.SolverFactory;
import net.fabricmc.loader.util.sat4j.specs.TimeoutException;
import net.fabricmc.loader.util.sat4j.specs.*;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.google.common.jimfs.Feature.FILE_CHANNEL;
import static com.google.common.jimfs.Feature.SECURE_DIRECTORY_STREAM;

public class ModResolver {
	// nested JAR store
	private static final FileSystem inMemoryFs = Jimfs.newFileSystem(
		"nestedJarStore",
		Configuration.builder(PathType.unix())
			.setRoots("/")
			.setWorkingDirectory("/")
			.setAttributeViews("basic")
			.setSupportedFeatures(SECURE_DIRECTORY_STREAM, FILE_CHANNEL)
			.build()
	);
	private static final Map<URL, List<Path>> inMemoryCache = new ConcurrentHashMap<>();
	private static final Pattern MOD_ID_PATTERN = Pattern.compile("[a-z][a-z0-9-_]{1,63}");
	private static final Object launcherSyncObject = new Object();

	private final List<ModCandidateFinder> candidateFinders = new ArrayList<>();

	public ModResolver() {
	}

	public void addCandidateFinder(ModCandidateFinder f) {
		candidateFinders.add(f);
	}

	private static IVecInt toVecInt(IntStream stream) {
		return new VecInt(stream.toArray());
	}

	private boolean matches(ModDependency dependency, Map<String, ModCandidate> result) {
		if (!result.containsKey(dependency.getModId())) {
			return false;
		}

		return dependency.matches(result.get(dependency.getModId()).getInfo().getVersion());
	}

	private void addErrorToList(ModCandidate candidate, ModDependency dependency, Map<String, ModCandidate> result, StringBuilder errors, String errorType, boolean cond) {
		if (matches(dependency, result) != cond) {
			errors.append("\n - Mod ").append(candidate.getInfo().getId()).append(" ").append(errorType).append(" mod ").append(dependency).append(", which is missing!");
		}
	}

	// TODO: Find a way to sort versions of mods by suggestions and conflicts (not crucial, though)
	public Map<String, ModCandidate> findCompatibleSet(Logger logger, Map<String, ModCandidateSet> modCandidateSetMap) throws ModResolutionException {
		// First, map all ModCandidateSets to Set<ModCandidate>s.
		boolean isAdvanced = false;
		Map<String, Collection<ModCandidate>> modCandidateMap = new HashMap<>();
		Set<String> mandatoryMods = new HashSet<>();

		for (ModCandidateSet mcs : modCandidateSetMap.values()) {
			Collection<ModCandidate> s = mcs.toSortedSet();
			modCandidateMap.put(mcs.getModId(), s);
			isAdvanced |= (s.size() > 1) || (s.iterator().next().getDepth() > 0);

			if (mcs.isUserProvided()) {
				mandatoryMods.add(mcs.getModId());
			}
		}

		Map<String, ModCandidate> result;

		if (!isAdvanced) {
			result = new HashMap<>();
			for (String s : modCandidateMap.keySet()) {
				result.put(s, modCandidateMap.get(s).iterator().next());
			}
		} else {
			// Inspired by http://0install.net/solver.html
			// probably also horrendously slow, for now

			// Map all the ModCandidates to DIMACS-format positive integers.
			int varCount = 1;
			Map<ModCandidate, Integer> candidateIntMap = new HashMap<>();
			List<ModCandidate> intCandidateMap = new ArrayList<>(modCandidateMap.size() * 2);
			intCandidateMap.add(null);
			for (Collection<ModCandidate> m : modCandidateMap.values()) {
				for (ModCandidate candidate : m) {
					candidateIntMap.put(candidate, varCount++);
					intCandidateMap.add(candidate);
				}
			}

			ISolver solver = SolverFactory.newLight();
			solver.newVar(varCount);

			try {
				// Each mod needs to have at most one version.
				for (String id : modCandidateMap.keySet()) {
					IVecInt versionVec = toVecInt(modCandidateMap.get(id).stream().mapToInt(candidateIntMap::get));

					try {
						if (mandatoryMods.contains(id)) {
							solver.addExactly(versionVec, 1);
						} else {
							solver.addAtMost(versionVec, 1);
						}
					} catch (ContradictionException e) {
						throw new ModResolutionException("Could not resolve valid mod collection (at: adding mod " + id + ")", e);
					}
				}

				for (ModCandidate mod : candidateIntMap.keySet()) {
					int modClauseId = candidateIntMap.get(mod);

					// Each mod's requirements must be satisfied, if it is to be present.
					// mod => ((a or b) AND (d or e))
					// \> not mod OR ((a or b) AND (d or e))
					// \> ((not mod OR a OR b) AND (not mod OR d OR e))

					for (ModDependency dep : mod.getInfo().getDepends()) {
						int[] matchingCandidates = modCandidateMap.getOrDefault(dep.getModId(), Collections.emptyList())
							.stream()
							.filter((c) -> dep.matches(c.getInfo().getVersion()))
							.mapToInt(candidateIntMap::get)
							.toArray();

						int[] clause = new int[matchingCandidates.length + 1];
						System.arraycopy(matchingCandidates, 0, clause, 0, matchingCandidates.length);
						clause[matchingCandidates.length] = -modClauseId;

						try {
							solver.addClause(new VecInt(clause));
						} catch (ContradictionException e) {
							throw new ModResolutionException("Could not resolve valid mod collection (at: " + mod.getInfo().getId() + " requires " + dep + ")", e);
						}
					}

					// Each mod's breaks must be NOT satisfied, if it is to be present.
					// mod => (not a AND not b AND not d AND not e))
					// \> not mod OR (not a AND not b AND not d AND not e)
					// \> (not mod OR not a) AND (not mod OR not b) ...

					for (ModDependency dep : mod.getInfo().getBreaks()) {
						int[] matchingCandidates = modCandidateMap.getOrDefault(dep.getModId(), Collections.emptyList())
							.stream()
							.filter((c) -> dep.matches(c.getInfo().getVersion()))
							.mapToInt(candidateIntMap::get)
							.toArray();

						try {
							for (int m : matchingCandidates) {
								solver.addClause(new VecInt(new int[] { -modClauseId, -m }));
							}
						} catch (ContradictionException e) {
							throw new ModResolutionException("Could not resolve valid mod collection (at: " + mod.getInfo().getId() + " breaks " + dep + ")", e);
						}
					}
				}

				//noinspection UnnecessaryLocalVariable
				IProblem problem = solver;
				IVecInt assumptions = new VecInt(modCandidateMap.size());

				for (String mod : modCandidateMap.keySet()) {
					int pos = assumptions.size();
					assumptions = assumptions.push(0);
					Collection<ModCandidate> candidates = modCandidateMap.get(mod);
					boolean satisfied = false;

					for (ModCandidate candidate : candidates) {
						assumptions.set(pos, candidateIntMap.get(candidate));
						if (problem.isSatisfiable(assumptions)) {
							satisfied = true;
							break;
						}
					}

					if (!satisfied) {
						if (mandatoryMods.contains(mod)) {
							throw new ModResolutionException("Could not resolve mod collection including mandatory mod '" + mod + "'");
						} else {
							assumptions = assumptions.pop();
						}
					}
				}

				// assume satisfied
				int[] model = problem.model();
				result = new HashMap<>();

				for (int i : model) {
					if (i <= 0) {
						continue;
					}

					ModCandidate candidate = intCandidateMap.get(i);
					if (result.containsKey(candidate.getInfo().getId())) {
						throw new ModResolutionException("Duplicate ID '" + candidate.getInfo().getId() + "' after solving - wrong constraints?");
					} else {
						result.put(candidate.getInfo().getId(), candidate);
					}
				}
			} catch (TimeoutException e) {
				throw new ModResolutionException("Mod collection took too long to be resolved", e);
			}
		}

		// verify result: all mandatory mods
		Set<String> missingMods = new HashSet<>();
		for (String m : mandatoryMods) {
			if (!result.keySet().contains(m)) {
				missingMods.add(m);
			}
		}

		if (!missingMods.isEmpty()) {
			throw new ModResolutionException("Missing mods: " + Joiner.on(", ").join(missingMods));
		}

		// verify result: dependencies
		StringBuilder errorsHard = new StringBuilder();
		StringBuilder errorsSoft = new StringBuilder();

		for (ModCandidate candidate : result.values()) {
			for (ModDependency dependency : candidate.getInfo().getDepends()) {
				addErrorToList(candidate, dependency, result, errorsHard, "depends on", true);
			}

			for (ModDependency dependency : candidate.getInfo().getRecommends()) {
				addErrorToList(candidate, dependency, result, errorsSoft, "recommends", true);
			}

			for (ModDependency dependency : candidate.getInfo().getBreaks()) {
				addErrorToList(candidate, dependency, result, errorsHard, "breaks", false);
			}

			for (ModDependency dependency : candidate.getInfo().getConflicts()) {
				addErrorToList(candidate, dependency, result, errorsSoft, "conflicts with", false);
			}
		}

		String errHardStr = errorsHard.toString();
		String errSoftStr = errorsSoft.toString();

		if (!errHardStr.isEmpty()) {
			throw new ModResolutionException("Unsatisfied dependencies!" + errHardStr + errSoftStr);
		} else if (!errSoftStr.isEmpty()) {
			logger.warn("Non-mandatory unsatisfied dependencies! " + errSoftStr);
		}

		return result;
	}

	static class UrlProcessAction extends RecursiveAction {
		private final FabricLoader loader;
		private final Map<String, ModCandidateSet> candidatesById;
		private final URL url;
		private final int depth;

		UrlProcessAction(FabricLoader loader, Map<String, ModCandidateSet> candidatesById, URL url, int depth) {
			this.loader = loader;
			this.candidatesById = candidatesById;
			this.url = url;
			this.depth = depth;
		}

		@Override
		protected void compute() {
			FileSystemUtil.FileSystemDelegate jarFs;
			Path path, modJson, okyModJson, rootDir;
			URL normalizedUrl;

			loader.getLogger().debug("Testing " + url);

			try {
				path = UrlUtil.asPath(url).normalize();
				// normalize URL (used as key for nested JAR lookup)
				normalizedUrl = UrlUtil.asUrl(path);
			} catch (UrlConversionException e) {
				throw new RuntimeException("Failed to convert URL " + url + "!", e);
			}

			if (Files.isDirectory(path)) {
				// Directory
				modJson = path.resolve("fabric.mod.json");
				okyModJson = path.resolve("okyanus.json");
				rootDir = path;

				if (loader.isDevelopmentEnvironment() && !Files.exists(modJson)) {
					loader.getLogger().warn("Adding directory " + path + " to mod classpath in development environment - workaround for Gradle splitting mods into two directories");
					synchronized (launcherSyncObject) {
						FabricLauncherBase.getLauncher().propose(url);
					}
				}
			} else {
				// JAR file
				try {
					jarFs = FileSystemUtil.getJarFileSystem(path, false);
					modJson = jarFs.get().getPath("fabric.mod.json");
					okyModJson = jarFs.get().getPath("okyanus.json");
					rootDir = jarFs.get().getRootDirectories().iterator().next();
				} catch (IOException e) {
					throw new RuntimeException("Failed to open mod JAR at " + path + "!");
				}
			}

			LoaderModMetadata[] info;

			try (InputStream stream = Files.newInputStream(modJson)) {
				info = ModMetadataParser.getMods(loader, stream);
			} catch (JsonSyntaxException e) {
				throw new RuntimeException("Mod at '" + path + "' has an invalid fabric.mod.json file!", e);
			} catch (NoSuchFileException e) {
				try (InputStream stream = Files.newInputStream(okyModJson)) {
					info = ModMetadataParser.getMods(loader, stream);
				} catch (JsonSyntaxException e1) {
					throw new RuntimeException("Mod at '" + path + "' has an invalid okyanus.json file!", e1);
				} catch (NoSuchFileException e1) {
					info = new LoaderModMetadata[0];
				} catch (IOException e1) {
					throw new RuntimeException("Failed to open okyanus.json for mod at '" + path + "'!", e1);
				}
			} catch (IOException e) {
				throw new RuntimeException("Failed to open fabric.mod.json for mod at '" + path + "'!", e);
			}

			for (LoaderModMetadata i : info) {
				ModCandidate candidate = new ModCandidate(i, normalizedUrl, depth);
				boolean added;

				if (candidate.getInfo().getId() == null || candidate.getInfo().getId().isEmpty()) {
					throw new RuntimeException(String.format("Mod file `%s` has no id", candidate.getOriginUrl().getFile()));
				}

				if (!MOD_ID_PATTERN.matcher(candidate.getInfo().getId()).matches()) {
					throw new RuntimeException(String.format("Mod id `%s` does not match the requirements", candidate.getInfo().getId()));
				}

				if (candidate.getInfo().getSchemaVersion() < ModMetadataParser.LATEST_VERSION) {
					loader.getLogger().warn("Mod ID " + candidate.getInfo().getId() + " uses outdated schema version: " + candidate.getInfo().getSchemaVersion() + " < " + ModMetadataParser.LATEST_VERSION);
				}

				added = candidatesById.computeIfAbsent(candidate.getInfo().getId(), ModCandidateSet::new).add(candidate);

				if (!added) {
					loader.getLogger().debug(candidate.getOriginUrl() + " already present as " + candidate);
				} else {
					loader.getLogger().debug("Adding " + candidate.getOriginUrl() + " as " + candidate);

					List<Path> jarInJars = inMemoryCache.computeIfAbsent(candidate.getOriginUrl(), (u) -> {
						loader.getLogger().debug("Searching for nested JARs in " + candidate);
						Collection<NestedJarEntry> jars = candidate.getInfo().getJars();
						List<Path> list = new ArrayList<>(jars.size());

						jars.stream()
							.map((j) -> rootDir.resolve(j.getFile().replace("/", rootDir.getFileSystem().getSeparator())))
							.forEach((modPath) -> {
								if (!Files.isDirectory(modPath) && modPath.toString().endsWith(".jar")) {
									// TODO: pre-check the JAR before loading it, if possible
									loader.getLogger().debug("Found nested JAR: " + modPath);
									Path dest = inMemoryFs.getPath(UUID.randomUUID() + ".jar");

									try {
										Files.copy(modPath, dest);
									} catch (IOException e) {
										throw new RuntimeException("Failed to load nested JAR " + modPath + " into memory (" + dest + ")!", e);
									}

									list.add(dest);
								}
							});

						return list;
					});

					if (!jarInJars.isEmpty()) {
						invokeAll(
							jarInJars.stream()
								.map((p) -> {
									try {
										return new UrlProcessAction(loader, candidatesById, UrlUtil.asUrl(p.normalize()), depth + 1);
									} catch (UrlConversionException e) {
										throw new RuntimeException("Failed to turn path '" + p.normalize() + "' into URL!", e);
									}
								}).collect(Collectors.toList())
						);
					}
				}
			}

			/* if (jarFs != null) {
				jarFs.close();
			} */
		}
	}

	public Map<String, ModCandidate> resolve(FabricLoader loader) throws ModResolutionException {
		Map<String, ModCandidateSet> candidatesById = new ConcurrentHashMap<>();

		long time1 = System.currentTimeMillis();

		Queue<UrlProcessAction> allActions = new ConcurrentLinkedQueue<>();
		ForkJoinPool pool = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
		for (ModCandidateFinder f : candidateFinders) {
			f.findCandidates(loader, (u) -> {
				UrlProcessAction action = new UrlProcessAction(loader, candidatesById, u, 0);
				allActions.add(action);
				pool.execute(action);
			});
		}

		boolean tookTooLong = false;
		Throwable exception = null;
		try {
			pool.shutdown();
			pool.awaitTermination(30, TimeUnit.SECONDS);
			for (UrlProcessAction action : allActions) {
				if (!action.isDone()) {
					tookTooLong = true;
				} else {
					Throwable t = action.getException();
					if (t != null) {
						if (exception == null) {
							exception = t;
						} else {
							exception.addSuppressed(t);
						}
					}
				}
			}
		} catch (InterruptedException e) {
			throw new RuntimeException("Mod resolution took too long!", e);
		}
		if (tookTooLong) {
			throw new RuntimeException("Mod resolution took too long!");
		}
		if (exception != null) {
			throw new RuntimeException("Mod resolution failed!", exception);
		}

		long time2 = System.currentTimeMillis();
		Map<String, ModCandidate> result = findCompatibleSet(loader.getLogger(), candidatesById);

		long time3 = System.currentTimeMillis();
		loader.getLogger().debug("Mod resolution detection time: " + (time2 - time1) + "ms");
		loader.getLogger().debug("Mod resolution time: " + (time3 - time2) + "ms");

		return result;
	}
}
