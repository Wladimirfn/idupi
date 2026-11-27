package com.idupi.app.data.phone.shell

/**
 * Android package-name validation for the typed shell surface.
 *
 * Android package ids are dot-separated segments; every segment starts with a
 * letter and continues with letters, digits or underscores, and there are at
 * least two segments. The rule is enforced here before a package name can
 * reach `pm`/`cmd` on the helper side.
 */
object PackageNames {

    private val PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun isValid(pkg: String?): Boolean = pkg != null && PATTERN.matches(pkg)
}
