/**
 * Debug mode support: stepping through a recorded simulation one filter at a
 * time, and rebuilding the simulated node's state at each step.
 *
 * A debug result has a snapshot of the node state when each datum starts
 * processing, and each filter step records the changes it made, so the state
 * after any step is its datum's snapshot plus the changes of the steps before.
 */

import {
	CHAIN_FACTORY_UID,
	type ControlInfo,
	type DatumInfo,
	type DatumTrace,
	type FilterStep,
	type InstructionInfo,
	type MetricInfo,
	type SamplesInfo,
	type SimulationResult,
} from "./model.ts";
import {
	controlsTable,
	element,
	formatTimestamp,
	formatValue,
	quoted,
	SAMPLE_TYPES,
	samplesLine,
	sameValue,
	waitingInstructionsTable,
} from "./view.ts";

/** A debug cursor position: a filter step of a datum. */
export interface DebugPosition {
	/** The index of the datum in the result. */
	traceIndex: number;
	/** The index of the step in the datum, or -1 for a datum without steps. */
	stepIndex: number;
}

/** The ways to move the debug cursor. */
export type DebugMove = "back" | "continue" | "nextDatum" | "end" | "restart";

/** A Local State entry. */
interface LocalStateValue {
	type: string;
	value: unknown;
}

/** An instruction issued while processing a datum. */
interface IssuedInstruction {
	filterId: string;
	instruction: InstructionInfo;
}

/** A metric a filter saved. */
interface SavedMetric {
	filterId: string;
	metric: MetricInfo;
}

/** The simulated node's state after a filter step. */
export interface DebugState {
	trace: DatumTrace;
	/** The step, or `undefined` for a datum without steps. */
	step?: FilterStep;
	/** `true` if the step is a chain that its filters run after. */
	entering: boolean;
	/** The datum properties before the step. */
	before: SamplesInfo;
	/** The datum properties after the step, or `null` if it was discarded. */
	after: SamplesInfo | null;
	/** A new datum timestamp from the step, if any. */
	timestamp?: string;
	parameters: Record<string, unknown>;
	localState: Map<string, LocalStateValue>;
	/** The Local State keys the step changed. */
	localStateChanged: Set<string>;
	opModes: string[];
	/** The modes the step changed. */
	opModesChanged: Set<string>;
	history: Record<string, DatumInfo[]>;
	unfilteredHistory: Record<string, DatumInfo[]>;
	/** The datum waiting in the queue. */
	queue: number[];
	/** The datum generated while processing this datum, so far. */
	generated: number[];
	instructions: IssuedInstruction[];
	/** The metrics saved while processing this datum, so far. */
	metrics: SavedMetric[];
	/** The simulated controls. */
	controls: ControlInfo[];
	/** The IDs of the controls the step set. */
	controlsChanged: Set<string>;
	/** The instructions waiting for the Instruction Execution Job. */
	waiting: InstructionInfo[];
	/** The numbers of the waiting instructions the step stored. */
	waitingChanged: Set<number>;
	/** The number of datum output by the Datum Queue before this one. */
	outputBefore: number;
}

/**
 * Get every debug cursor position of a result, in order.
 *
 * @param result the result
 * @returns the positions
 */
export function debugPositions(result: SimulationResult): DebugPosition[] {
	const positions: DebugPosition[] = [];
	result.datum.forEach((trace, traceIndex) => {
		const steps = trace.steps ?? [];
		if (!steps.length) {
			positions.push({ traceIndex, stepIndex: -1 });
		}
		steps.forEach((_, stepIndex) =>
			positions.push({ traceIndex, stepIndex }),
		);
	});
	return positions;
}

function indexOf(positions: DebugPosition[], pos: DebugPosition): number {
	return positions.findIndex(
		(p) => p.traceIndex === pos.traceIndex && p.stepIndex === pos.stepIndex,
	);
}

