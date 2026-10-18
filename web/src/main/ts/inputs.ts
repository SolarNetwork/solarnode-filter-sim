/**
 * The inputs of a simulation run: the input datum, the datum histories, and
 * the starting state of the simulated node, including its metadata.
 */

import type {
	SimulationEnvironment,
	SimulationHistory,
	UserMetadataConfig,
} from "./model.ts";

/** The Service Name of SolarNode's node metadata service. */
export const NODE_METADATA_SERVICE_UID = "Node Metadata Service";

/** The input tabs. */
export type InputTab =
	"datum" | "history" | "environment" | "node-metadata" | "user-metadata";

/** A simulated User Metadata Service, as entered. */
export interface UserMetadataInput {
	/** A unique id, for the UI. */
	id: string;
	/** The Service Name. */
	uid: string;
	/** The metadata JSON. */
	metadata: string;
}

/** The inputs, as entered, which are saved in the browser. */
export interface InputsState {
	version: 1;
	datum: string;
	history: string;
	unfilteredHistory: string;
	/** `true` to use the History datum for the unfiltered history too. */
	unfilteredSameAsHistory: boolean;
	/** Comma-delimited operational modes. */
	opModes: string;
	/** Local State CSV. */
	localState: string;
	nodeMetadata: string;
	userMetadata: UserMetadataInput[];
	/** The `id` of the filter to apply, or `""` for the Main chain. */
	entryPoint: string;
}

/** The text fields of the inputs. */
export type InputField = Exclude<
	keyof InputsState,
	"version" | "unfilteredSameAsHistory" | "userMetadata"
>;

/** The parts of a simulation request that come from the inputs. */
export interface RequestInputs {
	datum: unknown[];
	history: SimulationHistory | null;
	environment: SimulationEnvironment;
	entryPoint: string | null;
}

/** A problem with an input. */
export class InputError extends Error {
	/**
	 * Constructor.
	 *
	 * @param message the problem
	 * @param tab the tab with the input
	 * @param field the input field, or the id of a User Metadata Service
	 */
	constructor(
		message: string,
		readonly tab: InputTab,
		readonly field: string,
	) {
		super(message);
		this.name = "InputError";
	}
}

const STORAGE_KEY = "sn-filter-sim.inputs";
const SAVE_DELAY = 300;

function emptyState(): InputsState {
	return {
		version: 1,
		datum: "",
		history: "",
		unfilteredHistory: "",
		unfilteredSameAsHistory: true,
		opModes: "",
		localState: "",
		nodeMetadata: "",
		userMetadata: [],
		entryPoint: "",
	};
}

function parseJson(text: string, label: string): unknown {
	try {
		return JSON.parse(text);
	} catch (e) {
		throw new Error(
			`${label} is not valid JSON: ${e instanceof Error ? e.message : e}`,
			{ cause: e },
		);
	}
}

