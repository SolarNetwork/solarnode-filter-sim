/* ==================================================================
 * FilterRunStats.java - 29 Oct 2026 8:30:00 pm
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

/**
 * The datum a filter processed in a run, for the counts the canvas shows on it.
 *
 * @param in
 *        the datum that reached the filter
 * @param out
 *        the datum that went on from the filter: those it did not discard, and
 *        that did not fail
 * @param dropped
 *        the datum the filter discarded
 * @param changed
 *        the datum the filter changed
 * @param skipped
 *        the datum the filter skipped
 * @param errors
 *        the datum the filter failed on
 * @author matt
 * @version 1.0
 */
public record FilterRunStats(int in, int out, int dropped, int changed, int skipped, int errors) {

}
