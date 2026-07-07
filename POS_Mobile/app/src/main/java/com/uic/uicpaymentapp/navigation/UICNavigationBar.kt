package com.uic.uicpaymentapp.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.uic.uicpaymentapp.ui.theme.color_White40
import com.uic.uicpaymentapp.ui.theme.color_primaryBrand
import com.uic.uicpaymentapp.ui.theme.color_secondaryFive
import com.uic.uicpaymentapp.utils.SoundEffect
import com.uic.uicpaymentapp.utils.SoundManager
import com.uic.uicpaymentapp.ui.theme.UICMockAppTheme

const val NAVBARHEIGHT = 64

@Composable
fun UICNavigationBar(
    allScreens: List<UICDestination>,
    onTabSelected: (UICDestination) -> Unit,
    currentTab: UICDestination,
    visible: Boolean
) {

    val context = LocalContext.current
    val swDp = LocalConfiguration.current.smallestScreenWidthDp
    val navBarHeight = if (swDp >= 480) 56.dp else NAVBARHEIGHT.dp

    if (visible) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(navBarHeight)
                .background(MaterialTheme.colorScheme.background)
        ) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .height(1.dp)
            )
            Row(
                modifier = Modifier.selectableGroup().weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                allScreens.forEach { screen ->
                    UICTab(
                        modifier = Modifier.weight(1f),
                        text = screen.getLabel(context),
                        selectedIcon = screen.icon,
                        onSelected = {
                            SoundManager.play(SoundEffect.KEY_SPACEBAR) // 🔊 Play Click Sound
                            onTabSelected(screen)
                         },
                        currentTab == screen
                    )
                }
            }
        }
    } else {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .background(Color.Transparent)
        ) {}
    }
}

@Composable
private fun UICTab(
    modifier: Modifier,
    text: String,
    selectedIcon: ImageVector,
    onSelected: () -> Unit,
    selected: Boolean
) {
    val isN62 = LocalConfiguration.current.smallestScreenWidthDp >= 480
    val verticalContentPadding = if (isN62) 2.dp else ButtonDefaults.ContentPadding.calculateTopPadding()
    val iconBottomPadding = if (isN62) 0.dp else 4.dp
    val contentPadding = PaddingValues(start = 0.dp, end = 0.dp, top = verticalContentPadding, bottom = verticalContentPadding)

    if (selected) {
        val rounded = when (selectedIcon) {
            Icons.AutoMirrored.Filled.LibraryBooks -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
            Icons.Filled.Dashboard -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
            Icons.Filled.Menu -> RoundedCornerShape(topStart = 24.dp, topEnd = 0.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
            else -> RoundedCornerShape(topStart = 0.dp, topEnd = 24.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
        }
        Button(
            modifier = modifier,
            onClick = onSelected,
            colors = ButtonDefaults.buttonColors(
                containerColor = color_primaryBrand,
                contentColor = color_secondaryFive,
            ),
            contentPadding = contentPadding,
            shape = rounded
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(imageVector = selectedIcon, contentDescription = text, modifier = Modifier.padding(bottom = iconBottomPadding))
                Text(text = text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    } else {
        Button(
            modifier = modifier,
            onClick = onSelected,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = color_secondaryFive
            ),
            contentPadding = contentPadding
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(imageVector = selectedIcon, contentDescription = text, modifier = Modifier.padding(bottom = iconBottomPadding))
                Text(text = text, fontSize = 14.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(
    title: (@Composable () -> Unit)?,
    navigationIcon: (@Composable () -> Unit)?,
    actions: (@Composable RowScope.() -> Unit)?,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    CenterAlignedTopAppBar(
        title = title ?: {},
        modifier = if (modifier == Modifier) Modifier.padding(top = 20.dp) else modifier,
        navigationIcon = navigationIcon ?: {},
        actions = actions ?: {},
        windowInsets = windowInsets,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = color_White40),
    )
}

@Preview(showBackground = true)
@Composable
private fun UICNavigationBarPreview() {
    UICMockAppTheme {
        UICNavigationBar(
            allScreens = listOf(dst_Sale, dst_Transactions, dst_NewTransaction, dst_MoreMenu),
            onTabSelected = {},
            currentTab = dst_NewTransaction,
            visible = true,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun UICTabSelectedPreview() {
    UICMockAppTheme {
        Row(modifier = Modifier.fillMaxWidth()) {
            UICTab(
                modifier = Modifier.weight(1f),
                text = "Reports",
                selectedIcon = Icons.AutoMirrored.Filled.LibraryBooks,
                onSelected = {},
                selected = true,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun UICTabUnselectedPreview() {
    UICMockAppTheme {
        Row(modifier = Modifier.fillMaxWidth()) {
            UICTab(
                modifier = Modifier.weight(1f),
                text = "Dashboard",
                selectedIcon = Icons.Filled.Dashboard,
                onSelected = {},
                selected = false,
            )
        }
    }
}
