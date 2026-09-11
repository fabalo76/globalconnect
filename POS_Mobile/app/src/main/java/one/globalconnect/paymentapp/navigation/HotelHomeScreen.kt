package one.globalconnect.paymentapp.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AddCard
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.ui.theme.color_secondaryFive
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.ui.theme.color_white
import one.globalconnect.paymentapp.ui.theme.menuButtonShape
import one.globalconnect.paymentapp.utils.SoundEffect
import one.globalconnect.paymentapp.utils.SoundManager
import one.globalconnect.tms.paymentapp.TMSDATA

@Composable
fun HotelHomeScreen(
    onDestinationSelected: (UICDestination) -> Unit,
) {
    var showNotAllowed by rememberSaveable { mutableStateOf(false) }

    fun select(destination: UICDestination) {
        SoundManager.play(SoundEffect.KEY_TICK)
        onDestinationSelected(destination)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(color_secondaryThree)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HotelHomeButton(
            text = stringResource(id = R.string.trans_sale),
            icon = Icons.Filled.CreditCard,
            onClick = { select(dst_SaleTransaction) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            HotelHomeButton(
                text = stringResource(id = R.string.trans_checkin),
                icon = Icons.AutoMirrored.Filled.Login,
                onClick = { select(dst_CheckIn) },
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f),
            )
            HotelHomeButton(
                text = stringResource(id = R.string.trans_checkout),
                icon = Icons.AutoMirrored.Filled.Logout,
                onClick = { select(dst_CheckOut) },
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            HotelHomeButton(
                text = stringResource(id = R.string.hotel_check_in_report_title),
                icon = Icons.AutoMirrored.Filled.List,
                onClick = { select(dst_OpenCheckIns) },
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f),
            )
            HotelHomeButton(
                text = stringResource(id = R.string.hotel_home_incremental_check_in),
                icon = Icons.Filled.AddCard,
                onClick = { select(dst_IncrementalCheckIn) },
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            HotelHomeButton(
                text = stringResource(id = R.string.hotel_home_delay_charge),
                icon = Icons.Filled.Schedule,
                onClick = {
                    SoundManager.play(SoundEffect.KEY_INVALID)
                    showNotAllowed = true
                },
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f),
            )
            Spacer(modifier = Modifier.weight(1f))
        }
    }

    if (showNotAllowed) {
        AlertDialog(
            onDismissRequest = { showNotAllowed = false },
            text = {
                Text(
                    text = stringResource(id = R.string.function_not_allowed),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { showNotAllowed = false }) {
                    Text(text = stringResource(id = R.string.ok))
                }
            },
        )
    }
}

@Composable
private fun HotelHomeButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .padding(2.dp)
            .shadow(4.dp, shape = menuButtonShape),
        shape = menuButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = color_white,
            contentColor = color_secondaryFive,
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                modifier = Modifier.size(34.dp),
            )
            Text(
                text = text,
                modifier = Modifier.padding(top = 8.dp),
                textAlign = TextAlign.Center,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
            )
        }
    }
}

internal fun shouldUseHotelHome(tmsDatabase: TMSDATA): Boolean =
    tmsDatabase.Terminal.firstOrNull()?.enableCheckInOut == true &&
        tmsDatabase.Acquirer.any { it.enableCheckInOut }
