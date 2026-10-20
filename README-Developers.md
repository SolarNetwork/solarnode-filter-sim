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
| `layout.ts`    | The automatic layout of the flow chart. |
| `canvas.ts`    | The [JointJS][jointjs] canvas, and the gestures that edit chains. |
| `datumRoutes.ts` | The route each datum took through the drawing, from its steps, for the canvas to draw. |
| `routeOverlay.ts` | Draws datum routes on the canvas: lanes, the lines back through the queue, and number badges. |
| `routesLegend.ts` | The datum routes legend, which shows and hides routes like a Grafana legend. |
| `inspector.ts` | The filter inspector: the settings form, rendered from `/api/settings-form`, plus the instance ID, comment, and setting notes. |
| `inputs.ts`    | The run inputs (datum, histories, starting state, and metadata), saved in the browser, which make up a `/api/simulate` request. |
| `inputPanel.ts` | The input tabs and the Entry Point select. |
| `results.ts`   | The Execution Flow panel, which shows a simulation result, and the run statistics the canvas shows on its nodes. |
| `debug.ts`     | Debug mode: the cursor positions, the node state rebuilt at each filter step, and the state tabs. |
| `view.ts`      | Rendering helpers shared by the execution flow, debug views, and dialogs, including message rows with a copy button. |
| `instanceIds.ts` | SolarNode's instance ID rules, the same as the app's `InstanceIds` class. |
| `html.ts`      | A sanitizer for the HTML in SolarNode setting descriptions. |
| `session.ts`   | Simulation files: the workspace and run inputs saved as JSON, to share a simulation or open it in another browser. |
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
| `GET /api/info` | The application name and version. |
| `GET /api/filter-types` | The supported filter types, sorted by name. |
| `POST /api/settings-form` | Get a filter's settings form: a `SettingsFormRequest` in, a `SettingsForm` out. Labels and descriptions follow the `Accept-Language` header. |
| `POST /api/settings/import` | Import a SolarNode settings CSV file, sent as a `text/csv` body or a `file` form upload: a `SettingsImport` out. |
| `POST /api/settings/merge` | Add a settings CSV file to a configuration, the way a node imports a file: a `SettingsMergeRequest` (`config` and `csv`) in, a `SettingsImport` with the combined configuration out. |
| `POST /api/settings/external` | Combine External Settings files the way a node applies its auto settings files: an `ExternalSettingsRequest` (`files` of `name` and `csv`) in, `ExternalSettings` out. |
| `POST /api/settings/export` | Export a configuration as a settings CSV file: a `SettingsExportRequest` in, a `SettingsExport` out. With `external`, rows that the external settings already have are left out. |
| `POST /api/simulate` | Run a simulation: a `SimulationRequest` in, a `SimulationResult` out. An invalid request gets a `400` response with problem details. |

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

# Building

```sh
./gradlew build
```

This builds the UI (with `npm ci` and a Vite production build, using a Gradle-managed Node) and
packages it into the app. It also compiles and tests the app and runs the code checks. The
runnable jar is `app/build/libs/sn-filter-sim-<version>.jar`. `build` also builds the native
distribution (see below), so it needs GraalVM. Without it, `./gradlew bootJar` builds the
runnable jar, and `./gradlew check` runs the tests and code checks.

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

### Distribution

The release distribution is a tarball holding only the native executable, named for the
operating system and CPU (`darwin`, `linux`, or `windows`, and `arm64` or `amd64`), for example
`app/build/distributions/solarnode-filter-simualtor-0.1.0-linux-arm64.tar.gz`:

```sh
GRAALVM_HOME=/opt/graalvm-jdk@25 ./gradlew :solarnode-filter-sim-app:distTar
```

The executable runs on its own: the JDK shim libraries and build report that `nativeCompile`
writes next to it are left out. Gradle 9 archives normalize file permissions, so the
distribution sets the executable's mode explicitly.

### Native smoke test

A native image only has the reflection metadata that Spring's AOT processing and
`SimulatorRuntimeHints` give it, so an expression can fail natively that works on the JVM. The
`nativeSmokeTest` task builds the executable (if needed), starts it on a free port, and runs
the smoke fixtures through `/api/simulate`:

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

# Unreleased SolarNetwork bundles

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

[bootstrap]: https://getbootstrap.com/
[bootstrap-icons]: https://icons.getbootstrap.com/
[jointjs]: https://www.jointjs.com/
[vite]: https://vite.dev/
