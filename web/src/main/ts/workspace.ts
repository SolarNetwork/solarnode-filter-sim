import { instanceIdProblem } from "./instanceIds.ts";
import {
	CHAIN_FACTORY_UID,
	UNKNOWN_FACTORY_UID,
	UID_SETTING,
	type ExternalSettings,
	type ExternalSettingsFile,
	type FilterConfig,
	type FilterTypeInfo,
	type SimConfig,
	type UnavailableFilterType,
} from "./model.ts";
import { quoted } from "./view.ts";

/** A point on the canvas. */
export interface Point {
	x: number;
	y: number;
}

/** A position in a chain, where a filter is or can be inserted. */
export interface ChainSlot {
	/** The chain's filter id. */
	chainId: string;
	/** The index in the chain's filters. */
	index: number;
	/**
	 * `true` to put a filter in the Unassigned place at the index, instead of
	 * inserting it there.
	 */
	fill?: boolean;
}

/** The workspace state, which is saved in the browser. */
export interface WorkspaceState {
	version: 1;
	/** The filter configurations. */
	filters: FilterConfig[];
	/** The id of the Main chain: the filter the Datum Queue applies. */
	mainChainId: string;
	datumQueueComment?: string | null;
	/** The positions of filters that are not in a chain, by filter id. */
	positions: Record<string, Point>;
	/**
	 * `true` while the Main chain was created by the simulator, not imported or
	 * used, so an export leaves it out.
	 */
	implicitMain: boolean;
	/** The External Settings files, in the order they were added. */
	externalFiles?: ExternalSettingsFile[];
	/** The settings the External Settings files combine to, from the server. */
	external?: ExternalSettings | null;
}

/**
 * The filters as the simulation sees them: the project's own settings over the
 * External Settings.
 */
interface View {
	filters: FilterConfig[];
	byId: Map<string, FilterConfig>;
	mainChainId: string;
	/** The external filter of each filter with external settings, by id. */
	external: Map<string, FilterConfig>;
	/** The filters of each external chain, as view ids, by chain id. */
	externalMembers: Map<string, (string | null)[]>;
}

/** The detail of a workspace `change` event. */
export interface WorkspaceChange {
	/** What changed, for example "Add filter". */
	label: string;
	/** `false` if the change cannot affect the flow chart, such as a note. */
	drawing: boolean;
}

/**
 * Where a setting's value comes from: the External Settings (which the
 * simulation has no different value for), the simulation overriding an
 * external setting, or only the simulation.
 */
export type SettingSource = "external" | "override" | "project";

/** Setting changes: a value to set, or `null` to remove the setting. */
export type SettingChanges = Record<string, string | null>;

const STORAGE_KEY = "sn-filter-sim.workspace";
const MAX_UNDO = 100;
const SAVE_DELAY = 300;
const MAIN_INSTANCE_KEY = "Main";
const MAIN_UID = "Chain - Main";
const NEW_ID_PREFIX = "filter-";

/** The factory UID unknown filters had before they were renamed. */
const OLD_UNKNOWN_FACTORY_UID = "external";

/** How long, in milliseconds, edits of the same field merge into one undo step. */
const MERGE_TIME = 2000;

/**
 * Get a short name for a filter type, for new Service Names.
 *
 * @param type the filter type
 * @returns the short name, like "Expression" for "Expression Datum Filter"
 */
export function shortTypeName(type: FilterTypeInfo): string {
	if (type.chain) {
		return "Chain";
	}
	return type.name.replace(/ Datum Filter$/, "").replace(/ Filter$/, "");
}

/**
 * The filter configuration being edited: every filter, which chains they are
 * in, and the positions of the filters that are not in a chain.
 *
 * Changes emit a `change` event with a {@link WorkspaceChange} detail, can be
 * undone, and are saved in the browser's local storage.
 *
 * The state holds the project's own filters and settings, and the External
 * Settings beside them. The queries show the combination, as the simulation
 * runs it: an external filter's settings, with the project's settings for it
 * on top. Changing an external filter adds the project's own filter for it,
 * which holds just the changes.
 */
export class Workspace extends EventTarget {
	#state: WorkspaceState;
	#view?: View;
	#types: Map<string, FilterTypeInfo>;
	#unavailable: Map<string, UnavailableFilterType>;
	readonly #undo: string[] = [];
	readonly #redo: string[] = [];
	#saveTimer?: number;
	#mergeKey?: string;
	#mergeTime = 0;

	/**
	 * Constructor.
	 *
	 * @param types the supported filter types
	 * @param state the initial state, or `undefined` for an empty workspace
	 * @param unavailable the filter types the simulator knows of but cannot run
	 */
	constructor(
		types: FilterTypeInfo[],
		state?: WorkspaceState,
		unavailable: UnavailableFilterType[] = [],
	) {
		super();
		this.#types = new Map(types.map((t) => [t.factoryUid, t]));
		this.#unavailable = new Map(unavailable.map((t) => [t.factoryUid, t]));
		this.#state = state ?? this.#emptyState();
	}

	/**
	 * Load the workspace saved in the browser.
	 *
	 * @param types the supported filter types
	 * @param unavailable the filter types the simulator knows of but cannot run
	 * @returns the workspace, which is empty if nothing valid was saved
	 */
	static load(
		types: FilterTypeInfo[],
		unavailable: UnavailableFilterType[] = [],
	): Workspace {
		let state: WorkspaceState | undefined;
		try {
			const json = localStorage.getItem(STORAGE_KEY);
			if (json) {
				state = Workspace.parseState(JSON.parse(json));
			}
		} catch (e) {
			console.warn("Unable to load the saved workspace", e);
		}
		return new Workspace(types, state, unavailable);
	}

	/**
	 * Get a workspace state from saved data, such as a simulation file,
	 * updated from an earlier version of the simulator.
	 *
	 * @param data the saved data
	 * @returns the state, or `undefined` if the data is not a valid state
	 */
	static parseState(data: unknown): WorkspaceState | undefined {
		try {
			const state = migrate(
				structuredClone(data) as WorkspaceState | null,
			);
			return isValidState(state) ? state : undefined;
		} catch {
			return undefined;
		}
	}

	/* === Queries === */

