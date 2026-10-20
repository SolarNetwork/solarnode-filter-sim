import { defaultKeymap, history, historyKeymap } from "@codemirror/commands";
import { json } from "@codemirror/lang-json";
import {
	bracketMatching,
	HighlightStyle,
	indentOnInput,
	syntaxHighlighting,
} from "@codemirror/language";
import { tags } from "@lezer/highlight";
import { EditorState, type Extension } from "@codemirror/state";
import { EditorView, keymap, placeholder } from "@codemirror/view";
import { formatJson, JsonSyntaxError } from "./jsonFormat.ts";

/** The line height, in ems, which also sizes an editor from its rows. */
const LINE_HEIGHT = 1.4;

/** The colors of JSON: property names, strings, numbers, and keywords. */
const HIGHLIGHT = HighlightStyle.define([
	{ tag: tags.propertyName, color: "#0550ae" },
	{ tag: tags.string, color: "#116329" },
	{ tag: tags.number, color: "#953800" },
	{ tag: [tags.bool, tags.null], color: "#8250df" },
	{ tag: tags.invalid, color: "var(--bs-danger)" },
]);

/** The look of an editor, like a small Bootstrap form control. */
const THEME = EditorView.theme({
	"&": {
		height: "100%",
		fontSize: "0.8rem",
		backgroundColor: "var(--bs-body-bg)",
	},
	"&.cm-focused": { outline: "none" },
	".cm-scroller": {
		fontFamily: "var(--bs-font-monospace)",
		lineHeight: String(LINE_HEIGHT),
	},
	".cm-content": { padding: "0.25rem 0" },
	".cm-placeholder": { color: "var(--bs-secondary-color)" },
});

/**
 * A JSON editor that takes the place of a text area: the text area stays in
 * the document, hidden, so code that reads and sets its value, listens for its
 * `input` events, focuses it, or marks it invalid works unchanged.
 *
 * Its `value` property and `focus()` method are redirected to the editor, and
 * each edit fires an `input` event on it. An `is-invalid` class on the text
 * area marks the editor, which follows it in the document.
 */
export class JsonEditor {
	readonly view: EditorView;
	readonly #textarea: HTMLTextAreaElement;
	/** `true` while the value is set from code, which fires no `input` event. */
	#setting = false;

	/**
	 * Constructor.
	 *
	 * @param textarea the text area to take the place of
	 * @param extensions more editor extensions, such as validation
	 */
	constructor(textarea: HTMLTextAreaElement, extensions: Extension[] = []) {
		this.#textarea = textarea;
		const wrapper = document.createElement("div");
		wrapper.className = "sim-json-editor form-control form-control-sm p-0";
		wrapper.style.height = `calc(${textarea.rows || 6} * ${LINE_HEIGHT} * 0.8rem + 0.6rem)`;

		const format = document.createElement("button");
		format.type = "button";
		format.className =
			"btn btn-sm btn-light border sim-json-format py-0 px-1";
		format.title = "Format the JSON (Shift+Alt+F)";
		format.setAttribute("aria-label", format.title);
		format.innerHTML = '<i class="bi bi-braces" aria-hidden="true"></i>';
		format.addEventListener("click", () => {
			this.format();
			this.view.focus();
		});

		this.view = new EditorView({
			parent: wrapper,
			state: EditorState.create({
				doc: textarea.value,
				extensions: [
					history(),
					indentOnInput(),
					bracketMatching(),
					json(),
					syntaxHighlighting(HIGHLIGHT),
					EditorView.lineWrapping,
					EditorState.tabSize.of(2),
					placeholder(textarea.placeholder),
					keymap.of([
						{
							key: "Shift-Alt-f",
							run: () => {
								this.format();
								return true;
							},
						},
						...historyKeymap,
						// Ctrl+Enter runs the simulation, rather than adding a line
						...defaultKeymap.filter((b) => b.key !== "Mod-Enter"),
					]),
					EditorView.contentAttributes.of({
						"aria-label":
							textarea.getAttribute("aria-label") ??
							(textarea.id
								? (document.querySelector(
										`label[for="${textarea.id}"]`,
									)?.textContent ?? "")
								: ""),
					}),
					EditorView.updateListener.of((update) => {
						if (update.docChanged && !this.#setting) {
							textarea.dispatchEvent(
								new Event("input", { bubbles: true }),
							);
						}
					}),
					THEME,
					...extensions,
				],
			}),
		});
		wrapper.append(format);
		textarea.hidden = true;
		textarea.classList.add("sim-json-source");
		textarea.after(wrapper);

		Object.defineProperty(textarea, "value", {
			configurable: true,
			get: () => this.view.state.doc.toString(),
			set: (value: string) => this.#setValue(String(value ?? "")),
		});
		textarea.focus = () => this.view.focus();
		// a label for the text area focuses the editor
		if (textarea.id) {
			document
				.querySelector(`label[for="${textarea.id}"]`)
				?.addEventListener("click", (e) => {
					e.preventDefault();
					this.view.focus();
				});
		}
	}

	/** The text area the editor takes the place of. */
	get textarea(): HTMLTextAreaElement {
		return this.#textarea;
	}

	#setValue(value: string): void {
		const doc = this.view.state.doc.toString();
		if (value === doc) {
			return;
		}
		this.#setting = true;
		try {
			this.view.dispatch({
				changes: { from: 0, to: doc.length, insert: value },
			});
		} finally {
			this.#setting = false;
		}
	}

	/**
	 * Format the JSON, as one undoable change. Invalid JSON is left as it is,
	 * and the cursor moves to the problem.
	 *
	 * @returns `true` if it was formatted
	 */
	format(): boolean {
		const doc = this.view.state.doc.toString();
		let formatted: string;
		try {
			formatted = formatJson(doc);
		} catch (e) {
			if (e instanceof JsonSyntaxError) {
				this.view.dispatch({
					selection: { anchor: Math.min(e.pos, doc.length) },
					scrollIntoView: true,
				});
			}
			return false;
		}
		if (formatted !== doc) {
			this.view.dispatch({
				changes: { from: 0, to: doc.length, insert: formatted },
			});
		}
		return true;
	}
}
