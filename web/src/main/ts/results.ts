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
	DatumInfo,
	DatumTrace,
	FilterStep,
	FilterStepStatus,
	MessageLevel,
	SimulationMessage,
	SimulationResult,
	SimulationState,
} from "./model.ts";
import {
	commandLine,
	commandName,
	diffSamples,
	element,
	formatDuration,
	formatTimestamp,
	formatValue,
	SAMPLE_TYPES,
	samplesLine,
	sameValue,
	TYPE_NAMES,
	type SampleType,
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

const LEVEL_CLASSES: Record<MessageLevel, string> = {
	Info: "text-bg-secondary",
	Warning: "text-bg-warning",
	Error: "text-bg-danger",
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
	const path: DatumPath = { visited: new Set(), dropped: new Set() };
	for (const step of trace.steps ?? []) {
		path.visited.add(step.filterId);
		if (step.status === "Dropped") {
			path.dropped.add(step.filterId);
		}
	}
	return path;
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
		this.#result = result;
		this.#selectedSeq = null;
		const body = element("div", "sim-results-body p-3");
		if (result.messages.length) {
			body.append(this.#messages(result.messages));
		}
		if (!result.datum.length) {
			body.append(
				element("p", "text-body-secondary", "No datum were processed."),
			);
		}
		for (const trace of result.datum) {
			body.append(this.#datum(trace));
		}
		body.append(this.#finalState(result.finalState));
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
		const s = result.stats;
		const header = element(
			"header",
			"sim-results-header d-flex align-items-center gap-2 px-3 py-2 border-bottom bg-body-tertiary",
		);
		const title = element("h2", "h6 mb-0 text-nowrap", "Execution Flow");
		const parts = [
			`${s.processed} datum processed`,
			s.generated ? `${s.generated} generated` : "",
			`${s.output} output`,
			s.discarded ? `${s.discarded} discarded` : "",
			`${s.durationMs} ms`,
		].filter((p) => p);
		const summary = element(
			"span",
			"small text-body-secondary text-truncate",
			parts.join(" · "),
		);
		this.#stale = element(
			"span",
			"badge text-bg-warning text-nowrap",
			"Changed since this run",
		);
		this.#stale.title =
			"The filters or inputs changed after this run. Run again to see their effect.";
		this.#stale.hidden = true;
		const close = element("button", "btn-close ms-auto");
		close.type = "button";
		close.title = "Close the execution flow";
		close.setAttribute("aria-label", close.title);
		close.addEventListener("click", () =>
			this.dispatchEvent(new Event("close")),
		);
		header.append(title, summary, this.#stale, close);
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
		const close = element("button", "btn-close ms-auto");
		close.type = "button";
		close.title = "Close the execution flow";
		close.setAttribute("aria-label", close.title);
		close.addEventListener("click", () =>
			this.dispatchEvent(new Event("close")),
		);
		header.append(group, stop, label, this.#stale, close);
		return { el: header, label, buttons };
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

	#messages(messages: SimulationMessage[]): HTMLElement {
		const list = element(
			"div",
			"list-group list-group-flush small mb-3 border rounded",
		);
		for (const m of messages) {
			const target = m.filterId ?? m.datumSeq;
			const item = element(
				target !== undefined ? "button" : "div",
				"list-group-item d-flex gap-2 align-items-start" +
					(target !== undefined ? " list-group-item-action" : ""),
			);
			if (item instanceof HTMLButtonElement) {
				item.type = "button";
				item.title = m.filterId ? "Show the filter" : "Show the datum";
				item.addEventListener("click", () => {
					if (m.filterId) {
						this.#emitFilter(m.filterId);
					} else if (m.datumSeq !== undefined) {
						this.#scrollToDatum(m.datumSeq);
					}
				});
			}
			item.append(
				element(
					"span",
					`badge ${LEVEL_CLASSES[m.level] ?? "text-bg-secondary"}`,
					m.level,
				),
				element("span", "flex-grow-1", m.message),
			);
			list.append(item);
		}
		return list;
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
		section.dataset.trace = String(this.#result?.datum.indexOf(trace) ?? 0);

		const header = element(
			"header",
			"card-header d-flex flex-wrap align-items-center gap-2 py-1 px-2 sim-datum-header",
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
				`Generated by a scheduled task of ${this.#filterLabel(trace.origin.filterId)}`,
			);
		} else {
			origin.append(
				`Generated by ${this.#filterLabel(trace.origin.filterId)}`,
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
		header.append(
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
		header.setAttribute("aria-pressed", String(select));
		this.dispatchEvent(
			new CustomEvent<DatumPath | null>("datum", {
				detail: select ? datumPath(trace) : null,
			}),
		);
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
		if (section && !section.hidden) {
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
		for (const instr of step.instructions ?? []) {
			chip(
				`instruction: ${instr.topic}${instr.state ? ` (${instr.state})` : ""}`,
				"",
				Object.entries(instr.parameters ?? {})
					.map(([k, v]) => `${k} = ${v}`)
					.join("\n"),
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
			const note = text[step.status];
			if (note) {
				chip(note, "is-note");
			}
		}
		return chips;
	}

	#stepDetails(step: FilterStep): HTMLElement {
		const body = element("div", "sim-step-details small");
		body.append(
			element(
				"p",
				"text-body-secondary mb-1",
				STATUS_HELP[step.status] ?? "",
			),
		);
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
		return body;
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
		use.title =
			"Copy the final operational modes and Local State into the Environment inputs, to continue from here in the next run";
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
		section.append(header, body);
		return section;
	}
}
