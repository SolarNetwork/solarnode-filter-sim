import { instanceIdProblem } from "./instanceIds.ts";
import {
	CHAIN_FACTORY_UID,
	UNKNOWN_FACTORY_UID,
	UID_SETTING,
	type FilterConfig,
	type FilterTypeInfo,
	type SimConfig,
} from "./model.ts";

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
}

/** The detail of a workspace `change` event. */
export interface WorkspaceChange {
	/** What changed, for example "Add filter". */
	label: string;
	/** `false` if the change cannot affect the flow chart, such as a note. */
	drawing: boolean;
}

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
 */
export class Workspace extends EventTarget {
	#state: WorkspaceState;
	readonly #types: Map<string, FilterTypeInfo>;
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
	 */
	constructor(types: FilterTypeInfo[], state?: WorkspaceState) {
		super();
		this.#types = new Map(types.map((t) => [t.factoryUid, t]));
		this.#state = state ?? this.#emptyState();
	}

	/**
	 * Load the workspace saved in the browser.
	 *
	 * @param types the supported filter types
	 * @returns the workspace, which is empty if nothing valid was saved
	 */
	static load(types: FilterTypeInfo[]): Workspace {
		let state: WorkspaceState | undefined;
		try {
			const json = localStorage.getItem(STORAGE_KEY);
			if (json) {
				const saved = migrate(
					JSON.parse(json) as WorkspaceState | null,
				);
				if (isValidState(saved)) {
					state = saved;
				}
			}
		} catch (e) {
			console.warn("Unable to load the saved workspace", e);
		}
		return new Workspace(types, state);
	}

	/* === Queries === */

	/** The filter configurations. */
	get filters(): readonly FilterConfig[] {
		return this.#state.filters;
	}

	/** The Main chain id. */
	get mainChainId(): string {
		return this.#state.mainChainId;
	}

	/** The positions of filters that are not in a chain. */
	get positions(): Readonly<Record<string, Point>> {
		return this.#state.positions;
	}

	/** `true` if there is nothing but an empty Main chain. */
	get isEmpty(): boolean {
		return (
			this.#state.filters.length === 1 &&
			this.members(this.#state.mainChainId).length === 0
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
		return this.#state.filters.find((f) => f.id === id);
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
	 * @returns the filter ids, in order
	 */
	members(chainId: string): readonly string[] {
		return this.filter(chainId)?.filterIds ?? [];
	}

	/**
	 * Get the chains that a filter is in.
	 *
	 * @param filterId the filter id
	 * @returns the chain ids
	 */
	chainsOf(filterId: string): string[] {
		return this.#state.filters
			.filter((f) => f.filterIds?.includes(filterId))
			.map((f) => f.id);
	}

	/**
	 * Test if a filter is not in any chain, and is not the Main chain.
	 *
	 * @param filterId the filter id
	 */
	isRoot(filterId: string): boolean {
		return (
			filterId !== this.#state.mainChainId &&
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
		const same = this.#state.filters.find(
			(f) =>
				f.id !== filter.id &&
				f.factoryUid === filter.factoryUid &&
				f.instanceKey === key,
		);
		return same
			? `${this.label(same)} has the same instance ID, and instance IDs must be unique for each filter type.`
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
		const others = this.#state.filters.filter(
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
				if (!result.has(member)) {
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
			filterId === this.#state.mainChainId ||
			!this.filter(filterId)
		) {
			return false;
		}
		if (!moving && this.members(slot.chainId).includes(filterId)) {
			// already in the chain
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
	 * Get the configuration for the web API.
	 *
	 * @param forExport `true` to leave out the Main chain while the simulator
	 *     created it and it is still empty
	 * @returns the configuration
	 */
	toConfig(forExport = false): SimConfig {
		const s = this.#state;
		if (
			forExport &&
			s.implicitMain &&
			this.members(s.mainChainId).length === 0
		) {
			return {
				filters: s.filters.filter((f) => f.id !== s.mainChainId),
				mainChainId: null,
				datumQueueComment: s.datumQueueComment,
			};
		}
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
		if (!this.canInsert(filterId, to, sameChain)) {
			return;
		}
		const index =
			sameChain && to.index > from.index ? to.index - 1 : to.index;
		if (sameChain && index === from.index) {
			return;
		}
		this.#commit("Move filter", (s) => {
			this.#removeMember(s, from);
			this.#insertMember(s, filterId, { chainId: to.chainId, index });
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
		if (!filterId) {
			return;
		}
		this.#commit("Disconnect filter", (s) => {
			this.#removeMember(s, from);
			const inChain = s.filters.some((f) =>
				f.filterIds?.includes(filterId),
			);
			if (position && !inChain) {
				s.positions[filterId] = position;
			}
		});
	}

	/**
	 * Delete a filter, and remove it from every chain.
	 *
	 * The filters of a deleted chain are kept. The Main chain cannot be
	 * deleted.
	 *
	 * @param filterId the filter id
	 */
	remove(filterId: string): void {
		if (filterId === this.#state.mainChainId || !this.filter(filterId)) {
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
				const f = s.filters.find((f) => f.id === filterId)!;
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
				`The Datum Queue's filter ${this.label(main)} is not a filter chain, so it is now the only filter of a new Main chain.`,
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
				`The Datum Queue's filter ${this.label(main)} is not a filter chain, so it is now the only filter of a new Main chain.`,
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

	/** Undo the last change. */
	undo(): void {
		const previous = this.#undo.pop();
		this.#mergeKey = undefined;
		if (previous) {
			this.#redo.push(JSON.stringify(this.#state));
			this.#state = JSON.parse(previous) as WorkspaceState;
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
		return uniqueId(this.#state.filters, NEW_ID_PREFIX + "1");
	}

	#newInstanceKey(factoryUid: string): string {
		return nextInstanceKey(
			this.#state.filters
				.filter((f) => f.factoryUid === factoryUid && f.instanceKey)
				.map((f) => f.instanceKey!),
		);
	}

	#newUid(name: string): string {
		const uids = new Set(
			this.#state.filters.map((f) => f.settings?.[UID_SETTING]),
		);
		for (let n = 1; ; n++) {
			const uid = `${name} ${n}`;
			if (!uids.has(uid)) {
				return uid;
			}
		}
	}

	#insertMember(s: WorkspaceState, filterId: string, slot: ChainSlot): void {
		const chain = s.filters.find((f) => f.id === slot.chainId);
		if (!chain) {
			return;
		}
		const members = [...(chain.filterIds ?? [])];
		members.splice(Math.min(slot.index, members.length), 0, filterId);
		chain.filterIds = members;
		if (chain.id === s.mainChainId) {
			s.implicitMain = false;
		}
	}

	#removeMember(s: WorkspaceState, slot: ChainSlot): void {
		const chain = s.filters.find((f) => f.id === slot.chainId);
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
			(s) => change(s.filters.find((f) => f.id === filterId)!),
			mergeKey,
			drawing,
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
			f.filterIds = f.filterIds.map(rename);
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
