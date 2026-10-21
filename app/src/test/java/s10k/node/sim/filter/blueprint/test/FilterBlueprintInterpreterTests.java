/* ==================================================================
 * FilterBlueprintInterpreterTests.java - 21 Oct 2026 2:05:41 pm
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

package s10k.node.sim.filter.blueprint.test;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.BDDAssertions.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.context.HierarchicalMessageSource;
import org.springframework.context.MessageSource;
import org.springframework.context.support.AbstractResourceBasedMessageSource;
import net.solarnetwork.node.datum.filter.std.PropertyDatumFilterService;
import net.solarnetwork.node.datum.filter.tariff.TariffDatumFilterService;
import net.solarnetwork.node.datum.pvlib.PvlibPoaDatumFilterService;
import net.solarnetwork.node.metrics.harvester.MetricHarvesterDatumFilterService;
import net.solarnetwork.node.service.support.DatumFilterChainService;
import net.solarnetwork.service.DatumFilterService;
import net.solarnetwork.support.PrefixedMessageSource;
import s10k.node.sim.filter.blueprint.Blueprint;
import s10k.node.sim.filter.blueprint.BlueprintReader;
import s10k.node.sim.filter.blueprint.FilterBlueprintInterpreter;
import s10k.node.sim.filter.blueprint.FilterBlueprintInterpreter.Interpretation;
import s10k.node.sim.filter.blueprint.FilterTypeDescriptor;
import s10k.node.sim.filter.blueprint.FilterValue;
import s10k.node.sim.filter.blueprint.MessageSourceDescriptor;
import s10k.node.sim.filter.blueprint.ServiceKey;
import s10k.node.sim.filter.domain.FilterCapability;
import s10k.node.sim.filter.registry.FilterTypeDefinition;
import s10k.node.sim.filter.registry.SimPvlibPoaDatumFilterService;
import s10k.node.sim.filter.registry.StandardFilterTypes;

/**
 * Test cases for the {@link FilterBlueprintInterpreter} class, comparing the
 * filter types it finds in the blueprints of the SolarNetwork bundles on the
 * class path with {@link StandardFilterTypes}.
 *
 * @author matt
 * @version 1.0
 */
public class FilterBlueprintInterpreterTests {

	/** A class in each bundle with user datum filters. */
	private static final List<Class<?>> BUNDLE_CLASSES = List.of(DatumFilterChainService.class,
			PropertyDatumFilterService.class, TariffDatumFilterService.class,
			PvlibPoaDatumFilterService.class, MetricHarvesterDatumFilterService.class);

	/**
	 * The filter classes the simulator substitutes for the blueprint classes,
	 * by factory UID.
	 */
	private static final Map<String, Class<?>> SUBSTITUTED_CLASSES = Map
			.of(StandardFilterTypes.PVLIB_POA, SimPvlibPoaDatumFilterService.class);

	/**
	 * The capabilities that cannot be inferred from the services a filter uses,
	 * by factory UID.
	 */
	private static final Map<String, Set<FilterCapability>> NOT_INFERRED_CAPABILITIES = Map
			.of(StandardFilterTypes.PVLIB_POA, EnumSet.of(FilterCapability.Commands));

	/** The problems expected from the bundle blueprints. */
	private static final List<String> EXPECTED_PROBLEMS = List
			.of("Datum filter [net.solarnetwork.node.datum.xform.user]: bean [opModesService] of class "
					+ "[net.solarnetwork.node.runtime.DefaultOperationalModesService] is created by "
					+ "the bundle.");

	private static List<Interpretation> interpretations;
	private static Map<String, FilterTypeDescriptor> descriptors;
	private static Map<String, FilterTypeDefinition> definitions;

