// SPDX-License-Identifier: GPL-3.0-or-later

package org.codeberg.aimapp.utils.mounts

/** kernel mount table */
const val PROC_MOUNTS = "/proc/mounts"

/** uid:gid of the media_rw user that owns bind dirs and mount points */
const val MEDIA_RW_OWNERSHIP = "1023:1023"

/** uid:gid applied when resetting ownership back to system defaults */
const val SYSTEM_OWNERSHIP = "1000:1000"

/** default octal mode for bind dirs and mount points created for app access */
const val DEFAULT_DIR_MODE = "775"

/** SELinux context applied to bind mounts so regular apps can access them */
const val MEDIA_RW_SECONTEXT = "u:object_r:media_rw_data_file:s0"

/** fallback SELinux context used when the parent dir's context cannot be parsed */
const val APP_DATA_SECONTEXT = "u:object_r:app_data_file:s0"
