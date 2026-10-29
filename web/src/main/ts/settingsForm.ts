import { safeHtml } from "./html.ts";
import type { SettingField, SettingProblem } from "./model.ts";
import type { SettingChanges } from "./workspace.ts";

/** The names a Choose menu offers. */
export interface Choices {
	/** Get the names. */
	names: () => string[];
	/** The text shown when there are none. */
	none: string;
	/** The button's tooltip. */
	title: string;
}

/**
 * What a settings form edits, and the parts of the form that depend on what
 * it edits, such as a filter in the inspector.
 */
export interface SettingsFormHost {
	/** Get the current settings, as SolarNode setting keys and values. */
	settings(): Record<string, string>;
	/**
	 * Change settings.
	 *
	 * @param changes the changes, with `null` to remove a setting
	 * @param mergeKey the key of the setting being typed in, to merge typing
	 *     into one undo step, or `undefined`
	 * @param rerender `true` to show the form again, for changes to its
	 *     structure, like adding a list item
	 */
	edit(changes: SettingChanges, mergeKey?: string, rerender?: boolean): void;
	/** Get the notes about the settings, by key, if settings have notes. */
	notes?(): Record<string, string> | undefined;
	/**
	 * Change a setting's note. Without it, settings have no notes.
	 *
	 * @param key the setting key
	 * @param note the note
	 */
	setNote?(key: string, note: string): void;
	/**
	 * Get the Choose menu of a setting, for names other than its related
	 * services.
	 *
	 * @param field the setting
	 * @param multiline `true` if the setting is a text area
	 * @returns the choices, or `undefined` for none
	 */
	choices?(field: SettingField, multiline: boolean): Choices | undefined;
	/**
	 * Get the Service Names of the simulated services that match a setting's
	 * related service filter, for its Choose menu.
	 *
	 * @param serviceFilter the OSGi service filter, like
	 *     `(objectClass=net.solarnetwork.node.service.MetadataService)`
	 */
	relatedServices?(serviceFilter: string): string[];
	/**
	 * Add to a rendered text setting, such as a warning below it.
	 *
	 * @param field the setting
	 * @param wrapper the setting's element
	 */
	decorate?(field: SettingField, wrapper: HTMLElement): void;
	/**
	 * Called after a setting's value is edited.
	 *
	 * @param key the setting key
	 */
	valueEdited?(key: string): void;
	/**
	 * Show where a setting's value comes from, with the setting's Ext pill
	 * (`.sim-ext-pill`) and reset button (`.sim-reset`). Without it, settings
	 * have no Ext pill, and the reset button shows while the setting has a
	 * value.
	 *
	 * @param key the setting key
	 * @param container the element with the setting's pill and reset button
	 */
	refreshSource?(key: string, container: HTMLElement): void;
	/**
	 * Render the content of a read-only group, such as a chain's filters.
	 * Without it, a read-only group shows its settings, read-only.
	 *
	 * @param field the group
	 */
	readOnlyGroup?(field: SettingField): HTMLElement[];
}

/** A rendered setting, for showing its problems. */
interface Control {
	/** The element marked invalid. */
	control: HTMLElement;
	feedback: HTMLElement;
}

let ids = 0;

/** Get a new element ID for a form control. */
export function newId(): string {
	return `sim-field-${++ids}`;
}

export function element<K extends keyof HTMLElementTagNameMap>(
	tag: K,
	className?: string,
	text?: string,
): HTMLElementTagNameMap[K] {
	const el = document.createElement(tag);
	if (className) {
		el.className = className;
	}
	if (text !== undefined) {
		el.textContent = text;
	}
	return el;
}

export function iconButton(
	label: string,
	icon: string,
	className: string,
	onClick: () => void,
): HTMLButtonElement {
	const button = element("button", `btn btn-sm ${className}`);
	button.type = "button";
	const glyph = element("i", `bi bi-${icon}`);
	glyph.setAttribute("aria-hidden", "true");
	button.append(glyph);
	if (label) {
		button.append(` ${label}`);
	}
	button.addEventListener("click", onClick);
	return button;
}

