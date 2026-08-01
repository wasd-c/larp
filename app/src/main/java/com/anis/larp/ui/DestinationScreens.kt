package com.anis.larp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LocalLibrary
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.motionScheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import com.anis.larp.learning.Exercise
import com.anis.larp.learning.Lesson
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.launch

@Composable
fun ExercisesScreen(
    exercises: List<Exercise>,
    requestedOpenId: String? = null,
    onRequestedOpenHandled: () -> Unit = {},
    onArchive: (Exercise) -> Unit = {},
    onRemix: suspend (Exercise, String) -> Unit = { _, _ -> },
    onComplete: (Exercise, Int, Long, Int) -> Unit = { _, _, _, _ -> },
    onRateDifficulty: (Exercise, Int) -> Unit = { _, _ -> },
    onSpeakWord: suspend (String, String) -> Unit = { _, _ -> },
    onRecognizeAnswer: suspend (String) -> String = { "" },
    onImportText: suspend (String) -> Unit = {},
    onImportYoutube: suspend (String) -> Unit = {},
    onExerciseProgressChanged: (Boolean) -> Unit = {}
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var remixId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var difficultyFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var topicFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val searchRevealState = remember { LibrarySearchRevealState() }
    var importKind by rememberSaveable {
        mutableStateOf<ExerciseImportKind?>(null)
    }
    LaunchedEffect(requestedOpenId) {
        if (requestedOpenId != null && exercises.any { it.id == requestedOpenId }) {
            selectedId = requestedOpenId
            onRequestedOpenHandled()
        }
    }
    val remixTarget = exercises.firstOrNull { it.id == remixId }
    importKind?.let { kind ->
        ExerciseImportDialog(
            kind = kind,
            onDismiss = { importKind = null },
            onImport = when (kind) {
                ExerciseImportKind.Text -> onImportText
                ExerciseImportKind.Youtube -> onImportYoutube
            }
        )
    }
    if (remixTarget != null) {
        LearningContentRemixDialog(
            itemId = remixTarget.id,
            itemTitle = remixTarget.title,
            itemKind = "l'exercice",
            onDismiss = { remixId = null },
            onRemix = { guidance -> onRemix(remixTarget, guidance) }
        )
    }
    AnimatedContent(
        targetState = selectedId,
        transitionSpec = {
            if (targetState == null) {
                (fadeIn() + slideInHorizontally { -it / 5 }) togetherWith
                    (fadeOut() + slideOutHorizontally { it / 5 })
            } else {
                (fadeIn() + slideInHorizontally { it / 5 }) togetherWith
                    (fadeOut() + slideOutHorizontally { -it / 5 })
            }
        },
        label = "exercise_detail_library"
    ) { visibleId ->
        val visibleExercise = exercises.firstOrNull { it.id == visibleId }
        if (visibleExercise != null) {
            ExerciseDetail(
                exercise = visibleExercise,
                onBack = { selectedId = null },
                onComplete = { mistakes, elapsedMillis, hintsUsed ->
                    onComplete(visibleExercise, mistakes, elapsedMillis, hintsUsed)
                },
                onRateDifficulty = { rating ->
                    onRateDifficulty(visibleExercise, rating)
                },
                onSpeakWord = onSpeakWord,
                onRecognizeAnswer = onRecognizeAnswer,
                onExerciseProgressChanged = onExerciseProgressChanged,
                onFilterRequested = { filter ->
                    when (filter.kind) {
                        ExerciseFilterKind.DIFFICULTY -> difficultyFilter = filter.label
                        ExerciseFilterKind.TOPIC -> topicFilter = filter.label
                    }
                    searchRevealState.show()
                    selectedId = null
                }
            )
        } else {
            ExerciseLibrary(
                exercises = exercises,
                searchRevealState = searchRevealState,
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                difficultyFilter = difficultyFilter,
                topicFilter = topicFilter,
                onRemoveDifficultyFilter = { difficultyFilter = null },
                onRemoveTopicFilter = { topicFilter = null },
                onFilterRequested = { filter ->
                    when (filter.kind) {
                        ExerciseFilterKind.DIFFICULTY -> difficultyFilter = filter.label
                        ExerciseFilterKind.TOPIC -> topicFilter = filter.label
                    }
                    searchRevealState.show()
                },
                onOpen = { selectedId = it.id },
                onArchive = onArchive,
                onRemix = { remixId = it.id },
                onImport = { importKind = it }
            )
        }
    }
}

