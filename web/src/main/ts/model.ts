/**
 * Types for the simulator's web API, matching the records in the app's
 * `s10k.node.sim.filter.domain` package.
 */

/** The factory UID of a SolarNode user filter chain. */
export const CHAIN_FACTORY_UID = "net.solarnetwork.node.datum.xform.user";

/**
 * The factory UID of an unknown filter: one that a chain refers to by its
 * Service Name, but whose settings are not part of the configuration.
 */
export const UNKNOWN_FACTORY_UID = "unknown";

/** The Service Name setting key. */
export const UID_SETTING = "uid";

/**
 * The name of a chain place without a filter: the Service Name an export
 * writes for it, which no filter has.
 */
export const UNASSIGNED_UID = "Unassigned";

/** Application information. */
export interface AppInfo {
	name: string;
	version?: string;
	/**
	 * `true` if the simulator was started with a custom filters path, so the
	 * custom filters can be reloaded.
	 */
	customFilters?: boolean;
}

/** The bundle a custom filter type comes from. */
export interface FilterBundleInfo {
	/** The bundle symbolic name, or the jar file name if it has none. */
	name: string;
	version?: string;
}

/** A supported filter type. */
export interface FilterTypeInfo {
	factoryUid: string;
	name: string;
	/** The description, as HTML. */
	description?: string;
	chain: boolean;
	capabilities?: string[];
	/** The bundle of a custom filter type, from the custom filters path. */
	bundle?: FilterBundleInfo;
}

/** The result of reloading the custom filters. */
export interface CustomFiltersReload {
	filterTypes: FilterTypeInfo[];
	unavailable: UnavailableFilterType[];
	/**
	 * The description of the custom filters, like the startup message's: the
	 * jars and their filter types, warnings, the types that are not available,
	 * and problems.
	 */
	messages: string[];
}

/**
 * A filter type the simulator knows of but cannot run, such as a custom filter
 * type that needs a service the simulator does not provide.
 */
export interface UnavailableFilterType {
	factoryUid: string;
	/** The display name, or the factory UID if it is not known. */
	name: string;
	/** The bundle of a custom filter type. */
	bundle?: FilterBundleInfo;
	/** Why the type is not available, as a sentence. */
	reason: string;
}

/** The configuration of one filter instance. */
export interface FilterConfig {
	/** A unique identifier, assigned by the client. */
	id: string;
	factoryUid: string;
	/** The instance ID used in settings CSV files. */
	instanceKey?: string | null;
	/** The settings, as SolarNode setting keys and values. */
	settings?: Record<string, string> | null;
	/**
	 * For a chain, the `id` values of its filters, in execution order. Each
	 * index is the filter's `transformUids` index, and `null` is an Unassigned
	 * place, which SolarNode skips.
	 */
	filterIds?: (string | null)[] | null;
	/** The comment lines that introduce the filter in a settings CSV file. */
	comment?: string | null;
	/** Notes about individual settings, by setting key. */
	notes?: Record<string, string> | null;
}

/** A complete filter configuration. */
export interface SimConfig {
	filters?: FilterConfig[] | null;
	/** The `id` of the filter the Datum Queue applies. */
	mainChainId?: string | null;
	datumQueueComment?: string | null;
}

/** A message level. */
export type MessageLevel = "Info" | "Warning" | "Error";

/** A message about a simulation, import, or export. */
export interface SimulationMessage {
	level: MessageLevel;
	code: string;
	message: string;
	filterId?: string;
	datumSeq?: number;
}

/** A component of a settings CSV file that is not a filter. */
export interface IgnoredComponent {
	key: string;
	instanceKey?: string;
	settingCount: number;
}

/** The result of importing a settings CSV file. */
export interface SettingsImport {
	config: SimConfig;
	messages: SimulationMessage[];
	ignored: IgnoredComponent[];
}

/** An External Settings file: a settings CSV file the simulation applies first. */
export interface ExternalSettingsFile {
	name: string;
	csv: string;
}

/** A summary of an External Settings file. */
export interface ExternalSettingsSummary {
	name: string;
	/** The setting rows, including `.FACTORY` rows. */
	settingCount: number;
	/** `false` if SolarNode cannot import the file, as an error message says. */
	applied: boolean;
	/** The external filters the file has settings for. */
	filterIds: string[];
	ignored: IgnoredComponent[];
	messages: SimulationMessage[];
}

/** Where a filter's external settings come from. */
export interface ExternalFilterOrigin {
	/** The first file with settings for the filter. */
	file: string;
	/** The file of each setting, by setting key. */
	settings: Record<string, string>;
}

/** External Settings files, combined like SolarNode's auto settings files. */
export interface ExternalSettings {
	config: SimConfig;
	/** A summary of each file, in the order they apply. */
	files: ExternalSettingsSummary[];
	/** Where each external filter's settings come from, by external filter id. */
	origins: Record<string, ExternalFilterOrigin>;
	/** The file with the Datum Queue setting. */
	datumQueueFile?: string | null;
	messages: SimulationMessage[];
}

