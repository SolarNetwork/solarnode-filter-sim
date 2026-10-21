/* ==================================================================
 * FilterTypeDefinition.java - 18 Oct 2026 8:56:20 am
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

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import s10k.node.sim.filter.domain.FilterCapability;
import s10k.node.sim.filter.domain.FilterTypeInfo;
import net.solarnetwork.service.DatumFilterService;

/**
 * The definition of a supported filter type, ported from its SolarNode
 * blueprint wiring.
 *
 * <p>
 * SolarNode creates a filter instance, injects its services, applies its
 * settings, and then calls its init method (see Gemini Blueprint's
 * {@code ManagedServiceFactoryFactoryBean}). The update method
 * ({@code configurationChanged}) is only called when settings change later, so
 * the simulator does not call it.
 * </p>
 *
 * @param factoryUid
 *        the SolarNode component factory UID
 * @param displayName
 *        the display name to use if the message source has no {@code title}
 * @param filterClass
 *        the filter implementation class
 * @param messageSource
 *        the filter's message source, used for its settings and its title and
 *        description
 * @param factory
 *        the factory that creates new filter instances
 * @param init
 *        the init method to call after settings have been applied, or
 *        {@code null}
 * @param destroy
 *        the destroy method to call when the simulation finishes, or
 *        {@code null}
 * @param capabilities
 *        the filter capabilities
 * @param limitations
 *        a function that returns a description of how the simulation will
 *        differ from SolarNode for a given set of settings, or {@code null} if
 *        there are no differences; or {@code null} if the filter has no known
 *        limitations
 * @param check
 *        a check for configuration problems that stop the filter from working,
 *        or {@code null}
 * @param origin
 *        the bundle a custom filter type comes from, or {@code null} for the
 *        standard filter types
 * @author matt
 * @version 1.0
 */
public record FilterTypeDefinition(String factoryUid, String displayName,
		Class<? extends DatumFilterService> filterClass, MessageSource messageSource,
		FilterFactory factory, @Nullable Consumer<DatumFilterService> init,
		@Nullable Consumer<DatumFilterService> destroy, Set<FilterCapability> capabilities,
		@Nullable Function<Map<String, String>, @Nullable String> limitations,
		@Nullable FilterCheck check, @Nullable FilterTypeOrigin origin) {

	/** The factory UID of a SolarNode user filter chain. */
	public static final String CHAIN_FACTORY_UID = "net.solarnetwork.node.datum.xform.user";

	/**
	 * Test if this is the filter chain type.
	 *
	 * @return {@code true} if this definition is for a filter chain
	 */
	public boolean isChain() {
		return CHAIN_FACTORY_UID.equals(factoryUid);
	}

	/**
	 * Get the filter type information.
	 *
	 * @param locale
	 *        the locale to use
	 * @return the information
	 */
	public FilterTypeInfo info(Locale locale) {
		final String title = messageSource.getMessage("title", null, null, locale);
		return new FilterTypeInfo(factoryUid, title != null ? title : displayName,
				messageSource.getMessage("desc", null, null, locale), isChain(), capabilities);
	}

	/**
	 * Get a description of how the simulation of a filter will differ from
	 * SolarNode.
	 *
	 * @param settings
	 *        the filter settings
	 * @return the description, or {@code null} if there are no known
	 *         differences
	 */
	public @Nullable String limitations(Map<String, String> settings) {
		return (limitations != null ? limitations.apply(settings) : null);
	}

	/**
	 * Check a filter for a configuration problem that stops it from working.
	 *
	 * @param filter
	 *        the filter, after its settings have been applied and it has been
	 *        started
	 * @param context
	 *        the simulated services
	 * @return a description of the problem, or {@code null} if there is none
	 */
	public @Nullable String check(DatumFilterService filter, FilterContext context) {
		return (check != null ? check.check(filter, context) : null);
	}

	/**
	 * Get the class loader to run the filter with, as its thread context class
	 * loader.
	 *
	 * @return the class loader of a custom filter type, or {@code null} for the
	 *         standard filter types
	 */
	public @Nullable ClassLoader classLoader() {
		return (origin != null ? origin.classLoader() : null);
	}

	/**
	 * Copy this definition, with a check.
	 *
	 * @param check
	 *        the check
	 * @return the new definition
	 */
	public FilterTypeDefinition withCheck(FilterCheck check) {
		return new FilterTypeDefinition(factoryUid, displayName, filterClass, messageSource, factory,
				init, destroy, capabilities, limitations, check, origin);
	}

}
