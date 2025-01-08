package org.wikipedia.games.onthisday

import android.os.Bundle
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.wikipedia.Constants
import org.wikipedia.WikipediaApp
import org.wikipedia.database.AppDatabase
import org.wikipedia.dataclient.ServiceFactory
import org.wikipedia.dataclient.page.PageSummary
import org.wikipedia.feed.onthisday.OnThisDay
import org.wikipedia.json.JsonUtil
import org.wikipedia.settings.Prefs
import org.wikipedia.util.Resource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.random.Random

class OnThisDayGameViewModel(bundle: Bundle) : ViewModel() {

    private val _gameState = MutableLiveData<Resource<GameState>>()
    val gameState: LiveData<Resource<GameState>> get() = _gameState

    val invokeSource = bundle.getSerializable(Constants.INTENT_EXTRA_INVOKE_SOURCE) as Constants.InvokeSource

    private lateinit var currentState: GameState
    private val overrideDate = bundle.containsKey(EXTRA_DATE)
    val currentDate = if (overrideDate) LocalDate.ofInstant(Instant.ofEpochSecond(bundle.getLong(EXTRA_DATE)), ZoneOffset.UTC) else LocalDate.now()
    val currentMonth get() = currentDate.monthValue
    val currentDay get() = currentDate.dayOfMonth

    private val events = mutableListOf<OnThisDay.Event>()
    val savedPages = mutableListOf<PageSummary>()

    init {
        Prefs.lastOtdGameVisitDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

        loadGameState()
    }

    fun loadGameState() {
        viewModelScope.launch(CoroutineExceptionHandler { _, throwable ->
            _gameState.postValue(Resource.Error(throwable))
        }) {
            _gameState.postValue(Resource.Loading())

            events.clear()
            events.addAll(ServiceFactory.getRest(WikipediaApp.instance.wikiSite).getOnThisDay(currentMonth, currentDay).events)

            JsonUtil.decodeFromString<GameState>(Prefs.otdGameState)?.let {
                currentState = it
                if (overrideDate) {
                    currentState = it.copy(currentQuestionState = composeQuestionState(currentMonth, currentDay, 0))
                }
            } ?: run {
                currentState = GameState(currentQuestionState = composeQuestionState(currentMonth, currentDay, 0))
            }

            savedPages.clear()
            currentState.articles.forEach { pageSummary ->
                val inAnyList = AppDatabase.instance.readingListPageDao().findPageInAnyList(pageSummary.getPageTitle(WikipediaApp.instance.wikiSite)) != null
                if (inAnyList) {
                    savedPages.add(pageSummary)
                }
            }

            if (currentState.currentQuestionState.month == currentMonth && currentState.currentQuestionState.day == currentDay &&
                currentState.currentQuestionIndex == 0 && !currentState.currentQuestionState.goToNext) {
                // we're just starting today's game.
                currentState = currentState.copy(articles = emptyList())
                _gameState.postValue(GameStarted(currentState))
            } else if (currentState.currentQuestionState.month == currentMonth && currentState.currentQuestionState.day == currentDay &&
                currentState.currentQuestionIndex >= currentState.totalQuestions) {
                // we're already done for today.
                _gameState.postValue(GameEnded(currentState))
            } else if (currentState.currentQuestionState.month != currentMonth || currentState.currentQuestionState.day != currentDay &&
                currentState.currentQuestionIndex >= currentState.totalQuestions) {
                // we're coming back from a previous day's completed game, so start a new day's game.
                currentState = currentState.copy(currentQuestionState = composeQuestionState(currentMonth, currentDay, 0), currentQuestionIndex = 0, answerState = List(MAX_QUESTIONS) { false }, articles = emptyList())
                _gameState.postValue(GameStarted(currentState))
            } else {
                _gameState.postValue(Resource.Success(currentState))
            }

            persistState()
        }
    }

