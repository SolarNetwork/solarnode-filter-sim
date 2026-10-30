/* ==================================================================
 * FilterBlueprintInterpreter.java - 21 Oct 2026 2:05:41 pm
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

package s10k.node.sim.filter.blueprint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;
import s10k.node.sim.filter.blueprint.Blueprint.Bean;
import s10k.node.sim.filter.blueprint.Blueprint.BeanValue;
import s10k.node.sim.filter.blueprint.Blueprint.ListValue;
import s10k.node.sim.filter.blueprint.Blueprint.Literal;
import s10k.node.sim.filter.blueprint.Blueprint.ManagedServiceFactory;
import s10k.node.sim.filter.blueprint.Blueprint.MapValue;
import s10k.node.sim.filter.blueprint.Blueprint.Ref;
import s10k.node.sim.filter.blueprint.Blueprint.Reference;
import s10k.node.sim.filter.blueprint.Blueprint.ReferenceValue;
import s10k.node.sim.filter.blueprint.Blueprint.Service;
import s10k.node.sim.filter.blueprint.Blueprint.Unsupported;
import s10k.node.sim.filter.blueprint.Blueprint.Value;
import s10k.node.sim.filter.domain.FilterCapability;

/**
 * Describe the user datum filter types and datum source types a bundle's
 * blueprint defines.
 *
 * <p>
 * A user datum filter type is a managed service factory that publishes a
 * {@code DatumFilterService} with the {@code user} role, paired with the
 * {@code SettingSpecifierProviderFactory} service whose factory UID is the
 * managed service factory's PID. Each reference in the filter bean is resolved
 * to a {@link ServiceKey}, a literal, or a message source. Constructs that
 * cannot be resolved are reported as problems.
 * </p>
 *
 * <p>
 * A datum source type is a managed service factory that publishes a
 * {@code ManagedJob}: a {@code SimpleManagedJob} that wraps a
 * {@code DatumDataSourcePollManagedJob} that wraps the datum source, paired
 * with its {@code SettingSpecifierProviderFactory} service in the same way. The
 * datum source bean is resolved like a filter bean. Other managed jobs are
 * ignored.
 * </p>
 *
 * @author matt
 * @version 1.1
 */
public final class FilterBlueprintInterpreter {

	/** The datum filter service interface. */
	public static final String DATUM_FILTER_SERVICE = "net.solarnetwork.service.DatumFilterService";

	/** The setting specifier provider factory service interface. */
	public static final String SETTING_SPECIFIER_PROVIDER_FACTORY = "net.solarnetwork.settings.SettingSpecifierProviderFactory";

	/** The dynamic service tracker class. */
	public static final String DYNAMIC_SERVICE_TRACKER = "net.solarnetwork.common.osgi.service.DynamicServiceTracker";

	/** The resource bundle message source class. */
	public static final String RESOURCE_BUNDLE_MESSAGE_SOURCE = "org.springframework.context.support.ResourceBundleMessageSource";

	/** The prefixed message source class. */
	public static final String PREFIXED_MESSAGE_SOURCE = "net.solarnetwork.support.PrefixedMessageSource";

	/** The service role of user datum filters. */
	public static final String USER_ROLE = "user";

	/**
	 * The managed job service interface, which datum source factories publish.
	 */
	public static final String MANAGED_JOB = "net.solarnetwork.node.job.ManagedJob";

	/** The managed job class, which runs a datum source's poll job. */
	public static final String SIMPLE_MANAGED_JOB = "net.solarnetwork.node.job.SimpleManagedJob";

	/** The poll job class, which polls a datum source. */
	public static final String POLL_MANAGED_JOB = "net.solarnetwork.node.job.DatumDataSourcePollManagedJob";

	/** The poll job property of a datum source. */
	public static final String DATUM_DATA_SOURCE_PROPERTY = "datumDataSource";

	/** The poll job property of a multi datum source. */
	public static final String MULTI_DATUM_DATA_SOURCE_PROPERTY = "multiDatumDataSource";

	private static final String DATUM_FILTER = "Datum filter";

	private static final String DATUM_SOURCE = "Datum source";

