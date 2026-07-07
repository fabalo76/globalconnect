package com.uic.uicpaymentapp.profile

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.navigation.dst_SystemSettings

fun NavGraphBuilder.settingNavGraph(
    navController: NavController,
) {
    navigation(startDestination = SettingsNav.MAIN_ROUTE, route = dst_SystemSettings.route) {
        composable(SettingsNav.MAIN_ROUTE) {
            val context = LocalContext.current
            val sharedPreferences = context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
            SystemSettingsScreen(
                navigateToProfile = { navController.navigate(SettingsNav.PROFILE_ROUTE) },
                navigateToAbout = { navController.navigate(SettingsNav.ABOUT_ROUTE) },
                navigateToLanguage = { navController.navigate(SettingsNav.LANGUAGE_ROUTE) },
                navigateToDeviceSettings = { navController.navigate(SettingsNav.DEVICE_ROUTE) },
                navigateToPassword = { navController.navigate(SettingsNav.PASSWORD_ROUTE) },
                navigateToCommandTimeout = { navController.navigate(SettingsNav.CMD_TIMEOUT_ROUTE) },
                sharedPreferences = sharedPreferences,
            )
        }
        composable(SettingsNav.PROFILE_ROUTE) {
            ProfileScreen(
                onBackPressed = { navController.popBackStack() }
            )
        }

        composable(SettingsNav.ABOUT_ROUTE) {
            AboutScreen(previousScreenName = "Settings",
                onPressBackButton = { navController.popBackStack() })
        }

        composable(SettingsNav.LANGUAGE_ROUTE) {
//            EditTextSettingScreen(
//                systemSettingType = SystemSettingType.Language,
//                previousScreenName = stringResource(id = R.string.setting),
//                onPressBackButton = { navController.popBackStack() })

            val context = LocalContext.current
            val sharedPreferences = context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
            LanguageScreen(
                context = context,
                sharedPreferences = sharedPreferences,
                systemSettingType = SystemSettingType.Language,
                previousScreenName = stringResource(id = R.string.setting),
                onPressBackButton = { navController.popBackStack() })
        }

        composable(SettingsNav.DEVICE_ROUTE) {
            DeviceModeScreen(previousScreenName = stringResource(id = R.string.setting),
                onPressBackButton = { navController.popBackStack() },
                onPressTipSettings = { navController.navigate(SettingsNav.TIPPING_ROUTE) },
                onPressBatchSettings = { navController.navigate(SettingsNav.BATCH_SETTINGS_ROUTE)})
        }

        composable(SettingsNav.CMD_TIMEOUT_ROUTE) {
            EditTextSettingScreen(
                systemSettingType = SystemSettingType.CmdTout,
                previousScreenName = stringResource(id = R.string.setting),
                onPressBackButton = { navController.popBackStack() })
        }

        composable(SettingsNav.TIPPING_ROUTE) {
            TipSettingsScreen(
                onBackPressed = { navController.popBackStack() },
                previousScreen = stringResource(id = R.string.setting_device_mode),
            )
        }

        composable(SettingsNav.PASSWORD_ROUTE) {
            EditTextSettingScreen(
                systemSettingType = SystemSettingType.Password,
                previousScreenName = stringResource(id = R.string.setting),
                onPressBackButton = { navController.popBackStack() })
        }

        composable(SettingsNav.BATCH_SETTINGS_ROUTE) {
            BatchSettingsScreen(
                onBackPressed = { navController.popBackStack() }, previousScreen = stringResource(
                    id = R.string.setting_device_mode
                )
            )
        }
    }
}

object SettingsNav {
    const val MAIN_ROUTE = "main"
    const val PROFILE_ROUTE = "profile"
    const val ABOUT_ROUTE = "about"
    const val LANGUAGE_ROUTE = "language"
    const val DEVICE_ROUTE = "device"
    const val TIPPING_ROUTE = "tip_settings"
    const val CMD_TIMEOUT_ROUTE = "cmd_timeout"
    const val PASSWORD_ROUTE = "password_setting"
    const val BATCH_SETTINGS_ROUTE = "batch_settings"
}
