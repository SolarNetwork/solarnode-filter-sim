/**
 * Validation of the JSON inputs against their schemas, as CodeMirror lint
 * diagnostics at the exact place of each problem.
 *
 * The checks follow what the simulator and SolarNode read:
 *
 * - a datum (`BasicGeneralDatumDeserializer`) reads `created`, `sourceId`,
 *   `nodeId`, `locationId`, the `i`, `a`, and `s` property maps, the `t` tag
 *   list, and the same inside a nested `samples` object, and ignores other
 *   keys; the simulator needs a `sourceId`;
 * - metadata (`GeneralDatumMetadata`) reads `m`, `pm` (an object of objects),
 *   and `t`, and ignores other keys and values of the wrong type.
 */

import { jsonParseLinter } from "@codemirror/lang-json";
import { ensureSyntaxTree } from "@codemirror/language";
import { linter, type Diagnostic } from "@codemirror/lint";
import type { Extension } from "@codemirror/state";
import type { EditorView } from "@codemirror/view";
import type { SyntaxNode } from "@lezer/common";

/** The kinds of JSON input. */
export type JsonSchema =
	/** A datum, or an array of datum. */
	| "datumList"
	/** Metadata, like `{"m":{…},"pm":{…},"t":[…]}`. */
	| "metadata";

/** A JSON value, with its place in the text. */
type JsonValue =
	| {
			kind: "object";
			from: number;
			to: number;
			props: JsonProp[];
	  }
	| { kind: "array"; from: number; to: number; items: JsonValue[] }
	| {
			kind: "string" | "number" | "boolean" | "null";
			from: number;
			to: number;
			text: string;
	  };

/** An object property, with the place of its name. */
interface JsonProp {
	name: string;
	from: number;
	to: number;
	value: JsonValue | null;
}

/** The date forms SolarNode reads, roughly: a date, and an optional time and zone. */
const DATE =
	/^\d{4}-\d{2}-\d{2}([ T]\d{2}:\d{2}(:\d{2}([.,]\d+)?)?)?\s*(Z|[+-]\d{2}(:?\d{2})?)?$/i;

/** The keys of a datum that SolarNode reads. */
const DATUM_KEYS = new Set([
	"created",
	"sourceId",
	"nodeId",
	"locationId",
	"i",
	"a",
	"s",
	"t",
	"samples",
]);

/** The keys of nested datum samples that SolarNode reads. */
const SAMPLES_KEYS = new Set(["i", "a", "s", "t"]);

const TYPE_NAMES: Record<JsonValue["kind"], string> = {
	object: "an object",
	array: "an array",
	string: "a string",
	number: "a number",
	boolean: "true or false",
	null: "null",
};

/**
 * Convert a syntax node to a value.
 *
 * @returns the value, or `null` if the node is not a JSON value
 */
function toValue(node: SyntaxNode, text: string): JsonValue | null {
	const { from, to } = node;
	switch (node.name) {
		case "Object":
			return {
				kind: "object",
				from,
				to,
				props: node.getChildren("Property").map((p) => {
					const nameNode = p.getChild("PropertyName");
					const valueNode = p.lastChild;
					const raw = nameNode
						? text.slice(nameNode.from, nameNode.to)
						: '""';
					let name: string;
					try {
						name = JSON.parse(raw) as string;
					} catch {
						name = raw.slice(1, -1);
					}
					return {
						name,
						from: nameNode?.from ?? p.from,
						to: nameNode?.to ?? p.to,
						value:
							valueNode && valueNode !== nameNode
								? toValue(valueNode, text)
								: null,
					};
				}),
			};
		case "Array": {
			const items: JsonValue[] = [];
			for (let c = node.firstChild; c; c = c.nextSibling) {
				const v = toValue(c, text);
				if (v) {
					items.push(v);
				}
			}
			return { kind: "array", from, to, items };
		}
		case "String":
			return { kind: "string", from, to, text: text.slice(from, to) };
		case "Number":
			return { kind: "number", from, to, text: text.slice(from, to) };
		case "True":
		case "False":
			return { kind: "boolean", from, to, text: text.slice(from, to) };
		case "Null":
			return { kind: "null", from, to, text: "null" };
		default:
			return null;
	}
}

/** Collect diagnostics. */
class Problems {
	readonly list: Diagnostic[] = [];

