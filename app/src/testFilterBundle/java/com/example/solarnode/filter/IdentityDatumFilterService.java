/* ==================================================================
 * IdentityDatumFilterService.java - 21 Oct 2026 4:12:08 pm
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

package com.example.solarnode.filter;

import java.util.Map;
import net.solarnetwork.domain.datum.Datum;
import net.solarnetwork.domain.datum.DatumSamplesOperations;
import net.solarnetwork.node.service.IdentityService;
import net.solarnetwork.node.service.support.BaseDatumFilterSupport;
import net.solarnetwork.service.DatumFilterService;

/**
 * A custom filter for the custom filter tests that needs a service the
 * simulator does not provide, so it is not available.
 *
 * @author matt
 * @version 1.0
 */
public class IdentityDatumFilterService extends BaseDatumFilterSupport implements DatumFilterService {

	private final IdentityService identityService;

	/**
	 * Constructor.
	 *
	 * @param identityService
	 *        the identity service
	 */
	public IdentityDatumFilterService(IdentityService identityService) {
		super();
		this.identityService = identityService;
	}

	@Override
	public DatumSamplesOperations filter(Datum datum, DatumSamplesOperations samples,
			Map<String, Object> parameters) {
		return samples;
	}

	/**
	 * Get the identity service.
	 *
	 * @return the service
	 */
	public IdentityService getIdentityService() {
		return identityService;
	}

}
