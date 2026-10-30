# SolarNode Filter Simulator - Developers Guide

This document has information for developers of the SolarNode Filter Simulator.

# Project layout

| Directory | Gradle project              | Contents |
|:----------|:----------------------------|:---------|
| `app`     | `:solarnode-filter-sim-app` | The Spring Boot application, which runs the SolarNode filters and provides the web API. |
| `web`     | `:solarnode-filter-sim-web` | The web UI: a [Vite][vite] project using TypeScript, [Bootstrap][bootstrap], and [JointJS][jointjs]. The Gradle build packages it into a jar under `static/`, which Spring Boot serves. |

# Requirements

 * JDK 25: Gradle provisions it automatically as a toolchain if needed.
 * Node.js 24 for UI development (see `web/.nvmrc`). The Gradle build downloads its own copy of
   Node, so a local one is only needed to run the Vite development server.
 * GraalVM for JDK 25, to build the native executable.


# Running the app

```sh
./gradlew :solarnode-filter-sim-app:bootRun
```

Then visit <http://localhost:8080/>.

# Code style

Java sources are formatted with the Eclipse profile in `config/codeformatter.xml`, applied by
Spotless. UI sources are checked with ESLint and formatted with Prettier. `./gradlew check`
verifies both.

```sh
./gradlew spotlessApply          # format Java sources

cd web
npm run lint:fix                 # fix ESLint findings where possible
npm run prettier:write           # format UI sources
```

Java code is compiled with ErrorProne and NullAway. Every package has a `package-info.java`
annotated `@NullMarked`, and nullable types use the JSpecify `@Nullable` annotation.


# Building

```sh
./gradlew build
```

This builds the UI (with `npm ci` and a Vite production build, using a Gradle-managed Node) and
packages it into the app. It also compiles and tests the app and runs the code checks. The
runnable jar is `app/build/libs/sn-filter-sim-<version>.jar`. `build` also builds the native
distribution (see below), so it needs GraalVM. Without it, `./gradlew bootJar` builds the
runnable jar, and `./gradlew check` runs the tests and code checks.

## Using unreleased SolarNetwork bundles

The SolarNode and SolarNetwork Common bundles come from Maven Central, at the versions in
`gradle/libs.versions.toml`. To try an unreleased bundle version, first publish it to your local
Ivy repository with `ant publish` in the bundle's directory (in the `solarnetwork-node` or
`solarnetwork-common` repository). Then set that version in `gradle/libs.versions.toml` and pass
`-PlocalIvy` to Gradle:

```sh
./gradlew -PlocalIvy :solarnode-filter-sim-app:bootRun
```

The local Ivy repository defaults to `~/.ivy2/local`. Use `-PlocalIvyPath=/some/path` to change
it. Publish with `ant publish -Dpublish.ivy=true`, so the repository has the bundle's Ivy
descriptor, which Gradle needs to resolve it.

## Native executable

The native executable needs GraalVM for JDK 25. Point `GRAALVM_HOME` at it, for example:

```sh
GRAALVM_HOME=/opt/graalvm-jdk@25 ./gradlew :solarnode-filter-sim-app:nativeCompile
```

The executable is `app/build/native/nativeCompile/sn-filter-sim`. It listens on port 2008 by
default (`FilterSimulatorApplication.NATIVE_DEFAULT_PORT`), and only on `127.0.0.1`
(`NATIVE_DEFAULT_ADDRESS`), while the JVM build keeps Spring Boot's 8080 on every interface,
which the Vite dev server proxies to.

The build needs about 5 GB of memory for `native-image`. On a machine with less to spare, stop
other JVMs (such as a `bootRun`) first, or cap the builder's heap with `-J-Xmx4g` in the
native build arguments below.

By default the native build uses `-O3` and a fixed 256 MB runtime heap. You can supply your own
`native-image` arguments from any of these sources:

 * the `NATIVE_BUILD_ARGS` environment variable, as a comma-delimited list
 * the `nativeBuildArgs` Gradle property, as a comma-delimited list
 * a `native-build.args` file in `app/`, with one argument per line and `#` comment lines
   (choose another file with the `nativeBuildArgsFile` Gradle property)