/**
 * Find the closest position to one in an earlier result, such as after a
 * re-run.
 *
 * @param positions the positions of the new result
 * @param result the new result
 * @param seq the datum sequence number of the old position
 * @param stepIndex the step index of the old position
 * @returns the position, or `null` if there are none
 */
export function closestPosition(
	positions: DebugPosition[],
	result: SimulationResult,
	seq: number,
	stepIndex: number,
): DebugPosition | null {
	if (!positions.length) {
		return null;
	}
	const traceIndex = result.datum.findIndex((t) => t.seq === seq);
	if (traceIndex < 0) {
		return positions[positions.length - 1];
	}
	const same = positions.filter((p) => p.traceIndex === traceIndex);
	return same.find((p) => p.stepIndex === stepIndex) ?? same[same.length - 1];
}

/**
 * Move a debug cursor.
 *
 * @param positions the positions
 * @param pos the current position
 * @param move how to move
 * @returns the new position
 */
export function moveCursor(
	positions: DebugPosition[],
	pos: DebugPosition,
	move: DebugMove,
): DebugPosition {
	const i = indexOf(positions, pos);
	switch (move) {
		case "back":
			return positions[Math.max(0, i - 1)];
		case "continue":
			return positions[Math.min(positions.length - 1, i + 1)];
		case "nextDatum":
			return (
				positions.find((p) => p.traceIndex > pos.traceIndex) ??
				positions[positions.length - 1]
			);
		case "end":
			return positions[positions.length - 1];
		case "restart":
			return positions[0];
	}
}

/**
 * Test if a position is the first or last one.
 *
 * @param positions the positions
 * @param pos the position
 */
export function cursorBounds(
	positions: DebugPosition[],
	pos: DebugPosition,
): { first: boolean; last: boolean } {
	const i = indexOf(positions, pos);
	return { first: i <= 0, last: i >= positions.length - 1 };
}

/**
 * Get the simulated node's state after a filter step.
 *
 * @param result the debug result
 * @param pos the position
 * @returns the state
 */
export function debugState(
	result: SimulationResult,
	pos: DebugPosition,
): DebugState {
	const trace = result.datum[pos.traceIndex];
	const steps = trace.steps ?? [];
	const step = pos.stepIndex >= 0 ? steps[pos.stepIndex] : undefined;
	const start = trace.startState ?? {};
	const state: DebugState = {
		trace,
		step,
		entering: step?.factoryUid === CHAIN_FACTORY_UID,
		before: step?.input ?? trace.input.samples,
		after: trace.input.samples,
		parameters: {},
		localState: new Map(
			(start.localState ?? []).map((s) => [
				s.key,
				{ type: s.type, value: s.value },
			]),
		),
		localStateChanged: new Set(),
		opModes: [...(start.opModes ?? [])],
		opModesChanged: new Set(),
		history: start.history ?? {},
		unfilteredHistory: start.unfilteredHistory ?? {},
		queue: [...(start.queue ?? [])],
		generated: [],
		instructions: [],
		metrics: [],
		controls: (start.controls ?? []).map((c) => ({ ...c })),
		controlsChanged: new Set(),
		waiting: [],
		waitingChanged: new Set(),
		outputBefore: result.datum
			.slice(0, pos.traceIndex)
			.filter((t) => t.output).length,
	};
	const waiting = new Map(
		(start.instructions ?? []).map((i) => [i.number, i]),
	);
	steps.slice(0, pos.stepIndex + 1).forEach((s, i) => {
		const last = i === pos.stepIndex;
		if (s.parameters) {
			state.parameters = s.parameters;
		}
		for (const change of s.localState ?? []) {
			if (change.type === undefined) {
				state.localState.delete(change.key);
			} else {
				state.localState.set(change.key, {
					type: change.type,
					value: change.value,
				});
			}
			if (last) {
				state.localStateChanged.add(change.key);
			}
		}
		const modes = new Set(state.opModes);
		for (const m of s.opModes?.enabled ?? []) {
			modes.add(m);
			if (last) {
				state.opModesChanged.add(m);
			}
		}
		for (const m of s.opModes?.disabled ?? []) {
			modes.delete(m);
			if (last) {
				state.opModesChanged.add(m);
			}
		}
		state.opModes = [...modes].sort();
		for (const seq of s.generated ?? []) {
			state.generated.push(seq);
			state.queue.push(seq);
		}
		for (const instruction of s.instructions ?? []) {
			state.instructions.push({ filterId: s.filterId, instruction });
			// a step records each instruction once, with its latest state
			if (instruction.state === "Received") {
				waiting.set(instruction.number, instruction);
				if (last) {
					state.waitingChanged.add(instruction.number);
				}
			} else {
				waiting.delete(instruction.number);
			}
		}
		for (const metric of s.metrics ?? []) {
			state.metrics.push({ filterId: s.filterId, metric });
		}
		for (const update of s.controls ?? []) {
			const control = state.controls.find(
				(c) => c.controlId === update.controlId,
			);
			if (control) {
				control.value = update.value;
				if (update.mode === undefined) {
					// a set, not a switch changing with its mode
					control.updates++;
					control.modified = trace.input.timestamp;
				}
			}
			if (last) {
				state.controlsChanged.add(update.controlId);
			}
		}
	});
	state.waiting = [...waiting.values()];
	if (step && !state.entering) {
		state.after =
			step.status === "Dropped"
				? null
				: step.status === "Changed" && step.output
					? step.output
					: state.before;
		state.timestamp = step.outputTimestamp;
	} else {
		state.after = state.before;
	}
	return state;
}

