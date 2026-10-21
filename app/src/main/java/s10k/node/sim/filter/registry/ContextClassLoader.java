/* ==================================================================
 * ContextClassLoader.java - 21 Oct 2026 4:12:08 pm
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

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Run code with a thread context class loader, for custom filters.
 *
 * <p>
 * Code that loads classes by name, such as SpEL's {@code T(...)}, a
 * {@code ServiceLoader}, or Jackson's module lookup, uses the thread context
 * class loader, which only finds a custom filter's classes when it is the
 * filter bundle's class loader.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class ContextClassLoader {

	private ContextClassLoader() {
		// not available
	}

	/**
	 * Call a supplier with a thread context class loader.
	 *
	 * @param <T>
	 *        the result type
	 * @param classLoader
	 *        the class loader, or {@code null} to keep the current one
	 * @param supplier
	 *        the supplier
	 * @return the supplier's result
	 */
	public static <T extends @Nullable Object> T call(@Nullable ClassLoader classLoader,
			Supplier<T> supplier) {
		if ( classLoader == null ) {
			return supplier.get();
		}
		final Thread thread = Thread.currentThread();
		final ClassLoader previous = thread.getContextClassLoader();
		thread.setContextClassLoader(classLoader);
		try {
			return supplier.get();
		} finally {
			thread.setContextClassLoader(previous);
		}
	}

	/**
	 * Run a task with a thread context class loader.
	 *
	 * @param classLoader
	 *        the class loader, or {@code null} to keep the current one
	 * @param task
	 *        the task
	 */
	public static void run(@Nullable ClassLoader classLoader, Runnable task) {
		call(classLoader, () -> {
			task.run();
			return null;
		});
	}

}
