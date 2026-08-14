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
    fun `translations are not blank or mojibake`() {
        AppLanguage.entries.forEach { language ->
            Translations.translations(language).forEach { (key, value) ->
                assertTrue(value.isNotBlank(), "Blank translation for $language:$key")
                assertTrue(!value.contains("Ð") && !value.contains("Ñ"), "Broken encoding for $language:$key")
            }
        }
    }
}
