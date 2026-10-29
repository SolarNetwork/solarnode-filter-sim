/* ==================================================================
 * DatumSourceTypeInfo.java - 29 Oct 2026 9:12:00 pm
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

package s10k.node.sim.filter.domain;

import java.util.Set;
import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Information about a supported datum source type.
 *
 * @param factoryUid
 *        the SolarNode component factory UID, for example
 *        {@code net.solarnetwork.node.datum.energymeter.mock}
 * @param name
 *        the display name
 * @param description
 *        a description, or {@code null}
 * @param defaultSchedule
 *        the poll schedule a new datum source has, as a cron expression or
 *        milliseconds
 * @param capabilities
 *        the datum source capabilities
 * @author matt
 * @version 1.0
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record DatumSourceTypeInfo(String factoryUid, String name, @Nullable String description,
		String defaultSchedule, Set<DatumSourceCapability> capabilities) {

}
