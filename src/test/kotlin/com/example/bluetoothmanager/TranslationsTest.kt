package com.example.bluetoothmanager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranslationsTest {
    @Test
    fun `all languages cover the same translation keys`() {
        AppLanguage.entries.forEach { language ->
            assertEquals(emptySet(), Translations.missingKeys(language), "Missing keys for $language")
        }
    }

    @Test
    fun `format placeholders match English in every language`() {
        // A missing or extra %s/%d makes String.format() crash at runtime.
        val placeholder = Regex("%(\\d+\\$)?[sd]")
        val english = Translations.translations(AppLanguage.EN)
        AppLanguage.entries.forEach { language ->
            Translations.translations(language).forEach { (key, value) ->
                val expected = placeholder.findAll(english.getValue(key)).map { it.value }.sorted().toList()
                val actual = placeholder.findAll(value).map { it.value }.sorted().toList()
                assertEquals(expected, actual, "Placeholders differ for $language:$key")
            }
        }
    }

    @Test
    fun `translations are not blank or mojibake`() {
        AppLanguage.entries.forEach { language ->
            Translations.translations(language).forEach { (key, value) ->
                assertTrue(value.isNotBlank(), "Blank translation for $language:$key")
                assertTrue(!value.contains("Ð") && !value.contains("Ñ"), "Broken encoding for $language:$key")
            }
        }
    }
}