	/** A copy of the state, to save in a simulation file. */
	get state(): WorkspaceState {
		return structuredClone(this.#state);
	}

	/** The filter configurations, combined with the External Settings. */
	get filters(): readonly FilterConfig[] {
		return this.#v().filters;
	}

	/** The Main chain id. */
	get mainChainId(): string {
		return this.#v().mainChainId;
	}

	/** The External Settings files, in the order they were added. */
	get externalFiles(): readonly ExternalSettingsFile[] {
		return this.#state.externalFiles ?? [];
	}

	/** The settings the External Settings files combine to, if there are any. */
	get externalSettings(): ExternalSettings | null {
		return this.#state.external ?? null;
	}

	/**
	 * Get the external filter of a filter: its settings from the External
	 * Settings, without the project's.
	 *
	 * @param id the filter id
	 * @returns the external filter, or `undefined` if the External Settings do
	 *     not have the filter
	 */
	externalFilter(id: string): FilterConfig | undefined {
		return this.#v().external.get(id);
	}

	/**
	 * Test if the External Settings have a filter.
	 *
	 * @param id the filter id
	 */
	isExternal(id: string): boolean {
		return this.#v().external.has(id);
	}

	/**
	 * Get the External Settings files a filter comes from: every file with
	 * settings for it, such as files that each add a member to a chain.
	 *
	 * @param id the filter id
	 * @returns the files, in the order they apply, or an empty list if the
	 *     External Settings do not have the filter
	 */
	externalFilesOf(id: string): string[] {
		const external = this.externalFilter(id);
		const origin = external
			? this.#state.external?.origins[external.id]
			: undefined;
		if (!origin) {
			return [];
		}
		const files = new Set([origin.file, ...Object.values(origin.settings)]);
		const order = (this.#state.external?.files ?? []).map((f) => f.name);
		return [...files].sort((a, b) => order.indexOf(a) - order.indexOf(b));
	}

	/**
	 * Get the External Settings file of each of a chain's places: the file
	 * that puts the same filter in the same place, so an export leaves it out.
	 *
	 * @param chainId the chain id
	 * @returns the file of each place, or `undefined` for places that are the
	 *     simulation's own, in order
	 */
	memberFiles(chainId: string): (string | undefined)[] {
		const members = this.members(chainId);
		const external = this.externalFilter(chainId);
		const origin = external
			? this.#state.external?.origins[external.id]
			: undefined;
		const externalMembers = this.#v().externalMembers.get(chainId);
		return members.map((id, i) =>
			origin && id !== null && externalMembers?.[i] === id
				? origin.settings[`transformUids[${i}]`]
				: undefined,
		);
	}

	/**
	 * Get why a filter cannot be taken out of a chain.
	 *
	 * @param slot where the filter is
	 * @returns the reason, or `undefined` if it can be taken out
	 */
	takeOutProblem(slot: ChainSlot): string | undefined {
		const file = this.memberFiles(slot.chainId)[slot.index];
		if (!file) {
			return undefined;
		}
		const chain = this.filter(slot.chainId);
		return `The External Settings file ${quoted(file)} puts this filter in ${chain ? quoted(this.label(chain)) : "the chain"}, so it can be overridden, but not removed.`;
	}

	/**
	 * Get where a setting's value comes from.
	 *
	 * @param id the filter id
	 * @param key the setting key
	 * @returns the source, or `undefined` if the setting has no value
	 */
	settingSource(id: string, key: string): SettingSource | undefined {
		const external = this.externalFilter(id)?.settings?.[key];
		const own = this.#state.filters.find((f) => f.id === id)?.settings?.[
			key
		];
		if (external !== undefined && external !== null) {
			return own === undefined || own === null || own === external
				? "external"
				: "override";
		}
		return own !== undefined && own !== null ? "project" : undefined;
	}

	/**
	 * Get the External Settings file of a setting.
	 *
	 * @param id the filter id
	 * @param key the setting key
	 * @returns the file, or `undefined` if the External Settings do not have
	 *     the setting
	 */
	externalSettingFile(id: string, key: string): string | undefined {
		const external = this.externalFilter(id);
		if (external?.settings?.[key] === undefined) {
			return undefined;
		}
		const origin = this.#state.external?.origins[external.id];
		return origin?.settings[key] ?? origin?.file;
	}

	/**
	 * Test if the simulation changes an external filter: a setting with a
	 * different value, its own chain filters, a comment, or notes.
	 *
	 * @param id the filter id
	 */
	hasOverrides(id: string): boolean {
		const external = this.externalFilter(id);
		const own = this.#state.filters.find((f) => f.id === id);
		if (!external || !own) {
			return false;
		}
		if (
			Object.entries(own.settings ?? {}).some(
				([k, v]) => external.settings?.[k] !== v,
			)
		) {
			return true;
		}
		const ownMembers =
			own.filterIds !== undefined &&
			own.filterIds !== null &&
			(external.filterIds
				? !this.#inheritsMembersOf(this.#state, own, external)
				: own.filterIds.length > 0);
		return (
			ownMembers ||
			!!own.comment ||
			Object.keys(own.notes ?? {}).length > 0
		);
	}

	/** The positions of filters that are not in a chain. */
	get positions(): Readonly<Record<string, Point>> {
		return this.#state.positions;
	}

	/** `true` if there is nothing but an empty Main chain. */
	get isEmpty(): boolean {
		const v = this.#v();
		return (
			v.filters.length === 1 && this.members(v.mainChainId).length === 0
		);
	}

	/** `true` if there is a change to undo. */
	get canUndo(): boolean {
		return this.#undo.length > 0;
	}

	/** `true` if there is an undone change to redo. */
	get canRedo(): boolean {
		return this.#redo.length > 0;
	}

	/**
	 * Get a filter.
	 *
	 * @param id the filter id
	 * @returns the filter, or `undefined` if there is no such filter
	 */
	filter(id: string): FilterConfig | undefined {
		return this.#v().byId.get(id);
	}

	/**
	 * Get a filter type.
	 *
	 * @param factoryUid the factory UID
	 * @returns the type, or `undefined` if the type is not supported
	 */
	type(factoryUid: string): FilterTypeInfo | undefined {
		return this.#types.get(factoryUid);
	}

	/**
	 * Replace the filter types, such as when the custom filters are reloaded.
	 * The filters stay the same, so this has no undo.
	 *
	 * @param types the supported filter types
	 * @param unavailable the filter types the simulator knows of but cannot run
	 */
	setTypes(
		types: FilterTypeInfo[],
		unavailable: UnavailableFilterType[],
	): void {
		this.#types = new Map(types.map((t) => [t.factoryUid, t]));
		this.#unavailable = new Map(unavailable.map((t) => [t.factoryUid, t]));
		this.#view = undefined;
		this.#changed("Reload filter types");
	}

	/**
	 * Get a filter type the simulator knows of but cannot run.
	 *
	 * @param factoryUid the factory UID
	 * @returns the type, or `undefined` if it is not one
	 */
	unavailableType(factoryUid: string): UnavailableFilterType | undefined {
		return this.#unavailable.get(factoryUid);
	}

	/**
	 * Test if a filter is a filter chain.
	 *
	 * @param filter the filter, or its id
	 */
	isChain(filter: FilterConfig | string | undefined): boolean {
		const f = typeof filter === "string" ? this.filter(filter) : filter;
		return f?.factoryUid === CHAIN_FACTORY_UID;
	}

	/**
	 * Test if a filter is an unknown filter, known only by its Service Name.
	 *
	 * @param filter the filter
	 */
	isUnknown(filter: FilterConfig): boolean {
		return filter.factoryUid === UNKNOWN_FACTORY_UID;
	}

	/**
	 * Test if the simulator supports a filter's type.
	 *
	 * @param filter the filter
	 */
	isSupported(filter: FilterConfig): boolean {
		return this.#types.has(filter.factoryUid);
	}

	/**
	 * Get the filters of a chain.
	 *
	 * @param chainId the chain id
	 * @returns the filter ids, in order, with `null` for Unassigned places
	 */
	members(chainId: string): readonly (string | null)[] {
		return this.filter(chainId)?.filterIds ?? [];
	}

	/**
	 * Get the chains that a filter is in.
	 *
	 * @param filterId the filter id
	 * @returns the chain ids
	 */
	chainsOf(filterId: string): string[] {
		return this.#v()
			.filters.filter((f) => f.filterIds?.includes(filterId))
			.map((f) => f.id);
	}

	/**
	 * Test if a filter is not in any chain, and is not the Main chain.
	 *
	 * @param filterId the filter id
	 */
	isRoot(filterId: string): boolean {
		return (
			filterId !== this.#v().mainChainId &&
			this.chainsOf(filterId).length === 0
		);
	}

	/**
	 * Get a filter's Service Name.
	 *
	 * @param filter the filter
	 * @returns the Service Name, or `undefined` if it has none
	 */
	uid(filter: FilterConfig): string | undefined {
		const uid = filter.settings?.[UID_SETTING]?.trim();
		return uid ? uid : undefined;
	}

	/**
	 * Get a label for a filter: its Service Name, or else its type and
	 * instance ID.
	 *
	 * @param filter the filter
	 */
	label(filter: FilterConfig): string {
		const uid = this.uid(filter);
		if (uid) {
			return uid;
		}
		const type = this.type(filter.factoryUid);
		const name = type ? shortTypeName(type) : filter.factoryUid;
		return filter.instanceKey ? `${name} ${filter.instanceKey}` : name;
	}

	/**
	 * Check a filter's instance ID: SolarNode's rules, and unique among the
	 * filters of the same type.
	 *
	 * @param filter the filter
	 * @returns the problem, or `undefined` if the instance ID is fine
	 */
	instanceKeyProblem(filter: FilterConfig): string | undefined {
		if (this.isUnknown(filter)) {
			return undefined;
		}
		const key = filter.instanceKey;
		if (key === null || key === undefined) {
			// one is assigned on export
			return undefined;
		}
		const problem = instanceIdProblem(key);
		if (problem) {
			return problem;
		}
		const same = this.#v().filters.find(
			(f) =>
				f.id !== filter.id &&
				f.factoryUid === filter.factoryUid &&
				f.instanceKey === key,
		);
		return same
			? `${quoted(this.label(same))} has the same instance ID, and instance IDs must be unique for each filter type.`
			: undefined;
	}

