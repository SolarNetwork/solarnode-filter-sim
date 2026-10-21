/* ==================================================================
 * CaptureException.java - 21 Oct 2026 2:30:00 pm
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

package s10k.node.sim.filter.capture;

import java.io.Serial;

/**
 * A problem that prevents a datum capture from starting, such as a node that
 * cannot be reached or does not accept the login.
 *
 * @author matt
 * @version 1.0
 */
public class CaptureException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 4412790150627845125L;

	/**
	 * Constructor.
	 *
	 * @param message
	 *        the message
	 */
	public CaptureException(String message) {
		super(message);
	}

	/**
	 * Constructor.
	 *
	 * @param message
	 *        the message
	 * @param cause
	 *        the cause
	 */
	public CaptureException(String message, Throwable cause) {
		super(message, cause);
	}

}
