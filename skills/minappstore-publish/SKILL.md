---
name: minappstore-publish
description: Prepare signed Android APKs and store-manifest.json, publish GitHub Releases, and register or update apps in MinAppStore. Supports English and Korean requests for 앱 등록, 릴리스 게시, 업데이트 and 동기화 진단. Not a general server deployment or MoaLog-specific build skill.
---

# Publish Android apps to MinAppStore / 앱 등록·업데이트

Respond in the user's requested language; otherwise match the language of their request. Use the [English publishing guide](references/publishing-guide.en.md) for English requests and the [한국어 등록 가이드](references/publishing-guide.md) for Korean requests. Read only the needed language version. Both describe the same workflow.

The shared APK inspector is [assets/store_manifest.py](assets/store_manifest.py). Choose the [English metadata example](assets/store-metadata.en.json) or [Korean example](assets/store-metadata.json), then replace its sample content with the app's actual information. Preserve any publisher-provided language instead of translating app metadata without a request. Paths are relative to this SKILL.md.

## Operating contract

- Origin: `https://minappstore-api.eum-user.workers.dev`; admin `/admin`; public catalog `/api/catalog`.
- Allowed owners: `minia1234`, `AIProject-k`. Only public distribution repositories from these accounts are currently supported.
- Central runner: `store-sync.yml` in public `AIProject-k/ApplicationTest`. It runs daily at 03:00 KST, on releases published in that runner repository, and on manual dispatch. Publishing in a different app repository does not immediately trigger it.
- Keep the free architecture: Workers Free + SQLite Durable Object metadata, with APKs in public GitHub Releases. Do not enable R2, paid plans or higher spending limits.
- The admin token belongs only in the admin session and existing central runner secret. Do not copy it into each app repository or expose it in chat, source, URLs or logs.

## Workflow

1. Inspect the target project's instructions, remote, default branch, build configuration and existing releases. Distinguish the source repository from the public distribution repository. Ask only if the target app or repository cannot be established.
2. Follow the project's actual Android build process; do not guess Gradle, Flutter, Unity or Godot output paths. Preserve packageName and signing identity and increase versionCode. If the signing setup is missing, request its local configuration, never the key contents. Do not present an unsigned/debug APK as an official update.
3. Prepare the signed APK and actual display metadata. Generate the manifest using the bundled aapt/apksigner inspector. Never invent package, version, hash, size or signing information, or publish the sample metadata unchanged.
4. For an existing catalog entry, verify the package and signer match and no existing versionCode has different APK bytes. Inspect existing release assets and digests when present; a first registration needs no previous release. Do not bypass failed checks.
5. If publishing is authorized by the user's request, upload the APK and exactly `store-manifest.json` to the specified public release. Drafts and prereleases are ignored. A preparation or guide request alone does not authorize publishing. Do not ask again for authorization already given.
6. Link and enable only the target repository in the admin page, preserving all other choices. If the new repository is absent from the cache, run central sync once to refresh inventory before linking.
7. When immediate synchronization is requested, reuse the existing central workflow and inspect its result. Do not retry blindly: after a 409 wait for the current operation and retry once with a fresh snapshot. Stop on signer/hash failures and explain the cause. Never clear locks, pins or release assets to make validation pass.
8. Verify repository, packageName and latest versionCode in the catalog. Report GitHub upload, Actions success, catalog publication and actual device installation as separate evidence. Use a connected device only within the requested verification scope.

## Preserve scope and data

- Work on each repository's default branch. Do not create task branches/worktrees, overwrite dirty work or bulk-stage files.
- Keep private source repositories private. Publish only authorized APK/manifest files to the specified public distribution repository. Creating a repository, extending allowed owners or copying other app source requires a corresponding request.
- Reuse the central runner; do not replicate its workflow and admin token into every app repository.
- Never commit keystores, passwords, environment files, databases or generated APKs. Do not move published tags or overwrite existing release assets.
- Explain the current limitation and scope a separate change for owners outside the allowlist, APKs over 512 MiB, or signing-key replacement.

## Example requests / 사용 예

`$minappstore-publish Build this Android project, publish its signed APK and manifest to AIProject-k/MyApp, and connect it to MinAppStore.`

`$minappstore-publish Prepare the APK and manifest only. Do not publish this time.`

`$minappstore-publish I published a release but the app is missing. Investigate without changing anything.`

`$minappstore-publish 이 프로젝트를 확인하고 AIProject-k/대상저장소에 APK와 manifest를 릴리스해서 MinAppStore에 연동해줘.`
