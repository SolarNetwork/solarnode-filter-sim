/* ==================================================================
 * SimLocationService.java - 20 Oct 2026 3:35:26 pm
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

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import net.solarnetwork.domain.Location;
import net.solarnetwork.domain.SimpleLocation;
import net.solarnetwork.domain.datum.GeneralLocationSourceMetadata;
import net.solarnetwork.node.service.LocationService;

/**
 * A {@link LocationService} that provides the node location.
 *
 * <p>
 * Location metadata comes from SolarNetwork on a node, so the simulator has
 * none.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class SimLocationService implements LocationService {

	private @Nullable Location nodeLocation;

	@Override
	public Collection<GeneralLocationSourceMetadata> findLocationMetadata(String query,
			@Nullable String sourceId, @Nullable Set<String> tags) {
		return List.of();
	}

	@Override
	public @Nullable GeneralLocationSourceMetadata getLocationMetadata(Long locationId,
			String sourceId) {
		return null;
	}

	@Override
	public synchronized void updateNodeLocation(Location location) {
		this.nodeLocation = new SimpleLocation(location);
	}

	@Override
	public synchronized @Nullable Location getNodeLocation() {
		final Location loc = this.nodeLocation;
		return (loc != null ? new SimpleLocation(loc) : null);
	}

}
