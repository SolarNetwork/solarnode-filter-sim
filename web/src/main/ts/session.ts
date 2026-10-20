import { RunInputs, type InputsState } from "./inputs.ts";
import type { AppInfo } from "./model.ts";
import { Workspace, type WorkspaceState } from "./workspace.ts";

/** The `format` of a simulation file. */
export const SIMULATION_FORMAT = "sn-filter-sim";

/** The name of saved simulation files. */
export const SIMULATION_FILE_NAME = "solarnode-filter-simulation.json";

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
}

/** The simulation a file holds. */
export interface Simulation {
	workspace: WorkspaceState;
	inputs: InputsState;
}

/**
 * Get a simulation file's content.
 *
 * @param ws the workspace
 * @param inputs the run inputs
 * @param app the simulator, if known
 * @returns the file's JSON
 */
export function simulationJson(
	ws: Workspace,
	inputs: RunInputs,
	app: AppInfo | null,
): string {
	const file: SimulationFile = {
		format: SIMULATION_FORMAT,
		version: 1,
		app,
		saved: new Date().toISOString(),
		workspace: ws.state,
		inputs: structuredClone(inputs.state),
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
	return { workspace, inputs };
}
