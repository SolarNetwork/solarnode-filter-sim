/**
 * The inputs of a simulation run: the input datum, the datum histories, and
 * the starting state of the simulated node, including its metadata.
 */

import type {
	ControlConductorConfig,
	ControlKind,
	ControlPublishMode,
	DatumSourceConfig,
	NodeControlConfig,
	NodeControlPropertyType,
	NodeLocation,
	SimulationEnvironment,
	SimulationHistory,
	SimulationOptions,
	UserMetadataConfig,
} from "./model.ts";
import { jsonLanguage } from "@codemirror/lang-json";
import { canonicalTimeZone, parseLocalDate } from "./dates.ts";
import { formatJson } from "./jsonFormat.ts";
import { quoted } from "./view.ts";

/** The Service Name of SolarNode's node metadata service. */
export const NODE_METADATA_SERVICE_UID = "Node Metadata Service";

/** The service ID of the Spring Expression Language, SolarNode's only one. */
export const SPEL_SERVICE_ID =
	"net.solarnetwork.common.expr.spel.SpelExpressionService";

/**
 * The Expression Language choices, like a node's settings form; the first is
 * no language.
 */
export const EXPRESSION_LANGUAGES: readonly { id: string; label: string }[] = [
	{ id: "", label: "" },
	{ id: SPEL_SERVICE_ID, label: "Spring Expression Language" },
];

/** The default Instruction Execution Job seconds, like a node's. */
export const DEFAULT_INSTRUCTION_JOB_SECONDS = 10;

/** The input tabs. */
export type InputTab =
	"datum" | "sources" | "history" | "environment" | "metadata" | "controls";

/** The setting key of a datum source's schedule. */
export const SCHEDULE_KEY = "schedule";

/** The factory UID of the Mock Battery datum source. */
export const MOCK_BATTERY_UID = "net.solarnetwork.node.datum.battery.mock";

/** A simulated User Metadata Service, as entered. */
export interface UserMetadataInput {
	/** A unique id, for the UI. */
	id: string;
	/** The Service Name. */
	uid: string;
	/** The metadata JSON. */
	metadata: string;
}

/** The metadata of a datum stream, as entered. */
export interface DatumMetadataInput {
	/** A unique id, for the UI. */
	id: string;
	/** The source ID. */
	sourceId: string;
	/** The metadata JSON. */
	metadata: string;
}

/** A simulated control, as entered. */
export interface ControlInput {
	/** A unique id, for the UI. */
	id: string;
	/** The control ID. */
	controlId: string;
	/** The control type. */
	type: NodeControlPropertyType;
	/** The starting value, or `""` for none. */
	value: string;
	/** When the control publishes a datum. */
	publish: ControlPublishMode;
	/** `true` to persist the datum the control publishes. */
	persist: boolean;
	/**
	 * The datum property name of the value the control publishes, or `""` for
	 * `val` (with a number also as `v`).
	 */
	propertyName: string;
	/** The datum property type of the value, or `""` for `Status`. */
	propertyType: "" | "Instantaneous" | "Accumulating";
	/**
	 * The kind of control. An Operational Mode Switch's value follows its
	 * mode, so its type, value, and property are not used.
	 */
	kind: ControlKind;
	/** An Operational Mode Switch's operational mode. */
	mode: string;
}

/** A task of a simulated Control Conductor, as entered. */
export interface ConductorTaskInput {
	/** A unique id, for the UI. */
	id: string;
	/** The ID of the control to set. */
	controlId: string;
	/**
	 * The time offset from the orchestration date: milliseconds or an ISO 8601
	 * duration, possibly negative, possibly with placeholders.
	 */
	offset: string;
	/** The value to set, or an expression. */
	value: string;
	/** The Expression Language of the value, or `""` for none. */
	language: string;
}

/** A simulated Control Conductor, as entered. */
export interface ConductorInput {
	/** A unique id, for the UI. */
	id: string;
	/** The Service Name. */
	uid: string;
	/** The Predicate Expression, or `""` for none. */
	predicate: string;
	/** The Expression Language of the predicate, or `""` for none. */
	predicateLanguage: string;
	/** The tasks. */
	tasks: ConductorTaskInput[];
}

/** A simulated datum source, as entered. */
export interface DatumSourceInput {
	/** A unique id, for the UI, and the datum source's id in requests. */
	id: string;
	/** The datum source type. */
	factoryUid: string;
	/** The SolarNode instance ID, or `""` for none. */
	instanceKey: string;
	/**
	 * The settings of the datum source's job, like `schedule` and
	 * `jobService.datumDataSource.sourceId`.
	 */
	settings: Record<string, string>;
	/**
	 * When its controls publish a datum: `Never`, or `Change`, as datum
	 * sources post only changes.
	 */
	publish: ControlPublishMode;
	/** `true` to persist the datum its controls publish. */
	persist: boolean;
	/**
	 * The control IDs it provides, from its last settings form, with
	 * placeholders resolved.
	 */
	controlIds: string[];
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
	/**
	 * The time zone the results show dates in, and of the Start Date, or `""`
	 * for the simulator's time zone.
	 */
	timeZone: string;
	/**
	 * The date of the first datum without one, and the start of a run without
	 * input datum, as a `datetime-local` value in the time zone, or `""` for
	 * now.
	 */
	startDate: string;
	/**
	 * How long to keep running after the last datum: seconds, a duration like
	 * `15m`, or an ISO 8601 duration.
	 */
	runAfter: string;
	/** The most datum to process, or `""` for the default. */
	maxDatum: string;
	/** The Instruction Execution Job seconds, or `""` for the default. */
	instructionJobSeconds: string;
	/** Placeholders, as `name = value` lines. */
	placeholders: string;
	/** The datum stream metadata, one per source ID. */
	datumMetadata: DatumMetadataInput[];
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
	/** The simulated controls. */
	controls: ControlInput[];
	/** The simulated Control Conductors. */
	conductors: ConductorInput[];
	/** The simulated datum sources, which the simulation polls. */
	datumSources: DatumSourceInput[];
	/** The `id` of the filter to apply, or `""` for the Main chain. */
	entryPoint: string;
}

