package com.uic.uicpaymentapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.ui.theme.color_error
import com.uic.uicpaymentapp.ui.theme.color_primaryBrand
import com.uic.uicpaymentapp.ui.theme.color_secondaryFive
import com.uic.uicpaymentapp.ui.theme.color_secondaryFour
import com.uic.uicpaymentapp.ui.preview.RecordsPreviewData
import com.uic.uicpaymentapp.ui.theme.color_white

data class AcquirerSelectionOption(
    val id: String,
    val title: String,
)

@Composable
fun AcquirerSelectionPanel(
    modifier: Modifier = Modifier,
    headerTitle: String? = null,
    title: String,
    options: List<AcquirerSelectionOption>,
    onOptionSelected: (String) -> Unit,
    onCancel: (() -> Unit)? = null,
    bottomSheetStyle: Boolean = false,
    scrollOptions: Boolean = true,
) {
    val panelShape = if (bottomSheetStyle) {
        RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
    } else {
        RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)
    }
    //modifier.padding(all = 0.dp)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        headerTitle?.takeIf { it.isNotBlank() }?.let {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color_primaryBrand, shape = RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = it,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = color_secondaryFive,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        val titleTopRadius = 32.dp
        val titleShape = RoundedCornerShape(
            topStart = titleTopRadius,
            topEnd = titleTopRadius,
            bottomStart = 0.dp,
            bottomEnd = 0.dp,
        )

        Spacer( modifier = Modifier.padding(vertical = 10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(color_primaryBrand, shape = titleShape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = color_secondaryFive,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = true)
                .clip(panelShape)
                .background(color_secondaryFour),
        ) {

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
            ) {
                val optionContainer: @Composable ColumnScope.() -> Unit = {
                    options.forEach { option ->
                        val containerColor = color_white

                        val contentColor = color_secondaryFive

                        Button(
                            onClick = { onOptionSelected(option.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = containerColor,
                                contentColor = contentColor,
                            ),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = option.title,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 10.dp),
                                )
                            }
                        }
                    }
                }

                if (scrollOptions) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = true)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        optionContainer()
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = true),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        optionContainer()
                    }
                }

                onCancel?.let { cancel ->
                    Button(
                        onClick = cancel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = color_error,
                            contentColor = color_white,
                        ),
                    ) {
                        Text(
                            text = stringResource(R.string.action_cancel),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AcquirerSelectionPanelPreview() {
    AcquirerSelectionPanel(
        headerTitle = "Totals Report",
        title = "Select Acquirer",
        options = RecordsPreviewData.acquirerOptions,
        onOptionSelected = {},
        onCancel = {},
    )
}
