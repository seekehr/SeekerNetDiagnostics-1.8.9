# SeekerNetDiagnostics

In-game ping monitor for **Minecraft 1.8.9 / Forge 11.15.1.2318**. It pings the server from inside the
game process, so the pings take the same route as your game connection (including ExitLag, if it
handles Minecraft), and shows everything on one screen.

**Why not `ping` or `tracert`?** ExitLag optimizes traffic per program. Command-line tools are separate
programs (and `ping` uses ICMP, which ExitLag doesn't route), so they show your normal route.

## Safety

- Never touches game packets. Pings use their own separate connections.
- Never joins a server. Each ping is a server-list ping (status + ping/pong), like the Multiplayer menu.
- One connection at a time, at most one every 0.5 s, only to the server you choose.
- Pinging stops automatically when you leave the server.

## Install

Put `SeekerNetDiagnostics-1.8.9-2.0.0.jar` in your Forge 1.8.9 instance's `mods` folder (Prism: *Edit → Mods → Add file*).

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

## Usage

Join a server and type `/snd`. Commands run locally and are never sent to the server.

| Command | What it does |
|---|---|
| `/snd` | Start monitoring the server you're on and open the monitor |
| `/snd <host> [port]` | Monitor another server instead |
| `/snd stop` | Stop pinging |
| `/snd hud` | Drag the in-game ping line to a new spot, or turn it on/off (saved in `config/seekernetdiagnostics.cfg`) |

The monitor screen has:

- **Stats:** now, average, median, min, max, p95, p99, jitter, loss and spikes since the last reset.
- **Live graph:** one bar per ping, red for failures, blue marks for the tab-list ping. Hover a bar for details.
- **Route:** what you typed, the SRV record, DNS answers, the IP being pinged, the game socket's remote and
  local address, which network adapter the game and the pings leave through, TCP connect time, and whether a
  local proxy (ExitLag/VPN) is intercepting the connection. Also tab-list ping vs measured ping and server info.
- **Traceroute:** runs the system `tracert` to the server IP and lists each hop. This is your normal ISP
  route: it uses ICMP, which ExitLag doesn't carry.

Buttons: Pause (`Space`), interval (0.5/1/2/5 s), Reset (`R`), Trace route (`T`), Copy summary (`C`).
Closing the screen keeps pinging in the background with the HUD; `/snd` reopens it.

## What the numbers mean

- **Ping:** time from sending a Minecraft ping packet until the pong arrives, on a fresh connection each time.
  It should be close to the tab-list ping. A failed ping never counts as a ping; it counts as loss.
- **Spikes:** pings above 1.5 x median + 20 ms.
- **Jitter:** mean absolute difference between consecutive pings.
- **TCP connect under 1.5 ms** to a public IP means a local proxy accepted the connection, not the server.
  It shows interception, not which route ExitLag picked.
