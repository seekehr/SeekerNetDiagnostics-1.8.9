# SeekerNetDiagnostics (Minecraft Forge 1.8.9)

Client-side network diagnostics for Minecraft 1.8.9. It measures TCP handshake time and the
Minecraft server-list ping **from inside the running Minecraft JVM**, monitors your live connection
passively, and compares ExitLag route configurations side by side. You get plain-language results
and don't need to know any networking.

| | |
|---|---|
| Mod ID | `seekernetdiagnostics` |
| Version | 1.0.0 |
| Minecraft / Forge | 1.8.9 / 11.15.1.2318 |
| Java | 8 |
| Side | Client only (servers don't need it) |

## Safety: what the mod does and does not do

- **It never modifies, injects, delays, drops or reorders game packets.** The optional packet observer
  is a read-only Netty handler. It timestamps keepalive and tab-list latency packets and passes every
  message on unchanged. It is **off by default**.
- **It never joins a server.** The status ping stops after the status and ping/pong exchange
  (handshake `nextState = 1`). It never enters the login state.
- **Commands run locally.** `/ns` and `/snm` are Forge client commands and are never sent to the server.
- **It sends no background traffic.** It only contacts a host when you start a test. The live monitor
  only reads values Minecraft already has.
- **It limits its own traffic.** Only one connection attempt is in flight at a time. Attempts to the same
  IP and port are at least 500 ms apart, whatever the settings say. At most 4 jobs can be queued and
  every job can be cancelled. The mod never scans ports or address ranges.
- **It only probes what you name.** It connects only to the host and port you enter, or to the server
  you're connected to when you click "Test My Connection".
- **It collects no private data.** Exports never contain session tokens, account names or UUIDs, or your
  public IP. Local interface addresses are excluded unless you turn them on.
- **It needs no native code and no admin rights.**

> Repeated TCP connections to a server look like server-list refreshes. The defaults (10 samples,
> 1 s apart) are conservative. Avoid running many detailed tests in a row against a server you're
> playing on, because some anti-bot proxies rate-limit bare connections.

## Installation (Prism Launcher)

1. Create or select a **Minecraft 1.8.9** instance with **Forge 11.15.1.2318** (Prism: *Edit → Version → Install Forge*).
2. Copy `SeekerNetDiagnostics-1.8.9-1.0.0.jar` into the instance's `mods` folder (Prism: *Edit → Mods → Add file*).
3. Launch the game. Type `/ns` or `/snm` in chat to open the dashboard.

It works alongside common Forge PvP mods: it adds no keybinds, mixins, coremods or access transformers.

## Building

Required toolchain (legacy ForgeGradle; modern Gradle/JDKs won't work):

| Tool | Version |
|---|---|
| JDK | **8** (e.g. Temurin 8). Gradle 2.7 does not run on Java 9+. |
| Gradle | 2.7 via the included wrapper (`gradlew`); no separate install needed |
| ForgeGradle | 2.1-SNAPSHOT (from `https://maven.minecraftforge.net/`) |
| MCP mappings | `stable_20` |

```bash
./gradlew build
```

The distributable, reobfuscated JAR is written to `build/libs/SeekerNetDiagnostics-1.8.9-1.0.0.jar`.
`build` also runs the full test suite.

Other useful tasks:

```bash
./gradlew test
```

```bash
./gradlew setupDecompWorkspace
```

```bash
./gradlew runClient
```

On Windows, use `gradlew.bat`, or run `./gradlew` from Git Bash. Make sure `JAVA_HOME` points to a JDK 8.

### Legacy ForgeGradle limitations

- ForgeGradle 2.1 only runs on Gradle 2.x and JDK 8. Newer Gradle or Java versions fail during plugin
  application.
- The first build downloads Minecraft, Forge and the MCP mappings. After that it can run with `--offline`.
- Gradle 2.7 and ForgeGradle 2.1 are old build-time tools. Their own dependencies (for example old
  Guava and Apache Commons) are **only on the build classpath and are never packaged** into the mod.
  The mod JAR bundles no third-party libraries. At runtime it uses only the JDK plus Netty and LWJGL,
  which Minecraft already ships. JSON parsing uses a small built-in parser with size and depth limits,
  not Minecraft's old Gson.
- The only added dependency is JUnit 4.13.2, which is test-scope only and is the release that fixes
  CVE-2020-15250.
- `gradle-wrapper.jar` is committed, but the root `.gitignore` excludes `*.jar`. It has an explicit
  exception for the wrapper.

## Using the dashboard

Open it with `/ns`, `/snm` or `/seekernetmod`.

- **Test My Connection**: tests the server you're connected to (TCP handshakes plus a Minecraft status
  ping) and starts the live monitor.
- **Test an IP**: enter an IP or host name. The port defaults to 25565. Click **Start Test** or press Enter.
  You get real timings, or an explicit failure such as refused, timed out or DNS failure. If no handshake
  completes, the mod says that *a valid TCP handshake latency cannot be measured* and shows no ping value.
- **Compare ExitLag Routes**: see [Route comparison](#route-comparison) below.
- **Advanced Tools ▾** (or press `A`): Advanced TCP details, Route Analysis, monitor on/off,
  overlay on/off, Export Report and Settings.

The dashboard has three tabs:

- **Overview**: connection card (server, resolved endpoint, TCP state, session time, live ping,
  min/avg/max, p50/p95/p99, jitter, disconnects, monitor status) and an interactive latency graph.
  The graph has 30 s / 1 m / 5 m / 15 m / whole-session windows, red spike markers, a hover tooltip
  for each sample and a per-minute quality strip.
- **Results**: searchable, sortable history (host, port, type, successes, failures, median, p95, date).
  Quick actions: Retest, Copy IP, Compare, Details, Export, Clear History (needs a second click to confirm).
- **Alerts**: stability alerts. Each alert names the metric that triggered it.

Keyboard: `1`/`2`/`3` run the main actions, `←`/`→` switch tabs, `Tab`/`Shift+Tab` move focus,
`Enter` activates, `↑`/`↓` select a result, `Ctrl+F` jumps to search, `M` toggles the monitor,
`E` exports, `Esc` goes back.

## Command reference

| Command | Description |
|---|---|
| `/seekernetmod`, `/snm` | Open the dashboard |
| `/ns` | Open the dashboard |
| `/ns test <host> [port] [quick\|detailed]` | TCP handshake samples plus one Minecraft status ping. With no explicit port, a host name's `_minecraft._tcp` SRV record is used, like the vanilla client. Results are printed in chat. |
| `/ns tcp <host> <port>` | TCP handshakes to exactly that port, e.g. `/ns tcp 203.27.106.53 443`. No SRV lookup, no Minecraft protocol. |
| `/ns monitor [on\|off\|status\|clear]` | Live monitor. No argument starts it and opens the dashboard. |
| `/ns compare` | Open route comparison. `/ns compare status` prints the current comparison. |
| `/ns export` | Write JSON and CSV reports to `config/netscope/reports/` |
| `/ns settings` | Open settings |
| `/ns cancel` | Cancel running or queued tests |
| `/ns history` | Print the most recent results |
| `/ns help` | Command help |

`/netscope` is an alias for `/ns`.

## What is measured, exactly

### TCP handshake RTT

A non-blocking NIO `SocketChannel` is opened inside the Minecraft JVM. `System.nanoTime()` is read
immediately before `connect()`, and again when `finishConnect()` reports the connection established.
That interval covers SYN → SYN/ACK plus local scheduling. No data is sent, and the socket is closed
right away. A configurable number of warm-up connections run first and are excluded from statistics.
DNS is resolved once, so every sample targets the same IP.

Only successful handshakes become samples. Failed attempts are classified as:

| Category | Meaning |
|---|---|
| Reachable | Handshake completed |
| Refused | The host answered with a reset; nothing is listening on that port |
| Timed out | No reply within the timeout. This is **not** treated as proof of packet loss. |
| DNS failure | The name could not be resolved |
| Unreachable | The OS reported no route to the host or network |
| Local error | The socket failed locally |
| Unknown | Anything else |

Handshake time is connection-setup time. It is **not** the same as Minecraft gameplay latency.

### Minecraft status ping (application-level)

This is a full 1.8.9 server-list ping: optional SRV lookup, handshake (protocol 47, `nextState = 1`),
status request, then status response. Responses are capped at 256 KiB, and an absolute timeout applies
to every read. Three timings are reported separately:

- `tcpConnectMs`: the TCP connect.
- `statusResponseMs`: status request → full response. This includes server or proxy processing.
- `pingPongMs`: ping → matching pong. The pong payload is checked against the ping payload.

If TCP succeeds but the status step fails (non-Minecraft service, proxy closes the connection,
oversized or garbled response), both results are reported separately. A protocol mismatch, where the
server reports a version other than 47, is noted but doesn't count as a failure.

### Statistics

- **Percentiles (p50/p95/p99)** use linear interpolation between closest ranks (R-7, the same as Excel
  `PERCENTILE.INC`): `h = (n−1)·p/100`, value = `x[⌊h⌋] + (h−⌊h⌋)·(x[⌊h⌋+1] − x[⌊h⌋])`.
- **Standard deviation** is the sample standard deviation (n − 1).
- **Jitter** is the mean absolute difference between consecutive samples in measurement order:
  `J = Σ|xᵢ − xᵢ₋₁| / (n − 1)`. This is the unsmoothed form of RFC 3550's inter-arrival jitter.

### Live monitor

The live monitor is passive and sends no traffic.

- **Minecraft-reported latency** is your own tab-list ping. The server calculates it from keepalive
  round trips and smooths it (vanilla: `(3·old + new)/4`). Vanilla servers refresh it about every 30 s,
  so it is application-level and lags real changes. **It is not instantaneous network RTT.** A sample
  is recorded when the value changes. With the packet observer on, a sample is also recorded for every
  latency-update packet.
- **The packet observer** (optional, Settings → Packet observer) also records keepalive arrival spacing
  and client turnaround (keepalive decoded → reply handed to the encoder). Spacing variation includes
  server tick timing. A gap of more than 2.5× the typical spacing raises an informational alert, never
  a packet-loss claim.
- **Other session data**: TCP state, remote socket address, session duration, disconnect count, and
  whether a disconnect screen appeared (the server or network closed the connection) or not.
- **History** is kept in a fixed-size circular buffer. Its size is set by graph history ÷ monitor
  interval, with a minimum of 128 samples.

### Stability alerts

These alerts never diagnose ISP routing, congestion, server load or packet loss:

| Alert | Trigger |
|---|---|
| Spike | A sample above the rolling median + max(50 ms, 3·MAD) |
| Sudden increase | 3 spikes in the last 5 samples |
| Frequent spikes | 5 or more spikes in the last 30 samples |
| Sustained degradation | Median of the last 10 samples ≥ max(20 ms, 20 %) above the previous 10 |
| Excessive variation | Jitter over 20 samples ≥ max(15 ms, 25 % of median) |
| TCP timeouts | Timeouts in a test run |
| TCP median increase | Median ≥ max(10 ms, 15 %) above the previous run to the same target |
| Disconnect | Minecraft disconnect, with how long the session lasted |
| Keepalive gap | See the live monitor section above |

Each rule has a 60 s cooldown. The *Diagnostic verbosity* setting controls which alerts are echoed
to chat: Quiet shows none, Normal shows warnings, Verbose shows all.

## Route comparison

ExitLag has no supported API, so **the mod never changes ExitLag and never claims a route is
verified**. Every result shows this notice:

> ExitLag routing not verified. Confirm this destination is being optimized in ExitLag's real-time monitor.

What the mod *does* guarantee is that test sockets come from the Minecraft process itself
(`javaw.exe`), so process-based optimizers have the chance to route them.

1. Enter the target. Set ExitLag to your baseline, then click **Start Baseline** (Profile A).
2. Change ExitLag yourself, describe the new settings (e.g. "Manual Singapore, 2 routes"),
   then click **Test New Route** (Profile B). You can compare up to 4 profiles.
3. Click **Repeat (ABBA)** a few times. The screen tells you which profile to switch back to next
   (A B B A …, or A B C C B A … for more profiles), so slow network drift affects every profile equally.
4. Read the side-by-side table: median, mean, p95, p99, max, IQR, standard deviation, successful
   handshakes, timeouts, other failures, duration and runs.

The recommendation is based on the difference in medians, with a 95 % bootstrap confidence interval
(2000 resamples, fixed seed):

- **Not enough samples**: fewer than 10 successful handshakes in a profile.
- **About the same**: a difference under 1 ms.
- **No clear winner**: the confidence interval includes 0.
- **One profile is faster**, worded like *"Profile B showed a 4.0 ms lower median RTT over 50 successful
  TCP connections, with similar variability."* If either profile has fewer than 30 samples, the
  recommendation also suggests another ABBA round.

## Advanced panels

- **Advanced TCP**: local interface and ephemeral port, remote IP and port, DNS lookup time, answers,
  TTL and SRV record, timeout, success rate, establishment-time stats, failure explanations and a
  table of every attempt. OS-level TCP RTT and retransmission counters are **not available**. Java's
  standard socket API doesn't expose them, and the mod deliberately ships no native code. The panel
  says so instead of estimating.
- **DNS TTL**: addresses always come from the OS resolver, the same one Minecraft uses. A supplementary
  direct UDP query to the system's DNS server reads record TTLs. If that query isn't possible, TTL is
  shown as "not obtainable".
- **Route Analysis**: in-game traceroute is **disabled** because no reliable, permission-free
  implementation is possible from Java. The panel explains why: ICMP traceroute can bypass ExitLag,
  a TCP traceroute needs raw sockets and admin rights, process routing may not apply to probes, and
  proxies hide backends. It offers **Copy IP**, **Copy IP:port** and **Export target file** so you can
  investigate with external tools. It does not claim to find hidden backend IPs.

## Exports

`/ns export`, or the Export buttons, write files to `config/netscope/reports/`:

- `report-<timestamp>.json`: timestamp with UTC offset and timezone; mod, Minecraft, Forge and Java
  versions; settings; every run (target, port, connect host, SRV, DNS answers and TTL, test method,
  timeouts, raw per-attempt timings, summary statistics, failure categories, profile label, your
  ExitLag description, Minecraft status results); the route comparison; the live monitor summary and
  samples; and alerts.
- `report-<timestamp>-samples.csv`: one row per attempt.
- `report-<timestamp>-summary.csv`: one row per run.

CSV values that start with `=`, `+` or `@` are prefixed to prevent spreadsheet formula injection.
**Copy Summary** puts a short text summary on the clipboard.

Test history persists in `config/netscope/history.json`, capped at 100 runs.

## Settings

Change these with `/ns settings` or in `config/seekernetdiagnostics.cfg`:

| Setting | Default | Range |
|---|---|---|
| Quick / detailed samples | 10 / 50 | 3–100 / 10–200 |
| Warm-up connections | 1 | 0–3 |
| Interval between attempts | 1000 ms | **500**–10000 ms |
| TCP connect timeout | 3000 ms | 500–15000 ms |
| Minecraft status timeout | 5000 ms | 1000–20000 ms |
| Dashboard refresh | 250 ms | 50–2000 ms |
| Monitor interval | 1000 ms | 250–10000 ms |
| Graph history | 30 min | 5–120 min |
| Latency overlay | off | |
| Monitor on startup (passive) | on | |
| Packet observer | off | |
| Prefer IPv4 | on | |
| Export JSON / CSV | on / on | |
| Include local addresses in exports | off | |
| Diagnostic verbosity | Normal | Quiet / Normal / Verbose |

## Tests

`./gradlew test` runs 87 unit and integration tests. They need no external servers and cover:

- RTT statistics, percentiles, jitter (both definitions) and MAD
- Circular buffer wrap-around and time windows
- Route comparison verdicts, bootstrap determinism and ABBA order
- JSON round-trips and limits, CSV escaping
- VarInt encoding and limits, status packet encoding and decoding, status JSON parsing
- DNS wire format: compression, SRV, AAAA, truncation and loop rejection
- Target parsing
- TCP probe success, refused, timeout or unreachable, cancellation and unresolved addresses
  (against local sockets)
- A **mock Minecraft status server** covering normal, close-after-status, silent, garbage, oversized
  and wrong-pong cases
- The test engine end to end: rate limiting, cancellation, bounded queue, DNS failure, and refused
  ports never producing a latency
- Export JSON and CSV content and privacy, history persistence and sorting
- Quality alerts and live-monitor behaviour

## Project layout

```
src/main/java/com/seekehr/netdiagnostics/
  SeekerNetDiagnostics.java      Forge entry point (client-only)
  core/                          Pure Java, no Minecraft dependencies, unit-tested
    stats/      RttStats, StatSummary, SampleRing, ProfileComparison
    net/        TcpProbe (NIO), ProbeOutcome, Target
    mc/         VarInt, McProtocol, McStatusClient (server-list ping)
    dns/        DnsMessage (wire codec), DnsResolver, DnsInfo
    test/       TestEngine (bounded worker), TestRun, ResultStore
    monitor/    LatencyMonitor
    quality/    QualityEngine, Alert
    compare/    CompareSession (profiles, ABBA)
    report/     ReportWriter (JSON/CSV), Conclusions (plain-language text)
    json/       Json (bounded parser/writer), Csv
    config/     Settings (clamped)
  mod/                           Minecraft integration
    NetDiag, ClientEvents, PacketObserver (passive Netty handler), SettingsStore, Chat
    commands/   CommandNs (/ns), CommandSnm (/seekernetmod, /snm)
  gui/                           Dashboard and screens (dark theme, rounded cards, graph)
src/test/java/...                Unit + integration tests (mock TCP/status servers)
```
