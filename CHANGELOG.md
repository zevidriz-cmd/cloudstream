# Changelog - Cloudstream v4.8.0

All notable changes to the Cloudstream application in this major visual and functional release are documented below.

## [4.8.0] - 2026-06-01

### Added
- **Dynamic 1.05x TV Focus Scale**: Integrated a highly responsive, cinematic `1.05x` zoom scaling animation on horizontal media cards, cast lists (`ActorAdaptor`), and episode lists (`EpisodeAdapter`) upon receiving remote D-pad focus.
- **Material 3 Grouped Settings Dashboard**: Wrapped preference items in `main_settings.xml` inside premium translucent charcoal card containers (`MaterialCardView` with `#121414` background, `16dp` rounded corners, and a `#1AFFFFFF` glass boundary border) with horizontal dividers.
- **Cinematic Subtitle Defaults**: Configured modern default subtitle typography styles inside `SubtitlesFragment.kt` to auto-apply bold `26sp` layout sizes with outline edge highlights, guaranteeing high contrast.

### Changed
- **Premium Global Color Mapping**: Overhauled global accent colors from default blue (`#3d50fa`) to primary Brand Crimson (`#E50914`), and mapped all backgrounds to pure OLED Black (`#000000`).
- **Circular Smart-TV PINpad Padlock**: Upgraded rectangular PIN entry buttons inside `dialog_pin_entry.xml` to perfect circle `oval` shape targets with responsive D-pad glowing borders and an OLED black layout dialog.
- **Sleek rounded corner radii**: Standardized dimension resource guidelines to premium `16dp` corner radii for poster cards (`rounded_image_radius`) and `24dp` radii for pill action buttons (`rounded_button_radius`).

### Fixed
- **TV Unresponsiveness & Navigation**: Re-mapped all core interactive targets with D-pad safe focus bindings (`android:focusable="true"`) to prevent focus trapping while keeping mobile touchscreen gesture inputs completely pure.
- **Thread Safety in Asynchronous Loading**: Secured `previewResponsesAdded` and `lock` sets in `HomeViewModel.kt` against concurrency risks via thread-safe `ConcurrentHashMap.newKeySet()` implementations, preventing any potential `ConcurrentModificationException` during heavy home scroll loads.
