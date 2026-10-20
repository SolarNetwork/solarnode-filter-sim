/**
 * The inputs of a simulation run: the input datum, the datum histories, and
 * the starting state of the simulated node, including its metadata.
 */

import type {
	NodeLocation,
	SimulationEnvironment,
	SimulationHistory,
	SimulationOptions,
	UserMetadataConfig,
} from "./model.ts";

/** The Service Name of SolarNode's node metadata service. */
export const NODE_METADATA_SERVICE_UID = "Node Metadata Service";

/** The input tabs. */
export type InputTab =
	| "datum"
	| "history"
	| "environment"
	| "node-metadata"
	| "user-metadata"
	| "datum-metadata";

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
	/** The seconds to keep running after the last datum. */
	runAfter: string;
	/** Placeholders, as `name = value` lines. */
	placeholders: string;
	/** Datum metadata JSON: an object of source IDs to metadata. */
	datumMetadata: string;
	/** The node location latitude, in decimal degrees. */
	nodeLatitude: string;
	/** The node location longitude, in decimal degrees. */
	nodeLongitude: string;
	/** The node location elevation, in meters. */
	nodeElevation: string;
	/** The paths of the external commands filters may run, one per line. */
	allowedCommands: string;
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
	options: SimulationOptions;
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
		runAfter: "",
		placeholders: "",
		datumMetadata: "",
		nodeLatitude: "",
		nodeLongitude: "",
		nodeElevation: "",
		allowedCommands: "",
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
 * Parse the seconds to keep running after the last datum.
 *
 * @param text the seconds
 * @returns the seconds, or 0 if the text is blank
 * @throws Error if the text is not a number of seconds
 */
export function parseRunAfter(text: string): number {
	if (!text.trim()) {
		return 0;
	}
	const seconds = Number(text);
	if (!Number.isFinite(seconds) || seconds < 0) {
		throw new Error("Enter a number of seconds, like 300 for 5 minutes.");
	}
	return seconds;
}

/**
 * Parse placeholders: `name = value` lines, like a SolarNode placeholders
 * properties file, with `#` or `!` comment lines.
 *
 * @param text the placeholders
 * @returns the placeholders, by name
 * @throws Error if a line has no name and value
 */
export function parsePlaceholders(text: string): Record<string, string> {
	const result: Record<string, string> = {};
	text.split(/\r?\n/).forEach((line, i) => {
		const l = line.trim();
		if (!l || l.startsWith("#") || l.startsWith("!")) {
			return;
		}
		const match = /^([^=:\s]+)\s*[=:]\s*(.*)$/.exec(l);
		if (!match) {
			throw new Error(
				`Placeholder line ${i + 1} needs a name and value, like "factor = 2".`,
			);
		}
		result[match[1]] = match[2];
	});
	return result;
}

/**
 * Parse datum metadata JSON: an object of source IDs to metadata objects.
 *
 * @param text the JSON
 * @returns the metadata, or `null` if the text is blank
 * @throws Error if the JSON is not valid
 */
export function parseDatumMetadata(
	text: string,
): Record<string, unknown> | null {
	const value = parseMetadata(text, "Datum metadata") as Record<
		string,
		unknown
	> | null;
	for (const [sourceId, meta] of Object.entries(value ?? {})) {
		if (!isObject(meta)) {
			throw new Error(
				`The datum metadata of ${sourceId} must be a JSON object, like {"pm": {…}}.`,
			);
		}
	}
	return value;
}

/**
 * Parse a number of a node location.
 *
 * @param text the number
 * @param label the name of the number, for messages
 * @param min the smallest value allowed
 * @param max the largest value allowed
 * @returns the number, or `null` if the text is blank
 * @throws Error if the text is not a number in range
 */
export function parseLocationNumber(
	text: string,
	label: string,
	min = -Infinity,
	max = Infinity,
): number | null {
	if (!text.trim()) {
		return null;
	}
	const n = Number(text);
	if (!Number.isFinite(n) || n < min || n > max) {
		throw new Error(
			Number.isFinite(min)
				? `${label} must be a number from ${min} to ${max}.`
				: `${label} must be a number.`,
		);
	}
	return n;
}

/**
 * Parse the Allowed Commands: one command path per line.
 *
 * @param text the commands
 * @returns the command paths
 */
export function parseAllowedCommands(text: string): string[] {
	return text
		.split(/\r?\n/)
		.map((c) => c.trim())
		.filter((c) => c.length > 0);
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

	/** The paths of the external commands filters may run. */
	get allowedCommands(): string[] {
		return parseAllowedCommands(this.#state.allowedCommands);
	}

	/**
	 * Allow an external command to run.
	 *
	 * @param command the command path
	 */
	allowCommand(command: string): void {
		const cmd = command.trim();
		if (!cmd || this.allowedCommands.includes(cmd)) {
			return;
		}
		const text = this.#state.allowedCommands.trimEnd();
		this.set("allowedCommands", text ? `${text}\n${cmd}` : cmd);
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
		const runAfter = parse("environment", "runAfter", () =>
			parseRunAfter(s.runAfter),
		);
		const placeholders = parse("environment", "placeholders", () =>
			parsePlaceholders(s.placeholders),
		);
		const datumMetadata = parse("datum-metadata", "datumMetadata", () =>
			parseDatumMetadata(s.datumMetadata),
		);
		const nodeLocation: NodeLocation = {
			latitude: parse("environment", "nodeLatitude", () =>
				parseLocationNumber(s.nodeLatitude, "Latitude", -90, 90),
			),
			longitude: parse("environment", "nodeLongitude", () =>
				parseLocationNumber(s.nodeLongitude, "Longitude", -180, 180),
			),
			elevation: parse("environment", "nodeElevation", () =>
				parseLocationNumber(s.nodeElevation, "Elevation"),
			),
		};
		const allowedCommands = this.allowedCommands;
		return {
			datum,
			options: {
				runAfter: runAfter > 0 ? `PT${runAfter}S` : null,
				allowedCommands: allowedCommands.length
					? allowedCommands
					: null,
			},
			history:
				filtered.length || unfiltered?.length
					? { filtered, unfiltered }
					: null,
			environment: {
				opModes: parseOpModes(s.opModes),
				localStateCsv: s.localState.trim() ? s.localState : null,
				nodeMetadata,
				userMetadata: userMetadata.length ? userMetadata : null,
				placeholders: Object.keys(placeholders).length
					? placeholders
					: null,
				datumMetadata,
				nodeLocation: Object.values(nodeLocation).some(
					(v) => v !== null,
				)
					? nodeLocation
					: null,
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
