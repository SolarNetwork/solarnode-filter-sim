import type {
	AppInfo,
	ExportStyle,
	FilterTypeInfo,
	ProblemDetail,
	SettingsExport,
	SettingsImport,
	SimConfig,
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
 * Export a configuration as a settings CSV file.
 *
 * @param config the configuration
 * @param style the file style
 */
export function exportSettings(
	config: SimConfig,
	style: ExportStyle,
): Promise<SettingsExport> {
	return postJson<SettingsExport>("api/settings/export", { config, style });
}
