# SolarNode Filter Simulator

The SolarNode Filter Simulator is a web application for designing and testing SolarNode
[Datum Filter][datum-filters] configurations without a SolarNode:

 * build a filter chain on a canvas, starting from the main filter chain on the Datum Queue,
   including nested chains
 * configure each filter with the same settings SolarNode provides
 * run sample datum through the chain, and see how each filter transformed them
 * export the configuration as a SolarNode settings CSV file, or import an existing one

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
port.

[datum-filters]: https://solarnetwork.github.io/solarnode-handbook/users/datum-filters/