	/** The capabilities implied by the services a filter uses. */
	private static final Map<String, FilterCapability> SERVICE_CAPABILITIES = Map.of(
			"net.solarnetwork.node.service.DatumQueue", FilterCapability.GeneratesDatum,
			"org.springframework.scheduling.TaskScheduler", FilterCapability.ScheduledTasks,
			"net.solarnetwork.node.reactor.InstructionExecutionService", FilterCapability.Instructions,
			"net.solarnetwork.node.reactor.ReactorService", FilterCapability.Instructions,
			"net.solarnetwork.node.metrics.dao.MetricDao", FilterCapability.Metrics);

	/**
	 * The result of interpreting a blueprint.
	 *
	 * @param filterTypes
	 *        the user datum filter types
	 * @param datumSourceTypes
	 *        the datum source types
	 * @param problems
	 *        problems that are not specific to one filter or datum source type,
	 *        including types that could not be described
	 */
	public record Interpretation(List<FilterTypeDescriptor> filterTypes,
			List<DatumSourceTypeDescriptor> datumSourceTypes, List<String> problems) {

	}

	private FilterBlueprintInterpreter() {
		// not available
	}

	/**
	 * Describe the user datum filter types and datum source types a blueprint
	 * defines.
	 *
	 * @param blueprint
	 *        the blueprint, merged from all of a bundle's blueprint XML files
	 * @return the result
	 */
	public static Interpretation interpret(Blueprint blueprint) {
		final List<String> problems = new ArrayList<>(blueprint.problems());
		final Map<String, Bean> factoryBeans = factoryBeans(blueprint, problems);
		final List<FilterTypeDescriptor> filterTypes = new ArrayList<>(8);
		final List<DatumSourceTypeDescriptor> datumSourceTypes = new ArrayList<>(2);
		for ( ManagedServiceFactory msf : blueprint.managedServiceFactories() ) {
			if ( msf.interfaces().contains(MANAGED_JOB) ) {
				final DatumSourceTypeDescriptor d = datumSource(blueprint, msf, factoryBeans, problems);
				if ( d != null ) {
					datumSourceTypes.add(d);
				}
				continue;
			}
			if ( !msf.interfaces().contains(DATUM_FILTER_SERVICE)
					|| !roles(msf.serviceProperties()).contains(USER_ROLE) ) {
				continue;
			}
			final String uid = msf.factoryPid();
			if ( uid == null ) {
				problems.add("Datum filter managed service factory without a factory-pid.");
				continue;
			}
			final Bean bean = msf.bean();
			final String className = (bean != null ? bean.className() : null);
			if ( bean == null || className == null ) {
				problems.add("Datum filter [%s] has no bean class.".formatted(uid));
				continue;
			}
			if ( bean.factoryMethod() != null ) {
				problems.add("Datum filter [%s] bean uses factory method [%s], which is not supported."
						.formatted(uid, bean.factoryMethod()));
				continue;
			}
			final Bean factory = factoryBeans.get(uid);
			if ( factory == null ) {
				problems.add("Datum filter [%s] has no %s service.".formatted(uid,
						SETTING_SPECIFIER_PROVIDER_FACTORY));
				continue;
			}
			filterTypes
					.add(new Resolver(blueprint, uid, DATUM_FILTER).describe(className, bean, factory));
		}
		return new Interpretation(List.copyOf(filterTypes), List.copyOf(datumSourceTypes),
				List.copyOf(problems));
	}

