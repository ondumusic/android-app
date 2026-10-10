package com.ondumusic.player.service.outbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/**
 * The server rejects the same item twice in one request, and batching two drags of one
 * track produces exactly that.
 */
class ItemOpDeduplicationTest {
    private fun move(id: UUID, position: String) = MoveItemsPayload.Entry(id, position)

    @Test
    fun keepsOnlyTheLastMoveOfAnItem() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val entries = listOf(move(a, "a0"), move(b, "a1"), move(a, "a2"))

        val kept = OutboxService.lastPerItem(entries)

        assertEquals(listOf("a1", "a2"), kept.map { it.position })
    }

    @Test
    fun leavesDistinctMovesAlone() {
        val entries = listOf(move(UUID.randomUUID(), "a0"), move(UUID.randomUUID(), "a1"))
        assertEquals(2, OutboxService.lastPerItem(entries).size)
    }

    @Test
    fun dropsRepeatedRemovals() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        assertEquals(listOf(a, b), OutboxService.deduplicated(listOf(a, b, a)))
    }
}
