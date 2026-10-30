import { dia, shapes } from "@joint/core";
import { DatumRoutes, ROUTE_COLORS, type DatumRoute } from "./datumRoutes.ts";
import {
	drawRoutes,
	type RouteGeometry,
	type RouteLayers,
	type RouteLine,
} from "./routeOverlay.ts";
import {
	RoutesLegend,
	type LegendToggle,
	type SeqRange,
} from "./routesLegend.ts";
import { showToast } from "./dialogs.ts";
import {
	datumSourceIcon,
	filterIcon,
	glyphCenterTransform,
	iconFontLoaded,
	iconGlyph,
	MAIN_CHAIN_ICON,
} from "./icons.ts";
import {
	END_SIZE,
	layout,
	STATIC_SOURCE_ID,
	NODE_SIZE,
	UNASSIGNED_SIZE,
	type ContinuationLink,
	type EndNode,
	type FilterNode,
	type FlowLink,
	type SourceLink,
	type SourceNode,
} from "./layout.ts";
import {
	UNASSIGNED_UID,
	type DatumTrace,
	type FilterConfig,
	type InstructionRun,
} from "./model.ts";
import type { DatumPath, FilterRunStats } from "./results.ts";
import {
	bundleLabel,
	externalFilesText,
	expiredModeOriginText,
	instructionOriginText,
	polledOriginShortText,
	polledOriginText,
	quoted,
	type DatumSourceSummary,
} from "./view.ts";
import type {
	ChainSlot,
	Point,
	Workspace,
	WorkspaceChange,
} from "./workspace.ts";

/** The drag and drop data type of a filter type from the palette. */
export const FILTER_TYPE_MIME = "application/x-sn-filter-type";

/** The drag and drop data type of an Unassigned place from the palette. */
export const UNASSIGNED_MIME = "application/x-sn-filter-unassigned";

const MIN_SCALE = 0.2;
const MAX_SCALE = 2.5;

/** How close to a link, in screen pixels, a drop inserts a filter. */
const DROP_DISTANCE = 18;

/** How far a filter in a chain must be dragged to remove it from the chain. */
const DETACH_DISTANCE = 60;

/** The pixels in a line, for mouse wheel steps given in lines. */
const WHEEL_LINE = 16;

/** The width of the flow lines between filters. */
const FLOW_WIDTH = 1;

type DrawnNode = FilterNode | EndNode;
type DrawnLink = FlowLink | ContinuationLink | SourceLink;

/** Where a dragged filter would go: a link to insert it at, or a place to fill. */
interface Drop {
	/** The link or Unassigned place element. */
	cell: dia.Cell;
	slot: ChainSlot;
}

/** The results of a simulation run, which the canvas shows on its nodes. */
export interface CanvasRun {
	/** The run statistics, by filter id. */
	stats: Map<string, FilterRunStats>;
	/** The number of datum the Datum Queue output. */
	output: number;
	/** The datum traces, for their routes. */
	datum: DatumTrace[];
	/** The instruction runs, for the origins of the datum they published. */
	instructionRuns?: InstructionRun[];
	/** The number of datum each datum source polled, by datum source id. */
	polled?: ReadonlyMap<string, number>;
}

const SVG_NS = "http://www.w3.org/2000/svg";

/** The font size of the filter and datum source icons. */
const ICON_SIZE = 28;

/** The icon of the static datum, the Datum tab's input datum. */
const STATIC_SOURCE_ICON = "braces";

/** Start a sentence's text with a lowercase letter, to use it mid-sentence. */
function lowerFirst(text: string): string {
	return text.charAt(0).toLowerCase() + text.slice(1);
}

/**
 * The most datum routes shown at first in debug mode, so a large run stays
 * readable.
 */
const MAX_START_ROUTES = 20;

/** The browser storage key of whether every datum route is shown. */
const ROUTES_STORAGE_KEY = "sn-filter-sim.datumRoutes";

