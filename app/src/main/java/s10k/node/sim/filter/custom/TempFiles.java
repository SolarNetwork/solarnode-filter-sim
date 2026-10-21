/* ==================================================================
 * TempFiles.java - 22 Oct 2026 9:05:12 am
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The copies of the custom filter jars, and of the jars they embed, in a
 * temporary directory, which the class loader loads instead of the originals.
 *
 * <p>
 * Loading copies means a build can replace a jar while the simulator runs
 * (Windows does not allow replacing a jar that is open), and a reload loads the
 * new jar. The files are deleted by {@link #delete()}, or when the simulator
 * stops.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
final class TempFiles {

	private @Nullable Path dir;
	private final List<Path> files = new ArrayList<>(8);

	/**
	 * Copy a jar.
	 *
	 * @param jar
	 *        the jar
	 * @return the copy
	 * @throws IOException
	 *         if the jar cannot be copied
	 */
	Path copy(Path jar) throws IOException {
		try (InputStream in = Files.newInputStream(jar)) {
			return save(jar.getFileName().toString(), in);
		}
	}

	/**
	 * Save a jar that a bundle embeds.
	 *
	 * @param entryName
	 *        the jar's name in the bundle
	 * @param in
	 *        the jar's content
	 * @return the saved file
	 * @throws IOException
	 *         if the jar cannot be saved
	 */
	Path save(String entryName, InputStream in) throws IOException {
		Path d = dir;
		if ( d == null ) {
			d = Files.createTempDirectory("sn-filter-sim-");
			d.toFile().deleteOnExit();
			dir = d;
		}
		// numbered, as the names come from the jars
		final String name = entryName.substring(entryName.lastIndexOf('/') + 1)
				.replaceAll("[^A-Za-z0-9._-]", "_");
		final Path file = d.resolve("%d-%s".formatted(files.size() + 1, name));
		file.toFile().deleteOnExit();
		Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
		files.add(file);
		return file;
	}

	/**
	 * Delete the files, once their class loader is closed.
	 */
	void delete() {
		for ( Path file : files ) {
			try {
				Files.deleteIfExists(file);
			} catch ( IOException e ) {
				// deleted when the simulator stops
			}
		}
		files.clear();
		final Path d = dir;
		if ( d != null ) {
			try {
				Files.deleteIfExists(d);
			} catch ( IOException e ) {
				// deleted when the simulator stops
			}
			dir = null;
		}
	}

}
