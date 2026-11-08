# SeekerNetDiagnostics

Client-side ping testing for **Minecraft 1.8.9 / Forge 11.15.1.2318**. It pings Minecraft servers
from inside the running game process, so the tests take the same route as your game connection
(including ExitLag, if ExitLag handles Minecraft). It also compares ExitLag routes side by side.

**Why not `ping` or `tracert`?** ExitLag optimizes traffic per program. Command-line tools are separate
programs (and `ping` uses ICMP, which ExitLag doesn't route), so they show your normal route. This mod's
connections come from `javaw.exe` itself.

## Safety

- Never touches game packets. Tests use their own separate connections.
- Never joins a server. Each sample is a server-list ping (status + ping/pong), the same thing the Multiplayer menu does.
- Commands run locally and are never sent to the server.
- No background traffic. One connection at a time, at least 500 ms apart, only to the host you choose.
- Exports contain no tokens, account IDs, public IP or local addresses.

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

**Joining online servers from `runClient`:** [DevAuth](https://github.com/DJtheRedstoner/DevAuth) is
included as a dev-only dependency and enabled via `clientJvmArgs`, using the `main` account from
`~/.devauth/config.toml`. On the first launch, open the link it
prints and sign in with Microsoft. Tokens are stored **unencrypted** in `C:\Users\<you>\.devauth\`, so
never share that folder. DevAuth isn't packaged into the release jar. To use offline mode instead,
remove `-Ddevauth.enabled=true` from `clientJvmArgs`.

**Asset downloads:** ForgeGradle 2.1 downloads game assets over plain `http://`, which Mojang now
rejects. The `fetchAssetsHttps` task in `build.gradle` fetches them over HTTPS (SHA-1 verified) before
`getAssets` runs.

## Commands

Type these in chat. They run only on your machine and are never sent to the server. `/snd`,
`/seekernetdiag` and `/seekernetdiagnostics` are the same command.

| Command | What it does | When to use it |
|---|---|---|
| `/snd` | Opens the dashboard: Test My Connection, Test an IP, Compare ExitLag Routes | Most of the time. Everything below is also a button. |
| `/snd test <host> [port] [quick\|detailed]` | Pings the server 10 times (50 with `detailed`), one new connection each, and prints the results in chat. With no port, the server's SRV record is used, like the vanilla client. | To check your real ping and stability to a server, e.g. `/snd test 203.27.106.53` |
| `/snd compare` | Opens route comparison: baseline, then each new route, side by side with a recommendation | To decide which ExitLag route or setting is better |
| `/snd compare status` | Prints each profile's stats and the recommendation | To check a comparison without the GUI |
| `/snd export` | Writes JSON plus two CSVs (samples and summary) to `config/netscope/reports/` | To keep or share results, or open them in a spreadsheet |
| `/snd history` | Prints your last 8 test results | To recall earlier numbers |
| `/snd settings` | Ping counts, interval, timeout, export options | To change test size or timing |
| `/snd cancel` | Stops the running test and any queued tests | If a test is taking too long, e.g. a server that keeps timing out |
| `/snd help` | Lists the commands in chat | When you forget the syntax |

**Comparing ExitLag routes:**

1. Turn ExitLag on and join the server.
2. Run `/snd`, then click **Compare ExitLag Routes** and **Start Baseline**.
3. Change the route in ExitLag yourself, then click **Test New Route**.
4. Click **Repeat** a few times. The screen tells you which profile to switch ExitLag to next.
5. Read the recommendation, then run `/snd export`.

Dashboard keys: `1`/`2`/`3` for the main actions, `↑`/`↓` to select a result, `Ctrl+F` to search, `Tab`/`Enter` to navigate, `E` to export, `Esc` to close.

## What the numbers mean

- **Ping (the result):** the round trip of a Minecraft ping packet, from sending it until the server's pong arrives. Each sample is a fresh connection from the game process. This is an application-level, end-to-end time and should be close to the ping the tab list shows. A failed sample never counts as a ping.
- **TCP connect (diagnostic only):** with ExitLag on, Minecraft's connections are accepted by ExitLag on your own PC, so connect time can be under 1 ms. When that happens with a public server address, the mod reports that **a local proxy is handling the connection**. This shows interception, not which route ExitLag chose. The "Proxy" column on the dashboard marks these runs.
- **Failures:** each one is labelled refused, connect timeout, no response, no pong, not Minecraft, DNS failure or unreachable. A timeout doesn't prove packet loss.
- **Stats:** percentiles use linear interpolation (R-7). Jitter is the mean absolute difference between consecutive pings.
- **Route comparison:** you change ExitLag yourself between runs, then repeat in A-B-B-A order. The verdict uses a 95% bootstrap confidence interval on the median difference. It says "no clear winner" when results overlap, and asks for more data below 10 successful pings.
- **ExitLag:** the mod can't verify which route ExitLag used. Check ExitLag's real-time monitor.

## Limitations

- Only Minecraft servers can be tested. Other ports have no ping/pong to time, and behind ExitLag a bare TCP connect only measures your own PC.
- No traceroute. A reliable one needs raw sockets and admin rights, and ICMP-based traceroute bypasses ExitLag.
- Gradle 2.7 and ForgeGradle 2.1 are old, build-time-only tools. The mod jar bundles no third-party libraries. JUnit 4.13.2 (tests) and DevAuth (dev client login) are not packaged.

Settings live in `config/seekernetdiagnostics.cfg` and test history in `config/netscope/history.json`.
