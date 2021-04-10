package info.nightscout.comboctl.base

import kotlin.math.min

/**
 * Node of a [Tree] structure.
 *
 * See the [Tree] documentation on how to create nodes.
 *
 * @property value The value (that is, the payload) of a node.
 *           These must be unique in the tree.
 * @property parent This node's parent node, or null if there is no parent.
 */
class TreeNode<Value>(
    val value: Value,
    val parent: TreeNode<Value>? = null
) {
    private val mutableChildList = mutableListOf<TreeNode<Value>>()

    /**
     * All children of this node.
     */
    val children: List<TreeNode<Value>> = mutableChildList

    /**
     * Creates a child node.
     *
     * See the [Tree] documentation on how to create child nodes using this function.
     *
     * @param value Value of the new child node.
     * @param block Function literal with receiver to run on the child node.
     * @return The newly created child node.
     */
    fun child(
        value: Value,
        block: TreeNode<Value>.() -> Unit = { }
    ): TreeNode<Value> {
        val child = TreeNode(value, parent = this)
        mutableChildList.add(child)
        block(child)
        return child
    }
}

/**
 * Class for creating a tree structure.
 *
 * Each node in the tree has a value. This includes the root node,
 * which is created inside the tree's constructor. Values must be
 * unique, that is, no two nodes may have the same value. (They are
 * matched by equality comparison, not by reference comparison.)
 *
 * Trees are constructed by using the [TreeNode.child] function
 * in the function literal argument, like this:
 *
 * ```
 * val tree = Tree<String>("rootNodeString", {
 *     child("child1")
 *     child("child2")
 *     child("child3")
 * }
 * ```
 *
 * Children themselves can have children. This can be done by either using
 * function literals or by using the return value of the child function:
 *
 * ```
 * val tree = Tree<String>("rootNodeString", {
 *     // This is functionally equivalent to child("child1") { child("subchild1.1") }
 *     child("child1")
 *         .child("subchild1.1")
 *     child("child2") {
 *         child("subchild2.1")
 *         child("subchild2.2")
 *     }
 *     child("child3")
 * }
 * ```
 *
 * The ability to declare a child by typing ".child" is there for convenience.
 * It is especially useful if there are tree branches with only single children:
 *
 * ```
 * val tree = Tree<String>("rootNodeString", {
 *     // This is functionally equivalent to child("child1") { child("subchild1.1") }
 *     child("child1")
 *         .child("subchild1.1")
 *             .child("subchild1.1.1")
 *                 .child("subchild1.1.1.1")
 * }
 * ```
 *
 * This would be more verbose with the function literal notation.
 *
 * @param rootValue Node value that is assigned to the root node.
 * @param rootNodeBlock Functional literal with the root node being the receiver.
 *        Used for tree construction as described above.
 */
class Tree<Value>(rootValue: Value, rootNodeBlock: TreeNode<Value>.() -> Unit = { }) {
    /**
     * The tree's root node.
     *
     * This node always has its parent property set to null.
     * It is the only node in the tree that is allowed to
     * have a null parent.
     */
    val rootNode = TreeNode(rootValue, parent = null)

    /**
     * List of all nodes in the tree (including the root node).
     *
     * This is useful for looking up a node by value, which is needed
     * during pathfinding for example.
     */
    val nodes: Map<Value, TreeNode<Value>>

    init {
        rootNodeBlock(rootNode)
        nodes = mutableMapOf(Pair(rootNode.value, rootNode))

        // We use a trick here to walk through all nodes and their children.
        // The depth first search only stops when the function literal
        // returns true. We just always returns false and record the nodes
        // we've seen, resulting in a full walk through the entire tree.
        depthFirstSearch(rootNode) { node ->
            nodes[node.value] = node
            false
        }
    }
}

/**
 * Depth first search algorithm implementation.
 *
 * @param treeNode Tree node to start the search at. This node itself and
 *        all of its child nodes are visited.
 * @param predicate Predicate to apply to each visited node. The node's value
 *        is passed to the predicate. If it returns true, the searched value
 *        is considered to be found, and the search stops. If it returns value,
 *        the search continues.
 * @return The found value, or null if the search was unsuccessful (= the
 *         predicate never returned true).
 */
fun <Value> depthFirstSearch(treeNode: TreeNode<Value>, predicate: (treeNode: TreeNode<Value>) -> Boolean): Value? {
    if (predicate(treeNode))
        return treeNode.value

    for (child in treeNode.children)
        return depthFirstSearch(child, predicate) ?: continue

    return null
}

