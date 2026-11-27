package com.luke.tracks.ui.navigation

interface AppNavigation {
    fun openNowPlaying()
    fun openSearch(query: String)

    /**
     * A tracks://link request to approve a TV's link to the Trackside server [serverId], showing
     * [userCode] ("Q7RM-4X2K"). Both are checked before this is called. Phones only.
     */
    fun openLinkApproval(serverId: String, userCode: String) {}
}
