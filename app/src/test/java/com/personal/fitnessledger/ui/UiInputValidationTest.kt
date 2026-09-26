package com.personal.fitnessledger.ui

import com.personal.fitnessledger.data.Exercise
import com.personal.fitnessledger.data.BodyMeasurementFormDraft
import com.personal.fitnessledger.data.PersonalRecord
import com.personal.fitnessledger.data.PrEventKind
import com.personal.fitnessledger.data.PrType
import com.personal.fitnessledger.data.TrackingType
import com.personal.fitnessledger.data.WorkoutHistorySummary
import com.personal.fitnessledger.data.WorkoutSession
import com.personal.fitnessledger.data.WorkoutStatus
import com.personal.fitnessledger.data.WorkoutSet
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiInputValidationTest {
    @Test
    fun `rest countdown expires instead of freezing after wall clock rollback`() {
        val now = 1_000_000L

        assertEquals(0, safeRestRemainingSeconds(now + 86_400_000L, now, 120))
        assertEquals(91, safeRestRemainingSeconds(now + 90_001L, now, 120))
        assertEquals(0, safeRestRemainingSeconds(now - 1L, now, 120))
        assertEquals(0, safeRestRemainingSeconds(now + 99_000L, now, 1))
        assertEquals(0, safeRestRemainingSeconds(now + 9_000_000L, now, 9_999))
    }

    @Test
    fun `foreground workout and rest clocks advance monotonically across wall rollback`() {
        val wallNow = 10_000_000L
        val elapsedNow = 500_000L
        val anchor = workoutClockAnchor(
            session = WorkoutSession(
                id = 1L,
                startedAtMillis = wallNow + 86_400_000L,
                title = "回拨中的训练",
                restTimerEndAtMillis = wallNow + 120_000L,
                restDurationSeconds = 120,
            ),
            wallNowMillis = wallNow,
            elapsedRealtimeMillis = elapsedNow,
        )

        // A future persisted start is treated as zero at process start, then the
        // foreground timer progresses from elapsedRealtime rather than waiting a day.
        assertEquals(0L, monotonicWorkoutElapsedSeconds(anchor, elapsedNow))
        assertEquals(1L, monotonicWorkoutElapsedSeconds(anchor, elapsedNow + 1_000L))
        assertEquals(7L, monotonicWorkoutElapsedSeconds(anchor, elapsedNow + 7_000L))

        assertEquals(120, monotonicRestRemainingSeconds(anchor, elapsedNow))
        assertEquals(119, monotonicRestRemainingSeconds(anchor, elapsedNow + 1_000L))
        assertEquals(113, monotonicRestRemainingSeconds(anchor, elapsedNow + 7_000L))
        assertEquals(0, monotonicRestRemainingSeconds(anchor, elapsedNow + 121_000L))
    }

    @Test
    fun `workout completion never replaces a different loaded calendar date`() {
        val loadedDate = LocalDate.of(2026, 8, 30)
        val current = emptyList<com.personal.fitnessledger.data.WorkoutHistorySummary>()
        val nextDateRows = listOf(
            com.personal.fitnessledger.data.WorkoutHistorySummary(
                session = com.personal.fitnessledger.data.WorkoutSession(
                    id = 7L,
                    title = "跨日训练",
                    startedAtMillis = 1L,
                    endedAtMillis = 2L,
                    status = com.personal.fitnessledger.data.WorkoutStatus.COMPLETED,
                    recordedLocalDate = loadedDate.plusDays(1),
                    recordedZoneId = "Asia/Shanghai",
                ),
                exerciseNames = listOf("深蹲"),
                completedSetCount = 1,
                totalVolumeKg = 100.0,
                failedSetCount = 0,
            ),
        )

        assertEquals(
            current,
            completedWorkoutsForLoadedDate(
                loadedDate = loadedDate,
                recordedDate = loadedDate.plusDays(1),
                current = current,
                completedForRecordedDate = nextDateRows,
            ),
        )
        assertEquals(
            nextDateRows,
            completedWorkoutsForLoadedDate(
                loadedDate = loadedDate.plusDays(1),
                recordedDate = loadedDate.plusDays(1),
                current = current,
                completedForRecordedDate = nextDateRows,
            ),
        )
    }

    @Test
    fun `body editor update applies to latest draft and preserves database identity`() {
        val current = BodyMeasurementFormDraft(
            id = "body-form",
            measurementId = 91L,
            date = LocalDate.of(2026, 8, 20),
            weightText = "80",
            waistText = "88",
            shortcutRequestId = "shortcut-body-form",
            revision = 6L,
            updatedAtMillis = 6_000L,
        )

        val updated = requireNotNull(
            mergeBodyMeasurementFormUpdate(
                current = current,
                expectedFormId = current.id,
                transform = { stale ->
                    stale.copy(
                        id = "forged-id",
                        measurementId = 2L,
                        shortcutRequestId = "forged-shortcut",
                        weightText = "79.",
                    )
                },
                updatedAtMillis = 5_000L,
            ),
        )

        assertEquals("body-form", updated.id)
        assertEquals(91L, updated.measurementId)
        assertEquals("shortcut-body-form", updated.shortcutRequestId)
        assertEquals("79.", updated.weightText)
        assertEquals(7L, updated.revision)
        assertEquals(6_001L, updated.updatedAtMillis)
    }

    @Test
    fun `body event from an old form cannot mutate the current form`() {
        val current = BodyMeasurementFormDraft(
            id = "body-form-b",
            date = LocalDate.of(2026, 8, 30),
            weightText = "79",
            waistText = "85",
        )

        assertNull(
            mergeBodyMeasurementFormUpdate(
                current = current,
                expectedFormId = "body-form-a",
                transform = { it.copy(weightText = "120") },
            ),
        )
        assertEquals("79", current.weightText)
    }

    @Test
    fun `today body shortcut opens the first measurement editor but preserves trend access later`() {
        assertEquals(
            TodayBodyQuickActionModel(label = "记录身体", opensNewMeasurement = true),
            todayBodyQuickActionModel(hasBodyMeasurements = false),
        )
        assertEquals(
            TodayBodyQuickActionModel(label = "身体趋势", opensNewMeasurement = false),
            todayBodyQuickActionModel(hasBodyMeasurements = true),
        )
    }

    @Test
    fun `workout date label uses stored calendar date without timezone reconstruction`() {
        assertEquals("2026-08-27", workoutDateLabel(LocalDate.of(2026, 8, 27)))
        assertEquals("日期未记录", workoutDateLabel(null))
    }

    @Test
    fun `single set form clears only after success revision advances`() {
        assertEquals(
            SingleSetSaveUiDecision.WAIT,
            singleSetSaveUiDecision(true, true, 7L, 7L),
        )
        assertEquals(
            SingleSetSaveUiDecision.RELEASE_WITHOUT_CLEAR,
            singleSetSaveUiDecision(true, false, 7L, 7L),
        )
        assertEquals(
            SingleSetSaveUiDecision.CLEAR_AFTER_SUCCESS,
            singleSetSaveUiDecision(true, false, 7L, 8L),
        )
        assertEquals(
            SingleSetSaveUiDecision.WAIT,
            singleSetSaveUiDecision(false, false, 7L, 8L),
        )
    }

    @Test
    fun `single set local lock blocks rapid repeat submission before recomposition`() {
        assertTrue(canSubmitSingleSet(isSaving = false, isLocallyLocked = false, isInputValid = true))
        assertFalse(canSubmitSingleSet(isSaving = false, isLocallyLocked = true, isInputValid = true))
        assertFalse(canSubmitSingleSet(isSaving = true, isLocallyLocked = false, isInputValid = true))
        assertFalse(canSubmitSingleSet(isSaving = false, isLocallyLocked = false, isInputValid = false))
    }

    @Test
    fun `new superset is immediately selectable and remains available after save reset`() {
        val newGroup = nextSupersetId(emptyList())

        assertEquals("A", newGroup)
        assertEquals(listOf("A"), selectableSupersetIds(emptyList(), newGroup))
        assertEquals(newGroup, selectableSupersetIds(emptyList(), newGroup).single())

        val savedSets = listOf(set(id = 1, supersetId = newGroup))
        assertEquals(listOf("A"), selectableSupersetIds(savedSets, selectedSupersetId = null))
        assertEquals("B", nextSupersetId(savedSets))
    }

    @Test
    fun `PR event labels identify each exercise when events are mixed`() {
        val exercises = listOf(
            exercise(id = 1, name = "深蹲"),
            exercise(id = 2, name = "卧推"),
        )
        val squat = personalRecord(exerciseId = 1, setId = 11)
        val bench = personalRecord(exerciseId = 2, setId = 12)

        assertTrue(prEventDisplayLabel(squat, exercises).startsWith("深蹲 · 刷新纪录"))
        assertTrue(prEventDisplayLabel(bench, exercises).startsWith("卧推 · 刷新纪录"))
    }

    @Test
    fun `all primary exercises are returned without a six item display cap`() {
        val exercises = (1L..8L).map { id -> exercise(id = id, name = "主要动作$id") } +
            exercise(id = 9, name = "普通动作", isPrimary = false)

        assertEquals((1L..8L).toList(), primaryExercisesForDisplay(exercises).map { it.id })
    }

    @Test
    fun `onboarding decimal validation accepts comma and reports field range`() {
        assertNull(boundedDecimalError(" 180,5 ", "身高", 100.0, 250.0, "100–250 cm"))

        val empty = boundedDecimalError("", "身高", 100.0, 250.0, "100–250 cm")
        val outOfRange = boundedDecimalError("99.9", "身高", 100.0, 250.0, "100–250 cm")
        val nonFinite = boundedDecimalError("NaN", "身高", 100.0, 250.0, "100–250 cm")

        assertTrue(requireNotNull(empty).contains("身高"))
        assertTrue(empty.contains("100–250 cm"))
        assertTrue(requireNotNull(outOfRange).contains("100–250 cm"))
        assertNotNull(nonFinite)
    }

    @Test
    fun `exercise duplicate validation ignores case and repeated unicode whitespace`() {
        val error = customExerciseNameError(
            name = "  ROMANIAN\u3000deadlift  ",
            existingNames = listOf("Romanian   Deadlift"),
        )

        assertTrue(requireNotNull(error).contains("同名"))
        assertEquals("romanian deadlift", normalizeExerciseName(" Romanian\u3000  Deadlift "))
        assertEquals("Romanian Deadlift", collapseExerciseNameWhitespace(" Romanian\u3000  Deadlift "))
    }

    @Test
    fun `exercise name and category limits count unicode code points not utf16 units`() {
        val supplementaryCodePoint = "\uD83D\uDCAA"

        assertNull(customExerciseNameError(supplementaryCodePoint.repeat(60), emptyList()))
        assertNotNull(customExerciseNameError(supplementaryCodePoint.repeat(61), emptyList()))
        assertNull(exerciseCategoryError(supplementaryCodePoint.repeat(30)))
        assertNotNull(exerciseCategoryError(supplementaryCodePoint.repeat(31)))
    }

    @Test
    fun `active workout copy partitions total working warmup and failed sets`() {
        val sets = listOf(
            set(id = 1),
            set(id = 2),
            set(id = 3, isWarmup = true),
            set(id = 4, completed = false),
            set(id = 5, completed = false, isWarmup = true),
        )

        assertEquals(
            WorkoutSetBreakdown(total = 5, completedWorking = 2, warmup = 1, failed = 2),
            workoutSetBreakdown(sets),
        )
        assertEquals(
            "共记录 5 组：正式完成 2 / 热身 1 / 失败 2",
            workoutSetSummaryLabel(sets),
        )
        val message = workoutCompletionMessage(sets)
        assertTrue(message.contains(workoutSetSummaryLabel(sets)))
        assertTrue(message.contains("正式完成组进入 PR 候选"))
    }

    @Test
    fun `today workout card shows start state when no workout exists`() {
        assertEquals(
            TodayWorkoutCardModel(
                title = "今天：开始一次力量训练",
                detail = "动作库、自定义动作和主要动作 PR 已就绪",
                action = "开始",
            ),
            todayWorkoutCardModel(
                activeWorkout = null,
                activeSets = emptyList(),
                completedToday = emptyList(),
            ),
        )
    }

    @Test
    fun `today workout card shows active state with set breakdown`() {
        val activeSets = listOf(
            set(id = 1),
            set(id = 2, isWarmup = true),
            set(id = 3, completed = false),
        )

        assertEquals(
            TodayWorkoutCardModel(
                title = "训练进行中：腿部训练",
                detail = "共记录 3 组：正式完成 1 / 热身 1 / 失败 1",
                action = "继续",
            ),
            todayWorkoutCardModel(
                activeWorkout = workoutSession(id = 1, title = "腿部训练"),
                activeSets = activeSets,
                completedToday = emptyList(),
            ),
        )
    }

    @Test
    fun `today workout card shows completed state for one session`() {
        val completed = completedWorkout(
            id = 1,
            title = "推举训练",
            completedSetCount = 4,
            totalVolumeKg = 960.0,
            durationSeconds = 65,
        )

        assertEquals(
            TodayWorkoutCardModel(
                title = "今日训练已完成：推举训练",
                detail = "4 个正式完成组 · 负重容量 960 kg · 用时 1分5秒",
                action = "查看",
            ),
            todayWorkoutCardModel(
                activeWorkout = null,
                activeSets = emptyList(),
                completedToday = listOf(completed),
            ),
        )
    }

    @Test
    fun `today workout card keeps active state after an earlier completion`() {
        val completed = completedWorkout(
            id = 1,
            title = "晨练",
            completedSetCount = 3,
            totalVolumeKg = 600.0,
            durationSeconds = 1_200,
        )

        assertEquals(
            TodayWorkoutCardModel(
                title = "训练进行中：晚间训练",
                detail = "共记录 1 组：正式完成 1 / 热身 0 / 失败 0 · 今日另已完成 1 场",
                action = "继续",
            ),
            todayWorkoutCardModel(
                activeWorkout = workoutSession(id = 2, title = "晚间训练"),
                activeSets = listOf(set(id = 4)),
                completedToday = listOf(completed),
            ),
        )
    }

    @Test
    fun `today workout card aggregates all completed sessions`() {
        val completed = listOf(
            completedWorkout(
                id = 1,
                title = "晨练",
                completedSetCount = 3,
                failedSetCount = 1,
                totalVolumeKg = 1_200.0,
                durationSeconds = 125,
            ),
            completedWorkout(
                id = 2,
                title = "晚练",
                completedSetCount = 2,
                failedSetCount = 2,
                totalVolumeKg = 345.0,
                durationSeconds = 3_595,
            ),
        )

        assertEquals(
            TodayWorkoutCardModel(
                title = "今日已完成 2 场训练",
                detail = "5 个正式完成组 · 失败 3 组 · 负重容量 1545 kg · 用时 1时2分",
                action = "查看",
            ),
            todayWorkoutCardModel(
                activeWorkout = null,
                activeSets = emptyList(),
                completedToday = completed,
            ),
        )
    }

    @Test
    fun `analysis token label matches whether blank input retains or replaces token`() {
        val savedEndpoint = "https://proxy.example/analyze"

        assertEquals(
            "新的代理访问令牌（可留空保留）",
            analysisTokenFieldLabel(savedEndpoint, savedEndpoint, tokenConfigured = true),
        )
        assertEquals(
            "新的代理访问令牌（更换地址时必填）",
            analysisTokenFieldLabel("https://new.example/analyze", savedEndpoint, tokenConfigured = true),
        )
        assertEquals(
            "代理访问令牌（填写地址时必填）",
            analysisTokenFieldLabel("https://proxy.example/analyze", "", tokenConfigured = false),
        )
        assertEquals(
            "代理访问令牌（无需填写）",
            analysisTokenFieldLabel("", savedEndpoint, tokenConfigured = true),
        )
    }

    private fun set(
        id: Long,
        completed: Boolean = true,
        isWarmup: Boolean = false,
        supersetId: String? = null,
    ) = WorkoutSet(
        id = id,
        exerciseId = 1,
        setOrder = id.toInt(),
        loadGrams = 80_000,
        reps = if (completed) 8 else 0,
        completed = completed,
        isWarmup = isWarmup,
        supersetId = supersetId,
        commitId = "test-$id",
    )

    private fun exercise(
        id: Long,
        name: String,
        isPrimary: Boolean = true,
    ) = Exercise(
        id = id,
        name = name,
        category = "测试",
        isCustom = false,
        isPrimary = isPrimary,
        trackingType = TrackingType.WEIGHT_REPS,
    )

    private fun workoutSession(
        id: Long,
        title: String,
        startedAtMillis: Long = id * 10_000L,
        endedAtMillis: Long? = null,
    ) = WorkoutSession(
        id = id,
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis,
        title = title,
        status = if (endedAtMillis == null) WorkoutStatus.DRAFT else WorkoutStatus.COMPLETED,
    )

    private fun completedWorkout(
        id: Long,
        title: String,
        completedSetCount: Int,
        totalVolumeKg: Double,
        durationSeconds: Long,
        failedSetCount: Int = 0,
    ): WorkoutHistorySummary {
        val startedAtMillis = id * 10_000L
        return WorkoutHistorySummary(
            session = workoutSession(
                id = id,
                title = title,
                startedAtMillis = startedAtMillis,
                endedAtMillis = startedAtMillis + durationSeconds * 1_000L,
            ),
            exerciseNames = emptyList(),
            completedSetCount = completedSetCount,
            totalVolumeKg = totalVolumeKg,
            failedSetCount = failedSetCount,
        )
    }

    private fun personalRecord(
        exerciseId: Long,
        setId: Long,
    ) = PersonalRecord(
        exerciseId = exerciseId,
        setId = setId,
        type = PrType.MAX_WEIGHT,
        value = 100.0,
        weightKg = 100.0,
        reps = 5,
        achievedAtMillis = 1L,
        eventKind = PrEventKind.BROKEN,
    )
}