/**
 * Depth first search algorithm implementation.
 *
 * This is an overload for convenience. calls [depthFirstSearch] on the tree's
 * root node to search the entire tree.
 */
fun <Value> depthFirstSearch(tree: Tree<Value>, predicate: (treeNode: TreeNode<Value>) -> Boolean): Value? =
    depthFirstSearch(tree.rootNode, predicate)

enum class ShortestPathHalf { FIRST_HALF, SECOND_HALF }

/**
 * Finds the shortest path between nodes with the given values in the tree.
 *
 * This is a simple pathfinding algorithm that determines the last common ancestor
 * node between the branches that lead to the nodes with [startValue] and [endValue],
 * and then computes a path from the [startValue] to that ancestor (this is the first
 * half of the path) and then from the ancestor to the node with the [endValue] (the
 * second half of the path).
 *
 * On each node of that path, the given [transform] is applied. That block's return
 * value is a value that is stored in a list which is then the overall return value
 * of this path finding function. The transform is passed the value of the current
 * as well as the value of the next node in the path, along with a specifier about
 * what half of the path this node belongs to. That specifier is useful for the
 * [transform], since operations in that function literal may depend on the general
 * "direction" of the current traversal in the tree. For the very last item in the
 * path, the nextValue and [nextShortestPathHalf] arguments of [transform] will be
 * set to null.
 *
 * @param tree Tree to find the shortest path in.
 * @param startValue Value of the node the path shall start at.
 * @param endValue Value of the node the path shall end at.
 * @param transform Transform function literal to apply to each node in the path.
 * @throws IllegalArgumentException if the startValue or the endValue (or both)
 *         are not present in any of the tree's nodes.
 */
fun <Value, Result> findShortestPath(
    tree: Tree<Value>,
    startValue: Value,
    endValue: Value,
    transform: (value: Value, shortestPathHalf: ShortestPathHalf, nextValue: Value?, nextShortestPathHalf: ShortestPathHalf?) -> Result
): List<Result> {
    val startNode = tree.nodes[startValue] ?: throw IllegalArgumentException("There is no node with start value \"$startValue\" in tree")
    val endNode = tree.nodes[endValue] ?: throw IllegalArgumentException("There is no node with end value \"$endValue\" in tree")

    // Find the paths from the root node to the start and end nodes
    // so we can determine the last common ancestor between them.

    val rootToStartNodePath = mutableListOf<TreeNode<Value>>()
    val rootToEndNodePath = mutableListOf<TreeNode<Value>>()

    var currentNode: TreeNode<Value>? = startNode
    while (currentNode != null) {
        rootToStartNodePath.add(0, currentNode)
        currentNode = currentNode.parent
    }

    currentNode = endNode
    while (currentNode != null) {
        rootToEndNodePath.add(0, currentNode)
        currentNode = currentNode.parent
    }

    // Compare the nodes of the two paths side by side until the
    // first non-matching node is found. That's where the branch
    // that separates the two nodes is present. There's the last
    // common ancestor.
    var lastCommonAncestorIndex = 0
    for (i in 1 until min(rootToStartNodePath.size, rootToEndNodePath.size)) {
        if (rootToStartNodePath[i].value === rootToEndNodePath[i].value)
            lastCommonAncestorIndex = i
        else
            break
    }

    // Factor out all ancestors except the last one. Also, we
    // reverse the rootToStartNodeSubpath, since the overall path
    // will go _from_ startNode to the node right before the
    // lowest common ancestor. The ancestor itself is _not_ part
    // of rootToStartNodeSubpath, since it already is path of
    // rootToEndNodeSubpath.
    val rootToStartNodeSubpath = rootToStartNodePath.subList(lastCommonAncestorIndex + 1, rootToStartNodePath.size).reversed()
    val rootToEndNodeSubpath = rootToEndNodePath.subList(lastCommonAncestorIndex, rootToEndNodePath.size)

    val combinedPath = rootToStartNodeSubpath.map { node -> Pair(node, ShortestPathHalf.FIRST_HALF) } +
            rootToEndNodeSubpath.map { node -> Pair(node, ShortestPathHalf.SECOND_HALF) }

    return combinedPath.mapIndexed { index, nodeInfo ->
        val nextNodeInfo = if ((index + 1) < combinedPath.size)
            combinedPath[index + 1]
        else
            null

        transform(
            nodeInfo.first.value,
            nodeInfo.second,
            nextNodeInfo?.first?.value,
            nextNodeInfo?.second
        )
    }
}