/** The debug state tabs. */
const TABS = [
	["sample", "Sample"],
	["parameters", "Parameters"],
	["localState", "Local State"],
	["opModes", "Op Modes"],
	["controls", "Controls"],
	["instructions", "Instructions"],
	["history", "Datum History"],
	["queue", "Queue"],
	["output", "Output & Side Effects"],
] as const;

type TabName = (typeof TABS)[number][0];

/**
 * The debug state view: tabs that show the simulated node's state after the
 * current filter step.
 */
export class DebugView {
	readonly #el: HTMLElement;
	#tab: TabName = "sample";

	/**
	 * Constructor.
	 *
	 * @param el the element to render in
	 */
	constructor(el: HTMLElement) {
		this.#el = el;
	}

	/**
	 * Show a state.
	 *
	 * @param state the state
	 * @param result the result, for datum details
	 * @param label get a filter's name
	 */
	show(
		state: DebugState,
		result: SimulationResult,
		label: (filterId: string) => string,
	): void {
		const nav = element("ul", "nav nav-tabs nav-sm sim-debug-tabs");
		nav.setAttribute("role", "tablist");
		const body = element("div", "sim-debug-tab p-2 small");
		const render = () => {
			body.replaceChildren(
				...this.#content(this.#tab, state, result, label),
			);
			nav.querySelectorAll("button").forEach((b) => {
				const active = b.dataset.tab === this.#tab;
				b.classList.toggle("active", active);
				b.setAttribute("aria-selected", String(active));
			});
		};
		for (const [name, title] of TABS) {
			const item = element("li", "nav-item");
			const button = element("button", "nav-link", title);
			button.type = "button";
			button.dataset.tab = name;
			button.setAttribute("role", "tab");
			button.addEventListener("click", () => {
				this.#tab = name;
				render();
			});
			item.append(button);
			nav.append(item);
		}
		render();
		this.#el.replaceChildren(nav, body);
	}