/**
 * A small icon-only button for a setting's tools. Like SolarNode's help and
 * note buttons, it is left out of the tab order, so tabbing moves between
 * fields.
 */
export function toolButton(
	title: string,
	icon: string,
	onClick: () => void,
): HTMLButtonElement {
	const button = iconButton("", icon, "btn-link sim-tool", onClick);
	button.title = title;
	button.setAttribute("aria-label", title);
	button.tabIndex = -1;
	return button;
}

/**
 * Get the name of one item of a list, like "Expression" for "Expressions".
 *
 * @param label the list label
 */
function itemName(label: string | undefined): string {
	return (label ?? "Item").replace(/(?<!s)s$/, "");
}

/**
 * Get a name with its indefinite article, like "an expression".
 *
 * @param name the name, in lower case
 */
function withArticle(name: string): string {
	return `${/^[aeiou]/.test(name) ? "an" : "a"} ${name}`;
}

/** The number of rows for a text area. */
export function textRows(value: string | undefined): number {
	const lines = (value ?? "").split("\n").length;
	return Math.min(12, Math.max(2, lines + 1));
}

/**
 * Renders the fields of a settings form from the web API, which edit a
 * settings map: text, toggles, selects, radios, sliders, titles, groups, and
 * dynamic lists, each setting with its help, note, reset, and Choose menu.
 *
 * Create one for each rendering of a form, as it keeps the rendered settings
 * to show their problems.
 */
export class SettingsFormRenderer {
	readonly #host: SettingsFormHost;
	readonly #controls = new Map<string, Control>();

	/**
	 * Constructor.
	 *
	 * @param host what the form edits
	 */
	constructor(host: SettingsFormHost) {
		this.#host = host;
	}

	/**
	 * Render form fields.
	 *
	 * @param fields the fields
	 * @returns the elements of the fields
	 */
	render(fields: SettingField[]): HTMLElement[] {
		const result: HTMLElement[] = [];
		for (const field of fields) {
			const el = this.#field(field);
			if (el) {
				result.push(el);
			}
		}
		return result;
	}

	/**
	 * Mark the settings with problems, and clear the others.
	 *
	 * @param problems the problems
	 */
	showProblems(problems: SettingProblem[]): void {
		const byKey = new Map(problems.map((p) => [p.key, p.message]));
		for (const [key, control] of this.#controls) {
			const message = byKey.get(key);
			control.control.classList.toggle("is-invalid", !!message);
			control.feedback.textContent = message ?? "";
			// show feedback for controls wrapped in another element too
			control.feedback.classList.toggle("d-block", !!message);
		}
	}

