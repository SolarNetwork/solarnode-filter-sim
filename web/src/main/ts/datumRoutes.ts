import {
	MAIN_KEY,
	type ContinuationLink,
	type Drawing,
	type FilterNode,
	type FlowLink,
} from "./layout.ts";
import type { DatumTrace, FilterStepStatus } from "./model.ts";
import type { Workspace } from "./workspace.ts";

/**
 * The colors of datum routes, one per source ID: Tableau 10, which stays
 * distinct for most color vision deficiencies.
 */
export const ROUTE_COLORS = [
	"#4e79a7",
	"#f28e2b",
	"#e15759",
	"#76b7b2",
	"#59a14f",
	"#edc948",
	"#b07aa1",
	"#ff9da7",
	"#9c755f",
	"#bab0ac",
];

/** How a datum's route ends. */
export type RouteEnd =
	/** The Datum Queue output the datum. */
	| "output"
	/** A filter discarded the datum. */
	| "dropped"
	/** The datum stopped with an error. */
	| "stopped";

/** The way a datum went through the drawing. */
export interface DatumRoute {
	seq: number;
	sourceId: string;
	/**
	 * The node keys the datum passed, in order, including Unassigned places
	 * and end connectors, which have no steps.
	 */
	keys: string[];
	/** The link keys the datum travelled: `links[i]` joins `keys[i]` and `keys[i + 1]`. */
	links: string[];
	/** The node key of each step on the drawing, by step id. */
	stepKeys: Map<number, string>;
	/**
	 * How the route ends, or `null` if it is cut short, such as at the debug
	 * cursor, or the drawing does not match the steps.
	 */
	end: RouteEnd | null;
}

/** A chain the datum is going through. */
interface OpenChain {
	key: string;
	chainId: string;
	/** The index of the chain's next filter to look for. */
	next: number;
}

/**
 * Work out the routes datum took through a drawing of the workspace.
 */
export class DatumRoutes {
	readonly #ws: Workspace;
	readonly #nodes = new Map<string, FilterNode>();
	/** The link out of each node's `out` port, by node key. */
	readonly #outLinks = new Map<string, FlowLink>();
	/** The link into each chain's filters, by chain node key. */
	readonly #filtersLinks = new Map<string, FlowLink>();
	/** The continuation line from each nested chain, by chain node key. */
	readonly #continuations = new Map<string, ContinuationLink>();
	readonly #linkCount: number;

	/**
	 * Constructor.
	 *
	 * @param drawing the drawing
	 * @param ws the workspace it is a drawing of
	 */
	constructor(drawing: Drawing, ws: Workspace) {
		this.#ws = ws;
		for (const node of drawing.nodes) {
			this.#nodes.set(node.key, node);
		}
		for (const link of drawing.links) {
			if (link.kind === "continuation") {
				this.#continuations.set(link.sourceKey, link);
			} else if (link.source.port === "filters") {
				this.#filtersLinks.set(link.source.key, link);
			} else {
				this.#outLinks.set(link.source.key, link);
			}
		}
		this.#linkCount = drawing.links.length;
	}

	/**
	 * Work out the route of a datum.
	 *
	 * @param trace the datum's trace
	 * @param upTo the index of the last step to include, or `undefined` for
	 *     the whole route
	 * @returns the route
	 */
	route(trace: DatumTrace, upTo?: number): DatumRoute {
		const route: DatumRoute = {
			seq: trace.seq,
			sourceId: trace.input.sourceId ?? "",
			keys: [],
			links: [],
			stepKeys: new Map(),
			end: null,
		};
		const steps = trace.steps ?? [];
		const last = upTo ?? steps.length - 1;
		const statuses = new Map<string, FilterStepStatus>();
		const open: OpenChain[] = [];
		/** Steps inside a chain that is not drawn with its filters. */
		let hiddenDepth = Infinity;
		let broken = false;
		for (let i = 0; i <= last && i < steps.length; i++) {
			const step = steps[i];
			const depth = step.depth ?? 0;
			if (depth > hiddenDepth) {
				continue;
			}
			hiddenDepth = Infinity;
			let key: string | undefined;
			if (depth === 0) {
				open.length = 0;
				key = this.nodeKey(step.filterId);
			} else {
				open.length = depth;
				const chain = open[depth - 1];
				if (chain) {
					const members = this.#ws.members(chain.chainId);
					const index = members.indexOf(step.filterId, chain.next);
					if (index >= 0) {
						key = `${chain.key}/${index}`;
						chain.next = index + 1;
					}
				}
			}
			if (!key || !this.#addKey(route, key, statuses)) {
				broken = true;
				break;
			}
			route.stepKeys.set(step.id, key);
			statuses.set(key, step.status);
			if (this.#ws.isChain(step.filterId)) {
				// the Main chain's filters follow it in its row
				if (key === MAIN_KEY || this.#filtersLinks.has(key)) {
					open[depth] = { key, chainId: step.filterId, next: 0 };
				} else {
					hiddenDepth = depth;
				}
			}
		}
		if (broken || last < steps.length - 1) {
			return route;
		}
		if (trace.error) {
			route.end = "stopped";
		} else if (trace.output) {
			// out of the Main chain, to the Datum Queue output
			const end = `${MAIN_KEY}/end`;
			if (
				route.keys[0] !== MAIN_KEY ||
				this.#addKey(route, end, statuses)
			) {
				route.end = "output";
			}
		} else {
			route.end = "dropped";
		}
		return route;
	}

	/**
	 * Get the node of a filter: the Main chain's node, or else the filter's
	 * first node.
	 *
	 * @param filterId the filter id
	 * @returns the node key, or `undefined` if the filter is not drawn
	 */
	nodeKey(filterId: string): string | undefined {
		if (filterId === this.#ws.mainChainId && this.#nodes.has(MAIN_KEY)) {
			return MAIN_KEY;
		}
		for (const node of this.#nodes.values()) {
			if (!node.unassigned && node.filterId === filterId) {
				return node.key;
			}
		}
		return undefined;
	}

	/**
	 * Add the next node of a route, with the nodes and links on the way to it.
	 *
	 * @returns `false` if the drawing has no way there
	 */
	#addKey(
		route: DatumRoute,
		key: string,
		statuses: Map<string, FilterStepStatus>,
	): boolean {
		if (route.keys.length === 0) {
			route.keys.push(key);
			return true;
		}
		const keys: string[] = [];
		const links: string[] = [];
		let current = route.keys[route.keys.length - 1];
		for (let n = 0; current !== key; n++) {
			if (n > this.#linkCount) {
				return false;
			}
			let next: string | undefined;
			let link: string | undefined;
			if (this.#filtersLinks.has(current)) {
				// a chain that skipped the datum takes the continuation line
				if (statuses.get(current) === "Skipped") {
					const c = this.#continuations.get(current);
					next = c?.targetKey;
					link = c?.key;
				} else {
					const f = this.#filtersLinks.get(current)!;
					next = f.target.key;
					link = f.key;
				}
			} else {
				const out = this.#outLinks.get(current);
				next = out?.target.key;
				link = out?.key;
			}
			if (!next || !link) {
				return false;
			}
			keys.push(next);
			links.push(link);
			current = next;
		}
		route.keys.push(...keys);
		route.links.push(...links);
		return true;
	}
}
