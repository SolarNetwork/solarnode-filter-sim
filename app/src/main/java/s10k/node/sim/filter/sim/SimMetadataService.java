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
import net.solarnetwork.domain.InstructionStatus.InstructionState;
import net.solarnetwork.domain.datum.GeneralDatumMetadata;
import net.solarnetwork.node.reactor.Instruction;
import net.solarnetwork.node.reactor.InstructionHandler;
import net.solarnetwork.node.reactor.InstructionStatus;
import net.solarnetwork.node.reactor.InstructionUtils;
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
 * <p>
 * Like them, it handles a {@code Signal} instruction with a parameter named its
 * signal name: {@code clear-cache} completes (the metadata here is never
 * cached), and any other value is declined. A {@code Signal} without that
 * parameter is not its own.
 * </p>
 *
 * @author matt
 * @version 1.1
 */
public final class SimMetadataService extends BasicIdentifiable
		implements MetadataService, InstructionHandler {

	/** The Service Name of the SolarNode Node Metadata Service. */
	public static final String NODE_METADATA_SERVICE_UID = "Node Metadata Service";

	/**
	 * The signal name of the SolarNode Node Metadata Service, its setting UID.
	 */
	public static final String NODE_METADATA_SIGNAL_NAME = "net.solarnetwork.node.metadata.json";

	/** The signal that clears the metadata cache. */
	public static final String SIGNAL_CLEAR_CACHE = "clear-cache";

	/**
	 * The error code of an unsupported signal, from the Node Metadata Service.
	 */
	public static final String NODE_SIGNAL_ERROR_CODE = "JNMS.0001";

	/**
	 * The error code of an unsupported signal, from a User Metadata Service.
	 */
	public static final String USER_SIGNAL_ERROR_CODE = "UMS.0001";

	private final GeneralDatumMetadata metadata;
	private final boolean node;

	/**
	 * Constructor, for a User Metadata Service.
	 *
	 * @param uid
	 *        the Service Name, which is also its signal name
	 * @param metadata
	 *        the metadata
	 */
	public SimMetadataService(@Nullable String uid, GeneralDatumMetadata metadata) {
		this(uid, metadata, false);
	}

	/**
	 * Constructor.
	 *
	 * @param uid
	 *        the Service Name
	 * @param metadata
	 *        the metadata
	 * @param node
	 *        {@code true} for the Node Metadata Service, whose signal name is
	 *        {@link #NODE_METADATA_SIGNAL_NAME}, or {@code false} for a User
	 *        Metadata Service, whose signal name is its Service Name
	 */
	public SimMetadataService(@Nullable String uid, GeneralDatumMetadata metadata, boolean node) {
		super();
		setUid(uid);
		this.metadata = metadata;
		this.node = node;
	}

	@Override
	public GeneralDatumMetadata getAllMetadata() {
		return metadata;
	}

	/**
	 * Get the name of the parameter of the {@code Signal} instructions this
	 * service handles.
	 *
	 * @return the signal name, or {@code null} if it has none
	 */
	public @Nullable String signalName() {
		return (node ? NODE_METADATA_SIGNAL_NAME : getUid());
	}

	@Override
	public boolean handlesTopic(@Nullable String topic) {
		return InstructionHandler.TOPIC_SIGNAL.equalsIgnoreCase(topic);
	}

	@Override
	public @Nullable InstructionStatus processInstruction(Instruction instruction) {
		if ( !handlesTopic(instruction.getTopic()) ) {
			return null;
		}
		final String name = signalName();
		if ( name == null || !instruction.isParameterAvailable(name) ) {
			return null;
		}
		if ( SIGNAL_CLEAR_CACHE.equals(instruction.getParameterValue(name)) ) {
			return InstructionUtils.createStatus(instruction, InstructionState.Completed);
		}
		return InstructionUtils.createStatus(instruction, InstructionState.Declined,
				InstructionUtils.createErrorResultParameters("Signal name not supported.",
						node ? NODE_SIGNAL_ERROR_CODE : USER_SIGNAL_ERROR_CODE));
	}

}
