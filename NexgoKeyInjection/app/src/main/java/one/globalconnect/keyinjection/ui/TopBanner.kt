package one.globalconnect.keyinjection.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.globalconnect.keyinjection.BuildConfig
import one.globalconnect.keyinjection.R
import one.globalconnect.keyinjection.ui.theme.GlobalConnectNavy

/**
 * Displays the Global Connect One logo and the Nexgo key injection title.
 *
 * Author: Fabian Ramirez
 */
@Composable
fun TopBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(androidx.compose.ui.graphics.Color.White),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.global_connect_one_logo),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.height(44.dp)
            )
            Text(
                text = "${stringResource(R.string.key_loader_label)} v${BuildConfig.VERSION_NAME}",
                color = GlobalConnectNavy,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
