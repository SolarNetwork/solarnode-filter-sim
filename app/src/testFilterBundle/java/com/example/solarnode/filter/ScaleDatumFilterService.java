/* ==================================================================
 * ScaleDatumFilterService.java - 21 Oct 2026 4:12:08 pm
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

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import com.example.solarnode.filter.lib.Scaling;
import net.solarnetwork.domain.datum.Datum;
import net.solarnetwork.domain.datum.DatumSamples;
import net.solarnetwork.domain.datum.DatumSamplesOperations;
import net.solarnetwork.domain.datum.DatumSamplesType;
import net.solarnetwork.node.dao.TransientSettingDao;
import net.solarnetwork.node.service.DatumQueue;
import net.solarnetwork.node.service.support.BaseDatumFilterSupport;
import net.solarnetwork.service.DatumFilterService;
import net.solarnetwork.service.OptionalService;
import net.solarnetwork.settings.SettingSpecifier;
import net.solarnetwork.settings.SettingSpecifierProvider;
import net.solarnetwork.settings.support.BasicTextFieldSettingSpecifier;

/**
 * A custom filter for the custom filter tests, which multiplies an
 * instantaneous property by a factor.
 *
 * <p>
 * It also adds status properties that show how the simulator created and ran
 * it: {@code scaledAt} (the time of its clock), {@code started} (its init
 * method was called), {@code contextLoader} (it ran with its own class loader
 * as the thread context class loader), and {@code transientSettingDao} (its
 * blueprint property was set).
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class ScaleDatumFilterService extends BaseDatumFilterSupport
		implements DatumFilterService, SettingSpecifierProvider {

	/** The setting UID. */
	public static final String SETTING_UID = "com.example.solarnode.filter.scale";

	private final Clock clock;
	private final OptionalService<DatumQueue> datumQueue;
	private TransientSettingDao transientSettingDao;
	private Executor executor;
	private String propertyName;
	private BigDecimal factor = BigDecimal.ONE;
	private boolean started;

	/**
	 * Constructor, using the system clock.
	 *
	 * @param datumQueue
	 *        the datum queue
	 */
	public ScaleDatumFilterService(OptionalService<DatumQueue> datumQueue) {
		this(Clock.systemUTC(), datumQueue);
	}

	/**
	 * Constructor.
	 *
	 * @param clock
	 *        the clock
	 * @param datumQueue
	 *        the datum queue
	 */
	public ScaleDatumFilterService(Clock clock, OptionalService<DatumQueue> datumQueue) {
		super();
		this.clock = clock;
		this.datumQueue = datumQueue;
	}

	/**
	 * Start the filter (the blueprint's init method).
	 */
	public void startup() {
		started = true;
	}

	/**
	 * Stop the filter (the blueprint's destroy method).
	 */
	public void shutdown() {
		started = false;
	}

	@Override
	public DatumSamplesOperations filter(Datum datum, DatumSamplesOperations samples,
			Map<String, Object> parameters) {
		final long start = incrementInputStats();
		final BigDecimal value = (propertyName != null
				? samples.getSampleBigDecimal(DatumSamplesType.Instantaneous, propertyName)
				: null);
		if ( value == null || !conditionsMatch(datum, samples, parameters) ) {
			incrementIgnoredStats(start);
			return samples;
		}
		final DatumSamples out = new DatumSamples(samples);
		out.putSampleValue(DatumSamplesType.Instantaneous, propertyName, Scaling.scale(value, factor));
		out.putSampleValue(DatumSamplesType.Status, "scaledAt", clock.instant().toString());
		out.putSampleValue(DatumSamplesType.Status, "started", started);
		out.putSampleValue(DatumSamplesType.Status, "contextLoader",
				Thread.currentThread().getContextClassLoader() == getClass().getClassLoader());
		out.putSampleValue(DatumSamplesType.Status, "transientSettingDao", transientSettingDao != null);
		if ( executor != null ) {
			final Thread caller = Thread.currentThread();
			executor.execute(() -> out.putSampleValue(DatumSamplesType.Status, "executedInline",
					Thread.currentThread() == caller));
		}
		incrementStats(start, samples, out);
		return out;
	}

	@Override
	public String getSettingUid() {
		return SETTING_UID;
	}

	@Override
	public List<SettingSpecifier> getSettingSpecifiers() {
		final List<SettingSpecifier> result = baseIdentifiableSettings("");
		populateBaseSampleTransformSupportSettings(result);
		result.add(new BasicTextFieldSettingSpecifier("propertyName", null));
		result.add(new BasicTextFieldSettingSpecifier("factor", "1"));
		return result;
	}

	/**
	 * Get the datum queue.
	 *
	 * @return the datum queue
	 */
	public OptionalService<DatumQueue> getDatumQueue() {
		return datumQueue;
	}

	/**
	 * Set the transient setting DAO.
	 *
	 * @param transientSettingDao
	 *        the DAO
	 */
	public void setTransientSettingDao(TransientSettingDao transientSettingDao) {
		this.transientSettingDao = transientSettingDao;
	}

	/**
	 * Set the executor, for tasks run apart from the datum.
	 *
	 * @param executor
	 *        the executor
	 */
	public void setExecutor(Executor executor) {
		this.executor = executor;
	}

	/**
	 * Get the name of the instantaneous property to scale.
	 *
	 * @return the property name
	 */
	public String getPropertyName() {
		return propertyName;
	}

	/**
	 * Set the name of the instantaneous property to scale.
	 *
	 * @param propertyName
	 *        the property name
	 */
	public void setPropertyName(String propertyName) {
		this.propertyName = propertyName;
	}

	/**
	 * Get the factor.
	 *
	 * @return the factor
	 */
	public BigDecimal getFactor() {
		return factor;
	}

	/**
	 * Set the factor.
	 *
	 * @param factor
	 *        the factor
	 */
	public void setFactor(BigDecimal factor) {
		this.factor = factor;
	}

}