	/**
	 * Describe the datum source type of a managed job factory.
	 *
	 * @return the datum source type, or {@code null} if the factory is not a
	 *         datum source poll job, or cannot be described
	 */
	private static @Nullable DatumSourceTypeDescriptor datumSource(Blueprint blueprint,
			ManagedServiceFactory msf, Map<String, Bean> factoryBeans, List<String> problems) {
		final Bean job = msf.bean();
		if ( job == null || !SIMPLE_MANAGED_JOB.equals(job.className()) || job.arguments().isEmpty() ) {
			return null;
		}
		final Bean poll = bean(blueprint, job.arguments().getFirst());
		if ( poll == null || !POLL_MANAGED_JOB.equals(poll.className()) ) {
			return null;
		}
		final String uid = msf.factoryPid();
		if ( uid == null ) {
			problems.add("Datum source managed service factory without a factory-pid.");
			return null;
		}
		Value sourceValue = poll.properties().get(DATUM_DATA_SOURCE_PROPERTY);
		final boolean multi = (sourceValue == null);
		if ( multi ) {
			sourceValue = poll.properties().get(MULTI_DATUM_DATA_SOURCE_PROPERTY);
		}
		final Bean source = (sourceValue != null ? bean(blueprint, sourceValue) : null);
		final String className = (source != null ? source.className() : null);
		if ( source == null || className == null ) {
			problems.add("Datum source [%s] has no datum source bean class.".formatted(uid));
			return null;
		}
		if ( source.factoryMethod() != null ) {
			problems.add("Datum source [%s] bean uses factory method [%s], which is not supported."
					.formatted(uid, source.factoryMethod()));
			return null;
		}
		final Bean factory = factoryBeans.get(uid);
		if ( factory == null ) {
			problems.add("Datum source [%s] has no %s service.".formatted(uid,
					SETTING_SPECIFIER_PROVIDER_FACTORY));
			return null;
		}
		return new Resolver(blueprint, uid, DATUM_SOURCE).describeDatumSource(className, source, factory,
				job, multi);
	}

	/** Get the bean of a value: an inline bean, or a reference to one. */
	private static @Nullable Bean bean(Blueprint blueprint, Value value) {
		return switch (value) {
			case BeanValue(Bean b) -> b;
			case Ref(String id) -> blueprint.beans().get(id);
			default -> null;
		};
	}

	/**
	 * Format a problem of a filter type, the way
	 * {@link FilterTypeDescriptor#problems()} has it.
	 *
	 * @param factoryUid
	 *        the filter type's factory UID
	 * @param description
	 *        the problem description
	 * @return the problem
	 */
	public static String problem(String factoryUid, String description) {
		return problem(DATUM_FILTER, factoryUid, description);
	}

	/**
	 * Format a problem of a datum source type, the way
	 * {@link DatumSourceTypeDescriptor#problems()} has it.
	 *
	 * @param factoryUid
	 *        the datum source type's factory UID
	 * @param description
	 *        the problem description
	 * @return the problem
	 */
	public static String datumSourceProblem(String factoryUid, String description) {
		return problem(DATUM_SOURCE, factoryUid, description);
	}

	private static String problem(String kind, String factoryUid, String description) {
		return "%s [%s]: %s.".formatted(kind, factoryUid, description);
	}

	/**
	 * Get the setting specifier provider factory service beans, by factory UID.
	 */
	private static Map<String, Bean> factoryBeans(Blueprint blueprint, List<String> problems) {
		final Map<String, Bean> result = new LinkedHashMap<>(8);
		for ( Service service : blueprint.services() ) {
			if ( !service.interfaces().contains(SETTING_SPECIFIER_PROVIDER_FACTORY) ) {
				continue;
			}
			Bean bean = service.bean();
			if ( bean == null && service.ref() != null ) {
				bean = blueprint.beans().get(service.ref());
			}
			if ( bean == null ) {
				problems.add("%s service without a bean.".formatted(SETTING_SPECIFIER_PROVIDER_FACTORY));
				continue;
			}
			if ( bean.properties().get("factoryUid") instanceof Literal(String uid) ) {
				if ( result.putIfAbsent(uid, bean) != null ) {
					problems.add("Duplicate %s service for factory UID [%s]."
							.formatted(SETTING_SPECIFIER_PROVIDER_FACTORY, uid));
				}
			} else {
				problems.add("%s service without a factoryUid value."
						.formatted(SETTING_SPECIFIER_PROVIDER_FACTORY));
			}
		}
		return result;
	}

