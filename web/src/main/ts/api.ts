import type {
	AppInfo,
	CaptureInfo,
	CaptureRequest,
	ExportStyle,
	ExternalSettings,
	ExternalSettingsFile,
	FilterTypeInfo,
	UnavailableFilterType,
	ProblemDetail,
	SettingsExport,
	SettingsForm,
	SettingsImport,
	SimConfig,
	SimulationRequest,
	SimulationResult,
} from "./model.ts";

/** An error response from the web API. */
export class ApiError extends Error {
	/**
	 * Constructor.
	 *
	 * @param message the error message
	 * @param status the HTTP status
	 * @param problem the problem details, if the response had them
	 */
	constructor(
		message: string,
		readonly status: number,
		readonly problem?: ProblemDetail,
	) {
		super(message);
		this.name = "ApiError";
	}
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
	const res = await fetch(path, init);
	if (!res.ok) {
		let problem: ProblemDetail | undefined;
		try {
			problem = (await res.json()) as ProblemDetail;
		} catch {
			// not a problem details response
		}
		throw new ApiError(
			problem?.detail ?? `The server responded with HTTP ${res.status}.`,
			res.status,
			problem,
		);
	}
	return (await res.json()) as T;
}

function postJson<T>(path: string, body: unknown): Promise<T> {
	return request<T>(path, {
		method: "POST",
		headers: { "Content-Type": "application/json" },
		body: JSON.stringify(body),
	});
}

/** Get the application information. */
export function getInfo(): Promise<AppInfo> {
	return request<AppInfo>("api/info");
}

/** Get the supported filter types, sorted by name. */
export function getFilterTypes(): Promise<FilterTypeInfo[]> {
	return request<FilterTypeInfo[]>("api/filter-types");
}

/**
 * Get the filter types the simulator knows of but cannot run, sorted by name.
 */
export function getUnavailableFilterTypes(): Promise<UnavailableFilterType[]> {
	return request<UnavailableFilterType[]>("api/filter-types/unavailable");
}

/**
 * Import a settings CSV file.
 *
 * @param csv the file content
 */
export function importSettings(csv: string): Promise<SettingsImport> {
	return request<SettingsImport>("api/settings/import", {
		method: "POST",
		headers: { "Content-Type": "text/csv; charset=utf-8" },
		body: csv,
	});
}

/**
 * Add the settings of a settings CSV file to a configuration, the way
 * SolarNode imports a file into its existing settings.
 *
 * @param config the configuration
 * @param csv the file content
 * @returns the import result, with the combined configuration
 */
export function mergeSettings(
	config: SimConfig,
	csv: string,
): Promise<SettingsImport> {
	return postJson<SettingsImport>("api/settings/merge", { config, csv });
}

/**
 * Combine External Settings files, the way SolarNode applies its auto settings
 * files.
 *
 * @param files the files
 */
export function combineExternalSettings(
	files: readonly ExternalSettingsFile[],
): Promise<ExternalSettings> {
	return postJson<ExternalSettings>("api/settings/external", { files });
}

/**
 * Export a configuration as a settings CSV file.
 *
 * @param config the configuration, combined with any external settings
 * @param style the file style
 * @param external the configuration of the external settings, whose settings
 *     are left out of the export
 */
export function exportSettings(
	config: SimConfig,
	style: ExportStyle,
	external?: SimConfig | null,
): Promise<SettingsExport> {
	return postJson<SettingsExport>("api/settings/export", {
		config,
		style,
		external: external ?? null,
	});
}

/**
 * Get the settings form of a filter.
 *
 * @param factoryUid the filter type
 * @param settings the filter settings
 */
export function getSettingsForm(
	factoryUid: string,
	settings: Record<string, string>,
): Promise<SettingsForm> {
	return postJson<SettingsForm>("api/settings-form", {
		factoryUid,
		settings,
	});
}

/**
 * Run a simulation.
 *
 * @param req the simulation request
 */
export function simulate(req: SimulationRequest): Promise<SimulationResult> {
	return postJson<SimulationResult>("api/simulate", req);
}

/**
 * Start capturing the datum a SolarNode captures: log in to the node and
 * subscribe to its datum.
 *
 * @param req the node and how to log in to it
 */
export function startCapture(req: CaptureRequest): Promise<CaptureInfo> {
	return postJson<CaptureInfo>("api/capture", req);
}

/**
 * Get the address of a capture's event stream, for an `EventSource`.
 *
 * @param id the capture ID
 */
export function captureEventsUrl(id: string): string {
	return `api/capture/${encodeURIComponent(id)}/events`;
}

/**
 * End a capture.
 *
 * @param id the capture ID
 * @param keepalive `true` to finish the request even if the page is closing
 */
export async function endCapture(id: string, keepalive = false): Promise<void> {
	await fetch(`api/capture/${encodeURIComponent(id)}`, {
		method: "DELETE",
		keepalive,
	});
}
