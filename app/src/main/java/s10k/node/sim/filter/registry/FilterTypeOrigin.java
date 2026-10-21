/* ==================================================================
 * FilterTypeOrigin.java - 21 Oct 2026 4:12:08 pm
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

package s10k.node.sim.filter.registry;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import s10k.node.sim.filter.domain.FilterBundleInfo;

/**
 * The bundle a custom filter type comes from.
 *
 * @param bundleName
 *        the bundle symbolic name, or the jar file name if the manifest has
 *        none
 * @param bundleVersion
 *        the bundle version, or {@code null} if the manifest has none
 * @param file
 *        the bundle jar
 * @param classLoader
 *        the class loader of the filter classes, which filters run with as
 *        their thread context class loader
 * @author matt
 * @version 1.0
 */
public record FilterTypeOrigin(String bundleName, @Nullable String bundleVersion, Path file,
		ClassLoader classLoader) {

	/**
	 * Get the bundle name and version, for messages.
	 *
	 * @return the label, for example {@code com.example.filter 1.2.0}
	 */
	public String label() {
		return (bundleVersion != null ? bundleName + " " + bundleVersion : bundleName);
	}

	/**
	 * Get the bundle information, for the API.
	 *
	 * @return the information
	 */
	public FilterBundleInfo info() {
		return new FilterBundleInfo(bundleName, bundleVersion);
	}

}
