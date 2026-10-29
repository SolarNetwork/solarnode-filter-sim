import {
	ApiError,
	getRunProgress,
	getRunTraces,
	MAX_TRACES_PER_REQUEST,
	runDatumUrl,
} from "./api.ts";
import {
	DatumList,
	INSTRUCTION_RUNS,
	itemOutcome,
	OUTCOME_CLASSES,
	type DatumOutcome,
	type ListActivation,
	type ListItem,
} from "./datumList.ts";
import {
	closestPosition,
	cursorBounds,
	debugPositions,
	debugState,
	DebugView,
	moveCursor,
	traceDebugState,
	type DebugMove,
	type DebugPosition,
} from "./debug.ts";
import { showToast } from "./dialogs.ts";
import { filterIcon } from "./icons.ts";
import type {
	CommandInfo,
	ControlUpdate,
	DatumIndexEntry,
	DatumInfo,
	DatumOrigin,
	DatumTrace,
	FilterStep,
	FilterStepStatus,
	InstructionInfo,
	InstructionRun,
	MessageLevel,
	RunProgress,
	RunResult,
	SimulationMessage,
	SimulationResult,
	SimulationState,
	SkipReason,
} from "./model.ts";
import {
	commandLine,
	commandName,
	controlsTable,
	datumSourceLabel,
	diffSamples,
	element,
	formatDuration,
	formatTimestamp,
	formatValue,
	getDisplayZone,
	handlerLabel,
	instructionLabel,
	expiredModeOriginText,
	instructionOriginText,
	instructionsList,
	LEVEL_CLASSES,
	messageItem,
	opModesText,
	polledOriginText,
	quoted,
	SAMPLE_TYPES,
	samplesLine,
	sameValue,
	setDisplayZone,
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

/** A result without every trace, shown as a datum list. */
type LargeResult = RunResult & { index: DatumIndexEntry[] };

/**
 * Test if a result has an index of its datum in place of every trace, so the
 * datum show as a list, and each trace loads when it is needed.
 *
 * @param result the result
 */
export function isLargeResult(result: SimulationResult): result is LargeResult {
	return Array.isArray((result as RunResult).index);
}

/** The view of a result without every trace. */
interface LargeView {
	result: LargeResult;
	index: DatumIndexEntry[];
	/** The index position of each datum, by sequence number. */
	positions: Map<number, number>;
	/** The number of datum output before each index position. */
	outputsBefore: Int32Array;
	/** The traces loaded so far, by sequence number. */
	traces: Map<number, DatumTrace>;
	list: DatumList;
	/** Where the selected datum's or instruction run's card shows. */
	detail: HTMLElement;
	finalState: HTMLElement;
}

/** The state of debug mode for a result without every trace. */
interface LargeDebugSession {
	/** The index position of the datum at the cursor. */
	index: number;
	stepIndex: number;
	/** The trace of the datum at the cursor, once loaded. */
	trace: DatumTrace | null;
	view: DebugView;
	label: HTMLElement;
	buttons: Map<DebugMove, HTMLButtonElement>;
}

/** The most loaded datum a report of a result without every trace has. */
const MAX_REPORT_DATUM = 200;

/**
 * The most datum a result can have for the canvas to show every route; over
 * this, it shows the selected datum's routes.
 */
export const MAX_ALL_ROUTES = 20;

/** The message when the server no longer keeps a run. */
const RUN_GONE =
	"The simulator no longer keeps this run, as it keeps only the last 3 runs. Run it again.";

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
	const counted = (result as RunResult).filterStats;
	if (counted) {
		// a result without every trace, counted by the server
		return new Map(Object.entries(counted));
	}
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
		s.polled ? `${s.polled} polled` : "",
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
 * - `stop`, to stop debug mode;
 * - `cancel`, to cancel the run in progress;
 * - `traces`, with a {@link DatumTrace} list detail, when the traces of a
 *   result without every trace load;
 * - `routeselection`, with a datum sequence number list detail, or `null`,
 *   with the datum whose routes the canvas shows, for a result over
 *   {@link MAX_ALL_ROUTES} datum, or `null` to show every route;
 * - `timezone`, when the time zone dates show in changes.
 *
 * Over {@link MAX_ALL_ROUTES} datum, a click on a datum selects it, or
 * unselects it, and the canvas shows the routes of the selected datum, plus
 * the datum list's current row while the arrow keys are on it.
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
	/** A result without every trace, shown as a datum list. */
	#large: LargeView | null = null;
	/** Debug mode of a result without every trace. */
	#ldebug: LargeDebugSession | null = null;
	/** The latest trace load, so earlier ones that end later are ignored. */
	#loadToken = 0;
	#loadTimer?: number;
	/** The progress of the run in progress, shown above the flow. */
	#progressEl: HTMLElement | null = null;
	/**
	 * The datum selected for their routes, over {@link MAX_ALL_ROUTES} datum,
	 * whatever the filters show.
	 */
	readonly #selected = new Set<number>();
	/** The datum list's current datum, while it is not selected. */
	#preview: number | null = null;
	/** The time zone of the run shown. */
	#zone = "UTC";
	/** `true` to show dates in UTC, not the run's time zone. */
	#utc = false;

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
			this.#selected.clear();
		}
		this.#preview = null;
		this.#result = result;
		this.#selectedSeq = null;
		this.#debug = null;
		this.#ldebug = null;
		this.#el.classList.remove("is-debug");
		if (isLargeResult(result)) {
			const body = this.#largeBody(result);
			this.#replace(this.#header(result), body);
		} else {
			this.#large = null;
			this.#replace(this.#header(result), this.#body(result));
		}
		this.#el.scrollTop = 0;
		this.#emitSelection();
	}

	/** Stop showing a result. */
	clear(): void {
		this.#selected.clear();
		this.#preview = null;
		this.#debug = null;
		this.#ldebug = null;
		this.#large = null;
		this.#result = null;
		this.#el.classList.remove("is-debug");
		this.#replace();
	}

	/** Replace the panel's content, keeping the progress of a run. */
	#replace(...children: HTMLElement[]): void {
		this.#el.replaceChildren(
			...(this.#progressEl ? [this.#progressEl] : []),
			...children,
		);
	}

	/** `true` while a run's progress shows. */
	get running(): boolean {
		return this.#progressEl !== null;
	}

	/**
	 * Show the progress of a run above the flow, with a Cancel button that
	 * emits a `cancel` event, or stop showing it.
	 *
	 * @param progress the progress, or `null` to stop showing it
	 */
	setProgress(progress: RunProgress | null): void {
		if (!progress) {
			this.#progressEl?.remove();
			this.#progressEl = null;
			return;
		}
		let el = this.#progressEl;
		if (!el) {
			el = element(
				"div",
				"sim-run-progress d-flex align-items-center gap-2 px-3 py-2 border-bottom bg-body-tertiary",
			);
			const spinner = element(
				"span",
				"spinner-border spinner-border-sm text-secondary",
			);
			spinner.setAttribute("aria-hidden", "true");
			const bar = element("div", "progress flex-grow-1");
			bar.setAttribute("role", "progressbar");
			bar.setAttribute("aria-label", "Simulation progress");
			bar.append(element("div", "progress-bar"));
			const cancel = element(
				"button",
				"btn btn-sm btn-outline-danger py-0",
				"Cancel",
			);
			cancel.type = "button";
			cancel.title =
				"Stop the run, and show the datum it processed so far";
			cancel.addEventListener("click", () => {
				cancel.disabled = true;
				cancel.textContent = "Cancelling…";
				this.dispatchEvent(new Event("cancel"));
			});
			el.append(
				spinner,
				element("span", "small fw-semibold text-nowrap", "Running"),
				bar,
				element(
					"span",
					"small text-body-secondary text-nowrap sim-run-progress-text",
				),
				cancel,
			);
			this.#progressEl = el;
			this.#el.prepend(el);
		}
		const { processed, expected } = progress;
		const known = expected > 0;
		const percent = known
			? Math.min(100, Math.round((processed / expected) * 100))
			: 100;
		const bar = el.querySelector<HTMLElement>(".progress")!;
		const fill = el.querySelector<HTMLElement>(".progress-bar")!;
		fill.style.width = `${percent}%`;
		fill.classList.toggle("progress-bar-striped", !known);
		fill.classList.toggle("progress-bar-animated", !known);
		bar.setAttribute("aria-valuenow", String(known ? percent : 0));
		bar.setAttribute("aria-valuemin", "0");
		bar.setAttribute("aria-valuemax", "100");
		el.querySelector(".sim-run-progress-text")!.textContent = [
			known
				? `${processed.toLocaleString()} of ${expected.toLocaleString()} datum`
				: `${processed.toLocaleString()} datum`,
			progress.date ? formatTimestamp(progress.date) : "",
		]
			.filter((p) => p)
			.join(" · ");
	}

	/** `true` in debug mode. */
	get debugging(): boolean {
		return this.#debug !== null || this.#ldebug !== null;
	}

	/** The debug cursor position, or `null` if not in debug mode. */
	get debugMark(): DebugMark | null {
		const ld = this.#ldebug;
		if (ld && this.#large) {
			return {
				seq: this.#large.index[ld.index].seq,
				stepIndex: ld.stepIndex,
			};
		}
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
		if (isLargeResult(result)) {
			void this.#showLargeDebug(result, mark);
			return;
		}
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
			this.#selected.clear();
		}
		this.#preview = null;
		this.#result = result;
		this.#selectedSeq = null;
		this.#large = null;
		this.#ldebug = null;
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
		this.#replace(header.el, layout);
		this.#emitSelection();
		this.#applyCursor();
	}

	/**
	 * Move the debug cursor.
	 *
	 * @param move how to move
	 */
	move(move: DebugMove): void {
		if (this.#ldebug) {
			void this.#largeMove(move);
			return;
		}
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
		body.append(this.#zoneNote());
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

	/* === Time zone === */

	/**
	 * Set the time zone of the run shown, which dates show in unless UTC is
	 * chosen.
	 *
	 * @param zone the time zone
	 */
	setTimeZone(zone: string): void {
		this.#zone = zone;
		this.#applyZone();
	}

	#applyZone(): void {
		setDisplayZone(this.#utc ? "UTC" : this.#zone);
	}

	/**
	 * Render the note that names the time zone dates show in, with a button
	 * that shows them in UTC, or in the run's time zone again.
	 */
	#zoneNote(): HTMLElement {
		const note = element(
			"div",
			"d-flex flex-wrap align-items-center gap-2 small text-body-secondary mb-2 sim-zone-note",
		);
		const icon = element("i", "bi bi-globe2");
		icon.setAttribute("aria-hidden", "true");
		const text = element("span");
		text.append(
			"All dates are shown in the ",
			element("strong", undefined, getDisplayZone()),
			" time zone.",
		);
		note.append(icon, text);
		if (this.#zone !== "UTC") {
			const toggle = element(
				"button",
				"btn btn-sm btn-outline-secondary py-0",
				this.#utc ? `Show in ${this.#zone}` : "Show in UTC",
			);
			toggle.type = "button";
			toggle.dataset.report = "remove";
			toggle.title = this.#utc
				? `Show dates in the simulation's time zone, ${this.#zone}`
				: "Show dates in UTC";
			toggle.addEventListener("click", () => {
				this.#utc = !this.#utc;
				this.#applyZone();
				this.#refreshDates();
				this.dispatchEvent(new Event("timezone"));
			});
			note.append(toggle);
		}
		return note;
	}

	/** Render the result again, with dates in the time zone now chosen. */
	#refreshDates(): void {
		const result = this.#result;
		if (!result) {
			return;
		}
		if (this.debugging) {
			this.showDebug(result, this.debugMark);
			return;
		}
		const large = this.#large;
		if (large) {
			this.#el
				.querySelectorAll(".sim-zone-note")
				.forEach((el) => el.replaceWith(this.#zoneNote()));
			large.list.refreshDates();
			const item = large.list.current;
			const trace =
				item?.kind === "datum"
					? large.traces.get(item.entry.seq)
					: undefined;
			if (trace) {
				this.#showLargeDatum(trace);
			} else if (item?.kind === "run") {
				this.#showLargeRun(item.run);
			}
			const finalState = this.#finalState(result.finalState);
			large.finalState.replaceWith(finalState);
			large.finalState = finalState;
			return;
		}
		// render the cards again, keeping what is open and the scroll position
		const body = this.#el.querySelector(".sim-results-body");
		if (!body) {
			return;
		}
		const scrollTop = this.#el.scrollTop;
		const open = [...body.querySelectorAll("details")].map((d) => d.open);
		const collapsed = new Set(
			[
				...body.querySelectorAll<HTMLElement>(
					".sim-datum.is-collapsed",
				),
			].map((el) => el.dataset.seq),
		);
		const fresh = this.#body(result);
		fresh
			.querySelectorAll<HTMLElement>(".sim-datum")
			.forEach((el) =>
				this.#setCollapsed(el, collapsed.has(el.dataset.seq)),
			);
		fresh.querySelectorAll("details").forEach((d, i) => {
			if (open[i] !== undefined) {
				d.open = open[i];
			}
		});
		body.replaceWith(fresh);
		this.#refreshSelection();
		const selected = fresh.querySelector(`#sim-datum-${this.#selectedSeq}`);
		if (selected && !this.#selecting) {
			selected.classList.add("is-selected");
			selected
				.querySelector(".sim-datum-header")
				?.setAttribute("aria-pressed", "true");
		}
		this.#el.scrollTop = scrollTop;
	}

	/* === Datum selection === */

	/**
	 * `true` if a click on a datum selects it for its route: over
	 * {@link MAX_ALL_ROUTES} datum, outside of debug mode.
	 */
	get #selecting(): boolean {
		const result = this.#result;
		if (!result || this.debugging) {
			return false;
		}
		const count = isLargeResult(result)
			? result.index.length
			: result.datum.length;
		return count > MAX_ALL_ROUTES;
	}

	/**
	 * Emit a `routeselection` event with the datum whose routes to show, and
	 * show which datum are selected.
	 */
	#emitSelection(): void {
		this.#refreshSelection();
		let seqs: number[] | null = null;
		if (this.#selecting) {
			seqs = [...this.#selected];
			const preview = this.#preview;
			if (preview !== null && !this.#selected.has(preview)) {
				seqs.push(preview);
			}
		}
		this.dispatchEvent(
			new CustomEvent<number[] | null>("routeselection", {
				detail: seqs,
			}),
		);
	}

	/**
	 * Show which datum are selected: on their cards, in the datum list, and
	 * in the selection counts.
	 */
	#refreshSelection(): void {
		const selecting = this.#selecting;
		if (selecting) {
			this.#el
				.querySelectorAll<HTMLElement>(".sim-datum")
				.forEach((section) => {
					const selected = this.#selected.has(
						Number(section.dataset.seq),
					);
					section.classList.toggle("is-selected", selected);
					section
						.querySelector(".sim-datum-header")
						?.setAttribute("aria-pressed", String(selected));
				});
		}
		this.#large?.list.refresh();
		const count = this.#selected.size;
		this.#el
			.querySelectorAll<HTMLElement>(".sim-selection-info")
			.forEach((info) => {
				info.hidden = !selecting;
				info.querySelector(".sim-selection-count")!.textContent = count
					? `${count.toLocaleString()} selected ·`
					: "Select datum to show their routes on the canvas.";
				info.querySelector<HTMLElement>(
					".sim-selection-clear",
				)!.hidden = !count;
			});
	}

	/** Render the selection count, with a button that clears the selection. */
	#selectionInfo(): HTMLElement {
		const info = element(
			"span",
			"small text-body-secondary text-nowrap sim-selection-info",
		);
		info.dataset.report = "remove";
		const clear = element(
			"button",
			"btn btn-link btn-sm p-0 align-baseline sim-selection-clear",
			"Clear selection",
		);
		clear.type = "button";
		clear.title =
			"Unselect every selected datum, including any the filters hide";
		clear.addEventListener("click", () => this.#clearSelection());
		info.append(element("span", "sim-selection-count"), " ", clear);
		return info;
	}

	/** Unselect every selected datum. */
	#clearSelection(): void {
		this.#selected.clear();
		this.#preview = null;
		this.#setPath(null);
		this.#emitSelection();
	}

	/**
	 * Select a datum for its route, or unselect it.
	 *
	 * @param seq the datum sequence number
	 * @param trace the datum's trace, if it is loaded, to highlight the
	 *     filters it went through once it is selected
	 */
	#toggleSelected(seq: number, trace: DatumTrace | undefined): void {
		const select = !this.#selected.has(seq);
		if (select) {
			this.#selected.add(seq);
			const section = this.#el.querySelector<HTMLElement>(
				`#sim-datum-${seq}`,
			);
			if (section) {
				this.#setCollapsed(section, false);
			}
			if (trace) {
				this.#setPath(trace);
			}
		} else {
			this.#selected.delete(seq);
			if (this.#selectedSeq === seq) {
				this.#setPath(null);
			}
		}
		this.#preview = null;
		this.#emitSelection();
	}

	/**
	 * Highlight the filters a datum went through, over
	 * {@link MAX_ALL_ROUTES} datum.
	 *
	 * @param trace the datum's trace, or `null` for none
	 */
	#setPath(trace: DatumTrace | null): void {
		const seq = trace?.seq ?? null;
		if (seq === this.#selectedSeq && seq === null) {
			return;
		}
		this.#selectedSeq = seq;
		this.dispatchEvent(
			new CustomEvent<DatumPath | null>("datum", {
				detail: trace ? datumPath(trace) : null,
			}),
		);
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
			...this.#tools(result),
			close,
		);
		return header;
	}

	/**
	 * Get the header's tools: the buttons that expand and collapse every
	 * datum, the download menu, and the report button, the first of them
	 * with the `ms-auto` class.
	 */
	#tools(result: SimulationResult): HTMLElement[] {
		const tools = [
			...this.#collapseAllButtons(result),
			...("id" in result
				? [this.#downloadMenu(result as RunResult)]
				: []),
			this.#reportButton(),
		];
		tools[0].classList.add("ms-auto");
		return tools;
	}

	/** Get the menu that downloads a run's datum. */
	#downloadMenu(result: RunResult): HTMLElement {
		const menu = element("div", "dropdown sim-download-menu");
		menu.dataset.report = "remove";
		const button = element(
			"button",
			"btn btn-sm btn-link text-body-secondary p-0 sim-tool",
		);
		button.type = "button";
		button.title = "Download the run's datum as JSON";
		button.setAttribute("aria-label", button.title);
		button.setAttribute("aria-expanded", "false");
		button.dataset.bsToggle = "dropdown";
		const glyph = element("i", "bi bi-download");
		glyph.setAttribute("aria-hidden", "true");
		button.append(glyph);
		const list = element("ul", "dropdown-menu dropdown-menu-end");
		for (const [kind, text, help] of [
			[
				"output",
				"Output datum",
				"The datum the filters output, which a node persists",
			],
			[
				"input",
				"Input datum",
				"Each datum as it entered the Datum Queue, before the filters",
			],
		] as const) {
			const item = element("button", "dropdown-item");
			item.type = "button";
			item.append(
				element("div", undefined, text),
				element("div", "small text-body-secondary", help),
			);
			item.addEventListener(
				"click",
				() => void this.#download(result.id, kind),
			);
			const li = element("li");
			li.append(item);
			list.append(li);
		}
		const note = element(
			"li",
			"px-3 pt-1 small text-body-secondary sim-download-note",
			"As JSON, like the Datum input.",
		);
		list.append(note);
		menu.append(button, list);
		return menu;
	}

	/**
	 * Download a run's datum, if the server still keeps the run.
	 *
	 * @param id the run ID
	 * @param kind the datum to download
	 */
	async #download(id: string, kind: "output" | "input"): Promise<void> {
		try {
			await getRunProgress(id);
		} catch (e) {
			showToast(
				e instanceof ApiError && e.status === 404
					? RUN_GONE
					: `The datum were not downloaded: ${e instanceof Error ? e.message : e}`,
			);
			return;
		}
		// the server names the file
		const a = document.createElement("a");
		a.href = runDatumUrl(id, kind);
		a.download = "";
		a.click();
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
			...(this.#result ? this.#tools(this.#result) : []),
			close,
		);
		return { el: header, label, buttons };
	}

	/**
	 * Get the buttons that expand and collapse every datum, if there are
	 * several datum cards.
	 */
	#collapseAllButtons(result: SimulationResult): HTMLElement[] {
		if (result.datum.length < 2 || isLargeResult(result)) {
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
	 * `report` event.
	 */
	#reportButton(): HTMLButtonElement {
		const b = element(
			"button",
			"btn btn-sm btn-link text-body-secondary p-0 sim-tool",
		);
		b.type = "button";
		b.title = "Download the execution flow as an HTML report";
		b.setAttribute("aria-label", b.title);
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
		const large = this.#large;
		if (large) {
			// the datum loaded, as a report cannot load others
			const loaded = [...large.traces.values()]
				.sort((a, b) => a.seq - b.seq)
				.slice(0, MAX_REPORT_DATUM);
			const seqs = new Set(loaded.map((t) => t.seq));
			const runs = (result.instructionRuns ?? []).filter(
				(r) => r.afterSeq === undefined || seqs.has(r.afterSeq),
			);
			const body = this.#body({
				...result,
				datum: loaded,
				instructionRuns: runs,
			});
			body.prepend(
				element(
					"div",
					"alert alert-info small py-1 px-2 mb-3",
					`The run processed ${large.index.length.toLocaleString()} datum. This report has the ${loaded.length} that were loaded when it was saved${loaded.length === MAX_REPORT_DATUM ? `, up to ${MAX_REPORT_DATUM}` : ""}${runs.length ? ", with the instruction runs after them" : ""}.`,
				),
			);
			return {
				summary: summaryText(result),
				body,
				collapsible: loaded.length > 1,
			};
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
			} else if (m.datumSourceId) {
				const id = m.datumSourceId;
				link = {
					title: "Show the datum source",
					show: () =>
						this.dispatchEvent(
							new CustomEvent<string>("datumsource", {
								detail: id,
							}),
						),
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
		bar.append(
			element("span", "fw-semibold small", "Datum"),
			this.#selectionInfo(),
			pills,
		);
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
		header.title = this.#selecting
			? "Select this datum to show its route on the canvas, or unselect it"
			: "Highlight the filters this datum went through";
		const origin = element("span", "small text-body-secondary");
		origin.append(this.#originText(trace.origin));
		if (trace.origin.type === "Instruction") {
			const run = this.#result?.instructionRuns?.find(
				(r) => r.instruction.number === trace.origin.instruction,
			);
			if (run) {
				origin.title = this.#large
					? "The instruction run is before this datum in the list"
					: "The instruction run is above this datum";
			}
		} else if (
			trace.origin.type === "Generated" &&
			trace.origin.parentSeq !== undefined
		) {
			origin.append(" from ", this.#datumLink(trace.origin.parentSeq));
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
			const large = this.#large;
			if (this.#ldebug && large) {
				const index = large.positions.get(trace.seq);
				if (index !== undefined) {
					void this.#largeGoTo(index, "first");
				}
				return;
			}
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
				const large = this.#large;
				if (this.#ldebug && large) {
					e.preventDefault();
					const index = large.positions.get(trace.seq);
					if (index !== undefined) {
						void this.#largeGoTo(index, i);
					}
					return;
				}
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
		if (this.#selecting) {
			this.#toggleSelected(trace.seq, trace);
			return;
		}
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
		if (this.#selecting) {
			this.#setPath(null);
			if (this.#preview !== null) {
				this.#preview = null;
				this.#emitSelection();
			}
			return;
		}
		const seq = this.#selectedSeq;
		const trace = seq === null ? undefined : this.#traceOf(seq);
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
		if (this.#selecting) {
			// the datum is selected, as its route shows: show it
			const trace = this.#traceOf(seq);
			if (trace && !this.#large) {
				this.#setPath(trace);
			}
			this.#scrollToDatum(seq);
			return;
		}
		if (this.#large) {
			if (!this.#ldebug && this.#selectedSeq === seq) {
				this.clearDatum();
			} else {
				this.#scrollToDatum(seq);
			}
			return;
		}
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
		const large = this.#large;
		if (large) {
			if (this.#ldebug) {
				const index = large.positions.get(seq);
				if (index !== undefined) {
					void this.#largeGoTo(index, "first");
				}
			} else {
				large.list.showDatum(seq);
			}
			large.list.el.scrollIntoView({ block: "nearest" });
			return;
		}
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

	/** Get a datum's trace, if it is loaded. */
	#traceOf(seq: number): DatumTrace | undefined {
		return (
			this.#large?.traces.get(seq) ??
			this.#result?.datum.find((t) => t.seq === seq)
		);
	}

	/**
	 * Describe where a datum came from, like "Input 1" or "Generated by
	 * "Split"".
	 */
	#originText(o: DatumOrigin): string {
		switch (o.type) {
			case "Input":
				return `Input ${(o.index ?? 0) + 1}`;
			case "Polled":
				return polledOriginText(o);
			case "Scheduled":
				return o.controlId && !o.filterId
					? expiredModeOriginText(o.controlId)
					: `Generated by a scheduled task of ${quoted(this.#filterLabel(o.filterId))}`;
			case "Instruction":
				return instructionOriginText(o, this.#result?.instructionRuns);
			default:
				return o.controlId
					? `Published by control ${o.controlId}, set by ${quoted(this.#filterLabel(o.filterId))}`
					: `Generated by ${quoted(this.#filterLabel(o.filterId))}`;
		}
	}

	/* === Results without every trace === */

	/**
	 * Render the flow of a result without every trace: the messages, the
	 * datum list, the selected datum's card, and the final state.
	 */
	#largeBody(result: LargeResult): HTMLElement {
		const body = element("div", "sim-results-body p-3");
		body.append(this.#zoneNote());
		if (result.messages.length) {
			body.append(this.#messages(result.messages));
		}
		const index = result.index;
		const traces =
			this.#large?.result === result
				? this.#large.traces
				: new Map(result.datum.map((t) => [t.seq, t]));
		const positions = new Map(index.map((e, i) => [e.seq, i]));
		const outputsBefore = new Int32Array(index.length);
		let outputs = 0;
		index.forEach((e, i) => {
			outputsBefore[i] = outputs;
			if (e.outcome === "Output" || e.outcome === "OutputNotPersisted") {
				outputs++;
			}
		});
		// each instruction run goes after the datum processed before it
		const runsAfter = new Map<number | undefined, ListItem[]>();
		(result.instructionRuns ?? []).forEach((run, i) => {
			const list = runsAfter.get(run.afterSeq) ?? [];
			list.push({ kind: "run", run, index: i });
			runsAfter.set(run.afterSeq, list);
		});
		const items: ListItem[] = [...(runsAfter.get(undefined) ?? [])];
		for (const entry of index) {
			items.push(
				{ kind: "datum", entry },
				...(runsAfter.get(entry.seq) ?? []),
			);
		}
		const list = new DatumList(items, {
			hiddenOutcomes: this.#hiddenOutcomes,
			renderRow: (item) => this.#listRow(item),
			isSelected: (item) =>
				item.kind === "datum" && this.#selected.has(item.entry.seq),
			info: this.#selectionInfo(),
		});
		list.addEventListener("activate", (e) =>
			this.#listActivated((e as CustomEvent<ListActivation>).detail),
		);
		const detail = element("div", "sim-datum-detail");
		detail.append(
			element(
				"p",
				"small text-body-secondary",
				"Click a datum to see how it went through the filters, and select it to show its route on the canvas. The arrow keys show each datum's route as they reach it, and Space selects it.",
			),
		);
		const finalState = this.#finalState(result.finalState);
		this.#large = {
			result,
			index,
			positions,
			outputsBefore,
			traces,
			list,
			detail,
			finalState,
		};
		body.append(list.el, detail, finalState);
		return body;
	}

	/** Render a row of the datum list. */
	#listRow(item: ListItem): HTMLElement {
		const outcome = itemOutcome(item);
		if (item.kind === "run") {
			const run = item.run;
			const row = element("div", "sim-datum-row is-run");
			const icon = element("i", "bi bi-clock-history text-info-emphasis");
			icon.setAttribute("aria-hidden", "true");
			const state = run.error
				? "Error"
				: (run.instruction.state ?? "not handled");
			row.append(
				icon,
				element(
					"span",
					"font-monospace text-body-secondary text-nowrap",
					formatTimestamp(run.date),
				),
				element(
					"span",
					"text-truncate",
					`Instruction run: ${instructionLabel(run.instruction)}`,
				),
				element("span", `badge ${OUTCOME_CLASSES[outcome]}`, state),
			);
			row.title = `Instruction run at ${formatTimestamp(run.date)}: ${instructionLabel(run.instruction)}, ${state}`;
			return row;
		}
		const e = item.entry;
		const row = element("div", "sim-datum-row");
		let origin = this.#originText(e.origin);
		if (e.origin.type === "Generated" && e.origin.parentSeq !== undefined) {
			origin += ` from #${e.origin.parentSeq}`;
		}
		const badge = element(
			"span",
			`badge ${OUTCOME_CLASSES[outcome]}`,
			outcome,
		);
		row.append(
			element("span", "font-monospace text-body-secondary", `#${e.seq}`),
			element("span", "fw-semibold text-truncate", e.sourceId ?? ""),
			element(
				"span",
				"font-monospace text-body-secondary text-nowrap",
				formatTimestamp(e.created ?? undefined),
			),
			element("span", "text-body-secondary text-truncate", origin),
			badge,
		);
		if (e.error && e.outcome !== "Error") {
			const icon = element(
				"i",
				"bi bi-exclamation-triangle-fill text-danger sim-datum-row-error",
			);
			icon.setAttribute("aria-hidden", "true");
			badge.prepend(icon, " ");
		}
		row.title = `#${e.seq} ${e.sourceId ?? ""}: ${origin}, ${outcome}${e.error ? ", with a filter error" : ""}`;
		return row;
	}

	/**
	 * Show the datum list's current datum or instruction run, and select or
	 * unselect a datum that was clicked.
	 */
	#listActivated({ item, toggle }: ListActivation): void {
		const large = this.#large;
		if (!large) {
			return;
		}
		if (this.#ldebug) {
			// move the cursor to the datum, or the datum after the run, as its
			// changes show in that datum's state
			let index: number;
			if (item.kind === "datum") {
				index = large.positions.get(item.entry.seq) ?? 0;
			} else {
				const after = item.run.afterSeq;
				index =
					after === undefined
						? 0
						: Math.min(
								large.index.length - 1,
								(large.positions.get(after) ?? -1) + 1,
							);
			}
			void this.#largeGoTo(index, "first");
			return;
		}
		window.clearTimeout(this.#loadTimer);
		const token = ++this.#loadToken;
		if (item.kind === "run") {
			this.#showLargeRun(item.run);
			this.#setPath(null);
			if (this.#preview !== null) {
				this.#preview = null;
				this.#emitSelection();
			}
			return;
		}
		const seq = item.entry.seq;
		if (toggle) {
			this.#toggleSelected(seq, large.traces.get(seq));
		} else {
			// the route shows while the datum is the current row
			this.#preview = this.#selected.has(seq) ? null : seq;
			this.#emitSelection();
		}
		const loaded = large.traces.get(seq);
		if (loaded) {
			this.#showLargeDatum(loaded);
			return;
		}
		const loading = element("p", "small text-body-secondary");
		const spinner = element(
			"span",
			"spinner-border spinner-border-sm me-2",
		);
		spinner.setAttribute("aria-hidden", "true");
		loading.append(spinner, `Loading datum #${seq}…`);
		large.detail.replaceChildren(loading);
		// a short wait, so moving through the list with the keyboard loads
		// only the datum it stops at
		this.#loadTimer = window.setTimeout(async () => {
			const trace = (await this.#loadTraces([seq])).get(seq);
			if (token !== this.#loadToken || this.#large !== large) {
				return;
			}
			if (trace) {
				this.#showLargeDatum(trace);
			} else {
				large.detail.replaceChildren(
					element(
						"p",
						"small text-danger",
						`Datum #${seq} could not be loaded.`,
					),
				);
			}
		}, 150);
	}

	/** Show an instruction run's card below the datum list. */
	#showLargeRun(run: InstructionRun): void {
		const card = this.#run(run);
		card.querySelector("details")!.open = true;
		this.#large?.detail.replaceChildren(card);
	}

	/**
	 * Show a loaded datum's card below the datum list, and highlight the
	 * filters it went through while its route shows.
	 */
	#showLargeDatum(trace: DatumTrace): void {
		const large = this.#large;
		if (!large) {
			return;
		}
		const section = this.#datum(trace);
		large.detail.replaceChildren(section);
		this.#applyStepFilters(section);
		this.#refreshSelection();
		const shown =
			this.#selected.has(trace.seq) || this.#preview === trace.seq;
		this.#setPath(shown ? trace : null);
	}

	/**
	 * Load the traces of datum of a result without every trace, and emit a
	 * `traces` event with the ones that were not loaded before.
	 *
	 * @param seqs the datum sequence numbers
	 * @returns the loaded traces, by sequence number
	 */
	async #loadTraces(
		seqs: readonly number[],
	): Promise<Map<number, DatumTrace>> {
		const large = this.#large;
		if (!large) {
			return new Map();
		}
		const missing = [...new Set(seqs)].filter((s) => !large.traces.has(s));
		const added: DatumTrace[] = [];
		try {
			for (let i = 0; i < missing.length; i += MAX_TRACES_PER_REQUEST) {
				const traces = await getRunTraces(
					large.result.id,
					missing.slice(i, i + MAX_TRACES_PER_REQUEST),
				);
				for (const t of traces) {
					large.traces.set(t.seq, t);
					added.push(t);
				}
			}
		} catch (e) {
			showToast(
				e instanceof ApiError && e.status === 404
					? RUN_GONE
					: `The datum could not be loaded: ${e instanceof Error ? e.message : e}`,
			);
		}
		if (added.length && this.#large === large) {
			this.dispatchEvent(
				new CustomEvent<DatumTrace[]>("traces", { detail: added }),
			);
		}
		return large.traces;
	}

	/**
	 * Show a result without every trace in debug mode, which loads each
	 * datum's trace as the cursor reaches it.
	 *
	 * @param result the result, with debug detail
	 * @param mark the position to start at, or `null` for the first filter
	 */
	async #showLargeDebug(
		result: LargeResult,
		mark: DebugMark | null,
	): Promise<void> {
		if (result !== this.#result) {
			this.#hiddenStatuses.clear();
			this.#selected.clear();
		}
		this.#preview = null;
		this.#result = result;
		this.#selectedSeq = null;
		this.#debug = null;
		this.#ldebug = null;
		const header = this.#debugHeader();
		const stateEl = element("div", "sim-debug-state border-start");
		const body = this.#largeBody(result);
		const large = this.#large!;
		large.finalState.hidden = true;
		this.#ldebug = {
			index: 0,
			stepIndex: -1,
			trace: null,
			view: new DebugView(stateEl),
			label: header.label,
			buttons: header.buttons,
		};
		const layout = element("div", "sim-debug");
		layout.append(body, stateEl);
		this.#el.classList.add("is-debug");
		this.#replace(header.el, layout);
		this.#emitSelection();
		let index = 0;
		let step: number | "first" | "last" = "first";
		if (mark) {
			const at = large.positions.get(mark.seq);
			if (at === undefined) {
				index = large.index.length - 1;
				step = "last";
			} else {
				index = at;
				step = mark.stepIndex;
			}
		}
		await this.#largeGoTo(index, step);
	}

	/**
	 * Move the debug cursor of a result without every trace, loading the
	 * datum's trace first.
	 *
	 * @param index the datum's index position
	 * @param step the step index, or its first or last step
	 */
	async #largeGoTo(
		index: number,
		step: number | "first" | "last",
	): Promise<void> {
		const large = this.#large;
		const d = this.#ldebug;
		if (!large || !d) {
			return;
		}
		const token = ++this.#loadToken;
		const seq = large.index[index].seq;
		const trace = (await this.#loadTraces([seq])).get(seq);
		if (token !== this.#loadToken || this.#ldebug !== d || !trace) {
			return;
		}
		const count = trace.steps?.length ?? 0;
		d.index = index;
		d.trace = trace;
		d.stepIndex =
			count === 0
				? -1
				: step === "first"
					? 0
					: step === "last"
						? count - 1
						: Math.max(0, Math.min(count - 1, step));
		this.#applyLargeCursor();
	}

	async #largeMove(move: DebugMove): Promise<void> {
		const large = this.#large;
		const d = this.#ldebug;
		if (!large || !d?.trace) {
			return;
		}
		const count = d.trace.steps?.length ?? 0;
		const last = large.index.length - 1;
		switch (move) {
			case "restart":
				return this.#largeGoTo(0, "first");
			case "end":
				return this.#largeGoTo(last, "last");
			case "nextDatum":
				return d.index < last
					? this.#largeGoTo(d.index + 1, "first")
					: this.#largeGoTo(last, "last");
			case "continue":
				if (d.stepIndex < count - 1) {
					return this.#largeGoTo(d.index, d.stepIndex + 1);
				}
				if (d.index < last) {
					return this.#largeGoTo(d.index + 1, "first");
				}
				return;
			case "back":
				if (d.stepIndex > 0) {
					return this.#largeGoTo(d.index, d.stepIndex - 1);
				}
				if (d.index > 0) {
					return this.#largeGoTo(d.index - 1, "last");
				}
		}
	}

	/**
	 * Show the debug cursor of a result without every trace: its datum's card
	 * up to it, and the state after it.
	 */
	#applyLargeCursor(): void {
		const large = this.#large;
		const d = this.#ldebug;
		const trace = d?.trace;
		if (!large || !d || !trace) {
			return;
		}
		large.list.showDatum(trace.seq, false);
		const section = this.#datum(trace);
		section.classList.add("is-current");
		let current: HTMLElement | null = null;
		section.querySelectorAll<HTMLElement>(".sim-step").forEach((stepEl) => {
			const si = Number(stepEl.dataset.step);
			stepEl.classList.toggle("is-pending", si > d.stepIndex);
			const isCurrent = si === d.stepIndex;
			stepEl.classList.toggle("is-current", isCurrent);
			if (isCurrent) {
				current = stepEl;
			}
		});
		const count = trace.steps?.length ?? 0;
		const atEnd = d.stepIndex >= count - 1;
		section
			.querySelectorAll<HTMLElement>(
				".sim-output-line, .sim-datum-result",
			)
			.forEach((el) => el.classList.toggle("is-pending", !atEnd));
		large.detail.replaceChildren(section);
		this.#applyStepFilters(section);
		const first = d.index === 0 && d.stepIndex <= 0;
		const last = d.index === large.index.length - 1 && atEnd;
		large.finalState.hidden = !last;
		(current ?? section).scrollIntoView({ block: "nearest" });

		const step = d.stepIndex >= 0 ? trace.steps?.[d.stepIndex] : undefined;
		d.label.textContent =
			`Datum #${trace.seq} (${(d.index + 1).toLocaleString()} of ${large.index.length.toLocaleString()})` +
			(step
				? ` · filter ${d.stepIndex + 1} of ${count}: ${this.#filterLabel(step.filterId, step.uid)}`
				: " · no filters");
		d.buttons.get("restart")!.disabled = first;
		d.buttons.get("back")!.disabled = first;
		d.buttons.get("continue")!.disabled = last;
		d.buttons.get("nextDatum")!.disabled =
			d.index >= large.index.length - 1;
		d.buttons.get("end")!.disabled = last;

		// the loaded traces, for the datum the state lists
		const loaded: SimulationResult = {
			...large.result,
			datum: [...large.traces.values()].sort((a, b) => a.seq - b.seq),
		};
		d.view.show(
			traceDebugState(trace, d.stepIndex, large.outputsBefore[d.index]),
			loaded,
			(id) => this.#filterLabel(id),
		);

		const path: DatumPath = {
			visited: new Set(),
			dropped: new Set(),
			current: step?.filterId,
			seq: trace.seq,
			upTo: d.stepIndex,
		};
		for (const s of (trace.steps ?? []).slice(0, d.stepIndex + 1)) {
			path.visited.add(s.filterId);
			if (s.status === "Dropped") {
				path.dropped.add(s.filterId);
			}
		}
		this.dispatchEvent(
			new CustomEvent<DatumPath | null>("datum", { detail: path }),
		);
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
			chip(
				`modes: ${opModesText(step.opModes)}`,
				"",
				"Operational modes changed",
			);
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
			chip(controlUpdateText(c), "", controlUpdateTitle(c));
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
		if (run.opModes) {
			const el = element(
				"span",
				"sim-chip",
				`modes: ${opModesText(run.opModes)}`,
			);
			el.title = "Operational modes changed";
			chips.append(el);
		}
		for (const c of run.controls ?? []) {
			const el = element("span", "sim-chip", controlUpdateText(c));
			el.title = controlUpdateTitle(c);
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
/**
 * Describe a control update for its chip, like `control: /ctl/1 0 → 5`.
 */
function controlUpdateText(c: ControlUpdate): string {
	const changed = c.previousValue !== c.value;
	const note =
		c.mode !== undefined
			? " (mode)"
			: c.requested !== undefined
				? ` (asked ${c.requested})`
				: changed
					? ""
					: " (same)";
	return `control: ${c.controlId} ${changed && c.previousValue !== undefined ? `${c.previousValue} → ` : "= "}${c.value}${note}`;
}

/** Describe a control update for its chip's tooltip. */
function controlUpdateTitle(c: ControlUpdate): string {
	const published = c.published ? ", which published a datum" : "";
	if (c.mode !== undefined) {
		return `Operational Mode Switch ${c.controlId} changed with its mode ${c.mode}${published}`;
	}
	const changed = c.previousValue !== c.value;
	const of = c.component ? ` of ${datumSourceLabel(c.component)}` : "";
	const asked =
		c.requested !== undefined
			? `, to ${c.value} when asked for ${c.requested}`
			: "";
	return `Control ${c.controlId} (${c.type})${of} set${changed ? "" : " to the same value"}${asked}${published}`;
}

function controlUpdatesTable(updates: ControlUpdate[]): HTMLElement {
	const section = element("div", "mt-2");
	section.append(
		element(
			"div",
			"fw-semibold",
			updates.some((c) => c.mode === undefined)
				? "Controls set"
				: "Controls changed",
		),
	);
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
		const after = element("td", "font-monospace text-break", c.value);
		if (c.mode !== undefined) {
			row.title = `An Operational Mode Switch, changed with its mode ${c.mode}, not set`;
			after.append(
				element("span", "text-body-secondary", ` (mode ${c.mode})`),
			);
		} else {
			row.title = c.component
				? `A ${c.type} control of ${datumSourceLabel(c.component)}`
				: `A ${c.type} control`;
		}
		if (c.requested !== undefined) {
			after.append(
				element(
					"span",
					"text-body-secondary",
					` (asked ${c.requested})`,
				),
			);
		}
		row.append(
			element("td", "font-monospace", c.controlId),
			element("td", "font-monospace text-break", c.previousValue ?? ""),
			after,
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
	if (instr.handler) {
		lines.push(`Handled by ${handlerLabel(instr.handler)}`);
	}
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
