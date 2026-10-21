/* ==================================================================
 * FilterTypeDescriptor.java - 21 Oct 2026 2:05:41 pm
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

package s10k.node.sim.filter.blueprint;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import s10k.node.sim.filter.domain.FilterCapability;

/**
 * A user datum filter type, as defined by the blueprint of the bundle that
 * provides it.
 *
 * @param factoryUid
 *        the factory UID (the managed service factory PID)
 * @param displayName
 *        the display name from the factory service, or {@code null} if not
 *        given
 * @param filterClassName
 *        the filter implementation class name
 * @param initMethod
 *        the init method, or {@code null}
 * @param destroyMethod
 *        the destroy method, or {@code null}
 * @param messageSource
 *        the message source from the factory service, or {@code null} if it
 *        could not be resolved
 * @param arguments
 *        the filter constructor arguments, in order
 * @param properties
 *        the filter properties, by name, in order
 * @param capabilities
 *        the capabilities inferred from the services the filter uses
 * @param problems
 *        the constructs that could not be resolved
 * @author matt
 * @version 1.0
 */
public record FilterTypeDescriptor(String factoryUid, @Nullable String displayName,
		String filterClassName, @Nullable String initMethod, @Nullable String destroyMethod,
		@Nullable MessageSourceDescriptor messageSource, List<FilterValue> arguments,
		Map<String, FilterValue> properties, Set<FilterCapability> capabilities, List<String> problems) {

}