@Composable
fun LessonsScreen(
    lessons: List<Lesson>,
    requestedOpenId: String? = null,
    onRequestedOpenHandled: () -> Unit = {},
    onArchive: (Lesson) -> Unit = {},
    onRemix: suspend (Lesson, String) -> Unit = { _, _ -> }
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var remixId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var topicFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val searchRevealState = remember { LibrarySearchRevealState() }
    LaunchedEffect(requestedOpenId) {
        if (requestedOpenId != null && lessons.any { it.id == requestedOpenId }) {
            selectedId = requestedOpenId
            onRequestedOpenHandled()
        }
    }
    val remixTarget = lessons.firstOrNull { it.id == remixId }
    if (remixTarget != null) {
        LearningContentRemixDialog(
            itemId = remixTarget.id,
            itemTitle = remixTarget.title,
            itemKind = "la leçon",
            onDismiss = { remixId = null },
            onRemix = { guidance -> onRemix(remixTarget, guidance) }
        )
    }
    AnimatedContent(
        targetState = selectedId,
        transitionSpec = {
            if (targetState == null) {
                (fadeIn() + slideInHorizontally { -it / 5 }) togetherWith
                    (fadeOut() + slideOutHorizontally { it / 5 })
            } else {
                (fadeIn() + slideInHorizontally { it / 5 }) togetherWith
                    (fadeOut() + slideOutHorizontally { -it / 5 })
            }
        },
        label = "lesson_detail_library"
    ) { visibleId ->
        val visibleLesson = lessons.firstOrNull { it.id == visibleId }
        if (visibleLesson != null) {
            LessonDetail(
                lesson = visibleLesson,
                onBack = { selectedId = null },
                onTopicFilterRequested = { topic ->
                    topicFilter = topic
                    searchRevealState.show()
                    selectedId = null
                }
            )
        } else {
            LessonLibrary(
                lessons = lessons,
                searchRevealState = searchRevealState,
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                topicFilter = topicFilter,
                onRemoveTopicFilter = { topicFilter = null },
                onTopicFilterRequested = { topic ->
                    topicFilter = topic
                    searchRevealState.show()
                },
                onOpen = { selectedId = it.id },
                onArchive = onArchive,
                onRemix = { remixId = it.id }
            )
        }
    }
}

@Composable
private fun ExerciseLibrary(
    exercises: List<Exercise>,
    searchRevealState: LibrarySearchRevealState,
    query: String,
    onQueryChange: (String) -> Unit,
    difficultyFilter: String?,
    topicFilter: String?,
    onRemoveDifficultyFilter: () -> Unit,
    onRemoveTopicFilter: () -> Unit,
    onFilterRequested: (ExerciseFilter) -> Unit,
    onOpen: (Exercise) -> Unit,
    onArchive: (Exercise) -> Unit,
    onRemix: (Exercise) -> Unit,
    onImport: (ExerciseImportKind) -> Unit
) {
    val filteredExercises = exercises.filter { exercise ->
        val queryMatches = query.isBlank() || listOf(
                exercise.title,
                exercise.instructions,
                exercise.type.frenchLabel,
                languageName(exercise.languageTag)
            ).any { it.contains(query.trim(), ignoreCase = true) }
        val difficultyMatches = difficultyFilter == null ||
            exercise.difficulty.frenchLabel.equals(difficultyFilter, ignoreCase = true)
        val topicMatches = topicFilter == null ||
            exercise.topic.equals(topicFilter, ignoreCase = true)
        queryMatches && difficultyMatches && topicMatches
    }
    val unfinishedExercises = filteredExercises.filter { it.completion == null }
    val completedExercises = filteredExercises.filter { it.completion != null }
        .sortedByDescending { it.completion?.completedAtMillis }
    ContentLibrarySurface(searchRevealState = searchRevealState) {
        item {
            LibraryHeader(
                title = "Exercices",
                subtitle = "Créés avec votre tuteur vocal",
                count = exercises.size,
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.FitnessCenter,
                        contentDescription = null
                    )
                },
                trailingContent = {
                    ExerciseImportMenu(onImport = onImport)
                }
            )
        }
        item {
            SearchReveal(
                state = searchRevealState,
                query = query,
                onQueryChange = onQueryChange,
                placeholder = "Rechercher un exercice",
                filters = listOfNotNull(
                    difficultyFilter?.let {
                        ExerciseFilter(ExerciseFilterKind.DIFFICULTY, it)
                    },
                    topicFilter?.let {
                        ExerciseFilter(ExerciseFilterKind.TOPIC, it)
                    }
                ),
                onRemoveFilter = { filter ->
                    when (filter.kind) {
                        ExerciseFilterKind.DIFFICULTY -> onRemoveDifficultyFilter()
                        ExerciseFilterKind.TOPIC -> onRemoveTopicFilter()
                    }
                }
            )
        }
        if (exercises.isEmpty()) {
            item {
                VoiceCreationHint(
                    example = "« Crée-moi un exercice sur le passé composé. »"
                )
            }
        } else if (filteredExercises.isEmpty()) {
            item { EmptySearchResult(query) }
        } else {
            if (unfinishedExercises.isNotEmpty()) {
                item { LibrarySectionLabel("À faire", unfinishedExercises.size) }
            }
            items(unfinishedExercises, key = Exercise::id) { exercise ->
                LearningContentCard(
                    title = exercise.title,
                    supportingText = exercise.instructions,
                    categoryLabel = exercise.type.frenchLabel,
                    languageTag = exercise.languageTag,
                    suggestions = listOf(
                        ExerciseFilter(
                            ExerciseFilterKind.DIFFICULTY,
                            exercise.difficulty.frenchLabel
                        ),
                        ExerciseFilter(ExerciseFilterKind.TOPIC, exercise.topic)
                    ),
                    onSuggestionClick = onFilterRequested,
                    testTag = "exercise_${exercise.id}",
                    onClick = { onOpen(exercise) },
                    onArchive = { onArchive(exercise) },
                    onRemix = { onRemix(exercise) }
                )
            }
            if (completedExercises.isNotEmpty()) {
                item { LibrarySectionLabel("Terminés", completedExercises.size) }
            }
            items(completedExercises, key = Exercise::id) { exercise ->
                LearningContentCard(
                    title = exercise.title,
                    supportingText = exercise.instructions,
                    categoryLabel = exercise.type.frenchLabel,
                    languageTag = exercise.languageTag,
                    suggestions = listOf(
                        ExerciseFilter(
                            ExerciseFilterKind.DIFFICULTY,
                            exercise.difficulty.frenchLabel
                        ),
                        ExerciseFilter(ExerciseFilterKind.TOPIC, exercise.topic)
                    ),
                    onSuggestionClick = onFilterRequested,
                    completed = true,
                    testTag = "exercise_${exercise.id}",
                    onClick = { onOpen(exercise) },
                    onArchive = { onArchive(exercise) },
                    onRemix = { onRemix(exercise) }
                )
            }
        }
    }
}

