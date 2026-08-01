package com.anis.larp.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.anis.larp.R
import com.anis.larp.learning.Exercise
import com.anis.larp.learning.ExerciseCompletion
import com.anis.larp.learning.LearnedWord
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
internal fun ExercisePlayer(
    exercise: Exercise,
    onBack: () -> Unit = {},
    onSpeakWord: suspend (String, String) -> Unit = { _, _ -> },
    onRecognizeAnswer: suspend (String) -> String = { "" },
    onComplete: (mistakes: Int, elapsedMillis: Long, hintsUsed: Int) -> Unit = { _, _, _ -> },
    onRateDifficulty: (Int) -> Unit = {},
    onProgressChanged: (Boolean) -> Unit = {}
) {
    var step by rememberSaveable(exercise.id) { mutableIntStateOf(1) }
    var furthestUnlockedStep by rememberSaveable(exercise.id) { mutableIntStateOf(1) }
    var mistakes by rememberSaveable(exercise.id) { mutableIntStateOf(0) }
    var hintsUsed by rememberSaveable(exercise.id) { mutableIntStateOf(0) }
    val startedAt = rememberSaveable(exercise.id) { System.currentTimeMillis() }
    var localCompletion by remember(exercise.id) {
        mutableStateOf<ExerciseCompletion?>(exercise.completion)
    }
    val plan = exercise.plan

    LaunchedEffect(step, localCompletion) {
        onProgressChanged(step > 1 && localCompletion == null)
    }

    AnimatedContent(
        targetState = step,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "ten_step_exercise"
    ) { visibleStep ->
        val previous = if (visibleStep > 1) ({ step = visibleStep - 1 }) else null
        val historyNext: () -> Unit = { step = (visibleStep + 1).coerceAtMost(10) }
        val unlockAndAdvance: () -> Unit = {
            val followingStep = (visibleStep + 1).coerceAtMost(10)
            furthestUnlockedStep = maxOf(furthestUnlockedStep, followingStep)
            step = followingStep
        }
        val canReturnForward = visibleStep < furthestUnlockedStep
        when (visibleStep) {
            1 -> LearnWordStep(
                exercise = exercise,
                step = 1,
                word = plan.words[0],
                onBack = onBack,
                onPrevious = previous,
                canReturnForward = canReturnForward,
                onHistoryNext = historyNext,
                onContinue = unlockAndAdvance,
                onSpeakWord = onSpeakWord
            )
            2 -> AnswerStep(
                exercise = exercise,
                step = 2,
                prompt = stringResource(R.string.exercise_speak_or_type, plan.words[0].text),
                expectedAnswer = plan.words[0].text,
                answerTag = "word_answer_${plan.words[0].text}",
                singleLine = true,
                onBack = onBack,
                onPrevious = previous,
                canReturnForward = canReturnForward,
                onHistoryNext = historyNext,
                onCorrect = unlockAndAdvance,
                onRecognizeAnswer = onRecognizeAnswer,
                onMistake = { mistakes++ }
            )
            3 -> GapDragStep(
                exercise = exercise,
                step = 3,
                word = plan.words[0],
                onBack = onBack,
                onPrevious = previous,
                canReturnForward = canReturnForward,
                onHistoryNext = historyNext,
                onCorrect = unlockAndAdvance,
                onMistake = { mistakes++ }
            )
            4 -> AnswerStep(
                exercise = exercise,
                step = 4,
                prompt = plan.words[0].recallPrompt,
                expectedAnswer = plan.words[0].recallAnswer,
                answerTag = "recall_answer",
                singleLine = false,
                onBack = onBack,
                onPrevious = previous,
                canReturnForward = canReturnForward,
                onHistoryNext = historyNext,
                onCorrect = unlockAndAdvance,
                onRecognizeAnswer = onRecognizeAnswer,
                onMistake = { mistakes++ },
                onHint = { hintsUsed++ }
            )
            5 -> LearnWordStep(
                exercise = exercise,
                step = 5,
                word = plan.words[1],
                onBack = onBack,
                onPrevious = previous,
                canReturnForward = canReturnForward,
                onHistoryNext = historyNext,
                onContinue = unlockAndAdvance,
                onSpeakWord = onSpeakWord
            )
            6 -> AnswerStep(
                exercise = exercise,
                step = 6,
                prompt = stringResource(R.string.exercise_speak_or_type, plan.words[1].text),
                expectedAnswer = plan.words[1].text,
                answerTag = "word_answer_${plan.words[1].text}",
                singleLine = true,
                onBack = onBack,
                onPrevious = previous,
                canReturnForward = canReturnForward,
                onHistoryNext = historyNext,
                onCorrect = unlockAndAdvance,
                onRecognizeAnswer = onRecognizeAnswer,
                onMistake = { mistakes++ }
            )
            7 -> GapDragStep(
                exercise = exercise,
                step = 7,
                word = plan.words[1],
                onBack = onBack,
                onPrevious = previous,
                canReturnForward = canReturnForward,
                onHistoryNext = historyNext,
                onCorrect = unlockAndAdvance,
                onMistake = { mistakes++ }
            )
            8 -> AnswerStep(
                exercise = exercise,
                step = 8,
                prompt = plan.words[1].recallPrompt,
                expectedAnswer = plan.words[1].recallAnswer,
                answerTag = "recall_answer",
                singleLine = false,
                onBack = onBack,
                onPrevious = previous,
                canReturnForward = canReturnForward,
                onHistoryNext = historyNext,
                onCorrect = unlockAndAdvance,
                onRecognizeAnswer = onRecognizeAnswer,
                onMistake = { mistakes++ },
                onHint = { hintsUsed++ }
            )
            9 -> AnswerStep(
                exercise = exercise,
                step = 9,
                prompt = plan.hardPrompt,
                expectedAnswer = plan.hardAnswer,
                answerTag = "recall_answer",
                singleLine = false,
                onBack = onBack,
                onPrevious = previous,
                canReturnForward = canReturnForward,
                onHistoryNext = historyNext,
                onCorrect = unlockAndAdvance,
                onRecognizeAnswer = onRecognizeAnswer,
                onMistake = { mistakes++ },
                onHint = { hintsUsed++ }
            )
            else -> FinalMixedStep(
                exercise = exercise,
                onBack = onBack,
                onPrevious = previous,
                canReturnForward = canReturnForward,
                onHistoryNext = historyNext,
                onMistake = { mistakes++ },
                onHint = { hintsUsed++ },
                completion = localCompletion,
                onComplete = {
                    val completion = ExerciseCompletion(
                        completedAtMillis = System.currentTimeMillis(),
                        mistakes = mistakes,
                        elapsedMillis = System.currentTimeMillis() - startedAt,
                        hintsUsed = hintsUsed
                    )
                    localCompletion = completion
                    onComplete(
                        completion.mistakes,
                        completion.elapsedMillis,
                        completion.hintsUsed
                    )
                },
                onRateDifficulty = { rating ->
                    localCompletion = localCompletion?.copy(difficultyRating = rating)
                    onRateDifficulty(rating)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExerciseStepFrame(
    exercise: Exercise,
    step: Int,
    onBack: () -> Unit,
    onPrevious: (() -> Unit)?,
    middleLabel: String,
    middleEnabled: Boolean,
    onMiddleClick: () -> Unit,
    onMiddleLongClick: (() -> Unit)? = null,
    nextEnabled: Boolean,
    nextLabel: String = stringResource(R.string.exercise_next),
    onNext: () -> Unit,
    showProgress: Boolean = true,
    showControls: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(exercise.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.exercise_back)
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (showControls) {
                ExerciseControls(
                    onPrevious = onPrevious,
                    middleLabel = middleLabel,
                    middleEnabled = middleEnabled,
                    onMiddleClick = onMiddleClick,
                    onMiddleLongClick = onMiddleLongClick,
                    nextEnabled = nextEnabled,
                    nextLabel = nextLabel,
                    onNext = onNext
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            AnimatedVisibility(visible = showProgress && !imeVisible) {
                ExerciseOverview(exercise = exercise, step = step)
            }
            Box(modifier = Modifier.fillMaxSize(), content = content)
        }
    }
}

@Composable
private fun ExerciseOverview(exercise: Exercise, step: Int) {
    Column(
        modifier = Modifier.padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = exercise.instructions,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.exercise_step_progress, step),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge
            )
            Text("${step * 10} %", style = MaterialTheme.typography.labelMedium)
        }
        LinearProgressIndicator(
            progress = { step / 10f },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun LearnWordStep(
    exercise: Exercise,
    step: Int,
    word: LearnedWord,
    onBack: () -> Unit,
    onPrevious: (() -> Unit)?,
    canReturnForward: Boolean,
    onHistoryNext: () -> Unit,
    onContinue: () -> Unit,
    onSpeakWord: suspend (String, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var audioError by remember { mutableStateOf<String?>(null) }
    ExerciseStepFrame(
        exercise = exercise,
        step = step,
        onBack = onBack,
        onPrevious = onPrevious,
        middleLabel = stringResource(R.string.exercise_continue),
        middleEnabled = true,
        onMiddleClick = onContinue,
        nextEnabled = canReturnForward,
        onNext = onHistoryNext
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.exercise_learn_word), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = word.text,
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = word.pronunciation,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    FilledTonalIconButton(
                        onClick = {
                            scope.launch {
                                audioError = runCatching {
                                    onSpeakWord(word.text, exercise.languageTag)
                                }.exceptionOrNull()?.message
                            }
                        },
                        modifier = Modifier.testTag("speak_${word.text}")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.VolumeUp,
                            contentDescription = word.text
                        )
                    }
                }
            }
            Text(word.definition, style = MaterialTheme.typography.bodyLarge)
            audioError?.let { ErrorText(it) }
        }
    }
}

@Composable
private fun AnswerStep(
    exercise: Exercise,
    step: Int,
    prompt: String,
    expectedAnswer: String,
    answerTag: String,
    singleLine: Boolean,
    onBack: () -> Unit,
    onPrevious: (() -> Unit)?,
    canReturnForward: Boolean,
    onHistoryNext: () -> Unit,
    onCorrect: () -> Unit,
    onRecognizeAnswer: suspend (String) -> String,
    onMistake: () -> Unit,
    onHint: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var answer by rememberSaveable(prompt) { mutableStateOf("") }
    var inputVisible by rememberSaveable(prompt) { mutableStateOf(false) }
    var correct by rememberSaveable(prompt) { mutableStateOf(false) }
    var answeredByVoice by rememberSaveable(prompt) { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hintVisible by rememberSaveable(prompt) { mutableStateOf(false) }
    var wrongTranscript by remember { mutableStateOf("") }
    var visibleTranscript by remember { mutableStateOf("") }

    fun evaluate(value: String, voiced: Boolean) {
        val wasCorrect = correct
        answer = value
        answeredByVoice = voiced
        correct = answersEquivalent(value, expectedAnswer) ||
            (!singleLine && normalizeAnswer(value).contains(normalizeAnswer(expectedAnswer)))
        if (correct) {
            error = null
            wrongTranscript = ""
            visibleTranscript = ""
            keyboard?.hide()
            if (!wasCorrect) {
                scope.launch {
                    delay(550)
                    onCorrect()
                }
            }
        } else {
            onMistake()
            error = resources.getString(R.string.exercise_incorrect)
            wrongTranscript = if (voiced) value else ""
        }
    }

    fun recognize() {
        scope.launch {
            inputVisible = false
            keyboard?.hide()
            listening = true
            error = null
            runCatching { onRecognizeAnswer(exercise.languageTag) }
                .onSuccess { evaluate(it, voiced = true) }
                .onFailure { error = it.message }
            listening = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) recognize() else {
            error = resources.getString(R.string.exercise_microphone_denied)
        }
    }

    LaunchedEffect(inputVisible) {
        if (inputVisible) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    LaunchedEffect(wrongTranscript) {
        visibleTranscript = ""
        wrongTranscript.split(Regex("\\s+")).filter(String::isNotBlank).forEach { word ->
            visibleTranscript = (visibleTranscript + " " + word).trim()
            delay(90)
        }
    }

    val startVoice = {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            recognize()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    ExerciseStepFrame(
        exercise = exercise,
        step = step,
        onBack = onBack,
        onPrevious = onPrevious,
        middleLabel = stringResource(R.string.exercise_answer),
        middleEnabled = !listening,
        onMiddleClick = {
            inputVisible = true
            error = null
        },
        onMiddleLongClick = startVoice,
        nextEnabled = canReturnForward,
        onNext = onHistoryNext
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(prompt, style = MaterialTheme.typography.headlineSmall)
            if (inputVisible) {
                OutlinedTextField(
                    value = answer,
                    onValueChange = {
                        answer = it.take(600)
                        answeredByVoice = false
                        correct = false
                        error = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag(answerTag),
                    label = { Text(stringResource(R.string.exercise_your_answer)) },
                    singleLine = singleLine,
                    minLines = if (singleLine) 1 else 2,
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { evaluate(answer, voiced = false) }
                    )
                )
            } else if (listening) {
                Text(
                    stringResource(
                        R.string.exercise_listening,
                        Locale.forLanguageTag(exercise.languageTag).displayLanguage
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (visibleTranscript.isNotBlank() && !correct) {
                AnimatedContent(
                    targetState = visibleTranscript,
                    transitionSpec = {
                        (fadeIn() + slideInVertically { it / 2 }) togetherWith fadeOut()
                    },
                    label = "wrong_voice_transcript"
                ) { transcript ->
                    ErrorSurface(stringResource(R.string.exercise_heard, transcript))
                }
            }
            error?.let { ErrorSurface(it) }
            if (correct) {
                SuccessSurface(
                    stringResource(
                        if (answeredByVoice) {
                            R.string.exercise_pronunciation_correct
                        } else {
                            R.string.exercise_typed_correct
                        }
                    )
                )
            }
            if (onHint != null) {
                if (hintVisible) {
                    Text(
                        stringResource(
                            R.string.exercise_hint_value,
                            expectedAnswer.take((expectedAnswer.length / 2).coerceAtLeast(1))
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                TextButton(onClick = {
                    if (!hintVisible) onHint()
                    hintVisible = true
                }) {
                    Icon(Icons.Rounded.Lightbulb, contentDescription = null)
                    Text(stringResource(R.string.exercise_hint))
                }
            }
        }
    }
}

@Composable
private fun GapDragStep(
    exercise: Exercise,
    step: Int,
    word: LearnedWord,
    onBack: () -> Unit,
    onPrevious: (() -> Unit)?,
    canReturnForward: Boolean,
    onHistoryNext: () -> Unit,
    onCorrect: () -> Unit,
    onMistake: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var targetBounds by remember { mutableStateOf(Rect.Zero) }
    var selected by rememberSaveable(word.text) { mutableStateOf<String?>(null) }
    var correct by rememberSaveable(word.text) { mutableStateOf(false) }
    var attempted by rememberSaveable(word.text) { mutableStateOf(false) }
    val options = remember(word) {
        (word.distractors + word.text).shuffled(Random(word.text.hashCode()))
    }
    val pieces = word.gapSentence.split("___", limit = 2)
    ExerciseStepFrame(
        exercise = exercise,
        step = step,
        onBack = onBack,
        onPrevious = onPrevious,
        middleLabel = stringResource(R.string.exercise_verify),
        middleEnabled = selected != null,
        onMiddleClick = {
            attempted = true
            correct = answersEquivalent(selected.orEmpty(), word.text)
            if (!correct) {
                onMistake()
            } else {
                scope.launch {
                    delay(550)
                    onCorrect()
                }
            }
        },
        nextEnabled = canReturnForward,
        onNext = onHistoryNext
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                stringResource(R.string.exercise_drag_instruction),
                style = MaterialTheme.typography.titleMedium
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                InlineGapSentence(
                    before = pieces.getOrElse(0) { "" },
                    after = pieces.getOrElse(1) { "" },
                    selected = selected,
                    onBounds = { targetBounds = it }
                )
            }
            if (attempted) {
                if (correct) {
                    SuccessSurface(stringResource(R.string.exercise_drag_correct))
                } else {
                    ErrorSurface(stringResource(R.string.exercise_drag_incorrect))
                }
                Spacer(Modifier.height(12.dp))
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { option ->
                    DraggableWordChip(
                        text = option,
                        targets = mapOf(0 to targetBounds),
                        onDropped = {
                            selected = option
                            attempted = false
                            correct = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineGapSentence(
    before: String,
    after: String,
    selected: String?,
    onBounds: (Rect) -> Unit
) {
    val annotated = buildAnnotatedString {
        append(before)
        appendInlineContent("answer_gap", "_____")
        append(after)
    }
    Text(
        text = annotated,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.headlineSmall,
        inlineContent = mapOf(
            "answer_gap" to InlineTextContent(
                Placeholder(
                    width = 116.sp,
                    height = 42.sp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                )
            ) {
                DropTarget(text = selected.orEmpty(), onBounds = onBounds)
            }
        )
    )
}

@Composable
private fun FinalMixedStep(
    exercise: Exercise,
    onBack: () -> Unit,
    onPrevious: (() -> Unit)?,
    canReturnForward: Boolean,
    onHistoryNext: () -> Unit,
    onMistake: () -> Unit,
    onHint: () -> Unit,
    completion: ExerciseCompletion?,
    onComplete: () -> Unit,
    onRateDifficulty: (Int) -> Unit
) {
    if (completion != null) {
        ExerciseStepFrame(
            exercise = exercise,
            step = 10,
            onBack = onBack,
            onPrevious = null,
            middleLabel = "",
            middleEnabled = false,
            onMiddleClick = {},
            nextEnabled = false,
            onNext = {},
            showProgress = false,
            showControls = false
        ) {
            CompletionSummary(completion, onRateDifficulty)
        }
        return
    }
    val plan = exercise.plan
    val learnedIndexes = remember(plan) {
        val available = plan.finalAnswers.indices.toMutableSet()
        plan.words.mapNotNull { word ->
            available.firstOrNull { plan.finalAnswers[it].equals(word.text, ignoreCase = true) }
                ?.also(available::remove)
        }.toSet()
    }
    val fillerIndexes = plan.finalAnswers.indices.filterNot(learnedIndexes::contains)
    val targets = remember { mutableStateMapOf<Int, Rect>() }
    var typed by rememberSaveable(exercise.id) { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var dropped by rememberSaveable(exercise.id) { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var hintVisible by rememberSaveable(exercise.id) { mutableStateOf(false) }
    var correct by rememberSaveable(exercise.id) { mutableStateOf(false) }
    var attempted by rememberSaveable(exercise.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sentenceParts = plan.finalSentence.split("___")
    val ready = learnedIndexes.all { typed[it].orEmpty().isNotBlank() } &&
        fillerIndexes.all { dropped[it].orEmpty().isNotBlank() }

    ExerciseStepFrame(
        exercise = exercise,
        step = 10,
        onBack = onBack,
        onPrevious = onPrevious,
        middleLabel = stringResource(R.string.exercise_verify),
        middleEnabled = ready,
        onMiddleClick = {
            attempted = true
            correct = learnedIndexes.all { index ->
                answersEquivalent(typed[index].orEmpty(), plan.finalAnswers[index])
            } && fillerIndexes.all { index ->
                answersEquivalent(dropped[index].orEmpty(), plan.finalAnswers[index])
            }
            if (!correct) onMistake()
            else scope.launch {
                delay(550)
                onComplete()
            }
        },
        nextEnabled = canReturnForward,
        nextLabel = stringResource(R.string.exercise_next),
        onNext = onHistoryNext
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(stringResource(R.string.exercise_final_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.exercise_final_instruction),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                plan.finalAnswers.indices.forEach { index ->
                    Text(sentenceParts.getOrElse(index) { "" })
                    if (index in learnedIndexes) {
                        OutlinedTextField(
                            value = typed[index].orEmpty(),
                            onValueChange = {
                                typed = typed + (index to it.take(80))
                                correct = false
                                attempted = false
                            },
                            modifier = Modifier
                                .size(width = 130.dp, height = 64.dp)
                                .testTag("final_typed_$index"),
                            label = { Text(stringResource(R.string.exercise_write)) },
                            singleLine = true
                        )
                    } else {
                        DropTarget(
                            text = dropped[index].orEmpty(),
                            onBounds = { targets[index] = it },
                            modifier = Modifier.size(width = 112.dp, height = 48.dp)
                        )
                    }
                }
                Text(sentenceParts.getOrElse(4) { "" })
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                fillerIndexes.map { plan.finalAnswers[it] }.distinct().forEach { filler ->
                    DraggableWordChip(
                        text = filler,
                        targets = targets.filterKeys { index ->
                            plan.finalAnswers[index].equals(filler, ignoreCase = true)
                        },
                        onDroppedAt = { index ->
                            dropped = dropped + (index to filler)
                            correct = false
                            attempted = false
                        }
                    )
                }
            }
            if (attempted && !correct) {
                ErrorSurface(stringResource(R.string.exercise_final_incorrect))
            }
            if (hintVisible) {
                Text(
                    stringResource(
                        R.string.exercise_final_hint,
                        plan.words.joinToString { it.text }
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            TextButton(onClick = {
                if (!hintVisible) onHint()
                hintVisible = true
            }) {
                Icon(Icons.Rounded.Lightbulb, contentDescription = null)
                Text(stringResource(R.string.exercise_hint))
            }
        }
    }
}

@Composable
private fun CompletionSummary(
    completion: ExerciseCompletion,
    onRateDifficulty: (Int) -> Unit
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) {
        delay(350)
        bringIntoViewRequester.bringIntoView()
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Confetti()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .bringIntoViewRequester(bringIntoViewRequester),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(stringResource(R.string.exercise_congratulations), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.exercise_completed), style = MaterialTheme.typography.titleLarge)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = correctContainerColor()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(stringResource(R.string.exercise_mistakes, completion.mistakes))
                    Text(stringResource(R.string.exercise_time, formatDuration(completion.elapsedMillis)))
                    if (completion.hintsUsed > 0) {
                        Text(stringResource(R.string.exercise_hints_used, completion.hintsUsed))
                    }
                }
            }
            Text(stringResource(R.string.exercise_rate_difficulty), style = MaterialTheme.typography.titleMedium)
            Row {
                (1..5).forEach { rating ->
                    val description = stringResource(
                        if (rating == 1) R.string.exercise_star_description
                        else R.string.exercise_stars_description,
                        rating
                    )
                    IconButton(onClick = { onRateDifficulty(rating) }) {
                        Icon(
                            imageVector = if (rating <= (completion.difficultyRating ?: 0)) {
                                Icons.Rounded.Star
                            } else {
                                Icons.Rounded.StarBorder
                            },
                            contentDescription = description,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Confetti() {
    val progress = remember { Animatable(0f) }
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        Color(0xFFFFC107)
    )
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(1_600)) }
    Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
        repeat(28) { index ->
            val x = ((index * 83) % 100) / 100f * size.width
            val startY = -((index * 31) % 100).toFloat()
            val y = startY + progress.value * (size.height + 120f)
            drawCircle(colors[index % colors.size], radius = 5f + index % 4, center = Offset(x, y))
        }
    }
}

@Composable
private fun DropTarget(
    text: String,
    onBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    val outlineColor = MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 96.dp, minHeight = 42.dp)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .testTag("drop_target")
            .then(
                Modifier.drawDashedOutline(outlineColor)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (text.isNotBlank()) {
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

private fun Modifier.drawDashedOutline(color: Color): Modifier = this.then(
    Modifier.drawBehind {
        val strokeWidth = 2.dp.toPx()
        drawRoundRect(
            color = color,
            cornerRadius = CornerRadius(12.dp.toPx()),
            style = Stroke(
                width = strokeWidth,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))
            )
        )
    }
)

@Composable
private fun DraggableWordChip(
    text: String,
    targets: Map<Int, Rect>,
    onDropped: (() -> Unit)? = null,
    onDroppedAt: ((Int) -> Unit)? = null
) {
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    SuggestionChip(
        onClick = {
            onDropped?.invoke() ?: targets.keys.firstOrNull()?.let { onDroppedAt?.invoke(it) }
        },
        label = { Text(text) },
        modifier = Modifier
            .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .pointerInput(text, targets) {
                detectDragGestures(
                    onDragEnd = {
                        targets.entries.firstOrNull { (_, target) -> target.overlaps(bounds) }
                            ?.let { (index, _) ->
                                onDroppedAt?.invoke(index) ?: onDropped?.invoke()
                            }
                        dragOffset = Offset.Zero
                    },
                    onDragCancel = { dragOffset = Offset.Zero },
                    onDrag = { change, amount ->
                        change.consume()
                        dragOffset += amount
                    }
                )
            }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExerciseControls(
    onPrevious: (() -> Unit)?,
    middleLabel: String,
    middleEnabled: Boolean,
    onMiddleClick: () -> Unit,
    onMiddleLongClick: (() -> Unit)?,
    nextEnabled: Boolean,
    nextLabel: String,
    onNext: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(
                ButtonGroupDefaults.ConnectedSpaceBetween
            )
        ) {
            FilledTonalButton(
                onClick = { onPrevious?.invoke() },
                shapes = ButtonDefaults.shapes(
                    shape = ButtonGroupDefaults.connectedLeadingButtonShape,
                    pressedShape = ButtonGroupDefaults.connectedLeadingButtonPressShape
                ),
                modifier = Modifier.weight(1f),
                enabled = onPrevious != null,
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(stringResource(R.string.exercise_previous), maxLines = 1)
            }
            HoldAnswerButton(
                label = middleLabel,
                enabled = middleEnabled,
                onClick = onMiddleClick,
                onLongClick = onMiddleLongClick,
                modifier = Modifier.weight(1.15f)
            )
            FilledTonalButton(
                onClick = onNext,
                shapes = ButtonDefaults.shapes(
                    shape = ButtonGroupDefaults.connectedTrailingButtonShape,
                    pressedShape = ButtonGroupDefaults.connectedTrailingButtonPressShape
                ),
                modifier = Modifier.weight(1f),
                enabled = nextEnabled,
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(nextLabel, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HoldAnswerButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val holdLabel = stringResource(R.string.exercise_hold_to_speak)
    var longPressTriggered by remember { mutableStateOf(false) }
    LaunchedEffect(interactionSource, enabled, onLongClick) {
        var holdJob: Job? = null
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    holdJob?.cancel()
                    holdJob = if (enabled && onLongClick != null) {
                        launch {
                            delay(500)
                            longPressTriggered = true
                            onLongClick()
                        }
                    } else null
                }
                is PressInteraction.Release,
                is PressInteraction.Cancel -> {
                    holdJob?.cancel()
                    holdJob = null
                }
            }
        }
    }
    FilledTonalButton(
        onClick = {
            if (longPressTriggered) longPressTriggered = false else onClick()
        },
        shapes = ButtonDefaults.shapes(
            shape = androidx.compose.material3.ShapeDefaults.Small,
            pressedShape = ButtonGroupDefaults.connectedMiddleButtonPressShape
        ),
        modifier = modifier.semantics {
            if (onLongClick != null) {
                onLongClick(label = holdLabel) {
                    onLongClick()
                    true
                }
            }
        },
        interactionSource = interactionSource,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp)
    ) {
        Text(label, maxLines = 1)
    }
}

@Composable
private fun SuccessSurface(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = correctContainerColor()
    ) {
        Text(message, modifier = Modifier.padding(16.dp), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ErrorSurface(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Text(
            message,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun correctContainerColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Color(0xFF1B5E20)
    } else {
        Color(0xFFC8E6C9)
    }

private fun answersEquivalent(actual: String, expected: String): Boolean =
    expected.split("||").any { accepted -> normalizeAnswer(actual) == normalizeAnswer(accepted) }

private fun normalizeAnswer(value: String): String = Normalizer
    .normalize(value, Normalizer.Form.NFC)
    .lowercase(Locale.ROOT)
    .replace(Regex("\\s+"), " ")
    .trim()
    .trimEnd('.', '!', '?', '。', '！', '？')

@Composable
private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) {
        stringResource(R.string.exercise_minutes_seconds, minutes, seconds)
    } else {
        stringResource(R.string.exercise_seconds, seconds)
    }
}
