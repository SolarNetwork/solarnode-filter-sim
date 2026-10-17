/* ==================================================================
 * MessageSources.java - 18 Oct 2026 8:56:20 am
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

package s10k.node.sim.filter.registry;

import java.nio.charset.StandardCharsets;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import net.solarnetwork.support.PrefixedMessageSource;

/**
 * Helpers for building filter message sources, like the SolarNode blueprint
 * wiring does.
 *
 * <p>
 * The SolarNode blueprints use {@code ResourceBundleMessageSource}. These use
 * {@link ReloadableResourceBundleMessageSource} with class path resources
 * instead, which load the same {@code .properties} files but need only resource
 * hints (not resource bundle registrations) in a native image.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class MessageSources {

	private MessageSources() {
		// not available
	}

	/**
	 * Create a message source from resource bundle base names.
	 *
	 * @param basenames
	 *        the base names, as class names like the SolarNode blueprints use,
	 *        for example
	 *        {@code net.solarnetwork.node.datum.filter.expr.ExpressionDatumFilterService}
	 * @return the message source
	 */
	public static ReloadableResourceBundleMessageSource messageSource(String... basenames) {
		ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
		String[] paths = new String[basenames.length];
		for ( int i = 0; i < basenames.length; i++ ) {
			paths[i] = "classpath:" + basenames[i].replace('.', '/');
		}
		ms.setBasenames(paths);
		ms.setDefaultEncoding(StandardCharsets.UTF_8.name());
		ms.setFallbackToSystemLocale(false);
		return ms;
	}

	/**
	 * Create a message source from resource bundle base names, with a parent
	 * message source for prefixed keys.
	 *
	 * <p>
	 * This is how the blueprints give nested list items their own messages: a
	 * key like {@code controlConfigsItem.name.key} is looked up as
	 * {@code name.key} in the parent's delegate.
	 * </p>
	 *
	 * @param prefix
	 *        the key prefix the parent handles
	 * @param delegate
	 *        the message source for prefixed keys
	 * @param basenames
	 *        the base names
	 * @return the message source
	 */
	public static MessageSource messageSource(String prefix, MessageSource delegate,
			String... basenames) {
		ReloadableResourceBundleMessageSource ms = messageSource(basenames);
		ms.setParentMessageSource(prefixed(prefix, delegate));
		return ms;
	}

	/**
	 * Create a message source that handles keys with a prefix.
	 *
	 * @param prefix
	 *        the key prefix
	 * @param delegate
	 *        the message source to look up keys in, with the prefix removed
	 * @return the message source
	 */
	public static PrefixedMessageSource prefixed(String prefix, MessageSource delegate) {
		PrefixedMessageSource ms = new PrefixedMessageSource();
		ms.setPrefix(prefix);
		ms.setDelegate(delegate);
		return ms;
	}

}
