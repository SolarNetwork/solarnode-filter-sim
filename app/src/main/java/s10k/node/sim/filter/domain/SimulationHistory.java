/* ==================================================================
 * SimulationHistory.java - 18 Oct 2026 8:56:20 am
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

import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Datum to pre-load into the datum history before a simulation starts.
 *
 * <p>
 * SolarNode keeps two histories: the normal history of datum after filters have
 * been applied, used by expression functions like {@code latest()}, and the
 * unfiltered history of datum before any filters, used by functions like
 * {@code unfilteredLatest()}.
 * </p>
 *
 * @param filtered
 *        datum for the normal (filtered) history, as JSON objects in the
 *        SolarNode datum format
 * @param unfiltered
 *        datum for the unfiltered history, or {@code null} to use the same
 *        datum as {@code filtered}
 * @author matt
 * @version 1.0
 */
public record SimulationHistory(@Nullable List<JsonNode> filtered, @Nullable List<JsonNode> unfiltered) {

}
