# Install

The SolarNode Filter Simulator is an application you run on your own workstation. When you run it,
it will open up in your preferred browser. If you close your browser window and want to go back to
the application, it also prints out the URL to visit so you can click on that, or copy/paste it
into your preferred browser.

## Homebrew (macOS/Linux)

You can install the `sn-filter-sim` native binary using [Homebrew](https://brew.sh/) by adding the
`SolarNetwork/tap` tap's `solarnode-filter-sim` package:

```sh
# add the SolarNetwork tap
brew tap solarnetwork/tap

# trust the SolarNetwork tap
brew trust solarnetwork/tap

# install the solarnode-filter-sim package
brew install solarnode-filter-sim
```

After that, the `solarnode-filter-sim` tool should be available:

```sh
solarnode-filter-sim

SolarNode Filter Simulator (0.7.0) started, visit
http://localhost:2008
```

## Manual (macOS/Linux/Windows)

Download the [latest release](https://github.com/SolarNetwork/solarnode-filter-sim/releases) from
Github. Download the appropriate binary archive for your system, if available. Otherwise download
the JAR file.

=== "Linux (arm64)"

	Download the tar archive named like `solarnode-filter-sim-X.Y.Z-linux-arm64.tar.gz`. Use `tar` to expand the archive,
	and then move the extracted `sn-filter-sim` application somewhere on your `PATH` like `/usr/local/bin`.

=== "Linux (x64)"

	Download the tar archive named like `solarnode-filter-sim-X.Y.Z-linux-amd64.tar.gz`. Use `tar` to expand the
	archive, and then move the extracted `sn-filter-sim` application somewhere on your `PATH` like
	`/usr/local/bin`.

=== "macOS (Apple Silicon)"

	Download the tar archive named like `solarnode-filter-sim-X.Y.Z-darwin-arm64.tar.gz`. Double-click to expand
	the archive, or use `tar`, and then move the extracted `sn-filter-sim` application somewhere on your
	`PATH` like `/usr/local/bin`.

=== "Windows (x64)"

	Download the tar archive named like `solarnode-filter-sim-X.Y.Z-windows-amd64.tar.gz `. Expand the archive,
	and then move the extracted `sn-filter-sim.exe` application somewhere on your `PATH`.

If a binary is not available for your system, you can download the Java JAR, which requires you
to have Java 25+ installed.

=== "Java JAR"

	Download the JAR file named like `sn-filter-sim-X.Y.Z.jar`. You can execute this using
	`java -jar sn-filter-sim-X.Y.Z.jar`.

