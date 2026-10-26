import {
	closestPosition,
	cursorBounds,
	debugPositions,
	debugState,
	DebugView,
	moveCursor,
	type DebugMove,
	type DebugPosition,
} from "./debug.ts";
import { filterIcon } from "./icons.ts";
import type {
	CommandInfo,
	ControlUpdate,
	DatumInfo,
	DatumTrace,
	FilterStep,
	FilterStepStatus,
	InstructionInfo,
	InstructionRun,
	MessageLevel,
	SimulationMessage,
	SimulationResult,
	SimulationState,
	SkipReason,
} from "./model.ts";
import {
	commandLine,
	commandName,
	controlsTable,
	diffSamples,
	element,
	formatDuration,
	formatTimestamp,
	formatValue,
	instructionLabel,
	instructionOriginText,
	instructionsList,
	LEVEL_CLASSES,
	messageItem,
	quoted,
	SAMPLE_TYPES,
	samplesLine,
	sameValue,
	TYPE_NAMES,
	type SampleType,
	waitingInstructionsTable,
} from "./view.ts";
import type { Workspace } from "./workspace.ts";

/** The run statistics of one filter, for the canvas. */
export interface FilterRunStats {
	/** The datum that reached the filter. */
	in: number;
	/** The datum that went on from the filter. */
	out: number;
	dropped: number;
	changed: number;
	skipped: number;
	errors: number;
}

/** The filters a datum went through. */
export interface DatumPath {
	/** The ids of the filters the datum reached. */
	visited: Set<string>;
	/** The ids of the filters that discarded the datum. */
	dropped: Set<string>;
	/** In debug mode, the id of the filter at the cursor. */
	current?: string;
	/** The datum's sequence number, to draw its route. */
	seq?: number;
	/** The index of the last step to draw the route to, or all if missing. */
	upTo?: number;
}

/** A debug cursor position that stays meaningful across re-runs. */
export interface DebugMark {
	/** The datum sequence number. */
	seq: number;
	stepIndex: number;
}

/** The state of debug mode. */
interface DebugSession {
	positions: DebugPosition[];
	pos: DebugPosition;
	view: DebugView;
	label: HTMLElement;
	buttons: Map<DebugMove, HTMLButtonElement>;
}

const STATUS_CLASSES: Record<FilterStepStatus, string> = {
	Changed: "text-bg-primary",
	Unchanged: "text-bg-light border",
	Dropped: "text-bg-danger",
	Skipped: "text-bg-secondary",
	Error: "text-bg-danger",
	Unsupported: "text-bg-warning",
};

const STATUS_HELP: Record<FilterStepStatus, string> = {
	Changed: "The filter changed the datum.",
	Unchanged: "The filter returned the datum unchanged.",
	Dropped: "The filter discarded the datum.",
	Skipped:
		"The filter ignored the datum, for example because its source ID, Required Mode, or Required Tag conditions do not match.",
	Error: "The filter threw an error.",
	Unsupported:
		"The simulator does not support this filter, so the datum passed through unchanged.",
};

/**
 * Get the run statistics of each filter.
 *
 * @param result the simulation result
 * @returns the statistics, by filter id
 */
export function filterRunStats(
	result: SimulationResult,
): Map<string, FilterRunStats> {
	const stats = new Map<string, FilterRunStats>();
	for (const trace of result.datum) {
		for (const step of trace.steps ?? []) {
			let s = stats.get(step.filterId);
			if (!s) {
				s = {
					in: 0,
					out: 0,
					dropped: 0,
					changed: 0,
					skipped: 0,
					errors: 0,
				};
				stats.set(step.filterId, s);
			}
			s.in++;
			if (step.status === "Dropped") {
				s.dropped++;
			} else if (step.status === "Error") {
				s.errors++;
			} else {
				s.out++;
			}
			if (step.status === "Changed") {
				s.changed++;
			} else if (step.status === "Skipped") {
				s.skipped++;
			}
		}
	}
	return stats;
}

/**
 * Get the filters a datum went through.
 *
 * @param trace the datum trace
 * @returns the path
 */
export function datumPath(trace: DatumTrace): DatumPath {
	const path: DatumPath = {
		visited: new Set(),
		dropped: new Set(),
		seq: trace.seq,
	};
	for (const step of trace.steps ?? []) {
		path.visited.add(step.filterId);
		if (step.status === "Dropped") {
			path.dropped.add(step.filterId);
		}
	}
	return path;
}

/**
 * The most datum a result shows expanded at first: with more, only the first
 * is, so a large run stays easy to scan.
 */
const MAX_START_EXPANDED = 9;

/**
 * How a datum came out of the filters, which the datum filter pills group by,
 * or an instruction run, which has a pill of its own.
 */
type DatumOutcome =
	| "Output"
	| "Output, not persisted"
	| "Discarded"
	| "Error"
	| typeof INSTRUCTION_RUNS;

/** The datum filter pill of the instruction run cards. */
const INSTRUCTION_RUNS = "Instruction runs";

/** The badge class of each datum outcome. */
const OUTCOME_CLASSES: Record<DatumOutcome, string> = {
	Output: "text-bg-success",
	"Output, not persisted": "text-bg-success",
	Discarded: "text-bg-secondary",
	Error: "text-bg-danger",
	[INSTRUCTION_RUNS]: "text-bg-info",
};

/** The outcome of a datum. */
function datumOutcome(trace: DatumTrace): DatumOutcome {
	if (trace.error) {
		return "Error";
	}
	if (trace.output) {
		return trace.persist === false ? "Output, not persisted" : "Output";
	}
	return "Discarded";
}

/** The short name of each skip condition, for a step's note chip. */
const SKIP_LABELS: Record<SkipReason["condition"], string> = {
	SourceId: "source ID",
	RequiredMode: "Required Mode",
	RequiredTag: "Required Tag",
	Other: "",
};

/**
 * Explain why a filter ignored a datum: each condition the datum did not
 * meet, with the values involved as code.
 *
 * @param reasons the reasons
 * @returns the explanation
 */
function skipReasonsElement(reasons: SkipReason[]): HTMLElement {
	const code = (text: string) => element("code", undefined, text);
	const list = (
		values: string | undefined,
		none: string,
	): (string | Node)[] =>
		values
			? values
					.split(", ")
					.flatMap((v, i) => (i > 0 ? [", ", code(v)] : [code(v)]))
			: [none];
	const sentence = (r: SkipReason): (string | Node)[] => {
		const expected = r.expected ?? "";
		switch (r.condition) {
			case "SourceId":
				return r.actual
					? [
							"its source ID ",
							code(r.actual),
							" does not match the Source ID pattern ",
							code(expected),
						]
					: [
							"it has no source ID to match the Source ID pattern ",
							code(expected),
						];
			case "RequiredMode":
				return expected.startsWith("!")
					? [
							"the Required Mode ",
							code(expected),
							" needs ",
							code(expected.substring(1)),
							" to be inactive, but it is active",
						]
					: [
							"the Required Mode ",
							code(expected),
							" is not active; the active modes are: ",
							...list(r.actual, "none"),
						];
			case "RequiredTag":
				return [
					"its tags (",
					...list(r.actual, "none"),
					") do not match the Required Tag ",
					code(expected),
				];
			default:
				return [
					"of a reason of its own, which the simulator cannot tell: the datum met its source ID, Required Mode, and Required Tag conditions, but a filter can ignore a datum for any reason, such as a setting it requires that is not configured",
				];
		}
	};
	const el = element("div", "text-body-secondary mb-1");
	if (reasons.length === 1) {
		const p = element("p", "mb-0");
		p.append(
			"The filter ignored the datum because ",
			...sentence(reasons[0]),
			".",
		);
		el.append(p);
	} else {
		const ul = element("ul", "mb-0 ps-3");
		for (const r of reasons) {
			const li = element("li");
			li.append(...sentence(r));
			ul.append(li);
		}
		el.append(
			element("p", "mb-0", "The filter ignored the datum because:"),
			ul,
		);
	}
	return el;
}

