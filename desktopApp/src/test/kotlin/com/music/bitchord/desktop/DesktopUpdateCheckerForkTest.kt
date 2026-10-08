package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fork builds are stamped "version-yyyymmdd-commit"; the update check has to order them. */
class DesktopUpdateCheckerForkTest {

    @Test
    fun `a later day is newer`() {
        assertTrue(DesktopUpdateChecker.isNewer("1.8-20261010-aaaaaaaa", "1.8-20261008-bbbbbbbb"))
        assertFalse(DesktopUpdateChecker.isNewer("1.8-20261008-bbbbbbbb", "1.8-20261010-aaaaaaaa"))
    }

    @Test
    fun `a later version is newer whatever the day`() {
        assertTrue(DesktopUpdateChecker.isNewer("1.9-20261001-aaaaaaaa", "1.8-20261008-bbbbbbbb"))
    }

    @Test
    fun `the same build is not an update, another build that day is`() {
        assertFalse(DesktopUpdateChecker.isNewer("1.8-20261008-36514f3a", "1.8-20261008-36514f3a"))
        assertTrue(DesktopUpdateChecker.isNewer("1.8-20261008-cbbab8e0", "1.8-20261008-36514f3a"))
    }

    @Test
    fun `a fork build replaces an unstamped one`() {
        assertTrue(DesktopUpdateChecker.isNewer("1.8-20261008-36514f3a", "1.8-beta1"))
    }
}
