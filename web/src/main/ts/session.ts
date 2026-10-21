import { RunInputs, type InputsState } from "./inputs.ts";
import type { AppInfo, FilterBundleInfo } from "./model.ts";
import { bundleLabel } from "./view.ts";
import { Workspace, type WorkspaceState } from "./workspace.ts";

/** The `format` of a simulation file. */
export const SIMULATION_FORMAT = "sn-filter-sim";

/** The name of saved simulation files. */
export const SIMULATION_FILE_NAME = "solarnode-filter-simulation.json";

/**
 * A custom filter type a simulation uses, so a simulator without its bundle
 * can say which bundle the simulation needs.
 */
export interface CustomFilterRef {
	factoryUid: string;
	name: string;
	bundle: FilterBundleInfo;
}

/**
 * A simulation file: everything needed to open a simulation in another
 * browser, such as to share it. It holds the workspace (the filters, their
 * canvas positions, and the External Settings) and the run inputs.
 */
export interface SimulationFile {
	format: typeof SIMULATION_FORMAT;
	version: 1;
	/** The simulator that saved the file. */
	app?: AppInfo | null;
	/** When the file was saved, as an ISO instant. */
	saved: string;
	workspace: WorkspaceState;
	inputs: InputsState;
	/** The custom filter types the filters use, with their bundles. */
	customFilters?: CustomFilterRef[];
}

/** The simulation a file holds. */
export interface Simulation {
	workspace: WorkspaceState;
	inputs: InputsState;
	customFilters: CustomFilterRef[];
}

/**
 * Get the custom filter types a workspace's filters use.
 *
 * @param ws the workspace
 * @param known the custom filter types of the simulation file opened last, for
 *     the filters whose types the simulator does not know
 * @returns the types, by name
 */
export function customFilterRefs(
	ws: Workspace,
	known: readonly CustomFilterRef[] = [],
): CustomFilterRef[] {
	const refs = new Map<string, CustomFilterRef>();
	for (const filter of ws.filters) {
		const uid = filter.factoryUid;
		if (refs.has(uid)) {
			continue;
		}
		const type = ws.type(uid) ?? ws.unavailableType(uid);
		if (type?.bundle) {
			refs.set(uid, {
				factoryUid: uid,
				name: type.name,
				bundle: type.bundle,
			});
		} else if (!type) {
			const ref = known.find((r) => r.factoryUid === uid);
			if (ref) {
				refs.set(uid, ref);
			}
		}
	}
	return [...refs.values()].sort((a, b) => a.name.localeCompare(b.name));
}

/**
 * Get notes about the custom filter types of an opened simulation that the
 * simulator does not have, or has from another bundle version.
 *
 * @param refs the simulation's custom filter types
 * @param ws the workspace
 * @returns the notes, which are empty if the simulator has all the types
 */
export function customFilterNotes(
	refs: readonly CustomFilterRef[],
	ws: Workspace,
): string[] {
	const notes: string[] = [];
	for (const ref of refs) {
		const name = `The filter type ${ref.name} (${ref.factoryUid})`;
		const saved = bundleLabel(ref.bundle);
		const type = ws.type(ref.factoryUid);
		const unavailable = ws.unavailableType(ref.factoryUid);
		if (type?.bundle) {
			if (type.bundle.version !== ref.bundle.version) {
				notes.push(
					`${name} was from ${saved}, but the simulator has ${bundleLabel(type.bundle)}.`,
				);
			}
		} else if (unavailable) {
			notes.push(
				`${name}, from ${saved}, is not available, so datum pass through its filters unchanged. ${unavailable.reason}`,
			);
		} else if (!type) {
			notes.push(
				`${name} is from ${saved}, which the simulator was not started with, so datum pass through its filters unchanged. To run them, start the simulator with that bundle's jar in --sim.filters.path.`,
			);
		}
	}
	return notes;
}

/**
 * Get a simulation file's content.
 *
 * @param ws the workspace
 * @param inputs the run inputs
 * @param app the simulator, if known
 * @param known the custom filter types of the simulation file opened last
 * @returns the file's JSON
 */
export function simulationJson(
	ws: Workspace,
	inputs: RunInputs,
	app: AppInfo | null,
	known: readonly CustomFilterRef[] = [],
): string {
	const customFilters = customFilterRefs(ws, known);
	const file: SimulationFile = {
		format: SIMULATION_FORMAT,
		version: 1,
		app: app ? { name: app.name, version: app.version } : null,
		saved: new Date().toISOString(),
		workspace: ws.state,
		inputs: structuredClone(inputs.state),
		...(customFilters.length ? { customFilters } : {}),
	};
	return JSON.stringify(file, null, "\t");
}

/**
 * Test if a file looks like a simulation file, rather than a settings CSV.
 *
 * @param file the file
 */
export function isSimulationFile(file: File): boolean {
	return (
		file.type === "application/json" ||
		file.name.toLowerCase().endsWith(".json")
	);
}

/**
 * Read a simulation file.
 *
 * @param text the file's content
 * @returns the simulation
 * @throws Error if the file is not a simulation file this version can open
 */
export function parseSimulation(text: string): Simulation {
	let data: Partial<SimulationFile> | null;
	try {
		data = JSON.parse(text) as Partial<SimulationFile> | null;
	} catch (e) {
		throw new Error(
			`The file is not valid JSON: ${e instanceof Error ? e.message : e}`,
			{ cause: e },
		);
	}
	if (
		typeof data !== "object" ||
		data === null ||
		data.format !== SIMULATION_FORMAT
	) {
		throw new Error("The file is not a simulation file.");
	}
	if (data.version !== 1) {
		throw new Error(
			"The file was saved by a newer version of the simulator, so it cannot be opened.",
		);
	}
	const workspace = Workspace.parseState(data.workspace);
	if (!workspace) {
		throw new Error("The file's filters are not valid.");
	}
	const inputs = RunInputs.parseState(data.inputs ?? { version: 1 });
	if (!inputs) {
		throw new Error("The file's run inputs are not valid.");
	}
	return {
		workspace,
		inputs,
		customFilters: parseCustomFilters(data.customFilters),
	};
}

function parseCustomFilters(data: unknown): CustomFilterRef[] {
	if (!Array.isArray(data)) {
		return [];
	}
	return data.filter(
		(r): r is CustomFilterRef =>
			typeof r === "object" &&
			r !== null &&
			typeof r.factoryUid === "string" &&
			typeof r.name === "string" &&
			typeof r.bundle === "object" &&
			r.bundle !== null &&
			typeof r.bundle.name === "string",
	);
}