	private static Set<String> roles(Map<String, Value> serviceProperties) {
		return switch (serviceProperties.get("role")) {
			case Literal(String role) -> Set.of(role);
			case ListValue(List<Value> values) -> {
				final Set<String> result = new HashSet<>(values.size());
				for ( Value v : values ) {
					if ( v instanceof Literal(String role) ) {
						result.add(role);
					}
				}
				yield result;
			}
			case null, default -> Set.of();
		};
	}

	/**
	 * Resolve the values of one filter or datum source type, collecting its
	 * problems.
	 */
	private static final class Resolver {

		private final Blueprint blueprint;
		private final String factoryUid;
		private final String kind;
		private final List<String> problems = new ArrayList<>(2);

		/** The IDs of the beans being resolved, to detect cycles. */
		private final Set<String> resolving = new HashSet<>(4);

		private Resolver(Blueprint blueprint, String factoryUid, String kind) {
			super();
			this.blueprint = blueprint;
			this.factoryUid = factoryUid;
			this.kind = kind;
		}

		private @Nullable String displayName(Bean factory) {
			final String displayName = (factory.properties()
					.get("displayName") instanceof Literal(String name) ? name : null);
			if ( displayName == null ) {
				problem("factory service has no displayName value");
			}
			return displayName;
		}

		private @Nullable MessageSourceDescriptor factoryMessageSource(Bean factory) {
			final Value msValue = factory.properties().get("messageSource");
			if ( msValue == null ) {
				problem("factory service has no messageSource");
				return null;
			}
			return messageSource(msValue);
		}

		private List<FilterValue> arguments(Bean bean) {
			final List<FilterValue> arguments = new ArrayList<>(bean.arguments().size());
			for ( Value arg : bean.arguments() ) {
				arguments.add(resolve(arg));
			}
			return arguments;
		}

		private Map<String, FilterValue> properties(Bean bean) {
			final Map<String, FilterValue> properties = new LinkedHashMap<>(bean.properties().size());
			for ( Map.Entry<String, Value> e : bean.properties().entrySet() ) {
				properties.put(e.getKey(), resolve(e.getValue()));
			}
			return properties;
		}

		private FilterTypeDescriptor describe(String className, Bean bean, Bean factory) {
			final String displayName = displayName(factory);
			final MessageSourceDescriptor messageSource = factoryMessageSource(factory);
			final List<FilterValue> arguments = arguments(bean);
			final Map<String, FilterValue> properties = properties(bean);

			final Set<FilterCapability> capabilities = EnumSet.noneOf(FilterCapability.class);
			addCapabilities(arguments, capabilities);
			addCapabilities(properties.values(), capabilities);

			return new FilterTypeDescriptor(factoryUid, displayName, className, bean.initMethod(),
					bean.destroyMethod(), messageSource, List.copyOf(arguments),
					Collections.unmodifiableMap(properties), Collections.unmodifiableSet(capabilities),
					List.copyOf(problems));
		}

		private DatumSourceTypeDescriptor describeDatumSource(String className, Bean bean, Bean factory,
				Bean job, boolean multi) {
			final String displayName = displayName(factory);
			final MessageSourceDescriptor messageSource = factoryMessageSource(factory);
			final List<FilterValue> arguments = arguments(bean);
			final Map<String, FilterValue> properties = properties(bean);

			// the job's schedule: its second constructor argument, or property
			String schedule = null;
			if ( job.arguments().size() > 1 ) {
				if ( job.arguments().get(1) instanceof Literal(String s) ) {
					schedule = s;
				} else {
					problem("managed job schedule is not a value");
				}
			} else if ( job.properties().get("schedule") instanceof Literal(String s) ) {
				schedule = s;
			}

			return new DatumSourceTypeDescriptor(factoryUid, displayName, className, bean.initMethod(),
					bean.destroyMethod(), messageSource, List.copyOf(arguments),
					Collections.unmodifiableMap(properties), schedule, serviceInterfaces(job), multi,
					List.copyOf(problems));
		}

