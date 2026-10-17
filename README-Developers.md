# SolarNode Filter Simulator - Developers Guide

This document has information for developers of the SolarNode Filter Simulator.

# Project layout

| Directory | Gradle project | Contents |
|:----------|:---------------|:---------|
| `app`     | `:app`         | The Spring Boot application, which runs the SolarNode filters and provides the web API. |
| `web`     | `:web`         | The web UI: a [Vite][vite] project using TypeScript, [Bootstrap][bootstrap], and [JointJS][jointjs]. The Gradle build packages it into a jar under `static/`, which Spring Boot serves. |

# Requirements

 * JDK 25: Gradle provisions it automatically as a toolchain if needed.
 * Node.js 24 for UI development (see `web/.nvmrc`). The Gradle build downloads its own copy of
   Node, so a local one is only needed to run the Vite development server.
 * GraalVM for JDK 25, to build the native executable.

# Running the app

```sh
./gradlew :app:bootRun
```

Then visit <http://localhost:8080/>.

# UI development

For UI development, use the Vite development server. It reloads UI changes instantly, without
restarting the app. Run the app and the Vite server side by side:

```sh
# terminal 1: the app, which provides the /api endpoints
./gradlew :app:bootRun --console=plain

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

# Building

```sh
./gradlew build
```

This builds the UI (with `npm ci` and a Vite production build, using a Gradle-managed Node) and
packages it into the app. It also compiles and tests the app and runs the code checks. The
runnable jar is `app/build/libs/app-<version>.jar`.

## Native executable

The native executable needs GraalVM for JDK 25. Point `GRAALVM_HOME` at it, for example:

```sh
GRAALVM_HOME=/opt/graalvm-jdk@25 ./gradlew :app:nativeCompile
```

The executable is `app/build/native/nativeCompile/sn-filter-sim`.

By default the native build uses `-O3` and a fixed 160 MB runtime heap. You can supply your own
`native-image` arguments from any of these sources:

 * the `NATIVE_BUILD_ARGS` environment variable, as a comma-delimited list
 * the `nativeBuildArgs` Gradle property, as a comma-delimited list
 * a `native-build.args` file in `app/`, with one argument per line and `#` comment lines
   (choose another file with the `nativeBuildArgsFile` Gradle property)

Arguments from all of these sources are combined, and they replace the defaults entirely.

For example, for a faster development build:

```sh
NATIVE_BUILD_ARGS=--enable-native-access=ALL-UNNAMED,-Ob GRAALVM_HOME=/opt/graalvm-jdk@25 \
  ./gradlew :app:nativeCompile
```

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
./gradlew -PlocalIvy :app:bootRun
```

The local Ivy repository defaults to `~/.ivy2/local`. Use `-PlocalIvyPath=/some/path` to change
it.

[bootstrap]: https://getbootstrap.com/
[jointjs]: https://www.jointjs.com/
[vite]: https://vite.dev/