@Composable
private fun LibrarySectionLabel(label: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = count.toString(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun LessonLibrary(
    lessons: List<Lesson>,
    searchRevealState: LibrarySearchRevealState,
    query: String,
    onQueryChange: (String) -> Unit,
    topicFilter: String?,
    onRemoveTopicFilter: () -> Unit,
    onTopicFilterRequested: (String) -> Unit,
    onOpen: (Lesson) -> Unit,
    onArchive: (Lesson) -> Unit,
    onRemix: (Lesson) -> Unit
) {
    val filteredLessons = lessons.filter { lesson ->
        val queryMatches = query.isBlank() || listOf(
            lesson.title,
            lesson.objective,
            lesson.content,
            languageName(lesson.languageTag)
        ).any { it.contains(query.trim(), ignoreCase = true) }
        val topicMatches = topicFilter == null ||
            lesson.topic.equals(topicFilter, ignoreCase = true)
        queryMatches && topicMatches
    }
    ContentLibrarySurface(searchRevealState = searchRevealState) {
        item {
            LibraryHeader(
                title = "Leçons",
                subtitle = "Préparées selon vos demandes",
                count = lessons.size,
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.LocalLibrary,
                        contentDescription = null
                    )
                }
            )
        }
        item {
            SearchReveal(
                state = searchRevealState,
                query = query,
                onQueryChange = onQueryChange,
                placeholder = "Rechercher une leçon",
                filters = listOfNotNull(
                    topicFilter?.let {
                        ExerciseFilter(ExerciseFilterKind.TOPIC, it)
                    }
                ),
                onRemoveFilter = { onRemoveTopicFilter() }
            )
        }
        if (lessons.isEmpty()) {
            item {
                VoiceCreationHint(
                    example = "« Prépare une leçon sur les salutations. »"
                )
            }
        } else if (filteredLessons.isEmpty()) {
            item { EmptySearchResult(query) }
        } else {
            items(filteredLessons, key = Lesson::id) { lesson ->
                LearningContentCard(
                    title = lesson.title,
                    supportingText = lesson.objective,
                    languageTag = lesson.languageTag,
                    suggestions = listOf(
                        ExerciseFilter(ExerciseFilterKind.TOPIC, lesson.topic)
                    ),
                    onSuggestionClick = { onTopicFilterRequested(it.label) },
                    testTag = "lesson_${lesson.id}",
                    onClick = { onOpen(lesson) },
                    onArchive = { onArchive(lesson) },
                    onRemix = { onRemix(lesson) }
                )
            }
        }
    }
}

