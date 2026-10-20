import type { AppInfo } from "./model.ts";
import type { ReportContent } from "./results.ts";

/** The name of downloaded reports. */
export const REPORT_FILE_NAME = "solarnode-filter-report.html";

/** The font family of Bootstrap Icons, whose font a report embeds. */
const ICON_FONT_FAMILY = "bootstrap-icons";

/** The icons the report's script shows, which nothing may have yet. */
const SCRIPT_ICONS = ["chevron-right", "chevron-down", "check-lg", "copy"];

/** The styles a report adds to the app's. */
const REPORT_CSS = `
body.sim-report { background-color: var(--bs-body-bg); }
.sim-report .sim-datum-header { cursor: default; }
.sim-report .sim-datum-header:hover { background-color: var(--bs-card-cap-bg); }
.sim-report-header { max-width: 72rem; }
`;

/**
 * The report's script: the interactions within the flow, from the elements'
 * classes and data attributes, as the app's event listeners are not copied.
 */
const REPORT_SCRIPT = `(() => {
	const setCollapsed = (section, collapsed) => {
		section.classList.toggle("is-collapsed", collapsed);
		const b = section.querySelector(".sim-datum-collapse");
		if (b) {
			b.setAttribute("aria-expanded", String(!collapsed));
			b.title = collapsed ? "Expand this datum" : "Collapse this datum to its header";
			b.querySelector("i").className = "bi bi-chevron-" + (collapsed ? "right" : "down");
		}
	};
	const copy = async (text, el) => {
		try {
			await navigator.clipboard.writeText(text);
			return true;
		} catch {
			const s = window.getSelection();
			s.selectAllChildren(el);
			return document.execCommand("copy");
		}
	};
	document.addEventListener("click", async (e) => {
		const t = e.target.closest("button, a");
		if (!t) {
			return;
		}
		if (t.matches(".sim-datum-collapse")) {
			const s = t.closest(".sim-datum");
			setCollapsed(s, !s.classList.contains("is-collapsed"));
		} else if (t.matches(".sim-messages-collapse")) {
			const card = t.closest(".sim-messages");
			const collapsed = !card.classList.contains("is-collapsed");
			card.classList.toggle("is-collapsed", collapsed);
			t.setAttribute("aria-expanded", String(!collapsed));
			t.title = (collapsed ? "Expand" : "Collapse") + " the messages";
			t.querySelector("i").className = "bi bi-chevron-" + (collapsed ? "right" : "down");
		} else if (t.dataset.collapseAll) {
			const collapsed = t.dataset.collapseAll === "true";
			document.querySelectorAll(".sim-datum").forEach((s) => setCollapsed(s, collapsed));
		} else if (t.dataset.filterGroup) {
			const shown = t.getAttribute("aria-pressed") !== "true";
			const group = t.dataset.filterGroup;
			const value = t.dataset.filterValue;
			const v = CSS.escape(value);
			// a datum's step status pills apply only to that datum
			const scope = (group === "status" && t.closest(".sim-datum")) || document;
			scope.querySelectorAll(".sim-filter-pill[data-filter-group=\\"" + group + "\\"][data-filter-value=\\"" + v + "\\"]").forEach((p) => {
				p.setAttribute("aria-pressed", String(shown));
				p.title = (shown ? "Hide" : "Show") + " the " + value + " rows";
			});
			const rows = group === "level"
				? ".sim-messages [data-level=\\"" + v + "\\"]"
				: group === "status"
					? ".sim-step[data-status=\\"" + v + "\\"]"
					: ".sim-datum[data-outcome=\\"" + v + "\\"]";
			scope.querySelectorAll(rows).forEach((r) => r.classList.toggle("is-filtered", !shown));
		} else if (t.matches(".sim-copy")) {
			const text = t.closest(".sim-message").querySelector(".flex-grow-1");
			if (await copy(text.textContent, text)) {
				const i = t.querySelector("i");
				i.className = "bi bi-check-lg";
				setTimeout(() => (i.className = "bi bi-copy"), 1500);
			}
		} else if (t.matches('a[href^="#sim-datum-"]')) {
			const s = document.querySelector(t.getAttribute("href"));
			if (s) {
				s.classList.remove("is-filtered");
				setCollapsed(s, false);
			}
		}
	});
})();`;

