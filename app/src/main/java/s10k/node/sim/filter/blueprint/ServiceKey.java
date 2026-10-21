/* ==================================================================
 * ServiceKey.java - 21 Oct 2026 2:05:41 pm
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

import org.jspecify.annotations.Nullable;

/**
 * An OSGi service a filter needs, which the simulator must provide.
 *
 * @param serviceClassName
 *        the service interface
 * @param filter
 *        the service filter, or {@code null}
 * @param source
 *        how the blueprint obtains the service
 * @param ignoreEmptyPropertyFilterValues
 *        the {@code ignoreEmptyPropertyFilterValues} setting of a service
 *        tracker, which is {@code true} unless the blueprint sets it, and is
 *        always {@code true} for other sources
 * @author matt
 * @version 1.0
 */
public record ServiceKey(String serviceClassName, @Nullable String filter, Source source,
		boolean ignoreEmptyPropertyFilterValues) {

	/**
	 * Constructor, ignoring empty property filter values.
	 *
	 * @param serviceClassName
	 *        the service interface
	 * @param filter
	 *        the service filter, or {@code null}
	 * @param source
	 *        how the blueprint obtains the service
	 */
	public ServiceKey(String serviceClassName, @Nullable String filter, Source source) {
		this(serviceClassName, filter, source, true);
	}

	/** How a blueprint obtains a service. */
	public enum Source {

		/** A {@code <reference>}, which is a proxy to one service. */
		Reference,

		/** A {@code <reference-list>}, which is a list of services. */
		ReferenceList,

		/**
		 * A {@code DynamicServiceTracker} bean, which is a proxy to one
		 * service, or an iterable of all of them.
		 */
		Tracker,

	}

}
