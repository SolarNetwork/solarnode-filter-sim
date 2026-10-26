import "../scss/style.scss";
import {
	ApiError,
	exportSettings,
	getFilterTypes,
	getInfo,
	getUnavailableFilterTypes,
	importSettings,
	mergeSettings,
	reloadCustomFilters,
	simulate,
} from "./api.ts";
import { Canvas } from "./canvas.ts";
import { askCapture, CapturePanel, capturedDatumJson } from "./capture.ts";
import {
	askConfirm,
	askImportMode,
	askNewSimulation,
	askOpenSimulation,
	askSaveName,
	showError,
	showMessages,
	showToast,
	type ImportMode,
} from "./dialogs.ts";
import { ExternalSettingsDialog } from "./externalSettings.ts";
import { InputPanel } from "./inputPanel.ts";
import {
	InputError,
	NODE_METADATA_SERVICE_UID,
	parseDatumList,
	RunInputs,
} from "./inputs.ts";
import { Inspector } from "./inspector.ts";
import { MAIN_KEY, type FilterNode } from "./layout.ts";
import {
	UNKNOWN_FACTORY_UID,
	type AppInfo,
	type ExportStyle,
	type FilterTypeInfo,
	type SimulationResult,
	type UnavailableFilterType,
} from "./model.ts";
import { renderPalette } from "./palette.ts";
import {
	filterRunStats,
	ResultsPanel,
	type DatumPath,
	type DebugMark,
} from "./results.ts";
import {
	customFilterNotes,
	isSimulationFile,
	type CustomFilterRef,
	parseSimulation,
	SIMULATION_FILE_NAME,
	simulationJson,
} from "./session.ts";
import { buildReport, REPORT_FILE_NAME } from "./report.ts";
import { initColumnSplitter, initSplitter } from "./splitter.ts";
import { Workspace } from "./workspace.ts";

/** The browser storage key of the hidden side panels. */
const PANELS_STORAGE_KEY = "sn-filter-sim.hiddenPanels";

/**
 * The browser storage key of the simulation's file name: the one it was
 * opened from or saved as last.
 */
const SIMULATION_NAME_STORAGE_KEY = "sn-filter-sim.simulationName";

