# AIM

<img src="assets/aim_logo.svg" alt="get it? aim? it's a pun of a bullseye cause yk it looks like a target but also a disc image uhm heh sorry" width="72">

AIM is an Android app for mounting `.img` files on your phone. Supports `ext4`, `exfat` and `fat32` filesystems.

It is installed as a normal app and root access is required.

<img src="assets/screenshots/dark.webp" alt="dark screenshot" width="200" />

## Features

* Supports Android 11+
* Supports mounting multiple images at once
  - Including mixing and matching different filesystems
* Formatting (`exFAT`, `ext4`)
* Multi partition support
* Mounting on internal storage (bind mount) and in documents provider

Can be paired with [MSD](https://github.com/chenxiaolong/MSD)

## Limitations

* Must have root access and busybox
* Sparse (dynamic) images are not supported
* Only local files are supported
  - Must be on internal storage or sd card.
* Device must have filesystem support (most devices do)
  - Run `cat /proc/filesystems` in a rooted shell to check if they exist.
* Only tested on AOSP based ROMs
* POSIX-based filesystems reset to 1000:1000, only use images with a single permission scheme

> [!CAUTION]
> This application runs shell commands as root (`busybox`, `mount`, etc) and processes arbitrary user input. This introduces security risks. While protective measures have been implemented to prevent potential issues, no guarantees can be made. Use at your own risk.

Note: if you use KernelSU or similar root solutions and you encounter issues, please set AIM to use the global mount namespace.

## Usage

1. Download latest version from the [releases page](https://github.com/jeeneo/aim/releases).

2. Grant root permissions

3. Add one or more images and set mount options

4. Apply the settings

5. That's it!

AIM does not need to run in the background. Once configured, the mounted images remain available until they're explicitly disabled or the device is rebooted.

> [!IMPORTANT]
> Before uninstalling the app, check and unmount all images to prevent unexpected issues or data loss

## Info

ISO file types are supported, but the filesystem `ISO9660` is hardly supported on modern kernels, and only will succeed if the ISO is formatted as any of the supported filesystems. The app will however, attempt to mount and will fail if your device does not support it.

## License

AIM is licensed under GPL-3.0-or-later. Please see [`LICENSE`](./LICENSE) for the full license text.