	@BeforeAll
	public static void setup() throws IOException, URISyntaxException {
		final BlueprintReader reader = new BlueprintReader();
		interpretations = new ArrayList<>(BUNDLE_CLASSES.size());
		for ( Class<?> bundleClass : BUNDLE_CLASSES ) {
			interpretations.add(FilterBlueprintInterpreter.interpret(readBundle(reader, bundleClass)));
		}
		descriptors = interpretations.stream().flatMap(i -> i.filterTypes().stream()).collect(
				toMap(FilterTypeDescriptor::factoryUid, d -> d, (a, _) -> a, LinkedHashMap::new));
		definitions = StandardFilterTypes.definitions().stream()
				.collect(toMap(FilterTypeDefinition::factoryUid, d -> d));
	}

	/**
	 * Read and merge all the blueprint XML files of the bundle that contains a
	 * class.
	 */
	private static Blueprint readBundle(BlueprintReader reader, Class<?> bundleClass)
			throws IOException, URISyntaxException {
		final Path location = Path
				.of(bundleClass.getProtectionDomain().getCodeSource().getLocation().toURI());
		try (FileSystem jarFs = (Files.isDirectory(location) ? null
				: FileSystems.newFileSystem(location))) {
			final Path root = (jarFs != null ? jarFs.getPath("/") : location);
			final List<Blueprint> blueprints = new ArrayList<>(1);
			try (Stream<Path> files = Files.list(root.resolve("OSGI-INF/blueprint"))) {
				for ( Path file : files.filter(p -> p.toString().endsWith(".xml")).sorted().toList() ) {
					try (InputStream in = Files.newInputStream(file)) {
						blueprints.add(reader.read(in));
					}
				}
			}
			then(blueprints).as("Bundle of %s has blueprint XML", bundleClass.getName()).isNotEmpty();
			return Blueprint.merge(blueprints);
		}
	}

	@Test
	public void factoryUids() {
		// @formatter:off
		then(descriptors.keySet())
			.as("Same user filter types as hand-written definitions")
			.containsExactlyInAnyOrderElementsOf(definitions.keySet())
			.as("Global filters are not user filters")
			.doesNotContainAnyElementsOf(StandardFilterTypes.GLOBAL)
			;
		// @formatter:on
	}

	@Test
	public void problems() {
		final List<String> problems = new ArrayList<>();
		for ( Interpretation i : interpretations ) {
			problems.addAll(i.problems());
			i.filterTypes().forEach(d -> problems.addAll(d.problems()));
		}
		then(problems).as("Only the expected unresolved constructs")
				.containsExactlyInAnyOrderElementsOf(EXPECTED_PROBLEMS);
	}

	@Test
	public void displayNames() {
		for ( FilterTypeDefinition def : definitions.values() ) {
			then(descriptors.get(def.factoryUid()).displayName())
					.as("Display name of %s", def.factoryUid()).isEqualTo(def.displayName());
		}
	}

	@Test
	public void filterClasses() {
		for ( FilterTypeDefinition def : definitions.values() ) {
			final FilterTypeDescriptor desc = descriptors.get(def.factoryUid());
			final Class<?> substitute = SUBSTITUTED_CLASSES.get(def.factoryUid());
			if ( substitute != null ) {
				then(def.filterClass()).as("Substituted filter class of %s", def.factoryUid())
						.isEqualTo(substitute);
				then(def.filterClass().getSuperclass().getName())
						.as("Substitute of %s extends the blueprint class", def.factoryUid())
						.isEqualTo(desc.filterClassName());
			} else {
				then(desc.filterClassName()).as("Filter class of %s", def.factoryUid())
						.isEqualTo(def.filterClass().getName());
			}
		}
	}

	@Test
	public void lifecycleMethods() {
		for ( FilterTypeDefinition def : definitions.values() ) {
			final FilterTypeDescriptor desc = descriptors.get(def.factoryUid());
			then(invokedMethod(def.filterClass(), def.init())).as("Init method of %s", def.factoryUid())
					.isEqualTo(desc.initMethod());
			then(invokedMethod(def.filterClass(), def.destroy()))
					.as("Destroy method of %s", def.factoryUid()).isEqualTo(desc.destroyMethod());
		}
	}

