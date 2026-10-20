/**
 * A JSON formatter in the style of Prettier: a value goes on one line if it
 * fits, and otherwise each of its items goes on its own line.
 *
 * It works from the JSON's tokens, not `JSON.parse()`, so numbers and strings
 * keep their exact text: a datum value like `98765432.123456789` is not
 * rounded, and escapes like `°` stay as they are.
 */

/** The line width a value must fit in to go on one line. */
const PRINT_WIDTH = 80;

/** The indent of each level. */
const INDENT = "  ";

type JsonNode =
	| { kind: "object"; entries: { key: string; value: JsonNode }[] }
	| { kind: "array"; items: JsonNode[] }
	| { kind: "literal"; text: string };

/** A JSON syntax problem, at a position in the text. */
export class JsonSyntaxError extends Error {
	/**
	 * Constructor.
	 *
	 * @param message the problem
	 * @param pos the position in the text
	 */
	constructor(
		message: string,
		readonly pos: number,
	) {
		super(message);
		this.name = "JsonSyntaxError";
	}
}

const LITERAL = /-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?|true|false|null/y;
// JSON strings cannot have unescaped control characters
// eslint-disable-next-line no-control-regex
const STRING = /"(?:[^"\\\u0000-\u001f]|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*"/y;

/** Parse JSON into a tree that keeps each literal's text. */
class Parser {
	#pos = 0;

	constructor(readonly text: string) {}

	parse(): JsonNode {
		const node = this.#value();
		this.#space();
		if (this.#pos < this.text.length) {
			this.#fail("Unexpected text after the JSON value");
		}
		return node;
	}

	#fail(message: string): never {
		throw new JsonSyntaxError(message, this.#pos);
	}

	#space(): void {
		while (/\s/.test(this.text.charAt(this.#pos))) {
			this.#pos++;
		}
	}

	#value(): JsonNode {
		this.#space();
		const c = this.text.charAt(this.#pos);
		if (c === "{") {
			return this.#object();
		}
		if (c === "[") {
			return this.#array();
		}
		if (c === '"') {
			return { kind: "literal", text: this.#string() };
		}
		LITERAL.lastIndex = this.#pos;
		const m = LITERAL.exec(this.text);
		if (!m) {
			this.#fail(
				c ? `Unexpected ${JSON.stringify(c)}` : "Missing a value",
			);
		}
		this.#pos += m[0].length;
		return { kind: "literal", text: m[0] };
	}

	#string(): string {
		STRING.lastIndex = this.#pos;
		const m = STRING.exec(this.text);
		if (!m) {
			this.#fail("Unterminated or invalid string");
		}
		this.#pos += m[0].length;
		return m[0];
	}

	#expect(c: string): void {
		this.#space();
		if (this.text.charAt(this.#pos) !== c) {
			this.#fail(`Expected ${JSON.stringify(c)}`);
		}
		this.#pos++;
	}

	#object(): JsonNode {
		this.#pos++;
		const entries: { key: string; value: JsonNode }[] = [];
		this.#space();
		if (this.text.charAt(this.#pos) === "}") {
			this.#pos++;
			return { kind: "object", entries };
		}
		for (;;) {
			this.#space();
			if (this.text.charAt(this.#pos) !== '"') {
				this.#fail("Expected a property name in double quotes");
			}
			const key = this.#string();
			this.#expect(":");
			entries.push({ key, value: this.#value() });
			this.#space();
			const c = this.text.charAt(this.#pos);
			if (c === ",") {
				this.#pos++;
			} else if (c === "}") {
				this.#pos++;
				return { kind: "object", entries };
			} else {
				this.#fail('Expected "," or "}"');
			}
		}
	}

	#array(): JsonNode {
		this.#pos++;
		const items: JsonNode[] = [];
		this.#space();
		if (this.text.charAt(this.#pos) === "]") {
			this.#pos++;
			return { kind: "array", items };
		}
		for (;;) {
			items.push(this.#value());
			this.#space();
			const c = this.text.charAt(this.#pos);
			if (c === ",") {
				this.#pos++;
			} else if (c === "]") {
				this.#pos++;
				return { kind: "array", items };
			} else {
				this.#fail('Expected "," or "]"');
			}
		}
	}
}

/** Print a value on one line. */
function flat(node: JsonNode): string {
	switch (node.kind) {
		case "literal":
			return node.text;
		case "array":
			return `[${node.items.map(flat).join(", ")}]`;
		case "object":
			return node.entries.length
				? `{ ${node.entries.map((e) => `${e.key}: ${flat(e.value)}`).join(", ")} }`
				: "{}";
	}
}

/**
 * Print a value that starts after some text on its line.
 *
 * @param node the value
 * @param indent the indent of the value's line
 * @param used the length of the line before the value
 */
function print(node: JsonNode, indent: string, used: number): string {
	const one = flat(node);
	if (node.kind === "literal" || used + one.length <= PRINT_WIDTH) {
		return one;
	}
	const inner = indent + INDENT;
	if (node.kind === "array") {
		const items = node.items.map(
			(item) => inner + print(item, inner, inner.length),
		);
		return `[\n${items.join(",\n")}\n${indent}]`;
	}
	const entries = node.entries.map((e) => {
		const prefix = `${inner}${e.key}: `;
		return prefix + print(e.value, inner, prefix.length);
	});
	return `{\n${entries.join(",\n")}\n${indent}}`;
}

/**
 * Format JSON.
 *
 * @param text the JSON
 * @returns the formatted JSON, or an empty string for blank text
 * @throws JsonSyntaxError if the text is not valid JSON
 */
export function formatJson(text: string): string {
	if (!text.trim()) {
		return "";
	}
	return print(new Parser(text).parse(), "", 0);
}
