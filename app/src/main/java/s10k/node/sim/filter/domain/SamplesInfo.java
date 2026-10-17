/* ==================================================================
 * SamplesInfo.java - 18 Oct 2026 8:56:20 am
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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonInclude;
import net.solarnetwork.domain.datum.DatumSamplesOperations;
import net.solarnetwork.domain.datum.DatumSamplesType;

/**
 * A snapshot of datum sample properties, in the SolarNode datum JSON style.
 *
 * @param i
 *        the instantaneous properties
 * @param a
 *        the accumulating properties
 * @param s
 *        the status properties
 * @param t
 *        the tags
 * @author matt
 * @version 1.0
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record SamplesInfo(@Nullable Map<String, Object> i, @Nullable Map<String, Object> a,
		@Nullable Map<String, Object> s, @Nullable Set<String> t) {

	/** Empty samples. */
	public static final SamplesInfo EMPTY = new SamplesInfo(null, null, null, null);

	/**
	 * Create a snapshot of samples.
	 *
	 * @param samples
	 *        the samples to copy, or {@code null}
	 * @return the snapshot, never {@code null}
	 */
	public static SamplesInfo from(@Nullable DatumSamplesOperations samples) {
		if ( samples == null ) {
			return EMPTY;
		}
		Set<String> tags = samples.getTags();
		return new SamplesInfo(copy(samples.getSampleData(DatumSamplesType.Instantaneous)),
				copy(samples.getSampleData(DatumSamplesType.Accumulating)),
				copy(samples.getSampleData(DatumSamplesType.Status)),
				tags != null && !tags.isEmpty() ? new LinkedHashSet<>(tags) : null);
	}

	private static @Nullable Map<String, Object> copy(@Nullable Map<String, ?> data) {
		if ( data == null || data.isEmpty() ) {
			return null;
		}
		return new LinkedHashMap<>(data);
	}

}
