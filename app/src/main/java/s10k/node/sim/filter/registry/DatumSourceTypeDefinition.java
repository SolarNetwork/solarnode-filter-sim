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

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import net.solarnetwork.node.reactor.InstructionHandler;
import net.solarnetwork.node.service.DatumDataSource;

/**
 * The definition of a supported SolarNode datum source type, ported from its
 * bundle's blueprint.
 *
 * <p>
 * On a node, each datum source is wrapped in a
 * {@code DatumDataSourcePollManagedJob} that polls it, which is wrapped in a
 * {@code SimpleManagedJob} with the poll schedule, so the datum source's
 * setting keys start with {@link #SETTING_PREFIX}.
 * </p>
 *
 * @param factoryUid
 *        the SolarNode factory UID, which is also the component's setting UID
 * @param displayName
 *        the blueprint's display name
 * @param sourceClass
 *        the datum source class
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
 *        gives a datum source's Source ID setting
 * @param limitations
 *        gives a description of how the simulation differs from SolarNode for
 *        the given settings, or {@code null} if it does not
 * @param check
 *        a check of a datum source's configuration, or {@code null}
 * @author matt
 * @version 1.0
 */
public record DatumSourceTypeDefinition(String factoryUid, String displayName,
		Class<? extends DatumDataSource> sourceClass, MessageSource messageSource,
		DatumSourceFactory factory, String defaultSchedule, List<Class<?>> services,
		Function<DatumDataSource, @Nullable String> sourceId,
		@Nullable Function<Map<String, String>, @Nullable String> limitations,
		@Nullable DatumSourceCheck check) {

	/** The setting key prefix of the datum source's own settings. */
	public static final String SETTING_PREFIX = "jobService.datumDataSource.";

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
	public @Nullable String check(DatumDataSource source, DatumSourceContext context) {
		return (check != null ? check.check(source, context) : null);
	}

}
