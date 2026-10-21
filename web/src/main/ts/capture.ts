/**
 * Datum capture: recording the datum a real SolarNode captures, as input datum.
 *
 * The server logs in to the node and relays its datum as an event stream (see
 * the app's `CaptureSession`). The capture panel shows them in a table that
 * follows new datum, like `tail -f`, until the capture ends and its datum go
 * into the Datum input.
 */

import { Modal } from "bootstrap";
import { ApiError, captureEventsUrl, endCapture, startCapture } from "./api.ts";
import { formatJson } from "./jsonFormat.ts";
import type { CaptureInfo, CaptureState } from "./model.ts";
import { element, formatValue, SAMPLE_TYPES, TYPE_NAMES } from "./view.ts";

/** How captured datum go into the Datum input. */
export type CaptureMode = "add" | "replace";

/** A capture that started. */
export interface StartedCapture {
	info: CaptureInfo;
	mode: CaptureMode;
}

/** What the browser remembers of the last capture: never the password. */
interface SavedCapture {
	url: string;
	username: string;
	sourceIds: string;
}

/** The browser storage key of the last capture's node and username. */
const STORAGE_KEY = "sn-filter-sim.capture";

/** The topic of a node's captured datum, without the source ID. */
const TOPIC_PREFIX = "/topic/datum/captured/";

/**
 * How close to the bottom of the table, in pixels, still counts as at the
 * bottom, so new datum scroll into view.
 */
const TAIL_SLACK = 8;

function $<T extends HTMLElement>(selector: string): T {
	return document.querySelector<T>(selector)!;
}

