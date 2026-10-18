/**
 * Types for the simulator's web API, matching the records in the app's
 * `s10k.node.sim.filter.domain` package.
 */

/** The factory UID of a SolarNode user filter chain. */
export const CHAIN_FACTORY_UID = "net.solarnetwork.node.datum.xform.user";

/**
 * The factory UID of an external filter: one that a chain refers to by its
 * Service Name, but whose settings are not part of the configuration.
 */
export const EXTERNAL_FACTORY_UID = "external";

/** The Service Name setting key. */
export const UID_SETTING = "uid";

/** Application information. */
export interface AppInfo {
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
	/** For a chain, the `id` values of its filters, in execution order. */
	filterIds?: string[] | null;
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

/** A settings CSV file style. */
export type ExportStyle = "Organized" | "Plain";

/** An exported settings CSV file. */
export interface SettingsExport {
	csv: string;
	messages: SimulationMessage[];
}

/** An RFC 9457 problem details response. */
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
