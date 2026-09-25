package com.michalkrsik.internetrestrictor.ui

import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import com.michalkrsik.internetrestrictor.ui.components.drawExercisePointTrack
import org.junit.Test
import org.junit.Assert.*

/** Records drawing commands only. No screen, bitmap, or rendering surface is created. */
class ExerciseTrackGeometryTest {
    private data class Line(val color: Color, val start: Offset, val end: Offset, val width: Float, val cap: StrokeCap)
    @Test fun fillLengthTracksProgressAndAllSixTickMarksStayAligned() {
        for (progress in listOf(0f, .5f, 1f)) {
            val lines = mutableListOf<Line>()
            val base = CanvasDrawScope()
            base.drawContext.size = Size(100f, 26f)
            val recorder = object : DrawScope by base {
                override fun drawLine(color: Color, start: Offset, end: Offset, strokeWidth: Float, cap: StrokeCap,
                    pathEffect: PathEffect?, alpha: Float, colorFilter: ColorFilter?, blendMode: BlendMode) {
                    lines += Line(color, start, end, strokeWidth, cap)
                }
            }
            recorder.drawExercisePointTrack(progress, Color.Gray, Color.Black, Color.Blue)
            assertEquals(Line(Color.Gray, Offset(0f, 13f), Offset(100f, 13f), 6f, StrokeCap.Round), lines.first())
            val fills = lines.filter { it.color == Color.Blue }
            if (progress == 0f) assertTrue(fills.isEmpty())
            else assertEquals(Line(Color.Blue, Offset(0f, 13f), Offset(100f * progress, 13f), 6f, StrokeCap.Round), fills.single())
            val ticks = lines.filter { it.color == Color.Black }
            assertEquals(6, ticks.size)
            ticks.forEachIndexed { index, line ->
                assertEquals(Offset(index * 20f, 6f), line.start)
                assertEquals(Offset(index * 20f, 20f), line.end)
                assertEquals(1f, line.width)
            }
        }
    }
}
