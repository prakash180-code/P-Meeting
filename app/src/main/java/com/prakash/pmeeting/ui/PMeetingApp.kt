package com.prakash.pmeeting.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.prakash.pmeeting.ui.actionitem.ActionItemScreen
import com.prakash.pmeeting.ui.audio.AudioRecordScreen
import com.prakash.pmeeting.ui.detail.DetailScreen
import com.prakash.pmeeting.ui.email.EmailDraftScreen
import com.prakash.pmeeting.ui.home.HomeScreen
import com.prakash.pmeeting.ui.minutes.MinutesScreen
import com.prakash.pmeeting.ui.settings.SettingsScreen
import com.prakash.pmeeting.ui.transcript.TranscriptScreen
import com.prakash.pmeeting.ui.video.VideoRecordScreen

object Routes {
    const val HOME = "home"
    const val AUDIO_RECORD = "audioRecord"
    const val VIDEO_RECORD = "videoRecord"
    const val DETAIL = "detail/{recordingId}"
    const val TRANSCRIPT = "transcript/{recordingId}"
    const val MINUTES = "minutes/{recordingId}"
    const val ACTION_ITEMS = "actionItems/{recordingId}"
    const val EMAIL_DRAFT = "emailDraft/{recordingId}"
    const val SETTINGS = "settings"

    fun detail(id: Long): String = "detail/$id"
    fun transcript(id: Long): String = "transcript/$id"
    fun minutes(id: Long): String = "minutes/$id"
    fun actionItems(id: Long): String = "actionItems/$id"
    fun emailDraft(id: Long): String = "emailDraft/$id"
    fun settings(): String = "settings"
}

@Composable
fun PMeetingApp() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenAudioRecorder = { navController.navigate(Routes.AUDIO_RECORD) },
                onOpenVideoRecorder = { navController.navigate(Routes.VIDEO_RECORD) },
                onOpenRecording = { id -> navController.navigate(Routes.detail(id)) },
                onOpenSettings = { navController.navigate(Routes.settings()) },
                onOpenTranscript = { id -> navController.navigate(Routes.transcript(id)) }
            )
        }

        composable(Routes.AUDIO_RECORD) {
            AudioRecordScreen(onDone = { navController.popBackStack() })
        }

        composable(Routes.VIDEO_RECORD) {
            VideoRecordScreen(onDone = { navController.popBackStack() })
        }

        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("recordingId") { type = NavType.LongType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("recordingId") ?: -1L
            DetailScreen(
                onBack = { navController.popBackStack() },
                recordingId = id,
                onViewTranscript = { navController.navigate(Routes.transcript(id)) },
                onViewMinutes = { navController.navigate(Routes.minutes(id)) },
                onManageActionItems = { navController.navigate(Routes.actionItems(id)) },
                onViewEmailDraft = { navController.navigate(Routes.emailDraft(id)) }
            )
        }

        composable(
            route = Routes.TRANSCRIPT,
            arguments = listOf(navArgument("recordingId") { type = NavType.LongType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("recordingId") ?: -1L
            TranscriptScreen(
                onBack = { navController.popBackStack() },
                recordingId = id
            )
        }

        composable(
            route = Routes.MINUTES,
            arguments = listOf(navArgument("recordingId") { type = NavType.LongType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("recordingId") ?: -1L
            MinutesScreen(
                onBack = { navController.popBackStack() },
                recordingId = id
            )
        }

        composable(
            route = Routes.ACTION_ITEMS,
            arguments = listOf(navArgument("recordingId") { type = NavType.LongType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("recordingId") ?: -1L
            ActionItemScreen(
                onBack = { navController.popBackStack() },
                recordingId = id
            )
        }

        composable(
            route = Routes.EMAIL_DRAFT,
            arguments = listOf(navArgument("recordingId") { type = NavType.LongType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("recordingId") ?: -1L
            EmailDraftScreen(
                onBack = { navController.popBackStack() },
                recordingId = id
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
