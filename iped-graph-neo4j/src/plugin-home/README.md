# iped-graph-neo4j — link analysis plugin for IPED

Optional plugin that enables **link analysis (graph)** in IPED: generation of the communications graph
(calls, e-mails, instant messages, contacts...) during processing and the **"Links"** tab in the analysis
interface. It contains the Neo4j Community Edition 5 engine (GPL v3) and the Neo4j Bolt driver, which is
why it is distributed separately from IPED. Without this plugin IPED works normally, only without link
analysis.

## Install (3 steps)

1. Get `iped-graph-neo4j-<version>.zip` (published together with each IPED release).
2. Extract the zip **inside the IPED plugin folder**. By default it is the `plugins` folder next to the
   IPED application folder (`LocalConfig.txt` → `pluginFolder = ../plugins/`):

   ```text
   <dist>/
   ├── iped-<version>/                  (IPED application)
   └── plugins/
       ├── iped-graph-neo4j-<version>.jar
       └── iped-graph-neo4j/            (this folder: neo4j/, plugin.properties, LICENSE.txt, ...)
   ```

3. Restart IPED (processing or analysis). No configuration change is needed: graph generation
   (`enableGraphGeneration` in `IPEDConfig.txt`) is already enabled in the default profile.

## Check

The processing and analysis logs contain a line like:

```text
Graph plugin loaded: iped-graph-neo4j <version> (SPI v1, built for IPED <version>) from <plugins>/iped-graph-neo4j-<version>.jar
```

If the plugin is not loaded, the log tells why: `Graph plugin not installed (plugin folder: ...)`, or
`Graph plugin rejected (<reason>): <detail>` where the reason is one of:

| Reason | What to do |
|---|---|
| `multiple copies` | Keep only one `iped-graph-neo4j-*.jar` in the plugin folder. |
| `SPI mismatch` | Use the plugin version that matches your IPED (see Compatibility). |
| `incomplete` | Extract the **whole** zip: the `iped-graph-neo4j/neo4j/` folder must be next to the jar. |
| `load error` | The jar is corrupted or incompatible: download and extract the zip again. |

## Remove

Delete `iped-graph-neo4j-<version>.jar` and the `iped-graph-neo4j/` folder from the plugin folder and
restart IPED. Link analysis data already generated in cases (`<case>/iped/neo4j/`) is kept on disk, it is
only not used. Note that, without the plugin, IPED refuses to modify (`--append`, `--continue`, `-remove`) a
case that has link analysis data, unless `--ignore-missing-graph-plugin` is given (the graph is then not
updated).

## Compatibility

The plugin is accepted when the version of the graph extension point it implements
(`IPED-Graph-SPI-Version` in the jar manifest, also in `plugin.properties` as `spi.version`) is equal to the
one of the running IPED. Release numbers are informative only. The release notes of each IPED version list
the graph SPI version; this plugin implements **SPI v1**.

To update the plugin, remove the previous version before extracting the new one: two copies in the plugin
folder are rejected.

## Cases

When a case is processed with the plugin available, IPED copies the plugin (jar + `iped-graph-neo4j/`) to
`<case>/plugins/`, so the case opened from its own copy (`<case>/IPED-SearchApp`) has link analysis even on a
machine without the plugin installed.

## Troubleshooting: loose driver jars

The plugin jar embeds the Neo4j Bolt driver with its Netty/Reactor dependencies relocated under
`iped.graph.neo4j.shaded`. If some environment ever fails to load the relocated driver (`load error` with
Netty/Reactor classes in the detail), the fallback is to build the plugin jar without relocation and put the
driver jars (`neo4j-java-driver`, `netty-*`, `reactor-core`, `reactive-streams`) loose in the plugin folder,
next to `iped-graph-neo4j-<version>.jar`.

## Licenses

This package combines IPED code with Neo4j Community Edition (GPL v3) and is distributed under the GPL v3
(`LICENSE.txt`). Third-party notices are in `ThirdParty.txt`.
