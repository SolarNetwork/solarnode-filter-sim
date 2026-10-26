import { getSettingsForm } from "./api.ts";
import { safeHtml } from "./html.ts";
import { INSTANCE_ID_HELP, MAX_LENGTH } from "./instanceIds.ts";
import type { FilterNode } from "./layout.ts";
import {
	UID_SETTING,
	UNASSIGNED_UID,
	type FilterConfig,
	type SettingField,
	type SettingProblem,
	type SettingsForm,
} from "./model.ts";
import { bundleLabel, externalFilesText, quoted } from "./view.ts";
import type { ChainSlot, SettingChanges, Workspace } from "./workspace.ts";

/** What the inspector's buttons do. */
export interface InspectorActions {
	/** Remove the filter from the chain it is drawn in. */
	disconnect(node: FilterNode): void;
	/** Delete the filter. */
	remove(node: FilterNode): void;
	/**
	 * Get the Service Names of the simulated services that match a setting's
	 * related service filter, for its Choose menu.
	 *
	 * @param serviceFilter the OSGi service filter, like
	 *     `(objectClass=net.solarnetwork.node.service.MetadataService)`
	 */
	relatedServices(serviceFilter: string): string[];
	/**
	 * Test if an external command is in the Allowed Commands.
	 *
	 * @param command the command path
	 */
	isCommandAllowed(command: string): boolean;
	/**
	 * Add an external command to the Allowed Commands.
	 *
	 * @param command the command path
	 */
	allowCommand(command: string): void;
	/** Get the IDs of the simulated controls. */
	controlIds(): string[];
	/**
	 * Add a simulated control.
	 *
	 * @param controlId the control ID
	 */
	addControl(controlId: string): void;
	/** Get the Service Names of the simulated Control Conductors. */
	conductorUids(): string[];
	/**
	 * Add a simulated Control Conductor.
	 *
	 * @param uid the Service Name
	 */
	addConductor(uid: string): void;
}

/** How long after typing stops, in milliseconds, the settings are checked. */
const VALIDATE_DELAY = 400;

/** The setting key of a chain's filters. */
const CHAIN_FILTERS = "transformUids";

/** The setting of a filter's external command, like the POA Calculator's. */
const COMMAND_SETTING = "command";

/** The factory UID of the Control Updater filter. */
const CONTROL_UPDATER_FACTORY_UID =
	"net.solarnetwork.node.datum.filter.control.update";

/** The Control Updater's control ID settings. */
const CONTROL_ID_SETTING = /^controlConfigs\[\d+\]\.controlId$/;

/** The factory UID of the Instructor filter. */
const INSTRUCTOR_FACTORY_UID = "net.solarnetwork.node.datum.filter.instructor";

/** The topic of the instruction Control Conductors handle. */
const ORCHESTRATE_CONTROLS_TOPIC = "OrchestrateControls";

/**
 * The Instructor's instruction parameter value settings, with the
 * instruction and parameter key prefixes as groups 1 and 2.
 */
const INSTRUCTION_PARAM_SETTING =
	/^(instructorConfigs\[\d+\]\.instructions\[\d+\])\.(parameters\[\d+\])\.expression$/;

/** A rendered setting, for showing its problems. */
interface Control {
	/** The element marked invalid. */
	control: HTMLElement;
	feedback: HTMLElement;
}

/** The names a Choose menu offers. */
interface Choices {
	/** Get the names. */
	names: () => string[];
	/** The text shown when there are none. */
	none: string;
	/** The button's tooltip. */
	title: string;
}

function element<K extends keyof HTMLElementTagNameMap>(
	tag: K,
	className?: string,
	text?: string,
): HTMLElementTagNameMap[K] {
	const el = document.createElement(tag);
	if (className) {
		el.className = className;
	}
	if (text !== undefined) {
		el.textContent = text;
	}
	return el;
}

function iconButton(
	label: string,
	icon: string,
	className: string,
	onClick: () => void,
): HTMLButtonElement {
	const button = element("button", `btn btn-sm ${className}`);
	button.type = "button";
	const glyph = element("i", `bi bi-${icon}`);
	glyph.setAttribute("aria-hidden", "true");
	button.append(glyph);
	if (label) {
		button.append(` ${label}`);
	}
	button.addEventListener("click", onClick);
	return button;
}

/**
 * A small icon-only button for a setting's tools. Like SolarNode's help and
 * note buttons, it is left out of the tab order, so tabbing moves between
 * fields.
 */
function toolButton(
	title: string,
	icon: string,
	onClick: () => void,
): HTMLButtonElement {
	const button = iconButton("", icon, "btn-link sim-tool", onClick);
	button.title = title;
	button.setAttribute("aria-label", title);
	button.tabIndex = -1;
	return button;
}

/**
 * Get the name of one item of a list, like "Expression" for "Expressions".
 *
 * @param label the list label
 */
function itemName(label: string | undefined): string {
	return (label ?? "Item").replace(/(?<!s)s$/, "");
}

/**
 * Get a name with its indefinite article, like "an expression".
 *
 * @param name the name, in lower case
 */
function withArticle(name: string): string {
	return `${/^[aeiou]/.test(name) ? "an" : "a"} ${name}`;
}

/** The number of rows for a text area. */
function textRows(value: string | undefined): number {
	const lines = (value ?? "").split("\n").length;
	return Math.min(12, Math.max(2, lines + 1));
}

/**
 * The panel that shows and edits the selected filter.
 *
 * The settings form comes from the web API, rendered from the filter's
 * SolarNode setting specifiers. Edits change the workspace as you type, and
 * the settings are checked again shortly after typing stops.
 */
export class Inspector {
	readonly #el: HTMLElement;
	readonly #ws: Workspace;
	readonly #actions: InspectorActions;
	#node: FilterNode | null = null;
	#shownFilterId: string | null = null;
	#signature = "";
	#selfEdit = false;
	#token = 0;
	#ids = 0;
	#validateTimer?: number;
	#controls = new Map<string, Control>();
	#alerts?: HTMLElement;
	#uidWarning?: HTMLElement;
	/** The Remove Overrides button of an external filter. */
	#overridesButton?: HTMLButtonElement;

