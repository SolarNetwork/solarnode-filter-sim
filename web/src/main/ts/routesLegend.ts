/** A datum in the routes legend. */
export interface LegendItem {
	seq: number;
	sourceId: string;
	color: string;
	/** A short description, such as "input 1 → dropped by Join". */
	summary: string;
	/** The whole description, for a tooltip. */
	title: string;
	/** `true` if its route is shown. */
	visible: boolean;
	/** `true` if it is the selected datum. */
	selected: boolean;
}

/** A request to show or hide routes, from a legend click. */
export interface LegendToggle {
	seqs: number[];
	/**
	 * `true` to add the routes to the shown ones, or remove them if they are
	 * all shown (a Shift, Cmd, Ctrl, or right click), else to show only them.
	 */
	additive: boolean;
}

/** A range of datum sequence numbers, inclusive. */
export interface SeqRange {
	from: number;
	to: number;
}

/** The browser storage key of whether the legend is wide. */
const WIDE_STORAGE_KEY = "sn-filter-sim.routesLegendWide";

/**
 * Parse a comma-delimited list of datum numbers and ranges, like
 * `1-5, 8, 99-`: a range without a start starts at 1, and one without an end
 * has no end.
 *
 * @param text the list
 * @returns the ranges
 * @throws Error if the list is empty or has something that is not a number or
 *     range
 */
