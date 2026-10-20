/**
 * Rendering helpers shared by the execution flow, debug views, and dialogs.
 */

import type { MessageLevel, SamplesInfo, SimulationMessage } from "./model.ts";

/** The badge class of each message level. */
const LEVEL_CLASSES: Record<MessageLevel, string> = {
	Info: "text-bg-secondary",
	Warning: "text-bg-warning",
	Error: "text-bg-danger",
};

/** How long, in milliseconds, a copy button shows that it copied. */
const COPIED_TIME = 1500;

/** The sample property types, in display order. */
export const SAMPLE_TYPES = ["i", "a", "s"] as const;

export type SampleType = (typeof SAMPLE_TYPES)[number];

export const TYPE_NAMES: Record<SampleType | "t", string> = {
	i: "Instantaneous",
	a: "Accumulating",
	s: "Status",
	t: "Tags",
};

/** A change to one datum property. */
export interface PropertyChange {
	type: SampleType | "t";
	key: string;
	kind: "added" | "removed" | "changed";
	from?: unknown;
	to?: unknown;
}

export function element<K extends keyof HTMLElementTagNameMap>(
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

/** Format a property value for display. */
export function formatValue(value: unknown): string {
	if (value === null || value === undefined) {
		return "null";
	}
	if (typeof value === "object") {
		return JSON.stringify(value);
	}
	return String(value);
}

/** Format a timestamp like `2025-10-01 12:00:00Z`. */
export function formatTimestamp(ts: string | undefined): string {
	return ts ? ts.replace("T", " ") : "";
}

export function formatDuration(micros: number | undefined): string {
	if (micros === undefined) {
		return "";
	}
	return micros < 1000
		? `${micros} µs`
		: `${(micros / 1000).toFixed(micros < 10_000 ? 2 : 1)} ms`;
}

export function sameValue(a: unknown, b: unknown): boolean {
	return a === b || JSON.stringify(a) === JSON.stringify(b);
}

/**
 * Get the property changes between two sets of samples.
 *
 * @param from the samples before
 * @param to the samples after
 * @returns the changes
 */
export function diffSamples(
	from: SamplesInfo,
	to: SamplesInfo,
): PropertyChange[] {
	const changes: PropertyChange[] = [];
	for (const type of SAMPLE_TYPES) {
		const a = from[type] ?? {};
		const b = to[type] ?? {};
		for (const [key, value] of Object.entries(b)) {
			if (!(key in a)) {
				changes.push({ type, key, kind: "added", to: value });
			} else if (!sameValue(a[key], value)) {
				changes.push({
					type,
					key,
					kind: "changed",
					from: a[key],
					to: value,
				});
			}
		}
		for (const [key, value] of Object.entries(a)) {
			if (!(key in b)) {
				changes.push({ type, key, kind: "removed", from: value });
			}
		}
	}
	const tagsFrom = new Set(from.t ?? []);
	const tagsTo = new Set(to.t ?? []);
	for (const tag of tagsTo) {
		if (!tagsFrom.has(tag)) {
			changes.push({ type: "t", key: tag, kind: "added" });
		}
	}
	for (const tag of tagsFrom) {
		if (!tagsTo.has(tag)) {
			changes.push({ type: "t", key: tag, kind: "removed" });
		}
	}
	return changes;
}

/**
 * Render a line of a datum's properties, grouped by type.
 *
 * @param label the line label
 * @param samples the properties
 * @returns the line
 */
export function samplesLine(label: string, samples: SamplesInfo): HTMLElement {
	const line = element("div", "small sim-samples d-flex flex-wrap gap-1");
	line.append(element("span", "fw-semibold me-1", label));
	let empty = true;
	for (const type of SAMPLE_TYPES) {
		for (const [key, value] of Object.entries(samples[type] ?? {})) {
			const chip = element("span", "sim-chip");
			chip.title = `${TYPE_NAMES[type]} property ${key}`;
			chip.append(
				element("span", "sim-chip-type", type),
				`${key} = ${formatValue(value)}`,
			);
			line.append(chip);
			empty = false;
		}
	}
	for (const tag of samples.t ?? []) {
		const chip = element("span", "sim-chip");
		chip.title = `Tag ${tag}`;
		chip.append(element("span", "sim-chip-type", "t"), tag);
		line.append(chip);
		empty = false;
	}
	if (empty) {
		line.append(element("span", "text-body-secondary", "no properties"));
	}
	return line;
}

/**
 * Describe the External Settings files a filter comes from.
 *
 * @param files the files
 * @returns for example "the External Settings file a.csv", or "the External
 *     Settings files a.csv, b.csv, and c.csv"
 */
export function externalFilesText(files: readonly string[]): string {
	if (files.length < 2) {
		return `the External Settings file ${files[0] ?? ""}`;
	}
	const list =
		files.length === 2
			? `${files[0]} and ${files[1]}`
			: `${files.slice(0, -1).join(", ")}, and ${files[files.length - 1]}`;
	return `the External Settings files ${list}`;
}

/**
 * Format a command and its arguments like a shell command line, quoting
 * arguments with spaces or quotes.
 *
 * @param command the command and its arguments
 * @returns the command line
 */
export function commandLine(command: readonly string[]): string {
	return command
		.map((arg) =>
			arg === "" || /[\s'"\\$`]/.test(arg)
				? `'${arg.replace(/'/g, "'\\''")}'`
				: arg,
		)
		.join(" ");
}

/**
 * Get the file name of a command path.
 *
 * @param command the command path
 * @returns the file name
 */
export function commandName(command: string): string {
	const i = command.lastIndexOf("/");
	return i >= 0 && i < command.length - 1
		? command.substring(i + 1)
		: command;
}

/**
 * Copy text to the clipboard.
 *
 * @param text the text
 * @param fallback an element showing the text, which is selected and copied
 *     if the clipboard API is unavailable (outside a secure context)
 * @returns `true` if the text was copied
 */
export async function copyText(
	text: string,
	fallback: HTMLElement,
): Promise<boolean> {
	try {
		if (navigator.clipboard) {
			await navigator.clipboard.writeText(text);
			return true;
		}
	} catch (e) {
		console.warn("Unable to copy to the clipboard", e);
	}
	const selection = window.getSelection();
	if (!selection) {
		return false;
	}
	selection.selectAllChildren(fallback);
	return document.execCommand("copy");
}

/**
 * Render a simulation message as a list group item: its level, its text,
 * which can be selected, and a button that copies the text.
 *
 * @param m the message
 * @param link what clicking the item does, if anything: a click that ends a
 *     text selection does nothing
 * @returns the item
 */
export function messageItem(
	m: SimulationMessage,
	link?: { title: string; show: () => void },
): HTMLElement {
	const item = element(
		"div",
		"list-group-item d-flex gap-2 align-items-start sim-message" +
			(link ? " list-group-item-action" : ""),
	);
	const text = element("span", "flex-grow-1 text-break", m.message);
	const copy = element(
		"button",
		"btn btn-sm btn-link text-body-secondary p-0 sim-tool sim-copy",
	);
	copy.type = "button";
	copy.title = "Copy the message";
	copy.setAttribute("aria-label", copy.title);
	const icon = element("i", "bi bi-copy");
	icon.setAttribute("aria-hidden", "true");
	copy.append(icon);
	copy.addEventListener("click", async (e) => {
		e.stopPropagation();
		if (await copyText(m.message, text)) {
			icon.className = "bi bi-check-lg";
			copy.title = "Copied";
			window.setTimeout(() => {
				icon.className = "bi bi-copy";
				copy.title = "Copy the message";
			}, COPIED_TIME);
		}
	});
	if (link) {
		item.title = link.title;
		item.tabIndex = 0;
		item.addEventListener("click", () => {
			const selection = window.getSelection();
			if (
				selection &&
				!selection.isCollapsed &&
				item.contains(selection.anchorNode)
			) {
				return;
			}
			link.show();
		});
		item.addEventListener("keydown", (e) => {
			if (e.target === item && (e.key === "Enter" || e.key === " ")) {
				e.preventDefault();
				link.show();
			}
		});
	}
	item.append(
		element(
			"span",
			`badge ${LEVEL_CLASSES[m.level] ?? "text-bg-secondary"}`,
			m.level,
		),
		text,
		copy,
	);
	return item;
}
