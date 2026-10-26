const MIN_FRACTION = 0.1;
const MAX_FRACTION = 0.9;
const KEY_STEP = 0.05;

/**
 * Make a splitter between two stacked sections resize them.
 *
 * The splitter sets the `--sim-split` custom property of its container to the
 * top section's share of the height, as a percentage, and saves it in the
 * browser.
 *
 * @param splitter the splitter element
 * @param container the element containing both sections
 * @param storageKey the browser storage key for the split
 * @param defaultFraction the top section's share of the height when no split
 *     is saved
 */
export function initSplitter(
	splitter: HTMLElement,
	container: HTMLElement,
	storageKey: string,
	defaultFraction = 0.45,
): void {
	const apply = (fraction: number, save: boolean) => {
		const f = Math.max(MIN_FRACTION, Math.min(MAX_FRACTION, fraction));
		container.style.setProperty("--sim-split", `${(f * 100).toFixed(1)}%`);
		splitter.setAttribute("aria-valuenow", String(Math.round(f * 100)));
		if (save) {
			try {
				localStorage.setItem(storageKey, String(f));
			} catch {
				// not saved
			}
		}
		return f;
	};

	let fraction = defaultFraction;
	try {
		const saved = Number(localStorage.getItem(storageKey));
		if (saved > 0) {
			fraction = saved;
		}
	} catch {
		// use the default
	}
	fraction = apply(fraction, false);
	splitter.setAttribute("aria-valuemin", String(MIN_FRACTION * 100));
	splitter.setAttribute("aria-valuemax", String(MAX_FRACTION * 100));

	splitter.addEventListener("pointerdown", (e) => {
		e.preventDefault();
		splitter.setPointerCapture(e.pointerId);
		splitter.classList.add("is-dragging");
		const rect = container.getBoundingClientRect();
		const offset = e.clientY - splitter.getBoundingClientRect().top;
		const move = (m: PointerEvent) => {
			fraction = apply(
				(m.clientY - offset - rect.top) / rect.height,
				false,
			);
		};
		const end = () => {
			splitter.removeEventListener("pointermove", move);
			splitter.removeEventListener("pointerup", end);
			splitter.removeEventListener("pointercancel", end);
			splitter.classList.remove("is-dragging");
			apply(fraction, true);
		};
		splitter.addEventListener("pointermove", move);
		splitter.addEventListener("pointerup", end);
		splitter.addEventListener("pointercancel", end);
	});

	splitter.addEventListener("keydown", (e) => {
		if (e.key === "ArrowUp" || e.key === "ArrowDown") {
			e.preventDefault();
			fraction = apply(
				fraction + (e.key === "ArrowUp" ? -KEY_STEP : KEY_STEP),
				true,
			);
		}
	});
}

/** The smallest width of a side panel, in pixels. */
const MIN_PANEL_WIDTH = 200;

/** The smallest width the centre keeps when a side panel widens, in pixels. */
const MIN_CENTER_WIDTH = 320;

const KEY_STEP_PX = 16;

/**
 * Make a splitter between a side panel and the centre resize the panel.
 *
 * The splitter sets a custom property of its container, such as
 * `--sim-left-size`, to the panel's width in pixels, and saves it in the
 * browser. A double click returns the panel to its default width (the custom
 * property's fallback in the style sheet).
 *
 * @param splitter the splitter element
 * @param container the element whose columns hold the panels
 * @param panel the side panel
 * @param side the side the panel is on
 * @param property the custom property of the panel's width
 * @param storageKey the browser storage key for the width
 */
export function initColumnSplitter(
	splitter: HTMLElement,
	container: HTMLElement,
	panel: HTMLElement,
	side: "left" | "right",
	property: string,
	storageKey: string,
): void {
	const maxWidth = () =>
		Math.max(
			MIN_PANEL_WIDTH,
			panel.getBoundingClientRect().width +
				container
					.querySelector<HTMLElement>(".sim-center")!
					.getBoundingClientRect().width -
				MIN_CENTER_WIDTH,
		);
	const apply = (width: number, save: boolean) => {
		const w = Math.round(
			Math.max(MIN_PANEL_WIDTH, Math.min(maxWidth(), width)),
		);
		container.style.setProperty(property, `${w}px`);
		splitter.setAttribute("aria-valuenow", String(w));
		if (save) {
			try {
				localStorage.setItem(storageKey, String(w));
			} catch {
				// not saved
			}
		}
		return w;
	};

	try {
		const saved = Number(localStorage.getItem(storageKey));
		if (saved > 0) {
			// not limited here, as the panel might be hidden
			container.style.setProperty(property, `${saved}px`);
		}
	} catch {
		// use the default
	}
	splitter.setAttribute("aria-valuemin", String(MIN_PANEL_WIDTH));

	let width = 0;
	splitter.addEventListener("pointerdown", (e) => {
		if (e.button !== 0) {
			return;
		}
		e.preventDefault();
		splitter.setPointerCapture(e.pointerId);
		splitter.classList.add("is-dragging");
		const startX = e.clientX;
		const startWidth = panel.getBoundingClientRect().width;
		width = startWidth;
		const move = (m: PointerEvent) => {
			const dx = m.clientX - startX;
			width = apply(startWidth + (side === "left" ? dx : -dx), false);
		};
		const end = () => {
			splitter.removeEventListener("pointermove", move);
			splitter.removeEventListener("pointerup", end);
			splitter.removeEventListener("pointercancel", end);
			splitter.classList.remove("is-dragging");
			apply(width, true);
		};
		splitter.addEventListener("pointermove", move);
		splitter.addEventListener("pointerup", end);
		splitter.addEventListener("pointercancel", end);
	});

	splitter.addEventListener("dblclick", () => {
		container.style.removeProperty(property);
		splitter.removeAttribute("aria-valuenow");
		try {
			localStorage.removeItem(storageKey);
		} catch {
			// not removed
		}
	});

	splitter.addEventListener("keydown", (e) => {
		if (e.key === "ArrowLeft" || e.key === "ArrowRight") {
			e.preventDefault();
			const wider = (e.key === "ArrowRight") === (side === "left");
			apply(
				panel.getBoundingClientRect().width +
					(wider ? KEY_STEP_PX : -KEY_STEP_PX),
				true,
			);
		}
	});
}