function escapeHtml(text: string): string {
	return text.replace(
		/[&<>"]/g,
		(c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!,
	);
}

/**
 * Remove what reaches outside the flow, such as links to the inspector, and
 * the app's selection behaviour.
 *
 * @param body the flow
 */
function detach(body: HTMLElement): void {
	body.querySelectorAll('[data-report="remove"]').forEach((el) =>
		el.remove(),
	);
	body.querySelectorAll<HTMLElement>('[data-report="text"]').forEach((el) => {
		const span = document.createElement("span");
		span.className = "sim-step-filter text-primary-emphasis";
		span.textContent = el.textContent;
		el.replaceWith(span);
	});
	body.querySelectorAll<HTMLElement>(".sim-datum-header").forEach((h) => {
		for (const attr of ["role", "tabindex", "aria-pressed", "title"]) {
			h.removeAttribute(attr);
		}
	});
	body.querySelectorAll<HTMLElement>(".sim-message").forEach((m) => {
		m.classList.remove("list-group-item-action");
		m.removeAttribute("title");
		m.removeAttribute("tabindex");
	});
}

/**
 * Test if a selector, without its pseudo-classes and pseudo-elements, matches
 * anything in a report, to leave out the styles it does not use.
 */
function selectorUsed(selector: string, root: Element): boolean {
	if (SCRIPT_ICONS.some((icon) => selector.includes(`.bi-${icon}`))) {
		return true;
	}
	const plain = selector
		// states the report's script changes, which nothing may have yet
		.replace(/\.is-[\w-]+/g, "")
		.replace(/\[aria-(pressed|expanded)[^\]]*\]/g, "")
		.replace(/::?[a-zA-Z-]+(\([^)]*\))?/g, "")
		.replace(/[>+~]\s*$/, "")
		.trim();
	if (!plain || /(^|[\s>+~,])(html|body|\*)([\s>+~.#[]|$)/.test(plain)) {
		return true;
	}
	try {
		return root.matches(plain) || root.querySelector(plain) !== null;
	} catch {
		// a selector the browser cannot test, which is kept to be safe
		return true;
	}
}

/**
 * Get the CSS of the rules a report uses, from the page's style sheets.
 *
 * @param root the report's content
 * @returns the CSS, and the URL of the Bootstrap Icons font, if any
 */
function usedCss(root: Element): { css: string; iconFont?: string } {
	let iconFont: string | undefined;
	const rules = (list: CSSRuleList, base: string): string[] => {
		const out: string[] = [];
		for (const rule of Array.from(list)) {
			if (rule instanceof CSSStyleRule) {
				const used = rule.selectorText
					.split(",")
					.filter((s) => selectorUsed(s, root));
				if (used.length) {
					out.push(
						`${used.join(",")}{${rule.style.cssText}}${Array.from(
							rule.cssRules ?? [],
						)
							.map((r) => r.cssText)
							.join("")}`,
					);
				}
			} else if (rule instanceof CSSFontFaceRule) {
				if (
					rule.style
						.getPropertyValue("font-family")
						.includes(ICON_FONT_FAMILY)
				) {
					const m = /url\(["']?([^"')]+\.woff2[^"')]*)["']?\)/.exec(
						rule.style.getPropertyValue("src"),
					);
					if (m) {
						iconFont = new URL(m[1], base).href;
					}
				}
			} else if (
				rule instanceof CSSMediaRule ||
				rule instanceof CSSSupportsRule
			) {
				const inner = rules(rule.cssRules, base);
				if (inner.length) {
					out.push(
						`@${rule instanceof CSSMediaRule ? "media" : "supports"} ${rule.conditionText}{${inner.join("")}}`,
					);
				}
			} else if (rule instanceof CSSKeyframesRule) {
				out.push(rule.cssText);
			}
		}
		return out;
	};
	const css: string[] = [];
	for (const sheet of Array.from(document.styleSheets)) {
		try {
			css.push(...rules(sheet.cssRules, sheet.href ?? document.baseURI));
		} catch (e) {
			console.warn("Unable to read a style sheet for the report", e);
		}
	}
	return { css: css.join("\n"), iconFont };
}

/** Get a font as a `data:` URL, so the report needs no other files. */
async function fontDataUrl(url: string): Promise<string | null> {
	try {
		const blob = await (await fetch(url)).blob();
		return await new Promise((resolve, reject) => {
			const reader = new FileReader();
			reader.onload = () => resolve(String(reader.result));
			reader.onerror = () => reject(reader.error);
			reader.readAsDataURL(blob);
		});
	} catch (e) {
		console.warn("Unable to embed the icon font in the report", e);
		return null;
	}
}

/**
 * Build a stand-alone HTML report of an Execution Flow: the flow with the
 * styles it uses, the icon font, and a script for its own interactions.
 *
 * @param content the flow, from the results panel
 * @param app the simulator, if known
 * @returns the HTML
 */
export async function buildReport(
	content: ReportContent,
	app: AppInfo | null,
): Promise<string> {
	const { body } = content;
	detach(body);
	const now = new Date();
	const header = document.createElement("header");
	header.className = "sim-report-header mb-3";
	const title = "SolarNode Filter Simulation Report";
	header.innerHTML = `
		<h1 class="h4 mb-1">${escapeHtml(title)}</h1>
		<p class="small text-body-secondary mb-2">${escapeHtml(
			[
				app ? `${app.name}${app.version ? ` ${app.version}` : ""}` : "",
				`report made ${now.toLocaleString()}`,
			]
				.filter((s) => s)
				.join(" · "),
		)}</p>
		<div class="d-flex flex-wrap align-items-center gap-2">
			<span class="h6 mb-0">Execution Flow</span>
			<span class="small text-body-secondary">${escapeHtml(content.summary)}</span>
			${
				content.collapsible
					? `<span class="ms-auto d-flex gap-2">
				<button type="button" class="btn btn-sm btn-outline-secondary" data-collapse-all="false"><i class="bi bi-arrows-expand" aria-hidden="true"></i> Expand all</button>
				<button type="button" class="btn btn-sm btn-outline-secondary" data-collapse-all="true"><i class="bi bi-arrows-collapse" aria-hidden="true"></i> Collapse all</button>
			</span>`
					: ""
			}
		</div>`;
	const page = document.createElement("main");
	page.className = "container-fluid py-3";
	page.append(header, body);

	const { css, iconFont } = usedCss(page);
	let fontCss = "";
	if (iconFont) {
		const data = await fontDataUrl(iconFont);
		if (data) {
			fontCss = `@font-face{font-display:block;font-family:"${ICON_FONT_FAMILY}";src:url("${data}") format("woff2");}`;
		}
	}
	return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(title)}</title>
<style>
${fontCss}
${css}
${REPORT_CSS}
</style>
</head>
<body class="sim-report">
${page.outerHTML}
<script>
${REPORT_SCRIPT}
</script>
</body>
</html>
`;
}
