package com.github.sybila.local

class ComponentStore<S, T: Any>(
        private val solver: Solver<T>
) {

    private val components: MutableMap<S, T> = HashMap()

    fun push(component: Map<S, T>, bound: T) {
        synchronized(this) {
            solver.run {
                for ((k, v) in component.entries) {
                    components[k] = union(components.getOrDefault(k, emptySet), intersect(v, bound))
                }
            }
        }
    }

    fun getComponentMapping(count: Count<T>): List<Map<S, T>> {
        solver.run {
            val result = ArrayList<Map<S, T>>(count.size)
            for (c in 0 until count.size) {
                val levelParams = count[c]
                val levelMap = components.map { (k, v) -> k to intersect(v, levelParams) }.filter { !isEmpty(it.second) }.toMap()
                result.add(levelMap)
            }
            return result
        }
    }

}