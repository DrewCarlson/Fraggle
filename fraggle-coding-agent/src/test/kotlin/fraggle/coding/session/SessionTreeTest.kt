package fraggle.coding.session

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTreeTest {

    @Nested
    inner class Lookup {
        @Test
        fun `empty tree has no root and no size`() {
            val tree = SessionTree.EMPTY
            assertNull(tree.root)
            assertEquals(0, tree.size)
            assertEquals(emptyList(), tree.currentBranch())
            assertEquals(emptyList(), tree.leaves())
        }

        @Test
        fun `single entry is both root and leaf`() {
            val tree = SessionTree.of(listOf(entry("a")))
            assertEquals("a", tree.root?.id)
            assertEquals(listOf("a"), tree.leaves().map { it.id })
            assertEquals(listOf("a"), tree.currentBranch().map { it.id })
        }

        @Test
        fun `find returns entry by id and null for missing`() {
            val tree = SessionTree.of(listOf(entry("a"), entry("b", "a")))
            assertEquals("a", tree.find("a")?.id)
            assertEquals("b", tree.find("b")?.id)
            assertNull(tree.find("c"))
        }
    }

    @Nested
    inner class Branches {
        @Test
        fun `linear session walks to root`() {
            val tree = SessionTree.of(
                listOf(entry("a"), entry("b", "a"), entry("c", "b"), entry("d", "c")),
            )
            val branch = tree.branchTo(tree.find("d")!!)
            assertEquals(listOf("a", "b", "c", "d"), branch.map { it.id })
        }

        @Test
        fun `branching tree — currentBranch follows latest entry`() {
            // Structure:     a
            //               / \
            //              b   d
            //              |   |
            //              c   e
            //
            // Entries written in order: a, b, c, d, e. currentBranch should
            // follow the tip (e), walking back through d to a.
            val tree = SessionTree.of(
                listOf(
                    entry("a"),
                    entry("b", "a"),
                    entry("c", "b"),
                    entry("d", "a"),
                    entry("e", "d"),
                ),
            )
            assertEquals(listOf("a", "d", "e"), tree.currentBranch().map { it.id })
        }

        @Test
        fun `branchTo a specific branch point returns that branch`() {
            val tree = SessionTree.of(
                listOf(
                    entry("a"),
                    entry("b", "a"),
                    entry("c", "b"),
                    entry("d", "a"),
                ),
            )
            // Walking from c gives a → b → c
            assertEquals(listOf("a", "b", "c"), tree.branchTo(tree.find("c")!!).map { it.id })
            // Walking from d gives a → d
            assertEquals(listOf("a", "d"), tree.branchTo(tree.find("d")!!).map { it.id })
        }

        @Test
        fun `leaves returns entries with no children`() {
            val tree = SessionTree.of(
                listOf(
                    entry("a"),
                    entry("b", "a"),
                    entry("c", "b"),    // leaf
                    entry("d", "a"),    // leaf
                ),
            )
            assertEquals(setOf("c", "d"), tree.leaves().map { it.id }.toSet())
        }
    }

    @Nested
    inner class AppendAndIntegrity {
        @Test
        fun `append returns a new tree without mutating the original`() {
            val original = SessionTree.of(listOf(entry("a")))
            val extended = original.append(entry("b", "a"))

            assertEquals(1, original.size)
            assertEquals(2, extended.size)
            assertEquals(listOf("a", "b"), extended.currentBranch().map { it.id })
        }

        @Test
        fun `branchTo throws on dangling parentId`() {
            // "b" references a non-existent parent "ghost"
            val tree = SessionTree.of(listOf(entry("b", "ghost")))
            assertThrows<IllegalStateException> {
                tree.branchTo(tree.find("b")!!)
            }
        }

        @Test
        fun `root throws when multiple roots exist`() {
            val tree = SessionTree.of(listOf(entry("a"), entry("b")))
            assertThrows<IllegalStateException> { tree.root }
        }

        @Test
        fun `branchTo throws when leaf is not in the tree`() {
            val tree = SessionTree.of(listOf(entry("a")))
            val outsider = entry("outsider")
            assertThrows<IllegalStateException> { tree.branchTo(outsider) }
        }
    }

    private fun entry(id: String, parentId: String? = null): SessionEntry =
        SessionEntry(
            id = id,
            parentId = parentId,
            timestampMs = 0L,
            payload = SessionEntry.Payload.User(text = id),
        )
}
