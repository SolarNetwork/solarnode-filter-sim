/* ==================================================================
 * UnavailableFilterType.java - 21 Oct 2026 6:40:15 pm
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

import org.jspecify.annotations.Nullable;
import s10k.node.sim.filter.domain.FilterBundleInfo;
import s10k.node.sim.filter.domain.UnavailableFilterTypeInfo;

/**
 * A filter type the simulator knows of but cannot run: a SolarNode filter it
 * does not support, or a custom filter type that is not available.
 *
 * <p>
 * Imports keep the settings of its filters, and simulations pass datum through
 * them unchanged.
 * </p>
 *
 * @param factoryUid
 *        the factory UID
 * @param displayName
 *        the display name, or {@code null} if not known
 * @param bundle
 *        the bundle that provides a custom filter type, or {@code null}
 * @param reason
 *        why the filter type is not available, as a sentence
 * @author matt
 * @version 1.0
 */
public record UnavailableFilterType(String factoryUid, @Nullable String displayName,
		@Nullable FilterBundleInfo bundle, String reason) {

	/**
	 * Get the display name, or the factory UID if there is none.
	 *
	 * @return the name
	 */
	public String name() {
		return (displayName != null ? displayName : factoryUid);
	}

	/**
	 * Get the API information.
	 *
	 * @return the information
	 */
	public UnavailableFilterTypeInfo info() {
		return new UnavailableFilterTypeInfo(factoryUid, name(), bundle, reason);
	}

}
