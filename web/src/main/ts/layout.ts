import type { ChainSlot, Point, Workspace } from "./workspace.ts";

/** The key of the Main chain node. */
export const MAIN_KEY = "main";

/** The key prefix of filters that are not in a chain. */
export const ROOT_PREFIX = "root:";

/** The size of a filter node. */
export const NODE_SIZE = 64;

/** The size of an end connector. */
export const END_SIZE = 22;

/** The horizontal distance between filters in a row. */
const COLUMN = 150;

/** The vertical distance between rows. */
const ROW = 150;

/** How far right of a nested chain its filters start, on the row below it. */
const NESTED_OFFSET = 70;

/** The horizontal space an end connector takes in a row. */
const END_COLUMN = 80;

/** The position of the Main chain. */
const ORIGIN: Point = { x: 40, y: 40 };

/** A reference to a node port. */
export interface PortRef {
	key: string;
	port: string;
}

/** A filter node of the drawing. */
export interface FilterNode {
	kind: "filter";
	/** A key unique to this node, which is also its canvas element id. */
	key: string;
	filterId: string;
	position: Point;
	/** The node's position in its chain, if it is in one. */
	slot?: ChainSlot;
	/** The chain slot that the node's `out` port inserts filters at. */
	outSlot?: ChainSlot;
	/** For a chain, the slot that its `filters` port inserts filters at. */
	filtersSlot?: ChainSlot;
	/** `true` for the Main chain. */
	main: boolean;
	/** `true` if the filter is not in a chain, so it can be placed anywhere. */
	root: boolean;
	/** `true` for a chain shown without its filters, because it contains itself. */
	loop: boolean;
	/** The key of the chain node this node is drawn under. */
	parentKey?: string;
}

/** A chain's end connector. */
export interface EndNode {
	kind: "end";
	key: string;
	chainId: string;
	position: Point;
	/** `true` for the end of the Main chain, the Datum Queue output. */
	main: boolean;
	/** The parent chain slot that the end's `out` port inserts filters at. */
	outSlot?: ChainSlot;
	parentKey: string;
}

/** A link between two filters of a chain. */
export interface FlowLink {
	kind: "flow";
	key: string;
	source: PortRef;
	target: PortRef;
	/** Where a filter dropped on the link is inserted. */
	slot: ChainSlot;
}

/** A line from a nested chain to the filter after it in its parent chain. */
export interface ContinuationLink {
	kind: "continuation";
	key: string;
	sourceKey: string;
	targetKey: string;
}

/** The layout of the workspace on the canvas. */
export interface Drawing {
	nodes: FilterNode[];
	ends: EndNode[];
	links: (FlowLink | ContinuationLink)[];
}

/**
 * Lay out the workspace.
 *
 * The Main chain and its filters form the top row. A nested chain's filters
 * form a row below the chain, ending in the chain's end connector, which links
 * back up to the next filter of the parent chain. Filters that are not in a
 * chain are drawn at their saved position, or else in a row below everything
 * else. A filter in several chains is drawn once for each.
 *
 * @param ws the workspace
 * @returns the drawing
 */
