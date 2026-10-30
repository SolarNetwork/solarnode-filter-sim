/* ==================================================================
 * JsonArrayStore.java - 29 Oct 2026 8:45:00 pm
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

package s10k.node.sim.filter.run;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * A JSON array, compressed as its elements are added, to write out whole.
 *
 * @author matt
 * @version 1.1
 */
final class JsonArrayStore {

	private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(8 * 1024);
	private final DeflaterOutputStream out = new DeflaterOutputStream(bytes);
	private int count;
	private boolean finished;

	/**
	 * Add an element.
	 *
	 * @param json
	 *        the element, as JSON
	 * @throws IllegalStateException
	 *         if the array is finished
	 */
	synchronized void add(byte[] json) {
		if ( finished ) {
			throw new IllegalStateException("The array is finished.");
		}
		try {
			if ( count > 0 ) {
				out.write(',');
			}
			out.write(json);
		} catch ( IOException e ) {
			throw new UncheckedIOException(e);
		}
		count++;
	}

	/**
	 * Get the number of elements.
	 *
	 * @return the number of elements
	 */
	synchronized int count() {
		return count;
	}

	/**
	 * Finish the array, so it can be written, and release the compressor.
	 */
	synchronized void finish() {
		if ( finished ) {
			return;
		}
		finished = true;
		try {
			out.close();
		} catch ( IOException e ) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Write the array.
	 *
	 * @param target
	 *        the stream to write to
	 * @throws IOException
	 *         if writing fails
	 * @throws IllegalStateException
	 *         if the array is not finished
	 */
	void writeTo(OutputStream target) throws IOException {
		try (InputStream in = read()) {
			in.transferTo(target);
		}
	}

	/**
	 * Read the array.
	 *
	 * @return the array, as JSON
	 * @throws IllegalStateException
	 *         if the array is not finished
	 */
	InputStream read() {
		final byte[] data;
		synchronized ( this ) {
			if ( !finished ) {
				throw new IllegalStateException("The array is not finished.");
			}
			data = bytes.toByteArray();
		}
		// @formatter:off
		return new SequenceInputStream(Collections.enumeration(List.of(
				new ByteArrayInputStream(new byte[] { '[' }),
				new InflaterInputStream(new ByteArrayInputStream(data)),
				new ByteArrayInputStream(new byte[] { ']' }))));
		// @formatter:on
	}

}
