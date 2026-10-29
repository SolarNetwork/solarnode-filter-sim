/* ==================================================================
 * TraceStore.java - 29 Oct 2026 8:45:00 pm
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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Datum traces as JSON, compressed in blocks, so a long run's traces take
 * little memory, and each can still be read.
 *
 * <p>
 * Traces are added in processing order, and read by their position. A block of
 * {@link #BLOCK_SIZE} traces is compressed together, as one trace is too small
 * to compress well. The block read last is kept, as traces are often read in
 * order.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
final class TraceStore {

	/** The number of traces compressed together. */
	static final int BLOCK_SIZE = 64;

	/**
	 * A compressed block.
	 *
	 * @param data
	 *        the compressed traces
	 * @param length
	 *        the length of the traces, uncompressed
	 * @param ends
	 *        the end offset of each trace, uncompressed
	 */
	@SuppressWarnings("ArrayRecordComponent")
	private record Block(byte[] data, int length, int[] ends) {

	}

	private final List<Block> blocks = new ArrayList<>(8);
	private final ByteArrayOutputStream pending = new ByteArrayOutputStream(64 * 1024);
	private final int[] pendingEnds = new int[BLOCK_SIZE];
	private int pendingCount;
	private int size;
	private long compressedSize;

	private int cachedBlock = -1;
	private byte[] cachedData = new byte[0];

	/**
	 * Add a trace.
	 *
	 * @param json
	 *        the trace, as JSON
	 */
	synchronized void add(byte[] json) {
		pending.writeBytes(json);
		pendingEnds[pendingCount++] = pending.size();
		size++;
		if ( pendingCount == BLOCK_SIZE ) {
			compressPending();
		}
	}

	/**
	 * Get the number of traces.
	 *
	 * @return the number of traces
	 */
	synchronized int size() {
		return size;
	}

	/**
	 * Get the size of the compressed blocks.
	 *
	 * @return the size, in bytes, without the traces not compressed yet
	 */
	synchronized long compressedSize() {
		return compressedSize;
	}

	/**
	 * Get a trace.
	 *
	 * @param position
	 *        the trace's position, from 0
	 * @return the trace, as JSON
	 * @throws IndexOutOfBoundsException
	 *         if there is no trace at {@code position}
	 */
	synchronized String get(int position) {
		final int b = position / BLOCK_SIZE;
		final int i = position % BLOCK_SIZE;
		if ( position < 0 || position >= size ) {
			throw new IndexOutOfBoundsException(position);
		}
		final byte[] data;
		final int[] ends;
		if ( b < blocks.size() ) {
			final Block block = blocks.get(b);
			if ( cachedBlock != b ) {
				cachedData = inflate(block);
				cachedBlock = b;
			}
			data = cachedData;
			ends = block.ends();
		} else {
			data = pending.toByteArray();
			ends = pendingEnds;
		}
		final int start = (i > 0 ? ends[i - 1] : 0);
		return new String(data, start, ends[i] - start, StandardCharsets.UTF_8);
	}

	private void compressPending() {
		final byte[] raw = pending.toByteArray();
		final Deflater deflater = new Deflater();
		try {
			deflater.setInput(raw);
			deflater.finish();
			final ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 4 + 64);
			final byte[] buf = new byte[8192];
			while ( !deflater.finished() ) {
				out.write(buf, 0, deflater.deflate(buf));
			}
			final byte[] data = out.toByteArray();
			blocks.add(new Block(data, raw.length, Arrays.copyOf(pendingEnds, pendingCount)));
			compressedSize += data.length;
		} finally {
			deflater.end();
		}
		pending.reset();
		pendingCount = 0;
	}

	private static byte[] inflate(Block block) {
		final Inflater inflater = new Inflater();
		try {
			inflater.setInput(block.data());
			final byte[] result = new byte[block.length()];
			int n = 0;
			while ( n < result.length && !inflater.finished() ) {
				n += inflater.inflate(result, n, result.length - n);
			}
			return result;
		} catch ( DataFormatException e ) {
			throw new IllegalStateException("A stored trace block is corrupt.", e);
		} finally {
			inflater.end();
		}
	}

}