    fun submitCurrentResponse(selectedYear: Int) {
        currentState = currentState.copy(currentQuestionState = currentState.currentQuestionState.copy(yearSelected = selectedYear))

        if (currentState.currentQuestionState.goToNext) {
            val nextQuestionIndex = currentState.currentQuestionIndex + 1
            currentState = currentState.copy(currentQuestionState = composeQuestionState(currentMonth, currentDay, nextQuestionIndex), currentQuestionIndex = nextQuestionIndex)

            if (nextQuestionIndex >= currentState.totalQuestions) {
                // push today's answers to the history map
                val map = currentState.answerStateHistory.toMutableMap()
                if (!map.containsKey(currentDate.year))
                    map[currentDate.year] = emptyMap()
                val monthMap = map[currentDate.year]!!.toMutableMap()
                map[currentDate.year] = monthMap
                if (!monthMap.containsKey(currentMonth))
                    monthMap[currentMonth] = emptyMap()
                val dayMap = monthMap[currentMonth]!!.toMutableMap()
                monthMap[currentMonth] = dayMap
                dayMap[currentDay] = currentState.answerState
                currentState = currentState.copy(answerStateHistory = map)

                _gameState.postValue(GameEnded(currentState))
            } else {
                _gameState.postValue(Resource.Success(currentState))
            }
        } else {
            currentState = currentState.copy(currentQuestionState = currentState.currentQuestionState.copy(goToNext = true))

            val isCorrect = currentState.currentQuestionState.event1.year == selectedYear
            currentState = currentState.copy(
                answerState = currentState.answerState.toMutableList().apply { set(currentState.currentQuestionIndex, isCorrect) },
                articles = currentState.articles.toMutableList().apply {
                    addAll(currentState.currentQuestionState.event1.pages.take(2) ?: emptyList())
                }
            )

            if (isCorrect) {
                _gameState.postValue(CurrentQuestionCorrect(currentState))
            } else {
                _gameState.postValue(CurrentQuestionIncorrect(currentState))
            }
        }
        persistState()
    }

    fun resetCurrentDayState() {
        currentState = currentState.copy(currentQuestionState = composeQuestionState(currentMonth, currentDay, 0), currentQuestionIndex = 0, answerState = List(MAX_QUESTIONS) { false })
        persistState()
    }

    fun getCurrentGameState(): GameState {
        return currentState
    }

    private fun composeQuestionState(month: Int, day: Int, index: Int): QuestionState {
        val random = Random(month * 100 + day)
        val yearRegex = Regex(".*\\b\\d{1,4}\\b.*")

        val eventList = events.filter {
            it.year > 0 && it.year <= currentDate.year && !it.text.matches(yearRegex)
        }.toMutableList()
        eventList.shuffle(random)

        return QuestionState(eventList[0], eventList[1], month, day)
    }

    private fun persistState() {
        Prefs.otdGameState = JsonUtil.encodeToString(currentState).orEmpty()
    }

    @Serializable
    data class GameState(
        val totalQuestions: Int = Prefs.otdGameQuestionsPerDay,
        val currentQuestionIndex: Int = 0,

        // history of today's answers (correct vs incorrect)
        val answerState: List<Boolean> = List(MAX_QUESTIONS) { false },

        // list of today's mentioned articles
        val articles: List<PageSummary> = emptyList(),

        // map of:   year: month: day: list of answers
        val answerStateHistory: Map<Int, Map<Int, Map<Int, List<Boolean>>>> = emptyMap(),

        val currentQuestionState: QuestionState
    )

    @Serializable
    data class QuestionState(
        val event1: OnThisDay.Event,
        val event2: OnThisDay.Event,
        val month: Int = 0,
        val day: Int = 0,
        val yearSelected: Int? = null,
        val goToNext: Boolean = false
    )

    class CurrentQuestionCorrect(val data: GameState) : Resource<GameState>()
    class CurrentQuestionIncorrect(val data: GameState) : Resource<GameState>()
    class GameStarted(val data: GameState) : Resource<GameState>()
    class GameEnded(val data: GameState) : Resource<GameState>()

    class Factory(val bundle: Bundle) : ViewModelProvider.Factory {
        @Suppress("unchecked_cast")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return OnThisDayGameViewModel(bundle) as T
        }
    }

    companion object {
        const val MAX_QUESTIONS = 10
        const val EXTRA_DATE = "date"

        fun shouldShowEntryDialog(): Boolean {
            if (Prefs.lastOtdGameVisitDate.isEmpty()) {
                return true
            }
            try {
                val prevDate = LocalDate.parse(Prefs.lastOtdGameVisitDate, DateTimeFormatter.ISO_LOCAL_DATE)
                return prevDate.dayOfMonth != LocalDate.now().dayOfMonth
            } catch (e: Exception) {
                return true
            }
        }

        val gameStartDate: LocalDate get() {
            return try {
                LocalDate.parse(Prefs.otdGameStartDate)
            } catch (e: Exception) {
                LocalDate.ofEpochDay(0)
            }
        }

        val gameEndDate: LocalDate get() {
            return try {
                LocalDate.parse(Prefs.otdGameEndDate)
            } catch (e: Exception) {
                LocalDate.ofEpochDay(0)
            }
        }

        val isGameActive get() = true // LocalDate.now().isAfter(gameStartDate) && LocalDate.now().isBefore(gameEndDate)

        val gameForToday get() = LocalDate.now().toEpochDay() - gameStartDate.toEpochDay()

        val daysLeft get() = gameEndDate.toEpochDay() - LocalDate.now().toEpochDay()
    }
}
