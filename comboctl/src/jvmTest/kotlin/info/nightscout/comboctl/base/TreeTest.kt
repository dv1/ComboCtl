package info.nightscout.comboctl.base

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TreeTest {
    private fun createTestTree() = Tree<String>(rootValue = "rootValue") {
        child("child1")
            .child("child1.1")
            .child("child1.1.1")
        child("child2") {
            child("child2.1")
                .child("child2.1.1")
            child("child2.2")
        }
    }

    @Test
    fun checkTreeConstruction() {
        val tree = createTestTree()

        assertEquals(8, tree.nodes.size)

        assertNotNull(tree.nodes["rootValue"])
        assertNotNull(tree.nodes["child1"])
        assertNotNull(tree.nodes["child1.1"])
        assertNotNull(tree.nodes["child1.1.1"])
        assertNotNull(tree.nodes["child2"])
        assertNotNull(tree.nodes["child2.1"])
        assertNotNull(tree.nodes["child2.1.1"])
        assertNotNull(tree.nodes["child2.2"])

        assertNull(tree.nodes["rootValue"]!!.parent)
        assertEquals(tree.nodes["rootValue"], tree.nodes["child1"]!!.parent)
        assertEquals(tree.nodes["rootValue"], tree.nodes["child2"]!!.parent)
        assertEquals(tree.nodes["child1"], tree.nodes["child1.1"]!!.parent)
        assertEquals(tree.nodes["child1.1"], tree.nodes["child1.1.1"]!!.parent)
        assertEquals(tree.nodes["child2"], tree.nodes["child2.1"]!!.parent)
        assertEquals(tree.nodes["child2.1"], tree.nodes["child2.1.1"]!!.parent)
        assertEquals(tree.nodes["child2"], tree.nodes["child2.2"]!!.parent)
    }

    @Test
    fun checkDepthFirstSearch() {
        val tree = createTestTree()

        val searchResult1 = depthFirstSearch(tree) { it.value == "child2.2" }
        assertEquals("child2.2", searchResult1)

        val searchResult2 = depthFirstSearch(tree) { it.value == "invalidValue" }
        assertNull(searchResult2)
    }

    data class ShortestPathSegment(
        val value: String,
        val shortestPathHalf: ShortestPathHalf,
        val nextValue: String?,
        val nextShortestPathHalf: ShortestPathHalf?
    )

    @Test
    fun checkShortestPath() {
        val tree = createTestTree()
        val path = findShortestPath(tree, "child2.1.1", "child1.1.1") { value, shortestPathHalf, nextValue, nextShortestPathHalf ->
            ShortestPathSegment(value, shortestPathHalf, nextValue, nextShortestPathHalf)
        }

        assertEquals(7, path.size)

        assertEquals(
            ShortestPathSegment("child2.1.1", ShortestPathHalf.FIRST_HALF, "child2.1", ShortestPathHalf.FIRST_HALF),
            path[0]
        )
        assertEquals(
            ShortestPathSegment("child2.1", ShortestPathHalf.FIRST_HALF, "child2", ShortestPathHalf.FIRST_HALF),
            path[1]
        )
        assertEquals(
            ShortestPathSegment("child2", ShortestPathHalf.FIRST_HALF, "rootValue", ShortestPathHalf.SECOND_HALF),
            path[2]
        )
        assertEquals(
            ShortestPathSegment("rootValue", ShortestPathHalf.SECOND_HALF, "child1", ShortestPathHalf.SECOND_HALF),
            path[3]
        )
        assertEquals(
            ShortestPathSegment("child1", ShortestPathHalf.SECOND_HALF, "child1.1", ShortestPathHalf.SECOND_HALF),
            path[4]
        )
        assertEquals(
            ShortestPathSegment("child1.1", ShortestPathHalf.SECOND_HALF, "child1.1.1", ShortestPathHalf.SECOND_HALF),
            path[5]
        )
        assertEquals(
            ShortestPathSegment("child1.1.1", ShortestPathHalf.SECOND_HALF, null, null),
            path[6]
        )
    }
}
