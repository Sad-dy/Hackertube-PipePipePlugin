# Hackertube-PipePipePlugin

This plugin adds support for browsing [HackerTube](https://hackertube.org) — a curated YouTube list/frontend on Hacker News — inside [PipePipe](https://github.com/InfinityLoop1308/PipePipe) (`org.hackertube.pipepipe`, Plugin API `1.0`).

> **Note**
> This is a personal project for my own use. It was developed with AI assistance, with high-level direction and guidance from me. Not affiliated with HackerTube, Hacker News, or PipePipe.

## Overview

- **Browse & Search:** Home personalized feed, Longs, Categories (16 topic areas), Curated Playlists, Hidden Gems, Recently Recommended, Top Ranked, and filtered Search (enter `*` in Search to browse with filter chips).
- **HN Context & Playback:** Stream pages show HackerTube summaries, stats, related videos, and labeled Hacker News mentions as comments while delegating playback to PipePipe's YouTube extractor (or optional Direct YouTube mode).
- **Local Personalization (On-Device):** Optionally reads PipePipe's local watch history and configurable Likes/Dislikes playlists (`HackerTube Likes`, `HackerTube Dislikes`) on-device to exclude watched items and rank Home recommendations.
- **Offline Snapshot Fallback:** Periodically caches `hackertube-data.zip` and playlist metadata locally (default every `168` hours, `0` to disable) so search, browse, comments, and recommendations continue working offline.

Settings can be configured inside PipePipe under the plugin's preferences (Base URL, Direct YouTube mode, neutral thumbnails, personalization toggles, playlist names, news/0-rec filters, offline mode, and sync interval).

## Build & Setup

Requires **JDK 25** and Android SDK (`platforms;android-36`, `build-tools;37.0.0`).

```bash
./gradlew test
./gradlew :tools:genKey -PpluginSigningKey=signing.key
./gradlew :tools:index -PpluginSigningKey=signing.key
```

For GitHub Actions publishing (`.github/workflows/release.yml`), store the contents of `signing.key` in repository secret `PIPEPIPE_SIGNING_KEY` and push to `main` (or run `workflow_dispatch`), then add the repository URL in PipePipe:

```text
https://github.com/<owner>/<repo>/releases/download/repo/index.json
```

## License

GPLv3 — see [LICENSE](LICENSE).
