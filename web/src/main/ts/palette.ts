import { FILTER_TYPE_MIME, UNASSIGNED_MIME } from "./canvas.ts";
import { filterIcon } from "./icons.ts";
import { UNASSIGNED_UID, type FilterTypeInfo } from "./model.ts";
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

/** The palette's icon for an Unassigned place. */
const UNASSIGNED_ICON = "dash-square-dotted";

/**
 * Show the filter palette: Unassigned and the filter chain first, then the
 * other filter types by name.
 *
 * @param el the palette element
 * @param types the supported filter types
 * @param onAdd called when a filter type is clicked
 * @param onAddUnassigned called when Unassigned is clicked
 */
export function renderPalette(
	el: HTMLElement,
	types: FilterTypeInfo[],
	onAdd: (type: FilterTypeInfo) => void,
	onAddUnassigned: () => void,
): void {
	const sorted = [...types].sort((a, b) =>
		a.chain !== b.chain
			? a.chain
				? -1
				: 1
			: paletteName(a).localeCompare(paletteName(b)),
	);
	el.replaceChildren(
		paletteItem(
			UNASSIGNED_UID,
			UNASSIGNED_ICON,
			`${UNASSIGNED_UID}: a place in a chain without a filter, which SolarNode skips. It keeps the filters after it in their places (their transformUids indexes), and a filter dropped on it takes its place.

Drag onto a link of a chain, or click to add it to the end of the Main chain.`,
			UNASSIGNED_MIME,
			UNASSIGNED_UID,
			onAddUnassigned,
		),
		...sorted.map((type) => {
			const description = plainText(type.description);
			return paletteItem(
				paletteName(type),
				filterIcon(type.factoryUid),
				`${type.name}${description ? `: ${description}` : ""}

Drag onto the canvas, or click to add it to the end of the Main chain.`,
				FILTER_TYPE_MIME,
				type.factoryUid,
				() => onAdd(type),
			);
		}),
	);
}

function paletteItem(
	name: string,
	iconName: string,
	title: string,
	mime: string,
	data: string,
	onAdd: () => void,
): HTMLElement {
	const item = document.createElement("button");
	item.type = "button";
	item.className = "sim-palette-item";
	item.draggable = true;
	item.title = title;

	const icon = document.createElement("span");
	icon.className = "sim-palette-icon";
	const glyph = document.createElement("i");
	glyph.className = `bi bi-${iconName}`;
	glyph.setAttribute("aria-hidden", "true");
	icon.append(glyph);

	const label = document.createElement("span");
	label.className = "sim-palette-label";
	label.textContent = name;
	item.append(icon, label);

	item.addEventListener("dragstart", (e) => {
		if (e.dataTransfer) {
			e.dataTransfer.setData(mime, data);
			e.dataTransfer.effectAllowed = "copy";
			const ghost = dragImage(iconName);
			e.dataTransfer.setDragImage(
				ghost,
				ghost.offsetWidth / 2,
				ghost.offsetHeight / 2,
			);
		}
	});
	item.addEventListener("click", onAdd);
	return item;
}

/**
 * Get the image to drag a palette item with: its icon in an outline without a
 * background, so the canvas links under it stay visible, with the pointer in
 * the middle.
 *
 * @param iconName the icon name
 * @returns the image element, which is in the document but out of view
 */
function dragImage(iconName: string): HTMLElement {
	let ghost = document.querySelector<HTMLElement>(".sim-drag-image");
	if (!ghost) {
		ghost = document.createElement("div");
		ghost.className = "sim-drag-image";
		ghost.setAttribute("aria-hidden", "true");
		ghost.append(document.createElement("i"));
		document.body.append(ghost);
	}
	ghost.firstElementChild!.className = `bi bi-${iconName}`;
	return ghost;
}
