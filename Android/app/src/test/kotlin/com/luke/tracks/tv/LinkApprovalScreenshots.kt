package com.luke.tracks.tv

import com.luke.tracks.ScreenshotTest
import com.luke.tracks.ui.link.Approval
import com.luke.tracks.ui.link.LinkApprovalContent
import com.luke.tracks.ui.tv.link.LinkProblem
import com.luke.tracks.ui.tv.link.LinkRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import org.junit.Test

/** The phone's side of linking a TV, in each state. Images: link-approve-*-light.png and -dark.png. */
class LinkApprovalScreenshots(dark: Boolean) : ScreenshotTest(dark) {
    @Composable
    private fun Phone(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalNavigator provides Navigator()) { content() }
    }

    private val request = LinkRequest("Living Room TV", "android_tv", nearby = true)

    @Composable
    private fun Shot(code: String?, approval: Approval, server: String? = "Living Room Server") =
        Phone { LinkApprovalContent(server, code, approval, {}, {}, {}, {}, {}) }

    @Test fun asking() = snap("link-approve-asking") { Shot("Q7RM-4X2K", Approval.Asking(request)) }

    @Test fun farAway() = snap("link-approve-far") { Shot("Q7RM-4X2K", Approval.Asking(request.copy(nearby = false))) }

    @Test fun lookingUp() = snap("link-approve-looking") { Shot("Q7RM-4X2K", Approval.LookingUp) }

    @Test fun typed() = snap("link-approve-typed") { Shot(null, Approval.Typing) }

    @Test fun linked() = snap("link-approve-linked") { Shot("Q7RM-4X2K", Approval.Linked("Living Room TV")) }

    @Test fun denied() = snap("link-approve-denied") { Shot("Q7RM-4X2K", Approval.Denied("Living Room TV")) }

    @Test fun expired() = snap("link-approve-expired") { Shot("Q7RM-4X2K", Approval.Failed(LinkProblem.CodeExpired)) }

    @Test fun notFound() = snap("link-approve-not-found") { Shot("Q7RM-4X2K", Approval.Failed(LinkProblem.NotFound)) }

    @Test fun notPaired() = snap("link-approve-not-paired") { Shot("Q7RM-4X2K", Approval.LookingUp, server = null) }
}
