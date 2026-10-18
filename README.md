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

The simulator is a single application that serves its web UI over HTTP, by default on port
`8080`. Once it is running, visit <http://localhost:8080/> in a desktop web browser.

To run from source with Java 25:

```sh
./gradlew :solarnode-filter-sim-app:bootRun
```

To run the native executable (see [README-Developers.md](README-Developers.md) for how to build
it):

```sh
./app/build/native/nativeCompile/sn-filter-sim
```

Pass `--server.port=8081` to use a different port.

[datum-filters]: https://solarnetwork.github.io/solarnode-handbook/users/datum-filters/
