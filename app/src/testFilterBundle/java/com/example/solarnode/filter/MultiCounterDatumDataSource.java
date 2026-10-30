/* ==================================================================
 * MultiCounterDatumDataSource.java - 30 Oct 2026 8:41:12 pm
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

package com.example.solarnode.filter;

import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.solarnetwork.domain.datum.DatumSamples;
import net.solarnetwork.domain.datum.DatumSamplesType;
import net.solarnetwork.node.domain.datum.NodeDatum;
import net.solarnetwork.node.domain.datum.SimpleDatum;
import net.solarnetwork.node.service.MultiDatumDataSource;
import net.solarnetwork.node.service.support.DatumDataSourceSupport;
import net.solarnetwork.settings.SettingSpecifier;
import net.solarnetwork.settings.SettingSpecifierProvider;
import net.solarnetwork.settings.support.BasicTextFieldSettingSpecifier;

/**
 * A custom multi datum source for the custom filter tests, which counts its
 * polls for each of its source IDs.
 *
 * <p>
 * Each poll returns a datum for each source ID, with the instantaneous
 * {@code count} property, which goes up by the step each poll, and the
 * instantaneous {@code index} property, the source ID's position (from
 * {@code 0}). Its datum are dated by its clock.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class MultiCounterDatumDataSource extends DatumDataSourceSupport
		implements MultiDatumDataSource, SettingSpecifierProvider {

	/** The setting UID. */
	public static final String SETTING_UID = "com.example.solarnode.datum.counter.multi";

	private String sourceIds;
	private int step = 1;
	private int count;

	/**
	 * Constructor, using the system clock.
	 */
	public MultiCounterDatumDataSource() {
		super();
	}

	/**
	 * Constructor.
	 *
	 * @param clock
	 *        the clock
	 */
	public MultiCounterDatumDataSource(InstantSource clock) {
		super(clock);
	}

	@Override
	public Class<? extends NodeDatum> getMultiDatumType() {
		return NodeDatum.class;
	}

	@Override
	public Collection<NodeDatum> readMultipleDatum() {
		final Set<String> ids = ids();
		if ( ids.isEmpty() ) {
			return List.of();
		}
		count += step;
		final Instant now = getClock().instant();
		final List<NodeDatum> result = new ArrayList<>(ids.size());
		int index = 0;
		for ( String id : ids ) {
			final DatumSamples samples = new DatumSamples();
			samples.putSampleValue(DatumSamplesType.Instantaneous, "count", count);
			samples.putSampleValue(DatumSamplesType.Instantaneous, "index", index++);
			result.add(SimpleDatum.nodeDatum(id, now, samples));
		}
		return result;
	}

	@Override
	public Collection<String> publishedSourceIds() {
		return ids();
	}

	private Set<String> ids() {
		final Set<String> result = new LinkedHashSet<>(2);
		final String ids = resolvePlaceholders(sourceIds);
		if ( ids != null ) {
			for ( String id : ids.split(",") ) {
				if ( !id.isBlank() ) {
					result.add(id.trim());
				}
			}
		}
		return result;
	}

	@Override
	public String getSettingUid() {
		return SETTING_UID;
	}

	@Override
	public List<SettingSpecifier> getSettingSpecifiers() {
		final List<SettingSpecifier> results = new ArrayList<>(getIdentifiableSettingSpecifiers());
		results.add(new BasicTextFieldSettingSpecifier("sourceIds", null));
		results.add(new BasicTextFieldSettingSpecifier("step", "1"));
		return results;
	}

	/**
	 * Get the source IDs.
	 *
	 * @return the comma-delimited source IDs
	 */
	public String getSourceIds() {
		return sourceIds;
	}

	/**
	 * Set the source IDs.
	 *
	 * @param sourceIds
	 *        the comma-delimited source IDs to set
	 */
	public void setSourceIds(String sourceIds) {
		this.sourceIds = sourceIds;
	}

	/**
	 * Get the step.
	 *
	 * @return the step
	 */
	public int getStep() {
		return step;
	}

	/**
	 * Set the amount to count up each poll.
	 *
	 * @param step
	 *        the step to set
	 */
	public void setStep(int step) {
		this.step = step;
	}

}
