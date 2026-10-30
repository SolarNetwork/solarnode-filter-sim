/**
 * Rendering helpers shared by the execution flow, debug views, and dialogs.
 */

import type {
	ControlInfo,
	DatumOrigin,
	FilterBundleInfo,
	InstructionHandlerInfo,
	InstructionInfo,
	InstructionRun,
	MessageLevel,
	OpModeChange,
	SamplesInfo,
	SimulationMessage,
} from "./model.ts";
import { formatLocal } from "./dates.ts";

/**
 * Quote a name in a sentence, such as a Service Name, which can have spaces
 * and words like "and" that would otherwise read as part of the sentence.
 *
 * @param name the name
 * @returns the quoted name
 */
export function quoted(name: string): string {
	return `"${name}"`;
}

/**
 * How the results name a datum source, or an Operational Mode Invoker, of the
 * run they show.
 */
export interface DatumSourceName {
	/** The Service Name, if it has one. */
	name?: string;
	/** The type's name. */
	type: string;
	/**
	 * The position in the run's datum sources, or its invokers, counting from
	 * 1.
	 */
	position: number;
	/** `true` for an Operational Mode Invoker. */
	invoker?: boolean;
}

/** What the canvas shows of a datum source. */
export interface DatumSourceSummary {
	/** The datum source id. */
	id: string;
	factoryUid: string;
	/** The type's name. */
	type: string;
	/** The Service Name, if it has one. */
	name?: string;
	/** The Source ID, if it has one. */
	sourceId?: string;
	/** The schedule, as text, like `every 30s`. */
	schedule?: string;
	/** A problem with its settings, if it has one. */
	problem?: string;
	/** `true` for the static datum, the Datum tab's input datum. */
	static?: boolean;
	/** For the static datum, the number of datum. */
	count?: number;
}

/**
 * The datum sources of the run the results show, by id, as they were when it
 * ran, so later edits do not rename them.
 */
let datumSourceNames = new Map<string, DatumSourceName>();

/**
 * Set the datum sources of the run the results show.
 *
 * @param names the datum source names, by id
 */
export function setDatumSourceNames(
	names: ReadonlyMap<string, DatumSourceName>,
): void {
	datumSourceNames = new Map(names);
}

/**
 * Name a datum source of the run the results show, like the server's
 * messages: `"Meter 1" (Mock Energy Meter)`, or `datum source 2 (Mock Energy
 * Meter)` without a Service Name. An Operational Mode Invoker is `"Boost"
 * (Operational Mode Invoker)`, or `Operational Mode Invoker 1`.
 *
 * @param id the datum source or invoker id
 * @returns the name, starting with a lowercase letter, unless it is an
 *     invoker's type
 */
export function datumSourceLabel(id: string): string {
	const n = datumSourceNames.get(id);
	if (!n) {
		return `datum source ${quoted(id)}`;
	}
	if (n.name) {
		return `${quoted(n.name)} (${n.type})`;
	}
	return n.invoker
		? `${n.type} ${n.position}`
		: `datum source ${n.position} (${n.type})`;
}

/**
 * Name a datum source of the run the results show in short: its Service
 * Name, else `datum source 2`.
 *
 * @param id the datum source id
 * @returns the name, starting with a lowercase letter
 */
export function datumSourceShortLabel(id: string): string {
	const n = datumSourceNames.get(id);
	return n?.name
		? quoted(n.name)
		: `${n?.invoker ? "invoker" : "datum source"} ${n?.position ?? quoted(id)}`;
}

/**
 * Describe where a polled datum came from, like `Polled from "Meter 1" (Mock
 * Energy Meter)`, or `Polled from "Meter 1" (Mock Energy Meter) by "Boost"
 * (Operational Mode Invoker)`.
 *
 * @param origin the datum's origin, of type `Polled`
 * @returns the description, starting with a capital letter
 */
