import { Modal, Toast } from "bootstrap";
import { ApiError } from "./api.ts";
import type { IgnoredComponent, SimulationMessage } from "./model.ts";
import { messageItem } from "./view.ts";

/** What a message dialog shows. */
export interface MessageDialog {
	title: string;
	/** A sentence summarizing the result. */
	summary?: string;
	/** Notes from the UI, shown as information messages. */
	notes?: string[];
	messages?: SimulationMessage[];
	ignored?: IgnoredComponent[];
	/** Called when a message about a filter is clicked. */
	onFilter?: (filterId: string) => void;
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
 * Show messages in a dialog.
 *
 * @param options what to show
 */
export function showMessages(options: MessageDialog): void {
	const el = document.querySelector<HTMLElement>("#message-dialog")!;
	const dialog = Modal.getOrCreateInstance(el);
	el.querySelector("#message-dialog-title")!.textContent = options.title;
	const body = el.querySelector<HTMLElement>("#message-dialog-body")!;
	body.replaceChildren();
	if (options.summary) {
		body.append(element("p", undefined, options.summary));
	}
	const messages: SimulationMessage[] = [
		...(options.notes ?? []).map((note): SimulationMessage => ({
			level: "Info",
			code: "Note",
			message: note,
		})),
		...(options.messages ?? []),
	];
	if (messages.length > 0) {
		const list = element("div", "list-group mb-3");
		for (const m of messages) {
			const onFilter = options.onFilter;
			const filterId = m.filterId;
			list.append(
				messageItem(
					m,
					filterId && onFilter
						? {
								title: "Show the filter",
								show: () => {
									dialog.hide();
									onFilter(filterId);
								},
							}
						: undefined,
				),
			);
		}
		body.append(list);
	}
	if (options.ignored && options.ignored.length > 0) {
		const details = element("details");
		const count = options.ignored.length;
		details.append(
			element(
				"summary",
				undefined,
				`${count} other component${count === 1 ? "" : "s"} ignored, because ${count === 1 ? "it is" : "they are"} not datum filters`,
			),
		);
		const table = element("table", "table table-sm small mt-2 mb-0");
		const head = element("thead");
		const headRow = element("tr");
		headRow.append(
			element("th", undefined, "Component"),
			element("th", undefined, "Instance"),
			element("th", "text-end", "Rows"),
		);
		head.append(headRow);
		const rows = element("tbody");
		for (const c of options.ignored) {
			const row = element("tr");
			row.append(
				element("td", "text-break", c.key),
				element("td", undefined, c.instanceKey ?? ""),
				element("td", "text-end", String(c.settingCount)),
			);
			rows.append(row);
		}
		table.append(head, rows);
		details.append(table);
		body.append(details);
	}
	dialog.show();
}

/**
 * Show an error in a dialog.
 *
 * @param title the dialog title
 * @param error the error
 * @param onFilter called when a problem about a filter is clicked
 */
export function showError(
	title: string,
	error: unknown,
	onFilter?: (filterId: string) => void,
): void {
	console.error(title, error);
	const message = error instanceof Error ? error.message : String(error);
	const problems =
		error instanceof ApiError ? error.problem?.problems : undefined;
	showMessages({
		title,
		summary: message,
		messages: problems,
		onFilter,
	});
}

/** How to import a settings file into a simulation that has filters. */
export type ImportMode = "add" | "replace";

/**
 * Ask whether to add a settings file's settings to the simulation, or replace
 * the simulation's settings with them.
 *
 * @param fileName the file name
 * @returns the choice, or `null` if the dialog was closed without one
 */
export function askImportMode(fileName: string): Promise<ImportMode | null> {
	const el = document.querySelector<HTMLElement>("#import-dialog")!;
	const dialog = Modal.getOrCreateInstance(el);
	el.querySelector("#import-dialog-file")!.textContent = fileName;
	const buttons =
		el.querySelectorAll<HTMLButtonElement>("[data-import-mode]");
	return new Promise((resolve) => {
		let choice: ImportMode | null = null;
		const choose = (e: Event) => {
			choice = (e.currentTarget as HTMLElement).dataset
				.importMode as ImportMode;
			dialog.hide();
		};
		buttons.forEach((b) => b.addEventListener("click", choose));
		el.addEventListener(
			"shown.bs.modal",
			() =>
				el
					.querySelector<HTMLButtonElement>(
						'[data-import-mode="add"]',
					)
					?.focus(),
			{ once: true },
		);
		el.addEventListener(
			"hidden.bs.modal",
			() => {
				buttons.forEach((b) => b.removeEventListener("click", choose));
				resolve(choice);
			},
			{ once: true },
		);
		dialog.show();
	});
}

/** What a confirmation dialog asks. */
export interface ConfirmDialog {
	title: string;
	/** The question, which can include elements, like a file name. */
	message: (string | Node)[];
	/** More about what confirming does, in smaller text. */
	detail?: string;
	/** The text of the button that confirms, which is styled as dangerous. */
	confirm: string;
	/**
	 * The text of a button that saves the simulation first and then confirms,
	 * if the dialog has one.
	 */
	saveFirst?: string;
}

/**
 * The answer to a confirmation dialog: confirm, save first and then confirm,
 * or `null` to cancel.
 */
export type ConfirmResult = "confirm" | "saveFirst" | null;

/**
 * Ask to confirm an action that is hard to undo. The Cancel button has the
 * focus, so Enter does not confirm by accident.
 *
 * @param options what to ask
 * @returns the answer
 */
export function askConfirm(options: ConfirmDialog): Promise<ConfirmResult> {
	const el = document.querySelector<HTMLElement>("#confirm-dialog")!;
	const dialog = Modal.getOrCreateInstance(el);
	el.querySelector("#confirm-dialog-title")!.textContent = options.title;
	el.querySelector("#confirm-dialog-message")!.replaceChildren(
		...options.message,
	);
	const detail = el.querySelector<HTMLElement>("#confirm-dialog-detail")!;
	detail.textContent = options.detail ?? "";
	detail.hidden = !options.detail;
	const button = el.querySelector<HTMLButtonElement>("[data-confirm]")!;
	button.textContent = options.confirm;
	const saveButton = el.querySelector<HTMLButtonElement>(
		"[data-confirm-save]",
	)!;
	saveButton.textContent = options.saveFirst ?? "";
	saveButton.hidden = !options.saveFirst;
	return new Promise((resolve) => {
		let result: ConfirmResult = null;
		const confirm = () => {
			result = "confirm";
			dialog.hide();
		};
		const saveFirst = () => {
			result = "saveFirst";
			dialog.hide();
		};
		button.addEventListener("click", confirm);
		saveButton.addEventListener("click", saveFirst);
		el.addEventListener(
			"shown.bs.modal",
			() =>
				el
					.querySelector<HTMLButtonElement>(
						'[data-bs-dismiss="modal"].btn',
					)
					?.focus(),
			{ once: true },
		);
		el.addEventListener(
			"hidden.bs.modal",
			() => {
				button.removeEventListener("click", confirm);
				saveButton.removeEventListener("click", saveFirst);
				resolve(result);
			},
			{ once: true },
		);
		dialog.show();
	});
}

/**
 * Ask whether to replace the simulation with the one in a simulation file.
 *
 * @param fileName the file name
 * @returns the answer
 */
export function askOpenSimulation(fileName: string): Promise<ConfirmResult> {
	return askConfirm({
		title: "Open Simulation",
		message: [
			"Replace this simulation with the one in ",
			element("strong", "text-break", fileName),
			"?",
		],
		detail: "This replaces the filters, External Settings, and run inputs. You can undo the filter and External Settings changes with Ctrl+Z, but not the run inputs. To keep this simulation, save it first.",
		confirm: "Replace",
		saveFirst: "Save, then replace",
	});
}

/**
 * Ask whether to start a new, empty simulation.
 *
 * @returns the answer
 */
export function askNewSimulation(): Promise<ConfirmResult> {
	return askConfirm({
		title: "New Simulation",
		message: ["Start a new, empty simulation?"],
		detail: "This removes the filters, External Settings, and run inputs, and closes the execution flow. You can undo the filter and External Settings changes with Ctrl+Z, but not the run inputs. To keep this simulation, save it first.",
		confirm: "Start new",
		saveFirst: "Save, then start new",
	});
}

/**
 * Show a short message that disappears by itself.
 *
 * @param text the message
 */
export function showToast(text: string): void {
	const container = document.querySelector<HTMLElement>("#toasts")!;
	const toast = element(
		"div",
		"toast align-items-center border-0 text-bg-dark",
	);
	toast.setAttribute("role", "status");
	toast.setAttribute("aria-live", "polite");
	const content = element("div", "d-flex");
	const close = element("button", "btn-close btn-close-white me-2 m-auto");
	close.type = "button";
	close.setAttribute("data-bs-dismiss", "toast");
	close.setAttribute("aria-label", "Close");
	content.append(element("div", "toast-body", text), close);
	toast.append(content);
	container.append(toast);
	toast.addEventListener("hidden.bs.toast", () => toast.remove());
	Toast.getOrCreateInstance(toast, { delay: 4000 }).show();
}
