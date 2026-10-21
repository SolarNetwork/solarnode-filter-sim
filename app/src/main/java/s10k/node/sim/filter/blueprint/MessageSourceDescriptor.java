/* ==================================================================
 * MessageSourceDescriptor.java - 21 Oct 2026 2:05:41 pm
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

package s10k.node.sim.filter.blueprint;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The structure of a message source defined in a blueprint.
 *
 * @author matt
 * @version 1.0
 */
public sealed interface MessageSourceDescriptor {

	/**
	 * A {@code ResourceBundleMessageSource}.
	 *
	 * @param basenames
	 *        the resource bundle base names, in order
	 * @param parent
	 *        the parent message source, or {@code null}
	 */
	record Bundle(List<String> basenames, @Nullable MessageSourceDescriptor parent)
			implements MessageSourceDescriptor {

	}

	/**
	 * A {@code PrefixedMessageSource}, which handles keys that start with a
	 * prefix by looking them up without the prefix in a delegate.
	 *
	 * @param prefix
	 *        the key prefix
	 * @param delegate
	 *        the delegate message source
	 */
	record Prefixed(String prefix, MessageSourceDescriptor delegate) implements MessageSourceDescriptor {

	}

}