@Composable
private fun LibraryHeader(
    title: String,
    subtitle: String,
    count: Int,
    icon: @Composable () -> Unit,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Row(modifier = Modifier.padding(18.dp)) { icon() }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (trailingContent != null) {
            trailingContent()
        } else {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = count.toString(),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

private enum class ExerciseImportKind {
    Text,
    Youtube
}

@Composable
private fun ExerciseImportMenu(
    onImport: (ExerciseImportKind) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        FilledTonalIconButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag("exercise_import_menu")
        ) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = "Importer un exercice"
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            Text(
                text = "Import",
                modifier = Modifier.padding(
                    start = 16.dp,
                    top = 8.dp,
                    end = 16.dp,
                    bottom = 4.dp
                ),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge
            )
            DropdownMenuItem(
                text = { Text("Text") },
                onClick = {
                    expanded = false
                    onImport(ExerciseImportKind.Text)
                },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Rounded.Article, contentDescription = null)
                },
                modifier = Modifier.testTag("import_exercise_text")
            )
            DropdownMenuItem(
                text = { Text("YouTube video") },
                onClick = {
                    expanded = false
                    onImport(ExerciseImportKind.Youtube)
                },
                leadingIcon = {
                    Icon(Icons.Rounded.SmartDisplay, contentDescription = null)
                },
                modifier = Modifier.testTag("import_exercise_youtube")
            )
        }
    }
}

@Composable
private fun ExerciseImportDialog(
    kind: ExerciseImportKind,
    onDismiss: () -> Unit,
    onImport: suspend (String) -> Unit
) {
    val isTextImport = kind == ExerciseImportKind.Text
    var source by rememberSaveable(kind) { mutableStateOf("") }
    var isImporting by rememberSaveable(kind) { mutableStateOf(false) }
    var errorMessage by rememberSaveable(kind) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val maxLength = if (isTextImport) {
        MAX_IMPORTED_TEXT_LENGTH
    } else {
        MAX_YOUTUBE_URL_LENGTH
    }
    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = {
            Text(if (isTextImport) "Importer un texte" else "Importer une vidéo YouTube")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = if (isTextImport) {
                        "Collez un extrait : le modèle sélectionné en fera un exercice de compréhension et de vocabulaire."
                    } else {
                        "Collez le lien d'une vidéo avec des sous-titres. Sa transcription servira à créer un quiz."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = source,
                    onValueChange = {
                        source = it.take(maxLength)
                        errorMessage = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(
                            if (isTextImport) {
                                "exercise_import_text_input"
                            } else {
                                "exercise_import_youtube_input"
                            }
                        ),
                    enabled = !isImporting,
                    label = {
                        Text(if (isTextImport) "Texte source" else "Lien YouTube")
                    },
                    placeholder = {
                        Text(
                            if (isTextImport) {
                                "Collez ici le passage à étudier"
                            } else {
                                "https://www.youtube.com/watch?v=…"
                            }
                        )
                    },
                    supportingText = {
                        if (isTextImport) {
                            Text("${source.length} / $MAX_IMPORTED_TEXT_LENGTH")
                        }
                    },
                    minLines = if (isTextImport) 5 else 1,
                    maxLines = if (isTextImport) 9 else 2,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (isTextImport) {
                            KeyboardType.Text
                        } else {
                            KeyboardType.Uri
                        }
                    )
                )
                if (isImporting) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(
                            text = if (isTextImport) {
                                "Le modèle sélectionné crée l'exercice…"
                            } else {
                                "Transcription puis création du quiz…"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (source.isBlank()) {
                        errorMessage = if (isTextImport) {
                            "Collez le texte à transformer en exercice."
                        } else {
                            "Collez le lien de la vidéo YouTube."
                        }
                        return@TextButton
                    }
                    isImporting = true
                    errorMessage = null
                    scope.launch {
                        runCatching { onImport(source.trim()) }
                            .onSuccess { onDismiss() }
                            .onFailure { error ->
                                errorMessage = error.message ?: if (isTextImport) {
                                    "L'exercice n'a pas pu être créé."
                                } else {
                                    "La vidéo n'a pas pu être importée."
                                }
                                isImporting = false
                            }
                    }
                },
                enabled = !isImporting,
                modifier = Modifier.testTag("confirm_exercise_import")
            ) {
                Text(if (isTextImport) "Créer l'exercice" else "Créer le quiz")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isImporting) {
                Text("Annuler")
            }
        },
        shape = MaterialTheme.shapes.extraLarge
    )
}

