package com.procam.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                ProCamMainUI()
            }
        }
    }
}

@Composable
fun ProCamMainUI() {
    var selectedMode by remember { mutableStateOf("PHOTO") }
    val modes = listOf("PRO", "PORTRAIT", "PHOTO", "VIDEO", "AI LAB")

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0D14))) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⚡ ProCam AI", color = Color.White, fontSize = 18.sp)
            Text("⚙️", fontSize = 18.sp)
        }

        // Center Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(350.dp)
                .align(Alignment.Center),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Mode: $selectedMode",
                color = Color.LightGray,
                fontSize = 20.sp
            )
        }

        // Bottom Controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Modes Switcher
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                modes.forEach { mode ->
                    Text(
                        text = mode,
                        color = if (selectedMode == mode) Color(0xFFFFD60A) else Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clickable { selectedMode = mode }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }

            // Shutter Button
            Box(
                modifier = Modifier
                    .size(70.dp)
                    .background(Color.White, CircleShape)
            )
        }
    }
}
