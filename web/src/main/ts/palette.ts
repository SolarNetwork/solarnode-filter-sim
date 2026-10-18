import { FILTER_TYPE_MIME } from "./canvas.ts";
import { filterIcon } from "./icons.ts";
import type { FilterTypeInfo } from "./model.ts";
import { shortTypeName } from "./workspace.ts";

/**
 * Get the plain text of an HTML snippet.
 *
 * @param html the HTML
 * @returns the text
 */
export function plainText(html: string | undefined): string {
	if (!html) {
		return "";
	}
	const doc = new DOMParser().parseFromString(html, "text/html");
	return doc.body.textContent?.replace(/\s+/g, " ").trim() ?? "";
}

/**
 * Get the palette name of a filter type.
 *
 * @param type the filter type
 */
export function paletteName(type: FilterTypeInfo): string {
	return type.chain ? "Filter Chain" : shortTypeName(type);
}

/**
 * Show the filter palette: the filter chain first, then the other filter
 * types by name.
 *
 * @param el the palette element
 * @param types the supported filter types
 * @param onAdd called when a filter type is clicked
 */
export function renderPalette(
	el: HTMLElement,
	types: FilterTypeInfo[],
	onAdd: (type: FilterTypeInfo) => void,
): void {
	const sorted = [...types].sort((a, b) =>
		a.chain !== b.chain
			? a.chain
				? -1
				: 1
			: paletteName(a).localeCompare(paletteName(b)),
	);
	el.replaceChildren(...sorted.map((type) => paletteItem(type, onAdd)));
}

function paletteItem(
	type: FilterTypeInfo,
	onAdd: (type: FilterTypeInfo) => void,
): HTMLElement {
	const item = document.createElement("button");
	item.type = "button";
	item.className = "sim-palette-item";
	item.draggable = true;
	const description = plainText(type.description);
	item.title = `${type.name}${description ? `: ${description}` : ""}

Drag onto the canvas, or click to add it to the end of the Main chain.`;

	const icon = document.createElement("span");
	icon.className = "sim-palette-icon";
	const glyph = document.createElement("i");
	glyph.className = `bi bi-${filterIcon(type.factoryUid)}`;
	glyph.setAttribute("aria-hidden", "true");
	icon.append(glyph);

	const label = document.createElement("span");
	label.className = "sim-palette-label";
	label.textContent = paletteName(type);
	item.append(icon, label);

	item.addEventListener("dragstart", (e) => {
		if (e.dataTransfer) {
			e.dataTransfer.setData(FILTER_TYPE_MIME, type.factoryUid);
			e.dataTransfer.effectAllowed = "copy";
		}
	});
	item.addEventListener("click", () => onAdd(type));
	return item;
}