@Composable
private fun VoiceCreationHint(example: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Rounded.RecordVoiceOver,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Demandez-le à larp",
                modifier = Modifier.padding(top = 14.dp),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = example,
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LearningContentCard(
    title: String,
    supportingText: String,
    categoryLabel: String? = null,
    languageTag: String,
    suggestions: List<ExerciseFilter> = emptyList(),
    onSuggestionClick: (ExerciseFilter) -> Unit = {},
    completed: Boolean = false,
    testTag: String,
    onClick: () -> Unit,
    onArchive: () -> Unit,
    onRemix: () -> Unit
) {
    val swipeState = rememberSaveable(saver = AnchoredDraggableState.Saver()) {
        AnchoredDraggableState(initialValue = LearningContentReveal.Settled)
    }
    val scope = rememberCoroutineScope()
    val remixGreen = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Color(0xFF81C784)
    } else {
        Color(0xFF2E7D32)
    }
    val flingBehavior = AnchoredDraggableDefaults.flingBehavior(
        state = swipeState,
        positionalThreshold = { distance -> distance * 0.45f }
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .onSizeChanged { size ->
                val remixRevealDistance =
                    size.width * LEARNING_CONTENT_REMIX_REVEAL_FRACTION
                val archiveRevealDistance =
                    size.width * LEARNING_CONTENT_ARCHIVE_REVEAL_FRACTION
                swipeState.updateAnchors(
                    DraggableAnchors {
                        LearningContentReveal.Remix at -remixRevealDistance
                        LearningContentReveal.Settled at 0f
                        LearningContentReveal.Archive at archiveRevealDistance
                    }
                )
            }
    ) {
        val offset = swipeState.offset.takeUnless(Float::isNaN) ?: 0f
        Box(modifier = Modifier.matchParentSize()) {
            when {
                offset > 0f -> TextButton(
                        onClick = onArchive,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(horizontal = 10.dp)
                            .testTag("archive_$testTag"),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Archive")
                    }

                offset < 0f -> TextButton(
                        onClick = {
                            onRemix()
                            scope.launch {
                                swipeState.animateTo(LearningContentReveal.Settled)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(horizontal = 10.dp)
                            .testTag("remix_$testTag"),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = remixGreen
                        )
                    ) {
                        Text("Remix")
                    }
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("content_$testTag")
                .offset {
                    IntOffset(
                        x = (swipeState.offset.takeUnless(Float::isNaN) ?: 0f)
                            .roundToInt(),
                        y = 0
                    )
                }
                .anchoredDraggable(
                    state = swipeState,
                    orientation = Orientation.Horizontal,
                    flingBehavior = flingBehavior
                )
                .clickable(onClick = onClick),
            shape = MaterialTheme.shapes.extraLarge,
            color = if (completed) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = supportingText,
                        modifier = Modifier.padding(top = 6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2
                    )
                    Text(
                        text = listOfNotNull(
                            categoryLabel,
                            languageName(languageTag)
                        ).joinToString(" · "),
                        modifier = Modifier.padding(top = 10.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge
                    )
                    if (suggestions.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            suggestions.distinct().forEach { suggestion ->
                                SuggestionChip(
                                    onClick = { onSuggestionClick(suggestion) },
                                    label = { Text(suggestion.label) }
                                )
                            }
                        }
                    }
                }
                Icon(
                    imageVector = if (completed) {
                        Icons.Rounded.CheckCircle
                    } else {
                        Icons.AutoMirrored.Rounded.ArrowForward
                    },
                    contentDescription = if (completed) {
                        "$title terminé"
                    } else {
                        "Ouvrir $title"
                    },
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private enum class LearningContentReveal {
    Remix,
    Settled,
    Archive
}

private enum class ExerciseFilterKind {
    DIFFICULTY,
    TOPIC
}

private data class ExerciseFilter(
    val kind: ExerciseFilterKind,
    val label: String
)

@Composable
private fun LearningContentRemixDialog(
    itemId: String,
    itemTitle: String,
    itemKind: String,
    onDismiss: () -> Unit,
    onRemix: suspend (String) -> Unit
) {
    var guidance by rememberSaveable(itemId) { mutableStateOf("") }
    var isRemixing by rememberSaveable(itemId) { mutableStateOf(false) }
    var errorMessage by rememberSaveable(itemId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!isRemixing) onDismiss() },
        title = { Text("Remixer « $itemTitle »") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Décrivez ce que vous voulez changer. Une nouvelle version sera ajoutée ; l'original restera disponible.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = guidance,
                    onValueChange = {
                        guidance = it.take(MAX_REMIX_PROMPT_LENGTH)
                        errorMessage = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("remix_prompt_$itemId"),
                    enabled = !isRemixing,
                    label = { Text("Prompt pour $itemKind") },
                    placeholder = {
                        Text("Ex. Plus difficile, avec du vocabulaire de voyage")
                    },
                    minLines = 3,
                    maxLines = 6
                )
                if (isRemixing) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(
                            "Le modèle sélectionné prépare le remix…",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (guidance.isBlank()) {
                        errorMessage = "Ajoutez un prompt pour guider le remix."
                        return@TextButton
                    }
                    isRemixing = true
                    errorMessage = null
                    scope.launch {
                        runCatching { onRemix(guidance.trim()) }
                            .onSuccess { onDismiss() }
                            .onFailure { error ->
                                errorMessage = error.message
                                    ?: "Le remix n'a pas pu être créé."
                                isRemixing = false
                            }
                    }
                },
                enabled = !isRemixing,
                modifier = Modifier.testTag("confirm_remix_$itemId"),
                colors = ButtonDefaults.textButtonColors(contentColor = remixActionGreen())
            ) {
                Text("Créer le remix")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isRemixing) {
                Text("Annuler")
            }
        },
        shape = MaterialTheme.shapes.extraLarge
    )
}

@Composable
private fun remixActionGreen(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Color(0xFF81C784)
    } else {
        Color(0xFF2E7D32)
    }

@Composable
private fun ExerciseDetail(
    exercise: Exercise,
    onBack: () -> Unit,
    onComplete: (Int, Long, Int) -> Unit,
    onRateDifficulty: (Int) -> Unit,
    onSpeakWord: suspend (String, String) -> Unit,
    onRecognizeAnswer: suspend (String) -> String,
    onExerciseProgressChanged: (Boolean) -> Unit,
    onFilterRequested: (ExerciseFilter) -> Unit
) {
    var hasUnsavedProgress by remember(exercise.id) { mutableStateOf(false) }
    var pendingExitAction by remember(exercise.id) {
        mutableStateOf<(() -> Unit)?>(null)
    }
    fun requestExit(action: () -> Unit) {
        if (hasUnsavedProgress) {
            pendingExitAction = action
        } else {
            action()
        }
    }
    fun confirmExit() {
        val action = pendingExitAction ?: return
        pendingExitAction = null
        hasUnsavedProgress = false
        onExerciseProgressChanged(false)
        action()
    }

    BackHandler(enabled = pendingExitAction == null) {
        requestExit(onBack)
    }
    if (pendingExitAction != null) {
        UnsavedExerciseExitDialog(
            onDismiss = { pendingExitAction = null },
            onConfirm = ::confirmExit
        )
    }
    ContentDetailSurface(
        title = exercise.title,
        languageTag = exercise.languageTag,
        onBack = { requestExit(onBack) }
    ) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SuggestionChip(
                    onClick = {
                        requestExit {
                            onFilterRequested(
                                ExerciseFilter(
                                    ExerciseFilterKind.DIFFICULTY,
                                    exercise.difficulty.frenchLabel
                                )
                            )
                        }
                    },
                    label = { Text(exercise.difficulty.frenchLabel) }
                )
                SuggestionChip(
                    onClick = {
                        requestExit {
                            onFilterRequested(
                                ExerciseFilter(ExerciseFilterKind.TOPIC, exercise.topic)
                            )
                        }
                    },
                    label = { Text(exercise.topic) }
                )
            }
        }
        item {
            ExercisePlayer(
                exercise = exercise,
                onSpeakWord = onSpeakWord,
                onRecognizeAnswer = onRecognizeAnswer,
                onComplete = onComplete,
                onRateDifficulty = onRateDifficulty,
                onProgressChanged = {
                    hasUnsavedProgress = it
                    onExerciseProgressChanged(it)
                }
            )
        }
    }
}