/**
 * Summarize a result's statistics, such as "3 datum processed · 1 output".
 *
 * @param result the result
 */
function summaryText(result: SimulationResult): string {
	const s = result.stats;
	return [
		`${s.processed} datum processed`,
		s.generated ? `${s.generated} generated` : "",
		`${s.output} output`,
		s.discarded ? `${s.discarded} discarded` : "",
		`${s.durationMs} ms`,
	]
		.filter((p) => p)
		.join(" · ");
}

/** The content of an Execution Flow report. */
export interface ReportContent {
	/** The run's statistics, summarized. */
	summary: string;
	/** The flow: messages, datum, and final state. */
	body: HTMLElement;
	/** `true` if there are datum to expand and collapse. */
	collapsible: boolean;
}

/**
 * Count values by key, in the order they first appear.
 *
 * @param keys the keys
 * @returns the count of each key
 */
function countBy<K>(keys: K[]): Map<K, number> {
	const counts = new Map<K, number>();
	for (const k of keys) {
		counts.set(k, (counts.get(k) ?? 0) + 1);
	}
	return counts;
}

/**
 * The Execution Flow panel, which shows a simulation result: messages, every
 * datum with each filter step it went through, and the final state.
 *
 * It emits these events:
 *
 * - `filter`, with a filter id detail, to show a filter;
 * - `datum`, with a {@link DatumPath} detail, or `null`, to highlight the
 *   filters a datum went through;
 * - `usefinalstate`, to use the final state as the next starting state;
 * - `allowcommand`, with a command path detail, to allow a command that did
 *   not run;
 * - `report`, to download the flow as a report;
 * - `close`, to close the panel;
 * - `stop`, to stop debug mode.
 *
 * In debug mode, the panel steps through the result one filter at a time,
 * and shows the simulated node's state after each step. It emits `datum`
 * events with the path so far, and the filter at the cursor.
 */
export class ResultsPanel extends EventTarget {
	readonly #el: HTMLElement;
	readonly #ws: Workspace;
	#result: SimulationResult | null = null;
	#selectedSeq: number | null = null;
	#stale?: HTMLElement;
	/** The message levels hidden by their filter pills, kept between runs. */
	readonly #hiddenLevels = new Set<MessageLevel>();
	/** The datum outcomes hidden by their filter pills, kept between runs. */
	readonly #hiddenOutcomes = new Set<DatumOutcome>();
	/**
	 * The filter step statuses each datum's filter pills hide, by datum
	 * sequence number, for the result shown.
	 */
	readonly #hiddenStatuses = new Map<number, Set<FilterStepStatus>>();
	/** `true` if the messages are collapsed, kept between runs. */
	#messagesCollapsed = false;
	#debug: DebugSession | null = null;

	/**
	 * Constructor.
	 *
	 * @param el the panel element
	 * @param ws the workspace, for filter names
	 */
	constructor(el: HTMLElement, ws: Workspace) {
		super();
		this.#el = el;
		this.#ws = ws;
	}

	/** The result shown, or `null`. */
	get result(): SimulationResult | null {
		return this.#result;
	}

	/**
	 * Show a simulation result.
	 *
	 * @param result the result
	 */
	show(result: SimulationResult): void {
		if (result !== this.#result) {
			this.#hiddenStatuses.clear();
		}
		this.#result = result;
		this.#selectedSeq = null;
		this.#debug = null;
		this.#el.classList.remove("is-debug");
		this.#el.replaceChildren(this.#header(result), this.#body(result));
		this.#el.scrollTop = 0;
	}

	/** Stop showing a result. */
	clear(): void {
		this.#debug = null;
		this.#result = null;
		this.#el.classList.remove("is-debug");
		this.#el.replaceChildren();
	}

	/** `true` in debug mode. */
	get debugging(): boolean {
		return this.#debug !== null;
	}

	/** The debug cursor position, or `null` if not in debug mode. */
	get debugMark(): DebugMark | null {
		const d = this.#debug;
		const result = this.#result;
		if (!d || !result) {
			return null;
		}
		return {
			seq: result.datum[d.pos.traceIndex].seq,
			stepIndex: d.pos.stepIndex,
		};
	}

	/**
	 * Show a simulation result in debug mode.
	 *
	 * @param result the result, with debug detail
	 * @param mark the position to start at, like the position in the previous
	 *     result when re-running, or `null` to start at the first filter
	 */
	showDebug(result: SimulationResult, mark: DebugMark | null): void {
		const positions = debugPositions(result);
		const pos =
			(mark &&
				closestPosition(positions, result, mark.seq, mark.stepIndex)) ||
			positions[0];
		if (!pos) {
			this.show(result);
			return;
		}
		if (result !== this.#result) {
			this.#hiddenStatuses.clear();
		}
		this.#result = result;
		this.#selectedSeq = null;
		const header = this.#debugHeader();
		const stateEl = element("div", "sim-debug-state border-start");
		this.#debug = {
			positions,
			pos,
			view: new DebugView(stateEl),
			label: header.label,
			buttons: header.buttons,
		};
		const layout = element("div", "sim-debug");
		layout.append(this.#body(result), stateEl);
		this.#el.classList.add("is-debug");
		this.#el.replaceChildren(header.el, layout);
		this.#applyCursor();
	}

	/**
	 * Move the debug cursor.
	 *
	 * @param move how to move
	 */
	move(move: DebugMove): void {
		const d = this.#debug;
		if (d) {
			this.#moveTo(moveCursor(d.positions, d.pos, move));
		}
	}

