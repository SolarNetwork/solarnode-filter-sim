# Running a simulation

Once you have a simualtion set up, run it by clicking the **Run** button in the [Control pane][control-pane]:

<figure markdown>
![The simulation run button](./images/control-pane-run-button@2x.png){width=352}
<figcaption markdown>Execute a simulation by clicking the **Run** button.</figcaption>
</figure>

## Simulation result

After running a simulation, an **Execution Flow** pane will appear below the Canvas, with the results
of the simulation. The [Canvas][canvas] will show datum input/output counts above each filter.

<figure markdown>
![Example simulation. In this run 5 datum were passed through the filters, and a Downsample filter discarded the first 4, emitting a modified "downsampled" 5th datum.](./images/example-downsample@2x.png){width=1920}
<figcaption markdown>An example simulation. In this run 5 datum were passed through the filters,
and a [Downsample][downsample] filter discarded the first 4, emitting a modified "downsampled"
5^th^ datum.</figcaption>
</figure>

Here is an annotated version of that screen shot that showcases some high-level aspects
of what you are seeing:

<figure markdown>
![Example simulation, annotated.](./images/example-downsample-annotated@2x.png){width=1920}
</figure>

## Progress and Cancel

When a simulation takes more than a second, the Execution Flow shows a progress bar with the
number of datum processed, the simulated time reached, and a **Cancel** button. Cancelling keeps
the datum processed so far, with a message saying the run was cancelled.

## Long runs

A simulation processes up to its **Maximum Datum**, on the **Environment** tab (1,000 when it is
blank). To simulate a day or more of [datum sources](./datum-sources.md), raise it and set
**Keep Running After the Last Datum** to a duration, like `90` (seconds), `15m`, `24h`, `7d`, or
`PT24H`.

When a result has more than 200 datum, the Execution Flow shows a list of the datum in place of
their cards. Narrow the list by one or more source IDs, by a time range (like `2025-10-01 12:00`,
in the simulation's time zone), and with the outcome pills, or type a datum number to go to it.
Click a datum to see its card below the list, with how it went through the filters.

## Datum routes of large results

When a result has more than 20 datum, the Canvas shows the routes of only the datum you select.
Click a datum (its card header, or its row in the list) to select it, and click it again to
unselect it. Selecting a datum opens its card; hold **Alt** (**Option** on a Mac) as you click
its header to select or unselect it and leave the card as it is. You can select any number of datum, and they stay selected when you filter the
Execution Flow. In the list, the arrow keys show the route of each datum they reach, without
selecting it, and **Space** selects it. The datum bar shows how many datum are selected, and
**Clear selection** unselects them all, even those the filters hide.

## Time zone

The Execution Flow shows every date as a local date and time in the simulation's **Time Zone**,
from the **Environment** tab, and says which zone that is before the datum. Its **Show in UTC**
button switches the dates to UTC, and back. The Time Zone also applies to the **Start Date** and
the list's time range. When it is blank, it is the simulator's own time zone, which the
simulation itself always runs in: cron schedules, tariff schedules, and expression date functions
like `today()` use it. Downloaded datum have UTC dates, whatever the Time Zone.

## Downloading datum

The download menu in the Execution Flow header saves the datum of a run as a JSON file, in the
format of the **Datum** input, to review or chart them elsewhere:

* **Output datum:** the datum the filters output, which a node persists.
* **Input datum:** each datum as it entered the Datum Queue, before the filters.

The simulator keeps the last three runs, so download a run's datum before running three more.
A report of a long run has only the datum you have looked at, up to 200.

## Generated datum

Some filters like [Split][split] generate _new_ datum. These datum are added to SolarNode's
[Datum Queue][datum-queue] and then processed like any other datum, running through the
configured filters themselves.

<figure markdown>
![Example simulation with generated datum](./images/example-flow@2x.png){width=1920}
<figcaption markdown>An example simulation with generated datum.</figcaption>
</figure>

In this example the **Split - All to EV** Split filter generated a new `/ev/3` datum (#14 in the
simulation). The Canvas shows a dotted line that goes from that filter back to the starting point.
You can then see datum #14's route through the entire flow, ending at the **Output** at the far
right.

## Instruction runs

A [Control Conductor][control-conductor] task, or an Instructor instruction with a future
`executionDate`, waits for SolarNode's Instruction Execution Job. When the job runs one on the
simulated clock, the Execution Flow shows it as a dashed card between the datum, before the datum
processed after it. The card shows when it ran, the conductor and task (like `Control Conductor
"HVAC DR" task 1`), its status, the control it set, links to the datum the control published, and
the filter step that issued it. Click the card to see its instructions. The **Instruction runs**
pill above the datum shows or hides the cards.

A filter step's instruction chip shows each instruction it issued, and its details list the
instructions with their status messages (such as a Predicate Expression that declined), with the
tasks a conductor scheduled, and when they are due, indented below. A datum a task's control
published reads like "Published by control /hvac/1/mode, set by Control Conductor "HVAC DR" task 1
at 2025-10-01 00:09:00Z", and its route on the Canvas starts at the filter that issued the
instruction. Instructions still waiting when the simulation ends are listed in the final state. In
debug mode, the **Instructions** tab shows the instructions waiting as of each filter step.

[canvas]: ./getting-your-bearings.md#canvas
[control-conductor]: https://solarnetwork.github.io/solarnode-handbook/users/controls/conductor/
[control-pane]: ./getting-your-bearings.md#control-pane
[datum-queue]: https://solarnetwork.github.io/solarnode-handbook/users/datum-filters/#datum-queue
[downsample]: https://solarnetwork.github.io/solarnode-handbook/users/datum-filters/downsample/
[split]: https://solarnetwork.github.io/solarnode-handbook/users/datum-filters/split/
