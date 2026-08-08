package com.voiceclone.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.DisposableEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.voiceclone.app.ui.screens.HomeScreen
import com.voiceclone.app.ui.screens.RecordScreen
import com.voiceclone.app.ui.screens.TtsChatScreen
import com.voiceclone.app.ui.theme.VoiceCloneTheme
import com.voiceclone.app.viewmodel.VoiceViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: VoiceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 麦克风权限改为按需在 RecordScreen 内部申请,启动时不再弹权限框,
        // 避免冷启动出现无解释的权限弹窗,提升首屏体验。
        setContent {
            VoiceCloneTheme {
                val navController = rememberNavController()

                NavHost(
                    navController = navController,
                    startDestination = "home",
                    enterTransition = {
                        fadeIn(animationSpec = tween(300)) +
                            slideInHorizontally(animationSpec = tween(300), initialOffsetX = { it / 4 })
                    },
                    exitTransition = {
                        fadeOut(animationSpec = tween(300)) +
                            slideOutHorizontally(animationSpec = tween(300), targetOffsetX = { -it / 4 })
                    },
                    popEnterTransition = {
                        fadeIn(animationSpec = tween(300)) +
                            slideInHorizontally(animationSpec = tween(300), initialOffsetX = { -it / 4 })
                    },
                    popExitTransition = {
                        fadeOut(animationSpec = tween(300)) +
                            slideOutHorizontally(animationSpec = tween(300), targetOffsetX = { it / 4 })
                    }
                ) {
                    composable("home") {
                        HomeScreen(
                            viewModel = viewModel,
                            onRecordClick = {
                                navController.navigate("record")
                            },
                            onVoiceSelected = { voice ->
                                viewModel.selectVoice(voice)
                                navController.navigate("tts")
                            }
                        )
                    }
                    composable("record") {
                        // 离开录音/合成页时主动停掉录音与播放,
                        // 避免后台继续占用麦克风/AudioTrack 资源。
                        DisposableEffect(Unit) {
                            onDispose {
                                viewModel.stopRecording()
                                viewModel.stopPlayback()
                            }
                        }
                        RecordScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("tts") {
                        DisposableEffect(Unit) {
                            onDispose {
                                viewModel.stopPlayback()
                                viewModel.stopRecording()
                            }
                        }
                        TtsChatScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