/** The text fields of the inputs. */
export type InputField = Exclude<
	keyof InputsState,
	| "version"
	| "unfilteredSameAsHistory"
	| "userMetadata"
	| "datumMetadata"
	| "controls"
	| "conductors"
	| "datumSources"
>;

/** The parts of a simulation request that come from the inputs. */
export interface RequestInputs {
	datum: unknown[];
	datumSources: DatumSourceConfig[] | null;
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
	 * @param field the input field, or the id of a User Metadata Service or
	 *     control
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
		timeZone: "",
		startDate: "",
		runAfter: "",
		maxDatum: "",
		instructionJobSeconds: "",
		placeholders: "",
		datumMetadata: [],
		nodeLatitude: "",
		nodeLongitude: "",
		nodeElevation: "",
		allowedCommands: "",
		nodeMetadata: "",
		userMetadata: [],
		controls: [],
		conductors: [],
		datumSources: [],
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

/** The seconds of each duration unit. */
const DURATION_UNITS: Record<string, number> = {
	s: 1,
	m: 60,
	h: 3600,
	d: 86_400,
	w: 604_800,
};

/**
 * Parse an ISO 8601 duration, like `PT24H` or `P1DT12H`, as seconds.
 *
 * @param text the duration
 * @returns the seconds, or `null` if the text is not an ISO 8601 duration
 */
function parseIsoDuration(text: string): number | null {
	const m =
		/^P(?:(\d+(?:\.\d+)?)W)?(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$/i.exec(
			text,
		);
	if (!m || text.toUpperCase().endsWith("T") || !m.slice(1).some((p) => p)) {
		return null;
	}
	const [w, d, h, min, sec] = m.slice(1).map((p) => Number(p ?? 0));
	return w * 604_800 + d * 86_400 + h * 3600 + min * 60 + sec;
}

/**
 * Parse how long to keep running after the last datum: a number of seconds,
 * like `90`; numbers with units, like `15m`, `24h`, `7d`, or `1h30m` (`s`,
 * `m`, `h`, `d`, and `w`); or an ISO 8601 duration, like `PT24H`.
 *
 * @param text the duration
 * @returns the seconds, or 0 if the text is blank
 * @throws Error if the text is not a duration
 */
export function parseRunAfter(text: string): number {
	const t = text.trim();
	if (!t) {
		return 0;
	}
	let seconds: number | null = null;
	if (/^\d+(\.\d+)?$/.test(t)) {
		seconds = Number(t);
	} else if (/^(\d+(\.\d+)?\s*[smhdw]\s*)+$/i.test(t)) {
		seconds = 0;
		for (const m of t.matchAll(/(\d+(?:\.\d+)?)\s*([smhdw])/gi)) {
			seconds += Number(m[1]) * DURATION_UNITS[m[2].toLowerCase()];
		}
	} else if (/^P/i.test(t)) {
		seconds = parseIsoDuration(t);
	}
	if (seconds === null || !Number.isFinite(seconds)) {
		throw new Error(
			"Enter a number of seconds, like 90, or a duration, like 15m, 24h, 7d, or PT24H.",
		);
	}
	return seconds;
}

/**
 * Parse the Maximum Datum.
 *
 * @param text the number
 * @returns the number, or `null` if the text is blank
 * @throws Error if the text is not a whole number of at least 1
 */
export function parseMaxDatum(text: string): number | null {
	const t = text.trim().replaceAll(/[,_ ]/g, "");
	if (!t) {
		return null;
	}
	const n = Number(t);
	if (!Number.isInteger(n) || n < 1) {
		throw new Error("Enter a whole number of datum, like 10000.");
	}
	return n;
}

/** The time zone of the simulator, the Time Zone's default. */
let serverTimeZone = "UTC";

/**
 * Set the time zone of the simulator, which is the Time Zone's default.
 *
 * @param zone the time zone
 */
export function setServerTimeZone(zone: string): void {
	serverTimeZone = canonicalTimeZone(zone) ?? "UTC";
}

/**
 * Get the time zone of the simulator, which is the Time Zone's default.
 *
 * @returns the time zone
 */
export function getServerTimeZone(): string {
	return serverTimeZone;
}

/**
 * Parse the Time Zone.
 *
 * @param text the time zone name
 * @returns the canonical time zone name, or `null` if the text is blank
 * @throws Error if the time zone is not known
 */
export function parseTimeZone(text: string): string | null {
	if (!text.trim()) {
		return null;
	}
	const zone = canonicalTimeZone(text);
	if (!zone) {
		throw new Error(
			"Enter a time zone name, like Pacific/Auckland, America/New_York, or UTC.",
		);
	}
	return zone;
}

/**
 * Parse the Start Date.
 *
 * @param text the date, as a `datetime-local` value
 * @param zone the time zone of the date
 * @returns the date as an ISO 8601 instant, or `null` if the text is blank
 * @throws Error if the text is not a date
 */
export function parseStartDate(text: string, zone: string): string | null {
	let ms: number | null;
	try {
		ms = parseLocalDate(text, zone, false);
	} catch {
		ms = NaN;
	}
	if (ms === null) {
		return null;
	}
	if (Number.isNaN(ms)) {
		throw new Error("Enter a date and time, like 2026-01-01 00:00:00.");
	}
	return new Date(ms).toISOString();
}

/**
 * Get the problem with a datum source's Schedule, like the simulator finds.
 * Spring's cron expressions, which a node uses, have 6 fields.
 *
 * @param schedule the schedule, or `undefined` for the default
 * @returns the problem, or `null` if there is none
 */
export function scheduleProblem(schedule: string | undefined): string | null {
	if (schedule === undefined) {
		return null;
	}
	const expr = schedule.trim();
	if (!expr) {
		return "Enter a schedule, like 60000 for every minute, or 0 * * * * ? for the start of every minute.";
	}
	if (/^[-+]?\d+$/.test(expr)) {
		const ms = Number(expr);
		if (ms < 0) {
			return "Enter a positive number of milliseconds, like 60000 for every minute.";
		}
		if (ms === 0) {
			return "A node runs a datum source with the Schedule 0 without a pause, so the simulator does not poll it. Enter a number of milliseconds, like 60000 for every minute.";
		}
		return null;
	}
	if (expr.split(/\s+/).length !== 6) {
		return "Enter a number of milliseconds, like 60000 for every minute, or a cron expression of 6 fields (seconds, minutes, hours, day of month, month, and day of week), like 0 * * * * ?.";
	}
	return null;
}

/**
 * Describe a datum source's schedule.
 *
 * @param schedule the schedule
 * @returns the description, like `every 60 s` or the cron expression
 */
export function scheduleText(schedule: string): string {
	const expr = schedule.trim();
	if (/^\+?\d+$/.test(expr)) {
		const ms = Number(expr);
		return ms % 1000 === 0 ? `every ${ms / 1000} s` : `every ${ms} ms`;
	}
	return expr;
}

/**
 * Get the input field of a datum source's schedule.
 *
 * @param id the datum source's id
 */
export function scheduleField(id: string): string {
	return `${id}.${SCHEDULE_KEY}`;
}

/**
 * Parse the Instruction Execution Job seconds.
 *
 * @param text the seconds
 * @returns the seconds, or `null` if the text is blank
 * @throws Error if the text is not a whole number from 1 to 59
 */
export function parseInstructionJobSeconds(text: string): number | null {
	if (!text.trim()) {
		return null;
	}
	const seconds = Number(text);
	if (!Number.isInteger(seconds) || seconds < 1 || seconds > 59) {
		throw new Error(
			"Enter a whole number of seconds from 1 to 59, like 10 for every 10 seconds.",
		);
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
 * Get the input field of a datum metadata's source ID, for an `InputError`.
 *
 * @param id the datum metadata's id
 * @returns the field
 */
export function datumMetadataSourceField(id: string): string {
	return `${id}.sourceId`;
}

/**
 * Get the problem with a datum metadata's source ID.
 *
 * @param items all the datum metadata
 * @param item the datum metadata to check
 * @returns the problem, or `null` if the source ID is valid
 */
export function datumMetadataSourceProblem(
	items: readonly DatumMetadataInput[],
	item: DatumMetadataInput,
): string | null {
	const sourceId = item.sourceId.trim();
	if (!sourceId) {
		return "Enter the source ID of the datum stream, like /meter/1.";
	}
	const first = items.find((m) => m.sourceId.trim() === sourceId);
	return first && first.id !== item.id
		? `Other metadata has the source ID ${sourceId}.`
		: null;
}

/**
 * Split datum metadata JSON, an object of source IDs to metadata, as it was
 * entered before each source ID had its own input. Each metadata keeps its
 * exact text, formatted.
 *
 * @param text the JSON
 * @returns the source IDs and metadata JSON, or the whole text with no
 *     source ID if it is not a JSON object
 */
export function splitDatumMetadata(
	text: string,
): { sourceId: string; metadata: string }[] {
	if (!text.trim()) {
		return [];
	}
	const tree = jsonLanguage.parser.parse(text);
	let errors = false;
	tree.iterate({
		enter: (n) => {
			errors ||= n.type.isError;
		},
	});
	const top = tree.topNode.firstChild;
	const result: { sourceId: string; metadata: string }[] = [];
	let ok = !errors && top?.name === "Object";
	for (let p = top?.firstChild; ok && p; p = p.nextSibling) {
		if (p.name !== "Property") {
			continue;
		}
		const name = p.getChild("PropertyName");
		const value = p.lastChild;
		try {
			const sourceId = JSON.parse(text.slice(name!.from, name!.to));
			const raw = text.slice(value!.from, value!.to);
			result.push({
				sourceId: String(sourceId),
				metadata: formatJson(raw),
			});
		} catch {
			ok = false;
		}
	}
	return ok ? result : [{ sourceId: "", metadata: text }];
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
 * Get the problem with a control's starting value, the way the simulator
 * reads it for the control's type.
 *
 * Boolean values are never a problem: like SolarNode, values other than
 * `true`, `yes`, `y`, `t`, or `1` are `false`.
 *
 * @param type the control type
 * @param value the value
 * @returns the problem, or `null` if the value is valid or empty
 */
export function controlValueProblem(
	type: NodeControlPropertyType,
	value: string,
): string | null {
	const v = value.trim();
	if (!v) {
		return null;
	}
	switch (type) {
		case "Integer": {
			const n = /^[+-]?\d+$/.test(v) ? Number(v) : NaN;
			return Number.isSafeInteger(n) && n >= -(2 ** 31) && n < 2 ** 31
				? null
				: "An Integer control's value must be a whole number, like 42.";
		}
		case "Float":
		case "Percent":
			return /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(v)
				? null
				: `A ${type} control's value must be a number, like 1.5.`;
		default:
			return null;
	}
}

/**
 * Get the input field of a control's value, for an `InputError`.
 *
 * @param id the control's id
 * @returns the field
 */
export function controlValueField(id: string): string {
	return `${id}.value`;
}

/**
 * Get the problem with a control's ID.
 *
 * @param controls all the controls
 * @param control the control to check
 * @returns the problem, or `null` if the ID is valid
 */
export function controlIdProblem(
	controls: readonly ControlInput[],
	control: ControlInput,
): string | null {
	const controlId = control.controlId.trim();
	if (!controlId) {
		return "Enter the control ID, like /power/limit.";
	}
	const first = controls.find((c) => c.controlId.trim() === controlId);
	return first && first.id !== control.id
		? `Another control has the ID ${controlId}.`
		: null;
}

/**
 * Get the input field of a Control Conductor task's setting, for an
 * `InputError`.
 *
 * @param taskId the task's id
 * @param setting the setting
 * @returns the field
 */
export function conductorTaskField(
	taskId: string,
	setting: "controlId" | "offset" | "value",
): string {
	return `${taskId}.${setting}`;
}

/**
 * Get the problem with a Control Conductor's Service Name. Like SolarNode,
 * Service Names are compared ignoring case.
 *
 * @param conductors all the conductors
 * @param conductor the conductor to check
 * @returns the problem, or `null` if the Service Name is valid
 */
export function conductorUidProblem(
	conductors: readonly ConductorInput[],
	conductor: ConductorInput,
): string | null {
	const uid = conductor.uid.trim().toLowerCase();
	if (!uid) {
		return "Enter the Service Name, which OrchestrateControls instructions name in their service parameter.";
	}
	const first = conductors.find((c) => c.uid.trim().toLowerCase() === uid);
	return first && first.id !== conductor.id
		? "Another Control Conductor has this Service Name."
		: null;
}

/**
 * Test if a task offset is valid, the way a Control Conductor reads it: a
 * whole number of milliseconds, like Java's `Long.parseLong()`, or an ISO 8601
 * duration, like Java's `Duration.parse()`. Either can be negative.
 *
 * @param offset the offset, without placeholders
 * @returns `true` if the offset is valid
 */
export function isTaskOffset(offset: string): boolean {
	if (/^[+-]?\d+$/.test(offset)) {
		const n = BigInt(offset);
		return n >= -(2n ** 63n) && n < 2n ** 63n;
	}
	const m =
		/^[+-]?P(?:([+-]?\d+)D)?(T(?:([+-]?\d+)H)?(?:([+-]?\d+)M)?(?:([+-]?\d+)(?:[.,]\d{0,9})?S)?)?$/i.exec(
			offset,
		);
	return (
		!!m &&
		(m[1] !== undefined ||
			m[3] !== undefined ||
			m[4] !== undefined ||
			m[5] !== undefined) &&
		(m[2] === undefined ||
			m[3] !== undefined ||
			m[4] !== undefined ||
			m[5] !== undefined)
	);
}

/**
 * Get the problems with a Control Conductor task.
 *
 * @param task the task
 * @returns the problems, by task setting
 */
export function conductorTaskProblems(
	task: ConductorTaskInput,
): Partial<Record<"controlId" | "offset" | "value", string>> {
	const result: Partial<Record<"controlId" | "offset" | "value", string>> =
		{};
	if (!task.controlId.trim()) {
		result.controlId = "Enter the ID of the control to set.";
	}
	const offset = task.offset.trim();
	if (!offset) {
		result.offset =
			"Enter the offset from the orchestration date, like 0, -PT5M, or {duration}.";
	} else if (!offset.includes("{") && !isTaskOffset(offset)) {
		result.offset =
			"Enter a number of milliseconds or an ISO 8601 duration, like -60000 or -PT1M.";
	}
	if (!task.value.trim()) {
		result.value = "Enter the value to set the control to.";
	}
	return result;
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
				const state = RunInputs.parseState(JSON.parse(json));
				if (state) {
					return new RunInputs(state);
				}
			}
		} catch (e) {
			console.warn("Unable to load the saved inputs", e);
		}
		return new RunInputs();
	}

	/**
	 * Get inputs from saved data, such as a simulation file.
	 *
	 * @param data the saved data
	 * @returns the inputs, with empty values for missing ones, or `undefined`
	 *     if the data is not valid inputs
	 */
	static parseState(data: unknown): InputsState | undefined {
		if (!isObject(data) || data.version !== 1) {
			return undefined;
		}
		const state = {
			...emptyState(),
			...(structuredClone(data) as Partial<InputsState>),
		};
		if (!Array.isArray(state.userMetadata)) {
			state.userMetadata = [];
		}
		if (!Array.isArray(state.controls)) {
			state.controls = [];
		}
		for (const c of state.controls) {
			// saved before controls had a datum property, or a kind
			c.propertyName ??= "";
			c.propertyType ??= "";
			c.kind ??= "Mock";
			c.mode ??= "";
		}
		if (!Array.isArray(state.conductors)) {
			state.conductors = [];
		}
		if (!Array.isArray(state.datumSources)) {
			state.datumSources = [];
		}
		for (const d of state.datumSources) {
			d.settings ??= {};
			d.instanceKey ??= "";
			d.publish ??= "Change";
			d.persist ??= false;
			if (!Array.isArray(d.controlIds)) {
				d.controlIds = [];
			}
		}
		for (const c of state.conductors) {
			if (!Array.isArray(c.tasks)) {
				c.tasks = [];
			}
		}
		if (typeof state.datumMetadata === "string") {
			// saved before each source ID had its own input
			state.datumMetadata = splitDatumMetadata(state.datumMetadata).map(
				(m, i) => ({ id: `dmeta-saved-${i + 1}`, ...m }),
			);
		} else if (!Array.isArray(state.datumMetadata)) {
			state.datumMetadata = [];
		}
		return state;
	}

	/** The inputs. */
	get state(): Readonly<InputsState> {
		return this.#state;
	}

	/** `true` if every input is empty. */
	get isEmpty(): boolean {
		return JSON.stringify(this.#state) === JSON.stringify(emptyState());
	}

	/**
	 * The time zone the results show dates in: the Time Zone, or else the
	 * simulator's.
	 */
	get timeZone(): string {
		try {
			return parseTimeZone(this.#state.timeZone) ?? serverTimeZone;
		} catch {
			return serverTimeZone;
		}
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

	/** The IDs of the controls, then those of the datum sources' controls. */
	get controlIds(): string[] {
		const ids = this.#state.controls
			.map((c) => c.controlId.trim())
			.filter((id) => id.length > 0);
		const all = new Set(ids);
		for (const d of this.#state.datumSources) {
			for (const id of d.controlIds) {
				if (!all.has(id)) {
					all.add(id);
					ids.push(id);
				}
			}
		}
		return ids;
	}

	/** The Service Names of the Control Conductors. */
	get conductorUids(): string[] {
		return this.#state.conductors
			.map((c) => c.uid.trim())
			.filter((uid) => uid.length > 0);
	}

	/** The Service Names of the User Metadata Services. */
	get userMetadataUids(): string[] {
		return this.#state.userMetadata
			.map((m) => m.uid.trim())
			.filter((uid) => uid.length > 0);
	}

	/**
	 * Replace all the inputs, such as with a simulation file's.
	 *
	 * @param state the new inputs
	 */
	replace(state: InputsState): void {
		this.#update((s) => {
			Object.assign(s, structuredClone(state));
		});
	}

	/** Clear every input. */
	clear(): void {
		this.replace(emptyState());
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
	 * Add datum metadata.
	 *
	 * @returns the new datum metadata
	 */
	addDatumMetadata(): DatumMetadataInput {
		const item: DatumMetadataInput = {
			id: `dmeta-${Date.now().toString(36)}-${++this.#ids}`,
			sourceId: "",
			metadata: "",
		};
		this.#update((s) => {
			s.datumMetadata.push(item);
		});
		return item;
	}

	/**
	 * Change datum metadata.
	 *
	 * @param id the datum metadata's id
	 * @param changes the changes
	 */
	updateDatumMetadata(
		id: string,
		changes: Partial<Omit<DatumMetadataInput, "id">>,
	): void {
		this.#update((s) => {
			const item = s.datumMetadata.find((m) => m.id === id);
			if (item) {
				Object.assign(item, changes);
			}
		});
	}