	#field(field: SettingField, itemLabel?: string): HTMLElement | null {
		switch (field.type) {
			case "Group":
				return this.#group(field, itemLabel);
			case "Title":
				return this.#title(field);
			case "Toggle":
				return this.#toggle(field);
			case "Select":
				return this.#select(field);
			case "Radio":
				return this.#radio(field);
			case "Slider":
				return this.#slider(field);
			case "TextArea":
				return this.#text(field, true);
			default:
				return this.#text(field, false);
		}
	}

	/**
	 * Wrap a setting's control with its label, tools, help, problem feedback,
	 * and note.
	 */
	#setting(
		field: SettingField,
		control: HTMLElement,
		controlId: string,
		marked: HTMLElement = control,
	): HTMLElement {
		const host = this.#host;
		const key = field.key!;
		const wrapper = element("div", "mb-3 sim-setting");
		const head = element("div", "d-flex align-items-center gap-1 mb-1");
		const label = element(
			"label",
			"form-label mb-0 me-auto small fw-semibold",
			field.label ?? key,
		);
		label.htmlFor = controlId;
		head.append(label);
		if (field.transient) {
			const badge = element(
				"span",
				"badge text-bg-light border",
				"not saved",
			);
			badge.title =
				"SolarNode does not save this setting, so exports leave it out. The simulator applies it when a simulation starts.";
			head.append(badge);
		}
		if (host.refreshSource) {
			head.append(
				element(
					"span",
					"badge rounded-pill text-bg-info sim-ext-pill",
					"Ext",
				),
			);
		}

		const help = element("div", "form-text sim-help");
		help.hidden = true;
		if (field.description) {
			help.append(safeHtml(field.description));
			const toggle = toolButton("Help", "question-circle", () => {
				help.hidden = !help.hidden;
				toggle.setAttribute("aria-expanded", String(!help.hidden));
			});
			toggle.setAttribute("aria-expanded", "false");
			head.append(toggle);
		}

		const noteRow = element("div", "mt-1");
		noteRow.hidden = true;
		if (host.setNote) {
			const note = host.notes?.()?.[key] ?? "";
			noteRow.hidden = !note;
			const noteInput = element(
				"input",
				"form-control form-control-sm sim-note",
			);
			noteInput.value = note;
			noteInput.placeholder =
				"A note about this setting, kept in the CSV note column";
			noteInput.setAttribute(
				"aria-label",
				`Note for ${field.label ?? key}`,
			);
			noteRow.append(noteInput);
			if (!field.readOnly) {
				const noteButton = toolButton(
					"Note",
					note ? "sticky-fill" : "sticky",
					() => {
						noteRow.hidden = !noteRow.hidden;
						if (!noteRow.hidden) {
							noteInput.focus();
						}
					},
				);
				noteInput.addEventListener("input", () => {
					host.setNote!(key, noteInput.value);
					noteButton.querySelector("i")!.className =
						`bi bi-${noteInput.value.trim() ? "sticky-fill" : "sticky"}`;
				});
				head.append(noteButton);
			}
		}
		if (!field.readOnly) {
			const reset = toolButton(
				field.defaultValue
					? `Use the default value: ${field.defaultValue}`
					: "Use the default value",
				"arrow-counterclockwise",
				() => host.edit({ [key]: null }, undefined, true),
			);
			reset.classList.add("sim-reset");
			head.append(reset);
		}
		this.#refreshSource(key, head);

		const feedback = element("div", "invalid-feedback");
		wrapper.append(head, control, feedback, help, noteRow);
		this.#controls.set(key, { control: marked, feedback });
		return wrapper;
	}

	#text(field: SettingField, multiline: boolean): HTMLElement {
		const host = this.#host;
		const id = newId();
		const input = multiline
			? element("textarea", "form-control form-control-sm font-monospace")
			: element("input", "form-control form-control-sm");
		if (input instanceof HTMLInputElement) {
			input.type = field.secure ? "password" : "text";
			if (field.type === "Cron") {
				input.classList.add("font-monospace");
			}
		} else {
			input.rows = textRows(field.value);
		}
		input.id = id;
		input.value = field.value ?? "";
		input.placeholder = field.defaultValue ?? "";
		input.readOnly = field.readOnly;
		input.spellcheck = false;
		input.addEventListener("input", () =>
			this.#editValue(field.key!, input.value),
		);
		const choices: Choices | undefined = field.readOnly
			? undefined
			: (host.choices?.(field, multiline) ??
				(!multiline &&
				field.relatedServiceFilter &&
				host.relatedServices
					? {
							names: () =>
								host.relatedServices!(
									field.relatedServiceFilter!,
								),
							none: "No matching services",
							title: "Choose from the simulated services",
						}
					: undefined));
		const wrapper = choices
			? this.#setting(
					field,
					this.#chooser(field, input, choices),
					id,
					input,
				)
			: this.#setting(field, input, id);
		host.decorate?.(field, wrapper);
		if (field.type === "Cron") {
			wrapper.append(
				element(
					"div",
					"form-text",
					"A cron expression, or a number of milliseconds between runs.",
				),
			);
		}
		return wrapper;
	}

	/**
	 * Add a Choose menu to a text input, which lists names such as the
	 * simulated services that match the setting's related service filter, like
	 * SolarNode's settings page.
	 */
	#chooser(
		field: SettingField,
		input: HTMLInputElement | HTMLTextAreaElement,
		choices: Choices,
	): HTMLElement {
		const group = element("div", "input-group input-group-sm");
		const button = element(
			"button",
			"btn btn-outline-secondary dropdown-toggle",
			"Choose",
		);
		button.type = "button";
		button.setAttribute("data-bs-toggle", "dropdown");
		button.setAttribute("aria-expanded", "false");
		button.title = choices.title;
		const menu = element("ul", "dropdown-menu dropdown-menu-end");
		button.addEventListener("show.bs.dropdown", () => {
			const names = choices.names();
			menu.replaceChildren(
				...(names.length ? names : [null]).map((name) => {
					const item = element("li");
					const choice = element(
						"button",
						"dropdown-item",
						name ?? choices.none,
					);
					choice.type = "button";
					if (name === null) {
						choice.disabled = true;
					} else {
						choice.classList.toggle("active", input.value === name);
						choice.addEventListener("click", () => {
							input.value = name;
							this.#editValue(field.key!, name);
						});
					}
					item.append(choice);
					return item;
				}),
			);
		});
		group.append(input, button, menu);
		return group;
	}

	#toggle(field: SettingField): HTMLElement {
		const id = newId();
		const control = element("div", "form-check form-switch");
		const input = element("input", "form-check-input");
		input.type = "checkbox";
		input.setAttribute("role", "switch");
		input.id = id;
		input.checked = field.value === field.trueValue;
		input.disabled = field.readOnly;
		const state = element(
			"label",
			"form-check-label small text-body-secondary",
			input.checked ? "On" : "Off",
		);
		state.htmlFor = id;
		input.addEventListener("change", () => {
			state.textContent = input.checked ? "On" : "Off";
			this.#editValue(
				field.key!,
				(input.checked ? field.trueValue : field.falseValue) ??
					String(input.checked),
			);
		});
		control.append(input, state);
		return this.#setting(field, control, id);
	}

	#select(field: SettingField): HTMLElement {
		const id = newId();
		const select = element("select", "form-select form-select-sm");
		select.id = id;
		select.disabled = field.readOnly;
		const value = field.value ?? "";
		const options = [...(field.options ?? [])];
		if (!options.some((o) => o.value === value)) {
			// keep a value that is not one of the choices
			options.push({ value, label: value });
		}
		for (const o of options) {
			const option = element("option", undefined, o.label || "(none)");
			option.value = o.value;
			option.selected = o.value === value;
			select.append(option);
		}
		select.addEventListener("change", () =>
			this.#editValue(field.key!, select.value),
		);
		return this.#setting(field, select, id);
	}

	#radio(field: SettingField): HTMLElement {
		const name = newId();
		const control = element("div");
		let firstId = name;
		(field.options ?? []).forEach((o, i) => {
			const id = `${name}-${i}`;
			if (i === 0) {
				firstId = id;
			}
			const check = element("div", "form-check");
			const input = element("input", "form-check-input");
			input.type = "radio";
			input.name = name;
			input.id = id;
			input.value = o.value;
			input.checked = o.value === field.value;
			input.disabled = field.readOnly;
			input.addEventListener("change", () => {
				if (input.checked) {
					this.#editValue(field.key!, o.value);
				}
			});
			const label = element("label", "form-check-label small", o.label);
			label.htmlFor = id;
			check.append(input, label);
			if (o.description) {
				const about = element("div", "form-text mt-0");
				about.append(safeHtml(o.description));
				check.append(about);
			}
			control.append(check);
		});
		return this.#setting(field, control, firstId);
	}

	#slider(field: SettingField): HTMLElement {
		const id = newId();
		const control = element("div", "d-flex align-items-center gap-2");
		const input = element("input", "form-range");
		input.type = "range";
		input.id = id;
		input.min = String(field.min ?? 0);
		input.max = String(field.max ?? 100);
		input.step = String(field.step ?? 1);
		input.value = field.value ?? "";
		input.disabled = field.readOnly;
		const output = element("output", "small font-monospace", input.value);
		input.addEventListener("input", () => {
			output.textContent = input.value;
			this.#editValue(field.key!, input.value);
		});
		control.append(input, output);
		return this.#setting(field, control, id, input);
	}

	#title(field: SettingField): HTMLElement {
		const wrapper = element("div", "mb-3");
		wrapper.append(
			element("div", "small fw-semibold mb-1", field.label ?? field.key),
		);
		const value = element("div", "small");
		if (field.markup) {
			value.append(safeHtml(field.value ?? ""));
		} else {
			value.textContent = field.value ?? "";
		}
		wrapper.append(value);
		return wrapper;
	}

	#group(field: SettingField, itemLabel?: string): HTMLElement {
		const children = field.fields ?? [];
		if (!field.key) {
			// a group without a key, such as one item of a list
			const set = element("fieldset", "sim-item mb-3");
			if (itemLabel) {
				set.append(element("legend", "sim-item-legend", itemLabel));
			}
			set.append(...this.render(children));
			return set;
		}

		const set = element("fieldset", "sim-group mb-3");
		const head = element("div", "d-flex align-items-center gap-1 mb-1");
		head.append(
			element("legend", "h6 mb-0 me-auto", field.label ?? field.key),
		);
		const help = element("div", "form-text sim-help mb-2");
		help.hidden = true;
		if (field.description) {
			help.append(safeHtml(field.description));
			head.append(
				toolButton("Help", "question-circle", () => {
					help.hidden = !help.hidden;
				}),
			);
		}
		if (field.dynamic && !field.readOnly && field.countKey) {
			const buttons = element("div", "btn-group btn-group-sm");
			const remove = iconButton(
				"",
				"dash-lg",
				"btn-outline-secondary",
				() => this.#removeItem(field),
			);
			remove.title = `Remove the last ${itemName(field.label).toLowerCase()}`;
			remove.disabled = children.length === 0;
			const add = iconButton("", "plus-lg", "btn-outline-secondary", () =>
				this.#addItem(field),
			);
			add.title = `Add ${withArticle(itemName(field.label).toLowerCase())}`;
			buttons.append(remove, add);
			head.append(buttons);
		}
		set.append(head, help);

		if (field.readOnly && this.#host.readOnlyGroup) {
			set.append(...this.#host.readOnlyGroup(field));
			return set;
		}

		children.forEach((child, i) => {
			const label = field.dynamic
				? `${itemName(field.label)} ${i + 1}`
				: undefined;
			const el = this.#field(child, label);
			if (el) {
				set.append(el);
			}
		});
		if (field.dynamic && children.length === 0) {
			set.append(
				element(
					"p",
					"small text-body-secondary mb-0",
					"None yet: use + to add one.",
				),
			);
		}
		return set;
	}

	/* === Editing === */

	#editValue(key: string, value: string): void {
		const host = this.#host;
		host.edit({ [key]: value }, key);
		host.valueEdited?.(key);
		const setting = this.#controls
			.get(key)
			?.control.closest<HTMLElement>(".sim-setting");
		if (setting) {
			this.#refreshSource(key, setting);
		}
	}

	/**
	 * Show where a setting's value comes from, or else show its reset button
	 * while it has a value.
	 */
	#refreshSource(key: string, container: HTMLElement): void {
		const host = this.#host;
		if (host.refreshSource) {
			host.refreshSource(key, container);
			return;
		}
		const reset = container.querySelector<HTMLElement>(".sim-reset");
		if (reset) {
			reset.hidden = host.settings()[key] === undefined;
		}
	}

	#addItem(list: SettingField): void {
		const count = list.fields?.length ?? 0;
		this.#host.edit(
			{ [list.countKey!]: String(count + 1) },
			undefined,
			true,
		);
	}

	#removeItem(list: SettingField): void {
		const count = list.fields?.length ?? 0;
		if (count === 0) {
			return;
		}
		// like SolarNode, remove the last item's settings along with it
		const prefix = `${list.key}[${count - 1}]`;
		const changes: SettingChanges = { [list.countKey!]: String(count - 1) };
		for (const key of Object.keys(this.#host.settings())) {
			if (key.startsWith(prefix)) {
				changes[key] = null;
			}
		}
		this.#host.edit(changes, undefined, true);
	}
}
