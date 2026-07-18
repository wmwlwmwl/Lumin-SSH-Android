# Lumin SSH (Android)

**Android client** of the Lumin SSH suite.  
Shares servers, credentials, quick commands, proxies, and cloud sync with the desktop app.

| | |
|--|--|
| **Product** | Lumin SSH |
| **This repo** | Android |
| **Desktop** | [wmwlwmwl/Lumin-SSH](https://github.com/wmwlwmwl/Lumin-SSH) |
| **Version** | See `versionName` in [app/build.gradle.kts](app/build.gradle.kts) |

---

## Two-repo model

| Repository | Platform | Release cadence |
|------------|----------|-----------------|
| [Lumin-SSH](https://github.com/wmwlwmwl/Lumin-SSH) | Desktop | Independent |
| **This repo** | Android | Independent |

Desktop can ship without an Android release, and the reverse.  
Only **breaking sync/backup format** changes require coordinated notes (e.g. “requires desktop ≥ 1.x”).

See [VERSIONING.md](VERSIONING.md).

---

## Build

JDK 17 + Android SDK (compileSdk 35).

```bash
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:assembleRelease
```

---

## License

See [LICENSE](LICENSE) (**Lumin SSH Android Source License 1.1**).

| | |
|--|--|
| **Allowed** | Non-commercial use, study, research, public forks (keep license/attribution; redistribution must be **source-available**) |
| **Not allowed** | Commercial use (sale, paid distribution, commercial embedding, for-profit services, etc.; see LICENSE) |
| **Not allowed** | Public distribution only in encrypted/packed/heavily obfuscated form without corresponding readable source |

**Scope:** This license covers **original code in this repo**. Third-party components (AndroidX, Termux terminal, OkHttp, JSch, …) remain under **their own licenses**.

This is a custom license, **not legal advice**. Consult a lawyer for store distribution or commercial edge cases.
