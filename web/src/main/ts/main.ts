import "../scss/style.scss";
import {
	ApiError,
	exportSettings,
	getFilterTypes,
	getInfo,
	importSettings,
	simulate,
} from "./api.ts";
import { Canvas } from "./canvas.ts";
import { showError, showMessages, showToast } from "./dialogs.ts";
import { InputPanel } from "./inputPanel.ts";
import { InputError, NODE_METADATA_SERVICE_UID, RunInputs } from "./inputs.ts";
import { Inspector } from "./inspector.ts";
import type { FilterNode } from "./layout.ts";
import {
	EXTERNAL_FACTORY_UID,
	type ExportStyle,
	type FilterTypeInfo,
	type SimulationResult,
} from "./model.ts";
import { renderPalette } from "./palette.ts";
import {
	filterRunStats,
	ResultsPanel,
	type DatumPath,
	type DebugMark,
} from "./results.ts";
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

/** The service filter object class of metadata services. */
const METADATA_SERVICE_CLASS = "net.solarnetwork.node.service.MetadataService";

/** How long after a change, in milliseconds, debug mode runs again. */
const DEBUG_RERUN_DELAY = 600;

/** The service filter object class of datum filters. */
const FILTER_SERVICE_CLASS = "net.solarnetwork.service.DatumFilterService";

/** The simulator application. */
class App {
	readonly #ws: Workspace;
	readonly #inputs: RunInputs;
	readonly #canvas: Canvas;
	readonly #inspector: Inspector;
	readonly #inputPanel: InputPanel;
	readonly #results: ResultsPanel;
	#running = false;
	#rerunTimer?: number;

