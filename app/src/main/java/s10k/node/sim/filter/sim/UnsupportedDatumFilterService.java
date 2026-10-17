/* ==================================================================
 * UnsupportedDatumFilterService.java - 18 Oct 2026 8:56:20 am
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

package s10k.node.sim.filter.sim;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import net.solarnetwork.domain.datum.Datum;
import net.solarnetwork.domain.datum.DatumSamplesOperations;
import net.solarnetwork.service.DatumFilterService;

/**
 * A stand-in for a filter type the simulator does not support, which passes
 * datum through unchanged.
 *
 * @author matt
 * @version 1.0
 */
public class UnsupportedDatumFilterService implements DatumFilterService {

	private final String factoryUid;
	private final @Nullable String uid;

	/**
	 * Constructor.
	 *
	 * @param factoryUid
	 *        the factory UID of the unsupported filter type
	 * @param uid
	 *        the filter's Service Name, or {@code null}
	 */
	public UnsupportedDatumFilterService(String factoryUid, @Nullable String uid) {
		super();
		this.factoryUid = factoryUid;
		this.uid = uid;
	}

	@Override
	public DatumSamplesOperations filter(Datum datum, DatumSamplesOperations samples,
			@Nullable Map<String, Object> parameters) {
		return samples;
	}

	@Override
	public @Nullable String getUid() {
		return uid;
	}

	@Override
	public @Nullable String getGroupUid() {
		return null;
	}

	@Override
	public @Nullable String getDisplayName() {
		return factoryUid;
	}

}