function isObject(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

function loadSaved(): Partial<SavedCapture> {
	try {
		const saved = JSON.parse(
			localStorage.getItem(STORAGE_KEY) ?? "{}",
		) as unknown;
		return isObject(saved) ? (saved as Partial<SavedCapture>) : {};
	} catch {
		return {};
	}
}

function save(saved: SavedCapture): void {
	try {
		localStorage.setItem(STORAGE_KEY, JSON.stringify(saved));
	} catch {
		// not saved
	}
}

/**
 * Get the Datum input's JSON with captured datum added, or replaced by them.
 *
 * The result is formatted like the Format button does, and numbers keep
 * their exact text.
 *
 * @param existing the Datum input's JSON
 * @param captured the JSON of each captured datum
 * @param mode whether to add the captured datum after the existing ones, or
 *     replace them
 * @returns the new Datum input JSON
 * @throws Error if the mode is `add` and the existing JSON is not valid
 */
export function capturedDatumJson(
	existing: string,
	captured: readonly string[],
	mode: CaptureMode,
): string {
	const items = captured.join(",");
	const text = existing.trim();
	if (mode === "replace" || !text) {
		return formatJson(`[${items}]`);
	}
	const value = JSON.parse(text) as unknown;
	if (Array.isArray(value)) {
		const inner = text.slice(1, -1).trim();
		return formatJson(`[${inner}${inner ? "," : ""}${items}]`);
	}
	return formatJson(`[${text},${items}]`);
}

/**
 * Ask for a node and its login, and start capturing from it.
 *
 * Problems starting the capture, like a wrong password, show in the dialog,
 * so they can be fixed there.
 *
 * @param existing the number of datum in the Datum input, which the captured
 *     datum can be added to, or `null` if its JSON is not valid
 * @returns the capture, or `null` if the dialog was closed without one
 */
export function askCapture(
	existing: number | null,
): Promise<StartedCapture | null> {
	const el = $("#capture-dialog");
	const dialog = Modal.getOrCreateInstance(el);
	const form = $<HTMLFormElement>("#capture-form");
	const url = $<HTMLInputElement>("#capture-url");
	const username = $<HTMLInputElement>("#capture-username");
	const password = $<HTMLInputElement>("#capture-password");
	const sourceIds = $<HTMLInputElement>("#capture-source-ids");
	const add = $<HTMLInputElement>("#capture-mode-add");
	const replace = $<HTMLInputElement>("#capture-mode-replace");
	const error = $("#capture-error");
	const start = $<HTMLButtonElement>("#capture-start");
	const spinner = start.querySelector<HTMLElement>(".spinner-border")!;

	const saved = loadSaved();
	url.value = saved.url ?? "";
	username.value = saved.username ?? "";
	password.value = "";
	sourceIds.value = saved.sourceIds ?? "";
	$("#capture-mode").hidden = existing === 0;
	add.disabled = existing === null;
	add.checked = existing !== null;
	replace.checked = existing === null;
	$("#capture-mode-add-label").textContent =
		existing === null
			? "Add the captured datum after the datum already there (not possible, as the Datum input is not valid JSON)"
			: `Add the captured datum after the ${existing} datum already there`;
	error.hidden = true;

	const busy = (connecting: boolean) => {
		start.disabled = connecting;
		spinner.hidden = !connecting;
	};
	busy(false);

	return new Promise((resolve) => {
		let result: StartedCapture | null = null;
		let open = true;
		const submit = async (e: SubmitEvent) => {
			e.preventDefault();
			const missing = [url, username, password].find(
				(input) => !input.value.trim(),
			);
			if (missing) {
				error.textContent =
					missing === url
						? "Enter the node's address, like http://solarnode.local."
						: "Enter the username and password you log in to the node with.";
				error.hidden = false;
				missing.focus();
				return;
			}
			error.hidden = true;
			busy(true);
			const mode: CaptureMode = add.checked ? "add" : "replace";
			try {
				const info = await startCapture({
					url: url.value.trim(),
					username: username.value.trim(),
					password: password.value,
					sourceIds: sourceIds.value.trim() || null,
				});
				save({
					url: url.value.trim(),
					username: username.value.trim(),
					sourceIds: sourceIds.value.trim(),
				});
				if (!open) {
					// cancelled while connecting
					void endCapture(info.id);
					return;
				}
				result = { info, mode };
				dialog.hide();
			} catch (e) {
				error.textContent =
					e instanceof ApiError
						? e.message
						: "The simulator server is not available.";
				error.hidden = false;
			} finally {
				busy(false);
			}
		};
		form.addEventListener("submit", submit);
		el.addEventListener(
			"shown.bs.modal",
			() =>
				[url, username, password]
					.find((input) => !input.value)
					?.focus(),
			{ once: true },
		);
		el.addEventListener(
			"hidden.bs.modal",
			() => {
				open = false;
				form.removeEventListener("submit", submit);
				password.value = "";
				resolve(result);
			},
			{ once: true },
		);
		dialog.show();
	});
}

/**
 * The capture panel, below the canvas: a table of each datum captured, with
 * its index in the Datum input, source ID, date, and properties.
 *
 * It emits an `end` event when End Capture is clicked, and a `discard` event
 * when Discard is.
 */
export class CapturePanel extends EventTarget {
	readonly #el: HTMLElement;
	#capture: StartedCapture | null = null;
	#source: EventSource | null = null;
	#texts: string[] = [];
	#first = 0;
	#stopped = false;
	#body?: HTMLElement;
	#rows?: HTMLElement;
	#waiting?: HTMLElement;
	#dot?: HTMLElement;
	#status?: HTMLElement;
	#count?: HTMLElement;
	#guessed?: HTMLElement;
	#notice?: HTMLElement;

	/**
	 * Constructor.
	 *
	 * @param el the panel element
	 */
	constructor(el: HTMLElement) {
		super();
		this.#el = el;
	}

	/** `true` while the panel shows a capture. */
	get active(): boolean {
		return this.#capture !== null;
	}

	/** How the captured datum go into the Datum input. */
	get mode(): CaptureMode {
		return this.#capture?.mode ?? "add";
	}

	/** The JSON of each datum captured. */
	get datum(): readonly string[] {
		return this.#texts;
	}

	/**
	 * Show a capture that started, and receive its datum.
	 *
	 * @param capture the capture
	 * @param first the number of datum before the captured ones in the Datum
	 *     input, for the index column
	 */
	start(capture: StartedCapture, first: number): void {
		this.stop();
		this.#capture = capture;
		this.#first = first;
		this.#texts = [];
		this.#stopped = false;
		this.#render();
		this.#connect();
	}

	/**
	 * Stop the capture on the server, and stop receiving its datum. The
	 * panel keeps showing what was captured.
	 *
	 * @param closing `true` if the page is closing
	 */
	stop(closing = false): void {
		this.#source?.close();
		this.#source = null;
		const capture = this.#capture;
		if (capture && !this.#stopped) {
			this.#stopped = true;
			void endCapture(capture.info.id, closing).catch(() => {
				// the server ends it by itself once no one watches it
			});
		}
	}

	/** Stop the capture, and clear the panel. */
	clear(): void {
		this.stop();
		this.#capture = null;
		this.#texts = [];
		this.#el.replaceChildren();
	}

	#render(): void {
		const capture = this.#capture!;
		const header = element(
			"header",
			"sim-results-header d-flex align-items-center gap-2 px-3 py-2 border-bottom bg-body-tertiary",
		);
		this.#dot = element("span", "sim-capture-dot is-live");
		this.#dot.setAttribute("aria-hidden", "true");
		const title = element("h2", "h6 mb-0 text-nowrap", "Datum Capture");
		const pattern = capture.info.topic.startsWith(TOPIC_PREFIX)
			? capture.info.topic.substring(TOPIC_PREFIX.length)
			: "**";
		this.#status = element(
			"span",
			"small text-body-secondary text-truncate",
			`Connecting to ${capture.info.url}…`,
		);
		this.#status.title = `${capture.info.url}, source IDs ${pattern === "**" ? "all" : `/${pattern}`}`;
		this.#count = element(
			"span",
			"badge rounded-pill text-bg-secondary",
			"0",
		);
		this.#count.title = "The number of datum captured";
		const discard = element(
			"button",
			"btn btn-outline-secondary btn-sm text-nowrap ms-auto",
			"Discard",
		);
		discard.type = "button";
		discard.title = "End the capture without changing the Datum input";
		discard.addEventListener("click", () =>
			this.dispatchEvent(new Event("discard")),
		);
		const end = element("button", "btn btn-primary btn-sm text-nowrap");
		end.type = "button";
		end.title =
			capture.mode === "add"
				? "End the capture, and add the captured datum to the Datum input"
				: "End the capture, and replace the Datum input with the captured datum";
		end.append(element("i", "bi bi-stop-fill"), " End Capture");
		end.addEventListener("click", () =>
			this.dispatchEvent(new Event("end")),
		);
		header.append(
			this.#dot,
			title,
			this.#status,
			this.#count,
			discard,
			end,
		);

		this.#guessed = element(
			"div",
			"small px-3 py-1 border-bottom bg-warning-subtle text-warning-emphasis",
			"The node's messages do not say which type each property is, so they were guessed: numbers are instantaneous, except the wattHours and wattHoursReverse readings, which are accumulating, and other values are status. Check them in the Datum input after the capture.",
		);
		this.#guessed.hidden = true;
		this.#notice = element("div", "small px-3 py-1 border-bottom");
		this.#notice.hidden = true;

		const table = element(
			"table",
			"table table-sm table-hover sim-capture-table",
		);
		const head = element("thead");
		const headRow = element("tr");
		const index = element("th", "text-end", "#");
		index.title = "The datum's place in the Datum input";
		headRow.append(
			index,
			element("th", undefined, "Source ID"),
			element("th", undefined, "Created"),
			element("th", undefined, "Properties"),
		);
		head.append(headRow);
		this.#rows = element("tbody");
		this.#waiting = element("tr");
		const waiting = element(
			"td",
			"text-body-secondary",
			"Waiting for the node's datum…",
		);
		waiting.colSpan = 4;
		this.#waiting.append(waiting);
		this.#rows.append(this.#waiting);
		table.append(head, this.#rows);
		this.#body = element("div", "sim-capture-body");
		this.#body.append(table);
		this.#el.replaceChildren(
			header,
			this.#guessed,
			this.#notice,
			this.#body,
		);
	}

	#connect(): void {
		const capture = this.#capture!;
		const source = new EventSource(captureEventsUrl(capture.info.id));
		this.#source = source;
		source.addEventListener("state", (e) => {
			const state = JSON.parse((e as MessageEvent<string>).data) as
				CaptureState | undefined;
			if (state) {
				this.#showState(state);
			}
		});
		source.addEventListener("datum", (e) => {
			const msg = e as MessageEvent<string>;
			// a reconnected stream starts after the last datum it sent
			const n = Number(msg.lastEventId);
			if (n > this.#texts.length) {
				this.#addDatum(msg.data);
			}
		});
		source.addEventListener("error", () => {
			if (this.#source !== source) {
				return;
			}
			if (source.readyState === EventSource.CLOSED) {
				this.#showState({
					state: "Ended",
					count: this.#texts.length,
					guessed: !this.#guessed?.hidden,
					message: "The connection to the simulator server was lost.",
					error: true,
				});
			} else {
				this.#status!.textContent = "Reconnecting to the simulator…";
			}
		});
	}

	#showState(state: CaptureState): void {
		const capture = this.#capture!;
		this.#guessed!.hidden = !state.guessed;
		if (state.state === "Capturing") {
			this.#status!.textContent = `Capturing from ${capture.info.url}`;
			return;
		}
		this.#source?.close();
		this.#source = null;
		this.#dot!.classList.remove("is-live");
		this.#status!.textContent = `Ended, from ${capture.info.url}`;
		const count = this.#texts.length;
		const next = count
			? ` End the capture to ${capture.mode === "add" ? "add" : "use"} the ${count} datum ${capture.mode === "add" ? "to" : "in"} the Datum input, or discard them.`
			: "";
		const notice = this.#notice!;
		notice.textContent = `${state.message ?? "The capture ended."}${next}`;
		notice.className = `small px-3 py-1 border-bottom ${state.error ? "bg-danger-subtle text-danger-emphasis" : "bg-body-secondary"}`;
		notice.hidden = false;
	}

	#addDatum(json: string): void {
		let datum: unknown;
		try {
			datum = JSON.parse(json);
		} catch {
			return;
		}
		if (!isObject(datum)) {
			return;
		}
		this.#texts.push(json);
		const n = this.#texts.length;
		this.#count!.textContent = String(n);
		this.#waiting?.remove();
		const body = this.#body!;
		const atBottom =
			body.scrollHeight - body.scrollTop - body.clientHeight <=
			TAIL_SLACK;
		this.#rows!.append(this.#row(this.#first + n, datum));
		if (atBottom) {
			body.scrollTop = body.scrollHeight;
		}
	}

	#row(index: number, datum: Record<string, unknown>): HTMLElement {
		const row = element("tr");
		row.append(
			element("td", "text-end text-body-secondary", String(index)),
			element("td", undefined, formatValue(datum.sourceId)),
			element(
				"td",
				"text-body-secondary",
				typeof datum.created === "string" ? datum.created : "",
			),
		);
		const props = element("td");
		for (const type of SAMPLE_TYPES) {
			const samples = datum[type];
			if (!isObject(samples)) {
				continue;
			}
			for (const [key, value] of Object.entries(samples)) {
				const prop = element("span", "sim-capture-prop");
				prop.title = `${TYPE_NAMES[type]} property ${key}`;
				prop.append(
					element("span", "sim-chip-type", type),
					`${key}=${formatValue(value)}`,
				);
				props.append(prop);
			}
		}
		const tags = datum.t;
		if (Array.isArray(tags)) {
			for (const tag of tags) {
				const prop = element("span", "sim-capture-prop");
				prop.title = `Tag ${String(tag)}`;
				prop.append(element("span", "sim-chip-type", "t"), String(tag));
				props.append(prop);
			}
		}
		row.append(props);
		return row;
	}
}