	/**
	 * Check a filter's Service Name.
	 *
	 * @param filter the filter
	 * @returns a warning, or `undefined` if the Service Name is fine
	 */
	uidWarning(filter: FilterConfig): string | undefined {
		const uid = this.uid(filter);
		if (!uid) {
			return "A chain can only use a filter that has a Service Name.";
		}
		const others = this.#v().filters.filter(
			(f) => f.id !== filter.id && this.uid(f) === uid,
		);
		if (others.length === 0) {
			return undefined;
		}
		return `${others.length === 1 ? "Another filter has" : `${others.length} other filters have`} this Service Name, so chains use only the first filter with it.`;
	}

	/**
	 * Get all the filters a chain contains, through its nested chains.
	 *
	 * @param chainId the chain id
	 * @returns the filter ids
	 */
	descendants(chainId: string): Set<string> {
		const result = new Set<string>();
		const visit = (id: string) => {
			for (const member of this.members(id)) {
				if (member !== null && !result.has(member)) {
					result.add(member);
					visit(member);
				}
			}
		};
		visit(chainId);
		return result;
	}

	/**
	 * Test if a filter can be inserted into a chain.
	 *
	 * @param filterId the filter id
	 * @param slot where to insert the filter
	 * @param moving `true` if the filter is moving from another position in
	 *     the same chain
	 */
	canInsert(filterId: string, slot: ChainSlot, moving = false): boolean {
		const chain = this.filter(slot.chainId);
		if (
			!this.isChain(chain) ||
			filterId === this.#v().mainChainId ||
			!this.filter(filterId)
		) {
			return false;
		}
		const members = this.members(slot.chainId);
		if (!moving && members.includes(filterId)) {
			// already in the chain
			return false;
		}
		if (slot.fill && members[slot.index] !== null) {
			return false;
		}
		// a chain cannot contain itself
		return !(
			this.isChain(filterId) &&
			(filterId === slot.chainId ||
				this.descendants(filterId).has(slot.chainId))
		);
	}

	/**
	 * Get the configuration for the web API, combined with the External
	 * Settings.
	 *
	 * @param forExport `true` to leave out the Main chain while the simulator
	 *     created it and it is still empty, unless the External Settings have it
	 * @returns the configuration
	 */
	toConfig(forExport = false): SimConfig {
		const s = this.#state;
		const v = this.#v();
		if (
			forExport &&
			s.implicitMain &&
			v.mainChainId === s.mainChainId &&
			!v.external.has(s.mainChainId) &&
			this.members(s.mainChainId).length === 0
		) {
			return {
				filters: v.filters.filter((f) => f.id !== s.mainChainId),
				mainChainId: null,
				datumQueueComment: s.datumQueueComment,
			};
		}
		return {
			filters: v.filters,
			mainChainId: v.mainChainId,
			datumQueueComment: s.datumQueueComment,
		};
	}

	/**
	 * Get the project's own configuration, without the External Settings, for
	 * adding imported settings to.
	 *
	 * @returns the configuration
	 */
	projectConfig(): SimConfig {
		const s = this.#state;
		return {
			filters: s.filters,
			mainChainId: s.mainChainId,
			datumQueueComment: s.datumQueueComment,
		};
	}

	/* === Changes === */

	/**
	 * Add a new filter.
	 *
	 * The filter gets a new instance ID and a unique Service Name.
	 *
	 * @param type the filter type
	 * @param at where to put the filter: a chain slot, or a canvas position
	 * @returns the new filter
	 */
	addFilter(type: FilterTypeInfo, at: ChainSlot | Point): FilterConfig {
		const filter: FilterConfig = {
			id: this.#newId(),
			factoryUid: type.factoryUid,
			instanceKey: this.#newInstanceKey(type.factoryUid),
			settings: { [UID_SETTING]: this.#newUid(shortTypeName(type)) },
		};
		if (type.chain) {
			filter.filterIds = [];
		}
		this.#commit(`Add ${shortTypeName(type)}`, (s) => {
			s.filters.push(filter);
			if ("chainId" in at) {
				this.#insertMember(s, filter.id, at);
			} else {
				s.positions[filter.id] = at;
			}
		});
		return filter;
	}

	/**
	 * Insert a filter that is not in a chain into a chain.
	 *
	 * @param filterId the filter id
	 * @param slot where to insert the filter
	 */
	insert(filterId: string, slot: ChainSlot): void {
		if (!this.canInsert(filterId, slot)) {
			return;
		}
		this.#commit("Connect filter", (s) => {
			this.#insertMember(s, filterId, slot);
			delete s.positions[filterId];
		});
	}

	/**
	 * Insert an Unassigned place into a chain, which moves the filters after
	 * it up one place.
	 *
	 * @param slot where to insert the place
	 */
	addUnassigned(slot: ChainSlot): void {
		if (!this.isChain(slot.chainId)) {
			return;
		}
		this.#commit("Add Unassigned", (s) => {
			this.#insertMember(s, null, {
				chainId: slot.chainId,
				index: slot.index,
			});
		});
	}

	/**
	 * Delete an Unassigned place from a chain, which moves the filters after it
	 * down one place.
	 *
	 * @param slot where the place is
	 */
	removeUnassigned(slot: ChainSlot): void {
		if (this.members(slot.chainId)[slot.index] !== null) {
			return;
		}
		this.#commit("Delete Unassigned", (s) => {
			this.#removeMember(s, slot);
		});
	}

	/**
	 * Move a filter from one chain position to another.
	 *
	 * @param from where the filter is
	 * @param to where to insert it, as a slot before the move
	 */
	move(from: ChainSlot, to: ChainSlot): void {
		const filterId = this.members(from.chainId)[from.index];
		if (!filterId) {
			return;
		}
		const sameChain = from.chainId === to.chainId;
		if (
			!this.canInsert(filterId, to, sameChain) ||
			(!sameChain && this.takeOutProblem(from))
		) {
			return;
		}
		const index =
			sameChain && to.index > from.index ? to.index - 1 : to.index;
		if (sameChain && index === from.index) {
			return;
		}
		this.#commit("Move filter", (s) => {
			this.#removeMember(s, from);
			this.#insertMember(s, filterId, { ...to, index });
		});
	}

	/**
	 * Remove a filter from a chain, without deleting it.
	 *
	 * @param from where the filter is
	 * @param position the canvas position for the filter, if it is then not in
	 *     any chain
	 */
	detach(from: ChainSlot, position?: Point): void {
		const filterId = this.members(from.chainId)[from.index];
		if (!filterId || this.takeOutProblem(from)) {
			return;
		}
		// in another chain, or in this chain more than once
		const inChain =
			this.chainsOf(filterId).some((id) => id !== from.chainId) ||
			this.members(from.chainId).filter((id) => id === filterId).length >
				1;
		this.#commit("Disconnect filter", (s) => {
			this.#removeMember(s, from);
			if (position && !inChain) {
				s.positions[filterId] = position;
			}
		});
	}

	/**
	 * Delete a filter, and remove it from every chain.
	 *
	 * The filters of a deleted chain are kept. The Main chain and external
	 * filters cannot be deleted.
	 *
	 * @param filterId the filter id
	 */
	remove(filterId: string): void {
		if (
			filterId === this.mainChainId ||
			!this.filter(filterId) ||
			this.isExternal(filterId)
		) {
			// an external filter would come back, as on a node
			return;
		}
		this.#commit("Delete filter", (s) => {
			s.filters = s.filters.filter((f) => f.id !== filterId);
			for (const f of s.filters) {
				if (f.filterIds?.includes(filterId)) {
					f.filterIds = f.filterIds.filter((id) => id !== filterId);
				}
			}
			delete s.positions[filterId];
		});
	}

	/**
	 * Remove the simulation's changes to an external filter, so it has only its
	 * external settings again.
	 *
	 * @param filterId the filter id
	 */
	removeOverrides(filterId: string): void {
		const external = this.externalFilter(filterId);
		if (!external || !this.hasOverrides(filterId)) {
			return;
		}
		this.#commit("Remove overrides", (s) => {
			const own = s.filters.find((f) => f.id === filterId);
			if (!own) {
				return;
			}
			if (own.id === s.mainChainId) {
				// the Main chain stays, using the external chain's filters again
				const uid = external.settings?.[UID_SETTING];
				own.settings = uid ? { [UID_SETTING]: uid } : {};
				own.filterIds = [];
				own.notes = null;
				own.comment = null;
				s.implicitMain = true;
				return;
			}
			s.filters = s.filters.filter((f) => f !== own);
			if (own.id !== external.id) {
				// the filter then has its external id
				for (const f of s.filters) {
					if (f.filterIds?.includes(own.id)) {
						f.filterIds = f.filterIds.map((id) =>
							id === own.id ? external.id : id,
						);
					}
				}
				if (s.positions[own.id]) {
					s.positions[external.id] = s.positions[own.id];
					delete s.positions[own.id];
				}
			}
		});
	}

	/**
	 * Change a filter's settings.
	 *
	 * Removing a setting removes its note too.
	 *
	 * @param filterId the filter id
	 * @param changes the changes
	 * @param mergeKey a key that merges the change into the previous undo step
	 *     if that had the same key and was recent, for typing
	 */
	updateSettings(
		filterId: string,
		changes: SettingChanges,
		mergeKey?: string,
	): void {
		if (!this.filter(filterId)) {
			return;
		}
		const drawing = UID_SETTING in changes;
		this.#commit(
			"Edit settings",
			(s) => {
				const f = this.#own(s, filterId)!;
				const settings = { ...(f.settings ?? {}) };
				const notes = { ...(f.notes ?? {}) };
				for (const [key, value] of Object.entries(changes)) {
					if (value === null) {
						delete settings[key];
						delete notes[key];
					} else {
						settings[key] = value;
					}
				}
				f.settings = settings;
				f.notes = Object.keys(notes).length > 0 ? notes : null;
				if (f.id === s.mainChainId) {
					s.implicitMain = false;
				}
			},
			mergeKey,
			drawing,
		);
	}

	/**
	 * Change a filter's instance ID.
	 *
	 * @param filterId the filter id
	 * @param instanceKey the instance ID
	 * @param mergeKey a key that merges the change into the previous undo step
	 */
	setInstanceKey(
		filterId: string,
		instanceKey: string,
		mergeKey?: string,
	): void {
		if (this.isExternal(filterId)) {
			// the instance ID is what matches it to its external settings
			return;
		}
		this.#update(filterId, "Edit instance ID", mergeKey, true, (f) => {
			f.instanceKey = instanceKey;
		});
	}

	/**
	 * Change a filter's comment.
	 *
	 * @param filterId the filter id
	 * @param comment the comment, or an empty string for none
	 * @param mergeKey a key that merges the change into the previous undo step
	 */
	setComment(filterId: string, comment: string, mergeKey?: string): void {
		this.#update(filterId, "Edit comment", mergeKey, false, (f) => {
			f.comment = comment.trim() ? comment : null;
		});
	}

	/**
	 * Change the note of a setting.
	 *
	 * @param filterId the filter id
	 * @param key the setting key
	 * @param note the note, or an empty string for none
	 * @param mergeKey a key that merges the change into the previous undo step
	 */
	setNote(
		filterId: string,
		key: string,
		note: string,
		mergeKey?: string,
	): void {
		this.#update(filterId, "Edit note", mergeKey, false, (f) => {
			const notes = { ...(f.notes ?? {}) };
			if (note.trim()) {
				notes[key] = note;
			} else {
				delete notes[key];
			}
			f.notes = Object.keys(notes).length > 0 ? notes : null;
		});
	}

	/**
	 * Set the canvas position of a filter that is not in a chain.
	 *
	 * @param filterId the filter id
	 * @param position the position
	 */
	setPosition(filterId: string, position: Point): void {
		this.#commit("Move filter", (s) => {
			s.positions[filterId] = position;
		});
	}

	/**
	 * Replace the configuration with an imported one.
	 *
	 * A configuration without a Main chain gets an empty one. If the Datum
	 * Queue's filter is not a chain, it is put in a new Main chain, because
	 * the canvas starts with the Main chain.
	 *
	 * @param config the configuration
	 * @returns notes about changes made to the configuration
	 */
	importConfig(config: SimConfig): string[] {
		const notes: string[] = [];
		const filters = structuredClone(config.filters ?? []);
		let mainChainId = config.mainChainId ?? undefined;
		let implicitMain = false;
		const main = filters.find((f) => f.id === mainChainId);
		if (!main) {
			mainChainId = undefined;
			implicitMain = true;
		} else if (main.factoryUid !== CHAIN_FACTORY_UID) {
			notes.push(
				`The Datum Queue's filter ${quoted(this.label(main))} is not a filter chain, so it is now the only filter of a new Main chain.`,
			);
			mainChainId = undefined;
		}
		this.#commit("Import settings", (s) => {
			s.filters = filters;
			s.positions = {};
			s.datumQueueComment = config.datumQueueComment;
			s.implicitMain = implicitMain;
			if (mainChainId) {
				s.mainChainId = mainChainId;
			} else {
				const chain = this.#newMainChain(s.filters);
				if (main) {
					chain.filterIds = [main.id];
				}
				s.filters.unshift(chain);
				s.mainChainId = chain.id;
			}
		});
		return notes;
	}

	/**
	 * Add imported settings: use the combined configuration of the simulation
	 * and a settings file, from the server.
	 *
	 * Filters keep their canvas positions. The simulator's empty Main chain is
	 * dropped when the file sets another Main chain.
	 *
	 * @param config the combined configuration
	 * @returns notes about changes made to the configuration
	 */
	mergeConfig(config: SimConfig): string[] {
		const notes: string[] = [];
		const filters = structuredClone(config.filters ?? []);
		let mainChainId = config.mainChainId ?? undefined;
		const main = filters.find((f) => f.id === mainChainId);
		if (!main) {
			mainChainId = undefined;
		} else if (main.factoryUid !== CHAIN_FACTORY_UID) {
			notes.push(
				`The Datum Queue's filter ${quoted(this.label(main))} is not a filter chain, so it is now the only filter of a new Main chain.`,
			);
			mainChainId = undefined;
		}
		this.#commit("Add settings", (s) => {
			const oldMainId = s.mainChainId;
			const oldMain = s.filters.find((f) => f.id === oldMainId);
			s.filters = filters;
			s.datumQueueComment = config.datumQueueComment;
			if (mainChainId) {
				s.mainChainId = mainChainId;
			} else {
				const chain = this.#newMainChain(s.filters);
				if (main) {
					chain.filterIds = [main.id];
				}
				s.filters.unshift(chain);
				s.mainChainId = chain.id;
			}
			const newMain = s.filters.find((f) => f.id === s.mainChainId);
			if (s.implicitMain && s.mainChainId !== oldMainId) {
				const unused = s.filters.find(
					(f) =>
						f.id === oldMainId &&
						(f.filterIds ?? []).length === 0 &&
						!s.filters.some((c) =>
							c.filterIds?.includes(oldMainId),
						),
				);
				if (unused) {
					s.filters = s.filters.filter((f) => f !== unused);
				}
			}
			// the Main chain is still the simulator's only while the file left it alone
			s.implicitMain =
				s.implicitMain &&
				s.mainChainId === oldMainId &&
				(newMain?.filterIds ?? []).length === 0 &&
				JSON.stringify(newMain?.settings) ===
					JSON.stringify(oldMain?.settings);
			s.positions = Object.fromEntries(
				Object.entries(s.positions).filter(([id]) =>
					s.filters.some((f) => f.id === id),
				),
			);
		});
		return notes;
	}

	/**
	 * Change the External Settings.
	 *
	 * @param files the files, in the order they were added
	 * @param external what the files combine to, from the server, or `null`
	 *     if there are no files
	 * @param label what changed, for example "Add External Settings"
	 */
	setExternalSettings(
		files: ExternalSettingsFile[],
		external: ExternalSettings | null,
		label: string,
	): void {
		this.#commit(label, (s) => {
			s.externalFiles = files.length > 0 ? files : undefined;
			s.external = files.length > 0 ? external : undefined;
		});
	}

	/**
	 * Replace the whole state, such as with a simulation file's.
	 *
	 * @param state the new state
	 * @param label what changed, for example "Open simulation"
	 */
	replaceState(state: WorkspaceState, label: string): void {
		const next = structuredClone(state);
		this.#commit(label, (s) => {
			s.filters = next.filters;
			s.mainChainId = next.mainChainId;
			s.datumQueueComment = next.datumQueueComment;
			s.positions = next.positions;
			s.implicitMain = next.implicitMain;
			s.externalFiles = next.externalFiles;
			s.external = next.external;
		});
	}

	/**
	 * Start again with an empty workspace: only the Main chain, and no
	 * External Settings. The change can be undone.
	 *
	 * @param label what changed, for example "New simulation"
	 */
	reset(label: string): void {
		this.replaceState(this.#emptyState(), label);
	}

	/** Undo the last change. */
	undo(): void {
		const previous = this.#undo.pop();
		this.#mergeKey = undefined;
		if (previous) {
			this.#redo.push(JSON.stringify(this.#state));
			this.#state = JSON.parse(previous) as WorkspaceState;
			this.#view = undefined;
			this.#changed("Undo");
		}
	}

	/** Redo the last undone change. */
	redo(): void {
		const next = this.#redo.pop();
		this.#mergeKey = undefined;
		if (next) {
			this.#undo.push(JSON.stringify(this.#state));
			this.#state = JSON.parse(next) as WorkspaceState;
			this.#view = undefined;
			this.#changed("Redo");
		}
	}

	/* === Internal === */

	#emptyState(): WorkspaceState {
		const main = this.#newMainChain([]);
		return {
			version: 1,
			filters: [main],
			mainChainId: main.id,
			positions: {},
			implicitMain: true,
		};
	}

	#newMainChain(filters: FilterConfig[]): FilterConfig {
		const chains = filters.filter(
			(f) => f.factoryUid === CHAIN_FACTORY_UID,
		);
		const keys = chains.map((f) => f.instanceKey ?? "");
		const uids = new Set(filters.map((f) => f.settings?.[UID_SETTING]));
		let uid = MAIN_UID;
		for (let n = 2; uids.has(uid); n++) {
			uid = `${MAIN_UID} ${n}`;
		}
		return {
			id: uniqueId(filters, "main"),
			factoryUid: CHAIN_FACTORY_UID,
			instanceKey: keys.includes(MAIN_INSTANCE_KEY)
				? nextInstanceKey(keys)
				: MAIN_INSTANCE_KEY,
			settings: { [UID_SETTING]: uid },
			filterIds: [],
		};
	}

	#newId(): string {
		return uniqueId(this.#v().filters, NEW_ID_PREFIX + "1");
	}

	#newInstanceKey(factoryUid: string): string {
		// external filters count, so an export cannot merge a new filter into one
		return nextInstanceKey(
			this.#v()
				.filters.filter(
					(f) => f.factoryUid === factoryUid && f.instanceKey,
				)
				.map((f) => f.instanceKey!),
		);
	}

	#newUid(name: string): string {
		const uids = new Set(
			this.#v().filters.map((f) => f.settings?.[UID_SETTING]),
		);
		for (let n = 1; ; n++) {
			const uid = `${name} ${n}`;
			if (!uids.has(uid)) {
				return uid;
			}
		}
	}

	#insertMember(
		s: WorkspaceState,
		filterId: string | null,
		slot: ChainSlot,
	): void {
		const chain = this.#ownChain(s, slot.chainId);
		if (!chain) {
			return;
		}
		const members = [...(chain.filterIds ?? [])];
		if (slot.fill && members[slot.index] === null) {
			// an Unassigned place, so the other filters keep their places
			members[slot.index] = filterId;
		} else {
			members.splice(Math.min(slot.index, members.length), 0, filterId);
		}
		chain.filterIds = members;
		if (chain.id === s.mainChainId) {
			s.implicitMain = false;
		}
	}

	#removeMember(s: WorkspaceState, slot: ChainSlot): void {
		const chain = this.#ownChain(s, slot.chainId);
		if (chain?.filterIds) {
			chain.filterIds = chain.filterIds.filter(
				(_, i) => i !== slot.index,
			);
		}
	}

	#update(
		filterId: string,
		label: string,
		mergeKey: string | undefined,
		drawing: boolean,
		change: (filter: FilterConfig) => void,
	): void {
		if (!this.filter(filterId)) {
			return;
		}
		this.#commit(
			label,
			(s) => change(this.#own(s, filterId)!),
			mergeKey,
			drawing,
		);
	}

	/**
	 * Get the project's own filter, to change it. For an external filter that
	 * the project has no settings for yet, this adds one without settings, so
	 * the external settings still apply.
	 *
	 * @param s the state being changed
	 * @param id the filter id
	 * @returns the filter, or `undefined` if there is no such filter
	 */
	#own(s: WorkspaceState, id: string): FilterConfig | undefined {
		const own = s.filters.find((f) => f.id === id);
		if (own) {
			return own;
		}
		const v = this.#v();
		const filter = v.byId.get(id);
		if (!filter || !v.external.has(id)) {
			return undefined;
		}
		const added: FilterConfig = {
			id,
			factoryUid: filter.factoryUid,
			instanceKey: filter.instanceKey,
			settings: {},
		};
		s.filters.push(added);
		return added;
	}

	/**
	 * Get the project's own chain, to change its filters. If the chain's
	 * filters come from the External Settings, the project's chain gets them
	 * first.
	 *
	 * @param s the state being changed
	 * @param id the chain id
	 * @returns the chain, or `undefined` if there is no such filter
	 */
	#ownChain(s: WorkspaceState, id: string): FilterConfig | undefined {
		const inherited = this.#inheritsMembers(s, id);
		const chain = this.#own(s, id);
		if (chain && inherited) {
			chain.filterIds = [...this.members(id)];
		}
		return chain;
	}

	/**
	 * Test if a chain's filters come from the External Settings.
	 *
	 * @param s the state
	 * @param id the chain id
	 */
	#inheritsMembers(s: WorkspaceState, id: string): boolean {
		const external = this.#v().external.get(id);
		if (!external) {
			return false;
		}
		const own = s.filters.find((f) => f.id === id);
		return own
			? this.#inheritsMembersOf(s, own, external)
			: !!external.filterIds;
	}

	/** Get the view, computing it after a change. */
	#v(): View {
		this.#view ??= this.#computeView();
		return this.#view;
	}

	/**
	 * Combine the project's filters with the External Settings.
	 *
	 * Filters are matched by type and instance ID, like SolarNode components.
	 * A matched filter has the external settings with the project's on top, and
	 * keeps the project's id. Chains whose filters come from the External
	 * Settings, and unknown filters, are resolved by Service Name, as chains are
	 * on a node.
	 */
	#computeView(): View {
		const s = this.#state;
		const ext = s.external?.config;
		const extFilters = ext?.filters ?? [];
		if (extFilters.length === 0) {
			return {
				filters: s.filters,
				byId: new Map(s.filters.map((f) => [f.id, f])),
				mainChainId: s.mainChainId,
				external: new Map(),
				externalMembers: new Map(),
			};
		}
		const component = (f: FilterConfig) =>
			f.factoryUid !== UNKNOWN_FACTORY_UID && f.instanceKey
				? `${f.factoryUid}\u0000${f.instanceKey}`
				: undefined;
		const extByComponent = new Map<string, FilterConfig>();
		for (const e of extFilters) {
			const c = component(e);
			if (c && !extByComponent.has(c)) {
				extByComponent.set(c, e);
			}
		}

		let filters: FilterConfig[] = [];
		const external = new Map<string, FilterConfig>();
		/** The id of each external filter in the view, by external id. */
		const ids = new Map<string, string>();
		/** The chains whose filters are external ids. */
		const externalMembers = new Set<string>();
		for (const p of s.filters) {
			const c = component(p);
			const e = c ? extByComponent.get(c) : undefined;
			if (!e || ids.has(e.id)) {
				filters.push(p);
				continue;
			}
			ids.set(e.id, p.id);
			external.set(p.id, e);
			const inherit = this.#inheritsMembersOf(s, p, e);
			if (inherit) {
				externalMembers.add(p.id);
			}
			const notes = { ...(e.notes ?? {}), ...(p.notes ?? {}) };
			filters.push({
				...p,
				settings: { ...(e.settings ?? {}), ...(p.settings ?? {}) },
				notes: Object.keys(notes).length > 0 ? notes : null,
				comment: p.comment ?? e.comment,
				filterIds: inherit ? e.filterIds : p.filterIds,
			});
		}
		const used = new Set(s.filters.map((f) => f.id));
		for (const e of extFilters) {
			if (e.factoryUid === UNKNOWN_FACTORY_UID || ids.has(e.id)) {
				continue;
			}
			const id = used.has(e.id) ? uniqueId(filters, `ext-${e.id}`) : e.id;
			used.add(id);
			ids.set(e.id, id);
			external.set(id, e);
			if (e.filterIds) {
				externalMembers.add(id);
			}
			filters.push({ ...e, id });
		}

		// resolve chain members by Service Name, like a node
		const byUid = new Map<string, string>();
		for (const f of filters) {
			const uid = this.uid(f);
			if (uid && !this.isUnknown(f) && !byUid.has(uid)) {
				byUid.set(uid, f.id);
			}
		}
		const extUnknowns = new Map(
			extFilters
				.filter((f) => f.factoryUid === UNKNOWN_FACTORY_UID)
				.map((f) => [f.id, f]),
		);
		const unknowns = new Map<string, FilterConfig>();
		const resolve = (
			id: string | null,
			fromExternal: boolean,
		): string | null => {
			if (id === null) {
				return null;
			}
			const mapped = fromExternal ? (ids.get(id) ?? id) : id;
			const unknown = fromExternal
				? extUnknowns.get(id)
				: filters.find(
						(f) =>
							f.id === mapped &&
							f.factoryUid === UNKNOWN_FACTORY_UID,
					);
			if (!unknown) {
				return mapped;
			}
			const uid = this.uid(unknown);
			const real = uid ? byUid.get(uid) : undefined;
			if (real) {
				return real;
			}
			if (fromExternal && !used.has(mapped)) {
				unknowns.set(mapped, unknown);
			}
			return mapped;
		};
		filters = filters.map((f) =>
			f.filterIds
				? {
						...f,
						filterIds: f.filterIds.map((id) =>
							resolve(id, externalMembers.has(f.id)),
						),
					}
				: f,
		);
		// the external chains' filters, to tell which places come from them
		const extMembers = new Map<string, (string | null)[]>();
		for (const [id, e] of external) {
			if (e.filterIds) {
				extMembers.set(
					id,
					e.filterIds.map((m) => resolve(m, true)),
				);
			}
		}
		// an unknown filter that the External Settings have is not unknown
		filters = filters.filter((f) => {
			const uid = this.isUnknown(f) ? this.uid(f) : undefined;
			return !(uid && byUid.has(uid));
		});
		filters.push(...unknowns.values());

		// the External Settings' Main chain, unless the project has its own
		let mainChainId = s.mainChainId;
		const extMain = ext?.mainChainId ? ids.get(ext.mainChainId) : undefined;
		const projectMain = s.filters.find((f) => f.id === s.mainChainId);
		if (
			extMain &&
			extMain !== s.mainChainId &&
			s.implicitMain &&
			(projectMain?.filterIds ?? []).length === 0 &&
			filters.find((f) => f.id === extMain)?.factoryUid ===
				CHAIN_FACTORY_UID
		) {
			mainChainId = extMain;
			// the simulator's empty Main chain is then not used
			if (!filters.some((f) => f.filterIds?.includes(s.mainChainId))) {
				filters = filters.filter((f) => f.id !== s.mainChainId);
			}
		}
		return {
			filters,
			byId: new Map(filters.map((f) => [f.id, f])),
			mainChainId,
			external,
			externalMembers: extMembers,
		};
	}

	/**
	 * Test if a project chain uses the filters of its external chain.
	 *
	 * @param s the state
	 * @param p the project's filter
	 * @param e the external filter
	 */
	#inheritsMembersOf(
		s: WorkspaceState,
		p: FilterConfig,
		e: FilterConfig,
	): boolean {
		return (
			!!e.filterIds &&
			(p.filterIds === undefined ||
				p.filterIds === null ||
				(p.id === s.mainChainId &&
					s.implicitMain &&
					p.filterIds.length === 0))
		);
	}

	#commit(
		label: string,
		change: (state: WorkspaceState) => void,
		mergeKey?: string,
		drawing = true,
	): void {
		const now = Date.now();
		const merge =
			mergeKey !== undefined &&
			mergeKey === this.#mergeKey &&
			now - this.#mergeTime < MERGE_TIME;
		if (!merge) {
			this.#undo.push(JSON.stringify(this.#state));
			if (this.#undo.length > MAX_UNDO) {
				this.#undo.shift();
			}
		}
		this.#mergeKey = mergeKey;
		this.#mergeTime = now;
		this.#redo.length = 0;
		const next = structuredClone(this.#state);
		change(next);
		this.#state = next;
		this.#view = undefined;
		this.#changed(label, drawing);
	}

	#changed(label: string, drawing = true): void {
		this.dispatchEvent(
			new CustomEvent<WorkspaceChange>("change", {
				detail: { label, drawing },
			}),
		);
		window.clearTimeout(this.#saveTimer);
		this.#saveTimer = window.setTimeout(() => this.#save(), SAVE_DELAY);
	}

	#save(): void {
		try {
			localStorage.setItem(STORAGE_KEY, JSON.stringify(this.#state));
		} catch (e) {
			console.warn("Unable to save the workspace", e);
		}
	}
}

/**
 * Update a saved workspace from an earlier version of the simulator.
 *
 * Unknown filters used to have the `external` factory UID, and ids starting
 * with `external:`.
 *
 * @param state the saved state
 * @returns the state
 */
function migrate(state: WorkspaceState | null): WorkspaceState | null {
	if (!state || !Array.isArray(state.filters)) {
		return state;
	}
	const oldPrefix = `${OLD_UNKNOWN_FACTORY_UID}:`;
	const rename = (id: string) =>
		id.startsWith(oldPrefix)
			? `${UNKNOWN_FACTORY_UID}:${id.substring(oldPrefix.length)}`
			: id;
	for (const f of state.filters) {
		if (f.factoryUid === OLD_UNKNOWN_FACTORY_UID) {
			f.factoryUid = UNKNOWN_FACTORY_UID;
			f.id = rename(f.id);
		}
		if (f.filterIds) {
			f.filterIds = f.filterIds.map((id) =>
				id === null ? null : rename(id),
			);
		}
	}
	if (state.positions) {
		state.positions = Object.fromEntries(
			Object.entries(state.positions).map(([id, p]) => [rename(id), p]),
		);
	}
	return state;
}

/**
 * Get the instance ID SolarNode gives a new instance: one more than the
 * number of instances, counting up past numbers in use.
 *
 * @param used the instance IDs in use for the filter type
 */
function nextInstanceKey(used: string[]): string {
	let n = used.length + 1;
	while (used.includes(String(n))) {
		n++;
	}
	return String(n);
}

/**
 * Get an id that no filter has.
 *
 * @param filters the filters
 * @param id the preferred id, ending in a number to count up from if it is
 *     in use
 */
function uniqueId(filters: FilterConfig[], id: string): string {
	const ids = new Set(filters.map((f) => f.id));
	if (!ids.has(id)) {
		return id;
	}
	const match = /^(.*?)(\d*)$/.exec(id)!;
	for (let n = Number(match[2] || 1) + 1; ; n++) {
		const candidate = `${match[1]}${n}`;
		if (!ids.has(candidate)) {
			return candidate;
		}
	}
}

function isValidState(
	state: WorkspaceState | null | undefined,
): state is WorkspaceState {
	return (
		!!state &&
		state.version === 1 &&
		Array.isArray(state.filters) &&
		state.filters.every(
			(f) =>
				typeof f?.id === "string" && typeof f.factoryUid === "string",
		) &&
		typeof state.mainChainId === "string" &&
		state.filters.some(
			(f) =>
				f.id === state.mainChainId &&
				f.factoryUid === CHAIN_FACTORY_UID,
		) &&
		typeof state.positions === "object" &&
		state.positions !== null
	);
}
