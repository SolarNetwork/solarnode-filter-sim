/* ==================================================================
 * BundleSource.java - 22 Oct 2026 9:05:12 am
 *
 * Copyright 2026 SolarNetwork.net Dev Team
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
 * ==================================================================
 */

package s10k.node.sim.filter.custom;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;

/**
 * Where a bundle's files come from: a jar, or a bundle project's directory.
 *
 * <p>
 * File names are relative to the bundle's root, with {@code /} separators, like
 * jar entry names.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
sealed interface BundleSource extends Closeable {

	/** The manifest of a bundle, relative to its root. */
	String MANIFEST = "META-INF/MANIFEST.MF";

	/**
	 * Get the jar or directory.
	 *
	 * @return the path
	 */
	Path path();

	/**
	 * Get the name to use if the manifest has no symbolic name.
	 *
	 * @return the name
	 */
	String defaultName();

	/**
	 * Get the manifest.
	 *
	 * @return the manifest, or {@code null} if there is none
	 * @throws IOException
	 *         if the manifest cannot be read
	 */
	@Nullable
	Manifest manifest() throws IOException;

	/**
	 * Get the names of the files in a directory, not including those in its
	 * subdirectories.
	 *
	 * @param dir
	 *        the directory, ending with {@code /}, or empty for the root
	 * @return the file names, sorted
	 * @throws IOException
	 *         if the directory cannot be read
	 */
	List<String> files(String dir) throws IOException;

	/**
	 * Test if a file exists, and is not a directory.
	 *
	 * @param name
	 *        the file name
	 * @return {@code true} if the file exists
	 */
	boolean isFile(String name);

	/**
	 * Open a file.
	 *
	 * @param name
	 *        the file name
	 * @return the file content
	 * @throws IOException
	 *         if the file cannot be read
	 */
	InputStream open(String name) throws IOException;

	/**
	 * Get the class path of the bundle itself, which a {@code Bundle-ClassPath}
	 * of {@code .} means.
	 *
	 * @param temp
	 *        the temporary files, for copies of jars
	 * @return the class path
	 * @throws IOException
	 *         if a jar cannot be copied
	 */
	List<Path> root(TempFiles temp) throws IOException;

	/**
	 * Get a bundle source.
	 *
	 * @param path
	 *        a jar, or a bundle project directory
	 * @return the source
	 * @throws IOException
	 *         if a jar cannot be opened
	 */
	static BundleSource of(Path path) throws IOException {
		return (Files.isDirectory(path) ? new Directory(path)
				: new Jar(path, new JarFile(path.toFile())));
	}

	/**
	 * Test if a directory is a bundle project, which has a manifest.
	 *
	 * @param dir
	 *        the directory
	 * @return {@code true} if the directory is a bundle project
	 */
	static boolean isBundleDirectory(Path dir) {
		return Files.isRegularFile(dir.resolve(MANIFEST));
	}

	/**
	 * A bundle jar.
	 *
	 * @param path
	 *        the jar
	 * @param jar
	 *        the opened jar
	 */
	record Jar(Path path, JarFile jar) implements BundleSource {

		@Override
		public String defaultName() {
			return path.getFileName().toString().replaceFirst("(?i)\\.jar$", "");
		}

		@Override
		public @Nullable Manifest manifest() throws IOException {
			return jar.getManifest();
		}

		@Override
		public List<String> files(String dir) {
			return jar.stream().filter(e -> !e.isDirectory()).map(JarEntry::getName)
					.filter(n -> n.startsWith(dir) && n.indexOf('/', dir.length()) < 0).sorted()
					.toList();
		}

		@Override
		public boolean isFile(String name) {
			final JarEntry entry = jar.getJarEntry(name);
			return (entry != null && !entry.isDirectory());
		}

		@Override
		public InputStream open(String name) throws IOException {
			final JarEntry entry = jar.getJarEntry(name);
			if ( entry == null ) {
				throw new IOException("No such entry: " + name);
			}
			return jar.getInputStream(entry);
		}

		@Override
		public List<Path> root(TempFiles temp) throws IOException {
			return List.of(temp.copy(path));
		}

		@Override
		public void close() throws IOException {
			jar.close();
		}

	}

	/**
	 * A bundle project's directory, with its manifest, blueprint, and compiled
	 * classes.
	 *
	 * <p>
	 * The classes are in the output folders that the project's Eclipse
	 * {@code build.properties} names, with its source folders after them, for
	 * resources that a build does not copy. Without that file, the first of
	 * {@link #OUTPUT_FOLDERS} that exists is used, with
	 * {@link #SOURCE_FOLDERS}, or else the directory itself, like an unzipped
	 * jar.
	 * </p>
	 *
	 * @param path
	 *        the directory
	 */
	record Directory(Path path) implements BundleSource {

		/** The usual output folders of bundle projects, by build tool. */
		static final List<String> OUTPUT_FOLDERS = List.of("build/eclipse", "build/ant/classes", "bin",
				"build/classes/java/main", "target/classes");

		/** The usual source and resource folders of bundle projects. */
		static final List<String> SOURCE_FOLDERS = List.of("src", "build/resources/main",
				"src/main/resources");

		@Override
		public String defaultName() {
			return path.getFileName().toString();
		}

		@Override
		public @Nullable Manifest manifest() throws IOException {
			final Path file = path.resolve(MANIFEST);
			if ( !Files.isRegularFile(file) ) {
				return null;
			}
			try (InputStream in = Files.newInputStream(file)) {
				return new Manifest(in);
			}
		}

		@Override
		public List<String> files(String dir) throws IOException {
			final Path d = path.resolve(dir);
			if ( !Files.isDirectory(d) ) {
				return List.of();
			}
			try (Stream<Path> files = Files.list(d)) {
				return files.filter(Files::isRegularFile).map(f -> dir + f.getFileName()).sorted()
						.toList();
			}
		}

		@Override
		public boolean isFile(String name) {
			return Files.isRegularFile(path.resolve(name));
		}

		@Override
		public InputStream open(String name) throws IOException {
			return Files.newInputStream(path.resolve(name));
		}

		@Override
		public List<Path> root(TempFiles temp) throws IOException {
			final List<Path> result = new ArrayList<>(2);
			final Path buildProperties = path.resolve("build.properties");
			if ( Files.isRegularFile(buildProperties) ) {
				final Properties props = new Properties();
				try (InputStream in = Files.newInputStream(buildProperties)) {
					props.load(in);
				}
				addFolders(result, props.getProperty("output.."));
				addFolders(result, props.getProperty("source.."));
			}
			if ( result.isEmpty() ) {
				for ( String folder : OUTPUT_FOLDERS ) {
					final Path p = path.resolve(folder);
					if ( Files.isDirectory(p) ) {
						result.add(p);
						break;
					}
				}
				if ( !result.isEmpty() ) {
					for ( String folder : SOURCE_FOLDERS ) {
						final Path p = path.resolve(folder);
						if ( Files.isDirectory(p) ) {
							result.add(p);
						}
					}
				}
			}
			if ( result.isEmpty() ) {
				result.add(path);
			}
			return result;
		}

		private void addFolders(List<Path> result, @Nullable String value) {
			if ( value == null ) {
				return;
			}
			for ( String folder : StringUtils.commaDelimitedListToStringArray(value) ) {
				final Path p = path.resolve(folder.strip()).normalize();
				if ( !folder.isBlank() && Files.isDirectory(p) && !result.contains(p) ) {
					result.add(p);
				}
			}
		}

		@Override
		public void close() {
			// nothing to close
		}

	}

}