Arguments from all of these sources are combined, and they replace the defaults entirely.

For example, for a faster development build:

```sh
NATIVE_BUILD_ARGS=--enable-native-access=ALL-UNNAMED,-Ob GRAALVM_HOME=/opt/graalvm-jdk@25 \
  ./gradlew :solarnode-filter-sim-app:nativeCompile
```

### Native smoke test

A native image only has the reflection metadata that Spring's AOT processing and
`SimulatorRuntimeHints` give it, so an expression can fail natively that works on the JVM. The
`nativeSmokeTest` task builds the executable (if needed), starts it on a free port, and runs
each smoke fixture twice: through `/api/simulate`, and as a background run through the runs API,
as the UI does (following its events, then getting its result and the JSON, CSV, and zip downloads). It then runs
the `datum-sources` fixture for an hour of simulated time, so the result is over 200 datum, and
checks that the runs API's result, with the traces it loads by number, matches `/api/simulate`'s:

```sh
GRAALVM_HOME=/opt/graalvm-jdk@25 ./gradlew :solarnode-filter-sim-app:nativeSmokeTest
```

The fixtures are in `app/src/test/resources/s10k/node/sim/filter/smoke/`. Each `<name>.json` is
a simulation request, and `<name>.expected.json` is a summary of the expected result: message
codes, stats, and each datum's step statuses and output samples. `SmokeFixtureTests` checks the
same summaries on the JVM. When a summary differs, the actual one is written to
`app/build/reports/native-smoke/<name>.actual.json` (or `app/build/smoke/` by the test), next
to the executable's log. A native-only difference usually means a missing runtime hint, shown
as a `FilterLog` message with the expression error. When a behaviour change is deliberate,
review the new summary and copy it over the fixture's.

## Distribution

The release distribution is a tarball holding only the native executable, named for the
operating system and CPU (`darwin`, `linux`, or `windows`, and `arm64` or `amd64`), for example
`app/build/distributions/solarnode-filter-simualtor-0.1.0-linux-arm64.tar.gz`:

```sh
GRAALVM_HOME=/opt/graalvm-jdk@25 ./gradlew :solarnode-filter-sim-app:distTar
```

The executable runs on its own: the JDK shim libraries and build report that `nativeCompile`
writes next to it are left out. Gradle 9 archives normalize file permissions, so the
distribution sets the executable's mode explicitly.


# UI development

For UI development, use the Vite development server. It reloads UI changes instantly, without
restarting the app. Run the app and the Vite server side by side:

```sh
# terminal 1: the app, which provides the /api endpoints
./gradlew :solarnode-filter-sim-app:bootRun --console=plain

# terminal 2: the Vite development server
cd web
npm install
npm run dev
```

Then visit <http://localhost:5173/>. The Vite server proxies `/api` requests to the app on port
`8080`. You only need to run `npm install` again when `package.json` changes.

The UI sources are under `web/src/main`:

| Path | Contents |
|:-----|:---------|
| `index.html` | The page. |
| `ts/`        | TypeScript sources; `main.ts` is the entry point. |
| `scss/`      | Styles. |
| `img/`       | Images. |

The main TypeScript modules are:

| Module | Contents |
|:-------|:---------|
| `main.ts`      | The application: wires the parts together, and handles running simulations, import, export, and keyboard shortcuts. |
| `workspace.ts` | The configuration being edited: filters, chains, undo and redo, and saving in the browser. |
| `layout.ts`    | The automatic layout of the flow chart, with the static datum and datum sources left of the Main chain. |
| `canvas.ts`    | The [JointJS][jointjs] canvas, and the gestures that edit chains. |
| `datumRoutes.ts` | The route each datum took through the drawing, from its steps, for the canvas to draw. |
| `jsonEditor.ts`, `jsonFormat.ts`, `jsonLint.ts`, `jsonComplete.ts` | The [CodeMirror][codemirror] JSON editor of the JSON inputs, its Format button's formatter, its schema checks, and its key completion. |
| `report.ts`    | Execution Flow reports: the flow as a stand-alone HTML page. |
| `routeOverlay.ts` | Draws datum routes on the canvas: lanes, the lines back through the queue, and number badges. |
| `routesLegend.ts` | The datum routes legend, which shows and hides routes like a Grafana legend. |
| `inspector.ts` | The filter inspector: the settings form, rendered from `/api/settings-form`, plus the instance ID, comment, and setting notes. |
| `settingsForm.ts` | The settings form fields of a filter or datum source, rendered from `/api/settings-form`, which edit a settings map: help, notes, resets, dynamic lists, and Choose menus. |
| `inputs.ts`    | The run inputs (datum, datum sources and Operational Mode Invokers, histories, starting state, metadata, controls, and Control Conductors), saved in the browser, which make up a simulation request. |
| `inputPanel.ts` | The input tabs, including the datum source and invoker cards with their poll counts, and the Entry Point select. |
| `results.ts`   | The Execution Flow panel, which shows a simulation result (with a run's progress and Cancel, downloads, and, over 200 datum, the datum list with each datum's trace loaded when it is reached), the time zone note with its UTC toggle, the datum selected to show their routes over 20 datum, and the run statistics the canvas shows on its nodes. |
| `datumList.ts` | The datum list of a result over 200 datum: its filters (source IDs, time range, outcomes, datum number) and virtualized rows, with a current row the keys move and the selected rows. |
| `dates.ts`     | Dates in a time zone: local date-times of instants, and the instants of entered dates, across daylight saving changes. |
| `debug.ts`     | Debug mode: the cursor positions, the node state rebuilt at each filter step, and the state tabs. |
| `view.ts`      | Rendering helpers shared by the execution flow, debug views, and dialogs, including message rows with a copy button, the controls table, instruction lists, and the names of a run's datum sources. |
| `instanceIds.ts` | SolarNode's instance ID rules, the same as the app's `InstanceIds` class. |
| `html.ts`      | A sanitizer for the HTML in SolarNode setting descriptions. |
| `session.ts`   | Simulation files: the workspace and run inputs saved as JSON, to share a simulation or open it in another browser. |
| `capture.ts`   | Datum capture: the Capture from SolarNode dialog, and the panel that shows each captured datum until its datum go into the Datum input. |
| `externalSettings.ts` | The External Settings dialog: settings CSV files the simulation applies first, combined by `/api/settings/external`. |
| `palette.ts`, `dialogs.ts`, `splitter.ts` | The other parts of the page. |
| `icons.ts`     | The [Bootstrap Icons][bootstrap-icons] of the filter types. |
| `api.ts`, `model.ts` | The web API client and its types. |

To change a filter's icon, change its entry in `icons.ts` to the name of another Bootstrap icon.


# Web API

The UI talks to the app through a small JSON API. The request and response types are the
records in the `s10k.node.sim.filter.domain` package.