export function layout(ws: Workspace): Drawing {
	const drawing: Drawing = { nodes: [], ends: [], links: [] };
	const drawn = new Set<string>();
	let bottom = ORIGIN.y;

	const flow = (source: PortRef, target: PortRef, slot: ChainSlot) => {
		drawing.links.push({
			kind: "flow",
			key: `${source.key}>${target.key}`,
			source,
			target,
			slot,
		});
	};

	const continuation = (sourceKey: string, targetKey: string) => {
		drawing.links.push({
			kind: "continuation",
			key: `${sourceKey}~${targetKey}`,
			sourceKey,
			targetKey,
		});
	};

	/**
	 * Draw a chain's filters in a row, followed by its end connector.
	 *
	 * @returns the x position after the end connector
	 */
	const drawChain = (
		chainId: string,
		chainKey: string,
		start: PortRef,
		x0: number,
		y: number,
		path: string[],
		endOutSlot?: ChainSlot,
	): number => {
		const members = ws.members(chainId);
		let prev = start;
		let x = x0;
		let continuationFrom: string | undefined;
		bottom = Math.max(bottom, y);
		members.forEach((memberId, index) => {
			const key = `${chainKey}/${index}`;
			const slot: ChainSlot = { chainId, index };
			const next: ChainSlot = { chainId, index: index + 1 };
			const chain = ws.isChain(memberId);
			const loop =
				chain &&
				(path.includes(memberId) || memberId === ws.mainChainId);
			const expand = chain && !loop;
			drawing.nodes.push({
				kind: "filter",
				key,
				filterId: memberId,
				position: { x, y },
				slot,
				outSlot: expand ? undefined : next,
				filtersSlot: expand
					? { chainId: memberId, index: 0 }
					: undefined,
				main: false,
				root: false,
				loop,
				parentKey: chainKey,
			});
			drawn.add(memberId);
			flow(prev, { key, port: "in" }, slot);
			if (continuationFrom) {
				continuation(continuationFrom, key);
				continuationFrom = undefined;
			}
			if (expand) {
				const endX = drawChain(
					memberId,
					key,
					{ key, port: "filters" },
					x + NESTED_OFFSET,
					y + ROW,
					[...path, memberId],
					next,
				);
				prev = { key: `${key}/end`, port: "out" };
				continuationFrom = key;
				x = Math.max(x + COLUMN, endX);
			} else {
				prev = { key, port: "out" };
				x += COLUMN;
			}
		});
		const endKey = `${chainKey}/end`;
		drawing.ends.push({
			kind: "end",
			key: endKey,
			chainId,
			position: { x, y: y + (NODE_SIZE - END_SIZE) / 2 },
			main: chainId === ws.mainChainId && chainKey === MAIN_KEY,
			outSlot: endOutSlot,
			parentKey: chainKey,
		});
		flow(
			prev,
			{ key: endKey, port: "in" },
			{ chainId, index: members.length },
		);
		if (continuationFrom) {
			continuation(continuationFrom, endKey);
		}
		return x + END_COLUMN;
	};

	/**
	 * Draw a filter that is not in a chain, with its filters if it is a chain.
	 *
	 * @returns the x position after the filter and its filters
	 */
	const drawRoot = (
		filterId: string,
		position: Point,
		root: boolean,
	): number => {
		const key = `${ROOT_PREFIX}${filterId}`;
		const chain = ws.isChain(filterId);
		drawing.nodes.push({
			kind: "filter",
			key,
			filterId,
			position,
			filtersSlot: chain ? { chainId: filterId, index: 0 } : undefined,
			main: false,
			root,
			loop: false,
		});
		drawn.add(filterId);
		bottom = Math.max(bottom, position.y);
		if (!chain) {
			return position.x + COLUMN;
		}
		return Math.max(
			position.x + COLUMN,
			drawChain(
				filterId,
				key,
				{ key, port: "filters" },
				position.x + NESTED_OFFSET,
				position.y + ROW,
				[filterId],
			),
		);
	};

	// the Main chain, with its filters in the same row
	const mainId = ws.mainChainId;
	drawing.nodes.push({
		kind: "filter",
		key: MAIN_KEY,
		filterId: mainId,
		position: { ...ORIGIN },
		outSlot: { chainId: mainId, index: 0 },
		main: true,
		root: false,
		loop: false,
	});
	drawn.add(mainId);
	drawChain(
		mainId,
		MAIN_KEY,
		{ key: MAIN_KEY, port: "out" },
		ORIGIN.x + COLUMN,
		ORIGIN.y,
		[mainId],
	);

	// filters that are not in a chain: first those with saved positions, then
	// the others in a row below everything else
	const unplaced: string[] = [];
	for (const f of ws.filters) {
		if (!drawn.has(f.id) && ws.isRoot(f.id)) {
			const position = ws.positions[f.id];
			if (position) {
				drawRoot(f.id, position, true);
			} else {
				unplaced.push(f.id);
			}
		}
	}
	let x = ORIGIN.x;
	const y = bottom + ROW + 20;
	for (const id of unplaced) {
		x = drawRoot(id, { x, y }, true);
	}

	// filters only in chains that are not drawn, such as chains that contain
	// each other
	for (const f of ws.filters) {
		if (!drawn.has(f.id)) {
			x = drawRoot(f.id, { x, y: bottom + ROW + 20 }, false);
		}
	}
	return drawing;
}
