/* ==================================================================
 * ControlSettings.java - 27 Oct 2026 9:12:44 am
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

package s10k.node.sim.filter.csv;

import static s10k.node.sim.filter.domain.SimulationMessage.info;
import static s10k.node.sim.filter.domain.SimulationMessage.warning;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;
import net.solarnetwork.domain.NodeControlPropertyType;
import net.solarnetwork.domain.datum.DatumSamplesType;
import s10k.node.sim.filter.domain.ControlConductorConfig;
import s10k.node.sim.filter.domain.ControlPublishMode;
import s10k.node.sim.filter.domain.NodeControlConfig;
import s10k.node.sim.filter.domain.SimulationMessage;

/**
 * The simulated controls and Control Conductors of a settings CSV file's
 * control component settings.
 *
 * <p>
 * The Mock control ({@code net.solarnetwork.node.control.mock}) is one control,
 * with its type and initial value, that publishes when its value changes. Each
 * {@code propConfigs} item of a Modbus
 * ({@code net.solarnetwork.node.control.modbus}) or BACnet
 * ({@code net.solarnetwork.node.control.bacnet}) control is a control of its
 * type, without a starting value, that publishes after every successful set, as
 * those controls post a "changed" event after every write. Each Output
 * {@code propConfigs} item of a GPIO control
 * ({@code net.solarnetwork.node.control.gpiod}) is a {@code Boolean} control
 * starting at {@code false}, which also publishes after every successful set.
 * Its Input items are left out, as they decline every set, and change only with
 * their hardware input. An Operational Mode Switch
 * ({@code net.solarnetwork.node.control.opmode.switch}) is a switch control of
 * its operational mode, which publishes when its value changes.
 * </p>
 *
 * <p>
 * The Control Datum Sources ({@code net.solarnetwork.node.datum.control}) then
 * decide whether a control publishes at all, and whether its datum are
 * persisted: a control publishes if a Control Datum Source whose Control ID
 * pattern matches it has the {@code Change} or {@code CaptureAndChange} event
 * mode, and its datum are persisted if one of those has the
 * {@code PollAndEvent} persist mode. A file without Control Datum Sources
 * publishes like one with the default settings: every control, not persisted.
 * </p>
 *
 * <p>
 * Each Control Conductor ({@code net.solarnetwork.node.control.conductor})
 * keeps its settings as they are.
 * </p>
 *
 * @author matt
 * @version 1.1
 */
final class ControlSettings {

	/** The Mock control factory UID. */
	static final String MOCK = "net.solarnetwork.node.control.mock";

	/** The Modbus control factory UID. */
	static final String MODBUS = "net.solarnetwork.node.control.modbus";

	/** The BACnet control factory UID. */
	static final String BACNET = "net.solarnetwork.node.control.bacnet";

	/** The GPIO control factory UID. */
	static final String GPIO = "net.solarnetwork.node.control.gpiod";

	/** The Operational Mode Switch factory UID. */
	static final String SWITCH = "net.solarnetwork.node.control.opmode.switch";

	/** The Control Datum Source factory UID. */
	static final String DATUM_SOURCE = "net.solarnetwork.node.datum.control";

	/** The Control Conductor factory UID. */
	static final String CONDUCTOR = "net.solarnetwork.node.control.conductor";

	/** The factory UIDs of the components this class reads. */
	static final List<String> FACTORY_UIDS = List.of(MOCK, MODBUS, BACNET, GPIO, SWITCH, DATUM_SOURCE,
			CONDUCTOR);

	private static final Pattern PROP_CONFIG_INDEX = Pattern.compile("^propConfigs\\[(\\d+)\\]\\.");

	private static final String PROP_CONFIGS_COUNT = "propConfigsCount";

	/** The GPIO Output direction code, the {@code GpioDirection.Output} key. */
	private static final int GPIO_OUTPUT = 'o';

	/**
	 * A component instance of one of the {@link #FACTORY_UIDS}.
	 *
	 * @param factoryUid
	 *        the factory UID
	 * @param instanceKey
	 *        the instance key
	 * @param settings
	 *        the settings
	 * @param factoryRow
	 *        {@code true} if the file has the instance's {@code .FACTORY} row
	 */
	record Instance(String factoryUid, String instanceKey, Map<String, String> settings,
			boolean factoryRow) {

		private String label() {
			final String name = switch (factoryUid) {
				case MOCK -> "Mock Control";
				case MODBUS -> "Modbus Control";
				case BACNET -> "BACnet Control";
				case GPIO -> "GPIO Control";
				case SWITCH -> "Operational Mode Switch";
				case DATUM_SOURCE -> "Control Datum Source";
				default -> "Control Conductor";
			};
			return name + ' ' + SimulationMessage.quoted(instanceKey);
		}

	}

