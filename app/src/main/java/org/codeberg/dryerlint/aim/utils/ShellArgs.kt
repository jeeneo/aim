/**
 * Copyright (C) 2026 dryerlint <codeberg.org/dryerlint>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.codeberg.dryerlint.aim.utils

internal val VALID_CHMOD_MODES = setOf("775", "664", "777")

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
