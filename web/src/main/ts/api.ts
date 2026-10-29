import type {
	AppInfo,
	CaptureInfo,
	CaptureRequest,
	ExportStyle,
	ExternalSettings,
	ExternalSettingsFile,
	CustomFiltersReload,
	DatumSourceTypeInfo,
	FilterTypeInfo,
	UnavailableFilterType,
	ProblemDetail,
	RunInfo,
	RunProgress,
	RunResult,
	DatumTrace,
	SettingsExport,
	SettingsForm,
	SettingsImport,
	SimConfig,
	SimulationRequest,
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

/** Get the supported datum source types, sorted by name. */
export function getDatumSourceTypes(): Promise<DatumSourceTypeInfo[]> {
	return request<DatumSourceTypeInfo[]>("api/datum-source-types");
}

/**
 * Load the custom filters again, from the custom filters path the simulator
 * was started with.
 */
export function reloadCustomFilters(): Promise<CustomFiltersReload> {
	return request<CustomFiltersReload>("api/filter-types/reload", {
		method: "POST",
	});
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
 * Get the settings form of a filter or datum source.
 *
 * @param factoryUid the filter or datum source type
 * @param settings the settings; a datum source's are its job's, like
 *     `schedule` and `jobService.datumDataSource.sourceId`
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
 * Start a simulation run on the server, which runs in the background.
 *
 * @param req the simulation request
 * @returns the run, to follow with {@link runEventsUrl}
 */
export function startRun(req: SimulationRequest): Promise<RunInfo> {
	return postJson<RunInfo>("api/runs", req);
}

/**
 * Get the address of a run's progress event stream, for an `EventSource`.
 *
 * @param id the run ID
 */
export function runEventsUrl(id: string): string {
	return `api/runs/${encodeURIComponent(id)}/events`;
}

/**
 * Get a run's progress.
 *
 * @param id the run ID
 */
export function getRunProgress(id: string): Promise<RunProgress> {
	return request<RunProgress>(`api/runs/${encodeURIComponent(id)}/progress`);
}

/**
 * Get the result of a run that has ended.
 *
 * @param id the run ID
 */
export function getRunResult(id: string): Promise<RunResult> {
	return request<RunResult>(`api/runs/${encodeURIComponent(id)}`);
}

/** The most traces {@link getRunTraces} loads at once. */
export const MAX_TRACES_PER_REQUEST = 200;

/**
 * Get the traces of some of a run's datum.
 *
 * @param id the run ID
 * @param seqs the datum sequence numbers, at most
 *     {@link MAX_TRACES_PER_REQUEST}
 * @returns the traces, in the order asked
 */
export function getRunTraces(
	id: string,
	seqs: readonly number[],
): Promise<DatumTrace[]> {
	return request<DatumTrace[]>(
		`api/runs/${encodeURIComponent(id)}/datum?seq=${seqs.join(",")}`,
	);
}

/**
 * Get the address that downloads a run's datum, as a JSON array in the
 * format of the Datum input.
 *
 * @param id the run ID
 * @param kind `output` for the datum the filters output, or `input` for each
 *     datum as it entered the Datum Queue
 */
export function runDatumUrl(id: string, kind: "output" | "input"): string {
	return `api/runs/${encodeURIComponent(id)}/datum.json?kind=${kind}`;
}

/**
 * Cancel a run, which keeps the result so far.
 *
 * @param id the run ID
 * @param keepalive `true` to finish the request even if the page is closing
 */
export async function cancelRun(id: string, keepalive = false): Promise<void> {
	await fetch(`api/runs/${encodeURIComponent(id)}`, {
		method: "DELETE",
		keepalive,
	});
}

/** How often, in milliseconds, a run's progress is asked for without events. */
const PROGRESS_POLL_INTERVAL = 500;

/**
 * Follow a run's progress until it ends, with its event stream, or by asking
 * for its progress if the stream fails.
 *
 * @param id the run ID
 * @param onProgress called with the progress as the run goes on
 * @returns the progress when the run ended
 */
export function waitForRun(
	id: string,
	onProgress: (progress: RunProgress) => void,
): Promise<RunProgress> {
	return new Promise((resolve, reject) => {
		const events = new EventSource(runEventsUrl(id));
		let polling = false;
		const poll = async () => {
			try {
				for (;;) {
					const progress = await getRunProgress(id);
					if (progress.state !== "Running") {
						resolve(progress);
						return;
					}
					onProgress(progress);
					await new Promise((r) =>
						window.setTimeout(r, PROGRESS_POLL_INTERVAL),
					);
				}
			} catch (e) {
				reject(e);
			}
		};
		const data = (e: Event) =>
			JSON.parse((e as MessageEvent<string>).data) as RunProgress;
		events.addEventListener("progress", (e) => onProgress(data(e)));
		events.addEventListener("done", (e) => {
			events.close();
			resolve(data(e));
		});
		events.addEventListener("error", () => {
			// the stream ended without a done event, such as for a run the
			// server no longer keeps (a 404), so ask for the progress
			if (events.readyState === EventSource.CLOSED && !polling) {
				polling = true;
				void poll();
			}
		});
	});
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
