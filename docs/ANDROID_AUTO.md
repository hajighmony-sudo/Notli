# Notli Android Auto integration

## Architecture

Notli is a media-category Android Auto app. The in-car experience is rendered by Android Auto from the app's `MediaBrowserServiceCompat` tree and `MediaSessionCompat`; it is not a phone Activity mirrored onto the head unit. Keep the existing `AuxioService`, `MusicBrowser`, `MusicServiceFragment`, and `MediaSessionHolder` as the single source of truth for browsing and playback.

The automotive manifest descriptor must continue to declare the `media` capability. Do not add a custom Car App Library `CarAppService` just to replace the native media experience: media apps should expose browse/playback through the media APIs.

## Expected media UX

- Browse the library using system-rendered categories for songs, albums, artists, genres, and playlists.
- Provide useful title, artist, album, duration, and artwork metadata for the current track.
- Keep transport actions available through the media session: play, pause, next, previous, seek, repeat, shuffle, and queue selection where supported by the host.
- Support media-ID playback and voice search. Empty/unknown voice queries must not crash the service or unexpectedly start unrelated content.
- Handle stale queue IDs and malformed paging hints defensively.
- Preserve playback in the foreground media-playback service and restore the current media state through the existing playback manager.

## Driver and passenger safety boundary

Android Auto and the vehicle manufacturer control which input methods are enabled while driving. A media app cannot override a head unit's touch lockout, infer reliably whether a touch came from the driver or passenger, or grant a passenger-only touch exemption through a generic Android API.

Do not implement an app-side "unlock touch while moving" switch, biometric/PIN bypass, or a consent dialog that claims to waive responsibility and disables platform restrictions. Fingerprint/PIN authentication proves device-user identity; it does not prove that the user is a passenger or that interacting is safe.

When the vehicle/head unit permits passenger touch input, Notli should accept the normal media-browser actions. When the head unit disables touch, Notli must continue to support steering-wheel buttons, hardware media keys, and voice playback/search through the media session. The host may expose different input capabilities by vehicle and region; Notli must not promise passenger touch where the car does not provide it.

If an app flow genuinely requires the phone screen, show a non-urgent instruction to interact with the phone only after it is safe and the vehicle is parked. Never send a notification that encourages a driver to look at or unlock the phone while moving.

## Selected implementation decisions

### Passenger mode: OEM-gated

The generic Android Auto integration intentionally does not infer the driver's or passenger's identity. Passenger-specific input may be enabled only through a documented OEM SDK/API that explicitly reports a trusted passenger-input capability or occupant state, and only when the vehicle manufacturer authorizes third-party access.

Keep OEM integrations isolated behind a vendor-specific adapter/module. The adapter must:
- be opt-in and enabled only on supported vehicle/head-unit combinations;
- use the manufacturer's documented permission and lifecycle requirements;
- treat unavailable, denied, stale, or ambiguous occupant/input state as unsupported;
- never unlock host touch restrictions or change Android Auto's driver-distraction policy;
- expose capability status for diagnostics without collecting or retaining unnecessary occupant data.

Until a supported OEM, SDK, and authorization path are identified, the shipped behavior remains the standard Android Auto media experience. No biometric/PIN flow or phone notification is used to bypass driving restrictions.

### UI mode: branded native media

Use the Notli identity only in app-owned assets and media metadata: a simple high-contrast monogram, restrained cyan accent, and accurate track/artist/album/artwork metadata. Keep the attribution icon tintable so Android Auto can apply its own contrast and theme treatment. Do not promise custom layouts, fixed colors, or custom control placement; the host owns those surfaces.

## Visual design direction

The attached reference images define the Notli direction: a dark navy/black cyber identity, restrained electric-blue/cyan accents, strong contrast, prominent square album artwork, large readable track and artist labels, a clear progress timeline, and distinct previous/play-pause/next controls. Browse categories should feel like compact visual tiles, while queue entries should retain clear artwork and text hierarchy.

