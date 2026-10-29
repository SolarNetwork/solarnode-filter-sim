import type {
	DatumIndexEntry,
	DatumOutcomeCode,
	InstructionRun,
} from "./model.ts";
import { formatRangeDate, parseLocalDate } from "./dates.ts";
import { element, formatTimestamp, getDisplayZone } from "./view.ts";

/**
 * How a datum came out of the filters, which the datum filter pills group by,
 * or an instruction run, which has a pill of its own.
 */
export type DatumOutcome =
	| "Output"
	| "Output, not persisted"
	| "Discarded"
	| "Error"
	| typeof INSTRUCTION_RUNS;

/** The datum filter pill of the instruction runs. */
export const INSTRUCTION_RUNS = "Instruction runs";

/** The badge class of each datum outcome. */
export const OUTCOME_CLASSES: Record<DatumOutcome, string> = {
	Output: "text-bg-success",
	"Output, not persisted": "text-bg-success",
	Discarded: "text-bg-secondary",
	Error: "text-bg-danger",
	[INSTRUCTION_RUNS]: "text-bg-info",
};

/** The outcome of each datum index outcome code. */
export const OUTCOME_NAMES: Record<DatumOutcomeCode, DatumOutcome> = {
	Output: "Output",
	OutputNotPersisted: "Output, not persisted",
	Discarded: "Discarded",
	Error: "Error",
};

/** A row of the datum list: a datum, or an instruction run. */
export type ListItem =
	| { kind: "datum"; entry: DatumIndexEntry }
	| { kind: "run"; run: InstructionRun; index: number };

/** The height of a row, in pixels, which the CSS sets too. */
const ROW_HEIGHT = 26;

/** The rows drawn above and below the visible ones. */
const OVERSCAN = 10;

/** The outcome of a row. */
export function itemOutcome(item: ListItem): DatumOutcome {
	return item.kind === "run"
		? INSTRUCTION_RUNS
		: OUTCOME_NAMES[item.entry.outcome];
}

/** The date of a row, in milliseconds, or `NaN` if it has none. */
function itemTime(item: ListItem): number {
	const date = item.kind === "run" ? item.run.date : item.entry.created;
	return date ? Date.parse(date) : NaN;
}

/** The options of a {@link DatumList}. */
export interface DatumListOptions {
	/** The datum outcomes hidden, which the list's pills change. */
	hiddenOutcomes: Set<DatumOutcome>;
	/** Render a row's content. */
	renderRow: (item: ListItem) => HTMLElement;
	/** Test if a row is selected. */
	isSelected: (item: ListItem) => boolean;
	/** Content to add to the filter bar, such as the selection count. */
	info?: HTMLElement;
}

/** The detail of a list's `activate` event. */
export interface ListActivation {
	/** The row. */
	item: ListItem;
	/** `true` to select the row, or unselect it if it is selected. */
	toggle: boolean;
}

/**
 * The datum list of a result without every trace: a filter bar (source ID,
 * time range, outcome pills, and a datum number to go to) and the matching
 * rows, of which only the visible ones are drawn, as a run can have 100,000.
 *
 * One row is the current row, which the arrow keys move. Rows are selected
 * apart from that: a click on a row makes it the current row and selects or
 * unselects it, as do the Space and Enter keys, and the panel keeps which rows
 * are selected, whatever the filters show.
 *
 * It emits an `activate` event, with a {@link ListActivation} detail, when a
 * row becomes the current row or is clicked.
 */
export class DatumList extends EventTarget {
	readonly el: HTMLElement;
	readonly #items: ListItem[];
	readonly #options: DatumListOptions;
	readonly #scroller: HTMLElement;
	readonly #rows: HTMLElement;
	readonly #count: HTMLElement;
	#matching: ListItem[];
	/** The source IDs shown, or `null` for every one. */
	#sourceIds: Set<string> | null = null;
	#from: number | null = null;
	#to: number | null = null;
	#current: ListItem | null = null;
	#frame?: number;

