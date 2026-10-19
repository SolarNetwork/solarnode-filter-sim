/* ==================================================================
 * SimulationEnvironment.java - 18 Oct 2026 8:56:20 am
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
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The starting state of the simulated node.
 *
 * @param opModes
 *        the operational modes active when the simulation starts; mode names
 *        are case-insensitive
 * @param localStateCsv
 *        Local State to load when the simulation starts, in the SolarNode Local
 *        State CSV format ({@code Key,Created,Modified,Type,Value}); blank
 *        {@code Created} and {@code Modified} values default to the time of the
 *        first input datum
 * @param nodeMetadata
 *        the node's metadata, which the Node Metadata Service provides, as a
 *        JSON object in the SolarNetwork metadata format
 *        ({@code {"m":{…},"pm":{…},"t":[…]}}), or {@code null} for none
 * @param userMetadata
 *        the User Metadata Services, or {@code null} for none
 * @param placeholders
 *        placeholder values, like those in a node's {@code conf/placeholders.d}
 *        files, or {@code null} for none
 * @param datumMetadata
 *        datum stream (source) metadata, as a map of source IDs to JSON objects
 *        in the SolarNetwork metadata format, or {@code null} for none
 * @author matt
 * @version 1.0
 */
public record SimulationEnvironment(@Nullable List<String> opModes, @Nullable String localStateCsv,
		@Nullable JsonNode nodeMetadata, @Nullable List<UserMetadataConfig> userMetadata,
		@Nullable Map<String, String> placeholders, @Nullable Map<String, JsonNode> datumMetadata) {

}
