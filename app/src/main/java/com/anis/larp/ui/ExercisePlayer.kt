package com.anis.larp.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.anis.larp.learning.Exercise
import com.anis.larp.learning.ExerciseCompletion
import com.anis.larp.learning.LearnedWord
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
internal fun ExercisePlayer(
    exercise: Exercise,
    onSpeakWord: suspend (String, String) -> Unit = { _, _ -> },
    onRecognizeAnswer: suspend (String) -> String = { "" },
    onComplete: (mistakes: Int, elapsedMillis: Long, hintsUsed: Int) -> Unit = { _, _, _ -> },
    onRateDifficulty: (Int) -> Unit = {},
    onProgressChanged: (Boolean) -> Unit = {}
) {
    var step by rememberSaveable(exercise.id) { mutableIntStateOf(1) }
    var mistakes by rememberSaveable(exercise.id) { mutableIntStateOf(0) }
    var hintsUsed by rememberSaveable(exercise.id) { mutableIntStateOf(0) }
    val startedAt = rememberSaveable(exercise.id) { System.currentTimeMillis() }
    var localCompletion by remember(exercise.id) {
        mutableStateOf<ExerciseCompletion?>(exercise.completion)
    }
    val plan = exercise.plan
    val hasUnsavedProgress = step > 1 && localCompletion == null

    LaunchedEffect(hasUnsavedProgress) {
        onProgressChanged(hasUnsavedProgress)
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ExerciseOverview(exercise = exercise, step = step)
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "ten_step_exercise"
        ) { visibleStep ->
            when (visibleStep) {
                1 -> LearnWordStep(
                    word = plan.words[0],
                    languageTag = exercise.languageTag,
                    onSpeakWord = onSpeakWord,
                    onContinue = { step = 2 }
                )
                2 -> ReproduceWordStep(
                    word = plan.words[0],
                    languageTag = exercise.languageTag,
                    onRecognizeAnswer = onRecognizeAnswer,
                    onMistake = { mistakes++ },
                    onContinue = { step = 3 }
                )
                3 -> GapDragStep(
                    word = plan.words[0],
                    onMistake = { mistakes++ },
                    onContinue = { step = 4 }
                )
                4 -> RecallStep(
                    prompt = plan.words[0].recallPrompt,
                    answer = plan.words[0].recallAnswer,
                    onMistake = { mistakes++ },
                    onHint = { hintsUsed++ },
                    onContinue = { step = 5 }
                )
                5 -> LearnWordStep(
                    word = plan.words[1],
                    languageTag = exercise.languageTag,
                    onSpeakWord = onSpeakWord,
                    onContinue = { step = 6 }
                )
                6 -> ReproduceWordStep(
                    word = plan.words[1],
                    languageTag = exercise.languageTag,
                    onRecognizeAnswer = onRecognizeAnswer,
                    onMistake = { mistakes++ },
                    onContinue = { step = 7 }
                )
                7 -> GapDragStep(
                    word = plan.words[1],
                    onMistake = { mistakes++ },
                    onContinue = { step = 8 }
                )
                8 -> RecallStep(
                    prompt = plan.words[1].recallPrompt,
                    answer = plan.words[1].recallAnswer,
                    onMistake = { mistakes++ },
                    onHint = { hintsUsed++ },
                    onContinue = { step = 9 }
                )
                9 -> RecallStep(
                    prompt = plan.hardPrompt,
                    answer = plan.hardAnswer,
                    onMistake = { mistakes++ },
                    onHint = { hintsUsed++ },
                    onContinue = { step = 10 }
                )
                else -> FinalMixedStep(
                    exercise = exercise,
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
}

@Composable
private fun ExerciseOverview(exercise: Exercise, step: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = exercise.instructions,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Étape $step sur 10",
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
    word: LearnedWord,
    languageTag: String,
    onSpeakWord: suspend (String, String) -> Unit,
    onContinue: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var audioError by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Apprenez ce mot", style = MaterialTheme.typography.titleMedium)
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
                                onSpeakWord(word.text, languageTag)
                            }.exceptionOrNull()?.message
                        }
                    },
                    modifier = Modifier.testTag("speak_${word.text}")
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = "Écouter ${word.text}"
                    )
                }
            }
        }
        Text(word.definition, style = MaterialTheme.typography.bodyLarge)
        audioError?.let { ErrorText(it) }
        ContinueButton(onContinue)
    }
}

