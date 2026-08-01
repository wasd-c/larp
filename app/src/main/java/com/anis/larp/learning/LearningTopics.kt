package com.anis.larp.learning

import java.text.Normalizer
import java.util.Locale

internal const val APPROVED_TOPIC_TAGS_PROMPT =
    "Présentations, Famille, Routine, Maison, Cuisine, Politesse, Émotions, Opinions, " +
        "Invitations, Conflits, Transports, Aéroport, Hôtel, Directions, Tourisme, " +
        "Shopping, Restaurant, Argent, Administration, Technologie, École, Métiers, " +
        "Travail, Entretiens, Professionnel, Apparence, Santé, Médecine, Sport, " +
        "Urgences, Loisirs, Divertissement, Culture, Traditions, Jeux vidéo, Météo, " +
        "Nature, Lieux, Société, Avenir"

/** The only topic tags that can be persisted for exercises and lessons. */
object LearningTopics {
    val tags: List<String> = APPROVED_TOPIC_TAGS_PROMPT.split(", ")

    private val aliases = linkedMapOf(
        "Présentations" to listOf("presentation", "présentation", "introduction", "introduce", "se présenter"),
        "Famille" to listOf("famille", "family", "parent", "sibling"),
        "Routine" to listOf("routine", "daily", "quotidien", "every day"),
        "Maison" to listOf("maison", "home", "house", "apartment"),
        "Cuisine" to listOf("cuisine", "cooking", "kitchen", "recipe", "food"),
        "Politesse" to listOf("politesse", "polite", "manners", "courtesy"),
        "Émotions" to listOf("emotion", "émotion", "feeling", "sentiment"),
        "Opinions" to listOf("opinion", "debate", "débat", "argument"),
        "Invitations" to listOf("invitation", "invite"),
        "Conflits" to listOf("conflit", "conflict", "disagreement", "dispute"),
        "Transports" to listOf("transport", "train", "bus", "metro", "métro"),
        "Aéroport" to listOf("aéroport", "airport", "flight", "vol"),
        "Hôtel" to listOf("hôtel", "hotel", "room", "chambre"),
        "Directions" to listOf("direction", "itinéraire", "route", "turn left", "turn right"),
        "Tourisme" to listOf("tourisme", "tourism", "travel", "voyage", "sightseeing"),
        "Shopping" to listOf("shopping", "shop", "magasin", "acheter", "clothes"),
        "Restaurant" to listOf("restaurant", "café", "cafe", "menu", "meal"),
        "Argent" to listOf("argent", "money", "price", "bank", "budget"),
        "Administration" to listOf("administration", "paperwork", "formulaire", "official form"),
        "Technologie" to listOf("technologie", "technology", "computer", "internet", "phone"),
        "École" to listOf("école", "school", "student", "classroom", "education"),
        "Métiers" to listOf("métier", "job", "occupation", "profession"),
        "Travail" to listOf("travail", "work", "workplace", "office", "colleague"),
        "Entretiens" to listOf("entretien", "interview", "recruitment"),
        "Professionnel" to listOf("professionnel", "professional", "business", "meeting"),
        "Apparence" to listOf("apparence", "appearance", "look", "physical description"),
        "Santé" to listOf("santé", "health", "wellness", "healthy"),
        "Médecine" to listOf("médecine", "medicine", "doctor", "hospital", "pharmacy"),
        "Sport" to listOf("sport", "fitness", "football", "exercise"),
        "Urgences" to listOf("urgence", "emergency", "help", "police", "ambulance"),
        "Loisirs" to listOf("loisir", "hobby", "hobbies", "leisure", "free time"),
        "Divertissement" to listOf("divertissement", "entertainment", "movie", "cinema", "music"),
        "Culture" to listOf("culture", "art", "literature"),
        "Traditions" to listOf("tradition", "festival", "holiday", "coutume"),
        "Jeux vidéo" to listOf("jeu vidéo", "jeux vidéo", "video game", "gaming", "gamer"),
        "Météo" to listOf("météo", "weather", "rain", "sunny", "temperature"),
        "Nature" to listOf("nature", "environment", "animal", "plant", "outdoor"),
        "Lieux" to listOf("lieu", "place", "city", "town", "building"),
        "Société" to listOf("société", "society", "community", "social"),
        "Avenir" to listOf("avenir", "future", "plan", "goal", "dream")
    )

    fun choose(requested: String?, context: String = ""): String {
        val normalizedRequested = normalize(requested.orEmpty())
        tags.firstOrNull { normalize(it) == normalizedRequested }?.let { return it }

        val searchable = normalize(listOf(requested, context).joinToString(" "))
        return aliases.maxByOrNull { (tag, keywords) ->
            (keywords + tag).count { keyword ->
                normalize(keyword).let { it.isNotBlank() && searchable.contains(it) }
            }
        }?.takeIf { (tag, keywords) ->
            (keywords + tag).any { keyword ->
                normalize(keyword).let { value -> value.isNotBlank() && searchable.contains(value) }
            }
        }?.key ?: "Culture"
    }

    private fun normalize(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}
