/* ==================================================================
 * CustomFilterLoader.java - 21 Oct 2026 4:12:08 pm
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

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.core.NativeDetector;
import org.springframework.util.StringUtils;
import s10k.node.sim.filter.blueprint.Blueprint;
import s10k.node.sim.filter.blueprint.BlueprintReader;
import s10k.node.sim.filter.blueprint.FilterBlueprintInterpreter;
import s10k.node.sim.filter.blueprint.FilterBlueprintInterpreter.Interpretation;
import s10k.node.sim.filter.blueprint.FilterTypeDescriptor;
import s10k.node.sim.filter.custom.CustomFilters.UnavailableFilterType;
import s10k.node.sim.filter.registry.FilterTypeDefinition;
import s10k.node.sim.filter.registry.FilterTypeOrigin;
import net.solarnetwork.service.ExpressionService;

/**
 * Load custom filter types from SolarNode bundle jars.
 *
 * <p>
 * The path lists jars, and directories of jars. All the jars share one class
 * loader, whose parent is the simulator's, so a bundle can use the classes of
 * the simulator's SolarNode bundles and of the other jars, but cannot replace
 * any of the simulator's own classes. Conflicts between the jars are for the
 * user to resolve.
 * </p>
 *
 * <p>
 * Like an OSGi framework, the loader puts the jars a bundle's
 * {@code Bundle-ClassPath} names on its class path, and the bundle itself only
 * if that header is missing or has {@code .}. It reads the blueprint XML that
 * the {@code Bundle-Blueprint} header names, or
 * {@code OSGI-INF/blueprint/*.xml} by default. A jar without blueprint XML is a
 * library for the other jars.
 * </p>
 *
 * <p>
 * The native executable cannot load classes, so it refuses a custom filters
 * path.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class CustomFilterLoader {

	/** The default blueprint XML location. */
	public static final String DEFAULT_BLUEPRINT = "OSGI-INF/blueprint/*.xml";

	/** The bundle symbolic name manifest header. */
	public static final String BUNDLE_SYMBOLIC_NAME = "Bundle-SymbolicName";

	/** The bundle version manifest header. */
	public static final String BUNDLE_VERSION = "Bundle-Version";

	/** The bundle class path manifest header. */
	public static final String BUNDLE_CLASSPATH = "Bundle-ClassPath";

	/** The bundle blueprint manifest header. */
	public static final String BUNDLE_BLUEPRINT = "Bundle-Blueprint";

	private final ExpressionService expressionService;
	private final Set<String> reservedFactoryUids;
	private final BlueprintReader reader = new BlueprintReader();

	/** A jar read from the path. */
	private record BundleJar(Path file, String name, @Nullable String version, List<Path> classPath,
			List<Blueprint> blueprints) {

		private String label() {
			return (version != null ? name + " " + version : name);
		}

	}

	/**
	 * Constructor.
	 *
	 * @param expressionService
	 *        the expression service, for the simulated services filters are
	 *        checked with
	 * @param reservedFactoryUids
	 *        the factory UIDs of the standard filter types, which custom filter
	 *        types cannot use
	 */
	public CustomFilterLoader(ExpressionService expressionService,
			Collection<String> reservedFactoryUids) {
		super();
		this.expressionService = expressionService;
		this.reservedFactoryUids = Set.copyOf(reservedFactoryUids);
	}

	/**
	 * Load the custom filter types.
	 *
	 * @param path
	 *        the jars and directories of jars, where a leading {@code ~/} is
	 *        the user's home directory; blank entries are ignored
	 * @return the custom filters
	 * @throws IllegalStateException
	 *         if the path is not empty and the simulator is the native
	 *         executable
	 */
	public CustomFilters load(List<String> path) {
		final List<String> entries = path.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
		if ( entries.isEmpty() ) {
			return CustomFilters.NONE;
		}
		if ( NativeDetector.inNativeImage() ) {
			throw new IllegalStateException("""
					Custom filters (sim.filters.path) need the JVM build of the simulator, \
					the sn-filter-sim jar, because the native executable cannot load classes. \
					Run it with Java 25: java -jar sn-filter-sim-<version>.jar \
					--sim.filters.path=%s""".formatted(String.join(",", entries)));
		}

		final List<String> problems = new ArrayList<>(4);
		final List<BundleJar> jars = new ArrayList<>(4);
		final Extractor extractor = new Extractor();
		for ( Path file : jarFiles(entries, problems) ) {
			try {
				jars.add(readJar(file, extractor, problems));
			} catch ( IOException e ) {
				problems.add("The jar %s could not be read: %s".formatted(file, e.getMessage()));
			}
		}
		if ( jars.isEmpty() ) {
			return new CustomFilters(List.of(), List.of(), List.of(), List.copyOf(problems));
		}

		final URLClassLoader classLoader = new URLClassLoader("sim-custom-filters", urls(jars, problems),
				CustomFilterLoader.class.getClassLoader());
		final List<FilterTypeDefinition> definitions = new ArrayList<>(8);
		final List<UnavailableFilterType> unavailable = new ArrayList<>(2);
		final List<FilterBundle> bundles = new ArrayList<>(jars.size());
		final Set<String> factoryUids = new HashSet<>(8);
		for ( BundleJar jar : jars ) {
			final List<String> available = new ArrayList<>(4);
			if ( !jar.blueprints().isEmpty() ) {
				final Interpretation interpretation = FilterBlueprintInterpreter
						.interpret(Blueprint.merge(jar.blueprints()));
				for ( String p : interpretation.problems() ) {
					problems.add("%s: %s".formatted(jar.label(), p));
				}
				final FilterTypeOrigin origin = new FilterTypeOrigin(jar.name(), jar.version(),
						jar.file(), classLoader);
				for ( FilterTypeDescriptor descriptor : interpretation.filterTypes() ) {
					final String uid = descriptor.factoryUid();
					if ( reservedFactoryUids.contains(uid) ) {
						unavailable.add(new UnavailableFilterType(uid, descriptor.displayName(),
								jar.label(), "It has the factory UID of a standard filter type."));
						continue;
					}
					if ( !factoryUids.add(uid) ) {
						unavailable.add(
								new UnavailableFilterType(uid, descriptor.displayName(), jar.label(),
										"Another jar earlier in the path has the same factory UID."));
						continue;
					}
					switch (BlueprintFilterTypes.define(descriptor, classLoader, origin,
							expressionService)) {
						case BlueprintFilterTypes.Defined(var def, var warnings) -> {
							definitions.add(def);
							available.add(uid);
							for ( String w : warnings ) {
								problems.add("%s: %s".formatted(jar.label(), w));
							}
						}
						case BlueprintFilterTypes.Unavailable(var u, var name, var reason) -> {
							factoryUids.remove(u);
							unavailable.add(new UnavailableFilterType(u, name, jar.label(), reason));
						}
					}
				}
			}
			bundles.add(new FilterBundle(jar.name(), jar.version(), jar.file(), List.copyOf(available),
					!jar.blueprints().isEmpty()));
		}
		return new CustomFilters(List.copyOf(definitions), List.copyOf(unavailable),
				List.copyOf(bundles), List.copyOf(problems));
	}

	/**
	 * Get the jars of the path: each jar, and the jars in each directory, by
	 * name.
	 */
	private static List<Path> jarFiles(List<String> entries, List<String> problems) {
		final Set<Path> result = new LinkedHashSet<>(entries.size());
		for ( String entry : entries ) {
			Path p = (entry.equals("~") || entry.startsWith("~/")
					? Path.of(System.getProperty("user.home"), entry.substring(1).replaceFirst("^/", ""))
					: Path.of(entry));
			p = p.toAbsolutePath().normalize();
			if ( Files.isDirectory(p) ) {
				try (Stream<Path> files = Files.list(p)) {
					final List<Path> jars = files.filter(f -> Files.isRegularFile(f)
							&& f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
							.sorted().toList();
					if ( jars.isEmpty() ) {
						problems.add("The custom filters directory %s has no jar files.".formatted(p));
					}
					result.addAll(jars);
				} catch ( IOException e ) {
					problems.add("The custom filters directory %s could not be read: %s".formatted(p,
							e.getMessage()));
				}
			} else if ( Files.isRegularFile(p) ) {
				result.add(p);
			} else {
				problems.add("The custom filters path %s does not exist.".formatted(p));
			}
		}
		return List.copyOf(result);
	}

	private BundleJar readJar(Path file, Extractor extractor, List<String> problems) throws IOException {
		try (JarFile jar = new JarFile(file.toFile())) {
			final Manifest manifest = jar.getManifest();
			final Attributes attrs = (manifest != null ? manifest.getMainAttributes()
					: new Attributes());
			String name = headerValue(attrs.getValue(BUNDLE_SYMBOLIC_NAME));
			if ( name == null ) {
				name = file.getFileName().toString().replaceFirst("(?i)\\.jar$", "");
			}
			final String version = headerValue(attrs.getValue(BUNDLE_VERSION));

			final List<Path> classPath = new ArrayList<>(2);
			final String bundleClassPath = attrs.getValue(BUNDLE_CLASSPATH);
			if ( bundleClassPath == null || bundleClassPath.isBlank() ) {
				classPath.add(file);
			} else {
				for ( String entry : headerEntries(bundleClassPath) ) {
					if ( ".".equals(entry) || "/".equals(entry) ) {
						classPath.add(file);
						continue;
					}
					final String entryName = (entry.startsWith("/") ? entry.substring(1) : entry);
					final JarEntry nested = jar.getJarEntry(entryName);
					if ( nested == null ) {
						// OSGi ignores missing entries
						continue;
					}
					if ( nested.isDirectory() || !entryName.toLowerCase(Locale.ROOT).endsWith(".jar") ) {
						problems.add("%s: the Bundle-ClassPath entry %s is not supported, only jars."
								.formatted(name, entry));
						continue;
					}
					try (InputStream in = jar.getInputStream(nested)) {
						classPath.add(extractor.extract(entryName, in));
					}
				}
			}

			final List<Blueprint> blueprints = new ArrayList<>(1);
			for ( JarEntry entry : blueprintEntries(jar, attrs.getValue(BUNDLE_BLUEPRINT)) ) {
				try (InputStream in = jar.getInputStream(entry)) {
					blueprints.add(reader.read(in));
				} catch ( IOException e ) {
					problems.add("%s: the blueprint %s could not be read: %s".formatted(name,
							entry.getName(), e.getMessage()));
				}
			}
			return new BundleJar(file, name, version, List.copyOf(classPath), List.copyOf(blueprints));
		}
	}

	/**
	 * Get the blueprint XML entries of a jar, as the {@code Bundle-Blueprint}
	 * header names them: paths, where the last segment can have {@code *}
	 * wildcards, or directories, which mean all their XML files.
	 */
	private static List<JarEntry> blueprintEntries(JarFile jar, @Nullable String header) {
		final List<String> locations = (header != null && !header.isBlank() ? headerEntries(header)
				: List.of(DEFAULT_BLUEPRINT));
		final Set<String> names = new LinkedHashSet<>(2);
		for ( String location : locations ) {
			String loc = (location.startsWith("/") ? location.substring(1) : location);
			if ( loc.endsWith("/") ) {
				loc = loc + "*.xml";
			}
			final int slash = loc.lastIndexOf('/');
			final String dir = loc.substring(0, slash + 1);
			final String file = loc.substring(slash + 1);
			if ( file.indexOf('*') < 0 ) {
				names.add(loc);
				continue;
			}
			final Pattern pattern = Pattern
					.compile(Pattern.quote(dir) + Pattern.quote(file).replace("*", "\\E[^/]*\\Q"));
			jar.stream().map(JarEntry::getName).filter(n -> pattern.matcher(n).matches()).sorted()
					.forEach(names::add);
		}
		final List<JarEntry> result = new ArrayList<>(names.size());
		for ( String name : names ) {
			final JarEntry entry = jar.getJarEntry(name);
			if ( entry != null && !entry.isDirectory() ) {
				result.add(entry);
			}
		}
		return result;
	}

	/** Get the entries of a manifest header, without their parameters. */
	private static List<String> headerEntries(String header) {
		final List<String> result = new ArrayList<>(2);
		for ( String entry : StringUtils.commaDelimitedListToStringArray(header) ) {
			final String value = headerValue(entry);
			if ( value != null ) {
				result.add(value);
			}
		}
		return result;
	}

	/** Get a manifest header value, without its parameters. */
	private static @Nullable String headerValue(@Nullable String value) {
		if ( value == null ) {
			return null;
		}
		final int semi = value.indexOf(';');
		final String result = (semi < 0 ? value : value.substring(0, semi)).strip();
		return (result.isEmpty() ? null : result);
	}

	private static URL[] urls(List<BundleJar> jars, List<String> problems) {
		final Set<Path> paths = new LinkedHashSet<>(jars.size());
		for ( BundleJar jar : jars ) {
			paths.addAll(jar.classPath());
		}
		final List<URL> result = new ArrayList<>(paths.size());
		for ( Path p : paths ) {
			try {
				result.add(p.toUri().toURL());
			} catch ( MalformedURLException e ) {
				problems.add("The jar %s could not be loaded: %s".formatted(p, e.getMessage()));
			}
		}
		return result.toArray(URL[]::new);
	}

	/**
	 * Extracts the jars that bundles embed to a temporary directory, which is
	 * deleted when the simulator stops.
	 */
	private static final class Extractor {

		private @Nullable Path dir;
		private int count;

		private Path extract(String entryName, InputStream in) throws IOException {
			Path d = dir;
			if ( d == null ) {
				d = Files.createTempDirectory("sn-filter-sim-");
				d.toFile().deleteOnExit();
				dir = d;
			}
			// numbered, as the names come from the jars
			final String name = entryName.substring(entryName.lastIndexOf('/') + 1)
					.replaceAll("[^A-Za-z0-9._-]", "_");
			final Path file = d.resolve("%d-%s".formatted(++count, name));
			file.toFile().deleteOnExit();
			Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
			return file;
		}

	}

}
