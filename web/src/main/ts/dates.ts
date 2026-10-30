/**
 * Dates in a time zone: the simulation's time zone shows dates as local date
 * and time values, and dates entered without a zone are in it.
 */

/** The parts of a local date and time. */
export interface LocalDateTime {
	year: number;
	/** The month, from 1. */
	month: number;
	day: number;
	hour: number;
	minute: number;
	second: number;
	millisecond: number;
}

const formatters = new Map<string, Intl.DateTimeFormat>();

function formatter(zone: string): Intl.DateTimeFormat {
	let f = formatters.get(zone);
	if (!f) {
		f = new Intl.DateTimeFormat("en-US", {
			timeZone: zone,
			hourCycle: "h23",
			year: "numeric",
			month: "numeric",
			day: "numeric",
			hour: "numeric",
			minute: "numeric",
			second: "numeric",
		});
		formatters.set(zone, f);
	}
	return f;
}

/**
 * Get a time zone's canonical name, such as `UTC` for `utc`.
 *
 * @param zone the time zone name
 * @returns the canonical name, or `null` if the zone is not known
 */
export function canonicalTimeZone(zone: string): string | null {
	const z = zone.trim();
	if (!z) {
		return null;
	}
	try {
		return new Intl.DateTimeFormat("en-US", {
			timeZone: z,
		}).resolvedOptions().timeZone;
	} catch {
		return null;
	}
}

/**
 * Get the time zones the browser knows, for a list of choices.
 *
 * @returns the time zone names, with `UTC` first
 */
export function timeZoneNames(): string[] {
	const names = Intl.supportedValuesOf?.("timeZone") ?? [];
	return ["UTC", ...names.filter((n) => n !== "UTC")];
}

/**
 * Get the local date and time of an instant in a time zone.
 *
 * @param ms the instant, in epoch milliseconds
 * @param zone the time zone
 * @returns the local date and time
 */
export function localDateTime(ms: number, zone: string): LocalDateTime {
	const parts: Record<string, number> = {};
	for (const p of formatter(zone).formatToParts(ms)) {
		if (p.type !== "literal") {
			parts[p.type] = Number(p.value);
		}
	}
	return {
		year: parts.year,
		month: parts.month,
		day: parts.day,
		hour: parts.hour,
		minute: parts.minute,
		second: parts.second,
		millisecond: ((ms % 1000) + 1000) % 1000,
	};
}

/** The offset of a time zone from UTC at an instant, in milliseconds. */
function offsetAt(ms: number, zone: string): number {
	const l = localDateTime(ms, zone);
	return (
		Date.UTC(
			l.year,
			l.month - 1,
			l.day,
			l.hour,
			l.minute,
			l.second,
			l.millisecond,
		) - ms
	);
}

/**
 * Get the instant of a local date and time in a time zone. A time that a
 * daylight saving change skips is moved forward by the change, and a time it
 * repeats is the first of the two.
 *
 * @param local the local date and time; the month and day may overflow, like
 *     with `Date.UTC()`
 * @param zone the time zone
 * @returns the instant, in epoch milliseconds
 */
export function zonedInstant(local: LocalDateTime, zone: string): number {
	const asUtc = Date.UTC(
		local.year,
		local.month - 1,
		local.day,
		local.hour,
		local.minute,
		local.second,
		local.millisecond,
	);
	// the offsets a day before and after, either side of any change
	const offsets = [
		offsetAt(asUtc - 86_400_000, zone),
		offsetAt(asUtc, zone),
		offsetAt(asUtc + 86_400_000, zone),
	];
	const valid = offsets
		.map((o) => asUtc - o)
		.filter((t) => t + offsetAt(t, zone) === asUtc);
	// the earlier of a repeated time, or in a gap the time after it with the
	// offset from before it
	return valid.length ? Math.min(...valid) : asUtc - Math.min(...offsets);
}

function pad(n: number, size = 2): string {
	return String(n).padStart(size, "0");
}

/**
 * Format an instant as a local date and time, like `2025-10-01 12:00:00`.
 *
 * @param ms the instant, in epoch milliseconds
 * @param zone the time zone
 * @param fraction the fraction of a second to show after the seconds, if any
 * @returns the date and time
 */
