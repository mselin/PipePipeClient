package org.schabi.newpipe.enhancements.playermemory

/** The numbers of a storyboard frameset that decide which frame belongs to which position. */
data class FramesetShape(
    val pageCount: Int,
    val totalCount: Int,
    val durationPerFrameMs: Int,
    val framesPerPageX: Int,
    val framesPerPageY: Int
)

/** A preview frame: the sheet it is on and the stream position it stands for. */
data class FrameRef(val pageIndex: Int, val positionMs: Int)

/**
 * Which preview frame is shown for a seekbar position, without any image loaded.
 *
 * It holds the frames the upstream preview holder would have generated: of at most [MAX_PAGES]
 * evenly sampled sheets, every frame that lies within the video and whose position fits an Int.
 * The frame for a position is the one with the nearest position, the earlier one on a tie.
 */
class SeekbarFrameTable(shape: FramesetShape, maxPages: Int = MAX_PAGES) {
    /** Every [pageStep]-th sheet is used. */
    val pageStep: Int = Math.max(1, Math.ceil(shape.pageCount.toDouble() / maxPages).toInt())

    /** The indexes of the sheets that are used, ascending. */
    val sampledPages: List<Int> = (0 until shape.pageCount step pageStep).toList()

    // Parallel arrays, ascending by position: the position of a frame and the sheet it is on.
    private val positions: IntArray
    private val pages: IntArray

    init {
        val framesPerPage = shape.framesPerPageX * shape.framesPerPageY
        val positionList = ArrayList<Int>()
        val pageList = ArrayList<Int>()
        for (page in sampledPages) {
            for (i in 0 until framesPerPage) {
                val number = page.toLong() * framesPerPage + i
                val position = number * shape.durationPerFrameMs
                // Frames outside the video length are skipped, as are positions beyond an Int.
                if (number + 1 > shape.totalCount || position > Int.MAX_VALUE) {
                    break
                }
                positionList.add(position.toInt())
                pageList.add(page)
            }
        }
        positions = positionList.toIntArray()
        pages = pageList.toIntArray()
    }

    /** The frame nearest to [positionMs] on any sampled sheet, or null when there is no frame. */
    fun nearest(positionMs: Int): FrameRef? = scan(positionMs, null)

    /** The frame nearest to [positionMs] among the frames on the sheets [pages], or null. */
    fun nearestAmong(positionMs: Int, pages: Set<Int>): FrameRef? =
        if (pages.isEmpty()) null else scan(positionMs, pages)

    private fun scan(positionMs: Int, among: Set<Int>?): FrameRef? {
        var best = -1
        var bestDistance = Long.MAX_VALUE
        // The sheet of the previous frame and whether it is wanted: frames of one sheet follow
        // each other, so the set is asked once per sheet and not once per frame.
        var lastPage = -1
        var lastPageWanted = false
        for (i in positions.indices) {
            if (among != null) {
                if (pages[i] != lastPage) {
                    lastPage = pages[i]
                    lastPageWanted = lastPage in among
                }
                if (!lastPageWanted) {
                    continue
                }
            }
            val distance = Math.abs(positions[i].toLong() - positionMs)
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return if (best < 0) null else FrameRef(pages[best], positions[best])
    }

    companion object {
        /** At most this many sheets of an item are ever used, as upstream. */
        const val MAX_PAGES = 48
    }
}
