/* ==================================================================
 * FilterBundle.java - 21 Oct 2026 4:12:08 pm
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

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A jar loaded from the custom filters path.
 *
 * @param name
 *        the bundle symbolic name, or the jar file name if the manifest has
 *        none
 * @param version
 *        the bundle version, or {@code null} if the manifest has none
 * @param file
 *        the jar
 * @param factoryUids
 *        the factory UIDs of the available filter types the bundle provides
 * @param datumSourceUids
 *        the factory UIDs of the available datum source types the bundle
 *        provides
 * @param blueprint
 *        {@code true} if the bundle has blueprint XML; a jar without it is a
 *        library for other bundles
 * @param warnings
 *        problems with the bundle's manifest that might stop its filters from
 *        working, such as packages it imports that are missing
 * @author matt
 * @version 1.1
 */
public record FilterBundle(String name, @Nullable String version, Path file, List<String> factoryUids,
		List<String> datumSourceUids, boolean blueprint, List<String> warnings) {

	/**
	 * Get the bundle name and version, for messages.
	 *
	 * @return the label, for example {@code com.example.filter 1.2.0}
	 */
	public String label() {
		return (version != null ? name + " " + version : name);
	}

}
