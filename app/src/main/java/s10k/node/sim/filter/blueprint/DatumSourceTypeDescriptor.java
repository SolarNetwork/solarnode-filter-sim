/* ==================================================================
 * DatumSourceTypeDescriptor.java - 30 Oct 2026 3:12:44 pm
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
import org.jspecify.annotations.Nullable;

/**
 * A datum source type, as defined by the blueprint of the bundle that provides
 * it: a managed service factory of a {@code SimpleManagedJob} that wraps a
 * {@code DatumDataSourcePollManagedJob} that wraps the datum source.
 *
 * @param factoryUid
 *        the factory UID (the managed service factory PID)
 * @param displayName
 *        the display name from the factory service, or {@code null} if not
 *        given
 * @param sourceClassName
 *        the datum source implementation class name
 * @param initMethod
 *        the datum source bean's init method, or {@code null}
 * @param destroyMethod
 *        the datum source bean's destroy method, or {@code null}
 * @param messageSource
 *        the factory service's message source, or {@code null} if not given
 * @param arguments
 *        the datum source bean's constructor arguments
 * @param properties
 *        the datum source bean's properties, in blueprint order
 * @param defaultSchedule
 *        the managed job's schedule, or {@code null} if not given
 * @param serviceInterfaces
 *        the interfaces the managed job publishes the datum source as, from its
 *        service provider configurations
 * @param multi
 *        {@code true} if the poll job's datum source is a
 *        {@code MultiDatumDataSource}
 * @param problems
 *        problems found describing the datum source type
 * @author matt
 * @version 1.0
 */
public record DatumSourceTypeDescriptor(String factoryUid, @Nullable String displayName,
		String sourceClassName, @Nullable String initMethod, @Nullable String destroyMethod,
		@Nullable MessageSourceDescriptor messageSource, List<FilterValue> arguments,
		Map<String, FilterValue> properties, @Nullable String defaultSchedule,
		List<String> serviceInterfaces, boolean multi, List<String> problems) {

}