@Composable
private fun ReproduceWordStep(
    word: LearnedWord,
    languageTag: String,
    onRecognizeAnswer: suspend (String) -> String,
    onMistake: () -> Unit,
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var answer by rememberSaveable(word.text) { mutableStateOf("") }
    var correct by rememberSaveable(word.text) { mutableStateOf(false) }
    var answeredByVoice by rememberSaveable(word.text) { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun recognize() {
        scope.launch {
            listening = true
            error = null
            runCatching { onRecognizeAnswer(languageTag) }
                .onSuccess {
                    answer = it
                    answeredByVoice = true
                    correct = false
                }
                .onFailure { error = it.message }
            listening = false
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) recognize() else error = "Autorisation du microphone refusée."
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Dites ou écrivez « ${word.text} »", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = answer,
                onValueChange = {
                    answer = it.take(120)
                    answeredByVoice = false
                    correct = false
                },
                modifier = Modifier.weight(1f).testTag("word_answer_${word.text}"),
                label = { Text("Votre réponse") },
                singleLine = true
            )
            FilledTonalIconButton(
                onClick = {
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) recognize() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                modifier = Modifier.padding(start = 8.dp),
                enabled = !listening
            ) {
                Icon(Icons.Rounded.Mic, contentDescription = "Prononcer dans la langue étudiée")
            }
        }
        if (listening) Text("Écoute en ${Locale.forLanguageTag(languageTag).displayLanguage}…")
        error?.let { ErrorText(it) }
        if (correct) {
            SuccessSurface(
                if (answeredByVoice) "Bonne prononciation!" else "Correct!"
            )
            ContinueButton(onContinue)
        } else {
            FilledTonalButton(
                onClick = {
                    if (answersEquivalent(answer, word.text)) correct = true else onMistake()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = answer.isNotBlank()
            ) { Text("Vérifier") }
        }
    }
}

@Composable
private fun GapDragStep(
    word: LearnedWord,
    onMistake: () -> Unit,
    onContinue: () -> Unit
) {
    var targetBounds by remember { mutableStateOf(Rect.Zero) }
    var selected by rememberSaveable(word.text) { mutableStateOf<String?>(null) }
    val options = remember(word) {
        (word.distractors + word.text).shuffled(Random(word.text.hashCode()))
    }
    val pieces = word.gapSentence.split("___", limit = 2)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Glissez le bon mot dans la phrase", style = MaterialTheme.typography.titleMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(pieces.getOrElse(0) { "" }, style = MaterialTheme.typography.bodyLarge)
            DropTarget(
                text = selected ?: "Déposez ici",
                onBounds = { targetBounds = it }
            )
            Text(pieces.getOrElse(1) { "" }, style = MaterialTheme.typography.bodyLarge)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                DraggableWordChip(
                    text = option,
                    targets = mapOf(0 to targetBounds),
                    onDropped = {
                        if (answersEquivalent(option, word.text)) selected = option else onMistake()
                    }
                )
            }
        }
        if (selected != null) {
            SuccessSurface("Le mot complète correctement la phrase.")
            ContinueButton(onContinue)
        }
    }
}

@Composable
private fun RecallStep(
    prompt: String,
    answer: String,
    onMistake: () -> Unit,
    onHint: () -> Unit,
    onContinue: () -> Unit
) {
    var learnerAnswer by rememberSaveable(prompt) { mutableStateOf("") }
    var correct by rememberSaveable(prompt) { mutableStateOf(false) }
    var hintVisible by rememberSaveable(prompt) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(prompt, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = learnerAnswer,
            onValueChange = { learnerAnswer = it.take(600); correct = false },
            modifier = Modifier.fillMaxWidth().testTag("recall_answer"),
            label = { Text("Votre réponse") },
            minLines = 2
        )
        if (hintVisible) {
            Text("Indice : ${answer.take((answer.length / 2).coerceAtLeast(1))}…")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                if (!hintVisible) onHint()
                hintVisible = true
            }) {
                Icon(Icons.Rounded.Lightbulb, contentDescription = null)
                Text("Indice")
            }
        }
        if (correct) {
            SuccessSurface("Correct.")
            ContinueButton(onContinue)
        } else {
            FilledTonalButton(
                onClick = {
                    if (answersEquivalent(learnerAnswer, answer) ||
                        normalizeAnswer(learnerAnswer).contains(normalizeAnswer(answer))
                    ) correct = true else onMistake()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = learnerAnswer.isNotBlank()
            ) { Text("Vérifier") }
        }
    }
}

