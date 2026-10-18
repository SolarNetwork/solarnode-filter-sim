import "../scss/style.scss";
import {
	ApiError,
	exportSettings,
	getFilterTypes,
	getInfo,
	importSettings,
} from "./api.ts";
import { Canvas } from "./canvas.ts";
import { showError, showMessages, showToast } from "./dialogs.ts";
import { Inspector } from "./inspector.ts";
import type { FilterNode } from "./layout.ts";
import {
	EXTERNAL_FACTORY_UID,
	type ExportStyle,
	type FilterTypeInfo,
} from "./model.ts";
import { renderPalette } from "./palette.ts";
import { initSplitter } from "./splitter.ts";
import { Workspace } from "./workspace.ts";

/** The name of exported settings CSV files. */
const EXPORT_FILE_NAME = "solarnode-filter-settings.csv";

function $<T extends HTMLElement>(selector: string): T {
	return document.querySelector<T>(selector)!;
}

/**
 * Show the application version, from the server.
 *
 * @param el the element to show the version in
 */
async function showAppInfo(el: HTMLElement): Promise<void> {
	try {
		const info = await getInfo();
		el.textContent = info.version ? `v${info.version}` : info.name;
	} catch (e) {
		console.error("Unable to get application info", e);
		el.textContent = "Server unavailable";
		el.classList.add("text-danger");
	}
}

/**
 * Save text as a file.
 *
 * @param text the file content
 * @param fileName the file name
 * @param type the media type
 */
