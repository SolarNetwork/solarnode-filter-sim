/* ==================================================================
 * DatumSourceContext.java - 29 Oct 2026 6:50:00 pm
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

package s10k.node.sim.filter.registry;

import org.osgi.service.event.EventAdmin;
import net.solarnetwork.node.service.PlaceholderService;

/**
 * The simulated SolarNode services a datum source can use, in addition to those
 * a filter can use.
 *
 * @author matt
 * @version 1.0
 */
public interface DatumSourceContext extends FilterContext {

	/**
	 * Get the placeholder service, which the datum source blueprints give each
	 * datum source.
	 *
	 * @return the service
	 */
	PlaceholderService placeholderService();

	/**
	 * Get the event admin, which delivers the events of the simulated runtime
	 * components.
	 *
	 * @return the event admin
	 */
	EventAdmin eventAdmin();

}
