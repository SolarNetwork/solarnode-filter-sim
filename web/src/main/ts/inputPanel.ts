import { Tab } from "bootstrap";
import {
	NODE_METADATA_SERVICE_UID,
	parseDatumList,
	parseMetadata,
	parseRunAfter,
	parsePlaceholders,
	parseDatumMetadata,
	parseLocationNumber,
	type InputError,
	type InputField,
	type InputTab,
	type RunInputs,
	type UserMetadataInput,
} from "./inputs.ts";
import { JsonEditor } from "./jsonEditor.ts";
import { jsonLint, type JsonSchema } from "./jsonLint.ts";
import type { SimulationState } from "./model.ts";
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
	placeholders: "#input-placeholders",
	datumMetadata: "#input-datum-metadata",
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
	placeholders: "environment",
	datumMetadata: "datum-metadata",
	nodeLatitude: "environment",
	nodeLongitude: "environment",
	nodeElevation: "environment",
	allowedCommands: "environment",
	nodeMetadata: "node-metadata",
};

/** The JSON inputs, which get a JSON editor that checks their schema. */
const JSON_FIELDS: Partial<Record<InputField, JsonSchema>> = {
	datum: "datumList",
	history: "datumList",
	unfilteredHistory: "datumList",
	nodeMetadata: "metadata",
	datumMetadata: "datumMetadata",
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
	placeholders: (text) => parsePlaceholders(text),
	datumMetadata: (text) => parseDatumMetadata(text),
	nodeLatitude: (text) => parseLocationNumber(text, "Latitude", -90, 90),
	nodeLongitude: (text) => parseLocationNumber(text, "Longitude", -180, 180),
	nodeElevation: (text) => parseLocationNumber(text, "Elevation"),
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
	/** The tab of each input with a problem, by field or User Metadata id. */
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
		this.#bindEntryPoint();
		$("#use-final-state").addEventListener("click", () =>
			this.useFinalState(),
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
		return document.querySelector<HTMLElement>(
			`[data-metadata-id="${field}"] textarea`,
		);
	}

	#setInvalid(field: string, tab: InputTab, problem?: string): void {
		const el = this.#fieldElement(field);
		if (el) {
			el.classList.toggle("is-invalid", !!problem);
			const feedback = el
				.closest(".tab-pane, .sim-metadata-item")
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
		this.#renderEntryPoints();
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
		const list = $("#user-metadata-list");
		for (const field of [...this.#invalid.keys()]) {
			if (this.#invalid.get(field) === "user-metadata") {
				this.#invalid.delete(field);
			}
		}
		list.replaceChildren(
			...this.#inputs.state.userMetadata.map((m, i) =>
				this.#userMetadataItem(m, i),
			),
		);
		this.#refreshTabMarks();
	}

	#userMetadataItem(item: UserMetadataInput, index: number): HTMLElement {
		const card = element(
			"div",
			"card card-body p-2 mb-2 sim-metadata-item",
		);
		card.dataset.metadataId = item.id;

		const head = element("div", "d-flex gap-2 align-items-center mb-2");
		const uidId = `user-metadata-uid-${item.id}`;
		const label = element(
			"label",
			"form-label small fw-semibold mb-0 text-nowrap",
			"Service Name",
		);
		label.htmlFor = uidId;
		const uid = element("input", "form-control form-control-sm");
		uid.id = uidId;
		uid.value = item.uid;
		uid.spellcheck = false;
		uid.placeholder = `User Metadata Service ${index + 1}`;
		const remove = element(
			"button",
			"btn btn-sm btn-link text-body-secondary sim-tool",
		);
		remove.type = "button";
		remove.title = "Remove this User Metadata Service";
		remove.setAttribute("aria-label", remove.title);
		remove.append(element("i", "bi bi-trash"));
		remove.addEventListener("click", () => {
			this.#inputs.removeUserMetadata(item.id);
			this.#renderUserMetadata();
		});
		head.append(label, uid, remove);

		const uidWarning = element(
			"div",
			"form-text text-warning-emphasis mt-n1 mb-2",
		);
		const checkUid = () => {
			const name = uid.value.trim();
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
		uid.addEventListener("input", () => {
			this.#inputs.updateUserMetadata(item.id, { uid: uid.value });
			checkUid();
		});
		checkUid();

		const json = element(
			"textarea",
			"form-control form-control-sm font-monospace sim-json-input",
		);
		json.rows = 6;
		json.spellcheck = false;
		json.value = item.metadata;
		json.placeholder = '{"pm": {"tariffs": {"schedule": "…"}}}';
		json.setAttribute("aria-label", "Metadata JSON");
		const feedback = element("div", "invalid-feedback");
		feedback.dataset.feedbackFor = item.id;
		const check = () => parseMetadata(json.value, "The metadata");
		json.addEventListener("input", () => {
			this.#inputs.updateUserMetadata(item.id, { metadata: json.value });
			this.#scheduleCheck(item.id, "user-metadata", check);
		});
		card.append(head, uidWarning, json, feedback);
		new JsonEditor(json, [
			jsonLint("metadata", (errors) =>
				this.#setSchemaErrors(item.id, "user-metadata", errors),
			),
		]);
		try {
			check();
		} catch (e) {
			// mark it once the item is in the document
			window.setTimeout(() =>
				this.#setInvalid(item.id, "user-metadata", message(e)),
			);
		}
		return card;
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