@Composable
internal fun UnsavedExerciseExitDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quitter l’exercice ?") },
        text = {
            Text(
                "Votre progression actuelle n’est pas enregistrée. " +
                    "Si vous quittez maintenant, vous devrez recommencer cet exercice."
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Quitter")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Continuer l’exercice")
            }
        },
        shape = MaterialTheme.shapes.extraLarge
    )
}

@Composable
private fun LessonDetail(
    lesson: Lesson,
    onBack: () -> Unit,
    onTopicFilterRequested: (String) -> Unit
) {
    ContentDetailSurface(
        title = lesson.title,
        languageTag = lesson.languageTag,
        onBack = onBack
    ) {
        item {
            SuggestionChip(
                onClick = { onTopicFilterRequested(lesson.topic) },
                label = { Text(lesson.topic) }
            )
        }
        item { DetailSection("Objectif", lesson.objective, emphasized = true) }
        item { DetailSection("Leçon", lesson.content) }
    }
}

@Composable
private fun ContentDetailSurface(
    title: String,
    languageTag: String,
    onBack: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    ContentLibrarySurface {
        item {
            FilledTonalIconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Retour"
                )
            }
            Surface(
                modifier = Modifier.padding(top = 22.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    imageVector = Icons.Rounded.School,
                    contentDescription = null,
                    modifier = Modifier.padding(18.dp)
                )
            }
            Text(
                text = title,
                modifier = Modifier.padding(top = 18.dp),
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = languageName(languageTag),
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge
            )
        }
        content()
    }
}

