/* ==================================================================
 * FilterValue.java - 21 Oct 2026 2:05:41 pm
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

/**
 * A resolved filter constructor argument or property value.
 *
 * @author matt
 * @version 1.0
 */
public sealed interface FilterValue {

	/**
	 * A literal value.
	 *
	 * @param value
	 *        the value
	 */
	record Literal(String value) implements FilterValue {

	}

	/**
	 * A service.
	 *
	 * @param service
	 *        the service
	 */
	record Service(ServiceKey service) implements FilterValue {

	}

	/**
	 * The result of calling a no-argument method on a service, like a bean with
	 * a {@code factory-ref} and {@code factory-method}.
	 *
	 * @param service
	 *        the service
	 * @param method
	 *        the method to call on the service
	 */
	record ServiceMethod(ServiceKey service, String method) implements FilterValue {

	}

	/**
	 * A message source.
	 *
	 * @param messageSource
	 *        the message source
	 */
	record Messages(MessageSourceDescriptor messageSource) implements FilterValue {

	}

	/**
	 * A value that could not be resolved, which is also reported as a problem.
	 *
	 * @param reason
	 *        why the value could not be resolved
	 */
	record Unresolved(String reason) implements FilterValue {

	}

}
