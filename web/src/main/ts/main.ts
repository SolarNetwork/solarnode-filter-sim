import "../scss/style.scss";
import {
	ApiError,
	exportSettings,
	getFilterTypes,
	getInfo,
	importSettings,
	mergeSettings,
	simulate,
} from "./api.ts";
import { Canvas } from "./canvas.ts";
import {
	askImportMode,
	askOpenSimulation,
	showError,
	showMessages,
	showToast,
	type ImportMode,
} from "./dialogs.ts";
import { ExternalSettingsDialog } from "./externalSettings.ts";
import { InputPanel } from "./inputPanel.ts";
import { InputError, NODE_METADATA_SERVICE_UID, RunInputs } from "./inputs.ts";
import { Inspector } from "./inspector.ts";
import { MAIN_KEY, type FilterNode } from "./layout.ts";
import {
	UNKNOWN_FACTORY_UID,
	type AppInfo,
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
import {
	isSimulationFile,
	parseSimulation,
	SIMULATION_FILE_NAME,
	simulationJson,
} from "./session.ts";
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
 * @returns the application info, or `null` if the server is unavailable
 */
async function showAppInfo(el: HTMLElement): Promise<AppInfo | null> {
	try {
		const info = await getInfo();
		el.textContent = info.version ? `v${info.version}` : info.name;
		return info;
	} catch (e) {
		console.error("Unable to get application info", e);
		el.textContent = "Server unavailable";
		el.classList.add("text-danger");
		return null;
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
	readonly #info: Promise<AppInfo | null>;
	#running = false;
	#rerunTimer?: number;

	/**
	 * Constructor.
	 *
	 * @param types the supported filter types
	 * @param info the application info, for simulation files
	 */
	constructor(types: FilterTypeInfo[], info: Promise<AppInfo | null>) {
		this.#info = info;
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
			isCommandAllowed: (command) =>
				inputs.allowedCommands.includes(command),
			allowCommand: (command) => inputs.allowCommand(command),
		});
		this.#inputPanel = new InputPanel(inputs, ws);
		this.#results = new ResultsPanel($("#results"), ws);
		renderPalette(
			$("#palette"),
			types,
			(type) => {
				const main = ws.mainChainId;
				const filter = ws.addFilter(type, {
					chainId: main,
					index: ws.members(main).length,
				});
				this.#canvas.selectFilter(filter.id);
			},
			() => {
				const main = ws.mainChainId;
				const index = ws.members(main).length;
				ws.addUnassigned({ chainId: main, index });
				this.#canvas.select(`${MAIN_KEY}/${index}`);
			},
		);
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
			this.openFile((e as CustomEvent<File>).detail),
		);
		ws.addEventListener("change", () => {
			this.#refreshControls();
			this.#changed();
		});
		inputs.addEventListener("change", () => this.#changed());
		// the inspector warns about commands that are not allowed
		let allowed = inputs.state.allowedCommands;
		inputs.addEventListener("change", () => {
			if (inputs.state.allowedCommands !== allowed) {
				allowed = inputs.state.allowedCommands;
				this.#inspector.refreshAlerts();
			}
		});
		this.#bindResults();

		this.#bindControls();
		this.#refreshControls();
		this.#inspector.show(this.#canvas.selection);
		this.#canvas.fit();
	}

	/**
	 * Open a file: a simulation file, or else a settings CSV file to import.
	 *
	 * @param file the file
	 */
	async openFile(file: File): Promise<void> {
		if (isSimulationFile(file)) {
			await this.openSimulation(file);
		} else {
			await this.importFile(file);
		}
	}

	/**
	 * Save the whole simulation as a file, which can be opened in another
	 * browser.
	 */
	async saveSimulation(): Promise<void> {
		const json = simulationJson(this.#ws, this.#inputs, await this.#info);
		download(json, SIMULATION_FILE_NAME, "application/json");
		showToast(`The simulation was saved as ${SIMULATION_FILE_NAME}.`);
	}

	/**
	 * Open a simulation file, replacing the simulation.
	 *
	 * Unless the simulation is empty, this asks first.
	 *
	 * @param file the file
	 */
	async openSimulation(file: File): Promise<void> {
		let simulation;
		try {
			simulation = parseSimulation(await file.text());
		} catch (e) {
			showError("Simulation not opened", e);
			return;
		}
		if (
			!(this.#ws.isEmpty && this.#inputs.isEmpty) &&
			!(await askOpenSimulation(file.name))
		) {
			return;
		}
		this.#closeResults();
		// the workspace first, so the Entry Point input finds its filter
		this.#ws.replaceState(simulation.workspace, "Open simulation");
		this.#inputs.replace(simulation.inputs);
		this.#inputPanel.reload();
		this.#canvas.select(null);
		this.#canvas.fit();
		showToast(`Opened the simulation in ${file.name}.`);
	}

	/**
	 * Import a settings CSV file.
	 *
	 * Unless the simulation is empty, this asks whether to add the file's
	 * settings to the simulation, or replace the simulation's settings with
	 * them.
	 *
	 * @param file the file
	 */
	async importFile(file: File): Promise<void> {
		let mode: ImportMode = "replace";
		if (!this.#ws.isEmpty) {
			const choice = await askImportMode(file.name);
			if (!choice) {
				return;
			}
			mode = choice;
		}
		try {
			const csv = await file.text();
			const before = new Set(this.#ws.filters.map((f) => f.id));
			const result =
				mode === "add"
					? await mergeSettings(this.#ws.projectConfig(), csv)
					: await importSettings(csv);
			const notes =
				mode === "add"
					? this.#ws.mergeConfig(result.config)
					: this.#ws.importConfig(result.config);
			this.#canvas.select(null);
			this.#canvas.fit();
			const filters = (result.config.filters ?? []).filter(
				(f) => f.factoryUid !== UNKNOWN_FACTORY_UID,
			);
			let summary: string;
			if (mode === "add") {
				const added = filters.filter((f) => !before.has(f.id)).length;
				summary = `Added the settings in ${file.name}, with ${added} new filter${added === 1 ? "" : "s"}.`;
			} else {
				const count = filters.length;
				summary = `Imported ${count} filter${count === 1 ? "" : "s"} from ${file.name}.`;
			}
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
			const result = await exportSettings(
				this.#ws.toConfig(true),
				style,
				this.#ws.externalSettings?.config,
			);
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
			datum: result.datum,
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
		results.addEventListener("allowcommand", (e) => {
			const command = (e as CustomEvent<string>).detail;
			this.#inputs.allowCommand(command);
			showToast(
				`${command} is now in the Allowed Commands, on the Environment tab. Run again to use it.`,
			);
		});
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
		const simulationInput = $<HTMLInputElement>("#simulation-file");
		$("#open-simulation-button").addEventListener("click", () =>
			simulationInput.click(),
		);
		simulationInput.addEventListener("change", () => {
			const file = simulationInput.files?.[0];
			simulationInput.value = "";
			if (file) {
				void this.openSimulation(file);
			}
		});
		$("#save-simulation-button").addEventListener(
			"click",
			() => void this.saveSimulation(),
		);

		const external = new ExternalSettingsDialog(ws);
		$("#external-button").addEventListener("click", () => external.show());
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
				this.openFile(file);
			}
		});

		$("#undo-button").addEventListener("click", () => ws.undo());
		$("#redo-button").addEventListener("click", () => ws.redo());
		$("#zoom-in-button").addEventListener("click", () => canvas.zoom(1.2));
		$("#zoom-out-button").addEventListener("click", () =>
			canvas.zoom(1 / 1.2),
		);
		$("#fit-button").addEventListener("click", () => canvas.fit());
		const routesButton = $<HTMLButtonElement>("#routes-button");
		const showRoutes = (show: boolean) => {
			canvas.showRoutes = show;
			routesButton.classList.toggle("active", show);
			routesButton.setAttribute("aria-pressed", String(show));
		};
		showRoutes(canvas.showRoutes);
		routesButton.addEventListener("click", () =>
			showRoutes(!canvas.showRoutes),
		);

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
					if (node.unassigned) {
						if (node.slot) {
							ws.removeUnassigned(node.slot);
						}
					} else if (ws.isExternal(node.filterId)) {
						showToast(
							"This filter comes from the External Settings, so it can be overridden, but not removed. You can remove the simulation's changes to it in the inspector.",
						);
					} else {
						ws.remove(node.filterId);
					}
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
	const info = showAppInfo($("#app-version"));
	let types: FilterTypeInfo[] = [];
	try {
		types = await getFilterTypes();
	} catch (e) {
		showError("The simulator server is not available", e);
	}
	new App(types, info);
}

if (document.readyState === "loading") {
	window.addEventListener("DOMContentLoaded", startApp);
} else {
	startApp();
}
