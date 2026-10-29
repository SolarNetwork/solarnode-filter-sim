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

/** A supported datum source type. */
export interface DatumSourceTypeInfo {
	factoryUid: string;
	name: string;
	/** The description, as HTML. */
	description?: string;
	/** The poll schedule of a new datum source. */
	defaultSchedule: string;
	/** `Controls`, `Instructions`, and `TariffSchedule`. */
	capabilities?: string[];
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
	/** The id of the datum source the message is about. */
	datumSourceId?: string;
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
	/** The file's Mock, Modbus, and BACnet controls, for the Controls tab. */
	controls?: NodeControlConfig[] | null;
	/** The file's Control Conductors, for the Controls tab. */
	conductors?: ControlConductorConfig[] | null;
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
	/**
	 * Ant-style patterns of the source IDs to capture, separated by commas, or
	 * `null` for all.
	 */
	sourceIds: string | null;
}

/** A datum capture that started. */
export interface CaptureInfo {
	id: string;
	/** The node's web address. */
	url: string;
	/**
	 * The node's WebSocket topics the capture subscribed to, one for each
	 * source ID pattern.
	 */
	topics: string[];
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
	/** How the simulation of the filter or datum source differs from SolarNode. */
	limitation?: string;
	/**
	 * For a datum source that provides controls, the control IDs it provides
	 * with these settings, with placeholders resolved.
	 */
	controlIds?: string[];
}

/* === Simulation === */

/** A simulated User Metadata Service. */
export interface UserMetadataConfig {
	/** The Service Name, which filters use to find the service. */
	uid?: string | null;
	/** The metadata, as a SolarNetwork metadata JSON object. */
	metadata?: unknown;
}

/** The type of a SolarNode control's value. */
export type NodeControlPropertyType =
	"Boolean" | "Float" | "Integer" | "Percent" | "String";

/** The control types, in the order to offer them. */
export const NODE_CONTROL_TYPES: readonly NodeControlPropertyType[] = [
	"Boolean",
	"Float",
	"Integer",
	"Percent",
	"String",
];

/**
 * When a simulated control publishes a datum of its value: never, when a set
 * changes the value (like the Mock control), or after every successful set
 * (like the Modbus control).
 */
export type ControlPublishMode = "Never" | "Change" | "Update";

/**
 * The kind of simulated control: a Mock control, or an Operational Mode Switch,
 * a `Boolean` control whose value is whether its operational mode is active.
 */
export type ControlKind = "Mock" | "OperationalModeSwitch";

/** A simulated SolarNode control. */
export interface NodeControlConfig {
	controlId?: string | null;
	/** The control type, or `null` for `Boolean`. */
	type?: NodeControlPropertyType | null;
	/** The starting value, or `null` for none. */
	value?: string | null;
	/** When the control publishes a datum, or `null` for `Update`. */
	publish?: ControlPublishMode | null;
	/** `true` to persist the datum the control publishes. */
	persist?: boolean | null;
	/**
	 * The datum property name of the value the control publishes, or `null` for
	 * `val` (with a number also as `v`).
	 */
	propertyName?: string | null;
	/** The datum property type of the value, or `null` for `Status`. */
	propertyType?: ControlPropertyType | null;
	/** The kind of control, or `null` for `Mock`. */
	kind?: ControlKind | null;
	/** An Operational Mode Switch's operational mode. */
	mode?: string | null;
}

/** A datum property type a control's value can have. */
export type ControlPropertyType = "Instantaneous" | "Accumulating" | "Status";

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
	/** The simulated controls. */
	controls?: NodeControlConfig[] | null;
	/** The simulated Control Conductors. */
	conductors?: ControlConductorConfig[] | null;
}

/** A simulated SolarNode Control Conductor. */
export interface ControlConductorConfig {
	/**
	 * The conductor's SolarNode settings, like `uid`, `predicate.expression`,
	 * `taskConfigsCount`, and `taskConfigs[0].controlId`.
	 */
	settings?: Record<string, string> | null;
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
	 * The date of the first input datum without one, and the start of a run
	 * without input datum, as an ISO 8601 instant, or `null` for now.
	 */
	startDate?: string | null;
	/**
	 * How long the simulated clock keeps running after the last input datum,
	 * as an ISO 8601 duration like `PT5M`, so scheduled tasks can run.
	 */
	runAfter?: string | null;
	/** The paths of the external commands filters may run. */
	allowedCommands?: string[] | null;
	/**
	 * The schedule of the simulated Instruction Execution Job: every second
	 * of the minute that is a multiple of this number, or `null` for 10.
	 */
	instructionJobSeconds?: number | null;
}

/** A simulated SolarNode datum source, which the simulation polls. */
export interface DatumSourceConfig {
	/** The id of the datum source, unique in the request. */
	id: string;
	/** The factory UID of the datum source type. */
	factoryUid: string;
	/** The SolarNode instance ID, or `null`. */
	instanceKey?: string | null;
	/**
	 * The settings of the datum source's job, like `schedule` and
	 * `jobService.datumDataSource.sourceId`.
	 */
	settings?: Record<string, string> | null;
	/**
	 * When its controls publish a datum, or `null` for `Change`; as datum
	 * sources post only changes, any mode but `Never` publishes each one.
	 */
	publish?: ControlPublishMode | null;
	/** `true` to persist the datum its controls publish. */
	persist?: boolean | null;
}

