# Datum Sources

On a SolarNode, [Datum Sources][datum-sources] read data from devices like meters and power
inverters, on a schedule, and add the datum they create to the Datum Queue. A simulation can
include simulated datum sources, which provide datum instead of, or as well as, the datum you enter
on the [Setup](./getting-your-bearings.md#setup-pane) pane's **Datum** tab.

## Adding a datum source

Datum sources are configured on the Setup pane's **Datum Sources** tab. Use **Add a datum source**
to add one of these types:

| Type | Description |
|:-----|:------------|
| [Fixed Data][fixed] | A datum with the property values you set. |
| [Mock Battery][mock-battery] | A battery that charges and discharges at a target power rate, which you can change with its controls. |
| [Mock Energy Meter][mock-meter] | An AC energy meter, with optional random deviations in its readings. |
| [Time-based Tariff Datum Source][tou] | The rates of a time-based tariff schedule, from metadata. |

Each datum source is a card with the same settings SolarNode provides. Its header shows the type,
the Service Name (or else the Source ID), and the schedule. Click the header to show or hide its
settings.

The **Schedule** is a cron expression, like `0 * * * * ?` to poll at the start of every minute, or
a number of milliseconds between polls, like `30000` to poll every 30 seconds.

Importing a SolarNode settings CSV file also adds its datum sources of these types, and its
[Operational Mode Invokers](#operational-mode-invokers), to the tab, with their settings. See
[Creating a simulation](./creating-a-simulation.md) for how the import adds or replaces them.
Exports never include datum sources.

## Running with datum sources

The simulation polls each datum source on its schedule, on the simulated clock, from the first
datum until the simulation ends. The Datum Queue then processes each polled datum like any other
datum. A run without any input datum starts at the Environment tab's **Start Date** (or now), and
runs for its **Keep Running After the Last Datum** time, which must then be more than 0.

!!! tip

    A datum source that polls every minute needs a **Keep Running After the Last Datum** time of
    more than a minute to be polled more than once.

In the Execution Flow, a polled datum reads like `Polled from "Meter" (Mock Energy Meter)`, or
`Polled from datum source 2 (Mock Energy Meter)` for a datum source without a Service Name, and its
route on the Canvas leads in from the left, like an input datum's. After a run, each datum source's
card shows how many datum it polled, and clicking a message about a datum source shows its card.

## Mock Battery controls

A Mock Battery's **Target Power Rate Control ID** and **SOC Control ID** settings make it a
[control][controls] provider: filters like the Control Updater, the Instructor, and Control
Conductor tasks can set its target power rate (limited to its maximum charge or discharge rate) or
its state of charge. Its card lists its control IDs, which the other control ID choices then offer
too, and its **Publish** and **Persist** settings decide whether a control publishes a datum when
its value changes, like the [Controls tab's](./controls-and-instructions.md#control-publish-mode).

A control set shows in the step's control chip, like `control: /bat/rate 0 → 1000 (asked 5000)`
when the battery limited the rate it was asked for.

## Operational Mode Invokers

An [Operational Mode Invoker][invoker] polls datum sources on schedules of its own while an
[operational mode][op-modes] is active, as well as their own schedules: for example, to read a
meter every 5 seconds while a `boost` mode is active. Add one with **Add a datum source** →
**Operational Mode Invoker**. Its card has the same settings SolarNode provides:

* **Mode**: the operational mode that makes the invoker active.
* **Data Source Configurations**: each one matches datum sources by **Service Name**, **Service
  Group**, or **Datum Type** (any of them, when blank), and polls them on its **Schedule**. Unlike
  a datum source's schedule, a plain number here is a number of **seconds** between polls.
  The **Service Name**'s Choose menu lists the datum sources' Service Names.

The invoker hears every change of the active modes, whatever made it: the Environment tab's
**Starting Operational Modes**, an Operational Mode filter, an `EnableOperationalModes`
instruction, or a mode that expires. A mode that expires is noticed up to 20 seconds late, as on a
node.

In the Execution Flow, a datum an invoker polled reads like `Polled from "Meter" (Mock Energy
Meter) by "Boost" (Operational Mode Invoker)`. The simulation warns about an invoker without a
mode, a configuration with no valid schedule or that matches no datum source, and tells you when
an invoker never polled.

[controls]: https://solarnetwork.github.io/solarnode-handbook/users/controls/
[invoker]: https://github.com/SolarNetwork/solarnetwork-node/tree/develop/net.solarnetwork.node.datum.opmode
[op-modes]: https://solarnetwork.github.io/solarnode-handbook/users/op-modes/
[datum-sources]: https://solarnetwork.github.io/solarnode-handbook/users/datum-sources/
[fixed]: https://solarnetwork.github.io/solarnode-handbook/users/datum-sources/fixed/
[mock-battery]: https://github.com/SolarNetwork/solarnetwork-node/tree/develop/net.solarnetwork.node.datum.battery.mock
[mock-meter]: https://github.com/SolarNetwork/solarnetwork-node/tree/develop/net.solarnetwork.node.datum.energymeter.mock
[tou]: https://github.com/SolarNetwork/solarnetwork-node/tree/develop/net.solarnetwork.node.datum.tou
