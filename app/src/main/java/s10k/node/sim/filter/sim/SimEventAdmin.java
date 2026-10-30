/* ==================================================================
 * SimEventAdmin.java - 29 Oct 2026 4:20:00 pm
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

package s10k.node.sim.filter.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.event.EventHandler;
import s10k.node.sim.filter.domain.ControlPublishMode;
import net.solarnetwork.node.domain.datum.NodeDatum;
import net.solarnetwork.node.service.DatumEvents;
import net.solarnetwork.node.service.NodeControlProvider;
import net.solarnetwork.node.service.OperationalModesService;

/**
 * The {@link EventAdmin} of the simulated runtime components and the
 * operational modes service, for one simulation run.
 *
 * <p>
 * Each event is delivered at once, on the simulation thread. A node's
 * {@code postEvent()} delivers events asynchronously, but the datum they lead
 * to are queued either way.
 * </p>
 *
 * <ul>
 * <li>{@code OPERATIONAL_MODES_CHANGED} goes to the mode handlers, the
 * Operational Mode Switches.</li>
 * <li>{@code CONTROL_INFO_CHANGED} of a component's control publishes the
 * event's datum when the control's publish mode allows it, like a Control Datum
 * Source with the {@code Change} event mode.</li>
 * <li>Other topics, including {@code CONTROL_INFO_CAPTURED} (a control value
 * was read), are ignored.</li>
 * </ul>
 *
 * @author matt
 * @version 1.0
 */
public final class SimEventAdmin implements EventAdmin {

	/**
	 * API for hearing of a component's control value changes.
	 */
	@FunctionalInterface
	public interface ControlListener {

		/**
		 * Called when a component posts a control's changed value.
		 *
		 * @param controlId
		 *        the control ID
		 * @param datum
		 *        the control's datum
		 * @param published
		 *        {@code true} if the datum is published
		 */
		void controlChanged(String controlId, NodeDatum datum, boolean published);

	}

	private record Publication(ControlPublishMode publish, boolean persist) {

	}

	private final List<EventHandler> modeHandlers = new ArrayList<>(2);
	private final Map<String, Publication> publications = new HashMap<>(4);
	private final List<ControlListener> listeners = new ArrayList<>(2);
	private SimNodeControlService.@Nullable Publisher publisher;
	private int quiet;

	/**
	 * Add a handler of {@code OPERATIONAL_MODES_CHANGED} events.
	 *
	 * @param handler
	 *        the handler
	 */
	public void addModeHandler(EventHandler handler) {
		modeHandlers.add(handler);
	}

	/**
	 * Add a component's control, whose change events publish its datum.
	 *
	 * @param controlId
	 *        the control ID, as the component's datum have it
	 * @param publish
	 *        when the control publishes a datum; as components post only
	 *        changes, any mode but {@code Never} publishes each one
	 * @param persist
	 *        {@code true} to persist the datum
	 */
	public void addControl(String controlId, ControlPublishMode publish, boolean persist) {
		publications.put(controlId, new Publication(publish, persist));
	}

	/**
	 * Add a listener of control changes.
	 *
	 * @param listener
	 *        the listener
	 */
	public void addControlListener(ControlListener listener) {
		listeners.add(listener);
	}

	/**
	 * Set the publisher of control datum.
	 *
	 * @param publisher
	 *        the publisher to set, or {@code null} to publish nothing
	 */
	public void setPublisher(SimNodeControlService.@Nullable Publisher publisher) {
		this.publisher = publisher;
	}

	/**
	 * Run something without publishing or reporting control changes, such as
	 * starting a service whose first events tell components of their starting
	 * state.
	 *
	 * @param task
	 *        the task to run
	 */
	public void quietly(Runnable task) {
		quiet++;
		try {
			task.run();
		} finally {
			quiet--;
		}
	}

	@Override
	public void postEvent(Event event) {
		deliver(event);
	}

	@Override
	public void sendEvent(Event event) {
		deliver(event);
	}

	private void deliver(Event event) {
		final String topic = event.getTopic();
		if ( OperationalModesService.EVENT_TOPIC_OPERATIONAL_MODES_CHANGED.equals(topic) ) {
			for ( EventHandler handler : List.copyOf(modeHandlers) ) {
				handler.handleEvent(event);
			}
		} else if ( NodeControlProvider.EVENT_TOPIC_CONTROL_INFO_CHANGED.equals(topic) && quiet == 0
				&& event.getProperty(DatumEvents.DATUM_PROPERTY) instanceof NodeDatum datum ) {
			controlChanged(datum);
		}
	}

	private void controlChanged(NodeDatum datum) {
		final String controlId = datum.getSourceId();
		if ( controlId == null ) {
			return;
		}
		final Publication publication = publications.get(controlId);
		final SimNodeControlService.Publisher p = this.publisher;
		final boolean publish = (p != null && publication != null
				&& publication.publish() != ControlPublishMode.Never);
		for ( ControlListener listener : listeners ) {
			listener.controlChanged(controlId, datum, publish);
		}
		if ( publish && p != null && publication != null ) {
			p.publish(controlId, datum, publication.persist());
		}
	}

}
