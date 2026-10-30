/* ==================================================================
 * CounterDatumDataSource.java - 30 Oct 2026 5:40:12 pm
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

import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.solarnetwork.domain.datum.DatumSamples;
import net.solarnetwork.domain.datum.DatumSamplesType;
import net.solarnetwork.node.domain.datum.NodeDatum;
import net.solarnetwork.node.domain.datum.SimpleDatum;
import net.solarnetwork.node.service.DatumDataSource;
import net.solarnetwork.node.service.support.DatumDataSourceSupport;
import net.solarnetwork.settings.SettingSpecifier;
import net.solarnetwork.settings.SettingSpecifierProvider;
import net.solarnetwork.settings.support.BasicTextFieldSettingSpecifier;

/**
 * A custom datum source for the custom filter tests, which counts its polls.
 *
 * <p>
 * Each datum has the instantaneous {@code count} property, which goes up by the
 * step each poll, and status properties that show how the simulator created and
 * ran it: {@code started} (its init method was called), and
 * {@code contextLoader} (it ran with its own class loader as the thread context
 * class loader). Its datum are dated by its clock.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class CounterDatumDataSource extends DatumDataSourceSupport
		implements DatumDataSource, SettingSpecifierProvider {

	/** The setting UID. */
	public static final String SETTING_UID = "com.example.solarnode.datum.counter";

	private String sourceId;
	private int step = 1;
	private int count;
	private boolean started;

	/**
	 * Constructor, using the system clock.
	 */
	public CounterDatumDataSource() {
		super();
	}

	/**
	 * Constructor.
	 *
	 * @param clock
	 *        the clock
	 */
	public CounterDatumDataSource(InstantSource clock) {
		super(clock);
	}

	/**
	 * The init method.
	 */
	public void startup() {
		started = true;
	}

	@Override
	public Class<? extends NodeDatum> getDatumType() {
		return NodeDatum.class;
	}

	@Override
	public NodeDatum readCurrentDatum() {
		final String id = resolvePlaceholders(sourceId);
		if ( id == null || id.isBlank() ) {
			return null;
		}
		count += step;
		final DatumSamples samples = new DatumSamples();
		samples.putSampleValue(DatumSamplesType.Instantaneous, "count", count);
		samples.putSampleValue(DatumSamplesType.Status, "started", String.valueOf(started));
		samples.putSampleValue(DatumSamplesType.Status, "contextLoader", String
				.valueOf(Thread.currentThread().getContextClassLoader() == getClass().getClassLoader()));
		return SimpleDatum.nodeDatum(id, getClock().instant(), samples);
	}

	@Override
	public Collection<String> publishedSourceIds() {
		final String id = resolvePlaceholders(sourceId);
		return (id != null ? Set.of(id) : Set.of());
	}

	@Override
	public String getSettingUid() {
		return SETTING_UID;
	}

	@Override
	public List<SettingSpecifier> getSettingSpecifiers() {
		final List<SettingSpecifier> results = new ArrayList<>(getIdentifiableSettingSpecifiers());
		results.add(new BasicTextFieldSettingSpecifier("sourceId", null));
		results.add(new BasicTextFieldSettingSpecifier("step", "1"));
		return results;
	}

	/**
	 * Get the source ID.
	 *
	 * @return the source ID
	 */
	public String getSourceId() {
		return sourceId;
	}

	/**
	 * Set the source ID.
	 *
	 * @param sourceId
	 *        the source ID to set
	 */
	public void setSourceId(String sourceId) {
		this.sourceId = sourceId;
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