		/**
		 * Get the interfaces a managed job's service provider configurations
		 * publish its services as.
		 */
		private List<String> serviceInterfaces(Bean job) {
			if ( !(job.properties()
					.get("serviceProviderConfigurations") instanceof MapValue(var entries)) ) {
				return List.of();
			}
			final Set<String> result = new LinkedHashSet<>(4);
			for ( Value v : entries.values() ) {
				final Bean config = bean(blueprint, v);
				switch (config != null ? config.properties().get("interfaces") : null) {
					case ListValue(List<Value> values) -> {
						for ( Value i : values ) {
							if ( i instanceof Literal(String name) ) {
								result.add(name);
							}
						}
					}
					case Literal(String names) -> {
						// Spring converts a comma-delimited string to an array
						for ( String name : StringUtils.commaDelimitedListToStringArray(names) ) {
							if ( !name.isBlank() ) {
								result.add(name.strip());
							}
						}
					}
					case null, default -> problem("service provider configuration has no interfaces");
				}
			}
			return List.copyOf(result);
		}

		private static void addCapabilities(Iterable<FilterValue> values,
				Set<FilterCapability> capabilities) {
			for ( FilterValue v : values ) {
				final ServiceKey key = switch (v) {
					case FilterValue.Service(ServiceKey s) -> s;
					case FilterValue.ServiceMethod(ServiceKey s, String _) -> s;
					default -> null;
				};
				if ( key != null ) {
					FilterCapability c = SERVICE_CAPABILITIES.get(key.serviceClassName());
					if ( c != null ) {
						capabilities.add(c);
					}
				}
			}
		}

		private void problem(String description) {
			problems.add(FilterBlueprintInterpreter.problem(kind, factoryUid, description));
		}

		private FilterValue unresolved(String reason) {
			problem(reason);
			return new FilterValue.Unresolved(reason);
		}

		private FilterValue resolve(Value value) {
			return switch (value) {
				case Literal(String v) -> new FilterValue.Literal(v);
				case Ref(String id) -> resolveRef(id);
				case BeanValue(Bean bean) -> resolveBean(bean);
				case ReferenceValue(Reference ref) -> resolveReference(ref);
				case ListValue _ -> unresolved("list values are not supported");
				case MapValue _ -> unresolved("map values are not supported");
				case Unsupported(String desc) -> unresolved("unsupported value %s".formatted(desc));
			};
		}

		private FilterValue resolveRef(String id) {
			final Reference ref = blueprint.references().get(id);
			if ( ref != null ) {
				return resolveReference(ref);
			}
			final Bean bean = blueprint.beans().get(id);
			if ( bean == null ) {
				return unresolved("unknown component [%s]".formatted(id));
			}
			if ( !resolving.add(id) ) {
				return unresolved("circular reference to [%s]".formatted(id));
			}
			try {
				return resolveBean(bean);
			} finally {
				resolving.remove(id);
			}
		}

		private FilterValue resolveReference(Reference ref) {
			if ( ref.interfaceName() == null ) {
				return unresolved("reference [%s] has no interface".formatted(ref.id()));
			}
			return new FilterValue.Service(new ServiceKey(ref.interfaceName(), ref.filter(),
					ref.list() ? ServiceKey.Source.ReferenceList : ServiceKey.Source.Reference));
		}

