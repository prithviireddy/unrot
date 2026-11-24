# Changelog

## Unreleased
- Unrot now shows a small, silent "Unrot is blocking" notification while blocking is on. It helps stop your phone from closing Unrot (for example Recents › Clear all on Realme). See decision [0012](docs/decisions/0012-keep-alive-on-by-default.md).
- If you set Unrot up before this version, onboarding said "Nothing else gets a notification." That is no longer true: the notification above is part of every build.
- On Android 13 and later, Unrot is listed under "Active apps" at the bottom of quick settings. Its Stop button turns Unrot off until you open the app again.
