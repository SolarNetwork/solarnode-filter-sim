/* ==================================================================
 * UserMetadataConfig.java - 18 Oct 2026 7:20:10 pm
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

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A simulated SolarNode User Metadata Service.
 *
 * <p>
 * On a node, the service fetches the user's metadata from SolarNetwork, so its
 * settings are only credentials. The simulator gets the metadata from here
 * instead.
 * </p>
 *
 * @param uid
 *        the service's Service Name, which filters use to find it
 * @param metadata
 *        the metadata, as a JSON object in the SolarNetwork metadata format,
 *        for example {@code {"pm":{"tariffs":{"schedule":"…"}}}}, or
 *        {@code null} for none
 * @author matt
 * @version 1.0
 */
public record UserMetadataConfig(@Nullable String uid, @Nullable JsonNode metadata) {

}
