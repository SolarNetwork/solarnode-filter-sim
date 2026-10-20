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
