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

package net.fabricmc.loader.launch.server;

import club.issizler.okyanus.json.installer.InstallerFile;
import club.issizler.okyanus.json.installer.Library;
import com.google.gson.Gson;
import net.fabricmc.loader.util.UrlUtil;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public class FabricServerLauncher {
	private static final ClassLoader parentLoader = FabricServerLauncher.class.getClassLoader();
	private static String mainClass = "net.fabricmc.loader.launch.knot.KnotServer";
	private static File libFolder = new File(".okyanus" + File.separator + "libraries");

	public static void main(String[] args) {
		URL propUrl = parentLoader.getResource("fabric-server-launch.properties");
		if (propUrl != null) {
			Properties properties = new Properties();
			try (InputStream is = propUrl.openStream()) {
				properties.load(is);
			} catch (IOException e) {
				e.printStackTrace();
			}

			if (properties.containsKey("launch.mainClass")) {
				mainClass = properties.getProperty("launch.mainClass");
			}
		}

		boolean dev = Boolean.parseBoolean(System.getProperty("fabric.development", "false"));

		if (!dev) {
			try {
				checkLibraries();
				setup(args);
			} catch (Exception e) {
				throw new RuntimeException("Failed to setup Fabric server environment!", e);
			}
		} else {
			launch(mainClass, FabricServerLauncher.class.getClassLoader(), args);
		}
	}

	private static void launch(String mainClass, ClassLoader loader, String[] args) {
		try {
			Class<?> c = loader.loadClass(mainClass);
			c.getMethod("main", String[].class).invoke(null, (Object) args);
		} catch (Exception e) {
			throw new RuntimeException("An exception occurred when launching the server!", e);
		}
	}

	private static void setup(String... runArguments) throws IOException {
		// Pre-load "fabric-server-launcher.properties"
		File propertiesFile = new File("okyanus-loader.properties");
		Properties properties = new Properties();

		if (propertiesFile.exists()) {
			try (FileInputStream stream = new FileInputStream(propertiesFile)) {
				properties.load(stream);
			}
		}

		// Most popular Minecraft server hosting platforms do not allow
		// passing arbitrary arguments to the server .JAR. Meanwhile,
		// Mojang's default server filename is "server.jar" as of
		// a few versions... let's use this.
		if (!properties.containsKey("serverJar")) {
			properties.put("serverJar", ".okyanus" + File.separator + "server.jar");
			try (FileOutputStream stream = new FileOutputStream(propertiesFile)) {
				properties.store(stream, null);
			}
		}

		if (!properties.containsKey("serverUrl")) {
			// TODO: update this every time a new version gets released
			// Current version: 1.14.3
			properties.put("serverUrl", "https://launcher.mojang.com/v1/objects/d0d0fe2b1dc6ab4c65554cb734270872b72dadd6/server.jar");

			try (FileOutputStream stream = new FileOutputStream(propertiesFile)) {
				properties.store(stream, null);
			}
		}

		File serverJar = new File((String) properties.get("serverJar"));
		URL serverUrl = new URL((String) properties.get("serverUrl"));

		if (!serverJar.exists()) {
			downloadFile(serverJar, serverUrl);
		}

		System.setProperty("fabric.gameJarPath", serverJar.getAbsolutePath());
		try {
			URLClassLoader newClassLoader = new InjectingURLClassLoader(new URL[]{FabricServerLauncher.class.getProtectionDomain().getCodeSource().getLocation(), UrlUtil.asUrl(serverJar)}, parentLoader, "com.google.common.jimfs.");
			Thread.currentThread().setContextClassLoader(newClassLoader);
			launch(mainClass, newClassLoader, runArguments);
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}


	private static String sanitizedLibName(String name) {
		return name.replace(':', '.');
	}

	private static void downloadLibrary(Library library) {
		downloadFile(new File(libFolder, sanitizedLibName(library.name)), mkUrlFromName(library.name, library.url));
	}

	private static URL mkUrlFromName(String name, String root) {
		String[] spname = name.split(":");

		String path = spname[0].replace('.', '/') + "/" + spname[1] + "/" + spname[2];
		String fname = spname[1] + "-" + spname[2] + ".jar";

		try {
			return new URL(root + path + "/" + fname);
		} catch (MalformedURLException e) {
			System.err.println("Malformed library URL!");
			e.printStackTrace();
			System.exit(1);
		}

		return null;
	}

	private static void checkLibraries() {
		if (FabricServerLauncher.class.getResource("/mappings/mappings.tiny") != null)
			return; // We're already patched!

		InputStream in = FabricServerLauncher.class.getResourceAsStream("/fabric-installer.json");
		BufferedReader reader = new BufferedReader(new InputStreamReader(in));
		InstallerFile file = new Gson().fromJson(reader, InstallerFile.class);
//
		if (!libFolder.exists()) {
			libFolder.mkdirs();
		}

		for (Library library : file.libraries.common) {
			File libFile = new File(libFolder, sanitizedLibName(library.name));
			if (!libFile.exists()) {
				downloadLibrary(library);
			}
		}

		for (Library library : file.libraries.server) {
			File libFile = new File(libFolder, sanitizedLibName(library.name));
			if (!libFile.exists()) {
				downloadLibrary(library);
			}
		}

		File intermediary = new File(libFolder, "intermediary");
		if (!intermediary.exists()) {
			try {
				downloadFile(intermediary, new URL("https://maven.fabricmc.net/net/fabricmc/intermediary/1.14.3/intermediary-1.14.3.jar"));
			} catch (MalformedURLException e) {
				throw new RuntimeException(e);
			}
		}

		System.out.println("Patching current jar...");
		try (FileSystem thisFs = FileSystems.newFileSystem(Paths.get(FabricServerLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()), null)) {

			for (File lib : Objects.requireNonNull(libFolder.listFiles())) {
				System.out.println("Applying library " + lib);

				try (FileSystem libFs = FileSystems.newFileSystem(Paths.get(lib.toURI()), null)){
					for (Path directory : libFs.getRootDirectories()) {
						copyDir(directory, thisFs.getPath("/"));
					}
				} catch (IOException e) {
					throw new RuntimeException(e);
				}

				lib.deleteOnExit();
			}

		} catch (URISyntaxException | IOException e) {
			throw new RuntimeException(e);
		}

		System.out.println("Please re-start this JAR to complete installation!");
		System.exit(0);
	}

	private static void copyDir(Path from, Path to) {
		try (Stream<Path> stream = Files.walk(from)) {
			stream.forEachOrdered(path -> {
				try {
					if (!path.toString().contains("MANIFEST.MF"))
						Files.copy(path, to.relativize(path), StandardCopyOption.REPLACE_EXISTING);
				} catch (NegativeArraySizeException | FileAlreadyExistsException e) {
					/* ignore */
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static void downloadFile(File file, URL url) {
		System.out.println(url + " => " + file.getName());

		try {
			ReadableByteChannel rbc = Channels.newChannel(url.openStream());
			FileOutputStream fos = new FileOutputStream(file);
			fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
		} catch (IOException e) {
			System.err.println("Could not download file!");
			e.printStackTrace();
			System.exit(-1);
		}
	}
}
