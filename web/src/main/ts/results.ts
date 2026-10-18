import { filterIcon } from "./icons.ts";
import type {
	DatumInfo,
	DatumTrace,
	FilterStep,
	FilterStepStatus,
	MessageLevel,
	SamplesInfo,
	SimulationMessage,
	SimulationResult,
	SimulationState,
} from "./model.ts";
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
}

/** The sample property types, in display order. */
const SAMPLE_TYPES = ["i", "a", "s"] as const;

type SampleType = (typeof SAMPLE_TYPES)[number];

const TYPE_NAMES: Record<SampleType | "t", string> = {
	i: "Instantaneous",
	a: "Accumulating",
	s: "Status",
	t: "Tags",
};

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

/** A change to one datum property. */
interface PropertyChange {
	type: SampleType | "t";
	key: string;
	kind: "added" | "removed" | "changed";
	from?: unknown;
	to?: unknown;
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

/** Format a property value for display. */
function formatValue(value: unknown): string {
	if (value === null || value === undefined) {
		return "null";
	}
	if (typeof value === "object") {
		return JSON.stringify(value);
	}
	return String(value);
}

/** Format a timestamp like `2025-10-01 12:00:00Z`. */
function formatTimestamp(ts: string | undefined): string {
	return ts ? ts.replace("T", " ") : "";
}

function formatDuration(micros: number | undefined): string {
	if (micros === undefined) {
		return "";
	}
	return micros < 1000
		? `${micros} µs`
		: `${(micros / 1000).toFixed(micros < 10_000 ? 2 : 1)} ms`;
}

function sameValue(a: unknown, b: unknown): boolean {
	return a === b || JSON.stringify(a) === JSON.stringify(b);
}

/**
 * Get the property changes between two sets of samples.
 *
 * @param from the samples before
 * @param to the samples after
 * @returns the changes
 */
function diffSamples(from: SamplesInfo, to: SamplesInfo): PropertyChange[] {
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
 * - `close`, to close the panel.
 */
export class ResultsPanel extends EventTarget {
	readonly #el: HTMLElement;
	readonly #ws: Workspace;
	#result: SimulationResult | null = null;
	#selectedSeq: number | null = null;
	#stale?: HTMLElement;

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
		this.#el.replaceChildren(this.#header(result), body);
		this.#el.scrollTop = 0;
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
		result.classList.add("ms-auto");
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
		const toggle = () => this.#selectDatum(trace, section, header);
		header.addEventListener("click", toggle);
		header.addEventListener("keydown", (e) => {
			if (e.key === "Enter" || e.key === " ") {
				e.preventDefault();
				toggle();
			}
		});

		const body = element("div", "card-body p-2");
		body.append(this.#samplesLine("In", trace.input.samples));
		const steps = element("div", "sim-steps my-1");
		for (const step of trace.steps ?? []) {
			steps.append(this.#step(step));
		}
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
		const line = this.#samplesLine("Out", output.samples);
		if (output.timestamp) {
			line.title = `Timestamp: ${formatTimestamp(output.timestamp)}`;
		}
		return line;
	}

	/** A line of a datum's properties, grouped by type. */
	#samplesLine(label: string, samples: SamplesInfo): HTMLElement {
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
			line.append(
				element("span", "text-body-secondary", "no properties"),
			);
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
		if (section) {
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
		return body;
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
		section.append(header, body);
		return section;
	}
}
