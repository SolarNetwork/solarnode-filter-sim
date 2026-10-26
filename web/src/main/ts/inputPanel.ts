import { Tab } from "bootstrap";
import {
	conductorTaskField,
	conductorTaskProblems,
	conductorUidProblem,
	controlIdProblem,
	controlValueField,
	controlValueProblem,
	datumMetadataSourceField,
	datumMetadataSourceProblem,
	EXPRESSION_LANGUAGES,
	NODE_METADATA_SERVICE_UID,
	parseInstructionJobSeconds,
	parseDatumList,
	parseMetadata,
	parseRunAfter,
	parsePlaceholders,
	parseLocationNumber,
	type InputError,
	type InputField,
	type ConductorInput,
	type ConductorTaskInput,
	type ControlInput,
	type DatumMetadataInput,
	type InputTab,
	type RunInputs,
	type UserMetadataInput,
} from "./inputs.ts";
import { JsonEditor } from "./jsonEditor.ts";
import { jsonCompletion } from "./jsonComplete.ts";
import { jsonLint, type JsonSchema } from "./jsonLint.ts";
import {
	NODE_CONTROL_TYPES,
	type ControlPublishMode,
	type SimulationState,
} from "./model.ts";
import type { Workspace } from "./workspace.ts";

/** How long after typing stops, in milliseconds, an input is checked. */
const VALIDATE_DELAY = 300;

/** The browser storage key of the shown input tab. */
const TAB_STORAGE_KEY = "sn-filter-sim.inputTab";

/** The text inputs, by input field. */
const TEXT_INPUTS: Record<Exclude<InputField, "entryPoint">, string> = {
	datum: "#input-datum",
	history: "#input-history",
	unfilteredHistory: "#input-unfiltered",
	opModes: "#input-opmodes",
	localState: "#input-localstate",
	runAfter: "#input-runafter",
	instructionJobSeconds: "#input-instruction-job-seconds",
	placeholders: "#input-placeholders",
	nodeLatitude: "#input-node-latitude",
	nodeLongitude: "#input-node-longitude",
	nodeElevation: "#input-node-elevation",
	allowedCommands: "#input-allowed-commands",
	nodeMetadata: "#input-node-metadata",
};

/** The tab of each text input. */
const FIELD_TABS: Record<Exclude<InputField, "entryPoint">, InputTab> = {
	datum: "datum",
	history: "history",
	unfilteredHistory: "history",
	opModes: "environment",
	localState: "environment",
	runAfter: "environment",
	instructionJobSeconds: "environment",
	placeholders: "environment",
	nodeLatitude: "environment",
	nodeLongitude: "environment",
	nodeElevation: "environment",
	allowedCommands: "environment",
	nodeMetadata: "metadata",
};

/** The JSON inputs, which get a JSON editor that checks their schema. */
const JSON_FIELDS: Partial<Record<InputField, JsonSchema>> = {
	datum: "datumList",
	history: "datumList",
	unfilteredHistory: "datumList",
	nodeMetadata: "metadata",
};

/** The checks of the JSON inputs, which throw an error for a problem. */
const FIELD_CHECKS: Partial<
	Record<Exclude<InputField, "entryPoint">, (text: string) => void>
> = {
	datum: (text) => parseDatumList(text, "Datum"),
	history: (text) => parseDatumList(text, "History datum"),
	unfilteredHistory: (text) =>
		parseDatumList(text, "Unfiltered History datum"),
	nodeMetadata: (text) => parseMetadata(text, "Node metadata"),
	runAfter: (text) => parseRunAfter(text),
	instructionJobSeconds: (text) => parseInstructionJobSeconds(text),
	placeholders: (text) => parsePlaceholders(text),
	nodeLatitude: (text) => parseLocationNumber(text, "Latitude", -90, 90),
	nodeLongitude: (text) => parseLocationNumber(text, "Longitude", -180, 180),
	nodeElevation: (text) => parseLocationNumber(text, "Elevation"),
};

/** The Publish choices of a control, with their descriptions. */
const PUBLISH_MODES: readonly {
	mode: ControlPublishMode;
	label: string;
	desc: string;
}[] = [
	{
		mode: "Never",
		label: "Never",
		desc: "The control never publishes a datum.",
	},
	{
		mode: "Change",
		label: "On change",
		desc: "Each set that changes the control's value publishes a datum.",
	},
	{
		mode: "Update",
		label: "On update",
		desc: "Every set publishes a datum.",
	},
];

/** The placeholder of a control's Value input, by control type. */
const VALUE_PLACEHOLDERS: Record<ControlInput["type"], string> = {
	Boolean: "false",
	Float: "0.0",
	Integer: "0",
	Percent: "0.5",
	String: "",
};

/** The Control Conductor task settings that are checked. */
const TASK_SETTINGS = ["controlId", "offset", "value"] as const;

/** The help of the Control Conductor settings, from the node's messages. */
const CONDUCTOR_HELP = {
	uid: "The name OrchestrateControls instructions use in their service parameter to ask this conductor to run its tasks.",
	predicate:
		"An optional expression that must evaluate to true for the OrchestrateControls instruction to execute the configured tasks. Instruction parameters are variables.",
	controlId: "The ID of the control to change.",
	offset: "The time offset from the orchestration date to change the Control to Value: a number of milliseconds, or an ISO 8601 duration like PT5M. Both can be negative, and can use instruction parameters as placeholders, like {duration}.",
	value: "The control value to set. Instruction parameters are supported as placeholders. With an Expression Language, Value is an expression, with the instruction parameters as variables.",
};

