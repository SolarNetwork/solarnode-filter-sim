/* ==================================================================
 * Scaling.java - 21 Oct 2026 4:12:08 pm
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

package com.example.solarnode.filter.lib;

import java.math.BigDecimal;

/**
 * A library class that the test filter bundle embeds as a jar, through its
 * {@code Bundle-ClassPath}.
 *
 * @author matt
 * @version 1.0
 */
public final class Scaling {

	private Scaling() {
		// not available
	}

	/**
	 * Scale a value.
	 *
	 * @param value
	 *        the value
	 * @param factor
	 *        the factor
	 * @return the scaled value
	 */
	public static BigDecimal scale(BigDecimal value, BigDecimal factor) {
		return value.multiply(factor);
	}

}