	/**
	 * Constructor.
	 *
	 * @param items the rows, in processing order
	 * @param options the options
	 */
	constructor(items: ListItem[], options: DatumListOptions) {
		super();
		this.#items = items;
		this.#options = options;
		this.#matching = items;
		this.el = element("section", "sim-datum-list mb-3");
		this.#count = element(
			"span",
			"badge rounded-pill fw-normal text-nowrap bg-body-secondary text-body-secondary sim-datum-list-count",
		);
		this.#scroller = element(
			"div",
			"sim-datum-list-scroller border rounded",
		);
		this.#scroller.tabIndex = 0;
		this.#scroller.setAttribute("role", "listbox");
		this.#scroller.setAttribute("aria-label", "Datum");
		this.#scroller.setAttribute("aria-multiselectable", "true");
		this.#rows = element("div", "sim-datum-list-rows");
		this.#scroller.append(this.#rows);
		this.el.append(this.#filterBar(), this.#scroller);
		this.#scroller.addEventListener("scroll", () => this.#schedule());
		this.#scroller.addEventListener("keydown", (e) => this.#keydown(e));
		new ResizeObserver(() => this.#schedule()).observe(this.#scroller);
		this.#applyFilters();
	}

	/** The rows the filters match. */
	get matching(): readonly ListItem[] {
		return this.#matching;
	}

	/** The current row, or `null`. */
	get current(): ListItem | null {
		return this.#current;
	}

	/**
	 * Make a datum the current row, showing it if the filters hide it, and
	 * scroll to it.
	 *
	 * @param seq the datum sequence number
	 * @param emit `true` to emit an `activate` event
	 * @returns `true` if the list has the datum
	 */
	showDatum(seq: number, emit = true): boolean {
		const item = this.#items.find(
			(i) => i.kind === "datum" && i.entry.seq === seq,
		);
		if (!item) {
			return false;
		}
		this.#activate(item, false, emit);
		return true;
	}

	/** Make no row the current row. */
	clearCurrent(): void {
		this.#current = null;
		this.#render();
	}

	/** Draw the rows again, such as after their content changed. */
	refresh(): void {
		this.#render();
	}

	/**
	 * Show the dates again in the time zone the results show, such as after
	 * it changed.
	 */
	refreshDates(): void {
		this.#refreshDates();
		this.#render();
	}

	#activate(item: ListItem, toggle: boolean, emit = true): void {
		if (!this.#matching.includes(item)) {
			this.#resetFilters();
		}
		this.#current = item;
		this.#scrollIntoView(this.#matching.indexOf(item));
		this.#render();
		if (emit) {
			this.dispatchEvent(
				new CustomEvent<ListActivation>("activate", {
					detail: { item, toggle },
				}),
			);
		}
	}

	/* === Filters === */

	#filterBar(): HTMLElement {
		const bar = element(
			"div",
			"d-flex flex-wrap align-items-center gap-2 mb-2 sim-datum-filters",
		);
		bar.append(element("span", "fw-semibold small", "Datum"));

		// the source ID
		const sources = new Map<string, number>();
		for (const item of this.#items) {
			if (item.kind === "datum") {
				const id = item.entry.sourceId ?? "";
				sources.set(id, (sources.get(id) ?? 0) + 1);
			}
		}
		const sourceMenu = element("div", "dropdown sim-datum-list-source");
		const sourceButton = element(
			"button",
			"btn btn-sm btn-outline-secondary dropdown-toggle text-truncate",
		);
		sourceButton.type = "button";
		sourceButton.dataset.bsToggle = "dropdown";
		sourceButton.dataset.bsAutoClose = "outside";
		sourceButton.setAttribute("aria-expanded", "false");
		const sourceList = element(
			"div",
			"dropdown-menu p-1 sim-datum-list-sources",
		);
		const everySource = element(
			"button",
			"dropdown-item small",
			"Every source ID",
		);
		everySource.type = "button";
		sourceList.append(everySource, element("hr", "dropdown-divider"));
		const checks: HTMLInputElement[] = [];
		for (const [id, count] of [...sources].sort(([a], [b]) =>
			a.localeCompare(b),
		)) {
			const label = element(
				"label",
				"dropdown-item small d-flex align-items-center gap-2",
			);
			const check = element("input", "form-check-input mt-0");
			check.type = "checkbox";
			check.value = id;
			checks.push(check);
			label.append(
				check,
				element(
					"span",
					"text-truncate font-monospace",
					id || "(no source ID)",
				),
				element(
					"span",
					"ms-auto ps-2 text-body-secondary",
					count.toLocaleString(),
				),
			);
			sourceList.append(label);
		}
		const showSources = () => {
			const ids = this.#sourceIds;
			sourceButton.textContent = !ids
				? "Every source ID"
				: ids.size === 1
					? [...ids][0] || "(no source ID)"
					: `${ids.size} source IDs`;
			sourceButton.title = ids
				? `Showing the datum of ${[...ids].map((i) => i || "(no source ID)").join(", ")}`
				: "Show only the datum of some source IDs";
			for (const check of checks) {
				check.checked = !!ids?.has(check.value);
			}
		};
		sourceList.addEventListener("change", () => {
			const ids = checks.filter((c) => c.checked).map((c) => c.value);
			this.#sourceIds = ids.length ? new Set(ids) : null;
			showSources();
			this.#applyFilters();
		});
		everySource.addEventListener("click", () => {
			this.#sourceIds = null;
			showSources();
			this.#applyFilters();
		});
		showSources();
		sourceMenu.append(sourceButton, sourceList);

		// the time range
		let first = NaN;
		let last = NaN;
		for (const item of this.#items) {
			const t = itemTime(item);
			// each comparison with NaN is false, so the first date sets both
			if (!isNaN(t) && !(t >= first)) {
				first = t;
			}
			if (!isNaN(t) && !(t <= last)) {
				last = t;
			}
		}
		const dateInput = (label: string, end: boolean) => {
			const input = element(
				"input",
				"form-control form-control-sm sim-datum-list-date",
			);
			input.type = "text";
			input.spellcheck = false;
			input.setAttribute("aria-label", label);
			const apply = () => {
				try {
					const value = parseLocalDate(
						input.value,
						getDisplayZone(),
						end,
					);
					input.classList.remove("is-invalid");
					if (end) {
						this.#to = value;
					} else {
						this.#from = value;
					}
					this.#applyFilters();
				} catch (err) {
					input.classList.add("is-invalid");
					input.title =
						err instanceof Error ? err.message : String(err);
				}
			};
			input.addEventListener("change", apply);
			input.addEventListener("keydown", (e) => {
				if (e.key === "Enter") {
					e.preventDefault();
					apply();
				}
			});
			return input;
		};
		const from = dateInput("From", false);
		const to = dateInput("To", true);
		this.#refreshDates = () => {
			const zone = getDisplayZone();
			for (const [input, label, end, at, value] of [
				[from, "From", false, first, this.#from],
				[to, "To", true, last, this.#to],
			] as const) {
				input.placeholder = isNaN(at)
					? label
					: formatTimestamp(new Date(at).toISOString()).replace(
							/\.000$/,
							"",
						);
				input.title = `${label}, in the ${zone} time zone like the datum dates: a day, like 2025-10-01, or a time, like 2025-10-01 12:30. Press Enter to apply.`;
				if (value !== null && !input.classList.contains("is-invalid")) {
					input.value = formatRangeDate(value, zone, end);
				}
			}
		};
		this.#refreshDates();
		const range = element("span", "d-flex align-items-center gap-1");
		range.append(
			from,
			element("span", "small text-body-secondary", "to"),
			to,
		);

		// the datum number
		const go = element(
			"input",
			"form-control form-control-sm sim-datum-list-go",
		);
		go.type = "text";
		go.inputMode = "numeric";
		go.placeholder = "#";
		go.title = "Go to a datum number: press Enter to show it";
		go.setAttribute("aria-label", "Go to datum number");
		go.addEventListener("keydown", (e) => {
			if (e.key !== "Enter") {
				return;
			}
			e.preventDefault();
			const seq = Number(go.value.trim().replace(/^#/, ""));
			const found = Number.isInteger(seq) && this.showDatum(seq);
			go.classList.toggle("is-invalid", !found);
			if (found) {
				this.#scroller.focus();
			}
		});
		go.addEventListener("input", () => go.classList.remove("is-invalid"));

		// the outcome pills
		const pills = element(
			"span",
			"d-flex flex-wrap justify-content-end gap-2 ms-auto",
		);
		const counts = new Map<DatumOutcome, number>();
		for (const item of this.#items) {
			const o = itemOutcome(item);
			counts.set(o, (counts.get(o) ?? 0) + 1);
		}
		const hidden = this.#options.hiddenOutcomes;
		for (const [outcome, count] of counts) {
			const pill = element(
				"button",
				`badge rounded-pill border-0 sim-filter-pill ${OUTCOME_CLASSES[outcome]}`,
				`${outcome} ${count.toLocaleString()}`,
			);
			pill.type = "button";
			const refresh = () => {
				const shown = !hidden.has(outcome);
				pill.setAttribute("aria-pressed", String(shown));
				pill.title = `${shown ? "Hide" : "Show"} the ${outcome} rows`;
			};
			pill.addEventListener("click", () => {
				if (hidden.has(outcome)) {
					hidden.delete(outcome);
				} else {
					hidden.add(outcome);
				}
				refresh();
				this.#applyFilters();
			});
			pill.dataset.outcome = outcome;
			refresh();
			pills.append(pill);
		}

		this.#resetFilters = () => {
			from.value = "";
			to.value = "";
			from.classList.remove("is-invalid");
			to.classList.remove("is-invalid");
			this.#sourceIds = null;
			showSources();
			this.#from = null;
			this.#to = null;
			hidden.clear();
			pills
				.querySelectorAll<HTMLButtonElement>(".sim-filter-pill")
				.forEach((p) => {
					p.setAttribute("aria-pressed", "true");
					p.title = `Hide the ${p.dataset.outcome} rows`;
				});
			this.#applyFilters();
		};

		bar.append(
			sourceMenu,
			range,
			go,
			this.#count,
			...(this.#options.info ? [this.#options.info] : []),
			pills,
		);
		return bar;
	}

	/** Clear every filter, so every row shows. */
	#resetFilters: () => void = () => {};

	/** Show the time range in the time zone the results show. */
	#refreshDates: () => void = () => {};

	#matches(item: ListItem): boolean {
		if (this.#options.hiddenOutcomes.has(itemOutcome(item))) {
			return false;
		}
		if (this.#sourceIds !== null) {
			if (item.kind !== "datum") {
				return false;
			}
			if (!this.#sourceIds.has(item.entry.sourceId ?? "")) {
				return false;
			}
		}
		if (this.#from !== null || this.#to !== null) {
			const t = itemTime(item);
			if (
				isNaN(t) ||
				(this.#from !== null && t < this.#from) ||
				(this.#to !== null && t > this.#to)
			) {
				return false;
			}
		}
		return true;
	}

	#applyFilters(): void {
		this.#matching = this.#items.filter((i) => this.#matches(i));
		const datum = this.#items.filter((i) => i.kind === "datum").length;
		const matchingDatum = this.#matching.filter(
			(i) => i.kind === "datum",
		).length;
		this.#count.textContent =
			matchingDatum === datum &&
			this.#matching.length === this.#items.length
				? `${datum.toLocaleString()} datum`
				: `${matchingDatum.toLocaleString()} of ${datum.toLocaleString()} datum shown`;
		this.#rows.style.height = `${this.#matching.length * ROW_HEIGHT}px`;
		this.#render();
	}

	/* === Rows === */

	#schedule(): void {
		if (this.#frame === undefined) {
			this.#frame = window.requestAnimationFrame(() => {
				this.#frame = undefined;
				this.#render();
			});
		}
	}

	#render(): void {
		const top = this.#scroller.scrollTop;
		const height = this.#scroller.clientHeight || 300;
		const start = Math.max(0, Math.floor(top / ROW_HEIGHT) - OVERSCAN);
		const end = Math.min(
			this.#matching.length,
			Math.ceil((top + height) / ROW_HEIGHT) + OVERSCAN,
		);
		const rows: HTMLElement[] = [];
		for (let i = start; i < end; i++) {
			const item = this.#matching[i];
			const row = this.#options.renderRow(item);
			row.classList.add("sim-datum-list-row");
			row.style.top = `${i * ROW_HEIGHT}px`;
			row.setAttribute("role", "option");
			const selected = this.#options.isSelected(item);
			row.classList.toggle("is-selected", selected);
			row.classList.toggle("is-current", item === this.#current);
			row.setAttribute("aria-selected", String(selected));
			row.addEventListener("click", () => {
				this.#scroller.focus({ preventScroll: true });
				this.#activate(item, true);
			});
			rows.push(row);
		}
		if (!this.#matching.length) {
			rows.push(
				element(
					"div",
					"small text-body-secondary p-2",
					"No datum match the filters.",
				),
			);
		}
		this.#rows.replaceChildren(...rows);
	}

	#scrollIntoView(index: number): void {
		if (index < 0) {
			return;
		}
		const top = index * ROW_HEIGHT;
		const s = this.#scroller;
		if (top < s.scrollTop) {
			s.scrollTop = top;
		} else if (top + ROW_HEIGHT > s.scrollTop + s.clientHeight) {
			s.scrollTop = top + ROW_HEIGHT - s.clientHeight;
		}
	}

	#keydown(e: KeyboardEvent): void {
		const page = Math.max(
			1,
			Math.floor(this.#scroller.clientHeight / ROW_HEIGHT) - 1,
		);
		const moves: Record<string, number> = {
			ArrowDown: 1,
			ArrowUp: -1,
			PageDown: page,
			PageUp: -page,
		};
		const count = this.#matching.length;
		if (!count) {
			return;
		}
		const current = this.#current
			? this.#matching.indexOf(this.#current)
			: -1;
		if ((e.key === " " || e.key === "Enter") && this.#current) {
			e.preventDefault();
			this.#activate(this.#current, true);
			return;
		}
		let next: number;
		if (e.key in moves) {
			next =
				current < 0
					? 0
					: Math.max(0, Math.min(count - 1, current + moves[e.key]));
		} else if (e.key === "Home") {
			next = 0;
		} else if (e.key === "End") {
			next = count - 1;
		} else {
			return;
		}
		e.preventDefault();
		if (next !== current) {
			this.#activate(this.#matching[next], false);
		}
	}
}
