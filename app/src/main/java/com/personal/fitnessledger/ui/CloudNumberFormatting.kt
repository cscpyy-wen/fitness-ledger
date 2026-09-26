package com.personal.fitnessledger.ui

/** Keep original 0.05 kg resolution instead of making a 70.25 reading look like 70.3. */
internal fun formatCloudNumber(value: Double): String = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