/** A settings CSV file style. */
export type ExportStyle = "Organized" | "Plain";

/** An exported settings CSV file. */
export interface SettingsExport {
	csv: string;
	messages: SimulationMessage[];
}

/** An RFC 9457 problem details response. */
/** A request to capture the datum a SolarNode captures. */
export interface CaptureRequest {
	/** The node's web address, like `http://solarnode.local`. */
	url: string;
	username: string;
	password: string;
	/** An Ant-style pattern of the source IDs to capture, or `null` for all. */
	sourceIds: string | null;
}

/** A datum capture that started. */
export interface CaptureInfo {
	id: string;
	/** The node's web address. */
	url: string;
	/** The node's WebSocket topic the capture subscribed to. */
	topic: string;
}

/** The `state` event of a capture's event stream. */
export interface CaptureState {
	state: "Capturing" | "Ended";
	/** The number of datum captured. */
	count: number;
	/** `true` if the sample type of each property was guessed. */
	guessed: boolean;
	/** Why the capture ended. */
	message?: string;
	/** `true` if the capture ended because of a problem. */
	error?: boolean;
}

export interface ProblemDetail {
	title?: string;
	detail?: string;
	status?: number;
	problems?: SimulationMessage[];
}

/** A settings form field type. */
export type SettingFieldType =
	| "Title"
	| "Text"
	| "TextArea"
	| "Cron"
	| "Toggle"
	| "Slider"
	| "Radio"
	| "Select"
	| "Group";

/** A choice of a select or radio group setting. */
export interface SettingOption {
	value: string;
	label: string;
	/** The description, as HTML. */
	description?: string;
}

/**
 * A settings form field. Components that do not apply to the type are
 * missing or `false`.
 */
export interface SettingField {
	type: SettingFieldType;
	key?: string;
	label?: string;
	/** The description, as HTML. */
	description?: string;
	/** The current value: the setting's value, or else its default value. */
	value?: string;
	defaultValue?: string;
	transient: boolean;
	readOnly: boolean;
	markup: boolean;
	secure: boolean;
	relatedServiceFilter?: string;
	options?: SettingOption[];
	trueValue?: string;
	falseValue?: string;
	min?: number;
	max?: number;
	step?: number;
	dynamic: boolean;
	/** For a dynamic list, the key of the setting with its number of items. */
	countKey?: string;
	fields?: SettingField[];
}

/** A problem applying a setting. */
export interface SettingProblem {
	key: string;
	code: string;
	message: string;
}

/** A filter's settings form. */
export interface SettingsForm {
	factoryUid: string;
	fields: SettingField[];
	problems: SettingProblem[];
	/** How the simulation of the filter differs from SolarNode. */
	limitation?: string;
}

/* === Simulation === */

/** A simulated User Metadata Service. */
export interface UserMetadataConfig {
	/** The Service Name, which filters use to find the service. */
	uid?: string | null;
	/** The metadata, as a SolarNetwork metadata JSON object. */
	metadata?: unknown;
}

/** The starting state of the simulated node. */
export interface SimulationEnvironment {
	/** The active operational modes. */
	opModes?: string[] | null;
	/** Local State, in the SolarNode Local State CSV format. */
	localStateCsv?: string | null;
	/** The metadata of the Node Metadata Service. */
	nodeMetadata?: unknown;
	userMetadata?: UserMetadataConfig[] | null;
	/** Placeholder values, by name. */
	placeholders?: Record<string, string> | null;
	/** Datum stream metadata, by source ID. */
	datumMetadata?: Record<string, unknown> | null;
	/** The node location, which the Location Service provides. */
	nodeLocation?: NodeLocation | null;
}

/** The location of the simulated node. */
export interface NodeLocation {
	/** The latitude, in decimal degrees. */
	latitude?: number | null;
	/** The longitude, in decimal degrees. */
	longitude?: number | null;
	/** The elevation above sea level, in meters. */
	elevation?: number | null;
}

/** Datum to pre-load into the datum histories. */
export interface SimulationHistory {
	filtered?: unknown[] | null;
	/** The unfiltered history, or `null` to use the same datum as `filtered`. */
	unfiltered?: unknown[] | null;
}

/** The level of detail of a simulation result. */
export type SimulationDetail = "Summary" | "Debug";

/** Simulation options. */
export interface SimulationOptions {
	maxDatum?: number | null;
	maxGenerationDepth?: number | null;
	timeoutMs?: number | null;
	detail?: SimulationDetail | null;
	/**
	 * How long the simulated clock keeps running after the last input datum,
	 * as an ISO 8601 duration like `PT5M`, so scheduled tasks can run.
	 */
	runAfter?: string | null;
	/** The paths of the external commands filters may run. */
	allowedCommands?: string[] | null;
}

/** A request to run a simulation. */
export interface SimulationRequest {
	config: SimConfig;
	/** The `id` of the filter to apply, or `null` for the Main chain. */
	entryPoint?: string | null;
	/** The input datum, as SolarNode datum JSON objects. */
	datum?: unknown[] | null;
	history?: SimulationHistory | null;
	environment?: SimulationEnvironment | null;
	options?: SimulationOptions | null;
}

