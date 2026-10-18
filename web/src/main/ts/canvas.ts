import { dia, shapes } from "@joint/core";
import { filterIcon, iconGlyph, MAIN_CHAIN_ICON } from "./icons.ts";
import {
	END_SIZE,
	layout,
	NODE_SIZE,
	type ContinuationLink,
	type EndNode,
	type FilterNode,
	type FlowLink,
} from "./layout.ts";
import type { FilterConfig } from "./model.ts";
import type {
	ChainSlot,
	Point,
	Workspace,
	WorkspaceChange,
} from "./workspace.ts";

/** The drag and drop data type of a filter type from the palette. */
export const FILTER_TYPE_MIME = "application/x-sn-filter-type";

const MIN_SCALE = 0.2;
const MAX_SCALE = 2.5;

/** How close to a link, in screen pixels, a drop inserts a filter. */
const DROP_DISTANCE = 18;

/** How far a filter in a chain must be dragged to remove it from the chain. */
const DETACH_DISTANCE = 60;

type DrawnNode = FilterNode | EndNode;
type DrawnLink = FlowLink | ContinuationLink;

const PORT_MARKUP = [{ tagName: "circle", selector: "portBody" }];

function portGroup(position: string, magnet: boolean | "passive") {
	return {
		position: { name: position },
		markup: PORT_MARKUP,
		attrs: {
			portBody: { r: 6, magnet, class: `sim-port sim-port-${position}` },
		},
	};
}

const PORT_GROUPS = {
	in: portGroup("left", "passive"),
	out: portGroup("right", true),
	filters: portGroup("bottom", true),
};

const FilterElement = dia.Element.define(
	"sim.Filter",
	{
		size: { width: NODE_SIZE, height: NODE_SIZE },
		attrs: {
			body: {
				width: "calc(w)",
				height: "calc(h)",
				rx: 12,
				ry: 12,
				strokeWidth: 2,
			},
			icon: {
				x: "calc(0.5*w)",
				y: "calc(0.5*h)",
				textAnchor: "middle",
				textVerticalAnchor: "middle",
				fontFamily: "bootstrap-icons",
				fontSize: 28,
			},
			label: {
				x: "calc(0.5*w)",
				y: "calc(h+10)",
				textAnchor: "middle",
				textVerticalAnchor: "top",
				fontSize: 13,
				textWrap: { width: 132, maxLineCount: 2, ellipsis: true },
			},
		},
		ports: { groups: PORT_GROUPS },
	},
	{
		markup: [
			{ tagName: "rect", selector: "body" },
			{ tagName: "text", selector: "icon" },
			{ tagName: "text", selector: "label" },
		],
	},
);

const EndElement = dia.Element.define(
	"sim.End",
	{
		size: { width: END_SIZE, height: END_SIZE },
		attrs: {
			body: {
				cx: "calc(0.5*w)",
				cy: "calc(0.5*h)",
				r: "calc(0.5*w)",
				strokeWidth: 2,
			},
			dot: { cx: "calc(0.5*w)", cy: "calc(0.5*h)", r: 5 },
			label: {
				x: "calc(0.5*w)",
				y: "calc(h+8)",
				textAnchor: "middle",
				textVerticalAnchor: "top",
				fontSize: 12,
				textWrap: { width: 110, maxLineCount: 2, ellipsis: true },
			},
		},
		ports: { groups: PORT_GROUPS },
	},
	{
		markup: [
			{ tagName: "circle", selector: "body" },
			{ tagName: "circle", selector: "dot" },
			{ tagName: "text", selector: "label" },
		],
	},
);

const FlowShape = shapes.standard.Link.define("sim.Flow", {
	attrs: {
		line: {
			class: "sim-flow",
			strokeWidth: 2,
			targetMarker: { type: "path", d: "M 9 -5 0 0 9 5 z" },
		},
	},
	router: { name: "rightAngle", args: { margin: 18 } },
	connector: { name: "rounded", args: { radius: 6 } },
});

const ContinuationShape = shapes.standard.Link.define("sim.Continuation", {
	attrs: {
		line: {
			class: "sim-continuation",
			strokeWidth: 2,
			strokeDasharray: "1 6",
			strokeLinecap: "round",
			strokeOpacity: 0.5,
			targetMarker: null,
		},
		wrapper: { class: "sim-continuation-wrapper" },
	},
});

const namespace = {
	...shapes,
	sim: {
		Filter: FilterElement,
		End: EndElement,
		Flow: FlowShape,
		Continuation: ContinuationShape,
	},
};

