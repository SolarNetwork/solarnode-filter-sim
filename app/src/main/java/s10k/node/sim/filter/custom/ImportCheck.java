/* ==================================================================
 * ImportCheck.java - 21 Oct 2026 6:40:15 pm
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
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Check the packages a bundle imports against those the simulator and the
 * custom filter jars have, the way an OSGi framework resolves a bundle.
 *
 * <p>
 * Only missing packages, and the versions of the {@code net.solarnetwork}
 * packages, are checked. The simulator runs the SolarNode bundles with newer
 * versions of other libraries than they import, such as Spring 7 for
 * {@code [6.2,7.0)}, so their version ranges do not matter. The
 * {@code net.solarnetwork.common.osgi.service} package is not checked either,
 * as bundles import it only for their blueprint's service trackers, which the
 * simulator replaces.
 * </p>
 *
 * <p>
 * A package that no jar exports is still there if the class loader finds it, as
 * most libraries are not OSGi bundles.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class ImportCheck {

	/** The prefix of the packages whose versions are checked. */
	public static final String VERSIONED_PREFIX = "net.solarnetwork.";

	/** The packages that are not checked. */
	public static final Set<String> IGNORED = Set.of("net.solarnetwork.common.osgi.service");

	private static final Pattern CLAUSE_SEPARATOR = Pattern.compile(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
	private static final Pattern PART_SEPARATOR = Pattern.compile(";(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
	private static final Pattern RANGE = Pattern
			.compile("([\\[(])\\s*([^,]+?)\\s*,\\s*([^\\])]+?)\\s*([\\])])");

	private final ClassLoader classLoader;
	private final Map<String, List<Version>> exports;
	private final Set<String> jdkPackages;

	/** An OSGi version: major, minor, and micro numbers, and a qualifier. */
	record Version(int major, int minor, int micro, String qualifier) implements Comparable<Version> {

		static Version parse(String value) {
			final String[] parts = value.strip().split("\\.", 4);
			try {
				return new Version(Integer.parseInt(parts[0]),
						parts.length > 1 ? Integer.parseInt(parts[1]) : 0,
						parts.length > 2 ? Integer.parseInt(parts[2]) : 0,
						parts.length > 3 ? parts[3] : "");
			} catch ( NumberFormatException e ) {
				return new Version(0, 0, 0, "");
			}
		}

		@Override
		public int compareTo(Version o) {
			int c = Integer.compare(major, o.major);
			if ( c == 0 ) {
				c = Integer.compare(minor, o.minor);
			}
			if ( c == 0 ) {
				c = Integer.compare(micro, o.micro);
			}
			return (c == 0 ? qualifier.compareTo(o.qualifier) : c);
		}

		@Override
		public String toString() {
			return major + "." + minor + "." + micro + (qualifier.isEmpty() ? "" : "." + qualifier);
		}

	}

	/** An OSGi version range. */
	record VersionRange(Version min, boolean minInclusive, @Nullable Version max, boolean maxInclusive) {

		static VersionRange parse(String value) {
			final Matcher m = RANGE.matcher(value.strip());
			if ( m.matches() ) {
				return new VersionRange(Version.parse(m.group(2)), "[".equals(m.group(1)),
						Version.parse(m.group(3)), "]".equals(m.group(4)));
			}
			// a single version is a minimum
			return new VersionRange(Version.parse(value), true, null, false);
		}

		boolean includes(Version v) {
			final int lo = v.compareTo(min);
			if ( lo < 0 || (lo == 0 && !minInclusive) ) {
				return false;
			}
			if ( max == null ) {
				return true;
			}
			final int hi = v.compareTo(max);
			return (hi < 0 || (hi == 0 && maxInclusive));
		}

	}

	/**
	 * A package of an {@code Import-Package} or {@code Export-Package} header.
	 */
	record PackageClause(String name, Map<String, String> attributes) {

	}

	/**
	 * Constructor.
	 *
	 * @param classLoader
	 *        the class loader of the custom filter jars, whose parent is the
	 *        simulator's, to find the packages and their exports in
	 */
	public ImportCheck(ClassLoader classLoader) {
		super();
		this.classLoader = classLoader;
		this.exports = exports(classLoader);
		this.jdkPackages = jdkPackages();
	}

	/**
	 * Check a bundle's imports.
	 *
	 * @param importPackage
	 *        the bundle's {@code Import-Package} header, or {@code null}
	 * @return the problems found, as sentences
	 */
	public List<String> check(@Nullable String importPackage) {
		if ( importPackage == null || importPackage.isBlank() ) {
			return List.of();
		}
		final Set<String> missing = new TreeSet<>();
		final List<String> result = new ArrayList<>(2);
		for ( PackageClause clause : clauses(importPackage) ) {
			final String pkg = clause.name();
			if ( "optional".equals(clause.attributes().get("resolution")) || IGNORED.contains(pkg)
					|| jdkPackages.contains(pkg) ) {
				continue;
			}
			final List<Version> versions = exports.getOrDefault(pkg, List.of());
			if ( versions.isEmpty() ) {
				if ( classLoader.getResource(pkg.replace('.', '/') + "/") == null ) {
					missing.add(pkg);
				}
				continue;
			}
			final String range = clause.attributes().get("version");
			if ( range == null || !pkg.startsWith(VERSIONED_PREFIX) ) {
				continue;
			}
			final VersionRange r = VersionRange.parse(range);
			if ( versions.stream().noneMatch(r::includes) ) {
				result.add("It imports %s %s, but the simulator has version %s.".formatted(pkg, range,
						versions.stream().map(Version::toString).distinct()
								.reduce((a, b) -> a + ", " + b).orElse("")));
			}
		}
		if ( !missing.isEmpty() ) {
			result.add(0, ("It imports packages that the simulator and the other jars do not have: %s. "
					+ "If its filters use them, add jars that have them to the custom filters path.")
							.formatted(String.join(", ", missing)));
		}
		return result;
	}

	/**
	 * Parse the clauses of an {@code Import-Package} or {@code Export-Package}
	 * header, where a clause can name more than one package.
	 *
	 * @param header
	 *        the header
	 * @return the packages, with their attributes and directives
	 */
	static List<PackageClause> clauses(String header) {
		final List<PackageClause> result = new ArrayList<>(16);
		for ( String clause : CLAUSE_SEPARATOR.split(header, -1) ) {
			final List<String> names = new ArrayList<>(1);
			final Map<String, String> attrs = new LinkedHashMap<>(2);
			for ( String part : PART_SEPARATOR.split(clause, -1) ) {
				final String p = part.strip();
				final int eq = p.indexOf('=');
				if ( eq < 0 ) {
					if ( !p.isEmpty() ) {
						names.add(p);
					}
					continue;
				}
				final String key = p.substring(0, eq).replaceFirst(":$", "").strip();
				String value = p.substring(eq + 1).strip();
				if ( value.length() > 1 && value.startsWith("\"") && value.endsWith("\"") ) {
					value = value.substring(1, value.length() - 1);
				}
				attrs.put(key, value);
			}
			final Map<String, String> a = Collections.unmodifiableMap(attrs);
			for ( String name : names ) {
				result.add(new PackageClause(name, a));
			}
		}
		return result;
	}

	/**
	 * Get the packages the jars of a class loader export, with their versions.
	 */
	private static Map<String, List<Version>> exports(ClassLoader classLoader) {
		final Map<String, List<Version>> result = new HashMap<>(256);
		final Enumeration<URL> manifests;
		try {
			manifests = classLoader.getResources("META-INF/MANIFEST.MF");
		} catch ( IOException e ) {
			return result;
		}
		while ( manifests.hasMoreElements() ) {
			final URL url = manifests.nextElement();
			final String header;
			try (InputStream in = url.openStream()) {
				header = new Manifest(in).getMainAttributes().getValue("Export-Package");
			} catch ( IOException e ) {
				continue;
			}
			if ( header == null ) {
				continue;
			}
			for ( PackageClause clause : clauses(header) ) {
				final String v = clause.attributes().get("version");
				result.computeIfAbsent(clause.name(), _ -> new ArrayList<>(1))
						.add(v != null ? Version.parse(v) : new Version(0, 0, 0, ""));
			}
		}
		return result;
	}

	/** Get the packages of the JDK's modules. */
	private static Set<String> jdkPackages() {
		final Set<String> result = new HashSet<>(1024);
		for ( Module m : ModuleLayer.boot().modules() ) {
			result.addAll(m.getPackages());
		}
		return result;
	}

}