	#moveTo(pos: DebugPosition): void {
		const d = this.#debug;
		if (!d) {
			return;
		}
		d.pos = pos;
		this.#applyCursor();
	}

	#body(result: SimulationResult): HTMLElement {
		const body = element("div", "sim-results-body p-3");
		if (result.messages.length) {
			body.append(this.#messages(result.messages));
		}
		if (!result.datum.length) {
			body.append(
				element("p", "text-body-secondary", "No datum were processed."),
			);
		}
		if (result.datum.length) {
			body.append(this.#datumFilters(result));
		}
		const collapse = result.datum.length > MAX_START_EXPANDED;
		// each instruction run goes after the datum processed before it
		const runs = result.instructionRuns ?? [];
		const runsAfter = (seq: number | undefined) =>
			runs.filter((r) => r.afterSeq === seq).map((r) => this.#run(r));
		body.append(...runsAfter(undefined));
		result.datum.forEach((trace, i) => {
			const section = this.#datum(trace);
			this.#setCollapsed(section, collapse && i > 0);
			body.append(section, ...runsAfter(trace.seq));
		});
		body.append(this.#finalState(result.finalState));
		this.#applyDatumFilters(body);
		body.querySelectorAll<HTMLElement>(".sim-datum").forEach((section) =>
			this.#applyStepFilters(section),
		);
		return body;
	}

	/**
	 * Mark the result as made before the latest changes.
	 *
	 * @param stale `true` if the filters or inputs changed since the run
	 */
	setStale(stale: boolean): void {
		if (this.#stale) {
			this.#stale.hidden = !stale;
		}
	}

	/* === Header and messages === */

	#header(result: SimulationResult): HTMLElement {
		const header = element(
			"header",
			"sim-results-header d-flex align-items-center gap-2 px-3 py-2 border-bottom bg-body-tertiary",
		);
		const title = element("h2", "h6 mb-0 text-nowrap", "Execution Flow");
		const summary = element(
			"span",
			"small text-body-secondary text-truncate",
			summaryText(result),
		);
		this.#stale = element(
			"span",
			"badge text-bg-warning text-nowrap",
			"Changed since this run",
		);
		this.#stale.title =
			"The filters or inputs changed after this run. Run again to see their effect.";
		this.#stale.hidden = true;
		const close = element("button", "btn-close");
		close.type = "button";
		close.title = "Close the execution flow";
		close.setAttribute("aria-label", close.title);
		close.addEventListener("click", () =>
			this.dispatchEvent(new Event("close")),
		);
		header.append(
			title,
			summary,
			this.#stale,
			...this.#collapseAllButtons(result),
			this.#reportButton(result),
			close,
		);
		return header;
	}

	#debugHeader(): {
		el: HTMLElement;
		label: HTMLElement;
		buttons: Map<DebugMove, HTMLButtonElement>;
	} {
		const header = element(
			"header",
			"sim-results-header d-flex flex-wrap align-items-center gap-2 px-3 py-2 border-bottom bg-body-tertiary",
		);
		header.append(element("h2", "h6 mb-0 text-nowrap", "Debug"));
		const group = element("div", "btn-group btn-group-sm");
		group.setAttribute("role", "group");
		group.setAttribute("aria-label", "Debug controls");
		const buttons = new Map<DebugMove, HTMLButtonElement>();
		for (const [move, icon, title] of [
			["restart", "skip-backward-fill", "Restart"],
			["back", "skip-start-fill", "Step Back (Shift+F10)"],
			["continue", "play-fill", "Continue to the next filter (F10)"],
			["nextDatum", "skip-forward-fill", "Next Datum"],
			["end", "skip-end-fill", "Run to End"],
		] as const) {
			const button = element("button", "btn btn-outline-secondary");
			button.type = "button";
			button.title = title;
			button.setAttribute("aria-label", title);
			const glyph = element("i", `bi bi-${icon}`);
			glyph.setAttribute("aria-hidden", "true");
			button.append(glyph);
			button.addEventListener("click", () => this.move(move));
			buttons.set(move, button);
			group.append(button);
		}
		const stop = element("button", "btn btn-sm btn-outline-danger", "Stop");
		stop.type = "button";
		stop.title = "Stop debugging, and show the whole execution flow";
		stop.addEventListener("click", () =>
			this.dispatchEvent(new Event("stop")),
		);
		const label = element(
			"span",
			"small text-body-secondary text-truncate",
		);
		this.#stale = element(
			"span",
			"badge text-bg-warning text-nowrap",
			"Changed since this run",
		);
		this.#stale.hidden = true;
		const close = element(
			"button",
			`btn-close${this.#result ? "" : " ms-auto"}`,
		);
		close.type = "button";
		close.title = "Close the execution flow";
		close.setAttribute("aria-label", close.title);
		close.addEventListener("click", () =>
			this.dispatchEvent(new Event("close")),
		);
		header.append(
			group,
			stop,
			label,
			this.#stale,
			...(this.#result
				? [
						...this.#collapseAllButtons(this.#result),
						this.#reportButton(this.#result),
					]
				: []),
			close,
		);
		return { el: header, label, buttons };
	}

	/**
	 * Get the buttons that expand and collapse every datum, if there are
	 * several, the first of them with the `ms-auto` class.
	 */
	#collapseAllButtons(result: SimulationResult): HTMLElement[] {
		if (result.datum.length < 2) {
			return [];
		}
		const button = (icon: string, title: string, collapsed: boolean) => {
			const b = element(
				"button",
				"btn btn-sm btn-link text-body-secondary p-0 sim-tool",
			);
			b.type = "button";
			b.title = title;
			b.setAttribute("aria-label", title);
			const glyph = element("i", `bi bi-${icon}`);
			glyph.setAttribute("aria-hidden", "true");
			b.append(glyph);
			b.addEventListener("click", () =>
				this.#el
					.querySelectorAll<HTMLElement>(".sim-datum")
					.forEach((section) =>
						this.#setCollapsed(section, collapsed),
					),
			);
			return b;
		};
		const expand = button("arrows-expand", "Expand every datum", false);
		expand.classList.add("ms-auto");
		return [
			expand,
			button(
				"arrows-collapse",
				"Collapse every datum to its header",
				true,
			),
		];
	}

	/**
	 * Get the button that downloads the flow as a report, which emits a
	 * `report` event: with the `ms-auto` class if it is the first button.
	 */
	#reportButton(result: SimulationResult): HTMLButtonElement {
		const b = element(
			"button",
			"btn btn-sm btn-link text-body-secondary p-0 sim-tool",
		);
		b.type = "button";
		b.title = "Download the execution flow as an HTML report";
		b.setAttribute("aria-label", b.title);
		if (result.datum.length < 2) {
			b.classList.add("ms-auto");
		}
		const glyph = element("i", "bi bi-file-earmark-arrow-down");
		glyph.setAttribute("aria-hidden", "true");
		b.append(glyph);
		b.addEventListener("click", () =>
			this.dispatchEvent(new Event("report")),
		);
		return b;
	}

	/**
	 * Render the flow again for a report: the whole flow, as after a normal
	 * run, with the same filters, and datum collapsed as they are shown.
	 *
	 * @returns the content, or `null` if no result is shown
	 */
	reportContent(): ReportContent | null {
		const result = this.#result;
		if (!result) {
			return null;
		}
		const body = this.#body(result);
		body.querySelectorAll<HTMLElement>(".sim-datum").forEach((section) => {
			const shown = this.#el.querySelector(`#${section.id}`);
			if (shown) {
				this.#setCollapsed(
					section,
					shown.classList.contains("is-collapsed"),
				);
			}
		});
		return {
			summary: summaryText(result),
			body,
			collapsible: result.datum.length > 1,
		};
	}

	/**
	 * Collapse a datum to its header, or expand it.
	 *
	 * @param section the datum's section
	 * @param collapsed `true` to collapse it
	 */
	#setCollapsed(section: HTMLElement, collapsed: boolean): void {
		section.classList.toggle("is-collapsed", collapsed);
		const button = section.querySelector<HTMLButtonElement>(
			".sim-datum-collapse",
		);
		if (button) {
			button.setAttribute("aria-expanded", String(!collapsed));
			button.title = collapsed
				? "Expand this datum"
				: "Collapse this datum to its header";
			button.setAttribute("aria-label", button.title);
			button.querySelector("i")!.className =
				`bi bi-chevron-${collapsed ? "right" : "down"}`;
		}
	}

	/** Show the debug cursor: the flow up to it, and the state after it. */
	#applyCursor(): void {
		const d = this.#debug;
		const result = this.#result;
		if (!d || !result) {
			return;
		}
		const { traceIndex, stepIndex } = d.pos;
		const trace = result.datum[traceIndex];
		const last = cursorBounds(d.positions, d.pos);
		let current: HTMLElement | null = null;
		this.#el
			.querySelectorAll<HTMLElement>(".sim-datum")
			.forEach((section) => {
				const ti = Number(section.dataset.trace);
				section.hidden = ti > traceIndex;
				section.classList.toggle("is-current", ti === traceIndex);
				if (ti === traceIndex) {
					this.#setCollapsed(section, false);
				}
				section
					.querySelectorAll<HTMLElement>(".sim-step")
					.forEach((stepEl) => {
						const si = Number(stepEl.dataset.step);
						const pending = ti === traceIndex && si > stepIndex;
						stepEl.classList.toggle("is-pending", pending);
						const isCurrent = ti === traceIndex && si === stepIndex;
						stepEl.classList.toggle("is-current", isCurrent);
						if (isCurrent) {
							current = stepEl;
						}
					});
				const atEnd =
					ti < traceIndex ||
					stepIndex >= (result.datum[ti].steps?.length ?? 0) - 1;
				section
					.querySelectorAll<HTMLElement>(
						".sim-output-line, .sim-datum-result",
					)
					.forEach((el) => el.classList.toggle("is-pending", !atEnd));
			});
		// an instruction run shows once the cursor is past the datum before it,
		// as its changes show in the next datum's state
		const traceIndexes = new Map(result.datum.map((t, i) => [t.seq, i]));
		this.#el
			.querySelectorAll<HTMLElement>(".sim-instruction-run")
			.forEach((el) => {
				const after = el.dataset.afterSeq;
				const ai = after ? (traceIndexes.get(Number(after)) ?? -1) : -1;
				el.hidden = ai >= traceIndex && !last.last;
			});
		this.#el
			.querySelectorAll<HTMLElement>(".sim-final-state")
			.forEach((el) => (el.hidden = !last.last));
		(
			current ?? this.#el.querySelector(".sim-datum.is-current")
		)?.scrollIntoView({ block: "nearest" });

		const step = trace.steps?.[stepIndex];
		const stepCount = trace.steps?.length ?? 0;
		d.label.textContent =
			`Datum #${trace.seq} (${traceIndex + 1} of ${result.datum.length})` +
			(step
				? ` · filter ${stepIndex + 1} of ${stepCount}: ${this.#filterLabel(step.filterId, step.uid)}`
				: " · no filters");
		d.buttons.get("restart")!.disabled = last.first;
		d.buttons.get("back")!.disabled = last.first;
		d.buttons.get("continue")!.disabled = last.last;
		d.buttons.get("nextDatum")!.disabled =
			traceIndex >= result.datum.length - 1;
		d.buttons.get("end")!.disabled = last.last;

		d.view.show(debugState(result, d.pos), result, (id) =>
			this.#filterLabel(id),
		);

		const path: DatumPath = {
			visited: new Set(),
			dropped: new Set(),
			current: step?.filterId,
			seq: trace.seq,
			upTo: stepIndex,
		};
		for (const s of (trace.steps ?? []).slice(0, stepIndex + 1)) {
			path.visited.add(s.filterId);
			if (s.status === "Dropped") {
				path.dropped.add(s.filterId);
			}
		}
		this.dispatchEvent(
			new CustomEvent<DatumPath | null>("datum", { detail: path }),
		);
	}

	/**
	 * Render the messages in a card that collapses, with a pill for each
	 * message level that shows or hides its messages.
	 */
	#messages(messages: SimulationMessage[]): HTMLElement {
		const card = element("section", "card mb-3 sim-messages");
		const header = element(
			"header",
			"card-header d-flex flex-wrap align-items-center gap-2 py-1 px-2",
		);
		const list = element("div", "list-group list-group-flush small");
		for (const m of messages) {
			let link: { title: string; show: () => void } | undefined;
			if (m.filterId) {
				const filterId = m.filterId;
				link = {
					title: "Show the filter",
					show: () => this.#emitFilter(filterId),
				};
			} else if (m.datumSeq !== undefined) {
				const seq = m.datumSeq;
				link = {
					title: "Show the datum",
					show: () => this.#scrollToDatum(seq),
				};
			}
			const item = messageItem(m, link);
			item.dataset.level = m.level;
			list.append(item);
		}
		const applyLevels = () =>
			list.querySelectorAll<HTMLElement>("[data-level]").forEach((item) =>
				// a class, as the row's d-flex display overrides hidden
				item.classList.toggle(
					"is-filtered",
					this.#hiddenLevels.has(item.dataset.level as MessageLevel),
				),
			);
		const collapse = this.#collapseButton("the messages", (collapsed) => {
			this.#messagesCollapsed = collapsed;
			card.classList.toggle("is-collapsed", collapsed);
		});
		collapse.button.classList.add("sim-messages-collapse");
		header.append(
			collapse.button,
			element("span", "fw-semibold small", "Messages"),
		);
		const pills = element(
			"span",
			"d-flex flex-wrap justify-content-end gap-2 ms-auto",
		);
		header.append(pills);
		for (const [level, count] of countBy(messages.map((m) => m.level))) {
			pills.append(
				this.#filterPill(
					"level",
					level,
					count,
					LEVEL_CLASSES[level] ?? "text-bg-secondary",
					this.#hiddenLevels,
					applyLevels,
				),
			);
		}
		card.append(header, list);
		collapse.set(this.#messagesCollapsed);
		applyLevels();
		return card;
	}

	/** Render the pills that show or hide datum by their outcome. */
	#datumFilters(result: SimulationResult): HTMLElement {
		const bar = element(
			"div",
			"d-flex flex-wrap align-items-center gap-2 mb-2 sim-datum-filters",
		);
		const pills = element(
			"span",
			"d-flex flex-wrap justify-content-end gap-2 ms-auto",
		);
		bar.append(element("span", "fw-semibold small", "Datum"), pills);
		for (const [outcome, count] of countBy<DatumOutcome>([
			...result.datum.map(datumOutcome),
			...(result.instructionRuns ?? []).map(
				(): DatumOutcome => INSTRUCTION_RUNS,
			),
		])) {
			pills.append(
				this.#filterPill(
					"outcome",
					outcome,
					count,
					OUTCOME_CLASSES[outcome],
					this.#hiddenOutcomes,
					() => this.#applyDatumFilters(this.#el),
				),
			);
		}
		return bar;
	}

	/** Hide the filter steps whose status a filter pill hides. */
	/** Hide the filter steps of a datum whose status its filter pills hide. */
	#applyStepFilters(section: HTMLElement): void {
		const hidden = this.#statusesHiddenIn(Number(section.dataset.seq));
		section
			.querySelectorAll<HTMLElement>(".sim-step[data-status]")
			.forEach((step) =>
				step.classList.toggle(
					"is-filtered",
					hidden.has(step.dataset.status as FilterStepStatus),
				),
			);
	}

	/** Get the filter step statuses a datum's filter pills hide. */
	#statusesHiddenIn(seq: number): Set<FilterStepStatus> {
		let hidden = this.#hiddenStatuses.get(seq);
		if (!hidden) {
			hidden = new Set();
			this.#hiddenStatuses.set(seq, hidden);
		}
		return hidden;
	}

	/** Render the pills that show or hide a datum's filter steps by status. */
	#stepFilters(trace: DatumTrace, section: HTMLElement): HTMLElement {
		const bar = element(
			"div",
			"d-flex flex-wrap align-items-center justify-content-end gap-1 mb-1 sim-step-filters",
		);
		for (const [status, count] of countBy(
			(trace.steps ?? []).map((s) => s.status),
		)) {
			bar.append(
				this.#filterPill(
					"status",
					status,
					count,
					// soft, so they do not compete with the steps' own badges
					`sim-pill-soft sim-pill-${status.toLowerCase()}`,
					this.#statusesHiddenIn(trace.seq),
					() => this.#applyStepFilters(section),
				),
			);
		}
		return bar;
	}

	/** Hide the datum and instruction runs whose outcome a filter pill hides. */
	#applyDatumFilters(root: HTMLElement): void {
		root.querySelectorAll<HTMLElement>(
			".sim-datum, .sim-instruction-run",
		).forEach((section) =>
			section.classList.toggle(
				"is-filtered",
				this.#hiddenOutcomes.has(
					section.dataset.outcome as DatumOutcome,
				),
			),
		);
	}

	/**
	 * Render a pill that shows or hides a kind of row, with its count.
	 *
	 * @param name the kind, such as "Info"
	 * @param count how many rows there are of the kind
	 * @param className the pill's color class
	 * @param hidden the hidden kinds, which the pill changes
	 * @param apply show or hide the rows after a change
	 */
	#filterPill<K extends string>(
		group: "level" | "outcome" | "status",
		name: K,
		count: number,
		className: string,
		hidden: Set<K>,
		apply: () => void,
	): HTMLButtonElement {
		const pill = element(
			"button",
			`badge rounded-pill border-0 sim-filter-pill ${className}`,
			`${name} ${count}`,
		);
		pill.type = "button";
		pill.dataset.filterGroup = group;
		pill.dataset.filterValue = name;
		const refresh = () => {
			const shown = !hidden.has(name);
			pill.setAttribute("aria-pressed", String(shown));
			pill.title = `${shown ? "Hide" : "Show"} the ${name} rows`;
		};
		pill.addEventListener("click", () => {
			if (hidden.has(name)) {
				hidden.delete(name);
			} else {
				hidden.add(name);
			}
			// the pills of the same kind in the same scope stay in step: a
			// datum's own step status pills, or else the whole flow's
			const shown = !hidden.has(name);
			(
				pill.closest(".sim-datum") ??
				pill.closest(".sim-results-body") ??
				this.#el
			)
				.querySelectorAll<HTMLElement>(
					`.sim-filter-pill[data-filter-group="${group}"][data-filter-value="${CSS.escape(name)}"]`,
				)
				.forEach((p) => {
					p.setAttribute("aria-pressed", String(shown));
					p.title = `${shown ? "Hide" : "Show"} the ${name} rows`;
				});
			apply();
		});
		refresh();
		return pill;
	}

	/**
	 * Make a chevron button that collapses something.
	 *
	 * @param what what it collapses, for its title
	 * @param onChange called with the new state
	 * @returns the button, and a function that sets the state
	 */
	#collapseButton(
		what: string,
		onChange: (collapsed: boolean) => void,
	): { button: HTMLButtonElement; set: (collapsed: boolean) => void } {
		const button = element(
			"button",
			"btn btn-sm btn-link text-body-secondary p-0 sim-tool",
		);
		button.type = "button";
		const chevron = element("i");
		chevron.setAttribute("aria-hidden", "true");
		button.append(chevron);
		let state = false;
		const set = (collapsed: boolean) => {
			state = collapsed;
			button.setAttribute("aria-expanded", String(!collapsed));
			button.title = `${collapsed ? "Expand" : "Collapse"} ${what}`;
			button.setAttribute("aria-label", button.title);
			chevron.className = `bi bi-chevron-${collapsed ? "right" : "down"}`;
			onChange(collapsed);
		};
		button.addEventListener("click", () => set(!state));
		return { button, set };
	}

	/* === Datum === */

	#filterLabel(filterId: string | undefined, uid?: string): string {
		if (!filterId) {
			return "a filter";
		}
		const filter = this.#ws.filter(filterId);
		return filter ? this.#ws.label(filter) : (uid ?? filterId);
	}

	#datumLink(seq: number): HTMLElement {
		const link = element("a", "link-secondary", `#${seq}`);
		link.href = `#sim-datum-${seq}`;
		link.addEventListener("click", (e) => {
			e.preventDefault();
			e.stopPropagation();
			this.#scrollToDatum(seq);
		});
		return link;
	}

	#datum(trace: DatumTrace): HTMLElement {
		const section = element("section", "card mb-3 sim-datum");
		section.id = `sim-datum-${trace.seq}`;
		section.dataset.outcome = datumOutcome(trace);
		section.dataset.seq = String(trace.seq);
		section.dataset.trace = String(this.#result?.datum.indexOf(trace) ?? 0);

		const header = element(
			"header",
			"card-header d-flex flex-wrap align-items-baseline gap-2 py-1 px-2 sim-datum-header",
		);
		header.tabIndex = 0;
		header.setAttribute("role", "button");
		header.setAttribute("aria-pressed", "false");
		header.title = "Highlight the filters this datum went through";
		const origin = element("span", "small text-body-secondary");
		if (trace.origin.type === "Input") {
			origin.append(`Input ${(trace.origin.index ?? 0) + 1}`);
		} else if (trace.origin.type === "Scheduled") {
			origin.append(
				`Generated by a scheduled task of ${quoted(this.#filterLabel(trace.origin.filterId))}`,
			);
		} else if (trace.origin.type === "Instruction") {
			origin.append(
				instructionOriginText(
					trace.origin,
					this.#result?.instructionRuns,
				),
			);
			const run = this.#result?.instructionRuns?.find(
				(r) => r.instruction.number === trace.origin.instruction,
			);
			if (run) {
				origin.title = "The instruction run is above this datum";
			}
		} else {
			origin.append(
				trace.origin.controlId
					? `Published by control ${trace.origin.controlId}, set by ${quoted(this.#filterLabel(trace.origin.filterId))}`
					: `Generated by ${quoted(this.#filterLabel(trace.origin.filterId))}`,
			);
			if (trace.origin.parentSeq !== undefined) {
				origin.append(
					" from ",
					this.#datumLink(trace.origin.parentSeq),
				);
			}
		}
		const result = trace.error
			? element("span", "badge text-bg-danger", "Error")
			: trace.output
				? element(
						"span",
						"badge text-bg-success",
						trace.persist === false
							? "Output, not persisted"
							: "Output",
					)
				: element("span", "badge text-bg-secondary", "Discarded");
		result.classList.add("ms-auto", "sim-datum-result");
		const collapse = element(
			"button",
			"btn btn-sm btn-link text-body-secondary p-0 sim-tool sim-datum-collapse",
		);
		collapse.type = "button";
		const chevron = element("i");
		chevron.setAttribute("aria-hidden", "true");
		collapse.append(chevron);
		collapse.addEventListener("click", (e) => {
			// not a click on the header, which selects the datum
			e.stopPropagation();
			this.#setCollapsed(
				section,
				!section.classList.contains("is-collapsed"),
			);
		});
		collapse.addEventListener("keydown", (e) => e.stopPropagation());
		header.append(
			collapse,
			element("span", "badge text-bg-dark", `#${trace.seq}`),
			element(
				"span",
				"fw-semibold text-break",
				trace.input.sourceId ?? "",
			),
			element(
				"span",
				"small font-monospace text-body-secondary",
				formatTimestamp(trace.input.timestamp),
			),
			origin,
			result,
		);
		const toggle = () => {
			const d = this.#debug;
			if (d) {
				const ti = Number(section.dataset.trace);
				const first = d.positions.find((p) => p.traceIndex === ti);
				if (first) {
					this.#moveTo(first);
				}
			} else {
				this.#selectDatum(trace, section, header);
			}
		};
		header.addEventListener("click", toggle);
		header.addEventListener("keydown", (e) => {
			if (e.key === "Enter" || e.key === " ") {
				e.preventDefault();
				toggle();
			}
		});

		const body = element("div", "card-body p-2");
		if (trace.steps?.length) {
			body.append(this.#stepFilters(trace, section));
		}
		body.append(samplesLine("In", trace.input.samples));
		const steps = element("div", "sim-steps my-1");
		(trace.steps ?? []).forEach((step, i) => {
			const el = this.#step(step);
			el.dataset.step = String(i);
			el.querySelector("summary")!.addEventListener("click", (e) => {
				const d = this.#debug;
				if (d) {
					// in debug mode, a click moves the cursor to the step
					e.preventDefault();
					this.#moveTo({
						traceIndex: Number(section.dataset.trace),
						stepIndex: i,
					});
				}
			});
			steps.append(el);
		});
		if (!trace.steps?.length) {
			steps.append(
				element(
					"div",
					"small text-body-secondary",
					"No filters were applied.",
				),
			);
		}
		body.append(steps);
		if (trace.error) {
			body.append(
				element(
					"div",
					"alert alert-danger small py-1 px-2 mb-1",
					`The filters failed, so the datum went on unchanged: ${trace.error}`,
				),
			);
		}
		if (trace.output) {
			body.append(this.#outputLine(trace.output));
		}
		section.append(header, body);
		return section;
	}

	#outputLine(output: DatumInfo): HTMLElement {
		const line = samplesLine("Out", output.samples);
		line.classList.add("sim-output-line");
		if (output.timestamp) {
			line.title = `Timestamp: ${formatTimestamp(output.timestamp)}`;
		}
		return line;
	}

	#selectDatum(
		trace: DatumTrace,
		section: HTMLElement,
		header: HTMLElement,
	): void {
		const select = this.#selectedSeq !== trace.seq;
		this.#el
			.querySelectorAll(".sim-datum.is-selected")
			.forEach((el) => el.classList.remove("is-selected"));
		this.#el
			.querySelectorAll(".sim-datum-header[aria-pressed=true]")
			.forEach((el) => el.setAttribute("aria-pressed", "false"));
		this.#selectedSeq = select ? trace.seq : null;
		section.classList.toggle("is-selected", select);
		if (select) {
			this.#setCollapsed(section, false);
		}
		header.setAttribute("aria-pressed", String(select));
		this.dispatchEvent(
			new CustomEvent<DatumPath | null>("datum", {
				detail: select ? datumPath(trace) : null,
			}),
		);
	}

	/** Unselect the selected datum, if there is one. */
	clearDatum(): void {
		const seq = this.#selectedSeq;
		const trace = this.#result?.datum.find((t) => t.seq === seq);
		const section = this.#el.querySelector<HTMLElement>(
			`#sim-datum-${seq}`,
		);
		const header = section?.querySelector<HTMLElement>(".sim-datum-header");
		if (trace && section && header) {
			this.#selectDatum(trace, section, header);
		}
	}

	/**
	 * Select a datum and scroll to it, such as for a route clicked on the
	 * canvas, or unselect it if it is selected. In debug mode, move the cursor
	 * to it instead.
	 *
	 * @param seq the datum sequence number
	 */
	showDatum(seq: number): void {
		const section = this.#el.querySelector<HTMLElement>(
			`#sim-datum-${seq}`,
		);
		const trace = this.#result?.datum.find((t) => t.seq === seq);
		if (!section || !trace) {
			return;
		}
		const d = this.#debug;
		if (d) {
			const ti = Number(section.dataset.trace);
			const first = d.positions.find((p) => p.traceIndex === ti);
			if (first) {
				this.#moveTo(first);
			}
		} else if (this.#selectedSeq === seq) {
			this.clearDatum();
			return;
		} else {
			const header =
				section.querySelector<HTMLElement>(".sim-datum-header");
			if (header) {
				this.#selectDatum(trace, section, header);
			}
		}
		this.#scrollToDatum(seq);
	}

	#scrollToDatum(seq: number): void {
		const section = this.#el.querySelector<HTMLElement>(
			`#sim-datum-${seq}`,
		);
		const d = this.#debug;
		if (section && d && section.hidden) {
			// debug mode hides the datum after the cursor, so move the cursor to it
			const ti = Number(section.dataset.trace);
			const first = d.positions.find((p) => p.traceIndex === ti);
			if (first) {
				this.#moveTo(first);
			}
		}
		const outcome = section?.dataset.outcome as DatumOutcome | undefined;
		if (outcome && this.#hiddenOutcomes.has(outcome)) {
			// show the datum's outcome again, so the datum shows
			this.#hiddenOutcomes.delete(outcome);
			this.#el
				.querySelectorAll<HTMLButtonElement>(
					".sim-datum-filters .sim-filter-pill",
				)
				.forEach((pill) => {
					if (pill.textContent?.startsWith(`${outcome} `)) {
						pill.setAttribute("aria-pressed", "true");
						pill.title = `Hide the ${outcome} rows`;
					}
				});
			this.#applyDatumFilters(this.#el);
		}
		if (section && !section.hidden) {
			this.#setCollapsed(section, false);
			section.scrollIntoView({ behavior: "smooth", block: "start" });
			section.classList.remove("is-flashed");
			void section.offsetWidth;
			section.classList.add("is-flashed");
		}
	}

	#emitFilter(filterId: string): void {
		this.dispatchEvent(
			new CustomEvent<string>("filter", { detail: filterId }),
		);
	}

	/* === Filter steps === */

	#step(step: FilterStep): HTMLElement {
		const details = element("details", "sim-step");
		details.dataset.status = step.status;
		details.style.setProperty("--sim-depth", String(step.depth ?? 0));
		const summary = element(
			"summary",
			"d-flex flex-wrap align-items-center gap-2 small",
		);
		const icon = element(
			"i",
			`bi bi-${filterIcon(step.factoryUid, step.status !== "Unsupported")} text-body-secondary`,
		);
		icon.setAttribute("aria-hidden", "true");
		const name = element(
			"button",
			"btn btn-link btn-sm p-0 sim-step-filter text-start",
			this.#filterLabel(step.filterId, step.uid),
		);
		name.type = "button";
		name.title = "Show the filter";
		// a report shows the name as text
		name.dataset.report = "text";
		name.addEventListener("click", (e) => {
			e.preventDefault();
			this.#emitFilter(step.filterId);
		});
		const status = element(
			"span",
			`badge ${STATUS_CLASSES[step.status] ?? "text-bg-secondary"}`,
			step.status,
		);
		status.title = STATUS_HELP[step.status] ?? "";
		const changes = element(
			"span",
			"sim-step-changes d-flex flex-wrap gap-1",
		);
		changes.append(...this.#stepChanges(step));
		const duration = element(
			"span",
			"ms-auto text-body-secondary text-nowrap",
			formatDuration(step.durationMicros),
		);
		duration.title = "The time the filter took";
		summary.append(icon, name, status, changes, duration);
		details.append(summary, this.#stepDetails(step));
		return details;
	}

	/** The chips that summarize what a step changed. */
	#stepChanges(step: FilterStep): HTMLElement[] {
		const chips: HTMLElement[] = [];
		const chip = (text: string, className = "", title?: string) => {
			const el = element("span", `sim-chip ${className}`, text);
			if (title) {
				el.title = title;
			}
			chips.push(el);
			return el;
		};
		if (step.status === "Changed" && step.input && step.output) {
			for (const c of diffSamples(step.input, step.output)) {
				const name = c.type === "t" ? `#${c.key}` : c.key;
				const title = `${TYPE_NAMES[c.type]} ${c.type === "t" ? "tag" : "property"} ${c.key}`;
				if (c.kind === "added") {
					chip(
						c.type === "t"
							? `+ ${name}`
							: `+ ${name} = ${formatValue(c.to)}`,
						"is-added",
						`${title} added`,
					);
				} else if (c.kind === "removed") {
					chip(`− ${name}`, "is-removed", `${title} removed`);
				} else {
					chip(
						`${name}: ${formatValue(c.from)} → ${formatValue(c.to)}`,
						"is-changed",
						`${title} changed`,
					);
				}
			}
		}
		if (step.outputTimestamp) {
			chip(
				`timestamp → ${formatTimestamp(step.outputTimestamp)}`,
				"is-changed",
				"The filter changed the datum timestamp",
			);
		}
		if (step.error) {
			chip(step.error, "is-removed", "The filter error");
		}
		if (step.parameters && Object.keys(step.parameters).length) {
			chip(
				`parameters: ${Object.entries(step.parameters)
					.map(([k, v]) => `${k} = ${formatValue(v)}`)
					.join(", ")}`,
				"",
				"The filter parameters after this filter",
			);
		}
		if (step.opModes) {
			const modes = [
				...(step.opModes.enabled ?? []).map((m) => `+${m}`),
				...(step.opModes.disabled ?? []).map((m) => `−${m}`),
			];
			chip(`modes: ${modes.join(" ")}`, "", "Operational modes changed");
		}
		for (const ls of step.localState ?? []) {
			chip(
				ls.type === undefined
					? `local state: − ${ls.key}`
					: `local state: ${ls.key} ${ls.previousValue !== undefined ? `${formatValue(ls.previousValue)} → ` : "= "}${formatValue(ls.value)}`,
				"",
				"Local State changed",
			);
		}
		for (const m of step.metrics ?? []) {
			chip(
				`metric: ${m.name} = ${formatValue(m.value)}`,
				"",
				`Metric saved, of type ${m.type}, at ${formatTimestamp(m.timestamp)}`,
			);
		}
		for (const c of step.commands ?? []) {
			chip(
				`command: ${commandName(c.command[0] ?? "")}${c.ran ? "" : " (not allowed)"}`,
				c.error ? "is-removed" : "",
				commandLine(c.command),
			);
		}
		for (const c of step.controls ?? []) {
			const changed = c.previousValue !== c.value;
			chip(
				`control: ${c.controlId} ${changed && c.previousValue !== undefined ? `${c.previousValue} → ` : "= "}${c.value}${changed ? "" : " (same)"}`,
				"",
				`Control ${c.controlId} (${c.type}) set${changed ? "" : " to the same value"}${c.published ? ", which published a datum" : ""}`,
			);
		}
		const instructions = step.instructions ?? [];
		const numbers = new Set(instructions.map((i) => i.number));
		for (const instr of instructions) {
			if (instr.parent !== undefined && numbers.has(instr.parent)) {
				// in its parent's tooltip
				continue;
			}
			chip(
				`instruction: ${instr.topic} (${instr.state ?? "not handled"})`,
				instr.state === "Declined" || !instr.state ? "is-removed" : "",
				instructionTitle(instr, instructions),
			);
		}
		if (step.generated?.length) {
			const el = chip(
				"generated ",
				"",
				"Datum the filter added to the Datum Queue",
			);
			step.generated.forEach((seq, i) => {
				if (i > 0) {
					el.append(", ");
				}
				el.append(this.#datumLink(seq));
			});
		}
		if (!chips.length) {
			const text: Partial<Record<FilterStepStatus, string>> = {
				Unchanged: "no change",
				Dropped: "discarded the datum",
				Skipped: "ignored the datum",
				Unsupported: "passed the datum through",
			};
			const reasons = (step.skipReasons ?? []).filter(
				(r) => r.condition !== "Other",
			);
			const note =
				step.status === "Skipped" && reasons.length
					? `ignored: ${reasons.map((r) => SKIP_LABELS[r.condition]).join(", ")} not matched`
					: text[step.status];
			if (note) {
				chip(note, "is-note");
			}
		}
		return chips;
	}

	#stepDetails(step: FilterStep): HTMLElement {
		const body = element("div", "sim-step-details small");
		if (step.status === "Skipped" && step.skipReasons?.length) {
			body.append(skipReasonsElement(step.skipReasons));
		} else {
			body.append(
				element(
					"p",
					"text-body-secondary mb-1",
					STATUS_HELP[step.status] ?? "",
				),
			);
		}
		const input = step.input ?? {};
		const output = step.status === "Changed" ? step.output : undefined;
		const table = element("table", "table table-sm mb-1 sim-samples-table");
		const head = element("thead");
		const headRow = element("tr");
		headRow.append(
			element("th", undefined, "Type"),
			element("th", undefined, "Property"),
			element("th", undefined, output ? "In" : "Value"),
		);
		if (output) {
			headRow.append(element("th", undefined, "Out"));
		}
		head.append(headRow);
		const rows = element("tbody");
		const keys = (type: SampleType) => [
			...new Set([
				...Object.keys(input[type] ?? {}),
				...Object.keys(output?.[type] ?? {}),
			]),
		];
		for (const type of SAMPLE_TYPES) {
			for (const key of keys(type)) {
				const row = element("tr");
				const a = input[type]?.[key];
				const b = output?.[type]?.[key];
				const hasA = !!input[type] && key in input[type]!;
				const hasB = !!output?.[type] && key in output[type]!;
				row.append(
					element("td", "text-body-secondary", type),
					element("td", "font-monospace", key),
					element(
						"td",
						"font-monospace text-break",
						hasA ? formatValue(a) : "",
					),
				);
				if (output) {
					row.append(
						element(
							"td",
							"font-monospace text-break",
							hasB ? formatValue(b) : "",
						),
					);
					if (!hasA || !hasB || !sameValue(a, b)) {
						row.classList.add("table-active");
					}
				}
				rows.append(row);
			}
		}
		const tagsIn = input.t ?? [];
		const tagsOut = output?.t;
		if (tagsIn.length || tagsOut?.length) {
			const row = element("tr");
			row.append(
				element("td", "text-body-secondary", "t"),
				element("td", undefined, "tags"),
				element("td", "font-monospace text-break", tagsIn.join(", ")),
			);
			if (output) {
				row.append(
					element(
						"td",
						"font-monospace text-break",
						(tagsOut ?? []).join(", "),
					),
				);
			}
			rows.append(row);
		}
		if (!rows.children.length) {
			body.append(
				element(
					"p",
					"text-body-secondary mb-1",
					"The datum had no properties.",
				),
			);
		} else {
			table.append(head, rows);
			body.append(table);
		}
		for (const c of step.commands ?? []) {
			body.append(this.#commandDetails(c));
		}
		if (step.controls?.length) {
			body.append(controlUpdatesTable(step.controls));
		}
		if (step.instructions?.length) {
			body.append(instructionsList(step.instructions));
		}
		return body;
	}

	/* === Instruction runs === */

	/**
	 * Render an instruction the Instruction Execution Job ran, as a compact
	 * card between the datum.
	 */
	#run(run: InstructionRun): HTMLElement {
		const instr = run.instruction;
		const section = element(
			"section",
			"card mb-3 small sim-instruction-run",
		);
		section.dataset.outcome = INSTRUCTION_RUNS;
		section.dataset.afterSeq = String(run.afterSeq ?? "");
		const details = element("details");
		const summary = element(
			"summary",
			"card-header d-flex flex-wrap align-items-baseline gap-2 py-1 px-2",
		);
		summary.title = "Show the instructions of this run";
		const icon = element("i", "bi bi-clock-history text-info-emphasis");
		icon.setAttribute("aria-hidden", "true");
		const state = instr.state ?? "not handled";
		summary.append(
			icon,
			element(
				"span",
				"font-monospace text-body-secondary",
				formatTimestamp(run.date),
			),
			element("span", "fw-semibold", instructionLabel(instr)),
			element(
				"span",
				`badge ${run.error || instr.state === "Declined" || !instr.state ? "text-bg-danger" : "text-bg-light border"}`,
				run.error ? "Error" : state,
			),
		);
		const chips = element("span", "d-flex flex-wrap gap-1");
		for (const c of run.controls ?? []) {
			const changed = c.previousValue !== c.value;
			const el = element(
				"span",
				"sim-chip",
				`control: ${c.controlId} ${changed && c.previousValue !== undefined ? `${c.previousValue} → ` : "= "}${c.value}${changed ? "" : " (same)"}`,
			);
			el.title = `Control ${c.controlId} (${c.type}) set${changed ? "" : " to the same value"}${c.published ? ", which published a datum" : ""}`;
			chips.append(el);
		}
		if (run.generated?.length) {
			const el = element("span", "sim-chip", "published ");
			el.title = "Datum the controls published, processed next";
			run.generated.forEach((seq, i) => {
				if (i > 0) {
					el.append(", ");
				}
				el.append(this.#datumLink(seq));
			});
			chips.append(el);
		}
		summary.append(chips);
		if (run.datumSeq !== undefined || run.filterId) {
			const from = element("span", "ms-auto text-body-secondary");
			from.append("issued by ");
			if (run.datumSeq !== undefined) {
				from.append(this.#datumLink(run.datumSeq), " ");
			}
			from.append(quoted(this.#filterLabel(run.filterId)));
			summary.append(from);
		}
		const body = element("div", "card-body p-2");
		if (run.error) {
			body.append(
				element(
					"div",
					"alert alert-danger py-1 px-2 mb-1",
					`The instruction failed: ${run.error}`,
				),
			);
		}
		body.append(instructionsList([instr, ...(run.instructions ?? [])]));
		if (run.controls?.length) {
			body.append(controlUpdatesTable(run.controls));
		}
		details.append(summary, body);
		section.append(details);
		return section;
	}

	/** The details of an external command a step asked to run. */
	#commandDetails(c: CommandInfo): HTMLElement {
		const section = element("div", "mt-2");
		const heading = element(
			"div",
			"d-flex align-items-center gap-2 fw-semibold",
			c.ran ? "Command" : "Command (not allowed)",
		);
		if (c.ran && c.durationMicros !== undefined) {
			heading.append(
				element(
					"span",
					"fw-normal text-body-secondary",
					formatDuration(c.durationMicros),
				),
			);
		}
		const path = c.command[0];
		if (!c.ran && path) {
			const allow = element(
				"button",
				"btn btn-outline-secondary btn-sm py-0",
				"Allow",
			);
			allow.type = "button";
			allow.dataset.report = "remove";
			allow.title = `Add ${path} to the Allowed Commands, so the next run can run it. Allow only programs you trust.`;
			allow.addEventListener("click", () =>
				this.dispatchEvent(
					new CustomEvent<string>("allowcommand", { detail: path }),
				),
			);
			heading.append(allow);
		}
		section.append(
			heading,
			element("pre", "sim-command mb-1", commandLine(c.command)),
		);
		if (c.error) {
			section.append(element("div", "text-danger-emphasis", c.error));
		}
		if (c.result) {
			const table = element(
				"table",
				"table table-sm mb-1 sim-samples-table",
			);
			const head = element("thead");
			const headRow = element("tr");
			headRow.append(
				element("th", undefined, "Result"),
				element("th", undefined, "Value"),
			);
			head.append(headRow);
			const rows = element("tbody");
			for (const [k, v] of Object.entries(c.result)) {
				const row = element("tr");
				row.append(
					element("td", "font-monospace", k),
					element("td", "font-monospace text-break", formatValue(v)),
				);
				rows.append(row);
			}
			table.append(head, rows);
			section.append(table);
		}
		return section;
	}

	/* === Final state === */

	#finalState(state: SimulationState): HTMLElement {
		const section = element("section", "card mb-3 sim-final-state");
		const header = element(
			"header",
			"card-header d-flex align-items-center gap-2 py-1 px-2",
		);
		header.append(element("span", "fw-semibold small", "Final state"));
		const use = element(
			"button",
			"btn btn-sm btn-outline-secondary ms-auto",
			"Use as the starting state",
		);
		use.type = "button";
		use.dataset.report = "remove";
		use.title =
			"Copy the final operational modes, Local State, and control values into the inputs, to continue from here in the next run";
		use.addEventListener("click", () =>
			this.dispatchEvent(new Event("usefinalstate")),
		);
		header.append(use);

		const body = element("div", "card-body p-2 small");
		const modes = element("div", "d-flex flex-wrap gap-1 mb-2");
		modes.append(element("span", "fw-semibold me-1", "Operational modes"));
		if (state.opModes?.length) {
			for (const mode of state.opModes) {
				modes.append(element("span", "sim-chip", mode));
			}
		} else {
			modes.append(element("span", "text-body-secondary", "none active"));
		}
		body.append(modes);

		const localState = state.localState ?? [];
		body.append(element("div", "fw-semibold", "Local State"));
		if (localState.length) {
			const table = element(
				"table",
				"table table-sm mb-0 sim-samples-table",
			);
			const head = element("thead");
			const headRow = element("tr");
			headRow.append(
				element("th", undefined, "Key"),
				element("th", undefined, "Type"),
				element("th", undefined, "Value"),
			);
			head.append(headRow);
			const rows = element("tbody");
			for (const s of localState) {
				const row = element("tr");
				row.append(
					element("td", "font-monospace", s.key),
					element("td", "text-body-secondary", s.type),
					element(
						"td",
						"font-monospace text-break",
						formatValue(s.value),
					),
				);
				rows.append(row);
			}
			table.append(head, rows);
			body.append(table);
		} else {
			body.append(element("div", "text-body-secondary", "none"));
		}

		const metrics = state.metrics ?? [];
		if (metrics.length) {
			body.append(element("div", "fw-semibold mt-2", "Metrics"));
			const table = element(
				"table",
				"table table-sm mb-0 sim-samples-table",
			);
			const head = element("thead");
			const headRow = element("tr");
			headRow.append(
				element("th", undefined, "Date"),
				element("th", undefined, "Name"),
				element("th", undefined, "Value"),
			);
			head.append(headRow);
			const rows = element("tbody");
			for (const m of metrics) {
				const row = element("tr");
				row.title = `Metric type ${m.type}`;
				row.append(
					element("td", "text-nowrap", formatTimestamp(m.timestamp)),
					element("td", "font-monospace", m.name),
					element(
						"td",
						"font-monospace text-break",
						formatValue(m.value),
					),
				);
				rows.append(row);
			}
			table.append(head, rows);
			body.append(table);
		}
		const controls = state.controls ?? [];
		if (controls.length) {
			body.append(controlsTable(controls));
		}
		const instructions = state.instructions ?? [];
		if (instructions.length) {
			body.append(
				element(
					"div",
					"fw-semibold mt-2",
					"Instructions waiting for the Instruction Execution Job",
				),
				waitingInstructionsTable(instructions),
			);
		}
		section.append(header, body);
		return section;
	}
}

/** A table of the control values a step set. */
function controlUpdatesTable(updates: ControlUpdate[]): HTMLElement {
	const section = element("div", "mt-2");
	section.append(element("div", "fw-semibold", "Controls set"));
	const table = element("table", "table table-sm mb-1 sim-samples-table");
	const head = element("thead");
	const headRow = element("tr");
	headRow.append(
		element("th", undefined, "Control ID"),
		element("th", undefined, "Before"),
		element("th", undefined, "After"),
		element("th", undefined, "Datum"),
	);
	head.append(headRow);
	const rows = element("tbody");
	for (const c of updates) {
		const row = element("tr");
		row.title = `A ${c.type} control`;
		row.append(
			element("td", "font-monospace", c.controlId),
			element("td", "font-monospace text-break", c.previousValue ?? ""),
			element("td", "font-monospace text-break", c.value),
			element(
				"td",
				"text-body-secondary",
				c.published ? "published" : "not published",
			),
		);
		if (c.previousValue !== c.value) {
			row.classList.add("table-active");
		}
		rows.append(row);
	}
	table.append(head, rows);
	section.append(table);
	return section;
}

/**
 * Describe an instruction for its chip's tooltip: its status message,
 * parameters, and the instructions it issued.
 */
function instructionTitle(
	instr: InstructionInfo,
	instructions: readonly InstructionInfo[],
): string {
	const lines: string[] = [];
	if (instr.message) {
		lines.push(instr.message + (instr.code ? ` (${instr.code})` : ""));
	}
	for (const [k, v] of Object.entries(instr.parameters ?? {})) {
		lines.push(`${k} = ${v}`);
	}
	const children = instructions.filter((i) => i.parent === instr.number);
	if (children.length) {
		lines.push("Issued:");
		for (const c of children) {
			lines.push(
				`#${c.number} ${instructionLabel(c)} (${c.state ?? "not handled"}${c.executeAt ? `, due ${formatTimestamp(c.executeAt)}` : ""})`,
			);
		}
	}
	return lines.join("\n");
}
