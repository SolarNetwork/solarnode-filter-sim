/* ==================================================================
 * ConductorCheck.java - 25 Oct 2026 11:48:03 am
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
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import net.solarnetwork.node.control.conductor.ControlConductor;
import net.solarnetwork.node.datum.filter.instr.InstructionConfig;
import net.solarnetwork.node.datum.filter.instr.InstructorConfig;
import net.solarnetwork.node.datum.filter.instr.InstructorDatumFilterService;
import net.solarnetwork.node.reactor.InstructionHandler;
import net.solarnetwork.node.service.support.ExpressionConfig;
import net.solarnetwork.service.DatumFilterService;
import s10k.node.sim.filter.domain.SimulationMessage;

/**
 * Check that the Control Conductors the Instructor filter's
 * {@code OrchestrateControls} instructions name exist.
 *
 * <p>
 * A {@code service} parameter that is an expression is evaluated for each
 * datum, so it is not checked.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class ConductorCheck implements FilterCheck {

	@Override
	public @Nullable String check(DatumFilterService filter, FilterContext context) {
		if ( !(filter instanceof InstructorDatumFilterService f) ) {
			return null;
		}
		final InstructorConfig[] configs = f.getInstructorConfigs();
		if ( configs == null ) {
			return null;
		}
		final Set<String> available = new LinkedHashSet<>(4);
		for ( ControlConductor c : context.services(ControlConductor.class) ) {
			final String uid = c.getUid();
			if ( uid != null ) {
				available.add(uid.toLowerCase(Locale.ROOT));
			}
		}
		final Set<String> missing = new LinkedHashSet<>(2);
		boolean noService = false;
		for ( InstructorConfig config : configs ) {
			final InstructionConfig[] instructions = (config != null ? config.getInstructions() : null);
			if ( instructions == null ) {
				continue;
			}
			for ( InstructionConfig instruction : instructions ) {
				if ( instruction == null || !ControlConductor.TOPIC_ORCHESTRATE_CONTROLS
						.equals(instruction.getTopic()) ) {
					continue;
				}
				final ExpressionConfig service = serviceParameter(instruction);
				if ( service == null ) {
					noService = true;
				} else if ( service.getExpressionServiceId() == null
						|| service.getExpressionServiceId().isBlank() ) {
					final String name = service.getExpression();
					if ( name != null && !available.contains(name.toLowerCase(Locale.ROOT)) ) {
						missing.add(name);
					}
				}
			}
		}
		if ( !missing.isEmpty() ) {
			return (missing.size() == 1
					? "An OrchestrateControls instruction names the Control Conductor %s, which is not one of the simulation's Control Conductors, so nothing handles it, as on a node without that conductor. Add it to the Control Conductors to run its tasks."
					: "OrchestrateControls instructions name the Control Conductors %s, which are not among the simulation's Control Conductors, so nothing handles them, as on a node without those conductors. Add them to the Control Conductors to run their tasks.")
							.formatted(SimulationMessage.quoted(missing));
		}
		if ( noService ) {
			return "An OrchestrateControls instruction has no service parameter, so no Control Conductor handles it. Add a service parameter with the Service Name of a Control Conductor.";
		}
		return null;
	}

	private static @Nullable ExpressionConfig serviceParameter(InstructionConfig instruction) {
		final ExpressionConfig[] params = instruction.getParameters();
		if ( params != null ) {
			for ( ExpressionConfig p : params ) {
				if ( p != null && InstructionHandler.PARAM_SERVICE.equals(p.getName())
						&& p.getExpression() != null && !p.getExpression().isEmpty() ) {
					return p;
				}
			}
		}
		return null;
	}

}
