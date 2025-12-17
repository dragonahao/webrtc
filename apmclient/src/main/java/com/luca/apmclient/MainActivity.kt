package com.luca.apmclient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.luca.apmclient.ui.theme.EmotionappTheme
import com.luca.apmcore.ApmManager
import com.luca.apmcore.PcmGenerator

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val apmManager = ApmManager(this)

        enableEdgeToEdge()
        setContent {
            EmotionappTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding)) {
                        Box(modifier = Modifier.size(70.dp)){}

                        Greeting(
                            name = "Android-webrtc-client",
                            modifier = Modifier.padding(innerPadding)
                        )

                        Button(onClick = {
                            XXPermissions.with(this@MainActivity)
                                .permissions(
                                    listOf(
                                        PermissionLists.getRecordAudioPermission(),
                                        PermissionLists.getWriteExternalStoragePermission(),
                                        PermissionLists.getReadExternalStoragePermission()
                                    )
                                ).request { grantedList, deniedList ->
                                    val allGranted = deniedList.isEmpty();
                                    if (allGranted) {
                                        println("has permision")
                                        apmManager.initStatus()
                                    } else {
                                        println("not has permision")
                                    }
                                }

                        }) {
                            Text(text = "初始化")

                        }
                        Button(onClick = {
                            apmManager.startRecord()
                        }) {
                            Text(text = "开始")

                        }
                        Button(onClick = {
                            apmManager.stopRecord()
                        }) {
                            Text(text = "停止")

                        }

                        Button(onClick = {
                            Thread({
                                val pcm = PcmGenerator.loadPcmBytes(this@MainActivity, "mock_wav/luca_zha_asr_example.pcm")
                                while(true) {
                                    apmManager.playAudio(pcm)
                                    Thread.sleep(1000)
                                }
                            }).start()
                        }) {
                            Text(text = "播放外部音频")

                        }
                    }

                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    EmotionappTheme {
        Greeting("Android")
    }
}