export function polledOriginText(origin: DatumOrigin): string {
	const by = origin.invokerId
		? ` by ${datumSourceLabel(origin.invokerId)}`
		: "";
	return `Polled from ${datumSourceLabel(origin.datumSourceId ?? "")}${by}`;
}

/**
 * Describe where a polled datum came from in short, like `from "Meter 1"`, or
 * `from "Meter 1" by "Boost"`.
 *
 * @param origin the datum's origin, of type `Polled`
 * @returns the description
 */
export function polledOriginShortText(origin: DatumOrigin): string {
	const by = origin.invokerId
		? ` by ${datumSourceShortLabel(origin.invokerId)}`
		: "";
	return `from ${datumSourceShortLabel(origin.datumSourceId ?? "")}${by}`;
}

/**
 * Get a bundle's name and version, for messages.
 *
 * @param bundle the bundle
 * @returns the label, such as `com.example.filter 1.2.0`
 */
export function bundleLabel(bundle: FilterBundleInfo): string {
	return bundle.version ? `${bundle.name} ${bundle.version}` : bundle.name;
}

/** The badge class of each message level. */
export const LEVEL_CLASSES: Record<MessageLevel, string> = {
	Info: "text-bg-secondary",
	Warning: "text-bg-warning",
	Error: "text-bg-danger",
};

/** How long, in milliseconds, a copy button shows that it copied. */
const COPIED_TIME = 1500;

/** The sample property types, in display order. */
export const SAMPLE_TYPES = ["i", "a", "s"] as const;

export type SampleType = (typeof SAMPLE_TYPES)[number];

export const TYPE_NAMES: Record<SampleType | "t", string> = {
	i: "Instantaneous",
	a: "Accumulating",
	s: "Status",
	t: "Tags",
};

/** A change to one datum property. */
export interface PropertyChange {
	type: SampleType | "t";
	key: string;
	kind: "added" | "removed" | "changed";
	from?: unknown;
	to?: unknown;
}

