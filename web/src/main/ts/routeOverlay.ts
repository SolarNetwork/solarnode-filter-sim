import type { DatumRoute } from "./datumRoutes.ts";
import type { Point } from "./workspace.ts";

const SVG_NS = "http://www.w3.org/2000/svg";

/** How far above the entry node a line back through the queue runs. */
const QUEUE_GAP = 60;

/** How far left of the entry node an input datum's line starts. */
const LEAD_IN = 18;

/** The width of a route's line. */
const LINE_WIDTH = 2;

/**
 * The distance between lanes, when there is room: a gap between the lines, so
 * each can be clicked.
 */
const LANE = LINE_WIDTH + 4.5;

/** The narrowest a route's click area gets. */
const MIN_HIT_WIDTH = 7;

/** The closest lanes get, when there are many. */
const MIN_LANE = LINE_WIDTH + 1;

/** The widest the lanes get, from the first to the last, before they close up. */
const MAX_BAND = 64;

/** How far before its last point a discarded datum's end dot is. */
const END_DOT_BACK = 7;

const CHIP_HEIGHT = 14;
const CHIP_GAP = 3;
const CHIP_PADDING = 4;
const CHIP_CHAR_WIDTH = 6.2;

/** A node's bounds on the canvas. */
export interface Box {
	x: number;
	y: number;
	width: number;
	height: number;
}

/** A datum route to draw. */
export interface RouteLine {
	route: DatumRoute;
	color: string;
	/** For a generated datum, the node key of the filter that generated it. */
	fromKey?: string;
	/** A description, for the route's and its badges' tooltips. */
	title: string;
	/** `true` to draw it faded, behind an emphasized route. */
	dim: boolean;
}

/** Where the drawing is, for routes to follow. */
export interface RouteGeometry {
	/** Get the points of a link, as drawn, or `undefined` if it is not drawn. */
	linkPoints(key: string): Point[] | undefined;
	/** Get the bounds of a node, or `undefined` if it is not drawn. */
	box(key: string): Box | undefined;
}

/** The layers to draw routes in. */
export interface RouteLayers {
	/** The lines, under the links and filters. */
	lines: SVGGElement;
	/** The number badges, over everything. */
	badges: SVGGElement;
}

/** How a group of badges grows from its origin. */
interface ChipGroup {
	origin: Point;
	/** `true` to grow left from the origin, else right. */
	left: boolean;
	/** `true` to add rows upwards from the origin, else downwards. */
	up: boolean;
	perRow: number;
	chips: { seq: number; color: string; title: string; dim: boolean }[];
}

function svg<K extends keyof SVGElementTagNameMap>(
	tag: K,
	attrs: Record<string, string | number> = {},
	className?: string,
): SVGElementTagNameMap[K] {
	const el = document.createElementNS(SVG_NS, tag);
	for (const [k, v] of Object.entries(attrs)) {
		el.setAttribute(k, String(v));
	}
	if (className) {
		el.setAttribute("class", className);
	}
	return el;
}

function pathData(points: Point[]): string {
	return points
		.map((p, i) => `${i === 0 ? "M" : "L"} ${p.x} ${p.y}`)
		.join(" ");
}

/** Test if a point is on the way from one point to another, in a straight line. */
function straight(a: Point, b: Point, c: Point): boolean {
	const cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
	const dot = (b.x - a.x) * (c.x - b.x) + (b.y - a.y) * (c.y - b.y);
	return Math.abs(cross) < 1e-6 && dot > 0;
}

/**
 * Offset a line sideways, keeping each segment parallel to the original, so
 * lines that share a path run side by side.
 *
 * @param points the line's points, without repeats
 * @param d the distance, to the right of the line's direction (which is
 *     downwards for a line going right)
 * @returns the offset points
 */
export function offsetLine(points: Point[], d: number): Point[] {
	if (d === 0 || points.length < 2) {
		return points;
	}
	const normals: Point[] = [];
	for (let i = 0; i < points.length - 1; i++) {
		const dx = points[i + 1].x - points[i].x;
		const dy = points[i + 1].y - points[i].y;
		const len = Math.hypot(dx, dy) || 1;
		normals.push({ x: -dy / len, y: dx / len });
	}
	const off = points.map((p, i) => {
		const n1 = normals[Math.max(0, i - 1)];
		const n2 = normals[Math.min(normals.length - 1, i)];
		const mx = n1.x + n2.x;
		const my = n1.y + n2.y;
		const ml = Math.hypot(mx, my);
		if (ml < 1e-6) {
			// the line turns back on itself
			return { x: p.x + n2.x * d, y: p.y + n2.y * d };
		}
		// the corner moves along the bisector, far enough to keep both
		// segments at the distance
		const cos = Math.max((mx * n2.x + my * n2.y) / ml, 0.3);
		const len = d / cos;
		return { x: p.x + (mx / ml) * len, y: p.y + (my / ml) * len };
	});
	// a segment shorter than the offset on the inside of turns would point
	// backwards, so it shrinks to a point instead
	for (let i = 1; i < off.length - 1; i++) {
		const dx = points[i + 1].x - points[i].x;
		const dy = points[i + 1].y - points[i].y;
		const odx = off[i + 1].x - off[i].x;
		const ody = off[i + 1].y - off[i].y;
		if (dx * odx + dy * ody < 0) {
			const mid = {
				x: (off[i].x + off[i + 1].x) / 2,
				y: (off[i].y + off[i + 1].y) / 2,
			};
			off[i] = mid;
			off[i + 1] = mid;
		}
	}
	return off;
}

