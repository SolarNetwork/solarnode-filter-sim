# Controls and Instructions

Controls and Instructions are what provide interactivity in SolarNode.

## Controls

A [Control][controls] represents a value that can be changed in SolarNode. Simulated
controls can be configured on the [Setup](./getting-your-bearings.md#setup-pane) pane's **Controls**
tab. Each control has a Control ID, type, starting value, datum property, and a Publish setting.

### Control datum property

A datum a control publishes has its value in a `val` status property, and a number value also in a
`v` instantaneous property, the default for SolarNode controls. Some controls, like the Modbus and GPIO
controls, can use another property name: set it as
**Property**, and then the value is only that status property. **Property Type** can make the value
an instantaneous or accumulating property instead, like a GPIO control's setting does; a Boolean
value is then `1` or `0`, and a value that is not a number stays a status property.

### Control publish mode

The **Publish** setting for each control determines when a datum is generated from the control's
value. Any generated datum is added to the simulated Datum Queue and processed like any other
datum.

| Publish mode | Description |
|:-------------|:------------|
| **Never**     | Do not generate datum. |
| **On change** | Generate only when the the control value changes. |
| **On update** | Generate any time the control value is set. |

A generated datum is not persisted unless you turn on **Persist**. After a run, the Execution Flow's
final state lists each control's value, and **Use the final values** copies them into the
Controls tab, to continue from there in the next run.

### Control Conductors

Below the controls, **Control Conductors** are simulated [Control Conductor][control-conductor]
components. A conductor runs its tasks when an `OrchestrateControls` instruction, names it in its `service` parameter. Each task sets a
control to a value at a time offset from the instruction's `date` parameter: a number of
milliseconds, or an ISO 8601 duration like `-PT5M`, possibly with instruction parameters as
placeholders, like `{duration}`. A **Static Value** is used as written, or use the **Spring Expression Language** to treat the value as an expression, using the instruction parameters as variables.

## Instructions

An [Instruction][instructions] represents a request to perform some action. For example the `SetControlParameter` instruction asks SolarNode to update the value of a Control. Within the simulation there are two main ways for instructions to get generated:

1. The [Instructor][instructor] filter can generate instructions during datum processing.
2. A [Control Conductor][control-conductor] can generate instructions on a schedule in response to receiving an `OrchestrateControls` instruction.

### Deferred instructions

An instruction can be _deferred_ to a future date by including an `executionDate` parameter in the instruction. A Control Conductor can also generate instructions in the future based on its configured schedule. Within the simulation deferred instructions are processed like they would be in SolarNode, using a simulated Instruction Execution Job. The Execution Job's schedule can be configured on the [Setup](./getting-your-bearings.md#setup-pane) pane's Environment tab, with the **Instruction Job Seconds** setting. The default of **10s** matches SolarNode's default.

!!! tip

    Use the **Keep Running After the Last Datum** on the [Setup](./getting-your-bearings.md#setup-pane) pane's Environment tab to let instructions deferred to after the last datum to be executed.

[controls]: https://solarnetwork.github.io/solarnode-handbook/users/controls/
[control-conductor]: https://solarnetwork.github.io/solarnode-handbook/users/controls/conductor/
[instructions]: https://solarnetwork.github.io/solarnode-handbook/users/instructions/
[instructor]: https://solarnetwork.github.io/solarnode-handbook/users/datum-filters/instructor/