/** Datum sample properties, in the SolarNode datum JSON style. */
export interface SamplesInfo {
	i?: Record<string, unknown>;
	a?: Record<string, unknown>;
	s?: Record<string, unknown>;
	t?: string[];
}

/** A snapshot of a datum. */
export interface DatumInfo {
	sourceId?: string;
	timestamp?: string;
	locationId?: number;
	samples: SamplesInfo;
}

/** Where a processed datum came from. */
export interface DatumOrigin {
	/** Input, Generated by a filter step, or Scheduled by a filter's task. */
	type: "Input" | "Generated" | "Scheduled";
	/** For an input datum, its index in the request. */
	index?: number;
	/** For a generated datum, the datum being processed when it was generated. */
	parentSeq?: number;
	stepId?: number;
	/** For a generated datum, the filter that generated it. */
	filterId?: string;
}

/** The outcome of a filter step. */
export type FilterStepStatus =
	"Changed" | "Unchanged" | "Dropped" | "Skipped" | "Error" | "Unsupported";

/** A change to the active operational modes. */
export interface OpModeChange {
	enabled?: string[];
	disabled?: string[];
}

/** A change to one Local State entry. */
export interface LocalStateChange {
	key: string;
	/** The type, or missing if the entry was deleted. */
	type?: string;
	value?: unknown;
	previousValue?: unknown;
}

/** An instruction issued by a filter. */
export interface InstructionInfo {
	topic: string;
	parameters?: Record<string, string>;
	state?: string;
}

/** A metric a filter saved. */
export interface MetricInfo {
	timestamp: string;
	/** The metric type, like `s` for a sample value. */
	type: string;
	name: string;
	value: number;
}

/** An external command a filter asked to run. */
export interface CommandInfo {
	/** The command and its arguments. */
	command: string[];
	/** `false` if the simulator did not allow the command. */
	ran: boolean;
	/** The command's JSON result, if it succeeded. */
	result?: Record<string, unknown>;
	/** Why the command failed, or did not run. */
	error?: string;
	durationMicros?: number;
}

/** One filter invocation while processing a datum. */
export interface FilterStep {
	id: number;
	filterId: string;
	uid?: string;
	factoryUid: string;
	/** The chain nesting depth, from 0 for the entry filter. */
	depth?: number;
	status: FilterStepStatus;
	input?: SamplesInfo;
	/** The samples the filter returned, for a `Changed` step only. */
	output?: SamplesInfo;
	outputTimestamp?: string;
	parameters?: Record<string, unknown>;
	opModes?: OpModeChange;
	localState?: LocalStateChange[];
	/** The sequence numbers of datum the filter generated. */
	generated?: number[];
	instructions?: InstructionInfo[];
	metrics?: MetricInfo[];
	commands?: CommandInfo[];
	/** For a `Skipped` step, why the filter ignored the datum. */
	skipReasons?: SkipReason[];
	error?: string;
	durationMicros?: number;
}

/**
 * Why a filter ignored a datum: a condition the datum did not meet, with what
 * the condition requires and what the datum or node had instead.
 */
export interface SkipReason {
	condition: "SourceId" | "RequiredMode" | "RequiredTag" | "Other";
	/** The Source ID pattern, Required Mode, or Required Tag expression. */
	expected?: string;
	/**
	 * The datum's source ID, the active modes, or the datum's tags, both
	 * comma-delimited and empty for none.
	 */
	actual?: string;
}

/** The trace of one datum through the Datum Queue. */
export interface DatumTrace {
	seq: number;
	origin: DatumOrigin;
	input: DatumInfo;
	/** `false` if the datum is not persisted after filtering. */
	persist?: boolean;
	steps?: FilterStep[];
	/** The datum after the filters, or missing if they discarded it. */
	output?: DatumInfo;
	error?: string;
	/** The node state when the datum started processing, for debug results. */
	startState?: SimulationState;
}

/** A Local State entry. */
export interface LocalStateInfo {
	key: string;
	type: string;
	value?: unknown;
	created?: string;
	modified?: string;
}

/** The state of the simulated node. */
export interface SimulationState {
	opModes?: string[];
	localState?: LocalStateInfo[];
	/** The metrics filters saved, oldest first. */
	metrics?: MetricInfo[];
	/** The Local State, in the SolarNode Local State CSV format. */
	localStateCsv?: string;
	/** The datum history, by source ID, newest first. */
	history?: Record<string, DatumInfo[]>;
	/** The unfiltered datum history, by source ID, newest first. */
	unfilteredHistory?: Record<string, DatumInfo[]>;
	/** The sequence numbers of datum waiting in the Datum Queue. */
	queue?: number[];
}

/** Simulation statistics. */
export interface SimulationStats {
	processed: number;
	generated: number;
	output: number;
	discarded: number;
	durationMs: number;
}

/** The result of a simulation. */
export interface SimulationResult {
	datum: DatumTrace[];
	messages: SimulationMessage[];
	finalState: SimulationState;
	stats: SimulationStats;
}
