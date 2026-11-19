package com.example

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ai.configureAppCheck
import com.example.data.AnalysisCacheStore
import com.example.data.BasketballRepository
import com.example.data.BetDatabase
import com.example.data.BetHistoryRepository
import com.example.data.GeminiRepository
import com.example.data.TennisRepository
import com.example.ui.BetViewModel
import com.example.ui.MainScreen
import com.example.ui.theme.BetQuantTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Mandatory Firebase App Check wiring with launch intent before UI/ViewModels
        configureAppCheck(this, intent)

        // STEP 2: Edge-to-edge dark system bars
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        val database = BetDatabase.getInstance(applicationContext)
        val historyRepository = BetHistoryRepository(database.betSlipDao())
        val cacheStore = AnalysisCacheStore(applicationContext, database.cachedAnalysisDao())
        val geminiRepository = GeminiRepository()
        val basketballRepository = BasketballRepository()
        val tennisRepository = TennisRepository()
        val viewModelFactory = BetViewModel.Factory(
            geminiRepository = geminiRepository,
            basketballRepository = basketballRepository,
            tennisRepository = tennisRepository,
            historyRepository = historyRepository,
            cacheStore = cacheStore
        )

        setContent {
            BetQuantTheme {
                val betViewModel: BetViewModel = viewModel(factory = viewModelFactory)
                MainScreen(viewModel = betViewModel)
            }
        }
    }
}
