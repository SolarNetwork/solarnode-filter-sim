/* ==================================================================
 * CaptureInfo.java - 21 Oct 2026 2:30:00 pm
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
 * A datum capture that started.
 *
 * @param id
 *        the capture's ID, for its events and to end it
 * @param url
 *        the node's web address
 * @param topic
 *        the node's WebSocket topic the capture subscribed to
 * @author matt
 * @version 1.0
 */
public record CaptureInfo(String id, String url, String topic) {

}
