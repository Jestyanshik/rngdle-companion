# RNGdle research

Public inspection on 2026-11-10 used the original site, the HTML and frontend resources it served, and a fresh signed-out browser view. No roll was consumed and no login link was requested. The date and contents of the public leaderboard change over time.

| Surface | Confirmed evidence | Android integration |
| --- | --- | --- |
| Roll | The home screen offers Generate and advises signing in first to save the roll and rank. About describes one number per day, from 0 to 1,000,000. | Play in the original page. The user presses Generate there. |
| Result and timer | About distinguishes roll rarity (overall EP percentile) from badge rarity (badge probability). Badge detail pages publish descriptions, EP, rarity, and probability. The current home bundle renders “Next roll in” after a roll and computes it from the next UTC midnight. | Keep the result and animations on the site. Use the UTC boundary for native reminders and observe only the visible countdown to stop repeats in this installation. |
| Global rankings | The original leaderboard HTML contains Today, This Week, All-Time, and Badges. | Let the page own tabs, filters, and additional detail. |
| Account and session | The privacy page confirms email magic links with a five-minute lifetime. The shipped sign-in UI includes Cloudflare Turnstile. Authenticated callback behavior, storage details and session expiry were not exercised. | Sign in in the site's own UI. The user can manually open the first-party link inside the same WebView. Android WebView retains first-party cookies and site storage subject to the site's own expiry. |
| Profile, history and collection | Privacy says the profile exposes roll history and earned badges. The site navigation references /profile. The changelog documents favorite badge emojis, sets, and a Settings screen with theme, name color, favorites, sign-out, and account deletion. | Use the original profile, its links, and the site's Settings. The site determines editable fields. |
| Social | First-party navigation references /friends and Feed. The Friends frontend defines Rankings, Friends, Requests, My Code, and Search, with incoming and outgoing request actions. The signed-out page gates these tabs behind sign-in. | Open Friends and use the site's UI. Actual signed-in mutations and Party availability still require a test account. |
| Public API | No supported public client API was located in the site pages, sitemap, or documentation search. Frontend implementation details are not a stable external API contract. | The WebView is a normal first-party browser surface. The app makes no direct game API calls and does not compute game results. |

The research used these first-party pages. [Home](https://www.rngdle.com/), [About](https://www.rngdle.com/about), [Leaderboard](https://www.rngdle.com/leaderboard), [Badge example](https://www.rngdle.com/badges/quarter_century), [Privacy](https://www.rngdle.com/privacy), [Changelog](https://www.rngdle.com/changelog), [Settings](https://www.rngdle.com/settings), [Sitemap](https://www.rngdle.com/sitemap.xml).

The inspected home resources included the [countdown component](https://www.rngdle.com/_next/static/chunks/b3b194df50d4bd76.js) and [UTC date helper](https://www.rngdle.com/_next/static/chunks/1f5594f33de97463.js). These are deployment-specific evidence links.

The visual reference uses a near-black background, large result digits, a pink-to-purple Generate outline, dark cards and colored badge pills. The native shell uses a matching dark theme and a bottom navigation bar. The first-party page supplies the exact rarity palette, EP cards and reveal effects.

## Android 0.1.1 regression investigation

Version 0.1.0 began navigation before AndroidView attached to the activity. Its page-finished callback treated a loaded document as rendered content and only surfaced selected HTTP failures. Its paste dialog closed when WebView accepted a URL, without observing the result of authentication. These are confirmed code defects. The device-specific rendering symptom still needs an Android reproduction.

A signed-out mobile Chrome session loaded the home, leaderboard, and friends routes. Friends rendered its sign-in gate. The profile route redirected to the home screen. The app's form opener and observer detected the site's original email field and Send Magic Link button. An empty main document stayed in the loading state. No email was requested during the browser check.

Both hosts returned HTTP 404 for the Digital Asset Links file after following the apex redirect to the canonical host. The app uses an explicit clipboard action and preserves the copied HTTPS link's path, query, and fragment. The exact callback format, server cookie issuance, and session survival across a device restart require a live link and an Android test.

## Implementation stages

1. Establish a single Android WebView for Play, Rankings, Friends and Profile. Keep first-party storage across navigation and process restarts. Compose owns Android navigation, retry states and companion settings.
2. Verify the magic-link path and expired sessions on a physical device with an authorized account. Exercise a deliberate roll, history, collection, profile edits, friend requests and Party where available.
3. Schedule native notifications with DataStore, WorkManager and optional AlarmManager access. Store a local UTC-day observation for repeats. A roll elsewhere stops repeats once the site displays the updated countdown in this app. The app has no background account access.
4. Build and sign on GitHub Actions. Publish a versioned APK as a GitHub Release. Compare its versionCode and validate the downloaded APK before handing it to the Android installer.
5. Consider Room and native account screens when a supported read contract exists. Scope cached account data to its identity and expire it on sign-out. Until then, WebView provides the site's complete data and storage.
