# Buteo's privacy policy

Effective 21 November 2026.

Buteo is a glide computer for Android made by one glider pilot, not a
company, and built on [XCSoar](https://xcsoar.org)'s free software.
Questions about this policy:
[GitHub Issues](https://github.com/CorentinGrard/buteo/issues).

## In short

Buteo has no account, no advertising, no analytics and no crash
reporting. Its developer receives no data from it. What you fly stays
on your phone, unless you turn on a feature that sends something to a
service you chose, listed below.

## On your phone

Buteo keeps its data in its own folder on the phone
(`Android/data/app.corentin.buteo/files/XCSoarData`): your profile and
settings, planes, pilot name, flight logs (IGC files), maps, airspace,
waypoints, tasks and weather files. Uninstalling Buteo deletes this
folder.

Buteo uses the phone's location (GPS) to navigate and record flights:
while it is open, and while its notification shows that it is running,
so that the flight computer, airspace warnings and the flight log go on
with the screen off.

## What leaves the phone, and only when you ask

| Feature | Off until you… | What is sent | To |
|---|---|---|---|
| Live tracking: XCSoar Cloud | turn on "Share my position" | position, altitude, climb, thermals found | XCSoar Cloud (cloud.xcsoar.org) |
| Live tracking: SkyLines | turn on "Track on SkyLines" | position, altitude, your tracking key | [skylines.aero](https://skylines.aero) |
| Live tracking: LiveTrack24 | turn on "Track on LiveTrack24" | position, altitude, your user name and password | LiveTrack24, or the compatible server you enter |
| NOTAMs | turn them on | the area around your position | XCSoar's NOTAM server (notam.xcsoar.org) |
| WeGlide | upload a flight | the flight log (track, pilot, glider), your WeGlide pilot ID and date of birth | [WeGlide](https://www.weglide.org) |
| Share | share a flight | the flight log | the app you pick |
| Weather | add a station | the station codes | NOAA (tgftp.nws.noaa.gov) |
| Data files | download maps and files | which files | XCSoar's repository (download.xcsoar.org) |

Live tracking and its traffic also receive other pilots' positions
from these services. On the first start, Buteo uses your location to
pick the map for your region on the phone; the location is not sent.

Live tracking to XCSoar Cloud and SkyLines and the LiveTrack24 login
are not encrypted, as these services' protocols are. Like any internet
request, each of these shows your phone's IP address to the server.
Each service's own privacy policy applies to what it receives.

## Children

Buteo is a tool for glider pilots and is not directed at children.

## Changes

This page shows the current policy and its date. Its history is in
the repository: [PRIVACY.md](https://github.com/CorentinGrard/buteo/commits/mobile/mobile/docs/PRIVACY.md).
