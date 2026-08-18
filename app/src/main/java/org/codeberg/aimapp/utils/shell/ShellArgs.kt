// SPDX-License-Identifier: GPL-3.0-or-later

package org.codeberg.aimapp.utils.shell

import org.codeberg.aimapp.utils.paths.isValidPath

fun pathArg(path: String): ShellArg {
    require(isValidPath(path)) { "Invalid path: $path" }
    return ShellArg.of(path)
}

fun numArg(n: Long): ShellArg = ShellArg.literal(n.toString())
fun numArg(n: Int): ShellArg = ShellArg.literal(n.toString())
fun enumArg(value: String, allowed: Set<String>): ShellArg {
    require(value in allowed) { "Value '$value' not in allowed set $allowed" }
    return ShellArg.of(value)
}

fun mountOptsArg(opts: String): ShellArg {
    require(opts.matches(Regex("^[a-zA-Z0-9_=,.:]+$"))) { "Invalid mount opts: $opts" }
    return ShellArg.of(opts)
}

fun secontextArg(ctx: String): ShellArg {
    require(ctx.matches(Regex("^[a-zA-Z0-9_:,.]+$")) && ':' in ctx) { "Invalid SELinux context: $ctx" }
    return ShellArg.of(ctx)
}

fun loopDevArg(dev: String): ShellArg {
    require(dev.length <= 32) { "Loop device path too long: ${dev.length}" }
    require(dev.matches(Regex("^/dev/(block/)?loop\\d+$"))) { "Invalid loop device: $dev" }
    return ShellArg.of(dev)
}