	error(at: { from: number; to: number }, message: string): void {
		this.list.push({
			from: at.from,
			to: at.to,
			severity: "error",
			message,
		});
	}

	warning(at: { from: number; to: number }, message: string): void {
		this.list.push({
			from: at.from,
			to: at.to,
			severity: "warning",
			message,
		});
	}

	/** Warn about keys an object has more than once, as only the last counts. */
	duplicates(obj: Extract<JsonValue, { kind: "object" }>): void {
		const seen = new Set<string>();
		for (const p of obj.props) {
			if (seen.has(p.name)) {
				this.warning(
					p,
					`"${p.name}" is here more than once, so only the last one counts.`,
				);
			}
			seen.add(p.name);
		}
	}
}

function stringValue(v: JsonValue): string {
	try {
		return v.kind === "string" ? (JSON.parse(v.text) as string) : "";
	} catch {
		return "";
	}
}

/** Check a property map (`i`, `a`, or `s`). */
function checkSampleMap(p: Problems, prop: JsonProp, numbers: boolean): void {
	const v = prop.value;
	if (!v) {
		return;
	}
	if (v.kind !== "object") {
		p.error(
			v,
			`"${prop.name}" must be an object of property names and values, like {"watts": 123}.`,
		);
		return;
	}
	p.duplicates(v);
	for (const sp of v.props) {
		const sv = sp.value;
		if (!sv) {
			continue;
		}
		if (sv.kind === "object" || sv.kind === "array") {
			p.warning(
				sv,
				`Datum property values are numbers or strings, not ${TYPE_NAMES[sv.kind]}.`,
			);
		} else if (numbers && sv.kind !== "number") {
			p.warning(
				sv,
				`"${prop.name === "i" ? "Instantaneous" : "Accumulating"}" property values are usually numbers; put text in "s" (Status).`,
			);
		}
	}
}

/** Check a tag list (`t`). */
function checkTags(p: Problems, prop: JsonProp): void {
	const v = prop.value;
	if (!v) {
		return;
	}
	if (v.kind !== "array") {
		p.error(v, `"t" must be an array of tag strings, like ["alarm"].`);
		return;
	}
	for (const item of v.items) {
		if (item.kind !== "string") {
			p.error(item, "Tags must be strings.");
		}
	}
}

/** Check the sample keys of a datum or its nested samples. */
function checkSampleKey(p: Problems, prop: JsonProp): boolean {
	switch (prop.name) {
		case "i":
			checkSampleMap(p, prop, true);
			return true;
		case "a":
			checkSampleMap(p, prop, true);
			return true;
		case "s":
			checkSampleMap(p, prop, false);
			return true;
		case "t":
			checkTags(p, prop);
			return true;
		default:
			return false;
	}
}

/** Check a datum. */
function checkDatum(p: Problems, v: JsonValue, label: string): void {
	if (v.kind !== "object") {
		p.error(
			v,
			`${label} must be an object, like {"sourceId": "/meter/1", "i": {"watts": 123}}.`,
		);
		return;
	}
	p.duplicates(v);
	let sourceId = false;
	for (const prop of v.props) {
		const pv = prop.value;
		if (!pv) {
			continue;
		}
		if (checkSampleKey(p, prop)) {
			continue;
		}
		switch (prop.name) {
			case "created":
				if (pv.kind === "string") {
					if (!DATE.test(stringValue(pv).trim())) {
						p.warning(
							pv,
							'SolarNode might not read this date. Use a form like "2026-10-21 08:30:00Z", or epoch milliseconds.',
						);
					}
				} else if (pv.kind !== "number") {
					p.error(
						pv,
						`"created" must be a date string, or a number of epoch milliseconds.`,
					);
				}
				break;
			case "sourceId":
				if (pv.kind !== "string") {
					p.error(pv, `"sourceId" must be a string.`);
				} else if (!stringValue(pv)) {
					p.error(pv, `"sourceId" must not be empty.`);
				}
				sourceId = true;
				break;
			case "nodeId":
			case "locationId":
				if (pv.kind !== "number" || !/^-?\d+$/.test(pv.text)) {
					p.error(pv, `"${prop.name}" must be a whole number.`);
				}
				break;
			case "samples":
				if (pv.kind !== "object") {
					p.error(
						pv,
						`"samples" must be an object with "i", "a", "s", or "t".`,
					);
				} else {
					p.duplicates(pv);
					for (const sp of pv.props) {
						if (!checkSampleKey(p, sp)) {
							p.warning(
								sp,
								`SolarNode ignores "${sp.name}" in samples, which has only "i", "a", "s", and "t".`,
							);
						}
					}
				}
				break;
			default:
				if (
					!DATUM_KEYS.has(prop.name) &&
					!SAMPLES_KEYS.has(prop.name)
				) {
					p.warning(
						prop,
						`SolarNode ignores "${prop.name}". Datum properties go in "i" (Instantaneous), "a" (Accumulating), or "s" (Status), like {"i": {"${prop.name}": …}}.`,
					);
				}
		}
	}
	if (!sourceId) {
		p.error(
			{ from: v.from, to: v.from + 1 },
			`${label} has no "sourceId".`,
		);
	}
}

