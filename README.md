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
- **Background** – runs as a foreground service with a persistent notification and holds wake,
  Wi-Fi and multicast locks so it keeps working with the screen off.

The relay advertises its own device ID and appends " (VPN)" to the server name, so it does not
clash with the real server when both are visible.

## Usage

1. Connect the phone's VPN so the media server's IP is reachable.
2. Enter the server's IP. Leave the port blank to auto-detect, or tap **Scan for port**.
3. Tap **Start**. The status line shows the address the relay is shared at.
4. Tap **Stop** in the app or in the notification to turn it off.

If the system stops the app in the background, set its battery usage to "Unrestricted".

## Security

- **There is no authentication.** DLNA has none, so anyone on the same Wi-Fi can browse and play
  the library while the relay is on. Do not turn it on on public or untrusted networks.
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

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires Android 13 or newer (minSdk 33, targetSdk 37). On Android 17 the app asks for the
local-network and notification permissions on first launch.
