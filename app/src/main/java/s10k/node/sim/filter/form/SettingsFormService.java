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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import org.springframework.context.MessageSource;
import s10k.node.sim.filter.domain.SettingField;
import s10k.node.sim.filter.domain.SettingProblem;
import s10k.node.sim.filter.domain.SettingsForm;
import s10k.node.sim.filter.domain.SettingsFormRequest;
import s10k.node.sim.filter.domain.SimulationMessage;
import s10k.node.sim.filter.registry.ContextClassLoader;
import s10k.node.sim.filter.registry.DatumSourceTypeDefinition;
import s10k.node.sim.filter.registry.DatumSourceTypeRegistry;
import s10k.node.sim.filter.registry.FilterTypeDefinition;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.settings.SettingsApplier;
import s10k.node.sim.filter.sim.SimDatumSources;
import s10k.node.sim.filter.sim.SimOpModeInvokers;
import s10k.node.sim.filter.sim.SimulationContext;
import s10k.node.sim.filter.sim.SimulationException;
import net.solarnetwork.domain.datum.GeneralDatumMetadata;
import net.solarnetwork.node.datum.opmode.DatumDataSourceOpModeInvoker;
import net.solarnetwork.node.domain.datum.NodeDatum;
import net.solarnetwork.node.job.SimpleManagedJob;
import net.solarnetwork.node.service.NodeControlProvider;
import net.solarnetwork.service.DatumFilterService;
import net.solarnetwork.service.ExpressionService;
import net.solarnetwork.settings.CronExpressionSettingSpecifier;
import net.solarnetwork.settings.SettingSpecifier;
import net.solarnetwork.settings.support.BasicCronExpressionSettingSpecifier;
import net.solarnetwork.settings.SettingSpecifierProvider;

/**
 * Service for filter, datum source, and Operational Mode Datum Source Invoker
 * settings forms.
 *
 * <p>
 * A form is rendered from the setting specifiers of a new filter, with the
 * settings applied, like the SolarNode setup UI renders the settings of a
 * filter. The filter is not started.
 * </p>
 *
 * <p>
 * A datum source's form is rendered from the setting specifiers of the job that
 * polls it on a node, created as a simulation creates it, with the job's
 * message source. The job is not scheduled.
 * </p>
 *
 * <p>
 * An invoker's form is rendered from the setting specifiers of a new invoker,
 * with the settings applied. The invoker is not started.
 * </p>
 *
 * @author matt
 * @version 1.3
 */
public class SettingsFormService {

	/**
	 * The keys of the runtime status titles the datum sources show, with the
	 * job's key prefixes.
	 */
	private static final Set<String> DATUM_SOURCE_STATUS_KEYS = Set.of(
			DatumSourceTypeDefinition.SETTING_PREFIX + "status",
			DatumSourceTypeDefinition.SETTING_PREFIX + "datumStatus",
			DatumSourceTypeDefinition.SETTING_PREFIX + "touStatus",
			DatumSourceTypeDefinition.MULTI_SETTING_PREFIX + "status",
			DatumSourceTypeDefinition.MULTI_SETTING_PREFIX + "datumStatus");

	/** The key of the runtime status title the invoker shows. */
	private static final Set<String> INVOKER_STATUS_KEYS = Set.of("status");

	private final FilterTypeRegistry registry;
	private final DatumSourceTypeRegistry datumSourceRegistry;
	private final ExpressionService expressionService;

	/**
	 * Constructor, with the standard datum source types.
	 *
	 * @param registry
	 *        the filter type registry
	 * @param expressionService
	 *        the expression service filters use
	 */
	public SettingsFormService(FilterTypeRegistry registry, ExpressionService expressionService) {
		this(registry, DatumSourceTypeRegistry.standard(), expressionService);
	}

	/**
	 * Constructor.
	 *
	 * @param registry
	 *        the filter type registry
	 * @param datumSourceRegistry
	 *        the datum source type registry
	 * @param expressionService
	 *        the expression service filters use
	 */
	public SettingsFormService(FilterTypeRegistry registry, DatumSourceTypeRegistry datumSourceRegistry,
			ExpressionService expressionService) {
		super();
		this.registry = registry;
		this.datumSourceRegistry = datumSourceRegistry;
		this.expressionService = expressionService;
	}

