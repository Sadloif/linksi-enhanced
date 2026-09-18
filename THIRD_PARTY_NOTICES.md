# Third-party notices

Linksi's own source is MIT (see [LICENSE](LICENSE)). The published APK also contains the components
below, which keep their own licences. This file exists so that the MIT permission notice travels with
distributions as MIT requires, and so the components that are **not** MIT are stated plainly rather
than left implicit.

## Bundled in the published APK

| Component | Licence | Why it matters |
|---|---|---|
| [`youtubedl-android`](https://github.com/yausername/youtubedl-android) (0.17.3) | **GPL-3.0** | The site engine. Linking it makes the APK a combined work, so a distributed APK carries GPL-3.0 obligations for that component |
| FFmpeg, as bundled by `youtubedl-android` (Termux build) | **GPL-3.0** | Used to merge separate video and audio streams |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) | **Unlicense** | The extractor the engine runs |
| [AndroidX / Jetpack Compose](https://developer.android.com/jetpack) | Apache-2.0 | UI framework |
| [Hilt / Dagger](https://dagger.dev/hilt/) | Apache-2.0 | Dependency injection |
| [Room](https://developer.android.com/training/data-storage/room) | Apache-2.0 | Local database |
| [OkHttp](https://square.github.io/okhttp/) | Apache-2.0 | HTTP client |
| [Coil](https://coil-kt.github.io/coil/) | Apache-2.0 | Image loading |
| [Jsoup](https://jsoup.org/) | MIT | HTML parsing for link metadata |

## What this means in practice

- **Linksi's own code is MIT.** That is what [LICENSE](LICENSE) covers, and it is the licence that applies
  to the source in this repository.
- **A redistributed APK includes GPL-3.0 components.** GPL-3.0 is not permissive in the way MIT is: a
  combined work carries obligations to convey the corresponding source for those components. Anyone
  redistributing the built APK should satisfy them — the upstream projects publish their sources.
- **Private use is unaffected.** Neither licence restricts private modification or private use.

A fuller analysis, including the alternatives considered and their costs, is in
[DEPENDENCY_REVIEW.md](DEPENDENCY_REVIEW.md) and [LICENSE_REVIEW.md](LICENSE_REVIEW.md).