	/**
	 * The result.
	 *
	 * @param controls
	 *        the controls
	 * @param conductors
	 *        the Control Conductors
	 * @param messages
	 *        messages about them
	 */
	record Result(List<NodeControlConfig> controls, List<ControlConductorConfig> conductors,
			List<SimulationMessage> messages) {

	}

	/** A Control Datum Source. */
	private record DatumSource(@Nullable Pattern controlIdRegex, boolean publish, boolean persist) {

		private boolean matches(String controlId) {
			final Pattern p = controlIdRegex;
			return (p == null || p.matcher(controlId).find());
		}

	}

	/** A control, before the Control Datum Sources decide how it publishes. */
	private record Control(String controlId, String type, @Nullable String value,
			ControlPublishMode publish, @Nullable String propertyName, @Nullable String propertyType,
			@Nullable String mode) {

	}

	private ControlSettings() {
		// not available
	}

	/**
	 * Get the factory UID of a key of one of the {@link #FACTORY_UIDS}
	 * instances.
	 *
	 * <p>
	 * This is for keys without a {@code .FACTORY} row in the file, such as the
	 * settings excerpts of SolarNode's control CSV configurers.
	 * </p>
	 *
	 * @param key
	 *        the setting key
	 * @return the factory UID, or {@code null} if the key is not
	 *         {@code <factoryUid>.<instanceKey>} for one of them
	 */
	static @Nullable String factoryUid(String key) {
		String result = null;
		for ( String uid : FACTORY_UIDS ) {
			if ( key.length() > uid.length() + 1 && key.startsWith(uid)
					&& key.charAt(uid.length()) == '.'
					&& (result == null || uid.length() > result.length()) ) {
				result = uid;
			}
		}
		return result;
	}

