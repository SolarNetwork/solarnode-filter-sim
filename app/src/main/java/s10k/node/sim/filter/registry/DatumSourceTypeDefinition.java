/* ==================================================================
 * DatumSourceTypeDefinition.java - 29 Oct 2026 6:53:00 pm
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

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import s10k.node.sim.filter.domain.DatumSourceCapability;
import s10k.node.sim.filter.domain.DatumSourceTypeInfo;
import net.solarnetwork.node.reactor.InstructionHandler;
import net.solarnetwork.node.service.DatumDataSource;
import net.solarnetwork.node.service.DatumSourceIdProvider;
import net.solarnetwork.node.service.MultiDatumDataSource;
import net.solarnetwork.node.service.NodeControlProvider;
import net.solarnetwork.node.service.TariffScheduleProvider;

/**
 * The definition of a supported SolarNode datum source type, ported from its
 * bundle's blueprint, or defined from a custom bundle's blueprint.
 *
 * <p>
 * On a node, each datum source is wrapped in a
 * {@code DatumDataSourcePollManagedJob} that polls it, which is wrapped in a
 * {@code SimpleManagedJob} with the poll schedule, so the datum source's
 * setting keys start with {@link #SETTING_PREFIX}, or for a
 * {@link MultiDatumDataSource} {@link #MULTI_SETTING_PREFIX} (see
 * {@link #settingPrefix()}).
 * </p>
 *
 * @param factoryUid
 *        the SolarNode factory UID, which is also the component's setting UID
 * @param displayName
 *        the blueprint's display name
 * @param sourceClass
 *        the datum source class, a {@link DatumDataSource}, or a
 *        {@link MultiDatumDataSource} if {@code multi} is {@code true}
 * @param messageSource
 *        the datum source's message source, without the job's key prefixes
 * @param factory
 *        creates a datum source with the services its blueprint gives it
 * @param defaultSchedule
 *        the poll schedule the blueprint gives the job
 * @param services
 *        the interfaces the blueprint publishes the datum source as, such as
 *        {@link DatumDataSource} and {@link InstructionHandler}
 * @param sourceId
 *        gives a datum source's Source ID setting, or {@code null} if the type
 *        has no Source ID setting
 * @param limitations
 *        gives a description of how the simulation differs from SolarNode for
 *        the given settings, or {@code null} if it does not
 * @param check
 *        a check of a datum source's configuration, or {@code null}
 * @param origin
 *        the bundle of a custom datum source type, or {@code null} for a
 *        standard one
 * @param multi
 *        {@code true} if the poll job polls the datum source as a
 *        {@link MultiDatumDataSource}, its {@code multiDatumDataSource}
 *        property, rather than as a {@link DatumDataSource}
 * @author matt
 * @version 1.1
 */
