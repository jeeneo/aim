Android image mounter

Attempts to mount `.img` and `.iso`* files on Android, supports `fat/fat32`, `exFAT`, and `ext4` filesystems only

Root and busybox is required

> [!CAUTION]
> This app runs as root and because of arbitrary user input, can have security risks. Measures have been put in place to prevent such occurances, but I cannot 100% guarantee no bugs exist. Use at own risk.

Mounts are a best-effort and only tested on AOSP roms.

Goal is to be a helper program to [MSD](https://github.com/chenxiaolong/MSD)

*ISO filetypes are supported, but the filesystem `ISO9660` is hardly supported on modern kernels, and only will succeed if the ISO is formatted as any of the supported filesystems. The app will however, attempt to mount and will fail if your device does not support it.
