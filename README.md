# SolarNode Filter Simulator

The SolarNode Filter Simulator is a web application for designing and testing SolarNode
[Datum Filter][datum-filters] configurations without a SolarNode:

 * build a filter chain on a canvas, starting from the main filter chain on the Datum Queue,
   including nested chains
 * configure each filter with the same settings SolarNode provides
 * simulate the node's controls and Control Conductors, which filters like the Control Updater
   and Instructor use
 * run sample datum through the chain, and see how each filter transformed them
 * poll simulated datum sources, like a Mock Energy Meter or Mock Battery, on their schedules
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

The native executable also opens its URL in your web browser when it starts (on Linux, only when
there is a graphical display). Pass `--sim.browser.open=false` (or set the `SIM_BROWSER_OPEN`
environment variable to `false`) to turn this off, or `--sim.browser.open=true` to turn it on when
running from source.

## External commands

The POA Calculator (pvlib) filter runs an external command, normally the `ghi-to-poa` script of
the SolarNodeOS `sn-pvlib` package, to calculate POA irradiance. The simulator runs a command only
when its full path is in the **Allowed Commands** list, on the Environment tab of the inputs.
Allow only programs you trust, as they run on the computer running the simulator. A list of
commands to allow in every simulation can also be set when starting the simulator, with
`--sim.commands.allowed=/path/to/command,/another/command`.

## Long runs

A run processes up to its **Maximum Datum** (on the Environment tab, 1,000 when blank), and its
time limit grows with it: 10 seconds per 1,000 datum. A result over 200 datum shows as a list you
can filter, and the Execution Flow's download menu saves the output or input datum as JSON. The simulator allows at most 100,000 datum and 10 minutes per run;
change these when starting it with `--sim.max-datum` and `--sim.max-run-time` (such as `30m`).

## Custom filters

The simulator can also run datum filters that you write yourself, from the SolarNode bundles you
build for them. Start the simulator with `--sim.filters.path` (or the `SIM_FILTERS_PATH`
environment variable) set to bundle jars, bundle project directories, or directories of them,
separated by commas:

```sh
java -jar sn-filter-sim-<version>.jar --sim.filters.path=~/dev/my-filters/build/
```

A bundle project directory is one with a `META-INF/MANIFEST.MF`, like the bundle projects of the
`solarnetwork-node` repository. The simulator loads its classes from the output folders its
Eclipse `build.properties` names, with its source folders for resources such as `.properties`
files. Without a `build.properties`, it uses the first of `build/eclipse`, `build/ant/classes`,
`bin`, `build/classes/java/main`, and `target/classes` that exists.

After you rebuild a filter, click **Reload** above the palette (or `POST /api/filter-types/reload`)
to load the custom filters again, without restarting the simulator. The next run uses the new
classes. The simulator loads copies of the jars, so a build can replace them while it runs.

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
your bundle with its `Bundle-ClassPath` manifest header. The startup message warns about the
packages a bundle's `Import-Package` header names that no jar has, and about `net.solarnetwork`
packages it imports in a version range that does not include the simulator's version.

Custom filters appear under **Custom** in the palette. A saved simulation file names the bundle
and version of each custom filter it uses, so opening it in a simulator without that bundle, or
with another version, says which bundle it needs.

### Writing filters the simulator can run

The simulator creates each filter the way SolarNode does: with the blueprint's constructor
arguments and properties, then its settings, then its init method. It does not call an
`update-method` such as `configurationChanged`.

- **Time:** if the filter class also has a constructor that takes a `Clock` or `InstantSource`
  before the blueprint's arguments, the simulator uses it, with a clock that follows the datum
  timestamps. Give a filter that reads the time such a constructor, as the standard filters
  have, so it behaves the same in a simulation as on a node.
- **Services:** the blueprint can give the filter these services, as a `<reference>`, or with a
  `DynamicServiceTracker`:
  - `DatumQueue`, `DatumMetadataService`, `InstructionExecutionService` (which gives
    `SetControlParameter` instructions to the simulation's controls), and `ReactorService`
  - `TaskScheduler`, `TransientSettingDao`, and `MetricDao`
  - `MetadataService`: with the `(function=node)` filter, the Node Metadata Service, otherwise the
    metadata services of the simulation's environment
  - `TemporalTariffEvaluator` and `TariffScheduleProvider` (trackers only), and the filters, as a
    `<reference-list>` of `DatumFilterService`
  - the object mapper of an `ObjectMapperService` (`factory-method="getObjectMapper"`)
- **Common services:** the services that `BaseIdentifiable` and `BaseDatumFilterSupport` filters
  use (expressions, datum history, operational modes, placeholders, metadata, location, tariff
  schedules, and Local State) are always the simulator's, whatever the blueprint wires.
- **Not supported:** any other service, such as `IdentityService`, giving the filter the
  `bundleContext`, and a filter bean with a `factory-method`. A filter type that needs one of
  these is not available; datum pass through its filters unchanged, and imports keep their
  settings.
- **Settings:** the settings form comes from the filter's `getSettingSpecifiers()` and the
  message source of its `SettingSpecifierProviderFactory` service, as on a node.

[datum-filters]: https://solarnetwork.github.io/solarnode-handbook/users/datum-filters/