export function parseRanges(text: string): SeqRange[] {
	const ranges: SeqRange[] = [];
	for (const raw of text.split(",")) {
		const part = raw.trim();
		if (!part) {
			continue;
		}
		const m = /^(\d*)\s*-\s*(\d*)$/.exec(part) ?? /^(\d+)()$/.exec(part);
		if (!m || (!m[1] && !m[2])) {
			throw new Error(
				`"${part}" is not a datum number or range, like 3, 1-5, -4, or 99-.`,
			);
		}
		const single = !part.includes("-");
		const from = m[1] ? Number(m[1]) : 1;
		const to = single ? from : m[2] ? Number(m[2]) : Infinity;
		if (from > to) {
			throw new Error(`The range "${part}" starts after it ends.`);
		}
		ranges.push({ from, to });
	}
	if (ranges.length === 0) {
		throw new Error("Enter datum numbers or ranges, like 1-5, 8, 99-.");
	}
	return ranges;
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

/**
 * The datum routes legend: each datum, grouped by source ID, which a click
 * shows or hides like a chart legend in Grafana.
 *
 * It emits these events:
 *
 * - `toggle`, with a {@link LegendToggle} detail;
 * - `showall` and `shownone`, from the All and None buttons;
 * - `showrange`, with a {@link SeqRange} list detail, from the Range field;
 * - `hover`, with a datum sequence number detail, or `null` when the pointer
 *   leaves a datum.
 */
export class RoutesLegend extends EventTarget {
	readonly #el: HTMLElement;
	readonly #body: HTMLElement;
	readonly #count: HTMLElement;
	#collapsed = false;

	/**
	 * Constructor.
	 *
	 * @param el the legend element
	 */
	constructor(el: HTMLElement) {
		super();
		this.#el = el;
		el.replaceChildren();
		const header = element(
			"div",
			"card-header d-flex align-items-center gap-2 py-1 px-2",
		);
		const title = element(
			"span",
			"fw-semibold text-nowrap text-truncate",
			"Datum Routes",
		);
		this.#count = element(
			"span",
			"text-body-secondary text-nowrap ms-auto",
		);
		const collapse = element(
			"button",
			"btn btn-sm btn-link text-body-secondary p-0 sim-tool",
		);
		collapse.type = "button";
		const icon = element("i", "bi bi-chevron-down");
		icon.setAttribute("aria-hidden", "true");
		collapse.append(icon);
		const setCollapsed = (collapsed: boolean) => {
			this.#collapsed = collapsed;
			this.#body.hidden = collapsed;
			icon.className = `bi bi-chevron-${collapsed ? "up" : "down"}`;
			collapse.title = collapsed ? "Show the datum" : "Hide the datum";
			collapse.setAttribute("aria-label", collapse.title);
		};
		collapse.addEventListener("click", () =>
			setCollapsed(!this.#collapsed),
		);
		const button = (text: string, label: string, event: string) => {
			const b = element(
				"button",
				"btn btn-sm btn-link p-0 text-decoration-none",
				text,
			);
			b.type = "button";
			b.title = label;
			b.addEventListener("click", () =>
				this.dispatchEvent(new Event(event)),
			);
			return b;
		};
		const range = this.#rangeForm();
		const rangeButton = element(
			"button",
			"btn btn-sm btn-link p-0 text-decoration-none",
			"Range",
		);
		rangeButton.type = "button";
		rangeButton.title =
			"Show the routes of a list of datum numbers and ranges, like 1-5, 8, 99-";
		rangeButton.addEventListener("click", () => {
			range.hidden = !range.hidden;
			if (!range.hidden) {
				setCollapsed(false);
				range.querySelector("input")?.select();
			}
		});
		const wide = element(
			"button",
			"btn btn-sm btn-link text-body-secondary p-0 sim-tool",
		);
		wide.type = "button";
		const wideIcon = element("i");
		wideIcon.setAttribute("aria-hidden", "true");
		wide.append(wideIcon);
		const setWide = (isWide: boolean) => {
			el.classList.toggle("is-wide", isWide);
			wideIcon.className = `bi bi-arrows-angle-${isWide ? "contract" : "expand"}`;
			wide.title = isWide
				? "Make the legend smaller"
				: "Make the legend larger";
			wide.setAttribute("aria-label", wide.title);
		};
		wide.addEventListener("click", () => {
			const isWide = !el.classList.contains("is-wide");
			setWide(isWide);
			try {
				localStorage.setItem(WIDE_STORAGE_KEY, String(isWide));
			} catch {
				// not saved
			}
		});
		let savedWide = false;
		try {
			savedWide = localStorage.getItem(WIDE_STORAGE_KEY) === "true";
		} catch {
			// not wide
		}
		setWide(savedWide);
		header.append(
			title,
			this.#count,
			button("All", "Show every datum's route", "showall"),
			button("None", "Hide every datum's route", "shownone"),
			rangeButton,
			wide,
			collapse,
		);
		this.#body = element("div", "sim-routes-legend-body");
		header.title =
			"Click a datum or source to show only it, and again to show all. Shift+click, ⌘+click, Ctrl+click, or right click to show or hide one.";
		el.append(header, range, this.#body);
		setCollapsed(false);
		this.#body.addEventListener("pointerleave", () => this.#hover(null));
	}

	/** The Range field, which shows the routes of the datum numbers entered. */
	#rangeForm(): HTMLFormElement {
		const form = element(
			"form",
			"sim-routes-range px-2 py-1 border-bottom",
		);
		form.hidden = true;
		form.noValidate = true;
		const input = element("input", "form-control form-control-sm");
		input.placeholder = "1-5, 8, 99-";
		input.spellcheck = false;
		input.setAttribute("aria-label", "Datum numbers and ranges to show");
		input.title =
			"Datum numbers and ranges, separated by commas: 1-5 is datum 1 to 5, -4 is datum 1 to 4, and 99- is datum 99 and up. Press Enter to show them.";
		const feedback = element("div", "invalid-feedback");
		const setProblem = (problem: string) => {
			input.classList.toggle("is-invalid", !!problem);
			feedback.textContent = problem;
		};
		form.append(input, feedback);
		form.addEventListener("submit", (e) => {
			e.preventDefault();
			try {
				const ranges = parseRanges(input.value);
				setProblem("");
				this.dispatchEvent(
					new CustomEvent<SeqRange[]>("showrange", {
						detail: ranges,
					}),
				);
			} catch (err) {
				setProblem(err instanceof Error ? err.message : String(err));
			}
		});
		input.addEventListener("input", () => setProblem(""));
		input.addEventListener("keydown", (e) => {
			if (e.key === "Escape") {
				e.stopPropagation();
				form.hidden = true;
			}
		});
		return form;
	}

	/**
	 * Show the datum, or hide the legend.
	 *
	 * @param items the datum, in sequence order, or `null` to hide the legend
	 */
	show(items: LegendItem[] | null): void {
		this.#el.hidden = !items;
		if (!items) {
			return;
		}
		const shown = items.filter((i) => i.visible).length;
		this.#count.textContent =
			shown === items.length
				? `${items.length}`
				: `${shown} of ${items.length}`;
		const sources = new Map<string, LegendItem[]>();
		for (const item of items) {
			const list = sources.get(item.sourceId) ?? [];
			list.push(item);
			sources.set(item.sourceId, list);
		}
		const rows: HTMLElement[] = [];
		for (const [sourceId, list] of sources) {
			const name = sourceId || "(no source ID)";
			if (list.length === 1) {
				// a source with one datum is one row
				rows.push(this.#item(list[0], name));
				continue;
			}
			const heading = this.#row(
				"sim-legend-source",
				list.map((i) => i.seq),
				list.some((i) => i.visible),
			);
			const swatch = element("span", "sim-legend-swatch");
			swatch.style.backgroundColor = list[0].color;
			heading.append(
				swatch,
				element("span", "fw-semibold text-truncate", name),
				element(
					"span",
					"text-body-secondary ms-auto",
					String(list.length),
				),
			);
			heading.title = `${name}: ${list.length} datum`;
			heading.addEventListener("pointerenter", () => this.#hover(null));
			rows.push(heading);
			for (const item of list) {
				const row = this.#item(item);
				row.classList.add("is-nested");
				rows.push(row);
			}
		}
		this.#body.replaceChildren(...rows);
	}

	/**
	 * Highlight a datum, such as one whose route the pointer is over.
	 *
	 * @param seq the datum sequence number, or `null` for none
	 */
	setHover(seq: number | null): void {
		this.#body
			.querySelectorAll<HTMLElement>(".sim-legend-item")
			.forEach((row) =>
				row.classList.toggle(
					"is-hover",
					row.dataset.seq === String(seq),
				),
			);
	}

	/**
	 * Render a datum's row.
	 *
	 * @param item the datum
	 * @param sourceId the source ID to show, for a source with one datum
	 */
	#item(item: LegendItem, sourceId?: string): HTMLElement {
		const row = this.#row(
			`sim-legend-item${item.selected ? " is-selected" : ""}`,
			[item.seq],
			item.visible,
		);
		row.dataset.seq = String(item.seq);
		row.title = item.title;
		const chip = element("span", "sim-legend-chip", String(item.seq));
		chip.style.backgroundColor = item.color;
		const text = element("span", "text-truncate");
		if (sourceId) {
			text.append(element("span", "fw-semibold", sourceId), " ");
		}
		text.append(
			element(
				"span",
				sourceId ? "text-body-secondary" : "",
				item.summary,
			),
		);
		row.append(chip, text);
		row.addEventListener("pointerenter", () => this.#hover(item.seq));
		return row;
	}

	#row(className: string, seqs: number[], visible: boolean): HTMLElement {
		const row = element(
			"div",
			`${className} d-flex align-items-center gap-2${visible ? "" : " is-hidden"}`,
		);
		row.tabIndex = 0;
		row.setAttribute("role", "button");
		row.setAttribute("aria-pressed", String(visible));
		const toggle = (additive: boolean) =>
			this.dispatchEvent(
				new CustomEvent<LegendToggle>("toggle", {
					detail: { seqs, additive },
				}),
			);
		row.addEventListener("click", (e) =>
			toggle(e.ctrlKey || e.metaKey || e.shiftKey),
		);
		// a right click, which is Ctrl+click on macOS, never fires a click
		row.addEventListener("contextmenu", (e) => {
			e.preventDefault();
			toggle(true);
		});
		row.addEventListener("keydown", (e) => {
			if (e.key === "Enter" || e.key === " ") {
				e.preventDefault();
				toggle(e.ctrlKey || e.metaKey || e.shiftKey);
			}
		});
		return row;
	}

	#hover(seq: number | null): void {
		this.dispatchEvent(
			new CustomEvent<number | null>("hover", { detail: seq }),
		);
	}
}