public record DatumSourceTypeDefinition(String factoryUid, String displayName,
		Class<? extends DatumSourceIdProvider> sourceClass, MessageSource messageSource,
		DatumSourceFactory factory, String defaultSchedule, List<Class<?>> services,
		@Nullable Function<DatumSourceIdProvider, @Nullable String> sourceId,
		@Nullable Function<Map<String, String>, @Nullable String> limitations,
		@Nullable DatumSourceCheck check, @Nullable FilterTypeOrigin origin, boolean multi) {

	/** The setting key prefix of a datum source's own settings. */
	public static final String SETTING_PREFIX = "jobService.datumDataSource.";

	/** The setting key prefix of a multi datum source's own settings. */
	public static final String MULTI_SETTING_PREFIX = "jobService.multiDatumDataSource.";

	/**
	 * Constructor for a {@link DatumDataSource} type.
	 *
	 * @param factoryUid
	 *        the SolarNode factory UID
	 * @param displayName
	 *        the blueprint's display name
	 * @param sourceClass
	 *        the datum source class
	 * @param messageSource
	 *        the datum source's message source
	 * @param factory
	 *        creates a datum source
	 * @param defaultSchedule
	 *        the poll schedule the blueprint gives the job
	 * @param services
	 *        the interfaces the blueprint publishes the datum source as
	 * @param sourceId
	 *        gives a datum source's Source ID setting, or {@code null}
	 * @param limitations
	 *        gives a description of how the simulation differs from SolarNode,
	 *        or {@code null}
	 * @param check
	 *        a check of a datum source's configuration, or {@code null}
	 * @param origin
	 *        the bundle of a custom datum source type, or {@code null}
	 */
	public DatumSourceTypeDefinition(String factoryUid, String displayName,
			Class<? extends DatumSourceIdProvider> sourceClass, MessageSource messageSource,
			DatumSourceFactory factory, String defaultSchedule, List<Class<?>> services,
			@Nullable Function<DatumSourceIdProvider, @Nullable String> sourceId,
			@Nullable Function<Map<String, String>, @Nullable String> limitations,
			@Nullable DatumSourceCheck check, @Nullable FilterTypeOrigin origin) {
		this(factoryUid, displayName, sourceClass, messageSource, factory, defaultSchedule, services,
				sourceId, limitations, check, origin, false);
	}

	/**
	 * Get the setting key prefix of the datum source's own settings.
	 *
	 * @return {@link #MULTI_SETTING_PREFIX} for a multi datum source, else
	 *         {@link #SETTING_PREFIX}
	 */
	public String settingPrefix() {
		return (multi ? MULTI_SETTING_PREFIX : SETTING_PREFIX);
	}

	/**
	 * Test if the datum source handles instructions.
	 *
	 * @return {@code true} if its blueprint publishes it as an
	 *         {@link InstructionHandler}
	 */
	public boolean isInstructionHandler() {
		return services.contains(InstructionHandler.class);
	}

	/**
	 * Get the name of the datum source type: its message source's title, like
	 * the node's settings page shows, else its display name.
	 *
	 * @param locale
	 *        the locale to use
	 * @return the name
	 */
	public String name(Locale locale) {
		final String title = messageSource.getMessage("title", null, null, locale);
		return (title != null ? title : displayName);
	}

	/**
	 * Get information about the datum source type.
	 *
	 * @param locale
	 *        the locale to use for the name and description
	 * @return the information
	 */
	public DatumSourceTypeInfo info(Locale locale) {
		final Set<DatumSourceCapability> capabilities = EnumSet.noneOf(DatumSourceCapability.class);
		if ( services.contains(NodeControlProvider.class) ) {
			capabilities.add(DatumSourceCapability.Controls);
		}
		if ( isInstructionHandler() ) {
			capabilities.add(DatumSourceCapability.Instructions);
		}
		if ( services.contains(TariffScheduleProvider.class) ) {
			capabilities.add(DatumSourceCapability.TariffSchedule);
		}
		return new DatumSourceTypeInfo(factoryUid, name(locale),
				messageSource.getMessage("desc", null, null, locale), defaultSchedule, capabilities,
				origin != null ? origin.info() : null);
	}

	/**
	 * Run code of the datum source, with a custom datum source type's class
	 * loader as the thread context class loader.
	 *
	 * @param task
	 *        the code to run
	 */
	public void run(Runnable task) {
		ContextClassLoader.run(classLoader(), task);
	}

	/**
	 * Get the class loader of a custom datum source type.
	 *
	 * @return the class loader, or {@code null} for a standard type
	 */
	public @Nullable ClassLoader classLoader() {
		return (origin != null ? origin.classLoader() : null);
	}

	/**
	 * Get a description of how the simulation differs from SolarNode.
	 *
	 * @param settings
	 *        the datum source's settings, with the job's key prefixes
	 * @return the description, or {@code null} if it does not differ
	 */
	public @Nullable String limitations(Map<String, String> settings) {
		return (limitations != null ? limitations.apply(settings) : null);
	}

	/**
	 * Check a datum source's configuration.
	 *
	 * @param source
	 *        the datum source
	 * @param context
	 *        the simulated services
	 * @return the problem, or {@code null} if there is none
	 */
	public @Nullable String check(DatumSourceIdProvider source, DatumSourceContext context) {
		return (check != null ? check.check(source, context) : null);
	}

}