Implementation is deliberately limited to app-owned Android Auto media surfaces:
- Advertise grid presentation for the app's top-level browse categories using the standard MediaBrowser content-style hint; leave nested content and host pagination behavior intact.
- Render app-owned songs, albums, artists, genres, playlists, and more glyphs as crisp cyan vector artwork in the MediaDescription bitmaps; preserve the user's real album art for track and album rows.
- Use a high-contrast, monochrome, tintable Notli monogram for the Android Auto attribution icon. Android Auto may tint it to match the vehicle theme.
- Continue publishing the existing title, artist, album, duration, album artwork, queue, and transport metadata through MediaSession. The host decides how much of that metadata to show on each vehicle.
- Keep the existing browse IDs, category contents, playback callbacks, queue semantics, voice search, and OEM-gated passenger policy unchanged.

The reference's blue glow, full custom cards, exact layout, fixed colors, animated equalizer, and custom button placement cannot be forced by a generic Android Auto media app. Android Auto/head-unit software owns those surfaces. Do not add a separate Car App Library service or replace the media browser/session architecture to imitate a screenshot.

The visual treatment must degrade gracefully across Android Auto versions, screen sizes, and vehicle brands: rely on standard media metadata and content-style hints, keep text and artwork useful without color, and never assume a specific display aspect ratio or OEM skin.

## Verification checklist

1. Build debug and release variants and run unit/instrumentation tests.
2. Test with Android Auto Desktop Head Unit (DHU) at multiple display sizes.
3. Test a real head unit for browse, voice search, playback, seek, skip, repeat, shuffle, queue updates, reconnect, and steering-wheel controls.
4. Test empty libraries, long lists, paged children, stale queue actions, missing artwork, and malformed search requests.
5. Verify that Notli never attempts to bypass UX restrictions and that the car host remains responsible for touch availability.
6. Review the current Android for Cars media quality checklist before Play submission.

## Known limitation

A generic Android Auto media app cannot distinguish driver touches from passenger touches. Passenger-specific interaction is only possible if the vehicle OEM exposes a supported, trustworthy passenger-input or occupant-state API and the app is authorized to use it. Do not infer passenger status from seat occupancy guesses, touch location, biometrics, or a user confirmation.


## Open-source implementation research

The following public projects were reviewed as implementation references. Their code was not copied wholesale; Notli keeps its existing media browser/session architecture and uses only compatible, narrowly scoped presentation patterns.

- [Android for Cars samples](https://github.com/android/car-samples) (Apache-2.0): official template and host-compatibility examples. Useful for understanding the boundary between app-provided content/actions and host-rendered UI.
- [Open Hype](https://github.com/josusanmartin/open-hype) (MIT): centralizes MediaConstants browse hints, separates category-style root navigation from compact content rows, and supplies app-owned artwork for top-level sections. Notli adopts the general browse-hint pattern, not its service architecture or product-specific code.
- [Music Assistant mobile app](https://github.com/music-assistant/mobile-app) (Apache-2.0): useful reference for artwork delivery, stable artwork URIs, and testing metadata/artwork across Android Auto sessions.
- [Melodee Player](https://github.com/melodee-project/melodee-player) (MIT): useful reference for Android Auto media metadata, voice/search behavior, and test coverage around media browser integration.
- [Universal Android Music Player](https://github.com/googlesamples/android-UniversalMusicPlayer): historical multi-form-factor reference for sharing a media session across phone and car surfaces.

### Applied browse presentation

- Root navigation categories advertise category/grid presentation with Notli's own cyan section artwork.
- The More node advertises category-style children.
- Songs and nested library contents advertise compact list rows; browsable album, artist, genre, and playlist items tell the host that their children are list content.
- Album/artist/genre/playlist artwork continues to come from the user's actual library. No static concept screenshot replaces live track metadata or controls.

### Why the full concept image is not the live car screen

The generated Cyber image is a visual target, not a valid Android Auto screen definition. A MediaBrowserServiceCompat app cannot submit an arbitrary bitmap as its complete screen and retain working, host-integrated controls. The screenshot includes host-owned elements such as the app rail, status indicators, other apps, and the bottom dock. Notli can brand its own attribution icon, section artwork, media metadata, and supported actions; Android Auto/head-unit software chooses the overall layout, colors, sizing, and control placement.

Car App Library templated media experiences offer more customization through host-approved templates, but Google's current documentation describes templated media as beta/limited-distribution. Do not switch Notli to that path as a universal default until eligibility, host support, and distribution requirements are confirmed. Keep the existing standard media service as the compatibility baseline.