function download(text: string, fileName: string, type: string): void {
	const url = URL.createObjectURL(new Blob([text], { type }));
	const a = document.createElement("a");
	a.href = url;
	a.download = fileName;
	a.click();
	window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** Test if keyboard events go to a form control rather than the canvas. */
function isTyping(target: EventTarget | null): boolean {
	return (
		target instanceof HTMLElement &&
		(target.isContentEditable ||
			["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName))
	);
}

/** The simulator application. */
class App {
	readonly #ws: Workspace;
	readonly #canvas: Canvas;
	readonly #inspector: Inspector;

	constructor(types: FilterTypeInfo[]) {
		const ws = Workspace.load(types);
		this.#ws = ws;
		this.#canvas = new Canvas($("#canvas"), ws);
		this.#inspector = new Inspector($("#inspector"), ws, {
			disconnect: (node) => {
				if (node.slot) {
					ws.detach(node.slot);
				}
			},
			remove: (node) => ws.remove(node.filterId),
		});
		renderPalette($("#palette"), types, (type) => {
			const main = ws.mainChainId;
			const filter = ws.addFilter(type, {
				chainId: main,
				index: ws.members(main).length,
			});
			this.#canvas.selectFilter(filter.id);
		});
		initSplitter(
			$("#left-splitter"),
			$("#left-panel"),
			"sn-filter-sim.leftSplit",
		);

		this.#canvas.addEventListener("select", (e) =>
			this.#inspector.show((e as CustomEvent<FilterNode | null>).detail),
		);
		this.#canvas.addEventListener("filedrop", (e) =>
			this.importFile((e as CustomEvent<File>).detail),
		);
		ws.addEventListener("change", () => this.#refreshControls());

		this.#bindControls();
		this.#refreshControls();
		this.#inspector.show(this.#canvas.selection);
		this.#canvas.fit();
	}

	/**
	 * Import a settings CSV file.
	 *
	 * @param file the file
	 */
	async importFile(file: File): Promise<void> {
		try {
			const result = await importSettings(await file.text());
			const notes = this.#ws.importConfig(result.config);
			this.#canvas.select(null);
			this.#canvas.fit();
			const count = (result.config.filters ?? []).filter(
				(f) => f.factoryUid !== EXTERNAL_FACTORY_UID,
			).length;
			const summary = `Imported ${count} filter${count === 1 ? "" : "s"} from ${file.name}.`;
			if (
				notes.length ||
				result.messages.length ||
				result.ignored.length
			) {
				showMessages({
					title: "Settings imported",
					summary: `${summary} You can undo the import with Ctrl+Z.`,
					notes,
					messages: result.messages,
					ignored: result.ignored,
					onFilter: (id) => this.#canvas.selectFilter(id),
				});
			} else {
				showToast(summary);
			}
		} catch (e) {
			showError("Settings not imported", e);
		}
	}

	/**
	 * Export the configuration as a settings CSV file.
	 *
	 * @param style the file style
	 */
	async exportFile(style: ExportStyle): Promise<void> {
		try {
			const result = await exportSettings(this.#ws.toConfig(true), style);
			download(result.csv, EXPORT_FILE_NAME, "text/csv");
			if (result.messages.length) {
				showMessages({
					title: "Settings exported",
					summary: `The settings were saved as ${EXPORT_FILE_NAME}. Note:`,
					messages: result.messages,
					onFilter: (id) => this.#canvas.selectFilter(id),
				});
			} else {
				showToast(`The settings were saved as ${EXPORT_FILE_NAME}.`);
			}
		} catch (e) {
			const problems =
				e instanceof ApiError ? e.problem?.problems : undefined;
			if (problems) {
				this.#canvas.setFlagged(
					problems
						.map((p) => p.filterId)
						.filter((id): id is string => !!id),
				);
			}
			showError("Settings not exported", e, (id) =>
				this.#canvas.selectFilter(id),
			);
		}
	}

	#bindControls(): void {
		const ws = this.#ws;
		const canvas = this.#canvas;
		const importInput = $<HTMLInputElement>("#import-file");
		$("#import-button").addEventListener("click", () =>
			importInput.click(),
		);
		importInput.addEventListener("change", () => {
			const file = importInput.files?.[0];
			importInput.value = "";
			if (file) {
				this.importFile(file);
			}
		});

		$("#export-button").addEventListener("click", () =>
			this.exportFile("Organized"),
		);
		document
			.querySelectorAll<HTMLElement>("[data-export-style]")
			.forEach((item) =>
				item.addEventListener("click", () =>
					this.exportFile(item.dataset.exportStyle as ExportStyle),
				),
			);

		const emptyFile = $<HTMLInputElement>("#empty-file");
		const emptyUpload = $<HTMLButtonElement>("#empty-upload");
		emptyFile.addEventListener("change", () => {
			emptyUpload.disabled = !emptyFile.files?.length;
		});
		emptyUpload.addEventListener("click", () => {
			const file = emptyFile.files?.[0];
			if (file) {
				emptyFile.value = "";
				emptyUpload.disabled = true;
				this.importFile(file);
			}
		});

		$("#undo-button").addEventListener("click", () => ws.undo());
		$("#redo-button").addEventListener("click", () => ws.redo());
		$("#zoom-in-button").addEventListener("click", () => canvas.zoom(1.2));
		$("#zoom-out-button").addEventListener("click", () =>
			canvas.zoom(1 / 1.2),
		);
		$("#fit-button").addEventListener("click", () => canvas.fit());

		document.addEventListener("keydown", (e) => {
			if (isTyping(e.target) || document.querySelector(".modal.show")) {
				return;
			}
			const command = e.ctrlKey || e.metaKey;
			const key = e.key.toLowerCase();
			if (command && key === "z") {
				e.preventDefault();
				if (e.shiftKey) {
					ws.redo();
				} else {
					ws.undo();
				}
			} else if (command && key === "y") {
				e.preventDefault();
				ws.redo();
			} else if (e.key === "Delete" || e.key === "Backspace") {
				const node = canvas.selection;
				if (node && !node.main) {
					e.preventDefault();
					ws.remove(node.filterId);
				}
			} else if (e.key === "Escape") {
				canvas.select(null);
			}
		});
	}

	#refreshControls(): void {
		$<HTMLButtonElement>("#undo-button").disabled = !this.#ws.canUndo;
		$<HTMLButtonElement>("#redo-button").disabled = !this.#ws.canRedo;
		$("#empty-state").hidden = !this.#ws.isEmpty;
	}
}

async function startApp(): Promise<void> {
	showAppInfo($("#app-version"));
	let types: FilterTypeInfo[] = [];
	try {
		types = await getFilterTypes();
	} catch (e) {
		showError("The simulator server is not available", e);
	}
	new App(types);
}

if (document.readyState === "loading") {
	window.addEventListener("DOMContentLoaded", startApp);
} else {
	startApp();
}