	/**
	 * Get the name of the method a hand-written lifecycle function calls, by
	 * calling it on a mock filter.
	 */
	private static @Nullable String invokedMethod(Class<? extends DatumFilterService> filterClass,
			@Nullable Consumer<DatumFilterService> method) {
		if ( method == null ) {
			return null;
		}
		final DatumFilterService filter = mock(filterClass);
		method.accept(filter);
		final List<String> names = mockingDetails(filter).getInvocations().stream()
				.map(Invocation::getMethod).map(java.lang.reflect.Method::getName).toList();
		then(names).as("Lifecycle function of %s calls one method", filterClass.getName()).hasSize(1);
		return names.getFirst();
	}

	@Test
	public void messageSources() {
		for ( FilterTypeDefinition def : definitions.values() ) {
			then(descriptors.get(def.factoryUid()).messageSource())
					.as("Message source of %s", def.factoryUid())
					.isEqualTo(describe(def.messageSource()));
		}
	}

	/**
	 * Describe a hand-written message source like the blueprint interpreter
	 * does, with class names as base names.
	 */
	private static MessageSourceDescriptor describe(MessageSource ms) {
		if ( ms instanceof PrefixedMessageSource p ) {
			return new MessageSourceDescriptor.Prefixed(p.getPrefix(), describe(p.getDelegate()));
		}
		then(ms).isInstanceOf(AbstractResourceBasedMessageSource.class);
		final List<String> basenames = ((AbstractResourceBasedMessageSource) ms).getBasenameSet()
				.stream().map(n -> n.substring("classpath:".length()).replace('/', '.')).toList();
		final MessageSource parent = ((HierarchicalMessageSource) ms).getParentMessageSource();
		return new MessageSourceDescriptor.Bundle(basenames, (parent != null ? describe(parent) : null));
	}

	@Test
	public void capabilities() {
		for ( FilterTypeDefinition def : definitions.values() ) {
			final Set<FilterCapability> expected = EnumSet.noneOf(FilterCapability.class);
			expected.addAll(def.capabilities());
			expected.removeAll(NOT_INFERRED_CAPABILITIES.getOrDefault(def.factoryUid(), Set.of()));
			then(descriptors.get(def.factoryUid()).capabilities())
					.as("Inferred capabilities of %s", def.factoryUid())
					.containsExactlyInAnyOrderElementsOf(expected);
		}
	}

	@Test
	public void resolvedValues() {
		// @formatter:off
		final FilterTypeDescriptor poa = descriptors.get(StandardFilterTypes.PVLIB_POA);
		then(poa.arguments())
			.as("POA arguments resolved to services")
			.containsExactly(
				new FilterValue.ServiceMethod(new ServiceKey(
						"net.solarnetwork.service.ObjectMapperService", "(function=node)",
						ServiceKey.Source.Reference), "getObjectMapper"),
				new FilterValue.Service(new ServiceKey(
						"net.solarnetwork.node.service.DatumMetadataService", null,
						ServiceKey.Source.Tracker)),
				new FilterValue.Service(new ServiceKey(
						"net.solarnetwork.node.service.MetadataService", null,
						ServiceKey.Source.Tracker, false))
			);

		final FilterTypeDescriptor chain = descriptors.get(StandardFilterTypes.CHAIN);
		then(chain.arguments())
			.as("Chain arguments resolved to a literal and a reference list")
			.containsExactly(
				new FilterValue.Literal(StandardFilterTypes.CHAIN),
				new FilterValue.Service(new ServiceKey(
						"net.solarnetwork.service.DatumFilterService", "(!(role=global))",
						ServiceKey.Source.ReferenceList))
			);

		final FilterTypeDescriptor join = descriptors.get(StandardFilterTypes.JOIN);
		then(join.properties())
			.as("Join service and literal properties")
			.containsEntry("taskScheduler", new FilterValue.Service(new ServiceKey(
					"org.springframework.scheduling.TaskScheduler", "(function=node)",
					ServiceKey.Source.Reference)))
			.containsEntry("metadataService", new FilterValue.Service(new ServiceKey(
					"net.solarnetwork.node.service.MetadataService", "(function=node)",
					ServiceKey.Source.Tracker)))
			.containsEntry("startupDelay", new FilterValue.Literal("300"))
			.containsEntry("messageSource", new FilterValue.Messages(join.messageSource()))
			;
		// @formatter:on
	}

