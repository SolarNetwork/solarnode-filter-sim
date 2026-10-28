/* ==================================================================
 * ServiceBindings.java - 21 Oct 2026 4:12:08 pm
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

package s10k.node.sim.filter.custom;

import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import s10k.node.sim.filter.blueprint.ServiceKey;
import s10k.node.sim.filter.registry.FilterContext;
import s10k.node.sim.filter.sim.SimServiceTracker;
import net.solarnetwork.domain.tariff.TemporalTariffEvaluator;
import net.solarnetwork.node.service.MetadataService;
import net.solarnetwork.node.service.TariffScheduleProvider;
import net.solarnetwork.service.OptionalService;

/**
 * The simulated services a custom filter's blueprint can refer to.
 *
 * <p>
 * A blueprint gets a service in one of three ways (see
 * {@link ServiceKey.Source}): a {@code <reference>} is the service itself, a
 * {@code <reference-list>} is a list of services, and a
 * {@code DynamicServiceTracker} is an optional service. Only the services that
 * {@link FilterContext} has are available.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class ServiceBindings {

	/** The {@code ObjectMapperService} service interface. */
	public static final String OBJECT_MAPPER_SERVICE = "net.solarnetwork.service.ObjectMapperService";

	/** The {@code ObjectMapperService} method that gets its object mapper. */
	public static final String GET_OBJECT_MAPPER = "getObjectMapper";

	/** The service filter of the node's own instance of a service. */
	public static final String NODE_FUNCTION_FILTER = "(function=node)";

	private ServiceBindings() {
		// not available
	}

	/**
	 * Get how to get a service from the simulated services.
	 *
	 * @param key
	 *        the service
	 * @return a function that gets the service, or {@code null} if the
	 *         simulator does not provide it
	 */
	public static @Nullable Function<FilterContext, @Nullable Object> service(ServiceKey key) {
		return switch (key.serviceClassName()) {
			case "net.solarnetwork.node.service.DatumQueue" -> optional(key, FilterContext::datumQueue);
			case "net.solarnetwork.node.service.DatumMetadataService" -> optional(key,
					FilterContext::datumMetadataService);
			case "net.solarnetwork.node.reactor.InstructionExecutionService" -> optional(key,
					FilterContext::instructionExecutionService);
			case "net.solarnetwork.node.reactor.ReactorService" -> optional(key,
					FilterContext::reactorService);
			case "org.springframework.scheduling.TaskScheduler" -> instance(key,
					FilterContext::taskScheduler);
			case "java.util.concurrent.Executor", "org.springframework.core.task.TaskExecutor" -> instance(
					key, FilterContext::executor);
			case "net.solarnetwork.node.dao.TransientSettingDao" -> instance(key,
					FilterContext::transientSettingDao);
			case "net.solarnetwork.node.metrics.dao.MetricDao" -> instance(key,
					FilterContext::metricDao);
			case "net.solarnetwork.service.DatumFilterService" -> (key
					.source() == ServiceKey.Source.ReferenceList ? FilterContext::filterServices : null);
			case "net.solarnetwork.node.service.MetadataService" -> metadataService(key);
			case "net.solarnetwork.domain.tariff.TemporalTariffEvaluator" -> tracked(key,
					TemporalTariffEvaluator.class);
			case "net.solarnetwork.node.service.TariffScheduleProvider" -> tracked(key,
					TariffScheduleProvider.class);
			default -> null;
		};
	}

	/**
	 * Get how to get the result of calling a method on a service, like a bean
	 * with a {@code factory-ref} and {@code factory-method}.
	 *
	 * @param key
	 *        the service
	 * @param method
	 *        the method
	 * @return a function that gets the result, or {@code null} if the simulator
	 *         does not provide it
	 */
	public static @Nullable Function<FilterContext, @Nullable Object> serviceMethod(ServiceKey key,
			String method) {
		if ( OBJECT_MAPPER_SERVICE.equals(key.serviceClassName()) && GET_OBJECT_MAPPER.equals(method)
				&& (key.filter() == null || NODE_FUNCTION_FILTER.equals(key.filter())) ) {
			return FilterContext::nodeObjectMapper;
		}
		return null;
	}

	/** A service that the context provides as an optional service. */
	private static Function<FilterContext, @Nullable Object> optional(ServiceKey key,
			Function<FilterContext, OptionalService<?>> provider) {
		return instance(key, ctx -> provider.apply(ctx).service());
	}

	/** A service that the context provides itself. */
	private static Function<FilterContext, @Nullable Object> instance(ServiceKey key,
			Function<FilterContext, @Nullable Object> provider) {
		return switch (key.source()) {
			case Reference -> provider;
			case ReferenceList -> ctx -> {
				final Object service = provider.apply(ctx);
				return (service != null ? List.of(service) : List.of());
			};
			case Tracker -> ctx -> {
				final Object service = provider.apply(ctx);
				return new SimServiceTracker<>(Object.class,
						(service != null ? List.of(service) : List.of()),
						key.ignoreEmptyPropertyFilterValues());
			};
		};
	}

	/**
	 * A service found in the context's service registry, which only a tracker
	 * or a list can get, as the registry may have none.
	 */
	private static <T> @Nullable Function<FilterContext, @Nullable Object> tracked(ServiceKey key,
			Class<T> serviceClass) {
		return switch (key.source()) {
			case Reference -> null;
			case ReferenceList -> ctx -> ctx.services(serviceClass);
			case Tracker -> ctx -> ctx.serviceTracker(serviceClass,
					key.ignoreEmptyPropertyFilterValues());
		};
	}

	/**
	 * The metadata services: the Node Metadata Service always exists, and is
	 * the first one registered, and the User Metadata Services follow it.
	 */
	private static @Nullable Function<FilterContext, @Nullable Object> metadataService(ServiceKey key) {
		if ( NODE_FUNCTION_FILTER.equals(key.filter()) ) {
			return instance(key, ctx -> ctx.services(MetadataService.class).getFirst());
		}
		if ( key.source() == ServiceKey.Source.Reference ) {
			return (key.filter() == null ? ctx -> ctx.services(MetadataService.class).getFirst() : null);
		}
		return tracked(key, MetadataService.class);
	}

	/**
	 * Get the service interface, for messages about filter types that need a
	 * service the simulator does not provide.
	 *
	 * @param key
	 *        the service
	 * @return the description
	 */
	static String describe(ServiceKey key) {
		return (key.filter() != null ? key.serviceClassName() + " " + key.filter()
				: key.serviceClassName());
	}

}