	/**
	 * Constructor.
	 *
	 * @param el the panel element
	 * @param ws the workspace
	 * @param actions what the buttons do
	 */
	constructor(el: HTMLElement, ws: Workspace, actions: InspectorActions) {
		this.#el = el;
		this.#ws = ws;
		this.#actions = actions;
		this.#showNothing();
	}

	/**
	 * Show a filter.
	 *
	 * @param node the selected filter node, or `null` for none
	 */
	show(node: FilterNode | null): void {
		const previous = this.#node;
		this.#node = node;
		if (this.#selfEdit && node && previous?.filterId === node.filterId) {
			// the inspector made the change, so it already shows it
			return;
		}
		const signature = this.#signatureOf(node);
		if (signature === this.#signature) {
			return;
		}
		this.#signature = signature;
		void this.#render();
	}

	/**
	 * Get what the shown content depends on, to skip needless updates.
	 */
	#signatureOf(node: FilterNode | null): string {
		if (!node) {
			return "";
		}
		const ws = this.#ws;
		return JSON.stringify({
			node,
			filter: ws.filter(node.filterId),
			// for chain filter lists and Service Name and instance ID checks
			others: ws.filters.map((f) => [
				f.id,
				f.factoryUid,
				f.instanceKey,
				ws.uid(f),
			]),
			members: ws.members(node.filterId),
		});
	}

	/* === Rendering === */

	async #render(): Promise<void> {
		const ws = this.#ws;
		const node = this.#node;
		const filter = node ? ws.filter(node.filterId) : undefined;
		const token = ++this.#token;
		window.clearTimeout(this.#validateTimer);
		if (node?.unassigned && node.slot) {
			this.#shownFilterId = null;
			this.#showUnassigned(node.slot);
			return;
		}
		if (!node || !filter) {
			this.#shownFilterId = null;
			this.#showNothing();
			return;
		}
		let form: SettingsForm | undefined;
		let formError: unknown;
		if (ws.isSupported(filter)) {
			try {
				form = await getSettingsForm(
					filter.factoryUid,
					this.#formSettings(filter),
				);
			} catch (e) {
				formError = e;
			}
			if (token !== this.#token) {
				// shown something else meanwhile
				return;
			}
		}

		this.#controls.clear();
		this.#uidWarning = undefined;
		this.#overridesButton = undefined;
		const parts: HTMLElement[] = [this.#header(node, filter)];
		this.#alerts = element("div");
		this.#alerts.append(...this.#alertsContent(node, form, formError));
		parts.push(this.#alerts);
		if (ws.isUnknown(filter)) {
			parts.push(this.#unknownSettings(filter));
		} else {
			parts.push(this.#instanceIdField(filter));
			if (form) {
				for (const field of form.fields) {
					const el = this.#field(field, filter);
					if (el) {
						parts.push(el);
					}
				}
				const other = this.#otherSettings(filter, form);
				if (other) {
					parts.push(other);
				}
			} else {
				parts.push(this.#rawSettings(filter));
			}
		}
		parts.push(this.#commentSection(filter));
		parts.push(this.#buttons(node));

		const scroll =
			filter.id === this.#shownFilterId ? this.#el.scrollTop : 0;
		this.#el.replaceChildren(...parts);
		this.#el.scrollTop = scroll;
		this.#shownFilterId = filter.id;
		if (form) {
			this.#showProblems(form.problems);
		}
	}

	#showNothing(): void {
		const intro = element(
			"p",
			"text-body-secondary",
			"Select a filter to see its settings.",
		);
		const tips = element("ul", "small text-body-secondary ps-3");
		for (const tip of [
			"Drag a filter from the palette onto a link to add it to a chain there, or onto an empty spot to add it on its own.",
			"Drag a filter onto another link to move it, or away from its chain to disconnect it.",
			"Drag Unassigned onto a link to keep a place in a chain without a filter, and drag a filter onto an Unassigned place to put it there.",
			"Drag from a port to a filter that is not in a chain to connect it.",
			"Drag the background to pan, and pinch or hold Ctrl while scrolling to zoom.",
		]) {
			tips.append(element("li", "mb-1", tip));
		}
		this.#el.replaceChildren(intro, tips);
	}

	/**
	 * Show an Unassigned place in a chain.
	 *
	 * @param slot where the place is
	 */
	#showUnassigned(slot: ChainSlot): void {
		const ws = this.#ws;
		const chain = ws.filter(slot.chainId);
		const header = element("div", "mb-3");
		header.append(
			element("h2", "h5 mb-1", UNASSIGNED_UID),
			element(
				"div",
				"small text-body-secondary sim-about",
				`Place ${slot.index} of ${chain ? quoted(ws.label(chain)) : "the chain"}, without a filter. SolarNode skips it, and an export gives it the Service Name ${quoted(UNASSIGNED_UID)}, so the filters after it keep their places (their transformUids indexes). Drop a filter on it to put the filter here.`,
			),
		);
		const buttons = element("div", "d-flex gap-2 mt-3");
		const remove = iconButton("Delete", "trash", "btn-outline-danger", () =>
			ws.removeUnassigned(slot),
		);
		remove.title =
			"Delete this place, which moves the filters after it down one place.";
		buttons.append(remove);
		this.#el.replaceChildren(header, buttons);
		this.#el.scrollTop = 0;
	}

	/**
	 * Get the settings to render the form from. A chain's filters come from the
	 * canvas, so they are added as its `transformUids` settings.
	 */
	#formSettings(filter: FilterConfig): Record<string, string> {
		const ws = this.#ws;
		const settings = { ...(filter.settings ?? {}) };
		if (ws.isChain(filter)) {
			for (const key of Object.keys(settings)) {
				if (key.startsWith(CHAIN_FILTERS)) {
					delete settings[key];
				}
			}
			const members = ws.members(filter.id);
			settings[`${CHAIN_FILTERS}Count`] = String(members.length);
			members.forEach((id, i) => {
				const member = id === null ? undefined : ws.filter(id);
				settings[`${CHAIN_FILTERS}[${i}]`] = member
					? (ws.uid(member) ??
						`${ws.label(member)} (no Service Name)`)
					: (id ?? UNASSIGNED_UID);
			});
		}
		return settings;
	}

	#header(node: FilterNode, filter: FilterConfig): HTMLElement {
		const ws = this.#ws;
		const type = ws.type(filter.factoryUid);
		const header = element("div", "mb-3");
		let title: string;
		if (node.main) {
			title = "Main Filter Chain";
		} else if (ws.isUnknown(filter)) {
			title = "Unknown Filter";
		} else {
			title =
				type?.name ??
				ws.unavailableType(filter.factoryUid)?.name ??
				"Unsupported Filter";
		}
		header.append(element("h2", "h5 mb-1", title));
		const about = element("div", "small text-body-secondary sim-about");
		if (node.main) {
			about.textContent =
				"The filter chain the Datum Queue applies to every datum. Its filters run in order, from left to right.";
		} else if (ws.isUnknown(filter)) {
			about.textContent =
				"A chain uses this filter by its Service Name, but its settings are not in this configuration. Datum pass through it unchanged, and an export keeps only the chain's reference to it.";
		} else if (!type) {
			const unavailable = ws.unavailableType(filter.factoryUid);
			about.textContent = unavailable
				? `The filter type ${unavailable.name} (${filter.factoryUid})${unavailable.bundle ? `, from ${bundleLabel(unavailable.bundle)},` : ""} is not available, so datum pass through it unchanged. Its settings are kept as they are. ${unavailable.reason}`
				: `The simulator does not support the ${filter.factoryUid} filter type, so datum pass through it unchanged. Its settings are kept as they are.`;
		} else if (type.description) {
			about.append(safeHtml(type.description));
		}
		header.append(about);
		if (type?.bundle) {
			header.append(
				element(
					"div",
					"small text-body-secondary mt-1",
					`A custom filter, from ${bundleLabel(type.bundle)}.`,
				),
			);
		}
		const files = ws.externalFilesOf(filter.id);
		if (files.length) {
			const external = element("div", "small mt-2 sim-external-note");
			const pill = element(
				"span",
				"badge rounded-pill text-bg-info me-1",
				"Ext",
			);
			external.append(
				pill,
				`From ${externalFilesText(files)}. Settings marked Ext come from ${files.length === 1 ? "the file" : "these files"}. Changing one overrides it in this simulation, and exports include only the changes.`,
			);
			header.append(external);
		}
		return header;
	}

	#alertsContent(
		node: FilterNode,
		form: SettingsForm | undefined,
		formError: unknown,
	): HTMLElement[] {
		const alerts: HTMLElement[] = [];
		const alert = (kind: string, text: string) =>
			element("div", `alert alert-${kind} small py-2 mb-2`, text);
		if (node.root) {
			alerts.push(
				alert(
					"secondary",
					"This filter is not in a chain, so it never runs. Drag it onto a link to add it to a chain.",
				),
			);
		}
		if (node.loop) {
			alerts.push(
				alert(
					"warning",
					"This chain contains itself, so its filters are shown only where it first appears.",
				),
			);
		}
		if (formError) {
			alerts.push(
				alert(
					"danger",
					`The settings form is not available: ${formError instanceof Error ? formError.message : String(formError)}`,
				),
			);
		}
		if (form?.limitation) {
			alerts.push(alert("warning", form.limitation));
		}
		const command = this.#disallowedCommand(node, form);
		if (command) {
			alerts.push(this.#commandAlert(command));
		}
		const missing = this.#missingControls(node, form);
		if (missing.length) {
			alerts.push(this.#controlsAlert(missing));
		}
		const conductors = this.#missingConductors(node, form);
		if (conductors.length) {
			alerts.push(this.#conductorsAlert(conductors));
		}
		if (form && form.problems.length > 0) {
			const problems = element(
				"div",
				"alert alert-danger small py-2 mb-2",
			);
			const list = element("ul", "mb-0 ps-3");
			for (const p of form.problems) {
				list.append(element("li", undefined, p.message));
			}
			problems.append(list);
			alerts.push(problems);
		}
		return alerts;
	}

	/**
	 * Get a filter's external command, if it runs one that is not allowed.
	 *
	 * @returns the command path, or `undefined`
	 */
	#disallowedCommand(
		node: FilterNode,
		form: SettingsForm | undefined,
	): string | undefined {
		const filter = this.#ws.filter(node.filterId);
		const type = filter ? this.#ws.type(filter.factoryUid) : undefined;
		if (!form || !type?.capabilities?.includes("Commands")) {
			return undefined;
		}
		const field = form.fields.find((f) => f.key === COMMAND_SETTING);
		const command = (field?.value || field?.defaultValue || "").trim();
		return command && !this.#actions.isCommandAllowed(command)
			? command
			: undefined;
	}

	#commandAlert(command: string): HTMLElement {
		const el = element("div", "alert alert-warning small py-2 mb-2");
		const text = element("div", "mb-1");
		text.append(
			"The simulator runs the command ",
			element("code", "text-break", command),
			" only if it is in the Allowed Commands, on the Environment tab. Allow only programs you trust: it runs on this computer.",
		);
		const allow = element(
			"button",
			"btn btn-sm btn-outline-secondary",
			"Allow this command",
		);
		allow.type = "button";
		allow.addEventListener("click", () => {
			this.#actions.allowCommand(command);
			void this.#validate();
		});
		el.append(text, allow);
		return el;
	}

	/** Test if a setting is one of the Control Updater's control IDs. */
	#isControlIdField(field: SettingField, filter: FilterConfig): boolean {
		return (
			filter.factoryUid === CONTROL_UPDATER_FACTORY_UID &&
			!!field.key &&
			CONTROL_ID_SETTING.test(field.key)
		);
	}

	/**
	 * Get the control IDs a Control Updater sets that are not simulated
	 * controls. IDs with placeholders, resolved for each datum, are left out.
	 *
	 * @returns the control IDs, without repeats
	 */
	#missingControls(
		node: FilterNode,
		form: SettingsForm | undefined,
	): string[] {
		const filter = this.#ws.filter(node.filterId);
		if (!form || filter?.factoryUid !== CONTROL_UPDATER_FACTORY_UID) {
			return [];
		}
		const available = new Set(this.#actions.controlIds());
		const missing = new Set<string>();
		const collect = (fields: SettingField[]) => {
			for (const f of fields) {
				const id = (f.value ?? "").trim();
				if (
					f.key &&
					CONTROL_ID_SETTING.test(f.key) &&
					id &&
					!id.includes("{") &&
					!available.has(id)
				) {
					missing.add(id);
				}
				collect(f.fields ?? []);
			}
		};
		collect(form.fields);
		return [...missing];
	}

	#controlsAlert(controlIds: string[]): HTMLElement {
		const el = element("div", "alert alert-warning small py-2 mb-2");
		const text = element("div", "mb-1");
		const one = controlIds.length === 1;
		text.append(one ? "The control " : "The controls ");
		controlIds.forEach((id, i) => {
			if (i > 0) {
				text.append(", ");
			}
			text.append(element("code", "text-break", id));
		});
		text.append(
			one
				? " is not one of the simulation's Controls, so setting it fails, as on a node without that control."
				: " are not among the simulation's Controls, so setting them fails, as on a node without those controls.",
		);
		const add = element(
			"button",
			"btn btn-sm btn-outline-secondary",
			one ? "Add this control" : "Add these controls",
		);
		add.type = "button";
		add.addEventListener("click", () => {
			for (const id of controlIds) {
				this.#actions.addControl(id);
			}
			void this.#validate();
		});
		el.append(text, add);
		return el;
	}

	/**
	 * Test if a setting is the value of an Instructor `OrchestrateControls`
	 * instruction's `service` parameter, which names a Control Conductor.
	 */
	#isConductorServiceField(
		field: SettingField,
		filter: FilterConfig,
	): boolean {
		const m =
			filter.factoryUid === INSTRUCTOR_FACTORY_UID && field.key
				? INSTRUCTION_PARAM_SETTING.exec(field.key)
				: null;
		const settings = filter.settings ?? {};
		return (
			!!m &&
			settings[`${m[1]}.topic`]?.trim() === ORCHESTRATE_CONTROLS_TOPIC &&
			settings[`${m[1]}.${m[2]}.name`]?.trim() === "service"
		);
	}

	/**
	 * Get the Service Names an Instructor's `OrchestrateControls`
	 * instructions name that are not simulated Control Conductors. Like the
	 * server's check, a `service` parameter that is an expression is left out.
	 *
	 * @returns the Service Names, without repeats
	 */
	#missingConductors(
		node: FilterNode,
		form: SettingsForm | undefined,
	): string[] {
		const filter = this.#ws.filter(node.filterId);
		if (!form || filter?.factoryUid !== INSTRUCTOR_FACTORY_UID) {
			return [];
		}
		const values = new Map<string, string>();
		const collect = (fields: SettingField[]) => {
			for (const f of fields) {
				if (f.key) {
					values.set(f.key, f.value ?? "");
				}
				collect(f.fields ?? []);
			}
		};
		collect(form.fields);
		const available = new Set(
			this.#actions.conductorUids().map((uid) => uid.toLowerCase()),
		);
		const missing = new Map<string, string>();
		for (const [key, value] of values) {
			const m = INSTRUCTION_PARAM_SETTING.exec(key);
			const name = value.trim();
			if (
				m &&
				name &&
				values.get(`${m[1]}.topic`)?.trim() ===
					ORCHESTRATE_CONTROLS_TOPIC &&
				values.get(`${m[1]}.${m[2]}.name`)?.trim() === "service" &&
				!values.get(`${m[1]}.${m[2]}.expressionServiceId`) &&
				!available.has(name.toLowerCase())
			) {
				missing.set(name.toLowerCase(), name);
			}
		}
		return [...missing.values()];
	}

	#conductorsAlert(uids: string[]): HTMLElement {
		const el = element("div", "alert alert-warning small py-2 mb-2");
		const text = element("div", "mb-1");
		const one = uids.length === 1;
		text.append(
			one
				? `An OrchestrateControls instruction names the Control Conductor ${quoted(uids[0])}, which is not one of the simulation's Control Conductors, so nothing handles it, as on a node without that conductor.`
				: `OrchestrateControls instructions name the Control Conductors ${uids.map((u) => quoted(u)).join(", ")}, which are not among the simulation's Control Conductors, so nothing handles them, as on a node without those conductors.`,
		);
		const add = element(
			"button",
			"btn btn-sm btn-outline-secondary",
			one ? "Add a Control Conductor" : "Add these Control Conductors",
		);
		add.type = "button";
		add.addEventListener("click", () => {
			for (const uid of uids) {
				this.#actions.addConductor(uid);
			}
			void this.#validate();
		});
		el.append(text, add);
		return el;
	}

	/**
	 * Check the shown filter again, such as after the Allowed Commands or
	 * controls change.
	 */
	refreshAlerts(): void {
		void this.#validate();
	}

	#instanceIdField(filter: FilterConfig): HTMLElement {
		const ws = this.#ws;
		const id = this.#newId();
		const wrapper = element("div", "mb-3");
		const label = element(
			"label",
			"form-label mb-1 small fw-semibold",
			"Instance ID",
		);
		label.htmlFor = id;
		const input = element("input", "form-control form-control-sm");
		input.id = id;
		input.value = filter.instanceKey ?? "";
		input.maxLength = MAX_LENGTH;
		input.spellcheck = false;
		const external = ws.isExternal(filter.id);
		input.readOnly = external;
		const feedback = element("div", "invalid-feedback");
		const help = element("div", "form-text");
		const key = element("code");
		help.append(
			external
				? "The External Settings have this instance ID, which is how the simulation's changes apply to the filter, so it cannot change. Settings key: "
				: `${INSTANCE_ID_HELP} Settings key: `,
			key,
		);
		const check = () => {
			const current = ws.filter(filter.id);
			const problem = current
				? ws.instanceKeyProblem(current)
				: undefined;
			input.classList.toggle("is-invalid", !!problem);
			feedback.textContent = problem ?? "";
			key.textContent = `${filter.factoryUid}.${input.value}`;
		};
		input.addEventListener("input", () => {
			this.#change(() =>
				ws.setInstanceKey(
					filter.id,
					input.value,
					`${filter.id}:instanceKey`,
				),
			);
			check();
		});
		wrapper.append(label, input, feedback, help);
		check();
		return wrapper;
	}

	#field(
		field: SettingField,
		filter: FilterConfig,
		itemLabel?: string,
	): HTMLElement | null {
		switch (field.type) {
			case "Group":
				return this.#group(field, filter, itemLabel);
			case "Title":
				return this.#title(field);
			case "Toggle":
				return this.#toggle(field, filter);
			case "Select":
				return this.#select(field, filter);
			case "Radio":
				return this.#radio(field, filter);
			case "Slider":
				return this.#slider(field, filter);
			case "TextArea":
				return this.#text(field, filter, true);
			default:
				return this.#text(field, filter, false);
		}
	}

	/**
	 * Wrap a setting's control with its label, tools, help, problem feedback,
	 * and note.
	 */
	#setting(
		field: SettingField,
		filter: FilterConfig,
		control: HTMLElement,
		controlId: string,
		marked: HTMLElement = control,
	): HTMLElement {
		const key = field.key!;
		const wrapper = element("div", "mb-3 sim-setting");
		const head = element("div", "d-flex align-items-center gap-1 mb-1");
		const label = element(
			"label",
			"form-label mb-0 me-auto small fw-semibold",
			field.label ?? key,
		);
		label.htmlFor = controlId;
		head.append(label);
		if (field.transient) {
			const badge = element(
				"span",
				"badge text-bg-light border",
				"not saved",
			);
			badge.title =
				"SolarNode does not save this setting, so exports leave it out. The simulator applies it when a simulation starts.";
			head.append(badge);
		}
		const extPill = element(
			"span",
			"badge rounded-pill text-bg-info sim-ext-pill",
			"Ext",
		);
		head.append(extPill);

		const help = element("div", "form-text sim-help");
		help.hidden = true;
		if (field.description) {
			help.append(safeHtml(field.description));
			const toggle = toolButton("Help", "question-circle", () => {
				help.hidden = !help.hidden;
				toggle.setAttribute("aria-expanded", String(!help.hidden));
			});
			toggle.setAttribute("aria-expanded", "false");
			head.append(toggle);
		}

		const note = filter.notes?.[key] ?? "";
		const noteRow = element("div", "mt-1");
		noteRow.hidden = !note;
		const noteInput = element(
			"input",
			"form-control form-control-sm sim-note",
		);
		noteInput.value = note;
		noteInput.placeholder =
			"A note about this setting, kept in the CSV note column";
		noteInput.setAttribute("aria-label", `Note for ${field.label ?? key}`);
		noteRow.append(noteInput);
		if (!field.readOnly) {
			const noteButton = toolButton(
				"Note",
				note ? "sticky-fill" : "sticky",
				() => {
					noteRow.hidden = !noteRow.hidden;
					if (!noteRow.hidden) {
						noteInput.focus();
					}
				},
			);
			noteInput.addEventListener("input", () => {
				this.#change(() =>
					this.#ws.setNote(
						filter.id,
						key,
						noteInput.value,
						`${filter.id}:note:${key}`,
					),
				);
				noteButton.querySelector("i")!.className =
					`bi bi-${noteInput.value.trim() ? "sticky-fill" : "sticky"}`;
			});
			head.append(noteButton);

			const reset = toolButton(
				field.defaultValue
					? `Use the default value: ${field.defaultValue}`
					: "Use the default value",
				"arrow-counterclockwise",
				() => this.#edit(filter.id, { [key]: null }, undefined, true),
			);
			reset.classList.add("sim-reset");
			head.append(reset);
		}
		this.#refreshSource(filter.id, key, head);

		const feedback = element("div", "invalid-feedback");
		wrapper.append(head, control, feedback, help, noteRow);
		this.#controls.set(key, { control: marked, feedback });
		return wrapper;
	}

	#text(
		field: SettingField,
		filter: FilterConfig,
		multiline: boolean,
	): HTMLElement {
		const id = this.#newId();
		const input = multiline
			? element("textarea", "form-control form-control-sm font-monospace")
			: element("input", "form-control form-control-sm");
		if (input instanceof HTMLInputElement) {
			input.type = field.secure ? "password" : "text";
			if (field.type === "Cron") {
				input.classList.add("font-monospace");
			}
		} else {
			input.rows = textRows(field.value);
		}
		input.id = id;
		input.value = field.value ?? "";
		input.placeholder = field.defaultValue ?? "";
		input.readOnly = field.readOnly;
		input.spellcheck = false;
		input.addEventListener("input", () =>
			this.#editValue(filter, field.key!, input.value),
		);
		// a service parameter's value is a text area, like every Instructor
		// parameter's
		const choices: Choices | undefined = field.readOnly
			? undefined
			: this.#isConductorServiceField(field, filter)
				? {
						names: () => this.#actions.conductorUids(),
						none: "No Control Conductors: add them on the Controls tab",
						title: "Choose from the simulated Control Conductors",
					}
				: multiline
					? undefined
					: field.relatedServiceFilter
						? {
								names: () =>
									this.#actions.relatedServices(
										field.relatedServiceFilter!,
									),
								none: "No matching services",
								title: "Choose from the simulated services",
							}
						: this.#isControlIdField(field, filter)
							? {
									names: () => this.#actions.controlIds(),
									none: "No controls: add them on the Controls tab",
									title: "Choose from the simulated controls",
								}
							: undefined;
		const wrapper = choices
			? this.#setting(
					field,
					filter,
					this.#chooser(field, filter, input, choices),
					id,
					input,
				)
			: this.#setting(field, filter, input, id);
		if (field.key === UID_SETTING) {
			this.#uidWarning = element(
				"div",
				"form-text text-warning-emphasis",
			);
			wrapper.append(this.#uidWarning);
			this.#updateUidWarning(filter.id);
		}
		if (field.type === "Cron") {
			wrapper.append(
				element(
					"div",
					"form-text",
					"A cron expression, or a number of milliseconds between runs.",
				),
			);
		}
		return wrapper;
	}

	/**
	 * Add a Choose menu to a text input, which lists names such as the
	 * simulated services that match the setting's related service filter, like
	 * SolarNode's settings page.
	 */
	#chooser(
		field: SettingField,
		filter: FilterConfig,
		input: HTMLInputElement | HTMLTextAreaElement,
		choices: Choices,
	): HTMLElement {
		const group = element("div", "input-group input-group-sm");
		const button = element(
			"button",
			"btn btn-outline-secondary dropdown-toggle",
			"Choose",
		);
		button.type = "button";
		button.setAttribute("data-bs-toggle", "dropdown");
		button.setAttribute("aria-expanded", "false");
		button.title = choices.title;
		const menu = element("ul", "dropdown-menu dropdown-menu-end");
		button.addEventListener("show.bs.dropdown", () => {
			const names = choices.names();
			menu.replaceChildren(
				...(names.length ? names : [null]).map((name) => {
					const item = element("li");
					const choice = element(
						"button",
						"dropdown-item",
						name ?? choices.none,
					);
					choice.type = "button";
					if (name === null) {
						choice.disabled = true;
					} else {
						choice.classList.toggle("active", input.value === name);
						choice.addEventListener("click", () => {
							input.value = name;
							this.#editValue(filter, field.key!, name);
						});
					}
					item.append(choice);
					return item;
				}),
			);
		});
		group.append(input, button, menu);
		return group;
	}

	#toggle(field: SettingField, filter: FilterConfig): HTMLElement {
		const id = this.#newId();
		const control = element("div", "form-check form-switch");
		const input = element("input", "form-check-input");
		input.type = "checkbox";
		input.setAttribute("role", "switch");
		input.id = id;
		input.checked = field.value === field.trueValue;
		input.disabled = field.readOnly;
		const state = element(
			"label",
			"form-check-label small text-body-secondary",
			input.checked ? "On" : "Off",
		);
		state.htmlFor = id;
		input.addEventListener("change", () => {
			state.textContent = input.checked ? "On" : "Off";
			this.#editValue(
				filter,
				field.key!,
				(input.checked ? field.trueValue : field.falseValue) ??
					String(input.checked),
			);
		});
		control.append(input, state);
		return this.#setting(field, filter, control, id);
	}

	#select(field: SettingField, filter: FilterConfig): HTMLElement {
		const id = this.#newId();
		const select = element("select", "form-select form-select-sm");
		select.id = id;
		select.disabled = field.readOnly;
		const value = field.value ?? "";
		const options = [...(field.options ?? [])];
		if (!options.some((o) => o.value === value)) {
			// keep a value that is not one of the choices
			options.push({ value, label: value });
		}
		for (const o of options) {
			const option = element("option", undefined, o.label || "(none)");
			option.value = o.value;
			option.selected = o.value === value;
			select.append(option);
		}
		select.addEventListener("change", () =>
			this.#editValue(filter, field.key!, select.value),
		);
		return this.#setting(field, filter, select, id);
	}

	#radio(field: SettingField, filter: FilterConfig): HTMLElement {
		const name = this.#newId();
		const control = element("div");
		let firstId = name;
		(field.options ?? []).forEach((o, i) => {
			const id = `${name}-${i}`;
			if (i === 0) {
				firstId = id;
			}
			const check = element("div", "form-check");
			const input = element("input", "form-check-input");
			input.type = "radio";
			input.name = name;
			input.id = id;
			input.value = o.value;
			input.checked = o.value === field.value;
			input.disabled = field.readOnly;
			input.addEventListener("change", () => {
				if (input.checked) {
					this.#editValue(filter, field.key!, o.value);
				}
			});
			const label = element("label", "form-check-label small", o.label);
			label.htmlFor = id;
			check.append(input, label);
			if (o.description) {
				const about = element("div", "form-text mt-0");
				about.append(safeHtml(o.description));
				check.append(about);
			}
			control.append(check);
		});
		return this.#setting(field, filter, control, firstId);
	}

	#slider(field: SettingField, filter: FilterConfig): HTMLElement {
		const id = this.#newId();
		const control = element("div", "d-flex align-items-center gap-2");
		const input = element("input", "form-range");
		input.type = "range";
		input.id = id;
		input.min = String(field.min ?? 0);
		input.max = String(field.max ?? 100);
		input.step = String(field.step ?? 1);
		input.value = field.value ?? "";
		input.disabled = field.readOnly;
		const output = element("output", "small font-monospace", input.value);
		input.addEventListener("input", () => {
			output.textContent = input.value;
			this.#editValue(filter, field.key!, input.value);
		});
		control.append(input, output);
		return this.#setting(field, filter, control, id, input);
	}

	#title(field: SettingField): HTMLElement {
		const wrapper = element("div", "mb-3");
		wrapper.append(
			element("div", "small fw-semibold mb-1", field.label ?? field.key),
		);
		const value = element("div", "small");
		if (field.markup) {
			value.append(safeHtml(field.value ?? ""));
		} else {
			value.textContent = field.value ?? "";
		}
		wrapper.append(value);
		return wrapper;
	}

	#group(
		field: SettingField,
		filter: FilterConfig,
		itemLabel?: string,
	): HTMLElement {
		const children = field.fields ?? [];
		if (!field.key) {
			// a group without a key, such as one item of a list
			const set = element("fieldset", "sim-item mb-3");
			if (itemLabel) {
				set.append(element("legend", "sim-item-legend", itemLabel));
			}
			for (const child of children) {
				const el = this.#field(child, filter);
				if (el) {
					set.append(el);
				}
			}
			return set;
		}

		const set = element("fieldset", "sim-group mb-3");
		const head = element("div", "d-flex align-items-center gap-1 mb-1");
		head.append(
			element("legend", "h6 mb-0 me-auto", field.label ?? field.key),
		);
		const help = element("div", "form-text sim-help mb-2");
		help.hidden = true;
		if (field.description) {
			help.append(safeHtml(field.description));
			head.append(
				toolButton("Help", "question-circle", () => {
					help.hidden = !help.hidden;
				}),
			);
		}
		if (field.dynamic && !field.readOnly && field.countKey) {
			const buttons = element("div", "btn-group btn-group-sm");
			const remove = iconButton(
				"",
				"dash-lg",
				"btn-outline-secondary",
				() => this.#removeItem(filter, field),
			);
			remove.title = `Remove the last ${itemName(field.label).toLowerCase()}`;
			remove.disabled = children.length === 0;
			const add = iconButton("", "plus-lg", "btn-outline-secondary", () =>
				this.#addItem(filter, field),
			);
			add.title = `Add ${withArticle(itemName(field.label).toLowerCase())}`;
			buttons.append(remove, add);
			head.append(buttons);
		}
		set.append(head, help);

		if (field.readOnly) {
			// a chain's filters, which the canvas manages
			if (children.length > 0) {
				// each filter's transformUids index, and the file it comes from
				const members = this.#ws.members(filter.id);
				const files = this.#ws.memberFiles(filter.id);
				const list = element(
					"ul",
					"list-unstyled small mb-1 sim-members",
				);
				children.forEach((child, i) => {
					const item = element(
						"li",
						"d-flex align-items-baseline gap-2",
					);
					const index = element(
						"code",
						"sim-member-index",
						String(i),
					);
					index.title = `transformUids[${i}]`;
					item.append(
						index,
						element(
							"span",
							members[i] === null
								? "text-body-secondary fst-italic"
								: undefined,
							child.value ?? "",
						),
					);
					const file = files[i];
					if (file) {
						const pill = element(
							"span",
							"badge rounded-pill text-bg-info",
							"Ext",
						);
						pill.title = `From the External Settings file ${file}.`;
						item.append(pill);
					}
					list.append(item);
				});
				set.append(list);
			} else {
				set.append(
					element(
						"p",
						"small text-body-secondary mb-1",
						"No filters yet.",
					),
				);
			}
			set.append(
				element(
					"div",
					"form-text",
					"Drag filters onto the chain's links on the canvas to add them.",
				),
			);
			return set;
		}

		children.forEach((child, i) => {
			const label = field.dynamic
				? `${itemName(field.label)} ${i + 1}`
				: undefined;
			const el = this.#field(child, filter, label);
			if (el) {
				set.append(el);
			}
		});
		if (field.dynamic && children.length === 0) {
			set.append(
				element(
					"p",
					"small text-body-secondary mb-0",
					"None yet: use + to add one.",
				),
			);
		}
		return set;
	}

	/** Settings the form does not show, such as settings the filter does not have. */
	#otherSettings(
		filter: FilterConfig,
		form: SettingsForm,
	): HTMLElement | undefined {
		const shown = new Set<string>();
		const collect = (fields: SettingField[]) => {
			for (const f of fields) {
				if (f.key) {
					shown.add(f.key);
				}
				if (f.countKey) {
					shown.add(f.countKey);
				}
				collect(f.fields ?? []);
			}
		};
		collect(form.fields);
		const other = Object.entries(filter.settings ?? {}).filter(
			([key]) => !shown.has(key) && !key.startsWith(CHAIN_FILTERS),
		);
		if (other.length === 0) {
			return undefined;
		}
		const section = element("div", "mb-3");
		section.append(element("h3", "h6", "Other settings"));
		section.append(
			element(
				"p",
				"form-text mt-0",
				"The filter does not have these settings, so they have no effect. Exports keep them.",
			),
		);
		section.append(this.#settingsTable(filter, other, false));
		return section;
	}

	/** The settings of a filter type the simulator does not support. */
	#rawSettings(filter: FilterConfig): HTMLElement {
		const section = element("div", "mb-3");
		section.append(element("h3", "h6", "Settings"));
		section.append(
			this.#settingsTable(
				filter,
				Object.entries(filter.settings ?? {}),
				true,
			),
		);
		return section;
	}

	#settingsTable(
		filter: FilterConfig,
		entries: [string, string][],
		editable: boolean,
	): HTMLElement {
		const table = element(
			"table",
			"table table-sm small align-middle sim-settings-table",
		);
		const body = element("tbody");
		for (const [key, value] of entries) {
			const row = element("tr");
			const keyCell = element(
				"th",
				"fw-normal text-body-secondary text-break",
				key,
			);
			keyCell.scope = "row";
			const valueCell = element("td");
			if (editable) {
				const input = element("input", "form-control form-control-sm");
				input.value = value;
				input.setAttribute("aria-label", key);
				input.addEventListener("input", () =>
					this.#editValue(filter, key, input.value),
				);
				valueCell.append(input);
			} else {
				valueCell.append(element("code", "sim-setting-value", value));
			}
			const removeCell = element("td", "text-end");
			const remove = toolButton(`Remove ${key}`, "x-lg", () =>
				this.#edit(filter.id, { [key]: null }, undefined, true),
			);
			removeCell.append(remove);
			row.append(keyCell, valueCell, removeCell);
			body.append(row);
		}
		table.append(body);
		return table;
	}

	/** An unknown filter's only setting: its Service Name. */
	#unknownSettings(filter: FilterConfig): HTMLElement {
		const id = this.#newId();
		const wrapper = element("div", "mb-3");
		const label = element(
			"label",
			"form-label mb-1 small fw-semibold",
			"Service Name",
		);
		label.htmlFor = id;
		const input = element("input", "form-control form-control-sm");
		input.id = id;
		input.value = this.#ws.uid(filter) ?? "";
		input.addEventListener("input", () =>
			this.#editValue(filter, UID_SETTING, input.value),
		);
		this.#uidWarning = element("div", "form-text text-warning-emphasis");
		wrapper.append(label, input, this.#uidWarning);
		this.#updateUidWarning(filter.id);
		return wrapper;
	}

	#commentSection(filter: FilterConfig): HTMLElement {
		const details = element("details", "mb-3 sim-comment");
		details.open = !!filter.comment;
		details.append(element("summary", "small fw-semibold", "Comment"));
		const textarea = element(
			"textarea",
			"form-control form-control-sm font-monospace mt-2",
		);
		textarea.value = filter.comment ?? "";
		textarea.rows = textRows(filter.comment ?? "");
		textarea.spellcheck = false;
		textarea.placeholder = "# A comment about this filter";
		textarea.setAttribute("aria-label", "Comment");
		textarea.addEventListener("input", () =>
			this.#change(() =>
				this.#ws.setComment(
					filter.id,
					textarea.value,
					`${filter.id}:comment`,
				),
			),
		);
		details.append(
			textarea,
			element(
				"div",
				"form-text",
				"Exported above the filter's settings. Lines that do not start with # become comments.",
			),
		);
		return details;
	}

	#buttons(node: FilterNode): HTMLElement {
		const buttons = element("div", "d-flex flex-wrap gap-2 mt-3");
		const ws = this.#ws;
		const takeOutProblem = node.slot
			? ws.takeOutProblem(node.slot)
			: undefined;
		if (node.slot) {
			const disconnect = iconButton(
				"Disconnect",
				"scissors",
				"btn-outline-secondary",
				() => this.#actions.disconnect(this.#node ?? node),
			);
			disconnect.disabled = !!takeOutProblem;
			buttons.append(disconnect);
		}
		if (ws.isExternal(node.filterId)) {
			const remove = iconButton(
				"Remove Overrides",
				"arrow-counterclockwise",
				"btn-outline-secondary",
				() => ws.removeOverrides(node.filterId),
			);
			this.#overridesButton = remove;
			this.#refreshOverrides(node.filterId);
			buttons.append(remove);
		} else if (!node.main) {
			buttons.append(
				iconButton("Delete", "trash", "btn-outline-danger", () =>
					this.#actions.remove(this.#node ?? node),
				),
			);
		}
		if (!takeOutProblem) {
			return buttons;
		}
		const result = element("div");
		result.append(buttons, element("div", "form-text", takeOutProblem));
		return result;
	}

	/**
	 * Enable the Remove Overrides button while the simulation changes the
	 * filter.
	 *
	 * @param filterId the filter id
	 */
	#refreshOverrides(filterId: string): void {
		const button = this.#overridesButton;
		if (!button) {
			return;
		}
		button.disabled = !this.#ws.hasOverrides(filterId);
		button.title = button.disabled
			? "The simulation does not change this filter's external settings."
			: "Remove the simulation's changes to this filter, so it has only its external settings. The External Settings would add the filter back on a node if it were deleted, so it cannot be deleted.";
	}

	#newId(): string {
		return `sim-field-${++this.#ids}`;
	}

	/* === Editing === */

	/**
	 * Make a change to the workspace without showing it again, because the
	 * inspector already shows it.
	 */
	#change(change: () => void): void {
		this.#selfEdit = true;
		try {
			change();
		} finally {
			this.#selfEdit = false;
		}
		this.#signature = this.#signatureOf(this.#node);
		if (this.#node) {
			this.#refreshOverrides(this.#node.filterId);
		}
	}

	/**
	 * Change settings.
	 *
	 * @param filterId the filter id
	 * @param changes the changes
	 * @param mergeKey merges typing into one undo step
	 * @param rerender `true` to show the form again, for changes to its
	 *     structure, like adding a list item
	 */
	#edit(
		filterId: string,
		changes: SettingChanges,
		mergeKey?: string,
		rerender = false,
	): void {
		this.#change(() =>
			this.#ws.updateSettings(filterId, changes, mergeKey),
		);
		if (rerender) {
			this.#signature = "";
			void this.#render();
		} else {
			this.#scheduleValidate();
		}
	}

	#editValue(filter: FilterConfig, key: string, value: string): void {
		this.#edit(filter.id, { [key]: value }, `${filter.id}:${key}`);
		if (key === UID_SETTING) {
			this.#updateUidWarning(filter.id);
		}
		const setting = this.#controls
			.get(key)
			?.control.closest<HTMLElement>(".sim-setting");
		if (setting) {
			this.#refreshSource(filter.id, key, setting);
		}
	}

	/**
	 * Show where a setting's value comes from: the Ext pill while it is the
	 * External Settings' value, and the reset button while the simulation has
	 * its own value.
	 *
	 * @param filterId the filter id
	 * @param key the setting key
	 * @param container the element with the setting's pill and reset button
	 */
	#refreshSource(
		filterId: string,
		key: string,
		container: HTMLElement,
	): void {
		const ws = this.#ws;
		const source = ws.settingSource(filterId, key);
		const pill = container.querySelector<HTMLElement>(".sim-ext-pill");
		if (pill) {
			pill.hidden = source !== "external";
			pill.title = `From the External Settings file ${ws.externalSettingFile(filterId, key) ?? ""}. Change the value to override it in this simulation.`;
		}
		const reset = container.querySelector<HTMLElement>(".sim-reset");
		if (reset) {
			reset.hidden = source !== "override" && source !== "project";
			const external = ws.externalFilter(filterId)?.settings?.[key];
			if (external !== undefined) {
				reset.title = `Use the external value: ${external}`;
				reset.setAttribute("aria-label", reset.title);
			}
		}
	}

	#addItem(filter: FilterConfig, list: SettingField): void {
		const count = list.fields?.length ?? 0;
		this.#edit(
			filter.id,
			{ [list.countKey!]: String(count + 1) },
			undefined,
			true,
		);
	}

	#removeItem(filter: FilterConfig, list: SettingField): void {
		const count = list.fields?.length ?? 0;
		if (count === 0) {
			return;
		}
		// like SolarNode, remove the last item's settings along with it
		const prefix = `${list.key}[${count - 1}]`;
		const changes: SettingChanges = { [list.countKey!]: String(count - 1) };
		for (const key of Object.keys(
			this.#ws.filter(filter.id)?.settings ?? {},
		)) {
			if (key.startsWith(prefix)) {
				changes[key] = null;
			}
		}
		this.#edit(filter.id, changes, undefined, true);
	}

	#updateUidWarning(filterId: string): void {
		const filter = this.#ws.filter(filterId);
		if (this.#uidWarning && filter) {
			this.#uidWarning.textContent = this.#ws.uidWarning(filter) ?? "";
		}
	}

	/* === Checking === */

	#scheduleValidate(): void {
		window.clearTimeout(this.#validateTimer);
		this.#validateTimer = window.setTimeout(
			() => void this.#validate(),
			VALIDATE_DELAY,
		);
	}

	/** Check the settings with the server, and show any problems. */
	async #validate(): Promise<void> {
		const ws = this.#ws;
		const node = this.#node;
		const filter = node ? ws.filter(node.filterId) : undefined;
		if (!node || !filter || !ws.isSupported(filter)) {
			return;
		}
		const token = this.#token;
		let form: SettingsForm;
		try {
			form = await getSettingsForm(
				filter.factoryUid,
				this.#formSettings(filter),
			);
		} catch {
			return;
		}
		if (token !== this.#token || !this.#alerts) {
			return;
		}
		this.#alerts.replaceChildren(
			...this.#alertsContent(node, form, undefined),
		);
		this.#showProblems(form.problems);
	}

	#showProblems(problems: SettingProblem[]): void {
		const byKey = new Map(problems.map((p) => [p.key, p.message]));
		for (const [key, control] of this.#controls) {
			const message = byKey.get(key);
			control.control.classList.toggle("is-invalid", !!message);
			control.feedback.textContent = message ?? "";
			// show feedback for controls wrapped in another element too
			control.feedback.classList.toggle("d-block", !!message);
		}
	}
}