| Endpoint | Description |
|:---------|:------------|
| `GET /api/info` | The application name and version, `customFilters` if the app was started with a custom filters path, and the server's `timeZone`. |
| `GET /api/filter-types` | The supported filter types, sorted by name. A custom filter type has its `bundle` (`name` and `version`). |
| `POST /api/filter-types/reload` | Load the custom filters again: a `CustomFiltersReload` out, with the filter types, the unavailable ones, and the `messages` of the startup message. A `400` response if there is no custom filters path. |
| `GET /api/filter-types/unavailable` | The filter types the simulator knows of but cannot run, such as custom filter types that need a service it does not provide, sorted by name, each with its `reason`. |
| `GET /api/datum-source-types` | The supported datum source types, sorted by name, each with its default poll schedule and `capabilities` (`Controls`, `Instructions`, `TariffSchedule`). |
| `POST /api/settings-form` | Get a filter's, datum source's, or Operational Mode Invoker's (`net.solarnetwork.node.datum.opmode.invoker`) settings form: a `SettingsFormRequest` in, a `SettingsForm` out. Labels and descriptions follow the `Accept-Language` header. A datum source's settings are its poll job's (`schedule` and `jobService.datumDataSource.*`, or `jobService.multiDatumDataSource.*` for a multi datum source), and a datum source with controls has its `controlIds`. |
| `POST /api/settings/import` | Import a SolarNode settings CSV file, sent as a `text/csv` body or a `file` form upload: a `SettingsImport` out, with the filters, and the controls, Control Conductors, supported datum sources, and Operational Mode Invokers for the run inputs. |
| `POST /api/settings/merge` | Add a settings CSV file to a configuration, the way a node imports a file: a `SettingsMergeRequest` (`config` and `csv`) in, a `SettingsImport` with the combined configuration out. |
| `POST /api/settings/external` | Combine External Settings files the way a node applies its auto settings files: an `ExternalSettingsRequest` (`files` of `name` and `csv`) in, `ExternalSettings` out. |
| `POST /api/settings/export` | Export a configuration as a settings CSV file: a `SettingsExportRequest` in, a `SettingsExport` out. With `external`, rows that the external settings already have are left out. |
| `POST /api/simulate` | Run a simulation: a `SimulationRequest` in, a `SimulationResult` out. An invalid request gets a `400` response with problem details. |
| `POST /api/runs` | Start a simulation in the background: a `SimulationRequest` in, a `RunInfo` (`id`) out at once. An invalid request gets a `400` response with problem details, as does a request while the simulator is busy with other runs. |
| `GET /api/runs/{id}/events` | A run's progress, as a `text/event-stream` (see below). |
| `GET /api/runs/{id}/progress` | A run's progress now: a `RunProgress`. |
| `GET /api/runs/{id}` | A finished run's result: a `RunResult` (see below). A run that is still running gets a `409` response, and one the simulator no longer has a `404`. |
| `GET /api/runs/{id}/datum?seq=1,2` | The traces of some of a run's datum, by `seq`, up to 200, in the order asked. |
| `GET /api/runs/{id}/datum.json` | Download a finished run's output datum (persisted, in processing order), or with `kind=input` each datum as it entered the Datum Queue, as a JSON array in the format of the Datum input. |
| `GET /api/runs/{id}/datum.csv` | Download a finished run's datum (`kind` as above) as CSV, in the style of SolarNetwork's datum stream CSV: `ts`, `nodeId` (the `nodeId` parameter, 1 by default), `sourceId`, the instantaneous, accumulating, and status properties (one column per name), and `tags`. |
| `GET /api/runs/{id}/datum.zip` | The same, as a zip archive of one CSV per source ID, each with the columns of its own datum. |
| `DELETE /api/runs/{id}` | Cancel a run, which stops and keeps what it has, with a `Cancelled` message. |
| `POST /api/capture` | Start capturing the datum a SolarNode captures: a `CaptureRequest` (`url`, `username`, `password`, and optional `sourceIds`) in, a `CaptureInfo` (`id` and `topics`) out. A node that cannot be reached or does not accept the login gets a `400` response with problem details. |
| `GET /api/capture/{id}/events` | A capture's datum, as a `text/event-stream` (see below). |
| `DELETE /api/capture/{id}` | End a capture. |

A minimal simulation request runs one Expression filter on the main chain:

```json
{
  "config": {
    "mainChainId": "main",
    "filters": [
      {"id": "main", "factoryUid": "net.solarnetwork.node.datum.xform.user",
       "settings": {"uid": "Chain - Main"}, "filterIds": ["exp"]},
      {"id": "exp", "factoryUid": "net.solarnetwork.node.datum.samplefilter.expression",
       "settings": {"uid": "EXP", "expressionConfigsCount": "1",
         "expressionConfigs[0].name": "kw",
         "expressionConfigs[0].datumPropertyTypeKey": "i",
         "expressionConfigs[0].expressionServiceId": "net.solarnetwork.common.expr.spel.SpelExpressionService",
         "expressionConfigs[0].expression": "watts / 1000.0"}}
    ]
  },
  "datum": [{"created": "2025-10-01 00:00:00Z", "sourceId": "/meter/1", "i": {"watts": 1234}}]
}
```