	/**
	 * Get the controls and Control Conductors of component instances.
	 *
	 * @param instances
	 *        the instances, in file order
	 * @return the result
	 */
	static Result of(List<Instance> instances) {
		final List<SimulationMessage> messages = new ArrayList<>(4);
		final Map<String, Control> controls = new LinkedHashMap<>(8);
		final List<DatumSource> sources = new ArrayList<>(2);
		final List<ControlConductorConfig> conductors = new ArrayList<>(2);
		for ( Instance instance : instances ) {
			if ( !instance.factoryRow() ) {
				messages.add(warning(SimulationMessage.CODE_MISSING_FACTORY,
						"The file has no %s%s row for the %s, so SolarNode would not create it. The simulator imported it anyway."
								.formatted(instance.factoryUid(), SettingsCsv.FACTORY_SUFFIX,
										instance.label()),
						null, null));
			}
			switch (instance.factoryUid()) {
				case MOCK -> {
					final Map<String, String> s = instance.settings();
					final String value = s.get("initialControlValue");
					add(controls, messages, instance, s.get("controlId"),
							mockType(s.get("controlTypeValue")), value != null ? value : "false",
							ControlPublishMode.Change, null, null);
				}
				case MODBUS, BACNET -> {
					for ( int i : propConfigIndexes(instance, messages) ) {
						final String prefix = "propConfigs[" + i + "].";
						// only the Modbus control has a property name
						add(controls, messages, instance, instance.settings().get(prefix + "controlId"),
								propertyType(instance.settings().get(prefix + "controlPropertyTypeKey")),
								null, ControlPublishMode.Update,
								MODBUS.equals(instance.factoryUid())
										? instance.settings().get(prefix + "controlPropertyName")
										: null,
								null);
					}
				}
				case GPIO -> {
					final List<String> inputs = new ArrayList<>(2);
					for ( int i : propConfigIndexes(instance, messages) ) {
						final String prefix = "propConfigs[" + i + "].";
						final String controlId = instance.settings().get(prefix + "controlId");
						if ( isGpioOutput(instance.settings().get(prefix + "gpioDirectionCode")) ) {
							add(controls, messages, instance, controlId,
									NodeControlPropertyType.Boolean.name(), "false",
									ControlPublishMode.Update,
									instance.settings().get(prefix + "propertyKey"),
									samplesType(instance.settings().get(prefix + "propertyTypeKey")));
						} else if ( controlId != null && !controlId.isBlank() ) {
							inputs.add(controlId);
						}
					}
					if ( !inputs.isEmpty() ) {
						final boolean one = inputs.size() == 1;
						messages.add(info(SimulationMessage.CODE_CONTROL_SETTINGS,
								"The %s has the Input control%s %s, which %s left out, because %s only with %s hardware input, and SolarNode declines to set %s."
										.formatted(instance.label(), one ? "" : "s",
												String.join(", ", inputs), one ? "is" : "are",
												one ? "it changes" : "they change",
												one ? "its" : "their", one ? "it" : "them"),
								null));
					}
				}
				case SWITCH -> {
					final Map<String, String> s = instance.settings();
					final String mode = s.get("mode");
					add(controls, messages, instance, s.get("controlId"),
							NodeControlPropertyType.Boolean.name(), null, ControlPublishMode.Change,
							null, null, mode != null && !mode.isBlank() ? mode : "");
				}
				case DATUM_SOURCE -> sources.add(datumSource(instance, messages));
				case CONDUCTOR -> conductors.add(new ControlConductorConfig(instance.settings()));
				default -> {
					// not a control component
				}
			}
		}

		final List<NodeControlConfig> result = new ArrayList<>(controls.size());
		final List<String> silent = new ArrayList<>(2);
		for ( Control c : controls.values() ) {
			ControlPublishMode publish = c.publish();
			boolean persist = false;
			if ( !sources.isEmpty() ) {
				boolean published = false;
				for ( DatumSource source : sources ) {
					if ( source.publish() && source.matches(c.controlId()) ) {
						published = true;
						persist |= source.persist();
					}
				}
				if ( !published ) {
					publish = ControlPublishMode.Never;
					silent.add(c.controlId());
				}
			}
			result.add(c.mode() != null
					? NodeControlConfig.modeSwitch(c.controlId(), c.mode().isEmpty() ? null : c.mode(),
							publish, persist)
					: new NodeControlConfig(c.controlId(), c.type(), c.value(), publish, persist,
							c.propertyName(), c.propertyType()));
		}

		if ( !result.isEmpty() && sources.isEmpty() ) {
			messages.add(info(SimulationMessage.CODE_CONTROL_SETTINGS,
					"The file has no Control Datum Source settings, so its controls publish their datum like a Control Datum Source with its default settings, without persisting them.",
					null));
		}
		if ( !silent.isEmpty() ) {
			messages.add(info(SimulationMessage.CODE_CONTROL_SETTINGS,
					"No Control Datum Source publishes the change events of %s, so %s never publish%s datum."
							.formatted(String.join(", ", silent), silent.size() == 1 ? "it" : "they",
									silent.size() == 1 ? "es" : ""),
					null));
		}
		return new Result(result, conductors, messages);
	}

	private static void add(Map<String, Control> controls, List<SimulationMessage> messages,
			Instance instance, @Nullable String controlId, String type, @Nullable String value,
			ControlPublishMode publish, @Nullable String propertyName, @Nullable String propertyType) {
		add(controls, messages, instance, controlId, type, value, publish, propertyName, propertyType,
				null);
	}

	/**
	 * Add a control.
	 *
	 * @param mode
	 *        for an Operational Mode Switch, its mode, or empty for none; else
	 *        {@code null}
	 */
	private static void add(Map<String, Control> controls, List<SimulationMessage> messages,
			Instance instance, @Nullable String controlId, String type, @Nullable String value,
			ControlPublishMode publish, @Nullable String propertyName, @Nullable String propertyType,
			@Nullable String mode) {
		if ( controlId == null || controlId.isBlank() ) {
			messages.add(info(SimulationMessage.CODE_INVALID_CONTROL,
					"The %s has a control without a Control ID, so it was left out."
							.formatted(instance.label()),
					null));
			return;
		}
		if ( controls.containsKey(controlId) ) {
			messages.add(warning(SimulationMessage.CODE_DUPLICATE_CONTROL_ID,
					"The %s has the control %s, which the file already has, so it was left out."
							.formatted(instance.label(), controlId),
					null, null));
			return;
		}
		controls.put(controlId,
				new Control(controlId, type, value, publish,
						propertyName != null && !propertyName.isBlank() ? propertyName : null,
						propertyType, mode));
	}

