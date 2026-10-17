/* ==================================================================
 * SettingsFormService.java - 18 Oct 2026 12:25:01 pm
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

package s10k.node.sim.filter.form;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import s10k.node.sim.filter.domain.SettingField;
import s10k.node.sim.filter.domain.SettingProblem;
import s10k.node.sim.filter.domain.SettingsForm;
import s10k.node.sim.filter.domain.SettingsFormRequest;
import s10k.node.sim.filter.domain.SimulationMessage;
import s10k.node.sim.filter.registry.FilterTypeDefinition;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.settings.SettingsApplier;
import s10k.node.sim.filter.sim.SimulationContext;
import s10k.node.sim.filter.sim.SimulationException;
import net.solarnetwork.node.domain.datum.NodeDatum;
import net.solarnetwork.service.DatumFilterService;
import net.solarnetwork.service.ExpressionService;
import net.solarnetwork.settings.SettingSpecifier;
import net.solarnetwork.settings.SettingSpecifierProvider;

/**
 * Service for filter settings forms.
 *
 * <p>
 * A form is rendered from the setting specifiers of a new filter, with the
 * settings applied, like the SolarNode setup UI renders the settings of a
 * filter. The filter is not started.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class SettingsFormService {

	private final FilterTypeRegistry registry;
	private final ExpressionService expressionService;

	/**
	 * Constructor.
	 *
	 * @param registry
	 *        the filter type registry
	 * @param expressionService
	 *        the expression service filters use
	 */
	public SettingsFormService(FilterTypeRegistry registry, ExpressionService expressionService) {
		super();
		this.registry = registry;
		this.expressionService = expressionService;
	}

	/**
	 * Get the settings form of a filter.
	 *
	 * @param request
	 *        the request
	 * @param locale
	 *        the locale to use for the form text
	 * @return the form
	 * @throws SimulationException
	 *         if the request is not valid, or the filter type is not supported
	 */
	public SettingsForm form(SettingsFormRequest request, Locale locale) {
		final String factoryUid = request.factoryUid();
		if ( factoryUid == null || factoryUid.isBlank() ) {
			throw new SimulationException("The factoryUid is required.");
		}
		final FilterTypeDefinition def = registry.definition(factoryUid);
		if ( def == null ) {
			throw new SimulationException("The filter type %s is not supported.".formatted(factoryUid));
		}
		final Map<String, String> settings = settings(request.settingsOrEmpty());

		final SimulationContext context = new SimulationContext(Instant.now(), expressionService,
				SettingsFormService::rejectDatum);
		final DatumFilterService filter = def.factory().create(context);
		context.injectServices(filter, def.messageSource());
		final List<SettingProblem> problems = SettingsApplier.apply(filter, settings).stream()
				.map(SettingsFormService::problem).toList();

		final List<SettingSpecifier> specifiers = (filter instanceof SettingSpecifierProvider p
				? p.getSettingSpecifiers()
				: List.of());
		final List<SettingField> fields = new SettingFieldRenderer(def.messageSource(), settings, locale,
				def.isChain()).render(specifiers != null ? specifiers : List.of());
		return new SettingsForm(factoryUid, fields, problems, def.limitations(settings));
	}

	private static Map<String, String> settings(Map<String, String> settings) {
		final Map<String, String> result = new LinkedHashMap<>(settings.size());
		for ( Map.Entry<String, String> e : settings.entrySet() ) {
			if ( e.getKey() != null && e.getValue() != null ) {
				result.put(e.getKey(), e.getValue());
			}
		}
		return result;
	}

	private static SettingProblem problem(SettingsApplier.Problem p) {
		if ( p.unknown() ) {
			return new SettingProblem(p.key(), SimulationMessage.CODE_UNKNOWN_SETTING,
					"The filter has no setting %s.".formatted(p.key()));
		}
		return new SettingProblem(p.key(), SimulationMessage.CODE_INVALID_SETTING,
				"The setting %s could not be applied: %s".formatted(p.key(), p.message()));
	}

	/**
	 * The datum queue of a form's filter, which never processes datum.
	 *
	 * @param datum
	 *        the datum
	 * @param persist
	 *        {@code true} to persist the datum
	 * @return {@code false}
	 */
	private static boolean rejectDatum(NodeDatum datum, boolean persist) {
		return false;
	}

}
