package com.uic.uicpaymentapp.signature

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uic.uicpaymentapp.AppViewModelProvider
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.transaction.Transaction
import com.uic.uicpaymentapp.transaction.toStringForUsers
import com.uic.uicpaymentapp.ui.theme.color_black
import com.uic.uicpaymentapp.ui.theme.color_grey50
import com.uic.uicpaymentapp.ui.theme.color_grey95
import com.uic.uicpaymentapp.ui.theme.color_primaryBrand
import com.uic.uicpaymentapp.ui.theme.color_secondaryThree
import com.uic.uicpaymentapp.ui.theme.color_white
import com.uic.uicpaymentapp.utils.SoundEffect
import com.uic.uicpaymentapp.utils.SoundManager
import se.warting.signaturepad.SignaturePadAdapter
import se.warting.signaturepad.SignaturePadView

private const val SIGNATURE_PAD_HEIGHT = 100

@Composable
fun SignatureScreen(
    onConfirmPressed: (String) -> Unit,
//    viewModel: SignatureViewModel = viewModel(factory = AppViewModelProvider.Factory)
    )
{
    val context = LocalContext.current

    // Crea la fábrica con el contexto actual
    val viewModelFactory = remember { AppViewModelProvider.provideFactory(context) }

    // Obtén el InfoMgmtViewModel con la fábrica personalizada
    val viewModel: SignatureViewModel = viewModel(factory = viewModelFactory)

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .height(24.dp)
                    .background(color_white)
                    .fillMaxSize()){}
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .height(64.dp)
                    .background(color_secondaryThree)
                    .fillMaxSize()
            ){ }
        }
    ) { contentPadding ->
        Column(
            Modifier
                .fillMaxWidth(1f)
                .fillMaxSize()
                .padding(contentPadding)
                .background(color_secondaryThree)
        ) {
            Column(
                Modifier
                    .padding(8.dp,24.dp,8.dp,0.dp)
                    .background(color_white, shape = RoundedCornerShape(6.dp))
                    .fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .background(color_grey95, shape = RoundedCornerShape(6.dp,6.dp,0.dp,0.dp))
                        .fillMaxWidth(1f)
                        .padding(0.dp,32.dp)
                ){
                    val transaction by viewModel.transaction.observeAsState(Transaction())
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(1f),
                        horizontalAlignment = CenterHorizontally
                    ) {
                        Text(
                            fontSize = 16.sp,
                            text = transaction.type.toStringForUsers(),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = 16.dp)
                        )
                        Text(
                            fontSize = 24.sp,
                            text = "$${transaction.totalAmount}",
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }

                Text(
                    text = stringResource(id = R.string.add_signature),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = color_grey50,
                    modifier = Modifier
                        .padding(top = 48.dp)
                        .align(Alignment.CenterHorizontally)
                )

                var signaturePadAdapter: SignaturePadAdapter? = null

                Row(
                    Modifier
                        .fillMaxWidth(1f)
                        .padding(start = 32.dp, end = 32.dp, top = 32.dp)) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = "SignatureLine", modifier = Modifier
                        .align(Alignment.Bottom)
                        .padding(8.dp))
                    Box(
                        modifier = Modifier
                            .height(SIGNATURE_PAD_HEIGHT.dp)
                            .fillMaxWidth(1f)
                    ) {
                        SignaturePadView(
                            onReady = {
                                signaturePadAdapter = it
                            },
                            penColor = Color.Black,

                            onStartSigning = {
                                Log.d("SignedListener", "OnStartSigning")
                            },
                            onSigning = {
                                Log.d("SignedListener", "onSigning")
                            },
                            onSigned = {
                                Log.d("SignedListener", "onSigned")
                            },
                            onClear = {
                                Log.d(
                                    "ComposeActivity",
                                    "onClear isEmpty:" + signaturePadAdapter?.isEmpty
                                )
                            },
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, end = 32.dp, bottom = 32.dp))


                Row(modifier = Modifier
                    .height(48.dp)
                    .fillMaxWidth(1f)) {
                    Button(
                        modifier = Modifier
                            .fillMaxHeight(1f)
                            .weight(1f)
                            .padding(start = 16.dp, end = 16.dp),
                        onClick = {
                            SoundManager.play(SoundEffect.KEY_TICK) // 🔊 Play Click Sound
                            signaturePadAdapter?.clear()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = color_grey95,
                            contentColor = color_black
                        ),
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Text(text = stringResource(id = R.string.clear_signature), fontSize = 14.sp)
                    }

                    Button(
                        modifier = Modifier
                            .fillMaxHeight(1f)
                            .weight(1f)
                            .padding(start = 16.dp, end = 16.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = color_primaryBrand,
                            contentColor = color_black
                        ),
                        onClick = {
                            SoundManager.play(SoundEffect.KEY_TICK) // 🔊 Play Click Sound
                            if (signaturePadAdapter?.isEmpty == false) {
                                viewModel.saveSignature(
                                    signaturePadAdapter?.getSignatureBitmap() ?: Bitmap.createBitmap(
                                        SIGNATURE_PAD_HEIGHT, 240, Bitmap.Config.ARGB_8888
                                    )
                                )
                            } else {
                                viewModel.skipSignature()
                            }
                            onConfirmPressed(viewModel.transactionId)
                        },

                        ) {
                        Text(text = stringResource(id = R.string.confirm_signature), fontSize = 14.sp)
                    }
                }
            }

        }
    }
}