The request can also include `history` datum, a starting `environment`, and `options` such as
`"detail": "Debug"` for state snapshots, or `"runAfter": "PT5M"` to keep the simulated clock
running after the last datum, so scheduled tasks like the Timeout filter's can run. The environment holds the starting operational modes and
Local State CSV, placeholders (`placeholders`, a map of names to values), datum stream
metadata (`datumMetadata`, a map of source IDs to metadata), plus the metadata that metadata
services provide. The Node Metadata Service
always exists, with `nodeMetadata` (or no metadata), and `userMetadata` adds User Metadata
Services. A Tariff filter finds its service by the Service Name in its Metadata Service setting:

```json
"environment": {
  "nodeMetadata": {"pm": {"site": {"factor": 2.5}}},
  "userMetadata": [{"uid": "Tariffs", "metadata": {"pm": {"tariffs": {
    "schedule": "Month,Day,Weekday,Time,Rate\nJan-Jun,,,,0.10\nJul-Dec,,,,0.20"}}}}]
}
```

A settings form request names the filter type and gives its current settings:

```json
{
  "factoryUid": "net.solarnetwork.node.datum.samplefilter.expression",
  "settings": {"uid": "EXP", "expressionConfigsCount": "1", "expressionConfigs[0].name": "kw"}
}
```

The response lists the form fields in the order SolarNode shows them. Each field has a `type`
(such as `Text`, `TextArea`, `Toggle`, `Select`, or `Group`), its setting `key`, a `label`, a
`description` (HTML), and the current `value`. The response also lists `problems`, such as
unknown setting keys.

A `Group` with `dynamic` set is a list. To add an item, increase the setting named by its
`countKey`. To remove the last item, decrease that count and delete the settings whose keys
start with `key[n]`, where `n` is the new count. Then request the form again.

## Datum capture

A capture records the datum a real SolarNode captures, as input datum. The node's web app only
accepts WebSocket connections from its own pages, so the app connects in the browser's place: it
logs in like the node's login page (`GET /csrf`, `POST /login`, then `GET /csrf` again for the
new session's token), connects to the node's STOMP WebSocket at `/ws` with the session cookie and
the token as a `CONNECT` header, and subscribes to `/topic/datum/captured/**`, where the node's
Datum Queue posts each datum before its filters run. `sourceIds`, a comma-delimited list of
Ant-style patterns such as `/meter/**, /inv/*`, subscribes to a topic for each pattern instead;
the node leaves the source ID's leading `/` out of its topics. The node sends a datum once for
each subscription it matches, so the app drops the repeats that overlapping patterns cause.

The event stream has `state` events (`{"state":"Capturing"|"Ended","count":…,"guessed":…}`, plus
`message` and `error` once it ends) and a `datum` event for each datum, in the SolarNode datum
JSON format, with its number (from 1) as the event ID. The app keeps the datum, so an
`EventSource` that reconnects gets those after its `Last-Event-ID`. A capture ends when it is
deleted, when the node ends the connection, after 10,000 datum, or when no browser has watched it
for a minute.

From `net.solarnetwork.node` 4.10.1 a datum message includes the datum itself, as `_Datum`, so
each property keeps its type. Older nodes send only the datum's properties, merged together, so
the app guesses the types: numbers are instantaneous, except `wattHours` and
`wattHoursReverse` (also with a phase suffix like `_a`), which are accumulating, and everything
else is status. The UI says when it guessed.

```sh
curl -H 'Content-Type: application/json' \
  -d '{"url":"http://solarnode.local","username":"me@example.com","password":"…"}' \
  http://localhost:8080/api/capture
curl -N http://localhost:8080/api/capture/<id>/events
curl -X DELETE http://localhost:8080/api/capture/<id>
```

