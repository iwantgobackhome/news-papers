package app.fractal.ink

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.testng.Assert.assertEquals
import org.testng.annotations.Test

class InkPreferencesTest {
    private val legacyShape = stringPreferencesKey("shape")
    private val shapeV2 = stringPreferencesKey("shape_v2")

    @Test fun legacyShapeChoicesStartOnAuto() {
        for (mode in ShapeMode.entries) {
            assertEquals(persistedShapeMode(preferencesOf(legacyShape to mode.name)), ShapeMode.Auto)
        }
        assertEquals(persistedShapeMode(preferencesOf()), ShapeMode.Auto)
    }

    @Test fun explicitNewChoicesPersistAndOverrideLegacyKey() {
        for (mode in ShapeMode.entries) {
            assertEquals(persistedShapeMode(preferencesOf(legacyShape to "Line", shapeV2 to mode.name)), mode)
        }
    }

    @Test fun invalidNewPreferenceFallsBackToAuto() {
        assertEquals(persistedShapeMode(preferencesOf(legacyShape to "Line", shapeV2 to "unknown")), ShapeMode.Auto)
    }
}