/** Check metadata. */
function checkMetadata(p: Problems, v: JsonValue, label: string): void {
	if (v.kind !== "object") {
		p.error(
			v,
			`${label} must be an object, like {"m": {…}, "pm": {…}, "t": […]}.`,
		);
		return;
	}
	p.duplicates(v);
	for (const prop of v.props) {
		const pv = prop.value;
		if (!pv) {
			continue;
		}
		switch (prop.name) {
			case "m":
				if (pv.kind !== "object") {
					p.warning(
						pv,
						`SolarNode ignores "m" unless it is an object of names and values.`,
					);
				}
				break;
			case "pm":
				if (pv.kind !== "object") {
					p.warning(
						pv,
						`SolarNode ignores "pm" unless it is an object of objects, like {"tariffs": {…}}.`,
					);
				} else {
					for (const pp of pv.props) {
						if (pp.value && pp.value.kind !== "object") {
							p.warning(
								pp.value,
								`SolarNode ignores "${pp.name}", as each "pm" value must be an object.`,
							);
						}
					}
				}
				break;
			case "t":
				if (pv.kind !== "array") {
					p.warning(
						pv,
						`SolarNode ignores "t" unless it is an array of tag strings.`,
					);
				} else {
					for (const item of pv.items) {
						if (item.kind !== "string") {
							p.warning(
								item,
								"SolarNode ignores tags that are not strings.",
							);
						}
					}
				}
				break;
			default:
				p.warning(
					prop,
					`SolarNode ignores "${prop.name}". Metadata has only "m", "pm", and "t" properties.`,
				);
		}
	}
}

/**
 * Check a JSON input against its schema.
 *
 * @param schema the kind of input
 * @param text the JSON text
 * @param root the root syntax node
 * @returns the problems
 */
function check(
	schema: JsonSchema,
	text: string,
	root: SyntaxNode,
): Diagnostic[] {
	const p = new Problems();
	const node = root.firstChild;
	const v = node ? toValue(node, text) : null;
	if (!v) {
		return [];
	}
	switch (schema) {
		case "datumList":
			if (v.kind === "array") {
				v.items.forEach((item, i) =>
					checkDatum(p, item, `Datum ${i + 1}`),
				);
			} else {
				checkDatum(p, v, "The datum");
			}
			break;
		case "metadata":
			checkMetadata(p, v, "The metadata");
			break;
	}
	return p.list;
}

/** Check a JSON input's syntax, and then its schema. */
function lint(
	schema: JsonSchema,
	syntax: (view: EditorView) => Diagnostic[],
	view: EditorView,
): Diagnostic[] {
	const text = view.state.doc.toString();
	if (!text.trim()) {
		return [];
	}
	const syntaxProblems = syntax(view);
	if (syntaxProblems.length) {
		return syntaxProblems;
	}
	const tree = ensureSyntaxTree(view.state, view.state.doc.length, 200);
	return tree ? check(schema, text, tree.topNode) : [];
}

/**
 * Get the editor extension that validates a JSON input: its syntax, and then
 * its schema.
 *
 * @param schema the kind of input
 * @param onErrors called with the number of errors (not warnings) after each
 *     check
 * @returns the extension
 */
export function jsonLint(
	schema: JsonSchema,
	onErrors?: (count: number) => void,
): Extension {
	const syntax = jsonParseLinter();
	return linter(
		(view: EditorView) => {
			const problems = lint(schema, syntax, view);
			onErrors?.(problems.filter((d) => d.severity === "error").length);
			return problems;
		},
		{ delay: 300 },
	);
}
