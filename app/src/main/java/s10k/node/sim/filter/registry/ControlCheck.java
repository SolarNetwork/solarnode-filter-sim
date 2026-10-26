/* ==================================================================
 * ControlCheck.java - 25 Oct 2026 10:12:40 am
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

import java.util.LinkedHashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import net.solarnetwork.node.datum.filter.control.ControlConfig;
import net.solarnetwork.node.datum.filter.control.ControlUpdaterDatumFilterService;
import net.solarnetwork.node.service.NodeControlProvider;
import net.solarnetwork.service.DatumFilterService;

/**
 * Check that the controls the Control Updater filter sets exist.
 *
 * <p>
 * Control IDs with placeholders, such as {@code {sourceId}}, are resolved for
 * each datum, so they are not checked.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class ControlCheck implements FilterCheck {

	@Override
	public @Nullable String check(DatumFilterService filter, FilterContext context) {
		if ( !(filter instanceof ControlUpdaterDatumFilterService f) ) {
			return null;
		}
		final ControlConfig[] configs = f.getControlConfigs();
		if ( configs == null ) {
			return null;
		}
		final Set<String> available = new LinkedHashSet<>(8);
		for ( NodeControlProvider p : context.services(NodeControlProvider.class) ) {
			available.addAll(p.getAvailableControlIds());
		}
		final Set<String> missing = new LinkedHashSet<>(configs.length);
		for ( ControlConfig config : configs ) {
			final String controlId = (config != null ? config.getControlId() : null);
			if ( controlId == null || controlId.isBlank() || controlId.contains("{") ) {
				continue;
			}
			if ( !available.contains(controlId) ) {
				missing.add(controlId);
			}
		}
		if ( missing.isEmpty() ) {
			return null;
		}
		final String ids = String.join(", ", missing);
		return (missing.size() == 1
				? "The control %s is not one of the simulation's Controls, so setting it fails, as on a node without that control. Add it to the Controls to set it."
				: "The controls %s are not among the simulation's Controls, so setting them fails, as on a node without those controls. Add them to the Controls to set them.")
						.formatted(ids);
	}

}
