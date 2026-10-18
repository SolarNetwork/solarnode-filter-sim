import type { FilterNode } from "./layout.ts";
import type { FilterConfig } from "./model.ts";
import type { Workspace } from "./workspace.ts";

/** What the inspector's buttons do. */
export interface InspectorActions {
	/** Remove the filter from the chain it is drawn in. */
	disconnect(node: FilterNode): void;
	/** Delete the filter. */
	remove(node: FilterNode): void;
}

function element<K extends keyof HTMLElementTagNameMap>(
	tag: K,
	className?: string,
	text?: string,
): HTMLElementTagNameMap[K] {
	const el = document.createElement(tag);
	if (className) {
		el.className = className;
	}
	if (text !== undefined) {
		el.textContent = text;
	}
	return el;
}

function iconButton(
	label: string,
	icon: string,
	className: string,
	onClick: () => void,
): HTMLButtonElement {
	const button = element("button", `btn btn-sm ${className}`);
	button.type = "button";
	const glyph = element("i", `bi bi-${icon} me-1`);
	glyph.setAttribute("aria-hidden", "true");
	button.append(glyph, label);
	button.addEventListener("click", onClick);
	return button;
}

/**
 * The panel that shows the selected filter.
 */
export class Inspector {
	readonly #el: HTMLElement;
	readonly #ws: Workspace;
	readonly #actions: InspectorActions;

	/**
	 * Constructor.
	 *
	 * @param el the panel element
	 * @param ws the workspace
	 * @param actions what the buttons do
	 */
	constructor(el: HTMLElement, ws: Workspace, actions: InspectorActions) {
		this.#el = el;
		this.#ws = ws;
		this.#actions = actions;
		this.show(null);
	}

	/**
	 * Show a filter.
	 *
	 * @param node the selected filter node, or `null` for none
	 */
	show(node: FilterNode | null): void {
		const filter = node ? this.#ws.filter(node.filterId) : undefined;
		if (!node || !filter) {
			this.#showNothing();
			return;
		}
		const ws = this.#ws;
		const type = ws.type(filter.factoryUid);
		const parts: HTMLElement[] = [];

		let title: string;
		if (node.main) {
			title = "Main Filter Chain";
		} else if (ws.isExternal(filter)) {
			title = "External Filter";
		} else {
			title = type?.name ?? "Unsupported Filter";
		}
		parts.push(element("h2", "h5 mb-2", title));

		const about = element("div", "small text-body-secondary mb-3");
		if (node.main) {
			about.textContent =
				"The filter chain the Datum Queue applies to every datum. Its filters run in order, from left to right.";
		} else if (ws.isExternal(filter)) {
			about.textContent =
				"A chain uses this filter by its Service Name, but its settings are not in this configuration. Datum pass through it unchanged, and an export keeps only the chain's reference to it.";
		} else if (!type) {
			about.textContent = `The simulator does not support the ${filter.factoryUid} filter type, so datum pass through it unchanged. Its settings are kept as they are.`;
		} else if (type.description) {
			// the description is HTML from the SolarNode message bundles
			about.innerHTML = type.description;
		}
		parts.push(about);

		const status = this.#status(node);
		if (status) {
			parts.push(
				element("div", "alert alert-secondary small py-2", status),
			);
		}

		parts.push(this.#properties(node, filter));
		const settings = this.#settings(filter);
		if (settings) {
			parts.push(settings);
		}
		if (filter.comment) {
			parts.push(element("h3", "h6 mt-3", "Comment"));
			parts.push(
				element(
					"pre",
					"small bg-body-tertiary p-2 rounded",
					filter.comment,
				),
			);
		}

		const buttons = element("div", "d-flex gap-2 mt-3");
		if (node.slot) {
			buttons.append(
				iconButton(
					"Disconnect",
					"scissors",
					"btn-outline-secondary",
					() => this.#actions.disconnect(node),
				),
			);
		}
		if (!node.main) {
			buttons.append(
				iconButton("Delete", "trash", "btn-outline-danger", () =>
					this.#actions.remove(node),
				),
			);
		}
		if (buttons.childElementCount > 0) {
			parts.push(buttons);
		}
		this.#el.replaceChildren(...parts);
	}

	#showNothing(): void {
		const intro = element(
			"p",
			"text-body-secondary",
			"Select a filter to see its settings.",
		);
		const tips = element("ul", "small text-body-secondary ps-3");
		for (const tip of [
			"Drag a filter from the palette onto a link to add it to a chain there, or onto an empty spot to add it on its own.",
			"Drag a filter onto another link to move it, or away from its chain to disconnect it.",
			"Drag from a port to a filter that is not in a chain to connect it.",
			"Drag the background to pan, and pinch or hold Ctrl while scrolling to zoom.",
		]) {
			tips.append(element("li", "mb-1", tip));
		}
		this.#el.replaceChildren(intro, tips);
	}

	#status(node: FilterNode): string | undefined {
		if (node.root) {
			return "This filter is not in a chain, so it never runs. Drag it onto a link to add it to a chain.";
		}
		if (node.loop) {
			return "This chain contains itself, so SolarNode stops it from running in a loop. Its filters are shown where it first appears.";
		}
		return undefined;
	}

	#properties(node: FilterNode, filter: FilterConfig): HTMLElement {
		const ws = this.#ws;
		const list = element("dl", "row small mb-0");
		const add = (term: string, value: string) => {
			list.append(
				element("dt", "col-5 fw-normal text-body-secondary", term),
				element("dd", "col-7 text-break", value),
			);
		};
		add("Service Name", ws.uid(filter) ?? "(none)");
		if (!ws.isExternal(filter)) {
			add("Instance ID", filter.instanceKey ?? "(not assigned)");
		}
		const chains = ws.chainsOf(filter.id).map((id) => {
			const chain = ws.filter(id);
			return chain ? ws.label(chain) : id;
		});
		if (chains.length > 0) {
			add(chains.length === 1 ? "Chain" : "Chains", chains.join(", "));
		}
		if (ws.isChain(filter)) {
			add("Filters", String(ws.members(filter.id).length));
		}
		if (node.main) {
			add("Datum Queue", "Applies this chain");
		}
		return list;
	}

	#settings(filter: FilterConfig): HTMLElement | undefined {
		const entries = Object.entries(filter.settings ?? {}).filter(
			([key]) => !key.startsWith("transformUids"),
		);
		if (entries.length === 0) {
			return undefined;
		}
		const section = element("div", "mt-3");
		section.append(element("h3", "h6", "Settings"));
		const table = element(
			"table",
			"table table-sm small sim-settings-table",
		);
		const body = element("tbody");
		for (const [key, value] of entries) {
			const row = element("tr");
			const keyCell = element(
				"th",
				"fw-normal text-body-secondary text-break",
				key,
			);
			keyCell.scope = "row";
			const valueCell = element("td");
			valueCell.append(element("code", "sim-setting-value", value));
			const note = filter.notes?.[key];
			if (note) {
				valueCell.append(
					element("div", "text-body-secondary fst-italic", note),
				);
			}
			row.append(keyCell, valueCell);
			body.append(row);
		}
		table.append(body);
		section.append(table);
		return section;
	}
}