export function element<K extends keyof HTMLElementTagNameMap>(
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

/** Format a property value for display. */
export function formatValue(value: unknown): string {
	if (value === null || value === undefined) {
		return "null";
	}
	if (typeof value === "object") {
		return JSON.stringify(value);
	}
	return String(value);
}

/** The time zone the results show dates in. */
let displayZone = "UTC";

/**
 * Set the time zone the results show dates in.
 *
 * @param zone the time zone
 */
export function setDisplayZone(zone: string): void {
	displayZone = zone;
}

/**
 * Get the time zone the results show dates in.
 *
 * @returns the time zone
 */
export function getDisplayZone(): string {
	return displayZone;
}

/**
 * Format a timestamp as a local date and time in the time zone the results
 * show, like `2025-10-01 12:00:00`, keeping any fraction of a second.
 *
 * @param ts the timestamp, as an ISO 8601 instant
 * @returns the date and time
 */
export function formatTimestamp(ts: string | undefined): string {
	if (!ts) {
		return "";
	}
	const ms = Date.parse(ts);
	if (Number.isNaN(ms)) {
		return ts.replace("T", " ");
	}
	return formatLocal(ms, displayZone, /\.(\d+)/.exec(ts)?.[1]);
}

export function formatDuration(micros: number | undefined): string {
	if (micros === undefined) {
		return "";
	}
	return micros < 1000
		? `${micros} µs`
		: `${(micros / 1000).toFixed(micros < 10_000 ? 2 : 1)} ms`;
}

export function sameValue(a: unknown, b: unknown): boolean {
	return a === b || JSON.stringify(a) === JSON.stringify(b);
}

/**
 * Get the property changes between two sets of samples.
 *
 * @param from the samples before
 * @param to the samples after
 * @returns the changes
 */
export function diffSamples(
	from: SamplesInfo,
	to: SamplesInfo,
): PropertyChange[] {
	const changes: PropertyChange[] = [];
	for (const type of SAMPLE_TYPES) {
		const a = from[type] ?? {};
		const b = to[type] ?? {};
		for (const [key, value] of Object.entries(b)) {
			if (!(key in a)) {
				changes.push({ type, key, kind: "added", to: value });
			} else if (!sameValue(a[key], value)) {
				changes.push({
					type,
					key,
					kind: "changed",
					from: a[key],
					to: value,
				});
			}
		}
		for (const [key, value] of Object.entries(a)) {
			if (!(key in b)) {
				changes.push({ type, key, kind: "removed", from: value });
			}
		}
	}
	const tagsFrom = new Set(from.t ?? []);
	const tagsTo = new Set(to.t ?? []);
	for (const tag of tagsTo) {
		if (!tagsFrom.has(tag)) {
			changes.push({ type: "t", key: tag, kind: "added" });
		}
	}
	for (const tag of tagsFrom) {
		if (!tagsTo.has(tag)) {
			changes.push({ type: "t", key: tag, kind: "removed" });
		}
	}
	return changes;
}

/**
 * Render a line of a datum's properties, grouped by type.
 *
 * @param label the line label
 * @param samples the properties
 * @returns the line
 */
export function samplesLine(label: string, samples: SamplesInfo): HTMLElement {
	const line = element("div", "small sim-samples d-flex flex-wrap gap-1");
	line.append(element("span", "fw-semibold me-1", label));
	let empty = true;
	for (const type of SAMPLE_TYPES) {
		for (const [key, value] of Object.entries(samples[type] ?? {})) {
			const chip = element("span", "sim-chip");
			chip.title = `${TYPE_NAMES[type]} property ${key}`;
			chip.append(
				element("span", "sim-chip-type", type),
				`${key} = ${formatValue(value)}`,
			);
			line.append(chip);
			empty = false;
		}
	}
	for (const tag of samples.t ?? []) {
		const chip = element("span", "sim-chip");
		chip.title = `Tag ${tag}`;
		chip.append(element("span", "sim-chip-type", "t"), tag);
		line.append(chip);
		empty = false;
	}
	if (empty) {
		line.append(element("span", "text-body-secondary", "no properties"));
	}
	return line;
}

/**
 * Describe the External Settings files a filter comes from.
 *
 * @param files the files
 * @returns for example "the External Settings file a.csv", or "the External
 *     Settings files a.csv, b.csv, and c.csv"
 */
export function externalFilesText(files: readonly string[]): string {
	if (files.length < 2) {
		return `the External Settings file ${files[0] ?? ""}`;
	}
	const list =
		files.length === 2
			? `${files[0]} and ${files[1]}`
			: `${files.slice(0, -1).join(", ")}, and ${files[files.length - 1]}`;
	return `the External Settings files ${list}`;
}

/**
 * Format a command and its arguments like a shell command line, quoting
 * arguments with spaces or quotes.
 *
 * @param command the command and its arguments
 * @returns the command line
 */
export function commandLine(command: readonly string[]): string {
	return command
		.map((arg) =>
			arg === "" || /[\s'"\\$`]/.test(arg)
				? `'${arg.replace(/'/g, "'\\''")}'`
				: arg,
		)
		.join(" ");
}

/**
 * Get the file name of a command path.
 *
 * @param command the command path
 * @returns the file name
 */
export function commandName(command: string): string {
	const i = command.lastIndexOf("/");
	return i >= 0 && i < command.length - 1
		? command.substring(i + 1)
		: command;
}

/**
 * Copy text to the clipboard.
 *
 * @param text the text
 * @param fallback an element showing the text, which is selected and copied
 *     if the clipboard API is unavailable (outside a secure context)
 * @returns `true` if the text was copied
 */
export async function copyText(
	text: string,
	fallback: HTMLElement,
): Promise<boolean> {
	try {
		if (navigator.clipboard) {
			await navigator.clipboard.writeText(text);
			return true;
		}
	} catch (e) {
		console.warn("Unable to copy to the clipboard", e);
	}
	const selection = window.getSelection();
	if (!selection) {
		return false;
	}
	selection.selectAllChildren(fallback);
	return document.execCommand("copy");
}

/**
 * Render a simulation message as a list group item: its level, its text,
 * which can be selected, and a button that copies the text.
 *
 * @param m the message
 * @param link what clicking the item does, if anything: a click that ends a
 *     text selection does nothing
 * @returns the item
 */
export function messageItem(
	m: SimulationMessage,
	link?: { title: string; show: () => void },
): HTMLElement {
	const item = element(
		"div",
		"list-group-item d-flex gap-2 align-items-start sim-message" +
			(link ? " list-group-item-action" : ""),
	);
	const text = element("span", "flex-grow-1 text-break", m.message);
	const copy = element(
		"button",
		"btn btn-sm btn-link text-body-secondary p-0 sim-tool sim-copy",
	);
	copy.type = "button";
	copy.title = "Copy the message";
	copy.setAttribute("aria-label", copy.title);
	const icon = element("i", "bi bi-copy");
	icon.setAttribute("aria-hidden", "true");
	copy.append(icon);
	copy.addEventListener("click", async (e) => {
		e.stopPropagation();
		if (await copyText(m.message, text)) {
			icon.className = "bi bi-check-lg";
			copy.title = "Copied";
			window.setTimeout(() => {
				icon.className = "bi bi-copy";
				copy.title = "Copy the message";
			}, COPIED_TIME);
		}
	});
	if (link) {
		item.title = link.title;
		item.tabIndex = 0;
		item.addEventListener("click", () => {
			const selection = window.getSelection();
			if (
				selection &&
				!selection.isCollapsed &&
				item.contains(selection.anchorNode)
			) {
				return;
			}
			link.show();
		});
		item.addEventListener("keydown", (e) => {
			if (e.target === item && (e.key === "Enter" || e.key === " ")) {
				e.preventDefault();
				link.show();
			}
		});
	}
	item.append(
		element(
			"span",
			`badge ${LEVEL_CLASSES[m.level] ?? "text-bg-secondary"}`,
			m.level,
		),
		text,
		copy,
	);
	return item;
}

/**
 * A table of the simulated controls, with their starting values when they
 * changed.
 */
export function controlsTable(
	controls: ControlInfo[],
	changed?: Set<string>,
): HTMLElement {
	const section = element("div", "mt-2");
	section.append(element("div", "fw-semibold", "Controls"));
	const table = element("table", "table table-sm mb-0 sim-samples-table");
	const head = element("thead");
	const headRow = element("tr");
	headRow.append(
		element("th", undefined, "Control ID"),
		element("th", undefined, "Type"),
		element("th", undefined, "Value"),
		element("th", undefined, "Last set"),
		element("th", "text-end", "Sets"),
	);
	head.append(headRow);
	const rows = element("tbody");
	for (const c of controls) {
		const row = element("tr");
		const value = element("td", "font-monospace text-break", c.value ?? "");
		if (c.value === undefined) {
			value.append(element("span", "text-body-secondary", "none"));
		}
		if (c.initialValue !== c.value) {
			value.append(
				element(
					"span",
					"text-body-secondary",
					` (from ${c.initialValue ?? "none"})`,
				),
			);
		}
		const type = element("td", "text-body-secondary");
		if (c.kind === "OperationalModeSwitch") {
			type.append("switch of ");
			type.append(
				c.mode
					? element("span", "font-monospace", c.mode)
					: element("span", "fst-italic", "no mode"),
			);
			type.title = `An Operational Mode Switch: a ${c.type} control of whether its operational mode is active`;
		} else {
			type.append(c.type);
		}
		if (c.component) {
			type.append(
				element(
					"div",
					"small",
					`of ${datumSourceShortLabel(c.component)}`,
				),
			);
			type.title = `A ${c.type} control of ${datumSourceLabel(c.component)}`;
		}
		row.append(
			element("td", "font-monospace", c.controlId),
			type,
			value,
			element(
				"td",
				"text-nowrap",
				c.modified ? formatTimestamp(c.modified) : "",
			),
			element("td", "text-end", String(c.updates)),
		);
		if (changed?.has(c.controlId)) {
			row.classList.add("table-active");
		}
		rows.append(row);
	}
	table.append(head, rows);
	section.append(table);
	return section;
}

/**
 * Name an instruction: a Control Conductor task by its conductor and task,
 * like `Control Conductor "HVAC DR" task 1`, else its topic.
 *
 * @param instruction the instruction
 * @returns the name
 */
export function instructionLabel(instruction: InstructionInfo): string {
	return instruction.conductor
		? `Control Conductor ${quoted(instruction.conductor)} task ${instruction.task ?? "?"}`
		: instruction.topic;
}

/**
 * Name the component that handled an instruction, like `the Control Conductor
 * "HVAC DR"`.
 *
 * @param handler the handler
 * @returns the name, starting with a lowercase letter
 */
export function handlerLabel(handler: InstructionHandlerInfo): string {
	switch (handler.type) {
		case "Reactor":
			return "the reactor";
		case "OperationalModes":
			return "the operational modes service";
		case "Metadata":
			return `the metadata service ${quoted(handler.name ?? "")}`;
		case "Control":
			return `control ${handler.name ?? ""}`;
		case "OperationalModeSwitch":
			return `the Operational Mode Switch ${handler.name ?? ""}`;
		case "DatumSource":
			return datumSourceLabel(handler.name ?? "");
		case "Conductor":
			return `the Control Conductor ${quoted(handler.name ?? "")}`;
	}
}

/**
 * Describe a change to the active operational modes, like `+boost −eco`.
 *
 * @param change the change
 * @returns the description
 */
export function opModesText(change: OpModeChange): string {
	return [
		...(change.enabled ?? []).map((m) => `+${m}`),
		...(change.disabled ?? []).map((m) => `−${m}`),
	].join(" ");
}

/**
 * Describe where a datum published during a scheduled task of no filter came
 * from: an Operational Mode Switch whose mode the operational modes service's
 * auto-expire task found expired.
 *
 * @param controlId the control ID
 * @returns the description, starting with a capital letter
 */
export function expiredModeOriginText(controlId: string): string {
	return `Published by control ${controlId}, when the operational modes service found its mode expired`;
}

/**
 * Describe where a datum published during an instruction run came from, like
 * "Published by control /hvac/1/mode, set by Control Conductor "HVAC DR" task
 * 1 at 2025-10-01 00:09:00Z".
 *
 * @param origin the datum's origin, of type `Instruction`
 * @param runs the result's instruction runs
 * @returns the description, starting with a capital letter
 */
export function instructionOriginText(
	origin: DatumOrigin,
	runs: readonly InstructionRun[] | undefined,
): string {
	const run = runs?.find((r) => r.instruction.number === origin.instruction);
	const by = run
		? `${instructionLabel(run.instruction)} at ${formatTimestamp(run.date)}`
		: `instruction #${origin.instruction ?? "?"}`;
	return origin.controlId
		? `Published by control ${origin.controlId}, set by ${by}`
		: `Generated while running ${by}`;
}

/** The text class of an instruction state. */
function stateClass(state: string | undefined): string {
	return state === "Completed"
		? "text-body-secondary"
		: state === "Received" || state === "Executing"
			? "text-info-emphasis"
			: "text-danger-emphasis";
}

/**
 * A list of instructions, each with its status, message, and parameters, and
 * the instructions it issued indented below it.
 *
 * @param instructions the instructions
 * @returns the list
 */
export function instructionsList(
	instructions: readonly InstructionInfo[],
): HTMLElement {
	const section = element("div", "mt-2 sim-instructions");
	const numbers = new Set(instructions.map((i) => i.number));
	const item = (instr: InstructionInfo, child: boolean): HTMLElement => {
		const el = element("div", "sim-instruction");
		const heading = element(
			"div",
			"d-flex flex-wrap align-items-baseline gap-2",
		);
		const name = element(
			"span",
			"fw-semibold",
			`#${instr.number} ${instr.topic}`,
		);
		if (instr.id !== undefined) {
			name.title = `SolarNode instruction ID ${instr.id}`;
		}
		heading.append(name);
		if (instr.conductor) {
			heading.append(element("span", undefined, instructionLabel(instr)));
		}
		heading.append(
			element(
				"span",
				stateClass(instr.state),
				instr.state ?? "not handled: no control or service handles it",
			),
		);
		if (instr.handler) {
			heading.append(
				element(
					"span",
					"text-body-secondary",
					`handled by ${handlerLabel(instr.handler)}`,
				),
			);
		}
		if (instr.executeAt) {
			heading.append(
				element(
					"span",
					"text-body-secondary",
					`due ${formatTimestamp(instr.executeAt)}`,
				),
			);
		}
		el.append(heading);
		if (instr.message || instr.code) {
			el.append(
				element(
					"div",
					instr.state === "Declined"
						? "text-danger-emphasis"
						: "text-body-secondary",
					[instr.message, instr.code ? `(${instr.code})` : ""]
						.filter((t) => t)
						.join(" "),
				),
			);
		}
		const params = Object.entries(instr.parameters ?? {});
		if (params.length) {
			const table = element(
				"table",
				"table table-sm mb-1 sim-samples-table",
			);
			const rows = element("tbody");
			for (const [k, v] of params) {
				const row = element("tr");
				row.append(
					element("td", "font-monospace", k),
					element("td", "font-monospace text-break", v),
				);
				rows.append(row);
			}
			table.append(rows);
			if (child) {
				// a task's parameters are mostly its parent's, so they start hidden
				const more = element("details", "sim-instruction-params");
				more.append(
					element(
						"summary",
						"text-body-secondary",
						`${params.length} parameter${params.length === 1 ? "" : "s"}`,
					),
					table,
				);
				el.append(more);
			} else {
				el.append(table);
			}
		}
		const children = instructions.filter(
			(i) => i.parent === instr.number && i !== instr,
		);
		if (children.length) {
			const list = element("div", "sim-instruction-children");
			list.append(...children.map((c) => item(c, true)));
			el.append(list);
		}
		return el;
	};
	for (const instr of instructions) {
		if (instr.parent === undefined || !numbers.has(instr.parent)) {
			section.append(item(instr, false));
		}
	}
	return section;
}

/**
 * A table of the instructions waiting for the Instruction Execution Job.
 *
 * @param instructions the instructions
 * @param marked the numbers of the instructions to mark, like those a step
 *     stored
 * @returns the table
 */
export function waitingInstructionsTable(
	instructions: readonly InstructionInfo[],
	marked?: ReadonlySet<number>,
): HTMLElement {
	const table = element("table", "table table-sm mb-0 sim-samples-table");
	const head = element("thead");
	const headRow = element("tr");
	headRow.append(
		element("th", undefined, "#"),
		element("th", undefined, "Instruction"),
		element("th", undefined, "Due"),
	);
	head.append(headRow);
	const rows = element("tbody");
	for (const i of instructions) {
		const row = element("tr");
		row.title = Object.entries(i.parameters ?? {})
			.map(([k, v]) => `${k} = ${v}`)
			.join("\n");
		row.append(
			element("td", "text-body-secondary", String(i.number)),
			element(
				"td",
				undefined,
				i.conductor ? instructionLabel(i) : i.topic,
			),
			element(
				"td",
				"text-nowrap",
				i.executeAt ? formatTimestamp(i.executeAt) : "now",
			),
		);
		if (marked?.has(i.number)) {
			row.classList.add("table-active");
		}
		rows.append(row);
	}
	table.append(head, rows);
	return table;
}