	#content(
		tab: TabName,
		s: DebugState,
		result: SimulationResult,
		label: (filterId: string) => string,
	): HTMLElement[] {
		switch (tab) {
			case "sample":
				return this.#sample(s, label);
			case "parameters":
				return [
					keyValueTable(
						Object.entries(s.parameters).map(([k, v]) => [
							k,
							formatValue(v),
						]),
						["Parameter", "Value"],
						"No parameters. Filters like the Parameter Expression filter set them, for the filters after them in a chain.",
					),
				];
			case "localState":
				return [
					keyValueTable(
						[...s.localState.entries()].map(([k, v]) => [
							k,
							v.type,
							formatValue(v.value),
						]),
						["Key", "Type", "Value"],
						"No Local State.",
						s.localStateChanged,
					),
				];
			case "opModes":
				return [this.#opModes(s)];
			case "controls":
				return s.controls.length
					? [controlsTable(s.controls, s.controlsChanged)]
					: [
							element(
								"p",
								"text-body-secondary mb-1",
								"No controls. Add them on the Controls input tab.",
							),
						];
			case "instructions":
				return s.waiting.length
					? [
							element(
								"p",
								"mb-1",
								"The instructions waiting for the Instruction Execution Job, like Control Conductor tasks. Those this filter stored are marked.",
							),
							waitingInstructionsTable(
								s.waiting,
								s.waitingChanged,
							),
						]
					: [
							element(
								"p",
								"text-body-secondary mb-1",
								"No instructions are waiting for the Instruction Execution Job. Control Conductor tasks, and Instructor instructions with a future executionDate, wait for it.",
							),
						];
			case "history":
				return [
					this.#history("History", s.history),
					this.#history("Unfiltered History", s.unfilteredHistory),
					element(
						"p",
						"text-body-secondary mb-0",
						"As when the datum started processing. While the filters run, the unfiltered history also has this datum.",
					),
				];
			case "queue":
				return [
					this.#datumList(s.queue, result, "The queue is empty."),
				];
			case "output":
				return this.#output(s, result, label);
		}
	}

	#sample(s: DebugState, label: (filterId: string) => string): HTMLElement[] {
		const parts: HTMLElement[] = [];
		const intro = element("p", "mb-1");
		const t = s.trace;
		intro.append(
			element(
				"span",
				"fw-semibold",
				`#${t.seq} ${t.input.sourceId ?? ""}`,
			),
			` at ${formatTimestamp(s.timestamp ?? t.input.timestamp)}`,
		);
		if (s.step) {
			intro.append(
				s.entering
					? ` — entering ${quoted(label(s.step.filterId))}; its filters run next.`
					: ` — after ${quoted(label(s.step.filterId))} (${s.step.status}).`,
			);
		} else {
			intro.append(" — no filters were applied.");
		}
		parts.push(intro);
		if (s.after === null) {
			parts.push(
				element(
					"p",
					"text-danger-emphasis",
					"The filter discarded the datum, so it is not output.",
				),
				samplesLine("Before", s.before),
			);
			return parts;
		}
		parts.push(samplesTable(s.before, s.after));
		return parts;
	}

	#opModes(s: DebugState): HTMLElement {
		const line = element("div", "d-flex flex-wrap gap-1");
		if (!s.opModes.length) {
			line.append(
				element("span", "text-body-secondary", "No modes active."),
			);
		}
		for (const mode of s.opModes) {
			line.append(
				element(
					"span",
					`sim-chip${s.opModesChanged.has(mode) ? " is-added" : ""}`,
					mode,
				),
			);
		}
		for (const mode of s.opModesChanged) {
			if (!s.opModes.includes(mode)) {
				line.append(
					element("span", "sim-chip is-removed", `− ${mode}`),
				);
			}
		}
		return line;
	}

	#history(title: string, history: Record<string, DatumInfo[]>): HTMLElement {
		const section = element("div", "mb-2");
		section.append(element("div", "fw-semibold", title));
		const sources = Object.keys(history).sort();
		if (!sources.length) {
			section.append(element("div", "text-body-secondary", "Empty."));
		}
		for (const source of sources) {
			section.append(element("div", "font-monospace mt-1", source));
			for (const d of history[source]) {
				section.append(
					samplesLine(formatTimestamp(d.timestamp), d.samples),
				);
			}
		}
		return section;
	}

	#datumList(
		seqs: number[],
		result: SimulationResult,
		empty: string,
	): HTMLElement {
		const list = element("div");
		if (!seqs.length) {
			list.append(element("div", "text-body-secondary", empty));
		}
		for (const seq of seqs) {
			const trace = result.datum.find((t) => t.seq === seq);
			list.append(
				trace
					? samplesLine(
							`#${seq} ${trace.input.sourceId ?? ""}`,
							trace.input.samples,
						)
					: element("div", undefined, `#${seq}`),
			);
		}
		return list;
	}

	#output(
		s: DebugState,
		result: SimulationResult,
		label: (filterId: string) => string,
	): HTMLElement[] {
		const parts: HTMLElement[] = [
			element(
				"p",
				"mb-1",
				`The Datum Queue output ${s.outputBefore} datum before this one.`,
			),
			element("div", "fw-semibold", "Generated datum"),
			this.#datumList(
				s.generated,
				result,
				"No datum generated while processing this datum, so far.",
			),
			element("div", "fw-semibold mt-2", "Instructions"),
		];
		if (!s.instructions.length) {
			parts.push(
				element(
					"div",
					"text-body-secondary",
					"No instructions issued while processing this datum, so far.",
				),
			);
		}
		for (const { filterId, instruction } of s.instructions) {
			parts.push(
				keyValueTable(
					Object.entries(instruction.parameters ?? {}),
					[
						`${instruction.topic} from ${quoted(label(filterId))} (${instruction.state ?? "not handled"})`,
						"",
					],
					"No parameters.",
				),
			);
		}
		if (s.metrics.length) {
			parts.push(
				element("div", "fw-semibold mt-2", "Metrics"),
				keyValueTable(
					s.metrics.map(({ filterId, metric }) => [
						metric.name,
						formatValue(metric.value),
						label(filterId),
					]),
					["Name", "Value", "Filter"],
					"",
				),
			);
		}
		return parts;
	}
}

