# 墨映 Android 发布约定

从 `1.9.40` 开始，每个新版本都发布到 `Irolumn/moying-android`。每版交付包含 GitHub `main` 上的源码、指向该版本源码提交的 `v<versionName>` 标签，以及同一标签下的正式 Release APK。核对 GitHub 上的标签和 APK 后，这一版才算交付完成。

1. 修改 `app/build.gradle.kts` 的 `versionName`，同时递增 `versionCode`。发布标签使用 `v<versionName>`，附件使用 `moying-<versionName>.apk`。
2. 用包含本版全部源码改动的提交构建。Windows 上运行 `./build-debug.ps1 -Task ':app:assembleRelease' -SkipInstall`，产物位于 `app/build/outputs/apk/release/app-release.apk`。确保构建用的工作区与该提交一致。
3. 用 Android SDK 的 `aapt dump badging` 核对 APK 的应用 ID `com.moying.app`、`versionName` 和 `versionCode`。用 `apksigner verify --print-certs` 校验签名，证书 SHA-256 应与上一正式版本一致。当前分发证书指纹为 `6d4af550622f76eca753da11f804b01ddc4dde125e3370b467d81773defc0107`。沿用发布机的现有签名，保证已有安装可以覆盖升级。
4. 将源码提交和版本标签推送到 GitHub，并用 `git ls-remote` 核对远端标签指向本次构建的提交。创建 Release 草稿并上传该 APK，在发布说明中记录本次改动、`versionCode` 和 APK 的 SHA-256。
5. 核对 GitHub 附件的文件名、文件大小和 SHA-256 与本地 APK 一致，再发布草稿。发布后验证正式 Release 的标签和下载附件可用。重新执行发布时复用同版本的提交和草稿，已经公开的版本用新版本号交付后续修改。
