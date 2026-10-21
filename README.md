# SolarNode Filter Simulator

The SolarNode Filter Simulator is a web application for designing and testing SolarNode
[Datum Filter][datum-filters] configurations without a SolarNode:

 * build a filter chain on a canvas, starting from the main filter chain on the Datum Queue,
   including nested chains
 * configure each filter with the same settings SolarNode provides
 * run sample datum through the chain, and see how each filter transformed them
 * record the datum a real SolarNode captures, as they arrive, to use as sample datum
 * see the route each datum took through the filters, drawn on the canvas
 * download the execution flow as a stand-alone HTML report
 * export the configuration as a SolarNode settings CSV file, or import an existing one
 * save the whole simulation, with its inputs, to a file to share it or open it in another
   browser

The simulator runs the real SolarNode filter code, from the SolarNode bundles published to Maven
Central, so filters behave the same as they do on a node.

> [!NOTE]
> The simulator is in early development. See [PLAN.md](PLAN.md) for the roadmap.

# Running

The simulator is a single application that serves its web UI over HTTP. Once it is running,
visit the URL it prints in a desktop web browser. The native executable listens on port `2008` by
default, and the JVM build (`bootRun`) on port `8080`.

To run from source with Java 25:

```sh
./gradlew :solarnode-filter-sim-app:bootRun
```

To run the native executable (see [README-Developers.md](README-Developers.md) for how to build
it):

```sh
./app/build/native/nativeCompile/sn-filter-sim
```

Once started, it prints a message with its URL:

```
SolarNode Filter Simulator (0.1.1) started, visit
http://localhost:2008
```

Pass `--server.port=8081` (or set the `SERVER_PORT` environment variable) to use a different
port. The native executable listens only on the local machine (`127.0.0.1`); pass
`--server.address=0.0.0.0` to accept connections from other machines. It logs only warnings and
errors; pass `--logging.level.root=info` for the full log.

## External commands

The POA Calculator (pvlib) filter runs an external command, normally the `ghi-to-poa` script of
the SolarNodeOS `sn-pvlib` package, to calculate POA irradiance. The simulator runs a command only
when its full path is in the **Allowed Commands** list, on the Environment tab of the inputs.
Allow only programs you trust, as they run on the computer running the simulator. A list of
commands to allow in every simulation can also be set when starting the simulator, with
`--sim.commands.allowed=/path/to/command,/another/command`.

## Custom filters

The simulator can also run datum filters that you write yourself, from the SolarNode bundle jars
you build for them. Start the simulator with `--sim.filters.path` (or the `SIM_FILTERS_PATH`
environment variable) set to the jars, or to directories of jars, separated by commas:

```sh
java -jar sn-filter-sim-<version>.jar --sim.filters.path=~/dev/my-filters/build/
```

The simulator reads each bundle's blueprint (`OSGI-INF/blueprint/*.xml`, or the
`Bundle-Blueprint` manifest header) and adds its user datum filters to the palette, the settings
forms, settings CSV import and export, and simulations. Its startup message lists the custom
filters, and any that are not available, with the reason. A filter is only available if every
service its blueprint gives it is one the simulator provides, such as the Datum Queue, task
scheduler, datum metadata, metadata services, instruction services, transient settings, and
metrics.

Custom filters need the JVM build, the `sn-filter-sim` jar of each release, which runs with
Java 25. The native executable cannot load classes, so it refuses `--sim.filters.path`.

> [!WARNING]
> A custom filter jar is code that runs with all the permissions of the simulator, so load only
> jars you trust. The JVM build listens on every network interface; pass
> `--server.address=127.0.0.1` to accept connections only from your own computer.

All the jars share one class loader, which can also use the classes of the SolarNode bundles the
simulator includes. Add any library jars your filters need to the path too, or embed them in
your bundle with its `Bundle-ClassPath` manifest header.

[datum-filters]: https://solarnetwork.github.io/solarnode-handbook/users/datum-filters/