/** A table of rows, with changed rows marked. */
function keyValueTable(
	rows: string[][],
	headings: string[],
	empty: string,
	changed?: Set<string>,
): HTMLElement {
	if (!rows.length) {
		return element("p", "text-body-secondary mb-1", empty);
	}
	const table = element("table", "table table-sm mb-1 sim-samples-table");
	const head = element("thead");
	const headRow = element("tr");
	headRow.append(...headings.map((h) => element("th", undefined, h)));
	head.append(headRow);
	const body = element("tbody");
	for (const row of rows) {
		const tr = element("tr");
		tr.append(
			...row.map((v, i) =>
				element("td", i === 0 ? "font-monospace" : "text-break", v),
			),
		);
		if (changed?.has(row[0])) {
			tr.classList.add("table-active");
		}
		body.append(tr);
	}
	table.append(head, body);
	return table;
}

/** A table of datum properties before and after a step, with changes marked. */
function samplesTable(before: SamplesInfo, after: SamplesInfo): HTMLElement {
	const rows: string[][] = [];
	const changed = new Set<string>();
	for (const type of SAMPLE_TYPES) {
		const a = before[type] ?? {};
		const b = after[type] ?? {};
		for (const key of new Set([...Object.keys(a), ...Object.keys(b)])) {
			const id = `${type}.${key}`;
			rows.push([
				id,
				key in a ? formatValue(a[key]) : "",
				key in b ? formatValue(b[key]) : "",
			]);
			if (!(key in a) || !(key in b) || !sameValue(a[key], b[key])) {
				changed.add(id);
			}
		}
	}
	const tagsBefore = (before.t ?? []).join(", ");
	const tagsAfter = (after.t ?? []).join(", ");
	if (tagsBefore || tagsAfter) {
		rows.push(["t", tagsBefore, tagsAfter]);
		if (tagsBefore !== tagsAfter) {
			changed.add("t");
		}
	}
	return keyValueTable(
		rows,
		["Property", "Before", "After"],
		"The datum has no properties.",
		changed,
	);
}
