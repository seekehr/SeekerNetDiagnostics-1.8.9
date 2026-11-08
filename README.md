# SeekerNetDiagnostics

Client-side network diagnostics for **Minecraft 1.8.9 / Forge 11.15.1.2318**. It measures TCP handshake
time and the Minecraft server-list ping from inside the running Minecraft process, monitors your
connection, and compares ExitLag routes side by side.

## Safety

- Never modifies, injects, delays or reorders packets. The optional packet observer is read-only and off by default.
- Never joins a server. The status ping stops after the status and ping exchange.
- Commands run locally and are never sent to the server.
- No background traffic. One connection at a time, at least 500 ms apart, no port scanning.
- Exports exclude tokens, account IDs and your public IP. Local IPs are excluded unless you enable them.

## Install

Put `SeekerNetDiagnostics-1.8.9-1.0.0.jar` in your Forge 1.8.9 instance's `mods` folder (Prism: *Edit → Mods → Add file*).

## Build

You need **JDK 8**. The included Gradle 2.7 wrapper and ForgeGradle 2.1 don't work with newer Java.

```bash
./gradlew build
```

The jar is written to `build/libs/`. `build` also runs the tests (`./gradlew test`).

### IntelliJ

Modern IntelliJ can't import Gradle 2.7 projects, so generate a classic IntelliJ project instead:

```bash
./gradlew setupDecompWorkspace idea genIntellijRuns
```

1. Close the project in IntelliJ and delete the `.idea` folder.
2. Open `SeekerNetDiagnostics-1.8.9.ipr` (*File → Open*).
3. If IntelliJ offers to link a Gradle project, decline.
4. Set the Project SDK to JDK 8.

Minecraft sources come from the attached `forgeSrc` sources jar. `genIntellijRuns` adds the Minecraft Client run configuration.

**Asset downloads:** ForgeGradle 2.1 downloads game assets over plain `http://`, which Mojang now
rejects. The `fetchAssetsHttps` task in `build.gradle` fetches them over HTTPS (SHA-1 verified) before
`getAssets` runs.

## Commands

All subcommands also work with the `/seekernetdiag` and `/seekernetdiagnostics` aliases.

| Command | What it does |
|---|---|
| `/snd`, `/seekernetdiag`, `/seekernetdiagnostics` | Open the dashboard |
| `/snd test <host> [port] [quick\|detailed]` | TCP handshakes + Minecraft status ping (uses SRV records when no port is given) |
| `/snd tcp <host> <port>` | TCP handshakes to exactly that port |
| `/snd monitor [on\|off\|status\|clear]` | Live connection monitor |
| `/snd compare [status]` | ExitLag route comparison |
| `/snd export` | JSON + CSV reports to `config/netscope/reports/` |
| `/snd settings` · `cancel` · `history` · `help` | Settings, cancel tests, recent results, help |

Dashboard keys: `1`/`2`/`3` for the main actions, `←`/`→` for tabs, `Tab`/`Enter` to navigate, `A` for advanced tools, `M` for the monitor, `E` to export, `Esc` to close.

## What the numbers mean

- **TCP handshake:** NIO connect timed with `System.nanoTime()` from `connect()` until the connection is established. Failures are labelled refused, timed out, DNS failure, unreachable or local error, and never count as latency. A timeout doesn't prove packet loss.
- **Status ping:** TCP connect, status response and ping/pong are reported separately. These are application-level times, not raw network RTT.
- **Live ping:** the tab-list latency. The server computes and smooths it and refreshes it only periodically.
- **Stats:** percentiles use linear interpolation (R-7). Jitter is the mean absolute difference between consecutive samples.
- **Route comparison:** you change ExitLag yourself between runs, then repeat in A-B-B-A order. The verdict uses a 95% bootstrap confidence interval on the median difference and says "no clear winner" when results overlap or there are fewer than 10 successful samples.
- **ExitLag:** the mod can't verify routing. Check ExitLag's real-time monitor.

## Limitations

- OS-level TCP RTT and retransmission counters aren't available. Java doesn't expose them, and the mod ships no native code.
- In-game traceroute is disabled. A reliable one needs raw sockets and admin rights, and an ICMP traceroute can bypass ExitLag. Route Analysis lets you copy or export the target IP for external tools instead.
- Gradle 2.7 and ForgeGradle 2.1 are old, build-time-only tools. The mod jar bundles no third-party libraries. JUnit 4.13.2 is used for tests only.

Settings live in `config/seekernetdiagnostics.cfg` and test history in `config/netscope/history.json`.