/** The link colors, which are attributes so arrowheads match the lines. */
interface LinkColors {
	flow: string;
	drop: string;
	continuation: string;
	grid: string;
}

/**
 * Get the link colors from the Bootstrap theme.
 *
 * @returns the colors
 */
function linkColors(): LinkColors {
	const style = getComputedStyle(document.body);
	const color = (name: string, fallback: string) =>
		style.getPropertyValue(name).trim() || fallback;
	return {
		flow: color("--bs-secondary-color", "#6c757d"),
		drop: color("--bs-primary", "#0d6efd"),
		continuation: color("--bs-secondary-color", "#6c757d"),
		grid: color("--bs-border-color", "#dee2e6"),
	};
}

function isWithin(key: string, base: string): boolean {
	return key === base || key.startsWith(base + "/");
}

function clamp(n: number, min: number, max: number): number {
	return Math.max(min, Math.min(max, n));
}

/**
 * The canvas that draws the workspace as a flow chart, and turns gestures into
 * workspace changes.
 *
 * It emits a `select` event with the selected {@link FilterNode} (or `null`)
 * as its detail, and a `filedrop` event with a dropped `File` as its detail.
 */
export class Canvas extends EventTarget {
	readonly graph: dia.Graph;
	readonly paper: dia.Paper;
	readonly #ws: Workspace;
	readonly #el: HTMLElement;
	readonly #nodes = new Map<string, DrawnNode>();
	readonly #colors = linkColors();
	#selected: string | null = null;
	#selectedFilterId: string | null = null;
	#flagged = new Set<string>();
	#dropLink: dia.Link | null = null;
	#drag: { key: string; start: Point } | null = null;
	#changes = 0;

