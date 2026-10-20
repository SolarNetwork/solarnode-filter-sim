/* ==================================================================
 * SkipReasons.java - 21 Oct 2026 9:18:05 am
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

package s10k.node.sim.filter.sim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;
import s10k.node.sim.filter.domain.SkipReason;
import s10k.node.sim.filter.domain.SkipReason.Condition;
import net.solarnetwork.domain.datum.Datum;
import net.solarnetwork.domain.datum.DatumSamplesOperations;
import net.solarnetwork.node.service.OperationalModesService;
import net.solarnetwork.node.service.support.BaseDatumFilterSupport;

/**
 * Work out why a filter ignored a datum.
 *
 * <p>
 * SolarNode filters test their conditions in
 * {@code BaseDatumFilterSupport.conditionsMatch()}, whose tests are protected,
 * so this repeats them with the filter's public settings, the same way: the
 * Source ID pattern is case-insensitive and may match anywhere in the source
 * ID, the Required Mode is tested with the filter's operational modes service,
 * and the Required Tag is a comma-delimited list of tags, any of which may
 * match the samples or the datum, and each of which may start with {@code !} to
 * require that the tag is not present.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class SkipReasons {

	private static final Pattern TAG_COMMA_DELIM = Pattern.compile("\\s*,\\s*");

	private SkipReasons() {
		// not available
	}

	/**
	 * Get the conditions a datum did not meet.
	 *
	 * @param filter
	 *        the filter that ignored the datum
	 * @param datum
	 *        the datum
	 * @param samples
	 *        the samples passed to the filter
	 * @return the conditions not met, or a single {@link Condition#Other}
	 *         reason if they were all met, as the filter ignored the datum for
	 *         its own reasons
	 */
	public static List<SkipReason> of(Object filter, @Nullable Datum datum,
			@Nullable DatumSamplesOperations samples) {
		final List<SkipReason> reasons = new ArrayList<>(3);
		if ( filter instanceof BaseDatumFilterSupport f ) {
			final SkipReason source = sourceId(f, datum);
			if ( source != null ) {
				reasons.add(source);
			}
			final SkipReason mode = requiredMode(f);
			if ( mode != null ) {
				reasons.add(mode);
			}
			final SkipReason tag = requiredTag(f, datum, samples);
			if ( tag != null ) {
				reasons.add(tag);
			}
		}
		if ( reasons.isEmpty() ) {
			reasons.add(new SkipReason(Condition.Other, null, null));
		}
		return reasons;
	}

	private static @Nullable SkipReason sourceId(BaseDatumFilterSupport f, @Nullable Datum datum) {
		final String pattern = f.getSourceId();
		if ( pattern == null ) {
			return null;
		}
		final Pattern pat;
		try {
			pat = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
		} catch ( PatternSyntaxException e ) {
			// SolarNode ignores an invalid pattern
			return null;
		}
		final String sourceId = (datum != null ? datum.getSourceId() : null);
		if ( sourceId != null && pat.matcher(sourceId).find() ) {
			return null;
		}
		return new SkipReason(Condition.SourceId, pattern, sourceId);
	}

	private static @Nullable SkipReason requiredMode(BaseDatumFilterSupport f) {
		final String mode = f.getRequiredOperationalMode();
		if ( mode == null ) {
			return null;
		}
		final OperationalModesService service = f.getOpModesService();
		if ( service != null && service.isOperationalModeActive(mode) ) {
			return null;
		}
		return new SkipReason(Condition.RequiredMode, mode,
				service != null ? join(service.activeOperationalModes()) : null);
	}

	// the samples and the datum's own samples are often the same object, which
	// SolarNode only tests once
	@SuppressWarnings("ReferenceEquality")
	private static @Nullable SkipReason requiredTag(BaseDatumFilterSupport f, @Nullable Datum datum,
			@Nullable DatumSamplesOperations samples) {
		final String expr = f.getRequiredTag();
		if ( expr == null || expr.isEmpty() ) {
			return null;
		}
		final String[] tags = TAG_COMMA_DELIM.split(expr.trim());
		final DatumSamplesOperations datumSamples = (datum != null ? datum.asSampleOperations() : null);
		if ( anyTagMatches(tags, samples)
				|| (datumSamples != samples && anyTagMatches(tags, datumSamples)) ) {
			return null;
		}
		final Set<String> present = new TreeSet<>();
		if ( samples != null && samples.getTags() != null ) {
			present.addAll(samples.getTags());
		}
		if ( datumSamples != null && datumSamples.getTags() != null ) {
			present.addAll(datumSamples.getTags());
		}
		return new SkipReason(Condition.RequiredTag, expr, join(present));
	}

	private static boolean anyTagMatches(String[] tags, @Nullable DatumSamplesOperations samples) {
		if ( samples == null ) {
			return false;
		}
		for ( String tag : tags ) {
			boolean inverted = tag.startsWith("!");
			String t = (inverted ? tag.substring(1) : tag);
			if ( samples.hasTag(t) != inverted ) {
				return true;
			}
		}
		return false;
	}

	private static String join(@Nullable Collection<String> values) {
		return (values != null ? String.join(", ", new TreeSet<>(values)) : "");
	}

}