	/**
	 * Get the {@code propConfigs} item indexes, like the node's
	 * {@code propConfigsCount} setting and its indexed settings.
	 */
	private static SortedSet<Integer> propConfigIndexes(Instance instance,
			List<SimulationMessage> messages) {
		Integer count = null;
		final String countValue = instance.settings().get(PROP_CONFIGS_COUNT);
		if ( countValue != null ) {
			try {
				count = Integer.valueOf(countValue);
			} catch ( NumberFormatException e ) {
				messages.add(warning(SimulationMessage.CODE_INVALID_SETTING,
						"The %s has the %s %s, which is not a number.".formatted(instance.label(),
								PROP_CONFIGS_COUNT, countValue),
						null, null));
			}
		}
		final SortedSet<Integer> result = new TreeSet<>();
		for ( String key : instance.settings().keySet() ) {
			final Matcher m = PROP_CONFIG_INDEX.matcher(key);
			if ( m.find() ) {
				final int i = Integer.parseInt(m.group(1));
				if ( count == null || i < count ) {
					result.add(i);
				}
			}
		}
		return result;
	}

	/**
	 * Test if a GPIO control's {@code gpioDirectionCode} is Output, like its
	 * {@code setGpioDirectionCode()}: an invalid code is the default Input.
	 */
	private static boolean isGpioOutput(@Nullable String code) {
		if ( code == null ) {
			return false;
		}
		try {
			return Integer.parseInt(code.strip()) == GPIO_OUTPUT;
		} catch ( NumberFormatException e ) {
			return false;
		}
	}

	/**
	 * Get a GPIO control's datum property type, like its
	 * {@code setPropertyTypeKey()}: a {@code DatumSamplesType} key, else the
	 * default {@code Status}, as {@code null}.
	 */
	private static @Nullable String samplesType(@Nullable String key) {
		if ( key != null && !key.isEmpty() ) {
			try {
				final DatumSamplesType type = DatumSamplesType.valueOf(key.charAt(0));
				if ( type == DatumSamplesType.Instantaneous || type == DatumSamplesType.Accumulating ) {
					return type.name();
				}
			} catch ( IllegalArgumentException e ) {
				// use the default
			}
		}
		return null;
	}

	/**
	 * Get a Mock control's type, like its {@code setControlTypeValue()}: a type
	 * key, else a type name, else {@code Boolean}.
	 */
	private static String mockType(@Nullable String value) {
		if ( value != null && !value.isEmpty() ) {
			try {
				return NodeControlPropertyType.forKey(value.charAt(0)).name();
			} catch ( IllegalArgumentException e ) {
				try {
					return NodeControlPropertyType.valueOf(value).name();
				} catch ( IllegalArgumentException e2 ) {
					// use the default
				}
			}
		}
		return NodeControlPropertyType.Boolean.name();
	}

	/**
	 * Get a Modbus or BACnet control's type, like their
	 * {@code setControlPropertyTypeKey()}: a type key, else {@code Boolean}.
	 */
	private static String propertyType(@Nullable String key) {
		if ( key != null && !key.isEmpty() ) {
			try {
				return NodeControlPropertyType.forKey(key.charAt(0)).name();
			} catch ( IllegalArgumentException e ) {
				// use the default
			}
		}
		return NodeControlPropertyType.Boolean.name();
	}

	/**
	 * Read a Control Datum Source, like {@code NodeControlInfoDatumDataSource}:
	 * an invalid pattern matches every control, and an invalid mode is the
	 * default.
	 */
	private static DatumSource datumSource(Instance instance, List<SimulationMessage> messages) {
		final Map<String, String> s = instance.settings();
		Pattern regex = null;
		final String regexValue = s.get("controlIdRegexValue");
		if ( regexValue != null && !regexValue.isEmpty() ) {
			try {
				regex = Pattern.compile(regexValue, Pattern.CASE_INSENSITIVE);
			} catch ( PatternSyntaxException e ) {
				messages.add(warning(SimulationMessage.CODE_INVALID_SETTING,
						"The %s has the Control ID pattern %s, which is not valid, so it matches every control."
								.formatted(instance.label(), regexValue),
						null, null));
			}
		}
		final String eventMode = s.get("eventModeValue");
		final boolean publish = (eventMode == null
				|| !("None".equals(eventMode) || "Capture".equals(eventMode)));
		final boolean persist = "PollAndEvent".equals(s.get("persistModeValue"));
		return new DatumSource(regex, publish, persist);
	}

}