## Background runs

`POST /api/runs` runs a simulation on a small pool of threads (two at a time), for long runs that
the UI shows progress for and can cancel. The simulator keeps the last three runs, however long ago
they ran, until newer runs replace them. While a run runs, it keeps each datum's trace compressed,
so memory grows with the compressed size.

The event stream has a `progress` event at once and about every 250 ms while the run goes on, and a
`done` event when it ends, each a `RunProgress`:
`{"state":"Running"|"Done"|"Failed","processed":…,"expected":…,"date":…}`, plus `error` and
`problems` when it failed. `expected` is the input datum plus each datum source's polls, up to
Maximum Datum, without the datum filters generate. A run that has ended sends only `done`.

A `RunResult` is a `SimulationResult` with the run's `id`. Up to 200 datum it has every trace. Above
that, `datum` has the first 20 traces only, plus an `index` of every datum (`seq`, `sourceId`,
`created`, `origin`, `outcome`, and `error`) and `filterStats`, each filter's counts (`in`, `out`,
`dropped`, `changed`, `skipped`, `errors`). Load other traces with `/datum?seq=`.

The server limits runs with `--sim.max-datum` (default 100,000) and `--sim.max-run-time` (default
`10m`). A run's timeout is 10 seconds per 1,000 datum of its Maximum Datum (at least 10 seconds), up
to the maximum run time.

```sh
curl -H 'Content-Type: application/json' -d @request.json http://localhost:8080/api/runs
curl -N http://localhost:8080/api/runs/<id>/events
curl -o output.json 'http://localhost:8080/api/runs/<id>/datum.json'
curl -o output.zip 'http://localhost:8080/api/runs/<id>/datum.zip?nodeId=123'
curl -X DELETE http://localhost:8080/api/runs/<id>
```

## Settings CSV files

The import reads a settings CSV file the way SolarNode does (Core Settings Support 4.2.1), and
returns the filter configuration, `messages` about settings a node would skip or that the
simulator changed, and the `ignored` components that are not filters. Comment lines are kept
with the filter they introduce.

```sh
curl -H 'Content-Type: text/csv' --data-binary @settings.csv http://localhost:8080/api/settings/import
```

A chain member that is not in the file becomes an "external" filter, with the factory UID
`external` and only a `uid` setting. Datum pass through it unchanged, and an export writes only
the chain's reference to its Service Name, so a partial file keeps its chain intact.

The export takes `{"config": …, "style": "Organized"}` and returns `{"csv": …, "messages": […]}`.
The `Organized` style writes the Datum Queue and the Main chain first, then the other filters
in execution order, each with its comments or a generated banner. The `Plain` style sorts the
rows like a SolarNode export. A configuration with instance IDs that SolarNode does not allow
gets a `400` response whose `problems` array lists each one.


# Custom filters and datum sources

The standard filter types are ported by hand from their bundles' blueprints, in
`StandardFilterTypes`. Custom filter types, from the jars of `sim.filters.path`, are defined
from their bundles' blueprints at startup instead (the `custom` package):

1. `CustomFilterLoader` reads each bundle's manifest and blueprint XML, from a jar or a bundle
   project directory (`BundleSource`), and puts all the bundles on one `URLClassLoader`, whose
   parent is the app's. It loads copies of the jars, and of the jars a bundle's
   `Bundle-ClassPath` names, from a temporary directory (`TempFiles`). A bundle project's
   classes come from its `build.properties` output and source folders, or the usual output
   folders of each build tool.
2. `FilterBlueprintInterpreter` (the `blueprint` package) describes each user datum filter
   type the blueprint defines, as a `FilterTypeDescriptor`.
3. `BlueprintFilterTypes` turns each description into a `FilterTypeDefinition`, with its
   `FilterTypeOrigin` (the bundle and its class loader):
   - `ServiceBindings` maps each service the blueprint gives the filter to one of the
     `FilterContext` services. A type that needs any other service is not available.
   - Properties that `SimulationContext.injectServices()` sets (its `INJECTED_PROPERTIES`)
     are skipped.
   - A constructor that takes a `Clock` or `InstantSource` before the blueprint's arguments
     is used when there is one, with the simulated clock.
   - One filter is created, with simulated services, to check that the type works.

