package com.planterior.helper.feature.minihome

import kotlin.math.floor

/** Pixel-space projection used by floor drawing, placement anchoring, drag inversion, and depth. */
data class MiniHomePoint(val x: Float, val y: Float)

data class MiniHomeDiamond(
    val back: MiniHomePoint,
    val right: MiniHomePoint,
    val front: MiniHomePoint,
    val left: MiniHomePoint,
) {
    fun contains(point: MiniHomePoint): Boolean {
        val centerX = (left.x + right.x) / 2f
        val centerY = (back.y + front.y) / 2f
        val radiusX = (right.x - left.x) / 2f
        val radiusY = (front.y - back.y) / 2f
        if (radiusX <= 0f || radiusY <= 0f) return false
        return kotlin.math.abs(point.x - centerX) / radiusX +
            kotlin.math.abs(point.y - centerY) / radiusY <= 1.00001f
    }
}

/**
 * Canonical 5x4 isometric transform.
 *
 * Logical column/row fractions are projected along the two diamond axes. The inverse solves the
 * same transform, so resize, density, and font scale cannot introduce a second coordinate system.
 */
class MiniHomeIsometricProjection(val width: Float, val height: Float) {
    init {
        require(width > 0f && width.isFinite())
        require(height > 0f && height.isFinite())
    }

    val floorTop: Float = height * FLOOR_TOP_RATIO
    val floorHeight: Float = height * FLOOR_HEIGHT_RATIO
    val floorBounds: MiniHomeDiamond =
        MiniHomeDiamond(
            pointAt(0f, 0f),
            pointAt(1f, 0f),
            pointAt(1f, 1f),
            pointAt(0f, 1f),
        )

    val miniatureWidth: Float = width * MINIATURE_WIDTH_RATIO
    val miniatureHeight: Float = height * MINIATURE_HEIGHT_RATIO

    fun pointAt(columnFraction: Float, rowFraction: Float): MiniHomePoint =
        MiniHomePoint(
            x = width / 2f + width / 2f * (columnFraction - rowFraction),
            y = floorTop + floorHeight / 2f * (columnFraction + rowFraction),
        )

    fun cellCenter(position: GridPosition): MiniHomePoint =
        pointAt(
            (position.column + 0.5f) / MiniHomeGrid.COLUMNS,
            (position.row + 0.5f) / MiniHomeGrid.ROWS,
        )

    fun cell(position: GridPosition): MiniHomeDiamond {
        val left = position.column.toFloat() / MiniHomeGrid.COLUMNS
        val right = (position.column + 1f) / MiniHomeGrid.COLUMNS
        val back = position.row.toFloat() / MiniHomeGrid.ROWS
        val front = (position.row + 1f) / MiniHomeGrid.ROWS
        return MiniHomeDiamond(
            pointAt(left, back),
            pointAt(right, back),
            pointAt(right, front),
            pointAt(left, front),
        )
    }

    fun positionAt(point: MiniHomePoint): GridPosition {
        val difference = 2f * (point.x - width / 2f) / width
        val sum = 2f * (point.y - floorTop) / floorHeight
        val columnFraction = (sum + difference) / 2f
        val rowFraction = (sum - difference) / 2f
        return GridPosition(
            floor(columnFraction * MiniHomeGrid.COLUMNS)
                .toInt()
                .coerceIn(0, MiniHomeGrid.COLUMNS - 1),
            floor(rowFraction * MiniHomeGrid.ROWS).toInt().coerceIn(0, MiniHomeGrid.ROWS - 1),
        )
    }

    companion object {
        const val FLOOR_TOP_RATIO = 0.30f
        const val FLOOR_HEIGHT_RATIO = 0.62f
        const val MINIATURE_WIDTH_RATIO = 0.13f
        const val MINIATURE_HEIGHT_RATIO = 0.22f

        /** Integer-equivalent projected Y depth, avoiding float ordering differences on clients. */
        fun depth(position: GridPosition): Int =
            (2 * position.column + 1) * MiniHomeGrid.ROWS +
                (2 * position.row + 1) * MiniHomeGrid.COLUMNS

        fun horizontal(position: GridPosition): Int =
            (2 * position.column + 1) * MiniHomeGrid.ROWS -
                (2 * position.row + 1) * MiniHomeGrid.COLUMNS
    }
}
