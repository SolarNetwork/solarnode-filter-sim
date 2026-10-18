/**
 * SolarNode's rules for filter instance IDs, the same as the app's
 * `s10k.node.sim.filter.csv.InstanceIds` class.
 *
 * An ID must pass both the server's `CASettingsService.INSTANCE_UID_ALLOWED`
 * pattern and the setup UI's "Add new component" pattern, must not be the
 * reserved name `FACTORY`, and cannot start or end with a space, because
 * SolarNode trims settings CSV fields.
 */

/** The maximum length of an instance ID. */
export const MAX_LENGTH = 32;

/** The reserved name that cannot be an instance ID. */
const RESERVED = "FACTORY";

/** The server pattern: `[\p{IsAlphabetic}\p{IsDigit}_/ -]{1,32}`. */
const SERVER_PATTERN = /^[\p{Alphabetic}\p{Nd}_/ -]{1,32}$/u;

/** The setup UI pattern. */
const UI_PATTERN = /^[\p{L}\p{N}_/ -]{1,32}$/u;

/** The help text of the SolarNode setup UI. */
export const INSTANCE_ID_HELP =
	"Up to 32 letter, number, space, dash, underscore, and slash characters are allowed.";

/**
 * Check an instance ID.
 *
 * @param id the instance ID
 * @returns a description of the problem, or `undefined` if the ID is allowed
 */
export function instanceIdProblem(
	id: string | null | undefined,
): string | undefined {
	if (!id) {
		return "An instance ID is required.";
	}
	if (id.length > MAX_LENGTH) {
		return `An instance ID can have up to ${MAX_LENGTH} characters.`;
	}
	if (id === RESERVED) {
		return `${RESERVED} is reserved, so it cannot be an instance ID.`;
	}
	if (!SERVER_PATTERN.test(id) || !UI_PATTERN.test(id)) {
		return "An instance ID can have only letter, number, space, dash, underscore, and slash characters.";
	}
	if (id.startsWith(" ") || id.endsWith(" ")) {
		return "An instance ID cannot start or end with a space.";
	}
	return undefined;
}