	/**
	 * Remove datum metadata.
	 *
	 * @param id the datum metadata's id
	 */
	removeDatumMetadata(id: string): void {
		this.#update((s) => {
			s.datumMetadata = s.datumMetadata.filter((m) => m.id !== id);
		});
	}

	/**
	 * Add a control.
	 *
	 * @param controlId the control ID, or `undefined` for a new one
	 * @returns the new control
	 */
	addControl(controlId?: string): ControlInput {
		let id = controlId?.trim();
		if (!id) {
			const ids = new Set(this.controlIds);
			let n = this.#state.controls.length + 1;
			while (ids.has(`/control/${n}`)) {
				n++;
			}
			id = `/control/${n}`;
		}
		const item: ControlInput = {
			id: `ctl-${Date.now().toString(36)}-${++this.#ids}`,
			controlId: id,
			type: "Boolean",
			value: "",
			publish: "Update",
			persist: false,
			propertyName: "",
			propertyType: "",
			kind: "Mock",
			mode: "",
		};
		this.#update((s) => {
			s.controls.push(item);
		});
		return item;
	}

	/**
	 * Change a control.
	 *
	 * @param id the control's id
	 * @param changes the changes
	 */
	updateControl(
		id: string,
		changes: Partial<Omit<ControlInput, "id">>,
	): void {
		this.#update((s) => {
			const item = s.controls.find((c) => c.id === id);
			if (item) {
				Object.assign(item, changes);
			}
		});
	}

	/**
	 * Remove a control.
	 *
	 * @param id the control's id
	 */
	removeControl(id: string): void {
		this.#update((s) => {
			s.controls = s.controls.filter((c) => c.id !== id);
		});
	}

	/**
	 * Add a Control Conductor, with one task.
	 *
	 * @param uid the Service Name, or `undefined` for a new one
	 * @returns the new conductor
	 */
	addConductor(uid?: string): ConductorInput {
		let name = uid?.trim();
		if (!name) {
			const uids = new Set(
				this.conductorUids.map((u) => u.toLowerCase()),
			);
			let n = this.#state.conductors.length + 1;
			while (uids.has(`conductor ${n}`)) {
				n++;
			}
			name = `Conductor ${n}`;
		}
		const item: ConductorInput = {
			id: `cc-${Date.now().toString(36)}-${++this.#ids}`,
			uid: name,
			predicate: "",
			predicateLanguage: SPEL_SERVICE_ID,
			tasks: [this.#newTask()],
		};
		this.#update((s) => {
			s.conductors.push(item);
		});
		return item;
	}

	#newTask(): ConductorTaskInput {
		return {
			id: `cc-task-${Date.now().toString(36)}-${++this.#ids}`,
			controlId: this.controlIds[0] ?? "",
			offset: "0",
			value: "",
			language: "",
		};
	}

	/**
	 * Change a Control Conductor.
	 *
	 * @param id the conductor's id
	 * @param changes the changes
	 */
	updateConductor(
		id: string,
		changes: Partial<Omit<ConductorInput, "id" | "tasks">>,
	): void {
		this.#update((s) => {
			const item = s.conductors.find((c) => c.id === id);
			if (item) {
				Object.assign(item, changes);
			}
		});
	}

	/**
	 * Remove a Control Conductor.
	 *
	 * @param id the conductor's id
	 */
	removeConductor(id: string): void {
		this.#update((s) => {
			s.conductors = s.conductors.filter((c) => c.id !== id);
		});
	}

	/**
	 * Add a task to a Control Conductor.
	 *
	 * @param id the conductor's id
	 * @returns the new task, or `undefined` if there is no such conductor
	 */
	addConductorTask(id: string): ConductorTaskInput | undefined {
		if (!this.#state.conductors.some((c) => c.id === id)) {
			return undefined;
		}
		const task = this.#newTask();
		this.#update((s) => {
			s.conductors.find((c) => c.id === id)?.tasks.push(task);
		});
		return task;
	}

	/**
	 * Change a Control Conductor task.
	 *
	 * @param id the conductor's id
	 * @param taskId the task's id
	 * @param changes the changes
	 */
	updateConductorTask(
		id: string,
		taskId: string,
		changes: Partial<Omit<ConductorTaskInput, "id">>,
	): void {
		this.#update((s) => {
			const task = s.conductors
				.find((c) => c.id === id)
				?.tasks.find((t) => t.id === taskId);
			if (task) {
				Object.assign(task, changes);
			}
		});
	}

	/**
	 * Remove a Control Conductor task.
	 *
	 * @param id the conductor's id
	 * @param taskId the task's id
	 */
	removeConductorTask(id: string, taskId: string): void {
		this.#update((s) => {
			const item = s.conductors.find((c) => c.id === id);
			if (item) {
				item.tasks = item.tasks.filter((t) => t.id !== taskId);
			}
		});
	}

	/**
	 * Use the controls and Control Conductors of an imported settings CSV file.
	 *
	 * Replacing the simulation's settings replaces the controls when the file
	 * has controls, and the conductors when it has conductors. Adding the
	 * settings replaces the controls with the same control ID, and the
	 * conductors with the same Service Name (ignoring case), and adds the
	 * others.
	 *
	 * @param controls the file's controls
	 * @param conductors the file's conductors
	 * @param replace `true` to replace, `false` to add
	 * @returns notes about the changes, or an empty list if there are none
	 */
	importEnvironment(
		controls: NodeControlConfig[] | null | undefined,
		conductors: ControlConductorConfig[] | null | undefined,
		replace: boolean,
	): string[] {
		const newControls = (controls ?? []).map((c): ControlInput => ({
			id: `ctl-${Date.now().toString(36)}-${++this.#ids}`,
			controlId: c.controlId ?? "",
			type: c.type ?? "Boolean",
			value: c.value ?? "",
			publish: c.publish ?? "Update",
			persist: c.persist ?? false,
			propertyName: c.propertyName ?? "",
			propertyType:
				c.propertyType === "Instantaneous" ||
				c.propertyType === "Accumulating"
					? c.propertyType
					: "",
			kind: c.kind ?? "Mock",
			mode: c.mode ?? "",
		}));
		const newConductors = (conductors ?? []).map((c) =>
			this.#conductorInput(c.settings ?? {}),
		);
		if (!newControls.length && !newConductors.length) {
			return [];
		}
		const notes: string[] = [];
		this.#update((s) => {
			if (newControls.length) {
				const count = newControls.length;
				if (replace) {
					s.controls = newControls;
					notes.push(
						`The Controls tab now has the file's ${count} control${count === 1 ? "" : "s"}.`,
					);
				} else {
					let replaced = 0;
					for (const c of newControls) {
						const i = s.controls.findIndex(
							(e) => e.controlId === c.controlId,
						);
						if (i < 0) {
							s.controls.push(c);
						} else {
							s.controls[i] = c;
							replaced++;
						}
					}
					notes.push(
						`Added the file's ${count} control${count === 1 ? "" : "s"} to the Controls tab` +
							(replaced
								? `, replacing ${replaced} with the same control ID.`
								: "."),
					);
				}
			}
			if (newConductors.length) {
				const count = newConductors.length;
				if (replace) {
					s.conductors = newConductors;
					notes.push(
						`The Controls tab now has the file's ${count} Control Conductor${count === 1 ? "" : "s"}.`,
					);
				} else {
					let replaced = 0;
					for (const c of newConductors) {
						const uid = c.uid.trim().toLowerCase();
						const i = s.conductors.findIndex(
							(e) => e.uid.trim().toLowerCase() === uid,
						);
						if (i < 0) {
							s.conductors.push(c);
						} else {
							s.conductors[i] = c;
							replaced++;
						}
					}
					notes.push(
						`Added the file's ${count} Control Conductor${count === 1 ? "" : "s"} to the Controls tab` +
							(replaced
								? `, replacing ${replaced} with the same Service Name.`
								: "."),
					);
				}
			}
		});
		return notes;
	}

	/**
	 * Make a Control Conductor input from its SolarNode settings, the reverse
	 * of `toRequest()`.
	 *
	 * @param settings the settings
	 * @returns the input
	 */
	#conductorInput(settings: Record<string, string>): ConductorInput {
		let count = Number.parseInt(settings["taskConfigsCount"] ?? "", 10);
		if (!Number.isFinite(count) || count < 0) {
			count = 0;
			for (const key of Object.keys(settings)) {
				const m = /^taskConfigs\[(\d+)\]\./.exec(key);
				if (m) {
					count = Math.max(count, Number(m[1]) + 1);
				}
			}
		}
		const tasks: ConductorTaskInput[] = [];
		for (let i = 0; i < count; i++) {
			const prefix = `taskConfigs[${i}].`;
			tasks.push({
				id: `cc-task-${Date.now().toString(36)}-${++this.#ids}`,
				controlId: settings[`${prefix}controlId`] ?? "",
				offset: settings[`${prefix}executionOffset`] ?? "",
				value: settings[`${prefix}value`] ?? "",
				language: settings[`${prefix}expressionServiceId`] ?? "",
			});
		}
		return {
			id: `cc-${Date.now().toString(36)}-${++this.#ids}`,
			uid: settings["uid"] ?? "",
			predicate: settings["predicate.expression"] ?? "",
			predicateLanguage: settings["predicate.expressionServiceId"] ?? "",
			tasks,
		};
	}

	/**
	 * Add a datum source.
	 *
	 * @param factoryUid the datum source type
	 * @returns the new datum source
	 */
	addDatumSource(factoryUid: string): DatumSourceInput {
		const item: DatumSourceInput = {
			id: `ds-${Date.now().toString(36)}-${++this.#ids}`,
			factoryUid,
			instanceKey: "",
			settings: {},
			publish: "Change",
			persist: false,
			controlIds: [],
		};
		this.#update((s) => {
			s.datumSources.push(item);
		});
		return item;
	}

	/**
	 * Change a datum source.
	 *
	 * @param id the datum source's id
	 * @param changes the changes
	 */
	updateDatumSource(
		id: string,
		changes: Partial<Omit<DatumSourceInput, "id" | "settings">>,
	): void {
		this.#update((s) => {
			const item = s.datumSources.find((d) => d.id === id);
			if (item) {
				Object.assign(item, changes);
			}
		});
	}

	/**
	 * Change the settings of a datum source.
	 *
	 * @param id the datum source's id
	 * @param changes the changes, with `null` to remove a setting
	 */
	editDatumSourceSettings(
		id: string,
		changes: Record<string, string | null>,
	): void {
		this.#update((s) => {
			const item = s.datumSources.find((d) => d.id === id);
			if (!item) {
				return;
			}
			for (const [key, value] of Object.entries(changes)) {
				if (value === null) {
					delete item.settings[key];
				} else {
					item.settings[key] = value;
				}
			}
		});
	}

	/**
	 * Remove a datum source.
	 *
	 * @param id the datum source's id
	 */
	removeDatumSource(id: string): void {
		this.#update((s) => {
			s.datumSources = s.datumSources.filter((d) => d.id !== id);
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
		if (datum.length === 0 && s.datumSources.length === 0) {
			throw new InputError(
				"Enter the datum to run through the filters, as a JSON object or array of objects, or add a datum source to poll.",
				"datum",
				"datum",
			);
		}
		const datumSources: DatumSourceConfig[] = s.datumSources.map((d) => {
			const problem = scheduleProblem(d.settings[SCHEDULE_KEY]);
			if (problem) {
				throw new InputError(problem, "sources", scheduleField(d.id));
			}
			return {
				id: d.id,
				factoryUid: d.factoryUid,
				instanceKey: d.instanceKey.trim() || null,
				settings: d.settings,
				publish: d.publish,
				persist: d.publish !== "Never" && d.persist ? true : null,
			};
		});
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
		const nodeMetadata = parse("metadata", "nodeMetadata", () =>
			parseMetadata(s.nodeMetadata, "Node metadata"),
		);
		const userMetadata: UserMetadataConfig[] = s.userMetadata.map(
			(m, i) => ({
				uid: m.uid.trim() || null,
				metadata: parse("metadata", m.id, () =>
					parseMetadata(
						m.metadata,
						`The metadata of ${m.uid.trim() ? quoted(m.uid.trim()) : `User Metadata Service ${i + 1}`}`,
					),
				),
			}),
		);
		const runAfter = parse("environment", "runAfter", () =>
			parseRunAfter(s.runAfter),
		);
		if (datum.length === 0 && runAfter <= 0) {
			throw new InputError(
				"Enter how long to keep running, which is the length of a run without input datum, to poll the datum sources.",
				"environment",
				"runAfter",
			);
		}
		const timeZone = parse("environment", "timeZone", () =>
			parseTimeZone(s.timeZone),
		);
		const startDate = parse("environment", "startDate", () =>
			parseStartDate(s.startDate, timeZone ?? serverTimeZone),
		);
		const placeholders = parse("environment", "placeholders", () =>
			parsePlaceholders(s.placeholders),
		);
		const datumMetadata: Record<string, unknown> = {};
		for (const m of s.datumMetadata) {
			const problem = datumMetadataSourceProblem(s.datumMetadata, m);
			if (problem) {
				throw new InputError(
					problem,
					"metadata",
					datumMetadataSourceField(m.id),
				);
			}
			const sourceId = m.sourceId.trim();
			const meta = parse("metadata", m.id, () =>
				parseMetadata(m.metadata, `The metadata of ${sourceId}`),
			);
			if (meta !== null) {
				datumMetadata[sourceId] = meta;
			}
		}
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
		const controls: NodeControlConfig[] = s.controls.map((c) => {
			const idProblem = controlIdProblem(s.controls, c);
			if (idProblem) {
				throw new InputError(idProblem, "controls", c.id);
			}
			if (c.kind === "OperationalModeSwitch") {
				return {
					controlId: c.controlId.trim(),
					kind: c.kind,
					mode: c.mode.trim() || null,
					publish: c.publish,
					persist: c.publish !== "Never" && c.persist ? true : null,
				};
			}
			const valueProblem = controlValueProblem(c.type, c.value);
			if (valueProblem) {
				throw new InputError(
					valueProblem,
					"controls",
					controlValueField(c.id),
				);
			}
			return {
				controlId: c.controlId.trim(),
				type: c.type,
				value: (c.type === "String" ? c.value : c.value.trim()) || null,
				publish: c.publish,
				persist: c.publish !== "Never" && c.persist ? true : null,
				propertyName: c.propertyName.trim() || null,
				propertyType: c.propertyType || null,
			};
		});
		const conductors: ControlConductorConfig[] = s.conductors.map((c) => {
			const uidProblem = conductorUidProblem(s.conductors, c);
			if (uidProblem) {
				throw new InputError(uidProblem, "controls", c.id);
			}
			const settings: Record<string, string> = { uid: c.uid.trim() };
			if (c.predicate.trim()) {
				settings["predicate.expression"] = c.predicate;
				if (c.predicateLanguage) {
					settings["predicate.expressionServiceId"] =
						c.predicateLanguage;
				}
			}
			settings.taskConfigsCount = String(c.tasks.length);
			c.tasks.forEach((t, i) => {
				const problems = conductorTaskProblems(t);
				for (const setting of [
					"controlId",
					"offset",
					"value",
				] as const) {
					const problem = problems[setting];
					if (problem) {
						throw new InputError(
							problem,
							"controls",
							conductorTaskField(t.id, setting),
						);
					}
				}
				const prefix = `taskConfigs[${i}].`;
				settings[`${prefix}controlId`] = t.controlId.trim();
				settings[`${prefix}executionOffset`] = t.offset.trim();
				settings[`${prefix}value`] = t.value;
				if (t.language) {
					settings[`${prefix}expressionServiceId`] = t.language;
				}
			});
			return { settings };
		});
		const instructionJobSeconds = parse(
			"environment",
			"instructionJobSeconds",
			() => parseInstructionJobSeconds(s.instructionJobSeconds),
		);
		const maxDatum = parse("environment", "maxDatum", () =>
			parseMaxDatum(s.maxDatum),
		);
		const allowedCommands = this.allowedCommands;
		return {
			datum,
			datumSources: datumSources.length ? datumSources : null,
			options: {
				startDate,
				runAfter: runAfter > 0 ? `PT${runAfter}S` : null,
				maxDatum,
				allowedCommands: allowedCommands.length
					? allowedCommands
					: null,
				instructionJobSeconds,
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
				datumMetadata: Object.keys(datumMetadata).length
					? datumMetadata
					: null,
				nodeLocation: Object.values(nodeLocation).some(
					(v) => v !== null,
				)
					? nodeLocation
					: null,
				controls: controls.length ? controls : null,
				conductors: conductors.length ? conductors : null,
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
