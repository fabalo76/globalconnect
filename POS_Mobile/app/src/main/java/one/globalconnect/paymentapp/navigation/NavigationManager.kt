package one.globalconnect.paymentapp.navigation

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import one.globalconnect.paymentapp.BuildConfig
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.security.TerminalPasswordPolicy

/**
 * Centralizes navigation rules that were previously hard coded inside [UICApp].
 *
 * By keeping the behaviour (password gating, stack clearing, bottom bar visibility)
 * in a single place we avoid duplicating conditionals across the UI layer and make
 * it easier to reason about the allowed flows for PCI sensitive operations.
 */
class NavigationManager(
    private val navController: NavHostController,
) {

    var currentTab : UICDestination  by mutableStateOf(dst_Sale)
        private set

    private val passwordProtectedDestinations = setOf(
        dst_Reports,
        dst_ReportTotals,
        dst_ReportAudit,
        dst_ReportReprintLast,
        dst_ReportReprintSettlement,
        dst_EndOfDay,
        dst_SystemSettings,
        dst_Refund,
    )

    private val resetStackDestinations = setOf(
        dst_Sale,
        dst_Cash,
        dst_Payment,
        dst_LoyaltySale,
        dst_QuotaSale,
        dst_ExtrasSale,
        dst_CheckIn,
        dst_CheckOut,
        dst_NewTransaction,
        dst_MoreMenu,
    )

    private val hiddenBottomBarRoutes = buildSet {
        add("CardTransaction")
        if (BuildConfig.BRANDING_ANIMATION_FULL_SCREEN) add("BrandingAnimation")
        add("TransactionFinished")
        add("TipScreen")
        add("PostTipScreen")
        add("Signature")
        add(dst_ApplicationInfo.route)
    }

    fun onDestinationSelected(destination: UICDestination) {
        currentTab = resolveSelectedTab(destination)
        val targetRoute = buildTargetRoute(destination)
        Log.i(TAG, "Navigating to ${destination.route} (resolved route: $targetRoute)")
        navController.navigate(targetRoute) {
            popUpTo(dst_Sale.route) {
                inclusive = destination in resetStackDestinations
            }
            launchSingleTop = true
            if (destination in navBarScreens) {
                restoreState = true
            }
        }
    }

    fun shouldShowBottomBar(currentDestination: NavDestination?): Boolean {
        val routeName = currentDestination?.route?.toBaseRoute() ?: return true
        return routeName !in hiddenBottomBarRoutes
    }

    private fun buildTargetRoute(destination: UICDestination): String {
        val terminal = GlobalConnectPaymentApplication.instanceOrNull
            ?.tmsDatabase
            ?.Terminal
            ?.firstOrNull()
        val action = TerminalPasswordPolicy.actionForDestination(destination.route)
        return if (
            destination in passwordProtectedDestinations &&
            TerminalPasswordPolicy.requiresPassword(terminal, action)
        ) {
            PASSWORD_ROUTE_PREFIX + Uri.encode(destination.route)
        } else {
            destination.route
        }
    }

    private fun resolveSelectedTab(destination: UICDestination): UICDestination {
        return when (destination) {
            dst_Sale -> dst_Sale
            dst_Transactions -> dst_Transactions
            dst_Refund,
            dst_Cash,
            dst_LoyaltySale,
            dst_QuotaSale,
            dst_ExtrasSale,
            dst_Payment,
            dst_CheckIn,
            dst_CheckOut,
            dst_NewTransaction -> dst_NewTransaction
            dst_Reports,
            dst_EndOfDay,
            dst_CheckInReport,
            dst_SystemSettings,
            dst_MoreMenu -> dst_MoreMenu
            else -> dst_MoreMenu
        }
    }

    private fun String.toBaseRoute(): String =
        substringBefore("?")
            .substringBefore("/")

    private companion object {
        private const val PASSWORD_ROUTE_PREFIX = "Password/"
        private const val TAG = "NavigationManager"
    }
}
