/* ==================================================================
 * MetricInfo.java - 20 Oct 2026 3:33:27 pm
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

import java.time.Instant;

/**
 * A metric a filter saved, like the Metric Harvester filter does.
 *
 * @param timestamp
 *        the metric date
 * @param type
 *        the metric type, such as {@code s} for a sample value
 * @param name
 *        the metric name
 * @param value
 *        the metric value
 * @author matt
 * @version 1.0
 */
public record MetricInfo(Instant timestamp, String type, String name, double value) {

}
