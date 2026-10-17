/* ==================================================================
 * SimDatumMetadataService.java - 18 Oct 2026 8:56:20 am
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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import net.solarnetwork.domain.datum.GeneralDatumMetadata;
import net.solarnetwork.domain.datum.ObjectDatumKind;
import net.solarnetwork.domain.datum.ObjectDatumStreamMetadata;
import net.solarnetwork.node.service.DatumMetadataService;

/**
 * In-memory {@link DatumMetadataService}.
 *
 * <p>
 * The Virtual Meter filter reads its previous reading from datum stream
 * metadata when it has none in Local State, for backwards compatibility.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class SimDatumMetadataService implements DatumMetadataService {

	private final Map<String, GeneralDatumMetadata> metadata = new TreeMap<>();

	@Override
	public synchronized Set<String> availableSourceMetadataSourceIds() {
		return new TreeSet<>(metadata.keySet());
	}

	@Override
	public synchronized void addSourceMetadata(String sourceId, GeneralDatumMetadata meta) {
		GeneralDatumMetadata existing = metadata.get(sourceId);
		if ( existing == null ) {
			metadata.put(sourceId, new GeneralDatumMetadata(meta));
		} else {
			existing.merge(meta, true);
		}
	}

	@Override
	public synchronized @Nullable GeneralDatumMetadata getSourceMetadata(String sourceId) {
		GeneralDatumMetadata meta = metadata.get(sourceId);
		return (meta != null ? new GeneralDatumMetadata(meta) : null);
	}

	@Override
	public @Nullable ObjectDatumStreamMetadata getDatumStreamMetadata(ObjectDatumKind kind,
			Long objectId, String sourceId) {
		return null;
	}

}