@Composable
private fun DetailSection(
    label: String,
    text: String,
    emphasized: Boolean = false
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (emphasized) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = text,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String
) {
    DockedSearchBar(
        query = query,
        onQueryChange = onQueryChange,
        onSearch = {},
        active = false,
        onActiveChange = {},
        modifier = Modifier
            .fillMaxWidth()
            .testTag("library_search"),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        content = {}
    )
}

@Stable
private class LibrarySearchRevealState {
    var progress by mutableFloatStateOf(0f)

    fun show() {
        progress = 1f
    }
}

@Composable
private fun SearchReveal(
    state: LibrarySearchRevealState,
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    filters: List<ExerciseFilter> = emptyList(),
    onRemoveFilter: (ExerciseFilter) -> Unit = {}
) {
    val revealProgress = state.progress.coerceIn(0f, 1f)
    val expandedHeight = if (filters.isEmpty()) 72.dp else 124.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(expandedHeight * revealProgress)
            .clipToBounds()
            .graphicsLayer {
                alpha = revealProgress
                translationY = -size.height * (1f - revealProgress) * 0.2f
            }
            .testTag("search_reveal")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LibrarySearchBar(
                query = query,
                onQueryChange = onQueryChange,
                placeholder = placeholder
            )
            if (filters.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    filters.forEach { filter ->
                        InputChip(
                            selected = true,
                            onClick = { onRemoveFilter(filter) },
                            label = { Text(filter.label) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription =
                                        "Retirer le filtre ${filter.label}"
                                )
                            },
                            modifier = Modifier.testTag(
                                "active_filter_${filter.kind.name.lowercase()}"
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptySearchResult(query: String) {
    Text(
        text = if (query.isBlank()) {
            "Aucun contenu ne correspond à ces filtres"
        } else {
            "Aucun résultat pour « $query »"
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun ContentLibrarySurface(
    searchRevealState: LibrarySearchRevealState? = null,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    val statusBarPadding =
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val listState = rememberLazyListState()
    val revealDistancePx = with(LocalDensity.current) {
        SEARCH_REVEAL_DRAG_DISTANCE.toPx()
    }
    val revealSettleSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val nestedScrollConnection = remember(
        listState,
        searchRevealState,
        revealDistancePx,
        revealSettleSpec
    ) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val reveal = searchRevealState ?: return Offset.Zero
                if (
                    source != NestedScrollSource.UserInput ||
                    listState.canScrollBackward ||
                    available.y <= 0f ||
                    reveal.progress >= 1f
                ) {
                    return Offset.Zero
                }
                val consumedY = min(
                    available.y,
                    (1f - reveal.progress) * revealDistancePx
                )
                reveal.progress = (reveal.progress + consumedY / revealDistancePx)
                    .coerceIn(0f, 1f)
                return Offset(x = 0f, y = consumedY)
            }

            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val reveal = searchRevealState ?: return Offset.Zero
                if (
                    source != NestedScrollSource.UserInput ||
                    available.y >= 0f ||
                    reveal.progress <= 0f ||
                    reveal.progress >= 1f
                ) {
                    return Offset.Zero
                }
                val consumedY = max(
                    available.y,
                    -reveal.progress * revealDistancePx
                )
                reveal.progress = (reveal.progress + consumedY / revealDistancePx)
                    .coerceIn(0f, 1f)
                return Offset(x = 0f, y = consumedY)
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity
            ): Velocity {
                val reveal = searchRevealState ?: return Velocity.Zero
                if (reveal.progress > 0f && reveal.progress < 1f) {
                    val target = if (reveal.progress >= SEARCH_REVEAL_THRESHOLD) {
                        1f
                    } else {
                        0f
                    }
                    animate(
                        initialValue = reveal.progress,
                        targetValue = target,
                        animationSpec = revealSettleSpec
                    ) { value, _ ->
                        reveal.progress = value
                    }
                }
                return Velocity.Zero
            }
        }
    }
    val listContent = @Composable {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection)
                .testTag("content_library_list"),
            state = listState,
            contentPadding = PaddingValues(
                start = 20.dp,
                top = statusBarPadding + 22.dp,
                end = 20.dp,
                bottom = 124.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        listContent()
    }
}

private fun languageName(languageTag: String): String {
    val name = Locale.forLanguageTag(languageTag)
        .getDisplayLanguage(Locale.FRENCH)
        .ifBlank { languageTag }
    return name.replaceFirstChar { character ->
        if (character.isLowerCase()) character.titlecase(Locale.FRENCH)
        else character.toString()
    }
}

private const val MAX_REMIX_PROMPT_LENGTH = 1_000
private val SEARCH_REVEAL_DRAG_DISTANCE = 112.dp
private const val SEARCH_REVEAL_THRESHOLD = 0.2f
private const val MAX_IMPORTED_TEXT_LENGTH = 4_200
private const val MAX_YOUTUBE_URL_LENGTH = 500
private const val LEARNING_CONTENT_REMIX_REVEAL_FRACTION = 0.20f
private const val LEARNING_CONTENT_ARCHIVE_REVEAL_FRACTION = 0.24f

@Composable
fun ProfileScreen(
    dictionaryOpen: Boolean,
    onOpenDictionary: () -> Unit,
    onCloseDictionary: () -> Unit,
    exercises: List<Exercise> = emptyList(),
    onOpenExercise: (String) -> Unit = {}
) {
    if (dictionaryOpen) {
        DictionaryScreen(
            exercises = exercises,
            onBack = onCloseDictionary,
            onOpenExercise = onOpenExercise
        )
        return
    }

    ScreenSurface {
        Icon(
            imageVector = Icons.Rounded.Person,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Profil",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "Outils personnels et préférences",
            modifier = Modifier.padding(top = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(30.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("profile_dictionary")
                .clickable(onClick = onOpenDictionary),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = "Dictionnaire",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${dictionaryEntries(exercises).size} mots appris",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = "Ouvrir le dictionnaire"
                )
            }
        }
    }
}

@Composable
private fun DictionaryScreen(
    exercises: List<Exercise>,
    onBack: () -> Unit,
    onOpenExercise: (String) -> Unit
) {
    val entries = dictionaryEntries(exercises)
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedEntry = entries.firstOrNull { it.key == selected }
    if (selectedEntry != null) {
        ScreenSurface(horizontalAlignment = Alignment.Start) {
            FilledTonalIconButton(onClick = { selected = null }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour au dictionnaire")
            }
            Spacer(Modifier.height(22.dp))
            Text(selectedEntry.word, style = MaterialTheme.typography.headlineMedium)
            Text(
                selectedEntry.pronunciation,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(selectedEntry.definition, modifier = Modifier.padding(top = 18.dp))
            Text(
                "Date learned: ${DateFormat.getDateInstance().format(Date(selectedEntry.learnedAtMillis))}",
                modifier = Modifier.padding(top = 18.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(
                onClick = { onOpenExercise(selectedEntry.exerciseId) },
                modifier = Modifier.fillMaxWidth().padding(top = 22.dp)
            ) {
                Text("Revenir à l'exercice")
            }
        }
        return
    }
    ContentLibrarySurface {
        item {
            FilledTonalIconButton(
                onClick = onBack,
                modifier = Modifier.testTag("dictionary_back")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Retour au profil"
                )
            }
            Text(
                text = "Dictionnaire",
                modifier = Modifier.padding(top = 22.dp),
                style = MaterialTheme.typography.headlineMedium
            )
        }
        if (entries.isEmpty()) {
            item {
                Text(
                    text = "Terminez un exercice pour ajouter ses mots appris.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            items(entries, key = DictionaryEntry::key) { entry ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { selected = entry.key },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.word, style = MaterialTheme.typography.titleMedium)
                            Text(
                                entry.definition,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = "Ouvrir ${entry.word}"
                        )
                    }
                }
            }
        }
    }
}

private data class DictionaryEntry(
    val word: String,
    val pronunciation: String,
    val definition: String,
    val learnedAtMillis: Long,
    val exerciseId: String
) {
    val key: String get() = "${word.lowercase()}@$exerciseId"
}

private fun dictionaryEntries(exercises: List<Exercise>): List<DictionaryEntry> = exercises
    .mapNotNull { exercise ->
        exercise.completion?.let { completion -> exercise to completion }
    }
    .flatMap { (exercise, completion) ->
        exercise.plan.words.map { word ->
            DictionaryEntry(
                word = word.text,
                pronunciation = word.pronunciation,
                definition = word.definition,
                learnedAtMillis = completion.completedAtMillis,
                exerciseId = exercise.id
            )
        }
    }
    .distinctBy { it.word.lowercase() }
    .sortedBy(DictionaryEntry::word)

@Composable
private fun DestinationStatusScreen(
    title: String,
    description: String,
    icon: @Composable () -> Unit
) {
    ScreenSurface {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Column(
                modifier = Modifier.padding(30.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                icon()
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = description,
            modifier = Modifier.padding(top = 10.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ScreenSurface(
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    content: @Composable ColumnScope.() -> Unit
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = 24.dp,
                    top = statusBarPadding + 28.dp,
                    end = 24.dp,
                    bottom = 120.dp
                ),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = Arrangement.Top,
            content = content
        )
    }
}