		private FilterValue resolveBean(Bean bean) {
			final String className = bean.className();
			if ( DYNAMIC_SERVICE_TRACKER.equals(className) ) {
				if ( !(bean.properties().get("serviceClassName") instanceof Literal(String type)) ) {
					return unresolved(
							"service tracker [%s] has no serviceClassName value".formatted(bean.id()));
				}
				final Value filter = bean.properties().get("serviceFilter");
				if ( filter != null && !(filter instanceof Literal) ) {
					return unresolved(
							"service tracker [%s] serviceFilter is not a value".formatted(bean.id()));
				}
				final Value ignoreEmpty = bean.properties().get("ignoreEmptyPropertyFilterValues");
				if ( ignoreEmpty != null && !(ignoreEmpty instanceof Literal) ) {
					return unresolved(
							"service tracker [%s] ignoreEmptyPropertyFilterValues is not a value"
									.formatted(bean.id()));
				}
				return new FilterValue.Service(new ServiceKey(type,
						(filter instanceof Literal(String f) ? f : null), ServiceKey.Source.Tracker,
						!(ignoreEmpty instanceof Literal(String v)) || Boolean.parseBoolean(v.strip())));
			}
			if ( RESOURCE_BUNDLE_MESSAGE_SOURCE.equals(className)
					|| PREFIXED_MESSAGE_SOURCE.equals(className) ) {
				final MessageSourceDescriptor ms = messageSourceBean(bean);
				return (ms != null ? new FilterValue.Messages(ms)
						: new FilterValue.Unresolved("message source [%s]".formatted(bean.id())));
			}
			final String factoryRef = bean.factoryRef();
			final String factoryMethod = bean.factoryMethod();
			if ( factoryRef != null && factoryMethod != null && bean.arguments().isEmpty() ) {
				return switch (resolveRef(factoryRef)) {
					case FilterValue.Service(ServiceKey s) -> new FilterValue.ServiceMethod(s,
							factoryMethod);
					case FilterValue.Unresolved u -> u;
					default -> unresolved("factory-ref [%s] of bean [%s] is not a service"
							.formatted(factoryRef, bean.id()));
				};
			}
			if ( bean.id() != null ) {
				return unresolved("bean [%s] of class [%s] is created by the bundle".formatted(bean.id(),
						className));
			}
			return unresolved("inline bean of class [%s] is created by the bundle".formatted(className));
		}

		private @Nullable MessageSourceDescriptor messageSource(Value value) {
			return switch (value) {
				case Ref(String id) -> {
					final Bean bean = blueprint.beans().get(id);
					if ( bean == null ) {
						problem("message source [%s] is not a bean".formatted(id));
						yield null;
					}
					if ( !resolving.add(id) ) {
						problem("circular reference to message source [%s]".formatted(id));
						yield null;
					}
					try {
						yield messageSourceBean(bean);
					} finally {
						resolving.remove(id);
					}
				}
				case BeanValue(Bean bean) -> messageSourceBean(bean);
				default -> {
					problem("message source is not a bean");
					yield null;
				}
			};
		}

		private @Nullable MessageSourceDescriptor messageSourceBean(Bean bean) {
			final Map<String, Value> props = bean.properties();
			if ( RESOURCE_BUNDLE_MESSAGE_SOURCE.equals(bean.className()) ) {
				final List<String> basenames = new ArrayList<>(4);
				if ( props.get("basename") instanceof Literal(String name) ) {
					basenames.add(name);
				}
				switch (props.get("basenames")) {
					case Literal(String names) -> {
						// Spring converts a comma-delimited string to an array
						for ( String name : StringUtils.commaDelimitedListToStringArray(names) ) {
							if ( !name.isBlank() ) {
								basenames.add(name.strip());
							}
						}
					}
					case ListValue(List<Value> values) -> {
						for ( Value v : values ) {
							if ( v instanceof Literal(String name) ) {
								basenames.add(name);
							} else {
								problem("message source [%s] has a basename that is not a value"
										.formatted(bean.id()));
							}
						}
					}
					case null -> {
						// basename only
					}
					default -> problem(
							"message source [%s] basenames is not a list".formatted(bean.id()));
				}
				final Value parentValue = props.get("parentMessageSource");
				final MessageSourceDescriptor parent = (parentValue != null ? messageSource(parentValue)
						: null);
				return new MessageSourceDescriptor.Bundle(List.copyOf(basenames), parent);
			} else if ( PREFIXED_MESSAGE_SOURCE.equals(bean.className()) ) {
				if ( !(props.get("prefix") instanceof Literal(String prefix)) ) {
					problem("prefixed message source [%s] has no prefix value".formatted(bean.id()));
					return null;
				}
				final Value delegateValue = props.get("delegate");
				if ( delegateValue == null ) {
					problem("prefixed message source [%s] has no delegate".formatted(bean.id()));
					return null;
				}
				final MessageSourceDescriptor delegate = messageSource(delegateValue);
				return (delegate != null ? new MessageSourceDescriptor.Prefixed(prefix, delegate)
						: null);
			}
			problem("message source class [%s] is not supported".formatted(bean.className()));
			return null;
		}

	}

}
