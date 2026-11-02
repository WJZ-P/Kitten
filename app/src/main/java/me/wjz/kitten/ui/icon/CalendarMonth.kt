package me.wjz.kitten.ui.icon

import androidx.compose.ui.graphics.vector.ImageVector

/** 月历：设置页"Token 看板"那一行（看板是按天的日历格子）。Material Symbols 的 calendar_month。 */
val KittenIcons.CalendarMonth: ImageVector
    get() = calendarMonth ?: materialSymbol24(
        name = "CalendarMonth",
        pathData = "M5,22q-0.83,0-1.41-0.59T3,20V6Q3,5.18 3.59,4.59T5,4H6V2H8V4h8V2h2V4h1q0.83,0 1.41,0.59Q21,5.18 " +
            "21,6V20q0,0.82-0.59,1.41T19,22H5Zm0-2H19V10H5V20ZM5,8H19V6H5V8ZM5,8V6V8Zm7,6q-0.42,0-0.71-0.29Q11," +
            "13.43 11,13t0.29-0.71T12,12t0.71,0.29T13,13t-0.29,0.71T12,14ZM7.29,13.71Q7,13.43 7,13t0.29-0.71T8," +
            "12t0.71,0.29T9,13T8.71,13.71T8,14q-0.42,0-0.71-0.29ZM16,14q-0.42,0-0.71-0.29Q15,13.43 15,13t0.29-0.71" +
            "T16,12q0.43,0 0.71,0.29T17,13t-0.29,0.71T16,14Zm-4,4q-0.42,0-0.71-0.29Q11,17.43 11,17t0.29-0.71T12,16" +
            "t0.71,0.29T13,17t-0.29,0.71T12,18ZM7.29,17.71Q7,17.43 7,17t0.29-0.71T8,16t0.71,0.29T9,17T8.71,17.71" +
            "T8,18q-0.42,0-0.71-0.29ZM16,18q-0.42,0-0.71-0.29Q15,17.43 15,17t0.29-0.71T16,16q0.43,0 0.71,0.29" +
            "T17,17t-0.29,0.71T16,18Z",
    ).also { calendarMonth = it }

private var calendarMonth: ImageVector? = null
