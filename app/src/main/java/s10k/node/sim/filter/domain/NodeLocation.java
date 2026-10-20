/* ==================================================================
 * NodeLocation.java - 20 Oct 2026 3:33:27 pm
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

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * The location of the simulated node, which the Location Service provides.
 *
 * @param latitude
 *        the latitude, in decimal degrees
 * @param longitude
 *        the longitude, in decimal degrees
 * @param elevation
 *        the elevation above sea level, in meters
 * @author matt
 * @version 1.0
 */
public record NodeLocation(@Nullable BigDecimal latitude, @Nullable BigDecimal longitude,
		@Nullable BigDecimal elevation) {

}