	@Test
	public void unresolvedConstructsReported() throws IOException {
		// GIVEN
		final String xml = """
				<blueprint xmlns="http://www.osgi.org/xmlns/blueprint/v1.0.0"
					xmlns:osgix="http://www.eclipse.org/gemini/blueprint/schema/blueprint-compendium"
					xmlns:beans="http://www.springframework.org/schema/beans">
					<bean id="ms" class="org.example.CustomMessageSource"/>
					<bean id="helper" class="org.example.Helper"/>
					<service interface="net.solarnetwork.settings.SettingSpecifierProviderFactory">
						<bean class="net.solarnetwork.settings.support.BasicSettingSpecifierProviderFactory">
							<property name="displayName" value="Test Filter"/>
							<property name="factoryUid" value="test.filter"/>
							<property name="messageSource" ref="ms"/>
						</bean>
					</service>
					<osgix:managed-service-factory factory-pid="test.filter">
						<osgix:interfaces>
							<beans:value>net.solarnetwork.service.DatumFilterService</beans:value>
						</osgix:interfaces>
						<osgix:service-properties>
							<beans:entry key="role" value="user"/>
						</osgix:service-properties>
						<bean class="org.example.TestFilter" init-method="start">
							<argument ref="helper"/>
							<argument ref="missing"/>
							<property name="things"><map><entry key="a" value="b"/></map></property>
							<property name="mode" value="fast"/>
						</bean>
					</osgix:managed-service-factory>
					<osgix:managed-service-factory factory-pid="orphan.filter">
						<osgix:interfaces>
							<beans:value>net.solarnetwork.service.DatumFilterService</beans:value>
						</osgix:interfaces>
						<osgix:service-properties>
							<beans:entry key="role">
								<beans:array><beans:value>user</beans:value></beans:array>
							</beans:entry>
						</osgix:service-properties>
						<bean class="org.example.OrphanFilter"/>
					</osgix:managed-service-factory>
				</blueprint>
				""";

		// WHEN
		final Interpretation result = FilterBlueprintInterpreter.interpret(new BlueprintReader()
				.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));

		// THEN
		// @formatter:off
		then(result.problems())
			.as("Filter without a factory service reported")
			.containsExactly("Datum filter [orphan.filter] has no "
					+ "net.solarnetwork.settings.SettingSpecifierProviderFactory service.")
			;
		then(result.filterTypes())
			.as("Filter with unresolved values still described")
			.singleElement()
			.satisfies(d -> {
				then(d.displayName()).isEqualTo("Test Filter");
				then(d.filterClassName()).isEqualTo("org.example.TestFilter");
				then(d.initMethod()).isEqualTo("start");
				then(d.messageSource()).isNull();
				then(d.properties()).containsEntry("mode", new FilterValue.Literal("fast"));
				then(d.problems()).containsExactly(
						"Datum filter [test.filter]: message source class "
							+ "[org.example.CustomMessageSource] is not supported.",
						"Datum filter [test.filter]: bean [helper] of class "
							+ "[org.example.Helper] is created by the bundle.",
						"Datum filter [test.filter]: unknown component [missing].",
						"Datum filter [test.filter]: map values are not supported.");
			})
			;
		// @formatter:on
	}

}