	/**
	 * Get the settings form of a filter or datum source.
	 *
	 * @param request
	 *        the request
	 * @param locale
	 *        the locale to use for the form text
	 * @return the form
	 * @throws SimulationException
	 *         if the request is not valid, or the type is not supported
	 */
	public SettingsForm form(SettingsFormRequest request, Locale locale) {
		final String factoryUid = request.factoryUid();
		if ( factoryUid == null || factoryUid.isBlank() ) {
			throw new SimulationException("The factoryUid is required.");
		}
		if ( SimOpModeInvokers.FACTORY_UID.equals(factoryUid) ) {
			return invokerForm(request, locale);
		}
		final FilterTypeDefinition def = registry.definition(factoryUid);
		if ( def == null ) {
			final DatumSourceTypeDefinition source = datumSourceRegistry.definition(factoryUid);
			if ( source != null ) {
				return datumSourceForm(source, request, locale);
			}
			throw new SimulationException("The filter type %s is not supported.".formatted(factoryUid));
		}
		final Map<String, String> settings = settings(request.settingsOrEmpty());

		final SimulationContext context = newContext();
		final DatumFilterService filter = def.factory().create(context);
		context.injectServices(filter, def.messageSource());
		final List<SettingProblem> problems = SettingsApplier.apply(filter, settings).stream()
				.map(p -> problem(p, "filter")).toList();

		final List<SettingSpecifier> specifiers = (filter instanceof SettingSpecifierProvider p
				? p.getSettingSpecifiers()
				: List.of());
		final List<SettingField> fields = new SettingFieldRenderer(def.messageSource(), settings, locale,
				def.isChain()).render(specifiers != null ? specifiers : List.of());
		return new SettingsForm(factoryUid, fields, problems, def.limitations(settings));
	}

	private SettingsForm datumSourceForm(DatumSourceTypeDefinition def, SettingsFormRequest request,
			Locale locale) {
		// a custom datum source's code runs with its bundle's class loader
		return ContextClassLoader.call(def.classLoader(),
				() -> createDatumSourceForm(def, request, locale));
	}

	private SettingsForm createDatumSourceForm(DatumSourceTypeDefinition def,
			SettingsFormRequest request, Locale locale) {
		final Map<String, String> settings = settings(request.settingsOrEmpty());
		final SimulationContext context = newContext();
		final SimDatumSources.Source source = context.createDatumSource(def, def.factoryUid(),
				def.displayName(), new SplittableRandom(0L));
		final SimpleManagedJob job = source.job();
		final List<SettingProblem> problems = SettingsApplier.apply(job, settings).stream()
				.map(p -> problem(p, "datum source")).toList();

		// the job's schedule setting has the current schedule as its default,
		// so give it the blueprint's schedule, which a reset goes back to
		final List<SettingSpecifier> specifiers = new ArrayList<>();
		for ( SettingSpecifier spec : job.getSettingSpecifiers() ) {
			if ( spec instanceof CronExpressionSettingSpecifier cron
					&& job.getScheduleSettingKey().equals(cron.getKey()) ) {
				specifiers.add(
						new BasicCronExpressionSettingSpecifier(cron.getKey(), def.defaultSchedule()));
			} else {
				specifiers.add(spec);
			}
		}
		final MessageSource messageSource = job.getMessageSource();
		final List<SettingField> fields = new SettingFieldRenderer(
				messageSource != null ? messageSource : def.messageSource(), settings, locale, false,
				DATUM_SOURCE_STATUS_KEYS).render(specifiers);
		final List<String> controlIds = (source.datumSource() instanceof NodeControlProvider p
				? controlIds(p)
				: null);
		return new SettingsForm(def.factoryUid(), fields, problems, def.limitations(settings),
				controlIds);
	}

	private SettingsForm invokerForm(SettingsFormRequest request, Locale locale) {
		final Map<String, String> settings = settings(request.settingsOrEmpty());
		final SimulationContext context = newContext();
		final DatumDataSourceOpModeInvoker invoker = context
				.createInvoker(SimOpModeInvokers.FACTORY_UID, SimOpModeInvokers.FACTORY_UID).invoker();
		final List<SettingProblem> problems = SettingsApplier.apply(invoker, settings).stream()
				.map(p -> problem(p, "invoker")).toList();
		final List<SettingField> fields = new SettingFieldRenderer(SimOpModeInvokers.MESSAGE_SOURCE,
				settings, locale, false, INVOKER_STATUS_KEYS).render(invoker.getSettingSpecifiers());
		return new SettingsForm(SimOpModeInvokers.FACTORY_UID, fields, problems, null);
	}

	private static List<String> controlIds(NodeControlProvider provider) {
		final List<String> ids = provider.getAvailableControlIds();
		if ( ids == null ) {
			return List.of();
		}
		return ids.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
	}

	private SimulationContext newContext() {
		return new SimulationContext(Instant.now(), expressionService, SettingsFormService::rejectDatum,
				new GeneralDatumMetadata());
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

	private static SettingProblem problem(SettingsApplier.Problem p, String subject) {
		if ( p.unknown() ) {
			return new SettingProblem(p.key(), SimulationMessage.CODE_UNKNOWN_SETTING,
					"The %s has no setting %s.".formatted(subject, p.key()));
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