	/**
	 * Constructor.
	 *
	 * @param el the element to draw the canvas in
	 * @param ws the workspace
	 */
	constructor(el: HTMLElement, ws: Workspace) {
		super();
		this.#el = el;
		this.#ws = ws;
		this.graph = new dia.Graph({}, { cellNamespace: namespace });
		this.paper = new dia.Paper({
			el,
			model: this.graph,
			cellViewNamespace: namespace,
			width: "100%",
			height: "100%",
			gridSize: 10,
			drawGrid: { name: "dot", args: { color: this.#colors.grid } },
			async: false,
			sorting: dia.Paper.sorting.APPROX,
			linkPinning: false,
			markAvailable: true,
			snapLinks: { radius: 24 },
			defaultLink: () =>
				new FlowShape({
					attrs: { line: { stroke: this.#colors.drop } },
				}),
			interactive: (view) => this.#interactive(view),
			validateMagnet: (view, magnet) =>
				!!this.#portSlot(
					String(view.model.id),
					view.findAttribute("port", magnet),
				),
			validateConnection: (
				sourceView,
				sourceMagnet,
				targetView,
				targetMagnet,
			) =>
				this.#canConnect(
					sourceView,
					sourceMagnet,
					targetView,
					targetMagnet,
				),
		});
		this.#bindPaperEvents();
		this.#bindDropEvents();
		ws.addEventListener("change", (e) => {
			this.#changes++;
			this.#flagged.clear();
			if ((e as CustomEvent<WorkspaceChange>).detail.drawing) {
				this.render();
			}
		});
		this.render();
	}

	/** The selected filter node, or `null` if none. */
	get selection(): FilterNode | null {
		const node = this.#selected
			? this.#nodes.get(this.#selected)
			: undefined;
		return node?.kind === "filter" ? node : null;
	}

	/** Draw the workspace. */
	render(): void {
		const drawing = layout(this.#ws);
		this.#nodes.clear();
		this.#dropLink = null;
		const cells: dia.Cell[] = [];
		for (const node of drawing.nodes) {
			this.#nodes.set(node.key, node);
			cells.push(this.#filterCell(node));
		}
		for (const end of drawing.ends) {
			this.#nodes.set(end.key, end);
			cells.push(this.#endCell(end));
		}
		for (const link of drawing.links) {
			cells.push(this.#linkCell(link));
		}
		this.paper.freeze();
		this.graph.resetCells(cells);
		// embed nodes in the chain they are drawn under, so they move with it
		for (const node of this.#nodes.values()) {
			if (node.parentKey) {
				const child = this.graph.getCell(node.key);
				this.graph.getCell(node.parentKey)?.embed(child);
			}
		}
		this.paper.unfreeze();

		// keep the selection, or else select the same filter where it is now
		if (this.#selected && !this.#nodes.has(this.#selected)) {
			this.#selected =
				this.#firstNode(this.#selectedFilterId)?.key ?? null;
			if (!this.#selected) {
				this.#selectedFilterId = null;
			}
			this.#refreshNode(this.#selected);
		}
		// the selected filter might have changed, so always tell listeners
		this.#emitSelect();
	}

	/**
	 * Select a node.
	 *
	 * @param key the node key, or `null` to clear the selection
	 */
	select(key: string | null): void {
		const node = key ? this.#nodes.get(key) : undefined;
		const next = node?.kind === "filter" ? node.key : null;
		if (next === this.#selected) {
			return;
		}
		const previous = this.#selected;
		this.#selected = next;
		this.#selectedFilterId = node?.kind === "filter" ? node.filterId : null;
		this.#refreshNode(previous);
		this.#refreshNode(next);
		this.#emitSelect();
	}

	/**
	 * Select the first node of a filter.
	 *
	 * @param filterId the filter id
	 */
	selectFilter(filterId: string): void {
		this.select(this.#firstNode(filterId)?.key ?? null);
	}

	/**
	 * Mark filters with problems.
	 *
	 * @param filterIds the filter ids, which replace any marked before
	 */
	setFlagged(filterIds: Iterable<string>): void {
		this.#flagged = new Set(filterIds);
		this.render();
	}

	/** Zoom and pan so the whole flow chart is in view. */
	fit(): void {
		this.paper.transformToFitContent({
			// leave room for the tools in the top right corner
			padding: { top: 40, right: 90, bottom: 40, left: 40 },
			minScale: MIN_SCALE,
			maxScale: 1,
			horizontalAlign: "left",
			verticalAlign: "top",
			useModelGeometry: true,
		});
	}

	/**
	 * Zoom around the middle of the canvas.
	 *
	 * @param factor the zoom factor, over 1 to zoom in
	 */
	zoom(factor: number): void {
		const r = this.#el.getBoundingClientRect();
		const center = this.paper.clientToLocalPoint(
			r.left + r.width / 2,
			r.top + r.height / 2,
		);
		this.#zoomAt(this.paper.scale().sx * factor, center);
	}

	/* === Cells === */

	#filterCell(node: FilterNode): dia.Element {
		const ws = this.#ws;
		const filter = ws.filter(node.filterId);
		const items: dia.Element.Port[] = [];
		if (!node.main) {
			items.push({
				id: "in",
				group: "in",
				attrs: { portBody: { magnet: node.root ? "passive" : false } },
			});
		}
		if (node.filtersSlot) {
			items.push({ id: "filters", group: "filters" });
		} else {
			items.push({
				id: "out",
				group: "out",
				attrs: { portBody: { magnet: !!node.outSlot } },
			});
		}
		const icon = node.main
			? MAIN_CHAIN_ICON
			: filterIcon(
					filter?.factoryUid ?? "",
					!!filter && ws.isSupported(filter),
				);
		return new FilterElement({
			id: node.key,
			position: node.position,
			ports: { items },
			attrs: {
				root: { title: this.#tooltip(node, filter) },
				body: { class: this.#nodeClass(node) },
				icon: { text: iconGlyph(icon), class: "sim-node-icon" },
				label: {
					text: filter ? ws.label(filter) : node.filterId,
					class: "sim-node-label",
				},
			},
			sim: node,
		});
	}

	#endCell(end: EndNode): dia.Element {
		const chain = this.#ws.filter(end.chainId);
		const chainLabel = chain ? this.#ws.label(chain) : end.chainId;
		const items: dia.Element.Port[] = [
			{ id: "in", group: "in", attrs: { portBody: { magnet: false } } },
		];
		if (!end.main) {
			items.push({
				id: "out",
				group: "out",
				attrs: { portBody: { magnet: !!end.outSlot } },
			});
		}
		return new EndElement({
			id: end.key,
			position: end.position,
			ports: { items },
			attrs: {
				root: {
					title: end.main
						? "The Datum Queue output: datum that come out of the Main chain are persisted or uploaded."
						: `The end of ${chainLabel}. The flow continues with the filter it links to.`,
				},
				body: { class: end.main ? "sim-end is-output" : "sim-end" },
				dot: { class: "sim-end-dot" },
				label: {
					text: end.main ? "Output" : `End of ${chainLabel}`,
					class: "sim-end-label",
				},
			},
			sim: end,
		});
	}

	#linkCell(link: DrawnLink): dia.Link {
		if (link.kind === "flow") {
			return new FlowShape({
				id: link.key,
				source: { id: link.source.key, port: link.source.port },
				target: { id: link.target.key, port: link.target.port },
				attrs: { line: { stroke: this.#colors.flow } },
				sim: link,
			});
		}
		return new ContinuationShape({
			id: link.key,
			attrs: { line: { stroke: this.#colors.continuation } },
			source: {
				id: link.sourceKey,
				selector: "body",
				anchor: { name: "right" },
			},
			target: {
				id: link.targetKey,
				selector: "body",
				anchor: { name: "left", args: { dx: -14 } },
			},
			sim: link,
		});
	}

	#nodeClass(node: FilterNode): string {
		const ws = this.#ws;
		const filter = ws.filter(node.filterId);
		const classes = ["sim-node"];
		if (node.main) {
			classes.push("is-main");
		}
		if (ws.isChain(node.filterId)) {
			classes.push("is-chain");
		}
		if (filter && !ws.isSupported(filter)) {
			classes.push(
				ws.isExternal(filter) ? "is-external" : "is-unsupported",
			);
		}
		if (node.root) {
			classes.push("is-root");
		}
		if (node.loop) {
			classes.push("is-loop");
		}
		if (
			this.#flagged.has(node.filterId) ||
			(filter && ws.instanceKeyProblem(filter))
		) {
			classes.push("is-problem");
		}
		if (node.key === this.#selected) {
			classes.push("is-selected");
		}
		return classes.join(" ");
	}

	#tooltip(node: FilterNode, filter: FilterConfig | undefined): string {
		const ws = this.#ws;
		if (!filter) {
			return `The filter ${node.filterId} does not exist.`;
		}
		if (node.main) {
			return "The Main chain: the filter chain the Datum Queue applies to every datum.";
		}
		if (ws.isExternal(filter)) {
			return `${ws.label(filter)}: an external filter, which a chain uses by its Service Name but whose settings are not in this configuration. Datum pass through it unchanged.`;
		}
		const type = ws.type(filter.factoryUid);
		const lines = [
			type ? type.name : `Unsupported filter type ${filter.factoryUid}`,
		];
		if (filter.instanceKey) {
			lines.push(`Instance ID: ${filter.instanceKey}`);
		}
		const problem = ws.instanceKeyProblem(filter);
		if (problem) {
			lines.push(`Instance ID problem: ${problem}`);
		}
		if (!type) {
			lines.push("Datum pass through it unchanged.");
		}
		if (node.loop) {
			lines.push(
				"This chain contains itself, so its filters are not shown here.",
			);
		}
		if (node.root) {
			lines.push("Not in a chain, so it never runs.");
		}
		return lines.join("\n");
	}

	#firstNode(filterId: string | null): FilterNode | undefined {
		for (const node of this.#nodes.values()) {
			if (node.kind === "filter" && node.filterId === filterId) {
				return node;
			}
		}
		return undefined;
	}

	#refreshNode(key: string | null): void {
		const node = key ? this.#nodes.get(key) : undefined;
		if (node?.kind === "filter") {
			this.graph
				.getCell(node.key)
				?.attr("body/class", this.#nodeClass(node));
		}
	}

	#emitSelect(): void {
		this.dispatchEvent(
			new CustomEvent<FilterNode | null>("select", {
				detail: this.selection,
			}),
		);
	}

	/* === Interaction === */

	#interactive(
		view: dia.CellView,
	): dia.CellView.InteractivityOptions | boolean {
		if (view.model.isLink()) {
			return { linkMove: false, labelMove: false };
		}
		const node = this.#nodes.get(String(view.model.id));
		return {
			elementMove: node?.kind === "filter" && !node.main,
			addLinkFromMagnet: true,
		};
	}

	#portSlot(
		key: string,
		port: string | null | undefined,
	): ChainSlot | undefined {
		const node = this.#nodes.get(key);
		if (!node || !port) {
			return undefined;
		}
		if (node.kind === "end") {
			return port === "out" ? node.outSlot : undefined;
		}
		if (port === "filters") {
			return node.filtersSlot;
		}
		return port === "out" ? node.outSlot : undefined;
	}

	#canConnect(
		sourceView: dia.CellView,
		sourceMagnet: SVGElement | undefined,
		targetView: dia.CellView,
		targetMagnet: SVGElement | undefined,
	): boolean {
		if (!sourceMagnet || !targetMagnet || sourceView === targetView) {
			return false;
		}
		const slot = this.#portSlot(
			String(sourceView.model.id),
			sourceView.findAttribute("port", sourceMagnet),
		);
		const target = this.#nodes.get(String(targetView.model.id));
		return (
			!!slot &&
			target?.kind === "filter" &&
			target.root &&
			targetView.findAttribute("port", targetMagnet) === "in" &&
			this.#ws.canInsert(target.filterId, slot)
		);
	}

	#bindPaperEvents(): void {
		const paper = this.paper;
		paper.on("element:pointerclick", (view: dia.ElementView) =>
			this.select(String(view.model.id)),
		);
		paper.on("blank:pointerclick", () => this.select(null));
		paper.on("blank:pointerdown", (evt: dia.Event) => this.#startPan(evt));
		paper.on("blank:mousewheel", (evt: dia.Event, x: number, y: number) =>
			this.#wheel(evt, x, y),
		);
		paper.on(
			"cell:mousewheel",
			(_view: dia.CellView, evt: dia.Event, x: number, y: number) =>
				this.#wheel(evt, x, y),
		);
		paper.on("element:pointerdown", (view: dia.ElementView) => {
			const node = this.#nodes.get(String(view.model.id));
			this.#drag =
				node?.kind === "filter" && !node.main
					? { key: node.key, start: view.model.position() }
					: null;
		});
		paper.on(
			"element:pointermove",
			(view: dia.ElementView, _evt: dia.Event, x: number, y: number) =>
				this.#dragMove(view, { x, y }),
		);
		paper.on("element:pointerup", (view: dia.ElementView) =>
			this.#dragEnd(view),
		);
		paper.on("link:connect", (view: dia.LinkView) => this.#connected(view));
	}

	#startPan(evt: dia.Event): void {
		const start = { x: evt.clientX ?? 0, y: evt.clientY ?? 0 };
		const { tx, ty } = this.paper.translate();
		const move = (e: PointerEvent) => {
			this.paper.translate(
				tx + e.clientX - start.x,
				ty + e.clientY - start.y,
			);
		};
		const end = () => {
			window.removeEventListener("pointermove", move);
			window.removeEventListener("pointerup", end);
			this.#el.classList.remove("is-panning");
		};
		window.addEventListener("pointermove", move);
		window.addEventListener("pointerup", end);
		this.#el.classList.add("is-panning");
	}

	/**
	 * Handle the mouse wheel: zoom with Ctrl or Cmd (or a pinch gesture), and
	 * otherwise pan.
	 */
	#wheel(evt: dia.Event, x: number, y: number): void {
		const e = evt.originalEvent as WheelEvent | undefined;
		if (!e) {
			return;
		}
		evt.preventDefault();
		if (e.ctrlKey || e.metaKey) {
			this.#zoomAt(this.paper.scale().sx * Math.exp(-e.deltaY * 0.01), {
				x,
				y,
			});
		} else {
			const { tx, ty } = this.paper.translate();
			this.paper.translate(tx - e.deltaX, ty - e.deltaY);
		}
	}

	#zoomAt(scale: number, point: Point): void {
		this.paper.scaleUniformAtPoint(
			clamp(scale, MIN_SCALE, MAX_SCALE),
			point,
		);
	}

	#dragMove(view: dia.ElementView, point: Point): void {
		const drag = this.#drag;
		const node = drag ? this.#nodes.get(drag.key) : undefined;
		if (!drag || node?.kind !== "filter") {
			return;
		}
		const position = view.model.position();
		if (position.x === drag.start.x && position.y === drag.start.y) {
			return;
		}
		this.#setDropLink(
			this.#findDropLink(
				point,
				(slot) => this.#canDrop(node, slot),
				node.key,
			),
		);
	}

	#dragEnd(view: dia.ElementView): void {
		const drag = this.#drag;
		const dropLink = this.#dropLink;
		this.#drag = null;
		this.#setDropLink(null);
		const node = drag ? this.#nodes.get(drag.key) : undefined;
		if (!drag || node?.kind !== "filter") {
			return;
		}
		const position = view.model.position();
		const dx = position.x - drag.start.x;
		const dy = position.y - drag.start.y;
		if (dx === 0 && dy === 0) {
			return;
		}
		const changes = this.#changes;
		const ws = this.#ws;
		const slot = (dropLink?.get("sim") as FlowLink | undefined)?.slot;
		if (slot) {
			if (node.root) {
				ws.insert(node.filterId, slot);
			} else if (node.slot) {
				ws.move(node.slot, slot);
			}
		} else if (node.root) {
			ws.setPosition(node.filterId, { x: position.x, y: position.y });
		} else if (node.slot && Math.hypot(dx, dy) > DETACH_DISTANCE) {
			ws.detach(node.slot, { x: position.x, y: position.y });
		}
		if (this.#changes === changes) {
			// nothing changed, so put the filter back where it was
			this.render();
		}
	}

	#connected(view: dia.LinkView): void {
		const link = view.model;
		const source = link.source();
		const target = link.target();
		link.remove();
		const slot = this.#portSlot(String(source.id), source.port);
		const node = this.#nodes.get(String(target.id));
		if (slot && node?.kind === "filter" && node.root) {
			this.#ws.insert(node.filterId, slot);
		}
	}

	#canDrop(node: FilterNode, slot: ChainSlot): boolean {
		if (node.root) {
			return this.#ws.canInsert(node.filterId, slot);
		}
		return (
			!!node.slot &&
			this.#ws.canInsert(
				node.filterId,
				slot,
				slot.chainId === node.slot.chainId,
			)
		);
	}

	/**
	 * Find the link closest to a point, within the drop distance.
	 *
	 * @param point the point, in canvas coordinates
	 * @param accept a test for the link's chain slot
	 * @param exclude the key of a node whose links, and those of the nodes
	 *     drawn under it, are left out
	 */
	#findDropLink(
		point: Point,
		accept: (slot: ChainSlot) => boolean,
		exclude?: string,
	): dia.Link | null {
		let best: dia.Link | null = null;
		let bestDistance = DROP_DISTANCE / this.paper.scale().sx;
		for (const link of this.graph.getLinks()) {
			const data = link.get("sim") as DrawnLink | undefined;
			if (data?.kind !== "flow") {
				continue;
			}
			if (
				exclude &&
				(isWithin(data.source.key, exclude) ||
					isWithin(data.target.key, exclude))
			) {
				continue;
			}
			if (!accept(data.slot)) {
				continue;
			}
			const view = link.findView(this.paper) as dia.LinkView | undefined;
			const closest = view?.getClosestPoint(point);
			const distance = closest ? closest.distance(point) : Infinity;
			if (distance < bestDistance) {
				best = link;
				bestDistance = distance;
			}
		}
		return best;
	}

	#setDropLink(link: dia.Link | null): void {
		if (link === this.#dropLink) {
			return;
		}
		this.#dropLink?.attr("line", {
			stroke: this.#colors.flow,
			strokeWidth: 2,
		});
		this.#dropLink = link;
		link?.attr("line", { stroke: this.#colors.drop, strokeWidth: 4 });
	}

	#bindDropEvents(): void {
		const el = this.#el;
		el.addEventListener("dragover", (e) => {
			const types = e.dataTransfer?.types ?? [];
			if (types.includes(FILTER_TYPE_MIME)) {
				e.preventDefault();
				e.dataTransfer!.dropEffect = "copy";
				const point = this.paper.clientToLocalPoint(
					e.clientX,
					e.clientY,
				);
				this.#setDropLink(this.#findDropLink(point, () => true));
			} else if (types.includes("Files")) {
				e.preventDefault();
				e.dataTransfer!.dropEffect = "copy";
			}
		});
		el.addEventListener("dragleave", (e) => {
			if (!(
				e.relatedTarget instanceof Node && el.contains(e.relatedTarget)
			)) {
				this.#setDropLink(null);
			}
		});
		el.addEventListener("drop", (e) => {
			const dt = e.dataTransfer;
			if (!dt) {
				return;
			}
			const factoryUid = dt.getData(FILTER_TYPE_MIME);
			const slot = (this.#dropLink?.get("sim") as FlowLink | undefined)
				?.slot;
			this.#setDropLink(null);
			if (factoryUid) {
				e.preventDefault();
				const type = this.#ws.type(factoryUid);
				if (!type) {
					return;
				}
				const point = this.paper.clientToLocalPoint(
					e.clientX,
					e.clientY,
				);
				const filter = this.#ws.addFilter(
					type,
					slot ?? {
						x: Math.round(point.x - NODE_SIZE / 2),
						y: Math.round(point.y - NODE_SIZE / 2),
					},
				);
				this.selectFilter(filter.id);
			} else if (dt.files.length > 0) {
				e.preventDefault();
				this.dispatchEvent(
					new CustomEvent<File>("filedrop", { detail: dt.files[0] }),
				);
			}
		});
	}
}
