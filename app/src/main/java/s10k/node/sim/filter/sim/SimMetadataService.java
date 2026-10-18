/* ==================================================================
 * SimMetadataService.java - 18 Oct 2026 7:16:30 pm
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

import org.jspecify.annotations.Nullable;
import net.solarnetwork.domain.datum.GeneralDatumMetadata;
import net.solarnetwork.node.service.MetadataService;
import net.solarnetwork.service.support.BasicIdentifiable;

/**
 * A simulated {@link MetadataService} with fixed metadata.
 *
 * <p>
 * It stands in for the SolarNode Node Metadata Service and User Metadata
 * Service components, which fetch their metadata from SolarNetwork.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class SimMetadataService extends BasicIdentifiable implements MetadataService {

	/** The Service Name of the SolarNode Node Metadata Service. */
	public static final String NODE_METADATA_SERVICE_UID = "Node Metadata Service";

	private final GeneralDatumMetadata metadata;

	/**
	 * Constructor.
	 *
	 * @param uid
	 *        the Service Name
	 * @param metadata
	 *        the metadata
	 */
	public SimMetadataService(@Nullable String uid, GeneralDatumMetadata metadata) {
		super();
		setUid(uid);
		this.metadata = metadata;
	}

	@Override
	public GeneralDatumMetadata getAllMetadata() {
		return metadata;
	}

}