/** A request to run a simulation. */
export interface SimulationRequest {
	config: SimConfig;
	/** The `id` of the filter to apply, or `null` for the Main chain. */
	entryPoint?: string | null;
	/** The input datum, as SolarNode datum JSON objects. */
	datum?: unknown[] | null;
	/** The datum sources the simulation polls for more input datum. */
	datumSources?: DatumSourceConfig[] | null;
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
	/**
	 * Input, Generated by a filter step, Scheduled by a filter's task,
	 * published while the Instruction Execution Job ran an Instruction, or
	 * Polled from a datum source.
	 */
	type: "Input" | "Generated" | "Scheduled" | "Instruction" | "Polled";
	/** For an input datum, its index in the request. */
	index?: number;
	/** For a generated datum, the datum being processed when it was generated. */
	parentSeq?: number;
	stepId?: number;
	/** For a generated datum, the filter that generated it. */
	filterId?: string;
	/**
	 * For a datum a control published when a filter set its value, the
	 * control ID.
	 */
	controlId?: string;
	/**
	 * For a datum published while the Instruction Execution Job ran an
	 * instruction, the instruction's `number`.
	 */
	instruction?: number;
	/** For a polled datum, the id of its datum source. */
	datumSourceId?: string;
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

/** The kinds of simulated component that handle instructions. */
export type InstructionHandlerType =
	| "Reactor"
	| "OperationalModes"
	| "Metadata"
	| "Control"
	| "OperationalModeSwitch"
	| "DatumSource"
	| "Conductor";

/** The simulated component that gave an instruction its status. */
export interface InstructionHandlerInfo {
	type: InstructionHandlerType;
	/**
	 * The control ID of a control or Operational Mode Switch, the Service
	 * Name of a Control Conductor or metadata service, or the id of a datum
	 * source.
	 */
	name?: string;
}

/** An instruction issued by a filter. */
export interface InstructionInfo {
	/**
	 * The instruction's number in the simulation, from 1, as SolarNode
	 * instruction IDs differ between runs.
	 */
	number: number;
	/** The SolarNode instruction ID. */
	id?: number;
	topic: string;
	parameters?: Record<string, string>;
	/** The instruction status, or missing if nothing handled it. */
	state?: string;
	/** The number of the instruction that issued this one. */
	parent?: number;
	/**
	 * For an instruction stored for the Instruction Execution Job to run
	 * later, its execution date.
	 */
	executeAt?: string;
	/** The status message. */
	message?: string;
	/** The status error code. */
	code?: string;
	/** For a Control Conductor task's `Signal`, the conductor's Service Name. */
	conductor?: string;
	/** For a Control Conductor task's `Signal`, the task number, from 1. */
	task?: number;
	/**
	 * The component that gave the instruction its status when it was
	 * executed, or missing if none did.
	 */
	handler?: InstructionHandlerInfo;
}

/** A stored instruction the simulated Instruction Execution Job ran. */
export interface InstructionRun {
	/** When the job ran the instruction. */
	date: string;
	/** The sequence number of the last datum processed before the run. */
	afterSeq?: number;
	/** The instruction, with its state after the run. */
	instruction: InstructionInfo;
	/** The other instructions issued while it ran. */
	instructions?: InstructionInfo[];
	/** The control values set while it ran. */
	controls?: ControlUpdate[];
	/** The sequence numbers of the datum published while it ran. */
	generated?: number[];
	/** The change to the active operational modes while it ran. */
	opModes?: OpModeChange;
	/** The datum whose filter step issued the instruction. */
	datumSeq?: number;
	/** The ID of the filter step that issued the instruction. */
	stepId?: number;
	/** The filter of the step that issued the instruction. */
	filterId?: string;
	/** The error the instruction threw. */
	error?: string;
}

/** A metric a filter saved. */
export interface MetricInfo {
	timestamp: string;
	/** The metric type, like `s` for a sample value. */
	type: string;
	name: string;
	value: number;
}

/**
 * A successful set of a simulated control's value, or a change of an
 * Operational Mode Switch's value without a set.
 */
export interface ControlUpdate {
	controlId: string;
	type: NodeControlPropertyType;
	/** The value before the set, or missing if the control had none. */
	previousValue?: string;
	/** The value the set gave the control, normalized for its type. */
	value: string;
	/** `true` if the control published a datum of the value. */
	published: boolean;
	/**
	 * For an Operational Mode Switch whose value changed without a set,
	 * because its mode changed some other way, the mode.
	 */
	mode?: string;
	/** For a control of a datum source, the id of the datum source. */
	component?: string;
	/**
	 * The value asked for, when the control took a different number, like a
	 * rate limited to the battery's maximum.
	 */
	requested?: string;
}

/** The state of a simulated control. */
export interface ControlInfo {
	controlId: string;
	type: NodeControlPropertyType;
	/** The value, or missing if the control has none. */
	value?: string;
	/** The starting value, or missing if the control had none. */
	initialValue?: string;
	/** When the value was last set, or missing if it never was. */
	modified?: string;
	/** How many times the value was set. */
	updates: number;
	/** The kind of control, or missing for `Mock`. */
	kind?: ControlKind;
	/** An Operational Mode Switch's operational mode. */
	mode?: string;
	/** For a control of a datum source, the id of the datum source. */
	component?: string;
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
	/** The controls the filter set. */
	controls?: ControlUpdate[];
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
	/** The simulated controls. */
	controls?: ControlInfo[];
	/** The instructions waiting for the Instruction Execution Job. */
	instructions?: InstructionInfo[];
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
	/** The number of datum polled from datum sources. */
	polled?: number;
}

/** The result of a simulation. */
export interface SimulationResult {
	datum: DatumTrace[];
	/** The stored instructions the Instruction Execution Job ran, in order. */
	instructionRuns?: InstructionRun[];
	messages: SimulationMessage[];
	finalState: SimulationState;
	stats: SimulationStats;
}