	constructor(types: FilterTypeInfo[]) {
		const ws = Workspace.load(types);
		const inputs = RunInputs.load();
		this.#ws = ws;
		this.#inputs = inputs;
		this.#canvas = new Canvas($("#canvas"), ws);
		this.#inspector = new Inspector($("#inspector"), ws, {
			disconnect: (node) => {
				if (node.slot) {
					ws.detach(node.slot);
				}
			},
			remove: (node) => ws.remove(node.filterId),
			relatedServices: (serviceFilter) =>
				this.#relatedServices(serviceFilter),
		});
		this.#inputPanel = new InputPanel(inputs, ws);
		this.#results = new ResultsPanel($("#results"), ws);
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
			0.55,
		);

		this.#canvas.addEventListener("select", (e) =>
			this.#inspector.show((e as CustomEvent<FilterNode | null>).detail),
		);
		this.#canvas.addEventListener("filedrop", (e) =>
			this.importFile((e as CustomEvent<File>).detail),
		);
		ws.addEventListener("change", () => {
			this.#refreshControls();
			this.#changed();
		});
		inputs.addEventListener("change", () => this.#changed());
		this.#bindResults();

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

	/**
	 * Run the input datum through the filters, and show the result.
	 *
	 * @param debug `true` to step through the result in debug mode
	 * @param auto `true` for a re-run after a change in debug mode, which keeps
	 *     the debug position and does not interrupt with dialogs
	 */
	async run(debug = false, auto = false): Promise<void> {
		if (this.#running) {
			if (auto) {
				this.#scheduleRerun();
			}
			return;
		}
		window.clearTimeout(this.#rerunTimer);
		let parts;
		try {
			parts = this.#inputs.toRequest();
		} catch (e) {
			if (e instanceof InputError) {
				if (!auto) {
					this.#inputPanel.showError(e);
					showToast(e.message);
				}
				return;
			}
			throw e;
		}
		const mark = auto ? this.#results.debugMark : null;
		this.#setRunning(true);
		try {
			const result = await simulate({
				config: this.#ws.toConfig(),
				...parts,
				options: { ...parts.options, detail: debug ? "Debug" : null },
			});
			this.#showResult(result, debug, mark);
		} catch (e) {
			if (auto) {
				showToast(
					`The simulation did not run: ${e instanceof Error ? e.message : e}`,
				);
			} else {
				showError("The simulation did not run", e, (id) =>
					this.#canvas.selectFilter(id),
				);
			}
		} finally {
			this.#setRunning(false);
		}
	}

	/** Handle a workspace or input change. */
	#changed(): void {
		this.#markStale();
		if (this.#results.debugging) {
			this.#scheduleRerun();
		}
	}

	#scheduleRerun(): void {
		window.clearTimeout(this.#rerunTimer);
		this.#rerunTimer = window.setTimeout(
			() => void this.run(true, true),
			DEBUG_RERUN_DELAY,
		);
	}

	#setRunning(running: boolean): void {
		this.#running = running;
		const button = $<HTMLButtonElement>("#run-button");
		button.disabled = running;
		$<HTMLButtonElement>("#debug-button").disabled = running;
		button
			.querySelector("i")!
			.classList.replace(
				running ? "bi-play-fill" : "bi-hourglass-split",
				running ? "bi-hourglass-split" : "bi-play-fill",
			);
	}

	#showResult(
		result: SimulationResult,
		debug: boolean,
		mark: DebugMark | null,
	): void {
		const center = $("#center-panel");
		const wasShown = center.classList.contains("has-results");
		center.classList.add("has-results");
		$("#center-splitter").hidden = false;
		$("#results").hidden = false;
		// the canvas first, as debug mode then highlights the cursor on it
		this.#canvas.setRun({
			stats: filterRunStats(result),
			output: result.stats.output,
		});
		if (debug) {
			this.#results.showDebug(result, mark);
		} else {
			this.#results.show(result);
		}
		this.#inputPanel.setFinalState(result.finalState);
		if (!wasShown) {
			// the canvas is smaller now
			this.#canvas.fit();
		}
	}

	#stopDebug(): void {
		window.clearTimeout(this.#rerunTimer);
		const result = this.#results.result;
		if (result) {
			this.#results.show(result);
		}
		this.#canvas.setPath(null);
	}

	#closeResults(): void {
		window.clearTimeout(this.#rerunTimer);
		this.#results.clear();
		$("#center-panel").classList.remove("has-results");
		$("#center-splitter").hidden = true;
		$("#results").hidden = true;
		this.#canvas.setRun(null);
	}

	#markStale(): void {
		if (this.#results.result) {
			this.#results.setStale(true);
			this.#canvas.setStale(true);
		}
	}

	#bindResults(): void {
		const results = this.#results;
		initSplitter(
			$("#center-splitter"),
			$("#center-panel"),
			"sn-filter-sim.centerSplit",
			0.55,
		);
		results.addEventListener("filter", (e) =>
			this.#canvas.selectFilter((e as CustomEvent<string>).detail),
		);
		results.addEventListener("datum", (e) =>
			this.#canvas.setPath((e as CustomEvent<DatumPath | null>).detail),
		);
		results.addEventListener("usefinalstate", () =>
			this.#inputPanel.useFinalState(),
		);
		results.addEventListener("close", () => this.#closeResults());
		results.addEventListener("stop", () => this.#stopDebug());
		$("#run-button").addEventListener("click", () => void this.run());
		$("#debug-button").addEventListener("click", () => void this.run(true));
	}

	/**
	 * Get the Service Names of the simulated services that match a service
	 * filter, for a setting's Choose menu.
	 */
	#relatedServices(serviceFilter: string): string[] {
		if (serviceFilter.includes(METADATA_SERVICE_CLASS)) {
			return [
				NODE_METADATA_SERVICE_UID,
				...this.#inputs.userMetadataUids,
			];
		}
		if (serviceFilter.includes(FILTER_SERVICE_CLASS)) {
			return this.#ws.filters
				.map((f) => this.#ws.uid(f))
				.filter((uid): uid is string => !!uid);
		}
		return [];
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
			if (document.querySelector(".modal.show")) {
				return;
			}
			if ((e.ctrlKey || e.metaKey) && e.key === "Enter") {
				e.preventDefault();
				void this.run();
				return;
			}
			if (e.key === "F10" && this.#results.debugging) {
				e.preventDefault();
				this.#results.move(e.shiftKey ? "back" : "continue");
				return;
			}
			if (isTyping(e.target)) {
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
