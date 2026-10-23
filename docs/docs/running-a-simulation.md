# Running a simulation

Once you have a simualtion set up, run it by clicking the **Run** button in the [Control pane][control-pane]:

<figure markdown>
![The simulation run button](./images/control-pane-run-button@2x.png){width=352}
<figcaption markdown>Execute a simulation by clicking the **Run** button.</figcaption>
</figure>

## Simulation result

After running a simulation, an **Execution Flow** pane will appear below the Canvas, with the results
of the simulation.

<figure markdown>
![Example simulation. In this run 5 datum were passed through the filters, and a Downsample filter discarded the first 4, emitting a modified "downsampled" 5th datum.](./images/example-downsample@2x.png){width=1920}
<figcaption markdown>An example simulation. In this run 5 datum were passed through the filters,
and a [Downsample][downsample] filter discarded the first 4, emitting a modified "downsampled"
5^th^ datum.</figcaption>
</figure>

[control-pane]: ./getting-your-bearings.md#control-pane
[downsample]: https://solarnetwork.github.io/solarnode-handbook/users/datum-filters/downsample/