function $<T extends HTMLElement>(selector: string): T {
	return document.querySelector<T>(selector)!;
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

function message(e: unknown): string {
	return e instanceof Error ? e.message : String(e);
}

/**
 * The input panel: tabs for the input datum, the datum histories, the starting
 * state of the node, and its metadata, plus the Entry Point select.
 */
export class InputPanel {
	readonly #inputs: RunInputs;
	readonly #ws: Workspace;
	readonly #timers = new Map<string, number>();
	/**
	 * The tab of each input with a problem, by field, User Metadata id, or
	 * control field.
	 */
	readonly #invalid = new Map<string, InputTab>();
	#finalState: SimulationState | null = null;

	/**
	 * Constructor.
	 *
	 * @param inputs the inputs
	 * @param ws the workspace, for the Entry Point select
	 */
	constructor(inputs: RunInputs, ws: Workspace) {
		this.#inputs = inputs;
		this.#ws = ws;
		this.#bindTabs();
		this.#bindTextInputs();
		this.#bindHistory();
		this.#bindUserMetadata();
		this.#bindDatumMetadata();
		this.#bindControls();
		this.#bindConductors();
		this.#bindEntryPoint();
		$("#use-final-state").addEventListener("click", () =>
			this.useFinalState(),
		);
		$("#use-final-controls").addEventListener("click", () =>
			this.#useFinalControls(),
		);
		ws.addEventListener("change", () => this.#renderEntryPoints());
		inputs.addEventListener("change", () => this.#syncTextInputs());
		this.#renderEntryPoints();
	}

	/**
	 * Set the final state of the last run, which "Use the final state" copies
	 * into the starting state inputs.
	 *
	 * @param state the final state, or `null` if there was no run
	 */
	setFinalState(state: SimulationState | null): void {
		this.#finalState = state;
		$<HTMLButtonElement>("#use-final-state").disabled = !state;
		$<HTMLButtonElement>("#use-final-controls").disabled =
			!state?.controls?.length;
	}

	/**
	 * Show an input problem: switch to its tab, and mark and focus the input.
	 *
	 * @param e the problem
	 */
	showError(e: InputError): void {
		this.#showTab(e.tab);
		this.#setInvalid(e.field, e.tab, e.message);
		this.#fieldElement(e.field)?.focus();
	}

	/* === Tabs === */

	#tabButton(tab: InputTab): HTMLElement {
		return $(`[data-input-tab="${tab}"]`);
	}

	#showTab(tab: InputTab): void {
		Tab.getOrCreateInstance(this.#tabButton(tab)).show();
	}

	#bindTabs(): void {
		document
			.querySelectorAll<HTMLElement>("[data-input-tab]")
			.forEach((button) =>
				button.addEventListener("shown.bs.tab", () => {
					try {
						localStorage.setItem(
							TAB_STORAGE_KEY,
							button.dataset.inputTab!,
						);
					} catch {
						// not saved
					}
				}),
			);
		let tab: string | null = null;
		try {
			tab = localStorage.getItem(TAB_STORAGE_KEY);
		} catch {
			// show the first tab
		}
		if (tab && document.querySelector(`[data-input-tab="${tab}"]`)) {
			this.#showTab(tab as InputTab);
		}
	}

	/* === Checking === */

	#fieldElement(field: string): HTMLElement | null {
		const selector = TEXT_INPUTS[field as keyof typeof TEXT_INPUTS];
		if (selector) {
			return document.querySelector<HTMLElement>(selector);
		}
		return (
			document.querySelector<HTMLElement>(
				`[data-metadata-id="${field}"] textarea`,
			) ??
			document.querySelector<HTMLElement>(`[data-input-field="${field}"]`)
		);
	}

	#setInvalid(field: string, tab: InputTab, problem?: string): void {
		const el = this.#fieldElement(field);
		if (el) {
			el.classList.toggle("is-invalid", !!problem);
			const feedback = el
				.closest(
					".tab-pane, .sim-metadata-item, .sim-control-item, .sim-conductor-item, .sim-conductor-task",
				)
				?.querySelector<HTMLElement>(
					`.invalid-feedback[data-feedback-for="${field}"]`,
				);
			if (feedback) {
				feedback.textContent = problem ?? "";
				// shown even when the input is inside an input group
				feedback.classList.toggle("d-block", !!problem);
			}
		}
		if (problem) {
			this.#invalid.set(field, tab);
		} else {
			this.#invalid.delete(field);
		}
		this.#refreshTabMarks();
	}

	/**
	 * Mark a tab for the schema errors a JSON editor found, which the editor
	 * itself underlines.
	 *
	 * @param field the input field, or the id of a User Metadata Service
	 * @param tab the input's tab
	 * @param errors the number of errors
	 */
	#setSchemaErrors(field: string, tab: InputTab, errors: number): void {
		const key = `${field}#schema`;
		if (errors > 0) {
			this.#invalid.set(key, tab);
		} else {
			this.#invalid.delete(key);
		}
		this.#refreshTabMarks();
	}

	#refreshTabMarks(): void {
		const tabs = new Set(this.#invalid.values());
		document
			.querySelectorAll<HTMLElement>("[data-input-tab]")
			.forEach((button) => {
				const mark =
					button.querySelector<HTMLElement>(".sim-tab-error");
				if (mark) {
					mark.hidden = !tabs.has(
						button.dataset.inputTab as InputTab,
					);
				}
			});
	}

	/** Check an input shortly after typing stops. */
	#scheduleCheck(field: string, tab: InputTab, check: () => void): void {
		window.clearTimeout(this.#timers.get(field));
		this.#timers.set(
			field,
			window.setTimeout(() => {
				this.#timers.delete(field);
				try {
					check();
					this.#setInvalid(field, tab);
				} catch (e) {
					this.#setInvalid(field, tab, message(e));
				}
			}, VALIDATE_DELAY),
		);
	}

	/* === Inputs === */

	#bindTextInputs(): void {
		const state = this.#inputs.state;
		for (const [field, selector] of Object.entries(TEXT_INPUTS) as [
			keyof typeof TEXT_INPUTS,
			string,
		][]) {
			const input = $<HTMLInputElement | HTMLTextAreaElement>(selector);
			input.value = state[field];
			const schema = JSON_FIELDS[field];
			if (schema && input instanceof HTMLTextAreaElement) {
				new JsonEditor(input, [
					jsonLint(schema, (errors) =>
						this.#setSchemaErrors(field, FIELD_TABS[field], errors),
					),
					jsonCompletion(schema),
				]);
			}
			const check = FIELD_CHECKS[field];
			input.addEventListener("input", () => {
				this.#inputs.set(field, input.value);
				if (check) {
					this.#scheduleCheck(field, FIELD_TABS[field], () =>
						check(input.value),
					);
				}
			});
		}
		this.#checkTextInputs();
	}

	/** Check the text inputs that have checks, and mark their problems. */
	#checkTextInputs(): void {
		const state = this.#inputs.state;
		for (const [field, check] of Object.entries(FIELD_CHECKS) as [
			keyof typeof TEXT_INPUTS,
			(text: string) => void,
		][]) {
			window.clearTimeout(this.#timers.get(field));
			this.#timers.delete(field);
			try {
				check(state[field]);
				this.#setInvalid(field, FIELD_TABS[field]);
			} catch (e) {
				this.#setInvalid(field, FIELD_TABS[field], message(e));
			}
		}
	}

	/**
	 * Show inputs that were all replaced, such as by opening a simulation
	 * file, and check them.
	 */
	reload(): void {
		const state = this.#inputs.state;
		for (const [field, selector] of Object.entries(TEXT_INPUTS) as [
			keyof typeof TEXT_INPUTS,
			string,
		][]) {
			$<HTMLInputElement | HTMLTextAreaElement>(selector).value =
				state[field];
		}
		this.#checkTextInputs();
		const same = $<HTMLInputElement>("#input-unfiltered-same");
		same.checked = state.unfilteredSameAsHistory;
		$("#unfiltered-section").hidden = same.checked;
		this.#renderUserMetadata();
		this.#renderDatumMetadata();
		this.#renderControls();
		this.#renderConductors();
		this.#renderEntryPoints();
	}

	/**
	 * Replace a text input, such as the Datum input with captured datum, check
	 * it, and show its tab.
	 *
	 * @param field the input
	 * @param value the new value
	 */
	setText(field: keyof typeof TEXT_INPUTS, value: string): void {
		this.#inputs.set(field, value);
		$<HTMLInputElement | HTMLTextAreaElement>(TEXT_INPUTS[field]).value =
			value;
		const check = FIELD_CHECKS[field];
		if (check) {
			window.clearTimeout(this.#timers.get(field));
			this.#timers.delete(field);
			try {
				check(value);
				this.#setInvalid(field, FIELD_TABS[field]);
			} catch (e) {
				this.#setInvalid(field, FIELD_TABS[field], message(e));
			}
		}
		this.#showTab(FIELD_TABS[field]);
	}

	/**
	 * Show text inputs that changed elsewhere, such as a command allowed from
	 * the inspector, without disturbing the input being typed in.
	 */
	#syncTextInputs(): void {
		const state = this.#inputs.state;
		for (const [field, selector] of Object.entries(TEXT_INPUTS) as [
			keyof typeof TEXT_INPUTS,
			string,
		][]) {
			const input = $<HTMLInputElement | HTMLTextAreaElement>(selector);
			if (
				input !== document.activeElement &&
				input.value !== state[field]
			) {
				input.value = state[field];
			}
		}
	}

	#bindHistory(): void {
		const same = $<HTMLInputElement>("#input-unfiltered-same");
		const section = $("#unfiltered-section");
		same.checked = this.#inputs.state.unfilteredSameAsHistory;
		section.hidden = same.checked;
		same.addEventListener("change", () => {
			this.#inputs.setUnfilteredSameAsHistory(same.checked);
			section.hidden = same.checked;
		});
	}

	/**
	 * Copy the final state of the last run into the starting state inputs.
	 */
	useFinalState(): void {
		const state = this.#finalState;
		if (!state) {
			return;
		}
		const opModes = (state.opModes ?? []).join(", ");
		const localState = state.localStateCsv ?? "";
		this.#inputs.set("opModes", opModes);
		this.#inputs.set("localState", localState);
		$<HTMLInputElement>(TEXT_INPUTS.opModes).value = opModes;
		$<HTMLTextAreaElement>(TEXT_INPUTS.localState).value = localState;
		this.#useFinalControls();
	}

	/**
	 * Copy the final control values of the last run into the Controls
	 * inputs, by control ID.
	 */
	#useFinalControls(): void {
		const finals = new Map(
			(this.#finalState?.controls ?? []).map((c) => [c.controlId, c]),
		);
		let changed = false;
		for (const c of this.#inputs.state.controls) {
			const final = finals.get(c.controlId.trim());
			const value = final?.value ?? "";
			if (final && value !== c.value) {
				this.#inputs.updateControl(c.id, { value });
				changed = true;
			}
		}
		if (changed) {
			this.#renderControls();
		}
	}

	/* === User Metadata === */

	#bindUserMetadata(): void {
		$("#add-user-metadata").addEventListener("click", () => {
			const item = this.#inputs.addUserMetadata();
			this.#renderUserMetadata();
			document
				.querySelector<HTMLInputElement>(
					`[data-metadata-id="${item.id}"] input`,
				)
				?.select();
		});
		this.#renderUserMetadata();
	}

	#renderUserMetadata(): void {
		this.#clearInvalid("metadata", "meta-");
		$("#user-metadata-list").replaceChildren(
			...this.#inputs.state.userMetadata.map((m, i) =>
				this.#userMetadataItem(m, i),
			),
		);
		this.#refreshTabMarks();
	}

	/**
	 * Forget the problems of a list's inputs, before rendering it again.
	 *
	 * @param tab the list's tab
	 * @param prefix the start of the ids of the list's items, or `undefined`
	 *     for every input of the tab
	 */
	#clearInvalid(tab: InputTab, prefix?: string): void {
		for (const [field, t] of [...this.#invalid]) {
			if (
				t === tab &&
				(prefix === undefined || field.startsWith(prefix))
			) {
				this.#invalid.delete(field);
			}
		}
	}

	/**
	 * Render a metadata item: a name input, a remove button, and a metadata
	 * JSON editor, which is checked as you type.
	 */
	#metadataItem(opts: {
		id: string;
		label: string;
		name: string;
		placeholder: string;
		monospace?: boolean;
		removeTitle: string;
		metadata: string;
		onName: (name: string) => void;
		onMetadata: (metadata: string) => void;
		onRemove: () => void;
	}): { card: HTMLElement; name: HTMLInputElement; head: HTMLElement } {
		const card = element(
			"div",
			"card card-body p-2 mb-2 sim-metadata-item",
		);
		card.dataset.metadataId = opts.id;

		const head = element("div", "d-flex gap-2 align-items-center mb-2");
		const nameId = `metadata-name-${opts.id}`;
		const label = element(
			"label",
			"form-label small fw-semibold mb-0 text-nowrap",
			opts.label,
		);
		label.htmlFor = nameId;
		const name = element(
			"input",
			`form-control form-control-sm${opts.monospace ? " font-monospace" : ""}`,
		);
		name.id = nameId;
		name.value = opts.name;
		name.spellcheck = false;
		name.placeholder = opts.placeholder;
		name.addEventListener("input", () => opts.onName(name.value));
		const remove = element(
			"button",
			"btn btn-sm btn-link text-body-secondary sim-tool",
		);
		remove.type = "button";
		remove.title = opts.removeTitle;
		remove.setAttribute("aria-label", remove.title);
		remove.append(element("i", "bi bi-trash"));
		remove.addEventListener("click", opts.onRemove);
		head.append(label, name, remove);

		const json = element(
			"textarea",
			"form-control form-control-sm font-monospace sim-json-input",
		);
		json.rows = 6;
		json.spellcheck = false;
		json.value = opts.metadata;
		json.placeholder = '{"pm": {"tariffs": {"schedule": "…"}}}';
		json.setAttribute("aria-label", "Metadata JSON");
		const feedback = element("div", "invalid-feedback");
		feedback.dataset.feedbackFor = opts.id;
		const check = () => parseMetadata(json.value, "The metadata");
		json.addEventListener("input", () => {
			opts.onMetadata(json.value);
			this.#scheduleCheck(opts.id, "metadata", check);
		});
		card.append(head, json, feedback);
		new JsonEditor(json, [
			jsonLint("metadata", (errors) =>
				this.#setSchemaErrors(opts.id, "metadata", errors),
			),
			jsonCompletion("metadata"),
		]);
		try {
			check();
		} catch (e) {
			// mark it once the item is in the document
			window.setTimeout(() =>
				this.#setInvalid(opts.id, "metadata", message(e)),
			);
		}
		return { card, name, head };
	}

	#userMetadataItem(item: UserMetadataInput, index: number): HTMLElement {
		const uidWarning = element(
			"div",
			"form-text text-warning-emphasis mt-n1 mb-2",
		);
		const checkUid = (uid: string) => {
			const name = uid.trim();
			const names = [
				NODE_METADATA_SERVICE_UID,
				...this.#inputs.state.userMetadata
					.filter((m) => m.id !== item.id)
					.map((m) => m.uid.trim()),
			];
			uidWarning.textContent = !name
				? "Filters cannot find a service without a Service Name."
				: names.includes(name)
					? "Another metadata service has this Service Name, so filters use the first one."
					: "";
			uidWarning.hidden = !uidWarning.textContent;
		};
		const { card, head } = this.#metadataItem({
			id: item.id,
			label: "Service Name",
			name: item.uid,
			placeholder: `User Metadata Service ${index + 1}`,
			removeTitle: "Remove this User Metadata Service",
			metadata: item.metadata,
			onName: (uid) => {
				this.#inputs.updateUserMetadata(item.id, { uid });
				checkUid(uid);
			},
			onMetadata: (metadata) =>
				this.#inputs.updateUserMetadata(item.id, { metadata }),
			onRemove: () => {
				this.#inputs.removeUserMetadata(item.id);
				this.#renderUserMetadata();
			},
		});
		head.after(uidWarning);
		checkUid(item.uid);
		return card;
	}

	/* === Datum Metadata === */

	#bindDatumMetadata(): void {
		$("#add-datum-metadata").addEventListener("click", () => {
			const item = this.#inputs.addDatumMetadata();
			this.#renderDatumMetadata();
			document
				.querySelector<HTMLInputElement>(
					`[data-input-field="${datumMetadataSourceField(item.id)}"]`,
				)
				?.focus();
		});
		this.#renderDatumMetadata();
	}

	#renderDatumMetadata(): void {
		this.#clearInvalid("metadata", "dmeta-");
		$("#datum-metadata-list").replaceChildren(
			...this.#inputs.state.datumMetadata.map((m) =>
				this.#datumMetadataItem(m),
			),
		);
		this.#checkDatumMetadataSources();
	}

	/**
	 * Check every source ID, as a change to one can make another a repeat.
	 */
	#checkDatumMetadataSources(): void {
		const items = this.#inputs.state.datumMetadata;
		for (const m of items) {
			this.#setInvalid(
				datumMetadataSourceField(m.id),
				"metadata",
				datumMetadataSourceProblem(items, m) ?? undefined,
			);
		}
	}

	#datumMetadataItem(item: DatumMetadataInput): HTMLElement {
		const field = datumMetadataSourceField(item.id);
		const { card, name, head } = this.#metadataItem({
			id: item.id,
			label: "Source ID",
			name: item.sourceId,
			placeholder: "/meter/1",
			monospace: true,
			removeTitle: "Remove this datum metadata",
			metadata: item.metadata,
			onName: (sourceId) => {
				this.#inputs.updateDatumMetadata(item.id, { sourceId });
				const key = "datumMetadata#sources";
				window.clearTimeout(this.#timers.get(key));
				this.#timers.set(
					key,
					window.setTimeout(() => {
						this.#timers.delete(key);
						this.#checkDatumMetadataSources();
					}, VALIDATE_DELAY),
				);
			},
			onMetadata: (metadata) =>
				this.#inputs.updateDatumMetadata(item.id, { metadata }),
			onRemove: () => {
				this.#inputs.removeDatumMetadata(item.id);
				this.#renderDatumMetadata();
			},
		});
		name.dataset.inputField = field;
		const feedback = element("div", "invalid-feedback mt-n1 mb-2");
		feedback.dataset.feedbackFor = field;
		head.after(feedback);
		return card;
	}

	/* === Controls === */

	/**
	 * Add a control, such as one a filter sets, show it, and focus its value.
	 *
	 * @param controlId the control ID, or `undefined` for a new one
	 */
	addControl(controlId?: string): void {
		const item = this.#inputs.addControl(controlId);
		this.#renderControls();
		this.#showTab("controls");
		document
			.querySelector<HTMLInputElement>(
				`[data-input-field="${controlId ? controlValueField(item.id) : item.id}"]`,
			)
			?.select();
	}

	#bindControls(): void {
		$("#add-control").addEventListener("click", () => this.addControl());
		this.#renderControls();
	}

	#renderControls(): void {
		this.#clearInvalid("controls", "ctl-");
		$("#control-list").replaceChildren(
			...this.#inputs.state.controls.map((c) => this.#controlItem(c)),
		);
		this.#checkControls();
	}

	/** Check every control, as a change to one ID can change the others. */
	#checkControls(): void {
		const controls = this.#inputs.state.controls;
		for (const c of controls) {
			this.#setInvalid(
				c.id,
				"controls",
				controlIdProblem(controls, c) ?? undefined,
			);
			this.#setInvalid(
				controlValueField(c.id),
				"controls",
				controlValueProblem(c.type, c.value) ?? undefined,
			);
		}
		this.#refreshTabMarks();
		this.#refreshControlChoices();
	}

	#scheduleControlCheck(): void {
		const key = "controls#check";
		window.clearTimeout(this.#timers.get(key));
		this.#timers.set(
			key,
			window.setTimeout(() => {
				this.#timers.delete(key);
				this.#checkControls();
			}, VALIDATE_DELAY),
		);
	}

	#controlItem(item: ControlInput): HTMLElement {
		const card = element("div", "card card-body p-2 mb-2 sim-control-item");
		card.dataset.controlId = item.id;
		const update = (changes: Partial<Omit<ControlInput, "id">>) => {
			this.#inputs.updateControl(item.id, changes);
			this.#scheduleControlCheck();
		};

		const head = element("div", "d-flex gap-2 align-items-center");
		const idId = `control-id-${item.id}`;
		const idLabel = element(
			"label",
			"form-label small fw-semibold mb-0 text-nowrap",
			"Control ID",
		);
		idLabel.htmlFor = idId;
		const controlId = element(
			"input",
			"form-control form-control-sm font-monospace",
		);
		controlId.id = idId;
		controlId.dataset.inputField = item.id;
		controlId.value = item.controlId;
		controlId.spellcheck = false;
		controlId.placeholder = "/power/limit";
		controlId.addEventListener("input", () =>
			update({ controlId: controlId.value }),
		);
		const remove = element(
			"button",
			"btn btn-sm btn-link text-body-secondary sim-tool",
		);
		remove.type = "button";
		remove.title = "Remove this control";
		remove.setAttribute("aria-label", remove.title);
		remove.append(element("i", "bi bi-trash"));
		remove.addEventListener("click", () => {
			this.#inputs.removeControl(item.id);
			this.#renderControls();
		});
		head.append(idLabel, controlId, remove);
		const idFeedback = element("div", "invalid-feedback");
		idFeedback.dataset.feedbackFor = item.id;

		const row = element("div", "d-flex gap-2 mt-2");
		const typeId = `control-type-${item.id}`;
		const typeCol = element("div", "sim-control-type");
		const typeLabel = element(
			"label",
			"form-label small fw-semibold mb-0",
			"Type",
		);
		typeLabel.htmlFor = typeId;
		const type = element("select", "form-select form-select-sm");
		type.id = typeId;
		type.append(...NODE_CONTROL_TYPES.map((t) => new Option(t, t)));
		type.value = item.type;
		typeCol.append(typeLabel, type);

		const valueId = `control-value-${item.id}`;
		const valueCol = element("div", "flex-grow-1");
		const valueLabel = element(
			"label",
			"form-label small fw-semibold mb-0",
			"Value",
		);
		valueLabel.htmlFor = valueId;
		const value = element("input", "form-control form-control-sm");
		value.id = valueId;
		value.dataset.inputField = controlValueField(item.id);
		value.value = item.value;
		value.spellcheck = false;
		value.placeholder = VALUE_PLACEHOLDERS[item.type];
		value.title = "The starting value, or empty for none";
		value.addEventListener("input", () => update({ value: value.value }));
		valueCol.append(valueLabel, value);
		type.addEventListener("change", () => {
			const t = type.value as ControlInput["type"];
			value.placeholder = VALUE_PLACEHOLDERS[t];
			update({ type: t });
		});
		row.append(typeCol, valueCol);
		const valueFeedback = element("div", "invalid-feedback");
		valueFeedback.dataset.feedbackFor = controlValueField(item.id);

		const propRow = element("div", "d-flex gap-2 mt-2");
		const propNameId = `control-prop-${item.id}`;
		const propNameCol = element("div", "flex-grow-1");
		const propNameLabel = element(
			"label",
			"form-label small fw-semibold mb-0",
			"Property",
		);
		propNameLabel.htmlFor = propNameId;
		const propName = element(
			"input",
			"form-control form-control-sm font-monospace",
		);
		propName.id = propNameId;
		propName.value = item.propertyName;
		propName.spellcheck = false;
		propName.placeholder = "val";
		propName.title =
			"The datum property of the value the control publishes, like a Modbus or GPIO control's Property setting. Empty for val, with a number also as v.";
		propName.addEventListener("input", () =>
			update({ propertyName: propName.value }),
		);
		propNameCol.append(propNameLabel, propName);
		const propTypeId = `control-prop-type-${item.id}`;
		const propTypeCol = element("div", "sim-control-type");
		const propTypeLabel = element(
			"label",
			"form-label small fw-semibold mb-0",
			"Property Type",
		);
		propTypeLabel.htmlFor = propTypeId;
		const propType = element("select", "form-select form-select-sm");
		propType.id = propTypeId;
		propType.title =
			"The datum property type of the value, like a GPIO control's Property Type setting. A value that is not a number stays a Status property.";
		propType.append(
			new Option("Status", ""),
			new Option("Instantaneous", "Instantaneous"),
			new Option("Accumulating", "Accumulating"),
		);
		propType.value = item.propertyType;
		propType.addEventListener("change", () =>
			update({
				propertyType: propType.value as ControlInput["propertyType"],
			}),
		);
		propTypeCol.append(propTypeLabel, propType);
		propRow.append(propNameCol, propTypeCol);

		const publishRow = element("div", "d-flex gap-2 align-items-end mt-2");
		const publishId = `control-publish-${item.id}`;
		const publishCol = element("div", "sim-control-type");
		const publishLabel = element(
			"label",
			"form-label small fw-semibold mb-0",
			"Publish",
		);
		publishLabel.htmlFor = publishId;
		const publish = element("select", "form-select form-select-sm");
		publish.id = publishId;
		publish.append(
			...PUBLISH_MODES.map((m) => {
				const option = new Option(m.label, m.mode);
				option.title = m.desc;
				return option;
			}),
		);
		publish.value = item.publish;
		publishCol.append(publishLabel, publish);

		const persistId = `control-persist-${item.id}`;
		const persistCheck = element("div", "form-check mb-1");
		const persist = element("input", "form-check-input");
		persist.type = "checkbox";
		persist.id = persistId;
		persist.checked = item.persist;
		const persistLabel = element(
			"label",
			"form-check-label small",
			"Persist",
		);
		persistLabel.htmlFor = persistId;
		persistCheck.title =
			"Persist the published datum. A node's Control datum source does not by default.";
		persistCheck.append(persist, persistLabel);
		persist.addEventListener("change", () =>
			update({ persist: persist.checked }),
		);
		publishRow.append(publishCol, persistCheck);

		const publishDesc = element("div", "form-text mt-1");
		const showPublish = () => {
			const mode = publish.value as ControlPublishMode;
			publishDesc.textContent =
				PUBLISH_MODES.find((m) => m.mode === mode)?.desc ?? "";
			persistCheck.hidden = mode === "Never";
		};
		publish.addEventListener("change", () => {
			showPublish();
			update({ publish: publish.value as ControlPublishMode });
		});
		showPublish();

		card.append(
			head,
			idFeedback,
			row,
			valueFeedback,
			propRow,
			publishRow,
			publishDesc,
		);
		return card;
	}

	/* === Control Conductors === */

	/**
	 * Add a Control Conductor, such as one an Instructor names, show it, and
	 * select its first task's value.
	 *
	 * @param uid the Service Name, or `undefined` for a new one
	 */
	addConductor(uid?: string): void {
		const item = this.#inputs.addConductor(uid);
		this.#renderConductors();
		this.#showTab("controls");
		const task = item.tasks[0];
		document
			.querySelector<HTMLElement>(
				`[data-input-field="${uid && task ? conductorTaskField(task.id, task.controlId ? "value" : "controlId") : item.id}"]`,
			)
			?.focus();
		if (!uid) {
			document
				.querySelector<HTMLInputElement>(
					`[data-input-field="${item.id}"]`,
				)
				?.select();
		}
	}

	#bindConductors(): void {
		$("#add-conductor").addEventListener("click", () =>
			this.addConductor(),
		);
		this.#renderConductors();
	}

	#renderConductors(): void {
		this.#clearInvalid("controls", "cc-");
		$("#conductor-list").replaceChildren(
			...this.#inputs.state.conductors.map((c) => this.#conductorItem(c)),
		);
		this.#checkConductors();
	}

	/**
	 * Check every conductor, as a change to one Service Name can change the
	 * others, and the control IDs change the warnings.
	 */
	#checkConductors(): void {
		const conductors = this.#inputs.state.conductors;
		const controlIds = new Set(this.#inputs.controlIds);
		for (const c of conductors) {
			this.#setInvalid(
				c.id,
				"controls",
				conductorUidProblem(conductors, c) ?? undefined,
			);
			const noTasks = document.querySelector<HTMLElement>(
				`[data-conductor-warning-for="${c.id}"]`,
			);
			if (noTasks) {
				noTasks.hidden = c.tasks.length > 0;
			}
			for (const t of c.tasks) {
				const problems = conductorTaskProblems(t);
				for (const setting of TASK_SETTINGS) {
					this.#setInvalid(
						conductorTaskField(t.id, setting),
						"controls",
						problems[setting],
					);
				}
				const controlId = t.controlId.trim();
				const warning = document.querySelector<HTMLElement>(
					`[data-control-warning-for="${t.id}"]`,
				);
				if (warning) {
					warning.hidden =
						!controlId ||
						controlId.includes("{") ||
						controlIds.has(controlId);
					warning.querySelector("code")?.replaceChildren(controlId);
				}
			}
		}
		this.#refreshTabMarks();
	}

	#scheduleConductorCheck(): void {
		const key = "conductors#check";
		window.clearTimeout(this.#timers.get(key));
		this.#timers.set(
			key,
			window.setTimeout(() => {
				this.#timers.delete(key);
				this.#checkConductors();
			}, VALIDATE_DELAY),
		);
	}

	/**
	 * Show the control IDs that conductor tasks offer, and check the tasks'
	 * controls again.
	 */
	#refreshControlChoices(): void {
		$("#control-id-choices").replaceChildren(
			...this.#inputs.controlIds.map((id) => new Option(id)),
		);
		this.#checkConductors();
	}

	/** Create a small label, with help as its tooltip. */
	#label(text: string, htmlFor: string, help?: string): HTMLLabelElement {
		const label = element(
			"label",
			"form-label small fw-semibold mb-0 text-nowrap",
			text,
		);
		label.htmlFor = htmlFor;
		if (help) {
			label.title = help;
		}
		return label;
	}

	/**
	 * Create an Expression Language select.
	 *
	 * @param value the language
	 * @param label the select's label
	 * @param noneLabel the label of no language
	 * @param onChange called with the new language
	 */
	#languageSelect(
		value: string,
		label: string,
		noneLabel: string,
		onChange: (language: string) => void,
	): HTMLSelectElement {
		const select = element(
			"select",
			"form-select form-select-sm sim-conductor-language",
		);
		select.append(
			...EXPRESSION_LANGUAGES.map(
				(l) => new Option(l.id ? l.label : noneLabel, l.id),
			),
		);
		select.value = value;
		select.title = label;
		select.setAttribute("aria-label", label);
		select.addEventListener("change", () => onChange(select.value));
		return select;
	}

	#feedback(field: string): HTMLElement {
		const feedback = element("div", "invalid-feedback");
		feedback.dataset.feedbackFor = field;
		return feedback;
	}

	#conductorItem(item: ConductorInput): HTMLElement {
		const card = element(
			"div",
			"card card-body p-2 mb-2 sim-conductor-item",
		);
		card.dataset.conductorId = item.id;
		const update = (
			changes: Partial<Omit<ConductorInput, "id" | "tasks">>,
		) => {
			this.#inputs.updateConductor(item.id, changes);
			this.#scheduleConductorCheck();
		};

		const head = element("div", "d-flex gap-2 align-items-center");
		const uidId = `conductor-uid-${item.id}`;
		const uid = element("input", "form-control form-control-sm");
		uid.id = uidId;
		uid.dataset.inputField = item.id;
		uid.value = item.uid;
		uid.spellcheck = false;
		uid.placeholder = "HVAC DR";
		uid.addEventListener("input", () => update({ uid: uid.value }));
		const remove = element(
			"button",
			"btn btn-sm btn-link text-body-secondary sim-tool",
		);
		remove.type = "button";
		remove.title = "Remove this Control Conductor";
		remove.setAttribute("aria-label", remove.title);
		remove.append(element("i", "bi bi-trash"));
		remove.addEventListener("click", () => {
			this.#inputs.removeConductor(item.id);
			this.#renderConductors();
		});
		head.append(
			this.#label("Service Name", uidId, CONDUCTOR_HELP.uid),
			uid,
			remove,
		);

		const predicateHead = element(
			"div",
			"d-flex gap-2 align-items-center justify-content-between mt-2 mb-1",
		);
		const predicateId = `conductor-predicate-${item.id}`;
		const predicate = element(
			"textarea",
			"form-control form-control-sm font-monospace",
		);
		predicate.id = predicateId;
		predicate.rows = 1;
		predicate.value = item.predicate;
		predicate.spellcheck = false;
		predicate.placeholder = "mode != null";
		predicate.addEventListener("input", () =>
			update({ predicate: predicate.value }),
		);
		predicateHead.append(
			this.#label(
				"Predicate Expression",
				predicateId,
				CONDUCTOR_HELP.predicate,
			),
			this.#languageSelect(
				item.predicateLanguage,
				"Predicate Expression Language",
				"(none)",
				(predicateLanguage) => update({ predicateLanguage }),
			),
		);

		const tasksHead = element(
			"div",
			"form-label small fw-semibold mt-2 mb-1",
			"Tasks",
		);
		const noTasks = element(
			"div",
			"form-text text-warning-emphasis mt-0 mb-1",
			"Without tasks, the conductor declines every OrchestrateControls instruction.",
		);
		noTasks.dataset.conductorWarningFor = item.id;
		noTasks.hidden = item.tasks.length > 0;
		const tasks = element("div");
		tasks.append(
			...item.tasks.map((t, i) => this.#conductorTask(item, t, i)),
		);
		const addTask = element("button", "btn btn-sm btn-link px-0");
		addTask.type = "button";
		const addIcon = element("i", "bi bi-plus-lg");
		addIcon.setAttribute("aria-hidden", "true");
		addTask.append(addIcon, " Add a task");
		addTask.addEventListener("click", () => {
			const task = this.#inputs.addConductorTask(item.id);
			this.#renderConductors();
			if (task) {
				document
					.querySelector<HTMLElement>(
						`[data-input-field="${conductorTaskField(task.id, task.controlId ? "value" : "controlId")}"]`,
					)
					?.focus();
			}
		});

		card.append(
			head,
			this.#feedback(item.id),
			predicateHead,
			predicate,
			tasksHead,
			noTasks,
			tasks,
			addTask,
		);
		return card;
	}

	#conductorTask(
		conductor: ConductorInput,
		task: ConductorTaskInput,
		index: number,
	): HTMLElement {
		const el = element("div", "sim-conductor-task");
		const update = (changes: Partial<Omit<ConductorTaskInput, "id">>) => {
			this.#inputs.updateConductorTask(conductor.id, task.id, changes);
			this.#scheduleConductorCheck();
		};
		const field = (setting: (typeof TASK_SETTINGS)[number]) =>
			conductorTaskField(task.id, setting);

		const head = element("div", "d-flex align-items-center");
		head.append(
			element(
				"span",
				"small text-body-secondary flex-grow-1",
				`Task ${index + 1}`,
			),
		);
		const remove = element(
			"button",
			"btn btn-sm btn-link text-body-secondary sim-tool",
		);
		remove.type = "button";
		remove.title = `Remove task ${index + 1}`;
		remove.setAttribute("aria-label", remove.title);
		remove.append(element("i", "bi bi-x-lg"));
		remove.addEventListener("click", () => {
			this.#inputs.removeConductorTask(conductor.id, task.id);
			this.#renderConductors();
		});
		head.append(remove);

		const row = element("div", "d-flex gap-2");
		const controlCol = element("div", "flex-grow-1 sim-conductor-control");
		const controlId = `conductor-control-${task.id}`;
		const control = element(
			"input",
			"form-control form-control-sm font-monospace",
		);
		control.id = controlId;
		control.dataset.inputField = field("controlId");
		control.value = task.controlId;
		control.spellcheck = false;
		control.placeholder = "/hvac/1/mode";
		control.setAttribute("list", "control-id-choices");
		control.addEventListener("input", () =>
			update({ controlId: control.value }),
		);
		controlCol.append(
			this.#label("Control", controlId, CONDUCTOR_HELP.controlId),
			control,
			this.#feedback(field("controlId")),
		);
		const offsetCol = element("div", "sim-conductor-offset");
		const offsetId = `conductor-offset-${task.id}`;
		const offset = element(
			"input",
			"form-control form-control-sm font-monospace",
		);
		offset.id = offsetId;
		offset.dataset.inputField = field("offset");
		offset.value = task.offset;
		offset.spellcheck = false;
		offset.placeholder = "-PT5M";
		offset.addEventListener("input", () =>
			update({ offset: offset.value }),
		);
		offsetCol.append(
			this.#label("Offset", offsetId, CONDUCTOR_HELP.offset),
			offset,
			this.#feedback(field("offset")),
		);
		row.append(controlCol, offsetCol);

		const warning = element("div", "form-text text-warning-emphasis mt-1");
		warning.dataset.controlWarningFor = task.id;
		warning.hidden = true;
		const addControl = element(
			"button",
			"btn btn-sm btn-link p-0 align-baseline",
			"Add this control",
		);
		addControl.type = "button";
		addControl.addEventListener("click", () =>
			this.addControl(control.value.trim()),
		);
		warning.append(
			"The control ",
			element("code", "text-break"),
			" is not one of the Controls, so setting it is declined. ",
			addControl,
		);

		const valueHead = element(
			"div",
			"d-flex gap-2 align-items-center justify-content-between mt-1 mb-1",
		);
		const valueId = `conductor-value-${task.id}`;
		const value = element(
			"textarea",
			"form-control form-control-sm font-monospace",
		);
		value.id = valueId;
		value.dataset.inputField = field("value");
		value.rows = 1;
		value.value = task.value;
		value.spellcheck = false;
		value.placeholder = task.language ? "mode == 'Cool' ? 1 : 2" : "1";
		value.addEventListener("input", () => update({ value: value.value }));
		valueHead.append(
			this.#label("Value", valueId, CONDUCTOR_HELP.value),
			this.#languageSelect(
				task.language,
				"Expression Language",
				"Static Value",
				(language) => {
					value.placeholder = language
						? "mode == 'Cool' ? 1 : 2"
						: "1";
					update({ language });
				},
			),
		);

		el.append(
			head,
			row,
			warning,
			valueHead,
			value,
			this.#feedback(field("value")),
		);
		return el;
	}

	/* === Entry Point === */

	#bindEntryPoint(): void {
		const select = $<HTMLSelectElement>("#entry-point");
		select.disabled = false;
		select.addEventListener("change", () =>
			this.#inputs.set("entryPoint", select.value),
		);
	}

	#renderEntryPoints(): void {
		const ws = this.#ws;
		const select = $<HTMLSelectElement>("#entry-point");
		const main = ws.filter(ws.mainChainId);
		const options: HTMLElement[] = [
			new Option(
				`Main Filter Chain${main ? ` (${ws.label(main)})` : ""}`,
				"",
			),
		];
		const others = ws.filters
			.filter((f) => f.id !== ws.mainChainId && ws.isSupported(f))
			.map((f) => ({
				id: f.id,
				label: ws.label(f),
				chain: ws.isChain(f),
			}))
			.sort((a, b) => a.label.localeCompare(b.label));
		for (const [title, chain] of [
			["Chains", true],
			["Filters", false],
		] as const) {
			const group = others.filter((f) => f.chain === chain);
			if (group.length) {
				const optgroup = element("optgroup");
				optgroup.label = title;
				optgroup.append(...group.map((f) => new Option(f.label, f.id)));
				options.push(optgroup);
			}
		}
		select.replaceChildren(...options);
		const current = this.#inputs.state.entryPoint;
		if (current && others.some((f) => f.id === current)) {
			select.value = current;
		} else {
			select.value = "";
			if (current) {
				this.#inputs.set("entryPoint", "");
			}
		}
	}
}
