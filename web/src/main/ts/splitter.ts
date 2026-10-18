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
