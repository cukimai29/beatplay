package com.metrolist.music.premium

import com.metrolist.innertube.models.AccountInfo
import com.metrolist.innertube.models.YouTubeAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun account(
    name: String,
    email: String? = null,
    handle: String? = null,
) = AccountInfo(name = name, email = email, channelHandle = handle, thumbnailUrl = null)

private fun channel(
    name: String,
    byline: String? = null,
    handle: String? = null,
    selected: Boolean = false,
) = YouTubeAccount(
    name = name,
    byline = byline,
    channelHandle = handle,
    thumbnailUrl = null,
    isSelected = selected,
    signinUrl = null,
    pageId = null,
    dataSyncId = null,
)

class AccountIdentityTest {
    @Test
    fun usesAccountMenuEmailWhenPresent() {
        val info = account("Rzkyy", email = " Owner@Gmail.com ", handle = "@rzkyy")
        val accounts = listOf(channel("Rzkyy", byline = "lain@gmail.com", handle = "@rzkyy"))

        assertEquals("owner@gmail.com", resolveAccountEmail(info, accounts))
    }

    @Test
    fun brandChannelBorrowsOwnerEmailByHandle() {
        val info = account("rzkyy ads", email = null, handle = "@rzkyyads")
        val accounts = listOf(
            channel("Rzkyy", byline = "owner@gmail.com", handle = "@rzkyy"),
            channel("rzkyy ads", byline = "Owner@Gmail.com", handle = "rzkyyads"),
        )

        assertEquals("owner@gmail.com", resolveAccountEmail(info, accounts))
    }

    @Test
    fun brandChannelBorrowsOwnerEmailByName() {
        val info = account("rzkyy ads", email = null, handle = null)
        val accounts = listOf(channel("Rzkyy Ads", byline = "owner@gmail.com"))

        assertEquals("owner@gmail.com", resolveAccountEmail(info, accounts))
    }

    @Test
    fun fallsBackToSelectedChannel() {
        val info = account("channel tanpa email", email = null, handle = "@unknown")
        val accounts = listOf(
            channel("Rzkyy", byline = "other@gmail.com", handle = "@rzkyy"),
            channel("rzkyy ads", byline = "owner@gmail.com", handle = "@rzkyyads", selected = true),
        )

        assertEquals("owner@gmail.com", resolveAccountEmail(info, accounts))
    }

    @Test
    fun ignoresNonEmailByline() {
        val info = account("rzkyy ads", email = "", handle = "@rzkyyads")
        val accounts = listOf(channel("rzkyy ads", byline = "rzkyy ads", handle = "@rzkyyads"))

        assertNull(resolveAccountEmail(info, accounts))
    }

    @Test
    fun nullHandleDoesNotMatchEveryChannel() {
        val info = account("rzkyy ads", email = null, handle = null)
        val accounts = listOf(channel("Rzkyy", byline = "owner@gmail.com", handle = null))

        assertNull(resolveAccountEmail(info, accounts))
    }

    @Test
    fun normalizesChannelHandles() {
        assertEquals("rzkyyads", normalizeChannelHandle(" @RzkyyAds "))
        assertEquals("rzkyyads", normalizeChannelHandle("rzkyyads"))
        assertNull(normalizeChannelHandle(""))
        assertNull(normalizeChannelHandle(null))
        assertNull(normalizeChannelHandle("  @ "))
    }
}
