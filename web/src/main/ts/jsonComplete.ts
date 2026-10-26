/**
 * Completion of the keys the JSON inputs' schemas have, with descriptions,
 * and of `created` dates.
 */

import {
	autocompletion,
	closeBrackets,
	closeBracketsKeymap,
	snippetCompletion,
	type Completion,
	type CompletionContext,
	type CompletionResult,
} from "@codemirror/autocomplete";
import { syntaxTree } from "@codemirror/language";
import type { Extension } from "@codemirror/state";
import { keymap } from "@codemirror/view";
import type { SyntaxNode } from "@lezer/common";
import type { JsonSchema } from "./jsonLint.ts";

/** A key an object of a schema can have. */
interface KeyInfo {
	key: string;
	/** A short description, shown beside the key. */
	detail: string;
	/** A longer description, shown in the completion's info box. */
	info: string;
	/** The kind of value, which sets what completing the key inserts. */
	value: "object" | "array" | "string" | "number";
}

const SAMPLE_KEYS: KeyInfo[] = [
	{
		key: "i",
		detail: "Instantaneous",
		info: "Instantaneous properties: numbers measured at the datum's time, like watts or voltage.",
		value: "object",
	},
	{
		key: "a",
		detail: "Accumulating",
		info: "Accumulating properties: numbers that only grow, like wattHours.",
		value: "object",
	},
	{
		key: "s",
		detail: "Status",
		info: "Status properties: text or numbers, like a state or a mode.",
		value: "object",
	},
	{
		key: "t",
		detail: "Tags",
		info: 'Tags: an array of strings, like ["alarm"], which Required Tag settings match.',
		value: "array",
	},
];

const DATUM_KEYS: KeyInfo[] = [
	{
		key: "created",
		detail: "Date",
		info: 'The datum date: a date string like "2026-10-21 08:30:00Z", or epoch milliseconds. A datum without one is a second after the datum before it.',
		value: "string",
	},
	{
		key: "sourceId",
		detail: "Source ID",
		info: "The datum's source ID, which filters match their Source ID patterns against. Required.",
		value: "string",
	},
	...SAMPLE_KEYS,
	{
		key: "samples",
		detail: "Nested samples",
		info: 'The "i", "a", "s", and "t" properties nested in an object, which SolarNode also reads.',
		value: "object",
	},
	{
		key: "nodeId",
		detail: "Node ID",
		info: "The ID of the node the datum is from, for a node datum.",
		value: "number",
	},
	{
		key: "locationId",
		detail: "Location ID",
		info: "The ID of the location the datum is from, for a location datum like weather.",
		value: "number",
	},
];

const METADATA_KEYS: KeyInfo[] = [
	{
		key: "m",
		detail: "Metadata",
		info: "General metadata: names and values.",
		value: "object",
	},
	{
		key: "pm",
		detail: "Property metadata",
		info: 'Property metadata: an object of objects, like {"tariffs": {"schedule": "…"}}, which filters read by path, like /pm/tariffs/schedule.',
		value: "object",
	},
	{
		key: "t",
		detail: "Tags",
		info: 'Tags: an array of strings, like ["solar"].',
		value: "array",
	},
];

/**
 * Get the keys of an object, from its path in the JSON: property names, and
 * `[]` for array items.
 */
function keysAt(schema: JsonSchema, path: string[]): KeyInfo[] {
	switch (schema) {
		case "datumList":
			if (path.length === 0 || (path.length === 1 && path[0] === "[]")) {
				return DATUM_KEYS;
			}
			return path[path.length - 1] === "samples" ? SAMPLE_KEYS : [];
		case "metadata":
			return path.length === 0 ? METADATA_KEYS : [];
	}
}

/** Get the name of a property node. */
function propertyName(node: SyntaxNode, text: string): string {
	const name = node.getChild("PropertyName");
	if (!name) {
		return "";
	}
	try {
		return JSON.parse(text.slice(name.from, name.to)) as string;
	} catch {
		return text.slice(name.from + 1, name.to - 1);
	}
}

