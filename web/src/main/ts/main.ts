import "../scss/style.scss";
import { dia, shapes } from "@joint/core";

/** Application information, from the `/api/info` endpoint. */
interface AppInfo {
	name: string;
	version?: string;
}

/**
 * Show the application version, from the server.
 *
 * @param el the element to show the version in
 */
async function showAppInfo(el: HTMLElement): Promise<void> {
	try {
		const res = await fetch("api/info");
		if (!res.ok) {
			throw new Error(`HTTP ${res.status}`);
		}
		const info = (await res.json()) as AppInfo;
		el.textContent = info.version ? `v${info.version}` : info.name;
	} catch (e) {
		console.error("Unable to get application info", e);
		el.textContent = "Server unavailable";
		el.classList.add("text-danger");
	}
}

/**
 * Create the flow canvas, with the fixed Main Filter Chain start node and its end
 * connector (the Datum Queue output).
 *
 * @param el the element to render the canvas into
 * @returns the canvas paper
 */
function createCanvas(el: HTMLElement): dia.Paper {
	const graph = new dia.Graph({}, { cellNamespace: shapes });
	const paper = new dia.Paper({
		el,
		model: graph,
		cellViewNamespace: shapes,
		width: "100%",
		height: "100%",
		gridSize: 10,
		drawGrid: { name: "dot" },
		async: true,
	});

	const main = new shapes.standard.Rectangle({
		position: { x: 80, y: 160 },
		size: { width: 64, height: 64 },
		attrs: {
			body: { rx: 8, ry: 8, strokeWidth: 2 },
			label: {
				text: "Main Filter Chain",
				y: "calc(h + 10)",
				textVerticalAnchor: "top",
			},
		},
	});
	const output = new shapes.standard.Circle({
		position: { x: 320, y: 180 },
		size: { width: 24, height: 24 },
		attrs: {
			body: { strokeWidth: 2 },
			label: {
				text: "Output",
				y: "calc(h + 10)",
				textVerticalAnchor: "top",
			},
		},
	});
	const link = new shapes.standard.Link({
		source: { id: main.id },
		target: { id: output.id },
	});
	graph.addCells([main, output, link]);
	return paper;
}

function startApp(): void {
	showAppInfo(document.querySelector<HTMLElement>("#app-version")!);
	createCanvas(document.querySelector<HTMLElement>("#canvas")!);
}

if (document.readyState === "loading") {
	window.addEventListener("DOMContentLoaded", startApp);
} else {
	startApp();
}