export function formatLocal(
	ms: number,
	zone: string,
	fraction?: string,
): string {
	const l = localDateTime(ms, zone);
	return `${l.year}-${pad(l.month)}-${pad(l.day)} ${pad(l.hour)}:${pad(l.minute)}:${pad(l.second)}${fraction ? `.${fraction}` : ""}`;
}

/** The pattern of a local date and time, at any precision from a day. */
const LOCAL_PATTERN =
	/^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2})(?::(\d{2})(?::(\d{2})(?:\.(\d{1,3}))?)?)?)?\s*(Z|[+-]\d{2}:?\d{2})?$/i;

/**
 * Parse a date at its start or end: `2025-10-01` is the whole day, and
 * `2025-10-01 12:30` the whole minute.
 *
 * @param text the date, as a prefix of `yyyy-MM-dd HH:mm:ss.SSS`, with an
 *     optional zone offset
 * @param zone the time zone of a date without an offset
 * @param end `true` for the end of the date's precision, else its start
 * @returns the date in milliseconds, or `null` if the text is blank
 * @throws Error if the text is not a date
 */
export function parseLocalDate(
	text: string,
	zone: string,
	end: boolean,
): number | null {
	const t = text.trim();
	if (!t) {
		return null;
	}
	const m = LOCAL_PATTERN.exec(t);
	const invalid = () =>
		new Error("Enter a date like 2025-10-01 or 2025-10-01 12:30.");
	if (!m) {
		throw invalid();
	}
	const given = m.slice(1, 8).filter((p) => p !== undefined).length;
	const n = (i: number) => (m[i] === undefined ? 0 : Number(m[i]));
	const local: LocalDateTime = {
		year: n(1),
		month: n(2),
		day: n(3),
		hour: n(4),
		minute: n(5),
		second: n(6),
		millisecond: m[7] === undefined ? 0 : Number(m[7].padEnd(3, "0")),
	};
	if (
		local.month < 1 ||
		local.month > 12 ||
		local.day < 1 ||
		local.day > 31 ||
		local.hour > 23 ||
		local.minute > 59 ||
		local.second > 59
	) {
		throw invalid();
	}
	const offset = m[8];
	const instant = (l: LocalDateTime) => {
		if (!offset) {
			return zonedInstant(l, zone);
		}
		const utc = Date.UTC(
			l.year,
			l.month - 1,
			l.day,
			l.hour,
			l.minute,
			l.second,
			l.millisecond,
		);
		if (offset.toUpperCase() === "Z") {
			return utc;
		}
		const digits = offset.replace(":", "");
		const minutes =
			Number(digits.slice(1, 3)) * 60 + Number(digits.slice(3, 5));
		return utc - (digits[0] === "-" ? -1 : 1) * minutes * 60_000;
	};
	const start = instant(local);
	if (Number.isNaN(start)) {
		throw invalid();
	}
	if (!end) {
		return start;
	}
	// the start of the next day, hour, minute, second, or millisecond
	const fields: (keyof LocalDateTime)[] = [
		"year",
		"month",
		"day",
		"hour",
		"minute",
		"second",
		"millisecond",
	];
	const next = { ...local };
	next[fields[Math.max(2, given - 1)]] += 1;
	return instant(next) - 1;
}

/**
 * Format a date of a range so {@link parseLocalDate} parses it back to the
 * same date, as briefly as it can: `2025-10-01` for the start or end of a day,
 * or `2025-10-01 12:30` for the start or end of a minute.
 *
 * @param ms the date, in epoch milliseconds
 * @param zone the time zone
 * @param end `true` if the date is the end of a range
 * @returns the text
 */
export function formatRangeDate(
	ms: number,
	zone: string,
	end: boolean,
): string {
	const full = formatLocal(ms, zone, pad(((ms % 1000) + 1000) % 1000, 3));
	// a day, an hour (only for an end, as a start reads better with minutes),
	// minutes, seconds, then the whole date
	for (const length of end ? [10, 13, 16, 19, 23] : [10, 16, 19, 23]) {
		const text = full.substring(0, length);
		try {
			if (parseLocalDate(text, zone, end) === ms) {
				return text;
			}
		} catch {
			// try a longer one
		}
	}
	return full;
}