/** The page title, without a simulation's file name. */
const APP_TITLE = document.title;

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
	readonly #capture: CapturePanel;
	readonly #info: Promise<AppInfo | null>;
	/**
	 * The custom filter types of the simulation file opened last, so saving
	 * keeps the bundles of those the simulator does not have.
	 */
	#openedCustomFilters: CustomFilterRef[] = [];
	/**
	 * The simulation's file name, from Open or the last Save, or `null` for a
	 * simulation never opened or saved.
	 */
	#simulationName: string | null = null;
	#running = false;
	#rerunTimer?: number;

	/**
	 * Constructor.
	 *
	 * @param types the supported filter types
	 * @param info the application info, for simulation files
	 */
	constructor(
		types: FilterTypeInfo[],
		unavailable: UnavailableFilterType[],
		info: Promise<AppInfo | null>,
	) {
		this.#info = info;
		try {
			this.#setSimulationName(
				localStorage.getItem(SIMULATION_NAME_STORAGE_KEY),
			);
		} catch {
			// no name
		}
		const ws = Workspace.load(types, unavailable);
		const inputs = RunInputs.load();
		this.#ws = ws;
		this.#inputs = inputs;
		this.#canvas = new Canvas($("#canvas"), ws, $("#routes-legend"));
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
			controlIds: () => inputs.controlIds,
			addControl: (controlId) => this.#inputPanel.addControl(controlId),
			conductorUids: () => inputs.conductorUids,
			addConductor: (uid) => this.#inputPanel.addConductor(uid),
		});
		this.#inputPanel = new InputPanel(inputs, ws);
		this.#results = new ResultsPanel($("#results"), ws);
		this.#capture = new CapturePanel($("#capture"));
		this.#renderPalette(types);
		const reloadButton = $<HTMLButtonElement>("#reload-filters-button");
		void info.then((i) => {
			reloadButton.hidden = !i?.customFilters;
		});
		reloadButton.addEventListener(
			"click",
			() => void this.reloadCustomFilters(reloadButton),
		);
		initSplitter(
			$("#left-splitter"),
			$("#left-panel"),
			"sn-filter-sim.leftSplit",
			0.55,
		);
		initColumnSplitter(
			$("#left-column-splitter"),
			$("#layout"),
			$("#left-panel"),
			"left",
			"--sim-left-size",
			"sn-filter-sim.leftWidth",
		);
		initColumnSplitter(
			$("#right-column-splitter"),
			$("#layout"),
			$("#inspector"),
			"right",
			"--sim-right-size",
			"sn-filter-sim.rightWidth",
		);

		this.#canvas.addEventListener("select", (e) =>
			this.#inspector.show((e as CustomEvent<FilterNode | null>).detail),
		);
		this.#canvas.addEventListener("datumselect", (e) =>
			this.#results.showDatum((e as CustomEvent<number>).detail),
		);
		this.#canvas.addEventListener("blankclick", () =>
			this.#results.clearDatum(),
		);
		this.#canvas.addEventListener("filedrop", (e) =>
			this.openFile((e as CustomEvent<File>).detail),
		);
		ws.addEventListener("change", () => {
			this.#refreshControls();
			this.#changed();
		});
		inputs.addEventListener("change", () => this.#changed());
		// the inspector warns about commands that are not allowed, and
		// controls and Control Conductors that are missing
		let allowed = inputs.state.allowedCommands;
		let controlIds = inputs.controlIds.join("\n");
		let conductorUids = inputs.conductorUids.join("\n");
		inputs.addEventListener("change", () => {
			const ids = inputs.controlIds.join("\n");
			const uids = inputs.conductorUids.join("\n");
			if (
				inputs.state.allowedCommands !== allowed ||
				ids !== controlIds ||
				uids !== conductorUids
			) {
				allowed = inputs.state.allowedCommands;
				controlIds = ids;
				conductorUids = uids;
				this.#inspector.refreshAlerts();
			}
		});
		this.#bindResults();
		this.#bindCapture();

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
	 * Set the simulation's file name, which the browser remembers, and show it
	 * in the page title.
	 *
	 * @param name the file name, or `null` for none
	 */
	#setSimulationName(name: string | null): void {
		this.#simulationName = name || null;
		document.title = this.#simulationName
			? `${this.#simulationName} - ${APP_TITLE}`
			: APP_TITLE;
		try {
			if (this.#simulationName) {
				localStorage.setItem(
					SIMULATION_NAME_STORAGE_KEY,
					this.#simulationName,
				);
			} else {
				localStorage.removeItem(SIMULATION_NAME_STORAGE_KEY);
			}
		} catch {
			// not saved
		}
	}

	/**
	 * Save the whole simulation as a file, which can be opened in another
	 * browser.
	 *
	 * This asks for the file name, starting with the simulation's name, and
	 * the simulation keeps the name.
	 *
	 * @returns `true` if the simulation was saved, or `false` if the file name
	 *     dialog was cancelled
	 */
	async saveSimulation(): Promise<boolean> {
		const name = await askSaveName(
			this.#simulationName ?? SIMULATION_FILE_NAME,
		);
		if (!name) {
			return false;
		}
		const json = simulationJson(
			this.#ws,
			this.#inputs,
			await this.#info,
			this.#openedCustomFilters,
		);
		download(json, name, "application/json");
		this.#setSimulationName(name);
		showToast(`The simulation was saved as ${name}.`);
		return true;
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
		if (!(this.#ws.isEmpty && this.#inputs.isEmpty)) {
			const answer = await askOpenSimulation(file.name);
			if (!answer) {
				return;
			}
			if (answer === "saveFirst" && !(await this.saveSimulation())) {
				return;
			}
		}
		this.#closeResults();
		// the workspace first, so the Entry Point input finds its filter
		this.#ws.replaceState(simulation.workspace, "Open simulation");
		this.#openedCustomFilters = simulation.customFilters;
		this.#setSimulationName(file.name);
		this.#inputs.replace(simulation.inputs);
		this.#inputPanel.reload();
		this.#canvas.select(null);
		this.#canvas.fit();
		const notes = customFilterNotes(simulation.customFilters, this.#ws);
		if (notes.length) {
			showMessages({
				title: "Simulation opened",
				summary: `Opened the simulation in ${file.name}, which uses custom filters that the simulator does not have as they were.`,
				notes,
			});
		} else {
			showToast(`Opened the simulation in ${file.name}.`);
		}
	}

	/**
	 * Start a new, empty simulation: clear the filters, External Settings, and
	 * run inputs. Unless the simulation is already empty, this asks first.
	 */
	async newSimulation(): Promise<void> {
		if (!(this.#ws.isEmpty && this.#inputs.isEmpty)) {
			const answer = await askNewSimulation();
			if (!answer) {
				return;
			}
			if (answer === "saveFirst" && !(await this.saveSimulation())) {
				return;
			}
		}
		this.#closeResults();
		this.#ws.reset("New simulation");
		this.#openedCustomFilters = [];
		this.#setSimulationName(null);
		this.#inputs.clear();
		this.#inputPanel.reload();
		this.#canvas.select(null);
		this.#canvas.fit();
		showToast(
			"Started a new simulation. Ctrl+Z brings back the filters, but not the run inputs.",
		);
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
			const environmentNotes = this.#inputs.importEnvironment(
				result.controls,
				result.conductors,
				mode === "replace",
			);
			if (environmentNotes.length) {
				this.#inputPanel.reload();
				notes.push(...environmentNotes);
			}
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
					summary: environmentNotes.length
						? `${summary} You can undo the filter changes with Ctrl+Z, but not the Controls tab changes.`
						: `${summary} You can undo the import with Ctrl+Z.`,
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

	/** Download the Execution Flow as a stand-alone HTML report. */
	async downloadReport(): Promise<void> {
		const content = this.#results.reportContent();
		if (!content) {
			return;
		}
		try {
			const html = await buildReport(content, await this.#info);
			download(html, REPORT_FILE_NAME, "text/html");
			showToast(`The report was saved as ${REPORT_FILE_NAME}.`);
		} catch (e) {
			showError("Report not saved", e);
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
		if (this.#capture.active) {
			if (!auto) {
				showToast(
					"End the datum capture first, to run the simulation with its datum.",
				);
			}
			return;
		}
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
		this.#refreshRunButtons();
		$<HTMLButtonElement>("#run-button")
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
		// the canvas first, as debug mode then highlights the cursor on it
		this.#canvas.setRun({
			stats: filterRunStats(result),
			output: result.stats.output,
			datum: result.datum,
			instructionRuns: result.instructionRuns,
		});
		if (debug) {
			this.#results.showDebug(result, mark);
		} else {
			this.#results.show(result);
		}
		// the canvas gets shorter, but keeps its zoom and pan, so what is at
		// the top stays where it is
		this.#refreshBottom();
		this.#inputPanel.setFinalState(result.finalState);
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
		this.#refreshBottom();
		this.#canvas.setRun(null);
	}

	/**
	 * Show the panel below the canvas: the datum capture while there is one,
	 * else the execution flow if there is a result.
	 */
	#refreshBottom(): void {
		const capturing = this.#capture.active;
		const results = this.#results.result !== null;
		$("#capture").hidden = !capturing;
		$("#results").hidden = capturing || !results;
		$("#center-panel").classList.toggle(
			"has-results",
			capturing || results,
		);
		$("#center-splitter").hidden = !(capturing || results);
	}

	#refreshRunButtons(): void {
		const capturing = this.#capture.active;
		$<HTMLButtonElement>("#run-button").disabled =
			this.#running || capturing;
		$<HTMLButtonElement>("#debug-button").disabled =
			this.#running || capturing;
		$<HTMLButtonElement>("#capture-button").disabled = capturing;
	}

	/* === Datum capture === */

	/**
	 * Capture the datum a real SolarNode captures, as input datum: ask for the
	 * node and its login, then show each datum as it arrives.
	 */
	async startCapture(): Promise<void> {
		if (this.#capture.active) {
			return;
		}
		const text = this.#inputs.state.datum;
		let existing: number | null = 0;
		if (text.trim()) {
			try {
				existing = parseDatumList(text, "Datum").length;
			} catch {
				existing = null;
			}
		}
		const capture = await askCapture(existing);
		if (!capture) {
			return;
		}
		this.#capture.start(
			capture,
			capture.mode === "add" ? (existing ?? 0) : 0,
		);
		this.#refreshBottom();
		this.#refreshRunButtons();
	}

	/**
	 * End the datum capture, and put its datum in the Datum input.
	 */
	endCapture(): void {
		const panel = this.#capture;
		const datum = panel.datum;
		const mode = panel.mode;
		if (datum.length) {
			let json: string;
			try {
				json = capturedDatumJson(this.#inputs.state.datum, datum, mode);
			} catch {
				showError(
					"The captured datum were not added",
					"The Datum input is not valid JSON, so the captured datum cannot be added to it. Fix it, then end the capture again, or discard the capture.",
				);
				return;
			}
			this.#inputPanel.setText("datum", json);
		}
		panel.clear();
		this.#refreshBottom();
		this.#refreshRunButtons();
		showToast(
			!datum.length
				? "The capture ended without any datum."
				: mode === "add"
					? `Added the ${datum.length} captured datum to the Datum input.`
					: `Replaced the Datum input with the ${datum.length} captured datum.`,
		);
	}

	/** End the datum capture without changing the Datum input. */
	async discardCapture(): Promise<void> {
		const count = this.#capture.datum.length;
		if (
			count &&
			!(await askConfirm({
				title: "Discard Capture",
				message: [`Discard the ${count} captured datum?`],
				detail: "The capture ends, and the Datum input stays as it is.",
				confirm: "Discard",
			}))
		) {
			return;
		}
		this.#capture.clear();
		this.#refreshBottom();
		this.#refreshRunButtons();
	}

	#bindCapture(): void {
		$("#capture-button").addEventListener(
			"click",
			() => void this.startCapture(),
		);
		this.#capture.addEventListener("end", () => this.endCapture());
		this.#capture.addEventListener(
			"discard",
			() => void this.discardCapture(),
		);
		// end the capture on the server too
		window.addEventListener("pagehide", () => this.#capture.stop(true));
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
		results.addEventListener("report", () => void this.downloadReport());
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
		$("#new-simulation-button").addEventListener(
			"click",
			() => void this.newSimulation(),
		);
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
		this.#bindPanelButtons();
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
				this.#results.clearDatum();
			}
		});
	}

	/**
	 * Bind the buttons that hide and show the side panels, which the browser
	 * remembers.
	 */
	#bindPanelButtons(): void {
		const layout = $("#layout");
		const panels = [
			{
				button: $<HTMLButtonElement>("#left-panel-button"),
				panel: $("#left-panel"),
				hiddenClass: "is-left-hidden",
				name: "inputs and filters",
			},
			{
				button: $<HTMLButtonElement>("#right-panel-button"),
				panel: $("#inspector"),
				hiddenClass: "is-right-hidden",
				name: "inspector",
			},
		];
		let saved: Record<string, boolean> = {};
		try {
			saved = JSON.parse(
				localStorage.getItem(PANELS_STORAGE_KEY) ?? "{}",
			) as Record<string, boolean>;
		} catch {
			// all shown
		}
		const show = (
			p: (typeof panels)[number],
			shown: boolean,
			save: boolean,
		) => {
			p.panel.hidden = !shown;
			layout.classList.toggle(p.hiddenClass, !shown);
			p.button.classList.toggle("active", shown);
			p.button.setAttribute("aria-pressed", String(shown));
			p.button.title = `${shown ? "Hide" : "Show"} the ${p.name} panel${shown ? ", to give the canvas more room" : ""}`;
			if (save) {
				saved[p.panel.id] = !shown;
				try {
					localStorage.setItem(
						PANELS_STORAGE_KEY,
						JSON.stringify(saved),
					);
				} catch {
					// not saved
				}
			}
		};
		for (const p of panels) {
			show(p, !saved[p.panel.id], false);
			p.button.addEventListener("click", () =>
				show(p, layout.classList.contains(p.hiddenClass), true),
			);
		}
	}

	#renderPalette(types: FilterTypeInfo[]): void {
		const ws = this.#ws;
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
	}

	/**
	 * Load the custom filters again, after rebuilding them, and update the
	 * palette, canvas, and inspector. The next run uses the new filters.
	 *
	 * @param button the Reload button, which is disabled while reloading
	 */
	async reloadCustomFilters(button: HTMLButtonElement): Promise<void> {
		button.disabled = true;
		let result;
		try {
			result = await reloadCustomFilters();
		} catch (e) {
			showError("Custom filters not reloaded", e);
			return;
		} finally {
			button.disabled = false;
		}
		this.#ws.setTypes(result.filterTypes, result.unavailable);
		this.#renderPalette(result.filterTypes);
		this.#inspector.show(this.#canvas.selection);
		const [first, ...details] = result.messages;
		// the first line is like "Custom filters: 1 available from 1 bundle"
		const summary = `Reloaded the custom filters${first ? `: ${first.replace(/^Custom filters: /, "")}` : ""}.`;
		if (details.some((m) => /^(Warning|Not available|Problem):/.test(m))) {
			showMessages({
				title: "Custom filters reloaded",
				summary,
				notes: details,
			});
		} else {
			showToast(summary);
		}
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
	let unavailable: UnavailableFilterType[] = [];
	try {
		[types, unavailable] = await Promise.all([
			getFilterTypes(),
			getUnavailableFilterTypes(),
		]);
	} catch (e) {
		showError("The simulator server is not available", e);
	}
	new App(types, unavailable, info);
}

if (document.readyState === "loading") {
	window.addEventListener("DOMContentLoaded", startApp);
} else {
	startApp();
}
