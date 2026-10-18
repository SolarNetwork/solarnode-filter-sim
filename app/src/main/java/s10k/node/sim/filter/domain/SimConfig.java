/* ==================================================================
 * SimConfig.java - 18 Oct 2026 8:56:20 am
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

package s10k.node.sim.filter.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A complete filter configuration: every filter instance, and the main filter
 * chain on the Datum Queue.
 *
 * @param filters
 *        the filter configurations
 * @param mainChainId
 *        the {@code id} of the filter the Datum Queue applies (normally the
 *        main filter chain), or {@code null} if none
 * @param datumQueueComment
 *        the comment lines that introduce the Datum Queue setting in a settings
 *        CSV file, each a {@code #} comment or a blank row, separated by line
 *        feeds, or {@code null}
 * @author matt
 * @version 1.0
 */
public record SimConfig(@Nullable List<FilterConfig> filters, @Nullable String mainChainId,
		@Nullable String datumQueueComment) {

	/**
	 * Constructor, without a Datum Queue comment.
	 *
	 * @param filters
	 *        the filter configurations
	 * @param mainChainId
	 *        the {@code id} of the filter the Datum Queue applies, or
	 *        {@code null} if none
	 */
	public SimConfig(@Nullable List<FilterConfig> filters, @Nullable String mainChainId) {
		this(filters, mainChainId, null);
	}

	/**
	 * Get the filters, never {@code null}.
	 *
	 * @return the filters
	 */
	public List<FilterConfig> filtersOrEmpty() {
		return (filters != null ? filters : List.of());
	}

}
