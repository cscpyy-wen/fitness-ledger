package com.personal.fitnessledger.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.personal.fitnessledger.data.Nutrition
import com.personal.fitnessledger.data.UserProfile
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class FoodPortionCalculatorComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun convertingUsesFoodLabelButDoesNotOfferAutomaticLogging() {
        rule.setContent { MaterialTheme {
            FoodPortionCalculator(UserProfile().dailyTarget, Nutrition(carbsG = 180.0), LocalDate.of(2026, 9, 19), {})
        } }
        rule.onNodeWithTag("portion-food").performTextInput("熟米饭")
        rule.onNodeWithTag("portion-per100").performScrollTo().performTextInput("25.9")
        rule.onNodeWithTag("portion-result").performScrollTo().assertTextEquals("约 231.7 g 熟米饭")
        rule.onNodeWithText("确认并计入", substring = true).assertDoesNotExist()
    }

    @Test fun invalidFoodDensityCannotProduceResult() {
        rule.setContent { MaterialTheme {
            FoodPortionCalculator(UserProfile().dailyTarget, Nutrition(), LocalDate.of(2026, 9, 19), {})
        } }
        rule.onNodeWithTag("portion-food").performTextInput("熟米饭")
        rule.onNodeWithTag("portion-per100").performScrollTo().performTextInput("101")
        rule.onNodeWithTag("portion-result").assertDoesNotExist()
    }

    @Test fun reachingTargetDoesNotSuggestEatingAnExtraPortion() {
        rule.setContent { MaterialTheme {
            FoodPortionCalculator(UserProfile().dailyTarget, Nutrition(carbsG = 250.0), LocalDate.of(2026, 9, 19), {})
        } }
        rule.onNodeWithText("该项已达到或超过目标，无需为了凑数额外进食。").assertExists()
        rule.onNodeWithTag("portion-food").performTextInput("熟米饭")
        rule.onNodeWithTag("portion-per100").performScrollTo().performTextInput("25.9")
        rule.onNodeWithTag("portion-result").assertDoesNotExist()
    }
}
