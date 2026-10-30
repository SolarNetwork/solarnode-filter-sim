/**
 * The Bootstrap Icons (https://icons.getbootstrap.com/) used for filters, by
 * icon name. The palette shows them as `bi-*` classes, and the canvas draws
 * them as glyphs of the icon font.
 */

import { CHAIN_FACTORY_UID, UNKNOWN_FACTORY_UID } from "./model.ts";

/** The icon of each supported filter type, by factory UID. */
const FILTER_ICONS: Record<string, string> = {
	[CHAIN_FACTORY_UID]: "link-45deg",
	"net.solarnetwork.node.datum.samplefilter.expression": "calculator",
	"net.solarnetwork.node.datum.filter.std.param": "braces",
	"net.solarnetwork.node.datum.samplefilter.prop": "funnel",
	"net.solarnetwork.node.datum.samplefilter.limiter": "hourglass-split",
	"net.solarnetwork.node.datum.samplefilter.downsample": "graph-down",
	"net.solarnetwork.node.datum.samplefilter.virtmeter": "speedometer2",
	"net.solarnetwork.node.datum.samplefilter.opmode": "toggles",
	"net.solarnetwork.node.datum.filter.std.join": "union",
	"net.solarnetwork.node.datum.filter.std.split": "scissors",
	"net.solarnetwork.node.datum.filter.std.unchanged": "pause-circle",
	"net.solarnetwork.node.datum.filter.std.unchangedprop": "dash-square",
	"net.solarnetwork.node.datum.filter.control.update": "joystick",
	"net.solarnetwork.node.datum.filter.std.timeout": "alarm",
	"net.solarnetwork.node.datum.filter.std.timestamp": "clock",
	"net.solarnetwork.node.datum.filter.instructor": "send",
	"net.solarnetwork.node.datum.filter.tariff": "cash-coin",
	"net.solarnetwork.node.datum.filter.pvlib.poa": "brightness-high",
	"net.solarnetwork.node.metrics.service.harvester": "bar-chart-line",
	[UNKNOWN_FACTORY_UID]: "question-circle",
};

/** The icon of each datum source type, by factory UID. */
const DATUM_SOURCE_ICONS: Record<string, string> = {
	"net.solarnetwork.node.datum.fixed": "pin-angle",
	"net.solarnetwork.node.datum.battery.mock": "battery-half",
	"net.solarnetwork.node.datum.energymeter.mock": "lightning-charge",
	"net.solarnetwork.node.datum.tou": "calendar-week",
	// the Operational Mode Invoker, listed with the datum sources
	"net.solarnetwork.node.datum.opmode.invoker": "toggles",
};

/** The icon of a datum source type with no icon of its own. */
const DEFAULT_DATUM_SOURCE_ICON = "broadcast";

/**
 * Get the icon of a datum source type.
 *
 * @param factoryUid the factory UID
 * @returns the icon name
 */
export function datumSourceIcon(factoryUid: string): string {
	return DATUM_SOURCE_ICONS[factoryUid] ?? DEFAULT_DATUM_SOURCE_ICON;
}

/** The icon of a filter type the simulator does not support. */
export const UNSUPPORTED_ICON = "question-square";

/** The icon of the Main chain, where the Datum Queue starts. */
export const MAIN_CHAIN_ICON = "diagram-3";

/** The icon of a filter type with no icon of its own. */
const DEFAULT_ICON = "funnel-fill";

/**
 * Get the icon of a filter type.
 *
 * @param factoryUid the factory UID
 * @param supported `false` if the simulator does not support the type
 * @returns the icon name
 */
export function filterIcon(factoryUid: string, supported = true): string {
	const icon = FILTER_ICONS[factoryUid];
	if (icon) {
		return icon;
	}
	return supported ? DEFAULT_ICON : UNSUPPORTED_ICON;
}

const glyphs = new Map<string, string>();
let probe: HTMLElement | undefined;

/**
 * Get the icon font glyph of an icon, from its `bi-*` CSS rule.
 *
 * @param icon the icon name
 * @returns the glyph, or an empty string if it is not available
 */
export function iconGlyph(icon: string): string {
	let glyph = glyphs.get(icon);
	if (glyph !== undefined) {
		return glyph;
	}
	if (!probe) {
		probe = document.createElement("i");
		probe.setAttribute("aria-hidden", "true");
		probe.style.position = "absolute";
		probe.style.visibility = "hidden";
		document.body.append(probe);
	}
	probe.className = `bi bi-${icon}`;
	const content = getComputedStyle(probe, "::before").content;
	// the content is a quoted string with the glyph character, or "none"
	glyph = /^".+"$/.test(content) ? content.slice(1, -1) : "";
	glyphs.set(icon, glyph);
	return glyph;
}

const glyphShifts = new Map<string, string>();
let measure: CanvasRenderingContext2D | null | undefined;

/**
 * Get the SVG transform that centres an icon font glyph's ink on its text
 * position, for text drawn with JointJS's `middle` anchors. Those centre the
 * glyph's advance and put its baseline 0.3em below the position, while each
 * glyph's ink sits differently around them.
 *
 * @param glyph the glyph, from {@link iconGlyph}
 * @param size the font size, in pixels
 * @returns the transform, or an empty string until the icon font has loaded
 */
export function glyphCenterTransform(glyph: string, size: number): string {
	const font = `${size}px bootstrap-icons`;
	if (!glyph || !document.fonts.check(font, glyph)) {
		return "";
	}
	const cacheKey = `${size}:${glyph}`;
	let transform = glyphShifts.get(cacheKey);
	if (transform !== undefined) {
		return transform;
	}
	if (measure === undefined) {
		measure = document.createElement("canvas").getContext("2d");
	}
	if (!measure) {
		return "";
	}
	measure.font = font;
	measure.textAlign = "center";
	measure.textBaseline = "alphabetic";
	const m = measure.measureText(glyph);
	// the ink's centre, from the advance's centre and the baseline
	const x = (m.actualBoundingBoxRight - m.actualBoundingBoxLeft) / 2;
	const y = (m.actualBoundingBoxDescent - m.actualBoundingBoxAscent) / 2;
	const round = (n: number) => Math.round(n * 100) / 100;
	transform = `translate(${round(-x)},${round(-(0.3 * size + y))})`;
	glyphShifts.set(cacheKey, transform);
	return transform;
}

/**
 * Wait for the icon font, so {@link glyphCenterTransform} can measure its
 * glyphs.
 *
 * @param size the font size, in pixels
 * @returns a promise that resolves once the font has loaded, or failed to
 */
export function iconFontLoaded(size: number): Promise<unknown> {
	return document.fonts.load(`${size}px bootstrap-icons`).catch(() => []);
}
