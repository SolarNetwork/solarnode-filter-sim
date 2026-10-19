import { Modal } from "bootstrap";
import { combineExternalSettings } from "./api.ts";
import { showError } from "./dialogs.ts";
import type {
	ExternalSettingsFile,
	ExternalSettingsSummary,
	SimulationMessage,
} from "./model.ts";
import { element } from "./view.ts";
import type { Workspace } from "./workspace.ts";

/**
 * The External Settings dialog: SolarNode settings CSV files that the
 * simulation applies before its own settings, like the auto settings files
 * that SolarNode packages install.
 *
 * Adding or removing a file asks the server to combine the files again, and
 * stores the files and the result in the workspace, as one undo step.
 */
export class ExternalSettingsDialog {
	readonly #ws: Workspace;
	readonly #el: HTMLElement;
	readonly #fileInput: HTMLInputElement;
	readonly #addButton: HTMLButtonElement;
	readonly #rows: HTMLElement;
	/** The names of the files whose details are shown. */
	readonly #viewing = new Set<string>();
	#busy = false;

	/**
	 * Constructor.
	 *
	 * @param ws the workspace
	 */
	constructor(ws: Workspace) {
		this.#ws = ws;
		this.#el = document.querySelector<HTMLElement>("#external-dialog")!;
		this.#fileInput =
			this.#el.querySelector<HTMLInputElement>("#external-file")!;
		this.#addButton =
			this.#el.querySelector<HTMLButtonElement>("#external-add")!;
		this.#rows = this.#el.querySelector<HTMLElement>("#external-files")!;
		this.#fileInput.addEventListener("change", () => this.#refreshAdd());
		this.#addButton.addEventListener("click", () => {
			void this.#add();
		});
		ws.addEventListener("change", () => this.#render());
		this.#render();
	}

	/** Show the dialog. */
	show(): void {
		this.#render();
		Modal.getOrCreateInstance(this.#el).show();
	}

	async #add(): Promise<void> {
		const chosen = [...(this.#fileInput.files ?? [])];
		if (chosen.length === 0) {
			return;
		}
		const added: ExternalSettingsFile[] = await Promise.all(
			chosen.map(async (f) => ({ name: f.name, csv: await f.text() })),
		);
		const names = new Set(added.map((f) => f.name));
		// a file with the same name replaces the one already added
		const files = [
			...this.#ws.externalFiles.filter((f) => !names.has(f.name)),
			...added,
		];
		const label =
			added.length === 1
				? `Add External Settings ${added[0].name}`
				: "Add External Settings";
		if (await this.#apply(files, label)) {
			this.#fileInput.value = "";
			this.#refreshAdd();
		}
	}

	async #remove(name: string): Promise<void> {
		this.#viewing.delete(name);
		const files = this.#ws.externalFiles.filter((f) => f.name !== name);
		await this.#apply(files, `Remove External Settings ${name}`);
	}

	/**
	 * Combine files and store them in the workspace.
	 *
	 * @param files the files
	 * @param label the change label
	 * @returns `true` if the files were stored
	 */
	async #apply(
		files: ExternalSettingsFile[],
		label: string,
	): Promise<boolean> {
		this.#busy = true;
		this.#refreshAdd();
		try {
			const external =
				files.length > 0 ? await combineExternalSettings(files) : null;
			this.#ws.setExternalSettings(files, external, label);
			return true;
		} catch (e) {
			showError("External Settings not changed", e);
			return false;
		} finally {
			this.#busy = false;
			this.#refreshAdd();
		}
	}

	#refreshAdd(): void {
		this.#addButton.disabled = this.#busy || !this.#fileInput.files?.length;
	}

	#render(): void {
		const count = this.#ws.externalFiles.length;
		const badge = document.querySelector<HTMLElement>("#external-count");
		if (badge) {
			badge.hidden = count === 0;
			badge.textContent = String(count);
		}
		const summaries = this.#ws.externalSettings?.files ?? [];
		const rows: HTMLElement[] = [];
		if (summaries.length === 0) {
			const row = element("tr");
			const cell = element(
				"td",
				"text-body-secondary",
				"No External Settings files.",
			);
			cell.colSpan = 3;
			row.append(cell);
			rows.push(row);
		}
		for (const summary of summaries) {
			rows.push(this.#fileRow(summary));
			if (this.#viewing.has(summary.name)) {
				rows.push(this.#detailRow(summary));
			}
		}
		this.#rows.replaceChildren(...rows);
	}

	#fileRow(summary: ExternalSettingsSummary): HTMLElement {
		const row = element("tr");
		const name = element("td", "text-break");
		name.append(element("span", undefined, summary.name));
		if (!summary.applied) {
			const badge = element(
				"span",
				"badge text-bg-danger ms-2",
				"Not applied",
			);
			badge.title =
				"SolarNode cannot import this file, so it does not apply it.";
			name.append(badge);
		}
		const actions = element("td", "text-nowrap text-end");
		const viewing = this.#viewing.has(summary.name);
		const view = element(
			"button",
			"btn btn-outline-secondary btn-sm me-1",
			viewing ? "Hide" : "View",
		);
		view.type = "button";
		view.setAttribute("aria-expanded", String(viewing));
		view.addEventListener("click", () => {
			if (viewing) {
				this.#viewing.delete(summary.name);
			} else {
				this.#viewing.add(summary.name);
			}
			this.#render();
		});
		const remove = element("button", "btn btn-outline-danger btn-sm");
		remove.type = "button";
		remove.title = "Remove";
		remove.setAttribute("aria-label", `Remove ${summary.name}`);
		const icon = element("i", "bi bi-trash");
		icon.setAttribute("aria-hidden", "true");
		remove.append(icon);
		remove.addEventListener("click", () => {
			void this.#remove(summary.name);
		});
		actions.append(view, remove);
		row.append(
			name,
			element("td", "text-end pe-3", String(summary.settingCount)),
			actions,
		);
		return row;
	}

	#detailRow(summary: ExternalSettingsSummary): HTMLElement {
		const row = element("tr");
		const cell = element("td", "bg-body-tertiary small");
		cell.colSpan = 3;
		if (!summary.applied) {
			cell.append(
				element(
					"p",
					"text-danger-emphasis mb-2",
					"SolarNode cannot import this file, so the simulation does not apply it either.",
				),
			);
		}
		const filters = this.#filterLabels(summary.filterIds);
		cell.append(
			element(
				"p",
				"mb-2",
				filters.length > 0
					? `Filters: ${filters.join(", ")}.`
					: "The file has no datum filter settings.",
			),
		);
		if (summary.ignored.length > 0) {
			const ignored = summary.ignored
				.map(
					(c) =>
						`${c.key}${c.instanceKey ? ` ${c.instanceKey}` : ""} (${c.settingCount} row${c.settingCount === 1 ? "" : "s"})`,
				)
				.join(", ");
			cell.append(
				element(
					"p",
					"mb-2",
					`Ignored, because they are not datum filters: ${ignored}.`,
				),
			);
		}
		if (summary.messages.length > 0) {
			const list = element("ul", "mb-2");
			for (const m of summary.messages) {
				list.append(messageItem(m));
			}
			cell.append(list);
		}
		const csv = this.#ws.externalFiles.find(
			(f) => f.name === summary.name,
		)?.csv;
		if (csv !== undefined) {
			cell.append(element("pre", "sim-external-csv mb-0", csv));
		}
		row.append(cell);
		return row;
	}

	/**
	 * Get the labels of external filters.
	 *
	 * @param externalIds the external filter ids
	 */
	#filterLabels(externalIds: string[]): string[] {
		const ws = this.#ws;
		const result: string[] = [];
		for (const id of externalIds) {
			const filter = ws.filters.find(
				(f) => ws.externalFilter(f.id)?.id === id,
			);
			if (filter) {
				const label = ws.label(filter);
				result.push(
					filter.id === ws.mainChainId
						? `${label} (the Main chain)`
						: label,
				);
			}
		}
		return result;
	}
}

function messageItem(m: SimulationMessage): HTMLElement {
	const item = element("li");
	item.append(
		element(
			"span",
			m.level === "Error"
				? "text-danger-emphasis"
				: m.level === "Warning"
					? "text-warning-emphasis"
					: undefined,
			m.message,
		),
	);
	return item;
}
