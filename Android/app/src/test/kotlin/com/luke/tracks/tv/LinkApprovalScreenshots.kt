package com.luke.tracks.tv

import com.luke.tracks.ScreenshotTest
import com.luke.tracks.ui.link.Approval
import com.luke.tracks.ui.link.LinkApprovalContent
import com.luke.tracks.ui.tv.link.LinkProblem
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

    @Test fun fromTheQr() = snap("link-approve-asking") {
        Phone { LinkApprovalContent("Living Room Server", "Q7RM-4X2K", Approval.Asking, {}, {}) }
    }

    @Test fun typed() = snap("link-approve-typed") {
        Phone { LinkApprovalContent("Living Room Server", null, Approval.Asking, {}, {}) }
    }

    @Test fun approving() = snap("link-approve-approving") {
        Phone { LinkApprovalContent("Living Room Server", "Q7RM-4X2K", Approval.Approving, {}, {}) }
    }

    @Test fun linked() = snap("link-approve-linked") {
        Phone { LinkApprovalContent("Living Room Server", "Q7RM-4X2K", Approval.Linked("Living Room TV"), {}, {}) }
    }

    @Test fun failed() = snap("link-approve-failed") {
        Phone { LinkApprovalContent("Living Room Server", "Q7RM-4X2K", Approval.Failed(LinkProblem.NeedsUpdate), {}, {}) }
    }

    @Test fun notPaired() = snap("link-approve-not-paired") {
        Phone { LinkApprovalContent(null, "Q7RM-4X2K", Approval.Asking, {}, {}) }
    }
}
