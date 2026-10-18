/**
 * Support for the HTML in SolarNode setting descriptions and titles.
 */

/** The elements kept from setting HTML. Others are replaced by their text. */
const ALLOWED_ELEMENTS = new Set([
	"A",
	"B",
	"BR",
	"CODE",
	"DD",
	"DIV",
	"DL",
	"DT",
	"EM",
	"I",
	"KBD",
	"LI",
	"OL",
	"P",
	"PRE",
	"SMALL",
	"SPAN",
	"STRONG",
	"SUB",
	"SUP",
	"TABLE",
	"TBODY",
	"TD",
	"TH",
	"THEAD",
	"TR",
	"U",
	"UL",
]);

/** The classes kept: icons and text colors. */
const ALLOWED_CLASS = /^(bi|bi-[\w-]+|text-[\w-]+)$/;

/** Bootstrap 2 era classes in SolarNode messages, and their Bootstrap 5 classes. */
const CLASS_RENAMES: Record<string, string> = {
	"text-error": "text-danger",
};

function clean(node: Node): void {
	for (const child of [...node.childNodes]) {
		if (child.nodeType === Node.TEXT_NODE) {
			continue;
		}
		if (
			!(child instanceof Element) ||
			!ALLOWED_ELEMENTS.has(child.tagName)
		) {
			if (
				child instanceof Element &&
				!["SCRIPT", "STYLE"].includes(child.tagName)
			) {
				clean(child);
				child.replaceWith(...child.childNodes);
			} else {
				child.remove();
			}
			continue;
		}
		const href = child.tagName === "A" ? child.getAttribute("href") : null;
		const classes = (child.getAttribute("class") ?? "")
			.split(/\s+/)
			.map((c) => CLASS_RENAMES[c] ?? c)
			.filter((c) => ALLOWED_CLASS.test(c));
		for (const attr of [...child.attributes]) {
			child.removeAttribute(attr.name);
		}
		if (classes.length > 0) {
			child.setAttribute("class", classes.join(" "));
		}
		if (href && /^(https?:|mailto:)/i.test(href)) {
			child.setAttribute("href", href);
			child.setAttribute("target", "_blank");
			child.setAttribute("rel", "noopener noreferrer");
		}
		clean(child);
	}
}

/**
 * Turn setting HTML into a safe document fragment, keeping only simple text
 * markup and web links.
 *
 * @param html the HTML
 * @returns the fragment
 */
export function safeHtml(html: string): DocumentFragment {
	const template = document.createElement("template");
	template.innerHTML = html;
	const fragment = template.content;
	clean(fragment);
	return fragment;
}