function isObject(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * Parse datum JSON: an object, or an array of objects.
 *
 * @param text the JSON
 * @param label a description of the datum for messages, like "Datum"
 * @returns the datum, or an empty array if the text is blank
 * @throws Error if the JSON is not valid
 */
export function parseDatumList(text: string, label: string): unknown[] {
	if (!text.trim()) {
		return [];
	}
	const value = parseJson(text, label);
	const list = Array.isArray(value) ? value : [value];
	list.forEach((d, i) => {
		if (!isObject(d)) {
			throw new Error(
				list.length === 1 && !Array.isArray(value)
					? `${label} must be a JSON object, or an array of objects.`
					: `${label} ${i + 1} is not a JSON object.`,
			);
		}
	});
	return list;
}

/**
 * Parse metadata JSON, like `{"m":{…},"pm":{…},"t":[…]}`.
 *
 * @param text the JSON
 * @param label a description of the metadata for messages
 * @returns the metadata, or `null` if the text is blank
 * @throws Error if the JSON is not a valid object
 */
export function parseMetadata(text: string, label: string): unknown {
	if (!text.trim()) {
		return null;
	}
	const value = parseJson(text, label);
	if (!isObject(value)) {
		throw new Error(`${label} must be a JSON object.`);
	}
	return value;
}

/**
 * Parse operational modes.
 *
 * @param text the comma-delimited modes
 * @returns the modes
 */
export function parseOpModes(text: string): string[] {
	return text
		.split(",")
		.map((m) => m.trim())
		.filter((m) => m.length > 0);
}

/**
 * The inputs of a simulation run.
 *
 * Changes emit a `change` event, and are saved in the browser's local storage.
 */
export class RunInputs extends EventTarget {
	#state: InputsState;
	#saveTimer?: number;
	#ids = 0;

	/**
	 * Constructor.
	 *
	 * @param state the initial state, or `undefined` for empty inputs
	 */
	constructor(state?: InputsState) {
		super();
		this.#state = state ?? emptyState();
	}

	/**
	 * Load the inputs saved in the browser.
	 *
	 * @returns the inputs, which are empty if nothing valid was saved
	 */
	static load(): RunInputs {
		try {
			const json = localStorage.getItem(STORAGE_KEY);
			if (json) {
				const saved = JSON.parse(json) as Partial<InputsState>;
				if (saved?.version === 1) {
					const state = { ...emptyState(), ...saved };
					if (!Array.isArray(state.userMetadata)) {
						state.userMetadata = [];
					}
					return new RunInputs(state);
				}
			}
		} catch (e) {
			console.warn("Unable to load the saved inputs", e);
		}
		return new RunInputs();
	}

	/** The inputs. */
	get state(): Readonly<InputsState> {
		return this.#state;
	}

	/** The Service Names of the User Metadata Services. */
	get userMetadataUids(): string[] {
		return this.#state.userMetadata
			.map((m) => m.uid.trim())
			.filter((uid) => uid.length > 0);
	}

	/**
	 * Change a text input.
	 *
	 * @param field the input
	 * @param value the new value
	 */
	set(field: InputField, value: string): void {
		this.#update((s) => {
			s[field] = value;
		});
	}

	/**
	 * Set whether the unfiltered history is the same as the history.
	 *
	 * @param same `true` to use the History datum for both
	 */
	setUnfilteredSameAsHistory(same: boolean): void {
		this.#update((s) => {
			s.unfilteredSameAsHistory = same;
		});
	}

	/**
	 * Add a User Metadata Service.
	 *
	 * @returns the new service
	 */
	addUserMetadata(): UserMetadataInput {
		const uids = new Set(this.userMetadataUids);
		let n = this.#state.userMetadata.length + 1;
		while (uids.has(`Metadata ${n}`)) {
			n++;
		}
		const item: UserMetadataInput = {
			id: `meta-${Date.now().toString(36)}-${++this.#ids}`,
			uid: `Metadata ${n}`,
			metadata: "",
		};
		this.#update((s) => {
			s.userMetadata.push(item);
		});
		return item;
	}

	/**
	 * Change a User Metadata Service.
	 *
	 * @param id the service id
	 * @param changes the changes
	 */
	updateUserMetadata(
		id: string,
		changes: Partial<Omit<UserMetadataInput, "id">>,
	): void {
		this.#update((s) => {
			const item = s.userMetadata.find((m) => m.id === id);
			if (item) {
				Object.assign(item, changes);
			}
		});
	}

	/**
	 * Remove a User Metadata Service.
	 *
	 * @param id the service id
	 */
	removeUserMetadata(id: string): void {
		this.#update((s) => {
			s.userMetadata = s.userMetadata.filter((m) => m.id !== id);
		});
	}

	/**
	 * Get the parts of a simulation request that come from the inputs.
	 *
	 * @returns the request parts
	 * @throws InputError if an input is not valid
	 */
	toRequest(): RequestInputs {
		const s = this.#state;
		const parse = <T>(tab: InputTab, field: string, f: () => T): T => {
			try {
				return f();
			} catch (e) {
				throw new InputError(
					e instanceof Error ? e.message : String(e),
					tab,
					field,
				);
			}
		};
		const datum = parse("datum", "datum", () =>
			parseDatumList(s.datum, "Datum"),
		);
		if (datum.length === 0) {
			throw new InputError(
				"Enter the datum to run through the filters, as a JSON object or array of objects.",
				"datum",
				"datum",
			);
		}
		const filtered = parse("history", "history", () =>
			parseDatumList(s.history, "History datum"),
		);
		const unfiltered = s.unfilteredSameAsHistory
			? null
			: parse("history", "unfilteredHistory", () =>
					parseDatumList(
						s.unfilteredHistory,
						"Unfiltered History datum",
					),
				);
		const nodeMetadata = parse("node-metadata", "nodeMetadata", () =>
			parseMetadata(s.nodeMetadata, "Node metadata"),
		);
		const userMetadata: UserMetadataConfig[] = s.userMetadata.map(
			(m, i) => ({
				uid: m.uid.trim() || null,
				metadata: parse("user-metadata", m.id, () =>
					parseMetadata(
						m.metadata,
						`The metadata of ${m.uid.trim() || `User Metadata Service ${i + 1}`}`,
					),
				),
			}),
		);
		return {
			datum,
			history:
				filtered.length || unfiltered?.length
					? { filtered, unfiltered }
					: null,
			environment: {
				opModes: parseOpModes(s.opModes),
				localStateCsv: s.localState.trim() ? s.localState : null,
				nodeMetadata,
				userMetadata: userMetadata.length ? userMetadata : null,
			},
			entryPoint: s.entryPoint || null,
		};
	}

	#update(change: (state: InputsState) => void): void {
		const next = structuredClone(this.#state);
		change(next);
		this.#state = next;
		this.dispatchEvent(new Event("change"));
		window.clearTimeout(this.#saveTimer);
		this.#saveTimer = window.setTimeout(() => this.#save(), SAVE_DELAY);
	}

	#save(): void {
		try {
			localStorage.setItem(STORAGE_KEY, JSON.stringify(this.#state));
		} catch (e) {
			console.warn("Unable to save the inputs", e);
		}
	}
}