/** Get whether every datum route is shown, as saved in the browser. */
function loadShowRoutes(): boolean {
	try {
		return localStorage.getItem(ROUTES_STORAGE_KEY) === "true";
	} catch {
		return false;
	}
}

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
				fontSize: ICON_SIZE,
			},
			ext: {
				x: "calc(w-6)",
				y: 6,
				textAnchor: "end",
				textVerticalAnchor: "top",
				fontFamily: "bootstrap-icons",
				fontSize: 12,
			},
			label: {
				x: "calc(0.5*w)",
				y: "calc(h+10)",
				textAnchor: "middle",
				textVerticalAnchor: "top",
				fontSize: 13,
				textWrap: { width: 132, maxLineCount: 2, ellipsis: true },
			},
			badge: {
				x: "calc(0.5*w)",
				y: -14,
				textAnchor: "middle",
				textVerticalAnchor: "middle",
				fontSize: 11,
			},
			badgeBody: {
				ref: "badge",
				x: "calc(x-7)",
				y: "calc(y-3)",
				width: "calc(w+14)",
				height: "calc(h+6)",
				rx: 8,
				ry: 8,
			},
		},
		ports: { groups: PORT_GROUPS },
	},
	{
		markup: [
			{ tagName: "rect", selector: "body" },
			{ tagName: "text", selector: "icon" },
			{ tagName: "text", selector: "ext" },
			{ tagName: "text", selector: "label" },
			{ tagName: "rect", selector: "badgeBody" },
			{ tagName: "text", selector: "badge" },
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
			strokeWidth: FLOW_WIDTH,
			targetMarker: { type: "path", d: "M 6 -3.5 0 0 6 3.5 z" },
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
	// an opaque grey, which keeps the filter names readable and the lines
	// even where they cross or overlap
	const lineColor = mixedColor(
		"var(--bs-body-color) 40%, var(--bs-body-bg)",
		"#adb5bd",
	);
	return {
		flow: "#709BCB",
		drop: color("--bs-primary", "#0d6efd"),
		continuation: lineColor,
		grid: color("--bs-border-color", "#dee2e6"),
	};
}

/**
 * Resolve a CSS `color-mix()` of theme colors to a plain color.
 *
 * @param mix the `color-mix()` colors, after the interpolation method
 * @param fallback the color to use if the mix cannot be resolved
 * @returns the resolved color
 */
function mixedColor(mix: string, fallback: string): string {
	const el = document.createElement("span");
	el.style.color = `color-mix(in srgb, ${mix})`;
	if (!el.style.color) {
		return fallback;
	}
	el.style.display = "none";
	document.body.append(el);
	const result = getComputedStyle(el).color;
	el.remove();
	return result || fallback;
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
 * as its detail, a `filedrop` event with a dropped `File` as its detail, and a
 * `datumselect` event with a datum sequence number detail when a datum route
 * is clicked, a `datumsourceselect` event with a datum source id detail when a
 * datum source is clicked, and a `blankclick` event when the background is
 * clicked.
 */
export class Canvas extends EventTarget {
	readonly graph: dia.Graph;
	readonly paper: dia.Paper;
	readonly #ws: Workspace;
	readonly #el: HTMLElement;
	readonly #nodes = new Map<string, DrawnNode>();
	/** The datum source nodes, by node key. */
	readonly #sourceNodes = new Map<string, SourceNode>();
	/** The datum sources to draw. */
	#datumSources: DatumSourceSummary[] = [];
	/** The datum sources as last drawn, to draw them only when they change. */
	#datumSourcesKey = "[]";
	readonly #colors = linkColors();
	#selected: string | null = null;
	#selectedFilterId: string | null = null;
	#flagged = new Set<string>();
	#run: CanvasRun | null = null;
	#path: DatumPath | null = null;
	#routes: DatumRoutes | null = null;
	/** The color of each source ID's datum routes. */
	#sourceColors = new Map<string, string>();
	/** The datum routes: lines under the links and filters, badges over them. */
	readonly #routeLayers: RouteLayers;
	#showRoutes = loadShowRoutes();
	#routeFrame?: number;
	readonly #legend: RoutesLegend | null;
	/** The datum whose routes the legend shows, or `null` for all. */
	#visibleSeqs: Set<number> | null = null;
	/**
	 * The datum whose routes show, as a large run's results selected them, or
	 * `null` to show every route.
	 */
	#selection: Set<number> | null = null;
	/** The datum whose route the pointer is over. */
	#routeHover: number | null = null;
	#drop: Drop | null = null;
	#drag: { key: string; start: Point } | null = null;
	#changes = 0;

	/**
	 * Constructor.
	 *
	 * @param el the element to draw the canvas in
	 * @param ws the workspace
	 * @param legendEl the element to show the datum routes legend in
	 */
	constructor(el: HTMLElement, ws: Workspace, legendEl?: HTMLElement) {
		super();
		this.#legend = legendEl ? new RoutesLegend(legendEl) : null;
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
		this.#routeLayers = {
			lines: document.createElementNS(SVG_NS, "g"),
			badges: document.createElementNS(SVG_NS, "g"),
		};
		this.#routeLayers.lines.classList.add("sim-routes");
		this.#routeLayers.badges.classList.add("sim-routes");
		this.paper
			.getLayerView(dia.Paper.Layers.BACK)
			.el.append(this.#routeLayers.lines);
		this.paper
			.getLayerView(dia.Paper.Layers.FRONT)
			.el.append(this.#routeLayers.badges);
		// moving filters moves the links, so the routes follow
		this.graph.on("change:position", () => this.#scheduleRoutes());
		this.#bindRouteEvents();
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
		// the icons are centred by their glyphs' measurements, once the font loads
		void iconFontLoaded(ICON_SIZE).then(() => this.render());
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
		const drawing = layout(
			this.#ws,
			this.#datumSources.map((d) => d.id),
		);
		this.#nodes.clear();
		this.#sourceNodes.clear();
		this.#drop = null;
		for (const node of [...drawing.nodes, ...drawing.ends]) {
			this.#nodes.set(node.key, node);
		}
		for (const source of drawing.sources) {
			this.#sourceNodes.set(source.key, source);
		}

		// keep the selection, or else select the same filter where it is now,
		// before drawing the nodes with it
		const current = this.#selected
			? this.#nodes.get(this.#selected)
			: undefined;
		const same =
			current?.kind === "filter" &&
			(current.unassigned
				? this.#selectedFilterId === null
				: current.filterId === this.#selectedFilterId);
		if (this.#selected && !same) {
			this.#selected =
				this.#firstNode(this.#selectedFilterId)?.key ?? null;
			if (!this.#selected) {
				this.#selectedFilterId = null;
			}
		}

		const cells: dia.Cell[] = [
			...drawing.nodes.map((node) => this.#filterCell(node)),
			...drawing.ends.map((end) => this.#endCell(end)),
			...drawing.sources.map((source) => this.#sourceCell(source)),
			...drawing.links.map((link) => this.#linkCell(link)),
		];
		// draw the continuations under the flows, and both under the nodes
		// so their labels show over them
		cells.forEach((cell) =>
			cell.set(
				"z",
				cell instanceof ContinuationShape ? 1 : cell.isLink() ? 2 : 3,
			),
		);
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
		this.#routes = new DatumRoutes(drawing, this.#ws);
		this.#drawRoutes();
		// the selected filter might have changed, so always tell listeners
		this.#emitSelect();
	}

	/**
	 * Set the datum sources, which are drawn left of the Main chain, feeding
	 * it. The canvas is drawn again only if they changed.
	 *
	 * @param sources the datum sources, without Operational Mode Invokers
	 */
	setDatumSources(sources: readonly DatumSourceSummary[]): void {
		const key = JSON.stringify(sources);
		if (key === this.#datumSourcesKey) {
			return;
		}
		this.#datumSources = [...sources];
		this.#datumSourcesKey = key;
		this.render();
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
		this.#selectedFilterId =
			node?.kind === "filter" && !node.unassigned ? node.filterId : null;
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

	/**
	 * Show the results of a simulation run on the nodes: how many datum went
	 * into and out of each filter, and how many the Datum Queue output.
	 *
	 * @param run the run results, or `null` to show none
	 */
	setRun(run: CanvasRun | null): void {
		this.#run = run;
		this.#path = null;
		this.#selection = null;
		const datum = run?.datum ?? [];
		this.#visibleSeqs = this.#startRoutes();
		this.#routeHover = null;
		this.#sourceColors.clear();
		this.#addSourceColors(datum);
		this.#el.classList.remove("is-stale");
		this.render();
	}

	/** Give each new source ID of some datum a route color. */
	#addSourceColors(traces: readonly DatumTrace[]): void {
		for (const trace of traces) {
			const sourceId = trace.input.sourceId ?? "";
			if (!this.#sourceColors.has(sourceId)) {
				this.#sourceColors.set(
					sourceId,
					ROUTE_COLORS[this.#sourceColors.size % ROUTE_COLORS.length],
				);
			}
		}
	}

	/**
	 * Add datum traces to the run's, for their routes, such as the traces of
	 * a result without every trace as they load.
	 *
	 * @param traces the traces
	 */
	addTraces(traces: readonly DatumTrace[]): void {
		const run = this.#run;
		if (!run || !traces.length) {
			return;
		}
		const bySeq = new Map(run.datum.map((t) => [t.seq, t]));
		for (const trace of traces) {
			bySeq.set(trace.seq, trace);
		}
		run.datum = [...bySeq.values()].sort((a, b) => a.seq - b.seq);
		this.#addSourceColors(traces);
		this.#drawRoutes();
	}

	/** The routes the legend shows at first, so a large run stays readable. */
	#startRoutes(): Set<number> | null {
		const datum = this.#run?.datum ?? [];
		return datum.length > MAX_START_ROUTES
			? new Set(datum.slice(0, MAX_START_ROUTES).map((t) => t.seq))
			: null;
	}

	/**
	 * Show only the routes of some datum, such as the datum selected in a
	 * large run's results, or every route.
	 *
	 * @param seqs the datum sequence numbers, or `null` for every datum
	 */
	setRouteSelection(seqs: readonly number[] | null): void {
		const before = this.#selection;
		if (seqs === null) {
			if (before !== null) {
				this.#selection = null;
				this.#visibleSeqs = this.#startRoutes();
				this.#drawRoutes();
			}
			return;
		}
		const selection = new Set(seqs);
		if (before === null) {
			this.#visibleSeqs = null;
		} else if (this.#visibleSeqs) {
			// the legend shows the datum newly selected
			for (const seq of selection) {
				if (!before.has(seq)) {
					this.#visibleSeqs.add(seq);
				}
			}
		}
		this.#selection = selection;
		this.#drawRoutes();
	}

	/** Draw the datum routes again, such as after their text changed. */
	refreshRoutes(): void {
		this.#drawRoutes();
	}

	/** The datum the legend lists: the selected datum, or every datum. */
	#legendSeqs(): number[] {
		const selection = this.#selection;
		const seqs = (this.#run?.datum ?? []).map((t) => t.seq);
		return selection ? seqs.filter((s) => selection.has(s)) : seqs;
	}

	/**
	 * Mark the run results as made before the latest changes.
	 *
	 * @param stale `true` if the filters or inputs changed since the run
	 */
	setStale(stale: boolean): void {
		this.#el.classList.toggle("is-stale", stale);
	}

	/**
	 * Highlight the filters a datum went through.
	 *
	 * @param path the datum path, or `null` for none
	 */
	setPath(path: DatumPath | null): void {
		this.#path = path;
		for (const key of this.#nodes.keys()) {
			this.#refreshNode(key);
		}
		for (const source of this.#sourceNodes.values()) {
			this.graph
				.getCell(source.key)
				?.attr("body/class", this.#sourceClass(source));
		}
		this.#drawRoutes();
	}

	/** Zoom and pan so the whole flow chart is in view. */
	fit(): void {
		let contentArea = this.paper.getContentArea({ useModelGeometry: true });
		// datum routes reach outside the filters, such as above the Main row
		for (const layer of [
			this.#routeLayers.lines,
			this.#routeLayers.badges,
		]) {
			if (layer.childElementCount > 0) {
				contentArea = contentArea.union(layer.getBBox());
			}
		}
		this.paper.transformToFitContent({
			contentArea,
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
	 * Zoom around the top left corner of the visible part of the flow chart,
	 * so what is in view stays in view, or else around the middle of the
	 * canvas.
	 *
	 * @param factor the zoom factor, over 1 to zoom in
	 */
	zoom(factor: number): void {
		const view = this.paper.clientToLocalRect(
			this.#el.getBoundingClientRect(),
		);
		const content = this.paper.getContentArea({ useModelGeometry: true });
		const visible = content.width > 0 ? view.intersect(content) : null;
		this.#zoomAt(
			this.paper.scale().sx * factor,
			visible ? visible.topLeft() : view.center(),
		);
	}

	/* === Datum routes === */

	/** `true` if every datum's route is shown, not only the selected one's. */
	get showRoutes(): boolean {
		return this.#showRoutes;
	}

	/**
	 * Show every datum's route, or only the selected datum's.
	 *
	 * @param show `true` to show every route
	 */
	set showRoutes(show: boolean) {
		this.#showRoutes = show;
		try {
			localStorage.setItem(ROUTES_STORAGE_KEY, String(show));
		} catch {
			// not saved
		}
		this.#drawRoutes();
	}

	#scheduleRoutes(): void {
		if (this.#routeFrame === undefined) {
			this.#routeFrame = window.requestAnimationFrame(() => {
				this.#routeFrame = undefined;
				this.#drawRoutes();
			});
		}
	}

	/**
	 * Draw the datum routes: every datum's if they are shown, with the
	 * selected one emphasized, or else only the selected one's. In debug mode,
	 * routes stop at the cursor.
	 */
	#drawRoutes(): void {
		const run = this.#run;
		const routes = this.#routes;
		if (!run || !routes) {
			drawRoutes(this.#routeLayers, [], this.#routeGeometry);
			this.#legend?.show(null);
			return;
		}
		const path = this.#path;
		const traces = run.datum;
		let shown: { trace: DatumTrace; upTo?: number }[];
		if (path?.upTo !== undefined) {
			const index = traces.findIndex((t) => t.seq === path.seq);
			shown = [
				...(this.#showRoutes
					? traces.slice(0, Math.max(0, index))
					: []
				).map((trace) => ({ trace })),
				...(index >= 0
					? [{ trace: traces[index], upTo: path.upTo }]
					: []),
			];
		} else if (this.#selection) {
			const selection = this.#selection;
			shown = traces
				.filter((t) => selection.has(t.seq) || t.seq === path?.seq)
				.map((trace) => ({ trace }));
		} else if (this.#showRoutes) {
			shown = traces.map((trace) => ({ trace }));
		} else {
			const trace = traces.find((t) => t.seq === path?.seq);
			shown = trace ? [{ trace }] : [];
		}
		// the legend hides routes, but never the selected datum's
		const visible = this.#visibleSeqs;
		if (this.#showRoutes && visible) {
			shown = shown.filter(
				({ trace }) =>
					visible.has(trace.seq) || trace.seq === path?.seq,
			);
		}

		// the whole routes, to find where generated datum came from
		const whole = new Map<number, DatumRoute>();
		for (const trace of traces) {
			whole.set(trace.seq, routes.route(trace));
		}
		const lines: RouteLine[] = shown.map(({ trace, upTo }) => {
			const route =
				upTo === undefined
					? whole.get(trace.seq)!
					: routes.route(trace, upTo);
			const origin = trace.origin;
			let fromKey: string | undefined;
			if (origin.type !== "Input") {
				fromKey =
					(origin.parentSeq !== undefined &&
					origin.stepId !== undefined
						? whole
								.get(origin.parentSeq)
								?.stepKeys.get(origin.stepId)
						: undefined) ??
					(origin.filterId
						? routes.nodeKey(origin.filterId)
						: undefined);
			}
			return {
				route,
				color: this.#routeColor(route.sourceId),
				fromKey,
				title: this.#routeText(trace, route).title,
				dim:
					this.#showRoutes &&
					!this.#selection &&
					path?.seq !== undefined &&
					trace.seq !== path.seq,
			};
		});
		drawRoutes(this.#routeLayers, lines, {
			...this.#routeGeometry,
			sources: this.#sourceNodes.size > 0,
		});
		const selection = this.#selection;
		this.#legend?.show(
			this.#showRoutes
				? (selection
						? traces.filter(
								(t) =>
									selection.has(t.seq) || t.seq === path?.seq,
							)
						: traces
					).map((trace) => {
						const route = whole.get(trace.seq)!;
						const text = this.#routeText(trace, route);
						return {
							seq: trace.seq,
							sourceId: route.sourceId,
							color: this.#routeColor(route.sourceId),
							...text,
							visible: !visible || visible.has(trace.seq),
							selected: trace.seq === path?.seq,
						};
					})
				: null,
		);
		this.#applyRouteHover();
	}

	#routeColor(sourceId: string): string {
		return this.#sourceColors.get(sourceId) ?? ROUTE_COLORS[0];
	}

	/**
	 * Describe where a datum came from and where its route ended: in full,
	 * and in short for the legend.
	 */
	#routeText(
		trace: DatumTrace,
		route: DatumRoute,
	): { title: string; summary: string } {
		const label = (id: string | undefined) => {
			const filter = id ? this.#ws.filter(id) : undefined;
			return id
				? quoted(filter ? this.#ws.label(filter) : id)
				: "a filter";
		};
		const origin = trace.origin;
		const generator = label(origin.filterId);
		const [from, fromShort] =
			origin.type === "Input"
				? [
						`input ${(origin.index ?? 0) + 1}`,
						`input ${(origin.index ?? 0) + 1}`,
					]
				: origin.type === "Polled"
					? [
							lowerFirst(polledOriginText(origin)),
							polledOriginShortText(origin),
						]
					: origin.type === "Scheduled" &&
						  origin.controlId &&
						  !origin.filterId
						? [
								lowerFirst(
									expiredModeOriginText(origin.controlId),
								),
								`from control ${origin.controlId}`,
							]
						: origin.type === "Scheduled"
							? [
									`generated by a scheduled task of ${generator}`,
									`from ${generator}`,
								]
							: origin.type === "Instruction"
								? [
										lowerFirst(
											instructionOriginText(
												origin,
												this.#run?.instructionRuns,
											),
										),
										origin.controlId
											? `from control ${origin.controlId}`
											: `from ${generator}`,
									]
								: origin.controlId
									? [
											`published by control ${origin.controlId}, set by ${generator}`,
											`from control ${origin.controlId}`,
										]
									: [
											`generated by ${generator}`,
											`from ${generator}`,
										];
		const last = this.#nodes.get(route.keys[route.keys.length - 1]);
		const dropper = label(
			last?.kind === "filter" ? last.filterId : undefined,
		);
		const [end, endShort] =
			route.end === "output"
				? ["output by the Datum Queue", "output"]
				: route.end === "dropped"
					? [`discarded by ${dropper}`, `dropped by ${dropper}`]
					: route.end === "stopped"
						? ["stopped by an error", "error"]
						: ["", ""];
		return {
			title: `#${trace.seq} ${route.sourceId}: ${from}${end ? `, ${end}` : ""}`,
			summary: endShort ? `${fromShort} → ${endShort}` : fromShort,
		};
	}

	/**
	 * Show or hide routes, like a Grafana legend: show only the given routes,
	 * or all again if only they are shown; or add them to the shown routes, or
	 * remove them if they are all shown.
	 */
	#toggleRoutes({ seqs, additive }: LegendToggle): void {
		const all = this.#legendSeqs();
		let visible: Set<number> | null = this.#visibleSeqs ?? new Set(all);
		if (additive) {
			const allShown = seqs.every((s) => visible!.has(s));
			visible = new Set(visible);
			for (const seq of seqs) {
				if (allShown) {
					visible.delete(seq);
				} else {
					visible.add(seq);
				}
			}
		} else {
			const only =
				visible.size === seqs.length &&
				seqs.every((s) => visible!.has(s));
			visible = only ? null : new Set(seqs);
		}
		this.#visibleSeqs =
			visible && visible.size === all.length ? null : visible;
		this.#drawRoutes();
	}

	/**
	 * Emphasize a datum's route, such as while the pointer is over it.
	 *
	 * @param seq the datum sequence number, or `null` for none
	 */
	#setRouteHover(seq: number | null): void {
		if (seq !== this.#routeHover) {
			this.#routeHover = seq;
			this.#applyRouteHover();
		}
	}

	#applyRouteHover(): void {
		const seq = this.#routeHover;
		for (const layer of [
			this.#routeLayers.lines,
			this.#routeLayers.badges,
		]) {
			layer.classList.toggle("is-hovering", seq !== null);
			layer
				.querySelectorAll<SVGElement>("[data-seq]")
				.forEach((el) =>
					el.classList.toggle(
						"is-hover",
						el.dataset.seq === String(seq),
					),
				);
		}
		this.#legend?.setHover(seq);
	}

	/** Handle the pointer on routes and their badges. */
	#bindRouteEvents(): void {
		const seqOf = (e: Event) => {
			const el = (e.target as Element).closest<SVGElement>("[data-seq]");
			return el ? Number(el.dataset.seq) : null;
		};
		for (const layer of [
			this.#routeLayers.lines,
			this.#routeLayers.badges,
		]) {
			layer.addEventListener("pointerover", (e) =>
				this.#setRouteHover(seqOf(e)),
			);
			layer.addEventListener("pointerout", () =>
				this.#setRouteHover(null),
			);
			layer.addEventListener("click", (e) => {
				const seq = seqOf(e);
				if (seq !== null) {
					// not a click on the background, which unselects
					e.stopPropagation();
					this.dispatchEvent(
						new CustomEvent<number>("datumselect", { detail: seq }),
					);
				}
			});
		}
		this.#legend?.addEventListener("toggle", (e) =>
			this.#toggleRoutes((e as CustomEvent<LegendToggle>).detail),
		);
		this.#legend?.addEventListener("showall", () => {
			this.#visibleSeqs = null;
			this.#drawRoutes();
		});
		this.#legend?.addEventListener("showrange", (e) => {
			const ranges = (e as CustomEvent<SeqRange[]>).detail;
			const all = this.#legendSeqs();
			const seqs = all.filter((seq) =>
				ranges.some((r) => seq >= r.from && seq <= r.to),
			);
			this.#visibleSeqs =
				seqs.length === all.length ? null : new Set(seqs);
			this.#drawRoutes();
		});
		this.#legend?.addEventListener("shownone", () => {
			this.#visibleSeqs = new Set();
			this.#drawRoutes();
		});
		this.#legend?.addEventListener("hover", (e) =>
			this.#setRouteHover((e as CustomEvent<number | null>).detail),
		);
	}

	/** The drawing's geometry, for datum routes. */
	readonly #routeGeometry: RouteGeometry = {
		linkPoints: (key) => {
			const view = this.paper.findViewByModel(key) as
				dia.LinkView | undefined;
			if (!view?.sourcePoint || !view.targetPoint) {
				return undefined;
			}
			return [view.sourcePoint, ...(view.route ?? []), view.targetPoint];
		},
		box: (key) => {
			const cell = this.graph.getCell(key);
			return cell?.isElement() ? cell.getBBox() : undefined;
		},
	};

	/* === Cells === */

	#filterCell(node: FilterNode): dia.Element {
		if (node.unassigned) {
			return this.#unassignedCell(node);
		}
		const ws = this.#ws;
		const filter = ws.filter(node.filterId);
		const items: dia.Element.Port[] = [];
		// the Main chain takes the datum sources' lines
		if (!node.main || this.#sourceNodes.size > 0) {
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
				icon: this.#iconAttrs(icon),
				// the External Settings marker
				ext: {
					text: ws.isExternal(node.filterId)
						? iconGlyph("box-seam")
						: "",
					class: "sim-node-ext",
				},
				label: {
					text: filter ? ws.label(filter) : node.filterId,
					class: "sim-node-label",
				},
				...this.#badgeAttrs(node),
			},
			sim: node,
		});
	}

	#unassignedCell(node: FilterNode): dia.Element {
		return new FilterElement({
			id: node.key,
			position: node.position,
			size: { width: UNASSIGNED_SIZE, height: UNASSIGNED_SIZE },
			ports: {
				items: [
					{
						id: "in",
						group: "in",
						attrs: { portBody: { magnet: false } },
					},
					{
						id: "out",
						group: "out",
						attrs: { portBody: { magnet: !!node.outSlot } },
					},
				],
			},
			attrs: {
				root: {
					title: `Unassigned: place ${node.slot?.index ?? ""} of the chain, without a filter, which SolarNode skips. Drop a filter on it to put the filter here, or delete it to move the filters after it down one place.`,
				},
				body: { rx: 8, ry: 8, class: this.#nodeClass(node) },
				icon: { text: "" },
				ext: { text: "" },
				label: { text: UNASSIGNED_UID, class: "sim-node-label" },
				badge: { text: "", display: "none" },
				badgeBody: { display: "none" },
			},
			sim: node,
		});
	}

	/** The run results badge of a node. */
	#badgeAttrs(node: FilterNode): dia.Cell.Selectors {
		const stats = this.#run?.stats.get(node.filterId);
		if (!this.#run || (!stats && node.root)) {
			return {
				badge: { text: "", display: "none" },
				badgeBody: { display: "none" },
			};
		}
		const classes = ["sim-badge"];
		let text = "0";
		if (!stats) {
			classes.push("is-idle");
		} else {
			text = `${stats.in} → ${stats.out}`;
			if (stats.errors) {
				classes.push("is-error");
			} else if (stats.dropped) {
				classes.push("is-dropped");
			} else if (stats.changed) {
				classes.push("is-changed");
			}
		}
		const className = classes.join(" ");
		return {
			badge: {
				text,
				display: "block",
				class: `${className} sim-badge-text`,
			},
			badgeBody: { display: "block", class: className },
		};
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
						: `The end of ${quoted(chainLabel)}. The flow continues with the filter it links to.`,
				},
				body: { class: end.main ? "sim-end is-output" : "sim-end" },
				dot: { class: "sim-end-dot" },
				label: {
					text: end.main
						? this.#run
							? `Output: ${this.#run.output}`
							: "Output"
						: `End of ${chainLabel}`,
					class: "sim-end-label",
				},
			},
			sim: end,
		});
	}

	#linkCell(link: DrawnLink): dia.Link {
		if (link.kind === "source") {
			return new FlowShape({
				id: link.key,
				source: { id: link.source.key, port: link.source.port },
				target: { id: link.target.key, port: link.target.port },
				attrs: { line: { stroke: this.#colors.flow } },
				vertices: link.vertices,
				router: { name: "normal" },
				sim: link,
			});
		}
		if (link.kind === "flow") {
			return new FlowShape({
				id: link.key,
				source: { id: link.source.key, port: link.source.port },
				target: { id: link.target.key, port: link.target.port },
				attrs: { line: { stroke: this.#colors.flow } },
				// the layout routes the lines between rows itself
				...(link.vertices
					? { vertices: link.vertices, router: { name: "normal" } }
					: {}),
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

	#sourceCell(node: SourceNode): dia.Element {
		const source = this.#datumSources.find(
			(d) => d.id === node.datumSourceId,
		);
		const polled = this.#run?.polled?.get(node.datumSourceId);
		const what = source?.static ? "datum" : "polled";
		const badge: dia.Cell.Selectors =
			polled === undefined
				? {
						badge: { text: "", display: "none" },
						badgeBody: { display: "none" },
					}
				: {
						badge: {
							text: `${polled} ${what}`,
							display: "block",
							class: `sim-badge${polled ? "" : " is-idle"} sim-badge-text`,
						},
						badgeBody: {
							display: "block",
							class: `sim-badge${polled ? "" : " is-idle"}`,
						},
					};
		return new FilterElement({
			id: node.key,
			position: node.position,
			ports: {
				items: [
					{
						id: "out",
						group: "out",
						attrs: { portBody: { magnet: false } },
					},
				],
			},
			attrs: {
				root: { title: this.#sourceTooltip(source, polled) },
				body: { rx: NODE_SIZE / 2, ry: NODE_SIZE / 2 },
				icon: this.#iconAttrs(
					source?.static
						? STATIC_SOURCE_ICON
						: datumSourceIcon(source?.factoryUid ?? ""),
				),
				ext: { text: "" },
				label: {
					text: source?.static
						? "Static"
						: source?.name ||
							source?.sourceId ||
							source?.type ||
							node.datumSourceId,
					class: "sim-node-label",
				},
				...badge,
			},
			sim: node,
		}).attr("body/class", this.#sourceClass(node));
	}

	/** The attributes of a node's icon, with its glyph's ink centred. */
	#iconAttrs(icon: string): dia.Cell.Selectors[string] {
		const text = iconGlyph(icon);
		return {
			text,
			transform: glyphCenterTransform(text, ICON_SIZE),
			class: "sim-node-icon",
		};
	}

	#sourceClass(node: SourceNode): string {
		const classes = ["sim-node", "is-source"];
		const source = this.#datumSources.find(
			(d) => d.id === node.datumSourceId,
		);
		if (source?.problem) {
			classes.push("is-problem");
		}
		// the datum source of the selected datum
		const seq = this.#path?.seq;
		if (seq !== undefined) {
			const origin = this.#run?.datum.find((t) => t.seq === seq)?.origin;
			if (
				origin?.type === "Polled"
					? origin.datumSourceId === node.datumSourceId
					: origin?.type === "Input" &&
						node.datumSourceId === STATIC_SOURCE_ID
			) {
				classes.push("is-visited");
			}
		}
		return classes.join(" ");
	}

	#sourceTooltip(
		source: DatumSourceSummary | undefined,
		polled: number | undefined,
	): string {
		if (!source) {
			return "A datum source.";
		}
		if (source.static) {
			return [
				"Static: the datum of the Datum tab, which the Main chain processes in order.",
				source.problem
					? `Problem: ${source.problem}`
					: `${source.count ?? 0} datum.`,
				...(polled !== undefined
					? [`Last run: ${polled} input datum.`]
					: []),
				"Click to show them in the Datum tab.",
			].join("\n");
		}
		const lines = [`${source.type}: a datum source`];
		if (source.name) {
			lines.push(`Service Name: ${source.name}`);
		}
		if (source.sourceId) {
			lines.push(`Source ID: ${source.sourceId}`);
		}
		if (source.schedule) {
			lines.push(`Schedule: ${source.schedule}`);
		}
		if (source.problem) {
			lines.push(`Problem: ${source.problem}`);
		}
		lines.push("The Main chain processes the datum it polls.");
		if (polled !== undefined) {
			lines.push(`Last run: ${polled} datum polled.`);
		}
		lines.push("Click to show its settings in the Datum Sources tab.");
		return lines.join("\n");
	}

	#nodeClass(node: FilterNode): string {
		if (node.unassigned) {
			const classes = ["sim-node", "is-unassigned"];
			if (node.key === this.#drop?.cell.id) {
				classes.push("is-drop");
			}
			if (node.key === this.#selected) {
				classes.push("is-selected");
			}
			return classes.join(" ");
		}
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
				ws.isUnknown(filter) ? "is-unknown" : "is-unsupported",
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
		if (this.#path?.current === node.filterId) {
			classes.push("is-current");
		} else if (this.#path?.visited.has(node.filterId)) {
			classes.push(
				this.#path.dropped.has(node.filterId)
					? "is-dropped"
					: "is-visited",
			);
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
			const files = ws.externalFilesOf(filter.id);
			return (
				"The Main chain: the filter chain the Datum Queue applies to every datum." +
				(files.length ? `\nFrom ${externalFilesText(files)}.` : "")
			);
		}
		if (ws.isUnknown(filter)) {
			return `${quoted(ws.label(filter))}: an unknown filter, which a chain uses by its Service Name but whose settings are not in this configuration. Datum pass through it unchanged.`;
		}
		const type = ws.type(filter.factoryUid);
		const unavailable = type
			? undefined
			: ws.unavailableType(filter.factoryUid);
		const lines = [
			type
				? type.name
				: unavailable
					? `${unavailable.name}: not available. ${unavailable.reason}`
					: `Unsupported filter type ${filter.factoryUid}`,
		];
		if (type?.bundle) {
			lines.push(`A custom filter, from ${bundleLabel(type.bundle)}.`);
		}
		if (filter.instanceKey) {
			lines.push(`Instance ID: ${filter.instanceKey}`);
		}
		const files = ws.externalFilesOf(filter.id);
		if (files.length) {
			lines.push(`From ${externalFilesText(files)}.`);
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
		if (this.#run) {
			const s = this.#run.stats.get(node.filterId);
			lines.push(
				s
					? `Last run: ${s.in} datum in, ${s.out} out` +
							(s.dropped ? `, ${s.dropped} discarded` : "") +
							(s.changed ? `, ${s.changed} changed` : "") +
							(s.skipped ? `, ${s.skipped} ignored` : "") +
							(s.errors ? `, ${s.errors} errors` : "")
					: "Last run: no datum reached this filter.",
			);
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
			elementMove:
				node?.kind === "filter" && !node.main && !node.unassigned,
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
		paper.on("element:pointerclick", (view: dia.ElementView) => {
			const source = this.#sourceNodes.get(String(view.model.id));
			if (source) {
				this.dispatchEvent(
					new CustomEvent<string>("datumsourceselect", {
						detail: source.datumSourceId,
					}),
				);
			} else {
				this.select(String(view.model.id));
			}
		});
		paper.on("blank:pointerclick", () => {
			this.select(null);
			this.dispatchEvent(new Event("blankclick"));
		});
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
				node?.kind === "filter" && !node.main && !node.unassigned
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
	 * Handle the mouse wheel: zoom with Ctrl or Cmd (or a pinch gesture), pan
	 * sideways with Shift, and otherwise pan.
	 */
	#wheel(evt: dia.Event, x: number, y: number): void {
		const e = evt.originalEvent as WheelEvent | undefined;
		if (!e) {
			return;
		}
		evt.preventDefault();
		// some browsers report mouse wheel steps in lines or pages, not pixels
		const unit =
			e.deltaMode === WheelEvent.DOM_DELTA_LINE
				? WHEEL_LINE
				: e.deltaMode === WheelEvent.DOM_DELTA_PAGE
					? this.#el.clientHeight
					: 1;
		let dx = e.deltaX * unit;
		let dy = e.deltaY * unit;
		if (e.ctrlKey || e.metaKey) {
			this.#zoomAt(this.paper.scale().sx * Math.exp(-dy * 0.01), {
				x,
				y,
			});
			return;
		}
		if (e.shiftKey && dx === 0) {
			// Shift scrolls sideways, which some browsers leave to the page to do
			dx = dy;
			dy = 0;
		}
		const { tx, ty } = this.paper.translate();
		this.paper.translate(tx - dx, ty - dy);
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
		this.#setDrop(
			this.#findDrop(
				point,
				(slot) => this.#canDrop(node, slot),
				node.key,
			),
		);
	}

	#dragEnd(view: dia.ElementView): void {
		const drag = this.#drag;
		const drop = this.#drop;
		this.#drag = null;
		this.#setDrop(null);
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
		const slot = drop?.slot;
		if (slot) {
			if (node.root) {
				ws.insert(node.filterId, slot);
			} else if (node.slot) {
				ws.move(node.slot, slot);
			}
		} else if (node.root) {
			ws.setPosition(node.filterId, { x: position.x, y: position.y });
		} else if (node.slot && Math.hypot(dx, dy) > DETACH_DISTANCE) {
			const problem = ws.takeOutProblem(node.slot);
			if (problem) {
				showToast(problem);
			} else {
				ws.detach(node.slot, { x: position.x, y: position.y });
			}
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
		if (!node.slot) {
			return false;
		}
		const sameChain = slot.chainId === node.slot.chainId;
		return (
			this.#ws.canInsert(node.filterId, slot, sameChain) &&
			// a filter an External Settings file puts in its chain stays there
			(sameChain || !this.#ws.takeOutProblem(node.slot))
		);
	}

	/**
	 * Find where a filter dropped at a point would go: an Unassigned place
	 * under the point, or else the link closest to it, within the drop
	 * distance.
	 *
	 * @param point the point, in canvas coordinates
	 * @param accept a test for the chain slot
	 * @param exclude the key of a node whose links, and those of the nodes
	 *     drawn under it, are left out
	 * @param fill `false` to leave out Unassigned places
	 */
	#findDrop(
		point: Point,
		accept: (slot: ChainSlot) => boolean,
		exclude?: string,
		fill = true,
	): Drop | null {
		const distance = DROP_DISTANCE / this.paper.scale().sx;
		if (fill) {
			for (const node of this.#nodes.values()) {
				if (
					node.kind !== "filter" ||
					!node.unassigned ||
					!node.slot ||
					(exclude && isWithin(node.key, exclude))
				) {
					continue;
				}
				const slot: ChainSlot = { ...node.slot, fill: true };
				const cell = this.graph.getCell(node.key);
				if (
					cell?.isElement() &&
					cell
						.getBBox()
						.inflate(distance / 2)
						.containsPoint(point) &&
					accept(slot)
				) {
					return { cell, slot };
				}
			}
		}
		const link = this.#findDropLink(point, accept, exclude);
		const slot = (link?.get("sim") as FlowLink | undefined)?.slot;
		return link && slot ? { cell: link, slot } : null;
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

	#setDrop(drop: Drop | null): void {
		const previous = this.#drop;
		if (drop?.cell === previous?.cell) {
			return;
		}
		this.#drop = drop;
		if (previous?.cell.isLink()) {
			previous.cell.attr("line", {
				stroke: this.#colors.flow,
				strokeWidth: FLOW_WIDTH,
			});
		} else if (previous) {
			this.#refreshNode(String(previous.cell.id));
		}
		if (drop?.cell.isLink()) {
			drop.cell.attr("line", {
				stroke: this.#colors.drop,
				strokeWidth: 4,
			});
		} else if (drop) {
			this.#refreshNode(String(drop.cell.id));
		}
	}

	#bindDropEvents(): void {
		const el = this.#el;
		el.addEventListener("dragover", (e) => {
			const types = e.dataTransfer?.types ?? [];
			const unassigned = types.includes(UNASSIGNED_MIME);
			if (unassigned || types.includes(FILTER_TYPE_MIME)) {
				e.preventDefault();
				e.dataTransfer!.dropEffect = "copy";
				const point = this.paper.clientToLocalPoint(
					e.clientX,
					e.clientY,
				);
				// an Unassigned place can only be inserted, not fill another
				this.#setDrop(
					this.#findDrop(point, () => true, undefined, !unassigned),
				);
			} else if (types.includes("Files")) {
				e.preventDefault();
				e.dataTransfer!.dropEffect = "copy";
			}
		});
		el.addEventListener("dragleave", (e) => {
			if (!(
				e.relatedTarget instanceof Node && el.contains(e.relatedTarget)
			)) {
				this.#setDrop(null);
			}
		});
		el.addEventListener("drop", (e) => {
			const dt = e.dataTransfer;
			if (!dt) {
				return;
			}
			const factoryUid = dt.getData(FILTER_TYPE_MIME);
			const slot = this.#drop?.slot;
			this.#setDrop(null);
			if (dt.types.includes(UNASSIGNED_MIME)) {
				e.preventDefault();
				if (slot && !slot.fill) {
					this.#ws.addUnassigned(slot);
				} else {
					showToast(
						`Drop ${UNASSIGNED_UID} on a link of a chain, to insert a place without a filter there.`,
					);
				}
			} else if (factoryUid) {
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