/**
 * Get a text color that reads well on a background color.
 *
 * @param hex the background color, as `#rrggbb`
 */
function textColor(hex: string): string {
	const n = parseInt(hex.slice(1), 16);
	const r = (n >> 16) & 0xff;
	const g = (n >> 8) & 0xff;
	const b = n & 0xff;
	return 0.299 * r + 0.587 * g + 0.114 * b > 150 ? "#212529" : "#ffffff";
}

/**
 * Get the points of a route: in from the left of its entry node, or for a
 * generated datum from the filter that made it, up to the queue lane, and
 * down into the entry node; then along the links it travelled.
 *
 * @returns the points, and how many lead the line back through the queue
 */
function routePoints(
	line: RouteLine,
	geo: RouteGeometry,
): { points: Point[]; queued: number } {
	const entry = geo.box(line.route.keys[0]);
	if (!entry) {
		return { points: [], queued: 0 };
	}
	const center = {
		x: entry.x + entry.width / 2,
		y: entry.y + entry.height / 2,
	};
	const from = line.fromKey ? geo.box(line.fromKey) : undefined;
	let points: Point[];
	let queued = 0;
	if (from) {
		const x = from.x + from.width / 2;
		const y = entry.y - QUEUE_GAP;
		points = [
			{ x, y: from.y },
			{ x, y },
			{ x: center.x, y },
			{ x: center.x, y: entry.y },
			center,
		];
		queued = 4;
	} else {
		points = [{ x: entry.x - LEAD_IN, y: center.y }, center];
	}
	// a point in the middle of a straight run is left out, as offsetting it
	// would fold a lane back on itself where the run ends in a tight turn
	const fixed = points.length;
	const add = (p: Point) => {
		const prev = points[points.length - 1];
		if (prev.x === p.x && prev.y === p.y) {
			return;
		}
		if (
			points.length > fixed &&
			straight(points[points.length - 2], prev, p)
		) {
			points[points.length - 1] = { x: p.x, y: p.y };
		} else {
			points.push({ x: p.x, y: p.y });
		}
	};
	const { links, keys } = line.route;
	for (let i = 0; i < links.length; i++) {
		const linkPoints = geo.linkPoints(links[i]);
		if (!linkPoints || linkPoints.length === 0) {
			break;
		}
		linkPoints.forEach(add);
		// a continuation line stops short of the next filter, but routes reach
		// its edge, like every other route into it
		const last = linkPoints[linkPoints.length - 1];
		const next = geo.box(keys[i + 1]);
		if (
			next &&
			last.x < next.x &&
			last.y >= next.y &&
			last.y <= next.y + next.height
		) {
			add({ x: next.x, y: last.y });
		}
	}
	return { points, queued };
}

/**
 * Draw datum routes: each in its own lane, with a number badge where it
 * starts and ends.
 *
 * @param layers the layers to draw in, which are cleared first
 * @param lines the routes, in lane order
 * @param geo the drawing's geometry
 */
