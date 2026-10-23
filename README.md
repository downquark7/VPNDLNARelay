# VPN DLNA Relay

An Android app that makes a DLNA media server on a remote network (reached through a VPN on the
phone) show up as a local DLNA server on whatever Wi-Fi the phone is connected to.

TVs and media players on the Wi-Fi discover the phone as a media server. Browsing and playback go
through the phone, which forwards everything to the real server over its VPN.

Built and tested against Kodi's built-in UPnP/DLNA server (OSMC, Kodi 21).

## How it works

DLNA discovery (SSDP) is multicast and does not cross a VPN, and the server's responses contain
absolute URLs that only work on its own network. The app bridges both:

- **Discovery** – joins the SSDP multicast group on the phone's local interfaces, answers
  `M-SEARCH` probes and sends periodic `NOTIFY` announcements that point at the phone.
- **HTTP reverse proxy** – listens on port 49200 and forwards requests to the real server over
  TCP. XML responses (device description, browse results) are rewritten so every URL points back
  at the phone; media and artwork are streamed through unchanged, including range requests.
- **Port detection** – the VPN side cannot use SSDP, so the app finds the server by scanning TCP
  ports for a MediaServer device description. Kodi's range (1024–2047) is tried first. The port is
  remembered and only rescanned when it stops answering.
- **Background** – runs as a foreground service with a persistent notification and holds wake and
  multicast locks so it keeps working with the screen off. There is no Wi-Fi lock: on Android 14+
  the high-perf lock is turned into a low-latency lock, which only applies while the app is on
  screen.

The relay advertises its own device ID and appends " (VPN)" to the server name, so it does not
clash with the real server when both are visible.

## Usage

1. Connect the phone's VPN so the media server's IP is reachable.
2. Enter the server's IP. Leave the port blank to auto-detect, or tap **Scan for port**.
3. Tap **Start**. The status line shows the address the relay is shared at.
4. Tap **Stop** in the app or in the notification to turn it off.

### Only this phone

Turn on **Only this phone** to share the server with apps on the phone itself (VLC, Kodi,
BubbleUPnP…) instead of the whole Wi-Fi. Switching it while the relay runs restarts the relay.

- The HTTP relay listens on `127.0.0.1` only, so nothing off the phone can connect.
- Discovery replies only to probes sent by apps on the phone and point them at `127.0.0.1`;
  announcements are sent with a TTL of 0 so they never leave the device.
- Discovery still needs the phone to be on a Wi-Fi network (multicast does not run on loopback).
  Without one, add the server in the app by the URL shown in the status line,
  `http://127.0.0.1:49200/DeviceDescription.xml`.

If the system stops the app in the background, set its battery usage to "Unrestricted".

## Diagnosing buffering

The relay passes media straight through without buffering; smooth playback relies on the client's
own buffer, which sits after every link in the chain. When a stream of 16 MB or more ends, the log
shows one line of timings, for example:

```
Stream 647.2 MB in 1m 14s · avg 69.1 Mbps · waiting on server 21% · waiting on client 78% · no server stalls · first byte 38 ms
```

- **avg** – the rate the client pulled data. Once its buffer is full this is close to the video's
  bitrate.
- **waiting on server** – share of the stream spent waiting for data from the server over the VPN.
  If this is high while the client buffers, the VPN or server is too slow.
- **waiting on client** – share spent handing data to the client. High is normal once the client's
  buffer is full. If it is high while the client buffers, the Wi-Fi between the phone and the client
  is the problem.
- **server stalls** – reads from the server that blocked for a second or more.
- **first byte** – time to connect to the server and receive the response head. It applies to
  every seek, because each request opens a new connection.

The counters are updated a few times per 64 KB chunk and nothing runs on a timer, so measuring
adds no wakeups. A Wi-Fi drop on the phone shows up as "No Wi-Fi network to share on" in the log.

## Security

- **There is no authentication.** DLNA has none, so anyone on the same Wi-Fi can browse and play
  the library while the relay is on. Do not turn it on on public or untrusted networks.
  On such networks, use **Only this phone**.
- The relay only serves peers on the same subnet as one of the phone's local interfaces.
  Connections arriving over cellular or the VPN are dropped, and SSDP probes from outside the
  local subnet are ignored.
- The proxy only ever connects to the configured server IP and port.
- Event subscriptions (`SUBSCRIBE`) are acknowledged locally and never forwarded.
- Traffic between Wi-Fi clients and the phone is plain HTTP, as with any DLNA server.

## Limitations

- With a full-tunnel VPN (one that routes all traffic), Android may send the discovery replies
  into the tunnel instead of to the Wi-Fi client. Split-tunnel VPNs work.
- If the Wi-Fi network uses the same subnet as the remote server's network, routing is ambiguous.
- Port detection expects the device description at `/DeviceDescription.xml` (Kodi / Platinum
  UPnP). For other servers the proxy may work, but the port scan will not find them.
- No server-side change-event notifications; clients see new content when they browse again.

## Building

GitHub Actions builds a debug APK and runs the unit tests on every push to `main` and on pull
requests. Each successful build of `main` is published as a release on the **Releases** page, with
the version bumped automatically from the newest `vX.Y` tag (v1.1, v1.2, …). To start a new major
version, tag the commit `v2.0` by hand; later builds continue from v2.1.

Builds are signed with a throwaway debug key that differs per run, so uninstall the previous build
before installing a new one.

To build locally:

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires Android 13 or newer (minSdk 33, targetSdk 37). On Android 17 the app asks for the
local-network and notification permissions on first launch.