/** Get the path of an object node: property names, and `[]` for array items. */
function pathOf(obj: SyntaxNode, text: string): string[] {
	const path: string[] = [];
	for (let n = obj.parent; n; n = n.parent) {
		if (n.name === "Property") {
			path.unshift(propertyName(n, text));
		} else if (n.name === "Array") {
			path.unshift("[]");
		}
	}
	return path;
}

/** Make the completion of a key: its name, a colon, and an empty value. */
function keyCompletion(info: KeyInfo, nameOnly: boolean): Completion {
	const base = {
		// the typed text starts with the quote, which the label has to match
		label: `"${info.key}"`,
		displayLabel: info.key,
		detail: info.detail,
		info: info.info,
		type: "property",
	};
	if (nameOnly) {
		return { ...base, apply: `"${info.key}"` };
	}
	const value = {
		object: "{${}}",
		array: "[${}]",
		string: '"${}"',
		number: "${}",
	}[info.value];
	return snippetCompletion(`"${info.key}": ${value}`, base);
}

/** Get the date completions of a `created` value: now, as a string or epoch milliseconds. */
function dateCompletions(): Completion[] {
	const now = new Date();
	const text = now.toISOString().replace("T", " ");
	return [
		{
			label: `"${text}"`,
			detail: "now, as a date",
			type: "constant",
		},
		{
			label: String(now.getTime()),
			detail: "now, in epoch milliseconds",
			type: "constant",
		},
	];
}

/**
 * Get the completion source of a JSON input.
 *
 * @param schema the kind of input
 */
function source(schema: JsonSchema) {
	return (context: CompletionContext): CompletionResult | null => {
		const { state, pos } = context;
		const text = state.doc.toString();
		let node: SyntaxNode | null = syntaxTree(state).resolveInner(pos, -1);
		// the text typed so far: a key or value, with its opening quote
		const word = context.matchBefore(/"?[\w-]*/);
		const from = word ? word.from : pos;
		// a closing quote right after the cursor, which closeBrackets added
		const to = text.charAt(pos) === '"' ? pos + 1 : pos;

		for (; node; node = node.parent) {
			if (node.name === "PropertyName") {
				const obj = node.parent?.parent;
				if (!obj || obj.name !== "Object") {
					return null;
				}
				// an existing key keeps its value, so only its name changes
				const nameOnly = !!node.parent?.getChild(":");
				const keys = keysAt(schema, pathOf(obj, text));
				return keys.length
					? {
							from: node.from,
							to: node.to,
							options: keys.map((k) =>
								keyCompletion(k, nameOnly),
							),
							validFor: /^"?[\w-]*"?$/,
						}
					: null;
			}
			if (node.name === "Property") {
				const colon = node.getChild(":");
				if (colon && pos > colon.from) {
					// a value
					if (
						propertyName(node, text) === "created" &&
						keysAt(schema, pathOf(node.parent!, text)).some(
							(k) => k.key === "created",
						)
					) {
						const value = context.matchBefore(/"?[\w\-: .]*/);
						return {
							from: value ? value.from : pos,
							to,
							options: dateCompletions(),
							filter: false,
						};
					}
					return null;
				}
			}
			if (node.name === "Object") {
				if (!context.explicit && !word?.text) {
					return null;
				}
				const present = new Set(
					node
						.getChildren("Property")
						.map((p) => propertyName(p, text)),
				);
				const keys = keysAt(schema, pathOf(node, text)).filter(
					(k) => !present.has(k.key),
				);
				return keys.length
					? {
							from,
							to,
							options: keys.map((k) => keyCompletion(k, false)),
							validFor: /^"?[\w-]*$/,
						}
					: null;
			}
			if (node.name === "Array" || node.name === "String") {
				return null;
			}
		}
		return null;
	};
}

/**
 * Get the editor extensions that complete a JSON input's keys, and close
 * brackets and quotes.
 *
 * @param schema the kind of input
 * @returns the extensions
 */
export function jsonCompletion(schema: JsonSchema): Extension {
	return [
		autocompletion({ override: [source(schema)], icons: false }),
		closeBrackets(),
		keymap.of(closeBracketsKeymap),
	];
}