@Composable
private fun FinalMixedStep(
    exercise: Exercise,
    onMistake: () -> Unit,
    onHint: () -> Unit,
    completion: ExerciseCompletion?,
    onComplete: () -> Unit,
    onRateDifficulty: (Int) -> Unit
) {
    if (completion != null) {
        CompletionSummary(completion, onRateDifficulty)
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
    val sentenceParts = plan.finalSentence.split("___")
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Défi final · quatre mots manquants", style = MaterialTheme.typography.titleMedium)
        Text(
            "Écrivez vous-même les deux mots appris et glissez les autres mots.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            plan.finalAnswers.indices.forEach { index ->
                Text(sentenceParts.getOrElse(index) { "" })
                if (index in learnedIndexes) {
                    OutlinedTextField(
                        value = typed[index].orEmpty(),
                        onValueChange = { typed = typed + (index to it.take(80)) },
                        modifier = Modifier
                            .size(width = 130.dp, height = 64.dp)
                            .testTag("final_typed_$index"),
                        label = { Text("Écrire") },
                        singleLine = true
                    )
                } else {
                    DropTarget(
                        text = dropped[index] ?: "Déposer",
                        onBounds = { targets[index] = it }
                    )
                }
            }
            Text(sentenceParts.getOrElse(4) { "" })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fillerIndexes.map { plan.finalAnswers[it] }.distinct().forEach { filler ->
                DraggableWordChip(
                    text = filler,
                    targets = targets.filterKeys { index ->
                        plan.finalAnswers[index].equals(filler, ignoreCase = true)
                    },
                    onDroppedAt = { index -> dropped = dropped + (index to filler) }
                )
            }
        }
        if (hintVisible) {
            Text(
                "Indice : mots appris — ${plan.words.joinToString { it.text }}",
                color = MaterialTheme.colorScheme.primary
            )
        }
        TextButton(onClick = {
            if (!hintVisible) onHint()
            hintVisible = true
        }) {
            Icon(Icons.Rounded.Lightbulb, contentDescription = null)
            Text("Indice")
        }
        FilledTonalButton(
            onClick = {
                val learnedWordsAreCorrect = learnedIndexes.all { index ->
                    answersEquivalent(typed[index].orEmpty(), plan.finalAnswers[index])
                }
                if (learnedWordsAreCorrect) onComplete() else onMistake()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = learnedIndexes.all { typed[it].orEmpty().isNotBlank() } &&
                fillerIndexes.all { dropped[it].orEmpty().isNotBlank() }
        ) { Text("Terminer l'exercice") }
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
    Box {
        Confetti()
        Column(
            modifier = Modifier.bringIntoViewRequester(bringIntoViewRequester),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Bravo !", style = MaterialTheme.typography.headlineMedium)
            Text("Exercice terminé", style = MaterialTheme.typography.titleLarge)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = correctContainerColor()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Erreurs : ${completion.mistakes}")
                    Text("Temps : ${formatDuration(completion.elapsedMillis)}")
                    if (completion.hintsUsed > 0) Text("Indices utilisés : ${completion.hintsUsed}")
                }
            }
            Text("Difficulté de cet exercice", style = MaterialTheme.typography.titleMedium)
            Row {
                (1..5).forEach { rating ->
                    IconButton(onClick = { onRateDifficulty(rating) }) {
                        Icon(
                            imageVector = if (rating <= (completion.difficultyRating ?: 0)) {
                                Icons.Rounded.Star
                            } else {
                                Icons.Rounded.StarBorder
                            },
                            contentDescription = "$rating étoile${if (rating > 1) "s" else ""}",
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
private fun DropTarget(text: String, onBounds: (Rect) -> Unit) {
    Surface(
        modifier = Modifier
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .testTag("drop_target_$text"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
    }
}

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

@Composable
private fun ContinueButton(onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text("Continuer")
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

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes} min ${seconds} s" else "${seconds} s"
}