The factory, init and destroy methods, and each filter call, run with the bundle's class loader
as the thread context class loader. Problems loading a jar, and types that are not available,
are listed in the startup message, as are the warnings of `ImportCheck`. It checks each jar's
`Import-Package` header for packages that nothing has, and for `net.solarnetwork` packages
whose version range excludes the simulator's. It does not check the versions of other
libraries, as the simulator runs the SolarNode bundles with newer ones than they import (such as
Spring 7).

`CustomFilterService` owns the custom filters and the `FilterTypeRegistry`. A reload loads the
path again with a new class loader, replaces the registry's filter types, and closes the old
class loader. A simulation run and a settings CSV import or export each use a
`FilterTypeRegistry.snapshot()`, so a reload does not change their filter types partway. The
`testFilterProject` task lays out the test bundle as a bundle project directory, for the tests
of bundle projects, named by the `sim.test.filterProject` system property.

The registry also holds the types that are not available (`UnavailableFilterType`), with
`StandardFilterTypes.UNSUPPORTED`. A settings CSV import keeps their filters, and a simulation
passes datum through them unchanged, with the reason in its message. The UI shows the reason
in the inspector and on the canvas, puts the custom types under **Custom** in the palette, and
saves the bundle of each custom type a simulation uses in simulation files
(`customFilters`), to say which bundles are missing when a file is opened.

Custom datum source types come from the same bundles. `FilterBlueprintInterpreter` also
describes each managed service factory of a `ManagedJob` whose `SimpleManagedJob` wraps a
`DatumDataSourcePollManagedJob`, as a `DatumSourceTypeDescriptor` (the datum source bean, the
job's schedule argument, the interfaces of its `serviceProviderConfigurations`, and `multi` when
the bean is the poll job's `multiDatumDataSource`).
`BlueprintDatumSourceTypes` turns each into a `DatumSourceTypeDefinition` with the shared
bean creation in `BlueprintBeans` (which `BlueprintFilterTypes` uses too), binding services with
`ServiceBindings.datumSourceService()`, which adds the `DatumSourceContext` services. It prefers a
constructor that takes a clock and then a `RandomGenerator`, given the datum source's seeded
random generator. A multi type (`DatumSourceTypeDefinition.multi`) is a `MultiDatumDataSource`,
which `SimulationContext.createDatumSource()` gives the poll job as its `multiDatumDataSource`;
its settings use `settingPrefix()` (`jobService.multiDatumDataSource.`), which the UI's datum
source cards read too (`sourceSetting()`), and the invokers get the multi datum sources as their
`MultiDatumDataSource` collection. `CustomFilterService` also owns the `DatumSourceTypeRegistry`, which a reload
replaces, and runs, forms, and CSV imports (`DatumSourceSettings`) use the registry's
snapshots. Each datum source call runs with the bundle's class loader
(`DatumSourceTypeDefinition.run()`). `BlueprintDatumSourceTypesTests` defines the four standard
datum source types from their own blueprints and compares the `datum-sources` and
`opmode-invoker` smoke fixtures' results with `StandardDatumSourceTypes`'.

`BlueprintFilterTypesTests` defines the standard filter types from their own blueprints and
checks that the smoke fixtures give the same results as with `StandardFilterTypes`. That checks
the service bindings against real filters. `CustomFilterLoaderTests` loads a test bundle that
the build makes from the `testFilterBundle` source set, with a library jar from the
`testFilterLib` source set embedded in it. The bundle is not on the test class path; the
`sim.test.filterBundle` system property names its jar.

[bootstrap]: https://getbootstrap.com/
[bootstrap-icons]: https://icons.getbootstrap.com/
[codemirror]: https://codemirror.net/
[jointjs]: https://www.jointjs.com/
[vite]: https://vite.dev/