export function drawRoutes(
	layers: RouteLayers,
	lines: RouteLine[],
	geo: RouteGeometry,
): void {
	layers.lines.replaceChildren();
	layers.badges.replaceChildren();
	const n = lines.length;
	if (n === 0) {
		return;
	}
	const spacing =
		n === 1 ? 0 : Math.max(MIN_LANE, Math.min(LANE, MAX_BAND / (n - 1)));
	const width = LINE_WIDTH;
	const bandHalf = ((n - 1) * spacing) / 2 + width / 2;
	const groups = new Map<string, ChipGroup>();
	const chip = (key: string, group: Omit<ChipGroup, "chips">) => {
		let g = groups.get(key);
		if (!g) {
			g = { ...group, chips: [] };
			groups.set(key, g);
		}
		return g;
	};

	const dimmed: SVGElement[] = [];
	const shown: SVGElement[] = [];
	/** The invisible, wider lines that take the pointer for each route. */
	const hits: SVGElement[] = [];
	lines.forEach((line, lane) => {
		const { route } = line;
		const d = (lane - (n - 1) / 2) * spacing;
		const { points, queued } = routePoints(line, geo);
		const out = line.dim ? dimmed : shown;
		const cls = `sim-route${line.dim ? " is-dim" : ""}`;
		const style = {
			stroke: line.color,
			"stroke-width": width,
			"data-seq": route.seq,
		};
		if (points.length >= 2) {
			const off = offsetLine(points, d);
			if (queued > 0) {
				out.push(
					svg(
						"path",
						{
							...style,
							"stroke-width": Math.max(1.5, width * 0.75),
							d: pathData(off.slice(0, queued)),
						},
						`${cls} is-queued`,
					),
				);
			}
			const main = off.slice(Math.max(0, queued - 1));
			out.push(svg("path", { ...style, d: pathData(main) }, cls));
			const hit = svg(
				"path",
				{
					d: pathData(off),
					"stroke-width": Math.max(MIN_HIT_WIDTH, spacing),
					"data-seq": route.seq,
				},
				"sim-route-hit",
			);
			const title = svg("title");
			title.textContent = line.title;
			hit.append(title);
			hits.push(hit);
			if (
				(route.end === "dropped" || route.end === "stopped") &&
				main.length >= 2
			) {
				const p = main[main.length - 1];
				const q = main[main.length - 2];
				const len = Math.hypot(p.x - q.x, p.y - q.y) || 1;
				const back = Math.min(END_DOT_BACK, len);
				out.push(
					svg(
						"circle",
						{
							cx: p.x - ((p.x - q.x) / len) * back,
							cy: p.y - ((p.y - q.y) / len) * back,
							r: Math.max(3, width * 1.5),
							fill: line.color,
							"data-seq": route.seq,
						},
						`sim-route-end${line.dim ? " is-dim" : ""}`,
					),
				);
			}
		}

		const item = {
			seq: route.seq,
			color: line.color,
			title: line.title,
			dim: line.dim,
		};
		const entry = geo.box(route.keys[0]);
		const from = line.fromKey ? geo.box(line.fromKey) : undefined;
		if (from && entry) {
			chip(`from:${line.fromKey}`, {
				origin: {
					x: from.x + from.width / 2 + bandHalf + CHIP_GAP,
					y: entry.y - QUEUE_GAP - bandHalf - CHIP_GAP,
				},
				left: false,
				up: true,
				perRow: 3,
			}).chips.push(item);
		} else if (entry) {
			chip(`in:${route.keys[0]}`, {
				origin: {
					x: entry.x - LEAD_IN - CHIP_GAP,
					y: entry.y + entry.height / 2 - CHIP_HEIGHT / 2,
				},
				left: true,
				up: false,
				perRow: 2,
			}).chips.push(item);
		}
		const endKey = route.keys[route.keys.length - 1];
		const end = route.end ? geo.box(endKey) : undefined;
		if (end) {
			// right of the end connector, or else above the line out of the
			// filter, clear of its label and run badge
			const output = route.end === "output";
			chip(`end:${endKey}`, {
				origin: {
					x: end.x + end.width + CHIP_GAP + (output ? 3 : 1),
					y: output
						? end.y + end.height / 2 - CHIP_HEIGHT / 2
						: end.y + end.height / 2 - bandHalf - CHIP_GAP,
				},
				left: false,
				up: !output,
				perRow: 4,
			}).chips.push(item);
		}
	});
	layers.lines.append(...dimmed, ...shown, ...hits);

	for (const group of groups.values()) {
		drawChips(layers.badges, group);
	}
}

/** Draw a group of number badges in rows. */
function drawChips(layer: SVGGElement, group: ChipGroup): void {
	const chips = [...group.chips].sort((a, b) => a.seq - b.seq);
	const chipWidth =
		CHIP_PADDING * 2 +
		CHIP_CHAR_WIDTH * Math.max(...chips.map((c) => String(c.seq).length));
	chips.forEach((c, i) => {
		const col = i % group.perRow;
		const row = Math.floor(i / group.perRow);
		const dx = col * (chipWidth + CHIP_GAP);
		const dy = row * (CHIP_HEIGHT + CHIP_GAP);
		const x = group.left
			? group.origin.x - dx - chipWidth
			: group.origin.x + dx;
		const y = group.up
			? group.origin.y - dy - CHIP_HEIGHT
			: group.origin.y + dy;
		const g = svg(
			"g",
			{ transform: `translate(${x} ${y})`, "data-seq": c.seq },
			`sim-route-badge${c.dim ? " is-dim" : ""}`,
		);
		const title = svg("title");
		title.textContent = c.title;
		const label = svg(
			"text",
			{
				x: chipWidth / 2,
				y: CHIP_HEIGHT / 2,
				fill: textColor(c.color),
			},
			"sim-route-badge-text",
		);
		label.textContent = String(c.seq);
		g.append(
			title,
			svg("rect", {
				width: chipWidth,
				height: CHIP_HEIGHT,
				rx: CHIP_HEIGHT / 2,
				fill: c.color,
			}),
			label,
		);
		layer.append(g);
	});
}
